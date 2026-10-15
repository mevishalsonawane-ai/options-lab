"""Writes research/experiments/entries/r47-*.yaml: the study, every signal family explored (failures included) and every
candidate sent to the holdout.

    python3 research/hunt/r47/entries.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

import r47cfg as K

HERE = Path(__file__).resolve().parent
ENT = HERE.parents[1] / "experiments" / "entries"
FAMILY = {"U1_orb": "orb", "U2_gap": "overnight", "U3_lateday": "overnight", "U4_donchian": "patterns-indicators",
          "U5_daily_mr": "patterns-indicators", "U6_ema": "patterns-indicators", "U7_bigbar": "patterns-indicators",
          "U8_pdhl": "patterns-indicators", "U9_vix": "daily-filters", "U10_trendday": "patterns-indicators",
          "C1_pcr": "flow-oi", "C2_ivcheap": "option-structure", "C3_oishift": "flow-oi",
          "O1_prem_orb": "patterns-indicators", "O2_prem_mom": "patterns-indicators", "E1_event_vol": "calendar-events",
          "E2_expiry_vol": "calendar-events", "E3_expiry_hero": "calendar-events", "M1_gbm": "ml"}
BASE = dict(date="2026-10-15", doc="research/HUNT_R47_RELAXED_OPTION_BUYING.md",
            data_version="Drive lake gdrive:IraAlgo-data-lake: options_rolling_1m opt_{NIFTY,BANKNIFTY,FINNIFTY,SENSEX}_"
                         "{WEEK,MONTH} 2021-2026, index_1m, index_daily; foreign-underlying rows dropped (amendment 2)",
            period={"design": "1 Aug 2021 - 31 Oct 2025 (walk-forward blocks B1 2021-22, B2 2023, B3 2024, B4 Jan-Oct 2025)",
                    "holdout": "10 Nov 2025 - 13 Oct 2026, read once for 10 frozen candidates (1-9 Nov 2025 embargo)"},
            prereg="research/hunt/r47/PREREG.md", settings="research/hunt/r47/r47cfg.py")
COSTS = ("research/obuy Costs('dated') on every order of every leg + R17 'liq' fills (5 bps + 1/2/4 ticks by traded "
         "lots; stops 10 bps, 2x ticks), stray-print guard; one fixed contract per leg; Rs 20,000 premium budget")


def main():
    d = json.loads((HERE / "results" / "design_v3.json").read_text())
    cands = json.loads((HERE / "results" / "candidates.json").read_text())["candidates"]
    hold = {c["id"]: c for c in json.loads((HERE / "results" / "holdout_amended.json").read_text())["candidates"]}
    sent_by_fam = {}
    for c in cands:
        sent_by_fam.setdefault(c["fam"], []).append(c["id"])
    n = 0
    for fam, x in d["by_signal_family"].items():
        spec = K.FAMILIES[fam]
        b = x["best_100"]
        best = ("none with 100+ trades" if b is None else
                f"best single variant with 100+ trades (selection-biased): {b['und']} {b['instr']} exit {b['exit']}, "
                f"{b['n']} trades, Rs {b['npt']:+.0f}/trade, {b['green']}/{b['blocks_with_trades']} blocks green")
        fams = [f for f in d["families_detail"] if f["fam"] == fam]
        qual = [f"{f['und']} {f['kind']} (WF {f['wf']['oos_n']} OOS trades, Rs {f['wf']['oos_npt']:+.0f}/trade)" for f in fams if f["qualifies"]]
        e = dict(BASE, id=f"r47-{fam.lower().replace('_', '-')}",
                 title=f"R47 relaxed option buying, family {fam} ({K.SIGNAL_SOURCE[spec['label']].split(' - ')[0]})",
                 family=FAMILY[fam],
                 hypothesis=f"Some version of {fam} (params {spec['params']}) buying index options (any strike, near or "
                            f"month expiry, debit spreads, straddles; intraday to 5-session holds; regime filters) makes money "
                            f"after real costs on one fixed contract.",
                 instruments=list(K.UNDS), features=[K.SIGNAL_SOURCE[spec["label"]]],
                 model_or_rule=f"{fam}: see research/hunt/r47/PREREG.md table and r47sig.py",
                 variants_tried=int(x["variants"]),
                 results=dict(summary=(f"Design only. {x['variants']} variants (params x instrument x exit x 14 filters); "
                                       f"{x['with_100']} with 100+ trades, of which {x['positive_100']} net positive; median "
                                       f"Rs {x['median_npt_100']}/trade. {best}. Walk-forward qualifying families: "
                                       f"{'; '.join(qual) or 'none'}. Sent to the holdout: {', '.join(sent_by_fam.get(fam, [])) or 'none'}."),
                              costs=COSTS),
                 holdout_read=bool(sent_by_fam.get(fam)), verdict="rejected",
                 notes=f"Signal source: {K.SIGNAL_SOURCE[spec['label']]}.")
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    for c in cands:
        h = hold[c["id"]]
        hm = h["holdout"]
        g = [k for k, v in h["gates"].items() if not v]
        e = dict(BASE, id=f"r47-holdout-{c['id'].lower()}",
                 title=f"R47 holdout candidate {c['id']}: {c['fam']} {c['und']} {c['instr']} filter {c['filt']}",
                 family=FAMILY[c["fam"]],
                 hypothesis="This design-selected relaxed option-buying rule keeps its edge on the locked holdout.",
                 instruments=[c["und"]], features=[c["signal_source"]],
                 model_or_rule=(f"{c['fam']} params {c['params']}; instrument {c['instr']}; exit (stop, target, hold, lock) "
                                f"{c['exit_spec']}; filter {c['filt']}"),
                 variants_tried=1,
                 results=dict(summary=(f"Design {c['n']} trades, Rs {c['npt']:+.0f}/trade (t {c['t']}), blocks "
                                       + ", ".join(f"{k} {v['n']}/{v['net']:+,.0f}" for k, v in c["blocks"].items())
                                       + f"; walk-forward {c['wf']['oos_n']} OOS trades Rs {c['wf']['oos_npt']:+.0f}/trade. "
                                       f"Holdout {hm['trades']} trades, Rs {hm['npt']:+.0f}/trade, net Rs {hm['net']:+,.0f}, win "
                                       f"{hm['win']}%; random twins Rs {h['twins']['mean_npt']:+.0f}/trade, p {h['p']}, BH q "
                                       f"{h['q']} (10 candidates). Failed gates: {', '.join(g) or 'none'}."),
                              costs=COSTS),
                 holdout_read=True, verdict="rejected", notes=f"Signal source: {c['signal_source']}.")
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
