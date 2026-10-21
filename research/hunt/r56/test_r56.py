"""R56 tests: Boss's NIFTY ORB plan on synthetic sessions (no lake needed): the range, the first-close signal, the ATM
and held contract, the +Rs 1,000 target per lot, the range stop, square-off (15:15 / 15:05 on expiry), no-signal,
gap, holiday and missing-print days, dated charges, the stray-print guard, the foreign-row filter, the metrics, the
twins and the look-ahead guard."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r56cfg as K  # noqa: E402
import r56data as D  # noqa: E402
import r56engine as R  # noqa: E402
import r56study as S  # noqa: E402
from r3data import N, Sess  # noqa: E402

DAY = date(2025, 6, 10)
HI, LO = 24020.0, 23980.0


def flat_ix(level=24000.0):
    ix = np.full((N, 4), level)
    ix[:K.RANGE_ROWS, 1] = level + 5
    ix[:K.RANGE_ROWS, 2] = level - 5
    ix[3, 1] = HI      # the range high and low are set inside 09:15 .. 09:29
    ix[7, 2] = LO
    return ix


def set_close(ix, r, c):
    ix[r] = (c, c + 1, c - 1, c)


def opt(price=100.0, vol=10000.0):
    a = np.full((N, 6), np.nan, dtype=np.float32)
    a[:, 0] = a[:, 1] = a[:, 2] = a[:, 3] = price
    a[:, 4] = vol
    a[:, 5] = 1000
    return a


def session(ix=None, expiry=False, day=DAY, lot=75, strikes=range(23500, 24550, 50)):
    opts = {}
    for k in strikes:
        opts[(float(k), "C")] = opt()
        opts[(float(k), "P")] = opt()
    return Sess(day, "NIFTY", flat_ix() if ix is None else ix, np.full(N, np.nan), opts, None, expiry, lot)


def call_day(sig_row=40, close=24030.0, **kw):
    ix = flat_ix()
    set_close(ix, sig_row, close)
    ix[sig_row + 1:, :] = close     # stays above the range: no stop
    return session(ix, **kw)


# ------------------------------------------------------------------------------------------------ window, lots
def test_period_split():
    assert K.period(date(2024, 11, 19)) == "out"
    assert K.period(date(2024, 11, 21)) == "design"
    assert K.period(date(2026, 3, 31)) == "design"
    assert K.period(date(2026, 4, 1)) == "recent"


def test_lot_table_fallback_uses_the_repo_schedule():
    assert K.lot_table(date(2025, 6, 1)) == 75
    assert K.lot_table(date(2026, 1, 5)) == 65


def test_times():
    assert K.hm(K.RANGE_ROWS - 1) == "09:29" and K.hm(K.FIRST_SIG) == "09:30" and K.hm(K.LAST_SIG) == "14:59"
    assert K.hm(K.SQ_ROW) == "15:15" and K.hm(K.SQ_EXPIRY) == "15:05"


# ------------------------------------------------------------------------------------------------ range and signal
def test_range_is_0915_to_0929_only():
    ix = flat_ix()
    set_close(ix, 15, 24000.0)
    ix[15, 1] = 25000.0                     # the 09:30 bar's high is not in the range
    assert R.opening_range(ix) == (HI, LO)


def test_missing_0929_bar_means_no_trade():
    ix = flat_ix()
    ix[K.RANGE_ROWS - 1] = np.nan
    assert R.run_session(session(ix))["status"] == "no_range"


def test_holiday_or_empty_day_no_trade():
    ix = np.full((N, 4), np.nan)
    assert R.run_session(session(ix))["status"] == "no_range"


def test_no_signal_day():
    assert R.run_session(session())["status"] == "no_signal"


def test_first_close_beyond_counts_not_a_wick():
    ix = flat_ix()
    ix[30, 1] = HI + 50                     # a wick above, close inside: no signal
    set_close(ix, 50, LO - 10)              # first CLOSE beyond is below: a put
    ix[51:] = LO - 10
    r, side = R.find_signal(ix, HI, LO)
    assert (r, side) == (50, -1)


def test_signal_after_1459_is_ignored():
    ix = flat_ix()
    set_close(ix, K.LAST_SIG + 1, HI + 30)
    assert R.find_signal(ix, HI, LO) == (-1, 0)
    set_close(ix, K.LAST_SIG, HI + 30)
    assert R.find_signal(ix, HI, LO) == (K.LAST_SIG, 1)


def test_gap_past_the_range_signals_at_0930():
    ix = flat_ix(24500.0)                   # gap day: the range forms at the new level
    hi, lo = R.opening_range(ix)
    set_close(ix, 15, hi + 40)
    ix[16:] = hi + 40
    s = session(ix, strikes=range(24000, 25100, 50))
    out = R.run_session(s)
    assert out["status"] == "traded"
    t = out["trade"]
    assert t["sig_row"] == 15 and t["entry_row"] == 16 and t["side"] == 1


def test_atm_is_nearest_strike_half_up():
    assert R.atm(24024.9) == 24000.0
    assert R.atm(24025.0) == 24050.0
    assert R.atm(23974.0) == 23950.0


# ------------------------------------------------------------------------------------------------ the trade
def test_entry_next_minute_open_of_the_atm_call():
    s = call_day()
    t = R.run_session(s)["trade"]
    assert t["strike"] == 24050.0 and t["side"] == 1          # ATM of 24030 -> 24050 (nearest)
    assert t["entry_row"] == 41
    assert t["entry"] == pytest.approx(100.05 + 0.05, abs=0.011)   # 5 bps + 1 tick, rounded to the paisa


def test_target_level_is_rs_1000_per_lot_rounded_up():
    for lot, pts in ((75, 13.35), (65, 15.40), (25, 40.0)):
        s = call_day(lot=lot)
        t = R.run_session(s)["trade"]
        assert t["target_px"] == pytest.approx(round(t["entry"] + pts, 2), abs=0.051)
        assert (t["target_px"] - t["entry"]) * lot >= 1000 - 1e-6


def test_target_hit_sells_next_open():
    s = call_day()
    a = s.opts[(24050.0, "C")]
    a[60:, :4] = 114.0                      # high 114 >= target 113.45 at row 60
    t = R.run_session(s)["trade"]
    assert t["why"] == "target" and t["exit_row"] == 61
    assert t["gross"] > 900 and t["net"] < t["gross"]


def test_range_stop_exits_on_index_close_below_low_with_stop_slippage():
    s = call_day()
    set_close(s.ix, 70, LO - 1)             # back below the OTHER side
    t = R.run_session(s)["trade"]
    assert t["why"] == "stop" and t["exit_row"] == 71
    assert t["exit"] == pytest.approx(99.90 - 0.10, abs=0.011)    # 10 bps + 2 ticks


def test_close_back_inside_the_range_is_not_a_stop():
    s = call_day()
    set_close(s.ix, 70, 24000.0)            # inside the range, not below the low
    assert R.run_session(s)["trade"]["why"] == "square_off"


def test_stop_and_target_same_minute_count_as_stop():
    s = call_day()
    set_close(s.ix, 70, LO - 1)
    s.opts[(24050.0, "C")][70, 1] = 130.0
    assert R.run_session(s)["trade"]["why"] == "stop"


def test_square_off_1515_normal_and_1505_on_expiry():
    t = R.run_session(call_day())["trade"]
    assert t["why"] == "square_off" and t["exit_row"] == K.SQ_ROW
    t = R.run_session(call_day(expiry=True))["trade"]
    assert t["why"] == "square_off" and t["exit_row"] == K.SQ_EXPIRY


def test_expiry_day_signal_after_square_off_is_not_taken():
    s = call_day(sig_row=K.SQ_EXPIRY - 1, expiry=True)
    assert R.run_session(s)["status"] == "no_signal"


def test_put_side_stop_is_close_above_high():
    ix = flat_ix()
    set_close(ix, 40, LO - 30)
    ix[41:] = LO - 30
    set_close(ix, 90, HI + 1)
    t = R.run_session(session(ix))["trade"]
    assert t["side"] == -1 and t["strike"] == 23950.0 and t["why"] == "stop" and t["exit_row"] == 91


def test_held_contract_not_the_rolling_atm():
    s = call_day()
    s.ix[100:] = 24400.0                    # index runs up: the ATM moves to 24400
    s.opts[(24400.0, "C")][:, :4] = 5.0      # a different contract's price must not be used
    s.opts[(24050.0, "C")][200:, :4] = 105.0
    t = R.run_session(s)["trade"]
    assert t["strike"] == 24050.0 and t["exit"] > 100


def test_missing_option_print_retries_then_no_fill():
    s = call_day()
    a = s.opts[(24050.0, "C")]
    a[41:43, :] = np.nan                    # 2 minutes missing: fills at the 3rd
    t = R.run_session(s)["trade"]
    assert t["entry_row"] == 43
    a[41:44, :] = np.nan                    # 3 minutes missing: no fill
    R.clear_cache(s)
    assert R.run_session(s)["status"] == "no_fill"


def test_missing_strike_is_no_fill():
    s = call_day()
    del s.opts[(24050.0, "C")]
    assert R.run_session(s)["status"] == "no_fill"


def test_stale_exit_when_contract_leaves_the_band():
    s = call_day()
    s.opts[(24050.0, "C")][200:, :] = np.nan
    t = R.run_session(s)["trade"]
    assert t["why"] == "stale" and t["exit_row"] == 199


def test_stray_high_cannot_hit_the_target():
    s = call_day(lot=25)                     # target +40 on a 100 premium: above the 20% stray cap
    s.opts[(24050.0, "C")][60, 1] = 400.0    # one stray print, closes stay at 100
    assert R.run_session(s)["trade"]["why"] == "square_off"


def test_cached_prices_match_a_fresh_read():
    s = call_day()
    s.opts[(24050.0, "C")][60:, :4] = 114.0
    t0 = R.run_session(s)["trade"]
    t1 = R.run_session(s)["trade"]            # served from the session cache
    R.clear_cache(s)
    assert t0 == t1 == R.run_session(s)["trade"]


def test_confirmed_highs_match_ref_close_row_by_row():
    a = opt()
    rng = np.random.default_rng(0)
    a[:, 3] = rng.uniform(50, 150, N)
    a[:, 1] = a[:, 3] * rng.uniform(1.0, 2.0, N)
    a[[0, 10, 11, 200], 3] = np.nan
    h = R.confirmed_highs(a)
    for r in range(N):
        ref = R.ref_close(a[:, 3].astype(float), r)
        want = min(float(a[r, 1]), ref * 1.2) if np.isfinite(ref) else float(a[r, 1])
        assert h[r] == pytest.approx(want)


def test_stray_open_never_helps_the_entry():
    s = call_day()
    s.opts[(24050.0, "C")][41, 0] = 20.0     # a stray low open at the fill
    t = R.run_session(s)["trade"]
    assert t["entry"] >= 100.0


def test_dated_charges_stt_rises_in_april_2026():
    a = R.charge(False, 100.0, 75, date(2026, 3, 31))
    b = R.charge(False, 100.0, 75, date(2026, 4, 1))
    assert b - a == pytest.approx(7500 * 0.0005, abs=0.02)


# ------------------------------------------------------------------------------------------------ context variants
def test_no_target_variant_holds_to_square_off():
    s = call_day()
    s.opts[(24050.0, "C")][60:, :4] = 114.0
    assert R.run_session(s, R.C1)["trade"]["why"] == "square_off"


def test_hard_stop_variant_ignores_the_range_stop():
    s = call_day()
    set_close(s.ix, 70, LO - 1)
    assert R.run_session(s, R.C2)["trade"]["why"] == "square_off"
    s.opts[(24050.0, "C")][80:, :4] = 80.0   # -20 x 75 = -1,500 < -1,000
    R.clear_cache(s)
    t = R.run_session(s, R.C2)["trade"]
    assert t["why"] == "hard_stop" and t["exit_row"] == 80 and t["exit"] <= 80.0


def test_limit_target_fills_at_the_target_when_traded_through():
    s = call_day()
    s.opts[(24050.0, "C")][60:, :4] = 114.0
    t = R.run_session(s, R.C3)["trade"]
    assert t["why"] == "target_limit" and t["exit"] == t["target_px"] and t["exit_row"] == 60


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("seed", range(5))
def test_look_ahead_guard_signal_and_entry_ignore_the_future(seed):
    rng = np.random.default_rng(seed)
    s = call_day(sig_row=int(rng.integers(20, 300)), close=24030.0 + float(rng.integers(0, 50)))
    t0 = R.run_session(s)["trade"]
    cut = t0["entry_row"] + 2               # the fill's stray guard may read the entry minute's next close
    for a in s.opts.values():
        a[cut:, :4] = rng.uniform(1, 500, size=(N - cut, 4))
    s.ix[cut:] = rng.uniform(23000, 25000, size=(N - cut, 4))
    R.clear_cache(s)
    t1 = R.run_session(s)["trade"]
    for k in ("sig_row", "side", "strike", "entry_row", "entry", "target_px"):
        assert t0[k] == t1[k]


def test_signal_never_reads_after_its_row():
    ix = flat_ix()
    set_close(ix, 40, HI + 10)
    r0 = R.find_signal(ix, HI, LO)
    ix[41:] = np.nan
    assert R.find_signal(ix, HI, LO) == r0


# ------------------------------------------------------------------------------------------------ twins
def test_twin_entries_stay_in_the_window_and_keep_the_side():
    s = call_day()
    rng = np.random.default_rng(1)
    for _ in range(50):
        t = R.twin_trade(s, 1, rng)
        assert K.FIRST_SIG + 1 <= t["entry_row"] <= K.LAST_SIG + 1 + K.FILL_TRIES and t["side"] == 1


def test_twins_p_value_shape():
    s = call_day()
    days = [R.run_session(s)]
    out = S.twins([s], days, n=20, seed=3)
    assert 0 < out["whole"]["p"] <= 1 and out["n"] == 20


# ------------------------------------------------------------------------------------------------ data clean-up and metrics
def test_foreign_rows_are_dropped():
    m = D.foreign_mask(np.array([24000.0, 24800.0, 52000.0, 24000.0]), np.array([24000.0, 24000.0, 24000.0, np.nan]))
    assert m.tolist() == [False, True, True, True]


def test_index_ref_carries_the_last_close():
    ix = np.full((N, 4), np.nan)
    ix[5, 3] = 100.0
    ref = D.index_ref(ix)
    assert np.isnan(ref[4]) and ref[5] == 100.0 and ref[9] == 100.0


def test_metrics_drawdown_streak_and_runs():
    assert S.max_drawdown([100, -50, -80, 30, -10]) == pytest.approx(-130)
    assert S.max_drawdown([-10, 5]) == pytest.approx(-10)
    assert S.longest_losing_streak([1, -1, -2, 0, 3, -1]) == 3
    assert S.worst_run([1, -5, -5, -5, -5, -5, 10], 5) == -25
    assert S.worst_run([], 5) == 0.0


def test_metrics_loss_buckets_and_empty_part():
    def tr(net, why="stop"):
        return dict(day=DAY, net=net, gross=net + 50, charges=50, why=why, expiry=False, side=1, lot=75)
    days = [dict(day=DAY, status="traded", trade=tr(x)) for x in (-200, -900, -1200, -3500, 800)]
    days.append(dict(day=DAY, status="no_signal", trade=None))
    m = S.metrics(days)
    assert m["trades"] == 5 and m["loss"]["n"] == 4 and m["loss"]["over_1000"] == 2
    assert m["loss"]["buckets"]["0-500"] == 1 and m["loss"]["buckets"]["3000-inf"] == 1
    assert m["worst_day"] == -3500
    e = S.metrics([dict(day=DAY, status="no_signal", trade=None)])
    assert e["trades"] == 0 and e["max_dd"] == 0.0
