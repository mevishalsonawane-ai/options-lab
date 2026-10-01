"""1-minute index candles (no options) for an index the options archive does not hold, from Upstox, for the same
two years as the BANKNIFTY files. Written in dump_year.py's layout (right = IX rows only).

    python research/dump_index.py <UNDERLYING> <from YYYY-MM-DD> <to YYYY-MM-DD> <out.parquet>
"""
import os
import sys
from datetime import date

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from range_fade_long import upstox_index  # noqa: E402


def main():
    u, frm, to, out = sys.argv[1], date.fromisoformat(sys.argv[2]), date.fromisoformat(sys.argv[3]), sys.argv[4]
    ix = upstox_index(frm, to, u)
    if ix is None or ix.empty:
        raise SystemExit(f"no {u} candles from Upstox")
    df = ix.reset_index()[["ts", "open", "high", "low", "close"]]
    df["ts"] = pd.to_datetime(df.ts)
    df = df[(df.ts.dt.time >= pd.Timestamp("09:15").time()) & (df.ts.dt.time <= pd.Timestamp("15:29").time())]
    df = df.assign(right="IX", expiry=pd.NaT, strike=float("nan"), volume=0, open_interest=0, day=pd.to_datetime(df.ts.dt.date))
    df.to_parquet(out, compression="zstd", index=False)
    print(f"wrote {out}: {df.day.nunique()} days, {len(df):,} rows")


if __name__ == "__main__":
    main()
