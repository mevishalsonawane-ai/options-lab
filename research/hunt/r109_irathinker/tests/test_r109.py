"""R109 tests: synthetic bars only (no lake, no network)."""
from __future__ import annotations

import math
import subprocess
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))

import r109cfg as K  # noqa: E402
import r109engine as E  # noqa: E402

N = K.NROWS


def flat_idx(level=50000.0):
    a = np.full((4, N), level, dtype=np.float64)
    return a


def contract(price=100.0, vol=1000.0):
    c = np.full((5, N), price, dtype=np.float32).astype(np.float64)
    c[4] = vol
    return c


class Day:
    def __init__(self, idx, con=None, atm=None, expiry=False):
        self.idx, self.con, self.atm, self.expiry = idx, con or {}, atm, expiry


# ------------------------------------------------------------------------------------------------ ret60
def test_ret60_values_and_gaps():
    c = np.full(N, 100.0)
    c[70] = 101.0
    r = E.ret60(c)
    assert np.isnan(r[:60]).all()
    assert r[70] == pytest.approx(1.0)
    assert r[130] == pytest.approx((100 / 101 - 1) * 100)
    c[10] = np.nan
    assert np.isnan(E.ret60(c)[70])


def test_ret60_look_ahead_guard():
    rng = np.random.default_rng(1)
    c = 50000 * np.exp(np.cumsum(rng.normal(0, 5e-4, N)))
    base = E.ret60(c)
    for i in (60, 150, 300):
        c2 = c.copy()
        c2[i + 1:] *= 1.05
        assert np.allclose(E.ret60(c2)[: i + 1], base[: i + 1], equal_nan=True)


# ------------------------------------------------------------------------------------------------ the signal walk
def _walk(c, thr, res_after=30, fill=lambda i: True):
    calls = []

    def trade(i, call):
        calls.append(i)
        if not fill(i):
            return False, None, None
        return True, i + res_after, {"i": i}

    fired = E.mom60_day(c, thr, trade)
    return [f[0] for f in fired], calls


def test_walk_spacing_one_position_and_window():
    c = np.full(N, 100.0)
    c[60:] = 101.0                     # |ret60| = 1% from row 60 to 119, then 0
    c[200:] = 103.0                    # again from 200 to 259
    fired, _ = _walk(c, 0.5)
    assert fired[0] == K.FIRST_SIG_ROW          # first possible signal bar 10:15 (row 60)
    assert all(b - a >= K.SPACING for a, b in zip(fired, fired[1:]))
    assert fired == [60, 200]


def test_walk_busy_until_resolve_and_resolve_bar_not_judged():
    c = np.full(N, 100.0)
    c[60:] = 101.0
    c[121:] = 102.1                    # ret60 >= 0.5% again at 121..180
    fired, _ = _walk(c, 0.5, res_after=70)       # resolves at 130: 121..130 not judged, 131 fires
    assert fired == [60, 131]


def test_walk_unfilled_signal_does_not_reset_spacing():
    c = np.full(N, 100.0)
    c[60:] = 101.0
    fired, calls = _walk(c, 0.5, fill=lambda i: i >= 63)
    assert calls[:4] == [60, 61, 62, 63] and fired[0] == 63


def test_walk_last_signal_row():
    c = np.full(N, 100.0)
    c[K.LAST_SIG_ROW + 1:] = 102.0                # the move arrives at 14:30: too late
    assert _walk(c, 0.5)[0] == []
    c = np.full(N, 100.0)
    c[K.LAST_SIG_ROW:] = 102.0
    assert _walk(c, 0.5)[0] == [K.LAST_SIG_ROW]


def test_walk_look_ahead_guard():
    rng = np.random.default_rng(7)
    c = 50000 * np.exp(np.cumsum(rng.normal(0, 8e-4, N)))
    fired, _ = _walk(c, 0.3)
    assert fired
    first = fired[0]
    c2 = c.copy()
    c2[first + 1:] = c2[first + 1:] * 0.9
    assert _walk(c2, 0.3)[0][0] == first


# ------------------------------------------------------------------------------------------------ fills and exits
def test_slip_and_charges():
    assert E.slip(100.0, True, 50) == pytest.approx(round(100 * 1.0005 + 0.05, 2))
    assert E.slip(100.0, False, 0) == pytest.approx(round(100 * 0.9995 - 0.20, 2))
    assert E.slip(100.0, False, 5, stop=True) == pytest.approx(round(100 * 0.999 - 0.20, 2))
    ch_b = E.charges(100.0, 30, True, date(2025, 6, 2), False)
    ch_s = E.charges(100.0, 30, False, date(2025, 6, 2), False)
    assert ch_s > ch_b > 20                       # STT on the sell side only


