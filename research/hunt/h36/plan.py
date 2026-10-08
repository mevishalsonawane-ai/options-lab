"""h36: the honest fixed-lot plan at Rs 1 lakh, seen the way Boss sees it (days, not averages).

    python3 -I research/hunt/h36/plan.py      (light: reads existing trade tables, no market data)

No new rule, no tuning. Inputs, all already on disk:
  - h24 trades24.parquet: Liquidity 15+5, 1-ITM nearest monthly, h14 limit entry +0.5% / 3 min, h10 impact, app charges,
    real half-spread from the 2026-10-06 snapshot ("flat_x1": BN 0.162%, MIDCP 0.213%). kappa 0.02 central, 0.04 stress.
  - h23 run.walk: Rs 1 lakh, fixed 1 lot per index, free-cash check.
  - h36 flags.py output (h26's BU15 "skip if 15-min OI build-up opposes the trade" flag), joined per trade.
Plans:  A = BANKNIFTY 1 lot.  B = A + MIDCPNIFTY 1 lot (h17's pre-registered plan).  C = B minus BU15-opposed trades
(POST-HOC: h26's filter failed its own pre-registered adoption bar, BH q 0.13 > 0.10).
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
OUT = os.path.join(SCR, "hunt/h36")
HUNT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BOSS = {"2026-09-28": -56, "2026-09-29": 589, "2026-09-30": -6014, "2026-10-01": 5032, "2026-10-05": 4955,
        "2026-10-06": -4551, "2026-10-07": -3746}
LINES = []


def P(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    LINES.append(s)


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


R = _load("r23", os.path.join(HUNT, "h23", "run.py"))


def trades(model="flat_x1"):
    T = pd.read_parquet(os.path.join(SCR, "hunt/h24/trades24.parquet"))
    T = T[T.model == model].copy()
    T["day"] = pd.to_datetime(T.day)
    F = pd.read_parquet(os.path.join(OUT, "bu15_flags.parquet"))
    F["day"] = pd.to_datetime(F.day)
    key = ["und", "book", "day", "side", "entry_min"]
    F = F.drop_duplicates(key)
    T = T.merge(F[key + ["BU15", "skip_bu15"]], on=key, how="left")
    T["skip_bu15"] = T.skip_bu15.fillna(False).astype(bool)
    return T


def wide(T, kap):
    t = T[T.kappa == kap].copy()
    for c in ("net", "gross", "lots", "prem", "end"):
        t[f"{c}_{kap}"] = t[c]
    return t


def walk_counts(t, unds, k, lo, hi):
    """h23 walk, plus the number of filled trades per day (same free-cash logic; checked equal to R.walk)."""
    s, dn = R.walk(t, unds, k, lo, hi)
    x = t[t.und.isin(unds) & (t.day >= lo) & (t.day < hi) & (t[f"lots_{k}"] > 0)]
    n = x.groupby("day").size().reindex(dn.index, fill_value=0)
    return s, dn, n


def stat_boot(x, days=250, B=4000, block=10, seed=36, start=100_000.0):
    """stationary block bootstrap of one trading year (fixed lots, so daily P&L does not depend on capital)."""
    x = np.asarray(x, float)
    n = len(x)
    rng = np.random.default_rng(seed)
    out = np.empty((B, days))
    p = 1.0 / block
    i = rng.integers(n, size=B)
    for d in range(days):
        if d:
            jump = rng.random(B) < p
            i = np.where(jump, rng.integers(n, size=B), (i + 1) % n)
        out[:, d] = x[i]
    cum = out.cumsum(axis=1)
    peak = np.maximum.accumulate(np.concatenate([np.zeros((B, 1)), cum], axis=1), axis=1)[:, 1:]
    dd = (cum - peak).min(axis=1)
    m21 = out[:, : (days // 21) * 21].reshape(B, days // 21, 21).sum(axis=2)
    return dict(p_pos_year=float((cum[:, -1] > 0).mean()), p_cap_le_50k=float((cum.min(axis=1) <= -50_000).mean()),
                p_dd_ge_50k=float((dd <= -50_000).mean()), p_cap_le_25k=float((cum.min(axis=1) <= -75_000).mean()),
                year_p10=float(np.percentile(cum[:, -1], 10)), year_p50=float(np.percentile(cum[:, -1], 50)),
                year_p90=float(np.percentile(cum[:, -1], 90)),
                month_p10=float(np.percentile(m21, 10)), month_p25=float(np.percentile(m21, 25)),
                month_p50=float(np.percentile(m21, 50)), month_p75=float(np.percentile(m21, 75)),
                month_p90=float(np.percentile(m21, 90)), p_month_loss=float((m21 < 0).mean()),
                p_month_ge_1L=float((m21 >= 100_000).mean()))


def longest(mask):
    best = cur = 0
    for v in mask:
        cur = cur + 1 if v else 0
        best = max(best, cur)
    return best


def describe(dn, n, s, label):
    x = dn.values
    traded = n.values > 0
    xt = x[traded]
    eq = dn.cumsum()
    peak = np.maximum.accumulate(np.r_[0.0, eq.values])[1:]
    under = (eq.values - peak) < 0
    mon = dn.groupby(dn.index.to_period("M")).sum()
    d = dict(plan=label, days=len(x), net_day_mean=x.mean(), net_day_median=float(np.median(x)),
             median_traded_day=float(np.median(xt)) if len(xt) else np.nan,
             gross_day=s["gross_day"], pct_days_traded=100 * traded.mean(), trades=int(n.sum()),
             pct_ge_1k=100 * (x >= 1000).mean(), pct_ge_3k=100 * (x >= 3000).mean(), pct_ge_5k=100 * (x >= 5000).mean(),
             pct_le_m3k=100 * (x <= -3000).mean(), pct_le_m5k=100 * (x <= -5000).mean(),
             pct_green_days=100 * (x > 0).mean(), pct_red_days=100 * (x < 0).mean(),
             n_ge_5k=int((x >= 5000).sum()), n_le_m5k=int((x <= -5000).sum()),
             best_day=x.max(), worst_day=x.min(), sd_day=x.std(ddof=1),
             losing_streak_tradedays=longest(xt < 0), losing_streak_sessions=longest(x < 0),
             longest_underwater_sessions=longest(under), maxdd=s["maxdd"], min_cap=s["min_cap"], end_cap=s["end_cap"],
             green_months=s["green_months"], pct_green_months=100 * s["pct_green"], worst_month=float(mon.min()),
             best_month=float(mon.max()), skipped_cash=s["skipped_cash"],
             top10_share=100 * np.sort(x)[-10:].sum() / x.sum() if x.sum() > 0 else np.nan)
    d.update(stat_boot(x))
    return d


def main():
    T = trades()
    k02 = T[T.kappa == 0.02]
    P("join check: trades", len(k02), "with BU15 flag", int(k02.BU15.notna().sum()),
      "skip flags", int(k02.skip_bu15.sum()), k02.groupby("und").skip_bu15.sum().to_dict())
    plans = {"A: BN 1 lot": (("BANKNIFTY",), False), "B: BN 1 + MIDCP 1": (("BANKNIFTY", "MIDCPNIFTY"), False),
             "C: B + OI filter (post-hoc)": (("BANKNIFTY", "MIDCPNIFTY"), True)}
    wins = (("pre", R.START, R.HOLD), ("hold", R.HOLD, R.END))
    rows, series, extremes = [], {}, {}
    for kap in (0.02, 0.04):
        t = wide(T, kap)
        for pn, (unds, filt) in plans.items():
            tt = t[~t.skip_bu15] if filt else t
            for wn, lo, hi in wins:
                s, dn, n = walk_counts(tt, unds, kap, lo, hi)
                if kap == 0.02 and wn == "hold" and not filt:
                    ref = {("BANKNIFTY",): 167, ("BANKNIFTY", "MIDCPNIFTY"): 198}[unds]
                    P(f"VALIDATION {pn} holdout k.02: {s['net_day']:.1f} Rs/day vs h24 {ref}")
                d = describe(dn, n, s, pn)
                d.update(kappa=kap, window=wn)
                rows.append(d)
                if kap == 0.02:
                    series[(pn, wn)] = dn
                    o = dn.sort_values()
                    extremes[(pn, wn)] = (o.tail(10)[::-1], o.head(10))
    D = pd.DataFrame(rows)
    D.to_csv(os.path.join(OUT, "plans.csv"), index=False)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 60)
    for kap in (0.02, 0.04):
        for wn, _, _ in wins:
            x = D[(D.kappa == kap) & (D.window == wn)].set_index("plan").drop(columns=["kappa", "window"]).T
            P(f"\n===== kappa {kap}  window {wn} =====\n" + x.to_string(float_format=lambda v: f"{v:,.2f}"))
    for (pn, wn), (best, worst) in extremes.items():
        P(f"\n{pn} [{wn}] best 10: " + ", ".join(f"{d.date()} {v:+,.0f}" for d, v in best.items()))
        P(f"{pn} [{wn}] worst 10: " + ", ".join(f"{d.date()} {v:+,.0f}" for d, v in worst.items()))
    # monthly tables
    for (pn, wn), dn in series.items():
        m = dn.groupby(dn.index.to_period("M")).sum()
        m.to_csv(os.path.join(OUT, f"months_{pn[0]}_{wn}.csv"))
        if wn == "hold":
            P(f"\n{pn} holdout months: " + ", ".join(f"{p} {v / 1000:+.1f}k" for p, v in m.items()))
    # yearly (pre)
    for pn in plans:
        dn = series[(pn, "pre")]
        y = dn.groupby(dn.index.year).agg(["sum", "mean", "count"])
        P(f"\n{pn} pre-holdout by year (sum, Rs/day, days):\n" + y.round(0).to_string())
    # Boss's paper days vs the plans' own days on those dates, and how rare such days are
    P("\nBoss paper days vs plan days (kappa .02, real spread):")
    allA = pd.concat([series[("A: BN 1 lot", "pre")], series[("A: BN 1 lot", "hold")]])
    allB = pd.concat([series[("B: BN 1 + MIDCP 1", "pre")], series[("B: BN 1 + MIDCP 1", "hold")]])
    for d, v in BOSS.items():
        ts = pd.Timestamp(d)
        a = allA.get(ts, np.nan)
        b = allB.get(ts, np.nan)
        P(f"  {d}: Boss {v:+,}  planA {a:+,.0f}  planB {b:+,.0f}")
    bv = np.array(list(BOSS.values()), float)
    P(f"  Boss 7 days: sum {bv.sum():+,.0f}, mean {bv.mean():+,.0f}, sd {bv.std(ddof=1):,.0f}, "
      f">=+4.9k {int((bv >= 4900).sum())}, <=-3k {int((bv <= -3000).sum())}, <=-5k {int((bv <= -5000).sum())}")
    rare = {}
    for pn in plans:
        for wn in ("pre", "hold"):
            x = series[(pn, wn)].values
            p5 = (x >= 4900).mean()
            pm3 = (x <= -3000).mean()
            # P(at least 2 days >= +4.9k in 7 sessions) and P(at least 3 days <= -3k in 7), binomial
            from math import comb
            p2 = 1 - sum(comb(7, j) * p5 ** j * (1 - p5) ** (7 - j) for j in range(2))
            p3 = 1 - sum(comb(7, j) * pm3 ** j * (1 - pm3) ** (7 - j) for j in range(3))
            rare[f"{pn}|{wn}"] = dict(p_day_ge_4900=p5, p_day_le_m3k=pm3, sd=float(np.std(x, ddof=1)),
                                      p_2_of_7_ge_4900=p2, p_3_of_7_le_m3k=p3,
                                      sessions_per_5k_day=(1 / p5) if p5 > 0 else None)
            P(f"  {pn} [{wn}]: P(day>=+4.9k) {100 * p5:.1f}% (one per {1 / p5 if p5 else float('inf'):.0f} sessions), "
              f"P(day<=-3k) {100 * pm3:.1f}%, day sd {np.std(x, ddof=1):,.0f}; "
              f"P(2+ of 7 days >=+4.9k) {100 * p2:.2f}%, P(3+ of 7 days <=-3k) {100 * p3:.2f}%")
    # lots needed for Rs 5,000/day (linear, NO extra impact: optimistic) and the capital that needs
    P("\nLots for Rs 5,000/day (linear scaling of 1-lot results; ignores the extra impact at size, so optimistic):")
    t02 = wide(T, 0.02)
    sizing = []
    for pn, (unds, filt) in plans.items():
        for wn, lo, hi in wins:
            r = D[(D.kappa == 0.02) & (D.window == wn) & (D.plan == pn)].iloc[0]
            w = t02[t02.und.isin(unds) & (t02.day >= lo) & (t02.day < hi) & (t02["lots_0.02"] > 0)]
            prem = w.groupby("und").prem.median().to_dict()
            set_prem = sum(prem.values())               # premium of one "set" (1 lot of each index)
            mult = 5000 / r.net_day_mean if r.net_day_mean > 0 else np.inf
            lots = int(np.ceil(mult)) if np.isfinite(mult) else None
            cap = (2 * set_prem * mult + abs(r.maxdd) * mult) if np.isfinite(mult) else np.inf  # 2 books can be open
            sizing.append(dict(plan=pn, window=wn, net_day_1set=r.net_day_mean, sets_needed=lots,
                               prem_per_set=set_prem, prem_by_index=prem,
                               capital_needed=cap, maxdd_at_size=r.maxdd * mult if np.isfinite(mult) else None,
                               worst_day_at_size=r.worst_day * mult if np.isfinite(mult) else None,
                               sets_affordable_1L=int(100_000 // (2 * set_prem + abs(r.maxdd))),
                               rs_day_at_1L_affordable=r.net_day_mean * int(100_000 // (2 * set_prem + abs(r.maxdd)))))
            P(f"  {pn} [{wn}]: {r.net_day_mean:,.0f}/day per set -> {lots} sets; premium/set {set_prem:,.0f} {prem}; "
              f"capital ~{cap / 1e5:,.1f} L (2 open positions x premium + max DD at size); DD at size "
              f"{r.maxdd * mult / 1e5:,.1f} L; worst day at size {r.worst_day * mult / 1e5:,.2f} L")
    pd.DataFrame(sizing).to_csv(os.path.join(OUT, "sizing.csv"), index=False)
    # spread stress (flat x1.5) for the same three plans, headline only
    P("\nStress: spread 1.5x (flat_x1.5), kappa .02, headline Rs/day:")
    T15 = wide(trades("flat_x1.5"), 0.02)
    for pn, (unds, filt) in plans.items():
        tt = T15[~T15.skip_bu15] if filt else T15
        for wn, lo, hi in wins:
            s, dn, n = walk_counts(tt, unds, 0.02, lo, hi)
            P(f"  {pn} [{wn}]: mean {s['net_day']:,.0f}, median {dn.median():,.0f}, maxdd {s['maxdd']:,.0f}")
    json.dump(rare, open(os.path.join(OUT, "boss_compare.json"), "w"), indent=1)
    open(os.path.join(OUT, "plan.log"), "w").write("\n".join(LINES) + "\n")


if __name__ == "__main__":
    main()
