"""R113 adapter (26 Oct 2026): the MCX paper arm "SILVERM morning call" (app McxMorningRules = M4's frozen Q4 rule;
research/MCX_OPTIONS.md; research/hunt/m4/build.py + analyze.py run_rule / trades_for).

Rule (frozen, as tested): SILVERM OTM2-3 call, 11-20 trading days to expiry, morning 15-min grid (09:15-13:45), buy at
the grid minute's printed close, time exit after 240 grid minutes (E1; capped at 23:25), one position at a time, 1 lot
(5 kg; the mini lot rule only when > Rs 1 lakh), Rs 500 minimum premium (amendment 4).
How: build.py and analyze.py are run UNCHANGED except their hard-coded scratchpad data root (SP), which is pointed at
~/.cache/iraalgo/r113/m4sp (M3's raw option files rebuilt from the lake by _mcx_raw.py; build.py's CPI/FOMC flag file is
research/context/events.csv - those flags are not used by the Q4 rule). analyze.run_rule gives the trades and their net
(analyze.add_pnl: Zerodha charges + the committed spread model results/spread_estimates.json).
Exit minute = build.py's own horizon index j = min(i + 240, cut_i) on that day's minute grid.
Signal source: no direction signal (a direction-free time/strike/expiry rule); the strike is chosen from the SILVERM
future (Dhan `spot`) = the underlying.
usage: python3 -I research/hunt/r113_time_of_day/adapters/mcx_silverm_am.py"""
import json, os, shutil, sys, types
from pathlib import Path
import numpy as np, pandas as pd

REPO = Path("/home/user/options-lab")
HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import _mcx_raw

OUT = Path(os.path.expanduser("~/.cache/iraalgo/r113/trades"))
SPN = Path(os.path.expanduser("~/.cache/iraalgo/r113/m4sp"))
OLD_SP = 'Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt")'
SYM = "SILVERM"
CELL = (SYM, "OTM2-3 call", "11-20", "morning")
H, EX = "h240", "E1"


def load_module(name, path):
    src = Path(path).read_text()
    assert OLD_SP in src
    mod = types.ModuleType(name); mod.__file__ = str(path)
    exec(compile(src.replace(OLD_SP, f'Path("{SPN}")'), str(path), "exec"), mod.__dict__)
    return mod


