"""R98 follow-up (Boss, 21 Oct 2026: "after every 30% profit add a profit lock"): the R98 tickets with a +30% step
profit-lock ladder, filled the way the app's ProfitLock fills its resting stop.

    python3 -I research/hunt/r98/lock.py      # writes $R98_OUT/lock.parquet, results/lock.md and results/lock.json

The ladder (one rung behind, no top):
  add30  every +30% of the ENTRY price: +30% -> lock breakeven (after charges), +60% -> lock +30%, +90% -> +60%, ...
  cmp30  every x1.3 of the last rung (compounding): x1.3 -> breakeven, x1.69 -> x1.3, x2.197 -> x1.69, ...
Fills as android/engine/.../orb/ProfitLock.kt: the best price is the 1-minute HIGHS; a rung counts from the next minute
(peak before the minute); the resting sell stop fills at its price when the minute's low reaches it, or at the minute's
OPEN when the market opens below it (a gap); then 1 tick of spread. "Breakeven" is the price paid plus the round trip's
charges per unit, rounded up to the tick. Each ladder is run with no initial stop, a -20% stop and a -50% stop; the same
fill rules without a ladder are the baseline. Not hit by 15:20: out at the last close (worthless under Rs 0.10).
"""
from __future__ import annotations

import json
import math
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402

RES = Path(__file__).resolve().parent / "results"
STEP = 0.30
LADDERS = ("none", "add30", "cmp30")
STOPS = (0.0, 0.8, 0.5)                  # none, -20%, -50% (stop at 0.8x / 0.5x the entry)
VARIANTS = [(lad, st) for lad in LADDERS for st in STOPS]
COLS = [f"{lad}_s{st:g}" for lad, st in VARIANTS]
SNAME = {0.0: "no stop", 0.8: "-20% stop", 0.5: "-50% stop"}
LNAME = {"none": "no lock", "add30": "lock every +30% of entry", "cmp30": "lock every x1.3 (compounding)"}


def be_level(und: str, e: float) -> float:
    q = L.BUDGET / e
    cpu = -L.net_pnl(und, e, e + L.TICK) / q          # the round trip's charges per unit at the entry price
    return math.ceil((e + cpu) / L.TICK - 1e-9) * L.TICK


def lock_levels(lad: str, e: float, be: float, pk: np.ndarray) -> np.ndarray:
    """The locked stop for each minute from the peak BEFORE it; -inf below the first rung."""
    r = np.maximum(pk / e, 1.0)
    if lad == "add30":
        k = np.floor((r - 1.0) / STEP + 1e-9)
        lv = e * (1.0 + STEP * (k - 1.0))
    else:
        k = np.floor(np.log(r) / np.log(1.0 + STEP) + 1e-9)
        lv = e * (1.0 + STEP) ** (k - 1.0)
    lv = np.maximum(lv, be)
    lv = np.where(lv < pk - 1e-9, lv, -np.inf)              # a rung whose lock would not sit below the peak does not count
    return np.where(k >= 1, lv, -np.inf)


def simulate(und: str, e: float, o, h, l, c) -> np.ndarray:
    be = be_level(und, e)
    hh = np.where(np.isnan(h), -np.inf, h)
    pk = np.maximum(np.concatenate([[e], np.maximum.accumulate(hh)[:-1]]), e)
    okc = ~np.isnan(c)
    last = c[okc][-1] if okc.any() else 0.0
    lows = np.where(np.isnan(l), np.inf, l)
    out = np.empty(len(VARIANTS), dtype=np.float32)
    locks = {lad: lock_levels(lad, e, be, pk) for lad in LADDERS if lad != "none"}
    for j, (lad, st) in enumerate(VARIANTS):
        stop = np.full(len(c), -np.inf)
        if lad != "none":
            stop = np.maximum(stop, locks[lad])
        if st > 0:
            stop = np.maximum(stop, st * e)
        hit = lows <= stop
        if hit.any():
            i = int(np.argmax(hit))
            px = o[i] if (o[i] == o[i] and o[i] <= stop[i]) else stop[i]
        else:
            px = last
        out[j] = L.net_pnl(und, e, px)
    return out


