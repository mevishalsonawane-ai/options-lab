"""RC2 entry rules (pre-registered in PREREG.md before any P&L). Option BUYING only.

Input: one coin's 5-minute index bars (index t = bar START, UTC; columns o, h, l, c) and Deribit's hourly DVOL and
funding (index = hour start). A bar starting at t closes at t + 5 min; that close is the decision time T and the option
is bought at its mark at T. Hourly DVOL / funding for the hour starting at h are used only from h + 1 hour.
Every function returns entries [T, side] where side is "C" (buy a call) or "P" (buy a put), using rows up to T only;
tests/test_rc2.py checks that with the future removed or poisoned.
"""
from dataclasses import dataclass

import numpy as np
import pandas as pd

H = pd.Timedelta(hours=1)
M5 = pd.Timedelta(minutes=5)
YEAR_MIN = 365 * 24 * 60


@dataclass(frozen=True)
class Rule:
    name: str
    title: str
    min_left_h: float      # option expiry: the nearest Delta daily expiry with at least this many hours left at entry
    max_hold_h: float      # forced exit after this long (and never later than 10 min before settlement)
    strike: str            # "atm" or "cheap" (the hero)
    window: tuple          # bar-START times of day (UTC) eligible for an entry, [(start, end), ...] in minutes
    hourly: bool = False   # decisions only on the hour (DVOL / funding rules)


RULES = {
    "R1_asia_orb": Rule("R1_asia_orb", "Asian opening range (00:00-01:00 UTC) breakout, 01:00-07:00", 3, 11, "atm", ((60, 420),)),
    "R2_eu_orb": Rule("R2_eu_orb", "Europe opening range (07:00-08:00 UTC) breakout, 08:00-10:30", 1.5, 3, "atm", ((480, 630),)),
    "R3_us_orb": Rule("R3_us_orb", "US open range (13:30-14:00 UTC) breakout, 14:00-17:00, next-day expiry", 6, 4, "atm", ((840, 1020),)),
    "R4_vol_burst": Rule("R4_vol_burst", "15-min move >= 2.5x the DVOL-implied 15-min move", 1.5, 2, "atm", ((0, 630), (720, 1320))),
    "R5_dvol_jump": Rule("R5_dvol_jump", "DVOL up >= 3% in an hour with a >= 1x implied 1-h move", 3, 3, "atm", ((0, 1440),), True),
    "R6_hero": Rule("R6_hero", "Expiry-day hero: cheap OTM after a 2x-implied 15-min move, 09:00-11:00 UTC", 1 / 6, 3, "cheap", ((535, 655),)),
    "R7_funding_rev": Rule("R7_funding_rev", "Funding extreme (60-day 2.5/97.5 pct) reversal with 1-h confirmation", 6, 6, "atm", ((0, 1440),), True),
    "R8_pdl_break": Rule("R8_pdl_break", "Prior UTC day high/low break, 01:00-22:00", 3, 3, "atm", ((60, 1320),)),
}


def _mod(t):
    return t.hour * 60 + t.minute


def in_window(rule, t_index):
    """Bar starts eligible for the rule (and for its random same-clock twin)."""
    m = t_index.hour * 60 + t_index.minute
    ok = np.zeros(len(t_index), bool)
    for a, b in rule.window:
        ok |= (m >= a) & (m < b)
    if rule.hourly:
        ok &= t_index.minute == 55
    return ok


def known_hourly(series, T):
    """The value of an hourly series (indexed by hour start) known at times T: the last hour that ENDED at or before T."""
    s = series.copy()
    s.index = s.index + H
    s = s.sort_index()
    out = s.reindex(s.index.union(T)).ffill().reindex(T)
    return out.to_numpy(float)


def _frame(bars):
    d = bars[["o", "h", "l", "c"]].copy()
    d["T"] = d.index + M5
    return d


def orb(bars, rng_start, rng_end, win_start, win_end):
    """First 5-min close beyond the day's range (bars starting rng_start..rng_end minutes UTC), bars starting
    win_start..win_end. One entry a day."""
    d = _frame(bars)
    m = d.index.hour * 60 + d.index.minute
    day = d.index.floor("D")
    r = d[(m >= rng_start) & (m < rng_end)].groupby(day[(m >= rng_start) & (m < rng_end)])
    hi, lo, cnt = r.h.max(), r.l.min(), r.c.size()
    need = (rng_end - rng_start) // 5
    full = cnt[cnt == need].index
    w = (m >= win_start) & (m < win_end)
    x = d[w].copy()
    x["day"] = day[w]
    x = x[x.day.isin(full)]
    x["hi"] = x.day.map(hi)
    x["lo"] = x.day.map(lo)
    x["side"] = np.where(x.c > x.hi, "C", np.where(x.c < x.lo, "P", ""))
    x = x[x.side != ""]
    x = x.groupby("day").head(1)
    return x[["T", "side"]].reset_index(drop=True)


def _cooldown(T, side, hours):
    keep, last = [], None
    for t, s in zip(T, side):
        if last is None or t - last >= pd.Timedelta(hours=hours):
            keep.append((t, s))
            last = t
    return pd.DataFrame(keep, columns=["T", "side"])


