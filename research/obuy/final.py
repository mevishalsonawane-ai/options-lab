"""Final synthesis for research/OBUY_FINAL.md: one multiple-testing pass over every catalog variant, a ranked
family table and Monte Carlo for the paper-trade candidates.

    python3 -I research/obuy/final.py            # ~3-5 minutes, writes <OBUY_CACHE>/runs/final/final.json + tables

Inputs (all produced earlier by obuy, read as untrusted data):
  runs/ga_all/{variants,families}.csv, trades.csv.gz, ga_post.json     (1,329 variants, every trade)
  runs/gb_all/{variants,families,wf_years,mc}.csv + runs/gb_all_c*/chunk.pkl  (1,072 variants; daily P&L matrix X)
  runs/gc_all/{variants,families}.csv, trades.csv.gz                  (1,060 variants, every trade)
  runs/lv05_multiday/{variants.csv,summary.json}                      (100 variants; summary stats only)
  runs/singles, runs/grids (Liquidity 15+5, Solo, hero/ORB/straddle/big-bar grids) for the reference rows.
The gb chunk pickles are loaded with an allow-list unpickler (numpy / pandas / pyarrow / datetime only).
"""
from __future__ import annotations

import io
import json
import os
import pickle
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import obuy  # noqa: E402,F401  (adds the pandas deps path under python -I)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import stats as sst  # noqa: E402

from obuy import config as C  # noqa: E402
from obuy import overfit as OF  # noqa: E402

RUNS = os.path.join(C.CACHE, "runs")
OUT = os.path.join(RUNS, "final")
CAP = 500_000.0
DPY = 248.0


class SafeUnpickler(pickle.Unpickler):
    OK_PREFIX = ("numpy", "pandas", "pyarrow", "datetime")
    OK_BUILTINS = {"bytearray", "slice", "set", "frozenset", "complex", "range"}

    def find_class(self, module, name):
        if module.split(".")[0] in self.OK_PREFIX or (module == "builtins" and name in self.OK_BUILTINS) \
                or (module == "copyreg" and name == "_reconstructor"):
            return super().find_class(module, name)
        raise pickle.UnpicklingError(f"blocked global {module}.{name}")


def load_pkl(path):
    with open(path, "rb") as f:
        return SafeUnpickler(io.BytesIO(f.read())).load()


def daily_from_trades(path, vids, days):
    t = pd.read_csv(path, usecols=["day", "net", "vid"], dtype={"vid": "category"})
    t["day"] = pd.to_datetime(t["day"]).dt.date
    g = t.groupby(["day", "vid"], observed=True).net.sum().reset_index()
    dpos = {d: i for i, d in enumerate(days)}
    vpos = {v: j for j, v in enumerate(vids)}
    X = np.zeros((len(days), len(vids)))
    miss = g[~g.day.isin(dpos)]
    g = g[g.day.isin(dpos) & g.vid.isin(vpos)]
    np.add.at(X, (g.day.map(dpos).values.astype(int), g.vid.astype(str).map(vpos).values.astype(int)), g.net.values)
    return X, float(miss.net.sum()), len(miss)


def wf_years_from_trades(path, fam):
    """Per-year walk-forward net from the families.csv picks and the run's trades."""
    t = pd.read_csv(path, usecols=["year", "net", "vid"])
    by = t.groupby(["vid", "year"]).net.sum()
    out = {}
    for s, r in fam.iterrows():
        if not isinstance(r.get("picks"), str):
            continue
        yrs = {}
        for item in r.picks.split(";"):
            y, rest = item.split(":", 1)
            vid = f"{s}|{rest}"
            yrs[int(y)] = float(by.get((vid, int(y)), 0.0))
        out[s] = yrs
    return out


def tstat_p(X):
    T = X.shape[0]
    mu = X.mean(0)
    sd = X.std(0, ddof=1)
    t = np.where(sd > 0, mu / (sd / np.sqrt(T)), 0.0)
    return t, sst.t.sf(t, T - 1)


