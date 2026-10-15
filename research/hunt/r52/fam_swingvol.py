"""R52 family 'swingvol': swing (multi-day) and long-volatility index-option rules, re-tested on Nov 2024 onwards.

Written 15 Oct 2026 for R52 (PREREG.md, FAMILY_BRIEF.md). Signals only: pricing, fills, charges, twins and gates are
the harness's (r52lab.py). Every signal is read on the UNDERLYING index (or is a pure time / event-calendar rule); the
option is then bought by the strike rule. Multi-day holds use exit_day + exit_min (research/obuy/multiday.py).

Lake limits that shape every rule here (said once):
  * Option series: the lake holds only the nearest contract of each series (WEEK = nearest weekly, else monthly; MONTH =
    nearest monthly). On an expiry day the 'near' chain is the EXPIRING contract. The SWING / LONG_VOL / BOTH_SIDES
    studies bought "the nearest expiry strictly after the day", so on an expiry day they held the NEXT contract, which
    the lake does not have: those expiry-day entries are skipped here (labelled per rule).
  * No roll: a hold that would run past its contract's expiry is closed on the expiry day at 15:05 (CLAUDE.md expiry
    square-off) instead of being rolled. SWING_EXITS.md's own option engine did the same (no roll).
  * One position at a time is enforced inside the signal functions for multi-day holds (obuy positions() only blocks
    within a day), following each original's own blocking rule.

Keys covered (original setting first; full grids in SPECS):
  SWING-3       BANKNIFTY 5-day reversal decided on the 15:14 close (down over 5 days -> ATM CE, up -> PE), bought
                15:15, held 1 day to 15:15 (swing.py `signals`, `evaluate`, `option_trade`).
  SWING-4       BANKNIFTY gap-down day recovering at 15:15 -> ATM CE (mirror PE), 3-day hold (swing.py).
  SWING-5       BANKNIFTY big daily candle, 40% pullback next day -> option in the candle's way, 3-day hold (swing.py).
  SWING_DEEP-3  NIFTY + BANKNIFTY: close > 200DMA and RSI(2) < 10 -> long (< 200DMA and > 90 -> short); monthly ATM
                bought 09:20 next morning, out 09:20 after 10 sessions (swing_deep.py `prep`, `index_signals`,
                `run_index` in 'close' mode, as its options section `close_mode_trades`).
  SWING_DEEP-6  NIFTY + BANKNIFTY NR7 / inside day -> next-day stop entry both sides, 20-session hold. ORIGINAL TRADED
                FUTURES; the lake has no futures, so this is the OPTION VERSION (monthly ATM, the option leg the same
                study used) and is labelled so. The entry side and minute are read from the 1-minute index bars.
  SWING_DEEP-8  NIFTY + BANKNIFTY 1-day return < -2 x 20-day SD -> long; monthly ATM 09:20 next morning, 10 sessions
                (swing_deep.py). Merged SWING_EXITS-3 (weekly/near ATM call, app exits -30/+60 pts + ladder or
                -30%/+60% + ladder, max 5 or 3 sessions) is in the grid.
  LV-2          BANKNIFTY 09:20 ATM straddle held to 15:10, only when straddle cost / mean |15:10 close - 09:20 open| of
                the last 10 days is in (4.44, 6.25] (cost_vs_oc10 middle tercile, long_vol.py `day_rows`, `report`).
  LV-4          BANKNIFTY ATM straddle bought 15:15, sold 09:20 next session, same contract (long_vol.py `day_rows`).
  LV-5          BANKNIFTY ATM straddle bought 15:15 one session before an RBI / Budget / election-result session, sold
                on the event session at 15:10 (long_vol2.py `events`).
  BS-1          Boss's both-sides rule, owner's set: BANKNIFTY ATM CE and ATM PE at 09:20, each side stop -3%, targets
                +5 / +8 / +10% one lot each, rest at 15:10 (both_sides.py `side`, `trade`; BOTH_SIDES.md owner's
                table). R52 measures ONE lot per trade: the 3-lot scale-out is exactly the sum of three 1-lot positions
                per side (lot j = the same -3% stop, target T_j), so orig = lot 1 (+5%), g01 = lot 2 (+8%),
                g02 = lot 3 (+10%); the full rule = orig + g01 + g02 on the same days. Break-even after the first stop
                links the two sides and cannot be expressed: not tested.
  R13-1         Boss's Majority Rule on weekly NIFTY: MR(14) (close-to-close) touches <= 28.57, wait up to 8 weeks for
                the first green weekly candle, buy the next week's first open; +1,000 / -500 NIFTY points, 13-week cap
                (hunt/r13/boss.py `setups`, `sim`, `trades`; study.py `weekly`, `mr`, `touches`, ported because
                study.py reads its deleted cache at import). Original = futures / Black-Scholes 35- and 63-day calls;
                R52 = OPTION VERSION on the nearest MONTHLY ATM call (the lake has no 35/63-day contracts), held to the
                index target / stop or the contract's expiry day 15:05 (no roll). Expect 0-3 events.
  H5-1          Trend-day rule, 1-LOT VERSION (no pyramid adds: the adds cannot be expressed as one held contract):
                at 11:30 side = move since the open, only if the ATM+-5 OI change since the open agrees
                ((put - call OI added) / total >= +0.3 for up, mirror for down); 1-ITM nearest expiry; exit when the
                index falls 0.6 x ATR14 off its best close since entry, or after 60 min if it has not extended
                0.15 x ATR14 (the 'no add in 60 min' stop), else 15:15 (hunt/h5/sim.py `features`, `sim_day`,
                build.py OI features; ported because they read deleted caches). This is HUNT_H5's own "same rule with
                1 lot" row. The frozen rule's expiry-day setting is not recorded (sel.pkl is gone): orig allows
                expiry days, the grid skips them. Its exits are structural (exit_at from the index path).

LEFT (not tested here):
  LV-6   Expiry-day afternoon buy of the EXPIRING ATM straddle (long_vol2.py calls its 2x/3x take-profit form "the
         hero-zero trade"): an expiry-day zero-to-hero rule, owned by the parallel session R50 (CATALOGUE.md scope).
  SI-1   STRAD-INDEX: the size forecast is an ensemble of three models (ridge HAR-X, GBM, MLP) fitted in an anchored
         walk-forward on NIFTY / BANKNIFTY minute history from 2020-21 to Sep 2025, with h18 gamma features and the
         h28 event file. The lake starts in Jan 2024, the study's feature / forecast caches (feat_*.parquet,
         fc.parquet) and scratchpad/hunt/h28/events.csv are gone. Refitting on the ~10 months of lake warm-up would be
         a different model, not the original rule. Testable faithfully only once pre-2024 index minutes are in the lake.

Deviations common to all (said once): fills at the next minute's open after sig_min (the originals used a minute's
open or close with fixed slippage); exits the originals took at a minute's CLOSE are placed at the next minute's open
(e.g. "sold at the 15:10 close" -> 15:11 open); filters that used the entry minute's own option open (LV-2's straddle
cost) use the signal minute's close instead, so no bar after sig_min is read.
"""
from __future__ import annotations

