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


# ---- amendment A4 (Boss: "if the 10% is received, make the stoploss to the start point") ----------------------------

def test_breakeven_once_10pct_is_the_preregistered_rule():
    rows = S.rows_of("G12")
    assert rows[0, :2].tolist() == [0, 0.10] and np.isnan(rows[0, 2]) and rows[0, 3] == 0
    assert X.keep_level(rows, 100.0, 109.9, 30.0, 100.6, 0.0) == -1.0
    assert X.keep_level(rows, 100.0, 110.0, 30.0, 100.6, 0.0) == pytest.approx(100.6)   # breakeven after charges
    C = _flat()
    C[:4, 12] = 111.0
    C[:4, 13:] = 104.0
    C[2, 15] = 100.0                        # back under breakeven: sold at it, from the next minute on
    out = _sim(C, rows, be=100.6)
    assert R[out[6]] == "lock" and out[5] == 15 and out[1] == pytest.approx(100.6)


def test_the_a4_final_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "RESULTS", tmp_path)
    monkeypatch.setattr(S, "AMEND4_FLAG", tmp_path / "A4_FINAL_READ")
    with pytest.raises(SystemExit):
        S.amend4("final")
    (tmp_path / "amend4_design.json").write_text("{}")
    (tmp_path / "A4_FINAL_READ").write_text("x")
    with pytest.raises(SystemExit):
        S.amend4("final")


# ---- amendment A5 (Boss: "find a way to keep the profit and make it does not loss money") ----------------------------
# G13 two lots (half sold at a +15% limit), G14 a short leg 2 strikes further out of the money once +20%, G15 a +20%
# limit resting only for the first 15 minutes. Synthetic days: a stand-in Day with only what the engine reads.
E5 = G.E
EX5 = E5.Exits(stop=0.15, tmax=20, need=0.05)                 # Liquidity's own exits (hunt/r51/fam_liq.py EX)
D5 = date(2025, 6, 2)                                         # BANKNIFTY lot 30, step 100


class _Day:
    def __init__(self, con, d=D5, expiry=False):
        E5._STACKS.clear()                                    # the day-stack cache is keyed by (index, date): a new day
        self.d, self.con, self.expiry, self.idx, self.atm = d, con, expiry, np.full((4, N), 100.0), None


def _plain(side="C", strike=50000.0, e0=10, fx0=-1):
    return dict(side=side, strike=strike, e0=e0, fx0=fx0, sgn=1 if side == "C" else -1, close=0.0)


def _b0(con, t=None, **kw):
    t = t or _plain(**kw)
    return X.price_trade("BANKNIFTY", _Day(con), t, EX5)


def _sim2(C, tgt2, win, fx=-1, tmax=20, need=0.05, tgt=-1.0):
    return X.simulate(C, 10, K.SQ_ROW, fx, 0.15, tgt, -1.0, 0.0, 0.0, NOSTEP, tmax, need, -1.0, K.TICK, 30.0, 0.0,
                      X.NOKEEP, *E5.SLIP, tgt2, win)


def _rise(C, row, hi, rest=110.0, upto=None):
    """Minute `row` trades up to `hi` (open 105, low 104, close 110); the minutes after it sit at `rest`."""
    C[0, row], C[1, row], C[2, row], C[3, row] = 105.0, hi, 104.0, 110.0
    C[:4, row + 1:upto] = rest
    return C


# ---- G15: the early take-profit
def test_spike_target_fills_only_in_the_first_15_minutes():
    C = _rise(_flat(), 24, 121.0)                             # fill row 10: minute 14 after it
    out = _sim2(C, 0.20, 15, fx=40)
    assert R[out[6]] == "target" and out[5] == 24 and out[1] == pytest.approx(120.0) and out[3] == pytest.approx(120.0)
    C2 = _rise(_flat(), 25, 121.0)                            # minute 15 after the fill: own exits only
    out2 = _sim2(C2, 0.20, 15, fx=40)
    assert R[out2[6]] == "rule_exit" and out2[5] == 40
    assert out2 == pytest.approx(_sim(C2, fx=40, tmax=20, need=0.05), nan_ok=True)
    C3 = _rise(_flat(), 10, 121.0)                            # the fill minute itself counts (minute 0)
    C3[0, 10] = 100.0
    assert R[_sim2(C3, 0.20, 15, fx=40)[6]] == "target"


