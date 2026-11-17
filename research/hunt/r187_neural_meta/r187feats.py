"""R187 features (PREREG section 2, blocks 1-3): the traded index, the cross-index context, India VIX and the option-chain
context, all at the decision row `r` (the last 1-minute row whose bar is complete when the decision is made). Nothing
from a row after `r` is read: every array is cut to rows 0..r before anything is computed (test_r187.py's look-ahead
guard replaces the later rows with junk and checks nothing changes).

Grid: row t = the 1-minute bar that STARTS at 09:15 + t (375 rows a session), as R3 / R51.

The loaders (index_days, opt_days) read the lake's Parquet once and cache small pickles under the R187 cache; the feature
functions are pure. Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import math
import pickle
from bisect import bisect_left
from datetime import date

import numpy as np

import r187cfg as K

N = 375
O, H, L, C = range(4)
# option aggregate columns (per minute): total OI calls / puts over ATM-10..+10; ATM call / put IV; put IV at ATM-2; call
# IV at ATM+2; ATM call / put close; ATM call / put OI; ATM call / put volume
OA = ("oiC", "oiP", "ivC0", "ivP0", "ivPm2", "ivCp2", "cC0", "cP0", "oiC0", "oiP0", "vC0", "vP0")
NA = len(OA)

TRADED = ("ret5", "ret15", "ret30", "ret60", "from_open", "day_move", "gap", "range_adr", "range_pos", "room_atr",
          "behind_atr", "rv30", "adx14", "er12", "chop14", "tod", "dow", "dte", "is_exp", "side")
CROSSF = tuple(f"{k}_{s}" for s in K.CROSS for k in ("mv", "r15")) + ("breadth6",)
VIXF = ("vix", "dvix", "vix15")
OPTF = ("atm_iv", "skew", "pcr", "d_oic15", "d_oip15", "prem15_side", "oi15_side", "volr_side")
COMMON = TRADED + CROSSF + VIXF + OPTF


# ================================================================================================ loaders (I/O)
def _read(path, cols):
    import pandas as pd
    df = pd.read_parquet(path, columns=cols)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["d"] = ts.dt.date
    df["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
    return df[(df.m >= 0) & (df.m < N)].drop(columns=["ts"])


def index_days(sym: str) -> dict:
    """{day: (375, 4) O H L C} from every lake file of the index (NaN where a minute is missing). Cached."""
    p = K.OUT / f"ix_{sym}.pkl"
    if p.exists():
        return pickle.loads(p.read_bytes())
    import pandas as pd
    files = sorted(K.LAKE.glob(f"index_1m_{sym}_*.parquet"))
    files = [f for f in files if "gap" not in f.name and "fill" not in f.name]
    df = pd.concat([_read(f, ["open", "high", "low", "close", "ts"]) for f in files], ignore_index=True)
    df = df.drop_duplicates(["d", "m"], keep="first")
    out = {}
    for d, g in df.groupby("d", sort=True):
        a = np.full((N, 4), np.nan)
        a[g.m.values] = g[["open", "high", "low", "close"]].values.astype(float)
        out[d] = a
    K.OUT.mkdir(parents=True, exist_ok=True)
    p.write_bytes(pickle.dumps(out, protocol=pickle.HIGHEST_PROTOCOL))
    return out


def opt_days(und: str) -> dict:
    """{day: (375, 12) float32} option aggregates (OA) from the lake's rolling WEEK files. Cached."""
    p = K.OUT / f"oa_{und}.pkl"
    if p.exists():
        return pickle.loads(p.read_bytes())
    import pandas as pd
    out = {}
    files = [f for f in sorted(K.LAKE.glob(f"opt_{und}_WEEK_*.parquet")) if "gap" not in f.name]
    for f in files:
        o = _read(f, ["offset", "side", "close", "iv", "volume", "oi", "ts"])
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o.drop_duplicates(["d", "m", "side", "offset"], keep="first")
        tot = o.groupby(["d", "m", "side"], observed=True).oi.sum().unstack("side")
        at = o[o.offset == 0].set_index(["d", "m", "side"])[["iv", "close", "oi", "volume"]].unstack("side")
        pm2 = o[(o.offset == -2) & (o.side == "P")].set_index(["d", "m"]).iv
        cp2 = o[(o.offset == 2) & (o.side == "C")].set_index(["d", "m"]).iv
        j = pd.DataFrame(index=tot.index)
        j["oiC"], j["oiP"] = tot.get("C"), tot.get("P")
        for col, src in (("ivC0", ("iv", "C")), ("ivP0", ("iv", "P")), ("cC0", ("close", "C")), ("cP0", ("close", "P")),
                         ("oiC0", ("oi", "C")), ("oiP0", ("oi", "P")), ("vC0", ("volume", "C")), ("vP0", ("volume", "P"))):
            j[col] = at[src] if src in at.columns else np.nan
        j["ivPm2"], j["ivCp2"] = pm2, cp2
        j = j[list(OA)].reset_index()
        for d, g in j.groupby("d", sort=True):
            if d in out:
                continue
            a = np.full((N, NA), np.nan, dtype=np.float32)
            a[g.m.values] = g[list(OA)].values.astype(np.float32)
            out[d] = a
    K.OUT.mkdir(parents=True, exist_ok=True)
    p.write_bytes(pickle.dumps(out, protocol=pickle.HIGHEST_PROTOCOL))
    return out


