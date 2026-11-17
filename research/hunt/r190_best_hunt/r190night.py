"""R190 candidate 1 (PREREG.md section 3): Night (R3) exactly as built, through R147's builder, on R190's flat lake.

    python3 -I research/hunt/r190_best_hunt/r190night.py build     # ~/.cache/iraalgo/r190/r147/nights_<UND>.pkl

R147's builder (R27's exact rebuild of the app's NightRules) is imported unchanged; only its folders are pointed at
R190's lake and cache. Pricing (here, not in the builder): R147's fills (app +-5 bps and h24's half-spread on both legs)
with DATED charges (the entry leg on the entry day's rates, the exit leg on the next session's).
"""
from __future__ import annotations

import os
import pickle
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r190cfg as K  # noqa: E402

os.environ["IRAALGO_LAKE"] = str(K.LAKE)
os.environ["R147_OUT"] = str(K.OUT / "r147")

import numpy as np  # noqa: E402

from research.obuy.costs import Costs, adverse_bps  # noqa: E402

DATED = Costs("dated")
HALF_SPREAD = {"NIFTY": 0.0016, "BANKNIFTY": 0.0016}
EPOCH_DN = 0


def _dn(d) -> int:
    from datetime import date
    return (d - date(1970, 1, 1)).days


def build():
    from research.hunt.r147_night_plus import data as D7
    D7.K.OUT.mkdir(parents=True, exist_ok=True)
    vix = D7.index_days("INDIAVIX")
    br = D7.breadth_59()
    print("breadth sessions", int(br.notna().sum()), br.index.min(), br.index.max(), flush=True)
    us_ev, in_ev = D7.events()
    for u in K.NIGHT_UNDS:
        ns = D7.build(u, vix, br, None, None, us_ev, in_ev)
        (D7.K.OUT / f"nights_{u}.pkl").write_bytes(pickle.dumps(ns))
        print(u, "nights", len(ns), ns[0]["d"], ns[-1]["d"], flush=True)


def load(und: str) -> list:
    return pickle.loads((K.OUT / "r147" / f"nights_{und}.pkl").read_bytes())


def leg_net(und: str, d, nd, qty: int, e: float, x: float, mult: float = 1.0, lots: int = 1) -> float:
    """Net of one Night leg: buy at e (raw 15:20 open) on d, sell at x (raw 09:16 open) on nd. mult 2 = the stress
    (twice the bps and twice the half-spread)."""
    bps = int(round(5 * mult))
    ef = float(adverse_bps(np.array([e]), bps, True)[0])
    xf = float(adverse_bps(np.array([x]), bps, False)[0])
    q = qty * lots
    sp = HALF_SPREAD[und] * (e + x) * q * mult
    ch = (DATED.charge(True, np.array([ef]), np.array([q]), np.array([_dn(d)]))[0]
          + DATED.charge(False, np.array([xf]), np.array([q]), np.array([_dn(nd)]))[0])
    return float((xf - ef) * q - ch - sp)


def entry_fill(e: float) -> float:
    return float(adverse_bps(np.array([e]), 5, True)[0])


def night_rows(und: str) -> list:
    """Every night with its era, R3 side, both 1-ITM legs' 1-lot nets (NaN if not priced) and the traded leg's numbers."""
    from r190lib import fit_lots
    out = []
    for r in load(und):
        era = K.era_of(r["d"])
        if era is None or not r["tradable"]:
            continue
        legs = r.get("legs") or {}
        row = dict(und=und, day=r["d"], nd=r["nd"], era=era, side=int(r["r3"]), qty=int(r["qty"]))
        for side, nm in ((1, "c"), (-1, "p")):
            lg = legs.get((side, 1))
            if lg is None or lg[3] != "ok":
                row[f"net_{nm}"] = np.nan
                continue
            _, e, x, _ = lg
            row[f"net_{nm}"] = leg_net(und, r["d"], r["nd"], row["qty"], e, x)
            row[f"stress_{nm}"] = leg_net(und, r["d"], r["nd"], row["qty"], e, x, mult=2.0)
            ef = entry_fill(e)
            row[f"prem_{nm}"] = ef * row["qty"]
            L = fit_lots(ef, row["qty"])
            row[f"lots_{nm}"] = L
            row[f"fit_{nm}"] = leg_net(und, r["d"], r["nd"], row["qty"], e, x, lots=L) if L > 0 else np.nan
        out.append(row)
    return out


def trades_of(rows: list) -> list:
    """The rule's trades from night rows: side != 0 and the chosen leg priced."""
    out = []
    for r in rows:
        if r["side"] == 0:
            continue
        nm = "c" if r["side"] > 0 else "p"
        if not np.isfinite(r.get(f"net_{nm}", np.nan)):
            continue
        out.append(dict(und=r["und"], day=r["day"], era=r["era"], side=r["side"], net=r[f"net_{nm}"],
                        stress=r[f"stress_{nm}"], fit=r[f"fit_{nm}"] if np.isfinite(r[f"fit_{nm}"]) else None,
                        lots=r[f"lots_{nm}"], prem=r[f"prem_{nm}"]))
    return out


def n2_trades(nifty: list, bank: list) -> list:
    """N2: one position a night (NIFTY when both signal), 1 lot, only if the premium fits Rs 56,000; then the month cap."""
    from r190lib import month_capped
    by = {}
    for t in bank:
        by[t["day"]] = t
    for t in nifty:
        by[t["day"]] = t                          # NIFTY wins a shared night
    picked = [t for t in by.values() if t["prem"] <= K.ACCOUNT]
    return month_capped(picked)


def twin_pool(rows: list, era: str) -> tuple:
    ok = [r for r in rows if r["era"] == era and np.isfinite(r.get("net_c", np.nan)) and np.isfinite(r.get("net_p", np.nan))]
    return np.array([r["net_c"] for r in ok]), np.array([r["net_p"] for r in ok])


if __name__ == "__main__":
    {"build": build}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
