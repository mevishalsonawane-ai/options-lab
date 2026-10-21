"""Writes research/experiments/entries/r35-*.yaml (one per rule, failures included).

    python3 research/hunt/r35/entries.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENT = HERE.parents[1] / "experiments" / "entries"


def main():
    r = json.loads((HERE / "results" / "results.json").read_text())
    for rid, v in r["rules"].items():
        m = v["m"]
        s = (f"{m['trades']} trades on {sum(r['expiries'].values())} expiries (NIFTY {v['index']['NIFTY']['trades']}, SENSEX "
             f"{v['index']['SENSEX']['trades']}), hit {m['hit_pct']}%, >= 4x {m['x4_pct']}%, net Rs {m['net']:+,.0f} "
             f"({m['per_1000_risked']:+.0f} per Rs 1,000); worst day Rs {m['worst_day']:+,.0f}, max DD Rs {m['max_dd']:+,.0f}, "
             f"without the best day Rs {m['net_without_best_day']:+,.0f}; random-time twin mean Rs {v['twins']['mean']:+,.0f}, "
             f"p {v['twins']['p']}, BH q {v['q']} (family {r['bh_size']}). " + ("PASSED the gates." if v["passes"] else "FAILED the gates."))
        e = dict(id=f"r35-{rid.lower()}", title=f"Dual-Supertrend hero {rid}: {v['note']}", family="outside-strategies",
                 date="2026-10-14", doc="research/HUNT_R35_DUAL_SUPERTREND.md",
                 hypothesis="Boss's 1-minute Dual-Supertrend framework (ST 7,3 + 10,2 on the ATM+1 premium, option volume 2x "
                            "EMA10, index above TWAP, 3 heavyweights green, 14:00-14:45, Rs 2,000) pays after costs.",
                 data_version="Dhan lake on Drive: opt_{NIFTY,SENSEX}_WEEK (ATM+-10), index_1m NIFTY/SENSEX/INDIAVIX, "
                              "equity_1m HDFCBANK/RELIANCE/ICICIBANK; R26 day files; 13 Oct 2026 not in the lake",
                 period={"design": "ALL expiries (nothing fitted): NIFTY 271 (2021-08-05..2026-10-06), SENSEX 175 "
                                   "(2023-05-19..2026-10-08); R25 blocks reported"},
                 instruments=["NIFTY", "SENSEX"],
                 features=["supertrend on option premium", "option volume", "index TWAP", "heavyweight stock minutes"],
                 model_or_rule=v["note"], settings="research/hunt/r35/r35lib.py", prereg="research/hunt/r35/PREREG.md",
                 variants_tried=5, results=dict(summary=s, costs="app charges at today's rates; next-open fills + 5 bps + "
                                                "1/2/4-tick half-spread; entry limit 1.2x + 0.10 for 3 min; x2 spread stress"),
                 holdout_read=None, verdict="paper-only" if v["passes"] else "rejected",
                 notes="One of 5 R35 rules; BH across R21 9 + R25 9 + R26 10 + R34 11 + R35 5 = 44. The index has no volume: "
                       "VWAP is a time-weighted average since the open.")
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(r["rules"]))


if __name__ == "__main__":
    main()
