"""Post-hoc (Boss's request, 7 Oct 2026, after JARVIS_EXITS.md's pre-registered 12): the pre-06-Oct rule for Jarvis's own
trades, L40 = 15% premium stop (tick-rounded down, LiquidityRules), +40 point target, profit-lock ladder on 40 (+10 ->
breakeven after charges, +20 -> +10, +30 -> +20), out by 15:15, NO 35-premium floor; next to 30/60 (C0) on the same
entry sets, data, fills and charges as research/jarvis_exits.py (whose caches it reuses). Appends a section to
JARVIS_EXITS.md.

    python3 -I research/jarvis_exits_posthoc.py <scratchpad>
"""
import importlib.util
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("jx", os.path.join(HERE, "jarvis_exits.py"))
jx = importlib.util.module_from_spec(spec)
spec.loader.exec_module(jx)          # reads sys.argv[1] (the scratchpad) itself
pd, np = jx.pd, jx.np


class L40(jx.Exit):
    def plan(self, e):
        t = math.floor(e * 0.85 / jx.TICK + 1e-9) * jx.TICK
        r = round(t * 100) / 100.0
        stop = r if jx.TICK <= r < e else None
        return stop, e + 40.0, 40.0


def run():
    ix_all = {u: jx.load_index(u) for u in jx.UNDS}
    sets = {"PAT": jx.pattern_entries(ix_all), "LIQ": jx.liq_entries(ix_all), "SOLO": jx.solo_entries(ix_all), "RND": jx.random_entries(ix_all)}
    entries = [e for v in sets.values() for e in v]
    need = {u: jx.defaultdict(set) for u in jx.UNDS}
    for en in entries:
        en["strike"] = int(math.floor(en["spot"] / jx.STEP[en["und"]] + 0.5) * jx.STEP[en["und"]])
        en["right"] = "C" if en["side"] > 0 else "P"
        need[en["und"]][en["day"]].add((en["strike"], en["right"]))
    opts = {u: jx.load_options(u, dict(need[u])) for u in jx.UNDS}
    c0 = jx.EXITS[0]
    l40 = L40("L40", "L40 pre-06-Oct: -15% / +40 / ladder on 40, no 35 floor")
    rows = []
    for en in entries:
        ser = opts[en["und"]].get((en["day"], en["strike"], en["right"]))
        if ser is None or len(ser[0]) == 0:
            continue
        ixd = ix_all[en["und"]][en["day"]]
        for ex, floor in ((c0, 35.0), (l40, 0.0)):
            jx.MIN_PREMIUM = floor
            r = jx.simulate(ex, en, ser, ixd, ixd["lot"])
            if r is None:
                continue
            r.update(set=en["set"], und=en["und"], day=en["day"], x=ex.name, key=(en["set"], en["und"], en["day"], en["minute"], en["side"]))
            rows.append(r)
    T = pd.DataFrame(rows)
    T["year"] = pd.to_datetime(T.day).dt.year
    c0keys = set(T[T.x == "C0"].key)
    T["in_c0"] = T.key.isin(c0keys)
    return T


