"""R19 (PREREG.md): Liquidity 15+5 profit lock on BANKNIFTY 15m / 5m, real option minutes from the research-data release.

    python3 -I research/hunt/r19/run.py design <release dir> <out dir>   # design + walk-forward; writes results/design.json
    python3 -I research/hunt/r19/run.py hold   <release dir> <out dir>   # ONCE: writes <out>/HOLDOUT_READ.flag first
    python3 -I research/hunt/r19/run.py today                            # Boss's 13 Oct MIDCPNIFTY trade (illustration)

<release dir> holds banknifty_prev_year_wide.parquet and banknifty_year_wide.parquet (`gh release download research-data`).
Caches (day arrays, signals) go to <out dir>; the result tables go to research/hunt/r19/results.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sim as S  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, ROOT)

RESULTS = os.path.join(HERE, "results")
UND = "BANKNIFTY"
BOOKS = (15, 5)
HOLD_FROM, HOLD_TO = date(2024, 8, 1), date(2025, 2, 14)
FOLDS = [("2024Q1", date(2024, 2, 13), date(2024, 3, 31)), ("2024Q2", date(2024, 4, 1), date(2024, 6, 30)),
         ("2024Jul", date(2024, 7, 1), date(2024, 7, 31)), ("2025Q1", date(2025, 2, 17), date(2025, 3, 31)),
         ("2025Q2", date(2025, 4, 1), date(2025, 6, 30)), ("2025Q3", date(2025, 7, 1), date(2025, 9, 30)),
         ("2025Q4", date(2025, 10, 1), date(2025, 12, 31)), ("2026Q1", date(2026, 1, 1), date(2026, 2, 23))]
LOTS = [(date(2000, 1, 1), 25), (date(2023, 7, 21), 15), (date(2024, 11, 28), 30), (date(2025, 6, 27), 35)]
TWINS, SEED, BH_Q = 1000, 19, 0.10
LOCKS = [v for v in S.VARIANTS if v.kind != "none"]


def lot_on(d):
    out = 0
    for s, n in LOTS:
        if d >= s:
            out = n
    return out


def in_hold(d):
    return HOLD_FROM <= d <= HOLD_TO


# ------------------------------------------------------------------------------------------------ data
def load_days(rel, out):
    p = os.path.join(out, "r19_days.pkl")
    if os.path.exists(p):
        with open(p, "rb") as f:
            return pickle.load(f)
    from research.brain.adapters.release import ReleaseAdapter
    ad = ReleaseAdapter(rel)
    ss = ad.sessions(UND)
    days = []
    for dd in ad.load(UND, ss[0], ss[-1]):
        t0 = pd.Timestamp(dd.day) + pd.Timedelta(hours=9, minutes=15)
        ixd = dd.index.sort_values("ts")
        m = ((pd.to_datetime(ixd.ts) - t0).dt.total_seconds() // 60).astype(int).values
        ok = (m >= 0) & (m < S.N)
        raw = [(dd.day, int(mm) + S.OPEN_M, float(o), float(h), float(lo), float(c))
               for mm, o, h, lo, c in zip(m[ok], ixd.open.values[ok], ixd.high.values[ok], ixd.low.values[ok], ixd.close.values[ok])]
        if len(raw) < 300:
            continue
        ix = np.full((S.N, 4), np.nan)
        for b in raw:
            ix[b[1] - S.OPEN_M] = b[2:]
        c = pd.Series(ix[:, 3]).ffill().bfill().values
        for j in range(3):
            ix[:, j] = np.where(np.isfinite(ix[:, j]), ix[:, j], c)
        ix[:, 3] = c
        o = dd.options
        exps = sorted(e for e in o.expiry.unique() if e > dd.day) if len(o) else []
        opts = {}
        if exps:
            g = o[o.expiry == exps[0]]
            for (k, sd), gg in g.groupby(["strike", "side"]):
                mm = ((pd.to_datetime(gg.ts) - t0).dt.total_seconds() // 60).astype(int).values
                okk = (mm >= 0) & (mm < S.N)
                a = np.full((S.N, 5), np.nan)
                a[mm[okk]] = gg[["open", "high", "low", "close", "volume"]].values[okk].astype(float)
                opts[(int(round(k)), sd)] = a
        days.append(dict(day=dd.day, raw=raw, ix=ix, opts=opts, expiry=exps[0] if exps else None))
    with open(p, "wb") as f:
        pickle.dump(days, f, protocol=4)
    return days


def all_signals(days, out):
    p = os.path.join(out, "r19_signals.pkl")
    if os.path.exists(p):
        with open(p, "rb") as f:
            return pickle.load(f)
    rows = []
    hist = []
    for d in days:
        for tf in BOOKS:
            rows += S.day_signals(hist, d["day"], d["raw"], tf, UND)
        hist.append((d["day"], d["raw"]))
        hist = [h for h in hist if (d["day"] - h[0]).days <= 12]
    with open(p, "wb") as f:
        pickle.dump(rows, f, protocol=4)
    return rows


# ------------------------------------------------------------------------------------------------ trades
def build_trades(days, sigs, keep_day):
    """Every variant's result for the control's positions (one per book; the lock never adds or moves an entry).
    Also counts the signals a variant's earlier exit would have freed (information only)."""
    by_day = {d["day"]: d for d in days}
    out = []
    missing = 0
    freed = {v.id: 0 for v in LOCKS}
    sg = sorted([s for s in sigs if keep_day(s["day"])], key=lambda s: (s["book"], s["day"], s["gate_row"]))
    cur, flat = None, 0
    fl_var = {}
    for s in sg:
        k = (s["book"], s["day"])
        if k != cur:
            cur, flat, fl_var = k, 0, {v.id: 0 for v in LOCKS}
        d = by_day[s["day"]]
        side = "C" if s["side"] > 0 else "P"
        opt = d["opts"].get((s["strike"], side))
        if s["gate_row"] < flat:
            for v in LOCKS:                          # would the variant's book be flat here? (info only)
                if s["gate_row"] >= fl_var[v.id]:
                    freed[v.id] += 1
            continue
        if opt is None:
            missing += 1
            continue
        lot = lot_on(s["day"])
        en = S.entry(opt, s["gate_row"], lot)
        if en is None:
            missing += 1
            continue
        c0, e = en
        tick_in = S.ticks_for(S.vol5_lots(opt, c0, lot))
        base = S.run_trade(opt, d["ix"], s, c0, e, lot, lot, S.BY_ID["d"])
        flat = base["row"] + 1
        rec = dict(day=s["day"], book=s["book"], side=s["side"], c0=c0, e=e, qty=lot, tick_in=tick_in,
                   target=s["target"], sig=s, mfe=base["peak"] - e)
        rec["d"] = base
        for v in LOCKS:
            r = S.run_trade(opt, d["ix"], s, c0, e, lot, lot, v)
            rec[v.id] = r
            fl_var[v.id] = r["row"] + 1
        out.append(rec)
    return out, missing, freed


