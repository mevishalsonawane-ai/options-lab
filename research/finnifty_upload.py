"""Load the owner's FINNIFTY option upload (finnifty_data/<expiry>/<strike><CE|PE>_<expiry>.csv, 1-minute bars from
cloudtraderpro) into the day layout liquidity_break.simulate uses: the index from finnifty_index.parquet where it
exists, and for days after it ends a synthetic index from put-call parity (K + C - P at the strike where C and P are
closest, less the carry measured on the days that have both).
"""
from __future__ import annotations

import glob
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from ml_long import grid  # noqa: E402

COLS = ["open", "high", "low", "close"]


def read_upload(folder):
    parts = []
    for f in glob.glob(os.path.join(folder, "*", "*.csv")):
        s = pd.read_csv(f, parse_dates=["timestamp"])
        # a few files lack the expiry column: it is the folder's name
        s["contract_expiry"] = pd.Timestamp(os.path.basename(os.path.dirname(f)))
        parts.append(s[["timestamp", "open", "high", "low", "close", "strike", "option_type", "contract_expiry"]])
    df = pd.concat(parts, ignore_index=True)
    df["day"] = df.timestamp.dt.date
    df["expiry"] = df.contract_expiry.dt.date
    return df


def synthetic(o):
    """Per-minute K + C - P at the strike with the smallest |C - P| (closes), as a Series indexed by timestamp."""
    c = o[o.option_type == "CE"].set_index(["timestamp", "strike"]).close
    p = o[o.option_type == "PE"].set_index(["timestamp", "strike"]).close
    j = pd.concat([c.rename("c"), p.rename("p")], axis=1).dropna().reset_index()
    j["gap"] = (j.c - j.p).abs()
    best = j.loc[j.groupby("timestamp").gap.idxmin()]
    return pd.Series((best.strike + best.c - best.p).values, index=best.timestamp.values).sort_index()


def load_days(index_path, folder):
    ix = pd.read_parquet(index_path)
    ix["ts"] = pd.to_datetime(ix.ts)
    up = read_upload(folder)
    days, real = [], {}
    for day, g in ix.groupby(ix.ts.dt.date):
        g = g.sort_values("ts").set_index("ts")
        if len(g) >= 300:
            real[day] = g
    # carry: synthetic minus real on days that have both, by calendar days to expiry
    carry = []
    syn = {}
    for day, g in up.groupby("day"):
        exps = sorted(e for e in g.expiry.unique() if e > day)
        if not exps:
            continue
        o = g[g.expiry == exps[0]]
        s = synthetic(o)
        if len(s) < 300:
            continue
        syn[day] = (exps[0], s)
        if day in real:
            r = real[day].close.reindex(s.index).dropna()
            carry.append(((exps[0] - day).days, float(np.median(s.loc[r.index] - r)), float(r.iloc[0])))
    cr = pd.DataFrame(carry, columns=["dte", "diff", "spot"])
    rate = float(np.median(cr["diff"] / (cr.spot * cr.dte.clip(lower=1) / 365))) if len(cr) else 0.0
    chain_days = {}
    for day, (exp, _) in syn.items():
        o = up[(up.day == day) & (up.expiry == exp)]
        chain = {}
        for (k, r), s in o.groupby(["strike", "option_type"]):
            if len(s) > 150:
                chain[(float(k), r)] = grid(s.sort_values("timestamp").set_index("timestamp"), COLS)
        chain_days[day] = (exp, chain)
    all_days = sorted(set(real) | set(syn))
    for day in all_days:
        if day in real:
            I = grid(real[day], COLS)
            src = "index"
        else:
            exp, s = syn[day]
            adj = s - s.iloc[0] * rate * max((exp - day).days, 1) / 365
            f = pd.DataFrame({"open": adj, "high": adj, "low": adj, "close": adj})
            I = grid(f, COLS)
            src = "synthetic"
        exp, chain = chain_days.get(day, (None, {}))
        days.append(dict(day=day, exp=exp, I=I, chain=chain, src=src))
    return days, cr, rate
