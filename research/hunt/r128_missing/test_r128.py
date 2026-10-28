"""R128 tests: straddle exits (time, close, stop/trail), convex exits on a single option, periods.

    python -m pytest research/hunt/r128_missing/test_r128.py -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r128 as R  # noqa: E402

D = date(2025, 6, 10)


def _opt(price=100.0):
    a = np.full((R.N, 5), np.nan)
    a[:, :4] = price
    a[:, 4] = 6500
    return a


def test_straddle_time_and_close_exits():
    c, p = _opt(), _opt()
    a = R.straddle(c, p, 10, 364, 65, D, "NIFTY", "a")
    b = R.straddle(c, p, 10, 364, 65, D, "NIFTY", "b")
    assert a["exit"] == 70 and b["exit"] == 364 and a["net"] < 0 and a["charges"] > 0


def test_straddle_stop_and_trail():
    c, p = _opt(), _opt()
    c[30:, :4] = 30.0                                   # combined 130 < 0.65 x 200 -> stop
    s = R.straddle(c, p, 10, 364, 65, D, "NIFTY", "c")
    assert s["why"] == "stop" and s["exit"] == 30
    c, p = _opt(), _opt()
    c[20:, :4] = 160.0                                  # combined 260 = +30% -> peak
    c[40:, :4] = 115.0                                  # combined 215 <= 0.85 x 260 -> trail
    s = R.straddle(c, p, 10, 364, 65, D, "NIFTY", "c")
    assert s["why"] == "trail" and s["exit"] == 40 and s["net"] > 0


def test_convex_exit_trail_and_hold():
    a = _opt(100.0)
    a[20:, :4] = 140.0                                  # +40 x 65 = +2,600 gross
    a[30:, :4] = 120.0                                  # falls below 60% of the peak net -> trail
    r = R.convex_exit(a, 10, 364, 65, D, "NIFTY", 1000.0, 2000.0, "x1")
    assert r["why"] == "trail" and r["exit"] == 30 and r["net"] > 0
    r2 = R.convex_exit(a, 10, 364, 65, D, "NIFTY", 1000.0, 2000.0, "x2")
    assert r2["why"] == "close" and r2["exit"] == 364
    b = _opt(100.0)
    b[25, 2] = 50.0                                     # a low wick through the -Rs 2,000 stop
    assert R.convex_exit(b, 10, 364, 65, D, "NIFTY", 1000.0, 2000.0, "x2")["why"] == "stop"


def test_periods():
    assert R.period_of(date(2023, 5, 1)) == "F1" and R.period_of(date(2026, 5, 1)) == "holdout"
    assert R.period_of(date(2026, 3, 20)) is None


# ---- S5 Trail's app port (export_s5.py, s5_app_check.py), 28 Oct ------------------------------------------------------
def test_app_iv_inverts_black76_and_refuses_impossible_prices():
    import s5_app_check as C
    t = 3 / 365
    for call in (True, False):
        px = C.b76(25000.0, 25100.0, t, 0.12, call)
        assert abs(C.app_iv(px, 25000.0, 25100.0, t, call) - 12.0) < 1e-6
    assert np.isnan(C.app_iv(10.0, 25000.0, 24000.0, t, True))      # below intrinsic
    assert np.isnan(C.app_iv(30000.0, 25000.0, 25000.0, t, True))   # above the maximum
    assert np.isnan(C.app_iv(np.nan, 25000.0, 25000.0, t, True)) and np.isnan(C.app_iv(10.0, 25000.0, 25000.0, 0.0, True))
    v = C.app_iv(0.05, 25000.0, 26000.0, t, True)                                   # a far call at one tick: priced back
    assert abs(C.b76(25000.0, 26000.0, t, v / 100, True) - 0.05) < 1e-6


def test_years_to_expiry_counts_to_1530_and_never_negative():
    import s5_app_check as C
    d = date(2026, 10, 26)
    assert abs(C.years_to_expiry(d, 4, date(2026, 10, 27)) - (30 * 3600 + 10 * 60) / (365 * 86400)) < 1e-12
    assert C.years_to_expiry(d, 4, date(2026, 10, 25)) == 0.0


def test_iv_shift_only_moves_the_inputs_the_network_reads():
    import s5_app_check as C
    assert set(C.IV_SHIFT) == {("C", -3), ("C", 0), ("C", 3), ("P", -3), ("P", 0), ("P", 3)}
    assert all(v < 0 for (s, _), v in C.IV_SHIFT.items() if s == "C") and all(v > 0 for (s, _), v in C.IV_SHIFT.items() if s == "P")


def test_numpy_forward_matches_torch_on_random_weights():
    torch = __import__("pytest").importorskip("torch")
    import export_s5 as E
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "r123_neural_70"))
    import r123net as M
    torch.manual_seed(0)
    net = M.make_net(42).eval()
    st = {k: v.double().numpy() for k, v in net.state_dict().items()}
    rng = np.random.default_rng(1)
    seq = rng.normal(size=(120, 7)).astype(np.float32)
    tab = rng.normal(size=42).astype(np.float32)
    with torch.no_grad():
        a, b, c = net(torch.from_numpy(seq[None]), torch.from_numpy(tab[None]))
    p, rg, dr = E.forward(st, seq.astype(float), tab.astype(float))
    assert np.abs(p - torch.softmax(a[0], -1).numpy()).max() < 1e-5
    assert np.abs(rg - b[0].numpy()).max() < 1e-5 and np.abs(dr - torch.sigmoid(c[0]).numpy()).max() < 1e-5
