"""h27: download NSE participant-wise OI CSVs (published each evening) for every NSE trading day 2019+.
Dates come from the Yahoo ^NSEI daily file. Run: python3 -I fetch_poi.py <raw_dir>"""
import json, os, sys, time, subprocess, datetime as dt
R = sys.argv[1]
W, K = int(sys.argv[2]), int(sys.argv[3])   # worker id, number of workers
os.makedirs(f"{R}/poi", exist_ok=True)
j = json.load(open(f"{R}/yahoo/_NSEI.json"))["chart"]["result"][0]
days = sorted({dt.datetime.utcfromtimestamp(t + 19800).date() for t in j["timestamp"]})
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
for d in days:
    if d < dt.date(2020, 4, 1) or d.toordinal() % K != W:
        continue
    f = f"{R}/poi/{d:%Y%m%d}.csv"
    if os.path.exists(f) and os.path.getsize(f) > 300:
        continue
    u = f"https://nsearchives.nseindia.com/content/nsccl/fao_participant_oi_{d:%d%m%Y}.csv"
    r = subprocess.run(["curl", "-sS", "-m", "15", "-A", UA, "-o", f, "-w", "%{http_code}", u], capture_output=True, text=True)
    if r.stdout != "200":
        print(d, r.stdout, r.stderr.strip()[:80], flush=True)
        if os.path.exists(f): os.remove(f)
    time.sleep(0.25)
print("done")
