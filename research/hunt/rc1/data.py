"""RC1 data: Binance public archive (spot + USD-M perps, BTCUSDT / ETHUSDT) and USD/INR, cached as parquet OUTSIDE git.

    python3 research/hunt/rc1/data.py fetch        # downloads (streamed, nothing raw kept) into $IRAALGO_CACHE/rc1

Loading goes through load(split): "design" cuts every series at 12 Oct 2025 23:59 UTC; "holdout" is only allowed inside
the one holdout run (RC1_READ_HOLDOUT=1, see run.py). Bars carry their OPEN time in UTC; a daily bar is known at its close
(next day 00:00 UTC).
"""
from __future__ import annotations

import io
import json
import os
import sys
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import config as K  # noqa: E402

CACHE = os.path.join(os.environ.get("IRAALGO_CACHE", os.path.expanduser("~/.cache/iraalgo")), "rc1")
BASE = "https://data.binance.vision/data"
SYMS = {"BTC": "BTCUSDT", "ETH": "ETHUSDT"}
FIRST = {("spot", "BTC"): "2017-08", ("spot", "ETH"): "2017-08", ("um", "BTC"): "2019-09", ("um", "ETH"): "2019-11"}
FUND_FIRST = "2020-01"
COLS = ["t", "o", "h", "l", "c", "v"]


def _get(url: str) -> bytes | None:
    for _ in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
        except Exception:  # network blip: retry
            pass
    raise RuntimeError(f"failed: {url}")


def _csv_from_zip(blob: bytes) -> pd.DataFrame:
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        raw = z.read(z.namelist()[0])
    first = raw.split(b"\n", 1)[0]
    header = 0 if first[:1].isalpha() else None
    return pd.read_csv(io.BytesIO(raw), header=header)


def _months(first: str, last: date) -> list[str]:
    y, m = map(int, first.split("-"))
    out = []
    while (y, m) <= (last.year, last.month):
        out.append(f"{y:04d}-{m:02d}")
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return out


def _klines_frame(df: pd.DataFrame) -> pd.DataFrame:
    df = df.iloc[:, :6].copy()
    df.columns = COLS
    t = df.t.astype("int64")
    t = np.where(t > 10 ** 14, t // 1000, t)      # spot files from 2025 carry microseconds
    df["t"] = pd.to_datetime(t, unit="ms", utc=True)
    return df.astype({c: float for c in COLS[1:]})


def fetch_klines(market: str, coin: str, interval: str, end: date) -> pd.DataFrame:
    sym = SYMS[coin]
    root = f"{BASE}/spot" if market == "spot" else f"{BASE}/futures/um"
    months = _months(FIRST[(market, coin)], end)
    urls = [f"{root}/monthly/klines/{sym}/{interval}/{sym}-{interval}-{m}.zip" for m in months]
    # the current month is only in daily files
    d = date(end.year, end.month, 1)
    while d <= end:
        urls.append(f"{root}/daily/klines/{sym}/{interval}/{sym}-{interval}-{d.isoformat()}.zip")
        d += timedelta(days=1)
    with ThreadPoolExecutor(8) as ex:
        blobs = list(ex.map(_get, urls))
    parts = [_klines_frame(_csv_from_zip(b)) for b in blobs if b]
    out = pd.concat(parts).drop_duplicates("t").sort_values("t").reset_index(drop=True)
    return out


def fetch_funding(coin: str, end: date) -> pd.DataFrame:
    sym = SYMS[coin]
    urls = [f"{BASE}/futures/um/monthly/fundingRate/{sym}/{sym}-fundingRate-{m}.zip" for m in _months(FUND_FIRST, end)]
    with ThreadPoolExecutor(8) as ex:
        blobs = list(ex.map(_get, urls))
    parts = []
    for b in blobs:
        if not b:
            continue
        df = _csv_from_zip(b)
        df = df.iloc[:, [0, 2]] if df.shape[1] >= 3 else df.iloc[:, :2]
        df.columns = ["t", "rate"]
        parts.append(df)
    out = pd.concat(parts)
    out["t"] = pd.to_datetime(out.t.astype("int64"), unit="ms", utc=True)
    return out.drop_duplicates("t").sort_values("t").reset_index(drop=True)


def fetch_usdinr(end: date) -> pd.DataFrame:
    url = f"https://api.frankfurter.dev/v1/2017-01-01..{end.isoformat()}?base=USD&symbols=INR"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        js = json.load(r)
    s = pd.Series({pd.Timestamp(k, tz="UTC"): v["INR"] for k, v in js["rates"].items()}).sort_index()
    return s.rename("usdinr").rename_axis("t").reset_index()


def fetch(end: date) -> None:
    os.makedirs(CACHE, exist_ok=True)
    for coin in SYMS:
        for market in ("spot", "um"):
            for iv in ("1d", "1h"):
                df = fetch_klines(market, coin, iv, end)
                df.to_parquet(os.path.join(CACHE, f"{market}_{coin}_{iv}.parquet"))
                print(market, coin, iv, len(df), df.t.min(), df.t.max(), flush=True)
        f = fetch_funding(coin, end)
        f.to_parquet(os.path.join(CACHE, f"funding_{coin}.parquet"))
        print("funding", coin, len(f), f.t.min(), f.t.max(), flush=True)
    u = fetch_usdinr(end)
    u.to_parquet(os.path.join(CACHE, "usdinr.parquet"))
    print("usdinr", len(u), u.t.min(), u.t.max(), flush=True)


# ------------------------------------------------------------------------------------------------ loading

def _cut(df: pd.DataFrame, split: str) -> pd.DataFrame:
    if split == "design":
        return df[df.t < K.HOLDOUT_START_TS].reset_index(drop=True)
    if split == "holdout":
        if os.environ.get("RC1_READ_HOLDOUT") != "1":
            raise PermissionError("the RC1 holdout is locked: only the one holdout run may read it")
        return df[df.t <= K.HOLDOUT_END_TS].reset_index(drop=True)
    raise ValueError(split)


def load(split: str = "design") -> dict:
    """{'spot_1d': {coin: df}, 'spot_1h', 'um_1d', 'um_1h', 'funding': {coin: df}, 'usdinr': Series by UTC day}."""
    out: dict = {}
    for key in ("spot_1d", "spot_1h", "um_1d", "um_1h"):
        market, iv = key.split("_")
        out[key] = {c: _cut(pd.read_parquet(os.path.join(CACHE, f"{market}_{c}_{iv}.parquet")), split) for c in SYMS}
    out["funding"] = {c: _cut(pd.read_parquet(os.path.join(CACHE, f"funding_{c}.parquet")), split) for c in SYMS}
    u = pd.read_parquet(os.path.join(CACHE, "usdinr.parquet"))
    out["usdinr"] = _cut(u, split).set_index("t").usdinr
    return out


if __name__ == "__main__":
    if sys.argv[1:2] == ["fetch"]:
        fetch(K.FETCH_END)
