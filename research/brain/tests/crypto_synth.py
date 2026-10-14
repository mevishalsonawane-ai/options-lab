"""Synthetic Binance-shaped data for the crypto brain's tests (seeded; never written outside a test's temp folder).

raw(days, start)          the frames ArchiveAdapter.load returns (spot, perp, mark, index, premium, funding, metrics)
dvol(days, start)         hourly DVOL bars with their availability time
write_archive(root, raw)  the same rows as Binance archive files: spot without a header and, from 2025, in
                          MICROseconds; futures with a header in milliseconds; funding times +15 ms like the real file
"""
from __future__ import annotations

import io
import zipfile
from pathlib import Path

import numpy as np
import pandas as pd

KCOLS = ["open_time", "open", "high", "low", "close", "volume", "close_time", "quote_volume", "count",
         "taker_buy_volume", "taker_buy_quote_volume", "ignore"]


def _bars(close: np.ndarray, ts: pd.DatetimeIndex, rng, vol_scale: float) -> pd.DataFrame:
    prev = np.r_[close[0], close[:-1]]
    hi = np.maximum(prev, close) * (1 + np.abs(rng.normal(0, 2e-4, len(close))))
    lo = np.minimum(prev, close) * (1 - np.abs(rng.normal(0, 2e-4, len(close))))
    v = rng.lognormal(0, 0.6, len(close)) * vol_scale
    return pd.DataFrame(dict(ts=ts, open=prev, high=hi, low=lo, close=close, volume=v, quote_volume=v * close,
                             trades=rng.integers(50, 2000, len(close)).astype(float),
                             taker_buy_volume=v * rng.uniform(0.3, 0.7, len(close))))


def raw(days: int = 4, start: str = "2025-01-03", seed: int = 0, price: float = 90000.0) -> dict:
    rng = np.random.default_rng(seed)
    n = days * 1440
    ts = pd.date_range(start, periods=n, freq="min")
    spot = price * np.exp(np.cumsum(rng.normal(0, 5e-4, n)))
    basis = 3e-4 + np.cumsum(rng.normal(0, 2e-5, n)) * 0.1
    perp = spot * (1 + basis)
    out = {"spot": _bars(spot, ts, rng, 10.0), "perp": _bars(perp, ts, rng, 50.0)}
    for k, base in (("mark", perp * (1 + rng.normal(0, 1e-5, n))), ("index", spot * (1 + rng.normal(0, 1e-5, n)))):
        out[k] = _bars(base, ts, rng, 0.0)
        out[k]["volume"] = 0.0
    prem = (perp / spot - 1)
    p = _bars(np.abs(prem) + 1, ts, rng, 0.0)
    p[["open", "high", "low", "close"]] = p[["open", "high", "low", "close"]].values - 1
    p["close"] = prem
    out["premium"] = p
    ft = pd.date_range(pd.Timestamp(start) - pd.Timedelta(hours=8), ts[-1], freq="8h") + pd.Timedelta(milliseconds=15)
    out["funding"] = pd.DataFrame(dict(ts=ft, rate=rng.normal(1e-4, 5e-5, len(ft)), interval_h=8))
    mt = pd.date_range(pd.Timestamp(start) - pd.Timedelta(minutes=30), ts[-1], freq="5min")
    oi = 90000 * np.exp(np.cumsum(rng.normal(0, 2e-3, len(mt))))
    out["metrics"] = pd.DataFrame(dict(ts=mt, oi=oi, oi_value=oi * price, top_ls_accounts=rng.uniform(1, 2, len(mt)),
                                       top_ls_positions=rng.uniform(1, 2, len(mt)), ls_accounts=rng.uniform(1, 2, len(mt)),
                                       taker_ls_vol=rng.uniform(0.5, 1.5, len(mt))))
    return out


def dvol(days: int = 4, start: str = "2025-01-03", seed: int = 1) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    t = pd.date_range(pd.Timestamp(start) - pd.Timedelta(hours=2), periods=days * 24 + 2, freq="h")
    return pd.DataFrame(dict(ts=t, avail=t + pd.Timedelta(hours=1), dvol=50 + np.cumsum(rng.normal(0, 0.3, len(t)))))


