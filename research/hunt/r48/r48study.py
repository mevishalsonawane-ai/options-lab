"""R48 study driver (PREREG.md, 15 Oct 2026).

    python3 research/hunt/r48/r48study.py enum <UND> sell|buy     # design paths of one index -> cache (parallel-safe)
    python3 research/hunt/r48/r48study.py design                  # every config's design stats, candidates, WF report
    python3 research/hunt/r48/r48study.py holdout                 # ONE read of the locked holdout for the candidates
    python3 research/hunt/r48/r48study.py extras                  # tails, doubled ticks (design; holdout after the read)

Design entries: first session .. 17 Oct 2025. Holdout entries: 3 Nov 2025 .. last session, read once (refuses to run
if results/holdout.json exists). Signal source: the index (default) for every rule.
"""
from __future__ import annotations

import itertools
import json
import os
import pickle
import sys
from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r48cfg as K  # noqa: E402
import r48cost as X  # noqa: E402
import r48data as D  # noqa: E402
import r48sim as S  # noqa: E402

SELL_COLS = [(p, s, i, h) for h in K.HOLD for i in K.ISL for s in K.SLS for p in K.PTS]
BUY_COLS = [(t, s, tm) for tm in K.BTIME for s in K.BSTOPS for t in K.TGTS]
SELL_KINDS = [(k, m, w) for k in ("BPS", "BCS", "IC") for m in K.M_DIST for w in K.WIDTHS] + \
             [("IB", 0.0, w) for w in K.WIDTHS]


# ------------------------------------------------------------------------------------------------ per-index context
@dataclass
class Ctx:
    und: str
    days: list
    dates: list
    exp_idx: np.ndarray        # (ndays,) index of the day's expiry (-1 unknown)
    dte: np.ndarray            # (ndays,) sessions to expiry (-1 unknown)
    prev_close: np.ndarray     # (ndays,) index close of the previous session
    ema_prev: np.ndarray       # (ndays,) EMA20 of daily closes up to the previous session
    vix_prev: np.ndarray       # (ndays,) INDIAVIX close of the previous session
    switch: np.ndarray         # (ndays,) bool: the night before this day looks like a contract switch (amendment 1)
    cache: S.LegCache


def context(und: str, days=None, daily=None, expiries=None) -> Ctx:
    days = D.load(und) if days is None else days
    dates = [d.day for d in days]
    E = sorted(set(D.expiry_days(days) if expiries is None else expiries))
    pos = {d: i for i, d in enumerate(dates)}
    n = len(days)
    exp_idx = np.full(n, -1)
    ei = [pos[e] for e in E if e in pos]
    k = 0
    for i in range(n):
        while k < len(ei) and ei[k] < i:
            k += 1
        if k < len(ei):
            exp_idx[i] = ei[k]
    dte = np.where(exp_idx >= 0, exp_idx - np.arange(n), -1)
    daily = D.daily_index() if daily is None else daily
    ix = daily[daily.symbol == und].sort_values("date")
    cl = pd.Series(ix.close.to_numpy(), index=ix.date.to_numpy())
    ema = cl.ewm(span=20, adjust=False).mean()
    vx = daily[daily.symbol == "INDIAVIX"].sort_values("date")
    vxs = pd.Series(vx.close.to_numpy(), index=vx.date.to_numpy())

    def prev_of(s: pd.Series, d):
        k = s.index.searchsorted(d) - 1
        return float(s.iloc[k]) if k >= 0 else np.nan
    prev_close = np.array([prev_of(cl, d) for d in dates])
    ema_prev = np.array([prev_of(ema, d) for d in dates])
    vix_prev = np.array([prev_of(vxs, d) for d in dates]) if len(vxs) else np.full(n, np.nan)
    # contract-switch guard (amendment 1)
    tv = np.full(n, np.nan)
    close = np.array([d.spot[-1] for d in days])
    for i, d in enumerate(days):
        a = S.atm_straddle(d, S.T1500)
        if a is not None:
            tv[i] = (a[1] - abs(d.spot[S.T1500] - a[0])) / d.spot[S.T1500] * 100
    isexp = np.zeros(n, dtype=bool)
    isexp[ei] = True
    switch = np.zeros(n, dtype=bool)
    switch[1:] = (tv[1:] > 2 * tv[:-1]) & ~isexp[:-1] & (np.abs(close[1:] / close[:-1] - 1) < 0.015)
    return Ctx(und, days, dates, exp_idx, dte, prev_close, ema_prev, vix_prev, switch, S.LegCache(days))


def signals(ctx: Ctx, i: int, t: int) -> dict:
    """Index direction signals at decision minute t of day i (+1 up, -1 down, 0 none). Only data up to t's close."""
    s = ctx.days[i].spot
    pc, em = ctx.prev_close[i], ctx.ema_prev[i]
    out = {"gap": int(np.sign(s[t] - pc)) if np.isfinite(pc) else 0,
           "ema": int(np.sign(pc - em)) if np.isfinite(pc) and np.isfinite(em) else 0,
           "twap": int(np.sign(s[t] - np.mean(s[: t + 1])))}
    if t >= 15:
        hi, lo = np.max(s[:15]), np.min(s[:15])
        out["orb"] = 1 if s[t] > hi else (-1 if s[t] < lo else 0)
    else:
        out["orb"] = 0
    return out


def events_between(d0: date, d1: date) -> bool:
    return any(d0 <= e <= d1 for e in K.EVENT_DAYS)


