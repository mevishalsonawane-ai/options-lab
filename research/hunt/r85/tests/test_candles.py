"""R85 tests: candle building, features, shapes, forward outcomes, statistics and the look-ahead guard (synthetic)."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r85 import anatomy as A
from research.hunt.r85 import candles as K


def minutes(days=("2025-09-01", "2025-09-02"), start=9 * 60, end=12 * 60, seed=1, seg=None):
    """Synthetic session minutes: a random walk per day, contiguous across days."""
    rng = np.random.default_rng(seed)
    rows = []
    px = 1000.0
    for k, d in enumerate(days):
        for t in range(start, end):
            px += rng.normal(0, 1)
            rows.append((pd.Timestamp(d) + pd.Timedelta(minutes=t), pd.Timestamp(d).date(), t, px,
                         (seg[k] if seg else 0), float(rng.integers(0, 50)), False))
    return pd.DataFrame(rows, columns=["ts", "day", "tod", "F", "seg", "act", "exp_day"])


# ---------------------------------------------------------------- candle building
def test_candle_open_is_previous_minute_close_and_hl_cover_open():
    mn = minutes(days=("2025-09-01",), start=540, end=560)
    cd = K.build_candles(mn, 5)
    assert len(cd) == 4
    F = mn.F.values
    assert cd.o.iloc[0] == F[0]                      # day's first candle: first close
    assert cd.o.iloc[1] == F[4]                      # later candles: last close before the candle
    assert cd.c.iloc[1] == F[9]
    assert cd.h.iloc[1] == max(F[4:10].max(), F[4]) and cd.l.iloc[1] == min(F[4:10].min(), F[4])
    assert (cd.h >= cd[["o", "c"]].max(axis=1)).all() and (cd.l <= cd[["o", "c"]].min(axis=1)).all()


def test_candle_needs_half_its_minutes_and_does_not_cross_days():
    mn = minutes(days=("2025-09-01", "2025-09-02"), start=540, end=547)   # 7 minutes: one full 5m + a 2-minute stub
    cd = K.build_candles(mn, 5)
    assert list(cd.start) == [540, 540]                # the 2-minute stubs are dropped
    assert cd.o.iloc[1] == mn.F.values[7]              # next day's first candle opens at its own first close


def test_session_minutes_drop_outside_hours_weekend_and_zero_price():
    ts = pd.to_datetime(["2025-09-01 08:59", "2025-09-01 09:00", "2025-09-01 23:29", "2025-09-01 23:30",
                         "2025-09-06 10:00", "2025-12-01 23:40", "2025-12-01 23:55"])
    mn = pd.DataFrame({"ts": ts, "day": ts.date, "tod": ts.hour * 60 + ts.minute, "F": [1, 1, 1, 1, 1, 1, 1.0],
                       "seg": 0, "act": 0.0, "exp_day": False})
    mn.loc[1, "F"] = 0
    out = K.session_minutes(mn)
    # summer: 23:29 in, 23:30 out; Saturday out; winter: 23:40 in, 23:55 out; F = 0 out
    assert list(out.ts.dt.strftime("%m-%d %H:%M")) == ["09-01 23:29", "12-01 23:40"]


# ---------------------------------------------------------------- buckets and calendar
def test_buckets_follow_us_time():
    assert K.bucket_of(9 * 60, date(2025, 9, 1)) == "MORNING"
    assert K.bucket_of(15 * 60, date(2025, 9, 1)) == "LULL"
    assert K.bucket_of(19 * 60 + 15, date(2025, 9, 1)) == "USOPEN"     # US summer time: open 19:00 IST
    assert K.bucket_of(19 * 60 + 15, date(2025, 12, 1)) == "PREUS"     # US winter time: open 20:00 IST
    assert K.bucket_of(21 * 60 + 40, date(2025, 9, 1)) == "LATE"
    assert K.bucket_of(21 * 60 + 40, date(2025, 12, 1)) == "USOPEN"


def test_event_days_by_instrument():
    a, b = date(2025, 9, 1), date(2025, 9, 30)
    g = K.event_days("GOLDM", a, b)
    c = K.event_days("CRUDEOIL", a, b)
    n = K.event_days("NATURALGAS", a, b)
    assert date(2025, 9, 11) in g and date(2025, 9, 17) in g           # CPI, FOMC
    assert date(2025, 9, 10) in c and date(2025, 9, 10) not in g       # EIA Wednesday for crude only
    assert date(2025, 9, 11) in n and date(2025, 9, 10) not in n       # storage Thursday for gas


# ---------------------------------------------------------------- features and shapes
def frame(o, h, l, c, day="2025-09-01"):
    n = len(o)
    return pd.DataFrame({"day": [pd.Timestamp(day).date()] * n, "start": 540 + 5 * np.arange(n), "o": o, "h": h,
                         "l": l, "c": c, "n": 5, "act": 10.0, "seg": 0, "exp_day": False, "i_last": np.arange(n)})


def test_shapes_hammer_star_engulf_inside_outside_three_flip():
    base = 20
    o = [100.0] * base
    c = [100.5 if k % 2 else 99.5 for k in range(base)]
    h = [101.0] * base
    lo = [99.0] * base
    # hammer: long lower wick, close near top
    o += [100.0]; h += [100.3]; lo += [97.0]; c += [100.2]
    # shooting star
    o += [100.0]; h += [103.0]; lo += [99.8]; c += [99.9]
    # red then bullish engulfing
    o += [100.5]; h += [100.6]; lo += [99.9]; c += [100.0]
    o += [99.9]; h += [101.2]; lo += [99.8]; c += [101.0]
    # inside bar
    o += [100.2]; h += [100.9]; lo += [100.0]; c += [100.6]
    # three greens then a red flip
    o += [100.6]; h += [101.0]; lo += [100.5]; c += [100.9]
    o += [100.9]; h += [101.3]; lo += [100.8]; c += [101.2]
    o += [101.2]; h += [101.1 + 0.5]; lo += [101.1]; c += [101.5]
    o += [101.5]; h += [101.6]; lo += [100.6]; c += [100.7]
    cd = K.add_features(frame(o, h, lo, c), None, "GOLDM")
    i = base
    assert cd.s_hammer.iloc[i] and not cd.s_star.iloc[i]
    assert cd.s_star.iloc[i + 1] and not cd.s_hammer.iloc[i + 1]
    assert cd.s_bull_engulf.iloc[i + 3] and not cd.s_bear_engulf.iloc[i + 3]
    assert cd.s_inside.iloc[i + 4]
    assert cd.s_three.iloc[i + 7]
    assert cd.s_flip3.iloc[i + 8]
    assert cd.colour.iloc[i + 8] == -1


def test_doji_has_no_colour_and_no_signed_outcome():
    o = [100.0] * 30
    c = [100.0 + (0.5 if k % 2 else -0.5) for k in range(30)]
    c[25] = 100.01                         # body 0.01 of a 2.0 range -> doji
    h = [101.0] * 30
    lo = [99.0] * 30
    cd = K.add_features(frame(o, h, lo, c), None, "GOLDM")
    assert cd.colour.iloc[25] == 0 and cd.s_doji.iloc[25]
    assert np.isnan(cd.fwd_s1.iloc[25]) and np.isfinite(cd.fwd_u1.iloc[25])


def test_forward_outcomes_stay_inside_the_day():
    a = frame([1.0] * 12, [2.0] * 12, [0.5] * 12, list(np.linspace(1, 2, 12)), day="2025-09-01")
    b = frame([1.0] * 12, [2.0] * 12, [0.5] * 12, list(np.linspace(1, 2, 12)), day="2025-09-02")
    cd = K.add_features(pd.concat([a, b], ignore_index=True), None, "GOLDM")
    assert np.isnan(cd.fwd_u3.iloc[10]) and np.isnan(cd.fwd_u1.iloc[11])   # last candles of day 1: no window
    assert np.isfinite(cd.fwd_u3.iloc[8])
    k = 8
    assert cd.fwd_u3.iloc[k] == pytest.approx((cd.c.iloc[k + 3] - cd.c.iloc[k]) / cd.atr.iloc[k])
    assert cd.fwd_rng3.iloc[k] == pytest.approx((cd.h.iloc[k + 1:k + 4].max() - cd.l.iloc[k + 1:k + 4].min()) /
                                                cd.atr.iloc[k])


def test_previous_day_levels_and_gap_are_blank_on_a_roll_day():
    mn = minutes(days=("2025-09-01", "2025-09-02", "2025-09-03"), start=540, end=600, seg=[0, 0, 1])
    cd = K.add_features(K.build_candles(mn, 5), mn, "GOLDM")
    d2, d3 = date(2025, 9, 2), date(2025, 9, 3)
    assert np.isfinite(cd[cd.day == d2].gap_atr.iloc[0])
    assert np.isnan(cd[cd.day == d3].gap_atr.iloc[0])
    assert not cd[cd.day == d3].broke_pdh.any() and not cd[cd.day == d3].broke_pdl.any()
    assert cd[cd.day == d3].roll_day.all() and not cd[cd.day == d2].roll_day.any()


def test_days_to_expiry_and_expiry_week():
    mn = minutes(days=("2025-09-01", "2025-09-02", "2025-09-03", "2025-09-04"), start=540, end=560)
    cd = K.add_features(K.build_candles(mn, 5), mn, "GOLDM", exp_days=[np.datetime64("2025-09-03")])
    dte = cd.groupby("day").dte.first()
    assert list(dte.values[:3]) == [2, 1, 0]
    assert np.isnan(dte.values[3])                        # after the last known expiry: unknown
    assert cd.exp_week.iloc[0]


# ---------------------------------------------------------------- look-ahead guard
@pytest.mark.parametrize("tf", [1, 5, 15])
def test_no_feature_changes_when_the_future_is_cut_off(tf):
    """Every non-forward column of a candle must be identical whether or not later minutes exist."""
    mn = minutes(days=("2025-09-01", "2025-09-02", "2025-09-03"), start=540, end=720, seed=7)
    full = K.add_features(K.build_candles(mn, tf), mn, "GOLDM", exp_days=[np.datetime64("2025-09-05")])
    cut_ts = pd.Timestamp("2025-09-02 10:30")
    part = mn[mn.ts < cut_ts].reset_index(drop=True)
    cut = K.add_features(K.build_candles(part, tf), part, "GOLDM", exp_days=[np.datetime64("2025-09-05")])
    # compare only candles that were complete before the cut
    done = cut.day.astype(str) + (cut.start + tf).astype(str)
    keep = [(pd.Timestamp(d) + pd.Timedelta(minutes=int(s) + tf)) <= cut_ts for d, s in zip(cut.day, cut.start)]
    cut = cut[keep]
    full = full.iloc[:len(cut)]
    cols = [k for k in cut.columns if not k.startswith("fwd_") and k not in ("i_last",)]
    for k in cols:
        a, b = full[k].values, cut[k].values
        if a.dtype.kind in "fc":
            assert np.allclose(a, b, equal_nan=True), k
        else:
            assert (a == b).all(), k
    assert len(done) >= len(cut)


def test_daily_candles_forward_runs_across_days_and_shapes_use_previous_day():
    idx = pd.date_range("2024-01-01", periods=40, freq="B")
    c = np.linspace(100, 140, 40)
    d = pd.DataFrame({"open": c - 0.5, "high": c + 1, "low": c - 1, "close": c, "volume": 1.0, "oi": 1.0}, index=idx)
    cd = K.daily_candles(d)
    assert cd.fwd_u3.notna().sum() == 33   # ATR needs 5 days and np.isfinite(cd.fwd_u10.iloc[29]) and np.isnan(cd.fwd_u10.iloc[30])
    assert cd.s_three.iloc[5] and (cd.colour == 1).all()
    assert isinstance(cd.day.iloc[0], date)


# ---------------------------------------------------------------- statistics
def test_clustered_se_and_bh():
    x = np.array([1.0, 1.0, -1.0, -1.0])
    mu, se = A.clustered_mean_se(x, np.array([0, 0, 1, 1]))
    assert mu == 0 and se == pytest.approx(np.sqrt(8 / 16 * 2))
    assert np.isnan(A.clustered_mean_se(x, np.zeros(4))[1])
    q = A.bh(np.array([0.01, 0.04, 0.03, np.nan, 0.5]))
    assert np.isnan(q[3])
    assert q[0] == pytest.approx(0.04) and q[2] == pytest.approx(0.04 * 4 / 3) and q[1] == pytest.approx(0.04 * 4 / 3)
    assert q[4] == pytest.approx(0.5)


def test_cell_table_finds_a_planted_continuation_and_not_noise():
    rng = np.random.default_rng(3)
    n = 4000
    days = np.repeat(pd.date_range("2025-09-01", periods=200).date, n // 200)
    col = rng.choice([1, -1], n)
    plant = rng.random(n) < 0.2
    s3 = rng.normal(0, 1, n) + np.where(plant, 0.6, 0.0)
    cd = pd.DataFrame({"day": days, "colour": col, "bucket": "MORNING", "fwd_s3": s3, "fwd_rng3": 1.0})
    out = A.cell_table(cd, "X", "5m", {"plant": plant, "noise": rng.random(n) < 0.2}, ("ALL",), horizons=(3,))
    t = pd.DataFrame(out)
    p_plant = t[(t.cond == "plant")].p.max()
    assert p_plant < 1e-4
    assert (t[t.cond == "plant"].effect > 0.3).all()
    assert (t[t.cond == "noise"].p > 0.001).all()


def test_colour_matched_effect_removes_market_drift():
    """A market drifting up makes every red candle 'reverse' against the pooled baseline; the colour-matched effect
    of a random subset of red candles must stay near zero (deviation D1)."""
    rng = np.random.default_rng(5)
    n = 6000
    days = np.repeat(pd.date_range("2025-09-01", periods=200).date, n // 200)
    col = rng.choice([1, -1], n)
    u3 = rng.normal(0.3, 1, n)                        # forward move drifts up whatever the colour
    cd = pd.DataFrame({"day": days, "colour": col, "bucket": "MORNING", "fwd_s3": col * u3, "fwd_rng3": 1.0})
    sub = rng.random(n) < 0.3
    t = pd.DataFrame(A.cell_table(cd, "X", "5m", {"subset": sub}, ("ALL",), horizons=(3,)))
    red = t[t.colour == "red"].iloc[0]
    assert red.effect < -0.2 and red.p < 1e-3        # the pooled test is fooled by the drift
    assert abs(red.effect_cm) < 0.1 and red.p_cm > 0.01
