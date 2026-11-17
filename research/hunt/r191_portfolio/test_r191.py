"""R191 tests (PREREG.md section 10): timing, filters, caps, stats, twins, gate, BH, era lock, look-ahead guard."""
from __future__ import annotations

import random
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r191lib as L  # noqa: E402
import r191study as S  # noqa: E402

NT, IT = L.night_trade, L.intraday_trade


def d(y, m, dd):
    return date(y, m, dd)


# ================================================================================================ timing
def test_night_known_before_next_night_but_not_next_morning():
    n = NT("NIFTY", d(2023, 3, 1), d(2023, 3, 2), "B", -9000)
    next_morning = IT("L", "BANKNIFTY", d(2023, 3, 2), "B", 100)
    next_night = NT("NIFTY", d(2023, 3, 2), d(2023, 3, 3), "B", 100)
    assert not n["t_known"] < next_morning["t_dec"]
    assert n["t_known"] < next_night["t_dec"]


def test_intraday_not_known_same_day_even_to_night():
    i = IT("L", "BANKNIFTY", d(2023, 3, 1), "B", -9000)
    assert not i["t_known"] < NT("NIFTY", d(2023, 3, 1), d(2023, 3, 2), "B", 0)["t_dec"]
    assert i["t_known"] < IT("L", "FINNIFTY", d(2023, 3, 2), "B", 0)["t_dec"]


def test_month_halt_uses_only_visible_losses():
    r = L.Rules(month_halt=True)
    # a -9,000 intraday loss on the 1st: the 1st's other trades are still taken; the 2nd's are not
    c = [IT("L", "BANKNIFTY", d(2023, 3, 1), "B", -9000, er=1), IT("L", "FINNIFTY", d(2023, 3, 1), "B", 50, er=2),
         IT("L", "BANKNIFTY", d(2023, 3, 2), "B", 70)]
    taken = L.simulate(sorted(c, key=L.order_key), r)
    assert [t["net"] for t in taken] == [-9000, 50]


def test_month_halt_resets_next_month_and_weekend_gap_books_into_next_month():
    r = L.Rules(month_halt=True)
    fri = NT("NIFTY", d(2023, 3, 31), d(2023, 4, 3), "B", -9000)      # Friday 31 Mar -> Monday 3 Apr
    apr_night = NT("NIFTY", d(2023, 4, 3), d(2023, 4, 4), "B", 10)
    mar_intraday = IT("L", "BANKNIFTY", d(2023, 3, 31), "B", 5)
    c = sorted([fri, apr_night, mar_intraday], key=L.order_key)
    taken = L.simulate(c, r)
    # the Friday loss is booked in April (its close day), so April's night on the 3rd is halted
    assert apr_night not in taken and fri in taken and mar_intraday in taken
    assert L.stats([fri])["worst_month"] == -9000
    assert L.month_of(fri["close"]) == (2023, 4)


def test_month_halt_all_loss_month_and_empty_month():
    r = L.Rules(month_halt=True)
    c = [IT("L", "BANKNIFTY", d(2023, 5, k), "B", -3000) for k in (1, 2, 3, 4, 5)]
    c += [IT("L", "BANKNIFTY", d(2023, 7, 3), "B", 100)]              # June has no trade at all
    taken = L.simulate(sorted(c, key=L.order_key), r)
    # -3k, -6k, -9k visible after the 3rd -> the 4th and 5th are halted; July trades again
    assert [t["day"].day for t in taken] == [1, 2, 3, 3]
    s = L.stats(taken)
    assert s["months"] == 2 and s["months_up"] == 1 and s["months_up_pct"] == 50.0


def test_expiry_day_trade_is_an_ordinary_intraday_trade():
    # an expiry-day intraday trade (e.g. Thursday) books on the same day like any other
    t = IT("L", "FINNIFTY", d(2024, 10, 29), "B", 400)
    assert t["close"] == t["day"]
    assert L.simulate([t], L.Rules(month_halt=True, ec=True, brake=True)) == [t]


# ================================================================================================ static filters
def test_one_a_night_prefers_nifty_and_checks_premium():
    a = NT("BANKNIFTY", d(2023, 1, 2), d(2023, 1, 3), "B", 1, prem=20000)
    b = NT("NIFTY", d(2023, 1, 2), d(2023, 1, 3), "B", 2, prem=9000)
    c = NT("BANKNIFTY", d(2023, 1, 3), d(2023, 1, 4), "B", 3, prem=60000)   # does not fit Rs 56k
    e = NT("NIFTY", d(2023, 1, 4), d(2023, 1, 5), "B", 4, prem=60000)       # NIFTY too big: BANKNIFTY taken instead
    f = NT("BANKNIFTY", d(2023, 1, 4), d(2023, 1, 5), "B", 5, prem=20000)
    out = L.one_a_night([a, b, c, e, f])
    assert [t["net"] for t in out] == [2, 5]


