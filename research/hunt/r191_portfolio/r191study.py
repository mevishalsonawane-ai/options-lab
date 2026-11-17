"""R191 study (PREREG.md section 8). From the repo root, with R190's caches in place:

    python3 -I research/hunt/r191_portfolio/r191study.py b        # era B only -> results/era_b.json (+ the B pick)
    python3 -I research/hunt/r191_portfolio/r191study.py ca       # eras C and A, ONCE, after ERA_CA_READ.flag is committed
    python3 -I research/hunt/r191_portfolio/r191study.py report   # summary.md, report.md, decision.json

`select_era` is THE cut: phase b never lets a C or A trade out of it.
"""
from __future__ import annotations

import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))

import numpy as np  # noqa: E402

import r191lib as L  # noqa: E402

RESULTS = HERE / "results"
FLAG_CA = RESULTS / "ERA_CA_READ.flag"
ERA_B_JSON = RESULTS / "era_b.json"
ERA_CA_JSON = RESULTS / "era_ca.json"

VARIANTS = {
    "P1": (("N", "L"), L.Rules(), "Night book + Liquidity, 1 lot each, no caps"),
    "P2": (("N", "L", "R"), L.Rules(), "Night book + Liquidity + R72 picker, no caps"),
    "P3": (("N", "L"), L.Rules(one_night=True), "one Night a night + Liquidity"),
    "P4": (("N", "L"), L.Rules(one_night=True, month_halt=True), "P3 + month halt at -Rs 8k"),
    "P5": (("N", "L"), L.Rules(one_night=True, month_halt=True, brake=True), "P4 + drawdown brake at -Rs 25k"),
    "P6": (("N", "L"), L.Rules(one_night=True, month_halt=True, ec=True), "P4 + equity-curve filter (last 20 > 0)"),
    "P7": (("N", "L", "R"), L.Rules(one_night=True, month_halt=True, ec=True),
           "one Night a night + Liquidity + R72 picker + month halt + equity-curve filter"),
    "P8": (("N", "L"), L.Rules(one_night=True, month_halt=True, vix=True), "P4 + skip Night / Liquidity on high-VIX days"),
}
COMP_NAME = {"N": "Night (R3) book", "L": "Liquidity 15+5", "R": "R72 + R151 picker"}


# ================================================================================================ the cut
def select_era(streams: dict, era: str, allowed: tuple) -> dict:
    if era not in allowed:
        raise PermissionError(f"era {era} is not open in this phase (allowed {allowed})")
    return {c: [t for t in tr if t["era"] == era] for c, tr in streams.items()}


def era_ca_allowed(flag_tracked: bool, result_exists: bool) -> bool:
    """Eras C and A open once: only after the flag is committed, and never again once their result exists."""
    return flag_tracked and not result_exists


def flag_tracked() -> bool:
    r = subprocess.run(["git", "ls-files", "--error-unmatch", str(FLAG_CA)], cwd=ROOT, capture_output=True)
    return r.returncode == 0


# ================================================================================================ one era
def r0(s: dict) -> dict:
    return {k: (round(v, 2) if isinstance(v, float) else v) for k, v in s.items()}


def era_block(vid: str, streams: dict, high: set, era: str, rng) -> dict:
    comps, rules, _ = VARIANTS[vid]
    taken = L.run_variant(streams, comps, rules, high)
    s = L.stats(taken)
    tw = L.twin_nets(streams, comps, rules, high, rng)
    pe, pn = L.twin_p(s["net"], tw)
    per = {c: r0(L.stats([t for t in taken if t["comp"] == c])) for c in comps}
    cands = L.candidates(streams, comps, rules, high)
    return dict(stats=r0(s), comps=per, p_emp=pe, p_norm=pn, twin_mean=round(float(tw.mean())),
                twin_sd=round(float(tw.std(ddof=1))) if len(tw) > 1 else 0, candidates=len(cands),
                skipped=len(cands) - len(taken),
                comps_with_data=[c for c in comps if streams.get(c)])


