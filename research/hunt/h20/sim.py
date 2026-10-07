"""h20: the ORB-family arms' profit lock, measured on real BANKNIFTY option minutes (see PREREG.md).

Signals = the app's OrbRules / SweepRules / RangeFadeRules (port of research/hunt/h4/comps.py with the app's CURRENT
decision windows: ORB / Fresh to the 13:55 bar, Sweep to 14:25, Fade 10:30-13:55). Exits by a custom vectorised
simulator with two modes:
  app   resting -40 SL-M on the minute low; lock / trail / target / time exit decided on the minute CLOSE (peak = best
        close before this minute), sold at the NEXT minute's open - what OrbArms.priceCheck does on a sampled LTP.
  tick  optimistic: peak from minute highs, lock and target rest on the low / high and fill at the level (or gap open)
        - what ArmsBacktest / research/PROFIT_LOCK.md assume.

    OBUY_CACHE=<scratch>/hunt/h20/cache flock <scratch>/obuy.lock python3 -I research/hunt/h20/sim.py prep
"""
from __future__ import annotations

import dataclasses
import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")  # pandas deps under python -I

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import obuy  # noqa: E402,F401
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, Fills, floor_tick  # noqa: E402
from obuy.engine import Execution, StrikeRule, prepare_many, Pack  # noqa: E402
from obuy.strategies.common import day_arrays  # noqa: E402

OUT = os.path.join(C.CACHE, "h20")
NEG = -1e18
SQ = 15 * 60 + 10
HOLDOUT = pd.Timestamp("2025-10-01").date()

# arm: (kind, first decision bar k, last decision bar k inclusive, max entries a day, app target points)
ARMS = {"orb": ("orb", 10, 56, 99, 40.0), "orb_fresh": ("fresh", 10, 56, 99, 40.0),
        "orb_sweep": ("sweep", 10, 62, 2, 80.0), "range_fade": ("fade", 15, 56, 2, 40.0)}


def atm(spot, step):
    return float(np.floor(spot / step + 0.5) * step)


def signals(mk, kind):
    k0, k1 = [(v[1], v[2]) for v in ARMS.values() if v[0] == kind][0]
    ix = mk.index("BANKNIFTY")
    out = []
    for d in ix.days:
        if not ix.d[d]["real"]:
            continue
        o, h, l, c = day_arrays(ix, d)
        nb = C.W // 5
        bh = np.array([np.nanmax(h[5 * k:5 * k + 5]) if np.isfinite(h[5 * k:5 * k + 5]).any() else np.nan for k in range(nb)])
        bl = np.array([np.nanmin(l[5 * k:5 * k + 5]) if np.isfinite(l[5 * k:5 * k + 5]).any() else np.nan for k in range(nb)])
        bc = np.array([c[5 * k + 4] if np.isfinite(c[5 * k + 4]) else np.nan for k in range(nb)])
        if np.isnan(bh[:10]).sum() > 2 or not np.isfinite(bc[1]):
            continue
        orh, orl = np.nanmax(bh[:10]), np.nanmin(bl[:10])
        w = orh - orl
        strike = atm(bc[1], 100)
        for k in range(k0, k1 + 1):
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
                out.append(dict(und="BANKNIFTY", day=d, sig_min=C.OPEN_M + 5 * k + 4, side=s, book=kind,
                                ref_spot=float(bc[k]), strike=strike))
    return pd.DataFrame(out)


EXES = {"app": Execution(fills=Fills(), costs=Costs(), expiry="allow", min_premium=40.0),
        "gross": Execution(fills=Fills(mode="flat", pts=0.0), costs=Costs(mode="flat", per_order=0.0), expiry="allow", min_premium=40.0),
        "real": Execution(fills=Fills(mode="liq"), costs=Costs(), expiry="allow", min_premium=40.0)}


def prep():
    from obuy.data import market
    os.makedirs(OUT, exist_ok=True)
    mk = market()
    jobs, keys = [], []
    for arm, (kind, k0, k1, mx, tp) in ARMS.items():
        s = signals(mk, kind)
        print(arm, len(s), flush=True)
        win = (C.OPEN_M + 5 * k0 + 4, C.OPEN_M + 5 * k1 + 4)
        for en, exe in EXES.items():
            jobs.append((s, StrikeRule(0), exe, 5 if en == "app" else 0, win, False))
            keys.append((arm, en))
    mk.release()
    packs = prepare_many(jobs)
    res = {}
    for (arm, en), (pk, pool) in zip(keys, packs):
        res[(arm, en)] = (pk.meta, pool.meta if pool is not None else None)
    store = packs[0][0].store
    with open(os.path.join(OUT, "packs.pkl"), "wb") as f:
        pickle.dump((res, store.arr), f, protocol=4)
    print("saved", sum(v.nbytes for v in store.arr.values()) / 1e6, "MB")


