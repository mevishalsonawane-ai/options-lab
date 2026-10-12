#!/usr/bin/env python3
"""Download a sample of NSE F&O bhavcopies (one weekday in every five, Oct 2021 -> today) for validation.
Old format (to 5 Jul 2024): archives.nseindia.com .../DERIVATIVES/YYYY/MON/foDDMONYYYYbhav.csv.zip
UDiFF format (from 8 Jul 2024): nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_YYYYMMDD_F_0000.csv.zip
Also fetches Yahoo daily history (^NSEI ^NSEBANK ^INDIAVIX ^BSESN) into OUT_DIR/yahoo, and NSE's all-index closing file (ind_close_all_DDMMYYYY.csv) for every sampled date into OUT_DIR/idx.
Usage: python -I bhav_fetch.py OUT_DIR [step_weekdays]"""
import os, sys, time, datetime as dt, requests
out = sys.argv[1]; step = int(sys.argv[2]) if len(sys.argv) > 2 else 5
os.makedirs(out, exist_ok=True)
H = {"User-Agent": "Mozilla/5.0", "Accept": "*/*"}
d, i, got = dt.date(2021, 10, 1), 0, 0
while d <= dt.date.today():
    if d.weekday() < 5:
        if i % step == 0:
            if d < dt.date(2024, 7, 8):
                u = f"https://archives.nseindia.com/content/historical/DERIVATIVES/{d.year}/{d.strftime('%b').upper()}/fo{d.strftime('%d%b%Y').upper()}bhav.csv.zip"
            else:
                u = f"https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_{d:%Y%m%d}_F_0000.csv.zip"
            fn = f"{out}/{d}.csv.zip"
            if not os.path.exists(fn) and not os.path.exists(fn + ".missing"):
                for a in range(3):
                    try:
                        r = requests.get(u, headers=H, timeout=60)
                        break
                    except requests.RequestException:
                        time.sleep(3)
                else:
                    r = None
                if r is not None and r.status_code == 200 and r.content[:2] == b"PK":
                    open(fn, "wb").write(r.content); got += 1
                else:
                    # holiday (or not published): try the next weekday instead
                    open(fn + ".missing", "w").write(str(getattr(r, "status_code", "noresp")))
                    i -= 1
                time.sleep(0.5)
        i += 1
    d += dt.timedelta(days=1)
print("downloaded", got)
os.makedirs(f"{out}/idx", exist_ok=True)
for f in sorted(os.listdir(out)):
    if not f.endswith(".csv.zip"):
        continue
    d = dt.date.fromisoformat(f[:10]); fn = f"{out}/idx/{d}.csv"
    if os.path.exists(fn):
        continue
    try:
        r = requests.get(f"https://nsearchives.nseindia.com/content/indices/ind_close_all_{d:%d%m%Y}.csv", headers=H, timeout=60)
        if r.status_code == 200 and r.content.startswith(b"Index Name"):
            open(fn, "wb").write(r.content)
    except requests.RequestException:
        pass
    time.sleep(0.3)
print("index close files", len(os.listdir(f"{out}/idx")))
os.makedirs(f"{out}/yahoo", exist_ok=True)
for t in ("NSEI", "NSEBANK", "INDIAVIX", "BSESN"):
    r = requests.get(f"https://query1.finance.yahoo.com/v8/finance/chart/%5E{t}?period1=946684800&period2={int(time.time())}&interval=1d",
                     headers=H, timeout=60)
    if r.ok:
        open(f"{out}/yahoo/%5E{t}.json", "wb").write(r.content)
