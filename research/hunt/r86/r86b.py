"""R86b runner: how big players trade, read from index + constituent minutes. PREREG: research/hunt/r86/PREREG_B.md.

    python -m research.hunt.r86.r86b design     # Part 1 footprints + Part 2's 8 tests on design -> results/b_design.json
    python -m research.hunt.r86.r86b final      # ONCE, only design passers (refuses when none passed)

Data: R86's lake copies in /dev/shm (R86_LAKE). Caches go to /dev/shm/r86b (R86B_CACHE), never into git.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r86 import r86blib as B
from research.hunt.r86 import r86data as D
from research.hunt.r86 import r86lib as L
from research.hunt.r86.r86study import MinuteBook, fast_cost, one_at_a_time, summ, vec_sim
from research.obuy.config import LOT_SCHEDULE

OUT = Path(__file__).resolve().parent / "results"
CACHE = Path(os.environ.get("R86B_CACHE", "/dev/shm/r86b"))
SEED = 861
NTWIN = int(os.environ.get("R86_NTWIN", "500"))   # fewer only for a smoke run; the reported run uses 500
DESIGN_END = pd.Timestamp("2024-12-31")
FOLDS = [2022, 2023, 2024]
BANKS = {"AUBANK", "AXISBANK", "BANKBARODA", "CANBK", "FEDERALBNK", "HDFCBANK", "ICICIBANK", "IDFCFIRSTB", "INDUSINDBK",
         "KOTAKBANK", "PNB", "SBIN", "UNIONBANK", "YESBANK"}
BASKET = {"NIFTY": None, "BANKNIFTY": BANKS}
N_MIN = {"NIFTY": 30, "BANKNIFTY": 7}      # at least about half the basket must have printed that minute


def rng():
    return np.random.default_rng(SEED)


# ================================================================================ data


def basket(und: str) -> pd.DataFrame:
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / f"basket_{und}.parquet"
    if f.exists():
        return pd.read_parquet(f)
    parts = []
    for p in sorted((D.LAKE / "equity_1m").glob("equity_1m_*.parquet")):
        e = pd.read_parquet(p, columns=["symbol", "ts", "close", "volume"])
        ts = pd.to_datetime(e.ts)
        e = e.assign(day=ts.dt.tz_localize(None).dt.normalize(), mod=ts.dt.hour * 60 + ts.dt.minute)
        e = e[(e["mod"] >= 555) & (e["mod"] <= 929)]
        parts.append(B.basket_minutes(e, BASKET[und]))
    b = pd.concat(parts, ignore_index=True)
    b.to_parquet(f)
    return b


def book(und: str) -> MinuteBook:
    m = D.minutes(und)
    m = m[m.day >= pd.Timestamp("2019-01-01")]
    b = basket(und)
    m = m.merge(b, on=["day", "mod"], how="left")
    for c in ("tv", "up", "dn", "n"):
        m[c] = m[c].fillna(0)
    bk = MinuteBook(m)
    bk.tv, bk.up, bk.dn, bk.n = (bk.m[c].to_numpy(float) for c in ("tv", "up", "dn", "n"))
    return bk


def idx(mod, t):
    w = np.where(mod == t)[0]
    return int(w[0]) if len(w) else None


def good_day(mod) -> bool:
    return len(mod) >= 300 and mod[0] <= 560 and mod[-1] >= 925


# ================================================================================ per-day table


def day_table(bk: MinuteBook, und: str) -> pd.DataFrame:
    """One row per session with every footprint used in Parts 1 and 2 (each known at the time it is used)."""
    rows = []
    for k in range(len(bk.days)):
        sl = bk.day_slice(k)
        mod = bk.mod[sl]
        if not good_day(mod):
            continue
        o, h, l, c, tv = bk.o[sl], bk.h[sl], bk.l[sl], bk.c[sl], bk.tv[sl]
        i930, i1129, i1130, i1515 = idx(mod, 570), idx(mod, 689), idx(mod, 690), int(np.where(mod <= 915)[0][-1])
        i1430, i1524, i1525 = idx(mod, 870), idx(mod, 924), idx(mod, 925)
        r = dict(k=k, day=bk.days[k], open=o[0], close=c[-1], high=h.max(), low=l.min(), tv_day=tv.sum())
        r["er_day"] = B.efficiency(o[0], c)
        hours = [(555, 614), (615, 674), (675, 734), (735, 794), (795, 854), (855, 929)]
        for a, b_ in hours:
            m_ = (mod >= a) & (mod <= b_)
            r[f"mv{a}"] = (c[m_][-1] - o[m_][0]) / o[0] if m_.any() else np.nan     # the hour's move, % of the open
        r["tv_first30"] = tv[mod <= 584].sum() / tv.sum() if tv.sum() > 0 else np.nan
        r["tv_last30"] = tv[mod >= 900].sum() / tv.sum() if tv.sum() > 0 else np.nan
        a_ret = np.abs(np.diff(np.r_[o[0], c]))
        r["corr_abs_tv"] = float(np.corrcoef(a_ret, tv)[0, 1]) if tv.std() > 0 else np.nan
        if None not in (i930, i1129, i1130):
            r["m_move"] = c[i1129] / o[i930] - 1
            r["m_er"] = B.efficiency(o[i930], c[i930:i1129 + 1])
            r["m_tv"] = tv[i930:i1129 + 1].sum()
            r["m_rng"] = h[i930:i1129 + 1].max() - l[i930:i1129 + 1].min()
            r["e1130"], r["i1515"] = sl.start + i1130, sl.start + i1515
        if None not in (i1430, i1524, i1525):
            r["late_move"] = c[i1524] / o[i1430] - 1
            upto = tv[:i1524 + 1].sum()
            r["late_tv_share"] = tv[i1430:i1524 + 1].sum() / upto if upto > 0 else np.nan
            r["e1525"] = sl.start + i1525
        rows.append(r)
    d = pd.DataFrame(rows).reset_index(drop=True)
    d["prev_close"] = d.close.shift(1)
    d["gap_share"] = (d.open - d.prev_close) / (d.close - d.prev_close)
    d["ret"] = d.close / d.open - 1
    d["atr_prev"] = L.wilder_atr(d.high.to_numpy(), d.low.to_numpy(), d.close.to_numpy(), 14)
    d["atr_prev"] = d.atr_prev.shift(1)       # known before today's session
    return d


# ================================================================================ Part 1


def labels(d: pd.DataFrame) -> pd.DataFrame:
    p = D.participants()
    out = pd.DataFrame(index=p.index)
    for g, pre in (("FII", "fii_"), ("Client", "cl_")):
        net = p[pre + "fut_idx_long"] - p[pre + "fut_idx_short"]
        ch = net.diff()
        hi = ch.shift(1).rolling(250, min_periods=120).quantile(0.9)
        lo = ch.shift(1).rolling(250, min_periods=120).quantile(0.1)
        out[g] = np.where(ch >= hi, "BUY", np.where(ch <= lo, "SELL", "other"))
        out.loc[hi.isna(), g] = "na"
    d = d.merge(out, left_on="day", right_index=True, how="left")
    exps = pd.to_datetime(pd.Series(D.nifty_fut_oi().expiry.unique()))
    d["expiry"] = d.day.isin(set(exps))
    d["month_end"] = d.day.dt.month != d.day.shift(-1).dt.month
    return d


def boot_mean(x, n=1000):
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    if len(x) < 5:
        return {"n": int(len(x)), "mean": None}
    g = np.random.default_rng(SEED)
    bs = np.array([g.choice(x, len(x)).mean() for _ in range(n)])
    return {"n": int(len(x)), "mean": round(float(x.mean()), 4), "lo90": round(float(np.quantile(bs, 0.05)), 4),
            "hi90": round(float(np.quantile(bs, 0.95)), 4)}


METRICS = ["ret", "er_day", "tv_first30", "tv_last30", "corr_abs_tv", "bursts", "burst_agree"]
HOURS = [555, 615, 675, 735, 795, 855]


def move_shares(x: pd.DataFrame) -> dict:
    """Where the day's move was made, robust to flat days: sum over the group's days of (piece x sign of the day's
    close-to-close move) / sum of abs(close-to-close move). Pieces: the opening gap and each hour (sum ~ 1)."""
    cc = (x.close / x.prev_close - 1).to_numpy()
    sg, den = np.sign(cc), np.nansum(np.abs(cc))
    if not den > 0:
        return {}
    out = {"gap": round(float(np.nansum((x.open / x.prev_close - 1).to_numpy() * sg) / den), 3)}
    for h in HOURS:
        out[f"h{h}"] = round(float(np.nansum(x[f"mv{h}"].to_numpy() * x.open.to_numpy() / x.prev_close.to_numpy() * sg) / den), 3)
    out["n"] = int(np.isfinite(cc).sum())
    return out


def part1(bk, d, und, bursts_by_day):
    d = d.copy()
    d["bursts"] = d.k.map(lambda k: len(bursts_by_day.get(k, [])))
    d["burst_agree"] = [np.mean([s == np.sign(rt) for s in bursts_by_day.get(k, [])]) if bursts_by_day.get(k) else np.nan
                        for k, rt in zip(d.k, d.ret)]
    d = labels(d)
    d = d[d.day <= DESIGN_END]
    out = {}
    groups = {"all": d.index == d.index}
    for g in ("FII", "Client"):
        for lab in ("BUY", "SELL"):
            groups[f"{g}-{lab}"] = (d[g] == lab).to_numpy()
    groups["expiry"] = d.expiry.to_numpy()
    groups["month_end"] = d.month_end.to_numpy()
    for gname, m in groups.items():
        out[gname] = {mt: boot_mean(d.loc[m, mt]) for mt in METRICS}
        out[gname]["move_share"] = move_shares(d[m])
    # FII-BUY / SELL in direction-free terms: share of days the index closed the FII's way
    for lab, sgn in (("BUY", 1), ("SELL", -1)):
        x = d[d.FII == lab]
        out[f"FII-{lab}"]["index_moved_their_way"] = {"n": int(len(x)), "share": round(float((np.sign(x.ret) == sgn).mean()), 3)}
    # turnover profile by half hour (design)
    m = bk.m[(bk.m.day <= DESIGN_END) & (bk.m.day.isin(set(d.day)))]
    hh = ((m["mod"] - 555) // 30).clip(upper=12)
    prof = m.groupby(hh).tv.sum()
    out["turnover_profile_halfhour"] = {f"{555 + 30 * int(a)}": round(float(v / prof.sum()), 4) for a, v in prof.items()}
    return out


# ================================================================================ Part 2 tests


def trade_rows(bk, und, e, last, side, stop, target, days, overnight_days=None):
    xi, px, kind = vec_sim(bk, np.asarray(e), np.asarray(last), np.asarray(side), np.asarray(stop, float),
                           np.asarray(target, float))
    entry = bk.o[np.asarray(e)]
    units = np.array([L.lot_on(LOT_SCHEDULE[und], x) for x in days])
    gross = np.asarray(side) * (px - entry) * units
    net = gross - fast_cost(np.asarray(side), entry, px, units, list(days), kind == "stop")
    return pd.DataFrame({"day": list(days), "e": e, "xi": xi, "side": side, "entry": entry, "px": px, "kind": kind,
                         "gross": gross, "net": net})


def fold_result(tr, twins, nmax=None):
    nets = tr.net.to_numpy() if len(tr) else np.array([])
    s = summ(nets)
    yrs = pd.DatetimeIndex(tr.day).year if len(tr) else np.array([])
    s.update(twin_mean=round(float(np.mean(twins)), 0), twin_p=L.twin_p(float(nets.sum()), twins),
             by_year={str(y): round(float(nets[yrs == y].sum()), 0) for y in FOLDS},
             n_by_year={str(y): int((yrs == y).sum()) for y in FOLDS},
             exit_kinds=tr.kind.value_counts().to_dict() if len(tr) else {})
    s["p"] = s["twin_p"]
    s["pass_design"] = bool(s["n"] > 0 and s["net"] > 0 and s["net"] > s["twin_mean"] and s["net_wo_top5"] > 0)
    return s


def in_folds(day):
    return pd.DatetimeIndex(day).year.isin(FOLDS)


# ---- T1 steady accumulation


def t1(bk, d, und):
    d = d.dropna(subset=["m_move", "m_er"]).copy()
    d["er_q80"] = B.prev_quantile(d.m_er, 0.8)
    d["mv_q50"] = B.prev_quantile(d.m_move.abs(), 0.5)
    d["tv_med"] = d.m_tv.shift(1).rolling(20, min_periods=10).median()
    sig = (d.m_er >= d.er_q80) & (d.m_move.abs() >= d.mv_q50) & (d.m_tv >= d.tv_med)
    pool = d[in_folds(d.day) & d.er_q80.notna()]
    rule = pool[sig.loc[pool.index]]
    side = np.sign(rule.m_move.to_numpy()).astype(int)

    def make(rows, sd):
        e = rows.e1130.astype(int).to_numpy()
        ent = bk.o[e]
        stop = ent - sd * 0.5 * rows.m_rng.to_numpy()
        return trade_rows(bk, und, e, rows.i1515.astype(int).to_numpy(), sd, stop, np.full(len(e), np.nan), rows.day)
    tr = make(rule, side)
    g = rng()
    tw = np.zeros(NTWIN)
    yr_pool = {y: pool[pool.day.dt.year == y] for y in FOLDS}
    yr_side = {y: side[rule.day.dt.year.to_numpy() == y] for y in FOLDS}
    for w in range(NTWIN):
        tot = 0.0
        for y in FOLDS:
            k = len(yr_side[y])
            if k == 0:
                continue
            rows = yr_pool[y].iloc[g.choice(len(yr_pool[y]), k, replace=False)]
            tot += make(rows, g.permutation(yr_side[y])).net.sum()
        tw[w] = tot
    s = fold_result(tr, tw)
    s["signal_days"] = int(len(rule))
    s["pool_days"] = int(len(pool))
    return s


# ---- T2 basket bursts


def bursts(bk, und):
    """All burst minutes (global index, sign) from 09:30 to 14:45, with the same-minute 20-session turnover median."""
    m = bk.m[["day", "mod"]].copy()
    m["slot"] = m["mod"]
    m["tv"] = bk.tv
    med = B.same_slot_median(m, "tv", 20, 10)
    fl = B.burst_flags(bk.up, bk.dn, bk.n, bk.tv, med, N_MIN[und])
    win = (bk.mod >= 570) & (bk.mod <= 885)
    gi = np.where((fl != 0) & win)[0]
    return gi, fl[gi]


def t2(bk, und, gi, sg, day_of):
    keep_rows = []
    count = {}
    for i, s in zip(gi, sg):
        k = int(day_of[i])
        if count.get(k, 0) >= 3:
            continue
        keep_rows.append((i, s, k))
        count[k] = count.get(k, 0) + 1
    rows = pd.DataFrame(keep_rows, columns=["i", "side", "k"])
    rows["day"] = bk.days[rows.k.to_numpy()]
    rows = rows[in_folds(rows.day)]
    e = rows.i.to_numpy() + 1
    ok = bk.mod[np.minimum(e, len(bk.mod) - 1)] <= 915
    ok &= day_of[np.minimum(e, len(day_of) - 1)] == rows.k.to_numpy()
    rows, e = rows[ok], e[ok]
    i1515 = np.array([bk.start[k] + int(np.where(bk.mod[bk.day_slice(k)] <= 915)[0][-1]) for k in rows.k])
    last = np.minimum(e + 29, i1515)
    ent = bk.o[e]
    rngm = bk.h[rows.i] - bk.l[rows.i]
    dist = np.maximum(2 * rngm, 0.0005 * ent)
    side = rows.side.to_numpy().astype(int)
    tr = trade_rows(bk, und, e, last, side, ent - side * dist, np.full(len(e), np.nan), rows.day)
    keep = one_at_a_time(tr.e.to_numpy(), tr.xi.to_numpy())
    tr = tr.iloc[keep].reset_index(drop=True)
    pct = (dist / ent)[keep]
    tw = minute_twins(bk, und, tr, pct, 571, 886, lambda e_, i15: np.minimum(e_ + 29, i15), None)
    s = fold_result(tr, tw)
    s["bursts_in_folds"] = int(len(rows))
    return s


def minute_twins(bk, und, tr, stop_pct, lo_mod, hi_mod, last_fn, target_r):
    g = rng()
    dk = {d: k for k, d in enumerate(bk.days)}
    info = []
    for d in tr.day:
        k = dk[d]
        sl = bk.day_slice(k)
        mod = bk.mod[sl]
        cand = np.where((mod >= lo_mod) & (mod <= hi_mod))[0] + sl.start
        info.append((cand, sl.start + int(np.where(mod <= 915)[0][-1])))
    side = tr.side.to_numpy().astype(int)
    units = np.array([L.lot_on(LOT_SCHEDULE[und], x) for x in tr.day])
    out = np.zeros(NTWIN)
    for w in range(NTWIN):
        e = np.array([g.choice(c) for c, _ in info])
        last = last_fn(e, np.array([x for _, x in info]))
        ent = bk.o[e]
        stop = ent * (1 - side * stop_pct)
        tgt = ent + side * target_r * stop_pct * ent if target_r else np.full(len(e), np.nan)
        xi, px, kind = vec_sim(bk, e, last, side, stop, tgt)
        out[w] = float((side * (px - ent) * units - fast_cost(side, ent, px, units, list(tr.day), kind == "stop")).sum())
    return out


# ---- T3 absorption


def t3_events(bk, und, folds_only=True):
    """Absorption bars -> entries. The slot medians use the previous 20 sessions."""
    recs = []
    bars_all = []
    for k in range(len(bk.days)):
        sl = bk.day_slice(k)
        mod = bk.mod[sl]
        if not good_day(mod):
            continue
        bb = B.five_min_bars(mod, bk.o[sl], bk.h[sl], bk.l[sl], bk.c[sl], bk.tv[sl])
        bb["day"], bb["k"], bb["start"] = bk.days[k], k, sl.start
        bb["rng"] = (bb.h - bb.l) / bb.c
        bars_all.append(bb)
    A = pd.concat(bars_all, ignore_index=True)
    A["tv_med"] = B.same_slot_median(A, "tv", 20, 10)
    A["rng_med"] = B.same_slot_median(A, "rng", 20, 10)
    for k, bb in A.groupby("k", sort=True):
        day = bb.day.iloc[0]
        if folds_only and day.year not in FOLDS:
            continue
        bb = bb.reset_index(drop=True)
        for r, side in B.absorption_bars(bb, bb.tv_med.to_numpy(), bb.rng_med.to_numpy()):
            b = bb.iloc[r]
            if b.nmin < 5:
                continue
            e = int(b.start + b.i_last + 1)
            recs.append(dict(day=day, k=k, e=e, side=side, ext=b.h if side < 0 else b.l))
    return pd.DataFrame(recs)


def t3(bk, und):
    ev = t3_events(bk, und)
    if len(ev) == 0:
        return {"n": 0, "p": 1.0, "pass_design": False}
    sl_last = {k: bk.start[k] + int(np.where(bk.mod[bk.day_slice(k)] <= 915)[0][-1]) for k in ev.k.unique()}
    ev["i1515"] = ev.k.map(sl_last)
    ev = ev[(ev.e <= ev.i1515) & (bk.m.day.to_numpy()[np.minimum(ev.e, len(bk.c) - 1)] == ev.day.to_numpy())]
    ent = bk.o[ev.e.to_numpy()]
    side = ev.side.to_numpy().astype(int)
    stop = ev.ext.to_numpy() * (1 - side * 0.0002)
    risk = (ent - stop) * side
    ok = risk > 0
    ev, ent, side, stop, risk = ev[ok], ent[ok], side[ok], stop[ok], risk[ok]
    last = np.minimum(ev.e.to_numpy() + 59, ev.i1515.to_numpy())
    tr = trade_rows(bk, und, ev.e.to_numpy(), last, side, stop, ent + side * 2 * risk, ev.day)
    keep = one_at_a_time(tr.e.to_numpy(), tr.xi.to_numpy())
    tr = tr.iloc[keep].reset_index(drop=True)
    pct = (risk / ent)[keep]
    tw = minute_twins(bk, und, tr, pct, 620, 910, lambda e_, i15: np.minimum(e_ + 59, i15), 2.0)
    s = fold_result(tr, tw)
    s["events_in_folds"] = int(len(ev))
    return s


# ---- T4 close flow, overnight


def t4(bk, d, und):
    d = d.dropna(subset=["late_move", "late_tv_share"]).copy()
    d["hi"] = B.prev_quantile(d.late_move, 0.9)
    d["lo"] = B.prev_quantile(d.late_move, 0.1)
    d["shr_med"] = B.prev_quantile(d.late_tv_share, 0.5)
    d["next_k"] = d.k.shift(-1)
    d = d.dropna(subset=["hi", "lo", "shr_med", "next_k", "atr_prev"])
    d = d[d.next_k == d.k + 1]
    sig = ((d.late_move >= d.hi) | (d.late_move <= d.lo)) & (d.late_tv_share >= d.shr_med)
    pool = d[in_folds(d.day)]
    rule = pool[sig.loc[pool.index]]
    side = np.sign(rule.late_move.to_numpy()).astype(int)

    def make(rows, sd):
        out = []
        for (_, r), s in zip(rows.iterrows(), sd):
            nk = int(r.next_k)
            nsl = bk.day_slice(nk)
            nmod = bk.mod[nsl]
            i930 = idx(nmod, 570)
            if i930 is None:
                continue
            ent = bk.o[int(r.e1525)]
            px, st = B.sim_overnight(ent, int(s), float(r.atr_prev), bk.o[nsl][0], bk.l[nsl][:i930 + 1],
                                     bk.h[nsl][:i930 + 1], bk.c[nsl][i930])
            units = L.lot_on(LOT_SCHEDULE[und], r.day)
            days = (bk.days[nk] - r.day).days
            cost = L.nse_fut_cost(int(s), ent, px, units, r.day, days, st)
            out.append(dict(day=r.day, side=int(s), entry=ent, px=px, kind="stop" if st else "time",
                            gross=s * (px - ent) * units, net=s * (px - ent) * units - cost))
        return pd.DataFrame(out)
    tr = make(rule, side)
    g = rng()
    tw = np.zeros(NTWIN)
    for w in range(NTWIN):
        parts = []
        for y in FOLDS:
            ys = side[rule.day.dt.year.to_numpy() == y]
            yp = pool[pool.day.dt.year == y]
            if len(ys) == 0:
                continue
            parts.append(make(yp.iloc[g.choice(len(yp), len(ys), replace=False)], g.permutation(ys)))
        tw[w] = float(sum(p.net.sum() for p in parts if len(p)))
    s = fold_result(tr, tw)
    s["signal_days"] = int(len(rule))
    return s


# ================================================================================ descriptive: what follows


def follow_moves(bk, gi, sg, day_of, hz=(15, 30, 60)):
    out = {}
    g = rng()
    for h in hz:
        j = gi + h
        ok = (j < len(bk.c))
        ok &= day_of[np.minimum(j, len(day_of) - 1)] == day_of[gi]
        mv = sg[ok] * (bk.c[j[ok]] / bk.c[gi[ok]] - 1)
        # random minutes of the same days, same sign
        rr = []
        for i, s in zip(gi[ok][:4000], sg[ok][:4000]):
            k = day_of[i]
            sl = bk.day_slice(k)
            cand = np.arange(sl.start, sl.stop - h)
            if len(cand) == 0:
                continue
            x = g.choice(cand)
            rr.append(s * (bk.c[x + h] / bk.c[x] - 1))
        out[f"{h}m"] = {"n": int(ok.sum()), "signed_bps": round(float(np.mean(mv)) * 1e4, 2),
                        "random_bps": round(float(np.mean(rr)) * 1e4, 2) if rr else None,
                        "abs_bps": round(float(np.mean(np.abs(mv))) * 1e4, 2)}
    return out


# ================================================================================ main


def design():
    OUT.mkdir(parents=True, exist_ok=True)
    res, p1 = {}, {}
    for und in ("NIFTY", "BANKNIFTY"):
        print("book", und, flush=True)
        bk = book(und)
        day_of = np.repeat(np.arange(len(bk.days)), bk.end - bk.start)
        d = day_table(bk, und)
        gi, sg = bursts(bk, und)
        dsg = {}
        for i, s in zip(gi, sg):
            dsg.setdefault(int(day_of[i]), []).append(int(s))
        print("part1", und, flush=True)
        p1[und] = part1(bk, d, und, dsg)
        des = bk.m.day.to_numpy()[gi] <= np.datetime64(DESIGN_END)
        p1[und]["after_bursts"] = follow_moves(bk, gi[des], sg[des], day_of)
        bmod = bk.mod[gi[des]]
        hh = pd.Series((bmod - 555) // 30).value_counts().sort_index()
        p1[und]["bursts_by_halfhour"] = {str(555 + 30 * int(a)): int(v) for a, v in hh.items()}
        print("T1..T4", und, flush=True)
        res[f"T1-{und}"] = t1(bk, d, und)
        res[f"T2-{und}"] = t2(bk, und, gi, sg, day_of)
        res[f"T3-{und}"] = t3(bk, und)
        res[f"T4-{und}"] = t4(bk, d, und)
        (OUT / "b_design_partial.json").write_text(json.dumps({"part2": res, "part1": p1}, indent=1, default=str))
    ids = list(res)
    q = L.bh([res[k]["p"] for k in ids])
    for k, qq in zip(ids, q):
        res[k]["bh_q"] = qq
        res[k]["passes"] = bool(res[k]["pass_design"] and res[k]["p"] < 0.10 and qq < 0.10)
    (OUT / "b_design.json").write_text(json.dumps({"part2": res, "part1": p1}, indent=1, default=str))
    (OUT / "b_design_partial.json").unlink(missing_ok=True)
    for k, r in res.items():
        print(f"{k:14s} n={r['n']} net={r.get('net')} twins={r.get('twin_mean')} p={r['p']:.3f} q={r['bh_q']:.3f} "
              f"pass={r['passes']}")


def final():
    des = json.loads((OUT / "b_design.json").read_text())["part2"]
    passers = [k for k, r in des.items() if r.get("passes")]
    if (OUT / "FINAL_B_READ").exists():
        raise SystemExit("R86b final already read once")
    if not passers:
        print("no R86b test passed the design gates: the final window is NOT read (PREREG_B section 4)")
        return
    raise SystemExit(f"design passers {passers}: add the final scoring before reading")


if __name__ == "__main__":
    {"design": design, "final": final}.get(sys.argv[1] if len(sys.argv) > 1 else "design", lambda: print(__doc__))()
