"""h34 analysis, PRE-HOLDOUT only: gates G1-G5 (PREREG section 5), verdict vs original exits, random-entry baseline
tables, premium bands. Writes scratchpad/hunt/h34/{tables.md, analysis.json, choice.json, menu_rows.parquet}.

    python3 -I research/hunt/h34/analyse.py
"""
from __future__ import annotations

import glob
import json
import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import core as K  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import stats as sst  # noqa: E402
from obuy import overfit as OF  # noqa: E402
from obuy.data import market  # noqa: E402

YEARS = list(range(2020, 2026))
L = []


def rs(x):
    if x is None or not np.isfinite(x):
        return "-"
    return f"{'-' if x < 0 else '+'}{abs(x):,.0f}"


def P(*a):
    L.append(" ".join(str(x) for x in a))


def load():
    rows, Xs, vids = [], [], []
    cal = None
    for f in sorted(glob.glob(os.path.join(K.OUT, "res", "*.pkl"))):
        with open(f, "rb") as fh:
            r = pickle.load(fh)
        cal = r["days"]
        rows.append(r["rows"])
        Xs.append(r["X"])
        vids += r["vids"]
    R = pd.concat(rows, ignore_index=True)
    X = np.concatenate(Xs, axis=1)
    assert list(R.vid) == vids
    return R, X, cal


def index_days():
    mk = market()
    return {u: set(d for d in mk.index(u).days if d < K.HOLD) for u in K.UNDS5}


def strat_unds(g):
    return [u for u in K.UNDS5 if f"n_{u}" in g and g[f"n_{u}"].fillna(0).sum() > 0]


def fv(x):
    try:
        x = float(x)
    except (TypeError, ValueError):
        return 0.0
    return x if np.isfinite(x) else 0.0


def max_dd(x):
    c = np.cumsum(x)
    return float((c - np.maximum.accumulate(np.concatenate([[0], c]))[1:]).min()) if len(x) else 0.0


def walk_forward(g, X, cal, colmap, iday):
    """Anchored yearly WF over a strategy's menu rows g. Returns dict."""
    yrs_data = [y for y in YEARS if g.get(f"n_{y}", pd.Series(dtype=float)).fillna(0).sum() > 0]
    if len(yrs_data) < 3:
        return None
    first = yrs_data[0]
    tests = [y for y in YEARS if y >= first + 2]
    unds = strat_unds(g)
    picks, parts, rows = [], [], []
    for y in tests:
        prior = [c for c in YEARS if c < y]
        net = sum(g.get(f"net_{c}", 0).fillna(0) for c in prior)
        cnt = sum(g.get(f"n_{c}", 0).fillna(0) for c in prior)
        ok = cnt >= 20
        if not ok.any():
            continue
        pick = net[ok].idxmax()
        col = colmap[g.loc[pick, "vid"]]
        sel = np.array([d.year == y for d in cal])
        dd = np.array([d for d, s in zip(cal, sel) if s])
        x = X[sel, col].astype(float)
        nd = len(set().union(*[iday[u] for u in unds]) & set(dd)) if unds else len(dd)
        rows.append(dict(year=y, pick=g.loc[pick, "vid"], exit=g.loc[pick, "exit"], rule=g.loc[pick, "rule"],
                         sig=g.loc[pick, "sig"], test_net=fv(g.loc[pick].get(f"net_{y}", 0)),
                         test_gross=fv(g.loc[pick].get(f"gross_{y}", 0)), test_n=int(fv(g.loc[pick].get(f"n_{y}", 0))),
                         days=nd))
        parts.append(x)
    if not rows:
        return None
    x = np.concatenate(parts)
    tot = sum(r["test_net"] for r in rows)
    days = sum(r["days"] for r in rows)
    mo = None
    return dict(rows=rows, net=tot, gross=sum(r["test_gross"] for r in rows), trades=sum(r["test_n"] for r in rows),
                days=days, per_day=tot / max(days, 1), gross_day=sum(r["test_gross"] for r in rows) / max(days, 1),
                years_pos=sum(r["test_net"] > 0 for r in rows), years=len(rows), dd=max_dd(x),
                worst_day=float(x.min()) if len(x) else 0.0, mo=mo)