def run_und(und: str) -> pd.DataFrame:
    rows = pd.read_parquet(R.OUT / "rows.parquet", columns=["und", "day", "t", "strike", "side", "entry"],
                           filters=[("und", "==", und)])
    days = {d.day: d for d in R.load(und)}
    res = np.empty((len(rows), len(VARIANTS)), dtype=np.float32)
    peak = np.empty(len(rows), dtype=np.float32)
    for n, (dd, t, k, s, e) in enumerate(zip(rows.day, rows.t, rows.strike, rows.side, rows.entry)):
        a = days[dd].opts[(float(k), s)]
        te = int(t) + 1
        seg = a[te:L.T_END + 1]
        res[n] = simulate(und, float(e), seg[:, R.O], seg[:, R.H], seg[:, R.L], seg[:, R.C])
        peak[n] = np.nanmax(seg[:, R.H]) / e if np.any(~np.isnan(seg[:, R.H])) else np.nan
    out = rows.reset_index(drop=True).copy()
    out["peak_high_x"] = peak
    for j, col in enumerate(COLS):
        out[col] = res[:, j]
    print(und, len(out), flush=True)
    return out


def pct(x, d=0):
    return f"{x * 100:.{d}f}%"


def rs(x):
    return f"{x:,.0f}"


def md_table(df):
    cols = list(df.columns)
    out = ["| " + " | ".join(map(str, cols)) + " |", "|" + "---|" * len(cols)]
    out += ["| " + " | ".join(str(r[c]) for c in cols) + " |" for _, r in df.iterrows()]
    return "\n".join(out)


def without_best(g: pd.DataFrame, col: str, n=5) -> float:
    by = g.groupby(["und", "day"])[col].sum().sort_values(ascending=False)
    return (g[col].sum() - by.head(n).sum()) / len(g)


