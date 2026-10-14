"""RC2 tests: the stop and lock are always there, fees and caps, expiry handling (Delta 12:00 UTC = 17:30 IST,
Deribit 08:00 UTC), the look-ahead guard on every entry rule, sizing, tax readings and BH.
python -m pytest research/hunt/rc2/test_rc2.py -q"""
import numpy as np
import pandas as pd
import pytest

from research.hunt.rc2 import config as C
from research.hunt.rc2 import costs, data, signals, sim, stats
from research.hunt.rc2.exits import DIRECTIONAL, HERO, Exit, run

SM = {"BTC": [0.0001, 0.02], "ETH": [0.00015, 0.02]}


# ---------- the stop and the lock are never missing ----------

def test_exit_without_stop_or_lock_is_refused():
    with pytest.raises(ValueError):
        Exit("nostop", 0.0, "step", rungs=((1.3, 1.0),))
    with pytest.raises(ValueError):
        Exit("nostop", 1.0, "trail", trail_start=1.3, trail_keep=0.75)
    with pytest.raises(ValueError):
        Exit("nolock", 0.4, "none")
    with pytest.raises(ValueError):
        Exit("emptyladder", 0.4, "step", rungs=())
    with pytest.raises(ValueError):
        Exit("badtrail", 0.4, "trail", trail_start=1.0, trail_keep=0.75)


def test_every_preregistered_exit_has_both():
    for ex in DIRECTIONAL + HERO:
        assert 0 < ex.stop < 1
        assert ex.lock in ("step", "trail")
        assert ex.lock_level(1.0, 100.0) >= 1.0      # a big peak always locks a profit


def bars(path):
    p = np.asarray(path, float)
    return p, p, p, p


def test_stop_hit_exits_at_the_stop():
    ex = DIRECTIONAL[0]                                   # -40%
    o = np.array([10, 9, 8, 7]); h = o + 0.2; l = np.array([9.8, 8.5, 5.0, 6.0]); c = o
    lvl, why, i, _ = run(ex, 10.0, o, h, l, c, 99)
    assert (lvl, why, i) == (6.0, "stop", 2)


def test_gap_through_the_stop_fills_at_the_open():
    ex = DIRECTIONAL[0]
    o = np.array([10, 9, 4.0]); lvl, why, i, _ = run(ex, 10.0, o, o + 0.1, o - 0.1, o, 99)
    assert (lvl, why, i) == (4.0, "stop", 2)


def test_step_lock_keeps_the_rung():
    ex = DIRECTIONAL[0]                                   # rungs 1.3->1.0, 1.6->1.3, 2.0->1.6
    o = np.array([10, 13, 16.5, 15, 13.5]); h = np.array([10.5, 14, 17, 15.5, 13.6]); l = np.array([9.9, 12.5, 15.5, 14, 12.5])
    lvl, why, i, peak = run(ex, 10.0, o, h, l, o, 99)
    assert why == "lock" and lvl == pytest.approx(13.0) and i == 4 and peak == pytest.approx(1.7)


def test_trailing_lock_follows_the_peak():
    ex = DIRECTIONAL[1]                                   # active at 1.3x, keeps 75% of the peak, floor 1.0x
    o = np.array([10, 14, 20, 16]); h = np.array([10, 15, 20, 16]); l = np.array([10, 13.5, 19, 14.0])
    lvl, why, i, _ = run(ex, 10.0, o, h, l, o, 99)
    assert why == "lock" and lvl == pytest.approx(15.0) and i == 3


def test_same_bar_high_and_low_is_read_cautiously():
    """A bar that spikes up and down: the stop is checked first and the spike does not lock anything."""
    ex = DIRECTIONAL[0]
    o = np.array([10, 10]); h = np.array([10, 30]); l = np.array([10, 5])
    lvl, why, i, peak = run(ex, 10.0, o, h, l, o, 99)
    assert (lvl, why, i, peak) == (6.0, "stop", 1, 1.0)


def test_time_exit_at_the_deadline_open():
    ex = DIRECTIONAL[0]
    o = np.array([10, 10.5, 11, 11.2]); lvl, why, i, _ = run(ex, 10.0, o, o, o, o, 2)
    assert (lvl, why, i) == (11.0, "time", 2)


