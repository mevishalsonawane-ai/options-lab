"""Crypto sources -> raw frames for a time window (naive UTC, values as the source has them).

  ArchiveAdapter   the Binance bulk archive (data.binance.vision) in its own layout under a root folder: the sample
                   fetcher's mirror, or months copied from the Drive crypto lake. Files are found by NAME, anywhere
                   under the root: <SYM>-1m-<YYYY-MM>[-DD].{zip,csv}, <SYM>-fundingRate-<YYYY-MM>.zip,
                   <SYM>-metrics-<YYYY-MM-DD>.zip. The kind comes from the folder path (spot / futures ... klines,
                   markPriceKlines, indexPriceKlines, premiumIndexKlines). If the lake stores another layout, add one
                   rule to `_kind` and nothing else changes.
                   The Drive crypto lake (gdrive:IraAlgo-data-crypto, research/data/crypto/README.md) stores monthly
                   Parquet instead: <SYM>-{klines_1m,markprice_1m,indexprice_1m,funding,metrics}-<YYYY-MM>.parquet
                   under spot/ or futures_um/ (14 Oct 2026, F1). Both layouts are read; the lake has no premium-index
                   klines and no mark / index / metrics for SOL, BNB, XRP, so those come from the archive.
  DvolAdapter      Deribit DVOL (30-day implied volatility index), hourly bars: the options lake's
                   deribit/dvol/{BTC,ETH}_1h.parquet when it is in the cache (both coins, 14 Oct 2026), otherwise the
                   repo's crypto/data_extra/dvol_1h.csv (BTC only).
  OptionsAdapter   Deribit option trades (options lake deribit/trades/<COIN>/<YYYY>/<YYYY-MM>.parquet) -> one row per
                   UTC hour, stamped at the hour's END (when every trade in it is known): at-the-money IV, put-call
                   skew, put/call traded amount and taker flow over 24 hours, trade count. BTC and ETH only.

Each frame comes back with the columns clean.py reads. `fingerprint(sym, months)` names the files behind a month, so a
cube month is rebuilt when its source files change.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

from . import clean

REPO = Path(__file__).resolve().parents[3]
NAME = re.compile(r"^(?P<sym>[A-Z0-9]+)-(?P<what>1m|fundingRate|metrics)-(?P<per>\d{4}-\d{2}(?:-\d{2})?)\.(?:zip|csv)$")
LAKE = re.compile(r"^(?P<sym>[A-Z0-9]+)-(?P<what>klines_1m|markprice_1m|indexprice_1m|funding|metrics)-(?P<per>\d{4}-\d{2})"
                  r"\.parquet$")
KLINE_KINDS = ("spot", "perp", "mark", "index", "premium")


def _kind(parts: tuple[str, ...], what: str) -> str | None:
    p = [x.lower() for x in parts]
    lake = {"markprice_1m": "mark", "indexprice_1m": "index", "funding": "funding", "metrics": "metrics"}
    if what in lake:
        return lake[what]
    if what == "klines_1m":
        return "perp" if "futures_um" in p else "spot" if "spot" in p else None
    if what == "fundingRate":
        return "funding"
    if what == "metrics":
        return "metrics"
    if "markpriceklines" in p:
        return "mark"
    if "indexpriceklines" in p:
        return "index"
    if "premiumindexklines" in p:
        return "premium"
    if "klines" in p:
        return "perp" if ("futures" in p or "um" in p) else "spot"
    return None


@dataclass
class ArchiveAdapter:
    root: Path
    _index: dict = field(default_factory=dict, repr=False)

    def __post_init__(self):
        self.root = Path(self.root)
        idx: dict = {}
        if self.root.exists():
            for f in sorted(self.root.rglob("*")):
                m = NAME.match(f.name) or LAKE.match(f.name)
                if not m or not f.is_file():
                    continue
                k = _kind(f.relative_to(self.root).parts[:-1], m["what"])
                if k is None:
                    continue
                # a monthly file beats daily files of the same month for klines; both are kept and de-duplicated later
                idx.setdefault((m["sym"], k), []).append((m["per"], f))
        self._index = idx

    def symbols(self) -> list[str]:
        return sorted({s for s, _ in self._index})

    def kinds(self, sym: str) -> list[str]:
        return sorted(k for s, k in self._index if s == sym)

    def files(self, sym: str, kind: str, months: list[str]) -> list[Path]:
        """Files of the months asked for. When a month is in both layouts, the lake's Parquet wins and the archive's
        files of that month are not read (the same bars twice would be marked as duplicates)."""
        ms = set(months)
        got = [(per, f) for per, f in self._index.get((sym, kind), []) if per[:7] in ms]
        lake = {per[:7] for per, f in got if f.suffix == ".parquet"}
        return [f for per, f in got if f.suffix == ".parquet" or per[:7] not in lake]

    def months(self, sym: str) -> list[str]:
        """Months that have spot or perp klines (what a cube month is built from)."""
        out = set()
        for k in ("spot", "perp"):
            out |= {per[:7] for per, _ in self._index.get((sym, k), [])}
        return sorted(out)

    def fingerprint(self, sym: str, months: list[str]) -> str:
        parts = []
        for k in KLINE_KINDS + ("funding", "metrics"):
            for f in self.files(sym, k, months):
                st = f.stat()
                parts.append(f"{k}:{f.name}:{st.st_size}")
        return "|".join(sorted(parts))

    def load(self, sym: str, frm: pd.Timestamp, to: pd.Timestamp) -> dict[str, pd.DataFrame]:
        """Raw frames for [frm, to] (inclusive minutes). Keys: spot, perp, mark, index, premium, funding, metrics."""
        months = [str(p) for p in pd.period_range(frm, to, freq="M")]
        out = {}
        for k in KLINE_KINDS + ("funding", "metrics"):
            fs = self.files(sym, k, months)
            if not fs:
                continue
            reader = clean.read_funding if k == "funding" else clean.read_metrics if k == "metrics" else clean.read_klines
            d = pd.concat([reader(f) for f in fs], ignore_index=True)
            # funding/metrics are carried forward, so keep a little before frm; klines exactly the window
            lo = frm - pd.Timedelta(days=1) if k in ("funding", "metrics") else frm
            d = d[(d.ts >= lo) & (d.ts <= to + pd.Timedelta(minutes=1))].sort_values("ts", kind="stable")
            out[k] = d.reset_index(drop=True)
        return out


@dataclass
class DvolAdapter:
    """files: {symbol: path}. None (the default) = the options lake's <options_root>/deribit/dvol/<COIN>_1h.parquet
    when present, else the repo CSV for BTC. An explicit dict (even {}) is used as given."""
    files: dict | None = None
    options_root: Path | None = None

    def _file(self, sym: str) -> Path | None:
        from . import config
        if self.files is not None:
            f = self.files.get(sym)
            return Path(f) if f is not None and Path(f).exists() else None
        coin = config.COIN.get(sym)
        base = Path(self.options_root) if self.options_root else config.paths().options
        for f in ([base / "deribit" / "dvol" / f"{coin}_1h.parquet"] if coin else []) + (
                [REPO / "crypto/data_extra/dvol_1h.csv"] if sym == "BTCUSDT" else []):
            if f.exists():
                return f
        return None

    def available(self, sym: str) -> bool:
        return self._file(sym) is not None

    def fingerprint(self, sym: str) -> str:
        f = self._file(sym)
        if f is None:
            return "none"
        import hashlib
        return hashlib.sha256(f.read_bytes()).hexdigest()[:16]        # content, not size (an edit counts)

    def load(self, sym: str, frm: pd.Timestamp, to: pd.Timestamp) -> pd.DataFrame | None:
        """Hourly DVOL bars; `avail` = bar start + 1 hour (the close is known when the hour ends)."""
        f = self._file(sym)
        if f is None:
            return None
        if f.suffix == ".parquet":
            d = pd.read_parquet(f)
            ts, close = clean.to_utc(d.timestamp), d.close
        else:
            d = pd.read_csv(f)
            ts, close = clean.to_utc(d.time), d.close
        out = pd.DataFrame({"ts": ts, "avail": ts + pd.Timedelta(hours=1), "dvol": close.astype(float).values})
        return out[(out.avail >= frm - pd.Timedelta(days=1)) & (out.avail <= to + pd.Timedelta(minutes=1))]


# --- Deribit option trades -> hourly option-market columns -------------------------------------------------------------
# Fixed in advance (F1 PREREG, 14 Oct 2026); moneyness m = strike / index price at the trade, tte in days.
OPT_ATM = dict(m=(0.98, 1.02), tte=(1.0, 30.0))                    # at the money, 1-30 days
OPT_WING = dict(put=(0.85, 0.97), call=(1.03, 1.15), tte=(3.0, 45.0))   # out-of-the-money wings for the skew
OPT_COLS = ["opt_atm_iv", "opt_skew", "opt_pc_24h", "opt_flow_24h", "opt_n_1h"]


def option_hours(tr: pd.DataFrame, frm: pd.Timestamp, to: pd.Timestamp) -> pd.DataFrame:
    """Deribit trades -> one row per UTC hour H with ts = H + 1 hour (the instant every trade of [H, H+1h) is known):
      opt_atm_iv    median IV of at-the-money trades in the hour (vol points)
      opt_skew      median IV of OTM puts minus OTM calls over the last 4 hours (vol points; > 0 = puts dearer)
      opt_pc_24h    put / call traded amount (coins) over the last 24 hours, all expiries
      opt_flow_24h  taker flow over 24 h: (call buys - call sells - put buys + put sells) / total amount, -1 .. +1
      opt_n_1h      trades in the hour
    Only trades with ts < the row's ts are used (pure function of the trades)."""
    hrs = pd.date_range(pd.Timestamp(frm).floor("h"), pd.Timestamp(to).floor("h"), freq="h")
    out = pd.DataFrame({"ts": hrs + pd.Timedelta(hours=1)})
    for c in OPT_COLS:
        out[c] = np.nan
    if tr is None or not len(tr):
        return out
    t = clean.to_utc(tr.timestamp)
    iv = pd.to_numeric(tr.iv, errors="coerce").to_numpy(float)
    ok = np.isfinite(iv) & (iv > 0) & (iv <= 500) & (tr.index_price.to_numpy(float) > 0)
    d = pd.DataFrame({"h": t.floor("h"), "iv": iv, "amt": tr.amount.to_numpy(float),
                      "m": tr.strike.to_numpy(float) / tr.index_price.to_numpy(float),
                      "tte": (tr.expiry_ts.to_numpy(float) - tr.timestamp.to_numpy(float)) / 86_400_000,
                      "put": (tr.option_type == "P").to_numpy(), "buy": (tr.direction == "buy").to_numpy()})[ok]
    H = pd.Index(hrs)
    atm = d[(d.m >= OPT_ATM["m"][0]) & (d.m <= OPT_ATM["m"][1]) & (d.tte >= OPT_ATM["tte"][0]) & (d.tte <= OPT_ATM["tte"][1])]
    out["opt_atm_iv"] = atm.groupby("h").iv.median().reindex(H).to_numpy()
    out["opt_n_1h"] = d.groupby("h").size().reindex(H).fillna(0).to_numpy(float)
    wing = d[(d.tte >= OPT_WING["tte"][0]) & (d.tte <= OPT_WING["tte"][1])]
    # 4-hour medians need the trades themselves: one pass over 4-hour windows ending at each hour's end
    pw = wing[wing.put & (wing.m >= OPT_WING["put"][0]) & (wing.m <= OPT_WING["put"][1])]
    cw = wing[~wing.put & (wing.m >= OPT_WING["call"][0]) & (wing.m <= OPT_WING["call"][1])]

    def med4(x):
        g = {h: v.iv.to_numpy() for h, v in x.groupby("h")}
        res = np.full(len(H), np.nan)
        for i, h in enumerate(H):
            parts = [g[k] for k in (h - pd.Timedelta(hours=j) for j in range(4)) if k in g]
            if parts:
                v = np.concatenate(parts)
                if len(v) >= 5:
                    res[i] = np.median(v)
        return res
    out["opt_skew"] = med4(pw) - med4(cw)
    sgn = np.where(d.put, -1.0, 1.0) * np.where(d.buy, 1.0, -1.0)
    agg = pd.DataFrame({"h": d.h, "put": np.where(d.put, d.amt, 0.0), "call": np.where(d.put, 0.0, d.amt),
                        "flow": sgn * d.amt, "amt": d.amt}).groupby("h").sum()
    full = agg.reindex(pd.date_range(H.min() - pd.Timedelta(hours=23), H.max(), freq="h")).fillna(0.0)
    r = full.rolling(24, min_periods=24).sum().reindex(H)
    with np.errstate(invalid="ignore", divide="ignore"):
        out["opt_pc_24h"] = np.where(r.call > 0, r.put / r.call, np.nan)
        out["opt_flow_24h"] = np.where(r.amt > 0, r.flow / r.amt, np.nan)
    # an hour whose 24-hour window starts before the trades loaded is not computable
    first = d.h.min()
    out.loc[np.asarray(H - pd.Timedelta(hours=23) < first), ["opt_pc_24h", "opt_flow_24h"]] = np.nan
    return out


