"""R145 data: 1-minute perpetual klines of the top-100 coins -> 5-minute bars + 1-minute lead-lag statistics.

    python -m research.brain.crypto.r145.data bars --from 2023-12 --to 2026-03            # design months only
    python -m research.brain.crypto.r145.data funding --from 2023-12 --to 2026-03
    python -m research.brain.crypto.r145.data bars --from 2026-04 --to 2026-10 --allow-holdout   # the one holdout read

Months from 2025-11 on are read from the Drive crypto lake (gdrive:IraAlgo-data-crypto, the data Boss loaded); earlier
months from data.binance.vision, the archive the lake was built from. Each raw month is read into memory, turned into
5-minute bars and lead-lag statistics, and dropped: raw minutes never reach the disk for longer than one month's file,
and never git. Months on or after the locked holdout (config.HOLDOUT_START) are refused unless --allow-holdout.

Bars are stamped at their OPEN (UTC, like Binance): bar T covers [T, T + 5 min) and is known at T + 5 min. A bar with no
minutes is absent (never filled); `n1` counts the minutes it was built from.
"""
from __future__ import annotations

import argparse
import io
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

import numpy as np
import pandas as pd

from .. import config
from ..fetch import months as design_months

ARCHIVE = "https://data.binance.vision/data/futures/um/monthly/"
LAKE = "gdrive:IraAlgo-data-crypto/futures_um/"
LAKE_FROM = "2025-11"                       # the top-100 lake starts here; earlier months come from the archive
KCOLS = ["open_time", "open", "high", "low", "close", "volume", "close_time", "quote_volume", "count",
         "taker_buy_volume", "taker_buy_quote_volume"]
BAR_MIN = 5
LAGS = tuple(range(-10, 11))                # lead-lag: corr(alt_t, btc_{t-k}); k > 0 = BTC leads
RCLONE = os.environ.get("RCLONE", str(Path.home() / "bin" / "rclone"))


def root() -> Path:
    return config.paths().base / "r145"


def manifest_symbols(path: str | Path) -> list[str]:
    m = pd.read_csv(path)
    out = []
    for s in m.perp_symbol.dropna():
        out += s.split("+")                 # TONUSDT+GRAMUSDT: the same coin renamed, kept as two symbols
    return out


# --- reading one raw month -----------------------------------------------------------------------------------------
def _get(url: str, tries: int = 4) -> bytes | None:
    for i in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            time.sleep(2 ** i)
        except Exception:                                        # noqa: BLE001 (network blips: retry)
            time.sleep(2 ** i)
    raise RuntimeError(f"download failed: {url}")


def _csv(raw: bytes, cols: list[str]) -> pd.DataFrame:
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        b = z.read(z.namelist()[0])
    first = b[:200].split(b"\n")[0]
    header = 0 if first[:1].isalpha() else None
    df = pd.read_csv(io.BytesIO(b), header=header)
    if header is None:
        df.columns = cols[: df.shape[1]]
    return df


def _lake(kind: str, sym: str, month: str, name: str) -> pd.DataFrame | None:
    path = f"{LAKE}{kind}/{sym}/{month[:4]}/{sym}-{name}-{month}.parquet"
    r = subprocess.run([RCLONE, "cat", path], capture_output=True, timeout=600)
    if r.returncode != 0 or not r.stdout:
        return None
    return pd.read_parquet(io.BytesIO(r.stdout))


