"""R144a features (PREREG.md section 2): volume, liquidity, OI / options context, market context and order-flow proxies
at a breakout arm's entry. Everything is read at minute er - 1 or earlier (er = the fill row), or from earlier sessions.

Pure part (numpy only, covered by test_r144a.py with the look-ahead guard): `Chain`, `features`, `quadrant`.
Loader part (pandas, the lake): `chains_for`, `daily_context`, `vix_minutes`, `us_moves`.

Proxies, said plainly: the lake has no bid-ask quotes, no depth and no historical order book, and no index-futures
minutes before 29 Jul 2026. Q2 / Q3 (liquidity) and F1-F5 (order flow) are proxies built from option minutes, OI and
daily futures. 1 Nov 2026.
"""
from __future__ import annotations

import json
import math
import os
import warnings
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import numpy as np

N = 375
NOFF = 21                       # offsets -10..+10
SIDES = ("C", "P")
HIST = 20                       # sessions for the "usual" volume

QUINT = ("V1", "V2", "V4", "Q1", "Q2", "Q3", "Q4", "O1", "O2", "O3", "O4", "O5", "O6", "O7", "C7", "C8", "C10", "C11",
         "C12", "F1", "F2", "F5")
CATS = ("F3", "F4")
FEATS = QUINT + CATS
FAMILY = {f: f[0] for f in FEATS}
NAMES = dict(
    V1="option-market volume (ATM+-2, last 5 min) vs its usual",
    V2="traded strike's volume (last 5 min) vs the usual 1-ITM volume",
    V4="index futures volume yesterday vs its 20-day average",
    Q1="premium as % of spot", Q2="spread proxy: option minute range / price (last 5 min)",
    Q3="stale option minutes (no print or zero volume, last 15 min)", Q4="strike OI vs ATM+-5 average",
    O1="OI change at the strike since the open", O2="writers' lean since the open (trade's way)",
    O3="PCR ATM+-5 (trade's way, log)", O4="ATM IV level", O5="ATM IV change since the open",
    O6="skew, OTM-3 (trade's way)", O7="ATM straddle vs VIX one-day move",
    C7="VIX level", C8="VIX change from the previous close (%)", C10="futures basis, previous session (%)",
    C11="S&P 500 overnight move (trade's way, %)", C12="VIX-implied move vs 20-day range (regime proxy)",
    F1="option volume delta proxy (last 15 min)", F2="index tick direction (last 15 min, trade's way)",
    F3="option OI-price quadrant (last 15 min)", F4="futures OI-price quadrant, previous session (trade's way)",
    F5="trade-side vs other-side option volume (ATM+-2, last 15 min)")


# ================================================================================================ the chain of one session
@dataclass
class Chain:
    """One session's rolling option rows: arrays (2 sides, 21 offsets, 375 rows), NaN where the lake has none."""
    close: np.ndarray
    vol: np.ndarray
    oi: np.ndarray
    iv: np.ndarray
    strike: np.ndarray
    spot: np.ndarray            # (375,)

    @staticmethod
    def empty() -> "Chain":
        z = lambda: np.full((2, NOFF, N), np.nan)  # noqa: E731
        return Chain(z(), z(), z(), z(), z(), np.full(N, np.nan))

    def col(self, side: str, off: int) -> int:
        return SIDES.index(side), off + 10

    def strike_series(self, arr: np.ndarray, side: str, strike: float) -> np.ndarray:
        """(375,) of `arr` for one strike, gathered from whichever offset row carried it each minute."""
        si = SIDES.index(side)
        m = self.strike[si] == float(strike)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            return np.nanmax(np.where(m, arr[si], np.nan), axis=0)


def _fin(x) -> bool:
    return x is not None and isinstance(x, (int, float, np.floating, np.integer)) and math.isfinite(float(x))


def _first(a: np.ndarray, hi: int) -> float:
    """The first finite value of a[0:hi], NaN if none."""
    v = a[:max(hi, 0)]
    k = np.flatnonzero(np.isfinite(v))
    return float(v[k[0]]) if len(k) else float("nan")


def _at(a: np.ndarray, r: int, back: int = 5) -> float:
    """The last finite value of a at or before row r (looking back at most `back` minutes), NaN if none."""
    if r < 0:
        return float("nan")
    v = a[max(r - back, 0):r + 1]
    k = np.flatnonzero(np.isfinite(v))
    return float(v[k[-1]]) if len(k) else float("nan")


def _nsum(a) -> float:
    a = np.asarray(a, float)
    return float(np.nansum(a)) if np.isfinite(a).any() else float("nan")


