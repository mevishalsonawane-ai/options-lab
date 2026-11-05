"""Writes research/experiments/entries/r146-*.yaml from results/design.json and holdout.json (every R146 idea,
failures included; numbers copied, not recomputed).

    python -m research.hunt.r146_gap_btst.make_entries
"""
from __future__ import annotations

import json

import yaml

from . import cfg as K

ENT = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R146_GAP_BTST.md"
PRE = "research/hunt/r146_gap_btst/PREREG.md"
COSTS_P = "none (direction of the index gap; no trade)"
COSTS_T = ("app fills (+-5 bps, stop -10 bps), app charges (Costs('app'): Rs 20 an order, STT 0.15%, exchange, SEBI, "
           "stamp, GST), 0.16% half-spread on both legs; stress 1.5x spread")
DATA_M = ("research-data release (gh api): nifty_prev/year, banknifty_prev_year_wide/year_wide (index + nearest-expiry "
          "option minutes), finnifty_index, sensex_index, extra_series (INDIAVIX); Yahoo daily N225/HSI/KS11/GSPC; "
          "r98 events.csv")
DATA_D = "Yahoo chart API daily bars 2007 - 4 Nov 2026 (^NSEI, ^NSEBANK, NIFTY_FIN_SERVICE.NS, ^BSESN, ^INDIAVIX, ^N225, ^HSI, ^KS11, ^GSPC, USDINR=X)"
IDEA_TXT = {"R_mom": "sign of the day's move", "R_loc": "close location >= 0.6 up / <= 0.4 down",
            "R_l30": "sign of the last 30 minutes", "R_r17": "Night's strong close (location + move + cross-index breadth)",
            "R_r3p": "Night (R3) proxy: strong close + OI build-up z agrees", "R_asia": "sign of the Hang Seng's day",
            "LR": "L2 logistic on all features", "GB": "gradient trees (depth 2) on all features"}


def f(x, nd=3):
    return "n/a" if x is None else f"{x:.{nd}f}"


def pred_entry(und_key: str, rows_d: list, rows_h: list) -> dict:
    kind, und = und_key.split(":")
    ideas = [r["idea"] for r in rows_d]
    parts = []
    for rd, rh in zip(rows_d, rows_h):
        parts.append(f"{rd['idea']}: design {rd.get('n')} nights hit {f(rd.get('hit'))} vs base rate {f(rd.get('hit_p0'))}"
                     f"{' AUC ' + f(rd.get('auc')) if rd.get('auc') is not None else ''} p {f(rd.get('p_perm'))} "
                     f"q {f(rd.get('bh_q'))}; holdout {rh.get('n')} hit {f(rh.get('hit'))} vs {f(rh.get('hit_p0'))}"
                     f"{' AUC ' + f(rh.get('auc')) if rh.get('auc') is not None else ''} p {f(rh.get('p_perm'))}")
    m = kind == "M"
    return {
        "id": f"r146-{'m' if m else 'd'}-{und.lower()}",
        "title": f"R146 next-open gap direction from {'15:19' if m else 'the close (daily)'}: {und}",
        "family": "overnight", "date": "2026-11-05", "doc": DOC,
        "hypothesis": "Information known by 15:20 IST predicts the sign of the next 09:15 open vs the decision price "
                      "better than the gap-up base rate.",
        "data_version": DATA_M if m else DATA_D,
        "period": {"design": "M walk-forward test months Aug 2024 - Feb 2025 (monthly retrain)" if m else
                   "D walk-forward test years 2012 - Feb 2025 (yearly retrain)",
                   "holdout": "1 Mar 2025 - 20 Feb 2026 (M) / 1 Mar 2025 - 3 Nov 2026 (D), read once 5 Nov 2026"},
        "instruments": [und],
        "features": ["signal source: the index (day move, close location, last 30/60 min, gap, cross-index breadth), "
                     "India VIX, option-chain OI build-up / PCR / straddle as context, expiry calendar, weekday, "
                     "scheduled events, Asian closes, previous S&P session" if m else
                     "signal source: the index daily bar (return, close location, gap, 5-day return, range), India VIX, "
                     "Asian closes, previous S&P session, USD/INR (lagged), weekday"],
        "model_or_rule": "; ".join(f"{i}: {IDEA_TXT[i]}" for i in ideas),
        "settings": "research/hunt/r146_gap_btst/cfg.py (frozen in PREREG)",
        "prereg": PRE, "variants_tried": len(ideas),
        "results": {"summary": " | ".join(parts) + ". Gate: hit above the base rate on the same nights, p <= 0.05, "
                               "BH q (36) <= 0.10, >= 100 nights. None passed design.", "costs": COSTS_P},
        "holdout_read": True, "verdict": "rejected",
        "notes": "Design hit rates beat random sides (p < 0.001 on D) but not the gap-up base rate. In the holdout the "
                 "up-drift faded and momentum / location beat the base rate on some indices (information only)."
                 if not m else "Information only in the holdout since nothing passed design.",
    }


