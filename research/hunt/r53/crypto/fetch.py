"""R53 crypto: public data the CC rules need beyond fetch_crypto.py, cached OUTSIDE git ($IRAALGO_CACHE/r53).
Written 15 Oct 2026. Every download is parsed as JSON or as a zipped CSV only (untrusted data, never executed).

    python -m research.hunt.r53.crypto.fetch base      # DVOL, Binance 5m open interest, Delta India index 1m

- Deribit DVOL, hourly (RC2 fetch/fetch_deribit_base.py's call), from 1 Oct 2024: dvol_<COIN>.parquet [t = hour start, c].
- Binance USD-M daily metrics (5-minute open interest, the crypto brain's source; clean.read_metrics), from
  1 Oct 2024: metrics_<COIN>.parquet [ts = create_time, oi].
- Delta India index .DEXBTUSD / .DEETHUSD 1m (RC2 fetch/fetch_delta.py's endpoint): delta_idx_<COIN>_1m.parquet.
- Delta India option MARK 1m candles, one contract at a time, only for the contracts a trade or a pre-drawn twin entry
  needs (OptionStore.get): delta_opt/<COIN>/<YYYYMMDD>/<C|P>_<K>.parquet (an empty file = not listed).
"""
from __future__ import annotations

import io
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta

import numpy as np
import pandas as pd

from .. import common as RC

DELTA = "https://api.india.delta.exchange/v2/history/candles"
DERIBIT = "https://www.deribit.com/api/v2/public/get_volatility_index_data"
METRICS = "https://data.binance.vision/data/futures/um/daily/metrics/{s}/{s}-metrics-{d}.zip"
SYM = {"BTC": "BTCUSDT", "ETH": "ETHUSDT"}
INDEX = {"BTC": ".DEXBTUSD", "ETH": ".DEETHUSD"}
FIRST = date(2024, 10, 1)
LAST = date(2026, 10, 12)
OPT_SPAN = pd.Timedelta(hours=28)        # each contract: 1m marks from expiry - 28 h to expiry (one API call)

_lock = threading.Lock()
_last_call = [0.0]
MIN_GAP = 0.12                            # polite: at most ~8 calls a second across threads


def _pace():
    with _lock:
        w = _last_call[0] + MIN_GAP - time.time()
        if w > 0:
            time.sleep(w)
        _last_call[0] = time.time()


def get_json(url: str, tries: int = 8):
    """GET and parse JSON (nothing else is done with the body). 400/404 -> None; 429 and errors are retried."""
    for a in range(tries):
        _pace()
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as ex:
            if ex.code in (400, 404):
                return None
            time.sleep((5 if ex.code == 429 else 2) * (a + 1))
        except Exception:
            time.sleep(2 * (a + 1))
    raise RuntimeError(f"failed after {tries} tries: {url}")


def get_bytes(url: str, tries: int = 5) -> bytes | None:
    for a in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as ex:
            if ex.code == 404:
                return None
            time.sleep(2 * (a + 1))
        except Exception:
            time.sleep(2 * (a + 1))
    raise RuntimeError(f"failed: {url}")


def delta_candles(symbol: str, res: str, start: int, end: int) -> list[dict]:
    d = get_json(f"{DELTA}?symbol={symbol}&resolution={res}&start={start}&end={end}")
    if not d or not d.get("success"):
        return []
    return d.get("result") or []


def _candle_frame(rows: list[dict]) -> pd.DataFrame:
    if not rows:
        return pd.DataFrame(columns=["t", "o", "h", "l", "c"])
    d = pd.DataFrame(rows).drop_duplicates("time").sort_values("time")
    out = pd.DataFrame({"t": pd.to_datetime(d.time.astype("int64"), unit="s", utc=True)})
    for a, b in (("o", "open"), ("h", "high"), ("l", "low"), ("c", "close")):
        out[a] = pd.to_numeric(d[b], errors="coerce").to_numpy(float)
    return out.reset_index(drop=True)


# ------------------------------------------------------------------------------------------------ base series
def fetch_dvol(coin: str) -> pd.DataFrame:
    s = int(pd.Timestamp(FIRST, tz="UTC").timestamp() * 1000)
    t1 = int(pd.Timestamp(LAST + timedelta(days=1), tz="UTC").timestamp() * 1000)
    rows, step = [], 1000 * 3600_000
    while s < t1:
        e = min(s + step - 1, t1)
        d = get_json(f"{DERIBIT}?currency={coin}&start_timestamp={s}&end_timestamp={e}&resolution=3600")
        data = (d or {}).get("result", {}).get("data") or []
        if data:
            rows.append(pd.DataFrame(data, columns=["t", "o", "h", "l", "c"]))
        s = e + 1
    v = pd.concat(rows).drop_duplicates("t").sort_values("t")
    v["t"] = pd.to_datetime(v["t"], unit="ms", utc=True)
    return v[["t", "c"]].astype({"c": float}).reset_index(drop=True)


