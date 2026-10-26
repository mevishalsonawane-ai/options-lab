"""R110 tests: synthetic weeks only (no lake, no network)."""
from __future__ import annotations

import math
import subprocess
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))

import r110engine as X  # noqa: E402
import r109cfg as K  # noqa: E402
import r109engine as E  # noqa: E402

N = K.NROWS
MON = date(2025, 6, 2)
EXP = date(2025, 6, 26)


class Day:
    def __init__(self, idx, con, atm):
        self.idx, self.con, self.atm, self.expiry = idx, con, atm, False


def con(price, vol=1000.0):
    c = np.full((5, N), float(price))
    c[4] = vol
    return c


def make_week(level=50000.0, thu_holiday=False, strikes=None):
    """Mon-Thu, flat index and flat option prices: CE/PE at +-200 (150 / 140), +-500 (60 / 55), +-700 (30 / 28),
    +-1000 (10 / 9)."""
    strikes = strikes or {200: (150, 140), 500: (60, 55), 700: (30, 28), 1000: (10, 9)}
    days = {}
    for k in range(4):
        d = date(2025, 6, 2 + k)
        if thu_holiday and k == 3:
            continue
        cc = {}
        for off, (pc, pp) in strikes.items():
            cc[("C", level + off)] = con(pc)
            cc[("P", level - off)] = con(pp)
        days[d] = Day(np.full((4, N), level), cc, np.full(N, level))
    return days


# ------------------------------------------------------------------------------------------------ parity with R109
@pytest.mark.parametrize("thu_holiday", [False, True])
def test_b0_matches_r109_strangle(thu_holiday):
    days = make_week(thu_holiday=thu_holiday)
    a = E.strangle_week(days, MON, EXP, 30)
    b = X.week(days, MON, EXP, 30)
    assert b["net"] == pytest.approx(a["net"], abs=1e-9)
    assert b["exit_day"] == a["exit_day"] and b["credit"] == pytest.approx(a["credit"])


def test_b0_matches_r109_on_an_expiry_week():
    days = make_week()
    days[date(2025, 6, 4)].idx[3, :] = 50500.0
    a = E.strangle_week(days, MON, date(2025, 6, 4), 30)
    b = X.week(days, MON, date(2025, 6, 4), 30)
    assert a["settle"] and b["settle"] and b["net"] == pytest.approx(a["net"], abs=1e-9)


# ------------------------------------------------------------------------------------------------ wings and settlement
def test_wings_flat_prices_cost_only_and_credit():
    days = make_week()
    w = X.week(days, MON, EXP, 30, wings=700.0)
    assert w["credit"] == pytest.approx(150 + 140 - 30 - 28)
    assert w["net"] < 0 and w["margin"] == pytest.approx(500 * 30)


def test_wing_caps_an_expiry_week_loss_and_pays_exercise_stt():
    days = make_week()
    days[date(2025, 6, 4)].idx[3, :] = 52000.0    # expiry day, far above the CE wing at 50700
    naked = X.week(days, MON, date(2025, 6, 4), 30)
    capped = X.week(days, MON, date(2025, 6, 4), 30, wings=700.0)
    assert capped["net"] > naked["net"]
    # the short CE pays 1,800, the long CE gets 1,300: capped loss = (500 - credit) x lot + charges + exercise STT
    stt = 0.00125 * 1300 * 30
    assert capped["charges"] > stt and capped["net"] < -(500 - capped["credit"]) * 30


# ------------------------------------------------------------------------------------------------ stops
def test_s2_index_stop_triggers_and_fills_next_minute():
    days = make_week()
    days[date(2025, 6, 3)].idx[3, 100:] = 50000 + 200 + 290 + 1   # beyond CE strike + credit at Tuesday row 100
    w = X.week(days, MON, EXP, 30, stop="S2")
    assert w["stopped"] and w["stop_at"] == ("2025-06-03", 100)


def test_s1_premium_stop():
    days = make_week()
    tue = days[date(2025, 6, 3)]
    tue.con[("C", 50200.0)][:4, 50:] = 150 + 290 + 1          # loss = 291 >= 1 x credit 290
    w = X.week(days, MON, EXP, 30, stop="S1")
    assert w["stopped"] and w["stop_at"] == ("2025-06-03", 50)
    assert w["net"] < -290 * 30 * 0.9


def test_no_stop_when_quiet():
    days = make_week()
    for s in ("S1", "S2"):
        w = X.week(days, MON, EXP, 30, stop=s)
        assert not w["stopped"] and w["net"] == pytest.approx(X.week(days, MON, EXP, 30)["net"], abs=1e-9)


def test_stop_look_ahead_guard():
    days = make_week()
    days[date(2025, 6, 3)].idx[3, 100:] = 50600.0
    w1 = X.week(days, MON, EXP, 30, stop="S2")
    days[date(2025, 6, 3)].idx[3, 102:] = 40000.0             # what happens after the decision minute + 1
    days[date(2025, 6, 4)].idx[3, :] = 40000.0
    w2 = X.week(days, MON, EXP, 30, stop="S2")
    assert w1["stop_at"] == w2["stop_at"] == ("2025-06-03", 100)


def test_stale_mark_uses_black_scholes():
    tr = X.Tracker()
    tr.see(MON, 100, 800.0, 50000.0)
    fresh = X.mark(tr, MON, 104, 49000.0, 50000.0, EXP, True)
    stale = X.mark(tr, MON, 110, 49000.0, 50000.0, EXP, True)
    assert fresh == 800.0 and stale < 500                     # the index fell 1,000: the call is worth far less


# ------------------------------------------------------------------------------------------------ filters
def test_f1_keep_uses_only_earlier_weeks():
    r = [1.0] * 30 + [1.2, 0.8]
    assert X.f1_keep(r, 30) and not X.f1_keep(r, 31)
    assert not X.f1_keep(r, 20)                               # fewer than 26 earlier ratios: skip
    r2 = r[:31] + [5.0]
    assert X.f1_keep(r2, 30) == X.f1_keep(r, 30)              # a later ratio cannot change an earlier decision


def test_aftershock_matches_irathinker_definition():
    rng = np.random.default_rng(3)
    c = 50000 * np.exp(np.cumsum(rng.normal(0, 0.005, 24)))
    assert X.aftershock(c) == 0 or X.aftershock(c) == 1
    c2 = c.copy()
    c2[-1] = c2[-2] * 1.05                                    # a 5% day: >= 2 sigma of 0.5% days
    assert X.aftershock(c2) == 1
    assert X.aftershock(c[:10]) == -1


# ------------------------------------------------------------------------------------------------ statistics and lock
def test_boot_and_skip_twin():
    rng = np.random.default_rng(0)
    assert X.boot_p(np.full(50, 100.0), rng, 10_000) == pytest.approx(1 / 10_001)
    assert X.boot_p(np.full(50, -100.0), rng, 10_000) == pytest.approx(1.0)
    b = np.arange(-10, 10, dtype=float)
    best = b.sum() - b[:5].sum()                              # skipping the 5 worst weeks is the best possible skip
    assert X.skip_twin_p(b, 5, best, rng, 5_000) < 0.01
    assert X.skip_twin_p(b, 5, b.sum() - b[-5:].sum(), rng, 5_000) == pytest.approx(1.0)


def test_final_lock():
    if not (HERE / "results" / "FINAL_READ").exists():
        pytest.skip("the final has not been read yet")
    out = subprocess.run([sys.executable, str(HERE / "r110study.py"), "final"], capture_output=True, text=True)
    assert out.returncode != 0 and "already read" in (out.stderr + out.stdout)
