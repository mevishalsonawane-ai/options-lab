"""R94 idea 1 tests (PREREG 8): hand-made calendar pairs and the look-ahead guard. No lake data needed."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r94_mcx_advanced import calspread as K
from research.hunt.r94_mcx_advanced import common as C


def legs(ts, near, far, vn=None, vf=None):
    ts = pd.to_datetime(ts)
    n = pd.DataFrame({"ts": ts, "close": near, "volume": vn if vn is not None else np.ones(len(ts))})
    f = pd.DataFrame({"ts": ts, "close": far, "volume": vf if vf is not None else np.ones(len(ts))})
    return n, f


def obs_frame(s_values, start="2026-06-01 09:00", near=100_000.0, step_min=15, per_day=40):
    """Observations directly: one per 15 minutes, per_day a session, far = near * exp(s)."""
    ts = []
    t0 = pd.Timestamp(start)
    for k in range(len(s_values)):
        d, m = divmod(k, per_day)
        ts.append(t0 + pd.Timedelta(days=d) + pd.Timedelta(minutes=step_min * m))
    ts = pd.to_datetime(ts)
    s = np.asarray(s_values, float)
    ob = pd.DataFrame({"ts": ts, "near": near, "far": near * np.exp(s), "s": s})
    ob["day"] = ob.ts.dt.normalize()
    return ob


def test_observations_only_common_printed_minutes_and_last_in_bar():
    ts = ["2026-06-01 09:00", "2026-06-01 09:05", "2026-06-01 09:10", "2026-06-01 09:16"]
    n, f = legs(ts, [100, 101, 102, 103], [110, 111, 112, 113], vn=[1, 1, 0, 1], vf=[1, 1, 1, 0])
    ob = K.observations(n, f)
    # 09:10 near did not print, 09:16 far did not print -> bar 09:00 keeps 09:05; bar 09:15 has nothing
    assert list(ob.ts.dt.strftime("%H:%M")) == ["09:05"]
    assert ob.s.iloc[0] == pytest.approx(np.log(111 / 101))


def test_z_excludes_the_current_observation():
    rng = np.random.default_rng(1)
    s = list(rng.normal(0, 0.001, 120)) + [0.05]
    ob = K.add_signals(obs_frame(s), K.Rule("t", "z", 100), "2026-12-31", "2027-01-31")
    prev = np.array(s[-101:-1])
    assert ob.sig.iloc[-1] == pytest.approx((0.05 - prev.mean()) / prev.std(ddof=1))
    assert np.isnan(ob.sig.iloc[99]) and np.isfinite(ob.sig.iloc[100])


def test_carry_gap():
    ob = K.add_signals(obs_frame([0.01]), K.RULES["C1"], "2026-12-04", "2027-02-05")
    gap_days = (pd.Timestamp("2027-02-05") - pd.Timestamp("2026-12-04")).days
    assert ob.sig.iloc[0] == pytest.approx(0.01 / (gap_days / 365) - K.FAIR_CARRY)


def _run(s, rule, near_exp="2026-12-31", window=(pd.Timestamp("2026-01-01").date(), pd.Timestamp("2026-12-31").date()),
         per_day=40):
    ob = K.add_signals(obs_frame(s, per_day=per_day), rule, near_exp, "2027-01-31")
    sess_ix = np.searchsorted(np.unique(ob.day.values), ob.day.values)
    return ob, K.simulate("GOLDM", rule, ob, sess_ix, window)


def test_entry_fills_at_next_observation_and_signal_exit_next_too():
    base = [0.0, 0.001] * 50                       # sd ~ 0.0005, mean 0.0005
    s = base + [0.01, 0.009, 0.0004, 0.0003, 0.0002]
    rule = K.Rule("t", "z", 100, 2.0, 0.0, 50.0, 99)
    ob, tr = _run(s, rule)
    assert len(tr) == 1
    t = tr[0]
    assert t["side"] == -1 and t["i_in"] == 101            # signal at 100, fill at 101
    # z at 102 is below 0 -> exit fills at 103
    assert t["why"] == "signal" and t["i_out"] == 103


def test_hard_stop_fills_at_the_triggering_observation():
    base = [0.0, 0.001] * 50
    s = base + [0.01, 0.01, 0.0105, 0.05, 0.06]
    rule = K.Rule("t", "z", 100, 2.0, 0.0, 2.0, 99)
    ob, tr = _run(s, rule)
    t = tr[0]
    assert t["why"] == "stop" and t["i_out"] == 103 and t["net"] < 0
    sd_e = ob.sd.iloc[101]
    assert t["risk_rs"] == pytest.approx(2 * sd_e * ob.far.iloc[101] * 10 + (t["gross"] - t["net"]), rel=0.05)


def test_time_stop_counts_sessions():
    base = [0.0, 0.001] * 50
    s = base + [0.01] + [0.0095] * 200
    rule = K.Rule("t", "z", 100, 2.0, -1e9, 50.0, 2)           # the z exit never fires
    ob, tr = _run(s, rule, per_day=40)
    t = tr[0]
    assert t["why"] == "time"
    sess = np.searchsorted(np.unique(ob.day.values), ob.day.values)
    assert sess[t["i_out"]] == sess[t["i_in"]] + 2 and sess[t["i_out"] - 1] == sess[t["i_in"]] + 1


def test_expiry_blocks_entry_and_forces_close():
    base = [0.0, 0.001] * 50
    s = base + [0.01] + [0.0095] * 400
    rule = K.Rule("t", "z", 100, 2.0, -1e9, 50.0, 99)          # the z exit never fires
    # observations start 1 Jun; 40 a day -> obs 101 on 3 Jun. Near expiry 12 Jun: 7 business days before = 3 Jun
    ob, tr = _run(s, rule, near_exp="2026-06-12")
    assert tr == []
    ob, tr = _run(s, rule, near_exp="2026-06-16")
    assert tr and tr[0]["why"] == "expiry"
    assert np.busday_count(np.datetime64(tr[0]["exit_ts"].date()), np.datetime64("2026-06-16")) <= K.CLOSE_BDAYS


def test_costs_both_legs_and_far_leg_wider():
    A = {"ts": pd.to_datetime(["2026-06-01 10:00", "2026-06-01 11:00"]).values, "near": np.array([100000.0, 100000.0]),
         "far": np.array([101000.0, 101000.0]), "s": np.zeros(2), "sd": np.zeros(2), "sig": np.zeros(2),
         "must_close": np.zeros(2, bool)}
    t = K._trade("GOLDM", A, 0, 1, 1, "x", 3.0)
    assert t["gross"] == 0
    hn, hf = C.fut_half_spread("GOLDM", 100000.0), C.fut_half_spread("GOLDM", 101000.0, far=True)
    spread_cost = 2 * (hn + hf) * 10
    ch = C.fut_leg_charges(101000 * 10 + hf * 10, 101000 * 10 - hf * 10) + \
        C.fut_leg_charges(100000 * 10 + hn * 10, 100000 * 10 - hn * 10)
    assert -t["net"] == pytest.approx(spread_cost + ch, rel=1e-6)
    assert hf == pytest.approx(3 * C.fut_half_spread("GOLDM", 101000.0))


def test_twins_keep_count_and_are_reproducible():
    rng = np.random.default_rng(3)
    s = list(np.cumsum(rng.normal(0, 0.0005, 3000)) * 0.1)
    rule = K.Rule("t", "z", 100, 2.0, 0.0, 2.0, 2)
    ob, tr = _run(s, rule)
    assert tr
    sess_ix = np.searchsorted(np.unique(ob.day.values), ob.day.values)
    w = (pd.Timestamp("2026-01-01").date(), pd.Timestamp("2026-12-31").date())
    a = K.twins("GOLDM", rule, ob, sess_ix, w, tr, n_sets=20, seed=5)
    b = K.twins("GOLDM", rule, ob, sess_ix, w, tr, n_sets=20, seed=5)
    assert len(a) == 20 and np.allclose(a, b)


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("rname", ["Z1", "Z2", "C1"])
@pytest.mark.parametrize("seed", [0, 1, 2])
def test_look_ahead_guard(rname, seed):
    rng = np.random.default_rng(seed)
    s = list(np.cumsum(rng.normal(0, 0.0004, 2400)) * 0.2 + 0.006)
    rule = K.RULES[rname]
    ob = obs_frame(s)
    full = K.add_signals(ob, rule, "2026-12-31", "2027-02-05")
    sess_ix = np.searchsorted(np.unique(ob.day.values), ob.day.values)
    w = (pd.Timestamp("2026-01-01").date(), pd.Timestamp("2026-12-31").date())
    full_tr = K.simulate("GOLDM", rule, full, sess_ix, w, gap_days=63)
    for cut in rng.integers(500, 2300, size=4):
        cut = int(cut)
        trunc = K.add_signals(ob.iloc[:cut].copy(), rule, "2026-12-31", "2027-02-05")
        pert = ob.copy()
        pert.loc[cut:, "s"] = pert.loc[cut:, "s"] + rng.normal(0, 0.01, len(pert) - cut)
        pert.loc[cut:, "far"] = pert.loc[cut:, "near"] * np.exp(pert.loc[cut:, "s"])
        pert = K.add_signals(pert, rule, "2026-12-31", "2027-02-05")
        for other in (trunc, pert):
            a = full.sig.values[:cut]
            b = other.sig.values[:cut]
            assert np.allclose(a, b, equal_nan=True)
        # every trade that EXITS before the cut is identical with the data after the cut deleted / perturbed
        done = [t for t in full_tr if t["i_out"] < cut - 1]
        for other in (trunc, pert):
            tr = K.simulate("GOLDM", rule, other, sess_ix[:len(other)], w, gap_days=63)
            got = [t for t in tr if t["i_out"] < cut - 1]
            assert [(t["i_in"], t["i_out"], t["side"]) for t in got] == [(t["i_in"], t["i_out"], t["side"]) for t in done]
    assert len(full_tr) >= 2 or rname == "C1"                    # the guard is not vacuous for z rules