def net_of(t, vid, mult=1.0):
    r = t[vid]
    if mult == 1.0:
        return r["net"]
    return S.wide_spread_net(dict(net=r["net"], tick_in=t["tick_in"], tick_out=r["tick_out"], stop=r["stop"], qty=t["qty"]), mult)


def daily(trades, sessions, vid, book=None, mult=1.0):
    s = {}
    for t in trades:
        if book and t["book"] != book:
            continue
        s[t["day"]] = s.get(t["day"], 0.0) + net_of(t, vid, mult)
    return np.array([s.get(d, 0.0) for d in sessions])


def maxdd(x):
    eq = np.cumsum(x)
    return float((eq - np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:]).min()) if len(x) else 0.0


def one_sided_p(delta):
    from scipy import stats
    if len(delta) < 3 or np.allclose(delta, 0):
        return 1.0
    t, p2 = stats.ttest_1samp(delta, 0.0)
    return float(p2 / 2 if t > 0 else 1 - p2 / 2)


def measures(trades, sessions, vid, book):
    tt = [t for t in trades if t["book"] == book]
    x = daily(trades, sessions, vid, book)
    c = daily(trades, sessions, "d", book)
    dl = x - c
    pos = [t for t in tt if t["mfe"] > 0]
    cap = sum(t[vid]["fill"] - t["e"] for t in pos) / max(sum(t["mfe"] for t in pos), 1e-9)
    cut = [t for t in tt if t["d"]["net"] > 0 and t[vid]["net"] < t["d"]["net"] - 1e-6]
    saved = [t for t in tt if t[vid]["net"] > t["d"]["net"] + 1e-6]
    return dict(trades=len(tt), sessions=len(sessions), net=float(x.sum()), delta=float(dl.sum()),
                per_session=float(x.mean()) if len(x) else 0.0, worst_day=float(x.min()) if len(x) else 0.0,
                maxdd=maxdd(x), mfe_capture=float(cap), mfe_n=len(pos), winners_cut=len(cut),
                winners_cut_rs=float(sum(t[vid]["net"] - t["d"]["net"] for t in cut)), trades_helped=len(saved),
                trades_helped_rs=float(sum(t[vid]["net"] - t["d"]["net"] for t in saved)),
                lock_exits=sum(t[vid]["why"] == "lock" for t in tt),
                delta_wide=float((daily(trades, sessions, vid, book, 1.5) - daily(trades, sessions, "d", book, 1.5)).sum()),
                p_one_sided=one_sided_p(dl) if vid != "d" else None)


