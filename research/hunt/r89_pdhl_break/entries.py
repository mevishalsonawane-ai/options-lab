"""R89 experiment entries: one YAML per variant (9 instruments each) in research/experiments/entries/.

    python -m research.hunt.r89_pdhl_break.entries
"""
from __future__ import annotations

import json

from . import cfg as K

DESC = {"B": "base: PDH/PDL, close break, 1m, no HTF filter, fractal N=5, min RR 1, 09:16-14:45 incl. expiry, max 3 a day",
        "SR": "base + nearest 15m swing support / resistance levels", "WK": "wick break instead of close break",
        "T3": "3-minute candles", "T5": "5-minute candles", "HTF": "higher-timeframe filter (daily SMA20 + 15m EMA20) on",
        "N3": "fractal N=3 swings", "RR2": "minimum reward:risk 2", "WIN": "no trigger before 09:30 (MCX 09:15), no expiry day",
        "F1": "first trade of the day only"}
READ = {"C": "break and go (long after an up-break, put after a down-break)",
        "R": "break and reclaim (long after a down-break closed back above, put mirrored)"}


def _q(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def main():
    D = json.loads((K.RESULTS / "design.json").read_text())
    F = json.loads((K.RESULTS / "final.json").read_text()) if (K.RESULTS / "final.json").exists() else None
    dc = {(c["inst"], c["variant"]): c for c in D["cells"]}
    fc = {(c["inst"], c["variant"]): c for c in F["cells"]} if F else {}
    for v in K.VARIANTS:
        rd, tag = v.name.split("-")
        parts, hparts = [], []
        n_pass = 0
        for inst in K.INSTRUMENTS:
            c = dc[(inst, v.name)]
            o = c["opt"]
            n_pass += bool(c["gates"]["pass"])
            parts.append(f"{K.LABEL.get(inst, inst)} {o.get('trades', 0)} tr {o.get('net', 0):+,.0f} (p {o.get('twin_p')}, q {c['q']:.2f})")
            if F:
                h = fc[(inst, v.name)]["opt"]
                hparts.append(f"{K.LABEL.get(inst, inst)} {h.get('trades', 0)} tr {h.get('net', 0):+,.0f}")
        pi = D["pooled"][f"indices|{v.name}"]
        pm = D["pooled"][f"mcx|{v.name}"]
        summ = (f"Design option net (1 lot, 1-ITM, real prints, costs): " + "; ".join(parts) +
                f". Pooled indices {pi['trades']} tr {pi['net']:+,.0f} (twin mean {pi['twin_mean']:+,.0f}, futures "
                f"{pi['fut_net']:+,.0f}, false breaks {pi['false_break']:.0%}); MCX {pm['trades']} tr {pm['net']:+,.0f}. "
                f"Design passers: {n_pass} of 9.")
        if F:
            hpi = F["pooled"][f"indices|{v.name}"]
            hpm = F["pooled"][f"mcx|{v.name}"]
            summ += (" Holdout (read once): " + "; ".join(hparts) +
                     f". Pooled indices {hpi['trades']} tr {hpi['net']:+,.0f}; MCX {hpm['trades']} tr {hpm['net']:+,.0f}.")
        verdict = "rejected"
        if F and any(x["paper_candidate"] for k, x in F["verdict"].items() if k.endswith("|" + v.name)):
            verdict = "paper-only"
        y = [f"id: r89-{v.name.lower()}",
             f"title: {_q(f'R89 {v.name}: Boss previous-day high/low 1m break, {READ[rd]}, {DESC[tag]}')}",
             "family: patterns-indicators", "date: '2026-10-20'", "doc: research/HUNT_R89_PDHL_BREAK.md",
             f"hypothesis: {_q('Pre-registered: buying the 1-ITM option on Boss rule (signal on the underlying: break of a previous-day level, a same-colour candle beyond / back over it, entry on a break of that candle, stop at its other end, target the nearest swing with min RR) beats 500 random-entry twins after real costs (p < 0.05, BH q < 0.10 over 180 cells), >= 40 trades, walk-forward, without the top 5, worst month >= -Rs 13k.')}",
             f"data_version: {_q('Drive lake gdrive:IraAlgo-data-lake (20 Oct 2026 copy): index_1m + options_rolling_1m WEEK (R51 loader, clean + stale step) to 19 Oct 2026; MCX MONTH option books GOLDM, SILVERM, CRUDEOILM, NATGASMINI (R53 Market, close-only near future)')}",
             "period:", "  design: 20 Nov 2024 - 31 Mar 2026 (indices); 5 Aug 2025 - 31 Mar 2026 (MCX)",
             "  holdout: 1 Apr - 19 Oct 2026" + (" (read once)" if F else " (not yet read)"),
             "instruments:"] + [f"- {i}" for i in K.INSTRUMENTS] + [
             "features:", "- 'signal source: the underlying (index spot 1m OHLC; MCX near future, close-only); no option chart'",
             "- previous-day high / low (SR: nearest 15m swing levels), TF candles, fractal swings, daily SMA20 / 15m EMA20 (HTF)",
             f"model_or_rule: R89 {v.name}", "settings: research/hunt/r89_pdhl_break/cfg.py, rules.py, PREREG.md, NOTES.md",
             "prereg: research/hunt/r89_pdhl_break/PREREG.md", "variants_tried: 20",
             "results:", f"  summary: {_q(summ)}",
             "  costs: index R51 engine (5 bps + 1/2/4 ticks by volume, dated charges); MCX R53 charges + R85 strict half-spread; 1 lot in force",
             f"holdout_read: {'true' if F else 'false'}", f"verdict: {verdict}",
             f"notes: {_q('One of 20 variants x 9 instruments (180 cells, one BH family). Futures-only and fixed-risk (Rs 2,000, 1-3 lots) shown in the doc, not gated.')}"]
        (K.ROOT / "research" / "experiments" / "entries" / f"r89-{v.name.lower()}.yaml").write_text("\n".join(y) + "\n")


if __name__ == "__main__":
    main()
