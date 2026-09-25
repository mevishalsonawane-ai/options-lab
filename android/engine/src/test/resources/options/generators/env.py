"""Import-time sandbox for IraAlgo's services, so generating fixtures writes nothing into IraAlgo.

The fixtures in ../ are produced by calling IraAlgo's own functions. Its
database and logging modules create files on import; every such path is
pointed at a temp directory here, before anything from IraAlgo is imported.

    cd $IRAALGO && PYTHONPATH=<this dir> uv run python <this dir>/gen_greeks.py
    cd $IRAALGO && PYTHONPATH=<this dir> uv run python <this dir>/gen_chain.py
    node <this dir>/transpile.mjs && node <this dir>/gen_strategy.ts     (node >= 22.18)
"""
import logging
import os
import sys
import tempfile

IRAALGO = os.environ.get("IRAALGO", "/home/user/finalproducttradingapp")
S = os.path.join(tempfile.gettempdir(), "options-lab-fixture-sandbox")
os.makedirs(S, exist_ok=True)
os.environ.setdefault("API_KEY_PEPPER", "0" * 64)
for k in ("DATABASE_URL", "SANDBOX_DATABASE_URL", "LATENCY_DATABASE_URL", "LOGS_DATABASE_URL", "HEALTH_DATABASE_URL"):
    os.environ.setdefault(k, f"sqlite:///{S}/{k.lower()}.sqlite")
os.environ.setdefault("LOG_TO_FILE", "False")
os.environ.setdefault("LOG_DIR", f"{S}/log")
os.environ.setdefault("LOG_LEVEL", "CRITICAL")
os.environ.setdefault("MCP_OAUTH_KEYS_DIR", f"{S}/mcp")
sys.path.insert(0, IRAALGO)
os.chdir(S)
logging.disable(logging.CRITICAL)
