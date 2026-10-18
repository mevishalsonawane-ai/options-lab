"""R80 tests (18 Oct 2026): the entry-filter features (round number, sweep, body, stack, first-hour gate), the
look-ahead guard (no feature reads a bar after the breaking bar; the first-hour gate reads only rows 0-59 and earlier
sessions; L1 reads only trades already closed), the filters, the book walk (as R51 common.take), the post-walk caps,
that day_signals keeps fam_liq's rows unchanged, and the one-time final read. Synthetic data only.

    python -m pytest research/hunt/r80 -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r80rules as R  # noqa: E402


# ------------------------------------------------------------------------------------------------ features
def test_round_number_crossed_call_and_put():
    assert R.crosses_round(1, 48480.0, 48530.0, 30.0, 500.0)          # 48500 inside [48450, 48560]
    assert not R.crosses_round(1, 48100.0, 48200.0, 30.0, 500.0)      # [48070, 48230]: none
    assert R.crosses_round(1, 48420.0, 48460.0, 30.0, 500.0) is False  # [48390, 48490]: 48500 is beyond
    assert R.crosses_round(-1, 48520.0, 48480.0, 30.0, 500.0)         # put: [48450, 48550]
    assert not R.crosses_round(-1, 48300.0, 48260.0, 30.0, 500.0)
    assert R.crosses_round(1, 24990.0, 25010.0, 15.0, 100.0)          # NIFTY 25000
    assert R.crosses_round(1, 24470.0, 24480.0, 15.0, 500.0) is False


def test_sweep_needs_wick_beyond_and_close_back_inside_after_known():
    H = np.array([10, 10.7, 12, 10.5, 10.6, 13], float)
    Lo = np.array([9, 9, 9, 9, 9, 9], float)
    Cl = np.array([9.5, 10, 9.8, 10, 10, 12.5], float)
    edge = 10.8
    # bar 2 wicks to 12 and closes 9.8 (inside): a sweep if bar 2 is after `known`
    assert R.swept_before(1, edge, H, Lo, Cl, known=1, i=5)
    assert not R.swept_before(1, edge, H, Lo, Cl, known=2, i=5)         # bar 2 is the known bar itself
    assert not R.swept_before(1, edge, H, Lo, Cl, known=0, i=2)         # bars >= i never read
    # put side
    Lo2 = np.array([9, 8.5, 9, 9, 9, 7], float)
    Cl2 = np.array([9.5, 9.2, 9.6, 9.4, 9.3, 7.5], float)
    assert R.swept_before(-1, 8.8, H, Lo2, Cl2, known=0, i=5)
    assert not R.swept_before(-1, 8.8, H, Lo2, Cl2, known=1, i=5)


def test_sweep_ignores_future_bars():
    H = np.array([10, 10.5, 10.6, 10.7, 20.0], float)
    Lo = np.zeros(5)
    Cl = np.array([10, 10.2, 10.3, 10.4, 5.0], float)
    a = R.swept_before(1, 10.8, H, Lo, Cl, 0, 4)
    H2, Cl2 = H.copy(), Cl.copy()
    H2[4], Cl2[4] = 99.0, 1.0
    assert a == R.swept_before(1, 10.8, H2, Lo, Cl2, 0, 4) is False


def test_body_ratio_uses_only_the_bars_before():
    O = np.zeros(25)
    Cl = np.ones(25)
    Cl[21] = 3.0
    assert R.body_ratio(O, Cl, 21) == pytest.approx(3.0)
    Cl2 = Cl.copy()
    Cl2[22:] = 100.0                         # later bars change nothing
    assert R.body_ratio(O, Cl2, 21) == pytest.approx(3.0)
    assert np.isnan(R.body_ratio(O, Cl, 10))  # fewer than 20 bars
    assert np.isnan(R.body_ratio(np.zeros(25), np.zeros(25), 21))  # flat history


class Z:
    def __init__(self, side, top, bottom, known, broken=-1):
        self.side, self.top, self.bottom, self.known, self.broken = side, top, bottom, known, broken


def test_stack_count_known_unbroken_overlapping_same_side():
    zs = [Z(1, 101, 99, 3, 10), Z(1, 100.5, 99.5, 5), Z(1, 102, 100.8, 4),   # overlaps [99, 100.5]? third: no
          Z(-1, 101, 99, 2), Z(1, 100, 99, 12), Z(1, 100, 99, 2, 7)]          # other side; known later; broken before
    assert R.stack_count(zs, 1, 100.5, 99.0, 10) == 2


def test_first_hour_gate_needs_20_sessions_and_reads_rows_0_59():
    idx = np.full((4, 375), np.nan)
    idx[1, :60], idx[2, :60] = 105.0, 95.0
    idx[1, 60:], idx[2, 60:] = 500.0, -500.0                  # after 10:15: never read
    assert R.first_hour_range(idx) == 10.0
    assert R.fh_gate(10.0, [8.0] * 19) is None
    assert R.fh_gate(10.0, [8.0] * 10 + [12.0] * 10) is True    # median 10
    assert R.fh_gate(9.9, [8.0] * 10 + [12.0] * 10) is False
    assert R.fh_gate(10.0, [50.0] * 5 + [8.0] * 20) is True     # only the last 20 count
    assert R.fh_gate(float("nan"), [8.0] * 20) is None
    assert np.isnan(R.first_hour_range(np.full((4, 375), np.nan)))


# ------------------------------------------------------------------------------------------------ filters
def _c(**k):
    base = dict(er=100, f_prior=False, f_stack=2, f_round=False, f_swept=False, f_pdx=False, f_body=1.0, f_fh=True)
    base.update(k)
    return base


def test_keep_signal_each_rule():
    assert R.keep_signal("V1", _c(f_prior=True)) and not R.keep_signal("V1", _c())
    assert R.keep_signal("V2", _c(f_stack=3)) and not R.keep_signal("V2", _c(f_stack=2))
    assert R.keep_signal("V3", _c(f_round=True)) and not R.keep_signal("V3", _c())
    assert R.keep_signal("V4", _c()) and not R.keep_signal("V4", _c(f_swept=True))
    assert R.keep_signal("V5", _c(f_pdx=True)) and not R.keep_signal("V5", _c(f_pdx=None))
    assert R.keep_signal("V6", _c(er=60)) and not R.keep_signal("V6", _c(er=59))
    assert R.keep_signal("V7", _c(er=60)) and not R.keep_signal("V7", _c(er=59))
    assert not R.keep_signal("V7", _c(f_fh=None)) and not R.keep_signal("V7", _c(f_fh=False))
    assert R.keep_signal("V8", _c(f_body=1.5)) and not R.keep_signal("V8", _c(f_body=1.49))
    assert not R.keep_signal("V8", _c(f_body=float("nan")))
    with pytest.raises(ValueError):
        R.keep_signal("V9", _c())


def _r(er, xr, net):
    return dict(er=er + 1, xr=xr, net=net, und="BANKNIFTY", day=date(2025, 5, 2))


def test_walk_matches_take_one_position_per_book():
    cands = [dict(er=10, side="C"), dict(er=12, side="P"), dict(er=20, side="C"), dict(er=21, side="C"),
             dict(er=40, side="P")]
    res = [_r(10, 20, 1.0), _r(12, 30, 2.0), _r(20, 25, 3.0), None, _r(40, 45, 5.0)]
    t = R.walk(cands, res)
    assert [x["net"] for x in t] == [1.0, 5.0]          # er 12 and 20 blocked (er <= busy 20), er 21 no fill
    t2 = R.walk(cands, res, keep=[False, True, True, True, True])
    assert [x["net"] for x in t2] == [2.0, 5.0]         # dropping the first frees the book for er 12
    assert all("fx" not in x for x in t2)


def test_post_walk_cap_and_stop_after_first_loss():
    ts = [dict(er=10, xr=30, net=-5.0, book=15), dict(er=12, xr=14, net=3.0, book=5),
          dict(er=31, xr=40, net=4.0, book=5), dict(er=30, xr=50, net=2.0, book=15)]
    assert [t["er"] for t in R.post_walk("V9", ts)] == [10, 12]
    kept = R.post_walk("V10", ts)
    # the loss closes at row 30: the trade entered at 30 is kept (not strictly after), 31 is dropped
    assert [t["er"] for t in kept] == [10, 12, 30]
    assert [t["er"] for t in R.post_walk("V10", [dict(er=5, xr=9, net=1.0), dict(er=10, xr=12, net=-1.0)])] == [5, 10]


# ------------------------------------------------------------------------------------------------ signals
def _h4():
    pytest.importorskip("numba")
    sys.path.insert(0, str(HERE.parent / "r51"))
    import oldcode
    return oldcode.load("h4", "comps")


def _bars(n_prev=60, n_today=40, seed=3):
    rng = np.random.default_rng(seed)
    d0, d1 = date(2025, 5, 1), date(2025, 5, 2)
    out, px = [], 50000.0
    for k in range(n_prev + n_today):
        d = d0 if k < n_prev else d1
        m = 555 + 5 * (k if k < n_prev else k - n_prev)
        o = px
        c = o + rng.normal(0, 40)
        h, lo = max(o, c) + abs(rng.normal(0, 25)), min(o, c) - abs(rng.normal(0, 25))
        out.append((d, m, o, h, lo, c))
        px = c
    return d1, out


def test_day_signals_rows_equal_fam_liq():
    H4 = _h4()
    sys.path.insert(0, str(HERE.parent / "r51"))
    import fam_liq
    n_rows = 0
    for seed in range(12):
        d, bars = _bars(seed=seed)
        ref = fam_liq._day_signals(d, bars, 5, 30.0, 100.0, 1.0)
        mine = R.day_signals(H4, d, bars, 5, 30.0, 100.0, 1.0, 500.0, (50100.0, 49900.0))
        assert len(ref) == len(mine)
        for a, b in zip(ref, mine):
            for k, v in a.items():
                assert (np.isnan(v) and np.isnan(b[k])) if isinstance(v, float) and np.isnan(v) else b[k] == v
        n_rows += len(mine)
    assert n_rows > 0


def test_features_ignore_bars_after_the_break():
    H4 = _h4()
    seen = 0
    for seed in range(12):
        d, bars = _bars(seed=seed)
        rows = R.day_signals(H4, d, bars, 5, 30.0, 100.0, 1.0, 500.0, (50100.0, 49900.0))
        for s in rows:
            i = next(k for k, b in enumerate(bars) if b[0] == d and b[1] + 5 - 555 == s["er"])
            # replace every later bar with wild prices: the features of this row must not change
            wild = bars[:i + 1] + [(b[0], b[1], 1e5, 1e5 + 1, 1.0, 2.0) for b in bars[i + 1:]]
            again = [r for r in R.day_signals(H4, d, wild, 5, 30.0, 100.0, 1.0, 500.0, (50100.0, 49900.0))
                     if r["er"] == s["er"] and r["side"] == s["side"]]
            if not again:          # the later bars changed which pool the zones report; the features still must not
                continue
            for k in ("f_prior", "f_stack", "f_round", "f_swept", "f_pdx", "close", "level"):
                assert again[0][k] == s[k], k
            assert again[0]["f_body"] == pytest.approx(s["f_body"], nan_ok=True)
            seen += 1
    assert seen > 0


def test_final_read_only_once(tmp_path, monkeypatch):
    pytest.importorskip("numba")
    import r80study as S
    flag = tmp_path / "FINAL_READ"
    flag.write_text("2026-10-18\n")
    monkeypatch.setattr(S, "FINAL_FLAG", flag)
    with pytest.raises(SystemExit):
        S.run("final", ())


def test_decide_gates():
    pytest.importorskip("numba")
    import r80study as S
    good = dict(diff=5000, twin_p=0.001, top5_diff=1000, all=dict(trades=60))
    d = {v: dict(wf_diff=100) for v in R.VARIANTS}
    f = {v: dict(diff=-1, twin_p=0.9, top5_diff=-1, all=dict(trades=60)) for v in R.VARIANTS}
    f["V3"] = good
    out = S.decide(d, f)
    assert out["V3"]["passed"] and not out["V1"]["passed"]
    f["V3"] = dict(good, top5_diff=-5)
    assert "carried by top 5" in S.decide(d, f)["V3"]["fails"]
    f["V3"] = dict(good, all=dict(trades=39))
    assert "< 40 final trades" in S.decide(d, f)["V3"]["fails"]
