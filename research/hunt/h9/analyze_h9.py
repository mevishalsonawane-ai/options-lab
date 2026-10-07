"""h9 analysis. Two stages, run in this order; the holdout stage only READS the selection the first stage froze.

    OBUY_CACHE=<scratch>/hunt/h9/cache python3 -I research/hunt/h9/analyze_h9.py pre    # data before 2025-10-01 only
    OBUY_CACHE=<scratch>/hunt/h9/cache python3 -I research/hunt/h9/analyze_h9.py hold   # one holdout test
"""
from __future__ import annotations

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, RES)
sys.path.insert(0, os.path.join(RES, "hunt", "h4"))
import obuy  # noqa: E402,F401
from obuy import config as C  # noqa: E402
from obuy import overfit as OF  # noqa: E402
from obuy.data import market  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import portfolio as H4  # noqa: E402  (stats_row, p_losing_month, p_dd, outlay, maxdd)

IN = os.path.join(C.CACHE, "h9")
START, HOLD, ALL3 = pd.Timestamp("2021-10-01"), pd.Timestamp("2025-10-01"), pd.Timestamp("2023-06-01")
SH = {"BANKNIFTY": "BN", "FINNIFTY": "FIN", "MIDCPNIFTY": "MIDCP"}
EXIST = ["BN15", "BN5", "FIN30", "FIN5", "MIDCP15", "MIDCP5"]
EXIST_SRC = {"BN15": "port", "BN5": "port", "FIN30": "port", "FIN5": "port", "MIDCP15": "ext", "MIDCP5": "ext"}
EXTRA = ["BN3", "BN10", "BN30", "BN60", "FIN3", "FIN10", "FIN15", "FIN60", "MIDCP3", "MIDCP10", "MIDCP30", "MIDCP60"]
EXES = ("gross", "app", "real")
TARGET = 5000.0


def tf_of(book):
    return int(re.findall(r"\d+", str(book))[0])


def load():
    T = {}
    for e in EXES:
        t = pd.read_parquet(os.path.join(IN, f"trades_{e}.parquet"))
        t["key"] = t.und.map(SH) + t.book.map(tf_of).astype(str)
        t = t[t.day >= START]
        T[e] = t
    P = pd.read_parquet(os.path.join(IN, "pool_real.parquet"))
    V = pd.read_parquet(os.path.join(IN, "vol_real.parquet"))
    return T, P, V


def book_trades(t, key):
    src = EXIST_SRC.get(key, "ext")
    return t[(t.key == key) & (t.src == src)]


def daily(t, cal):
    return t.groupby("day").net.sum().reindex(cal, fill_value=0.0)


def dup_flags(tr_extra, tr_exist):
    """same_min: an existing book on the same index entered the same minute & side; overlap: an existing same-side
    position on that index was open at the extra trade's entry minute."""
    ex = tr_exist[["und", "day", "side", "entry_min", "exit_min"]]
    keys = set(zip(ex.und, ex.day, ex.side, ex.entry_min))
    same = np.array([(u, d, s, m) in keys for u, d, s, m in zip(tr_extra.und, tr_extra.day, tr_extra.side, tr_extra.entry_min)])
    g = {k: v[["entry_min", "exit_min"]].values for k, v in ex.groupby(["und", "day", "side"])}
    ov = []
    for u, d, s, m in zip(tr_extra.und, tr_extra.day, tr_extra.side, tr_extra.entry_min):
        a = g.get((u, d, s))
        ov.append(bool(a is not None and ((a[:, 0] <= m) & (a[:, 1] >= m)).any()))
    return same, np.array(ov)


def rand_p(tr, P, src="ext"):
    pl = P[(P.src == src) & P.book.isin(tr.book.unique()) & P.und.isin(tr.und.unique())]
    return OF.random_baseline(tr, pl, B=4000)


def select(T, P, cut, min_trades=0):
    """Apply the pre-registered rule K1-K3 to real trades with day < cut. Returns (table, kept list)."""
    t = T["real"][T["real"].day < cut]
    exist_tr = pd.concat([book_trades(t, k) for k in EXIST])
    rows = []
    for k in EXTRA:
        tr = book_trades(t, k)
        r = dict(book=k, trades=len(tr), net=tr.net.sum())
        if len(tr) >= max(min_trades, 5):
            same, ov = dup_flags(tr, exist_tr[exist_tr.und == tr.und.iloc[0]])
            rb = rand_p(tr, P)
            r.update(p_rand=rb["p"], per_trade=rb["obs"], rand_per_trade=rb["null_mean"], dup_same_min=same.mean(),
                     overlap=ov.mean(), nondup_net=tr.net.values[~same].sum())
        else:
            r.update(p_rand=1.0, per_trade=np.nan, rand_per_trade=np.nan, dup_same_min=np.nan, overlap=np.nan,
                     nondup_net=np.nan)
        rows.append(r)
    S = pd.DataFrame(rows).set_index("book")
    S["q_bh"] = OF.bh(S.p_rand.values)
    S["K1"] = S.net > 0
    S["K2"] = S.q_bh < 0.10
    S["K3"] = S.nondup_net > 0
    S["keep"] = S.K1 & S.K2 & S.K3 & (S.trades >= max(min_trades, 5))
    return S, list(S.index[S.keep])


