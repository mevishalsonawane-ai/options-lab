"""R60 study (PREREG.md): Solo on SENSEX, MIDCPNIFTY and the MCX commodities. Research only.

    python research/hunt/r60/r60idx.py build               # index day stores (R57 code, five indices)
    python research/hunt/r60/r60mcx.py build               # cleaned MCX markets
    python research/hunt/r60/r60study.py design            # design P&L and walk-forward only
    python research/hunt/r60/r60study.py dryrun            # the final pipeline on design months only (crash check)
    python research/hunt/r60/r60study.py final             # the ONE final-test read (refuses a second)

Writes results/design.json and results/final.json (small files only).
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import date

import numpy as np

import r60cfg as C
import r60idx as I
import r60mcx as X

M = I.M                                  # r57model (purged walk-forward, logistic)
S = I.S                                  # r57setups (index twins, 2x costs)

IDX_IDS = ("I1", "I2", "I3", "I4", "I5")
X_RULES = {f"X{2 * i + 1 + e}": (sym, "SE" if e else "SN") for i, sym in enumerate(("GOLD", "GOLDM", "SILVER", "SILVERM",
                                                                                     "CRUDEOIL", "NATURALGAS"))
           for e in (0, 1)}
X_RULES["X13"] = ("ALL", "SN")
X_RULES["X14"] = ("ALL", "SE")
MCX_IDS = tuple(X_RULES) + ("M1", "M2")
ALL_IDS = IDX_IDS + MCX_IDS
MODELS = {"I4", "I5", "M1", "M2"}


# ------------------------------------------------------------------------------------------------ statistics
def max_dd(daily) -> float:
    if not len(daily):
        return 0.0
    eq = np.concatenate([[0.0], np.cumsum(daily)])
    return float((np.maximum.accumulate(eq) - eq).max())


def net2(t: dict) -> float:
    return float(t["net2"]) if "net2" in t else float(S.cost_2x(t))


def stats(trades: list, sessions: int | None = None) -> dict:
    n = len(trades)
    if not n:
        return dict(trades=0, net=0, net_2x=0, ex_top5=0)
    net = np.array([t["net"] for t in trades], float)
    daily, monthly = defaultdict(float), defaultdict(float)
    for t in trades:
        daily[t["day"]] += t["net"]
        monthly[(t["day"].year, t["day"].month)] += t["net"]
    dv = np.array([daily[d] for d in sorted(daily)])
    tot = float(net.sum())
    top5 = float(np.sort(net)[::-1][:5].sum())
    per, pset = defaultdict(lambda: [0, 0.0]), defaultdict(lambda: [0, 0.0])
    for t in trades:
        per[t["und"]][0] += 1
        per[t["und"]][1] += t["net"]
        pset[t.get("setup", "-")][0] += 1
        pset[t.get("setup", "-")][1] += t["net"]
    cap = [t["rE"] * t["qty"] for t in trades]
    out = dict(trades=n, win=round(float((net > 0).mean()), 3), net=round(tot), per_trade=round(tot / n),
               net_2x=round(sum(net2(t) for t in trades)), max_dd=round(max_dd(dv)), worst_day=round(float(dv.min())),
               months_green=f"{sum(v > 0 for v in monthly.values())}/{len(monthly)}",
               top5_share=round(top5 / tot, 3) if tot > 0 else None, ex_top5=round(tot - top5),
               capital_median=round(float(np.median(cap))), capital_max=round(float(np.max(cap))),
               per_market={u: dict(trades=a, net=round(b)) for u, (a, b) in sorted(per.items())},
               per_setup={u: dict(trades=a, net=round(b)) for u, (a, b) in sorted(pset.items())},
               by_month={f"{y}-{m:02d}": round(v) for (y, m), v in sorted(monthly.items())})
    if sessions:
        out["sessions"] = sessions
        out["trades_per_day"] = round(n / sessions, 3)
    return out


def bh(p) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out


def gate(test: dict, p, q, design_net) -> str:
    fails = []
    if test["net"] <= 0:
        fails.append("final net <= 0")
    if p is None or p >= C.GATE_P:
        fails.append("p >= 0.05")
    if q is None or q > C.GATE_Q:
        fails.append("q > 0.10")
    if test["trades"] < C.GATE_MIN_TRADES:
        fails.append("fewer than 40 trades")
    if test["trades"] and test.get("ex_top5", 0) <= 0:
        fails.append("top-5 >= net")
    if design_net is None or design_net <= 0:
        fails.append("design <= 0")
    return "PASS" if not fails else "FAIL: " + "; ".join(fails)


def p_value(rule_net: float, tot: np.ndarray) -> float:
    return round((1 + int((tot >= rule_net).sum())) / (len(tot) + 1), 4)


# ------------------------------------------------------------------------------------------------ MCX variants
def mcx_rules(cands: list, lo: date, hi: date) -> dict:
    """X1-X14 from the SN / SE candidates on [lo, hi]."""
    out = {}
    for vid, (sym, setup) in X_RULES.items():
        c = [t for t in cands if t["setup"] == setup and lo <= t["day"] <= hi and (sym == "ALL" or t["und"] == sym)]
        if sym == "ALL":
            by = defaultdict(list)
            for t in c:
                by[t["day"]].append(t)
            c = [sorted(v, key=lambda t: (-t["strength"], t["und"]))[0] for _, v in sorted(by.items())]
        out[vid] = sorted(c, key=lambda t: t["entry_ts"])
    return out


def mcx_rows(cands: list) -> list:
    rows = []
    for t in cands:
        r = dict(t)
        r["f"] = X.row(t)
        r["y_win"] = int(t["net"] > 0)
        rows.append(r)
    return rows


def mcx_meta(rows: list, months: list, cal: list, setups) -> tuple:
    rows = [r for r in rows if r["setup"] in setups]
    Xm = M.matrix([r["f"] for r in rows], X.NAMES)
    y = np.array([r["y_win"] for r in rows])
    days = np.array([r["day"] for r in rows], dtype=object)
    sc, thr, _ = M.walk(days, Xm, y, "logit", months, cal)
    tested = np.isfinite(sc)
    trades = I.T.select_a(rows, sc, thr, "logit")
    return trades, dict(auc=M.auc(y[tested], sc[tested]), rows=len(rows), tested=int(tested.sum()))


def mcx_calendar(lo: date, hi: date) -> list:
    s = set()
    for sym in C.MCX_SYMS:
        m = X.market(sym)
        s.update(X.pydate(d) for d in m.days if lo <= X.pydate(d) <= hi)
    return sorted(s)


def mcx_sessions(sym: str, lo: date, hi: date) -> int:
    if sym == "ALL":
        return len(mcx_calendar(lo, hi))
    return sum(1 for d in X.market(sym).days if lo <= X.pydate(d) <= hi)


# ------------------------------------------------------------------------------------------------ twins
def twins(vid: str, trades: list, n: int) -> np.ndarray:
    if vid in IDX_IDS:
        rng = np.random.default_rng(C.SEED)
        lo, hi = I.T.TWIN_WIN["A"]
        return S.twin_totals(trades, n, rng, lo, hi)
    return X.twin_totals(trades, n, C.SEED)


# ------------------------------------------------------------------------------------------------ steps
def load_mcx(part: str) -> list:
    lo, hi = (C.MCX_START, C.DESIGN_END) if part == "design" else (C.FINAL_START, C.END)
    C.check_range(lo, hi, mcx=True)
    p = C.OUT / "mcx" / f"cands_{part}.pkl"
    if p.exists():
        with open(p, "rb") as f:
            return pickle.load(f)
    c = mcx_rows(X.candidates(lo, hi))
    with open(p, "wb") as f:
        pickle.dump(c, f, protocol=pickle.HIGHEST_PROTOCOL)
    return c


def run_variants(irows, mrows, idx_months, mcx_months, ilo, ihi, mlo, mhi, idx_cal, mcx_cal) -> tuple:
    """Every variant's trades on its test span (rules on [lo, hi]; models on their months)."""
    tr, info = I.run_all(irows, idx_months, idx_cal, ilo, ihi)
    a2 = dict(auc=info["auc"], rows=info["rows"], tested=info["tested"])
    extra = {"I4": a2, "I5": a2}
    tr.update(mcx_rules(mrows, mlo, mhi))
    tr["M1"], extra["M1"] = mcx_meta(mrows, mcx_months, mcx_cal, X.SETUPS)
    tr["M2"], extra["M2"] = mcx_meta(mrows, mcx_months, mcx_cal, ("SN", "SE"))
    return tr, extra


