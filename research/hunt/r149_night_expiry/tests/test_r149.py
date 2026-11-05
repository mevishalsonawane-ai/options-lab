"""R149 tests (synthetic only): which contract each skipped expiry night may use."""
from datetime import date

from research.hunt.r149_night_expiry.study import choose

D, ND = date(2024, 3, 5), date(2024, 3, 6)


def test_night_before_expiry_holds_expiring_and_monthly():
    assert choose("contract_expires_next_session", D, ND, set(), "NIFTY") == {"E1": "TRADE", "E2": "MONTH"}


def test_night_before_monthly_expiry_has_no_monthly_choice():
    assert choose("contract_expires_next_session", D, ND, {ND}, "NIFTY") == {"E1": "TRADE"}


def test_banknifty_trades_the_monthly_already():
    assert choose("contract_expires_next_session", D, ND, set(), "BANKNIFTY") == {"E1": "TRADE"}


def test_expiry_day_uses_monthly_unless_it_is_the_monthly_expiry():
    assert choose("entry_on_expiry_day", D, ND, set(), "NIFTY") == {"E3": "MONTH"}
    assert choose("entry_on_expiry_day", D, ND, {D}, "NIFTY") == {}
    assert choose("entry_on_expiry_day", D, ND, {ND}, "NIFTY") == {}
