"""R33 flags (PREREG.md): every indicator / pattern variant as a binary flag, on the option's own 1-minute bars and on the
index's (read in the option's direction), plus the 30-candle shapes. Same rows as R32. Row t reads rows <= t only.

    python3 research/hunt/r33/r33flags.py build      # -> <cache>/r33/flags.parquet, shapes.npz
"""
from __future__ import annotations

import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT / "research" / "hunt" / "r32", HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))

import r32feat as X  # noqa: E402  (r25-r29 on the path)
import r25cfg as K  # noqa: E402
import r25data as D  # noqa: E402
import r27feat as F  # noqa: E402
from r25data import C, H, L, N, O, OI, V  # noqa: E402

OUT = K.CACHE.root / "r33"
SHAPE = 30


# ------------------------------------------------------------------------------------------------ fast past-only primitives
def S(x):
    return pd.Series(x)


def ema(x, n):
    return S(x).ewm(span=n, adjust=False, ignore_na=True).mean().values


def wilder(x, n):
    return S(x).ewm(alpha=1.0 / n, adjust=False, ignore_na=True).mean().values


def rmax(x, n):
    return S(x).rolling(n, min_periods=1).max().values


def rmin(x, n):
    return S(x).rolling(n, min_periods=1).min().values


def rmean(x, n):
    return S(x).rolling(n, min_periods=1).mean().values


def rstd(x, n):
    return S(x).rolling(n, min_periods=2).std().values


def lag(x, n):
    return X.lag(x, n)


def within(ev, k):
    """ev happened in the last k bars (including now)."""
    return rmax(ev.astype(float), k) > 0


def cross_up(a, b):
    return np.r_[False, (a[1:] > b[1:]) & (a[:-1] <= b[:-1])]


def supertrend(h, l, c, n, m):
    first = np.argmax(np.isfinite(c)) if np.isfinite(c).any() else len(c)
    st = np.zeros(len(c))
    if first < len(c):
        import r28lib
        st[first:] = r28lib.supertrend(h[first:], l[first:], c[first:], n, m)
    return st


