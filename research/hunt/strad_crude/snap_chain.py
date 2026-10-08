"""Live CRUDEOIL option-chain snapshots (MCX) to measure real bid/ask spreads near ATM.
Saves small CSV rows: ts, expiry, strike, side, ltp, bid, ask, bidq, askq, oi, iv, fut."""
import sys, time, csv, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crude")
from dhan_io import client, safe, F, S
cl, _ = client()
pc = F.Pacer(3.5, 2000, name="strad_chain")
out = S / "hunt" / "strad_crude" / "chain_snaps.csv"
U = {"UnderlyingScrip": 294, "UnderlyingSeg": "MCX_COMM"}
exps = cl.post("/optionchain/expirylist", U, pc).get("data", [])
print("expiries", exps[:4], flush=True)
exps = exps[:2]
new = not out.exists()
f = open(out, "a", newline=""); w = csv.writer(f)
if new: w.writerow(["ts","expiry","strike","side","ltp","bid","ask","bidq","askq","oi","iv","fut"])
end = dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=float(sys.argv[1]) if len(sys.argv) > 1 else 70)
n = 0
while dt.datetime.now(dt.timezone.utc) < end:
    for e in exps:
        try:
            js = cl.post("/optionchain", dict(U, Expiry=e), pc)
        except Exception as ex:
            print("err", safe(ex)[:200], flush=True); time.sleep(10); continue
        d = js.get("data", {}); fut = d.get("last_price"); oc = d.get("oc", {})
        def gap(k):
            def m(x):
                x = x or {}; b, a = x.get("top_bid_price") or 0, x.get("top_ask_price") or 0
                return (a + b) / 2 if a and b else 0
            c=m(oc[k].get("ce")); q=m(oc[k].get("pe"))
            return abs(c-q) if c and q else 1e9
        ka = min(oc, key=gap); atm = float(ka)
        fut = atm + ((oc[ka].get("ce") or {}).get("last_price") or 0) - ((oc[ka].get("pe") or {}).get("last_price") or 0)
        ks = sorted(oc, key=lambda k: abs(float(k) - atm))[:9]
        ts = dt.datetime.now(dt.timezone.utc).isoformat()
        for k in ks:
            for side in ("ce", "pe"):
                x = oc[k].get(side) or {}
                w.writerow([ts, e, float(k), side, x.get("last_price"), x.get("top_bid_price"), x.get("top_ask_price"),
                            x.get("top_bid_quantity"), x.get("top_ask_quantity"), x.get("oi"), x.get("implied_volatility"), fut])
        f.flush(); n += 1
    if n % 10 == 0: print("snaps", n, flush=True)
    time.sleep(15)
