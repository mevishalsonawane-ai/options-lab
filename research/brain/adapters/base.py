"""The common shape every source is turned into before the cube is built.

An adapter yields one DayData per session. All timestamps are naive IST bar starts. Values are exactly what the
source has: nothing is filled or interpolated here.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from typing import Iterator, Protocol

import pandas as pd

INDEX_COLS = ["ts", "open", "high", "low", "close", "volume"]
OPTION_COLS = ["ts", "expiry", "strike", "side", "open", "high", "low", "close", "volume", "oi", "iv_vendor"]
FUT_COLS = ["ts", "close", "volume", "oi"]
VIX_COLS = ["ts", "close"]


def empty(cols: list[str]) -> pd.DataFrame:
    return pd.DataFrame({c: pd.Series(dtype="float64") for c in cols})


@dataclass
class DayData:
    day: date
    und: str
    index: pd.DataFrame                                    # INDEX_COLS
    options: pd.DataFrame = field(default_factory=lambda: empty(OPTION_COLS))   # side "C"/"P", expiry a date
    fut: pd.DataFrame = field(default_factory=lambda: empty(FUT_COLS))
    vix: pd.DataFrame = field(default_factory=lambda: empty(VIX_COLS))
    sources: dict = field(default_factory=dict)         # what each part came from (provenance, goes to the manifest)


class Adapter(Protocol):
    name: str

    def available(self, und: str) -> bool: ...

    def sessions(self, und: str) -> list[date]: ...

    def fingerprint(self, und: str) -> str: ...

    def load(self, und: str, frm: date, to: date) -> Iterator[DayData]: ...
