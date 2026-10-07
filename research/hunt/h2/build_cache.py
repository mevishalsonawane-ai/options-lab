"""Build dense minute arrays for the 214 F&O stocks + NIFTY: (stock, day, 375) float32 o/h/l/c/v.

    python3 -I research/hunt/h2/build_cache.py <dhan-data dir> <cache dir>

Days = NIFTY sessions from the first stock-minute date (2024-10-07) on, with >= 300 NIFTY minutes in 09:15-15:29.
Missing stock minutes are NaN (volume 0). Market data is untrusted: read with pandas only.
"""
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

DATA, CACHE = sys.argv[1], sys.argv[2]
M = os.path.join(DATA, "candles", "minute")
os.makedirs(CACHE, exist_ok=True)


def load(path_dir):
    fs = sorted(f for f in os.listdir(path_dir) if f.endswith(".parquet"))
    df = pd.concat([pd.read_parquet(os.path.join(path_dir, f)) for f in fs], ignore_index=True)
    ts = df["ts"].dt.tz_localize(None)
    df["day"] = ts.dt.normalize()
    df["mi"] = (ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)).astype(int)
    return df[(df.mi >= 0) & (df.mi < 375)]


nifty = load(os.path.join(M, "IDX_I", "NIFTY"))
nifty = nifty[nifty.day >= "2024-10-07"]
cnt = nifty.groupby("day").size()
days = cnt[cnt >= 300].index
days = days[days < pd.Timestamp("2026-10-06")]  # 2026-10-06 is a partial session in the dump
D = len(days)
dpos = pd.Series(np.arange(D), index=days)
names = sorted(os.listdir(os.path.join(M, "NSE_EQ")))
S = len(names) + 1
F = {k: np.full((S, D, 375), np.nan, dtype=np.float32) for k in "ohlc"}
F["v"] = np.zeros((S, D, 375), dtype=np.float32)


def put(i, df):
    df = df[df.day.isin(days)]
    di = dpos[df.day].to_numpy()
    mi = df.mi.to_numpy()
    for k, col in zip("ohlcv", ["open", "high", "low", "close", "volume"]):
        F[k][i, di, mi] = df[col].to_numpy(dtype=np.float32)


put(0, nifty)
for i, n in enumerate(names, start=1):
    put(i, load(os.path.join(M, "NSE_EQ", n)))
    if i % 40 == 0:
        print(i, n, flush=True)
for k in "ohlcv":
    np.save(os.path.join(CACHE, f"{k}.npy"), F[k])
np.save(os.path.join(CACHE, "days.npy"), np.array(days.values, dtype="datetime64[D]"))
with open(os.path.join(CACHE, "names.txt"), "w") as fh:
    fh.write("\n".join(["NIFTY"] + names))
print("done", S, D)