def load():
    from obuy.engine import Store
    with open(os.path.join(OUT, "packs.pkl"), "rb") as f:
        res, arr = pickle.load(f)
    st = Store()
    st.arr = arr
    return {k: (Pack(m, st, 1), Pack(p, st, 1) if p is not None and len(p) else None) for k, (m, p) in res.items()}


# ------------------------------------------------------------------------------------------------ exits
@dataclasses.dataclass(frozen=True)
class Spec:
    name: str
    stop: float = 40.0
    tgt: float | None = 40.0
    ladder: tuple = ()              # ((reach_pts, lock_pts), ...)
    trail: tuple | None = None      # (arm_pts, keep_frac)
    time: tuple | None = None       # (minutes, min_gain_pts)
    be_floor: bool = True


def app_ladder(tp):
    return ((0.25 * tp, 0.0), (0.5 * tp, 0.25 * tp), (0.75 * tp, 0.5 * tp))


def sim(pk: Pack, sp: Spec, exe: Execution, mode="app", chunk=6000):
    out = [_sim(pk, slice(i, min(i + chunk, len(pk))), sp, exe, mode) for i in range(0, len(pk), chunk)]
    return pd.concat(out, ignore_index=True) if out else pd.DataFrame()


def _sim(pk, sl, sp, exe, mode):
    meta = pk.meta.iloc[sl].reset_index(drop=True)
    N = len(meta)
    O, H, L, Cl = (pk.get(k, sl) for k in ("O", "H", "L", "Cl"))
    c0 = meta.c0.values.astype(np.int64)
    e = meta.e.values.astype(np.float64)
    qty = meta.qty.values.astype(np.float64)
    dn = meta.dn.values
    rows = np.arange(N)
    cols = np.arange(C.W)[None, :]
    valid = ~np.isnan(Cl)
    after = cols >= c0[:, None]
    sqc = SQ - C.OPEN_M
    barok = valid & after & (cols < sqc)
    t = floor_tick(e - sp.stop)
    trig = np.where((t > C.TICK + 1e-9) & (t < e), t, np.nan)
    trig_m = np.where(np.isnan(trig), NEG, trig)[:, None]
    tgt = e + sp.tgt if sp.tgt else np.full(N, np.inf)
    be = e + np.maximum(exe.costs.rt_per_unit(e, qty, dn, False), 0.0) if sp.be_floor else e.copy()
    src = H if mode == "tick" else Cl
    hm = np.where(barok, src, NEG)
    inc = np.maximum.accumulate(hm, axis=1)
    peak = np.empty_like(inc)
    peak[:, 0] = NEG
    peak[:, 1:] = inc[:, :-1]
    peak = np.maximum(peak, e[:, None])
    lock = np.full((N, C.W), NEG)
    for a, b in sp.ladder:
        lv = np.maximum(e + b, be)
        armed = peak >= (e + a - 1e-9)[:, None]
        lock = np.maximum(lock, np.where(armed & (lv[:, None] < peak), lv[:, None], NEG))
    if sp.trail:
        a, kf = sp.trail
        lv = np.maximum(e[:, None] + kf * (peak - e[:, None]), be[:, None])
        armed = peak >= (e + a - 1e-9)[:, None]
        lock = np.maximum(lock, np.where(armed & (lv < peak), lv, NEG))
    BIG = C.W

    def first(m):
        return np.where(m.any(axis=1), m.argmax(axis=1), BIG)

    okc = barok & (cols <= sqc - 2)
    # decisions on closes (app mode: lock, target; both modes: time exit)
    k_ts = np.full(N, BIG)
    if sp.time:
        T, g = sp.time
        kT = c0 + T - 1
        lvc = np.maximum.accumulate(np.where(valid, cols, -1), axis=1)
        kTc = np.clip(kT, 0, C.W - 1)
        ltp = Cl[rows, np.maximum(lvc[rows, kTc], 0)]
        k_ts = np.where((kT <= sqc - 2) & (ltp < e + g - 1e-9), kT, BIG)
    k_sq = np.maximum(sqc - 1, c0)
    if mode == "tick":
        rest = np.maximum(trig_m, lock)
        kb = first(barok & ((L <= rest) | (H >= tgt[:, None])))
        k_lk = k_tg = np.full(N, BIG)
    else:
        kb = first(barok & (L <= trig_m))            # only the resting -40 stop fills intrabar
        k_lk = first(okc & (Cl <= lock))
        k_tg = first(okc & (Cl >= tgt[:, None]))
    stack = np.stack([k_lk, k_tg, k_ts, k_sq])
    which = stack.argmin(axis=0)
    km = stack[which, rows]
    names = np.array(["lock", "target", "time", "square_off"], dtype=object)
    bar = kb <= km
    why = np.where(bar, "", names[which]).astype(object)
    xcol = np.where(bar, kb, 0)
    raw = np.full(N, np.nan)
    stopfill = np.zeros(N, bool)
    if bar.any():
        b = np.nonzero(bar)[0]
        k = kb[b]
        o = O[b, k]
        if mode == "tick":
            r_at = np.maximum(trig_m[b, 0], lock[b, k])
            hr = L[b, k] <= r_at
            is_stop = hr & (trig_m[b, 0] >= lock[b, k])
            is_lock = hr & ~is_stop
            raw[b] = np.where(is_stop, np.minimum(trig_m[b, 0], o), np.where(is_lock, np.minimum(lock[b, k], o), np.maximum(tgt[b], o)))
            why[b] = np.where(is_stop, "stop", np.where(is_lock, "lock", "target"))
            stopfill[b] = is_stop
        else:
            raw[b] = np.minimum(trig_m[b, 0], o)
            why[b] = "stop"
            stopfill[b] = True
    mm = np.nonzero(~bar)[0]
    if len(mm):
        nv = np.where(valid, cols, BIG)
        nv = np.minimum.accumulate(nv[:, ::-1], axis=1)[:, ::-1]
        lvx = np.maximum.accumulate(np.where(valid, cols, -1), axis=1)[:, -1]
        k1 = np.minimum(km[mm] + 1, C.W - 1)
        nxt = np.where(km[mm] + 1 <= C.W - 1, nv[mm, k1], BIG)
        has = nxt < BIG
        col = np.where(has, nxt, lvx[mm])
        xcol[mm] = col
        raw[mm] = np.where(has, O[mm, col], Cl[mm, col])
    fl, co = exe.fills, exe.costs
    x = fl.sell(raw, None, stop=False)
    if stopfill.any():
        x = np.where(stopfill, fl.sell(raw, None, stop=True), x)
    e1 = meta.e1.values.astype(np.float64)
    gross = (x - e1) * qty
    chg = co.charge(True, e1, qty, dn, False) + co.charge(False, x, qty, dn, False)
    # MFE until the exit bar (inclusive), on highs and on closes; lock level at the decision
    upto = after & (cols <= xcol[:, None]) & valid
    mfe_h = np.max(np.where(upto, H, NEG), axis=1) - e
    mfe_c = np.max(np.where(upto, Cl, NEG), axis=1) - e
    lk_at = np.where(why == "lock", lock[rows, np.minimum(km, C.W - 1)], np.nan)
    if mode == "tick":
        lk_at = np.where(why == "lock", lock[rows, np.minimum(xcol, C.W - 1)], np.nan)
    res = meta[["cand", "parent", "und", "book", "day", "sig_min", "gate", "side", "strike", "lot", "qty"]].copy() \
        if "parent" in meta.columns else meta[["cand", "und", "book", "day", "sig_min", "gate", "side", "strike", "lot", "qty"]].copy()
    res["year"] = [d.year for d in meta.day]
    res["entry_min"] = c0 + C.OPEN_M
    res["exit_min"] = xcol + C.OPEN_M
    res["entry"] = e
    res["exit"] = x
    res["why"] = why
    res["gross"] = gross
    res["charges"] = chg
    res["net"] = gross - chg
    res["mfe_h"] = mfe_h
    res["mfe_c"] = mfe_c
    res["lock_at"] = lk_at
    res["be"] = be - e
    return res[c0 < sqc - 1].reset_index(drop=True)


def positions(tr, max_per_day):
    """The app's position rule: one at a time per arm; after an exit the next decision bar must START after the exit's
    5-minute bar (OrbRules 'cooling_down_after_exit'); Sweep / Fade at most max_per_day entries a day."""
    if tr.empty:
        return tr
    t = tr.sort_values(["day", "sig_min", "cand"], kind="stable")
    keep = np.zeros(len(t), bool)
    day, sm, xm = t.day.values, t.sig_min.values, t.exit_min.values
    cur, block, n = None, -1, 0
    for i in range(len(t)):
        if day[i] != cur:
            cur, block, n = day[i], -1, 0
        bar_start = sm[i] - 4
        if bar_start <= block or n >= max_per_day:
            continue
        keep[i] = True
        n += 1
        ex = xm[i]
        block = ex - ((ex - C.OPEN_M) % 5)          # the exit's bar start: decide only on bars starting after it
    return t[keep].reset_index(drop=True)


if __name__ == "__main__":
    if sys.argv[1] == "prep":
        prep()
