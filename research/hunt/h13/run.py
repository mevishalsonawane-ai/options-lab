"""h13: which option should Liquidity 15+5 buy? (strike x series x premium-% exits; entries/exits unchanged). PREREG.md.

    OBUY_CACHE=<scratch>/hunt/h13/cache python3 -I research/hunt/h13/run.py pre    # everything chosen on < 2025-10-01
    OBUY_CACHE=<scratch>/hunt/h13/cache python3 -I research/hunt/h13/run.py hold   # ONE holdout test of choice.json
(packs: research/hunt/h13/build.py)
"""
from __future__ import annotations

import dataclasses
import json
import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h10"))
import cap  # noqa: E402  (h10 capacity model; imports h7 sim -> obuy, h4 on path)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy import overfit as OF  # noqa: E402
from obuy.costs import floor_tick  # noqa: E402
from obuy.engine import Pack, Store, positions  # noqa: E402
from obuy.strategies.liquidity import ARM_EXITS  # noqa: E402
import portfolio as PF  # noqa: E402  (h4)

OUT = os.path.join(C.CACHE, "h13")
HOLD = pd.Timestamp("2025-10-01")
REG = pd.Timestamp("2024-12-01")
START = pd.Timestamp("2021-10-01")
TARGET = 5000.0
L_D, L_M = 15 * TARGET, 50 * TARGET
UNDS = ["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"]
REF = dict(BANKNIFTY=26, FINNIFTY=3, MIDCPNIFTY=11)
BN_GRID = [10, 20, 30, 50, 75, 100, 150, 200, 287, 400]
KAPPAS = (0.02, 0.01, 0.04)
MONEY = (-1, 0, 1, 2)
SERIES = ("near", "month")
STOPS = ("fixed", "scaled")
VARIANTS = [(mo, s, st) for s in SERIES for mo in MONEY for st in STOPS]
INCUMBENT = (1, "near", "fixed")
SQC = ARM_EXITS.sq_off - C.OPEN_M
CAL = pd.read_csv(os.path.join(C.SCRATCH, "hunt/h4/cache/h4/daily_real.csv"), index_col=0, parse_dates=True).index
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)


def vname(v):
    mo, s, st = v
    m = "ATM" if mo == 0 else (f"ITM{mo}" if mo > 0 else f"OTM{-mo}")
    return f"{m}/{s}/{st}"


# ------------------------------------------------------------------------------------------------ trades
def load():
    with open(os.path.join(OUT, "packs.pkl"), "rb") as f:
        metas, arr, sig = pickle.load(f)
    st = Store()
    st.arr = arr
    return metas, st


def stop_of(meta, med):
    f = meta.f.values.copy()
    miss = ~np.isfinite(f)
    f[miss] = meta.und.map(med).values[miss]
    return np.clip(np.round(0.15 * f, 2), 0.05, 0.60)


def run_trades(meta, store, stops, pos):
    """Arm exits with a per-row premium stop (time-stop gain = stop / 3) -> trades with row / stop columns."""
    pk = Pack(meta, store, 1)
    out = []
    for sp in np.unique(stops):
        idx = np.nonzero(stops == sp)[0]
        sub = pk.subset(idx)
        ex = dataclasses.replace(ARM_EXITS, stop_pct=float(sp), time_gain=float(sp) / 3.0)
        tr = sub.run(ex, cap.EXE)
        rows = idx[np.nonzero(sub.meta.c0.values < SQC - 1)[0]]
        assert len(rows) == len(tr)
        tr["row"] = rows
        tr["stop"] = sp
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


class Book(cap.Book):
    pass


