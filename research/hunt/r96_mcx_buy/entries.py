"""Write research/experiments/entries/r96-<round>-<rule>.yaml from results/design_round<N>.json (+ holdout files).

    python -m research.hunt.r96_mcx_buy.entries [round]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

from research.hunt.r96_mcx_buy import cfg as K
from research.hunt.r96_mcx_buy import report as REP
from research.hunt.r96_mcx_buy import rules as R

ROOT = K.HERE.parents[2]
OUT = ROOT / "research" / "experiments" / "entries"
RULE_TEXT = {
    "S1": "daily squeeze release (Bollinger 20,2 inside Keltner EMA20 +/- 1.5 ATR20 on >= 5 of 6 sessions, then not) -> "
          "ATM straddle next session 09:15; stop -30%, lock ladder, out after 5 sessions",
    "S2": "daily squeeze release, side = close vs SMA20 -> 1-ITM next session 09:15; stop -40% premium / 1 ATR14 on "
          "the future, lock ladder, out after 7 sessions",
    "N1": "NR7 day -> next session first future minute close beyond its high / low (09:15-22:00) -> 1-ITM; stop -40% "
          "/ the NR7 opposite extreme, lock ladder, out after 3 sessions",
    "W1": "range >= 1.8 ATR14 and close in the top / bottom 25% -> 1-ITM next session 09:15; stop -40% / 1 ATR14, lock "
          "ladder, out after 5 sessions",
    "P1": "20- and 60-day returns agree, 2 closes against them -> 1-ITM on the trend side next session 09:15; stop -40% "
          "/ 1.5 ATR14, lock ladder, out after 5 sessions",
    "V1": "ATM IV at 17:00 < HAR forecast of the next 5 sessions' realised vol -> ATM straddle at 17:01; stop -30%, lock "
          "ladder, out after 5 sessions",
    "E1": "ATM straddle 20 minutes before EIA crude / EIA storage / US CPI / NFP, out 45 minutes after; stop -25%, "
          "short lock ladder",
    "E2": "the future's first 15 minutes after the release -> 1-ITM that way; stop -40% / the pre-release price, lock "
          "ladder, square-off the same session",
    "U1": "the future's first US half-hour (09:30-10:00 ET) >= 1.5x its 20-session median size -> 1-ITM that way; stop "
          "-40% / the 09:30 ET price, lock ladder, square-off the same session",
}


def main(round_no: int = 1):
    dj = json.loads((K.RESULTS / f"design_round{round_no}.json").read_text())
    doc = f"research/HUNT_R96_ROUND{round_no}.md"
    assert (ROOT / doc).exists(), f"write {doc} first"
    for rid, r in dj["rules"].items():
        eid = f"r96-{round_no}-{rid.lower()}"
        clash = [p for p in OUT.glob(f"{eid}.yaml")]
        s = r["summary"]
        hold = K.RESULTS / f"holdout_{rid}.json"
        hj = json.loads(hold.read_text()) if hold.exists() else None
        fl = ", ".join(f"F{f['fold']} Rs {f['net']:,.0f} ({f['trades']})" for f in r["folds"])
        summ = (f"Design: {s.get('trades', 0)} trades, net Rs {s.get('net', 0):,.0f} (2x spread Rs {s.get('net2', 0):,.0f}), "
                f"gross Rs {s.get('gross', 0):,.0f}, win {s.get('win', 0):.0%}, worst month Rs "
                f"{s.get('worst_month', 0):,.0f}, max DD Rs {s.get('max_dd', 0):,.0f}, without top 3 Rs "
                f"{s.get('wo_top3', 0):,.0f}; folds {fl}; twin mean Rs {r['twin_mean']:,.0f}, twin p {r['twin_p']:.3f}, "
                f"BH q {r['bh_q']:.3f} (cumulative ledger, {dj['ideas_in_ledger']} ideas). "
                f"Gates failed: {', '.join(k for k, v in r['gates'].items() if not v) or 'none'}.")
        if hj:
            hs = hj["summary"]
            summ += (f" Holdout (read once): {hs.get('trades', 0)} trades, net Rs {hs.get('net', 0):,.0f}, twin p "
                     f"{hj['twin_p']:.3f}, {'PASS' if hj['pass_'] else 'FAIL'}.")
        e = dict(
            id=eid, title=f"R96 round {round_no} {rid}: {REP.NAMES[rid]} (MCX option buying)", family="mcx-crypto",
            date="2026-10-21", doc=doc,
            hypothesis=f"Boss (21 Oct): find an MCX commodity option BUYING strategy. {rid} earns after real MCX costs on "
                       "design (5 Aug 2025 - 14 Jul 2026), beats random-session twins (p < 0.05, BH q <= 0.10 over the "
                       "cumulative R96 ledger), in >= 3 of 4 walk-forward folds, worst month > -Rs 13k.",
            data_version="Drive lake gdrive:IraAlgo-data-lake (options_rolling_1m MONTH books to 19 Oct 2026: near-future "
                         "spot + ATM-3..+3 minutes with IV; fut_daily_continuous to 16 Oct 2026), copied 21 Oct",
            period=dict(design="2025-08-05..2026-07-14 (folds Aug-Oct, Nov-Jan, Feb-Apr, May-14 Jul)",
                        holdout="2026-07-15..2026-10-19 (locked; read once only for a design pass)"),
            instruments=["CRUDEOILM", "NATGASMINI", "GOLDM", "SILVERM"],
            features=["signal source: the MCX future (near future minute closes; daily: the near-month daily "
                      "continuous future, back-adjusted)", "ATM IV as a filter only (V1)"],
            model_or_rule=RULE_TEXT[rid],
            settings="research/hunt/r96_mcx_buy/cfg.py; PREREG_ROUND1.md s3-6",
            prereg="research/hunt/r96_mcx_buy/PREREG_ROUND1.md", variants_tried=1,
            results=dict(summary=summ, costs="Zerodha MCX option charges + half-spread 0.30% from 17:00 / 0.60% before "
                                             "(NATGASMINI 0.45% / 0.90%) each fill, 2x on model-priced exits; gates "
                                             "also at 2x spreads"),
            holdout_read=bool(hj is not None),
            verdict=("paper-only" if (hj and hj["pass_"]) else ("inconclusive" if r.get("lead") else "rejected")),
            notes=("lead for round 2" if r.get("lead") and not r.get("pass") else ""))
        if clash and not clash[0].read_text().startswith(f"id: {eid}"):
            raise SystemExit(f"id clash: {eid}")
        (OUT / f"{eid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        print("wrote", eid)


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
