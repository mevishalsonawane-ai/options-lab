"""h16: cheaper / more convex options for Liquidity 15+5 at Rs 1,00,000 capital (compounding, ruin). PREREG.md.

    OBUY_CACHE=<scratch>/hunt/h16/cache python3 -I research/hunt/h16/run.py pre    # choice on < 2025-10-01 only
    OBUY_CACHE=<scratch>/hunt/h16/cache python3 -I research/hunt/h16/run.py hold   # ONE holdout test of choice.json
(packs: research/hunt/h16/build.py; the h10 simulator via research/hunt/h13/run.py)
"""
from __future__ import annotations

import dataclasses
import importlib.util
import json
import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("h13run", os.path.join(os.path.dirname(HERE), "h13", "run.py"))
R13 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(R13)
cap = R13.cap
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy import overfit as OF  # noqa: E402
from obuy.engine import Pack, Store, positions  # noqa: E402
from obuy.strategies.liquidity import ARM_EXITS  # noqa: E402

OUT = os.path.join(C.CACHE, "h16")
HOLD, REG, START = R13.HOLD, R13.REG, R13.START
END = pd.Timestamp("2100-01-01")
CAL = R13.CAL
UNDS = R13.UNDS
E0, RUIN = 100_000.0, 25_000.0
MONEY = (1, 0, -1, -2)
STOPS = ("scaled", "none")
ALLOC = ("third", "full")
VARIANTS = [(mo, st, al) for mo in MONEY for st in STOPS for al in ALLOC]
INCUMBENT = (1, "scaled", "third")
NG = np.array(list(range(1, 31)) + [35, 40, 50, 60, 80, 100, 130, 170, 220, 290, 380, 500, 650, 850, 1100, 1500, 2000,
                                     3000], float)
SQC = R13.SQC
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 50)


def vname(v):
    mo, st, al = v
    m = "ATM" if mo == 0 else (f"ITM{mo}" if mo > 0 else f"OTM{-mo}")
    return f"{m}/{st}/{al}"


# ------------------------------------------------------------------------------------------------ trades
def load():
    with open(os.path.join(OUT, "packs.pkl"), "rb") as f:
        metas, arr, sig = pickle.load(f)
    st = Store()
    st.arr = arr
    return metas, st


def run_trades(meta, store, stops, pos):
    """Arm exits with a per-row premium stop (nan = no premium stop; time-stop gain = stop/3, or 0.05 with no stop)."""
    pk = Pack(meta, store, 1)
    out = []
    key = np.where(np.isfinite(stops), stops, -1.0)
    for sp in np.unique(key):
        idx = np.nonzero(key == sp)[0]
        sub = pk.subset(idx)
        if sp < 0:
            ex = dataclasses.replace(ARM_EXITS, stop_pct=None, time_gain=0.05)
        else:
            ex = dataclasses.replace(ARM_EXITS, stop_pct=float(sp), time_gain=float(sp) / 3.0)
        tr = sub.run(ex, cap.EXE)
        rows = idx[np.nonzero(sub.meta.c0.values < SQC - 1)[0]]
        assert len(rows) == len(tr)
        tr["row"] = rows
        tr["stop"] = sp if sp > 0 else 0.99       # only used for the gross print of why == 'stop' (none here)
        out.append(tr)
    tr = pd.concat(out, ignore_index=True)
    if pos:
        tr = positions(tr, one_at_a_time=True)
    tr["day"] = pd.to_datetime(tr["day"])
    m = pk.meta.iloc[tr.row.values]
    tr["c0"] = m.c0.values
    tr["xcol"] = tr.exit_min.values - C.OPEN_M
    if "parent" not in tr:
        tr["parent"] = m.parent.values
    return pk, tr.reset_index(drop=True)


