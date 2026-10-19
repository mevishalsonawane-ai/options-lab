import numpy as np
import pandas as pd

from research.hunt.r87_candle_anatomy import build as B
from research.hunt.r87_candle_anatomy import candles as CA
from research.hunt.r87_candle_anatomy import cfg
from research.hunt.r87_candle_anatomy import context as CX
from research.hunt.r87_candle_anatomy import outcome as OC
from research.hunt.r87_candle_anatomy import stats as ST
from research.hunt.r87_candle_anatomy.tests import synth


def test_trend_state_and_tod():
    up = np.linspace(100, 200, 120)
    st, e = CX.trend_state(up)
    assert (st[:59] == 0).all() and (st[70:] == 1).all()
    st2, _ = CX.trend_state(up[::-1])
    assert (st2[70:] == -1).all()
    assert list(CX.tod_bucket(np.array([0, 44, 45, 135, 255, 330, 374]))) == [
        "open", "open", "morning", "midday", "afternoon", "close", "close"]


def test_minute_context_levels_vwap_or_gap():
    m = synth.minutes(days=3)
    mc = CX.minute_context(m, "NIFTY")
    d = mc.day.unique()
    d0, d1 = mc[mc.day == d[0]], mc[mc.day == d[1]]
    assert np.isnan(d0.pdh).all()
    assert np.allclose(d1.pdh, d0.h.max()) and np.allclose(d1.pdc, d0.c.iloc[-1])
    assert np.isclose(d1.gap.iloc[0], d1.o.iloc[0] / d0.c.iloc[-1] - 1)
    # opening range only from 09:30, equal to the 09:15-09:29 high / low
    r = (d1.ts.dt.hour * 60 + d1.ts.dt.minute - cfg.OPEN_MIN)
    assert d1[r < 15].orh.isna().all()
    assert np.allclose(d1[r >= 15].orh, d1[r < 15].h.max())
    # day high before the minute excludes the minute itself
    assert np.isclose(d1.dhi.iloc[5], d1.h.iloc[:5].max())
    # vwap = activity-weighted typical price so far
    tp = (d1.h + d1.l + d1.c) / 3
    assert np.isclose(d1.vwap.iloc[9], (tp.iloc[:10] * d1.act.iloc[:10]).sum() / d1.act.iloc[:10].sum())
    # no activity -> time-weighted
    mc2 = CX.minute_context(synth.minutes(days=1, chain=False), "NIFTY")
    tp2 = (mc2.h + mc2.l + mc2.c) / 3
    assert np.isclose(mc2.vwap.iloc[9], tp2.iloc[:10].mean())
    # the prior-day profile: VAL <= POC <= VAH, inside yesterday's range
    x = d1.iloc[0]
    assert x.pd_val <= x.pd_poc <= x.pd_vah and d0.l.min() - 10 <= x.pd_val and x.pd_vah <= d0.h.max() + 10


def test_activity_usual_uses_earlier_sessions_only():
    m = CX.minute_context(synth.minutes(days=25), "NIFTY")
    k = B.candle_table(m, "NIFTY", 15, set())
    days = sorted(k.day.unique())
    assert k[k.day.isin(days[:10])].act_usual.isna().all()
    d = days[15]
    x = k[(k.day == d) & (k.slot == 30)].iloc[0]
    prev = k[(k.day.isin(days[:15])) & (k.slot == 30)].sort_values("day").act.iloc[-20:]
    assert np.isclose(x.act_usual, prev.median())


def test_orderflow_proxies():
    m = synth.minutes(days=1)
    mc = CX.minute_context(m, "NIFTY")
    k = CA.anatomy(CA.resample(mc[["ts", "o", "h", "l", "c", "act"]], 5))
    k = CX.orderflow_proxies(k, mc)
    first = mc.iloc[:5]
    sg = np.sign(first.c - first.o)
    assert np.isclose(k.delta_share.iloc[0], (sg * first.act).sum() / first.act.sum())
    top = max(k.o.iloc[0], k.c.iloc[0])
    assert np.isclose(k.up_wick_act.iloc[0], first.act[first.h > top].sum() / first.act.sum())
    assert ((k.delta_share.abs() <= 1) | k.delta_share.isna()).all()


