"""R86 study runner. Pre-registration: research/hunt/r86/PREREG.md (commit 65267ec).

    python -m research.hunt.r86.r86study design      # the 23 tests on design + walk-forward -> results/design.json
    python -m research.hunt.r86.r86study final       # ONCE, only the design passers -> results/final.json

Data: r86data (Drive lake copies and CFTC files in /dev/shm). Nothing raw is written into the repo.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r86 import r86data as D
from research.hunt.r86 import r86lib as L
from research.obuy.config import LOT_SCHEDULE

OUT = Path(__file__).resolve().parent / "results"
SEED = 86
NTWIN = int(os.environ.get("R86_NTWIN", "500"))   # fewer only for a smoke run; the reported run uses 500
DESIGN_END = {"daily": pd.Timestamp("2022-12-31"), "minute": pd.Timestamp("2024-12-31"), "gex": pd.Timestamp("2023-12-31")}
FOLDS = {"daily": list(range(2015, 2023)), "minute": [2022, 2023, 2024], "gex": [2022, 2023]}
FINAL_START = {"daily": pd.Timestamp("2023-01-01"), "minute": pd.Timestamp("2025-01-01"), "gex": pd.Timestamp("2024-01-01")}
FIT_START = pd.Timestamp("2012-01-01")


def rng():
    return np.random.default_rng(SEED)


def summ(nets) -> dict:
    a = np.asarray(nets, float)
    return {"n": int(len(a)), "net": round(float(a.sum()), 0), "win": round(float((a > 0).mean()), 3) if len(a) else None,
            "per_trade": round(float(a.mean()), 1) if len(a) else None, "net_wo_top5": round(L.without_top(a), 0)}


# ================================================================================ daily trade engine (P, C, E1)


class DailyBook:
    """One price series (dates, o, h, l, c, ATR14) and a cost function cost(side, entry, exit, i_in, i_out, stopped)."""

    def __init__(self, dates, o, h, l, c, cost):
        self.dates = pd.DatetimeIndex(dates)
        self.o, self.h, self.l, self.c = (np.asarray(a, float) for a in (o, h, l, c))
        self.atr = L.wilder_atr(self.h, self.l, self.c, 14)
        self.cost = cost
        self.year = self.dates.year.to_numpy()

    def trade(self, i, hold, side, stop_k):
        if i < 1 or not np.isfinite(self.atr[i - 1]):
            return None
        r = L.sim_daily(self.o, self.h, self.l, self.c, i, hold, side, stop_k * self.atr[i - 1])
        if r is None:
            return None
        j, px, stopped = r
        e = self.o[i]
        gross, cost = self.cost(side, e, px, i, j, stopped)
        return dict(i=int(i), j=int(j), side=int(side), date=str(self.dates[i].date()), gross=gross, net=gross - cost,
                    stopped=bool(stopped))

    def fwd(self, i, hold):
        if i + hold - 1 >= len(self.c):
            return np.nan
        return self.c[i + hold - 1] / self.o[i] - 1


def run_daily(book: DailyBook, cand: pd.DataFrame, centre, hold_of, stop_k, folds, fit_end_rule="fold",
              final_from=None, final_to=None):
    """cand: rows i (entry index), x (signal), trig (bool). hold_of(i) -> hold in sessions.

    Walk-forward: for fold Y the side sign is fitted on cand rows (all, triggered or not) whose entry is in
    [FIT_START, Y) and whose exit is before 1 Jan Y. final_from: one fold from final_from to final_to, sign fitted on
    rows exiting before final_from.
    """
    cand = cand.dropna(subset=["x"]).sort_values("i").reset_index(drop=True)
    ent_dates = book.dates[cand.i.to_numpy()]
    holds = np.array([hold_of(i) for i in cand.i])
    exit_idx = np.minimum(cand.i.to_numpy() + holds - 1, len(book.c) - 1)
    exit_dates = book.dates[exit_idx]
    fwd = np.array([book.fwd(i, h) for i, h in zip(cand.i, holds)])
    blocks = []
    if final_from is None:
        for y in folds:
            blocks.append((pd.Timestamp(f"{y}-01-01"), pd.Timestamp(f"{y}-12-31")))
    else:
        blocks.append((final_from, final_to))
    trades, signs = [], {}
    busy_until = -1
    for a, b in blocks:
        fit = (ent_dates >= FIT_START) & (exit_dates < a)
        sg = L.walk_sign(cand.x.to_numpy()[fit], fwd[fit], centre)
        signs[str(a.date())] = sg
        sel = (ent_dates >= a) & (ent_dates <= b) & cand.trig.to_numpy()
        for k in np.where(sel)[0]:
            i = int(cand.i[k])
            if i <= busy_until:
                continue
            side = sg * (1 if cand.x[k] - centre > 0 else -1)
            t = book.trade(i, int(holds[k]), side, stop_k)
            if t is None:
                continue
            t["block"] = str(a.year) if final_from is None else "final"
            trades.append(t)
            busy_until = t["j"]
    return trades, signs


def daily_twins(book: DailyBook, trades, hold_of, stop_k, blocks, valid_i, n=NTWIN):
    """Random-day twins: per block the same count, sides shuffled, one position at a time (PREREG section 4)."""
    g = rng()
    out = np.zeros(n)
    by = {}
    for t in trades:
        by.setdefault(t["block"], []).append(t)
    pools = {}
    for blk, (a, z) in blocks.items():
        d = book.dates[valid_i]
        pools[blk] = valid_i[(d >= a) & (d <= z)]
    for w in range(n):
        tot = 0.0
        for blk, ts in by.items():
            pool = pools.get(blk)
            if pool is None or len(pool) == 0:
                continue
            sides = g.permutation([t["side"] for t in ts])
            got, spans = 0, []
            for i in g.permutation(pool):
                if got >= len(ts):
                    break
                h = hold_of(int(i))
                if any(not (i + h - 1 < s or i > e) for s, e in spans):
                    continue
                t = book.trade(int(i), h, int(sides[got]), stop_k)
                if t is None:
                    continue
                spans.append((t["i"], t["j"]))
                tot += t["net"]
                got += 1
        out[w] = tot
    return out


def nse_cost_fn(book_dates, lot_sched):
    def cost(side, e, x, i, j, stopped):
        units = L.lot_on(lot_sched, book_dates[i])
        days = (book_dates[j] - book_dates[i]).days + 1
        return side * (x - e) * units, L.nse_fut_cost(side, e, x, units, book_dates[i], days, stopped)
    return cost


def mcx_cost_fn(mult):
    def cost(side, e, x, i, j, stopped):
        return side * (x - e) * mult, L.mcx_cost(side, e, x, mult, stopped)
    return cost


def trade_test(book, cand, centre, hold_of, stop_k, family="daily"):
    folds = FOLDS[family]
    trades, signs = run_daily(book, cand, centre, hold_of, stop_k, folds)
    blocks = {str(y): (pd.Timestamp(f"{y}-01-01"), pd.Timestamp(f"{y}-12-31")) for y in folds}
    valid = cand.dropna(subset=["x"]).i.to_numpy()
    valid = np.unique(valid)
    tw = daily_twins(book, trades, hold_of, stop_k, blocks, valid)
    nets = [t["net"] for t in trades]
    s = summ(nets)
    s.update(twin_mean=round(float(tw.mean()), 0), twin_p=L.twin_p(sum(nets), tw), signs=signs,
             by_year={y: round(sum(t["net"] for t in trades if t["block"] == y), 0) for y in blocks},
             n_by_year={y: sum(1 for t in trades if t["block"] == y) for y in blocks},
             stopped=round(float(np.mean([t["stopped"] for t in trades])), 3) if trades else None)
    s["p"] = s["twin_p"]
    s["pass_design"] = bool(s["n"] > 0 and s["net"] > 0 and s["net"] > s["twin_mean"] and s["net_wo_top5"] > 0)
    return s, trades


# ================================================================================ family P


def nifty_book():
    d = D.index_daily("NIFTY")
    return DailyBook(d.index, d.open, d.high, d.low, d.close, nse_cost_fn(d.index, LOT_SCHEDULE["NIFTY"])), d


def p_candidates(book, feats, col, trig):
    """Participant date t -> entry index i = the next NIFTY session after t."""
    pos = np.searchsorted(book.dates.values, feats.index.values, side="right")
    cand = pd.DataFrame({"i": pos, "x": feats[col].to_numpy()})
    cand = cand[cand.i < len(book.dates)]
    cand["trig"] = trig(cand.x.to_numpy())
    return cand


def family_p():
    book, d = nifty_book()
    f = L.participant_features(D.participants())
    res = {}
    rules = {"P1": ("P1", 0.5, lambda x: (x <= 0.10) | (x >= 0.90)),
             "P2": ("P2", 0.0, lambda x: np.abs(x) >= 1.5),
             "P3": ("P3", 0.5, lambda x: (x <= 0.10) | (x >= 0.90))}
    for tid, (col, centre, trig) in rules.items():
        cand = p_candidates(book, f, col, trig)
        cand = cand[book.dates[cand.i.to_numpy()] <= DESIGN_END["daily"]]
        s, _ = trade_test(book, cand, centre, lambda i: 5, 1.5)
        s["triggers"] = int(cand.trig.sum())
        res[tid] = s
    # range forecasts P4, P5
    vix = D.index_daily("INDIAVIX").close
    rows = p_range_rows(d, f, vix)
    for tid, extra in (("P4", ["P4"]), ("P5", ["P5"])):
        res[tid] = forecast_test(rows[rows.date <= DESIGN_END["daily"]], ["lvix", "lr5", "lr1"], extra, FOLDS["daily"], lag=5,
                                 gap_days=8)
    return res


def p_range_rows(d, f, vix):
    o, h, l, c = d.open.to_numpy(), d.high.to_numpy(), d.low.to_numpy(), d.close.to_numpy()
    rng1 = (h - l) / c
    r5 = pd.Series(rng1).rolling(5, min_periods=5).mean().to_numpy()
    n = len(c)
    y = np.full(n, np.nan)
    for t in range(n - 5):
        y[t] = np.log((h[t + 1:t + 6].max() - l[t + 1:t + 6].min()) / o[t + 1])
    base = pd.DataFrame({"date": d.index, "y": y, "lr1": np.log(rng1), "lr5": np.log(r5),
                         "lvix": np.log(vix.reindex(d.index).to_numpy())})
    base = base.merge(f[["P4", "P5"]], left_on="date", right_index=True, how="inner")
    return base.replace([np.inf, -np.inf], np.nan).dropna().reset_index(drop=True)


# ================================================================================ forecasts (P4, P5, G1, G2)


def wf_predict(rows, base, extra, folds, gap_days, final=None):
    preds = []
    blocks = [(pd.Timestamp(f"{y}-01-01"), pd.Timestamp(f"{y}-12-31")) for y in folds] if final is None else [final]
    for a, b in blocks:
        tr = rows[(rows.date < a - pd.Timedelta(days=gap_days))]
        te = rows[(rows.date >= a) & (rows.date <= b)]
        if len(tr) < 60 or len(te) == 0:
            continue
        bB = L.ols_fit(tr[base].to_numpy(), tr.y.to_numpy())
        bX = L.ols_fit(tr[base + extra].to_numpy(), tr.y.to_numpy())
        eB = te.y.to_numpy() - L.ols_pred(bB, te[base].to_numpy())
        eX = te.y.to_numpy() - L.ols_pred(bX, te[base + extra].to_numpy())
        preds.append(pd.DataFrame({"date": te.date.to_numpy(), "eB": eB, "eX": eX}))
    return pd.concat(preds, ignore_index=True) if preds else pd.DataFrame(columns=["date", "eB", "eX"])


def forecast_test(rows, base, extra, folds, lag, gap_days, final=None):
    pr = wf_predict(rows, base, extra, folds, gap_days, final)
    dd = (pr.eB ** 2 - pr.eX ** 2).to_numpy()
    gain, t = L.hac_mean_t(dd, lag)
    p_dm = L.one_sided_p(t)
    g = rng()
    tw = np.zeros(NTWIN)
    yrs = rows.date.dt.year.to_numpy()
    for w in range(NTWIN):
        r2 = rows.copy()
        for col in extra:
            v = r2[col].to_numpy().copy()
            for y in np.unique(yrs):
                m = yrs == y
                v[m] = g.permutation(v[m])
            r2[col] = v
        p2 = wf_predict(r2, base, extra, folds, gap_days, final)
        tw[w] = float(np.mean(p2.eB ** 2 - p2.eX ** 2)) if len(p2) else 0.0
    mseB = float(np.mean(pr.eB ** 2))
    s = {"n": int(len(dd)), "gain": float(gain), "gain_pct_of_mse": round(100 * gain / mseB, 2) if mseB else None,
         "dm_t": round(t, 2), "p_dm": p_dm, "twin_mean": float(tw.mean()), "twin_p": L.twin_p(gain, tw),
         "gain_wo_top5": float(L.without_top(dd, 5) / max(len(dd), 1)),
         "by_year": {str(y): round(float(np.mean(dd[pr.date.dt.year.to_numpy() == y])) * 1e3, 3)
                     for y in sorted(set(pr.date.dt.year))}}
    s["p"] = max(s["p_dm"], s["twin_p"])
    s["pass_design"] = bool(s["gain"] > 0 and s["gain_wo_top5"] > 0)
    return s


# ================================================================================ family C


def mcx_book(name):
    m = D.mcx_daily(name)
    rolls = L.detect_rolls(m.oi.to_numpy())
    o, h, l, c = L.back_adjust(m.open, m.high, m.low, m.close, rolls)
    return DailyBook(m.date, o, h, l, c, mcx_cost_fn(D.MCX_MULT[name])), rolls


def c_candidates(book, name, col, trig):
    cw = D.cot(name)
    f = L.cot_features(cw)
    sess = [d.date() for d in book.dates]
    ii, xs = [], []
    for r, x in zip(cw.report, f[col]):
        if not L.cot_usable(r.date()):
            continue
        i = L.cot_entry_index(r.date(), sess)
        if i is None:
            continue
        ii.append(i)
        xs.append(x)
    cand = pd.DataFrame({"i": ii, "x": xs}).drop_duplicates("i", keep="last")
    cand["trig"] = trig(cand.x.to_numpy())
    return cand


def family_c():
    res = {}
    for name in ("GOLD", "SILVER", "CRUDE"):
        book, rolls = mcx_book(name)
        for tid, col, centre, trig, hold, k in (
                (f"C1-{name}", "C1", 0.5, lambda x: (x <= 0.10) | (x >= 0.90), 20, 2.0),
                (f"C2-{name}", "C2", 0.0, lambda x: np.abs(x) >= 1.5, 5, 1.5)):
            cand = c_candidates(book, name, col, trig)
            cand = cand[book.dates[cand.i.to_numpy()] <= DESIGN_END["daily"]]
            s, _ = trade_test(book, cand, centre, (lambda i, h=hold: h), k)
            s["triggers"], s["rolls_per_year"] = int(cand.trig.sum()), round(len(rolls) / (len(book.c) / 250), 1)
            res[tid] = s
    return res


# ================================================================================ minutes: shared


class MinuteBook:
    def __init__(self, m: pd.DataFrame):
        self.m = m.reset_index(drop=True)
        self.o, self.h, self.l, self.c = (self.m[k].to_numpy(float) for k in ("open", "high", "low", "close"))
        self.mod = self.m["mod"].to_numpy()
        days = self.m.day.to_numpy()
        cut = np.r_[0, np.where(days[1:] != days[:-1])[0] + 1, len(days)]
        self.days = pd.DatetimeIndex(days[cut[:-1]])
        self.start, self.end = cut[:-1], cut[1:]

    def day_slice(self, k):
        return slice(self.start[k], self.end[k])


def vec_sim(B: MinuteBook, e, last, side, stop, target):
    """Vectorised sim_minutes over many trades (global indices e..last, same day). Returns exit idx, px, kind."""
    e, last, side = np.asarray(e), np.asarray(last), np.asarray(side)
    stop, target = np.asarray(stop, float), np.asarray(target, float)
    T = int((last - e).max()) + 1 if len(e) else 1
    J = e[:, None] + np.arange(T)[None, :]
    valid = J <= last[:, None]
    Jc = np.minimum(J, len(B.c) - 1)
    O, H, Lo = B.o[Jc], B.h[Jc], B.l[Jc]
    s, st, tg = side[:, None], stop[:, None], target[:, None]
    first = np.zeros_like(valid)
    first[:, 0] = True
    gap_st = (~first) & ((O - st) * s <= 0)
    touch_st = np.where(s > 0, Lo <= st, H >= st)
    hit_st = (gap_st | touch_st) & valid
    gap_tg = (~first) & ((O - tg) * s >= 0)
    touch_tg = np.where(s > 0, H >= tg, Lo <= tg)
    hit_tg = (gap_tg | touch_tg) & valid & np.isfinite(tg)
    any_ = hit_st | hit_tg
    has = any_.any(axis=1)
    j0 = np.where(has, any_.argmax(axis=1), 0)
    rows = np.arange(len(e))
    is_st = hit_st[rows, j0] & has
    px_st = np.where(gap_st[rows, j0], O[rows, j0], stop)
    px = np.where(has, np.where(is_st, px_st, target), B.c[last])
    xi = np.where(has, e + j0, last)
    kind = np.where(~has, "time", np.where(is_st, "stop", "target"))
    return xi, px, kind


def minute_cost(und, day, side, e, x, stopped):
    units = np.array([L.lot_on(LOT_SCHEDULE[und], d) for d in day])
    gross = side * (x - e) * units
    cost = np.array([L.nse_fut_cost(int(s), float(a), float(b), int(u), d, 0, bool(st))
                     for s, a, b, u, d, st in zip(side, e, x, units, day, stopped)])
    return gross, gross - cost


def one_at_a_time(e, xi):
    keep, busy = [], -1
    for k in np.argsort(e, kind="stable"):
        if e[k] > busy:
            keep.append(k)
            busy = xi[k]
    return np.array(sorted(keep), dtype=int)


# ================================================================================ family S


ROUND = {"NIFTY": 100.0, "BANKNIFTY": 500.0}


def s_levels(kind, und, prev_h, prev_l, o, h, l, mod):
    if kind == "S1":
        return [prev_h, prev_l]
    if kind == "S3":
        m = mod <= 569
        return [h[m].max(), l[m].min()] if m.any() else []
    step = ROUND[und]
    lo, hi = np.floor(l.min() / step) * step, np.ceil(h.max() / step) * step
    return list(np.arange(lo, hi + step / 2, step))


def s_events(B: MinuteBook, und, kind, upto=None, since=None):
    ev = []
    for k in range(1, len(B.days)):
        d = B.days[k]
        if upto is not None and d > upto:
            break
        if since is not None and d < since:
            continue
        sl = B.day_slice(k)
        o, h, l, c, mod = B.o[sl], B.h[sl], B.l[sl], B.c[sl], B.mod[sl]
        ps = B.day_slice(k - 1)
        lev = s_levels(kind, und, B.h[ps].max(), B.l[ps].min(), o, h, l, mod)
        if mod[0] > 560 or len(c) < 300:       # a late-starting or broken day
            continue
        try:
            i1515 = int(np.where(mod <= 915)[0][-1])
        except IndexError:
            continue
        for e in L.stop_run_events(mod, o, h, l, c, lev):
            ent = e["k"] + 1
            if ent > i1515:
                continue
            g0 = sl.start
            entry = o[ent]
            stop = e["extreme"] * (1 + 0.0002 * (-e["side"]))
            risk = (entry - stop) * e["side"]
            if not risk > 0:
                continue
            ev.append(dict(day=d, e=g0 + ent, last=g0 + min(ent + 59, i1515), side=e["side"], stop=stop,
                           target=entry + e["side"] * 2 * risk, entry=entry, stop_pct=risk / entry,
                           poke_mod=int(mod[e["poke"]])))
    return pd.DataFrame(ev)


def s_run(B, und, ev):
    if len(ev) == 0:
        return ev
    xi, px, kind = vec_sim(B, ev.e.to_numpy(), ev["last"].to_numpy(), ev.side.to_numpy(), ev.stop.to_numpy(),
                           ev.target.to_numpy())
    ev = ev.assign(xi=xi, px=px, kind=kind)
    keep = one_at_a_time(ev.e.to_numpy(), ev.xi.to_numpy())
    ev = ev.iloc[keep].reset_index(drop=True)
    gross, net = minute_cost(und, ev.day, ev.side.to_numpy(), ev.entry.to_numpy(), ev.px.to_numpy(),
                             (ev.kind == "stop").to_numpy())
    return ev.assign(gross=gross, net=net)


def s_twins(B, und, tr, n=NTWIN):
    """Same day, random entry minute 09:31-14:40, same side, same stop %, 2R target, 60 minutes or 15:15."""
    g = rng()
    out = np.zeros(n)
    dk = {d: k for k, d in enumerate(B.days)}
    info = []
    for _, t in tr.iterrows():
        k = dk[t.day]
        sl = B.day_slice(k)
        mod = B.mod[sl]
        cand = np.where((mod >= 571) & (mod <= 880))[0] + sl.start
        i1515 = sl.start + int(np.where(mod <= 915)[0][-1])
        info.append((cand, i1515))
    side = tr.side.to_numpy()
    sp = tr.stop_pct.to_numpy()
    units = np.array([L.lot_on(LOT_SCHEDULE[und], d) for d in tr.day])
    days = list(tr.day)
    for w in range(n):
        e = np.array([g.choice(c) for c, _ in info])
        last = np.minimum(e + 59, np.array([x for _, x in info]))
        entry = B.o[e]
        stop = entry * (1 - side * sp)
        target = entry + side * 2 * sp * entry
        xi, px, kind = vec_sim(B, e, last, side, stop, target)
        gross = side * (px - entry) * units
        cost = fast_cost(side, entry, px, units, days, kind == "stop")
        out[w] = float((gross - cost).sum())
    return out


def fast_cost(side, e, x, units, days, stopped):
    v_in, v_out = e * units, x * units
    sell = np.where(side > 0, v_out, v_in)
    buy = np.where(side > 0, v_in, v_out)
    stt = np.array([L.nse_fut_stt(d) for d in days]) * sell
    exch, sebi = 0.0000173 * (v_in + v_out), 1e-6 * (v_in + v_out)
    slip = L.FUT_SLIP * v_in + np.where(stopped, L.STOP_SLIP_NSE, L.FUT_SLIP) * v_out
    return 40.0 + exch + sebi + stt + 0.00002 * buy + 0.18 * (40.0 + exch + sebi) + slip


def minute_fold_summary(tr, tw, folds):
    nets = tr.net.to_numpy() if len(tr) else np.array([])
    s = summ(nets)
    yrs = pd.DatetimeIndex(tr.day).year if len(tr) else []
    s.update(twin_mean=round(float(tw.mean()), 0), twin_p=L.twin_p(float(nets.sum()), tw),
             by_year={str(y): round(float(nets[yrs == y].sum()), 0) for y in folds},
             n_by_year={str(y): int((yrs == y).sum()) for y in folds})
    s["p"] = s["twin_p"]
    s["pass_design"] = bool(s["n"] > 0 and s["net"] > 0 and s["net"] > s["twin_mean"] and s["net_wo_top5"] > 0)
    return s


def s_describe(B, ev_all):
    """Signed move 30/60 min after the event (fade direction, % of price) vs a random minute of the same day."""
    out = {}
    if len(ev_all) == 0:
        return out
    g = rng()
    for hzn in (30, 60):
        j = ev_all.e.to_numpy() + hzn
        ok = j < len(B.c)
        same = ok & (B.m.day.to_numpy()[np.minimum(j, len(B.c) - 1)] == ev_all.day.to_numpy())
        mv = ev_all.side.to_numpy() * (B.c[np.minimum(j, len(B.c) - 1)] / B.o[ev_all.e.to_numpy()] - 1)
        out[f"fade_move_{hzn}m_bps"] = round(float(np.mean(mv[same])) * 1e4, 2)
        out[f"abs_move_{hzn}m_bps"] = round(float(np.mean(np.abs(mv[same]))) * 1e4, 2)
        out[f"n_{hzn}"] = int(same.sum())
    return out


def family_s():
    res = {}
    for und in ("NIFTY", "BANKNIFTY"):
        mm = D.minutes(und)
        mm = mm[mm.day >= pd.Timestamp("2017-01-01")]
        B = MinuteBook(mm)
        for kind in ("S1", "S2", "S3"):
            ev = s_events(B, und, kind, upto=DESIGN_END["minute"])
            folds = FOLDS["minute"]
            evf = ev[pd.DatetimeIndex(ev.day).year.isin(folds)].reset_index(drop=True) if len(ev) else ev
            tr = s_run(B, und, evf)
            tw = s_twins(B, und, tr)
            s = minute_fold_summary(tr, tw, folds)
            s["events_all_design_years"] = int(len(ev))
            s["describe_folds"] = s_describe(B, evf)
            s["exit_kinds"] = tr.kind.value_counts().to_dict() if len(tr) else {}
            s["median_stop_pct"] = round(float(tr.stop_pct.median()) * 100, 3) if len(tr) else None
            res[f"{kind}-{und}"] = s
    return res


# ================================================================================ family V


def v_days(upto=None, since=None):
    """Per NIFTY day: EVR, HIGH flag, stretch s at 11:59, entry/target/stop and the 12:00-15:15 path indices."""
    mm = D.minutes("NIFTY")
    mm = mm[mm.day >= pd.Timestamp("2019-01-01")]
    tv = D.turnover_minutes()
    mm = mm.merge(tv, on=["day", "mod"], how="left")
    mm["tv"] = mm.tv.fillna(0.0)
    B = MinuteBook(mm)
    tvv = B.m.tv.to_numpy()
    rows = []
    for k in range(len(B.days)):
        sl = B.day_slice(k)
        mod = B.mod[sl]
        early = tvv[sl][(mod >= 555) & (mod <= 614)].sum()
        rows.append(dict(k=k, day=B.days[k], early=early, full=tvv[sl].sum()))
    d = pd.DataFrame(rows)
    d.loc[d.early <= 0, "early"] = np.nan
    med = d.early.shift(1).rolling(20, min_periods=15).median()
    d["evr"] = d.early / med
    d["full_ratio"] = d.full / d.full.shift(1).rolling(20, min_periods=15).median()
    thr = d.evr.shift(1).rolling(250, min_periods=120).quantile(0.67)
    d["high"] = d.evr >= thr
    s_, e_, last_, vw_ = [], [], [], []
    for k in range(len(B.days)):
        sl = B.day_slice(k)
        mod = B.mod[sl]
        i1159 = np.where(mod == 719)[0]
        i1200 = np.where(mod == 720)[0]
        i1515 = np.where(mod <= 915)[0]
        if len(i1159) == 0 or len(i1200) == 0 or tvv[sl][:i1159[0] + 1].sum() <= 0:
            s_.append(np.nan); e_.append(-1); last_.append(-1); vw_.append(np.nan)
            continue
        typ = (B.h[sl] + B.l[sl] + B.c[sl]) / 3
        vv = L.vwap(typ[:i1159[0] + 1], tvv[sl][:i1159[0] + 1])[-1]
        cl = B.c[sl][i1159[0]]
        s_.append((cl - vv) / cl); e_.append(sl.start + i1200[0]); last_.append(sl.start + i1515[-1]); vw_.append(vv)
    d["s"], d["e"], d["last"], d["vwap"] = s_, e_, last_, vw_
    d["c1159"] = [B.c[e - 1] if e > 0 else np.nan for e in d.e]
    if upto is not None:
        d = d[d.day <= upto]
    if since is not None:
        d = d[d.day >= since]
    return B, d.reset_index(drop=True)


def v_trades(B, d):
    d = d[(d.e > 0) & (d.s.abs() >= 0.0025)].copy()
    if len(d) == 0:
        return d
    side = -np.sign(d.s.to_numpy()).astype(int)
    entry = B.o[d.e.to_numpy()]
    dist = np.abs(d.c1159.to_numpy() - d.vwap.to_numpy())
    stop = entry - side * dist
    target = d.vwap.to_numpy()
    ok = (target - entry) * side > 0          # the 12:00 open must still be on the far side of VWAP
    d, side, entry, stop, target = d[ok], side[ok], entry[ok], stop[ok], target[ok]
    xi, px, kind = vec_sim(B, d.e.to_numpy(), d["last"].to_numpy(), side, stop, target)
    gross, net = minute_cost("NIFTY", d.day, side, entry, px, kind == "stop")
    return d.assign(side=side, entry=entry, px=px, kind=kind, gross=gross, net=net)


def family_v():
    B, d = v_days(upto=DESIGN_END["minute"])
    folds = FOLDS["minute"]
    df = d[d.day.dt.year.isin(folds)]
    allt = v_trades(B, df)
    tr = allt[allt.high.fillna(False).astype(bool)].reset_index(drop=True)
    g = rng()
    tw = np.zeros(NTWIN)
    yrs = allt.day.dt.year.to_numpy()
    tyr = tr.day.dt.year.to_numpy()
    for w in range(NTWIN):
        tot = 0.0
        for y in folds:
            pool = np.where(yrs == y)[0]
            k = int((tyr == y).sum())
            if k and len(pool):
                tot += allt.net.to_numpy()[g.choice(pool, size=min(k, len(pool)), replace=False)].sum()
        tw[w] = tot
    s = minute_fold_summary(tr.rename(columns={}), tw, folds)
    s["all_stretch_days"] = summ(allt.net)
    s["exit_kinds"] = tr.kind.value_counts().to_dict() if len(tr) else {}
    # descriptive: reversion slope by EVR tercile (design years 2019-2024)
    dd = d[(d.e > 0)].dropna(subset=["s", "evr"]).copy()
    dd["ret"] = B.c[dd["last"].to_numpy()] / B.o[dd.e.to_numpy()] - 1
    q = dd.evr.quantile([1 / 3, 2 / 3]).to_numpy()
    terc = {}
    for name, m in (("low", dd.evr <= q[0]), ("mid", (dd.evr > q[0]) & (dd.evr <= q[1])), ("high", dd.evr > q[1])):
        x = dd[m]
        b = L.ols_fit(x[["s"]].to_numpy(), x.ret.to_numpy())
        terc[name] = {"n": int(len(x)), "slope": round(float(b[1]), 3)}
    s["reversion_slope_by_evr_tercile"] = terc
    ok = d.dropna(subset=["evr", "full_ratio"])
    s["evr_vs_full_day_corr"] = {"n": int(len(ok)), "r": round(float(np.corrcoef(ok.evr, ok.full_ratio)[0, 1]), 3)}
    return {"V1": s}


# ================================================================================ family G


def g_rows(und):
    gx = D.gex(und)
    g44 = gx[gx.minute == 44].set_index("day")
    mm = D.minutes(und)
    mm = mm[mm.day >= pd.Timestamp("2021-07-01")]
    vix = D.minutes("INDIAVIX")
    v = vix[vix["mod"] == 599].set_index("day").close
    rows = []
    for day, x in mm.groupby("day"):
        mod = x["mod"].to_numpy()
        if (mod == 599).sum() == 0 or (mod >= 600).sum() < 200 or mod[0] > 560:
            continue
        c599 = x.close.to_numpy()[mod == 599][0]
        a = x[mod >= 600]
        b = x[mod <= 599]
        rows.append(dict(date=day, rod=(a.high.max() - a.low.min()) / c599, r0=(b.high.max() - b.low.min()) / c599,
                         o600=a.open.iloc[0], c599=c599))
    r = pd.DataFrame(rows).sort_values("date").reset_index(drop=True)
    r["prev20"] = r.rod.shift(1).rolling(20, min_periods=10).mean()
    r["lvix"] = np.log(v.reindex(r.date).to_numpy())
    r = r.merge(g44[["near_net_cr", "near_norm", "dte"]], left_on="date", right_index=True, how="inner")
    r["y"] = np.log(r.rod)
    r["lr0"] = np.log(r.r0)
    r["lprev"] = np.log(r.prev20)
    r["dte0"] = (r.dte == 0).astype(float)
    r["dte1"] = (r.dte == 1).astype(float)
    r["neg"] = (r.near_net_cr < 0).astype(float)
    r["gnorm"] = r.near_norm.clip(-5, 5).fillna(0.0)
    return r.replace([np.inf, -np.inf], np.nan).dropna(subset=["y", "lr0", "lprev", "lvix"]).reset_index(drop=True)


G_BASE, G_EXTRA = ["lvix", "lr0", "lprev", "dte0", "dte1"], ["neg", "gnorm"]


def family_g():
    res = {}
    for und in ("NIFTY", "BANKNIFTY"):
        r = g_rows(und)
        rd = r[r.date <= DESIGN_END["gex"]]
        s = forecast_test(rd, G_BASE, G_EXTRA, FOLDS["gex"], lag=1, gap_days=1)
        s["calibration"] = g_calibration(rd)
        res[f"G1-{und}"] = s
    return res


def g_calibration(rows, folds=None):
    """Hit rate of a +-0.5 x forecast range bracket by forecast fifth (descriptive; the range touches both if
    range >= forecast)."""
    pr_rows = []
    for y in (folds or FOLDS["gex"]):
        a = pd.Timestamp(f"{y}-01-01")
        tr, te = rows[rows.date < a], rows[(rows.date >= a) & (rows.date.dt.year == y)]
        if len(tr) < 60 or len(te) == 0:
            continue
        for lab, cols in (("B", G_BASE), ("BX", G_BASE + G_EXTRA)):
            b = L.ols_fit(tr[cols].to_numpy(), tr.y.to_numpy())
            f = np.exp(L.ols_pred(b, te[cols].to_numpy()))
            pr_rows.append(pd.DataFrame({"m": lab, "f": f, "rod": te.rod.to_numpy()}))
    if not pr_rows:
        return {}
    p = pd.concat(pr_rows)
    out = {}
    for lab, x in p.groupby("m"):
        x = x.copy()
        x["q"] = pd.qcut(x.f, 5, labels=False, duplicates="drop")
        out[lab] = {"share_range_above_forecast_by_fifth": [round(float((z.rod >= z.f).mean()), 3) for _, z in x.groupby("q")],
                    "n": int(len(x))}
    return out


# ================================================================================ family E


def e1_candidates(book):
    b = D.nifty_fut_oi()
    exps = sorted(b.expiry.unique())
    dates = book.dates
    recs = []
    for X in exps:
        X = pd.Timestamp(X)
        if X not in dates:
            continue
        prev = b[b.date < X].date.max()
        if pd.isna(prev):
            continue
        day = b[b.date == prev].sort_values("expiry")
        day = day[day.expiry >= X]
        if len(day) < 2 or day.expiry.iloc[0] != X:
            continue
        tot = day.oi_qty.sum()
        recs.append(dict(X=X, R=day.oi_qty.iloc[1:].sum() / tot if tot > 0 else np.nan, i=int(dates.get_loc(X))))
    e = pd.DataFrame(recs).sort_values("X").reset_index(drop=True)
    m = e.R.shift(1).rolling(12, min_periods=9).mean()
    s = e.R.shift(1).rolling(12, min_periods=9).std()
    e["x"] = (e.R - m) / s
    e["nexti"] = e.i.shift(-1)
    e = e.dropna(subset=["nexti"])
    e["nexti"] = e.nexti.astype(int)
    e["trig"] = e.x.abs() >= 1
    return e


def family_e():
    res = {}
    book, d = nifty_book()
    e = e1_candidates(book)
    hold = dict(zip(e.i, e.nexti - e.i + 1))
    med_hold = int(np.median(list(hold.values())))
    hold_of = lambda i: int(hold.get(i, med_hold))
    ed = e[e.X <= DESIGN_END["daily"]]
    s, _ = trade_test(book, ed[["i", "x", "trig"]], 0.0, hold_of, 2.0)
    s["expiries"] = int(len(ed))
    res["E1"] = s
    for name in ("GOLD", "SILVER"):
        res[f"E2-{name}"] = e2_test(name, None, DESIGN_END["daily"])
    return res


def e2_test(name, since, upto, design_side=None):
    book, rolls = mcx_book(name)
    c = book.c
    ret = np.abs(np.r_[np.nan, c[1:] / c[:-1] - 1])
    dates = book.dates
    inside = np.zeros(len(c), bool)
    for k in rolls:
        inside[max(0, k - 5):k] = True
    m = np.ones(len(c), bool)
    m[0] = False
    m &= (dates >= (since or FIT_START)) & (dates <= upto)
    ratio = float(np.nanmean(ret[m & inside]) / np.nanmean(ret[m & ~inside]))
    g = rng()
    yrs = dates.year.to_numpy()
    tw = np.zeros(NTWIN)
    nwin = {y: int(sum(1 for k in rolls if dates[k].year == y and m[k])) for y in np.unique(yrs[m])}
    for w in range(NTWIN):
        ins = np.zeros(len(c), bool)
        for y, kk in nwin.items():
            pos = np.where(m & (yrs == y))[0]
            if len(pos) < 6 or kk == 0:
                continue
            for st in g.choice(pos[:-5], size=kk, replace=False):
                ins[st:st + 5] = True
        tw[w] = np.nanmean(ret[m & ins]) / np.nanmean(ret[m & ~ins])
    side = design_side or (1 if ratio > 1 else -1)
    p = L.twin_p(ratio, tw) if side > 0 else L.twin_p(-ratio, -tw)
    vin = np.sort(ret[m & inside][~np.isnan(ret[m & inside])])
    r_wo = float(np.mean(vin[:-5]) / np.nanmean(ret[m & ~inside])) if len(vin) > 5 else np.nan
    by_year = {}
    for y in np.unique(yrs[m]):
        mm = m & (yrs == y)
        a, b = np.nanmean(ret[mm & inside]), np.nanmean(ret[mm & ~inside])
        by_year[str(y)] = round(float(a / b), 3) if b > 0 and np.isfinite(a) else None
    s = {"n_inside": int((m & inside).sum()), "n_outside": int((m & ~inside).sum()), "ratio": round(ratio, 3),
         "twin_mean": round(float(tw.mean()), 3), "p": p, "twin_p": p, "ratio_wo_top5": round(r_wo, 3),
         "side": side, "by_year": by_year,
         "years_same_side": int(sum(1 for v in by_year.values() if v is not None and (v - 1) * side > 0)),
         "n_years": int(sum(1 for v in by_year.values() if v is not None))}
    s["pass_design"] = bool((r_wo - 1) * side > 0)
    return s


# ================================================================================ main


def gate(results):
    ids = list(results)
    q = L.bh([results[k]["p"] for k in ids])
    for k, qq in zip(ids, q):
        r = results[k]
        r["bh_q"] = qq
        r["passes"] = bool(r["pass_design"] and r["p"] < 0.10 and qq < 0.10)
    return results


def design():
    OUT.mkdir(parents=True, exist_ok=True)
    res = {}
    for fam in (family_p, family_c, family_e, family_g, family_v, family_s):
        print("running", fam.__name__, flush=True)
        res.update(fam())
        (OUT / "design_partial.json").write_text(json.dumps(res, indent=1, default=str))
    res = gate(res)
    (OUT / "design.json").write_text(json.dumps(res, indent=1, default=str))
    (OUT / "design_partial.json").unlink(missing_ok=True)
    for k, r in res.items():
        print(f"{k:16s} n={r.get('n', r.get('n_inside'))} net/gain={r.get('net', r.get('gain', r.get('ratio')))} "
              f"p={r['p']:.3f} q={r['bh_q']:.3f} pass={r['passes']}")
    return res


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd == "design":
        design()
    elif cmd == "final":
        from research.hunt.r86 import r86final
        r86final.main()
    else:
        raise SystemExit(__doc__)
