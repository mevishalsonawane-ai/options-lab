"""R167 rules (PREREG section 3) and helpers: per-minute exit flags, CTX's menu choice, ML features / labels, statistics,
twins, BH and the gates.

Every rule returns a (375,) bool array `fire`: fire[m] True = the rule wants out at the close of minute m (sold at the
open of m + 1). The rule's state (ratchets, peaks, trails) runs from the fill f whatever happens, so CTX can switch
between rules mid-trade. A rule's own exit row is exit_row(fire, f, sq). Only data at rows <= m is read for fire[m].

Coordinates: s = +1 for a call, -1 for a put. Index prices are turned into "favourable" ones (s x price; a put's high
becomes -low), so every rule is written once, as for a call.

Twin p, BH and the walk-forward gates follow R162 (`claude/r162-donchian-exits`, r162lib.py), copied here because R162
is not on main. Plain-English notes, 12 Nov 2026.
"""
from __future__ import annotations

import math
from collections import Counter, defaultdict

import numpy as np

N = 375
MINHOLD = 2              # momentum, flow and ML signals fire only from m >= f + 2 (first exit at f + 3)
BUF = 0.10               # b(m) = 0.10 x ATR5(m)
SR_FALLBACK_ATR = 2.0
VOLX_STOP, VOLX_TGT = 3.0, 4.0
VOLP_STOP, VOLP_N, VOLP_MIN = 3.0, 14, 5
SPIKE_X, SPIKE_WIN, SPIKE_MIN = 3.0, 20, 10
OI_UP, OI_LAG = 0.02, 5
FLOW_T0, FLOW_STEP, FLOW_TMAX = 30, 15, 60
ER_TREND = 0.30
ML_X = ML_Y = 1.0
ML_HORIZON = 60
ML_Q = 0.25


def fav(ctx, s: int) -> tuple:
    """(fo, fh, fl, fc) favourable index arrays."""
    if s > 0:
        return ctx.o, ctx.h, ctx.l, ctx.c
    return -ctx.o, -ctx.l, -ctx.h, -ctx.c


def exit_row(fire: np.ndarray, f: int, sq: int) -> int:
    """The forced exit row: the open after the first minute m in [f, sq) with fire[m]; -1 if none or if m + 1 is the
    square-off row or later (the square-off then sells, as R162's index stop)."""
    for m in range(max(f, 0), min(sq, len(fire))):
        if fire[m]:
            return m + 1 if m + 1 < sq else -1
    return -1


def _fin(*xs) -> bool:
    return all(x == x for x in xs)


# ================================================================================================ 1. SR
def sr_fire(ctx, s: int, f: int, sq: int, oc: np.ndarray, raw: float) -> np.ndarray:
    """Support / resistance: a stop just beyond the last broken level or latest swing (ratchets toward profit), and a
    target just before the next level ahead (taken only in profit; a close through it is a breakout and the trade stays).
    The target level is read above the previous minute's close (PREREG Clarification A)."""
    fire = np.zeros(N, bool)
    _, fh, fl, fc = fav(ctx, s)
    S = None
    prev = next((fc[j] for j in range(min(f, N) - 1, -1, -1) if fc[j] == fc[j]), np.nan)   # the close before the fill
    for m in range(max(f, 0), min(sq, N)):
        a = ctx.atr5[m]
        if not _fin(fc[m], a):
            if fc[m] == fc[m]:
                prev = fc[m]
            continue
        b = BUF * a
        lv = sorted(s * x for x in ctx.levels_at(m))
        below = [x for x in lv if x < fc[m]]
        sw = ctx.swing_below(m, s, fc[m])
        cands = ([below[-1]] if below else []) + ([sw] if sw is not None else [])
        if S is None:
            S = (max(cands) - b) if cands else fc[m] - SR_FALLBACK_ATR * a
        else:
            if fc[m] < S:
                fire[m] = True
            if cands:
                S = max(S, max(cands) - b)
        ref = prev if prev == prev else fc[m]
        above = [x for x in lv if x > ref]
        if above and fh[m] == fh[m]:
            R = above[0]
            if fh[m] >= R - b and fc[m] <= R + b and oc[m] == oc[m] and oc[m] > raw:
                fire[m] = True
        prev = fc[m]
    return fire


