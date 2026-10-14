"""Gamma exposure core (research/brain/gex.py) and its L4 features (features/gex_f.py). R28, 14 Oct 2026."""
from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.brain import bs, config, gex
from research.brain.features import REGISTRY, compute, load_all

DAY, EXP = date(2024, 3, 5), date(2024, 3, 7)
STEP = 50.0
GEX_FEATURES = ["gex_net_cr", "gex_gross_cr", "gex_s2_cr", "gex_ratio", "gex_norm_20d", "gex_flip_dist_pct",
                "gex_spot_above_flip", "gex_wall_pos_dist_pct", "gex_wall_neg_dist_pct", "gex_wall_abs_dist_pct",
                "gex_edge_share", "oi_edge_share"]


def synth(spot0=22000.0, iv=0.14, oi_c=None, oi_p=None, minutes=range(375), drift=0.0, expiry=EXP, day=DAY,
          vol=100.0):
    """One session of a synthetic ATM+-10 chain priced by Black-Scholes at a flat IV, traded every minute."""
    rows = []
    spot = np.full(375, np.nan)
    for m in minutes:
        s = spot0 * (1 + drift * m)
        spot[m] = s
        atm = round(s / STEP) * STEP
        T = gex.years_to_expiry(day, np.array([m]), expiry)[0]
        for o in range(-10, 11):
            k = atm + o * STEP
            for side in ("C", "P"):
                px = float(bs.price(s, k, T, iv, side == "C", config.RISK_FREE, config.DIV_YIELD))
                oi = (oi_c if side == "C" else oi_p)
                oi = oi(o) if callable(oi) else (1e6 if oi is None else oi)
                rows.append(dict(minute=m, offset=o, side=side, strike=k, close=round(px, 2), volume=vol, oi=float(oi)))
    return pd.DataFrame(rows), spot


def run(df, spot, expiry=EXP, day=DAY, flip=True):
    d = gex.last_trade(df)
    d["n"] = gex.contract_flow(d)
    return gex.session(d, spot, day, expiry, flip)


# ------------------------------------------------------------------------------------------------ units and signs
def test_gex_of_one_contract_is_gamma_oi_spot2_pct():
    S, K, T, sig = 22000.0, 22000.0, 2 / 365, 0.14
    _, g, _, _ = bs.greeks(S, K, T, sig, True, config.RISK_FREE, config.DIV_YIELD)
    assert gex._gamma_rs(S, K, T, sig) == pytest.approx(g * S * S * 0.01)
    # ~Rs 9-10k of index delta per unit per 1% move at the money two days out (hand check of the units)
    assert 8000 < gex._gamma_rs(S, K, T, sig) < 11000


def test_sign_convention_calls_plus_puts_minus():
    df, spot = synth(oi_c=1e6, oi_p=0.0, minutes=range(0, 20))
    r = run(df, spot, flip=False)
    assert (r.net_cr.iloc[:20] > 0).all() and np.allclose(r.ratio.iloc[:20], 1.0)
    assert np.allclose(r.net_cr.iloc[:20], r.gross_cr.iloc[:20])
    df, spot = synth(oi_c=0.0, oi_p=1e6, minutes=range(0, 20))
    r = run(df, spot, flip=False)
    assert (r.net_cr.iloc[:20] < 0).all() and np.allclose(r.ratio.iloc[:20], -1.0)
    assert r.net_cr.iloc[20:].isna().all()                       # no rows -> NaN, never 0


def test_iv_round_trip_recovers_the_flat_iv():
    df, spot = synth(iv=0.17, minutes=range(0, 3))
    d = gex.last_trade(df)
    s = d[(d.minute == 1) & (d.offset.abs() <= 3)]
    T = gex.years_to_expiry(DAY, s.minute.values, EXP)
    iv = bs.implied_vol(s.px.values, np.full(len(s), spot[1]), s.strike.values, T, (s.side == "C").values, config.RISK_FREE,
                        config.DIV_YIELD, min_vega=config.IV_MIN_VEGA)
    assert np.allclose(iv, 0.17, atol=2e-3)


