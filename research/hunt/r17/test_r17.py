"""R17 replay checks on synthetic paths (no data needed): python3 -m pytest research/hunt/r17/test_r17.py -q"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib  # noqa: E402
from lib import N, Trade  # noqa: E402


def path(prices):
    """A flat option path from minute 0; prices[k] = (o, h, l, c) for the first minutes, then flat at the last close."""
    a = np.full((N, 6), np.nan)
    last = prices[-1][3]
    for k in range(N):
        o, h, l, c = prices[k] if k < len(prices) else (last, last, last, last)
        a[k, :4] = (o, h, l, c)
    return a


def trade(prices, **kw):
    kw.setdefault("sq", lib.SQ_1510)
    return Trade(arm="t", und="BANKNIFTY", i=0, side=1, strike=0.0, c0=0, e=100.0, qty=30, path=path(prices),
                 meta=dict(lot=30), **kw)


def test_stop_is_checked_before_the_target():
    t = trade([(100, 150, 50, 100)], stop0=60.0, tgt=140.0)
    r = lib.replay(t)
    assert r["why"] == "stop" and r["exit_min"] == 0


def test_a_variant_only_exits_earlier_and_fills_next_open():
    t = trade([(100, 101, 99, 100), (100, 101, 99, 100), (105, 106, 104, 105)], stop0=60.0, tgt=200.0)
    base = lib.replay(t)
    r = lib.replay(t, extra=1)
    assert r["exit_min"] == 2 and r["why"] == "variant" and r["exit_min"] <= base["exit_min"]


def test_breakeven_rises_after_15_percent_and_never_widens():
    t = trade([(100, 120, 100, 118), (118, 118, 90, 92)], stop0=60.0, tgt=200.0)
    r0, r5 = lib.replay(t), lib.replay(t, be=True)
    assert r5["why"] == "stop" and r5["exit_min"] == 1
    assert r5["net"] > r0["net"]
    assert lib.be_level(t) > t.e


def test_ladder_breakeven_rung_counts_from_the_next_minute():
    t = trade([(100, 111, 99, 105), (105, 106, 90, 95)], stop0=60.0, tgt=140.0, ladder=((10.0, 0.0), (20.0, 10.0), (30.0, 20.0)))
    r = lib.replay(t)
    assert r["why"] == "stop" and r["exit_min"] == 1 and r["xavg"] > 99


def test_bh_and_bootstrap():
    q = lib.bh([0.01, 0.02, 0.5])
    assert np.all(np.diff(q[np.argsort([0.01, 0.02, 0.5])]) >= 0) and q.max() <= 1
    assert lib.boot_p(np.full(50, 10.0) + np.linspace(-1, 1, 50), B=2000) < 0.01
    assert lib.boot_p(np.zeros(10)) == 1.0