def risk_block(x, cal, label):
    r = H4.stats_row(x, cal)
    r["p_losing_month"] = H4.p_losing_month(x)
    r["label"] = label
    return r


def outlay_set(T, keys, cal, k):
    return sum(H4.outlay(book_trades(T["real"], kk), cal) for kk in keys) * k


def fill_check(T, V, keys, lots, period):
    t = pd.concat([book_trades(T["real"], kk) for kk in keys])
    t = t[period(t.day)]
    m = t.merge(V, on=["src", "cand"], how="left")
    share_e = lots / m.vol5_entry_lots.replace(0, np.nan)
    share_x = lots / m.vol5_exit_lots.replace(0, np.nan)
    by = m.assign(se=share_e).groupby("und").se.median().round(3).to_dict()
    return dict(lots=lots, med_vol5_entry_lots=float(m.vol5_entry_lots.median()),
                med_vol5_exit_lots=float(m.vol5_exit_lots.median()),
                share_entry_median=float(share_e.median()), share_entry_p90=float(share_e.quantile(0.9)),
                frac_entry_over_20pct=float((share_e.fillna(np.inf) > 0.2).mean()),
                frac_exit_over_20pct=float((share_x.fillna(np.inf) > 0.2).mean()),
                share_entry_median_by_und=by,
                zero_vol_entry=float((m.vol5_entry_lots.fillna(0) == 0).mean()))


def fmt(d):
    return {a: (round(b, 3) if isinstance(b, float) and abs(b) < 10 else round(b) if isinstance(b, float) else b) for a, b in d.items()}