import os
import sys
from datetime import date

import numpy as np
import pandas as pd

_HERE = os.path.dirname(os.path.abspath(__file__))
_RESEARCH = os.path.dirname(os.path.dirname(_HERE))
for _p in (_RESEARCH, _HERE):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from obuy import config as C  # noqa: E402
from obuy.engine import LADDER  # noqa: E402
from obuy.multiday import MD_SQ, expiry_tables, next_on_or_after  # noqa: E402

OPEN_M = C.OPEN_M                     # 09:15
LAST_M = C.LAST_M                     # 15:29
M0914 = 15 * 60 + 14                  # signal minute for "decided / bought at 15:15"
M0919 = 9 * 60 + 19                   # signal minute for "bought at 09:20"
EXP_SQ = 15 * 60 + 5                  # expiry-day square-off (CLAUDE.md)
NB = ("NIFTY", "BANKNIFTY")
BN = ("BANKNIFTY",)
COLS = ["und", "day", "sig_min", "side", "book"]
MDCOLS = COLS + ["exit_day", "exit_min"]


# ------------------------------------------------------------------------------------------------ shared helpers
_EXP = {}


def _expiry(ix, d, series):
    """Expiry day of the contract the lake holds on day d for this series ('month' = nearest monthly, else the nearest
    expiry): the first expiry day on or after d (multiday.contract_expiry, cached per index object)."""
    k = id(ix)
    if k not in _EXP or _EXP[k][0] is not ix:
        _EXP[k] = (ix, expiry_tables(ix))
    wk, mo = _EXP[k][1]
    return next_on_or_after(mo if series == "month" else wk, d)


def _last_valid(a, col):
    x = a[: col + 1]
    ok = np.nonzero(~np.isnan(x))[0]
    return float(x[ok[-1]]) if len(ok) else np.nan


def _first_valid(a):
    ok = np.nonzero(~np.isnan(a))[0]
    return float(a[ok[0]]) if len(ok) else np.nan


def _nanmax(a):
    return float(np.nanmax(a)) if np.isfinite(a).any() else np.nan


def _nanmin(a):
    return float(np.nanmin(a)) if np.isfinite(a).any() else np.nan


def _daily_full(ix):
    """Daily open / high / low / close of each session from the dense minute matrix (NaN for an empty session)."""
    M = ix.mat()
    rows = []
    for i, d in enumerate(ix.days):
        o, h, l, c = M["o"][i], M["h"][i], M["l"][i], M["c"][i]
        rows.append((d, _first_valid(o), _nanmax(h), _nanmin(l), _last_valid(c, C.W - 1)))
    return pd.DataFrame(rows, columns=["day", "open", "high", "low", "close"]).set_index("day")


def _frame(rows, cols):
    return pd.DataFrame(rows, columns=cols) if rows else pd.DataFrame(columns=cols)


def _cap_exit(ix, d, xd, xm, series):
    """Cap a hold at the contract's expiry day (15:05, no roll). None when the contract expires on the entry day."""
    E = _expiry(ix, d, series)
    if E is None or E <= d:
        return None
    if xd > E:
        return E, EXP_SQ
    if xd == E:
        return E, min(xm, EXP_SQ)
    return xd, xm


def _sd():
    """swing_deep.py reads sys.argv[1:3] at import (its data folders); its prep / signal / index-engine functions are
    pure, so it is imported with placeholder arguments."""
    if "swing_deep" in sys.modules:
        return sys.modules["swing_deep"]
    argv = sys.argv
    try:
        sys.argv = [argv[0] if argv else "x", "", ""]
        import swing_deep
    finally:
        sys.argv = argv
    return swing_deep


# ------------------------------------------------------------------------------------------------ SWING (swing.py)
SWING_RULES = {"rev5": "5-day reversal (down over 5 days -> up)",
               "gapdown": "Gap-down day recovering at 15:15 -> up (mirror)",
               "bigpull": "Big daily candle, 40% pullback next day"}
T1515 = 15 * 60 + 15 - OPEN_M         # 360: swing.py's 15:15 column


def _swing_daily(ix):
    """swing.py `daily`: o = day open, h / l = 09:15..15:14, c = the 15:14 close; H / L / C = the full day."""
    M = ix.mat()
    rows = []
    for i, d in enumerate(ix.days):
        o, h, l, c = M["o"][i], M["h"][i], M["l"][i], M["c"][i]
        rows.append(dict(day=d, o=_first_valid(o), h=_nanmax(h[:T1515]), l=_nanmin(l[:T1515]),
                         c=_last_valid(c, T1515 - 1), H=_nanmax(h), L=_nanmin(l), C=_last_valid(c, C.W - 1)))
    return pd.DataFrame(rows, columns=["day", "o", "h", "l", "c", "H", "L", "C"])


