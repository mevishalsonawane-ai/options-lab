"""R63 study driver (PREREG.md).

    python research/hunt/r63/r63study.py design     # every strategy's design tables (subprocesses), stats -> results/design.json
    python research/hunt/r63/r63study.py final      # the ONE final-test read, gates, BH -> results/final.json (refuses a second)

Tables (plain trades + each rule's re-priced trades + twin draws) live in <R63_OUT>/tables (never in git).
"""
from __future__ import annotations

import json
import pickle
import subprocess
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r63cfg as K  # noqa: E402
import r63rules as RR  # noqa: E402

PROCS = ("r51", "r57", "r54")


def build(period: str, missing_only: bool = False):
    for p in PROCS:
        if missing_only and all((K.OUT / "tables" / f"{s}_{period}.pkl").exists() for s in K.STRATS if K.PROC[s] == p):
            continue
        r = subprocess.run([sys.executable, str(HERE / "r63run.py"), p, period])
        if r.returncode:
            raise SystemExit(f"R63: {p} {period} failed")


def load(strat: str, period: str) -> dict:
    return pickle.loads((K.OUT / "tables" / f"{strat}_{period}.pkl").read_bytes())


# ------------------------------------------------------------------------------------------------ statistics
def max_dd(daily: np.ndarray) -> float:
    if not len(daily):
        return 0.0
    eq = np.concatenate([[0.0], np.cumsum(daily)])
    return float((np.maximum.accumulate(eq) - eq).max())


def bh(p) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    if not n:
        return p
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out


def p_value(actual: float, twins: np.ndarray) -> float:
    return (1 + int((np.asarray(twins) >= actual - 1e-9).sum())) / (len(twins) + 1)


def cell(T: pd.DataFrame, rule: str, twins: np.ndarray | None) -> dict:
    """Everything PREREG section 6 reports for one rule on one table of trades (one strategy or pooled)."""
    if not len(T):
        return dict(trades=0, changed=0, sessions=0, plain_net=0.0, rule_net=0.0, improvement=0.0, saved_on_losers=0.0,
                    given_up_on_winners=0.0, impr_ex_top5=0.0, plain_max_dd=0.0, rule_max_dd=0.0, plain_worst_day=0.0,
                    rule_worst_day=0.0, p=None, twin_mean=None)
    base, new = T.net.to_numpy(float), T[f"net_{rule}"].to_numpy(float)
    chg = T[f"chg_{rule}"].to_numpy(bool)
    d = new - base
    los, win = chg & (base < 0), chg & (base > 0)
    imp = float(d[chg].sum())
    top5 = np.sort(d[chg])[::-1][:5].sum() if chg.any() else 0.0
    dp = T.groupby("day").net.sum().sort_index()
    dn = T.assign(n_=new).groupby("day").n_.sum().sort_index()
    out = dict(trades=int(len(T)), changed=int(chg.sum()), sessions=int(T.day.nunique()),
               plain_net=round(float(base.sum()), 0), rule_net=round(float(new.sum()), 0), improvement=round(imp, 0),
               saved_on_losers=round(float(d[los].sum()), 0), given_up_on_winners=round(float(-d[win].sum()), 0),
               losers_changed=int(los.sum()), winners_changed=int(win.sum()),
               impr_ex_top5=round(imp - float(top5), 0),
               plain_max_dd=round(max_dd(dp.to_numpy()), 0), rule_max_dd=round(max_dd(dn.to_numpy()), 0),
               plain_worst_day=round(float(dp.min()), 0), rule_worst_day=round(float(dn.min()), 0),
               why=pd.Series(T[f"why_{rule}"][chg]).value_counts().to_dict() if chg.any() else {})
    if twins is not None:
        out["p"] = round(p_value(imp, twins), 4)
        out["twin_mean"] = round(float(np.mean(twins)), 0)
    return out


def quarters(T: pd.DataFrame, rule: str) -> list:
    out = []
    for a, b in K.WF_FOLDS:
        g = T[(T.day >= a) & (T.day <= b)] if len(T) else T
        out.append(round(float((g[f"net_{rule}"] - g.net)[g[f"chg_{rule}"]].sum()), 0) if len(g) else 0.0)
    return out


