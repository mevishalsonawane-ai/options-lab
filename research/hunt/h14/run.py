"""h14 execution policies (see PREREG.md).

    OBUY_CACHE=<scratch>/hunt/h14/cache python3 -I research/hunt/h14/run.py pre    # choice on < 2025-10-01 only
    OBUY_CACHE=<scratch>/hunt/h14/cache python3 -I research/hunt/h14/run.py hold   # ONE holdout test of choice.json
(packs: h7/build.py, then h14/build_atm.py, both with the same OBUY_CACHE)
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import exe  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from exe import cap, sim  # noqa: E402
from obuy import overfit as OF  # noqa: E402

_spec = importlib.util.spec_from_file_location("h10run", os.path.join(os.path.dirname(HERE), "h10", "run.py"))
R10 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(R10)
PF = R10.PF

HOLD, LONG, REG = R10.HOLD, R10.LONG, R10.REG
UNDS = R10.UNDS
SIZE = dict(BANKNIFTY=26, FINNIFTY=3, MIDCPNIFTY=11)
OUT = os.path.join(sim.C.CACHE, "h14")
KAPPAS = (0.01, 0.02, 0.04)
MODELS = ("M1", "M2")
BASE = dict(entry="E0", exit="X0", split=False)
SINGLES = {
    "E1": dict(entry="E1", exit="X0", split=False), "E2": dict(entry="E2", exit="X0", split=False),
    "E3": dict(entry="E3", exit="X0", split=False), "E4": dict(entry="E4", exit="X0", split=False),
    "E5": dict(entry="E5", exit="X0", split=False),
    "X1": dict(entry="E0", exit="X1", split=False), "X2": dict(entry="E0", exit="X2", split=False),
    "X3": dict(entry="E0", exit="X3", split=False),
    "K1": dict(entry="E0", exit="X0", split=True),
}
FAMILIES = {"entry": ["E1", "E2", "E3", "E4", "E5"], "exit": ["X1", "X2", "X3"], "split": ["K1"]}
YEARS = [(2023, pd.Timestamp("2023-01-01"), pd.Timestamp("2024-01-01")),
         (2024, pd.Timestamp("2024-01-01"), pd.Timestamp("2025-01-01")),
         (2025, pd.Timestamp("2025-01-01"), HOLD)]
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)


def setup():
    P = sim.load()
    tr = sim.base_trades(P["real"][0], cap.EXE)
    pool = sim.base_trades(P["real"][1], cap.EXE, pos=False)
    out = {}
    for kind, pk, t in (("real", P["real"][0], tr), ("pool", P["real"][1], pool)):
        for u in UNDS:
            b = cap.Book(pk, t[t.und == u])
            li = exe.leg_itm(b)
            la = exe.leg_atm(b)
            out[(kind, u)] = (b, dict(itm=li, atm=la, dhat=exe.delta_hat(b, la)))
    return out


def book_trades(BK, kind, u, n, pol, model, kappa):
    b, legs = BK[(kind, u)]
    o = exe.simulate(b, legs, n, pol, model, kappa)
    t = b.tr[["cand", "und", "book", "day", "entry_min", "exit_min", "lot", "why"]].copy()
    if kind == "pool":
        t["parent"] = b.tr.parent.values
    for col in o.columns:
        t[col] = o[col].values
    return t


def port(BK, pol, size=SIZE, model="M2", kappa=0.02, lim="none", kind="real"):
    t = pd.concat([book_trades(BK, kind, u, size[u], pol, model, kappa) for u in UNDS], ignore_index=True)
    ld, lm = R10.LIMITS[lim]
    keep = cap.gate(t, ld, lm) if kind == "real" else np.ones(len(t), bool)
    return t[keep].reset_index(drop=True)


def daily(t, lo, hi=None, col="net"):
    return R10.daily(t, lo, hi, col)


def summ(t, lo, hi=None, plm=False):
    r = R10.stats(t, lo, hi, plm=plm)
    m = (t.day >= lo) & ((t.day < hi) if hi is not None else True)
    x = t[m]
    nd = len(daily(t, lo, hi))
    r.update(missed=int(x.missed.sum()), pas_buy_share=x.pas_buy.sum() / max(x.lots.sum(), 1),
             pas_sell_share=x.pas_sell.sum() / max(x.lots.sum(), 1),
             cost_day=(x.gross.sum() - x.net.sum()) / nd, cost_share=1 - x.net.sum() / x.gross.sum() if x.gross.sum() else np.nan)
    return r


def foregone(BK, pol, model, kappa, lo, hi):
    """missed trades of a policy: their count and their baseline net (what was given up)."""
    t = port(BK, pol, model=model, kappa=kappa)
    b = port(BK, BASE, model=model, kappa=kappa)
    m = (t.day >= lo) & ((t.day < hi) if hi is not None else True) & t.missed
    return dict(n=int(m.sum()), base_net_of_missed=float(b.net[m].sum()), base_gross_of_missed=float(b.gross[m].sum()),
                base_net_per_missed=float(b.net[m].mean()) if m.any() else np.nan,
                base_net_per_all=float(b.net[(t.day >= lo) & ((t.day < hi) if hi is not None else True)].mean()))


def pre():
    os.makedirs(OUT, exist_ok=True)
    BK = setup()
    pols = dict(BASE=BASE, **SINGLES)
    rows, ser = [], {}
    for name, pol in pols.items():
        for model in MODELS:
            for k in KAPPAS:
                t = port(BK, pol, model=model, kappa=k)
                ser[(name, model, k)] = daily(t, LONG.replace(year=2023, month=1), HOLD)
                for wl, lo in (("regime", REG), ("long", LONG)):
                    r = dict(policy=name, model=model, kappa=k, window=wl)
                    r.update(summ(t, lo, HOLD, plm=(model == "M2" and k == 0.02 and wl == "regime")))
                    rows.append(r)
                print(name, model, k, "regime net/day %.0f" % rows[-2]["net_day"], flush=True)
    R = pd.DataFrame(rows)
    R.to_csv(os.path.join(OUT, "pre_policies.csv"), index=False)
    show = ["policy", "model", "kappa", "window", "net_day", "gross_day", "cost_day", "cost_share", "fill_ratio", "missed",
            "pas_buy_share", "pas_sell_share", "slices_mean", "sharpe", "maxdd", "worst_month"]
    print(R[show].round(3).to_string())

    def nd(name, model, k, lo, hi):
        s = ser[(name, model, k)]
        s = s[(s.index >= lo) & (s.index < hi)]
        return s.mean()

    gates = {}
    for name in SINGLES:
        up_reg = nd(name, "M2", 0.02, REG, HOLD) - nd("BASE", "M2", 0.02, REG, HOLD)
        up_long = nd(name, "M2", 0.02, LONG, HOLD) - nd("BASE", "M2", 0.02, LONG, HOLD)
        up_yr = {y: nd(name, "M2", 0.02, lo, hi) - nd("BASE", "M2", 0.02, lo, hi) for y, lo, hi in YEARS}
        up_k4 = nd(name, "M2", 0.04, REG, HOLD) - nd("BASE", "M2", 0.04, REG, HOLD)
        ok = up_reg >= 250 and up_long > 0 and sum(v > 0 for v in up_yr.values()) >= 2 and up_k4 > 0
        gates[name] = dict(up_regime=up_reg, up_long=up_long, up_years=up_yr, up_k04=up_k4, pass_=ok)
    G = pd.DataFrame({k: dict(up_regime=v["up_regime"], up_long=v["up_long"], **{f"y{y}": x for y, x in v["up_years"].items()},
                              up_k04=v["up_k04"], passes=v["pass_"]) for k, v in gates.items()}).T
    print("uplift vs BASE (M2, kappa 0.02 unless noted), Rs/day:"); print(G.to_string())
    X = np.column_stack([(ser[(n, "M2", 0.02)] - ser[("BASE", "M2", 0.02)])[lambda s: (s.index >= REG) & (s.index < HOLD)].values
                         for n in SINGLES])
    S = OF.spa(X, B=2000)
    print("SPA / White RC over the 9 regime-window uplift series:", S)
    chosen = {}
    for fam, names in FAMILIES.items():
        ok = [n for n in names if gates[n]["pass_"]]
        chosen[fam] = max(ok, key=lambda n: gates[n]["up_regime"]) if ok else None
    print("adopted per family:", chosen)
    final = dict(BASE)
    if chosen["entry"]:
        final["entry"] = SINGLES[chosen["entry"]]["entry"]
    if chosen["exit"]:
        final["exit"] = SINGLES[chosen["exit"]]["exit"]
    if chosen["split"]:
        final["split"] = True
    comb_rows = []
    n_adopt = sum(v is not None for v in chosen.values())
    if n_adopt >= 2:
        for model in MODELS:
            for k in KAPPAS:
                t = port(BK, final, model=model, kappa=k)
                ser[("COMB", model, k)] = daily(t, LONG.replace(year=2023, month=1), HOLD)
                for wl, lo in (("regime", REG), ("long", LONG)):
                    r = dict(policy="COMB", model=model, kappa=k, window=wl)
                    r.update(summ(t, lo, HOLD))
                    comb_rows.append(r)
        print(pd.DataFrame(comb_rows)[show].round(3).to_string())
        best_single = max((c for c in chosen.values() if c), key=lambda n: gates[n]["up_regime"])
        if nd("COMB", "M2", 0.02, REG, HOLD) < nd(best_single, "M2", 0.02, REG, HOLD):
            final = dict(SINGLES[best_single])
            print("combination worse than", best_single, "-> final =", best_single)
    print("FINAL policy:", final)
    # missed trades (adverse selection), pre-holdout regime + long, M2 kappa .02
    fg = {}
    for name in ("E1", "E2", "E3"):
        for wl, lo in (("regime", REG), ("long", LONG)):
            fg[f"{name}|{wl}"] = foregone(BK, SINGLES[name], "M2", 0.02, lo, HOLD)
    print("missed trades and their baseline net:", json.dumps(fg, indent=0, default=float))
    # random entries: base and final (M2, kappa .02, plan lots)
    rb = {}
    for nm, pol in (("BASE", BASE), ("FINAL", final)):
        t = port(BK, pol)
        tp = port(BK, pol, kind="pool")
        for wl, lo in (("regime", REG), ("long", LONG)):
            m = (t.day >= lo) & (t.day < HOLD)
            mp = (tp.day >= lo) & (tp.day < HOLD)
            rb[f"{nm}|{wl}"] = OF.random_baseline(t[m], tp[mp])
    q = OF.bh(np.array([v["p"] for v in rb.values()]))
    print("random-entry baseline:", {a: {k: round(v, 4) for k, v in b.items()} for a, b in rb.items()}, "BH q", q)
    # capacity scan (reporting)
    cv = []
    for nm, pol in (("BASE", BASE), ("FINAL", final)):
        for k in (0.02, 0.04):
            for bn in (26, 50, 75, 100, 150, 200, 287):
                for model in MODELS:
                    t = port(BK, pol, dict(SIZE, BANKNIFTY=bn), model, k)
                    cv.append(dict(policy=nm, kappa=k, model=model, bn=bn, regime_net_day=daily(t, REG, HOLD).mean(),
                                   regime_gross_day=daily(t, REG, HOLD, "gross").mean()))
    CV = pd.DataFrame(cv)
    CV.to_csv(os.path.join(OUT, "pre_capacity.csv"), index=False)
    print(CV.pivot_table(index=["bn"], columns=["policy", "model", "kappa"], values="regime_net_day").round(0).to_string())
    json.dump(dict(final=final, chosen=chosen, gates=gates, spa=S, random=rb, random_bh_q=list(q), foregone=fg,
                   n_series=len(ser)), open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=float)


def hold():
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    final = ch["final"]
    BK = setup()
    pols = dict(BASE=BASE, FINAL=final, **SINGLES)
    pols["E2m_posthoc"] = dict(entry="E2m", exit="X0", split=False)   # info only: limit on the marginal fill price
    rows = []
    for name, pol in pols.items():
        for model in MODELS:
            for k in KAPPAS:
                lims = ("none", "both") if name in ("BASE", "FINAL") else ("none",)
                for lim in lims:
                    t = port(BK, pol, model=model, kappa=k, lim=lim)
                    for wl, lo, hi in (("regime-pre", REG, HOLD), ("HOLDOUT", HOLD, None)):
                        r = dict(policy=name, model=model, kappa=k, limits=lim, window=wl)
                        r.update(summ(t, lo, hi, plm=(model == "M2" and k == 0.02 and name in ("BASE", "FINAL"))))
                        rows.append(r)
    R = pd.DataFrame(rows)
    R.to_csv(os.path.join(OUT, "holdout.csv"), index=False)
    show = ["policy", "model", "kappa", "limits", "window", "net_day", "gross_day", "cost_day", "cost_share", "fill_ratio",
            "missed", "worst_day", "worst_month", "maxdd", "losing_months", "P_lose_month", "sharpe"]
    print(R[R.window == "HOLDOUT"][show].round(3).to_string())
    for nm, pol in (("BASE", BASE), ("FINAL", final)):
        t = port(BK, pol)
        h = t[t.day >= HOLD]
        print(nm, "holdout by index:")
        print(h.groupby("und").agg(net=("net", "sum"), gross=("gross", "sum"), lots=("lots", "mean"),
                                   desired=("desired", "mean"), missed=("missed", "sum"), slices=("slices", "mean")).round(1))
        d = daily(t, HOLD)
        print(nm, "holdout months:", d.groupby(d.index.to_period("M")).sum().round(0).to_dict())
        print(nm, "premium tied up holdout p95/max", R10.outlay(t, HOLD))
        tp = port(BK, pol, kind="pool")
        print(nm, "holdout random baseline:", OF.random_baseline(h, tp[tp.day >= HOLD]))
    fg = {n: foregone(BK, SINGLES[n], "M2", 0.02, HOLD, None) for n in ("E1", "E2", "E3")}
    print("holdout missed trades:", fg)
    cv = []
    for nm, pol in (("BASE", BASE), ("FINAL", final)):
        for k in (0.02, 0.04):
            for bn in (26, 50, 75, 100, 150, 200, 287):
                for model in MODELS:
                    t = port(BK, pol, dict(SIZE, BANKNIFTY=bn), model, k)
                    cv.append(dict(policy=nm, kappa=k, model=model, bn=bn, hold_net_day=daily(t, HOLD).mean()))
    CV = pd.DataFrame(cv)
    CV.to_csv(os.path.join(OUT, "hold_capacity.csv"), index=False)
    print(CV.pivot_table(index=["bn"], columns=["policy", "model", "kappa"], values="hold_net_day").round(0).to_string())


if __name__ == "__main__":
    {"pre": pre, "hold": hold}[sys.argv[1]]()
