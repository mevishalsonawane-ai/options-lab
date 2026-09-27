"""Extra BTC data for the deeper study, into crypto/data_extra/:

  deribit_option_trades_sample10.csv.gz  1-in-10 sample of the Deribit BTC option trades read in EVERY hour
                                       (up to 1000 per hour are read; the surface files use all of them)
  options_surface_daily.csv / _hourly.csv  from those trades: ATM IV by tenor, 25-delta risk reversal and
                                           butterfly, put/call and taker-buy/sell flows, block and large trades
  dvol_1h.csv                          Deribit DVOL, hourly
  deribit_funding_1h.csv               Deribit BTC-PERPETUAL funding, hourly
  binance_funding.csv                  Binance BTCUSDT perpetual funding (8-hourly)
  binance_metrics_1h.csv               Binance futures open interest, top-trader and account long/short ratios,
                                       taker buy/sell volume ratio (5-minute files, aggregated to hours)
  binance_perp_1h.csv                  Binance BTCUSDT perpetual hourly candles (basis vs spot)
  coinbase_1h.csv                      Coinbase BTC-USD hourly candles (Coinbase premium)
  fear_greed.csv                       Crypto Fear & Greed index, daily

Only public endpoints, no keys. Run from the repo root: python crypto/fetch_extra.py
"""
import concurrent.futures as cf
import datetime as dt
import io
import math
import os
import re
import sys
import urllib.parse
import zipfile

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.net import get, get_json  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "data_extra")
DAYS = 3 * 365 + 5
NOW = dt.datetime.now(dt.timezone.utc).replace(minute=0, second=0, microsecond=0)
START = NOW - dt.timedelta(days=DAYS)
ms = lambda t: int(t.timestamp() * 1000)  # noqa: E731
BV = "https://data.binance.vision/data/futures/um"


def months():
    y, m = START.year, START.month
    while (y, m) <= (NOW.year, NOW.month):
        yield y, m
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)


def zip_csv(url, **kw):
    b = get(url)
    if not b:
        return None
    with zipfile.ZipFile(io.BytesIO(b)) as z:
        with z.open(z.namelist()[0]) as f:
            return pd.read_csv(f, **kw)


# ---------------------------------------------------------------- Binance (data.binance.vision)
def binance_funding():
    frames = []
    for y, m in months():
        df = zip_csv(f"{BV}/monthly/fundingRate/BTCUSDT/BTCUSDT-fundingRate-{y}-{m:02d}.zip")
        if df is not None:
            frames.append(df)
    if not frames:
        return None
    df = pd.concat(frames)
    tcol = next(c for c in df.columns if "time" in c)
    rcol = next(c for c in df.columns if "rate" in c)
    out = pd.DataFrame({"funding_rate": df[rcol].astype(float).values},
                       index=pd.to_datetime(df[tcol].astype("int64"), unit="ms", utc=True))
    out.index.name = "time"
    return out.sort_index()[~out.index.duplicated()]


def binance_metrics():
    days = [START.date() + dt.timedelta(days=i) for i in range((NOW.date() - START.date()).days)]
    def one(d):
        try:
            return zip_csv(f"{BV}/daily/metrics/BTCUSDT/BTCUSDT-metrics-{d.isoformat()}.zip")
        except Exception:  # noqa: BLE001
            return None
    frames = []
    with cf.ThreadPoolExecutor(8) as ex:
        for i, df in enumerate(ex.map(one, days)):
            if df is not None:
                frames.append(df)
            if i % 200 == 0:
                print(f"  metrics {i}/{len(days)}")
    if not frames:
        return None
    df = pd.concat(frames)
    df["time"] = pd.to_datetime(df["create_time"], utc=True)
    df = df.drop(columns=[c for c in ("create_time", "symbol") if c in df]).set_index("time").sort_index()
    df = df.apply(pd.to_numeric, errors="coerce")
    return df.resample("1h").last()


