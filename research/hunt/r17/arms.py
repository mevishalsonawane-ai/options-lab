"""The paper arms (PREREG.md table), simulated on option minute bars, with a pluggable stop for I5.

Grid rows: row r is the 1-minute bar labelled 09:15 + r, which closes at 09:16 + r. A decision on row r's close trades
at row r + 1's open. Fills / charges: research/obuy/costs.py (Fills 'liq', Costs 'app'), as PREREG.md says.

Each trade record carries what the three checks need: the decision row, the entry fill, the premium sensitivity
(delta), the expected 30/60-minute range at the decision, the per-unit cost the app would estimate, and the net P&L.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

import config as K  # noqa: F401  (sets sys.path)
from research.obuy.costs import Costs, Fills, floor_tick

TICK = 0.05
FILLS = Fills(mode="liq")
COSTS = Costs(mode="app")
SQ_ROW = 355                 # 15:10: the ORB family and VIX divergence sell at this row's open
HERO_EXIT_ROW = 350          # 15:05
OPEN_MIN = 9 * 60 + 15


# ------------------------------------------------------------------------------------------------ shared pricing
def ncdf(x):
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def bs_delta(spot, strike, minutes_to_expiry, vol, call):
    """Black-Scholes delta (r 6.5%, q 0); 0.5 / -0.5 when the inputs are unusable."""
    t = max(minutes_to_expiry, 1.0) / (365.0 * 1440.0)
    if not (spot > 0 and strike > 0 and vol > 0):
        return 0.5 if call else -0.5
    d1 = (math.log(spot / strike) + (0.065 + 0.5 * vol * vol) * t) / (vol * math.sqrt(t))
    return ncdf(d1) if call else ncdf(d1) - 1.0


def bs_price(spot, strike, t, vol, call):
    if vol <= 0 or t <= 0:
        return max(spot - strike, 0.0) if call else max(strike - spot, 0.0)
    d1 = (math.log(spot / strike) + (0.065 + 0.5 * vol * vol) * t) / (vol * math.sqrt(t))
    d2 = d1 - vol * math.sqrt(t)
    df = math.exp(-0.065 * t)
    return spot * ncdf(d1) - strike * df * ncdf(d2) if call else strike * df * ncdf(-d2) - spot * ncdf(-d1)


def implied_vol(premium, spot, strike, minutes, call):
    """Bisection on 1% .. 300% a year; None when the premium is outside what any of those vols give."""
    t = max(minutes, 1.0) / (365.0 * 1440.0)
    lo, hi = 0.01, 3.0
    if not (premium > 0 and spot > 0) or not (bs_price(spot, strike, t, lo, call) <= premium <= bs_price(spot, strike, t, hi, call)):
        return None
    for _ in range(80):
        mid = 0.5 * (lo + hi)
        if bs_price(spot, strike, t, mid, call) < premium:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


def delta_of(spot, strike, minutes, premium, vix_prev, call):
    """Amendment 1: delta at the option's own implied vol; VIX / 100 when none solves."""
    iv = implied_vol(premium, spot, strike, minutes, call)
    return bs_delta(spot, strike, minutes, iv if iv else vix_prev / 100, call)


def last_close(a, row):
    c = a[:row + 1, 3]
    c = c[np.isfinite(c)]
    return float(c[-1]) if len(c) else np.nan


def minutes_to_expiry(day, row, expiry):
    return (expiry - day).days * 1440 + (15 * 60 + 30) - (OPEN_MIN + row + 1)


def cost_per_unit(premium, qty):
    """What the app estimates before a trade (ira-core PreTradeCosts): the round trip's charges at the entry price
    (ProfitLock.roundTripPerUnit) plus two fills' slippage of 5 bps and one tick each."""
    q = np.array([float(qty)])
    p = np.array([float(premium)])
    rt = float(COSTS.rt_per_unit(p, q)[0])
    return rt + 2 * (0.0005 * premium + TICK)


def vol5_lots(a, row, lot):
    v = a[max(0, row - 5):row, 4]
    v = v[np.isfinite(v)]
    return float(v.sum() / lot) if lot else 0.0


def buy_fill(px, a, row, lot):
    return float(FILLS.buy(np.array([px]), np.array([vol5_lots(a, row, lot)]))[0])


def sell_fill(px, a, row, lot, stop=False):
    return float(FILLS.sell(np.array([px]), np.array([vol5_lots(a, row, lot)]), stop=stop)[0])


