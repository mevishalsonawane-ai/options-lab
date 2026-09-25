"""Session manifest: what was collected, how, and whether the chain is complete.

`scope` is the load-bearing field:

    same_day   collected on the session date itself, so the partition holds the
               whole live chain as it actually traded
    backfill   reached by asking today's instrument master for history, so the
               partition holds ONLY the contracts still listed on the day the
               harvester ran. Every front chain that had already expired is
               missing and is unrecoverable from any free source.

Chain-aggregate features - signed order flow, PCR, skew, anything summed across
strikes - are only meaningful on `same_day` sessions. On a backfilled session
the denominator is missing most of its contracts, so the aggregate is a
different quantity wearing the same name.
"""
from __future__ import annotations

from datetime import date
from pathlib import Path

import pandas as pd

SAME_DAY = "same_day"
BACKFILL = "backfill"
SCOPES = (SAME_DAY, BACKFILL)

COLUMNS = ["session", "n_expiries", "n_contracts", "scope", "collected_on"]
KEY = "session"


class ScopeMismatch(ValueError):
    """Claimed same-day collection for a session that was reached by backfill."""


def scope_for(*, day: date, today: date, failures: int,
              n_contracts: int = 1) -> str:
    """What this partition may honestly claim.

    same_day means the partition holds the whole live chain AS IT TRADED. If
    any contract failed to fetch it does not, so the claim would be false -
    and consumers treat same_day as the trustworthy set, so a partial chain
    hiding inside it would corrupt every chain-aggregate feature built on it.
    An EMPTY chain fails the same test. A partition holding only index rows
    once claimed same_day with n_contracts=0, because that underlying had no
    contract expiring today and so never needed the current-session fetch.
    Nothing was lost - tomorrow's dated fetch returns today's bars - but a
    same_day session with no chain in it would poison every consumer that
    filters on that scope precisely because it trusts it.

    Understating to backfill is the safe direction throughout.
    """
    if day == today and failures == 0 and n_contracts > 0:
        return SAME_DAY
    return BACKFILL


def _path(root: Path, underlying: str) -> Path:
    return Path(root) / "manifest" / f"{underlying}.csv"


def read(root: Path, underlying: str) -> pd.DataFrame:
    path = _path(root, underlying)
    if not path.exists():
        return pd.DataFrame({c: pd.Series(dtype="object") for c in COLUMNS})
    df = pd.read_csv(path, parse_dates=["session", "collected_on"])
    for col in ("session", "collected_on"):
        df[col] = df[col].dt.date
    return df


def record(
    root: Path,
    underlying: str,
    session: date,
    *,
    n_expiries: int,
    n_contracts: int,
    scope: str,
    collected_on: date,
) -> None:
    """Upsert one session row. Refuses a same-day claim that cannot be true."""
    if scope not in SCOPES:
        raise ValueError(f"unknown scope {scope!r}; expected one of {SCOPES}")
    if scope == SAME_DAY and collected_on != session:
        raise ScopeMismatch(
            f"session {session} was collected on {collected_on}; that is "
            f"{BACKFILL!r}, not {SAME_DAY!r} - the chain as traded that day is gone"
        )

    existing = read(root, underlying)

    # A later run re-fetches recent sessions through the dated endpoint and
    # upserts them into the same partition. That adds bars; it removes none,
    # so a chain captured on its own day is still the chain as it traded.
    # Re-recording it as backfill used to downgrade every same_day session the
    # night after it was collected, which left complete_chain_sessions holding
    # at most today - and emptied the scope chain-aggregate features rely on.
    prior = existing[existing[KEY] == session] if len(existing) else existing
    if (scope == BACKFILL and len(prior)
            and prior["scope"].iloc[-1] == SAME_DAY):
        scope = SAME_DAY
        collected_on = prior["collected_on"].iloc[-1]

    row = pd.DataFrame([{
        "session": session, "n_expiries": n_expiries, "n_contracts": n_contracts,
        "scope": scope, "collected_on": collected_on,
    }])

    merged = (
        pd.concat([existing, row], ignore_index=True)
        .drop_duplicates(subset=KEY, keep="last")
        .sort_values(KEY)
        .reset_index(drop=True)
    )

    path = _path(root, underlying)
    path.parent.mkdir(parents=True, exist_ok=True)
    merged[COLUMNS].to_csv(path, index=False)


def complete_chain_sessions(root: Path, underlying: str) -> list[date]:
    """Only these are eligible for chain-aggregate features."""
    df = read(root, underlying)
    if df.empty:
        return []
    return sorted(df.loc[df["scope"] == SAME_DAY, "session"].tolist())
