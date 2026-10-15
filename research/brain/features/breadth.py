"""Breadth family: PENDING DATA. Nothing here is computed.

The dictionary's breadth fields need 1-minute bars of the index constituents (and, for heavyweight contribution, their
index weights). No adapter provides them today:
  - adapters/release.py loads the index, its options, VIX and the BANKNIFTY future only. The research-data release
    also has bank_stocks_year.parquet (12 BANKNIFTY constituents from Upstox, research/fetch_stocks.py), but no adapter
    reads it and it carries no index weights.
  - the Dhan data lake (adapters/lake.py) has no CATALOG.json in the repo yet, and its planned files have no
    constituent bars.
  - there is no NIFTY 50 constituent data anywhere.
Once an adapter puts constituent bars (and weights) into DayData, replace these with @feature definitions.
"""
from __future__ import annotations

from .registry import pending

NEEDS_BN = "1-min OHLCV of the BANKNIFTY constituents in an adapter (release bank_stocks_year.parquet is not loaded)"
NEEDS_N = "1-min OHLCV of the NIFTY 50 constituents (no source)"
W = " + index weights"

for _und, _needs in (("banknifty", NEEDS_BN), ("nifty", NEEDS_N)):
    pending(f"breadth_ad_{_und}", "breadth", _needs,
            f"Advance/decline of the {_und.upper()} constituents: (advancing - declining) / count, vs the previous "
            f"close, per minute.")
    pending(f"breadth_above_vwap_{_und}", "breadth", _needs,
            f"Share of the {_und.upper()} constituents trading above their own session VWAP.")
    pending(f"heavyweight_contrib_{_und}", "breadth", _needs + W,
            f"Index points contributed today by the top {_und.upper()} weights (HDFC Bank, ICICI Bank, ... for "
            f"BANKNIFTY): weight x return.")
