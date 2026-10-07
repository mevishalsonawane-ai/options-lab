"""h4 components: the best-evidenced option-BUYING rules, fixed BEFORE any portfolio result was read.

Every component is a rule that already exists in the app or in earlier research, with its rules UNCHANGED:

  liq_<U>       Liquidity 15+5 (the app's arm). BANKNIFTY / FINNIFTY come from the validated port
                (obuy.strategies.liquidity, trade-for-trade equal to the app). NIFTY / SENSEX / MIDCPNIFTY use the SAME
                rules re-implemented here on obuy's index minutes (pool-on-swing break, lookback 20, confirm 10,
                15-min + 5-min books, room >= 1 index stop, 1 ITM, -15% premium stop, 20-min +5% time stop, index stop,
                next-level target, failed break, new level, 15:10, expiry days skipped). The only thing that must be
                chosen for a new index is the index-stop size: it is set a priori to the arms' ~0.065% of the index
                (BANKNIFTY 30 / FINNIFTY 15 -> NIFTY 15, SENSEX 50, MIDCPNIFTY 8). Not tuned.
  orb_app / orb_fresh / orb_sweep / range_fade   the app's four ORB-family arms (BANKNIFTY, 5-min bars, opening range
                09:15-10:00, ATM strike from the 09:20 bar, -40 / +40 (Sweep +80) premium points, 15:10), as
                research/arms_long.py defines them.
  solo_midday   obuy's validated Solo midday (Jarvis C0 exits).
  btst          gc_pos01_btst (overnight; NOT intraday) - whole 48-variant grid run; ONE variant picked on pre-holdout net.
  camarilla     gb_mr04_camarilla - whole 72-variant grid run; ONE variant picked on pre-holdout net.
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from obuy import config as C
from obuy.engine import Execution, Exits, StrikeRule
from obuy.strategies.base import Strategy
from obuy.strategies.common import day_arrays
from obuy.strategies import liquidity as LQ
from obuy.strategies import solo as SOLO
from obuy.strategies import gc_pos as GCP
from obuy.strategies import gb_meanrev as GBM

# ------------------------------------------------------------------ Liquidity rules (port of LiquidityRules.kt, as in
# research/liquidity_exits_3060.py lines 57-143; copied because that script parses sys.argv on import)
L, CONTACTS, GAP, CONFIRM, MAX_AGE = 20, 2, 5, 10, 300
OPEN_M = 9 * 60 + 15
WIN_FROM, WIN_TO = 9 * 60 + 20, 14 * 60
IDX_STOP_EXT = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "NIFTY": 15.0, "SENSEX": 50.0, "MIDCPNIFTY": 8.0}
BOOKS_EXT = {"BANKNIFTY": (15, 5), "FINNIFTY": (30, 5), "NIFTY": (15, 5), "SENSEX": (15, 5), "MIDCPNIFTY": (15, 5)}


def fold(ones, tf):
    out = []
    cur = None
    for d, m, o, h, lo, c in ones:
        k = (d, OPEN_M + (max(m - OPEN_M, 0) // tf) * tf)
        if cur is None or cur[0] != k:
            if cur is not None:
                out.append((cur[0][0], cur[0][1], cur[1], cur[2], cur[3], cur[4]))
            cur = [k, o, h, lo, c]
        else:
            cur[2] = max(cur[2], h); cur[3] = min(cur[3], lo); cur[4] = c
    if cur is not None:
        out.append((cur[0][0], cur[0][1], cur[1], cur[2], cur[3], cur[4]))
    return out


class Zone:
    __slots__ = ("kind", "side", "top", "bottom", "origin", "known", "broken")

    def __init__(self, kind, side, top, bottom, origin, known):
        self.kind, self.side, self.top, self.bottom, self.origin, self.known, self.broken = kind, side, top, bottom, origin, known, -1

    @property
    def edge(self):
        return self.top if self.side > 0 else self.bottom


def mark_breaks(zones, Cl):
    for z in zones:
        seg = Cl[z.known:]
        hit = (seg > z.top) if z.side > 0 else (seg < z.bottom)
        z.broken = int(z.known + np.argmax(hit)) if hit.any() else -1


def swing_zones(H, Lo, Cl):
    n = len(H)
    out = []
    if n > 2 * L:
        from numpy.lib.stride_tricks import sliding_window_view as sw
        lmax = np.full(n, np.inf); rmax = np.full(n, np.inf); lmin = np.full(n, -np.inf); rmin = np.full(n, -np.inf)
        wh = sw(H, L).max(axis=1); wl = sw(Lo, L).min(axis=1)
        js = np.arange(L, n - L)
        lmax[js] = wh[js - L]; rmax[js] = wh[js + 1]; lmin[js] = wl[js - L]; rmin[js] = wl[js + 1]
        for i in range(2 * L, n):
            j = i - L
            if lmax[j] < H[j] and rmax[j] < H[j]:
                out.append(Zone("swing", 1, H[j], Lo[j], j, i))
            if lmin[j] > Lo[j] and rmin[j] > Lo[j]:
                out.append(Zone("swing", -1, H[j], Lo[j], j, i))
    mark_breaks(out, Cl)
    return out


def pool_zones(O, H, Lo, Cl):
    out = []
    cand = []
    for i in range(len(Cl)):
        o, h, lo, c = O[i], H[i], Lo[i], Cl[i]
        keep = []
        for z in cand:
            side, top, bot, origin = z[0], z[1], z[2], z[3]
            if i - origin > MAX_AGE:
                continue
            if (side > 0 and c > top) or (side < 0 and c < bot):
                continue
            touched = (h >= bot and c < top) if side > 0 else (lo <= top and c > bot)
            if touched and i - z[5] >= GAP and z[4] < CONTACTS:
                z[4] += 1; z[5] = i
            if z[4] >= CONTACTS and i - z[5] >= CONFIRM:
                dup = any(q.side == side and q.bottom <= top and bot <= q.top for q in out[-50:])
                if not dup:
                    out.append(Zone("pool", side, top, bot, origin, i))
                continue
            keep.append(z)
        cand = keep
        bh, bl = max(o, c), min(o, c)
        if h > bh:
            cand.append([1, h, bh, i, 1, i])
        if lo < bl:
            cand.append([-1, bl, lo, i, 1, i])
    mark_breaks(out, Cl)
    return out


def atm(spot, step):
    return int(math.floor(spot / step + 0.5) * step)


def liq_ext_signals(mk, unds=("NIFTY", "SENSEX", "MIDCPNIFTY"), room=1.0):
    """The arm's signals on any index from obuy's index minutes (same output columns as obuy.strategies.liquidity)."""
    from datetime import timedelta
    rows = []
    for und in unds:
        ix = mk.index(und)
        step, istop = C.STEP[und], IDX_STOP_EXT[und]
        hist = []
        for d in ix.days:
            x = ix.d[d]
            real_today = bool(x["real"])
            m = np.asarray(x["m"]).astype(int)
            sel = (m >= 555) & (m <= 929)
            today1 = [(d, int(mm), float(o), float(h), float(lo), float(c))
                      for mm, o, h, lo, c in zip(m[sel], x["o"][sel], x["h"][sel], x["l"][sel], x["c"][sel])]
            hist = [hh for hh in hist if hh[0] >= d - timedelta(days=10)]
            real = real_today and hist and all(hh[1] for hh in hist)
            if real and not x["exp"]:
                first = {}
                for b in [b for hh in hist for b in hh[2]] + today1:
                    first.setdefault((b[0], b[1]), b)
                ones = sorted(first.values(), key=lambda b: (b[0], b[1]))
                for tf in BOOKS_EXT[und]:
                    book = f"liq{tf}_{und}"
                    bars = fold(ones, tf)
                    if len(bars) < 2 * L + 2:
                        continue
                    O = np.array([b[2] for b in bars]); H = np.array([b[3] for b in bars])
                    Lo = np.array([b[4] for b in bars]); Cl = np.array([b[5] for b in bars])
                    sw_, po = swing_zones(H, Lo, Cl), pool_zones(O, H, Lo, Cl)
                    zones = sw_ + po
                    n = len(bars)
                    is_today = [b[0] == d for b in bars]
                    bar_end = [b[1] + tf if is_today[k] else -1 for k, b in enumerate(bars)]
                    by_break = {}
                    for p in po:
                        if p.broken >= 0:
                            by_break.setdefault(p.broken, []).append(p)
                    for i in range(n):
                        if not is_today[i] or bars[i][1] + tf > 930 or i not in by_break:
                            continue
                        pool = None
                        for p in by_break[i]:
                            if p.known > i:
                                continue
                            if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                                   and (s.broken < 0 or s.broken >= i) for s in sw_):
                                pool = p; break
                        if pool is None:
                            continue
                        side, close = pool.side, Cl[i]
                        ahead = [q.edge for q in zones if q.side == side and q.known <= i and (q.broken < 0 or q.broken > i)
                                 and side * (q.edge - close) > 0]
                        target = (min(ahead) if side > 0 else max(ahead)) if ahead else None
                        level = pool.edge
                        fb = next((bar_end[k] for k in range(i + 1, n) if side * (Cl[k] - level) < 0), None)
                        nl = [bar_end[z.known] for z in zones if z.side == side and z.known >= i + 1]
                        nl = min(nl) if nl else None
                        done = bar_end[i]
                        if done > 930 or done < WIN_FROM or done > WIN_TO:
                            continue
                        if target is not None and side * (target - close) < room * istop:
                            continue
                        xa = [v for v in (fb, nl) if v is not None]
                        rows.append(dict(und=und, day=d, sig_min=done - 1, gate=done, side=side, book=book,
                                         ref_spot=float(close), strike=atm(close, step) - side * step,
                                         idx_stop=level - side * istop,
                                         idx_target=np.nan if target is None else float(target),
                                         exit_at=float(min(xa)) if xa else np.nan, tag=f"lvl={level:.2f}"))
            hist.append((d, real_today, today1))
    return pd.DataFrame(rows)


# ------------------------------------------------------------------ the app's ORB-family arms (BANKNIFTY)
ARM_KIND = {"orb_app": ("orb", 40, 40, 99), "orb_fresh": ("fresh", 40, 40, 99),
            "orb_sweep": ("sweep", 40, 80, 2), "range_fade": ("fade", 40, 40, 2)}


def orb_arm_signals(mk, kind="orb", unds=("BANKNIFTY",)):
    out = []
    for und in unds:
        ix = mk.index(und)
        step = C.STEP[und]
        for d in ix.days:
            if not ix.d[d]["real"]:
                continue
            o, h, l, c = day_arrays(ix, d)
            nb = C.W // 5
            bo = np.array([o[5 * k] for k in range(nb)]); bh = np.array([np.nanmax(h[5 * k:5 * k + 5]) if np.isfinite(h[5 * k:5 * k + 5]).any() else np.nan for k in range(nb)])
            bl = np.array([np.nanmin(l[5 * k:5 * k + 5]) if np.isfinite(l[5 * k:5 * k + 5]).any() else np.nan for k in range(nb)])
            bc = np.array([c[5 * k + 4] if np.isfinite(c[5 * k + 4]) else np.nan for k in range(nb)])
            if np.isnan(bh[:10]).sum() > 2 or not np.isfinite(bc[1]):
                continue
            orh, orl = np.nanmax(bh[:10]), np.nanmin(bl[:10])
            w = orh - orl
            strike = atm(bc[1], step)
            ks = range(15, 57) if kind == "fade" else range(10, 63)
            for k in ks:
                if not (np.isfinite(bc[k]) and np.isfinite(bh[k])):
                    continue
                s = 0
                if kind in ("orb", "fresh"):
                    s = 1 if bc[k] > orh else -1 if bc[k] < orl else 0
                    if kind == "fresh" and s and np.isfinite(bc[k - 1]) and ((s > 0 and bc[k - 1] > orh) or (s < 0 and bc[k - 1] < orl)):
                        s = 0
                elif kind == "sweep":
                    s = -1 if (bh[k] > orh and bc[k] < orh) else 1 if (bl[k] < orl and bc[k] > orl) else 0
                elif kind == "fade" and w > 0:
                    s = -1 if (bh[k] >= orh - 0.1 * w and bc[k] < orh) else 1 if (bl[k] <= orl + 0.1 * w and bc[k] > orl) else 0
                if s:
                    sm = C.OPEN_M + 5 * k + 4
                    out.append(dict(und=und, day=d, sig_min=sm, side=s, book=f"{kind}_{und}", ref_spot=float(bc[k]),
                                    strike=strike))
    return pd.DataFrame(out)


SQ10 = 15 * 60 + 10


def components():
    """[(component name, Strategy)] - fixed list. btst / camarilla carry their whole grids (picked later, pre-holdout)."""
    S = []
    S.append(("liq_bnfin", LQ.STRATEGIES[0]))
    S.append(("liq_ext", Strategy(name="liq_ext", family="level-break", signal_fn=liq_ext_signals, sig_grid=[{}],
                                  rules=[StrikeRule(money=1)], exe=Execution(expiry="skip"), exits=[LQ.ARM_EXITS],
                                  window=(WIN_FROM - 1, WIN_TO - 1))))
    for nm, (kind, sp, tp, mx) in ARM_KIND.items():
        S.append((nm, Strategy(name=nm, family="orb_arm", signal_fn=orb_arm_signals, sig_grid=[dict(kind=kind)],
                               rules=[StrikeRule(0)], exe=Execution(expiry="skip"),
                               exits=[Exits(stop_pts=sp, tgt_pts=tp, sq_off=SQ10)],
                               pos=dict(one_at_a_time=True, max_per_day=mx), window=(10 * 60 + 4, 14 * 60 + 29))))
    solo = [s for s in SOLO.STRATEGIES if s.name == "solo_midday"][0]
    S.append(("solo_midday", solo))
    S.append(("btst", [s for s in GCP.STRATEGIES if s.name == "gc_pos01_btst"][0]))
    S.append(("camarilla", [s for s in GBM.STRATEGIES if s.name == "gb_mr04_camarilla"][0]))
    return S
