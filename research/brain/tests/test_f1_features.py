"""F1 factory features (14 Oct 2026): definitions checked by hand on toys and on the release fixture. The look-ahead
guard for all of them runs in test_lookahead.py (every registered feature)."""
import numpy as np
import pandas as pd
import pytest

from research.brain.features import REGISTRY, compute, load_all
from research.brain.features.factory_f import buildup_codes, same_strike_lag
from research.brain.features.registry import Ctx

NEW = ["ema9_20_spread_pct", "ema9_20_cross_age", "c5_supertrend_dir", "c5_rsi14", "squeeze_on", "squeeze_run",
       "c5_squeeze_fire", "atr_ratio_5_50", "day_range_vs_adr10", "cpr_width_pct", "cpr_state", "prev_nr7",
       "ovwap_dist_pct", "ovwap_z", "tpavg_2d_dist_pct", "bu_atm_ce_15m", "bu_ce_short_buildup_share",
       "bu_pe_short_buildup_share", "bu_writing_bias_15m", "pcr_oi_chg_15m", "pcr_oi_chg_day",
       "oi_call_wall_dist_pct", "oi_put_wall_shift_day", "straddle_chg_15m_pct", "iv_skew_chg_15m", "gex_net_norm",
       "gex_near_share", "fut_oi_chg_day_pct", "fut_buildup_15m", "vix_pct_252"]


@pytest.fixture(scope="module")
def F(built):
    load_all()
    return compute(built["M"], built["C"], "BANKNIFTY", NEW).set_index("ts")


def test_all_registered():
    load_all()
    assert not set(NEW) - set(REGISTRY)


def test_buildup_codes_four_types_and_unknown():
    dp = np.array([1, -1, 1, -1, 0, 1, np.nan, 2])
    doi = np.array([5, 5, -5, -5, 5, 0, 5, np.nan])
    c = buildup_codes(dp, doi)
    assert list(c[:6]) == [1, 2, 3, 4, 0, 0]
    assert np.isnan(c[6]) and np.isnan(c[7])                   # unknown price or OI: no type, never a guess


def _toy(n_days=9, und="NIFTY", step=50.0):
    """A toy cube: index flat at 20,000 except where a test changes it; chain built from the ATM each minute."""
    rows = []
    for d in range(n_days):
        day = pd.Timestamp("2025-01-06") + pd.Timedelta(days=d)
        for k in range(375):
            rows.append(dict(ts=day + pd.Timedelta(hours=9, minutes=15 + k), day=day.date(), minute=k,
                             open=20000.0, high=20000.0 + 10 * (d + 1), low=20000.0 - 10 * (d + 1), close=20000.0,
                             flag="ok"))
    return pd.DataFrame(rows)


def test_nr7_and_cpr_from_previous_sessions_only():
    M = _toy(9)
    # day widths grow 20, 40, ... ; make day 7 the narrowest of the last 7
    M.loc[M.day == M.day.unique()[7], ["high", "low"]] = [20001.0, 19999.0]
    ctx = Ctx(M, None, "NIFTY")
    load_all()
    v = REGISTRY["prev_nr7"].fn(ctx)
    per_day = pd.Series(v).groupby(ctx.dayid).first()
    assert np.isnan(per_day[6])                                 # fewer than 7 earlier sessions: unknown
    assert per_day[8] == 1.0                                    # yesterday (day 7) was the NR7
    w = REGISTRY["cpr_width_pct"].fn(ctx)
    assert np.isnan(w[ctx.dayid == 0]).all()                    # first session: no previous H/L/C
    # flat close at the midpoint -> pivot == BC == TC -> zero width
    assert np.allclose(w[ctx.dayid == 2], 0.0)
    s = REGISTRY["cpr_state"].fn(ctx)
    assert set(np.unique(s[ctx.dayid == 2])) == {0.0}


def test_same_strike_lag_follows_the_strike_when_atm_moves():
    M = _toy(1)
    M.loc[M.minute >= 20, ["open", "high", "low", "close"]] = 20100.0       # ATM moves up two strikes at 09:35
    offs = range(-10, 11)
    ch = []
    for _, r in M.iterrows():
        atm = round(r.close / 50) * 50
        for o in offs:
            for side in ("C", "P"):
                K = atm + o * 50
                ch.append(dict(ts=r.ts, offset=o, side=side, strike=K, oi=K + (1 if side == "P" else 0),
                               ltp=1.0, flag="ok"))
    C = pd.DataFrame(ch)
    ctx = Ctx(M, C, "NIFTY")
    lag = same_strike_lag(ctx, "oi", "C", 15)
    t = int(np.flatnonzero(ctx.minute == 25)[0])
    K_now = ctx.chain_arr("strike", "C")[t]
    # OI equals the strike in this toy, so the lagged value of the same strike equals the strike itself
    ok = np.isfinite(lag[t])
    assert ok.sum() == 19                                        # the two top strikes were outside the chain at t-15
    assert np.allclose(lag[t][ok], K_now[ok])
    assert np.isnan(lag[:15]).all()                              # nothing before 15 minutes of the session


def test_fixture_values_in_range(F):
    assert F.squeeze_on.dropna().isin([0, 1]).all()
    assert (F.squeeze_run.dropna() >= 0).all()
    assert F.c5_supertrend_dir.dropna().isin([-1, 1]).all()
    assert F.c5_squeeze_fire.dropna().isin([-1, 0, 1]).all()
    assert F.c5_rsi14.dropna().between(0, 100).all()
    assert F.cpr_state.dropna().isin([-1, 0, 1]).all()
    assert F.bu_atm_ce_15m.dropna().isin([0, 1, 2, 3, 4]).all()
    for c in ("bu_ce_short_buildup_share", "bu_pe_short_buildup_share", "gex_near_share"):
        assert F[c].dropna().between(0, 1).all(), c
    for c in ("bu_writing_bias_15m", "gex_net_norm"):
        assert F[c].dropna().between(-1, 1).all(), c
    assert F.gex_net_norm.notna().mean() > 0.5                   # the fixture's chain has gamma and OI
    assert F.ovwap_dist_pct.notna().mean() > 0.9
    assert (F.atr_ratio_5_50.dropna() > 0).all()
    # the release fixture has no futures OI: NaN, never zero
    assert F.fut_oi_chg_day_pct.isna().all() and F.fut_buildup_15m.isna().all()
    assert F.vix_pct_252.isna().all()                             # 4 sessions < the 200 it needs


def test_cross_age_sign_matches_spread(F):
    both = F[["ema9_20_spread_pct", "ema9_20_cross_age"]].dropna()
    both = both[both.ema9_20_spread_pct.abs() > 1e-9]
    assert (np.sign(both.ema9_20_spread_pct) == np.sign(both.ema9_20_cross_age)).mean() > 0.99


def test_pcr_change_starts_at_zero_each_session(F):
    first = F.pcr_oi_chg_day.groupby(F.index.normalize()).apply(lambda s: s.dropna().iloc[0])
    assert np.allclose(first.values, 0.0)
