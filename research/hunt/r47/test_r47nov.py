"""R47 amendment 4 tests (the Nov 2024+ view): spans, no event before 20 Nov 2024, M1 never trained on what it
predicts nor on anything before 20 Nov 2024, twins drawn inside their span, the rule list, and the warm-up switch
restored.

    python -m pytest research/hunt/r47 -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r47cfg as K  # noqa: E402
import r47data as D  # noqa: E402
import r47nov as NV  # noqa: E402
import r47sig as S  # noqa: E402


class FakeU:
    def __init__(self, days):
        self.days = np.array([d.toordinal() for d in days], dtype=np.int64)


def test_spans_are_ordered_and_start_on_20_nov_2024():
    assert NV.DESIGN == (date(2024, 11, 20), date(2026, 3, 31))
    assert NV.FINAL == (date(2026, 4, 1), date(2026, 10, 13))
    assert NV.N1[1] < NV.N2[0] and NV.N1[0] == NV.NOV_START and NV.N2[1] == NV.DESIGN[1]
    assert NV.WARMUP < NV.NOV_START
    m = NV.span_mask([date(2024, 11, 19).toordinal(), date(2024, 11, 20).toordinal(), date(2026, 3, 31).toordinal(),
                      date(2026, 4, 1).toordinal()], NV.DESIGN)
    assert list(m) == [False, True, True, False]


def test_no_event_before_20_nov_2024():
    u = FakeU([date(2024, 11, 18), date(2024, 11, 19), date(2024, 11, 20), date(2024, 11, 21)])
    ev = S.Events(np.array([0, 1, 2, 3]), np.array([10, 10, 10, 10]), np.array([1, -1, 1, -1], dtype=np.int8),
                  np.array([-1, -1, -1, -1]), (0, 374), "any")
    out = NV.only_from(u, ev)
    assert list(out.di) == [2, 3] and list(out.d) == [1, -1] and out.win == (0, 374)
    empty = NV.only_from(u, S.Events(np.zeros(0, np.int64), np.zeros(0, np.int64), np.zeros(0, np.int8),
                                     np.zeros(0, np.int64)))
    assert len(empty) == 0


def test_m1_trains_only_on_earlier_nov_sessions(monkeypatch):
    days = [date(2024, 10, 1), date(2024, 12, 2), date(2025, 8, 1), date(2026, 5, 4)]
    u = FakeU(days)
    di = np.arange(4)
    calls = []

    def fake_rows(u_, f_):
        return di, np.full(4, 60), np.zeros((4, 3)), np.ones(4)

    def fake_fit(di_, X, y, train, test):
        calls.append((train.copy(), test.copy()))
        return np.where(test, 0.9, np.nan)

    monkeypatch.setattr(NV.ML, "feature_rows", fake_rows)
    monkeypatch.setattr(NV.ML, "fit_predict", fake_fit)
    _, _, p = NV.m1_probs(u, None)
    assert len(calls) == 2
    for train, test in calls:
        assert not train[0]                                   # Oct 2024: never trained on
        assert not (train & test).any()
        assert u.days[train].max(initial=0) < u.days[test].min()
    assert np.isnan(p[0]) and np.isnan(p[1]) and p[2] == 0.9 and p[3] == 0.9   # N1 rows get no probability


def test_twins_are_drawn_inside_the_span(monkeypatch):
    days = [date(2026, 3, 30), date(2026, 3, 31), date(2026, 4, 1), date(2026, 4, 2)]
    u = FakeU(days)
    seen = {}

    def fake_price(u_, tev, instr, ex, lots=None, sequential=True):
        seen["di"] = tev.di.copy()
        seen["seq"] = sequential
        import pandas as pd
        return pd.DataFrame(dict(ev=np.arange(len(tev.di)), net=np.ones(len(tev.di))))

    monkeypatch.setattr(NV.R, "price", fake_price)
    monkeypatch.setattr(NV.R, "lots_of", lambda u_: np.ones(len(u_.days), dtype=np.int64))
    import pandas as pd
    trades = pd.DataFrame(dict(d=[1, -1]))
    ev = S.Events(np.array([2]), np.array([5]), np.array([1], dtype=np.int8), np.array([-1]), (5, 20), "any")
    rule = dict(fam="U1_orb", instr="atm", exit_spec=[0.0, 0.0, "sq", None])
    tw = NV.twin_net(u, rule, ev, trades, NV.FINAL, np.random.default_rng(0), n_twins=50)
    assert set(seen["di"]) <= {2, 3} and seen["seq"] is False
    assert len(tw) == 50 and (tw == 2).all()                  # 2 trades of net 1 per twin
    assert (NV.twin_net(u, rule, ev, trades.iloc[:0], NV.FINAL, np.random.default_rng(0), n_twins=5) == 0).all()


def test_rule_list_has_the_ten_candidates_o1_and_family_bests():
    rules = NV.fixed_rules()
    ids = [r["id"] for r in rules]
    assert [f"C{i}" for i in range(1, 11)] == ids[:10]
    o1 = [r for r in rules if r["id"].startswith("O1-NIFTY")]
    assert len(o1) == 1 and o1[0]["fam"] == "O1_prem_orb" and o1[0]["label"] == "O"
    assert "non-default" in o1[0]["signal_source"]
    best = [r for r in rules if r["group"] == "family best"]
    assert len(best) >= 15 and all(r["filt"] == "all" for r in best)
    assert all(r["und"] in K.UNDS for r in rules)


def test_load_nov_restores_the_warmup(monkeypatch, tmp_path):
    monkeypatch.setattr(K, "OUT", tmp_path)

    def boom(*a, **k):
        raise RuntimeError("no lake here")

    monkeypatch.setattr(D, "index_panel", boom)
    before = D.WARMUP_FROM
    with pytest.raises(RuntimeError):
        NV.load_nov("NIFTY", cache=False)
    assert D.WARMUP_FROM == before


def test_gates_need_every_condition():
    good = dict(design=dict(net=10.0), final=dict(net=100.0, trades=50, p=0.01, top5=20.0, best_month=30.0),
                q_final=0.05)
    assert all(NV.gates(good).values())
    bad = dict(good, q_final=0.2)
    assert not NV.gates(bad)["G4_q"]
    none = dict(design=dict(net=0.0), final=dict(trades=0, net=0.0), q_final=1.0)
    g = NV.gates(none)
    assert not any(g.values())


def test_tstat():
    assert NV.tstat(np.array([1.0])) == 0.0
    assert NV.tstat(np.array([1.0, 1.0])) == 0.0
    assert NV.tstat(np.array([1.0, 3.0])) == pytest.approx(2.0)