def swing_signals(mk, rule="rev5", hold=1, unds=BN):
    """swing.py: decided on the 15:14 close (daily history + today so far), option bought at 15:15, out 15:15 `hold`
    sessions later. One position at a time as swing.evaluate (the next signal may come on day i + hold). The contract is
    the nearest expiry after the day, so a hold is cut at the session before its expiry (swing.option_trade sells at
    the last day the contract is still the 'next expiry'); expiry-day signals (next contract, not in the lake) and the
    session before expiry (a 15-minute trade in the original) are not traded but still block, as in the original."""
    import swing
    out = []
    for u in unds:
        ix = mk.index(u)
        days = list(ix.days)
        if len(days) < 3:
            continue
        D = _swing_daily(ix)
        sig = swing.signals(D)[SWING_RULES[rule]].values
        free = 0
        for i in range(len(days)):
            s = sig[i]
            if s == 0 or i < free or i + 1 >= len(days) or not np.isfinite(D.c.iloc[i]):
                continue
            free = i + hold
            d = days[i]
            if ix.d[d]["exp"]:
                continue
            E = _expiry(ix, d, "near")
            if E is None or E <= d:
                continue
            xi = min(i + hold, len(days) - 1)
            if days[xi] >= E:
                xi = ix.pos[E] - 1
            if xi <= i:
                continue
            out.append(dict(und=u, day=d, sig_min=M0914, side=int(s), book=f"swing_{rule}_{u}",
                            exit_day=days[xi], exit_min=15 * 60 + 15))
    return _frame(out, MDCOLS)


# ------------------------------------------------------------------------------------------------ SWING_DEEP
SD_RULES = {"rsi2": "Pullback: >200DMA & RSI(2)<10", "bigdown": "Mean reversion: buy after big down day"}


def _sd_exit(ex):
    SD = _sd()
    m = {"5d": SD.EXITS["fixed 5d"], "10d": SD.EXITS["fixed 10d"], "20d": SD.EXITS["fixed 20d"],
         "chand": SD.EXITS["2ATR stop + 3ATR chandelier (max 20d)"], "atr10": SD.EXITS["2ATR stop + 10d time stop"],
         "3d": dict(kind="fixed", N=3)}
    return m[ex]


def _sd_frame(ix):
    d = _daily_full(ix)
    d["volume"] = np.nan
    return d


def sdeep_signals(mk, rule="rsi2", ex="10d", series="month", unds=NB):
    """swing_deep.py options section: daily signal on the close; the trade sequence (one at a time, exits) is the
    study's own `run_index(..., 'close')` (stops judged on the daily close, out the next morning); the option is bought
    at 09:20 on the entry day and sold at 09:20 on the exit day (15:15 on the last data day). No roll: a hold past the
    contract's expiry is closed on the expiry day (as SWING_EXITS.md's engine); entries on the contract's own expiry
    day are skipped (SWING_EXITS: 'never buys today's expiry')."""
    SD = _sd()
    exd = _sd_exit(ex)
    out = []
    for u in unds:
        ix = mk.index(u)
        if len(ix.days) < 3:
            continue
        p = SD.prep(_sd_frame(ix))
        sig = SD.index_signals(p)[SD_RULES[rule]]
        for t in SD.run_index(p, sig, exd, "close"):
            ed, xd = t["ed"], t["xd"]
            xm = 15 * 60 + 15 if t["why"] == "end of data" else 9 * 60 + 20
            if xd <= ed:
                continue
            cap = _cap_exit(ix, ed, xd, xm, series)
            if cap is None:
                continue
            out.append(dict(und=u, day=ed, sig_min=M0919, side=int(t["dir"]), book=f"sd_{rule}_{u}",
                            exit_day=cap[0], exit_min=cap[1]))
    return _frame(out, MDCOLS)


def _nr7_exit(H, L, days_hi, days_lo, atr, e, col, dirn, px, a0, exd, n):
    """Exit (day index, clock minute) of an NR7 stop-entry trade entered on day e after minute column col, by
    swing_deep.run_index's futures rules on 1-minute bars: fixed N -> out at the open of day e + N (09:20 here);
    stop kinds -> initial stop px -/+ m x ATR(signal day), intraday on every minute after the entry (out the next
    minute), chandelier trail updated at each day's end from the extreme high / low and that day's ATR."""
    N = exd["N"]
    if exd["kind"] == "fixed":
        x = e + N
        return (x, 9 * 60 + 20) if x < n else (n - 1, 15 * 60 + 15)
    stop = px - dirn * exd.get("m", 2.0) * a0
    ext = px
    for k in range(e, min(e + N, n)):
        c0 = col + 1 if k == e else 0
        seg = (L[k, c0:] <= stop) if dirn > 0 else (H[k, c0:] >= stop)
        hit = np.nonzero(seg)[0]
        if len(hit):
            m = OPEN_M + c0 + int(hit[0]) + 1
            if m > LAST_M:
                if k + 1 < n:
                    return k + 1, OPEN_M + 1
                return k, LAST_M
            return k, m
        if exd.get("trail"):
            if dirn > 0:
                ext = max(ext, days_hi[k])
                stop = max(stop, ext - exd["trail"] * atr[k])
            else:
                ext = min(ext, days_lo[k])
                stop = min(stop, ext + exd["trail"] * atr[k])
    x = e + N
    return (x, 9 * 60 + 20) if x < n else (n - 1, 15 * 60 + 15)


