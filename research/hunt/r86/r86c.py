"""R86c runner: five out-of-the-box ways to catch the big players + the seller catalogue / detector (amendment C-A1).
PREREG: research/hunt/r86/PREREG_C.md.

    python -m research.hunt.r86.r86c design     # 12 tests on design + catalogue -> results/c_design.json
    python -m research.hunt.r86.r86c final      # ONCE, only design passers (refuses when none passed)
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r86 import r86b as RB
from research.hunt.r86 import r86blib as B
from research.hunt.r86 import r86data as D
from research.hunt.r86 import r86lib as L
from research.hunt.r86.r86study import MinuteBook, one_at_a_time, summ
from research.obuy.config import LOT_SCHEDULE
from research.obuy.costs import Costs

OUT = Path(__file__).resolve().parent / "results"
SEED = 862
NTWIN = int(os.environ.get("R86_NTWIN", "500"))
FOLDS = [2022, 2023, 2024]
DESIGN_END = pd.Timestamp("2024-12-31")
OPT_COSTS = Costs("dated")


def rng():
    return np.random.default_rng(SEED)


def fold_summary(nets, days, twins, folds):
    nets = np.asarray(nets, float)
    s = summ(nets)
    yrs = pd.DatetimeIndex(days).year if len(days) else np.array([])
    s.update(twin_mean=round(float(np.mean(twins)), 0), twin_p=L.twin_p(float(nets.sum()), twins),
             by_year={str(y): round(float(nets[yrs == y].sum()), 0) for y in folds},
             n_by_year={str(y): int((yrs == y).sum()) for y in folds})
    s["p"] = s["twin_p"]
    s["pass_design"] = bool(s["n"] > 0 and s["net"] > 0 and s["net"] > s["twin_mean"] and s["net_wo_top5"] > 0)
    return s


def last1515(bk, k):
    return bk.start[k] + int(np.where(bk.mod[bk.day_slice(k)] <= 915)[0][-1])


def fut_trade(bk, und, e, last, side, stop):
    """Futures-equivalent minute trade(s) with R86's cost model (vectorised)."""
    e, last, side = np.atleast_1d(e), np.atleast_1d(last), np.atleast_1d(side)
    rows = RB.trade_rows(bk, und, e, last, side, np.atleast_1d(stop), np.full(len(e), np.nan),
                         bk.m.day.to_numpy()[e])
    return rows


def day_twins_minute(bk, und, pool_k, n_by_year, side, stop_pct, mod_lo, mod_hi, exit_fn):
    """Random sessions (same count per fold year) and a random entry minute in [mod_lo, mod_hi]."""
    g = rng()
    out = np.zeros(NTWIN)
    yrs = bk.days.year.to_numpy()
    pools = {y: [k for k in pool_k if yrs[k] == y] for y in n_by_year}
    for w in range(NTWIN):
        ks, es = [], []
        for y, n in n_by_year.items():
            if n == 0 or not pools[y]:
                continue
            for k in g.choice(pools[y], size=min(n, len(pools[y])), replace=False):
                sl = bk.day_slice(k)
                c = np.where((bk.mod[sl] >= mod_lo) & (bk.mod[sl] <= mod_hi))[0]
                if len(c) == 0:
                    continue
                ks.append(k)
                es.append(sl.start + int(g.choice(c)))
        if not es:
            continue
        e = np.array(es)
        sp = g.permutation(np.resize(stop_pct, len(e)))
        sd = np.resize(side, len(e))
        last = np.array([exit_fn(k, ee) for k, ee in zip(ks, e)])
        ent = bk.o[e]
        out[w] = fut_trade(bk, und, e, last, sd, ent * (1 - sd * sp)).net.sum()
    return out


# ================================================================================ C1 buy the VWAP dip on up-steady days


def vwap_series(bk, k):
    sl = bk.day_slice(k)
    typ = (bk.h[sl] + bk.l[sl] + bk.c[sl]) / 3
    return L.vwap(typ, bk.tv[sl])