def test_walls_and_edge_shares():
    # calls heavy 4 strikes above ATM, puts heavy 3 below: walls there; uniform OI -> edge OI share = 8/42
    df, spot = synth(oi_c=lambda o: 9e6 if o == 4 else 1e5, oi_p=lambda o: 9e6 if o == -3 else 1e5,
                     minutes=range(0, 5))
    r = run(df, spot, flip=False)
    atm = round(22000 / STEP) * STEP
    assert r.wall_pos_strike.iloc[2] == atm + 4 * STEP
    assert r.wall_neg_strike.iloc[2] == atm - 3 * STEP
    df, spot = synth(minutes=range(0, 5))
    r = run(df, spot, flip=False)
    assert r.oi_edge_share.iloc[2] == pytest.approx(8 / 42)
    assert 0 < r.edge_share.iloc[2] < 8 / 42                       # far strikes carry less gamma than ATM ones


def test_flip_level_between_put_and_call_heavy_strikes():
    # puts heavy below, calls heavy above: S1 < 0 when spot sits near the puts, > 0 near the calls; the flip lies between
    df, spot = synth(oi_c=lambda o: 5e6 if o >= 2 else 1e4, oi_p=lambda o: 5e6 if o <= -2 else 1e4, minutes=range(0, 3))
    r = run(df, spot)
    f = r.flip_dist_pct.iloc[1]
    assert np.isfinite(f) and abs(f) < 0.3                          # between ATM-2 and ATM+2 (~ +-0.45%)
    # only calls: S1 > 0 everywhere on the grid -> no flip
    df, spot = synth(oi_c=1e6, oi_p=0.0, minutes=range(0, 3))
    r = run(df, spot)
    assert np.isnan(r.flip_dist_pct.iloc[1]) and np.isnan(r.spot_above_flip.iloc[1])


def test_fill_nearest_prefers_the_lower_strike_on_a_tie():
    a = np.array([[np.nan, 1.0, np.nan, 3.0, np.nan]])
    assert gex.fill_nearest(a).tolist() == [[1.0, 1.0, 1.0, 3.0, 3.0]]
    assert np.isnan(gex.fill_nearest(np.full((1, 3), np.nan))).all()


# ------------------------------------------------------------------------------------------------ S2 flow
def test_customer_flow_signs():
    df = pd.DataFrame(dict(minute=[0, 1, 2, 3, 0, 1], strike=[100.0] * 4 + [200.0] * 2, side=["C"] * 6,
                           oi=[10, 20, 30, 25, 50, 60], px=[5.0, 6.0, 5.5, 5.5, 9.0, 8.0]))
    n = gex.contract_flow(df)
    # 100 C: +10 (OI up, price up), -10 (OI up, price down), 0 (flat price); 200 C: -10
    assert n.tolist() == [0.0, 10.0, 0.0, 0.0, 0.0, -10.0]


def test_s2_is_zero_at_the_open_and_negative_when_customers_buy():
    df, spot = synth(minutes=range(0, 30), drift=0.0002, oi_c=None)
    df.loc[df.side == "C", "oi"] += df.loc[df.side == "C", "minute"] * 1000     # call OI builds while calls rise
    r = run(df, spot, flip=False)
    assert r.s2_cr.iloc[0] == 0.0
    assert r.s2_cr.iloc[29] < 0


# ------------------------------------------------------------------------------------------------ time and staleness
def test_time_to_expiry_includes_the_intraday_time_and_ends_at_1530():
    T = gex.years_to_expiry(EXP, np.array([0, 374]), EXP)
    assert T[0] == pytest.approx((375 - 1) / (365 * 1440))       # end of 09:15 bar to 15:30
    assert np.isnan(T[1])                                         # the 15:29 bar ends at 15:30: no time left
    assert np.isnan(gex.years_to_expiry(DAY, np.array([0]), None)).all()
    assert np.isnan(gex.years_to_expiry(EXP, np.array([0]), DAY)).all()        # expired series


def test_last_trade_staleness_and_untraded_minutes():
    df = pd.DataFrame(dict(minute=[0, 1, 40, 41], strike=[1.0] * 4, side=["C"] * 4, close=[5.0, 6.0, 7.0, 8.0],
                           volume=[10, 0, 0, 5]))
    d = gex.last_trade(df)
    assert d.px.tolist()[:2] == [5.0, 5.0]           # minute 1 had no trade: the 09:15 price, 1 minute old
    assert np.isnan(d.px.iloc[2])                    # 40 minutes old -> no price
    assert d.px.iloc[3] == 8.0 and d.age.iloc[3] == 0


