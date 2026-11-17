"""R186 experiment entries: one per rule (T1000, T1250, T1500, L500, LBE1000; each covers Liquidity, R72 and R51) and one
descriptive entry for the "+Rs 1,000, then a loss" count (PREREG 7a)."""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[2] / "research" / "experiments" / "entries"
RESULTS = HERE / "results"
LAB = {"T1000": "hard Rs 1,000 a lot net profit target", "T1250": "hard Rs 1,250 a lot net profit target",
       "T1500": "hard Rs 1,500 a lot net profit target",
       "L500": "Rs 500 step lock (+Rs 1,000 a lot net -> lock +Rs 500, then Rs 500 under each further Rs 500 step)",
       "LBE1000": "breakeven once +Rs 1,000 a lot net"}
LAB_B = {"P60": "60% lock of the best open P&L once +Rs 1,000 a lot net (as S5 Trail)",
         "IDX": "index lock: once +Rs 1,000 a lot net, exit when the index closes back through its level at the fill",
         "SPLIT": "split exit at 3 lots: 1 lot sold at +Rs 1,000 net, the rest moved to net breakeven"}
ARMS = ("LIQ", "R72", "R51")
DATA = ("Drive lake gdrive:IraAlgo-data-lake copied 15 Nov 2026, ending 19 Oct 2026; R51 Amendment A clean stores built to "
        "19 Oct (R106 clean_post)")
PERIOD = dict(design="none fitted here; early part 2025-10-20..2026-03-31 (R151 picker in-sample)",
              holdout="2026-04-01..2026-10-13 final (read again, data-led); year to 2026-10-19")
INSTR = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]