def entry_days(ctx: Ctx, period: str, max_dte: int | None = max(K.DTES), start=None) -> list[int]:
    """Day indices allowed as entries in a period ('design' or 'holdout'), with a known expiry at most max_dte
    sessions away (None: any). start: first design day (amendment 3/4 enumerate from DESIGN_START)."""
    out = []
    for i, d in enumerate(ctx.dates):
        if period == "design" and (d > K.DESIGN_END or (start is not None and d < start)):
            continue
        if period == "holdout" and d < K.HOLDOUT_START:
            continue
        if ctx.dte[i] >= 0 and (max_dte is None or ctx.dte[i] <= max_dte):
            out.append(i)
    return out


# ------------------------------------------------------------------------------------------------ path records
def sell_record(ctx: Ctx, i: int, t: int, kind: str, m: float, w: int, cols=SELL_COLS, tick_mult: float = 1.0):
    """One credit position decided at (day i, minute t): (meta dict, pnl[ncols], off[ncols], entry_cash, mc[5]) or a
    reason string when no trade."""
    day = ctx.days[i]
    spec = S.credit_legs(kind, day, t, m, w)
    if spec is None:
        return "no-chain"
    legs, kp, kc, W = spec
    j = int(ctx.exp_idx[i])
    dte = int(ctx.dte[i])
    path = S.build_path(ctx.cache, ctx.und, i, j, t + 1, legs)
    if path is None:
        return "no-row"
    credit = S.credit_of(path)
    if not credit > 0:
        return "no-credit"
    q = path.qty
    sp = float(day.spot[t])
    m_day = X.margin(ctx.und, day.day, sp, q, W, 1, dte == 0)
    m_exp = X.margin(ctx.und, day.day, sp, q, W, 1, True)
    istop = S.index_stop(path, kind, kp, kc, credit)
    ex = S.credit_exits(path, ctx.days, dte, credit, istop, tick_mult=tick_mult)
    pnl = np.full(len(cols), np.nan, np.float32)
    off = np.full(len(cols), -1, np.int8)
    for c, key in enumerate(cols):
        e = ex.get(key)
        if e is not None:
            pnl[c] = e.pnl
            off[c] = e.off
    entry_cash = -float((np.array([L.pos for L in legs]) * path.entry_px).sum()) * q - path.entry_charges
    mc = np.full(5, np.nan)
    for o in range(min(5, j - i + 1)):
        mc[o] = float(path.mark[path.close_g(o)]) * q
    guard_bad = bool(ctx.switch[i + 1: j + 1].any())
    meta = dict(i=i, date=day.day, dte=dte, t=t, kind=kind, m=m, w=w, credit=credit, qty=q, margin_day=m_day,
                margin_exp=m_exp, ev_day=events_between(day.day, day.day),
                ev_exp=events_between(day.day, ctx.dates[j]), guard_bad=guard_bad,
                maxloss=W * q - credit * q, vix=ctx.vix_prev[i], **{f"s_{k}": v for k, v in signals(ctx, i, t).items()})
    return meta, pnl, off, entry_cash, mc


def buy_record(ctx: Ctx, i: int, t: int, side: int, itm: int, cols=BUY_COLS, tick_mult: float = 1.0):
    day = ctx.days[i]
    a = S.atm_straddle(day, t)
    if a is None:
        return "no-chain"
    katm = a[0]
    st = day.step
    strike = katm - itm * st if side == 0 else katm + itm * st
    path = S.build_path(ctx.cache, ctx.und, i, i, t + 1, [S.Leg(side, strike, +1)])
    if path is None:
        return "no-row"
    dte = int(ctx.dte[i])
    ex = S.buy_exits(path, ctx.days, dte, tick_mult=tick_mult)
    q = path.qty
    pnl = np.full(len(cols), np.nan, np.float32)
    off = np.zeros(len(cols), np.int8)
    for c, key in enumerate(cols):
        pnl[c] = ex[key].pnl
    prem = float(path.entry_px[0])
    entry_cash = -prem * q - path.entry_charges
    mc = np.full(5, np.nan)
    mc[0] = float(path.mark[path.close_g(0)]) * q
    meta = dict(i=i, date=day.day, dte=dte, t=t, side=side, itm=itm, premium=prem, qty=q, margin_day=prem * q,
                margin_exp=prem * q, ev_day=events_between(day.day, day.day), ev_exp=events_between(day.day, day.day),
                guard_bad=False, vix=ctx.vix_prev[i], **{f"s_{k}": v for k, v in signals(ctx, i, t).items()})
    return meta, pnl, off, entry_cash, mc