def charges(e, x, qty):
    return float(COSTS.charge(True, np.array([e]), np.array([qty]))[0] + COSTS.charge(False, np.array([x]), np.array([qty]))[0])


def next_open(a, row):
    """The first printed open at or after row (forward), else the last close."""
    for r in range(row, 375):
        if np.isfinite(a[r, 0]):
            return r, float(a[r, 0])
    fin = np.nonzero(np.isfinite(a[:, 3]))[0]
    r = int(fin[-1])
    return r, float(a[r, 3])


@dataclass
class StopSpec:
    """How the stop is set for one run. form: 'fixed' | 'smart' | 'tight'; k: the multiple of E30 x |delta|."""
    form: str = "fixed"
    k: float = 1.0

    def name(self):
        return "fixed" if self.form == "fixed" else f"{self.form}_k{self.k:g}"


def smart_pts(k, e30, delta):
    if not (np.isfinite(e30) and np.isfinite(delta)):
        return np.nan
    return max(k * e30 * abs(delta), 2 * TICK)


# ------------------------------------------------------------------------------------------------ ORB family
ORB_ARMS = {  # kind, first decision bar, last decision bar (5-min bar indices), per-day cap, side cap, target
    "orb": ("orb", 10, 56, 99, 1, 40.0),
    "orb_fresh": ("fresh", 10, 56, 99, 1, 40.0),
    "orb_sweep": ("sweep", 10, 62, 2, 0, 80.0),
    "range_fade": ("fade", 15, 56, 2, 0, 40.0),
}
LADDER = [(0.25, 0.0), (0.50, 0.25), (0.75, 0.50)]


def orb_signals(d, kind, k0, k1):
    ix = d.ix
    nb = 75
    bh = ix[:, 1].reshape(nb, 5).max(axis=1)
    bl = ix[:, 2].reshape(nb, 5).min(axis=1)
    bc = ix[4::5, 3]
    orh, orl = bh[:10].max(), bl[:10].min()
    w = orh - orl
    strike = float(np.floor(bc[1] / 100 + 0.5) * 100)
    out = []
    for k in range(k0, k1 + 1):
        s = 0
        if kind in ("orb", "fresh"):
            s = 1 if bc[k] > orh else -1 if bc[k] < orl else 0
            if kind == "fresh" and s and ((s > 0 and bc[k - 1] > orh) or (s < 0 and bc[k - 1] < orl)):
                s = 0
        elif kind == "sweep":
            s = -1 if (bh[k] > orh and bc[k] < orh) else 1 if (bl[k] < orl and bc[k] > orl) else 0
        elif kind == "fade" and w > 0:
            s = -1 if (bh[k] >= orh - 0.1 * w and bc[k] < orh) else 1 if (bl[k] <= orl + 0.1 * w and bc[k] > orl) else 0
        if s:
            out.append((k, s, strike))
    return out


def contract(d, strike, side, same_day_ok=True):
    """The nearest expiry on or after the day with this strike / side ('C' for +1)."""
    s = "C" if side > 0 else "P"
    exps = sorted({e for (e, k, ss) in d.opts if k == strike and ss == s and (e > d.day or (same_day_ok and e == d.day))})
    if not exps:
        return None, None
    return exps[0], d.opts[(exps[0], strike, s)]


def sim_fixed_pts(a, c0, e, stop_pts, tgt, lot, qty):
    """The ORB family's exits ('fixed' mode, the app since 07 Oct): one resting SL-M at max(e - stop, lock), the lock
    earned on minute highs before this minute (ladder on the target, never below breakeven after charges), filled on
    the low at the level or a gap open; the target is checked on a minute's high and sold at the next minute's open;
    out at 15:10. Returns (exit price, exit row, why)."""
    trig = floor_tick(np.array([e - stop_pts]))[0] if np.isfinite(stop_pts) else -1.0
    be = e + float(COSTS.rt_per_unit(np.array([e]), np.array([float(qty)]))[0])
    peak = e
    for r in range(c0, SQ_ROW):
        if not np.isfinite(a[r, 3]):
            continue
        o = a[r, 0] if np.isfinite(a[r, 0]) else a[r, 3]
        lock = -1.0
        for reach, keep in LADDER:
            if peak >= e + reach * tgt - 1e-9:
                lv = max(e + keep * tgt, be)
                if lv < peak:
                    lock = max(lock, lv)
        rest = max(trig, lock)
        if rest > TICK and a[r, 2] <= rest:
            stop = trig >= lock
            px = min(rest, o)
            return sell_fill(px, a, r, lot, stop=stop), r, ("stop" if stop else "lock")
        if r <= SQ_ROW - 2 and a[r, 1] >= e + tgt:
            rr, px = next_open(a, r + 1)
            return sell_fill(px, a, rr, lot), rr, "target"
        peak = max(peak, a[r, 1])
    rr, px = next_open(a, SQ_ROW)
    return sell_fill(px, a, rr, lot), rr, "square_off"