def binance_perp_klines():
    cols = ["open_time", "open", "high", "low", "close", "volume", "close_time", "quote_volume", "count",
            "taker_buy_volume", "taker_buy_quote_volume", "ignore"]
    frames = []
    for y, m in months():
        df = zip_csv(f"{BV}/monthly/klines/BTCUSDT/1h/BTCUSDT-1h-{y}-{m:02d}.zip", header=None)
        if df is None:  # current month: daily files
            for d in range(1, 32):
                try:
                    day = dt.date(y, m, d)
                except ValueError:
                    break
                if day >= NOW.date():
                    break
                x = zip_csv(f"{BV}/daily/klines/BTCUSDT/1h/BTCUSDT-1h-{day.isoformat()}.zip", header=None)
                if x is not None:
                    frames.append(x)
            continue
        frames.append(df)
    if not frames:
        return None
    df = pd.concat(frames)
    df = df[pd.to_numeric(df[0], errors="coerce").notna()]  # drop header rows if present
    df.columns = cols
    df["time"] = pd.to_datetime(df["open_time"].astype("int64"), unit="ms", utc=True)
    df = df.set_index("time")[["open", "high", "low", "close", "volume", "taker_buy_volume"]].astype(float)
    return df.sort_index()[~df.index.duplicated()]


# ---------------------------------------------------------------- Deribit
def deribit_funding():
    rows, t = [], START
    while t < NOW:
        t2 = min(t + dt.timedelta(days=30), NOW)
        q = urllib.parse.urlencode({"instrument_name": "BTC-PERPETUAL", "start_timestamp": ms(t), "end_timestamp": ms(t2)})
        j = get_json(f"https://www.deribit.com/api/v2/public/get_funding_rate_history?{q}") or {}
        rows += j.get("result", [])
        t = t2
    df = pd.DataFrame(rows)
    if df.empty:
        return None
    df["time"] = pd.to_datetime(df["timestamp"], unit="ms", utc=True)
    df = df.set_index("time").drop(columns="timestamp").sort_index()
    return df[~df.index.duplicated()]


def dvol_hourly():
    rows, end = {}, ms(NOW)
    for _ in range(200):
        q = urllib.parse.urlencode({"currency": "BTC", "start_timestamp": ms(START), "end_timestamp": end, "resolution": "3600"})
        res = (get_json(f"https://www.deribit.com/api/v2/public/get_volatility_index_data?{q}") or {}).get("result") or {}
        data = res.get("data") or []
        for ts, o, h, l, c in data:
            rows[ts] = (o, h, l, c)
        cont = res.get("continuation")
        if not data or not cont or cont >= end:
            break
        end = cont
    df = pd.DataFrame([(k, *v) for k, v in rows.items()], columns=["t", "open", "high", "low", "close"])
    df["time"] = pd.to_datetime(df["t"], unit="ms", utc=True)
    return df.drop(columns="t").set_index("time").sort_index()


NAME = re.compile(r"BTC-(\d{1,2}[A-Z]{3}\d{2})-(\d+)-([CP])")


def trades_hour(t0):
    q = urllib.parse.urlencode({"currency": "BTC", "kind": "option", "start_timestamp": ms(t0),
                                "end_timestamp": ms(t0 + dt.timedelta(hours=1)), "count": 1000,
                                "sorting": "asc", "include_old": "true"})
    for host in ("https://history.deribit.com", "https://www.deribit.com"):
        try:
            return (get_json(f"{host}/api/v2/public/get_last_trades_by_currency_and_time?{q}") or {}).get("result", {}).get("trades", [])
        except Exception:  # noqa: BLE001
            continue
    return []


def _ncdf(x):
    """Standard normal CDF, vectorised (Abramowitz-Stegun 7.1.26 erf, error < 1.5e-7)."""
    z = np.abs(x) / math.sqrt(2)
    t = 1 / (1 + 0.3275911 * z)
    y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * np.exp(-z * z)
    return 0.5 * (1 + np.sign(x) * y)


