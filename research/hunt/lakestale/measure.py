"""Stale repeated prints: how much lakeclean step 3 flags across the lake (PREREG.md, "What will be measured" 1).

    IRAALGO_LAKE=/dev/shm/lake python research/hunt/lakestale/measure.py [UND ...]   # -> results/measure.json

Reads the same WEEK option rows R51's stores read (20 Nov 2024 - 13 Oct 2026, the same filters as r51data.add_options),
applies lakeclean steps 1-2, then counts, per index: option rows; stale-run rows (a flat zero-volume price repeated for
at least 5 minutes); rows flagged (the rule as frozen, two-sided run length); the contracts (day, side, strike) and days
touched; stale-run rows kept because they sit inside the neighbours' band, or because no neighbour could be read; and the
causal count (a row counts only from the 5th minute of its run). The largest flagged runs are listed for a sanity read,
with the contract's first traded price after the run (information only, never used by the rule). Nothing is written to
git but the small JSON. 18 Oct 2026.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r51"))
import r51cfg as K  # noqa: E402
import r51data  # noqa: E402

lakeclean = r51data.lakeclean
OUT_JSON = HERE / "results" / "measure.json"
TOP = 25


def after_step2(und: str, days: dict, y: int) -> pd.DataFrame | None:
    """One year's WEEK option rows after lakeclean steps 1-2, filtered exactly as r51data.add_options does."""
    p = K.LAKE / f"opt_{und}_WEEK_{y}.parquet"
    if not p.exists():
        return None
    o = r51data._read(p, ["ts", "offset", "side", "strike", "spot", "open", "high", "low", "close", "volume"])
    o = o[(o.day >= K.START) & (o.day <= K.END)]
    o["side"] = o.side.astype(str).str[0].str.upper()
    o = o[(o.strike - o.spot).abs() <= 12 * K.STEP[und]]
    o = o[o.day.isin(days.keys())]
    closes = {d: D.idx[3] for d, D in days.items()}
    a, _ = lakeclean.drop_foreign(o, closes)
    b, _, _ = lakeclean.fix_stray(a)
    return b.reset_index(drop=True)


def runs_of(df: pd.DataFrame, rows: np.ndarray) -> list:
    """Group flagged first-of-minute rows into runs (one contract, consecutive minutes)."""
    x = df.iloc[rows][["day", "side", "strike", "r", "close"]].copy()
    x["ord"] = [d.toordinal() for d in x.day]
    x = x.sort_values(["ord", "side", "strike", "r"])
    brk = ((x.ord.diff() != 0) | (x.side != x.side.shift()) | (x.strike.diff() != 0) | (x.r.diff() != 1)
           | (x.close.diff() != 0))
    x["run"] = brk.cumsum()
    out = []
    for _, g in x.groupby("run", sort=False):
        out.append(dict(day=g.day.iloc[0], side=g.side.iloc[0], strike=float(g.strike.iloc[0]), r0=int(g.r.min()),
                        r1=int(g.r.max()), n=len(g), price=float(g.close.iloc[0])))
    return out


def hm(r: int) -> str:
    m = K.OPEN_MIN + int(r)
    return f"{m // 60:02d}:{m % 60:02d}"


