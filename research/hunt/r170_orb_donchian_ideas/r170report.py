"""R170 report: the gates (PREREG section 4) over results/wf.json and results/final.json -> decision.json,
raw_pvalues.csv, tables.md and one experiment entry per arm x change x lots (research/experiments/entries/r170-*.yaml).

    python r170report.py

13 Nov 2026 (R170).
"""
from __future__ import annotations

import csv
import json

import numpy as np
import yaml

import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import r170lib as X  # noqa: E402
import r169cfg as K  # noqa: E402
import r170study as S  # noqa: E402

NAMES = {
    "E1": "STRONG: signal bar closes in its outer 30% (R161 E1)", "E3": "BIGBAR: signal bar >= 1 ATR5 (R161 E3)",
    "E4": "FAR: >= 0.2 ATR5 beyond the edge (R161 E4)", "E5": "SQUEEZE: range width <= its 100-session median (R161 E5)",
    "E6": "HTF15: 15-min Donchian midline agrees (R161 E6)", "E7": "HTF60: 60-min Donchian midline agrees (R161 E7)",
    "E8": "PDR: beyond yesterday's high / low (R161 E8, R166)", "E10": "VIXWAY: VIX moving the trade's way (R161 E10)",
    "E11": "BREADTH: 4 of 5 indices agree (R161 E11)", "E12": "NOLUNCH: no 11:30-13:29 entries (R161 E12)",
    "E13": "PASTHOUR: beyond the first hour's range (R161 E13)", "P5": "PIV: no floor pivot within 0.25% ahead (R155 F5)",
    "P8": "EMA20 agrees (R155 F8)", "P9": "Supertrend (10, 3) agrees (R155 F9)", "P10": "TWAP side agrees (R155 F10)",
    "P13": "FT3: not price and OI both falling (R155 F13 / R144c)", "P12": "PCR change agrees (R155 F12)",
    "P14": "WKA: wick against < 50% (R155 F14)", "P16": "WKF: rejection wick for the trade (R155 F16)",
    "P18": "BODY: body >= 60% the trade's way (R155 F18)", "V1": "VOL: option volume not below usual (R166 V1, fake volume)",
    "V2": "CLIMAX: skip a volume climax (R166 V2)", "I1": "IVDN: skip when the option's IV fell (R166 E1, fake buyer)",
    "I2": "OTHER: skip when the other side got dearer (R166 E2, fake seller)",
    "T1": "OPP30: skip after an opposite break within 30 min (R166 F1, trap)", "T2": "OUTSIDE: skip an outside bar (R166 F2)",
    "D1": "DB1: delta 0.45-0.55 (R165)", "D2": "DB2: delta 0.55-0.65 (R165)", "O1": "OF1: option order-flow proxy agrees (R165)",
    "K1": "TREES picker (R163 G1)", "K2": "NEURAL picker (R163 N1)",
    "S1": "ALLSR: no S/R level of the full map within 0.25% ahead (R155 F6, Boss)",
    "S2": "ROOM: more room ahead than behind, all levels (Boss)", "S3": "CLEAR: not boxed in by levels both ways (Boss)",
    "A1": "BANDVOL: actual option volume at or above usual (Boss)", "Q1": "NOSHORT: no short build-up in the option (Boss)",
    "B15": "BE15: breakeven once +15% (R162)", "B20": "BE20: breakeven once +20% (R162)", "LF1": "LF1 early lock steps (R162)",
    "LF2": "LF2 tight lock steps (R162)", "T45": "T45: out at 45 min unless +5% (R162)", "NIP30": "NIP30: out at 30 min if below the fill (R162)",
    "IXA": "IXA: index stop at the signal bar added (R162)", "Q2K": "+Rs 2k / -Rs 2k (R152)", "QT2K": "+Rs 2k target (R152)",
    "QS2K": "-Rs 2k stop (R152)", "LCK": "-Rs 2k stop + lock Rs 1k after +2k (R153 C1)", "DCAP": "day cap -Rs 4,000 (R153 C2)",
    "VOLX": "index ATR chandelier, tighten-only (R167 VOLX)", "VOLP": "premium ATR chandelier, tighten-only (R167 VOLP)",
    "MOM": "1-min Supertrend turn exit (R167 MOM)", "G1R": "breakeven once +Rs 1,500 (R167 G1)",
    "G2R": "floor +Rs 500, trail Rs 1,000 once +Rs 1,500 (R167 G2)", "FK3": "fake breakout exit, 3 min (R166)",
    "FK5": "fake breakout exit, 5 min (R166)", "Z1": "-20 / +20 points (Boss: 20 max)", "Z2": "-20 stop, today's target (Boss)",
    "Z3": "-15 / +20 points (Boss)", "ITM2": "2-ITM strike (R162)", "D70": "strike by delta 0.70 (R162 / R165)",
    "IXR": "index stop REPLACES the -40 stop (R162 IXR)",
    "C1": "C1: best two singles (chosen on design before each quarter)", "C2": "C2: best three singles",
    "C3": "C3: best filter + best exit",
}
ORDER = list(X.SINGLES) + ["C1", "C2", "C3"]


