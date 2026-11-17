"""R192 experiment entries: one YAML per idea, from results/r192.json.

    python3 research/hunt/r192_long_history/r192entries.py && python3 research/experiments/validate.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r192cfg as K  # noqa: E402
from r192report import rs  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r192_long_history/results/report.md"
PREREG = "research/hunt/r192_long_history/PREREG.md"
DATA = ("R190's copy of the Drive lake gdrive:IraAlgo-data-lake (17 Nov 2026): NIFTY 1-minute index 3 Apr 2017 - 13 Nov "
        "2026, index_daily NIFTY 2006 on and INDIAVIX Nov 2007 on; R51's clean NIFTY nearest-week option store (dhan-data "
        "branch Aug 2020 - Jul 2021, the lake from Aug 2021). Synthetic option prices (Black-76, India VIX, trading-time "
        "clock w 0.15, a 0.89, fitted on eras A + B real entries) for S0 / S")
SLUG = {"K1": "turn-of-month", "K2": "pre-holiday", "K3": "overnight-call", "K4": "night-proxy-filter", "K5": "gap-fade",
        "K6": "gap-follow", "K7": "vix-spike", "K8": "52-week", "K9": "trend-low-vol", "K10": "big-move-reversal",
        "K11": "big-move-continuation"}
FAMILY = {"K1": "calendar-events", "K2": "calendar-events", "K3": "overnight", "K4": "overnight", "K5": "outside-strategies",
          "K6": "outside-strategies", "K7": "daily-filters", "K8": "daily-filters", "K9": "daily-filters",
          "K10": "outside-strategies", "K11": "outside-strategies"}
SRC = {"K7": "signal source: India VIX daily closes and NIFTY daily closes",
       "K4": "signal source: NIFTY index minutes to 15:19 (close location, day move)"}


def line(r: dict) -> str:
    parts = []
    for e in K.ERAS:
        if e == "S0" and "S0" not in r["need"]:
            continue
        s = r["eras"][e]
        lab = f"era {e}{' (synthetic)' if e in K.SYN_ERAS else ''}"
        parts.append(f"{lab} {s['trades']} tr" + (f", {s['win']:.1f}% win, {rs(s['net'])}, DD {rs(s['max_dd'])}, worst month "
                                                   f"{rs(s['worst_month'])}" if s["trades"] else ""))
    return "; ".join(parts)


def main():
    d = json.loads((K.RESULTS / "r192.json").read_text())
    for i, r in d["ideas"].items():
        e = dict(
            id=f"r192-{i.lower()}-{SLUG[i]}", title=f"R192 long history {i}: {r['name']}"[:160], family=FAMILY[i],
            date="2026-11-17", doc=DOC,
            hypothesis=f"{r['name']} (published / replicated idea, PREREG section 4). Buying the NIFTY 1-ITM option on it "
                       "makes money in every era (synthetic S0 / S as an underlying check, real A, B, C), robust to the "
                       "top 5 trades, beats random days and sides on the pooled real trades, after BH over the whole log.",
            data_version=DATA,
            period=dict(design="no parameter fitted (K4: its filter chosen on 2017-04-03..2018-12-31 synthetic only); "
                               "S0 2008-01-01..2017-03-31 and S 2017-04-03..2020-07-31 synthetic",
                        holdout="none virgin: real eras A 2020-08-03..2021-07-30, B 2021-12-01..2024-10-31, C "
                                "2024-11-01..2026-11-13 run once, all seen at the data level by earlier hunts"),
            instruments=["NIFTY"],
            features=[SRC.get(i, "signal source: NIFTY index (minutes / daily closes)"),
                      "buy the 1-ITM nearest-expiry option; dated Zerodha charges; R17 slippage; real 1-minute option "
                      "prices in A / B / C"],
            model_or_rule=r["name"], settings="research/hunt/r192_long_history/r192cfg.py; PREREG.md section 4",
            prereg=PREREG, variants_tried=6 if i == "K4" else 1,
            results=dict(summary=line(r) + f". Pooled real {r['pooled_real']['trades']} tr, {rs(r['pooled_net'])}; twin p "
                         f"{r['p_emp']:.4f} (normal {r['p_norm']:.2g}), BH q {r['q']:.3g} (m {d['m_total']:,}). "
                         + ("RELIABLE." if r["reliable"] else "Not reliable: " + "; ".join(r["fails"]) + "."),
                         costs="dated Zerodha charges; R17 liq fills (+-5 bps, 1/2/4 ticks by volume); 2x slippage "
                               "stress in report.md; synthetic: +-5 bps and 1 tick, lot 75"),
            holdout_read=None, verdict="paper-only" if r["reliable"] else "rejected",
            notes="Signal on the underlying, option bought. Research only; nothing in the app changes.")
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=False))
    print("entries written", len(d["ideas"]))


if __name__ == "__main__":
    main()