def trade_entry(sid: str, td: list, th: list) -> dict:
    s = []
    for p, rows in (("design", td), ("holdout", th)):
        for r in rows:
            x = r["X16"]
            if not x.get("trades"):
                s.append(f"{p} {r['und']}: no trades")
                continue
            s.append(f"{p} {r['und']}: {x['trades']} trades, Rs {x['net_per_trade']:.0f} a trade, win {x['win_rate']*100:.0f}%, "
                     f"total Rs {x['total']:,.0f}, Rs {x['per_session']:.0f}/session, worst month Rs {x['worst_month']:,.0f}, "
                     f"{x['months_positive']}/{x['months']} months up, max DD Rs {x['max_dd']:,.0f}, twin p "
                     f"{f(r['twin_p'])}" + (f", BH q {f(r.get('bh_q'), 2)}" if p == "design" and r.get("bh_q") is not None else ""))
    base = sid == "T0"
    name = td[0]["name"]
    return {
        "id": f"r146-btst-{sid.lower()}",
        "title": f"R146 BTST 1-ITM option 15:20 -> 09:16 on '{name}'" + (" (baseline)" if base else ""),
        "family": "overnight", "date": "2026-11-05", "doc": DOC,
        "hypothesis": "Buying the predicted side at 15:20 and selling at 09:16 pays after real costs and beats the Night "
                      "(R3) proxy per session and a random-side twin." if not base else
                      "Baseline: the Night (R3) rule rebuilt on the release data (breadth proxy).",
        "data_version": DATA_M,
        "period": {"design": "walk-forward test months Aug 2024 - Feb 2025",
                   "holdout": "1 Mar 2025 - 20 Feb 2026, read once 5 Nov 2026"},
        "instruments": ["NIFTY", "BANKNIFTY"],
        "features": ["signal source: the index at 15:19 (see r146-m-*), option bought; exits X16 (gate), X15 / X20 / "
                     "X30 / S30 stop as comparisons"],
        "model_or_rule": f"{sid} {name}; Night's contract (1-ITM nearest expiry, no expiry day, not held into expiry), "
                         "1 lot, buy 15:20 open, sell 09:16 open",
        "settings": "research/hunt/r146_gap_btst/cfg.py", "prereg": PRE,
        "variants_tried": 2,
        "results": {"summary": "; ".join(s) + (". Baseline, not gated." if base else
                                                ". Gate (design): net > 0 also at 1.5x spread, Rs/session >= T0, twin p <= 0.05, "
                                                "BH q (10) <= 0.10, >= 30 trades. Failed design."),
                    "costs": COSTS_T},
        "holdout_read": True,
        "verdict": "not-a-test" if base else "rejected",
    } | ({"variants_tried": None} if base else {})


def main() -> None:
    d = json.loads((K.RESULTS / "design.json").read_text())
    h = json.loads((K.RESULTS / "holdout.json").read_text())
    out = []
    keys = sorted({r["und"] for r in d["prediction"]})
    for k in keys:
        out.append(pred_entry(k, [r for r in d["prediction"] if r["und"] == k], [r for r in h["prediction"] if r["und"] == k]))
    for sid in ("T0", "T1", "T2", "T3", "T4", "T5"):
        out.append(trade_entry(sid, [r for r in d["trading"] if r["strategy"] == sid],
                               [r for r in h["trading"] if r["strategy"] == sid]))
    for e in out:
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        print(e["id"])


if __name__ == "__main__":
    main()
