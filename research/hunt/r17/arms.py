"""R17 arms: each arm's entries as the app trades them today, with the arm's own exits (V0), as Trade objects (PREREG.md).

The trade SET is decided here with V0: one position at a time per arm, the arm's re-entry rule and caps. Variants then
only change how each of these trades ends (lib.replay).
"""
from __future__ import annotations

import math

import numpy as np

import lib
from lib import N, TICK, Grid, Trade, buy_px, floor_tick, lot_of, replay

ORB_FAMILY = ("orb", "orb_fresh", "orb_sweep", "range_fade")
NAMES = {"orb": "ORB", "orb_fresh": "ORB Fresh", "orb_sweep": "ORB Sweep", "range_fade": "Range Fade",
         "vixdiv_ni": "VIX divergence NIFTY", "vixdiv_bn": "VIX divergence BANKNIFTY", "hero": "Hero (NIFTY expiry)",
         "pine_ema_ni": "Pine EMA 9/21 NIFTY", "pine_ema_bn": "Pine EMA 9/21 BANKNIFTY",
         "pine_st_ni": "Pine Supertrend NIFTY", "pine_st_bn": "Pine Supertrend BANKNIFTY",
         "liq_bn": "Liquidity BANKNIFTY (frozen: study only)"}
ARMS = list(NAMES)
UND = {a: ("NIFTY" if a.endswith("_ni") or a == "hero" else "BANKNIFTY") for a in ARMS}


def bars5(ix):
    """5-minute bars of the day: starts 0, 5, ..., (o, h, l, c) and the minute their close is known (start + 4)."""
    out = []
    for s in range(0, N, 5):
        a = ix[s:s + 5]
        if not np.isfinite(a[:, 3]).any():
            out.append((s, np.nan, np.nan, np.nan, np.nan))
            continue
        c = a[np.isfinite(a[:, 3]), 3][-1]
        out.append((s, a[np.isfinite(a[:, 0]), 0][0], np.nanmax(a[:, 1]), np.nanmin(a[:, 2]), c))
    return out


def first_fill(path, k, within=3):
    """First printed minute in [k, k + within)."""
    for m in range(k, min(k + within, N)):
        if np.isfinite(path[m, 0]):
            return m
    return None


def make(arm, g: Grid, i, side, strike, k, sq, **kw):
    """A Trade entered at the open of minute k (first print within 3 minutes), or None."""
    today_ok = kw.pop("today_ok", True)
    path = g.opt(i, strike, side, today_ok)
    if path is None:
        return None
    c0 = first_fill(path, k)
    if c0 is None or c0 >= sq:
        return None
    hs = lib.K.HALF_SPREAD[g.und]
    e = buy_px(path[c0, 0], hs)
    lot = lot_of(g.und, g.dates[i])
    ix = g.days[i]["ix"]
    t = Trade(arm=arm, und=g.und, i=i, side=side, strike=strike, c0=c0, e=e, qty=lot, path=path, sq=sq,
              ix_entry=float(ix[k - 1, 3]), hs=hs, meta=dict(lot=lot), **kw)
    return t


