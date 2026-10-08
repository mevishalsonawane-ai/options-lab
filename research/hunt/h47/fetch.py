"""h47 data fetch (public data only, no keys). Re-fetches what the lost nn_crypto scratch had, compactly.

python3 -I fetch.py klines    # Binance USDT-M perp 1m BTCUSDT/ETHUSDT 2020-01 .. yesterday (t,o,h,l,c,v only)
python3 -I fetch.py funding   # Binance perp funding (8h)
python3 -I fetch.py dvol      # Deribit DVOL hourly BTC/ETH (2021-03 ..)
python3 -I fetch.py delta     # Delta Exchange India snapshot: perp L2 books + option chain (bid/ask/IV), for spreads/IV ratios
Output: scratchpad/hunt/h47/data/*.parquet (zstd). Zips are parsed in memory, never written.
"""
from __future__ import annotations

import io
import json
import os
import sys
import time
import zipfile
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta, timezone

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h47"
DATA = os.path.join(SCR, "data")
os.makedirs(DATA, exist_ok=True)
B = "https://data.binance.vision/data"
SYMS = ("BTCUSDT", "ETHUSDT")
TODAY = date.today()
UA = {"User-Agent": "options-lab-research/1.0 (public data study)"}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def get(url, raw=False, tries=6):
    for k in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90) as r:
                b = r.read()
            return b if raw else json.loads(b)
        except urllib.error.HTTPError as e:
            if e.code in (400, 404):
                return None
            time.sleep(2 * (k + 1) ** 2)
        except Exception:  # noqa: BLE001
            time.sleep(2 * (k + 1) ** 2)
    raise RuntimeError(f"failed {url}")


def save(df, name):
    p = os.path.join(DATA, name)
    df.to_parquet(p, compression="zstd", compression_level=9, index=False)
    log("saved", name, len(df), f"{os.path.getsize(p) / 1e6:.1f} MB")


def months(a, b):
    y, m = a
    while (y, m) <= b:
        yield y, m
        m += 1
        if m > 12:
            y, m = y + 1, 1


def zip_csv(url):
    b = get(url, raw=True)
    if b is None:
        return None
    with zipfile.ZipFile(io.BytesIO(b)) as z:
        raw = z.read(z.namelist()[0])
    header = 0 if raw[:1].decode(errors="ignore").isalpha() else None
    return pd.read_csv(io.BytesIO(raw), header=header)


def kl(d):
    if d is None or not len(d):
        return None
    d = d.iloc[:, :6]
    d.columns = ["ot", "o", "h", "l", "c", "v"]
    ot = d.ot.astype("int64").values
    ot = np.where(ot > 1e14, ot // 1000, ot)
    return pd.DataFrame({"t": (ot // 60000).astype("int32"), "o": d.o.astype("float32"), "h": d.h.astype("float32"),
                         "l": d.l.astype("float32"), "c": d.c.astype("float32"), "v": d.v.astype("float32")})


def klines():
    last_full = TODAY.replace(day=1) - timedelta(days=1)
    for s in SYMS:
        urls = [f"{B}/futures/um/monthly/klines/{s}/1m/{s}-1m-{y}-{m:02d}.zip"
                for y, m in months((2020, 1), (last_full.year, last_full.month))]
        d = TODAY.replace(day=1)
        while d < TODAY:
            urls.append(f"{B}/futures/um/daily/klines/{s}/1m/{s}-1m-{d}.zip")
            d += timedelta(days=1)
        with ThreadPoolExecutor(3) as ex:
            res = list(ex.map(lambda u: kl(zip_csv(u)), urls))
        miss = [u for u, r in zip(urls, res) if r is None]
        df = pd.concat([r for r in res if r is not None]).drop_duplicates("t").sort_values("t").reset_index(drop=True)
        log(s, "missing files", miss)
        save(df, f"k_{s}.parquet")


def funding():
    out = []
    for s in SYMS:
        for y, m in months((2020, 1), (TODAY.year, TODAY.month)):
            d = zip_csv(f"{B}/futures/um/monthly/fundingRate/{s}/{s}-fundingRate-{y}-{m:02d}.zip")
            if d is None:
                continue
            d = d.iloc[:, [0, 2]]
            d.columns = ["ts", "rate"]
            d["sym"] = s
            out.append(d)
    f = pd.concat(out)
    f["ts"] = f.ts.astype("int64")
    save(f.drop_duplicates(["sym", "ts"]).sort_values(["sym", "ts"]), "funding.parquet")


def dvol():
    out = []
    now = int(time.time() * 1000)
    for c in ("BTC", "ETH"):
        t = int(datetime(2021, 3, 1, tzinfo=timezone.utc).timestamp() * 1000)
        while t < now:
            e = min(t + 999 * 3600_000, now)
            r = get(f"https://www.deribit.com/api/v2/public/get_volatility_index_data?currency={c}"
                    f"&start_timestamp={t}&end_timestamp={e}&resolution=3600")
            d = r["result"]["data"] if r else []
            if d:
                f = pd.DataFrame(d, columns=["ts", "o", "h", "l", "c"])
                f["cur"] = c
                out.append(f)
            t = e + 1
            time.sleep(0.2)
    save(pd.concat(out).drop_duplicates(["cur", "ts"]).sort_values(["cur", "ts"]), "dvol.parquet")


def delta(n=4, gap=60):
    """n snapshots `gap` seconds apart: perp L2 top of book and the BTC/ETH option chain with bid/ask IV."""
    X = "https://api.india.delta.exchange/v2"
    rows, books = [], []
    for k in range(n):
        snap = int(time.time())
        for u in ("BTC", "ETH"):
            for ct in ("perpetual_futures", "call_options,put_options"):
                r = get(f"{X}/tickers?contract_types={ct}&underlying_asset_symbols={u}")["result"]
                for t in r:
                    q = t.get("quotes") or {}
                    rows.append(dict(snap=snap, symbol=t["symbol"], ctype=t["contract_type"], und=u,
                                     strike=t.get("strike_price"), bid=q.get("best_bid"), ask=q.get("best_ask"),
                                     bid_iv=q.get("bid_iv"), ask_iv=q.get("ask_iv"), mark_iv=q.get("mark_iv"),
                                     mark=t.get("mark_price"), spot=t.get("spot_price"), oi=t.get("oi_contracts"),
                                     funding=t.get("funding_rate")))
        for s in ("BTCUSD", "ETHUSD"):
            r = get(f"{X}/l2orderbook/{s}?depth=5")
            if r:
                for side in ("buy", "sell"):
                    for lv in r["result"].get(side, []):
                        books.append(dict(snap=snap, symbol=s, side=side, price=lv["price"], size=lv["size"]))
        log("delta snapshot", k)
        if k < n - 1:
            time.sleep(gap)
    save(pd.DataFrame(rows).astype(str), "delta_chain.parquet")
    save(pd.DataFrame(books).astype(str), "delta_l2.parquet")


if __name__ == "__main__":
    for a in sys.argv[1:]:
        globals()[a]()