def test_spike_target_edges_exact_level_stop_first_and_forced_exit_minute():
    C = _rise(_flat(), 14, 120.0)                             # exactly +20% fills
    assert R[_sim2(C, 0.20, 15, fx=40)[6]] == "target"
    C2 = _rise(_flat(), 14, 119.9)                            # just under: never
    assert R[_sim2(C2, 0.20, 15, fx=40)[6]] == "rule_exit"
    C3 = _rise(_flat(), 14, 125.0)
    C3[2, 14] = 84.0                                          # the stop (85) in the same minute acts first
    out3 = _sim2(C3, 0.20, 15, fx=40)
    assert R[out3[6]] == "stop" and out3[5] == 14
    C4 = _rise(_flat(), 20, 125.0)                            # an index exit at row 20's open: the trade is gone
    out4 = _sim2(C4, 0.20, 15, fx=20)
    assert R[out4[6]] == "rule_exit" and out4[5] == 20
    C5 = _flat()
    C5[:, 12] = np.nan                                        # a NaN minute triggers nothing
    _rise(C5, 13, 121.0)
    assert _sim2(C5, 0.20, 15, fx=40)[5] == 13


def test_spike_target_off_by_default_and_lower_target_wins():
    for seed in range(8):
        C = _walk(seed)
        base = _sim(C, tmax=20, need=0.05)
        assert _sim2(C, -1.0, 15) == pytest.approx(base, nan_ok=True)          # no level
        assert _sim2(C, 0.20, 0) == pytest.approx(base, nan_ok=True)           # no window
    C = _rise(_flat(), 14, 121.0)
    out = _sim2(C, 0.20, 15, fx=40, tgt=0.10)                 # the own +10% target is lower: it fills
    assert out[1] == pytest.approx(110.0)


@pytest.mark.parametrize("seed", range(10))
def test_spike_target_lookahead(seed):
    C = _walk(seed)
    a = _sim2(C, 0.20, 15)
    xr = a[5]
    if xr < 0 or xr >= N - 2:
        return
    C2 = C.copy()
    C2[:4, xr + 1:] = C2[:4, xr + 1:] * 3.0
    assert _sim2(C2, 0.20, 15) == pytest.approx(a, nan_ok=True)


def test_g15_price_trade_passes_the_window_through():
    C = _rise(_flat(), 20, 122.0)
    con = {("C", 50000.0): C}
    t = _plain(fx0=40)
    b0 = _b0(con, t)
    r = X.price_trade("BANKNIFTY", _Day(con), t, EX5, X.NOKEEP, None, tgt2=0.20, tgt2_win=15)
    assert b0["why"] == "rule_exit" and r["why"] == "target" and r["xr"] == 20 and r["X"] == pytest.approx(120.0)
    assert r["net"] > b0["net"]


# ---- G13: two lots, lot A sold at +15%
def test_half_lot_a_sells_at_15pct_and_the_trade_is_the_mean_of_two_lots():
    C = _rise(_flat(), 12, 116.0)
    con = {("C", 50000.0): C}
    t = _plain(fx0=40)
    r = X.price_half("BANKNIFTY", _Day(con), t, EX5, 0.15)
    b0 = _b0(con, t)
    qty = float(K.lot("BANKNIFTY", D5))
    assert qty == 30.0
    rE = 100.10                                               # 100 x 1.0005 + 1 tick (heavy volume)
    net_a = (115.0 - rE) * qty - float(E5.charges("BANKNIFTY", np.array([rE]), np.array([115.0]), qty, D5)[0])
    assert r["why_a"] == "target" and r["xr_a"] == 12 and r["X_a"] == pytest.approx(115.0)
    assert r["net_a"] == pytest.approx(net_a)
    assert r["net_b"] == pytest.approx(b0["net"]) and r["why"] == b0["why"] == "rule_exit" and r["xr"] == 40
    assert r["net"] == pytest.approx((net_a + b0["net"]) / 2.0)                 # per lot: each lot's own fills and charges
    assert r["gross"] == pytest.approx(((115.0 - 100.0) * qty + b0["gross"]) / 2.0)