# ---------- fees, cap, spread ----------

def test_fee_is_min_of_notional_and_premium_cap_plus_gst():
    # ATM-ish: 0.01% of notional binds. BTC 80,000, premium 400 (0.5%), 10 lots of 0.001 BTC
    f = costs.fee("BTC", 400.0, 80_000.0, 10)
    assert f == pytest.approx(min(0.0001 * 80_000 * 0.01, 0.035 * 400 * 0.01) * 1.18)
    assert f == pytest.approx(0.08 * 1.18)
    # cheap option: the 3.5%-of-premium cap binds
    f2 = costs.fee("BTC", 20.0, 80_000.0, 10)
    assert f2 == pytest.approx(0.035 * 20 * 0.01 * 1.18)
    assert costs.fee("ETH", 0.0, 2500.0, 5) == 0.0


def test_spread_has_a_one_tick_floor_and_sells_never_go_negative():
    assert costs.half_spread("BTC", 0.0, 0.0, SM) == pytest.approx(C.TICK["BTC"] / 2)
    assert costs.sell_fill("BTC", 0.05, 80_000, SM) == 0.0
    b = costs.buy_fill("BTC", 300.0, 80_000, SM)
    s = costs.sell_fill("BTC", 300.0, 80_000, SM)
    assert b > 300 > s


# ---------- expiry handling ----------

def test_expiry_times():
    E = pd.Timestamp("2026-10-13 12:00", tz="UTC")
    assert E.hour == C.DELTA_EXPIRY_HOUR_UTC
    assert E.tz_convert("Asia/Kolkata").strftime("%H:%M") == "17:30"
    assert pd.Timestamp("2026-10-13 08:00", tz="UTC").tz_convert("Asia/Kolkata").strftime("%H:%M") == "13:30"
    assert C.DERIBIT_EXPIRY_HOUR_UTC == 8


def fake_panel(coin, E, typ_k_path):
    times = pd.date_range(E - pd.Timedelta(hours=30), E - C.BAR, freq="5min")
    rows = []
    for (typ, K), f in typ_k_path.items():
        for i, t in enumerate(times):
            v = f(i, len(times))
            rows.append(dict(kind="mark", typ=typ, K=K, time=int(t.timestamp()), open=v, high=v * 1.01, low=v * 0.99, close=v))
    return data.Panel(coin, E, pd.DataFrame(rows))


@pytest.fixture
def fake_chain(monkeypatch):
    panels = {}

    def get(coin, E):
        if E not in panels:
            panels[E] = fake_panel(coin, E, {("C", 80_000): lambda i, n: 300.0 + i, ("P", 80_000): lambda i, n: 300.0,
                                             ("C", 81_000): lambda i, n: 40.0 + (i % 7), ("P", 79_000): lambda i, n: 30.0})
        return panels[E]
    monkeypatch.setattr(data, "panel", get)
    return panels


def test_pick_expiry_same_day_or_next(fake_chain):
    T = pd.Timestamp("2026-05-05 11:00", tz="UTC")
    P, _ = sim.pick_expiry("BTC", T, 1.5)
    assert P.expiry == pd.Timestamp("2026-05-06 12:00", tz="UTC")
    P, _ = sim.pick_expiry("BTC", T, 1 / 6)
    assert P.expiry == pd.Timestamp("2026-05-05 12:00", tz="UTC")
    P, _ = sim.pick_expiry("BTC", pd.Timestamp("2026-05-05 12:00", tz="UTC"), 0.1)
    assert P.expiry == pd.Timestamp("2026-05-06 12:00", tz="UTC")


def test_no_position_survives_past_1750_ist_on_expiry_day(fake_chain):
    spot = pd.Series(80_000.0, index=pd.date_range("2026-05-04", "2026-05-07", freq="5min", tz="UTC"))
    ent = pd.DataFrame({"T": [pd.Timestamp("2026-05-05 09:00", tz="UTC"), pd.Timestamp("2026-05-05 10:30", tz="UTC")],
                        "side": ["C", "C"]})
    for ex in DIRECTIONAL:
        t = sim.simulate("BTC", "R1_asia_orb", ent, ex, spot, SM, one_at_a_time=False)
        assert len(t) == 2
        assert (t.t_exit <= t.expiry - C.LAST_EXIT_BEFORE_EXPIRY).all()
        assert set(t.reason) <= {"stop", "lock", "time"}
        last = t.t_exit.max().tz_convert("Asia/Kolkata")
        assert (last.hour, last.minute) <= (17, 20)


