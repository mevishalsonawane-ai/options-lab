"""R113 adapter: the four paper index arms' trade lists from R101's units priced by R106's engine (today's rules, B0),
and the Part D entry filters on the same candidates (PREREG section 5). No arm logic is changed here.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build            # R101's clean stores (to 13 Oct)
    python research/hunt/r113_time_of_day/adapters/core.py export    # trades/{R72,R51,R52,LIQ}.csv + parity json

R51 is the arm as on paper since 16 Oct: R101's Donchian-20 signals kept only when Supertrend (10, 3) on the same
5-minute index bars points the same way (R73 T7), exactly R112's `r51_st_signals` (its helper is repeated below because
R112 is on its own branch). Plain R51 is priced once for parity with R101. 26 Oct 2026.
"""
from __future__ import annotations

import json
import sys
from datetime import timedelta
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
R113 = HERE.parent
for p in (str(R113), str(R113.parent / "r106_small_targets"), str(R113.parent / "r73")):
    if p not in sys.path:
        sys.path.insert(0, p)

import r113cfg as K  # noqa: E402
import r113lib as L  # noqa: E402

PUBLISHED = {  # R101 results/{design,final}_<ARM>.json B0 (trades, net)
    ("R72", "design"): (3071, 216929), ("R72", "final"): (1202, 171366),
    ("R51plain", "design"): (4498, 9998), ("R51plain", "final"): (1781, 81845),
    ("R52", "design"): (706, 290573), ("R52", "final"): (293, 48612),
    ("LIQ", "design"): (881, 257249), ("LIQ", "final"): (357, 11155),
}
_M: dict = {}


def mods():
    if not _M:
        import r106study as S
        M = S.mods()
        import r101arms as A
        import r73rules as P73
        _M.update(S=S, A=A, P73=P73, W=M["W"], r51data=M["r51data"], fam_r44=A.mods()["fam_r44"])
    return _M


