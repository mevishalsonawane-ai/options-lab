"""Probe what Dhan serves for MCX crude. Prints status + bar counts only (redacted)."""
import sys, json
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from dhan import Dhan, candles, redact
d = Dhan()
print("token exp", d.exp, "expired", d.expired())
P = [
 ("daily CRUDEOIL OCT 569900 2025-2026", "/charts/historical", dict(securityId="569900", exchangeSegment="MCX_COMM", instrument="FUTCOM", expiryCode=0, oi=True, fromDate="2025-01-01", toDate="2026-10-09")),
 ("daily CRUDEOIL OCT 569900 2005-2010", "/charts/historical", dict(securityId="569900", exchangeSegment="MCX_COMM", instrument="FUTCOM", expiryCode=0, oi=True, fromDate="2005-01-01", toDate="2009-12-31")),
 ("daily CRUDEOIL OCT 569900 2015-2019", "/charts/historical", dict(securityId="569900", exchangeSegment="MCX_COMM", instrument="FUTCOM", expiryCode=0, oi=True, fromDate="2015-01-01", toDate="2019-12-31")),
 ("intraday 569900 2026-10-07", "/charts/intraday", dict(securityId="569900", exchangeSegment="MCX_COMM", instrument="FUTCOM", interval="1", oi=True, fromDate="2026-10-07 09:00:00", toDate="2026-10-08 00:00:00")),
 ("intraday 569900 2026-06-01 (before Oct contract active?)", "/charts/intraday", dict(securityId="569900", exchangeSegment="MCX_COMM", instrument="FUTCOM", interval="1", oi=True, fromDate="2026-06-01 09:00:00", toDate="2026-06-02 00:00:00")),
 ("intraday 569900 2025-10-01 expiryCode 0", "/charts/intraday", dict(securityId="569900", exchangeSegment="MCX_COMM", instrument="FUTCOM", interval="1", oi=True, expiryCode=0, fromDate="2025-10-01 09:00:00", toDate="2025-10-02 00:00:00")),
 ("intraday underlying 294 FUTCOM 2025-10-01", "/charts/intraday", dict(securityId="294", exchangeSegment="MCX_COMM", instrument="FUTCOM", interval="1", oi=True, fromDate="2025-10-01 09:00:00", toDate="2025-10-02 00:00:00")),
 ("rollingoption MCX 294 OPTFUT MONTH ATM CALL Sep-2026", "/charts/rollingoption", dict(securityId=294, exchangeSegment="MCX_COMM", instrument="OPTFUT", expiryFlag="MONTH", expiryCode=1, strike="ATM", drvOptionType="CALL", requiredData=["open","high","low","close","iv","volume","strike","oi","spot"], fromDate="2026-09-01", toDate="2026-09-20", interval="1")),
 ("rollingoption MCX 569900 OPTFUT MONTH ATM CALL Sep-2026", "/charts/rollingoption", dict(securityId=569900, exchangeSegment="MCX_COMM", instrument="OPTFUT", expiryFlag="MONTH", expiryCode=1, strike="ATM", drvOptionType="CALL", requiredData=["open","high","low","close","iv","volume","strike","oi","spot"], fromDate="2026-09-01", toDate="2026-09-20", interval="1")),
 ("intraday option 580373 (15 OCT 7750 CE) 2026-10-07", "/charts/intraday", dict(securityId="580373", exchangeSegment="MCX_COMM", instrument="OPTFUT", interval="1", oi=True, fromDate="2026-10-07 09:00:00", toDate="2026-10-08 00:00:00")),
 ("expirylist 294 MCX_COMM", "/optionchain/expirylist", dict(UnderlyingScrip=294, UnderlyingSeg="MCX_COMM")),
]
for lab, path, body in P:
    st, js, err = d.post(path, body, gap=1.0)
    if err:
        print(lab, "->", err); continue
    if path == "/optionchain/expirylist":
        print(lab, "->", redact(json.dumps(js)[:300])); continue
    if path == "/charts/rollingoption":
        dd = (js or {}).get("data") or {}
        for k, v in dd.items():
            n = len((v or {}).get("timestamp") or []) if v else 0
            print(lab, k, "bars", n, redact(str({kk: (vv[:2] if isinstance(vv, list) else vv) for kk, vv in (v or {}).items()})[:300]))
        if not dd: print(lab, "->", redact(json.dumps(js)[:300]))
        continue
    df = candles(js)
    print(lab, "-> bars", len(df), (df.index[0], df.index[-1], df.close.iloc[0], df.close.iloc[-1]) if len(df) else "", list(df.columns))
