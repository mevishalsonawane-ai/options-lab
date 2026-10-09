"""R3: Dukascopy XAUUSD 1-minute BID candles, one year per parquet (resumable).
Raw bytes are untrusted: only LZMA-decoded fixed-width records are kept.
Usage: python3 -P fetch_duka_xau.py 2016 2017 ... -> scratchpad/hunt/r3/raw/xau_<Y>.parquet (ts UTC)"""
import lzma, struct, sys, time, urllib.request, urllib.error, datetime as dt
from pathlib import Path
import pandas as pd
D = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r3/raw")
def get(url, tries=6):
    for k in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            return urllib.request.urlopen(req, timeout=30).read()
        except urllib.error.HTTPError as e:
            if e.code in (404, 503) and k >= 1:
                return b""
            time.sleep(3 * (k + 1))
        except Exception as e:
            print('retry', url[-40:], str(e)[:80], flush=True)
            time.sleep(3 * (k + 1))
    return None
for y in map(int, sys.argv[1:]):
    out = D / f"xau_{y}.parquet"
    if out.exists():
        continue
    rows, miss = [], 0
    day, end = dt.date(y, 1, 1), min(dt.date(y, 12, 31), dt.date(2023, 10, 3))
    while day <= end:
        if day.weekday() != 5:
            b = get(f"https://datafeed.dukascopy.com/datafeed/XAUUSD/{day.year}/{day.month-1:02d}/{day.day:02d}/BID_candles_min_1.bi5")
            if b:
                raw = lzma.decompress(b) if len(b) else b''
                t0 = dt.datetime(day.year, day.month, day.day)
                for i in range(0, len(raw) // 24 * 24, 24):
                    s, o, c, l, h, v = struct.unpack(">IIIIIf", raw[i:i + 24])
                    if v > 0:
                        rows.append((t0 + dt.timedelta(seconds=s), o / 1e3, h / 1e3, l / 1e3, c / 1e3))
            elif b is None:
                miss += 1
            time.sleep(0.7)
            if day.day == 1 or day.day == 15:
                print(y, day, len(rows), 'missed', miss, flush=True)
        day += dt.timedelta(days=1)
    df = pd.DataFrame(rows, columns=["ts", "open", "high", "low", "close"])
    for c in ["open", "high", "low", "close"]:
        df[c] = df[c].astype("float32")
    df.to_parquet(out, compression="zstd")
    print(y, len(df), df.ts.min(), df.ts.max(), "missed", miss, flush=True)
