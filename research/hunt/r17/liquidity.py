"""R17: Liquidity BANKNIFTY 15+5 trades on the R17 grids, for STUDY ONLY (the arm is frozen until 26 Oct; nothing in the
app changes for it). The zone code is copied from research/liquidity_exits_3060.py (a line-by-line port of the Kotlin
replay; that script reads argv at import, so it cannot be imported). Books: 15-min and 5-min, one position per book,
pool-on-swing break, fills 09:20-14:00, room filter (1 x the index stop), 1-ITM, expiry days skipped, expiry strictly
after today. Own exits: index stop level -+ 30 on a 1-minute bar, 20-minute time stop unless +5%, next liquidity (touch),
failed break, new liquidity, resting SL-M at 0.85 x fill, 15:10.
"""
from __future__ import annotations

import math
from datetime import timedelta

import numpy as np

import lib
from lib import N, TICK, Grid, Trade, buy_px, replay

L, CONTACTS, GAP, CONFIRM, MAX_AGE = 20, 2, 5, 10, 300
OPEN_M = 555
WIN_FROM, WIN_TO = 9 * 60 + 20, 14 * 60
BOOKS = [("liquidity15", 15), ("liquidity5", 5)]
ISTOP = 30.0


class Zone:
    __slots__ = ("kind", "side", "top", "bottom", "origin", "known", "broken")

    def __init__(self, kind, side, top, bottom, origin, known):
        self.kind, self.side, self.top, self.bottom, self.origin, self.known, self.broken = kind, side, top, bottom, origin, known, -1

    @property
    def edge(self):
        return self.top if self.side > 0 else self.bottom


def mark_breaks(zones, C):
    for z in zones:
        seg = C[z.known:]
        hit = (seg > z.top) if z.side > 0 else (seg < z.bottom)
        z.broken = int(z.known + np.argmax(hit)) if hit.any() else -1


def swing_zones(H, Lo, C):
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
    mark_breaks(out, C)
    return out


def pool_zones(O, H, Lo, C):
    out = []
    cand = []
    for i in range(len(C)):
        o, h, lo, c = O[i], H[i], Lo[i], C[i]
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
    mark_breaks(out, C)
    return out


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


def ones_of(g: Grid, i):
    ix = g.days[i]["ix"]
    d = g.dates[i]
    return [(d, OPEN_M + k, ix[k, 0], ix[k, 1], ix[k, 2], ix[k, 3]) for k in range(N) if np.isfinite(ix[k, 3])]


def trades(g: Grid):
    out = []
    for i in range(1, len(g.days)):
        d = g.dates[i]
        if not g.ok(i) or g.is_expiry(i):
            continue
        hist = [j for j in range(max(0, i - 12), i) if g.dates[j] >= d - timedelta(days=10)]
        ones = [b for j in hist for b in ones_of(g, j)] + ones_of(g, i)
        ix = g.days[i]["ix"]
        for book, tf in BOOKS:
            bars = fold(ones, tf)
            if len(bars) < 2 * L + 2:
                continue
            O = np.array([b[2] for b in bars]); H = np.array([b[3] for b in bars])
            Lo = np.array([b[4] for b in bars]); C = np.array([b[5] for b in bars])
            sw_, po = swing_zones(H, Lo, C), pool_zones(O, H, Lo, C)
            zones = sw_ + po
            n = len(bars)
            is_today = [b[0] == d for b in bars]
            bar_end = [b[1] + tf if is_today[k] else -1 for k, b in enumerate(bars)]
            by_break = {}
            for p in po:
                if p.broken >= 0:
                    by_break.setdefault(p.broken, []).append(p)
            free_from = 0
            for bi in range(n):
                if not is_today[bi] or bars[bi][1] + tf > 930 or bi not in by_break:
                    continue
                pool = None
                for p in by_break[bi]:
                    if p.known > bi:
                        continue
                    if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= bi and (s.broken < 0 or s.broken >= bi)
                           for s in sw_):
                        pool = p
                        break
                if pool is None:
                    continue
                done = bar_end[bi]
                if done < free_from or done < WIN_FROM or done > WIN_TO:
                    continue
                side, close = pool.side, C[bi]
                ahead = [q.edge for q in zones if q.side == side and q.known <= bi and (q.broken < 0 or q.broken > bi) and side * (q.edge - close) > 0]
                target = (min(ahead) if side > 0 else max(ahead)) if ahead else None
                if target is not None and side * (target - close) < ISTOP:
                    continue
                level = pool.edge
                fb = next((bar_end[k] for k in range(bi + 1, n) if side * (C[k] - level) < 0), None)
                nl = [bar_end[z.known] for z in zones if z.side == side and z.known >= bi + 1]
                nl = min(nl) if nl else None
                strike = int(math.floor(close / 100 + 0.5) * 100) - side * 100
                k = done - OPEN_M
                path = g.opt(i, strike, side, today_ok=False)
                if path is None:
                    continue
                c0 = None
                for m in range(k, min(k + 3, N)):
                    if np.isfinite(path[m, 0]):
                        c0 = m
                        break
                if c0 is None:
                    continue
                e = buy_px(path[c0, 0], lib.K.HALF_SPREAD["BANKNIFTY"])
                lot = lib.lot_of("BANKNIFTY", d)
                # the arm's index / structure exits as a decision minute (close of minute m -> next open)
                ex = None
                for m in range(c0, lib.SQ_1510):
                    if (side > 0 and ix[m, 2] < level - ISTOP) or (side < 0 and ix[m, 1] > level + ISTOP):
                        ex = m
                        break
                    if target is not None and ((side > 0 and ix[m, 1] >= target) or (side < 0 and ix[m, 2] <= target)):
                        ex = m
                        break
                    if (fb is not None and m + 1 >= fb - OPEN_M) or (nl is not None and m + 1 >= nl - OPEN_M):
                        ex = m
                        break
                t = Trade(arm="liq_bn", und="BANKNIFTY", i=i, side=side, strike=float(strike), c0=c0, e=e, qty=lot,
                          path=path, sq=lib.SQ_1510, stop0=math.floor(e * 0.85 / TICK + 1e-9) * TICK, time_rule=(19, 0.05),
                          arm_exit=ex, ix_entry=float(close), hs=lib.K.HALF_SPREAD["BANKNIFTY"],
                          meta=dict(lot=lot, book=book))
                r = replay(t)
                out.append(t)
                free_from = OPEN_M + r["exit_min"] + 1
    return out
