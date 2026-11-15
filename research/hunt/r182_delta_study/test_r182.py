"""Tests for R182 (PREREG.md): minute bars and delta features, absorption, forward returns, the statistics (ranks,
day bootstrap, circular-shift null, BH), the trades rebuilt from paper orders (partial sells, the 15:15 square-off, other
arms, unclosed and orphan sells, bad symbols), dated charges, the entry-second features and labels, the random-skip
twins, and a look-ahead guard (data at or after the signal changed or deleted changes nothing). Synthetic data only."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parents[2])]
import r182lib as LB  # noqa: E402

D0 = date(2026, 10, 22)
DAY = D0.isoformat()
T0 = LB.day_epoch_0915(D0)


def secs(n_min=60, seed=0, every=1):
    """A synthetic future: one row every `every` seconds for n_min minutes, random walk price, random signed volume."""
    rng = np.random.default_rng(seed)
    es = np.arange(T0, T0 + 60 * n_min, every, dtype=np.int64)
    k = len(es)
    last = 22000 + np.cumsum(rng.normal(0, 1, k))
    buy = rng.integers(0, 50, k).astype(float)
    sell = rng.integers(0, 50, k).astype(float)
    return dict(epoch_sec=es, last=last, buy_vol=buy, sell_vol=sell, vol=buy + sell, q_buy_vol=buy.copy(),
                q_sell_vol=sell.copy(), depth_imb=rng.uniform(-1, 1, k), stale=np.zeros(k), gap_s=np.zeros(k),
                prints=np.ones(k), updates=np.ones(k), tbq=np.ones(k), tsq=np.ones(k))


def cut(s, upto):
    """The rows strictly before epoch second `upto`."""
    m = s["epoch_sec"] < upto
    return {k: v[m] for k, v in s.items()}


# ------------------------------------------------------------------------------------------------ time
def test_epochs():
    assert LB.epoch_of(DAY, "09:15:00") == T0
    assert LB.epoch_of(DAY, "10:00:05") == T0 + 45 * 60 + 5
    # 09:15 IST is 03:45 UTC
    assert (T0 % 86400) == 3 * 3600 + 45 * 60


# ------------------------------------------------------------------------------------------------ bars and features
def test_minute_bars_close_delta_rows_obi():
    s = dict(epoch_sec=np.array([T0, T0 + 30, T0 + 59, T0 + 60, T0 + 200]), last=np.array([10.0, 11.0, 12.0, 13.0, np.nan]),
             buy_vol=np.array([5.0, 0, 1, 2, 3]), sell_vol=np.array([1.0, 4, 0, 0, 0]), vol=np.array([6.0, 4, 1, 2, 3]),
             q_buy_vol=np.zeros(5), q_sell_vol=np.array([0, 0, 0, 0, 7.0]), depth_imb=np.array([0.2, 0.4, np.nan, 1, 0]))
    b = LB.minute_bars(s, T0)
    assert b.c[0] == 12.0 and b.c[1] == 13.0 and np.isnan(b.c[3])      # minute 3 has only a NaN price
    assert b.d[0] == 1.0 and b.d[1] == 2.0 and b.d[3] == 3.0
    assert b.rows[0] == 3 and b.rows[1] == 1 and b.rows[2] == 0
    assert b.obi[0] == pytest.approx(0.3) and np.isnan(b.obi[2])
    assert b.dq[3] == -7.0 and b.v[0] == 11.0


def test_rows_outside_session_ignored():
    s = secs(2)
    s["epoch_sec"] = s["epoch_sec"] - 600          # all before 09:15
    b = LB.minute_bars(s, T0)
    assert b.rows.sum() == 0 and np.isnan(b.c).all()


def test_scale_needs_15_full_minutes_and_is_point_in_time():
    d = np.arange(1, LB.N + 1, dtype=float)
    rows = np.full(LB.N, 60.0)
    s = LB.scale_series(d, rows)
    assert np.isnan(s[:15]).all() and s[15] == pytest.approx(np.mean(d[:15]))
    rows[3] = 5                                    # a thin minute is left out
    s2 = LB.scale_series(d, rows)
    assert np.isnan(s2[15]) and s2[16] == pytest.approx(np.mean(np.delete(d[:16], 3)))


def test_features_values():
    b = LB.minute_bars(secs(60, 1), T0)
    b.absorb = np.zeros(LB.N)
    f = LB.features(b)
    i = 40
    s = LB.scale_series(b.d, b.rows)[i]
    assert f["D5"][i] == pytest.approx(b.d[i - 4:i + 1].sum() / s)
    assert f["D15"][i] == pytest.approx(b.d[i - 14:i + 1].sum() / s)
    assert np.isnan(f["D5"][10])                   # no scale yet
    assert np.isnan(f["D1"][100])                  # no row after minute 59


def test_divergence_sign():
    n = LB.N
    c = np.full(n, np.nan)
    c[:40] = 1000 - np.arange(40)                  # price falling
    d = np.zeros(n)
    d[:40] = 10.0                                  # buying
    b = LB.Bars(c, d, np.zeros(n), np.ones(n), np.where(np.arange(n) < 40, 60.0, 0), np.zeros(n), np.zeros(n))
    f = LB.features(b)
    assert f["Div15"][30] == 1.0                   # bullish divergence: CVD up, price down
    d[:40] = -10.0
    f = LB.features(LB.Bars(c, d, np.zeros(n), np.ones(n), b.rows, np.zeros(n), np.zeros(n)))
    assert f["Div15"][30] == 0.0                   # both down: no divergence


def test_fwd_and_same_minute_returns():
    c = np.array([100.0, 101.0, np.nan, 103.0])
    r = LB.fwd_returns(c, 1)
    assert r[0] == pytest.approx(np.log(1.01)) and np.isnan(r[1]) and np.isnan(r[3])
    r0 = LB.same_minute_return(c)
    assert np.isnan(r0[0]) and r0[1] == pytest.approx(np.log(1.01))


def test_absorption_rule_and_direction():
    fp = {}
    for i in range(6):
        fp[T0 + 60 * i] = (np.array([100.0, 100.5]), np.array([5.0, 5.0]), np.array([5.0, 5.0]))
    # minute 6: 100 units, 80 at one price, mostly bought, range 0.0 -> buying absorbed (-1)
    fp[T0 + 360] = (np.array([100.0, 100.0 + 1e-3]), np.array([70.0, 10.0]), np.array([10.0, 10.0]))
    a = LB.absorb_minutes(fp, T0)
    assert a[6] == -1 and (a[:6] == 0).all()
    # too wide a range: no absorption
    fp[T0 + 360] = (np.array([100.0, 101.0]), np.array([70.0, 10.0]), np.array([10.0, 10.0]))
    assert LB.absorb_minutes(fp, T0)[6] == 0
    # only 4 earlier minutes: no absorption
    fp2 = {k: v for k, v in fp.items() if k >= T0 + 120}
    fp2[T0 + 360] = (np.array([100.0]), np.array([10.0]), np.array([70.0]))
    assert LB.absorb_minutes(fp2, T0)[6] == 0
    fp[T0 + 360] = (np.array([100.0]), np.array([10.0]), np.array([70.0]))
    assert LB.absorb_minutes(fp, T0)[6] == 1      # selling absorbed


# ------------------------------------------------------------------------------------------------ statistics
def test_rank_ties_and_nan():
    r = LB.rank(np.array([3.0, 1.0, np.nan, 3.0]))
    assert r[1] == 1 and r[0] == 2.5 and r[3] == 2.5 and np.isnan(r[2])


def test_spearman_and_bootstrap_detects_a_real_link():
    rng = np.random.default_rng(3)
    xs, ys = [], []
    for _ in range(20):
        x = rng.normal(size=300)
        xs.append(x)
        ys.append(0.5 * x + rng.normal(size=300))
    b = LB.day_bootstrap(xs, ys, draws=300)
    assert b["r"] > 0.3 and b["lo"] > 0.2 and b["p"] < 1e-6 and b["n_days"] == 20 and b["n"] == 6000
    assert LB.shift_null(xs, ys, draws=200) < 0.01


def test_null_is_not_significant_on_noise():
    rng = np.random.default_rng(4)
    xs = [rng.normal(size=300) for _ in range(20)]
    ys = [rng.normal(size=300) for _ in range(20)]
    b = LB.day_bootstrap(xs, ys, draws=300)
    assert b["lo"] < 0 < b["hi"]
    assert LB.shift_null(xs, ys, draws=300) > 0.05


def test_pearson_degenerate():
    assert np.isnan(LB.pearson(np.array([1.0, 1, 1]), np.array([1.0, 2, 3])))
    assert np.isnan(LB.pearson(np.array([1.0, np.nan]), np.array([1.0, 2])))


def test_bh_with_prior():
    q_all, q_in = LB.bh_with_prior([0.001, 0.5], 1000)
    assert q_all[0] == pytest.approx(1.0) and q_in[0] == pytest.approx(0.002)
    q_all, _ = LB.bh_with_prior([1e-6, 0.04], 100)
    assert q_all[0] == pytest.approx(1e-4) and q_all[1] == pytest.approx(1.0)


# ------------------------------------------------------------------------------------------------ the trades
def o(at, source, symbol, side, qty, avg, status="complete", filled=None, day=DAY):
    return dict(day=day, at=at, source=source, symbol=symbol, side=side, qty=qty,
                filled=qty if filled is None else filled, status=status, type="MARKET", avg=avg)


def test_parse_symbol():
    assert LB.parse_symbol("NIFTY17NOV2625250CE") == dict(und="NIFTY", expiry="17NOV26", strike=25250.0, right="CE")
    assert LB.parse_symbol("MIDCPNIFTY23NOV2613250PE")["und"] == "MIDCPNIFTY"
    assert LB.parse_symbol("BANKNIFTY23NOV2654900CE")["strike"] == 54900.0
    assert LB.parse_symbol("SILVERMIC30NOV26FUT") is None
    assert LB.arm_of("R51 Donchian 20") == "R51" and LB.arm_of("Liquidity 15+5") == "Liquidity"
    assert LB.arm_of("R72 Breadth Donchian") == "R72" and LB.arm_of("Manual") is None
    assert LB.is_squareoff("Auto square-off 15:15")


def test_rebuild_partial_sells_fifo_and_squareoff():
    orders = [
        o("09:20:03", "R51 Donchian 20", "NIFTY03NOV2623200CE", "BUY", 130, 100.0),
        o("09:20:03", "R72 Breadth Donchian", "NIFTY03NOV2623200CE", "BUY", 65, 101.0),
        o("09:20:03", "Liquidity 15+5", "NIFTY03NOV2623200CE", "SELL", 65, 0.0, status="cancelled", filled=0),
        o("09:50:00", "R51 Donchian 20", "NIFTY03NOV2623200CE", "SELL", 65, 110.0),
        o("15:15:00", "Auto square-off 15:15", "NIFTY03NOV2623200CE", "SELL", 130, 90.0),
        o("10:00:00", "Manual", "NIFTY03NOV2623200PE", "BUY", 65, 50.0),
    ]
    tr, st = LB.rebuild_trades(orders)
    assert st["unclosed"] == 0 and st["orphan_sell_qty"] == 0 and st["buys"] == 2
    r51 = next(t for t in tr if t["arm"] == "R51")
    r72 = next(t for t in tr if t["arm"] == "R72")
    assert r51["exit_px"] == pytest.approx(100.0) and r51["exit_at"] == "15:15:00"   # half at 110, half at 90
    assert r51["gross"] == pytest.approx(65 * 10 - 65 * 10)
    assert r72["exit_px"] == 90.0 and r72["gross"] == pytest.approx(-11 * 65)
    assert r51["net"] < r51["gross"] and r51["charges"] > 40                   # dated charges, 3 orders
    assert r51["side"] == 1 and r51["win"] == (r51["net"] > 0) and r51["win"] is False


def test_rebuild_sell_same_second_closes_and_other_arm_not_touched():
    orders = [
        o("10:10:00", "Liquidity 15+5", "FINNIFTY27OCT2624400CE", "BUY", 60, 256.9),
        o("10:10:00", "Liquidity 15+5", "FINNIFTY27OCT2624400CE", "SELL", 60, 215.05),
        o("10:00:00", "R72 Breadth Donchian", "FINNIFTY27OCT2624400CE", "BUY", 60, 250.0),
        o("10:30:00", "R51 Donchian 20", "FINNIFTY27OCT2624400CE", "SELL", 60, 260.0),   # R51 holds nothing: orphan
    ]
    tr, st = LB.rebuild_trades(orders)
    assert len(tr) == 1 and tr[0]["arm"] == "Liquidity" and tr[0]["points"] == pytest.approx(215.05 - 256.9)
    assert st["unclosed"] == 1 and st["orphan_sell_qty"] == 60


def test_rebuild_bad_symbol_and_empty():
    tr, st = LB.rebuild_trades([o("09:30:00", "R51 Donchian 20", "WHAT", "BUY", 1, 10.0)])
    assert tr == [] and st["bad_symbol"] == 1
    assert LB.rebuild_trades([]) == ([], dict(unclosed=0, orphan_sell_qty=0, bad_symbol=0, buys=0))


def test_charges_dated_and_bse():
    t = dict(arm="R51", day=DAY, symbol="X", und="SENSEX", right="PE", strike=1.0, entry_at="10:00:00", entry_px=500.0,
             qty=20, left=0, exits=[("10:30:00", 520.0, 20)])
    a = LB.finish(dict(t))
    b = LB.finish(dict(t, und="NIFTY"))
    assert a["gross"] == 400.0 and a["side"] == -1 and 60 < a["charges"] < 80 and a["charges"] != b["charges"]


# ------------------------------------------------------------------------------------------------ entry features, labels
def test_entry_features_window_and_alignment():
    s = secs(30, 5)
    S = T0 + 20 * 60 + 7
    f = LB.entry_features(s, None, T0, S, +1)
    m = (s["epoch_sec"] >= S - 300) & (s["epoch_sec"] < S)
    d5 = float(np.sum(s["buy_vol"][m] - s["sell_vol"][m]))
    assert f["d5"] == pytest.approx(d5) and f["a5"] == pytest.approx(d5) and f["d5_rows"] == 300
    g = LB.entry_features(s, None, T0, S, -1)
    assert g["a5"] == pytest.approx(-d5) and np.isnan(g["absorb_against5"])
    # 15-minute window clipped at 09:15
    h = LB.entry_features(s, None, T0, T0 + 600, +1)
    assert h["d15_rows"] == 600 and np.isnan(h["typ5"])      # 10 minutes in: only 6 earlier windows


def test_entry_features_absorption_against():
    s = secs(30, 6)
    ab = np.zeros(LB.N)
    ab[17] = -1                                           # buying absorbed in minute 17 (ends 09:33)
    S = T0 + 20 * 60 + 5                                  # last completed minute 19
    assert LB.entry_features(s, ab, T0, S, +1)["absorb_against5"] == 1
    assert LB.entry_features(s, ab, T0, S, -1)["absorb_against5"] == 0
    assert LB.entry_features(s, ab, T0, T0 + 26 * 60, +1)["absorb_against5"] == 0   # minute 17 out of the 5


def test_labels():
    assert LB.labels(None)["P"] is None
    base = dict(d5=-10.0, a5=-10.0, d5_rows=300, a15=5.0, d15_rows=900, typ5=8.0, absorb_against5=0)
    lab = LB.labels(base)
    assert lab["P"] is True and lab["V1"] is True and lab["V2"] is False and lab["V3"] is False and lab["group"] == "disagrees"
    lab = LB.labels(dict(base, a5=5.0, typ5=float("nan"), absorb_against5=float("nan")))
    assert lab["P"] is False and lab["V1"] is None and lab["V3"] is None and lab["group"] == "agrees"
    assert LB.labels(dict(base, a5=0.0))["P"] is None and LB.labels(dict(base, a5=0.0))["group"] == "zero"
    assert LB.labels(dict(base, d5_rows=0))["group"] == "no label"


def test_random_skip_twins():
    x = np.array([-100.0, -90, -80, 10, 20, 30, 40, 50, 60, 70])
    m = x < 0                                             # a perfect filter
    r = LB.random_skip_p(x, m, draws=2000)
    assert r["k"] == 3 and r["p"] < 0.01 and r["kept_obs"] == pytest.approx(280.0)
    bad = LB.random_skip_p(x, x > 50, draws=2000)        # skips the best
    assert bad["p"] > 0.9
    assert LB.random_skip_p(x, np.zeros(10, bool))["p"] is None
    assert LB.random_skip_p([], [])["p"] is None


def test_summary():
    s = LB.summary([10.0, -5.0, 0.0])
    assert s["n"] == 3 and s["win"] == pytest.approx(100 / 3) and s["net"] == 5.0
    assert LB.summary([])["n"] == 0


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_lookahead_minute_features():
    """Minute i's features are the same when everything after minute i is changed or deleted."""
    s = secs(120, 8)
    b = LB.minute_bars(s, T0)
    b.absorb = np.zeros(LB.N)
    f = LB.features(b)
    i = 70
    s2 = {k: v.copy() for k, v in s.items()}
    after = s2["epoch_sec"] >= T0 + 60 * (i + 1)
    s2["last"][after] *= 1.5
    s2["buy_vol"][after] = 999
    b2 = LB.minute_bars(s2, T0)
    b2.absorb = np.zeros(LB.N)
    b3 = LB.minute_bars(cut(s, T0 + 60 * (i + 1)), T0)
    b3.absorb = np.zeros(LB.N)
    for g in (LB.features(b2), LB.features(b3)):
        for k in f:
            assert np.allclose(f[k][:i + 1], g[k][:i + 1], equal_nan=True), k


def test_lookahead_absorption():
    fp = {T0 + 60 * i: (np.array([100.0]), np.array([5.0]), np.array([5.0])) for i in range(10)}
    a = LB.absorb_minutes(fp, T0)
    fp2 = dict(fp)
    fp2[T0 + 60 * 9] = (np.array([100.0]), np.array([500.0]), np.array([5.0]))
    assert (LB.absorb_minutes(fp2, T0)[:9] == a[:9]).all()


def test_lookahead_entry_features():
    """The entry second's own row and every later row do not change the label."""
    s = secs(40, 9)
    S = T0 + 30 * 60 + 11
    ab = np.zeros(LB.N)
    f = LB.entry_features(s, ab, T0, S, +1)
    s2 = {k: v.copy() for k, v in s.items()}
    later = s2["epoch_sec"] >= S
    s2["buy_vol"][later] = 10 ** 6
    ab2 = ab.copy()
    ab2[30] = -1                                          # the minute holding S is not complete
    g = LB.entry_features(s2, ab2, T0, S, +1)
    h = LB.entry_features(cut(s, S), ab, T0, S, +1)
    for k in f:
        assert (f[k] == g[k] == h[k]) or (np.isnan(f[k]) and np.isnan(g[k]) and np.isnan(h[k])), k
