"""R186 tests (synthetic): the net target (fill at the target, gap through at the open, never before the fill), the step
lock (L500 / L-BE1000), target / lock off = R176's simulator, the gates, the give-back count, the BH count."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
T = pytest.importorskip("r186study")
T.R7.mods()                                                         # resolves the R176 / R106 / R51 paths
import r186engine as Z  # noqa: E402
import r176engine as Z6  # noqa: E402
import r106engine as RX  # noqa: E402
import engine as E  # noqa: E402

NOTIME = E.Exits(stop=0.15, target=-1.0, tmax=0, sq_row=360)
QTY = 65.0
DAY = date(2026, 5, 4)


def _ch(a, b, qty=QTY):
    return float(E.charges("NIFTY", np.array([a]), np.array([b]), qty, DAY)[0])


def _flat(px=100.0, n=375):
    C = np.full((5, n), px)
    C[4] = 1e7
    return C


def _sim(C, tgt=-1.0, mode="", be=True, fx=-1, ix=None, ix_lvl=np.nan, ix_on=False, sgn=1.0):
    raw, real, ef, tks = RX.entry_info(C, 10, 360, QTY)
    lkt, lkk = -1.0, 0.0
    if be:
        s0 = RX.stop_price(real, QTY, 0.0, _ch, tks, E.SLIP[2], E.SLIP[3])
        lkt, lkk = 0.10 * raw * QTY * (1 - 1e-12), (s0 - raw) * QTY
    tp = RX.target_price(real, QTY, tgt, _ch) if tgt > 0 else -1.0
    rt, rl = Z.lock_levels(mode, real, QTY, 1, _ch, tks, float(np.nanmax(C[1][ef:360])))
    ixt = RX.target_price(real, QTY, 1000.0, _ch) if ix_on else -1.0
    r = Z.simulate(C, 10, 360, fx, 0.15, lkt, lkk, 0.0, NOTIME.steps_arr(), 0, -1.0, -1.0, 0.05, QTY, 0.0, tp, rt, rl,
                   Z.NONE if ix is None else ix, ix_lvl, ixt, sgn, *E.SLIP)
    return real, tp, rt, rl, r


def _net(r):
    return (r[3] - r[2]) * QTY - _ch(r[2], r[3])


def test_target_fills_at_net_target_price():
    C = _flat()
    C[1, 20] = 140.0                                                 # the high runs far above the target
    real, tp, _, _, r = _sim(C, 1000.0, be=False)
    assert r[6] == E.R_TARGET and r[5] == 20 and r[1] == pytest.approx(tp) and r[3] == pytest.approx(tp)
    assert 1000.0 <= _net(r) < 1000.0 + 0.05 * QTY + 1.0              # nets at least Rs 1,000, at most a tick more
    assert (tp - 0.05 - real) * QTY - _ch(real, tp - 0.05) < 1000.0     # one tick lower would net less


def test_target_gap_through_fills_at_open():
    C = _flat()
    C[0, 20], C[1, 20], C[2, 20], C[3, 20] = 130.0, 131.0, 129.0, 130.0   # opens above the target
    real, tp, _, _, r = _sim(C, 1000.0, be=False)
    assert tp < 130.0
    assert r[6] == Z.R_TGAP and r[5] == 20 and r[1] == 130.0 and r[3] == 130.0
    assert _net(r) > 1000.0


def test_target_never_before_the_fill():
    C = _flat()
    C[:4, :10] = np.nan                                              # nothing trades before the signal row
    C[1, 5] = 200.0                                                  # (a high before the fill must not count)
    C[0, 3] = 200.0
    real, tp, _, _, r = _sim(C, 1000.0, be=False)
    assert r[4] == 10 and r[6] != E.R_TARGET and r[6] != Z.R_TGAP
    C2 = _flat()
    C2[1, 10] = 140.0                                                # the fill minute's own high (after the open fill)
    _, _, _, _, r2 = _sim(C2, 1000.0, be=False)
    assert r2[4] == 10 and r2[5] >= r2[4]


def test_stop_and_target_same_minute_takes_the_stop():
    C = _flat()
    C[1, 20], C[2, 20] = 140.0, 80.0
    _, _, _, _, r = _sim(C, 1000.0, be=False)
    assert r[6] == E.R_STOP and r[5] == 20


@pytest.mark.parametrize("seed", range(6))
def test_off_equals_r176(seed):
    rng = np.random.default_rng(seed)
    n = 375
    c = 100.0 * np.exp(np.cumsum(rng.normal(0, 0.01, n)))
    C = np.vstack([c * (1 + rng.normal(0, 0.002, n)), c * 1.01, c * 0.99, c, np.full(n, 1e6)])
    C[1] = np.maximum(C[1], np.maximum(C[0], C[3]))
    C[2] = np.minimum(C[2], np.minimum(C[0], C[3]))
    ex = E.Exits(stop=0.15, target=-1.0, steps=((0.3, np.nan), (0.6, 0.3), (1.0, 0.6), (0.25, 0.05)), tmax=30,
                 sq_row=360)
    for lkt, lkk in ((-1.0, 0.0), (650.0, 40.0)):
        a = Z6.simulate_step(C, 10, 360, -1, ex.stop, -1.0, lkt, lkk, 0.0, ex.steps_arr(), ex.tmax, ex.need, -1.0, 0.05,
                             QTY, 101.0, -1.0, -1.0, *E.SLIP)
        b = Z.simulate(C, 10, 360, -1, ex.stop, lkt, lkk, 0.0, ex.steps_arr(), ex.tmax, ex.need, -1.0, 0.05, QTY, 101.0,
                       -1.0, Z.NONE, Z.NONE, Z.NONE, np.nan, -1.0, 1.0, *E.SLIP)
        assert tuple(np.nan_to_num(a)) == pytest.approx(tuple(np.nan_to_num(b[:7])))


def test_l500_levels_and_lock():
    C = _flat()
    real, _, rt, rl, _ = _sim(C, mode="L500", be=False)
    nets = [(x - real) * QTY - _ch(real, x) for x in rt]
    assert nets[0] >= 1000.0 and len(rt) == 1                       # flat contract: only the first level is built
    hi = _flat()
    hi[1, 15] = 160.0
    real, _, rt, rl, _ = _sim(hi, mode="L500", be=False)
    for k, (x, s) in enumerate(zip(rt, rl)):
        assert (x - real) * QTY - _ch(real, x) >= 1000.0 + 500 * k
        rx = s * E.SLIP[2] - 2.0 * E.SLIP[3] * float(E._ticks(E._vol5(hi[4], 9, QTY))) * 0.05
        assert (rx - real) * QTY - _ch(real, rx) >= 500.0 + 500 * k - 1e-6     # the planned stop sale nets the lock
    assert np.all(np.diff(rl) > 0)


def test_l500_acts_next_minute_and_only_up():
    C = _flat()
    real, _, rt, rl, _ = _sim(_flat(), mode="L500", be=False)
    C[1, 20] = rt[0] + 0.05                                          # +1,000 a lot reached in minute 20
    C[2, 20] = rl[0] - 1.0                                           # same minute dips under the lock: no exit yet
    C[:4, 21] = rl[0] + 1.0                                          # minute 21 stays above the lock
    C[1, 22], C[2, 22], C[0, 22], C[3, 22] = rl[0] + 1.0, rl[0] - 0.5, rl[0] + 0.5, rl[0]   # minute 22 trades through
    _, _, _, _, r = _sim(C, mode="L500", be=False)
    assert r[6] == Z.R_RLOCK and r[5] == 22 and r[1] == pytest.approx(rl[0])
    assert _net(r) > 400.0                                           # about +Rs 500 after slippage and charges


def test_lbe_locks_zero_only():
    hi = _flat()
    hi[1, 15] = 160.0
    real, _, rt, rl, _ = _sim(hi, mode="LBE", be=False)
    assert len(rt) == 1 and len(rl) == 1
    rx = rl[0] * E.SLIP[2] - 2.0 * E.SLIP[3] * float(E._ticks(E._vol5(hi[4], 9, QTY))) * 0.05
    assert 0.0 <= (rx - real) * QTY - _ch(real, rx) < 0.05 * QTY + 1.0


def test_twin_forced_exit_matches_sim():
    C = _flat()
    _, _, _, _, r = _sim(C, be=True, fx=50)
    assert r[6] == E.R_FORCED and r[5] == 50


def _st(net, trades=100, dd=1000.0, wm=-500.0):
    return dict(net=net, trades=trades, max_dd=dd, worst_month=dict(net=wm))


def test_gates():
    base = {p: _st(1000.0) for p in ("early", "final", "six", "year")}
    per = {p: _st(1000.0) for p in ("early", "final", "six", "year")}
    assert T.gates_r186(per, base, 0.05)["passed"]                  # equal to the base passes (>=)
    per["final"] = _st(999.0)
    assert "net below the base (year or final)" in T.gates_r186(per, base, 0.05)["fails"]
    per["final"] = _st(1000.0, trades=39)
    assert "< 40 final trades" in T.gates_r186(per, base, 0.05)["fails"]
    per["final"] = _st(1000.0, wm=-13001.0)
    g = T.gates_r186(per, base, 0.05)
    assert not g["passed"] and g["passed_except_account_stop"]
    per["final"] = _st(1000.0)
    assert not T.gates_r186(per, base, 0.11)["passed"]
    per["early"] = _st(0.0)
    base["early"] = _st(-5.0)
    assert "not positive in every period" in T.gates_r186(per, base, 0.05)["fails"]


def test_giveback():
    tr = [dict(net=-500.0, best=1200.0), dict(net=-100.0, best=2100.0), dict(net=-50.0, best=300.0),
          dict(net=900.0, best=1600.0), dict(net=1500.0, best=1600.0)]
    g = T.giveback(tr)
    assert g["losers"] == 3 and g["ge1000"]["lost_after"] == 2 and g["ge1500"]["lost_after"] == 1
    assert g["ge2000"]["lost_after"] == 1 and g["ge1000"]["reached"] == 4
    assert g["ge1000"]["given_back"] == 1700 + 2200 and g["ge1000"]["final_loss"] == -600
    assert g["share_losers_were_1000"] == pytest.approx(2 / 3, abs=1e-4)
    assert T.giveback([])["share_losers_were_1000"] is None


def test_target_effect():
    d = date(2026, 5, 4)
    t1 = dict(und="NIFTY", day=d, er_sig=10, side="C", net=1000.0, why="target")
    t2 = dict(und="NIFTY", day=d, er_sig=20, side="P", net=1000.0, why="target_gap")
    t3 = dict(und="NIFTY", day=d, er_sig=30, side="C", net=-200.0, why="stop")
    plain = {("NIFTY", d, 10, "C"): dict(net=3000.0), ("NIFTY", d, 20, "P"): dict(net=-500.0),
             ("NIFTY", d, 30, "C"): dict(net=-200.0)}
    e = T.target_effect([t1, t2, t3], plain, ("target", "target_gap"))
    assert e == dict(closed=2, base_made_more=1, profit_given_up=2000, profit_saved=1500, net_effect=-500)


def test_bh_count():
    assert len(T.TESTS) == 15 and T.M_TOTAL == 4852 + 15 == 4867
    assert {a for a, _ in T.TESTS} == {"LIQ", "R72", "R51"}


# ------------------------------------------------------------------------------------------------ Amendment B
def test_p60_levels_lock_60pct():
    hi = _flat()
    hi[1, 15] = 160.0
    real, _, rt, rl, _ = _sim(hi, mode="P60", be=False)
    assert len(rt) > 10 and np.all(np.diff(rt) >= 0) and np.all(np.diff(rl) >= 0)
    tks = float(E._ticks(E._vol5(hi[4], 9, QTY)))
    for k in (0, 5, len(rt) - 1):
        trig_net = 1000.0 + 50.0 * k
        assert (rt[k] - real) * QTY - _ch(real, rt[k]) >= trig_net
        rx = rl[k] * E.SLIP[2] - 2.0 * E.SLIP[3] * tks * 0.05
        got = (rx - real) * QTY - _ch(real, rx)
        assert 0.6 * trig_net - 1e-6 <= got < 0.6 * trig_net + 0.05 * QTY + 1.0


def test_idx_lock_sells_next_open_after_index_crosses_back():
    C = _flat()
    real, _, rt, _, _ = _sim(_flat(), mode="L500", be=False)
    ix = np.full(375, 20000.0)
    C[1, 20] = rt[0] + 0.05                                            # +Rs 1,000 reached in minute 20
    C[:4, 21:] = 105.0
    ix[22] = 19990.0                                                  # minute 22's index close is back under the entry level
    _, _, _, _, r = _sim(C, be=False, ix=ix, ix_lvl=20000.0, ix_on=True)
    assert r[6] == Z.R_IDX and r[5] == 23 and r[1] == 105.0
    _, _, _, _, rp = _sim(C, be=False, ix=ix, ix_lvl=20000.0, ix_on=True, sgn=-1.0)   # a put: a fall is not against it
    assert rp[6] != Z.R_IDX
    C2 = C.copy()
    C2[1, 20] = 100.0                                                  # never +Rs 1,000: the index lock never arms
    _, _, _, _, r2 = _sim(C2, be=False, ix=ix, ix_lvl=20000.0, ix_on=True)
    assert r2[6] != Z.R_IDX


def test_split_breakeven_leg_moves_after_target_price():
    C = _flat()
    raw, real, ef, tks = RX.entry_info(C, 10, 360, QTY)
    tgt1 = RX.target_price(real, QTY / 3, 1000.0, lambda a, b: _ch(a, b, QTY / 3))
    sbe = RX.stop_price(real, 2 * QTY / 3, 0.0, lambda a, b: _ch(a, b, 2 * QTY / 3), tks, E.SLIP[2], E.SLIP[3])
    C[1, 20] = tgt1 + 0.05
    C[:4, 21] = sbe + 1.0
    C[2, 22], C[0, 22], C[1, 22], C[3, 22] = sbe - 0.5, sbe + 0.5, sbe + 1.0, sbe
    rB = Z.simulate(C, 10, 360, -1, 0.15, -1.0, 0.0, 0.0, NOTIME.steps_arr(), 0, -1.0, -1.0, 0.05, QTY, 0.0, -1.0,
                    np.array([tgt1]), np.array([sbe]), Z.NONE, np.nan, -1.0, 1.0, *E.SLIP)
    assert rB[6] == Z.R_RLOCK and rB[5] == 22 and rB[1] == pytest.approx(sbe)
    rA = Z.simulate(C, 10, 360, -1, 0.15, -1.0, 0.0, 0.0, NOTIME.steps_arr(), 0, -1.0, -1.0, 0.05, QTY, 0.0, tgt1,
                    Z.NONE, Z.NONE, Z.NONE, np.nan, -1.0, 1.0, *E.SLIP)
    assert rA[6] == E.R_TARGET and rA[5] == 20


def test_bh_count_b():
    assert len(T.TESTS_B) == 9 and T.M_TOTAL_B == 4867 + 9 == 4876