def fold_of(d):
    for name, a, b in FOLDS:
        if a <= d <= b:
            return name
    return None


def walk_forward(trades, sessions, book):
    """Anchored: from fold 2 on, use the variant (control included) with the best summed delta over the earlier folds
    (ties: control); return the out-of-fold deltas."""
    ids = [v.id for v in S.VARIANTS]
    fd = {}
    for name, a, b in FOLDS:
        ss = [d for d in sessions if a <= d <= b]
        c = daily(trades, ss, "d", book)
        fd[name] = {v: float((daily(trades, ss, v, book) - c).sum()) for v in ids}
    out = []
    for k in range(1, len(FOLDS)):
        past = {v: sum(fd[FOLDS[j][0]][v] for j in range(k)) for v in ids}
        best = max(ids, key=lambda v: (round(past[v], 6), v == "d"))
        out.append(dict(fold=FOLDS[k][0], pick=best, delta=fd[FOLDS[k][0]][best]))
    return fd, out


def bh(p):
    p = np.asarray(p, dtype=float)
    n = len(p)
    o = np.argsort(p)
    q = np.empty(n)
    q[o] = np.minimum.accumulate((p[o] * n / np.arange(1, n + 1))[::-1])[::-1]
    return np.minimum(q, 1.0)


def twins(trades, days, vid, book, rng, n=TWINS):
    """Random twins (PREREG G4): k random trades of the book left early at the lock's own exit delays, shuffled."""
    by_day = {d["day"]: d for d in days}
    tt = [t for t in trades if t["book"] == book]
    lk = [t for t in tt if t[vid]["why"] == "lock"]
    k = len(lk)
    if k == 0:
        return np.zeros(n), 0
    delays = np.array([t[vid]["row"] - t["c0"] for t in lk])
    cache = {}

    def twin_delta(i, dl):
        key = (i, int(dl))
        if key not in cache:
            t = tt[i]
            d = by_day[t["day"]]
            side = "C" if t["side"] > 0 else "P"
            opt = d["opts"][(t["sig"]["strike"], side)]
            r = S.run_trade(opt, d["ix"], t["sig"], t["c0"], t["e"], t["qty"], t["qty"], S.BY_ID["d"],
                            force_exit_row=t["c0"] + int(dl))
            cache[key] = r["net"] - t["d"]["net"]
        return cache[key]

    out = np.empty(n)
    for b in range(n):
        pick = rng.choice(len(tt), size=k, replace=False)
        dls = rng.permutation(delays)
        out[b] = sum(twin_delta(int(i), dl) for i, dl in zip(pick, dls))
    return out, k


# ------------------------------------------------------------------------------------------------ stages
def design(rel, out):
    days = load_days(rel, out)
    sigs = all_signals(days, out)
    sessions = [d["day"] for d in days if not in_hold(d["day"])]
    trades, missing, freed = build_trades(days, sigs, lambda d: not in_hold(d))
    res = dict(stage="design", sessions=len(sessions), first=str(sessions[0]), last=str(sessions[-1]),
               trades=len(trades), missing_contract=missing, freed_signals=freed, books={})
    for tf in BOOKS:
        b = f"{tf}m"
        fd, wf = walk_forward(trades, sessions, b)
        res["books"][b] = dict(
            variants={v.id: measures(trades, sessions, v.id, b) for v in S.VARIANTS},
            folds=fd, walk_forward=wf, wf_delta=float(sum(w["delta"] for w in wf)),
            folds_positive={v.id: int(sum(fd[f][v.id] > 0 for f, _, _ in FOLDS)) for v in LOCKS})
    res["why_counts"] = {v.id: pd.Series([t[v.id]["why"] for t in trades]).value_counts().to_dict() for v in S.VARIANTS}
    os.makedirs(RESULTS, exist_ok=True)
    with open(os.path.join(RESULTS, "design.json"), "w") as f:
        json.dump(res, f, indent=1, default=str)
    return res


