"""Save a compact copy of the long BANKNIFTY history: the index minutes plus, for each day, the nearest expiry after
the day at strikes within 300 (or argv[3]) points of the 09:20 index (both rights), 1-minute OHLC with volume and OI.
Written as one parquet (columns: day, right IX/CE/PE, expiry, strike, ts, open, high, low, close, volume,
open_interest) that research/*.py read with the source argument `file:<path>`."""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from range_fade_long import archive_days  # noqa: E402


def main():
    n, path = int(sys.argv[1]), sys.argv[2]
    width = float(sys.argv[3]) if len(sys.argv) > 3 else 300.0
    parts = []
    for day, ix, opts in archive_days(n):
        if ix is None or len(ix) < 300 or opts.empty:
            continue
        spot = ix.between_time("09:20", "09:20").close
        spot = spot.iloc[0] if len(spot) else ix.close.iloc[0]
        exps = sorted(e for e in opts.expiry.unique() if e > day)
        if not exps:
            continue
        ch = opts[(opts.expiry == exps[0]) & (np.abs(opts.strike - spot) <= width)].copy()
        i = ix.reset_index()[["ts", "open", "high", "low", "close"]].assign(right="IX", expiry=pd.NaT, strike=np.nan,
                                                                              volume=0, open_interest=0)
        parts += [i.assign(day=day), ch.assign(day=day)]
    df = pd.concat(parts, ignore_index=True)
    df["expiry"] = pd.to_datetime(df["expiry"])
    df["day"] = pd.to_datetime(df["day"])
    df.to_parquet(path, compression="zstd", index=False)
    print(f"wrote {path}: {df.day.nunique()} days, {len(df):,} rows, {os.path.getsize(path) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
