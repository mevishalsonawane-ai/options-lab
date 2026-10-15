"""Writes research/experiments/entries/rc2-*.yaml: one per pre-registered rule (4 exits each) plus the anatomy.
python3 -m research.hunt.rc2.entries"""
import os

import pandas as pd
import yaml

from . import config as C
from .signals import RULES

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
ENT = os.path.join(ROOT, "research", "experiments", "entries")
DOC = "research/HUNT_RC2_CRYPTO_OPTIONS.md"
PREREG = "research/hunt/rc2/PREREG.md"
DATA = ("Delta Exchange India public API: .DEXBTUSD/.DEETHUSD 5-min index 2024-03-01..2026-10-13, MARK 5-min candles of "
        "1,912 BTC/ETH daily expiries (956 per coin) (2024-03-02..2026-10-13, strikes +-3.5% fine grid + 3.5-8% OTM); live chain polls "
        "13 Oct 2026; Deribit DVOL and funding (hourly) and option trades (history API). Raw data in the session "
        "scratchpad, not in git.")
PERIOD = dict(design="2024-03-02..2026-04-12 (772 expiries per coin)",
              holdout="2026-04-13..2026-10-13 12:00 UTC (184 expiries per coin; read once; partly seen by NN_CRYPTO/STRAD_CRYPTO)")
# Instruments must be from the app knowledge pack's known set (KnowledgeTest): crypto entries use "CRYPTO".
FEATURES = {
    "R1_asia_orb": ["00:00-01:00 UTC range", "5-min close"],
    "R2_eu_orb": ["07:00-08:00 UTC range", "5-min close"],
    "R3_us_orb": ["13:30-14:00 UTC range", "5-min close"],
    "R4_vol_burst": ["15-min index move", "DVOL (last completed hour)"],
    "R5_dvol_jump": ["DVOL hour-on-hour change", "1-h index move"],
    "R6_hero": ["15-min index move", "DVOL", "option premium band 0.02-0.10% of spot"],
    "R7_funding_rev": ["Deribit funding 8h rate vs its 60-day 2.5/97.5 percentiles", "1-h index move"],
    "R8_pdl_break": ["previous UTC day high/low", "5-min close"],
}


def main():
    d = pd.read_csv(os.path.join(C.OUT, "design.csv"))
    h = pd.read_csv(os.path.join(C.OUT, "holdout.csv"))
    m = d.merge(h, on=["rule", "exit"], suffixes=("", "_h"))
    for name, rule in RULES.items():
        g = m[m.rule == name]
        p = g[g.pick].iloc[0]
        rows = "; ".join(f"{r.exit}: design n {r.n} net {r.mean_ret:+.1%}/trade Rs {r['net_inr@100k']:+,.0f}, holdout n {r.n_h} "
                         f"{r.mean_ret_h:+.1%} Rs {r['net_inr@100k_h']:+,.0f}" for _, r in g.iterrows())
        summ = (f"Pick {p.exit} (best design mean). Design: {p.n} trades, {p['win_rate@100k']:.0%} win, {p.mean_ret:+.1%} a trade net "
                f"(gross {p.gross_ret:+.1%}), Rs {p['net_inr@100k']:+,.0f} at Rs 1 lakh / 2% a trade; same-clock twin p {p.p_clock:.3f} "
                f"(BH q {p.q_clock:.3f}), direction twin p {p.p_dir:.3f}; walk-forward Rs {p['wf_inr@100k']:+,.0f}; stress "
                f"Rs {p['stress_net_inr@100k']:+,.0f}. Holdout: {p.n_h} trades, {p.mean_ret_h:+.1%} a trade, Rs {p['net_inr@100k_h']:+,.0f} "
                f"(after VDA tax Rs {p['vda@100k_h']:+,.0f}). Gates passed on design: {'all' if p.design_pass else 'not all'}. "
                f"All exits: {rows}.")
        e = dict(id=f"rc2-{name.split('_')[0].lower()}", title=f"RC2 crypto options {name}: {rule.title}", family="mcx-crypto",
                 date="2026-10-13", doc=DOC,
                 hypothesis=f"Buying a Delta India BTC/ETH option on '{rule.title}', with a stop-loss and a profit lock, makes "
                            "money after real costs and beats random same-clock entries.",
                 data_version=DATA, period=PERIOD, instruments=["CRYPTO"],
                 features=FEATURES[name],
                 model_or_rule=f"{rule.title}; strike {rule.strike}; expiry >= {rule.min_left_h:g} h left; max hold {rule.max_hold_h:g} h; "
                               "exits: stop -40%/-25% (hero -50%/-70%) x step lock / trailing lock",
                 settings="research/hunt/rc2/signals.py, exits.py, config.py", prereg=PREREG, variants_tried=4,
                 results=dict(summary=summ,
                              costs="Delta India fee min(0.01% notional, 3.5% premium) + 18% GST per side; spread model (wider "
                                    "of two live-chain fits) on entry and exit; exits less 1 tick + 1%; stress spread x1.5 and fee 0.03%"),
                 holdout_read=True, verdict="rejected",
                 notes="One of 8 RC2 rules (32 variants, one BH family). Every trade had a stop and a profit lock (Boss's crypto "
                       "rule). Design was re-run once after a data fix (listing-glitch marks, PREREG amendment 4).")
        with open(os.path.join(ENT, f"{e['id']}.yaml"), "w") as f:
            f.write(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    a = dict(id="rc2-anatomy", title="RC2 step 1: anatomy of Delta India BTC/ETH daily options (design data only)",
             family="market-facts", date="2026-10-13", doc=DOC,
             hypothesis="Describe spreads, premium decay, zero-to-hero odds, realised vs implied moves by hour and what "
                        "precedes big days, before choosing rules.",
             data_version=DATA, period=dict(design=PERIOD["design"]), instruments=["CRYPTO"],
             features=["live bid/ask by strike and expiry", "ATM straddle by hours left", "cheap OTM option multiples",
                       "hourly realised vs DVOL-implied", "big-day precursors", "Delta mark vs Deribit trade IV"],
             model_or_rule="descriptive", settings="research/hunt/rc2/anatomy.py, deribit_check.py", prereg=None,
             variants_tried=None,
             results=dict(summary="Delta daily ATM straddle 1.72% (BTC, 767 days) / 2.53% (ETH, 764) of spot at 24 h left, "
                                  "0.66%/0.98% at 6 h (712/655 days). Cheap OTM options (0.02-0.10% of spot) bought 3 h before settlement "
                                  "closed at >= 3x on 17% (BTC, 3,554 options) / 13% (ETH, 2,185), 10x on 4%/4%; worth 0.68x/0.69x "
                                  "of entry at 11:50 on average. Realised/implied 1-h move 0.67-0.87 (BTC) / 0.73-0.90 (ETH) at 03:00-12:00 UTC, "
                                  "1.12-1.46 at 13:00-16:00 UTC (773 days). Delta mark IV is 0.88x (BTC, 230 h) / 0.92x "
                                  "(ETH, 227 h) Deribit trade IV.",
                          costs="none (descriptive)"),
             holdout_read=None, verdict="not-a-test")
    with open(os.path.join(ENT, "rc2-anatomy.yaml"), "w") as f:
        f.write(yaml.safe_dump(a, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    main()