# ------------------------------------------------------------------------------------------------ the library
def library(o, h, l, c, mv_a: tuple, v=None, vwap_ref=None, sgn: int = 1) -> dict:
    """Flags on a bar series. Prices are already mirrored for a put's index (x = -price); `mv_a` are the move thresholds in
    fractions; `sgn` signs the moves (computed on the true series by the caller through `c_true`)."""
    f = {}
    for a_, b_ in ((5, 13), (9, 21), (13, 34), (20, 50)):
        ea, eb = ema(c, a_), ema(c, b_)
        up = cross_up(ea, eb)
        dn = cross_up(eb, ea)
        f[f"ema{a_}_{b_}_above"] = ea > eb
        for k in (1, 3, 5):
            f[f"ema{a_}_{b_}_xup{k}"] = within(up, k)
        f[f"ema{a_}_{b_}_xdn3"] = within(dn, 3)
    for n in (9, 20, 50):
        f[f"above_ema{n}"] = c > ema(c, n)
    d = np.r_[np.nan, np.diff(c)]
    for n in (7, 14, 21):
        g, ls = wilder(np.where(d > 0, d, 0.0), n), wilder(np.where(d < 0, -d, 0.0), n)
        with np.errstate(invalid="ignore", divide="ignore"):
            rsi = 100 - 100 / (1 + g / ls)
        for lvl, op in ((20, "lt"), (30, "lt"), (70, "gt"), (80, "gt")):
            f[f"rsi{n}_{op}{lvl}"] = rsi < lvl if op == "lt" else rsi > lvl
        f[f"rsi{n}_rising"] = rsi > lag(rsi, 3)
    for a_, b_, s_ in ((12, 26, 9), (5, 35, 5), (8, 17, 9)):
        m = ema(c, a_) - ema(c, b_)
        sg = ema(m, s_)
        hist = m - sg
        f[f"macd{a_}_{b_}_{s_}_pos"] = hist > 0
        f[f"macd{a_}_{b_}_{s_}_rising"] = hist > lag(hist, 1)
        f[f"macd{a_}_{b_}_{s_}_xup3"] = within(cross_up(m, sg), 3)
    for n, k in ((20, 2.0), (20, 2.5), (10, 1.5)):
        mu, sd = rmean(c, n), rstd(c, n)
        up, lo = mu + k * sd, mu - k * sd
        bw = (up - lo) / np.abs(mu)
        f[f"bb{n}_{k}_below"] = c < lo
        f[f"bb{n}_{k}_above"] = c > up
        q = S(bw).expanding(min_periods=10).quantile(1 / 3).values
        f[f"bb{n}_{k}_squeeze"] = bw <= q
    for n, m in ((7, 3.0), (10, 2.0), (10, 3.0), (14, 2.0)):
        st = supertrend(h, l, c, n, m)
        f[f"st{n}_{m:g}_green"] = st == 1
        f[f"st{n}_{m:g}_flip3"] = within(np.r_[False, (st[1:] == 1) & (st[:-1] != 1)], 3)
    up_, dn_ = np.r_[np.nan, h[1:] - h[:-1]], np.r_[np.nan, l[:-1] - l[1:]]
    pdm = np.where((up_ > dn_) & (up_ > 0), up_, 0.0)
    ndm = np.where((dn_ > up_) & (dn_ > 0), dn_, 0.0)
    tr = np.r_[h[0] - l[0], np.maximum(h[1:] - l[1:], np.maximum(np.abs(h[1:] - c[:-1]), np.abs(l[1:] - c[:-1])))]
    for n in (7, 14):
        atr = wilder(tr, n)
        with np.errstate(invalid="ignore", divide="ignore"):
            pdi, ndi = 100 * wilder(pdm, n) / atr, 100 * wilder(ndm, n) / atr
            adx = wilder(100 * np.abs(pdi - ndi) / (pdi + ndi), n)
        for lvl in (20, 25, 40):
            f[f"adx{n}_gt{lvl}"] = adx > lvl
        f[f"di{n}_plus"] = pdi > ndi
    for n, s_ in ((14, 3), (5, 3)):
        hh, ll = rmax(h, n), rmin(l, n)
        with np.errstate(invalid="ignore", divide="ignore"):
            kk = 100 * (c - ll) / (hh - ll)
        dd = rmean(kk, s_)
        f[f"stoch{n}_lt20"] = kk < 20
        f[f"stoch{n}_gt80"] = kk > 80
        f[f"stoch{n}_xup3"] = within(cross_up(kk, dd), 3)
    tp = (h + l + c) / 3
    mad = S(tp).rolling(20, min_periods=5).apply(lambda w: np.mean(np.abs(w - w.mean())), raw=True).values
    with np.errstate(invalid="ignore", divide="ignore"):
        cci = (tp - rmean(tp, 20)) / (0.015 * mad)
    f["cci20_lt-100"], f["cci20_gt100"] = cci < -100, cci > 100
    for n in (10, 20, 30):
        f[f"donch{n}_high"] = c >= lag(rmax(h, n), 1)
        f[f"donch{n}_low"] = c <= lag(rmin(l, n), 1)
    mid, atr20 = ema(c, 20), wilder(tr, 20)
    f["kelt_above"], f["kelt_below"] = c > mid + 1.5 * atr20, c < mid - 1.5 * atr20
    if vwap_ref is not None:
        dv = c / vwap_ref - 1 if sgn > 0 else vwap_ref / c - 1      # mirrored series: distance in the option's direction
        f["vwap_above"], f["vwap_below"] = dv > 0, dv < 0
        f["vwap_gt1"], f["vwap_gt3"] = dv > 0.01, dv > 0.03
        f["vwap_lt1"], f["vwap_lt3"] = dv < -0.01, dv < -0.03
    body, rng = np.abs(c - o), h - l
    with np.errstate(invalid="ignore", divide="ignore"):
        br = body / rng
        upw, low = (h - np.maximum(o, c)) / rng, (np.minimum(o, c) - l) / rng
    g_, r_ = c > o, c < o
    f["c_doji"] = br < 0.1
    f["c_hammer"] = (low > 0.6) & (br < 0.3)
    f["c_shooting"] = (upw > 0.6) & (br < 0.3)
    f["c_maru_green"] = g_ & (br > 0.9)
    f["c_maru_red"] = r_ & (br > 0.9)
    f["c_bigbody"] = body > 2 * lag(rmean(body, 10), 1)
    po, pc = lag(o, 1), lag(c, 1)
    f["c_engulf_bull"] = g_ & (pc < po) & (c >= po) & (o <= pc)
    f["c_engulf_bear"] = r_ & (pc > po) & (c <= po) & (o >= pc)
    f["c_inside"] = (h < lag(h, 1)) & (l > lag(l, 1))
    f["c_outside"] = (h > lag(h, 1)) & (l < lag(l, 1))
    f["c_harami_bull"] = g_ & (pc < po) & (np.maximum(o, c) < po) & (np.minimum(o, c) > pc)
    g1, g2 = lag(g_.astype(float), 1) > 0, lag(g_.astype(float), 2) > 0
    r1, r2 = lag(r_.astype(float), 1) > 0, lag(r_.astype(float), 2) > 0
    f["c_3soldiers"] = g_ & g1 & g2 & (c > pc) & (pc > lag(c, 2))
    f["c_3crows"] = r_ & r1 & r2 & (c < pc) & (pc < lag(c, 2))
    small1 = lag(br, 1) < 0.3
    f["c_morning"] = g_ & small1 & r2 & (c > (lag(o, 2) + lag(c, 2)) / 2)
    f["c_evening"] = r_ & small1 & g2 & (c < (lag(o, 2) + lag(c, 2)) / 2)
    gs, rs = X.streak(g_), X.streak(r_)
    for n in (2, 3, 4, 5):
        f[f"run_green{n}"], f[f"run_red{n}"] = gs >= n, rs >= n
    hh_, ll_ = X.streak(np.r_[False, h[1:] > h[:-1]]), X.streak(np.r_[False, l[1:] < l[:-1]])
    for n in (2, 3):
        f[f"hh{n}"], f[f"ll{n}"] = hh_ >= n, ll_ >= n
    rng5 = rmax(h, 5) - rmin(l, 5)
    hi30, lo30 = rmax(h, 30), rmin(l, 30)
    with np.errstate(invalid="ignore", divide="ignore"):
        f["comp5_30"] = rng5 / (hi30 - lo30) < 0.3
        pos = (c - lo30) / (hi30 - lo30)
    f["pos30_top20"], f["pos30_bot20"] = pos > 0.8, pos < 0.2
    return f


