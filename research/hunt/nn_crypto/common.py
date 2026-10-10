"""Shared paths and a polite HTTP getter for the nn_crypto study (public data only, no keys)."""
from __future__ import annotations

import json
import os
import time
import http.client
import urllib.error
import urllib.request

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/nn_crypto"
DATA = os.path.join(SCR, "data")      # zstd parquet, the only persistent data
RAW = os.path.join(SCR, "raw")        # transient downloads (deleted after parsing)
MODELS = os.path.join(SCR, "models")
LOGS = os.path.join(SCR, "logs")
for _d in (DATA, RAW, MODELS, LOGS):
    os.makedirs(_d, exist_ok=True)

UA = {"User-Agent": "options-lab-research/1.0 (public data study)"}
_last = [0.0]


def get(url, min_gap=0.25, tries=5, raw=False, timeout=60):
    """GET with a minimum gap between calls and backoff on 429/5xx. Returns parsed JSON (or bytes if raw).
    Returns None on 404/400."""
    for k in range(tries):
        dt = time.time() - _last[0]
        if dt < min_gap:
            time.sleep(min_gap - dt)
        _last[0] = time.time()
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
                b = r.read()
            return b if raw else json.loads(b)
        except urllib.error.HTTPError as e:
            if e.code in (400, 404):
                return None
            if e.code in (418, 429) or e.code >= 500:
                time.sleep(2 * (k + 1) ** 2)
                continue
            raise
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException, OSError):
            time.sleep(2 * (k + 1) ** 2)
    raise RuntimeError(f"failed {url}")


def save(df, name):
    p = os.path.join(DATA, name)
    df.to_parquet(p, compression="zstd", compression_level=9, index=False)
    return p


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)
