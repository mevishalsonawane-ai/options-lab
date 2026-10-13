"""RC1 accounting: an Indian small account holding BTC / ETH on an Indian spot exchange and/or INR-settled perps.

Everything is in rupees. One bar grid (daily, or 4h for idea g). Orders fill at a bar's OPEN (decided at the previous
close) or, for stops and liquidations, at a stated price inside the bar. Equity is marked at each bar's close.

Tax (PREREG.md, modelling assumptions; Boss should confirm with a CA):
- spot: each sale order is one disposal; gain = sale value - FIFO cost of the coins sold (fees not deductible); 31.2% on a
  positive gain, losses ignored (no set-off, no carry-forward). Paid at the 31 Mar close. 1% TDS on each sale's value,
  returned 365 days later.
- perps reading A (VDA): each closed position's price P&L + funding, 31.2% if positive, losses ignored.
- perps reading B (business): each financial year's net (price P&L + funding - fees), losses carried forward 8 years.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from datetime import date, timedelta

import numpy as np
import pandas as pd

import config as K


def fy_of(d: date) -> int:
    """Indian financial year label: FY 2024 = 1 Apr 2024 - 31 Mar 2025."""
    return d.year if d.month >= 4 else d.year - 1


# ------------------------------------------------------------------------------------------------ market


@dataclass
class Market:
    t: pd.DatetimeIndex                     # bar open times (UTC)
    d: list                                 # bar dates (python date, UTC)
    o: dict
    h: dict
    l: dict
    c: dict
    fund: dict = field(default_factory=dict)  # sum of funding prints inside each bar (perps only)

    @property
    def n(self) -> int:
        return len(self.t)

    def index_of(self, day: date) -> int:
        """First bar on or after `day`."""
        return int(np.searchsorted(np.array(self.d, dtype="datetime64[D]"), np.datetime64(day)))


def build_market(bars: dict, usdinr: pd.Series, funding: dict | None = None, rule: str | None = None) -> Market:
    """bars: {coin: df(t,o,h,l,c)} in USDT. rule: None (as is) or a pandas offset like '4h' to resample 1h bars.
    Prices become INR with the day's USD/INR (ECB, carried forward). Coins are aligned on common bar times."""
    frames = {}
    for coin, df in bars.items():
        df = df.set_index("t")[["o", "h", "l", "c"]]
        if rule:
            df = df.resample(rule, label="left", closed="left").agg({"o": "first", "h": "max", "l": "min", "c": "last"}).dropna()
        frames[coin] = df
    idx = frames[next(iter(frames))].index
    for df in frames.values():
        idx = idx.intersection(df.index)
    days = idx.normalize()
    fx = usdinr.copy()
    fx.index = pd.to_datetime(fx.index, utc=True)
    ud = days.unique()
    fx = fx.reindex(fx.index.union(ud)).sort_index().ffill().reindex(days).to_numpy()
    m = Market(t=idx, d=[x.date() for x in idx], o={}, h={}, l={}, c={})
    for coin, df in frames.items():
        df = df.reindex(idx)
        for k in "ohlc":
            getattr(m, k)[coin] = df[k].to_numpy() * fx
    if funding:
        step = idx[1] - idx[0] if len(idx) > 1 else pd.Timedelta("1D")
        for coin, f in funding.items():
            ft = f.t.dt.round("h")
            pos = np.searchsorted(idx.values, ft.values, side="right") - 1
            ok = (pos >= 0) & (ft.values < (idx.values[np.clip(pos, 0, None)] + step))
            arr = np.zeros(len(idx))
            np.add.at(arr, pos[ok], f.rate.to_numpy()[ok])
            m.fund[coin] = arr
    return m


# ------------------------------------------------------------------------------------------------ accounts