def _metrics_day(sym: str, d: date) -> pd.DataFrame | None:
    blob = get_bytes(METRICS.format(s=sym, d=d.isoformat()))
    if not blob:
        return None
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        raw = z.read(z.namelist()[0])
    df = pd.read_csv(io.BytesIO(raw))
    if "create_time" not in df or "sum_open_interest" not in df:
        return None
    return pd.DataFrame({"ts": pd.to_datetime(df.create_time, utc=True),
                         "oi": pd.to_numeric(df.sum_open_interest, errors="coerce")})


def fetch_metrics(coin: str) -> tuple[pd.DataFrame, list]:
    days = [FIRST + timedelta(days=i) for i in range((LAST - FIRST).days + 1)]
    with ThreadPoolExecutor(8) as ex:
        parts = list(ex.map(lambda d: _metrics_day(SYM[coin], d), days))
    missing = [str(d) for d, p in zip(days, parts) if p is None]
    m = pd.concat([p for p in parts if p is not None]).drop_duplicates("ts").sort_values("ts").reset_index(drop=True)
    return m, missing


def fetch_index(coin: str) -> pd.DataFrame:
    s = int(pd.Timestamp(FIRST, tz="UTC").timestamp())
    t1 = int(pd.Timestamp(LAST + timedelta(days=1), tz="UTC").timestamp()) - 60
    spans = []
    while s <= t1:
        e = min(s + 3999 * 60, t1)
        spans.append((s, e))
        s = e + 60
    with ThreadPoolExecutor(4) as ex:
        parts = list(ex.map(lambda se: delta_candles(INDEX[coin], "1m", *se), spans))
    return _candle_frame([r for p in parts for r in p])


# ------------------------------------------------------------------------------------------------ option contracts
def opt_symbol(coin: str, typ: str, K: int, expiry: pd.Timestamp) -> str:
    return f"MARK:{typ}-{coin}-{int(K)}-{expiry:%d%m%y}"


class OptionStore:
    """Lazy, cached Delta India option MARK 1m candles per contract (coin, expiry, type, strike).
    offline=True never calls the network (tests, and the final run once the cache is complete is NOT assumed)."""

    def __init__(self, root=None, offline: bool = False):
        self.root = root or (RC.cache_dir() / "delta_opt")
        self.offline = offline
        self.mem: dict = {}
        self.calls = 0

    def path(self, coin, expiry, typ, K):
        return os.path.join(self.root, coin, f"{expiry:%Y%m%d}", f"{typ}_{int(K)}.parquet")

    def get(self, coin: str, expiry: pd.Timestamp, typ: str, K: int) -> pd.DataFrame | None:
        """1m MARK candles [t, o, h, l, c] of the contract over [expiry - 28 h, expiry), or None if not listed."""
        key = (coin, expiry, typ, int(K))
        if key in self.mem:
            return self.mem[key]
        p = self.path(*key)
        if os.path.exists(p):
            d = pd.read_parquet(p)
        elif self.offline:
            return None
        else:
            s = int((expiry - OPT_SPAN).timestamp())
            e = int(expiry.timestamp()) - 60
            d = _candle_frame(delta_candles(opt_symbol(coin, typ, K, expiry), "1m", s, e))
            self.calls += 1
            os.makedirs(os.path.dirname(p), exist_ok=True)
            tmp = p + f".{threading.get_ident()}.tmp"
            d.to_parquet(tmp)
            os.replace(tmp, p)
        d = None if d.empty else d
        self.mem[key] = d
        return d

    def prefetch(self, keys, threads: int = 6) -> int:
        """Download many contracts in parallel (keys: (coin, expiry, typ, K)); returns how many were fetched."""
        todo = sorted({(c, e, t, int(k)) for c, e, t, k in keys if not os.path.exists(self.path(c, e, t, k))})
        if self.offline or not todo:
            return 0
        with ThreadPoolExecutor(threads) as ex:
            list(ex.map(lambda k: self.get(*k), todo))
        return len(todo)


def main(argv=None) -> int:
    a = argv or sys.argv[1:]
    out = RC.cache_dir()
    os.makedirs(out, exist_ok=True)
    if a and a[0] == "base":
        log = {}
        for coin in ("BTC", "ETH"):
            v = fetch_dvol(coin)
            v.to_parquet(out / f"dvol_{coin}.parquet")
            print("dvol", coin, len(v), v.t.min(), v.t.max(), flush=True)
            m, miss = fetch_metrics(coin)
            m.to_parquet(out / f"metrics_{coin}.parquet")
            print("metrics", coin, len(m), m.ts.min(), m.ts.max(), "missing days", len(miss), miss[:10], flush=True)
            ix = fetch_index(coin)
            ix.to_parquet(out / f"delta_idx_{coin}_1m.parquet")
            print("index", coin, len(ix), ix.t.min(), ix.t.max(), flush=True)
            log[coin] = dict(dvol=len(v), metrics=len(m), metrics_missing=miss, index=len(ix))
        (out / "fetch_base_log.json").write_text(json.dumps(log, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