def test_half_trades_that_never_reach_15pct_are_b0s():
    C = _rise(_flat(), 12, 114.9)
    con = {("C", 50000.0): C}
    t = _plain(fx0=40)
    r = X.price_half("BANKNIFTY", _Day(con), t, EX5, 0.15)
    b0 = _b0(con, t)
    assert r["why_a"] == "rule_exit" and r["net"] == pytest.approx(b0["net"]) and r["net_a"] == pytest.approx(r["net_b"])
    C2 = _rise(_flat(), 12, 115.0)                            # +15% (100 x 1.15 is 114.99999999999999 in floats) fills
    assert X.price_half("BANKNIFTY", _Day({("C", 50000.0): C2}), t, EX5, 0.15)["why_a"] == "target"


def test_half_edges_stop_first_exit_minute_and_no_fill():
    C = _rise(_flat(), 12, 120.0)
    C[2, 12] = 80.0                                           # the stop in the same minute: both lots stop
    con = {("C", 50000.0): C}
    r = X.price_half("BANKNIFTY", _Day(con), _plain(fx0=40), EX5, 0.15)
    assert r["why_a"] == r["why"] == "stop" and r["net"] == pytest.approx(r["net_b"])
    C2 = _flat()                                              # the time-stop minute (fill + 19) touches +15% but closes
    C2[:4, 11:] = 103.0                                       # under +5%: lot B leaves on the time stop at its close,
    C2[1, 29] = 116.0                                         # lot A's limit fills inside that minute
    r2 = X.price_half("BANKNIFTY", _Day({("C", 50000.0): C2}), _plain(), EX5, 0.15)
    assert r2["why"] == "time" and r2["xr"] == 29 and r2["why_a"] == "target" and r2["xr_a"] == 29
    C3 = _flat()
    C3[:, 10:13] = np.nan                                     # no fill
    assert X.price_half("BANKNIFTY", _Day({("C", 50000.0): C3}), _plain(), EX5, 0.15) is None
    assert X.price_half("BANKNIFTY", _Day({("C", 50100.0): C}), _plain(), EX5, 0.15) is None    # no contract


@pytest.mark.parametrize("seed", range(6))
def test_half_lookahead(seed):
    C = _walk(seed)
    con = {("C", 50000.0): C}
    t = _plain()
    r = X.price_half("BANKNIFTY", _Day(con), t, EX5, 0.15)
    if r is None:
        return
    last = max(r["xr"], r["xr_a"])
    if last >= N - 2:
        return
    C2 = C.copy()
    C2[:4, last + 1:] = C2[:4, last + 1:] * 3.0
    r2 = X.price_half("BANKNIFTY", _Day({("C", 50000.0): C2}), t, EX5, 0.15)
    for k in ("net", "net_a", "net_b", "xr", "xr_a", "why", "why_a"):
        assert r2[k] == pytest.approx(r[k]) if isinstance(r[k], float) else r2[k] == r[k]


# ---- G14: the spread once +20%
def test_spread_arm_row_edges():
    C = _rise(_flat(), 12, 119.9)
    assert X.spread_arm_row(C, 10, 40, 0.20) == -1                             # never +20%
    C = _rise(_flat(), 12, 120.0)
    assert X.spread_arm_row(C, 10, 40, 0.20) == 12                             # exactly +20%, judged at 12's end
    assert X.spread_arm_row(C, 10, 12, 0.20) == -1                             # in the exit minute: too late
    assert X.spread_arm_row(C, -1, 40, 0.20) == -1                             # no fill
    C2 = _rise(_flat(), 13, 121.0)
    C2[2, 12] = np.nan                                                         # a minute with no trade is skipped
    C2[1, 12] = 130.0
    assert X.spread_arm_row(C2, 10, 40, 0.20) == 13
    C3 = C.copy()
    C3[:4, 13:] = 999.0                                                        # later bars never change it
    assert X.spread_arm_row(C3, 10, 40, 0.20) == 12


