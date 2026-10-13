#!/usr/bin/env python3
"""OF1 analysis driver (PREREG.md). Reads <cache>/of1/raw (fetch.py) and <cache>/of1/work (build.py).

    python3 -I research/hunt/of1/analyze.py design     # everything on the design period (2 Jan 2012 - 12 Oct 2025)
    python3 -I research/hunt/of1/analyze.py holdout    # ONE read of 13 Oct 2025 - 13 Oct 2026 for the design survivors
    python3 -I research/hunt/of1/analyze.py summary    # descriptive who-profited tables over 2012-2026 (after holdout)

Outputs go to research/hunt/of1/results/ (small tables only: no raw data). `holdout` refuses to run twice: it writes
results/HOLDOUT_READ.json first.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import buildup as BU  # noqa: E402
import load as LD  # noqa: E402
import pnl as P  # noqa: E402
import rules as RU  # noqa: E402
import signals as SG  # noqa: E402
import stats as ST  # noqa: E402

WORK = Path(os.environ.get("OF1_WORK", LD.CACHE / "of1" / "work")).expanduser()
RES = Path(os.environ.get("OF1_RESULTS", HERE / "results"))
CUT = pd.Timestamp("2025-10-13")            # first holdout day
END = pd.Timestamp("2026-10-13")
Q = 0.10
UNDS = ("NIFTY", "BANKNIFTY")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}
CR = 1e7                                     # rupees per crore


def md(df: pd.DataFrame, floatfmt: str = "{:,.2f}", index: bool = True) -> str:
    """A small markdown table without extra dependencies."""
    d = df.reset_index() if index else df
    cols = [str(c) for c in d.columns]

    def f(v):
        if isinstance(v, (int, np.integer)) and 1990 <= v <= 2100:          # a year
            return str(v)
        if isinstance(v, (float, np.floating)):
            return "" if np.isnan(v) else floatfmt.format(v)
        if isinstance(v, (int, np.integer)):
            return f"{v:,}"
        return str(v)

    out = ["| " + " | ".join(cols) + " |", "|" + "|".join("---" for _ in cols) + "|"]
    out += ["| " + " | ".join(f(v) for v in row) + " |" for row in d.itertuples(index=False)]
    return "\n".join(out)


# ---- loading ---------------------------------------------------------------------------------------------------------
def load_all() -> dict:
    part, prob = LD.participants("oi")
    vol, vprob = LD.participants("vol")
    wide = LD.wide(part)
    bad = LD.bad_days(wide, prob)
    wide = wide.drop(index=[d for d in bad if d in wide.index])
    ix = LD.index_closes()
    fpi = LD.fpi()
    pools = pd.read_parquet(WORK / "pools.parquet")
    pools.index = pd.to_datetime(pools.date)
    pools["prev"] = pd.to_datetime(pools.prev)
    chain = pd.read_parquet(WORK / "chain_near.parquet")
    finals = pd.read_parquet(WORK / "finals.parquet")
    futexp = json.loads((WORK / "fut_expiries.json").read_text())
    return dict(part=part, prob=prob, bad=bad, vol=vol, vprob=vprob, wide=wide, ix=ix, fpi=fpi, pools=pools, chain=chain,
                finals=finals, futexp=futexp)


def coverage(D: dict) -> str:
    w, ix, po = D["wide"], D["ix"], D["pools"]
    days_bhav = len(LD.bhav_days())
    raw = LD.RAW
    miss = {k: len(list((raw / sub).glob("*.missing"))) for k, sub in
            [("oi", "participants/oi"), ("vol", "participants/vol"), ("idx", "indices"), ("bhav", "bhav"), ("fii", "fii_stats")]}
    sess = ix.index[(ix.index >= w.index.min()) & (ix.index <= w.index.max())]
    lines = [
        f"- participant OI files: {len(w):,} days, {w.index.min().date()} .. {w.index.max().date()}; volume files: "
        f"{D['vol'].date.nunique():,}; parse/consistency problems (beyond NSE's +-2-contract rounding): OI {len(D['prob'])}, vol {len(D['vprob'])}",
        f"- participant days left out (damaged; positions carried from the day before for the MTM only): "
        + "; ".join(f"{d.date()} ({why[:60]})" for d, why in sorted(D["bad"].items())),
        f"- index sessions (NSE all-index file, Dhan fallback) in that span: {len(sess):,}; sessions with no participant "
        f"file: {len(set(sess) - set(w.index)):,}; of them from the Dhan fallback: {(ix.loc[sess, 'src'] == 'dhan').sum():,}",
        f"- index-only bhavcopies: {days_bhav:,}; day-to-day MTM steps: {len(po):,}; OI units with no next-day price: "
        f"{po.lost_oi.sum():,.0f} on {(po.lost_oi > 0).sum()} steps",
        f"- weekdays with no file (holidays or not published), by kind: {miss}",
        f"- FPI reporting days (NSDL): {len(D['fpi']):,}" + (f", {D['fpi'].index.min().date()} .. {D['fpi'].index.max().date()}" if len(D['fpi']) else ""),
        "- DII cash flows: NOT obtained (only on NSE's www API, which returned HTTP 403 to this machine)",
    ]
    return "\n".join(lines)


# ---- P&L ---------------------------------------------------------------------------------------------------------------
def pnl_tables(D: dict, lo: pd.Timestamp, hi: pd.Timestamp) -> tuple[pd.DataFrame, dict[str, pd.DataFrame]]:
    po = D["pools"][(D["pools"].index >= lo) & (D["pools"].index < hi)]
    # a session with no usable participant file carries the previous day's positions (at most 3 sessions; MTM only)
    alld = D["wide"].index.union(po.index).union(pd.DatetimeIndex(po.prev))
    wff = D["wide"].reindex(alld).ffill(limit=3)
    pc = P.per_contract(po, wff)
    g = P.group_pnl(wff, pc, po.prev)
    reg = SG.regimes(D["ix"], [e for u, e in D["futexp"] if u == "NIFTY"])
    r = reg.reindex(pd.to_datetime(po.prev.values))                    # the regime known at t labels day t+1's P&L
    r.index = g.index
    tabs = {}
    for b, lab in (("fut", "index futures"), ("fut2", "index futures + day's change"), ("opt_all", "index options (a) all strikes"),
                   ("opt_near", "index options (b) near"), ("opt_far", "index options (c) far")):
        cols = [f"{x}.{b}" for x in P.GROUPS]
        t = g[cols].copy()
        t.columns = list(P.GROUPS)
        tabs[f"{b}|year"] = (t.groupby(t.index.year).sum() / CR).rename_axis("year")
        for k in ("trend", "vix", "expiry_week"):
            lab_k = r[k].astype(str)
            tt = (t.groupby(lab_k.values).sum() / CR).rename_axis(k)
            n = t.groupby(lab_k.values).size()
            tt["days"] = n
            tabs[f"{b}|{k}"] = tt
        tabs[f"{b}|total"] = (t.sum() / CR).to_frame("Rs cr").T
    return g, tabs


# ---- WHY ---------------------------------------------------------------------------------------------------------------
def behaviour(D: dict, lo, hi) -> pd.DataFrame:
    w = D["wide"][(D["wide"].index >= lo) & (D["wide"].index < hi)]
    ix = D["ix"]
    ret = (ix["NIFTY_close"] / ix["NIFTY_close"].shift(1) - 1) * 100
    rows = []
    for g in P.GROUPS:
        dn = w[f"{g}.fut_net"].diff()
        rt = ret.reindex(w.index)
        rows.append({
            "group": g,
            "corr(d fut net, NIFTY ret same day)": ST.spearman(dn, rt),
            "corr(d fut net, NIFTY ret day before)": ST.spearman(dn, rt.shift(1)),
            "median fut long share": float(w[f"{g}.long_share"].median()),
            "median call net (k contracts)": float(w[f"{g}.call_net"].median() / 1e3),
            "median put net (k contracts)": float(w[f"{g}.put_net"].median() / 1e3),
            "days net option WRITER (calls+puts net < 0) %": float(((w[f"{g}.call_net"] + w[f"{g}.put_net"]) < 0).mean() * 100),
            "corr(d stock fut long, d index put long)": ST.spearman(w[f"{g}.fut_stk_long"].diff(), w[f"{g}.idx_put_long"].diff()),
        })
    return pd.DataFrame(rows).set_index("group")


def volume_share(D: dict, lo, hi) -> pd.DataFrame:
    v = D["vol"]
    v = v[(pd.to_datetime(v.date) >= lo) & (pd.to_datetime(v.date) < hi) & (v.group != "TOTAL")].copy()
    v["y"] = pd.to_datetime(v.date).dt.year
    v["idx_opt"] = v.idx_call_long + v.idx_put_long + v.idx_call_short + v.idx_put_short
    v["idx_fut"] = v.fut_idx_long + v.fut_idx_short
    out = {}
    for c in ("idx_opt", "idx_fut"):
        t = v.pivot_table(index="y", columns="group", values=c, aggfunc="sum")
        out[c] = (t.div(t.sum(axis=1), axis=0) * 100)
    r = pd.concat(out, axis=1)
    r.columns = [f"{a} {b} %" for a, b in r.columns]
    r.index.name = "year"
    return r


def level_table(D: dict, p: pd.DataFrame, rows: pd.Series) -> pd.DataFrame:
    """Descriptive (PREREG §2): each group's index-futures long share as a point-in-time percentile of its own last 250
    days (rows <= t), in quintiles, vs the NEXT 1/5/20-day close-to-close move (%), plus the HAC t of the slope."""
    w = D["wide"].reindex(p.index)
    out = []
    for g in P.GROUPS:
        x = w[f"{g}.long_share"]
        pct = x.rolling(250, min_periods=120).apply(lambda a: (a[:-1] < a[-1]).mean() if len(a) > 1 else np.nan, raw=True)
        qn = pd.cut(pct, [-0.01, 0.2, 0.4, 0.6, 0.8, 1.0], labels=["Q1 low", "Q2", "Q3", "Q4", "Q5 high"])
        for u in UNDS:
            for h in (1, 5, 20):
                y = p[f"{u}.cc{h}"]
                ok = rows & (p.index + pd.offsets.BDay(h) < CUT)
                m = y[ok].groupby(qn[ok], observed=False).mean()
                r = ST.ols_hac(y[ok], pct[ok], h)
                out.append({"group": g, "index": u, "h": h, **{str(k): float(v) for k, v in m.items()},
                            "slope t (HAC)": r["t"], "n": r["n"]})
    return pd.DataFrame(out)


# ---- predictive tests --------------------------------------------------------------------------------------------------
def panel(D: dict) -> pd.DataFrame:
    s = SG.build(D["wide"], D["fpi"])
    parts = [s]
    for u in UNDS:
        t = SG.targets(D["ix"], u).reindex(s.index)
        t.columns = [f"{u}.{c}" for c in t.columns]
        parts.append(t)
    p = pd.concat(parts, axis=1)
    # the date the target ENDS (t+1 for oc1): needed to keep design targets inside the design period
    nxt = pd.Series(D["ix"].index[1:].append(pd.DatetimeIndex([pd.NaT])), index=D["ix"].index)
    p["t1"] = nxt.reindex(p.index)
    return p


def tests(p: pd.DataFrame, target: str, lag: int, rows: pd.Series) -> pd.DataFrame:
    out = []
    for sname in SG.SIGNALS:
        for u in UNDS:
            y = p.loc[rows, f"{u}.{target}"]
            x = p.loc[rows, sname]
            r = ST.ols_hac(y, x, lag)
            out.append({"signal": sname, "index": u, "target": target, "b": r["b"], "t": r["t"], "p": r["p"], "n": r["n"],
                        "spearman": ST.spearman(y, x)})
    t = pd.DataFrame(out)
    t["q"] = ST.bh(t.p)
    return t


def walk_forward(p: pd.DataFrame, sname: str, u: str, years=range(2014, 2026)) -> pd.DataFrame:
    rows = []
    for Y in years:
        tr = (p.index.year < Y) & (p.t1 < CUT)
        te = (p.index.year == Y) & (p.t1 < CUT)
        b = ST.ols_hac(p.loc[tr, f"{u}.oc1"], p.loc[tr, sname], 1)["b"]
        x, y = p.loc[te, sname], p.loc[te, f"{u}.oc1"]
        m = (x.abs() >= 1) & y.notna()
        if np.isnan(b) or m.sum() == 0:
            rows.append({"year": Y, "sign": np.nan, "days": int(m.sum()), "hit%": np.nan, "mean signed ret %": np.nan})
            continue
        sr = np.sign(b) * np.sign(x[m]) * y[m]
        rows.append({"year": Y, "sign": float(np.sign(b)), "days": int(m.sum()), "hit%": float((sr > 0).mean() * 100),
                     "mean signed ret %": float(sr.mean())})
    return pd.DataFrame(rows).set_index("year")


# ---- walls and magnets -------------------------------------------------------------------------------------------------
def wall_rows(D: dict) -> pd.DataFrame:
    ch, fi, ix = D["chain"], D["finals"], D["ix"]
    ch = ch.copy()
    ch["date"] = pd.to_datetime(ch.date)
    ch["eff_expiry"] = pd.to_datetime(ch.eff_expiry)
    fin = fi.groupby(["und", "eff_expiry"]).und_final.first().reset_index()
    fin["eff_expiry"] = pd.to_datetime(fin.eff_expiry)
    sess = ix.index
    rows = []
    for r in fin.itertuples(index=False):
        if np.isnan(r.und_final):
            continue
        i = sess.searchsorted(r.eff_expiry)
        if i >= len(sess) or sess[i] != r.eff_expiry:
            continue
        for k in (1, 3):
            if i - k < 0:
                continue
            dk = sess[i - k]
            c = ch[(ch.und == r.und) & (ch.date == dk) & (ch.eff_expiry == r.eff_expiry)]
            spot = float(ix.loc[dk, f"{r.und}_close"])
            if c.empty or np.isnan(spot):
                continue
            w = BU.walls(c, spot)
            rows.append({"und": r.und, "expiry": r.eff_expiry, "k": k, "day": dk, "spot": spot, "final": float(r.und_final), **w})
    return pd.DataFrame(rows)


def wall_tests(W: pd.DataFrame, seed: int = 11) -> pd.DataFrame:
    out = []
    rng = np.random.default_rng(seed)
    for u in UNDS:
        for k in (1, 3):
            w = W[(W.und == u) & (W.k == k)].reset_index(drop=True)
            if len(w) < 10:
                continue
            move = (w.final / w.spot - 1) * 100
            for side in ("call", "put"):
                wall = w[f"{side}_wall"]
                dist = ((wall / w.spot - 1) * 100) if side == "call" else ((1 - wall / w.spot) * 100)
                ok = wall.notna()
                br = (w.final > wall) if side == "call" else (w.final < wall)
                pbase = BU.breach_baseline(dist[ok].to_numpy(), move[ok].to_numpy(), side)
                obs = int(br[ok].sum())
                out.append({"test": f"{side} wall", "index": u, "k": k, "n": int(ok.sum()), "breaches": obs,
                            "expected (twins)": float(np.nansum(pbase)), "p": BU.poisson_binomial_p(obs, pbase),
                            "median distance %": float(dist[ok].median())})
            ok = w.kstar.notna()
            mir = np.array([BU.mirror(a, s, STEP[u]) for a, s in zip(w.kstar[ok], w.spot[ok])])
            ks = w.kstar[ok].to_numpy()
            fin = w.final[ok].to_numpy()
            keep = mir != ks
            diff = np.abs(fin - ks) - np.abs(fin - mir)
            # random twins at the same |distance| +-1 step, either side
            dist = np.abs(ks - w.spot[ok].to_numpy())
            closer = []
            for j in np.flatnonzero(keep):
                dd = dist[j] + rng.integers(-1, 2, 1000) * STEP[u]
                kr = w.spot[ok].to_numpy()[j] + rng.choice([-1, 1], 1000) * np.maximum(dd, STEP[u])
                kr = np.round(kr / STEP[u]) * STEP[u]
                closer.append(np.mean(np.abs(fin[j] - ks[j]) < np.abs(fin[j] - kr)))
            out.append({"test": "magnet (max OI strike)", "index": u, "k": k, "n": int(keep.sum()), "breaches": np.nan,
                        "expected (twins)": np.nan,
                        "K* closer than random twin, share": float(np.mean(closer)) if closer else np.nan,
                        "p": BU.signflip_p(diff[keep]), "median distance %": float(np.median(dist / w.spot[ok].to_numpy()) * 100),
                        "dropped (K* at spot)": int((~keep).sum()),
                        "mean |final-K*| - |final-mirror| (pts)": float(np.mean(diff[keep])) if keep.any() else np.nan})
    t = pd.DataFrame(out)
    t["q"] = ST.bh(t.p)
    return t


# ---- build-up -----------------------------------------------------------------------------------------------------------
def buildup_tables(D: dict, lo, hi) -> dict[str, pd.DataFrame]:
    ch = D["chain"].copy()
    ch["date"] = pd.to_datetime(ch.date)
    ch = ch[(ch.date >= lo) & (ch.date < hi)]
    # the price a day's new positions traded near: the settle (= close) on ordinary days; on the expiry day the settle
    # column holds the intrinsic value (build.expiry_settle), so use the day's traded close there
    on_exp = pd.to_datetime(ch.eff_expiry) == ch.date
    ch["px"] = np.where(on_exp, ch.close, ch.settle)
    ch["label"] = BU.classify(ch.px - ch.prev_settle, ch.chg_oi_qty)
    adds = ch[(ch.chg_oi_qty > 0) & (ch.px > 0) & (ch.contracts.fillna(0) > 0)].rename(columns={"chg_oi_qty": "add_qty"})
    adds = adds.drop(columns=["settle"]).rename(columns={"px": "settle"})
    fi = D["finals"][["und", "eff_expiry", "strike", "kind", "final"]]
    j = adds.merge(fi, on=["und", "eff_expiry", "strike", "kind"], how="inner")
    j["long_pnl_unit"] = j.final - j.settle
    j["long_rs"] = j.long_pnl_unit * j.add_qty
    j["mny"] = BU.moneyness_bucket(j.kind, j.strike, j.fut_settle)
    dte = (pd.to_datetime(j.eff_expiry) - j.date).dt.days
    j["dte"] = pd.cut(dte, [-1, 0, 2, 7, 30, 10_000], labels=["0", "1-2", "3-7", "8-30", ">30"]).astype(str)
    j["y"] = j.date.dt.year

    def agg(g):
        return pd.Series({"adds (lakh units)": g.add_qty.sum() / 1e5, "long won %": (g.long_pnl_unit > 0).mean() * 100,
                          "long P&L Rs cr": g.long_rs.sum() / CR, "writer P&L Rs cr": -g.long_rs.sum() / CR,
                          "long P&L % of premium": g.long_rs.sum() / (g.settle * g.add_qty).sum() * 100, "rows": len(g)})
    out = {"label": j.groupby(["und", "label"]).apply(agg, include_groups=False),
           "label_mny": j[j.label.isin(["long_buildup", "short_buildup"])].groupby(["und", "label", "mny"]).apply(agg, include_groups=False),
           "label_dte": j[j.label.isin(["long_buildup", "short_buildup"])].groupby(["und", "label", "dte"]).apply(agg, include_groups=False),
           "year": j.groupby(["und", "y"]).apply(agg, include_groups=False)}
    lab = ch.groupby(["und", "label"]).size().unstack(fill_value=0)
    out["label_counts"] = lab
    return out


# ---- rules ------------------------------------------------------------------------------------------------------------
def run_rule(p: pd.DataFrame, sname: str, u: str, rows: pd.Series, sign_by_year: dict, ix: pd.DataFrame) -> pd.DataFrame:
    """Trades for |z| >= 1 days: buy u's ATM call/put at t+1's open; skip t+1 if it is an expiry day of the nearest expiry."""
    out = []
    sel = p[rows & (p[sname].abs() >= 1) & p.t1.notna()]
    for t, r in sel.iterrows():
        sg = sign_by_year.get(t.year)
        if sg is None or np.isnan(sg):
            continue
        d1 = r.t1.date()
        try:
            day = LD.bhav(d1)
        except FileNotFoundError:
            continue
        exp = pd.to_datetime(day[(day.und == u) & (day.kind != "FUT")].expiry).dt.date
        if len(exp) and (exp == d1).any():
            continue                                             # expiry day: skipped (Execution expiry='skip')
        ref = float(ix.loc[r.t1, f"{u}_open"]) if r.t1 in ix.index else np.nan
        if np.isnan(ref):
            continue
        side = int(np.sign(sg * r[sname]))
        for sd, tag in ((side, "rule"), (-side, "alt")):
            tr = RU.trade(day, u, d1, ref, sd)
            if tr is not None:
                tr.update({"signal_day": t.date(), "z": float(r[sname]), "tag": tag})
                out.append(tr)
    return pd.DataFrame(out)


def rule_summary(tr: pd.DataFrame) -> dict:
    a = tr[tr.tag == "rule"]
    b = tr[tr.tag == "alt"]
    if a.empty:
        return {"trades": 0}
    yr = a.groupby(pd.to_datetime(a.date).dt.year).net.sum()
    return {"trades": len(a), "wins": int((a.net > 0).sum()), "net Rs": float(a.net.sum()), "gross Rs": float(a.gross.sum()),
            "charges Rs": float(a.charges.sum()), "mean net Rs": float(a.net.mean()), "worst Rs": float(a.net.min()),
            "years +": int((yr > 0).sum()), "years": len(yr), "twin p": RU.twin_p(a, b),
            "exits": a.why.value_counts().to_dict()}


# ---- commands -----------------------------------------------------------------------------------------------------------
def design() -> int:
    RES.mkdir(parents=True, exist_ok=True)
    D = load_all()
    lo = D["wide"].index.min()
    rep = ["# OF1 design-period results (generated by analyze.py design)", "", "## Data", "", coverage(D), ""]

    g, tabs = pnl_tables(D, lo, CUT)
    g.to_csv(WORK / "group_pnl_daily_design.csv.gz", float_format="%.0f")       # daily series: Drive, not git
    rep += ["## Who profited (design, Rs crore, gross MTM; see PREREG §3 for the limits)", ""]
    for k, t in tabs.items():
        rep += [f"### {k}", "", md(t, "{:,.0f}"), ""]

    beh = behaviour(D, lo, CUT)
    rep += ["## Behaviour (design)", "", md(beh, "{:,.2f}"), "", "### Share of index F&O volume by group, % (design)", "",
            md(volume_share(D, lo, CUT), "{:,.1f}"), ""]

    p = panel(D)
    des = (p.t1 < CUT) & p.t1.notna()
    prim = tests(p, "oc1", 1, des)
    prim.to_csv(RES / "signals_design.csv", index=False)
    rep += ["## Predictive tests, primary family (open t+1 -> close t+1), design", "",
            f"36 tests, BH q = {Q}. Survivors: {int((prim.q < Q).sum())}.", "",
            md(prim.sort_values("p"), "{:.4f}", index=False), ""]
    for tg, lag in (("cc1", 1), ("cc5", 5), ("cc20", 20)):
        h = int(tg[2:])
        rows = des & (p.index + pd.offsets.BDay(h) < CUT)
        t = tests(p, tg, lag, rows)
        t.to_csv(RES / f"signals_design_{tg}.csv", index=False)
        rep += [f"### Descriptive: close t -> close t+{h} (HAC lag {lag}); BH within this horizon: {int((t.q < Q).sum())} with q < {Q}",
                "", md(t.sort_values("p").head(10), "{:.4f}", index=False), ""]
    lv = level_table(D, p, des)
    lv.to_csv(RES / "levels_design.csv", index=False)
    rep += ["### Descriptive: positioning LEVEL (long share percentile, quintiles) vs the next 1/5/20 days, % move", "",
            md(lv, "{:,.3f}", index=False), ""]
    wf = walk_forward(p, "S1_FII", "NIFTY")
    rep += ["### Walk-forward, FII S1 -> NIFTY open-to-close (|z| >= 1 days)", "", md(wf, "{:,.3f}"), ""]
    surv = prim[prim.q < Q].sort_values(["q", "p"], kind="mergesort")     # strongest q first; ties: smaller p
    for r in surv.head(3).itertuples():
        rep += [f"### Walk-forward, {r.signal} -> {r.index}", "", md(walk_forward(p, r.signal, r.index), "{:,.3f}"), ""]

    W = wall_rows(D)
    W.to_csv(WORK / "walls_rows.csv", index=False)                 # holds holdout expiries too: not in results/
    Wd = W[W.expiry < CUT]
    wt = wall_tests(Wd)
    wt.to_csv(RES / "walls_design.csv", index=False)
    rep += ["## Walls and magnets (design)", "", md(wt, "{:,.4f}", index=False), ""]

    bt = buildup_tables(D, lo, CUT)
    rep += ["## Option-chain build-up (design, nearest expiry, held to expiry)", ""]
    for k, t in bt.items():
        rep += [f"### {k}", "", md(t, "{:,.2f}"), ""]

    # R0 (always) and R1-R3 (design survivors of §5 / §6)
    rules = [("R0", "S1_FII", "NIFTY")]
    for i, r in enumerate(surv.head(3).itertuples(), 1):
        rules.append((f"R{i}", r.signal, r.index))
    summ = {}
    for name, sname, u in rules:
        signs = {}
        for Y in range(2014, 2026):
            tr = (p.index.year < Y) & (p.t1 < CUT)
            signs[Y] = float(np.sign(ST.ols_hac(p.loc[tr, f"{u}.oc1"], p.loc[tr, sname], 1)["b"]))
        trd = run_rule(p, sname, u, des & (p.index.year >= 2014), signs, D["ix"])
        trd.to_csv(RES / f"trades_{name}_design.csv.gz", index=False)
        summ[name] = {"signal": sname, "index": u, **rule_summary(trd)}
    qs = ST.bh([summ[k].get("twin p", np.nan) for k in summ])
    for k, qv in zip(summ, qs):
        summ[k]["BH q (R0-R3, twin p)"] = float(qv)
        summ[k]["design gates"] = bool(summ[k].get("net Rs", 0) > 0 and summ[k].get("twin p", 1) < 0.05 and qv < Q)
    wtsurv = wt[wt.q < Q]
    rep += ["## Rules (design walk-forward 2014-2025, daily-bar pessimistic, dated charges)", "",
            "```", json.dumps(summ, indent=1, default=str), "```", "",
            f"Wall/magnet design survivors: {len(wtsurv)} (no option-BUYING rule is defined for them; see PREREG §8)", ""]
    (RES / "design_summary.json").write_text(json.dumps({"survivors_signals": surv[["signal", "index", "b", "p", "q"]].to_dict("records"),
                                                         "survivors_walls": wtsurv.to_dict("records"), "rules": summ},
                                                        indent=1, default=str))
    (RES / "REPORT_design.md").write_text("\n".join(rep))
    print("\n".join(rep[:12]))
    print(f"signals survivors {len(surv)}; wall survivors {len(wtsurv)}; rules {list(summ)}")
    return 0


def holdout() -> int:
    flag = RES / "HOLDOUT_READ.json"
    if flag.exists():
        print("The holdout was already read:", flag.read_text())
        return 1
    ds = json.loads((RES / "design_summary.json").read_text())
    flag.write_text(json.dumps({"read_at": pd.Timestamp.now(tz="Asia/Kolkata").isoformat(), "period": [str(CUT.date()), str(END.date())],
                                "survivors_signals": ds["survivors_signals"], "survivors_walls": ds["survivors_walls"],
                                "rules": list(ds["rules"])}, indent=1, default=str))
    D = load_all()
    rep = ["# OF1 holdout results (ONE read, generated by analyze.py holdout)", "", f"Period {CUT.date()} .. {END.date()}.", ""]
    g, tabs = pnl_tables(D, CUT, END + pd.Timedelta(days=1))
    g.to_csv(WORK / "group_pnl_daily_holdout.csv.gz", float_format="%.0f")
    rep += ["## Who profited (holdout, descriptive, Rs crore)", ""]
    for k in ("fut|total", "fut2|total", "opt_all|total", "opt_near|total", "opt_far|total"):
        rep += [f"### {k}", "", md(tabs[k], "{:,.0f}"), ""]

    p = panel(D)
    ho = (p.index >= CUT) & p.t1.notna()
    des = (p.t1 < CUT) & p.t1.notna()
    res = []
    for s in ds["survivors_signals"]:
        r = ST.ols_hac(p.loc[ho, f"{s['index']}.oc1"], p.loc[ho, s["signal"]], 1)
        p1 = ST.one_sided(r["p"], r["b"], np.sign(s["b"]))
        res.append({**s, "holdout b": r["b"], "holdout n": r["n"], "holdout one-sided p": p1,
                    "pass": bool(np.sign(r["b"]) == np.sign(s["b"]) and p1 < 0.05)})
    rep += ["## Signals: design survivors on the holdout", "", md(pd.DataFrame(res), "{:.4f}", index=False) if res else "None to read.", ""]
    # every primary test on the holdout, for the record only (not a pass/fail: only survivors can pass)
    allh = tests(p, "oc1", 1, ho)
    allh.to_csv(RES / "signals_holdout_all.csv", index=False)

    W = pd.read_csv(WORK / "walls_rows.csv", parse_dates=["expiry", "day"])
    wh = wall_tests(W[W.expiry >= CUT])
    wh.to_csv(RES / "walls_holdout.csv", index=False)
    wres = []
    for s in ds["survivors_walls"]:
        m = wh[(wh.test == s["test"]) & (wh["index"] == s["index"]) & (wh.k == s["k"])]
        if len(m):
            wres.append({"test": s["test"], "index": s["index"], "k": s["k"], "holdout p": float(m.p.iloc[0]),
                         "pass": bool(m.p.iloc[0] < 0.05)})
    rep += ["## Walls/magnets: design survivors on the holdout", "", md(pd.DataFrame(wres), "{:.4f}", index=False) if wres else "None to read.", ""]

    summ = {}
    for name, r in ds["rules"].items():
        sname, u = r["signal"], r["index"]
        b = ST.ols_hac(p.loc[des, f"{u}.oc1"], p.loc[des, sname], 1)["b"]
        trd = run_rule(p, sname, u, ho, {2025: float(np.sign(b)), 2026: float(np.sign(b))}, D["ix"])
        trd.to_csv(RES / f"trades_{name}_holdout.csv.gz", index=False)
        summ[name] = {"signal": sname, "index": u, **rule_summary(trd)}
    rep += ["## Rules on the holdout", "", "```", json.dumps(summ, indent=1, default=str), "```", ""]
    bt = buildup_tables(D, CUT, END + pd.Timedelta(days=1))
    rep += ["## Build-up (holdout, descriptive)", "", md(bt["label"], "{:,.2f}"), ""]
    (RES / "REPORT_holdout.md").write_text("\n".join(rep))
    print("\n".join(rep))
    return 0


def summary() -> int:
    """Descriptive tables over the WHOLE period (design + holdout) for the write-up. Runs only after the holdout read
    (it reads holdout-period P&L, which is description, not a test)."""
    if not (RES / "HOLDOUT_READ.json").exists():
        print("Run `holdout` first: the summary covers holdout dates.")
        return 1
    D = load_all()
    lo = D["wide"].index.min()
    g, tabs = pnl_tables(D, lo, END + pd.Timedelta(days=1))
    rep = ["# OF1 who profited, 2012-2026 (design + holdout; descriptive; generated by analyze.py summary)", "",
           f"Sessions with an MTM step: {len(g):,} ({g.index.min().date()} .. {g.index.max().date()}). Rs crore, gross of "
           "costs, end-of-day positions only (PREREG §3).", ""]
    robust = []
    for b in ("fut", "fut2", "opt_all", "opt_near", "opt_far"):
        robust.append(tabs[f"{b}|total"].rename(index={"Rs cr": b}))
    rb = pd.concat(robust)
    rep += ["## Totals by estimate", "", md(rb, "{:,.0f}"), ""]
    opt_sign = np.sign(rb.loc[["opt_all", "opt_near", "opt_far"]])
    fut_sign = np.sign(rb.loc[["fut", "fut2"]])
    rows = []
    for gname in P.GROUPS:
        rows.append({"group": gname,
                     "index futures": ("+" if (fut_sign[gname] > 0).all() else "-" if (fut_sign[gname] < 0).all() else "mixed"),
                     "index options": ("+" if (opt_sign[gname] > 0).all() else "-" if (opt_sign[gname] < 0).all() else "mixed")})
    rep += ["## Robust sign (same under every estimate)", "", md(pd.DataFrame(rows).set_index("group")), ""]
    for k in ("fut|year", "opt_all|year", "fut|trend", "opt_all|trend", "fut|vix", "opt_all|vix", "fut|expiry_week",
              "opt_all|expiry_week"):
        rep += [f"## {k}", "", md(tabs[k], "{:,.0f}"), ""]
    # share of years each group made money (futures + options (a))
    tot = tabs["fut|year"].add(tabs["opt_all|year"], fill_value=0)
    rep += ["## Index futures + options (a), by year", "", md(tot, "{:,.0f}"), "",
            "Years with a profit: " + ", ".join(f"{c} {int((tot[c] > 0).sum())}/{len(tot)}" for c in tot.columns), ""]
    bt = buildup_tables(D, lo, END + pd.Timedelta(days=1))
    rep += ["## Build-up, 2012-2026 (nearest expiry, held to expiry)", "", md(bt["label"], "{:,.2f}"), "",
            md(bt["label_mny"], "{:,.2f}"), ""]
    (RES / "SUMMARY_full.md").write_text("\n".join(rep))
    print("\n".join(rep[:40]))
    return 0


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    sys.exit({"design": design, "holdout": holdout, "summary": summary}[cmd]())
