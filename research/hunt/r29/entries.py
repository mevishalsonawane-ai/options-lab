"""Writes research/experiments/entries/r29-*.yaml (4 rules + the GEX descriptive check).

    python3 research/hunt/r29/entries.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENT = HERE.parents[1] / "experiments" / "entries"
NOTE = {"X1": "GEX_short (writers short both sides) in its top third vs earlier expiries",
        "X2": "GEX_net (calls minus puts, US convention) in its bottom third vs earlier expiries",
        "X3": "both X1 and X2", "X4": "X1, buying only legs priced at or below forecast realized vol (iv_rv <= 1.0)"}
BASE = dict(family="flow-oi", date="2026-10-14", doc="research/HUNT_R29_GAMMA_EXPOSURE.md",
            data_version="Dhan lake on Drive: opt_{NIFTY,SENSEX}_WEEK (ATM+-10 with OI), index_1m, INDIAVIX; R26 day files",
            period={"design": "ALL expiries with >= 20 earlier GEX values and >= 10 earlier regime values (expanding, "
                              "point-in-time): NIFTY 241, SENSEX 100 decision days (2021-2026)"},
            instruments=["NIFTY", "SENSEX"], prereg="research/hunt/r29/PREREG.md", settings="research/hunt/r29/r29gex.py",
            holdout_read=None)
COSTS = "app charges at today's rates; next-open fills + 5 bps + 1/2/4-tick half-spread; x2 spread stress"


def main():
    r = json.loads((HERE / "results" / "results.json").read_text())
    for rid in ("X1", "X2", "X3", "X4"):
        v = r["rules"][rid]
        m = v["m"]
        s = (f"{m['trades']} expiries ({m['legs']} legs: Rs 15-30 CE + PE at 14:00, Rs 1,000 each, half at 2x + step lock), "
             f"net Rs {m['net']:+,.0f} ({m['per_1000']:+.0f} per Rs 1,000), hit {m['hit_pct']}%, max DD Rs {m['max_dd']:+,.0f}; "
             f"random same-size expiry sets mean Rs {v['control']['mean']:+,.0f}, p {v['control']['p']}, BH q {v['q']} "
             f"(family {r['bh_size']}). FAILED the gates.")
        e = dict(id=f"r29-{rid.lower()}", title=f"Gamma exposure {rid}: {NOTE[rid]}",
                 hypothesis="Heavy dealer short gamma at 14:00 predicts afternoon moves big enough for a cheap CE + PE pair to pay.",
                 features=["option-chain OI x BS gamma (GEX)", "ATM implied vol", "realized vol"], model_or_rule=NOTE[rid],
                 variants_tried=4, results=dict(summary=s, costs=COSTS), verdict="paper-only" if v["passes"] else "rejected",
                 notes="BH across R21-R29 (48). GEX at 14:00 ranks with a bigger afternoon move than ATM IV prices "
                       "(Spearman 0.33), but that is mostly low IV: given IV the link is -0.11.", **BASE)
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    d, c = r["describe"], r["confound"]
    s = (f"{d['all']['n']} decision days: afternoon realized / ATM-implied variance, mean {d['all']['move_mean']}, median "
         f"{d['all']['move_median']}. GEX_short Spearman with it {d['s_short']['spearman_move']} (p {d['s_short']['p']}); "
         f"terciles median " + ", ".join(f"{k} {x['move_median']}" for k, x in d['s_short']['terciles'].items()) +
         f". GEX vs ATM IV {c['rho_gex_iv']}, IV vs move {c['rho_iv_move']}; partial GEX-move given IV "
         f"{c['partial_rho_gex_move_given_iv']}. The pair loses in every tercile.")
    e = dict(id="r29-describe", title="Gamma exposure vs the afternoon move (descriptive)",
             hypothesis="Descriptive: does GEX at 14:00 relate to how much the afternoon moves versus what options price?",
             features=["GEX", "ATM implied vol", "realized vol"], model_or_rule="descriptive", variants_tried=None,
             results=dict(summary=s, costs="none (descriptive)"), verdict="not-a-test",
             notes="The apparent GEX effect is mostly an implied-vol effect (cheap options relative to the move).", **BASE)
    (ENT / "r29-describe.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote 5")


if __name__ == "__main__":
    main()
