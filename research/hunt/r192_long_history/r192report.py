"""R192 report: results/summary.md (for Boss), results/report.md (details) and decision.json, from results/r192.json,
results/calibration.json, results/calibration_calendar.json and results/k4_choice.json.

    python3 -I research/hunt/r192_long_history/r192study.py report
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r192cfg as K  # noqa: E402

TIMING_WORDS = {"ON": "15:20 -> next 09:16", "CC1": "15:20 -> next session 15:20", "CC2": "15:20 -> 2 sessions on 15:20",
                "D2": "09:20 -> next session 15:20", "G5": "K5 intraday (target + stop)", "G6": "K6 intraday (stop)"}


def rs(x: float) -> str:
    return f"-Rs {abs(x):,.0f}" if x < 0 else f"Rs {x:,.0f}"


def era_table(r: dict) -> list:
    out = ["| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |",
           "|---|---|---|---|---|---|---|---|"]
    for e in K.ERAS:
        if e == "S0" and "S0" not in r["need"]:
            continue
        s = r["eras"][e]
        lab = e + (" (synthetic)" if e in K.SYN_ERAS else "")
        if e == "S" and r["timing"] and r["name"].startswith("Night proxy"):
            lab = "S (synthetic, 2019 - Jul 2020)"
        if not s["trades"]:
            out.append(f"| {lab} | 0 | | | | | | |")
            continue
        top5 = rs(s["net_wo_top5"]) if e in K.REAL_ERAS else "(n/a)"
        out.append(f"| {lab} | {s['trades']} | {s['win']:.1f}% | {rs(s['net'])} | {rs(s['avg_win'])} / {rs(s['avg_loss'])} "
                   f"| {top5} | {rs(s['max_dd'])} | {rs(s['worst_month'])} |")
    return out


def verdict(r: dict) -> str:
    return "**Verdict: RELIABLE.**" if r["reliable"] else "**Verdict: NOT RELIABLE.** Failed: " + "; ".join(r["fails"]) + "."


def breach_line(r: dict) -> str:
    b = [f"era {e}: " + ", ".join(v) for e, v in r["breach"].items() if v]
    return ("Account stop (1 lot): " + "; ".join(b) + ".") if b else "Account stop (1 lot): no breach in the real eras."


def calib_lines(cal: dict, calc: dict) -> list:
    out = [f"- Amended model (trading-time clock, w = {cal['w']}, a = {cal['a']}): entry premium median absolute error "
           f"{cal['premium_AB']['median_abs_rel_error'] * 100:.1f}% on {cal['premium_AB']['n']:,} era A + B entries (the fit), "
           f"{cal['premium_C']['median_abs_rel_error'] * 100:.1f}% on {cal['premium_C']['n']:,} era C entries (the check).",
           f"- Pre-registered model (calendar clock, a = {calc['a']}): {calc['premium_AB']['median_abs_rel_error'] * 100:.1f}% (A + B), "
           f"{calc['premium_C']['median_abs_rel_error'] * 100:.1f}% (C).", "",
           "Synthetic minus real net per trade, every pool trade (all eligible days, both sides), 1 lot at the real lot:", "",
           "| hold type | A + B trades | A + B mean error | A + B mean abs error | A + B corr | C trades | C mean error | C mean abs error | calendar clock A + B / C mean error |",
           "|---|---|---|---|---|---|---|---|---|"]
    n = cal["net"]
    for t in ("ON", "CC1", "CC2", "D2", "G5", "G6"):
        a, c = n.get(f"syn:AB:{t}"), n.get(f"syn:C:{t}")
        ka, kc = n.get(f"syn_cal:AB:{t}"), n.get(f"syn_cal:C:{t}")
        if not a or not c:
            continue
        out.append(f"| {t} ({TIMING_WORDS[t]}) | {a['n']:,} | {rs(a['mean_error'])} | {rs(a['mae'])} | {a['corr']:.2f} | {c['n']:,} | "
                   f"{rs(c['mean_error'])} | {rs(c['mae'])} | {rs(ka['mean_error'])} / {rs(kc['mean_error'])} |")
    return out


def write():
    d = json.loads((K.RESULTS / "r192.json").read_text())
    cal = json.loads((K.RESULTS / "calibration.json").read_text())
    calc = json.loads((K.RESULTS / "calibration_calendar.json").read_text())
    k4 = json.loads((K.RESULTS / "k4_choice.json").read_text())
    ideas = d["ideas"]
    passed = [i for i, r in ideas.items() if r["reliable"]]
    outcome = (f"**Outcome: {len(passed)} of {len(ideas)} ideas passed the RELIABLE gate: {', '.join(passed)}.**" if passed else
               f"**Outcome: none of the {len(ideas)} ideas passed the RELIABLE gate.** Nothing here is a \"great and reliable\" "
               "strategy by the rules fixed before looking.")
    S = ["# R192 longer history and replicated patterns: summary for Boss", "",
         "Written 17 Nov 2026. NIFTY only, option BUYING only, 1 lot, the 1-ITM option of the nearest expiry. Real money "
         "(eras A, B, C) uses real 1-minute option prices, dated Zerodha charges and R17 slippage. Eras S0 / S use a "
         "SYNTHETIC option price (Black-76 from the index and India VIX, calibrated on real fills) as a check of the "
         "underlying edge only; synthetic rupees are never added to real rupees.", "", outcome, "",
         "**What this means, in plain words:**", ""]
    k4r, k5r, k3r = ideas["K4"], ideas["K5"], ideas["K3"]
    S += [
        f"- **The best lead is K4, a Night-like rule: buy the call after a strong close at the day's top (put after a weak close at "
        f"the bottom), 15:20 to 09:16.** Its filter (only a close in the top / bottom 20% of the day's range) was chosen on "
        f"2017-18 only, then left alone. On real prices it made money in all three eras ({rs(k4r['eras']['A']['net'])} in A, "
        f"{rs(k4r['eras']['B']['net'])} in B, {rs(k4r['eras']['C']['net'])} in C; {k4r['pooled_real']['trades']} trades, "
        f"{k4r['pooled_real']['win']:.0f}% won) and beat random nights clearly (twin p {k4r['p_emp']:.3f}). But it lost on the "
        f"synthetic 2019 - Jul 2020 check ({rs(k4r['eras']['S']['net'])}), its profit in A and C came from a handful of big "
        f"nights, and after counting the ~4,770 ideas tried it is not significant. Not proven.",
        f"- **Gap fade (K5) is the second lead:** buy against a 0.3-1.0% opening gap at 09:20, out when the gap fills. Real "
        f"money in all three eras ({rs(k5r['eras']['A']['net'])}, {rs(k5r['eras']['B']['net'])}, {rs(k5r['eras']['C']['net'])}), "
        f"but the synthetic 2017-20 check lost {rs(k5r['eras']['S']['net'])}, era C rests on its best 5 trades, and twin p "
        f"{k5r['p_emp']:.3f} misses 0.05.",
        f"- **The famous calendar and overnight effects do not survive the option's costs.** Buying a call every night (K3) "
        f"lost {rs(k3r['eras']['B']['net'] + k3r['eras']['C']['net'])} in eras B + C, worse than random sides. Turn of the "
        f"month, pre-holiday and VIX spikes are too rare for 40 trades an era (the 52-week signal fired only 9 times in era C), "
        f"and each lost in at least one era.",
        "- **Trend, gap-follow and big-move rules lost on real prices** (K6, K9, K10, K11).",
        "- **High win rate and reliable profit did not come together anywhere.** The win rates of the two leads are 41-55%; "
        "their average win is only a little bigger than their average loss.",
        "- **Longer history did not rescue anything.** Where the old synthetic eras were positive the real eras "
        "often were not (K3, K8, K11), and the two real-money leads lost in the synthetic years; the edges move with the "
        "regime.", ""]
    S += ["The gate (fixed before any number): net > 0 with 40+ trades in every era (synthetic S0 for daily rules, synthetic "
          "S, real A, B, C), net without the best 5 trades > 0 in each real era, beats random days / sides on the pooled "
          f"real trades (twin p < 0.05, {d['twins']:,} twins), and survives the correction for all {d['m_total']:,} ideas "
          "logged (BH q <= 0.10).", "",
          "Eras: **S0** Jan 2008 - Mar 2017 (synthetic, daily bars); **S** Apr 2017 - Jul 2020 (synthetic, index minutes); "
          "**A** Aug 2020 - Jul 2021; **B** Dec 2021 - Oct 2024; **C** Nov 2024 - 13 Nov 2026 (real). A, B and C were "
          "seen by earlier hunts at the data level (not these rules).", "",
          "Account stop for reference: -Rs 40,000 drawdown or -Rs 13,000 in a month (account about Rs 56k).", "",
          "## How good is the synthetic price?", ""] + calib_lines(cal, calc) + [
          "", "So the synthetic eras can tell a clear edge from a clear loss, but an edge of a few hundred rupees a trade is "
          "inside their error. Era C (bigger lots, higher STT) is priced more kindly by the model than reality.", ""]
    for i, r in ideas.items():
        S += [f"## {i}: {r['name']}", ""] + era_table(r) + ["", verdict(r), breach_line(r), ""]
    S += ["## Caveats", "",
          "- Synthetic eras check the underlying move with a modelled option; the model's error per trade (above) is as big "
          "as the edges tested. They are reported apart and never added to real money.",
          "- Only NIFTY could be tested on the long history; BANKNIFTY and the others have no minutes or options before 2021.",
          "- Eras A, B, C were seen at the data level by R190 and earlier hunts; several ideas are relatives of logged rules "
          "(PREREG section 3). Nothing was tuned on them; K4's filter was chosen on 2017-18 synthetic only.",
          "- The synthetic clock was amended after the calibration and before any rule result (PREREG section 10); the "
          "pre-registered calendar clock gives the same verdicts (report.md).",
          "- Research only. Nothing in the app changes.", ""]
    (K.RESULTS / "summary.md").write_text("\n".join(S))

    R = ["# R192 report: longer history and replicated patterns", "",
         "Pre-registration: `research/hunt/r192_long_history/PREREG.md` (committed before any number; amendment 1 to the "
         "synthetic clock committed after the calibration and before any rule result). Code: `r192build.py`, "
         "`r192study.py`, `r192lib.py`, `r192cfg.py`; tests `test_r192.py`.", "", outcome, "",
         "## Calibration of the synthetic price", "",
         f"Choice of w on eras A + B (sum over hold types of the squared mean net error); grid 0.00-1.00:", "",
         "| w | a | rmse log premium | objective |", "|---|---|---|---|"]
    for w, g in cal["grid"].items():
        R.append(f"| {w} | {g['a']} | {g['rmse_log']:.3f} | {g['objective']:,.0f} |")
    R += ["", f"Chosen: w = {cal['w']}, a = {cal['a']}.", ""] + calib_lines(cal, calc) + [
          "", "Synthetic expiry calendar check: on 3 Aug 2020 - 28 Aug 2025 the synthetic Thursday calendar matched 264 of "
          "the store's 265 weekly expiries (the miss: 4 Nov 2021, the Diwali Muhurat Thursday; the real expiry was 3 Nov).", "",
          f"## K4's filter (chosen on {k4['window'][0]} - {k4['window'][1]}, synthetic, before the run)", "",
          "| filter | trades | net | per trade |", "|---|---|---|---|"]
    for f, m in k4["menu"].items():
        R.append(f"| {f} | {m['trades']} | {rs(m['net'])} | {rs(m['per_trade'])} |")
    R += ["", f"Chosen: **{k4['choice']}** (F0 none, F1 skip weekend / holiday nights, F2 with the 200-day average, F3 skip "
          "high VIX, F4 skip 1.5% days, F5 extreme close only).", "", "## Every idea in detail", ""]
    for i, r in ideas.items():
        R += [f"### {i}: {r['name']}", "", f"Timing {r['timing']} ({TIMING_WORDS[r['timing']]}); calls {r['calls']}, puts "
              f"{r['puts']} (all eras).", ""] + era_table(r) + [""]
        R.append("| real era | net at 2x slippage | worst day | months up / months |")
        R.append("|---|---|---|---|")
        for e in K.REAL_ERAS:
            s, st = r["eras"][e], r["stress"][e]
            R.append(f"| {e} | {rs(st['net'])} | {rs(s['worst_day'])} | {s['months_up']} / {s['months']} |")
        pr = r["pooled_real"]
        cc = r["eras_calendar_clock"]
        R += ["", f"- Pooled real: {pr['trades']} trades, {pr['win']:.1f}% won, {rs(pr['net'])}, avg win {rs(pr['avg_win'])} / "
              f"avg loss {rs(pr['avg_loss'])}, max drawdown {rs(pr['max_dd'])}, worst month {rs(pr['worst_month'])}.",
              f"- Twins (random day and side, same exit; pool days A / B / C {r['pool_days']['A']} / {r['pool_days']['B']} / "
              f"{r['pool_days']['C']}): mean {rs(r['twin_mean'])}, sd {rs(r['twin_sd'])}; empirical p {r['p_emp']:.4f}, "
              f"normal-tail p {r['p_norm']:.2g}; BH q {r['q']:.3g} (m = {d['m_total']:,}).",
              f"- Real exits: {', '.join(f'{k} {v}' for k, v in sorted(r['exits'].items()))}.",
              "- Pre-registered calendar clock, synthetic eras: " + "; ".join(
                  f"{e} {cc[e]['trades']} trades {rs(cc[e]['net'])}" for e in K.SYN_ERAS if cc[e]['trades']) +
              (". Same verdict." if bool(r["fails_calendar_clock"]) != r["reliable"] else ". VERDICT WOULD DIFFER."),
              "", verdict(r), breach_line(r), ""]
    R += ["## Multiple testing", "",
          f"BH over m = {d['m_total']:,}: the {K.N_PRIOR:,} entry files at the branch base count as p = 1, plus R192's 16 "
          "tests (K1-K11, K4's six menu members counted separately; the five unchosen members count as p = 1). The smallest "
          "normal-tail p would need to be below about 2e-5 to reach q <= 0.10.", ""]
    (K.RESULTS / "report.md").write_text("\n".join(R))

    dec = dict(study="R192", outcome="reliable: " + ", ".join(passed) if passed else "nothing reliable",
               k4_filter=d["k4_filter"], m_total=d["m_total"], calibration=dict(a=cal["a"], w=cal["w"]),
               ideas={i: dict(name=r["name"], reliable=r["reliable"], fails=r["fails"], p_emp=r["p_emp"], q=r["q"],
                              eras={e: {k: r["eras"][e][k] for k in ("trades", "win", "net", "net_wo_top5", "max_dd",
                                                                    "worst_month", "avg_win", "avg_loss")} for e in K.ERAS})
                      for i, r in ideas.items()})
    (HERE / "decision.json").write_text(json.dumps(dec, indent=1))
    print("written summary.md, report.md, decision.json")
