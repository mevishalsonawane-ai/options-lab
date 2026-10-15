"""Writes research/experiments/entries/r50-*.yaml: one per rule family (its originals and its re-tune grid), failures
included.

    python3 research/hunt/r50/entries.py
"""
from __future__ import annotations

import json

import yaml

import r50cfg as K
import r50rules as RR
from r50report import inr, rows_for_family

ENT = K.ROOT / "research" / "experiments" / "entries"
FAMILY = {"app Hero arm": ("hero-arm", "calendar-events"), "cheap OTM lottery": ("lottery", "calendar-events"),
          "OI-wall short covering": ("oi-wall", "flow-oi"), "Boss's 1:45-2:30 window": ("boss-window", "calendar-events"),
          "gamma / GEX": ("gex", "flow-oi"), "sweep signals": ("sweep", "patterns-indicators"),
          "volume / supertrend": ("supertrend", "patterns-indicators"), "afternoon window": ("afternoon", "calendar-events")}


def _line(name, d, f) -> str:
    m, fm = d["m"], f["m"]
    g = f["gates"]
    v = "PASS" if g["pass"] else ("fail, too few trades to judge" if g["too_few"] else "fail")
    top = f"{fm['top5']:.0f}%" if fm["top5"] is not None else "n/a (net <= 0)"
    return (f"{name}: design {m['trades']} trades net Rs {inr(m['net'])} (walk-forward Rs {inr(d['wf_net'])}); final "
            f"{fm['trades']} trades, win {fm['win']}%, peaks 5x/10x {fm['hero5']}/{fm['hero10']}, net Rs {inr(fm['net'])}, "
            f"without top 5 Rs {inr(fm['ex5'])} (top-5 share {top}), p {f['p']:.3f}, q {f['q']:.3f}: {v}")


def main():
    des = json.loads((K.RESULTS / "design.json").read_text())
    fin = json.loads((K.RESULTS / "final.json").read_text())
    exp = json.loads((K.RESULTS / "expiry_days.json").read_text())
    nd = sum(1 for u in K.UNDS for x in exp[u] if x <= str(K.DESIGN_END))
    nf = sum(len(exp[u]) for u in K.UNDS) - nd
    for fam, (slug, vfam) in FAMILY.items():
        rows = rows_for_family(fam, des, fin)
        ncells = sum(len(RR.RETUNES[t]) for t, f in RR.RETUNE_FAMILY.items() if f == fam)
        norig = sum(r.family == fam for r in RR.ORIGINALS)
        npass = sum(f["gates"]["pass"] for _, _, _, f, _ in rows)
        sources = sorted({rs.source for _, rs, _, _, _ in rows})
        summ = (f"{norig} original rules and a re-tune grid of {ncells} cells (one cell chosen on design by walk-forward), "
                f"on {nd} design and {nf} final-test index-expiry-days (20 Nov 2024 - 13 Oct 2026; NIFTY and SENSEX weekly, "
                f"BANKNIFTY / FINNIFTY / MIDCPNIFTY monthly). {npass} pass. "
                + "; ".join(_line(n, d, f) for n, _, d, f, _ in rows) + ".")
        e = dict(
            id=f"r50-{slug}", title=f"Cancelled hero rules on the Nov 2024+ market: {fam}", family=vfam,
            date="2026-10-15", doc="research/HUNT_R50_HERO_RECENT.md",
            hypothesis=(f"A cancelled {fam} hero rule (R21-R43), or a small pre-registered re-tune of it, makes money on the "
                        "market since NSE's weekly-expiry change (20 Nov 2024) after real costs, on one held contract, "
                        "and beats random entry times."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake: opt_<UND>_WEEK_2024-2026 (rolling ATM+-10 1-minute, rebuilt "
                          "per held strike), index_1m 2024-2026, equity_1m 2024-2026 (R35 heavyweights); lake to 13 Oct "
                          "2026"),
            period={"design": "expiry days 20 Nov 2024 - 31 Mar 2026 (walk-forward blocks B0-B4)",
                    "holdout": "final test: expiry days 1 Apr - 13 Oct 2026, read once (not pristine: R43-R45 saw 2025-26)"},
            instruments=list(K.UNDS),
            features=[f"signal source: {s}" for s in sources],
            model_or_rule="; ".join(f"{n} = {rs.orig or 'R50 re-tune'}" for n, rs, _, _, _ in rows),
            settings="research/hunt/r50/r50rules.py (rules), research/hunt/r50/r50cfg.py (dates, lots)",
            prereg="research/hunt/r50/PREREG.md", variants_tried=norig + ncells,
            results=dict(summary=summ, costs=("charges in force on the trade date (obuy Costs('dated'), BSE rates for "
                                              "SENSEX); next-minute fills + 5 bps + 1/2/4-tick half-spread by 5-minute "
                                              "volume; entry limit ref x 1.2 + 0.10 for 3 minutes; gap fills at the "
                                              "worse open; 2x spread stress reported")),
            holdout_read=True, verdict="paper-only" if npass else "rejected",
            notes=(f"BH over all {fin['bh_family']} R50 final-test tests (20 originals + 8 chosen re-tunes). Random-entry "
                   "twins: 500 per rule, same day, side, strike rule, budget and exit at a random minute of the rule's "
                   "window. Signal-source labels: 'OPTION'S OWN CHART' rules are not the default kind."))
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(FAMILY))


if __name__ == "__main__":
    main()
