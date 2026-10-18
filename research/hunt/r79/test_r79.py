"""R79 tests (18 Oct 2026): the bar builder, the Supertrend read (only bars closed before the signal row; earlier day
when the day has none; same-day for breadth), the 10-candle waits (missing candles, the later price, the entry window,
an index exit during the wait, the new strike), breadth counting, the skip accounting, random-skip twins, the decision
rule and the one-time final read. Look-ahead guard: changing any index minute at or after the row a filter decides at
never changes the decision. Synthetic data only.

    python -m pytest research/hunt/r79 -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r73"))

import r73rules  # noqa: E402
import r79rules as RU  # noqa: E402
import r79study as S  # noqa: E402

N = RU.NROWS
D1, D2, D3 = date(2025, 1, 6), date(2025, 1, 7), date(2025, 1, 8)


def _day(seed, drift=0.0, gaps=0):
    r = np.random.default_rng(seed)
    c = 50000.0 + np.cumsum(r.normal(drift, 8.0, N))
    o = np.r_[c[0], c[:-1]]
    h, lo = np.maximum(o, c) + 3.0, np.minimum(o, c) - 3.0
    a = np.vstack([o, h, lo, c])
    if gaps:
        a[:, r.choice(N, gaps, replace=False)] = np.nan
    return a


def _days(n=6, drift=0.0, seed=0, gaps=0):
    out, d = {}, date(2025, 1, 1)
    for k in range(n):
        out[date.fromordinal(d.toordinal() + k)] = _day(seed + k, drift, gaps)
    return out


# ------------------------------------------------------------------------------------------------ bars / Supertrend
def test_bars_ohlc_and_skipped_slot():
    a = _day(1)
    a[:, 5:10] = np.nan                                         # slot 1 empty -> skipped
    a[3, 14] = np.nan                                           # last minute of slot 2 missing -> its close is row 13
    d, s, o, h, l, c = RU.bars({D1: a}, 5)
    assert list(s[:3]) == [0, 2, 3] and len(s) == 74
    assert o[0] == a[0, 0] and h[0] == a[1, :5].max() and l[0] == a[2, :5].min() and c[0] == a[3, 4]
    assert c[1] == a[3, 13]
    d15, s15, *_ = RU.bars({D1: _day(1)}, 15)
    assert len(s15) == 25


def test_trend_uses_r73_supertrend():
    days = _days(4)
    tr = RU.Trend.build(days, 5)
    _, _, _, h, l, c = RU.bars(days, 5)
    assert np.array_equal(tr.dir, r73rules.supertrend_dir(h, l, c, 10, 3.0))


def test_trend_at_reads_last_closed_bar():
    days = _days(3)
    tr = RU.Trend.build(days, 5)
    d = sorted(days)[2]
    j = [i for i, (x, s) in enumerate(zip(tr.day, tr.slot)) if x == d and s == 11][0]
    assert tr.at(d, 60) == tr.dir[j]                            # row 60: slot 11 (rows 55-59) is the last closed
    assert tr.at(d, 64) == tr.dir[j]                            # slot 12 not closed until row 65
    assert tr.at(d, 65) == tr.dir[j + 1]
    prev = [i for i, x in enumerate(tr.day) if x == sorted(days)[1]][-1]
    assert tr.at(d, 3) == tr.dir[prev]                          # no closed bar today -> yesterday's last
    assert tr.at(d, 3, same_day=True) == 0
    assert tr.at(sorted(days)[0], 0) == 0                       # nothing before -> 0


@pytest.mark.parametrize("tf,er", [(5, 60), (5, 125), (15, 60), (15, 135), (15, 140)])
def test_lookahead_trend(tf, er):
    days = _days(4, seed=3)
    d = sorted(days)[3]
    base = RU.Trend.build(days, tf).at(d, er)
    for k in range(5):
        alt = {x: a.copy() for x, a in days.items()}
        r = np.random.default_rng(k)
        alt[d][:, er:] = alt[d][:, er:] + r.normal(0, 400, (4, N - er))     # rewrite every minute from er on
        assert RU.Trend.build(alt, tf).at(d, er) == base


def test_trend_up_and_down():
    up = RU.Trend.build(_days(4, drift=6.0), 5)
    dn = RU.Trend.build(_days(4, drift=-6.0), 5)
    last = sorted(_days(4))[-1]
    assert up.at(last, 300) == 1 and dn.at(last, 300) == -1
    assert RU.trend_agrees(up, last, 300, 1) and not RU.trend_agrees(up, last, 300, -1)


class _Fixed:
    def __init__(self, v, has_day=True):
        self.v, self.has_day = v, has_day

    def at(self, day, er, same_day=False):
        return 0 if (same_day and not self.has_day) else self.v


def test_breadth_count_excludes_own_index_and_missing():
    t = {"NIFTY": _Fixed(1), "BANKNIFTY": _Fixed(1), "FINNIFTY": _Fixed(-1), "MIDCPNIFTY": _Fixed(1),
         "SENSEX": _Fixed(1, has_day=False)}
    assert RU.breadth_count(t, "BANKNIFTY", D1, 100, 1) == 2         # NIFTY, MIDCP; own skipped; SENSEX no data
    assert RU.breadth_count(t, "FINNIFTY", D1, 100, 1) == 3
    assert RU.breadth_count(t, "BANKNIFTY", D1, 100, -1) == 1


def test_breadth_real_trends_same_day_only():
    days = _days(3, drift=6.0)
    t = {u: RU.Trend.build(days, 5) for u in ("NIFTY", "SENSEX", "BANKNIFTY")}
    d = sorted(days)[2]
    assert RU.breadth_count(t, "BANKNIFTY", d, 200, 1) == 2
    assert RU.breadth_count(t, "BANKNIFTY", d, 4, 1) == 0             # no bar closed today yet -> not agreeing


# ------------------------------------------------------------------------------------------------ waits
def _flat(v=100.0):
    a = np.full((4, N), v)
    return a


def test_wait_1min_agrees_and_entry_row():
    a = _flat()
    a[3, 109] = 101.0
    assert RU.wait_entry(a, 100, 1, 100.0, 1) == (110, 101.0)
    assert RU.wait_entry(a, 100, -1, 100.0, 1) is None
    a[3, 109] = 100.0
    assert RU.wait_entry(a, 100, 1, 100.0, 1) is None                # equal is not beyond


def test_wait_missing_candle_drops():
    a = _flat()
    a[3, 109] = 101.0
    a[3, 104] = np.nan
    assert RU.wait_entry(a, 100, 1, 100.0, 1) is None


def test_wait_5min_last_nonmissing_close_and_gap_tolerance():
    a = _flat()
    a[3, 149] = np.nan                                                 # 10th slot rows 145-149: last close is row 148
    a[3, 148] = 99.0
    assert RU.wait_entry(a, 100, -1, 100.0, 5) == (150, 99.0)
    a[3, 120:125] = np.nan                                             # a whole 5-minute candle missing -> drop
    assert RU.wait_entry(a, 100, -1, 100.0, 5) is None


def test_wait_past_session_end():
    a = _flat()
    assert RU.wait_entry(a, N - 10, 1, 90.0, 1) is None
    assert RU.wait_entry(a, N - 11, 1, 90.0, 1) == (N - 1, 100.0)


@pytest.mark.parametrize("width", [1, 5])
def test_lookahead_wait(width):
    a = _day(7)
    er = 120
    span = 10 * width
    base = RU.wait_entry(a, er, 1, a[3, er - 1], width)
    for k in range(5):
        b = a.copy()
        b[:, er + span:] = np.random.default_rng(k).normal(50000, 500, (4, N - er - span))
        b[:, :er] = np.random.default_rng(k + 9).normal(50000, 500, (4, er))
        b[[0, 1, 2], er:er + span] = 0.0                                # only closes of the wait rows are read
        assert RU.wait_entry(b, er, 1, a[3, er - 1], width) == base


# ------------------------------------------------------------------------------------------------ filter_signal
def _ctx(t5=None, t15=None):
    return dict(und="BANKNIFTY", day=D1, step=100.0, last=285, atm=lambda x, s: int(np.floor(x / s + 0.5) * s),
                t5=t5 or {}, t15=t15)


def _cand(er=100, sgn=1, close=50020.0, fx=-1):
    return dict(er=er, sgn=sgn, side="C" if sgn > 0 else "P", close=close, strike=49900.0, fx=fx, book=15, sig_er=er)


def test_filter_b0_and_supertrend_routes():
    c = _cand()
    assert S.filter_signal("B0", c, _flat(), _ctx()) is c
    ctx = _ctx(t5={"BANKNIFTY": _Fixed(1), "NIFTY": _Fixed(-1)}, t15=_Fixed(-1))
    assert S.filter_signal("S1", c, _flat(), ctx) is c
    assert S.filter_signal("S2", c, _flat(), ctx) is None
    assert S.filter_signal("S1", _cand(sgn=-1), _flat(), ctx) is None


def test_filter_breadth_thresholds():
    t5 = {"NIFTY": _Fixed(1), "BANKNIFTY": _Fixed(-1), "FINNIFTY": _Fixed(1), "MIDCPNIFTY": _Fixed(-1),
          "SENSEX": _Fixed(-1)}
    c = _cand()
    assert S.filter_signal("X1", c, _flat(), _ctx(t5=t5)) is c                   # 2 others agree
    assert S.filter_signal("X2", c, _flat(), _ctx(t5=t5)) is None                # needs 3


def test_filter_wait_entry_strike_window_and_exit():
    a = _flat(50020.0)
    a[3, 109] = 50180.0
    c = _cand()
    w = S.filter_signal("W10a", c, a, _ctx())
    assert w["er"] == 110 and w["strike"] == 50100.0 and w["waited_from"] == 100 and w["sig_er"] == 100
    assert c["er"] == 100                                                        # the original is not changed
    assert S.filter_signal("W10a", _cand(fx=110), a, _ctx()) is None             # index exit at the entry row
    assert S.filter_signal("W10a", _cand(fx=105), a, _ctx()) is None
    assert S.filter_signal("W10a", _cand(fx=111), a, _ctx())["er"] == 110
    b = _flat(50020.0)
    b[3, 284] = 50100.0
    assert S.filter_signal("W10a", _cand(er=275), b, _ctx()) is not None         # entry 285 = 14:00, inside
    b[3, 285] = 50100.0
    assert S.filter_signal("W10a", _cand(er=276), b, _ctx()) is None             # entry 286, past 14:00
    p = _flat(50020.0)
    p[3, 149] = 49880.0
    w = S.filter_signal("W10b", _cand(sgn=-1), p, _ctx())
    assert w["er"] == 150 and w["strike"] == 50000.0                             # put: atm 49900 + 100


# ------------------------------------------------------------------------------------------------ stats and decision
def _t(und, day, book, se, net):
    return dict(und=und, day=day, book=book, sig_er=se, net=net)


def test_skip_compare():
    base = [_t("BANKNIFTY", D1, 15, 10, 100), _t("BANKNIFTY", D1, 15, 50, -40), _t("BANKNIFTY", D2, 5, 10, 30)]
    var = [_t("BANKNIFTY", D1, 15, 10, 90), _t("BANKNIFTY", D1, 15, 70, 5)]
    r = S.skip_compare(base, var)
    assert r == dict(skipped=2, skipped_net=-10, losers_avoided=1, losers_avoided_rs=40, winners_missed=1,
                     winners_missed_rs=30, extra=1, extra_net=5, kept=1, kept_diff=-10)


def test_skip_twins_and_p():
    nets = [10.0, -5.0, 3.0, 7.0]
    assert np.all(S.skip_twins(nets, 4, 20, np.random.default_rng(0)) == 15.0)
    assert np.all(S.skip_twins(nets, 6, 20, np.random.default_rng(0)) == 15.0)   # more trades than B0: none dropped
    tw = S.skip_twins(nets, 3, 200, np.random.default_rng(0))
    assert set(np.round(tw, 6)) <= {5.0, 20.0, 12.0, 8.0}
    assert S.p_of([1, 2, 3], 2.5) == round(2 / 4, 4)
    assert S.p_of([1, 2, 3], 9) == 0.25


def _res(wf, fin, q=0.05, sp=0.05):
    return dict(wf=dict(net=wf), final=dict(net=fin), q=q, skip_p=sp)


def test_decide_rule():
    b0 = _res(100, 10)
    assert S.decide(b0, _res(101, 11))["verdict"] == "PASS"
    assert S.decide(b0, _res(100, 11))["verdict"] == "FAIL"                      # must beat, not tie
    assert S.decide(b0, _res(101, 10))["verdict"] == "FAIL"
    assert S.decide(b0, _res(101, 11, q=0.10))["verdict"] == "FAIL"
    assert S.decide(b0, _res(101, 11, sp=0.10))["verdict"] == "FAIL"
    assert len(S.decide(b0, _res(50, 5, q=None, sp=None))["fails"]) == 4


def test_final_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "RESULTS", tmp_path)
    monkeypatch.setattr(S, "FINAL_FLAG", tmp_path / "FINAL_READ")
    with pytest.raises(SystemExit):
        S.run("final")                                                           # no flag: refused
    with pytest.raises(SystemExit):
        S.final()                                                                # no design yet: refused
    (tmp_path / "FINAL_READ").write_text("x")
    with pytest.raises(SystemExit):
        S.final()                                                                # already read: refused
    (tmp_path / "final.json").write_text("{}")
    with pytest.raises(SystemExit):
        S.run("final")                                                           # final.json exists: refused
