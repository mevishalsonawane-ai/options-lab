"""Writes research/experiments/entries/r28-*.yaml: the GEX feature, the descriptive step and each of the 10 filters
(failures included), from results/{describe,design,holdout}.json ("pending" where a step has not run).

    python3 research/hunt/r28/entries.py
"""
from __future__ import annotations

import json

import yaml

import r28cfg as K
import study as S

ENT = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R28_GEX.md"
PREREG = "research/hunt/r28/PREREG.md"
DATA = ("Dhan lake on Drive (IraAlgo-data-lake): options_rolling_1m opt_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX}_"
        "{WEEK,MONTH} (ATM+-10 each minute, OI in units), index_1m incl. INDIAVIX, Aug 2021 - 12 Oct 2026; expiries "
        "found from the data; per-minute GEX tables on Drive derived/r28_gex/")
COSTS = {"orb": "R17 Fills 'liq' + app charges (R22 trade()); x2 spread stress",
         "fade": "R17 Fills 'liq' + app charges (R22 trade()); x2 spread stress",
         "liq": "R19b engine charges and fills (as the app); no spread stress in that code",
         "h0": "R25: app charges, next-open + 5 bps + 1/2/4-tick half-spread; x2 spread stress",
         "l3": "R25: app charges, next-open + 5 bps + 1/2/4-tick half-spread; x2 spread stress",
         "night": "R27/X2: app fills and charges + 0.16% real half-spread; 1.5x spread stress"}
FAMILY = {"orb": "orb", "fade": "orb", "liq": "liquidity", "h0": "calendar-events", "l3": "calendar-events",
          "night": "overnight"}
INSTR = {"orb": ["BANKNIFTY", "NIFTY"], "fade": ["BANKNIFTY", "NIFTY"], "liq": ["BANKNIFTY"], "h0": ["NIFTY"],
         "l3": ["NIFTY", "BANKNIFTY", "SENSEX"], "night": ["NIFTY", "BANKNIFTY"]}
PERIOD_A = dict(design="2023-01-16..2026-10-12 (walk-forward years 2023, 2024, 2025, 2026)",
                holdout="lake start (Aug 2021) .. 2022-12-31; embargo 2023-01-01..15")
PERIOD_H = dict(design="lake start .. 2023-12-17 and 2025-04-15 .. 2026-10-12 (folds 2021-22, 2023, 2025-26)",
                holdout="2024-01-01 .. 2025-03-31; embargoes 2023-12-18..31 and 2025-04-01..14")


def _load(name):
    p = K.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else {}


def _m(e):
    b, k = e["base"], e["kept"]
    return (f"{b['trades']} trades -> {k['trades']} kept, Rs/day {b['per_day']:+.1f} -> {k['per_day']:+.1f} "
            f"(change {e['delta_day']:+.1f}; {b['sessions']} sessions)")