def report(df: pd.DataFrame):
    S, md = {}, ["# R98 follow-up: a profit lock every +30% (generated by lock.py)\n"]
    md += [f"Tickets: {len(df):,} (all five indices, 2021-26, Rs 5,000 each). Reached +30% (by the minute highs) at some "
           f"point before 15:20: {pct((df.peak_high_x >= 1.3).mean(), 1)}; +60%: {pct((df.peak_high_x >= 1.6).mean(), 1)}; "
           f"2×: {pct((df.peak_high_x >= 2).mean(), 1)}.\n"]
    t = []
    for (lad, st), col in zip(VARIANTS, COLS):
        x = df[col].values
        t.append({"ladder": LNAME[lad], "initial stop": SNAME[st], "win": pct((x > 0).mean(), 1), "mean Rs": rs(x.mean()),
                  "median Rs": rs(np.median(x)), "worst 5% Rs": rs(np.percentile(x, 5)),
                  "mean without the 5 best days": rs(without_best(df, col)),
                  "years > 0": f"{int((df.groupby('yr')[col].mean() > 0).sum())} of {df.yr.nunique()}"})
    S["all"] = t
    md += ["## A. All tickets\n", md_table(pd.DataFrame(t)), ""]
    # how the lock exits split
    t = []
    for lad in ("add30", "cmp30"):
        for st in STOPS:
            col, base = f"{lad}_s{st:g}", f"none_s{st:g}"
            dlt = df[col] - df[base]
            t.append({"ladder": LNAME[lad], "initial stop": SNAME[st],
                      "tickets the lock changed": pct((dlt.abs() > 1).mean(), 1),
                      "of those, better / worse": f"{pct((dlt > 1).sum() / max((dlt.abs() > 1).sum(), 1))} / "
                                                 f"{pct((dlt < -1).sum() / max((dlt.abs() > 1).sum(), 1))}",
                      "Rs saved on the better ones (mean)": rs(dlt[dlt > 1].mean()),
                      "Rs given up on the worse ones (mean)": rs(dlt[dlt < -1].mean()),
                      "net effect per ticket": rs(dlt.mean())})
    S["effect"] = t
    md += ["## B. What the lock changes, against the same initial stop without a lock\n", md_table(pd.DataFrame(t)), ""]
    t = []
    for u in list(R.UNDS) + ["ALL"]:
        g = df if u == "ALL" else df[df.und == u]
        r = {"index": u, "tickets": len(g)}
        for col, name in (("none_s0", "no lock, no stop"), ("add30_s0", "+30% lock, no stop"), ("cmp30_s0", "x1.3 lock, no stop"),
                          ("none_s0.8", "no lock, -20%"), ("add30_s0.8", "+30% lock, -20%"), ("add30_s0.5", "+30% lock, -50%")):
            r[name] = f"{pct((g[col] > 0).mean())} / {rs(g[col].mean())}"
        t.append(r)
    S["by_index"] = t
    md += ["## C. By index (win / mean Rs per ticket)\n", md_table(pd.DataFrame(t)), ""]
    t = []
    for y, g in df.groupby("yr"):
        t.append({"year": int(y), **{f"{LNAME[l][:14]}, {SNAME[s]}": rs(g[f'{l}_s{s:g}'].mean()) for l, s in
                                     (("none", 0.0), ("add30", 0.0), ("none", 0.8), ("add30", 0.8), ("add30", 0.5))}})
    S["by_year"] = t
    md += ["## D. By year (mean Rs per ticket)\n", md_table(pd.DataFrame(t)), ""]
    d = df[df.yr >= 2023]
    top10 = d[d.hgb_y2 >= d.groupby("yr").hgb_y2.transform(lambda x: x.quantile(0.9))]
    one = d.loc[d.groupby(["und", "day"]).logit_y10.idxmax()]
    t = []
    for name, g in (("model's top 10% (2x model)", top10), ("one ticket per index-day (10x model)", one)):
        for (lad, st), col in zip(VARIANTS, COLS):
            x = g[col].values
            t.append({"tickets": f"{name} ({len(g):,})", "exit": f"{LNAME[lad]}, {SNAME[st]}", "win": pct((x > 0).mean(), 1),
                      "mean Rs": rs(x.mean()), "without the 5 best days": rs(without_best(g, col)),
                      "years > 0 (of 4)": int((g.groupby("yr")[col].sum() > 0).sum())})
    S["models"] = t
    md += ["## E. The model's picks, test years 2023-26 (post hoc)\n", md_table(pd.DataFrame(t)), ""]
    x = df[(df.und == "NIFTY") & (df.day == pd.Timestamp("2026-10-13").date()) & (df.t == R.minute(13, 30)) & (df.side == "C")]
    t = [{"option": f"{int(r.strike)} CE", "entry Rs": f"{r.entry:.2f}", "best (highs)": f"{r.peak_high_x:.1f}×",
          "no lock, no stop": rs(r.none_s0), "+30% lock, no stop": rs(r.add30_s0), "x1.3 lock, no stop": rs(r.cmp30_s0)}
         for _, r in x.sort_values("strike").iterrows()]
    S["oct13"] = t
    md += ["## F. NIFTY 13 Oct 2026, the calls bought at 13:30\n", md_table(pd.DataFrame(t)), ""]
    RES.mkdir(exist_ok=True)
    (RES / "lock.md").write_text("\n".join(md) + "\n")
    (RES / "lock.json").write_text(json.dumps(S, indent=1, default=str))
    print("\n".join(md))


def main():
    if sys.argv[1:2] != ["report"]:
        with Pool(4) as p:
            parts = p.map(run_und, R.UNDS)
        df = pd.concat(parts, ignore_index=True)
        df.to_parquet(R.OUT / "lock.parquet")
    df = pd.read_parquet(R.OUT / "lock.parquet")
    keys = ["und", "day", "t", "strike", "side"]
    rows = pd.read_parquet(R.OUT / "rows.parquet", columns=keys).join(
        pd.read_parquet(R.OUT / "scores.parquet", columns=["hgb_y2", "logit_y10"]))
    df = df.merge(rows, on=keys, how="left", validate="one_to_one")
    df["yr"] = pd.to_datetime(df.day).dt.year
    report(df)


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    main()
