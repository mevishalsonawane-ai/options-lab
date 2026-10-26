"""R113 tests: buckets, day labels, bootstrap bands, cross-arm helpers, Part D filters, twins, BH, gates, the paper
record parser, and the look-ahead guards (VIX cuts from design days and the previous session only; filters read only
the entry minute). Pure; no lake, no network. 26 Oct 2026."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r113cfg as K  # noqa: E402
import r113lib as L  # noqa: E402


# ---------------------------------------------------------------------------------------------- buckets
def test_bucket_edges():
    assert L.bucket(555, K.NSE_BUCKETS) == "09:15-09:30"
    assert L.bucket(569, K.NSE_BUCKETS) == "09:15-09:30"
    assert L.bucket(570, K.NSE_BUCKETS) == "09:30-10:00"
    assert L.bucket(839, K.NSE_BUCKETS) == "13:00-14:00"
    assert L.bucket(930, K.NSE_BUCKETS) == "14:00-15:30"   # 15:30 itself (last print minute is 15:29)
    assert L.bucket(931, K.NSE_BUCKETS) is None
    assert L.bucket(554, K.NSE_BUCKETS) is None             # before the open
    assert L.bucket(1380, K.MCX_BUCKETS) == "21:00-23:55"


def test_mcx_buckets_by_underlying():
    assert L.time_buckets("NATURALGAS") is K.MCX_BUCKETS
    assert L.time_buckets("silverm") is K.MCX_BUCKETS
    assert L.time_buckets("NIFTY") is K.NSE_BUCKETS


def test_hold_bucket():
    assert L.hold_bucket(0, False) == "<=5m"
    assert L.hold_bucket(5, False) == "5-15m"
    assert L.hold_bucket(29.9, False) == "15-30m"
    assert L.hold_bucket(500, False) == ">120m"
    assert L.hold_bucket(1000, True) == "overnight"
    assert L.hold_bucket(-3, False) == "<=5m"               # a bad row never crashes


def test_gap_and_body_labels():
    assert L.gap_label(100.3, 100) == "gap up"
    assert L.gap_label(99.7, 100) == "gap down"
    assert L.gap_label(100.25, 100) == "flat"               # exactly on the line is flat
    assert L.gap_label(100, float("nan")) is None           # first day: no previous close
    assert L.body_label(100, 110, 99, 109) == "trend day"
    assert L.body_label(100, 110, 90, 101) == "range day"
    assert L.body_label(100, 110, 90, 108) == "mixed"
    assert L.body_label(100, 100, 100, 100) is None         # a flat (holiday-like) bar


def test_minute_conversions():
    assert L.row_minute(5) == 560                            # row 5 = 09:20
    assert L.minute_of("2026-10-26 14:10") == 850
    assert L.hhmm_minute("09:20:03") == 560


# ---------------------------------------------------------------------------------------------- look-ahead guards
def _vix():
    idx = [date(2024, 12, 2), date(2025, 6, 2), date(2026, 3, 31), date(2026, 4, 1), date(2026, 9, 1)]
    return pd.Series([10.0, 20.0, 30.0, 99.0, 99.0], index=idx)


def test_vix_cuts_use_design_days_only():
    v = _vix()
    lo, hi = L.vix_cuts(v)
    v2 = v.copy()
    v2[date(2026, 4, 1)] = -50.0                             # changing a final day must not move the cuts
    assert L.vix_cuts(v2) == (lo, hi)
    assert lo < hi <= 30.0


def test_prev_vix_is_strictly_before():
    v = _vix()
    assert L.prev_vix(v, date(2026, 4, 1)) == 30.0           # not that day's own close (99)
    assert L.prev_vix(v, date(2024, 12, 2)) != L.prev_vix(v, date(2024, 12, 3))
    assert np.isnan(L.prev_vix(v, date(2024, 1, 1)))         # nothing before: unknown, not a guess


def test_filter_reads_only_entry_minute():
    drop = K.RULES["MID"]["drop"]
    assert L.keep_mask([689, 690, 809, 810], drop) == [True, False, False, True]
    assert L.keep_mask([], drop) == []


# ---------------------------------------------------------------------------------------------- statistics
def _frame():
    rows = []
    for i, (t, net) in enumerate([("09:20", 500), ("09:21", -100), ("12:10", -300), ("12:15", -200), ("14:30", 50)]):
        d = date(2025, 5, 5 + (i % 3))
        rows.append(dict(arm="X", und="NIFTY", day=str(d), entry_ts=f"{d} {t}", exit_ts=f"{d} 15:00", side="C",
                         net_rs=net, period="design", expiry="0"))
    return pd.DataFrame(rows)


def test_label_and_describe():
    df = _frame()
    days = {("NIFTY", date(2025, 5, 5)): (100, 110, 99, 109, 99.0)}
    lab = L.label_trades(df, days, _vix(), (12.0, 25.0))
    assert lab.entry_b.tolist()[:3] == ["09:15-09:30", "09:15-09:30", "12:00-13:00"]
    assert lab.gap_b.iloc[0] == "gap up" and pd.isna(lab.gap_b.iloc[1])   # a day without a bar: unlabelled
    assert lab.expiry_b.iloc[0] == "not expiry"
    rows = {r["bucket"]: r for r in L.describe(lab, "entry_b", L.time_order(lab))}
    assert rows["09:15-09:30"]["trades"] == 2 and rows["09:15-09:30"]["net"] == 400
    assert rows["12:00-13:00"]["net"] == -500 and rows["12:00-13:00"]["small"]
    assert rows["11:00-12:00"]["trades"] == 0                # an empty bucket is shown, not dropped
    assert sum(r["net"] for r in rows.values()) == lab.net_rs.sum()


def test_day_band_contains_total_and_is_seeded():
    nets = np.array([100.0, -50, 30, 30, -10, 200])
    days = np.array(["a", "a", "b", "c", "d", "e"])
    lo, hi = L.day_band(nets, days)
    assert lo <= nets.sum() <= hi
    assert L.day_band(nets, days) == (lo, hi)
    assert L.day_band(np.array([]), np.array([])) == (0.0, 0.0)


def test_pair_corr_and_shares():
    a = pd.Series({1: 1.0, 2: 2.0, 3: 3.0})
    b = pd.Series({1: 2.0, 2: 4.0, 3: 6.0, 4: 0.0})
    r, n = L.pair_corr(a, b)
    assert n == 4 and r > 0.9
    assert np.isnan(L.pair_corr(a, pd.Series({1: 5.0, 2: 5.0, 3: 5.0}))[0])   # a flat series has no correlation
    assert L.top_share([10, 1, 1, 1, 1, 1, 1, 1, 1, 1]) == pytest.approx(10 / 19)
    assert L.top_share([-5, -1]) is None                       # no profit to share
    assert L.top_share([]) is None


def test_first_hour_share():
    df = _frame()
    df["entry_min"] = [560, 561, 730, 735, 870]
    assert L.first_hour_share(df) == pytest.approx(400 / -50)


# ---------------------------------------------------------------------------------------------- Part D
def test_random_drop_and_twin_p():
    rng = np.random.default_rng(1)
    d = L.random_drop(10, 3, rng)
    assert len(set(d.tolist())) == 3 and d.max() < 10
    assert len(L.random_drop(5, 9, rng)) == 5
    assert len(L.random_drop(5, 0, rng)) == 0
    assert L.twin_p(100, [0, 50, 200]) == pytest.approx(2 / 4)
    assert L.twin_p(1e9, [0, 1]) == pytest.approx(1 / 3)


def test_bh_counts_prior_tests():
    p = [0.002, 0.5]
    assert L.bh(p).tolist() == pytest.approx([0.004, 0.5])
    q = L.bh(p, 1000)
    assert q[0] == pytest.approx(1.0)                        # 0.002 * 1000 = 2 -> capped at 1
    assert len(L.bh([])) == 0


def test_qualifies_and_gates():
    assert L.qualifies(-1) and not L.qualifies(0) and not L.qualifies(10)
    good = dict(net=10, trades=50)
    g = L.gates(good, good, good, dict(net=5), dict(net=5), 0.05)
    assert g["passed"]
    bad = L.gates(good, good, dict(net=10, trades=10), dict(net=5), dict(net=50), 0.5)
    assert not bad["passed"] and len(bad["fails"]) == 3


def test_rules_are_the_preregistered_eleven():
    n = sum(len(r["arms"]) for r in K.RULES.values())
    assert n == 11
    assert K.N_PRIOR == 5829


# ---------------------------------------------------------------------------------------------- paper record
def test_pair_orders():
    orders = [
        dict(at="09:20:03", account="paper", source="R72", symbol="NIFTY1", side="BUY", status="complete", avg=100.0),
        dict(at="09:20:03", account="paper", source="R72", symbol="NIFTY1", side="SELL", status="cancelled", avg=0.0),
        dict(at="09:30:32", account="paper", source="R72", symbol="NIFTY1", side="SELL", status="complete", avg=110.0),
        dict(at="09:05:00", account="paper", source="US", symbol="SILV", side="SELL", status="complete", avg=200.0),
        dict(at="14:39:38", account="paper", source="US", symbol="SILV", side="BUY", status="complete", avg=190.0),
        dict(at="10:00:00", account="live", source="R72", symbol="X", side="BUY", status="complete", avg=None),
        dict(at="10:05:00", account="paper", source="R72", symbol="OPEN", side="BUY", status="complete", avg=5.0),
        dict(junk=True),
    ]
    t = L.pair_orders(orders, "2026-10-26")
    assert len(t) == 2                                       # live, open and malformed rows never become trips
    a = {x["source"]: x for x in t}
    assert a["R72"]["points"] == pytest.approx(10) and a["R72"]["pct"] == pytest.approx(10)
    assert a["US"]["points"] == pytest.approx(10)            # a short future: sell 200, buy back 190
    assert L.pair_orders(None, "d") == []
