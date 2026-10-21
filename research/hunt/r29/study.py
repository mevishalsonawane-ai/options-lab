"""R29 Steps 3-4 (PREREG.md): the Part B rules + baseline B0, design / walk-forward / the ONE holdout read.

    python3 research/hunt/r29/study.py design      # discovery + selection (allowed any time)
    python3 research/hunt/r29/study.py wf          # 2025 and 2026 (only after PREREG.md Part B is committed)
    python3 research/hunt/r29/study.py holdout     # 2023, ONCE: writes results/holdout.json and the HOLDOUT_READ flag

Twins: for every (index, day, direction) the ATM X1 net of an entry at EVERY signal minute 09:30-14:45 is simulated once
(the day table); each of the 1,000 twin sets draws one random minute per rule trade from it (seed 29). p = share of twin
sets whose mean net per trade >= the rule's.
"""
from __future__ import annotations

import json
import sys

import numpy as np
import pandas as pd

import r29cfg as K
import r29data as D
import engine as EN
import mine as MI
import patterns as PT
import rules as RU
import sim as S

WIN = np.arange(K.SIG_FIRST, K.SIG_LAST + 1)


# ------------------------------------------------------------------------------------------------ day table
def day_table(und, days, M, op, opt="ATM", exit_name="X1", spread_mult=1.0):
    """{(day, dirn): (len(WIN),) net of an entry at each signal row} for the given days (NaN = no fill)."""
    paths = EN.Paths(op)
    bse = und in K.BSE
    out = {}
    for d in days:
        d_ord = d.toordinal()
        if d_ord not in op.atm:
            continue
        qty = K.lot(und, d)
        for dirn in (1, -1):
            ent, ex = np.full(len(WIN), np.nan), np.full(len(WIN), np.nan)
            for i, t in enumerate(WIN):
                r = EN.trade_one(paths, op, und, d_ord, int(t), dirn, opt, (exit_name,), qty, spread_mult)
                if r:
                    ent[i], ex[i] = r[exit_name][0], r[exit_name][1]
            out[(d, dirn)] = S.net(ent, ex, np.full(len(WIN), qty), bse)
    return out


def twin_p(G: pd.DataFrame, table: dict, n_sets=K.TWINS, seed=K.SEED):
    """(p, twin mean of set means, twin set means). Each rule trade draws one random signal minute per set."""
    if not len(G):
        return np.nan, np.nan, np.array([])
    rng = np.random.default_rng(seed)
    T = np.vstack([table[(d, int(s))] for d, s in zip(G.day, G.dirn)])          # (n, len(WIN))
    idx = rng.integers(0, len(WIN), size=(n_sets, len(G)))
    draws = T[np.arange(len(G))[None, :], idx]                                    # (sets, n)
    means = np.nanmean(draws, axis=1)
    actual = G.net.mean()
    return float((means >= actual).mean()), float(np.nanmean(means)), means


# ------------------------------------------------------------------------------------------------ metrics
def metrics(G: pd.DataFrame, n_days: int) -> dict:
    """Trades/day, win rate, avg win/loss, P&L/day, % green days, worst day, max DD, big winners cut short."""
    if not len(G):
        return dict(trades=0, days=n_days)
    net = G.net
    daily = G.groupby("day").net.sum()
    eq = daily.sort_index().cumsum()
    dd = float((eq - eq.cummax().clip(lower=0)).min())
    cut = G.post >= G.exit_px + 0.5 * G.entry                 # any closed trade (PREREG), stops included (erratum 1)
    return dict(trades=len(G), days=n_days, trade_days=int(daily.size), per_day=len(G) / max(n_days, 1),
                win=float((net > 0).mean()), avg_win=float(net[net > 0].mean()) if (net > 0).any() else np.nan,
                avg_loss=float(net[net <= 0].mean()) if (net <= 0).any() else np.nan,
                net=float(net.sum()), per_trade=float(net.mean()), pnl_day=float(net.sum() / max(n_days, 1)),
                green_days=float((daily > 0).mean()), worst_day=float(daily.min()), max_dd=dd,
                cut_short=int(cut.sum()), cut_share=float(cut.mean()), missing=int((G.why == S.R_MISSING).sum()))


