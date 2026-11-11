"""R160 tests: synthetic data only (no lake file is read).

    python -m pytest research/hunt/r160_cas_gap/tests -q
"""
from __future__ import annotations

from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from research.hunt.r160_cas_gap import cfg as K
from research.hunt.r160_cas_gap import measure as M
from research.hunt.r160_cas_gap import stats as S
from research.hunt.r160_cas_gap import study as ST
from research.hunt.r160_cas_gap import trade as T
from research.obuy.costs import Costs, Fills


# ================================================================================================ fixtures
def ts(d: date, r: int) -> pd.Timestamp:
    return pd.Timestamp(d) + pd.Timedelta(minutes=9 * 60 + 15 + r)


def parity_rows(d: date, spot: np.ndarray, step=50.0, offsets=K.OFFSETS, skip_put=(), carry=0.0, tv=20.0) -> pd.DataFrame:
    """Option rows whose call - put = (spot + carry) - strike exactly (put-call parity), every minute."""
    out = []
    for r in range(K.N):
        if not np.isfinite(spot[r]):
            continue
        atm = round(spot[r] / step) * step
        for o in offsets:
            k = atm + o * step
            f = spot[r] + carry
            c = max(f - k, 0) + tv
            p = c - (f - k)
            for s, px in (("C", c), ("P", p)):
                if s == "P" and o in skip_put:
                    continue
                out.append(dict(ts=ts(d, r), offset=o, side=s, open=px, high=px, low=px, close=px, strike=k))
    return pd.DataFrame(out)


