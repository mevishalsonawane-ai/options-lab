"""Tests for R134's forward test (PREREG.md): the frozen rules, the decision rule, the summary and the filter."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import forward as F  # noqa: E402


def test_frozen_rules_and_start():
    assert F.PRIMARY == "S3-V2-E0" and F.PRIMARY in F.RULES and len(F.RULES) == 6
    assert F.FWD_START == date(2026, 10, 20) and F.DECIDE_N == 50
    for r in F.RULES:
        e, x = r.rsplit("-", 1)
        assert e in F.R.ALL_VARIANTS and (x == "E0" or x in F.Q.EXITS)


def _trades(nets, x2=None, day=date(2026, 11, 2)):
    n = len(nets)
    return pd.DataFrame(dict(rule=F.PRIMARY, und="NIFTY", day=[day] * n, entry=np.arange(n) + 10, net=nets,
                             netx2=x2 if x2 is not None else [v - 20 for v in nets]))


def test_no_decision_before_50_trades():
    s = F.summary(_trades([100.0] * 49))[F.PRIMARY]
    assert F.decision(s) == dict(status="collecting", trades=49, needed=50)


def test_decision_pass_and_fail():
    ok = F.summary(_trades([300.0, -100.0] * 25))[F.PRIMARY]
    assert F.decision(ok)["status"] == "pass"
    thin = F.summary(_trades([30.0, -10.0] * 25, x2=[-1.0] * 50))[F.PRIMARY]
    assert F.decision(thin) == dict(status="fail", checks=dict(net_x2=False, drawdown=True, month=True))
    # +Rs 30k in November, -Rs 15k in December: positive overall, drawdown -15k is fine, but one month < -13k
    T = pd.concat([_trades([1000.0] * 30, day=date(2026, 11, 2)), _trades([-750.0] * 20, day=date(2026, 12, 1))])
    T["entry"] = np.arange(len(T))
    s = F.summary(T)[F.PRIMARY]
    assert s["n"] == 50 and s["worst_month"] == -15000.0
    assert F.decision(s) == dict(status="fail", checks=dict(net_x2=True, drawdown=True, month=False))


def test_summary_counts_every_rule():
    s = F.summary(_trades([10.0, -5.0]))
    assert set(s) == set(F.RULES) and s[F.PRIMARY]["n"] == 2 and s["S3-V2-E3"]["n"] == 0


def test_filter_keeps_positive_ev_side_only():
    d0 = date(2026, 11, 2)
    T = pd.DataFrame(dict(rule=F.PRIMARY, und="NIFTY", day=[d0, d0], entry=[F.NN.FIRST_ROW + 1] * 2, side=[1, -1],
                          net=[1.0, 2.0], netx2=[1.0, 2.0]))
    P = pd.DataFrame(dict(und=["NIFTY"], day=[d0], row=[F.NN.FIRST_ROW], pdir30=[0.5],
                          **{f"ev_{j}": [5.0 if j % 2 == 0 else -5.0] for j in range(16)}))
    K_ = F.filtered(T, P)
    assert K_.side.tolist() == [1]
