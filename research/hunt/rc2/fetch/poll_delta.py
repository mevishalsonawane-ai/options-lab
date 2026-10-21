"""Poll Delta India option tickers (all strikes, one call) every 10 min; keep bid/ask/size/mark/iv/oi. Usage: OUTDIR N"""
import json, sys, time, urllib.request, gzip
OUT, N = sys.argv[1], int(sys.argv[2])
U = "https://api.india.delta.exchange/v2/tickers?contract_types=call_options,put_options"
for i in range(N):
    try:
        with urllib.request.urlopen(U, timeout=60) as r: d = json.load(r)["result"]
        t = int(time.time()); rows = []
        for x in d:
            if x.get("underlying_asset_symbol") not in ("BTC", "ETH"): continue
            q = x.get("quotes") or {}; g = x.get("greeks") or {}
            rows.append(dict(sym=x["symbol"], K=x.get("strike_price"), spot=x.get("spot_price"), mark=x.get("mark_price"),
                             bid=q.get("best_bid"), ask=q.get("best_ask"), bs=q.get("bid_size"), as_=q.get("ask_size"),
                             iv=q.get("mark_iv"), delta=g.get("delta"), oi=x.get("oi_contracts"), vol=x.get("volume"),
                             turn=x.get("turnover_usd")))
        with gzip.open(f"{OUT}/snap_{t}.json.gz", "wt") as f: json.dump(dict(t=t, rows=rows), f)
        print(i, t, len(rows), flush=True)
    except Exception as e:
        print("err", e, flush=True)
    time.sleep(600)
