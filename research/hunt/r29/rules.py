"""R29 rules (PREREG.md Part B) and the baseline B0. A rule = a P1-P12 event (optionally restricted to one sign) plus
optional ribbon-state filters read at the event's row, traded follow or fade, with one option choice and one exit.

Filters: (kind, tf, op, value) on the row's last complete tf-minute bar, kind in {"stack", "order", "pos", "width_atr",
"width_chg"} of the RIB ribbon; op "same" means value x sign (e.g. ("stack", 15, "same", 1): the 15-minute ribbon is
fully stacked in the event's direction), ">=" / "<=" compare the raw value, "same>=" compares value x sign.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import r29cfg as K
from research.brain.features import ribbon as RB
from research.brain.features.bars import to_rows
from research.brain.features.registry import Ctx

ALL = K.UNDS

B0 = dict(id="B0", name="Baseline: plain single-EMA cross (EMA 9 x EMA 21, 5-minute bars), follow", kind="b0",
          patterns=("B0",), sign=None, mode="follow", filters=(), unds=ALL, opt="ATM", exit="X1", origin="baseline")

# Part B (PREREG.md, 14 Oct 2026): the 10 best Step-2 survivors by selection twin-adjusted expectancy, mechanically.
# All five indices, ATM, exit X1 (Boss's lock), no filters, as Part A fixes for the primary test.
_TOP10 = [("P3_3", -1, "fade"), ("P3_5", 1, "fade"), ("P5_15", 1, "fade"), ("P2_15", -1, "fade"),
          ("P5_3", 1, "fade"), ("P9_5", -1, "fade"), ("P3_3", 1, "fade"), ("P10_1", -1, "fade"),
          ("P2_5", -1, "fade"), ("P1_5", 1, "fade")]
RULES: list[dict] = [
    dict(id=f"R{i}", name=f"{p} {'bull' if s > 0 else 'bear'} event, {m}", kind="pattern", patterns=(p,), sign=s,
         mode=m, filters=(), unds=ALL, opt="ATM", exit="X1", origin="survivor")
    for i, (p, s, m) in enumerate(_TOP10, 1)
]


def _state(ctx, cache, kind, tf):
    key = (kind, tf)
    if key not in cache:
        b = RB.ribbon_bars(ctx, tf, RB.RIB)
        cache[key] = to_rows(ctx, b, b[kind].values) if len(b) else np.full(ctx.n, np.nan)
    return cache[key]


def rule_events(rule, und, M, E_all, B_all, cache) -> pd.DataFrame:
    src = B_all if rule.get("kind") == "b0" else E_all
    E = src[src.pattern.isin(rule["patterns"])]
    if rule.get("sign") is not None:
        E = E[E.sign == rule["sign"]]
    if rule.get("filters"):
        if "ctx" not in cache:
            cache["ctx"] = Ctx(M, None, und)
        ctx = cache["ctx"]
        keep = np.ones(len(E), bool)
        rows, sg = E.row.values, E.sign.values
        for kind, tf, op, val in rule["filters"]:
            v = _state(ctx, cache, kind, tf)[rows]
            if op == "same":
                keep &= v == val * sg
            elif op == "same>=":
                keep &= v * sg >= val - 1e-9
            elif op == ">=":
                keep &= v >= val
            elif op == "<=":
                keep &= v <= val
            else:
                raise ValueError(op)
        E = E[keep]
    # several patterns firing on the same bar are one signal
    return E.drop_duplicates(["row", "sign"]).sort_values("row").reset_index(drop=True)
