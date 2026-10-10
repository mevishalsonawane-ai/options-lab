"""R3 probe: how far back does Dhan rollingoption (GOLDM, with spot) go?"""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crude")
from dhan_io import F, safe
c = F.Creds(); cl, pc = F.Client(c.headers(), c.client_id), F.Pacer(1.0, 2000, name="r3")
for und, sid in [("GOLDM", 117)]:
    for d0, d1 in [("2024-10-01","2024-10-20"),("2025-01-06","2025-01-20"),("2025-05-05","2025-05-20"),("2025-06-02","2025-06-20"),("2025-07-01","2025-07-20")]:
        pl = {"securityId": str(sid), "exchangeSegment": "MCX_COMM", "instrument": "OPTFUT", "expiryFlag": "MONTH", "expiryCode": 1,
              "strike": "ATM", "drvOptionType": "CALL", "requiredData": ["open", "close", "spot", "volume"], "fromDate": d0, "toDate": d1, "interval": 1}
        try:
            js = cl.post("/charts/rollingoption", pl, pc); x = (js.get("data") or {}).get("ce")
            df = F.arrays_to_df(x) if x else None
            print(und, d0, 0 if df is None else len(df), "" if df is None else (df.ts.min(), df.ts.max(), df.spot.iloc[[0, -1]].tolist()), flush=True)
        except Exception as e:
            print(und, d0, "ERR", safe(str(e))[:150], flush=True)