def risk_class(changes) -> str:
    if changes and all(c in X.FILTERS + X.EXITS for c in changes):
        return "lowers risk only (would go to shadow first)"
    return "changes entries / size (would need Boss's explicit choice)"


def rs(x) -> str:
    return ("-" if x < 0 else "") + "Rs " + f"{abs(x):,.0f}"


def main():
    wf = json.loads((S.RESULTS / "wf.json").read_text())
    fn = json.loads((S.RESULTS / "final.json").read_text())
    tests, pv = [], []
    for arm in K.ARMS:
        for lots in K.LOTS:
            key = f"{arm}|{lots}"
            for c in ORDER:
                tests.append((arm, lots, c))
                pv.append(fn[key]["rows"][c].get("twin_p", 1.0))
    q = X.bh_total(pv, S.M_PRIOR + len(tests))
    q_alone = X.bh_total(pv, len(tests))
    dec, rows_csv = {}, []
    for (arm, lots, c), p, qq, qa in zip(tests, pv, q, q_alone):
        key = f"{arm}|{lots}"
        bw, bf = wf[key]["rows"]["B0"]["score"], fn[key]["rows"]["B0"]["score"]
        sw, sf = wf[key]["rows"][c]["score"], fn[key]["rows"][c]["score"]
        gw, brw = X.gate1(sw, bw)
        gf, brf = X.gate1(sf, bf)
        changes = (c,) if c in X.SINGLES else tuple(fn[key]["combos"]["final"]["chosen"][c])
        why = []
        if not gw:
            why.append("WF: neither net up with drawdown not worse, nor +5 win points with net not lower")
        if not gf:
            why.append("final: neither net up with drawdown not worse, nor +5 win points with net not lower")
        if p > K.P_MAX:
            why.append(f"twin p {p:.3g} > 0.05")
        if qq > K.Q_MAX:
            why.append(f"BH q {qq:.3g} > 0.10 (m {S.M_PRIOR + len(tests)})")
        if sf["trades"] < K.MIN_FINAL:
            why.append(f"{sf['trades']} final trades < 40")
        if not X.within_limits(sw) or not X.within_limits(sf):
            why.append("outside Boss's limits (-Rs 40k drawdown / -Rs 13k month)")
        ok = not why
        dec[f"{key}|{c}"] = dict(arm=arm, lots=lots, change=c, changes=list(changes), passed=ok, why=why, p=p, q=float(qq),
                                 q_alone=float(qa), branch_wf=brw, branch_final=brf, risk=risk_class(changes))
        rows_csv.append(dict(study="R170", arm=K.ARM_NAME[arm], change=c, changes="+".join(changes), lots=lots, p=round(p, 6),
                             metric=fn[key]["rows"][c].get("twin_metric", ""), final_trades=sf["trades"],
                             final_win=round(sf["win"], 4), b0_final_win=round(bf["win"], 4), final_net=round(sf["net"]),
                             b0_final_net=round(bf["net"]), q_r170_m=round(float(qq), 6), q_r170_alone=round(float(qa), 6),
                             passed=ok))
    (S.RESULTS / "decision.json").write_text(json.dumps(dict(m_total=S.M_PRIOR + len(tests), tests=len(tests),
                                                              passed=[k for k, v in dec.items() if v["passed"]], tests_detail=dec),
                                                         indent=1))
    with open(S.RESULTS / "raw_pvalues.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows_csv[0]))
        w.writeheader()
        w.writerows(rows_csv)
    tables(wf, fn, dec)
    entries(wf, fn, dec)
    print("passed:", [k for k, v in dec.items() if v["passed"]], "min q", float(np.min(q)), "min p", float(np.min(pv)))


def line(s: dict) -> str:
    return (f"{s['trades']} | {s['win'] * 100:.1f}% | {s['be_win'] * 100:.1f}% | {rs(s['net'])} | {rs(s['per_session'])} | "
            f"{rs(s['worst_day'])} | {rs(s['worst_month'])} | {rs(s['max_dd'])} | {rs(s['net_wo5'])}")


def tables(wf, fn, dec):
    out = ["# R170 tables (BANKNIFTY, dated Zerodha charges, R17 fills sized to the order)", "",
           "WF = walk-forward window Apr 2025 - Mar 2026 (each quarter fitted only on earlier data). Final = 1 Apr - 12 Oct "
           "2026, read once (read by many earlier studies: weak evidence). Columns: trades | win | break-even win | net | net "
           "per session | worst day | worst month | max drawdown | net without the 5 best.", ""]
    for arm in K.ARMS:
        for lots in K.LOTS:
            key = f"{arm}|{lots}"
            out += [f"## {K.ARM_NAME[arm]}, {lots} lot{'s' if lots > 1 else ''}", "",
                    "| change | WF | final | twin p | q | verdict |", "|---|---|---|---|---|---|",
                    f"| B0 today | {line(wf[key]['rows']['B0']['score'])} | {line(fn[key]['rows']['B0']['score'])} | | | |"]
            for c in ORDER:
                d = dec[f"{key}|{c}"]
                nm = NAMES[c] if c in X.SINGLES else f"{c} = {'+'.join(d['changes']) or 'B0 (none qualified)'}"
                out.append(f"| {nm} | {line(wf[key]['rows'][c]['score'])} | {line(fn[key]['rows'][c]['score'])} | "
                           f"{d['p']:.3g} | {d['q']:.3g} | {'PASS' if d['passed'] else 'fail: ' + '; '.join(d['why'][:2])} |")
            out.append("")
            out.append("Combinations chosen (design only): " + "; ".join(
                f"{k}: {v['chosen']}" for k, v in fn[key]["combos"].items()) + "; WF quarters: " + "; ".join(
                f"{k}: {v['chosen']}" for k, v in wf[key]["combos"].items()))
            out.append("")
    (S.RESULTS / "tables.md").write_text("\n".join(out))


def entries(wf, fn, dec):
    ed = K.ROOT / "research" / "experiments" / "entries"
    for k, d in dec.items():
        arm, lots, c = d["arm"], d["lots"], d["change"]
        key = f"{arm}|{lots}"
        sw, sf = wf[key]["rows"][c]["score"], fn[key]["rows"][c]["score"]
        bw, bf = wf[key]["rows"]["B0"]["score"], fn[key]["rows"]["B0"]["score"]
        eid = f"r170-{arm.replace('_', '-')}-{c.lower()}-{lots}lot"
        e = dict(
            id=eid,
            title=f"R170 {c} on {K.ARM_NAME[arm]} (BANKNIFTY), {lots} lot: {NAMES[c]}",
            family="orb", date="2026-11-13", doc="research/hunt/r170_orb_donchian_ideas/results/report.md",
            hypothesis=(f"Boss (13 Nov): try every other Donchian-study rule on ORB. {NAMES[c]} raises net (drawdown not worse) or the "
                        "win rate by >= 5 points (net not lower) vs the arm's current rules, in walk-forward AND final, beats "
                        f"random twins (p <= 0.05, BH q <= 0.10, m = {S.M_PRIOR + 576}), >= 40 final trades, within -Rs 40k "
                        "drawdown / -Rs 13k month."),
            data_version="Drive lake gdrive:IraAlgo-data-lake (index_1m BANKNIFTY + INDIAVIX, options_rolling_1m BANKNIFTY WEEK) "
                         "copied 13 Nov 2026; R3 session store rebuilt; R123 N1 prediction rows (derived/r123/cleaned)",
            period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                        holdout="2026-04-01..2026-10-12 another look by R170 (read by R169 today and many earlier studies)"),
            instruments=["BANKNIFTY"],
            features=["signal source: the index (BANKNIFTY 5-minute bars); option chain / network only as context"],
            model_or_rule=f"{K.ARM_NAME[arm]} with {'+'.join(d['changes']) or 'nothing (no combination qualified)'}; {lots} lot(s)",
            settings="research/hunt/r170_orb_donchian_ideas/PREREG.md (+ Amendments A, B); r170lib.py; r170study.py",
            prereg="research/hunt/r170_orb_donchian_ideas/PREREG.md", variants_tried=1, holdout_read=True,
            verdict="rejected" if not d["passed"] else "inconclusive",
            results=dict(
                summary=(f"WF: {sw['trades']} trades, win {sw['win'] * 100:.1f}% (today {bw['win'] * 100:.1f}%), net {rs(sw['net'])} "
                         f"(today {rs(bw['net'])}), max DD {rs(sw['max_dd'])}. Final: {sf['trades']} trades, win "
                         f"{sf['win'] * 100:.1f}% (today {bf['win'] * 100:.1f}%), net {rs(sf['net'])} (today {rs(bf['net'])}), worst "
                         f"month {rs(sf['worst_month'])}, max DD {rs(sf['max_dd'])}. Twin p {d['p']:.3g}, BH q {d['q']:.3g}. "
                         + ("PASSED every gate (a candidate only: nothing changes in the app; see APP_SPEC.md)." if d["passed"] else "Fails: " + "; ".join(d["why"]) + ".")),
                costs="dated Zerodha charges on the whole quantity, R17 fills sized to the order"),
        )
        (ed / f"{eid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    main()
