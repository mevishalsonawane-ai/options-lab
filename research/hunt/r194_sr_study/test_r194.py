"""R194 tests (PREREG.md section 10, synthetic, no data): the look-ahead guard, the S/R target (call / put, no level, a gap
through, the last minute), the filters, the ablation, R151's threshold rule (on R151's committed curves), the Liquidity
signal copy (room 0 / 0.5 / 1 / 2, no level ahead, the new-level exit on / off, and fam_liq itself), the swap twin, the
reversed-tail p, BH with m = 4,808 and the gate.

    python -m pytest research/hunt/r194_sr_study -q
"""
from __future__ import annotations

import importlib.util
import json
import sys
from datetime import date, datetime
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r194cfg as K  # noqa: E402
import r194lib as L  # noqa: E402

sys.path.insert(0, str(K.R193))
import r193lib as G  # noqa: E402

R151 = K.HUNT / "r151_donchian_winrate60"
R150 = K.HUNT / "r150_sr_levels"


def _load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


PK = _load(R151 / "pick.py", "r151_pick_for_r194")
V = _load(R150 / "levels.py", "r150_levels_for_r194")
H4 = _load(K.HUNT / "h4" / "comps.py", "h4_comps_for_r194")


# ================================================================================================ the tests' list
def test_24_tests_and_m():
    assert len(K.TESTS) == 24
    assert K.M_TOTAL == 4808
    fams = [v[0] for v in K.TESTS.values()]
    assert fams.count("A") == 2 and fams.count("B") == 2 and fams.count("C") == 12 and fams.count("D") == 8


# ================================================================================================ look-ahead guard
def _minutes(n_days=25, seed=0):
    rng = np.random.default_rng(seed)
    days = []
    p = 20000.0
    for _ in range(n_days):
        c = p + np.cumsum(rng.normal(0, 8, 375))
        o = np.concatenate([[p], c[:-1]])
        h = np.maximum(o, c) + rng.uniform(0, 5, 375)
        lo = np.minimum(o, c) - rng.uniform(0, 5, 375)
        days.append(np.vstack([o, h, lo, c]))
        p = c[-1]
    return days


def _levels_at(days, day_i, er):
    b = V.bars15(days, minutes=5)
    piv = V.pivots(b["h"], b["l"], 6)
    return V.levels_at(b, piv, day_i, er, window=20)


def test_target_level_ignores_the_future():
    """The S/R level for a decision at minute er of the last day uses only minutes before er: the rest of the day and
    any later day replaced by noise changes nothing."""
    days = _minutes()
    di = len(days) - 1
    rng = np.random.default_rng(5)
    for er in (30, 120, 300):
        lv = _levels_at(days, di, er)
        d2 = [d.copy() for d in days]
        d2[di][:, er:] = rng.normal(50000, 500, (4, 375 - er))
        d2.append(rng.normal(50000, 500, (4, 375)))
        assert _levels_at(d2, di, er) == lv
        price = float(days[di][3, er - 1])
        assert L.target_level(lv, price, "C") == L.target_level(_levels_at(d2, di, er), price, "C")


def test_target_row_only_looks_from_the_fill():
    hi = np.full(20, 100.0)
    lo = np.full(20, 90.0)
    hi[3] = 120.0                                       # a touch before the fill must not count
    assert L.target_row(hi, lo, 5, "C", 110.0) == -1
    hi[8] = 111.0
    assert L.target_row(hi, lo, 5, "C", 110.0) == 9     # sold at the next minute's open


# ================================================================================================ the S/R target
def test_target_level_call_put_and_touches():
    lv = [(99.0, 5), (101.0, 2), (102.0, 3), (104.0, 7), (97.5, 3)]
    assert L.target_level(lv, 100.0, "C") == 102.0     # 101 has only 2 touches
    assert L.target_level(lv, 100.0, "P") == 99.0
    assert L.target_level([(101.0, 2)], 100.0, "C") is None
    assert L.target_level(lv, float("nan"), "C") is None


def test_target_level_matches_r151_sr_ahead():
    """The level behind R151's sr_ahead: the same distance."""
    lv = [(99.0, 5), (102.0, 3), (104.0, 7)]
    price = 100.0
    ahead = [abs(l_ / price - 1) for l_, n in lv if n >= 3 and (l_ - price) > 0]
    assert abs(L.target_level(lv, price, "C") / price - 1) == min(ahead)