def sessions_of(vid: str, ilo, ihi, mlo, mhi, idx_cal) -> int:
    if vid in IDX_IDS:
        und = {"I1": "SENSEX", "I2": "MIDCPNIFTY", "I5": "SENSEX"}.get(vid)
        days = [d for d in idx_cal if ilo <= d <= ihi]
        if und:
            st = I.r57data.load(und)
            days = [d for d in days if d in st and st[d].atm is not None]
        return len(days)
    sym = X_RULES.get(vid, ("ALL",))[0]
    return mcx_sessions(sym, mlo, mhi)


def setups_summary(irows, mrows, ilo, ihi, mlo, mhi) -> dict:
    """Every candidate unfiltered (several a day), per setup and market: context, not gated."""
    out = {}
    for r in irows:
        if ilo <= r["day"] <= ihi and r["und"] in ("SENSEX", "MIDCPNIFTY"):
            k = f"{r['setup']}:{r['und']}"
            out.setdefault(k, [0, 0.0])
            out[k][0] += 1
            out[k][1] += r["net"]
    for r in mrows:
        if mlo <= r["day"] <= mhi:
            k = f"{r['setup']}:{r['und']}"
            out.setdefault(k, [0, 0.0])
            out[k][0] += 1
            out[k][1] += r["net"]
    return {k: dict(trades=a, net=round(b)) for k, (a, b) in sorted(out.items())}


