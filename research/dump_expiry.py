"""Expiry-day sessions: the index minutes plus the chain EXPIRING THAT DAY (strikes within 1,000 points of 09:20).

    python research/dump_expiry.py <sessions> <out.parquet> BANKNIFTY NIFTY

BANKNIFTY expires monthly (about 12 days a year); NIFTY weekly (about 50) - same columns as dump_year.py plus
`underlying`.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from range_fade_long import archive_days  # noqa: E402


def main():
    n, path, unds = int(sys.argv[1]), sys.argv[2], sys.argv[3:]
    parts = []
    for u in unds:
        for day, ix, opts in archive_days(n, u, only=lambda d, o: (o.expiry == d).any()):
            if ix is None or len(ix) < 300:
                continue
            spot = ix.between_time("09:20", "09:20").close
            spot = spot.iloc[0] if len(spot) else ix.close.iloc[0]
            ch = opts[(opts.expiry == day) & (np.abs(opts.strike - spot) <= 1000)].copy()
            i = ix.reset_index()[["ts", "open", "high", "low", "close"]].assign(right="IX", expiry=pd.NaT, strike=np.nan,
                                                                                  volume=0, open_interest=0)
            parts += [i.assign(day=day, underlying=u), ch.assign(day=day, underlying=u)]
            print(f"{u} {day}: {len(ch):,} option rows", flush=True)
    df = pd.concat(parts, ignore_index=True)
    df["expiry"] = pd.to_datetime(df["expiry"])
    df["day"] = pd.to_datetime(df["day"])
    df.to_parquet(path, compression="zstd", index=False)
    print(f"wrote {path}: {df.groupby('underlying').day.nunique().to_dict()} days, {len(df):,} rows")


if __name__ == "__main__":
    main()
