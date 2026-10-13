"""RC2 step 3: run the pre-registered rules.
  python3 -m research.hunt.rc2.run design    # design span only: all variants, twins, BH, walk-forward, cost stress
  python3 -m research.hunt.rc2.run holdout   # ONCE: refuses to run again (results/HOLDOUT_READ)
Outputs in results/."""
import json
import os
import sys
import time

import numpy as np
import pandas as pd

from . import config as C
from . import costs, data, signals, stats
from .exits import DIRECTIONAL, HERO
from .sim import simulate

SEED = 20261013
POOL_PER_COIN = 2500


def exits_for(rule_name):
    return HERO if rule_name == "R6_hero" else DIRECTIONAL


def load():
    idx = {c: data.index5m(c) for c in C.COINS}
    dvol = {c: data.dvol1h(c) for c in C.COINS}
    fund = {c: data.funding1h(c) for c in C.COINS}
    return idx, dvol, fund


def span(phase):
    first = min(min(data.expiries_available(c)) for c in C.COINS) - pd.Timedelta(hours=30)
    if phase == "design":
        return first, C.HOLDOUT_START
    return C.HOLDOUT_START, C.HOLDOUT_END


def in_span(ent, a, b):
    return ent[(ent["T"] >= a) & (ent["T"] < b)].reset_index(drop=True)


def all_entries(idx, dvol, fund):
    out = {}
    for r in signals.RULES:
        for c in C.COINS:
            out[(r, c)] = signals.entries(r, idx[c][["o", "h", "l", "c"]], dvol[c], fund[c])
    return out


def run_variants(ents, idx, a, b, smodel, spread_mult=1.0, fee_rate=C.FEE_RATE):
    res = {}
    for r in signals.RULES:
        for ex in exits_for(r):
            parts = []
            for c in C.COINS:
                e = in_span(ents[(r, c)], a, b)
                t = simulate(c, r, e, ex, idx[c].c, smodel, spread_mult, fee_rate)
                if not t.empty:
                    parts.append(t)
            res[(r, ex.name)] = pd.concat(parts).sort_values("T").reset_index(drop=True) if parts else pd.DataFrame()
    return res


def pools(idx, a, b, smodel):
    """Random same-clock twins: for each rule and coin, POOL_PER_COIN random bars inside the rule's own entry window,
    a random side each, simulated with every exit (no overlap filter)."""
    rng = np.random.default_rng(SEED)
    out = {}
    for r, rule in signals.RULES.items():
        for c in C.COINS:
            bars = idx[c].index[(idx[c].index >= a) & (idx[c].index < b - pd.Timedelta(hours=1))]
            cand = bars[signals.in_window(rule, bars)]
            pick = cand[rng.choice(len(cand), min(POOL_PER_COIN, len(cand)), replace=False)]
            e = pd.DataFrame({"T": pick + C.BAR, "side": rng.choice(["C", "P"], len(pick))}).sort_values("T")
            for ex in exits_for(r):
                t = simulate(c, r, e, ex, idx[c].c, smodel, one_at_a_time=False)
                out[(r, ex.name, c)] = t.ret.to_numpy() if not t.empty else np.array([])
    return out


def flips(ents_sim, idx, smodel):
    """Same entry times, the other side: for the random-direction twin."""
    out = {}
    for (r, xn), t in ents_sim.items():
        if t.empty:
            out[(r, xn)] = np.array([]); continue
        ex = [x for x in exits_for(r) if x.name == xn][0]
        parts = []
        for c in C.COINS:
            g = t[t.coin == c]
            if g.empty:
                continue
            e = pd.DataFrame({"T": g["T"].to_numpy(), "side": np.where(g.side == "C", "P", "C")})
            f = simulate(c, r, e, ex, idx[c].c, smodel, one_at_a_time=False)
            m = g[["T"]].merge(f[["T", "ret"]], on="T", how="left")
            parts.append(pd.DataFrame({"T": g["T"].to_numpy(), "same": g.ret.to_numpy(), "flip": m.ret.to_numpy()}))
        out[(r, xn)] = pd.concat(parts) if parts else pd.DataFrame()
    return out


