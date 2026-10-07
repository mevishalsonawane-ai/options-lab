"""Catalog LV-05 (N-day Donchian breakout, positional) as a MULTI-DAY option-buying trade.

The obuy engine (engine.py) squares every position off the same day, so ga_levels.py could only test LV-05's first-day
leg. This module walks a position forward day by day on real option minute bars instead. engine.py is not touched.

Catalog rules (research/option_buying_catalog.json, LV-05) and how they are implemented here:
  instrument   NIFTY and BANKNIFTY (one book each, one position at a time per book), ATM or 1-ITM.
  expiry       'M5': the nearest MONTHLY (the catalog's choice). 'W1': the nearest expiry with >= 2 sessions left -
               the weekly where Dhan has one, else the monthly (Dhan's data has no next-week / next-month contract).
  entry        ga_levels.lv05_signals(mode='nextday'), unchanged: yesterday's daily close above the highest high of the
               n days before it -> buy a call at today's 09:16 open (signal = the 09:15 bar); below the lowest low ->
               a put. Optional VIX filter: yesterday's India VIX below its 60-day median.
  stop/trail   the opposite N/2-day channel, trailed daily: a long is closed when the index trades below the lowest
               low of the xlen (= n // 2) sessions before today (decision on the 1-minute bar, filled at the next
               minute's open, as engine.py's index stop). On the entry day the level is dropped when the index is
               already through it (as ga_levels). trigger='close': the daily close beyond the level instead, filled
               the next morning at 09:16.
  time exit    'roll or exit 5 sessions before expiry'. M5: exit at 15:15 five sessions before the monthly expiry and
               do not enter with 5 or fewer sessions left (the next monthly is not in the data, so no roll).
               W1: at 15:15 one session before the contract's expiry, roll into the nearest contract with >= 2
               sessions left (the monthly, when the weekly expires first); when none is in the data (the weekly IS
               the monthly, or BANKNIFTY's monthly-only period), stay flat for the expiry day and re-enter the new
               nearest contract at 09:16 the next session, if the channel stop was not hit meanwhile.
  premium stop none / -30% / -50% of each leg's fill, a resting order on the option bar (filled at the open when the
               option gaps through it, -10 bps). Optional max hold in sessions (exit 15:15).
Fills / charges: obuy's 'app' fills (+-5 bps, stops -10 bps) and SandboxCosts; lot as of the entry (or roll) date.

Prices: the held contract's real minute bars. Dhan's rolling series only covers ATM+-10 strikes, so a contract that
moves more than ~2-3% into (or out of) the money leaves the data. While it is out of that window (or has no bar all
day) it is priced by Black-Scholes from the index minute and the IV of the edge strike on that side (OTM put IV below
the window, OTM call IV above it; r = 6.5%), as research/swing_deep.py did. Every such leg-day is flagged.
Inside the window a minute without a bar is just illiquid: fills go to the next real bar, as in engine.py.

Futures comparison: every option position is mirrored by 1 lot of index futures over the same legs (spot + 5.5% a year
carry paid by longs, a futures roll charge per monthly expiry inside the leg, swing_deep's futures costs and 0.02%
slippage), and 'pure' futures positions run the same signal + channel exits without any expiry rule.
"""
from __future__ import annotations

import math
import zlib
from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd
from scipy.special import ndtr

from .. import config as C
from ..costs import Costs, Fills, floor_tick
from ..data import expiry_from_chain
from .ga_levels import lv05_signals

SQ_COL = 15 * 60 + 15 - C.OPEN_M           # 15:15, the time-exit / roll minute (column 360)
ENTRY_COL = 1                              # 09:16: daily signals are acted on at the open after the 09:15 bar
R_BS = 0.065
CARRY = 0.055                              # futures carry vs spot, paid by longs (swing_deep.py)
FUT_SLIP = 0.0002
FILLS, COSTS = Fills(), Costs()
BIG = 10 ** 6