def fixed_years(r, first):
    tests = [y for y in YEARS if y >= first + 2]
    return sum(fv(r.get(f"net_{y}", 0)) for y in tests), sum(fv(r.get(f"net_{y}", 0)) > 0 for y in tests), len(tests)


def main():
    R, X, cal = load()
    R = R.reset_index(drop=True)
    colmap = {v: i for i, v in enumerate(R.vid)}
    iday = index_days()
    M = R[R.kind == "menu"].copy()
    Xm = X[:, M.index.values].astype(np.float64)
    nvar = len(M)
    P(f"# h34 tables (pre-holdout, {cal[0]} .. {cal[-1]}, {len(cal)} days)\n")
    P(f"Menu variants: {nvar} over {M.strategy.nunique()} strategies; reference (original-exit) rows: {len(R) - nvar}.\n")
    # ---- G2: t-test of daily NET over each strategy's own span
    first_day = {}
    for s, g in M.groupby("strategy"):
        cols = [colmap[v] for v in g.vid]
        nz = np.nonzero((X[:, cols] != 0).any(axis=1))[0]
        first_day[s] = nz[0] if len(nz) else 0
    tt = np.zeros(nvar)
    for k, (i, r) in enumerate(M.iterrows()):
        x = X[first_day[r.strategy]:, colmap[r.vid]].astype(float)
        sd = x.std(ddof=1) if len(x) > 2 else 0
        tt[k] = x.mean() / sd * np.sqrt(len(x)) if sd > 0 else 0.0
    M["t"] = tt
    M["p_t"] = 1 - sst.norm.cdf(tt)
    M["q_t"] = OF.bh(M.p_t.values)
    M["p_rand_f"] = M.p_rand.fillna(1.0)
    M.loc[M.NET <= 0, "p_rand_f"] = 1.0
    M["q_rand"] = OF.bh(M.p_rand_f.values)
    M["G1"] = (M.NET > 0) & (M.trades >= 30)
    M["G2"] = M.q_t <= 0.10
    M["G3"] = M.q_rand <= 0.10
    spa = OF.spa(Xm, B=1000)
    G4 = spa["spa_p"] <= 0.05
    P(f"SPA over all {nvar} menu variants (daily NET vs not trading): SPA p = {spa['spa_p']:.3f}, RC p = {spa['rc_p']:.3f}, "
      f"best t = {spa['t_best']:.2f} ({M.iloc[spa['best']].vid}). G4 {'PASS' if G4 else 'FAIL'}.\n")
    P(f"G1 (NET > 0, >= 30 trades): {int(M.G1.sum())} variants ({M.G1.mean():.1%}). Max t = {M.t.max():.2f}; "
      f"BH q <= 0.10 on t: {int(M.G2.sum())}; matched-random BH q <= 0.10: {int(M.G3.sum())}; G1&G2&G3: {int((M.G1 & M.G2 & M.G3).sum())}.\n")
    # gross view too
    P(f"GROSS (mid, no costs) > 0: {(M.GROSS > 0).mean():.1%} of menu variants; median GROSS/trade "
      f"{(M.GROSS / M.trades.replace(0, np.nan)).median():+.0f}, median NET/trade {(M.NET / M.trades.replace(0, np.nan)).median():+.0f} Rs.\n")
    # ---- per strategy
    out = []
    for s, g in M.groupby("strategy", sort=False):
        g = g.copy()
        wf = walk_forward(g, X, cal, colmap, iday)
        ref = R[(R.strategy == s) & (R.kind != "menu")]
        yrs_data = [y for y in YEARS if g.get(f"n_{y}", pd.Series(dtype=float)).fillna(0).sum() > 0]
        first = yrs_data[0] if yrs_data else 2020
        best = g.NET.idxmax()
        row = dict(strategy=s, n_var=len(g), trades_med=float(g.trades.median()), menu_med=float(g.NET.median()),
                   menu_pos=float((g.NET > 0).mean()), best_vid=g.loc[best, "vid"], best_exit=g.loc[best, "exit"],
                   best_rule=g.loc[best, "rule"], best_sig=g.loc[best, "sig"], best_net=float(g.loc[best, "NET"]),
                   best_gross=float(g.loc[best, "GROSS"]), best_trades=int(g.loc[best, "trades"]),
                   best_p=float(g.loc[best, "p_rand"]) if np.isfinite(g.loc[best, "p_rand"]) else np.nan,
                   best_qr=float(g.loc[best, "q_rand"]), best_qt=float(g.loc[best, "q_t"]), best_t=float(g.loc[best, "t"]),
                   best_rand_obs=g.loc[best, "rand_obs"], best_rand_null=g.loc[best, "rand_null"],
                   any_pass=bool((g.G1 & g.G2 & g.G3).any()), best_pass=bool(g.loc[best, ["G1", "G2", "G3"]].all()),
                   menu_gross_pos=float((g.GROSS > 0).mean()), unds=",".join(strat_unds(g)))
        if wf:
            row.update(wf_net=wf["net"], wf_gross=wf["gross"], wf_trades=wf["trades"], wf_days=wf["days"],
                       wf_day=wf["per_day"], wf_gross_day=wf["gross_day"], wf_yp=wf["years_pos"], wf_y=wf["years"],
                       wf_dd=wf["dd"], wf_worst_day=wf["worst_day"],
                       wf_years=" / ".join(f"{r['year']}:{rs(r['test_net'])}" for r in wf["rows"]),
                       wf_picks="; ".join(f"{r['year']}:{r['rule']} {r['exit']} {r['sig']}" for r in wf["rows"]))
            row["G5"] = wf["net"] > 0 and wf["years_pos"] > wf["years"] / 2
        else:
            row["G5"] = False
        for _, rr in ref.iterrows():
            k = rr.kind
            tot, yp, ny = fixed_years(rr, first)
            row[f"{k}_net"] = float(rr.NET)
            row[f"{k}_gross"] = float(rr.GROSS)
            row[f"{k}_trades"] = int(rr.trades)
            row[f"{k}_wfnet"] = tot
            row[f"{k}_yp"] = f"{yp}/{ny}"
            row[f"{k}_exit"] = rr.exit
        row["PASS"] = bool(G4 and row["G5"] and row["best_pass"])
        out.append(row)
    F = pd.DataFrame(out)
    M.to_parquet(os.path.join(K.OUT, "menu_rows.parquet"))
    F.to_csv(os.path.join(K.OUT, "strategies.csv"), index=False)
    # ---- tables
    P("## Per strategy: point menu vs original exits (pre-holdout, NET = dated charges + real half-spread, 1 lot)\n")
    P("| strategy | idx | menu: share NET>0 / median NET | best menu variant (IS) | best NET (gross) | best p rand / BH q | WF NET (gross), yrs+ | WF Rs/day | orig default NET (WF yrs) | orig IS-best NET (WF yrs) | changed? |")
    P("|---|---|---|---|---|---|---|---|---|---|---|")
    F = F.sort_values("wf_net", ascending=False)
    for _, r in F.iterrows():
        od = f"{rs(r.get('orig_def_net', np.nan))} ({rs(r.get('orig_def_wfnet', np.nan))}, {r.get('orig_def_yp', '-')})" if pd.notna(r.get("orig_def_net", np.nan)) else "= IS-best"
        oi = f"{rs(r.get('orig_isb_net', np.nan))} ({rs(r.get('orig_isb_wfnet', np.nan))}, {r.get('orig_isb_yp', '-')})"
        orig_wf = r.get("orig_def_wfnet", r.get("orig_isb_wfnet", np.nan))
        if pd.isna(orig_wf):
            orig_wf = r.get("orig_isb_wfnet", np.nan)
        wfn = r.get("wf_net", np.nan)
        ch = "PASS (new)" if r.PASS else ("no (both lose)" if (pd.notna(wfn) and wfn <= 0 and pd.notna(orig_wf) and orig_wf <= 0)
                                           else ("points worse" if pd.notna(wfn) and pd.notna(orig_wf) and wfn <= 0 < orig_wf
                                                 else ("points WF>0, fails tests" if pd.notna(wfn) and wfn > 0 else "no")))
        P(f"| {r.strategy} | {r.unds} | {r.menu_pos:.0%} / {rs(r.menu_med)} | {r.best_rule} {r.best_exit} ({r.best_sig}) | "
          f"{rs(r.best_net)} ({rs(r.best_gross)}) | {r.best_p:.3f} / {r.best_qr:.2f} | {rs(wfn)} ({rs(r.get('wf_gross', np.nan))}), "
          f"{int(fv(r.get('wf_yp', 0)))}/{int(fv(r.get('wf_y', 0)))} | {rs(r.get('wf_day', np.nan))} | {od} | {oi} | {ch} |")
    P("")
    P("WF picks per strategy: " + " || ".join(f"{r.strategy}: {r.get('wf_picks', '-')}" for _, r in F.iterrows()))
    P("")
    # ---- totals
    P(f"Strategies with WF NET > 0 (point menu): {int((F.wf_net > 0).sum())} of {len(F)}; G5 (WF > 0 and > half years +): "
      f"{int(F.G5.sum())}; any variant G1-G3: {int(F.any_pass.sum())}; PASS: {int(F.PASS.sum())}.")
    od = F.get("orig_def_wfnet", F.get("orig_isb_wfnet"))
    P(f"Sum of WF NET, point menu: {rs(F.wf_net.sum())}; original default exits over the same test years: "
      f"{rs(F.orig_def_wfnet.fillna(F.orig_isb_wfnet).sum())}.\n")
    # ---- random baseline
    A = pd.concat([pd.read_parquet(f) for f in sorted(glob.glob(os.path.join(K.OUT, "rand_*_agg.parquet"))) if "_hold" not in f], ignore_index=True)
    A["lab"] = [K.MLAB[i] for i in A.exit]
    P("## Random entries with the point menu (pre-holdout, per trade, 1 lot)\n")
    g = A.groupby(["und", "rule"]).apply(lambda d: pd.Series(dict(n=d[d.exit == 0].n.sum())), include_groups=False)
    P("| index | rule | random trades | best exit (NET/trade) | worst exit | median exit NET/trade | best GROSS/trade exit | share of exits NET>0 |")
    P("|---|---|---|---|---|---|---|---|")
    for (u, ru), d in A.groupby(["und", "rule"]):
        e = d.groupby("exit")[["n", "net", "gross"]].sum()
        e["nt"] = e.net / e.n
        e["gtr"] = e.gross / e.n
        P(f"| {u} | {ru} | {int(e.n.iloc[0]):,} | {K.MLAB[e.nt.idxmax()]} {e.nt.max():+.0f} | {K.MLAB[e.nt.idxmin()]} {e.nt.min():+.0f} | "
          f"{e.nt.median():+.0f} | {K.MLAB[e.gtr.idxmax()]} {e.gtr.max():+.0f} | {(e.nt > 0).mean():.0%} |")
    P("")
    P("### Random entries by hour bucket (all indices, ATM + 1-ITM, mean over the 128 exits, NET / GROSS per trade)\n")
    P("| bucket | trades per exit | NET/trade | GROSS/trade | best exit NET/trade |")
    P("|---|---|---|---|---|")
    bl = ["09:15", "10:15", "11:15", "12:15", "13:15", "14:15"]
    for b, d in A.groupby("bucket"):
        e = d.groupby("exit")[["n", "net", "gross"]].sum()
        P(f"| {bl[b]} | {int(e.n.iloc[0]):,} | {(e.net / e.n).mean():+.0f} | {(e.gross / e.n).mean():+.0f} | {K.MLAB[(e.net / e.n).idxmax()]} {(e.net / e.n).max():+.0f} |")
    P("")
    P("### Premium bands (random entries, all indices; a 20-point move as % of premium)\n")
    P("| band | trades per exit | median-ish premium | 20 pts = % | NET/trade T20/S15/t30 | GROSS/trade T20/S15/t30 | NET/trade best exit | share of exits NET>0 |")
    P("|---|---|---|---|---|---|---|---|")
    x20 = K.MLAB.index("T20/S15/t30")
    for b, d in A.groupby("band"):
        e = d.groupby("exit")[["n", "net", "gross", "prem"]].sum()
        pm = e.prem.iloc[0] / e.n.iloc[0]
        P(f"| {K.BLAB[b]} | {int(e.n.iloc[0]):,} | {pm:.0f} | {20 / pm:.0%} | {e.net[x20] / e.n[x20]:+.0f} | {e.gross[x20] / e.n[x20]:+.0f} | "
          f"{K.MLAB[(e.net / e.n).idxmax()]} {(e.net / e.n).max():+.0f} | {((e.net / e.n) > 0).mean():.0%} |")
    P("")
    P("### Rupees per premium point per lot, and typical premiums (random ATM / 1-ITM entries, pre-holdout)\n")
    P("| index | lot now | Rs per point per lot | Rs for +20 pts | mean ATM premium | mean 1-ITM premium | 20 pts as % (ATM) | half-spread |")
    P("|---|---|---|---|---|---|---|---|")
    for u in K.UNDS5:
        d = A[(A.und == u) & (A.exit == 0)]
        pa = d[d.rule == "ATM"].prem.sum() / max(d[d.rule == "ATM"].n.sum(), 1)
        pi = d[d.rule == "ITM1"].prem.sum() / max(d[d.rule == "ITM1"].n.sum(), 1)
        P(f"| {u} | {K.LOT_NOW[u]} | {K.LOT_NOW[u]} | {20 * K.LOT_NOW[u]:,} | {pa:.0f} | {pi:.0f} | {20 / pa:.0%} | {K.HS[u]:.2%} |")
    P("")
    # menu-wide bands from strategies
    P("### Premium bands across all strategies' menu variants (pre-holdout; sums over every menu variant)\n")
    P("| band | trades | GROSS/trade | NET/trade |")
    P("|---|---|---|---|")
    for b in range(4):
        n = M[f"n_b{b}"].sum()
        P(f"| {K.BLAB[b]} | {int(n):,} | {M[f'gross_b{b}'].sum() / max(n, 1):+.0f} | {M[f'net_b{b}'].sum() / max(n, 1):+.0f} |")
    P("")
    # per exit dimension (all strategies)
    P("### Which part of the menu does least damage (all strategies' menu variants, NET per trade)\n")
    for dim, f in (("target", lambda s: s.split("/")[0]), ("stop", lambda s: s.split("/")[1]),
                   ("time", lambda s: s.split("/")[2]), ("lock", lambda s: "lock" if s.endswith("/L") else "no lock")):
        M[dim] = M.exit.map(f)
        t = M.groupby(dim)[["NET", "GROSS", "trades"]].sum()
        P(f"- {dim}: " + "; ".join(f"{k} NET {v.NET / v.trades:+.0f} / GROSS {v.GROSS / v.trades:+.0f}" for k, v in t.iterrows()))
    P("")
    choice = dict(pass_=[r.strategy for _, r in F.iterrows() if r.PASS], spa=spa,
                  candidates=[dict(strategy=r.strategy, vid=r.best_vid) for _, r in F.iterrows() if r.PASS])
    with open(os.path.join(K.OUT, "choice.json"), "w") as f:
        json.dump(choice, f, indent=1, default=float)
    with open(os.path.join(K.OUT, "tables.md"), "w") as f:
        f.write("\n".join(L) + "\n")
    print("\n".join(L))


if __name__ == "__main__":
    main()
