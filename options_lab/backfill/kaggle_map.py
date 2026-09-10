"""Map a Kaggle archive session onto the store schema.

Archive columns:
    date, open, high, low, close, volume, oi, symbol, name, expiry, strike,
    instrument_type   (instrument_type is CE / PE / FUT)

Two conventions are load-bearing:

  UNITS. Archive open interest is denominated in CONTRACTS, reconciled against
  NSE bhavcopy at a median ratio of 1.0000. The Upstox feed is lot-multiplied.
  The store keeps contracts, so this path passes OI through UNSCALED - applying
  a lot divisor here would be a 65x (NIFTY) / 30x (BANKNIFTY) error landing on
  the boundary between the two feeds.

  LOT SIZE. The archive does not carry it, and NIFTY's changed more than once
  across 2023-2026. It is recorded as NA rather than guessed, so any rupee P&L
  computed from these rows fails loudly instead of being quietly wrong. Index
  points and information coefficients do not need it.

Futures rows are dropped here; they are real and useful (they give a directly
observable forward for put-call parity) but they are not chain members.
"""
from __future__ import annotations

import pandas as pd

from options_lab.harvest import store

RIGHTS = ("CE", "PE")

COLUMNS = store.COLUMNS[:-3] + ["dte"]   # store schema minus source/qty_units/fetched_at


def to_store_frame(session: pd.DataFrame, underlying: str) -> pd.DataFrame:
    """Archive session -> store-shaped option bars, with dte attached."""
    names = set(session["name"].dropna().unique())
    if names and names != {underlying}:
        raise ValueError(
            f"session carries {sorted(names)} but was read as {underlying!r}; "
            "the two underlyings live in different archive folders"
        )

    opts = session[session["instrument_type"].isin(RIGHTS)]
    if opts.empty:
        return pd.DataFrame({c: pd.Series(dtype="object") for c in COLUMNS})

    expiry = pd.to_datetime(opts["expiry"]).dt.date
    ts = pd.to_datetime(opts["date"])
    strike = opts["strike"].astype("float64")
    right = opts["instrument_type"]

    # Vectorised contract_id. The per-row f-string cost ~24M formats on a
    # 60-session run and dominated the wall clock.
    expiry_str = pd.Series(expiry, dtype="object").astype("string")
    strike_str = pd.Series(
        [f"{v:g}" for v in strike.unique()], index=strike.unique()
    ).reindex(strike.to_numpy()).astype("string")

    out = pd.DataFrame({
        "contract_id": (underlying + "|" + expiry_str.to_numpy()
                        + "|" + strike_str.to_numpy()
                        + "|" + right.astype("string").to_numpy()),
        "instrument_key": pd.NA,          # archive has no broker token
        "underlying": underlying,
        "expiry": list(expiry),
        "strike": strike.to_numpy(),
        "right": right.to_numpy(),
        "lot_size": pd.NA,                # unknown for this era; never guessed
        "ts": ts.to_numpy(),
        "open": opts["open"].to_numpy(),
        "high": opts["high"].to_numpy(),
        "low": opts["low"].to_numpy(),
        "close": opts["close"].to_numpy(),
        "volume": opts["volume"].to_numpy(),
        "open_interest": opts["oi"].to_numpy(),      # already contracts
    })
    out["ts"] = pd.to_datetime(out["ts"])
    out["dte"] = [(e - t.date()).days for e, t in zip(out["expiry"], out["ts"])]
    return out.reset_index(drop=True)
