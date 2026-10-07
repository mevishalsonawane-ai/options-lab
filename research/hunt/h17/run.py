"""h17: Liquidity 15+5 with FIXED lots at Rs 1,00,000 + daily loss-stop / profit-lock. See PREREG.md.

Reuses h15's per-trade, per-lot-count tables (h14 E2 limit entry, h10 impact; research/hunt/h15/tab.py).

    OBUY_CACHE=<scratch>/hunt/h15/cache flock <scratch>/obuy.lock python3 -I research/hunt/h17/run.py pre
    OBUY_CACHE=<scratch>/hunt/h15/cache flock <scratch>/obuy.lock python3 -I research/hunt/h17/run.py hold   # ONCE
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h15"))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import cap1l as K  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import portfolio as PF  # noqa: E402
from obuy import overfit as OF  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h17"
E0 = 100_000.0
START, REG, HOLD, END = pd.Timestamp("2021-10-01"), pd.Timestamp("2024-12-01"), pd.Timestamp("2025-10-01"), \
    pd.Timestamp("2030-01-01")
CAL = K.CAL
SIZES = [(n, m) for m in (0, 1) for n in (1, 2, 3, 4)]          # (BN lots, MIDCP lots)
DISC = ["none", "L", "LP"]
VARIANTS = [(n, m, d) for (n, m) in SIZES for d in DISC]
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 60)


def vname(v):
    n, m, d = v
    return f"BN{n}" + (f"+M{m}" if m else "") + f"/{d}"


def thr(n):
    return 5_000.0 if n <= 2 else 10_000.0


class Setup:
    """Per variant-size row arrays for one kappa table."""

    def __init__(self, T):
        self.T = T
        self.cal_pos = CAL.get_indexer(pd.DatetimeIndex(T.M.day))
        assert (self.cal_pos >= 0).all()

    def arrays(self, n, m):
        T = self.T
        books = (0,) if m == 0 else (0, 2)
        rows = np.nonzero(np.isin(T.ui, books))[0]                # already time-ordered
        k = np.where(T.ui[rows] == 0, n, m)
        prem = T.PREM[rows, k]
        net = np.nan_to_num(T.NET[rows, k])
        gross = np.nan_to_num(T.GROSS[rows, k])
        end = T.dord[rows] * 2000 + T.END[rows, k]
        kin = T.kin[rows]
        dpos = self.cal_pos[rows]
        bk = np.where(T.ui[rows] == 0, 0, 2) + pd.Series(T.M.book.values[rows]).str.match(r"liq(uidity)?5(_|$)").values.astype(int)   # (index, sub-book) slot
        # per calendar day padded row table
        cnt = np.bincount(dpos, minlength=len(CAL))
        L = max(1, cnt.max())
        DR = np.full((len(CAL), L), -1)
        first = np.searchsorted(dpos, np.arange(len(CAL)))
        for j in range(L):
            ok = cnt > j
            DR[ok, j] = first[ok] + j
        return dict(prem=prem, net=net, gross=gross, end=end, kin=kin, bk=bk, DR=DR)


def walk(A, D, disc, X, start=E0):
    """D: (P, H) calendar positions. Vectorised over paths. Returns per-day net/gross, equity, min equity, counts."""
    P, H = D.shape
    E = np.full(P, start)
    minE = E.copy()
    dn = np.zeros((P, H))
    dg = np.zeros((P, H))
    taken = np.zeros(P)
    skipped = np.zeros(P)
    stopped = np.zeros(P)
    ar = np.arange(P)
    for h in range(H):
        S = A["DR"][D[:, h]]
        oprem = np.zeros((P, 4))
        onet = np.zeros((P, 4))
        oend = np.full((P, 4), np.inf)
        dayR = np.zeros(P)
        G = np.zeros(P)
        halted = np.zeros(P, bool)
        for s in range(S.shape[1]):
            r = S[:, s]
            v = r >= 0
            if not v.any():
                continue
            rr = np.where(v, r, 0)
            kin = A["kin"][rr]
            done = oend <= kin[:, None]
            rel = (onet * done).sum(1)
            E += rel
            dayR += rel
            oprem[done], onet[done], oend[done] = 0.0, 0.0, np.inf
            minE = np.minimum(minE, E)
            if disc in ("L", "LP"):
                halted |= dayR <= -X
            if disc == "LP":
                halted |= dayR >= X
            b = A["bk"][rr]
            busy = np.isfinite(oend[ar, b])
            want = v & ~busy
            stopped += want & halted
            want &= ~halted
            cash = E - oprem.sum(1)
            afford = A["prem"][rr] + 100.0 <= cash
            skipped += want & ~afford
            go = want & afford
            gi = np.nonzero(go)[0]
            if len(gi):
                q = rr[gi]
                oprem[gi, b[gi]] = A["prem"][q]
                onet[gi, b[gi]] = A["net"][q]
                oend[gi, b[gi]] = A["end"][q]
                G[gi] += A["gross"][q]
                taken += go
        rel = onet.sum(1)
        E += rel
        dayR += rel
        minE = np.minimum(minE, E)
        dn[:, h] = dayR
        dg[:, h] = G
    return dict(dn=dn, dg=dg, E=E, minE=minE, taken=taken, skipped=skipped, stopped=stopped)


def window(lo, hi):
    return np.nonzero((CAL >= lo) & (CAL < hi))[0]


def replay(A, v, lo, hi, start=E0):
    n, m, d = v
    w = window(lo, hi)
    W = walk(A, w[None, :], d, thr(n), start)
    dn = pd.Series(W["dn"][0], index=CAL[w])
    dg = pd.Series(W["dg"][0], index=CAL[w])
    eq = start + dn.cumsum()
    peak = np.maximum.accumulate(np.r_[start, eq.values])[1:]
    mon = dn.groupby(dn.index.to_period("M")).sum()
    dd = float((eq - peak).min())
    return dict(net_day=float(dn.mean()), gross_day=float(dg.mean()), end_cap=float(eq.iloc[-1]),
                min_cap=float(min(start, W["minE"][0])), maxdd_rs=dd, maxdd_pct_1L=dd / E0, worst_day=float(dn.min()),
                worst_month=float(mon.min()), losing_months=f"{int((mon < 0).sum())}/{len(mon)}",
                taken=int(W["taken"][0]), skipped=int(W["skipped"][0]), stopped=int(W["stopped"][0]),
                days=len(w)), dn, dg


def yearly_restart(A, v, lo, hi):
    """net Rs/day with each calendar year restarted at Rs 1 lakh (days-weighted)."""
    tot, nd, per = 0.0, 0, {}
    for y in range(lo.year, hi.year + 1):
        a, b = max(lo, pd.Timestamp(f"{y}-01-01")), min(hi, pd.Timestamp(f"{y + 1}-01-01"))
        if a >= b:
            continue
        s, dn, _ = replay(A, v, a, b)
        per[y] = s["net_day"]
        tot += dn.sum()
        nd += len(dn)
    return tot / nd, per


def boot_idx(w, P=2000, H=496, block=10.0, seed=17):
    rng = np.random.default_rng(seed)
    n = len(w)
    k = rng.integers(n, size=P)
    out = np.empty((P, H), int)
    for h in range(H):
        if h:
            jump = rng.random(P) < 1.0 / block
            k = np.where(jump, rng.integers(n, size=P), (k + 1) % n)
        out[:, h] = w[k]
    return out


def mc(A, v, D):
    n, m, d = v
    res = {}
    for H in (248, 496):
        W = walk(A, D[:, :H], d, thr(n))
        eqmin = np.minimum(W["minE"], E0)
        res[f"P_lt25k_{H}"] = float((eqmin < 25_000).mean())
        res[f"P_lt50k_{H}"] = float((eqmin < 50_000).mean())
        res[f"P_loss_{H}"] = float((W["E"] < E0).mean())
        res[f"med_end_{H}"] = float(np.median(W["E"]))
        res[f"p5_end_{H}"] = float(np.quantile(W["E"], 0.05))
        res[f"skip_rate_{H}"] = float(W["skipped"].sum() / max(1.0, (W["skipped"] + W["taken"]).sum()))
    return res


def boot_p_mean(x, B=4000, block=5.0, seed=21):
    """one-sided stationary bootstrap p for mean > 0 (centred)."""
    x = np.asarray(x, float)
    T = len(x)
    rng = np.random.default_rng(seed)
    cnt = OF._stationary_counts(T, B, block, rng) if hasattr(OF, "_stationary_counts") else None
    mu = x.mean()
    xc = x - mu
    if cnt is not None and cnt.shape == (B, T):
        bm = cnt @ xc / T
    else:
        bm = np.array([xc[rng.integers(T, size=T)].mean() for _ in range(B)])
    return float((1 + (bm >= mu).sum()) / (B + 1))


def choose(R):
    el = R[R.elig].copy()
    if not len(el):
        return None
    best = el.score.max()
    el["simp"] = el.n * 100 + el.m * 10 + el.d.map({"none": 0, "L": 1, "LP": 2})
    c = el[el.score >= 0.95 * best].sort_values("simp")
    return c.iloc[0].variant


# ------------------------------------------------------------------------------------------------ stages
def pre():
    os.makedirs(SCR, exist_ok=True)
    Ts = {k: Setup(K.Tab("real", k)) for k in ("E2_k02", "E2_k04")}
    Ar = {k: {s: Ts[k].arrays(*s) for s in SIZES} for k in Ts}
    wP = window(START, HOLD)
    wM = window(REG, HOLD)
    DP = boot_idx(wP)
    DM = boot_idx(wM, H=248, seed=18)
    rows, XP = [], []
    for v in VARIANTS:
        n, m, d = v
        A, A4 = Ar["E2_k02"][(n, m)], Ar["E2_k04"][(n, m)]
        sP, dnP, dgP = replay(A, v, START, HOLD)
        sM, _, _ = replay(A, v, REG, HOLD)
        s4, _, _ = replay(A4, v, START, HOLD)
        score, per = yearly_restart(A, v, START, HOLD)
        r = dict(variant=vname(v), n=n, m=m, d=d, X=thr(n), score=score, k04_net_day=s4["net_day"],
                 **{f"PRE_{a}": b for a, b in sP.items()}, **{f"M_{a}": b for a, b in sM.items()},
                 **{f"Y{y}": x for y, x in per.items()})
        r["P_lose_month"] = PF.p_losing_month(dnP.values, B=5000)
        r.update({f"mcP_{a}": b for a, b in mc(A, v, DP).items()})
        W = walk(A, DM, d, thr(n))
        r["mcM_P_lt50k_248"] = float((np.minimum(W["minE"], E0) < 50_000).mean())
        r["mcM_P_lt25k_248"] = float((np.minimum(W["minE"], E0) < 25_000).mean())
        r["p_mean"] = boot_p_mean(dnP.values)
        XP.append(dnP.values)
        rows.append(r)
        print(vname(v), {a: (round(b, 3) if isinstance(b, float) else b) for a, b in r.items()
                         if a not in ("variant",)}, flush=True)
    R = pd.DataFrame(rows)
    R["bh_q"] = OF.bh(R.p_mean.values)
    R["elig"] = (R.mcP_P_lt50k_248 < 0.05) & (R.k04_net_day > 0)
    spa = OF.spa(np.column_stack(XP), B=2000)
    choice = choose(R)
    # anchored walk-forward
    wf = []
    for ty in (2023, 2024, 2025):
        rr = []
        Dt = boot_idx(window(START, pd.Timestamp(f"{ty}-01-01")), H=248, seed=30 + ty)
        for v in VARIANTS:
            n, m, d = v
            A, A4 = Ar["E2_k02"][(n, m)], Ar["E2_k04"][(n, m)]
            sc, _ = yearly_restart(A, v, START, pd.Timestamp(f"{ty}-01-01"))
            s4, _, _ = replay(A4, v, START, pd.Timestamp(f"{ty}-01-01"))
            W = walk(A, Dt, d, thr(n))
            rr.append(dict(variant=vname(v), n=n, m=m, d=d, score=sc, k04_net_day=s4["net_day"],
                           elig=((np.minimum(W["minE"], E0) < 50_000).mean() < 0.05) and s4["net_day"] > 0))
        pk = choose(pd.DataFrame(rr))
        yrow = R.set_index("variant")[f"Y{ty}"]
        wf.append(dict(test=ty, pick=pk, pick_test=yrow.get(pk, np.nan) if pk else np.nan,
                       final_choice_test=yrow[choice] if choice else np.nan))
    WF = pd.DataFrame(wf)
    # biggest fixed size scan (risk only)
    scan = []
    for m in (0, 1):
        for d in DISC:
            for n in range(1, 9):
                A = Ts["E2_k02"].arrays(n, m)
                W = walk(A, DP[:, :248], d, 5_000.0 if n <= 2 else 10_000.0)
                sk = W["skipped"].sum() / max(1.0, (W["skipped"] + W["taken"]).sum())
                scan.append(dict(books="BN" + ("+M1" if m else ""), d=d, n=n,
                                 P_lt50k=float((np.minimum(W["minE"], E0) < 50_000).mean()),
                                 P_lt25k=float((np.minimum(W["minE"], E0) < 25_000).mean()),
                                 skip_rate=float(sk), med_net_day=float(np.median((W["E"] - E0) / 248))))
    SC = pd.DataFrame(scan)
    big = {}
    for (b, d), g in SC.groupby(["books", "d"]):
        ok = g[(g.P_lt50k < 0.05) & (g.skip_rate <= 0.5)]
        big[f"{b}/{d}"] = int(ok.n.max()) if len(ok) else 0
    R.to_csv(os.path.join(SCR, "pre_variants.csv"), index=False)
    SC.to_csv(os.path.join(SCR, "pre_size_scan.csv"), index=False)
    pd.set_option("display.float_format", lambda x: "%.3f" % x if abs(x) < 10 else "%.0f" % x)
    cols = ["variant", "score", "PRE_net_day", "PRE_gross_day", "k04_net_day", "PRE_end_cap", "PRE_min_cap",
            "PRE_maxdd_rs", "PRE_maxdd_pct_1L", "PRE_worst_day", "PRE_worst_month", "PRE_losing_months",
            "P_lose_month", "PRE_skipped", "PRE_stopped", "PRE_taken"]
    print(R[cols].to_string())
    cols2 = ["variant", "M_net_day", "M_gross_day", "M_end_cap", "M_maxdd_rs", "Y2021", "Y2022", "Y2023", "Y2024",
             "Y2025", "mcP_P_lt25k_248", "mcP_P_lt25k_496", "mcP_P_lt50k_248", "mcP_P_lt50k_496", "mcP_P_loss_248",
             "mcP_med_end_248", "mcP_p5_end_248", "mcM_P_lt50k_248", "mcM_P_lt25k_248", "p_mean", "bh_q", "elig"]
    print(R[cols2].to_string())
    print("SPA/RC over 24 PRE daily series:", spa)
    print("size scan (PRE bootstrap 248d, kappa .02):")
    print(SC.to_string())
    print("biggest fixed BN lots with P(<50k in 1y) < 5% and skip <= 50%:", big)
    print("walk-forward:")
    print(WF.to_string())
    print("CHOICE:", choice)
    json.dump(dict(choice=choice, spa=spa, walk_forward=wf, biggest=big, n_variants=len(VARIANTS)),
              open(os.path.join(SCR, "choice.json"), "w"), indent=1, default=float)


def hold():
    ch = json.load(open(os.path.join(SCR, "choice.json")))
    v = [x for x in VARIANTS if vname(x) == ch["choice"]][0]
    print("HOLDOUT (once):", ch["choice"], "from Rs 1 lakh on", HOLD.date())
    out = {}
    for k in ("E2_k02", "E2_k04", "E2_k01"):
        A = Setup(K.Tab("real", k)).arrays(v[0], v[1])
        s, dn, dg = replay(A, v, HOLD, END)
        s["P_lose_month"] = PF.p_losing_month(dn.values, B=5000)
        mon = dn.groupby(dn.index.to_period("M")).sum()
        s["monthly"] = {str(a): round(float(b)) for a, b in mon.items()}
        if k == "E2_k02":
            Dh = boot_idx(window(HOLD, END), seed=19)
            s.update({f"mcH_{a}": b for a, b in mc(A, v, Dh).items()})
        out[k] = s
        print(k, json.dumps(s, default=float), flush=True)
    json.dump(out, open(os.path.join(SCR, "holdout.json"), "w"), indent=1, default=float)
    # POST-HOC, labelled, NOT used for anything: all 24 variants in the holdout
    rows = []
    for kk in ("E2_k02", "E2_k04"):
        S = Setup(K.Tab("real", kk))
        for x in VARIANTS:
            s, _, _ = replay(S.arrays(x[0], x[1]), x, HOLD, END)
            rows.append(dict(kappa=kk, variant=vname(x), **{a: s[a] for a in (
                "net_day", "gross_day", "end_cap", "min_cap", "maxdd_rs", "worst_day", "worst_month",
                "losing_months", "skipped", "stopped")}))
    PH = pd.DataFrame(rows)
    PH.to_csv(os.path.join(SCR, "holdout_all_posthoc.csv"), index=False)
    pd.set_option("display.float_format", lambda x: "%.0f" % x)
    print("POST-HOC all variants (holdout):")
    print(PH.to_string())


if __name__ == "__main__":
    {"pre": pre, "hold": hold}[sys.argv[1]]()