def _frame(tr):
    rows = []
    for x in tr:
        m = NAME.match(x.get("instrument_name", ""))
        if not m or x.get("iv") is None:
            continue
        exp = dt.datetime.strptime(m.group(1), "%d%b%y").replace(hour=8, tzinfo=dt.timezone.utc)
        rows.append((x["timestamp"], exp.timestamp(), float(m.group(2)), m.group(3), float(x["iv"]),
                     float(x.get("index_price") or np.nan), float(x["price"]), float(x["amount"]),
                     x.get("direction"), 1 if x.get("block_trade_id") else 0))
    df = pd.DataFrame(rows, columns=["ts", "expiry_ts", "strike", "type", "iv", "index_price", "price_btc", "amount",
                                     "direction", "block"])
    if df.empty:
        return df
    T = ((df["expiry_ts"] - df["ts"] / 1000) / (365 * 86400)).clip(lower=1e-6).values
    S, K, v = df["index_price"].values, df["strike"].values, (df["iv"].values / 100).clip(1e-4)
    d1 = (np.log(S / K) + 0.5 * v * v * T) / (v * np.sqrt(T))
    n = _ncdf(d1)
    df["dte"] = T * 365
    df["delta"] = np.where(df["type"].values == "C", n, n - 1)
    df["time"] = pd.to_datetime(df["ts"], unit="ms", utc=True)
    return df


def _wmed(v, w):
    if len(v) == 0:
        return np.nan
    o = np.argsort(v)
    v, w = np.asarray(v)[o], np.asarray(w)[o]
    c = np.cumsum(w)
    return float(v[np.searchsorted(c, c[-1] / 2)])


def surface_row(g, when):
    g = g.assign(notional=g["amount"] * g["index_price"])
    sign = np.where(g["direction"].values == "buy", 1.0, -1.0)
    tot = g.notional.sum()
    r = {"time": when, "trades": len(g), "notional_usd": tot,
         "block_notional": float(g.loc[g.block == 1, "notional"].sum()), "large_trades": int((g.amount >= 25).sum())}
    ad = g["delta"].abs()
    for name, lo, hi in (("atm_iv_0_2d", 0, 2), ("atm_iv_2_10d", 2, 10), ("atm_iv_10_45d", 10, 45), ("atm_iv_45_120d", 45, 120)):
        s = g[(g.dte >= lo) & (g.dte < hi) & (ad > 0.4) & (ad < 0.6)]
        r[name] = _wmed(s.iv.values, s.amount.values) if len(s) >= 2 else np.nan
        r[name + "_n"] = len(s)
    m = g[(g.dte >= 7) & (g.dte < 60) & (ad > 0.15) & (ad < 0.35)]
    c25, p25 = m[m.type == "C"], m[m.type == "P"]
    r["call25_iv"] = _wmed(c25.iv.values, c25.amount.values) if len(c25) >= 2 else np.nan
    r["put25_iv"] = _wmed(p25.iv.values, p25.amount.values) if len(p25) >= 2 else np.nan
    r["n25"] = len(m)
    for cp in ("C", "P"):
        sel = g.type.values == cp
        r[f"{cp.lower()}_notional"] = float(g.notional.values[sel].sum())
        r[f"{cp.lower()}_net_taker"] = float((g.notional.values[sel] * sign[sel]).sum())
    return r


def option_trades():
    """Every hour: up to 1000 Deribit BTC option trades. Surface stats are built hour by hour; a 1-in-10
    sample of the raw trades is kept (the full set is too large for the repository)."""
    hours = []
    t = START.replace(minute=0)
    while t + dt.timedelta(hours=1) <= NOW:
        hours.append(t)
        t += dt.timedelta(hours=1)
    rows, sample, n = [], [], 0
    with cf.ThreadPoolExecutor(8) as ex:
        for i, (h0, tr) in enumerate(zip(hours, ex.map(trades_hour, hours))):
            g = _frame(tr)
            n += len(g)
            if len(g):
                rows.append(surface_row(g, pd.Timestamp(h0)))
                sample.append(g.iloc[::10])
            if i % 2000 == 0:
                print(f"  option trades: hour {i}/{len(hours)}, {n} trades")
    hourly = pd.DataFrame(rows).set_index("time")
    print(f"  option trades seen: {n}")
    return hourly, pd.concat(sample) if sample else pd.DataFrame()


