#!/usr/bin/env python3
"""H38 Dhan probe: a handful of tiny requests, prints only HTTP status, Dhan errorCode and bar counts.
Credentials are read at runtime from scratchpad/secrets/dhan.env, kept only in request headers, and every
printed string is redacted. Refuses to send authenticated requests when the JWT exp claim is in the past
(unless --force-one, which sends exactly ONE request to record the server's answer)."""
import base64, datetime as dt, json, re, sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import requests

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
_SEC = []

def redact(s):
    s = str(s)
    for x in _SEC:
        if x: s = s.replace(x, "<redacted>")
    return re.sub(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.?[A-Za-z0-9_-]*", "<redacted-jwt>", s)

def creds():
    v = {}
    for l in open(f"{SCR}/secrets/dhan.env"):
        l = l.strip()
        if l and not l.startswith("#") and "=" in l:
            k, x = l.split("=", 1); v[k.strip()] = x.strip().strip('"').strip("'")
    tok, cid = v.get("DHAN_ACCESS_TOKEN", ""), v.get("DHAN_CLIENT_ID", "")
    _SEC.extend([tok, cid])
    p = tok.split(".")[1]
    exp = dt.datetime.fromtimestamp(int(json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))["exp"]), dt.timezone.utc)
    return {"access-token": tok, "client-id": cid, "Content-Type": "application/json", "Accept": "application/json"}, exp

def post(h, path, body):
    try:
        r = requests.post("https://api.dhan.co/v2" + path, headers=h, json=body, timeout=30)
    except requests.RequestException as e:
        return f"NO RESPONSE {redact(type(e).__name__)}"
    try:
        js = r.json()
    except ValueError:
        return f"HTTP {r.status_code} non-JSON {len(r.content)} B"
    if not r.ok:
        return f"HTTP {r.status_code} " + redact({k: js.get(k) for k in ("errorCode", "errorType", "errorMessage")})[:200]
    ts = js.get("timestamp") or []
    n = len(ts)
    if not n:
        return f"HTTP {r.status_code} bars=0 keys={sorted(js)[:8]}"
    f = lambda t: dt.datetime.fromtimestamp(t, dt.timezone(dt.timedelta(hours=5, minutes=30))).strftime("%Y-%m-%d %H:%M")
    oi = js.get("open_interest") or []
    return (f"HTTP {r.status_code} bars={n} {f(ts[0])}..{f(ts[-1])} close0={js['close'][0]} closeN={js['close'][-1]} "
            f"oi0={oi[0] if oi else None} oiN={oi[-1] if oi else None} vol0={js['volume'][0]}")

PROBES = [
    # (label, path, body)
    ("intraday live NIFTY 2026-10 fut (48704), 2026-10-01", "/charts/intraday",
     {"securityId": "48704", "exchangeSegment": "NSE_FNO", "instrument": "FUTIDX", "interval": "1", "oi": True,
      "fromDate": "2026-10-01 09:15:00", "toDate": "2026-10-01 15:30:00"}),
    ("intraday EXPIRED NIFTY 2026-09 fut (68407), 2026-09-01", "/charts/intraday",
     {"securityId": "68407", "exchangeSegment": "NSE_FNO", "instrument": "FUTIDX", "interval": "1", "oi": True,
      "fromDate": "2026-09-01 09:15:00", "toDate": "2026-09-01 15:30:00"}),
    ("intraday EXPIRED BANKNIFTY 2026-09 fut (68390), 2026-09-01", "/charts/intraday",
     {"securityId": "68390", "exchangeSegment": "NSE_FNO", "instrument": "FUTIDX", "interval": "1", "oi": True,
      "fromDate": "2026-09-01 09:15:00", "toDate": "2026-09-01 15:30:00"}),
    ("intraday EXPIRED token 35006 (NIFTY 2025-01 fut), 2025-01-02", "/charts/intraday",
     {"securityId": "35006", "exchangeSegment": "NSE_FNO", "instrument": "FUTIDX", "interval": "1", "oi": True,
      "fromDate": "2025-01-02 09:15:00", "toDate": "2025-01-02 15:30:00"}),
    ("intraday live 48704 BEFORE its listing (2026-06-01) = continuous?", "/charts/intraday",
     {"securityId": "48704", "exchangeSegment": "NSE_FNO", "instrument": "FUTIDX", "interval": "1", "oi": True,
      "fromDate": "2026-06-01 09:15:00", "toDate": "2026-06-01 15:30:00"}),
    ("intraday live 48704 with expiryCode 1, 2025-01-02", "/charts/intraday",
     {"securityId": "48704", "exchangeSegment": "NSE_FNO", "instrument": "FUTIDX", "interval": "1", "oi": True,
      "expiryCode": 1, "fromDate": "2025-01-02 09:15:00", "toDate": "2025-01-02 15:30:00"}),
    ("intraday underlying id 13 as FUTIDX expiryCode 1, 2025-01-02", "/charts/intraday",
     {"securityId": "13", "exchangeSegment": "NSE_FNO", "instrument": "FUTIDX", "interval": "1", "oi": True,
      "expiryCode": 1, "fromDate": "2025-01-02 09:15:00", "toDate": "2025-01-02 15:30:00"}),
    ("daily live 48704 expiryCode 0, 2020-01-01..2020-03-01 (continuous daily?)", "/charts/historical",
     {"securityId": "48704", "exchangeSegment": "NSE_FNO", "instrument": "FUTIDX", "expiryCode": 0, "oi": True,
      "fromDate": "2020-01-01", "toDate": "2020-03-01"}),
    ("daily EXPIRED 68407 expiryCode 0, 2026-08-01..2026-09-30", "/charts/historical",
     {"securityId": "68407", "exchangeSegment": "NSE_FNO", "instrument": "FUTIDX", "expiryCode": 0, "oi": True,
      "fromDate": "2026-08-01", "toDate": "2026-09-30"}),
]

if __name__ == "__main__":
    h, exp = creds()
    now = dt.datetime.now(dt.timezone.utc)
    print(f"token exp (JWT claim) {exp:%Y-%m-%d %H:%M} UTC; now {now:%Y-%m-%d %H:%M} UTC; expired={exp < now}")
    if exp < now and "--force-one" not in sys.argv:
        print("token expired -> no authenticated request sent"); sys.exit(3)
    import time
    for lab, path, body in (PROBES[:1] if "--force-one" in sys.argv else PROBES):
        print(f"{lab}: POST {path} ->", post(h, path, body), flush=True)
        time.sleep(1.0)
