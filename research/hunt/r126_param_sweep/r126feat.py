"""R126 inputs (PREREG section 2): 118 variants of every indicator / data family on wicks, plus the index-exit option
trades at three exit sizes, for R124's rows from 20 Oct 2024.

Every input at row t reads bars <= t (whole-series indicators are causal: rolling windows and EMAs only look back;
the tf-minute candle read at t is the last COMPLETED one). Prices are wick midpoints (high + low) / 2; ranges use
highs and lows; no open or close is used (the trade's entry reference is the one exception, as R124).
"""
from __future__ import annotations

import math
import os
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
for p in (str(HERE.parent / "r125_levels_flow"), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r125feat as F5  # noqa: E402  (puts r124 / r123 / of1 on the path)
import r124cfg as K  # noqa: E402
import r124data as RD  # noqa: E402
import r123data as D3  # noqa: E402
import r124zig as Z  # noqa: E402

OUT = Path(os.environ.get("R126_OUT", K.K3.CACHE_ROOT / "r126")).expanduser()
START = date(2024, 10, 20)
N = K.N
EXITS = (0.67, 1.0, 1.5)

# ------------------------------------------------------------------------------------------------ the grid
BB = [(n, k, tf) for n in (10, 20, 50) for k in (1.5, 2.0, 2.5) for tf in (1, 5)]
MACD = [(f, s, g, tf) for (f, s, g) in ((12, 26, 9), (8, 21, 5), (5, 35, 5)) for tf in (1, 5)]
RSI = [(n, tf) for n in (7, 14, 21) for tf in (1, 5, 15)]
ST = [(n, mult, tf) for n in (7, 10, 14) for mult in (2.0, 3.0, 4.0) for tf in (1, 5)]
EMA = [(n, tf) for n in (5, 9, 20, 50, 100, 200) for tf in (1, 5)]
CROSS = [(a, b, tf) for (a, b) in ((5, 20), (9, 21), (20, 50)) for tf in (1, 5)]
VWAP = ("session", 30, 60, 120)
VOLW = [(w, b) for w in (5, 15, 30, 60) for b in (5, 10, 20)]
PROF = [(0, 0.7), (1, 0.7), (5, 0.7), (10, 0.7), (5, 0.6), (5, 0.8)]
SR = [(k, lb) for k in (3, 5, 10) for lb in (1, 5)]
SWEEP = [(fail, look) for fail in (1, 3, 5) for look in (10, 30)]
POOL = (10, 6, 3)                                    # gap = m / value
CANDLE = (1, 3, 5, 15)
FLOW_CP = (3, 5, 15, 30)
FLOW_BU = (5, 15, 30)


def variant_names() -> dict:
    """{family: {variant: [input columns]}} for all 118 variants."""
    V: dict = {}

    def add(fam, var, names):
        V.setdefault(fam, {})[var] = [f"{fam}__{var}__{x}" for x in names]
    for n, k, tf in BB:
        add("bb", f"n{n}_k{k}_tf{tf}", ("up", "lo", "bw", "squeeze"))
    for f, s, g, tf in MACD:
        add("macd", f"{f}_{s}_{g}_tf{tf}", ("line", "hist", "hist_chg"))
    for n, tf in RSI:
        add("rsi", f"n{n}_tf{tf}", ("rsi", "chg"))
    for n, mult, tf in ST:
        add("st", f"n{n}_m{mult}_tf{tf}", ("dir", "dist", "age"))
    for n, tf in EMA:
        add("ema", f"n{n}_tf{tf}", ("dist", "slope"))
    for a, b, tf in CROSS:
        add("cross", f"{a}_{b}_tf{tf}", ("age", "gap"))
    for w in VWAP:
        add("vwap", f"{w}", ("dist", "band"))
    for w, b in VOLW:
        add("vol", f"w{w}_b{b}", ("ratio",))
    for d, va in PROF:
        add("prof", f"d{d}_va{int(va * 100)}", ("poc", "vah", "val", "in_va", "node"))
    for k, lb in SR:
        add("sr", f"k{k}_lb{lb}", ("res", "sup", "res_str", "sup_str", "n_above", "n_below"))
    for fail, look in SWEEP:
        add("sweep", f"f{fail}_l{look}", ("hi", "lo", "since", "poke"))
    for gdiv in POOL:
        add("pool", f"g{gdiv}", ("up", "dn"))
    for tf in CANDLE:
        add("candle", f"tf{tf}", F5.CANDLE_KEYS)
    for w in FLOW_CP:
        add("flow", f"cp{w}", ("cp",))
    for w in FLOW_BU:
        add("flow", f"bu{w}", ("bu",))
    add("base125", "default", ("fii_ls", "fii_ls_chg", "pro_stance", "pro_stance_chg", "cli_stance", "hilo_net"))
    return V


VARIANTS = variant_names()
ALL_COLS = [c for fam in VARIANTS.values() for cols in fam.values() for c in cols]
FAMILY_COLS = {fam: [c for cols in v.values() for c in cols] for fam, v in VARIANTS.items()}


# ------------------------------------------------------------------------------------------------ series helpers
def tf_series(h: np.ndarray, l: np.ndarray, tf: int) -> tuple[np.ndarray, np.ndarray]:
    """Flat tf-minute candles (sessions x 375 / tf): highs and lows from the 1-minute wicks."""
    D = h.shape[0]
    if tf == 1:
        return h.reshape(-1), l.reshape(-1)
    with np.errstate(all="ignore"), __import__("warnings").catch_warnings():
        __import__("warnings").simplefilter("ignore", RuntimeWarning)
        H = np.nanmax(h.reshape(D, -1, tf), 2).reshape(-1)
        L = np.nanmin(l.reshape(D, -1, tf), 2).reshape(-1)
    return H, L


def cidx(i: np.ndarray, t: np.ndarray, tf: int) -> np.ndarray:
    """Flat index of the last COMPLETED tf-minute candle at bar t of session i (-1 if none yet today... the candle
    before the session's first is the previous session's last, which is complete)."""
    return i * (N // tf) + (t + 1) // tf - 1


def ewm(x: np.ndarray, span: int | None = None, alpha: float | None = None) -> np.ndarray:
    s = pd.Series(x).ffill()
    return (s.ewm(span=span, adjust=False) if span else s.ewm(alpha=alpha, adjust=False)).mean().values


def span_signed_v(lo, hi, lvl, m):
    """Vectorised signed wick-span distance in m (+ the span wholly above the level, - wholly below, 0 touching)."""
    lo, hi, lvl, m = (np.asarray(a, dtype=float) for a in (lo, hi, lvl, m))
    out = np.where(lo > lvl, (lo - lvl) / m, np.where(hi < lvl, (hi - lvl) / m, 0.0))
    return np.where(np.isfinite(lo) & np.isfinite(hi) & np.isfinite(lvl), out, np.nan)


def rsi_series(x: np.ndarray, n: int) -> np.ndarray:
    s = pd.Series(x).ffill()
    d = s.diff()
    up = d.clip(lower=0).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    dn = (-d).clip(lower=0).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    with np.errstate(all="ignore"):
        r = 100 - 100 / (1 + up / dn)
    return np.where(dn.values == 0, 100.0, r.values)


def age_since_change(sig: np.ndarray, cap: int = 120) -> np.ndarray:
    """Signed candles since the last sign change of `sig` (sign of the current regime), capped."""
    s = np.sign(np.nan_to_num(sig))
    ch = np.r_[False, (s[1:] != s[:-1]) & (s[1:] != 0)]
    idx = np.where(ch, np.arange(len(s)), -10 ** 9)
    last = np.maximum.accumulate(idx)
    age = np.minimum(np.arange(len(s)) - last, cap)
    return s * age


# ------------------------------------------------------------------------------------------------ series families
def series_features(I, v_flat: np.ndarray, R: pd.DataFrame) -> dict:
    """BB, MACD, RSI, Supertrend, EMA, crosses and VWAP variants for rows R (columns i, t, m, lo, hi)."""
    out = {}
    i, t, m, lo, hi = (R[c].values for c in ("i", "t", "m", "lo", "hi"))
    g = i * N + t
    cache = {}
    for tf in (1, 5, 15):
        H, L = tf_series(I.g.h, I.g.l, tf)
        cache[tf] = (H, L, (H + L) / 2, cidx(i, t, tf))
    for n, k, tf in BB:
        H, L, mid, ki = cache[tf]
        s = pd.Series(mid).ffill()
        mu = s.rolling(n, min_periods=n).mean().values
        sd = s.rolling(n, min_periods=n).std(ddof=0).values
        up, dn = mu + k * sd, mu - k * sd
        bw = (up - dn) / mu * 100
        q20 = pd.Series(bw).rolling(120, min_periods=30).quantile(0.2).values
        p = f"bb__n{n}_k{k}_tf{tf}__"
        kk = np.clip(ki, 0, None)
        ok = ki >= 0
        out[p + "up"] = np.where(ok, span_signed_v(lo, hi, up[kk], m), np.nan)
        out[p + "lo"] = np.where(ok, span_signed_v(lo, hi, dn[kk], m), np.nan)
        out[p + "bw"] = np.where(ok, bw[kk], np.nan)
        out[p + "squeeze"] = np.where(ok, (bw[kk] <= q20[kk]).astype(float), np.nan)
    for f, s_, sg, tf in MACD:
        H, L, mid, ki = cache[tf]
        line = ewm(mid, span=f) - ewm(mid, span=s_)
        sig = pd.Series(line).ewm(span=sg, adjust=False).mean().values
        hist = line - sig
        kk = np.clip(ki, 0, None)
        p = f"macd__{f}_{s_}_{sg}_tf{tf}__"
        out[p + "line"] = line[kk] / m
        out[p + "hist"] = hist[kk] / m
        out[p + "hist_chg"] = (hist[kk] - hist[np.clip(kk - 3, 0, None)]) / m
    for n, tf in RSI:
        H, L, mid, ki = cache[tf]
        r = rsi_series(mid, n)
        kk = np.clip(ki, 0, None)
        p = f"rsi__n{n}_tf{tf}__"
        out[p + "rsi"] = r[kk]
        out[p + "chg"] = r[kk] - r[np.clip(kk - 3, 0, None)]
    for n, mult, tf in ST:
        H, L, mid, ki = cache[tf]
        line, d = F5.supertrend(H, L, n, mult)
        a = age_since_change(d)
        kk = np.clip(ki, 0, None)
        p = f"st__n{n}_m{mult}_tf{tf}__"
        out[p + "dir"] = d[kk]
        out[p + "dist"] = span_signed_v(lo, hi, line[kk], m)
        out[p + "age"] = a[kk]
    emas = {}
    for n, tf in EMA:
        H, L, mid, ki = cache[tf]
        e = ewm(mid, span=n)
        emas[(n, tf)] = e
        kk = np.clip(ki, 0, None)
        p = f"ema__n{n}_tf{tf}__"
        out[p + "dist"] = span_signed_v(lo, hi, e[kk], m)
        out[p + "slope"] = (e[kk] - e[np.clip(kk - 3, 0, None)]) / m
    for a_, b_, tf in CROSS:
        H, L, mid, ki = cache[tf]
        ea = emas.get((a_, tf), ewm(mid, span=a_))
        eb = emas.get((b_, tf), ewm(mid, span=b_))
        diff = ea - eb
        kk = np.clip(ki, 0, None)
        p = f"cross__{a_}_{b_}_tf{tf}__"
        out[p + "age"] = age_since_change(diff)[kk]
        out[p + "gap"] = diff[kk] / m
    # VWAP on 1-minute wick midpoints, weights = ATM+-3 option volume (the index has no volume before 2026)
    h1, l1 = I.g.h.reshape(-1), I.g.l.reshape(-1)
    mid1 = (h1 + l1) / 2
    w = np.where(np.isfinite(mid1), v_flat, 0.0)
    mz = np.nan_to_num(mid1)
    for win in VWAP:
        if win == "session":
            day = np.repeat(np.arange(I.g.h.shape[0]), N)
            cw = pd.Series(w).groupby(day).cumsum().values
            cp = pd.Series(w * mz).groupby(day).cumsum().values
            cq = pd.Series(w * mz * mz).groupby(day).cumsum().values
        else:
            cw = pd.Series(w).rolling(win, min_periods=1).sum().values
            cp = pd.Series(w * mz).rolling(win, min_periods=1).sum().values
            cq = pd.Series(w * mz * mz).rolling(win, min_periods=1).sum().values
        with np.errstate(all="ignore"):
            vw = cp / cw
            sd = np.sqrt(np.clip(cq / cw - vw * vw, 0, None))
        p = f"vwap__{win}__"
        out[p + "dist"] = span_signed_v(lo, hi, vw[g], m)
        with np.errstate(all="ignore"):
            out[p + "band"] = np.where(sd[g] > 0, ((lo + hi) / 2 - vw[g]) / sd[g], np.nan)
    return out


# ------------------------------------------------------------------------------------------------ one index
def sweeps_p(h, l, t, hi_at, lo_at, m, fail, look):
    out = dict(hi=0.0, lo=0.0, since=np.nan, poke=0.0)
    last_j, poke = None, 0.0
    for j in range(max(t - look + 1, 0), t - fail + 1):
        if not (np.isfinite(h[j]) and np.isfinite(l[j])):
            continue
        for L in hi_at(j):
            if h[j] > L and np.all(np.nan_to_num(h[j + 1:j + fail + 1], nan=-np.inf) < L):
                out["hi"] = 1.0
                if last_j is None or j >= last_j:
                    last_j, poke = j, (h[j] - L) / m
                break
        for L in lo_at(j):
            if l[j] < L and np.all(np.nan_to_num(l[j + 1:j + fail + 1], nan=np.inf) > L):
                out["lo"] = 1.0
                if last_j is None or j >= last_j:
                    last_j, poke = j, (L - l[j]) / m
                break
    if last_j is not None:
        out["since"], out["poke"] = float(t - last_j), float(poke)
    return out


def index_features(part: str, und: str, keys: pd.DataFrame, log=print) -> pd.DataFrame:
    last_day = K.DESIGN[1] if part == "design" else K.HOLDOUT[1]
    grids = {u: D3.load_grid(u, last_day) for u in K.UNDS}
    vix = D3.load_grid("INDIAVIX", last_day)
    I = D3.IndexData(und, grids, vix)
    nifty = D3.IndexData("NIFTY", grids, vix)
    LO = D3.LakeOptions(und, last_day)
    exp = LO.expiry_days()
    P = F5.participants_daily()
    bw = F5.BIN[und]
    lot = K.LOT[und]
    want = keys.groupby("day").row.apply(list).to_dict()
    days = sorted(want)
    first = max(I.dpos[days[0]] - 25, 0)
    D = I.g.h.shape[0]
    v_flat = np.zeros(D * N)
    hist_by_day, piv = {}, {k: {} for k in (3, 5, 10)}
    nb = int(np.nanmax(I.g.h) * 1.3 / bw) + 10
    per_row = []
    meta = []
    last_wanted = days[-1]
    chains = {}
    for i in range(first, len(I.days)):
        d = I.days[i]
        if d > last_wanted:
            break
        opts = LO.day(d, I.g.c[i])
        ch = opts.chain if opts is not None else None
        v = F5.minute_volume(ch)
        v_flat[i * N:(i + 1) * N] = v
        hist_by_day[i] = F5.spread_hist(I.g.l[i], I.g.h[i], v, bw, 0, nb)
        for k in piv:
            piv[k][i] = F5.pivots(I.g.h[i], I.g.l[i], k)
        if d not in want:
            continue
        ni = nifty.dpos.get(d)
        m, _ = Z.thresholds(I.d_open[i], nifty.d_open[ni] if ni is not None else np.nan)
        if not np.isfinite(m):
            continue
        h, l = I.g.h[i], I.g.l[i]
        is_exp = d in exp
        last = K.K3.EXIT_LAST_EXPIRY if is_exp else K.K3.EXIT_LAST
        flow_day = F5.lagged(P, d)
        pdh = np.nanmax(I.g.h[i - 1]) if i > 0 else np.nan
        pdl = np.nanmin(I.g.l[i - 1]) if i > 0 else np.nan
        prev5 = [k for k in range(i - 5, i) if k in piv[5]]
        with np.errstate(all="ignore"):
            h5p = max([np.nanmax(I.g.h[k]) for k in prev5], default=np.nan)
            l5p = min([np.nanmin(I.g.l[k]) for k in prev5], default=np.nan)
        base_by_dd = {dd: sum((hist_by_day[x] for x in range(i - dd, i) if x in hist_by_day), np.zeros(nb))
                      for dd in sorted({dd for dd, _ in PROF})}
        today_hist = np.zeros(nb)
        done = -1
        for t in sorted(want[d]):
            lo_t, hi_t = l[t], h[t]
            if not (np.isfinite(lo_t) and np.isfinite(hi_t)):
                continue
            f = dict(und=und, day=d, row=t)
            # --- support / resistance variants
            for k, lb in SR:
                hp, lp = piv[k][i]
                prev = [x for x in range(i - lb, i) if x in piv[k]]
                ph = [p for x in prev for _, p, _ in piv[k][x][0]]
                pl = [p for x in prev for _, p, _ in piv[k][x][1]]
                lv = F5.cluster([p for _, p, c in hp if c <= t] + [p for _, p, c in lp if c <= t] + ph + pl, m / 3)
                s = F5.sr_features(lv, hi_t, lo_t, m, np.nan, np.nan)
                pre = f"sr__k{k}_lb{lb}__"
                f[pre + "res"], f[pre + "sup"] = s["sr_res_dist"], s["sr_sup_dist"]
                f[pre + "res_str"], f[pre + "sup_str"] = s["sr_res_str"], s["sr_sup_str"]
                f[pre + "n_above"], f[pre + "n_below"] = s["sr_n_above"], s["sr_n_below"]
            # --- pools (k = 5, 5 sessions) at three gaps; sweeps at six settings
            hp5, lp5 = piv[5][i]
            ph5 = [p for x in prev5 for _, p, _ in piv[5][x][0]]
            pl5 = [p for x in prev5 for _, p, _ in piv[5][x][1]]
            pools_at = {}
            for gdiv in POOL:
                up = F5.pools(hp5, m / gdiv, t, h, "high", ph5)
                dn = F5.pools(lp5, m / gdiv, t, l, "low", pl5)
                pools_at[gdiv] = (up, dn)
                upa, dnb = [p for p in up if p > hi_t], [p for p in dn if p < lo_t]
                f[f"pool__g{gdiv}__up"] = (min(upa) - hi_t) / m if upa else np.nan
                f[f"pool__g{gdiv}__dn"] = (lo_t - max(dnb)) / m if dnb else np.nan
            nh = [x for x in (pdh, h5p) if np.isfinite(x)] + pools_at[6][0]
            nl = [x for x in (pdl, l5p) if np.isfinite(x)] + pools_at[6][1]
            for fail, look in SWEEP:
                s = sweeps_p(h, l, t, lambda j: [p for _, p, c in hp5 if c <= j] + nh,
                             lambda j: [p for _, p, c in lp5 if c <= j] + nl, m, fail, look)
                for kk_, vv in s.items():
                    f[f"sweep__f{fail}_l{look}__{kk_}"] = vv
            # --- volume windows / baselines
            for w_, b_ in VOLW:
                a = v_flat[i * N + max(t - w_ + 1, 0):i * N + t + 1].sum()
                prevs = [v_flat[x * N + max(t - w_ + 1, 0):x * N + t + 1].sum() for x in range(i - b_, i) if x >= 0]
                f[f"vol__w{w_}_b{b_}__ratio"] = math.log((a + 1) / (np.mean(prevs) + 1)) if prevs else np.nan
            # --- volume profile variants
            if t > done:
                today_hist += F5.spread_hist(l[done + 1:t + 1], h[done + 1:t + 1], v_flat[i * N + done + 1:i * N + t + 1],
                                             bw, 0, nb)
                done = t
            for dd, va in PROF:
                pf = profile_va(base_by_dd[dd] + today_hist, bw, lo_t, hi_t, m, va)
                for kk_, vv in pf.items():
                    f[f"prof__d{dd}_va{int(va * 100)}__{kk_}"] = vv
            # --- wick candles
            for tf in CANDLE:
                cf = F5.candle_features(h, l, t, tf)
                for key in F5.CANDLE_KEYS:
                    f[f"candle__tf{tf}__{key}"] = cf[f"c{tf}_{key}"]
            # --- option flow
            for w_ in FLOW_CP:
                if ch is None:
                    f[f"flow__cp{w_}__cp"] = np.nan
                    continue
                vv = np.clip(ch["volume"][max(t - w_ + 1, 0):t + 1, F5.ATM3, :], 0, None)
                a = np.nansum(vv, axis=(0, 1))
                f[f"flow__cp{w_}__cp"] = math.log((a[0] + 1) / (a[1] + 1))
            for w_ in FLOW_BU:
                f[f"flow__bu{w_}__bu"] = buildup(ch, t, w_)
            # --- R125 defaults
            for src, dst in (("fl_fii_ls", "fii_ls"), ("fl_fii_ls_chg", "fii_ls_chg"), ("fl_pro_stance", "pro_stance"),
                             ("fl_pro_stance_chg", "pro_stance_chg"), ("fl_cli_stance", "cli_stance")):
                f[f"base125__default__{dst}"] = flow_day.get(src, np.nan)
            f["base125__default__hilo_net"] = F5.hilo_net(h, l, t)
            # --- the option trades at three exit sizes (index wicks touch entry +- e*m)
            k_atm = D3.atm(I.cf[I.gpos(i, t)], K.STEP[und])
            ca, pa = (opts.contracts.get(("C", k_atm)), opts.contracts.get(("P", k_atm))) if opts else (None, None)
            for hz in K.HS:
                end = min(t + hz, last)
                for e in EXITS:
                    for name, sides in (("C", (("C", ca),)), ("P", (("P", pa),))):
                        tr = (RD.index_exit_trade(opts, sides, h, l, I.g.o[i], t, end, e * m, lot, d, und)
                              if t + hz <= last else None)
                        pre = f"tr{hz}_e{e}_{name}"
                        f[pre + "_net"] = np.nan if tr is None else tr["net"]
                        f[pre + "_netx2"] = np.nan if tr is None else tr["net_x2"]
                        f[pre + "_exit"] = -1 if tr is None else tr["exit_row"]
                        f[pre + "_why"] = "" if tr is None else tr["why"]
            per_row.append(f)
            meta.append(dict(i=i, t=t, m=m, lo=lo_t, hi=hi_t))
    X = pd.DataFrame(per_row)
    if len(X):
        S = series_features(I, v_flat, pd.DataFrame(meta))
        X = pd.concat([X, pd.DataFrame(S)], axis=1)
    log(f"R126 {und} {part}: {len(X)} rows", flush=True)
    return X


def profile_va(hist, bw, lo_t, hi_t, m, va):
    tot = hist.sum()
    if not tot > 0:
        return dict(poc=np.nan, vah=np.nan, val=np.nan, in_va=np.nan, node=np.nan)
    poc = (int(np.argmax(hist)) + 0.5) * bw
    order = np.argsort(hist)[::-1]
    take = order[:int(np.searchsorted(np.cumsum(hist[order]), va * tot)) + 1]
    vah, val = (take.max() + 1) * bw, take.min() * bw
    i0, i1 = max(int(math.floor(lo_t / bw)), 0), min(int(math.floor(hi_t / bw)), len(hist) - 1)
    nz = hist[hist > 0]
    here = hist[i0:i1 + 1].mean() if i1 >= i0 else 0.0
    return dict(poc=F5.Z_dist(lo_t, hi_t, poc) / m, vah=(vah - hi_t) / m, val=(lo_t - val) / m,
                in_va=float(lo_t <= vah and hi_t >= val), node=float(here / nz.mean()) if len(nz) else np.nan)


def buildup(ch, t, w):
    if ch is None or t < w:
        return np.nan
    cl = D3.ffill_rows(ch["close"][:t + 1, F5.ATM3, :].transpose(1, 2, 0))
    oi = D3.ffill_rows(ch["oi"][:t + 1, F5.ATM3, :].transpose(1, 2, 0))
    dp, doi = cl[..., t] - cl[..., t - w], oi[..., t] - oi[..., t - w]
    ok = np.isfinite(dp) & np.isfinite(doi) & (doi > 0)
    s = np.zeros_like(dp)
    s[:, 0] = np.where(dp[:, 0] > 0, 1, -1)
    s[:, 1] = np.where(dp[:, 1] < 0, 1, -1)
    return float(s[ok].mean()) if ok.any() else 0.0


def _one(args):
    part, und, keys = args
    return index_features(part, und, keys)


def build(part: str, R124_OUT: Path):
    from multiprocessing import Pool
    keys = pd.read_parquet(R124_OUT / f"rows_{part}.parquet", columns=["und", "day", "row"])
    keys = keys[keys.day >= START]
    with Pool(len(K.UNDS)) as pool:
        res = pool.map(_one, [(part, u, keys[keys.und == u]) for u in K.UNDS])
    X = pd.concat(res, ignore_index=True)
    OUT.mkdir(parents=True, exist_ok=True)
    X.to_parquet(OUT / f"feat_{part}.parquet")
    return X
