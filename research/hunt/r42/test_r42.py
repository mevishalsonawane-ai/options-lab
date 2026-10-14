"""Tests for the R42 spike filters (synthetic data). Run: python -m pytest research/hunt/r42/test_r42.py -q"""
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
from spikes import flag_spikes, naive_sweep  # noqa: E402


def bars(prices, start="2026-07-08 09:15", step_min=1, side="C", strike=234000.0, offset=2, minutes=None):
    t0 = pd.Timestamp(start, tz="Asia/Kolkata")
    ts = [t0 + pd.Timedelta(minutes=m) for m in (minutes if minutes is not None else
                                                   [i * step_min for i in range(len(prices))])]
    p = np.asarray(prices, float)
    return pd.DataFrame({"ts": ts, "side": side, "strike": strike, "offset": offset, "close": p, "high": p, "low": p})


def test_single_minute_spike_is_flagged():
    d = bars([100, 101, 100, 102, 900, 101, 100, 99, 100])
    f = flag_spikes(d)
    assert f.f1.tolist() == [False] * 4 + [True] + [False] * 4


def test_downward_spike_is_flagged():
    d = bars([100, 101, 100, 5, 101, 100])
    assert flag_spikes(d).f1.tolist() == [False, False, False, True, False, False]


def test_sustained_jump_is_not_flagged():
    # a genuine re-pricing that stays: 100 -> 300 and holds
    d = bars([100] * 6 + [300] * 10)
    f = flag_spikes(d)
    assert not f.f1.any() and not f.f2.any()


def test_minute_before_big_repricing_is_kept():
    # a 3x re-pricing: with a pooled median the last 100 would look "too low"; one-sided medians keep it
    d = bars([100] * 6 + [300] * 6)
    assert not flag_spikes(d).f1.any()


def test_short_burst_that_reverts_is_flagged():
    d = bars([100] * 6 + [500, 510, 505] + [100] * 6)
    f = flag_spikes(d)
    assert f.f1.tolist() == [False] * 6 + [True] * 3 + [False] * 6


def test_empty_data():
    d = bars([])
    f = flag_spikes(d)
    assert f.empty and naive_sweep(d).empty


def test_gap_beyond_window_is_not_judged():
    # the neighbours of the 900 print are more than 15 minutes away: cannot judge, keep it
    d = bars([100, 900, 100], minutes=[0, 30, 60])
    f = flag_spikes(d)
    assert not f.judged.any() and not f.f1.any()


def test_gap_within_window_still_judged():
    # sparse trading: neighbours 10 minutes away still count
    d = bars([100, 100, 900, 100, 100], minutes=[0, 10, 20, 30, 40])
    f = flag_spikes(d)
    assert f.f1.tolist() == [False, False, True, False, False]


def test_first_and_last_minute_not_judged():
    d = bars([900, 100, 100, 100, 5])
    f = flag_spikes(d)
    assert not f.judged.iloc[0] and not f.judged.iloc[-1] and not f.f1.any()


def test_contracts_and_days_are_separate():
    a = bars([100, 100, 100], strike=234000.0)
    b = bars([900], strike=235000.0, minutes=[1])          # another strike, same minute: not a spike
    c = bars([900], start="2026-07-09 09:15")                # next day: not a neighbour
    d = pd.concat([a, b, c], ignore_index=True)
    assert not flag_spikes(d).f1.any()


def test_band_filter_catches_wild_high():
    d = bars([100, 101, 100, 102, 101])
    d.loc[2, "high"] = 400.0
    f = flag_spikes(d)
    assert not f.f1.any() and f.f2.tolist() == [False, False, True, False, False]


def test_duplicate_rows_do_not_break_alignment():
    d = bars([100, 101, 900, 100, 101])
    d = pd.concat([d, d.iloc[[2]]], ignore_index=True)  # same contract-minute twice
    f = flag_spikes(d)
    assert len(f) == len(d) and f.f1.iloc[2]


def test_naive_sweep_fires_on_strike_roll_but_f1_does_not():
    # rolling series ATM+2 moves from strike 234000 (price 100) to 235000 (price 40): a roll, not a bad print
    a = bars([100, 100, 100], strike=234000.0)
    b = bars([40, 40, 40], strike=235000.0, minutes=[3, 4, 5])
    d = pd.concat([a, b], ignore_index=True)
    assert naive_sweep(d).sum() == 1
    assert not flag_spikes(d).f1.any()