def rs(x):
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def main():
    r = json.loads((RESULTS / "year.json").read_text())
    d = json.loads((RESULTS / "decision.json").read_text())
    for v in LAB:
        parts = []
        for a in ARMS:
            s, f, b, g = r["year"][f"{v}@1"][a], r["final"][f"{v}@1"][a], r["year"]["BASE@1"][a], d[f"{a}:{v}"]
            parts.append(f"{a}: year {s['trades']} trades win {100 * s['win']:.1f}% {rs(s['net'])} (base {rs(b['net'])}); "
                         f"final {f['trades']} trades {rs(f['net'])} (base {rs(g['base_net_final'])}); twin p {g['twin_p']}; "
                         + ("passed" if g["passed"] else "failed: " + "; ".join(g["fails"])))
        verdict = "rejected" if not any(d[f"{a}:{v}"]["passed"] for a in ARMS) else "inconclusive"
        e = dict(
            id=f"r186-{v.lower()}", title=f"R186 {v}: {LAB[v]} on top of today's exit rule, Liquidity / R72 / R51 (last year, 1 lot)",
            family="trade-management", date="2026-11-16", doc="research/hunt/r186_fixed_profit/results/summary.md",
            hypothesis=("Boss: 'check if i had a hard 1-1.5 fix profit what will be effect on liquidity,r51,r72'." if v[0] == "T"
                        else "Boss: trades reach +Rs 1,000 / 1,500 / 2,000 a lot and then end negative; lock part of it."),
            data_version=DATA, period=PERIOD, instruments=INSTR,
            features=["signal source: the index (entries unchanged; R151 picker on R72 / R51); the exit on the option premium"],
            model_or_rule=LAB[v] + "; on top of own stop + R162 breakeven at +10% (+ R151 picker on R72 / R51); PREREG.md",
            settings="research/hunt/r186_fixed_profit/r186study.py", prereg="research/hunt/r186_fixed_profit/PREREG.md",
            variants_tried=3, results=dict(summary=" | ".join(parts) + ".",
                                           costs="dated Zerodha charges on the whole quantity; R17 fills (locks sold as stops; "
                                                 "a target fills at its price or at the open of a minute that gaps above it)"),
            holdout_read=True, verdict=verdict,
            notes=f"BH m = {d['_m']['total']}. Nothing in the app changes.")
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    parts = []
    for a in ARMS:
        g = r["giveback"][a]["year"]
        parts.append(f"{a}: {g['trades']} trades, {g['losers']} losers; {g['ge1000']['reached']} reached +Rs 1,000 a lot, "
                     f"{g['ge1000']['lost_after']} of them then lost (gave back {rs(g['ge1000']['given_back'])}); "
                     f"{100 * g['share_losers_were_1000']:.1f}% of losers had been +Rs 1,000 first; after +1,500: "
                     f"{g['ge1500']['lost_after']}, after +2,000: {g['ge2000']['lost_after']}")
    gp = RESULTS / "gap_month.json"
    extra = ""
    if gp.exists():
        gm = json.loads(gp.read_text())
        extra = " 21 Oct - 13 Nov 2026 (gap files): " + "; ".join(
            f"{a} {gm['giveback'][a]['ge1000']['lost_after']} of {gm['giveback'][a]['losers']} losers had been +Rs 1,000"
            for a in ARMS) + "."
    e = dict(
        id="r186-giveback-count", title="R186: how often a trade is +Rs 1,000 / 1,500 / 2,000 a lot and then ends with a loss (today's rule)",
        family="diagnosis", date="2026-11-16", doc="research/hunt/r186_fixed_profit/results/summary.md",
        hypothesis="Boss's observation: a lot reaches +1,000, then ends negative; +1,500 then negative; +2,000 then negative.",
        data_version=DATA + "; plus the lake's 20 Oct - 13 Nov 2026 gap files (stores built outside git)",
        period=PERIOD, instruments=INSTR,
        features=["best open P&L net on the option premium (highest premium from the fill to the minute before the exit)"],
        model_or_rule="descriptive count under the base (no test)", settings="research/hunt/r186_fixed_profit/r186study.py",
        prereg="research/hunt/r186_fixed_profit/PREREG.md", variants_tried=None,
        results=dict(summary="Year, 1 lot: " + " | ".join(parts) + "." + extra, costs="dated Zerodha charges; R17 fills"),
        holdout_read=True, verdict="not-a-test", notes="Descriptive only; not counted in BH.")
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    rb = RESULTS / "year_b.json"
    n = 6
    if rb.exists():
        b = json.loads(rb.read_text())
        db = json.loads((RESULTS / "decision_b.json").read_text())
        for v, lab in LAB_B.items():
            parts = []
            for a in ARMS:
                g = db[f"{a}:{v}"]
                k = f"{v}@{g['lots']}"
                s_, f_ = b["year"][k][a], b["final"][k][a]
                parts.append(f"{a} ({g['lots']} lot{'s' if g['lots'] > 1 else ''}): year {s_['trades']} trades win "
                             f"{100 * s_['win']:.1f}% {rs(s_['net'])} (base {rs(g['base_net_year'])}); final {rs(f_['net'])} "
                             f"(base {rs(g['base_net_final'])}); twin p {g['twin_p']}; "
                             + ("passed" if g["passed"] else "failed: " + "; ".join(g["fails"])))
            e = dict(
                id=f"r186b-{v.lower()}", title=f"R186 Amendment B {v}: {lab}, on top of today's exit rule, Liquidity / R72 / R51",
                family="trade-management", date="2026-11-16", doc="research/hunt/r186_fixed_profit/results/summary.md",
                hypothesis="Boss: other ways to stop trades that reach +Rs 1,000 / 1,500 / 2,000 a lot from ending negative.",
                data_version=DATA, period=PERIOD, instruments=INSTR,
                features=["signal source: the index (entries; R151 picker)" + ("; the exit on a 1-minute index close (IDX)"
                                                                               if v == "IDX" else "; the exit on the option premium")],
                model_or_rule=lab + "; on top of own stop + R162 breakeven at +10% (+ R151 picker); PREREG.md Amendment B",
                settings="research/hunt/r186_fixed_profit/r186study.py run-b", prereg="research/hunt/r186_fixed_profit/PREREG.md",
                variants_tried=3, results=dict(summary=" | ".join(parts) + ".",
                                               costs="dated Zerodha charges; R17 fills (locks and the index exit sold as stops)"),
                holdout_read=True, verdict="rejected" if not any(db[f"{a}:{v}"]["passed"] for a in ARMS) else "inconclusive",
                notes=f"BH m = {db['_m']['total']}. Nothing in the app changes.")
            (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
            n += 1
    print(f"{n} entries")


if __name__ == "__main__":
    main()
