"""R103 tests: strike rounding and the 10-step cap, the legs of each structure, fills at the next minute's open,
slippage and dated charges, the stop / lock / target / time paths, the look-ahead guard, BH, the bootstrap, the
summary maths and the holdout lock. Hand-made days only (no lake).

    python -m pytest research/hunt/r103_sell/tests -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import r103engine as E  # noqa: E402
import r103study as S  # noqa: E402

N = E.NROWS


def con(prices, vol=100.0):
    """A contract with the same price as open/high/low/close each minute; NaN where prices is NaN."""
    p = np.asarray(prices, float)
    a = np.full((5, N), np.nan)
    a[0] = a[1] = a[2] = a[3] = p
    a[4] = np.where(np.isfinite(p), vol, np.nan)
    return a


def flat_day(k0=25000.0, call=100.0, put=100.0, step=50.0, otm_decay=10.0, spot=25000.0):
    """A day with flat prices: strikes k0 +- 10 steps; ATM call/put at `call`/`put`, each step out `otm_decay` cheaper."""
    D = SimpleNamespace(idx=np.full((4, N), spot), atm=np.full(N, k0), con={}, expiry=False)
    for j in range(-10, 11):
        k = k0 + j * step
        c = max(call - max(j, 0) * otm_decay + max(-j, 0) * step, 0.5)
        p = max(put - max(-j, 0) * otm_decay + max(j, 0) * step, 0.5)
        D.con[("C", k)] = con(np.full(N, c))
        D.con[("P", k)] = con(np.full(N, p))
    return D


def test_steps_and_lot():
    assert E.steps_of(200, 50) == 4 and E.steps_of(224, 50) == 4 and E.steps_of(225, 50) == 5
    assert E.steps_of(10, 50) == 1 and E.steps_of(5000, 50) == 10 and E.steps_of(float("nan"), 50) == 0
    assert E.lot("NIFTY", date(2026, 3, 1)) == 65 and E.lot("NIFTY", date(2025, 3, 1)) == 75
    assert E.lot("BANKNIFTY", date(2026, 3, 1)) == 30 and E.lot("BANKNIFTY", date(2025, 9, 1)) == 35
    assert E.lot("BANKNIFTY", date(2022, 3, 1)) == 25 and E.lot("NIFTY", date(2022, 3, 1)) == 50


def test_setup_legs_for_each_structure():
    D = flat_day()                                   # straddle S = 200 points -> 4 steps of 50
    st = E.setup(D, "NIFTY", "IF")
    assert st.k0 == 25000 and st.s == 200
    assert st.legs == (("C", 25000, -1), ("P", 25000, -1), ("C", 25200, 1), ("P", 24800, 1))
    st = E.setup(D, "NIFTY", "IFW")                  # 1.5 x 200 = 300 -> 6 steps
    assert st.legs[2] == ("C", 25300, 1) and st.legs[3] == ("P", 24700, 1)
    st = E.setup(D, "NIFTY", "IC")                   # shorts at +-2 steps, wings 4 steps beyond
    assert st.legs == (("C", 25100, -1), ("P", 24900, -1), ("C", 25300, 1), ("P", 24700, 1))


def test_setup_caps_at_ten_steps_and_skips_missing_legs():
    D = flat_day(call=400, put=400)                  # S = 800 -> 16 steps, capped at 10
    st = E.setup(D, "NIFTY", "IF")
    assert st.legs[2][1] - st.k0 == 10 * 50
    st = E.setup(D, "NIFTY", "IC")                   # a = 8 steps, w capped at 2
    assert st.legs[0][1] == 25400 and st.legs[2][1] == 25500
    del D.con[("C", 25500.0)]
    assert E.setup(D, "NIFTY", "IC") is None
    D2 = flat_day()
    D2.con[("C", 25000.0)][3, E.DECIDE_ROW] = np.nan
    assert E.setup(D2, "NIFTY", "IF") is None


def test_fill_next_open_and_lookahead():
    c = con(np.r_[np.full(6, 100.0), np.nan, np.nan, np.full(N - 8, 90.0)])
    assert E.fill_px(c, 5) == (100.0, 5)
    assert E.fill_px(c, 6) == (90.0, 8)              # no print at 6 and 7: the next open
    assert E.last_close(c, 7) == 100.0
    assert E.vol5_lots(c, 5, 50) == pytest.approx(500 / 50)


def test_slippage_and_charges():
    assert E.slip(100.0, True, 25) == round(100 * 1.0005 + 0.05, 2)
    assert E.slip(100.0, False, 5) == round(100 * 0.9995 - 0.10, 2)
    assert E.slip(100.0, False, 0, mult=2) == round(100 * 0.9995 - 0.40, 2)
    assert E.slip(0.1, False, 0) == 0.0
    sell = E.charges(100.0, 65, False, date(2026, 5, 4))
    buy = E.charges(100.0, 65, True, date(2026, 5, 4))
    assert sell > buy > 20                            # STT 0.15% on the sell side only; brokerage 20 each
    assert E.charges(100.0, 65, False, date(2024, 5, 4)) < sell     # STT was 0.0625% then


def test_flat_day_runs_to_time_and_loses_only_costs():
    D = flat_day()
    t = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X1", False)
    assert t["why"] == "time" and t["xr"] == E.SQ_ROW and t["er"] == E.DECIDE_ROW + 1
    assert t["credit"] > 0 and t["gross"] < 0 and t["net"] < t["gross"]     # slippage and charges only
    assert t["max_loss"] == pytest.approx(200 * 65 - t["credit"], abs=0.01)
    te = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X1", True)
    assert te["xr"] == E.SQ_ROW_EXPIRY


def test_stop_path_exits_next_minute():
    D = flat_day()
    # from minute 50 the call side jumps: the short ATM call +150, the long wing +60 -> loss well past 50% of credit
    for k, add in ((25000.0, 150.0), (25200.0, 60.0)):
        c = D.con[("C", k)]
        c[0:4, 50:] += add
    t = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X1", False)
    assert t["why"] == "stop" and t["xr"] == 51 and t["net"] < -0.5 * t["credit"] * 0.9


def test_target_and_lock_paths():
    D = flat_day()
    for k in (25000.0,):                             # both short legs decay 40% from minute 40: profit > 60% of credit?
        for side in ("C", "P"):
            D.con[(side, k)][0:4, 40:] *= 0.4
    t2 = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X2", False)
    t1 = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X1", False)
    assert t2["why"] == "target" and t2["xr"] == 41 and t1["why"] == "time"
    D = flat_day()
    for side in ("C", "P"):                          # credit ~79 points: profit ~36 (45%), then back to ~8 (10%)
        c = D.con[(side, 25000.0)]
        c[0:4, 30:60] = 82.0
        c[0:4, 60:] = 96.0
    t = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X2", False)
    assert t["why"] == "lock" and t["xr"] == 61


def test_lookahead_guard():
    """Changing prices after the exit decision minute never changes the decision or the exit minute."""
    D = flat_day()
    for k, add in ((25000.0, 150.0), (25200.0, 60.0)):
        D.con[("C", k)][0:4, 50:] += add
    base = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X1", False)
    for c in D.con.values():
        c[0:4, base["xr"] + 1:] *= 3.0
    after = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X1", False)
    assert after["why"] == base["why"] and after["xr"] == base["xr"] and after["legs"] == base["legs"]
    D2 = flat_day()
    st = E.setup(D2, "NIFTY", "IF")
    for c in D2.con.values():
        c[:, E.DECIDE_ROW + 1:] *= 5.0                # nothing after the decision minute moves the strikes
    assert E.setup(D2, "NIFTY", "IF").legs == st.legs


def test_bh_boot_summary():
    assert S.bh([0.01, 0.04, 0.03, 0.5]) == pytest.approx([0.04, 0.04 * 4 / 3, 0.04 * 4 / 3, 0.5])
    rng = np.random.default_rng(0)
    assert S.boot_p(np.full(100, 10.0) + rng.normal(0, 1, 100), rng) == 0.0
    assert S.boot_p(np.full(100, -10.0), rng) == 1.0
    tr = [dict(day=date(2025, 1, i + 1), net=v, credit=100, max_loss=200, why="time")
          for i, v in enumerate([100, -50, 200, -20000, 300, 50])]
    s = S.summary(tr)
    assert s["n"] == 6 and s["win"] == round(4 / 6, 3) and s["worst_month"] == sum(t["net"] for t in tr)
    assert s["months_below_stop"] == 1 and s["max_dd"] == 20000 and s["ex_best5"] == -20000
    g = S.gates(dict(s, boot_p=0.01), dict(net=1.0), 0.05)
    assert not g["pass"] and not g["6_account_stop"]


def test_holdout_lock(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "HOLDOUT_FLAG", tmp_path / "HOLDOUT_READ")
    with pytest.raises(PermissionError):
        S.trades_for("holdout")
    (tmp_path / "HOLDOUT_READ").write_text("x")
    monkeypatch.setattr(S, "RESULTS", tmp_path)
    with pytest.raises(SystemExit):
        S.holdout()
    assert S.period_of(date(2026, 7, 14)) == "design" and S.period_of(date(2026, 7, 15)) == "holdout"
    assert S.period_of(date(2026, 10, 20)) == "after"
    assert len(S.TESTS) == 24 and len({S.test_id(*t) for t in S.TESTS}) == 24


# ---- Amendment 1 (22 Oct 2026): the BUY mirror, the margin estimate, the one-day tail count -------------------------

def test_mirror_is_the_buy_side_with_the_exits_on_the_debit():
    D = flat_day()
    sell = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X1", False)
    buy = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X1", False, mirror=True)
    assert buy["mirror"] and not sell["mirror"]
    assert [c for _, _, c in buy["legs"]] == [-c for _, _, c in sell["legs"]]
    assert buy["credit"] < 0 < sell["credit"] and buy["max_loss"] == pytest.approx(-buy["credit"])
    assert buy["margin"] == pytest.approx(-buy["credit"])                   # a buyer's margin is the debit paid
    assert buy["why"] == "time" and buy["net"] < 0 and sell["net"] < 0        # a flat day costs both sides
    # the seller's stop day is the buyer's winning day: the call side jumps from minute 50
    for k, add in ((25000.0, 150.0), (25200.0, 60.0)):
        D.con[("C", k)][0:4, 50:] += add
    sell = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X2", False)
    buy = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X2", False, mirror=True)
    assert sell["why"] == "stop" and buy["why"] == "target" and buy["xr"] == 51 and buy["net"] > 0
    # look-ahead: later prices never change the mirror's exit minute either
    for c in D.con.values():
        c[0:4, buy["xr"] + 1:] *= 3.0
    again = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X2", False, mirror=True)
    assert again["why"] == buy["why"] and again["xr"] == buy["xr"]


def test_margin_estimate():
    assert E.margin_est(25000.0, 65, date(2026, 5, 4), False) == pytest.approx(0.025 * 2 * 25000 * 65)
    assert E.margin_est(25000.0, 65, date(2026, 5, 5), True) == pytest.approx(0.045 * 2 * 25000 * 65)
    assert E.margin_est(45000.0, 15, date(2024, 5, 8), True) == pytest.approx(0.025 * 2 * 45000 * 15)   # before the ELM
    D = flat_day()
    t = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X1", True)
    assert t["margin"] == E.margin_est(25000.0, 65, date(2026, 5, 4), True)


def test_summary_tail_and_return_on_margin():
    tr = [dict(day=date(2025, 1, i + 1), net=v, credit=100, max_loss=200, why="time", margin=50000.0)
          for i, v in enumerate([100, -13000, 200, -20000, 300, 50])]
    s = S.summary(tr)
    assert s["days_below_stop"] == 2 and s["mean_margin"] == 50000
    assert s["rom"] == pytest.approx(sum(t["net"] for t in tr) / 50000, abs=1e-4)
    assert s["rom_per_year"] == pytest.approx(s["rom"] / (5 / 365.25), rel=1e-3)
    assert S.summary([])["n"] == 0


def test_tail_straddle_loss_on_a_hand_made_day(tmp_path, monkeypatch):
    import r103tail as T
    D = flat_day()
    r = T.straddle_loss(D, "NIFTY", date(2026, 5, 4), False)
    assert r["straddle_pts"] == 200 and r["lot"] == 65 and r["move_pct"] == 0.0
    assert r["straddle_net"] < 0 and r["if_net"] < 0 and r["if_net"] > r["straddle_net"] - 2000   # costs only on a flat day
    for side in ("C", "P"):                          # the ATM legs double at minute 100: the naked straddle loses ~2 x 100 x 65
        D.con[(side, 25000.0)][0:4, 100:] *= 2.0
    r2 = T.straddle_loss(D, "NIFTY", date(2026, 5, 4), False)
    assert -14500 < r2["straddle_net"] < -12500 and r2["if_net"] == pytest.approx(r2["straddle_net"] + (r["if_net"] - r["straddle_net"]), abs=1)
    monkeypatch.setattr(S, "HOLDOUT_FLAG", tmp_path / "HOLDOUT_READ")
    with pytest.raises(PermissionError):
        T.write()


# ---- Amendment 2 (22 Oct 2026): the stale-leg check -------------------------------------------------------------------

def test_bs_and_implied_vol_round_trip():
    import r103stale as ST
    p = ST.bs(25000.0, 25200.0, 5 / 365, 0.15, True)
    assert 90 < p < 110                                         # the illustration in DEEP_OPTIONS_SELLING.md: about 101
    assert ST.implied_vol(p, 25000.0, 25200.0, 5 / 365, True) == pytest.approx(0.15, abs=1e-4)
    assert ST.implied_vol(0.01, 25000.0, 24000.0, 5 / 365, True) is None     # below intrinsic
    assert ST.bs(25000.0, 24800.0, 0.0, 0.2, False) == 0.0
    assert ST.years_left(date(2026, 5, 4), date(2026, 5, 5), 0) == pytest.approx((1440 + 375) / (365 * 1440))


def test_reprice_only_touches_stale_legs_and_lowers_a_frozen_long_wing():
    import r103stale as ST
    D = flat_day()
    t = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X1", False)
    net, fixed, fail = ST.reprice(t, D, date(2026, 5, 5))
    assert (net, fixed, fail) == (t["net"], 0, 0)              # every leg printed at exit: unchanged
    # the long call wing stops printing at minute 100 and the index has fallen 300 points by the exit: its frozen price
    # (100 + 4 x 50 points over the strike... flat_day gives it 200 - 4 x 10 = 60) is too high; repriced it is lower
    w = D.con[("C", 25200.0)]
    w[:, 100:] = np.nan
    D.idx[3, 100:] = 24700.0
    t2 = E.run(D, "NIFTY", date(2026, 5, 4), "IF", "X1", False)
    net2, fixed2, fail2 = ST.reprice(t2, D, date(2026, 5, 5))
    assert fixed2 == 1 and fail2 == 0 and net2 < t2["net"]
    assert ST.reprice(t2, D, None)[2] == 1                       # no expiry date: counted, not repriced


def test_stale_holdout_lock(tmp_path, monkeypatch):
    import r103stale as ST
    monkeypatch.setattr(S, "HOLDOUT_FLAG", tmp_path / "HOLDOUT_READ")
    with pytest.raises(PermissionError):
        ST.run("holdout")