def _kline_csv(d: pd.DataFrame, unit: str, header: bool) -> str:
    mult = {"ms": 10**3, "us": 10**6}[unit]
    ot = (d.ts.values.astype("datetime64[ms]").astype(np.int64)) * (mult // 1000)
    f = pd.DataFrame({"open_time": ot, "open": d.open, "high": d.high, "low": d.low, "close": d.close,
                      "volume": d.volume, "close_time": ot + 60 * mult - 1, "quote_volume": d.quote_volume,
                      "count": d.trades.astype(int), "taker_buy_volume": d.taker_buy_volume,
                      "taker_buy_quote_volume": d.taker_buy_volume * d.close, "ignore": 0})
    return f.to_csv(index=False, header=header)


def _zip(path: Path, name: str, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(name, text)


FOLDER = {"spot": "data/spot/monthly/klines/{s}/1m", "perp": "data/futures/um/monthly/klines/{s}/1m",
          "mark": "data/futures/um/monthly/markPriceKlines/{s}/1m", "index": "data/futures/um/monthly/indexPriceKlines/{s}/1m",
          "premium": "data/futures/um/monthly/premiumIndexKlines/{s}/1m"}


def write_archive(root: Path, frames: dict, sym: str = "BTCUSDT") -> None:
    root = Path(root)
    for k, folder in FOLDER.items():
        d = frames[k]
        for per, part in d.groupby(d.ts.dt.to_period("M")):
            unit = "us" if (k == "spot" and per.year >= 2025) else "ms"
            name = f"{sym}-1m-{per}"
            _zip(root / folder.format(s=sym) / f"{name}.zip", f"{name}.csv", _kline_csv(part, unit, header=k != "spot"))
    f = frames["funding"]
    for per, part in f.groupby(f.ts.dt.to_period("M")):
        ct = part.ts.values.astype("datetime64[ms]").astype(np.int64)
        txt = pd.DataFrame(dict(calc_time=ct, funding_interval_hours=part.interval_h, last_funding_rate=part.rate)).to_csv(index=False)
        name = f"{sym}-fundingRate-{per}"
        _zip(root / f"data/futures/um/monthly/fundingRate/{sym}" / f"{name}.zip", f"{name}.csv", txt)
    m = frames["metrics"]
    for d, part in m.groupby(m.ts.dt.date):
        buf = io.StringIO()
        pd.DataFrame(dict(create_time=part.ts.dt.strftime("%Y-%m-%d %H:%M:%S"), symbol=sym,
                          sum_open_interest=part.oi, sum_open_interest_value=part.oi_value,
                          count_toptrader_long_short_ratio=part.top_ls_accounts,
                          sum_toptrader_long_short_ratio=part.top_ls_positions,
                          count_long_short_ratio=part.ls_accounts,
                          sum_taker_long_short_vol_ratio=part.taker_ls_vol)).to_csv(buf, index=False)
        name = f"{sym}-metrics-{d}"
        _zip(root / f"data/futures/um/daily/metrics/{sym}" / f"{name}.zip", f"{name}.csv", buf.getvalue())


def option_trades(days: int = 4, start: str = "2025-01-03", seed: int = 2, price: float = 90000.0,
                  per_hour: int = 120) -> pd.DataFrame:
    """Deribit-shaped option trades (the options lake's columns, epoch ms), from 2 days before start: random strikes
    70-130% of the index, 0.3-60 days to expiry, a smile in IV, random taker sides and amounts."""
    rng = np.random.default_rng(seed)
    t0 = pd.Timestamp(start) - pd.Timedelta(days=2)
    n = (days + 2) * 24 * per_hour
    ms = np.sort(rng.integers(0, (days + 2) * 86_400_000, n)) + int(t0.value // 1_000_000)
    idx = price * np.exp(np.cumsum(rng.normal(0, 3e-4, n)))
    m = rng.uniform(0.7, 1.3, n)
    tte = rng.uniform(0.3, 60, n)
    put = rng.random(n) < 0.45
    iv = 50 + 40 * (m - 1) ** 2 + np.where(m < 1, 8 * (1 - m), 0) + rng.normal(0, 2, n)
    iv[rng.random(n) < 0.003] = 0.0                                 # the lake has a few IV-0 prints
    return pd.DataFrame(dict(timestamp=ms, iv=iv, index_price=idx, strike=np.round(m * idx, -2),
                             expiry_ts=ms + (tte * 86_400_000).astype(np.int64), option_type=np.where(put, "P", "C"),
                             direction=np.where(rng.random(n) < 0.5, "buy", "sell"),
                             amount=np.round(rng.lognormal(-1.5, 1.0, n), 1) + 0.1))