def splits(T: pd.DataFrame, rule: str) -> dict:
    """Per index (BANKNIFTY apart for 26 Oct) and per month: changed trades and improvement."""
    out = {"by_index": {}, "by_month": {}}
    if not len(T):
        return out
    d = (T[f"net_{rule}"] - T.net).where(T[f"chg_{rule}"], 0.0)
    for u, g in d.groupby(T.und):
        out["by_index"][u] = dict(changed=int(T[f"chg_{rule}"][g.index].sum()), improvement=round(float(g.sum()), 0),
                                  trades=int(len(g)))
    mon = T.day.map(lambda x: f"{x.year}-{x.month:02d}")
    for m, g in d.groupby(mon):
        out["by_month"][m] = round(float(g.sum()), 0)
    return out


def tables(period: str) -> dict:
    return {s: load(s, period) for s in K.STRATS}


def evaluate(period: str) -> dict:
    tb = tables(period)
    res = {}
    for rule in RR.RULES:
        res[rule] = {}
        pool_T, pool_tw = [], np.zeros(K.TWINS)
        for s in K.STRATS:
            T, tw = tb[s]["T"], tb[s]["twins"][rule]
            c = cell(T, rule, tw)
            c.update(splits(T, rule))
            if period == "design":
                c["wf_quarters"] = quarters(T, rule)
                c["wf_net"] = round(float(sum(c["wf_quarters"])), 0)
            res[rule][s] = c
            if len(T):
                cols = ["und", "day", "net", f"net_{rule}", f"chg_{rule}", f"why_{rule}"]
                pool_T.append(T[cols].assign(strat=s))
            pool_tw += tw
        P = pd.concat(pool_T, ignore_index=True)
        c = cell(P, rule, pool_tw)
        c.update(splits(P, rule))
        if period == "design":
            c["wf_quarters"] = quarters(P, rule)
            c["wf_net"] = round(float(sum(c["wf_quarters"])), 0)
        res[rule][K.POOLED] = c
    return res


def design_order(res: dict) -> dict:
    """PREREG section 8, decided on DESIGN only: per strategy (and pooled) the rules by design improvement, best first."""
    out = {}
    for s in K.STRATS + (K.POOLED,):
        out[s] = sorted(RR.RULES, key=lambda r: (-res[r][s]["improvement"], RR.RULES.index(r)))
    return out


def design():
    build("design", missing_only=True)
    res = evaluate("design")
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), period=[str(K.START), str(K.DESIGN_END)],
               rules=RR.NAMES, strategies=K.NAMES, cells=res, design_order=design_order(res))
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    for r in RR.RULES:
        print(r, {s: (c["changed"], c["improvement"], c.get("p")) for s, c in res[r].items()})


def gates(f: dict, d: dict, q: float) -> dict:
    fails = []
    if f["improvement"] <= 0:
        fails.append("final improvement <= 0")
    if f.get("p") is None or f["p"] >= K.GATE_P:
        fails.append("p >= 0.05")
    if q > K.GATE_Q:
        fails.append("q > 0.10")
    if f["changed"] < K.GATE_MIN_CHANGED:
        fails.append("fewer than 40 changed trades")
    if f["impr_ex_top5"] <= 0:
        fails.append("top-5 >= improvement")
    if d["improvement"] <= 0:
        fails.append("design improvement <= 0")
    if d["wf_net"] <= 0:
        fails.append("walk-forward <= 0")
    return dict(passed=not fails, fails=fails, q=round(float(q), 4))


def final():
    if K.FINAL_FLAG.exists():
        raise SystemExit("R63: the final test was already read (results/FINAL_READ). One read only.")
    des = json.loads((K.RESULTS / "design.json").read_text())
    K.FINAL_FLAG.write_text(f"final test read on {date.today()} ({datetime.now(timezone.utc).isoformat(timespec='seconds')})\n")
    build("final")
    res = evaluate("final")
    keys = [(r, s) for r in RR.RULES for s in K.STRATS + (K.POOLED,)]
    ps = [res[r][s]["p"] if res[r][s].get("p") is not None else 1.0 for r, s in keys]
    qs = bh(ps)
    for (r, s), q in zip(keys, qs):
        res[r][s]["gate"] = gates(res[r][s], des["cells"][r][s], q)
    passed = [f"{r}:{s}" for r, s in keys if res[r][s]["gate"]["passed"]]
    act = {}
    for s in K.STRATS + (K.POOLED,):
        ok = [r for r in des["design_order"][s] if res[r][s]["gate"]["passed"]]
        act[s] = ok[0] if ok else None
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), period=[str(K.FINAL_START), str(K.END)],
               cells=res, passed=passed, paper_rule=act, shadow_rule=des["design_order"][K.POOLED][0])
    (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    print("passed:", passed, "paper rule per strategy:", act)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "final":
        final()
    else:
        print(__doc__)