def twin_p(t, pool, r, xn, draws=2000):
    """Mean of the rule's trades vs draws of the same number of trades per coin from the same-clock random pool."""
    if t.empty:
        return np.nan
    rng = np.random.default_rng(SEED)
    act = t.ret.mean()
    n = {c: int((t.coin == c).sum()) for c in C.COINS}
    pl = {c: pool[(r, xn, c)] for c in C.COINS}
    if any(n[c] > len(pl[c]) for c in C.COINS):
        return np.nan
    hits = 0
    for _ in range(draws):
        s = np.concatenate([pl[c][rng.choice(len(pl[c]), n[c], replace=False)] for c in C.COINS if n[c]])
        hits += s.mean() >= act
    return float((hits + 1) / (draws + 1))


def walk_forward(res, start, end):
    """Per rule: each calendar quarter (from the third), use the exit with the best mean return on all EARLIER design
    trades (>= 20 of them); and across all rules, the best variant overall. Rupees at the Rs 1 lakh sizing."""
    qs = pd.date_range(start.floor("D"), end, freq="QS")
    qs = [q for q in qs if q > start][1:]
    rows = []
    money = {k: stats.money(v, 100_000) for k, v in res.items() if not v.empty}
    for scope in list(signals.RULES) + ["ALL"]:
        for i, q in enumerate(qs):
            q2 = qs[i + 1] if i + 1 < len(qs) else end
            best, bv = None, -np.inf
            for (r, xn), m in money.items():
                if scope != "ALL" and r != scope:
                    continue
                past = m[(m["T"] < q) & (m.lots > 0)]
                if len(past) >= 20 and past.ret.mean() > bv:
                    best, bv = (r, xn), past.ret.mean()
            if best is None:
                continue
            m = money[best]
            cur = m[(m["T"] >= q) & (m["T"] < q2) & (m.lots > 0)]
            rows.append(dict(scope=scope, quarter=str(q.date()), pick=f"{best[0]}/{best[1]}", past_mean_ret=bv,
                             trades=len(cur), mean_ret=cur.ret.mean() if len(cur) else np.nan, inr=cur.inr.sum()))
    return pd.DataFrame(rows)


