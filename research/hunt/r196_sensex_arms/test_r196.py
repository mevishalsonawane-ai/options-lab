"""R196 tests (PREREG.md section 9, synthetic, no data): the look-ahead guard (a SENSEX liquidity signal's entry and a twin's
strike never read later minutes), windows and walk-forward quarters, the gate (each item failing alone), BH with
m = 4,811, twin plans and totals (same days / sides / counts, window bounds, expiry-day sessions refused, a twin that cannot
fill is Rs 0), the SENSEX slice (empty slice, a holiday gap), SENSEX's lot either side of 28 Nov 2024, BSE vs NSE
charges, and the expiry-day 15:05 square-off row.

    python -m pytest research/hunt/r196_sensex_arms -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r196cfg as K  # noqa: E402
import r196lib as L  # noqa: E402

sys.path.insert(0, str(K.HUNT / "r51"))


def _t(day, und="SENSEX", side="C", net=100.0, **kw):
    return dict(day=day, und=und, side=side, net=net, **kw)


def _stats(net=1000, trades=50, wo5=10, ok=True):
    return dict(net=net, trades=trades, net_wo_top5=wo5, account_stop_ok=ok)


def _win(**over):
    w = {"W1": _stats(), "W2": _stats(), "total": _stats()}
    for k, v in over.items():
        w[k] = v
    return w


# ================================================================================================ look-ahead guard
def _bars(n_days=12, tf=5, seed=3):
    """Synthetic SENSEX 5-minute bars (day, start minute, o, h, l, c): a random walk with real swings."""
    rng = np.random.default_rng(seed)
    out, px, d = [], 80000.0, date(2025, 3, 3)
    for _ in range(n_days):
        for m in range(9 * 60 + 15, 15 * 60 + 30, tf):
            o = px
            px = px + rng.normal(0, 40)
            h, lo = max(o, px) + abs(rng.normal(0, 15)), min(o, px) - abs(rng.normal(0, 15))
            out.append((d, m, o, h, lo, px))
        d += timedelta(days=1)
        while d.weekday() >= 5:
            d += timedelta(days=1)
    return out


ENTRY_KEYS = ("er", "side", "sgn", "close", "strike", "idx_stop", "target")


@pytest.mark.parametrize("seed", [2, 5, 8, 9, 10])
def test_sensex_liquidity_entry_never_reads_later_bars(seed):
    import fam_liq
    bars = _bars(seed=seed)
    today = bars[-1][0]
    full = fam_liq._day_signals(today, bars, 5, K.LIQ_SX_IDX_STOP, 100.0, 1.0)
    assert full, "the synthetic day should give at least one signal (else the guard tests nothing)"
    for s in full:
        end_min = fam_liq.OPEN_M + s["er"]                  # the signal bar's end
        cut = [b for b in bars if b[0] < today or b[1] + 5 <= end_min]
        part = fam_liq._day_signals(today, cut, 5, K.LIQ_SX_IDX_STOP, 100.0, 1.0)
        same = [p for p in part if p["er"] == s["er"] and p["side"] == s["side"]]
        assert same, f"signal at row {s['er']} needs later bars"
        for k in ENTRY_KEYS:
            a, b = same[0][k], s[k]
            assert (np.isnan(a) and np.isnan(b)) if isinstance(a, float) and np.isnan(a) else a == b, k


def test_twin_strike_reads_only_the_minute_before_entry():
    import engine as E
    rng = np.random.default_rng(1)
    atm = np.round((80000 + np.cumsum(rng.normal(0, 30, 375))) / 100) * 100
    idx = np.vstack([atm] * 4)
    D = SimpleNamespace(atm=atm.copy(), idx=idx.copy())
    for r in (6, 100, 300):
        k = E.strike_for(D, "SENSEX", "C", r - 1, K.TWIN_MONEY)
        D2 = SimpleNamespace(atm=atm.copy(), idx=idx.copy())
        D2.atm[r:] = 1e9                                     # the entry minute and later replaced
        D2.idx[:, r:] = 1e9
        assert E.strike_for(D2, "SENSEX", "C", r - 1, K.TWIN_MONEY) == k
        assert k == atm[r - 1] - 100                         # 1-ITM call, SENSEX step 100


def test_twin_strike_missing_atm_falls_back_and_none_without_price():
    import engine as E
    D = SimpleNamespace(atm=np.full(375, np.nan), idx=np.full((4, 375), np.nan))
    assert E.strike_for(D, "SENSEX", "P", 50, 1) is None     # no option minute and no index minute: no twin
    D.idx[3, 50] = 80049.0
    assert E.strike_for(D, "SENSEX", "P", 50, 1) == 80100.0  # index close rounded to 100, 1-ITM put


# ================================================================================================ windows / quarters
def test_quarters_tile_w1_and_w2_without_gaps():
    for w in ("W1", "W2"):
        qs = K.QUARTERS[w]
        assert qs[0][1] == K.WINDOWS[w][0] and qs[-1][2] == K.WINDOWS[w][1]
        for (_, _, hi), (_, lo, _) in zip(qs, qs[1:]):
            assert lo == hi + timedelta(days=1)
    assert len(K.QUARTERS["W1"]) == 6 and K.WF_MIN_POSITIVE == 4


def test_quarters_and_wf_count():
    tr = [_t(date(2024, 11, 5), net=-50), _t(date(2025, 2, 3), net=10), _t(date(2025, 5, 5), net=10),
          _t(date(2025, 8, 5), net=10), _t(date(2026, 3, 31), net=10)]
    qs = L.quarters(tr, "W1")
    assert [q["net"] for q in qs] == [-50, 10, 10, 10, 0, 10]
    assert [q["trades"] for q in qs] == [1, 1, 1, 1, 0, 1]
    assert L.wf_positive(qs) == 4                          # an empty quarter is not positive


def test_window_stats_empty_and_holiday_gap():
    s = L.window_stats([], "W2")
    assert s["trades"] == 0 and s["net"] == 0
    # a holiday (no session) between two trades: two trading days, nothing invented in between
    tr = [_t(date(2026, 10, 1), net=500), _t(date(2026, 10, 3), net=-200)]
    s = L.window_stats(tr, "W2")
    assert s["trades"] == 2 and s["net"] == 300 and s["max_dd"] == 200


def test_slice_filters():
    tr = [_t(date(2025, 1, 1)), _t(date(2025, 1, 1), und="NIFTY"), _t(date(2025, 1, 2), und="SENSEX")]
    assert len(L.only(tr, "SENSEX")) == 2 and len(L.without(tr, "SENSEX")) == 1
    assert L.only([], "SENSEX") == []


def test_exits_by_reason():
    tr = [_t(date(2025, 1, 1), why="stop"), _t(date(2025, 1, 1), why="stop"), _t(date(2025, 1, 2), why="lock")]
    assert L.exits_by_reason(tr) == {"stop": 2, "lock": 1}


# ================================================================================================ the gate
def test_gate_passes_when_all_hold():
    g = L.gate(_win(), 4, 0.01, 0.05)
    assert g["passed"] and not g["fails"]


@pytest.mark.parametrize("win,wf,p,q,needle", [
    (_win(W1=_stats(net=0)), 6, 0.01, 0.01, "W1: net"),
    (_win(W2=_stats(net=-1)), 6, 0.01, 0.01, "W2: net"),
    (_win(W1=_stats(trades=39)), 6, 0.01, 0.01, "W1: 39 trades"),
    (_win(W2=_stats(trades=39)), 6, 0.01, 0.01, "W2: 39 trades"),
    (_win(), 3, 0.01, 0.01, "walk-forward"),
    (_win(total=_stats(wo5=0)), 6, 0.01, 0.01, "without the 5 best"),
    (_win(), 6, 0.05, 0.01, "twin p"),
    (_win(), 6, 0.01, 0.11, "BH q"),
])
def test_gate_each_item_fails_alone(win, wf, p, q, needle):
    g = L.gate(win, wf, p, q)
    assert not g["passed"] and len(g["fails"]) == 1 and needle in g["fails"][0]


def test_gate_account_stop_is_not_a_gate():
    g = L.gate(_win(W1=_stats(ok=False)), 6, 0.01, 0.01)
    assert g["passed"]


def test_bh_uses_m_4811():
    assert K.M_TOTAL == 4811
    q = L.bh([1e-6, 0.5, 1.0])
    assert q[0] == pytest.approx(1e-6 * 4811)
    assert all(0 <= x <= 1 for x in q)


# ================================================================================================ twins
def test_twin_plan_counts_days_and_sides():
    tr = [_t(date(2025, 1, 1), side="C"), _t(date(2025, 1, 1), side="C"), _t(date(2025, 1, 1), side="P"),
          _t(date(2025, 1, 2), side="P")]
    assert L.twin_plan(tr) == [(date(2025, 1, 1), "C", 2), (date(2025, 1, 1), "P", 1), (date(2025, 1, 2), "P", 1)]
    assert L.twin_plan([]) == []


def test_twin_plan_refuses_an_expiry_day():
    tr = [_t(date(2025, 1, 7))]
    with pytest.raises(ValueError):
        L.twin_plan(tr, {date(2025, 1, 7)})
    assert L.twin_plan(tr, {date(2025, 1, 9)}) == [(date(2025, 1, 7), "C", 1)]


def test_twin_rows_inside_the_window():
    rng = np.random.default_rng(0)
    for arm in ("R72", "R51", "LIQ"):
        lo, hi = K.TWIN_WINDOW[arm]
        r = L.twin_rows(lo, hi, 500, 3, rng)
        assert r.shape == (500, 3) and r.min() >= lo and r.max() <= hi
    assert K.TWIN_WINDOW["LIQ"] == (5, 285) and K.TWIN_WINDOW["R51"] == (5, 355)
    with pytest.raises(ValueError):
        L.twin_rows(10, 9, 5, 1, rng)


def test_twin_totals_sum_and_no_fill_is_zero():
    plan = [(date(2025, 1, 1), "C", 2), (date(2025, 1, 2), "P", 1)]
    calls = []

    def net_at(d, side, r):
        calls.append((d, side, r))
        if r % 3 == 0:
            return None                                      # no contract / stale minute: never a made-up price
        if r % 3 == 1:
            return float("nan")
        return 10.0

    tot = L.twin_totals(plan, net_at, 5, 20, 200, np.random.default_rng(4))
    assert tot.shape == (200,)
    assert set(np.unique(tot)) <= {0.0, 10.0, 20.0, 30.0}
    assert len(calls) == len(set(calls))                    # each (day, side, row) priced once
    t2 = L.twin_totals(plan, net_at, 5, 20, 200, np.random.default_rng(4))
    assert np.array_equal(tot, t2)                           # seeded: reproducible


# ================================================================================================ SENSEX specifics
def test_sensex_lot_either_side_of_28_nov_2024():
    import r51cfg
    assert r51cfg.lot("SENSEX", date(2024, 11, 27)) == 10
    assert r51cfg.lot("SENSEX", date(2024, 11, 28)) == 20
    assert r51cfg.STEP["SENSEX"] == 100.0 and "SENSEX" in r51cfg.BSE


def test_bse_and_nse_charges_differ_on_the_same_trade():
    from obuy.costs import Costs
    c = Costs("dated")
    dn = np.array([(date(2025, 6, 2) - date(1970, 1, 1)).days])
    nse = c.charge(False, np.array([200.0]), np.array([20]), dn, False)[0]
    bse = c.charge(False, np.array([200.0]), np.array([20]), dn, True)[0]
    assert bse < nse                                         # BSE 0.0325% vs NSE 0.03503% + IPFT
    v = 200.0 * 20
    assert nse - bse == pytest.approx(v * (0.0003503 + 0.000005 - 0.000325) * 1.18, abs=0.011)


def test_expiry_day_square_off_is_15_05():
    import engine as E
    import r51cfg
    ex = SimpleNamespace(sq_row=-1)
    assert E.sq_row(SimpleNamespace(expiry=True), ex) == r51cfg.row(15, 5)
    assert E.sq_row(SimpleNamespace(expiry=False), ex) == r51cfg.row(15, 10)
    assert E.sq_row(SimpleNamespace(expiry=True), SimpleNamespace(sq_row=r51cfg.row(15, 15))) == r51cfg.row(15, 5)