def c1(bk, d, und, mirror=False):
    d = d.dropna(subset=["m_move", "m_er"]).copy()
    d["er_q80"] = B.prev_quantile(d.m_er, 0.8)
    d["mv_q50"] = B.prev_quantile(d.m_move.abs(), 0.5)
    d["tv_med"] = d.m_tv.shift(1).rolling(20, min_periods=10).median()
    want = -1 if mirror else 1
    q = (d.m_er >= d.er_q80) & (d.m_move.abs() >= d.mv_q50) & (d.m_tv >= d.tv_med) & (np.sign(d.m_move) == want)
    pool = d[d.day.dt.year.isin(FOLDS) & d.er_q80.notna()]
    sel = pool[q.loc[pool.index]]
    es, ks, sts = [], [], []
    for _, r in sel.iterrows():
        k = int(r.k)
        sl = bk.day_slice(k)
        mod = bk.mod[sl]
        vw = vwap_series(bk, k)
        cand = np.where((mod >= 690) & (mod <= 870) & ((bk.l[sl] <= vw) if want > 0 else (bk.h[sl] >= vw)))[0]
        if len(cand) == 0:
            continue
        e = sl.start + int(cand[0]) + 1
        if bk.mod[e] > 915:
            continue
        es.append(e); ks.append(k); sts.append(bk.o[e] - want * 0.5 * r.m_rng)
    if not es:
        return {"n": 0, "p": 1.0, "pass_design": False, "qualifying_days": int(len(sel))}
    e = np.array(es)
    last = np.array([last1515(bk, k) for k in ks])
    tr = fut_trade(bk, und, e, last, np.full(len(e), want), np.array(sts))
    stop_pct = np.abs(bk.o[e] - np.array(sts)) / bk.o[e]
    nby = {y: int((bk.days[ks].year == y).sum()) for y in FOLDS}
    tw = day_twins_minute(bk, und, list(pool.k.astype(int)), nby, want, stop_pct, 691, 871, lambda k, ee: last1515(bk, k))
    s = fold_summary(tr.net, tr.day, tw, FOLDS)
    s["qualifying_days"], s["exit_kinds"] = int(len(sel)), tr.kind.value_counts().to_dict()
    return s


# ================================================================================ C2 fade the month-end close


def month_end_flags(days: pd.Series) -> pd.Series:
    """True on the last trading session of each month (the next session is in another month; holidays allowed)."""
    days = pd.Series(pd.to_datetime(days)).reset_index(drop=True)
    return days.dt.month != days.shift(-1).dt.month


def c2(und):
    m = D.minutes(und)
    bk = MinuteBook(m[m.day >= pd.Timestamp("2017-01-01")])
    rows = []
    for k in range(len(bk.days)):
        sl = bk.day_slice(k)
        mod = bk.mod[sl]
        if not RB.good_day(mod):
            continue
        i900, i924, i925, i930 = (RB.idx(mod, t) for t in (900, 924, 925, 570))
        rows.append(dict(k=k, day=bk.days[k], o=bk.o[sl][0], h=bk.h[sl].max(), l=bk.l[sl].min(), c=bk.c[sl][-1],
                         mv=(bk.c[sl][i924] / bk.o[sl][i900] - 1) if None not in (i900, i924) else np.nan,
                         e1525=sl.start + i925 if i925 is not None else -1, i930=i930))
    d = pd.DataFrame(rows)
    d["atr_prev"] = pd.Series(L.wilder_atr(d.h.to_numpy(), d.l.to_numpy(), d.c.to_numpy(), 14)).shift(1).to_numpy()
    d["next_k"] = d.k.shift(-1)
    d["month_end"] = month_end_flags(d.day).to_numpy()
    d = d.dropna(subset=["mv", "atr_prev", "next_k"])
    d = d[(d.e1525 > 0) & (d.day.dt.year >= 2018) & (d.day <= DESIGN_END)]
    years = sorted(d.day.dt.year.unique())

    def make(rows_):
        out = []
        for _, r in rows_.iterrows():
            nk = int(r.next_k)
            nsl = bk.day_slice(nk)
            i930 = RB.idx(bk.mod[nsl], 570)
            if i930 is None or r.mv == 0:
                continue
            s = -int(np.sign(r.mv))
            ent = bk.o[int(r.e1525)]
            px, st = B.sim_overnight(ent, s, float(r.atr_prev), bk.o[nsl][0], bk.l[nsl][:i930 + 1], bk.h[nsl][:i930 + 1],
                                     bk.c[nsl][i930])
            units = L.lot_on(LOT_SCHEDULE[und], r.day)
            cost = L.nse_fut_cost(s, ent, px, units, r.day, (bk.days[nk] - r.day).days, st)
            out.append(dict(day=r.day, net=s * (px - ent) * units - cost, stopped=st))
        return pd.DataFrame(out)
    me = d[d.month_end]
    tr = make(me)
    g = rng()
    others = d[~d.month_end]
    tw = np.zeros(NTWIN)
    for w in range(NTWIN):
        parts = []
        for y in years:
            n = int((me.day.dt.year == y).sum())
            po = others[others.day.dt.year == y]
            if n and len(po):
                parts.append(make(po.iloc[g.choice(len(po), min(n, len(po)), replace=False)]))
        tw[w] = float(sum(p.net.sum() for p in parts if len(p)))
    s = fold_summary(tr.net, tr.day, tw, years)
    s["month_ends"] = int(len(me))
    s["fade_hit_rate"] = round(float((tr.net > 0).mean()), 3) if len(tr) else None
    return s