def read_klines(sym: str, month: str) -> pd.DataFrame | None:
    if month >= LAKE_FROM:
        df = _lake("klines_1m", sym, month, "klines_1m")
    else:
        raw = _get(ARCHIVE + f"klines/{urllib.parse.quote(sym)}/1m/{urllib.parse.quote(sym)}-1m-{month}.zip")
        df = None if raw is None else _csv(raw, KCOLS)
    if df is None or not len(df):
        return None
    df = df[KCOLS].copy()
    ot = df.open_time.astype("int64")
    ot = np.where(ot > 10 ** 14, ot // 1000, ot)                 # microsecond files -> ms
    df["ts"] = pd.to_datetime(ot, unit="ms")
    df = df.drop_duplicates("ts").sort_values("ts")
    lo = pd.Timestamp(month + "-01")
    hi = lo + pd.offsets.MonthBegin(1)
    return df[(df.ts >= lo) & (df.ts < hi)].reset_index(drop=True)


def read_funding(sym: str, month: str) -> pd.DataFrame | None:
    raw = _get(ARCHIVE + f"fundingRate/{urllib.parse.quote(sym)}/{urllib.parse.quote(sym)}-fundingRate-{month}.zip")
    if raw is None:
        df = _lake("funding", sym, month, "funding") if month >= LAKE_FROM else None
        if df is None:
            return None
    else:
        df = _csv(raw, ["calc_time", "funding_interval_hours", "last_funding_rate"])
    ct = df.calc_time.astype("int64")
    return pd.DataFrame({"ts": pd.to_datetime(ct, unit="ms"), "interval_h": df.funding_interval_hours.astype(float),
                         "rate": df.last_funding_rate.astype(float)})


# --- 1-minute -> 5-minute bars ------------------------------------------------------------------------------------------
def to_bars(m: pd.DataFrame, bar_min: int = BAR_MIN) -> pd.DataFrame:
    """5-minute bars from one month of 1-minute klines. rv = sum of squared 1-minute log returns inside the bar (the
    first minute's return is taken from the previous minute's close when that minute exists; a gap breaks the chain)."""
    m = m.sort_values("ts").reset_index(drop=True)
    c = m.close.to_numpy(float)
    lr = np.full(len(m), np.nan)
    if len(m) > 1:
        adj = (m.ts.diff() == pd.Timedelta(minutes=1)).to_numpy()
        lr[1:] = np.where(adj[1:], np.log(c[1:] / c[:-1]), np.nan)
    m = m.assign(r2=lr ** 2, b=m.ts.dt.floor(f"{bar_min}min"))
    g = m.groupby("b", sort=True)
    out = pd.DataFrame({
        "open": g.open.first(), "high": g.high.max(), "low": g.low.min(), "close": g.close.last(),
        "volume": g.volume.sum(), "qv": g.quote_volume.sum(), "trades": g["count"].sum(),
        "tbqv": g.taker_buy_quote_volume.sum(), "rv": g.r2.sum(min_count=1), "n1": g.size(),
    }).astype("float64")
    out.index.name = "ts"
    return out.reset_index()


def leadlag(alt: pd.DataFrame, btc_ret: pd.Series, lags=LAGS) -> pd.DataFrame:
    """corr(alt 1-minute return at t, BTC 1-minute return at t - k) for each k (k > 0: BTC earlier), on minutes where
    both exist. Also the n used."""
    a = alt.set_index("ts").close.astype(float)
    a = np.log(a).diff()
    a = a[a.index.to_series().diff() == pd.Timedelta(minutes=1)]
    rows = []
    for k in lags:
        b = btc_ret.copy()
        b.index = b.index + pd.Timedelta(minutes=k)
        j = pd.concat([a.rename("a"), b.rename("b")], axis=1, join="inner").dropna()
        n = len(j)
        r = float(np.corrcoef(j.a, j.b)[0, 1]) if n > 100 and j.a.std() > 0 and j.b.std() > 0 else np.nan
        rows.append((k, r, n))
    return pd.DataFrame(rows, columns=["lag", "corr", "n"])


def btc_returns(m: pd.DataFrame) -> pd.Series:
    s = m.set_index("ts").close.astype(float)
    r = np.log(s).diff()
    return r[r.index.to_series().diff() == pd.Timedelta(minutes=1)].dropna()


# --- jobs ----------------------------------------------------------------------------------------------------------
def _job(sym: str, month: str, out: str) -> tuple[str, str, str]:
    out = Path(out)
    bp = out / "bars5" / sym / f"{month}.parquet"
    lp = out / "leadlag" / sym / f"{month}.parquet"
    if bp.exists() and (sym == "BTCUSDT" or lp.exists()):
        return sym, month, "cached"
    m = read_klines(sym, month)
    if m is None:
        bp.parent.mkdir(parents=True, exist_ok=True)
        (bp.parent / f"{month}.missing").touch()
        return sym, month, "none"
    bars = to_bars(m)
    bp.parent.mkdir(parents=True, exist_ok=True)
    bars.to_parquet(bp, index=False)
    bfile = out / "btc1m" / f"{month}.parquet"
    if sym == "BTCUSDT":
        bfile.parent.mkdir(parents=True, exist_ok=True)
        btc_returns(m).rename("r").to_frame().to_parquet(bfile)
    else:
        br = pd.read_parquet(bfile).r
        lp.parent.mkdir(parents=True, exist_ok=True)
        leadlag(m, br).assign(sym=sym, month=month).to_parquet(lp, index=False)
    return sym, month, f"{len(bars)} bars"


def build_bars(symbols, frm, to, allow_holdout=False, workers=6):
    ms = design_months(frm, to, allow_holdout)
    out = root()
    # BTC first (the lead-lag reference), then everything else in parallel
    with ProcessPoolExecutor(workers) as ex:
        for f in as_completed([ex.submit(_job, "BTCUSDT", m, str(out)) for m in ms]):
            print(*f.result(), flush=True)
        jobs = [ex.submit(_job, s, m, str(out)) for s in symbols if s != "BTCUSDT" for m in ms
                if not (out / "bars5" / s / f"{m}.missing").exists()]
        for i, f in enumerate(as_completed(jobs)):
            r = f.result()
            if i % 50 == 0 or r[2] == "none":
                print(i, len(jobs), *r, flush=True)


def build_funding(symbols, frm, to, allow_holdout=False):
    ms = design_months(frm, to, allow_holdout)
    out = root() / "funding"
    out.mkdir(parents=True, exist_ok=True)
    for s in symbols:
        parts = [read_funding(s, m) for m in ms]
        parts = [p for p in parts if p is not None and len(p)]
        f = out / f"{s}.parquet"
        old = pd.read_parquet(f) if f.exists() else None
        if parts or old is not None:
            d = pd.concat(([old] if old is not None else []) + parts).drop_duplicates("ts").sort_values("ts")
            d.to_parquet(f, index=False)
            print(s, len(d), flush=True)


def load_bars(sym: str, lo: str | None = None, hi: str | None = None, base: Path | None = None) -> pd.DataFrame | None:
    d = (base or root()) / "bars5" / sym
    fs = sorted(p for p in d.glob("*.parquet") if (lo is None or p.stem >= lo) and (hi is None or p.stem <= hi))
    if not fs:
        return None
    return pd.concat([pd.read_parquet(p) for p in fs], ignore_index=True)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.crypto.r145.data")
    ap.add_argument("what", choices=["bars", "funding"])
    ap.add_argument("--manifest", default=None)
    ap.add_argument("--symbols", nargs="*")
    ap.add_argument("--from", dest="frm", required=True)
    ap.add_argument("--to", required=True)
    ap.add_argument("--allow-holdout", action="store_true")
    ap.add_argument("--workers", type=int, default=6)
    a = ap.parse_args(argv)
    syms = a.symbols or manifest_symbols(a.manifest)
    if a.what == "bars":
        build_bars(syms, a.frm, a.to, a.allow_holdout, a.workers)
    else:
        build_funding(syms, a.frm, a.to, a.allow_holdout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
