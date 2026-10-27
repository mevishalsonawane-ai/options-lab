"""R116 tests (synthetic, no lake): the expiry calendar logic, the archive check, the minute grid, the world swap and its
15:05 square-off, statistics, twins' p, BH, the gates, the 2% cap arithmetic, the frozen count of earlier tests, and the
look-ahead guards (the strike is read from the minute before the entry; a trade never sees a price after its exit or
before its fill; the next-expiry contracts never change the signals).

    python -m pytest research/hunt/r116_expiry_next_option/test_r116.py -q
"""
from __future__ import annotations

import os
import shutil
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

os.environ.setdefault("R51_CLEAN", "1")
HERE = Path(__file__).resolve().parent
for p in (str(HERE), str(HERE.parent / "r51"), str(HERE.parent)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r116cfg as K  # noqa: E402
import r116lib as L  # noqa: E402

D0 = date(2025, 6, 3)
N = L.NROWS


def wd(*ds):
    return sorted(date.fromisoformat(x) for x in ds)


# ------------------------------------------------------------------------------------------------ the calendar
def test_next_after_and_first_on_or_after():
    days = wd("2025-06-03", "2025-06-10", "2025-06-17")
    assert L.next_after(days, date(2025, 6, 3)) == date(2025, 6, 10)
    assert L.next_after(days, date(2025, 6, 17)) is None
    assert L.first_on_or_after(days, date(2025, 6, 3)) == date(2025, 6, 3)
    assert L.first_on_or_after(days, date(2025, 6, 4)) == date(2025, 6, 10)
    assert L.first_on_or_after([], D0) is None


def test_classify_month_cases():
    week = wd("2025-06-03", "2025-06-10", "2025-06-17", "2025-06-24", "2025-07-01")
    month = wd("2025-05-27", "2025-06-24", "2025-07-29")
    assert L.classify_month(date(2025, 6, 17), week, month) == ("exact", date(2025, 6, 24))     # week before monthly
    assert L.classify_month(date(2025, 6, 3), week, month) == ("standin", date(2025, 6, 24))   # 3 weeks out
    assert L.classify_month(date(2025, 6, 24), week, month) == ("same", date(2025, 6, 24))     # monthly expiry day
    assert L.classify_month(date(2025, 7, 1), week, month)[0] == "unknown"                     # no next weekly known
    assert L.classify_month(date(2025, 8, 5), week, month) == ("unknown", None)                # past the calendar


def test_classify_month_never_guesses_a_disagreement():
    week = wd("2025-06-03", "2025-06-17")              # a weekly missing from the calendar
    month = wd("2025-06-10")
    assert L.classify_month(date(2025, 6, 3), week, month) == ("unknown", date(2025, 6, 10))


def test_archive_ok():
    d = date(2024, 12, 24)
    assert L.archive_ok(d, date(2025, 1, 30), date(2025, 1, 30), weekly=False)
    assert not L.archive_ok(d, date(2025, 1, 29), date(2025, 1, 30), weekly=False)  # label != calendar: rejected
    assert not L.archive_ok(d, d, None, weekly=True)                                  # not after the day
    assert not L.archive_ok(d, None, None, weekly=True)
    assert L.archive_ok(d, d + timedelta(days=7), None, weekly=True)                  # past the calendar's end
    assert not L.archive_ok(d, d + timedelta(days=9), None, weekly=True)
    assert L.archive_ok(d, d + timedelta(days=35), None, weekly=False)


# ------------------------------------------------------------------------------------------------ the minute grid
def test_grid_gaps_duplicates_and_range():
    r = np.array([0, 2, 2, 400, -1, 374])
    o = np.array([1.0, 2.0, 99.0, 5.0, 6.0, 7.0])
    g = L.grid(r, [o, o * 10])
    assert g.shape == (2, N) and g.dtype == np.float32
    assert g[0, 0] == 1.0 and np.isnan(g[0, 1]) and g[0, 2] == 2.0 and g[1, 2] == 20.0   # first of a duplicate kept
    assert g[0, 374] == 7.0 and np.isfinite(g[0]).sum() == 3                              # out-of-range rows dropped


def test_grid_empty():
    g = L.grid(np.array([], np.int64), [np.array([])])
    assert np.isnan(g).all()


def test_minute_row():
    assert list(L.minute_row(np.array([9, 9, 15]), np.array([15, 20, 29]))) == [0, 5, 374]


# ------------------------------------------------------------------------------------------------ the world swap
class _Day:
    def __init__(self, d, idx, atm, con=None, expiry=False):
        self.d, self.idx, self.atm, self.con, self.expiry = d, idx, atm, con or {}, expiry


def _store():
    idx, atm = np.zeros((4, N)), np.zeros(N)
    days = {date(2025, 6, 2): _Day(date(2025, 6, 2), idx, atm, {"k": "normal"}),
            date(2025, 6, 3): _Day(date(2025, 6, 3), idx, atm, {"k": "expiring"}, expiry=True),
            date(2025, 6, 10): _Day(date(2025, 6, 10), idx, atm, {"k": "expiring2"}, expiry=True),
            date(2024, 11, 14): _Day(date(2024, 11, 14), idx, atm, {"k": "old"}, expiry=True)}
    return days, idx, atm


def test_swap_same_day_world():
    import r116study as S
    days, idx, atm = _store()
    normal = days[date(2025, 6, 2)]
    sw = S.swap_days(days, "S", None, _Day)
    assert set(sw) == {date(2025, 6, 3), date(2025, 6, 10)}                        # before START never traded
    X = days[date(2025, 6, 3)]
    assert X.expiry is False and X.xday and X.con == {"k": "expiring"} and X.idx is idx and X.atm is atm
    assert days[date(2025, 6, 2)] is normal and days[date(2024, 11, 14)].expiry  # other days untouched


def test_swap_next_world_only_days_with_data():
    import r116study as S
    days, idx, _ = _store()
    nxt = {date(2025, 6, 3): dict(con={"k": "next"}, src="archive", expiry=date(2025, 6, 10), dte=7)}
    sw = S.swap_days(days, "N", nxt, _Day)
    assert list(sw) == [date(2025, 6, 3)] and "archive" in sw[date(2025, 6, 3)]
    assert days[date(2025, 6, 3)].con == {"k": "next"} and days[date(2025, 6, 3)].idx is idx
    assert days[date(2025, 6, 10)].expiry and not getattr(days[date(2025, 6, 10)], "xday", False)  # still skipped


def test_patched_square_off():
    import r116study as S

    class Ex:
        def __init__(self, sq):
            self.sq_row = sq
    orig = lambda D, ex: 999                                  # noqa: E731
    sq = S.patched_sq(orig, 350)
    x, plain = _Day(D0, None, None), _Day(D0, None, None)
    x.xday = True
    assert sq(x, Ex(-1)) == 350 and sq(x, Ex(360)) == 350 and sq(x, Ex(300)) == 300
    assert sq(plain, Ex(-1)) == 999


# ------------------------------------------------------------------------------------------------ statistics
def _t(day, net, er=20, und="NIFTY"):
    return dict(day=day, net=net, er=er, und=und)


def test_stats_empty_and_basic():
    assert L.stats([])["trades"] == 0 and L.stats([])["net"] == 0
    tr = [_t(date(2025, 1, 2), 100.0), _t(date(2025, 1, 2), -300.0), _t(date(2025, 1, 3), 500.0),
          _t(date(2025, 2, 4), -14_000.0)]
    s = L.stats(tr)
    assert s["trades"] == 4 and s["days"] == 3 and s["net"] == -13_700 and s["win"] == 0.5
    assert s["worst_day"] == -14_000 and s["max_dd"] == 14_000 and s["bad_months"] == 1


def test_max_dd_from_zero():
    assert L.stats([_t(date(2025, 1, 2), -50.0)])["max_dd"] == 50


def test_open_slice_boundary():
    tr = [_t(D0, 1.0, er=14), _t(D0, 1.0, er=15), _t(D0, 1.0, er=5)]
    assert [t["er"] for t in L.open_slice(tr)] == [14, 5]


def test_twin_p():
    r = L.twin_p(10.0, [0.0, 5.0, 10.0, 20.0])
    assert r["hits"] == 2 and r["p"] == pytest.approx(3 / 5)
    r = L.twin_p(100.0, np.random.default_rng(1).normal(0, 10, 500))
    assert r["hits"] == 0 and r["p"] < 1e-6                     # the normal tail when no twin reaches it
    assert L.twin_p(0.0, [])["p"] == 1.0


def test_bh():
    q = L.bh([0.01, 0.04, 0.03], 3)
    assert q == pytest.approx([0.03, 0.04, 0.04])
    assert L.bh([1e-9], 1_000_000)[0] == pytest.approx(1e-3)


def _per(d=1000, w=500, f=800, nf=50):
    s = lambda n, net: dict(trades=n, net=net)              # noqa: E731
    return {"design": s(100, d), "wf": s(60, w), "final": s(nf, f)}


def test_gates_pass_and_each_failure():
    assert L.gates(_per(), 0.05)["passed"]
    g = L.gates(_per(d=-1), 0.05)
    assert not g["passed"] and any("design" in x for x in g["fails"])
    assert not L.gates(_per(w=0), 0.05)["passed"]                         # Rs 0 is not above skipping
    g = L.gates(_per(nf=39), 0.05)
    assert not g["passed"] and not g["decidable"]
    assert not L.gates(_per(), 0.2)["passed"]
    g = L.gates({"design": dict(trades=10, net=5), "wf": dict(trades=5, net=5), "final": dict(trades=0, net=0)}, 0.0)
    assert not g["passed"] and any("no trades" in x for x in g["fails"])


def test_risk_cap_arithmetic():
    # NIFTY next weekly at Rs 220 x 65: Rs 14,300 a lot; 15% stop + Rs 60 = Rs 2,205; 2% needs Rs 1,10,250
    assert L.risk_per_lot(220.0, 65) == pytest.approx(2205.0)
    assert L.account_needed(220.0, 65) == pytest.approx(110_250.0)


# ------------------------------------------------------------------------------------------------ config and counts
def test_periods_and_family():
    assert K.START < K.WF_START < K.DESIGN_END < K.FINAL_START <= K.FINAL_END
    assert K.in_period(K.DESIGN_END, "design") and not K.in_period(K.FINAL_START, "design")
    assert K.in_period(K.WF_START, "wf") and not K.in_period(K.START, "wf")
    with pytest.raises(ValueError):
        K.in_period(D0, "x")
    assert len(K.TESTS) == 8 and K.M_ALL == K.N_PRIOR_ALL + 8
    assert {w for _, w in K.TESTS.values()} == {"N", "S"}                 # M is never gated


def test_count_prior_matches_frozen(tmp_path):
    pytest.importorskip("yaml")
    sys.path.insert(0, str(HERE.parent / "r115_price_action_arms"))
    import r115lib
    for f in K.ENTRIES.glob("*.yaml"):
        if not f.name.startswith("r116-"):
            shutil.copy(f, tmp_path / f.name)
    assert r115lib.count_prior(tmp_path, "2026-10-27") == K.N_PRIOR_ALL


# ------------------------------------------------------------------------------------------------ look-ahead guards
def _engine():
    pytest.importorskip("numba")
    import engine as E
    import r51data
    return E, r51data


def _contract(px=100.0):
    c = np.full((5, N), px, dtype=np.float32)
    c[4] = 1000.0
    return c


def test_strike_reads_only_the_minute_before_the_entry():
    E, r51data = _engine()
    atm = np.full(N, 24000.0)
    D = r51data.Day(D0, np.full((4, N), 24000.0), atm.copy())
    k = E.strike_for(D, "NIFTY", "C", 19, 1)
    D.atm[20:] = 25000.0                                       # the ATM moves from the entry minute on
    assert E.strike_for(D, "NIFTY", "C", 19, 1) == k == 23950.0
    assert E.strike_for(D, "NIFTY", "P", 19, 1) == 24050.0


def test_trade_never_sees_prices_after_its_exit_or_before_its_fill():
    E, r51data = _engine()
    c = _contract()
    c[:4, 40:] = 130.0                                          # +30%: a 25% target fills
    D = r51data.Day(D0, np.full((4, N), 24000.0), np.full(N, 24000.0), {("C", 23950.0): c})
    ex = E.Exits(stop=0.15, target=0.25)
    a = E.run_day("NIFTY", D, ["C"], [23950.0], [20], ex)[0]
    c2 = c.copy()
    c2[:4, a["xr"] + 2:] = 1.0                                  # anything after the exit
    c2[:4, :20] = 500.0                                         # anything before the fill
    D2 = r51data.Day(D0, D.idx, D.atm, {("C", 23950.0): c2})
    b = E.run_day("NIFTY", D2, ["C"], [23950.0], [20], ex)[0]
    assert a["net"] == pytest.approx(b["net"]) and a["xr"] == b["xr"]


def test_next_expiry_contracts_never_change_the_signal_inputs():
    """The world swap keeps the very same index minutes and ATM label objects: every arm's signal reads only those."""
    import r116study as S
    days, idx, atm = _store()
    before = {d: (D.idx, D.atm) for d, D in days.items()}
    S.swap_days(days, "N", {date(2025, 6, 3): dict(con={"k": np.ones(3)}, src="x", expiry=date(2025, 6, 10), dte=7)},
                _Day)
    assert all(days[d].idx is before[d][0] and days[d].atm is before[d][1] for d in days)
