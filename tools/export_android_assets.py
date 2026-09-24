"""Pack the parquet data into the compact `.olx` series format the Android app reads.

    python tools/export_android_assets.py            # writes android/app/src/main/assets
    python tools/export_android_assets.py --no-reference   # skip the slow PC ledgers

The app is standalone: it carries the 170 expiry-session chains and the
harvested bar partitions with it, so every backtest, health check and IC table
runs on the phone with no server. Parquet has no light reader on Android, so the
data is re-encoded once, here, into a format both sides share:

    gzip(
      "OLX1"  u8 flags (bit0: open/high/low present, bit1: volume present)
      varint nDays
      per day:   varint epochDay, varint lotHint+1 (0 = none), varint nSeries
      per series: varint expiryEpochDay+1 (0 = index), varint strike*100,
                  u8 right (0 CE, 1 PE, 2 IX), varint lot, varint nBars
      per series, column by column (nBars values each):
                 varint minute-of-day deltas, zigzag close-paise deltas,
                 [zigzag open-close, high-close, low-close in paise],
                 [varint volume], zigzag open-interest deltas
    )

Every price in the cache is a whole number of paise (verified: 0 of 6,522,207
rows are not), so the encoding is lossless; the reader rebuilds the float32 the
parquet held, which keeps the phone's P&L bit-for-bit comparable to the PC's.

It also writes `reference_trades.csv` - the PC backtest's own ledger - which the
Kotlin engine's tests reproduce session by session.
"""
from __future__ import annotations

import gzip
import io
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from options_lab import lots as lot_table  # noqa: E402
from options_lab.strategy import expiry_put as ep  # noqa: E402

DATA = ROOT / "options_lab" / "data"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
FIXTURES = ROOT / "android" / "engine" / "src" / "test" / "resources"
EPOCH = date(1970, 1, 1)
RIGHTS = {"CE": 0, "PE": 1, "IX": 2}


def _varint(buf: io.BytesIO, v: int) -> None:
    if v < 0:
        raise ValueError(f"varint cannot hold {v}")
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            buf.write(bytes([b | 0x80]))
        else:
            buf.write(bytes([b]))
            return


def _zigzag(buf: io.BytesIO, v: int) -> None:
    _varint(buf, (v << 1) if v >= 0 else ((-v << 1) - 1))


def _paise(x) -> np.ndarray:
    return np.round(np.asarray(x, dtype="float64") * 100).astype("int64")


def _day(d: date) -> int:
    return (d - EPOCH).days


def encode(days: list[tuple[date, int | None, pd.DataFrame]], *, ohl: bool,
           volume: bool = True) -> bytes:
    buf = io.BytesIO()
    buf.write(b"OLX1")
    buf.write(bytes([(1 if ohl else 0) | (2 if volume else 0)]))
    _varint(buf, len(days))
    for day, lot_hint, frame in days:
        _varint(buf, _day(day))
        _varint(buf, 0 if lot_hint is None else lot_hint + 1)
        ts = pd.to_datetime(frame["ts"]).dt.tz_convert("Asia/Kolkata")
        frame = frame.assign(_min=(ts.dt.hour * 60 + ts.dt.minute).astype(int))
        if "expiry" not in frame:
            frame = frame.assign(expiry=day)
        if "lot_size" not in frame:
            frame = frame.assign(lot_size=0)
        groups = list(frame.groupby(["expiry", "strike", "right"], sort=True))
        _varint(buf, len(groups))
        for (expiry, strike, right), g in groups:
            g = g.sort_values("_min")
            is_ix = right == "IX"
            _varint(buf, 0 if is_ix else _day(pd.Timestamp(expiry).date()) + 1)
            _varint(buf, int(round(float(strike) * 100)))
            buf.write(bytes([RIGHTS[right]]))
            _varint(buf, int(g["lot_size"].iloc[0]) if pd.notna(g["lot_size"].iloc[0]) else 0)
            _varint(buf, len(g))
            # Column by column, so each run of similar small deltas sits
            # together; gzip does markedly better on that than on rows.
            close = _paise(g["close"])
            minutes = g["_min"].to_numpy().astype("int64")
            oi = g["open_interest"].astype("int64").to_numpy()
            prev = 0
            for m in minutes:
                _varint(buf, int(m) - prev)
                prev = int(m)
            prev = 0
            for c in close:
                _zigzag(buf, int(c) - prev)
                prev = int(c)
            if ohl:
                for col in ("open", "high", "low"):
                    for x, c in zip(_paise(g[col]), close):
                        _zigzag(buf, int(x) - int(c))
            if volume:
                for v in g["volume"].astype("int64").to_numpy():
                    _varint(buf, int(v))
            prev = 0
            for x in oi:
                _zigzag(buf, int(x) - prev)
                prev = int(x)
    return gzip.compress(buf.getvalue(), 9)


