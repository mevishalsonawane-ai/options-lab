"""R144c: the expiry-day "Hero" arm (`HeroRules.kt`), ported line for line, on R98's NIFTY expiry-day records.

No runnable research engine for this arm exists on the lake (R12 replayed hero_deep's v2_trades files, which are not in
the repo), so its rules are ported here. Kept from the Kotlin:
- NIFTY on its own expiry days (R98's expiry calendar from the data);
- the straddle at each minute from 12:00 (ATM by the minute's spot, step 50; a leg not traded for > 2 minutes makes the
  minute invalid); window: bars CLOSING 13:30 .. 14:45; trigger: straddle >= 15% above its low since 12:00 and NIFTY
  >= +-0.25% in 15 minutes (up: CE, down: PE); > 5 skipped minutes in a row in the window: stood down for the day;
- the strike: nearest-the-money OTM option within 3% of spot priced Rs 1-5, traded in its last 5 bars, not stale
  (no further-OTM strike priced above it by more than max(0.10, 25%));
- one entry a day; LIMIT capped at signal price x 1.2 + 0.10 (ask + 1 tick, 2 under Rs 2: with no ask in the data,
  R98's fill: the first of the next 3 bars whose open + the ticks is within the cap, filled at open + the ticks);
- exits on the fill e: half at 5x (a print a tick through floorTick(5e)), the rest at 20x, 15:05, and a 1-minute close
  <= 0.4e (from the bar after the fill's) sells the rest at the next bar's open; checked targets, 15:05, stop.
Not ported (said): the kill switch (12 losing days / Rs 50k; a research replay keeps every day), the bid/ask log.
Size and charges: R98's (Rs 5,000 a ticket, fractional quantity, `hero2.net_legs`), as FT3.

Signal source: OPTION-PRICE (the ATM straddle's premium) plus NIFTY's own 15-minute move; labelled, reported apart.
"""
from __future__ import annotations

import math

import numpy as np

import r98data as R
import r98lib as L98
from hero2 import net_legs

TICK = 0.05
EPS = 1e-9
ST_EXP, MOM, MOM_MIN = 0.15, 0.0025, 15
MIN_P, MAX_P, BAND = 1.0, 5.0, 0.03
LEG_STALE, TRADED_BARS, MAX_SKIPPED = 2, 5, 5
# grid rows are bar STARTS (row r = 09:15 + r); the Kotlin minutes are bar CLOSES: close minute u <-> row u - 1
LOW_FROM_ROW = R.minute(11, 59)          # the bar closing 12:00
FIRST_ROW = R.minute(13, 29)             # closing 13:30
LAST_ROW = R.minute(14, 44)              # closing 14:45
EXIT_ROW = R.minute(15, 5)               # the 15:05 sale at that bar's open
LAST_STOP_ROW = R.minute(15, 3)          # the last bar closing before 15:05


def floor_tick(x: float) -> float:
    return round(math.floor(x / TICK + 1e-6) * TICK, 2)


def atm(spot: float) -> float:
    return float(round(spot / 50.0) * 50.0)


def leg_price(a: np.ndarray, r: int):
    """HeroRules.legPrice at the close of row r: the last bar's close at or before r, if it traded (volume > 0) within
    the last LEG_STALE minutes; else None."""
    if a is None:
        return None
    c, v = a[: r + 1, R.C], a[: r + 1, R.V]
    has = np.nonzero(~np.isnan(c))[0]
    tr = np.nonzero(~np.isnan(c) & (np.nan_to_num(v) > 0))[0]
    if not len(has) or not len(tr) or tr[-1] < r - LEG_STALE:
        return None
    return float(c[has[-1]])


def straddles(day) -> dict:
    sp = day.ix[:, 3]
    out = {}
    for r in range(LOW_FROM_ROW, LAST_ROW + 1):
        if not sp[r] == sp[r]:
            continue
        k = atm(sp[r])
        c, p = leg_price(day.opts.get((k, "C")), r), leg_price(day.opts.get((k, "P")), r)
        if c is not None and p is not None:
            out[r] = c + p
    return out


