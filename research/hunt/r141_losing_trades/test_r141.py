"""Tests for R141 (PREREG.md): the loss buckets, the 'did it ever work' types, the steady-pattern rule and clustering."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r141 as X  # noqa: E402


def T(day, net, er=5, xr=40, peak=0.0, und="NIFTY", why="stop"):
    return dict(day=day, net=net, er=er, xr=xr, peak=peak, und=und, why=why, side="C")


def test_buckets_and_types():
    assert X.bucket(5, X.TIME_BUCKETS) == "09:15-09:30" and X.bucket(300, X.TIME_BUCKETS) == "after 13:30"
    assert X.bucket(-4000, X.SIZE_BUCKETS) == "loss > Rs 3,000" and X.bucket(-100, X.SIZE_BUCKETS) == "loss < Rs 500"
    assert X.bucket(0, X.SIZE_BUCKETS) is None
    assert X.peak_type(T(date(2025, 1, 1), -1, peak=0.01)).startswith("never")
    assert X.peak_type(T(date(2025, 1, 1), -1, peak=0.2)).startswith("gave")
    assert X.peak_type(T(date(2025, 1, 1), -1, peak=0.1)) == "in between"
    assert X.peak_type(dict(peak=np.nan)) == "unknown"


def test_summary_and_clustering():
    d1, d2 = date(2025, 1, 1), date(2025, 1, 2)
    tr = [T(d1, -100), T(d1, -50, er=50), T(d2, 300), T(d2, -20, er=60)]
    s = X.summary(tr)
    assert s["losers"] == 3 and s["loser_rate"] == 0.75 and s["sum_losses"] == -170
    c = X.clustering(tr)
    assert c["all_loss_index_days"] == 1 and c["losers_on_all_loss_days"] == round(2 / 3, 3)
    assert c["longest_losing_streak"] == 2
    assert X.summary([]) == dict(n=0)


def test_steady_needs_every_period():
    def per(day0, n, net):
        return [T(day0, net, er=5 + i) for i in range(n)]
    good = per(date(2025, 1, 6), 40, 100.0) + per(date(2025, 6, 2), 40, 100.0) + per(date(2026, 5, 4), 40, 100.0)
    bad = [dict(t, und="BANKNIFTY", net=-100.0) for t in good]
    sl = X.slices(good + bad, lambda t: t["und"])
    assert sl["BANKNIFTY"]["steady_losing"] and not sl["NIFTY"]["steady_losing"]
    few = [dict(t, und="SENSEX", net=-100.0) for t in good[:70]]           # nothing in the final
    sl2 = X.slices(good + few, lambda t: t["und"])
    assert not sl2["SENSEX"]["steady_losing"]
