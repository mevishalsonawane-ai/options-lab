"""R74 experiment entries: one YAML per gated test (5 originals + 5 re-tunes, failures included) from results/*.json,
plus the strategy map itself (a measurement, not a test).

    python research/hunt/r74/r74entries.py && python -m research.experiments.validate
"""
from __future__ import annotations

import json

import yaml

import r74study as X

X.use_r74()
R, P = X.R, X.P
from r71entries import line  # noqa: E402
from r71report import rs, setting  # noqa: E402

ENTRIES = R.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R74_STRATEGY_MAP.md"
PREREG = "research/hunt/r74/PREREG.md"
FAMILY = {"M1": "patterns-indicators", "V1": "flow-oi", "V2": "daily-filters", "B1": "patterns-indicators",
          "B2": "daily-filters"}
RULE_TXT = {
    "M1": "at 14:45, the return since the previous close beyond k x the 20-day mean absolute daily return, on a day whose "
          "range so far is at least its 20-day median; buy that way for the last 30 minutes",
    "V1": "at 10:30 / 11:30 / 12:30 / 13:30, index up >= k since its open while India VIX is up >= 2% since its first "
          "print: the put (mirror: the call); first signal a day",
    "V2": "a Donchian-20 break taken only when India VIX moved the confirming way over the last 30 minutes (calls: "
          "falling by more than x; puts: rising by more than x)",
    "B1": "the share of the 59 large caps above their own session VWAP jumps to >= T from <= 1 - T within 30 minutes: "
          "calls on NIFTY / BANKNIFTY / FINNIFTY / SENSEX (mirror: puts)",
    "B2": "a Donchian-20 break on NIFTY / BANKNIFTY / FINNIFTY / SENSEX taken only when the large caps' volume in that "
          "bar is >= m x its mean at that time of day over the previous 20 sessions",
}
CONTEXT = {"M1": "the index only", "V1": "the index, with India VIX (computed from option prices) as the partner condition",
           "V2": "the index, with India VIX as a filter", "B1": "the 59 large caps' prices and volume (underlying stocks)",
           "B2": "the index, with the large caps' volume as a filter"}


def main():
    des = json.loads((R.RESULTS / "design.json").read_text())
    fin = json.loads((R.RESULTS / "final.json").read_text())
    dfx, c1 = fin["donchian"]["XB"], fin["breadth_donchian"]
    n = 0
    for k, t in fin["tests"].items():
        rule, kind = t["rule"], t["kind"]
        r = des["rules"][rule]
        dz = r["grid"][t["setting"]]["design"]
        what = "original (fixed before any result)" if kind == "O" else \
            f"re-tune (2 parameters x 3 exits; walk-forward picks {', '.join(r['wf_picks'])}; final pick = best design net)"
        passed = t["verdict"] == "PASS"
        slip = f" At twice the slippage: final {rs(t['slip2']['net'])}." if t.get("slip2") else ""
        e = dict(
            id=f"r74-{rule.lower()}-{kind.lower()}",
            title=f"R74 {rule} {P.NAMES[rule]} ({'original' if kind == 'O' else 're-tune'}): index signal, 1-ITM",
            family=FAMILY[rule],
            date="2026-10-16",
            doc=DOC,
            hypothesis=(f"Boss (16 Oct, \"except Donchian, what else could be a strategy or arm\"; R74 strategy map rank "
                        f"of the untried families): {RULE_TXT[rule]}, buying the 1-ITM option and taken as Donchian "
                        "takes its trades, earns after real costs and beats random entries on the same days. Gates as "
                        "R51 (BH over the 10 R74 tests)."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m incl. INDIAVIX, options_rolling_1m WEEK, "
                          "equity_1m 59 large caps), R51 Amendment A clean stores, 20 Nov 2024 - 13 Oct 2026"),
            period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)",
                        holdout=("1 Apr - 13 Oct 2026, read once for R74 (16 Oct); the window was already read for other "
                                 "ideas by R49-R72 (weak)")),
            instruments=list(P.LARGE) if rule in ("B1", "B2") else ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY",
                                                                    "SENSEX"],
            features=[f"signal source: {CONTEXT[rule]}; no option chart"],
            model_or_rule=f"{P.NAMES[rule]}: {RULE_TXT[rule]}; setting {setting(rule, t['setting'])} ({what})",
            settings="research/hunt/r74/r74rules.py RULES, research/hunt/r71/r71cfg.py EXITS; PREREG.md",
            prereg=PREREG,
            variants_tried=1 if kind == "O" else 6,
            results=dict(
                summary=(f"Design: {line(dz)}. Walk-forward net {rs(t['wf_net'])}. Final: {line(t['final'])}, "
                         f"{t['final'].get('per_month')} trades a month; random-entry twin p {t['p']} (twin mean "
                         f"{rs(t['twin_mean'])}), BH q {t['q']} (q {t['q38']} across the 38 tests of 16 Oct). Verdict: "
                         f"{t['verdict']} ({', '.join(t['fails']) or 'all gates'}).{slip} Same final days: Donchian XB "
                         f"{rs(dfx['net'])} on {dfx['trades']} trades (daily correlation {t['corr_donchian']}, same "
                         f"trade {t['overlap_donchian']}); Breadth Donchian {rs(c1['net'])} on {c1['trades']} trades "
                         f"(correlation {t['corr_c1']}, same trade {t['overlap_c1']})."),
                costs="dated charges, R17 liq fills, next-minute fills, stops on the minute low with gap fills at the "
                      "worse open, locks from the next minute, one held contract, 1 lot in force"),
            holdout_read=True,
            verdict="paper-only" if passed else "rejected",
            notes=("Signal on the underlying; research only, nothing changes paper or live."
                   + (" PASSED every gate; proposed to Boss for PAPER only (not running until Boss switches it on)."
                      if passed else "")
                   + (" Labelled a duplicate (daily correlation >= 0.6 with Donchian or Breadth Donchian)."
                      if t.get("duplicate") else "")
                   + (" Beat random entries (twin p < 0.05) but failed other gates." if (t["p"] or 1) < 0.05 and not passed
                      else "")),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    m = dict(
        id="r74-map", title="R74 strategy map: every family tried here, the outside evidence, the untried families ranked",
        family="diagnosis", date="2026-10-16", doc=DOC,
        hypothesis="Inventory and literature review (no test): what families remain untried after R1-R72.",
        data_version="research/experiments/entries (1,190 entries before R74), research/HUNT_*.md, published papers",
        period=dict(design="n/a"), instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        features=["n/a"], model_or_rule="map and ranking (evidence x fit x data x low overlap with Donchian)",
        settings=DOC, prereg=None, variants_tried=None,
        results=dict(summary="Ranks 1-5 tested in R74 Part B (PREREG.md); ranks 6-8 are risk tools proposed for later.",
                     costs="n/a"),
        holdout_read=None, verdict="not-a-test")
    (ENTRIES / "r74-map.yaml").write_text(yaml.safe_dump(m, sort_keys=False, allow_unicode=True, width=120))
    print(n + 1, "entries")


if __name__ == "__main__":
    main()
