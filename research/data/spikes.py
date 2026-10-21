#!/usr/bin/env python3
"""Bad-tick check for option files: one-minute close spikes that revert, and WEEK/MONTH files that are the same contract.

  python -I spikes.py FILE.parquet [FILE ...]     # print the spike metric of each option file as JSON

Spike definition (used by validate.py and make_catalog.py; README.md and VALIDATION.md describe the same thing):
* Unit: a contract-minute. Rows are de-duplicated on (ts, strike, side), because one contract can sit on two ATM
  offsets in the same minute. Rows whose close is missing, zero or negative are left out (not counted at all).
* Neighbours: the previous and the next bar of the same contract (strike + side) on the same IST date, in time order.
  Minutes with no trade in between are skipped, so a neighbour may be several minutes away; bars are never paired
  across days or across strikes (an ATM offset changing strike is not a price move).
* A minute with close c, previous close p and next close n is a spike when
      c > 1.5 * max(p, n)   or   c < min(p, n) / 1.5        (c is more than 50% away from BOTH neighbours, same side)
  and  max(p, n) <= 1.5 * min(p, n)                          (it reverts: n is back within 50% of p).
  So a sustained jump (p=100, c=200, n=200) is not a spike; nor is a two-minute spike (100, 200, 200, 100),
  where each high minute has one high neighbour. The first and last bar of a contract-day can never be a spike but
  count in the denominator.
* share = spikes / contract-minutes, overall and split by time of day: "early" is the first part of the session up to
  10:45 IST (NSE/BSE 09:15-10:44, the 09:00-10:45 window; MCX 09:00-10:44, the session's first 105 minutes), "late"
  is every minute from 10:45 on. Each part has its own denominator.
"""
import json, os, re, sys
import numpy as np, pandas as pd

JUMP = 1.5  # "more than 50%": the larger price is more than 1.5x the smaller one
MCX = {"CRUDEOIL", "NATURALGAS", "GOLD", "GOLDM", "SILVER", "SILVERM", "CRUDEOILM", "NATGASMINI", "COPPER", "ZINC"}  # 4 added 19 Oct 2026
SESSION_OPEN = {"NSE": 9 * 60 + 15, "MCX": 9 * 60}
EARLY_MINUTES = {"NSE": 90, "MCX": 105}  # both end at 10:45 IST


def book(fname):
    """('GOLD', 'MONTH', 2026) for opt_GOLD_MONTH_2026.parquet, ('INFY', 'MONTH', None) for stkopt_INFY_MONTH.parquet."""
    b = os.path.basename(fname)
    m = re.match(r"opt_([A-Z0-9&-]+)_(WEEK|MONTH)_(\d{4})\.parquet$", b)
    if m:
        return m.group(1), m.group(2), int(m.group(3))
    m = re.match(r"stkopt_(.+)_(MONTH)\.parquet$", b)
    return (m.group(1), m.group(2), None) if m else None


def exchange(underlying):
    return "MCX" if underlying in MCX else "NSE"


def spike_flags(df):
    """df with ts, strike, side, close. Returns (contract-minute frame, boolean spike Series aligned with it)."""
    x = df[["ts", "strike", "side", "close"]]
    x = x[x.close.notna() & (x.close > 0)].drop_duplicates(["ts", "strike", "side"])
    x = x.assign(_d=x.ts.dt.normalize())  # IST date (ts is tz-aware IST in the lake)
    x = x.sort_values(["_d", "strike", "side", "ts"], kind="stable")
    g = x.groupby(["_d", "strike", "side"], observed=True, sort=False).close
    p, n, c = g.shift(1), g.shift(-1), x.close
    hi, lo = np.maximum(p, n), np.minimum(p, n)  # NaN at a contract-day's first/last bar -> comparisons False
    spike = ((c > JUMP * hi) | (c * JUMP < lo)) & (hi <= JUMP * lo)
    return x, spike


def spike_metric(df, underlying):
    """Spike counts and shares for one option file's rows (see module docstring)."""
    ex = exchange(underlying)
    o, e = SESSION_OPEN[ex], SESSION_OPEN[ex] + EARLY_MINUTES[ex]
    out = {"early_window": f"{o // 60:02d}:{o % 60:02d}-{(e - 1) // 60:02d}:{(e - 1) % 60:02d}"}
    x, s = spike_flags(df)
    early = (x.ts.dt.hour * 60 + x.ts.dt.minute) < e
    for k, m in (("", slice(None)), ("early_", early), ("late_", ~early)):
        n, k_ = int(len(s[m])), int(s[m].sum())
        out[f"{k}minutes"], out[f"{k}spikes"] = n, k_
        out[f"{k}share"] = round(k_ / n, 8) if n else None
    return out


def file_spikes(path):
    b = book(path)
    if b is None:
        raise ValueError(f"not an option file: {path}")
    return spike_metric(pd.read_parquet(path, columns=["ts", "strike", "side", "close"]), b[0])


def same_contract_reason(underlying, year):
    if underlying == "MIDCPNIFTY" and year == 2022:
        return ("MIDCPNIFTY had only monthly options in 2022, so Dhan's WEEK series is the monthly contract. "
                "Studies must never count both files.")
    if year is not None and year >= 2024:
        return ("Since 20 Nov 2024 NSE allows weekly expiries only on NIFTY (BSE: SENSEX), so the nearest weekly "
                f"expiry of {underlying} is the monthly one and the WEEK and MONTH files hold the same contracts. "
                "Studies must never count both files.")
    return "Byte-identical to the paired file (same contracts). Studies must never count both files."


def same_contract_pairs(entries):
    """entries: CATALOG.json file entries. {file: (partner, reason)} for opt_*_WEEK/MONTH files with equal sha256."""
    by = {}
    for e in entries:
        b = book(e["file"])
        if b and e["file"].startswith("opt_") and e.get("sha256"):
            by.setdefault((b[0], b[2], e["sha256"]), {})[b[1]] = e["file"]
    out = {}
    for (und, year, _), f in by.items():
        if "WEEK" in f and "MONTH" in f:
            r = same_contract_reason(und, year)
            out[f["WEEK"]], out[f["MONTH"]] = (f["MONTH"], r), (f["WEEK"], r)
    return out


if __name__ == "__main__":
    for f in sys.argv[1:]:
        print(json.dumps({"file": os.path.basename(f), **file_spikes(f)}))
