"""Download 3 years of BTC data into crypto/data/.

  btc_1d.csv, btc_1h.csv      BTCUSDT candles from Binance's public market-data mirror
  dvol_1d.csv                 Deribit DVOL (BTC 30-day implied-volatility index), daily
  options_daily.csv           daily option-market summary built from sampled Deribit option trades
                              (ATM IV, short/long-dated ATM IV, 90/110 skew, put/call volume ratio)
  options_trades_sample.csv.gz the sampled trades themselves (3 one-hour windows per day)
  chain_snapshot.csv          today's full BTC option chain (open interest, mark IV, prices)
  context_*.csv               S&P 500, dollar index, gold (Yahoo daily) for cross-asset study

Only public endpoints, no keys. Run from the repo root: python crypto/fetch.py
"""
import concurrent.futures as cf
import datetime as dt
import os
import re
import sys
import time
import urllib.parse

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.net import get_json  # noqa: E402
from marketlab.yahoo import chart  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "data")
DAYS = 3 * 365 + 5
NOW_MS = int(time.time() * 1000)
START_MS = NOW_MS - DAYS * 86_400_000


def binance(interval):
    rows, start = [], START_MS
    hosts = ["https://data-api.binance.vision", "https://api.binance.com", "https://api.binance.us"]
    host = None
    while start < NOW_MS:
        q = urllib.parse.urlencode({"symbol": "BTCUSDT", "interval": interval, "startTime": start, "limit": 1000})
        batch = None
        for hst in ([host] if host else hosts):
            try:
                batch = get_json(f"{hst}/api/v3/klines?{q}")
                host = hst
                break
            except Exception as e:  # noqa: BLE001
                print(f"  {hst}: {type(e).__name__} {e}")
        if not batch:
            break
        rows += batch
        start = batch[-1][0] + 1
        if len(batch) < 1000:
            break
    df = pd.DataFrame(rows, columns=["t", "open", "high", "low", "close", "volume", "close_time", "quote_volume",
                                     "trades", "taker_buy_base", "taker_buy_quote", "_"])
    df["time"] = pd.to_datetime(df["t"], unit="ms", utc=True)
    df = df.drop(columns=["t", "close_time", "_"]).set_index("time")
    df = df.astype(float)
    df = df[~df.index.duplicated()].sort_index()
    df = df.iloc[:-1]  # the last candle is still forming
    print(f"  BTCUSDT {interval}: {len(df)} bars from {host}")
    return df


def dvol():
    rows, end = {}, NOW_MS
    for _ in range(40):
        q = urllib.parse.urlencode({"currency": "BTC", "start_timestamp": START_MS, "end_timestamp": end,
                                    "resolution": "1D"})
        j = get_json(f"https://www.deribit.com/api/v2/public/get_volatility_index_data?{q}")
        res = (j or {}).get("result") or {}
        data = res.get("data") or []
        for ts, o, h, l, c in data:
            rows[ts] = (o, h, l, c)
        cont = res.get("continuation")
        if not data or not cont or cont >= end:
            break
        end = cont
    df = pd.DataFrame([(k, *v) for k, v in rows.items()], columns=["t", "open", "high", "low", "close"])
    df["time"] = pd.to_datetime(df["t"], unit="ms", utc=True)
    df = df.drop(columns="t").set_index("time").sort_index()
    print(f"  DVOL: {len(df)} days")
    return df


NAME = re.compile(r"BTC-(\d{1,2}[A-Z]{3}\d{2})-(\d+)-([CP])")


def parse_instrument(name):
    m = NAME.match(name)
    if not m:
        return None
    exp = dt.datetime.strptime(m.group(1), "%d%b%y").replace(hour=8, tzinfo=dt.timezone.utc)
    return exp, float(m.group(2)), m.group(3)


def trades_window(start_ms, end_ms):
    q = urllib.parse.urlencode({"currency": "BTC", "kind": "option", "start_timestamp": start_ms,
                                "end_timestamp": end_ms, "count": 1000, "sorting": "asc", "include_old": "true"})
    for host in ("https://history.deribit.com", "https://www.deribit.com"):
        try:
            j = get_json(f"{host}/api/v2/public/get_last_trades_by_currency_and_time?{q}")
            return (j or {}).get("result", {}).get("trades", [])
        except Exception as e:  # noqa: BLE001
            last = e
    print(f"  trades {start_ms}: {type(last).__name__}")
    return []