def enumerate_paths(und: str, fam: str, period: str = "design", days_sel=None, ctx: Ctx | None = None):
    """Every design (or holdout) path of one index and family -> (meta DataFrame, PNL, OFF, ECASH, MC, skips)."""
    ctx = context(und) if ctx is None else ctx
    if days_sel is not None:
        sel = days_sel
    elif fam == "buy":
        sel = entry_days(ctx, period, None, K.DESIGN_START)
    else:
        sel = entry_days(ctx, period)
    metas, P, O, EC, MC = [], [], [], [], []
    skips: dict = {}
    skiprows: list = []
    for i in sel:
        assert (period != "design" or ctx.dates[i] <= K.DESIGN_END) and \
            (period != "holdout" or ctx.dates[i] >= K.HOLDOUT_START)
        for tn, (hh, mm) in (K.TIMES if fam == "sell" else K.BUY_TIMES).items():
            t = D.mi(hh, mm)
            if fam == "sell":
                items = [(k, m, w) for k, m, w in SELL_KINDS]
                for k, m, w in items:
                    r = sell_record(ctx, i, t, k, m, w)
                    if isinstance(r, str):
                        skips[r] = skips.get(r, 0) + 1
                        skiprows.append((i, int(ctx.dte[i]), tn, k, m, w, r))
                        continue
                    r[0]["time"] = tn
                    metas.append(r[0]); P.append(r[1]); O.append(r[2]); EC.append(r[3]); MC.append(r[4])
            else:
                for side in (0, 1):
                    for itm in K.ITM:
                        r = buy_record(ctx, i, t, side, itm)
                        if isinstance(r, str):
                            skips[r] = skips.get(r, 0) + 1
                            skiprows.append((i, int(ctx.dte[i]), tn, "BUY", float(side), itm, r))
                            continue
                        r[0]["time"] = tn
                        metas.append(r[0]); P.append(r[1]); O.append(r[2]); EC.append(r[3]); MC.append(r[4])
        ctx.cache.memo.clear() if len(ctx.cache.memo) > 200_000 else None
    meta = pd.DataFrame(metas)
    meta["und"] = und
    skips["rows"] = pd.DataFrame(skiprows, columns=["i", "dte", "time", "kind", "m", "w", "reason"])
    return meta, np.array(P, np.float32), np.array(O, np.int8), np.array(EC), np.array(MC), skips


def cache_path(und, fam, period="design"):
    return D.CACHE / f"paths_{period}_{fam}_{und}.pkl"


def cmd_enum(und: str, fam: str):
    out = enumerate_paths(und, fam, "design")
    D.CACHE.mkdir(parents=True, exist_ok=True)
    cache_path(und, fam).write_bytes(pickle.dumps(out, protocol=pickle.HIGHEST_PROTOCOL))
    print(und, fam, len(out[0]), "paths; skipped", {k: v for k, v in out[5].items() if k != "rows"}, flush=True)


# ------------------------------------------------------------------------------------------------ configs and stats
def load_family(fam: str, period="design"):
    parts = []
    for u in K.UNDS:
        p = cache_path(u, fam, period)
        if p.exists():
            parts.append(pickle.loads(p.read_bytes()))
    return parts


def config_groups(meta: pd.DataFrame, fam: str):
    """Yield (config dict without exit, row indices, per-row 'buy side'/'kind' already chosen). Selling: plain BPS /
    BCS / IC / IB, and BPS/BCS chosen by an index signal ('with' / 'fade'). Buying: side chosen by a signal ('with')."""
    if fam == "sell":
        for (dte, tm, kind, m, w), g in meta.groupby(["dte", "time", "kind", "m", "w"], sort=False):
            yield dict(kind=kind, m=m, w=w, dte=dte, time=tm, sig="none", dir="none"), g.index.to_numpy()
        sp = meta[meta.kind.isin(["BPS", "BCS"])]
        for (dte, tm, m, w), g in sp.groupby(["dte", "time", "m", "w"], sort=False):
            for sig in K.SIGNALS:
                if sig == "orb" and tm == "0920":
                    continue
                for dr in K.DIRS:
                    want_bps = g[f"s_{sig}"] > 0 if dr == "with" else g[f"s_{sig}"] < 0
                    want_bcs = g[f"s_{sig}"] < 0 if dr == "with" else g[f"s_{sig}"] > 0
                    pick = ((g.kind == "BPS") & want_bps) | ((g.kind == "BCS") & want_bcs)
                    yield dict(kind="DIR", m=m, w=w, dte=dte, time=tm, sig=sig, dir=dr), g.index[pick.to_numpy()].to_numpy()
    else:
        groups = [(k, g) for k, g in meta[meta.dte <= 4].groupby(["dte", "time", "itm"], sort=False)]
        groups += [(("any", tm, itm), g) for (tm, itm), g in meta.groupby(["time", "itm"], sort=False)]
        for (dte, tm, itm), g in groups:
            for sig in K.SIGNALS:
                if sig == "orb" and tm == "0920":
                    continue
                for dr in K.BUY_DIRS:
                    up, dn = (g[f"s_{sig}"] > 0), (g[f"s_{sig}"] < 0)
                    if dr == "fade":
                        up, dn = dn, up
                    pick = ((g.side == 0) & up) | ((g.side == 1) & dn)
                    yield dict(kind="BUY", itm=itm, dte=dte, time=tm, sig=sig, dir=dr), g.index[pick.to_numpy()].to_numpy()


def valid_matrix(meta: pd.DataFrame, rows: np.ndarray, cols, fam: str, vix: str, ev: str, vix_med: float):
    """(n, ncols) bool: the trade exists for that exit column under the filters (margin, guard, VIX, events)."""
    mm = meta.loc[rows]
    hold = np.array([c[3] for c in cols]) if fam == "sell" else np.array(["day"] * len(cols))
    ok_day = (mm.margin_day <= K.ACCOUNT).to_numpy()
    ok_exp = ((mm.margin_exp <= K.ACCOUNT) & ~mm.guard_bad).to_numpy()
    if ev == "skip":
        ok_day = ok_day & ~mm.ev_day.to_numpy()
        ok_exp = ok_exp & ~mm.ev_exp.to_numpy()
    if vix != "all":
        v = mm.vix.to_numpy()
        f = (v <= vix_med) if vix == "low" else (v > vix_med)
        f = f & np.isfinite(v)
        ok_day, ok_exp = ok_day & f, ok_exp & f
    return np.where(hold[None, :] == "exp", ok_exp[:, None], ok_day[:, None])


def halves_default():
    return [(K.DESIGN_START, K.H1_END), (date.fromordinal(K.H1_END.toordinal() + 1), K.DESIGN_END)]


