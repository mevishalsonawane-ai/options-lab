"""R3: Dukascopy XAUUSD 1-minute BID candles, SAMPLED: one full week (Sun-Fri) out of every 3, Jan 2019 - Sep 2023
(the server throttles: 503 = back off and retry; only 404 counts as 'no file'). Single process, gentle.
Raw bytes are untrusted: only LZMA-decoded fixed-width records are kept. -> scratchpad/hunt/r3/raw/xauS_<Y>.parquet (ts UTC)
In analysis the first trading day of each block is warm-up (its signals are dropped)."""
import lzma, struct, sys, time, urllib.request, urllib.error, datetime as dt
from pathlib import Path
import pandas as pd
D = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r3/raw")


def get(url):
    for k in range(10):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            return urllib.request.urlopen(req, timeout=30).read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return b""
            time.sleep(min(60, 10 * (k + 1)))
        except Exception:
            time.sleep(min(60, 5 * (k + 1)))
    print("GAVE UP", url[-36:], flush=True)
    return None


for y in map(int, sys.argv[1:]):
    out = D / f"xauS_{y}.parquet"
    if out.exists():
        continue
    rows, miss = [], 0
    d0 = dt.date(y, 1, 1)
    d0 -= dt.timedelta(days=(d0.weekday() + 1) % 7)       # back to Sunday
    wk = 0
    while d0 <= min(dt.date(y, 12, 31), dt.date(2023, 9, 29)):
        if wk % 3 == 0:
            for i in range(6):                              # Sun..Fri
                day = d0 + dt.timedelta(days=i)
                if day.year != y:
                    continue
                b = get(f"https://datafeed.dukascopy.com/datafeed/XAUUSD/{day.year}/{day.month-1:02d}/{day.day:02d}/BID_candles_min_1.bi5")
                if b is None:
                    miss += 1
                elif len(b):
                    raw = lzma.decompress(b)
                    t0 = dt.datetime(day.year, day.month, day.day)
                    for j in range(0, len(raw) // 24 * 24, 24):
                        s, o, c, l, h, v = struct.unpack(">IIIIIf", raw[j:j + 24])
                        if v > 0:
                            rows.append((t0 + dt.timedelta(seconds=s), o / 1e3, h / 1e3, l / 1e3, c / 1e3))
                time.sleep(1.0)
            print(y, d0, len(rows), "missed", miss, flush=True)
        d0 += dt.timedelta(days=7); wk += 1
    df = pd.DataFrame(rows, columns=["ts", "open", "high", "low", "close"])
    for c in ["open", "high", "low", "close"]:
        df[c] = df[c].astype("float32")
    df.to_parquet(out, compression="zstd")
    print("SAVED", y, len(df), "missed", miss, flush=True)
