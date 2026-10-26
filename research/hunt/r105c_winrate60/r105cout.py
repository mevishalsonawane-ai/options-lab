"""R105C: results -> research/experiments/entries/r105c-*.yaml (24, failures included).

    python research/hunt/r105c_winrate60/r105cout.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[1] / "experiments" / "entries"
NAMES = {"C": "R72 Breadth Donchian", "B": "R51 Donchian 20 + Supertrend"}


def rs(x):
    x = round(float(x))
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def main():
    des = json.loads((HERE / "results" / "design.json").read_text())
    fin = json.loads((HERE / "results" / "final.json").read_text())
    import r105c as Cc
    for arm in Cc.ARMS:
        for rule in Cc.RULES:
            d, w, f = des[arm][rule]["design"], des[arm][rule]["wf"], fin[arm][rule]
            k = rule[1:].rstrip("B")
            what = f"sell at +{k}% of the premium" + (f", breakeven once +{int(k) / 2:g}%" if rule.endswith("B") else "")
            v = Cc.verdict(w, f)
            e = dict(
                id=f"r105c-{arm.lower()}-{rule.lower()}", title=f"R105C {rule} ({what}) on {NAMES[arm]} for a 60% win rate",
                family="trade-management", date="2026-10-26", doc="research/HUNT_R105C_WINRATE60.md",
                hypothesis=("Boss (26 Oct: \"make the winrate 60%, even the profit is less\"): " + what + " on top of the "
                            f"paper exits gives {NAMES[arm]} a win rate >= 60% with a positive net in walk-forward and final."),
                data_version="Drive lake (index_1m, options_rolling_1m WEEK), R51 Amendment A clean stores, 20 Nov 2024 - 13 Oct 2026",
                period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward Apr 2025 - Mar 2026)",
                            holdout="1 Apr - 13 Oct 2026, read once for R105C; already read by ~27 studies (weak evidence)"),
                instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
                features=["signal source: the index (Donchian 20 on 5-minute index bars); the target is on the option premium"],
                model_or_rule=f"{NAMES[arm]}, exits P + {rule}: {what}",
                settings="research/hunt/r105c_winrate60/r105c.py; PREREG.md", prereg="research/hunt/r105c_winrate60/PREREG.md",
                variants_tried=1,
                results=dict(summary=(f"Design {d['trades']} trades, win {round(100 * d['win'])}%, net {rs(d['net'])}; walk-forward "
                                      f"win {round(100 * w['win'])}%, net {rs(w['net'])}; final {f['trades']} trades, win "
                                      f"{round(100 * f['win'])}%, net {rs(f['net'])}, avg win {rs(f['avg_win'])} / loss "
                                      f"{rs(f['avg_loss'])} (today: win {round(100 * fin[arm]['today']['win'])}%, net "
                                      f"{rs(fin[arm]['today']['net'])}). Verdict: {v}."),
                             costs="dated charges, R17 liquidity fills, next-minute fills, limit target fills, 1 lot"),
                holdout_read=True, verdict="rejected",
                notes="Signal on the underlying. Win rate 60% only at +5%, where the net is negative; nothing changes.")
            (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    import sys
    sys.path.insert(0, str(HERE))
    main()