def quick_stats(P: np.ndarray, V: np.ndarray, dates: np.ndarray, halves=None):
    """Column-wise stats of trade P&L P (n, c) where V (valid & finite). halves: the design blocks that must each have
    YEAR_MIN_N+ trades and net > 0 (amendment 3); ys_ok / ys_tot count them."""
    halves = halves_default() if halves is None else halves
    V = V & np.isfinite(P)
    X0 = np.where(V, P, 0.0)
    n = V.sum(0)
    net = X0.sum(0)
    win = (V & (P > 0)).sum(0)
    ys_ok, ys_tot = np.zeros(P.shape[1]), np.zeros(P.shape[1])
    for lo, hi in halves:
        r = np.array([lo <= d <= hi for d in dates], dtype=bool)
        ny = V[r].sum(0)
        my = X0[r].sum(0)
        big = ny >= K.YEAR_MIN_N
        ys_tot += big
        ys_ok += big & (my > 0)
    ly = np.array([K.LAST_YEAR <= d <= K.DESIGN_END for d in dates], dtype=bool)
    n_ly = V[ly].sum(0)
    net_ly = X0[ly].sum(0)
    mean = np.where(n > 0, net / np.maximum(n, 1), np.nan)
    sq = np.where(V, (P - mean[None, :]) ** 2, 0.0).sum(0)
    sd = np.sqrt(sq / np.maximum(n - 1, 1))
    with np.errstate(divide="ignore", invalid="ignore"):
        tstat = np.where((n > 1) & (sd > 0), mean / (sd / np.sqrt(np.maximum(n, 1))), np.nan)
    return dict(n=n, net=net, win=win / np.maximum(n, 1), ys_ok=ys_ok, ys_tot=ys_tot, n_ly=n_ly, net_ly=net_ly,
                mean=mean, t=tstat, n_halves=len(halves))


def daily_series(meta, rows, pnl, off, ecash, mc, ctx_dates) -> pd.Series:
    """Mark-to-market P&L per session of a set of trades (one exit column): Series date -> Rs."""
    acc: dict = {}
    for r, p, o in zip(rows, pnl, off):
        i0 = int(meta.at[r, "i"])
        und = meta.at[r, "und"]
        dl = ctx_dates[und]
        prev = 0.0
        for k in range(int(o)):
            v = (ecash[r] if k == 0 else 0.0) + mc[r, k] - prev
            prev = mc[r, k]
            d = dl[i0 + k]
            acc[d] = acc.get(d, 0.0) + v
        d = dl[i0 + int(o)]
        exit_cash = p - ecash[r]
        acc[d] = acc.get(d, 0.0) + (ecash[r] if o == 0 else 0.0) + exit_cash - (prev if o > 0 else 0.0)
    return pd.Series(acc).sort_index()


def full_stats(pnl: np.ndarray, dates: list, daily: pd.Series) -> dict:
    pnl = np.asarray(pnl, dtype=np.float64)
    n = len(pnl)
    w = pnl[pnl > 0]
    l = pnl[pnl <= 0]
    eq = daily.cumsum()
    dd = float((eq - np.maximum.accumulate(np.r_[0.0, eq.to_numpy()])[1:]).min()) if len(eq) else 0.0
    di = pd.to_datetime(pd.Series(daily.index))
    wk = daily.groupby(di.dt.to_period("W").to_numpy()).sum() if len(daily) else daily
    mo = daily.groupby(di.dt.to_period("M").to_numpy()).sum() if len(daily) else daily
    top5 = float(np.sort(pnl)[-5:].sum()) if n >= 5 else float(pnl.sum())
    yrs = pd.Series(pnl).groupby([d.year for d in dates]).agg(["count", "sum", "mean"])
    return dict(n=n, win=float((pnl > 0).mean()) if n else np.nan, net=float(pnl.sum()), mean=float(pnl.mean()) if n else np.nan,
                avg_win=float(w.mean()) if len(w) else 0.0, avg_loss=float(l.mean()) if len(l) else 0.0,
                worst_trade=float(pnl.min()) if n else 0.0, best_trade=float(pnl.max()) if n else 0.0,
                worst_day=float(daily.min()) if len(daily) else 0.0, worst_week=float(wk.min()) if len(wk) else 0.0,
                worst_month=float(mo.min()) if len(mo) else 0.0, max_dd=dd, net_minus_top5=float(pnl.sum() - top5),
                pf=float(w.sum() / -l.sum()) if len(l) and l.sum() < 0 else float("inf"),
                years={int(y): dict(n=int(r["count"]), net=round(float(r["sum"])), mean=round(float(r["mean"]), 1))
                       for y, r in yrs.iterrows()})


def cfg_key(c: dict) -> str:
    return json.dumps(c, sort_keys=True, default=str)


def family_of(c: dict) -> str:
    return {"BPS": "spread", "BCS": "spread", "DIR": "spread", "IC": "condor", "IB": "fly", "BUY": "buy"}[c["kind"]]


def entry_id(c: dict) -> tuple:
    return (c["und"], c["kind"], c.get("m"), c.get("w"), c.get("itm"), c["dte"], c["time"], c["sig"], c["dir"])