def quadrant(dp: float, doi: float) -> str | None:
    """OI-price quadrant: long build-up (price up, OI up), short build-up (down, up), short covering (up, down), long
    unwinding (down, down). None when either is unknown or zero."""
    if not (_fin(dp) and _fin(doi)) or dp == 0 or doi == 0:
        return None
    if dp > 0:
        return "long build-up" if doi > 0 else "short covering"
    return "short build-up" if doi > 0 else "long unwinding"


def window_median(hist: np.ndarray | None, lo: int, hi: int) -> float:
    """Median over earlier sessions (rows of hist, (k, 375)) of each session's sum over minutes [lo, hi)."""
    if hist is None or not len(hist) or hi <= lo:
        return float("nan")
    w = hist[:, lo:hi]
    ok = np.isfinite(w).any(axis=1)
    if ok.sum() < 5:
        return float("nan")
    return float(np.median(np.nansum(w[ok], axis=1)))


def atm_volume(ch: Chain) -> np.ndarray:
    """(375,) calls + puts volume over offsets -2..+2 (NaN when no row)."""
    w = ch.vol[:, 8:13, :]
    ok = np.isfinite(w).any(axis=(0, 1))
    return np.where(ok, np.nansum(w, axis=(0, 1)), np.nan)


def itm_volume(ch: Chain, side: str) -> np.ndarray:
    """(375,) the 1-ITM row's volume of one side (call offset -1, put offset +1)."""
    si, oi = ch.col(side, -1 if side == "C" else 1)
    return ch.vol[si, oi].copy()


