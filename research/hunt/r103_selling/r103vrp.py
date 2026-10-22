"""R103 deep, idea 1 (PREREG_VRP.md): sell only when implied volatility is rich against the index's realised volatility.

    python3 -I research/hunt/r103_selling/r103vrp.py              # design A and B: results/vrp_cells.csv
    python3 -I research/hunt/r103_selling/r103vrp.py --holdout    # the one (partly seen) holdout read, passers only

Nothing here trades.
"""
from __future__ import annotations

import argparse
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r103data as R  # noqa: E402
import r103sim as S  # noqa: E402

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
DESIGN_A = (date(2024, 11, 20), date(2026, 7, 14))
DESIGN_B = (date(2021, 8, 5), date(2024, 11, 19))
HOLDOUT = (date(2026, 7, 15), date(2026, 10, 19))
STRUCTS = ("STR", "STG", "IC")
EXITS = {"X3": None, "X3L": (0.3, 0.2)}
DAYSETS = ("ALL", "EXPIRY")
SOURCES = ("ATMIV", "VIX")
THRESHOLDS = (1.0, 1.25, 1.5)
ENTRY = S.ENTRIES["0920"]
RV_N = 20
N_TWIN, SEED = 1000, 103
GATE = dict(trades=40, p=0.05, q=0.10, twin=0.05, blocks=3, dd=-40000.0, month=-13000.0, b_trades=20)


def rv20(und: str, lake: Path = R.LAKE) -> dict[date, float]:
    """Annualised RV of the last 20 daily log returns ending at the PREVIOUS session's close, by session date."""
    d = pd.read_parquet(lake / "index_daily" / "index_daily.parquet")
    d = d[d.symbol == und].copy()
    d["date"] = pd.to_datetime(d.date).dt.date
    d = d.sort_values("date").drop_duplicates("date")
    r = np.log(d.close / d.close.shift(1))
    rv = r.rolling(RV_N).std(ddof=1).shift(1) * np.sqrt(252)
    return dict(zip(d.date, rv))


def implied(day: R.Day, t: int, source: str) -> float:
    """The entry-minute implied volatility (annualised, a fraction): ATM call and put solved, or India VIX / 100."""
    if source == "VIX":
        v = S.ffill(day.vix, 5)[t] if day.vix is not None else np.nan
        return float(v) / 100.0 if np.isfinite(v) and v > 0 else np.nan
    step = R.STEP[day.und]
    s = S.spot_at(day, t)
    if not np.isfinite(s):
        return np.nan
    k0 = round(s / step) * step
    T = S.years_left(day.dte)[t]
    ivs = []
    for sd in ("C", "P"):
        a = day.opts.get((k0, sd))
        if a is not None:
            ivs.append(float(S.implied_vol(S.ffill(a[:, R.C])[t], s, k0, T, sd == "C")))
    ivs = [x for x in ivs if np.isfinite(x)]
    return float(np.mean(ivs)) if ivs else np.nan


def ratio(day: R.Day, t: int, source: str, rv: dict[date, float]) -> float:
    r = rv.get(day.day, np.nan)
    iv = implied(day, t, source)
    return iv / r if np.isfinite(iv) and np.isfinite(r) and r > 0 else np.nan


def base_trades(days: list[R.Day], struct: str, exit_: str) -> dict[date, S.Trade]:
    out = {}
    for d in days:
        legs = S.structure(d, ENTRY, struct)
        if legs is None:
            continue
        tr = S.simulate(d, ENTRY, struct, "X3", "T0", legs=legs, lock=EXITS[exit_])
        if tr is not None:
            out[d.day] = tr
    return out


def blocks_of(days: list[R.Day], n: int = 4) -> list[tuple[date, date]]:
    ds = sorted(d.day for d in days)
    cuts = np.linspace(0, len(ds), n + 1).astype(int)
    return [(ds[cuts[i]], ds[cuts[i + 1] - 1]) for i in range(n)]


def twin_p(all_net: np.ndarray, k: int, net: float, seed: int) -> float:
    """Share of random k-day subsets of the unfiltered trades with a net at least the filtered net."""
    if k == 0 or k > len(all_net):
        return 1.0
    rng = np.random.default_rng(seed)
    sums = np.array([all_net[rng.choice(len(all_net), k, replace=False)].sum() for _ in range(N_TWIN)])
    return float(((sums >= net - 1e-9).sum() + 1) / (N_TWIN + 1))


