"""R171 report: results/report.md from results/design.json + results/oos.json (no data is read here).

    python research/hunt/r171_liquidity_gates/r171report.py
"""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
STRATS = ("R51", "R72", "LIQ", "EXP")
GATES = ("G1a", "G1b", "G1c", "G2a", "G2b", "G2c", "G2d", "G2e", "G2f", "G3a", "G3b", "G3c", "G3d",
         "G4a", "G4b", "G4c", "G4d", "C")
NAMES = {"R51": "R51 Donchian 20 + Supertrend", "R72": "R72 Breadth Donchian (4 of the 4 indices with data)",
         "LIQ": "Liquidity 15+5", "EXP": "options_lab expiry put (sold, to settlement)"}


def rs(x) -> str:
    if x is None:
        return "-"
    return f"{'-' if x < 0 else '+'}Rs {abs(x):,.0f}"


def row(g: str, r: dict | None, combo) -> str:
    if r is None:
        return f"| {g} | no combination (nothing qualified on design) | | | | | | | | | |"
    k, b = r["kept"]["quoted"], r["base"]["quoted"]
    label = g if g != "C" else f"C ({' + '.join(combo)})"
    return (f"| {label} | {k['n']} / {b['n']} | {rs(k['per_trade'])} | {rs(r['gain']['quoted'])} | "
            f"{rs(r['gain']['roll'])} / {rs(r['gain']['stress'])} | {rs(k['net'])} | "
            f"{k['win'] if k['win'] is not None else '-'}% | {rs(k['worst_day'])} | {rs(k['max_dd'])} | "
            f"{r['p_mc']:.3g} | {r['q']:.3g} |")


