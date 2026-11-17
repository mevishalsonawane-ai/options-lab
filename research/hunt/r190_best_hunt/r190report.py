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
HEAD = ("| era | trades | win % | net (1 lot) | avg win / avg loss | net without top 5 | worst day | max drawdown | "
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
            L.append(f"| {e} | {tw.get('p', '-') if not tw else f'{tw['p']:.4f}'} | "
                     f"{'-' if not tw else f'{tw['p_norm']:.2e}'} | {'-' if not tw else rs(tw['mean'])} | "
                     f"{', '.join(f'{y}: {rs(v)}' for y, v in blk.get('by_year', {}).items())} | "
                     f"{s['months_up']} of {s['months']} |")
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
