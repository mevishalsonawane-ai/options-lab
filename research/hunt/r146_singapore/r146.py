"""R146 (PREREG.md): Singapore's STI before India's open vs NIFTY / BANKNIFTY's gap, first hour and rest of day.

    bash research/hunt/r146_singapore/fetch.sh /tmp/r146raw
    python -I research/hunt/r146_singapore/r146.py design  /tmp/r146raw    # -> results/design.json
    python -I research/hunt/r146_singapore/r146.py holdout /tmp/r146raw    # the ONE holdout read

Signal source: the indices themselves (Singapore STI, S&P 500, NIFTY, BANKNIFTY). Raw Yahoo JSON is untrusted and only
parsed here (numbers only).
"""
from __future__ import annotations

import datetime as dt
import json
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
LAKE_MIN = Path(os.environ.get("R146_MINUTES", CACHE / "lake" / "index_1m"))
DAILY = Path(os.environ.get("R146_DAILY", CACHE / "flat" / "index_daily.parquet"))
SGT = dt.timezone(dt.timedelta(hours=8))
UNDS = ("NIFTY", "BANKNIFTY")
HOLD = dt.date(2025, 10, 1)
END = dt.date(2026, 10, 30)
STARTS = {"G_T0": dt.date(2015, 1, 2), "G_T": dt.date(2021, 8, 1), "H": dt.date(2023, 12, 6)}
BOOT, BLOCK, SEED = 2000, 10, 146
ROW = {"0920": 5, "1015": 60, "1510": 355}          # minute rows from 09:15 (the minute's close)


# ------------------------------------------------------------------------------------------------ parsing
def chart(path: Path) -> pd.DataFrame:
    r = json.loads(path.read_text())["chart"]["result"][0]
    q = r["indicators"]["quote"][0]
    df = pd.DataFrame(dict(ts=r["timestamp"], open=q["open"], high=q["high"], low=q["low"], close=q["close"]))
    df = df.dropna(subset=["open", "close"])
    for c in ("open", "high", "low", "close"):
        df[c] = df[c].astype(float)
    return df


def sti_daily(raw: Path) -> pd.DataFrame:
    d = chart(raw / "sti_1d.json")
    d["date"] = [dt.datetime.fromtimestamp(t, SGT).date() for t in d.ts]
    return d.drop_duplicates("date", keep="last").set_index("date")[["open", "close"]]


def sti_morning(raw: Path) -> pd.Series:
    """Close of the 10:00-11:00 SGT hourly bar (08:30 IST) by date."""
    h = chart(raw / "sti_1h.json")
    t = [dt.datetime.fromtimestamp(x, SGT) for x in h.ts]
    h["date"] = [x.date() for x in t]
    h["hm"] = [x.strftime("%H:%M") for x in t]
    m = h[h.hm == "10:00"].drop_duplicates("date", keep="last").set_index("date").close
    return m


def us_daily(raw: Path) -> pd.Series:
    d = chart(raw / "gspc_1d.json")
    d["date"] = [dt.datetime.fromtimestamp(t, dt.timezone(dt.timedelta(hours=-5))).date() for t in d.ts]
    d = d.drop_duplicates("date", keep="last").set_index("date").close
    return d.pct_change()


def india_daily(und: str) -> pd.DataFrame:
    d = pd.read_parquet(DAILY)
    d = d[d.symbol == und].copy()
    d["date"] = pd.to_datetime(d.date).dt.date
    d = d.drop_duplicates("date", keep="last").set_index("date").sort_index()
    d["gap"] = d.open / d.close.shift(1) - 1
    return d[["gap"]]


def india_minutes(und: str) -> pd.DataFrame:
    parts = []
    for p in sorted(LAKE_MIN.glob(f"index_1m_{und}_*.parquet")):
        x = pd.read_parquet(p, columns=["ts", "close"])
        ts = pd.to_datetime(x.ts)
        if ts.dt.tz is not None:
            ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        x["date"] = ts.dt.date
        x["row"] = ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)
        parts.append(x[x.row.isin(ROW.values())])
    m = pd.concat(parts).pivot_table(index="date", columns="row", values="close", aggfunc="last")
    out = pd.DataFrame(index=m.index)
    out["T1"] = m[ROW["1015"]] / m[ROW["0920"]] - 1
    out["T2"] = m[ROW["1510"]] / m[ROW["0920"]] - 1
    return out


def table(raw: Path) -> pd.DataFrame:
    s = sti_daily(raw)
    s["G"] = s.open / s.close.shift(1) - 1
    mo = sti_morning(raw)
    s["MO"] = mo.reindex(s.index) / s.open - 1
    s["PRE"] = mo.reindex(s.index) / s.close.shift(1) - 1
    us = us_daily(raw)
    us_dates = np.array(sorted(us.index))
    rows = []
    for und in UNDS:
        ind = india_daily(und).join(india_minutes(und), how="left")
        for d in ind.index:
            if d not in s.index:
                continue
            k = np.searchsorted(us_dates, d) - 1           # the last US session dated before India's day
            usr = us.get(us_dates[k]) if k >= 0 else np.nan
            rows.append(dict(und=und, date=d, G=s.at[d, "G"], MO=s.at[d, "MO"], PRE=s.at[d, "PRE"], US=usr,
                             T0=ind.at[d, "gap"], T1=ind.at[d, "T1"] if "T1" in ind else np.nan,
                             T2=ind.at[d, "T2"] if "T2" in ind else np.nan))
    return pd.DataFrame(rows)


