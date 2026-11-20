"""R209 tests (PREREG.md section 7): synthetic data only. Plain-English notes, 20 Nov 2026."""
from __future__ import annotations

import math
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r209cfg as K  # noqa: E402
import r209lib as L  # noqa: E402

N = K.NROWS
DAYS = [date(2024, 1, 1) + timedelta(days=i) for i in range(30)]


def walk(seed, start, n_days=len(DAYS), vol=2.0):
    rng = np.random.default_rng(seed)
    out = {}
    p = start
    for d in DAYS[:n_days]:
        c = p + np.cumsum(rng.normal(0, vol, N))
        o = np.concatenate([[p], c[:-1]])
        out[d] = np.vstack([o, np.maximum(o, c) + 0.3, np.minimum(o, c) - 0.3, c])
        p = c[-1] + rng.normal(0, 5)
    return out


def chain(seed, n_days=len(DAYS)):
    rng = np.random.default_rng(seed)
    out = {}
    for d in DAYS[:n_days]:
        out[d] = {k: 100 + np.cumsum(rng.normal(0, 1, N)) for k in L.OPT_KEYS}
        out[d]["cvol"] = rng.integers(1, 100, N).astype(float)
        out[d]["pvol"] = rng.integers(1, 100, N).astype(float)
    return out


def world(seed=0):
    idx = {u: walk(seed + i, 1000.0 * (i + 1)) for i, u in enumerate(K.UNDS)}
    vix = walk(seed + 99, 15.0, vol=0.05)
    opt = {u: chain(seed + 50 + i) for i, u in enumerate(K.UNDS[:3])}     # MIDCPNIFTY and SENSEX: no options
    return idx, vix, opt


def corrupt(idx, vix, opt, day, e, seed=7):
    """Every minute from e on (that day) and every later day replaced by noise, everywhere."""
    rng = np.random.default_rng(seed)

    def hit(a, d):
        a = a.copy()
        if d == day:
            a[..., e:] = rng.normal(1e5, 1e3, a[..., e:].shape)
        elif d > day:
            a[...] = rng.normal(1e5, 1e3, a.shape)
        return a
    idx2 = {u: {d: hit(a, d) for d, a in v.items()} for u, v in idx.items()}
    vix2 = {d: hit(a, d) for d, a in vix.items()}
    opt2 = {u: {d: {k: hit(x, d) for k, x in m.items()} for d, m in v.items()} for u, v in opt.items()}
    return idx2, vix2, opt2


FEATS = L.GROUPS["ALL"]


@pytest.mark.parametrize("e", [5, 60, 200, 355])
def test_look_ahead_guard_every_input(e):
    idx, vix, opt = world(1)
    day = DAYS[25]
    a = L.build(idx, vix, opt)
    b = L.build(*corrupt(idx, vix, opt, day, e))
    ra = a[(a.day == day) & (a.e == e)].set_index("und")[FEATS]
    rb = b[(b.day == day) & (b.e == e)].set_index("und")[FEATS]
    assert len(ra) == 5
    pd.testing.assert_frame_equal(ra, rb, check_exact=False, rtol=1e-12)
    assert ra[L.P_COLS + L.X_COLS + L.V_COLS].notna().all().all() or e == 5   # every own / cross / VIX input is known
    assert ra.loc["NIFTY", L.O_COLS].notna().any() and ra.loc["SENSEX", L.O_COLS].isna().all()


def test_labels_read_only_up_to_the_horizon():
    idx, vix, opt = world(2)
    day, e = DAYS[20], 100
    a = L.build(idx, vix, opt)
    i2 = {u: {d: x.copy() for d, x in v.items()} for u, v in idx.items()}
    i2["NIFTY"][day][:, e + 15:] = 1e9                          # after the 15-minute horizon
    b = L.build(i2, vix, opt)
    pick = lambda t: t[(t.day == day) & (t.e == e) & (t.und == "NIFTY")].iloc[0]  # noqa: E731
    assert pick(a)["up15"] == pick(b)["up15"] and pick(a)["m15"] == pick(b)["m15"]
    c = idx["NIFTY"][day][3]
    assert pick(a)["up15"] == float(c[e + 14] > c[e - 1])
    late = a[(a.e == 355)]
    assert late.up15.notna().all() and late.up30.isna().all()   # 15:10 + 29 minutes is past the session


def test_label_with_no_print_after_entry_and_missing_minutes():
    idx, vix, opt = world(3)
    day = DAYS[10]
    idx["BANKNIFTY"][day][:, 100:130] = np.nan                  # market data missing mid-day
    t = L.build(idx, vix, opt)
    r = t[(t.day == day) & (t.e == 100) & (t.und == "BANKNIFTY")].iloc[0]
    assert math.isnan(r["up15"])                                # nothing after e - 1 within the horizon
    r2 = t[(t.day == day) & (t.e == 120) & (t.und == "BANKNIFTY")].iloc[0]
    assert r2["r1"] == 0.0 or math.isnan(r2["r1"])              # the last known close is carried
    assert math.isnan(r2["up15"]) is False                      # prints from 130 on