def test_conditions_sweeps_and_breaks():
    n3 = lambda *v: list(v)
    k = pd.DataFrame(dict(o=n3(100, 100, 104), h=n3(101, 106, 103), l=n3(99, 99, 100), c=n3(101, 104, 101),
                          slot=n3(15, 20, 25), day=[pd.Timestamp("2024-01-02")] * 3, trend=n3(0, 0, 0),
                          pos50=n3(.5, .5, .5), dhi_before=n3(np.nan, 101, 106), dlo_before=n3(np.nan, 99, 99),
                          pdh=n3(102, 102, 102), pdl=n3(95, 95, 95), orh=n3(101, 101, 101), orl=n3(97, 97, 97),
                          vwap_dist=n3(0, 0, 0), gap=n3(0, 0, 0), act_ratio=n3(1, 1, 1),
                          pd_vah=n3(110, 110, 102.5), pd_val=n3(90, 90, 90), dpoi=n3(np.nan, 1, -1),
                          dcoi=n3(np.nan, -1, 1), gex_ratio=n3(np.nan, 0.1, -0.1), expiry=[False] * 3,
                          volreg=["normal"] * 3))
    c = CX.conditions(k)
    assert not c["break_pdh"][0] and c["break_pdh"][1] and not c["sweep_pdh"][1] and c["above_or"][1]
    assert c["sweep_pdh"][2] and not c["break_pdh"][2]
    assert c["new_day_high"][1] and not c["new_day_high"][2] and not c["new_day_high"][0]
    assert c["bull_oi_shift"][1] and c["bear_oi_shift"][2] and c["long_gamma"][1] and c["short_gamma"][2]
    assert not c["bull_oi_shift"][0] and not c["long_gamma"][0] and not c["short_gamma"][0]
    assert c["reject_pd_vah"][2] and not c["reject_pd_vah"][1]


def test_outcomes_same_session_and_first_touch():
    k = synth.bars([(100, 101, 99, 100), (100, 112, 99.5, 111), (111, 112, 85, 90), (90, 91, 89, 90)])
    k["atr"] = 10.0
    o = OC.outcomes(k)
    assert np.isclose(o.f_ret1[0], 1.1) and np.isclose(o.f_ret3[0], -1.0)
    assert np.isnan(o.f_ret5[0]) and np.isnan(o.f_ret1[3])
    assert o.f_upfirst20[0] == 1.0          # +1 ATR (110) traded in candle 1, -1 ATR (90) only in candle 2
    assert o.f_upfirst20[1] == 0.0          # from 111: -1 ATR (101) in candle 2, +1 ATR (121) never
    k3 = synth.bars([(100, 101, 99, 100), (100, 111, 89, 100)])
    k3["atr"] = 10.0
    assert np.isnan(OC.outcomes(k3).f_upfirst20[0])   # both inside one candle: order unknown
    assert o.f_lobrk10[0] == 1.0 and o.f_hibrk10[0] == 1.0 and o.f_lobrk10[2] == 0.0
    assert np.isclose(o.f_mfe10[0], 1.2) and np.isclose(o.f_mae10[0], 1.5)
    # a new session cuts the intraday outcomes but not f_any
    k.loc[2:, "day"] = pd.Timestamp("2024-01-03")
    o2 = OC.outcomes(k)
    assert np.isnan(o2.f_ret3[0]) and np.isclose(o2.f_any3[0], -1.0) and np.isclose(o2.f_mfe10[0], 1.2)


def test_path_matrix():
    c = np.arange(10, dtype=float)
    P = OC.path_matrix(c, np.ones(10), np.array([0, 5]), K=3)
    assert P.shape == (2, 7)
    assert np.isnan(P[0, :3]).all() and np.allclose(P[0, 3:], [0, 1, 2, 3])
    assert np.allclose(P[1], [-3, -2, -1, 0, 1, 2, 3])


def test_bh_and_clustered():
    q = ST.bh([0.01, 0.04, 0.03, np.nan, 0.5])
    assert np.isclose(q[0], 0.04) and np.isclose(q[1], 0.04 * 4 / 3 if False else 0.0533333, atol=1e-6)
    assert np.isnan(q[3]) and np.isclose(q[4], 0.5)
    # identical values within a day: clustering widens the error versus i.i.d.
    rng = np.random.default_rng(0)
    dv = rng.normal(0, 1, 200)
    e = np.repeat(dv, 5)
    day = np.repeat(np.arange(200), 5)
    m, se, g = ST.clustered(e, day)
    assert g == 200 and se > e.std() / np.sqrt(len(e)) * 1.8


def test_matched_baseline_removes_colour_drift():
    """A market that drifts up: every candle's next move is +, so 'red candles reverse' against a pooled baseline;
    the colour-matched baseline (R85 D1) shows no effect for a shape with no information."""
    rng = np.random.default_rng(3)
    n = 4000
    col = rng.choice([-1, 1], n)
    y = 0.3 + 0.2 * (col > 0) + rng.normal(0, 1, n)        # drift + a colour effect, nothing shape-specific
    df = pd.DataFrame(dict(und="X", tf=5, tod="midday", trend=0, volreg="normal", col=col, y=y,
                           day=np.repeat(np.arange(n // 10), 10)))
    shape = (col < 0) & (rng.random(n) < 0.2)
    base = ST.baseline(df, "y")
    r = ST.effect(df.y.to_numpy(), base, df.day.to_numpy(), shape)
    assert abs(r["z"]) < 2.5
    pooled = ST.effect(df.y.to_numpy(), np.full(n, df.y.mean()), df.day.to_numpy(), shape)
    assert pooled["eff"] < -0.05
