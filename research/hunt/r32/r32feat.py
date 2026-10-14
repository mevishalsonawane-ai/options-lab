"""R32 rows: every 1-minute bar close 12:00 .. 14:55 x every traded OTM option at Rs 1-30 (NIFTY, SENSEX expiries), with
every signal family at once (PREREG.md). Every feature at row t reads rows <= t of the day (plus earlier expiries for
the vol seasonality); labels (y_*) are hindsight.

    python3 research/hunt/r32/r32feat.py build     # -> <cache>/r32/rows.parquet
"""
from __future__ import annotations

import math
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT / "research" / "hunt" / "r27", ROOT / "research" / "hunt" / "r28", ROOT / "research" / "hunt" / "r29", HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))

import r28lib as S  # noqa: E402  (r25 / r26 / r27 on the path)
import r29gex as G  # noqa: E402
import r25cfg as K  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r26lib as L26  # noqa: E402
import r27feat as F  # noqa: E402
from r25data import C, H, IV, L, N, O, OI, V, minute  # noqa: E402
from research.brain import bs  # noqa: E402

OUT = K.CACHE.root / "r32"
UNDS = ("NIFTY", "SENSEX")
T0, T1 = R.close_row(12, 0), R.close_row(14, 55)
TS = np.arange(T0, T1 + 1)
LAST_LABEL = minute(15, 4)
BAND = (1.0, 30.0)


# ------------------------------------------------------------------------------------------------ helpers (past-only)
def ffill(x: np.ndarray) -> np.ndarray:
    idx = np.where(np.isfinite(x), np.arange(len(x)), -1)
    np.maximum.accumulate(idx, out=idx)
    return np.where(idx >= 0, x[np.maximum(idx, 0)], np.nan)


def lag(x: np.ndarray, n: int) -> np.ndarray:
    out = np.full(len(x), np.nan)
    out[n:] = x[:-n]
    return out


def ema(x: np.ndarray, n: int) -> np.ndarray:
    out = np.full(len(x), np.nan)
    a = 2.0 / (n + 1)
    e = math.nan
    for i, v in enumerate(x):
        if not np.isfinite(v):
            out[i] = e
            continue
        e = v if math.isnan(e) else a * v + (1 - a) * e
        out[i] = e
    return out


def roll(x: np.ndarray, n: int, fn) -> np.ndarray:
    s = pd.Series(x)
    r = s.rolling(n, min_periods=1)
    return getattr(r, fn)().values


def streak(cond: np.ndarray) -> np.ndarray:
    out = np.zeros(len(cond))
    c = 0
    for i, v in enumerate(cond):
        c = c + 1 if v else 0
        out[i] = c
    return out


def since(event: np.ndarray) -> np.ndarray:
    """Bars since the last True (NaN before the first)."""
    out = np.full(len(event), np.nan)
    last = -1
    for i, v in enumerate(event):
        if v:
            last = i
        if last >= 0:
            out[i] = i - last
    return out


def adx(h, l, c, n=14) -> np.ndarray:
    up = np.r_[np.nan, h[1:] - h[:-1]]
    dn = np.r_[np.nan, l[:-1] - l[1:]]
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    ndm = np.where((dn > up) & (dn > 0), dn, 0.0)
    tr = np.r_[h[0] - l[0], np.maximum(h[1:] - l[1:], np.maximum(np.abs(h[1:] - c[:-1]), np.abs(l[1:] - c[:-1])))]

    def wild(x):
        out = np.full(len(x), np.nan)
        s = 0.0
        for i, v in enumerate(np.nan_to_num(x)):
            s = s - s / n + v if i >= n else s + v
            out[i] = s if i >= n - 1 else np.nan
        return out
    a, p, m = wild(tr), wild(pdm), wild(ndm)
    with np.errstate(invalid="ignore", divide="ignore"):
        pdi, ndi = 100 * p / a, 100 * m / a
        dx = 100 * np.abs(pdi - ndi) / (pdi + ndi)
    return ema(dx, 2 * n - 1)


