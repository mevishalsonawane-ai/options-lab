"""h15 stage 2: Liquidity 15+5 from Rs 1,00,000 with compounding (see PREREG.md).

    OBUY_CACHE=<scratch>/hunt/h15/cache flock <scratch>/obuy.lock python3 -I research/hunt/h15/cap1l.py pre
    OBUY_CACHE=<scratch>/hunt/h15/cache flock <scratch>/obuy.lock python3 -I research/hunt/h15/cap1l.py hold   # ONCE
"""
from __future__ import annotations

import json
import os
import pickle
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
from obuy import overfit as OF  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = os.path.join(C.CACHE, "h15")
UNDS = ["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"]
NMAX = np.array([100, 3, 11])
NC = 101
START, RUIN, TARGET = 100_000.0, 25_000.0, 5_000.0
HOLD, REG, LONG = pd.Timestamp("2025-10-01"), pd.Timestamp("2024-12-01"), pd.Timestamp("2023-06-01")
CAL = pd.read_csv(os.path.join(C.SCRATCH, "hunt/h4/cache/h4/daily_real.csv"), index_col=0, parse_dates=True).index
BOOKS = {"A": (0, 1, 2), "B": (0, 2), "C": (0,)}
SIZINGS = ["F1", "FL", "R2", "R4", "K4", "K2"]
SIMPLE = {s: i for i, s in enumerate(SIZINGS)}
HORIZON = 60
NPATH = 5000
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)


# ------------------------------------------------------------------------------------------------ tables
class Tab:
    """All trades of one kind (real or pool) and one run, as flat arrays; lots axis 0..100 (0 = nothing)."""

    def __init__(self, kind, run):
        with open(os.path.join(OUT, f"tab_{kind}.pkl"), "rb") as f:
            T = pickle.load(f)
        metas, arrs = [], {k: [] for k in ("net", "gross", "prem", "end", "lots")}
        for ui, u in enumerate(UNDS):
            m, d = T[u]
            m = m.copy()
            m["ui"] = ui
            metas.append(m)
            a = d[run]
            for k in arrs:
                x = np.full((len(m), NC), np.nan)
                x[:, :a[k].shape[1]] = a[k]
                x[:, 0] = 0.0
                arrs[k].append(x)
        M = pd.concat(metas, ignore_index=True)
        o = np.lexsort((M.entry_min.values, M.day.values.astype("datetime64[D]").astype(np.int64)))
        self.M = M.iloc[o].reset_index(drop=True)
        for k in arrs:
            setattr(self, k.upper(), np.concatenate(arrs[k])[o])
        self.END = np.nan_to_num(self.END, nan=0.0)
        self.PREM = np.where(np.isnan(self.PREM), np.inf, self.PREM)
        self.PREM[:, 0] = 0.0
        self.ui = self.M.ui.values
        self.p1 = (self.M.entry.values * self.M.lot.values).astype(float)
        self.dord = self.M.day.values.astype("datetime64[D]").astype(np.int64)
        self.kin = self.dord * 2000 + self.M.entry_min.values
        self.month = pd.DatetimeIndex(self.M.day).to_period("M").astype(str).values


def kelly(R):
    fs = np.linspace(0, 3, 3001)
    R = R[np.isfinite(R)]
    g = [np.mean(np.log1p(np.maximum(f * R, -0.999999))) for f in fs]
    return float(fs[int(np.argmax(g))])


def kelly_fracs(T):
    m = ((T.M.day >= REG) & (T.M.day < HOLD)).values & (T.LOTS[:, 1] > 0)
    R = T.NET[:, 1] / np.where(T.PREM[:, 1] > 0, T.PREM[:, 1], np.nan)
    return np.array([kelly(R[m & (T.ui == u)]) for u in range(3)])


def n_rule(siz, E, p1, ui, kf):
    E = np.asarray(E, float)
    if siz == "F1":
        n = np.ones_like(E)
    elif siz == "FL":
        n = np.floor(E / 100_000.0)
    elif siz in ("R2", "R4"):
        f = 0.02 if siz == "R2" else 0.04
        n = np.floor(f * E / (0.15 * p1))
    else:
        x = 0.25 if siz == "K4" else 0.5
        n = np.floor(x * kf[ui] * E / p1)
    n = np.where(n < 1, np.where(p1 <= 0.35 * E, 1.0, 0.0), n)
    return np.minimum(n, NMAX[ui])