def test_long_trade_time_exit():
    con = contract(100.0)
    idx = flat_idx()
    t = E.long_trade(con, idx, 61, 30, date(2025, 6, 2), date(2025, 6, 26), False, 50000.0, True)
    assert t["f"] == 61 and t["reason"] == "TIME" and t["xr"] == 121 and t["dec"] == 120
    assert t["net"] < 0                           # flat premium: costs only
    assert t["gross_pct"] == pytest.approx(0.0)


def test_long_trade_stop_on_close_next_open():
    con = contract(100.0)
    con[3, 80] = 69.0                             # close <= 70% at 80
    con[0, 81] = 68.0
    t = E.long_trade(con, flat_idx(), 61, 30, date(2025, 6, 2), date(2025, 6, 26), False, 50000.0, True)
    assert t["reason"] == "STOP" and t["dec"] == 80 and t["xr"] == 81 and t["raw_x"] == 68.0
    assert t["real_x"] == pytest.approx(round(68 * 0.999 - 2 * 0.05, 2))


def test_long_trade_low_alone_does_not_stop():
    con = contract(100.0)
    con[2, 80] = 50.0                             # a low through the stop with the close above: no stop (closes only)
    t = E.long_trade(con, flat_idx(), 61, 30, date(2025, 6, 2), date(2025, 6, 26), False, 50000.0, True)
    assert t["reason"] == "TIME"


def test_long_trade_entry_look_and_no_fill():
    con = contract(100.0)
    con[0, 61:63] = np.nan
    t = E.long_trade(con, flat_idx(), 61, 30, date(2025, 6, 2), date(2025, 6, 26), False, 50000.0, True)
    assert t["f"] == 63
    con[0, 61:64] = np.nan
    assert E.long_trade(con, flat_idx(), 61, 30, date(2025, 6, 2), date(2025, 6, 26), False, 50000.0, True) is None


def test_long_trade_late_entry_exits_at_1529_close():
    con = contract(100.0)
    con[3, N - 1] = 110.0
    t = E.long_trade(con, flat_idx(), K.LAST_SIG_ROW + 1, 30, date(2025, 6, 2), date(2025, 6, 26), False, 50000.0,
                     True)
    assert t["xr"] == N - 1 and t["raw_x"] == 110.0


def test_long_trade_stale_exit_repriced():
    con = contract(800.0)
    idx = flat_idx(50000.0)
    con[:4, 100:] = np.nan                        # the contract stops printing after row 99
    t = E.long_trade(con, idx, 61, 30, date(2025, 6, 2), date(2025, 6, 26), False, 50000.0, True)
    assert t["stale"]
    # same index level, a little less time: the model price is just under the last print
    assert 790 < t["raw_x"] < 800
    idx2 = flat_idx(50000.0)
    idx2[3, 100:] = 49000.0                       # the index fell 1,000 points: the call is repriced far lower
    t2 = E.long_trade(con, idx2, 61, 30, date(2025, 6, 2), date(2025, 6, 26), False, 50000.0, True)
    assert t2["raw_x"] < 500


# ------------------------------------------------------------------------------------------------ Black-Scholes
def test_bs_and_implied_vol_roundtrip():
    p = E.bs(50000, 50000, 20 / 365, 0.15, True)
    v = E.implied_vol(p, 50000, 50000, 20 / 365, True)
    assert v == pytest.approx(0.15, abs=1e-6)
    assert E.implied_vol(0.01, 50000, 49000, 20 / 365, True) is None     # below intrinsic


def test_ira_bs_port():
    for x in (-2.5, -0.3, 0.0, 0.7, 3.0):
        assert E.ira_ncdf(x) == pytest.approx(0.5 * math.erfc(-x / math.sqrt(2)), abs=1e-6)
    ours = E.bs(50000, 50200, 10 / 365, 0.14, True)
    theirs = E.ira_bs(50000, 50200, 10, 14, True)
    assert theirs == pytest.approx(ours, rel=1e-4)
    assert E.ira_bs(50000, 50000, 0.0, 14, True) == pytest.approx(E.ira_bs(50000, 50000, 0.35, 14, True))


def test_ira_expiry_rules():
    assert E.ira_monthly_expiry(date(2025, 10, 1), False) == date(2025, 10, 28)
    assert E.ira_monthly_expiry(date(2025, 10, 29), False) == date(2025, 11, 25)
    assert E.ira_monthly_expiry(date(2026, 10, 26), True) == date(2026, 10, 29)


def test_mom60_bs_stop_and_time():
    c = np.full(N, 50000.0)
    r = E.mom60_bs(c, 100, True, 100.0, date(2025, 6, 2), 15.0, False)
    assert r[1] == "TIME" and -5 < r[0] < 0       # flat spot: theta only
    c2 = c.copy()
    c2[110:] = 49000.0
    r2 = E.mom60_bs(c2, 100, True, 100.0, date(2025, 6, 2), 15.0, False)
    assert r2[1] == "STOP" and r2[0] <= -30