def features(ch: Chain | None, con: np.ndarray | None, idx: np.ndarray, er: int, side: str, strike: float,
             ctx: dict) -> dict:
    """PREREG section 2 for one candidate. ch: the session's chain; con: (5, 375) O H L C V of the traded contract (the
    store's, as priced); idx: (4, 375) index O H L C; er: the fill row; ctx: day and history context (see
    `context_for`). Reads rows < er only. NaN / None = unknown."""
    out = {f: float("nan") for f in QUINT}
    out.update({f: None for f in CATS})
    s = 1.0 if side == "C" else -1.0
    r = er - 1
    if r < 0:
        return out
    w5, w15 = max(er - 5, 0), max(er - 15, 0)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        # ---------------- the traded contract (store prices)
        if con is not None:
            o, h, lo_, c, v = (con[i].astype(float) for i in range(5))
            cl = _at(c, r)
            spot = _at(idx[3], r)
            if _fin(cl) and _fin(spot) and spot > 0:
                out["Q1"] = cl / spot * 100
            rr = (h[w5:er] - lo_[w5:er]) / c[w5:er]
            rr = rr[np.isfinite(rr)]
            if len(rr):
                out["Q2"] = float(rr.mean())
            vv = v[w15:er]
            out["Q3"] = float(np.sum(~np.isfinite(vv) | (np.nan_to_num(vv) <= 0)))
            sv = np.sign(c[w15:er] - o[w15:er]) * v[w15:er]
            tot = np.nansum(v[w15:er])
            if tot > 0:
                out["F1"] = float(np.nansum(sv) / tot)
            if ch is not None:
                med = window_median(ctx.get("hist_itm"), w5, er)
                vol5 = _nsum(v[w5:er])
                if _fin(med) and med > 0 and _fin(vol5):
                    out["V2"] = vol5 / med
        # ---------------- the chain
        if ch is not None:
            av = atm_volume(ch)
            med = window_median(ctx.get("hist_atm"), w5, er)
            a5 = _nsum(av[w5:er])
            if _fin(med) and med > 0 and _fin(a5):
                out["V1"] = a5 / med
            coi = ch.strike_series(ch.oi, side, strike)
            oi_now = _at(coi, r)
            si = SIDES.index(side)
            ring = ch.oi[si, 5:16, :]
            m5 = np.nanmean(np.array([_at(ring[k], r) for k in range(11)]))
            if _fin(oi_now) and _fin(m5) and m5 > 0:
                out["Q4"] = oi_now / m5
            oi0 = _first(coi, er)
            if _fin(oi_now) and _fin(oi0) and oi0 > 0:
                out["O1"] = oi_now / oi0 - 1
            # writers' lean since the open, on the strikes that are ATM+-2 at er - 1 (fixed strikes, not drifting rows)
            dc = dp_ = tot0 = 0.0
            ok = True
            for k in range(8, 13):
                for j, sd in enumerate(SIDES):
                    kk = _at(ch.strike[j, k], r)
                    if not _fin(kk):
                        ok = False
                        break
                    ser = ch.strike_series(ch.oi, sd, kk)
                    now, beg = _at(ser, r), _first(ser, er)
                    if not (_fin(now) and _fin(beg)):
                        ok = False
                        break
                    if sd == "C":
                        dc += now - beg
                    else:
                        dp_ += now - beg
                    tot0 += beg
                if not ok:
                    break
            if ok and tot0 > 0:
                out["O2"] = s * (dp_ - dc) / tot0
            ccall = np.array([_at(ch.oi[0, k], r) for k in range(5, 16)])
            cput = np.array([_at(ch.oi[1, k], r) for k in range(5, 16)])
            if np.isfinite(ccall).all() and np.isfinite(cput).all() and ccall.sum() > 0 and cput.sum() > 0:
                out["O3"] = s * math.log(cput.sum() / ccall.sum())
            iv = np.where(ch.iv > 0, ch.iv, np.nan)
            ivc, ivp = iv[0, 10], iv[1, 10]
            atm_iv = np.nanmean(np.vstack([ivc, ivp]), axis=0)
            out["O4"] = _at(atm_iv, r)
            iv0 = _first(atm_iv, er)
            if _fin(out["O4"]) and _fin(iv0):
                out["O5"] = out["O4"] - iv0
            c3, p3 = _at(iv[0, 13], r), _at(iv[1, 7], r)
            if _fin(c3) and _fin(p3):
                out["O6"] = s * (c3 - p3)
            sc, sp = _at(ch.close[0, 10], r), _at(ch.close[1, 10], r)
            spot = _at(ch.spot, r)
            if not _fin(spot):
                spot = _at(idx[3], r)
            vix = _at(ctx["vix"], r) if ctx.get("vix") is not None else float("nan")
            if all(_fin(x) for x in (sc, sp, spot, vix)) and vix > 0 and spot > 0:
                out["O7"] = (sc + sp) / (spot * vix / 100 / math.sqrt(252))
            tv = np.nansum(ch.vol[si, 8:13, w15:er])
            ov = np.nansum(ch.vol[1 - si, 8:13, w15:er])
            if tv > 0 and ov > 0:
                out["F5"] = float(tv / ov)
            # option OI-price quadrant (traded contract)
            if con is not None:
                c = con[3].astype(float)
                p_end, p_beg = _at(c, r), _first(c[w15:], er - w15)
                o_end, o_beg = oi_now, _first(coi[w15:], er - w15)
                out["F3"] = quadrant(p_end - p_beg, o_end - o_beg)
    # ---------------- index and day context
    cidx = idx[3, w15:er]
    d = np.diff(cidx)
    d = d[np.isfinite(d)]
    if len(d):
        out["F2"] = float(np.mean(s * d > 0) - 0.5)
    vix = ctx.get("vix")
    if vix is not None:
        out["C7"] = _at(vix, r)
        pc = ctx.get("vix_prev")
        if _fin(out["C7"]) and _fin(pc) and pc > 0:
            out["C8"] = (out["C7"] / pc - 1) * 100
        spot = _at(idx[3], r)
        adr = ctx.get("adr")
        if _fin(out["C7"]) and _fin(spot) and _fin(adr) and adr > 0:
            out["C12"] = spot * out["C7"] / 100 / math.sqrt(252) / adr
    for k_ctx, k_out in (("fut_vol_ratio", "V4"), ("basis", "C10")):
        if _fin(ctx.get(k_ctx)):
            out[k_out] = float(ctx[k_ctx])
    if _fin(ctx.get("us_chg")):
        out["C11"] = s * float(ctx["us_chg"])
    q = ctx.get("fut_quad")
    if q is not None:
        with_ = (q in ("long build-up", "short covering")) == (s > 0)
        out["F4"] = ("build-up " if "build" in q else ("covering " if "covering" in q else "unwinding ")) + \
            ("with" if with_ else "against")
    return out


# ================================================================================================ loaders (the lake)
def lake() -> Path:
    return Path(os.environ.get("IRAALGO_LAKE", Path.home() / ".cache" / "iraalgo" / "lake")).expanduser()


def _read(path: Path, cols: list):
    import pandas as pd
    df = pd.read_parquet(path, columns=cols)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["day"] = ts.dt.date
    df["r"] = (ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)).astype(np.int32)
    df = df[(df.r >= 0) & (df.r < N)]
    return df.drop(columns=["ts"])


