"""R130 tests: bars, wick swings, the sweep -> MSS -> FVG -> engulfing sequence on a hand-built session (both sides),
targets, wick exits (stop first), the look-ahead guard, option pricing and the holdout lock.

    python -m pytest research/hunt/r130_sweep_mss_fvg -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r130 as R  # noqa: E402

N = R.N

# A hand-built 5-minute session (o, h, l, c per bar). Worked out by hand (k = 3):
#   swing highs 1010 (bar 3) and 1010.2 (bar 7): equal highs (within 0.03%); swing high 1005 (bar 13) = the recent high;
#   swing low 990 (bar 10) = the recent low; bar 17 sweeps it (989); bar 20 closes 1007.5 above 1005 (the MSS);
#   FVGs in the leg 17..20: (17,18,19) 996-995 = 1 and (18,19,20) 1002-998 = 4 -> the larger: 998..1002, stop 997.8004;
#   bar 22 pulls back into it (1001.5, bearish); bar 23 is a bullish engulfing -> buy at row 120 (bar 24's open 1005.5);
#   target 1010.2 (the higher of the equal highs), reached by bar 26's high -> row 130.
BARS = [
    (1000, 1001, 999, 1000), (1000, 1001, 999, 1000), (1000, 1001, 999, 1000),
    (1000, 1010, 1000, 1005), (1005, 1006, 1000, 1001), (1001, 1002, 998, 999), (999, 1000, 995, 996),
    (996, 1010.2, 996, 1009), (1009, 1009, 1000, 1001), (1001, 1002, 992, 993), (993, 994, 990, 991),
    (991, 1003, 991, 1002), (1002, 1004, 1000, 1003), (1003, 1005, 1001, 1002), (1002, 1003, 997, 998),
    (998, 999, 994, 995), (995, 996, 993, 994),
    (994, 995, 989, 992),                                    # 17 the sweep
    (992, 998, 991, 997), (997, 1004, 996, 1003),
    (1003, 1008, 1002, 1007.5),                              # 20 the MSS close
    (1007.5, 1008, 1004, 1005),
    (1005, 1005.5, 1001.5, 1002),                            # 22 the pullback into the FVG
    (1001.8, 1006, 1001.6, 1005.5),                          # 23 the bullish engulfing
    (1005.5, 1007, 1004, 1006), (1006, 1008, 1005, 1007),
    (1007, 1011, 1006, 1010),                                # 26 the target
]
BARS += [(1006, 1007, 1005, 1006)] * (75 - len(BARS))


def minutes(bars=BARS, mirror_at=None):
    """5-minute bars -> one session of minutes: every minute carries the bar's high and low; the first minute opens
    at the bar's open, the others at its close; every minute closes at the bar's close."""
    o, h, l, c = (np.full((1, N), np.nan) for _ in range(4))
    for b, (bo, bh, bl, bc) in enumerate(bars):
        rows = slice(5 * b, 5 * b + 5)
        h[0, rows], l[0, rows], c[0, rows] = bh, bl, bc
        o[0, rows] = bc
        o[0, 5 * b] = bo
    if mirror_at is not None:                                 # the mirror image: a bearish session
        o, h, l, c = mirror_at - o, mirror_at - l, mirror_at - h, mirror_at - c
    return SimpleNamespace(days=[date(2025, 6, 10)], o=o, h=h, l=l, c=c)


def run_v(v, g=None):
    return R.scan(g or minutes(), R.VARIANTS[v], set(), lambda d: True)


def test_make_bars_aggregates_minutes():
    g = minutes()
    B = R.make_bars(g.o, g.h, g.l, g.c, 5)
    assert len(B.day) == 75
    assert (B.o[23], B.h[23], B.l[23], B.c[23]) == (1001.8, 1006, 1001.6, 1005.5)
    assert B.start[23] == 115 and B.end[23] == 119
    g.h[0, 10:15] = g.l[0, 10:15] = g.o[0, 10:15] = g.c[0, 10:15] = np.nan    # a bar with no data is left out
    assert len(R.make_bars(g.o, g.h, g.l, g.c, 5).day) == 74


def test_swing_rule_strict_left_equal_right():
    h = np.array([1, 2, 3, 5, 5, 2, 1, 0, 0.0])
    assert list(R.swing_highs(h, 3)) == [3]                  # bar 4 equals bar 3 on its left: not a swing
    assert list(R.swing_highs(np.array([1, 2, 3, 4, 5, 6, 7.0]), 3)) == []


def test_fvg_largest_and_minimum_size():
    hh = np.array([10, 11, 12, 13, 14.0])
    ll = np.array([9, 10, 11.05, 13.5, 13.6])
    # gaps: (0,1,2) 11.05-10 = 1.05; (1,2,3) 13.5-11 = 2.5; (2,3,4) 13.6-12 = 1.6
    assert R.find_fvg(hh, ll, 0, 4) == (13.5, 11.0)
    assert R.find_fvg(np.array([10, 10, 10.0]), np.array([9, 9, 10.05]), 0, 2) is None   # 0.05 < 2 ticks


def test_eq_target_equal_highs_else_nearest_swing():
    assert R.eq_target([1010.0, 1010.2, 1005.0], 1003.0) == 1010.2             # equal highs first, as stated
    assert R.eq_target([1010.0, 1012.0, 1005.0], 1003.0) == 1005.0             # no pair: the nearest swing high
    assert R.eq_target([1010.0, 1010.2, 1005.0], 1005.5) == 1010.2             # equal highs: the higher of the pair
    assert R.eq_target([1010.0, 1012.0], 1005.5) == 1010.0                     # 2 points apart: not equal at 1010
    assert R.eq_target([1000.0], 1005.5) is None


def test_exit_stop_first_target_time_and_gap():
    h, l, o = np.full(N, 101.0), np.full(N, 99.0), np.full(N, 100.0)
    h[30], l[30] = 120.0, 80.0
    assert R.exit_on(h, l, o, 20, 90.0, 110.0, 364)[1] == "stop"
    h2, l2 = np.full(N, 101.0), np.full(N, 99.0)
    h2[30] = 111.0
    assert R.exit_on(h2, l2, o, 20, 90.0, 110.0, 364) == (30, "target", 110.0)
    assert R.exit_on(np.full(N, 101.0), l2, o, 20, 90.0, 110.0, 364)[1] == "time"
    l3, o3 = np.full(N, 99.0), np.full(N, 100.0)
    l3[25], o3[25] = 85.0, 86.0                              # gapped through the stop: filled at the open
    assert R.exit_on(np.full(N, 101.0), l3, o3, 20, 90.0, 110.0, 364) == (25, "stop", 86.0)


def test_v1_finds_the_hand_built_buy():
    trades, meta = run_v("V1")
    assert len(trades) == 1
    t = trades[0]
    assert (t["side"], t["entry"], t["exit"], t["why"]) == (1, 120, 130, "target")
    assert t["ref"] == 1005.5
    assert t["pts"] == pytest.approx(4.7)
    assert t["stop_pts"] == pytest.approx(1005.5 - (998 - 0.0002 * 998))
    assert meta["census"]["sweeps"] >= 1 and meta["census"]["fvg"] >= 1 and meta["census"]["triggered"] == 1


def test_mirror_finds_the_same_sell():
    trades, _ = run_v("V1", minutes(mirror_at=2000.0))
    assert len(trades) == 1
    t = trades[0]
    assert (t["side"], t["entry"], t["exit"], t["why"]) == (-1, 120, 130, "target")
    assert t["ref"] == pytest.approx(2000 - 1005.5)
    assert t["pts"] == pytest.approx(4.7)


def test_no_engulfing_enters_on_the_first_touch_minute():
    trades, _ = run_v("V3")
    t = trades[0]
    assert t["entry"] == 111                                  # bar 22's first minute touches 1002 -> the next minute
    assert t["pts"] == pytest.approx(1010.2 - 1002)


def test_2r_target_and_time_exit():
    t = run_v("V4")[0][0]
    assert t["tgt_pts"] == pytest.approx(2 * t["stop_pts"])
    assert t["why"] == "time" and t["exit"] == R.K.K3.EXIT_LAST


def test_no_engulfing_no_trade_and_stop_before_trigger_drops():
    bars = list(BARS)
    bars[23] = (1002, 1003, 1001.6, 1002.5)                  # no engulfing candle any more
    assert run_v("V1", minutes(bars))[0] == []
    bars = list(BARS)
    bars[22] = (1005, 1005.5, 997.0, 1002)                   # the pullback runs through the stop first
    assert run_v("V1", minutes(bars))[0] == []


def test_close_back_variant_needs_close_inside():
    bars = list(BARS)
    bars[17] = (994, 995, 989, 989.5)                         # closes below the swept low
    assert run_v("V1", minutes(bars))[0] != []
    assert run_v("V6", minutes(bars))[0] == []
    assert run_v("V6")[0] != []                              # the original sweep closes back at 992: kept


def test_look_ahead_guard_future_bars_do_not_change_the_entry():
    full = run_v("V1")[0][0]
    g = minutes()
    for a in (g.o, g.h, g.l, g.c):
        a[0, full["entry"] + 1:] = np.nan                    # remove everything after the entry minute
    cut = run_v("V1", g)[0][0]
    for k in ("side", "entry", "ref", "stop_pts", "tgt_pts"):
        assert cut[k] == full[k]


def test_late_and_blocked_days_do_not_trade():
    assert R.scan(minutes(), R.VARIANTS["V1"], {0}, lambda d: True)[0] == []
    trades, meta = R.scan(minutes(), R.VARIANTS["V1"], set(), lambda d: True, last_row=100)
    assert trades == [] and meta["skips"]["late"] == 1


def test_option_trade_prices_traded_bars_only():
    a = np.full((N, 5), np.nan)
    a[:, :4] = 100.0
    a[:, 4] = 6500
    a[40:51] = np.nan
    a[39, 3] = 110.0
    r = R.option_trade(a, 20, 50, "time", 65, date(2025, 6, 10), "NIFTY")
    assert r[0] > 500 and r[1] < r[0]                       # 2x slippage costs more
    a[20] = np.nan
    assert R.option_trade(a, 20, 50, "time", 65, date(2025, 6, 10), "NIFTY") is None


def test_holdout_lock_reads_once(tmp_path):
    R.holdout_lock("start", tmp_path, tmp_path)
    R.holdout_lock("done", tmp_path, tmp_path)
    with pytest.raises(R.HoldoutError):
        R.holdout_lock("start", tmp_path, tmp_path)


def test_c3_two_proportion():
    out = R.c3_test({"swept": [60, 40, 5], "not_swept": [50, 50, 5]})
    assert out["up_first_swept"] == 0.6 and out["n_not_swept"] == 100 and 0 < out["p_two_sided"] < 0.2


# ---- Amendment 1: wick-only versions ---------------------------------------------------------------------------------
def _wick_bars():
    """BARS with bar 23's low at 1001.4: its wicks now engulf bar 22's (high 1006 > 1005.5, low 1001.4 <= 1001.5)."""
    bars = list(BARS)
    bars[23] = (1001.8, 1006, 1001.4, 1005.5)
    return bars


def run_w(v, g):
    return R.scan(g, R.WICK_VARIANTS[v], set(), lambda d: True)


def test_w1_wick_break_and_wick_engulfing():
    assert run_w("W1", minutes())[0] == []                   # no candle's wicks engulf the previous one's in time
    t = run_w("W1", minutes(_wick_bars()))[0][0]
    # wick MSS: bar 20's high 1008 >= 1005 + 1 tick; the same FVG 998..1002; trigger bar 23 -> buy at row 120
    assert (t["side"], t["entry"], t["exit"], t["why"]) == (1, 120, 130, "target")
    assert t["stop_pts"] == pytest.approx(1005.5 - (998 - 0.0002 * 998))


def test_w1_mss_needs_only_a_wick():
    bars = _wick_bars()
    bars[20] = (1003, 1008, 1002, 1004.0)                    # closes BELOW the recent high 1005: no close-based MSS
    t = run_w("W1", minutes(bars))[0][0]
    assert t["entry"] == 120
    capped = [(min(o, 1005.02), min(h, 1005.02), min(l, 1005.02), min(c, 1005.02)) if 18 <= b <= 29 else (o, h, l, c)
              for b, (o, h, l, c) in enumerate(_wick_bars())]     # every wick after the sweep stops short of 1005.05
    trades, meta = run_w("W1", minutes(capped))
    assert trades == [] and meta["census"]["mss"] == 0


def test_wick_versions_never_read_opens_or_closes():
    """Scramble every minute's open and close inside its high-low range (all but the entry minute's open, which is the
    fill price): the wick-only trade must not change."""
    rng = np.random.default_rng(0)
    base = run_w("W1", minutes(_wick_bars()))[0][0]
    for _ in range(5):
        g = minutes(_wick_bars())
        u1, u2 = rng.random(N), rng.random(N)
        keep = g.o[0, base["entry"]]
        g.o[0] = g.l[0] + u1 * (g.h[0] - g.l[0])
        g.c[0] = g.l[0] + u2 * (g.h[0] - g.l[0])
        g.o[0, base["entry"]] = keep
        t = run_w("W1", g)[0][0]
        for k in ("side", "entry", "exit", "why", "stop_pts", "tgt_pts", "pts"):
            assert t[k] == base[k]


def test_wick_mirror_and_wick_census_runs():
    t = run_w("W1", minutes(_wick_bars(), mirror_at=2000.0))[0][0]
    assert (t["side"], t["entry"], t["exit"]) == (-1, 120, 130)
    c = R.mss_census(minutes(_wick_bars()), lambda d: True, wick=True)
    assert sum(c["swept"]) + sum(c["not_swept"]) >= 1


def test_wick_holdout_lock_is_separate(tmp_path):
    R.holdout_lock("start", tmp_path, tmp_path)
    R.holdout_lock("done", tmp_path, tmp_path)
    R.holdout_lock("start", tmp_path, tmp_path, suffix="_wick")          # its own lock
    R.holdout_lock("done", tmp_path, tmp_path, suffix="_wick")
    with pytest.raises(R.HoldoutError):
        R.holdout_lock("start", tmp_path, tmp_path, suffix="_wick")
