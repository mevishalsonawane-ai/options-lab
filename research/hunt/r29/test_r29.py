"""R29 unit tests on synthetic data (no lake files needed): python3 -m pytest research/hunt/r29/test_r29.py -q

Covers the trade simulator (fills, every exit, gaps / missing prices, expiry-day square-off), the gate, the twins,
BH, the holdout / walk-forward guard and that pattern events are point in time."""
from __future__ import annotations

import os
import sys
from datetime import date

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r29cfg as K  # noqa: E402
import r29data as D  # noqa: E402
import engine as EN  # noqa: E402
import mine as MI  # noqa: E402
import patterns as PT  # noqa: E402
import sim as S  # noqa: E402

N = K.N


def _P(close, vol=1e6, hi_add=0.0, lo_sub=0.0):
    c = np.asarray(close, float)
    o = np.r_[c[0], c[:-1]]
    P = np.full((5, N), np.nan)
    P[0, :len(c)], P[3, :len(c)] = o, c
    P[1, :len(c)] = np.maximum(o, c) + hi_add
    P[2, :len(c)] = np.minimum(o, c) - lo_sub
    P[4, :len(c)] = vol
    return P


# ------------------------------------------------------------------------------------------------ simulator
def test_fill_prices_and_spread_by_volume():
    P = _P(np.full(N, 100.0))
    e, x, r, why, _ = S.run(P, 20, 365, "X4", 50)
    assert e == pytest.approx(round(100 * 1.0005, 2) + 0.05)          # >= 20 lots traded: 1 tick
    assert why == S.R_TIME and r == 21 + 30                           # out after 30 minutes, at the next open
    assert x == pytest.approx(round(100 * 0.9995, 2) - 0.05)
    P[4] = 0.0
    e2, *_ = S.run(P, 20, 365, "X4", 50)
    assert e2 == pytest.approx(round(100 * 1.0005, 2) + 4 * 0.05)     # nothing traded: 4 ticks
    e3, *_ = S.run(_P(np.full(N, 100.0)), 20, 365, "X4", 50, spread_mult=2.0)
    assert e3 == pytest.approx(round(100 * 1.0005, 2) + 2 * 0.05)     # the 2x spread stress


def test_no_fill_on_a_missing_open_or_after_square_off():
    P = _P(np.full(N, 100.0))
    P[:, 21] = np.nan
    e, *_, why, _ = S.run(P, 20, 365, "X1", 50)
    assert np.isnan(e) and why == S.R_NOFILL
    e, *_ = S.run(_P(np.full(N, 100.0)), 364, 365, "X1", 50)
    assert np.isnan(e)


def test_stop_and_gap_through_stop():
    c = np.full(N, 100.0)
    c[40:] = 60.0                                                     # falls 40%: opens through the -30% stop
    P = _P(c)
    P[0, 40] = 60.0
    e, x, r, why, _ = S.run(P, 20, 365, "X1", 50)
    assert why == S.R_STOP and r == 40
    assert x == pytest.approx(round(60 * 0.999, 2) - 2 * 0.05)        # filled at the open (below the stop), stop fill
    c = np.full(N, 100.0)
    P = _P(c)
    P[2, 50] = 69.0                                                   # only the low touches the stop
    e, x, r, why, _ = S.run(P, 20, 365, "X1", 50)
    assert why == S.R_STOP and r == 50 and x == pytest.approx(round(e * 0.7 * 0.999, 2) - 0.10, abs=0.011)


def test_boss_lock_keeps_500_after_1000():
    qty = 50
    c = np.full(N, 100.0)
    c[25:35] = np.linspace(100, 130, 10)                              # +30 x 50 = Rs 1,500 open profit at 130
    c[35:] = 110.0                                                    # falls back
    e, x, r, why, _ = S.run(_P(c), 20, 365, "X1", qty)
    assert why == S.R_LOCK
    lock = 130 - 500 / qty                                            # best close - Rs 500 / qty = 120
    assert x == pytest.approx(round(lock * 0.999, 2) - 0.10, abs=0.011)   # the bar opens above the lock: fills at it
    assert (x - e) * qty > 0


