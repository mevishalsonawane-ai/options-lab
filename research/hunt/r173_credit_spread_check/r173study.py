"""R173 study runner (PREREG.md sections 3 - 6).

    python research/hunt/r173_credit_spread_check/r173study.py design   # DESIGN + WALK-FORWARD -> results/design.json
    python research/hunt/r173_credit_spread_check/r173study.py final    # FINAL, read ONCE (results/FINAL_READ) -> final.json

Data: the R171 release store (`R171_DATA` / `R171_CACHE`, r171data.load()).
"""
from __future__ import annotations

import json
import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import r173real as R  # noqa: E402

RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
PERIODS = {"design": (date(2024, 11, 1), date(2025, 3, 31)),
           "walkforward": (date(2025, 4, 1), date(2025, 9, 30)),
           "final": (date(2025, 10, 1), date(2026, 2, 23))}
WF_QUARTERS = {"wf_q2_2025": (date(2025, 4, 1), date(2025, 6, 30)), "wf_q3_2025": (date(2025, 7, 1), date(2025, 9, 30))}
M_EARLIER = 3913
M_R173 = 2
M_TOTAL = M_EARLIER + M_R173
SETS = 2000
SLOTS = list(range(R.FIRST_ENTRY, R.LAST_ENTRY + 1, 5))


def period_of(d: date) -> str | None:
    for k, (a, b) in PERIODS.items():
        if a <= d <= b:
            return k
    return None


# ================================================================================================ summaries
def summ(df: pd.DataFrame, col: str = "net") -> dict:
    if df is None or len(df) == 0:
        return dict(n=0)
    daily = df.groupby("day")[col].sum().sort_index()
    eq = daily.cumsum()
    month = df.groupby(pd.to_datetime(df.day).dt.to_period("M"))[col].sum()
    return dict(n=int(len(df)), win=round(100 * float((df[col] > 0).mean()), 1), net=round(float(df[col].sum())),
                per_trade=round(float(df[col].mean())), worst_day=round(float(daily.min())),
                max_dd=round(float(min((eq - eq.cummax()).min(), 0.0))), worst_month=round(float(month.min())),
                worst_month_name=str(month.idxmin()), sessions=int(df.day.nunique()))


def block(df: pd.DataFrame) -> dict:
    out = {"pooled": summ(df)}
    if len(df):
        for u, g in df.groupby("und"):
            out[u] = summ(g)
        for g in R.REGIMES:
            out["pooled_" + g] = summ(df, "net_" + g)
    return out


def describe(df: pd.DataFrame, bk: dict) -> dict:
    if len(df) == 0:
        return {}
    out = dict(exit_mix={k: int(v) for k, v in df.why.value_counts().items()},
               stale_exit=int(df.stale_exit.sum()))
    for u, g in df.groupby("und"):
        credit = g.s_in - g.w_in
        deltas = [R.bs_delta(r.s_in, r.spot, r.ks, r.right, max(r.dte, 0) / 365.0 + (6.25 - r.fill / 60.0) / (24 * 365))
                  for r in g.itertuples()]
        deltas = np.array([x for x in deltas if math.isfinite(x)])
        out[u] = dict(n=int(len(g)), dte_median=float(g.dte.median()), dte_q10_q90=[float(g.dte.quantile(.1)), float(g.dte.quantile(.9))],
                      short_premium_median=round(float(g.s_in.median()), 1), credit_median=round(float(credit.median()), 1),
                      credit_rs_median=round(float((credit * g.lot).median())),
                      short_delta_median=round(float(np.median(np.abs(deltas))), 3) if len(deltas) else None,
                      margin_median=round(float(g.margin.median())), margin_r103_median=round(float(g.margin_r103.median())),
                      max_loss_rs_median=round(float(((g.ks - g.kw).abs() - credit).clip(lower=0).mul(g.lot).median())),
                      raw_gross_rs=round(float((g.gross_pts * g.lot).sum())), index_pts_mean=round(float(g.idx_pts.mean()), 1),
                      index_win=round(100 * float((g.idx_pts > 0).mean()), 1))
    return out


# ================================================================================================ runs
def strat_trades(bk: dict, start: date, end: date):
    sigs = R.r72_signals(bk)
    e72 = R.r72_entries(bk, sigs, start, end)
    t72, sk72 = R.walk_r72(e72, bk)
    eliq = R.liq_entries(bk, start, end)
    tliq, skliq = R.walk_liq(eliq, bk)
    return {"R72-CS": (t72, sk72, e72), "LIQ2-CS": (tliq, skliq, eliq)}


