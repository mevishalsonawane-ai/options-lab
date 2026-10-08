"""Deribit public API (no key). www.deribit.com for live data, history.deribit.com for old trades.

python3 -I fetch_deribit.py dvol        # DVOL (30-day implied vol index) BTC/ETH, hourly OHLC, 2021-03 ..
python3 -I fetch_deribit.py delivery    # daily 08:00 UTC delivery (settlement) prices btc_usd / eth_usd
python3 -I fetch_deribit.py chain       # current option chain snapshot: bid/ask/mark/IV/OI for every BTC/ETH option
python3 -I fetch_deribit.py trades      # SAMPLE of historical option trades: one rotating 1-hour window every 3rd day
"""
from __future__ import annotations

import os
import sys
import time
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.append("/root/.local/lib/python3.11/site-packages")  # pandas deps under python -I
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from common import get, log, save  # noqa: E402

W = "https://www.deribit.com/api/v2/public"
H = "https://history.deribit.com/api/v2/public"
CUR = ("BTC", "ETH")


def ms(y, m, d):
    return int(datetime(y, m, d, tzinfo=timezone.utc).timestamp() * 1000)


def dvol():
    out = []
    now = int(time.time() * 1000)
    for c in CUR:
        t = ms(2021, 3, 1)
        while t < now:
            e = min(t + 999 * 3600_000, now)
            r = get(f"{W}/get_volatility_index_data?currency={c}&start_timestamp={t}&end_timestamp={e}&resolution=3600")
            d = r["result"]["data"] if r else []
            if d:
                f = pd.DataFrame(d, columns=["ts", "o", "h", "l", "c"])
                f["cur"] = c
                out.append(f)
            t = e + 1
    f = pd.concat(out).drop_duplicates(["cur", "ts"]).sort_values(["cur", "ts"])
    save(f, "deribit_dvol_1h.parquet")
    log("dvol", f.groupby("cur").ts.agg(["min", "max", "count"]).to_dict())


def delivery():
    out = []
    for idx in ("btc_usd", "eth_usd"):
        off = 0
        while True:
            r = get(f"{W}/get_delivery_prices?index_name={idx}&offset={off}&count=1000")["result"]
            if not r["data"]:
                break
            f = pd.DataFrame(r["data"])
            f["idx"] = idx
            out.append(f)
            off += len(r["data"])
            if off >= r["records_total"]:
                break
    f = pd.concat(out)
    save(f, "deribit_delivery.parquet")
    log("delivery", len(f), f.groupby("idx").date.agg(["min", "max"]).to_dict())


def chain():
    out = []
    for c in CUR:
        r = get(f"{W}/get_book_summary_by_currency?currency={c}&kind=option")["result"]
        f = pd.DataFrame(r)
        f["snap"] = int(time.time())
        out.append(f)
    f = pd.concat(out)
    keep = ["snap", "instrument_name", "bid_price", "ask_price", "mark_price", "mark_iv", "underlying_price",
            "open_interest", "volume", "volume_usd", "base_currency"]
    save(f[keep], f"deribit_chain_{time.strftime('%Y%m%d_%H%M')}.parquet")
    log("chain", len(f))


def parse_inst(s):
    p = s.str.split("-", expand=True)
    exp = pd.to_datetime(p[1], format="%d%b%y") + pd.Timedelta(hours=8)
    return exp, p[2].astype(float), p[3]


def trades():
    """One 1-hour window every 3rd day; the hour rotates (7 h step) so all hours of day are covered evenly."""
    now = int(time.time() * 1000)
    out = []
    for c in CUR:
        t0 = ms(2020, 1, 1)
        k = 0
        while t0 + 86400_000 < now:
            hh = (k * 7) % 24
            a = t0 + hh * 3600_000
            b = a + 3600_000
            s, n = a, 0
            while True:
                r = get(f"{H}/get_last_trades_by_currency_and_time?currency={c}&kind=option&start_timestamp={s}"
                        f"&end_timestamp={b}&count=1000&sorting=asc", min_gap=0.3)
                if not r:
                    break
                tr = r["result"]["trades"]
                if tr:
                    out.append(pd.DataFrame(tr)[["timestamp", "trade_id", "instrument_name", "price", "mark_price",
                                                 "iv", "index_price", "direction", "amount"]].assign(cur=c))
                    n += len(tr)
                if not r["result"]["has_more"] or not tr:
                    break
                s = tr[-1]["timestamp"]
            k += 1
            t0 += 3 * 86400_000
            if k % 50 == 0:
                log(c, k, datetime.fromtimestamp(a / 1000, timezone.utc), "trades so far", sum(len(x) for x in out))
    f = pd.concat(out).drop_duplicates("trade_id")
    exp, K, typ = parse_inst(f.instrument_name)
    f["expiry"] = exp.values.astype("datetime64[ms]").astype("int64")
    f["strike"] = K.values.astype("float32")
    f["cp"] = typ.values
    for col in ("price", "mark_price", "iv", "amount"):
        f[col] = f[col].astype("float32")
    f = f.drop(columns=["trade_id"])
    save(f, "deribit_opt_trades_sample.parquet")
    log("trades", len(f), f.groupby("cur").timestamp.agg(["min", "max", "count"]).to_dict())


if __name__ == "__main__":
    for a in sys.argv[1:]:
        globals()[a]()