# ================================================================================================ 2. VOLX / VOLP
def volx_fire(ctx, s: int, f: int, sq: int, oc: np.ndarray, raw: float, ratchet: bool) -> np.ndarray:
    """Chandelier on the index: peak - 3 x ATR5 (ratcheted for T, recomputed for F), checked on closes against the
    level known after the previous minute; target 4 x ATR5 from the fill minute's close, in profit only."""
    fire = np.zeros(N, bool)
    _, fh, _, fc = fav(ctx, s)
    P, C, c0 = -np.inf, -np.inf, np.nan
    for m in range(max(f, 0), min(sq, N)):
        a = ctx.atr5[m]
        if not _fin(fc[m], a):
            continue
        if c0 != c0:
            c0 = fc[m]
        if fc[m] < C:
            fire[m] = True
        if fh[m] == fh[m]:
            P = max(P, fh[m])
        new = P - VOLX_STOP * a
        C = max(C, new) if ratchet else new
        if fc[m] - c0 >= VOLX_TGT * a and oc[m] == oc[m] and oc[m] > raw:
            fire[m] = True
    return fire


def option_atr(C: np.ndarray) -> np.ndarray:
    """Wilder ATR(14) of the held contract's own 1-minute bars that day (>= 5 bars needed)."""
    return _wilder_min(_tr(C[1], C[2], C[3]), VOLP_N, VOLP_MIN)


def _tr(h, l_, c):
    out = np.full(len(c), np.nan)
    pc = np.nan
    for i in range(len(c)):
        if _fin(h[i], l_[i], c[i]):
            out[i] = max(h[i] - l_[i], abs(h[i] - pc), abs(l_[i] - pc)) if pc == pc else h[i] - l_[i]
            pc = c[i]
    return out


def _wilder_min(x, n, min_count):
    out = np.full(len(x), np.nan)
    cnt, s, prev = 0, 0.0, np.nan
    for i, v in enumerate(x):
        if v == v:
            cnt += 1
            if cnt <= n:
                s += v
                prev = s / cnt if cnt >= min_count else np.nan
            else:
                prev = (prev * (n - 1) + v) / n
        out[i] = prev
    return out


def volp_fire(C: np.ndarray, f: int, sq: int, ratchet: bool) -> np.ndarray:
    """Chandelier on the option's premium: highest close since fill - 3 x the option's ATR(14); no target."""
    fire = np.zeros(N, bool)
    oc = C[3]
    atr = option_atr(C)
    Q, stop = -np.inf, -np.inf
    for m in range(max(f, 0), min(sq, N)):
        if not oc[m] == oc[m]:
            continue
        if oc[m] < stop:
            fire[m] = True
        Q = max(Q, oc[m])
        if atr[m] == atr[m]:
            new = Q - VOLP_STOP * atr[m]
            stop = max(stop, new) if ratchet else new
    return fire


# ================================================================================================ 3. FLOW
def spikes(v: np.ndarray) -> np.ndarray:
    """(375,) bool: v[m] >= 3 x the median of the previous 20 minutes (>= 10 of them printed) and v[m] > 0."""
    out = np.zeros(len(v), bool)
    for m in range(len(v)):
        if not (v[m] == v[m]) or v[m] <= 0:
            continue
        w = v[max(0, m - SPIKE_WIN):m]
        w = w[np.isfinite(w)]
        if len(w) >= SPIKE_MIN and v[m] >= SPIKE_X * float(np.median(w)):
            out[m] = True
    return out


def oi_against(oi, oc: np.ndarray, m: int) -> bool:
    """The held contract's OI up >= 2% over 5 minutes while its premium fell (short build-up on the bought option)."""
    if oi is None or m - OI_LAG < 0:
        return False
    a, b, p0, p1 = oi[m - OI_LAG], oi[m], oc[m - OI_LAG], oc[m]
    return bool(_fin(a, b, p0, p1) and a > 0 and b >= a * (1 + OI_UP) and p1 < p0)


