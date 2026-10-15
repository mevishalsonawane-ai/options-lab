"""R53 MCX rules MA1-MA9 (PREREG section 4), each with its original settings and its listed re-tune (RT) variants.

Every rule gives: its signals on a set of allowed days (signal minute j, side, exits), a simulator call (sim.py), and a
random twin (a random entry minute inside the rule's own entry window on a random eligible day of the period, a random
side for two-way rules, the rule's side for one-way rules, the rule's own exits). Futures stops / targets of a twin keep
the distances of a real trade of the same version (M3 run.py randoms()), measured from the twin's own signal price.

Sources (logic copied, not imported, because the old modules hard-code dead scratchpad paths):
- MA1 / MA1b / MA1c / MA8 / MA9: m3lib.range_break_signals + run.py run_book (EVE amendment 1: cut 23:15); app
  McxEveRules (the same rule).
- MA2: app McxUsSilverRules (s at 09:05 from Yahoo 1h px_at; 3% disaster stop; out 10 min before the close; roll days
  skipped).
- MA3 / MA4: research/hunt/r2/cand.py (C1 follow the gap; C3 fade the residual) + hourly.py us_on / inr_on.
- MA5: research/hunt/r2/cand.py C4 (XAUUSD 5-minute lead over GOLDM).
- MA6: research/hunt/r3/r3lib.py make_bars / signals (EMA8/21 cross, ADX14 Wilder > 15) + opt.py simulate.
- MA7: research/hunt/r6/signals6.py gann_swing + lib6.py index_exit / run_fut / nonoverlap (15-minute bars of the
  close-only panel, NAT exit = close back through the swing stop, else 23:15).
"""
from __future__ import annotations

import warnings

import numpy as np
import pandas as pd

from research.hunt.r53.mcx import data as D
from research.hunt.r53.mcx import sim as S

NAN = float("nan")
CUT_2315 = 23 * 60 + 15


def dkey(d):
    return np.datetime64(pd.Timestamp(d).normalize(), "ns")


class Ctx:
    """Lazy loads of the markets and the outside series."""

    def __init__(self, markets=None, yahoo=None, xau=None):
        self._m = dict(markets or {})
        self._y = dict(yahoo or {})
        self._x = xau

    def m(self, sym):
        if sym not in self._m:
            self._m[sym] = D.load_market(sym)
        return self._m[sym]

    def y(self, name):
        if name not in self._y:
            self._y[name] = D.yahoo_hourly(name)
        return self._y[name]

    def xau(self):
        if self._x is None:
            self._x = D.xau_minutes()
        return self._x


# ------------------------------------------------------------------------------------------------ base
class Rule:
    rid = ""
    sym = ""
    inst = "opt"                 # "opt" or "fut"
    two_way = True
    max_per_day = 1
    variants: dict = {"orig": {}}
    skip_expiry = None           # default: options skip expiry days

    def __init__(self):
        if self.skip_expiry is None:
            self.skip_expiry = self.inst == "opt"

    # ---- days
    def eligible_days(self, ctx, days):
        m = ctx.m(self.sym)
        out = []
        for d in days:
            d = dkey(d)
            if d not in m.day_first:
                continue
            if self.skip_expiry and m.is_exp[m.day_first[d]]:
                continue
            out.append(d)
        return out

    # ---- to be provided
    def signals(self, ctx, P, days):
        raise NotImplementedError

    def window(self, ctx, P, d):
        """Grid indices a twin's signal minute may be drawn from on day d (array, may be empty)."""
        raise NotImplementedError

    def twin_sig(self, ctx, P, d, j, side, tmpl):
        raise NotImplementedError

    def simulate(self, ctx, sig, P):
        raise NotImplementedError

    # ---- running
    def run(self, ctx, P, days):
        """Signals on `days` -> trades, one position at a time, at most max_per_day entries a day."""
        sigs = sorted(self.signals(ctx, P, days), key=lambda s: s["j"])
        out, busy, nday = [], -1, {}
        m = ctx.m(self.sym)
        for s in sigs:
            d = m.day[s["j"]]
            if s["j"] < busy or nday.get(d, 0) >= self.max_per_day:
                continue
            t = self.simulate(ctx, s, P)
            if t is None:
                continue
            t.update(rule=self.rid, sig_ts=m.ts[s["j"]], sig_j=int(s["j"]))
            for k in ("stopF", "tgtF", "sigF"):
                if k in s:
                    t[k] = float(s[k])
            out.append(t)
            busy = t["exit_i"]
            nday[d] = nday.get(d, 0) + 1
        return out

    def twins(self, ctx, P, days, n_trades, templates, n_twins, rng, max_tries=30):
        """n_twins sums of net over n_trades random entries each. Returns an array of twin nets."""
        days = self.eligible_days(ctx, days)
        wins = {}
        good = []
        for d in days:
            w = self.window(ctx, P, d)
            if len(w):
                wins[d] = w
                good.append(d)
        if not good or n_trades == 0 or not templates:
            return np.zeros(n_twins)
        nets = np.zeros(n_twins)
        for b in range(n_twins):
            tot, got, tries = 0.0, 0, 0
            while got < n_trades and tries < n_trades * max_tries:
                tries += 1
                d = good[rng.integers(len(good))]
                w = wins[d]
                j = int(w[rng.integers(len(w))])
                side = int(rng.choice([-1, 1])) if self.two_way else int(templates[0]["side"])
                tm = templates[rng.integers(len(templates))]
                s = self.twin_sig(ctx, P, d, j, side, tm)
                if s is None:
                    continue
                t = self.simulate(ctx, s, P)
                if t is None:
                    continue
                tot += t["net"]
                got += 1
            nets[b] = tot
        return nets


