"""Crypto sources -> raw frames for a time window (naive UTC, values as the source has them).

  ArchiveAdapter   the Binance bulk archive (data.binance.vision) in its own layout under a root folder: the sample
                   fetcher's mirror, or months copied from the Drive crypto lake. Files are found by NAME, anywhere
                   under the root: <SYM>-1m-<YYYY-MM>[-DD].{zip,csv}, <SYM>-fundingRate-<YYYY-MM>.zip,
                   <SYM>-metrics-<YYYY-MM-DD>.zip. The kind comes from the folder path (spot / futures ... klines,
                   markPriceKlines, indexPriceKlines, premiumIndexKlines). If the lake stores another layout, add one
                   rule to `_kind` and nothing else changes.
  DvolAdapter      Deribit DVOL (30-day implied volatility index) from the repo's crypto/ study: hourly bars for BTC
                   (crypto/data_extra/dvol_1h.csv). ETH has no DVOL file yet, so its column stays NaN.

Each frame comes back with the columns clean.py reads. `fingerprint(sym, months)` names the files behind a month, so a
cube month is rebuilt when its source files change.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd

from . import clean

REPO = Path(__file__).resolve().parents[3]
NAME = re.compile(r"^(?P<sym>[A-Z0-9]+)-(?P<what>1m|fundingRate|metrics)-(?P<per>\d{4}-\d{2}(?:-\d{2})?)\.(?:zip|csv)$")
KLINE_KINDS = ("spot", "perp", "mark", "index", "premium")


def _kind(parts: tuple[str, ...], what: str) -> str | None:
    p = [x.lower() for x in parts]
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
                m = NAME.match(f.name)
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
        ms = set(months)
        return [f for per, f in self._index.get((sym, kind), []) if per[:7] in ms]

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
    files: dict = field(default_factory=lambda: {"BTCUSDT": REPO / "crypto/data_extra/dvol_1h.csv"})

    def available(self, sym: str) -> bool:
        return sym in self.files and Path(self.files[sym]).exists()

    def fingerprint(self, sym: str) -> str:
        if not self.available(sym):
            return "none"
        st = Path(self.files[sym]).stat()
        return f"{Path(self.files[sym]).name}:{st.st_size}"

    def load(self, sym: str, frm: pd.Timestamp, to: pd.Timestamp) -> pd.DataFrame | None:
        """Hourly DVOL bars; `avail` = bar start + 1 hour (the close is known when the hour ends)."""
        if not self.available(sym):
            return None
        d = pd.read_csv(self.files[sym])
        ts = clean.to_utc(d.time)
        out = pd.DataFrame({"ts": ts, "avail": ts + pd.Timedelta(hours=1), "dvol": d.close.astype(float).values})
        return out[(out.avail >= frm - pd.Timedelta(days=1)) & (out.avail <= to + pd.Timedelta(minutes=1))]
