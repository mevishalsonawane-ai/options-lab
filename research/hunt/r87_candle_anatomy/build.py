"""Builds the candle tables: one Parquet per instrument and timeframe under cfg.OUT (never committed).

    python -m research.hunt.r87_candle_anatomy.build            # all of cfg.UNDS x cfg.TFS
"""
from __future__ import annotations

import sys
from datetime import date

import numpy as np
import pandas as pd

from . import candles as CA
from . import cfg
from . import context as CX
from . import data as D
from . import outcome as OC


def minutes(und: str) -> pd.DataFrame:
    """Index minutes joined with the chain summary (activity, OI) and the gamma proxy; missing chain = NaN."""
    m = D.index_minutes(und)
    o = D.option_minutes(und)
    if len(o):
        ch = D.chain_minute(o)
        gx = D.gex_sign_proxy(o)
        m = m.merge(ch, on="ts", how="left").merge(gx[["ts", "gex_ratio"]], on="ts", how="left")
    return m


def candle_table(m: pd.DataFrame, und: str, tf: int, expiries: set) -> pd.DataFrame:
    """Everything for one instrument and timeframe from its minute_context() table (pure; used by the tests)."""
    k = CA.resample(m[[x for x in ("ts", "o", "h", "l", "c", "act") if x in m]], tf)
    k = CA.anatomy(k)
    k = CX.candle_context(k, m, expiries)
    k = CX.orderflow_proxies(k, m)
    sh = CA.shapes(k)
    pt = CA.patterns(k)
    cd = pd.DataFrame(CX.conditions(k), index=k.index).add_prefix("c_")
    fp = pd.DataFrame(CX.footprints(k), index=k.index)
    k = pd.concat([k, sh, pt, fp, cd], axis=1)
    k["cell"] = CA.grid_cell(k)
    k = pd.concat([k, OC.outcomes(k)], axis=1)
    k["und"], k["tf"] = und, tf
    return k


def build(und: str, tfs=cfg.TFS) -> None:
    m = minutes(und)
    m = CX.minute_context(m, und)
    ex = CX.expiry_set(und, cfg.START, cfg.END)
    for tf in tfs:
        k = candle_table(m, und, tf, ex)
        p = cfg.OUT / f"candles_{und}_{tf}m.parquet"
        k.to_parquet(p, compression="zstd")
        print(und, tf, len(k), "candles", k.day.min().date(), "..", k.day.max().date(),
              "act known", f"{np.isfinite(k.act.to_numpy(float)).mean():.0%}" if "act" in k else "-", flush=True)


def load(und: str, tf: int, frm: date | None = None, to: date | None = None) -> pd.DataFrame:
    k = pd.read_parquet(cfg.OUT / f"candles_{und}_{tf}m.parquet")
    d = k.day.dt.date
    if frm:
        k = k[d >= frm]
        d = k.day.dt.date
    if to:
        k = k[d <= to]
    return k


if __name__ == "__main__":
    for u in (sys.argv[1:] or cfg.UNDS):
        build(u)
