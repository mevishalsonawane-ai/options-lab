"""R108 tests (synthetic data, no network, no release files)."""
from __future__ import annotations

import os
import sys
from datetime import date, timedelta

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r108study as E  # noqa: E402

R = E.R
MON = date(2025, 3, 3)
DAYS = [MON + timedelta(days=i) for i in range(4)]       # Mon-Thu
ROW = {"monday": MON.isoformat(), "ce_strike": 22200.0, "pe_strike": 21800.0}


class Mk(R.Market):
    """BANKNIFTY-like four days; every listed contract priced flat at `px` unless overridden."""

    def __init__(self, exp=date(2025, 3, 27), px=None, spot=22000.0):
        self.und = "BANKNIFTY"
        self.days = list(DAYS)
        self.ix = {d: np.full((4, R.W), spot) for d in DAYS}
        self.opt, self.expiry = {}, {}
        px = px or {}
        for d in DAYS:
            book = {}
            for k, r in [(22200.0, "CE"), (21800.0, "PE"), (22600.0, "CE"), (21400.0, "PE"), (22000.0, "CE"),
                         (22000.0, "PE"), (21900.0, "CE"), (22100.0, "PE")]:
                a = np.full((5, R.W), 20.0 if k in (22600.0, 21400.0) else 100.0)
                a[4] = 0.0
                if (d, k, r) in px:
                    px[(d, k, r)](a)
                book[(exp, k, r)] = a
            self.opt[d] = book
            self.expiry[d] = exp


F0 = R.Fill(0.0)


def test_flat_week_loses_only_charges():
    t = E.strangle_plus(Mk(), ROW, F0)
    assert t["why"] == "thu_close" and t["gross"] == 0 and t["credit"] == 200
    assert t["net"] == -t["charges"] and t["exit_day"] == DAYS[3]


def test_stop_at_twice_the_credit_exits_next_minute():
    def jump(a):
        a[0:4, 100:] = 350.0                   # Tuesday 10:55 onwards the CE is 350: cost 450 >= 2 x 200
    t = E.strangle_plus(Mk(px={(DAYS[1], 22200.0, "CE"): jump}), ROW, F0, stop=True)
    assert t["why"] == "stop" and t["exit_day"] == DAYS[1]
    assert t["gross"] == (100 - 350) * R.lot("BANKNIFTY", MON)
    nostop = E.strangle_plus(Mk(px={(DAYS[1], 22200.0, "CE"): jump}), ROW, F0)
    assert nostop["why"] == "thu_close"         # without the stop it holds (the CE is back to 100 on Wed/Thu here)


def test_stop_ignores_minutes_before_entry():
    def early(a):
        a[0:4, 5] = 900.0                       # Monday 09:20, before the 09:31 entry
    t = E.strangle_plus(Mk(px={(MON, 22200.0, "CE"): early}), ROW, F0, stop=True)
    assert t["why"] == "thu_close"


def test_wings_make_an_iron_condor():
    t = E.strangle_plus(Mk(), ROW, F0, wings=True)
    assert t["credit"] == 200 - 40              # two shorts at 100, two wings at 20
    assert t["charges"] > E.strangle_plus(Mk(), ROW, F0)["charges"]   # 8 orders, not 4


def test_expiry_week_settles_or_is_skipped():
    mk = Mk(exp=date(2025, 3, 5), spot=22300.0)     # expiry Wednesday: inside the hold
    assert E.strangle_plus(mk, ROW, F0, skip_expiry_week=True) == "skipped"
    t = E.strangle_plus(mk, ROW, F0)
    assert t["why"] == "settle" and t["exit_day"] == date(2025, 3, 5)
    lots = R.lot("BANKNIFTY", MON)
    assert t["gross"] == (100 - 100) * lots + (100 - 0) * lots   # CE settles at 100 intrinsic, PE at 0


def test_no_monthly_price_before_nov_2024():
    mk = Mk()
    row = dict(ROW, monday="2024-03-04")
    mk.opt = {date(2024, 3, 4): mk.opt[MON]}
    mk.expiry = {date(2024, 3, 4): date(2025, 3, 27)}
    assert E.strangle_plus(mk, row, F0) is None


def test_mom60_itm_strike_and_no_stop():
    mk = Mk()
    ts = int(pd.Timestamp("2025-03-03 10:30", tz=R.IST).timestamp())
    mom = pd.DataFrame([{"entry_ts": ts, "exit_ts": ts + 3600, "call": 1, "strike": 22000.0},
                        {"entry_ts": ts, "exit_ts": ts + 3600, "call": 0, "strike": 22000.0}])
    t = E.run_mom(mk, mom, F0, itm=True, monthly=True)
    assert list(t["strike"]) == [21900.0, 22100.0]          # CE one strike lower, PE one strike higher
    assert set(t["why"]) == {"time"}
    t2 = E.run_mom(mk, mom, F0, stop=False)
    assert list(t2["strike"]) == [22000.0, 22000.0]


def test_variant_list_is_the_preregistered_ten():
    assert [v[0] for v in E.VARIANTS] == [f"E{i}" for i in range(1, 11)]
    assert [v[4] for v in E.VARIANTS].count("post-hoc") == 1
    assert not any(v[5] for v in E.VARIANTS if v[2] == "strangle")   # strangle variants are descriptive only


# ---- app-fit account rules (appfit.py) ----
def _weeks(nets):
    return pd.DataFrame({"day": [date(2025, 1, 6) + timedelta(days=7 * i) for i in range(len(nets))], "net": nets})


def test_appfit_guard_and_stop_rules():
    import appfit as A
    t = _weeks([5000, -12000, 3000, 3000])
    m = {d: 10_000 for d in t["day"]}
    # -12k from a 105k peak is 11.4% >= 10%: the guard refuses every later entry
    r = A.account(t, m, legs=2)
    assert r["weeks_taken"] == 2 and r["refused"] == {"guard: drawdown 10%": 2}
    # a condor's 4 legs exceed the default 3 open positions: nothing is placed
    assert A.account(t, m, legs=4)["weeks_taken"] == 0
    # margin above equity is refused
    assert A.account(t, {d: 200_000 for d in t["day"]}, legs=2)["refused"] == {"margin above equity": 4}


def test_appfit_month_stop():
    import appfit as A
    # +50k, then -14k in the same month (136k: 9.3% below the 150k peak, under the guard's 10%):
    # the -13k month rule refuses the next entry that month; February trades again
    t = pd.DataFrame({"day": [date(2025, 1, 6), date(2025, 1, 13), date(2025, 1, 20), date(2025, 2, 3)],
                      "net": [50_000, -14_000, 1_000, 1_000]})
    t.loc[0, "day"] = date(2024, 12, 30)        # the +50k week in December, so January's P&L is -14k
    r = A.account(t, {d: 1 for d in t["day"]}, legs=2)
    assert r["refused"] == {"Boss: -13k month stop": 1} and r["weeks_taken"] == 3