def supertrend_keep(sig_by_day: dict, pos: dict, st: np.ndarray) -> dict:
    """R112's helper (R73 T7 on R51's signals): keep a signal whose 5-minute signal bar b (er = 5 (b + 1)) has Supertrend
    pointing its way (+1 call, -1 put); a bar not in the series or Supertrend unknown (0) is skipped."""
    out = {}
    for d, sigs in sig_by_day.items():
        keep = []
        for er, side in sigs:
            i = pos.get((d, int(er) // 5 - 1))
            if i is not None and int(st[i]) == (1 if side == "C" else -1):
                keep.append((er, side))
        if keep:
            out[d] = keep
    return out


def r51_st_signals() -> dict:
    M = mods()
    raw = M["A"].r51_signals()
    out = {}
    for u in M["A"].K.UNDS:
        d, b, o, h, l, c = M["fam_r44"].bars5(u)
        pos = {(d[i], int(b[i])): i for i in range(len(d))}
        st = M["P73"].supertrend_dir(np.asarray(h, float), np.asarray(l, float), np.asarray(c, float), 10, 3.0)
        out[u] = supertrend_keep(raw.get(u, {}), pos, st)
    return out


_ST: dict = {}


def units(arm: str, lo, hi) -> list:
    M = mods()
    A = M["A"]
    if arm == "R51":
        if "sig" not in _ST:
            _ST["sig"] = r51_st_signals()
        return A._side2_units(_ST["sig"], lo, hi, A._xl())
    if arm == "R51plain":
        return A.r51_units(lo, hi)
    return A.UNITS[arm](lo, hi)


def exits_arm(arm: str) -> str:
    return "R51" if arm.startswith("R51") else arm


def walk(arm: str, us: list) -> list:
    """Today's taking walk (R101 / R106 B0) on the units' own R101 pricing."""
    S = mods()["S"]
    return S.run_rule(exits_arm(arm), us, [u[2] for u in us], None)


def filtered(us: list, keep_fn) -> list:
    """The units with only the candidates keep_fn(unit_index, cand_index, cand) keeps (pricing re-used, aligned)."""
    out = []
    for g, (key, cands, res, how) in enumerate(us):
        idx = [j for j, c in enumerate(cands) if keep_fn(g, j, c)]
        if idx:
            out.append((key, [cands[j] for j in idx], [res[j] for j in idx], how))
    return out


def drop_rule(us: list, drop) -> list:
    return filtered(us, lambda g, j, c: not L.in_drop(L.row_minute(c["er"]), drop))


def n_removed(us: list, drop) -> int:
    return sum(1 for _, cands, _, _ in us for c in cands if L.in_drop(L.row_minute(c["er"]), drop))


def twins(arm: str, us: list, k: int, n: int = K.TWINS, seed: int = K.SEED) -> list:
    """Nets of n twins that each remove k of the arm's candidates chosen at random (the same walk)."""
    flat = [(g, j) for g, (_, cands, _, _) in enumerate(us) for j in range(len(cands))]
    rng = np.random.default_rng(seed)
    out = []
    for _ in range(n):
        gone = {flat[i] for i in L.random_drop(len(flat), k, rng)}
        tr = walk(arm, filtered(us, lambda g, j, c: (g, j) not in gone))
        out.append(float(sum(t["net"] for t in tr)))
    return out


def to_frame(arm: str, trades: list) -> pd.DataFrame:
    r51data = mods()["r51data"]
    rows = []
    for t in trades:
        d = t["day"]
        base = pd.Timestamp(d) + timedelta(hours=9, minutes=15)
        D = r51data.load(t["und"]).get(d)
        rows.append(dict(arm=arm, und=t["und"], day=str(d), entry_ts=(base + timedelta(minutes=int(t["er"]))).strftime(
            "%Y-%m-%d %H:%M"), exit_ts=(base + timedelta(minutes=int(t["xr"]))).strftime("%Y-%m-%d %H:%M"),
            side=t["side"], net_rs=round(float(t["net"]), 2), period=K.period_of(d) or "other",
            expiry=(int(bool(D.expiry)) if D is not None else "")))
    return pd.DataFrame(rows, columns=L.COLS)


def open_final() -> None:
    """R51's final guard, opened only by r113study.py final (after Part D is fixed on design numbers)."""
    import r106study as S
    S.mods()["C"].open_final()


def export(phases=("design",)) -> dict:
    """Write each arm's trades for the phases asked; 'final' needs open_final() first (r113study.py final)."""
    K.TRADES.mkdir(parents=True, exist_ok=True)
    rep = {}
    spans = {"design": (K.START, K.DESIGN_END), "final": (K.FINAL_START, K.FINAL_END)}
    for arm in ("R72", "R51plain", "R52", "LIQ", "R51"):
        frames, par = [], {}
        old = K.TRADES / f"{arm}.csv"
        if old.exists() and arm != "R51plain":
            prev = pd.read_csv(old, dtype={"expiry": str})
            frames.append(prev[~prev.period.isin(phases)])
        for per in phases:
            lo, hi = spans[per]
            tr = walk(arm, units(arm, lo, hi))
            s = L.summary(tr)
            pub = PUBLISHED.get((arm, per))
            par[per] = dict(trades=s["trades"], net=s["net"], published=pub,
                            parity=(pub is not None and pub[0] == s["trades"] and abs(pub[1] - s["net"]) <= 1))
            frames.append(to_frame(arm, tr))
            print(arm, per, s, "published", pub, flush=True)
        if arm == "R51plain":
            rep[arm] = par
            continue
        pd.concat(frames).to_csv(K.TRADES / f"{arm}.csv", index=False)
        meta = dict(arm=arm, name=K.ARM_NAME[arm], source="research/hunt/r101_arms_enhance/r101arms.py units, "
                    "research/hunt/r106_small_targets/r106study.py run_rule (rule None = today's rules)",
                    command="python research/hunt/r113_time_of_day/adapters/core.py export", periods=par,
                    size="1 lot", costs="dated Zerodha charges, R17 fills, next-minute fills (R101 / R106)",
                    signal="the index (5-minute bars); 1-ITM weekly option bought",
                    caveats=("R51 = Donchian 20 + Supertrend (10, 3) (R73 T7) as on paper since 16 Oct; R101 has no "
                             "published figure for it, plain R51 is the parity check") if arm == "R51" else "")
        (K.TRADES / f"{arm}.json").write_text(json.dumps(meta, indent=1, default=str))
        rep[arm] = par
    return rep


if __name__ == "__main__":
    if sys.argv[1:] == ["export"]:
        print(json.dumps(export(("design",)), indent=1, default=str))
    else:
        print(__doc__)
