"""Thin wrapper over scratchpad/dhan/fetch.py (Creds/Client/Pacer with token redaction)."""
import sys, json
sys.path.append("/root/.local/lib/python3.11/site-packages")
from pathlib import Path
S = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad")
sys.path.insert(0, str(S / "dhan"))
import fetch as F  # noqa

CACHE = S / "hunt" / "strad_crude" / "cache"
CACHE.mkdir(parents=True, exist_ok=True)

def client():
    c = F.Creds()
    return F.Client(c.headers(), c.client_id), F.Pacer(0.3, 20000, name="strad_crude")

def safe(x):
    return F.redact(x)