# ------------------------------------------------------------------------------------------------ strangle
def _week(mon_level=50000.0, exit_level=50000.0, thu_holiday=False):
    days = {}
    mon = date(2025, 6, 2)
    for k in range(4):
        d = date(2025, 6, 2 + k)
        if thu_holiday and k == 3:
            continue
        lvl = mon_level if k == 0 else exit_level
        con = {("C", 50200.0): contract(150.0), ("P", 49800.0): contract(140.0)}
        days[d] = Day(flat_idx(lvl), con, np.full(N, 50000.0))
    return days, mon


def test_strangle_normal_week():
    days, mon = _week()
    w = E.strangle_week(days, mon, date(2025, 6, 26), 30)
    assert w["exit_day"] == date(2025, 6, 5) and not w["exp_week"]
    assert w["credit"] == pytest.approx(290.0) and w["cost"] == pytest.approx(290.0)
    assert w["net"] < 0                           # flat prices: costs only


def test_strangle_holiday_thursday_exits_wednesday():
    days, mon = _week(thu_holiday=True)
    w = E.strangle_week(days, mon, date(2025, 6, 26), 30)
    assert w["exit_day"] == date(2025, 6, 4)


def test_strangle_expiry_week_settles_at_intrinsic():
    days, mon = _week(exit_level=50500.0)
    w = E.strangle_week(days, mon, date(2025, 6, 4), 30)
    assert w["exp_week"] and w["settle"] and w["exit_day"] == date(2025, 6, 4)
    assert w["cost"] == pytest.approx(300.0)      # CE 50200 is 300 in the money, PE worthless


def test_strangle_monday_holiday_and_missing_leg():
    days, mon = _week()
    assert E.strangle_week(days, date(2025, 6, 3), date(2025, 6, 26), 30) is None   # not a Monday
    del days[mon].con[("P", 49800.0)]
    assert E.strangle_week(days, mon, date(2025, 6, 26), 30) is None


def test_strangle_look_ahead_guard_entry():
    days, mon = _week()
    w1 = E.strangle_week(days, mon, date(2025, 6, 26), 30)
    days[mon].idx[:, K.STR_DECIDE + 1:] = 60000.0  # the index after the 09:30 bar cannot change the strikes
    w2 = E.strangle_week(days, mon, date(2025, 6, 26), 30)
    assert w1["atm"] == w2["atm"] == 50000.0


# ------------------------------------------------------------------------------------------------ statistics
def test_twin_p():
    rng = np.random.default_rng(0)
    real = np.full(30, 100.0)
    pool = rng.normal(0, 10, (30, 50))
    assert E.twin_p(real, pool, 2000, rng) == pytest.approx(1 / 2001)
    assert E.twin_p(np.zeros(30), pool * 0, 2000, rng) == pytest.approx(1.0)
    pool[3] = np.nan                              # a trade with no twin keeps its own net
    assert 0 < E.twin_p(real, pool, 500, rng) <= 1


def test_bh_with_earlier_tests():
    q = E.bh([1e-6, 0.5], 14_245 + 2)
    assert q[0] == pytest.approx(1e-6 * 14_247)
    assert E.bh([0.01, 0.02])[1] == pytest.approx(0.02)


def test_period_stats_drawdown_and_weeks():
    rows = [dict(day=date(2025, 6, 2), net=100.0), dict(day=date(2025, 6, 3), net=-300.0),
            dict(day=date(2025, 6, 10), net=50.0), dict(day=date(2025, 7, 1), net=-14_000.0)]
    s = E.period_stats(rows)
    assert s["n"] == 4 and s["max_dd"] == pytest.approx(14_250.0)
    assert s["worst_week"] == pytest.approx(-14_000.0) and s["months_le_13k"] == 1
    assert s["win"] == pytest.approx(0.5)


def test_final_lock(tmp_path):
    flag = HERE / "results" / "FINAL_READ"
    if not flag.exists():
        pytest.skip("the final has not been read yet")
    out = subprocess.run([sys.executable, str(HERE / "r109study.py"), "final"], capture_output=True, text=True)
    assert out.returncode != 0 and "already read" in (out.stderr + out.stdout)


def test_threshold_config_matches_irathinker():
    assert K.THRESH == {"BANKNIFTY": 0.50, "FINNIFTY": 0.5025, "SENSEX": 0.4735}
    assert K.HOLD == 60 and K.STOP_MULT == 0.70 and K.LAST_SIG_ROW == K.row(14, 29)
    assert K.N_PRIOR == 14_245