# ------------------------------------------------------------------------------------------------ per-day features
def index_block(day) -> dict:
    ix = day.ix
    c = ffill(ix[:, 3])
    h = np.where(np.isfinite(ix[:, 1]), ix[:, 1], c)
    l = np.where(np.isfinite(ix[:, 2]), ix[:, 2], c)
    out = {}
    for n in (1, 5, 15, 30):
        out[f"ix_r{n}"] = (c / lag(c, n) - 1) * 100
    out["ix_ema"] = (ema(c, 9) - ema(c, 21)) / c * 100
    out["ix_adx"] = adx(h, l, c)
    out["ix_twap"] = (c / S.twap(day) - 1) * 100
    lr = np.r_[np.nan, np.log(c[1:] / c[:-1])]
    out["ix_rv15"] = np.sqrt(roll(lr ** 2, 15, "mean") * F.YEAR_MIN) * 100
    out["ix_rv60"] = np.sqrt(roll(lr ** 2, 60, "mean") * F.YEAR_MIN) * 100
    out["ix_rvx"] = out["ix_rv15"] / out["ix_rv60"]
    hi, lo = np.fmax.accumulate(h), np.fmin.accumulate(l)
    out["_newhi"] = since(h >= hi - 1e-9)
    out["_newlo"] = since(l <= lo + 1e-9)
    out["ix_rngpos"] = (c - lo) / np.where(hi - lo > 0, hi - lo, np.nan)
    op = ix[0, 0]
    out["ix_open"] = (c / op - 1) * 100
    f30 = (ix[minute(9, 44), 3] / op - 1) * 100 if np.isfinite(op) else np.nan
    out["ix_first30"] = np.full(N, f30)
    out["ix_gap"] = np.full(N, (op / day.prev_close - 1) * 100 if day.prev_close == day.prev_close else np.nan)
    v = day.vix if day.vix is not None else np.full(N, np.nan)
    v = ffill(v)
    out["vix"] = v
    first = v[np.isfinite(v)][0] if np.isfinite(v).any() else np.nan
    out["vix_ch"] = v - first
    out["_spot"] = c
    return out