# ================================================================================ C3 straddle on predicted busy afternoons


def opt_day_tables(und):
    """{day: DataFrame of that day's option minutes (mod, offset, side, strike, open, close, volume, oi)}."""
    out = {}
    for f in sorted((D.LAKE / "options_rolling_1m").glob(f"opt_{und}_WEEK_*.parquet")):
        o = pd.read_parquet(f, columns=["ts", "offset", "side", "strike", "open", "close", "volume", "oi"])
        ts = pd.to_datetime(o.ts)
        o["day"] = ts.dt.tz_localize(None).dt.normalize()
        o["mod"] = ts.dt.hour * 60 + ts.dt.minute
        for day, x in o.groupby("day"):
            out[day] = x.drop(columns=["ts", "day"])
    return out


def straddle_pnl(ox: pd.DataFrame, und, day):
    """Buy the call + put of the 11:29 ATM strike at the 11:30 open; sell the SAME two contracts at the 15:15 close."""
    atm = ox[(ox["mod"] == 689) & (ox.offset == 0)]
    if len(atm) == 0:
        return None
    K = float(atm.strike.iloc[0])
    legs = []
    for sd in ("C", "P"):
        x = ox[(ox.strike == K) & (ox.side == sd)]
        ent = x[x["mod"] == 690]
        ex = x[(x["mod"] <= 915) & (x["mod"] >= 690)]
        if len(ent) == 0 or len(ex) == 0:
            return None
        legs.append((float(ent.open.iloc[0]), float(ex.sort_values("mod").close.iloc[-1])))
    units = L.lot_on(LOT_SCHEDULE[und], day)
    dn = (day.date() - date(1970, 1, 1)).days
    net = 0.0
    for e, x in legs:
        if not (e > 0):
            return None
        eb = e * 1.0005 + 0.05
        xs = max(x * 0.9995 - 0.05, 0.0)
        ch = float(OPT_COSTS.charge(True, eb, units, dn)) + float(OPT_COSTS.charge(False, xs, units, dn))
        net += (xs - eb) * units - ch
    prem = sum(e for e, _ in legs)
    return dict(net=net, prem_pct=prem / K, K=K)


def c3_rows(bk, d, und, gi, sg, day_of):
    vix = D.minutes("INDIAVIX")
    v1129 = vix[vix["mod"] == 689].set_index("day").close
    gx = D.gex(und)
    dte = gx[gx.minute == 44].set_index("day").dte
    nb = {}
    for i, s in zip(gi, sg):
        if 570 <= bk.mod[i] <= 689:
            nb[int(day_of[i])] = nb.get(int(day_of[i]), 0) + 1
    rows = []
    for _, r in d.iterrows():
        k = int(r.k)
        sl = bk.day_slice(k)
        mod = bk.mod[sl]
        i1129, i1130, i1515 = RB.idx(mod, 689), RB.idx(mod, 690), np.where(mod <= 915)[0][-1]
        if i1129 is None or i1130 is None:
            continue
        early = bk.tv[sl][(mod >= 555) & (mod <= 614)].sum()
        aft = (bk.h[sl][i1130:i1515 + 1].max() - bk.l[sl][i1130:i1515 + 1].min()) / bk.c[sl][i1129]
        rows.append(dict(k=k, day=r.day, aft=aft, mrng=(bk.h[sl][:i1129 + 1].max() - bk.l[sl][:i1129 + 1].min())
                         / bk.c[sl][i1129], early=early, bursts=nb.get(k, 0), gap=abs(r.open / r.prev_close - 1)
                         if np.isfinite(r.prev_close) else np.nan, vix=v1129.get(r.day, np.nan), dte=dte.get(r.day, np.nan)))
    x = pd.DataFrame(rows)
    x["prev_aft"] = x.aft.shift(1).rolling(20, min_periods=10).mean()
    x["evr"] = x.early / x.early.shift(1).rolling(20, min_periods=15).median()
    for c in ("aft", "mrng", "prev_aft", "vix", "evr"):
        x["l" + c] = np.log(x[c].where(x[c] > 0))
    return x.replace([np.inf, -np.inf], np.nan)


C3_X = ["lvix", "lmrng", "lprev_aft", "levr", "bursts", "gap"]
C3_BASE = ["lvix", "lmrng", "lprev_aft"]


