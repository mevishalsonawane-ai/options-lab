"""R46 amendment 1: the same trades costed for Delta Exchange India (R46's own costs) and for CoinSwitch PRO, with the
GST on the fee and without it. The fill, spread and exit slippage do not change (CoinSwitch PRO routes BTC options to
what is most likely Delta's own book). Tax is unchanged: 31.2% of each winning trade's gross gain, fees not deductible,
no loss set-off; 1% TDS on each sale is a cash-flow note.

Option trade rows need: buy, sell (USD per 1 BTC), spot, spot_out (BTC at the fill and at the exit), expiry, and q
(BTC of underlying; R46's pools are per Delta lot, 0.001 BTC, when q is absent).
Perp trade rows need: buy, sell (the fill prices of the long, or of the short's entry and exit), gross.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import amend_cfg as A
from . import data as D
from . import r46cfg as K


def opt_side_fee(scheme: str, price, spot, q, expiry) -> np.ndarray:
    """Fee of one side in USD, GST included as the scheme says."""
    kind, gst = A.OPT_SCHEMES[scheme]
    price, spot, q = (np.asarray(x, float) for x in (price, spot, q))
    if kind == "cs":
        return A.CS_OPT_RATE * spot * q * (1 + gst)
    ex = pd.DatetimeIndex(np.atleast_1d(expiry))
    rules = {e: D.fee_rule(e) for e in ex.unique()}
    rate = np.array([rules[e][0] for e in ex], float)
    cap = np.array([rules[e][1] for e in ex], float)
    return np.minimum(rate * spot * q, cap * np.maximum(price, 0.0) * q) * (1 + gst)


def recost_opt(T: pd.DataFrame, scheme: str) -> pd.DataFrame:
    """A copy of the option trades with fees, net, tax and net_after for this scheme (gross unchanged)."""
    T = T.copy()
    if not len(T):
        return T
    q = T["q"].to_numpy(float) if "q" in T else np.full(len(T), K.LOT_BTC)
    gross = (T.sell.to_numpy(float) - T.buy.to_numpy(float)) * q
    fees = opt_side_fee(scheme, T.buy, T.spot, q, T.expiry) + opt_side_fee(scheme, T.sell, T.spot_out, q, T.expiry)
    T["gross"] = gross
    T["fees"] = fees
    T["net"] = gross - fees
    T["tax"] = K.TAX * np.maximum(gross, 0.0)
    T["net_after"] = T.net - T.tax
    T["tds"] = K.TDS * T.sell.to_numpy(float) * q
    return T


def recost_perp(T: pd.DataFrame, scheme: str) -> pd.DataFrame:
    T = T.copy()
    if not len(T):
        return T
    rate, gst = A.PERP_SCHEMES[scheme]
    T["fees"] = rate * (1 + gst) * (T.buy.to_numpy(float) + T.sell.to_numpy(float))
    T["net"] = T.gross - T.fees
    T["tax"] = K.TAX * np.maximum(T.gross.to_numpy(float), 0.0)
    T["net_after"] = T.net - T.tax
    return T