class SpotBook:
    """Cash + FIFO lots on an Indian spot exchange."""

    def __init__(self, cash: float, cost: K.Cost, tax: bool, tds: bool):
        self.cash = float(cash)
        self.cost = cost
        self.tax_on, self.tds_on = tax, tds
        self.lots: dict = {}
        self.fy_gain = 0.0
        self.tds_q: deque = deque()
        self.tax_paid = self.fees = 0.0
        self.orders = 0
        self.disposals = []          # (date, gain) for tests and reports

    def qty(self, coin) -> float:
        return sum(q for q, _ in self.lots.get(coin, ()))

    def buy(self, coin, inr, px):
        inr = min(inr, self.cash)
        if inr < 1.0:
            return 0.0
        f = self.cost.fee * (1 + K.GST)
        notional = inr / (1 + f)
        fill = px * (1 + self.cost.half_spread)
        q = notional / fill
        self.lots.setdefault(coin, deque()).append([q, fill])
        self.cash -= inr
        self.fees += notional * f
        self.orders += 1
        return q

    def sell(self, coin, q, px, day: date):
        have = self.qty(coin)
        q = min(q, have)
        if q <= 0 or q * px < 1.0:
            return 0.0
        fill = px * (1 - self.cost.half_spread)
        value = q * fill
        fee = value * self.cost.fee * (1 + K.GST)
        basis, left = 0.0, q
        lots = self.lots[coin]
        while left > 1e-15 and lots:
            lq, lp = lots[0]
            take = min(lq, left)
            basis += take * lp
            left -= take
            if take >= lq - 1e-15:
                lots.popleft()
            else:
                lots[0][0] = lq - take
        gain = value - basis
        self.disposals.append((day, gain))
        if self.tax_on and gain > 0:
            self.fy_gain += gain
        tds = value * K.TDS if self.tds_on else 0.0
        if tds:
            self.tds_q.append((day + timedelta(days=K.TDS_LOCK_DAYS), tds))
        self.cash += value - fee - tds
        self.fees += fee
        self.orders += 1
        return value

    def release_tds(self, day: date):
        while self.tds_q and self.tds_q[0][0] <= day:
            self.cash += self.tds_q.popleft()[1]

    def accrued_tax(self) -> float:
        return self.fy_gain * K.TAX if self.tax_on else 0.0

    def pay_tax(self, day: date, px: dict):
        due = self.accrued_tax()
        for _ in range(4):                     # sell pro rata if cash is short (those sales are taxed too)
            if self.cash >= due or due <= 0:
                break
            hold = {c: self.qty(c) * px[c] for c in self.lots if self.qty(c) > 0}
            tot = sum(hold.values())
            if tot <= 0:
                break
            need = (due - self.cash) * 1.05
            for c, v in hold.items():
                self.sell(c, self.qty(c) * min(1.0, need / tot), px[c], day)
            due = self.accrued_tax()
        self.cash -= due
        self.tax_paid += due
        self.fy_gain = 0.0

    def value(self, px: dict) -> float:
        return self.cash + sum(self.qty(c) * px[c] for c in self.lots) + sum(a for _, a in self.tds_q)

    def equity(self, px: dict) -> float:
        return self.value(px) - self.accrued_tax()


