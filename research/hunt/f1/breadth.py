"""F1 breadth features (PREREG amendment 2) from the lake's equity_1m: today's 58 NIFTY 50 / NIFTY BANK stocks, 1-minute.

    python research/hunt/f1/breadth.py build      # -> <OUT>/breadth.parquet (one row per minute, 2021-08 on)

Per minute t, over the stocks with a bar at t (the bar's close is known at t + 1, like the cube's rows):
  br_adv_share        share of stocks above their previous session's last close
  br_above_vwap       share above their own session VWAP (typical price x volume, from 09:15)
  br_adv_share_chg15  br_adv_share now minus 15 minutes earlier (same session)
  br_bank_adv_share   br_adv_share over the bank stocks only
Caveat: today's constituents, not the index's members on each date (survivorship bias).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import f1cfg as K  # noqa: E402

BANKS = {"AUBANK", "AXISBANK", "BANKBARODA", "CANBK", "FEDERALBNK", "HDFCBANK", "ICICIBANK", "IDFCFIRSTB",
         "INDUSINDBK", "KOTAKBANK", "PNB", "SBIN", "UNIONBANK", "YESBANK", "BANDHANBNK"}
COLS = ["br_adv_share", "br_above_vwap", "br_adv_share_chg15", "br_bank_adv_share"]
SRC = K.CACHE.lake / "equity_1m"


def compute(e: pd.DataFrame) -> pd.DataFrame:
    """e: symbol, open, high, low, close, volume, ts (naive IST). Returns ts + COLS, one row per minute with stocks."""
    e = e.sort_values(["symbol", "ts"]).copy()
    e["d"] = e.ts.dt.normalize()
    last = e.groupby(["symbol", "d"]).close.last().rename("pc").reset_index()
    last["pc"] = last.groupby("symbol").pc.shift(1)                    # previous session's last close
    e = e.merge(last, on=["symbol", "d"], how="left")
    tp = (e.high + e.low + e.close) / 3
    v = e.volume.fillna(0).clip(lower=0)
    e["cv"] = v.groupby([e.symbol, e.d]).cumsum()
    e["cpv"] = (tp * v).groupby([e.symbol, e.d]).cumsum()
    vwap = np.where(e.cv > 0, e.cpv / e.cv.where(e.cv > 0), np.nan)
    e["adv"] = np.where(e.pc.notna(), (e.close > e.pc).astype(float), np.nan)
    e["abv"] = np.where(np.isfinite(vwap), (e.close > vwap).astype(float), np.nan)
    e["bank"] = e.symbol.astype(str).isin(BANKS)
    m = e.groupby("ts").agg(br_adv_share=("adv", "mean"), br_above_vwap=("abv", "mean"))
    m["br_bank_adv_share"] = e[e.bank].groupby("ts").adv.mean()
    m = m.sort_index()
    day = m.index.normalize()
    prev = m.br_adv_share.groupby(day).shift(15)
    # shift(15) counts rows; require the row 15 rows back to be exactly 15 minutes earlier
    ts15 = pd.Series(m.index, index=m.index).groupby(day).shift(15)
    ok = (m.index - ts15) == pd.Timedelta(minutes=15)
    m["br_adv_share_chg15"] = np.where(ok, m.br_adv_share - prev, np.nan)
    return m[COLS].reset_index()


def build() -> Path:
    parts = []
    for p in sorted(SRC.glob("equity_1m_*.parquet")):
        if int(p.stem[-4:]) < 2021:
            continue
        e = pd.read_parquet(p)
        e["ts"] = pd.to_datetime(e.ts).dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        e["symbol"] = e.symbol.astype(str)
        parts.append(e)
    E = pd.concat(parts, ignore_index=True)
    B = compute(E)
    q = K.OUT / "breadth.parquet"
    q.parent.mkdir(parents=True, exist_ok=True)
    B.astype({c: np.float32 for c in COLS}).to_parquet(q, index=False)
    print(f"breadth: {B.ts.dt.date.nunique()} sessions -> {q}")
    return q


def at_rows(B: pd.DataFrame) -> pd.DataFrame:
    """Breadth keyed by (day, row) for joining to the decision rows."""
    mn = (B.ts - B.ts.dt.normalize()).dt.total_seconds() // 60 - 555
    return pd.DataFrame(dict(day=B.ts.dt.normalize(), row=mn.astype(int), **{c: B[c].values for c in COLS}))


if __name__ == "__main__":
    build()
