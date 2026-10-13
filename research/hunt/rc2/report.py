"""RC2 tables for the write-up: results/report.md (design, holdout, walk-forward, costs, by year), results/summary.json.
python3 -m research.hunt.rc2.report"""
import json
import os

import numpy as np
import pandas as pd

from . import config as C
from . import costs

L = []


def say(s=""):
    L.append(s)


def rs(x):
    return "-" if pd.isna(x) else f"{x:+,.0f}"


def pct(x, d=1):
    return "-" if pd.isna(x) else f"{x * 100:+.{d}f}%"


def trades(phase, r, x):
    fn = os.path.join(C.TRADES, f"{phase}_{r}_{x}.csv")
    return pd.read_csv(fn, parse_dates=["T", "t_exit", "expiry"]) if os.path.exists(fn) else pd.DataFrame()


def main():
    d = pd.read_csv(os.path.join(C.OUT, "design.csv"))
    h = pd.read_csv(os.path.join(C.OUT, "holdout.csv"))
    wf = pd.read_csv(os.path.join(C.OUT, "walkforward.csv"))
    m = d.merge(h, on=["rule", "exit"], suffixes=("", "_h"))
    picks = m[m.pick]
    say("# RC2 report tables")
    say()
    say("Design 2024-03-02..2026-04-12 (772 expiries per coin); holdout 2026-04-13..2026-10-13 (read once). Rs at the "
        "Rs 1 lakh account unless marked; 2% of the account per trade in premium; per-trade returns are on the premium "
        "paid, after Delta fees, the spread model and exit slippage.")
    say()
    say("## The pick of each rule (best design mean return among its four exits): design vs holdout")
    say()
    say("| rule | exit | design n | trades/day | win | avg win | avg loss | net/trade | Rs net | Rs/day | green days | worst day | max DD | "
        "holdout n | net/trade | Rs net | Rs/day | green days | worst day | max DD | after VDA tax | after business tax |")
    say("|" + "---|" * 23)
    for _, r in picks.iterrows():
        say(f"| {r.rule} | {r.exit} | {r.n} | {r['trades_per_day@100k']:.2f} | {r['win_rate@100k']:.0%} | {pct(r['avg_win_ret@100k'], 0)} | "
            f"{pct(r['avg_loss_ret@100k'], 0)} | {pct(r.mean_ret)} | {rs(r['net_inr@100k'])} | {rs(r['inr_per_day@100k'])} | "
            f"{r['green_days@100k']:.0%} | {rs(r['worst_day@100k'])} | {r['max_dd@100k']:,.0f} | {r.n_h} | {pct(r.mean_ret_h)} | "
            f"{rs(r['net_inr@100k_h'])} | {rs(r['inr_per_day@100k_h'])} | {r['green_days@100k_h']:.0%} | {rs(r['worst_day@100k_h'])} | "
            f"{r['max_dd@100k_h']:,.0f} | {rs(r['vda@100k_h'])} | {rs(r['business@100k_h'])} |")
    say()
    say("Rs 25,000 account (same trades, 2% = Rs 500 of premium a trade):")
    say()
    say("| rule | exit | design Rs net | Rs/day | holdout Rs net | Rs/day | holdout after VDA tax | TDS locked (holdout) |")
    say("|---|---|---|---|---|---|---|---|")
    for _, r in picks.iterrows():
        say(f"| {r.rule} | {r.exit} | {rs(r['net_inr@25k'])} | {rs(r['inr_per_day@25k'])} | {rs(r['net_inr@25k_h'])} | "
            f"{rs(r['inr_per_day@25k_h'])} | {rs(r['vda@25k_h'])} | {r['tds@25k_h']:,.0f} |")
    say()
    say("## Gates (design) and statistics, all 32 variants")
    say()
    say("| rule | exit | n | gross/trade | net/trade | Rs net | p same-clock | BH q | p direction | walk-forward Rs | stress Rs | "
        "G1 | G2 | G3 | stress | holdout n | holdout net/trade | holdout Rs | holdout t |")
    say("|" + "---|" * 19)
    for _, r in m.iterrows():
        yn = lambda b: "yes" if b else "no"
        say(f"| {r.rule} | {r.exit}{' (pick)' if r.pick else ''} | {r.n} | {pct(r.gross_ret)} | {pct(r.mean_ret)} | {rs(r['net_inr@100k'])} | "
            f"{r.p_clock:.3f} | {r.q_clock:.3f} | {r.p_dir:.3f} | {rs(r['wf_inr@100k'])} | {rs(r['stress_net_inr@100k'])} | "
            f"{yn(r.G1)} | {yn(r.G2)} | {yn(r.G3)} | {yn(r.G_stress)} | {r.n_h} | {pct(r.mean_ret_h)} | {rs(r['net_inr@100k_h'])} | "
            f"{r.t_stat:+.2f} |")
    say()
    say("## Big winners cut short by the lock (picks; a lock exit after which the option later CLOSED at >= 2x the exit "
        "price before the forced exit)")
    say()
    say("| rule | exit | design lock exits | cut short | holdout lock exits | cut short | design stops | design time exits |")
    say("|---|---|---|---|---|---|---|---|")
    for _, r in picks.iterrows():
        say(f"| {r.rule} | {r.exit} | {r['lock_exits@100k']:.0f} | {r['lock_cut_2x@100k']:.0f} | {r['lock_exits@100k_h']:.0f} | "
            f"{r['lock_cut_2x@100k_h']:.0f} | {r['stops@100k']:.0f} | {r['times@100k']:.0f} |")
    say()
    say("## Where the money goes: cost per round trip, picks, design trades (points of the premium)")
    say()
    say("| rule | exit | n | median entry mark (bp of spot) | spread paid | fees | exit slippage | gross/trade | net/trade |")
    say("|---|---|---|---|---|---|---|---|---|")
    sm = costs.spread_model()
    out_costs = {}
    for _, r in picks.iterrows():
        t = trades("design", r.rule, r.exit)
        if t.empty:
            continue
        hs_in = (t.buy - t.mid) / t.mid
        hs_out = np.array([costs.half_spread(c, lv, s, sm) for c, lv, s in zip(t.coin, t.level, t.spot)]) / t.mid
        slip = (t.level - t.sell) / t.mid - hs_out
        lot = t.coin.map(C.LOT)
        fees = (t.cost_lot - t.buy * lot) / (t.mid * lot) + ((t.sell - t.buy) * lot - t.cost_lot + t.buy * lot - t.pnl_lot) / (t.mid * lot)
        say(f"| {r.rule} | {r.exit} | {len(t)} | {(t.mid / t.spot * 1e4).median():.1f} | {pct((hs_in + hs_out).mean())} | "
            f"{pct(fees.mean())} | {pct(slip.mean())} | {pct(t.gross_ret.mean())} | {pct(t.ret.mean())} |")
        out_costs[r.rule] = dict(spread=float((hs_in + hs_out).mean()), fees=float(fees.mean()), slip=float(slip.mean()))
    say()
    say("## Picks by year and coin (design trades, net per trade)")
    say()
    say("| rule | exit | " + " | ".join(["2024", "2025", "2026 (to 12 Apr)", "BTC", "ETH", "median trade", "top-3 trades' share of the sum of returns"]) + " |")
    say("|" + "---|" * 9)
    for _, r in picks.iterrows():
        t = trades("design", r.rule, r.exit)
        if t.empty:
            continue
        y = t.groupby(t["T"].dt.year).ret.agg(["size", "mean"])
        c = t.groupby("coin").ret.agg(["size", "mean"])
        cell = lambda g, k: f"{pct(g.loc[k, 'mean'])} ({g.loc[k, 'size']})" if k in g.index else "-"
        tot = t.ret.sum()                     # equal weight per trade (BTC and ETH lots differ in size)
        top = t.ret.nlargest(3).sum() / tot if tot > 0 else np.nan
        say(f"| {r.rule} | {r.exit} | {cell(y, 2024)} | {cell(y, 2025)} | {cell(y, 2026)} | {cell(c, 'BTC')} | {cell(c, 'ETH')} | "
            f"{pct(t.ret.median())} | {'-' if pd.isna(top) else f'{top:.0%}'} |")
    say()
    say("## Walk-forward (each quarter uses the variant with the best mean return on all earlier design trades)")
    say()
    a = wf[wf.scope == "ALL"]
    say("| quarter | pick | its past net/trade | trades | net/trade in the quarter | Rs |")
    say("|---|---|---|---|---|---|")
    for _, r in a.iterrows():
        say(f"| {r.quarter} | {r.pick} | {pct(r.past_mean_ret)} | {r.trades} | {pct(r.mean_ret)} | {rs(r.inr)} |")
    say(f"| **all** | | | **{a.trades.sum()}** | **{pct((a.mean_ret * a.trades).sum() / max(a.trades.sum(), 1))}** | **{rs(a.inr.sum())}** |")
    say()
    g = wf.groupby("scope").agg(quarters=("quarter", "size"), trades=("trades", "sum"), inr=("inr", "sum"))
    say("Per rule (exit re-picked each quarter): " + "; ".join(f"{k} {rs(v.inr)} on {int(v.trades)} trades" for k, v in g.iterrows() if k != "ALL"))
    say()
    open(os.path.join(C.OUT, "report.md"), "w").write("\n".join(L) + "\n")
    summ = dict(picks=picks[["rule", "exit", "n", "mean_ret", "net_inr@100k", "n_h", "mean_ret_h", "net_inr@100k_h", "q_clock",
                             "design_pass"]].to_dict("records"),
                passed=[f"{r.rule}/{r.exit}" for _, r in picks.iterrows() if r.design_pass and r.n_h >= 20 and r["net_inr@100k_h"] > 0],
                costs=out_costs)
    json.dump(summ, open(os.path.join(C.OUT, "summary.json"), "w"), indent=1, default=float)
    print("\n".join(L))


if __name__ == "__main__":
    main()
