"""B2 extra inputs (PREREG.md amendments 1-2): every indicator in many parameter settings on 1 / 5 / 15-minute charts, the
option-chain volume profile ("vertical volume") and an order-flow proxy, R28's gamma exposure, other indices, levels.

    python3 research/hunt/b2/b2sweep.py build [UND ...]      # -> <OUT>/sweep_<UND>.parquet (every day, decision rows)

Point in time: a value at decision row t uses 1-minute bars <= t only (bar t closed at t + 1, as the brain's rows), and a
5- / 15-minute bar only once it has closed by row t. Indicators run on the continuous series across days (as a chart
does). Nothing here reads a target.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import b2data as D  # noqa: E402

try:
    from numba import njit
except Exception:  # pragma: no cover
    def njit(*a, **k):
        return (lambda f: f) if not (a and callable(a[0])) else a[0]

ROWS = np.arange(15, 316, 5)                      # 09:30 .. 14:30 (F1's RULE_ROWS)
EMA_N = (5, 8, 9, 13, 20, 21, 34, 50, 100, 200)
CROSSES = ((5, 13), (8, 21), (9, 20), (9, 21), (12, 26), (13, 34), (20, 50), (50, 200))
RSI_N = (7, 9, 14, 21)
MACD_P = ((12, 26, 9), (8, 17, 9), (5, 35, 5))
BB_P = ((20, 2.0), (20, 2.5), (10, 1.5), (50, 2.0))
ST_P = ((7, 2.0), (10, 3.0), (14, 2.0), (20, 4.0))
ATR_N = (7, 14, 28)
ADX_N = (7, 14, 28)
MOM_N = (1, 3, 5, 10, 20, 40)
DON_N = (20, 55)
OTHER = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY", "BANKEX")


# ------------------------------------------------------------------------------------------------ raw series
def minutes(und: str) -> pd.DataFrame:
    """Index 1-minute OHLC on the session grid: columns day, m (0..374), o, h, l, c (sorted, continuous)."""
    parts = []
    for p in sorted(D.LAKE.rglob(f"index_1m_{und}_*.parquet")):
        df = pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"])
        ts = pd.to_datetime(df.ts)
        if ts.dt.tz is not None:
            ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        df = df.assign(day=ts.dt.normalize(), m=((ts - ts.dt.normalize()).dt.total_seconds() // 60 - 555).astype(int))
        parts.append(df[(df.m >= 0) & (df.m < 375)].drop(columns="ts"))
    df = pd.concat(parts, ignore_index=True).drop_duplicates(["day", "m"]).sort_values(["day", "m"])
    return df.rename(columns={"open": "o", "high": "h", "low": "l", "close": "c"}).reset_index(drop=True)


def bars(M: pd.DataFrame, tf: int) -> pd.DataFrame:
    """tf-minute bars per session (bar b = rows tf*b .. tf*b+tf-1), labelled by their LAST row (closed after it)."""
    if tf == 1:
        return M.assign(end=M.m)
    g = M.assign(b=M.m // tf).groupby(["day", "b"], sort=True)
    B = g.agg(o=("o", "first"), h=("h", "max"), l=("l", "min"), c=("c", "last"), n=("c", "size")).reset_index()
    B["end"] = B.b * tf + tf - 1
    return B


# ------------------------------------------------------------------------------------------------ indicators
def ema(x, n):
    return pd.Series(x).ewm(span=n, adjust=False).mean().values


def wilder(x, n):
    return pd.Series(x).ewm(alpha=1.0 / n, adjust=False).mean().values


@njit(cache=True)
def _supertrend(h, l, c, atr, mult):
    n = len(c)
    side = np.zeros(n)
    line = np.full(n, np.nan)
    up_prev, dn_prev, s = np.nan, np.nan, 1.0
    for i in range(n):
        if not (np.isfinite(atr[i]) and np.isfinite(c[i])):
            side[i] = s
            continue
        mid = (h[i] + l[i]) / 2.0
        up = mid - mult * atr[i]
        dn = mid + mult * atr[i]
        if np.isfinite(up_prev) and i > 0 and c[i - 1] > up_prev:
            up = max(up, up_prev)
        if np.isfinite(dn_prev) and i > 0 and c[i - 1] < dn_prev:
            dn = min(dn, dn_prev)
        if s > 0 and c[i] < up:
            s = -1.0
        elif s < 0 and c[i] > dn:
            s = 1.0
        side[i] = s
        line[i] = up if s > 0 else dn
        up_prev, dn_prev = up, dn
    return side, line


def since_change(sign: np.ndarray) -> np.ndarray:
    """Bars since the sign last changed."""
    s = pd.Series(np.sign(sign))
    grp = (s != s.shift()).cumsum()
    return s.groupby(grp).cumcount().values.astype(float)


def indicators(B: pd.DataFrame, tag: str) -> pd.DataFrame:
    h, l, c = B.h.values.astype(float), B.l.values.astype(float), B.c.values.astype(float)
    pc = np.r_[np.nan, c[:-1]]
    out = {}
    E = {n: ema(c, n) for n in set(EMA_N) | {a for p in CROSSES for a in p}}
    for n in EMA_N:
        out[f"{tag}_ema{n}_d"] = (c - E[n]) / c * 100
    for a, b in CROSSES:
        sp = (E[a] - E[b]) / c * 100
        out[f"{tag}_x{a}_{b}"] = sp
        out[f"{tag}_x{a}_{b}_age"] = since_change(sp)
    d = np.diff(c, prepend=np.nan)
    for n in RSI_N:
        up, dn = wilder(np.clip(np.nan_to_num(d), 0, None), n), wilder(np.clip(-np.nan_to_num(d), 0, None), n)
        out[f"{tag}_rsi{n}"] = 100 - 100 / (1 + up / np.where(dn == 0, np.nan, dn))
    for n in (5, 14):
        hh = pd.Series(h).rolling(n).max().values
        ll = pd.Series(l).rolling(n).min().values
        out[f"{tag}_stoch{n}"] = (c - ll) / np.where(hh - ll == 0, np.nan, hh - ll) * 100
    hh, ll = pd.Series(h).rolling(14).max().values, pd.Series(l).rolling(14).min().values
    out[f"{tag}_willr14"] = (hh - c) / np.where(hh - ll == 0, np.nan, hh - ll) * -100
    tp = (h + l + c) / 3
    sma = pd.Series(tp).rolling(20).mean()
    mad = pd.Series(tp).rolling(20).apply(lambda x: np.mean(np.abs(x - x.mean())), raw=True)
    out[f"{tag}_cci20"] = ((tp - sma) / (0.015 * mad.replace(0, np.nan))).values
    for f, s, g in MACD_P:
        m = ema(c, f) - ema(c, s)
        out[f"{tag}_macdh{f}_{s}_{g}"] = (m - ema(m, g)) / c * 100
    for n, k in BB_P:
        mu = pd.Series(c).rolling(n).mean().values
        sd = pd.Series(c).rolling(n).std().values
        out[f"{tag}_bbpb{n}_{k}"] = (c - (mu - k * sd)) / np.where(sd == 0, np.nan, 2 * k * sd)
        out[f"{tag}_bbw{n}_{k}"] = 2 * k * sd / mu * 100
    tr = np.nanmax(np.vstack([h - l, np.abs(h - pc), np.abs(l - pc)]), axis=0)
    for n in ATR_N:
        out[f"{tag}_atr{n}"] = wilder(tr, n) / c * 100
    for n, k in ST_P:
        side, line = _supertrend(h, l, c, wilder(tr, n), k)
        out[f"{tag}_st{n}_{k}_side"] = side
        out[f"{tag}_st{n}_{k}_d"] = (c - line) / c * 100
    upm = np.r_[np.nan, np.diff(h)]
    dnm = np.r_[np.nan, -np.diff(l)]
    pdm = np.where((upm > dnm) & (upm > 0), upm, 0.0)
    ndm = np.where((dnm > upm) & (dnm > 0), dnm, 0.0)
    for n in ADX_N:
        at = wilder(tr, n)
        pdi, ndi = 100 * wilder(pdm, n) / at, 100 * wilder(ndm, n) / at
        dx = 100 * np.abs(pdi - ndi) / np.where(pdi + ndi == 0, np.nan, pdi + ndi)
        out[f"{tag}_adx{n}"] = wilder(np.nan_to_num(dx), n)
        out[f"{tag}_di{n}"] = pdi - ndi
    for n in MOM_N:
        out[f"{tag}_mom{n}"] = (c / pd.Series(c).shift(n).values - 1) * 100
    for n in DON_N:
        hh, ll = pd.Series(h).rolling(n).max().values, pd.Series(l).rolling(n).min().values
        out[f"{tag}_don{n}"] = (c - ll) / np.where(hh - ll == 0, np.nan, hh - ll)
    F = pd.DataFrame(out)
    F.insert(0, "end", B.end.values)
    F.insert(0, "day", B.day.values)
    return F


def at_rows(F: pd.DataFrame, days: pd.Index, tf: int) -> pd.DataFrame:
    """Values of the last tf-bar CLOSED by each decision row (end row <= t)."""
    out = []
    F = F.set_index(["day", "end"])
    for t in ROWS:
        last_end = ((t + 1) // tf) * tf - 1
        idx = pd.MultiIndex.from_arrays([days, np.full(len(days), last_end)])
        x = F.reindex(idx)
        x.index = pd.MultiIndex.from_arrays([days, np.full(len(days), t)], names=["day", "row"])
        out.append(x)
    return pd.concat(out)


# ------------------------------------------------------------------------------------------------ option chain: profile + order flow
def chain_features(und: str, days: pd.Index) -> pd.DataFrame:
    """Per (day, row): the day's option VOLUME profile by strike so far (POC, volume-weighted strike, value area 70%),
    OI-weighted strikes, and a tick-rule order-flow proxy (option volume signed by the contract's price change: calls
    up / puts down = bullish)."""
    res = []
    for p in sorted(D.LAKE.rglob(f"opt_{und}_WEEK_*.parquet")):
        o = pd.read_parquet(p, columns=["ts", "side", "strike", "spot", "close", "volume", "oi"])
        ts = pd.to_datetime(o.ts)
        if ts.dt.tz is not None:
            ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        o = o.assign(day=ts.dt.normalize(), m=((ts - ts.dt.normalize()).dt.total_seconds() // 60 - 555).astype(int))
        o = o[(o.m >= 0) & (o.m < 375) & o.day.isin(days)].drop(columns="ts")
        if o.empty:
            continue
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o.drop_duplicates(["day", "m", "strike", "side"]).sort_values(["day", "strike", "side", "m"])
        dc = o.groupby(["day", "strike", "side"]).close.diff()
        o["sv"] = o.volume * np.sign(dc.fillna(0)) * np.where(o.side == "C", 1, -1)
        for d, g in o.groupby("day", sort=True):
            for t in ROWS:
                x = g[g.m <= t]
                if x.empty:
                    continue
                spot = x.spot[x.m == x.m.max()].iloc[0]
                v = x.groupby("strike").volume.sum()
                tot = v.sum()
                row = dict(day=d, row=t)
                if tot > 0:
                    poc = v.idxmax()
                    row["vp_poc_d"] = (poc - spot) / spot * 100
                    row["vp_vws_d"] = (np.average(v.index, weights=v.values) - spot) / spot * 100
                    srt = v.sort_values(ascending=False)
                    keep = srt.index[: int(np.searchsorted(srt.cumsum().values, 0.7 * tot) + 1)]
                    row["vp_vah_d"] = (keep.max() - spot) / spot * 100
                    row["vp_val_d"] = (keep.min() - spot) / spot * 100
                    cv = x[x.side == "C"].volume.sum()
                    row["vp_call_share"] = cv / tot
                    above = v[v.index > spot].sum()
                    row["vp_above_share"] = above / tot
                    rec = x[x.m > t - 15]
                    rv = rec.volume.sum()
                    row["vp_call_share15"] = rec[rec.side == "C"].volume.sum() / rv if rv > 0 else np.nan
                    row["of_cum"] = x.sv.sum() / tot
                    row["of_15"] = rec.sv.sum() / rv if rv > 0 else np.nan
                    rec5 = x[x.m > t - 5]
                    v5 = rec5.volume.sum()
                    row["of_5"] = rec5.sv.sum() / v5 if v5 > 0 else np.nan
                last = x[x.m == x.m.max()]
                for s, nm in (("C", "ce"), ("P", "pe")):
                    y = last[last.side == s]
                    if y.oi.sum() > 0:
                        row[f"oi_ws_{nm}_d"] = (np.average(y.strike, weights=y.oi) - spot) / spot * 100
                first = x[x.m == x.m.min()]
                oi0 = first.groupby(["strike", "side"]).oi.sum()
                oi1 = last.groupby(["strike", "side"]).oi.sum()
                ch = (oi1 - oi0.reindex(oi1.index)).dropna()
                if len(ch) and np.abs(ch).sum() > 0:
                    row["oi_chg_ce_pe"] = (ch.xs("C", level=1).sum() - ch.xs("P", level=1).sum()) / np.abs(ch).sum() \
                        if "C" in ch.index.get_level_values(1) and "P" in ch.index.get_level_values(1) else np.nan
                res.append(row)
    return pd.DataFrame(res).set_index(["day", "row"]) if res else pd.DataFrame()


def gex_features(und: str) -> pd.DataFrame:
    p = D.CACHE.root / "r28" / f"gex_{und}.parquet"
    if not p.exists():
        return pd.DataFrame()
    g = pd.read_parquet(p)
    g = g[g.minute.isin(ROWS)]
    out = pd.DataFrame(dict(day=pd.to_datetime(g.day), row=g.minute.astype(int)))
    sp = g.spot.values
    out["gx_near_ratio"] = g.near_ratio.values
    out["gx_flip_d"] = g.near_flip_dist_pct.values
    out["gx_above_flip"] = g.near_spot_above_flip.values
    for k in ("pos", "neg", "abs"):
        out[f"gx_wall_{k}_d"] = (g[f"near_wall_{k}_strike"].values - sp) / sp * 100
    out["gx_edge_share"] = g.near_edge_share.values
    out["gx_net_share"] = g.all_net_cr.values / np.where(g.all_gross_cr.values == 0, np.nan, g.all_gross_cr.values)
    out["gx_near_share"] = g.near_share.values
    out["gx_month_net_share"] = g.month_net_cr.values / np.where(g.month_gross_cr.values == 0, np.nan, g.month_gross_cr.values)
    return out.drop_duplicates(["day", "row"]).set_index(["day", "row"])


# ------------------------------------------------------------------------------------------------ context
def context(M: pd.DataFrame, und: str, days: pd.Index) -> pd.DataFrame:
    """Round numbers, previous-day Fibonacci levels, multi-day returns, the gap, the session TWAP, other indices."""
    C = M.pivot_table(index="day", columns="m", values="c", aggfunc="last").reindex(columns=range(375))
    H = M.groupby("day").h.max()
    L = M.groupby("day").l.min()
    O = M[M.m == 0].set_index("day").o
    last = C.ffill(axis=1)[374]
    pc, ph, pl = last.shift(1), H.shift(1), L.shift(1)
    out = []
    for t in ROWS:
        c = C[t]
        tw = C.loc[:, :t].mean(axis=1)
        x = pd.DataFrame(index=C.index)
        x["row"] = t
        for k in (100, 500, 1000):
            x[f"rn{k}_d"] = ((c / k).round() * k - c) / c * 100
        rng = (ph - pl).replace(0, np.nan)
        x["pd_pos"] = (c - pl) / rng
        for f in (0.236, 0.382, 0.5, 0.618, 0.786):
            x[f"fib{int(f * 1000)}_d"] = (c - (pl + f * rng)) / c * 100
        for n in (1, 2, 5, 10, 20):
            x[f"r{n}d"] = (pc / last.shift(n + 1) - 1) * 100
        x["gap"] = (O / pc - 1) * 100
        x["twap_d"] = (c - tw) / c * 100
        x["dom"] = C.index.day
        x["month"] = C.index.month
        out.append(x)
    X = pd.concat(out).reset_index().rename(columns={"index": "day"})
    for u in OTHER:
        if u == und:
            continue
        try:
            Mo = minutes(u)
        except ValueError:
            continue
        Co = Mo.pivot_table(index="day", columns="m", values="c", aggfunc="last").reindex(columns=range(375)).ffill(axis=1)
        Oo = Mo[Mo.m == 0].set_index("day").o
        for t in ROWS:
            sel = X.row == t
            dd = pd.to_datetime(X.loc[sel, "day"])
            X.loc[sel, f"oth_{u}_r15"] = ((Co[t] / Co[t - 15] - 1) * 100).reindex(dd).values
            X.loc[sel, f"oth_{u}_rday"] = ((Co[t] / Oo - 1) * 100).reindex(dd).values
    X = X[X.day.isin(days)]
    return X.set_index(["day", "row"])


# ------------------------------------------------------------------------------------------------ build
def build(und: str) -> Path:
    M = minutes(und)
    days = pd.Index(sorted(M.day.unique()))
    days = days[days >= pd.Timestamp("2021-07-01")]
    parts = []
    for tf in (1, 5, 15):
        F = indicators(bars(M, tf), f"t{tf}")
        parts.append(at_rows(F, days, tf))
    parts.append(context(M, und, days))
    parts.append(gex_features(und))
    parts.append(chain_features(und, days))
    X = parts[0]
    for p in parts[1:]:
        if len(p):
            X = X.join(p, how="left")
    X = X.astype(np.float32).reset_index()
    q = D.OUT / f"sweep_{und}.parquet"
    q.parent.mkdir(parents=True, exist_ok=True)
    X.to_parquet(q, compression="zstd", index=False)
    print(und, X.shape, flush=True)
    return q


if __name__ == "__main__":
    if sys.argv[1:2] == ["build"]:
        for u in sys.argv[2:] or D.UNDS:
            build(u)