def nr7_signals(mk, ex="20d", series="month", unds=NB):
    """SWING_DEEP-6, option version: an NR7 or inside day (swing_deep.prep `nr7` | `inside`, daily) arms a stop entry
    at its high (long) and low (short) for the next session only; the first 1-minute bar that trades through a level
    is the signal (both in one bar: the level nearer the open, the original's rule); a monthly ATM call / put is bought
    at the next minute's open. Exits as swing_deep.run_index's futures rules (see _nr7_exit). One position at a time:
    a new setup may be armed on the exit day's close (run_index). Minute bars settle which level traded first, so the
    daily-bar 'whipsaw' assumption of the futures study is not needed."""
    SD = _sd()
    exd = _sd_exit(ex)
    out = []
    for u in unds:
        ix = mk.index(u)
        days = list(ix.days)
        n = len(days)
        if n < 3:
            continue
        p = SD.prep(_sd_frame(ix))
        arm = (p.nr7 | p.inside).values
        hi, lo, atr = p.high.values, p.low.values, p.atr.values
        M = ix.mat()
        H, L, O = M["h"], M["l"], M["o"]
        i = 0
        while i < n - 1:
            if not arm[i] or not (np.isfinite(atr[i]) and atr[i] > 0 and np.isfinite(hi[i]) and np.isfinite(lo[i])):
                i += 1
                continue
            e = i + 1
            lb, ls = hi[i], lo[i]
            with np.errstate(invalid="ignore"):
                hb, hs = H[e] >= lb, L[e] <= ls
            ib = np.nonzero(hb)[0]
            is_ = np.nonzero(hs)[0]
            cb = int(ib[0]) if len(ib) else None
            cs = int(is_[0]) if len(is_) else None
            if cb is None and cs is None:
                i = e                      # setup expired unfilled; the entry day's close may arm a new one
                continue
            o0 = _first_valid(O[e])
            if cs is None or (cb is not None and cb < cs):
                dirn, col = 1, cb
            elif cb is None or cs < cb:
                dirn, col = -1, cs
            else:
                dirn, col = (1 if abs(o0 - lb) <= abs(o0 - ls) else -1), cb
            px = max(o0, lb) if dirn > 0 else min(o0, ls)
            sm = OPEN_M + col
            xk, xm = _nr7_exit(H, L, hi, lo, atr, e, col, dirn, px, atr[i], exd, n)
            if sm < 15 * 60 + 14 and (xk > e or xm > sm + 1):
                cap = _cap_exit(ix, days[e], days[xk], xm, series)
                if cap is not None and (cap[0] > days[e] or cap[1] > sm + 1):
                    out.append(dict(und=u, day=days[e], sig_min=sm, side=dirn, book=f"nr7_{u}",
                                    exit_day=cap[0], exit_min=cap[1]))
            i = max(xk, e)                 # flat again on the exit day; its close may arm the next setup
    return _frame(out, MDCOLS)


# ------------------------------------------------------------------------------------------------ LONG_VOL
LV2_CUT = 355                          # long_vol.py CUT (15:10)


def _lv2_feats(ix, opts, i, d, step):
    """long_vol.day_rows features known at 09:19 (from the previous 10 sessions and today's first 5 minutes) and the
    09:19 ATM straddle (close + 2 x 0.5 slippage, as legs_pnl's cost)."""
    M = ix.mat()
    if i < 10:
        return None
    prev = range(i - 10, i)
    rng = [_nanmax(M["h"][j]) - _nanmin(M["l"][j]) for j in prev]
    oc = [abs(M["c"][j][LV2_CUT] - M["o"][j][5]) for j in prev]
    p1c = _last_valid(M["c"][i - 1], C.W - 1)
    H1, L1 = _nanmax(M["h"][i - 1]), _nanmin(M["l"][i - 1])
    o, h, l, c = M["o"][i], M["h"][i], M["l"][i], M["c"][i]
    f = dict(prev_range_pct=rng[-1] / p1c * 100, avg_range10=float(np.nanmean(rng)),
             avg_oc10=float(np.nanmean(oc)) if np.isfinite(oc).any() else np.nan,
             gap_pct=(o[0] - p1c) / p1c * 100, first5_range=_nanmax(h[:5]) - _nanmin(l[:5]))
    P = (H1 + L1 + p1c) / 3
    f["cpr"] = abs(2 * P - (H1 + L1)) / P * 100
    E = _expiry(ix, d, "near")
    f["dte"] = (E - d).days if E is not None else np.nan
    spot = _last_valid(c, 4)
    if not np.isfinite(spot):
        return None
    ch = opts.chain(d, "near")
    if ch is None:
        return None
    k = int(np.floor(spot / step + 0.5) * step)
    j = ch.kpos(k)
    if j < 0:
        return None
    ce, pe = _last_valid(ch.c["C"][j], 4), _last_valid(ch.c["P"][j], 4)
    if not (np.isfinite(ce) and np.isfinite(pe)):
        return None
    cost = ce + pe + 2 * 0.5
    f["implied_move_pct"] = cost / spot * 100
    f["cost_vs_range10"] = cost / f["avg_range10"] if f["avg_range10"] > 0 else np.nan
    f["cost_vs_oc10"] = cost / f["avg_oc10"] if f["avg_oc10"] > 0 else np.nan
    return f


def lv2_signals(mk, feat="cost_vs_oc10", lo=4.44, hi=6.25, unds=BN):
    """LV-2: the 09:20 ATM straddle (bought at the 09:20 open, sold at the 15:10 close) only on days whose pre-entry
    feature is in (lo, hi] - long_vol.report's tercile cells, cut points fixed from its Year B (LONG_VOL.md).
    gap_pct is taken in absolute value (report's '|gap|'). Expiry days are skipped (the study held the next contract)."""
    out = []
    for u in unds:
        ix, opts = mk.index(u), mk.options(u)
        step = C.STEP[u]
        for i, d in enumerate(ix.days):
            if ix.d[d]["exp"]:
                continue
            f = _lv2_feats(ix, opts, i, d, step)
            if f is None:
                continue
            x = f.get(feat, np.nan)
            if feat == "gap_pct":
                x = abs(x)
            if np.isfinite(x) and lo < x <= hi:
                out.append(dict(und=u, day=d, sig_min=M0919, side=0, book=f"lv2_{u}"))
    return _frame(out, COLS)


def lv4_signals(mk, unds=BN):
    """LV-4: the ATM straddle bought at 15:15 (ATM by the 15:14 close) and sold at the 09:20 close next session (09:21
    open here), same contracts. long_vol.day_rows requires the next session to hold the same 'next expiry after the
    day', so neither the expiry day itself nor the night into the expiry day is traded."""
    out = []
    for u in unds:
        ix = mk.index(u)
        days = list(ix.days)
        M = ix.mat()
        for i in range(len(days) - 1):
            d, nd = days[i], days[i + 1]
            if ix.d[d]["exp"] or not np.isfinite(_last_valid(M["c"][i], T1515 - 1)):
                continue
            E = _expiry(ix, d, "near")
            if E is None or E <= nd:
                continue
            out.append(dict(und=u, day=d, sig_min=M0914, side=0, book=f"lv4_{u}", exit_day=nd, exit_min=9 * 60 + 21))
    return _frame(out, MDCOLS)


