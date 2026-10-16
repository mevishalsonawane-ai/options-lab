"""R72 experiment entries: one YAML per gated test (6 originals + 6 re-tunes, failures included) from results/*.json.

    python research/hunt/r72/r72entries.py && python -m research.experiments.validate
"""
from __future__ import annotations

import json

import yaml

import r72study as X

X.use_r72()
R, P = X.R, X.P
from r71entries import line  # noqa: E402
from r71report import rs, setting  # noqa: E402

ENTRIES = R.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R72_CROSS_INDEX.md"
PREREG = "research/hunt/r72/PREREG.md"
RULE_TXT = {
    "C1": "a 5-min bar on which at least M of the 5 indices close beyond their own 20-bar Donchian channel; trade each "
          "index that broke",
    "C2": "the index that has not broken while at least 3 of the other 4 broke within K bars (catch-up)",
    "C3": "an index breaking its channel while none of the other 4 broke within K bars, faded (opposite side)",
    "C4": "at a fixed time, all 5 above (below) their open: the strongest (weakest) index in ATR units",
    "C5": "the count of indices above EMA20 jumping to >= M from <= 1 within 3 bars; every index above its EMA20",
    "C6": "an index making a new day low (high) while >= R of the other 4 hold above (below) theirs, then a close back "
          "through bar 1's high (low) within 3 bars",
}


def main():
    des = json.loads((R.RESULTS / "design.json").read_text())
    fin = json.loads((R.RESULTS / "final.json").read_text())
    dfx = fin["donchian"]["XB"]
    n = 0
    for k, t in fin["tests"].items():
        rule, kind = t["rule"], t["kind"]
        r = des["rules"][rule]
        dz = r["grid"][t["setting"]]["design"]
        what = "original (fixed before any result)" if kind == "O" else \
            f"re-tune (2 parameters x 3 exits; walk-forward picks {', '.join(r['wf_picks'])}; final pick = best design net)"
        passed = t["verdict"] == "PASS"
        dup = (t["corr_donchian"] or 0) >= R.DUPLICATE_CORR
        slip = f" At twice the slippage: final {rs(t['slip2']['net'])}." if t.get("slip2") else ""
        e = dict(
            id=f"r72-{rule.lower()}-{kind.lower()}",
            title=f"R72 {rule} {P.NAMES[rule]} ({'original' if kind == 'O' else 're-tune'}): cross-index signal, 1-ITM",
            family="patterns-indicators",
            date="2026-10-16",
            doc=DOC,
            hypothesis=(f"Boss (16 Oct, \"any more strategies/pattern\", chose cross-index signals): {RULE_TXT[rule]}, read "
                        "from the five indices together, buying the 1-ITM option and taken as Donchian takes its trades, "
                        "earns after real costs and beats random entries on the same days. Gates as R51 (BH over the 12 "
                        "R72 tests)."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK), R51 Amendment A clean "
                          "stores, 20 Nov 2024 - 13 Oct 2026"),
            period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)",
                        holdout=("1 Apr - 13 Oct 2026, read once for R72 (16 Oct); the window was already read for other "
                                 "ideas by R51-R71 (weak)")),
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=["signal source: the five indices' 5-min bars read together (underlying); no option-chain data, "
                      "no option chart"],
            model_or_rule=f"{P.NAMES[rule]}: {RULE_TXT[rule]}; setting {setting(rule, t['setting'])} ({what})",
            settings="research/hunt/r72/r72rules.py RULES, research/hunt/r71/r71cfg.py EXITS; PREREG.md",
            prereg=PREREG,
            variants_tried=1 if kind == "O" else 6,
            results=dict(
                summary=(f"Design: {line(dz)}. Walk-forward net {rs(t['wf_net'])}. Final: {line(t['final'])}, "
                         f"{t['final'].get('per_month')} trades a month; random-entry twin p {t['p']} (twin mean "
                         f"{rs(t['twin_mean'])}), BH q {t['q']}. Verdict: {t['verdict']} "
                         f"({', '.join(t['fails']) or 'all gates'}).{slip} Donchian XB on the same final days: "
                         f"{rs(dfx['net'])} on {dfx['trades']} trades; daily correlation {t['corr_donchian']}, "
                         f"same trade as Donchian {t['overlap_donchian']}."),
                costs="dated charges, R17 liq fills, next-minute fills, stops on the minute low with gap fills at the "
                      "worse open, locks from the next minute, one held contract, 1 lot in force"),
            holdout_read=True,
            verdict="paper-only" if passed else "rejected",
            notes=("Signal on the underlying (the five indices); research only, nothing changes paper or live."
                   + (" PASSED every gate; proposed to Boss for PAPER only (not running until Boss switches it on). "
                      "Labelled a duplicate of Donchian (daily correlation >= 0.6): it is Donchian 20 kept only when "
                      "at least 4 of the 5 indices break on the same bar, so it is proposed as a filter on / "
                      "replacement for the Donchian paper arm, not as an extra arm beside it." if passed and dup else "")
                   + (" Beat random entries (twin p < 0.05) but failed other gates." if (t["p"] or 1) < 0.05 and not passed
                      else "")),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
