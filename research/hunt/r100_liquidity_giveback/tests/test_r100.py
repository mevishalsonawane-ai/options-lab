"""R100 tests (22 Oct 2026): R100's engine equals R64's with no keep rows; each keep rule (breakeven, keep a share, the
time steps, rupees) locks at the right level and only from the NEXT minute; locks never fall; a gap through a lock fills
at the open; a lock above the price sells at the next minute; the time stop and the index exit still act; the index
breakeven row (arming, firing, NaN minutes, no fill); the look-ahead guard (bars after the decision minute never change
it); the decision rule and the accounting. Synthetic data only.

    python -m pytest research/hunt/r100_liquidity_giveback -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parents[1]
for d in ("r51", "r64", "r65", "r70", "r76"):
    sys.path.insert(0, str(HERE.parent / d))
sys.path.insert(0, str(HERE))

import r51cfg as K  # noqa: E402
import r64engine as G  # noqa: E402
import r100engine as X  # noqa: E402
import r100study as S  # noqa: E402

N = K.NROWS
NOSTEP = np.zeros((0, 2))
NAN = float("nan")
R = G.E.REASONS


def _sim(C, rows=X.NOKEEP, fx=-1, stop=0.15, tmax=-1, need=-1.0, e=10, be=0.0, qty=30.0, steps=NOSTEP):
    return X.simulate(C, e, K.SQ_ROW, fx, stop, -1.0, -1.0, 0.0, 0.0, steps, tmax, need, -1.0, K.TICK, qty, be, rows)


def _g(C, fx=-1, steps=NOSTEP, stop=0.15, tmax=20, need=0.05, e=10):
    return G.simulate(C, e, K.SQ_ROW, fx, stop, -1.0, -1.0, 0.0, 0.0, steps, tmax, need, -1.0, K.TICK, 30.0, 0.0)


def _walk(seed):
    r = np.random.default_rng(seed)
    c = 100.0 * np.exp(np.cumsum(r.normal(0, 0.02, N)))
    o = np.r_[100.0, c[:-1]]
    h, lo = np.maximum(o, c) * 1.01, np.minimum(o, c) * 0.99
    C = np.vstack([o, h, lo, c, np.full(N, 50.0)])
    C[:, r.choice(N, 15, replace=False)] = np.nan
    return C


def _flat(p=100.0):
    C = np.full((5, N), p)
    C[4] = 1000.0
    return C


def _rows(*r):
    return np.array(r, dtype=np.float64).reshape(-1, 4)


@pytest.mark.parametrize("seed", range(25))
def test_engine_equals_r64_without_keep_rows(seed):
    C = _walk(seed)
    for kw in (dict(), dict(fx=40), dict(steps=np.array([[0.25, 0.05]])), dict(tmax=-1, need=-1.0), dict(stop=-1.0)):
        k = dict(tmax=20, need=0.05)
        k.update(kw)
        assert _sim(C, **k) == pytest.approx(_g(C, **k), nan_ok=True)


def test_breakeven_once_20pct_from_the_next_minute_only():
    C = _flat()
    C[:4, 12] = 121.0                       # best +21% in minute 12 ...
    C[2, 12] = 99.0                         # ... and the same minute dips under breakeven: not sold (the lock is set at its end)
    C[:4, 13:] = 110.0
    C[2, 16] = 100.5                        # minute 16 comes back to 100.5, under breakeven 101
    out = _sim(C, _rows((0, 0.20, NAN, 0)), be=101.0)
    assert R[out[6]] == "lock" and out[5] == 16 and out[1] == pytest.approx(101.0)
    assert R[_sim(C, X.NOKEEP)[6]] != "lock"


def test_not_armed_below_the_threshold_and_exactly_at_it():
    C = _flat()
    C[:4, 12] = 119.9
    C[:4, 13:] = 100.0
    assert R[_sim(C, _rows((0, 0.20, NAN, 0)), be=101.0)[6]] != "lock"
    C[:4, 12] = 120.0                       # exactly +20% arms
    out = _sim(C, _rows((0, 0.20, NAN, 0)), be=101.0)
    assert R[out[6]] == "lock" and out[5] == 13


def test_keep_share_and_gap_fill_at_the_open():
    C = _flat()
    C[:4, 12] = 140.0                       # best +40%: keep 50% -> 120
    C[:4, 13] = 130.0
    C[0, 14], C[1, 14], C[2, 14], C[3, 14] = 115.0, 116.0, 110.0, 112.0     # opens under the lock: fills at the open
    out = _sim(C, _rows((0, 0.20, 0.50, 0)), be=101.0)
    assert R[out[6]] == "lock" and out[5] == 14 and out[1] == pytest.approx(115.0)


def test_lock_only_rises_with_the_best_price():
    C = _flat()
    C[:4, 12] = 140.0                       # lock 120
    C[:4, 13] = 125.0                       # lower high: lock stays 120
    C[:4, 14:] = 121.0
    C[2, 15] = 119.0                        # opens at 121, trades down through 120
    out = _sim(C, _rows((0, 0.20, 0.50, 0)), be=101.0)
    assert out[5] == 15 and out[1] == pytest.approx(120.0)


def test_keep_is_floored_at_breakeven():
    C = _flat()
    C[:4, 12] = 120.0                       # keep 0.05 of +20 = 101 < breakeven 103
    C[:4, 13:] = 110.0
    C[2, 15] = 102.0
    out = _sim(C, _rows((0, 0.20, 0.05, 0)), be=103.0)
    assert out[5] == 15 and out[1] == pytest.approx(103.0)


def test_step_lock_takes_the_highest_rung():
    rows = _rows((0, 0.15, NAN, 0), (0, 0.30, 0.40, 0), (0, 0.50, 0.60, 0))
    assert X.keep_level(rows, 100.0, 114.0, 30.0, 101.0, 0.0) == -1.0
    assert X.keep_level(rows, 100.0, 116.0, 30.0, 101.0, 0.0) == pytest.approx(101.0)
    assert X.keep_level(rows, 100.0, 135.0, 30.0, 101.0, 0.0) == pytest.approx(114.0)
    assert X.keep_level(rows, 100.0, 160.0, 30.0, 101.0, 0.0) == pytest.approx(136.0)


def test_time_tightening_rows_count_minutes_from_the_fill():
    rows = _rows((0, 0.20, 0.25, 0), (30, 0.20, 0.50, 0), (60, 0.20, 0.75, 0))
    assert X.keep_level(rows, 100.0, 140.0, 30.0, 0.0, 29.0) == pytest.approx(110.0)
    assert X.keep_level(rows, 100.0, 140.0, 30.0, 0.0, 30.0) == pytest.approx(120.0)
    assert X.keep_level(rows, 100.0, 140.0, 30.0, 0.0, 60.0) == pytest.approx(130.0)
    assert X.keep_level(rows, 100.0, 119.0, 30.0, 0.0, 90.0) == -1.0


def test_rupee_rows_use_the_quantity():
    rows = _rows((0, 3000.0, 0.50, 1))
    assert X.keep_level(rows, 100.0, 199.0, 30.0, 0.0, 0.0) == -1.0          # +99 x 30 = 2,970
    assert X.keep_level(rows, 100.0, 200.0, 30.0, 0.0, 0.0) == pytest.approx(150.0)
    assert X.keep_level(rows, 100.0, 125.0, 120.0, 0.0, 0.0) == pytest.approx(112.5)  # MIDCPNIFTY lot 120: +25 = Rs 3,000


def test_the_time_stop_and_forced_exit_still_act():
    C = _flat()
    C[:4, 12:] = 103.0                      # never +20%: the 20-minute time stop (not +5%) still sells
    out = _sim(C, _rows((0, 0.20, NAN, 0)), tmax=20, need=0.05, be=101.0)
    assert R[out[6]] == "time"
    C2 = _flat()
    C2[:4, 12:] = 125.0
    out2 = _sim(C2, _rows((0, 0.20, NAN, 0)), fx=20, be=101.0)
    assert R[out2[6]] == "rule_exit" and out2[5] == 20


def test_nan_minutes_and_no_fill():
    C = _flat()
    C[:, 13] = np.nan
    C[:4, 12] = 125.0
    C[:4, 14:] = 110.0
    C[2, 15] = 100.0
    out = _sim(C, _rows((0, 0.20, NAN, 0)), be=101.0)
    assert out[5] == 15 and R[out[6]] == "lock"
    D = _flat()
    D[:, 10:13] = np.nan
    assert _sim(D, _rows((0, 0.20, NAN, 0)))[6] == G.E.R_NOFILL


@pytest.mark.parametrize("seed", range(10))
def test_lookahead_bars_after_the_exit_never_matter(seed):
    C = _walk(seed)
    rows = _rows((0, 0.15, NAN, 0), (0, 0.30, 0.40, 0))
    a = _sim(C, rows, be=101.0)
    xr = a[5]
    if xr < 0 or xr >= N - 2:
        return
    C2 = C.copy()
    C2[:4, xr + 1:] = C2[:4, xr + 1:] * 3.0      # every later bar changed
    assert _sim(C2, rows, be=101.0) == pytest.approx(a, nan_ok=True)


def _idx(n=N, base=100.0):
    a = np.full((4, n), base)
    return a


def test_index_breakeven_row_call_put_and_edges():
    a = _idx()
    a[1, 15] = 161.0                        # call: +61 past I0 100 arms at 15 (60 needed)
    a[2, 15] = 100.0                        # the arming minute itself touching I0 does not fire
    a[2, 16:] = 120.0
    a[2, 22] = 99.0                         # back at I0 in minute 22 -> sold at 23's open
    assert X.index_be_row(a, 10, +1, 100.0, 60.0) == 23
    assert X.index_be_row(a, 10, +1, 100.0, 62.0) == -1        # never armed
    b = _idx()
    b[2, 12] = 49.0                         # put: low 49 arms (units 50)
    b[1, 13] = 100.0                        # back at I0 -> 14
    assert X.index_be_row(b, 10, -1, 100.0, 50.0) == 14
    assert X.index_be_row(b, -1, -1, 100.0, 50.0) == -1        # no fill
    assert X.index_be_row(b, 10, -1, float("nan"), 50.0) == -1
    c = _idx()
    c[1, 5] = 200.0                         # before the fill row: never arms
    assert X.index_be_row(c, 10, +1, 100.0, 60.0) == -1
    d = a.copy()
    d[2, 22] = np.nan                       # a NaN minute never fires
    assert X.index_be_row(d, 10, +1, 100.0, 60.0) == -1
    assert X.first_forced(-1, 23) == 23 and X.first_forced(30, 23) == 23 and X.first_forced(-1, -1) == -1


def test_index_breakeven_lookahead():
    a = _idx()
    a[1, 15] = 161.0
    a[2, 16:] = 120.0
    a[2, 22] = 99.0
    r = X.index_be_row(a, 10, +1, 100.0, 60.0)
    b = a.copy()
    b[:, r:] = 500.0                        # bars from the selling row on never change the decision
    assert X.index_be_row(b, 10, +1, 100.0, 60.0) == r


def _t(und, day, net, peak=0.0, er=10):
    return dict(und=und, day=day, net=net, held_peak=peak, er=er, side="P", strike=1.0, why="x")


def test_accounting_giveback_top_removed_and_decision():
    d = date(2025, 1, 2)
    b0 = [_t("BANKNIFTY", d, -500.0, 0.25), _t("BANKNIFTY", d, 3000.0, 0.6), _t("MIDCPNIFTY", d, -100.0, 0.05)]
    v = [_t("BANKNIFTY", d, 200.0, 0.25), _t("BANKNIFTY", d, 1000.0, 0.6), _t("MIDCPNIFTY", d, -100.0, 0.05)]
    a = S.account(b0, v)
    assert a["changed"] == 2 and a["saved"] == 700 and a["given_up"] == 2000 and a["net_effect"] == -1300
    g = S.giveback(b0, v)
    assert g == dict(n=1, b0=-500, variant=200, kept=700)
    assert S.top_removed(b0, v, 1) == pytest.approx(-2000.0)
    assert S.by_index(b0) == {"BANKNIFTY": dict(trades=2, net=2500), "MIDCPNIFTY": dict(trades=1, net=-100)}
    row = lambda x: dict(net=x)  # noqa: E731
    base = dict(design=row(100), wf=row(50), final=row(10))
    good = dict(design=row(150), wf=row(60), final=row(20), q_design=0.05, q_final=0.08, top_removed_design=5,
                changed=dict(design=40, final=10))
    assert S.decide(good, base)["passed"]
    assert not S.decide(dict(good, q_design=0.2), base)["passed"]
    assert not S.decide(dict(good, top_removed_design=-1), base)["passed"]
    assert not S.decide(dict(good, changed=dict(design=29, final=1)), base)["passed"]
    worse = dict(good, final=row(5))
    assert not S.decide(worse, base)["passed"] and not S.decide(worse, base)["harmless"]


def test_wf_select_uses_only_earlier_days():
    q = ((date(2025, 4, 1), date(2025, 6, 30)),)
    T = {v: [] for v in ("B0",) + S.TESTS}
    T["B0"] = [_t("BANKNIFTY", date(2025, 1, 2), 100.0), _t("BANKNIFTY", date(2025, 5, 2), 50.0)]
    T["G1"] = [_t("BANKNIFTY", date(2025, 1, 2), 200.0), _t("BANKNIFTY", date(2025, 5, 2), -999.0)]
    out = S.wf_select(T, q, date(2024, 11, 20))
    assert out["picks"][0]["pick"] == "G1" and out["net"] == -999 and out["b0"] == 50


def test_final_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "RESULTS", tmp_path)
    monkeypatch.setattr(S, "FINAL_FLAG", tmp_path / "FINAL_READ")
    with pytest.raises(SystemExit):
        S.final()                           # no design results yet
    with pytest.raises(SystemExit):
        S.run("final")                      # no flag: refused
    (tmp_path / "FINAL_READ").write_text("x")
    with pytest.raises(SystemExit):
        S.final()                           # a second read refused


def test_variants_are_the_preregistered_ones():
    assert S.TESTS == ("G1", "G2", "G3", "G4", "G5", "G6", "G7")
    assert S.rows_of("G1").tolist()[0][:2] == [0, 0.20] and np.isnan(S.rows_of("G1")[0, 2])
    assert S.rows_of("G7").tolist() == [[0, 3000.0, 0.5, 1]]
    assert S.VARIANTS["G5"] == ([], 2.0)
    assert S.rows_of("G6")[:, 0].tolist() == [0, 30, 60]


# ---- amendments A1 / A2 (22 Oct, Boss: keep half once +10%; a trailing stop-loss) ------------------------------------

def test_trailing_stop_kind_follows_the_best_price_and_is_not_floored():
    rows = _rows((0, 0.0, 0.15, 2))
    assert X.keep_level(rows, 100.0, 100.0, 30.0, 101.0, 0.0) == pytest.approx(85.0)      # starts at the own -15%
    assert X.keep_level(rows, 100.0, 110.0, 30.0, 101.0, 0.0) == pytest.approx(93.5)      # rises with the best price
    # Not floored at breakeven: a stop-loss under the price paid stays there.
    assert X.keep_level(rows, 100.0, 105.0, 30.0, 101.0, 0.0) == pytest.approx(89.25)


def test_trailing_stop_sells_on_a_pullback_from_the_next_minute_only():
    C = _flat()
    C[:4, 12] = 110.0                       # best 110 -> trail 93.5 from minute 13
    C[2, 12] = 90.0                         # the same minute's dip is not judged against its own high
    C[:4, 13:] = 100.0
    C[2, 15] = 93.0
    out = _sim(C, _rows((0, 0.0, 0.15, 2)), be=101.0)
    assert out[5] == 15 and out[1] == pytest.approx(93.5)
    plain = _sim(C, X.NOKEEP)
    assert R[plain[6]] != "lock"


def test_keep_half_once_10pct_and_the_trail_together_take_the_higher():
    rows = S.rows_of("G9")
    assert X.keep_level(rows, 100.0, 108.0, 30.0, 100.5, 0.0) == pytest.approx(91.8)      # under +10%: the trail only
    assert X.keep_level(rows, 100.0, 112.0, 30.0, 100.5, 0.0) == pytest.approx(106.0)     # +12%: keep half = 106 > 95.2
    assert X.keep_level(S.rows_of("G8"), 100.0, 109.9, 30.0, 100.5, 0.0) == -1.0          # G8 alone waits for +10%
    assert X.keep_level(S.rows_of("G8"), 100.0, 130.0, 30.0, 100.5, 0.0) == pytest.approx(115.0)  # Boss's example


def test_amendments_are_the_preregistered_ones_and_leave_g1_g7_alone():
    assert tuple(S.AMEND) == ("G8", "G9", "G10")
    assert S.rows_of("G8").tolist() == [[0, 0.10, 0.50, 0]]
    assert S.rows_of("G10").tolist() == [[0, 0.0, 0.15, 2]]
    assert S.TESTS == ("G1", "G2", "G3", "G4", "G5", "G6", "G7")


def test_the_amendments_final_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "RESULTS", tmp_path)
    monkeypatch.setattr(S, "AMEND_FLAG", tmp_path / "A1_FINAL_READ")
    with pytest.raises(SystemExit):
        S.amend("final")                    # no design results yet
    (tmp_path / "amend_design.json").write_text("{}")
    (tmp_path / "A1_FINAL_READ").write_text("x")
    with pytest.raises(SystemExit):
        S.amend("final")                    # a second read refused


# ---- amendment A3 (Boss: "trailing stop loss like breath donchain") -------------------------------------------------

def test_breadth_donchian_ladder_is_the_apps_and_locks_from_the_next_minute():
    assert S.AMEND3["G11"][0] == (0.25, 0.05) and np.isnan(S.AMEND3["G11"][1][1])
    assert S.AMEND3["G11"][2:] == ((0.60, 0.30), (1.00, 0.60))
    steps = np.array(S.AMEND3["G11"], dtype=np.float64)
    C = _flat()
    C[:4, 12] = 126.0                       # +26%: +5% locked from minute 13
    C[:4, 13:] = 110.0
    C[2, 16] = 104.0
    out = _sim(C, steps=steps, be=101.0)
    assert R[out[6]] == "lock" and out[5] == 16 and out[1] == pytest.approx(105.0)
    C2 = _flat()
    C2[:4, 12] = 131.0                      # +31%: breakeven 101 is under the +5% already locked: +5% stays
    C2[:4, 13:] = 110.0
    C2[2, 16] = 104.0
    assert _sim(C2, steps=steps, be=101.0)[1] == pytest.approx(105.0)


def test_the_a3_final_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "RESULTS", tmp_path)
    monkeypatch.setattr(S, "AMEND3_FLAG", tmp_path / "A3_FINAL_READ")
    with pytest.raises(SystemExit):
        S.amend3("final")
    (tmp_path / "amend3_design.json").write_text("{}")
    (tmp_path / "A3_FINAL_READ").write_text("x")
    with pytest.raises(SystemExit):
        S.amend3("final")