def test_target_row_put_gap_and_last_minute():
    hi = np.full(10, 105.0)
    lo = np.full(10, 101.0)
    lo[4] = 95.0                                        # a gap through the level: touched in that minute
    assert L.target_row(hi, lo, 2, "P", 99.0) == 5
    lo2 = np.full(10, 101.0)
    lo2[9] = 98.0                                       # touched in the last minute: no next minute to sell in
    assert L.target_row(hi, lo2, 2, "P", 99.0) == -1
    assert L.target_row(hi, lo, -1, "P", 99.0) == -1    # no fill
    assert L.target_row(hi, lo, 2, "P", None) == -1     # no level


def test_target_row_skips_missing_minutes_and_respects_n():
    hi = np.array([100, np.nan, np.nan, 112, 100.0])
    lo = np.full(5, 95.0)
    assert L.target_row(hi, lo, 0, "C", 110.0) == 4
    assert L.target_row(hi, lo, 0, "C", 110.0, n=3) == -1          # the square-off row comes first


def test_earlier():
    assert L.earlier(-1, 5) == 5 and L.earlier(7, 5) == 5 and L.earlier(-1, -1) == -1 and L.earlier(None, 3) == 3


# ================================================================================================ filters
def test_wall_and_cross():
    assert L.wall_skip(0.0015, 0.0015) and L.wall_skip(0.001, 0.0015) and not L.wall_skip(0.0016, 0.0015)
    assert not L.wall_skip(L.NO_LEVEL, 0.003)          # no level ahead: never skipped
    assert not L.wall_skip(float("nan"), 0.003)
    assert L.cross_skip(0.0) and not L.cross_skip(1.0) and not L.cross_skip(float("nan"))


# ================================================================================================ ablation
def _nums():
    return json.loads((R151 / "results" / "r72_picker_model.json").read_text())


def test_ablation_equals_missing_inputs():
    nums = _nums()
    rng = np.random.default_rng(1)
    for _ in range(20):
        row = np.array(nums["mean"]) + rng.normal(0, 1, len(nums["feats"])) * np.array(nums["scale"])
        miss = row.copy()
        for f in L.SR_FEATS:
            miss[nums["feats"].index(f)] = np.nan
        a = PK.score_from_numbers(nums, np.array(L.ablate(nums, row)))
        b = PK.score_from_numbers(nums, miss)
        assert a == pytest.approx(b, abs=1e-15)
        # only the S/R inputs change
        ab = L.ablate(nums, row)
        for i, f in enumerate(nums["feats"]):
            if f not in L.SR_FEATS:
                assert ab[i] == row[i]


def test_drop_feats():
    nums = _nums()
    f = L.drop_feats(nums)
    assert len(f) == 23 and not set(f) & set(L.SR_FEATS)


# ================================================================================================ threshold rule
def _curve(fn, pid, arm):
    d = json.loads((R151 / "results" / fn).read_text())
    p = d[arm]["pickers"][pid]
    return p["curve"], p["base_wf"]


def test_threshold_rule_reproduces_the_app_thresholds():
    c, b = _curve("design.json", "R51-20-3.0-M2", "R51")
    thr, how = L.threshold_rule(c, b, PK.first_pass)
    assert how == "best_point" and thr == pytest.approx(0.4469, abs=5e-5)
    c, b = _curve("bug_cache/design.json", "R72-20-4-M2", "R72")
    thr, how = L.threshold_rule(c, b, PK.first_pass)
    assert how == "best_point" and thr == pytest.approx(0.4656, abs=5e-5)
    c, b = _curve("design.json", "R72-20-4-M2", "R72")
    assert L.threshold_rule(c, b, PK.first_pass)[0] == pytest.approx(0.4640, abs=5e-5)


def test_threshold_rule_first_pass_first_ties_and_none():
    base = dict(trades=100, per_trade=10.0)
    curve = [dict(thr=0.1, trades=100, win=0.5, per_trade=10), dict(thr=0.2, trades=50, win=0.62, per_trade=20),
             dict(thr=0.3, trades=30, win=0.7, per_trade=30)]
    assert L.threshold_rule(curve, base, PK.first_pass) == (0.2, "first_pass")
    tie = [dict(thr=0.1, trades=100, win=0.5, per_trade=10), dict(thr=0.2, trades=60, win=0.55, per_trade=12),
           dict(thr=0.3, trades=40, win=0.55, per_trade=15), dict(thr=0.4, trades=20, win=0.59, per_trade=40)]
    assert L.threshold_rule(tie, base, PK.first_pass) == (0.2, "best_point")       # 0.4 keeps < 25%; tie -> lower
    bad = [dict(thr=0.1, trades=100, win=0.5, per_trade=5)]
    assert L.threshold_rule(bad, base, PK.first_pass) == (None, "none")