def simulate(b, n0, cap_=cap.CAP, kappa=cap.KAPPA):
    """h10 cap.simulate (flat lots, no adds) with the per-trade premium stop for the gross stop print."""
    tr = b.tr
    N = len(tr)
    ri = np.arange(N)
    fl, co = cap.EXE.fills, cap.EXE.costs
    lot, dn = b.lot, b.dn
    c0, xc = tr.c0.values, tr.xcol.values
    e = tr.entry.values
    n0 = np.full(N, float(n0))
    imp = cap.imp
    v = b.V5[ri, c0]
    q0 = np.minimum(n0, np.maximum(1.0, np.floor(cap_ * v)))
    pe = e * (1 + imp(q0, v, kappa))
    gross_g = -b.O[ri, c0] * q0 * lot
    cash = -pe * q0 * lot
    chg = co.charge(True, pe, q0 * lot, dn)
    prem = pe * q0 * lot
    Q = q0.copy()
    vx = b.V5[ri, xc]
    a = np.minimum(Q, np.maximum(1.0, np.floor(cap_ * vx)))
    px = tr.exit.values * (1 - imp(a, vx, kappa))
    trig = floor_tick(e * (1 - tr.stop.values))
    Ox = b.O[ri, xc]
    graw = np.where(tr.why.values == "stop", np.fmin(trig, Ox), np.where(np.isnan(Ox), b.Clf[ri, xc], Ox))
    cash += px * a * lot
    gross_g += graw * a * lot
    chg += co.charge(False, px, a * lot, dn)
    R = Q - a
    end = xc.copy()
    slices = np.ones(N)
    k = int(xc[R > 0].min()) + 1 if (R > 0).any() else C.W
    while k < C.W and (R > 0).any():
        act = (R > 0) & (k > xc)
        if act.any():
            vm = b.Vl[:, k]
            allow = np.where((vm > 0) & ~np.isnan(b.Cl[:, k]), np.maximum(1.0, np.floor(cap_ * vm)), 0.0)
            if k == C.W - 1:
                allow = R.copy()
            sl = np.where(act, np.minimum(R, allow), 0.0)
            m = sl > 0
            if m.any():
                raw = b.Clf[m, k]
                v5k = b.V5[m, k]
                p = fl.sell(raw, v5k) * (1 - imp(sl[m], v5k, kappa))
                cash[m] += p * sl[m] * lot[m]
                gross_g[m] += raw * sl[m] * lot[m]
                chg[m] += co.charge(False, p, sl[m] * lot[m], dn[m])
                R[m] -= sl[m]
                end[m] = k
                slices[m] += 1
        k += 1
    t = tr[["cand", "parent", "und", "book", "day", "entry_min", "exit_min", "lot", "why", "stop"]].copy()
    for nm, x in dict(gross=gross_g, charges=chg, net=cash - chg, lots=Q, desired=n0, prem=prem,
                      exit_end_min=end + C.OPEN_M, slices=slices, clipped=q0 < n0).items():
        t[nm] = x
    return t


def setup():
    metas, store = load()
    V = {}
    for v in VARIANTS:
        mo, s, st = v
        mr, mp = metas[(mo, s, "real")], metas[(mo, s, "pool")]
        if st == "fixed":
            sr, sp = np.full(len(mr), 0.15), np.full(len(mp), 0.15)
        else:
            pre = mr[pd.to_datetime(mr.day) < HOLD]
            med = pre.groupby("und").f.median().to_dict()
            sr, sp = stop_of(mr, med), stop_of(mp, med)
        pk, tr = run_trades(mr, store, sr, True)
        pp, tp = run_trades(mp, store, sp, False)
        V[v] = dict(B={u: Book(pk, tr[tr.und == u]) for u in UNDS}, BP={u: Book(pp, tp[tp.und == u]) for u in UNDS},
                    fmiss=float((~np.isfinite(mr.f.values)).mean()), stop_med=float(np.median(sr)),
                    stop_p10=float(np.quantile(sr, .1)), stop_p90=float(np.quantile(sr, .9)))
    return V


# ------------------------------------------------------------------------------------------------ stats
def daily(t, lo, hi=None, col="net"):
    s = t.groupby("day")[col].sum().reindex(CAL, fill_value=0.0)
    s = s[s.index >= lo]
    return s[s.index < hi] if hi is not None else s


def port(B, n, kappa=cap.KAPPA, books=None, cap_=cap.CAP):
    return pd.concat([simulate(B[u], n[u], cap_, kappa) for u in (books or UNDS)], ignore_index=True)


