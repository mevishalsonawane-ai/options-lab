"""Turning the strategy into an order you could actually place.

Everything in this repo measures the past. Nothing turned a live chain into a
concrete instruction, and nothing accumulated a forward track record - so the
strategy's only evidence is a backtest of itself.

Two deliberate limits.

A TICKET IS NOT A TRADE. This computes and records an intention. It sends
nothing, holds no credentials, and cannot place an order. The ledger it writes
is a PAPER ledger, and every row says so.

THE LEDGER IS THE SCHEMA monitor ALREADY READS. A paper record that could not
be fed to run_checks would be a second, unvalidated notion of a trade. Settled
rows go through the same settle_trade the backtest uses.
"""
from __future__ import annotations

import json
from datetime import date, time

import pandas as pd
import pytest

from options_lab import live

IST = "Asia/Kolkata"


def _chain(day="2026-09-15", spot=24_000.0, step=50.0, n=15, upto="11:00"):
    rows = []
    stamps = [pd.Timestamp(f"{day} {h:02d}:{m:02d}", tz=IST)
              for h in range(9, int(upto[:2]) + 1) for m in (0, 30)]
    for ts in stamps:
        for i in range(-n, n + 1):
            k = spot + i * step
            for right in ("CE", "PE"):
                intr = max(spot - k, 0.0) if right == "CE" else max(k - spot, 0.0)
                rows.append({"ts": ts, "strike": k, "right": right,
                             "close": intr + 20.0, "volume": 500,
                             "open_interest": 6500})
    return pd.DataFrame(rows)


# --- the ticket ------------------------------------------------------------
def test_a_ticket_names_the_contract_and_the_side():
    t = live.build_ticket(_chain(), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65)

    assert t.side == "SELL"
    assert t.right == "PE"
    assert t.strike < t.forward, "a short put sits BELOW the forward"


def test_the_quantity_is_lots_times_the_lot_size():
    t = live.build_ticket(_chain(), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65, lots=2)

    assert t.qty == 130


def test_the_ticket_states_break_even_not_just_the_credit():
    """A credit alone does not tell you what has to happen to keep it."""
    t = live.build_ticket(_chain(), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65)

    assert t.breakeven == pytest.approx(t.strike - t.credit)


def test_a_naked_ticket_reports_its_loss_as_unbounded():
    """The single most important line on it. Reporting a number here would be
    a lie, and reporting nothing would let it be read as zero."""
    t = live.build_ticket(_chain(), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65)

    assert t.max_loss is None
    assert "unbounded" in t.format().lower()


def test_a_hedged_ticket_reports_a_real_maximum_loss():
    t = live.build_ticket(_chain(), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65, wing_pct=0.0075)

    assert t.wing_strike is not None and t.wing_strike < t.strike
    assert t.max_loss is not None and t.max_loss > 0
    assert "unbounded" not in t.format().lower()


def test_the_ticket_reports_the_margin_it_will_block():
    t = live.build_ticket(_chain(), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65)

    assert t.margin > 0


# --- refusals --------------------------------------------------------------
def test_a_chain_too_thin_to_price_is_refused():
    with pytest.raises(Exception):
        live.build_ticket(_chain(n=2), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65)


def test_a_chain_with_no_bars_at_the_entry_time_is_refused():
    """Before 11:00 there is no signal yet, and saying so is the point."""
    with pytest.raises(live.TooEarly):
        live.build_ticket(_chain(upto="10:00"), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65,
                          entry_time=time(11, 0))


# --- the paper ledger ------------------------------------------------------
def test_recording_a_ticket_writes_an_open_paper_row(tmp_path):
    t = live.build_ticket(_chain(), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65)

    path = live.record_ticket(tmp_path, t)
    row = json.loads(path.read_text())

    assert row["status"] == "open"
    assert row["paper"] is True, "every row must say it was never sent"
    assert "settlement" not in row


def test_an_open_ticket_cannot_be_recorded_twice(tmp_path):
    t = live.build_ticket(_chain(), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65)
    live.record_ticket(tmp_path, t)

    with pytest.raises(live.AlreadyRecorded):
        live.record_ticket(tmp_path, t)


def test_settling_completes_the_row_through_the_same_settle_trade(tmp_path):
    """Not a second notion of P&L: the backtest's own function."""
    t = live.build_ticket(_chain(), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65)
    live.record_ticket(tmp_path, t)

    row = live.settle_ticket(tmp_path, date(2026, 9, 15), settlement=24_050.0)

    assert row["status"] == "settled"
    assert row["settlement"] == 24_050.0
    assert "net_pnl" in row and "won" in row


def test_the_settled_ledger_is_readable_by_the_health_monitor(tmp_path):
    """The whole reason the schema matters."""
    from options_lab import monitor

    for i, day in enumerate([date(2026, 9, 15), date(2026, 9, 22)]):
        t = live.build_ticket(_chain(day=f"{day}"), session=day,
                              underlying="NIFTY", lot_size=65)
        live.record_ticket(tmp_path, t)
        live.settle_ticket(tmp_path, day, settlement=24_050.0)

    ledger = live.read_ledger(tmp_path)

    assert len(ledger) == 2
    checks = monitor.run_checks(ledger, otm_pct=0.0075, lot_size=65)
    assert all(c.status in ("pass", "warn", "fail") for c in checks)


def test_settling_a_session_that_was_never_opened_is_refused(tmp_path):
    with pytest.raises(live.NotRecorded):
        live.settle_ticket(tmp_path, date(2026, 9, 15), settlement=24_000.0)


def test_the_ledger_ignores_rows_still_open(tmp_path):
    """An open ticket has no P&L, and must not be counted as a flat trade."""
    t = live.build_ticket(_chain(), session=date(2026, 9, 15),
                          underlying="NIFTY", lot_size=65)
    live.record_ticket(tmp_path, t)

    assert live.read_ledger(tmp_path).empty
