"""R71 experiment entries: one YAML per gated test (8 originals + 8 re-tunes, failures included) from results/*.json.

    python research/hunt/r71/r71entries.py && python -m research.experiments.validate
"""
from __future__ import annotations

import json

import yaml

import r71cfg as R
import r71rules as P
from r71report import rs, setting

ENTRIES = R.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R71_CHART_PATTERNS.md"
PREREG = "research/hunt/r71/PREREG.md"
RULE_TXT = {
    "P1": "15-min (or 30-min) inside bar; the first 5-min close beyond the mother bar within 30 minutes",
    "P2": "today's narrowest 15-min bar of the last N; the first 5-min close beyond it within 30 minutes",
    "P3": "5-min Bollinger(20,2) inside Keltner(20,1.5 ATR) for >= S bars, then the first close beyond a band",
    "P4": "5-min flag: a 3-bar pole >= K x ATR14, 3-10 flag bars (<= 50% retrace), close beyond the pole extreme",
    "P5": "5-min double bottom / top (two swing lows / highs within T x ATR14, neckline >= 1 ATR), neckline close break",
    "P6": "15-min Heikin-Ashi flip (no wick against it) after 2 opposite bars, with the trend (close vs EMA L)",
    "P7": "5-min three-bar reversal at a new day low / high (from 09:45 or 10:30)",
    "P8": "15-min swing structure (HH + HL / LH + LL, W-bar pivots), 5-min close beyond the last swing high / low",
}


def line(s: dict) -> str:
    if not s.get("trades"):
        return "0 trades"
    return (f"{s['trades']} trades on {s['days']} days, win {100 * s['win']:.0f}%, net {rs(s['net'])} "
            f"({rs(s['per_trade'])}/trade), avg win {rs(s['avg_win'])} / loss {rs(s['avg_loss'])}, worst day "
            f"{rs(s['worst_day'])}, max DD {rs(s['max_dd'])}, green days {100 * s['green_days']:.0f}%")


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
        e = dict(
            id=f"r71-{rule.lower()}-{kind.lower()}",
            title=f"R71 {rule} {P.NAMES[rule]} ({'original' if kind == 'O' else 're-tune'}): index chart pattern, 1-ITM",
            family="patterns-indicators",
            date="2026-10-16",
            doc=DOC,
            hypothesis=(f"Boss (16 Oct, \"find something new like Donchian, find chart patterns\"): {RULE_TXT[rule]} on the "
                        "index, buying the 1-ITM option and taken as Donchian takes its trades, earns after real costs "
                        "and beats random entries on the same days; it would add to Donchian, not copy it. Gates as R51 "
                        "(BH over the 16 R71 tests)."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK), R51 Amendment A clean "
                          "stores, 20 Nov 2024 - 13 Oct 2026"),
            period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)",
                        holdout=("1 Apr - 13 Oct 2026, read once for R71 (16 Oct); the window was already read for other "
                                 "ideas by R51-R70 (weak)")),
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=["signal source: index (underlying) 5-min / 15-min bars; no option-chain data, no option chart"],
            model_or_rule=f"{P.NAMES[rule]}: {RULE_TXT[rule]}; setting {setting(rule, t['setting'])} ({what})",
            settings="research/hunt/r71/r71rules.py RULES, r71cfg.py EXITS; PREREG.md",
            prereg=PREREG,
            variants_tried=1 if kind == "O" else 6,
            results=dict(
                summary=(f"Design: {line(dz)}. Walk-forward net {rs(t['wf_net'])}. Final: {line(t['final'])}, "
                         f"{t['final'].get('per_month')} trades a month; random-entry twin p {t['p']} (twin mean "
                         f"{rs(t['twin_mean'])}), BH q {t['q']}. Verdict: {t['verdict']} "
                         f"({', '.join(t['fails']) or 'all gates'}). Donchian XB on the same final days: "
                         f"{rs(dfx['net'])} on {dfx['trades']} trades; daily correlation {t['corr_donchian']}, "
                         f"same trade as Donchian {t['overlap_donchian']}."),
                costs="dated charges, R17 liq fills, next-minute fills, stops on the minute low with gap fills at the "
                      "worse open, locks from the next minute, one held contract, 1 lot in force"),
            holdout_read=True,
            verdict="paper-only" if t["verdict"] == "PASS" else "rejected",
            notes=("Signal on the underlying (index); research only, nothing changes paper or live."
                   + (" Beat random entries on the same days and sides (fading day extremes loses less than random "
                      "timing), but still lost money after costs." if (t["p"] or 1) < 0.05 and t["final"]["net"] <= 0
                      else "")),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