def chains_for(und: str, year: int, keep_days=None) -> dict:
    """{day: Chain} of one index's WEEK rolling file for one year (all days, or keep_days only)."""
    p = lake() / f"opt_{und}_WEEK_{year}.parquet"
    if not p.exists():
        return {}
    o = _read(p, ["ts", "offset", "side", "strike", "spot", "close", "volume", "oi", "iv"])
    o = o[(o.offset >= -10) & (o.offset <= 10)]
    if keep_days is not None:
        o = o[o.day.isin(set(keep_days))]
    o["si"] = (o.side.astype(str).str[0].str.upper() == "P").astype(np.int64)
    o = o.drop_duplicates(["day", "si", "offset", "r"])
    out = {}
    for d, g in o.groupby("day", sort=True):
        ch = Chain.empty()
        si, oi, r = g.si.values, g.offset.values.astype(np.int64) + 10, g.r.values
        for name, col in (("close", "close"), ("vol", "volume"), ("oi", "oi"), ("iv", "iv"), ("strike", "strike")):
            getattr(ch, name)[si, oi, r] = g[col].values.astype(float)
        ch.spot[r] = g.spot.values.astype(float)
        out[d] = ch
    return out


def vix_minutes(years) -> dict:
    """{day: (375,) INDIAVIX minute closes}."""
    out = {}
    for y in years:
        p = lake() / f"index_1m_INDIAVIX_{y}.parquet"
        if not p.exists():
            continue
        x = _read(p, ["ts", "close"])
        x = x[x.close > 0].drop_duplicates(["day", "r"])
        for d, g in x.groupby("day"):
            a = np.full(N, np.nan)
            a[g.r.values] = g.close.values
            out[d] = a
    return out


def daily_context() -> dict:
    """Per index and day, from sessions BEFORE the day only: VIX previous close, 20-session average range (ADR), the
    previous session's futures basis, futures volume ratio and futures OI-price quadrant."""
    import pandas as pd
    ix = pd.read_parquet(lake() / "index_daily.parquet")
    ix["date"] = pd.to_datetime(ix["date"]).dt.date
    fu = pd.read_parquet(lake() / "fut_daily_continuous.parquet")
    fu["date"] = pd.to_datetime(fu["date"]).dt.date
    fu = fu[fu.expiry_code == 0]
    out = {}
    vx = ix[ix.symbol == "INDIAVIX"].sort_values("date")
    vprev = dict(zip(vx.date.shift(-1), vx.close))           # next date -> this close
    for und in ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"):
        a = ix[ix.symbol == und].sort_values("date").reset_index(drop=True)
        rng = (a.high - a.low).values
        adr = pd.Series(rng).rolling(20, min_periods=10).mean().shift(1).values
        f = fu[fu.symbol == und].sort_values("date").set_index("date")
        m = a.merge(f[["close", "volume", "oi"]].rename(columns=lambda c: "f_" + c), left_on="date", right_index=True,
                    how="left")
        basis = ((m.f_close - m.close) / m.close * 100).values
        fv = m.f_volume.values
        fv_avg = pd.Series(fv).rolling(20, min_periods=10).mean().values
        dpx = m.f_close.diff().values
        doi = m.f_oi.diff().values
        res = {}
        for i in range(1, len(a)):
            d = a.date[i]
            j = i - 1                                       # the previous session
            res[d] = dict(adr=float(adr[i]), basis=float(basis[j]),
                          fut_vol_ratio=float(fv[j] / fv_avg[j]) if np.isfinite(fv_avg[j]) and fv_avg[j] > 0 else
                          float("nan"),
                          fut_quad=quadrant(float(dpx[j]), float(doi[j])), vix_prev=float(vprev.get(d, np.nan)))
        out[und] = res
    return out


def us_moves(path: Path) -> dict:
    """{indian day: S&P 500 % change of the last US session dated BEFORE that day} from [(us_date, close), ...]."""
    rows = sorted((date.fromisoformat(d), float(c)) for d, c in json.loads(Path(path).read_text()))
    chg = [(rows[i][0], (rows[i][1] / rows[i - 1][1] - 1) * 100) for i in range(1, len(rows))]
    return {"_list": chg}


def us_change_for(us: dict, day: date) -> float:
    """The change of the latest US session strictly before `day` (within 5 calendar days), NaN if none."""
    chg = us["_list"]
    lo, hi = 0, len(chg)
    while lo < hi:
        mid = (lo + hi) // 2
        if chg[mid][0] < day:
            lo = mid + 1
        else:
            hi = mid
    if lo == 0:
        return float("nan")
    d, v = chg[lo - 1]
    return v if (day - d).days <= 5 else float("nan")
