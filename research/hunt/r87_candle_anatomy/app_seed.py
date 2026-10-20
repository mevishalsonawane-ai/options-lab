"""The Candle agent's research seed (20 Oct 2026): a compact copy of the R87 / R87B / R85 knowledge for the app.

The app's Candle agent says, beside every candle it reads, what that kind of candle did historically, with the sample
size. The full pack (knowledge/candle_pack.json, ~500 KB with the +/-50-candle paths) is too big to ship, so this keeps
per family and per index x timeframe only the numbers the agent says (n, up over 5 candles vs matched, the effect in ATR
and points, BH q, the far end broken in 10 candles vs the same distance, held out of sample) plus each family's
definition, textbook reason (a labelled hypothesis) and verdict, R87B's plain line and R85's MCX verdict.

Signal source of every number: the index's own candle (spot) for R87, the MCX future for R85. Descriptive only: no rule
in it passed, and the agent never trades on it.

    python -m research.hunt.r87_candle_anatomy.app_seed            # writes the app asset
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PACK = HERE / "knowledge" / "candle_pack.json"
OUT = HERE.parents[2] / "android" / "app" / "src" / "main" / "assets" / "knowledge" / "candle_seed.json"

MCX = ("R85 (MCX candles, 19 Oct 2026; signal source: the MCX future): no candle shape pays an MCX option buyer. "
       "59 of 7,564 candle cells looked significant in design; 18 survived a same-colour, same-time test; "
       "all 4 option rules lost in the final (-Rs 17k to -Rs 30k each). Most of what candles 'mean' on MCX is the trend. "
       "Candles are biggest in the US-open hour; the morning and the 14:00-17:00 lull are quietest.")


def _r(x, nd=3):
    return None if x is None else round(float(x), nd)


def seed(pack: dict) -> dict:
    fams = []
    for f in pack["families"]:
        rows = []
        for s in f["stats"]:
            held = s.get("holds_oos")
            rows.append([s["und"], int(s["tf"]), int(s["n"]), _r(s["up_rate_5"]), _r(s["base_up_rate_5"]), _r(s["eff_atr_5"]),
                         _r(s["move_pts_5"], 1), _r(s["q"]), _r(s.get("low_broken_10")), _r(s.get("low_broken_10_base")),
                         _r(s.get("high_broken_10")), _r(s.get("high_broken_10_base")),
                         1 if held is True else (0 if held is False else -1)])
        fams.append({"k": f["family"], "name": f["name"], "def": f["definition"], "why": f["mechanism_hypothesis"],
                     "verdict": f["verdict"], "s": rows})
    b = pack.get("r87b", {})
    return {
        "schema": 1, "study": "R87 + R87B + R85", "date": pack["date"], "doc": pack["doc"],
        "design": pack["design"], "holdout": pack["holdout"],
        "held": pack["holdout_check"]["held"], "findings": pack["holdout_check"]["design_findings"],
        "tested": sum(v["tested"] for v in pack["design_tests"].values()),
        "r87b": b.get("plain", ""), "mcx": MCX, "families": fams,
    }


def main(argv=None) -> int:
    pack = json.loads(PACK.read_text())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(seed(pack), separators=(",", ":"), ensure_ascii=False) + "\n")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