def priced_share(meta: pd.DataFrame, skipdf: pd.DataFrame, base: dict, fam: str, lo, hi, dates: list) -> float:
    """Amendment 2: share of the config's sessions (its DTE and decision time, dates in [lo, hi], before filters) on
    which its structure could be priced. Direction rules count both BPS and BCS; buys both sides."""
    if fam == "sell":
        kinds = ["BPS", "BCS"] if base["kind"] == "DIR" else [base["kind"]]
        mk = meta.kind.isin(kinds) & np.isclose(meta.m, base["m"]) & (meta.w == base["w"])
        sk = skipdf.kind.isin(kinds) & np.isclose(skipdf.m, base["m"]) & (skipdf.w == base["w"])
    else:
        mk = meta.itm == base["itm"]
        sk = (skipdf.kind == "BUY") & (skipdf.w == base["itm"])
    if base["dte"] == "any":
        mk = mk & (meta.time == base["time"])
        sk = sk & (skipdf.time == base["time"])
    else:
        mk = mk & (meta.dte == base["dte"]) & (meta.time == base["time"])
        sk = sk & (skipdf.dte == base["dte"]) & (skipdf.time == base["time"])
    md = meta.date[mk]
    sd = pd.Series([dates[i] for i in skipdf.i[sk]], dtype=object)
    if lo is not None:
        md, sd = md[md >= lo], sd[sd >= lo]
    if hi is not None:
        md, sd = md[md <= hi], sd[sd <= hi]
    tot = len(md) + len(sd)
    return len(md) / tot if tot else 0.0


def scan(parts_by_fam: dict, date_lo=K.DESIGN_START, date_hi=K.DESIGN_END, min_trades=K.MIN_TRADES,
         ly=(K.LAST_YEAR, K.DESIGN_END), vix_med=None, keep_all=False, cd=None, halves=None, ly_min=K.LAST_YEAR_MIN_N):
    """Every config's quick stats on design rows with date_lo <= date <= date_hi. Returns (eligible rows list, count of
    configs, summary table of all configs' quick stats when keep_all)."""
    elig, allrows, count = [], [], 0
    for fam, parts in parts_by_fam.items():
        cols = SELL_COLS if fam == "sell" else BUY_COLS
        for meta, P, O, EC, MC, sk in parts:
            if len(meta) == 0:
                continue
            und = meta.und.iloc[0]
            cd = all_ctx_dates() if cd is None else cd
            ps_memo: dict = {}
            for base, rows in config_groups(meta, fam):
                if len(rows) == 0:
                    continue
                pk = (base["kind"] == "DIR", base.get("kind"), base.get("m"), base.get("w"), base.get("itm"),
                      base["dte"], base["time"])
                if pk not in ps_memo:
                    ps_memo[pk] = priced_share(meta, sk["rows"], base, fam, date_lo, date_hi, cd[und])
                pshare = ps_memo[pk]
                d = meta.date.to_numpy()[rows]
                sel = np.ones(len(rows), dtype=bool)
                if date_lo is not None:
                    sel &= d >= date_lo
                if date_hi is not None:
                    sel &= d <= date_hi
                rows_s = rows[sel]
                if len(rows_s) < 1:
                    continue
                dates = meta.date.to_numpy()[rows_s]
                for vix in K.VIX:
                    for ev in K.EVENTS:
                        V = valid_matrix(meta, rows_s, cols, fam, vix, ev, vix_med)
                        Pm = P[rows_s]
                        st = _quick_with_ly(Pm, V, dates, ly, halves)
                        count += int((st["n"] > 0).sum())
                        ok = ((st["n"] >= min_trades) & (st["win"] >= K.MIN_WIN) & (st["net"] > 0)
                              & (st["ys_ok"] == st["n_halves"])
                              & (st["n_ly"] >= ly_min) & (st["net_ly"] > 0) & (pshare >= K.MIN_PRICED))
                        near = keep_all & (st["n"] >= K.MIN_TRADES) & ((st["win"] >= 0.60) | (st["t"] >= 2.0))
                        for c in np.flatnonzero(ok | near):
                            cfg = dict(base, und=und, fam=fam, vix=vix, ev=ev,
                                       exit=list(cols[c]) if fam == "sell" else list(cols[c]))
                            rec = dict(cfg=cfg, col=int(c), rows=rows_s[V[:, c] & np.isfinite(Pm[:, c])],
                                       n=int(st["n"][c]), win=float(st["win"][c]), net=float(st["net"][c]),
                                       t=float(st["t"][c]), mean=float(st["mean"][c]), n_ly=int(st["n_ly"][c]),
                                       net_ly=float(st["net_ly"][c]), quick_ok=bool(ok[c]), priced=pshare)
                            if ok[c]:
                                elig.append(rec)
                            if keep_all and near[c]:
                                allrows.append({k: v for k, v in rec.items() if k != "rows"})
    return elig, count, allrows


def _quick_with_ly(P, V, dates, ly, halves=None):
    st = quick_stats(P, V, dates, halves)
    if ly != (K.LAST_YEAR, K.DESIGN_END):
        V2 = V & np.isfinite(P)
        X0 = np.where(V2, P, 0.0)
        m = np.array([ly[0] <= d <= ly[1] for d in dates])
        st["n_ly"] = V2[m].sum(0)
        st["net_ly"] = X0[m].sum(0)
    return st


def parts_index(parts_by_fam):
    """(fam, und) -> (meta, P, O, EC, MC)."""
    out = {}
    for fam, parts in parts_by_fam.items():
        for meta, P, O, EC, MC, _ in parts:
            if len(meta):
                out[(fam, meta.und.iloc[0])] = (meta, P, O, EC, MC)
    return out


