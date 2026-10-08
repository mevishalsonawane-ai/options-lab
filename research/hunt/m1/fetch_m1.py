#!/usr/bin/env python3
"""M1: fetch MCX facts from Dhan v2 for the MCX guide.
  daily  : continuous near-month daily (expiryCode 0) for every MCX futures underlying, 2012-> now
  chain  : option chain snapshot (all listed expiries) for every MCX options underlying
Token via nn_crude/dhan.py (redacted). Output: scratchpad/hunt/m1/data/*.parquet"""
import sys, csv, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
import pandas as pd
from dhan import Dhan, candles, redact, IST
SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/m1"
D = f"{SCR}/data"
d = Dhan()
rows = list(csv.DictReader(open(f"{SCR}/raw/dhan_mcx_det.csv")))
def log(*a): print(redact(" ".join(map(str, a))), flush=True)

def daily():
    fut = {}
    for r in rows:
        if r["INSTRUMENT"] in ("FUTCOM", "FUTIDX"):
            fut.setdefault(r["UNDERLYING_SYMBOL"], []).append((r["SM_EXPIRY_DATE"], r["SECURITY_ID"], r["INSTRUMENT"]))
    for sym, lst in sorted(fut.items()):
        lst.sort(); sid, ins = lst[0][1], lst[0][2]
        parts = []
        for y0 in range(2012, 2027, 5):
            f, t = f"{y0}-01-01", min(f"{y0+4}-12-31", dt.date.today().isoformat())
            st, js, err = d.post("/charts/historical", dict(securityId=sid, exchangeSegment="MCX_COMM", instrument=ins,
                         expiryCode=0, oi=True, fromDate=f, toDate=(dt.date.fromisoformat(t) + dt.timedelta(days=1)).isoformat()), gap=0.4)
            df = candles(js)
            log("daily", sym, f, err or len(df))
            if len(df): parts.append(df)
        if parts:
            df = pd.concat(parts); df = df[~df.index.duplicated()].sort_index()
            df.to_parquet(f"{D}/daily_{sym}.parquet")

def chain():
    und = sorted({(r["UNDERLYING_SYMBOL"], r["UNDERLYING_SECURITY_ID"]) for r in rows if r["INSTRUMENT"] in ("OPTFUT", "OPTIDX")})
    out = []
    now = dt.datetime.now(IST).strftime("%Y%m%d_%H%M")
    for sym, sid in und:
        st, js, err = d.post("/optionchain/expirylist", {"UnderlyingScrip": int(sid), "UnderlyingSeg": "MCX_COMM"}, gap=3.5)
        if err: log("expirylist", sym, err); continue
        for e in (js.get("data") or [])[:2]:
            st, js2, err = d.post("/optionchain", {"UnderlyingScrip": int(sid), "UnderlyingSeg": "MCX_COMM", "Expiry": e}, gap=3.5)
            if err: log("chain", sym, e, err); continue
            dd = js2.get("data") or {}
            for k, v in (dd.get("oc") or {}).items():
                for side in ("ce", "pe"):
                    o = (v or {}).get(side)
                    if not o: continue
                    out.append(dict(snap=now, sym=sym, expiry=e, und=dd.get("last_price"), strike=float(k), type=side.upper(),
                        ltp=o.get("last_price"), bid=o.get("top_bid_price"), ask=o.get("top_ask_price"), oi=o.get("oi"),
                        prev_oi=o.get("previous_oi"), vol=o.get("volume"), iv=o.get("implied_volatility")))
            log("chain", sym, e, "strikes", len(dd.get("oc") or {}))
    if out: pd.DataFrame(out).to_parquet(f"{D}/chain_{now}.parquet")

if __name__ == "__main__":
    log("token exp", d.exp.isoformat())
    for a in sys.argv[1:]: globals()[a]()
