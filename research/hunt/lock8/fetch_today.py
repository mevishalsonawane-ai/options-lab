#!/usr/bin/env python3
"""Fetch today's (2026-10-08) 1-minute candles from Dhan v2 /charts/intraday for the contracts Boss traded.
Credentials: scratchpad/secrets/dhan.env, read at runtime, sent only as headers, never printed (redact()).
Usage: python3 fetch_today.py [secId:segment:instrument:name ...]   (no args = the default list)"""
import base64, datetime as dt, json, re, sys, time
from pathlib import Path
sys.path.append("/root/.local/lib/python3.11/site-packages")
import requests

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
OUT = Path(__file__).resolve().parent / "data"
DAY = "2026-10-08"
_SEC = []

def redact(s):
    s = str(s)
    for x in _SEC:
        if x: s = s.replace(x, "<redacted>")
    return re.sub(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.?[A-Za-z0-9_-]*", "<redacted-jwt>", s)

def headers():
    v = {}
    for l in open(f"{SCR}/secrets/dhan.env"):
        l = l.strip()
        if l and not l.startswith("#") and "=" in l:
            k, x = l.split("=", 1); v[k.strip()] = x.strip().strip('"').strip("'")
    _SEC.extend([v["DHAN_ACCESS_TOKEN"], v["DHAN_CLIENT_ID"]])
    return {"access-token": v["DHAN_ACCESS_TOKEN"], "client-id": v["DHAN_CLIENT_ID"],
            "Content-Type": "application/json", "Accept": "application/json"}

DEFAULT = [
    ("49440", "NSE_FNO", "OPTIDX", "BANKNIFTY_54900PE"),
    ("49435", "NSE_FNO", "OPTIDX", "BANKNIFTY_54900CE"),
    ("49434", "NSE_FNO", "OPTIDX", "BANKNIFTY_54800PE"),
    ("49432", "NSE_FNO", "OPTIDX", "BANKNIFTY_54700PE"),
    ("50304", "NSE_FNO", "OPTIDX", "FINNIFTY_24800PE"),
    ("60386", "NSE_FNO", "OPTIDX", "FINNIFTY_24750PE"),
    ("60384", "NSE_FNO", "OPTIDX", "FINNIFTY_24650PE"),
    ("50299", "NSE_FNO", "OPTIDX", "FINNIFTY_24600PE"),
    ("44615", "NSE_FNO", "OPTIDX", "NIFTY_13OCT_22550PE"),
    ("13", "IDX_I", "INDEX", "IDX_NIFTY"), ("25", "IDX_I", "INDEX", "IDX_BANKNIFTY"),
    ("27", "IDX_I", "INDEX", "IDX_FINNIFTY"), ("442", "IDX_I", "INDEX", "IDX_MIDCPNIFTY"),
]

def main():
    h = headers()
    items = [tuple(a.split(":")) for a in sys.argv[1:]] or DEFAULT
    for sid, seg, ins, name in items:
        body = {"securityId": sid, "exchangeSegment": seg, "instrument": ins, "interval": "1", "oi": seg != "IDX_I",
                "fromDate": f"{DAY} 09:15:00", "toDate": f"{DAY} 15:31:00"}
        try:
            r = requests.post("https://api.dhan.co/v2/charts/intraday", headers=h, json=body, timeout=30)
            js = r.json()
        except Exception as e:
            print(name, "ERR", redact(type(e).__name__)); continue
        if not r.ok or not js.get("timestamp"):
            print(name, "HTTP", r.status_code, redact({k: js.get(k) for k in ("errorCode", "errorMessage")})[:160]); continue
        (OUT / f"{name}.json").write_text(json.dumps({k: js[k] for k in ("open", "high", "low", "close", "volume", "timestamp")}))
        ist = lambda t: dt.datetime.fromtimestamp(t, dt.timezone(dt.timedelta(hours=5, minutes=30))).strftime("%H:%M")
        print(name, "bars", len(js["timestamp"]), ist(js["timestamp"][0]), "..", ist(js["timestamp"][-1]), "lastclose", js["close"][-1])
        time.sleep(0.4)

if __name__ == "__main__":
    main()
