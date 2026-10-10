"""M3: live MCX option-chain snapshots (near expiry) to measure real bid/ask spreads, all commodities.
Waits until argv[1] (UTC HH:MM), runs until argv[2] (UTC HH:MM). Rows: ts, sym, expiry, strike, side, ltp, bid, ask,
bidq, askq, oi, iv, volume, fut. Token never printed."""
import sys, time, csv, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crude")
from dhan_io import F, safe, S
c = F.Creds(); cl = F.Client(c.headers(), c.client_id)
pc = F.Pacer(3.6, 3000, name="m3_chain")
UND = {"CRUDEOIL": 294, "NATURALGAS": 401, "GOLDM": 117, "SILVERM": 122, "CRUDEOILM": 556, "NATGASMINI": 596,
       "GOLD": 114, "SILVER": 115, "COPPER": 152}
out = S / "hunt" / "m3" / "chain_snaps.csv"
def at(hm):
    h, m = map(int, hm.split(":")); now = dt.datetime.now(dt.timezone.utc)
    t = now.replace(hour=h, minute=m, second=0, microsecond=0)
    return t if t > now - dt.timedelta(hours=12) else t + dt.timedelta(days=1)
t0, t1 = at(sys.argv[1]), at(sys.argv[2])
if t1 < t0: t1 += dt.timedelta(days=1)
while dt.datetime.now(dt.timezone.utc) < t0: time.sleep(20)
exps = {}
for s, u in UND.items():
    try:
        e = cl.post("/optionchain/expirylist", {"UnderlyingScrip": u, "UnderlyingSeg": "MCX_COMM"}, pc).get("data", [])
        exps[s] = sorted(e)[0]
    except Exception as ex:
        print("exp err", s, safe(str(ex))[:150], flush=True)
print("expiries", exps, flush=True)
new = not out.exists(); f = open(out, "a", newline=""); w = csv.writer(f)
if new: w.writerow(["ts","sym","expiry","strike","side","ltp","bid","ask","bidq","askq","oi","iv","volume","fut"])
n = 0
while dt.datetime.now(dt.timezone.utc) < t1:
    for s, e in exps.items():
        try:
            js = cl.post("/optionchain", {"UnderlyingScrip": UND[s], "UnderlyingSeg": "MCX_COMM", "Expiry": e}, pc)
        except Exception as ex:
            print("err", s, safe(str(ex))[:150], flush=True); time.sleep(5); continue
        d = js.get("data", {}) or {}; oc = d.get("oc", {}) or {}
        if not oc: continue
        def mid(x):
            x = x or {}; b, a = x.get("top_bid_price") or 0, x.get("top_ask_price") or 0
            return (a + b) / 2 if a and b else 0
        def gap(k):
            cc, pp = mid(oc[k].get("ce")), mid(oc[k].get("pe"))
            return abs(cc - pp) if cc and pp else 1e18
        ka = min(oc, key=gap); atm = float(ka)
        fut = atm + mid(oc[ka].get("ce")) - mid(oc[ka].get("pe"))
        ks = sorted(oc, key=lambda k: abs(float(k) - atm))[:9]
        ts = dt.datetime.now(dt.timezone.utc).isoformat()
        for k in ks:
            for side in ("ce", "pe"):
                x = oc[k].get(side) or {}
                w.writerow([ts, s, e, float(k), side, x.get("last_price"), x.get("top_bid_price"), x.get("top_ask_price"),
                            x.get("top_bid_quantity"), x.get("top_ask_quantity"), x.get("oi"), x.get("implied_volatility"),
                            x.get("volume"), round(fut, 2)])
        f.flush()
    n += 1
    if n % 5 == 0: print("rounds", n, dt.datetime.now(dt.timezone.utc).isoformat(), flush=True)
print("done", n, flush=True)