def write():
    des, ho, ds = _load("design.json"), _load("holdout.json"), _load("describe.json")
    out = []
    for f in S.FILTERS:
        d = des.get("filters", {}).get(f.id)
        h = ho.get("filters", {}).get(f.id)
        if not d:
            summ, verdict, read = "pending: pre-registered, not yet run", "inconclusive", False
        else:
            summ = (f"Design: {_m(d['design'])}, twin p {d['design'].get('twin_p')}, q R28 {d.get('q_r28')}, q joined "
                    f"{d.get('q_joined')}, years better {d['gates']['years_better']}; design pass {d['design_pass']}.")
            verdict, read = "inconclusive", False
            if h:
                summ += (f" Holdout: {_m(h['holdout'])}, twin p {h['holdout'].get('twin_p')}. "
                         f"{'PASSED' if h['passed'] else 'FAILED'} the gates.")
                verdict = "paper-only" if h["paper_eligible"] else "rejected"
                read = True
        e = dict(id=f"r28-{f.id.lower()}", title=f"R28 {f.id}: {f.text}", family=FAMILY[f.arm], date="2026-10-14",
                 doc=DOC, hypothesis=f"Filtering the arm by intraday dealer gamma (GEX) raises its P&L per session: "
                 f"{f.text}.", data_version=DATA, period=PERIOD_A if f.family == "A" else PERIOD_H,
                 instruments=INSTR[f.arm], features=["intraday GEX (S1/S2/norm/flip) from ATM+-10 option prices and OI"],
                 model_or_rule=f.text + " (skip rule on the arm's unfiltered lake rebuild; missing GEX = keep)",
                 settings=PREREG, prereg=PREREG, variants_tried=1,
                 results=dict(summary=summ, costs=COSTS[f.arm]), holdout_read=read, verdict=verdict,
                 notes="One of 10 R28 filters; BH across the 10 and in the joined family (R9's 68 gamma tests + the "
                       "arm families' recorded holdout p-values). Random-filter twins skip the same number of "
                       "index-days at random.")
        out.append(e)
    n_desc = ds.get("m")
    out.append(dict(id="r28-describe", title="R28 describe: does the intraday GEX regime separate days (range, trend, "
                    "ORB runs, expiry pins, flip crosses, hero blasts, next gap)?", family="flow-oi", date="2026-10-14",
                    doc=DOC, hypothesis="Negative GEX days are bigger / trendier, positive GEX days pin and revert; "
                    "flip crosses accelerate; hero blasts come under negative GEX; day-end GEX tells the next gap.",
                    data_version=DATA, period=dict(design="Step-2 days: 2023-01-16..2023-12-17 and 2025-04-15..2026-10-12"),
                    instruments=list(K.UNDS), features=["intraday GEX S1/S2/S3, norm, flip, walls", "index minutes", "VIX"],
                    model_or_rule="Descriptive comparisons (Mann-Whitney / proportion / Wilcoxon) with VIX and DTE strata",
                    settings=PREREG, prereg=PREREG, variants_tried=None,
                    results=dict(summary=(f"{n_desc} tests, BH q<0.10: {len(ds.get('passed', []))}: "
                                          + "; ".join(ds.get("passed", [])[:12])) if ds else "pending",
                                 costs="none (descriptive)"),
                    holdout_read=None, verdict="not-a-test",
                    notes="Descriptive only: no rule was chosen from it (the 10 filters were fixed in PREREG Part A)."))
    out.append(dict(id="r28-gex-feature", title="R28 GEX feature: intraday dealer gamma exposure in the Data Brain (L4)",
                    family="flow-oi", date="2026-10-14", doc=DOC,
                    hypothesis="A point-in-time intraday GEX (S1/S2/S3, norm, flip, walls, near vs all) can be built "
                    "from the lake's ATM+-10 option minutes.", data_version=DATA,
                    period=dict(design="every lake day (a measurement, not a test)"), instruments=list(K.UNDS),
                    features=["option last trade <= 30 min", "OI (units)", "Black-Scholes IV r 6.5% q 0", "index close"],
                    model_or_rule="research/brain/gex.py + research/brain/features/gex_f.py (12 L4 features)",
                    settings=PREREG, prereg=PREREG, variants_tried=None,
                    results=dict(summary=_cov(ds), costs="none (feature)"), holdout_read=None, verdict="not-a-test",
                    notes="Coverage gap: only ATM+-10 is stored; the share of stored gamma and OI at the edge strikes "
                          "is in results/describe.md."))
    for e in out:
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(out), "entries")


def _cov(ds) -> str:
    c = ds.get("coverage")
    if not c:
        return "pending"
    return "; ".join(f"{u}: gamma at ATM+-9/10 median {v['gamma_edge_median']:.3f}, OI there {v['oi_edge_median']:.3f}, "
                     f"S1<0 at 10:00 on {v['s1_negative_share_10'] * 100:.0f}% of days, flip found "
                     f"{v['flip_found_share'] * 100:.0f}%" for u, v in c.items() if v.get("gamma_edge_median") is not None)


if __name__ == "__main__":
    write()
