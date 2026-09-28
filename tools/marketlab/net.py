"""HTTP helpers with retries. Public market data only: no keys, nothing is sent but the query."""
import json
import time
import urllib.error
import urllib.request

UA = "Mozilla/5.0 (X11; Linux x86_64) marketlab/1.0"


def get(url, tries=5, timeout=30, headers=None):
    """GET url and return the body bytes; retries on network errors, 429 and 5xx with backoff.
    Returns None on 404 (a missing file is an answer, not a failure)."""
    h = {"User-Agent": UA, "Accept": "*/*"}
    h.update(headers or {})
    delay = 1.0
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code not in (408, 418, 429, 500, 502, 503, 504) or i == tries - 1:
                raise
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if i == tries - 1:
                raise
        time.sleep(delay)
        delay = min(delay * 2, 30)
    return None


def get_json(url, **kw):
    b = get(url, **kw)
    return None if b is None else json.loads(b)
