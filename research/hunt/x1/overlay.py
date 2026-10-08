"""X1: build an overlay copy of the Dhan data tree (symlinks) that adds 6 Oct afternoon, 7 Oct and 8 Oct 2026 from the
minutes fetched by fetch.py, so obuy / h19 code can replay those days unchanged.
  options/<U>/MONTH/<SIDE>/2026x1.parquet : new rows (Block globs 2026*.parquet; the original file wins duplicates)
  candles/minute/IDX_I/<U>/2026.parquet    : original + new minutes (h19.supplement reads exactly this file)
Run: python3 -I overlay.py   -> scratchpad/hunt/x1/data (point OBUY_DATA at it)"""
import json, os, sys, math
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pandas as pd
SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
SRC = f"{SCR}/dhan/repo/dhan-data"
RAW = f"{SCR}/hunt/x1/raw"
DST = f"{SCR}/hunt/x1/data"
STEP = {"BANKNIFTY": 100, "FINNIFTY": 50, "MIDCPNIFTY": 25}
TZ = "Asia/Kolkata"


def link_tree(src, dst, skip):
    os.makedirs(dst, exist_ok=True)
    for n in os.listdir(src):
        s, d = os.path.join(src, n), os.path.join(dst, n)
        if os.path.relpath(s, SRC) in skip:
            link_tree(s, d, skip) if os.path.isdir(s) else None
            continue
        if not os.path.lexists(d):
            os.symlink(s, d)


def load(name):
    d = json.load(open(f"{RAW}/{name}.json"))
    x = pd.DataFrame({"ts": pd.to_datetime(d["timestamp"], unit="s", utc=True).tz_convert(TZ),
                      "open": d["open"], "high": d["high"], "low": d["low"], "close": d["close"], "volume": d["volume"]})
    if "open_interest" in d:
        x["oi"] = d["open_interest"]
    return x


def main():
    skip = set()
    for u in STEP:
        skip |= {"options", f"options/{u}", f"options/{u}/MONTH", f"options/{u}/MONTH/CALL", f"options/{u}/MONTH/PUT",
                 "candles", "candles/minute", "candles/minute/IDX_I", f"candles/minute/IDX_I/{u}",
                 f"candles/minute/IDX_I/{u}/2026.parquet"}
    link_tree(SRC, DST, skip)
    for u, st in STEP.items():
        orig = pd.read_parquet(f"{SRC}/candles/minute/IDX_I/{u}/2026.parquet")
        new = load(f"IDX_{u}")
        new = new[new.ts > orig.ts.max()]
        new["ts"] = new.ts.astype(orig.ts.dtype)
        for c in ("open", "high", "low", "close"):
            new[c] = new[c].astype("float32")
        new["volume"] = new["volume"].astype("int64")
        allx = pd.concat([orig, new[orig.columns]], ignore_index=True)
        allx.to_parquet(f"{DST}/candles/minute/IDX_I/{u}/2026.parquet")
        spot = load(f"IDX_{u}").set_index("ts")["close"]
        for side, ot in (("CALL", "CE"), ("PUT", "PE")):
            o = pd.read_parquet(f"{SRC}/options/{u}/MONTH/{side}/2026.parquet", columns=["ts"])
            last = o.ts.max()
            parts = []
            for f in sorted(os.listdir(RAW)):
                if not (f.startswith(f"{u}_") and f.endswith(f"{ot}.json")):
                    continue
                k = int(f[len(u) + 1:-7])
                x = load(f[:-5])
                x = x[x.ts > last]
                if not len(x):
                    continue
                sp = spot.reindex(x.ts).values
                atm = np.floor(sp / st + 0.5) * st
                off = np.rint((k - atm) / st)
                x = x.assign(strike=float(k), spot=sp, offset=off, iv=np.nan)
                x = x[np.abs(x.offset) <= 10]
                parts.append(x)
            y = pd.concat(parts, ignore_index=True)
            out = pd.DataFrame({"iv": y.iv.astype("float32"), "oi": y.oi.astype("int64"), "strike": y.strike.astype("float32"),
                                "spot": y.spot.astype("float32"), "open": y.open.astype("float32"), "high": y.high.astype("float32"),
                                "low": y.low.astype("float32"), "close": y.close.astype("float32"),
                                "volume": y.volume.astype("int64"), "ts": y.ts.astype(o.ts.dtype), "offset": y.offset.astype("int8")})
            os.makedirs(f"{DST}/options/{u}/MONTH/{side}", exist_ok=True)
            out.to_parquet(f"{DST}/options/{u}/MONTH/{side}/2026x1.parquet")
            print(u, side, "orig last", last, "added", len(out), out.ts.min(), out.ts.max())


if __name__ == "__main__":
    main()
