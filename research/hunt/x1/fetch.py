#!/usr/bin/env python3
"""X1: fetch 1-minute candles (with OI) from Dhan v2 /charts/intraday for 6-8 Oct 2026: the indices and every Oct-2026
monthly strike of BANKNIFTY / FINNIFTY / MIDCPNIFTY inside the days' ATM+-12 band.
Credentials: scratchpad/secrets/dhan.env, read at runtime, sent only as headers, never printed (redact()).
    python3 fetch.py index            # indices only
    python3 fetch.py options          # options (needs the index files for the band)
Output: scratchpad/hunt/x1/raw/<name>.json"""
import gzip, csv, json, re, sys, time, datetime as dt
from pathlib import Path
sys.path.append("/root/.local/lib/python3.11/site-packages")
import requests

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
OUT = Path(SCR) / "hunt/x1/raw"
FROM, TO = "2026-10-06 09:15:00", "2026-10-08 15:31:00"
_SEC = []
STEP = {"BANKNIFTY": 100, "FINNIFTY": 50, "MIDCPNIFTY": 25}
IDX = [("13", "NIFTY"), ("25", "BANKNIFTY"), ("27", "FINNIFTY"), ("442", "MIDCPNIFTY")]


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


def get(h, sid, seg, ins, name):
    f = OUT / f"{name}.json"
    if f.exists():
        return
    body = {"securityId": sid, "exchangeSegment": seg, "instrument": ins, "interval": "1", "oi": seg != "IDX_I",
            "fromDate": FROM, "toDate": TO}
    for attempt in range(3):
        try:
            r = requests.post("https://api.dhan.co/v2/charts/intraday", headers=h, json=body, timeout=30)
            js = r.json()
        except Exception as e:
            print(name, "ERR", redact(type(e).__name__)); time.sleep(2); continue
        if r.status_code == 429:
            time.sleep(3); continue
        if not r.ok or not js.get("timestamp"):
            print(name, "HTTP", r.status_code, redact({k: js.get(k) for k in ("errorCode", "errorMessage")})[:160]); return
        keep = [k for k in ("open", "high", "low", "close", "volume", "timestamp", "open_interest") if k in js]
        f.write_text(json.dumps({k: js[k] for k in keep}))
        print(name, "bars", len(js["timestamp"]))
        time.sleep(0.35)
        return


def master():
    p = f"{SCR}/dhan/data/master/api-scrip-master-detailed_2026-10-06.csv.gz"
    rows = {}
    with gzip.open(p, "rt") as fh:
        for r in csv.DictReader(fh):
            if r["INSTRUMENT"] == "OPTIDX" and r["SM_EXPIRY_DATE"] == "2026-10-27" and r["UNDERLYING_SYMBOL"] in STEP:
                rows[(r["UNDERLYING_SYMBOL"], int(float(r["STRIKE_PRICE"])), r["OPTION_TYPE"])] = r["SECURITY_ID"]
    return rows


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    h = headers()
    if sys.argv[1] == "index":
        for sid, n in IDX:
            get(h, sid, "IDX_I", "INDEX", f"IDX_{n}")
        return
    ms = master()
    for u, st in STEP.items():
        d = json.loads((OUT / f"IDX_{u}.json").read_text())
        lo, hi = min(d["low"]), max(d["high"])
        k0 = int((lo // st) - 12) * st; k1 = int((hi // st) + 13) * st
        n = 0
        for k in range(k0, k1 + 1, st):
            for ot in ("CE", "PE"):
                sid = ms.get((u, k, ot))
                if sid:
                    get(h, sid, "NSE_FNO", "OPTIDX", f"{u}_{k}{ot}"); n += 1
        print(u, "band", k0, k1, "contracts", n)


if __name__ == "__main__":
    main()