def run_orb(days, er, arm, spec: StopSpec):
    kind, k0, k1, cap, side_cap, tgt = ORB_ARMS[arm]
    out = []
    for di, d in enumerate(days):
        if (30, di) not in er.coef:
            continue
        sigs = orb_signals(d, kind, k0, k1)
        n = 0
        per_side = {1: 0, -1: 0}
        block = -1
        for k, s, strike in sigs:
            if 5 * k <= block or n >= cap or (side_cap and per_side[s] >= side_cap):
                continue
            exp, a = contract(d, strike, s, same_day_ok=False)
            if a is None:
                continue
            sig_row = 5 * k + 4
            c0, px = next_open(a, sig_row + 1)
            if c0 > sig_row + 3 or px <= 40.0:          # no print soon after the bar, or premium <= 40 refused
                continue
            qty = d.lot
            e = buy_fill(px, a, c0, d.lot)
            spot = d.ix[sig_row, 3]
            delta = delta_of(spot, strike, minutes_to_expiry(d.day, sig_row, exp), last_close(a, sig_row), d.vix_prev, s > 0)
            e30, e60 = er.predict(di, sig_row, 30), er.predict(di, sig_row, 60)
            sp = smart_pts(spec.k, e30, delta)
            if spec.form == "fixed":
                stop = K.STOP_FIXED
            elif spec.form == "smart":
                stop = sp if np.isfinite(sp) else K.STOP_FIXED
            else:
                stop = min(K.STOP_FIXED, sp) if np.isfinite(sp) else K.STOP_FIXED
            x, xr, why = sim_fixed_pts(a, c0, e, stop, tgt, d.lot, qty)
            out.append(_rec(arm, d, di, sig_row, s, strike, exp, e, x, xr, why, qty, delta, e30, e60, stop, K.STOP_FIXED))
            n += 1
            per_side[s] += 1
            block = xr - (xr % 5)
    return out


def _rec(arm, d, di, sig_row, s, strike, exp, e, x, xr, why, qty, delta, e30, e60, stop, fixed_stop):
    ch = charges(e, x, qty)
    cpu = cost_per_unit(e, qty)
    move = e30 * abs(delta) if np.isfinite(e30) else np.nan
    return dict(arm=arm, und=d.und, day=d.day, di=di, row=sig_row, side=s, strike=strike, expiry=exp, entry=e, exit=x,
                exit_row=xr, why=why, qty=qty, gross=(x - e) * qty, charges=ch, net=(x - e) * qty - ch, delta=delta,
                e30=e30, e60=e60, cost_unit=cpu, x_ratio=cpu / move if move and move > 0 else np.nan, stop_pts=stop,
                fixed_stop=fixed_stop)


# ------------------------------------------------------------------------------------------------ VIX divergence
VD_CHECKS = [10 * 60 + 30, 11 * 60 + 30, 12 * 60 + 30, 13 * 60 + 30]


