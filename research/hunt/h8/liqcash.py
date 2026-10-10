"""h8: the app's Liquidity 15+5 level-break rule, UNCHANGED, on F&O stocks' minute data, LONG-ONLY, as intraday cash.

Rule logic = research/hunt/h4/comps.py (port of LiquidityRules.kt): 15-min and 5-min books built from the last 10
calendar days of 1-minute bars; swing zones (L = 20 bars each side); liquidity pools (a wick re-touched >= GAP bars
later, confirmed CONFIRM bars after the touch, max age 300 bars, de-duplicated against the last 50 pools); a signal is
the CLOSE of a bar that breaks a pool that overlaps a live swing zone; target = the next live level ahead; room filter
(target at least 1 index stop away); index stop = broken level - istop; exit_at = first failed break (close back
below the level) or the first new level, whichever comes first; entries whose bar ends 09:20..14:00.

This module computes the SAME zones over the whole minute history once (exact equivalent of the per-day 10-day window:
pools / swings are restricted to those whose origin lies inside each day's window and the de-dup filter is re-applied
per window), which makes 214 stocks feasible. Parity with the per-day reference is checked by parity.py.

Cash translation (PRE-REGISTERED before any stock result was looked at; see HUNT_H8.md):
  istop (stock)   = 0.046 x ATR14 (daily, from the minute data, prior days only), floor Rs 0.10.  0.046 = BANKNIFTY /
                    FINNIFTY index stop / their median ATR14 over Oct 2024 - Sep 2025 (0.045 / 0.048).
  premium stop -15%  -> resting stop at entry - 5 x istop (1-ITM premium ~1.2% of spot, delta ~0.6).
  time stop 20 min unless premium +5%  -> at the 20th minute exit unless close >= entry + 1.75 x istop.
  square-off 15:10 (decision 15:09 close, fill 15:10 open). Minute-close decisions fill at the next minute's open.
Long-only: only upside breaks (side +1) are generated / traded; the 5-min and 15-min books are separate positions.
"""
from __future__ import annotations

import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
DATA = os.path.join(SCR, "dhan", "repo", "dhan-data")
OUT = os.path.join(SCR, "hunt", "h8")
HOLD = pd.Timestamp("2025-10-01")

L, CONTACTS, GAP, CONFIRM, MAX_AGE = 20, 2, 5, 10, 300
OPEN_M = 555
WIN_FROM, WIN_TO = 560, 840
SQC = 15 * 60 + 10 - OPEN_M          # 355: square-off column
K_ATR, ISTOP_FLOOR = 0.046, 0.10
HARD_K, TGAIN_K = 5.0, 1.75
TIME_STOP = 20


# ------------------------------------------------------------------------------------------------ data
def load_minutes(kind, name):
    d = os.path.join(DATA, "candles", "minute", kind, name)
    fs = sorted(f for f in os.listdir(d) if f.endswith(".parquet"))
    df = pd.concat([pd.read_parquet(os.path.join(d, f)) for f in fs], ignore_index=True)
    ts = df["ts"].dt.tz_localize(None)
    df["day"] = ts.dt.normalize()
    df["m"] = (ts.dt.hour * 60 + ts.dt.minute).astype(np.int64)
    df = df[(df.m >= 555) & (df.m <= 929)].drop_duplicates(["day", "m"]).sort_values(["day", "m"])
    if "volume" not in df:
        df["volume"] = 0
    return df[["day", "m", "open", "high", "low", "close", "volume"]].reset_index(drop=True)


def dense(df):
    """(days, D x 375 o/h/l/c/v arrays, minutes per day)."""
    days = np.array(sorted(df.day.unique()), dtype="datetime64[D]")
    di = np.searchsorted(days, df.day.values.astype("datetime64[D]"))
    mi = df.m.values - OPEN_M
    A = {}
    for k, col in zip("ohlcv", ["open", "high", "low", "close", "volume"]):
        a = np.full((len(days), 375), np.nan)
        a[di, mi] = df[col].values.astype(np.float64)
        A[k] = a
    cnt = np.bincount(di, minlength=len(days))
    return days, A, cnt


