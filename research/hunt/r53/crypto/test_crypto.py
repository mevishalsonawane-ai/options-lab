"""Tests for the R53 crypto book (research/hunt/r53/crypto). Synthetic data only: no network, no cache.

    python -m pytest research/hunt/r53 -q
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd
import pytest

from research.hunt.f1c import perp as f1cperp
from research.hunt.rc2.exits import DIRECTIONAL, Exit
from research.hunt.r53 import common as RC
from research.hunt.r53.crypto import baselines, data, features, fetch, opt, perps, rules as R, run

UTC = "UTC"
E0 = pd.Timestamp("2025-01-15 12:00", tz=UTC)


# ------------------------------------------------------------------------------------------------ fixtures
class FakeStore:
    """An offline OptionStore: contracts from a dict {(coin, expiry, typ, K): DataFrame}."""

    def __init__(self, book=None):
        self.book = book or {}
        self.calls = 0

    def get(self, coin, expiry, typ, K):
        self.calls += 1
        return self.book.get((coin, expiry, typ, int(K)))

    def prefetch(self, keys, threads=6):
        return 0


def candles(expiry, start, prices, gaps=()):
    """1m candles from `start` with o = h = l = c = price unless prices are (o, h, l, c) tuples; `gaps` drops minutes."""
    rows = []
    for i, p in enumerate(prices):
        if i in gaps:
            continue
        o, h, l, c = p if isinstance(p, tuple) else (p, p, p, p)
        rows.append(dict(t=start + pd.Timedelta(minutes=i), o=o, h=h, l=l, c=c))
    return pd.DataFrame(rows)


def flat_contract(expiry, px=100.0, n=1680):
    return candles(expiry, expiry - fetch.OPT_SPAN, [px] * n)


def walk_minutes(n, seed=1, start="2024-10-01", p0=60_000.0, vol=0.0008):
    rng = np.random.default_rng(seed)
    c = p0 * np.exp(np.cumsum(rng.normal(0, vol, n)))
    o = np.r_[p0, c[:-1]]
    h = np.maximum(o, c) * (1 + np.abs(rng.normal(0, vol / 2, n)))
    l = np.minimum(o, c) * (1 - np.abs(rng.normal(0, vol / 2, n)))
    t = pd.date_range(pd.Timestamp(start, tz=UTC), periods=n, freq="min", name="t")
    return pd.DataFrame({"o": o, "h": h, "l": l, "c": c}, index=t)


@pytest.fixture(autouse=True)
def fixed_fx(monkeypatch):
    s = pd.Series([85.0], index=pd.DatetimeIndex([pd.Timestamp("2016-01-01", tz=UTC)]))
    monkeypatch.setattr(data, "usdinr", lambda: s)
    f = pd.Series([0.0001], index=pd.DatetimeIndex([pd.Timestamp("2016-01-01", tz=UTC)]))
    monkeypatch.setattr(data, "funding", lambda coin: f)


def market(n=6 * 1440, seed=1, start="2024-10-01", store=None):
    m1 = walk_minutes(n, seed, start)
    hours = pd.date_range(m1.index[0], m1.index[-1], freq="h")
    dvol = pd.Series(50.0, index=hours)
    oi = pd.DataFrame({"ts": pd.date_range(m1.index[0], m1.index[-1], freq="5min"),
                       "oi": 1e5 * np.exp(np.cumsum(np.random.default_rng(seed + 7).normal(0, 0.004, len(
                           pd.date_range(m1.index[0], m1.index[-1], freq="5min")))))})
    return R.Market("BTC", m1=m1, dvol=dvol, oi=oi, index=m1.c, store=store or FakeStore())


# ------------------------------------------------------------------------------------------------ stop + lock always
def test_every_preregistered_version_has_a_stop_and_a_lock():
    for rule in R.RULES.values():
        for v in rule.variants:
            R.assert_stop_and_lock(R.exit_of(rule, v))


def test_a_version_without_a_stop_or_a_lock_is_refused():
    with pytest.raises(ValueError):
        Exit("nostop", 0.0, "step", rungs=((1.3, 1.0),))
    with pytest.raises(ValueError):
        Exit("nolock", 0.4, "none")
    with pytest.raises(ValueError):
        f1cperp.PerpExit("nostop", 0.0, "trail")
    with pytest.raises(ValueError):
        f1cperp.PerpExit("nolock", 1.5, "none")
    with pytest.raises(ValueError):
        perps.BrkExit("nostop", stop_atr=0.0)
    with pytest.raises(ValueError):
        perps.BrkExit("nolock", trail_n=0)
    with pytest.raises(ValueError):
        R.assert_stop_and_lock(object())
    with pytest.raises(KeyError):
        R.exit_of(R.RULES["CC1"], "s40_none")
    with pytest.raises(TypeError):     # the simulators only take a stop+lock exit object
        opt.trade(opt.Chain("BTC", FakeStore()), E0 - pd.Timedelta(hours=5), "C", E0, E0, object(), "atm",
                  lambda t: 1e5, lambda t: [85.0])
    with pytest.raises(TypeError):
        perps.f1c_trades("BTC", walk_minutes(100), [], [], [], [], object(), None, None)


def test_rule_table_matches_the_prereg():
    r = R.RULES
    assert r["CC1"].variants == ("s40_step", "s40_trail", "s25_step")
    assert r["CC2"].variants == ("s40_trail", "s40_step") and r["CC2"].max_hold_min == 120
    assert r["CC3"].variants == ("s70_step", "s50_step") and r["CC3"].strike == "cheap"
    assert r["CC4"].variants == ("s15_trail", "s10_trail", "s15_step") and r["CC4"].max_hold_min == 180
    assert r["CC5"].variants == ("s15_step", "s15_trail") and r["CC5"].max_per_day == 2
    assert r["CC6"].variants == ("s25_step", "s40_trail") and r["CC6"].max_hold_min == 120
    assert r["CC7"].variants == ("s40_trail", "s25_step") and r["CC7"].signal == "P07"
    assert r["CC8"].variants == ("orig", "step_lock") and not r["CC8"].two_way


# ------------------------------------------------------------------------------------------------ option fills
def option_setup(prices, gaps=(), K=100_000, typ="C", spot=100_050.0):
    """A contract whose candles start 5 h before the decision T (well past the 15-minute listing wait)."""
    T = E0 - pd.Timedelta(hours=6)
    start = T - pd.Timedelta(hours=5)
    pre = [100.0] * 300
    d = candles(E0, start, pre + list(prices), gaps=tuple(g + 300 for g in gaps))
    store = FakeStore({("BTC", E0, typ, K): d})
    return opt.Chain("BTC", store), T, spot


def test_option_fills_one_minute_after_the_next_bar():
    # minute T (next bar) opens at 100, minute T+1 at 120: the entry must be 120
    chain, T, spot = option_setup([100.0, 120.0] + [120.0] * 200)
    ex = DIRECTIONAL[0]
    r = opt.trade(chain, T, "C", E0, T + pd.Timedelta(hours=3), ex, "atm", lambda t: spot, lambda t: [85.0])
    assert isinstance(r, dict)
    assert r["entry_ts"] == T + pd.Timedelta(minutes=1)
    assert r["mid"] == 120.0
    assert r["reason"] == "time" and r["exit_ts"] == T + pd.Timedelta(hours=3)


def test_option_stop_gap_fills_at_the_worse_open():
    # entry 100 at T+1; at T+3 the candle OPENS at 40 (through the -40% stop at 60): exit at 40, not 60
    chain, T, spot = option_setup([100.0, 100.0, 100.0, (40.0, 41.0, 39.0, 40.0)] + [40.0] * 50)
    r = opt.trade(chain, T, "C", E0, T + pd.Timedelta(hours=3), DIRECTIONAL[0], "atm", lambda t: spot,
                  lambda t: [85.0])
    assert r["reason"] == "stop" and r["level"] == 40.0
    # a touch without a gap fills at the level
    chain, T, spot = option_setup([100.0, 100.0, 100.0, (90.0, 91.0, 50.0, 70.0)] + [70.0] * 50)
    r = opt.trade(chain, T, "C", E0, T + pd.Timedelta(hours=3), DIRECTIONAL[0], "atm", lambda t: spot,
                  lambda t: [85.0])
    assert r["reason"] == "stop" and abs(r["level"] - 60.0) < 1e-9


def test_option_missing_candles_are_carried_flat_and_a_missing_entry_is_skipped():
    chain, T, spot = option_setup([100.0, 110.0, 111.0, 112.0], gaps=(2, 3))
    ct = chain.contract(E0, "C", 100_000)
    j = ct.pos(T + pd.Timedelta(minutes=2))
    assert ct.o[j] == ct.h[j] == ct.l[j] == ct.c[j] == 110.0           # flat at the last close
    # the entry minute itself missing: carried flat, so its open equals the previous close (still tradable)
    # but a contract with NO candle before the entry is never bought
    T2 = E0 - pd.Timedelta(hours=6)
    late = candles(E0, T2 + pd.Timedelta(minutes=1), [100.0] * 100)
    ch2 = opt.Chain("BTC", FakeStore({("BTC", E0, "C", 100_000): late}))
    assert opt.trade(ch2, T2, "C", E0, T2 + pd.Timedelta(hours=1), DIRECTIONAL[0], "atm", lambda t: 100_050.0,
                     lambda t: [85.0]) == "no_strike"


def test_listing_wait_and_jump_filter():
    T = E0 - pd.Timedelta(hours=6)
    fresh = candles(E0, T - pd.Timedelta(minutes=10), [100.0] * 200)       # listed 10 minutes before T
    ch = opt.Chain("BTC", FakeStore({("BTC", E0, "C", 100_000): fresh}))
    assert ch.atm(E0, "C", 100_050.0, T) is None
    chain, T, spot = option_setup([100.0, 250.0] + [250.0] * 50)          # the entry candle jumps 2.5x
    assert opt.trade(chain, T, "C", E0, T + pd.Timedelta(hours=1), DIRECTIONAL[0], "atm", lambda t: spot,
                     lambda t: [85.0]) == "no_print"


def test_atm_picks_the_nearest_listed_strike_within_1pct():
    T = E0 - pd.Timedelta(hours=6)
    book = {("BTC", E0, "C", k): flat_contract(E0) for k in (99_000, 100_000, 101_000)}
    ch = opt.Chain("BTC", FakeStore(book))
    assert ch.atm(E0, "C", 100_300.0, T)[0] == 100_000
    assert ch.atm(E0, "C", 100_600.0, T)[0] == 101_000
    assert opt.Chain("BTC", FakeStore({("BTC", E0, "C", 105_000): flat_contract(E0)})).atm(E0, "C", 100_000.0, T) \
        is None


def test_hero_picks_the_dearest_cheap_otm_option():
    T = E0 - pd.Timedelta(hours=2)
    spot = 100_000.0
    book = {("BTC", E0, "C", 100_200): flat_contract(E0, 300.0),    # 0.30% of spot: too dear
            ("BTC", E0, "C", 100_400): flat_contract(E0, 90.0),     # 0.09%: in the band, the dearest
            ("BTC", E0, "C", 100_600): flat_contract(E0, 30.0)}
    ch = opt.Chain("BTC", FakeStore(book))
    assert ch.cheap(E0, "C", spot, T)[0] == 100_400
    assert ch.cheap(E0, "P", spot, T) is None


def test_expiry_and_deadline_rules():
    T = pd.Timestamp("2025-01-15 08:55", tz=UTC)
    assert R.rc2_expiry(T, 3) == pd.Timestamp("2025-01-15 12:00", tz=UTC)
    assert R.rc2_expiry(T + pd.Timedelta(minutes=10), 3) == pd.Timestamp("2025-01-16 12:00", tz=UTC)
    assert R.rc2_expiry(pd.Timestamp("2025-01-15 11:55", tz=UTC), 1 / 6) is None or \
        R.rc2_expiry(pd.Timestamp("2025-01-15 11:55", tz=UTC), 1 / 6) == pd.Timestamp("2025-01-16 12:00", tz=UTC)
    # F1C: the expiry of the entry's IST date (19:00 UTC on the 14th = 00:30 IST on the 15th)
    assert R.f1c_expiry(pd.Timestamp("2025-01-14 19:00", tz=UTC)) == pd.Timestamp("2025-01-15 12:00", tz=UTC)
    # RC2: never held into the last ten minutes before settlement
    E = pd.Timestamp("2025-01-15 12:00", tz=UTC)
    assert R.deadline_of(R.RULES["CC3"], pd.Timestamp("2025-01-15 11:00", tz=UTC), E) == E - pd.Timedelta(minutes=10)
    # F1C: flat by 09:00 IST (03:30 UTC) or the time limit
    T = pd.Timestamp("2025-01-15 02:00", tz=UTC)
    assert R.deadline_of(R.RULES["CC6"], T) == pd.Timestamp("2025-01-15 03:30", tz=UTC)
    assert R.deadline_of(R.RULES["CC6"], T - pd.Timedelta(hours=3)) == T - pd.Timedelta(hours=1)


def test_option_trade_never_held_past_its_deadline():
    chain, T, spot = option_setup([100.0] * 400)
    dl = T + pd.Timedelta(minutes=30)
    r = opt.trade(chain, T, "C", E0, dl, DIRECTIONAL[0], "atm", lambda t: spot, lambda t: [85.0])
    assert r["exit_ts"] == dl and r["reason"] == "time"
    assert opt.trade(chain, T, "C", E0, T + pd.Timedelta(seconds=30), DIRECTIONAL[0], "atm", lambda t: spot,
                     lambda t: [85.0]) == "no_time"


# ------------------------------------------------------------------------------------------------ costs and tax
def test_option_cost_books():
    nets = opt.book_nets("BTC", buy=500.0, sell=600.0, spot_in=100_000.0, spot_out=100_000.0, q=0.01, fx=85.0)
    gross = (600 - 500) * 0.01
    # Delta: min(0.01% x 1000 = 0.10, 3.5% x 5 = 0.175) = 0.10 in; min(0.10, 0.21) = 0.10 out; + 18% GST
    assert abs(nets["delta"] - (gross - 0.2 * 1.18) * 85) < 1e-9
    # CoinSwitch: 0.015% of notional each side, with / without GST
    assert abs(nets["coinswitch_gst"] - (gross - 0.3 * 1.18) * 85) < 1e-9
    assert abs(nets["coinswitch_nogst"] - (gross - 0.3) * 85) < 1e-9
    # the Delta cap: a cheap option pays 3.5% of the premium
    cheap = opt.book_nets("BTC", buy=10.0, sell=0.0, spot_in=100_000.0, spot_out=100_000.0, q=0.01, fx=1.0)
    assert abs(cheap["delta"] - (-0.1 - 0.035 * 0.1 * 1.18)) < 1e-12


def test_perp_cost_books_slippage_funding_and_sizing():
    t0 = pd.DatetimeIndex([pd.Timestamp("2025-01-15 07:00", tz=UTC)])
    t1 = pd.DatetimeIndex([pd.Timestamp("2025-01-15 09:00", tz=UTC)])        # one settlement (08:00) held
    B = perps.book_rows("BTC", t0, t1, [1.0], [100_000.0], [101_000.0], [85.0], [0.0001], ["time"])
    q = np.floor(100_000 / 85 / (100_000 * 0.001) + 1e-9) * 0.001
    assert abs(B.q[0] - q) < 1e-12 and q == 0.011
    e, x = 100_000 * 1.0002, 101_000 * 0.9998
    fund = 0.0001 * 1 * 100_000 * q
    fee_d = 0.0005 * 1.18 * (e + x) * q
    assert abs(B.net_delta[0] - ((x - e) * q - fee_d - fund) * 85) < 1e-6
    assert abs(B.net_coinswitch_nogst[0] - ((x - e) * q - 0.0005 * (e + x) * q - fund) * 85) < 1e-6
    # a short pays slippage the other way and its TDS sale is the entry
    S = perps.book_rows("BTC", t0, t1, [-1.0], [100_000.0], [99_000.0], [85.0], [0.0], ["time"])
    assert S.sale_inr[0] == pytest.approx(100_000 * 0.9998 * q * 85)
    assert perps.settlements(t0, t1)[0] == 1


def test_after_tax_is_per_trade_with_no_set_off():
    B = pd.DataFrame({"net_delta": [100.0, -50.0], "net_coinswitch_gst": [10.0, 0.0], "net_coinswitch_nogst": [0, 0]})
    T = run.add_tax(B)
    assert T.net_delta_at.tolist() == [100 * (1 - 0.312), -50.0]
    assert T.net_delta_at.sum() < B.net_delta.sum()


# ------------------------------------------------------------------------------------------------ look-ahead
def test_features_use_no_future_rows():
    m1 = walk_minutes(3 * 1440 + 300, seed=3)
    oi = pd.DataFrame({"ts": pd.date_range(m1.index[0], m1.index[-1], freq="5min"), "oi": 1.0})
    oi["oi"] = np.linspace(1, 2, len(oi))
    dv = pd.Series(50.0 + np.arange(80), index=pd.date_range(m1.index[0], periods=80, freq="h"))
    full = features.build(m1, oi, dv)
    cut = 2 * 1440 + 777
    part = features.build(m1.iloc[:cut], oi[oi.ts <= m1.index[cut - 1]], dv[dv.index <= m1.index[cut - 1]])
    pd.testing.assert_frame_equal(full.iloc[:cut], part, check_freq=False)
    # poisoning the future changes nothing up to the cut
    m2 = m1.copy()
    m2.iloc[cut:] = m2.iloc[cut:] * 3
    pd.testing.assert_frame_equal(features.build(m2, oi, dv).iloc[:cut], full.iloc[:cut], check_freq=False)


def test_dvol_is_used_only_after_its_hour_ends():
    m1 = walk_minutes(300)
    dv = pd.Series([40.0, 60.0], index=m1.index[[0, 60]])
    F = features.build(m1, None, dv)
    assert np.isnan(F.dvol.iloc[59])          # the hour starting at row 0 is known from row 60
    assert F.dvol.iloc[60] == 40.0 and F.dvol.iloc[119] == 40.0 and F.dvol.iloc[120] == 60.0


@pytest.mark.parametrize("rid", ["CC1", "CC2", "CC3", "CC4", "CC5", "CC6"])
def test_signals_use_no_future_rows(rid):
    full = market(6 * 1440, seed=11)
    cut = full.m1.index[4 * 1440 + 333]
    m2 = full.m1.copy()
    m2.loc[m2.index > cut] *= np.random.default_rng(5).uniform(0.9, 1.1, (int((m2.index > cut).sum()), 1))
    poisoned = R.Market("BTC", m1=m2, dvol=full.dvol, oi=full.oi, index=full.index, store=FakeStore())
    a, b = R.signals(R.RULES[rid], full), R.signals(R.RULES[rid], poisoned)
    assert (a["T"] <= cut).sum() > 0
    keep = lambda e: e[e["T"] <= cut].reset_index(drop=True)   # noqa: E731  (decisions known by the cut)
    pd.testing.assert_frame_equal(keep(a), keep(b))


def test_f1c_decisions_are_inside_the_window_and_on_5_minute_closes():
    mk = market(4 * 1440)
    D = mk.dec
    e = pd.DatetimeIndex(D.ts) + pd.Timedelta(minutes=1) + pd.Timedelta(hours=5, minutes=30)
    assert (e.hour < 9).all() and ((pd.DatetimeIndex(D.ts).minute + 1) % 5 == 0).all()


def test_perp_entries_fill_at_the_next_bar_open():
    m1 = walk_minutes(600, seed=4)
    T = [m1.index[100]]
    B = perps.f1c_trades("BTC", m1, T, [1], [0.5], [m1.index[160]], f1cperp.EXITS[3], data.fx_at,
                         lambda t: np.zeros(len(t)))
    assert B.entry[0] == m1.o.iloc[100] and B.entry_ts[0] == m1.index[100]


# ------------------------------------------------------------------------------------------------ RC1 breakout
def test_breakout_walk_reproduces_rc1_exactly_without_the_step_lock():
    b = walk_minutes(3000, seed=8, vol=0.01)
    o, h, l, c = (b[k].to_numpy(float) for k in ("o", "h", "l", "c"))
    ours = perps.brk_signals(o, h, l, c, perps.BRK_EXITS["orig"])
    theirs = perps.rc1s.breakout_spells(o, h, l, c)
    k = min(len(ours), len(theirs)) - 1
    assert k > 20
    assert [(e, x) for e, x, _, _ in ours[:k]] == [(e, x) for e, x, _ in theirs[:k]]
    for (_, _, p, w), (_, _, f) in zip(ours[:k], theirs[:k]):
        if w == "stop":
            assert abs(p - f) < 1e-9
        else:
            assert f is None


def test_breakout_step_lock_locks_profit():
    # entry 100 at bar 21 with ATR 1 -> stop 98, R = 2; the bar reaches 104 (+2R) -> lock +1R = 102 from the next bar
    n = 30
    o = np.full(n, 100.0)
    h, l, c = o + 0.5, o - 0.5, o.copy()
    a = np.ones(n)
    ll = np.full(n, 50.0)
    h[21] = 104.0
    l[22] = 101.0
    ex = perps.BrkExit("step_lock", step_lock=True)
    j, px, why = perps.brk_walk(21, o, h, l, c, a, ll, ex)
    assert (j, px, why) == (22, 100.0, "lock") or (j, why) == (22, "lock")
    assert px == min(o[22], 102.0)
    j0, px0, why0 = perps.brk_walk(21, o, h, l, c, a, ll, perps.BRK_EXITS["orig"])
    assert why0 != "lock"


# ------------------------------------------------------------------------------------------------ selection, twins
def test_select_one_at_a_time_max_per_day_and_day_cap():
    t = lambda s: pd.Timestamp(s, tz=UTC)  # noqa: E731
    B = pd.DataFrame({"coin": "BTC",
                      "entry_ts": [t("2025-01-01 19:00"), t("2025-01-01 19:30"), t("2025-01-01 21:00"),
                                   t("2025-01-01 22:00")],
                      "exit_ts": [t("2025-01-01 20:00"), t("2025-01-01 20:30"), t("2025-01-01 21:30"),
                                  t("2025-01-01 22:30")],
                      "net_delta": [-4000.0, 10.0, 5.0, 5.0]})
    S = R.select(B, 0.03, 5)
    assert S.entry_ts.tolist() == [t("2025-01-01 19:00")]          # busy, then the 3% day cap stops the rest
    S2 = R.select(B.assign(net_delta=1.0), 0.03, 2)
    assert len(S2) == 2 and S2.entry_ts.iloc[1] == t("2025-01-01 21:00")


def test_twin_entries_stay_inside_the_window_and_the_part():
    mk = market(70 * 1440, start="2024-11-15")
    rng = np.random.default_rng(1)
    for rid in ("CC1", "CC3", "CC6", "CC8"):
        rule = R.RULES[rid]
        e = R.twin_entries(rule, mk, "design", 300, rng)
        assert len(e) > 0
        assert all(RC.part_of(t) == "design" for t in R.entry_time(rule, e["T"]))
        if rid == "CC3":
            m = (pd.DatetimeIndex(e["T"]).hour * 60 + pd.DatetimeIndex(e["T"]).minute)
            assert ((m >= 540) & (m <= 660)).all()
        if rid == "CC6":
            assert (((pd.DatetimeIndex(e["T"]) + pd.Timedelta(hours=5, minutes=30)).hour) < 9).all()
        if rid == "CC8":
            assert (e.side == 1).all()


def test_twins_p_on_after_tax_delta():
    rule = R.RULES["CC4"]
    mk = market(70 * 1440, start="2024-11-15")
    mk.coin = "BTC"
    tr = {v: run.add_tax(R.run_rule(rule, v, {"BTC": mk}, "design")[0]) for v in rule.variants}
    tw = run.twins(rule, rule.variants, {"BTC": mk}, "design", tr)
    for v in rule.variants:
        assert 0 < tw[v]["p"] <= 1
        assert tw[v]["sizes"]["BTC"]["rule_trades"] == len(tr[v])


def test_empty_data_gives_no_trades():
    mk = market(2 * 1440, start="2023-01-01")           # entirely before 20 Nov 2024: nothing may trade
    for rid in ("CC1", "CC4", "CC6", "CC8"):
        B, sk = R.run_rule(R.RULES[rid], R.RULES[rid].original, {"BTC": mk}, "design")
        assert B.empty
    assert RC.summary(run.trades_list(pd.DataFrame(), "net"))["trades"] == 0


def test_option_rule_end_to_end_on_a_synthetic_chain():
    mk = market(40 * 1440, start="2024-11-15", seed=21)
    book = {}
    for d in pd.date_range("2024-11-16 12:00", periods=40, freq="D", tz=UTC):
        for k in range(50_000, 75_001, 200):
            for typ in ("C", "P"):
                book[("BTC", d, typ, k)] = flat_contract(d, 300.0)
    mk.chain = opt.Chain("BTC", FakeStore(book))
    for rid in ("CC1", "CC6"):
        B, sk = R.run_rule(R.RULES[rid], R.RULES[rid].original, {"BTC": mk}, "design")
        assert len(B) > 10
        assert (B.entry_ts == B["T"] + pd.Timedelta(minutes=1)).all()
        assert (B.exit_ts > B.entry_ts).all()
        assert (B.net_delta < 0).all()                         # flat marks: spread and fees only
        assert (B.net_coinswitch_gst < B.net_coinswitch_nogst).all()
        # one position at a time per coin
        assert (B.entry_ts.iloc[1:].to_numpy() >= B.exit_ts.iloc[:-1].to_numpy()).all()


# ------------------------------------------------------------------------------------------------ final lock
def test_final_refuses_without_design_and_refuses_a_second_read(tmp_path, monkeypatch):
    monkeypatch.setattr(RC, "RESULTS", tmp_path)
    with pytest.raises(SystemExit):
        run.final()
    (tmp_path / "crypto_design.json").write_text(json.dumps({"rules": {}}))
    (tmp_path / "final_read_crypto.json").write_text(json.dumps({"crypto-CC1:s40_step": "x"}))
    with pytest.raises(SystemExit):
        run.final()


def test_period_days():
    assert run.period_days("design") == 497
    assert run.period_days("final") == 195