def measure(und: str) -> tuple[dict, list]:
    days = r51data.index_days(und)
    tot = dict(rows=0, minute_rows=0, stale_run_rows=0, flagged_rows=0, flagged_with_dups=0, kept_in_band=0,
               kept_no_neighbour=0, causal_flagged=0, flagged_above=0, flagged_below=0)
    con, dd, cand_con = set(), set(), set()
    runs = []
    for y in range(K.START.year, K.END.year + 1):
        b = after_step2(und, days, y)
        if b is None or not len(b):
            continue
        tot["rows"] += len(b)
        length, place = lakeclean.stale_runs(b)
        pos, _ = lakeclean._contract_order(b)
        tot["minute_rows"] += len(pos)
        cand = np.flatnonzero(length >= lakeclean.STALE_MIN_RUN)
        tot["stale_run_rows"] += len(cand)
        m = lakeclean.stale_mask(b)
        mc = lakeclean.stale_mask(b, causal=True)
        _, dropped = lakeclean.drop_stale(b)
        tot["flagged_with_dups"] += len(dropped)
        fl = np.flatnonzero(m)
        tot["flagged_rows"] += len(fl)
        tot["causal_flagged"] += int(mc.sum())
        lo, hi = lakeclean.neighbour_band(b, cand)
        judge = np.isfinite(lo) | np.isfinite(hi)
        kept = ~m[cand]
        tot["kept_in_band"] += int((kept & judge).sum())
        tot["kept_no_neighbour"] += int((kept & ~judge).sum())
        p = b.close.to_numpy(np.float64)[cand]
        with np.errstate(all="ignore"):
            tot["flagged_above"] += int((m[cand] & np.isfinite(hi) & (p > hi)).sum())
            tot["flagged_below"] += int((m[cand] & ~(np.isfinite(hi) & (p > hi))).sum())
        for d, s, k in zip(b.day.to_numpy()[fl], b.side.to_numpy()[fl], b.strike.to_numpy()[fl]):
            con.add((d, s, float(k)))
            dd.add(d)
        for d, s, k in zip(b.day.to_numpy()[cand], b.side.to_numpy()[cand], b.strike.to_numpy()[cand]):
            cand_con.add((d, s, float(k)))
        if len(fl):
            rr = runs_of(b, fl)
            lo_f, hi_f = lakeclean.neighbour_band(b, fl)
            band = {(b.day.iat[i], b.side.iat[i], float(b.strike.iat[i]), int(b.r.iat[i])): (lo_f[j], hi_f[j])
                    for j, i in enumerate(fl)}
            traded = b[b.volume > 0]
            for x in rr:
                bl, bh_ = band[(x["day"], x["side"], x["strike"], x["r0"])]
                nxt = traded[(traded.day == x["day"]) & (traded.side == x["side"]) & (traded.strike == x["strike"])
                             & (traded.r > x["r1"])].sort_values("r")
                x.update(und=und, band_lo=None if not np.isfinite(bl) else round(float(bl), 2),
                         band_hi=None if not np.isfinite(bh_) else round(float(bh_), 2),
                         next_trade=None if not len(nxt) else round(float(nxt.close.iloc[0]), 2),
                         next_trade_at=None if not len(nxt) else hm(int(nxt.r.iloc[0])),
                         spot=round(float(days[x["day"]].idx[3][x["r0"]]), 2)
                         if np.isfinite(days[x["day"]].idx[3][x["r0"]]) else None)
                runs.append(x)
    tot.update(contracts=len(con), days=len(dd), stale_run_contracts=len(cand_con), runs=len(runs),
               option_days=sum(1 for d in days if d >= K.START and d <= K.END))
    return tot, runs


def main(unds):
    out = dict(rule=dict(min_run=lakeclean.STALE_MIN_RUN, tol=lakeclean.STALE_TOL, age=lakeclean.STALE_AGE,
                         tick=lakeclean.TICK), window=[str(K.START), str(K.END)], series="WEEK", by_index={}, runs=[])
    allruns = []
    for u in unds:
        t, r = measure(u)
        out["by_index"][u] = t
        allruns += r
        print(u, t, flush=True)
    keys = [k for k in next(iter(out["by_index"].values())) if k != "option_days"]
    out["total"] = {k: sum(v[k] for v in out["by_index"].values()) for k in keys}
    out["run_length_hist"] = pd.Series([x["n"] for x in allruns]).value_counts().sort_index().to_dict() if allruns else {}
    allruns.sort(key=lambda x: -x["n"] * x["price"] if x["band_hi"] is None else -x["n"] * abs(x["price"] - x["band_hi"]))
    for x in allruns[:TOP]:
        x["day"], x["start"], x["end"] = str(x["day"]), hm(x["r0"]), hm(x["r1"])
    out["largest_runs"] = [{k: v for k, v in x.items() if k not in ("r0", "r1")} for x in allruns[:TOP]]
    out["all_runs"] = [dict(und=x["und"], day=str(x["day"]), side=x["side"], strike=x["strike"], n=x["n"],
                            price=x["price"], band_lo=x["band_lo"], band_hi=x["band_hi"], next_trade=x["next_trade"])
                       for x in allruns]
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(out, indent=1, default=str) + "\n")
    return out


if __name__ == "__main__":
    main(sys.argv[1:] or list(K.UNDS))
