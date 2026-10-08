"""Shared paths, loaders and the Zerodha MCX cost model for NN-CRUDE."""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import glob, os
import numpy as np, pandas as pd

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/nn_crude"
D, MODELS, LOGS = f"{SCR}/data", f"{SCR}/models", f"{SCR}/logs"
HOLDOUT_START = pd.Timestamp("2026-04-01")   # pre-registered (PREREG.md); touched once by evaluate.py --holdout
LOT = {"CRUDEOIL": 100, "CRUDEOILM": 10}    # barrels per lot (MCX contract spec)
TICK_FUT = 1.0                               # Rs/bbl
TICK_OPT = {"CRUDEOIL": 0.10, "CRUDEOILM": 0.05}


def yahoo(tf, name):
    df = pd.read_parquet(f"{D}/yahoo_{tf}_{name}.parquet")
    return df


def mcx_daily(sym="CRUDEOIL"):
    return pd.read_parquet(f"{D}/daily_{sym}_code0.parquet")


def roll_parts(sym="CRUDEOIL", code=1):
    fs = sorted(glob.glob(f"{D}/rollparts/{sym}_c{code}_*.parquet"))
    return pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True) if fs else pd.DataFrame()


def zerodha_costs(kind, buy_px, sell_px, qty):
    """Zerodha MCX charges for ONE round trip (buy then sell), Rs. Rates verified on zerodha.com/charges 2026-10-08:
    futures: brokerage min(0.03%, Rs 20)/order, CTT 0.01% sell, exch 0.0021%, SEBI Rs10/cr, stamp 0.002% buy, GST 18%.
    options: brokerage Rs 20/order, CTT 0.05% sell premium, exch 0.0418% premium, SEBI Rs10/cr, stamp 0.003% buy."""
    tb, ts = buy_px * qty, sell_px * qty
    if kind == "fut":
        brok = min(0.0003 * tb, 20) + min(0.0003 * ts, 20)
        ctt, exch, stamp = 0.0001 * ts, 0.000021 * (tb + ts), 0.00002 * tb
    else:
        brok = 40.0
        ctt, exch, stamp = 0.0005 * ts, 0.000418 * (tb + ts), 0.00003 * tb
    sebi = 10e-7 * (tb + ts)
    gst = 0.18 * (brok + exch + sebi)
    return brok + ctt + exch + sebi + stamp + gst
