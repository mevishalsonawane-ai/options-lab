"""Delta India calibration from the fresh snapshots: perp half-spreads and ATM ask/bid IV to DVOL ratios
for the nearest daily expiry at least 6 h away. Writes scratchpad/hunt/h47/delta_cal.json."""
from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from lib import DATA, SCR  # noqa: E402

l2 = pd.read_parquet(os.path.join(DATA, "delta_l2.parquet"))
l2[["price", "size"]] = l2[["price", "size"]].astype(float)
hs = {}
for s, a in (("BTCUSD", "BTC"), ("ETHUSD", "ETH")):
    v = []
    for snap, g in l2[l2.symbol == s].groupby("snap"):
        bb, ba = g[g.side == "buy"].price.max(), g[g.side == "sell"].price.min()
        v.append((ba - bb) / (ba + bb))
    hs[a] = float(np.mean(v))
ch = pd.read_parquet(os.path.join(DATA, "delta_chain.parquet"))
for c in ("strike", "bid", "ask", "bid_iv", "ask_iv", "spot", "snap"):
    ch[c] = pd.to_numeric(ch[c], errors="coerce")
o = ch[ch.ctype.str.contains("options") & (ch.bid > 0) & (ch.ask > 0)].copy()
o["exp"] = pd.to_datetime(o.symbol.str.split("-").str[-1], format="%d%m%y") + pd.Timedelta(hours=12)
o["T_h"] = (o.exp - pd.to_datetime(o.snap, unit="s")).dt.total_seconds() / 3600
dv = pd.read_parquet(os.path.join(DATA, "dvol.parquet"))
ratio, info = {}, {}
for a in ("BTC", "ETH"):
    q = o[(o.und == a) & (o.T_h >= 6)]
    e = q.T_h.min()
    q = q[q.exp == q.exp[q.T_h == e].iloc[0]]
    q = q[(q.bid_iv > 0.05) & (q.ask_iv > 0.05)]
    q = q.assign(dist=(q.strike / q.spot - 1).abs())
    q = q[q.dist <= q.groupby("snap").dist.transform("min")]
    d = dv[dv.cur == a].c.iloc[-1] / 100
    ratio[a] = (float(q.ask_iv.mean() / d), float(q.bid_iv.mean() / d))
    mid = (q.bid + q.ask) / 2
    info[a] = dict(hours_to_expiry=float(e), dvol=float(d), ask_iv=float(q.ask_iv.mean()), bid_iv=float(q.bid_iv.mean()),
                   spread_pct_of_mid=float(((q.ask - q.bid) / mid).mean()), n=int(len(q)),
                   premium_pct_spot=float((mid / q.spot).mean()))
json.dump(dict(hs=hs, ratio=ratio, info=info), open(os.path.join(SCR, "delta_cal.json"), "w"), indent=1)
print(json.dumps(dict(hs=hs, ratio=ratio, info=info), indent=1))