class PerpBook:
    """Cash + isolated-margin INR perps (whole contracts)."""

    def __init__(self, cash: float, cost: K.Cost, tax: bool, reading: str = "A", lev: float = 1.0):
        self.cash = float(cash)
        self.cost, self.tax_on, self.reading, self.lev = cost, tax, reading, lev
        self.pos: dict = {}          # coin -> dict(n, entry, margin, funding, fees)
        self.fy_gain = 0.0           # reading A
        self.fy_net = 0.0            # reading B
        self.carry: deque = deque()  # reading B: (fy, loss left)
        self.tax_paid = self.fees = 0.0
        self.orders = 0
        self.liquidations = 0
        self.closed = []             # (date, gain A)

    def open(self, coin, side: int, notional, px) -> int:
        n = int(notional / (K.CONTRACT[coin] * px) + 1e-9)
        if n <= 0:
            return 0
        fill = px * (1 + side * self.cost.half_spread)
        size = n * K.CONTRACT[coin]
        margin = size * fill / self.lev
        fee = size * fill * self.cost.fee * (1 + K.GST)
        self.cash -= margin + fee
        self.fees += fee
        self.orders += 1
        self.pos[coin] = dict(n=side * n, entry=fill, margin=margin, funding=0.0, fees=fee)
        return n

    def liq_price(self, coin) -> float:
        p = self.pos[coin]
        s = np.sign(p["n"])
        return p["entry"] * (1 - s * (1 / self.lev - K.MM))

    def _settle(self, coin, pnl, day, fee):
        p = self.pos.pop(coin)
        self.cash += p["margin"] + pnl - fee
        self.fees += fee
        gain_a = pnl + p["funding"]
        self.closed.append((day, gain_a))
        if self.tax_on:
            if gain_a > 0:
                self.fy_gain += gain_a
            self.fy_net += pnl + p["funding"] - p["fees"] - fee

    def close(self, coin, px, day: date):
        if coin not in self.pos:
            return
        p = self.pos[coin]
        s = np.sign(p["n"])
        fill = px * (1 - s * self.cost.half_spread)
        size = abs(p["n"]) * K.CONTRACT[coin]
        pnl = p["n"] * K.CONTRACT[coin] * (fill - p["entry"])
        fee = size * fill * self.cost.fee * (1 + K.GST)
        self.orders += 1
        self._settle(coin, pnl, day, fee)

    def check_liq(self, coin, lo, hi, day: date) -> bool:
        """Closes the position at its liquidation price, losing the whole margin, if the bar touched it."""
        if coin not in self.pos:
            return False
        p = self.pos[coin]
        lp = self.liq_price(coin)
        hit = lo <= lp if p["n"] > 0 else hi >= lp
        if hit:
            self.liquidations += 1
            self._settle(coin, -p["margin"], day, 0.0)
        return hit

    def apply_funding(self, coin, rate_sum, px):
        """Positive funding: longs pay, shorts receive."""
        if coin not in self.pos or rate_sum == 0:
            return
        p = self.pos[coin]
        pay = -np.sign(p["n"]) * rate_sum * abs(p["n"]) * K.CONTRACT[coin] * px
        self.cash += pay
        p["funding"] += pay

    def accrued_tax(self) -> float:
        if not self.tax_on:
            return 0.0
        if self.reading == "A":
            return self.fy_gain * K.TAX
        net = self.fy_net - sum(x for _, x in self.carry) if self.fy_net > 0 else 0.0
        return max(0.0, net) * K.TAX

    def pay_tax(self, fy: int):
        due = self.accrued_tax()
        if self.tax_on and self.reading == "B":
            while self.carry and self.carry[0][0] < fy - K.CARRY_YEARS:
                self.carry.popleft()
            net = self.fy_net
            if net < 0:
                self.carry.append((fy, -net))
            else:
                while net > 0 and self.carry:
                    y, x = self.carry[0]
                    use = min(x, net)
                    net -= use
                    if use >= x:
                        self.carry.popleft()
                    else:
                        self.carry[0] = (y, x - use)
        self.cash -= due
        self.tax_paid += due
        self.fy_gain = self.fy_net = 0.0

    def unreal(self, px: dict) -> float:
        return sum(p["margin"] + p["n"] * K.CONTRACT[c] * (px[c] - p["entry"]) for c, p in self.pos.items())

    def value(self, px: dict) -> float:
        return self.cash + self.unreal(px)

    def equity(self, px: dict) -> float:
        return self.value(px) - self.accrued_tax()


# ------------------------------------------------------------------------------------------------ results


@dataclass
class Run:
    t: pd.DatetimeIndex
    equity: np.ndarray        # after-tax (or pre-tax) equity at each bar close; the last value is after full liquidation
    start_capital: float
    contributions: float
    final: float
    tax_paid: float
    fees: float
    orders: int
    entries: int
    liquidations: int = 0
    events: int = 0                   # rebalances (idea b)
    units: np.ndarray | None = None   # NAV per unit (SIP)
    flows: list = field(default_factory=list)   # (date, -amount) contributions and (end, +final) for IRR


def _daily(t: pd.DatetimeIndex, eq: np.ndarray) -> pd.Series:
    s = pd.Series(eq, index=t.tz_localize(None) if t.tz is not None else t)
    return s.groupby(s.index.normalize()).last()