def stage_pre():
    T, P, V = load()
    days = pd.to_datetime(pd.Index(market().index("NIFTY").days))
    cal = days[(days >= START) & (days < HOLD)]
    out = {}
    # sanity: re-implementation vs validated port on the existing BN/FIN books (pre-holdout, app execution)
    chk = {}
    for k in ["BN15", "BN5", "FIN30", "FIN5"]:
        a = T["app"]
        pa, ea = a[(a.key == k) & (a.src == "port") & (a.day < HOLD)], a[(a.key == k) & (a.src == "ext") & (a.day < HOLD)]
        chk[k] = dict(port_n=len(pa), ext_n=len(ea), port_net=round(pa.net.sum()), ext_net=round(ea.net.sum()))
    out["port_vs_ext_app"] = chk
    # selection on all pre-holdout data
    S, kept = select(T, P, HOLD)
    out["kept"] = kept
    # per-book detail incl. gross/app, per-year and correlations (pre-holdout)
    Dr = {e: pd.DataFrame({k: daily(book_trades(T[e][T[e].day < HOLD], k), cal) for k in EXIST + EXTRA}) for e in EXES}
    ex_sum = Dr["real"][EXIST].sum(axis=1)
    for k in EXTRA:
        und = k.rstrip("0123456789")
        same_idx = [e for e in EXIST if e.rstrip("0123456789") == und]
        x = Dr["real"][k]
        live = x.index >= book_trades(T["real"], k).day.min()
        S.loc[k, "gross_net"] = Dr["gross"][k].sum()
        S.loc[k, "app_net"] = Dr["app"][k].sum()
        S.loc[k, "corr_existing_all"] = np.corrcoef(x[live], ex_sum[live])[0, 1]
        S.loc[k, "corr_same_index"] = np.corrcoef(x[live], Dr["real"][same_idx].sum(axis=1)[live])[0, 1]
        for y in sorted(set(cal.year)):
            S.loc[k, f"y{y}"] = x[cal.year == y].sum()
    # existing books table, same columns
    E = []
    for k in EXIST:
        tr = book_trades(T["real"][T["real"].day < HOLD], k)
        rb = rand_p(tr, P, EXIST_SRC[k])
        r = dict(book=k, trades=len(tr), net=tr.net.sum(), gross_net=Dr["gross"][k].sum(), p_rand=rb["p"],
                 per_trade=rb["obs"], rand_per_trade=rb["null_mean"])
        for y in sorted(set(cal.year)):
            r[f"y{y}"] = Dr["real"][k][cal.year == y].sum()
        E.append(r)
    E = pd.DataFrame(E).set_index("book")
    # walk-forward selection by test year
    wf = {}
    parts = {e: [] for e in EXES}
    for y in (2023, 2024, 2025):
        _, kk = select(T, P, pd.Timestamp(f"{y}-01-01"), min_trades=30)
        wf[y] = kk
        m = cal.year == y
        for e in EXES:
            parts[e].append(Dr[e][kk].sum(axis=1)[m] if kk else pd.Series(0.0, index=cal[m]))
    out["wf_picks"] = {str(a): b for a, b in wf.items()}
    wf_extra = {e: pd.concat(parts[e]) for e in EXES}
    # portfolios (1 lot per book) from ALL3 (all three indices live) to Sep 2025
    m3 = cal >= ALL3
    A = {e: Dr[e][EXIST].sum(axis=1) for e in EXES}
    Bk = {e: Dr[e][EXIST + kept].sum(axis=1) for e in EXES}
    w23 = cal >= pd.Timestamp("2023-01-01")
    Bwf = {e: A[e][w23] + wf_extra[e].reindex(cal[w23], fill_value=0.0) for e in EXES}
    port = {}
    for nm, X, msk in (("a_existing", A, m3), ("b_existing+chosen(in-sample)", Bk, m3)):
        per = {e: float(X[e][msk].mean()) for e in EXES}
        k = TARGET / per["real"] if per["real"] > 0 else np.nan
        xs = X["real"][msk].values * k
        r = dict(per_day_1lot=per, k_lots=k, **risk_block(xs, cal[msk], nm))
        r["gross_per_day_at_k"] = per["gross"] * k
        r["trades_per_day"] = float(sum(len(book_trades(T["real"][(T["real"].day >= ALL3) & (T["real"].day < HOLD)], kk))
                                        for kk in (EXIST if nm.startswith("a") else EXIST + kept)) / msk.sum())
        O = outlay_set(T, EXIST if nm.startswith("a") else EXIST + kept, cal[msk], k)
        r["outlay_p95"], r["outlay_max"] = float(O[O > 0].quantile(0.95)), float(O.max())
        port[nm] = r
    # walk-forward comparison 2023..Sep 2025: existing vs existing + WF-picked extras
    for nm, X in (("a_existing_2023+", {e: A[e][w23] for e in EXES}), ("b_existing+WF_extras_2023+", Bwf)):
        r = dict(per_day_1lot={e: float(X[e].mean()) for e in EXES}, **risk_block(X["real"].values, cal[w23], nm))
        r["by_year"] = {int(y): round(float(X["real"][X["real"].index.year == y].sum())) for y in (2023, 2024, 2025)}
        port[nm] = r
    out["port_pre"] = port
    # random baseline for the chosen extras as a set (pre-holdout)
    if kept:
        tr = pd.concat([book_trades(T["real"][T["real"].day < HOLD], k) for k in kept])
        out["rand_set_pre"] = fmt(rand_p(tr, P))
    # SPA / RC over everything tried (18 books + 2 sets + WF set), 2022-01 .. Sep 2025, real
    X = Dr["real"].copy()
    X["SET_a"], X["SET_b"] = A["real"], Bk["real"]
    X["SET_bwf"] = Bwf["real"].reindex(cal, fill_value=0.0)
    Xp = X[cal >= pd.Timestamp("2022-01-01")]
    sp = OF.spa(Xp.values, B=2000)
    Xe = Dr["real"][EXTRA][cal >= pd.Timestamp("2022-01-01")]
    sp2 = OF.spa(Xe.values, B=2000)
    out["spa_all"] = dict(rc_p=sp["rc_p"], spa_p=sp["spa_p"], n=sp["n"], best=list(X.columns)[sp["best"]])
    out["spa_extras_only"] = dict(rc_p=sp2["rc_p"], spa_p=sp2["spa_p"], n=sp2["n"], best=EXTRA[sp2["best"]])
    # fill realism at the Rs 5k size, pre-holdout
    for nm in port:
        if "k_lots" in port[nm] and np.isfinite(port[nm]["k_lots"]):
            keys = EXIST if nm.startswith("a") else EXIST + kept
            port[nm]["fills"] = fill_check(T, V, keys, int(np.ceil(port[nm]["k_lots"])), lambda d: (d >= ALL3) & (d < HOLD))
    # daily correlation among all 18 books
    corr = Dr["real"][Dr["real"].index >= ALL3].corr()
    pd.set_option("display.width", 250); pd.set_option("display.max_columns", 40)
    S.to_csv(os.path.join(IN, "extras_pre.csv")); E.to_csv(os.path.join(IN, "existing_pre.csv"))
    corr.to_csv(os.path.join(IN, "corr_pre.csv"))
    with open(os.path.join(IN, "selection.json"), "w") as f:
        json.dump(dict(kept=kept, frozen_on="data < 2025-10-01", k_a=port["a_existing"]["k_lots"],
                       k_b=port["b_existing+chosen(in-sample)"]["k_lots"]), f, indent=1)
    with open(os.path.join(IN, "pre.json"), "w") as f:
        json.dump(out, f, indent=1, default=str)
    print(E.round(3).to_string()); print(S.round(3).to_string()); print(corr.round(2).to_string())
    print(json.dumps(out, indent=1, default=lambda o: round(o, 3) if isinstance(o, float) else str(o)))


