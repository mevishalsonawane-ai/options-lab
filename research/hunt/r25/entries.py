"""Writes research/experiments/entries/r25-*.yaml: one per rule (failures included) plus the census and the anatomy.

    python3 research/hunt/r25/entries.py      # from results/{design,walkforward,holdout}.json (pending where absent)
"""
from __future__ import annotations

import json

import yaml

import r25cfg as K

ENT = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R25_HERO_DEEP.md"
PREREG = "research/hunt/r25/PREREG.md"
DATA = ("Dhan lake on Drive (IraAlgo-data-lake): options_rolling_1m opt_{NIFTY,BANKNIFTY,SENSEX}_WEEK (ATM+-10, nearest "
        "expiry), index_1m NIFTY/BANKNIFTY/SENSEX/INDIAVIX; expiry days found from the data; BANKNIFTY weeklies to "
        "2024-11-13; 13 Oct 2026 not in the lake (ends 2026-10-12)")
PERIOD = dict(design="expiry days 2023-01-01..2026-10-12: NIFTY 197, BANKNIFTY 98, SENSEX 175 (470)",
              holdout="expiry days 2021-08-05..2022-12-29: NIFTY 74, BANKNIFTY 74 (148)")
COSTS = ("app charges at today's rates (Rs 20/order, STT 0.15% sell, exchange NSE/BSE, SEBI, stamp, GST); fills at the "
         "next open + 5 bps + a 1/2/4-tick half-spread by 5-min volume; stress x2 spread; out-of-range prices at 0")
TITLES = {
    "H0": "Hero H0: the app's Hero arm (straddle >= 15% + 0.25% momentum, 13:30-14:45, Rs 1-5, app exits) on the lake",
    "L1": "Hero lottery basket: CE + PE nearest Rs 3 at 13:30, Rs 500 a side, hold to 15:05",
    "L2": "Hero lottery basket: CE + PE nearest Rs 3 at 13:30, Rs 500 a side, half at 3x + step lock",
    "L3": "Hero lottery basket: CE + PE nearest Rs 3 at 14:30, Rs 500 a side, hold to 15:05",
    "L4": "Hero lottery basket: CE + PE nearest Rs 3 at 14:30, Rs 500 a side, half at 3x + step lock",
    "V4": "Hero R21-V4 on the lake: one-sided (own close >= 2.5x its low since 12:00) + momentum, 13:30-15:00, Rs 1-15",
    "T1": "Hero T1: fresh day-high/low break + strike near in expected-move units (Rs 1-10, 11:00-14:30)",
    "T2": "Hero T2: strike near in day-range units + max pain behind the option (Rs 1-10, 11:00-13:30)",
    "T3": "Hero T3: 15-min momentum + beyond the opening range + other-side OI building (Rs 1-10, 13:00-14:45)",
}
HYP = {
    "H0": "The app's Hero rule makes money on 6 years of expiries after costs (baseline).",
    "L1": "A blind basket of cheap CE + PE on every expiry pays after costs because jackpots are frequent (no prediction).",
    "L2": "The lottery basket pays when profits are locked Boss-style (half at 3x, step lock).",
    "L3": "A later (14:30) lottery basket pays after costs (cheaper, less theta left).",
    "L4": "The 14:30 lottery basket with Boss's ladder pays after costs.",
    "V4": "R21's best design variant (one-sided trigger) holds up on 6 years.",
    "T1": "Breakouts with a near strike pick jackpots well enough to pay after costs.",
    "T2": "Near strikes with max pain behind pick jackpots well enough to pay after costs.",
    "T3": "Momentum + opening-range break + other-side writers adding picks jackpots well enough to pay after costs.",
}


def _load(name):
    p = K.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else {}


