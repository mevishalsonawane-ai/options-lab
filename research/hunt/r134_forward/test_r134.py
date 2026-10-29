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


# ---- 29 Oct 2026: the app's paper arm "CISD + network" runs S3-V2-E3 from the same frozen rules ---------------------------------
APP_RULES = Path(__file__).resolve().parents[3] / "android/engine/src/main/kotlin/com/optionslab/engine/orb/CisdNet.kt"


def _kt(name: str) -> str:
    import re
    m = re.search(rf"\bval {name}\s*=\s*([^\n]+)", APP_RULES.read_text())
    assert m, name
    return m.group(1).strip()


def test_s3_v2_e3_is_still_scored_weekly():
    assert "S3-V2-E3" in F.RULES
    cfg = F.R.ALL_VARIANTS["S3-V2"]
    assert cfg["s"] == "cisd" and cfg["level"] == "open" and cfg["tf"] == 5 and cfg["target"] == "S"
    assert F.Q.EXITS["E3"] == dict(prem_stop=None, lock=True, idx="fixed", label=F.Q.EXITS["E3"]["label"])
    assert (F.Q.LOCK_AFTER, F.Q.LOCK_KEEP) == (1000.0, 0.60)


def test_the_app_arm_uses_the_frozen_numbers():
    """The Kotlin port's constants are R131's / R132's (a change on either side fails here)."""
    R, Q = F.R, F.Q
    assert float(_kt("STOP_PCT")) == R.STOP_PCT and float(_kt("CISD_ATR")) == R.CISD_ATR
    assert int(_kt("ATR_N")) == R.ATR_N and int(_kt("N_CONF")) == R.N_CONF and int(_kt("K_PIVOT")) == R.K_PIVOT
    assert int(_kt("SESSIONS_BACK")) == R.SESSIONS_BACK and int(_kt("TF")) == R.VARIANTS["S3-V2"]["tf"]
    assert float(_kt("LOCK_AFTER")) == Q.LOCK_AFTER and float(_kt("LOCK_KEEP")) == Q.LOCK_KEEP
    assert int(_kt("MAX_AGE")) == F.NN.MAX_AGE and int(_kt("VIEW_STEP")) == F.NN.STEP
    assert _kt("LAST_ENTRY") == "PassedArms.Session.row(15, 0)" and R.LAST_ENTRY == R.K.row(15, 0)
    assert _kt("EXIT_LAST") == "PassedArms.Session.row(15, 19)" and Q.EXIT_LAST == R.K.row(15, 19)
    assert int(_kt("FIRST_VIEW")) == F.NN.FIRST_ROW and _kt("LAST_VIEW") == "PassedArms.Session.row(14, 54)"
    assert F.NN.LAST_ROW == R.K.row(14, 54)