def run_vixdiv(days, er, spec: StopSpec):
    out = []
    for di, d in enumerate(days):
        if (30, di) not in er.coef or d.vix_min is None or not np.isfinite(d.vix_open):
            continue
        step = 100 if d.und == "BANKNIFTY" else 50
        io = d.ix[0, 0]
        for T in VD_CHECKS:
            row = T - OPEN_MIN - 1
            im = d.ix[row, 3] / io - 1
            vm = d.vix_min[row] / d.vix_open - 1 if np.isfinite(d.vix_min[row]) else np.nan
            if not np.isfinite(vm):
                continue
            s = -1 if (im > 0.002 and vm > 0.02) else 1 if (im < -0.002 and vm < -0.02) else 0
            if not s:
                continue
            x0 = d.ix[row, 3]
            strike = float(round(x0 / step) * step - s * step)
            exp, a = contract(d, strike, s)
            if a is None:
                break
            c0 = next((r for r in range(row + 1, row + 1 + 3) if np.isfinite(a[r, 0])), None)
            if c0 is None:
                break
            qty = d.lot
            e = buy_fill(float(a[c0, 0]), a, c0, d.lot)
            delta = delta_of(x0, strike, minutes_to_expiry(d.day, row, exp), last_close(a, row), d.vix_prev, s > 0)
            e30, e60 = er.predict(di, row, 30), er.predict(di, row, 60)
            sp = smart_pts(spec.k, e30, delta)
            fixed = e - floor_tick(np.array([e * 0.85]))[0] if d.und == "NIFTY" else np.nan
            if spec.form == "fixed":
                stop = fixed
            elif spec.form == "smart" or not np.isfinite(fixed):
                stop = sp
            else:
                stop = min(fixed, sp) if np.isfinite(sp) else fixed
            x, xr, why = sim_vixdiv(a, c0, e, stop, d.und == "NIFTY", d.lot)
            out.append(_rec(f"vix_div_{d.und.lower()}", d, di, row, s, strike, exp, e, x, xr, why, qty, delta, e30, e60,
                            stop, fixed))
            break
    return out


def sim_vixdiv(a, c0, e, stop_pts, time_stop, lot):
    trig = floor_tick(np.array([e - stop_pts]))[0] if np.isfinite(stop_pts) else -1.0
    check = c0 + 19
    for r in range(c0, SQ_ROW):
        if not np.isfinite(a[r, 3]):
            continue
        o = a[r, 0] if np.isfinite(a[r, 0]) else a[r, 3]
        if trig > TICK and a[r, 2] <= trig:
            return sell_fill(min(trig, o), a, r, lot, stop=True), r, "stop"
        if time_stop and r == check and a[r, 3] < e * 1.05:
            rr, px = next_open(a, r + 1)
            return sell_fill(px, a, rr, lot), rr, "time"
    rr, px = next_open(a, SQ_ROW)
    return sell_fill(px, a, rr, lot), rr, "square_off"


# ------------------------------------------------------------------------------------------------ Hero (NIFTY expiry days)
def run_hero(days, er, spec: StopSpec):
    out = []
    for di, d in enumerate(days):
        if d.und != "NIFTY" or (30, di) not in er.coef:
            continue
        chain = {(k, s): a for (e, k, s), a in d.opts.items() if e == d.day}
        if len(chain) < 10:
            continue                                     # not an expiry day with today's chain in the data
        res = _hero_day(d, di, er, chain, spec)
        if res:
            out.append(res)
    return out


def _traded(a, r, back):
    v = a[max(0, r - back):r + 1, 4]
    return bool(np.any(np.isfinite(v) & (v > 0)))