# ------------------------------------------------------------------------------------------------ vectorised walk
def month_slots(T, months, books, idx_map=None):
    """per month label: array of trade rows (time-ordered) restricted to books."""
    keep = np.isin(T.ui, books)
    out = {}
    for mth in months:
        out[mth] = np.nonzero(keep & (T.month == mth))[0]
    return out


def walk(T, seqm, slots, books, siz, kf, days, Estar=np.inf, alt=None, rng=None, start=START):
    """seqm: (P, H) month labels per path. slots: dict month -> trade rows. alt: optional (TP, choices) to replace each
    real row r by a random pool row from choices[r] (random-entry baseline). Returns dict of per-month arrays."""
    P, H = seqm.shape
    E = np.full(P, start)
    G = np.zeros(P)
    minE = E.copy()
    Em = np.zeros((P, H))
    Gm = np.zeros((P, H))
    dm = np.zeros((P, H))
    hit = np.full(P, -1)
    skipped = np.zeros(P)
    taken = np.zeros(P)
    rows_all = np.arange(P)
    for h in range(H):
        labs = seqm[:, h]
        L = max(len(slots[x]) for x in np.unique(labs))
        S = np.full((P, L), -1)
        for x in np.unique(labs):
            r = slots[x]
            S[labs == x, :len(r)] = r
        dm[:, h] = [days[x] for x in labs]
        oprem = np.zeros((P, 3))
        onet = np.zeros((P, 3))
        ogr = np.zeros((P, 3))
        oend = np.full((P, 3), np.inf)
        for s in range(L):
            r = S[:, s]
            v = r >= 0
            if not v.any():
                continue
            rr = np.where(v, r, 0)
            if alt is None:
                src, ix = T, rr
            else:
                TP, ch = alt
                ix = np.array([ch[q][rng.integers(len(ch[q]))] if len(ch[q]) else -1 for q in rr])
                v = v & (ix >= 0)
                ix = np.where(ix >= 0, ix, 0)
                src = TP
            kin = src.kin[ix]
            # release closed positions (exit key <= this entry key)
            done = oend <= kin[:, None]
            E += (onet * done).sum(1)
            G += (ogr * done).sum(1)
            oprem[done], onet[done], ogr[done], oend[done] = 0.0, 0.0, 0.0, np.inf
            minE = np.minimum(minE, E)
            u = src.ui[ix]
            busy = np.isfinite(oend[rows_all, u])
            v = v & ~busy
            n = n_rule(siz, E, src.p1[ix], u, kf)
            cash = E - oprem.sum(1)
            ns = np.arange(NC)[None, :]
            ok = (ns <= n[:, None]) & (src.PREM[ix] + 100.0 <= cash[:, None])
            na = np.where(ok, ns, 0).max(1)
            want = v & (n > 0)
            skipped += want & (na == 0)
            go = v & (na > 0)
            taken += go
            gi = np.nonzero(go)[0]
            if len(gi):
                k = na[gi]
                oprem[gi, u[gi]] = src.PREM[ix[gi], k]
                onet[gi, u[gi]] = src.NET[ix[gi], k]
                ogr[gi, u[gi]] = src.GROSS[ix[gi], k]
                oend[gi, u[gi]] = src.dord[ix[gi]] * 2000 + src.END[ix[gi], k]
        E += onet.sum(1)
        G += ogr.sum(1)
        minE = np.minimum(minE, E)
        Em[:, h] = E
        Gm[:, h] = G
        hit = np.where((hit < 0) & (E >= Estar), h + 1, hit)
    return dict(Em=Em, Gm=Gm, dm=dm, minE=minE, hit=hit, skipped=skipped, taken=taken)


