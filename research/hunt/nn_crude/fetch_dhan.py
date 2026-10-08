#!/usr/bin/env python3
"""NN-CRUDE: fetch everything Dhan v2 serves for MCX crude (MCX_COMM).
Usage: python3 fetch_dhan.py [chain|margin|daily|intraday|rolling|all]
Outputs (zstd parquet) under scratchpad/hunt/nn_crude/data/. No raw responses kept.
What was established by probe_dhan.py/probe2/probe3 (2026-10-08):
  * /charts/historical FUTCOM live id + expiryCode 0 -> continuous near-month DAILY from 2012-01-02, with OI
  * /charts/intraday only for live contract ids and only while the contract is listed (no continuous)
  * /charts/rollingoption securityId=294 (CRUDEOIL underlying) / 556 (CRUDEOILM), instrument OPTFUT, MONTH,
    expiryCode 1|2, strikes ATM-3..ATM+3 only, data from ~2025-10-01 only; includes 'spot' = underlying future
  * /optionchain/expirylist + /optionchain work with UnderlyingSeg MCX_COMM
"""
import sys, os, json, datetime as dt, time
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
import pandas as pd, numpy as np
from dhan import Dhan, candles, redact, OUT, IST

D = f"{OUT}/data"
LOG = open(f"{OUT}/logs/fetch_dhan.log", "a")
def log(*a):
    s = redact(" ".join(str(x) for x in a)); print(s, flush=True); LOG.write(s + "\n"); LOG.flush()

FUT = {"CRUDEOIL": ["569900", "573422", "576264"], "CRUDEOILM": ["569901", "573423"]}
UND = {"CRUDEOIL": 294, "CRUDEOILM": 556}
d = Dhan()
log("== run", dt.datetime.now(dt.timezone.utc).isoformat(), "token exp", d.exp.isoformat(), "args", sys.argv[1:])

def save(df, name):
    df.to_parquet(f"{D}/{name}.parquet", compression="zstd")
    log("saved", name, len(df))

def chain():
    now = dt.datetime.now(IST).strftime("%Y%m%d_%H%M")
    rows = []
    for sym, sid in UND.items():
        st, js, err = d.post("/optionchain/expirylist", {"UnderlyingScrip": sid, "UnderlyingSeg": "MCX_COMM"}, gap=12)
        if err: log("expirylist", sym, err); continue
        for e in js.get("data", []):
            st, js2, err = d.post("/optionchain", {"UnderlyingScrip": sid, "UnderlyingSeg": "MCX_COMM", "Expiry": e}, gap=12)
            if err: log("chain", sym, e, err); continue
            dd = js2.get("data") or {}
            ltp = dd.get("last_price")
            for k, v in (dd.get("oc") or {}).items():
                for side in ("ce", "pe"):
                    o = (v or {}).get(side)
                    if not o: continue
                    g = o.get("greeks") or {}
                    rows.append(dict(snap=now, sym=sym, expiry=e, und=ltp, strike=float(k), type=side.upper(),
                                     ltp=o.get("last_price"), bid=o.get("top_bid_price"), ask=o.get("top_ask_price"),
                                     bidq=o.get("top_bid_quantity"), askq=o.get("top_ask_quantity"), oi=o.get("oi"),
                                     prev_oi=o.get("previous_oi"), vol=o.get("volume"), iv=o.get("implied_volatility"),
                                     delta=g.get("delta"), theta=g.get("theta"), vega=g.get("vega")))
            log("chain", sym, e, "und", ltp, "strikes", len(dd.get("oc") or {}))
    if rows:
        save(pd.DataFrame(rows), f"chain_{now}")

def margin():
    """Dhan margin calculator for 1 lot (qty in lots for MCX? try both)."""
    out = []
    tests = [("FUT CRUDEOIL OCT", "569900", "BUY", "MARGIN"), ("FUT CRUDEOIL OCT", "569900", "BUY", "INTRADAY"),
             ("FUT CRUDEOILM OCT", "569901", "BUY", "MARGIN"), ("FUT CRUDEOILM OCT", "569901", "BUY", "INTRADAY")]
    for lab, sid, side, prod in tests:
        for q in (1, 100):
            body = dict(dhanClientId=d.cid, exchangeSegment="MCX_COMM", transactionType=side, quantity=q,
                        productType=prod, securityId=sid, price=0)
            st, js, err = d.post("/margincalculator", body, gap=1)
            log("margin", lab, prod, "qty", q, err or json.dumps(js)[:400])
            if not err: out.append(dict(label=lab, product=prod, qty=q, **{k: v for k, v in js.items() if not isinstance(v, (dict, list))}))
    if out: save(pd.DataFrame(out), "margin_dhan")

