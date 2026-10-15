"""Expiry days inferred from the lake's option prices (expiry_infer.py, F1 14 Oct 2026)."""
from datetime import date

import pandas as pd
import pytest

from research.brain import calendar, expiry_infer as X


def test_collapse_then_jump_is_an_expiry():
    tv = pd.Series({date(2024, 1, 1): 1.2, date(2024, 1, 2): 0.1, date(2024, 1, 3): 1.0,   # Tue expiry
                    date(2024, 1, 8): 0.4, date(2024, 1, 9): 0.5,                          # no jump: not an expiry
                    date(2024, 1, 16): 0.05})                                              # last session: never
    assert X.expiry_days(tv) == [date(2024, 1, 2)]


def test_time_value_from_lake_rows():
    rows = []
    for d, c, p in ((date(2024, 1, 2), 12.0, 10.0), (date(2024, 1, 3), 80.0, 70.0)):
        for hhmm, cc, pp in (("14:59", 999.0, 999.0), ("15:10", c + 5, p + 5), ("15:20", c, p), ("15:21", 1.0, 1.0)):
            ts = pd.Timestamp(f"{d} {hhmm}")
            rows.append(dict(ts=ts, offset=0, side="C", close=cc, strike=20000.0, spot=20010.0))
            rows.append(dict(ts=ts, offset=0, side="P", close=pp, strike=20000.0, spot=20010.0))
            rows.append(dict(ts=ts, offset=1, side="C", close=1.0, strike=20050.0, spot=20010.0))
    tv = X.close_time_value(pd.DataFrame(rows))
    # the last ATM closes at or before 15:20: (12 + 10 - |20010 - 20000|) / 20010 %
    assert tv[date(2024, 1, 2)] == pytest.approx(12 / 20010 * 100)
    assert X.expiry_days(tv) == [date(2024, 1, 2)]


def test_write_keeps_other_indices(tmp_path, monkeypatch):
    p = tmp_path / "expiries.csv"
    pd.DataFrame([dict(date="2024-02-14", und="BANKNIFTY", source="release")]).to_csv(p, index=False)
    monkeypatch.setattr(calendar, "EXPIRIES_CSV", p)
    monkeypatch.setattr(X, "infer", lambda lake, u: [date(2024, 1, 2)])
    X.write(["FINNIFTY"], lake=None)
    e = pd.read_csv(p)
    assert set(e.und) == {"BANKNIFTY", "FINNIFTY"} and len(e) == 2
    X.write(["FINNIFTY"], lake=None)                              # re-running replaces, never duplicates
    assert len(pd.read_csv(p)) == 2
    calendar.observed_expiries.cache_clear()


def test_refuses_indices_with_rules():
    with pytest.raises(SystemExit):
        X.main(["--und", "NIFTY"])


def test_csv_has_finnifty_and_sensex_not_midcp():
    e = pd.read_csv(calendar.EXPIRIES_CSV)
    assert {"FINNIFTY", "SENSEX"} <= set(e.und) and "MIDCPNIFTY" not in set(e.und)
