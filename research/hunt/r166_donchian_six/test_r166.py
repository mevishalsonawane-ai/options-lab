"""R166 tests: every check's decision-time feature and its look-ahead wall (minutes at or after the entry row never
change a skip), the follow-through exit row, the depth cap, the gate routes and the p choice. Synthetic data only.
12 Nov 2026."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r166cfg as K  # noqa: E402
import r166lib as L  # noqa: E402


def test_counts():
    assert len(K.RULE_IDS) == 12 and len(K.TESTS) == 24
    assert len(K.TESTS) + K.N_PRIOR == 2290
    assert {s["fam"] for s in K.RULES.values()} == set(K.FAMILY)


def test_prev_hl_and_sr():
    p = np.full((4, 10), np.nan)
    p[1, 2:5] = [101, 105, 103]
    p[2, 2:5] = [99, 98, 100]
    assert L.prev_hl(p) == (105.0, 98.0)
    assert np.isnan(L.prev_hl(np.full((4, 10), np.nan))[0])
    assert L.sr1_skip("C", 104.8, 105.0, 98.0)                 # 0.19% below PDH
    assert not L.sr1_skip("C", 104.0, 105.0, 98.0)             # 0.96% away
    assert not L.sr1_skip("C", 105.5, 105.0, 98.0)             # already above
    assert L.sr1_skip("P", 98.2, 105.0, 98.0) and not L.sr1_skip("P", 97.0, 105.0, 98.0)
    assert not L.sr1_skip("C", float("nan"), 105.0, 98.0) and not L.sr1_skip("C", 104.9, float("nan"), 98.0)
    assert L.sr2_skip("C", 104.0, 105.0, 98.0) and not L.sr2_skip("C", 105.1, 105.0, 98.0)
    assert L.sr2_skip("P", 99.0, 105.0, 98.0) and not L.sr2_skip("P", 97.9, 105.0, 98.0)
    assert not L.sr2_skip("C", 104.0, float("nan"), 98.0)


def test_signal_extreme_and_ft_row():
    hi = np.array([10, 11, 12, 13, 14, 15, 14, 14, 14, 16, 14.0])
    lo = hi - 2
    assert L.signal_extreme(hi, lo, 6, "C") == 15.0 and L.signal_extreme(hi, lo, 6, "P") == 9.0
    assert L.ft_exit_row(hi, lo, 6, 3, 15.0, "C", 50) == 9           # 14, 14, 14: never above 15
    assert L.ft_exit_row(hi, lo, 6, 4, 15.0, "C", 50) == -1          # minute 9 traded 16
    assert L.ft_exit_row(hi, lo, 6, 3, 15.0, "C", 9) == -1           # the exit row is the square-off
    assert L.ft_exit_row(hi, lo, 6, 3, float("nan"), "C", 50) == -1
    assert L.ft_exit_row(hi, lo, 6, 3, 12.5, "P", 50) == -1          # lows 12 < 12.5
    assert L.ft_exit_row(hi, lo, 6, 3, 11.0, "P", 50) == 9


def test_signal_extreme_ignores_entry_minute_and_later():
    hi = np.arange(20.0)
    lo = hi - 1
    hi2 = hi.copy()
    hi2[10:] = 999
    assert L.signal_extreme(hi, lo, 10, "C") == L.signal_extreme(hi2, lo, 10, "C") == 9.0


def test_volume_ratio_and_wall():
    v = np.full(40, 10.0)
    v[35:40] = 30.0
    assert L.volume_ratio(v, 40) == 3.0                    # 150 / median 50
    assert L.v2_skip(L.volume_ratio(v, 40)) and not L.v1_skip(L.volume_ratio(v, 40))
    assert L.volume_ratio(np.r_[v, np.full(10, 1e9)], 40) == 3.0      # later minutes never count
    assert L.volume_ratio(v, 22) is None                   # 3 earlier blocks only
    assert L.volume_ratio(np.zeros(40), 40) is None
    assert not L.v1_skip(None) and not L.v2_skip(None)
    assert L.v1_skip(0.5)


def test_buyer_seller_checks():
    assert L.iv_falling(0.14, 0.15) and not L.iv_falling(0.16, 0.15)
    assert not L.iv_falling(None, 0.15) and not L.iv_falling(0.14, None)
    assert L.other_side_bid(101.0, 100.0) and not L.other_side_bid(99.0, 100.0)
    assert not L.other_side_bid(float("nan"), 100.0)


def test_traps():
    cands = [dict(er=20, side="P"), dict(er=45, side="C"), dict(er=80, side="C"), dict(er=100, side="P")]
    assert L.whipsaw(cands, 1)                              # P at 20 within 30 min of 45
    assert not L.whipsaw(cands, 2)                          # same side only before
    assert L.whipsaw(cands, 3)                              # C at 80 is 20 minutes before the P at 100
    hi = np.r_[np.full(5, 10.0), np.full(5, 12.0)]
    lo = np.r_[np.full(5, 9.0), np.full(5, 8.0)]
    assert L.outside_bar(hi, lo, 10)
    assert not L.outside_bar(np.r_[np.full(5, 10.0), np.full(5, 9.5)], lo, 10)
    assert not L.outside_bar(hi, lo, 9)
    hi2 = np.r_[hi, np.full(5, 0.0)]
    assert L.outside_bar(hi2, np.r_[lo, np.full(5, 99.0)], 10)      # entry minute and later ignored


def test_cap_depth():
    assert L.cap_depth("C", 24800.0, 24900.0) == 24900.0 and L.cap_depth("C", 25000.0, 24900.0) == 25000.0
    assert L.cap_depth("P", 25200.0, 25100.0) == 25100.0 and L.cap_depth("P", 25000.0, 25100.0) == 25000.0
    assert L.cap_depth("C", None, 24900.0) is None


def test_routes_gates_and_p():
    st = lambda w, n, dd, tr=100: dict(win=w, net=n, max_dd=dd, trades=tr)   # noqa: E731
    base = dict(design=st(0.45, 100, 50), wf=st(0.45, 100, 50), final=st(0.45, 100, 50))
    prof = dict(design=st(0.44, 120, 54), wf=st(0.44, 120, 54), final=st(0.44, 130, 55))
    r = L.routes(prof, base)
    assert r == dict(win=False, profit=True)
    assert L.gates(prof, base, 0.05)["passed"]
    assert not L.routes(dict(prof, final=st(0.44, 130, 56)), base)["profit"]          # DD 12% larger
    win = dict(design=st(0.51, 100, 80), wf=st(0.50, 100, 80), final=st(0.51, 101, 80))
    assert L.routes(win, base) == dict(win=True, profit=False)
    assert L.p_of(dict(win=True, profit=False), 0.01, 0.2) == 0.2
    assert L.p_of(dict(win=True, profit=True), 0.01, 0.2) == 0.01
    g = L.gates(dict(prof, design=st(0.4, -1, 50)), base, 0.05)
    assert "design: net not positive" in g["fails"]
    assert not L.gates(prof, base, 0.2)["passed"]
    assert L.gates(prof, dict(base, final=st(0.9, 999, 1)), 0.05, periods=("wf",))["passed"]   # family A: wf only
