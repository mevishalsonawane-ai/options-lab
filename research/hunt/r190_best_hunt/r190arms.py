"""R190 candidates 2 and 5 (PREREG.md section 3): Liquidity 15+5 (L1) and R72 (D1, D2 picker, D3 FB-R72-A10), exactly as
built, through the same engine stack R143 / R144a / R144b / R151 used (R115 -> R106 -> R101 -> R51's clean stores).

    python3 -I research/hunt/r190_best_hunt/r190arms.py build     # R51 clean stores, 3 Aug 2020 - 13 Nov 2026
    python3 -I research/hunt/r190_best_hunt/r190arms.py parity    # published windows (R144a fresh year, R143's two years)
    python3 -I research/hunt/r190_best_hunt/r190arms.py run       # trades of L1 / D1 / D2 / D3, 1 Dec 2021 - 13 Nov 2026

The stores live in ~/.cache/iraalgo/r190/r51/clean (never in git). Era A is untestable for these arms (no BANKNIFTY /
FINNIFTY / MIDCPNIFTY / SENSEX data before Aug 2021), so no trade before 1 Dec 2021 is priced here.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r190cfg as K  # noqa: E402

os.environ["IRAALGO_LAKE"] = str(K.LAKE)
os.environ["R51_OUT"] = str(K.OUT / "r51")
os.environ["R51_CLEAN"] = "1"
os.environ.pop("R51_STALE", None)

import numpy as np  # noqa: E402

STORE = (date(2020, 6, 1), date(2020, 8, 3), date(2026, 11, 13))      # warm-up, first option day, last day
RUN = (K.ERAS["B"][0], K.ERAS["C"][1])
A10_CUT = 1.3206                                                     # R140 / R143's frozen cut
PICK_THR = 0.4656                                                    # R151 Amendment C
PARITY = {"R144a fresh": (date(2021, 11, 1), date(2022, 11, 30)), "R143 unseen": (date(2022, 12, 1), date(2024, 11, 19))}
PUBLISHED = {("R144a fresh", "R72"): (1169, -67223), ("R143 unseen", "R72"): (3395, -306931),
             ("R143 unseen", "LIQ"): (894, 83000)}
TRADES = K.OUT / "arms_trades.pkl"


def engine():
    sys.path.insert(0, str(K.HUNT / "r142_bad_break_noprogress"))
    import r142 as B
    k = B.S.mods()["r51data"].K
    k.WARM_START, k.START, k.END = STORE
    B.S.mods()["C"].open_final()
    return B


def build():
    B = engine()
    import r51data  # noqa: F401  (pickle the Day class under its module name)
    for u in B.S.mods()["r51data"].K.UNDS:
        try:
            print(B.S.mods()["r51data"].build(u), flush=True)
        except ValueError as e:
            print(u, "store written, no option days:", e, flush=True)


def norm(arm: str, t: dict) -> dict:
    return dict(arm=arm, und=t["und"], day=t["day"], net=float(t["net"]), side=t.get("side"),
                er=int(t.get("er_sig", t.get("er", -1))))


def parity():
    B = engine()
    S = B.S
    out = {}
    for name, (lo, hi) in PARITY.items():
        for arm in ("R72", "LIQ"):
            tr = S.walk(arm, S.units(arm, lo, hi))
            if arm == "LIQ":
                tr = [t for t in tr if t["und"] in ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")]
            tr = [t for t in tr if lo <= t["day"] <= hi]
            out[f"{name} {arm}"] = dict(trades=len(tr), net=round(float(sum(t["net"] for t in tr))),
                                        published=PUBLISHED.get((name, arm)))
            print(name, arm, out[f"{name} {arm}"], flush=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "arms_parity.json").write_text(json.dumps(out, indent=1))


def picker_scores(us: list) -> dict:
    """R151's frozen R72 logistic picker (numbers in r72_picker_model.json) on every candidate of these units."""
    sys.path.insert(0, str(K.HUNT / "r151_donchian_winrate60"))
    sys.argv = [sys.argv[0], "design"]
    import study as T
    from research.hunt.r151_donchian_winrate60 import pick as PK
    nums = json.loads((K.HUNT / "r151_donchian_winrate60" / "results" / "r72_picker_model.json").read_text())
    fx = T.feats_of_units(us)
    out = {}
    for key, cands, _, _ in us:
        for c in cands:
            ck = T.cand_key(key[0], key[1], c["er"], c["side"])
            out[ck] = PK.score_from_numbers(nums, [fx[ck].get(f, np.nan) for f in nums["feats"]])
    return out


def run():
    B = engine()
    S, A = B.S, B.A
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), lo=str(RUN[0]), hi=str(RUN[1]))}
    liq = [norm("L1", t) for t in S.walk("LIQ", S.units("LIQ", *RUN)) if t["und"] in ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")]
    print("L1", len(liq), flush=True)
    us = S.units("R72", *RUN)
    d1 = [norm("D1", t) for t in S.walk("R72", us)]
    print("D1", len(d1), flush=True)
    feats = A.all_features(us)

    def keep_a10(g, j, key, c):
        x = feats.get(A.cand_key(key[0], key[1], c), {}).get("A10")
        return not (x is not None and np.isfinite(x) and x >= A10_CUT)
    d3 = [norm("D3", t) for t in S.walk("R72", S.filtered(us, keep_a10))]
    print("D3", len(d3), flush=True)
    try:
        sc = picker_scores(us)
        sys.path.insert(0, str(K.HUNT / "r151_donchian_winrate60"))
        import study as T
        d2 = [norm("D2", t) for t in S.walk("R72", S.filtered(
            us, lambda g, j, key, c: sc.get(T.cand_key(key[0], key[1], c["er"], c["side"]), -1) >= PICK_THR))]
        d2_note = "R151 picker numbers (threshold 0.4656) on R151's own features"
    except Exception as e:  # noqa: BLE001  (PREREG: D2 reported as not run, with the reason)
        d2, d2_note = None, f"not run: {type(e).__name__}: {e}"
    print("D2", None if d2 is None else len(d2), d2_note, flush=True)
    data = {"L1": liq, "D1": d1, "D2": d2, "D3": d3, "D2_note": d2_note}
    TRADES.write_bytes(pickle.dumps(data))
    print("written", TRADES, flush=True)


if __name__ == "__main__":
    {"build": build, "parity": parity, "run": run}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