def daily():
    for sym, ids in FUT.items():
        for code in (0, 1):
            parts = []
            for y0 in range(2010, 2027, 5):
                f, t = f"{y0}-01-01", min(f"{y0+4}-12-31", dt.date.today().isoformat())
                st, js, err = d.post("/charts/historical", dict(securityId=ids[0], exchangeSegment="MCX_COMM", instrument="FUTCOM",
                                     expiryCode=code, oi=True, fromDate=f, toDate=(dt.date.fromisoformat(t)+dt.timedelta(days=1)).isoformat()), gap=0.5)
                df = candles(js)
                log("daily", sym, "code", code, f, err or len(df))
                if len(df): parts.append(df)
            if parts:
                df = pd.concat(parts); df = df[~df.index.duplicated()].sort_index()
                save(df, f"daily_{sym}_code{code}")

def intraday():
    for sym, ids in FUT.items():
        for sid in ids:
            parts, end, empty = [], dt.date.today() + dt.timedelta(days=1), 0
            while empty < 2 and end > dt.date(2025, 1, 1):
                start = end - dt.timedelta(days=30)
                st, js, err = d.post("/charts/intraday", dict(securityId=sid, exchangeSegment="MCX_COMM", instrument="FUTCOM",
                                     interval="1", oi=True, fromDate=f"{start} 00:00:00", toDate=f"{end} 00:00:00"), gap=0.3)
                df = candles(js)
                log("intraday", sym, sid, start, end, err or len(df))
                empty = empty + 1 if not len(df) else 0
                if len(df): parts.append(df)
                end = start
            if parts:
                df = pd.concat(parts); df = df[~df.index.duplicated()].sort_index().astype("float32")
                save(df, f"min_{sym}_{sid}")

def rolling_unit(sym, code, off, typ):
    """One (underlying, expiryCode, strike offset, CALL/PUT) series, 30-day windows from 2025-08-01; own file."""
    RD = ["open", "high", "low", "close", "iv", "volume", "strike", "oi", "spot"]
    strike = "ATM" if off == 0 else f"ATM{off:+d}"
    fn = f"{D}/rollparts/{sym}_c{code}_{off:+d}_{typ[0]}.parquet"
    if os.path.exists(fn):
        return
    dd = Dhan()  # own pacer per thread
    parts, start = [], dt.date(2025, 8, 1)
    while start <= dt.date.today():
        end = start + dt.timedelta(days=30)
        t0 = time.time()
        st, js, err = dd.post("/charts/rollingoption", dict(securityId=UND[sym], exchangeSegment="MCX_COMM", instrument="OPTFUT",
                             expiryFlag="MONTH", expiryCode=code, strike=strike, drvOptionType=typ, requiredData=RD,
                             fromDate=start.isoformat(), toDate=end.isoformat(), interval="1"), gap=1.0)
        v = ((js or {}).get("data") or {}).get("ce" if typ == "CALL" else "pe") or {}
        n = len(v.get("timestamp") or [])
        log("rolling", sym, code, strike, typ, start, err or n, f"{time.time()-t0:.1f}s")
        if n:
            df = pd.DataFrame({k: v[k] for k in RD if v.get(k)})
            df["ts"] = pd.to_datetime(v["timestamp"], unit="s", utc=True).tz_convert("Asia/Kolkata").tz_localize(None)
            parts.append(df)
        start = end
    if parts:
        df = pd.concat(parts, ignore_index=True)
        for c in ["open", "high", "low", "close", "iv", "strike", "spot"]:
            if c in df: df[c] = df[c].astype("float32")
        df["off"] = off; df["type"] = typ[0]
        df = df.drop_duplicates("ts").sort_values("ts")
        df.to_parquet(fn, compression="zstd")


def rolling():
    os.makedirs(f"{D}/rollparts", exist_ok=True)
    units = [("CRUDEOIL", 1, o, t) for o in (0, 1, -1, 2, -2, 3, -3) for t in ("CALL", "PUT")]
    units += [("CRUDEOILM", 1, 0, t) for t in ("CALL", "PUT")]
    units += [("CRUDEOIL", 2, o, t) for o in (0, 1, -1, 2, -2, 3, -3) for t in ("CALL", "PUT")]
    import concurrent.futures as cf
    with cf.ThreadPoolExecutor(int(os.environ.get("NW", "4"))) as ex:
        for f in [ex.submit(rolling_unit, *u) for u in units]:
            try: f.result()
            except Exception as e: log("ERROR rolling unit", type(e).__name__, e)

if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    for name, fn in [("chain", chain), ("margin", margin), ("daily", daily), ("intraday", intraday), ("rolling", rolling)]:
        if what in (name, "all"):
            try: fn()
            except Exception as e: log("ERROR", name, type(e).__name__, e)
