"""R2: Dukascopy 1-minute BID candles (daily bi5 files), resumable per-day cache. Untrusted raw bytes: only LZMA-decoded
fixed-width records are kept. usage: fetch_duka.py START END SYM... ; then fetch_duka.py build SYM..."""
import lzma, struct, sys, time, urllib.request, urllib.error
import datetime as dt
from pathlib import Path
import pandas as pd
R = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r2")
C = R / "raw" / "duka"; D = R / "data"


def get(url):
    for k in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            return urllib.request.urlopen(req, timeout=30).read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return b""
            time.sleep(2 + 3 * k)
        except Exception:
            time.sleep(2 + 3 * k)
    return None


if sys.argv[1] == "build":
    for sym in sys.argv[2:]:
        rows = []
        for f in sorted((C / sym).glob("*.bi5")):
            b = f.read_bytes()
            if not b:
                continue
            raw = lzma.decompress(b); t0 = dt.datetime.strptime(f.stem, "%Y%m%d")
            for i in range(0, len(raw) // 24 * 24, 24):
                s, o, c, l, h, v = struct.unpack(">IIIIIf", raw[i:i + 24])
                if v > 0:
                    rows.append((t0 + dt.timedelta(seconds=s), o / 1e3, h / 1e3, l / 1e3, c / 1e3, v))
        df = pd.DataFrame(rows, columns=["ts", "open", "high", "low", "close", "vol"]); df.to_parquet(D / f"duka_{sym}.parquet")
        print(sym, len(df), df.ts.min(), df.ts.max())
    sys.exit()
import os
STEP = int(os.environ.get('STEP', '1'))
a, b = dt.date.fromisoformat(sys.argv[1]), dt.date.fromisoformat(sys.argv[2])
for sym in sys.argv[3:]:
    (C / sym).mkdir(parents=True, exist_ok=True)
    day, got, miss = a, 0, 0
    while day <= b:
        f = C / sym / f"{day:%Y%m%d}.bi5"
        if day.weekday() != 5 and not f.exists():
            x = get(f"https://datafeed.dukascopy.com/datafeed/{sym}/{day.year}/{day.month-1:02d}/{day.day:02d}/BID_candles_min_1.bi5")
            if x is None:
                miss += 1
            else:
                f.write_bytes(x); got += 1
            time.sleep(0.3)
        day += dt.timedelta(days=STEP if day.weekday() < 5 else 1)
    print(sym, a, b, "got", got, "missed", miss, flush=True)