def test_hero_picks_a_cheap_otm_option(fake_chain):
    spot = pd.Series(80_000.0, index=pd.date_range("2026-05-04", "2026-05-07", freq="5min", tz="UTC"))
    ent = pd.DataFrame({"T": [pd.Timestamp("2026-05-05 09:30", tz="UTC")], "side": ["C"]})
    t = sim.simulate("BTC", "R6_hero", ent, HERO[0], spot, SM)
    assert t.K.iloc[0] == 81_000 and t.expiry.iloc[0] == pd.Timestamp("2026-05-05 12:00", tz="UTC")


# ---------- look-ahead guard on every entry rule ----------

def synthetic(seed=1, days=75):
    rng = np.random.default_rng(seed)
    t = pd.date_range("2025-01-01", periods=days * 288, freq="5min", tz="UTC")
    c = 50_000 * np.exp(np.cumsum(rng.normal(0, 0.0025, len(t)) + np.where(rng.random(len(t)) < 0.01, rng.normal(0, 0.01, len(t)), 0)))
    o = np.r_[c[0], c[:-1]]
    b = pd.DataFrame({"o": o, "h": np.maximum(o, c) * (1 + rng.random(len(t)) * 0.001),
                      "l": np.minimum(o, c) * (1 - rng.random(len(t)) * 0.001), "c": c}, index=t)
    hrs = pd.date_range(t[0], t[-1], freq="h")
    dvol = pd.Series(50 + np.cumsum(rng.normal(0, 1.5, len(hrs))).clip(-30, 60), index=hrs)
    fund = pd.Series(rng.normal(0, 1e-4, len(hrs)), index=hrs)
    return b, dvol, fund


def truncate(b, dvol, fund, cut):
    """Everything not yet known at the decision time cut + 5 min (the close of the bar starting at cut)."""
    T = cut + C.BAR
    return b[b.index <= cut], dvol[dvol.index + pd.Timedelta(hours=1) <= T], fund[fund.index + pd.Timedelta(hours=1) <= T]


def poison(b, dvol, fund, cut):
    T = cut + C.BAR
    b2, d2, f2 = b.copy(), dvol.copy(), fund.copy()
    fut = b2.index > cut
    b2.loc[fut, ["o", "h", "l", "c"]] = b2.loc[fut, ["o", "h", "l", "c"]].to_numpy() * np.random.default_rng(9).uniform(0.5, 1.5, (fut.sum(), 1))
    d2[d2.index + pd.Timedelta(hours=1) > T] = 500.0
    f2[f2.index + pd.Timedelta(hours=1) > T] = 0.05
    return b2, d2, f2


@pytest.mark.parametrize("rule", list(signals.RULES))
def test_lookahead_guard(rule):
    b, dvol, fund = synthetic()
    full = signals.entries(rule, b, dvol, fund)
    assert len(full) > 0, rule
    rng = np.random.default_rng(3)
    cuts = list(full["T"].sample(min(6, len(full)), random_state=1) - C.BAR) + list(b.index[rng.integers(30 * 288, len(b), 6)])
    for cut in cuts:
        T = cut + C.BAR
        want = full[full["T"] <= T].reset_index(drop=True)
        for mode in ("cut", "poison"):
            args = truncate(b, dvol, fund, cut) if mode == "cut" else poison(b, dvol, fund, cut)
            got = signals.entries(rule, *args)
            got = got[got["T"] <= T].reset_index(drop=True)
            pd.testing.assert_frame_equal(got, want, obj=f"{rule} {mode} {cut}")


def test_entries_only_inside_their_windows():
    b, dvol, fund = synthetic(2)
    for name, rule in signals.RULES.items():
        e = signals.entries(name, b, dvol, fund)
        starts = pd.DatetimeIndex(e["T"] - C.BAR)
        assert signals.in_window(rule, starts).all(), name


# ---------- money, tax, BH ----------