def _hero_day(d, di, er, chain, spec):
    C = d.ix[:, 3]
    low = None
    skipped = 0
    for r in range(164, 330):                            # bars closing 12:00 .. 14:45
        atm = float(np.floor(C[r] / 50 + 0.5) * 50)
        ce, pe = chain.get((atm, "C")), chain.get((atm, "P"))
        st = None
        if ce is not None and pe is not None and _traded(ce, r, 2) and _traded(pe, r, 2):
            cc = ce[:r + 1, 3][np.isfinite(ce[:r + 1, 3])]
            pc = pe[:r + 1, 3][np.isfinite(pe[:r + 1, 3])]
            if len(cc) and len(pc):
                st = float(cc[-1] + pc[-1])
        if st is not None:
            low = st if low is None else min(low, st)
        if r < 254:
            continue
        if st is None or r < 15:
            skipped += 1
            if skipped > 5:
                return None
            continue
        skipped = 0
        mom = C[r] / C[r - 15] - 1
        if low is None or st / low - 1 < 0.15 - 1e-9 or abs(mom) < 0.0025 - 1e-9:
            continue
        s = 1 if mom > 0 else -1
        cands = sorted([(abs(k - C[r]), k, a) for (k, ss), a in chain.items()
                        if ss == ("C" if s > 0 else "P") and ((k > C[r]) if s > 0 else (k < C[r]))], key=lambda z: z[0])
        pick = None
        for i, (_, k, a) in enumerate(cands):
            lc = a[:r + 1, 3][np.isfinite(a[:r + 1, 3])]
            if not len(lc):
                continue
            ltp = float(lc[-1])
            if not (1.0 - 1e-9 <= ltp <= 5.0 + 1e-9) or not _traded(a, r, 4):
                continue
            slack = max(0.10, 0.25 * ltp)
            if any(np.isfinite(b[r, 3]) and b[r, 3] > ltp + slack for _, _, b in cands[i + 1:]):
                continue
            pick = (k, a, ltp)
            break
        if pick is None:
            continue
        k, a, ltp = pick
        ask = ltp + TICK
        limit = float(floor_tick(np.array([min(ask + (2 if ask < 2 else 1) * TICK, ltp * 1.2 + 0.10)]))[0])
        c0 = r + 1
        if not (np.isfinite(a[c0, 2]) and a[c0, 2] <= limit):
            return None                                  # the limit was not reached in the next minute: no trade
        e = min(limit, float(a[c0, 0])) if np.isfinite(a[c0, 0]) else limit
        lots = int(math.floor(5000.0 / (limit * d.lot) + 1e-9))
        if lots <= 0:
            return None
        qty = lots * d.lot
        delta = delta_of(C[r], k, minutes_to_expiry(d.day, r, d.day), ltp, d.vix_prev, s > 0)
        e30, e60 = er.predict(di, r, 30), er.predict(di, r, 60)
        fixed_level = 0.4 * e
        sp = smart_pts(spec.k, e30, delta)
        if spec.form == "fixed" or not np.isfinite(sp):
            level = fixed_level
        elif spec.form == "smart":
            level = e - sp
        else:
            level = max(fixed_level, e - sp)
        gross, ch, xr, why = _hero_exits(a, c0, e, level, lots, d.lot)
        rec = _rec("hero", d, di, r, s, k, d.day, e, e, xr, why, qty, delta, e30, e60, e - level, e - fixed_level)
        rec.update(gross=gross, charges=ch, net=gross - ch)
        return rec
    return None


def _hero_exits(a, c0, e, level, lots, lot):
    t1 = float(floor_tick(np.array([5 * e]))[0])
    t2 = float(floor_tick(np.array([20 * e]))[0])
    held = lots
    half = max(1, int(round(lots / 2)))
    gross = 0.0
    ch = float(COSTS.charge(True, np.array([e]), np.array([lots * lot]))[0])
    why = "time"
    for r in range(c0, 375):
        if not np.isfinite(a[r, 3]):
            if r >= HERO_EXIT_ROW:
                break
            continue
        if held == lots and a[r, 1] >= t1 + TICK:
            q = half * lot
            gross += (t1 - e) * q
            ch += float(COSTS.charge(False, np.array([t1]), np.array([q]))[0])
            held -= half
            why = "5x"
        if held > 0 and held < lots and a[r, 1] >= t2 + TICK:
            q = held * lot
            gross += (t2 - e) * q
            ch += float(COSTS.charge(False, np.array([t2]), np.array([q]))[0])
            return gross, ch, r, "20x"
        if held <= 0:
            return gross, ch, r, why
        if r >= HERO_EXIT_ROW or (r > c0 and a[r, 3] <= level + 1e-9):
            stop = r < HERO_EXIT_ROW
            rr, px = next_open(a, min(r + 1, 374) if stop else r)
            px = max(px - TICK, TICK)
            q = held * lot
            gross += (px - e) * q
            ch += float(COSTS.charge(False, np.array([px]), np.array([q]))[0])
            return gross, ch, rr, ("stop" if stop else why if why != "time" else "15:05")
    fin = np.nonzero(np.isfinite(a[:, 3]))[0]
    px = max(float(a[fin[-1], 3]) - TICK, TICK)
    q = held * lot
    gross += (px - e) * q
    ch += float(COSTS.charge(False, np.array([px]), np.array([q]))[0])
    return gross, ch, int(fin[-1]), "15:05"


# ------------------------------------------------------------------------------------------------ all arms
def run_all(days_by, ers, spec: StopSpec):
    """Every arm's trades under one stop spec. days_by / ers: {und: [Day]}, {und: ERange}."""
    tr = []
    bn = days_by.get("BANKNIFTY")
    if bn:
        for arm in ORB_ARMS:
            tr += run_orb(bn, ers["BANKNIFTY"], arm, spec)
    for u, ds in days_by.items():
        tr += run_vixdiv(ds, ers[u], spec)
    if "NIFTY" in days_by:
        tr += run_hero(days_by["NIFTY"], ers["NIFTY"], spec)
    return tr
