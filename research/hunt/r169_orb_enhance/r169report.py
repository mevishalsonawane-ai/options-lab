"""R169 report: the gates (PREREG section 4) over results/wf.json and results/final.json -> decision.json,
raw_pvalues.csv, tables.md and one experiment entry per arm x change x lots (research/experiments/entries/r169-*.yaml).

    python r169report.py

13 Nov 2026.
"""
from __future__ import annotations

import csv
import json

import numpy as np
import yaml

import r169cfg as K
import r169lib as X

NAMES = {
    "F1": "PICK: logistic trade picker on ORB's own features (R151 / R163)", "F2": "NET: R123 N1 network EV > 0 (R133)",
    "F3": "BUSY: 5-min ATR at or above its 20-session median (R161 E9)", "F4": "HTF: agrees with the 60-minute move (R161)",
    "F5": "GAP: skip against a big gap / with a small gap (R160, R146, MARKET_HOW)",
    "F6": "EXT15: the edge set in the first 15 minutes (MARKET_HOW)", "F7": "FVG: displacement gap on 5-min (ICT, R130-R139)",
    "F8": "ROOM: yesterday's high / low within 30 points (Liquidity 15+5)", "F9": "NOEXP: no expiry-day trades (Liquidity 15+5)",
    "X1": "BE10: breakeven once +10% (R162)", "X2": "T40: out at 40 min unless +5% (R162)",
    "X3": "T20: out at 20 min unless +5% (Liquidity 15+5)", "X4": "G3: 3-minute index-low trail once +50% of target (R167)",
    "X5": "SRT: out at yesterday's high / low (R150 levels)", "X6": "FAIL: out when a 5-min bar closes back through the range edge",
    "S1": "D60: strike by delta 0.60 (R162 / R165)", "S2": "D50: strike by delta 0.50 (R165)", "S3": "ITM1: 1-ITM strike (Liquidity)",
    "W1": "WAIT1: wait one bar for confirmation (R161 E2)", "L1": "LIQX: Liquidity 15+5's exits (-15%, 20-min time stop, no target)",
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
    wf = json.loads((K.RESULTS / "wf.json").read_text())
    fn = json.loads((K.RESULTS / "final.json").read_text())
    tests, pv = [], []
    for arm in K.ARMS:
        for lots in K.LOTS:
            key = f"{arm}|{lots}"
            for c in ORDER:
                tests.append((arm, lots, c))
                pv.append(fn[key]["rows"][c].get("twin_p", 1.0))
    q = X.bh_total(pv, K.M_PRIOR + len(tests))
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
            why.append(f"BH q {qq:.3g} > 0.10 (m {K.M_PRIOR + len(tests)})")
        if sf["trades"] < K.MIN_FINAL:
            why.append(f"{sf['trades']} final trades < 40")
        if not X.within_limits(sw) or not X.within_limits(sf):
            why.append("outside Boss's limits (-Rs 40k drawdown / -Rs 13k month)")
        ok = not why
        dec[f"{key}|{c}"] = dict(arm=arm, lots=lots, change=c, changes=list(changes), passed=ok, why=why, p=p, q=float(qq),
                                 q_alone=float(qa), branch_wf=brw, branch_final=brf, risk=risk_class(changes))
        rows_csv.append(dict(study="R169", arm=K.ARM_NAME[arm], change=c, changes="+".join(changes), lots=lots, p=round(p, 6),
                             metric=fn[key]["rows"][c].get("twin_metric", ""), final_trades=sf["trades"],
                             final_win=round(sf["win"], 4), b0_final_win=round(bf["win"], 4), final_net=round(sf["net"]),
                             b0_final_net=round(bf["net"]), q_r169_m=round(float(qq), 6), q_r169_alone=round(float(qa), 6),
                             passed=ok))
    (K.RESULTS / "decision.json").write_text(json.dumps(dict(m_total=K.M_PRIOR + len(tests), tests=len(tests),
                                                              passed=[k for k, v in dec.items() if v["passed"]], tests_detail=dec),
                                                         indent=1))
    with open(K.RESULTS / "raw_pvalues.csv", "w", newline="") as f:
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
    out = ["# R169 tables (BANKNIFTY, dated Zerodha charges, R17 fills sized to the order)", "",
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
    (K.RESULTS / "tables.md").write_text("\n".join(out))


def entries(wf, fn, dec):
    ed = K.ROOT / "research" / "experiments" / "entries"
    for k, d in dec.items():
        arm, lots, c = d["arm"], d["lots"], d["change"]
        key = f"{arm}|{lots}"
        sw, sf = wf[key]["rows"][c]["score"], fn[key]["rows"][c]["score"]
        bw, bf = wf[key]["rows"]["B0"]["score"], fn[key]["rows"]["B0"]["score"]
        eid = f"r169-{arm.replace('_', '-')}-{c.lower()}-{lots}lot"
        e = dict(
            id=eid,
            title=f"R169 {c} on {K.ARM_NAME[arm]} (BANKNIFTY), {lots} lot: {NAMES[c]}",
            family="orb", date="2026-11-13", doc="research/hunt/r169_orb_enhance/results/report.md",
            hypothesis=(f"Boss (13 Nov): use the new knowledge to enhance ORB. {NAMES[c]} raises net (drawdown not worse) or the "
                        "win rate by >= 5 points (net not lower) vs the arm's current rules, in walk-forward AND final, beats "
                        f"random twins (p <= 0.05, BH q <= 0.10, m = {K.M_PRIOR + 207}), >= 40 final trades, within -Rs 40k "
                        "drawdown / -Rs 13k month."),
            data_version="Drive lake gdrive:IraAlgo-data-lake (index_1m BANKNIFTY + INDIAVIX, options_rolling_1m BANKNIFTY WEEK) "
                         "copied 13 Nov 2026; R3 session store rebuilt; R123 N1 prediction rows (derived/r123/cleaned)",
            period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                        holdout="2026-04-01..2026-10-12 read once by R169 (read by many earlier studies)"),
            instruments=["BANKNIFTY"],
            features=["signal source: the index (BANKNIFTY 5-minute bars); option chain / network only as context"],
            model_or_rule=f"{K.ARM_NAME[arm]} with {'+'.join(d['changes']) or 'nothing (no combination qualified)'}; {lots} lot(s)",
            settings="research/hunt/r169_orb_enhance/PREREG.md section 3; r169lib.py; r169cfg.py",
            prereg="research/hunt/r169_orb_enhance/PREREG.md", variants_tried=1, holdout_read=True,
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
