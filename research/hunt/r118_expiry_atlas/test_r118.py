"""R118 tests: the atlas builder (look-ahead guard, value path, walls / max pain), the report helpers and the quant maths.

    python3 -m pytest research/hunt/r118_expiry_atlas -q
"""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import atlas as A  # noqa: E402
import quant as Q  # noqa: E402
import report as RP  # noqa: E402

R = A.R


def synthetic_day(und="NIFTY", spot=22000.0, drift=0.0, seed=0, d=date(2025, 1, 2), sd_min=0.0004):
    """A chain priced by Black-76 at 15% vol on a random-walk index, with volume and OI."""
    rng = np.random.default_rng(seed)
    n = R.N
    s = spot * np.exp(np.cumsum(rng.normal(drift, sd_min, n)))
    ix = np.column_stack([s, s * 1.0002, s * 0.9998, s])
    opts = {}
    step = R.STEP[und]
    k0 = round(spot / step) * step
    for j in range(-10, 11):
        k = k0 + j * step
        for side in ("C", "P"):
            a = np.full((n, 7), np.nan)
            t = np.maximum(n - np.arange(n), 1) / (365 * 24 * 60)
            px = np.maximum(np.round(Q.b76_price(s, k, t, 0.15, side == "C") * 20) / 20, 0.05)
            a[:, R.O] = px
            a[:, R.H] = px
            a[:, R.L] = px
            a[:, R.C] = px
            a[:, R.V] = 1000.0
            a[:, R.OI] = 1e5 * (11 + j) if side == "C" else 2e5 / (1 + abs(j))
            a[:, R.IV] = 15.0
            opts[(float(k), side)] = a
    return R.Day(d, und, ix, opts, np.full(n, 14.0), spot, 14.0, 100.0)


FEATURES = ["px", "dist_k", "dist_pct", "dist_em", "em_pct", "tv", "iv", "atm_iv", "iv_ratio", "vol5", "volcum", "oi",
            "oi_chg", "idx_move_em", "idx_move_pct"]


def test_lookahead_guard_mark_features_ignore_the_future():
    """Changing every price AFTER a mark changes no feature column of that mark (only the y_ outcomes)."""
    day = synthetic_day()
    rows, _, _ = A.build_day(day, [day])
    base = pd.DataFrame(rows)
    for cut in (A.MARKS[3], A.MARKS[12], A.MARKS[20]):
        d2 = synthetic_day()
        rng = np.random.default_rng(9)
        d2.ix[cut + 1:] *= rng.uniform(0.97, 1.03, (R.N - cut - 1, 1))
        for a in d2.opts.values():
            a[cut + 1:, [R.O, R.H, R.L, R.C]] *= rng.uniform(0.2, 5, (R.N - cut - 1, 1))
            a[cut + 1:, R.V] *= 7
            a[cut + 1:, R.OI] *= 3
        r2 = pd.DataFrame(A.build_day(d2, [d2])[0])
        key = ["side", "strike", "t"]
        j = base[base.t <= cut].merge(r2[r2.t <= cut], on=key, suffixes=("", "_2"))
        assert len(j) == (base.t <= cut).sum() > 0
        for c in FEATURES:
            assert np.allclose(j[c].values, j[c + "_2"].values, equal_nan=True), (cut, c)
        assert not np.allclose(j.y_end.values, j.y_end_2.values, equal_nan=True)     # outcomes do change


def test_marks_and_distance_signs():
    day = synthetic_day()
    rows, drow, mins = A.build_day(day, [day])
    m = pd.DataFrame(rows)
    assert set(m.t) <= set(A.MARKS) and m.t.min() == A.MARKS[0]
    otm_c = m[(m.side == "C") & (m.strike > m.strike.median() + 200)]
    assert (otm_c.dist_k > 0).all()
    assert (m.y_max.fillna(0) >= 0).all()
    assert drow["n_opts"] == 42 and mins["vol_b"].shape == (2, len(A.EM_LABELS), R.N)


def test_value_path_and_end_value():
    sp = np.full(R.N, 100.0)
    sp[-50:] = 120.0
    a = np.full((R.N, 7), np.nan)
    a[:200, R.C] = 2.0
    v = A.value_path(a, "C", 110.0, sp)
    assert v[0] == 2.0 and v[-1] == 10.0                    # no print later: intrinsic (deep ITM)
    assert A.end_value(a, "C", 110.0, sp) == 10.0
    a[R.N - 3, R.C] = 9.5
    assert A.end_value(a, "C", 110.0, sp) == 9.5            # a print from 15:20 is the end price
    assert A.end_value(np.full((R.N, 7), np.nan), "P", 90.0, sp) == 0.0


def test_suffix_stats():
    mx, am, mn = A.suffix_stats(np.array([1.0, 5.0, np.nan, 3.0, 2.0]))
    assert mx[0] == 5 and am[0] == 1 and mn[0] == 2
    assert mx[1] == 3 and am[1] == 3
    assert np.isnan(mx[-1]) and am[-1] == -1


def test_em_buckets_match_scalar():
    xs = np.array([-1.0, 0.0, 0.1, 0.25, 0.7, 1.2, 1.9, 2.5, 4.0, np.nan])
    assert list(A.em_buckets(xs)) == [A.em_bucket(x) for x in xs]
    assert A.EM_LABELS[A.em_bucket(0.1)] == "0-0.25" and A.EM_LABELS[A.em_bucket(5)] == "3+"