def test_too_few_contracts_or_market_closed_gives_nan():
    df, spot = synth(minutes=range(0, 3))
    keep = df[(df.offset.abs() <= 1)]                # 6 contracts < MIN_CONTRACTS
    assert run(keep, spot, flip=False).net_cr.isna().all()
    empty = df.iloc[:0]
    assert run(empty, np.full(375, np.nan), flip=False).net_cr.isna().all()
    df, spot = synth(minutes=range(0, 3), vol=0.0)    # nothing ever traded today: no prices, no GEX
    assert run(df, spot, flip=False).net_cr.isna().all()


def test_expiry_afternoon_atm_gamma_is_larger_than_the_day_before():
    # OI at the money only (with uniform OI the strike-sum of gamma barely depends on T: it integrates to ~1 / step)
    atm_only = dict(oi_c=lambda o: 1e6 if o == 0 else 0.0, oi_p=lambda o: 1e6 if o == 0 else 0.0)
    a, sa = synth(minutes=range(300, 302), day=EXP, expiry=EXP, **atm_only)
    b, sb = synth(minutes=range(300, 302), day=DAY, expiry=EXP, **atm_only)
    assert run(a, sa, EXP, EXP, False).gross_cr.iloc[301] > 2 * run(b, sb, EXP, DAY, False).gross_cr.iloc[301]


def test_rolling_norm_uses_previous_sessions_only():
    a = np.ones((25, 375))
    a[24] = 3.0
    r = gex.rolling_norm(a, 20, 10)
    assert np.isnan(r[:10]).all() and np.allclose(r[10:24], 1.0) and np.allclose(r[24], 3.0)
    b = a.copy()
    b[24] = 1e9                                       # the current day's value never enters its own median
    assert np.allclose(gex.rolling_norm(b, 20, 10)[24], 1e9)


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_gex_at_t_is_unchanged_when_the_future_is_deleted_or_corrupted():
    df, spot = synth(minutes=range(0, 120), drift=0.00005,
                     oi_c=lambda o: 2e6 + 1e5 * o, oi_p=lambda o: 2e6 - 1e5 * o)
    full = run(df, spot)
    rng = np.random.default_rng(3)
    for t in (5, 59, 100):
        past = df[df.minute <= t]
        s2 = spot.copy()
        s2[t + 1:] = np.nan
        cut = run(past, s2)
        bad = df.copy()
        fut = bad.minute > t
        bad.loc[fut, "close"] = bad.loc[fut, "close"] * rng.uniform(0.2, 5, fut.sum())
        bad.loc[fut, "oi"] = bad.loc[fut, "oi"] * rng.uniform(0, 10, fut.sum())
        s3 = spot.copy()
        s3[t + 1:] *= 1.5
        wild = run(bad, s3)
        for c in gex.OUT_COLS:
            a, b, w = full[c].values[:t + 1], cut[c].values[:t + 1], wild[c].values[:t + 1]
            assert np.allclose(a, b, equal_nan=True), (c, t, "deleted")
            assert np.allclose(a, w, equal_nan=True), (c, t, "corrupted")


# ------------------------------------------------------------------------------------------------ the L4 features
def test_gex_features_registered_in_the_options_family():
    load_all()
    for n in GEX_FEATURES:
        assert n in REGISTRY and REGISTRY[n].family == "options", n


def test_gex_features_on_the_fixture_cube(built):
    F = compute(built["M"], built["C"], "BANKNIFTY", GEX_FEATURES)
    M = built["M"].sort_values("ts").reset_index(drop=True)
    net = F.gex_net_cr.values
    assert np.isfinite(net).mean() > 0.5                     # the fixture's chain gives a GEX most minutes
    g = F.gex_gross_cr.values
    ok = np.isfinite(net)
    assert (np.abs(net[ok]) <= g[ok] + 1e-6).all()
    assert ((F.gex_ratio[ok].abs()) <= 1 + 1e-9).all()
    assert np.isnan(F.gex_norm_20d).all()                    # 4 sessions < the 10 the norm needs
    # the expiry day (27 Mar) carries more gamma per unit than the session before it, at the same minute
    d = pd.to_datetime(M.ts).dt.date.values
    mins = M.minute.values
    a = g[(d == date(2025, 3, 27)) & (mins == 240)]
    b = g[(d == date(2025, 3, 26)) & (mins == 240)]
    if np.isfinite(a).all() and np.isfinite(b).all():
        assert a[0] > b[0]
    s2_open = F.gex_s2_cr.values[mins == 0]
    assert np.all((s2_open == 0) | np.isnan(s2_open))