class Lake:
    """Every index's minute grids and the five option books' aggregates, with each symbol's sorted days."""

    def __init__(self, ix: dict, oa: dict):
        self.ix = ix                                 # sym -> {day: (375, 4)}
        self.oa = oa                                 # und -> {day: (375, 12)}
        self.days = {s: sorted(v) for s, v in ix.items()}

    @classmethod
    def load(cls) -> "Lake":
        ix = {s: index_days(s) for s in K.CROSS + ("INDIAVIX",)}
        oa = {u: opt_days(u) for u in K.UNDS}
        return cls(ix, oa)

    def prev_days(self, sym: str, day: date, n: int) -> list:
        ds = self.days.get(sym, [])
        i = bisect_left(ds, day)
        return ds[max(0, i - n): i]


# ================================================================================================ pure helpers
def lastf(a: np.ndarray, upto: int) -> float:
    """The last finite value of a[0..upto] (NaN if none or upto < 0)."""
    if upto < 0:
        return np.nan
    seg = a[: upto + 1]
    ok = np.flatnonzero(np.isfinite(seg))
    return float(seg[ok[-1]]) if ok.size else np.nan


def firstf(a: np.ndarray) -> float:
    ok = np.flatnonzero(np.isfinite(a))
    return float(a[ok[0]]) if ok.size else np.nan


def day_close(a: np.ndarray) -> float:
    return lastf(a[:, C], len(a) - 1)


def bars5(a: np.ndarray, r: int | None = None) -> np.ndarray:
    """5-minute O H L C bars of a (rows, 4) grid; with r, only bars whose last row 5b+4 <= r (complete at r)."""
    n = a.shape[0] // 5
    out = []
    for b in range(n):
        if r is not None and 5 * b + 4 > r:
            break
        seg = a[5 * b: 5 * b + 5]
        if not np.isfinite(seg[:, C]).any():
            continue
        out.append((firstf(seg[:, O]), np.nanmax(seg[:, H]), np.nanmin(seg[:, L]), lastf(seg[:, C], 4)))
    return np.array(out, dtype=float).reshape(-1, 4)


