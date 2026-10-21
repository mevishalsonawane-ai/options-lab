"""R99 study (PREREG.md). Commands:

    python research/hunt/r99_nifty100/r99study.py design     # R99A-C on the design window
    python research/hunt/r99_nifty100/r99study.py final      # the ONE final read, only for rules passing every gate

Results: results/design.json (and final.json). Prices: R51's engine unchanged (r99dataset.label for R99A; engine
run_day for the re-priced Solo trades).
"""
from __future__ import annotations

import json
import sys

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

import r99cfg as K
import r51cfg as K51
import engine as E51
import r99data as DA
from r99scan import feature_cols
from research.hunt.r96_mcx_buy.study import bh

TWINS, BOOT = 500, 2000
EDGE_RS = 300.0
PARAMS = dict(learning_rate=0.05, max_iter=300, max_leaf_nodes=15, min_samples_leaf=300, l2_regularization=1.0,
              random_state=K.SEED)
QUARTERS = tuple(K51.WF_FOLDS)
STOP_PTS, TGT_PTS = 40.0, 100.0
RUN_TRIG, RUN_KEEP, RUN_TRAIL = 50.0, 25.0, 40.0
SOLO_STOP = {"S1": 0.30, "S2": 0.15, "S3": 0.30, "S4": 0.25}


# ------------------------------------------------------------------------------------------------ pricing
def price_und(und: str, D, side, strike, er, ex, slip=None):
    old = E51.SLIP
    try:
        if slip is not None:
            E51.SLIP = slip
        return E51.run_day(und, D, [side], [strike], [er], ex)[0]
    finally:
        E51.SLIP = old


def first_open(D, side, strike, er):
    C = D.contract(side, strike)
    if C is None:
        return None
    op = C[0, er:er + 3]
    ok = np.flatnonzero(np.isfinite(op) & (op > 0))
    return float(op[ok[0]]) if len(ok) else None


def catch100_exits(E0: float) -> E51.Exits:
    return E51.Exits(stop=STOP_PTS / E0, target=TGT_PTS / E0)


def runner_exits(setup: str, qty: float) -> E51.Exits:
    return E51.Exits(stop=SOLO_STOP[setup], lock_trig=RUN_TRIG * qty, lock_keep=RUN_KEEP * qty,
                     lock_trail=RUN_TRAIL * qty)


# ------------------------------------------------------------------------------------------------ statistics
def summary(tr: list) -> dict:
    if not tr:
        return dict(trades=0, net=0.0)
    df = pd.DataFrame(tr)
    daily = df.groupby("day").net.sum().sort_index()
    cum = daily.cumsum()
    month = df.groupby(pd.to_datetime(df.day).dt.to_period("M")).net.sum()
    return dict(trades=int(len(df)), net=float(df.net.sum()), per_trade=float(df.net.mean()),
                win=float((df.net > 0).mean()), worst_month=float(month.min()),
                max_dd=float((cum - cum.cummax()).min()), why={k: int(v) for k, v in df.why.value_counts().items()},
                quarters=[float(df[(df.day >= pd.Timestamp(a)) & (df.day <= pd.Timestamp(b))].net.sum())
                          for a, b in QUARTERS],
                by_month={str(k): float(v) for k, v in month.items()})


def boot_diff(diff_by_day: pd.Series, n=BOOT, seed=K.SEED) -> float:
    """One-sided day-block bootstrap p of mean(diff) <= 0 (resampling sessions)."""
    x = diff_by_day.values
    rng = np.random.default_rng(seed)
    m = np.array([x[rng.integers(0, len(x), len(x))].sum() for _ in range(n)])
    return float((1 + (m <= 0).sum()) / (n + 1))


# ------------------------------------------------------------------------------------------------ R99A
def r99a(df: pd.DataFrame, days: dict, design=True, model_train=None):
    cols = sorted(set(feature_cols(df)) | {"side", "tod"})
    df = df.copy()
    df["pred"] = np.nan
    if design:
        for a, b in QUARTERS:
            tr = df[(df.day < pd.Timestamp(a) - pd.Timedelta(days=1))].dropna(subset=["net_100"])
            te = (df.day >= pd.Timestamp(a)) & (df.day <= pd.Timestamp(b))
            m = HistGradientBoostingRegressor(**PARAMS).fit(tr[cols].astype(float), tr.net_100.astype(float))
            df.loc[te, "pred"] = m.predict(df.loc[te, cols].astype(float))
    else:
        tr = model_train.dropna(subset=["net_100"])
        m = HistGradientBoostingRegressor(**PARAMS).fit(tr[cols].astype(float), tr.net_100.astype(float))
        df["pred"] = m.predict(df[cols].astype(float))
    sig = df[df.pred >= EDGE_RS].sort_values(["day", "row", "pred"], ascending=[True, True, False])
    trades = []
    for d, g in sig.groupby("day"):
        r = g.iloc[0]
        D = days[pd.Timestamp(d).date()]
        side = "C" if r.side > 0 else "P"
        k = E51.strike_for(D, K.UND, side, int(r.row))
        E0 = first_open(D, side, k, int(r.row) + 1) if k is not None else None
        if E0 is None:
            continue
        t = price_und(K.UND, D, side, k, int(r.row) + 1, catch100_exits(E0))
        t2 = price_und(K.UND, D, side, k, int(r.row) + 1, catch100_exits(E0), E51.SLIP2)
        if t is None:
            continue
        t.update(day=pd.Timestamp(d), net2=t2["net"] if t2 else t["net"], row=int(r.row))
        trades.append(t)
    return trades