def moves(c_true, sgn, a_list) -> dict:
    f = {}
    for n in (5, 15, 30):
        r = sgn * (c_true / lag(c_true, n) - 1)
        for a in a_list:
            f[f"mv{n}_dn{a * 100:g}"] = r < -a
            f[f"mv{n}_up{a * 100:g}"] = r > a
    return f


def volume_flags(c, v, h, l) -> dict:
    f = {}
    vz = np.nan_to_num(v)
    e10, e30 = lag(ema(vz, 10), 1), lag(ema(vz, 30), 1)
    with np.errstate(invalid="ignore", divide="ignore"):
        for m in (1.5, 2, 3, 5):
            f[f"vol_x{m:g}_e10"] = vz >= m * e10
        f["vol_x2_e30"] = vz >= 2 * e30
        f["vol_dry"] = vz < 0.5 * e10
    f["vol_rise3"] = (vz > lag(vz, 1)) & (lag(vz, 1) > lag(vz, 2)) & (lag(vz, 2) > lag(vz, 3))
    # vertical volume: the option's volume profile since 12:00 by price (in 2-tick buckets)
    poc_above = np.zeros(len(c), bool)
    poc_below = np.zeros(len(c), bool)
    near20 = np.zeros(len(c), bool)
    prof: dict = {}
    tot = 0.0
    for t in range(len(c)):
        if t >= X.T0 and np.isfinite(c[t]) and vz[t] > 0:
            b = round(c[t] / 0.10)
            prof[b] = prof.get(b, 0.0) + vz[t]
            tot += vz[t]
        if prof and np.isfinite(c[t]):
            poc = max(prof, key=prof.get) * 0.10
            poc_above[t], poc_below[t] = c[t] > poc + 1e-9, c[t] < poc - 1e-9
            b = round(c[t] / 0.10)
            near = sum(prof.get(b + j, 0.0) for j in (-1, 0, 1))
            near20[t] = tot > 0 and near / tot >= 0.2
    f["vprof_above_poc"], f["vprof_below_poc"], f["vprof_near20"] = poc_above, poc_below, near20
    return f


def oi_flags(oi) -> dict:
    f = {}
    oif = X.ffill(np.where(np.nan_to_num(oi) > 0, oi, np.nan))
    for n in (1, 5, 15, 30):
        r = oif / lag(oif, n) - 1
        for a in (0.01, 0.05):
            f[f"oi{n}_up{a * 100:g}"] = r > a
            f[f"oi{n}_dn{a * 100:g}"] = r < -a
    return f


def shape(c, t):
    w = c[t - SHAPE + 1: t + 1]
    return w / w[-1] if len(w) == SHAPE and np.isfinite(w).all() and w[-1] != 0 else np.full(SHAPE, np.nan)