def adx(b: np.ndarray, n: int = 14) -> float:
    """Wilder's ADX(n) of the last bar (needs 2n + 1 bars), as R183."""
    if len(b) < 2 * n + 1:
        return np.nan
    h, l_, c = b[:, 1], b[:, 2], b[:, 3]
    up, dn = h[1:] - h[:-1], l_[:-1] - l_[1:]
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    mdm = np.where((dn > up) & (dn > 0), dn, 0.0)
    tr = np.maximum.reduce([h[1:] - l_[1:], np.abs(h[1:] - c[:-1]), np.abs(l_[1:] - c[:-1])])
    atr, sp, sm = tr[:n].sum(), pdm[:n].sum(), mdm[:n].sum()
    dxs = []
    for i in range(n, len(tr) + 1):
        if i > n:
            atr = atr - atr / n + tr[i - 1]
            sp = sp - sp / n + pdm[i - 1]
            sm = sm - sm / n + mdm[i - 1]
        if atr <= 0:
            dxs.append(0.0)
            continue
        pdi, mdi = 100 * sp / atr, 100 * sm / atr
        dxs.append(100 * abs(pdi - mdi) / (pdi + mdi) if pdi + mdi > 0 else 0.0)
    if len(dxs) < n:
        return np.nan
    a_ = float(np.mean(dxs[:n]))
    for x in dxs[n:]:
        a_ = (a_ * (n - 1) + x) / n
    return a_


def eff_ratio(c: np.ndarray, n: int = 12) -> float:
    if len(c) < n + 1:
        return np.nan
    seg = c[-(n + 1):]
    den = float(np.sum(np.abs(np.diff(seg))))
    return abs(seg[-1] - seg[0]) / den if den > 0 else 0.0


def chop(b: np.ndarray, n: int = 14) -> float:
    if len(b) < n + 1:
        return np.nan
    h, l_, c = b[-n:, 1], b[-n:, 2], b[-(n + 1):, 3]
    tr = np.maximum.reduce([h - l_, np.abs(h - c[:-1]), np.abs(l_ - c[:-1])])
    rng = float(h.max() - l_.min())
    return 100 * math.log10(tr.sum() / rng) / math.log10(n) if rng > 0 and tr.sum() > 0 else np.nan


def _lr(a: float, b: float) -> float:
    return math.log(a / b) if np.isfinite(a) and np.isfinite(b) and a > 0 and b > 0 else np.nan


