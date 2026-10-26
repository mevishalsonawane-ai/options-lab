"""R105D (PREREG.md, exploratory): does the breakout bar's index-futures volume separate good Donchian breaks from bad?

    python research/hunt/r105d_volume/r105d.py        # -> results/volume.json, results/report.md

Inputs: the lake's fut_1m_live_index (29 Jul - 19 Oct 2026) and R105's frozen baseline trades
($R105_OUT/{C,B}_base_{final,oos}.pkl). Pure functions first; tests/test_r105d.py covers them.
"""
from __future__ import annotations

import json
import os
import pickle
from collections import defaultdict
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
NROWS, OPEN_MIN = 375, 9 * 60 + 15
HIGH = 1.5
TOD_N, TOD_MIN = 10, 5
BAR20 = 20
HALVES = (("29 Jul - 31 Aug", date(2026, 7, 29), date(2026, 8, 31)), ("1 Sep - 13 Oct", date(2026, 9, 1), date(2026, 10, 13)),
          ("14 - 19 Oct (OOS)", date(2026, 10, 14), date(2026, 10, 19)))


# ================================================================================================ pure
def active_contract(vol_by_day: dict, day: date):
    """vol_by_day: {contract: {day: total volume}}. The contract with the most volume on the latest session BEFORE day
    (None if no earlier session)."""
    prev = [d for v in vol_by_day.values() for d in v if d < day]
    if not prev:
        return None
    pd_ = max(prev)
    best = max(vol_by_day, key=lambda k: vol_by_day[k].get(pd_, -1.0))
    return best if vol_by_day[best].get(pd_, 0) > 0 else None


def bar_vol(minutes: np.ndarray, er: int) -> float:
    """Volume of the 5-minute signal bar, rows er-5 .. er-1 of one day's (375,) minute volumes (NaN missing). NaN if
    the bar has no minute."""
    if er < 5:
        return np.nan
    seg = minutes[er - 5:er]
    return float(np.nansum(seg)) if np.isfinite(seg).any() else np.nan


def ratio_tod(days_minutes: list, er: int, n: int = TOD_N, need: int = TOD_MIN) -> float:
    """days_minutes: earlier sessions' (375,) minute volumes of the contract, oldest first, then TODAY last. The bar's
    volume / the mean of the same slot over the previous n sessions (>= need of them known)."""
    today = days_minutes[-1]
    v = bar_vol(today, er)
    ref = [bar_vol(m, er) for m in days_minutes[:-1][-n:]]
    ref = [x for x in ref if np.isfinite(x)]
    if not np.isfinite(v) or len(ref) < need or np.mean(ref) <= 0:
        return np.nan
    return float(v / np.mean(ref))


def bars5(minutes: np.ndarray, upto_row: int) -> list:
    """Volumes of the complete 5-minute bars that end before row upto_row (rows 0-4, 5-9, ...), NaN-free bars only."""
    out = []
    for a in range(0, upto_row - 4, 5):
        seg = minutes[a:a + 5]
        if np.isfinite(seg).any():
            out.append(float(np.nansum(seg)))
    return out


def ratio_20(days_minutes: list, er: int, n: int = BAR20) -> float:
    """The bar's volume / the mean of the previous n five-minute bars of the contract (earlier days included)."""
    today = days_minutes[-1]
    v = bar_vol(today, er)
    hist = []
    for m in days_minutes[:-1]:
        hist += bars5(m, NROWS)
    hist += bars5(today, er - 5)
    hist = hist[-n:]
    if not np.isfinite(v) or len(hist) < n or np.mean(hist) <= 0:
        return np.nan
    return float(v / np.mean(hist))


def group(r: float) -> str:
    if not np.isfinite(r):
        return "unknown"
    return "high" if r >= HIGH else "low"


def perm_p(nets_high, nets_low, n: int = 10000, seed: int = 1054) -> float:
    """One-sided: P(mean(high) - mean(low) >= observed) under label shuffles."""
    a, b = np.asarray(nets_high, float), np.asarray(nets_low, float)
    if len(a) == 0 or len(b) == 0:
        return 1.0
    obs = a.mean() - b.mean()
    x = np.concatenate([a, b])
    rng = np.random.default_rng(seed)
    k = len(a)
    hits = 0
    for _ in range(n):
        rng.shuffle(x)
        if x[:k].mean() - x[k:].mean() >= obs - 1e-12:
            hits += 1
    return round((1 + hits) / (n + 1), 4)