# ------------------------------------------------------------------------------------------------ range breaks
def range_break(m, d, a, b, u):
    """m3lib.range_break_signals for one day: range of 1-minute closes in [a, b) (>= max(5, (b - a) // 2) minutes,
    positive width), the first close beyond it in [b, u]. Returns (j, side, hi, lo) or None."""
    ix = m.day_ix(d)
    if not len(ix):
        return None
    t = m.tod[ix]
    r = ix[(t >= a) & (t < b)]
    if len(r) < max(5, (b - a) // 2):
        return None
    hi, lo = m.F[r].max(), m.F[r].min()
    if hi - lo <= 0:
        return None
    for j in ix[(t >= b) & (t <= u)]:
        side = 1 if m.F[j] > hi else (-1 if m.F[j] < lo else 0)
        if side:
            if j + 1 >= len(m.ts) or m.day[j + 1] != m.day[j]:
                return None
            return int(j), side, float(hi), float(lo)
    return None


class RangeBreak(Rule):
    """EVE / INORB / EVT: mid stop, edge + 1 width target, max hold, option -x% stop, cut."""
    fam = ""
    mult = 0.0
    units = 0.0
    tick = 1.0
    cut_tod = None

    def windows(self, ctx, d):
        raise NotImplementedError

    def cut(self, m, d):
        c = m.day_cut[d]
        if self.cut_tod is not None:
            c = min(c, m.at_or_before(d, self.cut_tod))
        return c

    def _sig(self, m, d, j, side, sigF, stopF, tgtF, P):
        cut = self.cut(m, d)
        e = j + 1
        if e >= cut:
            return None
        return dict(j=int(j), side=int(side), sigF=sigF, stopF=stopF, tgtF=tgtF, exit_i=int(min(cut, e + P["maxh"])))

    def signals(self, ctx, P, days):
        m = ctx.m(self.sym)
        out = []
        for d in self.eligible_days(ctx, days):
            w = self.windows(ctx, d)
            if w is None:
                continue
            r = range_break(m, d, *w)
            if r is None:
                continue
            j, side, hi, lo = r
            edge = hi if side > 0 else lo
            s = self._sig(m, d, j, side, float(m.F[j]), (hi + lo) / 2, edge + side * (hi - lo), P)
            if s is not None:
                out.append(s)
        return out

    def window(self, ctx, P, d):
        w = self.windows(ctx, d)
        if w is None:
            return np.array([], dtype=int)
        m = ctx.m(self.sym)
        ix = m.day_ix(d)
        t = m.tod[ix]
        return ix[(t >= w[1]) & (t <= w[2]) & (ix + 1 < self.cut(m, d))]

    def twin_sig(self, ctx, P, d, j, side, tmpl):
        m = ctx.m(self.sym)
        ds = tmpl["side"] * (tmpl["sigF"] - tmpl["stopF"])
        dt = tmpl["side"] * (tmpl["tgtF"] - tmpl["sigF"])
        F = float(m.F[j])
        return self._sig(m, d, j, side, F, F - side * ds, F + side * dt, P)

    def simulate(self, ctx, sig, P):
        m = ctx.m(self.sym)
        if self.inst == "opt":
            return S.sim_option(m, sig["j"], sig["side"], exit_i=sig["exit_i"], fam=self.fam, mult=self.mult,
                                strike="itm1", wait=2, stop_pct=P["opt_stop"], stopF=sig["stopF"], tgtF=sig["tgtF"])
        return S.sim_fut(m, sig["j"] + 1, sig["side"], exit_i=sig["exit_i"], fam=self.fam, units=self.units,
                         tick=self.tick, stopF=sig["stopF"], tgtF=sig["tgtF"])


class EveRule(RangeBreak):
    cut_tod = CUT_2315

    def windows(self, ctx, d):
        return (17 * 60, 19 * 60, 22 * 60)


class MA1(EveRule):
    rid, sym, fam, mult = "MA1", "NATURALGAS", "natgas", D.MULT["NATURALGAS"]
    variants = {"orig": dict(maxh=120, opt_stop=0.15), "stop25": dict(maxh=120, opt_stop=0.25),
                "hold60": dict(maxh=60, opt_stop=0.15)}


class MA1b(EveRule):
    rid, sym, inst, fam, units, tick = "MA1b", "GOLDM", "fut", "gold", D.MULT["GOLDM"], D.TICK["GOLDM"]
    variants = {"orig": dict(maxh=120), "hold60": dict(maxh=60)}


class MA1c(EveRule):
    rid, sym, fam, mult = "MA1c", "CRUDEOIL", "crude", D.MULT["CRUDEOIL"]
    variants = {"orig": dict(maxh=120, opt_stop=0.15), "hold60": dict(maxh=60, opt_stop=0.15)}


class MA8(RangeBreak):
    rid, sym, fam, mult = "MA8", "GOLDM", "gold", D.MULT["GOLDM"]
    variants = {"orig": dict(maxh=60, opt_stop=0.15), "hold120": dict(maxh=120, opt_stop=0.15)}

    def windows(self, ctx, d):
        return (9 * 60, 9 * 60 + 30, 12 * 60)


class MA9(RangeBreak):
    rid, sym, fam, mult = "MA9", "CRUDEOIL", "crude", D.MULT["CRUDEOIL"]
    variants = {"orig": dict(maxh=30, opt_stop=0.15)}
    _eia = None

    def eia(self):
        if self._eia is None:
            self._eia = {dkey(k): v for k, v in D.eia_releases(pd.Timestamp("2025-08-01"), pd.Timestamp("2026-10-31")).items()}
        return self._eia

    def windows(self, ctx, d):
        r = self.eia().get(dkey(d))
        if r is None:
            return None
        return (r - 15, r, r + 30)

    def eligible_days(self, ctx, days):
        return [d for d in super().eligible_days(ctx, days) if d in self.eia()]


# ------------------------------------------------------------------------------------------------ morning gap rules
class MorningRule(Rule):
    """One entry a day at a fixed time (09:05 signal), exit at 14:00 or 10 min before the close; roll days skipped."""
    fam = ""
    mult = 0.0
    units = 0.0
    tick = 1.0
    opt_wait = 30

    def eligible_days(self, ctx, days):
        m = ctx.m(self.sym)
        out = []
        for d in super().eligible_days(ctx, days):
            i0 = m.day_first[d]
            if i0 == 0:
                continue
            if m.seg[i0] != m.seg[i0 - 1]:          # the near contract changed overnight
                continue
            out.append(d)
        return out

    def sig_minute(self, m, d):
        return m.at_or_after(d, 9 * 60 + 5, until=9 * 60 + 15)

    def exit_index(self, m, d, P):
        return m.at_or_before(d, 14 * 60) if P.get("exit", "x14") == "x14" else m.day_cut[d]

    def side_of(self, ctx, m, d, j, P):
        raise NotImplementedError

    def _sig(self, ctx, m, d, j, side, P):
        x = self.exit_index(m, d, P)
        if j < 0 or x <= j + 1 or side == 0:
            return None
        return dict(j=int(j), side=int(side), exit_i=int(x))

    def signals(self, ctx, P, days):
        m = ctx.m(self.sym)
        out = []
        for d in self.eligible_days(ctx, days):
            j = self.sig_minute(m, d)
            if j < 0:
                continue
            side = self.side_of(ctx, m, d, j, P)
            s = self._sig(ctx, m, d, j, side, P)
            if s is not None:
                out.append(s)
        return out

    def window(self, ctx, P, d):
        m = ctx.m(self.sym)
        j = self.sig_minute(m, d)
        return np.array([j] if j >= 0 else [], dtype=int)

    def twin_sig(self, ctx, P, d, j, side, tmpl):
        return self._sig(ctx, ctx.m(self.sym), d, j, side, P)

    def simulate(self, ctx, sig, P):
        m = ctx.m(self.sym)
        if self.inst == "opt":
            return S.sim_option(m, sig["j"], sig["side"], exit_i=sig["exit_i"], fam=self.fam, mult=self.mult,
                                strike="itm1", wait=self.opt_wait, stop_pct=P.get("opt_stop"))
        return S.sim_fut(m, sig["j"] + 1, sig["side"], exit_i=sig["exit_i"], fam=self.fam, units=self.units,
                         tick=self.tick, stop_pct=P.get("fut_stop_pct"))


def gap_at(m, d, j):
    """ln(F at the signal minute / the previous session's last price); NaN when the contract changed."""
    i0 = m.day_first[dkey(d)]
    if i0 == 0 or m.seg[i0] != m.seg[i0 - 1] or not (m.F[i0 - 1] > 0 and m.F[j] > 0):
        return NAN
    return float(np.log(m.F[j] / m.F[i0 - 1]))


class MA3Fut(MorningRule):
    rid, sym, inst, fam, units, tick = "MA3_fut", "NATURALGAS", "fut", "natgas", 250.0, D.TICK["NATGASMINI"]
    variants = {"orig": dict(exit="x14"), "xeod": dict(exit="xeod")}

    def side_of(self, ctx, m, d, j, P):
        g = gap_at(m, d, j)
        return int(np.sign(g)) if np.isfinite(g) else 0


class MA3Opt(MA3Fut):
    rid, inst, mult = "MA3_opt", "opt", D.MULT["NATURALGAS"]
    variants = {"orig": dict(exit="x14"), "xeod": dict(exit="xeod"), "stop30": dict(exit="x14", opt_stop=0.30)}


US_ON_SYM = {"CRUDEOIL": "CL_F", "NATURALGAS": "NG_F", "GOLDM": "GC_F", "SILVERM": "SI_F"}


def us_overnight(ctx, m, d, us_sym, prev_close_utc=None):
    """R2 hourly.py: us_on = ln(px_at(08:30 IST) / px_at(previous MCX day 18:00 UTC)), inr_on likewise; returns
    (us_on, inr_on). prev_close_utc overrides the previous close time (the app's MA2 reads the real MCX close)."""
    d = pd.Timestamp(dkey(d))
    i0 = m.day_first[dkey(d)]
    if i0 == 0:
        return NAN, NAN
    prev = pd.Timestamp(m.day[i0 - 1])
    t_prev = prev_close_utc if prev_close_utc is not None else prev + pd.Timedelta(hours=18)
    t_pre = d + pd.Timedelta(hours=3)              # 08:30 IST
    y, inr = ctx.y(us_sym), ctx.y("INR_X")
    a, b = D.px_at(y, t_prev), D.px_at(y, t_pre)
    i_a, i_b = D.px_at(inr, t_prev), D.px_at(inr, t_pre)
    us_on = float(np.log(b / a)) if a > 0 and b > 0 else NAN
    inr_on = float(np.log(i_b / i_a)) if i_a > 0 and i_b > 0 else NAN
    return us_on, inr_on


class MA4Base(MorningRule):
    thr = 0.0

    def side_of(self, ctx, m, d, j, P):
        g = gap_at(m, d, j)
        us_on, inr_on = us_overnight(ctx, m, d, US_ON_SYM[self.sym])
        if not (np.isfinite(g) and np.isfinite(us_on)):
            return 0
        res = g - (us_on + (inr_on if np.isfinite(inr_on) else 0.0))
        return int(-np.sign(res)) if abs(res) > self.thr * P.get("thr_x", 1.0) else 0


class MA4Crude(MA4Base):
    rid, sym, inst, fam, units, tick, thr = "MA4_crudeoilm", "CRUDEOIL", "fut", "crude", 10.0, 1.0, 12e-4
    variants = {"orig": dict(exit="x14"), "thr15": dict(exit="x14", thr_x=1.5)}


class MA4NG(MA4Base):
    rid, sym, inst, fam, mult, thr = "MA4_ng_opt", "NATURALGAS", "opt", "natgas", D.MULT["NATURALGAS"], 22e-4
    variants = {"orig": dict(exit="x14"), "thr15": dict(exit="x14", thr_x=1.5)}


class MA4Gold(MA4Base):
    rid, sym, inst, fam, units, tick, thr = "MA4_goldten", "GOLDM", "fut", "gold", 1.0, 1.0, 11e-4
    variants = {"orig": dict(exit="x14"), "thr15": dict(exit="x14", thr_x=1.5)}


class MA2(MorningRule):
    """App McxUsSilverRules on the SILVERMIC proxy (SILVERM near future per kg x 1)."""
    rid, sym, inst, fam, units, tick = "MA2", "SILVERM", "fut", "silver", 1.0, 1.0
    variants = {"orig": dict(exit="xeod", fut_stop_pct=0.03), "x14": dict(exit="x14", fut_stop_pct=0.03)}

    def sig_minute(self, m, d):
        # the app enters from 09:05 for 5 minutes; the signal is known at 08:30, so the entry is the close of the first
        # minute at or after 09:05 (sim_fut fills at the close of j + 1: j = that minute - 1).
        e = m.at_or_after(d, 9 * 60 + 5, until=9 * 60 + 9)
        return e - 1 if e > 0 else -1

    def side_of(self, ctx, m, d, j, P):
        i0 = m.day_first[dkey(d)]
        prev = m.day[i0 - 1]
        close_min = m.session_end(prev)
        t_prev = pd.Timestamp(prev) + pd.Timedelta(minutes=close_min) - pd.Timedelta(hours=5, minutes=30)
        us_on, inr_on = us_overnight(ctx, m, d, "SI_F", prev_close_utc=t_prev)
        if not (np.isfinite(us_on) and np.isfinite(inr_on)):
            return 0
        return int(np.sign(us_on + inr_on))

    def _sig(self, ctx, m, d, j, side, P):
        s = super()._sig(ctx, m, d, j, side, P)
        if s is not None and m.day[j] != m.day[j + 1]:
            return None
        return s


# ------------------------------------------------------------------------------------------------ MA5 XAU lead
class MA5(Rule):
    rid, sym, fam, mult = "MA5", "GOLDM", "gold", D.MULT["GOLDM"]
    variants = {"orig": dict(hold=15), "hold30": dict(hold=30)}
    thr = 23.8e-4
    _cu = None

    def catch_up(self, ctx):
        """cu[i] = 5-min ln XAUUSD change - 5-min ln GOLDM change at minute i (both known at i's close)."""
        if self._cu is None:
            m = ctx.m(self.sym)
            ts = pd.DatetimeIndex(m.ts)
            u = ctx.xau().reindex(ts).ffill(limit=2).values
            with np.errstate(all="ignore"):
                lf, lu = np.log(m.F), np.log(u)
            cont5 = np.r_[np.full(5, False), (ts[5:] - ts[:-5]) == pd.Timedelta(minutes=5)]
            cu = np.full(len(ts), np.nan)
            cu[5:] = (lu[5:] - lu[:-5]) - (lf[5:] - lf[:-5])
            cu[~cont5] = np.nan
            self._cu = cu
        return self._cu

    def _sig(self, m, d, j, side, P):
        e = j + 1
        x = e + P["hold"]
        if x >= len(m.ts) or m.day[x] != m.day[j] or x > m.day_cut[dkey(d)]:
            return None
        return dict(j=int(j), side=int(side), exit_i=int(x))

    def signals(self, ctx, P, days):
        m = ctx.m(self.sym)
        cu = self.catch_up(ctx)
        ok = set(self.eligible_days(ctx, days))
        out = []
        with np.errstate(invalid="ignore"):
            hits = np.nonzero(np.abs(cu) > self.thr)[0]
        for j in hits:
            d = m.day[j]
            if d not in ok:
                continue
            s = self._sig(m, d, j, int(np.sign(cu[j])), P)
            if s is not None:
                out.append(s)
        return out

    def window(self, ctx, P, d):
        m = ctx.m(self.sym)
        cu = self.catch_up(ctx)
        ix = m.day_ix(d)
        ix = ix[np.isfinite(cu[ix])]
        return ix[ix + 1 + P["hold"] <= m.day_cut[dkey(d)]]

    def twin_sig(self, ctx, P, d, j, side, tmpl):
        return self._sig(ctx.m(self.sym), d, j, side, P)

    def simulate(self, ctx, sig, P):
        return S.sim_option(ctx.m(self.sym), sig["j"], sig["side"], exit_i=sig["exit_i"], fam=self.fam,
                            mult=self.mult, strike="itm1", wait=2)


# ------------------------------------------------------------------------------------------------ MA6 EMA/ADX
def ema(x, n):
    return pd.Series(x).ewm(span=n, adjust=False).mean().values


def adx(h, l, c, n=14):
    """Wilder ADX (research/hunt/r3/r3lib.py adx)."""
    h, l, c = (pd.Series(a) for a in (h, l, c))
    pc = c.shift(1)
    tr = pd.concat([h - l, (h - pc).abs(), (l - pc).abs()], axis=1).max(axis=1)
    up, dn = h.diff(), -l.diff()
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    mdm = np.where((dn > up) & (dn > 0), dn, 0.0)
    a = 1.0 / n
    atr = tr.ewm(alpha=a, adjust=False).mean()
    pdi = 100 * pd.Series(pdm).ewm(alpha=a, adjust=False).mean() / atr
    mdi = 100 * pd.Series(mdm).ewm(alpha=a, adjust=False).mean() / atr
    dx = 100 * (pdi - mdi).abs() / (pdi + mdi).replace(0, np.nan)
    return dx.ewm(alpha=a, adjust=False).mean().values


def bars_from_closes(m, tf):
    """tf-minute bars anchored at 09:00 from the near future's minute closes, weekdays, 09:00 to the session end
    (r3lib.minute_frame / make_bars). Columns: day, start, sig_min (bar's last clock minute, capped at the end - 1),
    o, h, l, c, last_i (grid index of the bar's last minute)."""
    end = np.array([m.session_end(d) for d in m.day]) if len(m.ts) else np.array([], int)
    wk = pd.DatetimeIndex(m.day).weekday < 5
    keep = (m.tod >= D.OPEN_M) & (m.tod < end) & wk
    ix = np.nonzero(keep)[0]
    df = pd.DataFrame({"day": m.day[ix], "b": (m.tod[ix] - D.OPEN_M) // tf, "F": m.F[ix], "i": ix, "end": end[ix]})
    g = df.groupby(["day", "b"], sort=True)
    B = g.agg(o=("F", "first"), h=("F", "max"), l=("F", "min"), c=("F", "last"), last_i=("i", "last"),
              end=("end", "first")).reset_index()
    B["start"] = D.OPEN_M + B.b * tf
    B["sig_min"] = np.minimum(B.start + tf - 1, B.end - 1)
    return B


def ema_adx_signals(B, fast=8, slow=21, thr=15.0):
    """r3lib.signals: (e8 - e21) changes sign on this bar's close and ADX14 > thr on this bar."""
    e1, e2 = ema(B.c.values, fast), ema(B.c.values, slow)
    ad = adx(B.h.values, B.l.values, B.c.values)
    dd = e1 - e2
    dp = np.r_[np.nan, dd[:-1]]
    up = (dd > 0) & (dp <= 0) & (ad > thr)
    dn = (dd < 0) & (dp >= 0) & (ad > thr)
    Sg = B[up | dn].copy()
    Sg["side"] = np.where(up, 1, -1)[up | dn]
    return Sg.reset_index(drop=True)


class MA6(Rule):
    rid, sym, fam, mult = "MA6", "GOLDM", "gold", D.MULT["GOLDM"]
    max_per_day = 2
    skip_expiry = False          # R3 kept expiry days (its report trades them)
    variants = {"orig": dict(strike="atm", am=True), "itm1": dict(strike="itm1", am=True),
                "allday": dict(strike="atm", am=False)}
    _B = None

    def bars(self, ctx):
        if self._B is None:
            self._B = bars_from_closes(ctx.m(self.sym), 5)
        return self._B

    def _sig(self, m, d, j, side, P):
        cut = m.at_or_before(d, CUT_2315)
        if j + 1 >= cut:
            return None
        return dict(j=int(j), side=int(side), exit_i=int(cut))

    def signals(self, ctx, P, days):
        m = ctx.m(self.sym)
        Sg = ema_adx_signals(self.bars(ctx))
        if P["am"]:
            Sg = Sg[Sg.start < 15 * 60]
        ok = set(self.eligible_days(ctx, days))
        out = []
        for r in Sg.itertuples():
            if r.day not in ok or r.sig_min + 1 >= CUT_2315:
                continue
            s = self._sig(m, r.day, int(r.last_i), int(r.side), P)
            if s is not None:
                out.append(s)
        return out

    def window(self, ctx, P, d):
        B = self.bars(ctx)
        b = B[B.day == d]
        if P["am"]:
            b = b[b.start < 15 * 60]
        b = b[b.sig_min + 1 < CUT_2315]
        return b.last_i.values.astype(int)

    def twin_sig(self, ctx, P, d, j, side, tmpl):
        return self._sig(ctx.m(self.sym), d, j, side, P)

    def simulate(self, ctx, sig, P):
        return S.sim_option(ctx.m(self.sym), sig["j"], sig["side"], exit_i=sig["exit_i"], fam=self.fam,
                            mult=self.mult, strike=P["strike"], wait=2, stop_pct=0.25, tgt_pct=0.50,
                            hold_from_fill=60)


# ------------------------------------------------------------------------------------------------ MA7 Gann swing
W_COLS = 15 * 60                  # panel columns 09:00 .. 23:59
SQ = CUT_2315 - D.OPEN_M          # flat by 23:15
W0, W1 = 5, 22 * 60 + 45 - D.OPEN_M


class Panel:
    """Day x minute panel of near-future closes (lib6.load_panel): forward-filled inside a day up to its last real
    minute; ok days = >= 300 real minutes and a last minute at or after 22:00; roll = contract changed overnight."""

    def __init__(self, m):
        days = np.unique(m.day)
        days = days[pd.DatetimeIndex(days).weekday < 5]
        self.days = days
        pos = {d: k for k, d in enumerate(days)}
        nd = len(days)
        Cr = np.full((nd, W_COLS), np.nan)
        cc = m.tod - D.OPEN_M
        sel = np.array([d in pos for d in m.day]) & (cc >= 0) & (cc < W_COLS)
        rows = np.array([pos[d] for d in m.day[sel]], dtype=int)
        Cr[rows, cc[sel]] = m.F[sel]
        nreal = np.isfinite(Cr).sum(1)
        last = np.array([np.nonzero(np.isfinite(r))[0].max() if np.isfinite(r).any() else -1 for r in Cr])
        Cf = pd.DataFrame(Cr).T.ffill().T.to_numpy(copy=True)
        for k in range(nd):
            Cf[k, last[k] + 1:] = np.nan
        seg = np.array([m.seg[m.day_first[d]] for d in days])
        self.C = Cf
        self.roll = np.r_[True, seg[1:] != seg[:-1]]
        self.ok = (nreal >= 300) & (last >= 22 * 60 - D.OPEN_M)
        self.pos = pos


def bars15(C, tf=15):
    """h37 sigs.bars on a close-only panel: per day, tf-minute bars of the columns, kept when any close exists."""
    n, W = C.shape
    nb = -(-W // tf)
    pad = nb * tf - W
    A = np.concatenate([C, np.full((n, pad), np.nan)], axis=1).reshape(n, nb, tf)
    okc = ~np.isnan(A)
    keep = okc.any(axis=2)
    first = np.argmax(okc, axis=2)
    lastc = tf - 1 - np.argmax(okc[:, :, ::-1], axis=2)
    ii, jj = np.indices((n, nb))
    o = A[ii, jj, first]
    c = A[ii, jj, lastc]
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)       # empty bars are dropped by `keep` below
        h = np.nanmax(A, axis=2)
        lo = np.nanmin(A, axis=2)
    col_end = np.minimum((jj + 1) * tf, W) - 1
    sel = keep.ravel()
    return dict(o=o.ravel()[sel], h=h.ravel()[sel], l=lo.ravel()[sel], c=c.ravel()[sel], dpos=ii.ravel()[sel],
                col_end=col_end.ravel()[sel])


def gann_swing_signals(B):
    """signals6.gann_swing: two higher-high (lower-low) bars, outside inside bars, turn the swing; a close above the
    last swing top (below the last bottom) starts an up (down) trend: +1 long with stop = last bottom, -1 short with
    stop = last top. Yields (bar index, side, stop)."""
    h, l, c = B["h"], B["l"], B["c"]
    n = len(c)
    out = []
    if n == 0:
        return out
    p = 0
    cu = cd = 0
    d = 0
    ext_hi, ext_lo = h[0], l[0]
    top = bot = None
    trend = 0
    for t in range(1, n):
        inside = h[t] <= h[p] and l[t] >= l[p]
        if not inside:
            hh, ll = h[t] > h[p], l[t] < l[p]
            if hh and not ll:
                cu, cd = cu + 1, 0
            elif ll and not hh:
                cd, cu = cd + 1, 0
            p = t
        ext_hi, ext_lo = max(ext_hi, h[t]), min(ext_lo, l[t])
        if d != 1 and cu >= 2:
            if d == -1:
                bot = ext_lo
            d, ext_hi, ext_lo = 1, h[t], l[t]
        elif d != -1 and cd >= 2:
            if d == 1:
                top = ext_hi
            d, ext_hi, ext_lo = -1, h[t], l[t]
        sig = None
        if top is not None and trend != 1 and c[t] > top:
            trend, sig = 1, 1
        elif bot is not None and trend != -1 and c[t] < bot:
            trend, sig = -1, -1
        if sig is None:
            continue
        stop = bot if sig == 1 else top
        if stop is None:
            continue
        out.append((t, sig, float(stop)))
    return out


class MA7(Rule):
    """Gann swing chart, 15-minute bars, SILVERMIC proxy (SILVERM near future per kg x 1), R6's NAT exit, flat 23:15,
    one position at a time, at most 3 a day (lib6.nonoverlap)."""
    rid, sym, inst, fam, units, tick = "MA7", "SILVERM", "fut", "silver", 1.0, 1.0
    two_way = False
    max_per_day = 3
    variants = {"orig": dict(sides=(1,)), "both": dict(sides=(1, -1), two_way=True)}
    _P = None
    _sigs = None

    def panel(self, ctx):
        if self._P is None:
            self._P = Panel(ctx.m(self.sym))
            B = bars15(self._P.C)
            self._sigs = [(int(B["dpos"][t]), int(B["col_end"][t]), side, stop)
                          for t, side, stop in gann_swing_signals(B)]
        return self._P

    def eligible_days(self, ctx, days):
        P = self.panel(ctx)
        out = []
        for d in days:
            d = dkey(d)
            k = P.pos.get(d)
            if k is not None and P.ok[k] and not P.roll[k]:
                out.append(d)
        return out

    def signals(self, ctx, Pv, days):
        P = self.panel(ctx)
        ok = set(self.eligible_days(ctx, days))
        out = []
        for di, s, side, stop in self._sigs:
            if P.days[di] not in ok or side not in Pv["sides"] or not (W0 <= s <= W1):
                continue
            g = self._sig(P, di, s, side, stop)
            if g is not None:
                out.append(g)
        return out

    def _sig(self, P, di, s, side, stop):
        if s + 1 >= SQ or not np.isfinite(P.C[di, s + 1]):
            return None
        # j is an ordering key only (MA7 runs on the panel, not the minute grid)
        return dict(j=int(di * W_COLS + s), di=int(di), s=int(s), side=int(side), stopF=float(stop),
                    sigF=float(P.C[di, s]), tgtF=NAN)

    def window(self, ctx, Pv, d):
        P = self.panel(ctx)
        di = P.pos[dkey(d)]
        cols = np.arange(W0, W1 + 1)
        cols = cols[np.isfinite(P.C[di, cols]) & np.isfinite(P.C[di, np.minimum(cols + 1, W_COLS - 1)])]
        return di * W_COLS + cols

    def twin_sig(self, ctx, Pv, d, j, side, tmpl):
        P = self.panel(ctx)
        di, s = divmod(int(j), W_COLS)
        dist = tmpl["side"] * (tmpl["sigF"] - tmpl["stopF"])
        F = P.C[di, s]
        return self._sig(P, di, s, side, F - side * dist)

    def simulate(self, ctx, sig, Pv):
        """lib6.run_fut with the NAT exit: fill at the close of column s + 1; exit at the close of the first column
        after s whose close is back through the stop (at least e + 1), else at 23:15."""
        P = self.panel(ctx)
        di, s, side = sig["di"], sig["s"], sig["side"]
        e = s + 1
        en = P.C[di, e]
        c = P.C[di, s + 1:SQ]
        with np.errstate(invalid="ignore"):
            hit = (c <= sig["stopF"]) if side > 0 else (c >= sig["stopF"])
        if hit.any():
            xc, why = min(max(s + 1 + int(np.argmax(hit)), e + 1), SQ), "fut_stop"
        else:
            xc, why = SQ, "time"
        xp = P.C[di, xc]
        if not np.isfinite(xp):
            vv = P.C[di, :xc + 1]
            vv = vv[np.isfinite(vv)]
            xp = vv[-1]
        gross = side * (xp - en) * self.units
        cost = S._fut_cost(self.fam, self.tick, self.units, en, xp, side)
        day = pd.Timestamp(P.days[di])
        return dict(entry_i=int(di * W_COLS + e), exit_i=int(di * W_COLS + xc),
                    entry_ts=(day + pd.Timedelta(minutes=D.OPEN_M + e)).to_datetime64(),
                    exit_ts=(day + pd.Timedelta(minutes=D.OPEN_M + xc)).to_datetime64(), side=int(side),
                    fill=float(en), exit=float(xp), why=why, stale=False, gross=float(gross), cost=float(cost),
                    net=float(gross - cost))

    def run(self, ctx, Pv, days):
        """One at a time per day (s >= the previous exit column), at most 3 a day (lib6.nonoverlap)."""
        P = self.panel(ctx)
        out, last_day, busy, cnt = [], None, -1, 0
        for s in sorted(self.signals(ctx, Pv, days), key=lambda g: g["j"]):
            if s["di"] != last_day:
                last_day, busy, cnt = s["di"], -1, 0
            if s["s"] < busy or cnt >= self.max_per_day:
                continue
            t = self.simulate(ctx, s, Pv)
            if t is None:
                continue
            t.update(rule=self.rid, sig_ts=(pd.Timestamp(P.days[s["di"]]) + pd.Timedelta(minutes=D.OPEN_M + s["s"])).to_datetime64(),
                     sig_j=int(s["j"]), stopF=s["stopF"], sigF=s["sigF"])
            out.append(t)
            busy = t["exit_i"] - s["di"] * W_COLS
            cnt += 1
        return out


class MA5u(MA5):
    """MA5 as pre-registered (R2 C4: one position at a time, NO daily cap). Added 15 Oct 2026 after the final lock:
    MA5 itself stays as run (first signal a day only) so mcx_design.json / mcx_final.json still reproduce."""
    rid = "MA5u"
    max_per_day = 10 ** 9


MINUTE_RULES = [MA1, MA1b, MA1c, MA2, MA3Fut, MA3Opt, MA4Crude, MA4NG, MA4Gold, MA5, MA6, MA7, MA8, MA9]
