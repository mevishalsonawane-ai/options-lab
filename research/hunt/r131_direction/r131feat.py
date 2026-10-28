"""R131 features and targets (PREREG sections 2-3).

daily_panel(srcs) turns the raw source tables into one table per index, indexed by that index's trading days, where
every column is tagged with WHEN it is known: 'close' (at the close of the day), 'evening' (published that evening:
participants, bhavcopy, NSE index file, MCX close, stock-futures OI) or 'evening1' (FPI: one more trading day later).
assemble() then applies the publication lags for a decision kind:

    kind 'close'   (GAP, C2C; keyed by decision day D):  close of D, evening of D-1, evening1 of D-2
    kind 'morning' (PRE, DAY, R11, R13, M60; keyed by day E = the day traded): close and evening of D = E-1,
                   evening1 of D-1 = E-2

Calendar inputs depend on the TARGET day and are added in assemble(). Every function is pure (tables in, tables out),
so test_r131.py can feed synthetic data and check that later values never leak in.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import r131cfg as K

R123_MAP = {
    "ri_": ("gap", "from_open", "ret5", "ret15", "ret30", "ret60", "rv30", "day_range", "dist_hi", "dist_lo",
            "prev_range", "prev_ret", "avg_range20", "vix", "vix_chg", "vix_chg30"),
    "rb_": ("br_up", "br_from_open", "br_ret15"),
    "rc_": ("atm_c_pct", "atm_p_pct", "strad_pct", "log_strad_rs", "log_c_rs", "log_p_rs", "atm_iv", "skew", "pcr",
            "pcr_chg15", "c_oi_chg15", "p_oi_chg15", "cp_vol15"),
    "rk_": ("tod", "weekday", "to_expiry", "is_expiry", "is_budget"),
}
MCX = ("GOLD", "SILVER", "CRUDEOIL", "COPPER")


def _lr(a, b):
    with np.errstate(all="ignore"):
        return np.log(a / b) * 100


def _rsi(c: pd.Series, n: int = 14) -> pd.Series:
    d = c.diff()
    up = d.clip(lower=0).rolling(n).mean()
    dn = (-d.clip(upper=0)).rolling(n).mean()
    return 100 - 100 / (1 + up / dn.replace(0, np.nan))


def _streak(r: pd.Series) -> pd.Series:
    s = np.sign(r.fillna(0).values)
    out = np.zeros(len(s))
    for i in range(len(s)):
        if s[i] == 0:
            out[i] = 0
        elif i > 0 and np.sign(out[i - 1]) == s[i]:
            out[i] = out[i - 1] + s[i]
        else:
            out[i] = s[i]
    return pd.Series(out, index=r.index)


def _z(x: pd.Series, n: int) -> pd.Series:
    return x / x.rolling(n, min_periods=max(5, n // 2)).std()


def _on(cal: pd.DatetimeIndex, s: pd.Series, asof: bool = False) -> pd.Series:
    """Values of a date-indexed series on the trading calendar: the same date (or, asof=True, the latest date <= it)."""
    s = s[~s.index.duplicated(keep="last")].sort_index()
    if asof:
        return s.reindex(s.index.union(cal)).ffill().reindex(cal)
    return s.reindex(cal)


# ------------------------------------------------------------------------------------------------ market-wide
def market_wide(srcs: dict) -> tuple[pd.DataFrame, dict]:
    """Columns shared by every index, indexed by date (their own calendar), with their class."""
    cols, cls = {}, {}
    eq = srcs.get("equity_daily")
    if eq is not None and len(eq):
        c = eq.pivot_table(index="date", columns="symbol", values="close", aggfunc="last").sort_index()
        r = _lr(c, c.shift(1))
        ok = r.notna()
        cols["brd_up"] = (r > 0).where(ok).mean(axis=1)
        cols["brd_mean"] = r.mean(axis=1)
        cols["brd_disp"] = r.std(axis=1)
        cols["brd_ma20"] = (c > c.rolling(20).mean()).where(c.rolling(20).mean().notna()).mean(axis=1)
        cols["brd_up5"] = cols["brd_up"].rolling(5).mean()
        for k in ("brd_up", "brd_mean", "brd_disp", "brd_ma20", "brd_up5"):
            cls[k] = "close"
    sf = srcs.get("stock_fut")
    if sf is not None and len(sf):
        c = sf.pivot_table(index="date", columns="symbol", values="close", aggfunc="last").sort_index()
        oi = sf.pivot_table(index="date", columns="symbol", values="oi", aggfunc="last").reindex(c.index)
        r, doi = c.diff(), oi.diff()
        ok = r.notna() & doi.notna()
        cols["brd_lbu"] = ((r > 0) & (doi > 0)).where(ok).mean(axis=1)
        cols["brd_sbu"] = ((r < 0) & (doi > 0)).where(ok).mean(axis=1)
        cls["brd_lbu"] = cls["brd_sbu"] = "evening"
    nse = srcs.get("nse_indices")
    if nse is not None and len(nse):
        p = nse.pivot_table(index="date", columns="key", values="close", aggfunc="last").sort_index()
        def r1(k, n=1):
            return _lr(p[k], p[k].shift(n)) if k in p else pd.Series(np.nan, index=p.index)
        cols["brd_mid"] = r1("MID100") - r1("NIFTY")
        cols["brd_small"] = r1("SMALL100") - r1("NIFTY")
        if "N50USD" in p and "NIFTY" in p:
            fx = p["NIFTY"] / p["N50USD"]
            cols["glob_usd1"] = _lr(fx, fx.shift(1))
            cols["glob_usd5"] = _lr(fx, fx.shift(5))
        cols["glob_gsec1"] = r1("GSEC10")
        cols["glob_gsec5"] = r1("GSEC10", 5)
        for k in ("brd_mid", "brd_small", "glob_usd1", "glob_usd5", "glob_gsec1", "glob_gsec5"):
            cls[k] = "evening"
    pt = srcs.get("participants")
    if pt is not None and len(pt):
        pt = pt.set_index("date").sort_index()
        for g in ("FII", "DII", "Pro", "Client"):
            L, S = pt[f"{g}_fil"], pt[f"{g}_fis"]
            x = L - S
            cols[f"part_{g}_net"] = x / (L + S)
            cols[f"part_{g}_dnet"] = _z(x.diff(), 20)
            cls[f"part_{g}_net"] = cls[f"part_{g}_dnet"] = "evening"
        for g in ("FII", "Client"):
            cl, cs, pl, ps = (pt[f"{g}_{k}"] for k in ("cl", "cs", "pl", "ps"))
            cols[f"part_{g}_optdir"] = ((cl - cs) - (pl - ps)) / (cl + cs + pl + ps)
            cls[f"part_{g}_optdir"] = "evening"
        x = pt["FII_fil"] - pt["FII_fis"]
        cols["part_FII_d5"] = _z(x - x.shift(5), 60)
        cls["part_FII_d5"] = "evening"
    fp = srcs.get("fpi")
    if fp is not None and len(fp):
        v = fp.set_index("date").eq_total_net_cr.sort_index()
        cols["fpi_net"] = _z(v, 60)
        cols["fpi_5"] = _z(v.rolling(5).sum(), 60)
        cls["fpi_net"] = cls["fpi_5"] = "evening1"
    fut = srcs.get("fut_daily")
    mcx = {}
    if fut is not None and len(fut):
        for m in MCX:
            s = fut[fut.symbol == m].set_index("date").close.sort_index()
            if len(s):
                mcx[f"mcx_{m.lower()}"] = _lr(s, s.shift(1)).clip(-4, 4)
                cls[f"mcx_{m.lower()}"] = "evening"
    frames = [pd.Series(v, name=k) for k, v in cols.items()]
    mw = pd.concat(frames, axis=1).sort_index() if frames else pd.DataFrame()
    return mw, cls, mcx


# ------------------------------------------------------------------------------------------------ per index
def nse_check(idx: pd.DataFrame, nse: pd.DataFrame | None, u: str, tol: float = 0.005) -> tuple[pd.DataFrame, dict]:
    """PREREG section 2 data check: a close that differs from NSE's own file by > 0.5% blanks that day; an open equal
    to the previous close (stale) blanks the open."""
    idx = idx.copy()
    log = {"days": int(len(idx)), "close_mismatch": 0, "stale_open": 0}
    if nse is not None and len(nse):
        n = nse[nse.key == u].set_index("date").close
        ref = n.reindex(idx.index)
        bad = (ref.notna() & ((idx.close / ref - 1).abs() > tol)).values
        log["close_mismatch"] = int(bad.sum())
        idx.loc[bad, ["open", "high", "low", "close"]] = np.nan
    stale = (idx.open == idx.close.shift(1)).values
    log["stale_open"] = int(stale.sum())
    idx.loc[stale, "open"] = np.nan
    return idx, log


def und_panel(u: str, srcs: dict, mw: pd.DataFrame, mw_cls: dict, mcx: dict, chain: pd.DataFrame | None,
              expiries: dict) -> tuple[pd.DataFrame, dict, dict]:
    """One index's daily table on its own trading calendar (all classes, before any lag) + the class map + the
    data-check log."""
    idd = srcs["index_daily"]
    raw = idd[idd.symbol == u].set_index("date")[["open", "high", "low", "close"]].sort_index()
    raw, log = nse_check(raw, srcs.get("nse_indices"), u)
    cal = raw.index
    o, h, l, c = raw.open, raw.high, raw.low, raw.close
    f, cls = {}, {}
    r1 = _lr(c, c.shift(1))
    f["idx_r1"] = r1
    f["idx_r2_5"] = _lr(c.shift(1), c.shift(5))
    f["idx_r20"] = _lr(c, c.shift(20))
    for n in (20, 50, 200):
        f[f"idx_ma{n}"] = (c / c.rolling(n, min_periods=int(n * 0.8)).mean() - 1) * 100
    f["idx_rsi14"] = _rsi(c)
    f["idx_rng"] = (h - l) / c * 100
    f["idx_clv"] = (c - l) / (h - l).replace(0, np.nan) - 0.5
    f["idx_gap"] = _lr(o, c.shift(1))
    f["idx_oc"] = _lr(c, o)
    f["idx_rv20"] = r1.rolling(20, min_periods=15).std()
    f["idx_streak"] = _streak(r1)
    for nm, sym in (("idx_nifty_r1", "NIFTY"), ("idx_bank_r1", "BANKNIFTY")):
        s = idd[idd.symbol == sym].set_index("date").close.sort_index()
        f[nm] = _on(cal, _lr(s, s.shift(1)))
    v = idd[idd.symbol == "INDIAVIX"].set_index("date").close.sort_index()
    v = v[v > 0]
    f["vix_lvl"] = _on(cal, v)
    f["vix_chg"] = _on(cal, _lr(v, v.shift(1)))
    f["vix_rel20"] = _on(cal, v / v.rolling(20, min_periods=15).mean() - 1)
    for k in f:
        cls[k] = "close"
    fut = srcs.get("fut_daily")
    if fut is not None and len(fut):
        fs = fut[fut.symbol == u].set_index("date").sort_index()
        if len(fs):
            fc = _on(cal, fs.close)
            f["fut_basis"] = (fc / c - 1) * 100
            f["fut_basis_chg"] = f["fut_basis"].diff()
            cls["fut_basis"] = cls["fut_basis_chg"] = "close"
            f["fut_oi_chg"] = _on(cal, _lr(fs.oi.replace(0, np.nan), fs.oi.replace(0, np.nan).shift(1)))
            cls["fut_oi_chg"] = "evening"
    if chain is not None and len(chain) and u in K.NSE_UNDS:
        ch = chain[chain.und == u].set_index("date").sort_index()
        for k in ("ch_pcr", "ch_pcr_vol", "ch_maxc", "ch_maxp", "ch_strad", "ch_skew", "fut_roll"):
            f[k] = _on(cal, ch[k].astype(float))
            cls[k] = "evening"
        f["ch_pcr_chg"] = _on(cal, ch.ch_pcr.astype(float).diff())
        f["ch_strad_chg"] = _on(cal, ch.ch_strad.astype(float).diff())
        cls["ch_pcr_chg"] = cls["ch_strad_chg"] = "evening"
    for k in mw.columns:
        f[k] = _on(cal, mw[k], asof=(mw_cls[k] == "evening1"))
        cls[k] = mw_cls[k]
    for k, s in mcx.items():
        f[k] = _on(cal, s, asof=True)
        cls[k] = "evening"
    p = pd.DataFrame(f, index=cal)
    p["_open"], p["_close"] = o, c
    # calendar helpers (pure functions of the trading calendar, which is known in advance)
    ex = sorted(expiries.get(u, []))
    eff = sorted({cal[cal <= e].max() for e in ex if (cal <= e).any()})
    pos = {d: i for i, d in enumerate(cal)}
    dte = np.full(len(cal), np.nan)
    if eff:
        ei = np.array([pos[e] for e in eff])
        for i in range(len(cal)):
            j = np.searchsorted(ei, i)
            if j < len(ei):
                dte[i] = ei[j] - i
    p["_dte"] = dte
    return p, cls, log


def assemble(p: pd.DataFrame, cls: dict, kind: str, budget: set) -> pd.DataFrame:
    """Apply the publication lags. kind 'close': row D (decision at D's close, target day = next trading day).
    kind 'morning': row E (decision on E, information up to the evening of the previous trading day)."""
    cal = p.index
    shift = {"close": {"close": 0, "evening": 1, "evening1": 2},
             "morning": {"close": 1, "evening": 1, "evening1": 2}}[kind]
    out = {}
    for k, c in cls.items():
        out[k] = p[k].shift(shift[c])
    a = pd.DataFrame(out, index=cal)
    nxt = pd.Series(cal, index=cal).shift(-1)
    prv = pd.Series(cal, index=cal).shift(1)
    if kind == "close":
        tday = nxt
        a["cal_gapdays"] = (nxt - pd.Series(cal, index=cal)).dt.days
        a["cal_dte"] = p["_dte"].shift(-1)
    else:
        tday = pd.Series(cal, index=cal)
        a["cal_gapdays"] = (pd.Series(cal, index=cal) - prv).dt.days
        a["cal_dte"] = p["_dte"]
    a["cal_dow"] = tday.dt.weekday
    a["cal_isexp"] = (a["cal_dte"] == 0).astype(float).where(a["cal_dte"].notna())
    ym = pd.Series(cal, index=cal).dt.to_period("M")
    rank_f = ym.groupby(ym).cumcount()
    rank_b = ym.groupby(ym).cumcount(ascending=False)
    mt = ((rank_f < 3) | (rank_b < 2)).astype(float)
    a["cal_mturn"] = mt.shift(-1) if kind == "close" else mt
    a["cal_budget"] = tday.isin(budget).astype(float)
    a["tday"] = tday
    return a


def daily_targets(p: pd.DataFrame) -> pd.DataFrame:
    """Keyed by decision day D: GAP = close D -> open D+1, C2C = close D -> close D+1 (log %, NaN when either price
    is missing). B1 (follow) for both = sign of D's close-to-close."""
    o, c = p["_open"], p["_close"]
    t = pd.DataFrame(index=p.index)
    t["GAP"] = _lr(o.shift(-1), c)
    t["C2C"] = _lr(c.shift(-1), c)
    t["follow"] = np.sign(_lr(c, c.shift(1)))
    return t


# ------------------------------------------------------------------------------------------------ intraday
def eq_breadth(close: np.ndarray, opn: np.ndarray, prev_close: np.ndarray, t: int) -> dict:
    """59-stock breadth at bar t of ONE session from bars <= t only. close/opn: (stocks, N) for that session (NaN where
    no bar); prev_close: (stocks,) each stock's last close of the previous session."""
    out = {"eq_up": np.nan, "eq_prev": np.nan, "eq_open": np.nan, "eq_r15": np.nan}
    c = pd.DataFrame(close[:, :t + 1].T).ffill().values.T          # forward fill within the session, bars <= t
    ct = c[:, -1]
    with np.errstate(all="ignore"):
        first = np.array([row[np.isfinite(row)][0] if np.isfinite(row).any() else np.nan for row in opn[:, :t + 1]])
        rp = np.log(ct / prev_close) * 100
        ro = np.log(ct / first) * 100
        ok = np.isfinite(rp)
        if ok.sum() >= 10:
            out["eq_up"] = float((rp[ok] > 0).mean())
            out["eq_prev"] = float(np.nanmean(rp))
        if np.isfinite(ro).sum() >= 10:
            out["eq_open"] = float(np.nanmean(ro))
        if t >= 15:
            r15 = np.log(ct / c[:, t - 15]) * 100
            if np.isfinite(r15).sum() >= 10:
                out["eq_r15"] = float(np.nanmean(r15))
    return out


def intra_targets(c_day: np.ndarray, t: int, horizon: str) -> float:
    """Index move (log %) from the close of bar t to the horizon end, bars > t of the SAME session only."""
    c0 = c_day[t]
    if not np.isfinite(c0):
        prev = c_day[:t + 1][np.isfinite(c_day[:t + 1])]
        c0 = prev[-1] if len(prev) else np.nan
    if horizon == "M60":
        if t + 60 >= len(c_day):
            return np.nan
        ce = c_day[t + 60]
    else:
        after = c_day[t + 1:]
        fin = np.flatnonzero(np.isfinite(after))
        ce = after[fin[-1]] if len(fin) else np.nan
    with np.errstate(all="ignore"):
        return float(np.log(ce / c0) * 100) if np.isfinite(ce) and np.isfinite(c0) and c0 > 0 else np.nan


def ovn_target(c_day: np.ndarray, t: int, o_next: np.ndarray, c_next: np.ndarray, horizon: str) -> float:
    """Amendment 1: index move (log %) from the close of bar t of day D to the NEXT session's first open (GAP_T) or
    last close (C2C_T). Reads bars <= t of D only for the entry."""
    seg = c_day[:t + 1]
    fin = seg[np.isfinite(seg)]
    c0 = fin[-1] if len(fin) else np.nan
    x = o_next if horizon == "GAP_T" else c_next
    f = np.flatnonzero(np.isfinite(x))
    if not len(f):
        return np.nan
    ce = x[f[0]] if horizon == "GAP_T" else x[f[-1]]
    with np.errstate(all="ignore"):
        return float(np.log(ce / c0) * 100) if np.isfinite(c0) and c0 > 0 and ce > 0 else np.nan


def feature_cols(df: pd.DataFrame, set_name: str) -> list:
    srcs = ("IDX",) + K.SOURCES if set_name == "ALL" else tuple(set_name.split("+"))
    pre = tuple(p for s in srcs for p in K.PREFIX[s])
    return [c for c in df.columns if c.startswith(pre)]