def lv5_events():
    """long_vol2.EVENTS (RBI, Budget, the general-election result), extended past Feb 2026 with the obuy calendar's
    RBI and Budget dates (gc_common: hand-made, the 2026 RBI dates after February are the scheduled meetings as
    recalled and the least certain). Returns sorted (date, kind)."""
    import long_vol2
    from obuy.strategies import gc_common as G
    ev = {}
    for kind, ds in long_vol2.EVENTS.items():
        for s in ds:
            ev[date.fromisoformat(s)] = kind
    for s in G._RBI:
        ev.setdefault(date.fromisoformat(s), "RBI")
    for s in G._BUDGET:
        ev.setdefault(date.fromisoformat(s), "Budget")
    return sorted(ev.items())


def lv5_signals(mk, back=1, xmin=15 * 60 + 11, unds=BN, events=None):
    """LV-5: ATM straddle bought at 15:15 `back` sessions before an event session, sold on the event session at the
    10:30 or 15:10 close (10:31 / 15:11 open here). Only when the same contracts trade on both days: no expiry from
    the buy day to the event day (long_vol2.events: B['exp'] == E['exp'] with 'exp' = the next expiry after the day)."""
    evs = lv5_events() if events is None else events
    out = []
    for u in unds:
        ix = mk.index(u)
        days = list(ix.days)
        for ev, kind in evs:
            if ev not in ix.pos:
                continue
            i = ix.pos[ev]
            if i - back < 0:
                continue
            B = days[i - back]
            if ix.d[B]["exp"]:
                continue
            E = _expiry(ix, B, "near")
            if E is None or E <= ev:
                continue
            out.append(dict(und=u, day=B, sig_min=M0914, side=0, book=f"lv5_{u}", exit_day=ev, exit_min=xmin))
    return _frame(out, MDCOLS).drop_duplicates(["und", "day"]).reset_index(drop=True)


# ------------------------------------------------------------------------------------------------ BOTH_SIDES (BS-1)
def bs_signals(mk, m0=9 * 60 + 20, unds=BN):
    """BS-1: every session, the ATM call AND the ATM put (ATM by the close of the minute before m0, both_sides.trade),
    bought at m0's open, each side its own book (each side is managed alone: stop / target / 15:10 in Exits)."""
    out = []
    for u in unds:
        ix = mk.index(u)
        M = ix.mat()
        for i, d in enumerate(ix.days):
            if not np.isfinite(_last_valid(M["c"][i], m0 - 1 - OPEN_M)):
                continue
            for side, nm in ((1, "CE"), (-1, "PE")):
                out.append(dict(und=u, day=d, sig_min=m0 - 1, side=side, book=f"bs_{nm}_{u}"))
    return _frame(out, COLS)


# ------------------------------------------------------------------------------------------------ R13 (Majority Rule)
def _weekly(D):
    """hunt/r13/study.py `weekly`: W-FRI bars labelled by their last trading day."""
    g = D.resample("W-FRI")
    w = pd.DataFrame({"open": g["open"].first(), "high": g["high"].max(), "low": g["low"].min(),
                      "close": g["close"].last(),
                      "last_day": g["close"].apply(lambda s: s.index[-1] if len(s) else pd.NaT),
                      "ndays": g["close"].count()})
    w = w[w["ndays"] > 0].copy()
    w.index = pd.DatetimeIndex(w["last_day"])
    return w.drop(columns="last_day")


def _mr(bars, L, mode):
    """study.py `mr`: MR(L) = 100 x up bars among the last L ('cc' close > previous close, 'co' close > open)."""
    up = (bars["close"] > bars["close"].shift(1)) if mode == "cc" else (bars["close"] > bars["open"])
    up = up.astype(float)
    if mode == "cc" and len(up):
        up.iloc[0] = np.nan
    return 100.0 * up.rolling(L).sum() / L


def _touches(m, thr):
    """study.py `touches` (side 'low'): new MR <= thr with the previous week above it."""
    cur = m <= thr + 1e-9
    prev = m.shift(1) > thr + 1e-9
    return cur & prev & m.shift(1).notna()


def _r13_setups(W, mode, reading):
    """boss.py `setups` (+ 'touch' = study.py's plain touch: the touch week itself is the signal week)."""
    m = _mr(W, 14, mode)
    new = _touches(m, 100 * 4 / 14)
    green = (W["close"] > W["open"]).values
    out = []
    for k in np.nonzero(new.values)[0]:
        if reading == "touch":
            out.append(int(k))
        elif reading == "strict":
            if k + 1 < len(W) and green[k + 1]:
                out.append(int(k + 1))
        else:
            for j in range(k + 1, min(k + 9, len(W))):
                if green[j]:
                    out.append(int(j))
                    break
    return out, m


def _r13_sim(dates, O, H, Lo, C_, W, i0, ep, rule, a, b, cap, g, m):
    """boss.py `sim` on daily bars from i0 -> exit day index and whether it was an open (09:15) or close exit.
    The cap is the last trading day of week g + cap (cut at the data's end instead of dropping the trade)."""
    if g + cap < len(W):
        last = int(dates.searchsorted(W.index[g + cap], side="right")) - 1
    else:
        last = len(dates) - 1
    if rule in ("pts", "pct"):
        tgt = ep + a if rule == "pts" else ep * (1 + a)
        stp = ep - b if rule == "pts" else ep * (1 - b)
        for j in range(i0, last + 1):
            if O[j] <= stp or (O[j] >= tgt and j > i0) or Lo[j] <= stp or H[j] >= tgt:
                return j
        return last
    if rule == "mr":
        for kk in range(g + 1, min(g + cap, len(W))):
            if m.iloc[kk] > 50:
                j = int(dates.searchsorted(W.index[kk], side="right"))
                return min(j, len(dates) - 1)
    return last


