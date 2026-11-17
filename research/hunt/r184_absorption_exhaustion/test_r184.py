"""R184 tests: references, the two setups, the look-ahead guard, the exits, trade-taking, selection and statistics.
Synthetic data only. python -m pytest research/hunt/r184_absorption_exhaustion -q. 16 Nov 2026."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parents[2])]
import r184lib as L  # noqa: E402

R1 = L.R1
N = L.N
D0 = date(2026, 11, 5)
T0 = R1.day_epoch_0915(D0)


def bars(c, d, rows=None, h=None, l=None):
    c = np.asarray(c, float)
    h = c + 1.0 if h is None else np.asarray(h, float)
    l = c - 1.0 if l is None else np.asarray(l, float)
    rows = np.full(N, 60.0) if rows is None else np.asarray(rows, float)
    return R1.Bars(c.copy(), h, l, c, np.abs(d) + 10, np.asarray(d, float), rows)


def flat_day(level=24000.0, dsd=100.0, seed=0):
    rng = np.random.default_rng(seed)
    d = rng.normal(0, dsd, N)
    c = level + np.cumsum(rng.normal(0, 2, N))
    return c, d


def random_bars(seed):
    rng = np.random.default_rng(seed)
    c = 24000 + np.cumsum(rng.normal(0, 6, N))
    h = c + rng.uniform(0, 8, N)
    l = c - rng.uniform(0, 8, N)
    d = rng.standard_t(2, N) * 300
    rows = np.where(rng.uniform(size=N) < 0.05, 5, 60)
    return bars(c, d, rows, h, l)


# ------------------------------------------------------------------------------------------------ references
def test_ref1_reads_only_earlier_bars():
    d = np.arange(N, dtype=float)
    thin = np.zeros(N, bool)
    r = L.ref1(d, thin)
    assert np.isnan(r[19]) and r[20] == pytest.approx(np.mean(np.arange(20)))
    d2 = d.copy()
    d2[100] = 1e9
    r2 = L.ref1(d2, thin)
    assert np.array_equal(r[:101], r2[:101], equal_nan=True) and r2[101] > r[101]


def test_ref1_skips_thin_bars():
    d = np.ones(N)
    thin = np.zeros(N, bool)
    thin[:10] = True
    d[:10] = 1e6
    r = L.ref1(d, thin)
    assert np.isnan(r[29]) and r[30] == pytest.approx(1.0)


def test_window_sums_and_ref_w():
    d = np.ones(N)
    thin = np.zeros(N, bool)
    thin[50] = True
    S = L.window_sums(d, thin, 3)
    assert np.isnan(S[1]) and S[2] == 3 and np.isnan(S[50]) and np.isnan(S[52]) and S[53] == 3
    R = L.ref_w(S)
    assert np.isnan(R[21]) and R[22] == pytest.approx(3.0)
    S2 = S.copy()
    S2[200] = 999
    assert np.array_equal(L.ref_w(S2)[:201], R[:201], equal_nan=True)


# ------------------------------------------------------------------------------------------------ absorption
def absorption_case(side="L", hold=True):
    c, d = flat_day()
    c[:] = 24000.0
    h, l = c + 2, c - 2
    sg = -1 if side == "L" else 1
    d[100:103] = sg * 5000            # heavy selling (L) / buying (S) in bars 100..102
    if not hold:
        if side == "L":
            l[101] = 24000 - 30           # broke more than 6 bp (14.4 points) below
        else:
            h[101] = 24000 + 30
    return bars(c, d, None, h, l)


@pytest.mark.parametrize("side", ["L", "S"])
def test_absorption_fires_when_price_holds(side):
    s = L.absorption(absorption_case(side), 3, 2, 6, "none")
    hit = [x for x in s if x.r == 102]
    assert len(hit) == 1 and hit[0].side == side
    band = 6e-4 * 24000
    exp = (24000 - 2 - band) if side == "L" else (24000 + 2 + band)
    assert hit[0].stop == pytest.approx(exp)


@pytest.mark.parametrize("side", ["L", "S"])
def test_absorption_needs_the_hold(side):
    s = L.absorption(absorption_case(side, hold=False), 3, 2, 6, "none")
    assert not any(x.r == 102 for x in s)


def test_absorption_turn_waits_for_the_next_close():
    B = absorption_case("L")
    assert not any(x.r in (102, 103) for x in L.absorption(B, 3, 2, 6, "turn"))   # bar 103 closes flat
    B.c[103] = 24001.0
    s = L.absorption(B, 3, 2, 6, "turn")
    assert any(x.r == 103 and x.side == "L" for x in s)


def test_absorption_thin_bar_blocks_and_no_signal_before_0940():
    B = absorption_case("L")
    B.rows[101] = 5
    B.derive()
    assert not any(x.r == 102 for x in L.absorption(B, 3, 2, 6, "none"))
    c, d = flat_day()
    d[20:23] = -9000
    assert not any(x.r < L.FIRST_SIG for x in L.absorption(bars(c, d), 3, 2, 6, "none"))


# ------------------------------------------------------------------------------------------------ exhaustion
def exhaustion_case(side="L"):
    c = np.full(N, 24000.0)
    rng = np.random.default_rng(3)
    d = rng.normal(0, 100, N)
    h, l = c + 2, c - 2
    sg = 1 if side == "L" else -1
    # a push to a new low (L) / high (S) at bar 150 with a spike of selling / buying
    c[150] = 24000 - sg * 20
    l[150] = c[150] - 2 if side == "L" else c[150] - 1
    h[150] = c[150] + 1 if side == "L" else c[150] + 2
    d[150] = -sg * 3000
    # bar 151: still some selling but under half; bar 152: delta flips and the close is beyond the climax close
    c[151], h[151], l[151], d[151] = c[150], c[150] + 1, c[150] - 1, -sg * 500
    c[152], h[152], l[152], d[152] = c[150] + sg * 3, c[150] + sg * 3 + 1, c[150] + sg * 3 - 1, sg * 400
    return bars(c, d, None, h, l)


@pytest.mark.parametrize("side", ["L", "S"])
def test_exhaustion_fires_on_climax_dryup_flip(side):
    for conf in ("flip", "flip+close"):
        s = L.exhaustion(exhaustion_case(side), 15, 2, 6, conf)
        hit = [x for x in s if x.r == 152]
        assert len(hit) == 1 and hit[0].side == side, conf


def test_exhaustion_dies_on_a_new_low_or_more_selling():
    B = exhaustion_case("L")
    B.l[151] = B.l[150] - 40
    B.d[151] = -150                   # (kept below the spike size, so bar 151 is not a climax of its own)
    assert not any(x.r == 152 for x in L.exhaustion(B, 15, 2, 6, "flip"))
    B = exhaustion_case("L")
    B.d[151] = -2000                  # more than half the climax's selling
    assert not any(x.r == 152 for x in L.exhaustion(B, 15, 2, 6, "flip"))


def test_exhaustion_flip_close_needs_close_beyond_climax():
    B = exhaustion_case("L")
    B.c[152] = B.c[150] - 1
    assert any(x.r == 152 for x in L.exhaustion(B, 15, 2, 6, "flip"))
    assert not any(x.r == 152 for x in L.exhaustion(B, 15, 2, 6, "flip+close"))


def test_exhaustion_needs_a_new_extreme_and_a_spike():
    B = exhaustion_case("L")
    B.l[140] = B.l[150] - 5           # an earlier lower low in the lookback: not a new 15-minute low
    assert not any(x.r == 152 for x in L.exhaustion(B, 15, 2, 6, "flip"))
    B = exhaustion_case("L")
    B.d[150] = -150                   # no spike
    assert not any(x.r == 152 for x in L.exhaustion(B, 15, 2, 6, "flip"))


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("seed", [1, 2, 3])
def test_lookahead_guard_all_variants(seed):
    """A signal at bar r must not change when every bar after r is replaced by random values."""
    B = random_bars(seed)
    rng = np.random.default_rng(seed + 100)
    seen = 0
    for setup in ("ABS", "EXH"):
        for v in L.GRID[setup]:
            if v["T"] != 15:
                continue
            full = L.signals(B, v)
            for t in (120, 200, 300):
                P = random_bars(seed)
                k = slice(t + 1, N)
                P.c[k] = 24000 + rng.normal(0, 50, N - t - 1)
                P.h[k] = P.c[k] + 5
                P.l[k] = P.c[k] - 5
                P.d[k] = rng.normal(0, 5000, N - t - 1)
                P.rows[k] = rng.choice([5, 60], N - t - 1)
                P.derive()
                a = [(x.r, x.side, x.stop) for x in full if x.r <= t]
                b = [(x.r, x.side, x.stop) for x in L.signals(P, v) if x.r <= t]
                assert a == b, (L.vid(v), t)
                seen += len(a)
    assert seen > 0


# ------------------------------------------------------------------------------------------------ exits
def series(secs, prices, vol=100.0):
    secs = np.asarray(secs, np.int64)
    p = np.asarray(prices, float)
    return R1.Series(secs, p, np.full(len(p), vol), p - 0.5, p + 0.5)


def fut_flat(level=24000.0, start=T0, n=7 * 3600):
    s = np.arange(start, start + n, dtype=np.int64)
    return (s, np.full(n, level + 1.0), np.full(n, level - 1.0))


E = T0 + 3600


def test_premium_stop_and_entry_second_ignored():
    secs = np.arange(E - 5, E + 1200)
    p = np.full(len(secs), 100.0)
    p[secs == E] = 50.0              # a print AT the entry second does not trigger (checked from the next second)
    p[secs >= E + 300] = 84.0
    o = series(secs, p)
    s, why = L.exit_path(o, fut_flat(), E, E + 900, "L", 23000.0, 100.0, 101.0)
    assert (s, why) == (E + 300, "prem_stop")


def test_breakeven_after_lock():
    secs = np.arange(E, E + 1200)
    p = np.full(len(secs), 100.0)
    p[(secs >= E + 100) & (secs < E + 200)] = 111.0
    p[secs >= E + 200] = 100.5
    o = series(secs, p)
    s, why = L.exit_path(o, fut_flat(), E, E + 900, "L", 23000.0, 100.0, 101.0)
    assert (s, why) == (E + 200, "breakeven")
    # never locked: a dip to 100.5 is not an exit
    p2 = np.full(len(secs), 100.5)
    s, why = L.exit_path(series(secs, p2), fut_flat(), E, E + 900, "L", 23000.0, 100.0, 101.0)
    assert (s, why) == (E + 900, "time")


@pytest.mark.parametrize("side", ["L", "S"])
def test_index_stop(side):
    secs = np.arange(E, E + 1200)
    o = series(secs, np.full(len(secs), 100.0))
    f = fut_flat()
    k = np.searchsorted(f[0], E + 77)
    lo, hi = f[2].copy(), f[1].copy()
    if side == "L":
        lo[k] = 23980
    else:
        hi[k] = 24020
    stop = 23990 if side == "L" else 24010
    s, why = L.exit_path(o, (f[0], hi, lo), E, E + 900, side, stop, 100.0, 101.0)
    assert (s, why) == (E + 77, "index_stop")


def test_trade_prices_and_reasons():
    secs = np.arange(E - 10, E + 4000)
    o = series(secs, np.full(len(secs), 100.0))
    r = L.trade(o, fut_flat(), E, E + 600, "L", 23000.0, 15, 65, D0)
    assert r["why"] == "day_end" and r["exit_s"] == E + 600 and r["net"] < 0      # flat price: charges + slippage
    r = L.trade(o, fut_flat(), E, E + 7200, "L", 23000.0, 15, 65, D0)
    assert r["why"] == "time" and r["hold_s"] == 900
    assert r["net2"] < r["net"] and r["net_ba"] is not None
    assert L.trade(series([E - 500], [100.0]), fut_flat(), E, E + 600, "L", 23000.0, 15, 65, D0) is None   # stale


def make_U(sigbars=(), level=24000.0):
    c = np.full(N, level)
    B = bars(c, np.zeros(N))
    spot = np.full(N, level - 50)
    secs = np.arange(T0, T0 + N * 60)
    opts = {("CE", 23950.0): series(secs, np.full(len(secs), 100.0)),
            ("PE", 23950.0): series(secs, np.full(len(secs), 100.0))}
    return dict(bars=B, fut=fut_flat(level), spot=spot, opts=opts)


def test_take_day_one_at_a_time_and_cap():
    U = make_U()
    sigs = [L.Sig(r, "L", 23000.0, 24000.0) for r in (30, 35, 50, 70, 90, 110, 130)]
    tr, up = L.take_day(sigs, U, T0, T0 + 6 * 3600, "NIFTY", D0, 15)
    assert [t["r"] for t in tr] == [30, 50, 70, 90]          # 35 blocked (in the trade), cap 4
    assert all(t["strike"] == 23950.0 for t in tr) and up == {}


def test_take_day_unpriced_reasons():
    U = make_U()
    U["spot"][40] = np.nan
    U["opts"].pop(("PE", 23950.0))
    sigs = [L.Sig(40, "L", 23000.0, 24000.0), L.Sig(60, "S", 25000.0, 24000.0)]
    tr, up = L.take_day(sigs, U, T0, T0 + 6 * 3600, "NIFTY", D0, 15)
    assert tr == [] and up == {"no_spot": 1, "no_strike": 1}


def test_twin_trade_same_distance():
    U = make_U()
    r = L.twin_trade(U, T0, T0 + 6 * 3600, "NIFTY", D0, 100, "S", 20.0, 30)
    assert r is not None and r["why"] == "time"
    U["bars"].c[100] = np.nan
    assert L.twin_trade(U, T0, T0 + 6 * 3600, "NIFTY", D0, 100, "S", 20.0, 30) is None


# ------------------------------------------------------------------------------------------------ selection and stats
def test_grids_and_ids():
    assert len(L.ABS_GRID) == 32 and len(L.EXH_GRID) == 32
    assert len({L.vid(v) for v in L.ABS_GRID + L.EXH_GRID}) == 64


def test_select_rule():
    rows = [dict(i=0, n=11, net=9e9), dict(i=1, n=12, net=100), dict(i=2, n=20, net=100), dict(i=3, n=30, net=50)]
    assert L.select(rows)["i"] == 2
    rows = [dict(i=0, n=5, net=-1), dict(i=1, n=5, net=3), dict(i=2, n=2, net=99)]
    assert L.select(rows)["i"] == 1


def test_summary_twins_zero_power():
    assert L.summary([])["n"] == 0
    tr = [dict(net=100, net2=90, net_ba=80, day="a", side="L", why="time"),
          dict(net=-50, net2=-60, net_ba=None, day="b", side="S", why="prem_stop")]
    s = L.summary(tr)
    assert s["n"] == 2 and s["win"] == 0.5 and s["worst_day"] == -50 and s["n_ba"] == 1
    rng = np.random.default_rng(0)
    assert L.twin_test([1, 2], [[0], [0]], rng)["p"] == 1.0
    t = L.twin_test([100, 100, 100], [[-10, -20], [-5], [0, 1]], rng, draws=200)
    assert t["p"] == pytest.approx(1 / 201)
    t = L.twin_test([-100, -100, -100], [[10], [5], []], rng, draws=200)
    assert t["p"] == 1.0
    assert L.zero_test([10, 20, 30], rng, draws=500) < 0.01
    assert L.zero_test([-10, -20, 0], rng, draws=500) > 0.99
    assert L.zero_test([10, 20], rng, n_trades=2) == 1.0
    assert L.power(None, 5)["mde_5pct"] is None
    assert L.power(1000, 100)["mde_5pct"] == pytest.approx(249)


def test_bh_prior():
    q, qi = L.bh_with_prior([0.001, 0.5], 4512)
    assert q[0] == pytest.approx(min(1, 0.001 * 4512)) and qi[0] == pytest.approx(0.002)


def test_holdout_refuses_without_flag(tmp_path):
    import r184
    with pytest.raises(SystemExit):
        r184.stage_holdout("/nonexistent", None, None, tmp_path)