def vol_burst(bars, dvol, k=2.5, cooldown_h=4, rule=None):
    rule = rule or RULES["R4_vol_burst"]
    d = _frame(bars)
    r15 = d.c / d.c.shift(3) - 1
    iv = known_hourly(dvol, pd.DatetimeIndex(d["T"]))
    imp = iv / 100 * np.sqrt(15 / YEAR_MIN)
    sig = (np.abs(r15.to_numpy()) >= k * imp) & in_window(rule, d.index) & np.isfinite(imp)
    x = d[sig]
    side = np.where(r15[sig] > 0, "C", "P")
    return _cooldown(list(x["T"]), list(side), cooldown_h)


def dvol_jump(bars, dvol, rise=0.03, cooldown_h=6, rule=None):
    rule = rule or RULES["R5_dvol_jump"]
    d = _frame(bars)
    T = pd.DatetimeIndex(d["T"])
    v1 = known_hourly(dvol, T)                 # the hour that just ended
    v0 = known_hourly(dvol, T - H)             # the hour before it
    r1 = (d.c / d.c.shift(12) - 1).to_numpy()
    imp = v1 / 100 * np.sqrt(60 / YEAR_MIN)
    sig = in_window(rule, d.index) & (v1 / v0 - 1 >= rise) & (np.abs(r1) >= imp) & np.isfinite(imp)
    x = d[sig]
    return _cooldown(list(x["T"]), list(np.where(r1[sig] > 0, "C", "P")), cooldown_h)


def hero(bars, dvol, k=2.0, rule=None):
    rule = rule or RULES["R6_hero"]
    d = _frame(bars)
    r15 = (d.c / d.c.shift(3) - 1).to_numpy()
    imp = known_hourly(dvol, pd.DatetimeIndex(d["T"])) / 100 * np.sqrt(15 / YEAR_MIN)
    sig = in_window(rule, d.index) & (np.abs(r15) >= k * imp) & np.isfinite(imp)
    x = d[sig].copy()
    x["side"] = np.where(r15[sig] > 0, "C", "P")
    x["day"] = x.index.floor("D")
    return x.groupby("day").head(1)[["T", "side"]].reset_index(drop=True)


def funding_rev(bars, funding, q=0.025, lookback_h=1440, cooldown_h=12, rule=None):
    rule = rule or RULES["R7_funding_rev"]
    d = _frame(bars)
    T = pd.DatetimeIndex(d["T"])
    f = funding.sort_index()
    # thresholds from the trailing window of hourly values known at T (each hour usable from its end)
    fk = f.copy()
    fk.index = fk.index + H
    hi = fk.rolling(f"{lookback_h}h", min_periods=lookback_h // 2).quantile(1 - q)
    lo = fk.rolling(f"{lookback_h}h", min_periods=lookback_h // 2).quantile(q)
    def at(s):
        return s.reindex(s.index.union(T)).ffill().reindex(T).to_numpy(float)
    fv, h_, l_ = at(fk), at(hi), at(lo)
    r1 = (d.c / d.c.shift(12) - 1).to_numpy()
    w = in_window(rule, d.index)
    put = w & (fv > h_) & (r1 < 0)        # crowd long, price turning down
    call = w & (fv < l_) & (r1 > 0)       # crowd short, price turning up
    sig = put | call
    x = d[sig]
    return _cooldown(list(x["T"]), list(np.where(call[sig], "C", "P")), cooldown_h)


def pdl_break(bars, rule=None):
    rule = rule or RULES["R8_pdl_break"]
    d = _frame(bars)
    day = d.index.floor("D")
    g = d.groupby(day)
    dh, dl, cnt = g.h.max(), g.l.min(), g.c.size()
    prev_full = cnt.shift(1, freq="D") == 288
    ph, pl = dh.shift(1, freq="D"), dl.shift(1, freq="D")
    x = d[in_window(rule, d.index)].copy()
    x["day"] = x.index.floor("D")
    x["ph"] = x.day.map(ph)
    x["pl"] = x.day.map(pl)
    x["ok"] = x.day.map(prev_full).eq(True)          # a day with no full previous day: False
    x = x[x.ok]
    up = x[x.c > x.ph].groupby("day").head(1).assign(side="C")
    dn = x[x.c < x.pl].groupby("day").head(1).assign(side="P")
    return pd.concat([up, dn]).sort_values("T")[["T", "side"]].reset_index(drop=True)


def entries(rule_name, bars, dvol, funding):
    if rule_name == "R1_asia_orb":
        return orb(bars, 0, 60, 60, 420)
    if rule_name == "R2_eu_orb":
        return orb(bars, 420, 480, 480, 630)
    if rule_name == "R3_us_orb":
        return orb(bars, 810, 840, 840, 1020)
    if rule_name == "R4_vol_burst":
        return vol_burst(bars, dvol)
    if rule_name == "R5_dvol_jump":
        return dvol_jump(bars, dvol)
    if rule_name == "R6_hero":
        return hero(bars, dvol)
    if rule_name == "R7_funding_rev":
        return funding_rev(bars, funding)
    if rule_name == "R8_pdl_break":
        return pdl_break(bars)
    raise KeyError(rule_name)
