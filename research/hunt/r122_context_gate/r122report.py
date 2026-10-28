"""R122 report and log entry: results/loop.json + final.json -> results/report.md and
research/experiments/entries/r122-context-gate.yaml (one entry for the whole loop, variants_tried = every variant, so
the BH count is exact). 28 Oct 2026.

    python research/hunt/r122_context_gate/r122report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r122loop as R  # noqa: E402


def rs(x):
    return ("-" if x < 0 else "") + f"Rs {abs(round(x)):,}"


def main():
    lp = json.loads(R.LOOP.read_text())
    fin = json.loads((R.RESULTS / "final.json").read_text())
    L = ["# R122 results: a context-aware time machine, searched in a bounded loop", "",
         f"Frozen variant: **{fin['variant']}** ({', '.join(fin['spec'].get('rules', [fin['spec']['kind']]))}). "
         f"Variants tried: {fin['variants_tried']}. BH m = {fin['m_all']:,}. Final q = {fin['q']:.3g}.", "",
         f"**Step 3 passed: {'yes' if fin['gates']['passed'] else 'no'}.** " + "; ".join(fin["gates"]["fails"]), "",
         "## The loop (design walk-forward, Apr 2025 - Mar 2026; pooled without ORB Fresh)", "",
         "| round | variant | skipped / trades | net before | net after | uplift vs random | z | p |",
         "|---:|---|---:|---:|---:|---:|---:|---:|"]
    for name, v in lp["variants"].items():
        p = v["wf"]["pooled"]
        L.append(f"| {v['round']} | {name} | {p['skipped']:,} / {p['trades']:,} | {rs(p['net_before'])} | "
                 f"{rs(p['net_after'])} | {rs(p['uplift'])} | {p['z']} | {p['p']} |")
    for title, ev in (("walk-forward (design)", fin["wf"]), ("final, read once", fin["final"])):
        p = ev["pooled"]
        L += ["", f"## The frozen variant, {title}", "",
              f"Pooled: {p['trades']:,} trades, {p['skipped']:,} skipped, {rs(p['net_before'])} -> {rs(p['net_after'])}, "
              f"uplift vs random {rs(p['uplift'])}, z {p['z']}, p {p['p']}, twin 95th pct {rs(p.get('twin_p95', 0))}, "
              f"drawdown {rs(p['dd_before'])} -> {rs(p['dd_after'])}.", "",
              "| arm | trades | skipped | net before | net after | win before | win after | z |",
              "|---|---:|---:|---:|---:|---:|---:|---:|"]
        for a, x in ev["arms"].items():
            L.append(f"| {a} | {x['trades']} | {x['skipped']} | {rs(x['net_before'])} | {rs(x['net_after'])} | "
                     f"{x['win_before']} | {x['win_after']} | {x['z']} |")
    (R.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    fp, wp = fin["final"]["pooled"], fin["wf"]["pooled"]
    e = dict(
        id="r122-context-gate",
        title="R122 a context-aware time machine (strategies, chart patterns, VIX, gap, trend, analogs) searched in a "
              "bounded loop as a pre-entry gate",
        family="daily-filters", date="2026-10-28", doc="research/HUNT_R122_CONTEXT_GATE.md",
        data_version="Drive lake copied 28 Oct 2026 (index_1m incl. INDIAVIX); R120's trade lists (R113 adapters)",
        period=dict(design="2024-11-20..2026-03-31 (monthly walk-forward Apr 2025 - Mar 2026)",
                    holdout="2026-04-01..2026-10-13 read once (not fresh: read by R51-R121)"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        features=["signal source: each arm's own (the underlying; Hero's strike by the option's price); the gate reads "
                  "only the index, India VIX and the arms' earlier entries, before the entry minute"],
        hypothesis="Boss: feed the strategies, chart patterns and everything else to the time machine, in a loop until "
                   "success. Bounded loop: <= 30 variants on the design walk-forward, freeze the best by pooled p, read "
                   "the final once; a pass would earn only a shadow forward test on the phone.",
        model_or_rule="research/hunt/r122_context_gate/ (r122feat.py inputs, r122lib.py variants and twins, r122loop.py)",
        settings="PREREG.md; rounds in results/round{2,3,4}.json, every variant in results/loop.json",
        prereg="research/hunt/r122_context_gate/PREREG.md", variants_tried=fin["variants_tried"], holdout_read=True,
        verdict="rejected" if not fin["gates"]["passed"] else "paper-only",
        results=dict(summary=(
            f"Frozen {fin['variant']}: walk-forward {wp['trades']:,} trades, {wp['skipped']:,} skipped, "
            f"{rs(wp['net_before'])} -> {rs(wp['net_after'])}, z {wp['z']}, p {wp['p']}; FINAL {fp['trades']:,} trades, "
            f"{fp['skipped']:,} skipped, {rs(fp['net_before'])} -> {rs(fp['net_after'])}, uplift vs random "
            f"{rs(fp['uplift'])}, z {fp['z']}, p {fp['p']}, q {fin['q']:.3g}. Fails: " + "; ".join(fin["gates"]["fails"]) +
            ". The design 'success' did not survive the final: a search overfit."),
            costs="each arm's own study costs (R120's lists), 1 lot"),
        notes="Normal-approximation twin p (the empirical twin floor 1/20,001 can never clear BH over ~9.3M tests; the "
              "same floor made R120/R121's twin gate unpassable). The loop is closed: no variant is tried on the final.")
    (R.HERE.parents[2] / "research" / "experiments" / "entries" / "r122-context-gate.yaml").write_text(
        yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("written", e["verdict"])


if __name__ == "__main__":
    main()