def _txt(rid, des, wf, ho) -> tuple[str, str, bool]:
    d, w, h = des.get(rid), wf.get(rid), ho.get(rid)
    if not d:
        return "pending: pre-registered, not yet run", "inconclusive", False
    s = (f"Design {d['m']['trades']} trades, hit {d['m']['hit_pct']}%, net Rs {d['m']['net']:+,.0f} "
         f"({d['m']['per_1000_risked']:+.0f} per Rs 1,000 risked; {d['m']['expiries']} expiries).")
    if w:
        s += f" Walk-forward 2024/25/26 net Rs {w['sum']:+,.0f} ({w['years_positive']} of 3 years positive)."
    if h:
        m = h["m"]
        s += (f" Holdout {m['trades']} trades, hit {m['hit_pct']}%, net Rs {m['net']:+,.0f} ({m['expiries']} expiries; "
              f"worst day Rs {m['worst_day']:,.0f}; max DD Rs {m['max_dd']:,.0f}); p {h['sig']['p']:.3f}, "
              f"BH q {ho['bh_family'][rid]['q']:.3f}. {'PASSED' if h['passes'] else 'FAILED'} the gates.")
        return s, ("paper-only" if h["passes"] else "rejected"), True
    return s, "inconclusive", False


def write() -> None:
    des, wf, ho = _load("design.json"), _load("walkforward.json"), _load("holdout.json")
    for rid, title in TITLES.items():
        summ, verdict, read = _txt(rid, des, wf, ho)
        d = des.get(rid, {})
        e = dict(id=f"r25-{rid.lower()}", title=title, family="calendar-events", date="2026-10-14", doc=DOC,
                 hypothesis=HYP[rid], data_version=DATA, period=PERIOD,
                 instruments=["NIFTY"] if rid == "H0" else ["NIFTY", "BANKNIFTY", "SENSEX"],
                 features=["expiry-day Rs 1-10 OTM options", "index minutes", "VIX", "option OI"],
                 model_or_rule=title + (f"; chosen on design: {d.get('params')} exits {d.get('exits')}" if rid.startswith("T") and d else ""),
                 settings=PREREG, prereg=PREREG,
                 variants_tried={"T1": 9, "T2": 27, "T3": 18}.get(rid, 1),   # threshold grid x 3 exits
                 results=dict(summary=summ, costs=COSTS), holdout_read=read, verdict=verdict,
                 notes="One of 9 R25 rules; BH across the 9 plus R21's 9 (18). Exit grid reported separately with its own BH.")
        (ENT / f"r25-{rid.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    c = _load("census.json")
    a = _load("anatomy.json")
    for eid, title, summ, feats in (
        ("r25-census", "Hero R25 step 1: census of expiry-day Rs 1-10 options (how often 5x/10x/20x/33x)",
         (f"{c.get('units', 0):,} units on {c.get('days', 0)} design expiries; any unit 5x on {c.get('days_any', {}).get('x5', ['?'])[0]} "
          f"days, 10x on {c.get('days_any', {}).get('x10', ['?'])[0]}, 33x on {c.get('days_any', {}).get('x33', ['?'])[0]}; mean held "
          f"to 15:05 Rs {c.get('all', {}).get('mean_per1000', '?')} per Rs 1,000") if c else "pending",
         ["option price", "best multiple before 15:05 / close", "VIX regime", "distance OTM", "slot", "weekday"]),
        ("r25-anatomy", "Hero R25 step 2: what precedes jackpots vs duds (lift and expectancy, design only)",
         (f"{a.get('rows', 0):,} decision rows on {a.get('days', 0)} days; base jackpot {a.get('base_jackpot_pct')}%, dud "
          f"{a.get('base_dud_pct')}%; best pair lift {a['pairs'][0]['lift'] if a.get('pairs') else '?'}; every state has negative "
          f"expectancy (best Rs {a['by_expectancy'][0]['exp_half5'] if a.get('by_expectancy') else '?'} per Rs 1,000)") if a else "pending",
         ["index move 5/15/30", "speed", "day high/low break", "opening range", "distance in % / range / ATR / expected move",
          "IV", "OI change own / other side", "PCR change", "straddle change", "volume burst", "VIX", "max pain", "gap", "trend"]),
    ):
        e = dict(id=eid, title=title, family="market-facts", date="2026-10-14", doc=DOC,
                 hypothesis="Describe the jackpots and what precedes them (no trading claim).", data_version=DATA,
                 period=dict(design=PERIOD["design"]), instruments=["NIFTY", "BANKNIFTY", "SENSEX"], features=feats,
                 model_or_rule="descriptive", settings=f"research/hunt/r25/{eid.split('-')[1]}.py", prereg=PREREG,
                 variants_tried=None, results=dict(summary=summ, costs=COSTS), holdout_read=None, verdict="not-a-test")
        (ENT / f"{eid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    write()
