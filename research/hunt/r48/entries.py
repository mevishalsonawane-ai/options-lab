"""Writes research/experiments/entries/r48-*.yaml: the whole search (one entry per family, failures included), the
full-period scan superseded by amendment 3, and one entry per candidate read on the holdout.

    python3 research/hunt/r48/entries.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENT = HERE.parents[1] / "experiments" / "entries"
SIG = ("SIGNAL SOURCE: the index (index_1m closes / index_daily), the default; the ATM strike (straddle for selling) sets the strike "
       "distance; stops and targets on the spread or premium value.")
BASE = dict(date="2026-10-15", doc="research/HUNT_R48_HIGH_WINRATE.md",
            data_version="Drive lake gdrive:IraAlgo-data-lake: options_rolling_1m opt_<UND>_WEEK_2021-2026 (nearest expiry, "
                         "ATM+-10, held contracts), index_1m, index_daily (to 13 Oct 2026)",
            period={"design": "entries 1 Nov 2024 - 17 Oct 2025 (amendment 3, Boss)",
                    "holdout": "entries 3 Nov 2025 - 13 Oct 2026, read once (20-31 Oct 2025 embargo)"},
            prereg="research/hunt/r48/PREREG.md", settings="research/hunt/r48/r48cfg.py")
COSTS = ("research/obuy Costs('dated') per order per leg (STT on sold premium at the dated rate, exchange, SEBI, stamp, "
         "GST, Rs 20); exercise STT on ITM longs left to expire; 5 bps + 1-4 ticks a leg by 5-minute volume, +1 far OTM, "
         "x2 on stops; next-minute fills; 1 lot at the dated lot size")


def name(c: dict) -> str:
    if c["kind"] == "BUY":
        t, s, tm = c["exit"]
        return (f"{c['und']} buy {c['itm']}-ITM on {c['sig']} ({c['dir']}), DTE {c['dte']}, {c['time']}, target +{t:.0%}, "
                f"stop {'none' if s is None else f'-{s:.0%}'}, time {tm}; VIX {c['vix']}, events {c['ev']}")
    p, s, isl, hold = c["exit"]
    st = c["kind"] if c["kind"] != "DIR" else f"BPS/BCS by {c['sig']} ({c['dir']})"
    return (f"{c['und']} {st}, short {c['m']}x straddle, width {c['w']} steps, DTE {c['dte']}, {c['time']}, "
            f"target {'none' if p is None else f'{p:.0%}'}, stop {'none' if s is None else f'{s}x credit'}, "
            f"index stop {'on' if isl else 'off'}, {'hold to expiry' if hold == 'exp' else 'same day'}; "
            f"VIX {c['vix']}, events {c['ev']}")


def summ(x: dict) -> str:
    return (f"{x['n']} trades, win {x['win']:.1%}, net Rs {x['net']:+,.0f}, avg win Rs {x.get('avg_win', 0):,.0f} / avg loss "
            f"Rs {x.get('avg_loss', 0):,.0f}, worst day Rs {x.get('worst_day', 0):,.0f}, max DD Rs {x.get('max_dd', 0):,.0f}")


def main():
    d = json.loads((HERE / "results" / "design.json").read_text())
    fp = json.loads((HERE / "results" / "design_fullperiod.json").read_text())
    hp = HERE / "results" / "holdout.json"
    h = json.loads(hp.read_text()) if hp.exists() else None
    out = {}
    out["r48-search"] = dict(
        BASE, id="r48-search", family="option-structure",
        title="High-win-rate index option BUYING search (amendment 4: buying only), Nov 2024 - Oct 2025 design",
        hypothesis="Some NSE index option rule wins >= 65% of trades AND makes money after real costs on unseen data.",
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        features=["index gap / EMA20 / TWAP / opening range", "ATM straddle (strike distance)", "INDIAVIX prior close",
                  "RBI / budget / election days"],
        model_or_rule="grid of PREREG amendment 4 (ATM..6-ITM buys on index gap / EMA / TWAP / opening-range signals, with or "
                      "fade; targets +3..+30%, stops none..-50%, time 15 min..15:15; DTE 0-4 or any; VIX / event "
                      "filters); candidate rule fixed in advance",
        variants_tried=int(d["n_configs"]),
        results=dict(summary=(f"{d['n_configs']:,} configs on design; {d['n_eligible']} passed every design gate; "
                              f"{len(d['candidates'])} candidates read on the holdout"
                              + (f", {sum(r['passed'] for r in h['candidates'])} passed" if h else "")
                              + f". Walk-forward of the selection: {wf(d)}"), costs=COSTS),
        holdout_read=h is not None, verdict="rejected" if not h or not any(r["passed"] for r in h["candidates"]) else "paper-only",
        notes=SIG)
    out["r48-fullperiod"] = dict(
        BASE, id="r48-fullperiod", family="option-structure",
        title="Selling (spreads / condors / flies) + narrow buying grid on Aug 2021 - Oct 2025 design (superseded by "
              "amendments 3-4 before any holdout read)",
        hypothesis="Defined-risk selling or ITM buying wins >= 65% and makes money after costs.",
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"], features=["as r48-search"],
        model_or_rule="as r48-search (100+ trades, positive in 75% of years)", variants_tried=int(fp["n_configs"]),
        period={"design": "entries Aug 2021 - 17 Oct 2025", "holdout": "not read for this scan"},
        results=dict(summary=(f"{fp['n_configs']:,} configs; {fp['n_eligible']} passed every design gate; "
                              f"{len(fp['candidates'])} candidates (not read). Walk-forward of the selection: "
                              + "; ".join(f"{w['test_year']}: {w['trades']} trades, win {w['win']:.1%}, net Rs {w['net']:+,.0f}"
                                          for w in fp["walk_forward"])), costs=COSTS),
        holdout_read=False, verdict="superseded", notes=SIG)
    if h:
        for k, r in enumerate(h["candidates"], 1):
            c = r["cfg"]
            eid = f"r48-c{k:02d}"
            out[eid] = dict(
                BASE, id=eid, family="option-structure", title=f"R48 candidate {k}: {name(c)}",
                hypothesis="This rule, picked by the pre-registered design rule, keeps a >= 60% win rate and makes money "
                           "on the holdout.",
                instruments=[c["und"]], features=["index signals as named", "ATM strike (strike rule)"],
                model_or_rule=name(c), variants_tried=1,
                results=dict(summary=(f"Design {summ(r['design_full'])}. Holdout {summ(r['holdout'])}; p_twin "
                                      f"{r['p_twin']:.3f}, BH q {r['q']:.3f}, bootstrap p(mean<=0) {r['p_zero']:.3f}. "
                                      f"Gates failed: {', '.join(k2 for k2, v in r['gates'].items() if not v) or 'none'}."),
                             costs=COSTS),
                holdout_read=True, verdict="paper-only" if r["passed"] else "rejected", notes=SIG)
    for eid, e in out.items():
        (ENT / f"{eid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(out), "entries")


def wf(d) -> str:
    w = d["walk_forward"][0]
    return (f"picked on {w['pick']}, tested {w['test']}: {w['trades']} trades, win "
            f"{(w['win'] or 0):.1%}, net Rs {w['net']:+,.0f}")


if __name__ == "__main__":
    main()
