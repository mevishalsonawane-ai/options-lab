"""R176 tests: Boss's Rs 500 step trail per lot (levels, rounding, the tighter-stop-only rule), the simulator on synthetic
contracts (rule off = R106 exactly, never later than today's exits, stop slippage, gaps, NaN minutes), the look-ahead
guard (exits never change when data after the exit is changed), and the pure study helpers (exit mix, months, gates, BH
count). Synthetic data only: no stores, no lake."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r176cfg as K  # noqa: E402

Z = pytest.importorskip("r176engine")
import r106engine as X  # noqa: E402
import engine as E  # noqa: E402
import r176study as S  # noqa: E402

STEPS = np.array([[0.30, np.nan], [0.60, 0.30], [1.00, 0.60], [0.25, 0.05]])
XL = E.Exits(stop=0.15, target=-1.0, steps=tuple(map(tuple, STEPS)), tmax=30, sq_row=360)
LIQ = E.Exits(stop=0.15, tmax=20, need=0.05)
NOTIME = E.Exits(stop=0.15, target=-1.0, tmax=0, sq_row=360)


def _flat(n=375, px=100.0, vol=1e7):
    C = np.full((5, n), px, dtype=np.float64)
    C[4] = vol
    return C


def _run(C, e, qty, ex, on, lots, sq=360):
    raw, real, ef, _ = X.entry_info(C, e, sq, qty)
    s, f, st, kind = Z.levels(raw, real, qty, ex, on, lots)
    out = Z.simulate_step(C, e, sq, -1, f, ex.target, ex.lock_trig, ex.lock_keep, ex.lock_trail, ex.steps_arr(), ex.tmax,
                          ex.need, ex.close_stop, 0.05, qty, 0.0, s, st, *E.SLIP)
    return (raw, real, s, kind), out


# ------------------------------------------------------------------------------------------------ pure levels
def test_step_locked_per_lot():
    assert K.step_locked(999.99, 1) is None
    assert K.step_locked(1000.0, 1) == 500.0
    assert K.step_locked(1499.0, 1) == 500.0
    assert K.step_locked(1500.0, 1) == 1000.0
    assert K.step_locked(2000.0, 1) == 1500.0
    assert K.step_locked(2999.0, 3) is None                        # 3 lots: starts at +3,000
    assert K.step_locked(3000.0, 3) == 1500.0
    assert K.step_locked(4500.0, 3) == 3000.0
    assert K.step_locked(float("nan"), 1) is None


def test_entry_stop_is_500_a_lot_rounded_up():
    s = Z.entry_stop_px(100.2, 65.0, 1)                            # 100.2 - 7.692 = 92.508 -> 92.55
    assert s == pytest.approx(92.55) and (100.2 - s) * 65 <= 500.0
    s3 = Z.entry_stop_px(100.2, 195.0, 3)                          # 1,500 / 195 = 7.692 -> same premium
    assert s3 == pytest.approx(92.55) and (100.2 - s3) * 195 <= 1500.0
    assert Z.entry_stop_px(5.0, 65.0, 1) == -1.0                   # the position is worth less than Rs 500


def test_step_px_rounds_up_and_locks_at_least_the_rupees():
    p = Z.step_px(100.2, 65.0, 1000.0, 1)                          # +500 / 65 = 7.692 -> 107.892 -> 107.90
    assert p == pytest.approx(107.9) and (p - 100.2) * 65 >= 500.0
    assert Z.step_px(100.2, 65.0, 999.0, 1) == -1.0


def test_entry_stop_only_when_tighter_than_own():
    # 1 lot of 65 at Rs 100: Rs 500 = 7.7 points, -15% = 85 -> the rule's stop (92.55) is tighter and acts
    s, f, st, k = Z.levels(100.0, 100.2, 65.0, XL, True, 1)
    assert (s, f, st, k) == (pytest.approx(92.55), -1.0, 500.0, "rupee")
    # premium Rs 20: -15% = 17; Rs 500 / 65 = 7.7 -> 12.35, looser: the arm's own stays, the trail is still on
    s, f, st, k = Z.levels(20.0, 20.1, 65.0, XL, True, 1)
    assert (s, f, st, k) == (-1.0, 0.15, 500.0, "own")
    assert Z.levels(100.0, 100.2, 65.0, XL, False, 1) == (-1.0, 0.15, -1.0, "own")


def test_labels():
    assert Z.label(E.R_STOP, "rupee") == "stop500"
    assert Z.label(E.R_STOP, "own") == "stop"
    assert Z.label(Z.R_STEP, "rupee") == "step"
    assert Z.label(E.R_LOCK, "own") == "lock"


# ------------------------------------------------------------------------------------------------ simulator
@pytest.mark.parametrize("seed", range(25))
@pytest.mark.parametrize("ex", [XL, LIQ], ids=["XL", "LIQ"])
def test_rule_off_equals_r106(seed, ex):
    rng = np.random.default_rng(seed)
    n = 375
    c = 100 * np.exp(np.cumsum(rng.normal(0, 0.01, n)))
    C = np.vstack([c * (1 + rng.normal(0, 0.002, n)), c * 1.004, c * 0.996, c, rng.uniform(1e3, 1e5, n)])
    if seed % 5 == 0:
        C[:, 40:43] = np.nan                                       # missing minutes
    a = Z.simulate_step(C, 10, 360, -1, ex.stop, ex.target, ex.lock_trig, ex.lock_keep, ex.lock_trail, ex.steps_arr(),
                        ex.tmax, ex.need, ex.close_stop, 0.05, 65.0, 0.0, -1.0, -1.0, *E.SLIP)
    b = X.simulate(C, 10, 360, -1, ex.stop, ex.target, ex.lock_trig, ex.lock_keep, ex.lock_trail, ex.steps_arr(),
                   ex.tmax, ex.need, ex.close_stop, 0.05, 65.0, 0.0, -1.0, -1.0, *E.SLIP)
    assert np.allclose(np.array(a, float), np.array(b, float), equal_nan=True)


@pytest.mark.parametrize("seed", range(25))
@pytest.mark.parametrize("lots", [1, 3])
def test_rule_never_exits_later_than_own(seed, lots):
    rng = np.random.default_rng(100 + seed)
    n = 375
    c = 100 * np.exp(np.cumsum(rng.normal(0, 0.008, n)))
    C = np.vstack([c, c * 1.003, c * 0.997, c, np.full(n, 1e6)])
    _, a = _run(C, 10, 65.0 * lots, XL, False, lots)
    _, b = _run(C, 10, 65.0 * lots, XL, True, lots)
    assert b[5] <= a[5]


def test_entry_stop_hit_with_stop_slippage():
    C = _flat()
    C[2, 20] = 90.0                                                # minute low pierces 92.55 (open stays 100)
    (raw, real, s, kind), r = _run(C, 10, 65.0, XL, True, 1)
    assert kind == "rupee" and r[6] == E.R_STOP and r[5] == 20
    assert r[1] == pytest.approx(s)                                # triggered at the level
    assert r[3] < s                                                # but sold below it (R17 stop fill)
    assert Z.label(r[6], kind) == "stop500"


def test_gap_through_entry_stop_fills_at_the_open():
    C = _flat()
    C[0, 20], C[1, 20], C[2, 20], C[3, 20] = 85.0, 86.0, 84.0, 85.0
    _, r = _run(C, 10, 65.0, XL, True, 1)
    assert r[6] == E.R_STOP and r[1] == pytest.approx(85.0)


def test_step_trail_sets_and_exits():
    # 1 lot of 65, fill ~100.05: high 117 -> best ~ (117 - 100.05) x 65 = 1,102 -> locks +500 -> 107.75 or so
    C = _flat()
    C[1, 15] = 117.0
    C[2, 16], C[0, 16], C[3, 16] = 105.0, 112.0, 106.0             # falls through the step next minute
    (raw, real, s, kind), r = _run(C, 10, 65.0, NOTIME, True, 1)
    lv = Z.step_px(real, 65.0, (117.0 - real) * 65.0, 1)
    assert r[6] == Z.R_STEP and r[5] == 16
    assert r[1] == pytest.approx(lv) and (lv - real) * 65 >= 500.0
    assert r[3] < lv                                               # stop sale slips


def test_step_not_set_below_1000():
    C = _flat()
    C[1, 15] = 115.0                                               # best ~ 968 < 1,000: no step
    C[2, 16], C[0, 16], C[3, 16] = 105.0, 112.0, 106.0
    _, r = _run(C, 10, 65.0, NOTIME, True, 1)
    assert r[6] != Z.R_STEP


def test_step_new_level_acts_only_from_next_minute():
    # the same minute reaches +1,000 and dips below the would-be step: no exit that minute
    C = _flat()
    C[1, 15], C[2, 15] = 117.0, 104.0
    _, r = _run(C, 10, 65.0, NOTIME, True, 1)
    assert r[5] != 15


def test_step_only_moves_up_and_steps_per_lot():
    C = _flat()
    C[1, 15] = 125.0                                               # best ~1,622 -> +1,000 locked (115.45ish)
    C[0, 16], C[1, 16], C[2, 16], C[3, 16] = 118.0, 119.0, 117.0, 118.0   # stays above the step, lower best
    C[2, 17], C[0, 17], C[3, 17] = 113.0, 116.0, 114.0              # below 115.5 -> step exit at the higher level
    (raw, real, s, kind), r = _run(C, 10, 65.0, NOTIME, True, 1)
    lv = Z.step_px(real, 65.0, (125.0 - real) * 65.0, 1)
    assert r[6] == Z.R_STEP and r[1] == pytest.approx(lv) and (lv - real) * 65 >= 1000.0
    # 3 lots: per lot, so best and the step both scale with qty -> the same premium levels (up to fill slippage)
    (raw3, real3, s3, k3), r3 = _run(C, 10, 195.0, NOTIME, True, 3)
    lv3 = Z.step_px(real3, 195.0, (125.0 - real3) * 195.0, 3)
    assert r3[6] == Z.R_STEP and r3[1] == pytest.approx(lv3) and (lv3 - real3) * 195 >= 3000.0
    assert abs(lv3 - lv) <= 0.1


def test_njit_ceil_matches_python():
    for x in (107.892, 92.508, 100.0, 99.99999999, 0.051):
        assert Z._ceil_tick(x, 0.05) == pytest.approx(K.ceil_tick(x))


@pytest.mark.parametrize("seed", range(15))
def test_look_ahead_guard(seed):
    rng = np.random.default_rng(500 + seed)
    n = 375
    c = 100 * np.exp(np.cumsum(rng.normal(0, 0.01, n)))
    C = np.vstack([c, c * 1.004, c * 0.996, c, np.full(n, 1e5)])
    _, r = _run(C, 10, 130.0, XL, True, 2)
    xr = r[5]
    D = C.copy()
    if xr + 1 < n:
        D[:4, xr + 1:] = 100 * np.exp(rng.normal(0, 0.3, (4, n - xr - 1)))
    _, r2 = _run(D, 10, 130.0, XL, True, 2)
    assert r2[5] == xr and r2[1] == pytest.approx(r[1]) and r2[3] == pytest.approx(r[3])


# ------------------------------------------------------------------------------------------------ study helpers
def _t(day, net, why="time", arm=None):
    d = dict(day=day, net=net, why=why)
    if arm:
        d["arm"] = arm
    return d


def test_exit_mix():
    tr = [_t(date(2026, 5, 4), -600, "stop500"), _t(date(2026, 5, 4), 900, "step"), _t(date(2026, 5, 5), -800, "stop"),
          _t(date(2026, 5, 5), 100, "time")]
    m = S.exit_mix(tr)
    assert (m["stop500"], m["step"], m["other"], m["own_stop"]) == (1, 1, 2, 1)
    assert S.exit_mix([]) == dict(stop500=0, step=0, other=0, own_stop=0, reasons={})


def test_monthly_and_worst_month():
    tr = [_t(date(2026, 4, 20), 100), _t(date(2026, 5, 4), -300), _t(date(2026, 5, 6), 50)]
    m = S.monthly(tr, K.W6_MONTHS)
    assert m["2026-04"] == 100 and m["2026-05"] == -250 and m["2026-10"] == 0 and len(m) == 7
    assert S.worst_month(tr, K.W6_MONTHS) == ("2026-05", -250)
    assert S.worst_month([], K.W6_MONTHS)[1] == 0


def test_months_between_and_spans():
    assert K.W6_MONTHS == ("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10")
    assert K.months_between(date(2025, 11, 20), date(2026, 1, 2)) == ("2025-11", "2025-12", "2026-01")
    assert K.span_of("wf") == (date(2025, 4, 1), date(2026, 3, 31))


def test_together_sums_and_sorts():
    out = S.together({"LIQ": [_t(date(2026, 5, 5), 1)], "R72": [_t(date(2026, 5, 4), 2)], "R51": []})
    assert [t["arm"] for t in out] == ["R72", "LIQ"] and sum(t["net"] for t in out) == 3


def test_in_span():
    tr = [_t(date(2025, 3, 31), 1), _t(date(2025, 4, 1), 2), _t(date(2026, 3, 31), 3), _t(date(2026, 4, 1), 4)]
    assert [t["net"] for t in S.in_span(tr, "wf")] == [2, 3]


def _p(net, dd=1000, wm=-500, trades=100):
    return dict(net=net, max_dd=dd, worst_month=dict(month="2026-05", net=wm), trades=trades)


def test_gates_pass_and_each_failure():
    good = {p: dict(S=_p(10), A=_p(5)) for p in K.PERIODS}
    g = S.gates(good, 0.05)
    assert g["passed"] and g["fails"] == []
    bad = {p: dict(S=_p(10), A=_p(5)) for p in K.PERIODS}
    bad["six"]["S"] = _p(-1)
    assert "not positive in every period" in S.gates(bad, 0.05)["fails"]
    bad = {p: dict(S=_p(10), A=_p(50)) for p in K.PERIODS}
    assert "net not above today's rules (design, wf, final)" in S.gates(bad, 0.05)["fails"]
    assert any("BH" in f for f in S.gates(good, 0.2)["fails"])
    few = {p: dict(S=_p(10, trades=39), A=_p(5)) for p in K.PERIODS}
    assert "< 40 final trades" in S.gates(few, 0.05)["fails"]
    risky = {p: dict(S=_p(10), A=_p(5)) for p in K.PERIODS}
    risky["design"]["S"] = _p(10, dd=40001)
    assert any("account stop" in f for f in S.gates(risky, 0.05)["fails"])
    risky["design"]["S"] = _p(10, wm=-13001)
    assert any("account stop" in f for f in S.gates(risky, 0.05)["fails"])


def test_bh_count():
    assert K.M_TOTAL == 3979 and len(K.TESTS) == 9
    q = S.bh_q([0.002] * 9)
    assert len(q) == 9 and all(abs(x - min(1.0, 0.002 * 3979 / 9)) < 1e-9 for x in q)


def test_year_gates_use_the_amended_periods():
    per = {p: dict(S=_p(10), A=_p(5)) for p in K.YEAR_PERIODS}
    assert S.gates(per, 0.05, K.YEAR_PERIODS, K.YEAR_BETTER)["passed"]
    per["six"]["A"] = _p(50)                                       # six is not a "better than A" period
    assert S.gates(per, 0.05, K.YEAR_PERIODS, K.YEAR_BETTER)["passed"]
    per["early"]["S"] = _p(1, wm=-14000)
    f = S.gates(per, 0.05, K.YEAR_PERIODS, K.YEAR_BETTER)["fails"]
    assert any("early" in x for x in f)
    assert K.span_of("year") == (date(2025, 10, 20), date(2026, 10, 19))
    assert K.span_of("early") == (date(2025, 10, 20), date(2026, 3, 31))
    assert len(K.months_between(*K.span_of("year"))) == 13


def test_amendment_b_entry_stop_is_1000_a_lot():
    s = Z.entry_stop_px(100.2, 65.0, 1, 1000.0)                    # 100.2 - 15.385 = 84.815 -> 84.85
    assert s == pytest.approx(84.85) and (100.2 - s) * 65 <= 1000.0
    s3 = Z.entry_stop_px(100.2, 195.0, 3, 1000.0)
    assert s3 == pytest.approx(84.85) and (100.2 - s3) * 195 <= 3000.0
    assert Z.entry_stop_px(10.0, 65.0, 1, 1000.0) == -1.0
    # at Rs 100 the -15% stop (85.0) is tighter than 84.85: the arm's own stop stays, the trail stays on
    assert Z.levels(100.0, 100.2, 65.0, XL, True, 1, 1000.0) == (-1.0, 0.15, 500.0, "own")
    # at Rs 200 (lot 65): -15% = 170; Rs 1,000 / 65 = 15.4 -> 184.85 is tighter and acts
    s, f, st, k = Z.levels(200.0, 200.2, 65.0, XL, True, 1, 1000.0)
    assert (f, st, k) == (-1.0, 500.0, "rupee") and s == pytest.approx(184.85)
    assert K.M_TOTAL_B == 3988 and K.entry_amount(3, K.ENTRY_B) == 3000.0


def test_amendment_b_wider_stop_survives_a_dip_the_500_stop_does_not():
    C = _flat(px=200.0)
    C[2, 20] = 189.0                                               # dips 11 points: below the Rs 500 stop (192.55)
    _, r5 = _run(C, 10, 65.0, NOTIME, True, 1)
    raw, real, ef, _ = X.entry_info(C, 10, 360, 65.0)
    s, f, st, kind = Z.levels(raw, real, 65.0, NOTIME, True, 1, 1000.0)
    r10 = Z.simulate_step(C, 10, 360, -1, f, NOTIME.target, NOTIME.lock_trig, NOTIME.lock_keep, NOTIME.lock_trail,
                          NOTIME.steps_arr(), NOTIME.tmax, NOTIME.need, NOTIME.close_stop, 0.05, 65.0, 0.0, s, st, *E.SLIP)
    assert r5[6] == E.R_STOP and r5[5] == 20
    assert r10[5] != 20                                            # the Rs 1,000 stop (184.85) holds


def test_gap_1000_step_levels():
    assert K.step_locked(999.0, 1, 1000.0) is None
    assert K.step_locked(1000.0, 1, 1000.0) == 0.0                 # cost at +1,000
    assert K.step_locked(1499.0, 1, 1000.0) == 0.0
    assert K.step_locked(1500.0, 1, 1000.0) == 500.0
    assert K.step_locked(2000.0, 1, 1000.0) == 1000.0
    assert K.step_locked(4500.0, 3, 1000.0) == 1500.0              # 3 lots: 4,500 -> 4,500 - 3,000
    assert K.step_locked(1500.0, 1) == 1000.0                      # default gap unchanged


def test_gap_1000_in_the_simulator():
    C = _flat()
    C[1, 15] = 125.0                                               # best ~1,622 at 1 lot
    C[0, 16], C[1, 16], C[2, 16], C[3, 16] = 118.0, 119.0, 117.0, 118.0
    C[2, 17], C[0, 17], C[3, 17] = 107.0, 112.0, 108.0              # falls to 107: below +500 locked (107.75)
    raw, real, ef, _ = X.entry_info(C, 10, 360, 65.0)
    s, f, st, kind = Z.levels(raw, real, 65.0, NOTIME, True, 1)
    r = Z.simulate_step(C, 10, 360, -1, f, NOTIME.target, NOTIME.lock_trig, NOTIME.lock_keep, NOTIME.lock_trail,
                        NOTIME.steps_arr(), NOTIME.tmax, NOTIME.need, NOTIME.close_stop, 0.05, 65.0, 0.0, s, st, *E.SLIP,
                        1000.0)
    lv = Z.step_px(real, 65.0, (125.0 - real) * 65.0, 1, 1000.0)
    assert r[6] == Z.R_STEP and r[5] == 17 and r[1] == pytest.approx(lv) and (lv - real) * 65 >= 500.0
    # the Rs 500 gap (stop 115.5) also exits at minute 17, but at the open 112 (a gap through it), above 107.75
    r5 = Z.simulate_step(C, 10, 360, -1, f, NOTIME.target, NOTIME.lock_trig, NOTIME.lock_keep, NOTIME.lock_trail,
                         NOTIME.steps_arr(), NOTIME.tmax, NOTIME.need, NOTIME.close_stop, 0.05, 65.0, 0.0, s, st, *E.SLIP)
    assert r5[5] == 17 and r5[1] > r[1]