def test_trail_target_time_and_square_off():
    c = np.full(N, 100.0)
    c[25:40] = np.linspace(100, 150, 15)
    c[40:] = 120.0
    e, x, r, why, _ = S.run(_P(c), 20, 365, "X2", 50)                # trail 15% under 150 = 127.5
    assert why == S.R_LOCK and x < 127.5 and x > 115
    c = np.full(N, 100.0)
    c[30] = 141.0
    P = _P(c)
    e, x, r, why, _ = S.run(P, 20, 365, "X3", 50)
    tgt = e * 1.4
    assert (why == S.R_TARGET and x == pytest.approx(tgt)) if P[1, 30] >= tgt + 0.05 else why != S.R_TARGET
    P = _P(np.full(N, 100.0))
    P[1, 30] = 100 * 1.4 + 0.2                                       # high a tick through the target
    e, x, r, why, _ = S.run(P, 20, 365, "X3", 50)
    assert why == S.R_TARGET and x == pytest.approx(e * 1.4)
    # expiry-day square-off at 15:05 (row 350) before the 60-minute time stop
    e, x, r, why, _ = S.run(_P(np.full(N, 100.0)), 320, K.SQ_EXPIRY, "X1", 50)
    assert why == S.R_SQ and r == K.SQ_EXPIRY


def test_missing_bars_carry_and_missing_exit_price_is_counted():
    c = np.full(N, 100.0)
    P = _P(c)
    P[:, 30:60] = np.nan                                              # 30 minutes of no prints: no exit triggered
    e, x, r, why, _ = S.run(P, 20, 365, "X1", 50)
    assert why == S.R_TIME and r == 81
    P = _P(c)
    P[:, 300:] = np.nan                                               # the strike stops printing: last close used
    e, x, r, why, _ = S.run(P, 250, 365, "X4", 50)
    assert why == S.R_TIME and r == 281
    e, x, r, why, _ = S.run(P, 290, 365, "X1", 50)
    assert why == S.R_MISSING and x == pytest.approx(round(100 * 0.9995, 2) - 0.2)


def test_post_exit_high_for_big_winners_cut_short():
    c = np.full(N, 100.0)
    c[60:] = 200.0
    e, x, r, why, ph = S.run(_P(c), 20, 365, "X4", 50)
    assert r == 51 and ph == 200.0


def test_net_includes_charges():
    n = S.net(np.array([100.0]), np.array([110.0]), np.array([50]))
    assert n[0] < 500 and n[0] > 400
    assert np.isnan(S.net(np.array([np.nan]), np.array([np.nan]), np.array([50]))[0])
    assert S.charges(np.array([100.0]), np.array([110.0]), np.array([20]), bse=True)[0] > 40


# ------------------------------------------------------------------------------------------------ gate, BH, p
def test_gate_one_at_a_time_and_three_a_day():
    d = date(2024, 3, 5)
    T = pd.DataFrame(dict(und="NIFTY", pattern="P1_5", sign=1, mode="follow", day=d,
                          m=[20, 30, 70, 80, 120, 200, 250], ATM_X1_row=[60, 65, 79, 100, 150, 230, 300],
                          ATM_X1_entry=100.0))
    G = EN.gate(T)
    assert list(G.m) == [20, 70, 80]                                  # 30 is busy; 80 fills at 81, after the exit at 79
    T2 = T.assign(m=[20, 61, 66, 200, 210, 260, 300], ATM_X1_row=[60, 65, 70, 205, 220, 270, 310])
    assert list(EN.gate(T2).m) == [20, 61, 66]                        # cap of 3 a day
    T3 = T.assign(day=[d, d, d, date(2024, 3, 6), date(2024, 3, 6), date(2024, 3, 6), date(2024, 3, 6)])
    assert len(EN.gate(T3)) == 5                                      # 2 on day 1 (30 busy), a new day resets: 3


def test_bh_and_clustered_p():
    q = MI.bh(np.array([0.01, 0.04, 0.03, 0.5, np.nan]))
    assert q[0] == pytest.approx(0.04) and q[2] == pytest.approx(0.04 * 4 / 3) and q[1] == pytest.approx(0.04 * 4 / 3)
    assert np.isnan(q[4])
    rng = np.random.default_rng(1)
    adj = rng.normal(5, 10, 400)
    key = np.repeat(np.arange(100), 4)
    t, p, n = MI.day_clustered_p(adj, key)
    assert n == 100 and t > 0 and p < 0.01
    t, p, n = MI.day_clustered_p(-adj, key)
    assert p > 0.99


# ------------------------------------------------------------------------------------------------ guard
def test_holdout_and_walk_forward_guard(monkeypatch):
    with pytest.raises(PermissionError):
        D.check_part("holdout")
    monkeypatch.setattr(D, "prereg_b_filled", lambda: False)
    with pytest.raises(PermissionError):
        D.check_part("wf2025")
    D.check_part("discovery")
    D.check_part("selection")
    monkeypatch.setattr(D, "ALLOW_HOLDOUT", True)
    D.check_part("holdout")
    E = pd.DataFrame(dict(day=[date(2022, 1, 3), date(2023, 1, 3), date(2024, 1, 3)]))
    assert list(EN.events_for("NIFTY", "selection", E).day) == [date(2024, 1, 3)]
    assert K.part_of(date(2023, 6, 1)) == "holdout" and K.part_of(date(2026, 10, 12)) == "wf2026"