def stats(t, lo, hi=None, plm=True):
    d, g = daily(t, lo, hi), daily(t, lo, hi, "gross")
    st = PF.stats_row(d.values, d.index)
    m = (t.day >= lo) & ((t.day < hi) if hi is not None else True)
    x = t[m]
    r = dict(net_day=d.mean(), gross_day=g.mean(), worst_day=st["worst_day"], worst_month=st["worst_month"],
             losing_months=st["losing_months"], maxdd=st["maxdd"], sharpe=st["sharpe"], trades=len(x),
             fill_ratio=x.lots.sum() / max(x.desired.sum(), 1), charges_day=x.charges.sum() / len(d))
    if plm:
        r["P_lose_month"] = PF.p_losing_month(d.values, B=5000)
    return r


def outlay(t, lo, hi=None):
    m = (t.day >= lo) & ((t.day < hi) if hi is not None else True)
    x = t[m][["day", "entry_min", "exit_end_min", "prem"]].rename(columns={"exit_end_min": "exit_min", "prem": "entry"})
    x["qty"] = 1.0
    O = PF.outlay(x, CAL)
    O = O[O > 0]
    return float(O.quantile(0.95)), float(O.max())


def capacity(B, lo, hi):
    out = {}
    for u in UNDS:
        b = B[u]
        m = ((b.tr.day >= lo) & (b.tr.day < hi)).values
        vx = b.V5[np.arange(len(b.tr)), b.tr.xcol.values][m]
        out[u] = max(1, int(np.floor(cap.CAP * np.quantile(vx, 0.25)))) if m.any() else 1
    return out


def book_curve(b, sizes, kappa, wins):
    """{size: {window: net/day, window+'_g': gross/day}} for one book (books are independent without limits)."""
    res = {}
    for n in sorted(set(sizes)):
        t = simulate(b, n, cap.CAP, kappa)
        r = {}
        for wn, (lo, hi) in wins.items():
            r[wn] = daily(t, lo, hi).mean()
            r[wn + "_g"] = daily(t, lo, hi, "gross").mean()
        res[n] = r
    return res


def max_realistic(B, capw, wn, lo, hi, kappa):
    """FIN / MIDCP at capacity, BN scanned on BN_GRID capped at its capacity -> (peak net/day, BN lots, gross/day, curve)."""
    w = {wn: (lo, hi)}
    base = sum(book_curve(B[u], [capw[u]], kappa, w)[capw[u]][wn] for u in ("FINNIFTY", "MIDCPNIFTY"))
    base_g = sum(book_curve(B[u], [capw[u]], kappa, w)[capw[u]][wn + "_g"] for u in ("FINNIFTY", "MIDCPNIFTY"))
    grid = sorted({min(g, capw["BANKNIFTY"]) for g in BN_GRID} | {capw["BANKNIFTY"]})
    bc = book_curve(B["BANKNIFTY"], grid, kappa, w)
    curve = {n: base + bc[n][wn] for n in grid}
    best = max(curve, key=curve.get)
    return curve[best], best, base_g + bc[best][wn + "_g"], curve


def solve5k(B, capw, lo, hi, kappa=cap.KAPPA):
    w = {"x": (lo, hi)}
    base = sum(book_curve(B[u], [capw[u]], kappa, w)[capw[u]]["x"] for u in ("FINNIFTY", "MIDCPNIFTY"))
    best = None
    for n in list(range(1, 61)) + list(range(65, 301, 5)):
        if n > capw["BANKNIFTY"]:
            break
        d = base + book_curve(B["BANKNIFTY"], [n], kappa, w)[n]["x"]
        if best is None or abs(d - TARGET) < abs(best[1] - TARGET):
            best = (n, d)
    return dict(capw, BANKNIFTY=best[0]), best[1]