def run_rule(rule, und, part, M, op, E_all, B_all, cache):
    """Gated trades of one rule on one index and part, with every exit / option and the 2x-spread stress."""
    E = RU.rule_events(rule, und, M, E_all, B_all, cache)
    E = EN.events_for(und, part, E)
    if not len(E):
        return None
    T = EN.simulate_events(und, E, M, op, modes=(rule["mode"],))
    if not len(T):
        return None
    T["pattern"] = rule["id"]
    opt, x = rule["opt"], rule["exit"]
    T["ATM_X1_row"] = T[f"{opt}_{x}_row"]
    T["ATM_X1_entry"] = T[f"{opt}_{x}_entry"]
    # one trade at a time per rule and index, whatever the sign (B0 trades both ways; erratum 1, 14 Oct 2026)
    G = EN.gate(T, keys=("und", "pattern", "mode"))
    G["net"], G["entry"], G["exit_px"] = G[f"{opt}_{x}_net"], G[f"{opt}_{x}_entry"], G[f"{opt}_{x}_exit"]
    G["post"], G["why"] = G[f"{opt}_{x}_post"], G[f"{opt}_{x}_why"]
    S2 = EN.simulate_events(und, E, M, op, modes=(rule["mode"],), opts=(opt,), exits=(x,), spread_mult=2.0)
    s2 = S2.set_index(["day", "m"])[f"{opt}_{x}_net"]
    G["net_2x"] = s2.reindex(pd.MultiIndex.from_arrays([G.day, G.m])).values
    return G


def evaluate(parts, tag, rules=None, trades_file=None):
    out = {}
    all_trades = []
    rules = rules or RU.RULES + [RU.B0]
    for und in K.UNDS:
        op = D.load_options(und)
        if op is None:
            continue
        M = D.load_index(und)
        E_all = pd.read_parquet(K.OUT / f"events_{und}.parquet")
        E_all["day"] = pd.to_datetime(E_all.day).dt.date
        B_all = pd.read_parquet(K.OUT / f"events_B0_{und}.parquet")
        B_all["day"] = pd.to_datetime(B_all.day).dt.date
        cache = {}
        opdays = sorted(pd.Timestamp.fromordinal(d).date() for d in op.atm)
        for part in parts:
            days = D.days_in(part, opdays)
            if not days:
                continue
            table = None
            for rule in rules:
                if und not in rule["unds"]:
                    continue
                G = run_rule(rule, und, part, M, op, E_all, B_all, cache)
                key = f"{rule['id']}|{und}|{part}"
                if G is None or not len(G):
                    out[key] = dict(trades=0, days=len(days))
                    continue
                if table is None:
                    table = day_table(und, days, M, op)
                p, tw, _ = twin_p(G, table)
                r = metrics(G, len(days))
                # descriptive only (not a gate): a random minute AND a random direction on the same day, which does not
                # borrow the day's direction from the signal (see PREREG.md Part B, "twin caveat")
                any_dir = np.nanmean([np.nanmean(np.r_[table[(d, 1)], table[(d, -1)]]) for d in G.day])
                r.update(p_twins=p, twin_per_trade=tw, rand_any_per_trade=float(any_dir),
                         per_trade_2x=float(G.net_2x.mean()), net_2x=float(G.net_2x.sum()))
                for o in EN.OPTS:
                    for x in S.EXIT_NAMES:
                        r[f"{o}_{x}_pnl_day"] = float(G[f"{o}_{x}_net"].sum() / len(days))
                out[key] = r
                all_trades.append(G.assign(rule=rule["id"]))
                print(tag, key, {k: (round(v, 3) if isinstance(v, float) else v) for k, v in r.items()
                                 if k in ("trades", "per_trade", "pnl_day", "win", "p_twins", "twin_per_trade")},
                      flush=True)
    if all_trades:
        pd.concat(all_trades, ignore_index=True).to_parquet(trades_file or K.OUT / f"rule_trades_{tag}.parquet")
    return out