def block_boot(d, n_days=248, B=10000, block=5, seed=11):
    """Stationary block bootstrap of a daily P&L series into 1-year paths. Returns P(profit), P(DD>=10%/20% of
    Rs 5 lakh), median, 5th / 95th pct of the year's P&L."""
    rng = np.random.default_rng(seed)
    d = np.asarray(d, float)
    T = len(d)
    p = 1.0 / block
    idx = np.empty((B, n_days), dtype=np.int64)
    idx[:, 0] = rng.integers(0, T, B)
    jump = rng.random((B, n_days)) < p
    newp = rng.integers(0, T, (B, n_days))
    for k in range(1, n_days):
        idx[:, k] = np.where(jump[:, k], newp[:, k], (idx[:, k - 1] + 1) % T)
    paths = d[idx].cumsum(1)
    peak = np.maximum.accumulate(np.maximum(paths, 0), axis=1)
    dd = (paths - peak).min(1)
    fin = paths[:, -1]
    return dict(p_profit=float((fin > 0).mean()), p_dd10=float((dd <= -0.10 * CAP).mean()),
                p_dd20=float((dd <= -0.20 * CAP).mean()), median=float(np.median(fin)),
                p5=float(np.quantile(fin, 0.05)), p95=float(np.quantile(fin, 0.95)),
                mean_day=float(d.mean()), days=int(T))


NAMES = {
    "liquidity15_5": "App: Liquidity 15+5 (exits tuned on this data)", "solo_midday": "App: Solo midday (C0 exit picked after the fact)",
    "hero_zero": "App: Hero rule, NIFTY expiry (fixed rule)", "random_entry": "Control: random entries",
    "bigbar_pullback": "Big-bar pullback (fixed)", "orb15": "ORB 15 (fixed)", "straddle920": "09:20 straddle (fixed)",
    "hero_grid": "Hero grid (54 variants)", "solo_midday_grid": "Solo grid (414)", "straddle_grid": "Straddle-time grid (180)",
    "bigbar_grid": "Big-bar grid (192)", "orb_grid": "ORB grid (384)", "lv05_multiday": "LV-05 Donchian, multi-day hold",
    "ga_or01_orb15": "OR-01 15-min ORB", "ga_or02_first5": "OR-02 first 5-min candle", "ga_or03_late": "OR-03 late/wide ORB",
    "ga_or04_box9": "OR-04 Bank Nifty Box 9", "ga_or05_ohol": "OR-05 open = low/high", "ga_or06_premmom": "OR-06 09:20 premium momentum",
    "ga_or07_gapgo": "OR-07 gap and go", "ga_or08_gapfill": "OR-08 gap fill", "ga_td01_c250": "TD-01 2:50 pm candle",
    "ga_td02_gao": "TD-02 first half-hour -> last", "ga_td03_jackpot": "TD-03 last-30-min OTM jackpot",
    "ga_lv01_pdhl": "LV-01 PDH/PDL break-retest", "ga_lv02_cpr": "LV-02 narrow CPR", "ga_lv03_camarilla": "LV-03 Camarilla H4/L4 breakout",
    "ga_lv04_nr7": "LV-04 NR7/inside day (1st day)", "ga_lv05_donchian": "LV-05 Donchian (1st day only)", "ga_lv06_oiwall": "LV-06 OI wall break",
    "gb_ti01_supertrend": "TI-01 Supertrend flip", "gb_ti02_st_ema": "TI-02 Supertrend + EMA", "gb_ti03_ema_cross": "TI-03 EMA cross",
    "gb_ti04_ema9_twap": "TI-04 EMA9 x VWAP", "gb_ti05_vwap_pullback": "TI-05 VWAP pullback", "gb_ti06_rsi50": "TI-06 RSI 50",
    "gb_ti07_rsi_shift": "TI-07 RSI 60/40", "gb_ti08_heikin_ashi": "TI-08 Heikin-Ashi", "gb_ti09_adx": "TI-09 ADX/DMI",
    "gb_ti10_confluence": "TI-10 ST+EMA+VWAP+RSI confluence", "gb_mr01_5ema": "MR-01 5-EMA alert candle",
    "gb_mr02_bb_alert": "MR-02 Bollinger alert", "gb_mr03_rsi_reversal": "MR-03 RSI 30/70 reversal",
    "gb_mr04_camarilla": "MR-04 Camarilla R3/S3 fade", "gb_mr05_vwap_stretch": "MR-05 VWAP stretch fade",
    "gb_mr06_or_fade": "MR-06 opening-range fade", "gc_exp01_hero": "EXP-01 hero-zero (Rs 5k tickets)",
    "gc_exp02_gamma": "EXP-02 gamma blast", "gc_exp03_orb": "EXP-03 expiry-day ORB", "gc_exp04_maxpain": "EXP-04 max pain",
    "gc_exp05_scalp1": "EXP-05 +10% scalp, 1/day", "gc_exp05_scalp5": "EXP-05 +10% scalp, 5/day", "gc_oi01_pcr": "OI-01 PCR contrarian",
    "gc_oi02_doi": "OI-02 intraday OI change", "gc_oi04_maxoi": "OI-04 max-OI magnet", "gc_vol01_strad": "VOL-01 long straddle",
    "gc_vol02_sbo": "VOL-02 straddle-premium breakout", "gc_vol03_ivp": "VOL-03 low-IVP straddle", "gc_vol04_vixorb": "VOL-04 VIX + ORB",
    "gc_ev05_runup": "VOL-05 pre-event run-up", "gc_ev06_eventday": "VOL-06 event-day straddle", "gc_ev07_post": "VOL-07 post-event breakout",
    "gc_pos01_btst": "POS-01 BTST (strong close)", "gc_pos02_trend": "POS-02 Supertrend positional", "gc_pos04_nextwk": "POS-04 new weekly after expiry",
}