# ------------------------------------------------------------------------------------------------ stages
def pre():
    V = setup()
    rows, XM, XP, yearly = [], [], [], {}
    wins = {"W": (START, REG), "M": (REG, HOLD), "PRE": (START, HOLD)}
    for v in VARIANTS:
        B, BP = V[v]["B"], V[v]["BP"]
        r = dict(variant=vname(v), stop_med=V[v]["stop_med"], stop_p10=V[v]["stop_p10"], stop_p90=V[v]["stop_p90"],
                 f_missing=V[v]["fmiss"])
        t = port(B, REF)
        for wn, (lo, hi) in wins.items():
            d = daily(t, lo, hi)
            r[f"ref_net_{wn}"] = d.mean()
            r[f"ref_gross_{wn}"] = daily(t, lo, hi, "gross").mean()
            r[f"ref_sharpe_{wn}"] = d.mean() / (d.std() + 1e-9) * np.sqrt(248)
        r["ref_1lot_net_PRE"] = daily(port(B, dict(BANKNIFTY=1, FINNIFTY=1, MIDCPNIFTY=1)), START, HOLD).mean()
        XM.append(daily(t, REG, HOLD).values)
        XP.append(daily(t, START, HOLD).values)
        yearly[vname(v)] = t[t.day < HOLD].groupby(t.day.dt.year).net.sum() / daily(t, START, HOLD).groupby(
            daily(t, START, HOLD).index.year).size()
        tp = pd.concat([simulate(BP[u], REF[u]) for u in UNDS], ignore_index=True)
        rb = OF.random_baseline(t[t.day < HOLD], tp[tp.day < HOLD])
        r.update(rand_p=rb["p"], rand_obs=rb["obs"], rand_null=rb["null_mean"])
        capM, capW = capacity(B, REG, HOLD), capacity(B, START, REG)
        r.update({f"capM_{u[:3]}": capM[u] for u in UNDS})
        r.update({f"capW_{u[:3]}": capW[u] for u in UNDS})
        for k in KAPPAS:
            pkM, bnM, gM, _ = max_realistic(B, capM, "M", REG, HOLD, k)
            r[f"maxM_k{k}"], r[f"maxM_bn_k{k}"], r[f"maxM_gross_k{k}"] = pkM, bnM, gM
        pkW, bnW, gW, _ = max_realistic(B, capW, "W", START, REG, 0.02)
        r["maxW_k0.02"], r["maxW_bn"], r["maxW_gross"] = pkW, bnW, gW
        rows.append(r)
        print(vname(v), {k: (round(x, 3) if isinstance(x, float) else x) for k, x in r.items() if k != "variant"}, flush=True)
    R = pd.DataFrame(rows)
    R["rand_bh_q"] = OF.bh(R.rand_p.values)
    R.to_csv(os.path.join(OUT, "pre_variants.csv"), index=False)
    Y = pd.DataFrame(yearly).T
    Y.to_csv(os.path.join(OUT, "pre_yearly_ref.csv"))
    print(R.round(3).to_string())
    print("per-year net/day at reference size:"); print(Y.round(0).to_string())
    spaM, spaP = OF.spa(np.column_stack(XM), B=2000), OF.spa(np.column_stack(XP), B=2000)
    print("SPA/RC era M:", spaM, " PRE:", spaP)
    # walk-forward (anchored yearly, reference size)
    wf = []
    for ty in (2023, 2024, 2025):
        tr_ = Y[[c for c in Y.columns if c < ty]]
        cal = CAL[(CAL >= START) & (CAL < HOLD)]
        nd = {y: int((cal.year == y).sum()) for y in tr_.columns}
        score = (tr_ * pd.Series(nd)).sum(axis=1) / sum(nd.values())
        pick = score.idxmax()
        wf.append(dict(test=ty, pick=pick, test_net_day=Y.loc[pick, ty], incumbent=Y.loc[vname(INCUMBENT), ty]))
    WF = pd.DataFrame(wf)
    print("walk-forward:"); print(WF.round(0).to_string())
    # choice
    el = R[(R.ref_net_W > 0) & (R.ref_net_M > 0) & (R.rand_bh_q < 0.05)].copy()
    print("eligible:", el.variant.tolist())
    el["key"] = list(zip(el["maxM_k0.02"].round(0), el["maxW_k0.02"]))
    best = el.sort_values(["maxM_k0.02", "maxW_k0.02"], ascending=False).iloc[0]
    inc = R[R.variant == vname(INCUMBENT)].iloc[0]
    choice = best.variant
    if vname(INCUMBENT) in el.variant.values and inc["maxM_k0.02"] >= 0.85 * best["maxM_k0.02"]:
        choice = vname(INCUMBENT)
    print("best by score:", best.variant, round(best["maxM_k0.02"]), "incumbent:", round(inc["maxM_k0.02"]), "-> CHOICE", choice)
    vc = [v for v in VARIANTS if vname(v) == choice][0]
    plans = {}
    for lab, v in (("choice", vc), ("incumbent", INCUMBENT)):
        B = V[v]["B"]
        capM = capacity(B, REG, HOLD)
        size, d5 = solve5k(B, capM, REG, HOLD)
        t = port(B, size)
        o95, omax = outlay(t, REG, HOLD)
        plans[lab] = dict(variant=vname(v), size=size, regime_net_day=d5, stats_M=stats(t, REG, HOLD),
                          stats_PRE=stats(t, START, HOLD), outlay_M=[o95, omax],
                          per_year=t[t.day < HOLD].groupby(t.day.dt.year)[["net", "gross"]].sum().round(0).to_dict())
        print(lab, json.dumps(plans[lab], default=float, indent=None)[:1500], flush=True)
    json.dump(dict(choice=choice, plans=plans, spa_M=spaM, spa_PRE=spaP, walk_forward=wf, n_variants=len(VARIANTS),
                   eligible=el.variant.tolist()), open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=float)