def test_mining_never_reads_other_parts():
    assert set(MI.MINE_PARTS) == {"discovery", "selection"}


# ------------------------------------------------------------------------------------------------ events
def _minutes(n_days, seed=5):
    rng = np.random.default_rng(seed)
    out, px = [], 20000.0
    for i, d in enumerate(pd.bdate_range("2024-01-01", periods=n_days)):
        mu = 30.0 if (i // 3) % 2 == 0 else -30.0
        c = px + np.cumsum(rng.normal(mu / 375, 3, N))
        o = np.r_[px, c[:-1]]
        ts = d + pd.Timedelta(hours=9, minutes=15) + pd.to_timedelta(np.arange(N), "min")
        out.append(pd.DataFrame(dict(ts=ts, day=d, minute=np.arange(N), flag="ok", open=o,
                                     high=np.maximum(o, c) + 2, low=np.minimum(o, c) - 2, close=c)))
        px = c[-1]
    return pd.concat(out, ignore_index=True)


def test_onset():
    s = np.array([np.nan, 0, 1, 1, 0, -1, -1, 1])
    assert list(PT.onset(s)) == [0, 0, 1, 0, 0, -1, 0, 1]


def test_events_are_point_in_time_and_in_window(monkeypatch):
    monkeypatch.setattr(K, "WARM_SESSIONS", 3)
    M = _minutes(14)
    E = PT.events(M, "NIFTY")
    assert len(E) > 50 and E.m.between(K.SIG_FIRST, K.SIG_LAST).all() and (E.sess >= 3).all()
    assert set(E.pattern) <= {p for p, _ in PT.PATTERNS}
    cut = int(np.flatnonzero(M.day == M.day.unique()[-2])[200])        # mid-session, two days from the end
    E2 = PT.events(M.iloc[:cut + 1], "NIFTY")
    a = E[E.row <= cut][["pattern", "row", "sign"]].reset_index(drop=True)
    b = E2[["pattern", "row", "sign"]].reset_index(drop=True)
    pd.testing.assert_frame_equal(a, b)
    Mp = M.copy()
    Mp.loc[cut + 1:, ["open", "high", "low", "close"]] *= 1.07          # the future perturbed: nothing <= cut changes
    E3 = PT.events(Mp, "NIFTY")
    pd.testing.assert_frame_equal(a, E3[E3.row <= cut][["pattern", "row", "sign"]].reset_index(drop=True))


def test_b0_baseline_events(monkeypatch):
    monkeypatch.setattr(K, "WARM_SESSIONS", 2)
    B = PT.ema_cross_events(_minutes(8), "NIFTY")
    assert len(B) > 0 and set(B.sign) <= {-1.0, 1.0} and (B.pattern == "B0").all()


def test_index_labels_are_signed():
    M = _minutes(3)
    g = EN.IndexGrid(M)
    d = sorted(g.pos)[1]
    up, dn = g.labels(d, 100, +1), g.labels(d, 100, -1)
    assert up["mv30"] == pytest.approx(-dn["mv30"])
    assert up["mfe60"] >= 0 >= up["mae60"] and dn["mfe60"] >= 0 >= dn["mae60"]
    assert np.isnan(g.labels(d, 360, 1)["mv30"])                       # past the session end: no label


def test_engine_on_a_synthetic_option_day():
    """simulate_events + gate + twins on one fake underlying day with a call that follows the index."""
    M = _minutes(3)
    d = sorted(pd.to_datetime(M.day.unique()).date)[2]
    spot = M[M.day == pd.Timestamp(d)].close.values
    k = 20000.0
    rows = {}
    for side in "CP":
        px = np.maximum((spot - k) if side == "C" else (k - spot), 0) + 80
        rows[(d.toordinal(), side, k)] = px
    m_all, o, h, l, c, v, key, pos = [], [], [], [], [], [], {}, 0
    for (dd, side, kk), px in rows.items():
        op_ = np.r_[px[0], px[:-1]]
        m_all.append(np.arange(N)); o.append(op_); c.append(px)
        h.append(np.maximum(op_, px)); l.append(np.minimum(op_, px)); v.append(np.full(N, 1e5))
        key[(dd, side, kk)] = (pos, pos + N)
        pos += N
    cat = lambda a: np.concatenate(a).astype(np.float32)
    op = D.Opt(np.concatenate(m_all).astype(np.int16), cat(o), cat(h), cat(l), cat(c), cat(v), key,
               {d.toordinal(): np.full(N, k)}, set())
    E = pd.DataFrame(dict(pattern="P4_5", row=0, day=d, m=[50, 52, 200], sign=1.0))
    T = EN.simulate_events("NIFTY", E, M, op)
    assert len(T) == 6 and np.isfinite(T.ATM_X1_net).all()
    G = EN.gate(T)
    assert len(G) == 4                                                 # follow and fade: 50 and 200 (52 is busy)
    tn, tm = EN.twins("NIFTY", G, M, op, 5)
    assert tn.shape == (4, 5) and np.isfinite(tn).all()
    tn2, _ = EN.twins("NIFTY", G, M, op, 5)
    assert np.array_equal(tn, tn2)                                     # seeded: reproducible


# ------------------------------------------------------------------------------------------------ study (Steps 3-4)
import rules as RU  # noqa: E402
import study as ST  # noqa: E402


def _G(nets, days, whys=None, post=None):
    n = len(nets)
    return pd.DataFrame(dict(day=days, net=np.asarray(nets, float), entry=100.0, exit_px=110.0,
                             post=post if post is not None else np.full(n, 100.0),
                             why=whys if whys is not None else np.full(n, S.R_TIME), dirn=1))


def test_metrics_days_drawdown_and_cut_short():
    d1, d2, d3 = date(2024, 1, 1), date(2024, 1, 2), date(2024, 1, 3)
    G = _G([100, -50, -300, 400], [d1, d1, d2, d3], post=np.array([170.0, 100, 100, 100]))
    r = ST.metrics(G, n_days=10)
    assert r["trades"] == 4 and r["per_day"] == pytest.approx(0.4) and r["pnl_day"] == pytest.approx(15.0)
    assert r["win"] == 0.5 and r["avg_win"] == 250 and r["avg_loss"] == -175
    assert r["green_days"] == pytest.approx(2 / 3) and r["worst_day"] == -300
    assert r["max_dd"] == pytest.approx(-300)                       # peak +50 after day 1, -250 after day 2
    assert r["cut_short"] == 1                                       # post 170 >= exit 110 + 50
    assert ST.metrics(_G([], []), 5) == dict(trades=0, days=5)


def test_twin_p_uses_the_day_table():
    d = date(2024, 1, 1)
    table = {(d, 1): np.zeros(len(ST.WIN)), (d, -1): np.zeros(len(ST.WIN))}
    table[(d, 1)][:10] = 1000.0                                       # a few lucky minutes
    G = _G([500.0] * 5, [d] * 5)
    p, tw, means = ST.twin_p(G, table, n_sets=500)
    assert len(means) == 500 and 0 <= p < 0.2 and tw == pytest.approx(1000 * 10 / len(ST.WIN), rel=0.3)
    p2, *_ = ST.twin_p(_G([-10.0] * 5, [d] * 5), table, n_sets=500)
    assert p2 == 1.0


def test_family_q_and_max_family():
    res = {"R1|NIFTY|holdout": dict(p_twins=0.001), "R2|NIFTY|holdout": dict(p_twins=0.5),
           "R3|NIFTY|holdout": dict(trades=0)}
    fam = ST.family_q(res)
    assert fam["family_size"] == 2
    assert res["R1|NIFTY|holdout"]["bh_q"] == pytest.approx(0.002)
    assert res["R1|NIFTY|holdout"]["max_family_for_q10"] == pytest.approx(100.0)


def test_rule_events_filters_and_dedup(monkeypatch):
    monkeypatch.setattr(K, "WARM_SESSIONS", 3)
    M = _minutes(14)
    E = PT.events(M, "NIFTY")
    rule = dict(id="T", patterns=("P4_1", "P4_3"), sign=None, mode="follow", filters=(("stack", 15, "same", 1),),
                unds=K.UNDS, opt="ATM", exit="X1")
    cache = {}
    R = RU.rule_events(rule, "NIFTY", M, E, None, cache)
    s15 = cache[("stack", 15)]
    assert len(R) and (s15[R.row.values] == R.sign.values).all()
    assert not R.duplicated(["row", "sign"]).any()
    one = RU.rule_events(dict(rule, sign=1, filters=()), "NIFTY", M, E, None, {})
    assert (one.sign == 1).all()
    assert len(RU.RULES) <= 10 and RU.B0["mode"] == "follow"