def option_trades():
    """Three one-hour windows a day (02, 10 and 15 UTC: Asia, Europe, US), up to 1000 trades each."""
    day0 = dt.datetime.fromtimestamp(START_MS / 1000, dt.timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    jobs = []
    d = day0
    today = dt.datetime.now(dt.timezone.utc)
    while d < today:
        for hr in (2, 10, 15):
            s = d + dt.timedelta(hours=hr)
            if s + dt.timedelta(hours=1) < today:
                jobs.append((int(s.timestamp() * 1000), int((s + dt.timedelta(hours=1)).timestamp() * 1000)))
        d += dt.timedelta(days=1)
    out = []
    with cf.ThreadPoolExecutor(6) as ex:
        for i, tr in enumerate(ex.map(lambda w: trades_window(*w), jobs)):
            out += tr
            if i % 300 == 0:
                print(f"  option trades: window {i}/{len(jobs)}, {len(out)} trades")
    rows = []
    for t in out:
        p = parse_instrument(t.get("instrument_name", ""))
        if not p or t.get("iv") is None:
            continue
        exp, k, cp = p
        rows.append({"time": pd.to_datetime(t["timestamp"], unit="ms", utc=True), "instrument": t["instrument_name"],
                     "expiry": exp, "strike": k, "type": cp, "iv": float(t["iv"]),
                     "index_price": float(t.get("index_price") or np.nan), "price_btc": float(t["price"]),
                     "amount": float(t["amount"]), "direction": t.get("direction")})
    df = pd.DataFrame(rows).drop_duplicates()
    print(f"  option trades kept: {len(df)}")
    return df


def wmedian(v, w):
    o = np.argsort(v)
    v, w = np.asarray(v)[o], np.asarray(w)[o]
    c = np.cumsum(w)
    return float(v[np.searchsorted(c, c[-1] / 2)]) if len(v) else np.nan


def options_daily(tr):
    tr = tr.copy()
    tr["dte"] = (tr["expiry"] - tr["time"]).dt.total_seconds() / 86400
    tr["mny"] = tr["strike"] / tr["index_price"]
    tr["day"] = tr["time"].dt.floor("D")
    out = []
    for day, g in tr.groupby("day"):
        row = {"time": day, "trades": len(g), "call_amount": g.loc[g.type == "C", "amount"].sum(),
               "put_amount": g.loc[g.type == "P", "amount"].sum()}
        atm = g[(g.mny.sub(1).abs() < 0.03)]
        for name, lo, hi in (("atm_iv_short", 1, 7), ("atm_iv", 7, 45), ("atm_iv_long", 45, 180)):
            s = atm[(atm.dte >= lo) & (atm.dte < hi)]
            row[name] = wmedian(s.iv.values, s.amount.values) if len(s) >= 3 else np.nan
        m = g[(g.dte >= 7) & (g.dte < 60)]
        puts = m[(m.type == "P") & (m.mny > 0.85) & (m.mny < 0.95)]
        calls = m[(m.type == "C") & (m.mny > 1.05) & (m.mny < 1.15)]
        pv = wmedian(puts.iv.values, puts.amount.values) if len(puts) >= 3 else np.nan
        cv = wmedian(calls.iv.values, calls.amount.values) if len(calls) >= 3 else np.nan
        row["put90_iv"], row["call110_iv"], row["skew_90_110"] = pv, cv, pv - cv
        row["pc_ratio"] = row["put_amount"] / row["call_amount"] if row["call_amount"] > 0 else np.nan
        row["buy_share"] = float((g.direction == "buy").mean())
        out.append(row)
    return pd.DataFrame(out).set_index("time")


def chain_snapshot():
    j = get_json("https://www.deribit.com/api/v2/public/get_book_summary_by_currency?currency=BTC&kind=option")
    rows = []
    for x in (j or {}).get("result", []):
        p = parse_instrument(x["instrument_name"])
        if not p:
            continue
        exp, k, cp = p
        rows.append({"instrument": x["instrument_name"], "expiry": exp, "strike": k, "type": cp,
                     "open_interest": x.get("open_interest"), "volume_24h": x.get("volume"),
                     "mark_iv": x.get("mark_iv"), "mark_price_btc": x.get("mark_price"),
                     "bid_price_btc": x.get("bid_price"), "ask_price_btc": x.get("ask_price"),
                     "underlying_price": x.get("underlying_price"), "snapshot_time": pd.Timestamp.now(tz="UTC")})
    df = pd.DataFrame(rows).sort_values(["expiry", "strike", "type"])
    print(f"  chain snapshot: {len(df)} options, {df.expiry.nunique() if len(df) else 0} expiries")
    return df


def main():
    os.makedirs(OUT, exist_ok=True)
    print("BTC candles")
    binance("1d").to_csv(f"{OUT}/btc_1d.csv")
    binance("1h").to_csv(f"{OUT}/btc_1h.csv")
    print("Deribit DVOL")
    dvol().to_csv(f"{OUT}/dvol_1d.csv")
    print("Deribit option chain")
    chain_snapshot().to_csv(f"{OUT}/chain_snapshot.csv", index=False)
    print("Deribit option trades (sampled)")
    tr = option_trades()
    tr.to_csv(f"{OUT}/options_trades_sample.csv.gz", index=False, compression="gzip")
    options_daily(tr).to_csv(f"{OUT}/options_daily.csv")
    print("Context")
    for sym, name in (("^GSPC", "spx"), ("DX-Y.NYB", "dxy"), ("GC=F", "gold")):
        df = chart(sym, DAYS)
        if df is not None:
            df.to_csv(f"{OUT}/context_{name}.csv")
            print(f"  {sym}: {len(df)} days")
    print("done")


if __name__ == "__main__":
    main()