# ------------------------------------------------------------------------------------------------ grid
@dataclass(frozen=True)
class Var:
    n: int                     # entry channel (days)
    vix: bool                  # VIX < 60-day median filter
    money: int                 # 0 ATM, 1 ITM1
    mode: str                  # 'M5' monthly, exit 5 sessions before expiry | 'W1' nearest with >= 2 left, roll at T-1
    pstop: float | None        # premium stop fraction
    xlen: int | None = None    # exit channel length (default n // 2)
    trigger: str = "touch"     # 'touch' (intraday) | 'close' (daily close, out next 09:16)
    max_hold: int | None = None  # sessions (entry day = 1), exit 15:15

    @property
    def xl(self):
        return self.xlen if self.xlen else max(self.n // 2, 1)

    def vid(self):
        s = f"n{self.n}|{'vix' if self.vix else 'all'}|{'ITM1' if self.money else 'ATM'}|{self.mode}|" \
            f"ps{int(self.pstop * 100) if self.pstop else 0}"
        if self.xlen:
            s += f"|x{self.xlen}"
        if self.trigger != "touch":
            s += f"|{self.trigger}"
        if self.max_hold:
            s += f"|mh{self.max_hold}"
        return s


def grid():
    """The declared grid: 96 main variants + 4 one-at-a-time sensitivities around the GA's walk-forward pick
    (N = 7, VIX filter, ITM1 monthly) = 100. Declared before any result was seen; never pruned."""
    main = [Var(n, v, m, md, ps) for n in (7, 10, 20, 55) for v in (False, True) for m in (0, 1) for md in ("M5", "W1")
            for ps in (None, 0.3, 0.5)]
    base = dict(n=7, vix=True, money=1, mode="M5", pstop=None)
    extra = [Var(**base, xlen=7), Var(**base, xlen=2), Var(**base, max_hold=5), Var(**base, trigger="close")]
    return main + extra


MODES = {"M5": dict(series="month", exit_before=5, roll=False),
         "W1": dict(series="nearest", exit_before=1, roll=True),
         "M0": dict(series="month", exit_before=None, roll=False),      # parity with the same-day engine only
         "FUT": dict(series="fut", exit_before=None, roll=False)}


# ------------------------------------------------------------------------------------------------ helpers
def buy_fill(p):
    return float(FILLS.buy(np.array([round(float(p), 2)]))[0])


def sell_fill(p, stop=False):
    return float(FILLS.sell(np.array([round(float(p), 2)]), stop=stop)[0])


def charge(buy, p, qty):
    return float(COSTS.charge(buy, np.array([p]), np.array([qty]))[0])


def fut_costs(n_in, n_out, direction, stop_exit=False):
    """research/swing_deep.py fut_costs (Rs per lot round trip incl. 0.02% slippage a side, stops x2)."""
    buy, sell = (n_in, n_out) if direction > 0 else (n_out, n_in)
    exch = 0.0000173 * (n_in + n_out)
    sebi = 1e-6 * (n_in + n_out)
    return (40.0 + exch + sebi + 0.0002 * sell + 0.00002 * buy + 0.18 * (40.0 + exch + sebi)
            + FUT_SLIP * n_in + FUT_SLIP * n_out * (2 if stop_exit else 1))


def bs(S, K, T, vol, right, r=R_BS):
    S, T, vol = np.asarray(S, float), np.maximum(np.asarray(T, float), 1e-6), np.clip(np.asarray(vol, float), 0.01, 3.0)
    sq = vol * np.sqrt(T)
    d1 = (np.log(S / K) + (r + 0.5 * vol * vol) * T) / sq
    d2 = d1 - sq
    if right == 0:
        return S * ndtr(d1) - K * np.exp(-r * T) * ndtr(d2)
    return K * np.exp(-r * T) * ndtr(-d2) - S * ndtr(-d1)


def _ffill(a):
    a = np.asarray(a, float).copy()
    ok = np.isfinite(a)
    if not ok.any():
        return a
    idx = np.where(ok, np.arange(len(a)), 0)
    np.maximum.accumulate(idx, out=idx)
    a = a[idx]
    first = int(np.argmax(ok))
    a[:first] = a[first]
    return a


# ------------------------------------------------------------------------------------------------ per-underlying cache
class Und:
    """Everything one underlying's simulations need, in memory: index minutes, daily channels, lots, expiry calendar and
    the MONTH / WEEK option chains of every day (open, low, close float32; per-minute window edges and edge IVs)."""

    def __init__(self, mk, und, log=print, series=("MONTH", "WEEK")):
        self.und = und
        ix = mk.index(und)
        self.ix = ix
        self.days = list(ix.days)
        self.n = len(self.days)
        self.pos = {d: i for i, d in enumerate(self.days)}
        M = ix.mat()
        self.io, self.ih, self.il, self.ic = (M[k] for k in ("o", "h", "l", "c"))
        self.icf = np.vstack([_ffill(r) if np.isfinite(r).any() else r for r in self.ic])   # ffilled closes for BS
        dl = ix.daily()
        self.dl = dl
        self.lot = np.array([ix.lot(d, "data") for d in self.days])
        self.exp = np.array([bool(ix.d[d]["exp"]) for d in self.days])
        self.step = C.STEP[und]
        self._chan = {}
        # chains
        opts = mk.options(und)
        self.ch = {}
        has_week = np.zeros(self.n, bool)
        for i, d in enumerate(self.days):
            for ser in series:
                ch = opts.chain(d, "week" if ser == "WEEK" else "month")
                if ch is None or ch.series != ser:
                    continue
                self.ch[(ser, i)] = self._pack(ch)
                if ser == "WEEK":
                    has_week[i] = True
            if i % 250 == 0:
                log(f"  {und} chains {i}/{self.n}")
        mk.release(und)
        self.has_week = has_week
        # expiry calendar: weekly = the exp-flagged days while a weekly series exists; monthly = the last exp-flagged
        # day of each calendar month (the near series' expiry flag; checked against the MONTH chain below)
        fl = [i for i in range(self.n) if self.exp[i]]
        bym = {}
        for i in fl:
            d = self.days[i]
            bym[(d.year, d.month)] = i
        self.mexp_days = sorted(bym.values())
        self.mexp = np.full(self.n, -1)
        self.wexp = np.full(self.n, -1)
        j = 0
        for i in range(self.n):
            while j < len(self.mexp_days) and self.mexp_days[j] < i:
                j += 1
            self.mexp[i] = self.mexp_days[j] if j < len(self.mexp_days) else -1
        k = 0
        for i in range(self.n):
            while k < len(fl) and fl[k] < i:
                k += 1
            self.wexp[i] = fl[k] if (k < len(fl) and has_week[i]) else -1
        # check: the MONTH chain must look like an expiry (CE + PE < 0.75 step at the close) on each monthly expiry
        self.mexp_check = []
        for i in self.mexp_days:
            ch = opts.chain(self.days[i], "month")
            ok = ch is not None and ch.series == "MONTH" and expiry_from_chain(ch, self.step)
            self.mexp_check.append((self.days[i], bool(ok)))
        mk.release(und)

    @staticmethod
    def _pack(ch):
        K = ch.K.astype(np.int64)
        O = np.stack([ch.o["C"], ch.o["P"]]).astype(np.float32)
        L = np.stack([ch.l["C"], ch.l["P"]]).astype(np.float32)
        Cl = np.stack([ch.c["C"], ch.c["P"]]).astype(np.float32)
        anyb = ~np.isnan(ch.c["C"]) | ~np.isnan(ch.c["P"])                 # (nk, W)
        has = anyb.any(axis=0)
        lo_i = np.argmax(anyb, axis=0)
        hi_i = len(K) - 1 - np.argmax(anyb[::-1], axis=0)
        kmin = _ffill(np.where(has, K[lo_i], np.nan))
        kmax = _ffill(np.where(has, K[hi_i], np.nan))
        ivs = []
        for r, edge in (("P", 0), ("C", -1)):               # OTM put IV at the low edge, OTM call IV at the high edge
            iv = np.where(ch.iv[r] > 0.5, ch.iv[r], np.nan)
            ok = np.isfinite(iv)
            if edge == 0:
                ii = np.argmax(ok, axis=0)
            else:
                ii = len(K) - 1 - np.argmax(ok[::-1], axis=0)
            v = np.where(ok.any(axis=0), iv[ii, np.arange(C.W)], np.nan)
            ivs.append(_ffill(v))
        return dict(K=K, O=O, L=L, C=Cl, kmin=kmin.astype(np.float32), kmax=kmax.astype(np.float32),
                    ivlo=ivs[0].astype(np.float32), ivhi=ivs[1].astype(np.float32))

    # channels: level for day i = min low / max high of the xl sessions BEFORE day i (known at the open of day i)
    def chan(self, xl):
        if xl not in self._chan:
            lo = self.dl.low.rolling(xl).min().shift(1).values
            hi = self.dl.high.rolling(xl).max().shift(1).values
            self._chan[xl] = (lo, hi)
        return self._chan[xl]

    def stoe(self, i, epos):
        return BIG if epos < 0 else epos - i

    def tyears(self, i, epos, cols):
        """Time to expiry (years, calendar) at minute columns of day i; an unknown expiry (after the data) = +20 days."""
        d = self.days[i]
        e = self.days[epos] if epos >= 0 else date.fromordinal(d.toordinal() + 20)
        mins = (e - d).days * 1440 + (15 * 60 + 30) - (C.OPEN_M + np.asarray(cols))
        return np.maximum(mins, 1) / (365.0 * 1440)


# ------------------------------------------------------------------------------------------------ one contract-day
class LegDay:
    """Effective O / L / C of one contract on one day: real bars; Black-Scholes where the strike is outside the data's
    ATM+-10 window (or has no bar all day); NaN where it is inside the window but did not trade that minute."""
    __slots__ = ("O", "L", "C", "model_any", "model_cols")

    def __init__(self, U: Und, i, ser, epos, K, right, fut_side=0):
        if ser == "FUT":
            self.O, self.L, self.C = U.io[i], (U.il[i] if fut_side > 0 else -U.ih[i]), U.ic[i]
            self.model_any, self.model_cols = False, 0
            return
        ch = U.ch.get((ser, i))
        rows = None
        if ch is not None:
            k = int(np.searchsorted(ch["K"], K))
            if k < len(ch["K"]) and ch["K"][k] == K:
                rows = (ch["O"][right, k].astype(float), ch["L"][right, k].astype(float), ch["C"][right, k].astype(float))
        if rows is not None and np.isfinite(rows[2]).any():
            O, L, Cl = (np.round(r, 2) for r in rows)
            out = (K < ch["kmin"]) | (K > ch["kmax"])
            real = np.isfinite(Cl)
            model = out & ~real
        else:
            O = L = Cl = np.full(C.W, np.nan)
            model = np.ones(C.W, bool)
        if model.any():
            cols = np.nonzero(model)[0]
            S = U.icf[i, cols]
            if ch is not None:
                lo_side = K <= np.nan_to_num(ch["kmin"][cols], nan=-1e18)
                hi_side = K >= np.nan_to_num(ch["kmax"][cols], nan=1e18)
                iv = np.where(lo_side, ch["ivlo"][cols], np.where(hi_side, ch["ivhi"][cols],
                                                                  np.where(K < S, ch["ivlo"][cols], ch["ivhi"][cols])))
                iv = np.where(np.isfinite(iv), iv, np.nanmedian(np.concatenate([ch["ivlo"], ch["ivhi"]])))
            else:
                iv = np.full(len(cols), np.nan)
            iv = np.where(np.isfinite(iv), iv, U.last_iv if hasattr(U, "last_iv") else 15.0)
            p = np.round(bs(S, K, U.tyears(i, epos, cols), iv / 100.0, right), 2)
            p = np.maximum(p, 0.05)
            O, L, Cl = O.copy(), L.copy(), Cl.copy()
            O[cols] = p
            L[cols] = p
            Cl[cols] = p
        self.O, self.L, self.C = O, L, Cl
        self.model_any = bool(model.any())
        self.model_cols = int(model.sum())


def first_fill(ld: LegDay, k):
    """Fill price for an order at column k: the first bar at/after k (real or model), else the day's last close."""
    o = ld.O[k:]
    ok = np.nonzero(np.isfinite(o))[0]
    if len(ok):
        return float(o[ok[0]]), k + int(ok[0])
    c = np.nonzero(np.isfinite(ld.C))[0]
    if len(c):
        return float(ld.C[c[-1]]), int(c[-1])
    return np.nan, k


def day_mark(ld: LegDay):
    c = np.nonzero(np.isfinite(ld.C))[0]
    return float(ld.C[c[-1]]) if len(c) else np.nan


# ------------------------------------------------------------------------------------------------ the position walker
class Sim:
    """Walks positions forward day by day. One instance per underlying; leg-days are memoised."""

    def __init__(self, U: Und, maxmemo=60000):
        self.U = U
        self.memo = {}
        self.maxmemo = maxmemo
        self.legs_model = 0

    def legday(self, i, ser, epos, K, right, fut_side=0):
        key = (i, ser, epos, K, right, fut_side)
        ld = self.memo.get(key)
        if ld is None:
            if len(self.memo) > self.maxmemo:
                self.memo.clear()
            ld = self.memo[key] = LegDay(self.U, i, ser, epos, K, right, fut_side)
        return ld

    def choose(self, i, col, side, money, mode):
        """Contract for an order at column `col` of day i: (ser, epos, K, right) or None."""
        U = self.U
        m = MODES[mode]
        if m["series"] == "fut":
            return ("FUT", -1, 0, 0)
        ref = U.icf[i, max(col - 1, 0)]
        if not np.isfinite(ref):
            return None
        atm = math.floor(ref / U.step + 0.5) * U.step
        K = int(atm - side * money * U.step)
        right = 0 if side > 0 else 1
        cands = []
        if m["series"] == "nearest" and U.has_week[i]:
            cands.append(("WEEK", int(U.wexp[i])))
        cands.append(("MONTH", int(U.mexp[i])))
        need = (m["exit_before"] + 1) if m["exit_before"] is not None else 0
        cands.sort(key=lambda x: U.stoe(i, x[1]))
        for ser, ep in cands:
            if U.stoe(i, ep) >= need and (ser, i) in U.ch:
                ch = U.ch[(ser, i)]
                k = int(np.searchsorted(ch["K"], K))
                if k < len(ch["K"]) and ch["K"][k] == K:
                    return (ser, ep, K, right)
                return None
        return None

    def open_leg(self, i, col, side, money, mode, pstop, max_delay=2):
        c = self.choose(i, col, side, money, mode)
        if c is None:
            return None
        ser, ep, K, right = c
        ld = self.legday(i, ser, ep, K, right, side if ser == "FUT" else 0)
        # entry fill: the contract's real (or model) open within max_delay minutes (engine.py's rule)
        seg = ld.O[col:col + max_delay + 1]
        ok = np.nonzero(np.isfinite(seg))[0]
        if not len(ok):
            return None
        c0 = col + int(ok[0])
        raw = float(ld.O[c0])
        U = self.U
        qty = int(U.lot[i])
        if ser == "FUT":
            e = raw
        else:
            e = buy_fill(raw)
            if e <= 0:
                return None
        trig = np.nan
        if pstop and ser != "FUT":
            t = float(floor_tick(e * (1 - pstop)))
            trig = t if (t >= C.TICK and t < e) else np.nan
        return dict(ser=ser, ep=ep, K=K, right=right, side=side, e=e, raw_e=raw, qty=qty, trig=trig, i0=i, c0=c0,
                    model=ld.model_any, days=[], fut_e=float(U.io[i, c0]) if np.isfinite(U.io[i, c0]) else float(U.icf[i, max(c0 - 1, 0)]))

    def run_position(self, i0, side, v: Var, mode=None, fixed=None, skip_exp_entry=False):
        """Simulate one position from day i0 (entry 09:16). fixed=(hold_sessions, exit_col) replaces the channel /
        max-hold exits (random baseline). Returns a dict or None (no entry)."""
        U = self.U
        mode = mode or v.mode
        mcfg = MODES[mode]
        if skip_exp_entry and U.exp[i0]:
            return None
        leg = self.open_leg(i0, ENTRY_COL, side, v.money, mode, v.pstop)
        if leg is None:
            return None
        lo, hi = U.chan(v.xl)
        legs = [leg]
        mtm = {}                 # day index -> option P&L marked to that day's close (charges booked on their day)
        fmtm = {}                # same for the futures mirror (or the futures position itself)
        on_g = in_g = 0.0        # gross overnight / intraday (options)
        fon = fin = 0.0
        why = None
        x_i = x_c = None
        pend_close_exit = False  # 'close' trigger fired yesterday
        pending = False          # flat between a W1 expiry exit and the re-entry
        i = i0
        cs = leg["c0"]
        prev_mark = leg["e"]
        prev_fmark = leg["fut_e"]
        nmodel = 0
        ndays = 0
        while True:
            if i >= U.n:
                break
            session = i - i0 + 1
            # scheduled exits on day i (decision at col-1, fill at the first bar at/after col)
            sched, sreason = BIG, None
            if fixed is not None:
                if session == fixed[0]:
                    sched, sreason = max(fixed[1], cs), "fixed"
                elif session > fixed[0]:
                    sched, sreason = cs, "fixed"
            elif v.max_hold and session >= v.max_hold:
                sched, sreason = max(SQ_COL, cs), "max_hold"
            if pend_close_exit:
                sched, sreason = ENTRY_COL, "channel_close"
            roll_here = False
            if not pending and leg["ser"] != "FUT" and mcfg["exit_before"] is not None:
                st = U.stoe(i, leg["ep"])
                if st <= mcfg["exit_before"] and SQ_COL < sched:
                    sched, sreason = max(SQ_COL, cs), ("roll" if mcfg["roll"] else "expiry")
                    roll_here = mcfg["roll"]
            # channel level for today
            lvl = np.nan
            if fixed is None:
                lvl = lo[i] if side > 0 else hi[i]
                if i == i0 and np.isfinite(lvl):
                    ref = U.icf[i0, ENTRY_COL - 1]
                    if (ref - lvl) * side <= 0:
                        lvl = np.nan
            ih, il, ic = U.ih[i], U.il[i], U.ic[i]
            # ---- flat between contracts (W1): only the index stop / max hold can end the position
            if pending:
                k_is = BIG
                if v.trigger == "touch" and np.isfinite(lvl):
                    hit = (il < lvl) if side > 0 else (ih > lvl)
                    hit[:0] = False
                    nz = np.nonzero(hit)[0]
                    k_is = int(nz[0]) if len(nz) else BIG
                if k_is < BIG or sched < BIG:
                    why = "channel" if k_is < BIG and k_is < sched else sreason
                    x_i, x_c = i, min(k_is + 1, sched)
                    break
                nl = self.open_leg(i, ENTRY_COL, side, v.money, mode, v.pstop)
                if nl is not None:
                    leg = nl
                    legs.append(leg)
                    pending = False
                    cs = leg["c0"]
                    prev_mark, prev_fmark = leg["e"], leg["fut_e"]
                    mtm[i] = mtm.get(i, 0.0) - charge(True, leg["e"], leg["qty"]) if leg["ser"] != "FUT" else mtm.get(i, 0.0)
                else:
                    if v.trigger == "close" and np.isfinite(lvl):
                        cl = ic[np.isfinite(ic)]
                        if len(cl) and (cl[-1] - lvl) * side < 0:
                            pend_close_exit = True
                    i += 1
                    continue
            if i == leg["i0"] and leg["ser"] != "FUT" and i not in mtm:
                mtm[i] = -charge(True, leg["e"], leg["qty"])
            ld = self.legday(i, leg["ser"], leg["ep"], leg["K"], leg["right"], side if leg["ser"] == "FUT" else 0)
            if ld.model_any:
                nmodel += 1
            ndays += 1
            # day's first price (overnight part)
            if i != leg["i0"]:
                fo, _ = first_fill(ld, 0)
                on_g += (fo - prev_mark) * leg["qty"]
                fio = U.io[i][np.isfinite(U.io[i])]
                fo_i = float(fio[0]) if len(fio) else prev_fmark
                fon += (fo_i - prev_fmark) * leg["qty"] * side
                mtm[i] = mtm.get(i, 0.0) + (fo - prev_mark) * leg["qty"] * (side if leg["ser"] == "FUT" else 1)
                fmtm[i] = fmtm.get(i, 0.0) + (fo_i - prev_fmark) * leg["qty"] * side
                prev_mark, prev_fmark = fo, fo_i
            # ---- events within the day
            cols = np.arange(C.W)
            after = cols >= cs
            kb = BIG
            trig = leg["trig"]
            if np.isfinite(trig):
                hb = after & np.isfinite(ld.L) & (ld.L <= trig) & (cols < sched)
                nz = np.nonzero(hb)[0]
                kb = int(nz[0]) if len(nz) else BIG
            k_is = BIG
            if v.trigger == "touch" and np.isfinite(lvl):
                hit = after & (cols <= C.W - 2) & ((il < lvl) if side > 0 else (ih > lvl))
                nz = np.nonzero(hit)[0]
                k_is = int(nz[0]) if len(nz) else BIG
            km = min(k_is, sched - 1 if sched < BIG else BIG)
            exit_now = None
            if kb < BIG and kb <= km:
                raw = min(trig, float(ld.O[kb])) if np.isfinite(ld.O[kb]) else trig
                x = sell_fill(raw, stop=True)
                xc, exit_now = kb, "pstop"
            elif km < BIG:
                fill_col = km + 1
                raw, xc = first_fill(ld, fill_col)
                if leg["ser"] == "FUT":
                    x = raw
                else:
                    x = sell_fill(raw)
                exit_now = "channel" if (k_is <= km and k_is < BIG and (sched == BIG or k_is < sched - 1)) else sreason
            if exit_now is not None:
                # option P&L of the day up to the exit
                in_g += (x - prev_mark) * leg["qty"]
                if leg["ser"] == "FUT":
                    pnl_day = (x - prev_mark) * leg["qty"] * side
                else:
                    pnl_day = (x - prev_mark) * leg["qty"] - charge(False, x, leg["qty"])
                mtm[i] = mtm.get(i, 0.0) + pnl_day
                fx = U.io[i, xc] if np.isfinite(U.io[i, xc]) else U.icf[i, max(xc - 1, 0)]
                fin += (fx - prev_fmark) * leg["qty"] * side
                fmtm[i] = fmtm.get(i, 0.0) + (fx - prev_fmark) * leg["qty"] * side
                leg.update(x=x, xi=i, xc=xc, why=exit_now, fut_x=float(fx))
                leg["days"].append(i)
                if exit_now == "roll":
                    nl = self.open_leg(i, xc, side, v.money, mode, v.pstop)
                    if nl is not None:
                        leg = nl
                        legs.append(leg)
                        mtm[i] = mtm.get(i, 0.0) - charge(True, leg["e"], leg["qty"])
                        cs = leg["c0"]
                        prev_mark, prev_fmark = leg["e"], leg["fut_e"]
                        # the rest of the roll day for the new leg
                        ld2 = self.legday(i, leg["ser"], leg["ep"], leg["K"], leg["right"])
                        sub = self._finish_day(ld2, leg, i, cs, lvl, side, v, il, ih)
                        if sub is not None:      # stopped out again on the roll day
                            x2, xc2, why2 = sub
                            in_g += (x2 - prev_mark) * leg["qty"]
                            mtm[i] += (x2 - prev_mark) * leg["qty"] - charge(False, x2, leg["qty"])
                            fx2 = U.io[i, xc2] if np.isfinite(U.io[i, xc2]) else U.icf[i, max(xc2 - 1, 0)]
                            fin += (fx2 - prev_fmark) * leg["qty"] * side
                            fmtm[i] = fmtm.get(i, 0.0) + (fx2 - prev_fmark) * leg["qty"] * side
                            leg.update(x=x2, xi=i, xc=xc2, why=why2, fut_x=float(fx2))
                            leg["days"].append(i)
                            why, x_i, x_c = why2, i, xc2
                            break
                        mk_ = day_mark(ld2)
                        in_g += (mk_ - prev_mark) * leg["qty"]
                        mtm[i] += (mk_ - prev_mark) * leg["qty"]
                        cl = ic[np.isfinite(ic)]
                        fcl = float(cl[-1]) if len(cl) else prev_fmark
                        fin += (fcl - prev_fmark) * leg["qty"] * side
                        fmtm[i] = fmtm.get(i, 0.0) + (fcl - prev_fmark) * leg["qty"] * side
                        prev_mark, prev_fmark = mk_, fcl
                        leg["days"].append(i)
                        if v.trigger == "close" and np.isfinite(lvl) and len(cl) and (cl[-1] - lvl) * side < 0:
                            pend_close_exit = True
                        i += 1
                        cs = 0
                        continue
                    # nothing to roll into today: flat until a contract is available again
                    pending = True
                    if v.trigger == "close" and np.isfinite(lvl):
                        cl = ic[np.isfinite(ic)]
                        if len(cl) and (cl[-1] - lvl) * side < 0:
                            pend_close_exit = True
                    i += 1
                    continue
                why, x_i, x_c = exit_now, i, xc
                break
            # ---- no exit today: mark to the close
            mk_ = day_mark(ld)
            if not np.isfinite(mk_):
                mk_ = prev_mark
            in_g += (mk_ - prev_mark) * leg["qty"]
            mtm[i] = mtm.get(i, 0.0) + ((mk_ - prev_mark) * leg["qty"] * (side if leg["ser"] == "FUT" else 1))
            cl = ic[np.isfinite(ic)]
            fcl = float(cl[-1]) if len(cl) else prev_fmark
            fin += (fcl - prev_fmark) * leg["qty"] * side
            fmtm[i] = fmtm.get(i, 0.0) + (fcl - prev_fmark) * leg["qty"] * side
            prev_mark, prev_fmark = mk_, fcl
            leg["days"].append(i)
            if v.trigger == "close" and fixed is None and np.isfinite(lvl) and len(cl) and (cl[-1] - lvl) * side < 0:
                pend_close_exit = True
            i += 1
            cs = 0
        if why is None:                     # end of data: close at the last mark
            why = "end_of_data"
            x_i = U.n - 1
            x_c = C.W - 1
            if not pending:
                x = sell_fill(prev_mark) if leg["ser"] != "FUT" else prev_mark
                in_g += (x - prev_mark) * leg["qty"]
                mtm[x_i] = mtm.get(x_i, 0.0) + (x - prev_mark) * leg["qty"] * (side if leg["ser"] == "FUT" else 1) - (
                    charge(False, x, leg["qty"]) if leg["ser"] != "FUT" else 0.0)
                leg.update(x=x, xi=x_i, xc=x_c, why=why, fut_x=prev_fmark)
        return self._book(legs, mtm, fmtm, on_g, in_g, fon, fin, why, x_i, x_c, i0, side, v, nmodel, ndays)

    def _finish_day(self, ld, leg, i, cs, lvl, side, v, il, ih):
        """Exits of a freshly rolled leg later on the roll day (premium stop / index touch). (x, col, why) or None."""
        cols = np.arange(C.W)
        after = cols >= cs
        kb = BIG
        if np.isfinite(leg["trig"]):
            nz = np.nonzero(after & np.isfinite(ld.L) & (ld.L <= leg["trig"]))[0]
            kb = int(nz[0]) if len(nz) else BIG
        k_is = BIG
        if v.trigger == "touch" and np.isfinite(lvl):
            nz = np.nonzero(after & (cols <= C.W - 2) & ((il < lvl) if side > 0 else (ih > lvl)))[0]
            k_is = int(nz[0]) if len(nz) else BIG
        if kb < BIG and kb <= k_is:
            raw = min(leg["trig"], float(ld.O[kb])) if np.isfinite(ld.O[kb]) else leg["trig"]
            return sell_fill(raw, stop=True), kb, "pstop"
        if k_is < BIG:
            raw, xc = first_fill(ld, k_is + 1)
            return sell_fill(raw), xc, "channel"
        return None

    def _book(self, legs, mtm, fmtm, on_g, in_g, fon, fin, why, x_i, x_c, i0, side, v, nmodel, ndays):
        U = self.U
        legs = [l for l in legs if "x" in l]
        if not legs:
            return None
        fut = legs[0]["ser"] == "FUT"
        gross = sum((l["x"] - l["e"]) * l["qty"] * (l["side"] if fut else 1) for l in legs)
        if fut:
            chg = 0.0
            for l in legs:
                stop = l["why"] == "channel"
                c = fut_costs(l["e"] * l["qty"], l["x"] * l["qty"], side, stop)
                d0, d1 = U.days[l["i0"]], U.days[l["xi"]]
                rolls = sum(1 for j in U.mexp_days if l["i0"] <= j < l["xi"])
                c += rolls * fut_costs(l["x"] * l["qty"], l["x"] * l["qty"], side)
                c += CARRY * l["e"] * l["qty"] * (d1 - d0).days / 365 * side
                chg += c
                mtm[l["xi"]] = mtm.get(l["xi"], 0.0) - c
        else:
            chg = sum(charge(True, l["e"], l["qty"]) + charge(False, l["x"], l["qty"]) for l in legs)
        net = gross - chg
        # futures mirror of the option legs (same timestamps): spot + carry + futures costs, 1 lot
        fgross = fchg = 0.0
        for l in legs:
            fe, fx = l["fut_e"], l.get("fut_x", l["fut_e"])
            fgross += (fx - fe) * l["qty"] * side
            c = fut_costs(fe * l["qty"], fx * l["qty"], side, l["why"] == "channel")
            c += sum(1 for j in U.mexp_days if l["i0"] <= j < l["xi"]) * fut_costs(fx * l["qty"], fx * l["qty"], side)
            c += CARRY * fe * l["qty"] * (U.days[l["xi"]] - U.days[l["i0"]]).days / 365 * side
            fchg += c
            fmtm[l["xi"]] = fmtm.get(l["xi"], 0.0) - c
        l0 = legs[0]
        risk = ((l0["e"] - l0["trig"]) if np.isfinite(l0["trig"]) else l0["e"]) * l0["qty"] + (
            0.0 if fut else charge(True, l0["e"], l0["qty"]))
        if fut:
            risk = abs(l0["e"] - (U.chan(v.xl)[0][i0] if side > 0 else U.chan(v.xl)[1][i0])) * l0["qty"] \
                if np.isfinite(U.chan(v.xl)[0][i0]) else l0["e"] * 0.03 * l0["qty"]
        return dict(und=U.und, day=U.days[i0], i0=i0, side=side, xday=U.days[x_i], xi=x_i, xcol=int(x_c),
                    sessions=int(x_i - i0 + 1), why=why, n_legs=len(legs), strike=l0["K"], ser=l0["ser"],
                    lot=l0["qty"], qty=l0["qty"], entry=l0["e"], gross=gross, charges=chg, net=net,
                    overnight=on_g if not fut else fon, intraday=in_g if not fut else fin, risk_rs=risk,
                    model_days=nmodel, days_held=ndays, mtm=mtm, fut_net=fgross - fchg, fut_gross=fgross,
                    fut_overnight=fon, fut_intraday=fin, fmtm=fmtm, year=U.days[i0].year)


# ------------------------------------------------------------------------------------------------ variants
def signals(mk, v: Var, und):
    s = lv05_signals(mk, n=v.n, vix=v.vix, mode="nextday", unds=(und,))
    return s


def run_variant(sim: Sim, sig: pd.DataFrame, v: Var, mode=None, skip_exp_entry=False, sameday=False):
    """One position at a time per book: a signal is taken only when the book is flat at its 09:16 entry."""
    U = sim.U
    out = []
    busy_until = (-1, -1)
    if sameday:
        v = Var(v.n, v.vix, v.money, v.mode, v.pstop, v.xlen, v.trigger, max_hold=1)
    for r in sig.sort_values("day").itertuples(index=False):
        i = U.pos.get(r.day)
        if i is None:
            continue
        if (i, ENTRY_COL) < (busy_until[0], busy_until[1] + 1):
            continue
        p = sim.run_position(i, int(r.side), v, mode=mode, skip_exp_entry=skip_exp_entry)
        if p is None:
            continue
        out.append(p)
        busy_until = (p["xi"], p["xcol"])
    return out


def random_pool(sim: Sim, trades, v: Var, k=20, seed=11, mode=None):
    """Baseline B: for each real position, k alternatives entered at 09:16 on a random session of the same calendar
    year (any day the contract rule allows), coin-flip side, the same strike / expiry rule and premium stop, held the
    SAME number of sessions and closed at the same minute as the real exit (rolls / expiry exits as the rule says).
    Baseline A: the same day and hold with the opposite side (with the real trade, a coin flip of the side)."""
    U = sim.U
    years = np.array([d.year for d in U.days])
    rowsB, rowsA = [], []
    for t in trades:
        rng = np.random.default_rng(zlib.crc32(repr((seed, U.und, t["i0"], t["sessions"], t["xcol"])).encode()))
        cand = np.nonzero(years == U.days[t["i0"]].year)[0]
        fixed = (t["sessions"], max(t["xcol"], ENTRY_COL))
        got = tries = 0
        while got < k and tries < 6 * k:
            tries += 1
            j = int(rng.choice(cand))
            sd = int(rng.choice((1, -1)))
            p = sim.run_position(j, sd, v, mode=mode, fixed=fixed)
            if p is None:
                continue
            rowsB.append(dict(parent=t["key"], net=p["net"], year=t["year"], fut_net=p["fut_net"]))
            got += 1
        p = sim.run_position(t["i0"], -t["side"], v, mode=mode, fixed=fixed)
        if p is not None:
            rowsA.append(dict(parent=t["key"], net=p["net"], year=t["year"], fut_net=p["fut_net"]))
        rowsA.append(dict(parent=t["key"], net=t["net"], year=t["year"], fut_net=t["fut_net"]))
    return pd.DataFrame(rowsB), pd.DataFrame(rowsA)
