"""Run every h6 signal set through the obuy engine (real Dhan option minutes) under 3 executions, ONE data pass.

    flock <scratch>/obuy.lock python3 -I research/hunt/h6/run.py [div|heavy]

gross: fills at the print, no charges.   app: app fills (+-5 bps) + SandboxCosts (today's rates, STT 0.15%).
real : 'liq' fills (app bps + 1-4 tick half-spread from the last 5 minutes' volume) + SandboxCosts  -> the NET we report.
Output: <scratch>/hunt/h6/trades_<group>.parquet (+ pool_<group>.parquet: engine random-entry pools for single legs).
"""
import sys, os, dataclasses, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as cm
import numpy as np, pandas as pd
from obuy.costs import Costs, Fills
from obuy.engine import Execution, Exits, StrikeRule, prepare_many
import signals as S

EXES = {"gross": (Fills(mode="flat", pts=0.0), Costs(mode="flat", per_order=0.0)),
        "app": (Fills(), Costs()),
        "real": (Fills(mode="liq"), Costs())}
EXITS = {"t60": Exits(time_stop=60, time_gain=None), "sq": Exits(), "s30": Exits(stop_pct=0.3)}
RULE = StrikeRule(0)
WINDOW = (9 * 60 + 30, 13 * 60 + 30)
KEEP = ["cand", "parent", "und", "book", "day", "year", "sig_min", "entry_min", "exit_min", "side", "strike", "qty",
        "entry", "exit", "why", "gross", "charges", "net", "tag"]


def sets(group):
    out = {}
    if group == "div":
        for T in (30, 60):
            for k in (1.5, 2.0):
                out[f"DIV_mom_T{T}_k{k}"] = (S.div(T, k, "mom"), 0)
                out[f"DIV_rev_T{T}_k{k}"] = (S.div(T, k, "rev"), 0)
                out[f"RPAIR_T{T}_k{k}"] = (S.rpair(T, k), 0)
                out[f"CATCH_T{T}_k{k}"] = (S.catch(T, k), 5)
    else:
        import heavy
        out.update(heavy.sets())
    return out


def main(group):
    t0 = time.time()
    ss = sets(group)
    jobs, meta = [], []
    for name, (sig, pool) in ss.items():
        print(name, len(sig), flush=True)
        for en, (fl, co) in EXES.items():
            exe = Execution(fills=fl, costs=co, expiry="skip")
            jobs.append((sig, RULE, exe, pool if en == "real" else 0, WINDOW, False))
            meta.append((name, en, exe))
    packs = prepare_many(jobs)
    print("packs", round(time.time() - t0), flush=True)
    res, pools = [], []
    for (pk, pl), (name, en, exe) in zip(packs, meta):
        for xn, ex in EXITS.items():
            if len(pk):
                tr = pk.run(ex, exe)
                res.append(tr[[c for c in KEEP if c in tr]].assign(set=name, exe=en, xn=xn))
            if pl is not None and len(pl):
                pt = pl.run(ex, exe)
                pools.append(pt[[c for c in KEEP if c in pt]].assign(set=name, exe=en, xn=xn))
    pd.concat(res).to_parquet(os.path.join(cm.SCR, f"trades_{group}.parquet"))
    if pools:
        pd.concat(pools).to_parquet(os.path.join(cm.SCR, f"pool_{group}.parquet"))
    print("done", round(time.time() - t0), flush=True)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "div")
