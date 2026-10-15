"""R61 option-chain features (PREREG section 4, block CH and GK), vectorised over the 375 minutes of one session.

Every function here is point in time: the value at minute r uses only chain rows at or before r. The pure-Python
single-snapshot versions in r61eval.py are the spec a Kotlin port follows; test_r61.py checks the two agree.

A day's chain is a "snapshot" array per field, shape (2, 21, 375): side (0 call, 1 put) x offset (-10..+10) x minute.
Each (side, offset) slot is carried forward from its latest minute at or before r, all fields together (the strike
with its own OI / IV / close), so a slot never mixes two minutes.
"""
from __future__ import annotations

import numpy as np
from scipy.special import ndtr

import r61cfg as K

NOFF = 21
OFFS = np.arange(-10, 11)
SIDES = ("C", "P")
FIELDS = ("strike", "oi", "iv", "close", "volume")


# ------------------------------------------------------------------------------------------------ snapshots
def snapshot(g, nrows: int = K.NROWS) -> dict:
    """Cleaned option rows of one day (columns r, offset, side 'C'/'P', strike, oi, iv, close, volume) ->
    {"raw": {field: (2, 21, n)} as recorded, "snap": {field: (2, 21, n)} carried forward, "vol": raw volume (0 if none)}.
    Rows with |offset| > 10 are ignored; duplicate (side, offset, minute) rows keep the first."""
    raw = {f: np.full((2, NOFF, nrows), np.nan) for f in FIELDS}
    if len(g):
        g = g[(g.offset.abs() <= 10) & (g.r >= 0) & (g.r < nrows)].drop_duplicates(["side", "offset", "r"])
        si = np.where(g.side.values == "C", 0, 1)
        oi = g.offset.values.astype(int) + 10
        rr = g.r.values.astype(int)
        for f in FIELDS:
            raw[f][si, oi, rr] = g[f].values.astype(float)
    present = np.isfinite(raw["strike"])
    last = np.where(present, np.arange(nrows)[None, None, :], -1)
    last = np.maximum.accumulate(last, axis=2)
    snap = {}
    for f in ("strike", "oi", "iv", "close"):
        v = np.take_along_axis(raw[f], np.maximum(last, 0), axis=2)
        snap[f] = np.where(last >= 0, v, np.nan)
    vol = np.nan_to_num(raw["volume"], nan=0.0)
    return dict(raw=raw, snap=snap, vol=vol)


def ffill(a: np.ndarray) -> np.ndarray:
    a = np.asarray(a, float)
    idx = np.where(np.isfinite(a), np.arange(len(a)), -1)
    idx = np.maximum.accumulate(idx)
    out = a[np.maximum(idx, 0)]
    out[idx < 0] = np.nan
    return out


