"""R53 crypto baselines (PREREG section 4; reported, never gated): buy-and-hold and a monthly DCA of BTC and ETH spot
(Binance 1m), Rs 1 lakh each, over the design and final windows, after tax.

- Costs: RC1's base spot cost per side (rc1/config.py SPOT_COST["base"]: fee 0.25% + 18% GST, half-spread 0.15%).
- Buy-and-hold: buy at the window's first minute open, sell at its last minute close.
- DCA: Rs 1 lakh split into equal buys at the first minute of the window and of each later calendar month, all sold
  at the window's last close.
- Tax: common.after_tax per buy lot (31.2% of each winning lot, no set-off); TDS 1% of the sale value (cash locked).
- Rupees at the ECB USD/INR known on each day (data.fx_at).
"""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd

from .. import common as RC
from . import data, perps

DATA_END = date(2026, 10, 12)
POT = 100_000.0


def window(part: str):
    a, b = (RC.DESIGN if part == "design" else (RC.FINAL[0], min(RC.FINAL[1], DATA_END)))
    return pd.Timestamp(a, tz="UTC"), pd.Timestamp(b, tz="UTC") + pd.Timedelta(hours=23, minutes=59)


def lots(spot: pd.DataFrame, buys: list[pd.Timestamp], end: pd.Timestamp, pot: float = POT) -> dict:
    side = perps.rc1k.SPOT_COST["base"].per_side
    px_out = float(spot.c.loc[:end].dropna().iloc[-1])
    fx_out = float(data.fx_at([end])[0])
    each = pot / len(buys)
    nets, sale = [], 0.0
    for t in buys:
        px_in = float(spot.o.loc[t:].dropna().iloc[0])
        fx_in = float(data.fx_at([t])[0])
        coins = each * (1 - side) / (px_in * fx_in)
        value = coins * px_out * fx_out * (1 - side)
        nets.append(value - each)
        sale += coins * px_out * fx_out
    nets = np.array(nets)
    return dict(buys=len(buys), net_pre_tax=float(nets.sum()), net_after_tax=float(RC.after_tax(nets).sum()),
                tds_locked=RC.tds_locked([sale]))


def run(part: str) -> dict:
    a, b = window(part)
    days = (b.normalize() - a).days + 1
    out = {"window": [str(a.date()), str(b.date())], "period_days": days, "costs": "rc1 SPOT_COST base"}
    for coin in data.COINS:
        spot = data.to_grid(pd.read_parquet(RC.cache_dir() / f"spot_{coin}_1m.parquet")).loc[a:b]
        months = [a] + [t for t in pd.date_range(a, b, freq="MS") if t > a]
        bh, dca = lots(spot, [a], b), lots(spot, months, b)
        for x in (bh, dca):
            x["net_per_day_after_tax"] = x["net_after_tax"] / days
        out[coin] = dict(buy_and_hold=bh, monthly_dca=dca)
    return out
