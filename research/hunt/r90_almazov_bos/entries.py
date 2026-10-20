"""R90 experiment entries: one YAML per variant (9 instruments each) in research/experiments/entries/.

    python -m research.hunt.r90_almazov_bos.entries
"""
from __future__ import annotations

import json

from . import cfg as K


def _q(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def main():
    D = json.loads((K.RESULTS / "design.json").read_text())
    F = json.loads((K.RESULTS / "final.json").read_text()) if (K.RESULTS / "final.json").exists() else None
    dc = {(c["inst"], c["variant"]): c for c in D["cells"]}
    fc = {(c["inst"], c["variant"]): c for c in F["cells"]} if F else {}
    for v in K.VARIANTS:
        parts, hparts, n_pass = [], [], 0
        for inst in K.INSTRUMENTS:
            c = dc[(inst, v.name)]
            o = c["opt"]
            n_pass += bool(c["gates"]["pass"])
            aw = c.get("as_written", {})
            parts.append(f"{K.LABEL.get(inst, inst)} {o.get('trades', 0)} tr {o.get('net', 0):+,.0f} (p {o.get('twin_p')}, "
                         f"q {c['q']:.2f}; as written {aw.get('trades', 0)} tr {aw.get('pts_mean', 0):+.2f} pts)")
            if F:
                h = fc[(inst, v.name)]["opt"]
                hparts.append(f"{K.LABEL.get(inst, inst)} {h.get('trades', 0)} tr {h.get('net', 0):+,.0f}")
        pi, pm = D["pooled"][f"indices|{v.name}"], D["pooled"][f"mcx|{v.name}"]
        summ = ("Design option net (1 lot, 1-ITM, real prints, costs): " + "; ".join(parts) +
                f". Pooled indices {pi['trades']} tr {pi['net']:+,.0f} (after-trigger twins {pi['twin_mean']:+,.0f}; "
                f"underlying no costs Rs {pi['und_rs']:+,.0f}; as written no costs Rs {pi['aw_rs']:+,.0f} on "
                f"{pi['aw_trades']}); MCX {pm['trades']} tr {pm['net']:+,.0f}. Design passers: {n_pass} of 9.")
        if F:
            hpi, hpm = F["pooled"][f"indices|{v.name}"], F["pooled"][f"mcx|{v.name}"]
            summ += (" Holdout (read once): " + "; ".join(hparts) +
                     f". Pooled indices {hpi['trades']} tr {hpi['net']:+,.0f}; MCX {hpm['trades']} tr {hpm['net']:+,.0f}.")
        verdict = "rejected"
        if F and any(x["paper_candidate"] for k, x in F["verdict"].items() if k.endswith("|" + v.name)):
            verdict = "paper-only"
        y = [f"id: r90-{v.name.lower()}",
             f"title: {_q(f'R90 {v.name}: Almazov MTF structure & BOS-to-BOS entries, {K.DESC[v.name]}')}",
             "family: patterns-indicators", "date: '2026-10-20'", "doc: research/HUNT_R90_ALMAZOV_BOS.md",
             f"hypothesis: {_q('Pre-registered: buying the 1-ITM option on the Almazov indicator (signal on the underlying: chart and HTF market structure agree, buy-stop at the chart swing high / sell-stop at the swing low, stop at the opposite chart swing -/+ 1 tick, target the HTF swing, min RR) beats 500 after-trigger random-entry twins after real costs (p < 0.05, BH q < 0.10 over 99 cells), >= 40 trades, walk-forward, without the top 5, worst month >= -Rs 13k.')}",
             f"data_version: {_q('Drive lake gdrive:IraAlgo-data-lake (20 Oct 2026 copy): index_1m + options_rolling_1m WEEK (R51 loader via R89 stores, clean + stale step) to 19 Oct 2026; MCX MONTH option books GOLDM, SILVERM, CRUDEOILM, NATGASMINI (R53 Market, close-only near future)')}",
             "period:", "  design: 20 Nov 2024 - 31 Mar 2026 (indices); 5 Aug 2025 - 31 Mar 2026 (MCX)",
             "  holdout: 1 Apr - 19 Oct 2026" + (" (read once)" if F else " (not yet read)"),
             "instruments:"] + [f"- {i}" for i in K.INSTRUMENTS] + [
             "features:", "- 'signal source: the underlying (index spot 1m OHLC; MCX near future, close-only); no option chart, no option-chain data'",
             "- swings (D bars each side), CHoCH / BOS market structure on the chart and the HTF, HTF swing target",
             f"model_or_rule: R90 {v.name}", "settings: research/hunt/r90_almazov_bos/cfg.py, structure.py, model.py, PREREG.md, NOTES.md",
             "prereg: research/hunt/r90_almazov_bos/PREREG.md", "variants_tried: 11",
             "results:", f"  summary: {_q(summ)}",
             "  costs: index R51 engine (5 bps + 1/2/4 ticks by volume, dated charges), flat 15:15 / 15:05 expiry; MCX R53 charges + R85 strict half-spread; 1 lot in force",
             f"holdout_read: {'true' if F else 'false'}", f"verdict: {verdict}",
             f"notes: {_q('One of 11 variants x 9 instruments (99 cells, one BH family). The indicator as written (chart-bar fills, no fees, overnight holds) and the underlying in points are shown in the doc, not gated.')}"]
        (K.ROOT / "research" / "experiments" / "entries" / f"r90-{v.name.lower()}.yaml").write_text("\n".join(y) + "\n")


if __name__ == "__main__":
    main()