def run_period(bk: dict, name: str, a: date, b: date) -> dict:
    out = {}
    for strat, (trades, skipped, ents) in strat_trades(bk, a, b).items():
        df = R.to_frame(trades)
        acct, miss = R.account(trades)
        acct_r103, miss_r103 = R.account(trades, margin_key="margin_r103")
        dfa = R.to_frame(acct)
        out[strat] = dict(signals=len(ents), skipped=skipped, all=block(df), account=block(dfa),
                          not_taken_margin=miss, not_taken_margin_r103=miss_r103, describe=describe(df, bk),
                          max_concurrent=_max_conc(trades))
        _save(df, f"{strat}_{name}_all")
        _save(dfa, f"{strat}_{name}_account")
    return out


def _max_conc(trades: list) -> int:
    best = 0
    by: dict = {}
    for t in trades:
        by.setdefault(t.day, []).append(t)
    for ts in by.values():
        for t in ts:
            best = max(best, sum(1 for o in ts if o.fill <= t.fill < o.xfill))
    return best


def _save(df: pd.DataFrame, name: str) -> None:
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "trades").mkdir(exist_ok=True)
    if len(df):
        df.to_csv(RESULTS / "trades" / f"{name}.csv.gz", index=False)


# ================================================================================================ arms (bought options)
def arms(store: dict, keep: tuple) -> dict:
    """The arms' current bought-option rules as R171 rebuilt them on this release (R72: 1-ITM XL; Liquidity 15+5: ATM),
    re-costed with R17 fills + dated charges."""
    import r171arms as A
    out = {}
    t72 = A.r72_trades(store)
    tlq = A.liq_trades(store)
    for name, ts in (("R72-arm (1-ITM, XL)", t72), ("LIQ-arm 15+5 (ATM)", tlq)):
        rows = []
        for t in ts:
            d = next(x for x in store[t.und] if x.day == t.day)
            arr = d.opts.get((t.strike, t.right))
            lot = t.lot
            vin = R.vol5_lots(arr, t.fill_row, lot) if arr is not None else None
            vout = R.vol5_lots(arr, min(t.exit_row, R.N - 1), lot) if arr is not None else None
            buy = float(R.FILLS.buy(t.entry_raw, vin))
            sell = float(R.FILLS.sell(t.exit_raw, vout, stop=t.reason in ("stop", "lock")))
            dn = R.dnum(t.day)
            net = (sell - buy) * lot - R.charges(False, buy, lot, dn) - R.charges(True, sell, lot, dn)
            rows.append(dict(und=t.und, day=t.day, net=net, net_quoted=t.net["quoted"], strat=t.strat))
        df = pd.DataFrame(rows)
        df = df[df.day.map(period_of).isin(keep)]          # never summarise or save a period not being read now
        res = {}
        for p in keep:
            a, b = PERIODS[p]
            g = df[(df.day >= a) & (df.day <= b)]
            res[p] = dict(r17=summ(g), quoted=summ(g, "net_quoted"),
                          by_und={u: summ(x) for u, x in g.groupby("und")})
        out[name] = res
        _save(df, name.split()[0] + "_arm_" + "_".join(keep))
    return out


# ================================================================================================ random twins
def twin_values(t, ent: dict, bk: dict) -> list:
    """Net of the same structure entered at every 5-minute slot 09:20 - 14:30 the same day, index and side, with the
    trade's exits (R72: +3.2 / -4.5 x from the twin's entry; LIQ: the trade's target / stop distances from its entry and
    the day's new-level bars)."""
    B = bk[t.und]
    I = B.I[ent["k"]]
    vals = []
    for e in SLOTS:
        ix = float(I["open"][e])
        tw = dict(ent)
        tw["e"] = e
        tw["ix0"] = ix
        tw["target"] = ix + (ent["target"] - ent["ix0"])
        tw["stop"] = ix + (ent["stop"] - ent["ix0"])
        r = R.simulate(tw, B, "twin")
        if not isinstance(r, str):
            vals.append(r.net)
    return vals


def random_test(trades_acct: list, ents: list, bk: dict, rng) -> dict:
    by_key = {(e["und"], e["day"], e["side"], e["e"]): e for e in ents}
    pools = []
    for t in trades_acct:
        ent = by_key[(t.und, t.day, t.side, t.e)]
        v = twin_values(t, ent, bk)
        pools.append(np.array(v) if v else np.array([t.net]))
    actual = float(sum(t.net for t in trades_acct))
    tot = np.zeros(SETS)
    for p in pools:
        tot += p[rng.integers(0, len(p), SETS)]
    mu = float(sum(p.mean() for p in pools))
    sd = float(math.sqrt(sum(p.var() for p in pools)))
    from scipy.stats import norm
    p_norm = float(1 - norm.cdf((actual - mu) / sd)) if sd > 0 else (0.0 if actual > mu else 1.0)
    p_mc = float(((tot >= actual).sum() + 1) / (SETS + 1))
    return dict(actual=round(actual), random_mean=round(mu), random_sd=round(sd), p_normal=p_norm, p_mc=p_mc,
                random_share_positive=round(float((tot > 0).mean()), 3), twins_per_trade=float(np.mean([len(p) for p in pools])))