def finish(rec, idx, ctx_dates) -> dict:
    """Full stats (worst day, DD, top 5) of an eligible config."""
    c = rec["cfg"]
    meta, P, O, EC, MC = idx[(c["fam"], c["und"])]
    rows = rec["rows"]
    pnl = P[rows, rec["col"]].astype(np.float64)
    off = O[rows, rec["col"]]
    daily = daily_series(meta, rows, pnl, off, EC, MC, ctx_dates)
    fs = full_stats(pnl, list(meta.date.to_numpy()[rows]), daily)
    extra = {}
    if c["fam"] == "sell":
        mm = meta.loc[rows]
        hold = c["exit"][3]
        extra = dict(margin_max=float((mm.margin_exp if hold == "exp" else mm.margin_day).max()),
                     maxloss_max=float(mm.maxloss.max()), credit_med=float(mm.credit.median()))
    else:
        extra = dict(margin_max=float(meta.loc[rows].margin_day.max()))
    return dict(fs, **extra)


def passes_full(fs) -> bool:
    return fs["worst_day"] >= K.WORST_DAY and fs["max_dd"] >= K.MAX_DD and fs["net_minus_top5"] > 0


def pick(cands: list) -> list:
    """PREREG ranking: by t; skip a candidate whose (index, entry) is taken, or whose (index, family) is taken twice."""
    out = []
    for r in sorted(cands, key=lambda r: -r["t"]):
        c = r["cfg"]
        if any(entry_id(o["cfg"]) == entry_id(c) for o in out):
            continue
        if sum(1 for o in out if o["cfg"]["und"] == c["und"] and family_of(o["cfg"]) == family_of(c)) >= 2:
            continue
        out.append(r)
        if len(out) == K.N_CAND:
            break
    return out


def vix_median() -> float:
    d = D.daily_index()
    v = d[(d.symbol == "INDIAVIX") & (d.date <= K.DESIGN_END)]
    return float(v.close.median())


def all_ctx_dates():
    return {u: [d.day for d in D.load(u)] for u in K.UNDS}


def jdump(path, obj):
    path.write_text(json.dumps(obj, indent=1, default=lambda x: x.isoformat() if hasattr(x, "isoformat") else
                               (float(x) if isinstance(x, (np.floating,)) else (int(x) if isinstance(x, np.integer) else str(x)))))


def cmd_design():
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    pb = {"buy": load_family("buy")}          # amendment 4: buying only
    vmed = vix_median()
    cd = all_ctx_dates()
    elig, count, allrows = scan(pb, vix_med=vmed, keep_all=True, cd=cd)
    idx = parts_index(pb)
    full = []
    for r in elig:
        fs = finish(r, idx, cd)
        r2 = {k: v for k, v in r.items() if k != "rows"}
        r2["full"] = fs
        r2["passes"] = passes_full(fs)
        full.append(r2)
    passing = [r for r in full if r["passes"]]
    cands = pick(passing)
    skips = {f"{fam}:{p[0].und.iloc[0] if len(p[0]) else '?'}": {k: v for k, v in p[5].items() if k != "rows"}
             for fam, ps in pb.items() for p in ps}
    # closest misses: the best by t among configs with win >= 65% that miss a gate, and the best net among win >= 65%
    tab = pd.DataFrame(allrows)
    tab.to_pickle(D.CACHE / "design_all.pkl")
    res = dict(n_configs=count, n_quick_eligible=len(elig), n_eligible=len(passing), vix_median=vmed, skips=skips,
               candidates=[dict(cfg=r["cfg"], **{k: r[k] for k in ("n", "win", "net", "t", "mean", "n_ly", "net_ly", "priced")},
                                full=r["full"]) for r in cands],
               eligible=[dict(cfg=r["cfg"], **{k: r[k] for k in ("n", "win", "net", "t", "mean")}, full=r["full"],
                              passes=r["passes"]) for r in sorted(full, key=lambda r: -r["t"])[:200]])
    res["walk_forward"] = walk_forward(pb, vmed, idx, cd)
    jdump(K.RESULTS / "design.json", res)
    print(f"configs {count:,}; quick-eligible {len(elig)}; fully eligible {len(passing)}; candidates {len(cands)}")
    for r in cands:
        f = r["full"]
        print(r["cfg"], f"n {r['n']} win {r['win']:.1%} net {r['net']:,.0f} t {r['t']:.2f} wd {f['worst_day']:,.0f} dd {f['max_dd']:,.0f}")


# ------------------------------------------------------------------------------------------------ config replay
def config_rows(cfg: dict, meta: pd.DataFrame, P: np.ndarray, vix_med: float):
    """Rows (all dates of `meta`) and P&L of one config: the group's rows, valid for its exit column and filters."""
    fam = cfg["fam"]
    cols = SELL_COLS if fam == "sell" else BUY_COLS
    col = [tuple(c) for c in cols].index(tuple(cfg["exit"]))
    base = {k: cfg[k] for k in (("kind", "m", "w", "dte", "time", "sig", "dir") if fam == "sell"
                                else ("kind", "itm", "dte", "time", "sig", "dir"))}
    for b, rows in config_groups(meta, fam):
        if all(_eq(b[k], v) for k, v in base.items()):
            if len(rows) == 0:
                return rows, col
            V = valid_matrix(meta, rows, cols, fam, cfg["vix"], cfg["ev"], vix_med)[:, col] & np.isfinite(P[rows, col])
            return rows[V], col
    return np.array([], dtype=int), col


def _eq(a, b):
    try:
        return abs(float(a) - float(b)) < 1e-9
    except (TypeError, ValueError):
        return a == b