def test_buyback_row_open_or_next_minute():
    sq = K.SQ_ROW
    assert X.buyback_row("rule_exit", 30, 30, sq) == 30                        # long sold at 30's open
    assert X.buyback_row("squareoff", sq, -1, sq) == sq
    for why in ("stop", "lock", "target", "time"):                             # left inside minute 30
        assert X.buyback_row(why, 30, 40, sq) == 31
    assert X.buyback_row("missing", 40, 40, sq) == 40                          # forced row with no price: its row
    assert X.buyback_row("missing", 29, 40, sq) == 30                          # time-stop minute with no trade


def _short(p=60.0):
    C = _flat(p)
    return C


def test_short_leg_fills_slippage_and_edges():
    C2 = _short(60.0)
    C2[0, 30] = 40.0
    leg = X.short_leg(C2, 12, 30, 30, 30.0, K.TICK)
    assert leg == pytest.approx((60.0, 59.92, 13, 40.0, 40.07))               # bid side -5 bps - 1 tick; ask +5 bps + 1
    C3 = C2.copy()
    C3[0, 13] = np.nan                                                         # no trade at 13: sold at 14's open
    assert X.short_leg(C3, 12, 30, 30, 30.0, K.TICK)[2] == 14
    C4 = C2.copy()
    C4[0, 13:16] = np.nan                                                      # nothing in 13-15: no sale
    assert X.short_leg(C4, 12, 30, 30, 30.0, K.TICK) is None
    assert X.short_leg(C2, 12, 14, 14, 30.0, K.TICK)[2] == 13                  # the sale must come before the long's exit
    assert X.short_leg(C2, 12, 13, 13, 30.0, K.TICK) is None
    C5 = C2.copy()
    C5[0, 30] = np.nan
    C5[0, 31] = 41.0                                                           # buy-back row has no trade: the next open
    assert X.short_leg(C5, 12, 30, 30, 30.0, K.TICK)[3] == pytest.approx(41.0)
    C6 = C2.copy()
    C6[0, 30:34] = np.nan
    C6[3, 29] = 45.0                                                           # none within 3: its last close
    assert X.short_leg(C6, 12, 30, 30, 30.0, K.TICK)[3] == pytest.approx(45.0)
    C7 = C2.copy()
    C7[:4, 31:] = 999.0                                                        # bars after the buy-back never matter
    assert X.short_leg(C7, 12, 30, 30, 30.0, K.TICK) == pytest.approx(leg)


def _spread_day(long_hi_row=14, short_buy=55.0, side="C", short_k=50200.0, fx0=30):
    L = _rise(_flat(), long_hi_row, 121.0)
    S2 = _short(50.0)
    S2[0, fx0] = short_buy
    return {(side, 50000.0): L, (side, short_k): S2}


def test_spread_adds_the_short_leg_with_its_own_fills_and_charges():
    con = _spread_day()
    t = _b0(con, fx0=30)
    assert t["why"] == "rule_exit" and t["xr"] == 30
    r, how = X.price_spread("BANKNIFTY", _Day(con), t, EX5, 0.20, 2)
    assert how == "spread" and r["arm_row"] == 14 and r["short_strike"] == 50200.0
    assert r["short_sell_row"] == 15 and r["short_buy_row"] == 30
    qty = 30.0
    s_real = float(E5._r2(50.0 * 0.9995)) - 0.05              # bid side: -5 bps - 1 tick (heavy volume)
    b_real = float(E5._r2(55.0 * 1.0005)) + 0.05              # ask side: +5 bps + 1 tick
    ch = float(E5.charges("BANKNIFTY", np.array([b_real]), np.array([s_real]), qty, D5)[0])
    net_s = (s_real - b_real) * qty - ch
    assert r["short_net"] == pytest.approx(net_s) and r["net"] == pytest.approx(t["net"] + net_s)
    assert r["why"] == t["why"] and r["xr"] == t["xr"] and r["held_peak"] == t["held_peak"]