def hold():
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    V = setup()
    res = {}
    for lab in ("choice", "incumbent"):
        p = ch["plans"][lab]
        v = [x for x in VARIANTS if vname(x) == p["variant"]][0]
        B, BP = V[v]["B"], V[v]["BP"]
        size = p["size"]
        out = {}
        for k in KAPPAS:
            t = port(B, size, k)
            out[f"k{k}_none"] = stats(t, HOLD, plm=k == 0.02)
            keep = cap.gate(t, L_D, L_M)
            out[f"k{k}_limits"] = stats(t[keep].reset_index(drop=True), HOLD, plm=k == 0.02)
        t = port(B, size)
        out["by_index"] = t[t.day >= HOLD].groupby("und")[["net", "gross"]].sum().round(0).to_dict()
        d = daily(t, HOLD)
        mm = d.groupby(d.index.to_period("M")).sum().round(0)
        out["months"] = {str(a): float(b) for a, b in mm.items()}
        out["best5_share"] = float(d.nlargest(5).sum() / d.sum()) if d.sum() else np.nan
        out["outlay_hold"] = outlay(t, HOLD)
        tp = pd.concat([simulate(BP[u], size[u]) for u in UNDS], ignore_index=True)
        out["random"] = OF.random_baseline(t[t.day >= HOLD], tp[tp.day >= HOLD])
        capH = capacity(B, HOLD, pd.Timestamp("2100-01-01"))
        out["capacity_hold"] = capH
        for k in KAPPAS:
            pk_, bn, g, curve = max_realistic(B, dict(size, BANKNIFTY=max(BN_GRID)), "H", HOLD, None, k)
            out[f"curve_k{k}"] = {int(a): round(b) for a, b in curve.items()}
        # capital at the max-realistic size (era M capacities, BN at its era-M capacity), pre-holdout and holdout
        capM = capacity(B, REG, HOLD)
        tt = port(B, capM)
        out["max_size"] = capM
        out["outlay_max_size_M"] = outlay(tt, REG, HOLD)
        out["max_size_M_stats"] = stats(tt, REG, HOLD, plm=False)
        out["max_size_hold_stats"] = stats(tt, HOLD, plm=False)
        out["max_size_hold_k004"] = stats(port(B, capM, 0.04), HOLD, plm=False)
        res[lab] = out
        print(lab, p["variant"], json.dumps(out, default=float, indent=1), flush=True)
    # post-hoc, every variant at the reference size in the holdout (NOT used for anything)
    ph = []
    for v in VARIANTS:
        t = port(V[v]["B"], REF)
        ph.append(dict(variant=vname(v), hold_net_day=daily(t, HOLD).mean(), hold_gross_day=daily(t, HOLD, None, "gross").mean()))
    PH = pd.DataFrame(ph)
    PH.to_csv(os.path.join(OUT, "holdout_all_posthoc.csv"), index=False)
    print(PH.round(0).to_string())
    json.dump(res, open(os.path.join(OUT, "holdout.json"), "w"), indent=1, default=float)


if __name__ == "__main__":
    {"pre": pre, "hold": hold}[sys.argv[1]]()
