"""R13 data: NIFTY / BANKNIFTY / INDIA VIX daily bars.

Primary source: local Dhan daily candles (scratchpad/dhan/data/daily/IDX_I/<SYM>/*.parquet, 2006+).
Cross-check + top-up: Yahoo Finance chart API (^NSEI, ^NSEBANK, ^INDIAVIX) cached in scratchpad/hunt/r13/raw.
Run: venv/bin/python -I data.py   (writes scratchpad/hunt/r13/daily.parquet)
"""
import glob, json, os, sys
import numpy as np, pandas as pd

S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
OUT = f"{S}/hunt/r13"
RAW = f"{OUT}/raw"


def dhan(sym):
    fs = sorted(glob.glob(f"{S}/dhan/data/daily/IDX_I/{sym}/*.parquet"))
    d = pd.concat([pd.read_parquet(f) for f in fs])
    d["date"] = pd.to_datetime(d["ts"]).dt.tz_localize(None).dt.normalize()
    d = d.drop_duplicates("date").set_index("date").sort_index()[["open", "high", "low", "close"]]
    return d[(d["close"] > 0)]


def yahoo(tick):
    j = json.load(open(f"{RAW}/y_%5E{tick}.json"))["chart"]["result"][0]
    q = j["indicators"]["quote"][0]
    idx = pd.to_datetime(np.array(j["timestamp"]) + j["meta"]["gmtoffset"], unit="s").normalize()
    d = pd.DataFrame({k: q[k] for k in ["open", "high", "low", "close"]}, index=idx).dropna()
    d = d[~d.index.duplicated(keep="last")]
    return d[d["close"] > 0]


def build(sym, tick, log):
    a, y = dhan(sym), yahoo(tick)
    common = a.index.intersection(y.index)
    rel = (a.loc[common, "close"] / y.loc[common, "close"] - 1).abs()
    log.append(f"{sym}: dhan {a.index[0].date()}..{a.index[-1].date()} n={len(a)}; "
               f"yahoo {y.index[0].date()}..{y.index[-1].date()} n={len(y)}; overlap n={len(common)}, "
               f"close |diff| median {rel.median()*100:.3f}% , >0.5%: {(rel>0.005).sum()} days, >2%: {(rel>0.02).sum()} days")
    big = rel[rel > 0.02]
    if len(big):
        log.append(f"   >2% mismatches (first 5): {[str(x.date()) for x in big.index[:5]]}")
    extra = y[y.index > a.index[-1]]
    log.append(f"   topped up {len(extra)} days from Yahoo after {a.index[-1].date()}")
    d = pd.concat([a, extra])
    d.columns = [f"{sym}_{c}" for c in d.columns]
    return d


if __name__ == "__main__":
    log = []
    n = build("NIFTY", "NSEI", log)
    b = build("BANKNIFTY", "NSEBANK", log)
    v = build("INDIA_VIX", "INDIAVIX", log)
    d = n.join(b, how="left").join(v[["INDIA_VIX_close"]], how="left")
    d.to_parquet(f"{OUT}/daily.parquet")
    log.append(f"saved {len(d)} rows {d.index[0].date()}..{d.index[-1].date()}; VIX first valid {d['INDIA_VIX_close'].first_valid_index().date()}")
    print("\n".join(log))
    open(f"{OUT}/data_log.txt", "w").write("\n".join(log))