def amendment_a() -> tuple[list, list]:
    """(Boss bullets, report section) for Amendment A, from results/measure.json; empty when it was not run."""
    path = RESULTS / "measure.json"
    if not path.exists():
        return [], []
    m = json.loads(path.read_text())
    S = m["strategies"]
    rf = m["refit"]
    bands = m["all_oos_bands_pct"]
    below_q = round(bands["below roll"] + bands["roll-quoted"], 1)

    def pt(s, k, per="oos"):
        return rs(S[s][per]["priced"][k]["per_trade"])
    boss = [
        "- **New angle (Amendment A): measure the spread from real prices instead of assuming it.** Three estimators "
        "(Roll, Abdi-Ranaldo, Corwin-Schultz) on each trade's 30 minutes before entry.",
        f"  - The market looks CHEAPER than costs.py's 'quoted': on {below_q}% of out-of-sample trades the measured spread is "
        f"below it ({bands['below roll']}% even below 'roll'). The median-fit law is {rf['median']['a']:.2f} + "
        f"{rf['median']['b']:.5f} x premium points: the same slope as costs.py ({rf['costs_law']['b']}) but a bigger fixed "
        f"part ({rf['costs_law']['a']}), i.e. about 'roll + half a point'. 'quoted' overstates it by a median "
        f"{rf['oos_median_bias_pts']['costs.py quoted']:.1f} points a trade.",
        f"  - Re-priced with the measured spread, out-of-sample net per trade: R72 {pt('R72', 'measured')}, R51 "
        f"{pt('R51', 'measured')}, Liquidity {pt('LIQ', 'measured')} (with the wide Corwin-Schultz bound: "
        f"{pt('R72', 'cs_wide')} / {pt('R51', 'cs_wide')} / {pt('LIQ', 'cs_wide')}). In design all three lose under every "
        f"cost ({pt('R72', 'measured', 'design')} / {pt('R51', 'measured', 'design')} / {pt('LIQ', 'measured', 'design')} "
        "measured). So only R72 is positive out-of-sample at realistic costs, and not in design.",
        f"  - NIFTY options cost about twice BANKNIFTY's as a share of premium (median measured "
        f"{S['R51']['by_index']['NIFTY']['describe']['measured_share_q'][1] * 100:.2f}% vs "
        f"{S['R51']['by_index']['BANKNIFTY']['describe']['measured_share_q'][1] * 100:.2f}% on R51's trades). R51 on NIFTY "
        f"loses at every cost, even 'roll' ({rs(S['R51']['by_index']['NIFTY']['priced']['roll'])} a trade).",
        "  - Honest limit: these are effective-spread estimates from trade prices, not quotes; the two covariance "
        "estimators are unstable right before breakouts (trending minutes push them to 0), so the trustworthy bracket is "
        "'roll' (low) to Corwin-Schultz (high). Only the phone's sealed depth recordings can settle it.",
    ]
    L = ["## Amendment A: the spread MEASURED from real prices (a measurement, not a test)", "",
         "Pre-registered as Amendment A in PREREG.md before any of these numbers. It re-reads the out-of-sample trades for "
         "costing only (a second look). The pooled table at the end was added AFTER the first look (disclosed).", "",
         "### Where the measured spread sits against costs.py (share of trades with an estimate)", "",
         "| strategy | period | trades (measured) | below roll | roll-quoted | quoted-stress | above stress | "
         "median measured (pts / % of premium) | law at the same premiums: roll / quoted (median pts) |",
         "|---|---|---|---|---|---|---|---|---|"]
    for s in ("R51", "R72", "LIQ"):
        for per in ("design", "oos"):
            d = S[s][per]["describe"]
            b = d["bands_pct"]
            L.append(f"| {s} | {per} | {d['n']} ({d['n_measured']}) | {b['below roll']}% | {b['roll-quoted']}% | "
                     f"{b['quoted-stress']}% | {b['above stress']}% | {d['measured_pts_q'][1]:.2f} / "
                     f"{d['measured_share_q'][1] * 100:.2f}% | {d['law_roll_pts_q'][1]:.2f} / {d['law_quoted_pts_q'][1]:.2f} |")
    L += ["", "Estimator medians (share of premium, out-of-sample): " + "; ".join(
        f"{s} Roll {S[s]['oos']['describe']['roll_share_q'][1] * 100:.2f}%, AR {S[s]['oos']['describe']['ar_share_q'][1] * 100:.2f}%, "
        f"CS {S[s]['oos']['describe']['cs_share_q'][1] * 100:.2f}%" for s in ("R51", "R72", "LIQ")) +
        ". AR is 0 on most single 30-minute windows, so MEASURED (the median of the three) often falls to Roll or CS.", "",
        "### The spread law, refitted on design trades and checked out-of-sample", "",
        f"- costs.py (in-session, 'roll'): {rf['costs_law']['a']} + {rf['costs_law']['b']} x premium; 'quoted' is that x "
        f"{rf['costs_law']['quoted_scale']:.0f}.",
        f"- Refit, least squares: {rf['ols']['a']:.3f} + {rf['ols']['b']:.5f} x premium; median fit: {rf['median']['a']:.3f} + "
        f"{rf['median']['b']:.5f} x premium ({rf['n_design']} design trades).",
        "- Out-of-sample median absolute error / median bias (points, + = the law is wider than measured), "
        f"{rf['n_oos']} trades: " + "; ".join(f"{k} {rf['oos_median_abs_error_pts'][k]:.2f} / {rf['oos_median_bias_pts'][k]:+.2f}"
                                              for k in rf["oos_median_abs_error_pts"]) + ".",
        "", "### The strategies re-priced (net per trade; total; max DD)", "",
        "| strategy | period | roll | quoted | stress | MEASURED | CS (wide bound) |", "|---|---|---|---|---|---|---|"]
    for s in ("R51", "R72", "LIQ"):
        for per in ("design", "oos"):
            pr = S[s][per]["priced"]
            L.append(f"| {s} | {per} (n {pr['quoted']['n']}) | " + " | ".join(
                f"{rs(pr[k]['per_trade'])}; {rs(pr[k]['net'])}; DD {rs(pr[k]['max_dd'])}"
                for k in ("roll", "quoted", "stress", "measured", "cs_wide")) + " |")
    miss = ", ".join(f"{s} {S[s]['oos']['describe']['n'] - S[s]['oos']['describe']['n_measured']} of "
                     f"{S[s]['oos']['describe']['n']}" for s in ("R51", "R72", "LIQ"))
    L += ["", f"Trades with no estimate (fewer than 10 traded minutes before the fill, all of them entries before 09:30; "
          f"out-of-sample {miss}) are costed at 'quoted' in the MEASURED and CS columns, which pulls those columns toward "
          "'quoted' (the cautious side)."]
    L += ["", "By index, out-of-sample net per trade (roll / quoted / MEASURED / CS):", ""]
    for s in ("R51", "R72", "LIQ"):
        L.append(f"- {s}: " + "; ".join(
            f"{u} (n {S[s]['by_index'][u]['describe']['n']}) {rs(v['roll'])} / {rs(v['quoted'])} / {rs(v['measured'])} / "
            f"{rs(v['cs_wide'])}" for u, v in ((u, S[s]['by_index'][u]['priced']) for u in ("NIFTY", "BANKNIFTY"))))
    L += ["", "### The R171 gates re-priced (out-of-sample gain per trade: quoted / MEASURED / CS; verdicts unchanged)", "",
          "| gate | R51 | R72 | LIQ |", "|---|---|---|---|"]
    for g in GATES[:-1]:
        cells = []
        for s in ("R51", "R72", "LIQ"):
            v = S[s]["gates"][g]
            cells.append("-" if v["quoted"] is None else f"{rs(v['quoted'])} / {rs(v['measured'])} / {rs(v['cs_wide'])}")
        L.append(f"| {g} | " + " | ".join(cells) + " |")
    L += ["", "G4a's out-of-sample gain shrinks with real costs (R51 and Liquidity most), so part of it came from the "
          "assumed spread; G3c's gain on Liquidity grows. Neither changes a verdict.", "",
          "### Pooled check (POST-HOC, added after the first look)", "",
          "Single 30-minute windows often give 0, so the moment terms were averaged over all trades of each index and "
          "premium tercile before the square root. Points at the group's median premium:", "",
          "| period | group | n | premium | Roll pooled | AR pooled | CS median | law roll / quoted / stress |",
          "|---|---|---|---|---|---|---|---|"]
    for per in ("design", "oos"):
        for k, r in m["pooled_posthoc"][per].items():
            L.append(f"| {per} | {k} | {r['n']} | {r['premium']} | {r['roll_pts']} | {r['ar_pts']} | {r['cs_pts_median']} | "
                     f"{r['law_roll_pts']} / {r['law_quoted_pts']} / {r['law_stress_pts']} |")
    L += ["", "Reading: the covariance estimators (Roll, AR) swing from 0 to 2-3x the law between groups. The minutes "
          "before a breakout entry trend, which pushes them toward 0, so they cannot pin the spread here. Corwin-Schultz is "
          "stable and sits between costs.py's 'roll' and 'quoted' in 11 of the 12 groups (the exception: design-period "
          "BANKNIFTY low-premium trades, above 'quoted'); it reads some of each minute's real move as spread, so it is an "
          "upper bound. Best estimate from real prices: the effective spread on these "
          "near-the-money options is between 'roll' and 'quoted', closer to 'roll' on BANKNIFTY. 'quoted' (the costs.py "
          "default) is likely too pessimistic for them; 'stress' is far too wide for a 1-3 lot order.", ""]
    return boss, L


