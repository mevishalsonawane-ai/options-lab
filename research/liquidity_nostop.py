"""POST-HOC (Boss's question, 07 Oct): Liquidity 15+5 with no stop loss and no premium target - hold to the liquidity level.

    python3 -I research/liquidity_nostop.py <prep dir> <dhan options dir> <cache dir>

Reuses research/liquidity_exits_3060.py (same cache, same entries, same fills and charges) and APPENDS a post-hoc section
to research/LIQUIDITY_EXITS_3060.md. Variants (exits only; 15:10 square-off always):
  N1   next liquidity: out when the index touches the next existing level ahead (the arm's target); nothing else
  N2   new liquidity: out when a new level forms on the trade's side after the entry (the arm's 'new_liquidity' exit)
  N12  the first of N1 and N2
  N3   N1 + the failed break (a completed bar closes back through the broken level)
Each is run two ways: (a) as the app would run it (one position per book, so a longer hold skips later breaks), and
(b) on exactly the current arm's 1,651 entries, each held on its own.
"""
from __future__ import annotations

import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.argv = [sys.argv[0], sys.argv[1], sys.argv[2], sys.argv[3]]
spec = importlib.util.spec_from_file_location("lx", os.path.join(HERE, "liquidity_exits_3060.py"))
lx = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lx)
np, pd, V, rs = lx.np, lx.pd, lx.V, lx.rs

NV = [
    V("A", "A current exits", family="A"),
    V("N1", "N1 next liquidity only (+15:10)", None, idx=False, timed=False, next_liq=True, failed=False, newliq=False),
    V("N2", "N2 new liquidity only (+15:10)", None, idx=False, timed=False, next_liq=False, failed=False, newliq=True),
    V("N12", "N12 next or new liquidity (+15:10)", None, idx=False, timed=False, next_liq=True, failed=False, newliq=True),
    V("N3", "N3 next liquidity + failed break (+15:10)", None, idx=False, timed=False, next_liq=True, failed=True, newliq=False),
]


def extra(t):
    t = t.copy()
    t["pct"] = (t.exit - t.entry) / t.entry * 100
    day = t.groupby("day").net.sum()
    w = t.loc[t.net.idxmin()]
    return dict(worst_trade=t.net.min(), worst_trade_pct=w.pct, worst_pct=t.pct.min(), worst_day=day.min(), worst_day_name=str(day.idxmin()),
                p30=(t.pct <= -30).mean() * 100, p50=(t.pct <= -50).mean() * 100, avg_hold=(t.exit_min - t.entry_min).mean(),
                worst5=t.net.nsmallest(5).mean())


def main():
    recs = lx.build("BANKNIFTY") + lx.build("FINNIFTY")
    all_days = sorted({r["day"] for r in recs})
    span = (all_days[-1] - all_days[0]).days / 365.25 * 248
    base = lx.run_variant(NV[0], recs)
    only = set(zip(base.book, base.day, base.entry_min))
    out = []
    P = out.append
    P("")
    P("## POST-HOC (added at Boss's request, 07 Oct): no stop loss, no target - hold to the liquidity level (research/liquidity_nostop.py)")
    P("")
    P("Not part of the pre-set comparison above; these were run after its results were known. Every stop is removed (no -15% "
      "premium stop, no index stop, no 20-minute time stop) and there is no premium target. 15:10 square-off stays (an intraday "
      "option arm must be flat by then).")
    P("")
    P("'Wait till the next liquidity' has two readings in LiquidityRules:")
    P("- **next liquidity** (`target`, exit `next_liquidity`): the nearest level that ALREADY exists beyond the entry; out when "
      "the index touches it. With no level ahead, the trade holds to 15:10. -> **N1**")
    P("- **new liquidity** (exit `new_liquidity`): a NEW swing or pool level forming on the trade's side after the entry. -> **N2**")
    P("They differ, so both are run, plus N12 (whichever comes first) and **N3** = N1 + the failed break (bar closes back through the broken level).")
    P("")
    for mode, label, kw in (("app", "(a) as the app would run it: one position per book, so longer holds skip later breaks", {}),
                            ("same", "(b) exactly the current arm's 1,651 entries, each held on its own", {"only": only})):
        res = {v.name: (base if v.name == "A" else lx.run_variant(v, recs, **kw)) for v in NV}
        P(f"### {label}")
        P("")
        P("| variant | trades | win | Rs / trade | Rs / year / lot | max DD | worst trade (Rs, % of premium) | worst trade % | trades losing >30% / >50% of premium | worst day | worst month | longest losing run | years + | BANKNIFTY | FINNIFTY | avg hold |")
        P("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for v in NV:
            t = res[v.name]
            s, x = lx.stats(t, span), extra(t)
            P(f"| {v.label} | {s['trades']} | {s['win'] * 100:.0f}% | {rs(s['avg'])} | **{rs(s['per_year'])}** | {rs(s['dd'])} | "
              f"{rs(x['worst_trade'])} ({x['worst_trade_pct']:.0f}%) | {x['worst_pct']:.0f}% | {x['p30']:.1f}% / {x['p50']:.1f}% | "
              f"{rs(x['worst_day'])} ({x['worst_day_name']}) | {rs(s['worst_month'])} ({s['worst_month_name']}) | {s['streak']} | "
              f"{s['years_pos']}/{s['years']} | {rs(t[t.und == 'BANKNIFTY'].net.sum())} | {rs(t[t.und == 'FINNIFTY'].net.sum())} | {x['avg_hold']:.0f} min |")
        P("")
        yr = pd.concat([res[v.name].assign(v=v.name) for v in NV])
        yr = yr.groupby(["v", pd.to_datetime(yr.day).dt.year]).net.sum().unstack().reindex([v.name for v in NV])
        P("| per year | " + " | ".join(str(c) for c in yr.columns) + " |")
        P("|---|" + "---|" * len(yr.columns))
        for n in yr.index:
            P(f"| {n} | " + " | ".join(rs(yr.loc[n, c]) for c in yr.columns) + " |")
        P("")
        if mode == "same":
            P("How they end: " + "; ".join(f"{v.name}: " + ", ".join(f"{k} {p * 100:.0f}%" for k, p in res[v.name].why.value_counts(normalize=True).items())
                                           for v in NV[1:]) + ".")
            P("")
        print("\n".join(out[-(len(NV) + 12):]))
        globals()["last_" + mode] = res
    P("Notes: 'worst trade %' is the exit price against the price paid; with no stop the only cap on a loss is the premium "
      "itself (100%) and the 15:10 square-off. Per year = net / the sample's span in years (as above). Charges and fills as above.")
    with open(os.path.join(HERE, "LIQUIDITY_EXITS_3060.md"), "a") as f:
        f.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