def c3(bk, d, und, gi, sg, day_of, opts):
    x = c3_rows(bk, d, und, gi, sg, day_of).dropna(subset=["laft"] + C3_X)
    x = x[x.day <= DESIGN_END]
    pnl = {}
    for day in x.day:
        if day in opts and x.loc[x.day == day, "dte"].iloc[0] != 0 and np.isfinite(x.loc[x.day == day, "dte"].iloc[0]):
            p = straddle_pnl(opts[day], und, day)
            if p is not None:
                pnl[day] = p
    trades, errs = [], {"base": [], "full": []}
    for y in FOLDS:
        tr_ = x[x.day.dt.year < y]
        te = x[x.day.dt.year == y]
        if len(tr_) < 100 or len(te) == 0:
            continue
        b = L.ols_fit(tr_[C3_X].to_numpy(), tr_.laft.to_numpy())
        bb = L.ols_fit(tr_[C3_BASE].to_numpy(), tr_.laft.to_numpy())
        thr = np.quantile(L.ols_pred(b, tr_[C3_X].to_numpy()), 0.8)
        f = L.ols_pred(b, te[C3_X].to_numpy())
        errs["full"] += list((te.laft.to_numpy() - f) ** 2)
        errs["base"] += list((te.laft.to_numpy() - L.ols_pred(bb, te[C3_BASE].to_numpy())) ** 2)
        for day, fv in zip(te.day, f):
            if fv >= thr and day in pnl:
                trades.append(dict(day=day, net=pnl[day]["net"], prem_pct=pnl[day]["prem_pct"]))
    tr = pd.DataFrame(trades)
    g = rng()
    tw = np.zeros(NTWIN)
    for w in range(NTWIN):
        tot = 0.0
        for y in FOLDS:
            n = int((tr.day.dt.year == y).sum()) if len(tr) else 0
            pool = [dd for dd in pnl if dd.year == y]
            if n and pool:
                tot += sum(pnl[pool[i]]["net"] for i in g.choice(len(pool), min(n, len(pool)), replace=False))
        tw[w] = tot
    s = fold_summary(tr.net if len(tr) else [], tr.day if len(tr) else [], tw, FOLDS)
    allnet = [v["net"] for dd, v in pnl.items() if dd.year in FOLDS]
    s["all_days_straddle"] = summ(allnet)
    s["forecast_mse_full_vs_base"] = {"full": round(float(np.mean(errs["full"])), 4),
                                      "base": round(float(np.mean(errs["base"])), 4), "n": len(errs["full"])}
    return s


# ================================================================================ C4 unusual OTM option volume


def c4(bk, und, opts):
    win_rows = []
    for day, ox in opts.items():
        if day > DESIGN_END:
            continue
        o = ox[(ox["mod"] >= 570) & (ox["mod"] <= 869)].copy()
        o["w"] = (o["mod"] - 570) // 5
        otm = ((o.side == "C") & o.offset.between(3, 8)) | ((o.side == "P") & o.offset.between(-8, -3))
        o = o[otm]
        if len(o) == 0:
            continue
        agg = o.groupby(["w", "offset", "side"]).agg(vol=("volume", "sum")).reset_index()
        last = o.sort_values("mod").groupby(["w", "offset", "side"]).tail(1)[["w", "offset", "side", "strike", "oi"]]
        agg = agg.merge(last, on=["w", "offset", "side"])
        first = o.sort_values("mod").groupby(["w", "strike", "side"]).head(1)[["w", "strike", "side", "oi"]]
        agg = agg.merge(first.rename(columns={"oi": "oi0"}), on=["w", "strike", "side"], how="left")
        agg["day"] = day
        win_rows.append(agg)
    W = pd.concat(win_rows, ignore_index=True).sort_values("day")
    days = sorted(W.day.unique())
    base = {}
    for (off, sd), x in W.groupby(["offset", "side"]):
        per_day = {dd: v.vol.to_numpy() for dd, v in x.groupby("day")}
        for i, dd in enumerate(days):
            prev = [per_day[p] for p in days[max(0, i - 20):i] if p in per_day]
            if len(prev) >= 10:
                base[(dd, off, sd)] = float(np.median(np.concatenate(prev)))
    W["base"] = [base.get((dd, o_, s_), np.nan) for dd, o_, s_ in zip(W.day, W.offset, W.side)]
    ev = W[(W.vol >= 8 * W.base) & (W.base > 0) & (W.oi > W.oi0)].copy()
    ev = ev[pd.DatetimeIndex(ev.day).year.isin(FOLDS)]
    dk = {dd: k for k, dd in enumerate(bk.days)}
    es, sides, ds = [], [], []
    per_day = {}
    for _, r in ev.sort_values(["day", "w"]).iterrows():
        k = dk.get(r.day)
        if k is None:
            continue
        sl = bk.day_slice(k)
        mod = bk.mod[sl]
        a, b_ = RB.idx(mod, 570 + 5 * r.w), RB.idx(mod, 574 + 5 * r.w)
        en = RB.idx(mod, 575 + 5 * r.w)
        if None in (a, b_, en):
            continue
        im = bk.c[sl][b_] / bk.o[sl][a] - 1
        side = 1 if r.side == "C" else -1
        if im * side <= 0 or per_day.get(k, 0) >= 3:
            continue
        per_day[k] = per_day.get(k, 0) + 1
        es.append(sl.start + en); sides.append(side); ds.append(k)
    if not es:
        return {"n": 0, "p": 1.0, "pass_design": False}
    e, side = np.array(es), np.array(sides)
    last = np.minimum(e + 29, np.array([last1515(bk, k) for k in ds]))
    tr = fut_trade(bk, und, e, last, side, bk.o[e] * (1 - side * 0.0015))
    keep = one_at_a_time(tr.e.to_numpy(), tr.xi.to_numpy())
    tr = tr.iloc[keep].reset_index(drop=True)
    tw = RB.minute_twins(bk, und, tr, np.full(len(tr), 0.0015), 575, 875, lambda e_, i15: np.minimum(e_ + 29, i15), None)
    s = fold_summary(tr.net, tr.day, tw, FOLDS)
    s["spike_windows"] = int(len(ev))
    s["label"] = "option-flow trigger (option-chain volume/OI) confirmed by the underlying: NOT the default class"
    return s