# ================================================================================================ the features
def common(lake: Lake, und: str, day: date, r: int, side: str, dte: float, is_exp: bool) -> dict:
    """Blocks 1-3 of PREREG section 2 at decision row r (rows 0..r known). side 'C' / 'P'."""
    s = 1.0 if side == "C" else -1.0
    f = {k: np.nan for k in COMMON}
    f.update(tod=float(r), dow=float(day.weekday()), dte=float(dte), is_exp=float(bool(is_exp)), side=s)
    tday = lake.ix.get(und, {}).get(day)
    if tday is None or r < 0:
        return f
    a = np.array(tday[: r + 1], dtype=float, copy=True)        # the look-ahead wall
    px = lastf(a[:, C], r)
    prevs = lake.prev_days(und, day, 20)
    pa = [lake.ix[und][d] for d in prevs]
    pc = day_close(pa[-1]) if pa else np.nan
    o0 = firstf(a[:, O])
    if np.isfinite(px):
        for k in (5, 15, 30, 60):
            f[f"ret{k}"] = s * (px / lastf(a[:, C], r - k) - 1) if r - k >= 0 else np.nan
        f["from_open"] = s * (px / o0 - 1) if np.isfinite(o0) else np.nan
        f["day_move"] = s * (px / pc - 1) if np.isfinite(pc) else np.nan
        f["gap"] = s * (o0 / pc - 1) if np.isfinite(pc) and np.isfinite(o0) else np.nan
        hi, lo = np.nanmax(a[:, H]), np.nanmin(a[:, L])
        rngs = [np.nanmax(z[:, H]) - np.nanmin(z[:, L]) for z in pa if np.isfinite(z[:, H]).any()]
        if rngs:
            f["range_adr"] = (hi - lo) / float(np.mean(rngs))
        if hi > lo:
            pos = (px - lo) / (hi - lo)
            f["range_pos"] = pos if s > 0 else 1 - pos
        if len(pa) >= 15:
            trs = []
            for i in range(len(pa) - 14, len(pa)):
                z, zp = pa[i], pa[i - 1]
                h_, l_, c_ = np.nanmax(z[:, H]), np.nanmin(z[:, L]), day_close(zp)
                trs.append(max(h_ - l_, abs(h_ - c_), abs(l_ - c_)))
            atr = float(np.mean(trs))
            if atr > 0 and pa:
                pdh, pdl = np.nanmax(pa[-1][:, H]), np.nanmin(pa[-1][:, L])
                ahead, behind = (pdh, pdl) if s > 0 else (pdl, pdh)
                f["room_atr"] = s * (ahead - px) / atr
                f["behind_atr"] = s * (px - behind) / atr
        rets = np.diff(np.log(a[max(0, r - 30): r + 1, C]))
        rets = rets[np.isfinite(rets)]
        if len(rets) >= 10:
            f["rv30"] = float(np.std(rets) * math.sqrt(375 * 250))
        b5 = np.vstack([bars5(z) for z in pa[-3:]] + [bars5(a, r)]) if pa else bars5(a, r)
        f["adx14"], f["er12"], f["chop14"] = adx(b5), eff_ratio(b5[:, 3]), chop(b5)
    ups = []
    for sym in K.CROSS:
        z = lake.ix.get(sym, {}).get(day)
        pd_ = lake.prev_days(sym, day, 1)
        if z is None or not pd_:
            continue
        now = lastf(z[: r + 1, C], r)
        zc = day_close(lake.ix[sym][pd_[-1]])
        if np.isfinite(now) and np.isfinite(zc):
            f[f"mv_{sym}"] = s * (now / zc - 1)
            ups.append(float(now > zc))
        p15 = lastf(z[: r + 1, C], r - 15)
        if np.isfinite(now) and np.isfinite(p15):
            f[f"r15_{sym}"] = s * (now / p15 - 1)
    if ups:
        f["breadth6"] = float(np.mean(ups))
    v = lake.ix.get("INDIAVIX", {}).get(day)
    vp = lake.prev_days("INDIAVIX", day, 1)
    if v is not None:
        vn = lastf(v[: r + 1, C], r)
        f["vix"] = vn
        if vp:
            f["dvix"] = _lr(vn, day_close(lake.ix["INDIAVIX"][vp[-1]]))
        f["vix15"] = _lr(vn, lastf(v[: r + 1, C], r - 15))
    oa = lake.oa.get(und, {}).get(day)
    if oa is not None:
        z = np.array(oa[: r + 1], dtype=float, copy=True)
        col = {k: i for i, k in enumerate(OA)}

        def at(k, row):
            return lastf(z[:, col[k]], row)
        ivc, ivp = at("ivC0", r), at("ivP0", r)
        f["atm_iv"] = float(np.nanmean([ivc, ivp])) if np.isfinite([ivc, ivp]).any() else np.nan
        f["skew"] = at("ivPm2", r) - at("ivCp2", r)
        f["pcr"] = at("oiP", r) / at("oiC", r) if at("oiC", r) > 0 else np.nan
        f["d_oic15"] = _lr(at("oiC", r), at("oiC", r - 15))
        f["d_oip15"] = _lr(at("oiP", r), at("oiP", r - 15))
        cs, os_, vs = ("cC0", "oiC0", "vC0") if s > 0 else ("cP0", "oiP0", "vP0")
        f["prem15_side"] = _lr(at(cs, r), at(cs, r - 15))
        f["oi15_side"] = _lr(at(os_, r), at(os_, r - 15))
        v15 = np.nansum(z[max(0, r - 14): r + 1, col[vs]])
        v60 = np.nansum(z[max(0, r - 74): max(0, r - 14), col[vs]])
        f["volr_side"] = v15 / (v60 / 4.0) if v60 > 0 else np.nan
    return f