# ================================================================================================ Liquidity copy
def _rows():
    return [dict(er=10, side="C", sgn=1, close=100.0, strike=100, idx_stop=90.0, target=130.0, room=30.0, istop=30.0,
                 fb_exit=9 * 60 + 15 + 40, nl_exit=9 * 60 + 15 + 25),
            dict(er=20, side="P", sgn=-1, close=100.0, strike=100, idx_stop=110.0, target=85.0, room=15.0, istop=30.0,
                 fb_exit=None, nl_exit=None),
            dict(er=30, side="C", sgn=1, close=100.0, strike=100, idx_stop=90.0, target=np.nan, room=float("inf"),
                 istop=30.0, fb_exit=None, nl_exit=9 * 60 + 15 + 50),
            dict(er=40, side="C", sgn=1, close=100.0, strike=100, idx_stop=90.0, target=160.0, room=60.0, istop=30.0,
                 fb_exit=None, nl_exit=None)]


def test_liq_room_levels():
    rows = _rows()
    assert [r["er"] for r in L.liq_select(rows, 0.0, True)] == [10, 20, 30, 40]
    assert [r["er"] for r in L.liq_select(rows, 0.5, True)] == [10, 20, 30, 40]   # 15 >= 0.5 x 30
    assert [r["er"] for r in L.liq_select(rows, 1.0, True)] == [10, 30, 40]       # exactly 1 stop is kept
    assert [r["er"] for r in L.liq_select(rows, 2.0, True)] == [30, 40]           # no level ahead: always kept


def test_liq_new_level_exit_on_off():
    on = {r["er"]: r["bar_exit"] for r in L.liq_select(_rows(), 0.0, True)}
    off = {r["er"]: r["bar_exit"] for r in L.liq_select(_rows(), 0.0, False)}
    assert on == {10: 25, 20: -1, 30: 50, 40: -1}
    assert off == {10: 40, 20: -1, 30: -1, 40: -1}
    assert "room" not in L.liq_select(_rows(), 0.0, True)[0]


def _fam_day_signals(d, bars, tf, istop, step, room, first_m, last_m):
    """fam_liq._day_signals as written in research/hunt/r51/fam_liq.py (the entry window passed in), for the parity test."""
    src = (K.HUNT / "r51" / "fam_liq.py").read_text()
    start = src.index("def _day_signals")
    end = src.index("def forced_row")
    body = src[start:end].replace("OPEN_M + FIRST", "first_m").replace("OPEN_M + LAST", "last_m")
    body = body.replace("def _day_signals(d, bars, tf, istop, step, room):",
                        "def _day_signals(d, bars, tf, istop, step, room, first_m, last_m):")
    ns = {"np": np, "H4": H4, "OPEN_M": L.OPEN_M}
    exec(compile(body, "fam_liq_day_signals", "exec"), ns)  # noqa: S102  (the repo's own file)
    return ns["_day_signals"](d, bars, tf, istop, step, room, first_m, last_m)


def _bars(n_days=6, tf=15, seed=3):
    rng = np.random.default_rng(seed)
    ones = []
    p = 45000.0
    for k in range(n_days):
        d = date(2026, 1, 5 + k)
        for r in range(375):
            o = p
            c = p + rng.normal(0, 12)
            ones.append((d, L.OPEN_M + r, o, max(o, c) + abs(rng.normal(0, 6)), min(o, c) - abs(rng.normal(0, 6)), c))
            p = c
    return H4.fold(ones, tf), date(2026, 1, 5 + n_days - 1)


@pytest.mark.parametrize("seed", [3, 4, 11])
@pytest.mark.parametrize("room", [0.0, 0.5, 1.0, 2.0])
def test_liq_copy_matches_fam_liq(seed, room):
    """R194's copy at room r with the new-level exit on gives fam_liq's rows exactly."""
    bars, d = _bars(seed=seed)
    first_m, last_m = L.OPEN_M + 5, L.OPEN_M + 285
    want = _fam_day_signals(d, bars, 15, 30.0, 100, room, first_m, last_m)
    got = L.liq_select(L.liq_day_signals(H4, d, bars, 15, 30.0, 100, first_m, last_m), room, True)
    assert len(got) == len(want)
    for a, b in zip(got, want):
        assert set(a) == set(b)
        for k in a:
            assert (a[k] == b[k]) or (isinstance(a[k], float) and np.isnan(a[k]) and np.isnan(b[k]))


