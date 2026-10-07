"""h10 realistic plan (see PREREG.md).

    OBUY_CACHE=<scratch>/hunt/h10/cache python3 -I research/hunt/h10/run.py pre    # everything chosen on < 2025-10-01
    OBUY_CACHE=<scratch>/hunt/h10/cache python3 -I research/hunt/h10/run.py hold   # ONE holdout test of choice.json
(packs: OBUY_CACHE=<scratch>/hunt/h10/cache python3 -I research/hunt/h7/build.py)
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cap  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from cap import sim  # noqa: E402
from obuy import overfit as OF  # noqa: E402
import portfolio as PF  # noqa: E402  (h4)

HOLD = pd.Timestamp("2025-10-01")
LONG = pd.Timestamp("2023-06-01")
REG = pd.Timestamp("2024-12-01")
TARGET = 5000.0
L_D, L_M = 15 * TARGET, 50 * TARGET
CAL = pd.read_csv(os.path.join(sim.C.SCRATCH, "hunt/h4/cache/h4/daily_real.csv"), index_col=0, parse_dates=True).index
H7 = json.load(open(os.path.join(sim.C.SCRATCH, "hunt/h7/cache/h7/choice.json")))
EDGES = H7["edges"]["terc"]
OUT = os.path.join(sim.C.CACHE, "h10")
UNDS = ["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"]
LIMITS = {"none": (None, None), "daily": (L_D, None), "monthly": (None, L_M), "both": (L_D, L_M)}
SENS = [(0.15, 0.02), (0.10, 0.02), (0.20, 0.02), (0.15, 0.01), (0.15, 0.04)]
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)


def setup():
    P = sim.load()
    tr = sim.base_trades(P["real"][0], cap.EXE)
    pool = sim.base_trades(P["real"][1], cap.EXE, pos=False)
    B = {u: cap.Book(P["real"][0], tr[tr.und == u]) for u in UNDS}
    BP = {u: cap.Book(P["real"][1], pool[pool.und == u]) for u in UNDS}
    return B, BP


def book_trades(b, rule, n, c=cap.CAP, k=cap.KAPPA):
    if rule == "flat":
        o = cap.simulate(b, np.full(len(b.tr), float(n)), 0.0, c, k)
    else:
        o = cap.simulate(b, cap.room_units(b.tr, EDGES) * n, float(n), c, k)
    t = b.tr[["cand", "und", "book", "day", "entry_min", "exit_min", "lot", "why"]].copy()
    if "parent" in b.tr:
        t["parent"] = b.tr.parent.values
    for col in o.columns:
        t[col] = o[col].values
    return t


def port(B, rule, n, lim="none", c=cap.CAP, k=cap.KAPPA, scale_lim=1.0):
    t = pd.concat([book_trades(B[u], rule, n[u], c, k) for u in UNDS], ignore_index=True)
    ld, lm = LIMITS[lim]
    keep = cap.gate(t, None if ld is None else ld * scale_lim, None if lm is None else lm * scale_lim)
    return t[keep].reset_index(drop=True)


def daily(t, lo, hi=None, col="net"):
    s = t.groupby("day")[col].sum().reindex(CAL, fill_value=0.0)
    s = s[s.index >= lo]
    return s[s.index < hi] if hi is not None else s


def stats(t, lo, hi=None, plm=True):
    d = daily(t, lo, hi)
    g = daily(t, lo, hi, "gross")
    st = PF.stats_row(d.values, d.index)
    r = dict(net_day=d.mean(), gross_day=g.mean(), worst_day=st["worst_day"], worst_month=st["worst_month"],
             losing_months=st["losing_months"], maxdd=st["maxdd"], sharpe=st["sharpe"], pos_days=st["pos_days"])
    if plm:
        r["P_lose_month"] = PF.p_losing_month(d.values, B=5000)
    m = (t.day >= lo) & ((t.day < hi) if hi is not None else True)
    x = t[m]
    r.update(trades=len(x), lots_mean=x.lots.mean(), clipped=x.clipped.mean(), fill_ratio=x.lots.sum() / x.desired.sum(),
             slices_mean=x.slices.mean(), charges_day=x.charges.sum() / len(d))
    return r


def outlay(t, lo, hi=None):
    m = (t.day >= lo) & ((t.day < hi) if hi is not None else True)
    x = t[m][["day", "entry_min", "exit_end_min", "prem"]].rename(columns={"exit_end_min": "exit_min", "prem": "entry"})
    x["qty"] = 1.0
    O = PF.outlay(x, CAL)
    O = O[O > 0]
    return float(O.quantile(0.95)), float(O.max())


def solve_bn(B, rule, n, lo, hi, grid):
    best, curve = None, []
    for v in grid:
        nn = dict(n, BANKNIFTY=v)
        d = daily(port(B, rule, nn), lo, hi).mean()
        curve.append((v, d))
        if best is None or abs(d - TARGET) < abs(best[1] - TARGET):
            best = (v, d)
    return best, curve


def capacity(B):
    out = {}
    for u in UNDS:
        b = B[u]
        m = ((b.tr.day >= REG) & (b.tr.day < HOLD)).values
        vx = b.V5[np.arange(len(b.tr)), b.tr.xcol.values][m]
        out[u] = dict(p25_exit_v5=float(np.quantile(vx, 0.25)), N=int(np.floor(cap.CAP * np.quantile(vx, 0.25))),
                      median_exit_v5=float(np.median(vx)))
    return out


def pre():
    os.makedirs(OUT, exist_ok=True)
    B, BP = setup()
    capy = capacity(B)
    print("capacity (regime window, exit 5-min lots):", capy)
    N = {u: capy[u]["N"] for u in UNDS}
    assert (N["BANKNIFTY"], N["FINNIFTY"], N["MIDCPNIFTY"]) == (287, 3, 11), N
    base = {"flat": dict(FINNIFTY=N["FINNIFTY"], MIDCPNIFTY=N["MIDCPNIFTY"]),
            "h7": dict(FINNIFTY=max(1, round(N["FINNIFTY"] / 2.45)), MIDCPNIFTY=max(1, round(N["MIDCPNIFTY"] / 2.45)))}
    sizes, curves = {}, {}
    for rule in ("flat", "h7"):
        grid = list(range(1, 41)) + list(range(45, 301, 5)) + [350, 400, 500] if rule == "flat" else \
            list(range(1, 21)) + list(range(22, 121, 2)) + [140, 160, 200]
        best, curve = solve_bn(B, rule, base[rule], REG, HOLD, grid)
        sizes[rule] = dict(base[rule], BANKNIFTY=best[0])
        curves[rule] = curve
        print(rule, "size for Rs 5,000/day (regime window):", sizes[rule], "-> %.0f/day" % best[1])
    # capacity curve: Rs/day vs BANKNIFTY size, both windows; plus small books alone
    cv = []
    for rule in ("flat", "h7"):
        for v, d in curves[rule]:
            cv.append(dict(rule=rule, bn=v, regime_net_day=d))
    CV = pd.DataFrame(cv)
    CV.to_csv(os.path.join(OUT, "capacity_curve.csv"), index=False)
    for rule in ("flat", "h7"):
        c = CV[CV.rule == rule]
        pk = c.loc[c.regime_net_day.idxmax()]
        print(rule, "peak regime net/day %.0f at BN %d; at BN capacity-equivalent: %s" % (
            pk.regime_net_day, pk.bn, c[c.bn.isin([N["BANKNIFTY"], round(N["BANKNIFTY"] / 2.45)])].round(0).values.tolist()))
    # per-index curves (flat, regime window) for the max-capacity question
    per = []
    for u in UNDS:
        for v in [1, 2, 3, 5, 8, 11, 15, 20, 30, 50, 75, 100, 150, 200, 287, 400]:
            t = book_trades(B[u], "flat", v)
            for lab, lo in (("regime", REG), ("long", LONG)):
                d = daily(t, lo, HOLD)
                per.append(dict(und=u, lots=v, window=lab, net_day=d.mean(), gross_day=daily(t, lo, HOLD, "gross").mean(),
                                net_per_lot_day=d.mean() / v,
                                fill_ratio=t[(t.day >= lo) & (t.day < HOLD)].lots.sum() / t[(t.day >= lo) & (t.day < HOLD)].desired.sum()))
    PER = pd.DataFrame(per)
    PER.to_csv(os.path.join(OUT, "per_index_curve.csv"), index=False)
    print(PER[PER.window == "regime"].round(2).to_string())
    # all variants: rule x limits x sensitivity (40 series), stats on regime and long windows
    rows, X, names = [], [], []
    for rule in ("flat", "h7"):
        for c, k in SENS:
            for lim in LIMITS:
                t = port(B, rule, sizes[rule], lim, c, k)
                for wl, lo in (("regime", REG), ("long", LONG)):
                    r = dict(rule=rule, cap=c, kappa=k, limits=lim, window=wl)
                    r.update(stats(t, lo, HOLD, plm=(c, k) == (0.15, 0.02)))
                    rows.append(r)
                X.append(daily(t, REG, HOLD).values)
                names.append(f"{rule}|{c}|{k}|{lim}")
    R = pd.DataFrame(rows)
    R.to_csv(os.path.join(OUT, "pre_variants.csv"), index=False)
    print(R.round(3).to_string())
    S = OF.spa(np.column_stack(X), B=2000)
    print("SPA/RC over", len(names), "regime-window daily series (vs 0):", S)
    # choice
    sh = {rule: R[(R.rule == rule) & (R.cap == 0.15) & (R.kappa == 0.02) & (R.limits == "none") & (R.window == "regime")].sharpe.iloc[0]
          for rule in ("flat", "h7")}
    choice = "h7" if sh["h7"] - sh["flat"] >= 0.10 else "flat"
    print("Sharpe regime window:", sh, "-> CHOICE", choice)
    # random entries (same exits, capacity model, lots), per rule, no limits
    rb = {}
    for rule in ("flat", "h7"):
        t = port(B, rule, sizes[rule], "none")
        tp = pd.concat([book_trades(BP[u], rule, sizes[rule][u]) for u in UNDS], ignore_index=True)
        for wl, lo in (("regime", REG), ("long", LONG)):
            m = (t.day >= lo) & (t.day < HOLD)
            mp = (tp.day >= lo) & (tp.day < HOLD)
            rb[f"{rule}|{wl}"] = OF.random_baseline(t[m], tp[mp])
    print("random-entry baseline:", {a: {k: round(v, 4) for k, v in b.items()} for a, b in rb.items()})
    q = OF.bh(np.array([v["p"] for v in rb.values()]))
    # chosen plan details
    t = port(B, choice, sizes[choice], "both")
    yr = t[t.day < HOLD].assign(y=t.day.dt.year).groupby("y")[["net", "gross"]].sum().round(0)
    print("chosen per year (limits on):"); print(yr)
    o95, omax = outlay(t, REG, HOLD)
    print("premium tied up regime window p95 %.0f max %.0f" % (o95, omax))
    # monthly distribution per 1 'rung' for the ladder: 1 lot each book, flat, regime window, no limits
    ladder = {}
    for lab, nn in (("1/1/1", dict(BANKNIFTY=1, FINNIFTY=1, MIDCPNIFTY=1)), ("plan", sizes[choice])):
        tt = port(B, choice if lab == "plan" else "flat", nn, "none")
        d = daily(tt, LONG, HOLD)
        m = d.groupby(d.index.to_period("M")).sum()
        q3 = m.rolling(3).sum().dropna()
        ladder[lab] = dict(net_day=d.mean(), month_mean=m.mean(), month_sd=m.std(), month_p10=m.quantile(0.1),
                           q3_p10=q3.quantile(0.10), q3_p05=q3.quantile(0.05), maxdd=PF.maxdd(d.values), worst_month=m.min())
    print("ladder stats (2023-06..2025-09):", {a: {k: round(v) for k, v in b.items()} for a, b in ladder.items()})
    json.dump(dict(capacity=capy, sizes=sizes, choice=choice, sharpe_regime=sh, spa=S, n_series=len(names),
                   random=rb, random_bh_q=list(q), ladder=ladder, outlay_regime=[o95, omax],
                   limits=dict(daily=L_D, monthly=L_M)), open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=float)


def hold():
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    B, BP = setup()
    rows = []
    for rule in ("flat", "h7"):
        for lim in LIMITS:
            for c, k in SENS:
                t = port(B, rule, ch["sizes"][rule], lim, c, k)
                for wl, lo, hi in (("regime-pre", REG, HOLD), ("HOLDOUT", HOLD, None)):
                    r = dict(rule=rule, limits=lim, cap=c, kappa=k, window=wl)
                    r.update(stats(t, lo, hi, plm=(c, k) == (0.15, 0.02)))
                    rows.append(r)
    R = pd.DataFrame(rows)
    R.to_csv(os.path.join(OUT, "holdout.csv"), index=False)
    print(R[(R.cap == 0.15) & (R.kappa == 0.02)].round(3).to_string())
    print(R[(R.limits == "both")].round(3).to_string())
    choice = ch["choice"]
    t = port(B, choice, ch["sizes"][choice], "both")
    h = t[t.day >= HOLD]
    print("holdout by index (net, gross, lots mean, fill ratio):")
    print(h.groupby("und").agg(net=("net", "sum"), gross=("gross", "sum"), lots=("lots", "mean"), desired=("desired", "mean"),
                               slices=("slices", "mean"), clipped=("clipped", "mean")).round(2))
    d = daily(t, HOLD)
    print("holdout best 5 days share of net: %.2f" % (d.nlargest(5).sum() / d.sum()))
    print("holdout months:"); print(d.groupby(d.index.to_period("M")).sum().round(0).to_string())
    o95, omax = outlay(t, HOLD)
    print("premium tied up holdout p95 %.0f max %.0f" % (o95, omax))
    # random entries in the holdout
    tp = pd.concat([book_trades(BP[u], choice, ch["sizes"][choice][u]) for u in UNDS], ignore_index=True)
    t0 = port(B, choice, ch["sizes"][choice], "none")
    print("holdout random baseline:", OF.random_baseline(t0[t0.day >= HOLD], tp[tp.day >= HOLD]))
    # holdout liquidity: exit 5-min volume per index
    for u in UNDS:
        b = B[u]
        m = (b.tr.day >= HOLD).values
        vx = b.V5[np.arange(len(b.tr)), b.tr.xcol.values][m]
        print(u, "holdout exit 5-min lots p25 %.0f median %.0f -> 15%% of p25 = %d lots" % (np.quantile(vx, .25), np.median(vx), np.floor(.15 * np.quantile(vx, .25))))
    # post-hoc (reported, not chosen): capacity curve in the holdout, flat, FIN 3 / MIDCP 11
    cv = []
    for v in [1, 5, 10, 20, 30, 40, 60, 80, 100, 150, 200, 287, 400]:
        tt = port(B, "flat", dict(ch["sizes"]["flat"], BANKNIFTY=v))
        cv.append(dict(bn=v, hold_net_day=daily(tt, HOLD).mean(), hold_gross_day=daily(tt, HOLD, None, "gross").mean()))
    print(pd.DataFrame(cv).round(0).to_string())
    per = []
    for u in UNDS:
        for v in [1, 2, 3, 5, 8, 11, 15, 20, 30, 50, 100, 200, 287]:
            tt = book_trades(B[u], "flat", v)
            per.append(dict(und=u, lots=v, hold_net_day=daily(tt, HOLD).mean()))
    print(pd.DataFrame(per).pivot(index="lots", columns="und", values="hold_net_day").round(0).to_string())



def extra():
    """Pre-holdout only, reporting (no choice): capacity curve sensitivity to kappa / cap; BANKNIFTY-only curve."""
    B, _ = setup()
    rows = []
    for c, k in SENS:
        for v in [10, 26, 50, 100, 150, 200, 287, 400]:
            t = port(B, "flat", dict(FINNIFTY=3, MIDCPNIFTY=11, BANKNIFTY=v), "none", c, k)
            tb = book_trades(B["BANKNIFTY"], "flat", v, c, k)
            rows.append(dict(cap=c, kappa=k, bn=v, plan_regime=daily(t, REG, HOLD).mean(), bn_only_regime=daily(tb, REG, HOLD).mean(),
                             bn_only_long=daily(tb, LONG, HOLD).mean()))
    R = pd.DataFrame(rows)
    print(R.round(0).to_string())
    R.to_csv(os.path.join(OUT, "capacity_sens.csv"), index=False)
    t = port(B, "flat", dict(FINNIFTY=3, MIDCPNIFTY=11, BANKNIFTY=26))
    x = t[(t.day >= REG) & (t.day < HOLD)]
    nd = len(daily(t, REG, HOLD))
    print("plan by index, regime window, Rs/day:", (x.groupby("und")[["net", "gross"]].sum() / nd).round(0).to_dict())
    x = t[(t.day >= LONG) & (t.day < HOLD)]
    nd = len(daily(t, LONG, HOLD))
    print("plan by index, long window, Rs/day:", (x.groupby("und")[["net", "gross"]].sum() / nd).round(0).to_dict())



RUNGS = [(1, 1, 1), (3, 1, 2), (6, 1, 3), (10, 2, 4), (15, 2, 6), (20, 3, 8), (26, 3, 11)]


def ladder():
    """Step-up ladder stats per rung (BN/FIN/MIDCP lots, flat), PRE-holdout only (2023-06..2025-09 and regime)."""
    B, _ = setup()
    rows = []
    for bn, fn, mc in RUNGS:
        t = port(B, "flat", dict(BANKNIFTY=bn, FINNIFTY=fn, MIDCPNIFTY=mc))
        for wl, lo in (("long", LONG), ("regime", REG)):
            d = daily(t, lo, HOLD)
            m = d.groupby(d.index.to_period("M")).sum()
            q3 = m.rolling(3).sum().dropna()
            o95, omax = outlay(t, lo, HOLD)
            rows.append(dict(rung=f"{bn}/{fn}/{mc}", window=wl, net_day=d.mean(), gross_day=daily(t, lo, HOLD, "gross").mean(),
                             month_sd=m.std(), q3_p05=q3.quantile(0.05), q3_p25=q3.quantile(0.25), maxdd=PF.maxdd(d.values),
                             worst_month=m.min(), prem_p95=o95, prem_max=omax))
    R = pd.DataFrame(rows)
    print(R.round(0).to_string())
    R.to_csv(os.path.join(OUT, "ladder.csv"), index=False)


if __name__ == "__main__":
    {"pre": pre, "hold": hold, "extra": extra, "ladder": ladder}[sys.argv[1]]()