def export_expiry_cache() -> None:
    days = []
    for f in sorted((DATA / "expiry_cache").glob("*_chain.parquet")):
        day = date.fromisoformat(f.name[:10])
        chain = pd.read_parquet(f)
        closes = chain["close"].to_numpy()
        if not np.array_equal(np.float32(_paise(closes) / 100.0), closes):
            raise SystemExit(f"{f.name}: a close is not a whole paisa; the encoding would lose it")
        lot = lot_table.lot_from_chain(chain)
        days.append((day, lot, chain))
    # The strategy never reads volume off an expiry chain; it is most of the bytes.
    blob = encode(days, ohl=False, volume=False)
    (ASSETS / "expiry_nifty.olx").write_bytes(blob)
    print(f"expiry cache: {len(days)} sessions -> {len(blob)/1e6:.1f} MB")


def export_bars() -> None:
    for u in ("NIFTY", "BANKNIFTY", "INDIAVIX"):
        days = []
        for f in sorted((DATA / "bars" / u).glob("*.parquet")):
            day = date.fromisoformat(f.stem)
            days.append((day, None, pd.read_parquet(f)))
        blob = encode(days, ohl=True)
        (ASSETS / f"bars_{u.lower()}.olx").write_bytes(blob)
        print(f"bars {u}: {len(days)} days -> {len(blob)/1e6:.1f} MB")
    for u in ("NIFTY", "BANKNIFTY"):
        src = DATA / "manifest" / f"{u}.csv"
        if src.exists():
            (ASSETS / f"manifest_{u.lower()}.csv").write_text(src.read_text())
    (ASSETS / "provenance.csv").write_text(
        (DATA / "expiry_cache" / "PROVENANCE.csv").read_text())


def export_reference() -> None:
    """The PC's own ledgers, for the Kotlin engine to reproduce exactly."""
    sessions = []
    for f in sorted((DATA / "expiry_cache").glob("*_chain.parquet")):
        chain = pd.read_parquet(f)
        chain["ts"] = pd.to_datetime(chain["ts"])
        sessions.append((date.fromisoformat(f.name[:10]), chain))
    FIXTURES.mkdir(parents=True, exist_ok=True)
    runs = {
        "reference_naked.csv": dict(lot_size=65),
        "reference_dated_hedged.csv": dict(lot_size=ep.DATED_LOT, wing_pct=0.0075),
        "reference_roll_100.csv": dict(lot_size=65, otm_pct=0.01, regime="roll"),
    }
    for name, kw in runs.items():
        trades, skipped = ep.run_backtest(sessions, **kw)
        trades.to_csv(FIXTURES / name, index=False)
        print(f"{name}: {len(trades)} trades, {len(skipped)} skipped")
    lots = pd.DataFrame([(d, lot_table.lot_from_chain(c)) for d, c in sessions],
                        columns=["session", "lot"])
    lots.to_csv(FIXTURES / "reference_lots.csv", index=False)


if __name__ == "__main__":
    ASSETS.mkdir(parents=True, exist_ok=True)
    export_expiry_cache()
    export_bars()
    if "--no-reference" not in sys.argv:
        export_reference()