def test_liq_signals_ignore_later_bars():
    """A signal at bar i reads only bars <= i for its entry and room (the exits read later bars by design)."""
    bars, d = _bars(seed=4)
    first_m, last_m = L.OPEN_M + 5, L.OPEN_M + 285
    full = L.liq_day_signals(H4, d, bars, 15, 30.0, 100, first_m, last_m)
    for cut in (len(bars) - 10, len(bars) - 5):
        part = L.liq_day_signals(H4, d, bars[:cut], 15, 30.0, 100, first_m, last_m)
        last_end = bars[cut - 1][1] + 15 - L.OPEN_M
        a = [(r["er"], r["side"], r["room"], r["strike"]) for r in full if r["er"] <= last_end]
        b = [(r["er"], r["side"], r["room"], r["strike"]) for r in part]
        # pivots need L bars after them to be known, so a cut can only remove signals near its end, never change one
        assert set(b) <= set(a)


# ================================================================================================ twins / p
def test_swap_mask_counts():
    rng = np.random.default_rng(0)
    kb = np.array([1, 1, 1, 0, 0, 1, 0, 1], bool)
    for _ in range(50):
        m = L.swap_mask(kb, 2, 1, rng)
        assert m.sum() == kb.sum() - 2 + 1
        assert (m & ~kb).sum() == 1 and (kb & ~m).sum() == 2
    assert np.array_equal(L.swap_mask(kb, 0, 0, rng), kb)


def test_p_upper_lower_and_normal():
    tw = np.arange(100, dtype=float)
    assert L.p_upper(99, tw) == pytest.approx(2 / 101)
    assert L.p_upper(1000, tw) == pytest.approx(1 / 101)
    assert L.p_lower(0, tw) == pytest.approx(2 / 101)
    assert L.p_lower(-5, tw) == pytest.approx(1 / 101)
    assert L.normal_p(49.5, tw, True) == pytest.approx(0.5)
    assert L.normal_p(200, tw, True) < 1e-6 and L.normal_p(-100, tw, False) < 1e-6
    assert L.normal_p(200, tw, False) > 0.999
    assert L.normal_p(5, np.full(10, 3.0), True) == 0.0 and L.normal_p(5, np.full(10, 3.0), False) == 1.0


def test_bh_with_m_4808():
    q = G.bh_total([1e-6, 0.01, 1.0], K.M_TOTAL)
    assert q[0] == pytest.approx(1e-6 * 4808)
    assert q[1] == pytest.approx(min(1.0, 0.01 * 4808 / 2))


def _w(net, dd, n):
    return dict(net=net, max_dd=dd, trades=n, net_wo_top5=net)


def test_gate_each_item_fails_alone():
    base = {"W1": _w(100, 50, 100), "W2": _w(100, 50, 100), "total": _w(200, 60, 200)}
    good = {"W1": _w(150, 40, 60), "W2": _w(150, 40, 60), "total": dict(_w(300, 45, 120), net_wo_top5=250)}
    assert G.gate(good, base, 0.01, 0.05)["helps"]
    for w in ("W1", "W2"):
        for k, v in (("net", 90), ("max_dd", 55), ("trades", 39)):
            bad = json.loads(json.dumps(good))
            bad[w][k] = v
            g = G.gate(bad, base, 0.01, 0.05)
            assert not g["helps"] and len(g["fails"]) == 1
    bad = json.loads(json.dumps(good))
    bad["total"]["net_wo_top5"] = 150
    assert len(G.gate(bad, base, 0.01, 0.05)["fails"]) == 1
    assert len(G.gate(good, base, 0.05, 0.05)["fails"]) == 1
    assert len(G.gate(good, base, 0.01, 0.11)["fails"]) == 1


def test_windows_are_r193s():
    assert K.WINDOWS["W1"] == (date(2024, 11, 1), date(2026, 3, 31))
    assert K.WINDOWS["W2"] == (date(2026, 4, 1), date(2026, 11, 13))
    assert isinstance(datetime.now(), datetime)
