"""R103 deep, idea 2 (PREREG_DIR.md): a credit spread sold in the direction of Boss's own index signals.

    R51 store needed: IRAALGO_LAKE=<flat lake> R51_OUT=<store dir> python research/hunt/r51/r51data.py build BANKNIFTY NIFTY
    R51_OUT=<store dir> python research/hunt/r103_selling/r103dir.py              # design: results/dir_cells.csv
    R51_OUT=<store dir> python research/hunt/r103_selling/r103dir.py --holdout    # passers only, read once

The signals' timing comes unchanged from research/hunt/r51 (fam_liq: Liquidity 15+5; fam_r44: Donchian 20). Nothing trades.
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r103data as R  # noqa: E402
import r103sim as S  # noqa: E402

RESULTS = HERE / "results"
DESIGN = (date(2024, 11, 20), date(2026, 7, 14))
HOLDOUT = (date(2026, 7, 15), date(2026, 10, 19))
SOURCES = {"LIQ-BN": "BANKNIFTY", "DON-NIFTY": "NIFTY", "DON-BN": "BANKNIFTY"}
PLACEMENTS = {"ATM": 0, "OTM2": 2}
WING = 4
EXITS = ("E1", "E2", "E3")
LOCK = (0.3, 0.2)
FIRST, LAST = R.minute(9, 20), R.minute(14, 0)
DON_HOLD = 30
N_TWIN, SEED = 500, 103
GATE = dict(trades=40, p=0.05, q=0.10, twin=0.05, blocks=3, dd=-40000.0, month=-13000.0)


@dataclass(frozen=True)
class Signal:
    day: date
    er: int            # the entry fill row (the arm's entry minute)
    bull: bool
    arm_exit: int      # E3's square-off fill row (-1: none, use 15:10)


def load_signals(source: str) -> list[Signal]:
    """The arm's own signals (research/hunt/r51), expiry days and entries outside 09:20-14:00 dropped."""
    sys.path.insert(0, str(HERE.parent / "r51"))
    import r51data  # noqa: E402
    und = SOURCES[source]
    days51 = r51data.load(und)
    out = []
    if source == "LIQ-BN":
        import fam_liq  # noqa: E402
        for tf in (15, 5):
            for d, lst in fam_liq.signals(und, tf, 1.0).items():
                for s in lst:
                    fx = fam_liq.forced_row(days51[d], s)
                    out.append(Signal(d, int(s["er"]), s["sgn"] > 0, int(fx)))
    else:
        import fam_r44  # noqa: E402
        for d, lst in fam_r44.signal_list(und, "donchian20").items():
            if days51[d].expiry:
                continue
            for b, side in lst:
                er = 5 * (int(b) + 1)
                out.append(Signal(d, er, side == "C", er + DON_HOLD))
    out = [s for s in out if FIRST <= s.er <= LAST]
    return sorted(set(out), key=lambda s: (s.day, s.er, not s.bull))


def spread_legs(day: R.Day, t: int, bull: bool, offset: int) -> list[S.Leg] | None:
    """Bullish: sell the put `offset` strikes below K0, buy the put WING strikes further down. Bearish: the call mirror."""
    step = R.STEP[day.und]
    s = S.spot_at(day, t)
    if not np.isfinite(s):
        return None
    k0 = round(s / step) * step
    side, sgn = ("P", -1) if bull else ("C", 1)
    k_short, k_long = k0 + sgn * offset * step, k0 + sgn * (offset + WING) * step
    if not (S.traded(day, k_short, side, t) and S.traded(day, k_long, side, t)):
        return None
    T = S.years_left(day.dte)[t]
    ivs = [float(S.implied_vol(S.ffill(day.opts[(k0, sd)][:, R.C])[t], s, k0, T, sd == "C"))
           for sd in ("C", "P") if (k0, sd) in day.opts]
    ivs = [x for x in ivs if np.isfinite(x)]
    iv = float(np.mean(ivs)) if ivs else np.nan
    a = S.leg_arrays(day, k_short, side, offset, True, iv)
    b = S.leg_arrays(day, k_long, side, offset + WING, False, iv)
    return None if a is None or b is None else [a, b]


def one_trade(day: R.Day, sig: Signal, bull: bool, offset: int, exit_: str) -> S.Trade | None:
    t = sig.er - 1
    legs = spread_legs(day, t, bull, offset)
    if legs is None:
        return None
    close = sig.arm_exit if exit_ == "E3" and sig.arm_exit > sig.er else None
    return S.simulate(day, t, "VS", "X3", "T0", legs=legs, lock=LOCK if exit_ == "E2" else None, close_row=close)