# ------------------------------------------------------------------------------------------------ scalar replay
def replay(T, lo, hi, books, siz, kf, start=START):
    """Chronological single path; returns trades taken (with lots, net, gross, prem) and daily net/gross/capital."""
    m = ((T.M.day >= lo) & (T.M.day < hi)).values & np.isin(T.ui, books)
    E = start
    open_ = {}  # ui -> (end key, net, gross, prem)
    rec = []
    for r in np.nonzero(m)[0]:
        kin = T.kin[r]
        for b in list(open_):
            if open_[b][0] <= kin:
                E += open_[b][1]
                del open_[b]
        u = T.ui[r]
        if u in open_:
            continue
        n = float(n_rule(siz, np.array([E]), np.array([T.p1[r]]), np.array([u]), kf)[0])
        cash = E - sum(x[3] for x in open_.values())
        ns = np.arange(NC)
        ok = (ns <= n) & (T.PREM[r] + 100.0 <= cash)
        k = int(np.where(ok, ns, 0).max())
        rec.append(dict(row=r, day=T.M.day.iloc[r], und=UNDS[u], E_before=E, want=n, lots=k,
                        net=T.NET[r, k] if k else 0.0, gross=T.GROSS[r, k] if k else 0.0,
                        prem=T.PREM[r, k] if k else 0.0, skipped=(n > 0) and k == 0))
        if k:
            open_[u] = (T.dord[r] * 2000 + T.END[r, k], T.NET[r, k], T.GROSS[r, k], T.PREM[r, k])
        if open_ and any(T.dord[r] != (x[0] // 2000) for x in open_.values()):
            pass
    t = pd.DataFrame(rec)
    cal = CAL[(CAL >= lo) & (CAL < hi)]
    if not len(t):
        z = pd.Series(0.0, index=cal)
        return t, z, z, z + start
    dn = t.groupby("day").net.sum().reindex(cal, fill_value=0.0)
    dg = t.groupby("day").gross.sum().reindex(cal, fill_value=0.0)
    cap = start + dn.cumsum()
    return t, dn, dg, cap


def path_stats(dn, dg, cap, start=START):
    mon = dn.groupby(dn.index.to_period("M")).sum()
    dd = (cap - np.maximum.accumulate(np.r_[start, cap.values])[1:]).min()
    return dict(end_cap=float(cap.iloc[-1]), min_cap=float(min(start, cap.min())), net_day=float(dn.mean()),
                gross_day=float(dg.mean()), worst_day=float(dn.min()), worst_month=float(mon.min()),
                losing_months=f"{int((mon < 0).sum())}/{len(mon)}", maxdd=float(dd),
                monthly=[round(x) for x in mon.values])


# ------------------------------------------------------------------------------------------------ E* (target size)
def estar(T, books, siz, kf):
    m = ((T.M.day >= REG) & (T.M.day < HOLD)).values & np.isin(T.ui, books)
    nd = ((CAL >= REG) & (CAL < HOLD)).sum()
    r = np.nonzero(m)[0]
    grid = np.unique(np.r_[np.round(np.geomspace(1e5, 3e8, 400), -3)])
    best = None
    curve = []
    for E in grid:
        n = n_rule(siz, np.full(len(r), E), T.p1[r], T.ui[r], kf).astype(int)
        nd_ = T.NET[r, n].sum() / nd
        curve.append((E, nd_))
        if best is None and nd_ >= TARGET:
            best = E
    return (best if best is not None else np.inf), curve


def exp_day(curve, E):
    c = np.array(curve)
    return float(np.interp(E, c[:, 0], c[:, 1]))


# ------------------------------------------------------------------------------------------------ bootstrap
def blocks(T, lo, hi):
    mths = sorted(set(T.month[((T.M.day >= lo) & (T.M.day < hi)).values]))
    days = {x: int(((CAL >= lo) & (CAL < hi) & (CAL.to_period("M").astype(str) == x)).sum()) for x in mths}
    return mths, days


def summarize(W, Estar, start=START):
    Em, dm = W["Em"], W["dm"]
    r = {}
    for h in (3, 6, 12, 24, 36, 60):
        if h > Em.shape[1]:
            continue
        e = Em[:, h - 1]
        r[f"cap{h}_p05"], r[f"cap{h}_p25"], r[f"cap{h}_med"], r[f"cap{h}_p75"], r[f"cap{h}_p95"] = \
            np.quantile(e, [0.05, 0.25, 0.5, 0.75, 0.95])
        r[f"P_double{h}"] = float((e >= 2 * start).mean())
        r[f"P_below_start{h}"] = float((e < start).mean())
    # ruin: E < 25k at any point within horizon h (min over month ends is a lower bound; use the walk's running min
    # for the full horizon and month-end min for the shorter ones)
    run_min = np.minimum.accumulate(np.minimum(Em, start), axis=1)
    for h in (12, 24, 60):
        if h > Em.shape[1]:
            continue
        r[f"P_ruin{h}"] = float((run_min[:, h - 1] < RUIN).mean())
    r["P_ruin60_intramonth"] = float((W["minE"] < RUIN).mean())
    prev = np.concatenate([np.full((Em.shape[0], 1), start), Em[:, :-1]], axis=1)
    rd = (Em - prev) / dm
    for h in (1, 6, 12, 24):
        if h > Em.shape[1]:
            continue
        r[f"rsday_m{h}_med"] = float(np.median(rd[:, h - 1]))
        r[f"rsday_m{h}_mean"] = float(np.mean(rd[:, h - 1]))
    hit = W["hit"]
    for h in (12, 24, 36, 60):
        if h > Em.shape[1]:
            continue
        r[f"P_hit{h}"] = float(((hit > 0) & (hit <= h)).mean())
    r["hit_med_months"] = float(np.median(hit[hit > 0])) if (hit > 0).any() else np.nan
    r["Estar"] = Estar
    r["skip_rate"] = float(W["skipped"].sum() / max(1.0, (W["skipped"] + W["taken"]).sum()))
    return r


def alt_choices(T, TP):
    """for each real row: pool rows whose parent is that trade's cand."""
    g = pd.Series(np.arange(len(TP.M))).groupby(TP.M.parent.values).apply(np.asarray)
    return [g.get(c, np.array([], int)) for c in T.M.cand.values]


def pre():
    os.makedirs(OUT, exist_ok=True)
    res = {}
    Ts = {k: Tab("real", k) for k in ("E2_k02", "E2_k04", "E0_k02")}
    T = Ts["E2_k02"]
    kf = kelly_fracs(T)
    print("Kelly f* per book (regime window, 1 lot, E2 k.02):", dict(zip(UNDS, kf.round(3))), flush=True)
    print("1-lot premium p1 regime median:", {u: round(float(np.median(T.p1[(T.ui == i) & (T.M.day >= REG).values
                                                                            & (T.M.day < HOLD).values])))
                                              for i, u in enumerate(UNDS)})
    TP = Tab("pool", "E2_k02")
    ch = alt_choices(T, TP)
    mths, days = blocks(T, REG, HOLD)
    rng = np.random.default_rng(15)
    seqm = np.array(mths)[rng.integers(len(mths), size=(NPATH, HORIZON))]
    mthsL, daysL = blocks(T, LONG, HOLD)
    seqL = np.array(mthsL)[np.random.default_rng(16).integers(len(mthsL), size=(NPATH, 24))]
    rows = []
    daily = {}
    rand_p = {}
    for bk, books in BOOKS.items():
        for siz in SIZINGS:
            v = f"{bk}-{siz}"
            Es, curve = estar(T, books, siz, kf)
            r = dict(variant=v, books=bk, sizing=siz)
            r["exp_day_at_1L"] = exp_day(curve, START)
            r["exp_day_at_1L_k04"] = exp_day(estar(Ts["E2_k04"], books, siz, kf)[1], START)
            for kn in ("E2_k02", "E2_k04"):
                Tk = Ts[kn]
                Es_k = Es if kn == "E2_k02" else estar(Tk, books, siz, kf)[0]
                sl = month_slots(Tk, mths, books)
                W = walk(Tk, seqm, sl, books, siz, kf, days, Es_k)
                s = summarize(W, Es_k)
                r.update({f"{kn}|{a}": b for a, b in s.items()})
            # long-window sensitivity (k .02, 24 months)
            slL = month_slots(T, mthsL, books)
            W = walk(T, seqL, slL, books, siz, kf, daysL, Es)
            s = summarize(W, Es)
            r.update({f"LONG|{a}": s[a] for a in ("cap12_med", "cap24_med", "P_ruin24", "P_double12", "rsday_m1_med")})
            # random entries, same bootstrap paths (first 1,000), k .02
            sl = month_slots(T, mths, books)
            W = walk(T, seqm[:1000, :24], sl, books, siz, kf, days, Es, alt=(TP, ch), rng=np.random.default_rng(7))
            s = summarize(W, Es)
            r.update({f"RAND|{a}": s[a] for a in ("cap12_med", "cap24_med", "P_ruin24", "P_double12")})
            # pre-holdout chronological replay on the regime window (k .02) + random-entry p
            t, dn, dg, cp = replay(T, REG, HOLD, books, siz, kf)
            ps = path_stats(dn, dg, cp)
            r.update({f"REPLAY|{a}": b for a, b in ps.items() if a != "monthly"})
            daily[v] = dn
            sq = np.array([mths])
            Wr = walk(T, np.repeat(sq, 2000, 0), sl, books, siz, kf, days, alt=(TP, ch), rng=np.random.default_rng(8))
            endr = Wr["Em"][:, -1]
            rand_p[v] = (1 + int((endr >= ps["end_cap"]).sum())) / (len(endr) + 1)
            r["REPLAY|rand_p"] = rand_p[v]
            r["REPLAY|rand_end_med"] = float(np.median(endr))
            rows.append(r)
            print(v, "E*=%.0f exp@1L=%.0f | k02 cap12 med %.0f P_ruin24 %.3f P_dbl12 %.3f rsday m1 %.0f m12 %.0f "
                     "P_hit24 %.3f P_hit60 %.3f | k04 cap12 med %.0f ruin24 %.3f | replay end %.0f (rand p %.4f)" % (
                      Es, r["exp_day_at_1L"], r["E2_k02|cap12_med"], r["E2_k02|P_ruin24"], r["E2_k02|P_double12"],
                      r["E2_k02|rsday_m1_med"], r["E2_k02|rsday_m12_med"], r["E2_k02|P_hit24"], r["E2_k02|P_hit60"],
                      r["E2_k04|cap12_med"], r["E2_k04|P_ruin24"], ps["end_cap"], rand_p[v]), flush=True)
    R = pd.DataFrame(rows)
    R.to_csv(os.path.join(OUT, "pre_variants.csv"), index=False)
    # multiple testing
    q = OF.bh(np.array([rand_p[v] for v in R.variant]))
    X = np.column_stack([daily[v].values for v in R.variant])
    spa = OF.spa(X)
    print("BH q (random-entry, replay):", dict(zip(R.variant, np.round(q, 4))))
    print("SPA over 18 daily series (regime replay):", spa)
    # choice
    el = R[R["E2_k02|P_ruin24"] <= 0.10].copy()
    print("eligible:", list(el.variant))
    best = el.loc[el["E2_k02|cap12_med"].idxmax()]
    el["simp"] = el.sizing.map(SIMPLE) * 10 + el.books.map({"C": 0, "B": 1, "A": 2})
    cand = el[el["E2_k02|cap12_med"] >= 0.95 * best["E2_k02|cap12_med"]].sort_values("simp")
    choice = cand.iloc[0]
    print("best by metric:", best.variant, round(best["E2_k02|cap12_med"]), "-> CHOICE (simplest within 5%):",
          choice.variant, round(choice["E2_k02|cap12_med"]))
    json.dump(dict(choice=choice.variant, books=choice.books, sizing=choice.sizing, kelly=list(kf),
                   spa=spa if isinstance(spa, dict) else str(spa), bh=dict(zip(R.variant, map(float, q)))),
              open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=float)
    pd.set_option("display.float_format", lambda x: "%.3f" % x if abs(x) < 10 else "%.0f" % x)
    show = ["variant", "exp_day_at_1L", "E2_k02|Estar", "E2_k02|cap3_med", "E2_k02|cap6_med", "E2_k02|cap12_p05",
            "E2_k02|cap12_med", "E2_k02|cap12_p95", "E2_k02|cap24_med", "E2_k02|P_ruin12", "E2_k02|P_ruin24",
            "E2_k02|P_double12", "E2_k02|P_double24", "E2_k02|rsday_m1_med", "E2_k02|rsday_m6_med",
            "E2_k02|rsday_m12_med", "E2_k02|P_hit24", "E2_k02|P_hit36", "E2_k02|P_hit60", "E2_k02|hit_med_months",
            "E2_k02|skip_rate"]
    print(R[show].to_string())
    show4 = ["variant", "E2_k04|Estar", "E2_k04|cap12_med", "E2_k04|cap24_med", "E2_k04|P_ruin24", "E2_k04|P_double12",
             "E2_k04|rsday_m1_med", "E2_k04|rsday_m12_med", "E2_k04|P_hit60", "LONG|cap12_med", "LONG|cap24_med",
             "LONG|P_ruin24", "RAND|cap12_med", "RAND|P_ruin24", "RAND|P_double12"]
    print(R[show4].to_string())
    show5 = ["variant", "REPLAY|end_cap", "REPLAY|min_cap", "REPLAY|net_day", "REPLAY|gross_day", "REPLAY|worst_day",
             "REPLAY|worst_month", "REPLAY|losing_months", "REPLAY|maxdd", "REPLAY|rand_end_med", "REPLAY|rand_p"]
    print(R[show5].to_string())


def hold():
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    kf = np.array(ch["kelly"])
    books, siz = BOOKS[ch["books"]], ch["sizing"]
    END = pd.Timestamp("2030-01-01")
    Ts = {k: Tab("real", k) for k in ("E2_k02", "E2_k04", "E2_k01", "E0_k02")}
    T = Ts["E2_k02"]
    print("HOLDOUT (once) choice", ch["choice"], "from Rs 1 lakh on", HOLD.date())
    out = {}
    for kn, Tk in Ts.items():
        t, dn, dg, cp = replay(Tk, HOLD, END, books, siz, kf)
        ps = path_stats(dn, dg, cp)
        out[kn] = ps
        print(kn, {a: (round(b) if isinstance(b, float) else b) for a, b in ps.items()})
        if kn == "E2_k02":
            t.to_csv(os.path.join(OUT, "holdout_trades.csv"), index=False)
            print("  trades taken", int((t.lots > 0).sum()), "of", len(t), "signals; skipped (capital tied up)",
                  int(t.skipped.sum()), "; lots by book:",
                  t[t.lots > 0].groupby("und").lots.agg(["mean", "max", "count"]).round(1).to_dict("index"))
            mon = t.groupby(t.day.dt.to_period("M")).agg(net=("net", "sum"), gross=("gross", "sum"),
                                                        lots_bn=("lots", lambda x: x[t.loc[x.index, "und"] == "BANKNIFTY"].mean()))
            capm = cp.groupby(cp.index.to_period("M")).last()
            dpm = dn.groupby(dn.index.to_period("M")).size()
            mon["cap_end"] = capm
            mon["net_per_day"] = mon.net / dpm
            print(mon.round(0).to_string())
    # random-entry replay p
    TP = Tab("pool", "E2_k02")
    chs = alt_choices(T, TP)
    mths, days = blocks(T, HOLD, END)
    sl = month_slots(T, mths, books)
    Wr = walk(T, np.repeat(np.array([mths]), 2000, 0), sl, books, siz, kf, days, alt=(TP, chs),
              rng=np.random.default_rng(9))
    endr = Wr["Em"][:, -1]
    p = (1 + int((endr >= out["E2_k02"]["end_cap"]).sum())) / 2001
    print("random-entry holdout replays: end capital median %.0f p05 %.0f p95 %.0f; P(ruin) %.3f; p = %.4f" % (
        np.median(endr), np.quantile(endr, .05), np.quantile(endr, .95), (Wr["minE"] < RUIN).mean(), p))
    # where the holdout path sits in the pre-holdout bootstrap
    mR, dR = blocks(T, REG, HOLD)
    seq = np.array(mR)[np.random.default_rng(15).integers(len(mR), size=(NPATH, 13))]
    W = walk(T, seq, month_slots(T, mR, books), books, siz, kf, dR)
    c12 = out["E2_k02"]
    for h in (3, 6, 12):
        tt, dn, dg, cp = replay(T, HOLD, HOLD + pd.DateOffset(months=h), books, siz, kf)
        e = cp.iloc[-1]
        print("holdout capital after %d months %.0f -> bootstrap percentile %.2f (bootstrap median %.0f)" % (
            h, e, (W["Em"][:, h - 1] < e).mean(), np.median(W["Em"][:, h - 1])))
    # info only: all variants
    rows = []
    for bk, bb in BOOKS.items():
        for sz in SIZINGS:
            r = dict(variant=f"{bk}-{sz}")
            for kn in ("E2_k02", "E2_k04"):
                t, dn, dg, cp = replay(Ts[kn], HOLD, END, bb, sz, kf)
                ps = path_stats(dn, dg, cp)
                r.update({f"{kn}|{a}": ps[a] for a in ("end_cap", "min_cap", "net_day", "gross_day", "maxdd", "losing_months")})
            rows.append(r)
    R = pd.DataFrame(rows)
    pd.set_option("display.float_format", lambda x: "%.0f" % x)
    print("INFO ONLY, all variants, holdout replay from Rs 1 lakh:")
    print(R.to_string())
    R.to_csv(os.path.join(OUT, "holdout_variants.csv"), index=False)
    json.dump(out, open(os.path.join(OUT, "holdout.json"), "w"), indent=1, default=str)


if __name__ == "__main__":
    {"pre": pre, "hold": hold}[sys.argv[1]]()