def r13_signals(mk, mode="cc", reading="wait", rule="pts", a=1000.0, b=500.0, cap=13, series="month",
                unds=("NIFTY",)):
    """R13-1 option version: the signal week (boss.py setups) is closed on its Friday; the option is bought at the
    next session's first minute (signal = the 09:15 bar, fill 09:16 open ~ boss.py's E2 'next week's first open').
    Index target / stop as absolute levels from that session's open (pts: +a / -b points; pct: x(1+a) / x(1-b));
    'time' holds `cap` weeks; 'mr' also exits at the open after a week whose MR(14) > 50. One position at a time on
    the original's own index path (boss.trades: a setup whose entry is not after the previous exit is skipped).
    The option leg is the nearest monthly ATM call, closed at the index exit or its expiry day (no roll)."""
    out = []
    for u in unds:
        ix = mk.index(u)
        days = list(ix.days)
        if len(days) < 20:
            continue
        dl = _daily_full(ix).dropna()
        D = dl.copy()
        D.index = pd.DatetimeIndex(pd.to_datetime(list(dl.index)))
        W = _weekly(D)
        dates = D.index
        O, H, Lo, C_ = (D[k].values for k in ("open", "high", "low", "close"))
        gs, m = _r13_setups(W, mode, reading)
        busy = -1
        for g in gs:
            i0 = int(dates.searchsorted(W.index[g], side="right"))
            if i0 >= len(dates) or i0 <= busy:
                continue
            ep = O[i0]
            j = _r13_sim(dates, O, H, Lo, C_, W, i0, ep, rule, a, b, cap, g, m)
            busy = j
            d0, xd = dates[i0].date(), dates[j].date()
            xm = 15 * 60 + 15
            if rule == "mr" and j > i0:
                # an MR > 50 exit is at that session's open (boss.sim returns O[j]); a time exit at the cap's close
                cap_last = (int(dates.searchsorted(W.index[g + cap], side="right")) - 1) if g + cap < len(W) else len(dates) - 1
                if j != cap_last:
                    xm = 9 * 60 + 16
            row = dict(und=u, day=d0, sig_min=OPEN_M, side=1, book=f"r13_{u}")
            if rule == "pts":
                row.update(idx_target=float(ep + a), idx_stop=float(ep - b))
            elif rule == "pct":
                row.update(idx_target=float(ep * (1 + a)), idx_stop=float(ep * (1 - b)))
            if xd <= d0:
                continue
            capx = _cap_exit(ix, d0, xd, xm, series)
            if capx is None:
                continue
            row.update(exit_day=capx[0], exit_min=capx[1])
            out.append(row)
    return _frame(out, MDCOLS + ["idx_target", "idx_stop"])


# ------------------------------------------------------------------------------------------------ H5 (trend day)
H5_SQ = 360                            # 15:15 column (hunt/h5/sim.py SQ)


def _h5_atr(ix):
    """sim.features: ATR14 = simple mean of the true range over the 14 REAL sessions before the day."""
    dl = ix.daily()
    dl = dl[dl.real].copy()
    tr = np.maximum(dl.high - dl.low, np.maximum(abs(dl.high - dl.close.shift()), abs(dl.low - dl.close.shift())))
    return tr.rolling(14).mean().shift(1).to_dict()


def _h5_oi_imb(ch, spot, t, step):
    """build.build_und: (put OI added - call OI added) / (|call| + |put| added) over ATM+-5 strikes, 'added' = the last
    OI up to column t minus the first OI within the first 10 minutes."""
    K = ch.K
    atm = int(round(spot / step)) * step
    sel = (K >= atm - 5 * step) & (K <= atm + 5 * step)

    def first10(a):
        o = np.full(a.shape[0], np.nan)
        for i in range(a.shape[0]):
            v = a[i, :10][~np.isnan(a[i, :10])]
            if len(v):
                o[i] = v[0]
        return o

    def last(a):
        o = np.full(a.shape[0], np.nan)
        for i in range(a.shape[0]):
            v = a[i, :t + 1][~np.isnan(a[i, :t + 1])]
            if len(v):
                o[i] = v[-1]
        return o

    dC = np.nansum((last(ch.oi["C"]) - first10(ch.oi["C"]))[sel])
    dP = np.nansum((last(ch.oi["P"]) - first10(ch.oi["P"]))[sel])
    den = abs(dC) + abs(dP)
    return (dP - dC) / den if den > 0 else 0.0


def h5_signals(mk, T=11 * 60 + 30, X=0.15, Y=0.6, TS=60, oi_min=0.3, unds=NB):
    """H5-1, 1-lot version. Decision on the T bar's close (sim.DEC, build.DEC); side = sign of (close - day open);
    OI agreement oi_imb x side >= oi_min (pick.FILTERS 'oi_side>=0.3'). Exit (sim.sim_day, the 1-lot column): the
    first close at least Y x ATR14 below the best close since T (in the trade's direction), or, with TS, after TS
    minutes if the best extension by then is < X x ATR14 (the 'no add within 60 min' stop); filled at the next
    minute's open (exit_at); else 15:15. Real index days only, at most 30 missing minutes (build.build_und)."""
    out = []
    t = T - OPEN_M
    for u in unds:
        ix, opts = mk.index(u), mk.options(u)
        step = C.STEP[u]
        atr = _h5_atr(ix)
        M = ix.mat()
        for i, d in enumerate(ix.days):
            x = ix.d[d]
            A = atr.get(d, np.nan)
            if not x.get("real", True) or not np.isfinite(A) or A <= 0:
                continue
            ic = M["c"][i]
            if np.isnan(ic).sum() > 30:
                continue
            seen = pd.Series(ic[: t + 1]).ffill().bfill().values
            o0 = _first_valid(M["o"][i][: t + 1])
            if not (np.isfinite(seen[-1]) and np.isfinite(o0)):
                continue
            c = seen[-1]
            dr = 1 if c >= o0 else -1
            ch = opts.chain(d, "near")
            if ch is None:
                continue
            if _h5_oi_imb(ch, c, t, step) * dr < oi_min:
                continue
            full = pd.Series(ic).ffill().values.copy()
            full[: t + 1] = seen
            fav = (full[t:H5_SQ] - c) * dr
            peak = np.maximum.accumulate(fav)
            hit = np.nonzero(fav[1:] <= peak[1:] - Y * A)[0]
            kx = hit[0] + 1 if len(hit) else None
            if TS and t + TS < H5_SQ - 1 and peak[TS] < X * A:
                kx = TS if kx is None else min(kx, TS)
            xcol = H5_SQ if kx is None else t + kx + 1
            out.append(dict(und=u, day=d, sig_min=T, side=dr, book=f"h5_{u}",
                            exit_at=float(OPEN_M + xcol) if xcol < H5_SQ else np.nan))
    return _frame(out, COLS + ["exit_at"])