def family_q(res: dict) -> dict:
    """BH across the confirmatory family (rules + B0) x indices, on the twin p-values of this read."""
    keys = [k for k, v in res.items() if np.isfinite(v.get("p_twins", np.nan))]
    q = MI.bh(np.array([res[k]["p_twins"] for k in keys]))
    m = len(keys)
    for k, qq in zip(keys, q):
        res[k]["bh_q"] = float(qq)
        # the largest combined family (R29 + F1) at which this result still passes BH at 0.10, if every F1 p-value is
        # larger than R29's (rank unchanged): p <= 0.10 * rank / M  ->  M <= 0.10 * rank / p
        p = res[k]["p_twins"]
        rank = int((np.array([res[j]["p_twins"] for j in keys]) <= p).sum())
        res[k]["max_family_for_q10"] = float(0.10 * rank / p) if p > 0 else float("inf")
    return dict(family_size=m)


def main(cmd):
    K.RESULTS.mkdir(exist_ok=True)
    if cmd == "design":
        res = evaluate(K.DESIGN, "design")
        (K.RESULTS / "design.json").write_text(json.dumps(res, indent=1, default=float))
    elif cmd == "wf":
        res = evaluate(("wf2025", "wf2026"), "wf")
        (K.RESULTS / "wf.json").write_text(json.dumps(res, indent=1, default=float))
    elif cmd == "holdout":
        if K.HOLDOUT_FLAG.exists():
            raise SystemExit("the R29 holdout has been read already (results/HOLDOUT_READ); a new read needs a new "
                             "pre-registration and a fresh holdout")
        if not D.prereg_b_filled():
            raise SystemExit("PREREG.md Part B is empty")
        K.HOLDOUT_FLAG.write_text(pd.Timestamp.now(tz="Asia/Kolkata").isoformat() + "\n")
        D.ALLOW_HOLDOUT = True
        res = evaluate(("holdout",), "holdout")
        fam = family_q(res)
        (K.RESULTS / "holdout.json").write_text(json.dumps(dict(family=fam, results=res), indent=1, default=float))
    elif cmd == "erratum1":
        erratum1()
    else:
        raise SystemExit(__doc__)


ERRATUM_FLAG = K.RESULTS / "ERRATUM1_DONE"


def erratum1():
    """Erratum 1 (14 Oct 2026, found by the pre-push review, AFTER the holdout read): B0 trades both signs but was gated
    per sign, so it could hold a bull and a bear trade at once (up to 6 a day), against PREREG "one trade at a time, at
    most 3 a day". R1-R10 have one sign each and are unaffected. B0 alone is re-run, unchanged in every other way, on
    every period incl. the holdout (no choice depends on it; disclosed in PREREG.md). 'Cut short' is recounted over
    every closed trade, as PREREG defines it (stops were left out), from the saved R1-R10 trades. Runs once."""
    if ERRATUM_FLAG.exists():
        raise SystemExit("erratum 1 has been applied already")
    ERRATUM_FLAG.write_text(pd.Timestamp.now(tz="Asia/Kolkata").isoformat() + "\n")
    for tag, parts, name in (("design", K.DESIGN, "design.json"), ("wf", ("wf2025", "wf2026"), "wf.json"),
                             ("holdout", ("holdout",), "holdout.json")):
        doc = json.loads((K.RESULTS / name).read_text())
        res = doc["results"] if tag == "holdout" else doc
        if tag == "holdout":
            D.ALLOW_HOLDOUT = True
        T = pd.read_parquet(K.OUT / f"rule_trades_{tag}.parquet")
        T = T[T.rule != "B0"]
        for key, r in res.items():
            rid, und, part = key.split("|")
            if rid == "B0" or not r.get("trades"):
                continue
            g = T[(T.rule == rid) & (T.und == und) & (T.part == part)]
            m = metrics(g, r["days"])
            assert m["trades"] == r["trades"] and abs(m["net"] - r["net"]) < 1e-6, key
            r["cut_short"], r["cut_share"] = m["cut_short"], m["cut_share"]
        b0 = evaluate(parts, tag + "_b0", rules=[RU.B0], trades_file=K.OUT / f"rule_trades_{tag}_b0.parquet")
        res.update(b0)
        B = pd.read_parquet(K.OUT / f"rule_trades_{tag}_b0.parquet")
        pd.concat([T, B], ignore_index=True).to_parquet(K.OUT / f"rule_trades_{tag}.parquet")
        if tag == "holdout":
            doc["family"] = family_q(res)
            doc["erratum1"] = "B0 re-gated one trade at a time across signs; cut short over all closed trades"
        (K.RESULTS / name).write_text(json.dumps(doc, indent=1, default=float))


if __name__ == "__main__":
    main(sys.argv[1])
