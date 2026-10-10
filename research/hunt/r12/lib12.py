"""R12 trade manager: shared code - arm loading, per-trade feature matrices, the minute replay (original exits + the
manager's EXIT / TRAIL / EXTEND), costs, random twins, BH and White's reality check.

Conventions (as the source studies):
  * a column k is the 1-minute bar starting at 09:15 + k; decisions are taken on bar k's CLOSE and filled at the next
    printed bar's OPEN (market, -5 bps); resting stops / locks fill on the bar's LOW at the level or the gap open (-10 bps),
    stop-first when a bar touches both a stop and a target;
  * the manager may only lower risk: it exits early, raises a lock (never above the price, never down), or - EXTEND -
    raises the target ONLY together with a ratcheting lock (the app's TradeManager.levels formula);
  * costs: obuy app fills + app charges (STT 0.15%) + the h24 real half-spread on entry and exit (0.16% NIFTY/BANKNIFTY).
    Hero keeps its own 1-tick LIMIT fill model (its source), no % half-spread.
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy import config as C  # noqa: E402
from obuy.costs import Costs, Fills, floor_tick  # noqa: E402

S = C.SCRATCH
OUT = os.path.join(S, "hunt", "r12")
W = C.W
NEG = -1e18
HOLD0 = pd.Timestamp("2025-10-01")
HOLD1 = pd.Timestamp("2026-10-06")
HS = {"NIFTY": 0.0016, "BANKNIFTY": 0.0016}
SQ = 15 * 60 + 10 - C.OPEN_M          # 345: the 15:10 square-off column (obuy sq_off)
HERO_E = 15 * 60 + 4 - C.OPEN_M       # 349: Hero's 15:05 exit (close of the bar starting 15:04)
TICK = 0.05
FILLS, COSTS = Fills(), Costs()

ARMS = ["liq_bn", "orb", "orb_fresh", "orb_sweep", "range_fade", "vixdiv_ni", "vixdiv_ba", "hero"]
NAMES = {"liq_bn": "Liquidity BANKNIFTY 15+5", "orb": "ORB", "orb_fresh": "ORB Fresh", "orb_sweep": "ORB Sweep",
         "range_fade": "Range Fade", "vixdiv_ni": "VIX divergence NIFTY", "vixdiv_ba": "VIX divergence BANKNIFTY",
         "hero": "Expiry Hero (NIFTY, F07)"}


# ------------------------------------------------------------------------------------------------ arms
class Arm:
    def __init__(self, name):
        z = np.load(os.path.join(OUT, f"arm_{name}.npz"), allow_pickle=True)
        self.name = name
        self.O, self.H, self.L, self.Cl, self.V = (z[k].astype(np.float64) for k in ("O", "H", "L", "Cl", "V"))
        for k in ("O", "H", "L", "Cl"):
            setattr(self, k, np.round(getattr(self, k), 2))
        self.c0 = z["m_c0"].astype(np.int64)
        self.e = z["m_e"]
        self.e1 = z["m_e1"]
        self.qty = z["m_qty"]
        self.lot = z["m_lot"]
        self.side = z["m_side"].astype(np.int64)
        self.dn = z["m_dn"].astype(np.int64)
        self.und = z["m_und"]
        self.book = z["m_book"]
        self.idx_stop = z["m_idx_stop"]
        self.idx_target = z["m_idx_target"]
        self.exit_at = z["m_exit_at"]
        self.src = {k[4:]: z[k] for k in z.files if k.startswith("src_")}
        self.tp = float(z["tp"][0]) if "tp" in z.files else None
        self.day = pd.to_datetime(self.dn, unit="D")
        self.n = len(self.e)
        self.hs = np.array([HS.get(u, 0.0) for u in self.und]) if name != "hero" else np.zeros(self.n)
        self.kind = "hero" if name == "hero" else ("orb" if name in ("orb", "orb_fresh", "orb_sweep", "range_fade") else "obuy")
        self.be = self.e + np.maximum(COSTS.rt_per_unit(self.e, self.qty, self.dn, False), 0.0)
        # original premium stop / time stop per arm
        if self.kind == "orb":
            t = floor_tick(self.e - 40.0)
            self.trig = np.where((t > TICK + 1e-9) & (t < self.e), t, np.nan)
            self.tgt = self.e + self.tp
            self.ladder = ((0.25 * self.tp, 0.0), (0.5 * self.tp, 0.25 * self.tp), (0.75 * self.tp, 0.5 * self.tp))
            self.time = None
        elif self.kind == "obuy":
            liqx = name == "liq_bn" or name == "vixdiv_ni"
            t = floor_tick(self.e * 0.85)
            self.trig = np.where(liqx & (t >= TICK) & (t < self.e), t, np.nan) if liqx else np.full(self.n, np.nan)
            self.tgt = np.full(self.n, np.inf)
            self.ladder = ()
            self.time = (20, 0.05) if liqx else None
        else:
            self.trig = np.full(self.n, np.nan)
            self.tgt = np.floor(self.e * 5 / TICK + 1e-6) * TICK
            self.ladder = ()
            self.time = None
        self.sqc = HERO_E if self.kind == "hero" else SQ

    def design(self):
        return self.day < HOLD0

    def holdout(self):
        return (self.day >= HOLD0) & (self.day <= HOLD1)


# ------------------------------------------------------------------------------------------------ features
_PANEL = {}


def panel(u):
    if u not in _PANEL:
        z = np.load(os.path.join(S, "hunt", "r11", f"panel_{u}.npz"))
        d = {k: z[k] for k in ("days", "exp", "o", "h", "l", "c", "vix", "optv", "flow", "cedoi2", "pedoi2")}
        d["pos"] = {int(x): i for i, x in enumerate(d["days"])}
        _PANEL[u] = d
    return _PANEL[u]


def ffill(a):
    return pd.DataFrame(a.T).ffill().values.T


def rolling_sum(a, n):
    cs = np.concatenate([np.zeros((a.shape[0], 1)), np.cumsum(np.nan_to_num(a), axis=1)], axis=1)
    out = cs[:, n:] - cs[:, :-n]
    return np.concatenate([np.full((a.shape[0], n - 1), np.nan), out], axis=1)


def rolling_mean_prev(a, n):
    """mean of the n bars BEFORE each bar (NaN-aware)."""
    x = np.nan_to_num(a)
    k = (~np.isnan(a)).astype(float)
    cs = np.concatenate([np.zeros((a.shape[0], 1)), np.cumsum(x, axis=1)], axis=1)
    ck = np.concatenate([np.zeros((a.shape[0], 1)), np.cumsum(k, axis=1)], axis=1)
    idx = np.arange(a.shape[1])
    lo = np.maximum(idx - n, 0)
    s = cs[:, idx] - cs[:, lo]
    c = ck[:, idx] - ck[:, lo]
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(c > 0, s / c, np.nan)


def rsi(c, n=14):
    d = np.diff(c, axis=1, prepend=c[:, :1])
    up, dn = np.maximum(d, 0), np.maximum(-d, 0)
    au, ad = np.zeros_like(c), np.zeros_like(c)
    au[:, 0], ad[:, 0] = up[:, 0], dn[:, 0]
    a = 1.0 / n
    for k in range(1, c.shape[1]):
        au[:, k] = (1 - a) * au[:, k - 1] + a * up[:, k]
        ad[:, k] = (1 - a) * ad[:, k - 1] + a * dn[:, k]
    with np.errstate(invalid="ignore", divide="ignore"):
        return 100 - 100 / (1 + au / np.maximum(ad, 1e-12))


def day_features(u):
    """Per-day [ndays, W] feature panels for one index (computed once; only data up to each column)."""
    P = panel(u)
    if "F" in P:
        return P["F"]
    c = ffill(P["c"].astype(np.float64))
    h, l = P["h"].astype(np.float64), P["l"].astype(np.float64)
    h = np.where(np.isnan(h), c, h)
    l = np.where(np.isnan(l), c, l)
    v = np.nan_to_num(P["optv"].astype(np.float64))
    tp = (h + l + c) / 3
    w = v + 1e-9
    cw = np.cumsum(w, axis=1)
    vw = np.cumsum(w * tp, axis=1) / cw
    vsd = np.sqrt(np.maximum(np.cumsum(w * tp * tp, axis=1) / cw - vw * vw, 0))
    # developing value area (TPO proxy: 15th-85th percentile of the session's minute closes so far)
    vah, val = np.full_like(c, np.nan), np.full_like(c, np.nan)
    for k in range(4, W):
        q = np.nanpercentile(c[:, :k + 1], [15, 85], axis=1)
        val[:, k], vah[:, k] = q[0], q[1]
    # prior day's value area (same proxy over the whole prior session)
    pq = np.nanpercentile(c, [15, 85], axis=1)
    pval, pvah = np.r_[np.nan, pq[0][:-1]], np.r_[np.nan, pq[1][:-1]]
    vix = ffill(P["vix"].astype(np.float64))
    vix5 = np.full_like(vix, np.nan)
    vix5[:, 5:] = vix[:, 5:] / vix[:, :-5] - 1
    # OI: put writing minus call writing ATM+-2 over 15 minutes (bullish when > 0), scaled by the index's design std
    oi = np.nan_to_num(P["pedoi2"].astype(np.float64)) - np.nan_to_num(P["cedoi2"].astype(np.float64))
    oi15 = rolling_sum(oi, 15)
    flow10 = rolling_sum(np.nan_to_num(P["flow"].astype(np.float64)), 10)
    days = pd.to_datetime(P["days"], unit="D")
    dz = days < HOLD0
    oi_sd = np.nanstd(oi15[dz])
    fl_sd = np.nanstd(flow10[dz])
    roc10 = np.full_like(c, np.nan)
    roc10[:, 10:] = c[:, 10:] / c[:, :-10] - 1
    vz = v / np.maximum(rolling_mean_prev(np.where(v > 0, v, np.nan), 30), 1.0)
    F = dict(c=c, h=h, l=l, vw=vw, vsd=vsd, vah=vah, val=val, pvah=pvah, pval=pval, vix=vix, vix5=vix5,
             oiz=oi15 / oi_sd, flz=flow10 / fl_sd, rsi=rsi(c), roc10=roc10, optvz=vz, exp=P["exp"])
    P["F"] = F
    return F


def trade_features(arm: Arm):
    """{name: [n, W]} for the arm's trades (index features from the r11 panel of the trade's index and day)."""
    out = {}
    rows = {}
    for u in np.unique(arm.und):
        P = panel(u)
        F = day_features(u)
        sel = np.nonzero(arm.und == u)[0]
        pos = np.array([P["pos"].get(int(d), -1) for d in arm.dn[sel]])
        rows[u] = (sel, pos)
        for k, a in F.items():
            if a.ndim == 1 and k in ("pvah", "pval"):
                a = np.repeat(a[:, None], W, axis=1)
            if a.ndim != 2:
                continue
            if k not in out:
                out[k] = np.full((arm.n, W), np.nan)
            ok = pos >= 0
            out[k][sel[ok]] = a[pos[ok]]
    exp = np.zeros(arm.n, bool)
    for u, (sel, pos) in rows.items():
        ok = pos >= 0
        exp[sel[ok]] = panel(u)["exp"][pos[ok]]
    out["exp"] = exp
    # option-path features (the held contract)
    rng = arm.H - arm.L
    out["oatr"] = rolling_mean_prev(rng, 10)
    v = arm.V
    out["ovz"] = v / np.maximum(rolling_mean_prev(np.where(v > 0, v, np.nan), 30), 1.0)
    return out


# ------------------------------------------------------------------------------------------------ replay
def lastvalid_fill(arm):
    """nv[i, k]: first printed bar >= k (W if none); lv[i]: last printed bar; lastc[i, k]: last printed close <= k."""
    valid = ~np.isnan(arm.Cl)
    cols = np.arange(W)[None, :]
    nv = np.where(valid, cols, W)
    nv = np.minimum.accumulate(nv[:, ::-1], axis=1)[:, ::-1]
    lvi = np.maximum.accumulate(np.where(valid, cols, -1), axis=1)
    return nv, lvi


class Manager:
    """A configured manager: exit votes (bool [n, W], decided at close k), a lock proposal ([n, W], level wanted from
    bar k+1 on; NEG = none), an extension vote (bool [n, W]) and its mode. None parts do nothing."""

    def __init__(self, exit=None, lock=None, extend=None, ext_mode=None, label=""):
        self.exit, self.lock, self.extend, self.ext_mode, self.label = exit, lock, extend, ext_mode, label


def replay(arm: Arm, mg: Manager | None = None, sel=None):
    """Replays the arm's trades (rows `sel`) with its original exits and, if given, the manager. Returns a DataFrame:
    one row per trade (net, gross, charges, exit col, why, n_ext, acted)."""
    idx = np.arange(arm.n) if sel is None else np.nonzero(sel)[0] if np.asarray(sel).dtype == bool else np.asarray(sel)
    if arm.kind == "hero":
        return _replay_hero(arm, mg, idx)
    n = len(idx)
    O, H, L, Cl = arm.O[idx], arm.H[idx], arm.L[idx], arm.Cl[idx]
    c0, e, e1, qty, side = arm.c0[idx], arm.e[idx], arm.e1[idx], arm.qty[idx], arm.side[idx]
    be = arm.be[idx]
    trig = np.where(np.isnan(arm.trig[idx]), NEG, arm.trig[idx])
    tgt = arm.tgt[idx].astype(np.float64).copy()
    lvl = arm.idx_stop[idx] if arm.kind == "obuy" else np.full(n, np.nan)
    itl = arm.idx_target[idx].astype(np.float64).copy() if arm.kind == "obuy" else np.full(n, np.nan)
    xa = arm.exit_at[idx] if arm.kind == "obuy" else np.full(n, np.nan)
    kx = np.where(np.isfinite(xa), np.maximum(np.nan_to_num(xa, nan=0).astype(np.int64) - 1 - C.OPEN_M, c0), 10 ** 6)
    F = _IX.get(arm.name)
    IH, IL = (F["h"][idx], F["l"][idx]) if F is not None else (None, None)
    sqc = arm.sqc
    kT = c0 + arm.time[0] - 1 if arm.time else np.full(n, 10 ** 6)
    gT = arm.time[1] if arm.time else 0.0
    k_sq = np.maximum(sqc - 1, c0)
    tgt0 = tgt.copy()
    itl0 = itl.copy()

    open_ = np.ones(n, bool)
    pend = np.zeros(n, bool)
    pwhy = np.zeros(n, np.int8)
    xcol = np.full(n, -1)
    raw = np.full(n, np.nan)
    stopf = np.zeros(n, bool)
    why = np.zeros(n, np.int8)        # 1 stop 2 lock 3 target 4 index_stop 5 index_target 6 time 7 exit_at 8 sq 9 mgr_exit 10 mgr_lock
    peak = e.copy()                   # best high BEFORE this bar (ladder)
    mlock = np.full(n, NEG)           # manager lock in force this bar
    lastc = np.full(n, np.nan)
    next_ = np.zeros(n, np.int8)
    n_ext = np.zeros(n, np.int64)
    ext_on = mg is not None and mg.extend is not None
    mex = mg.exit[idx] if (mg is not None and mg.exit is not None) else None
    mlk = mg.lock[idx] if (mg is not None and mg.lock is not None) else None
    mext = mg.extend[idx] if ext_on else None
    last_ext = np.full(n, -10 ** 6)
    for k in range(W):
        act = open_ & (k >= c0)
        if not act.any():
            if (k > c0).all():
                break
            continue
        v = ~np.isnan(Cl[:, k])
        a = act & v
        # 1) pending market exits fill at this bar's open
        f = a & pend
        if f.any():
            raw[f] = O[f, k]
            xcol[f] = k
            why[f] = pwhy[f]
            open_[f] = False
            a = a & ~f
        bar = a & (k < sqc)
        # 2) resting stop / ladder lock / manager lock on the low
        lad = np.full(n, NEG)
        for ra, rb in arm.ladder:
            lv = np.maximum(e + rb, be)
            lad = np.where((peak >= e + ra - 1e-9) & (lv < peak), np.maximum(lad, lv), lad)
        rest = np.maximum(np.maximum(trig, lad), mlock)
        hit = bar & (L[:, k] <= rest)
        if hit.any():
            o = O[hit, k]
            r = rest[hit]
            raw[hit] = np.minimum(r, np.where(np.isnan(o), r, o))
            xcol[hit] = k
            is_stop = trig[hit] >= np.maximum(lad[hit], mlock[hit])
            is_m = ~is_stop & (mlock[hit] > lad[hit])
            why[hit] = np.where(is_stop, 1, np.where(is_m, 10, 2))
            stopf[hit] = is_stop | is_m
            open_[hit] = False
            bar = bar & ~hit
        # 3) premium target (ORB: an app check on the high, sold at the next open)
        dec = act & open_ & ~pend & (k <= sqc - 2)
        if arm.kind == "orb":
            th = bar & (k <= sqc - 2) & (H[:, k] >= tgt)
            if th.any():
                pend[th], pwhy[th] = True, 3
        # 4) close decisions (filled next open)
        dec = dec & ~pend
        if IH is not None:
            ih, il = IH[:, k], IL[:, k]
            st = dec & np.where(side > 0, il < lvl, ih > lvl)
            pend[st], pwhy[st] = True, 4
            dec = dec & ~st
            tg = dec & np.where(side > 0, ih >= itl, il <= itl)
            if ext_on and mg.ext_mode == "index" and tg.any():
                ok = tg & mext[:, k] & (n_ext < 2) & (k <= sqc - 16) & (k - last_ext >= 1)
                if ok.any():
                    px = Cl[:, k]
                    step = 0.5 * np.abs(itl0 - _REF[arm.name][idx])
                    itl[ok] = itl[ok] + side[ok] * step[ok]
                    n_ext[ok] += 1
                    last_ext[ok] = k
                    lk = _ext_lock(e[ok], be[ok], px[ok], tgt_old=None, lock=mlock[ok])
                    mlock[ok] = np.maximum(mlock[ok], lk)
                    tg = tg & ~ok
            pend[tg], pwhy[tg] = True, 5
            dec = dec & ~tg
        lastc = np.where(v, Cl[:, k], lastc)
        ts = dec & (k == kT) & (lastc < e * (1 + gT) - 1e-9)
        pend[ts], pwhy[ts] = True, 6
        dec = dec & ~ts
        xe = dec & (k == kx)
        pend[xe], pwhy[xe] = True, 7
        dec = dec & ~xe
        if mex is not None:
            me = dec & mex[:, k]
            pend[me], pwhy[me] = True, 9
            dec = dec & ~me
        sq = act & open_ & ~pend & (k == k_sq)
        pend[sq], pwhy[sq] = True, 8
        # 5) after the close: peak, manager lock, extension (premium targets)
        upd = act & open_ & (k < sqc)
        peak = np.where(upd & ~np.isnan(H[:, k]), np.maximum(peak, H[:, k]), peak)
        if ext_on and mg.ext_mode == "premium":
            px = Cl[:, k]
            near = e + 0.9 * (tgt - e)
            ok = upd & ~pend & v & mext[:, k] & (px >= near) & (n_ext < 2) & (k <= sqc - 16) & (k - last_ext >= 1)
            if ok.any():
                step = np.minimum(0.5 * (tgt0 - e), np.where(np.isfinite(_ATR[arm.name][idx, k]), 3 * _ATR[arm.name][idx, k], 0.25 * (tgt0 - e)))
                lk = _ext_lock(e[ok], be[ok], px[ok], tgt_old=tgt[ok], lock=mlock[ok])
                tgt[ok] = np.floor((tgt[ok] + step[ok]) / TICK + 1e-9) * TICK
                mlock[ok] = np.maximum(mlock[ok], lk)
                n_ext[ok] += 1
                last_ext[ok] = k
        if mlk is not None:
            pr = mlk[:, k]
            cap = np.floor(Cl[:, k] * 0.99 / TICK + 1e-9) * TICK
            pr = np.where(v & (pr < cap), pr, NEG)
            mlock = np.where(upd & ~pend, np.maximum(mlock, pr), mlock)
        if ext_on and n_ext.any():
            # between extensions the lock trails new highs at half the open profit (the app's trail)
            tr = np.ceil((e + 0.5 * (peak - e)) / TICK - 1e-9) * TICK
            cap = np.floor(np.nan_to_num(Cl[:, k], nan=-1) * 0.99 / TICK + 1e-9) * TICK
            mlock = np.where((n_ext > 0) & upd & ~pend & (tr < cap), np.maximum(mlock, tr), mlock)
    # never-filled pending / still open: the last printed close
    nv, lvi = lastvalid_fill(type("A", (), {"Cl": Cl})())
    left = open_
    if left.any():
        li = np.nonzero(left)[0]
        lc = lvi[li, -1]
        raw[li] = Cl[li, np.maximum(lc, 0)]
        xcol[li] = lc
        why[li] = np.where(pend[li], pwhy[li], 8)
    x = FILLS.sell(raw, None, stop=False)
    x = np.where(stopf, FILLS.sell(raw, None, stop=True), x)
    gross = (x - e1) * qty
    dn = arm.dn[idx]
    chg = COSTS.charge(True, e1, qty, dn, False) + COSTS.charge(False, x, qty, dn, False)
    spread = arm.hs[idx] * (e1 + x) * qty
    net = gross - chg - spread
    return pd.DataFrame(dict(i=idx, day=arm.day[idx], xcol=xcol, exit=x, why=why, gross=gross, charges=chg + spread,
                             net=net, n_ext=n_ext))


def _ext_lock(e, be, px, tgt_old, lock):
    """TradeManager.levels: the lock on an extension = max(lock, BE + charges, entry + 50% of the open profit,
    old target - 25% of the entry-to-old-target gap), kept at least 1% (and a tick) under the price."""
    keep = np.maximum.reduce([lock, be, e + 0.5 * (px - e)])
    want = keep if tgt_old is None else np.maximum(keep, tgt_old - 0.25 * (tgt_old - e))
    want = np.ceil(want / TICK - 1e-9) * TICK
    room = np.floor((px - np.maximum(px * 0.01, TICK)) / TICK + 1e-9) * TICK
    return np.where(want <= room, want, np.maximum(np.ceil(keep / TICK - 1e-9) * TICK, np.minimum(room, keep)))


def _replay_hero(arm, mg, idx):
    """hero_deep sim.run_trade ('real' fills) for F07: half the lots at 5x (resting, high >= target + tick, at max(target,
    open)), the rest at 20x; close <= 40% of entry -> next open - tick; 15:05 at the last close - tick. The manager's
    exit: next open - tick; its lock: a close at/under it -> next open - tick (Hero's own exits are close-based)."""
    rows = []
    for i in idx:
        O, H, L, Cl = arm.O[i], arm.H[i], arm.L[i], arm.Cl[i]
        e, qty, lot, c0 = arm.e[i], arm.qty[i], arm.lot[i], int(arm.c0[i])
        nl = int(round(qty / lot))
        q1 = min(qty, max(lot, int(round(nl * 0.5)) * lot))
        t1 = np.floor(e * 5 / TICK + 1e-6) * TICK
        t2 = np.floor(e * 20 / TICK + 1e-6) * TICK
        legs, rem, hit1 = [], qty, False
        E = HERO_E
        ml = NEG
        acted = 0
        j = c0 + 1
        while j <= E and rem > 0:
            if not np.isnan(H[j]) and not np.isnan(Cl[j]):
                if not hit1 and H[j] >= t1 + TICK - 1e-9:
                    legs.append((q1, max(t1, O[j]) if not np.isnan(O[j]) else t1, "tgt"))
                    rem -= q1
                    hit1 = True
                    if rem <= 0:
                        break
                if hit1 and rem > 0 and H[j] >= t2 + TICK - 1e-9:
                    legs.append((rem, t2, "tgt2"))
                    rem = 0
                    break
            if j == E:
                break
            if np.isnan(Cl[j]):
                j += 1
                continue
            w = None
            if Cl[j] <= e * 0.4:
                w = "stop"
            elif mg is not None and mg.lock is not None and Cl[j] <= ml:
                w = "mgr_lock"
            elif mg is not None and mg.exit is not None and mg.exit[i, j]:
                w = "mgr_exit"
            if w:
                px, jj = None, j + 1
                while jj <= E:
                    if not np.isnan(O[jj]):
                        px = O[jj]
                        break
                    jj += 1
                px = (Cl[j] if px is None else px) - TICK
                legs.append((rem, max(px, 0.0), w))
                rem = 0
                acted = int(w.startswith("mgr"))
                break
            if mg is not None and mg.lock is not None and mg.lock[i, j] > ml:
                ml = min(mg.lock[i, j], np.floor(Cl[j] * 0.99 / TICK) * TICK)
            j += 1
        if rem > 0:
            lc = Cl[:E + 1]
            lc = lc[~np.isnan(lc)]
            px = (lc[-1] if len(lc) else 0.0) - TICK
            legs.append((rem, max(round(px, 2), 0.0), "time"))
        gross = sum(q * (p - e) for q, p, _ in legs)
        ch = float(COSTS.charge(True, np.array([e]), np.array([qty]))[0]) + sum(
            float(COSTS.charge(False, np.array([p]), np.array([q]))[0]) for q, p, _ in legs if p >= TICK - 1e-9)
        code = {"tgt": 3, "tgt2": 3, "stop": 1, "time": 8, "mgr_exit": 9, "mgr_lock": 10}[legs[-1][2]]
        rows.append(dict(i=i, day=arm.day[i], xcol=j, exit=legs[-1][1], why=code, gross=gross, charges=ch, net=gross - ch, n_ext=0))
    return pd.DataFrame(rows)


_IX, _ATR, _REF = {}, {}, {}


def attach(arm: Arm, F):
    """Register the arm's index wick panels (index stop / target) and option ATR for the replay."""
    _IX[arm.name] = F
    _ATR[arm.name] = F["oatr"]
    ref = np.full(arm.n, np.nan)
    if arm.kind == "obuy":
        # the entry reference spot = the index close at the signal minute
        z = np.load(os.path.join(OUT, f"arm_{arm.name}.npz"), allow_pickle=True)
        ref = z["m_ref"]
    _REF[arm.name] = ref


# ------------------------------------------------------------------------------------------------ statistics
def daily(tr, days):
    s = tr.groupby("day").net.sum()
    return s.reindex(days, fill_value=0.0)


def maxdd(x):
    eq = np.cumsum(x)
    return float((eq - np.maximum.accumulate(np.r_[0.0, eq][1:] * 0 + np.maximum.accumulate(eq))).min()) if len(x) else 0.0


def max_drawdown(x):
    eq = np.concatenate([[0.0], np.cumsum(np.asarray(x, float))])
    return float((eq - np.maximum.accumulate(eq)).min())


def bh(p):
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out


def stationary_boot_idx(T, B, mean_block=5, seed=11):
    rng = np.random.default_rng(seed)
    idx = np.empty((B, T), np.int64)
    p = 1.0 / mean_block
    start = rng.integers(0, T, size=B)
    idx[:, 0] = start
    newb = rng.random((B, T)) < p
    rnd = rng.integers(0, T, size=(B, T))
    for t in range(1, T):
        idx[:, t] = np.where(newb[:, t], rnd[:, t], (idx[:, t - 1] + 1) % T)
    return idx


def white_rc(D, B=2000, seed=11):
    """White's reality check: D [T days, M configs] of daily (manager - original) P&L. H0: no config beats the original
    (mean <= 0). Returns (best mean, p)."""
    D = np.asarray(D, float)
    T, M = D.shape
    mu = D.mean(axis=0)
    stat = np.sqrt(T) * mu.max()
    idx = stationary_boot_idx(T, B, 5, seed)
    mx = np.empty(B)
    for b in range(B):
        mb = D[idx[b]].mean(axis=0)
        mx[b] = np.sqrt(T) * (mb - mu).max()
    return float(mu.max()), float((1 + (mx >= stat).sum()) / (B + 1))