def flow_fire(ctx, s: int, f: int, sq: int, C: np.ndarray, oi) -> tuple:
    """(tighten fire, extended-time fire). Tighten: from the first 'against' event (m >= f + 2), a 3-minute low trail
    on the index (checked before it is raised). Time (F only): 30 minutes + 15 per confirming spike, at most 60."""
    fire = np.zeros(N, bool)
    tfire = np.zeros(N, bool)
    fo, fh, fl, fc = fav(ctx, s)
    sp = spikes(C[4])
    oc = C[3]
    trail = None
    conf = 0
    for m in range(max(f, 0), min(sq, N)):
        d = fc[m] - fo[m] if _fin(fc[m], fo[m]) else np.nan
        if sp[m] and d == d and d > 0:
            conf += 1
        tmax = min(FLOW_T0 + FLOW_STEP * conf, FLOW_TMAX)
        if m >= f + tmax - 1:
            tfire[m] = True
        if not fc[m] == fc[m]:
            continue
        w = fl[max(0, m - 2):m + 1]
        w = w[np.isfinite(w)]
        if trail is not None:
            if fc[m] < trail:
                fire[m] = True
            if len(w):
                trail = max(trail, float(w.min()))
        elif m >= f + MINHOLD and ((sp[m] and d == d and d < 0) or oi_against(oi, oc, m)) and len(w):
            trail = float(w.min())
    return fire, tfire


# ================================================================================================ 4. MOM
def mom_fire(ctx, s: int, f: int, sq: int) -> np.ndarray:
    """1-minute Supertrend (10, 3) against the trade, from m >= f + 2."""
    fire = np.zeros(N, bool)
    for m in range(max(f + MINHOLD, 0), min(sq, N)):
        d = ctx.st[m]
        if d == d and d * s < 0:
            fire[m] = True
    return fire


# ================================================================================================ giveback (Amendment A)
GB_TRIG, GB_KEEP_G1, GB_KEEP_G2, GB_TRAIL_G2 = 1500.0, 0.0, 500.0, 1000.0


def g3_fire(ctx, s: int, f: int, sq: int, oh: np.ndarray, x_trig: float) -> np.ndarray:
    """G3: once the option's high has reached x_trig (the premium whose sale nets +Rs 1,500), a 3-minute index low trail
    (call; high for a put), raised each minute and checked before it is raised. The minute that first reaches x_trig
    only sets the trail."""
    fire = np.zeros(N, bool)
    _, _, fl, fc = fav(ctx, s)
    armed = False
    trail = None
    for m in range(max(f, 0), min(sq, N)):
        w = fl[max(0, m - 2):m + 1]
        w = w[np.isfinite(w)]
        if trail is not None and fc[m] == fc[m] and fc[m] < trail:
            fire[m] = True
        if not armed and oh[m] == oh[m] and oh[m] >= x_trig:
            armed = True
        if armed and len(w):
            trail = float(w.min()) if trail is None else max(trail, float(w.min()))
    return fire


def giveback(trades: list, trig: float = GB_TRIG) -> dict:
    """Amendment A2: of the trades whose best open profit (field 'best_net', the sale at the best high net of charges)
    reached >= trig, how many ended <= 0 and <= -2,000, and the rupees they ended with."""
    hit = [t for t in trades if t.get("best_net") is not None and t["best_net"] >= trig]
    lost = [t for t in hit if t["net"] <= 0]
    big = [t for t in hit if t["net"] <= -2000]
    return dict(trades=len(trades), reached=len(hit), reached_share=round(len(hit) / len(trades), 4) if trades else None,
                ended_le0=len(lost), ended_le0_net=round(sum(t["net"] for t in lost)),
                ended_le_m2000=len(big), ended_le_m2000_net=round(sum(t["net"] for t in big)))