def _sum(x: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """Sum over the offsets in mask of (21, n) x, NaN treated as 0; NaN where no slot in mask has a value."""
    sub = x[mask]
    out = np.nansum(sub, axis=0)
    out[~np.isfinite(sub).any(axis=0)] = np.nan
    return out


def _lagged(a: np.ndarray, f0: int, lag: int = 30) -> np.ndarray:
    """a at max(r - lag, f0) for r >= f0 (NaN before f0): '30 minutes ago, or the first chain minute if fewer'."""
    n = len(a)
    out = np.full(n, np.nan)
    if f0 < 0:
        return out
    r = np.arange(n)
    j = np.maximum(r - lag, f0)
    out[r >= f0] = a[j[r >= f0]]
    return out


def _pct(now, then):
    with np.errstate(all="ignore"):
        x = (now / then - 1.0) * 100.0
    return np.where(np.isfinite(x), x, np.nan)


# ------------------------------------------------------------------------------------------------ Black-Scholes
def years_left(dte_sessions: float, nrows: int = K.NROWS) -> np.ndarray:
    """T in years at each minute: (sessions to expiry + the rest of today / 375) / 252 (NaN if dte unknown)."""
    rest = (nrows - np.arange(nrows)) / nrows
    return (float(dte_sessions) + rest) / K.YEAR_SESSIONS


def bs(S, Kx, sigma, T, call, r: float = K.RATE) -> dict:
    """Black-Scholes delta, gamma, theta (per session = 1/252 year) and vega (per 1 IV point), broadcast; NaN where
    sigma or T is not positive."""
    S, Kx, sigma, T = (np.asarray(x, float) for x in (S, Kx, sigma, T))
    ok = (sigma > 0) & (T > 0) & (S > 0) & (Kx > 0)
    sg = np.where(ok, sigma, 1.0)
    TT = np.where(ok, T, 1.0)
    sq = np.sqrt(TT)
    with np.errstate(all="ignore"):
        d1 = (np.log(np.where(ok, S / Kx, 1.0)) + (r + 0.5 * sg ** 2) * TT) / (sg * sq)
    d2 = d1 - sg * sq
    pdf = np.exp(-0.5 * d1 ** 2) / np.sqrt(2 * np.pi)
    disc = np.exp(-r * TT)
    delta = np.where(call, ndtr(d1), ndtr(d1) - 1.0)
    gamma = pdf / (S * sg * sq)
    th_c = -S * pdf * sg / (2 * sq) - r * Kx * disc * ndtr(d2)
    th_p = -S * pdf * sg / (2 * sq) + r * Kx * disc * ndtr(-d2)
    theta = np.where(call, th_c, th_p) / K.YEAR_SESSIONS
    vega = S * pdf * sq / 100.0
    nan = np.nan
    return dict(delta=np.where(ok, delta, nan), gamma=np.where(ok, gamma, nan), theta=np.where(ok, theta, nan),
                vega=np.where(ok, vega, nan))


def net_gex(S, strikes, ivs, ois, T):
    """Net and gross gamma exposure per minute: S, T (n,); strikes / ivs (in %) / ois (2, 21, n). Calls +, puts -
    (R28 convention: dealers long the calls, short the puts). Slots with no strike, IV or OI count 0.
    Returns (net, gross), each (n,), in OI x index-points-per-1%-move units."""
    g = bs(S, strikes, ivs / 100.0, T, True)["gamma"]       # gamma is the same for a call and a put
    w = np.nan_to_num(g * np.nan_to_num(ois, nan=0.0), nan=0.0)
    S2 = np.asarray(S, float) ** 2 * 0.01
    c, p = w[0].sum(axis=0), w[1].sum(axis=0)
    return (c - p) * S2, (c + p) * S2


def flip_distance(S: np.ndarray, strikes, ivs, ois, T) -> np.ndarray:
    """Gamma flip, % of the index from S (per minute): net GEX re-priced at S x (1 + x), x in -3%..+3% (0.1% steps);
    the sign change nearest x = 0 (linear interpolation); NaN if the net never changes sign on the grid."""
    xs = np.round(np.arange(-K.FLIP_GRID, K.FLIP_GRID + K.FLIP_STEP / 2, K.FLIP_STEP), 6)
    S = np.asarray(S, float)
    n = S.shape[-1]
    nets = np.empty((len(xs), n))
    for i, x in enumerate(xs):
        nets[i] = net_gex(S * (1 + x), strikes, ivs, ois, T)[0]
    out = np.full(n, np.nan)
    for m in range(n):
        if not np.isfinite(S[m]):
            continue
        v = nets[:, m]
        best = np.nan
        for i in range(len(xs) - 1):
            a, b = v[i], v[i + 1]
            if not (np.isfinite(a) and np.isfinite(b)):
                continue
            if a == 0 and b == 0:
                continue
            if a == 0:
                x0 = xs[i]
            elif a * b < 0:
                x0 = xs[i] + (xs[i + 1] - xs[i]) * a / (a - b)
            else:
                continue
            if not np.isfinite(best) or abs(x0) < abs(best):
                best = x0
        out[m] = best * 100.0 if np.isfinite(best) else np.nan
    return out


# ------------------------------------------------------------------------------------------------ max pain, walls
def max_pain(strikes: np.ndarray, ois: np.ndarray) -> np.ndarray:
    """Per minute: the settlement strike (among the chain's strikes) that minimises the payout to option holders,
    sum OI_C x max(X - K, 0) + OI_P x max(K - X, 0). Ties: the lowest strike. NaN with no OI."""
    kc, kp = strikes[0], strikes[1]                         # (21, n)
    oc, op = np.nan_to_num(ois[0], nan=0.0), np.nan_to_num(ois[1], nan=0.0)
    oc = np.where(np.isfinite(kc), oc, 0.0)
    op = np.where(np.isfinite(kp), op, 0.0)
    X = np.concatenate([kc, kp], axis=0)                   # (42, n) candidate settles
    kc0, kp0 = np.nan_to_num(kc, nan=0.0), np.nan_to_num(kp, nan=0.0)
    pain = (oc[None] * np.maximum(X[:, None] - kc0[None], 0)).sum(1) + \
           (op[None] * np.maximum(kp0[None] - X[:, None], 0)).sum(1)
    pain = np.where(np.isfinite(X), pain, np.inf)
    # lowest strike among the minima: sort candidates by strike first
    Xs = np.where(np.isfinite(X), X, np.inf)
    order = np.argsort(Xs, axis=0, kind="stable")
    ps = np.take_along_axis(pain, order, 0)
    xs = np.take_along_axis(Xs, order, 0)
    j = np.argmin(ps, axis=0)
    mp = xs[j, np.arange(X.shape[1])]
    has = (oc.sum(0) + op.sum(0)) > 0
    return np.where(has & np.isfinite(mp), mp, np.nan)


def walls(S: np.ndarray, strikes: np.ndarray, ois: np.ndarray) -> tuple:
    """Per minute: (call wall - S) / S x 100 for the biggest call OI at a strike >= S, and (S - put wall) / S x 100 for
    the biggest put OI at a strike <= S. Ties: the strike nearest S. NaN if no such strike with OI > 0."""
    n = S.shape[-1]
    out = []
    for si, above in ((0, True), (1, False)):
        k, o = strikes[si], ois[si]
        elig = np.isfinite(k) & np.isfinite(o) & (o > 0) & ((k >= S[None]) if above else (k <= S[None]))
        oo = np.where(elig, o, -np.inf)
        dist = np.where(elig, np.abs(k - S[None]), np.inf)
        best = np.full(n, np.nan)
        for m in range(n):
            if not elig[:, m].any() or not np.isfinite(S[m]):
                continue
            top = oo[:, m].max()
            cand = np.flatnonzero(oo[:, m] == top)
            j = cand[np.argmin(dist[cand, m])]
            best[m] = (k[j, m] - S[m]) / S[m] * 100 if above else (S[m] - k[j, m]) / S[m] * 100
        out.append(best)
    return out[0], out[1]


# ------------------------------------------------------------------------------------------------ the day's features
DAY_FEATS = ("pcr_oi", "iv_atm", "oi_near_log", "oi_near_chg_pct", "writer_tilt30", "buildup", "pcr_oi_chg30",
             "pcr_vol", "maxpain_dist_pct", "call_wall_pct", "put_wall_pct", "iv_chg_day", "smile_curv", "gex_norm",
             "gex_sign", "flip_dist_pct", "synth_basis_bps")


def buildup_code(dp: np.ndarray, doi: np.ndarray) -> np.ndarray:
    """+2 long build-up (price up, OI up), +1 short covering (up, down), -1 long unwinding (down, down),
    -2 short build-up (down, up), 0 when either is flat; NaN when either is unknown."""
    sp, so = np.sign(dp), np.sign(doi)
    code = np.where((sp > 0) & (so > 0), 2, np.where((sp > 0) & (so < 0), 1,
                    np.where((sp < 0) & (so < 0), -1, np.where((sp < 0) & (so > 0), -2, 0)))).astype(float)
    return np.where(np.isfinite(dp) & np.isfinite(doi), code, np.nan)


def day_features(snap: dict, index_close: np.ndarray, dte_sessions: float, with_flip: bool = True) -> dict:
    """{name: (n,)} chain features of one session from its snapshot (r61chain.snapshot) and the index closes."""
    s = snap["snap"]
    n = index_close.shape[-1]
    S = ffill(index_close)
    near2, near5 = np.abs(OFFS) <= 2, np.abs(OFFS) <= 5
    c5, p5 = _sum(s["oi"][0], near5), _sum(s["oi"][1], near5)
    tot5 = c5 + p5
    out = {}
    with np.errstate(all="ignore"):
        pcr = np.where((c5 > 0) & np.isfinite(p5), p5 / c5, np.nan)
    out["pcr_oi"] = pcr
    have = np.flatnonzero(np.isfinite(tot5) & (tot5 > 0))
    f0 = int(have[0]) if len(have) else -1
    tot2 = _sum(s["oi"][0], near2) + _sum(s["oi"][1], near2)
    out["oi_near_log"] = np.where(np.isfinite(tot2), np.log10(1 + np.maximum(tot2, 0)), np.nan)
    base = np.full(n, tot5[f0] if f0 >= 0 else np.nan)
    chg = _pct(tot5, base)
    chg[: max(f0, 0) if f0 >= 0 else n] = np.nan
    out["oi_near_chg_pct"] = chg
    out["writer_tilt30"] = _pct(p5, _lagged(p5, f0)) - _pct(c5, _lagged(c5, f0))
    out["buildup"] = buildup_code(S - _lagged(S, f0), tot5 - _lagged(tot5, f0))
    out["pcr_oi_chg30"] = pcr - _lagged(pcr, f0)
    cv, pv = np.cumsum(snap["vol"][0][near5].sum(0)), np.cumsum(snap["vol"][1][near5].sum(0))
    with np.errstate(all="ignore"):
        out["pcr_vol"] = np.where(cv > 0, pv / cv, np.nan)
    mp = max_pain(s["strike"], s["oi"])
    out["maxpain_dist_pct"] = (mp - S) / S * 100
    out["call_wall_pct"], out["put_wall_pct"] = walls(S, s["strike"], s["oi"])
    ivc, ivp = s["iv"][0, 10], s["iv"][1, 10]
    ivatm = np.where((ivc > 0) & (ivp > 0), (ivc + ivp) / 2, np.nan)
    out["iv_atm"] = ivatm
    fi = np.flatnonzero(np.isfinite(ivatm))
    out["iv_chg_day"] = ivatm - (ivatm[fi[0]] if len(fi) else np.nan)
    pm3, cp3 = s["iv"][1, 7], s["iv"][0, 13]
    out["smile_curv"] = np.where((pm3 > 0) & (cp3 > 0), (pm3 + cp3) / 2 - ivatm, np.nan)
    T = years_left(dte_sessions, n) if np.isfinite(dte_sessions) else np.full(n, np.nan)
    net, gross = net_gex(S, s["strike"], s["iv"], s["oi"], T)
    with np.errstate(all="ignore"):
        gn = np.where(gross > 0, net / gross, np.nan)
    out["gex_norm"] = gn
    out["gex_sign"] = np.where(np.isfinite(gn), np.sign(gn), np.nan)
    out["flip_dist_pct"] = flip_distance(S, s["strike"], s["iv"], s["oi"], T) if with_flip else np.full(n, np.nan)
    kc, kp = s["strike"][0, 10], s["strike"][1, 10]
    cc, pc = s["close"][0, 10], s["close"][1, 10]
    ok = (kc == kp) & (cc > 0) & (pc > 0) & (S > 0)
    out["synth_basis_bps"] = np.where(ok, (kc + cc - pc - S) / S * 1e4, np.nan)
    return {k: np.asarray(v, float) for k, v in out.items()}


def held_greeks(snap: dict, side: str, strike: float, frow: int, S: float, dte_sessions: float) -> dict:
    """GK block for the candidate's own contract at frow: IV and premium from that strike's snapshot slot."""
    nan = dict(delta_abs=np.nan, gamma_1pct=np.nan, theta_pct_day=np.nan, vega_pct=np.nan)
    if not (np.isfinite(S) and np.isfinite(dte_sessions)):
        return nan
    si = 0 if side == "C" else 1
    ks = snap["snap"]["strike"][si, :, frow]
    hit = np.flatnonzero(ks == float(strike))
    if not len(hit):
        return nan
    j = hit[0]
    iv, prem = snap["snap"]["iv"][si, j, frow], snap["snap"]["close"][si, j, frow]
    if not (np.isfinite(iv) and iv > 0 and np.isfinite(prem) and prem > 0):
        return nan
    T = years_left(dte_sessions)[frow]
    g = bs(S, float(strike), iv / 100.0, T, side == "C")
    return dict(delta_abs=float(abs(g["delta"])), gamma_1pct=float(g["gamma"] * S * 0.01),
                theta_pct_day=float(g["theta"] / prem * 100), vega_pct=float(g["vega"] / prem * 100))


# ------------------------------------------------------------------------------------------------ cross-day ranks
def same_row_rank(hist: list, today: np.ndarray, look: int, min_n: int) -> np.ndarray:
    """Share of the previous `look` sessions' values at the SAME minute below today's (NaN with fewer than min_n
    finite values). hist: list of (n,) arrays, oldest first, sessions BEFORE today only."""
    today = np.asarray(today, float)
    out = np.full(today.shape, np.nan)
    if not hist:
        return out
    H = np.vstack(hist[-look:])
    fin = np.isfinite(H)
    cnt = fin.sum(0)
    below = (np.where(fin, H, np.inf) < today[None]).sum(0)
    ok = (cnt >= min_n) & np.isfinite(today)
    out[ok] = below[ok] / cnt[ok]
    return out


def same_row_minmax(hist: list, today: np.ndarray, look: int, min_n: int) -> np.ndarray:
    """(today - min) / (max - min) over the previous `look` sessions at the same minute (IV-rank style)."""
    today = np.asarray(today, float)
    out = np.full(today.shape, np.nan)
    if not hist:
        return out
    H = np.vstack(hist[-look:])
    fin = np.isfinite(H)
    cnt = fin.sum(0)
    with np.errstate(all="ignore"):
        lo, hi = np.nanmin(np.where(fin, H, np.nan), 0), np.nanmax(np.where(fin, H, np.nan), 0)
        x = (today - lo) / (hi - lo)
    ok = (cnt >= min_n) & np.isfinite(x)
    out[ok] = x[ok]
    return out
