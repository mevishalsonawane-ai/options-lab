"""Minimal Dhan v2 client for the NN-CRUDE study (MCX_COMM).
Token is read at runtime from scratchpad/secrets/dhan.env, kept only in request headers; every string
that is printed/logged goes through redact(). Refuses to call when the JWT exp claim has passed."""
import base64, datetime as dt, json, re, time
import requests

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
OUT = f"{SCR}/hunt/nn_crude"
API = "https://api.dhan.co/v2"
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
_SEC = []


def redact(s):
    s = str(s)
    for x in _SEC:
        if x:
            s = s.replace(x, "<redacted>")
    return re.sub(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.?[A-Za-z0-9_-]*", "<redacted-jwt>", s)


class Dhan:
    def __init__(self):
        v = {}
        for l in open(f"{SCR}/secrets/dhan.env"):
            l = l.strip()
            if l and not l.startswith("#") and "=" in l:
                k, x = l.split("=", 1)
                v[k.strip()] = x.strip().strip('"').strip("'")
        self._tok, self.cid = v.get("DHAN_ACCESS_TOKEN", ""), v.get("DHAN_CLIENT_ID", "")
        _SEC.extend([self._tok, self.cid])
        p = self._tok.split(".")[1]
        self.exp = dt.datetime.fromtimestamp(
            int(json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))["exp"]), dt.timezone.utc)
        self.last = 0.0

    def expired(self):
        return dt.datetime.now(dt.timezone.utc) >= self.exp

    def post(self, path, body, gap=0.3, retries=3):
        """Returns (status, json-or-None, short error string)."""
        if self.expired():
            return 0, None, "TOKEN EXPIRED (not sent)"
        h = {"access-token": self._tok, "client-id": self.cid,
             "Content-Type": "application/json", "Accept": "application/json"}
        for a in range(retries):
            w = self.last + gap - time.time()
            if w > 0:
                time.sleep(w)
            self.last = time.time()
            try:
                r = requests.post(API + path, headers=h, json=body, timeout=60)
            except requests.RequestException as e:
                err = f"NO RESPONSE {type(e).__name__}"
                time.sleep(2 + 3 * a)
                continue
            try:
                js = r.json()
            except ValueError:
                js = None
            if r.status_code == 429 or r.status_code >= 500:
                err = f"HTTP {r.status_code}"
                time.sleep(5 + 10 * a)
                continue
            if not r.ok:
                e = {k: js.get(k) for k in ("errorCode", "errorType", "errorMessage")} if isinstance(js, dict) else ""
                return r.status_code, js, redact(f"HTTP {r.status_code} {e}")[:300]
            return r.status_code, js, ""
        return -1, None, redact(err)


def candles(js, oi=True):
    import pandas as pd
    if not js or not js.get("timestamp"):
        return pd.DataFrame()
    cols = ["open", "high", "low", "close", "volume"] + (["open_interest"] if js.get("open_interest") else [])
    df = pd.DataFrame({c: js[c] for c in cols})
    df.index = pd.to_datetime(js["timestamp"], unit="s", utc=True).tz_convert("Asia/Kolkata").tz_localize(None)
    df.index.name = "ts"
    return df