# ------------------------------------------------------------------------------------------------ SPECS
SQ15 = 15 * 60 + 15
SQ1511 = 15 * 60 + 11
MD = dict(sq_off=MD_SQ)
MD30 = dict(stop_pct=0.3, tgt_pct=0.3, sq_off=MD_SQ)
SW_WIN = (14 * 60, M0914)              # twins: a random minute that afternoon, coin-flip side, same exit day / time
AM_WIN = (9 * 60 + 16, 10 * 60 + 30)   # twins for 09:20 entries


def _swing_spec(key, title, rule, hold, why):
    other = [h for h in (1, 3, 5) if h != hold]
    return dict(
        key=key, title=title, source="index", why=why,
        orig=dict(fn=swing_signals, params=dict(rule=rule, hold=hold), money=0, series="near", unds=BN, exits=MD,
                  expiry="skip", pos=dict(one_at_a_time=True), window=SW_WIN),
        grid=[dict(params=dict(hold=h)) for h in other]
             + [dict(params=dict(hold=h), money=1, exits=MD30) for h in (1, 3, 5)],
    )


def _per_und(exs, unds_list, params_extra=None):
    g = []
    for u in unds_list:
        for e in exs:
            g.append(dict(params=dict(ex=e, **(params_extra or {})), unds=(u,)))
    return g


APP_LIT = dict(stop_pts=30.0, tgt_pts=60.0, ladder=LADDER, ladder_ref_pts=60.0, sq_off=MD_SQ)
APP_PCT = dict(stop_pct=0.3, tgt_pct=0.6, ladder=LADDER, ladder_ref_pct=0.6, sq_off=MD_SQ)

