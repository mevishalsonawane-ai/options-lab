"""h32 shared: load rows, event definitions, feature list."""
from __future__ import annotations

import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h32")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
EPOCH = date(1970, 1, 1)
HOLD = (date(2025, 10, 1) - EPOCH).days
DISC_END = (date(2024, 1, 1) - EPOCH).days      # discovery (event study) = first day .. 2023-12-31
TYPN = {0: "CE ATM", 1: "CE ITM1", 2: "PE ATM", 3: "PE ITM1"}

FEATS = ["s", "dte", "exp", "rexp5", "rexp15", "box30", "drng", "vix", "vix15", "strad", "strad15",
         "a_ir5", "a_ir15", "a_ir30", "a_ir60", "a_igap", "a_iday", "a_pcrch", "a_dfav", "a_dadv",
         "E", "prem_pct", "o5", "o15", "o_pos", "o_off_lo", "o_vsurge", "o_vshare", "o_oi15", "x_oi15", "x_o15",
         "chainv5"]
FDESC = {
    "s": "clock (minute of day)", "dte": "days to expiry", "exp": "expiry day", "rexp5": "index speed: last-5-min range vs normal",
    "rexp15": "index speed: last-15-min range vs normal", "box30": "last-30-min index box height vs normal (coil)",
    "drng": "day's index range so far vs normal", "vix": "India VIX (prev close)", "vix15": "VIX change 15 min (%)",
    "strad": "ATM straddle price (bps of spot)", "strad15": "ATM straddle change 15 min (%)",
    "a_ir5": "index move 5 min, in the option's favour", "a_ir15": "index move 15 min, in favour",
    "a_ir30": "index move 30 min, in favour", "a_ir60": "index move 60 min, in favour", "a_igap": "opening gap, in favour",
    "a_iday": "index move since open, in favour", "a_pcrch": "put-minus-call OI added 15 min near ATM, in favour",
    "a_dfav": "room to day extreme in favour (bps)", "a_dadv": "distance from day extreme against (bps)",
    "E": "option premium (Rs)", "prem_pct": "premium as bps of spot", "o5": "option premium change 5 min (%)",
    "o15": "option premium change 15 min (%)", "o_pos": "option price position in its day range",
    "o_off_lo": "option % above its day low", "o_vsurge": "option volume last 5 min vs prior 30 min",
    "o_vshare": "my side's share of ATM-pair volume last 5 min", "o_oi15": "option's own OI change 15 min (%)",
    "x_oi15": "opposite option's OI change 15 min (%)", "x_o15": "opposite option's premium change 15 min (%)",
    "chainv5": "chain volume near ATM last 5 min"}


def load(unds=UNDS, cols=None):
    parts = []
    for u in unds:
        p = os.path.join(OUT, f"rows_{u}.parquet")
        if os.path.exists(p):
            parts.append(pd.read_parquet(p, columns=cols))
    df = pd.concat(parts, ignore_index=True)
    for c in df.columns:
        if df[c].dtype == np.float32:
            v = df[c].values.copy()
            v[~np.isfinite(v)] = np.nan
            df[c] = v
    return df


def event(df, up=20, win=15, dn=15):
    """premium +up points (high) within `win` minutes of the entry bar, before the low touched entry - dn
    (same-minute touches count as the stop: conservative)."""
    u = df[f"up{up}"].values.astype(np.int16)
    d = df[f"dn{dn}"].values.astype(np.int16)
    return (u < win) & (u < d)


def crash(df, up=20, win=15, dn=15):
    u = df[f"up{up}"].values.astype(np.int16)
    d = df[f"dn{dn}"].values.astype(np.int16)
    return (d < win) & (d <= u)