# ================================================================================ C5 / C6 learned detectors


def early_features(bk, d, gi, sg, day_of, upto_mod):
    """Per day, features known at upto_mod + 1 (from 09:15 to upto_mod)."""
    vix = D.minutes("INDIAVIX")
    vday = vix.groupby("day").close.last()
    vat = vix[vix["mod"] == upto_mod].set_index("day").close
    ub, db = {}, {}
    for i, s in zip(gi, sg):
        if bk.mod[i] <= upto_mod:
            dct = ub if s > 0 else db
            dct[int(day_of[i])] = dct.get(int(day_of[i]), 0) + 1
    rows = []
    for _, r in d.iterrows():
        k = int(r.k)
        sl = bk.day_slice(k)
        mod = bk.mod[sl]
        j = RB.idx(mod, upto_mod)
        if j is None:
            continue
        o, h, l, c, tv = bk.o[sl][:j + 1], bk.h[sl][:j + 1], bk.l[sl][:j + 1], bk.c[sl][:j + 1], bk.tv[sl][:j + 1]
        dc = np.diff(np.r_[o[0], c])
        rows.append(dict(k=k, day=r.day, gap=r.open / r.prev_close - 1 if np.isfinite(r.prev_close) else np.nan,
                         mv=c[-1] / o[0] - 1, rng=(h.max() - l.min()) / c[-1], er=B.efficiency(o[0], c),
                         tv_early=tv.sum(), first30=tv[mod[:j + 1] <= 584].sum() / tv.sum() if tv.sum() > 0 else np.nan,
                         ub=ub.get(k, 0), db=db.get(k, 0), pos=(c[-1] - l.min()) / (h.max() - l.min()) if h.max() > l.min() else 0.5,
                         dn_tv=tv[1:][dc[1:] < 0].sum() > tv[1:][dc[1:] > 0].sum() if len(dc) > 1 else False,
                         vix_chg=vat.get(r.day, np.nan) / vday.shift(1).get(r.day, np.nan) - 1,
                         mv_1h=(c[mod[:j + 1] <= 614][-1] / o[0] - 1) if (mod[:j + 1] <= 614).any() else np.nan,
                         breadth_dn=np.nan))
    x = pd.DataFrame(rows)
    x["tv_ratio"] = x.tv_early / x.tv_early.shift(1).rolling(20, min_periods=15).median()
    return x


def basket_breadth_down(und, upto_mod):
    """Share of basket stocks whose close at upto_mod is below their 09:15 open, per day (P5)."""
    out = []
    for p in sorted((D.LAKE / "equity_1m").glob("equity_1m_*.parquet")):
        e = pd.read_parquet(p, columns=["symbol", "ts", "open", "close"])
        if RB.BASKET[und] is not None:
            e = e[e.symbol.isin(RB.BASKET[und])]
        ts = pd.to_datetime(e.ts)
        e = e.assign(day=ts.dt.tz_localize(None).dt.normalize(), mod=ts.dt.hour * 60 + ts.dt.minute)
        o = e[e["mod"] == 555].set_index(["day", "symbol"]).open
        c = e[e["mod"] == upto_mod].set_index(["day", "symbol"]).close
        j = pd.concat([o, c], axis=1, join="inner")
        out.append((j.close < j.open).groupby(level=0).mean())
    return pd.concat(out)


