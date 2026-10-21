"""R89 tests: option tape lookups, straddle pricing, exits, non-overlap, summary (20 Oct 2026)."""
from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r89 import run, sim


def tape_rows():
    """One day, NIFTY: ATM 24000 at 09:15-09:59, the strike moves to offset -1 at 09:40 (spot rose)."""
    rows = []
    for m in range(45):
        ts = pd.Timestamp("2025-06-02 09:15") + pd.Timedelta(minutes=m)
        off = 0 if m < 25 else -1
        rows.append(dict(ts=ts, offset=off, side="C", strike=24000.0, open=100 + m, close=100.5 + m, volume=750))
        rows.append(dict(ts=ts, offset=-off, side="P", strike=24000.0, open=100 - m / 2, close=99.5 - m / 2, volume=750))
        if m >= 25:
            rows.append(dict(ts=ts, offset=0, side="C", strike=24050.0, open=80 + m, close=80 + m, volume=10))
    return pd.DataFrame(rows)


def test_tape_lookups():
    t = sim.OptionTape.build("NIFTY", tape_rows())
    assert t.atm_strike([np.datetime64("2025-06-02")], [10])[0] == 24000
    assert t.atm_strike([np.datetime64("2025-06-02")], [30])[0] == 24050      # offset 0 moved
    cid = t.cid([pd.Timestamp("2025-06-02")], "C", [24000.0])
    pos, ok = t.first_print(cid, np.array([20]))
    assert ok[0] and t.opn[pos[0]] == 120
    pos, ok = t.first_print(cid, np.array([60]))
    assert not ok[0]                                                          # no print within 3 minutes
    pos, ok = t.last_close(cid, np.array([60]))
    assert ok[0] and t.cls[pos[0]] == 100.5 + 44
    assert t.vol_before(cid, np.array([10]))[0] == 5 * 750
    assert t.cid([pd.Timestamp("2025-06-02")], "C", [1.0])[0] == -1


def test_price_straddle_index():
    t = sim.OptionTape.build("NIFTY", tape_rows())
    p = sim.price(t, "NIFTY", [pd.Timestamp("2025-06-02")], np.array([10]), np.array([25])).iloc[0]
    assert p.ok and p.strike == 24000 and p.qty == 75 and not p.stale
    # buy at the open + 5 bps + 1 tick (volume >= 20 lots); sell at the open - 5 bps - 1 tick
    assert p.buy_c == pytest.approx(110.11) and p.sell_c == pytest.approx(124.89)  # 5 bps rounded against us, 1 tick
    assert p.gross == pytest.approx(75 * (p.sell_c - p.buy_c + p.sell_p - p.buy_p))
    assert 80 < p.cost < 200 and p.net == pytest.approx(p.gross - p.cost)
    q = sim.price(t, "NIFTY", [pd.Timestamp("2025-06-02")], np.array([20]), np.array([60])).iloc[0]
    assert q.ok and q.stale                                                   # exit after the last print: last close
    r = sim.price(t, "NIFTY", [pd.Timestamp("2025-06-03")], np.array([10]), np.array([25])).iloc[0]
    assert not r.ok and np.isnan(r.net)


def test_exit_minute_square_off():
    d = [pd.Timestamp("2025-06-02"), pd.Timestamp("2025-06-03")]
    ex = sim.exit_minute("NIFTY", d, np.array([340, 340]), 60, {date(2025, 6, 3)})
    assert list(ex) == [15 * 60 + 10 - 555, 15 * 60 + 5 - 555]
    mx = sim.exit_minute("GOLDM", [pd.Timestamp("2026-10-19")], np.array([820]), 60, set())
    assert mx[0] == 23 * 60 + 30 - 15 - 540                                   # US summer close 23:30, minus 15


def test_non_overlap():
    days = np.array(["a", "a", "a", "b"])
    keep = run.non_overlap(days, np.array([10, 20, 30, 10]), np.array([25, 35, 45, 25]))
    assert list(keep) == [True, False, True, True]


def test_summary():
    t = pd.DataFrame(dict(ts=pd.date_range("2025-06-02 10:00", periods=4, freq="D"), ok=[True, True, True, False],
                          net=[100.0, -50.0, -30.0, np.nan], gross=[150.0, 0, 20, np.nan], cost=[50.0, 50, 50, np.nan],
                          entry_prem=[200.0, 210, 190, np.nan], stale=[False] * 4))
    t["day"] = t.ts.dt.normalize()
    s = run.summary(t)
    assert s["trades"] == 3 and s["win_rate"] == pytest.approx(1 / 3) and s["net"] == 20
    assert s["avg_win"] == 100 and s["avg_loss"] == -40 and s["max_drawdown"] == -80


def test_atm_without_a_put_is_not_priced():
    t = sim.OptionTape.build("NIFTY", tape_rows())
    q = sim.price(t, "NIFTY", [pd.Timestamp("2025-06-02")], np.array([30]), np.array([40])).iloc[0]
    assert q.strike == 24050 and not q.ok and np.isnan(q.net)