# ================================================================================================ 5. CTX
def regimes(ctx, vix_med: float) -> np.ndarray:
    """(375,) int regime = 2 x (VIX >= median) + (efficiency ratio >= 0.30); NaN VIX counts as low, NaN ER as range."""
    hi = np.where(np.isfinite(ctx.vix), ctx.vix >= vix_med, False)
    tr = np.where(np.isfinite(ctx.er), ctx.er >= ER_TREND, False)
    return (2 * hi.astype(int) + tr.astype(int)).astype(int)


def choose_menu(rows: list, menu: tuple) -> dict:
    """rows: [{'regime': r, 'res': {item: net}}] training trades at 1 lot (B0's walked trades priced by every item).
    Per regime: the item with the highest net among those whose win rate >= B0's; ties / none -> 'B0' (first wins)."""
    by = defaultdict(list)
    for r in rows:
        by[int(r["regime"])].append(r["res"])
    out = {}
    for g in range(4):
        rs = by.get(g, [])
        if not rs:
            out[g] = "B0"
            continue
        w0 = np.mean([x["B0"] > 0 for x in rs])
        best, bnet = "B0", sum(x["B0"] for x in rs)
        for it in menu:
            if it == "B0":
                continue
            w = np.mean([x[it] > 0 for x in rs])
            net = sum(x[it] for x in rs)
            if w >= w0 - 1e-12 and net > bnet + 1e-9:
                best, bnet = it, net
        out[g] = best
    return out


def ctx_fire(fires: dict, reg: np.ndarray, choice: dict) -> np.ndarray:
    """CTX-T: fire[m] = the rule chosen for minute m's regime fires at m ('B0' never fires)."""
    out = np.zeros(N, bool)
    for m in range(N):
        it = choice.get(int(reg[m]), "B0")
        if it != "B0" and fires[it][m]:
            out[m] = True
    return out


# ================================================================================================ 6. ML
FEATS = ("side", "held", "ret", "peak_ret", "dd", "imove_atr", "lev_ahead", "lev_behind", "atr_bps", "roc3", "st",
         "vol_ratio", "oi_chg", "vix", "vix_chg", "minute")


def ml_features(ctx, s: int, f: int, m: int, C: np.ndarray, oi, raw: float, vol_med: np.ndarray) -> list:
    """The PREREG feature vector at minute m (NaN where not known)."""
    _, fh, fl, fc = fav(ctx, s)
    oc = C[3]
    a = ctx.atr5[m]
    seg = oc[f:m + 1]
    seg = seg[np.isfinite(seg)]
    ret = oc[m] / raw - 1 if oc[m] == oc[m] and raw > 0 else np.nan
    pk = float(seg.max()) / raw - 1 if len(seg) and raw > 0 else np.nan
    dd = (float(seg.max()) - oc[m]) / raw if len(seg) and oc[m] == oc[m] and raw > 0 else np.nan
    c0 = next((fc[j] for j in range(f, m + 1) if fc[j] == fc[j]), np.nan)
    im = (fc[m] - c0) / a if _fin(fc[m], c0, a) and a > 0 else np.nan
    ahead = behind = 5.0
    if _fin(fc[m], a) and a > 0:
        lv = [s * x for x in ctx.levels_at(m)]
        up = [x - fc[m] for x in lv if x > fc[m]]
        dn = [fc[m] - x for x in lv if x < fc[m]]
        ahead = min(min(up) / a, 5.0) if up else 5.0
        behind = min(min(dn) / a, 5.0) if dn else 5.0
    bps = a / ctx.c[m] * 1e4 if _fin(a, ctx.c[m]) and ctx.c[m] > 0 else np.nan
    c = ctx.c
    roc = s * (c[m] / c[m - 3] - 1) if m >= 3 and _fin(c[m], c[m - 3]) and c[m - 3] != 0 else np.nan   # + = favourable
    st = ctx.st[m] * s if ctx.st[m] == ctx.st[m] else np.nan
    v = C[4][m]
    vr = math.log1p(v / vol_med[m]) if _fin(v, vol_med[m]) and vol_med[m] > 0 else np.nan
    oich = (oi[m] / oi[m - 5] - 1) * 100 if oi is not None and m >= 5 and _fin(oi[m], oi[m - 5]) and oi[m - 5] > 0 else np.nan
    vx = ctx.vix[m]
    v0 = next((ctx.vix[j] for j in range(0, m + 1) if ctx.vix[j] == ctx.vix[j]), np.nan)
    vch = vx - v0 if _fin(vx, v0) else np.nan
    return [float(s), float(m - f), ret, pk, dd, im, ahead, behind, bps, roc, st, vr, oich, vx, vch, float(m)]


