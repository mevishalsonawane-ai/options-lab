"""R45 settings, fixed in PREREG.md (14 Oct 2026) before any P&L. Signal source of every variant: the OPTION'S OWN
PRICE CHART (rolling ATM call, or one fixed call) - not the default kind of rule; reported separately."""
from __future__ import annotations

from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"

DESIGN_END = date(2026, 4, 13)          # research/brain/holdout.py
HOLDOUT_START = date(2026, 4, 27)       # 14-26 Apr 2026 embargo, used by neither

CLAIM = dict(trades=3726, net=649957, npt=174, win=53.4, pf=1.45, green="52/63", max_dd=24809,
             years={2021: 231, 2022: 195, 2023: 190, 2024: 73, 2025: 120, 2026: 125}, bn_npt=14, tpd=3.2)

N_TWINS = 300
SEED = 45

# A variant: und, series (tuple of WEEK/MONTH, simulated one by one and pooled), signal ('roll' = rolling ATM call,
# 'k915' = the 09:15 ATM strike all session), price ('roll' or 'fixed' = the strike ATM at the entry bar), rules
# (r45sim.Rules fields), expiry ('theirs' = any of the last 5 closes < 1.0, 'real', 'none'), cost ('flat95' or
# 'dated' = research/obuy Costs("dated")), slip (fraction of price each side), lot ('75' or 'hist'), boll ('day' or
# 'cont' across sessions), ddof (Bollinger SD).
T0 = dict(und="NIFTY", series=("WEEK",), signal="roll", price="roll", rules={}, expiry="theirs", cost="flat95",
          slip=0.0, lot="75", boll="day", ddof=0)

# Reproduction aids (step 0): every series x band combination, no twins.
REPRO = {f"R-{'+'.join(s)}-{b}-sd{d}": dict(series=s, boll=b, ddof=d)
         for s in (("WEEK",), ("MONTH",), ("WEEK", "MONTH")) for b in ("day", "cont") for d in (0, 1)}

P2 = dict(gap=True, prev_lock=True)
P3A = dict(checked=3, exit_at=2)
P3B = dict(checked=4, exit_at=3)
FIX = dict(series=("WEEK",), rules={**P2, **P3A}, expiry="real", cost="dated", slip=0.005)

# Applied on top of the chosen T0 (the best reproduction). 'rules' entries are merged.
VARIANTS = {
    "T0": {},
    "P1a-fixed-contract": dict(price="fixed"),
    "P1b-0915-contract": dict(signal="k915", price="roll"),
    "P2-gap-fills": dict(rules=P2),
    "P3a-15min": dict(rules=P3A),
    "P3b-20min-checked": dict(rules=P3B),
    "P4-week-only": dict(series=("WEEK",)),
    "P5-costs-slip0": dict(cost="dated", slip=0.0),
    "P5-costs-slip0.5": dict(cost="dated", slip=0.005),
    "P5-costs-slip1": dict(cost="dated", slip=0.01),
    "P5-hist-lots": dict(lot="hist"),
    "P6-real-expiry": dict(expiry="real"),
    "F-a": dict(FIX, price="fixed"),
    "F-b": dict(FIX, signal="k915", price="roll"),
    "F-a-slip1": dict(FIX, price="fixed", slip=0.01),
    "F-b-slip1": dict(FIX, signal="k915", price="roll", slip=0.01),
    "F-a-hist-lots": dict(FIX, price="fixed", lot="hist"),
    "F-b-hist-lots": dict(FIX, signal="k915", price="roll", lot="hist"),
    "BN-T0": dict(und="BANKNIFTY"),
    "BN-F-a": dict(FIX, und="BANKNIFTY", price="fixed", lot="hist"),   # BANKNIFTY at its real lot
}
CANDIDATES = ("F-a", "F-b")             # only the tradable all-fixes versions can "survive"


def variant(base: dict, over: dict) -> dict:
    v = {**base, **{k: x for k, x in over.items() if k != "rules"}}
    v["rules"] = {**base.get("rules", {}), **over.get("rules", {})}
    v["series"] = tuple(v["series"])
    return v
