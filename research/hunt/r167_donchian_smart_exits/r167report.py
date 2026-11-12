"""R167 tables (after `r167study.py decide`): results/tables.md with every test's walk-forward and final numbers next to
today's exits, twin p / BH q, the giveback counts (Amendment A2) and the second look. The plain-English summary for
Boss at the top of results/report.md is written by hand from these tables.

    python research/hunt/r167_donchian_smart_exits/r167report.py
"""
from __future__ import annotations

import json

import r167cfg as K


def rs(x) -> str:
    return "-" if x is None else f"Rs {x:,.0f}"


def pct(x) -> str:
    return "-" if x is None else f"{x:.1%}"


def gb(s: dict) -> str:
    g = s.get("giveback") or {}
    return f"{g.get('reached', 0)} / {g.get('ended_le0', 0)} / {g.get('ended_le_m2000', 0)}"


def build() -> str:
    design = {a: json.loads((K.RESULTS / f"design_{a}.json").read_text()) for a in K.ARMS}
    final = {a: json.loads((K.RESULTS / f"final_{a}.json").read_text()) for a in K.ARMS}
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    tq = {(t["arm"], t["rule"], t["version"], t["lots"]): t for t in dec["tests"]}
    out = [f"# R167 tables (m = {K.M_TOTAL}; passes: {len(dec['passed'])} of {len(dec['tests'])})", ""]
    for arm in K.ARMS:
        for lots in K.LOTS:
            d, f = design[arm], final[arm]
            b_w, b_f = d[f"B0@{lots}"]["wf"], f[f"B0@{lots}"]["final"]
            out += [f"## {K.ARM_NAME[arm]}, {lots} lot{'s' if lots > 1 else ''}", "",
                    "| rule | version | WF trades | WF win | WF net | final trades | final win | final net | worst day | max DD "
                    "| +1.5k reached / ended <= 0 / <= -2k | p | q | pass |",
                    "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
                    f"| today (B0) | - | {b_w['trades']} | {pct(b_w['win'])} | {rs(b_w['net'])} | {b_f['trades']} | "
                    f"{pct(b_f['win'])} | {rs(b_f['net'])} | {rs(b_f['worst_day'])} | {rs(b_f['max_dd'])} | {gb(b_f)} | | | |"]
            for rule, ver in K.VARIANTS:
                k = f"{rule}-{ver}@{lots}"
                w, fi, t = d[k]["wf"], f[k]["final"], tq[(arm, rule, ver, lots)]
                out.append(f"| {rule} | {ver} | {w['trades']} | {pct(w['win'])} | {rs(w['net'])} | {fi['trades']} | "
                           f"{pct(fi['win'])} | {rs(fi['net'])} | {rs(fi['worst_day'])} | {rs(fi['max_dd'])} | {gb(fi)} | "
                           f"{t['p']:.3g} | {t['q']:.3g} | {'yes' if t['passed'] else 'no'} |")
            out.append("")
    pk = K.RESULTS / "picker.json"
    if pk.exists():
        p = json.loads(pk.read_text())
        out += ["## Second look: R151's pickers (Apr - Oct 2026, descriptive, never gated)", "",
                "| arm | lots | rule | version | trades | win | net | worst day |", "|---|---|---|---|---|---|---|---|"]
        for arm in K.ARMS:
            for lots in K.LOTS:
                for name in ["B0"] + [f"{r}-{v}" for r, v in K.VARIANTS]:
                    s = p[arm][f"{name}@{lots}"]
                    r, v = (name.split("-") + ["-"])[:2]
                    out.append(f"| {arm} | {lots} | {r} | {v} | {s['trades']} | {pct(s['win'])} | {rs(s['net'])} | "
                               f"{rs(s['worst_day'])} |")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    (K.RESULTS / "tables.md").write_text(build())
    print("results/tables.md written")