def stage_hold():
    with open(os.path.join(IN, "selection.json")) as f:
        sel = json.load(f)
    kept, ka, kb = sel["kept"], sel["k_a"], sel["k_b"]
    T, P, V = load()
    days = pd.to_datetime(pd.Index(market().index("NIFTY").days))
    cal = days[days >= HOLD]
    D = {e: pd.DataFrame({k: daily(book_trades(T[e][T[e].day >= HOLD], k), cal) for k in EXIST + EXTRA}) for e in EXES}
    out = dict(kept=kept, last_day=str(cal.max().date()), days=len(cal))
    # per extra book on the holdout (reported for all 12; only `kept` were chosen)
    rows = []
    exist_tr = pd.concat([book_trades(T["real"][T["real"].day >= HOLD], k) for k in EXIST])
    for k in EXIST + EXTRA:
        tr = book_trades(T["real"][T["real"].day >= HOLD], k)
        r = dict(book=k, chosen=k in kept or k in EXIST, trades=len(tr), real=D["real"][k].sum(), app=D["app"][k].sum(),
                 gross=D["gross"][k].sum())
        if len(tr) >= 5:
            rb = rand_p(tr, P, EXIST_SRC.get(k, "ext"))
            r.update(p_rand=rb["p"], per_trade=rb["obs"], rand_per_trade=rb["null_mean"])
            if k in EXTRA:
                same, ov = dup_flags(tr, exist_tr[exist_tr.und == tr.und.iloc[0]])
                r.update(dup_same_min=same.mean(), overlap=ov.mean(), nondup_net=tr.net.values[~same].sum())
        rows.append(r)
    H = pd.DataFrame(rows).set_index("book")
    res = {}
    for nm, keys, k in (("a_existing", EXIST, ka), ("b_existing+chosen", EXIST + kept, kb)):
        r = {"per_day_1lot": {e: float(D[e][keys].sum(axis=1).mean()) for e in EXES}, "k_lots": k}
        for e in EXES:
            x = D[e][keys].sum(axis=1).values * k
            r[e] = H4.stats_row(x, cal)
        r["p_losing_month_real"] = H4.p_losing_month(D["real"][keys].sum(axis=1).values * k)
        O = sum(H4.outlay(book_trades(T["real"][T["real"].day >= HOLD], kk), cal) for kk in keys) * k
        r["outlay_p95"], r["outlay_max"] = float(O[O > 0].quantile(0.95)), float(O.max())
        r["fills"] = fill_check(T, V, keys, int(np.ceil(k)), lambda d: d >= HOLD)
        tr = pd.concat([book_trades(T["real"][T["real"].day >= HOLD], kk) for kk in keys])
        r["rand_set"] = fmt(rand_p(tr[tr.src == "ext"], P)) if (tr.src == "ext").any() else None
        r["trades_per_day"] = len(tr) / len(cal)
        res[nm] = r
    if kept:
        tr = pd.concat([book_trades(T["real"][T["real"].day >= HOLD], k) for k in kept])
        res["extras_only_rand"] = fmt(rand_p(tr, P))
        res["corr_extras_vs_existing"] = float(np.corrcoef(D["real"][kept].sum(axis=1), D["real"][EXIST].sum(axis=1))[0, 1])
    out["sets"] = res
    H.to_csv(os.path.join(IN, "books_hold.csv"))
    with open(os.path.join(IN, "hold.json"), "w") as f:
        json.dump(out, f, indent=1, default=str)
    pd.set_option("display.width", 250); pd.set_option("display.max_columns", 40)
    print(H.round(3).to_string())
    print(json.dumps(out, indent=1, default=lambda o: round(o, 3) if isinstance(o, float) else str(o)))


if __name__ == "__main__":
    {"pre": stage_pre, "hold": stage_hold}[sys.argv[1]]()
