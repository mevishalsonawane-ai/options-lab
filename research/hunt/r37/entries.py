"""Writes research/experiments/entries/r37-*.yaml (4 rules, failures included).

    python3 research/hunt/r37/entries.py
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
        s = (f"{m['trades']} trades (NIFTY {v['index']['NIFTY']['trades']}, SENSEX {v['index']['SENSEX']['trades']}; "
             f"{v['direction']['CE']} CE / {v['direction']['PE']} PE), hit {m['hit_pct']}%, >= 4x {m['x4_pct']}%, net Rs "
             f"{m['net']:+,.0f} ({m['per_1000_risked']:+.0f} per Rs 1,000); max DD Rs {m['max_dd']:+,.0f}; without the best "
             f"day Rs {m['net_without_best_day']:+,.0f}; random-time twin mean Rs {v['twins']['mean']:+,.0f}, p {v['twins']['p']}, "
             f"BH q {v['q']} (family {r['bh_size']}). " + ("PASSED the gates." if v["passes"] else "FAILED the gates."))
        e = dict(id=f"r37-{rid.lower()}", title=f"Gamma flip {rid}: {v['note']}", family="flow-oi", date="2026-10-14",
                 doc="research/HUNT_R37_GAMMA_FLIP.md",
                 hypothesis="Spot crossing the 13:45 gamma-flip level (net GEX zero crossing) with volume, TWAP and heavyweight "
                            "confirmation starts a gamma squeeze that pays an ATM+-1 option after costs.",
                 data_version="Dhan lake on Drive: opt_{NIFTY,SENSEX}_WEEK (ATM+-10 with OI), index_1m, equity_1m HDFCBANK / "
                              "RELIANCE / ICICIBANK; R26 day files",
                 period={"design": "ALL expiries (nothing fitted): NIFTY 271, SENSEX 175 (130 with OI); R25 blocks reported"},
                 instruments=["NIFTY", "SENSEX"],
                 features=["net GEX profile (call OI - put OI) x BS gamma", "index TWAP", "option volume", "heavyweight stock minutes"],
                 model_or_rule=v["note"], settings="research/hunt/r37/r37flip.py", prereg="research/hunt/r37/PREREG.md",
                 variants_tried=4, results=dict(summary=s, costs="app charges at today's rates; next-open fills + 5 bps + "
                                                "1/2/4-tick half-spread; x2 spread stress"),
                 holdout_read=None, verdict="paper-only" if v["passes"] else "rejected",
                 notes="BH across R21-R26 and R34-R37 (52). US sign convention (calls minus puts); under the Indian reading no flip exists.")
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(r["rules"]))


if __name__ == "__main__":
    main()