def walk_forward(pb, vmed, idx, cd) -> list:
    """Amendment 3 report: the rule on 1 Nov 2024 - 30 Apr 2025 (20+ trades, positive there), top 10, their result on
    1 May - 17 Oct 2025."""
    lo1, hi1 = K.DESIGN_START, K.H1_END
    lo2, hi2 = date.fromordinal(K.H1_END.toordinal() + 1), K.DESIGN_END
    elig, _, _ = scan(pb, date_lo=lo1, date_hi=hi1, min_trades=20, ly=(lo1, hi1), vix_med=vmed, cd=cd,
                      halves=[(lo1, hi1)], ly_min=20)
    good = [r for r in elig if passes_full(finish(r, idx, cd))]
    top = pick(good)
    pn, per = [], []
    for r in top:
        c = r["cfg"]
        meta, P, O, EC, MC = idx[(c["fam"], c["und"])]
        rows, col = config_rows(c, meta, P, vmed)
        d = meta.date.to_numpy()[rows]
        m = (d >= lo2) & (d <= hi2)
        x = P[rows[m], col].astype(float)
        pn += list(x)
        per.append(dict(cfg=c, n=int(m.sum()), net=float(x.sum()), win=float((x > 0).mean()) if len(x) else None,
                        pick_win=r["win"], pick_n=r["n"], pick_net=r["net"]))
    pn = np.array(pn)
    out = [dict(pick=f"{lo1} - {hi1}", test=f"{lo2} - {hi2}", n_eligible=len(good), picked=len(top),
                trades=int(len(pn)), net=float(pn.sum()) if len(pn) else 0.0,
                win=float((pn > 0).mean()) if len(pn) else None, per=per)]
    print(f"WF pick {lo1}..{hi1}, test {lo2}..{hi2}: eligible {len(good)}, picked {len(top)}, test trades {len(pn)}, "
          f"net {pn.sum() if len(pn) else 0:,.0f}, win {(pn > 0).mean() if len(pn) else float('nan'):.1%}", flush=True)
    return out


# ------------------------------------------------------------------------------------------------ holdout (once)
TWIN_LO, TWIN_HI = D.mi(9, 20), D.mi(14, 30)


def holdout_trades(cfg: dict, ctx: Ctx, vix_med: float, tick_mult: float = 1.0):
    """The candidate's holdout trades: (pnl array, dates, daily Series)."""
    fam = cfg["fam"]
    sel = same_dte_days(ctx, cfg)
    t = D.mi(*(K.TIMES if fam == "sell" else K.BUY_TIMES)[cfg["time"]])
    metas, P, O, EC, MC = [], [], [], [], []
    for i in sel:
        if fam == "sell":
            kinds = ["BPS", "BCS"] if cfg["kind"] == "DIR" else [cfg["kind"]]
            for k in kinds:
                r = sell_record(ctx, i, t, k, cfg["m"], cfg["w"], tick_mult=tick_mult)
                if isinstance(r, str):
                    continue
                r[0]["time"] = cfg["time"]
                metas.append(r[0]); P.append(r[1]); O.append(r[2]); EC.append(r[3]); MC.append(r[4])
        else:
            for side in (0, 1):
                r = buy_record(ctx, i, t, side, cfg["itm"], tick_mult=tick_mult)
                if isinstance(r, str):
                    continue
                r[0]["time"] = cfg["time"]
                metas.append(r[0]); P.append(r[1]); O.append(r[2]); EC.append(r[3]); MC.append(r[4])
    if not metas:
        return np.array([]), [], pd.Series(dtype=float)
    meta = pd.DataFrame(metas)
    meta["und"] = ctx.und
    P, O, EC, MC = np.array(P, np.float32), np.array(O, np.int8), np.array(EC), np.array(MC)
    rows, col = config_rows(cfg, meta, P, vix_med)
    pnl = P[rows, col].astype(float)
    daily = daily_series(meta, rows, pnl, O[rows, col], EC, MC, {ctx.und: ctx.dates})
    return pnl, list(meta.date.to_numpy()[rows]), daily


def same_dte_days(ctx: Ctx, cfg: dict) -> list[int]:
    """Holdout sessions with the config's DTE ('any': every holdout session with a known expiry)."""
    if cfg["dte"] == "any":
        return entry_days(ctx, "holdout", None)
    return [i for i in entry_days(ctx, "holdout") if ctx.dte[i] == cfg["dte"]]


def twin_pool(cfg: dict, ctx: Ctx, rng) -> dict:
    """P&L of the candidate's structure / strike rule / exit at EVERY decision minute 09:20-14:30 of every holdout
    session with its DTE, both sides for direction rules; no filters. -> {(day, minute, side): pnl} (valid only)."""
    fam = cfg["fam"]
    cols = SELL_COLS if fam == "sell" else BUY_COLS
    col = [tuple(c) for c in cols].index(tuple(cfg["exit"]))
    hold = cfg["exit"][3] if fam == "sell" else "day"
    pool = {}
    sel = same_dte_days(ctx, cfg)
    for i in sel:
        for t in range(TWIN_LO, TWIN_HI + 1):
            if fam == "sell":
                kinds = ["BPS", "BCS"] if cfg["kind"] == "DIR" else [cfg["kind"]]
                for side, k in enumerate(kinds):
                    r = sell_record(ctx, i, t, k, cfg["m"], cfg["w"], cols=[cols[col]])
                    if isinstance(r, str) or not np.isfinite(r[1][0]):
                        continue
                    mt = r[0]
                    okm = (mt["margin_exp"] if hold == "exp" else mt["margin_day"]) <= K.ACCOUNT
                    if okm and not (hold == "exp" and mt["guard_bad"]):
                        pool[(i, t, side)] = float(r[1][0])
            else:
                for side in (0, 1):
                    r = buy_record(ctx, i, t, side, cfg["itm"], cols=[cols[col]])
                    if isinstance(r, str) or not np.isfinite(r[1][0]):
                        continue
                    pool[(i, t, side)] = float(r[1][0])
        ctx.cache.memo.clear()
    return pool


