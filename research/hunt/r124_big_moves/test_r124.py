"""R124 tests (PREREG section 7): the wick zigzag, the factors and level inputs read the past only, the wick move
labels, the index-exit option simulator, training-only thresholds and the holdout lock. Synthetic data only.

    python -m pytest research/hunt/r124_big_moves/test_r124.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r124cfg as K  # noqa: E402
import r124zig as Z  # noqa: E402
import r124data as RD  # noqa: E402
import r123data as D3  # noqa: E402

DAY = date(2025, 6, 10)


def _path(points):
    """Bars from a list of (high, low)."""
    h = np.array([p[0] for p in points], float)
    l = np.array([p[1] for p in points], float)
    return h, l


# ------------------------------------------------------------------------------------------------ zigzag
def test_zigzag_uses_wicks_and_ends_on_turnback():
    # low 100 at bar 1, high 140 at bar 4 (wick), then a bar whose LOW is 10 below 140 -> up leg confirmed at bar 5
    h, l = _path([(105, 101), (102, 100), (115, 108), (125, 118), (140, 126), (133, 130), (131, 120)])
    legs = Z.zigzag(h, l, 10)
    up = legs[0]
    assert up["dir"] == 1 and up["start"] == 1 and up["start_px"] == 100 and up["end"] == 4 and up["end_px"] == 140
    assert up["confirm"] == 5 and up["size"] == 40
    assert legs[-1]["dir"] == -1 and legs[-1]["confirm"] is None and legs[-1]["end_px"] == 120


def test_zigzag_closes_do_not_matter():
    """Only highs and lows enter: the same wicks give the same legs whatever the closes were."""
    h, l = _path([(101, 99), (130, 100), (131, 118), (125, 105)])
    a = Z.zigzag(h, l, 10)
    b = Z.zigzag(h.copy(), l.copy(), 10)
    assert a == b and a[0]["size"] == 32


def test_zigzag_small_moves_and_gaps():
    h, l = _path([(101, 99), (np.nan, np.nan), (104, 98), (103, 99)])
    legs = Z.zigzag(h, l, 10)
    assert legs == []
    h, l = _path([(100, 90), (95, 91), (130, 94)])          # one long bar: direction from a later bar
    legs = Z.zigzag(h, l, 10)
    assert legs[0]["dir"] == 1 and legs[0]["start_px"] == 90 and legs[0]["end_px"] == 130


def test_zigzag_cascade_in_one_bar_window():
    h, l = _path([(100, 99), (140, 100), (141, 128), (139, 101), (160, 102)])
    legs = Z.zigzag(h, l, 10)
    assert [g["dir"] for g in legs[:3]] == [1, -1, 1]
    assert legs[0]["end_px"] == 141 and legs[1]["end_px"] == 101


def test_thresholds_scale_by_price():
    assert Z.thresholds(25000, 25000) == (30.0, 10.0)
    t, r = Z.thresholds(50000, 25000)
    assert t == 60.0 and r == 20.0
    assert np.isnan(Z.thresholds(50000, np.nan)[0])


# ------------------------------------------------------------------------------------------------ move labels
def test_move_label_wick_to_wick():
    h = np.full(40, 100.5)
    l = np.full(40, 99.5)
    assert RD.move_label(h, l, 0, 15, 30) == (K.NEITHER, None)
    l[3] = 90.0                                             # low wick at bar 3
    h[7] = 120.5                                            # high wick 30.5 above it at bar 7 -> UP at 7
    assert RD.move_label(h, l, 0, 15, 30) == (K.UP, 7)
    assert RD.move_label(h, l, 0, 6, 30)[0] == K.NEITHER    # window ends before the completing bar
    h2, l2 = np.full(40, 100.5), np.full(40, 99.5)
    h2[2], l2[2] = 131.0, 99.0                              # one bar spans 32 points: UP and DOWN both -> unclear
    assert RD.move_label(h2, l2, 0, 15, 30)[0] == K.UNCLEAR


def test_move_label_ignores_bars_up_to_t():
    h = np.full(40, 100.5)
    l = np.full(40, 99.5)
    l[5] = 70.0                                             # at t itself: not in the window
    h[8] = 101.0
    assert RD.move_label(h, l, 5, 20, 30)[0] == K.NEITHER


# ------------------------------------------------------------------------------------------------ the exit simulator
def _opt(price=100.0, n=K.N):
    a = np.full((n, 5), np.nan)
    a[:, :4] = price
    a[:, 4] = 5000
    return a


def test_index_exit_call_target_stop_time_and_straddle():
    io = np.full(K.N, 25000.0)
    ih, il = io + 5, io - 5
    ca, pa = _opt(100.0), _opt(100.0)
    r = RD.index_exit_trade({}, (("C", ca),), ih, il, io, 10, 25, 30, 65, DAY, "NIFTY")
    assert r["why"] == "time" and r["exit_row"] == 25 and r["net"] < 0
    ih2 = ih.copy()
    ih2[15] = 25031.0                                       # the HIGH wick touches +30 at bar 15
    ca2 = ca.copy()
    ca2[15, 3] = 115.0
    r = RD.index_exit_trade({}, (("C", ca2),), ih2, il, io, 10, 25, 30, 65, DAY, "NIFTY")
    assert r["why"] == "target" and r["exit_row"] == 15 and r["net"] > 800
    il2 = il.copy()
    il2[15] = 24969.0                                       # both wicks in one bar: the stop
    r = RD.index_exit_trade({}, (("C", ca2),), ih2, il2, io, 10, 25, 30, 65, DAY, "NIFTY")
    assert r["why"] == "stop"
    r = RD.index_exit_trade({}, (("P", pa),), ih2, il, io, 10, 25, 30, 65, DAY, "NIFTY")
    assert r["why"] == "stop" and r["exit_row"] == 15       # a put is stopped by the high wick
    r = RD.index_exit_trade({}, (("C", ca2), ("P", pa)), ih2, il, io, 10, 25, 30, 65, DAY, "NIFTY", straddle=True)
    assert r["why"] == "touch" and r["exit_row"] == 15


def test_index_exit_needs_traded_entry_and_uses_last_traded_exit():
    io = np.full(K.N, 25000.0)
    ih, il = io + 5, io - 5
    ca = _opt(100.0)
    ca[11] = np.nan
    assert RD.index_exit_trade({}, (("C", ca),), ih, il, io, 10, 25, 30, 65, DAY, "NIFTY") is None
    ca = _opt(100.0)
    ca[20:26] = np.nan
    ca[19, 3] = 104.0
    r = RD.index_exit_trade({}, (("C", ca),), ih, il, io, 10, 25, 30, 65, DAY, "NIFTY")
    assert r["exit_row"] == 25 and r["net"] > 0             # sold at the last traded close (104) before bar 25


# ------------------------------------------------------------------------------------------------ point in time
def _grid(days, base, rng):
    c = rng.normal(0, base * 0.0004, (len(days), K.N)).cumsum(1) + base
    o = np.concatenate([c[:, :1], c[:, :-1]], 1)
    h = np.maximum(o, c) + abs(rng.normal(0, base * 0.0002, c.shape))
    lo = np.minimum(o, c) - abs(rng.normal(0, base * 0.0002, c.shape))
    return D3.Grid(list(days), o, h, lo, c)


def _market():
    rng = np.random.default_rng(0)
    days = [DAY - timedelta(days=29 - k) for k in range(30)]
    grids = {u: _grid(days, b, rng) for u, b in zip(K.UNDS, (25000, 55000, 26000, 13000, 82000))}
    return grids, _grid(days, 14.0, rng)


def test_level_features_read_bar_t_and_before_only():
    grids, vix = _market()
    i, t = 25, 154
    I = D3.IndexData("NIFTY", grids, vix)
    conf = {100: (90, 1), 200: (190, -1)}
    f1 = RD.level_features(I, i, t, 30.0, conf)
    g = grids["NIFTY"]
    for a in (g.h, g.l, g.o, g.c):
        a[i, t + 1:] += 500
    I2 = D3.IndexData("NIFTY", grids, vix)
    f2 = RD.level_features(I2, i, t, 30.0, conf)
    for k in f1:
        assert (np.isnan(f1[k]) and np.isnan(f2[k])) or f1[k] == pytest.approx(f2[k]), k
    assert f1["lv_since_move"] == t - 90 and f1["lv_last_dir"] == 1     # the leg confirmed at 200 is not known yet


def test_span_dist_zero_inside_wicks():
    assert RD.span_dist(99, 101, 100, 30) == 0.0
    assert RD.span_dist(99, 101, 110, 30) == pytest.approx(9 / 30)


def test_factors_read_before_start_only():
    grids, vix = _market()
    i, p = 25, 120
    I = D3.IndexData("NIFTY", grids, vix)
    others = {u: (np.stack([grids[u].h[k] for k in range(30)]), np.stack([grids[u].l[k] for k in range(30)]))
              for u in K.UNDS if u != "NIFTY"}
    ctx = dict(is_expiry=False, last_confirm=lambda p_: None)
    f1 = Z.factors_at(I, i, p, 1, 30.0, ctx, None, others)
    g = grids["NIFTY"]
    for a in (g.h, g.l, g.o, g.c):
        a[i, p + 1:] += 500                                  # after the start bar
    others2 = {u: (oh.copy(), ol.copy()) for u, (oh, ol) in others.items()}
    for oh, ol in others2.values():
        oh[i, p + 1:] += 999
        ol[i, p + 1:] += 999
    I2 = D3.IndexData("NIFTY", grids, vix)
    f2 = Z.factors_at(I2, i, p, 1, 30.0, ctx, None, others2)
    for k in f1:
        a, b = f1[k], f2[k]
        assert (isinstance(a, float) and np.isnan(a) and np.isnan(b)) or a == pytest.approx(b), k


# ------------------------------------------------------------------------------------------------ lock and rules
def test_holdout_lock(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "OUT", tmp_path)
    monkeypatch.setattr(K, "RESULTS", tmp_path / "results")
    with pytest.raises(Z.HoldoutError):
        RD.build("holdout")
    Z.holdout_gate("start")
    assert Z.holdout_open()
    Z.holdout_gate("done")
    with pytest.raises(Z.HoldoutError):
        Z.holdout_gate("start")


def test_top_threshold_from_training_rows_only():
    import r124rules as RR
    tr = np.linspace(0, 1, 1001)
    te = np.full(500, 5.0)
    assert RR.top_threshold(tr) == pytest.approx(0.9)
    assert RR.top_threshold(tr) == RR.top_threshold(tr.copy())
    assert te.min() > RR.top_threshold(tr)


def test_side_rule_direction_share_and_portfolio():
    import r124rules as RR
    F = pd.DataFrame(dict(und="NIFTY", day=DAY, row=[4, 9, 14, 19], p_up15=[.5, .2, .30, .1], p_dn15=[.2, .5, .25, .1],
                          tr15_C_net=[100.0, 1, 1, 1], tr15_P_net=[1.0, 200, 1, 1], tr15_C_netx2=0.0, tr15_P_netx2=0.0,
                          tr15_C_exit=[6, 11, 16, 21], tr15_P_exit=[6, 11, 16, 21], tr15_C_why="t", tr15_P_why="t",
                          tr15_S_net=1.0, tr15_S_netx2=0.0, tr15_S_exit=[6, 11, 16, 21], tr15_S_why="t"))
    T = RR.trades(F, "M1", thr_move=0.5)
    assert list(T.side) == ["C", "P"] and list(T.net) == [100.0, 200.0]   # row 14: share 0.545 -> no side
    T4 = RR.trades(F, "M4", thr_move=0.5)
    assert list(T4.row) == [4, 9, 14]
