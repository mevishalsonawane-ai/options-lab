"""RC2 costs: Delta India option fees (with the 3.5%-of-premium cap and GST), the bid/ask spread model and fills.
Prices are USD per 1 coin of underlying (Delta's quote); a lot is config.LOT coins."""
import json
import os

from . import config as C

# Full spread / spot = a + b * mid / spot, per coin. Two fits on Delta India's live chain: STRAD_CRYPTO.md's 22 snapshots
# of 8 Oct 2026 (the default) and this study's own polls (anatomy.py writes results/spread_fit_live.json). Both were
# taken in the evening (UTC), with no chain in its last 6 hours, so the simulator uses the WIDER of the two, coefficient by
# coefficient (PREREG amendment 2).
DEFAULT_SPREAD = {"BTC": [0.000098, 0.0179], "ETH": [0.000148, 0.0188]}


def spread_model(path=None):
    path = path or os.path.join(C.OUT, "spread_fit_live.json")
    m = {k: list(v) for k, v in DEFAULT_SPREAD.items()}
    if os.path.exists(path):
        with open(path) as f:
            live = json.load(f)
        for k in C.COINS:
            if k in live:
                m[k] = [max(a, b) for a, b in zip(m[k], live[k])]
    return m


def half_spread(coin, mid, spot, model, mult=1.0):
    """Half the quoted spread in USD; the full spread is never less than one tick."""
    a, b = model[coin]
    full = max((a * spot + b * mid) * mult, C.TICK[coin])
    return full / 2


def fee(coin, price, spot, lots, rate=C.FEE_RATE):
    """Delta India fee for one side, in USD: min(rate x notional, 3.5% x premium) plus 18% GST."""
    q = C.LOT[coin] * lots
    return min(rate * spot * q, C.FEE_CAP * max(price, 0.0) * q) * (1 + C.GST)


def buy_fill(coin, mid, spot, model, mult=1.0):
    """A buy pays the ask (mid + half spread)."""
    return mid + half_spread(coin, mid, spot, model, mult)


def sell_fill(coin, level, spot, model, mult=1.0, market=True):
    """A sell at a level: the bid (level - half spread), less one tick and 1% when it is a market exit. Never below 0."""
    px = level - half_spread(coin, level, spot, model, mult)
    if market:
        px -= C.EXIT_SLIP_TICKS * C.TICK[coin] + C.EXIT_SLIP_FRAC * max(level, 0.0)
    return max(px, 0.0)
