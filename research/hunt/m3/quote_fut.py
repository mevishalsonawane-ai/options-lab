"""M3: futures top-of-book snapshots (Dhan /marketfeed/quote) every ~60 s between argv[1] and argv[2] UTC (HH:MM).
-> scratchpad/hunt/m3/fut_quotes.csv. Token never printed."""
import sys, time, csv, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crude")
from dhan_io import F, safe, S
c = F.Creds(); cl = F.Client(c.headers(), c.client_id); pc = F.Pacer(2.0, 3000, name="m3_quote")
IDS = {569900: "CRUDEOIL", 569901: "CRUDEOILM", 570750: "NATURALGAS", 570751: "NATGASMINI", 571445: "GOLDM",
       483080: "SILVERM", 562058: "SILVERMIC", 571307: "GOLDTEN", 574829: "COPPER"}
def at(hm):
    h, m = map(int, hm.split(":")); now = dt.datetime.now(dt.timezone.utc)
    t = now.replace(hour=h, minute=m, second=0, microsecond=0)
    return t if t > now - dt.timedelta(hours=12) else t + dt.timedelta(days=1)
t0, t1 = at(sys.argv[1]), at(sys.argv[2])
if t1 < t0: t1 += dt.timedelta(days=1)
while dt.datetime.now(dt.timezone.utc) < t0: time.sleep(20)
out = S / "hunt" / "m3" / "fut_quotes.csv"; new = not out.exists()
f = open(out, "a", newline=""); w = csv.writer(f)
if new: w.writerow(["ts", "sym", "sid", "ltp", "bid", "ask", "bidq", "askq", "volume"])
n = 0
while dt.datetime.now(dt.timezone.utc) < t1:
    try:
        js = cl.post("/marketfeed/quote", {"MCX_COMM": list(IDS)}, pc)
        ts = dt.datetime.now(dt.timezone.utc).isoformat()
        for sid, q in ((js.get("data") or {}).get("MCX_COMM") or {}).items():
            dep = q.get("depth") or {}
            b, a = (dep.get("buy") or [{}])[0], (dep.get("sell") or [{}])[0]
            w.writerow([ts, IDS.get(int(sid)), sid, q.get("last_price"), b.get("price"), a.get("price"), b.get("quantity"),
                        a.get("quantity"), q.get("volume")])
        f.flush(); n += 1
    except Exception as e:
        print("err", safe(str(e))[:150], flush=True)
    time.sleep(60)
print("done", n, flush=True)
