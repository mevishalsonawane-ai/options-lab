"""F1C runner (PREREG sections 4-8).

    python -m research.hunt.f1c.run design     # every rule x exit x coin on design data: base, stress, twins
    python -m research.hunt.f1c.run gates      # walk-forward by quarter, twin p, BH, gates -> results/design.json
    python -m research.hunt.f1c.run holdout    # ONE read of the holdout (refuses a second), -> results/holdout.json

Trade lists go to <cache>/f1c/trades (never git); results/ holds statistics only.
"""
from __future__ import annotations

import json
import sys
import zlib
from concurrent.futures import ProcessPoolExecutor

import numpy as np
import pandas as pd

from . import book, data, engine
from . import f1ccfg as C
from .rules import RULES
from .stats import bh

TR = C.OUT / "trades"
TWIN_N = 1500


def _coin_job(args):
    sym, part = args
    F = engine.frame(sym, part)
    mk = engine.Market(sym, part)
    out = []
    for rid, rule in RULES.items():
        if sym not in rule.coins:
            continue
        dec = rule.signal(F, mk)
        dec = dec[engine.in_window(dec.ts)].reset_index(drop=True) if len(dec) else dec
        for ex in rule.exits:
            for tag, stress in (("base", 1.0), ("stress", C.STRESS_SLIP)):
                B = engine.trades(rule, sym, mk, F, ex, stress=stress, part=part, dec=dec)
                if len(B):
                    B["rule"], B["exit_v"], B["cost"] = rid, ex.name, tag
                    out.append(B)
            if part == "design":
                pool = engine.twin_pool(rule, sym, mk, F, ex, dec, TWIN_N, seed=zlib.crc32(f"{rid}{ex.name}{sym}".encode()))
                tw = pd.DataFrame({"net_a": pool})
                tw["rule"], tw["exit_v"], tw["coin"] = rid, ex.name, sym
                p = TR / part / "twins" / f"{rid}_{ex.name}_{sym}.parquet"
                p.parent.mkdir(parents=True, exist_ok=True)
                tw.to_parquet(p, index=False)
        print(f"{part} {sym} {rid} done", flush=True)
    T = pd.concat(out, ignore_index=True) if out else pd.DataFrame()
    p = TR / part / f"trades_{sym}.parquet"
    p.parent.mkdir(parents=True, exist_ok=True)
    T.to_parquet(p, index=False)
    return sym, len(T), engine.design_days(F)


def simulate(part: str):
    jobs = [(s, part) for s in C.COINS]
    with ProcessPoolExecutor(4) as ex:
        res = list(ex.map(_coin_job, jobs))
    days = {s: d for s, _, d in res}
    (TR / part / "days.json").write_text(json.dumps(days))
    print(res)


def load(part: str) -> tuple[pd.DataFrame, dict]:
    T = pd.concat([pd.read_parquet(p) for p in sorted((TR / part).glob("trades_*.parquet"))], ignore_index=True)
    days = json.loads((TR / part / "days.json").read_text())
    return T, days


def twin_p_strat(rule_trades: pd.DataFrame, pools: dict, sims: int = 4000, seed: int = 0) -> float:
    """One-sided p: random twin sets with the rule's own number of trades per coin, drawn from each coin's pool."""
    rng = np.random.default_rng(seed)
    n_c = rule_trades.groupby("coin").size()
    if n_c.sum() == 0:
        return np.nan
    tot = np.zeros(sims)
    for coin, n in n_c.items():
        pool = pools.get(coin, np.array([]))
        pool = pool[np.isfinite(pool)]
        if len(pool) < 20:
            return np.nan
        tot += pool[rng.integers(0, len(pool), (sims, n))].sum(axis=1)
    means = tot / n_c.sum()
    m = rule_trades.net_a.mean()
    return float((1 + (means >= m).sum()) / (sims + 1))


def span_days(days: dict, coins) -> int:
    return max(days.get(c, 0) for c in coins)


