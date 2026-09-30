"""Extra series for the direction model, both years: NIFTY 50 and India VIX 1-minute (Upstox), and the near-month
BANKNIFTY future 1-minute from the archive (basis = future - index).

    python research/dump_extra.py <out.parquet>
"""
from __future__ import annotations

import os
import sys
from datetime import date

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from range_fade_long import INDEX_KEYS, upstox_index  # noqa: E402

INDEX_KEYS["INDIAVIX"] = "NSE_INDEX%7CIndia%20VIX"


def main():
    frm, to = date(2024, 2, 1), date(2026, 3, 1)
    parts = []
    for name in ("NIFTY", "INDIAVIX"):
        ix = upstox_index(frm, to, name)
        if ix is not None:
            parts.append(ix.reset_index().assign(series=name))
            print(name, len(ix), flush=True)
    # futures for both years (500 archive sessions)
    sys.path.insert(0, ".")
    from options_lab.backfill import archive
    from range_fade_long import from_archive
    zf = archive.open_archive()
    days = archive.available_days(zf.namelist(), "BANKNIFTY")[-500:]
    fut_rows = []
    for i, day in enumerate(days):
        try:
            sess = archive.read_session(zf, "BANKNIFTY", day)
        except Exception as e:  # noqa: BLE001
            print(day, e, flush=True)
            continue
        _, fut = from_archive(sess)
        if len(fut):
            fut_rows.append(fut.reset_index().assign(series="BNFUT"))
        if i % 50 == 0:
            print(f"fut {i}/{len(days)}", flush=True)
    parts += fut_rows
    df = pd.concat(parts, ignore_index=True)
    df.to_parquet(sys.argv[1], compression="zstd", index=False)
    print(df.groupby("series").ts.agg(["count", "min", "max"]))


if __name__ == "__main__":
    main()
