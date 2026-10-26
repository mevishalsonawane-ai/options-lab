"""R109 experiment-log entries (research/experiments/entries/r109-*.yaml), one per pre-registered test.

    python research/hunt/r109_irathinker/r109entries.py
"""
from __future__ import annotations

import json

import yaml

import r109cfg as K
from r109report import LABEL, rs

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
FAMILY = {"mom60": "outside-strategies", "out-of-family": "outside-strategies", "weekly-variant": "outside-strategies",
          "strangle": "option-structure"}


def main():
    D = json.loads((K.RESULTS / "design.json").read_text())
    F = json.loads((K.RESULTS / "final.json").read_text())
    C = json.loads((K.RESULTS / "decision.json").read_text())
    for n, t in K.TESTS.items():
        parts = []
        for lab, src, p in (("History", D, "history"), ("Design", D, "design"), ("Walk-forward", D, "wf"),
                            ("Final", F, "final"), ("Post-13 Oct", F, "post")):
            s = src[n]["periods"].get(p) or {}
            if not s.get("n"):
                parts.append(f"{lab}: 0 trades")
                continue
            tp = f", twin p {s['twin_p']:.3g}" if s.get("twin_p") is not None else ""
            parts.append(f"{lab}: {s['n']:,} trades, win {s['win'] * 100:.1f}%, net {rs(s['net'])}, worst month "
                         f"{rs(s['worst_month'])}, max DD {rs(s['max_dd'])}{tp}")
        r = C["tests"][n]
        summary = ". ".join(parts) + (f". BH q {r['q']:.3g} over {C['m']:,} tests. "
                                      + ("Passes every gate." if r["verdict"] == "PASS"
                                         else f"Fails {len(r['fails'])} checks, first: {'; '.join(r['fails'][:3])}."))
        rule = ("IraThinker's mom60: |60-minute index return| >= threshold -> buy the ATM option that way, hold 60 min, "
                "exit if the premium closes 30% below entry; one at a time, entries 60 min apart, last entry 14:30"
                if t["kind"] == "mom60" else
                "IraThinker's Monday short strangle: sell the monthly CE ATM+200 / PE ATM-200 at 09:31 Monday, buy back "
                "Thursday 15:29 or settle at intrinsic when the monthly expiry falls inside")
        e = dict(
            id=f"r109-{n}", title=f"R109 {LABEL[n]} on real prints", family=FAMILY[t["family"]], date="2026-10-26",
            doc="research/HUNT_R109_IRATHINKER_RULES.md",
            hypothesis=("IraThinker's own study claims (" + K.CLAIMS.get(n, "not claimed by IraThinker; out-of-family "
                        "or weekly-chain check") + ") hold on our lake's real prints after dated charges and slippage. "
                        "Gates: net > 0 and twin p < 0.05 in design, walk-forward and final; BH q <= 0.10 over all "
                        "earlier tests; >= 40 design / 20 final trades"
                        + ("; account rule and bootstrap for selling" if t["kind"] == "strangle" else "") + "."),
            data_version="Drive lake gdrive:IraAlgo-data-lake (index_1m, index_daily, options_rolling_1m MONTH/WEEK, "
                         "bhav_index) copied 26 Oct 2026; R51 Amendment A clean held-contract stores",
            period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                        holdout="2026-04-01..2026-10-13 read once (already read by R51-R108); 14-19 Oct descriptive"),
            instruments=[t["und"]],
            features=["signal source: the index spot's 60-minute return (underlying)" if t["kind"] == "mom60"
                      else "signal source: none directional; strikes off the index spot at 09:30"],
            model_or_rule=rule + f" ({t['series'].lower()} chain)",
            settings="research/hunt/r109_irathinker/r109cfg.py TESTS; PREREG.md sections 1-5",
            prereg="research/hunt/r109_irathinker/PREREG.md", variants_tried=1,
            results=dict(summary=summary, costs="dated Zerodha charges, liq slippage, next-minute fills, stale legs "
                                                "repriced by BS at the last implied vol"),
            holdout_read=True, verdict="paper-only" if r["verdict"] == "PASS" else "rejected",
            notes="Research only; nothing changes live. Signal on the underlying.")
        (ENTRIES / f"r109-{n}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=1000))
        print("wrote", f"r109-{n}.yaml", r["verdict"])


if __name__ == "__main__":
    main()