SPECS = [
    _swing_spec("SWING-3", "BANKNIFTY 5-day reversal (swing, bought 15:15)", "rev5", 1,
                "1-day ATM is the cell that came close (+129.8k yr1, -83.0k yr2, SWING.md); grid = SWING.md's own "
                "holds 1/3/5 x ATM / ITM1 +-30%"),
    _swing_spec("SWING-4", "BANKNIFTY gap-down day recovering at 15:15 (swing)", "gapdown", 3,
                "3-day ATM is the cell that came close (+40.6k / -46.0k, SWING.md); grid = holds 1/3/5 x ATM / ITM1"),
    _swing_spec("SWING-5", "BANKNIFTY big daily candle, 40% pullback next day (swing)", "bigpull", 3,
                "no pre-registered pick: 3-day ATM is the best yr-2 cell (+16.6k on 9, SWING.md); grid = holds x strike"),
    dict(key="SWING_DEEP-3", title="RSI(2) pullback above/below 200DMA, monthly ATM 09:20, 10 sessions", source="index",
         why="NIFTY monthly ATM fixed 10d was the close cell (+23.3k, 63 tr, 4/7 yrs; SWING_DEEP.md section 2); grid = "
             "its options exits (fixed 5d / 10d, 2ATR + 3ATR chandelier) + the 2ATR + 10d exit, per index as its "
             "per-index walk-forward",
         orig=dict(fn=sdeep_signals, params=dict(rule="rsi2", ex="10d", series="month"), money=0, series="month",
                   unds=NB, exits=MD, expiry="allow", pos=dict(one_at_a_time=True), window=AM_WIN),
         grid=[dict(params=dict(ex="5d")), dict(params=dict(ex="chand")), dict(params=dict(ex="atr10"))]
              + _per_und(["10d", "5d", "chand"], ["NIFTY", "BANKNIFTY"])),
    dict(key="SWING_DEEP-6", title="NR7 / inside day next-day stop entry, multi-day (OPTION VERSION of a futures rule)",
         source="index (futures traded in the original; R52 tests the monthly ATM option version)",
         why="NIFTY futures fixed 20d was the headline (+570k, 8/11 yrs); BN futures 2ATR + 10d (+590k). The lake has "
             "no futures: the option version on the monthly ATM (SWING_DEEP's own option leg). Grid = SWING_DEEP's "
             "exits, and the two per-index headline cells",
         orig=dict(fn=nr7_signals, params=dict(ex="20d", series="month"), money=0, series="month", unds=NB, exits=MD,
                   expiry="allow", pos=dict(one_at_a_time=True), window=(9 * 60 + 15, 14 * 60 + 30)),
         grid=[dict(params=dict(ex=e)) for e in ("5d", "10d", "chand", "atr10")]
              + [dict(params=dict(ex="20d"), unds=("NIFTY",)), dict(params=dict(ex="atr10"), unds=("BANKNIFTY",))]),
    dict(key="SWING_DEEP-8", title="Buy after a big down day (1-day return < -2 x 20-day SD), monthly ATM call",
         source="index",
         why="NIFTY monthly ATM fixed 10d was the close cell (+145k, 44 tr, 4/7 yrs, SWING_DEEP.md); grid = its options "
             "exits per index + merged SWING_EXITS-3 (near ATM call, app literal -30/+60 pts + ladder, or scaled "
             "-30%/+60% + ladder; max 5 / 3 sessions; its NIFTY literal and BN scaled headline cells)",
         orig=dict(fn=sdeep_signals, params=dict(rule="bigdown", ex="10d", series="month"), money=0, series="month",
                   unds=NB, exits=MD, expiry="allow", pos=dict(one_at_a_time=True), window=AM_WIN),
         grid=[dict(params=dict(ex="5d")), dict(params=dict(ex="chand"))]
              + _per_und(["10d", "5d", "chand"], ["NIFTY", "BANKNIFTY"])
              + [dict(params=dict(ex=e, series="near"), series="near", exits=x)
                 for x in (APP_LIT, APP_PCT) for e in ("5d", "3d")]
              + [dict(params=dict(ex="5d", series="near"), series="near", exits=APP_LIT, unds=("NIFTY",)),
                 dict(params=dict(ex="5d", series="near"), series="near", exits=APP_PCT, unds=("BANKNIFTY",))]),
    dict(key="LV-2", title="Filtered 09:20 BANKNIFTY long straddle held to 15:10",
         source="index + option-chain context (straddle price vs past index moves; no direction)",
         why="cost_vs_oc10 middle tercile was the best cell (+20.0k / -19.7k, LONG_VOL.md); grid = the other cells of "
             "its 'best single filters' list, cut points as published",
         orig=dict(fn=lv2_signals, params=dict(feat="cost_vs_oc10", lo=4.44, hi=6.25), money=0, series="near",
                   unds=BN, exits=dict(sq_off=SQ1511), expiry="skip", pos=dict(one_at_a_time=True),
                   window=(9 * 60 + 16, 11 * 60)),
         grid=[dict(params=dict(feat="implied_move_pct", lo=-np.inf, hi=1.75)),
               dict(params=dict(feat="cost_vs_range10", lo=1.86, hi=2.55)),
               dict(params=dict(feat="gap_pct", lo=0.30, hi=np.inf)),
               dict(params=dict(feat="cpr", lo=0.11, hi=0.23)),
               dict(params=dict(feat="first5_range", lo=224.67, hi=np.inf)),
               dict(params=dict(feat="dte", lo=-np.inf, hi=10.0)),
               dict(params=dict(feat="cost_vs_range10", lo=-np.inf, hi=1.86))]),
    dict(key="LV-4", title="Overnight BANKNIFTY long straddle (15:15 -> next 09:20)",
         source="time (no price signal; straddle)", why="the only overnight setting in LONG_VOL.md (+19.0k / -117k)",
         orig=dict(fn=lv4_signals, params={}, money=0, series="near", unds=BN, exits=MD, expiry="skip",
                   pos=dict(one_at_a_time=True), window=SW_WIN),
         grid=[]),
    dict(key="LV-5", title="Pre-event BANKNIFTY long straddle (RBI / Budget / election result)",
         source="event calendar (no price signal; straddle)",
         why="1 session before, sold 15:10 was the close cell (+60.3k on 14, LONG_VOL.md part 2); grid = its own "
             "1 / 2 sessions before x sold 10:30 / 15:10. (Merged OBUY_GC-11 = obuy gc_ev05_runup is its own registered "
             "strategy and is not re-run here.)",
         orig=dict(fn=lv5_signals, params=dict(back=1, xmin=SQ1511), money=0, series="near", unds=BN, exits=MD,
                   expiry="skip", pos=dict(one_at_a_time=True), window=SW_WIN),
         grid=[dict(params=dict(back=2)), dict(params=dict(xmin=10 * 60 + 31)),
               dict(params=dict(back=2, xmin=10 * 60 + 31))]),
    dict(key="BS-1", title="Boss's both-sides rule: ATM CE + ATM PE, each side -3% stop, +5/8/10% targets (per lot)",
         source="time; exits on premium (no price signal)",
         why="the owner's set (BOTH_SIDES.md: stop -3%, +5/8/10%, once a day at 09:20, no break-even) is Boss's own "
             "rule; 1 lot per trade, so orig = lot 1 (+5%), g01 / g02 = lots 2 / 3 (+8 / +10%); the rule = their sum. "
             "Grid adds the same lots at 10:00 and 11:00 (the study's entry times)",
         orig=dict(fn=bs_signals, params=dict(m0=9 * 60 + 20), money=0, series="near", unds=BN,
                   exits=dict(stop_pct=0.03, tgt_pct=0.05, sq_off=SQ1511), expiry="skip",
                   pos=dict(one_at_a_time=True, max_per_day=1), window=(9 * 60 + 16, 11 * 60)),
         grid=[dict(exits=dict(stop_pct=0.03, tgt_pct=0.08, sq_off=SQ1511)),
               dict(exits=dict(stop_pct=0.03, tgt_pct=0.10, sq_off=SQ1511))]
              + [dict(params=dict(m0=m0), exits=dict(stop_pct=0.03, tgt_pct=tg, sq_off=SQ1511))
                 for m0 in (10 * 60, 11 * 60) for tg in (0.05, 0.08, 0.10)]),
    dict(key="R13-1", title="Majority Rule MR(14) oversold touch, first green week (OPTION VERSION, monthly ATM call)",
         source="index (weekly NIFTY); original traded futures / modelled calls, R52 tests the nearest monthly ATM call",
         why="Boss's exact rule as boss.py: MR cc touch <= 28.57, WAIT reading, next week's open, +1000 / -500 pts, 13w "
             "cap. Grid = boss.py / study.py readings and exits (strict, plain touch, co mode, +4%/-2%, hold 4w, "
             "13w or MR > 50)",
         orig=dict(fn=r13_signals, params=dict(mode="cc", reading="wait", rule="pts", a=1000.0, b=500.0, cap=13),
                   money=0, series="month", unds=("NIFTY",), exits=MD, expiry="allow", pos=dict(one_at_a_time=True),
                   window=(9 * 60 + 15, 10 * 60 + 30)),
         grid=[dict(params=dict(reading="strict")),
               dict(params=dict(mode="co")),
               dict(params=dict(rule="pct", a=0.04, b=0.02)),
               dict(params=dict(rule="time", cap=4)),
               dict(params=dict(rule="mr", cap=13)),
               dict(params=dict(reading="touch", rule="time", cap=4)),
               dict(params=dict(reading="touch", rule="mr", cap=13))]),
    dict(key="H5-1", title="Trend-day rule with OI agreement at 11:30, 1-lot version (no pyramid adds)",
         source="index + option-chain context (OI)",
         why="HUNT_H5.md's best train rule (11:30, OI side >= 0.3, 1-ITM, trail 0.6 ATR, 60-min no-add stop, 15:15) "
             "and its own 'same rule with 1 lot' row; grid = one step at a time over its declared grid (add step X, "
             "trail Y, time stop, decision time, expiry skip, OI >= 0.6)",
         orig=dict(fn=h5_signals, params=dict(T=11 * 60 + 30, X=0.15, Y=0.6, TS=60, oi_min=0.3), money=1,
                   series="near", unds=NB, exits=dict(sq_off=SQ15), expiry="allow",
                   pos=dict(one_at_a_time=True, max_per_day=1), window=(10 * 60, 11 * 60 + 30)),
         grid=[dict(params=dict(X=0.25)), dict(params=dict(X=0.35)), dict(params=dict(Y=0.25)),
               dict(params=dict(Y=0.4)), dict(params=dict(TS=0)), dict(params=dict(T=10 * 60)),
               dict(params=dict(T=10 * 60 + 30)), dict(params=dict(T=11 * 60)), dict(expiry="skip"),
               dict(params=dict(oi_min=0.6))]),
]
