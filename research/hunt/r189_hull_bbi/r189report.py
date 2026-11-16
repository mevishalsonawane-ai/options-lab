"""R189 report: results/summary.md (plain English for Boss, outcome first) and results/report.md (full tables).
Plain-English notes, 16 Nov 2026."""
from __future__ import annotations

import r189cfg as K

UND_NAME = {"NIFTY": "NIFTY", "BANKNIFTY": "BANKNIFTY", "FINNIFTY": "FINNIFTY", "MIDCPNIFTY": "MIDCPNIFTY",
            "SENSEX": "SENSEX", "all": "all five"}


def rs(x: float) -> str:
    """Rupees, Indian grouping, sign first: -Rs 1,23,456."""
    v = int(round(float(x)))
    sign = "-" if v < 0 else ""
    s = str(abs(v))
    if len(s) > 3:
        head, tail = s[:-3], s[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        s = ",".join(parts) + "," + tail
    return f"{sign}Rs {s}"


def lots_word(n: int) -> str:
    return f"{n} lot" + ("s" if n > 1 else "")


def row(label: str, s: dict) -> str:
    return (f"| {label} | {s['trades']} | {s['win']:.0f}% | {rs(s['net'])} | {rs(s['avg_win'])} / {rs(s['avg_loss'])} | "
            f"{rs(s['worst_day'])} | {rs(s['max_dd'])} | {rs(s['worst_month'])} |")


HDR = ("| | trades | win | net | avg win / avg loss | worst day | max drawdown | worst month |\n"
       "|---|---|---|---|---|---|---|---|")


def _mix(s: dict) -> str:
    tot = max(s["trades"], 1)
    return ", ".join(f"{k} {v * 100 / tot:.0f}%" for k, v in sorted(s["exits"].items(), key=lambda kv: -kv[1]))


def write(cells: dict, nofill: dict, trades: dict, ch: dict):
    pick = ch["pick"]
    P = f"BOSS-{pick}"
    c = lambda r, u="all", s="both", n=1: cells[(r, u, s, n)]  # noqa: E731
    head = c(P)
    verdict_rules = [P] + [f"{v}-{pick}" for v in K.VARIANTS]
    passed = sorted(k for k, v in cells.items() if not v["fails"] and k[0] in verdict_rules)
    passed_any = sorted(k for k, v in cells.items() if not v["fails"])
    nxt = ch["next_two"]
    std = c(f"STD-{pick}")

    # ------------------------------------------------------------------------------------------ summary
    L = []
    L.append("# R189: Boss's Hull line (\"BBI\"), 2 closed candles above the rising line: summary for Boss\n")
    L.append("**1-min only, no 10-sec data.** The 10-second half of the rule could not be tested: no 10-second index "
             "data is reachable (the phone's per-second recordings are sealed with Boss's passphrase, which this "
             "study does not have and never tried to open). Nothing was faked from 1-minute bars.\n")
    f, d = head["per"]["final"], head["per"]["design"]
    verdict = ("**Verdict: FAILED.** No cell of the picked rule passed every gate." if not passed else
               f"**Verdict: {len(passed)} cell(s) of the picked rule passed every gate** (listed in report.md).")
    L.append(verdict + "\n")
    L.append(f"- **The exit research picked {pick}** ({K.EXIT_NAME[pick]}): {ch['rule']}, on the design months only "
             f"(20 Nov 2024 - 31 Mar 2026).")
    L.append(f"- **Primary rule (Boss's line, candle close above / below, ATM option, the {pick} exit), all five "
             f"indices, 1 lot:**")
    for per, name in (("final", "last 6 months (1 Apr - 6 Oct 2026, the locked holdout, read once)"),
                      ("full", "full period (20 Nov 2024 - 6 Oct 2026)")):
        parts = []
        for s, sn in (("C", "calls"), ("P", "puts"), ("both", "together")):
            x = c(P, "all", s)["per"][per]
            parts.append(f"{sn} {x['trades']} trades, {x['win']:.0f}% won, {rs(x['net'])}")
        L.append(f"  - {name}: " + "; ".join(parts) + ".")
    L.append(f"- **Standard Hull line instead of Boss's script** (same exit): last 6 months "
             f"{std['per']['final']['trades']} trades, {std['per']['final']['win']:.0f}% won, "
             f"{rs(std['per']['final']['net'])}; full period {std['per']['full']['trades']} trades, "
             f"{rs(std['per']['full']['net'])}. The difference in one line: the standard Hull doubles the fast (27) "
             f"average and subtracts the slow (55) one to cut lag; Boss's script does the reverse, so its line lags "
             f"MORE than a plain 55-minute average (about 29 minutes on a steady move, against about 1 for the "
             f"standard one).")
    L.append(f"- Twin test on the last 6 months (random entry times, same exits): p = {head['p_emp']:.3f}; "
             f"BH q = {head['q_all']:.2f} across {K.M_TOTAL:,} tests.")
    if head["fails"]:
        L.append("- Gates failed by the headline (all five, calls + puts, 1 lot): " + "; ".join(head["fails"]) + ".")
    L.append("")
    L.append(f"## Net per index and lot, the picked exit ({pick}), calls + puts\n")
    for per, name in (("full", "Full period (20 Nov 2024 - 6 Oct 2026)"), ("final", "Last 6 months (1 Apr - 6 Oct 2026)")):
        L.append(f"### {name}\n")
        L.append(HDR)
        for u in K.IDX_SCOPES:
            for n in K.LOTS:
                L.append(row(f"{UND_NAME[u]}, {lots_word(n)}", c(P, u, "both", n)["per"][per]))
        L.append("")
    L.append("## Which exit research picked, and how it held up\n")
    L.append("| exit | design folds (1 lot, all five) | design net | last 6 months: trades | win | net |")
    L.append("|---|---|---|---|---|---|")
    for x in [pick] + nxt:
        cc = c(f"BOSS-{x}")
        fn = ch["fold_nets"][x]
        L.append(f"| {x} {K.EXIT_NAME[x]}{' (picked)' if x == pick else ''} | {', '.join(rs(v) for v in fn)} | "
                 f"{rs(sum(fn))} | {cc['per']['final']['trades']} | {cc['per']['final']['win']:.0f}% | "
                 f"{rs(cc['per']['final']['net'])} |")
    L.append(f"\nThe walk-forward of the choice itself (pick on earlier folds, score the next): "
             f"{rs(ch['walk_forward']['net'])} (picks {', '.join(ch['walk_forward']['picks'])} for folds 2-4).\n")
    L.append("## What it means\n")
    best_final = max(K.EXITS, key=lambda x: c(f"BOSS-{x}")["per"]["final"]["net"])
    bf = c(f"BOSS-{best_final}")["per"]["final"]
    L.append(f"- Of all 16 exits on the same entries, the best last-6-months result was {best_final} "
             f"({K.EXIT_NAME[best_final]}): {bf['trades']} trades, {rs(bf['net'])} (a secondary number, not gated; "
             f"picking it after seeing the holdout would be hindsight).")
    n_pos = sum(1 for x in K.EXITS if c(f"BOSS-{x}")["per"]["final"]["net"] > 0)
    n_pos_d = sum(1 for x in K.EXITS if c(f"BOSS-{x}")["per"]["design"]["net"] > 0)
    L.append(f"- Exits with a positive design net (all five, 1 lot): {n_pos_d} of 16; with a positive last 6 months: "
             f"{n_pos} of 16.")
    L.append("- To test Boss's full rule (1-minute AND 10-second), the 10-second candles are needed: about a year of "
             "10-second index (or near-futures) bars. The phone's tick zone records per-second NIFTY and BANKNIFTY "
             "futures since 12 Oct; Boss could export them with `research/data/decrypt_live.py` on their own PC, "
             "but 23 sessions is too few to judge. The full rule can only take a subset of these 1-minute entries.")
    L.append("- Data: Dhan's 1-minute index candles and 1-minute option prices (nearest expiry, ATM +/- 10), real fills "
             "(R17 slippage; stops sold as stop orders) and dated Zerodha charges. Signal source: the index spot.")
    if passed_any and not passed:
        L.append(f"- {len(passed_any)} cell(s) outside the picked rule passed every gate (secondary candidates; "
                 "they were not chosen by the design rule, so they are hindsight; listed in report.md).")
    (K.RESULTS / "summary.md").write_text("\n".join(L) + "\n")

    # ------------------------------------------------------------------------------------------ report
    R = []
    R.append("# R189 report: Boss's Hull line, 2 closed 1-minute candles beyond the line, buy the option\n")
    R.append("Pre-registration: `research/hunt/r189_hull_bbi/PREREG.md` (committed before any number). **1-min only, "
             "no 10-sec data** (PREREG section 1). Signal source of every rule: the index spot (1-minute candles); "
             "the option is only traded; premium stops / targets / locks on the option. Data: the `dhan-data` branch "
             "(Dhan v2, fetched 6 Oct 2026), read with research/obuy/data.py; NIFTY and SENSEX weekly, BANKNIFTY, "
             "FINNIFTY and MIDCPNIFTY monthly options (nearest expiry). Fills: R51's `simulate` with R17 slippage; "
             "dated Zerodha charges.\n")
    R.append(f"Tests: {len(cells)} cells (19 rule sets x 6 index scopes x 3 sides x 3 lots); BH m = {K.M_TOTAL:,}.\n")
    R.append("## 1. The exit choice (design only)\n")
    R.append(f"Rule: {ch['rule']}. Order: {', '.join(ch['order'])}. Picked **{pick}**; next two: {', '.join(nxt)}. "
             f"Exits with all four folds > 0: {', '.join(ch['qualified']) or 'none'}.\n")
    R.append("| exit | fold 1 | fold 2 | fold 3 | fold 4 | design net | design trades | win | last 6 months net "
             "(secondary) | trades | full net |")
    R.append("|---|---|---|---|---|---|---|---|---|---|---|")
    for x in ch["order"]:
        cc = c(f"BOSS-{x}")
        fn = ch["fold_nets"][x]
        R.append(f"| {x} {K.EXIT_NAME[x]} | " + " | ".join(rs(v) for v in fn) +
                 f" | {rs(cc['per']['design']['net'])} | {cc['per']['design']['trades']} | "
                 f"{cc['per']['design']['win']:.0f}% | {rs(cc['per']['final']['net'])} | "
                 f"{cc['per']['final']['trades']} | {rs(cc['per']['full']['net'])} |")
    R.append(f"\nSelection walk-forward: {rs(ch['walk_forward']['net'])}, picks {ch['walk_forward']['picks']} for folds "
             f"2-4. Not run (no data): " + "; ".join(f"{k} {v}" for k, v in K.UNTESTABLE.items()) + ".\n")
    R.append("## 2. Verdict cells (the picked exit and its three entry variants)\n")
    R.append("| rule | index | side | lots | final trades | final net | net w/o top 5 | design WF | twin p | BH q | "
             "q (R189 only) | gates failed |")
    R.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in verdict_rules:
        for u in K.IDX_SCOPES:
            for s in K.SIDE_SCOPES:
                for n in K.LOTS:
                    x = c(r, u, s, n)
                    fl = x["per"]["final"]
                    R.append(f"| {r} | {u} | {s} | {n} | {fl['trades']} | {rs(fl['net'])} | {rs(fl['net_wo_top5'])} | "
                             f"{rs(x['wf'])} | {x['p_emp']:.3f} | {x['q_all']:.2f} | {x['q_own']:.2f} | "
                             f"{'; '.join(x['fails']) or 'PASS'} |")
    R.append("")
    R.append(f"## 3. The picked rule ({P}) by index, side and lots\n")
    for per in ("full", "design", "final"):
        R.append(f"### {K.PERIOD_NAME[per]}\n")
        R.append(HDR)
        for u in K.IDX_SCOPES:
            for s in K.SIDE_SCOPES:
                for n in K.LOTS:
                    R.append(row(f"{u}, {K.SIDE_NAME[s]}, {lots_word(n)}", c(P, u, s, n)["per"][per]))
        R.append("")
    R.append("### Design folds and exit mix (1 lot, calls + puts)\n")
    R.append("| index | fold 1 | fold 2 | fold 3 | fold 4 | exit mix (full) |")
    R.append("|---|---|---|---|---|---|")
    for u in K.IDX_SCOPES:
        x = c(P, u)
        R.append(f"| {u} | " + " | ".join(rs(v) for v in x["folds"]) + f" | {_mix(x['per']['full'])} |")
    R.append("")
    R.append("## 4. Entry variants with the picked exit (all five, 1 lot)\n")
    R.append("| rule | side | full trades | full win | full net | design net | last 6 months trades | win | net |")
    R.append("|---|---|---|---|---|---|---|---|---|")
    for r in verdict_rules:
        for s in K.SIDE_SCOPES:
            x = c(r, "all", s)["per"]
            R.append(f"| {r} | {K.SIDE_NAME[s]} | {x['full']['trades']} | {x['full']['win']:.0f}% | {rs(x['full']['net'])} | "
                     f"{rs(x['design']['net'])} | {x['final']['trades']} | {x['final']['win']:.0f}% | "
                     f"{rs(x['final']['net'])} |")
    R.append("")
    R.append("## 5. Every exit candidate on the last 6 months (secondary, not gated), all five, 1 lot\n")
    R.append("| exit | calls: trades / win / net | puts: trades / win / net | together: trades / win / net | twin p | "
             "worst month | max DD | exit mix |")
    R.append("|---|---|---|---|---|---|---|---|")
    for x in sorted(K.EXITS):
        r = f"BOSS-{x}"
        parts = []
        for s in K.SIDE_SCOPES:
            q = c(r, "all", s)["per"]["final"]
            parts.append(f"{q['trades']} / {q['win']:.0f}% / {rs(q['net'])}")
        q = c(r)
        R.append(f"| {x} {K.EXIT_NAME[x]} | " + " | ".join(parts) + f" | {q['p_emp']:.3f} | "
                 f"{rs(q['per']['final']['worst_month'])} | {rs(q['per']['final']['max_dd'])} | {_mix(q['per']['final'])} |")
    R.append("")
    R.append("## 6. Cells that passed every gate\n")
    if passed_any:
        for k in passed_any:
            x = cells[k]
            tag = "verdict rule" if k[0] in verdict_rules else "secondary (not the design pick: hindsight)"
            R.append(f"- {k}: final {x['per']['final']['trades']} trades, {rs(x['per']['final']['net'])}, "
                     f"p {x['p_emp']:.4f}, q {x['q_all']:.3f} ({tag})")
    else:
        R.append("None.")
    R.append("")
    R.append("## 7. No-fills (signals with no option trade in the 3-minute slack or no stored strike)\n")
    R.append("| rule | " + " | ".join(K.UNDS) + " |")
    R.append("|---|" + "---|" * len(K.UNDS))
    for r in verdict_rules:
        R.append(f"| {r} | " + " | ".join(str(nofill[r].get(u, 0)) for u in K.UNDS) + " |")
    R.append("")
    R.append("## 8. Caveats\n")
    R.append("- 1-min only: Boss's 10-second confirmation was not tested (no data). The full rule is stricter.")
    R.append("- The last 6 months (Apr - Oct 2026) were read before by other studies for other rules; this rule never.")
    R.append("- Dhan's rolling option data holds ATM +/- 10 strikes of the nearest expiry; a strike outside it is a "
             "no-fill. FINNIFTY and MIDCPNIFTY options are thin (more no-fills, wider spreads in the slippage model).")
    R.append("- Twins share their random entry draws across rule sets (common random numbers) and may overlap.")
    R.append("- The index has no volume; Pine's line is on the index close, as Boss's chart would draw it.")
    (K.RESULTS / "report.md").write_text("\n".join(R) + "\n")
