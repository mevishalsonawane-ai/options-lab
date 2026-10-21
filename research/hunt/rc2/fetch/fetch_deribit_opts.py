"""Deribit public history (no keys).
mode 'expiry': for every 3rd Deribit daily expiry E (08:00 UTC) before the holdout, all option trades in [E-10h, E)
  on instruments expiring at E (traded prices, iv, mark, index) -> zero-to-hero anatomy with real prices.
mode 'ivchk': 300 random hours per coin in the Delta-overlap span; all option trades (<=1000) in the hour before,
  expiries <= 4 days -> compare Deribit IV with Delta's mark.
Usage: python3 -I fetch_deribit_opts.py MODE OUTDIR"""
import sys, os, json, time, urllib.request, random
sys.path.append("/root/.local/lib/python3.11/site-packages")
from concurrent.futures import ThreadPoolExecutor
import pandas as pd
MODE, OUT = sys.argv[1], sys.argv[2]
API = "https://history.deribit.com/api/v2/public/get_last_trades_by_currency_and_time"
MON = {m: i + 1 for i, m in enumerate("JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC".split())}
KEEP = ["trade_id", "timestamp", "instrument_name", "price", "mark_price", "iv", "index_price", "direction", "amount"]

def expiry(name):
    d = name.split("-")[1]
    return pd.Timestamp(int("20" + d[-2:]), MON[d[-5:-2]], int(d[:-5]), 8, tz="UTC")

def call(cur, s, e, sort):
    u = f"{API}?currency={cur}&kind=option&start_timestamp={s}&end_timestamp={e}&count=1000&sorting={sort}"
    for a in range(8):
        try:
            with urllib.request.urlopen(u, timeout=60) as r: return json.load(r)["result"]
        except Exception:
            time.sleep(2 + 4 * a)
    raise RuntimeError(u)

def window(cur, s, e):
    rows, seen = [], set()
    while True:
        r = call(cur, s, e, "asc"); tr = r["trades"]
        new = [t for t in tr if t["trade_id"] not in seen]
        for t in new: seen.add(t["trade_id"]); rows.append({k: t.get(k) for k in KEEP})
        if not r.get("has_more") or not new: break
        s = tr[-1]["timestamp"]; time.sleep(0.1)
    return rows

def do_expiry(job):
    cur, E = job
    fn = f"{OUT}/{cur}_{E:%Y%m%d}.parquet"
    if os.path.exists(fn): return 0
    e = int(E.timestamp() * 1000) - 1; s = e - 10 * 3600000 + 1
    rows = [x for x in window(cur, s, e) if expiry(x["instrument_name"]) == E]
    pd.DataFrame(rows).to_parquet(fn); return len(rows)

def do_iv(job):
    cur, H = job
    fn = f"{OUT}/{cur}_{H:%Y%m%d%H}.parquet"
    if os.path.exists(fn): return 0
    e = int(H.timestamp() * 1000) - 1; s = e - 3600000 + 1
    tr = call(cur, s, e, "desc")["trades"]
    rows = []
    for t in tr:
        try: ex = expiry(t["instrument_name"])
        except Exception: continue
        if (ex - H).total_seconds() <= 4 * 86400: rows.append({k: t.get(k) for k in KEEP})
    pd.DataFrame(rows).to_parquet(fn); return len(rows)

if MODE == "expiry":
    days = pd.date_range("2022-01-02 08:00", "2026-04-12 08:00", freq="3D", tz="UTC")
    jobs = [(c, d) for d in days for c in ("BTC", "ETH")]; f = do_expiry
else:
    random.seed(20261013)
    span = pd.date_range("2024-06-01", "2026-10-12", freq="h", tz="UTC")
    jobs = [(c, h) for c in ("BTC", "ETH") for h in random.sample(list(span), 300)]; f = do_iv
with ThreadPoolExecutor(3) as ex:
    for i, n in enumerate(ex.map(f, jobs)):
        if i % 50 == 0: print(MODE, i, len(jobs), n, time.strftime("%H:%M:%S"), flush=True)
print("done", flush=True)
