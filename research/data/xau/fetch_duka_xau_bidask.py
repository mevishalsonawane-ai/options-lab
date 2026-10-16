#!/usr/bin/env python3
"""XAUUSD 1-minute BID and ASK candles from Dukascopy's free public datafeed (15 Oct 2026, for the gold lab pack).

  python -I fetch_duka_xau_bidask.py OUT.parquet [--start 2025-10-15] [--end 2026-10-15] [--cache DIR]

Downloads one LZMA file per UTC day and side (BID, ASK):
    https://datafeed.dukascopy.com/datafeed/XAUUSD/{YYYY}/{MM-1:02d}/{DD:02d}/{SIDE}_candles_min_1.bi5
The months in the URL are 0-based (January = 00). Each decompressed file is a run of fixed-width 24-byte big-endian
records: uint32 seconds from 00:00 UTC of that day, uint32 open, close, low, high (price x 1000 for XAUUSD, so
3 decimals) and float32 volume. Note the order: open, CLOSE, low, high. Raw bytes are untrusted: only whole records are
decoded, nothing else from the file is used. Saturdays are skipped (no trading). A day the server answers 404
twice counts as empty; a day that keeps failing on the network is listed as "missed" at the end.

Output: one parquet (zstd), one row per minute where either side traded (both volumes zero = market closed, dropped):
ts_utc (bar OPEN time, tz UTC), ts_ist, open/high/low/close/volume _bid and _ask, mid_close = (close_bid +
close_ask) / 2, spread = close_ask - close_bid. Licence: Dukascopy's free historical data, for research use only.

Resumable (16 Oct 2026: two earlier runs died on container restarts, the last one at 2026-09-01, and had to start over).
Every downloaded file is kept as-is in a cache folder outside git (default ~/.cache/iraalgo/xau_duka, or --cache), one
file per day and side, written to a temp name and renamed, so a killed run never leaves half a file. A restart reads the
cached days and downloads only the rest. A confirmed-empty day is cached as an empty file; a day that failed on the
network is NOT cached, so the next run tries it again. When Dukascopy is busy (HTTP 429 or 503), the fetch waits longer and
longer instead of giving up after a minute.
"""
import argparse
import datetime as dt
import lzma
import os
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


def get(url, tries=6, opener=urllib.request.urlopen, sleep=time.sleep, throttled_tries=60):
    """Bytes of url; b"" when the server says there is no file (404 twice); None after `tries` network errors or
    `throttled_tries` busy answers. 16 Oct 2026: the datafeed answers 503 "No server is available" and 429 "Too Many
    Requests" while it is busy, then 200 on a later try. The old code took two 503s as "no file", which with a cache
    would have stored a real trading day as empty for good, and gave up on 429s after a minute. Now a 503 waits 3 s,
    6 s, ... (up to 1 minute) and a 429 waits 30 s, 60 s, ... (up to 5 minutes), and neither ever means "empty"."""
    k = t = 0
    while k < tries and t < throttled_tries:
        try:
            return opener(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=30).read()
        except urllib.error.HTTPError as e:
            if e.code in (429, 503):
                t += 1
                sleep(min(30 * t, 300) if e.code == 429 else min(3 * t, 60))
                continue
            if e.code == 404 and k >= 1:
                return b""
            k += 1
            sleep(3 * k)
        except Exception:  # noqa: BLE001 - network hiccups are retried
            k += 1
            sleep(3 * k)
    return None


def cache_path(cache, day, side):
    return os.path.join(cache, f"{day.isoformat()}_{side}.bi5")


def cached_get(cache, day, side, fetch=get):
    """The day's raw bi5 bytes, from the cache when there, else downloaded and cached (atomic temp + rename). b"" is
    a confirmed-empty day and is cached too; None (network failure) is not cached, so a later run retries it."""
    p = cache_path(cache, day, side)
    if os.path.exists(p):
        with open(p, "rb") as f:
            return f.read()
    b = fetch(URL.format(y=day.year, m=day.month - 1, d=day.day, side=side))
    if b is not None:
        tmp = p + ".part"
        with open(tmp, "wb") as f:
            f.write(b)
        os.replace(tmp, p)
    return b


def day_rows(day, side, fetch=get, cache=None):
    if cache:
        b = cached_get(cache, day, side, fetch)
    else:
        b = fetch(URL.format(y=day.year, m=day.month - 1, d=day.day, side=side))
    if b is None:
        return None
    try:
        raw = lzma.decompress(b) if b else b""
    except lzma.LZMAError:  # a bad file is dropped from the cache and counted as missed, so a rerun fetches it again
        if cache and os.path.exists(cache_path(cache, day, side)):
            os.remove(cache_path(cache, day, side))
        return None
    return decode_candles(raw, day)


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


def main(argv=None, fetch=get, pause=0.3):
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--start", default="2025-10-15")
    ap.add_argument("--end", default="2026-10-15")
    ap.add_argument("--cache", default=os.path.expanduser("~/.cache/iraalgo/xau_duka"),
                    help="folder (outside git) holding each downloaded day, so a restarted run resumes")
    a = ap.parse_args(argv)
    os.makedirs(a.cache, exist_ok=True)
    day, end = dt.date.fromisoformat(a.start), dt.date.fromisoformat(a.end)
    frames, missed, empty, hits = {"BID": [], "ASK": []}, [], [], 0
    while day <= end:
        if day.weekday() != 5:  # no Saturday trading
            for side in ("BID", "ASK"):
                hit = os.path.exists(cache_path(a.cache, day, side))
                r = day_rows(day, side, fetch, a.cache)
                if r is None:
                    missed.append(f"{day} {side}")
                elif not r:
                    empty.append(f"{day} {side}")
                if r:
                    frames[side] += r
                if hit:
                    hits += 1
                elif pause:
                    time.sleep(pause)
        if day.day == 1:
            print(day, len(frames["BID"]), "cached", hits, "missed", len(missed), flush=True)
        day += dt.timedelta(days=1)
    m = merge_sides(frames["BID"], frames["ASK"])
    m.to_parquet(a.out, compression="zstd", index=False)
    print("empty (404, cached; expect only holidays such as 25 Dec / 1 Jan)", empty, flush=True)
    print("rows", len(m), m.ts_utc.min(), m.ts_utc.max(), "cached", hits, "missed", missed[:20], len(missed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
