"""R146 minute data (PREREG 1, M): the research-data release -> per-index day stores.

    python -I -m research.hunt.r146_gap_btst.mdata     # writes <OUT>/m_<UND>.pkl, m_index.pkl

Store per traded index (NIFTY, BANKNIFTY): {date: Day}, where
    idx    (4, 375) index O H L C on the 09:15 grid (NaN where no bar; nothing is filled)
    opt    {(side 'C'/'P', strike): (6, 375) O H L C V OI} of the day's one (nearest) expiry
    expiry that expiry (Jan 2025's 29 -> 30 move applied)
Index-only store: {und: {date: (4, 375)}} for FINNIFTY, SENSEX, INDIAVIX (and NIFTY from extra_series, which starts
two months before NIFTY's option file).
The release files are read-only data. Nothing from them goes into git.
"""
from __future__ import annotations

import pickle
from dataclasses import dataclass, field
from datetime import date

import numpy as np
import pandas as pd

from . import cfg as K


@dataclass
class Day:
    d: date
    idx: np.ndarray
    expiry: date | None = None
    opt: dict = field(default_factory=dict)


def rows_of(df: pd.DataFrame) -> pd.DataFrame:
    """Adds d (date) and r (grid row) from naive-IST ts; keeps 09:15-15:29 only."""
    ts = pd.to_datetime(df["ts"])
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    out = df.assign(d=ts.dt.date, r=(ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)).astype(np.int64))
    return out[(out.r >= 0) & (out.r < K.N)]


def index_days(df: pd.DataFrame) -> dict:
    """{date: (4, 375)} from index rows. A duplicated minute keeps its first row; non-positive prices dropped."""
    x = rows_of(df)
    x = x[(x[["open", "high", "low", "close"]] > 0).all(axis=1)].drop_duplicates(["d", "r"], keep="first")
    out = {}
    for d, g in x.groupby("d", sort=True):
        a = np.full((4, K.N), np.nan)
        for i, c in enumerate(("open", "high", "low", "close")):
            a[i, g.r.values] = g[c].values
        out[d] = a
    return out


def option_days(df: pd.DataFrame) -> dict:
    """{date: (expiry, {(side, strike): (6, 375)})} from CE / PE rows."""
    x = rows_of(df[df["right"].isin(["CE", "PE"])])
    x = x.drop_duplicates(["d", "right", "strike", "r"], keep="first")
    out: dict = {}
    for (d, rt, k), g in x.groupby(["d", "right", "strike"], sort=True):
        a = np.full((6, K.N), np.nan, dtype=np.float32)
        for i, c in enumerate(("open", "high", "low", "close", "volume", "open_interest")):
            a[i, g.r.values] = g[c].values
        e = pd.Timestamp(g["expiry"].iloc[0]).date()
        e = K.EXPIRY_MOVED.get(e, e)
        rec = out.setdefault(d, [e, {}])
        rec[1][(rt[0], float(k))] = a
    return {d: (e, o) for d, (e, o) in out.items()}


def expiry_dates(df: pd.DataFrame) -> set:
    e = pd.to_datetime(df.loc[df["right"].isin(["CE", "PE"]), "expiry"].dropna().unique())
    return {K.EXPIRY_MOVED.get(x.date(), x.date()) for x in e}


def build() -> None:
    K.OUT.mkdir(parents=True, exist_ok=True)
    expiries = {}
    for und, files in K.RELEASE_FILES.items():
        df = pd.concat([pd.read_parquet(K.RELEASE / f) for f in files], ignore_index=True)
        ix = index_days(df[df["right"] == "IX"])
        od = option_days(df)
        expiries[und] = sorted(expiry_dates(df))
        days = {d: Day(d, ix[d], *(od[d] if d in od else (None, {}))) for d in sorted(ix)}
        (K.OUT / f"m_{und}.pkl").write_bytes(pickle.dumps(days))
        print(und, len(days), "days", min(days), "..", max(days), flush=True)
        del df
    ixs = {}
    for und, f in K.INDEX_ONLY.items():
        df = pd.read_parquet(K.RELEASE / f, columns=["ts", "open", "high", "low", "close", "right"])
        ixs[und] = index_days(df[df["right"] == "IX"])
    ex = pd.read_parquet(K.RELEASE / "extra_series.parquet")
    for s in ("INDIAVIX", "NIFTY"):
        ixs[s if s != "NIFTY" else "NIFTY_X"] = index_days(ex[ex["series"] == s])
    ixs["EXPIRIES"] = expiries
    (K.OUT / "m_index.pkl").write_bytes(pickle.dumps(ixs))
    print("index-only:", {k: len(v) for k, v in ixs.items()})


def load(und: str) -> dict:
    return pickle.loads((K.OUT / f"m_{und}.pkl").read_bytes())


def load_index() -> dict:
    return pickle.loads((K.OUT / "m_index.pkl").read_bytes())


if __name__ == "__main__":
    # pickle Day under the package name, not __main__
    from research.hunt.r146_gap_btst import mdata as _pkg
    _pkg.build()
