"""R76 experiment entries: one YAML per variant (B0 + 5 tests) from results/summary.json. 18 Oct 2026.

    python research/hunt/r76/r76entries.py && python -m research.experiments.validate
"""
from __future__ import annotations

import json
import sys

import yaml

import r76study as S

sys.path.insert(0, str(S.HERE.parent / "r64"))
from r64entries import line  # noqa: E402
from r65report import rs  # noqa: E402

ENTRIES = S.R64.ROOT / "research" / "experiments" / "entries"
TITLE = "Liquidity 15+5 (index level breaks on 15/5-min bars BANKNIFTY and MIDCPNIFTY, 30/5 FINNIFTY; 1-ITM)"
LABEL = {"B0": "own exits (today)",
         "T25": "own exits + stop to +5% once the index has covered 25% of the way to its target (Boss)",
         "T30": "own exits + stop to +5% once the index has covered 30% of the way to its target (neighbour)",
         "T35": "own exits + stop to +5% once the index has covered 35% of the way to its target (Boss)",
         "T50": "own exits + stop to +5% once the index has covered 50% of the way to its target (neighbour)",
         "L35": "own exits + stop to +5% once the option has traded +35% (premium reading, side line)"}
SRC = {"B0": "signal: index bars (liquidity levels); exits: option premium and index levels",
       "L35": "signal: index bars (liquidity levels); trigger and stop: option premium"}


def main():
    s = json.loads((S.RESULTS / "summary.json").read_text())
    for v in S.ALL:
        r = s["res"][v]
        if v == "B0":
            verdict, extra = "paper-only", " Liquidity's own exits re-measured (reproduces R70's B0 exactly); unchanged."
        else:
            dec = r["decision"]
            verdict = "rejected" if not dec["harmless"] else "inconclusive"
            t, p, ls = r["trail"], r["paired"], r["lock_split"]
            extra = (f" Changed {t['design']['changed']} design / {t['final']['changed']} final trades (design: "
                     f"{rs(t['design']['saved'])} saved on {t['design']['n_better']}, {rs(t['design']['given_up'])} "
                     f"given up on {t['design']['n_worse']}); versus B0: design {rs(p['design']['diff'])} (bootstrap p "
                     f"{p['design']['p']}), final {rs(p['final']['diff'])} (p {p['final']['p']}); exit-twin p "
                     f"{r['exit_p']} (BH q {r['exit_q']} over 5 tests).")
            if v != "L35":
                extra += (f" Of the design changes, {ls['design']['below']['n']} were sold under +5% because the index "
                          "reached the trigger while the option was still below +5% (the stop sat above the price).")
            extra += " Not at least B0's net in every period, so not recommended and not 'harmless'."
        e = dict(
            id=f"r76-liquidity-{v.lower()}", title=f"R76 Liquidity: {LABEL[v]}", family="trade-management",
            date="2026-10-18", doc="research/HUNT_R76_LIQUIDITY_LOCK_35.md",
            hypothesis=("Boss (18 Oct): 'What about 35-5 for liquidity', then '25% -35% of target': once the index has "
                        "covered 25-35% of the way to Liquidity's own index target, the option's stop rises to +5% over "
                        f"the fill. This variant: {LABEL[v]}; recommended only if its net is at least B0's in design, "
                        "walk-forward and final and it beats random early exits (BH q <= 0.10)."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK), R51 Amendment A "
                          "clean stores, 20 Nov 2024 - 13 Oct 2026"),
            period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)",
                        holdout="1 Apr - 13 Oct 2026, read once; already read by R51 / R63 / R70 (weak)"),
            instruments=["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"],
            features=[SRC.get(v, "signal: index bars (liquidity levels); trigger: the index's progress to its target; "
                                 "stop: option premium")],
            model_or_rule=f"{TITLE}; exits: {LABEL[v]}", settings="research/hunt/r76/r76study.py; PREREG.md",
            prereg="research/hunt/r76/PREREG.md", variants_tried=5,
            results=dict(summary=(f"Design: {line(r['design'])}. Walk-forward: {line(r['wf'])}. Final: "
                                  f"{line(r['final'])}.{extra}"),
                         costs="dated charges, R17 liq fills, next-minute fills, stops on the minute low with gap "
                               "fills at the worse open, locks from the next minute, one held contract, 1 lot"),
            holdout_read=True, verdict=verdict,
            notes="Signal on the underlying; research only, nothing changes paper or live. Liquidity BANKNIFTY's "
                  "plain-rules record for the 26 Oct decision is untouched.")
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(f"{len(S.ALL)} entries")


if __name__ == "__main__":
    main()
