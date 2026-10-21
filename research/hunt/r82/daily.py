"""R82 daily book (PREREG.md section 4): T0 (the app's 12-month rule), T1-T4, D0-D2, G1, U1 on the rupee proxies of the
GOLDM and SILVERM futures. Signal source: the metal's own proxy price (DXY for U1's filter, the gold/silver ratio for G1).

Mechanics (PREREG section 3): a decision at day i's close fills at day i+1's open; stops are checked on each day's
low / high from the entry day on; a trailing stop moves at the close and applies from the next day. A window is run on
its own: flat at its first day, anything open closed at its last day's close.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from research.hunt.r82 import r82lib as L

METALS = ("gold", "silver")


@dataclass
class Market:
    """Both metals on their own trading days, with every indicator a rule needs (each computed from bars up to t)."""
    d: dict

    @classmethod
    def build(cls, frames: dict, dxy: pd.Series | None = None) -> "Market":
        d = {}
        for m, df in frames.items():
            h, l, c = df.high.values, df.low.values, df.close.values
            x = df.copy()
            x["atr20"] = L.wilder_atr(h, l, c, 20)
            x["sign252"] = np.sign(pd.Series(c).diff(252).values)
            x["st"] = L.supertrend_dir(h, l, c, 10, 3.0)
            x["dc55h"], x["dc55l"] = L.donchian(h, l, 55)
            x["dc20h"], x["dc20l"] = L.donchian(h, l, 20)
            r = pd.Series(np.log(c)).diff()
            vol = r.rolling(20).std()
            x["vol20"] = vol.values
            # 80th percentile of the previous 252 days' vol20 (not including today)
            x["vol_p80"] = vol.shift(1).rolling(252).quantile(0.8).values
            nd = pd.Series(x.index.month)
            x["month_end"] = (nd.values != np.r_[nd.values[1:], -1]) & np.r_[np.ones(len(nd) - 1, bool), False]
            d[m] = x
        # gold / silver ratio on common days (USD per oz both; the rupee factors cancel apart from grams, so use USD)
        g, s = d["gold"]["usd_close"], d["silver"]["usd_close"]
        ratio = (g / s).dropna()
        r63 = ratio / ratio.shift(63) - 1
        for m in METALS:
            d[m]["ratio63"] = r63.reindex(d[m].index).ffill().values
            other = "silver" if m == "gold" else "gold"
            d[m]["other_st"] = pd.Series(d[other]["st"].values, index=d[other].index).reindex(d[m].index).ffill().fillna(0).values
            d[m]["other_sign252"] = pd.Series(d[other]["sign252"].values, index=d[other].index).reindex(d[m].index).ffill().fillna(0).values
            if dxy is not None:
                dx63 = (dxy / dxy.shift(63) - 1)
                d[m]["dxy63"] = dx63.reindex(d[m].index, method="ffill").values
        return cls(d)


# ------------------------------------------------------------------------------------------------ rules
# decide(metal, row, held) -> (target side, may open a new position). Called at day i's close.
def _sign(v) -> int:
    return 0 if not np.isfinite(v) else int(np.sign(v))


class Rule:
    name = ""
    monthly = True
    trail_k = 3.0                 # trailing stop in ATR(20); None = none
    init_k = None                 # fixed initial stop in ATR(20)
    long_only = False

    def decide(self, m, r, held):
        raise NotImplementedError


class T0(Rule):
    name, trail_k = "T0", None

    def decide(self, m, r, held):
        return _sign(r.sign252), True


class T1(Rule):
    name = "T1"

    def decide(self, m, r, held):
        return _sign(r.sign252), True


class T2(Rule):
    name, long_only = "T2", True

    def decide(self, m, r, held):
        return max(_sign(r.sign252), 0), True


class T3(Rule):
    name = "T3"

    def decide(self, m, r, held):
        s = _sign(r.sign252)
        return s, s != 0 and s == _sign(r.other_sign252)


class T4(Rule):
    name = "T4"

    def decide(self, m, r, held):
        hi = np.isfinite(r.vol20) and np.isfinite(r.vol_p80) and r.vol20 > r.vol_p80
        return _sign(r.sign252), not hi


class G1(Rule):
    name, long_only = "G1", True

    def decide(self, m, r, held):
        if not np.isfinite(r.ratio63):
            return 0, False
        cand = "silver" if r.ratio63 < 0 else "gold"
        return (1 if (m == cand and _sign(r.sign252) > 0) else 0), True


class U1(Rule):
    name, long_only = "U1", True

    def decide(self, m, r, held):
        ok = np.isfinite(r.dxy63) and r.dxy63 <= 0
        return max(_sign(r.sign252), 0), bool(ok)


class D0(Rule):
    name, monthly, trail_k, init_k = "D0", False, None, 2.0
    filt = None

    def allow(self, r, side):
        return True

    def decide(self, m, r, held):
        c = r.close
        if not (np.isfinite(r.dc55h) and np.isfinite(r.dc20h)):
            return 0, False
        new = 1 if c > r.dc55h else (-1 if c < r.dc55l else 0)
        if held > 0:
            if new < 0 and self.allow(r, -1):
                return -1, True
            return (0, False) if c < r.dc20l else (1, False)
        if held < 0:
            if new > 0 and self.allow(r, 1):
                return 1, True
            return (0, False) if c > r.dc20h else (-1, False)
        if new != 0 and self.allow(r, new):
            return new, True
        return 0, False


class D1(D0):
    name = "D1"

    def allow(self, r, side):
        return r.st == side


class D2(D0):
    name = "D2"

    def allow(self, r, side):
        return r.st == side and r.other_st == side


RULES = {c.name: c for c in (T0, T1, T2, T3, T4, D0, D1, D2, G1, U1)}


# ------------------------------------------------------------------------------------------------ simulator
def run_leg(rule: Rule, metal: str, x: pd.DataFrame, lo, hi) -> list[L.Trade]:
    """One metal's trades of [rule] on days lo..hi (inclusive), starting flat, closed at hi's close."""
    units = L.LOT[metal][1]
    idx = x.index
    a, b = idx.searchsorted(pd.Timestamp(lo)), idx.searchsorted(pd.Timestamp(hi), side="right")
    o, h, l, c = x.open.values, x.high.values, x.low.values, x.close.values
    atr = x.atr20.values
    rows = list(x.itertuples(index=False))
    me = x.month_end.values
    trades = []
    held, ent, ent_i, stop, best = 0, 0.0, 0, np.nan, 0.0
    pending = None                       # (close?, open side) for the next open

    def close_at(i, px, why, stop_fill=False):
        nonlocal held
        ex = L.fill(px, -held, stop_fill)
        trades.append(L.Trade(metal, held, idx[ent_i], idx[i], ent, ex, why, units,
                              L.carry_rs(held, ent, units, idx[ent_i], idx[i]), bars=i - ent_i))
        held = 0

    for i in range(a, b):
        # 1) the order decided at yesterday's close
        if pending is not None:
            do_close, new_side, why = pending
            pending = None
            if do_close and held != 0:
                close_at(i, o[i], why)
            if new_side != 0 and held == 0:
                held, ent, ent_i = new_side, L.fill(o[i], new_side), i
                best = c[i - 1] if i > 0 else o[i]
                k = rule.trail_k or rule.init_k
                stop = (o[i] - held * k * atr[i - 1]) if (k and i > 0 and np.isfinite(atr[i - 1])) else np.nan
        # 2) the stop during the day
        if held != 0 and np.isfinite(stop):
            hit = l[i] <= stop if held > 0 else h[i] >= stop
            if hit:
                px = min(stop, o[i]) if held > 0 else max(stop, o[i])
                close_at(i, px, "stop", stop_fill=True)
        # 3) the close: trail, then the rule's decision
        if held != 0 and rule.trail_k and np.isfinite(atr[i]):
            best = max(best, c[i]) if held > 0 else min(best, c[i])
            ns = best - held * rule.trail_k * atr[i]
            stop = max(stop, ns) if held > 0 else min(stop, ns)
        if i == b - 1:
            break
        if rule.monthly and not me[i]:
            continue
        tgt, may_open = rule.decide(metal, rows[i], held)
        if held != 0 and tgt != held:
            pending = (True, tgt if may_open else 0, "flip" if tgt != 0 else "signal_exit")
        elif held == 0 and tgt != 0 and may_open:
            pending = (False, tgt, "")
    if held != 0:
        close_at(b - 1, c[b - 1], "window_end")
    return trades


def run(rule_name: str, mk: Market, lo, hi) -> list[L.Trade]:
    rule = RULES[rule_name]()
    out = []
    for m in METALS:
        out += run_leg(rule, m, mk.d[m], lo, hi)
    return out


# ------------------------------------------------------------------------------------------------ twins
def twin_books(rule_name: str, trades: list[L.Trade], mk: Market, lo, hi, n=L.N_TWINS, seed=L.SEED) -> np.ndarray:
    """Random-entry twins: same metal, side and holding length (trading days), a random entry day in lo..hi, the same
    stop rule (3-ATR trail / 2-ATR initial / none), exit at the open after the same length or at the window end."""
    rule = RULES[rule_name]()
    rng = np.random.default_rng(seed)
    books = np.zeros(n)
    cache = {}
    for m in METALS:
        x = mk.d[m]
        a, b = x.index.searchsorted(pd.Timestamp(lo)), x.index.searchsorted(pd.Timestamp(hi), side="right")
        cache[m] = (x, a, b)
    for t in trades:
        x, a, b = cache[t.metal]
        o, h, l, c, atr = x.open.values, x.high.values, x.low.values, x.close.values, x.atr20.values
        units = L.LOT[t.metal][1]
        L_ = max(int(t.bars), 1)
        starts = rng.integers(a + 1, max(b - 1, a + 2), size=n)
        for k, j in enumerate(starts):
            side = t.side
            e = L.fill(o[j], side)
            end = min(j + L_, b - 1)
            kk = rule.trail_k or rule.init_k
            stop = o[j] - side * kk * atr[j - 1] if kk and np.isfinite(atr[j - 1]) else np.nan
            best = c[j - 1]
            px, why, xi = None, None, end
            for i in range(j, end + 1):
                if np.isfinite(stop) and ((side > 0 and l[i] <= stop) or (side < 0 and h[i] >= stop)):
                    px = L.fill(min(stop, o[i]) if side > 0 else max(stop, o[i]), -side, True)
                    xi = i
                    break
                if i == end:
                    px = L.fill(o[i] if end < b - 1 else c[i], -side)
                    break
                if rule.trail_k and np.isfinite(atr[i]):
                    best = max(best, c[i]) if side > 0 else min(best, c[i])
                    ns = best - side * rule.trail_k * atr[i]
                    stop = max(stop, ns) if side > 0 else min(stop, ns)
            tw = L.Trade(t.metal, side, x.index[j], x.index[xi], e, px, "twin", units,
                         L.carry_rs(side, e, units, x.index[j], x.index[xi]))
            books[k] += tw.net
    return books
