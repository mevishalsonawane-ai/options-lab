"""R190 report (PREREG.md sections 5-6, 9): BH across the log, the RELIABLE gate, summary.md (for Boss), report.md and
decision.json. Reads results/eras_bc.json and results/era_a.json (era A shows as "not read yet" until it exists).
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r190cfg as K  # noqa: E402

import r190lib as G  # noqa: E402

ERAS = ("A", "B", "C")


def rs(x) -> str:
    if x is None:
        return "-"
    return f"{'-' if x < 0 else ''}Rs {abs(x):,.0f}"


def load() -> tuple:
    bc = json.loads((K.RESULTS / "eras_bc.json").read_text())
    a = json.loads(K.ERA_A_JSON.read_text()) if K.ERA_A_JSON.exists() else None
    return bc, a


def era_of(bc, a, cid, e):
    src = a if e == "A" else bc
    if src is None:
        return "pending"
    return src.get(cid, {}).get(e)


def decide(bc, a) -> dict:
    p_norm, p_emp = [], {}
    for cid in K.CANDIDATES:
        blk = era_of(bc, a, cid, "A")
        tw = blk.get("twin") if isinstance(blk, dict) else None
        p_norm.append(tw["p_norm"] if tw else 1.0)
        p_emp[cid] = tw["p"] if tw else None
    q = G.bh_total(p_norm, K.M_TOTAL)
    out = {}
    for i, cid in enumerate(K.CANDIDATES):
        eras = {}
        for e in ERAS:
            blk = era_of(bc, a, cid, e)
            eras[e] = blk["one_lot"] if isinstance(blk, dict) and "one_lot" in blk else None
        fails = G.gate(eras, p_emp[cid], float(q[i]))
        breach = {e: G.account_breach(eras[e]) for e in ERAS if eras[e]}
        out[cid] = dict(name=K.NAMES[cid], reliable=not fails and a is not None, fails=fails, era_a_twin_p=p_emp[cid],
                        era_a_twin_p_norm=p_norm[i], bh_q=float(q[i]), account_stop_breaches=breach,
                        eras={e: eras[e] for e in ERAS})
    return out


ROW = "| {era} | {trades} | {win} | {net} | {aw} / {al} | {wo} | {wd} | {dd} | {wm} |"
HEAD = ("| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | "
        "worst month |\n|---|---|---|---|---|---|---|---|---|")


def era_rows(bc, a, cid, key="one_lot") -> list:
    lines = [HEAD]
    for e in ERAS:
        blk = era_of(bc, a, cid, e)
        if blk == "pending":
            lines.append(f"| {e} | era A not read yet | | | | | | | |")
            continue
        if blk is None:
            lines.append(f"| {e} | no data (untestable) | | | | | | | |")
            continue
        if "not_run" in blk:
            lines.append(f"| {e} | not run: {blk['not_run']} | | | | | | | |")
            continue
        s = blk.get(key)
        if not s:
            lines.append(f"| {e} | n/a | | | | | | | |")
            continue
        lines.append(ROW.format(era=e, trades=s["trades"], win=f"{s['win']:.1f}%", net=rs(s["net"]), aw=rs(s["avg_win"]),
                                al=rs(s["avg_loss"]), wo=rs(s["net_wo_top5"]), wd=rs(s["worst_day"]), dd=rs(s["max_dd"]),
                                wm=rs(s["worst_month"])))
    return lines


def verdict_line(d: dict, a) -> str:
    if a is None:
        return "**Verdict: pending (era A not read yet).**"
    if d["reliable"]:
        return "**Verdict: RELIABLE** (passed every item of the gate)."
    return "**Verdict: NOT RELIABLE.** Failed: " + "; ".join(d["fails"]) + "."


def _s(dec, cid, e):
    return dec[cid]["eras"].get(e) or {}


def plain(dec) -> list:
    """The plain reading for Boss (numbers from decision.json)."""
    n = {e: _s(dec, "N1N", e) for e in ERAS}
    b = {e: _s(dec, "N1B", e) for e in ERAS}
    lq = {e: _s(dec, "L1", e) for e in ERAS}
    d1, d2 = _s(dec, "D1", "B"), _s(dec, "D2", "B")
    v = {e: _s(dec, "V1", e) for e in ERAS}
    return [
        "**What this means, in plain words:**", "",
        f"- **Night (R3) is still the most consistent rule we have.** It made money in all three eras: on NIFTY alone "
        f"{rs(n['A'].get('net'))} (era A, {n['A'].get('trades')} trades), {rs(n['B'].get('net'))} (B), "
        f"{rs(n['C'].get('net'))} (C), winning {n['A'].get('win', 0):.0f}% / {n['B'].get('win', 0):.0f}% / "
        f"{n['C'].get('win', 0):.0f}% of trades; the NIFTY + BANKNIFTY book made {rs(b['B'].get('net'))} (B) and "
        f"{rs(b['C'].get('net'))} (C). But it is NOT proven: in era A it was not clearly better than random nights "
        f"(twin p {dec['N1N']['era_a_twin_p']:.3f}), its profit there came from its 5 best nights, and after counting "
        "the ~4,760 ideas tried it is not significant. Its drawdowns and worst months also break the account stop at 1 lot "
        "each (the book's worst month in era C was " + rs(b['C'].get('worst_month')) + ").",
        f"- **Liquidity 15+5 made money in both eras it can be tested on** ({rs(lq['B'].get('net'))} in B at "
        f"{lq['B'].get('win', 0):.0f}% wins, {rs(lq['C'].get('net'))} in C at {lq['C'].get('win', 0):.0f}%), but era A "
        "has no BANKNIFTY / FINNIFTY / MIDCPNIFTY option data, so it cannot meet the gate.",
        f"- **The R72 Donchian arms lose before Nov 2024, again:** R72 {rs(d1.get('net'))} in era B "
        f"({d1.get('trades')} trades), with R151's picker {rs(d2.get('net'))}. Their profit is a recent-regime story. "
        "(The picker's era-C numbers are mostly in-sample: it was trained on Nov 2024 - Mar 2026.)",
        f"- **The new outside ideas did not hold up on NIFTY options.** End-of-day momentum lost in every era. The "
        f"noise-area breakout made {rs(v['A'].get('net'))} (A) and {rs(v['B'].get('net'))} (B) but lost "
        f"{rs(v['C'].get('net'))} in the recent era, and wins only about {v['B'].get('win', 0):.0f}% of trades. The trend / VIX "
        "overlay did not rescue either.",
        "- **High win rate and reliable profit did not come together anywhere.** The best win rates (Night, 53-58%) come "
        "with an average loss about as big as the average win.",
    ]


def summary(bc, a, dec) -> str:
    rel = [c for c in K.CANDIDATES if dec[c]["reliable"]]
    L = ["# R190 best strategy hunt: summary for Boss", "",
         f"Written {datetime.now(timezone.utc):%d %b %Y}. All money at 1 lot after dated Zerodha charges and slippage, "
         "real 1-minute option prices, option BUYING only.", ""]
    if a is None:
        L += ["**Outcome: era A (Aug 2020 - Jul 2021) has not been read yet; no verdict.**", ""]
    elif rel:
        L += [f"**Outcome: {len(rel)} of {len(K.CANDIDATES)} candidates passed the RELIABLE gate: "
              + ", ".join(f"{c} ({K.NAMES[c]})" for c in rel) + ".**", ""]
    else:
        L += [f"**Outcome: none of the {len(K.CANDIDATES)} candidates passed the RELIABLE gate.** Nothing here is a "
              "\"great and reliable\" strategy by the rules we set before looking.", ""]
    if a is not None:
        L += plain(dec) + [""]
    L += ["The gate (fixed before any number): profit in ALL THREE eras with 40+ trades each, still profit without the "
          "best 5 trades, beats random entries in era A (p < 0.05), and survives the correction for the ~4,760 ideas "
          "logged so far (BH q <= 0.10).", "",
          "Eras: **A** = Aug 2020 - Jul 2021 (the oldest option data, NIFTY only, read once at the end; note: an Oct "
          "2026 hunt had used it for other rules, so it is not virgin data); **B** = Dec 2021 - Oct 2024; **C** = "
          "Nov 2024 - 13 Nov 2026.", "",
          "Account stop for reference: -Rs 40,000 drawdown or -Rs 13,000 in a month.", ""]
    for cid in K.CANDIDATES:
        d = dec[cid]
        L += [f"## {cid}: {K.NAMES[cid]}", ""] + era_rows(bc, a, cid) + [""]
        L.append(verdict_line(d, a))
        br = {e: v for e, v in d["account_stop_breaches"].items() if v}
        if br:
            L.append("Account stop: " + "; ".join(f"era {e}: {', '.join(v)}" for e, v in br.items()) + ".")
        L.append("")
    return "\n".join(L) + "\n"


def report(bc, a, dec) -> str:
    L = ["# R190 best strategy hunt: full report", "",
         "Pre-registration: `PREREG.md` (committed before any number). Code: `r190data.py` (flat lake), `r190night.py` "
         "(Night via R147's builder), `r190arms.py` (Liquidity and R72 via R115 / R101's engine on R51's clean stores), "
         "`r190rules.py` (new rules), `r190study.py` (eras, twins), `r190report.py` (this file).", "",
         f"BH family: m = {K.M_TOTAL:,} ({K.N_PRIOR:,} earlier entries + {len(K.CANDIDATES)} R190 candidates); "
         "R190's q uses each candidate's era-A normal-tail twin p (untestable = 1).", ""]
    par = json.loads((K.RESULTS / "arms_parity.json").read_text()) if (K.RESULTS / "arms_parity.json").exists() else {}
    L += ["## Data, checks and caveats", "",
          "- Data: the Drive lake (copied 17 Nov 2026, incl. the 20 Oct - 13 Nov 2026 fill) plus dhan-data NIFTY WEEK "
          "options 3 Aug 2020 - 30 Jul 2021 (same endpoint; 7,875 / 7,875 rows identical on 3 Aug 2021). Era A has NIFTY "
          "only; BANKNIFTY / FINNIFTY options start Aug 2021, MIDCPNIFTY 2022, SENSEX 2023.",
          "- Era A is not virgin: the Oct 2026 h-series hunt (h22-h47) used NIFTY options from Aug 2020 as design data, "
          "including h31's close-following overnight rules that Night descends from. For Night, era A is a consistency "
          "check, not out-of-sample proof. Era A was still locked here (flag committed in 823379785) and read once.",
          "- Engine parity: Liquidity / R72 on R190's stores reproduce the published windows: "
          + "; ".join(f"{k}: {v['trades']} trades {rs(v['net'])} (published {v['published']})" for k, v in par.items())
          + ". Night NIFTY on 2022-24 reproduces R147's design exactly (247 trades, Rs 200 a trade at app costs); "
          "BANKNIFTY gives 257 trades and Rs 306 a trade against R147's 256 and Rs 296 (one extra priced night; not chased).",
          "- R72 + picker on Apr - 13 Oct 2026: 651 trades +Rs 2.20 lakh against R151's published 652 / +Rs 2.19 lakh "
          "(R72 1,200 vs 1,202): the store now flags 13 Oct 2026 as an expiry day (the old lake end could not).",
          "- D2's era-C numbers are mostly in-sample (the frozen picker was trained on Nov 2024 - Mar 2026 trades).",
          "- V1 uses TWAP, not VWAP (no index volume; no futures volume history). Intraday fills: R17 liq; Night: app "
          "bps + h24 half-spread; all with dated charges. Max-fit lots size each trade to Rs 56,000 of premium.",
          "- Clarification of PREREG's ATM (no look-ahead): M / V buy the strike that was ATM on the decision minute "
          "(14:49 for M; the minute before each mark for V), as R51's engine does, not the 14:50 label. The Night book's "
          "max-fit column sizes each leg to Rs 56,000 on its own (two legs on one night would need more cash).",
          "- Liquidity / R72 twins were not run (era A untestable; the gate's twin test is era A only).", ""]
    meta = bc.get("_meta", {})
    if meta.get("D2_note"):
        L += [f"D2 (picker): {meta['D2_note']}.", ""]
    for cid in K.CANDIDATES:
        d = dec[cid]
        L += [f"## {cid}: {K.NAMES[cid]}", "", "**1 lot**", ""] + era_rows(bc, a, cid)
        for key, title in (("stress_2x", "twice the slippage"), ("max_fit", "max lots that fit Rs 56k")):
            if any(isinstance(era_of(bc, a, cid, e), dict) and era_of(bc, a, cid, e).get(key) for e in ERAS):
                L += ["", f"**{title}**", ""] + era_rows(bc, a, cid, key)
        L += ["", "| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |", "|---|---|---|---|---|---|"]
        for e in ERAS:
            blk = era_of(bc, a, cid, e)
            if not isinstance(blk, dict) or "one_lot" not in blk:
                continue
            tw = blk.get("twin") or {}
            s = blk["one_lot"]
            pe = "-" if not tw else "{:.4f}".format(tw["p"])
            pn = "-" if not tw else "{:.2e}".format(tw["p_norm"])
            tm = "-" if not tw else rs(tw["mean"])
            yrs = ", ".join("{}: {}".format(y, rs(v)) for y, v in blk.get("by_year", {}).items())
            L.append(f"| {e} | {pe} | {pn} | {tm} | {yrs} | {s['months_up']} of {s['months']} |")
        bi = {e: era_of(bc, a, cid, e).get("by_index") for e in ERAS if isinstance(era_of(bc, a, cid, e), dict)
              and era_of(bc, a, cid, e).get("by_index") and len(era_of(bc, a, cid, e)["by_index"]) > 1}
        if bi:
            L += ["", "By index (1 lot): " + "; ".join(
                f"era {e}: " + ", ".join(f"{u} {s['trades']} tr {s['win']:.0f}% {rs(s['net'])}" for u, s in v.items())
                for e, v in bi.items())]
        L += ["", f"BH q {d['bh_q']:.3g}; era-A twin p {d['era_a_twin_p']}.", verdict_line(d, a), ""]
    return "\n".join(L) + "\n"


def main():
    bc, a = load()
    dec = decide(bc, a)
    (K.RESULTS / "summary.md").write_text(summary(bc, a, dec))
    (K.RESULTS / "report.md").write_text(report(bc, a, dec))
    out = dict(study="R190 best strategy hunt", written=datetime.now(timezone.utc).isoformat(), era_a_read=a is not None,
               m_total=K.M_TOTAL, reliable=[c for c in K.CANDIDATES if dec[c]["reliable"]], candidates=dec)
    (HERE / "decision.json").write_text(json.dumps(out, indent=1, default=str))
    print("reliable:", out["reliable"], flush=True)


if __name__ == "__main__":
    main()
