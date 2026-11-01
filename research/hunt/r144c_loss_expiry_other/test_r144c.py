"""R144c tests (synthetic data only; no lake needed).

    python -m pytest research/hunt/r144c_loss_expiry_other -q
"""
from __future__ import annotations

import math
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r98"))
import lake as LK  # noqa: E402
import features as FE  # noqa: E402
import study as ST  # noqa: E402
import flow_port as FP  # noqa: E402

N = LK.N


def opt(seed=0):
    r = np.random.default_rng(seed)
    a = np.full((N, 7), np.nan)
    c = 10 + np.cumsum(r.normal(0, 0.2, N))
    a[:, LK.O], a[:, LK.C] = c - 0.05, c
    a[:, LK.H], a[:, LK.L] = c + 0.3, c - 0.3
    a[:, LK.V] = r.integers(0, 2000, N)
    a[:, LK.OI] = 1e5 + np.cumsum(r.integers(-500, 800, N))
    a[:, LK.IV] = 15 + r.normal(0, 0.5, N)
    return a


def daily_frame(d0, n=30):
    days = [d0 - timedelta(days=n - i) for i in range(n)]
    return pd.DataFrame(dict(open=100.0, high=np.linspace(101, 103, n), low=99.0, close=100.5), index=days)


# ---------------------------------------------------------------------------------------- look-ahead guard
@pytest.mark.parametrize("dec", [5, 60, 200, 373])
def test_contract_feats_never_read_after_decision(dec):
    a = opt(1)
    f0 = FE.contract_feats(a, dec)
    b = a.copy()
    b[dec + 1:] = 9e9                          # the fill row and everything after it
    f1 = FE.contract_feats(b, dec)
    assert all((f0[k] == f1[k]) or (f0[k] != f0[k] and f1[k] != f1[k]) for k in f0)


def test_chain_and_index_feats_never_read_after_decision():
    d = date(2025, 1, 9)
    dec = 120
    ch = {"spot": np.full(N, 23000.0)}
    for k in (22900.0, 22950.0, 23000.0, 23050.0, 23100.0):
        for s in "CP":
            ch[(k, s)] = opt(int(k) + ord(s))
    ix = np.full((N, 4), 23000.0) + np.arange(N)[:, None]
    dl = daily_frame(d)
    f0 = {**FE.chain_feats(ch, "NIFTY", 23000.0, dec, "C", 23050.0, 14.0, 300.0),
          **FE.index_feats(ix, dec, 1, dl, d)}
    ch2 = {k: (v.copy() if k == "spot" else v.copy()) for k, v in ch.items()}
    for k, v in ch2.items():
        if k != "spot":
            v[dec + 1:] = 1e9
    ix2 = ix.copy()
    ix2[dec + 1:] = 1e9
    dl2 = pd.concat([dl, pd.DataFrame(dict(open=1e9, high=1e9, low=0.0, close=1e9), index=[d, d + timedelta(days=1)])])
    f1 = {**FE.chain_feats(ch2, "NIFTY", 23000.0, dec, "C", 23050.0, 14.0, 300.0),
          **FE.index_feats(ix2, dec, 1, dl2, d)}
    for k in f0:
        assert (f0[k] == f1[k]) or (f0[k] != f0[k] and f1[k] != f1[k]), k


def test_adr_uses_only_earlier_days():
    d = date(2025, 3, 3)
    dl = daily_frame(d)
    a = FE.adr20(dl, d)
    dl2 = pd.concat([dl, pd.DataFrame(dict(open=0.0, high=1e6, low=0.0, close=0.0), index=[d])])
    assert FE.adr20(dl2, d) == a