def cells(days_by_und: dict, window, blackout: set, ratios: dict, label: str, with_twins: bool) -> pd.DataFrame:
    rows = []
    for und, days in days_by_und.items():
        ds = [d for d in days if window[0] <= d.day <= window[1] and d.day not in blackout]
        blocks = blocks_of(ds)
        for struct in STRUCTS:
            for ex in EXITS:
                base = base_trades(ds, struct, ex)
                for dayset in DAYSETS:
                    pool = {k: v for k, v in base.items() if dayset == "ALL" or k in {d.day for d in ds if d.expiry}}
                    pool_net = np.array([v.net for v in pool.values()])
                    st0 = S.stats(list(pool.values()))
                    for src in SOURCES:
                        for thr in THRESHOLDS:
                            sel = [v for k, v in pool.items() if np.isfinite(ratios[und].get((k, src), np.nan))
                                   and ratios[und][(k, src)] >= thr]
                            st = S.stats(sel, blocks)
                            net = np.array([t.net for t in sel])
                            i = len(rows)
                            rows.append(dict(
                                window=label, und=und, struct=struct, exit=ex, dayset=dayset, source=src, thr=thr,
                                trades=st["trades"], win_rate=st.get("win_rate", 0.0), net=st["net"], per_trade=st.get("avg", 0.0),
                                avg_win=st.get("avg_win", 0.0), avg_loss=st.get("avg_loss", 0.0), worst_day=st.get("worst_day", 0.0),
                                max_dd=st.get("max_dd", 0.0), worst_month=st.get("worst_month", 0.0),
                                blocks_positive=st.get("blocks_positive", 0), p_zero=S.sign_flip_p(net, seed=SEED) if len(net) else 1.0,
                                twin_p=twin_p(pool_net, len(sel), st["net"], SEED + i) if with_twins else np.nan,
                                base_trades=st0.get("trades", 0), base_win=st0.get("win_rate", 0.0), base_net=st0.get("net", 0.0),
                                base_per_trade=st0.get("avg", 0.0)))
            print(label, und, struct, "done", flush=True)
    return pd.DataFrame(rows)


def all_ratios(days_by_und: dict) -> dict:
    out = {}
    for und, days in days_by_und.items():
        rv = rv20(und)
        out[und] = {(d.day, src): ratio(d, ENTRY, src, rv) for d in days for src in SOURCES}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--holdout", action="store_true")
    args = ap.parse_args()
    blackout = S.events(HERE / "events.csv")
    days_by_und = {u: R.load(u) for u in R.UNDS}
    if args.holdout:
        A = pd.read_csv(RESULTS / "vrp_cells.csv")
        pas = A[A.passes.astype(bool)]
        if pas.empty:
            print("no passer: the holdout is not read")
            return
        hold_days = {u: [d for d in ds if HOLDOUT[0] <= d.day <= HOLDOUT[1]] for u, ds in days_by_und.items()}
        H = cells(hold_days, HOLDOUT, blackout, all_ratios(hold_days), "H", False)
        key = ["und", "struct", "exit", "dayset", "source", "thr"]
        H = H.merge(pas[key], on=key)
        H.to_csv(RESULTS / "vrp_holdout.csv", index=False)
        print(H[key + ["trades", "win_rate", "net", "worst_day", "worst_month"]].to_string())
        return
    design = {u: [d for d in ds if d.day <= DESIGN_A[1]] for u, ds in days_by_und.items()}
    ratios = all_ratios(design)
    A = cells(design, DESIGN_A, blackout, ratios, "A", True)
    B = cells(design, DESIGN_B, blackout, ratios, "B", False)
    A["q"] = S.bh(A.p_zero.values)
    key = ["und", "struct", "exit", "dayset", "source", "thr"]
    A = A.merge(B[key + ["trades", "win_rate", "net"]].rename(columns={"trades": "trades_B", "win_rate": "win_B", "net": "net_B"}), on=key)
    A["passes"] = ((A.trades >= GATE["trades"]) & (A.net > 0) & (A.p_zero <= GATE["p"]) & (A.q <= GATE["q"])
                   & (A.twin_p <= GATE["twin"]) & (A.blocks_positive >= GATE["blocks"]) & (A.max_dd >= GATE["dd"])
                   & (A.worst_month >= GATE["month"]) & (A.net_B > 0) & (A.trades_B >= GATE["b_trades"]))
    RESULTS.mkdir(exist_ok=True)
    A.to_csv(RESULTS / "vrp_cells.csv", index=False)
    # the ratio's own distribution (descriptive)
    desc = {u: {src: pd.Series([v for (d, s), v in r.items() if s == src and DESIGN_A[0] <= d <= DESIGN_A[1]]).describe().round(3).to_dict()
                for src in SOURCES} for u, r in ratios.items()}
    pd.DataFrame({(u, s): v for u, x in desc.items() for s, v in x.items()}).to_csv(RESULTS / "vrp_ratio_describe.csv")
    print("cells", len(A), "net>0", int((A.net > 0).sum()), "q<=.1&net>0", int(((A.q <= .1) & (A.net > 0)).sum()),
          "twin<=.05", int((A.twin_p <= .05).sum()), "passes", int(A.passes.sum()))


if __name__ == "__main__":
    main()