def daily_from_hourly(hr):
    """Daily surface: IVs weighted by the number of trades behind them each hour; flows summed."""
    hr = hr.copy()
    day = hr.index.floor("D")
    out = {}
    for col in ("trades", "notional_usd", "block_notional", "large_trades", "c_notional", "p_notional", "c_net_taker", "p_net_taker"):
        out[col] = hr[col].groupby(day).sum()
    for col, wcol in (("atm_iv_0_2d", "atm_iv_0_2d_n"), ("atm_iv_2_10d", "atm_iv_2_10d_n"), ("atm_iv_10_45d", "atm_iv_10_45d_n"),
                      ("atm_iv_45_120d", "atm_iv_45_120d_n"), ("call25_iv", "n25"), ("put25_iv", "n25")):
        w = hr[wcol].where(hr[col].notna(), 0)
        out[col] = (hr[col].fillna(0) * w).groupby(day).sum() / w.groupby(day).sum().replace(0, np.nan)
    d = pd.DataFrame(out)
    d["rr25"] = d["call25_iv"] - d["put25_iv"]
    d["fly25"] = (d["call25_iv"] + d["put25_iv"]) / 2 - d["atm_iv_10_45d"]
    d["pc_notional"] = d["p_notional"] / d["c_notional"]
    d["block_share"] = d["block_notional"] / d["notional_usd"]
    d.index.name = "time"
    return d


# ---------------------------------------------------------------- others
def coinbase():
    rows, t = [], START
    while t < NOW:
        t2 = min(t + dt.timedelta(hours=300), NOW)
        q = urllib.parse.urlencode({"granularity": 3600, "start": t.isoformat(), "end": t2.isoformat()})
        try:
            rows += get_json(f"https://api.exchange.coinbase.com/products/BTC-USD/candles?{q}") or []
        except Exception as e:  # noqa: BLE001
            print(f"  coinbase {t}: {type(e).__name__}")
        t = t2
    df = pd.DataFrame(rows, columns=["t", "low", "high", "open", "close", "volume"])
    if df.empty:
        return None
    df["time"] = pd.to_datetime(df["t"], unit="s", utc=True)
    df = df.drop(columns="t").set_index("time").sort_index()
    return df[~df.index.duplicated()]


def fear_greed():
    j = get_json("https://api.alternative.me/fng/?limit=0&format=json") or {}
    df = pd.DataFrame(j.get("data", []))
    if df.empty:
        return None
    df["time"] = pd.to_datetime(df["timestamp"].astype(int), unit="s", utc=True)
    return df.set_index("time")[["value", "value_classification"]].sort_index()


def save(name, fn, **kw):
    try:
        df = fn()
    except Exception as e:  # noqa: BLE001
        print(f"  {name} failed: {type(e).__name__} {e}")
        return None
    if df is None or len(df) == 0:
        print(f"  {name}: no data")
        return None
    df.to_csv(os.path.join(OUT, name), **kw)
    print(f"  {name}: {len(df)} rows")
    return df


def main():
    os.makedirs(OUT, exist_ok=True)
    print("Fear & Greed"); save("fear_greed.csv", fear_greed)
    print("Deribit funding"); save("deribit_funding_1h.csv", deribit_funding)
    print("DVOL hourly"); save("dvol_1h.csv", dvol_hourly)
    print("Binance funding"); save("binance_funding.csv", binance_funding)
    print("Binance perp klines"); save("binance_perp_1h.csv", binance_perp_klines)
    print("Coinbase"); save("coinbase_1h.csv", coinbase)
    print("Binance metrics"); save("binance_metrics_1h.csv", binance_metrics)
    print("Deribit option trades, every hour")
    hourly, sample = option_trades()
    hourly["rr25"] = hourly["call25_iv"] - hourly["put25_iv"]
    hourly["fly25"] = (hourly["call25_iv"] + hourly["put25_iv"]) / 2 - hourly["atm_iv_10_45d"]
    hourly.to_csv(os.path.join(OUT, "options_surface_hourly.csv"))
    daily_from_hourly(hourly).to_csv(os.path.join(OUT, "options_surface_daily.csv"))
    if len(sample):
        sample.drop(columns=["ts"]).to_csv(os.path.join(OUT, "deribit_option_trades_sample10.csv.gz"), index=False, compression="gzip")
    print("done")


if __name__ == "__main__":
    main()