def summ(nets) -> dict:
    x = np.asarray(nets, float)
    if not len(x):
        return dict(n=0, win=None, net=0, per_trade=None)
    return dict(n=int(len(x)), win=round(float((x > 0).mean()), 4), net=round(float(x.sum())),
                per_trade=round(float(x.mean())))


def bh(p):
    p = np.asarray(p, float)
    o = np.argsort(p)
    q = p[o] * len(p) / np.arange(1, len(p) + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(len(p))
    out[o] = np.minimum(q, 1.0)
    return out


# ================================================================================================ data
def load_futures(path: Path) -> dict:
    """{symbol: {contract: {day: (375,) minute volumes}}} from fut_1m_live_index."""
    import pandas as pd
    d = pd.read_parquet(path, columns=["symbol", "expiry", "volume", "ts"])
    ts = pd.to_datetime(d.ts).dt.tz_convert("Asia/Kolkata")
    d["day"] = ts.dt.date
    d["r"] = (ts.dt.hour * 60 + ts.dt.minute - OPEN_MIN).astype(int)
    d = d[(d.r >= 0) & (d.r < NROWS)]
    out = defaultdict(lambda: defaultdict(dict))
    for (s, e, dy), g in d.groupby(["symbol", "expiry", "day"]):
        a = np.full(NROWS, np.nan)
        a[g.r.to_numpy()] = g.volume.to_numpy(float)
        out[s][str(e)][dy] = a
    return out


def main():
    lake = Path(os.environ.get("IRAALGO_LAKE", Path.home() / ".cache" / "iraalgo" / "lake"))
    fpath = lake / "fut" / "fut_1m_live_index.parquet"
    if not fpath.exists():
        fpath = lake / "fut_1m_live_index.parquet"
    F = load_futures(fpath)
    r105 = Path(os.environ.get("R105_OUT", Path.home() / ".cache" / "iraalgo" / "r105"))
    first = min(d for s in F.values() for c in s.values() for d in c)
    out = {}
    for arm in ("C", "B"):
        tr = []
        for ph in ("final", "oos"):
            tr += [t for t in pickle.loads((r105 / f"{arm}_base_{ph}.pkl").read_bytes()) if t["day"] >= first]
        rows = []
        for t in tr:
            S = F.get(t["und"])
            rt = r2 = np.nan
            if S:
                volday = {c: {d: float(np.nansum(m)) for d, m in dm.items()} for c, dm in S.items()}
                c = active_contract(volday, t["day"])
                if c and t["day"] in S[c]:
                    seq = [S[c][d] for d in sorted(S[c]) if d <= t["day"]]
                    rt, r2 = ratio_tod(seq, t["er"]), ratio_20(seq, t["er"])
            rows.append(dict(day=t["day"], und=t["und"], net=t["net"], tod=rt, r20=r2))
        res = {}
        for meas in ("tod", "r20"):
            g = defaultdict(list)
            for x in rows:
                g[group(x[meas])].append(x)
            m = dict(groups={k: summ([x["net"] for x in g[k]]) for k in ("high", "low", "unknown")})
            m["p"] = perm_p([x["net"] for x in g["high"]], [x["net"] for x in g["low"]])
            m["halves"] = {}
            for nm, lo, hi in HALVES:
                sel = lambda k: [x["net"] for x in g[k] if lo <= x["day"] <= hi]  # noqa: E731
                m["halves"][nm] = dict(high=summ(sel("high")), low=summ(sel("low")))
            res[meas] = m
        out[arm] = dict(trades=len(rows), all=summ([x["net"] for x in rows]), **res)
    keys = [(a, m) for a in ("C", "B") for m in ("tod", "r20")]
    q = bh([out[a][m]["p"] for a, m in keys])
    for (a, m), qq in zip(keys, q):
        out[a][m]["q"] = round(float(qq), 4)
        h = out[a][m]
        hp = [h["halves"][nm] for nm, _, _ in HALVES[:2]]
        h["lead"] = bool(m == "tod" and all((x["high"]["per_trade"] or -1e9) > (x["low"]["per_trade"] or 1e9) for x in hp)
                         and (h["groups"]["high"]["per_trade"] or -1e9) > (h["groups"]["low"]["per_trade"] or 1e9))
    for a in ("C", "B"):
        out[a]["tod"]["lead"] = bool(out["C"]["tod"]["lead"] and out["B"]["tod"]["lead"]
                                     and out["C"]["tod"]["p"] < 0.05 and out["B"]["tod"]["p"] < 0.05)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "volume.json").write_text(json.dumps(out, indent=1, default=str) + "\n")
    print(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    main()
