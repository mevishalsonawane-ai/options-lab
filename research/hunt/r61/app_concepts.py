"""Build the app's concept book for Jarvis from R61 / R61b / R62b (15 Oct 2026).

Boss asked Solo to "understand the concepts" (OI, gamma, traps, fake breakouts, order flow ...). This turns
`concepts.json` (R61's 26 + R61b's 11 entries) and R62b's `CONCEPTS_ADD.md` (zigzag, MSB, fib factor, plus newer
results for BOS / ChoCH and order blocks) into one small bundled asset that Jarvis answers from:

    python -m research.hunt.r61.app_concepts            # writes the asset
    python -m research.hunt.r61.app_concepts --check    # fails if the asset is out of date

Every entry says, in plain English: what it is, how the app measures it, where its signal comes from (the house rule:
signals on the underlying index, option-chain data is context only), and the honest research verdict with sample
sizes. No concept here passed the gates on its own; Solo 2.0 (A2) passed as a whole and stays paper only.
Text only: nothing here is a trading rule.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SRC = HERE / "concepts.json"
OUT = REPO / "android/app/src/main/assets/concepts/concepts.json"
SCHEMA = 1

# Where each concept's numbers come from. The app shows the label next to every concept, so Boss can see at a glance
# that only index-chart concepts may give a signal by default (CLAUDE.md "signal on the underlying").
SOURCES = {
    "underlying": "Index chart (the underlying): may be a signal source by default.",
    "chain": "Option chain: context or filter only, never the signal by default.",
    "contract": "The option contract Solo would buy: describes it; the strike rule picks it.",
    "market": "Market-wide data (not a chart signal): context or size only.",
    "orderflow": "Order flow (ticks and order book): context only; our history has only minute-bar proxies.",
}

SOURCE_OF = {
    "oi": "chain", "buildup": "chain", "pcr": "chain", "max_pain": "chain", "oi_walls": "chain", "iv": "chain",
    "iv_rank": "chain", "skew": "chain", "term": "chain", "gex": "chain", "dealers": "chain",
    "delta": "contract", "gamma": "contract", "theta": "contract", "vega": "contract",
    "vix": "market", "participants": "market", "basis": "market", "rollover": "market", "events": "market",
    "news": "market",
    "vwap": "underlying", "atr": "underlying", "orb": "underlying", "value_area": "underlying",
    "regime": "underlying", "fake_breakout": "underlying", "traps": "underlying", "stop_hunt": "underlying",
    "volume_profile": "underlying", "order_blocks": "underlying", "fvg": "underlying", "bos_choch": "underlying",
    "zigzag": "underlying", "msb": "underlying", "fib_factor": "underlying",
    "absorption": "orderflow", "delta_cvd": "orderflow", "ofi": "orderflow", "depth_imbalance": "orderflow",
}

# One honest line per concept, with sample sizes, taken from the entries' own research rows. "Did not help Solo 2.0"
# always refers to R61 / R61b: 5,780 design candidate rows (walk-forward Jul 2025 - Mar 2026) and the final days
# 1 Apr - 14 Oct 2026 (not fresh).
R61 = "In R61 it added nothing to Solo 2.0 (AUC change under 0.01 on 5,780 design candidates and the final days)."
R61B = "In R61b it added nothing to Solo 2.0 (AUC change under 0.01 on 5,780 design candidates and the final days)."
VERDICTS = {
    "oi": "Failed: no OI check helped an arm (OI_FILTER, 145 results; H26, 2,268 variants). " + R61,
    "buildup": "Failed: build-up rules lost (IV1 O3, 7,339 trades; H38, 2,880 variants). " + R61,
    "pcr": "Failed as a filter (R15 F3); PCR as the signal is a non-default option-chain rule, Boss-only "
           "(R57 A4, 121 final trades). " + R61,
    "max_pain": "Failed: price is not pulled to max pain (R16; EXPIRY_PLAYBOOK, 64 expiries). " + R61,
    "oi_walls": "Failed: max-OI strikes were neither walls nor magnets (OF1, 0 of 12 tests on 873 expiries); as a breakout range "
                "random levels did better (R20b, 155 trades). " + R61,
    "iv": "Size only: IV says how much the index may move, not which way (VOLATILITY, 249 days). Solo 2.0 already "
          "uses ATM IV as one input (128 final trades). " + R61,
    "iv_rank": "Failed as a rule (F1 S18, 158 holdout trades); size only. " + R61,
    "skew": "Failed: weak at best (H29, 24 variants; R30 M4 near-miss on 77 trades). " + R61,
    "term": "Failed (H29; MCX options). " + R61,
    "vix": "Size only (R16 / F1). Failed as a filter (R15 F2, 228 trades). Solo 2.0 already uses VIX as one input "
           "(128 final trades). " + R61,
    "delta": "Describes the option only; never a rule (F1). " + R61,
    "gamma": "Describes the option only; 0 of 52 expiry-day gamma rules passed (R21-R37). " + R61,
    "theta": "Describes the option only (EXPIRY_PLAYBOOK, 64 expiries). " + R61,
    "vega": "Describes the option only (F1). " + R61,
    "gex": "Size only: negative GEX means somewhat bigger ranges, never the direction. Trading it failed (R36, 264 "
           "expiries, -Rs 1,03,781; R37, 42 trades, -Rs 40,003; R9, 0 of 28). " + R61,
    "dealers": "Failed: follow-the-group signals stopped working, 0 of 15 held in the last 12 months (OF1, 3,632 days). Who "
               "holds which side is not visible intraday, so it is not an R61 input.",
    "vwap": "Failed as a filter (TREND_FILTER, 249 days; H46, 2,400 variants). Solo 2.0 already has a VWAP-like "
            "input; not tested again.",
    "atr": "Size only (F1); ATR stops did not help (R17 / R20). Solo 2.0 already uses ATR; not tested again.",
    "orb": "On its own the opening-range breakout failed (R3, 2,489 trades, -Rs 2,23,040; R57, 459 final trades). It "
           "is one of Solo 2.0's candidate setups (S4), almost never taken.",
    "value_area": "Rules on value-area levels failed (R9, 0 of 56; H37, 18,170 variants). 'Open outside value and "
                  "go' is Solo 2.0's main setup S3 (92 of 128 final trades; not fresh, paper only).",
    "regime": "Failed as a guard (SIDEWAYS_GUARD, 0 of 35). Solo 2.0 already uses trend / chop inputs; not tested "
              "again.",
    "participants": "Failed (OF1, 3,632 days; H40, 9,600 variants). " + R61,
    "basis": "Failed (H38, 72 real-basis trades). " + R61,
    "rollover": "Barely tested (H40 S10) and not on the phone. Not an R61 input.",
    "events": "Size warning only: RBI and Budget days move more (EVENT_SHIELD, 995 sessions); 0 trade rules "
              "survived (H28, about 10,300 tests). " + R61,
    "news": "Failed: trading headlines lost (h33, 3,072 variants); news tone did not beat Solo 2.0 (R61-N, 127 final "
            "trades, +Rs 85,483 vs +Rs 99,172).",
    "fake_breakout": "Fakes cannot be told apart in advance (R11); buying breaks made no money after costs (H18, 4,537 "
                     "trades). " + R61B,
    "traps": "Failed: trading traps lost (R1 N7, 1,434 trades; R52, 166 final trades, -Rs 17,852). As a skip filter "
             "(R61b F1) it made less than Solo 2.0 (126 final trades, +Rs 90,775 vs +Rs 99,172).",
    "stop_hunt": "Failed: sweeps do not reverse (H18); ORB Sweep lost (R3, 3,156 trades, -Rs 5.06 lakh; R49, 0 of 336). "
                 "R61b's best skip filter made less than Solo 2.0 (117 final trades, +Rs 75,675 vs +Rs 99,172).",
    "absorption": "Failed (R8, one MCX evening; R9, 52 events). " + R61B,
    "delta_cvd": "No lead found (R7, 34-minute sample); minute-bar proxies failed (R9). " + R61B
                 + " Real tick delta needs 3-6 months of the phone's recordings.",
    "ofi": "Not testable on our history (R7, R10 75 tests). " + R61B
           + " Real OFI needs 3-6 months of the phone's recordings.",
    "depth_imbalance": "Waiting for data: walls in the book lasted a median 1.2 s (R8, 11,700 walls, one evening). "
                       "Needs 3-6 months of the phone's recordings.",
    "volume_profile": "Failed as levels (R9, 0 of 32; LEVEL_MAP, 0 of 24). Appears in Solo 2.0 only through S3 "
                      "(paper only).",
    "order_blocks": "Failed: 0 of 16 R62b variants passed (5 indices; win 21-54%, not the 75-80% claimed); R62 "
                    "not robust.",
    "fvg": "Failed: flipped to a loss (R1 N6, 1,796 design trades); R52 lost on 207 final trades; ADVANCED, 209 "
           "trades, -Rs 35,814.",
    "bos_choch": "Failed: lost in all 38 Matrix versions; ADVANCED, 167 trades, -Rs 54,886; R62 0 of 32; R62b as "
                 "a filter did not rescue the rule.",
    "zigzag": "Context only; as R62b's swing source no variant passed (0 of 16, 5 indices).",
    "msb": "Failed: 0 of 16 R62b variants passed (28-415 design trades each, win 21-44%); MATRIX_V22 all 38 "
           "versions lost.",
    "fib_factor": "No effect worth using: changing it moved trade counts 10-20% and turned no variant profitable "
                  "(R62b, 16 variants x 3 values).",
}

# R62b (CONCEPTS_ADD.md, 15 Oct 2026): three new entries, in concepts.json's own field names.
R62B_DOC = "research/HUNT_R62B_MSB_ORDER_BLOCK.md"
R62B_NEW = [
    {
        "id": "zigzag", "name": "Zigzag (swing highs and lows)", "aka": ["zigzag swings", "swing points", "pivots"],
        "what": "A line joining the chart's alternating swing highs and swing lows, ignoring the smaller wiggles in "
                "between. It turns a price chart into a list of 'the last high, the last low, the high before it, "
                "the low before it'.",
        "formula": "EmreKb's script (length 9): the trend turns down when a bar's low is the lowest of the last 9 "
                   "bars and up when a bar's high is the highest of the last 9; the extreme since the last turn "
                   "becomes the swing. A swing is only known 9 bars later.",
        "phone": "not built in",
        "use": "To see the trend at a glance (higher highs and higher lows = up) and to place stops beyond the last "
               "swing.",
        "pitfalls": [
            "A swing is only known AFTER price has moved away from it; a zigzag drawn back at the swing bar looks "
            "perfect in hindsight (repainting).",
            "The length decides everything: a short one finds many small swings, a long one few late ones.",
            "On an intraday chart the zigzag runs across the overnight gap.",
        ],
        "research": [{"study": "R62b", "doc": R62B_DOC,
                      "sample": "5 indices, 5- and 15-minute bars, 20 Nov 2024 - 14 Oct 2026",
                      "finding": "Used as the swing source for the MSB and order-block rule; no variant passed.",
                      "verdict": "failed"}],
        "role": "context only: a way to describe the recent swings; never a trade rule on its own",
        "features": [],
        "r61_result": "R62b: the zigzag swings did not lead to a profitable rule (see Market structure break).",
    },
    {
        "id": "msb", "name": "Market structure break (MSB)",
        "aka": ["MSB", "structure shift", "market structure shift"],
        "what": "The moment the chart's swing pattern turns: in a downtrend, price makes a high that clears the last "
                "swing high by a clear margin (bullish MSB); the mirror for an uptrend (bearish MSB).",
        "formula": "EmreKb compares confirmed zigzag swings, not a close: bullish when the newest swing high is above "
                   "the previous one by more than fib x the last leg; bearish the mirror. After a break, a new swing "
                   "high and low are needed before the next one.",
        "phone": "not built in",
        "use": "As the signal that the trend has changed side, and as the trigger that creates an order block to "
               "trade on the pullback.",
        "pitfalls": [
            "Read from confirmed swings, so it is known late: only after price has already bounced 9 bars.",
            "The same word means different things in different scripts (pivot-based here, close-based in others).",
        ],
        "research": [
            {"study": "R62b", "doc": R62B_DOC,
             "sample": "5 indices, 16 variants, design 20 Nov 2024 - 31 Mar 2026, final 1 Apr - 14 Oct 2026",
             "finding": "MSB + order-block pullback: design win rate 21-44% (28-415 trades a variant), every variant "
                        "failed the design gate; final 30-54%, 4 variants positive but each fails top-5 and design. "
                        "Claimed 75-80%.",
             "verdict": "failed"},
            {"study": "MV-1 Matrix v2.2", "doc": "research/MATRIX_V22.md",
             "sample": "BANKNIFTY Feb 2024 - Feb 2026, 38 versions",
             "finding": "Close beyond the last 3-bar swing pivot: all 38 lost in both years; R52 re-test 602 trades, "
                        "-Rs 86,873.",
             "verdict": "failed"},
        ],
        "role": "not a setup; notes only",
        "features": [],
        "r61_result": "R62b (15 Oct 2026; signal on the index, 1-ITM nearest-expiry option, real fills and charges; "
                      "fib 0.273, zigzag 9): 0 of 16 pass. The best design variant (95 trades, win 41%, +Rs 2,560) "
                      "was positive in only 2 of 5 quarters and lost Rs 896 on 46 final trades.",
    },
    {
        "id": "fib_factor", "name": "Fib breakout confirmation (fib factor)",
        "aka": ["fib factor", "Fibonacci confirmation", "breakout margin"],
        "what": "A margin a break must clear before it counts: a new swing must go beyond the old one by a fraction "
                "(the fib factor) of the last swing leg, so small pokes beyond a level are ignored.",
        "formula": "Bullish break needs the new high above the old by more than fib x the leg; bearish the mirror. "
                   "The script's default is 0.33, the video uses 0.273; R62b also tried 0.50.",
        "phone": "not built in",
        "use": "A larger factor means fewer, 'cleaner' breaks; a smaller one more, earlier breaks.",
        "pitfalls": [
            "0.273 and 0.33 are not magic numbers: nothing in the data prefers them.",
            "Tuning the factor on past charts until the win rate looks good is curve-fitting.",
        ],
        "research": [{"study": "R62b", "doc": R62B_DOC, "sample": "16 variants x 3 fib values",
                      "finding": "fib 0.33: 0 of 16 positive in design; fib 0.50: 3 of 16 positive in design, the "
                                 "best +Rs 1,226 on 46 final trades; not gated.",
                      "verdict": "no effect worth using"}],
        "role": "none",
        "features": [],
        "r61_result": "R62b: changing the factor moves trade counts by about 10-20% and does not turn the rule "
                      "profitable in design.",
    },
]

# R62b rows and results for two concepts R61b left to R62.
R62B_UPDATES = {
    "bos_choch": {
        "research": [
            {"study": "R62", "doc": "research/HUNT_R62_VOLUME_PROFILE_SMC.md",
             "sample": "5 indices + 10 stocks, Nov 2024 onwards, 32 primary tests",
             "finding": "1-hour BOS / ChoCH filter: fewer trades, no better per trade; 0 of 32 pass.",
             "verdict": "failed"},
            {"study": "R62b", "doc": R62B_DOC, "sample": "5 indices, 8 filter-on vs 8 filter-off variants",
             "finding": "The 1-hour MSB filter halved the trades; no variant passed.", "verdict": "failed"},
        ],
        "role": "context only (trend description); never a trade rule or a filter on the arms",
        "r61_result": "R62b, as a filter: design net -Rs 13,395 to -Rs 84,702 at fib 0.273 (except one variant, "
                      "+Rs 2,560 on 95 trades), so it did not rescue the rule.",
    },
    "order_blocks": {
        "research": [
            {"study": "R62b", "doc": R62B_DOC, "sample": "5 indices, 16 variants",
             "finding": "First-touch and rejection entries both failed; of 343-355 fresh 5-minute zones per index "
                        "only 92-106 were touched in the trading window.",
             "verdict": "failed"},
            {"study": "R62", "doc": "research/HUNT_R62_VOLUME_PROFILE_SMC.md", "sample": "5 indices + 10 stocks",
             "finding": "POC on a clean OB roughly halved the design loss but was as bad as the base in the final.",
             "verdict": "failed (not robust)"},
        ],
        "role": "not a setup; notes only",
        "r61_result": "R62b: win rate 21-54% across the 16 gated variants (design and final); average win Rs "
                      "963-3,814 against average loss Rs 1,208-2,750 a lot.",
    },
}


def _short(text: str, limit: int = 600) -> str:
    """Trim long research text at a sentence end so the dialog stays readable."""
    text = " ".join(text.split())
    if len(text) <= limit:
        return text
    cut = text.rfind(". ", 0, limit)
    return text[: cut + 1] if cut > 0 else text[: limit - 3].rstrip() + "..."


def build(src: dict) -> dict:
    concepts = [dict(c) for c in src["concepts"]]
    by_id = {c["id"]: c for c in concepts}
    for cid, upd in R62B_UPDATES.items():
        c = by_id[cid]
        c["research"] = [r for r in c["research"] if r["study"] != "search"] + upd["research"]
        c["role"], c["r61_result"] = upd["role"], upd["r61_result"]
    concepts += [dict(c) for c in R62B_NEW if c["id"] not in by_id]
    out = []
    for c in concepts:
        cid = c["id"]
        src_key = SOURCE_OF[cid]
        out.append({
            "id": cid,
            "name": c["name"],
            "aka": list(c["aka"]),
            "what": c["what"],
            "use": c["use"],
            "measure": _short(c["formula"]),
            "phone": c["phone"],
            "source": src_key,
            "sourceText": SOURCES[src_key],
            "role": c["role"],
            "verdict": VERDICTS[cid],
            "pitfalls": list(c["pitfalls"]),
            "evidence": [{"study": r["study"], "sample": r["sample"], "finding": _short(r["finding"], 300),
                          "verdict": r["verdict"]} for r in c["research"]],
            "latest": _short(c["r61_result"]),
        })
    return {
        "schema": SCHEMA,
        "asOf": "2026-10-15",
        "houseRule": src["house_rule"],
        "summary": "No concept here passed the research gates on its own. Solo 2.0 passed as a whole and stays on "
                   "paper; R61, R61b and R62b found nothing that beats it.",
        "concepts": out,
    }


def render(src_path: Path = SRC) -> str:
    return json.dumps(build(json.loads(src_path.read_text())), ensure_ascii=False, indent=1) + "\n"


def main(argv: list[str]) -> int:
    text = render()
    if "--check" in argv:
        if not OUT.exists() or OUT.read_text() != text:
            print(f"{OUT} is out of date: run python -m research.hunt.r61.app_concepts")
            return 1
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    print(f"wrote {OUT} ({len(text):,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
