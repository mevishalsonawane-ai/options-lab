"""All strategies by name. Add a module to MODULES (or call register()) to make its STRATEGIES runnable."""
from __future__ import annotations

import importlib

MODULES = ["liquidity", "solo", "orb", "straddle", "hero", "bigbar", "random_entry", "ga_opening", "ga_timeofday", "ga_levels",
           "gb_trend", "gb_meanrev"]
_EXTRA = {}


def register(st):
    _EXTRA[st.name] = st


def all_strategies():
    out = {}
    for m in MODULES:
        mod = importlib.import_module(f"obuy.strategies.{m}")
        for st in mod.STRATEGIES:
            out[st.name] = st
    out.update(_EXTRA)
    return out