def design():
    t0 = time.time()
    idx, dvol, fund = load()
    a, b = span("design")
    smodel = costs.spread_model()
    print("design span", a, b, "spread model", smodel, flush=True)
    ents = all_entries(idx, dvol, fund)
    res = run_variants(ents, idx, a, b, smodel)
    print("variants done", round(time.time() - t0), flush=True)
    pool = pools(idx, a, b, smodel)
    print("pools done", round(time.time() - t0), flush=True)
    fl = flips(res, idx, smodel)
    stress = run_variants(ents, idx, a, b, smodel, spread_mult=1.5, fee_rate=C.FEE_RATE_STRESS)
    print("stress done", round(time.time() - t0), flush=True)
    rows = []
    for (r, xn), t in res.items():
        row = dict(rule=r, exit=xn)
        for acc in C.ACCOUNTS:
            m = stats.money(t, acc) if not t.empty else t
            s = stats.summary(m, a, b) if not t.empty else dict(trades=0)
            row.update({f"{k}@{acc // 1000}k": v for k, v in s.items()})
        row["mean_ret"] = t.ret.mean() if not t.empty else np.nan
        row["gross_ret"] = t.gross_ret.mean() if not t.empty else np.nan
        row["n"] = len(t)
        row["p_clock"] = twin_p(t, pool, r, xn)
        f = fl[(r, xn)]
        row["p_dir"] = stats.p_direction(f["same"], f["flip"].fillna(f["same"])) if len(f) else np.nan
        st = stress[(r, xn)]
        row["stress_mean_ret"] = st.ret.mean() if not st.empty else np.nan
        row["stress_net_inr@100k"] = stats.money(st, 100_000).inr.sum() if not st.empty else np.nan
        row["skips"] = json.dumps(t.attrs.get("skips", {})) if hasattr(t, "attrs") else ""
        rows.append(row)
        if not t.empty:
            t.to_csv(os.path.join(C.TRADES, f"design_{r}_{xn}.csv"), index=False)
    df = pd.DataFrame(rows)
    df["q_clock"] = stats.bh(df.p_clock.fillna(1).to_numpy())
    df["q_dir"] = stats.bh(df.p_dir.fillna(1).to_numpy())
    wf = walk_forward(res, a, b)
    wf.to_csv(os.path.join(C.OUT, "walkforward.csv"), index=False)
    wf_rule = wf.groupby("scope").agg(quarters=("quarter", "size"), trades=("trades", "sum"), inr=("inr", "sum"))
    df["wf_inr@100k"] = df.rule.map(wf_rule.inr)
    # gates G1-G3 (PREREG.md), on design only
    df["G1"] = (df["n"] >= 60) & (df["mean_ret"] > 0) & (df["net_inr@100k"] > 0)
    df["G2"] = df.q_clock < 0.10
    df["G3"] = df["wf_inr@100k"] > 0
    df["G_stress"] = df["stress_net_inr@100k"] > 0
    df["design_pass"] = df.G1 & df.G2 & df.G3 & df.G_stress
    # the pick per rule: the exit with the best design mean return
    df["pick"] = df.groupby("rule").mean_ret.transform(lambda x: x == x.max())
    df.to_csv(os.path.join(C.OUT, "design.csv"), index=False)
    print(wf_rule.to_string(), flush=True)
    print("done", round(time.time() - t0), flush=True)


def holdout():
    flag = os.path.join(C.OUT, "HOLDOUT_READ")
    if os.path.exists(flag):
        sys.exit("The holdout was already read once (results/HOLDOUT_READ). A second look needs a new holdout.")
    if not os.path.exists(os.path.join(C.OUT, "design.csv")):
        sys.exit("Run the design phase first.")
    open(flag, "w").write(time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()) + "\n")
    idx, dvol, fund = load()
    a, b = span("holdout")
    smodel = costs.spread_model()
    ents = all_entries(idx, dvol, fund)
    res = run_variants(ents, idx, a, b, smodel)
    stress = run_variants(ents, idx, a, b, smodel, spread_mult=1.5, fee_rate=C.FEE_RATE_STRESS)
    rows = []
    for (r, xn), t in res.items():
        row = dict(rule=r, exit=xn, n=len(t), mean_ret=t.ret.mean() if not t.empty else np.nan)
        for acc in C.ACCOUNTS:
            m = stats.money(t, acc) if not t.empty else t
            s = stats.summary(m, a, b) if not t.empty else dict(trades=0)
            row.update({f"{k}@{acc // 1000}k": v for k, v in s.items()})
        st = stress[(r, xn)]
        row["stress_net_inr@100k"] = stats.money(st, 100_000).inr.sum() if not st.empty else np.nan
        if len(t) > 1:
            se = t.ret.std(ddof=1) / np.sqrt(len(t))
            row["t_stat"] = t.ret.mean() / se if se > 0 else np.nan
        rows.append(row)
        if not t.empty:
            t.to_csv(os.path.join(C.TRADES, f"holdout_{r}_{xn}.csv"), index=False)
    pd.DataFrame(rows).to_csv(os.path.join(C.OUT, "holdout.csv"), index=False)
    print("holdout written", flush=True)


if __name__ == "__main__":
    os.makedirs(C.TRADES, exist_ok=True)
    os.makedirs(C.OUT, exist_ok=True)
    {"design": design, "holdout": holdout}[sys.argv[1]]()