def setup():
    metas, store = load()
    S = {}
    for mo in MONEY:
        mr, mp = metas[(mo, "near", "real")], metas[(mo, "near", "pool")]
        for st in STOPS:
            if st == "none":
                sr, sp = np.full(len(mr), np.nan), np.full(len(mp), np.nan)
            else:
                pre = mr[pd.to_datetime(mr.day) < HOLD]
                med = pre.groupby("und").f.median().to_dict()
                sr, sp = R13.stop_of(mr, med), R13.stop_of(mp, med)
            pk, tr = run_trades(mr, store, sr, True)
            pp, tp = run_trades(mp, store, sp, False)
            S[(mo, st)] = dict(B={u: R13.Book(pk, tr[tr.und == u]) for u in UNDS},
                               BP={u: R13.Book(pp, tp[tp.und == u]) for u in UNDS},
                               stop_med=float(np.nanmedian(sr)) if st == "scaled" else None)
    return S


class Table:
    """Per-trade net / premium-in / exit minute at lot counts NG (all books, time-ordered)."""

    def __init__(self, B, kappa=cap.KAPPA):
        parts = []
        for u in UNDS:
            b = B[u]
            nets, prems, ends, lots, gross, clip = [], [], [], [], [], []
            for n in NG:
                t = R13.simulate(b, n, cap.CAP, kappa)
                nets.append(t.net.values)
                prems.append(t.prem.values + 0.5 * t.charges.values)
                ends.append(t.exit_end_min.values)
                lots.append(t.lots.values)
                gross.append(t.gross.values)
                clip.append(t.clipped.values)
            base = t[["day", "entry_min", "und"]].copy()
            base["k"] = np.arange(len(base))
            parts.append((base, np.array(nets).T, np.array(prems).T, np.array(ends).T, np.array(lots).T,
                          np.array(gross).T, np.array(clip).T))
        base = pd.concat([p[0] for p in parts], ignore_index=True)
        o = np.lexsort((base.entry_min.values, base.day.values.astype("datetime64[D]").astype(np.int64)))
        self.day = base.day.values[o]
        self.und = base.und.values[o]
        self.em = base.entry_min.values[o].astype(int)
        self.net, self.prem, self.end, self.lots, self.gross, self.clip = (
            np.concatenate([p[i] for p in parts])[o] for i in range(1, 7))
        self.unit = self.prem[:, 0]                       # premium-in for 1 lot
        # trades per calendar day
        dn = pd.DatetimeIndex(self.day)
        self.cal = CAL
        pos = CAL.get_indexer(dn)
        assert (pos >= 0).all()
        self.dpos = pos
        self.first = np.searchsorted(pos, np.arange(len(CAL)), "left")
        self.last = np.searchsorted(pos, np.arange(len(CAL)), "right")

    def pick(self, i, budget):
        """largest n (int) with premium-in(n) <= budget; returns (n, idx-weights) or (0, None)."""
        u = self.unit[i]
        if not (u > 0) or budget < u:
            return 0
        n = int(budget // u)
        while n > 0 and self.at(self.prem, i, n) > budget:
            n = int(n * 0.97) if n > 40 else n - 1
        return n

    def at(self, A, i, n):
        return float(np.interp(n, NG, A[i]))


def replay(T: Table, alloc, day_idx, kappa_tab=None):
    """Compounding replay over CAL positions day_idx (sequence; may repeat for bootstrap). Returns dict."""
    E = E0
    peak, mdd = E0, 0.0
    ruined, ruin_day = False, None
    daily = np.zeros(len(day_idx))
    s_net = s_prem = s_gross = 0.0
    taken = skipped = clipped = 0
    lots_sum = 0.0
    by_und = {}
    for j, d in enumerate(day_idx):
        e_start = E
        if not ruined:
            open_ = []
            for i in range(T.first[d], T.last[d]):
                em = T.em[i]
                still = []
                for (x, nt, c) in open_:
                    if x <= em:
                        E += nt
                    else:
                        still.append((x, nt, c))
                open_ = still
                if E < RUIN:
                    ruined = True
                    break
                free = E - sum(c for _, _, c in open_)
                budget = min(free, E / 3.0) if alloc == "third" else free
                n = T.pick(i, budget)
                if n <= 0:
                    skipped += 1
                    continue
                nt, c = T.at(T.net, i, n), T.at(T.prem, i, n)
                open_.append((T.at(T.end, i, n), nt, c))
                taken += 1
                s_net += nt
                by_und[T.und[i]] = by_und.get(T.und[i], 0.0) + nt
                s_prem += c
                s_gross += T.at(T.gross, i, n)
                lots_sum += n
                clipped += bool(T.clip[i][min(np.searchsorted(NG, n), len(NG) - 1)])
            for (x, nt, c) in open_:
                E += nt
            if E < RUIN and not ruined:
                ruined = True
            if ruined and ruin_day is None:
                ruin_day = j
        daily[j] = E - e_start
        peak = max(peak, E)
        mdd = max(mdd, 1 - E / peak)
    return dict(final=E, daily=daily, mdd_pct=mdd, ruined=ruined, ruin_day=ruin_day, taken=taken, skipped=skipped,
                ret_on_prem=s_net / s_prem if s_prem else np.nan, gross=s_gross, net=s_net, prem=s_prem,
                avg_lots=lots_sum / max(taken, 1), clipped=clipped, by_und=by_und)


def window(lo, hi):
    return np.nonzero((CAL >= lo) & (CAL < hi))[0]


def summarize(T, alloc, lo, hi):
    w = window(lo, hi)
    r = replay(T, alloc, w)
    d = pd.Series(r["daily"], index=CAL[w])
    m = d.groupby(d.index.to_period("M")).sum()
    return dict(rs_day=(r["final"] - E0) / len(w), final=r["final"], gross_day=r["gross"] / len(w),
                net_trades_day=r["net"] / len(w), ret_on_prem=r["ret_on_prem"], mdd_pct=r["mdd_pct"],
                worst_day=d.min(), worst_month=m.min(), losing_months=f"{(m < 0).sum()}/{len(m)}",
                taken=r["taken"], skipped=r["skipped"], avg_lots=r["avg_lots"], clipped=r["clipped"],
                ruined=r["ruined"], days=len(w),
                by_und={k: round(x) for k, x in r["by_und"].items()}), d


def mc(T, alloc, lo, hi, H=248, B=2000, block=10.0, seed=16, targets=(200_000.0, 3_500_000.0)):
    w = window(lo, hi)
    n = len(w)
    rng = np.random.default_rng(seed)
    finals, ruin, hit = np.empty(B), np.zeros(B, bool), {t: [] for t in targets}
    for b in range(B):
        idx = np.empty(H, int)
        k = rng.integers(n)
        for h in range(H):
            if h and rng.random() < 1.0 / block:
                k = rng.integers(n)
            else:
                k = (k + 1) % n if h else k
            idx[h] = w[k]
        r = replay(T, alloc, idx)
        finals[b], ruin[b] = r["final"], r["ruined"]
        eq = E0 + np.cumsum(r["daily"])
        for t in targets:
            hh = np.nonzero(eq >= t)[0]
            hit[t].append(hh[0] + 1 if len(hh) else np.nan)
    out = dict(H=H, p_ruin=float(ruin.mean()), p_loss=float((finals < E0).mean()), med_final=float(np.median(finals)),
               p5_final=float(np.quantile(finals, .05)), p95_final=float(np.quantile(finals, .95)))
    for t in targets:
        a = np.array(hit[t], float)
        out[f"p_reach_{int(t)}"] = float(np.isfinite(a).mean())
        out[f"med_days_{int(t)}"] = float(np.nanmedian(a)) if np.isfinite(a).any() else None
    return out


def rand_1lot(S, lo, hi):
    B, BP = S["B"], S["BP"]
    t = pd.concat([R13.simulate(B[u], 1) for u in UNDS], ignore_index=True)
    tp = pd.concat([R13.simulate(BP[u], 1) for u in UNDS], ignore_index=True)
    m, mp = (t.day >= lo) & (t.day < hi), (tp.day >= lo) & (tp.day < hi)
    rb = OF.random_baseline(t[m], tp[mp])
    x = t[m]
    return rb, dict(net_1lot_trade=x.net.mean(), ret_on_prem_1lot=x.net.sum() / x.prem.sum(),
                    gross_ret_on_prem_1lot=x.gross.sum() / x.prem.sum(),
                    prem_1lot_med={u: float(x[x.und == u].prem.median()) for u in UNDS})


# ------------------------------------------------------------------------------------------------ stages
def pre():
    S = setup()
    TB = {k: Table(v["B"]) for k, v in S.items()}
    rows, XM, XP, yearly = [], [], [], {}
    for v in VARIANTS:
        mo, st, al = v
        T = TB[(mo, st)]
        r = dict(variant=vname(v), stop_med=S[(mo, st)]["stop_med"])
        sW, _ = summarize(T, al, START, REG)
        sM, dM = summarize(T, al, REG, HOLD)
        sP, dP = summarize(T, al, START, HOLD)
        for nm, s in (("W", sW), ("M", sM), ("PRE", sP)):
            r.update({f"{nm}_{k}": x for k, x in s.items()})
        XM.append(dM.values)
        XP.append(dP.values)
        yr = {}
        for y in (2021, 2022, 2023, 2024, 2025):
            lo, hi = max(pd.Timestamp(f"{y}-01-01"), START), min(pd.Timestamp(f"{y + 1}-01-01"), HOLD)
            s, _ = summarize(T, al, lo, hi)
            yr[y] = s["rs_day"]
        yearly[vname(v)] = yr
        if al == "third":
            rb, one = rand_1lot(S[(mo, st)], START, HOLD)
            S[(mo, st)]["rb"], S[(mo, st)]["one"] = rb, one
        rb, one = S[(mo, st)]["rb"], S[(mo, st)]["one"]
        r.update(rand_p=rb["p"], rand_obs=rb["obs"], rand_null=rb["null_mean"], **{k: x for k, x in one.items()
                                                                                    if k != "prem_1lot_med"})
        r["prem_1lot_med"] = json.dumps({u[:3]: round(x) for u, x in one["prem_1lot_med"].items()})
        r.update({f"mcM_{k}": x for k, x in mc(T, al, REG, HOLD, B=1000).items()})
        rows.append(r)
        print(vname(v), {k: (round(x, 3) if isinstance(x, float) else x) for k, x in r.items() if k != "variant"},
              flush=True)
    R = pd.DataFrame(rows)
    R["rand_bh_q"] = OF.bh(R.rand_p.values)
    R.to_csv(os.path.join(OUT, "pre_variants.csv"), index=False)
    Y = pd.DataFrame(yearly).T
    Y.to_csv(os.path.join(OUT, "pre_yearly.csv"))
    cols = ["variant", "stop_med", "W_rs_day", "M_rs_day", "M_final", "M_ret_on_prem", "M_mdd_pct", "M_taken",
            "M_skipped", "M_avg_lots", "PRE_rs_day", "PRE_mdd_pct", "ret_on_prem_1lot", "gross_ret_on_prem_1lot",
            "rand_p", "rand_bh_q", "mcM_p_ruin", "mcM_p_loss", "mcM_med_final", "prem_1lot_med"]
    print(R[cols].round(3).to_string())
    print("per-year Rs/day at 1 lakh (restart each year):"); print(Y.round(0).to_string())
    spaM, spaP = OF.spa(np.column_stack(XM), B=2000), OF.spa(np.column_stack(XP), B=2000)
    print("SPA/RC era M:", spaM, " PRE:", spaP)
    wf = []
    for ty in (2023, 2024, 2025):
        tr_ = Y[[c for c in Y.columns if c < ty]]
        cal = CAL[(CAL >= START) & (CAL < HOLD)]
        nd = pd.Series({y: int((cal.year == y).sum()) for y in tr_.columns})
        score = (tr_ * nd).sum(axis=1) / nd.sum()
        pick = score.idxmax()
        wf.append(dict(test=ty, pick=pick, test_rs_day=Y.loc[pick, ty], incumbent=Y.loc[vname(INCUMBENT), ty]))
    print("walk-forward:"); print(pd.DataFrame(wf).round(0).to_string())
    el = R[(R.W_final > E0) & (R.M_final > E0) & (R.rand_bh_q < 0.05) & (R.mcM_p_ruin < 0.10)].copy()
    print("eligible:", el.variant.tolist())
    best = el.sort_values(["M_rs_day", "W_rs_day"], ascending=False).iloc[0]
    inc = R[R.variant == vname(INCUMBENT)].iloc[0]
    choice = best.variant
    if vname(INCUMBENT) in el.variant.values and inc.M_rs_day >= 0.85 * best.M_rs_day:
        choice = vname(INCUMBENT)
    print("best by score:", best.variant, round(best.M_rs_day), "incumbent:", round(inc.M_rs_day), "-> CHOICE", choice)
    json.dump(dict(choice=choice, incumbent=vname(INCUMBENT), spa_M=spaM, spa_PRE=spaP, walk_forward=wf,
                   eligible=el.variant.tolist(), n_variants=len(VARIANTS)),
              open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=float)


def hold():
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    S = setup()
    res = {}
    labs = [("choice", ch["choice"]), ("incumbent", ch["incumbent"])]
    for lab, vn in labs:
        v = [x for x in VARIANTS if vname(x) == vn][0]
        mo, st, al = v
        out = dict(variant=vn)
        for k in (0.02, 0.01, 0.04):
            T = Table(S[(mo, st)]["B"], k)
            s, d = summarize(T, al, HOLD, END)
            out[f"k{k}"] = s
            if k == 0.02:
                mm = d.groupby(d.index.to_period("M")).sum().round(0)
                out["months"] = {str(a): float(b) for a, b in mm.items()}
                eq = E0 + d.cumsum()
                out["equity_path_monthend"] = {str(a): float(b) for a, b in eq.groupby(eq.index.to_period("M")).last().round(0).items()}
                out["mc_hold_248"] = mc(T, al, HOLD, END, H=248)
                out["mc_hold_496"] = mc(T, al, HOLD, END, H=496)
                out["mc_pre_248"] = mc(T, al, START, HOLD, H=248)
                out["mc_pre_496"] = mc(T, al, START, HOLD, H=496)
                out["p_lose_month"] = R13.PF.p_losing_month(d.values, B=5000)
        rb, one = rand_1lot(S[(mo, st)], HOLD, END)
        out["random_1lot"], out["one_lot"] = rb, one
        res[lab] = out
        print(lab, json.dumps(out, default=float, indent=1), flush=True)
    # post-hoc (labelled; NOT used for anything): all 16 in the holdout at 1 lakh, kappa 0.02
    ph = []
    TB = {k: Table(v["B"]) for k, v in S.items()}
    for v in VARIANTS:
        s, _ = summarize(TB[v[:2]], v[2], HOLD, END)
        ph.append(dict(variant=vname(v), **{k: x for k, x in s.items()}))
    PH = pd.DataFrame(ph)
    PH.to_csv(os.path.join(OUT, "holdout_all_posthoc.csv"), index=False)
    print("POST-HOC all variants, holdout:"); print(PH.round(3).to_string())
    json.dump(res, open(os.path.join(OUT, "holdout.json"), "w"), indent=1, default=float)


if __name__ == "__main__":
    {"pre": pre, "hold": hold}[sys.argv[1]]()