def walk(seed=0, base=20000.0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return base + np.cumsum(rng.normal(0, 3, K.N))


# ================================================================================================ measures
def test_implied_index_recovers_spot_plus_carry():
    d = date(2026, 8, 4)
    s = walk(1)
    f = M.implied_minutes(parity_rows(d, s, carry=12.5))
    g = M.to_grid(f, "F")[d]
    assert np.allclose(g, s + 12.5)
    assert (f.n == 5).all()


def test_implied_index_needs_three_offsets_with_both_sides():
    d = date(2026, 8, 4)
    s = np.full(K.N, 20000.0)
    rows = parity_rows(d, s, offsets=(-1, 0, 1, 2), skip_put=(1, 2))   # only -1 and 0 have both sides
    assert M.implied_minutes(rows).empty
    rows = parity_rows(d, s, offsets=(-1, 0, 1), skip_put=())
    assert len(M.implied_minutes(rows)) == K.N


def test_zero_price_side_is_ignored():
    d = date(2026, 8, 4)
    rows = parity_rows(d, np.full(K.N, 20000.0))
    rows.loc[(rows.offset == 2) & (rows.side == "P"), "close"] = 0.0
    f = M.implied_minutes(rows)
    assert (f.n == 4).all() and np.allclose(f.F, 20000.0)


def test_basis_and_late_level_windows():
    idx = np.full(K.N, 100.0)
    f = idx + 2.0
    lo, hi = K.LATE_ROWS
    f[lo: hi + 1] = 110.0                     # the late window moves up 8 above the carry-adjusted index
    f[K.ENTRY_ROW:] = 999.0                   # after 15:27: must be ignored
    b = M.basis(f, idx)
    assert b == pytest.approx(2.0)
    assert M.late_level(f, b) == pytest.approx(108.0)


def test_short_windows_give_nan():
    idx = np.full(K.N, 100.0)
    f = np.full(K.N, np.nan)
    lo, hi = K.BASIS_ROWS
    f[lo: lo + K.MIN_BASIS_MIN - 1] = 101.0
    assert np.isnan(M.basis(f, idx))
    f[lo: hi + 1] = 101.0
    b = M.basis(f, idx)
    l0, _ = K.LATE_ROWS
    f[l0: l0 + K.MIN_LATE_MIN - 1] = 105.0
    assert np.isnan(M.late_level(f, b))
    assert np.isnan(M.late_level(f, np.nan))


def test_day_measures_identity_and_signs():
    m = M.day_measures(L=101.0, C=100.0, O=100.5, i1514=100.2, i0920=100.7)
    assert m["D"] == pytest.approx(1e4 * np.log(1.01))
    assert m["R"] == pytest.approx(m["G"] - m["D"])
    assert m["M"] > 0 and m["G20"] > m["G"]
    assert np.isnan(M.day_measures(np.nan, 100, 100, 100, 100)["D"])
    assert np.isnan(M.bps(0.0, 1.0))


def test_expiry_days_weekly_with_holiday():
    days = [date(2026, 9, 7) + timedelta(days=i) for i in range(14) if (date(2026, 9, 7) + timedelta(days=i)).weekday() < 5]
    days.remove(date(2026, 9, 15))                      # a Tuesday holiday -> Monday 14 Sep
    e = M.expiry_days(days, 1, False)
    assert e == {date(2026, 9, 8), date(2026, 9, 14)}


def test_expiry_days_monthly_last_tuesday():
    start = date(2026, 8, 3)
    days = [start + timedelta(days=i) for i in range(80) if (start + timedelta(days=i)).weekday() < 5]
    e = M.expiry_days(days, 1, True)
    assert date(2026, 8, 25) in e and date(2026, 9, 29) in e
    assert date(2026, 8, 18) not in e
    # data ending on 19 Oct: October's last Tuesday (27 Oct) is not known, so 13 Oct must not be marked
    days = [d for d in days if d <= date(2026, 10, 19)]
    assert date(2026, 10, 13) not in M.expiry_days(days, 1, True)


def test_empty_calendar():
    assert M.expiry_days([], 1, False) == set()


# ================================================================================================ look-ahead guard
def synthetic_market(n_days=6, seed=3):
    d0 = date(2026, 8, 3)
    days = [d0 + timedelta(days=i) for i in range(n_days + 3) if (d0 + timedelta(days=i)).weekday() < 5][:n_days]
    idx, fg, opens, closes = {}, {}, [], []
    for i, d in enumerate(days):
        s = walk(seed + i)
        idx[d] = s
        fg[d] = s + 10.0 + np.random.default_rng(seed + 50 + i).normal(0, 1, K.N)
        opens.append(s[0])
        closes.append(s[-1])
    daily = pd.DataFrame({"open": opens, "close": closes}, index=days)
    return days, idx, fg, daily


def test_lookahead_guard_late_level_and_move_ignore_the_future():
    days, idx, fg, daily = synthetic_market()
    x0, _ = ST.nights("NIFTY", idx, daily, fg, None)
    t = days[2]
    idx2 = {d: a.copy() for d, a in idx.items()}
    fg2 = {d: a.copy() for d, a in fg.items()}
    for a in (idx2[t], fg2[t]):
        a[K.LATE_ROWS[1] + 1:] += 500.0                  # every bar after 15:27 of t
    idx2[days[3]] += 777.0                               # every bar of t+1
    fg2[days[3]] += 777.0
    daily2 = daily.copy()
    daily2.loc[days[3], "open"] += 300.0
    x1, _ = ST.nights("NIFTY", idx2, daily2, fg2, None)
    a, b = x0[x0.d == t].iloc[0], x1[x1.d == t].iloc[0]
    assert a.M == pytest.approx(b.M)                    # M uses L (to 15:27) and the 15:14 print only
    assert a.D == pytest.approx(b.D)                    # D uses L and day t's official close
    assert a.G != pytest.approx(b.G)                    # the outcome did change


def test_nights_period_labels_and_drop_counts():
    days, idx, fg, daily = synthetic_market()
    fg[days[1]][:] = np.nan
    x, drop = ST.nights("NIFTY", idx, daily, fg, None)
    assert drop == {"basis_short": 1}
    assert set(x.period) == {"POST"} and len(x) == len(days) - 2


def test_trade_side_ignores_next_session():
    d, nd = date(2026, 8, 5), date(2026, 8, 6)
    opt = pd.concat([parity_rows(d, np.full(K.N, 20000.0)), parity_rows(nd, np.full(K.N, 20030.0))], ignore_index=True)
    x = pd.DataFrame([dict(und="NIFTY", d=d, nd=nd, period="POST", expiry=False, D=8.0, G=3.0, R=-5.0, M=1.0, G20=2.0)])
    a = ST.run_trade("NIFTY", x, opt, np.random.default_rng(0))
    x2 = x.assign(G=-40.0, R=-48.0)
    opt2 = pd.concat([parity_rows(d, np.full(K.N, 20000.0)), parity_rows(nd, np.full(K.N, 19900.0))], ignore_index=True)
    b = ST.run_trade("NIFTY", x2, opt2, np.random.default_rng(0))
    assert a["detail"][0]["side"] == b["detail"][0]["side"] == "C"
    assert a["detail"][0]["e"] == b["detail"][0]["e"]


# ================================================================================================ stats
def test_slope_and_perm_p():
    rng = np.random.default_rng(0)
    x = rng.normal(size=60)
    y = 0.8 * x + rng.normal(scale=0.3, size=60)
    assert S.slope(x, y) == pytest.approx(np.polyfit(x, y, 1)[0])
    assert S.perm_p(x, y, 2000, np.random.default_rng(1)) < 0.001
    assert S.perm_p(x, -y, 2000, np.random.default_rng(1)) > 0.99
    assert S.perm_p(x, -y, 2000, np.random.default_rng(1), side="two") < 0.001


def test_perm_p_null_is_roughly_uniform():
    rng = np.random.default_rng(5)
    ps = [S.perm_p(rng.normal(size=40), rng.normal(size=40), 300, rng) for _ in range(200)]
    assert 0.03 < np.mean(np.array(ps) < 0.1) < 0.18


def test_nan_and_degenerate_inputs():
    assert np.isnan(S.slope([1, np.nan], [2, 3]))
    assert np.isnan(S.perm_p([1, 1, 1], [1, 2, 3], 10, np.random.default_rng(0)))
    assert S.hit_rate([], [], 2) == (np.nan, 0, np.nan) or S.hit_rate([], [], 2)[1] == 0


def test_hit_rate_ignores_small_and_zero():
    r, n, p = S.hit_rate([5, -5, 1, 5, 6], [2, -1, -3, 0, -1], 2.0)
    assert n == 3 and r == pytest.approx(2 / 3, abs=1e-3)


def test_bh_counts_prior_tests():
    q = S.bh([0.001, 0.5], m_total=1000)
    assert q[0] == pytest.approx(1.0) and q[1] == 1.0
    q = S.bh([0.01, 0.02, 0.5])
    assert q[0] == pytest.approx(0.03) and q[1] == pytest.approx(0.03) and q[2] == pytest.approx(0.5)


def test_boot_diff_brackets_true_difference():
    rng = np.random.default_rng(2)
    x1, x2 = rng.normal(size=200), rng.normal(size=200)
    lo, hi = S.boot_diff(x1, 1.0 * x1 + rng.normal(0, .2, 200), x2, 0.2 * x2 + rng.normal(0, .2, 200), 500, rng)
    assert lo < 0.8 < hi and lo > 0


def test_twin_p():
    assert S.twin_p(6.0, np.full(100, 6.0)) == 1.0
    assert S.twin_p(7.0, np.full(100, 6.0)) == pytest.approx(1 / 101)


# ================================================================================================ trade
def test_price_leg_entry_exit_and_net():
    d, nd = date(2026, 8, 5), date(2026, 8, 6)
    a = np.full((4, K.N), np.nan)
    a[:, K.ENTRY_ROW] = 100.0
    nx = np.full((4, K.N), np.nan)
    nx[:, K.EXIT_ROW + 2] = 120.0                       # 09:16 missing: falls forward to 09:18
    lg, why = T.price_leg(d, "C", 20000.0, 65, {("C", 20000.0): a}, {("C", 20000.0): nx})
    assert why == "" and lg.e == 100.0 and lg.x == 120.0 and lg.x_row == K.EXIT_ROW + 2
    ef, xf = float(Fills("app").buy(np.array([100.0]))[0]), float(Fills("app").sell(np.array([120.0]))[0])
    dn = np.array([(d - date(1970, 1, 1)).days])
    ch = Costs("dated").charge(True, np.array([ef]), np.array([65]), dn)[0] + \
        Costs("dated").charge(False, np.array([xf]), np.array([65]), dn)[0]
    want = (xf - ef) * 65 - ch - 0.0016 * 220 * 65
    assert T.net(lg) == pytest.approx(want)
    assert T.net(lg, 1.5) < T.net(lg)


def test_price_leg_failures():
    d = date(2026, 8, 5)
    a = np.full((4, K.N), np.nan)
    assert T.price_leg(d, "C", 1.0, 65, {}, {})[1] == "strike_missing"
    assert T.price_leg(d, "C", 1.0, 65, {("C", 1.0): a}, {("C", 1.0): a})[1] == "no_entry_bar"
    a[:, K.ENTRY_ROW] = 50.0
    assert T.price_leg(d, "C", 1.0, 65, {("C", 1.0): a}, {("C", 1.0): np.full((4, K.N), np.nan)})[1] == "no_exit_bar"


def test_guard_drops_isolated_stray_print_only():
    a = np.full((4, K.N), np.nan)
    a[0, 10], a[3, 10], a[0, 11] = 150.0, 100.0, 101.0   # stray open, next bar back near 100
    assert not T.guard(a, 10)
    a[0, 11] = 149.0                                      # a real jump persists
    assert T.guard(a, 10)


def test_lot_sizes():
    assert K.lot("NIFTY", date(2026, 8, 3)) == 65 and K.lot("NIFTY", date(2025, 6, 1)) == 75


def test_book_and_atm_from_rows():
    d = date(2026, 8, 5)
    rows = parity_rows(d, np.full(K.N, 20010.0))
    bk = T.book(rows)
    assert ("C", 20000.0) in bk and bk[("C", 20000.0)].shape == (4, K.N)
    assert T.atm_strike(rows, K.ENTRY_ROW) == 20000.0
    assert T.atm_strike(rows.iloc[:0], K.ENTRY_ROW) is None


# ================================================================================================ verdict / entries
def _reg(slope=0.5, p=0.01, hit=0.6):
    return dict(n=53, slope=slope, pearson=0.3, spearman=0.3, p=p, hit=hit, hit_n=46, hit_p=0.1)


def _res(h1=None, h2=None, q=0.05, t1=None):
    r = dict(describe=dict(POST=dict(mean_abs_D=12.0, nights=53), PRE=dict(mean_abs_D=5.0, nights=392)),
             H1_post=h1 or _reg(), H1_pre=_reg(), H1_diff_ci95=(-0.5, 0.5), H2_post=h2 or _reg(-0.5), H2_pre=_reg(),
             H2_post_fut=_reg(), H1_post_fut=_reg(), H1_post_G20=_reg(), A_RM_post=_reg(), A_GM_post=_reg(),
             A_RM_pre=_reg(), B_expiry=dict(n_expiry=11, n_other=42, mean_abs_D_expiry=13.0, mean_abs_D_other=12.0))
    fam = [dict(test=f"{h} {u}", p=0.01, q_r160=q, q_all=q) for u in K.UNDS for h in ("H1", "H2", "A")]
    fam.append(dict(test="T1 twin NIFTY", p=0.01, q_r160=q, q_all=q))
    t = t1 or dict(trades=30, per_trade=100, win=0.5, total=3000, worst=-1, max_dd=-1, per_trade_stress=50,
                   total_stress=1500, calls=10, twin_p=0.01, twin_mean_total=0)
    return dict(und={u: r for u in K.UNDS}, family=fam, m_total=2164, T1=t)


def test_verdict_needs_every_condition():
    assert ST.verdict(_res()) == dict(H1_supported=True, T1_passed=False, H1_fails=[])   # H2 < 0 blocks T1
    assert ST.verdict(_res(h2=_reg(0.2)))["T1_passed"] is True
    v = ST.verdict(_res(q=0.5))
    assert not v["H1_supported"] and any("BH q" in w for w in v["H1_fails"])
    assert not ST.verdict(_res(h1=_reg(0.2)))["H1_supported"]
    assert not ST.verdict(_res(h1=_reg(hit=0.5)))["H1_supported"]
    t = dict(_res()["T1"], total_stress=-1)
    assert not ST.verdict(_res(h2=_reg(0.2), t1=t))["T1_passed"]
    t = dict(_res()["T1"], trades=19)
    assert not ST.verdict(_res(h2=_reg(0.2), t1=t))["T1_passed"]


def test_describe_shares():
    x = pd.DataFrame(dict(D=[10.0, -10.0, 1.0, 20.0], G=[5.0, -12.0, 3.0, -4.0], R=[-5.0, -2.0, 2.0, -24.0]))
    d = ST.describe(x)
    assert d["nights_absD_ge5"] == 3
    assert d["share_open_at_least_half_way_absD_ge5"] == pytest.approx(2 / 3, abs=1e-3)
    assert d["share_open_past_L_absD_ge5"] == pytest.approx(1 / 3, abs=1e-3)
    assert d["median_G_over_D_absD_ge5"] == pytest.approx(0.5)


def test_make_entries_builds_valid_entries():
    from research.hunt.r160_cas_gap import make_entries as E
    es = E.build(_res())
    assert len(es) == 11 and len({e["id"] for e in es}) == 11
    req = {"id", "title", "family", "date", "doc", "hypothesis", "data_version", "period", "instruments", "features",
           "model_or_rule", "settings", "prereg", "variants_tried", "results", "holdout_read", "verdict"}
    for e in es:
        assert req <= set(e) and set(e["results"]) == {"summary", "costs"}
        assert e["verdict"] in {"rejected", "not-a-test"}
    b = next(e for e in es if e["id"] == "r160-b-expiry")
    assert b["variants_tried"] is None and b["holdout_read"] is None
