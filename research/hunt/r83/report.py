"""R83 aggregator: read results/<idea>_design.json and <idea>_final.json, apply Benjamini-Hochberg across ALL pre-registered
variants (unread variants count with p = 1; PREREG section 5), apply the pass gates, and write results/summary.json and
results/report.md.

    python -m research.hunt.r83.report
"""
from __future__ import annotations

import json

from research.hunt.r83 import common as C

IDEAS = ["E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "E9", "E10", "E11", "E12"]
SIGNAL = {
    "E1": "WTI proxy (CRUDEOILM future)", "E2": "WTI proxy (CRUDEOILM future)",
    "E3": "Henry Hub proxy (NATGASMINI future)", "E4": "Henry Hub proxy (NATGASMINI future)",
    "E5": "MCX CRUDEOIL daily future", "E6": "WTI / Henry Hub proxy",
    "E7": "WTI proxy (CRUDEOILM future)", "E8": "MCX daily futures (copper: COMEX x INR)",
    "E9": "Henry Hub history (signal), MCX NATGASMINI (trade)", "E10": "Henry Hub proxy (NATGASMINI future)",
    "E11": "MCX daily futures", "E12": "MCX daily futures",
}


def load(name):
    p = C.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else None


def main():
    rows, pvals = [], {}
    for idea in IDEAS:
        d, f = load(f"{idea}_design.json"), load(f"{idea}_final.json")
        if d is None:
            rows.append({"idea": idea, "status": "not run"})
            continue
        for v in d.get("variants", {}):
            pvals[f"{idea}:{v}"] = 1.0                    # unread variants: p = 1
        if f is None:
            rows.append({"idea": idea, "status": "design only", "frozen": d.get("frozen")})
            continue
        key = f"{idea}:{f['frozen']}"
        pvals[key] = f["twin_p"] if f.get("twin_p") is not None else 1.0
        rows.append({"idea": idea, "status": "final read", "frozen": f["frozen"], "final": f["final"],
                     "twin_p": f.get("twin_p"), "top5_removed": f.get("top5_removed"),
                     "base_final_net": f.get("base_final_net", 0.0), "wf_net": f.get("wf_net", d.get("wf_net")),
                     "base_wf_net": f.get("base_wf_net", d.get("base_wf_net", 0.0)), "mcx_real": f.get("mcx_real"),
                     "design_variants": {v: x.get("design", {}) for v, x in d.get("variants", {}).items()}})
    # pad to the pre-registered count so BH never uses fewer tests than were planned
    m = max(C.N_VARIANTS, len(pvals))
    padded = dict(pvals)
    for i in range(m - len(pvals)):
        padded[f"_unrun{i}"] = 1.0
    q = C.bh(padded)
    for r in rows:
        if r["status"] != "final read":
            continue
        r["bh_q"] = q[f"{r['idea']}:{r['frozen']}"]
        r.update(C.gates(r["final"]["net"], r["base_final_net"] or 0.0, r["wf_net"] or 0.0, r["base_wf_net"] or 0.0,
                         r["twin_p"], r["bh_q"], r["top5_removed"] if r["top5_removed"] is not None else -1,
                         r["final"]["trades"]))
    C.save_json("summary.json", {"n_variants_bh": m, "rows": rows})

    def rs(x):
        return "-" if x is None else f"{x:+,.0f}"
    lines = ["| idea | signal source | frozen pick | final trades | final net Rs | baseline final | WF net | baseline WF | "
             "twin p | BH q | top-5 removed | verdict |", "|" + "---|" * 12]
    for r in rows:
        if r["status"] != "final read":
            lines.append(f"| {r['idea']} | {SIGNAL[r['idea']]} | {r.get('frozen') or '-'} | - | - | - | - | - | - | - | - | "
                         f"{r['status']} |")
            continue
        lines.append(f"| {r['idea']} | {SIGNAL[r['idea']]} | {r['frozen']} | {r['final']['trades']} | {rs(r['final']['net'])} | "
                     f"{rs(r['base_final_net'])} | {rs(r['wf_net'])} | {rs(r['base_wf_net'])} | {r['twin_p']:.3f} | "
                     f"{r['bh_q']:.2f} | {rs(r['top5_removed'])} | {r['verdict']} |")
    (C.RESULTS / "report.md").write_text("\n".join(lines) + f"\n\nBH across {m} variants (unread ones at p = 1).\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
