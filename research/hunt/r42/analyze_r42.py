"""R42 analysis: M4's frozen rule on raw / F1-clean / F2-clean lake data, twin random baseline, walk-forward,
and which of M4's original trades touched a flagged price (PREREG.md).

    python -I analyze_r42.py WORK FLAGS        # WORK = m4_rerun.py's work dir, FLAGS = census.py's out dir
Writes small CSVs to research/hunt/r42/results/."""
import importlib.util
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
RES = HERE / "results"
M4RES = HERE.parent / "m4" / "results"
CELL = ("SILVERM", "OTM2-3 call", "11-20", "morning")
H, EX, NDRAW = "h240", "E1", 2000
VARIANTS = ("raw", "f1", "f2")


def load_module(work, v):
    os.environ["R42_SP"] = str(Path(work) / v)
    os.environ["R42_RES"] = str(Path(work) / v / "results")
    spec = importlib.util.spec_from_file_location(f"m4a_{v}", Path(work) / v / "analyze_r42.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def windows(E):
    h = E[E.hold & ~E.extra]
    return {"design": E[~E.hold], "holdout": h, "holdout_to_7oct": h[h.day < pd.Timestamp("2026-10-08")],
            "extra": E[E.extra]}


def twin_p(A, T, pool, rng, spread_mult=1.0):
    """Same day, random morning minute with a valid entry in the same cell; p = share of draws >= rule net."""
    if not len(T):
        return np.nan, 0
    _, pn, pm = A.add_pnl(pool, H, exit_col="P", spread_mult=spread_mult)
    pool = pool.assign(net=pn)[np.isfinite(pm)]
    byday = {d: x.net.values for d, x in pool.groupby("day")}
    sims = np.zeros(NDRAW)
    missing = 0
    for d, c in T.groupby("day").size().items():
        v = byday.get(d)
        if v is None or not len(v):
            missing += c
            continue
        sims += rng.choice(v, size=(NDRAW, c)).sum(1)
    return float((sims >= T.net.sum()).mean()), missing


def summarize(A, T, label):
    r = A.summarize(T, label) if len(T) else dict(rule=label, trades=0)
    if len(T):
        top3 = T.net.nlargest(3).sum()
        r["top3_share"] = top3 / T.net.sum() if T.net.sum() != 0 else np.nan
        r["first"], r["last"] = str(T.day.min().date()), str(T.day.max().date())
    return r


def rule_runs(work):
    out, trades = [], {}
    for v in VARIANTS:
        A = load_module(work, v)
        E = A.load("SILVERM")
        E1m = A.load("SILVERM", tag="1m")
        cm, db, tb = CELL[1:]
        pool_all = E1m[(E1m.cell_m == cm) & (E1m.db == db) & (E1m.tb == tb) & E1m[f"{H}_P"].notna()]
        for wn, Ew in windows(E).items():
            rng = np.random.default_rng(11)  # M4's seed, fresh per run as in its holdout call
            T, p_m4 = A.run_rule(Ew, CELL, H, EX, rng, NDRAW)
            trades[(v, wn)] = T
            for sm in (1.0, 2.0):
                Ts = T
                if sm == 2.0 and len(T):
                    g, n, _ = A.add_pnl(T, H, exit_col="P", spread_mult=2.0)
                    Ts = T.assign(gross=g, net=n)
                pt, miss = twin_p(A, Ts, pool_all[pool_all.day.isin(Ts.day.unique())] if len(Ts) else pool_all,
                                  np.random.default_rng(42), sm)
                r = summarize(A, Ts, f"{v} {wn} spread x{sm:g}")
                r.update(variant=v, window=wn, spread=sm, random_p_m4=p_m4, random_p_twin=pt, twin_missing=miss)
                if len(Ts):
                    r["net_by_month"] = "; ".join(f"{m}: {x:+.0f}" for m, x in Ts.groupby("month").net.sum().items())
                out.append(r)
            print(v, wn, len(T), round(T.net.mean(), 1) if len(T) else None, "p_m4", p_m4, flush=True)
    return pd.DataFrame(out), trades


def walkforward(work):
    rows = []
    for v in VARIANTS:
        A = load_module(work, v)
        A.q4(["CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM"], hold=False)
        WF = pd.read_csv(Path(work) / v / "results" / "q4_walkforward_CRUDEOIL_NATURALGAS_GOLDM_SILVERM.csv")
        WF.insert(0, "variant", v)
        rows.append(WF)
        print(v, "WF net", WF.net.sum(), flush=True)
    return pd.concat(rows, ignore_index=True)


def flagged_original(flags_dir, trades):
    """M4's 51 original holdout trades: flagged entry/exit minute (or a flagged minute in the 5-min ffill window
    before the exit), and the trade's raw vs cleaned prices."""
    O = pd.read_csv(M4RES / "q4_holdout_trades.csv", parse_dates=["ts"])
    F = pd.read_parquet(Path(flags_dir) / "flags_SILVERM.parquet")
    F = F[F.side.astype(str) == "C"].set_index(["ts", "strike"]).sort_index()
    rows = []
    for _, t in O.iterrows():
        ent = F.loc[(t.ts, t.K)] if (t.ts, t.K) in F.index else None
        ex_ts = t.ts + pd.Timedelta(minutes=240)
        win = [(ex_ts - pd.Timedelta(minutes=m), t.K) for m in range(0, 6)]
        exw = F.loc[[w for w in win if w in F.index]] if any(w in F.index for w in win) else F.iloc[:0]
        r = dict(ts=t.ts, K=t.K, P0_m4=t.P0, exit_m4=t[f"{H}_P"], net_m4=t.net,
                 flag_entry_f1=bool(ent is not None and ent.f1_tr), flag_entry_f2=bool(ent is not None and ent.f2_tr),
                 flag_exit_f1=bool(exw.f1_tr.any()), flag_exit_f2=bool(exw.f2_tr.any()),
                 exit_vol=int(F.loc[(ex_ts, t.K)].volume) if (ex_ts, t.K) in F.index else 0,  # no row = no trade
                 entry_vol=int(ent.volume) if ent is not None else -1)
        for v in VARIANTS:
            T = trades[(v, "holdout")]
            m = T[(T.ts == t.ts) & (T.K == t.K)]
            r[f"P0_{v}"] = float(m.P0.iloc[0]) if len(m) else np.nan
            r[f"exit_{v}"] = float(m[f"{H}_P"].iloc[0]) if len(m) else np.nan
            r[f"net_{v}"] = float(m.net.iloc[0]) if len(m) else np.nan
        rows.append(r)
    return pd.DataFrame(rows)


def main(work, flags_dir):
    RES.mkdir(exist_ok=True)
    R, trades = rule_runs(work)
    R.to_csv(RES / "m4_rerun.csv", index=False)
    for v in VARIANTS:
        for wn in ("holdout", "extra"):
            T = trades[(v, wn)]
            T[["ts", "K", "cp", "k", "P0", f"{H}_P", f"{H}_fb", "mult", "gross", "net"]].to_csv(
                RES / f"trades_{wn}_{v}.csv", index=False)
    FO = flagged_original(flags_dir, trades)
    FO.to_csv(RES / "m4_original_trades_flags.csv", index=False)
    # design trades: did any entry/exit price change between raw and cleaned?
    chg = []
    for v in ("f1", "f2"):
        a, b = trades[("raw", "design")], trades[(v, "design")]
        m = a.merge(b, on=["ts", "K"], how="outer", suffixes=("_raw", "_c"), indicator=True)
        both = m[m._merge == "both"]
        chg.append(dict(variant=v, raw_trades=len(a), clean_trades=len(b), same_entries=len(both),
                        price_changed=int(((both.P0_raw != both.P0_c) | (both[f"{H}_P_raw"] != both[f"{H}_P_c"])).sum())))
    pd.DataFrame(chg).to_csv(RES / "design_trade_changes.csv", index=False)
    print(pd.DataFrame(chg).to_string())
    WF = walkforward(work)
    WF.to_csv(RES / "walkforward.csv", index=False)
    pd.set_option("display.width", 250)
    print(R[["variant", "window", "spread", "trades", "hit", "net_tr", "net_tot", "maxdd", "top3_share", "random_p_m4",
             "random_p_twin"]].round(3).to_string())


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