def main():
    raw = _mcx_raw.build(SYM)
    (SPN / "m3").mkdir(parents=True, exist_ok=True); (SPN / "h28").mkdir(exist_ok=True)
    if not (SPN / "m3" / "raw").exists():
        (SPN / "m3" / "raw").symlink_to(raw)
    shutil.copy(REPO / "research/context/events.csv", SPN / "h28" / "events.csv")
    B = load_module("m4build", REPO / "research/hunt/m4/build.py")
    if not (B.W / f"ent_{SYM}.parquet").exists():
        B.main(SYM)
    A = load_module("m4analyze", REPO / "research/hunt/m4/analyze.py")
    E = A.load(SYM)
    Mn = pd.read_parquet(B.W / f"min_{SYM}.parquet")
    out, per_sum = [], {}
    for per, part in (("design", E[~E.hold]), ("final", E[E.hold])):
        T, _ = A.run_rule(part, CELL, H, EX)
        T = T.sort_values("ts").copy()
        # exit minute: build.py j = min(i + 240, cut_i) on the day's grid (rows of min_SYM, tod 09:00-23:55)
        ex = []
        for ts, d in zip(T.ts, T.day):
            md = Mn[Mn.day == d]
            idx = md.index; tod = md.tod.values
            i = idx.get_loc(ts)
            cut_i = int(np.searchsorted(tod, B.CUT, side="right")) - 1
            ex.append(idx[min(i + B.HS[H], cut_i)])
        T["exit_ts"] = ex
        T["period"] = per
        out.append(T)
        per_sum[per] = dict(n=int(len(T)), net=round(float(T.net.sum()), 1))
    T = pd.concat(out, ignore_index=True)
    # final: M4's committed holdout list (results/q4_holdout_trades.csv) is the study's own record; the lake copy has 8 Oct
    # (2 more trades M4's data did not reach) and a few later prints, so 6 of the 51 nets differ on re-run. Keep exactly
    # the committed trades with their committed net; the exit minute is the re-run's (a time exit: same minute).
    hc0 = pd.read_csv(REPO / "research/hunt/m4/results/q4_holdout_trades.csv")
    hc0["ts"] = pd.to_datetime(hc0.ts)
    rerun_final = T[T.period == "final"][["ts", "net"]].rename(columns={"net": "net_rerun"})
    fin = T[T.period == "final"].drop(columns=["net"]).merge(hc0[["ts", "net"]], on="ts", how="inner")
    T = pd.concat([T[T.period == "design"], fin], ignore_index=True)
    per_sum["final"] = dict(n=int(len(fin)), net=round(float(fin.net.sum()), 1))
    per_sum["final_rerun_on_lake"] = dict(n=int(len(rerun_final)), net=round(float(rerun_final.net_rerun.sum()), 1),
                                          note="incl. 2 trades on 8 Oct 2026 and 6 re-priced exits; not used")
    # expiry flag: the rule only enters 11-20 trading days before expiry, so never on an expiry day
    csv = pd.DataFrame(dict(arm="MCX_SILVERM_AM", und=SYM, day=pd.to_datetime(T.day).dt.strftime("%Y-%m-%d"),
                            entry_ts=pd.DatetimeIndex(T.ts).strftime("%Y-%m-%d %H:%M"),
                            exit_ts=pd.DatetimeIndex(T.exit_ts).strftime("%Y-%m-%d %H:%M"), side="C",
                            net_rs=T.net.round(2), period=T.period, expiry=0))
    csv.to_csv(OUT / "MCX_SILVERM_AM.csv", index=False)
    # parity vs the committed holdout trades and the published design/holdout totals
    hc = pd.read_csv(REPO / "research/hunt/m4/results/q4_holdout_trades.csv")
    hf = rerun_final.rename(columns={"net_rerun": "net"}).assign(P0=np.nan)
    same_ts = set(pd.to_datetime(hc.ts)) == set(pd.DatetimeIndex(hf.ts))
    j = hc.assign(ts=pd.to_datetime(hc.ts)).merge(hf[["ts", "net", "P0"]], on="ts", how="outer", suffixes=("_pub", "_run"))
    pub = dict(design=dict(n=78, net_per_trade=1439, net="+1.12 L (q4_design csv)"),
               final=dict(n=51, net=23178.0, net_per_trade=454.47))
    dz = pd.read_csv(REPO / "research/hunt/m4/results/q4_design_CRUDEOIL_NATURALGAS_GOLDM_SILVERM.csv")
    drow = dz[dz.rule.str.contains("SILVERM OTM2-3 call dte11-20 morning h240 E1")]
    if len(drow):
        pub["design"] = dict(n=int(drow.trades.iloc[0]), net=round(float(drow.net_tot.iloc[0]), 1))
    gap = {p: dict(n=per_sum[p]["n"] - pub[p]["n"], net=round(per_sum[p]["net"] - pub[p]["net"], 1)) for p in ("design", "final")}
    par = all(g["n"] == 0 and abs(g["net"]) <= 0.01 * abs(pub[p]["net"]) for p, g in gap.items())
    meta = dict(
        arm="MCX_SILVERM_AM", app_rule="McxMorningRules (mcx_silverm_am, 'SILVERM morning call'); switched off 14 Oct (R42, "
        "McxMorningPark) and hidden 21 Oct",
        source_code="research/hunt/m4/build.py main() + analyze.py load/run_rule/trades_for/add_pnl (cell SILVERM OTM2-3 "
                    "call dte11-20 morning h240 E1)",
        adapter="research/hunt/r113_time_of_day/adapters/mcx_silverm_am.py (+ _mcx_raw.py)",
        command="python3 -I research/hunt/r113_time_of_day/adapters/mcx_silverm_am.py",
        signal_source="no direction signal (direction-free buy of a call at a fixed time); ATM/strike from the SILVERM "
                      "future (Dhan rolling-option `spot`) = the underlying; exit is a time exit",
        periods=dict(design="5 Aug 2025 - 7 Jul 2026 (M4 design)", final="8 Jul - 8 Oct 2026 (M4 locked holdout, run once)"),
        size="1 lot SILVERM option (5 kg); lot rule: big lot if premium x lot <= Rs 1 lakh, else the mini (none here)",
        cost_model="M4 analyze.py add_pnl/fees: Rs 40 brokerage a round trip, MCX 0.0418%, SEBI, CTT 0.05% sell, stamp, GST; "
                   "half the modelled spread each side (results/spread_estimates.json, crude's per-strike shape, x2 before 17:00)",
        n=per_sum, published=pub, gap_run_minus_published=gap,
        holdout_trade_check=dict(same_entry_minutes=bool(same_ts), committed=int(len(hc)), rerun=int(len(hf)),
                                 max_abs_net_diff=float(np.nanmax(np.abs(j.net_pub - j.net_run))) if len(j) else None),
        parity="yes" if par else "no (see gap_run_minus_published)",
        caveats=["entry_ts = the 15-min grid minute (M4 buys at that minute's printed close); exit_ts = 240 grid minutes "
                 "later on the day's minute rows, capped at 23:25 (build.py horizon); if the option had no print there, "
                 "M4 priced it from a <=5-min-old print or Black-76 at the last IV (h240_fb)",
                 "grid minutes are rows of the futures minute data, so a gap in the data makes the clock hold a little longer than 4 h",
                 "design = re-run on the lake (exact parity with M4's published design cell); final = M4's committed "
                 "holdout trade list with its committed net (the lake re-run adds 8 Oct and re-prices 6 exits: "
                 "see n.final_rerun_on_lake and holdout_trade_check)",
                 "expiry = 0 for all: the rule enters only 11-20 trading days before expiry",
                 "M4 verdict: not adopted (random p 0.050, walk-forward lost); top 3 holdout trades = 145% of net"],
    )
    (OUT / "MCX_SILVERM_AM.json").write_text(json.dumps(meta, indent=1, default=str))
    print(json.dumps(dict(n=per_sum, published=pub, gap=gap, check=meta["holdout_trade_check"]), indent=1, default=str))


if __name__ == "__main__":
    main()