def gates():
    T, days = load("design")
    base, stress = T[T.cost == "base"], T[T.cost == "stress"]
    rows = []
    for rid, rule in RULES.items():
        nd = span_days(days, rule.coins)
        for ex in rule.exits:
            B = base[(base.rule == rid) & (base.exit_v == ex.name)]
            S = stress[(stress.rule == rid) & (stress.exit_v == ex.name)]
            pools = {}
            for c in rule.coins:
                p = TR / "design" / "twins" / f"{rid}_{ex.name}_{c}.parquet"
                if p.exists():
                    pools[c] = pd.read_parquet(p).net_a.to_numpy()
            s = book.summary(B, nd)
            s.update(rule=rid, exit_v=ex.name, venue=rule.venue, stress_net_a=float(S.net_a.sum()) if len(S) else 0.0,
                     twin_p=twin_p_strat(B, pools) if len(B) else np.nan,
                     twin_mean=float(np.mean(np.concatenate(list(pools.values())))) if pools else np.nan,
                     by_coin={c: dict(trades=int((B.coin == c).sum()), net_a=float(B.net_a[B.coin == c].sum()))
                              for c in rule.coins},
                     by_quarter={q: float(v) for q, v in B.groupby(C.quarter(B.t_in)).net_a.sum().items()} if len(B) else {})
            rows.append(s)
    R = pd.DataFrame(rows)
    R["twin_q"] = bh(R.twin_p.to_numpy())
    picks = {}
    for rid, rule in RULES.items():
        r = R[R.rule == rid].copy()
        r = r.sort_values(["net_a", "trades"], ascending=[False, True])
        pick = r.iloc[0]
        # walk-forward: each quarter from 2024Q1 uses the variant with the best net on all earlier design quarters
        wf, chosen = {}, {}
        for i, q in enumerate(C.QUARTERS[1:], start=1):
            prev = C.QUARTERS[:i]
            score = {v.exit_v: sum(v.by_quarter.get(p, 0.0) for p in prev) for v in r.itertuples()}
            best = max(sorted(score), key=lambda k: score[k])
            chosen[q] = best
            wf[q] = float(r[r.exit_v == best].iloc[0].by_quarter.get(q, 0.0))
        wf_sum = sum(wf.values())
        green = sum(v > 0 for v in wf.values()) / len(wf)
        g = dict(G1=bool(pick.net_a > 0 and pick.trades >= 100), G2=bool(wf_sum > 0 and green >= 0.5),
                 G3=bool(pick.twin_p < 0.05 and pick.twin_q < 0.10), G4=bool(pick.stress_net_a > 0))
        picks[rid] = dict(pick=pick.exit_v, wf=wf, wf_chosen=chosen, wf_sum=wf_sum, wf_green=green, gates=g,
                          design_pass=all(g.values()))
    C.RESULTS.mkdir(parents=True, exist_ok=True)
    out = dict(variants=json.loads(R.to_json(orient="records")), picks=picks, days=days,
               baselines=baselines("design"))
    (C.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps({k: (v["pick"], round(v["wf_sum"]), v["gates"]) for k, v in picks.items()}, indent=0))
    return out


def baselines(part: str) -> dict:
    """Buy-and-hold (Rs 1 lakh at the first design minute of each span, sold at its end) and weekly DCA (equal weekly
    buys, all sold at the span's end) per coin, after tax reading A (31.2% of the gain) and with the TDS on the sale."""
    out = {}
    spans = C.DESIGN if part == "design" else (C.HOLDOUT,)
    for sym in C.COINS:
        res = []
        for a, b in spans:
            M = data.bars(sym, a.date(), (b - pd.Timedelta(days=1)).date())
            if M.empty:
                continue
            px = M.set_index("ts").close.dropna()
            p0, p1 = px.iloc[0], px.iloc[-1]
            hold = C.ACCOUNT * (p1 / p0 - 1)
            weekly = px.resample("7D").first().dropna()
            coins = (C.ACCOUNT / len(weekly)) / weekly
            dca = float((coins * p1).sum() - C.ACCOUNT)
            days = (px.index[-1] - px.index[0]).days + 1
            res.append(dict(span=f"{a.date()}..{b.date()}", days=days, hold_pre_tax=hold,
                            hold_after_tax=hold - C.TAX * max(hold, 0), dca_pre_tax=dca,
                            dca_after_tax=dca - C.TAX * max(dca, 0), tds_on_sale=C.TDS * (C.ACCOUNT + hold)))
        out[sym] = res
    return out


def holdout():
    data.read_holdout("f1c-rules-P01-O20")           # raises on a second read
    simulate("holdout")
    T, days = load("holdout")
    D = json.loads((C.RESULTS / "design.json").read_text())
    base, stress = T[T.cost == "base"], T[T.cost == "stress"]
    res = {}
    for rid, rule in RULES.items():
        nd = span_days(days, rule.coins)
        var = {}
        for ex in rule.exits:
            B = base[(base.rule == rid) & (base.exit_v == ex.name)]
            S = stress[(stress.rule == rid) & (stress.exit_v == ex.name)]
            s = book.summary(B, nd)
            s["stress_net_a"] = float(S.net_a.sum()) if len(S) else 0.0
            s["by_coin"] = {c: dict(trades=int((B.coin == c).sum()), net_a=float(B.net_a[B.coin == c].sum()))
                            for c in rule.coins}
            var[ex.name] = s
        pick = D["picks"][rid]["pick"]
        res[rid] = dict(pick=pick, pick_result=var[pick], variants=var,
                        G5=bool(var[pick].get("net_a", 0) > 0 and var[pick].get("trades", 0) > 0),
                        passes=bool(D["picks"][rid]["design_pass"] and var[pick].get("net_a", 0) > 0))
    out = dict(rules=res, days=days, baselines=baselines("holdout"))
    (C.RESULTS / "holdout.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps({k: (v["pick"], round(v["pick_result"].get("net_a", 0)), v["passes"]) for k, v in res.items()},
                     indent=0))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        simulate("design")
    elif cmd == "gates":
        gates()
    elif cmd == "holdout":
        holdout()
    else:
        print(__doc__)
