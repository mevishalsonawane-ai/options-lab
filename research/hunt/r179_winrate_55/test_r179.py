"""R179 tests (synthetic): the breakeven lock (R162's BE10) in the R176 engine, the gates, the BH count."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
T = pytest.importorskip("r179study")
X = T.R7.S6.mods  # noqa: F841  (imports resolve paths)
import r106engine as RX  # noqa: E402
import r176engine as Z  # noqa: E402
import engine as E  # noqa: E402

NOTIME = E.Exits(stop=0.15, target=-1.0, tmax=0, sq_row=360)


def _flat(px=100.0, n=375):
    C = np.full((5, n), px)
    C[4] = 1e7
    return C


def _sim(C, be: bool, qty=65.0):
    raw, real, ef, tks = RX.entry_info(C, 10, 360, qty)
    lkt, lkk = -1.0, 0.0
    if be:
        ch = lambda a, b: float(E.charges("NIFTY", np.array([a]), np.array([b]), qty, __import__("datetime").date(2026, 5, 4))[0])  # noqa: E731
        s0 = RX.stop_price(real, qty, 0.0, ch, tks, E.SLIP[2], E.SLIP[3])
        lkt, lkk = 0.10 * raw * qty * (1 - 1e-12), (s0 - raw) * qty
    r = Z.simulate_step(C, 10, 360, -1, 0.15, -1.0, lkt, lkk, 0.0, NOTIME.steps_arr(), 0, -1.0, -1.0, 0.05, qty, 0.0,
                        -1.0, -1.0, *E.SLIP)
    return real, lkk, r


def test_be_lock_moves_stop_to_net_zero_after_10pct():
    C = _flat()
    C[1, 15] = 111.0                                               # +11% on the raw fill
    C[:4, 16] = 108.0                                              # holds above the lock
    C[2, 17], C[0, 17], C[3, 17] = 99.0, 104.0, 100.0              # falls back towards the fill
    real, lkk, r = _sim(C, True)
    lock_px = 100.0 + lkk / 65.0
    assert r[6] == E.R_LOCK and r[5] == 17 and r[1] == pytest.approx(lock_px)
    assert lock_px > real                                          # above the fill, so the sale nets about Rs 0
    _, _, r0 = _sim(C, False)                                      # without BE: no exit at row 17 (-15% is far)
    assert r0[5] != 17


def test_be_lock_needs_10pct():
    C = _flat()
    C[1, 15] = 109.0                                               # only +9%
    C[2, 17], C[0, 17], C[3, 17] = 99.0, 104.0, 100.0
    _, _, r = _sim(C, True)
    assert r[5] != 17


def _st(win, net, trades=100, dd=1000.0, wm=-500.0):
    return dict(win=win, net=net, trades=trades, max_dd=dd, worst_month=dict(net=wm))


def test_gates():
    per = {p: _st(0.56, 100) for p in ("early", "final", "six", "year")}
    base = {p: _st(0.45, 90) for p in ("early", "final", "six", "year")}
    assert T.gates_r179(per, base, 0.05)["passed"]
    per["final"] = _st(0.549, 100)
    assert "win rate below 55% (year or final)" in T.gates_r179(per, base, 0.05)["fails"]
    per["final"] = _st(0.55, 100)                                  # boundary passes
    assert T.gates_r179(per, base, 0.05)["passed"]
    base["year"] = _st(0.45, 101)
    assert "net below its base (year or final)" in T.gates_r179(per, base, 0.05)["fails"]
    base["year"] = _st(0.45, 90)
    per["early"] = _st(0.6, -1)
    assert "not positive in every period" in T.gates_r179(per, base, 0.05)["fails"]
    per["early"] = _st(0.6, 10, dd=50000.0)
    g = T.gates_r179(per, base, 0.05)
    assert not g["passed"] and g["passed_except_account_stop"]


def test_counts():
    assert len(T.TESTS) == 8 and T.M_TOTAL == 4038


def test_amendment_a_cap_and_loss_stats():
    assert T.M_TOTAL_A == 4040 and T.M_TOTAL_B == 4042 and T.M_TOTAL_C == 4044
    tr = [dict(why="stop500", net=-1200.0), dict(why="stop500", net=-990.0), dict(why="time", net=-1500.0),
          dict(why="time", net=300.0)]
    s = T.loss_stats(tr)
    assert s == dict(cap_exits=2, over_1000=2, over_1000_cap=1, worst=-1500)
    assert T.loss_stats([]) == dict(cap_exits=0, over_1000=0, over_1000_cap=0, worst=0)


def test_cap_stop_nets_about_minus_1000():
    import datetime
    C = _flat(px=200.0)
    C[2, 20] = 150.0                                               # deep dip: the cap (about -15.4 / unit) acts
    qty = 65.0
    raw, real, ef, tks = RX.entry_info(C, 10, 360, qty)
    d = datetime.date(2026, 5, 4)
    ch = lambda a, b: float(E.charges("NIFTY", np.array([a]), np.array([b]), qty, d)[0])  # noqa: E731
    s = RX.stop_price(real, qty, 1000.0, ch, tks, E.SLIP[2], E.SLIP[3])
    assert s > 200.0 * 0.85                                        # tighter than -15% at a Rs 200 premium
    r = Z.simulate_step(C, 10, 360, -1, -1.0, -1.0, -1.0, 0.0, 0.0, NOTIME.steps_arr(), 0, -1.0, -1.0, 0.05, qty, 0.0,
                        s, -1.0, *E.SLIP)
    net = (r[3] - r[2]) * qty - ch(r[2], r[3])
    assert r[5] == 20 and -1000.0 - 15.0 <= net <= -950.0           # about -Rs 1,000 after charges and stop slippage
