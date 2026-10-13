"""Fetch Delta Exchange India public history (no keys).
1) Underlying index 5m (.DEXBTUSD, .DEETHUSD) from 2024-03-01.
2) For every daily expiry D (12:00 UTC), MARK 5m candles of calls/puts over [D-1 06:00, D 12:00] UTC:
   fine strike grid within +-3.5% of the index at D-1 12:00, coarse grid 3.5-8% out of the money.
   Every 7th day also the traded-price candles (volume) for the same strikes.
Cached per (coin, expiry) as parquet; re-runs skip finished days. Usage: python3 -I fetch_delta.py RAWDIR"""
import sys, os, json, time, urllib.request, urllib.error
sys.path.append("/root/.local/lib/python3.11/site-packages")
from concurrent.futures import ThreadPoolExecutor
import pandas as pd, numpy as np
RAW = sys.argv[1]
API = "https://api.india.delta.exchange/v2/history/candles"

def get(sym, res, s, e):
    u = f"{API}?symbol={sym}&resolution={res}&start={s}&end={e}"
    for a in range(8):
        try:
            with urllib.request.urlopen(u, timeout=60) as r:
                return json.load(r)["result"]
        except urllib.error.HTTPError as ex:
            if ex.code == 429: time.sleep(5 + 5 * a); continue
            if ex.code in (400, 404): return []
            time.sleep(2 + 3 * a)
        except Exception:
            time.sleep(2 + 3 * a)
    raise RuntimeError(u)

T0 = int(pd.Timestamp("2024-03-01", tz="UTC").timestamp()); T1 = int(time.time()) // 300 * 300
und = {}
for coin, sym in [("BTC", ".DEXBTUSD"), ("ETH", ".DEETHUSD")]:
    fn = f"{RAW}/../delta_und/{coin}_idx5m.parquet"
    if not os.path.exists(fn):
        rows = []; s = T0
        while s < T1:
            e = min(s + 3999 * 300, T1)
            rows += get(sym, "5m", s, e); s = e + 300; time.sleep(0.15)
        df = pd.DataFrame(rows).drop_duplicates("time").sort_values("time")
        df["t"] = pd.to_datetime(df.time, unit="s", utc=True)
        df[["t", "open", "high", "low", "close"]].to_parquet(fn)
        print(coin, "index", len(df), df.t.min(), df.t.max(), flush=True)
    und[coin] = pd.read_parquet(fn).set_index("t").close

FINE = {"BTC": [200, 500, 1000], "ETH": [20, 25, 50]}
COARSE = {"BTC": 1000, "ETH": 50}

def one(coin, D):
    fn = f"{RAW}/{coin}_{D:%Y%m%d}.parquet"
    if os.path.exists(fn): return 0
    ref = D - pd.Timedelta(hours=24)
    s0 = und[coin].asof(ref)
    if not np.isfinite(s0): return 0
    s = int((D - pd.Timedelta(hours=30)).timestamp()); e = int(D.timestamp())
    tag = f"{D:%d%m%y}"
    # find the fine grid in use
    grid = None
    for g in FINE[coin]:
        k0 = round(s0 / g) * g
        hit = sum(1 for k in (k0 - g, k0, k0 + g) if get(f"MARK:C-{coin}-{int(k)}-{tag}", "1h", s, e))
        if hit >= 2: grid = g; break
    if grid is None:
        pd.DataFrame().to_parquet(fn); return 0
    ks = set()
    for k in np.arange(np.floor(s0 * 0.965 / grid) * grid, s0 * 1.035 + grid, grid): ks.add(("C", int(k))); ks.add(("P", int(k)))
    cg = max(COARSE[coin], grid)
    for k in np.arange(np.ceil(s0 * 1.035 / cg) * cg, s0 * 1.08, cg): ks.add(("C", int(k)))
    for k in np.arange(np.floor(s0 * 0.965 / cg) * cg, s0 * 0.92, -cg): ks.add(("P", int(k)))
    want_trades = D.dayofyear % 7 == 0
    out = []
    for typ, k in sorted(ks):
        sym = f"{typ}-{coin}-{k}-{tag}"
        for kind, pre in (("mark", "MARK:"), ("trade", "")):
            if kind == "trade" and not want_trades: continue
            r = get(pre + sym, "5m", s, e)
            if r:
                d = pd.DataFrame(r); d["kind"] = kind; d["typ"] = typ; d["K"] = k; out.append(d)
            elif kind == "mark":
                break
    if not out:
        pd.DataFrame().to_parquet(fn); return 0
    df = pd.concat(out)
    df["coin"] = coin; df["expiry"] = D; df["grid"] = grid; df["s_ref"] = s0
    df.to_parquet(fn)
    return len(df)

days = pd.date_range(pd.Timestamp("2024-03-02 12:00", tz="UTC"), pd.Timestamp.now(tz="UTC").floor("D") + pd.Timedelta(hours=12), freq="D")
days = [d for d in days if d <= pd.Timestamp.now(tz="UTC")]
jobs = [(c, d) for d in days[::-1] for c in ("BTC", "ETH")]
with ThreadPoolExecutor(6) as ex:
    for i, n in enumerate(ex.map(lambda j: one(*j), jobs)):
        if i % 20 == 0: print(i, len(jobs), jobs[i], n, time.strftime("%H:%M:%S"), flush=True)
print("done", flush=True)