def chain_block(day, ratios) -> dict:
    """Per row t in TS: chain-wide features (dicts of arrays over TS)."""
    ch = L26.chain(day)
    out = {k: np.full(len(TS), np.nan) for k in ("pcr", "pcr_ch15", "cwall_em", "pwall_em", "cwall_oi", "pwall_oi",
                                                 "maxpain", "gex_s", "gex_n", "atm_iv", "st_ch15", "rv_fc")}
    pcr_t = {}
    for j, t in enumerate(TS):
        s = R.spot(day, t)
        if math.isnan(s):
            continue
        sums = {}
        for side in ("C", "P"):
            ok = ch.present[side][:, t] & np.isfinite(ch.oi[side][:, t])
            sums[side] = float(ch.oi[side][ok, t].sum())
        pcr_t[t] = sums["P"] / sums["C"] if sums["C"] > 0 else np.nan
        out["pcr"][j] = pcr_t[t]
        if t - 15 in pcr_t:
            out["pcr_ch15"][j] = pcr_t[t] - pcr_t[t - 15]
        sig = G.atm_iv(day, t)
        out["atm_iv"][j] = sig
        T = max(F.mins_left(t), 1) / F.YEAR_MIN
        em = s * sig * math.sqrt(T) if sig == sig else np.nan
        for side, key in (("C", "cwall"), ("P", "pwall")):
            k, _ = ch.wall(side, t)
            if k == k and em == em and em > 0:
                out[f"{key}_em"][j] = (k - s) / em
                path = ch.oi_path(side, k)
                pk = np.nanmax(path[L26.NOON: t + 1]) if np.isfinite(path[L26.NOON: t + 1]).any() else np.nan
                out[f"{key}_oi"][j] = path[t] / pk - 1 if pk == pk and pk > 0 else np.nan
        gs, gn = G.gex(day, t, sig)
        out["gex_s"][j], out["gex_n"][j] = gs, gn
        st, st15 = R.straddle(day, t), R.straddle(day, t - 15)
        out["st_ch15"][j] = st / st15 - 1 if st == st and st15 == st15 and st15 > 0 else np.nan
        jj = min(max((t - F.DECISIONS[0]) // 5, 0), len(F.DECISIONS) - 1)
        out["rv_fc"][j] = F.vol_state(day, t, float(ratios[jj]))["rv_fc"]
        ks = sorted(set(ch.k["C"][ch.present["C"][:, t]]) | set(ch.k["P"][ch.present["P"][:, t]]))
        if ks:
            kk = np.array(ks)
            pain = np.zeros(len(kk))
            for side, sg in (("C", 1), ("P", -1)):
                ok = ch.present[side][:, t] & np.isfinite(ch.oi[side][:, t])
                for k, oi in zip(ch.k[side][ok], ch.oi[side][ok, t]):
                    pain += oi * np.maximum(sg * (kk - k), 0)
            out["maxpain"][j] = (kk[int(np.argmin(pain))] - s) / s * 100
    for key in ("gex_s", "gex_n"):
        base = out[key][0]
        out[key] = out[key] / abs(base) if base == base and base != 0 else np.full(len(TS), np.nan)
    return out


def heavy_block(day) -> dict:
    hv = S.heavy_bars(day.day)
    out = {"hv_r1": np.full(N, np.nan), "hv_r5": np.full(N, np.nan), "hv_agree": np.full(N, np.nan)}
    if hv is None:
        return out
    r1s, r5s = [], []
    for s_ in S.HEAVY:
        c = ffill(hv[s_][:, 1])
        r1s.append((c / lag(c, 1) - 1) * 100)
        r5s.append((c / lag(c, 5) - 1) * 100)
    r1, r5 = np.array(r1s), np.array(r5s)
    out["hv_r1"], out["hv_r5"] = np.nanmean(r1, axis=0), np.nanmean(r5, axis=0)
    out["_hv_up"] = np.sum(r1 > 0, axis=0).astype(float)
    out["_hv_dn"] = np.sum(r1 < 0, axis=0).astype(float)
    return out


def option_block(a: np.ndarray) -> dict:
    """The option's own 1-minute features over the whole grid (row t uses rows <= t), and its labels."""
    o, h, l, c, v, oi, iv = (a[:, i] for i in (O, H, L, C, V, OI, IV))
    traded = np.isfinite(c) & (np.nan_to_num(v) > 0)
    cf = ffill(c)
    hf = np.where(np.isfinite(h), h, cf)
    lf = np.where(np.isfinite(l), l, cf)
    of = np.where(np.isfinite(o), o, cf)
    vz = np.nan_to_num(v)
    out = {}
    for n in (1, 2, 3, 5, 10, 15, 30):
        out[f"op_r{n}"] = cf / lag(cf, n) - 1
    green = (cf > of).astype(float) * traded
    for n in (2, 3, 4):
        out[f"op_green{n}"] = roll(green, n, "sum")
    out["op_hh"] = streak(np.r_[False, hf[1:] > hf[:-1]])
    out["op_hl"] = streak(np.r_[False, lf[1:] > lf[:-1]])
    out["op_inside"] = np.r_[0.0, ((hf[1:] < hf[:-1]) & (lf[1:] > lf[:-1])).astype(float)]
    out["op_engulf"] = np.r_[0.0, ((cf[1:] > of[1:]) & (of[:-1] > cf[:-1]) & (cf[1:] >= of[:-1]) & (of[1:] <= cf[:-1])).astype(float)]
    out["op_rise3"] = np.r_[np.zeros(3), ((cf[3:] > cf[2:-1]) & (cf[2:-1] > cf[1:-2]) & (cf[1:-2] > cf[:-3])).astype(float)]
    rng = hf - lf
    out["op_body"] = np.where(rng > 0, (cf - of) / rng, 0.0)
    r5 = roll(hf, 5, "max") - roll(lf, 5, "min")
    r30h, r30l = roll(hf, 30, "max"), roll(lf, 30, "min")
    out["op_comp"] = r5 / np.where(r30h - r30l > 0, r30h - r30l, np.nan)
    out["op_pos30"] = (cf - r30l) / np.where(r30h - r30l > 0, r30h - r30l, np.nan)
    e10 = lag(ema(vz, 10), 1)
    out["op_vol1"] = vz / np.where(e10 > 0, e10, np.nan)
    s5, s30 = roll(vz, 5, "sum"), roll(vz, 30, "sum")
    out["op_vol5_30"] = s5 / np.where(s30 > 0, s30, np.nan)
    oif = ffill(np.where(np.nan_to_num(oi) > 0, oi, np.nan))
    for n in (1, 5, 15, 30):
        out[f"op_oi{n}"] = oif / lag(oif, n) - 1
    ivf = ffill(np.where(np.nan_to_num(iv) > 0, iv, np.nan))
    out["op_iv5"] = ivf - lag(ivf, 5)
    out["op_iv15"] = ivf - lag(ivf, 15)
    e9, e21 = ema(cf, 9), ema(cf, 21)
    out["op_ema"] = (e9 - e21) / cf
    out["op_emax"] = since(np.r_[False, np.sign(e9[1:] - e21[1:]) != np.sign(e9[:-1] - e21[:-1])])
    first = np.argmax(np.isfinite(c)) if np.isfinite(c).any() else N
    for (n, m), key in (((7, 3.0), "op_st7"), ((10, 2.0), "op_st10")):
        st = np.zeros(N)
        if first < N:
            st[first:] = S.supertrend(hf[first:], lf[first:], cf[first:], n, m)
        out[key] = st
        out[key + "_age"] = since(np.r_[False, st[1:] != st[:-1]])
    pv = np.cumsum(np.nan_to_num(c) * vz)
    cv = np.cumsum(vz)
    out["op_vwap"] = cf / np.where(cv > 0, pv / np.where(cv > 0, cv, 1), np.nan) - 1
    vol12 = np.cumsum(np.where(np.arange(N) >= T0, vz, 0))
    out["op_vol12"] = vol12
    # labels: the highest confirmed price after t, up to the bar starting 15:04
    nxt = np.r_[o[1:], np.nan]
    # R25's confirmed(): only a bar with a high counts, up to max(its close, the next open) (review fix, 14 Oct: fmin over
    # a NaN high let an untraded bar take the next bar's open unconfirmed)
    conf = np.where(np.isfinite(h), np.fmin(h, np.fmax(c, nxt)), np.nan)
    conf[LAST_LABEL + 1:] = np.nan
    fut = np.full(N, np.nan)
    m = np.nan
    for i in range(N - 1, -1, -1):
        fut[i] = m
        if np.isfinite(conf[i]):
            m = conf[i] if math.isnan(m) else max(m, conf[i])
    out["_fut"] = fut
    out["_traded"] = traded
    return out


def day_rows(args) -> pd.DataFrame:
    day, ratios = args
    D.clean(day)
    ib = index_block(day)
    cb = chain_block(day, ratios)
    hb = heavy_block(day)
    st = L26.chain(day)
    rows = []
    for (k, side), a in day.opts.items():
        ob = option_block(a)
        sgn = 1 if side == "C" else -1
        for j, t in enumerate(TS):
            if not ob["_traded"][t]:
                continue
            p = float(a[t, C])
            s = ib["_spot"][t]
            if not (BAND[0] <= p <= BAND[1]) or not np.isfinite(s) or (k - s) * sgn <= 0:
                continue
            f = {"und": day.und, "day": day.day, "t": int(t), "strike": float(k), "side": side, "price": p}
            for key, arr in ob.items():
                if not key.startswith("_"):
                    f[key] = arr[t]
            for key, arr in ib.items():
                if key.startswith("_"):
                    continue
                f[key] = arr[t] * (sgn if key in ("ix_r1", "ix_r5", "ix_r15", "ix_r30", "ix_ema", "ix_twap", "ix_open", "ix_first30", "ix_gap") else 1)
            f["ix_rngpos"] = ib["ix_rngpos"][t] if sgn > 0 else 1 - ib["ix_rngpos"][t]
            f["ix_since_ext"] = ib["_newhi"][t] if sgn > 0 else ib["_newlo"][t]
            for key, arr in cb.items():
                f[key] = arr[j]
            f["pcr_ch15"] = sgn * cb["pcr_ch15"][j] if cb["pcr_ch15"][j] == cb["pcr_ch15"][j] else np.nan
            f["wall_em"] = cb["cwall_em"][j] if sgn > 0 else -cb["pwall_em"][j]
            f["wall_oi"] = cb["cwall_oi"][j] if sgn > 0 else cb["pwall_oi"][j]
            f["maxpain"] = sgn * cb["maxpain"][j] if cb["maxpain"][j] == cb["maxpain"][j] else np.nan
            for x in ("cwall_em", "pwall_em", "cwall_oi", "pwall_oi"):
                f.pop(x)
            T = max(F.mins_left(t), 1) / F.YEAR_MIN
            sig = cb["atm_iv"][j]
            em = s * sig * math.sqrt(T) if sig == sig else np.nan
            f["dist_em"] = abs(k - s) / em if em == em and em > 0 else np.nan
            ivb = float(bs.implied_vol(np.array([p]), np.array([s]), np.array([k]), np.array([T]), np.array([sgn > 0]))[0])
            fc = cb["rv_fc"][j]
            f["iv_rv"] = ivb * 100 / fc if ivb == ivb and fc == fc and fc > 0 else np.nan
            f["mins_left"] = F.mins_left(t)
            f["hv_r1"] = sgn * hb["hv_r1"][t] if hb["hv_r1"][t] == hb["hv_r1"][t] else np.nan
            f["hv_r5"] = sgn * hb["hv_r5"][t] if hb["hv_r5"][t] == hb["hv_r5"][t] else np.nan
            f["hv_agree"] = (hb["_hv_up"][t] if sgn > 0 else hb["_hv_dn"][t]) if "_hv_up" in hb else np.nan
            fut = ob["_fut"][t]
            f["y_peak"] = fut / p if fut == fut else 1.0
            rows.append(f)
    df = pd.DataFrame(rows)
    for col in df.columns:
        if df[col].dtype == np.float64:
            df[col] = df[col].astype(np.float32)
    return df


def build(procs: int = 4) -> pd.DataFrame:
    jobs = []
    for u in UNDS:
        ds = sorted(D.load(u, "all"), key=lambda d: d.day)
        jobs += list(zip(ds, F.all_ratios(ds)))
    parts = []
    with Pool(procs) as pool:
        for i, df in enumerate(pool.imap(day_rows, jobs, chunksize=1)):
            parts.append(df)
            if i % 50 == 0:
                print(i, len(jobs), flush=True)
    df = pd.concat(parts, ignore_index=True)
    OUT.mkdir(parents=True, exist_ok=True)
    df.to_parquet(OUT / "rows.parquet")
    return df


if __name__ == "__main__":
    if sys.argv[1:2] == ["build"]:
        d = build()
        print(len(d), "rows", d.groupby("und").day.nunique().to_dict())