def test_quadrant_and_vol_delta():
    a = np.full((N, 7), np.nan)
    a[:, LK.O] = np.linspace(10, 20, N) - 0.1
    a[:, LK.C] = np.linspace(10, 20, N)
    a[:, LK.H], a[:, LK.L] = a[:, LK.C] + 0.1, a[:, LK.O] - 0.1
    a[:, LK.V] = 100
    a[:, LK.OI] = np.linspace(1e5, 2e5, N)
    f = FE.contract_feats(a, 100)
    assert f["quadrant"] == "long build-up" and f["vol_delta"] == pytest.approx(1.0)
    a[:, LK.OI] = np.linspace(2e5, 1e5, N)
    assert FE.contract_feats(a, 100)["quadrant"] == "short covering"


def test_period_boundaries():
    assert LK.period(date(2022, 11, 30)) == "early"
    assert LK.period(date(2022, 12, 1)) == "unseen"
    assert LK.period(date(2024, 11, 19)) == "unseen"
    assert LK.period(date(2024, 11, 20)) == "design"
    assert LK.period(date(2026, 3, 31)) == "design"
    assert LK.period(date(2026, 4, 1)) == "final"


def test_expiry_for_monthly_and_weekly():
    cal = [date(2024, 1, 4), date(2024, 1, 11), date(2024, 1, 25), date(2024, 2, 1)]
    assert FE.expiry_for(cal, date(2024, 1, 5), False) == date(2024, 1, 11)
    assert FE.expiry_for(cal, date(2024, 1, 5), True) == date(2024, 1, 25)
    assert FE.expiry_for(cal, date(2024, 2, 2), True) is None


def test_strike_1itm():
    assert FE.strike_1itm(23012, "NIFTY", "C") == 22950.0
    assert FE.strike_1itm(23012, "NIFTY", "P") == 23050.0
    assert FE.strike_1itm(23025, "NIFTY", "C") == 22950.0      # half to even: 460.5 -> 460


# ---------------------------------------------------------------------------------------- Flow port
def test_java_random_matches_java():
    assert FP.java_next_ints(0, 100, 1) == [60]                # new java.util.Random(0).nextInt(100) == 60


def test_numba_mc_uses_the_same_draws():
    rets = np.array([0.01, -0.01, 0.002, -0.003, 0.0], float)
    draws = FP.java_next_ints(19, len(rets), 200)
    price, out, i = 100.0, 0, 0
    for i, r in enumerate(draws[:50]):
        price *= math.exp(rets[r])
        if price >= 101.0:
            out = 1
            break
        if price <= 99.0:
            out = -1
            break
    tf, sf, nn = FP._mc(rets, 100.0, 99.0, 101.0, True, 50, 1, 19)
    assert (tf, sf, nn) == ((1, 0, 0) if out > 0 else (0, 1, 0) if out < 0 else (0, 0, 1))


def bars_from(closes, start=0):
    return [(start + 5 * i, c, c + 1, c - 1, c) for i, c in enumerate(closes)]


def test_flow_scan_first_break_and_plan():
    b = bars_from([100, 101, 100, 99, 97, 95, 96])
    s = FP.scan(b, [], math.nan)
    assert s.setup == "ORB_DOWN" and s.brk == 20 and s.entry == 97
    assert s.stop == pytest.approx(100.5) and s.target == pytest.approx(93.5)      # range 99..102 (wicks), risk 3.5


def test_flow_lite_score_by_hand():
    b = bars_from([100, 101, 100, 99, 97])
    s = FP.scan(b, [], math.nan)
    # range 99..102: 25 + strength int(5 + (99-97)/3*20)=18 + no 20DMA 10 + no RSI 8 + width 3.1% (> 1%) 7 + early 8
    assert FP.lite_score(s) == 25 + 18 + 10 + 8 + 7 + 8


def test_flow_walk_to_close():
    past = FP.scan(bars_from([100, 101, 100, 99, 97, 96, 93]), [], math.nan)
    assert FP.walk_to_close(bars_from([100, 101, 100, 99, 97, 96, 93]), past) == 1


