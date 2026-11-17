"""R191 trade streams (PREREG.md section 2): R190's priced trades, read from its caches (never in git).

    N  Night (R3) book, NIFTY + BANKNIFTY, 1 lot each   r190night.night_rows / trades_of (R147 builder caches)
    L  Liquidity 15+5 (BANKNIFTY, FINNIFTY, MIDCPNIFTY)  ~/.cache/iraalgo/r190/arms_trades.pkl "L1"
    R  R72 + R151 picker                                 ~/.cache/iraalgo/r190/arms_trades.pkl "D2"
    VIX flag per day                                     ~/.cache/iraalgo/r190/rules_NIFTY.pkl "vix_high"

Run the readers with python3 -I (the caches are data). Nothing here prices a trade: every net is R190's.
"""
from __future__ import annotations

import pickle
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
R190 = HERE.parent / "r190_best_hunt"
for p in (str(R190), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

import r190cfg as K190  # noqa: E402

import r191lib as L  # noqa: E402


def night_stream() -> list:
    import r190night as NT
    out = []
    for u in K190.NIGHT_UNDS:
        rows = NT.night_rows(u)
        nd = {r["day"]: r["nd"] for r in rows}
        for t in NT.trades_of(rows):
            out.append(L.night_trade(u, t["day"], nd[t["day"]], t["era"], t["net"], prem=t["prem"], side=t["side"]))
    return out


def arm_stream(key: str, comp: str) -> list:
    d = pickle.loads((K190.OUT / "arms_trades.pkl").read_bytes())
    out = []
    for t in d.get(key) or []:
        era = K190.era_of(t["day"])
        if era is not None:
            out.append(L.intraday_trade(comp, t["und"], t["day"], era, t["net"], er=int(t.get("er", 0)), side=t.get("side")))
    return out


def vix_high_days() -> set:
    rows = pickle.loads((K190.OUT / "rules_NIFTY.pkl").read_bytes())
    return {r["day"] for r in rows if r.get("vix_high")}


def load_all() -> tuple:
    """({'N','L','R'} -> trades with an era, set of high-VIX days)."""
    return dict(N=night_stream(), L=arm_stream("L1", "L"), R=arm_stream("D2", "R")), vix_high_days()
