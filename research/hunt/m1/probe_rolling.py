"""Which MCX underlyings have expired-option minute history on Dhan /charts/rollingoption, and from when."""
import sys, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from dhan import Dhan, redact
d = Dhan()
UND = dict(NATURALGAS=401, NATGASMINI=596, GOLDM=117, GOLD=114, SILVERM=122, SILVER=115, COPPER=152, ZINC=378, CRUDEOIL=294)
for sym, sid in UND.items():
    for start in ("2024-09-01", "2025-06-01", "2025-09-01", "2026-09-01"):
        end = (dt.date.fromisoformat(start) + dt.timedelta(days=30)).isoformat()
        st, js, err = d.post("/charts/rollingoption", dict(securityId=sid, exchangeSegment="MCX_COMM", instrument="OPTFUT",
            expiryFlag="MONTH", expiryCode=1, strike="ATM", drvOptionType="CALL", requiredData=["close", "volume", "oi", "spot", "strike"],
            fromDate=start, toDate=end, interval="1"), gap=1.0)
        v = ((js or {}).get("data") or {}).get("ce") or {}
        ts = v.get("timestamp") or []
        first = dt.datetime.utcfromtimestamp(ts[0]).isoformat() if ts else "-"
        print(sym, start, err or len(ts), first, flush=True)
