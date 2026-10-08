"""For every hour H, fetch the (up to) 1000 most recent Deribit option trades in [H-60min, H) from the public
history API (no keys) and keep a per-expiry summary for expiries <= 10 days: near-ATM trade IV and the
effective half-spread vs Deribit mark. Nothing after H is used. Usage: python3 -I fetch_trades.py OUTDIR CUR"""
import sys, json, time, urllib.request, os, math
sys.path.append("/root/.local/lib/python3.11/site-packages")  # pandas deps under python -I
from concurrent.futures import ThreadPoolExecutor
import pandas as pd, numpy as np
OUT, CUR = sys.argv[1], sys.argv[2]
API = "https://history.deribit.com/api/v2/public/get_last_trades_by_currency_and_time"
MON = {m: i + 1 for i, m in enumerate("JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC".split())}

def expiry(name):
    d = name.split("-")[1]
    return pd.Timestamp(int("20" + d[-2:]), MON[d[-5:-2]], int(d[:-5]), 8, tz="UTC")

def fetch(H):
    e = int(H.timestamp() * 1000) - 1; s = e - 3600000 + 1
    url = f"{API}?currency={CUR}&kind=option&start_timestamp={s}&end_timestamp={e}&count=1000&sorting=desc"
    for a in range(8):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                tr = json.load(r)["result"]["trades"]
            break
        except Exception:
            time.sleep(2 + 4 * a)
    else:
        return [dict(H=H, err=1)]
    rows = {}
    for t in tr:
        nm = t["instrument_name"]
        try:
            ex = expiry(nm)
        except Exception:
            continue
        T = (ex - H).total_seconds() / 31536000
        if T <= 0 or T > 10 / 365: continue
        K = float(nm.split("-")[2]); iv = t.get("iv") or 0; idx = t["index_price"]
        r = rows.setdefault(ex, dict(n=0, ivs=[], hs=[], last=None))
        r["n"] += 1
        if iv <= 0: continue
        m = math.log(K / idx) / (iv / 100 * math.sqrt(T))
        if abs(m) <= 0.5:
            r["ivs"].append(iv)
            if r["last"] is None: r["last"] = (iv, t["timestamp"], idx)
            mk = t.get("mark_price") or 0
            if mk > 0: r["hs"].append((1 if t["direction"] == "buy" else -1) * (t["price"] - mk) / mk)
    out = []
    for ex, r in rows.items():
        out.append(dict(H=H, ex=ex, n=r["n"], n_atm=len(r["ivs"]),
                        iv_med=float(np.median(r["ivs"])) if r["ivs"] else np.nan,
                        iv_last=r["last"][0] if r["last"] else np.nan,
                        last_ms=r["last"][1] if r["last"] else np.nan,
                        hs_med=float(np.median(r["hs"])) if r["hs"] else np.nan, n_hs=len(r["hs"])))
    if not out: out = [dict(H=H, n=0)]
    return out

start = pd.Timestamp("2021-03-24", tz="UTC"); end = pd.Timestamp.now(tz="UTC").floor("h")
for m0 in pd.date_range(start.to_period("M").to_timestamp().tz_localize("UTC"), end, freq="MS"):
    fn = f"{OUT}/{CUR}_{m0:%Y%m}.parquet"
    m1 = min(m0 + pd.offsets.MonthBegin(1), end + pd.Timedelta(hours=1))
    if os.path.exists(fn) and m1 <= end: continue
    hours = pd.date_range(max(m0, start), m1 - pd.Timedelta(hours=1), freq="h")
    with ThreadPoolExecutor(8) as ex:
        res = [x for lst in ex.map(fetch, hours) for x in lst]
    df = pd.DataFrame(res); df.to_parquet(fn)
    print(CUR, f"{m0:%Y-%m}", len(hours), "hours", len(df), "rows", int(df.get("err", pd.Series(dtype=float)).fillna(0).sum()), "errs", time.strftime("%H:%M:%S"), flush=True)