def bh_q(p: float, rank: int = 1, m: int = M_TOTAL) -> float:
    return min(1.0, p * m / rank)


# ================================================================================================ commands
def design() -> dict:
    import r171data as D
    store = D.load()
    bk = R.books(store)
    out = {name: run_period(bk, name, a, b) for name, (a, b) in (("design", PERIODS["design"]),
                                                                 ("walkforward", PERIODS["walkforward"]))}
    for q, (a, b) in WF_QUARTERS.items():
        out[q] = {s: dict(account=v["account"]["pooled"], all=v["all"]["pooled"])
                  for s, v in run_period(bk, q, a, b).items()}
    out["arms"] = arms(store, ("design", "walkforward"))
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def final() -> dict:
    if FINAL_FLAG.exists():
        raise SystemExit("FINAL already read (results/FINAL_READ). It is read once.")
    import r171data as D
    RESULTS.mkdir(exist_ok=True)
    FINAL_FLAG.write_text(f"read {date.today().isoformat()}\n")
    store = D.load()
    bk = R.books(store)
    a, b = PERIODS["final"]
    res = run_period(bk, "final", a, b)
    trades = strat_trades(bk, a, b)
    rng = np.random.default_rng(1731)
    des = json.loads((RESULTS / "design.json").read_text())
    gates = {}
    ps = {}
    for strat, (ts, _, ents) in trades.items():
        acct, _ = R.account(ts)
        rt = random_test(acct, ents, bk, rng)
        ps[strat] = rt["p_normal"]
        res[strat]["random"] = rt
    ranked = sorted(ps.items(), key=lambda kv: kv[1])
    for rank, (strat, p) in enumerate(ranked, 1):
        q = min(1.0, min(pp * M_TOTAL / r for r, (_, pp) in enumerate(ranked, 1) if r >= rank))
        res[strat]["random"]["q"] = q
        fa = res[strat]["account"]["pooled"]
        da = des["design"][strat]["account"]["pooled"]
        wa = des["walkforward"][strat]["account"]["pooled"]
        # account limits over all three periods
        frames = [pd.read_csv(RESULTS / "trades" / f"{strat}_{p}_account.csv.gz", parse_dates=["day"])
                  for p in ("design", "walkforward", "final") if (RESULTS / "trades" / f"{strat}_{p}_account.csv.gz").exists()]
        allp = pd.concat(frames) if frames else pd.DataFrame()
        if len(allp):
            allp["day"] = allp.day.dt.date
        whole = summ(allp) if len(allp) else dict(n=0)
        quoted_ok = all(x.get("net", 0) > 0 for x in (
            res[strat]["account"].get("pooled_quoted", {}), des["design"][strat]["account"].get("pooled_quoted", {}),
            des["walkforward"][strat]["account"].get("pooled_quoted", {})))
        g = {"1 net > 0 design / WF / final": all(x.get("net", -1) > 0 for x in (da, wa, fa)),
             "2 beats random entries (Monte-Carlo p <= 0.01)": res[strat]["random"]["p_mc"] <= 0.01,
             "3 BH q <= 0.10 (m = %d)" % M_TOTAL: q <= 0.10,
             "4 >= 40 final trades": fa.get("n", 0) >= 40,
             "5 max DD > -40k and no month < -13k (all periods)": whole.get("max_dd", -1e9) > -40_000 and whole.get("worst_month", -1e9) > -13_000,
             "6 sign holds under quoted spread": quoted_ok}
        gates[strat] = dict(gates=g, passed=all(g.values()), whole=whole)
    res["gates"] = gates
    res["arms_final"] = {k: r["final"] for k, r in arms(store, ("final",)).items()}
    (RESULTS / "final.json").write_text(json.dumps(res, indent=1, default=str))
    return res


if __name__ == "__main__":
    cmd = (sys.argv[1:] or [""])[0]
    if cmd == "design":
        print(json.dumps(design(), indent=1, default=str)[:4000])
    elif cmd == "final":
        print(json.dumps(final(), indent=1, default=str)[:4000])
    else:
        raise SystemExit(__doc__)
