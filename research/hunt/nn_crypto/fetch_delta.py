"""Delta Exchange India public API (api.india.delta.exchange, no key).

python3 -I fetch_delta.py specs      # contract specs + fee rates for live perps/futures/options (BTC, ETH)
python3 -I fetch_delta.py chain      # option chain + perp tickers with best bid/ask, IV, greeks; L2 books for perps/ATM
python3 -I fetch_delta.py candles    # 1m candles BTCUSD/ETHUSD perps (from listing, early 2024), hourly funding + OI
"""
from __future__ import annotations

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.append("/root/.local/lib/python3.11/site-packages")  # pandas deps under python -I
import pandas as pd  # noqa: E402

from common import DATA, get, log, save  # noqa: E402

X = "https://api.india.delta.exchange/v2"
DROP = ("quoting_asset", "underlying_asset", "spot_index", "ui_config", "settling_asset")


def specs():
    out = []
    for ct in ("perpetual_futures", "futures", "call_options,put_options"):
        r = get(f"{X}/products?contract_types={ct}&states=live&page_size=3000")["result"]
        for p in r:
            u = p.get("underlying_asset", {}).get("symbol")
            if u in ("BTC", "ETH"):
                q = {k: (json.dumps(v) if isinstance(v, (dict, list)) else v) for k, v in p.items() if k not in DROP}
                q["underlying"] = u
                out.append(q)
    f = pd.DataFrame(out)
    f["snap"] = int(time.time())
    save(f.astype(str), f"delta_specs_{time.strftime('%Y%m%d_%H%M')}.parquet")
    log("specs", len(f), f.contract_type.value_counts().to_dict())


def chain():
    rows, books = [], []
    for ct in ("perpetual_futures", "call_options,put_options"):
        for u in ("BTC", "ETH"):
            r = get(f"{X}/tickers?contract_types={ct}&underlying_asset_symbols={u}")["result"]
            for t in r:
                q = t.get("quotes") or {}
                g = t.get("greeks") or {}
                rows.append(dict(symbol=t["symbol"], ctype=t["contract_type"], und=u, strike=t.get("strike_price"),
                                 bid=q.get("best_bid"), ask=q.get("best_ask"), bid_size=q.get("bid_size"),
                                 ask_size=q.get("ask_size"), mark=t.get("mark_price"), mark_iv=q.get("mark_iv"),
                                 bid_iv=q.get("bid_iv"), ask_iv=q.get("ask_iv"), spot=t.get("spot_price"),
                                 delta=g.get("delta"), theta=g.get("theta"), vega=g.get("vega"), oi=t.get("oi_contracts"),
                                 volume=t.get("volume"), turnover_usd=t.get("turnover_usd"),
                                 funding=t.get("funding_rate"), ts=t.get("timestamp")))
    f = pd.DataFrame(rows)
    stamp = time.strftime('%Y%m%d_%H%M')
    save(f.astype(str), f"delta_chain_{stamp}.parquet")
    for s in ("BTCUSD", "ETHUSD"):
        r = get(f"{X}/l2orderbook/{s}?depth=20")
        if r:
            b = r["result"]
            for side in ("buy", "sell"):
                for lv in b.get(side, []):
                    books.append(dict(symbol=s, side=side, price=lv["price"], size=lv["size"]))
    save(pd.DataFrame(books).astype(str), f"delta_l2_{stamp}.parquet")
    log("chain", len(f), "book rows", len(books))


def candles():
    now = int(time.time())
    for sym, res, step, fname in (("BTCUSD", "1m", 60, "delta_perp_BTCUSD_1m"), ("ETHUSD", "1m", 60, "delta_perp_ETHUSD_1m"),
                                  ("FUNDING:BTCUSD", "1h", 3600, "delta_funding_BTCUSD"),
                                  ("FUNDING:ETHUSD", "1h", 3600, "delta_funding_ETHUSD"),
                                  ("OI:BTCUSD", "1h", 3600, "delta_oi_BTCUSD"), ("OI:ETHUSD", "1h", 3600, "delta_oi_ETHUSD")):
        t, out = 1701388800, []        # 2023-12-01 (BTCUSD listed 2023-12-18)
        while t < now:
            e = min(t + 3990 * step, now)
            r = get(f"{X}/history/candles?resolution={res}&symbol={sym}&start={t}&end={e}", min_gap=0.3)
            if r and r.get("result"):
                out.append(pd.DataFrame(r["result"]))
            t = e
        f = pd.concat(out).drop_duplicates("time").sort_values("time").reset_index(drop=True)
        for c in ("open", "high", "low", "close"):
            f[c] = f[c].astype("float64")
        f["volume"] = pd.to_numeric(f.volume, errors="coerce").astype("float32")
        save(f, fname + ".parquet")
        log(sym, len(f), pd.to_datetime(f.time.min(), unit="s"), pd.to_datetime(f.time.max(), unit="s"))


if __name__ == "__main__":
    for a in sys.argv[1:]:
        globals()[a]()
