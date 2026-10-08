"""h32 holdout summary (run once, descriptive: no variant passed the gates)."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import HOLD, OUT  # noqa
from fwd import trading_days  # noqa
import numpy as np, pandas as pd  # noqa
T = pd.read_parquet(os.path.join(OUT, "hold_trades.parquet"))
days = trading_days(HOLD, 10 ** 6)
nd = len(days)
for c in ("gross", "net", "net_sp", "net_st"):
    print(c, round(T[c].sum()), "Rs/day", round(T[c].sum() / nd, 1), "per trade", round(T[c].mean(), 1))
print("trades", len(T), "days", nd, "win", (T.net_sp > 0).mean().round(3))
print(T.groupby("und")[["gross", "net", "net_sp"]].sum().round(0))
d = T.groupby("day").net_sp.sum().reindex(days).fillna(0)
eq = d.cumsum(); print("maxDD", round((eq - eq.cummax()).min()), "worst day", round(d.min()))
m = d.groupby([x.strftime("%Y-%m") for x in d.index]).sum(); print("months", m.round(0).to_dict())