def test_spread_no_short_strike_late_arm_and_put_side():
    con = _spread_day(short_k=50300.0)                        # the 2-OTM call (50200) is not in the stack
    t = _b0(con, fx0=30)
    r, how = X.price_spread("BANKNIFTY", _Day(con), t, EX5, 0.20, 2)
    assert how == "no_strike" and r["net"] == t["net"]
    con = _spread_day(long_hi_row=29)                         # armed at 29: its sale (30) is the long's exit row
    t = _b0(con, fx0=30)
    r, how = X.price_spread("BANKNIFTY", _Day(con), t, EX5, 0.20, 2)
    assert how == "no_arm" and r["net"] == t["net"]
    con = _spread_day(long_hi_row=30)                         # the high is in the exit minute (sold at its open)
    t = _b0(con, fx0=30)
    assert X.price_spread("BANKNIFTY", _Day(con), t, EX5, 0.20, 2)[1] == "no_arm"
    con = _spread_day(side="P", short_k=49800.0)              # a put: 2 strikes further OTM is 200 points lower
    t = _b0(con, side="P", fx0=30)
    r, how = X.price_spread("BANKNIFTY", _Day(con), t, EX5, 0.20, 2)
    assert how == "spread" and r["short_strike"] == 49800.0
    assert X.short_strike("MIDCPNIFTY", "C", 12000.0, 2) == 12050.0


def test_spread_buys_back_the_minute_after_a_stop_inside_a_minute():
    con = _spread_day(fx0=40)
    con[("C", 50000.0)][:4, 15:] = 100.0
    con[("C", 50000.0)][2, 20] = 84.0                         # the long stops inside minute 20
    con[("C", 50200.0)][0, 21] = 44.0
    t = _b0(con, fx0=40)
    assert t["why"] == "stop" and t["xr"] == 20
    r, how = X.price_spread("BANKNIFTY", _Day(con), t, EX5, 0.20, 2)
    assert how == "spread" and r["short_buy_row"] == 21 and r["short_buy"] == pytest.approx(44.0)


def test_spread_lookahead_bars_after_the_buyback_never_matter():
    con = _spread_day()
    t = _b0(con, fx0=30)
    r, _ = X.price_spread("BANKNIFTY", _Day(con), t, EX5, 0.20, 2)
    con2 = {k: v.copy() for k, v in con.items()}
    for v in con2.values():
        v[:4, 31:] = 777.0
    t2 = _b0(con2, fx0=30)
    r2, _ = X.price_spread("BANKNIFTY", _Day(con2), t2, EX5, 0.20, 2)
    assert r2["net"] == pytest.approx(r["net"]) and r2["short_net"] == pytest.approx(r["short_net"])


# ---- A5's study plumbing
def test_a5_rules_are_the_preregistered_ones_and_leave_the_earlier_tests_alone():
    assert S.AMEND5 == {"G13": dict(kind="half", target=0.15), "G14": dict(kind="spread", gain=0.20, otm=2),
                        "G15": dict(kind="spike", target=0.20, minutes=15)}
    assert S.TESTS == ("G1", "G2", "G3", "G4", "G5", "G6", "G7")
    assert S.rows_of("G13").shape == (0, 4)
    assert S.priors_of("amend_A5.json") == ("amend_A1.json", "amend_A3.json", "amend_A4.json")   # BH over 15
    assert S.priors_of("amend_A4.json") == ("amend_A1.json", "amend_A3.json")                    # as A4 was read
    assert S.priors_of("amend_A1.json") == ()


def test_the_a5_final_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "RESULTS", tmp_path)
    monkeypatch.setattr(S, "AMEND5_FLAG", tmp_path / "A5_FINAL_READ")
    with pytest.raises(SystemExit):
        S.amend5("final")
    assert not (tmp_path / "A5_FINAL_READ").exists()
    (tmp_path / "amend5_design.json").write_text("{}")
    (tmp_path / "A5_FINAL_READ").write_text("x")
    with pytest.raises(SystemExit):
        S.amend5("final")