def test_ffill_ratio_and_auc():
    assert np.array_equal(np.isnan(L.ffill(np.array([np.nan, 1, np.nan, 3]))), [True, False, False, False])
    assert L.ffill(np.array([np.nan, 1, np.nan, 3]))[2] == 1.0 and np.isnan(L.ffill(np.array([np.nan, np.nan]))).all()
    assert L.auc([0, 0, 1, 1], [0.1, 0.2, 0.8, 0.9]) == 1.0 and L.auc([0, 0, 1, 1], [0.9, 0.8, 0.2, 0.1]) == 0.0
    assert L.auc([0, 1, 0, 1], [0.5, 0.5, 0.5, 0.5]) == 0.5 and math.isnan(L.auc([1, 1], [0.2, 0.3]))


def test_boot_auc_finds_signal_and_not_a_coin():
    rng = np.random.default_rng(4)
    n_days, per = 150, 40
    day = np.repeat(np.arange(n_days), per)
    y = (rng.random(n_days * per) < 0.5).astype(float)
    good = np.clip(0.5 + 0.15 * (y - 0.5) + rng.normal(0, 0.1, len(y)), 0, 1)
    r = L.boot_auc(y, good, day, 300, np.random.default_rng(5))
    assert r["auc"] > 0.6 and r["p"] < 0.01 and r["lo"] < r["auc"] < r["hi"]
    coin = rng.random(len(y))
    assert L.boot_auc(y, coin, day, 300, np.random.default_rng(6))["p"] > 0.02


def test_accuracy_and_top():
    y = np.array([1, 0, 1, 0, 1, 0, 1, 0, 1, 0], float)
    p = np.array([0.9, 0.1, 0.55, 0.45, 0.4, 0.6, 0.51, 0.49, 0.95, 0.05])
    assert L.accuracy(y, p) == pytest.approx(0.8)
    acc, k = L.top_accuracy(y, p, 0.2)
    assert k == 2 and acc == 1.0                                 # 0.95 and 0.05 are the two most confident
    assert math.isnan(L.top_accuracy([], [], 0.2)[0])


def test_wf_split_never_trains_on_or_after_the_quarter():
    days = pd.Series(pd.date_range("2021-12-01", "2026-06-30", freq="D").date)
    for q in K.QUARTERS:
        tr, te = L.wf_split(days, q)
        assert days[tr].max() < q and days[tr].min() >= K.TRAIN_FROM
        assert days[te].min() == q and days[te].max() < L.quarter_end(q)
    assert L.quarter_end(date(2025, 10, 1)) == date(2026, 1, 1) and len(K.QUARTERS) == 13 and K.QUARTERS[-1] == date(2026, 1, 1)


def test_gates_each_item_fails_alone():
    hold = dict(auc=0.56, p=0.001)
    ok = L.gate_t1(hold, 0.58, [0.56] * 13, 0.05)
    assert ok["passes"]
    cases = {"p ": (dict(auc=0.56, p=0.06), 0.58, [0.56] * 13, 0.05), "< 0.53": (dict(auc=0.525, p=0.001), 0.58, [0.56] * 13, 0.05),
             "top-20% accuracy 54": (hold, 0.54, [0.56] * 13, 0.05), "quarters": (hold, 0.58, [0.56] * 8 + [0.5] * 5, 0.05),
             "BH q": (hold, 0.58, [0.56] * 13, 0.2)}
    for item, args in cases.items():
        g = L.gate_t1(*args)
        assert not g["passes"] and len(g["fails"]) == 1 and item in g["fails"][0], (item, g["fails"])
    r = dict(d=0.05, p=0.01, rate_a=0.58, rate_src=0.54)
    assert L.gate_t2(r, 0.05)["passes"]
    for bad, item in ((dict(r, p=0.2), "difference"), (dict(r, rate_a=0.565), "above the source"), (r, "BH q")):
        g = L.gate_t2(bad, 0.5 if item == "BH q" else 0.05)
        assert not g["passes"] and len(g["fails"]) == 1 and item in g["fails"][0]
    assert L.choose_t({0.45: 10.0, 0.48: 12.0, 0.5: 12.0, 0.52: 3.0}) == 0.48


def test_settings():
    assert len(K.TESTS) == 8 and K.M_TOTAL == 4912 and K.DECISION[0] == 5 and K.DECISION[-1] == 355
    assert len(L.GROUPS["ALL"]) == len(set(L.GROUPS["ALL"]))