def tr(rows):
    return pd.DataFrame(rows, columns=["T", "coin", "cost_lot", "pnl_lot", "sell", "ret", "reason", "post_max", "level", "mid"])


def test_sizing_two_percent_and_daily_cap():
    t0 = pd.Timestamp("2026-01-05 03:00", tz="UTC")
    # one lot costs $0.50 = Rs 48.39; Rs 25k x 2% = Rs 500 -> 10 lots
    rows = [(t0 + pd.Timedelta(hours=i), "BTC", 0.5, -0.5, 0.0, -1.0, "stop", np.nan, 0, 1) for i in range(5)]
    m = stats.money(tr(rows), 25_000)
    assert m.lots.iloc[0] == 10
    assert (m.lots.iloc[0] * 0.5 * C.USDINR) <= 500
    # each loses Rs 483.9; the cap (Rs 1,250) is reached after 3 trades, the rest of the IST day is skipped
    assert list(m.lots) == [10, 10, 10, 0, 0]
    # too dear for the account: skipped
    assert stats.money(tr([(t0, "BTC", 10.0, 1, 0, 0.1, "lock", 1, 1, 1)]), 25_000).lots.iloc[0] == 0


def test_tax_readings():
    t = pd.DataFrame({"T": pd.to_datetime(["2025-05-01", "2025-06-01", "2026-05-01"], utc=True), "lots": [1, 1, 1],
                      "inr": [1000.0, -1500.0, 800.0], "sale_inr": [2000.0, 0.0, 1800.0]})
    x = stats.taxes(t)
    assert x["net"] == pytest.approx(300)
    assert x["vda"] == pytest.approx(300 - 0.312 * 1800)               # losses never set off
    assert x["business"] == pytest.approx(300 - 0.312 * 300)           # FY25 -500 carried into FY26's +800
    assert x["tds"] == pytest.approx(0.01 * 3800)


def test_bh_matches_the_textbook():
    q = stats.bh([0.01, 0.04, 0.03, 0.20])
    assert q == pytest.approx([0.04, 0.16 / 3, 0.16 / 3, 0.20])   # 0.03 x 4/2 = 0.06 > 0.04 x 4/3


def test_spread_model_takes_the_wider_fit(tmp_path):
    import json
    p = tmp_path / "live.json"
    p.write_text(json.dumps({"BTC": [0.00001, 0.03], "ETH": [0.001, 0.001]}))
    m = costs.spread_model(str(p))
    assert m["BTC"] == [costs.DEFAULT_SPREAD["BTC"][0], 0.03]
    assert m["ETH"] == [0.001, costs.DEFAULT_SPREAD["ETH"][1]]
    assert costs.spread_model(str(tmp_path / "missing.json")) == costs.DEFAULT_SPREAD


def test_no_entry_on_a_listing_glitch(monkeypatch):
    """A strike's first candle can open far from its real price (Delta listing glitch, 2 Feb 2025: open 25.6, close
    1,664). No entry in the first 15 minutes of a strike, nor on a bar that opens > 2x away from the last close."""
    E = pd.Timestamp("2026-05-05 12:00", tz="UTC")
    T = pd.Timestamp("2026-05-05 02:00", tz="UTC")
    times = pd.date_range(E - pd.Timedelta(hours=30), E - C.BAR, freq="5min")
    rows = []
    for t in times:
        if t >= T:
            o = 25.6 if t == T else 1650.0
            rows.append(dict(kind="mark", typ="P", K=80_000, time=int(t.timestamp()), open=o, high=1700.0, low=min(o, 1600.0), close=1650.0))
    P = data.Panel("BTC", E, pd.DataFrame(rows))
    i = P.bar(T)
    assert not P.tradable(("P", 80_000), i)
    assert not P.tradable(("P", 80_000), i + 2)
    assert P.tradable(("P", 80_000), i + 3)
    monkeypatch.setattr(data, "panel", lambda coin, e: P if e == E else None)
    assert sim.pick_strike(P, "P", 80_100.0, i, "atm") is None
    assert sim.pick_strike(P, "P", 80_100.0, i + 3, "atm") == 80_000
    P.arr[("P", 80_000)][i + 5, 0] = 5000.0          # a jump in the series
    assert not P.tradable(("P", 80_000), i + 5)
