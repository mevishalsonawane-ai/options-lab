"""R7: record Dhan v2 live market feed (FULL packets: LTP/LTQ/LTT, volume, total buy/sell qty, OI, 5-level depth)
for MCX CRUDEOIL + NATURALGAS near futures and ATM +-1 options until argv[1] UTC (HH:MM).
Raw frames -> scratchpad/hunt/r7/feed_<date>.bin as [f64 recv_ts][u32 len][payload]. Token never printed."""
import sys, time, json, struct, datetime as dt, os
sys.path.append("/root/.local/lib/python3.11/site-packages")
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crude")
from dhan_io import F, safe, S
import websocket
from urllib.parse import urlparse
c = F.Creds(); cl = F.Client(c.headers(), c.client_id); pc = F.Pacer(3.0, 100, name="r7")
OUT = S / "hunt" / "r7"; OUT.mkdir(parents=True, exist_ok=True)
FUT = {"CRUDEOIL": 569900, "NATURALGAS": 570750}; UND = {"CRUDEOIL": 294, "NATURALGAS": 401}
inst = [("MCX_COMM", str(v)) for v in FUT.values()]; meta = {str(v): {"sym": k, "kind": "FUT"} for k, v in FUT.items()}
for s, u in UND.items():
    try:
        e = cl.post("/optionchain/expirylist", {"UnderlyingScrip": u, "UnderlyingSeg": "MCX_COMM"}, pc).get("data", [])[0]
        oc = (cl.post("/optionchain", {"UnderlyingScrip": u, "UnderlyingSeg": "MCX_COMM", "Expiry": e}, pc).get("data") or {}).get("oc") or {}
        def mid(x):
            x = x or {}; b, a = x.get("top_bid_price") or 0, x.get("top_ask_price") or 0
            return (a + b) / 2 if a and b else 0
        ks = sorted(oc, key=float)
        def gap(k):
            cc, pp = mid(oc[k].get("ce")), mid(oc[k].get("pe")); return abs(cc - pp) if cc and pp else 1e18
        i = ks.index(min(ks, key=gap))
        for k in ks[max(0, i - 1): i + 2]:
            for side in ("ce", "pe"):
                sid = (oc[k].get(side) or {}).get("security_id")
                if sid:
                    inst.append(("MCX_COMM", str(sid))); meta[str(sid)] = {"sym": s, "kind": side.upper(), "strike": float(k), "expiry": e}
    except Exception as ex:
        print("chain err", s, safe(str(ex))[:200], flush=True)
json.dump(meta, open(OUT / "instruments.json", "w"), indent=1)
print("instruments", len(inst), flush=True)
h, m = map(int, sys.argv[1].split(":")); now = dt.datetime.now(dt.timezone.utc)
t_end = now.replace(hour=h, minute=m, second=0, microsecond=0)
p = urlparse(os.environ.get("HTTPS_PROXY", ""))
url = f"wss://api-feed.dhan.co?version=2&token={c._token}&clientId={c.client_id}&authType=2"
fout = open(OUT / f"feed_{now:%Y%m%d}.bin", "ab"); n = 0
while dt.datetime.now(dt.timezone.utc) < t_end:
    try:
        ws = websocket.create_connection(url, timeout=30, http_proxy_host=p.hostname, http_proxy_port=p.port, proxy_type="http",
                                         sslopt={"ca_certs": "/root/.ccr/ca-bundle.crt"})
        ws.send(json.dumps({"RequestCode": 21, "InstrumentCount": len(inst),
                            "InstrumentList": [{"ExchangeSegment": a, "SecurityId": b} for a, b in inst]}))
        print("connected", flush=True)
        while dt.datetime.now(dt.timezone.utc) < t_end:
            msg = ws.recv()
            if isinstance(msg, str): msg = msg.encode()
            fout.write(struct.pack("<dI", time.time(), len(msg)) + msg); n += 1
            if n % 2000 == 0: fout.flush(); print("frames", n, flush=True)
        ws.close()
    except Exception as ex:
        print("ws err", safe(str(ex))[:200], flush=True); time.sleep(5)
fout.close(); print("done", n, flush=True)