# ------------------------------------------------------------------------------------------------ ORB family
def orb_family(arm, g: Grid):
    tp = 80.0 if arm == "orb_sweep" else 40.0
    ladder = ((0.25 * tp, 0.0), (0.5 * tp, 0.25 * tp), (0.75 * tp, 0.5 * tp))
    out = []
    for i in range(len(g.days)):
        if not g.ok(i):
            continue
        ix = g.days[i]["ix"]
        if not np.isfinite(ix[4, 3]) or g.chain(i) is None:
            continue
        b = bars5(ix)
        orh, orl = np.nanmax(ix[:50, 1]), np.nanmin(ix[:50, 2])
        w = orh - orl
        strike = math.floor(ix[4, 3] / 100 + 0.5) * 100
        if arm in ("orb", "orb_fresh"):
            first, last = 50, 280
        elif arm == "orb_sweep":
            first, last = 50, 310
        else:
            first, last = 75, 280
        per_side = {1: 0, -1: 0}
        n_day = 0
        free_from = 0          # next decision bar must START at or after this minute
        for s, o, h, l, c in b:
            if s < first or s > last or s < free_from or not np.isfinite(c):
                continue
            side = 0
            if arm in ("orb", "orb_fresh"):
                side = 1 if c > orh else -1 if c < orl else 0
                if side and arm == "orb_fresh":
                    pc = b[s // 5 - 1][4]
                    if np.isfinite(pc) and ((side > 0 and pc > orh) or (side < 0 and pc < orl)):
                        side = 0
                if side and per_side[side] >= 1:          # paper cap: 1 entry per side a day (since 13 Oct)
                    side = 0
            elif arm == "orb_sweep":
                side = -1 if (h > orh and c < orh) else 1 if (l < orl and c > orl) else 0
            else:
                side = -1 if (h >= orh - 0.1 * w and c < orh) else 1 if (l <= orl + 0.1 * w and c > orl) else 0
            if not side or (arm in ("orb_sweep", "range_fade") and n_day >= 2):
                continue
            k = s + 5
            path = g.opt(i, strike, side)
            if path is None:
                continue
            c0 = first_fill(path, k)
            if c0 is None or path[c0, 0] * 1.0005 - 40 <= 0:
                continue
            t = make(arm, g, i, side, strike, k, lib.SQ_1510, ladder=ladder)
            if t is None:
                continue
            t.stop0 = round(round((t.e - 40.0) / TICK) * TICK, 2)
            t.tgt = t.e + tp
            r = replay(t)
            out.append(t)
            per_side[side] += 1
            n_day += 1
            free_from = (r["exit_min"] // 5 + 1) * 5
    return out


# ------------------------------------------------------------------------------------------------ VIX divergence
def vixdiv(arm, g: Grid):
    out = []
    step = lib.STEP[g.und]
    for i in range(len(g.days)):
        if not g.ok(i):
            continue
        d = g.days[i]
        ix, vx = d["ix"], d["vix"]
        o0, v0 = ix[0, 0], vx[0]
        if not (np.isfinite(o0) and np.isfinite(v0)) or g.chain(i) is None:
            continue
        for T in (75, 135, 195, 255):            # 10:30, 11:30, 12:30, 13:30 as minute indices
            c, v = ix[T - 1, 3], vx[T - 1]
            if not (np.isfinite(c) and np.isfinite(v)):
                continue
            ri, rv = c / o0 - 1, v / v0 - 1
            side = -1 if (ri > 0.002 and rv > 0.02) else 1 if (ri < -0.002 and rv < -0.02) else 0
            if not side:
                continue
            atm = round(c / step) * step                 # half-to-even
            strike = atm - step if side > 0 else atm + step
            kw = {}
            if arm == "vixdiv_ni":
                kw["time_rule"] = (19, 0.05)
            t = make(arm, g, i, side, strike, T, lib.SQ_1510, **kw)
            if t is not None and arm == "vixdiv_ni":
                t.stop0 = floor_tick(t.e * 0.85)
            if t is not None:
                out.append(t)
            break
    return out


# ------------------------------------------------------------------------------------------------ Hero
def hero(g: Grid):
    out = []
    step = 50
    for i in range(len(g.days)):
        if not g.ok(i) or not g.is_expiry(i):
            continue
        c = g.chain(i)
        if c is None:
            continue
        _, ks, arr = c
        ix = g.days[i]["ix"]
        cl = ix[:, 3]
        # last-traded price and its age per strike/side
        ltp = np.full(arr.shape[:3], np.nan)
        age = np.full(arr.shape[:3], 99.0)
        last = np.full(arr.shape[1:3], np.nan)
        lastk = np.full(arr.shape[1:3], -99.0)
        for k in range(N):
            tr = np.isfinite(arr[k, :, :, 3])
            last = np.where(tr, arr[k, :, :, 3], last)
            lastk = np.where(tr, k, lastk)
            ltp[k] = last
            age[k] = k - lastk
        low = np.inf
        done = False
        for k in range(165, 330):               # from 12:00; window = bars closing 13:30-14:45 (k 254..329)
            if not np.isfinite(cl[k]):
                continue
            atm = round(cl[k] / step) * step
            j = np.searchsorted(ks, atm)
            if j >= len(ks) or ks[j] != atm or age[k, j, 0] > 2 or age[k, j, 1] > 2:
                continue
            st = ltp[k, j, 0] + ltp[k, j, 1]
            low = min(low, st)
            if k < 254 or done or k < 15 or not np.isfinite(cl[k - 15]):
                continue
            mv = cl[k] / cl[k - 15] - 1
            side = 1 if mv >= 0.0025 else -1 if mv <= -0.0025 else 0
            if not (side and st >= 1.15 * low):
                continue
            # nearest OTM option priced Rs 1-5 that traded in the last 5 bars
            cand = range(j + 1, len(ks)) if side > 0 else range(j - 1, -1, -1)
            pick = None
            for jj in cand:
                p = ltp[k, jj, 0 if side > 0 else 1]
                if np.isfinite(p) and 1.0 <= p <= 5.0 and age[k, jj, 0 if side > 0 else 1] <= 4:
                    pick = jj
                    break
            if pick is None:
                continue
            p = ltp[k, pick, 0 if side > 0 else 1]
            ask = p + TICK
            lim = math.floor(min(ask + (2 if ask < 2 - 1e-9 else 1) * TICK, p * 1.2 + 0.1) / TICK + 1e-6) * TICK
            path = arr[:, pick, 0 if side > 0 else 1, :].astype(np.float64)
            c0 = None
            for m in range(k + 1, min(k + 4, lib.SQ_1505)):
                if np.isfinite(path[m, 0]) and (path[m, 0] + TICK <= lim + 1e-9 or path[m, 2] + TICK <= lim + 1e-9):
                    c0 = m
                    break
            done = True
            if c0 is None:
                break
            e = round(min(lim, max(path[c0, 0], path[c0, 2]) + TICK), 2)
            lot = lot_of("NIFTY", g.dates[i])
            lots = int(5000 // (lim * lot))
            if lots < 1:
                break
            t = Trade(arm="hero", und="NIFTY", i=i, side=side, strike=float(ks[pick]), c0=c0, e=e, qty=lots * lot,
                      path=path, sq=lib.SQ_1505, tgt=floor_tick(20 * e), close_stop=0.4 * e, hero=True,
                      ix_entry=float(cl[k]), hs=0.0, meta=dict(lot=lot))
            out.append(t)
            break
    return out


# ------------------------------------------------------------------------------------------------ Pine samples
def _ema(x, n):
    a = 2.0 / (n + 1)
    out = np.empty_like(x)
    out[0] = x[0]
    for k in range(1, len(x)):
        out[k] = a * x[k] + (1 - a) * out[k - 1]
    return out


def _supertrend(h, l, c, n=10, mult=3.0):
    tr = np.maximum(h - l, np.maximum(abs(h - np.roll(c, 1)), abs(l - np.roll(c, 1))))
    tr[0] = h[0] - l[0]
    atr = np.empty_like(c)
    atr[:n] = np.nan
    atr[n - 1] = tr[:n].mean()
    for k in range(n, len(c)):
        atr[k] = (atr[k - 1] * (n - 1) + tr[k]) / n          # RMA, as ta.atr
    hl2 = (h + l) / 2
    up = hl2 - mult * atr
    dn = hl2 + mult * atr
    d = np.ones(len(c))
    for k in range(1, len(c)):
        if np.isfinite(up[k - 1]):
            up[k] = max(up[k], up[k - 1]) if c[k - 1] > up[k - 1] else up[k]
            dn[k] = min(dn[k], dn[k - 1]) if c[k - 1] < dn[k - 1] else dn[k]
        if d[k - 1] == -1 and c[k] > dn[k - 1]:
            d[k] = 1
        elif d[k - 1] == 1 and c[k] < up[k - 1]:
            d[k] = -1
        else:
            d[k] = d[k - 1]
    return d


def pine(arm, g: Grid):
    """Signals on a continuous 5-minute series (warm-up from earlier sessions), trades inside each session."""
    step = lib.STEP[g.und]
    rows = []
    for i, d in enumerate(g.days):
        for s, o, h, l, c in bars5(d["ix"]):
            if np.isfinite(c):
                rows.append((i, s, o, h, l, c))
    R = np.array(rows)
    o, h, l, c = R[:, 2], R[:, 3], R[:, 4], R[:, 5]
    if "ema" in arm:
        f, sl = _ema(c, 9), _ema(c, 21)
        pos = np.sign(f - sl)
        sig = np.zeros(len(c))
        sig[1:] = np.where((pos[1:] > 0) & (pos[:-1] <= 0), 1, np.where((pos[1:] < 0) & (pos[:-1] >= 0), -1, 0))
    else:
        dr = _supertrend(h, l, c)
        sig = np.zeros(len(c))
        sig[1:] = np.where((dr[1:] == 1) & (dr[:-1] == -1), 1, np.where((dr[1:] == -1) & (dr[:-1] == 1), -1, 0))
    by_day = {}
    for r, sgn in zip(R, sig):
        if sgn:
            by_day.setdefault(int(r[0]), []).append((int(r[1]), int(sgn)))
    out = []
    for i, sigs in by_day.items():
        if not g.ok(i):
            continue
        ix = g.days[i]["ix"]
        sigs = [(s, x) for s, x in sigs]
        opp = {}
        for n_, (s, x) in enumerate(sigs):
            nxt = next((s2 + 4 for s2, x2 in sigs[n_ + 1:] if x2 != x), None)
            opp[s] = nxt
        free_from = 0
        for s, x in sigs:
            k = s + 5
            if k < 5 or k > 345 or k < free_from:          # entries 09:20 .. 15:00
                continue
            strike = math.floor(ix[s + 4, 3] / step + 0.5) * step
            t = make(arm, g, i, x, strike, k, lib.SQ_1515, today_ok=False,
                     ladder=((15.0, 0.0), (30.0, 15.0), (45.0, 30.0)), pine_trail=True)
            if t is None:
                continue
            t.stop0 = round(round((t.e - 30.0) / TICK) * TICK, 2)
            t.tgt = t.e + 60.0
            ex = opp[s]
            if "ema" in arm:                      # strategy.exit: index stop 60 / target 120 from the fill
                ref = ix[t.c0, 0] if np.isfinite(ix[t.c0, 0]) else t.ix_entry
                for m in range(t.c0, lib.SQ_1515):
                    if ex is not None and m >= ex:
                        break
                    if (x > 0 and (ix[m, 2] <= ref - 60 or ix[m, 1] >= ref + 120)) or \
                            (x < 0 and (ix[m, 1] >= ref + 60 or ix[m, 2] <= ref - 120)):
                        ex = m
                        break
            t.arm_exit = ex
            r = replay(t)
            out.append(t)
            free_from = r["exit_min"]          # a switch sells and buys the other side on the same minute
    return out


def build(arm, grids):
    g = grids[UND[arm]]
    if arm in ORB_FAMILY:
        return orb_family(arm, g)
    if arm.startswith("vixdiv"):
        return vixdiv(arm, g)
    if arm == "hero":
        return hero(g)
    if arm.startswith("pine"):
        return pine(arm, g)
    if arm == "liq_bn":
        import liquidity
        return liquidity.trades(g)
    raise KeyError(arm)