# ------------------------------------------------------------------------------------------------ statistics
def resid(y: np.ndarray, x: np.ndarray) -> np.ndarray:
    b = np.polyfit(x, y, 1)
    return y - np.polyval(b, x)


def block_p(x: np.ndarray, y: np.ndarray, rng) -> tuple:
    """r and its two-sided moving-block bootstrap p (r* <= 0 or >= 0)."""
    n = len(x)
    r = float(np.corrcoef(x, y)[0, 1])
    nb = int(np.ceil(n / BLOCK))
    st = rng.integers(0, max(n - BLOCK, 1), size=(BOOT, nb))
    idx = (st[:, :, None] + np.arange(BLOCK)[None, None, :]).reshape(BOOT, -1)[:, :n]
    xs, ys = x[idx], y[idx]
    xs = xs - xs.mean(1, keepdims=True)
    ys = ys - ys.mean(1, keepdims=True)
    rs = (xs * ys).sum(1) / np.sqrt((xs ** 2).sum(1) * (ys ** 2).sum(1))
    p = 2 * min((rs <= 0).mean(), (rs >= 0).mean())
    return r, float(min(1.0, max(p, 1.0 / BOOT)))


def tests() -> list:
    out = []
    for und in UNDS:
        for s in ("G", "MO", "PRE"):
            for t in ("T0", "T1", "T2"):
                out.append(dict(und=und, sig=s, tgt=t, partial=False))
        for s in ("G", "PRE"):
            for t in ("T1", "T2"):
                out.append(dict(und=und, sig=s, tgt=t, partial=True))
    return out


def start_of(sig: str, tgt: str) -> dt.date:
    if sig == "G":
        return STARTS["G_T0"] if tgt == "T0" else STARTS["G_T"]
    return STARTS["H"]


def bh(p) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out


def evaluate(T: pd.DataFrame, lo_fn, hi: dt.date, rng) -> list:
    res = []
    for k in tests():
        lo = lo_fn(k)
        X = T[(T.und == k["und"]) & (T.date >= lo) & (T.date <= hi)]
        cols = [k["sig"], k["tgt"]] + (["US"] if k["partial"] else [])
        X = X.dropna(subset=cols)
        if len(X) < 30:
            res.append(dict(k, n=int(len(X)), r=None, p=1.0))
            continue
        x, y = X[k["sig"]].values, X[k["tgt"]].values
        if k["partial"]:
            x, y = resid(x, X.US.values), resid(y, X.US.values)
        r, p = block_p(x, y, rng)
        row = dict(k, n=int(len(X)), r=round(r, 4), p=p)
        mid = X.date.iloc[len(X) // 2]                  # the two halves of the period (design only)
        hal = []
        for part in (X[X.date < mid], X[X.date >= mid]):
            a, b = part[k["sig"]].values, part[k["tgt"]].values
            if k["partial"]:
                a, b = resid(a, part.US.values), resid(b, part.US.values)
            hal.append(round(float(np.corrcoef(a, b)[0, 1]), 4) if len(part) > 10 else None)
        row["halves"] = hal
        res.append(row)
    return res


def run(phase: str, raw: str):
    T = table(Path(raw))
    rng = np.random.default_rng(SEED + (1 if phase == "holdout" else 0))
    RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "design":
        res = evaluate(T, lambda k: start_of(k["sig"], k["tgt"]), HOLD - dt.timedelta(days=1), rng)
        for r_, q in zip(res, bh([x["p"] for x in res])):
            r_["q"] = round(float(q), 4)
            h = r_.get("halves") or [None, None]
            r_["passes_design"] = bool(q <= 0.10 and None not in h and h[0] * h[1] > 0 and r_["r"] * h[0] > 0)
        out = dict(phase=phase, n_days={u: int((T.und == u).sum()) for u in UNDS}, tests=res,
                   coverage=dict(first=str(T.date.min()), last=str(T.date.max())))
    else:
        flag = RESULTS / "HOLDOUT_READ"
        if flag.exists() or (RESULTS / "holdout.json").exists():
            raise SystemExit("R146 holdout already read")
        if not (RESULTS / "design.json").exists():
            raise SystemExit("commit the design results first")
        flag.write_text(dt.datetime.now(dt.timezone.utc).isoformat() + "\n")
        res = evaluate(T, lambda k: HOLD, END, rng)
        des = {(x["und"], x["sig"], x["tgt"], x["partial"]): x for x in
               json.loads((RESULTS / "design.json").read_text())["tests"]}
        for r_ in res:
            d = des[(r_["und"], r_["sig"], r_["tgt"], r_["partial"])]
            r_["design_r"] = d["r"]
            r_["confirms"] = bool(d["passes_design"] and r_["r"] is not None and d["r"] is not None
                                  and r_["r"] * d["r"] > 0 and abs(r_["r"]) >= 0.5 * abs(d["r"]))
        out = dict(phase=phase, tests=res)
    (RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))
    for x in out["tests"]:
        print({k: x.get(k) for k in ("und", "sig", "tgt", "partial", "n", "r", "p", "q", "halves", "passes_design",
                                       "design_r", "confirms") if k in x})


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] in ("design", "holdout"):
        run(sys.argv[1], sys.argv[2])
    else:
        raise SystemExit(__doc__)
