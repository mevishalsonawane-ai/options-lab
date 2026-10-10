import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from dhan import Dhan, candles
d = Dhan()
RD = ["open","high","low","close","iv","volume","strike","oi","spot"]
def ro(sid, strike, typ, f, t, code=1):
    st, js, err = d.post("/charts/rollingoption", dict(securityId=sid, exchangeSegment="MCX_COMM", instrument="OPTFUT", expiryFlag="MONTH", expiryCode=code, strike=strike, drvOptionType=typ, requiredData=RD, fromDate=f, toDate=t, interval="1"), gap=0.5)
    if err: return err
    v = ((js or {}).get("data") or {}).get("ce" if typ=="CALL" else "pe") or {}
    n = len(v.get("timestamp") or [])
    return f"bars {n}" + (f" strike0 {v['strike'][0]} spot0 {v['spot'][0]} close0 {v['close'][0]}" if n else "")
for s in ["ATM+3","ATM+5","ATM+10","ATM-10","ATM+15","ATM+20"]:
    print("strike", s, ro(294, s, "PUT", "2026-09-01", "2026-09-20"))
for y in ["2018-06-01","2019-06-01","2020-06-01","2021-06-01","2022-06-01","2023-06-01"]:
    import datetime as dt
    t = (dt.date.fromisoformat(y)+dt.timedelta(days=25)).isoformat()
    print("date", y, ro(294, "ATM", "CALL", y, t))
print("code2", ro(294, "ATM", "CALL", "2026-09-01", "2026-09-20", code=2))
print("CRUDEOILM 556", ro(556, "ATM", "CALL", "2026-09-01", "2026-09-20"))
print("40 day window", ro(294, "ATM", "CALL", "2026-07-01", "2026-08-10"))
for f,t in [("2010-01-01","2014-12-31"),("2000-01-01","2004-12-31"),("2005-01-01","2009-12-31")]:
    st, js, err = d.post("/charts/historical", dict(securityId="569900", exchangeSegment="MCX_COMM", instrument="FUTCOM", expiryCode=0, oi=True, fromDate=f, toDate=t), gap=0.5)
    df = candles(js); print("daily", f, err or (len(df), df.index[0] if len(df) else None))
