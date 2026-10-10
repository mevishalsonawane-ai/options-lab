"""Catalog family 'levels_breakout' (research/option_buying_catalog.json LV-01 .. LV-06). Expiry days skipped, out 15:15.

The engine is intraday only, so the swing / positional LV-04 (NR7) and LV-05 (Donchian) are tested as their intraday
legs: LV-04 enters on the breakout day and exits that day; LV-05 either enters at the next day's open after a daily-close
breakout or on the intraday break of the N-day channel, and exits the same day (nearest-monthly or near series).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import Execution, Exits, StrikeRule
from .base import Strategy
from .common import day_arrays
from .ga_common import SQ, UNDS2, UNDS4, close_exit, closes, first_break, last_valid

M = lambda h, m: h * 60 + m  # noqa: E731
END = SQ - 5 - C.OPEN_M


def _prev(ix):
    dl = ix.daily()
    p = dl[["high", "low", "close"]].shift(1)
    return {d: tuple(r) for d, r in zip(p.index, p.values)}


# ------------------------------------------------------------------------------------------------ LV-01 PDH / PDL
def lv01_signals(mk, retest=True, trigger="close", bias=True, cutoff=M(14, 30), unds=UNDS2):
    """Long: the index trades above the previous day's high (PDH); with retest, a later 5-min candle's low comes back to
    within 0.05% of PDH (or below it) without closing below PDL; then a 5-min close ('close') or a 1-min high ('touch')
    above the day's high so far buys a call. Without retest: the first 5-min close / 1-min high above PDH buys.
    Index stop: the lowest low since the first break (retest swing low); without retest, the low of the last 15 minutes.
    bias: opening above PDH allows longs only, below PDL shorts only. Short mirror. First signal of the day only."""
    out = []
    for und in unds:
        ix = mk.index(und)
        pv = _prev(ix)
        for d in ix.days:
            if not ix.d[d]["real"] or d not in pv or not np.isfinite(pv[d][0]):
                continue
            o, h, l, c = day_arrays(ix, d)
            ph, pl, _ = pv[d]
            op = o[0] if np.isfinite(o[0]) else c[0]
            sides = (1, -1)
            if bias and op > ph:
                sides = (1,)
            elif bias and op < pl:
                sides = (-1,)
            best = None
            for sd in sides:
                lvl0 = ph if sd > 0 else pl
                beyond = (h > lvl0) if sd > 0 else (l < lvl0)
                if not retest:
                    col, s = first_break(h, l, c, lvl0 if sd > 0 else np.nan, lvl0 if sd < 0 else np.nan, 0,
                                         cutoff - C.OPEN_M, tf=5, trigger=trigger, sides=(sd,))
                    if not s:
                        continue
                    stop = np.nanmin(l[max(col - 14, 0):col + 1]) if sd > 0 else np.nanmax(h[max(col - 14, 0):col + 1])
                else:
                    b = np.nonzero(beyond)[0]
                    if not len(b):
                        continue
                    b0 = int(b[0])
                    zone = lvl0 * (1 + 0.0005) if sd > 0 else lvl0 * (1 - 0.0005)
                    col = None
                    rt = None
                    for k in closes(5, b0 + 5, cutoff - C.OPEN_M):          # candles wholly after the first break
                        lo5, hi5 = np.nanmin(l[k - 4:k + 1]), np.nanmax(h[k - 4:k + 1])
                        if rt is None:
                            if (sd > 0 and lo5 <= zone) or (sd < 0 and hi5 >= zone):
                                rt = k
                            continue
                        ext = np.nanmax(h[:rt + 1]) if sd > 0 else np.nanmin(l[:rt + 1])   # day's extreme before the retest
                        if trigger == "close":
                            if np.isfinite(c[k]) and (c[k] - ext) * sd > 0:
                                col = k
                                break
                        else:
                            hit = np.nonzero((h[k - 4:k + 1] > ext) if sd > 0 else (l[k - 4:k + 1] < ext))[0]
                            if len(hit):
                                col = k - 4 + int(hit[0])
                                break
                    if col is None:
                        continue
                    stop = np.nanmin(l[b0:col + 1]) if sd > 0 else np.nanmax(h[b0:col + 1])
                    s = sd
                if best is None or col < best[0]:
                    best = (col, s, stop)
            if best is None:
                continue
            col, s, stop = best
            x = c[col]
            if not np.isfinite(x) or (x - stop) * s <= 0:
                continue
            out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=s, book=f"pdhl_{und}", idx_stop=stop))
    return pd.DataFrame(out)


LV01_GRID = [dict(retest=r, trigger=t, bias=b) for r in (True, False) for t in ("close", "touch") for b in (True, False)]


# ------------------------------------------------------------------------------------------------ LV-02 narrow CPR
def lv02_signals(mk, thr=0.2, tf=5, target="R1", cutoff=M(14, 0), unds=UNDS2):
    """Previous day's P = (H+L+C)/3, BC = (H+L)/2, TC = 2P - BC. Narrow day: |TC - BC| < thr% of P. First tf-min close
    above the CPR top buys a call (below the bottom a put); index stop at the other CPR edge; target R1 = 2P - L
    (S1 = 2P - H) or R2 = P + (H - L) (S2 = P - (H - L)) or none. Skipped if the target is already behind the entry."""
    out = []
    for und in unds:
        ix = mk.index(und)
        pv = _prev(ix)
        for d in ix.days:
            if not ix.d[d]["real"] or d not in pv or not np.isfinite(pv[d][0]):
                continue
            H, L, Cc = pv[d]
            P = (H + L + Cc) / 3
            BC = (H + L) / 2
            TC = 2 * P - BC
            top, bot = max(TC, BC), min(TC, BC)
            if (top - bot) >= thr / 100 * P:
                continue
            o, h, l, c = day_arrays(ix, d)
            col, side = first_break(h, l, c, top, bot, 0, cutoff - C.OPEN_M, tf=tf)
            if not side:
                continue
            x = c[col]
            t = {"R1": (2 * P - L) if side > 0 else (2 * P - H),
                 "R2": (P + (H - L)) if side > 0 else (P - (H - L)), "none": np.nan}[target]
            if np.isfinite(t) and (t - x) * side <= 0:
                continue
            out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"cpr_{und}",
                            idx_stop=bot if side > 0 else top, idx_target=t))
    return pd.DataFrame(out)


LV02_GRID = [dict(thr=t, tf=tf, target=g) for t in (0.1, 0.2, 0.3) for tf in (5, 15) for g in ("R1", "R2", "none")]


# ------------------------------------------------------------------------------------------------ LV-03 Camarilla
def lv03_signals(mk, level=4, tgt_m=2.0, stop_mode="close", unds=UNDS4):
    """Camarilla from the previous day: Hn = C + 1.1 (H - L) x f, f = 1/12, 1/6, 1/4, 1/2 for n = 1..4 (Ln mirror).
    After 09:30 the first 5-min close above H{level} buys a call (below L{level} a put). Stop: back inside the next
    level in (H{level-1}): on a 5-min close ('close') or touched ('touch'). Target: H{level} + tgt_m x (H{level} -
    H{level-1}), or none (tgt_m = 0)."""
    f = {1: 1 / 12, 2: 1 / 6, 3: 1 / 4, 4: 1 / 2}
    out = []
    for und in unds:
        ix = mk.index(und)
        pv = _prev(ix)
        for d in ix.days:
            if not ix.d[d]["real"] or d not in pv or not np.isfinite(pv[d][0]):
                continue
            H, L, Cc = pv[d]
            r = 1.1 * (H - L)
            up, up1 = Cc + r * f[level], Cc + r * f[level - 1]
            dn, dn1 = Cc - r * f[level], Cc - r * f[level - 1]
            o, h, l, c = day_arrays(ix, d)
            col, side = first_break(h, l, c, up, dn, 15, END, tf=5)
            if not side:
                continue
            lv, lv1 = (up, up1) if side > 0 else (dn, dn1)
            tgt = lv + tgt_m * (lv - lv1) if tgt_m else np.nan
            row = dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"cam_{und}", idx_target=tgt)
            if stop_mode == "touch":
                row["idx_stop"] = lv1
            else:
                row["exit_at"] = close_exit(c, lv1, side, col + 1)
            out.append(row)
    return pd.DataFrame(out)


LV03_GRID = [dict(level=lv, tgt_m=m, stop_mode=s) for lv in (4, 3) for m in (0.0, 1.0, 2.0) for s in ("close", "touch")]


# ------------------------------------------------------------------------------------------------ LV-04 NR7 / inside day
def lv04_signals(mk, n=7, inside=True, trigger="touch", tgt_k=None, unds=UNDS2):
    """Day t is NR-n (the smallest high-low range of the last n days) [and an inside day]. Day t+1: a break of day t's
    high buys a call, of its low a put (1-min 'touch' or 15-min 'close'); index stop at the other side of day t;
    target tgt_k x day t's range from the broken level, or none. First break only; exit the same day (intraday leg)."""
    out = []
    for und in unds:
        ix = mk.index(und)
        dl = ix.daily()
        rng = dl.high - dl.low
        nr = (rng == rng.rolling(n).min())
        ins = (dl.high <= dl.high.shift(1)) & (dl.low >= dl.low.shift(1))
        flag = (nr & (ins if inside else True)).shift(1, fill_value=False)
        ph, pl = dl.high.shift(1), dl.low.shift(1)
        for d in ix.days:
            if not flag.get(d, False) or not ix.d[d]["real"]:
                continue
            H, L = ph[d], pl[d]
            o, h, l, c = day_arrays(ix, d)
            col, side = first_break(h, l, c, H, L, 0, END, tf=15, trigger=trigger)
            if not side:
                continue
            lvl = L if side > 0 else H
            tgt = ((H if side > 0 else L) + side * tgt_k * (H - L)) if tgt_k else np.nan
            if np.isfinite(tgt) and (tgt - c[col]) * side <= 0:
                continue
            if (c[col] - lvl) * side <= 0:
                continue
            out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"nr_{und}", idx_stop=lvl,
                            idx_target=tgt))
    return pd.DataFrame(out)