# ---------------------------------------------------------------------------------------- Hero port
def test_hero_scan_fires_on_expansion_and_momentum():
    import hero_port as HP

    class D:
        pass
    d = D()
    d.ix = np.full((N, 4), 20000.0)
    d.opts = {}
    for k in (19950.0, 20000.0, 20050.0, 20100.0):
        for s in "CP":
            a = np.full((N, 7), np.nan)
            a[:, LK.C] = 20.0
            a[:, LK.V] = 10
            d.opts[(k, s)] = a
    r = HP.FIRST_ROW + 10
    d.ix[r:, 3] = 20000 * 1.004                      # +0.4% in 15 minutes
    d.opts[(20100.0, "C")][r:, LK.C] = 40.0
    d.opts[(20100.0, "P")][r:, LK.C] = 10.0
    sg = HP.scan(d)
    assert sg == (r, 1)


def test_hero_fill_cap_and_walk_targets():
    import hero_port as HP
    a = np.full((N, 7), np.nan)
    a[:, LK.O] = a[:, LK.C] = a[:, LK.H] = 2.0
    e, kb = HP.fill(a, 100, 2.0)
    assert kb == 101 and e == pytest.approx(2.05)
    a[150, LK.H] = 11.0                              # 5x of 2.05 = 10.25 -> floorTick 10.25; a tick through: 10.30
    legs, xr, kind, _ = HP.walk(a, kb, e)
    assert legs[0] == (0.5, pytest.approx(10.30)) and kind == "half+15:05"


# ---------------------------------------------------------------------------------------- study
def T(day, net, f=None, row=100):
    return dict(day=day, net=net, f=f or {}, entry_row=row, period=LK.period(day))


def test_stats_loss_sum_and_drawdown():
    tr = [T(date(2025, 1, 1), -100), T(date(2025, 1, 1), 50), T(date(2025, 1, 2), 200), T(date(2025, 1, 3), -300)]
    s = ST.stats(tr)
    assert s["loss_sum"] == -350 and s["max_dd"] == 300 and s["worst_day"] == -300 and s["win"] == 0.5


def test_bh():
    q = ST.bh([0.01, 0.04, 0.03])
    assert q == pytest.approx([0.03, 0.04, 0.04])


def test_choose_reads_design_only():
    rng = np.random.default_rng(3)
    tr = []
    d0 = date(2024, 12, 1)
    for i in range(200):
        x = float(rng.normal())
        tr.append(dict(day=d0 + timedelta(days=i * 2), net=(-500 if x < -0.8 else 300) + rng.normal(0, 50), entry_row=100,
                       f={"trend": x}, period=None))
    for t in tr:
        t["period"] = LK.period(t["day"])
    fin = [dict(t, day=t["day"] + timedelta(days=700), net=-t["net"]) for t in tr]
    for t in fin:
        t["period"] = LK.period(t["day"])
    a = ST.choose("X", tr + fin)
    for t in fin:
        t["net"] = 1e6
    b = ST.choose("X", tr + fin)
    assert a["chosen"] == b["chosen"]
    assert any(c["feature"] == "trend" and c["kind"] == "low" for c in a["chosen"])


def test_drops_unknown_never_skips():
    assert not ST.drops(dict(feature="trend", kind="low", cut=0.0), T(date(2025, 1, 1), 1, {"trend": math.nan}))
    assert ST.drops(dict(feature="trend", kind="low", cut=0.0), T(date(2025, 1, 1), 1, {"trend": -1.0}))
    assert ST.drops(dict(feature="quadrant", kind="cat", value="long build-up"),
                    T(date(2025, 1, 1), 1, {"quadrant": "long build-up"}))


def test_gates_fail_when_arm_loses():
    b = {p: dict(trades=50, loss_sum=-1000, max_dd=500, worst_day=-300, worst_month=-600, net=-200) for p in ST.TESTS}
    f = {p: dict(trades=40, loss_sum=-800, max_dd=400, worst_day=-200, worst_month=-500, net=-50) for p in ST.TESTS}
    fails = ST.gates(f, b, 0.01, 0.05, 100, 40)
    assert any("does not make money" in x for x in fails) and len(fails) == 2