def fmt(x):
    return f"{x:+,.0f}"


def write_table(fam, path):
    L = ["| # | strategy family | variants | WF Rs total | Rs / year | % of Rs 5L / yr | years + | WF by year (2022 .. 2026) | "
         "beats random p raw / BH / Holm | max DD | P(profit yr) | P(DD >= 20%) |", "|" + "---|" * 12]
    for i, r in fam.iterrows():
        ys = r.years
        yrs = " / ".join(f"{v / 1000:+.0f}k" if y in ys else "-" for y, v in
                         [(y, ys.get(y, 0)) for y in range(2022, 2027)])
        L.append(f"| {i + 1} | {NAMES.get(r.strategy, r.strategy)} | {r.n_variants} | {fmt(r.wf_net)} | {fmt(r.wf_per_year)} | "
                 f"{100 * r.wf_per_year / CAP:+.1f}% | {r.wf_years_pos}/{r.wf_years} | {yrs} | "
                 f"{r.p_rand:.3f} / {r.p_bh:.2f} / {r.p_holm:.2f} | {fmt(r.wf_dd)} | {100 * r.mc_p_profit:.0f}% | {100 * r.mc_p_dd20:.0f}% |")
    open(path, "w").write("\n".join(L) + "\n")


def main():
    os.makedirs(OUT, exist_ok=True)
    res = {}

    # ---------------------------------------------------------------- gb: daily matrices from the chunk pickles
    chunks = [load_pkl(os.path.join(RUNS, f"gb_all_c{k}", "chunk.pkl")) for k in range(7)]
    days = {d for r in chunks for d in r["days"]}
    for n in ["ga_all", "gc_all", "grids", "singles"]:     # ga / grids also traded 2026-10-06
        days |= set(pd.to_datetime(pd.read_csv(os.path.join(RUNS, n, "trades.csv.gz"), usecols=["day"]).day.unique()).date)
    days = sorted(days)
    dpos = {d: i for i, d in enumerate(days)}
    gb_vids, gb_cols = [], []
    for r in chunks:
        Xc = np.zeros((len(days), len(r["vids"])))
        Xc[[dpos[d] for d in r["days"]], :] = np.asarray(r["X"], float)
        gb_cols.append(Xc)
        gb_vids += list(r["vids"])
    Xgb = np.hstack(gb_cols)
    del chunks
    print("calendar", days[0], days[-1], len(days), "gb", Xgb.shape)

    V = {}
    for n in ["ga_all", "gb_all", "gc_all"]:
        V[n] = pd.read_csv(os.path.join(RUNS, n, "variants.csv"))
    lv = pd.read_csv(os.path.join(RUNS, "lv05_multiday", "variants.csv")).rename(columns={"Unnamed: 0": "vid"})
    lv["vid"] = "lv05_multiday|" + lv["vid"].astype(str)

    ga_vids = list(V["ga_all"].vid)
    gc_vids = list(V["gc_all"].vid)
    Xga, miss_ga, nmiss_ga = daily_from_trades(os.path.join(RUNS, "ga_all", "trades.csv.gz"), ga_vids, days)
    Xgc, miss_gc, nmiss_gc = daily_from_trades(os.path.join(RUNS, "gc_all", "trades.csv.gz"), gc_vids, days)
    print("trades outside the calendar: ga", nmiss_ga, miss_ga, "gc", nmiss_gc, miss_gc)

    # parity: column sums against variants.csv net
    chk = {}
    for name, X, vids in [("ga", Xga, ga_vids), ("gb", Xgb, gb_vids), ("gc", Xgc, gc_vids)]:
        vv = V[name + "_all"].set_index("vid").net.reindex(vids).values
        chk[name] = float(np.nanmax(np.abs(X.sum(0) - vv)))
    print("max |daily sum - variants.net|:", chk)
    res["parity_max_abs_diff"] = chk

    # ---------------------------------------------------------------- 1. union multiple testing
    Xcat = np.hstack([Xga, Xgb, Xgc])
    vids_cat = ga_vids + gb_vids + gc_vids
    t, p_t = tstat_p(Xcat)
    # lv05: no daily matrix on disk; t from its annualised daily Sharpe (sharpe = mean/sd*sqrt(248) over 1,528 days)
    t_lv = lv.sharpe.fillna(0).values * np.sqrt(len(days) / DPY)
    p_lv = sst.t.sf(t_lv, len(days) - 1)
    t_all = np.concatenate([t, t_lv])
    p_all = np.concatenate([p_t, p_lv])
    vids_all = vids_cat + list(lv.vid)
    m = len(p_all)
    q_bh = OF.bh(p_all)
    q_holm = OF.holm(p_all)
    order = np.argsort(-t_all)
    top_t = [dict(vid=vids_all[i], t=float(t_all[i]), p=float(p_all[i]), bh=float(q_bh[i]), holm=float(q_holm[i]),
                  bonf=float(min(1, p_all[i] * m))) for i in order[:12]]
    res["t_union"] = dict(m=m, n_pos_mean=int((t_all > 0).sum()), n_t2=int((t_all > 2).sum()),
                          n_t3=int((t_all > 3).sum()), min_bh=float(q_bh.min()), min_holm=float(q_holm.min()),
                          n_bh10=int((q_bh <= 0.10).sum()), n_bh05=int((q_bh <= 0.05).sum()),
                          bonf_t_needed=float(sst.t.isf(0.05 / m, len(days) - 1)), top=top_t)
    print("t union", {k: v for k, v in res["t_union"].items() if k != "top"})

    # random-baseline p over the union of reported per-variant p's
    pr = np.concatenate([V["ga_all"].p_rand.values, V["gb_all"].p_rand.values, V["gc_all"].p_rand.values,
                         lv.p_rand.values])
    pr = np.where(np.isfinite(pr), pr, 1.0)
    vids_pr = ga_vids + list(V["gb_all"].vid) + gc_vids + list(lv.vid)
    nets = np.concatenate([V["ga_all"].net.values, V["gb_all"].net.values, V["gc_all"].net.values, lv.net.values])
    qb, qh = OF.bh(pr), OF.holm(pr)
    o = np.argsort(pr)
    floor = float(pr.min())
    res["p_rand_union"] = dict(m=len(pr), floor=floor, n_at_floor=int((pr <= floor + 1e-12).sum()),
                               n_raw05=int((pr < 0.05).sum()), n_raw01=int((pr < 0.01).sum()),
                               min_bh=float(qb.min()), n_bh05=int((qb <= 0.05).sum()), n_bh10=int((qb <= 0.10).sum()),
                               min_holm=float(qh.min()), bonf_min=float(min(1, floor * len(pr))),
                               bh10=[dict(vid=vids_pr[i], p=float(pr[i]), bh=float(qb[i]), net=float(nets[i]))
                                     for i in o if qb[i] <= 0.10],
                               top=[dict(vid=vids_pr[i], p=float(pr[i]), bh=float(qb[i]), holm=float(qh[i]),
                                         net=float(nets[i])) for i in o[:25]])
    print("p_rand union", {k: v for k, v in res["p_rand_union"].items() if k not in ("top", "bh10")})

    # SPA / RC over the 3,461 catalog variants with daily series
    sp = OF.spa(Xcat, B=1000, mean_block=5.0)
    sp["best_vid"] = vids_cat[sp["best"]]
    res["spa_catalog"] = sp
    print("SPA catalog", sp)

    # sensitivity: + the validation grids (1,224) + singles (Liquidity, Solo, hero, ORB, straddle, big-bar, random)
    gv = pd.read_csv(os.path.join(RUNS, "grids", "variants.csv"))
    sv = pd.read_csv(os.path.join(RUNS, "singles", "variants.csv"))
    Xgr, _, _ = daily_from_trades(os.path.join(RUNS, "grids", "trades.csv.gz"), list(gv.vid), days)
    Xsi, _, _ = daily_from_trades(os.path.join(RUNS, "singles", "trades.csv.gz"), list(sv.vid), days)
    Xbig = np.hstack([Xcat, Xgr, Xsi])
    vids_big = vids_cat + list(gv.vid) + list(sv.vid)
    sp2 = OF.spa(Xbig, B=1000, mean_block=5.0)
    sp2["best_vid"] = vids_big[sp2["best"]]
    res["spa_all_incl_liquidity"] = sp2
    tb, pb = tstat_p(Xbig)
    li = vids_big.index("liquidity15_5|s0|r0|x0")
    so = vids_big.index("solo_midday|s0|r0|x0")
    qbb = OF.bh(np.concatenate([pb, p_lv]))
    res["liquidity_in_union"] = dict(m=len(pb) + len(p_lv), t=float(tb[li]), p=float(pb[li]), bh=float(qbb[li]),
                                     bonf=float(min(1, pb[li] * (len(pb) + len(p_lv)))),
                                     solo_t=float(tb[so]), solo_p=float(pb[so]), solo_bh=float(qbb[so]))
    print("SPA incl liquidity", sp2, res["liquidity_in_union"])
    # Liquidity alone, and Liquidity against the 3,461 catalog variants (is it the best?)
    res["spa_liquidity_alone"] = OF.spa(Xsi[:, [list(sv.vid).index("liquidity15_5|s0|r0|x0")]], B=1000)

    # ---------------------------------------------------------------- 2. family table inputs
    F = {}
    for n in ["ga_all", "gb_all", "gc_all", "singles", "grids"]:
        F[n] = pd.read_csv(os.path.join(RUNS, n, "families.csv")).set_index("strategy")
    years = {}
    gpost = json.load(open(os.path.join(RUNS, "ga_all", "ga_post.json")))
    for s, d in gpost.items():
        years[s] = {int(k): float(v) for k, v in d["yrs"].items()}
    wy = pd.read_csv(os.path.join(RUNS, "gb_all", "wf_years.csv"))
    for s, g in wy.groupby("strategy"):
        years[s] = {int(r.year): float(r.test_net) for r in g.itertuples()}
    years.update(wf_years_from_trades(os.path.join(RUNS, "gc_all", "trades.csv.gz"), F["gc_all"]))
    years.update(wf_years_from_trades(os.path.join(RUNS, "grids", "trades.csv.gz"), F["grids"]))
    # ga check against ga_post
    ga_re = wf_years_from_trades(os.path.join(RUNS, "ga_all", "trades.csv.gz"), F["ga_all"])
    res["ga_wf_recompute_max_diff"] = max(abs(ga_re[s][y] - years[s][y]) for s in ga_re for y in ga_re[s])
    st = pd.read_csv(os.path.join(RUNS, "singles", "trades.csv.gz"), usecols=["year", "net", "vid"])
    for s, r in F["singles"].iterrows():
        by = st[st.vid == f"{s}|s0|r0|x0"].groupby("year").net.sum()
        first = int(by.index.min())
        years[s] = {int(y): float(v) for y, v in by.items() if y >= first + 2}
    lvs = json.load(open(os.path.join(RUNS, "lv05_multiday", "summary.json")))
    years["lv05_multiday"] = {int(r["year"]): float(r["test_net"]) for r in lvs["wf"]}

    rows = []
    for n in ["ga_all", "gb_all", "gc_all", "singles", "grids"]:
        for s, r in F[n].iterrows():
            rows.append(dict(strategy=s, run=n, n_variants=int(r.n_variants), wf_trades=int(r.wf_trades),
                             wf_net=float(r.wf_net), wf_per_year=float(r.wf_per_year), wf_years=int(r.wf_years),
                             wf_years_pos=int(r.wf_years_pos), wf_dd=float(r.wf_dd), p_rand=float(r.p_rand),
                             mc_p_profit=float(r.mc_p_profit), mc_p_dd20=float(r.mc_p_dd20),
                             best_net=float(r.best_net), years=years.get(s, {})))
    w = lvs["wf_summary"]
    rows.append(dict(strategy="lv05_multiday", run="lv05_multiday", n_variants=100, wf_trades=int(w["trades"]),
                     wf_net=float(w["net"]), wf_per_year=float(w["per_year"]), wf_years=int(w["years"]),
                     wf_years_pos=int(w["years_pos"]), wf_dd=float(w["max_dd"]), p_rand=float(lvs["wf_p_rand"]["p"]),
                     mc_p_profit=float(lvs["monte_carlo"]["1 lot"]["p_profit"]),
                     mc_p_dd20=float(lvs["monte_carlo"]["1 lot"]["p_dd20"]),
                     best_net=float(lv.net.max()), years=years["lv05_multiday"]))
    fam = pd.DataFrame(rows)
    # GA LV-05 next-day: use the fairer time-matched baseline from the robustness rerun (OBUY_GA), not 0.0005
    tm = pd.read_csv(os.path.join(RUNS, "ga_lv05_check", "families.csv")).set_index("strategy")
    fam.loc[fam.strategy == "ga_lv05_donchian", "p_rand"] = float(tm.loc["ga_lv05_nextday_tm", "p_rand"])
    fam["p_bh"] = OF.bh(fam.p_rand.values)
    fam["p_holm"] = OF.holm(fam.p_rand.values)
    cat = fam.run.isin(["ga_all", "gb_all", "gc_all", "lv05_multiday"])
    fam.loc[cat, "p_bh_cat"] = OF.bh(fam.loc[cat, "p_rand"].values)
    fam.loc[cat, "p_holm_cat"] = OF.holm(fam.loc[cat, "p_rand"].values)
    fam = fam.sort_values("wf_per_year", ascending=False).reset_index(drop=True)
    res["families"] = fam.to_dict(orient="records")
    res["family_m"] = dict(all=int(len(fam)), catalog=int(cat.sum()))

    # pooled odds across the catalog families' walk-forwards
    c = fam[fam.run.isin(["ga_all", "gb_all", "gc_all", "lv05_multiday"])]
    fy = [v for ys in c.years for v in ys.values()]
    res["pooled_catalog"] = dict(families=int(len(c)), fam_wf_pos=int((c.wf_net > 0).sum()),
                                 family_years=len(fy), family_years_pos=int(sum(v > 0 for v in fy)),
                                 median_mc_p_profit=float(c.mc_p_profit.median()),
                                 share_mc_p_profit_ge50=float((c.mc_p_profit >= 0.5).mean()),
                                 median_wf_per_year=float(c.wf_per_year.median()),
                                 total_wf_net=float(c.wf_net.sum()),
                                 share_variants_pos_net=float((nets > 0).mean()),
                                 median_mc_p_dd20=float(c.mc_p_dd20.median()),
                                 share_mc_p_dd20_ge25=float((c.mc_p_dd20 >= 0.25).mean()),
                                 share_wf_dd_ge_1lakh=float((c.wf_dd <= -0.2 * CAP).mean()))
    bh10 = pd.Series([r["vid"].split("|")[0] for r in res["p_rand_union"]["bh10"]]).value_counts()
    res["p_rand_union"]["bh10_by_strategy"] = {k: int(v) for k, v in bh10.items()}

    # ---------------------------------------------------------------- 3. the candidates on Rs 5 lakh
    day_ix = pd.to_datetime(pd.Series(days))
    cand = {}
    # BTST strong close: the full-sample best POS-01 variant, by period
    gcv = V["gc_all"]
    b = gcv[gcv.strategy == "gc_pos01_btst"].sort_values("net", ascending=False).iloc[0]
    j = gc_vids.index(b.vid)
    d = Xgc[:, j]
    cand["btst_best"] = dict(vid=b.vid, rule=b.rule, exits=b.exits, sig=b.sig, net=float(b.net),
                             all=block_boot(d), since2023=block_boot(d[(day_ix.dt.year >= 2023).values]),
                             since2024=block_boot(d[(day_ix.dt.year >= 2024).values]))
    # BTST walk-forward daily (from picks)
    # Camarilla fade MR-04: full-sample best variant and the WF picks' variant
    for key, vid in [("camarilla_best", "gb_mr04_camarilla|s0|r0|x0"), ("camarilla_wfpick", "gb_mr04_camarilla|s0|r1|x0")]:
        j = gb_vids.index(vid)
        d = Xgb[:, j]
        row = V["gb_all"].set_index("vid").loc[vid]
        cand[key] = dict(vid=vid, rule=row.rule, exits=row.exits, sig=row.sig, net=float(row.net),
                         p_rand=float(row.p_rand), all=block_boot(d),
                         since2023=block_boot(d[(day_ix.dt.year >= 2023).values]),
                         by_year={int(y): float(v) for y, v in pd.Series(d).groupby(day_ix.dt.year.values).sum().items()})
    # Liquidity 15+5: all, walk-forward years, and only the windows its rules were not tuned on
    j = list(sv.vid).index("liquidity15_5|s0|r0|x0")
    d = Xsi[:, j]
    act = pd.Series(d, index=day_ix.values)
    first = act[act != 0].index.min()
    span = (day_ix >= first).values
    untuned = span & ((day_ix < "2024-02-13") | (day_ix > "2026-02-23")).values
    cand["liquidity"] = dict(all=block_boot(d[span]), wf2023=block_boot(d[(day_ix.dt.year >= 2023).values]),
                             untuned=block_boot(d[untuned]),
                             untuned_net=float(d[untuned].sum()), untuned_days=int(untuned.sum()),
                             pre2024_net=float(d[span & (day_ix < "2024-02-13").values].sum()),
                             post_feb26_net=float(d[(day_ix > "2026-02-23").values].sum()),
                             by_year={int(y): float(v) for y, v in pd.Series(d).groupby(day_ix.dt.year.values).sum().items()})
    j = list(sv.vid).index("solo_midday|s0|r0|x0")
    cand["solo"] = dict(all=block_boot(Xsi[:, j]))
    res["candidates"] = cand

    write_table(fam, os.path.join(OUT, "family_table.md"))
    with open(os.path.join(OUT, "final.json"), "w") as f:
        json.dump(res, f, indent=1, default=str)
    print(json.dumps({k: v for k, v in res.items() if k not in ("families",)}, indent=1, default=str)[:12000])
    pd.set_option("display.width", 250)
    print(fam.drop(columns=["years"]).to_string())
    for _, r in fam.iterrows():
        print(r.strategy, r.years)


if __name__ == "__main__":
    main()