def design():
    I.load_all()
    irows = I.build_rows("design")
    mrows = load_mcx("design")
    idx_cal = I.calendar(C.DESIGN_END)
    mcx_cal = mcx_calendar(C.MCX_START, C.DESIGN_END)
    tr, extra = run_variants(irows, mrows, C.IDX_WF_MONTHS, C.MCX_WF_MONTHS, C.START, C.DESIGN_END, C.MCX_START,
                             C.DESIGN_END, idx_cal, mcx_cal)
    out = dict(rows=dict(idx=len(irows), mcx=len(mrows)), variants={})
    for vid in ALL_IDS:
        t = tr[vid]
        if vid in ("I4", "I5"):
            lo, hi = M.month_start(*C.IDX_WF_MONTHS[0]), M.month_end(*C.IDX_WF_MONTHS[-1])
        elif vid in ("M1", "M2"):
            lo, hi = M.month_start(*C.MCX_WF_MONTHS[0]), M.month_end(*C.MCX_WF_MONTHS[-1])
        elif vid in IDX_IDS:
            lo, hi = C.START, C.DESIGN_END
        else:
            lo, hi = C.MCX_START, C.DESIGN_END
        ses = sessions_of(vid, lo, hi, lo, hi, idx_cal)
        out["variants"][vid] = dict(span=f"{lo} - {hi}", design=stats(t, ses), **extra.get(vid, {}))
        print(vid, json.dumps(out["variants"][vid]["design"], default=str)[:220], flush=True)
    out["setups_unfiltered"] = setups_summary(irows, mrows, C.START, C.DESIGN_END, C.MCX_START, C.DESIGN_END)
    out["mcx_cleaning"] = {s: X.market(s).counts for s in C.MCX_SYMS}
    out["mcx_expiries"] = {s: [str(X.pydate(d)) for d in X.market(s).exp_days] for s in C.MCX_SYMS}
    C.RESULTS.mkdir(parents=True, exist_ok=True)
    (C.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))


