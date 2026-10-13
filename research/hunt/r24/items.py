"""R24 items: point-in-time features at the decision minute, aligned to the option's side and cut into bins (PREREG.md
Step 2 table). Research only, 13 Oct 2026.

Everything here reads the brain's FEATURE store only (research/brain/features, look-ahead guarded), at the decision
minute t (the entry fills at t + 1 .. t + 3). No what-if outcome and no y_ label is ever read here: tests check it.

A minute frame has one row per (index, session, entry minute, side); `codes()` turns it into one int8 code per item
(-1: missing), with cut points fitted on design rows only (`fit_cuts`).
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

import r24cfg as K

FEATURES = ["ret_day", "ret_15m", "or15_state", "dist_tpavg_pct", "pos_in_day_range", "lvl_above_dist_pct",
            "lvl_below_dist_pct", "lvl_near_count", "atm_iv", "atm_iv_chg_15m", "iv_rank_252", "oi_build_ce_pct",
            "oi_build_pe_pct", "pcr_oi", "vix", "vix_chg_day_pct", "gap_pct", "rv_30m", "adx14", "day_range_pct",
            "em_vix_1d_pct", "straddle_vs_0920", "dte_days"]


@dataclass(frozen=True)
class Item:
    name: str
    label: str                 # plain English
    kind: str                  # "t" terciles (fitted), "fixed" (edges), "code" (already a small integer code)
    bins: tuple                # names of the bins, low to high
    edges: tuple = ()          # "fixed": right-open edges between bins


T3 = ("low", "mid", "high")
ITEMS = [
    Item("trend", "trend since the open, with the option", "t", ("against", "flat", "with")),
    Item("mom15", "last 15 minutes, with the option", "t", ("against", "flat", "with")),
    Item("orb15", "15-min opening range, with the option", "code", ("broken against", "inside", "broken with")),
    Item("vwapx", "distance from the day's average price, with the option", "t", ("behind", "near", "ahead")),
    Item("pos", "place in the day's range, with the option", "t", ("bottom", "middle", "top")),
    Item("room", "room to the next level ahead", "t", ("little", "some", "lots")),
    Item("behind", "distance to the level behind", "t", ("close", "some", "far")),
    Item("near", "levels within 0.2%", "fixed", ("none", "one", "two+"), (0.5, 1.5)),
    Item("iv", "ATM IV", "t", T3),
    Item("ivchg", "ATM IV change, 15 min", "t", ("falling", "flat", "rising")),
    Item("ivrank", "IV rank (252 sessions)", "t", T3),
    Item("oi_own", "own-side OI build-up today", "t", T3),
    Item("oi_other", "other-side OI build-up today", "t", T3),
    Item("pcr", "put-call OI ratio", "t", T3),
    Item("vix", "VIX", "t", T3),
    Item("vixchg", "VIX vs yesterday", "t", ("down", "flat", "up")),
    Item("gap", "opening gap, with the option", "t", ("against", "small", "with")),
    Item("rv", "realised vol, 30 min", "t", T3),
    Item("adx", "trend strength (ADX 14)", "t", T3),
    Item("used", "day's range so far / VIX day move", "t", T3),
    Item("strad", "ATM straddle since 09:20", "t", ("shrunk", "flat", "grown")),
    Item("tod", "time of day (entry)", "fixed", ("09:20-09:59", "10:00-11:29", "11:30-12:59", "13:00-14:45"),
         (45, 135, 225)),
    Item("dte", "days to expiry", "fixed", ("expiry day", "1-2", "3-6", "7+"), (0.5, 2.5, 6.5)),
    Item("side", "option", "code", ("PE", "CE")),
    Item("und", "index", "code", ("NIFTY", "BANKNIFTY")),
    Item("mnyb", "moneyness", "code", ("ITM3-2", "ITM1", "ATM", "OTM1", "OTM2-3")),
]
BY = {it.name: it for it in ITEMS}
NAMES = [it.name for it in ITEMS]
ALIGNED = {"trend", "mom15", "orb15", "vwapx", "pos", "room", "behind", "oi_own", "oi_other", "gap"}
OPTION_ITEMS = ("side", "und", "mnyb")
SIDE_FIXING = ALIGNED | {"side"}


def raw_values(F: pd.DataFrame, side: np.ndarray) -> dict:
    """Continuous (or coded) value per item for rows of F (features at the decision minute) and side (+1 / -1)."""
    s = side.astype(float)
    call = side > 0
    with np.errstate(invalid="ignore", divide="ignore"):
        return {
            "trend": F.ret_day.values * s,
            "mom15": F.ret_15m.values * s,
            "orb15": F.or15_state.values * s,
            "vwapx": F.dist_tpavg_pct.values * s,
            "pos": np.where(call, F.pos_in_day_range.values, 1 - F.pos_in_day_range.values),
            "room": np.where(call, F.lvl_above_dist_pct.values, F.lvl_below_dist_pct.values),
            "behind": np.where(call, F.lvl_below_dist_pct.values, F.lvl_above_dist_pct.values),
            "near": F.lvl_near_count.values,
            "iv": F.atm_iv.values,
            "ivchg": F.atm_iv_chg_15m.values,
            "ivrank": F.iv_rank_252.values,
            "oi_own": np.where(call, F.oi_build_ce_pct.values, F.oi_build_pe_pct.values),
            "oi_other": np.where(call, F.oi_build_pe_pct.values, F.oi_build_ce_pct.values),
            "pcr": F.pcr_oi.values,
            "vix": F.vix.values,
            "vixchg": F.vix_chg_day_pct.values,
            "gap": F.gap_pct.values * s,
            "rv": F.rv_30m.values,
            "adx": F.adx14.values,
            "used": F.day_range_pct.values / F.em_vix_1d_pct.values,
            "strad": F.straddle_vs_0920.values,
            "dte": F.dte_days.values,
        }


def fit_cuts(vals: dict, und: np.ndarray, mask: np.ndarray) -> dict:
    """Tercile cut points per index for the 't' items, from the rows in mask (design rows only)."""
    cuts = {}
    for it in ITEMS:
        if it.kind != "t":
            continue
        for u in K.UNDS:
            v = vals[it.name][mask & (und == u)]
            v = v[np.isfinite(v)]
            cuts[(it.name, u)] = tuple(np.quantile(v, [1 / 3, 2 / 3])) if len(v) >= 30 else (np.nan, np.nan)
    return cuts


def codes(vals: dict, side: np.ndarray, und: np.ndarray, entry_row: np.ndarray, cuts: dict,
          mny: np.ndarray | None = None) -> pd.DataFrame:
    """int8 code per item (-1 missing). mny (ITM steps) only for option rows; without it mnyb is -1."""
    out = {}
    n = len(side)
    for it in ITEMS:
        c = np.full(n, -1, dtype=np.int8)
        if it.kind == "t":
            v = vals[it.name]
            for u in K.UNDS:
                m = (und == u) & np.isfinite(v)
                lo, hi = cuts[(it.name, u)]
                if not np.isfinite(lo):
                    continue
                c[m] = np.where(v[m] <= lo, 0, np.where(v[m] <= hi, 1, 2))
        elif it.name == "orb15":
            v = vals["orb15"]
            m = np.isfinite(v)
            c[m] = (np.round(v[m]) + 1).astype(np.int8)
        elif it.name == "near":
            v = vals["near"]
            m = np.isfinite(v)
            c[m] = np.digitize(v[m], it.edges)
        elif it.name == "tod":
            c[:] = np.digitize(entry_row, it.edges)
        elif it.name == "dte":
            v = vals["dte"]
            m = np.isfinite(v)
            c[m] = np.digitize(v[m], it.edges)
        elif it.name == "side":
            c[:] = (side > 0).astype(np.int8)
        elif it.name == "und":
            c[:] = (und == "BANKNIFTY").astype(np.int8)
        elif it.name == "mnyb":
            if mny is not None:
                c[:] = np.select([mny >= 2, mny == 1, mny == 0, mny == -1], [0, 1, 2, 3], 4)
        out[it.name] = c
    return pd.DataFrame(out)


MNYB_TO_MNY = {0: 2, 1: 1, 2: 0, 3: -1, 4: -2}      # a rule buys the bucket's nearer-the-money strike


def describe(pattern: tuple) -> str:
    """((item, code), ...) -> plain words."""
    return " AND ".join(f"{BY[n].label} = {BY[n].bins[c]}" for n, c in pattern)