def daily_stats(days, A):
    """ATR14 (prior days) and prior-20-day median traded value, per day."""
    h = np.nanmax(A["h"], axis=1); lo = np.nanmin(A["l"], axis=1)
    cf = pd.DataFrame(A["c"]).ffill(axis=1).to_numpy()[:, -1]
    pc = np.r_[np.nan, cf[:-1]]
    tr = np.fmax(h - lo, np.fmax(np.abs(h - pc), np.abs(lo - pc)))
    atr = pd.Series(tr).rolling(14, min_periods=10).mean().shift(1).to_numpy()
    val = np.nansum(A["c"] * A["v"], axis=1)
    val = np.where(val > 0, val, np.nan)
    adv = pd.Series(val).rolling(20, min_periods=5).median().shift(1).to_numpy()
    return atr, adv, pc


# ------------------------------------------------------------------------------------------------ zones (side +1)
def fold(df, tf):
    b = OPEN_M + ((df.m.values - OPEN_M) // tf) * tf
    g = pd.DataFrame(dict(day=df.day.values, b=b, o=df.open.values, h=df.high.values, l=df.low.values,
                          c=df.close.values)).groupby(["day", "b"], sort=True)
    a = g.agg(o=("o", "first"), h=("h", "max"), l=("l", "min"), c=("c", "last")).reset_index()
    return a


def first_true(mask):
    """index of the first True per row, or -1."""
    has = mask.any(axis=1)
    return np.where(has, mask.argmax(axis=1), -1)


def swings_up(H, Lo):
    n = len(H)
    if n <= 2 * L:
        return np.zeros(0, np.int64)
    from numpy.lib.stride_tricks import sliding_window_view as sw
    wh = sw(H, L).max(axis=1)
    js = np.arange(L, n - L)
    ok = (wh[js - L] < H[js]) & (wh[js + 1] < H[js])
    return js[ok]


def pools_up_raw(O, H, Lo, Cl):
    """All up-pool emissions WITHOUT the de-dup filter: arrays (origin, known, top, bottom)."""
    n = len(Cl)
    bh = np.maximum(O, Cl)
    orig = np.nonzero(H > bh)[0]
    res = []
    T = np.arange(1, MAX_AGE + 1)
    for a in range(0, len(orig), 4000):
        og = orig[a:a + 4000]
        idx = og[:, None] + T[None, :]
        ok = idx < n
        idc = np.minimum(idx, n - 1)
        top, bot = H[og][:, None], bh[og][:, None]
        beyond = ok & (Cl[idc] > top)
        touched = ok & (H[idc] >= bot) & (Cl[idc] < top) & (T[None, :] >= GAP)
        fb = first_true(beyond)
        fbv = np.where(fb >= 0, fb, 10 ** 6)          # column (t-1) of first beyond
        t1 = first_true(touched)
        good = (t1 >= 0) & (t1 < fbv)
        te = t1 + CONFIRM                              # column of the emission bar (t = te + 1)
        good &= (te < fbv) & (te + 1 <= MAX_AGE) & (og + te + 1 < n)
        og2, te2 = og[good], te[good]
        res.append(np.c_[og2, og2 + te2 + 1, H[og2], bh[og2]])
    if not res:
        return np.zeros((0, 4))
    r = np.vstack(res)
    o = np.lexsort((r[:, 0], r[:, 1]))              # emission order: by known, then origin (candidate order)
    return r[o]


def breaks_up(known, top, Cl, hz):
    """first index k >= known with Cl[k] > top, searched hz bars ahead; -1 if none."""
    n = len(Cl)
    out = np.full(len(known), -1, np.int64)
    T = np.arange(hz)
    for a in range(0, len(known), 3000):
        kn = known[a:a + 3000].astype(np.int64)
        idx = kn[:, None] + T[None, :]
        ok = idx < n
        hit = ok & (Cl[np.minimum(idx, n - 1)] > top[a:a + 3000, None])
        f = first_true(hit)
        out[a:a + 3000] = np.where(f >= 0, kn + f, -1)
    return out


def signals_up(df, istop_by_day, real_by_day, tf, room=1.0):
    """Long liquidity-break signals of one book (tf minutes). istop_by_day: {day: istop} (days without -> skipped)."""
    bars = fold(df, tf)
    O, H, Lo, Cl = (bars[k].to_numpy(np.float64) for k in "ohlc")
    bday = bars.day.values.astype("datetime64[D]")
    bst = bars.b.to_numpy()
    n = len(Cl)
    hz = 10 * int(np.ceil(375 / tf)) + 20
    sj = swings_up(H, Lo)
    s_org, s_kn, s_top, s_bot = sj, sj + L, H[sj], Lo[sj]
    s_brk = breaks_up(s_kn, s_top, Cl, hz)
    P = pools_up_raw(O, H, Lo, Cl)
    p_org, p_kn, p_top, p_bot = P[:, 0].astype(np.int64), P[:, 1].astype(np.int64), P[:, 2], P[:, 3]
    p_brk = breaks_up(p_kn, p_top, Cl, hz)
    udays = np.unique(bday)
    dstart = np.searchsorted(bday, udays, "left")
    dend = np.searchsorted(bday, udays, "right")
    rows = []
    for q, d in enumerate(udays):
        dts = pd.Timestamp(d)
        if dts not in istop_by_day:
            continue
        lo_day = d - np.timedelta64(10, "D")
        q0 = np.searchsorted(udays, lo_day, "left")
        if q0 == q or not all(real_by_day.get(pd.Timestamp(x), False) for x in udays[q0:q + 1]):
            continue
        ws, a, b = dstart[q0], dstart[q], dend[q]
        if b - ws < 2 * L + 2:
            continue
        istop = istop_by_day[dts]
        # pools of this window (emission order) + de-dup vs the last 50 accepted
        sel = np.nonzero((p_org >= ws) & (p_kn < b))[0]
        acc = []
        for k in sel:
            t, bt = p_top[k], p_bot[k]
            if any(p_bot[j] <= t and bt <= p_top[j] for j in acc[-50:]):
                continue
            acc.append(k)
        acc = np.array(acc, np.int64)
        ssel = np.nonzero((s_org >= ws + L) & (s_kn < b))[0]
        z_kn = np.r_[s_kn[ssel], p_kn[acc]]
        z_edge = np.r_[s_top[ssel], p_top[acc]]
        z_brk = np.r_[s_brk[ssel], p_brk[acc]]
        z_brk = np.where(z_brk >= b, -1, z_brk)
        sk, stp, sbt, sbr = s_kn[ssel], s_top[ssel], s_bot[ssel], np.where(s_brk[ssel] >= b, -1, s_brk[ssel])
        pk, ptp, pbt, pbr = p_kn[acc], p_top[acc], p_bot[acc], p_brk[acc]
        for i in range(a, b):
            if bst[i] + tf > 930:
                continue
            cand = np.nonzero(pbr == i)[0]
            if len(cand) == 0:
                continue
            pool = -1
            for c in cand:
                if pk[c] > i:
                    continue
                if np.any((sbt <= ptp[c]) & (pbt[c] <= stp) & (sk <= i) & ((sbr < 0) | (sbr >= i))):
                    pool = c
                    break
            if pool < 0:
                continue
            close = Cl[i]
            done = bst[i] + tf
            if done < WIN_FROM or done > WIN_TO:
                continue
            live = (z_kn <= i) & ((z_brk < 0) | (z_brk > i)) & (z_edge > close)
            target = z_edge[live].min() if live.any() else np.nan
            if np.isfinite(target) and target - close < room * istop:
                continue
            level = ptp[pool]
            fbk = np.nonzero(Cl[i + 1:b] < level)[0]
            fb = bst[i + 1 + fbk[0]] + tf if len(fbk) else np.nan
            nlk = z_kn[(z_kn >= i + 1) & (z_kn < b)]
            nl = bst[nlk.min()] + tf if len(nlk) else np.nan
            xa = np.nanmin([fb, nl]) if np.isfinite([fb, nl]).any() else np.nan
            rows.append(dict(day=dts, done=int(done), close=float(close), level=float(level), istop=float(istop),
                             idx_stop=float(level - istop), target=float(target), exit_at=float(xa), book=tf))
    return pd.DataFrame(rows)


# ------------------------------------------------------------------------------------------------ exits (long)
def simulate(A, di, c0, istop, idx_stop, target, exit_at, hard_k=HARD_K, tgain_k=TGAIN_K, sq=SQC):
    """Vectorised long exits. A: dense o/h/l/c (D x 375); di day rows; c0 entry column (fill at its open).
    Returns entry, exit, exit column, reason code (0 sq, 1 index_stop, 2 time_stop, 3 index_target, 4 exit_at, 5 hard)."""
    N = len(di)
    O, H, Lw, C = A["o"][di], A["h"][di], A["l"][di], A["c"][di]
    cols = np.arange(375)[None, :]
    valid = np.isfinite(C)
    r = np.arange(N)
    # entry: open of the first valid bar >= c0
    nv = np.where(valid, cols, 10 ** 6)
    nxt = np.minimum.accumulate(nv[:, ::-1], axis=1)[:, ::-1]
    ce = nxt[r, np.minimum(c0, 374)]
    ok = ce < sq
    ce = np.where(ok, ce, 374)
    e = O[r, ce]
    after = (cols >= ce[:, None]) & valid
    okm = after & (cols <= sq - 2)
    hs = e - hard_k * istop
    hit_h = after & (cols < sq) & (Lw <= hs[:, None])
    BIG = 10 ** 6
    kb = np.where(hit_h.any(1), hit_h.argmax(1), BIG)
    m_is = okm & (Lw < idx_stop[:, None])
    k_is = np.where(m_is.any(1), m_is.argmax(1), BIG)
    tg = np.where(np.isfinite(target), target, np.inf)
    m_it = okm & (H >= tg[:, None])
    k_it = np.where(m_it.any(1), m_it.argmax(1), BIG)
    kT = ce + TIME_STOP - 1
    lvc = np.maximum.accumulate(np.where(valid, cols, -1), axis=1)
    ltp = C[r, np.maximum(lvc[r, np.minimum(kT, 374)], 0)]
    k_ts = np.where((kT <= sq - 2) & (ltp < e + tgain_k * istop - 1e-9), kT, BIG)
    xa = np.where(np.isfinite(exit_at), exit_at, -1).astype(np.int64)
    kx = np.where(xa > 0, np.maximum(xa - 1 - OPEN_M, ce), BIG)
    k_xa = np.where(kx <= sq - 2, kx, BIG)
    k_sq = np.full(N, sq - 1)
    st = np.stack([k_sq, k_is, k_ts, k_it, k_xa])
    w = st.argmin(0)
    km = st[w, r]
    bar = kb <= km
    k1 = np.minimum(km + 1, 374)
    xc = nxt[r, k1]
    lastv = lvc[:, -1]
    ex = np.where(xc < 10 ** 6, O[r, np.minimum(xc, 374)], C[r, np.maximum(lastv, 0)])
    xcol = np.where(xc < 10 ** 6, xc, lastv)
    kbc = np.minimum(kb, 374)
    hfill = np.minimum(hs, O[r, kbc])
    ex = np.where(bar, hfill, ex)
    xcol = np.where(bar, kbc, xcol)
    why = np.where(bar, 5, w)
    return dict(ok=ok & np.isfinite(e), ce=ce, e=e, x=ex, xc=xcol, why=why)


def entry_price(A, di, c0):
    C = A["c"][di]
    cols = np.arange(375)[None, :]
    nv = np.where(np.isfinite(C), cols, 10 ** 6)
    nxt = np.minimum.accumulate(nv[:, ::-1], axis=1)[:, ::-1]
    ce = nxt[np.arange(len(di)), np.minimum(c0, 374)]
    return A["o"][di, np.minimum(ce, 374)]


def book_filter(df):
    """one position at a time per (book, day): next entry only from the minute after the previous exit."""
    df = df.sort_values(["book", "day", "done"], kind="stable")
    keep = np.zeros(len(df), bool)
    cur, flat = None, -1
    for i, (bk, d, ce, xc) in enumerate(zip(df.book.values, df.day.values, df.ce.values, df.xc.values)):
        if (bk, d) != cur:
            cur, flat = (bk, d), -1
        if ce < flat:
            continue
        keep[i] = True
        flat = xc + 1
    return df[keep]


def run_instrument(df, istop_fn, real_min=200, books=(15, 5), n_rand=5, seed=0, extra=None):
    """signals (both books) -> exits -> book filter; plus n_rand random long entries per kept trade (same day, entry
    minute uniform in 09:20..14:00, identical exit distances / offsets)."""
    days, A, cnt = dense(df)
    atr, adv, pc = daily_stats(days, A)
    real = {pd.Timestamp(d): bool(c >= real_min) for d, c in zip(days, cnt)}
    ist = {}
    for q, d in enumerate(days):
        v = istop_fn(q, atr[q], pc[q])
        if v is not None and np.isfinite(v) and v > 0:
            ist[pd.Timestamp(d)] = v
    sig = [signals_up(df, ist, real, tf) for tf in books]
    sig = pd.concat([s for s in sig if len(s)], ignore_index=True) if any(len(s) for s in sig) else pd.DataFrame()
    if sig.empty:
        return sig, sig
    dmap = {pd.Timestamp(d): q for q, d in enumerate(days)}
    di = sig.day.map(dmap).to_numpy()
    c0 = sig.done.to_numpy() - OPEN_M
    s = simulate(A, di, c0, sig.istop.to_numpy(), sig.idx_stop.to_numpy(), sig.target.to_numpy(), sig.exit_at.to_numpy())
    for k in ("ce", "e", "x", "xc", "why"):
        sig[k] = s[k]
    sig = sig[s["ok"]]
    sig = book_filter(sig)
    sig["di"] = sig.day.map(dmap)
    sig["adv"] = adv[sig.di.to_numpy()]
    sig["atr"] = atr[sig.di.to_numpy()]
    # random entries with identical exits
    rng = np.random.default_rng(seed)
    R = sig.loc[sig.index.repeat(n_rand)].copy()
    R["rep"] = np.tile(np.arange(n_rand), len(sig))
    rc = rng.integers(WIN_FROM - OPEN_M, WIN_TO - OPEN_M + 1, len(R))
    di_r = R.di.to_numpy()
    e_r = entry_price(A, di_r, rc)
    e_sig = R.e.to_numpy()
    stop_r = e_r - (e_sig - R.idx_stop.to_numpy())
    tgt_r = e_r + (R.target.to_numpy() - e_sig)
    xa = R.exit_at.to_numpy()
    xa_r = np.where(np.isfinite(xa), rc + OPEN_M + (xa - R.done.to_numpy()), np.nan)
    s2 = simulate(A, di_r, rc, R.istop.to_numpy(), stop_r, tgt_r, xa_r)
    R["done"] = rc + OPEN_M
    for k in ("ce", "e", "x", "xc", "why"):
        R[k] = s2[k]
    R = R[s2["ok"]]
    return sig.reset_index(drop=True), R.reset_index(drop=True)


def stock_istop(q, atr, pc):
    if not np.isfinite(atr):
        return None
    return max(K_ATR * atr, ISTOP_FLOOR)