def main():
    T = run()
    T.drop(columns=["key"]).to_csv(os.path.join(jx.CACHE, "jx_posthoc_trades.csv"), index=False)
    REAL = ["PAT", "LIQ", "SOLO"]
    span = {s: max((T[T.set == s].day.max() - T[T.set == s].day.min()).days / 365.25, 0.5) for s in REAL + ["RND"]}
    real = T[T.set.isin(REAL)]
    span_real = (real.day.max() - real.day.min()).days / 365.25
    rs = jx.rs
    variants = [("C0 30/60 + ladder 60 (35 floor)", T.x == "C0"), ("L40 pre-06-Oct rule (no floor)", T.x == "L40"),
                ("L40 on 30/60's trades only (premium > 35)", (T.x == "L40") & T.in_c0)]
    L = []
    P = L.append
    P("")
    P("## Post-hoc: the pre-06-Oct rule (L40), added after the fact at Boss's request")
    P("")
    P("Not one of the pre-registered 12 above: Boss switched Jarvis's own trades back to this rule on 7 Oct and asked for it to be "
      "measured the same way (research/jarvis_exits_posthoc.py, same entry sets, option choice, prices, fills, charges and 15:15 exit). "
      "L40: stop 15% below the price paid (tick-rounded down, as LiquidityRules), target +40 points, profit-lock ladder on 40 "
      "(+10 -> breakeven after charges, +20 -> lock +10, +30 -> lock +20), out by 15:15, and no 35-premium floor (so it also buys "
      "the cheap options 30/60 skips; the third row compares on exactly 30/60's trades).")
    P("")
    P("### Real entry sets pooled (pattern ideas + Liquidity + Solo; 1 lot each)")
    P("")
    P("| rule | trades | win | Rs / trade | Rs / year | max DD | worst trade | years + |")
    P("|---|---|---|---|---|---|---|---|")
    for name, m in variants:
        s = jx.stats(real[m[real.index]], span_real)
        P(f"| {name} | {s['trades']} | {s['win'] * 100:.0f}% | {rs(s['avg'])} | **{rs(s['per_year'])}** | {rs(s['dd'])} | {rs(s['worst'])} | {s['years_pos']}/{s['years']} |")
    P("")
    P("### Per entry set")
    P("")
    P("| set | rule | trades | Rs / trade | Rs / year | max DD | worst trade | years + |")
    P("|---|---|---|---|---|---|---|---|")
    for st in REAL + ["RND"]:
        for name, m in variants:
            t = T[m & (T.set == st)]
            s = jx.stats(t, span[st])
            P(f"| {st} | {name.split(' ')[0]}{' (same trades)' if 'only' in name else ''} | {s['trades']} | {rs(s['avg'])} | {rs(s['per_year'])} | "
              f"{rs(s['dd'])} | {rs(s['worst'])} | {s['years_pos']}/{s['years']} |")
    P("")
    P("### Net by year, real sets pooled (Rs)")
    P("")
    years = sorted(real.year.unique())
    P("| rule | " + " | ".join(str(y) for y in years) + " |")
    P("|---|" + "---|" * len(years))
    for name, m in variants:
        by = real[m[real.index]].groupby("year").net.sum()
        P(f"| {name.split(' ')[0]}{' (same trades)' if 'only' in name else ''} | " + " | ".join(rs(by.get(y, 0.0)) for y in years) + " |")
    P("")
    P("### Risk per trade (planned stop, median per lot by index; all sets)")
    P("")
    P("| rule | NIFTY pts / Rs | BANKNIFTY pts / Rs | FINNIFTY pts / Rs | median premium NIFTY / BANKNIFTY / FINNIFTY |")
    P("|---|---|---|---|---|")
    for name, m in variants[:2]:
        t = T[m]
        g = t.groupby("und")
        cells = [f"{g.risk_pts.median()[u]:.0f} / {g.risk_rs.median()[u]:,.0f}" for u in jx.UNDS]
        P(f"| {name.split(' ')[0]} | " + " | ".join(cells) + " | " + " / ".join(f"Rs {g.entry.median()[u]:.0f}" for u in jx.UNDS) + " |")
    P("")
    c = jx.stats(real[real.x == "C0"], span_real)
    l = jx.stats(real[real.x == "L40"], span_real)
    g0 = T[T.x == "C0"].groupby("und").risk_rs.median()
    g1 = T[T.x == "L40"].groupby("und").risk_rs.median()
    more = [u for u in jx.UNDS if g1[u] > g0[u] * 1.001]
    P(f"Reading it: L40 makes Rs {rs(l['avg'])} a trade on the real sets against Rs {rs(c['avg'])} for 30/60 (drawdown Rs {rs(l['dd'])} vs "
      f"Rs {rs(c['dd'])}). Against 30/60's 30-point stop it risks " +
      (f"MORE per lot on {', '.join(more)} (median), less elsewhere - by Boss's rule that part adds risk." if more else "no more per lot on any index (median).") +
      " Post-hoc and a single rule: it was not chosen by these results, but it was not pre-registered either.")
    with open(jx.OUT, "a") as f:
        f.write("\n".join(L) + "\n")
    print("\n".join(L))


if __name__ == "__main__":
    main()
