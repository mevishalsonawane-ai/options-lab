import sys, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from dhan import Dhan
d = Dhan()
RD = ["open","high","low","close","iv","volume","strike","oi","spot"]
for y in ["2023-10-01","2024-01-01","2024-04-01","2024-07-01","2024-10-01","2025-01-01","2025-04-01","2025-07-01","2025-10-01"]:
    t = (dt.date.fromisoformat(y)+dt.timedelta(days=29)).isoformat()
    st, js, err = d.post("/charts/rollingoption", dict(securityId=294, exchangeSegment="MCX_COMM", instrument="OPTFUT", expiryFlag="MONTH", expiryCode=1, strike="ATM", drvOptionType="CALL", requiredData=RD, fromDate=y, toDate=t, interval="1"), gap=0.5)
    v = ((js or {}).get("data") or {}).get("ce") or {}
    ts = v.get("timestamp") or []
    print(y, err or len(ts), dt.datetime.utcfromtimestamp(ts[0]) if ts else "")