def main():
    des = json.loads((RESULTS / "design.json").read_text())
    oos = json.loads((RESULTS / "oos.json").read_text())
    S = oos["strategies"]
    n_pass = sum(1 for s in STRATS for g in GATES if S[s]["rows"].get(g) and S[s]["rows"][g]["verdict"] == "pass")
    b = {s: S[s]["rows"]["G1a"]["base"] for s in STRATS}
    r72 = S["R72"]["rows"]["G4a"]
    r51 = S["R51"]["rows"]["G4a"]
    liq = S["LIQ"]["rows"]["G3c"]
    amend_boss, amend_sec = amendment_a()
    L = []
    L += [
        "# R171: four liquidity / execution-cost gates on the arms",
        "",
        "13 Nov 2026. Pre-registered in `PREREG.md` before any number (written first; this session could not commit at "
        "first, so the gate pre-registration is not in its own earlier commit; Amendment A is, commit b684d4f). Research "
        "only: nothing in the app changes.",
        "",
        "## For Boss (short)",
        "",
        f"- **No gate passed: {n_pass} of {oos['n_tests']}.** None of the four upgrades beat random skips of the same size "
        f"after counting every earlier test (BH, m = {oos['m_total']:,}). Nothing should change in the app.",
        "- **1. Same-day 30/60-minute window:** it never skipped an out-of-sample trade. Every out-of-sample R51, R72 and "
        "Liquidity trade's strike traded in every one of the 30 minutes before entry; in design only 2 R51 and 2 R72 "
        "trades had a short gap (NIFTY and BANKNIFTY near-the-money options are always active). "
        "Today's d-1 check is not missing anything on these arms.",
        "- **3a. Depth:** never binding. The thinnest 1% of BANKNIFTY monthly entries still traded about 30-90 lots a "
        "minute; NIFTY about 250-1,900. A 1- or 3-lot order is far inside that (0% of trades would be 'stress' at 1 lot, at most 0.6% "
        "at 3 lots).",
        "- **2. Cost cap:** it skips cheap options and that LOWERED profit on R51 / R72 in design (the cheap options were "
        "not the losers). On the expiry put it removes almost every trade (a Rs 5 sold put costs far more than 3% of its "
        "premium), so it cannot be used there.",
        f"- **Best leads (not passed, keep watching only):**",
        f"  - 4a (spread vs theta, effectively 'on BANKNIFTY monthly, only the last week before expiry') on R72: "
        f"{r72['kept']['quoted']['n']} of {r72['base']['quoted']['n']} out-of-sample trades kept, "
        f"{rs(r72['kept']['quoted']['per_trade'])} a trade vs {rs(r72['base']['quoted']['per_trade'])}, better in all 4 "
        f"quarters, but only {rs(r72['design_gain'])} a trade better in design, p {r72['p_mc']:.3g} -> q {r72['q']:.2g}. "
        f"Same direction on R51 ({rs(r51['gain']['quoted'])} a trade, p {r51['p_mc']:.2g}).",
        f"  - 3c (skip a strike whose Roll spread just doubled) on Liquidity 15+5: {rs(liq['gain']['quoted'])} a trade "
        f"out-of-sample ({liq['kept']['quoted']['n']} of {liq['base']['quoted']['n']} kept) and "
        f"{rs(liq['design_gain'])} in design, but p {liq['p_mc']:.2g} -> q {liq['q']:.2g}.",
        "- **The big caveat:** the spread is an assumption. At the 'roll' spread these arms make "
        f"{rs(b['R51']['roll']['per_trade'])} (R51) / {rs(b['R72']['roll']['per_trade'])} (R72) / "
        f"{rs(b['LIQ']['roll']['per_trade'])} (Liquidity) a trade out-of-sample; at 'quoted' it is "
        f"{rs(b['R51']['quoted']['per_trade'])} / {rs(b['R72']['quoted']['per_trade'])} / "
        f"{rs(b['LIQ']['quoted']['per_trade'])}. Which one is true decides whether the arms make money at all, and only the "
        "phone's recorded depth (sealed, private IraAlgo repo, not opened here) can tell.",
        *amend_boss,
        "- These numbers are on the public release data (2024-02 to 2026-02-23, NIFTY + BANKNIFTY), not the app's own data, "
        "so the arms' rupee totals do not match their published records. Only 'gate vs no gate vs random skip on the same "
        "trades' is the result.",
        "",
        "## What was run",
        "",
        "- Data: the public `research-data` GitHub release (1-minute OHLCV + OI with real traded volume; one expiry a day, "
        "20 strikes). NIFTY 2024-04-01 - 2026-02-23, BANKNIFTY 2024-02-13 - 2026-02-23; FINNIFTY / SENSEX index minutes for "
        "R72's breadth; `options_lab/data/expiry_cache` for the expiry put. **Nothing on or after 2026-02-24** (the "
        "2026-02-24 .. 2026-09-05 hole, docs/design.md:83). The Drive lake the arms' own studies use is not reachable from "
        "this session, so the Apr - Oct 2026 period could not be read at all.",
        "- Strategies (signal on the index, the option bought; the expiry put sells): " +
        "; ".join(f"{k} = {v}" for k, v in NAMES.items()) + ". Rules as PREREG.md section 1.",
        "- Costs: dated statutory charges + Rs 20 an order (`research/obuy/costs.py` 'dated'), plus the `options_lab/costs.py` "
        "spread law crossed half each way. Primary regime 'quoted'; 'roll' and 'stress' beside it.",
        "- Periods: DESIGN start - 2025-03-31 (the combination is chosen here); OUT-OF-SAMPLE 2025-04-01 - 2026-02-23, read "
        "once, also shown by quarter. The expiry put's last 30 sessions are options_lab's sealed holdout and were not read, "
        "so it has only 24 out-of-sample sessions (cannot pass, < 40).",
        "- Control: random skips of the same size from the same trades (20,000 draws; the finite-population normal p "
        "agrees). Every gated trade is one of the no-gate trades (a skipped break still holds its side and counts in the "
        "caps, as the app does), so the control is exact.",
        "- Signal source: the index for every R51 / R72 / Liquidity entry; the expiry put's strike from the options' "
        "parity forward. Option volume, premium and the Roll estimate are used only as filters (CLAUDE.md allows).",
        "",
        "## Out-of-sample results (quoted spread)",
        "",
        "Columns: trades kept / no-gate trades; net per trade; gain per trade vs no gate (quoted); the gain under roll / "
        "stress costs; total net; win %; worst day; max drawdown; random-skip p (Monte-Carlo); BH q.",
        "",
    ]
    for s in STRATS:
        base = S[s]["rows"]["G1a"]["base"]["quoted"]
        L += [f"### {NAMES[s]}", "",
              f"No gate: {base['n']} trades, {rs(base['per_trade'])} a trade, total {rs(base['net'])}, win {base['win']}%, "
              f"worst day {rs(base['worst_day'])}, max DD {rs(base['max_dd'])} (roll {rs(S[s]['rows']['G1a']['base']['roll']['per_trade'])}"
              f" / stress {rs(S[s]['rows']['G1a']['base']['stress']['per_trade'])} a trade).", "",
              "| gate | kept | net / trade | gain | gain roll / stress | total | win | worst day | max DD | p | q |",
              "|---|---|---|---|---|---|---|---|---|---|---|"]
        for g in GATES:
            L.append(row(g, S[s]["rows"].get(g), S[s]["combo"]))
        L.append("")
    L += ["## Design period (the gates were fixed in advance; only the combination was chosen here)", "",
          "| strategy | no gate (n, net / trade) | combination chosen | gains per trade, G1a..G4d (quoted) |", "|---|---|---|---|"]
    for s in STRATS:
        rows = des["strategies"][s]["rows"]
        bb = rows["G1a"]["base"]["quoted"]
        gains = ", ".join(f"{g} {rs(rows[g]['gain']['quoted'])}" for g in GATES[:-1])
        L.append(f"| {s} | {bb['n']}, {rs(bb['per_trade'])} | {' + '.join(des['strategies'][s]['combo']) or 'none'} | {gains} |")
    L += ["",
          "## Why each family failed",
          "",
          "- **G1 (same-day window):** no trade had a single empty minute in its 30- or 60-minute pre-entry window (G1c "
          "skipped 2 R51 / 2 R72 trades in design only). The idea is right in principle but these arms only buy "
          "near-the-money NIFTY / BANKNIFTY options, which trade every minute; it would matter for far strikes, "
          "FINNIFTY / MIDCPNIFTY or stock options (no option data here).",
          "- **G2 (cost cap):** skipping cheap premiums cut good trades as often as bad ones; the tick-floor rule (Rs 10) "
          "never fired on the arms (their 1-ITM premiums are Rs 100+). G2f (stress spread) keeps under 15% of trades and "
          "they are worse. On the expiry put the cap is incompatible with the strategy.",
          "- **G3 (depth / Roll):** depth never binds at 1-3 lots. The Roll spike (G3c/d) helped Liquidity in both periods "
          "but not enough to beat random skips after BH; it hurt R51 in both periods, so it is not a general rule.",
          "- **G4 (DTE-coupled):** the spread-to-premium ratio is nearly constant, so spread_in_theta_minutes is mostly a "
          "DTE cap; in this data it skips BANKNIFTY monthly trades more than about a week from expiry (few NIFTY trades: 5 / 3 / 79 "
          "out-of-sample for R51 / R72 / Liquidity). Those trades lost; the kept BANKNIFTY trades made money out-of-sample, but the design period showed "
          "almost none of that gain, and G4d (the same rule with the MEASURED Roll spread) did not help at all, so the "
          "effect is about DTE, not about the spread. Not passed; it would need a fresh pre-registered test of 'BANKNIFTY "
          "monthly only in its last week' on new data.",
          "",
          "## Spread assumption (costs.py) and what would validate it",
          "",
          "- No free bid / ask history exists; every spread here is the costs.py law. Results that depend on it (G2, G4a-c) "
          "are shown under roll / quoted / stress above; G4d and G3c/d use the Roll estimate measured from real 1-minute "
          "prices instead.",
          "- The arms' sign flips between the roll and quoted regimes. Validating the regime needs the phone's recorded "
          "market depth (sealed recordings in the private IraAlgo repo); they were NOT opened (encrypted, and this session "
          "may not read that repo).",
          "",
          *amend_sec,
          "## App change",
          "",
          "None: no gate passed, so there is no app spec. Nothing in `android/**` changed. The gate functions are in "
          "`options_lab/features/gates.py` (new functions only; `liquid()` and `LIQUID_THRESHOLD` unchanged) with tests in "
          "`options_lab/tests/test_gates_r171.py`. If Boss wants the two leads watched, the only allowed form is a SHADOW "
          "log (off by default, never skipping a trade) of 'would 4a / 3c have skipped this', judged later on new days.",
          "",
          "## Reproduce",
          "",
          "```",
          "R171_DATA=<release files> R171_CACHE=<cache> python research/hunt/r171_liquidity_gates/r171data.py build",
          "... python research/hunt/r171_liquidity_gates/r171study.py trades && ... design && ... oos   # oos once only",
          "python research/hunt/r171_liquidity_gates/r171report.py",
          "python -m pytest research/hunt/r171_liquidity_gates/test_r171.py options_lab/tests/test_gates_r171.py -q",
          "```",
          ""]
    (RESULTS / "report.md").write_text("\n".join(L))


if __name__ == "__main__":
    main()