# ------------------------------------------------------------------------------------------------ per day
def day_flags(day):
    D.clean(day)
    ix = day.ix
    ci = X.ffill(ix[:, 3])
    hi_ = np.where(np.isfinite(ix[:, 1]), ix[:, 1], ci)
    lo_ = np.where(np.isfinite(ix[:, 2]), ix[:, 2], ci)
    oi_ = np.where(np.isfinite(ix[:, 0]), ix[:, 0], ci)
    tw = X.S.twap(day)
    idx = {}
    for sgn in (1, -1):
        if sgn > 0:
            lib = library(oi_, hi_, lo_, ci, (), vwap_ref=tw, sgn=1)
        else:
            lib = library(-oi_, -lo_, -hi_, -ci, (), vwap_ref=-tw, sgn=1)
            dv = tw / ci - 1                                  # in the put's direction: below the TWAP is "above"
            lib["vwap_above"], lib["vwap_below"] = dv > 0, dv < 0
            lib["vwap_gt1"], lib["vwap_gt3"], lib["vwap_lt1"], lib["vwap_lt3"] = dv > 0.001, dv > 0.003, dv < -0.001, dv < -0.003
        if sgn > 0:
            dv = ci / tw - 1
            lib["vwap_gt1"], lib["vwap_gt3"], lib["vwap_lt1"], lib["vwap_lt3"] = dv > 0.001, dv > 0.003, dv < -0.001, dv < -0.003
        lib.update(moves(ci, sgn, (0.001, 0.002, 0.003)))
        idx[sgn] = {f"ix_{k}": v for k, v in lib.items()}
    keys, flags, shp_o, shp_i = [], [], [], []
    for (k, side), a in day.opts.items():
        sgn = 1 if side == "C" else -1
        traded = np.isfinite(a[:, C]) & (np.nan_to_num(a[:, V]) > 0)
        cand = [t for t in X.TS if traded[t] and 1.0 <= a[t, C] <= 30.0 and np.isfinite(ci[t]) and (k - ci[t]) * sgn > 0]
        if not cand:
            continue
        c = X.ffill(a[:, C])
        h = np.where(np.isfinite(a[:, H]), a[:, H], c)
        l = np.where(np.isfinite(a[:, L]), a[:, L], c)
        o = np.where(np.isfinite(a[:, O]), a[:, O], c)
        vz = np.nan_to_num(a[:, V])
        pv, cv = np.cumsum(np.nan_to_num(a[:, C]) * vz), np.cumsum(vz)
        vwap = np.where(cv > 0, pv / np.where(cv > 0, cv, 1), np.nan)
        lib = library(o, h, l, c, (), vwap_ref=vwap, sgn=1)
        dv = c / vwap - 1
        lib["vwap_gt1"], lib["vwap_gt3"], lib["vwap_lt1"], lib["vwap_lt3"] = dv > 0.01, dv > 0.03, dv < -0.01, dv < -0.03
        lib.update(moves(c, 1, (0.3, 0.5, 0.7)))
        lib = {f"op_{kk}": vv for kk, vv in lib.items()}
        lib.update({f"op_{kk}": vv for kk, vv in volume_flags(c, a[:, V], h, l).items()})
        lib.update({f"op_{kk}": vv for kk, vv in oi_flags(a[:, OI]).items()})
        lib.update(idx[sgn])
        names = list(lib)
        M = np.array([np.nan_to_num(lib[nm].astype(float)) > 0 for nm in names])     # (flags, N)
        for t in cand:
            keys.append((day.und, day.day, int(t), float(k), side))
            flags.append(M[:, t])
            shp_o.append(shape(c, t))
            shp_i.append(shape(ci * sgn if sgn > 0 else ci, t) if sgn > 0 else 2 - shape(ci, t))
    return keys, (np.array(flags, dtype=bool) if flags else np.zeros((0, 0), bool)), names if keys else [], \
        np.array(shp_o, dtype=np.float32), np.array(shp_i, dtype=np.float32)


def build(procs: int = 4):
    days = []
    for u in X.UNDS:
        days += sorted(D.load(u, "all"), key=lambda d: d.day)
    K_, Fs, Os, Is = [], [], [], []
    names = None
    with Pool(procs) as pool:
        for i, (k, f, nm, so, si) in enumerate(pool.imap(day_flags, days, chunksize=1)):
            if not k:
                continue
            names = names or nm
            assert nm == names
            K_ += k
            Fs.append(f)
            Os.append(so)
            Is.append(si)
            if i % 50 == 0:
                print(i, len(days), flush=True)
    keys = pd.DataFrame(K_, columns=["und", "day", "t", "strike", "side"])
    flags = pd.DataFrame(np.vstack(Fs), columns=names)
    OUT.mkdir(parents=True, exist_ok=True)
    pd.concat([keys, flags], axis=1).to_parquet(OUT / "flags.parquet")
    np.savez_compressed(OUT / "shapes.npz", opt=np.vstack(Os), idx=np.vstack(Is))
    print(len(keys), "rows", len(names), "flags")


if __name__ == "__main__":
    if sys.argv[1:2] == ["build"]:
        build()