def hold(rel, out):
    flag = os.path.join(out, "HOLDOUT_READ.flag")
    if os.path.exists(flag):
        sys.exit("R19 holdout already read once: " + open(flag).read())
    with open(os.path.join(RESULTS, "design.json")) as f:
        des = json.load(f)
    with open(flag, "w") as f:
        f.write(pd.Timestamp.now().isoformat())
    days = load_days(rel, out)
    sigs = all_signals(days, out)
    sessions = [d["day"] for d in days if in_hold(d["day"])]
    trades, missing, freed = build_trades(days, sigs, in_hold)
    rng = np.random.default_rng(SEED)
    rows = []
    res = dict(stage="holdout", sessions=len(sessions), first=str(sessions[0]), last=str(sessions[-1]), trades=len(trades),
               missing_contract=missing, freed_signals=freed, books={})
    for tf in BOOKS:
        b = f"{tf}m"
        ms = {v.id: measures(trades, sessions, v.id, b) for v in S.VARIANTS}
        res["books"][b] = dict(variants=ms)
        for v in LOCKS:
            tw, k = twins(trades, days, v.id, b, rng)
            dv = ms[v.id]["delta"]
            p_tw = float((1 + (tw >= dv).sum()) / (len(tw) + 1)) if k else 1.0
            dz = des["books"][b]
            rows.append(dict(book=b, variant=v.id, design_delta=dz["variants"][v.id]["delta"],
                             design_folds_pos=dz["folds_positive"][v.id], wf_book=dz["wf_delta"],
                             hold_delta=dv, p=ms[v.id]["p_one_sided"], twin_p=p_tw, twin_k=k,
                             twin_median=float(np.median(tw)) if k else 0.0,
                             worst_day=ms[v.id]["worst_day"], worst_day_ctrl=ms["d"]["worst_day"],
                             maxdd=ms[v.id]["maxdd"], maxdd_ctrl=ms["d"]["maxdd"], delta_wide=ms[v.id]["delta_wide"]))
    R = pd.DataFrame(rows)
    R["bh_q"] = bh(R.p.values)
    R["G1"] = (R.design_delta > 0) & (R.design_folds_pos >= len(FOLDS) / 2)
    R["G2"] = R.wf_book > 0
    R["G3"] = (R.hold_delta > 0) & (R.bh_q <= BH_Q)
    R["G4"] = R.twin_p < 0.05
    R["G5"] = (R.worst_day >= R.worst_day_ctrl - 1e-6) & (R.maxdd >= R.maxdd_ctrl - 1e-6)
    R["G6"] = R.delta_wide > 0
    R["PASS"] = R[["G1", "G2", "G3", "G4", "G5", "G6"]].all(axis=1)
    res["gates"] = R.to_dict(orient="records")
    res["why_counts"] = {v.id: pd.Series([t[v.id]["why"] for t in trades]).value_counts().to_dict() for v in S.VARIANTS}
    with open(os.path.join(RESULTS, "holdout.json"), "w") as f:
        json.dump(res, f, indent=1, default=str)
    pd.set_option("display.width", 250)
    print(R.to_string())
    return res


def today(write=True):
    """Boss's 13 Oct trade (Liquidity 5m MIDCPNIFTY PE): fill 195.45, best about +21 a unit, out 188.25 on the index
    stop; 1 lot of 120. Premium-only variants are worked out on those three prices (lock fills at the lock, no slippage,
    as the -864 Boss quoted is (188.25 - 195.45) x 120 before charges). The index ladder needs the index path and the
    trade's target level, which are not in the release: it is reported as unknown."""
    e, best, xit, qty = 195.45, 195.45 + 21.0, 188.25, 120
    be = S.be_level(e, qty)
    out = {"d": dict(exit=xit, gross=round((xit - e) * qty))}
    for v in LOCKS:
        if v.kind == "index":
            out[v.id] = dict(exit=None, gross=None, note="needs the index path and the target level (not in the release)")
            continue
        lv = v.level(e, be, best, None)
        x = xit if lv is None else round(np.ceil(lv / S.TICK - 1e-6) * S.TICK, 2)
        out[v.id] = dict(exit=x, gross=round((x - e) * qty), lock=None if lv is None else round(lv, 2))
    if write:
        os.makedirs(RESULTS, exist_ok=True)
        with open(os.path.join(RESULTS, "today.json"), "w") as f:
            json.dump(dict(breakeven_after_charges=round(be, 2), variants=out), f, indent=1)
        print(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "today":
        today()
    else:
        rel, out = sys.argv[2], sys.argv[3]
        os.makedirs(out, exist_ok=True)
        r = (design if mode == "design" else hold)(rel, out)
        if mode == "design":
            for b, x in r["books"].items():
                print(b, "wf", round(x["wf_delta"]), [(w["fold"], w["pick"], round(w["delta"])) for w in x["walk_forward"]])
                for vid, m in x["variants"].items():
                    print(f"  {vid}: n={m['trades']} net={m['net']:+,.0f} delta={m['delta']:+,.0f} p={m['p_one_sided']} "
                          f"cap={m['mfe_capture']:.2f} cut={m['winners_cut']} locks={m['lock_exits']} dd={m['maxdd']:,.0f} "
                          f"worst={m['worst_day']:,.0f} folds+={x['folds_positive'].get(vid)}")
