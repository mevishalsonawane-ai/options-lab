"""R202 report: results/report.md (every test, every period) and the 42 log entries.

    python3 -I research/hunt/r202_price_action25/r202report.py      # after decide

Plain-English notes, 21 Nov 2026.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r202cfg as K  # noqa: E402
import r202pat as P  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
XNAME = {"X": "plain exit (XB: -15% / +30% / 30 min)", "V": "V5 as the arms (XL + breakeven +10% + L500 step lock)"}
RULES = {
    "P01": "a bullish 5-min bar that dips to within 0.25 ATR of a 3+-touch level from above and closes above it -> call",
    "P02": "a bearish 5-min bar that pokes to within 0.25 ATR of a 3+-touch level from below and closes below it -> put",
    "P03": "pivot structure up (or down) and a pullback bar to the 20-bar EMA that closes back with the trend",
    "P04": "a touch of the line through the last two rising pivot lows (falling highs) that holds and closes with the line",
    "P06": "a 3+-touch level broken by > 0.25 ATR, the first retest within 12 bars that holds",
    "P07": "a close beyond a 12-bar box whose span is <= 1.5 ATR",
    "P09": "a new higher pivot low confirmed while the last two pivot highs rise -> call",
    "P10": "a new lower pivot high confirmed while the last two pivot lows fall -> put",
    "P11": "two pivot highs within 0.25 ATR, 5-40 bars apart, a 1 ATR dip between; the first close below that dip -> put",
    "P12": "the mirror of the double top -> call",
    "P13": "three pivot highs with the middle >= 0.5 ATR above both shoulders (within 0.5 ATR of each other); close below the neckline -> put",
    "P14": "the mirror -> call",
    "P15": "flat top (two highs within 0.25 ATR) and rising lows; the first close above the top -> call",
    "P16": "flat bottom and falling highs; the first close below the bottom -> put",
    "P17": "falling highs and rising lows; the first close beyond either line",
    "P18": "a >= 3 ATR 6-bar pole, a 3-12 bar flag holding half the pole; a close beyond the flag in the pole's direction",
    "P19": "converging rising (falling) pivots; a close through the lower (upper) line -> put (call)",
    "P21": "a 3-bar >= 2 ATR move closing beyond the last pivot; the first return into the last opposite bar before it that closes with the move",
    "P22": "a 3-bar gap >= 0.3 ATR; the first return into it that closes with the gap",
    "P24": "two pivot lows (highs) within 0.2 ATR; the first bar that trades through them and closes back -> call (put)",
    "P25": "a bar with a wick >= 2x body and >= 60% of a >= 0.8 ATR range at the 12-bar extreme -> against the wick's direction",
}


def _r(x):
    return "-" if x is None else f"{x:,}"


def _s(s):
    if not s or not s.get("trades"):
        return "0 trades"
    return (f"{s['trades']:,} tr, {s['win']}% win, Rs {s['net']:,}, worst month Rs {s['worst_month']['net']:,} "
            f"({s['worst_month']['month']}), DD Rs {s['max_dd']:,}")


def main():
    d = json.loads((K.RESULTS / "design.json").read_text())
    h = json.loads((K.RESULTS / "holdout.json").read_text()) if (K.RESULTS / "holdout.json").exists() else {}
    dec = json.loads((K.RESULTS / "decision.json").read_text()) if (K.RESULTS / "decision.json").exists() else {}
    ks = [f"{p}-{x}" for p in P.PATTERNS for x in K.EXITS]
    L = ["# R202 results: 21 price-action patterns, 1 lot, dated charges and R17 fills", "",
         "Design (walk-forward, 5 folds) 1 Dec 2021 - 31 Mar 2026; holdout 1 Apr - 13 Nov 2026 (read once). q = BH over "
         f"m = {K.M_TOTAL:,}; q42 = within R202's 42 tests. Signal source: the index's 5-minute bars; 1-ITM option bought.", "",
         "| test | design trades | win % | net | net w/o top 5 | worst month | max DD | folds + | F1 | F2 | F3 | F4 | F5 "
         "| twin p | q | q42 | holdout trades | win % | net | worst month | verdict |",
         "|---|" + "---:|" * 19 + "---|"]
    for k in ks:
        a = d[k]
        s = a["design"]
        f = a["folds"]
        ho = h.get(k, {})
        v = dec.get(k, {})
        verdict = "PASS" if v.get("passes") else ("fail" if v else "-")
        L.append(f"| {k} | {s['trades']:,} | {s['win']} | {_r(s['net'])} | {_r(s['net_wo_top5'])} | "
                 f"{_r(s['worst_month']['net'])} | {_r(s['max_dd'])} | {a['folds_pos']}/5 | "
                 + " | ".join(_r(f[x]['net']) for x in K.FOLDS) +
                 f" | {a['twin_p']:.3f} | {a['q']:.3g} | {a['q_r202']:.3g} | {ho.get('trades', '-')} | {ho.get('win', '-')} | "
                 f"{_r(ho.get('net'))} | {_r(ho.get('worst_month', {}).get('net'))} | {verdict} |")
    L += ["", "## Why each failed", ""]
    for k in ks:
        v = dec.get(k)
        if v and not v["passes"]:
            L.append(f"- **{k}** ({v['name']}, {v['exit']}): " + "; ".join(v["fails"]))
    L += ["", "## Net by index (design)", "", "| test | " + " | ".join(K.UNDS) + " |", "|---|" + "---:|" * len(K.UNDS)]
    for k in ks:
        L.append(f"| {k} | " + " | ".join(_r(d[k]["design"]["by_und"][u]) for u in K.UNDS) + " |")
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    if dec:
        write_entries(d, h, dec)
    print("report written", flush=True)


def write_entries(d, h, dec):
    import yaml
    for p in P.PATTERNS:
        for x in K.EXITS:
            k = f"{p}-{x}"
            a, v = d[k], dec[k]
            e = {
                "id": f"r202-{p.lower()}-{x.lower()}",
                "title": f"R202 price action: {P.NAMES[p]}, {XNAME[x]}",
                "family": "patterns-indicators",
                "date": "2026-11-21",
                "doc": "research/hunt/r202_price_action25/RESULT.md",
                "hypothesis": (f"{P.NAMES[p]} on the index's 5-minute bars, buying the 1-ITM option, makes money after "
                               "charges in the walk-forward design and the holdout, beats random entries, inside the "
                               "account stop, after BH"),
                "data_version": "Drive lake copy of 21 Nov 2026 (~/.cache/iraalgo/r202/lake: index_1m, options_rolling_1m WEEK "
                                "with the 20 Oct - 13 Nov 2026 fill); R51 clean stores 1 Dec 2021 - 13 Nov 2026",
                "period": {"design": "2021-12-01..2026-03-31 (walk-forward: 5 folds in time order; nothing fitted)",
                           "holdout": "2026-04-01..2026-11-13; read once (results/HOLDOUT_READ)"},
                "instruments": list(K.UNDS),
                "features": ["signal source: the index (5-minute bars from 1-minute index minutes; pivots known 3 bars late)",
                             "buy the 1-ITM option; never on the index's expiry day; one position at a time, 3 a day"],
                "model_or_rule": f"{RULES[p]}; exit {XNAME[x]}",
                "settings": "research/hunt/r202_price_action25/r202pat.py, r202cfg.py; PREREG.md sections 3-5",
                "prereg": "research/hunt/r202_price_action25/PREREG.md",
                "variants_tried": 1,
                "results": {
                    "summary": (f"design {_s(a['design'])}; {a['folds_pos']} of 5 folds positive; holdout {_s(h.get(k))}; "
                                f"twin p {a['twin_p']:.3f}, BH q {a['q']:.3g} (m = {K.M_TOTAL:,}). "
                                + ("PASSES" if v["passes"] else "Fails: " + "; ".join(v["fails"]))),
                    "costs": "dated Zerodha charges, R17 fills, 1 lot",
                },
                "holdout_read": True,
                "verdict": "paper-only" if v["passes"] else "rejected",
                "notes": "Research only; nothing in the app changes. Earlier tests of the same concept are cited in RESULT.md.",
            }
            (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))


if __name__ == "__main__":
    main()