def evaluate(irows, mrows, idx_months, mcx_months, ilo, ihi, mlo, mhi, idx_cal, mcx_cal, design_res, n_twins) -> dict:
    tr, extra = run_variants(irows, mrows, idx_months, mcx_months, ilo, ihi, mlo, mhi, idx_cal, mcx_cal)
    out = dict(variants={})
    for vid in ALL_IDS:
        t = tr[vid]
        tot = twins(vid, t, n_twins) if t else np.zeros(n_twins)
        p = p_value(sum(x["net"] for x in t), tot) if t else None
        ses = sessions_of(vid, ilo, ihi, mlo, mhi, idx_cal)
        out["variants"][vid] = dict(test=stats(t, ses), p=p, twin_mean=round(float(tot.mean())), **extra.get(vid, {}))
        print(vid, out["variants"][vid]["test"].get("trades"), out["variants"][vid]["test"].get("net"), p, flush=True)
    ps = [out["variants"][v]["p"] if out["variants"][v]["p"] is not None else 1.0 for v in ALL_IDS]
    for v, q in zip(ALL_IDS, bh(ps)):
        r = out["variants"][v]
        r["q"] = round(float(q), 4)
        dn = design_res["variants"][v]["design"]["net"] if design_res else None
        r["design_net"] = dn
        r["verdict"] = gate(r["test"], r["p"], r["q"], dn)
    out["setups_unfiltered"] = setups_summary(irows, mrows, ilo, ihi, mlo, mhi)
    return out, tr


def dryrun():
    """The final pipeline on the last 3 design months only (no final-test session is touched); writes nothing."""
    I.load_all()
    irows = I.build_rows("design")
    mrows = load_mcx("design")
    idx_cal = I.calendar(C.DESIGN_END)
    mcx_cal = mcx_calendar(C.MCX_START, C.DESIGN_END)
    months = [(2026, 1), (2026, 2), (2026, 3)]
    design_res = json.loads((C.RESULTS / "design.json").read_text())
    out, _ = evaluate(irows, mrows, months, months, date(2026, 1, 1), C.DESIGN_END, date(2026, 1, 1), C.DESIGN_END,
                      idx_cal, mcx_cal, design_res, 20)
    print({k: v["verdict"] for k, v in out["variants"].items()})


def final():
    if C.FINAL_FLAG.exists():
        raise SystemExit("R60: the final test was already read (results/FINAL_READ). One read only.")
    design_res = json.loads((C.RESULTS / "design.json").read_text())
    C.RESULTS.mkdir(parents=True, exist_ok=True)
    C.FINAL_FLAG.write_text(f"final test read on {date.today()}\n")
    C.open_final()
    S.open_final()
    I.load_all()
    irows = I.build_rows("design") + I.build_rows("final")
    mrows = load_mcx("design") + load_mcx("final")
    idx_cal = I.calendar(C.END)
    mcx_cal = mcx_calendar(C.MCX_START, C.END)
    out, trades = evaluate(irows, mrows, C.FINAL_MONTHS, C.FINAL_MONTHS, C.FINAL_START, C.END, C.FINAL_START, C.END,
                           idx_cal, mcx_cal, design_res, C.TWINS)
    (C.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    with open(C.OUT / "final_trades.pkl", "wb") as f:
        pickle.dump(trades, f)
    print({k: v["verdict"] for k, v in out["variants"].items()})


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    {"design": design, "dryrun": dryrun, "final": final}.get(cmd, lambda: print(__doc__))()
