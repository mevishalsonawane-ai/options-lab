"""RC2 trade simulator: Delta India option marks, fills with the spread model, fees, stop + lock exits."""
import numpy as np
import pandas as pd

from . import config as C
from . import costs
from . import data
from .exits import run as run_exit
from .signals import RULES

CHEAP_BAND = (0.0002, 0.0010)    # the hero's option: mark between 0.02% and 0.10% of spot at entry
ATM_MAX_DIST = 0.01              # an "ATM" strike more than 1% from spot means the chain was not fetched there: skip


def pick_expiry(coin, T, min_left_h):
    """The nearest Delta daily expiry (12:00 UTC) with at least min_left_h hours left whose chain covers T."""
    base = T.floor("D") + pd.Timedelta(hours=C.DELTA_EXPIRY_HOUR_UTC)
    for k in range(0, 3):
        E = base + pd.Timedelta(days=k)
        if (E - T) < pd.Timedelta(hours=min_left_h) or E <= T:
            continue
        P = data.panel(coin, E)
        if P is None:
            return None, "no_chain"
        if P.bar(T) is None:
            return None, "chain_window"
        return P, None
    return None, "no_expiry"


def pick_strike(P, side, spot, i0, mode):
    best, bestv = None, None
    for K in P.strikes[side]:
        if not P.tradable((side, K), i0):
            continue
        px = P.arr[(side, K)][i0, 0]
        if not np.isfinite(px) or px <= 0:
            continue
        if mode == "atm":
            d = abs(K - spot)
            if best is None or d < bestv:
                best, bestv = K, d
        else:
            otm = K > spot if side == "C" else K < spot
            if otm and CHEAP_BAND[0] * spot <= px <= CHEAP_BAND[1] * spot and (best is None or px > bestv):
                best, bestv = K, px
    if best is None:
        return None
    if mode == "atm" and abs(best - spot) / spot > ATM_MAX_DIST:
        return None
    return best


def simulate(coin, rule_name, ent, ex, spot_close, smodel, spread_mult=1.0, fee_rate=C.FEE_RATE, one_at_a_time=True):
    """ent: DataFrame [T, side]. spot_close: Series of index closes keyed by bar START. Returns one row per trade."""
    rule = RULES[rule_name]
    rows, busy_until, skips = [], None, {}
    for T, side in zip(ent["T"], ent["side"]):
        if one_at_a_time and busy_until is not None and T < busy_until:
            skips["busy"] = skips.get("busy", 0) + 1
            continue
        P, why = pick_expiry(coin, T, rule.min_left_h)
        if P is None:
            skips[why] = skips.get(why, 0) + 1
            continue
        i0 = P.bar(T)
        spot = spot_close.get(T - C.BAR, np.nan)
        if not np.isfinite(spot):
            skips["no_spot"] = skips.get("no_spot", 0) + 1
            continue
        K = pick_strike(P, side, spot, i0, rule.strike)
        if K is None:
            skips["no_strike"] = skips.get("no_strike", 0) + 1
            continue
        a = P.arr[(side, K)]
        mid = a[i0, 0]
        if mid < 2 * C.TICK[coin]:
            skips["too_cheap"] = skips.get("too_cheap", 0) + 1
            continue
        deadline = min(T + pd.Timedelta(hours=rule.max_hold_h), P.expiry - C.LAST_EXIT_BEFORE_EXPIRY)
        dl_abs = int(np.ceil((deadline - P.t0) / C.BAR))
        seg = a[i0:]
        lvl, why, j, peak = run_exit(ex, mid, seg[:, 0], seg[:, 1], seg[:, 2], seg[:, 3], dl_abs - i0)
        t_exit = P.times[i0 + j]
        buy = costs.buy_fill(coin, mid, spot, smodel, spread_mult)
        spot_x = spot_close.get(t_exit - C.BAR, spot)
        sell = costs.sell_fill(coin, lvl, spot_x, smodel, spread_mult, market=True)
        fee_in = costs.fee(coin, buy, spot, 1, fee_rate)
        fee_out = costs.fee(coin, sell, spot_x, 1, fee_rate) if sell > 0 else 0.0
        pnl_lot = (sell - buy) * C.LOT[coin] - fee_in - fee_out
        cost_lot = buy * C.LOT[coin] + fee_in
        # what the option did after a lock exit, until the forced exit (big winners cut short?)
        after = a[i0 + j + 1:dl_abs, 3] if why == "lock" else a[0:0, 3]   # closes (highs can spike)
        post_max = float(np.nanmax(after)) / mid if len(after) else np.nan
        rows.append(dict(coin=coin, rule=rule_name, exit=ex.name, T=T, side=side, expiry=P.expiry, K=K, spot=spot,
                         hours_left=(P.expiry - T).total_seconds() / 3600, mid=mid, buy=buy, sell=sell, level=lvl,
                         reason=why, t_exit=t_exit, peak=peak, post_max=post_max, gross_ret=lvl / mid - 1,
                         pnl_lot=pnl_lot, cost_lot=cost_lot, ret=pnl_lot / cost_lot))
        busy_until = t_exit
    df = pd.DataFrame(rows)
    df.attrs["skips"] = skips
    return df
