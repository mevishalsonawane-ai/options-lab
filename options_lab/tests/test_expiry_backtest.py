"""Session loop and sealed holdout for the expiry-day put.

Two things this closes.

THE LOOP LIVED IN THE CLI. `cmd_expiry_put` embedded the whole backtest inline,
so it could not be unit-tested and could not be reused. It now lives here.

THE MODULE CLAIMED A HOLDOUT IT DID NOT HAVE. expiry_put's docstring says the
strategy "measured positive on both train and a sealed holdout", but every run
evaluated all 170 sessions in one pass. The split existed only in a scratch
script. Given this project has twice reported results that did not survive out
of sample, a claimed-but-absent holdout is the worst defect in the repo.

The split is cheap and honest here for one specific reason: the strategy has NO
fitted parameter. Entry time and strike distance were chosen by a prior sweep,
not by this code, so holding sessions back costs nothing and the holdout is
genuinely untouched.
"""
from __future__ import annotations

from datetime import date, time

import pandas as pd
import pytest

from options_lab.strategy import expiry_put as ep

IST = "Asia/Kolkata"


def _session_chain(day: date, spot: float = 24_000.0, settle: float | None = None,
                   step: float = 50.0, n: int = 12) -> pd.DataFrame:
    """A full session: an 11:00 quote plus a 15:00-15:29 settlement window.

    Priced so the parity forward is exactly `spot` at entry and `settle` in the
    settlement window, which makes the expected P&L computable by hand.
    """
    settle = spot if settle is None else settle
    stamps = [(pd.Timestamp(f"{day} 11:00", tz=IST), spot)]
    stamps += [(pd.Timestamp(f"{day} 15:{m:02d}", tz=IST), settle)
               for m in range(0, 30, 5)]

    rows = []
    for ts, fwd in stamps:
        for i in range(-n, n + 1):
            k = spot + i * step
            tv = 20.0
            rows.append({"ts": ts, "strike": k, "right": "CE",
                         "close": max(fwd - k, 0.0) + tv, "volume": 1000})
            rows.append({"ts": ts, "strike": k, "right": "PE",
                         "close": max(k - fwd, 0.0) + tv, "volume": 1000})
    return pd.DataFrame(rows)


def _days(n: int, start=date(2024, 1, 4)) -> list[date]:
    return [start + pd.Timedelta(weeks=i) for i in range(n)]


def _days_as_dates(n: int) -> list[date]:
    base = pd.Timestamp("2024-01-04")
    return [(base + pd.Timedelta(weeks=i)).date() for i in range(n)]


# --- the session loop ------------------------------------------------------
def test_one_session_produces_one_trade():
    day = date(2024, 1, 4)
    trades, skipped = ep.run_backtest([(day, _session_chain(day))], lot_size=65)

    assert len(trades) == 1
    assert not skipped
    assert trades.iloc[0]["session"] == day


def test_a_worthless_expiry_is_a_win_and_an_itm_one_is_not():
    up = date(2024, 1, 4)
    down = date(2024, 1, 11)
    trades, _ = ep.run_backtest([
        (up, _session_chain(up, settle=24_500.0)),      # rallied, put expires OTM
        (down, _session_chain(down, settle=23_000.0)),  # fell hard, put is ITM
    ], lot_size=65)

    by_day = trades.set_index("session")
    assert by_day.loc[up, "won"]
    assert not by_day.loc[down, "won"]
    assert by_day.loc[down, "intrinsic"] > 0


def test_a_session_too_thin_to_price_is_skipped_not_silently_dropped():
    thin = date(2024, 1, 4)
    chain = _session_chain(thin, n=2)          # only 5 strikes, needs 10

    trades, skipped = ep.run_backtest([(thin, chain)], lot_size=65)

    assert trades.empty
    assert len(skipped) == 1
    assert skipped[0][0] == thin


def test_the_entry_time_is_honoured():
    day = date(2024, 1, 4)
    chain = _session_chain(day)
    late = chain[chain["ts"].dt.hour >= 15]     # nothing at or before 11:00

    _, skipped = ep.run_backtest([(day, late)], lot_size=65,
                                 entry_time=time(11, 0))

    assert len(skipped) == 1


def test_every_trade_carries_the_realised_otm_distance():
    day = date(2024, 1, 4)
    trades, _ = ep.run_backtest([(day, _session_chain(day))], lot_size=65,
                                otm_pct=0.0075)

    otm = trades.iloc[0]["otm_realised"]
    assert 0.004 < otm < 0.011, "strike lands near, not exactly on, the target"


def test_the_cost_regime_is_passed_through():
    day = date(2024, 1, 4)
    cheap, _ = ep.run_backtest([(day, _session_chain(day))], lot_size=65,
                               regime="roll")
    dear, _ = ep.run_backtest([(day, _session_chain(day))], lot_size=65,
                              regime="stress")

    assert cheap.iloc[0]["cost"] < dear.iloc[0]["cost"]
    assert cheap.iloc[0]["net_pnl"] > dear.iloc[0]["net_pnl"]


# --- the sealed split ------------------------------------------------------
def test_the_split_holds_back_the_most_recent_sessions():
    days = _days_as_dates(50)

    train, holdout = ep.split_sessions(days, holdout=30)

    assert len(train) == 20 and len(holdout) == 30
    assert max(train) < min(holdout), "the holdout must be the LATER sessions"


def test_train_and_holdout_are_disjoint_and_complete():
    days = _days_as_dates(50)

    train, holdout = ep.split_sessions(days, holdout=30)

    assert set(train) | set(holdout) == set(days)
    assert not set(train) & set(holdout)


def test_the_split_is_deterministic():
    days = _days_as_dates(50)

    assert ep.split_sessions(days, holdout=30) == ep.split_sessions(
        list(reversed(days)), holdout=30), "order of input must not matter"


def test_a_holdout_larger_than_the_sample_is_refused():
    with pytest.raises(ValueError):
        ep.split_sessions(_days_as_dates(10), holdout=30)


def test_a_holdout_that_would_leave_no_training_data_is_refused():
    with pytest.raises(ValueError):
        ep.split_sessions(_days_as_dates(30), holdout=30)


def test_the_boundary_date_is_recoverable_from_the_split():
    """A holdout you cannot state the boundary of is not sealed."""
    days = _days_as_dates(50)

    train, holdout = ep.split_sessions(days, holdout=30)

    assert min(holdout) > max(train)
    assert (min(holdout) - max(train)).days > 0