def run_eras(eras: tuple, allowed: tuple) -> dict:
    import r191data as D
    streams, high = D.load_all()
    out = {}
    for e in eras:
        se = select_era(streams, e, allowed)
        out[e] = {}
        for vid in VARIANTS:
            rng = np.random.default_rng([L.SEED, ord(e), int(vid[1:])])
            out[e][vid] = era_block(vid, se, high, e, rng)
            s = out[e][vid]["stats"]
            print(e, vid, s["trades"], f"{s['win']:.1f}%", round(s["net"]), "dd", round(s["max_dd"]),
                  "wm", round(s["worst_month"]), "p", round(out[e][vid]["p_emp"], 4), flush=True)
    return out


def phase_b():
    res = run_eras(("B",), ("B",))
    pick = L.b_pick(res["B"])
    RESULTS.mkdir(parents=True, exist_ok=True)
    ERA_B_JSON.write_text(json.dumps(dict(written=datetime.now(timezone.utc).isoformat(), b_pick=pick, B=res["B"]),
                                     indent=1, default=str))
    print("B pick:", pick)


def phase_ca():
    if not era_ca_allowed(flag_tracked(), ERA_CA_JSON.exists()):
        sys.exit("eras C and A are locked: commit results/ERA_CA_READ.flag first (and they open only once)")
    res = run_eras(("C", "A"), ("C", "A"))
    ERA_CA_JSON.write_text(json.dumps(dict(written=datetime.now(timezone.utc).isoformat(), **res), indent=1, default=str))


# ================================================================================================ decision
def decide(b: dict, ca: dict) -> dict:
    eras = {"A": ca["A"], "B": b["B"], "C": ca["C"]}
    vids = list(VARIANTS)
    p_bh = [max(eras[e][v]["p_norm"] for e in eras) for v in vids]
    q = L.bh_total(p_bh)
    out = {}
    for i, v in enumerate(vids):
        st = {e: eras[e][v]["stats"] for e in eras}
        pe = {e: eras[e][v]["p_emp"] for e in eras}
        fails = L.gate(st, pe, float(q[i]))
        out[v] = dict(name=VARIANTS[v][2], reliable=not fails, fails=fails, p_bh=p_bh[i], q=float(q[i]))
    return dict(written=datetime.now(timezone.utc).isoformat(), m_total=L.M_TOTAL, b_pick=b["b_pick"],
                any_reliable=any(d["reliable"] for d in out.values()), variants=out)


def rs(x: float) -> str:
    return f"-Rs {abs(x):,.0f}" if x < 0 else f"Rs {x:,.0f}"


def era_row(e: str, d: dict) -> str:
    s = d["stats"]
    return (f"| {e} | {s['trades']} | {s['win']:.1f}% | {s['months_up_pct']:.0f}% ({s['months_up']}/{s['months']}) | "
            f"{rs(s['net'])} | {rs(s['avg_win'])} / {rs(s['avg_loss'])} | {rs(s['net_wo_top5'])} | {rs(s['max_dd'])} | "
            f"{rs(s['worst_month'])} | {d['p_emp']:.3f} |")


HDR = ("| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | "
       "worst month | twin p |\n|---|---|---|---|---|---|---|---|---|---|")


def comp_rows(eras: dict, v: str) -> list:
    lines = ["| era | component | trades | win % | net | worst month | max drawdown |", "|---|---|---|---|---|---|---|"]
    for e in ("A", "B", "C"):
        for c, s in eras[e][v]["comps"].items():
            if not s["trades"]:
                lines.append(f"| {e} | {COMP_NAME[c]} | 0 (no data or none taken) | | | | |")
                continue
            lines.append(f"| {e} | {COMP_NAME[c]} | {s['trades']} | {s['win']:.1f}% | {rs(s['net'])} | "
                         f"{rs(s['worst_month'])} | {rs(s['max_dd'])} |")
    return lines