def seller_flags(x, und):
    f = pd.DataFrame({"day": x.day})
    f["P1"] = x.gap <= -0.005
    f["P2"] = (x.gap <= -0.005) & (x.mv < 0)
    f["P3"] = x.mv <= -0.004
    f["P4"] = x.db >= 1
    f["P5"] = x.breadth_dn >= 0.8
    f["P6"] = x.tv_ratio >= 1.5
    f["P7"] = x.stair
    f["P8"] = x.pos <= 0.2
    f["P9"] = x.vix_chg >= 0.05
    f["P10"] = x.bn_lead if und == "NIFTY" else False
    f["P11"] = x.failed_bounce
    f["P12"] = x.dn_tv.astype(bool)
    return f


def staircase_and_bounce(bk, x, upto_mod):
    st, fb, = [], []
    for k in x.k:
        sl = bk.day_slice(int(k))
        mod = bk.mod[sl]
        m = mod <= upto_mod
        bb = B.five_min_bars(mod[m], bk.o[sl][m], bk.h[sl][m], bk.l[sl][m], bk.c[sl][m], bk.tv[sl][m])
        st.append(bool(len(bb) >= 2 and np.all(np.diff(bb.l.to_numpy()) < 0)))
        up = bb[(bb.slot >= 1) & (bb.c > bb.o)]
        fb.append(bool(len(up) and bb.c.iloc[-1] <= up.o.iloc[0]))
    return st, fb


def fit_predict(train, test, cols, label):
    from sklearn.linear_model import LogisticRegression
    from sklearn.preprocessing import StandardScaler
    sc = StandardScaler().fit(train[cols].astype(float))
    m = LogisticRegression(C=1.0, max_iter=1000).fit(sc.transform(train[cols].astype(float)), train[label])
    return m.predict_proba(sc.transform(train[cols].astype(float)))[:, 1], m.predict_proba(sc.transform(test[cols].astype(float)))[:, 1]


def auc(y, p):
    y, p = np.asarray(y, bool), np.asarray(p, float)
    if y.sum() == 0 or (~y).sum() == 0:
        return None
    r = pd.Series(p).rank().to_numpy()
    return float((r[y].sum() - y.sum() * (y.sum() + 1) / 2) / (y.sum() * (~y).sum()))


def run_detector(bk, x, und, cols, label, side, entry_mod, exit_mod, stop_floor, extra_exit=None):
    x = x.dropna(subset=cols).copy()
    x = x[x.day <= DESIGN_END]
    trades, aucs, ks_rule = [], {}, []
    rec = []
    for y in FOLDS:
        tr_ = x[(x.day.dt.year < y) & x[label + "_known"]]
        te = x[x.day.dt.year == y]
        if len(tr_) < 150 or tr_[label].sum() < 10 or len(te) == 0:
            continue
        ptr, pte = fit_predict(tr_, te, cols, label)
        thr = np.quantile(ptr, 0.9)
        known = te[label + "_known"].to_numpy()
        aucs[str(y)] = auc(te[label].to_numpy()[known], pte[known])
        go = (pte >= thr) & (np.sign(te.mv.to_numpy()) == side)
        for (_, r), g_ in zip(te.iterrows(), go):
            if g_:
                rec.append(r)
    if not rec:
        return {"n": 0, "p": 1.0, "pass_design": False, "auc_by_year": aucs}, None
    sel = pd.DataFrame(rec)
    es, last, stops, ks, last15 = [], [], [], [], []
    for _, r in sel.iterrows():
        k = int(r.k)
        sl = bk.day_slice(k)
        mod = bk.mod[sl]
        i_e, i_x = RB.idx(mod, entry_mod), RB.idx(mod, exit_mod)
        if i_e is None or i_x is None:
            continue
        ent = bk.o[sl][i_e]
        dist = max(0.5 * r.rng * ent, stop_floor * ent)
        es.append(sl.start + i_e); last.append(sl.start + i_x); stops.append(ent - side * dist); ks.append(k)
        last15.append(last1515(bk, k))
    e = np.array(es)
    tr = fut_trade(bk, und, e, np.array(last), np.full(len(e), side), np.array(stops))
    sp = np.abs(bk.o[e] - np.array(stops)) / bk.o[e]
    nby = {y: int((bk.days[ks].year == y).sum()) for y in FOLDS}
    pool = list(x[x.day.dt.year.isin(FOLDS)].k.astype(int))

    def exit_fn(k, ee):
        sl = bk.day_slice(k)
        j = RB.idx(bk.mod[sl], exit_mod)
        return sl.start + j if j is not None else last1515(bk, k)
    tw = day_twins_minute(bk, und, pool, nby, side, sp, entry_mod, entry_mod, exit_fn)
    s = fold_summary(tr.net, tr.day, tw, FOLDS)
    s["auc_by_year"] = aucs
    s["exit_kinds"] = tr.kind.value_counts().to_dict()
    if extra_exit:
        t2 = fut_trade(bk, und, e, np.array(last15), np.full(len(e), side), np.array(stops))
        s["held_to_1515"] = summ(t2.net)
    return s, sel


