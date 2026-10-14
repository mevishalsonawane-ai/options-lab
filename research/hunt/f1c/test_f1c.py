"""F1C tests: the perp simulator (stop + lock, cautious bar order, shorts), sizing, costs, tax readings, TDS, the
one-at-a-time / day-cap selection, the periods and the holdout lock, BH. Synthetic data only."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.f1c import book, data, perp, stats
from research.hunt.f1c import f1ccfg as C


def _bars(path):
    """o/h/l/c from a list of (open, high, low, close)."""
    a = np.array(path, float)
    return a[:, 0], a[:, 1], a[:, 2], a[:, 3]


def _walk(path, side=1, stop=0.01, deadline=None, lock="step"):
    o, h, l, c = _bars(path)
    d = len(o) if deadline is None else deadline
    w = perp.walk(o, h, l, c, [0], [side], [stop], [d], lock)
    return {k: v[0] for k, v in w.items()}


def test_every_perp_exit_needs_a_stop_and_a_lock():
    with pytest.raises(ValueError):
        perp.PerpExit("nostop", 0.0, "step")
    with pytest.raises(ValueError):
        perp.PerpExit("nolock", 1.0, "none")
    assert all(e.k_atr > 0 and e.lock in ("step", "trail") for e in perp.EXITS) and len(perp.EXITS) == 4


def test_lock_levels():
    assert np.isneginf(perp.lock_r(np.array([0.99]), "step"))[0]
    assert list(perp.lock_r(np.array([1.0, 1.9, 2.0, 3.5, 5.0]), "step")) == [0.25, 0.25, 1.0, 2.0, 4.0]
    assert list(perp.lock_r(np.array([1.0, 2.0, 4.0]), "trail")) == [0.5, 1.0, 2.0]
    assert perp.lock_r(np.array([1.0]), "trail")[0] >= perp.STEP_FIRST


def test_stop_at_level_and_gap_through_the_stop():
    # entry 100, stop 1% = 99; bar 1 touches 98.9 -> exit at 99
    w = _walk([(100, 100.2, 99.8, 100), (100, 100.1, 98.9, 99.2), (99, 99, 99, 99)])
    assert w["entry"] == 100 and w["exit"] == pytest.approx(99.0) and w["reason"] == 0 and w["idx"] == 1
    # bar 1 opens at 98 (below the stop): exit at the open, not at the level
    w = _walk([(100, 100.2, 99.8, 100), (98, 98.5, 97.5, 98)])
    assert w["exit"] == 98 and w["reason"] == 0
    # the entry bar's own low can stop the trade (cautious: no gap on the entry bar)
    w = _walk([(100, 100.1, 98.0, 99.5), (99.5, 101, 99.4, 100)])
    assert w["exit"] == pytest.approx(99.0) and w["idx"] == 0


def test_step_lock_and_the_bar_order():
    # bar 0 reaches +1.5R (101.5): from bar 1 the lock is +0.25R = 100.25; bar 1 dips to 100.2 -> lock exit at 100.25
    w = _walk([(100, 101.5, 99.9, 101), (101, 101.2, 100.2, 100.5), (100, 100, 100, 100)])
    assert w["exit"] == pytest.approx(100.25) and w["reason"] == 1 and w["peak_r"] == pytest.approx(1.5)
    # a bar that makes a new high AND touches the old level exits at the old level (the high comes after)
    w = _walk([(100, 102.1, 99.9, 102), (102, 105, 100.9, 104)])
    assert w["exit"] == pytest.approx(101.0) and w["reason"] == 1          # +2.1R peak locks +1R = 101
    # trail: peak +4R (104) keeps 50% -> 102
    w = _walk([(100, 104, 99.9, 103.5), (103.5, 103.6, 101.5, 102)], lock="trail")
    assert w["exit"] == pytest.approx(102.0) and w["reason"] == 1


def test_short_mirrors_long_and_time_exit():
    # short at 100, stop 101; bar 1 high 101.2 -> exit at 101 (a loss)
    w = _walk([(100, 100.2, 99.8, 100), (100, 101.2, 99.9, 101)], side=-1)
    assert w["exit"] == pytest.approx(101.0) and w["reason"] == 0
    # short peak at 98.4 (+1.6R): lock +0.25R = 99.75; bar 2 high 99.8 -> exit 99.75
    w = _walk([(100, 100.1, 98.4, 98.6), (98.6, 99.0, 98.5, 98.9), (98.9, 99.8, 98.8, 99.5)], side=-1)
    assert w["exit"] == pytest.approx(99.75) and w["reason"] == 1
    # nothing hit: exit at the deadline bar's open (time)
    w = _walk([(100, 100.5, 99.5, 100), (100, 100.5, 99.5, 100.2), (100.3, 100.4, 100.1, 100.2)], deadline=2)
    assert w["exit"] == 100.3 and w["reason"] == 2 and w["idx"] == 2


def test_winner_cut_short_is_measured_after_the_exit():
    w = _walk([(100, 101.5, 99.9, 101), (101, 101.2, 100.2, 100.5), (100.5, 103.0, 100.4, 102.8), (103, 103, 103, 103)],
              deadline=3)
    assert w["reason"] == 1 and w["after_r"] == pytest.approx((103.0 - 100.25) / 1.0)


def test_vectorised_matches_one_by_one():
    rng = np.random.default_rng(0)
    c = 100 * np.exp(np.cumsum(rng.normal(0, 0.002, 3000)))
    o = np.r_[100, c[:-1]]
    h = np.maximum(o, c) * (1 + np.abs(rng.normal(0, 0.001, 3000)))
    l = np.minimum(o, c) * (1 - np.abs(rng.normal(0, 0.001, 3000)))
    i0 = rng.integers(0, 2500, 300)
    side = rng.choice([-1, 1], 300)
    st = rng.uniform(0.003, 0.01, 300)
    dl = i0 + rng.integers(5, 400, 300)
    W = perp.walk(o, h, l, c, i0, side, st, dl, "trail", chunk=37)
    for k in range(0, 300, 29):
        w = perp.walk(o, h, l, c, [i0[k]], [side[k]], [st[k]], [dl[k]], "trail")
        for key in ("exit", "idx", "reason"):
            assert W[key][k] == pytest.approx(w[key][0]), key


def test_sizing_lots_and_leverage_cap():
    q = book.size_perp("BTCUSDT", np.array([100_000.0]), np.array([0.005]), 100_000)
    # 1% risk / 0.5% stop = Rs 2 lakh notional (< 3x cap) = $2,353 = 0.023 BTC in 0.001 lots
    assert q[0] == pytest.approx(0.023)
    q = book.size_perp("BTCUSDT", np.array([100_000.0]), np.array([0.001]), 100_000)
    assert q[0] * 100_000 * C.INR_PER_USD <= 3 * 100_000 + 1e-6                 # capped at 3x the account
    assert book.size_perp("BTCUSDT", np.array([100_000.0]), np.array([0.5]), 25_000)[0] == 0.0   # under one lot


def test_book_costs_tax_tds_and_funding():
    ts = pd.date_range("2025-01-01 23:50", periods=40, freq="min")
    w = dict(entry=np.array([100_000.0, 100_000.0]), exit=np.array([101_000.0, 101_000.0]), idx=np.array([30.0, 30.0]),
             reason=np.array([1.0, 0.0]), peak_r=np.array([2.0, 0.0]), after_r=np.array([0.0, 0.0]))
    B = book.perp_book("BTCUSDT", ts, [0, 0], w, [1, -1], [0.01, 0.01], [0.0001, 0.0001])
    fee, slip = book.perp_costs("BTCUSDT")
    q = B.qty[0]
    assert q == pytest.approx(0.011)                                            # Rs 1L notional / $100k / Rs 85
    e, x = 100_000 * (1 + slip), 101_000 * (1 - slip)
    assert B.gross[0] == pytest.approx((x - e) * q * C.INR_PER_USD)
    assert B.fees[0] == pytest.approx(fee * (e + x) * q * C.INR_PER_USD)
    # the 00:00 UTC settlement is crossed: the long pays, the short receives
    assert B.funding[0] == pytest.approx(0.0001 * 100_000 * q * C.INR_PER_USD)
    assert B.funding[1] == pytest.approx(-0.0001 * 100_000 * q * C.INR_PER_USD)
    # reading A: tax only on the winner (the long); the losing short never offsets it
    assert B.tax_a[0] == pytest.approx(C.TAX * B.gross[0]) and B.tax_a[1] == 0.0
    # TDS: the long's sale is its exit, the short's sale its entry
    assert B.tds[0] == pytest.approx(0.01 * x * q * C.INR_PER_USD)
    assert B.tds[1] == pytest.approx(0.01 * 100_000 * (1 - slip) * q * C.INR_PER_USD)


def test_tax_b_carries_losses_forward_and_tds_lock():
    B = pd.DataFrame(dict(t_in=pd.to_datetime(["2024-05-01", "2025-05-01", "2026-01-01"]),
                          t_out=pd.to_datetime(["2024-05-01", "2025-05-01", "2026-01-01"]), net=[-1000.0, 600.0, 700.0],
                          tds=[60_000.0, 0.0, 0.0]))
    assert book.tax_b(B) == pytest.approx(C.TAX * 300.0)                       # FY25-26: 600 + 700 - 1000 carried
    t = book.tds_locked(B)
    assert t["peak"] == 60_000.0 and t["first_half_day"] == "2024-05-01"


def test_select_one_at_a_time_and_day_cap():
    t = pd.to_datetime(["2025-01-01 19:00", "2025-01-01 19:10", "2025-01-01 20:00", "2025-01-01 21:00",
                        "2025-01-01 22:00"])
    B = pd.DataFrame(dict(t_in=t, t_out=t + pd.Timedelta(minutes=30), net=[-1500.0, 0.0, -1600.0, 500.0, 500.0]))
    S = book.select(B, 100_000)
    # 19:10 is skipped (19:00 still open); after -3,100 on the IST day the cap stops 21:00 and 22:00
    assert list(S.t_in) == [t[0], t[2]]


def test_periods_are_disjoint_and_the_holdout_is_the_clean_2025_block():
    assert C.HOLDOUT == (pd.Timestamp("2025-04-01"), pd.Timestamp("2025-10-01"))
    t = pd.to_datetime(["2025-03-31 23:00", "2025-03-31 19:59", "2025-05-01 00:00", "2025-10-15 00:00", "2025-11-01 00:00",
                        "2026-03-31 23:00", "2026-04-01 00:00"])
    d = C.is_design(t, 240)
    assert not C.is_design(pd.to_datetime(["2025-03-31 20:00"]), 240)[0]       # ends exactly at the holdout: out
    assert list(d) == [False, True, False, False, True, False, False]           # windows never cross into the holdout
    assert list(C.is_holdout(t, 0)) == [False, False, True, False, False, False, False]
    assert not (C._in(t, C.SCREEN) & C._in(t, C.SELECT)).any()
    for a, b in C.DESIGN:
        assert b <= C.HOLDOUT[0] or a >= C.HOLDOUT[1] + pd.Timedelta(days=30)   # the 31-day embargo after the holdout
        assert b <= C.CC.HOLDOUT_START                                          # the brain's own lock is untouched


def test_holdout_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(C, "HOLD_LOG", tmp_path / "HOLDOUT_READ.json")
    data.read_holdout("idea-x")
    with pytest.raises(data.HoldoutError):
        data.read_holdout("idea-x")
    data.read_holdout("idea-y")
    assert set(data.holdout_reads()) == {"idea-x", "idea-y"}


def test_bh_and_twin_p():
    q = stats.bh([0.01, 0.04, 0.03, np.nan, 0.5])
    assert np.isnan(q[3]) and q[0] == pytest.approx(0.04) and q[4] == pytest.approx(0.5)
    pool = np.random.default_rng(1).normal(0, 1, 5000)
    assert stats.twin_p(3.0, pool, 50) < 0.001 and stats.twin_p(-3.0, pool, 50) > 0.99


# --- engine (synthetic market; no real data) ---------------------------------------------------------------------------
class _FakeMarket:
    def __init__(self, start="2024-01-10 18:00", minutes=600, drift=0.0, seed=0):
        from research.hunt.f1c import engine
        rng = np.random.default_rng(seed)
        self.sym = "BTCUSDT"
        self.ts = pd.date_range(start, periods=minutes, freq="min")
        c = 40_000 * np.exp(np.cumsum(rng.normal(drift, 3e-4, minutes)))
        self.o = np.r_[c[0], c[:-1]]
        self.h = np.maximum(self.o, c) * 1.0002
        self.l = np.minimum(self.o, c) * 0.9998
        self.c = c
        self.spot = c
        self.fund = np.full(minutes, 1e-4)
        self.idx = lambda ts: self.ts.get_indexer(pd.DatetimeIndex(ts))
        self._e = engine


def test_window_entry_minute_and_deadline():
    from research.hunt.f1c import engine
    d = pd.to_datetime(["2024-01-10 18:29", "2024-01-10 18:24", "2024-01-11 03:24", "2024-01-11 03:29"])
    assert list(engine.in_window(d)) == [True, False, True, False]      # entries 00:00, 23:55, 08:55, 09:00 IST
    assert engine.window_end(d[:1])[0] == pd.Timestamp("2024-01-11 03:30")


def test_engine_perp_trades_enter_next_minute_and_exit_by_nine_ist():
    from research.hunt.f1c import engine
    mk = _FakeMarket()
    F = pd.DataFrame({"ts": pd.date_range("2024-01-10 18:29", "2024-01-11 03:24", freq="5min")})
    F["atr14_1h_pct"] = 0.4
    F["ist_day"] = (F.ts + pd.Timedelta(minutes=1) + C.IST).dt.date
    F["ist_min"] = 0
    rule = engine.Rule("T", "test", "perp", ("BTCUSDT",), lambda F, mk=None: pd.DataFrame(
        {"ts": F.ts[[0, 50, 100]].values, "side": [1, -1, 1]}), None, None)
    B = engine.trades(rule, "BTCUSDT", mk, F, perp.EXITS[0], part="design")
    assert len(B) >= 1 and B.t_in.iloc[0] == pd.Timestamp("2024-01-10 18:30")
    assert (B.t_out <= pd.Timestamp("2024-01-11 03:30")).all()
    assert (B.t_in.iloc[1:].values >= B.t_out.iloc[:-1].values).all()     # one at a time
    assert set(B.reason.unique()) <= {0.0, 1.0, 2.0}
    # random twins use the rule's clock, random days of the frame and both sides
    e = F.ts + pd.Timedelta(minutes=1) + C.IST
    F2 = pd.concat([F.assign(ts=F.ts + pd.Timedelta(days=k)) for k in range(5)], ignore_index=True)
    e = F2.ts + pd.Timedelta(minutes=1) + C.IST
    F2["ist_min"] = e.dt.hour * 60 + e.dt.minute
    td = engine.twin_decisions(pd.DataFrame({"ts": F.ts[:3]}), F2, 200, 1)
    assert len(td) == 200 and set(td.side) == {-1, 1} and td.ts.isin(F2.ts).all()
    tm = (td.ts + pd.Timedelta(minutes=1) + C.IST)
    assert set(tm.dt.hour * 60 + tm.dt.minute) <= {0, 5, 10} and td.ts.dt.date.nunique() > 1


def test_rule_set_is_pre_registered_and_every_rule_has_stop_and_lock_exits():
    from research.hunt.f1c.rules import RULES
    assert len(RULES) == 20
    for r in RULES.values():
        assert len(r.exits) == 4
        for ex in r.exits:
            if r.venue == "perp":
                assert ex.k_atr > 0 and ex.lock in ("step", "trail")
            else:
                assert 0 < ex.stop < 1 and ex.lock in ("step", "trail")
    assert {r.venue for r in RULES.values()} == {"perp", "option"}
    assert all(set(r.coins) <= {"BTCUSDT", "ETHUSDT"} for r in RULES.values() if r.venue == "option")


def _fake_day(prices, vols=None, listed_min_before=60):
    from research.hunt.f1c import options
    d = options.Day.__new__(options.Day)
    d.sym, d.coin, d.d, d.ok = "BTCUSDT", "BTC", None, True
    d.E = pd.Timestamp("2025-01-11 12:00")
    d.grid = pd.date_range(d.E - options.KEEP_FROM, d.E - options.KEEP_TO, freq="min")
    n = len(d.grid)
    p = np.full(n, np.nan)
    p[:len(prices)] = prices
    v = np.ones(n) if vols is None else np.r_[vols, np.ones(n - len(vols))]
    a = np.c_[p, p, p, p, v]
    d.arr = {("C", 95_000.0): a}
    d.listed = {("C", 95_000.0): d.grid[0] - pd.Timedelta(minutes=listed_min_before)}
    d.strikes = {"C": [95_000.0], "P": []}
    return d


def test_option_trade_stop_lock_fills_and_entry_rules():
    from research.hunt.f1c import options
    from research.hunt.rc2 import costs as rc2costs
    T = pd.Timestamp("2025-01-10 18:40")                  # grid starts 18:00; i = 40
    path = [500.0] * 41 + [520, 560, 680, 700, 650, 600, 560, 500]   # peaks 1.4x -> step locks 1.0x
    day = _fake_day(path)
    dl = pd.Timestamp("2025-01-11 03:30")
    r = options.trade(day, T, 1, 95_000.0, dl, options.EXITS[0], 100_000)
    assert r is not None and r["mid"] == 500.0 and r["reason"] == 1.0              # a lock exit at 1.0x
    sm = rc2costs.spread_model()
    assert r["buy"] == pytest.approx(rc2costs.buy_fill("BTC", 500.0, 95_000.0, sm))
    assert r["sell"] == pytest.approx(rc2costs.sell_fill("BTC", 500.0, 95_000.0, sm, market=True))
    assert r["lots"] == int(np.floor(0.02 * 100_000 / C.INR_PER_USD / (r["buy"] * 0.001)))
    assert r["net"] < r["gross"] and r["tax_a"] == pytest.approx(C.TAX * max(r["gross"], 0))
    # a fall to 0.6x hits the -40% stop
    day = _fake_day([500.0] * 41 + [450, 380, 290, 250])
    r = options.trade(day, T, 1, 95_000.0, dl, options.EXITS[0], 100_000)
    assert r["reason"] == 0.0 and r["sell"] < 0.6 * 500
    # entry rules: listed 10 minutes ago -> no trade; no volume in the last 30 minutes -> no trade; a 3x jump -> no
    assert options.trade(_fake_day([500.0] * 60, listed_min_before=-35), T, 1, 95_000.0, dl, options.EXITS[0],
                         100_000) is None
    assert options.trade(_fake_day([500.0] * 60, vols=np.zeros(60)), T, 1, 95_000.0, dl, options.EXITS[0],
                         100_000) is None
    assert options.trade(_fake_day([150.0] * 40 + [500.0] * 20), T, 1, 95_000.0, dl, options.EXITS[0], 100_000) is None
    # a strike more than 1% from spot is not "at the money"
    assert options.trade(_fake_day([500.0] * 60), T, 1, 97_000.0, dl, options.EXITS[0], 100_000) is None


def test_holdout_rows_stay_locked_until_the_read(tmp_path, monkeypatch):
    monkeypatch.setattr(C, "HOLD_LOG", tmp_path / "HOLDOUT_READ.json")
    t = pd.to_datetime(["2025-05-01 00:00"])
    with pytest.raises(data.HoldoutError):
        data.keep(t, "holdout", 0)
    assert data.keep(t, "design", 0).tolist() == [False]
    data.read_holdout(data.HOLDOUT_IDEA)
    assert data.keep(t, "holdout", 0).tolist() == [True]


def test_strict_option_entry_waits_a_minute_and_skips_stale_prints(monkeypatch):
    from research.hunt.f1c import options
    monkeypatch.setitem(options.ENTRY, "strict", True)
    T = pd.Timestamp("2025-01-10 18:40")
    dl = pd.Timestamp("2025-01-11 03:30")
    path = [500.0] * 41 + [510.0] + [510.0] * 10
    r = options.trade(_fake_day(path), T, 1, 95_000.0, dl, options.EXITS[0], 100_000)
    assert r["mid"] == 510.0 and r["t_in"] == T + pd.Timedelta(minutes=1)
    vols = np.r_[np.ones(30), np.zeros(30)]                   # last trade 12 minutes before the entry minute
    assert options.trade(_fake_day([500.0] * 60, vols=vols), T, 1, 95_000.0, dl, options.EXITS[0], 100_000) is None