def scan(day):
    """(signal row, +1 CE / -1 PE) of the first firing bar, or None (HeroRules.scan at every minute of the window)."""
    sp = day.ix[:, 3]
    st = straddles(day)
    low, row = None, 0
    for r in range(LOW_FROM_ROW, LAST_ROW + 1):
        valid = sp[r] == sp[r] and r in st
        if valid:
            low = st[r] if low is None else min(low, st[r])
        if r < FIRST_ROW:
            continue
        back = sp[r - MOM_MIN] if r - MOM_MIN >= 0 else np.nan
        if valid and back == back:
            row = 0
        else:
            row += 1
            if row > MAX_SKIPPED:
                return None                                    # stood down for the day
            continue
        if low is None or low <= EPS or back <= EPS:
            continue
        exp, mom = st[r] / low - 1, sp[r] / back - 1
        if exp < ST_EXP - EPS:
            continue
        if mom >= MOM - EPS:
            return r, 1
        if mom <= -MOM + EPS:
            return r, -1
    return None


def pick(day, r: int, sign: int):
    s = day.ix[r, 3]
    side = "C" if sign > 0 else "P"
    chain = []
    for (k, sd), a in day.opts.items():
        if sd != side or abs(k - s) > BAND * s + EPS:
            continue
        if not ((k > s + EPS) if sign > 0 else (k < s - EPS)):
            continue
        c = a[: r + 1, R.C]
        has = np.nonzero(~np.isnan(c))[0]
        if not len(has):
            continue
        traded = bool((np.nan_to_num(a[max(0, r - TRADED_BARS + 1): r + 1, R.V]) > 0).any())
        chain.append((abs(k - s), k, float(c[has[-1]]), traded))
    chain.sort()
    for i, (_, k, ltp, traded) in enumerate(chain):
        if ltp < MIN_P - EPS or ltp > MAX_P + EPS or not traded:
            continue
        slack = max(0.10, 0.25 * ltp)
        if any(x[2] > ltp + slack + EPS for x in chain[i + 1:]):
            continue
        return k, side, ltp
    return None


def fill(a, r: int, ref: float):
    cap = floor_tick(ref * 1.2 + 0.10)
    for k in range(r + 1, min(r + 4, R.N)):
        o = a[k, R.O]
        if o == o:
            ticks = 2 if o < 2.0 - EPS else 1
            if o + ticks * TICK <= cap + EPS:
                return float(round(o + ticks * TICK, 2)), k
    return None


def walk(a, kb: int, e: float):
    """The exits from the fill bar kb: (legs [(fraction, price + tick)], exit row, kind, best close / e).
    net_legs sells each leg at price - 1 tick, so a target leg passes target + 1 tick to book the target itself."""
    t1, t2, stop = floor_tick(5 * e), floor_tick(20 * e), e * 0.4
    held, half, legs = 1.0, False, []
    o, h, c = a[:, R.O], a[:, R.H], a[:, R.C]
    best = e
    for j in range(kb + 1, R.N):
        if c[j] == c[j]:
            best = max(best, c[j])
        if j >= EXIT_ROW:
            px = o[j] if o[j] == o[j] else last_close(c, j)
            legs.append((held, px))
            return legs, j, "15:05" if not half else "half+15:05", best / e
        hj = h[j]
        if not half and hj == hj and hj >= t1 + TICK - EPS:
            legs.append((0.5, t1 + TICK))
            held, half = 0.5, True
        if half and hj == hj and hj >= t2 + TICK - EPS:
            legs.append((held, t2 + TICK))
            return legs, j, "20x", best / e
        if j <= LAST_STOP_ROW and c[j] == c[j] and c[j] <= stop + EPS:
            nx = j + 1
            px = o[nx] if nx < R.N and o[nx] == o[nx] else c[j]
            legs.append((held, px))
            return legs, nx, "stop" if not half else "half+stop", best / e
    legs.append((held, last_close(c, R.N - 1)))
    return legs, R.N - 1, "15:05", best / e


def last_close(c, j):
    ok = np.nonzero(~np.isnan(c[: j + 1]))[0]
    return float(c[ok[-1]]) if len(ok) else 0.0


def build() -> list:
    out = []
    for day in R.load("NIFTY"):
        sg = scan(day)
        if sg is None:
            continue
        r, sign = sg
        pk = pick(day, r, sign)
        if pk is None:
            continue
        k, side, ltp = pk
        a = day.opts[(k, side)]
        f = fill(a, r, ltp)
        if f is None:
            continue
        e, kb = f
        legs, xr, kind, reach = walk(a, kb, e)
        out.append(dict(arm="HERO", und="NIFTY", day=day.day, side=side, strike=float(k), sig_row=int(r),
                        entry_row=int(kb), entry_px=e, exit_row=int(xr), exit_kind=kind,
                        net=float(net_legs("NIFTY", e, legs)), reach=float(reach)))
    return out