def prep_detector_table(bk, d, und, gi, sg, day_of, upto_mod):
    x = early_features(bk, d, gi, sg, day_of, upto_mod)
    lab = RB.labels(d.copy())[["day", "FII", "Client"]]
    x = x.merge(lab, on="day", how="left")
    x["FII_BUY"] = x.FII == "BUY"
    x["FII_SELL"] = x.FII == "SELL"
    x["CL_BUY"] = x.Client == "BUY"
    for c in ("FII_BUY", "FII_SELL", "CL_BUY"):
        x[c + "_known"] = x.FII.isin(["BUY", "SELL", "other"]) if c != "CL_BUY" else x.Client.isin(["BUY", "SELL", "other"])
    return x


# ================================================================================ 6a catalogue


def fisher_p(a, b, c, d_):
    from scipy.stats import fisher_exact
    return float(fisher_exact([[a, b], [c, d_]], alternative="greater")[1])


def catalogue(bk, x, flags, und):
    x = x.copy().reset_index(drop=True)
    flags = flags.reset_index(drop=True)
    des = (x.day <= DESIGN_END) & x.FII_SELL_known
    xs, fs = x[des], flags[des]
    base = float(xs.FII_SELL.mean())
    rows = {}
    # forward moves 09:45 -> 11:15 and -> 15:15
    f1115, f1515 = [], []
    for k in xs.k:
        sl = bk.day_slice(int(k))
        mod = bk.mod[sl]
        a, b_ = RB.idx(mod, 585), RB.idx(mod, 674)
        z = np.where(mod <= 915)[0][-1]
        f1115.append(bk.c[sl][b_] / bk.o[sl][a] - 1 if None not in (a, b_) else np.nan)
        f1515.append(bk.c[sl][z] / bk.o[sl][a] - 1 if a is not None else np.nan)
    f1115, f1515 = np.array(f1115), np.array(f1515)
    ps = []
    for p in [f"P{i}" for i in range(1, 13)]:
        m = fs[p].fillna(False).astype(bool).to_numpy()
        if m.sum() == 0:
            rows[p] = {"days": 0}
            ps.append(np.nan)
            continue
        y = xs.FII_SELL.to_numpy()
        a, b_ = int((m & y).sum()), int((m & ~y).sum())
        c, d_ = int((~m & y).sum()), int((~m & ~y).sum())
        pv = fisher_p(a, b_, c, d_)
        ps.append(pv)
        rows[p] = {"days": int(m.sum()), "share_of_days": round(float(m.mean()), 3),
                   "fii_sell_share": round(a / max(m.sum(), 1), 3), "base_rate": round(base, 3),
                   "lift": round((a / max(m.sum(), 1)) / base, 2) if base > 0 else None, "fisher_p": pv,
                   "caught_share_of_sell_days": round(a / max(y.sum(), 1), 3),
                   "move_0945_1115_bps": {"with": round(float(np.nanmean(f1115[m])) * 1e4, 1),
                                          "without": round(float(np.nanmean(f1115[~m])) * 1e4, 1)},
                   "move_0945_1515_bps": {"with": round(float(np.nanmean(f1515[m])) * 1e4, 1),
                                          "without": round(float(np.nanmean(f1515[~m])) * 1e4, 1)},
                   "client_buy_share": round(float(xs.CL_BUY.to_numpy()[m].mean()), 3)}
    q = L.bh(ps)
    for p, qq in zip([f"P{i}" for i in range(1, 13)], q):
        if "fisher_p" in rows[p]:
            rows[p]["bh_q"] = qq
    rows["_n_days"] = int(len(xs))
    rows["_fii_sell_days"] = int(xs.FII_SELL.sum())
    return rows


