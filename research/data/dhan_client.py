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
    rate-limited (e.g. HTTP 429 DH-904 for hours), every thread pauses before trying again: BREAK_S, then twice
    as long each time it happens again (up to BREAK_MAX), until a request gets through (ok())."""
    BREAK_N, BREAK_S, BREAK_MAX = 25, 600, 3600

    def __init__(self, gap):
        self.gap, self.t, self.lock = gap, 0.0, threading.Lock()
        self.limited, self.breaks = 0, 0

    def pause_s(self):
        return min(self.BREAK_MAX, self.BREAK_S * 2 ** self.breaks)

    def ok(self):
        self.limited, self.breaks = 0, 0

    def wait(self, sleep=time.sleep):
        with self.lock:
            if self.limited >= self.BREAK_N:
                p = self.pause_s()
                print(f"rate limited {self.limited} times in a row; pausing {p}s", flush=True)
                sleep(p)
                self.breaks += 1
                self.limited = self.BREAK_N // 2
            w = self.t + self.gap - time.time()
            if w > 0:
                time.sleep(w)
            self.t = time.time()


PACE = Pacer(float(os.environ.get("DHAN_GAP", "0.25")))
_S = threading.local()


RETRIES = int(os.environ.get("DHAN_RETRIES", "6"))  # 20 Oct 2026: 2 fails fast on 504s; a later pass retries them


def post(path, body, retries=None):
    """Returns (status, json or None, error string ''/short). retries defaults to DHAN_RETRIES (6)."""
    retries = RETRIES if retries is None else retries
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
        PACE.ok()
        if not r.ok:
            e = {k: js.get(k) for k in ("errorCode", "errorType", "errorMessage")} if isinstance(js, dict) else (r.text[:200])
            return r.status_code, js, redact(f"HTTP {r.status_code} {e}")[:300]
        return r.status_code, js, ""
    if "429" in err or "DH-904" in err:
        PACE.limited += 1
    return -1, None, redact(err)