@dataclass
class OptionsAdapter:
    root: Path | None = None

    def __post_init__(self):
        from . import config
        self.root = Path(self.root) if self.root else config.paths().options

    def _files(self, sym: str, months: list[str]) -> list[Path]:
        from . import config
        coin = config.COIN.get(sym)
        if coin is None:
            return []
        base = self.root / "deribit" / "trades" / coin
        return [f for m in months if (f := base / m[:4] / f"{m}.parquet").exists()]

    def fingerprint(self, sym: str, months: list[str]) -> str:
        return "|".join(f"{f.name}:{f.stat().st_size}" for f in self._files(sym, months)) or "none"

    def load(self, sym: str, frm: pd.Timestamp, to: pd.Timestamp) -> pd.DataFrame | None:
        """Hourly option columns for the rows from frm to to (needs the trades of the 24 hours before frm)."""
        lo = pd.Timestamp(frm) - pd.Timedelta(days=2)
        months = [str(p) for p in pd.period_range(lo, to, freq="M")]
        fs = self._files(sym, months)
        if not fs:
            return None
        cols = ["timestamp", "iv", "index_price", "strike", "expiry_ts", "option_type", "direction", "amount"]
        tr = pd.concat([pd.read_parquet(f, columns=cols) for f in fs], ignore_index=True)
        tr = tr[(tr.timestamp >= (lo - pd.Timestamp(0)).total_seconds() * 1000)]
        return option_hours(tr, pd.Timestamp(frm) - pd.Timedelta(hours=1), to)