def twins(cfg: dict, pool: dict, n_trades: int, rng, n_twins=K.N_TWINS) -> np.ndarray:
    """Net of each random-entry twin: n_trades draws of (random session with the DTE, random minute, coin-flip side
    for direction rules); an empty (day, minute, side) is redrawn."""
    keys = list(pool)
    if not keys:
        return np.array([])
    by_day: dict = {}
    for k in keys:
        by_day.setdefault(k[0], []).append(k)
    days_ = sorted(by_day)
    vals = np.array([pool[k] for k in keys])
    pos = {k: n for n, k in enumerate(keys)}
    directional = cfg["kind"] in ("DIR", "BUY")
    out = np.empty(n_twins)
    for z in range(n_twins):
        tot = 0.0
        for _ in range(n_trades):
            while True:
                d = days_[rng.integers(len(days_))]
                t = int(rng.integers(TWIN_LO, TWIN_HI + 1))
                side = int(rng.integers(2)) if directional else 0
                k = (d, t, side)
                if k in pos:
                    tot += vals[pos[k]]
                    break
        out[z] = tot
    return out


def bh(p: list) -> list:
    """Benjamini-Hochberg q-values."""
    p = np.asarray(p, dtype=float)
    n = len(p)
    if n == 0:
        return []
    o = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for r in range(n - 1, -1, -1):
        prev = min(prev, p[o[r]] * n / (r + 1))
        q[o[r]] = prev
    return list(q)


def gates(h: dict) -> dict:
    return dict(win=h["win"] >= K.H_WIN, net=h["net"] > 0, n=h["n"] >= K.H_MIN_N, worst_day=h["worst_day"] >= K.WORST_DAY,
                max_dd=h["max_dd"] >= K.MAX_DD, top5=h["net_minus_top5"] > 0, p=h["p_twin"] < K.H_P,
                q=h["q"] <= K.H_Q)


def cmd_holdout():
    out_p = K.RESULTS / "holdout.json"
    if out_p.exists():
        raise SystemExit("refused: the locked holdout was already read (results/holdout.json exists)")
    des = json.loads((K.RESULTS / "design.json").read_text())
    cands = des["candidates"]
    if not cands:
        raise SystemExit("no candidate passed the design rule: the holdout is NOT read")
    vmed = des["vix_median"]
    rng = np.random.default_rng(K.SEED)
    res = []
    ctxs = {}
    for c in cands:
        cfg = c["cfg"]
        ctx = ctxs.get(cfg["und"]) or context(cfg["und"])
        ctxs[cfg["und"]] = ctx
        pnl, dates, daily = holdout_trades(cfg, ctx, vmed)
        fs = full_stats(pnl, dates, daily) if len(pnl) else dict(n=0, win=0.0, net=0.0, worst_day=0.0, max_dd=0.0,
                                                                  net_minus_top5=0.0)
        pool = twin_pool(cfg, ctx, rng) if len(pnl) else {}
        tw = twins(cfg, pool, len(pnl), rng) if len(pnl) else np.array([])
        p_twin = (1 + int((tw >= fs["net"]).sum())) / (len(tw) + 1) if len(tw) else 1.0
        boot = rng.choice(pnl, size=(K.N_BOOT, len(pnl)), replace=True).mean(1) if len(pnl) else np.array([0.0])
        p_zero = float((boot <= 0).mean())
        # split at the Data Brain holdout start
        d = np.array(dates)
        split = {}
        for name, m in (("3 Nov 2025 - 26 Apr 2026", d < K.BRAIN_HOLDOUT), ("27 Apr 2026 -", d >= K.BRAIN_HOLDOUT)):
            x = pnl[m] if len(pnl) else np.array([])
            split[name] = dict(n=int(len(x)), net=float(x.sum()), win=float((x > 0).mean()) if len(x) else None)
        pnl2, dates2, daily2 = holdout_trades(cfg, ctx, vmed, tick_mult=2.0)
        fs2 = full_stats(pnl2, dates2, daily2) if len(pnl2) else {}
        res.append(dict(cfg=cfg, design={k: c[k] for k in ("n", "win", "net", "t", "mean")}, design_full=c["full"],
                        holdout=fs, p_twin=p_twin, p_zero=p_zero, twin_mean=float(tw.mean()) if len(tw) else None,
                        twin_pool=len(pool), split=split, doubled_ticks=fs2,
                        daily_worst5=[(str(k), round(v)) for k, v in daily.sort_values().head(5).items()]))
        print(cfg, f"holdout n {fs['n']} win {fs.get('win', 0):.1%} net {fs['net']:,.0f} p_twin {p_twin:.3f}", flush=True)
    qs = bh([r["p_twin"] for r in res])
    for r, q in zip(res, qs):
        r["holdout"]["p_twin"], r["holdout"]["q"] = r["p_twin"], q
        r["q"] = q
        r["gates"] = gates(r["holdout"]) if r["holdout"]["n"] else {"n": False}
        r["passed"] = all(r["gates"].values())
    jdump(out_p, dict(read_on=str(date.today()), candidates=res))
    print("passed:", sum(r["passed"] for r in res), "of", len(res))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "enum":
        cmd_enum(sys.argv[2], sys.argv[3])
    elif cmd == "design":
        cmd_design()
    elif cmd == "holdout":
        cmd_holdout()
    else:
        raise SystemExit(f"unknown command {cmd}")
