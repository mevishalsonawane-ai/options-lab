"""Minimal Dhan v2 client for the data lake. Credentials are read from the environment
(DHAN_ACCESS_TOKEN, DHAN_CLIENT_ID), sent only as HTTP headers, and redacted from anything logged."""
import os, re, time, threading
import requests

API = "https://api.dhan.co/v2"
_TOK = os.environ.get("DHAN_ACCESS_TOKEN", "")
_CID = os.environ.get("DHAN_CLIENT_ID", "")


def redact(s):
    s = str(s)
    for x in (_TOK, _CID):
        if x:
            s = s.replace(x, "<redacted>")
    return re.sub(r"eyJ[A-Za-z0-9_-]{8,}(\.[A-Za-z0-9_-]+){0,2}", "<redacted-jwt>", s)


class Pacer:
    """Global minimum gap between requests across threads. After many requests in a row that ended
    rate-limited (e.g. a daily cap), every thread pauses for BREAK_S before trying again."""
    BREAK_N, BREAK_S = 25, 600

    def __init__(self, gap):
        self.gap, self.t, self.lock = gap, 0.0, threading.Lock()
        self.limited = 0

    def wait(self):
        with self.lock:
            if self.limited >= self.BREAK_N:
                print(f"rate limited {self.limited} times in a row; pausing {self.BREAK_S}s", flush=True)
                time.sleep(self.BREAK_S)
                self.limited = self.BREAK_N // 2
            w = self.t + self.gap - time.time()
            if w > 0:
                time.sleep(w)
            self.t = time.time()


PACE = Pacer(float(os.environ.get("DHAN_GAP", "0.25")))
_S = threading.local()


def post(path, body, retries=6):
    """Returns (status, json or None, error string ''/short)."""
    if not _TOK or not _CID:
        return 0, None, "credentials missing"
    if not hasattr(_S, "s"):
        _S.s = requests.Session()
    h = {"access-token": _TOK, "client-id": _CID, "Content-Type": "application/json", "Accept": "application/json"}
    err = ""
    for a in range(retries):
        PACE.wait()
        try:
            r = _S.s.post(API + path, headers=h, json=body, timeout=90)
        except requests.RequestException as e:
            err = f"NO RESPONSE {type(e).__name__}"
            time.sleep(2 * 2 ** a)
            continue
        try:
            js = r.json()
        except ValueError:
            js = None
        if r.status_code == 429 or r.status_code >= 500 or (
                isinstance(js, dict) and js.get("errorCode") in ("DH-904", "DH-805")):
            err = f"HTTP {r.status_code} {(js or {}).get('errorCode', '') if isinstance(js, dict) else ''}"
            time.sleep(min(60, 3 * 2 ** a))
            continue
        PACE.limited = 0
        if not r.ok:
            e = {k: js.get(k) for k in ("errorCode", "errorType", "errorMessage")} if isinstance(js, dict) else (r.text[:200])
            return r.status_code, js, redact(f"HTTP {r.status_code} {e}")[:300]
        return r.status_code, js, ""
    if "429" in err or "DH-904" in err:
        PACE.limited += 1
    return -1, None, redact(err)