def metrics(run: Run) -> dict:
    nav = run.units if run.units is not None else run.equity
    s = _daily(run.t, nav)
    days = (s.index[-1] - s.index[0]).days + 1
    yrs = days / 365.25
    start = 1.0 if run.units is not None else run.start_capital
    end = s.iloc[-1]
    cagr = (end / start) ** (1 / yrs) - 1 if end > 0 else -1.0
    full = pd.concat([pd.Series([start], index=[s.index[0] - pd.Timedelta("1D")]), s])
    peak = full.cummax()
    dd = full / peak - 1
    mdd = dd.min()
    trough = dd.idxmin()
    pk_val = peak.loc[trough]
    after = full.loc[trough:]
    rec = after[after >= pk_val - 1e-9]
    rec_days = (rec.index[0] - trough).days if len(rec) else None
    me = full.groupby(full.index.to_period("M")).last()
    mret = me.pct_change().dropna()
    if len(me):  # first month vs the start value
        mret = pd.concat([pd.Series([me.iloc[0] / start - 1], index=[me.index[0]]), mret])
    roll = full / full.shift(365) - 1 if len(full) > 365 else pd.Series(dtype=float)
    out = dict(days=days, years=round(yrs, 2), final=run.final, cagr=cagr, mdd=mdd,
               worst_month=mret.min() if len(mret) else np.nan,
               worst_12m=roll.min() if roll.notna().any() else np.nan,
               green_months=(mret > 0).mean() if len(mret) else np.nan, months=len(mret),
               orders_per_year=run.orders / yrs, entries_per_year=run.entries / yrs,
               recover_days=rec_days, still_down_days=None if rec_days is not None else (full.index[-1] - trough).days,
               tax_paid=run.tax_paid, fees=run.fees, liquidations=run.liquidations,
               calmar=(cagr / abs(mdd)) if mdd < 0 else np.inf)
    if run.flows:
        out["irr"] = xirr(run.flows)
    return out


def xirr(flows: list) -> float:
    d0 = flows[0][0]
    ts = np.array([(d - d0).days / 365.25 for d, _ in flows])
    cf = np.array([a for _, a in flows])
    lo, hi = -0.99, 10.0
    f = lambda r: np.sum(cf / (1 + r) ** ts)  # noqa: E731
    if f(lo) * f(hi) > 0:
        return np.nan
    for _ in range(200):
        mid = (lo + hi) / 2
        if f(lo) * f(mid) <= 0:
            hi = mid
        else:
            lo = mid
    return (lo + hi) / 2


# ------------------------------------------------------------------------------------------------ the simulator


def mask_to_spells(mask: np.ndarray) -> list:
    """mask[i] = 1: be in position during bar i (decided at close i-1). -> [(a, e)] enter at open a, exit at open e."""
    out, i, n = [], 0, len(mask)
    while i < n:
        if mask[i]:
            j = i
            while j < n and mask[j]:
                j += 1
            out.append((i, j))
            i = j
        else:
            i += 1
    return out


