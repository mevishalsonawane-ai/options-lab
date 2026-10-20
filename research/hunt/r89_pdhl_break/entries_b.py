"""R89B experiment entries: one YAML per variant (5 indices each) in research/experiments/entries/.

    python -m research.hunt.r89_pdhl_break.entries_b
"""
from __future__ import annotations

import json

from . import cfg as K
from . import rules_b
from .entries import _q
from .study_b import RESULTS

DESC = {"B0": "R89 base (1m, candle stop, swing target), reference", "B1": "15m candles, candle stop, swing target",
        "B2": "15m, candle stop, RUN exit (breakeven at +1R, 1R trail)", "B3": "B2 + breadth (4 of 5 indices broke)",
        "B4": "B2 + opening-range alignment (from 09:45)", "B5": "B2 + skip gap days (> 0.5%)",
        "B6": "5m, 1x ATR14(15m) stop, RUN exit", "B7": "B6 + breadth", "B8": "B2 + breadth + opening range + gap"}


def main():
    D = json.loads((RESULTS / "design.json").read_text())
    pF = RESULTS / "final.json"
    F = json.loads(pF.read_text()) if pF.exists() else None
    dc = {(c["inst"], c["variant"]): c for c in D["cells"]}
    fc = {(c["inst"], c["variant"]): c for c in F["cells"]} if F else {}
    for v in rules_b.VARIANTS_B:
        parts = []
        for u in K.INDICES:
            o = dc[(u, v.name)]["opt"]
            s = f"{u} {o.get('trades', 0)} tr {o.get('net', 0):+,.0f} (twin p {o.get('twin_p')}, after-trigger p {o.get('twin_after_p')})"
            if F:
                h = fc[(u, v.name)]["opt"]
                s += f"; holdout {h.get('trades', 0)} tr {h.get('net', 0):+,.0f} (after-trigger p {h.get('twin_after_p')})"
            parts.append(s)
        pd_ = D["pooled"][v.name]
        summ = (f"Design 20 Nov 2024 - 19 Oct 2026, 1-ITM option, 1 lot, real prints and costs: " + "; ".join(parts) +
                f". Pooled {pd_['trades']} tr {pd_['net']:+,.0f}, false breaks {pd_['false_break']:.0%}.")
        if F:
            ph = F["pooled"][v.name]
            summ += f" Holdout 2 Aug 2021 - 19 Nov 2024 pooled {ph['trades']} tr {ph['net']:+,.0f}."
        lead = any(k.endswith("|" + v.name) for k in D.get("carried", []))
        verdict = "rejected"
        if F and lead and any(x["holdout_pass"] for k, x in F.get("amended_leads", {}).items() if k.endswith("|" + v.name)):
            verdict = "inconclusive"
        y = [f"id: r89b-{v.name.lower()}",
             f"title: {_q(f'R89B {v.name}: enhanced previous-day high/low break, {DESC[v.name]}')}",
             "family: patterns-indicators", "date: '2026-10-20'", "doc: research/HUNT_R89_PDHL_BREAK.md",
             f"hypothesis: {_q('Pre-registered (PREREG_B): the enhanced break-and-go rule on the index, buying the 1-ITM option, beats 500 random-entry twins after real costs (p < 0.05, BH q < 0.10 over 45 cells), >= 40 trades, >= 3 of 4 folds, without the top 5, worst month >= -Rs 13k; then the holdout.')}",
             f"data_version: {_q('Drive lake gdrive:IraAlgo-data-lake (20 Oct 2026 copy): index_1m + options_rolling_1m WEEK, R51 loader (clean + stale step); design stores to 19 Oct 2026, holdout stores 2 Aug 2021 - 19 Nov 2024')}",
             "period:", "  design: 20 Nov 2024 - 19 Oct 2026",
             "  holdout: 2 Aug 2021 - 19 Nov 2024" + (" (read once)" if F else " (not yet read)"),
             "instruments:"] + [f"- {u}" for u in K.INDICES] + [
             "features:", "- 'signal source: the underlying (index spot 1m OHLC; breadth reads the other indices spot); no option chart'",
             f"model_or_rule: R89B {v.name}", "settings: research/hunt/r89_pdhl_break/rules_b.py, PREREG_B.md, NOTES_B.md",
             "prereg: research/hunt/r89_pdhl_break/PREREG_B.md", "variants_tried: 9",
             "results:", f"  summary: {_q(summ)}",
             "  costs: R51 engine (5 bps + 1/2/4 ticks by volume, dated charges); 1 lot in force",
             f"holdout_read: {'true' if F else 'false'}", f"verdict: {verdict}",
             f"notes: {_q('One of 9 variants x 5 indices (45 cells, one BH family), after R89 (180 cells). NOTES_B A1: the pre-registered twins carry hindsight on the day side; an after-trigger twin was added before the holdout read.' + (' Carried as the one amended lead (FINNIFTY).' if lead else ''))}"]
        (K.ROOT / "research" / "experiments" / "entries" / f"r89b-{v.name.lower()}.yaml").write_text("\n".join(y) + "\n")


if __name__ == "__main__":
    main()