def run_cell(days: dict[date, R.Day], sigs: list[Signal], offset: int, exit_: str):
    """Sequential trades (one position at a time) and, for each taken entry, the opposite-side trade (for the twin)."""
    real, flip, busy = [], [], (None, -1)
    for s in sigs:
        d = days.get(s.day)
        if d is None or d.expiry:
            continue
        if busy[0] == s.day and s.er <= busy[1]:
            continue
        tr = one_trade(d, s, s.bull, offset, exit_)
        if tr is None:
            continue
        op = one_trade(d, s, not s.bull, offset, exit_)
        real.append(tr)
        flip.append(op.net if op is not None else np.nan)
        busy = (s.day, tr.exit_row)
    return real, np.array(flip)


def twin_p(real: np.ndarray, flip: np.ndarray, seed: int) -> float:
    """Random-direction twin: each taken entry keeps its own side or takes the opposite at random (500 draws)."""
    ok = np.isfinite(flip)
    r, f = real[ok], flip[ok]
    if not len(r):
        return 1.0
    rng = np.random.default_rng(seed)
    obs = r.sum()
    pick = rng.integers(0, 2, size=(N_TWIN, len(r))).astype(bool)
    sums = np.where(pick, r, f).sum(axis=1)
    return float(((sums >= obs - 1e-9).sum() + 1) / (N_TWIN + 1))


def blocks_of(days: list[date], n: int = 4):
    ds = sorted(days)
    cuts = np.linspace(0, len(ds), n + 1).astype(int)
    return [(ds[cuts[i]], ds[cuts[i + 1] - 1]) for i in range(n)]


def evaluate(window, label: str, keys=None) -> pd.DataFrame:
    blackout = S.events(HERE / "events.csv")
    rows = []
    for source, und in SOURCES.items():
        days = {d.day: d for d in R.load(und) if window[0] <= d.day <= window[1] and d.day not in blackout}
        sigs = [s for s in load_signals(source) if s.day in days]
        blocks = blocks_of([d for d, D in days.items() if not D.expiry])
        for pl, off in PLACEMENTS.items():
            for ex in EXITS:
                if keys is not None and (source, pl, ex) not in keys:
                    continue
                real, flip = run_cell(days, sigs, off, ex)
                st = S.stats(real, blocks)
                net = np.array([t.net for t in real])
                rows.append(dict(window=label, source=source, und=und, placement=pl, exit=ex, signals=len(sigs),
                                 trades=st["trades"], win_rate=st.get("win_rate", 0.0), net=st["net"], per_trade=st.get("avg", 0.0),
                                 avg_win=st.get("avg_win", 0.0), avg_loss=st.get("avg_loss", 0.0), worst_day=st.get("worst_day", 0.0),
                                 max_dd=st.get("max_dd", 0.0), worst_month=st.get("worst_month", 0.0),
                                 blocks_positive=st.get("blocks_positive", 0), mean_margin=st.get("mean_margin", 0.0),
                                 modelled_share=st.get("modelled_share", 0.0),
                                 p_zero=S.sign_flip_p(net, seed=SEED) if len(net) else 1.0,
                                 twin_p=twin_p(net, flip, SEED + len(rows)) if len(net) else 1.0,
                                 flip_net=float(np.nansum(flip))))
                print(label, source, pl, ex, st["trades"], round(st["net"]), flush=True)
    return pd.DataFrame(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--holdout", action="store_true")
    args = ap.parse_args()
    RESULTS.mkdir(exist_ok=True)
    if args.holdout:
        A = pd.read_csv(RESULTS / "dir_cells.csv")
        pas = A[A.passes.astype(bool)]
        if pas.empty:
            print("no passer: the holdout is not read")
            return
        H = evaluate(HOLDOUT, "H", keys={(r.source, r.placement, r.exit) for r in pas.itertuples()})
        H.to_csv(RESULTS / "dir_holdout.csv", index=False)
        print(H.to_string())
        return
    A = evaluate(DESIGN, "A")
    A["q"] = S.bh(A.p_zero.values)
    A["passes"] = ((A.trades >= GATE["trades"]) & (A.net > 0) & (A.p_zero <= GATE["p"]) & (A.q <= GATE["q"])
                   & (A.twin_p <= GATE["twin"]) & (A.blocks_positive >= GATE["blocks"]) & (A.max_dd >= GATE["dd"])
                   & (A.worst_month >= GATE["month"]))
    A.to_csv(RESULTS / "dir_cells.csv", index=False)
    print("cells", len(A), "net>0", int((A.net > 0).sum()), "passes", int(A.passes.sum()))


if __name__ == "__main__":
    main()
