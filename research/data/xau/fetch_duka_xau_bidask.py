#!/usr/bin/env python3
"""XAUUSD 1-minute BID and ASK candles from Dukascopy's free public datafeed (15 Oct 2026, for the gold lab pack).

  python -I fetch_duka_xau_bidask.py OUT.parquet [--start 2025-10-15] [--end 2026-10-15]

Downloads one LZMA file per UTC day and side (BID, ASK):
    https://datafeed.dukascopy.com/datafeed/XAUUSD/{YYYY}/{MM-1:02d}/{DD:02d}/{SIDE}_candles_min_1.bi5
The months in the URL are 0-based (January = 00). Each decompressed file is a run of fixed-width 24-byte big-endian
records: uint32 seconds from 00:00 UTC of that day, uint32 open, close, low, high (price x 1000 for XAUUSD, so
3 decimals) and float32 volume. Note the order: open, CLOSE, low, high. Raw bytes are untrusted: only whole records are
decoded, nothing else from the file is used. Saturdays are skipped (no trading). A day the server answers 404 / 503
twice counts as empty; a day that keeps failing on the network is listed as "missed" at the end.

Output: one parquet (zstd), one row per minute where either side traded (both volumes zero = market closed, dropped):
ts_utc (bar OPEN time, tz UTC), ts_ist, open/high/low/close/volume _bid and _ask, mid_close = (close_bid +
close_ask) / 2, spread = close_ask - close_bid. Licence: Dukascopy's free historical data, for research use only.
"""
import argparse
import datetime as dt
import lzma
import struct
import sys
import time
import urllib.error
import urllib.request

import pandas as pd

RECORD = struct.Struct(">IIIIIf")  # 24 bytes: seconds, open, close, low, high, volume
SCALE = 1e3                        # XAUUSD point = 0.001
URL = "https://datafeed.dukascopy.com/datafeed/XAUUSD/{y}/{m:02d}/{d:02d}/{side}_candles_min_1.bi5"
COLS = ["open", "high", "low", "close", "volume"]


def decode_candles(raw, day):
    """[(ts, open, high, low, close, volume), ...] from decompressed bi5 bytes for UTC date `day`. A trailing partial
    record (fewer than 24 bytes) is ignored."""
    t0 = dt.datetime(day.year, day.month, day.day)
    out = []
    for i in range(0, len(raw) // RECORD.size * RECORD.size, RECORD.size):
        s, o, c, lo, hi, v = RECORD.unpack_from(raw, i)
        out.append((t0 + dt.timedelta(seconds=s), o / SCALE, hi / SCALE, lo / SCALE, c / SCALE, float(v)))
    return out


def get(url, tries=6, opener=urllib.request.urlopen, sleep=time.sleep):
    """Bytes of url; b"" when the server says there is no file (404/503 twice); None after `tries` network errors."""
    for k in range(tries):
        try:
            return opener(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=30).read()
        except urllib.error.HTTPError as e:
            if e.code in (404, 503) and k >= 1:
                return b""
            sleep(3 * (k + 1))
        except Exception:  # noqa: BLE001 - network hiccups are retried
            sleep(3 * (k + 1))
    return None


def day_rows(day, side, fetch=get):
    b = fetch(URL.format(y=day.year, m=day.month - 1, d=day.day, side=side))
    if b is None:
        return None
    return decode_candles(lzma.decompress(b) if b else b"", day)


def merge_sides(bid_rows, ask_rows):
    """One frame from BID and ASK candle lists (see the module docstring for the columns)."""
    b = pd.DataFrame(bid_rows, columns=["ts"] + COLS)
    a = pd.DataFrame(ask_rows, columns=["ts"] + COLS)
    m = b.merge(a, on="ts", suffixes=("_bid", "_ask"), how="outer").sort_values("ts")
    m = m[(m.volume_bid > 0) | (m.volume_ask > 0)]  # drop empty (closed-market) minutes
    m.insert(0, "ts_utc", pd.to_datetime(m.pop("ts")).dt.tz_localize("UTC"))
    m.insert(1, "ts_ist", m.ts_utc.dt.tz_convert("Asia/Kolkata"))
    m["mid_close"] = (m.close_bid + m.close_ask) / 2
    m["spread"] = m.close_ask - m.close_bid
    return m.reset_index(drop=True)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--start", default="2025-10-15")
    ap.add_argument("--end", default="2026-10-15")
    a = ap.parse_args(argv)
    day, end = dt.date.fromisoformat(a.start), dt.date.fromisoformat(a.end)
    frames, missed = {"BID": [], "ASK": []}, []
    while day <= end:
        if day.weekday() != 5:  # no Saturday trading
            for side in ("BID", "ASK"):
                r = day_rows(day, side)
                if r is None:
                    missed.append(f"{day} {side}")
                else:
                    frames[side] += r
                time.sleep(0.3)
        if day.day == 1:
            print(day, len(frames["BID"]), "missed", len(missed), flush=True)
        day += dt.timedelta(days=1)
    m = merge_sides(frames["BID"], frames["ASK"])
    m.to_parquet(a.out, compression="zstd", index=False)
    print("rows", len(m), m.ts_utc.min(), m.ts_utc.max(), "missed", missed[:20], len(missed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