def simulate(m: Market, a: int, b: int, *, capital: float, coins=("BTC", "ETH"), venue: str = "spot",
             case: str = "base", tax: bool = True, tds: bool = True, reading: str = "A", lev: float = 1.0,
             spells: dict | None = None, weights: dict | None = None, band: float = K.VOL_BAND,
             rebal: np.ndarray | None = None, rebal_band: float | None = None, buys: dict | None = None,
             contrib: np.ndarray | None = None, sleeve: float = 0.5, carry: dict | None = None) -> Run:
    """Run bars a..b-1 of `m`. Exactly one plan:
    spells  {coin: [(i_in, i_out, px_out|None)]} absolute bar indices: enter at open i_in with `sleeve` x equity; exit in
            bar i_out at px_out (a stop) or at open i_out; i_out >= b means held to the end.
    weights {coin: array over all bars}: target share of equity decided at close i-1, traded at open i if it differs
            from the current share by more than `band` (or goes to / comes from 0).
    rebal   bool array over all bars: rebalance to 50/50 at that open (rebal_band: instead check |w - 0.5| > band daily).
    buys    {coin: array of rupees to buy at each open} (hold, DCA); contrib: rupees added at each open (SIP, unit NAV).
    carry   {coin: [(i_in, i_out)]}: spot long + perp short of the same size (venue='carry').
    case    cost case: 'low' / 'base' / 'high' (PREREG table) or 'zero' (gross).
    """
    spot_cost = K.ZERO_COST if case == "zero" else K.SPOT_COST[case]
    perp_cost = K.ZERO_COST if case == "zero" else K.PERP_COST[case]
    spot = SpotBook(capital, spot_cost, tax, tds)
    perp = PerpBook(0.0, perp_cost, tax, reading, lev)
    if venue == "perp":
        perp.cash, spot.cash = capital, 0.0
    starts, stops = {}, {}
    for plan in (spells, carry):
        for coin, sp in (plan or {}).items():
            for s in sp:
                if s[1] > a and s[0] < b:
                    starts.setdefault(max(s[0], a), []).append((coin, s))
    eq = np.empty(b - a)
    units_nav = np.empty(b - a) if contrib is not None else None
    units = capital if contrib is not None else None
    flows = [(m.d[a], -capital)] if contrib is not None else []
    contributed = 0.0
    entries = events = 0
    cur_fy = fy_of(m.d[a])
    open_spell: dict = {}
    for i in range(a, b):
        day = m.d[i]
        po = {c: m.o[c][i] for c in coins}
        pc = {c: m.c[c][i] for c in coins}
        if fy_of(day) != cur_fy:      # 31 Mar close passed: pay last year's tax
            spot.pay_tax(day, po)
            perp.pay_tax(cur_fy)
            cur_fy = fy_of(day)
        spot.release_tds(day)
        if contrib is not None and i > a and contrib[i] > 0:
            nav = (spot.equity(po) + perp.equity(po)) / units
            units += contrib[i] / nav
            spot.cash += contrib[i]
            contributed += contrib[i]
            flows.append((day, -contrib[i]))
        # exits due at this open
        for coin, s in list(open_spell.items()):
            if s[1] == i and (len(s) < 3 or s[2] is None):
                _exit(spot, perp, venue, coin, po[coin], day)
                del open_spell[coin]
        # entries at this open
        for coin, s in starts.get(i, []):
            if coin in open_spell:
                continue
            tot = spot.equity(po) + perp.equity(po)
            if venue == "spot":
                spot.buy(coin, sleeve * tot, po[coin])
            elif venue == "perp":
                margin = min(sleeve * tot, perp.cash)
                if perp.open(coin, +1, margin * lev, po[coin]) == 0:
                    continue
            else:   # carry: spot long on the Indian exchange + the same size short on the perp exchange
                budget = min(sleeve * tot, spot.cash)
                n = int(budget * lev / (lev + 1) // (K.CONTRACT[coin] * po[coin] * 1.01))
                if n <= 0:
                    continue
                size = n * K.CONTRACT[coin]
                fill = po[coin] * (1 - perp_cost.half_spread)
                need = size * fill / lev + size * fill * perp_cost.fee * (1 + K.GST)
                spot.cash -= need                 # margin moved to the perp exchange
                perp.cash += need
                perp.open(coin, -1, (n + 0.5) * K.CONTRACT[coin] * po[coin], po[coin])
                spot.buy(coin, size * po[coin] * (1 + spot_cost.half_spread) * (1 + spot_cost.fee * (1 + K.GST)), po[coin])
            entries += 1
            open_spell[coin] = s
        if weights is not None:
            entries += _weights(spot, perp, venue, weights, i, coins, po, band, day, lev)
        if rebal is not None:
            events += _rebal(spot, rebal, rebal_band, i, a, coins, po, day, m)
        if buys is not None:
            for coin in coins:
                amt = buys[coin][i]
                if amt > 0:
                    spot.buy(coin, amt, po[coin])
        # inside the bar: funding, liquidation, stops
        for coin in coins:
            if perp.pos.get(coin):
                perp.apply_funding(coin, m.fund.get(coin, np.zeros(m.n))[i], po[coin])
                if perp.check_liq(coin, m.l[coin][i], m.h[coin][i], day):
                    s = open_spell.pop(coin, None)
                    if venue == "carry" and s is not None:   # the spot leg is sold at the next open
                        open_spell[coin] = (s[0], i + 1, None)
            s = open_spell.get(coin)
            if s is not None and len(s) >= 3 and s[2] is not None and s[1] == i:
                _exit(spot, perp, venue, coin, s[2], day)
                del open_spell[coin]
        # after the last bar's close: sell everything
        if i == b - 1:
            for coin in coins:
                if spot.qty(coin) > 0:
                    spot.sell(coin, spot.qty(coin), pc[coin], day)
                perp.close(coin, pc[coin], day)
            spot.pay_tax(day, pc)
            perp.pay_tax(cur_fy)
            final = spot.cash + perp.cash + sum(x for _, x in spot.tds_q)
            eq[i - a] = final
        else:
            eq[i - a] = spot.equity(pc) + perp.equity(pc)
        if units_nav is not None:
            units_nav[i - a] = eq[i - a] / units
    if contrib is not None:
        flows.append((m.d[b - 1], eq[-1]))
    return Run(t=m.t[a:b], equity=eq, start_capital=capital, contributions=contributed, final=eq[-1],
               tax_paid=spot.tax_paid + perp.tax_paid, fees=spot.fees + perp.fees, orders=spot.orders + perp.orders,
               entries=entries + (sum(1 for c in coins for x in (buys or {}).get(c, [])[a:b] if x > 0) if buys else 0),
               liquidations=perp.liquidations, events=events, units=units_nav, flows=flows)


def _exit(spot, perp, venue, coin, px, day):
    if venue in ("spot", "carry") and spot.qty(coin) > 0:
        spot.sell(coin, spot.qty(coin), px, day)
    if venue in ("perp", "carry"):
        perp.close(coin, px, day)
        if venue == "carry":          # margin and P&L come back to the spot exchange's cash
            spot.cash += perp.cash
            perp.cash = 0.0


def _weights(spot, perp, venue, weights, i, coins, po, band, day, lev) -> int:
    tot = spot.equity(po) + perp.equity(po)
    new = 0
    if tot <= 0:
        return 0
    for coin in coins:
        w = weights[coin][i]
        if venue == "spot":
            cur = spot.qty(coin) * po[coin] / tot
            if (w == 0 and cur > 0) or (w > 0 and abs(w - cur) > band):
                if w < cur:
                    spot.sell(coin, spot.qty(coin) * (1 - w / cur) if w > 0 else spot.qty(coin), po[coin], day)
                else:
                    new += cur == 0
                    spot.buy(coin, (w - cur) * tot, po[coin])
        else:
            p = perp.pos.get(coin)
            cur = (abs(p["n"]) * K.CONTRACT[coin] * po[coin] / lev / tot) if p else 0.0
            if (w == 0 and cur > 0) or (w > 0 and abs(w - cur) > band):
                perp.close(coin, po[coin], day)
                if w > 0:
                    new += cur == 0
                    perp.open(coin, +1, min(w * tot, perp.cash) * lev, po[coin])
    return int(new)


def _rebal(spot, rebal, rebal_band, i, a, coins, po, day, m):
    tot_v = sum(spot.qty(c) * po[c] for c in coins)
    if i == a:
        for c in coins:
            spot.buy(c, spot.cash / (len(coins) - coins.index(c)), po[c])
        return 0
    if rebal_band is not None:
        j = i - 1   # decided at the previous close
        val = {c: spot.qty(c) * m.c[c][j] for c in coins}
        tv = sum(val.values())
        go = tv > 0 and any(abs(val[c] / tv - 0.5) > rebal_band for c in coins)
    else:
        go = bool(rebal[i])
    if not go or tot_v <= 0:
        return 0
    target = (tot_v + spot.cash) / len(coins)
    for c in coins:                               # sell the overweight coin first
        v = spot.qty(c) * po[c]
        if v > target:
            spot.sell(c, spot.qty(c) * (v - target) / v, po[c], day)
    for c in coins:
        v = spot.qty(c) * po[c]
        if v < target:
            spot.buy(c, min(target - v, spot.cash), po[c])
    return 1
