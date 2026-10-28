"""R125 tests (PREREG section 6): pivots confirm late, levels/pools use confirmed pivots, sweeps need their failure bars,
participant data is lagged, the volume profile and option flow read the past only, and hand-made cases.

    python -m pytest research/hunt/r125_levels_flow/test_r125.py -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r125feat as F  # noqa: E402


def _flat(n=60, p=100.0):
    return np.full(n, p + 0.5), np.full(n, p - 0.5)


def test_pivot_found_and_confirmed_k_bars_later():
    h, l = _flat()
    h[20] = 110.0
    l[30] = 90.0
    hi, lo = F.pivots(h, l, k=5)
    assert (20, 110.0, 25) in hi and (30, 90.0, 35) in lo


def test_cluster_and_sr_features():
    lv = F.cluster([100.0, 101.0, 130.0, 131.0, 132.0], gap=5)
    assert lv == [(100.5, 2), (131.0, 3)]
    assert F.cluster([100.0, 104.0, 108.0, 112.0], gap=5) == [(102.0, 2), (110.0, 2)]   # no chaining
    f = F.sr_features(lv, hi_t=110.0, lo_t=108.0, m=30.0, h5=140.0, l5=95.0)
    assert f["sr_res_dist"] == pytest.approx(21 / 30) and f["sr_res_str"] == 3
    assert f["sr_sup_dist"] == pytest.approx(7.5 / 30) and f["sr_n_below"] == 1 and f["sr_n_above"] == 1


def test_pool_untaken_then_taken():
    h, _ = _flat()
    piv = [(10, 120.0, 15), (20, 121.0, 25)]
    h[10], h[20] = 120.0, 121.0
    assert F.pools(piv, 5.0, 30, h, "high", []) == [121.0]
    assert F.pools(piv, 5.0, 22, h, "high", []) == []                 # the second pivot not confirmed yet
    h[28] = 122.0                                                      # traded through after confirmation
    assert F.pools(piv, 5.0, 30, h, "high", []) == []


def test_sweep_needs_failure_bars_and_wicks_only():
    h, l = _flat(60)
    lv = lambda j: [105.0]                                             # noqa: E731
    none = lambda j: []                                                # noqa: E731
    h[40] = 107.0                                                      # pokes above 105; next 3 highs 100.5 < 105
    s = F.sweeps(h, l, 43, lv, none, 30.0)
    assert s["lq_sweep_hi"] == 1.0 and s["lq_since_sweep"] == 3 and s["lq_poke"] == pytest.approx(2 / 30)
    assert F.sweeps(h, l, 42, lv, none, 30.0)["lq_sweep_hi"] == 0.0    # failure bars not all closed yet
    h[42] = 106.0                    # bar 40's poke is retaken at 42 (no sweep at 40); 42 itself fails by 45
    s = F.sweeps(h, l, 44, lv, none, 30.0)
    assert s["lq_sweep_hi"] == 0.0                                     # 40 cancelled, 42 not yet confirmed
    s = F.sweeps(h, l, 45, lv, none, 30.0)
    assert s["lq_sweep_hi"] == 1.0 and s["lq_since_sweep"] == 3


def test_hilo_net_counts_new_wick_extremes():
    h, l = _flat(40)
    h[30], h[32] = 101.0, 102.0
    l[35] = 99.0
    assert F.hilo_net(h, l, 39) == 1.0


def test_volume_profile_poc_value_area_and_node():
    lo = np.array([100.0, 100.0, 110.0])
    hi = np.array([104.9, 104.9, 114.9])
    v = np.array([100.0, 100.0, 10.0])
    hist = F.spread_hist(lo, hi, v, 5.0, 0, 40)
    assert hist[20] == 200.0 and hist[22] == 10.0
    f = F.profile_features(hist, 5.0, 0, 111.0, 112.0, 30.0)
    assert f["vo_poc"] == pytest.approx((111.0 - 102.5) / 30)
    assert f["vo_in_va"] == 0.0 and f["vo_node"] < 1


def test_participants_lagged_one_session():
    P = pd.DataFrame({"fl_fii_ls": [0.4, 0.6]}, index=[date(2025, 1, 1), date(2025, 1, 2)])
    assert F.lagged(P, date(2025, 1, 2))["fl_fii_ls"] == 0.4             # the 2 Jan file is not known on 2 Jan
    assert np.isnan(F.lagged(P, date(2025, 1, 1))["fl_fii_ls"])


def _chain(seed=0):
    rng = np.random.default_rng(seed)
    return {k: np.abs(rng.normal(100, 10, (375, 21, 2))) for k in ("close", "oi", "iv", "volume")}


def test_option_flow_and_minute_volume_read_past_only():
    ch = _chain()
    t = 100
    a = F.option_flow(ch, t)
    ch2 = {k: v.copy() for k, v in ch.items()}
    for v in ch2.values():
        v[t + 1:] = 1e6
    b = F.option_flow(ch2, t)
    for k in a:
        assert a[k] == pytest.approx(b[k])
    v1, v2 = F.minute_volume(ch), F.minute_volume(ch2)
    np.testing.assert_allclose(v1[:t + 1], v2[:t + 1])


def test_buildup_signs():
    ch = {k: np.full((375, 21, 2), 100.0) for k in ("close", "oi", "iv", "volume")}
    ch["close"][100, F.ATM3, 0] = 110.0                                # calls up
    ch["oi"][100, F.ATM3, 0] = 120.0                                   # with OI up -> bought: bullish
    ch["close"][100, F.ATM3, 1] = 90.0                                 # puts down
    ch["oi"][100, F.ATM3, 1] = 120.0                                   # with OI up -> written: bullish
    assert F.option_flow(ch, 100)["fl_buildup"] == 1.0
    ch["close"][100, F.ATM3, 1] = 110.0                                # puts bought: bearish -> the average is 0
    assert F.option_flow(ch, 100)["fl_buildup"] == 0.0


def test_new_cols_cover_groups():
    assert set(F.NEW_COLS) == set(sum((list(v) for v in F.GROUPS.values()), []))
    assert len(F.NEW_COLS) == 31