# ================================================================================ main


def design():
    OUT.mkdir(parents=True, exist_ok=True)
    res, extra = {}, {}
    res.update({f"C2-{u}": c2(u) for u in ("NIFTY", "BANKNIFTY")})
    print("C2 done", flush=True)
    for und in ("NIFTY", "BANKNIFTY"):
        print("book", und, flush=True)
        bk = RB.book(und)
        day_of = np.repeat(np.arange(len(bk.days)), bk.end - bk.start)
        d = RB.day_table(bk, und)
        gi, sg = RB.bursts(bk, und)
        res[f"C1-{und}"] = c1(bk, d, und)
        extra[f"C1-mirror-{und}"] = c1(bk, d, und, mirror=True)
        print("C1", und, flush=True)
        opts = opt_day_tables(und)
        res[f"C3-{und}"] = c3(bk, d, und, gi, sg, day_of, opts)
        print("C3", und, flush=True)
        res[f"C4-{und}"] = c4(bk, und, opts)
        print("C4", und, flush=True)
        del opts
        # C5 at 12:00 (features 09:15-11:59)
        x5 = prep_detector_table(bk, d, und, gi, sg, day_of, 719)
        cols5 = ["gap", "mv_1h", "mv", "er", "tv_ratio", "ub", "db", "first30"]
        res[f"C5-{und}"], _ = run_detector(bk, x5, und, cols5, "FII_BUY", 1, 720, 915, 0.0)
        print("C5", und, flush=True)
        # C6 at 09:45 (features 09:15-09:44)
        x6 = prep_detector_table(bk, d, und, gi, sg, day_of, 584)
        br = basket_breadth_down(und, 584)
        x6["breadth_dn"] = x6.day.map(br)
        st, fb = staircase_and_bounce(bk, x6, 584)
        x6["stair"], x6["failed_bounce"] = st, fb
        if und == "NIFTY":
            bk2 = RB.book("BANKNIFTY")
            bn = RB.day_table(bk2, "BANKNIFTY")
            bmv = {}
            for _, r in bn.iterrows():
                sl = bk2.day_slice(int(r.k))
                j = RB.idx(bk2.mod[sl], 584)
                if j is not None:
                    bmv[r.day] = bk2.c[sl][j] / bk2.o[sl][0] - 1
            x6["bn_lead"] = [bmv.get(dd, np.nan) < m_ if dd in bmv else False for dd, m_ in zip(x6.day, x6.mv)]
        else:
            x6["bn_lead"] = False
        fl = seller_flags(x6, und)
        for c in fl.columns[1:]:
            x6[c] = fl[c].fillna(False).astype(float)
        extra[f"seller_catalogue-{und}"] = catalogue(bk, x6, fl, und)
        cols6 = [f"P{i}" for i in range(1, 13)] + ["gap", "mv", "tv_ratio", "vix_chg"]
        if und != "NIFTY":
            cols6.remove("P10")
        res[f"C6-{und}"], _ = run_detector(bk, x6, und, cols6, "FII_SELL", -1, 585, 675, 0.0015, extra_exit=True)
        print("C6", und, flush=True)
        (OUT / "c_design_partial.json").write_text(json.dumps({"tests": res, "extra": extra}, indent=1, default=str))
    ids = list(res)
    q = L.bh([res[k]["p"] for k in ids])
    for k, qq in zip(ids, q):
        res[k]["bh_q"] = qq
        res[k]["passes"] = bool(res[k]["pass_design"] and res[k]["p"] < 0.10 and qq < 0.10)
    (OUT / "c_design.json").write_text(json.dumps({"tests": res, "extra": extra}, indent=1, default=str))
    (OUT / "c_design_partial.json").unlink(missing_ok=True)
    for k, r in res.items():
        print(f"{k:14s} n={r['n']} net={r.get('net')} twins={r.get('twin_mean')} p={r['p']:.3f} q={r['bh_q']:.3f} "
              f"pass={r['passes']}")


def final():
    des = json.loads((OUT / "c_design.json").read_text())["tests"]
    passers = [k for k, r in des.items() if r.get("passes")]
    if (OUT / "FINAL_C_READ").exists():
        raise SystemExit("R86c final already read once")
    if not passers:
        print("no R86c test passed the design gates: the final window is NOT read (PREREG_C)")
        return
    raise SystemExit(f"design passers {passers}: add the final scoring before reading")


if __name__ == "__main__":
    {"design": design, "final": final}.get(sys.argv[1] if len(sys.argv) > 1 else "design", lambda: print(__doc__))()