def vol_median(v: np.ndarray) -> np.ndarray:
    """(375,) median of the previous 20 minutes' volume (>= 10 printed), else NaN."""
    out = np.full(len(v), np.nan)
    for m in range(len(v)):
        w = v[max(0, m - SPIKE_WIN):m]
        w = w[np.isfinite(w)]
        if len(w) >= SPIKE_MIN:
            out[m] = float(np.median(w))
    return out


def ml_label(ctx, s: int, m: int, sq: int, x: float = ML_X, y: float = ML_Y) -> float:
    """1 if the index moves >= x ATR5(m) in favour before >= y ATR5(m) against, from m + 1 to sq - 1 (both in one
    minute: against first); 0 otherwise; NaN when ATR5(m) or the close is missing. Training rows only."""
    _, fh, fl, fc = fav(ctx, s)
    a = ctx.atr5[m]
    if not _fin(fc[m], a) or a <= 0:
        return np.nan
    up, dn = fc[m] + x * a, fc[m] - y * a
    for j in range(m + 1, min(sq, N)):
        if fl[j] == fl[j] and fl[j] <= dn:
            return 0.0
        if fh[j] == fh[j] and fh[j] >= up:
            return 1.0
    return 0.0


def ml_rows(f: int, sq: int) -> range:
    """Training minutes of one trade: [f + 2, min(f + 60, sq - 1)]."""
    return range(f + MINHOLD, min(f + ML_HORIZON, sq - 1) + 1)


class ExitModel:
    """Logistic regression (L2, C = 1.0) on standardised features; NaN -> the training median; θ = 25th percentile of
    the in-sample probabilities."""

    def __init__(self):
        self.med = None
        self.pipe = None
        self.theta = None

    def fit(self, X: np.ndarray, y: np.ndarray) -> "ExitModel":
        from sklearn.linear_model import LogisticRegression
        from sklearn.pipeline import make_pipeline
        from sklearn.preprocessing import StandardScaler
        X = np.asarray(X, float)
        y = np.asarray(y, float)
        ok = np.isfinite(y)
        X, y = X[ok], y[ok].astype(int)
        self.med = np.nanmedian(np.where(np.isfinite(X), X, np.nan), axis=0)
        self.med = np.where(np.isfinite(self.med), self.med, 0.0)
        Xf = self._fill(X)
        if len(set(y.tolist())) < 2:
            raise ValueError("ExitModel needs both label values in training")
        self.pipe = make_pipeline(StandardScaler(), LogisticRegression(C=1.0, max_iter=2000))
        self.pipe.fit(Xf, y)
        self.theta = float(np.quantile(self.pipe.predict_proba(Xf)[:, 1], ML_Q))
        return self

    def _fill(self, X):
        X = np.array(X, float)
        bad = ~np.isfinite(X)
        X[bad] = np.broadcast_to(self.med, X.shape)[bad]
        return X

    def proba(self, X) -> np.ndarray:
        return self.pipe.predict_proba(self._fill(np.atleast_2d(X)))[:, 1]


def ml_fire(probs: np.ndarray, rows: list, theta: float) -> np.ndarray:
    """fire[m] = P(m) < θ for the scored minutes `rows` (each >= f + 2)."""
    fire = np.zeros(N, bool)
    for m, p in zip(rows, probs):
        if p == p and p < theta:
            fire[m] = True
    return fire