LV04_GRID = [dict(n=n, inside=i, trigger=t, tgt_k=k) for n in (4, 7) for i in (True, False) for t in ("touch", "close")
             for k in (None, 1.0, 2.0)]


# ------------------------------------------------------------------------------------------------ LV-05 Donchian
def lv05_signals(mk, n=7, vix=False, mode="nextday", unds=UNDS2):
    """N-day channel = the highest high / lowest low of the n days before. 'nextday': yesterday CLOSED above the channel
    (as of yesterday) -> buy a call at today's open (signal on the 09:15 bar), below -> a put. 'intraday': the first
    1-min close today beyond the channel of the n previous days. Index stop: the opposite n/2-day channel (as of
    yesterday). vix: only when yesterday's India VIX is below its 60-day median (cheap options)."""
    vp = mk.vix.daily.close
    vok = (vp.shift(1) < vp.shift(1).rolling(60).median()).to_dict()
    out = []
    for und in unds:
        ix = mk.index(und)
        dl = ix.daily()
        hh = dl.high.rolling(n).max().shift(1)          # channel of the n days before each day
        ll = dl.low.rolling(n).min().shift(1)
        h2 = dl.high.rolling(max(n // 2, 1)).max()      # n/2 channel including that day (known at its close)
        l2 = dl.low.rolling(max(n // 2, 1)).min()
        brk = np.where(dl.close > hh, 1, np.where(dl.close < ll, -1, 0))
        brk = pd.Series(brk, index=dl.index).shift(1, fill_value=0)
        sh2, sl2 = h2.shift(1), l2.shift(1)
        for d in ix.days:
            if not ix.d[d]["real"] or not np.isfinite(hh.get(d, np.nan)):
                continue
            if vix and not vok.get(d, False):
                continue
            o, h, l, c = day_arrays(ix, d)
            if mode == "nextday":
                side, col = int(brk[d]), 0
                if not side or not np.isfinite(c[0]):
                    continue
            else:
                col, side = first_break(h, l, c, hh[d], ll[d], 0, END, tf=1)
                if not side:
                    continue
            lvl = sl2[d] if side > 0 else sh2[d]
            if not np.isfinite(lvl) or (c[col] - lvl) * side <= 0:
                lvl = np.nan
            out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"donch_{und}", idx_stop=lvl))
    return pd.DataFrame(out)


LV05_GRID = [dict(n=n, vix=v, mode=m) for n in (5, 7, 10, 20) for v in (False, True) for m in ("nextday", "intraday")]


# ------------------------------------------------------------------------------------------------ LV-06 OI wall break
_MEMO06 = {}


def _oi_day(mk, und, d):
    """(K, call OI, put OI, spot) at 5-min candle closes (forward-filled), near series; memoised per (und, day)."""
    k = (und, d)
    if k in _MEMO06:
        return _MEMO06[k]
    ch = mk.options(und).chain(d, "near")
    r = None
    if ch is not None:
        def ff(a):
            x = pd.DataFrame(a.T).ffill().values.T
            return x[:, 4::5].astype(np.float32)
        r = (ch.K.copy(), ff(ch.oi["C"]), ff(ch.oi["P"]))
    _MEMO06[k] = r
    return r


def lv06_signals(mk, look=15, nk=5, drop=0.0, target="next", unds=UNDS2):
    """At each 5-min close from 09:45 to 14:30: the call wall = the strike with the most call OI among the nk strikes
    above the index at the previous 5-min close. If the index now closes above it AND that strike's call OI fell over
    the last `look` minutes by more than drop% -> buy a call. Put wall below -> a put. Stop: a 5-min close back
    through the wall strike. Target: the next wall beyond (the most call OI among the nk strikes above the wall), or
    none. First signal of the day."""
    out = []
    lb = look // 5
    for und in unds:
        ix = mk.index(und)
        step = C.STEP[und]
        for d in ix.days:
            if ix.d[d]["exp"] or not ix.d[d]["real"]:
                continue
            r = _oi_day(mk, und, d)
            if r is None:
                continue
            K, oc, op = r
            o, h, l, c = day_arrays(ix, d)
            done = False
            for j in range(6, (M(14, 30) - C.OPEN_M) // 5):     # j-th 5-min candle; its close column = 5j + 4
                col, pcol = 5 * j + 4, 5 * j - 1
                x, xp = c[col], last_valid(c, pcol)
                if not (np.isfinite(x) and np.isfinite(xp)) or j - lb < 0:
                    continue
                for sd, oi in ((1, oc), (-1, op)):
                    cand = np.nonzero((K > xp) & (K <= xp + nk * step))[0] if sd > 0 else \
                        np.nonzero((K < xp) & (K >= xp - nk * step))[0]
                    vals = oi[cand, j - 1]
                    ok = np.isfinite(vals)
                    if not ok.any():
                        continue
                    i = cand[ok][np.argmax(vals[ok])]
                    wall = K[i]
                    if (x - wall) * sd <= 0:
                        continue
                    a, b = oi[i, j], oi[i, j - lb]
                    if not (np.isfinite(a) and np.isfinite(b) and b > 0 and a < b * (1 - drop / 100) - 1e-9):
                        continue
                    tgt = np.nan
                    if target == "next":
                        nxt = np.nonzero((K > wall) & (K <= wall + nk * step))[0] if sd > 0 else \
                            np.nonzero((K < wall) & (K >= wall - nk * step))[0]
                        v2 = oi[nxt, j]
                        if np.isfinite(v2).any():
                            tgt = float(K[nxt[np.isfinite(v2)][np.argmax(v2[np.isfinite(v2)])]])
                            if (tgt - x) * sd <= 0:
                                tgt = np.nan
                    out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=sd, book=f"oiwall_{und}",
                                    idx_target=tgt, exit_at=close_exit(c, float(wall), sd, col + 1),
                                    tag=f"wall={wall}"))
                    done = True
                    break
                if done:
                    break
        mk.release(und)
    return pd.DataFrame(out)


LV06_GRID = [dict(look=lk, nk=n, drop=dp, target=t) for lk in (15, 30) for n in (5, 10) for dp in (0.0, 5.0, 10.0)
             for t in ("next", "none")]

EXE = Execution(expiry="skip")
ATM, ITM1 = StrikeRule(0), StrikeRule(1)
LEVELS = Exits(sq_off=SQ)
P30 = Exits(stop_pct=0.3, sq_off=SQ)
TRAIL = Exits(stop_pct=0.3, pine_trail=True, sq_off=SQ)

STRATEGIES = [
    Strategy(name="ga_lv01_pdhl", family="levels_breakout", signal_fn=lv01_signals, sig_grid=LV01_GRID,
             rules=[ATM, ITM1], exits=[Exits(idx_tgt_r=r, sq_off=SQ) for r in (1.0, 2.0, 3.0)] + [
                 Exits(idx_tgt_r=3.0, pine_trail=True, sq_off=SQ)], exe=EXE, window=(M(9, 20), M(14, 30)),
             doc="LV-01 PDH/PDL break, retest yes/no, close/touch trigger, opening bias, retest-swing stop, 1/2/3R (+trail)."),
    Strategy(name="ga_lv02_cpr", family="levels_breakout", signal_fn=lv02_signals, sig_grid=LV02_GRID,
             rules=[ATM, ITM1], exits=[LEVELS, P30], exe=EXE, window=(M(9, 19), M(14, 0)),
             doc="LV-02 narrow CPR (<0.1/0.2/0.3%) break on 5/15-min close, stop other CPR edge, target R1/R2/none."),
    Strategy(name="ga_lv03_camarilla", family="levels_breakout", signal_fn=lv03_signals, sig_grid=LV03_GRID,
             rules=[ATM, ITM1], exits=[LEVELS, P30, TRAIL], exe=EXE, window=(M(9, 34), M(15, 9)),
             doc="LV-03 Camarilla H4/L4 (or H3/L3) 5-min close break after 09:30, stop back inside (close/touch), "
                 "target 0/1/2 x level gap."),
    Strategy(name="ga_lv04_nr7", family="levels_breakout", signal_fn=lv04_signals, sig_grid=LV04_GRID,
             rules=[ITM1, StrikeRule(1, "month"), ATM], exits=[LEVELS], exe=EXE, window=(M(9, 15), M(15, 9)),
             doc="LV-04 NR4/NR7 [inside] day, next-day break (touch/15-min close), stop other side, target 1/2 x range "
                 "or none; intraday leg only."),
    Strategy(name="ga_lv05_donchian", family="levels_breakout", signal_fn=lv05_signals, sig_grid=LV05_GRID,
             rules=[StrikeRule(0, "month"), StrikeRule(1, "month")], exits=[LEVELS, P30, TRAIL], exe=EXE,
             window=(M(9, 15), M(15, 9)),
             doc="LV-05 N-day (5/7/10/20) channel break, next-open after a daily close or intraday, VIX<median filter, "
                 "monthly ATM/ITM1; first-day leg only."),
    Strategy(name="ga_lv06_oiwall", family="levels_breakout", signal_fn=lv06_signals, sig_grid=LV06_GRID,
             rules=[ATM, ITM1], exits=[LEVELS, P30], exe=EXE, window=(M(9, 44), M(14, 30)),
             doc="LV-06 close through the max-OI call (put) wall with that strike's OI falling (15/30 min, >0/5/10%), "
                 "ATM+-5/10 strikes, stop close back through, target next wall."),
]
