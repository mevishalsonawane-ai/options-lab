"""Write research/experiments/entries/r96-<round>-<rule>.yaml from results/design_round<N>.json (+ holdout files).

    python -m research.hunt.r96_mcx_buy.entries [round]
"""
from __future__ import annotations

import json
import sys

import yaml

from research.hunt.r96_mcx_buy import cfg as K
from research.hunt.r96_mcx_buy import report as REP

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
    "R2A": "N1's NR7 break -> 1-ITM, stop -40% / the NR7 opposite extreme, lock ladder, 3 sessions, plus the edge "
           "take-profit (sold at a real print once the strike is the deepest stored ITM strike)",
    "R2B": "as R2A with the 2-ITM strike",
    "R2C": "as R2A on NR7 days that are also inside days (Crabel ID/NR7)",
    "R2D": "as R2A on the CRUDEOIL and NATURALGAS option books",
    "R2E": "as R2A, skipping trades whose 1-lot stop risk (40% of the premium + Rs 100) is above Rs 6,500",
    "R2F": "squeeze release (on >= 3 of the previous 5 sessions) -> ATM straddle, stop -30%, lock ladder, 5 sessions, "
           "edge take-profit; 4 minis + CRUDEOIL, NATURALGAS",
    "R2G": "NR7 day -> ATM straddle next 09:15, stop -30%, lock ladder, 3 sessions, edge take-profit",
    "R2H": "close beyond the previous 20 closes' high / low with an NR7 day in the previous 5 sessions -> 1-ITM, stop "
           "-40% / 1 ATR14, lock ladder, 5 sessions, edge take-profit",
    "R3A": "as R2A (NR7 break -> 1-ITM, edge take-profit, 3 sessions) on CRUDEOILM and CRUDEOIL; books chosen after "
           "round 2",
    "R3B": "as R3A with NR4 (the narrowest range of 4 sessions)",
    "R3C": "as R2D (CRUDEOIL, NATURALGAS) without the premium lock ladder: the future's stop moves to the entry price "
           "after +1 ATR14, then trails 1.5 ATR14 behind the best minute close; -40% premium stop and edge kept",
    "R3D": "as R2D, only NR7 days whose ATR14 / close is in the bottom third of its last-250-session range",
    "R3E": "weekly NR7 (narrowest of 7 ISO weeks) -> first future minute close beyond that week's high / low during the "
           "next week -> 1-ITM, stop -40% / the week's opposite extreme, lock ladder, edge, 5 sessions; CRUDEOIL, "
           "NATURALGAS",
    "R3F": "as R2D, only breaks between 09:15 and 11:15",
    "R3G": "as R2D, but a break before the US cash open (09:30 ET) that still holds at the minute before it enters at "
           "the US open",
    "R3H": "EIA crude days: the future's 11:00-11:30 ET return -> 1-ITM that way; stop -40% / the 11:00 ET price, lock "
           "ladder, edge, out at the session square-off; CRUDEOILM, CRUDEOIL",
    "R3I": "at the session close - 45 min, the future's move since the previous session's last minute, if >= the 67th "
           "percentile of the last 60 sessions' moves -> 1-ITM that way; stop -40% / 0.25 ATR14, out at close - 15",
    "R3J": "previous 5-day Garman-Klass variance in the top third of its last-250 range; first minute close beyond the "
           "09:00-09:29 ET range between 09:30 and 11:00 ET -> 1-ITM; stop -40% / the range midpoint, lock ladder, edge, "
           "out at the square-off; CRUDEOILM, CRUDEOIL",
    "R3K": "R3A's NR7 break on crude -> 1-ITM option; exits = R93's daily set on the future (stop entry -/+ 2 ATR14, "
           "chandelier best session close -/+ 3 ATR14, never loosening, checked at each session's square-off minute) "
           "+ -40% premium stop; no premium ladder, no edge take-profit (deep-ITM exits model-priced, flagged); out "
           "after 10 sessions or before the option expiry; Boss's request",
    "R3L": "as R3K, trading the crude future itself (CRUDEOILM x10, CRUDEOIL x100); R93 futures costs",
    "R4A": "R3A's NR7 crude signal; a break from 09:15 to the minute before the US cash open that still holds at that "
           "minute enters at the US open (09:30 ET); 1-ITM, stop -40% / NR7 opposite extreme, lock ladder, edge, 3 "
           "sessions; books chosen after rounds 2-3",
    "R4B": "as R3A, only breaks in the direction of the sign of the back-adjusted 250-session return at the NR7 day",
    "R4C": "EIA crude days: the future's move from the minute before the release to release + 5 min, if >= 0.20 ATR14 "
           "-> 1-ITM against it; stop -40% / the spike's extreme, lock ladder, edge, out after 60 minutes",
    "R4D": "GOLDM, SILVERM: the future's 17:00 -> 17:30 IST move -> 1-ITM that way; stop -40% / the 17:00 price, lock "
           "ladder, edge, out at the session square-off",
    "R4E": "as R3A with the 2-ITM strike",
    "R4F": "as R3A, only breaks in the direction of the sign of the back-adjusted 20-session return at the NR7 day",
    "R4G": "as R3A, only when the NR7 day's median ATM IV (option-chain filter) is below the median of the previous 20 "
           "sessions' values",
    "R5A": "EIA natural-gas storage days: 1-ITM call at the first minute at or after 17:00 IST; stop -40% / the future "
           "1 ATR14 below the signal close, lock ladder, edge, out at the session square-off; NATGASMINI, NATURALGAS",
    "R5B": "as R2A (NR7 break -> 1-ITM, edge, 3 sessions) on NATGASMINI, NATURALGAS, GOLDM, SILVERM, only when R4G's "
           "cheap-IV filter (option-chain context) holds",
    "R5C": "NR7 day -> ATM straddle next session 09:15, stop -30%, lock ladder, edge, 3 sessions (R2G) on crude, only "
           "on R4G's cheap-IV days",
    "R5D": "as R4C with |move| >= 0.10 ATR14",
    "R5E": "EIA crude days (crude) / EIA storage days (gas): ATM straddle at 17:00 IST, out at the release - 5 min; "
           "stop -25%, short event ladder",
    "R5F": "gold-minus-silver daily return index above / below its previous 20 values' max / min -> 1-ITM SILVERM put / "
           "call next session 09:15; stop -40% / 1.5 ATR14, lock ladder, edge, 5 sessions",
    "R6A": "as R3A (NR7 break -> 1-ITM, stop -40% / the NR7 opposite extreme, lock ladder, edge, 3 sessions) on the "
           "COPPER and ZINC option books, never used by R96 before",
    "R6B": "as R6A, only when R4G's cheap-IV filter (option-chain context) holds",
    "R6C": "the future's move from the previous session's last minute to 09:30 IST, if >= the 67th percentile of the "
           "last 60 sessions' moves -> 1-ITM that way at close - 45 min; stop -40% / 0.25 ATR14, out at close - 15; "
           "COPPER, ZINC",
    "R6D": "first back-adjusted close above / below the previous 20 closes' max / min -> 1-ITM next session 09:15; "
           "stop -40% / 1.5 ATR14, lock ladder, edge, 5 sessions; COPPER, ZINC",
}
PREREG = {1: "research/hunt/r96_mcx_buy/PREREG_ROUND1.md", 2: "research/hunt/r96_mcx_buy/PREREG_ROUND2.md",
          3: "research/hunt/r96_mcx_buy/PREREG_ROUND3.md", 4: "research/hunt/r96_mcx_buy/PREREG_ROUND4.md",
          5: "research/hunt/r96_mcx_buy/PREREG_ROUND5.md", 6: "research/hunt/r96_mcx_buy/PREREG_ROUND6.md"}


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
                f"BH q {r['bh_q']:.3f} (cumulative ledger, {dj['ideas_in_ledger']} ideas); model-priced exits "
                f"{s.get('model_share', 0):.0%}, real-exit net Rs {s.get('real_exit_net', 0):,.0f}. "
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
            instruments=sorted(r["summary"].get("by_book", {})) or ["CRUDEOILM", "NATGASMINI", "GOLDM", "SILVERM"],
            features=["signal source: the MCX future (near future minute closes; daily: the near-month daily "
                      "continuous future, back-adjusted)", "ATM IV as a filter only (V1, R4G, R5B, R5C, R6B)"],
            model_or_rule=RULE_TEXT[rid],
            settings=f"research/hunt/r96_mcx_buy/cfg.py; {PREREG[round_no].split('/')[-1]}",
            prereg=PREREG[round_no], variants_tried=1,
            results=dict(summary=summ, costs="Zerodha MCX option charges + half-spread 0.30% from 17:00 / 0.60% before "
                                             "(NATGASMINI / NATURALGAS 0.45% / 0.90%"
                                             + ("; COPPER 0.50% / 1.00%, ZINC 1.25% / 2.50%" if round_no >= 6 else "")
                                             + ") each fill, 2x on model-priced exits; gates also at 2x spreads"),
            holdout_read=bool(hj is not None),
            verdict=("paper-only" if (hj and hj["pass_"]) else ("inconclusive" if r.get("lead") else "rejected")),
            notes=(f"lead for round {round_no + 1}" if r.get("lead") and not r.get("pass") else ""))
        if clash and not clash[0].read_text().startswith(f"id: {eid}"):
            raise SystemExit(f"id clash: {eid}")
        (OUT / f"{eid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        print("wrote", eid)


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