# ================================================================================================ statistics
def stats(trades: list) -> dict:
    """Trades, win, net, avg win / loss, worst day, max drawdown (daily), exit mix, moved earlier / later."""
    n = len(trades)
    if not n:
        return dict(trades=0, win=None, net=0, avg_win=None, avg_loss=None, worst_day=0, max_dd=0, mix={})
    nets = np.array([float(t["net"]) for t in trades])
    dd = defaultdict(float)
    for t in trades:
        dd[t["day"]] += float(t["net"])
    seq = np.array([dd[d] for d in sorted(dd)])
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    w, lo = nets[nets > 0], nets[nets <= 0]
    return dict(trades=n, win=round(float((nets > 0).mean()), 4), net=round(float(nets.sum())),
                avg_win=round(float(w.mean())) if len(w) else None, avg_loss=round(float(lo.mean())) if len(lo) else None,
                worst_day=round(float(seq.min())), max_dd=round(float((np.maximum.accumulate(eq) - eq).max())),
                mix=dict(Counter(t.get("why", "?") for t in trades)))


def tkey(t: dict) -> tuple:
    return (t["und"], t["day"], int(t["er_sig"]), t["side"])


def moved(trades: list, base: dict) -> dict:
    """How many trades exit earlier / later / the same as the B0-priced same candidate (base: tkey -> B0 trade)."""
    c = Counter()
    for t in trades:
        b = base.get(tkey(t))
        if b is None:
            c["no_b0"] += 1
        elif t["xr"] < b["xr"]:
            c["earlier"] += 1
        elif t["xr"] > b["xr"]:
            c["later"] += 1
        else:
            c["same"] += 1
    return dict(c)


# ================================================================================================ twins (R162)
def twin_window(fill_row: int, rule_xr: int, b0_xr: int):
    """(lo, hi, which): earlier -> rows [fill+1, b0_xr-1] with B0's exits; later -> rows [b0_xr+1, rule_xr-1] with the
    rule's exits. None when the exits match or the window is empty (the twin is the rule's own trade)."""
    if rule_xr == b0_xr:
        return None
    if rule_xr < b0_xr:
        lo, hi, which = fill_row + 1, b0_xr - 1, "b0"
    else:
        lo, hi, which = b0_xr + 1, rule_xr - 1, "rule"
    return (lo, hi, which) if hi >= lo else None


def twin_p(actual: float, twins) -> float:
    """p = (1 + twins >= actual) / (n + 1)."""
    t = np.asarray(twins, float)
    return float((1 + int((t >= actual - 1e-9).sum())) / (len(t) + 1))


def joint_p(net: float, win: float, twin_nets: np.ndarray, twin_wins: np.ndarray) -> dict:
    """PREREG section 6: p on net, p on win rate, the test's p = the larger."""
    pn, pw = twin_p(net, twin_nets), twin_p(win, twin_wins)
    return dict(p=max(pn, pw), p_net=pn, p_win=pw)


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q; m_total > len(p) counts extra earlier tests taken as p = 1."""
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    m = max(m_total or n, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


# ================================================================================================ gates (PREREG 7)
def gates(rule: dict, base: dict, q: float, win_up: float = 0.05, gate_q: float = 0.10, min_final: int = 40) -> dict:
    """rule / base = {'design', 'wf', 'final'}: stats dicts (trades, win, net)."""
    fails = []
    for per in ("wf", "final"):
        r, b = rule[per], base[per]
        if r["win"] is None or b["win"] is None or r["win"] < b["win"] + win_up - 1e-9:
            fails.append(f"{per}: win rate not up 5 points")
        if r["net"] < b["net"]:
            fails.append(f"{per}: net lower than today's")
    for per in ("design", "wf", "final"):
        if not rule[per]["net"] > 0:
            fails.append(f"{per}: net not positive")
    if not q <= gate_q:
        fails.append(f"BH q {q:.3g} > {gate_q}")
    if rule["final"]["trades"] < min_final:
        fails.append(f"< {min_final} final trades")
    return dict(passed=not fails, fails=fails)