def test_one_a_night_empty():
    assert L.one_a_night([]) == []


def test_vix_skip_only_night_and_liquidity():
    day = d(2022, 3, 7)
    tr = [NT("NIFTY", day, d(2022, 3, 8), "B", 1), IT("L", "BANKNIFTY", day, "B", 2), IT("R", "NIFTY", day, "B", 3)]
    assert [t["net"] for t in L.vix_skip(tr, {day})] == [3]
    assert len(L.vix_skip(tr, set())) == 3


# ================================================================================================ brake, equity curve
def test_drawdown_brake_halts_and_resumes_after_a_positive_paper_month():
    r = L.Rules(brake=True, brake_dd=10000)
    c = [IT("L", "BANKNIFTY", d(2022, 12, 30), "B", -11000),        # DD 11k visible from 2 Jan -> halted
         IT("L", "BANKNIFTY", d(2023, 1, 3), "B", 500),             # paper only (January paper +500)
         IT("L", "BANKNIFTY", d(2023, 2, 1), "B", 700)]             # Jan paper > 0 -> resume
    taken = L.simulate(c, r)
    assert [t["net"] for t in taken] == [-11000, 700]


def test_drawdown_brake_stays_halted_after_a_losing_paper_month():
    r = L.Rules(brake=True, brake_dd=10000)
    c = [IT("L", "BANKNIFTY", d(2023, 1, 2), "B", -11000), IT("L", "BANKNIFTY", d(2023, 1, 3), "B", -500),
         IT("L", "BANKNIFTY", d(2023, 2, 1), "B", 900), IT("L", "BANKNIFTY", d(2023, 3, 1), "B", 800)]
    taken = L.simulate(c, r)
    # January paper (-500, the -11000 booked before the halt also counts as January paper) <= 0 -> still halted in
    # February; February paper +900 > 0 -> trades again in March
    assert [t["net"] for t in taken] == [-11000, 800]


def test_equity_curve_filter_warm_up_and_switch_off():
    r = L.Rules(ec=True, ec_n=3)
    c = [IT("L", "BANKNIFTY", d(2023, 1, k), "B", -100) for k in (2, 3, 4)]
    c += [IT("L", "BANKNIFTY", d(2023, 1, 5), "B", 999), IT("R", "NIFTY", d(2023, 1, 5), "B", 7)]
    taken = L.simulate(sorted(c, key=L.order_key), r)
    # first 3 L trades taken in warm-up; then L's last 3 sum -300 -> skipped; R has no history -> taken
    assert [t["net"] for t in taken] == [-100, -100, -100, 7]


# ================================================================================================ look-ahead guard
def _random_streams(seed: int) -> dict:
    rng = random.Random(seed)
    day, out = d(2022, 1, 3), {"N": [], "L": [], "R": []}
    for _ in range(260):
        day += timedelta(days=1 if day.weekday() < 4 else 3)
        nd = day + timedelta(days=1 if day.weekday() < 4 else 3)
        if rng.random() < 0.6:
            out["N"].append(NT(rng.choice(["NIFTY", "BANKNIFTY"]), day, nd, "B", rng.gauss(0, 3000),
                               prem=rng.uniform(5000, 70000)))
        for k in range(rng.randint(0, 3)):
            out["L"].append(IT("L", rng.choice(["BANKNIFTY", "FINNIFTY"]), day, "B", rng.gauss(0, 1500), er=k))
        for k in range(rng.randint(0, 4)):
            out["R"].append(IT("R", "NIFTY", day, "B", rng.gauss(0, 1200), er=10 + k))
    return out


@pytest.mark.parametrize("vid", list(S.VARIANTS))
def test_look_ahead_guard(vid):
    comps, rules, _ = S.VARIANTS[vid]
    st = _random_streams(7)
    high = {t["day"] for t in st["L"][::9]}
    base = L.run_variant(st, comps, rules, high)
    cut = date(2022, 6, 15).toordinal() + 0.45
    rng = random.Random(3)
    noisy = {c: [dict(t, net=rng.gauss(0, 50000)) if t["t_dec"] >= cut else t for t in tr] for c, tr in st.items()}
    again = L.run_variant(noisy, comps, rules, high)
    key = lambda t: (t["comp"], t["und"], t["day"], t.get("er", 0))  # noqa: E731
    assert [key(t) for t in base if t["t_dec"] < cut] == [key(t) for t in again if t["t_dec"] < cut]


