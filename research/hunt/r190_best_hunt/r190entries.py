"""R190 experiment entries: one YAML per candidate group, from decision.json.

    python3 research/hunt/r190_best_hunt/r190entries.py && python3 research/experiments/validate.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r190cfg as K  # noqa: E402
from r190report import rs  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r190_best_hunt/results/report.md"
PREREG = "research/hunt/r190_best_hunt/PREREG.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake (copied 17 Nov 2026: index_1m, options_rolling_1m WEEK + BANKNIFTY MONTH, "
        "equity_1m, index_daily, with the 20 Oct - 13 Nov 2026 fill) plus the dhan-data branch NIFTY WEEK options "
        "3 Aug 2020 - 30 Jul 2021 (same Dhan endpoint, identical on the overlap)")
GROUPS = {
    "night": (("N1B", "N1N", "N2"), "overnight", ["NIFTY", "BANKNIFTY"],
              "Night (R3) exactly as built (R147 builder): strong close + breadth + OI build-up at 15:19, 1-ITM, "
              "15:20 -> 09:16; also NIFTY alone and a Rs 56k version (one position a night, month cap -Rs 13k)",
              "signal source: index close location, 59-stock breadth; option OI build-up as context"),
    "liquidity": (("L1",), "liquidity", ["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"],
                  "Liquidity 15+5 exactly as built (R101 LIQ on R51 clean stores, R115 / R106 walk)",
                  "signal source: the index"),
    "r72": (("D1", "D2", "D3"), "patterns-indicators", ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            "R72 Breadth Donchian with its own exits; + R151's frozen logistic picker (0.4656); + FB-R72-A10",
            "signal source: the five indices' 5-minute Donchian breaks"),
    "eod-momentum": (("M1", "M2"), "outside-strategies", ["NIFTY"],
                     "End-of-day intraday momentum (Gao et al. 2018): r1 + r2 (and r2 alone) in the top 40% of 60 "
                     "sessions, buy NIFTY ATM CE / PE 14:50 -> 15:20, no expiry days",
                     "signal source: NIFTY index returns"),
    "noise-breakout": (("V1",), "outside-strategies", ["NIFTY"],
                       "Noise-area breakout (Zarattini, Aziz, Barbon 2024) over TWAP (no index volume), 30-minute "
                       "checks from 10:15, NIFTY ATM, exit back inside max/min(band, TWAP) or 15:20 (15:05 expiry)",
                       "signal source: NIFTY index minutes"),
    "trend-vix-overlay": (("O1", "O2", "O3"), "daily-filters", ["NIFTY"],
                          "M1 / M2 / V1 with calls only above the 50-day average and puts only below, and no entry when "
                          "India VIX's previous close is above its 1-year 90th percentile",
                          "signal source: NIFTY index; daily closes and India VIX as filters"),
}


def line(cid: str, d: dict) -> str:
    parts = []
    for e in ("A", "B", "C"):
        s = d["eras"].get(e)
        parts.append(f"era {e}: untestable" if s is None else
                     f"era {e} {s['trades']} tr, {s['win']:.1f}% win, {rs(s['net'])}, DD {rs(s['max_dd'])}")
    return f"{cid} ({K.NAMES[cid]}): " + "; ".join(parts) + (" -> RELIABLE" if d["reliable"] else " -> not reliable")


def main():
    dec = json.loads((HERE / "decision.json").read_text())
    for g, (cids, fam, inst, rule, src) in GROUPS.items():
        ds = {c: dec["candidates"][c] for c in cids}
        any_rel = any(d["reliable"] for d in ds.values())
        e = dict(
            id=f"r190-{g}", title=f"R190 best strategy hunt: {rule.split(':')[0][:90]}", family=fam, date="2026-11-17",
            doc=DOC, hypothesis=f"{rule}. Is it profitable in all three eras (A Aug 2020 - Jul 2021, B Dec 2021 - Oct "
            "2024, C Nov 2024 - 13 Nov 2026), robust to the top 5 trades, better than random entries in era A, after BH?",
            data_version=DATA,
            period=dict(design="era B 2021-12-01..2024-10-31 (no parameter fitted); era C 2024-11-01..2026-11-13",
                        holdout="era A 2020-08-03..2021-07-30, read once (results/ERA_A_READ.flag); NOT virgin: the "
                                "Oct 2026 h-series hunt used it for other rules"),
            instruments=inst, features=[src, "buy the option; dated Zerodha charges; real 1-minute option prices"],
            model_or_rule=rule, settings="research/hunt/r190_best_hunt/r190cfg.py; PREREG.md section 3", prereg=PREREG,
            variants_tried=len(cids),
            results=dict(summary=" | ".join(line(c, d) for c, d in ds.items()) +
                         f". BH m = {K.M_TOTAL:,}; failed items: " +
                         " / ".join(f"{c}: {'; '.join(d['fails']) or 'none'}" for c, d in ds.items()),
                         costs="dated Zerodha charges; intraday: R17 liq fills; Night: app +-5 bps + h24 half-spread; "
                               "arms: their engines' R17 fills; 2x slippage stress reported"),
            holdout_read=bool(dec["era_a_read"]), verdict="paper-only" if any_rel else "rejected",
            notes="Signal on the underlying, option bought. Research only; nothing in the app changes.")
        (ENTRIES / f"r190-{g}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=False))
    print("entries written")


if __name__ == "__main__":
    main()
