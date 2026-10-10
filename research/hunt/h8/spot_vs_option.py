"""The app arm's OWN option trades (h4 cache: liq_bnfin = BANKNIFTY/FINNIFTY validated port; liq_ext = NIFTY/SENSEX/
MIDCPNIFTY) re-priced on the index spot over the identical holding window (index open at the option's entry minute ->
index open at its exit minute), x side. Separates the signal's directional content from option effects.

    python3 -I research/hunt/h8/spot_vs_option.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import liqcash as LC  # noqa: E402

T = pd.read_parquet(os.path.join(LC.SCR, "hunt", "h4", "cache", "h4", "trades_app.parquet"))
T = T[T.comp.isin(["liq_bnfin", "liq_ext"])].copy()
out = []
for u, g in T.groupby("und"):
    if u == "SENSEX":
        continue
    df = LC.load_minutes("IDX_I", u)
    days, A, cnt = LC.dense(df)
    dmap = {pd.Timestamp(d): q for q, d in enumerate(days)}
    di = g.day.map(dmap)
    ok = di.notna().to_numpy()
    g = g[ok].copy(); di = di[ok].astype(int).to_numpy()
    e = LC.entry_price(A, di, g.entry_min.to_numpy() - LC.OPEN_M)
    x = LC.entry_price(A, di, np.minimum(g.exit_min.to_numpy() - LC.OPEN_M, 374))
    g["spot_pts"] = (x - e) * g.side.to_numpy()
    g["spot_bps"] = g.spot_pts / e * 1e4
    out.append(g)
T = pd.concat(out)
T["per"] = np.where(T.day < LC.HOLD, "pre", "hold")
T["dir"] = np.where(T.side > 0, "long", "short")
pd.set_option("display.width", 200)
print(T.groupby(["und", "dir", "per"]).agg(n=("net", "size"), opt_net_rs=("net", "mean"), opt_gross_rs=("gross", "mean"),
                                         spot_pts=("spot_pts", "mean"), spot_bps=("spot_bps", "mean"),
                                         hit_spot=("spot_pts", lambda v: (v > 0).mean())).round(2).to_string())
print(T.groupby(["dir"]).agg(n=("net", "size"), opt_net=("net", "mean"), spot_bps=("spot_bps", "mean")).round(2))
T[["und", "day", "side", "entry_min", "exit_min", "why", "gross", "net", "spot_pts", "spot_bps"]].to_parquet(
    os.path.join(LC.OUT, "spot_vs_option.parquet"))