def test_a_trade_never_decides_on_its_own_result():
    r = L.Rules(month_halt=True, brake=True, ec=True, ec_n=1, brake_dd=1)
    t = IT("L", "BANKNIFTY", d(2023, 1, 2), "B", -10 ** 6)
    assert L.simulate([t], r) == [t]


# ================================================================================================ stats
def test_stats_empty_and_basic():
    assert L.stats([])["trades"] == 0
    tr = [IT("L", "X", d(2023, 1, 2), "B", 100), IT("L", "X", d(2023, 1, 3), "B", -300),
          IT("L", "X", d(2023, 2, 1), "B", 50)]
    s = L.stats(tr)
    assert s["trades"] == 3 and s["net"] == -150 and s["max_dd"] == 300 and s["worst_month"] == -200
    assert s["months_up"] == 1 and round(s["win"], 1) == 66.7 and s["avg_loss"] == -300
    assert s["net_wo_top5"] == 0


def test_max_drawdown():
    assert L.max_drawdown([]) == 0
    assert L.max_drawdown([100, -50, -100, 300, -10]) == 150


# ================================================================================================ twins, BH, gate
def test_twin_signs_are_per_month_blocks():
    tr = [IT("L", "X", d(2023, 1, k), "B", 100) for k in (2, 3, 4)] + [IT("L", "X", d(2023, 2, 1), "B", 100)]
    tw = L.twin_nets({"L": tr}, ("L",), L.Rules(), set(), np.random.default_rng(1), n=400)
    assert set(np.round(tw).astype(int)) <= {400, 200, -200, -400}
    assert {200, -400} <= set(np.round(tw).astype(int))        # January's three trades always move together


def test_twin_empty():
    tw = L.twin_nets({"L": []}, ("L",), L.Rules(), set(), np.random.default_rng(1), n=5)
    assert list(tw) == [0.0] * 5


def test_twin_p():
    assert L.twin_p(10, [])[0] == 1.0
    pe, pn = L.twin_p(100, [0.0] * 99 + [200.0])
    assert pe == pytest.approx(2 / 101) and pn < 0.5


def test_bh_total():
    q = L.bh_total([1e-6, 0.5], m_total=1000)
    assert q[0] == pytest.approx(1e-3) and q[1] == 1.0
    assert len(L.bh_total([])) == 0


def _good():
    return dict(trades=100, net=50000.0, net_wo_top5=20000.0, max_dd=20000.0, worst_month=-9000.0)


def test_gate_passes_and_fails():
    eras = {e: _good() for e in "ABC"}
    assert L.gate(eras, {e: 0.01 for e in "ABC"}, 0.05) == []
    bad = dict(eras, C=dict(_good(), worst_month=-13000.0))
    assert L.gate(bad, {e: 0.01 for e in "ABC"}, 0.05) == ["era C: worst month Rs -13,000 <= -Rs 13k"]
    a_dd = dict(eras, A=dict(_good(), max_dd=90000.0))              # era A: account check not gated
    assert L.gate(a_dd, {e: 0.01 for e in "ABC"}, 0.05) == []
    f = L.gate(dict(eras, A=dict(_good(), trades=39)), {"A": 0.2, "B": 0.01, "C": 0.01}, 0.2)
    assert "era A: 39 trades < 40" in f and "era A: twin p 0.200 >= 0.05" in f and "BH q 0.2 > 0.1" in f


def test_b_pick():
    eb = {"P1": dict(stats=dict(_good(), net=40000.0, max_dd=20000.0), p_emp=0.01),
          "P2": dict(stats=dict(_good(), net=30000.0, max_dd=10000.0), p_emp=0.01),
          "P3": dict(stats=dict(_good(), net=90000.0, max_dd=10000.0), p_emp=0.2)}
    assert L.b_pick(eb) == "P2"
    assert L.b_pick({}) is None


# ================================================================================================ era lock
def test_select_era_cut():
    st = {"L": [IT("L", "X", d(2023, 1, 2), "B", 1), IT("L", "X", d(2025, 1, 2), "C", 2)]}
    assert [t["net"] for t in S.select_era(st, "B", ("B",))["L"]] == [1]
    with pytest.raises(PermissionError):
        S.select_era(st, "C", ("B",))


def test_era_ca_lock():
    assert S.era_ca_allowed(True, False)
    assert not S.era_ca_allowed(False, False)
    assert not S.era_ca_allowed(True, True)


def test_variants_fixed():
    assert list(S.VARIANTS) == [f"P{i}" for i in range(1, 9)]
    assert L.M_TOTAL == 4770