def write_report():
    b = json.loads(ERA_B_JSON.read_text())
    ca = json.loads(ERA_CA_JSON.read_text())
    dec = decide(b, ca)
    (HERE / "decision.json").write_text(json.dumps(dec, indent=1))
    eras = {"A": ca["A"], "B": b["B"], "C": ca["C"]}
    n_rel = sum(d["reliable"] for d in dec["variants"].values())
    head = [
        "# R191 portfolio of existing streams: summary for Boss", "",
        "Written 17 Nov 2026. Money at 1 lot per trade after dated Zerodha charges and slippage (R190's priced trades), "
        "option BUYING only, booked on the day each trade closes.", "",
        f"**Outcome: {'YES' if n_rel else 'NO'} - {n_rel} of {len(VARIANTS)} pre-registered portfolios passed the "
        "RELIABLE gate.**", "",
        f"B pick (chosen on era B alone, before eras C and A were run): **{dec['b_pick'] or 'none (no variant passed era B)'}**.",
        "",
        "Gate (fixed before any number, PREREG.md section 7): in each era A, B, C: 40+ trades, net > 0, net without the "
        "top 5 trades > 0, month-block random-side twin p < 0.05; BH q <= 0.10 with m = 4,770; and in B and C max "
        "drawdown < Rs 40,000 and every month > -Rs 13,000.", "",
        "Era A has Night on NIFTY only (no Liquidity / R72 / BANKNIFTY option data), so in era A every variant is its "
        "Night rules on NIFTY. Eras C and A were already seen by R190 at the stream level; these portfolio rules were not.",
        "",
        "## All variants at a glance", "",
        "| variant | era | trades | win % | winning months | net | max DD | worst month | verdict |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for v in VARIANTS:
        for e in ("A", "B", "C"):
            s = eras[e][v]["stats"]
            verdict = ("RELIABLE" if dec["variants"][v]["reliable"] else "not reliable") if e == "A" else ""
            head.append(f"| {v} | {e} | {s['trades']} | {s['win']:.1f}% | {s['months_up_pct']:.0f}% | {rs(s['net'])} | "
                        f"{rs(s['max_dd'])} | {rs(s['worst_month'])} | {verdict} |")
    body = []
    for v, (comps, rules, name) in VARIANTS.items():
        d = dec["variants"][v]
        body += ["", f"## {v}: {name}", "", HDR] + [era_row(e, eras[e][v]) for e in ("A", "B", "C")]
        body += ["", f"**Verdict: {'RELIABLE' if d['reliable'] else 'NOT RELIABLE'}.** BH p {d['p_bh']:.3g}, "
                 f"q {d['q']:.3g}." + ("" if d["reliable"] else " Failed: " + "; ".join(d["fails"]) + ".")]
        body += ["", "Per component (trades the portfolio took):", ""] + comp_rows(eras, v)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "summary.md").write_text("\n".join(head + body) + "\n")
    detail = ["", "## Detail: candidates, skips and twins", "",
              "| variant | era | candidates | skipped by the rules | twin mean | twin sd | p (empirical) | p (normal) |",
              "|---|---|---|---|---|---|---|---|"]
    for v in VARIANTS:
        for e in ("A", "B", "C"):
            x = eras[e][v]
            detail.append(f"| {v} | {e} | {x['candidates']} | {x['skipped']} | {rs(x['twin_mean'])} | "
                          f"{rs(x['twin_sd'])} | {x['p_emp']:.4f} | {x['p_norm']:.3g} |")
    (RESULTS / "report.md").write_text((HERE / "report_head.md").read_text() + "\n".join(head[4:] + body + detail) + "\n")
    print("written summary.md, report.md, decision.json; reliable:", n_rel)


if __name__ == "__main__":
    {"b": phase_b, "ca": phase_ca, "report": write_report}.get((sys.argv[1:] or [""])[0],
                                                               lambda: sys.exit(__doc__))()
