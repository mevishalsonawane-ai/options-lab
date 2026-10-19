"""Write research/experiments/entries/r86c-*.yaml from results/c_design.json.

    python -m research.hunt.r86.r86centries
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[2] / "research/experiments/entries"
DOC = "research/HUNT_R86C_CATCH_THEM.md"
PREREG = "research/hunt/r86/PREREG_C.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake index_1m (NIFTY 2017-, BANKNIFTY Aug 2021-, INDIAVIX), equity_1m (59 current "
        "index stocks 2019 - 12 Oct 2026), options_rolling_1m WEEK (Aug 2021 -), derived/r28_gex (dte only); files of 13-15 Oct "
        "2026; NSE participant OI as labels only")
FUT = "index future: Rs 40, dated STT, exchange, SEBI, stamp, GST, 0.02% slippage a side (0.04% stops), carry overnight; 1 lot"
OPT = "options: research/obuy Costs('dated') charges both legs + 5 bps + Rs 0.05 a side; 1 lot in force"
IDEAS = {
    "c1": ("Trade only the slow side: buy the first VWAP dip after 11:30 on a steady, busy UP morning", "patterns-indicators", FUT,
           "underlying (index + constituents' turnover)"),
    "c2": ("Fade the month-end 15:00-15:24 push overnight to 09:30", "overnight", FUT, "underlying (index minutes)"),
    "c3": ("Footprint-based busy-afternoon forecast -> buy the 11:30 ATM straddle to 15:15 on the top-20% days", "option-structure",
           OPT, "underlying (index, VIX, constituents' turnover and bursts); the option is only the instrument"),
    "c4": ("Unusual OTM option buying (8x volume, OI up, index confirming) -> follow 30 minutes", "flow-oi", FUT,
           "OPTION-CHAIN volume/OI trigger confirmed by the underlying: labelled non-default"),
    "c5": ("Learned 'FII buying day in progress' detector at 12:00 -> long to 15:15", "ml", FUT,
           "underlying (index + constituents); participant OI only as training labels"),
    "c6": ("Learned 'seller at work' detector at 09:45 (12 seller patterns) -> short to 11:15", "ml", FUT,
           "underlying (index + constituents + VIX); participant OI only as training labels"),
}


def main():
    r = json.loads((HERE / "results/c_design.json").read_text())
    t = r["tests"]
    for key, (title, fam, costs, src) in IDEAS.items():
        ids = [f"{key.upper()}-NIFTY", f"{key.upper()}-BANKNIFTY"]
        parts = [f"{i}: {t[i]['n']} trades, net Rs {t[i]['net']:,.0f}, without top 5 Rs {t[i]['net_wo_top5']:,.0f}, random twins "
                 f"mean Rs {t[i]['twin_mean']:,.0f}, twin p {t[i]['p']:.3f}, BH q {t[i]['bh_q']:.3f}" for i in ids]
        passed = any(t[i]["passes"] for i in ids)
        e = {"id": f"r86c-{key}", "title": f"R86c {key.upper()}: {title}", "family": fam, "date": "2026-10-19", "doc": DOC,
             "hypothesis": f"Pre-registered ({PREREG}): {title} beats random twins in design (net > 0 and > twins, twin p < 0.10, "
                           "BH q < 0.10 over R86c's 12 tests, not carried by its top 5).",
             "data_version": DATA,
             "period": {"design": "to 2024 (folds 2022-2024; C2 every year 2018-2024)",
                        "holdout": "1 Jan 2025 - Oct 2026 NOT read: no R86c test passed design"},
             "instruments": ["NIFTY", "BANKNIFTY"], "features": [f"signal source: {src}"],
             "model_or_rule": title, "settings": "research/hunt/r86/r86c.py; PREREG_C.md", "prereg": PREREG,
             "variants_tried": 2,
             "results": {"summary": "Design. " + "; ".join(parts) + (". PASS." if passed else ". Verdict: FAIL."), "costs": costs},
             "holdout_read": False, "verdict": "adopted" if passed else "rejected",
             "notes": "Out-of-the-box ways to catch the big players (Boss, 19 Oct). Research only."}
        (ENTRIES / f"r86c-{key}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    cat = r["extra"]
    lines = []
    for und in ("NIFTY", "BANKNIFTY"):
        c = cat[f"seller_catalogue-{und}"]
        for p in ("P1", "P2", "P9"):
            x = c[p]
            lines.append(f"{und} {p}: {x['days']} days, FII-sell share {x['fii_sell_share']} vs base {x['base_rate']} "
                         f"(lift {x['lift']}, BH q {x['bh_q']:.2g})")
    f = {"id": "r86c-seller-catalogue", "title": "R86c 6a: twelve early (09:15-09:44) seller patterns and how often they mark an FII selling day",
         "family": "market-facts", "date": "2026-10-19", "doc": DOC,
         "hypothesis": "Describe which early patterns mark heavy FII selling days (lift vs base rate, Fisher exact, BH within the catalogue).",
         "data_version": DATA, "period": {"design": "2019 - 2024 (descriptive)"}, "instruments": ["NIFTY", "BANKNIFTY"],
         "features": ["12 pattern flags at 09:45 from index, constituents and VIX; FII-sell labels after the close"],
         "model_or_rule": "lift table", "settings": PREREG + " amendment C-A1", "prereg": PREREG, "variants_tried": None,
         "results": {"summary": "; ".join(lines) + ". Full table in the doc.", "costs": "none (description)"},
         "holdout_read": None, "verdict": "not-a-test"}
    (ENTRIES / "r86c-seller-catalogue.yaml").write_text(yaml.safe_dump(f, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    main()