def r99a_twins(trades: list, days: dict, n=TWINS, seed=K.SEED) -> np.ndarray:
    rng = np.random.default_rng(seed)
    grid = np.arange(K.FIRST_ROW, K.LAST_ROW + 1, K.SAMPLE_EVERY)
    tot = np.zeros(n)
    cache = {}
    for t in trades:
        D = days[t["day"].date()]
        for s in range(n):
            r = int(rng.choice(grid))
            key = (t["day"], t["side"], r)
            if key not in cache:
                k = E51.strike_for(D, K.UND, t["side"], r)
                E0 = first_open(D, t["side"], k, r + 1) if k is not None else None
                x = price_und(K.UND, D, t["side"], k, r + 1, catch100_exits(E0)) if E0 else None
                cache[key] = x["net"] if x else 0.0
            tot[s] += cache[key]
    return tot


# ------------------------------------------------------------------------------------------------ R99B / C
def solo_trades(final=False):
    """R57 A2's selected trades (its walk-forward, unchanged)."""
    sys.path.insert(0, str(K.HERE.parent / "r57"))
    import r57study as RS
    import r57data
    import r57setups as S57
    for u in RS.K.UNDS:
        r57data.load(u)
    cal = RS.calendar()
    a0, _ = RS.load_or_build("design_data", RS.K.START, RS.K.DESIGN_END)
    if not final:
        tr, info, _ = RS.run_a(RS.VARIANTS["A2"], a0, RS.K.WF_MONTHS, [d for d in cal if d <= RS.K.DESIGN_END])
        return tr, r57data
    S57.open_final()
    a1, _ = RS.load_or_build("final_data", RS.K.FINAL_START, RS.K.END)
    tr, info, _ = RS.run_a(RS.VARIANTS["A2"], a0 + a1, RS.K.FINAL_MONTHS, cal)
    return tr, r57data


def reprice(solo: list, r57data, variant: str) -> list:
    out = []
    for t in solo:
        D = r57data.load(t["und"])[t["day"]]
        qty = float(K51.lot(t["und"], t["day"]))
        if variant == "R99B":
            E0 = first_open(D, t["side"], t["strike"], t["er"])
            ex = catch100_exits(E0) if E0 else None
        else:
            ex = runner_exits(t["setup"], qty)
        x = price_und(t["und"], D, t["side"], t["strike"], t["er"], ex) if ex else None
        x2 = price_und(t["und"], D, t["side"], t["strike"], t["er"], ex, E51.SLIP2) if ex else None
        net = x["net"] if x else 0.0
        out.append(dict(und=t["und"], day=pd.Timestamp(t["day"]), side=t["side"], setup=t["setup"], net=net,
                        net2=x2["net"] if x2 else 0.0, why=x["why"] if x else "nofill", solo_net=float(t["net"])))
    return out


def exit_variant(tr: list) -> dict:
    df = pd.DataFrame(tr)
    s = summary(tr)
    diff = (df.net - df.solo_net).groupby(df.day).sum()
    s.update(solo_net=float(df.solo_net.sum()), diff=float(diff.sum()), p=boot_diff(diff),
             net2=float(df.net2.sum()),
             diff_quarters=[float((df.net - df.solo_net)[(df.day >= pd.Timestamp(a)) & (df.day <= pd.Timestamp(b))].sum())
                            for a, b in QUARTERS])
    return s


# ------------------------------------------------------------------------------------------------ commands
def design():
    days = DA.days()
    df = pd.read_parquet(K.CACHE / "dataset_design.parquet")
    res = {}
    ta = r99a(df, days)
    sa = summary(ta)
    tw = r99a_twins(ta, days) if ta else np.zeros(TWINS)
    sa.update(net2=float(sum(t["net2"] for t in ta)), twin_mean=float(tw.mean()),
              p=float((1 + (tw >= sa["net"]).sum()) / (TWINS + 1)))
    res["R99A"] = sa
    solo, r57data = solo_trades()
    res["solo_reference"] = summary([dict(day=pd.Timestamp(t["day"]), net=t["net"], why=t["why"]) for t in solo])
    for v in ("R99B", "R99C"):
        res[v] = exit_variant(reprice(solo, r57data, v))
    qs = bh([res[k]["p"] for k in ("R99A", "R99B", "R99C")])
    for k, q in zip(("R99A", "R99B", "R99C"), qs):
        r = res[k]
        r["q"] = q
        if k == "R99A":
            g = dict(trades=r["trades"] >= 40, net=r["net"] > 0, net2=r["net2"] > 0, twin_p=r["p"] < 0.05,
                     bh_q=q <= 0.10, quarters=sum(x > 0 for x in r["quarters"]) >= 3)
        else:
            g = dict(net=r["net"] > 0, beats_solo=r["diff"] > 0, boot_p=r["p"] < 0.05, bh_q=q <= 0.10,
                     quarters=sum(x > 0 for x in r["diff_quarters"]) >= 3, net2=r["net2"] > 0)
        g.update(worst_month=r.get("worst_month", -1e9) > -13000, max_dd=r.get("max_dd", -1e9) > -40000)
        r["gates"] = g
        r["pass"] = bool(all(g.values()))
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(res, indent=1, default=str))
    for k in ("R99A", "R99B", "R99C"):
        r = res[k]
        print(k, r["trades"], round(r["net"]), "p", round(r["p"], 3), "q", round(r["q"], 3),
              "diff" if "diff" in r else "", round(r.get("diff", 0)), [g for g, ok in r["gates"].items() if not ok])
    print("solo ref", res["solo_reference"]["trades"], round(res["solo_reference"]["net"]))
    return res


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "final":
        raise SystemExit("final: written only if a rule passes every design gate (see results/design.json)")
    else:
        raise SystemExit(__doc__)