def test_walls_and_max_pain():
    day = synthetic_day()
    cw, pw, mp, pcr = A.walls_and_pain(day, A.MARKS[0])
    ks = sorted({k for k, _ in day.opts})
    assert cw == max(ks)                                     # call OI rises with the strike in the synthetic chain
    assert pw == ks[10]                                      # put OI peaks at the ATM strike
    assert min(ks) <= mp <= max(ks)
    assert pcr > 0
    # max pain by brute force
    oc = {k: A.oi_at(a, A.MARKS[0]) for (k, s), a in day.opts.items() if s == "C"}
    op = {k: A.oi_at(a, A.MARKS[0]) for (k, s), a in day.opts.items() if s == "P"}
    pay = {x: sum(o * max(x - k, 0) for k, o in oc.items()) + sum(o * max(k - x, 0) for k, o in op.items()) for x in ks}
    assert mp == min(pay, key=pay.get)


def test_report_helpers():
    assert RP.half(date(2023, 12, 31)) == "H1" and RP.half(date(2024, 1, 1)) == "H2"
    assert RP.stable(1, 2) == "stable" and RP.stable(-1, 2) == "once" and RP.stable(1, 2, n1=10) == "small"
    assert RP.stable(40, 45, 50) == "stable" and RP.stable(40, 55, 50) == "once"
    assert RP.slot(RP.m_(9, 30)) == "09:30-10:45" and RP.slot(RP.m_(15, 15)) == "14:45-15:15" and RP.slot(0) is None
    assert RP.hm(0) == "09:15" and RP.hm(R.N - 1) == "15:29"
    assert "| a |" in RP.md(pd.DataFrame({"a": [1.0]}))


# ------------------------------------------------------------------------------------------------ quant maths
def test_black76_iv_roundtrip_and_bounds():
    f, k, t = np.array([100.0, 100, 100]), np.array([105.0, 95, 100]), np.array([0.05, 0.05, 0.002])
    call = np.array([True, False, True])
    for sig in (0.1, 0.25, 0.6):
        px = Q.b76_price(f, k, t, sig, call)
        assert np.allclose(Q.b76_iv(px, f, k, t, call), sig, atol=1e-4)
    assert np.isnan(Q.b76_iv(np.array([4.0]), np.array([100.0]), np.array([95.0]), np.array([0.001]), np.array([True]))[0])


def test_atm_straddle_approximation():
    """ATM straddle ~ sqrt(2/pi) x F x sigma x sqrt(T): the basis of L1's implied variance."""
    f, t, sig = 22000.0, 120 / Q.YEAR_MIN, 0.15
    s = Q.b76_price(np.array([f]), np.array([f]), np.array([t]), sig, np.array([True]))[0] * 2
    assert abs(s / (Q.SQ2PI * f * sig * math.sqrt(t)) - 1) < 1e-3


def test_rv_bv_kurt_ac1_vr_on_gaussian():
    rng = np.random.default_rng(1)
    r = rng.normal(0, 1e-3, 200_000)
    rv, bv = Q.rv_bv(r)
    assert abs(bv / rv - 1) < 0.02                          # no jumps
    assert abs(Q.kurt(r) - 3) < 0.1
    assert abs(Q.ac1(r)) < 0.01
    for q in (5, 15, 30):
        assert abs(Q.variance_ratio(r, q) - 1) < 0.05
    r2 = r.copy()
    r2[::5000] += 0.05                                       # add jumps
    rv2, bv2 = Q.rv_bv(r2)
    assert 1 - bv2 / rv2 > 0.3
    mr = r[1:] - 0.6 * r[:-1]                                # mean-reverting (MA with negative weight)
    assert Q.ac1(mr) < -0.3 and Q.variance_ratio(mr, 15) < 0.5
    assert np.isnan(Q.kurt(np.array([1.0, 2.0]))) and np.isnan(Q.rv_bv(np.array([np.nan]))[0])


def test_log_returns_leave_gaps():
    r = Q.log_returns(np.array([100.0, np.nan, 101.0, 102.0]))
    assert np.isnan(r[0]) and np.isnan(r[1]) and np.isclose(r[2], math.log(102 / 101))


def test_vrp_z_is_standard_normal_under_the_model():
    """If the index is a random walk with the straddle's own volatility, z has sd near 1 (L1 / L2 calibration)."""
    zs = []
    for i in range(150):
        day = synthetic_day(seed=1000 + i, sd_min=0.15 / math.sqrt(Q.YEAR_MIN))   # realised = the priced 15%
        rows, drow, mins = A.build_day(day, [day])
        v = Q.vrp_rows([mins])
        zs += list(v[v.t == RP.m_(12, 0)].z)
    sd = np.std(zs)
    assert 0.85 < sd < 1.15                                  # 150 days: the estimate's standard error is about 0.06


def test_bh_free_descriptive_has_no_pnl_columns():
    """Descriptive only: the atlas carries no trade or rupee P&L column."""
    day = synthetic_day()
    m = pd.DataFrame(A.build_day(day, [day])[0])
    assert not [c for c in m.columns if "pnl" in c or "net" in c]


@pytest.mark.skipif(not (A.OUT / "marks_MIDCPNIFTY.parquet").exists(), reason="atlas cache not built")
def test_real_cache_columns():
    m = pd.read_parquet(A.OUT / "marks_MIDCPNIFTY.parquet")
    assert set(FEATURES) <= set(m.columns) and m.t.isin(A.MARKS).all()
    assert (m.px > 0).all()
