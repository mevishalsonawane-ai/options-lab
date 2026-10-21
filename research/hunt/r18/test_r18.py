"""R18 checks on synthetic data (no data download): replay, square-off, look-ahead, exit variants V1-V4, periods.

    python3 -m pytest research/hunt/r18/test_r18.py -q
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib  # noqa: E402
from lib import N, Trade  # noqa: E402


def path(prices):
    """A flat option path from minute 0; prices[k] = (o, h, l, c) for the first minutes, then flat at the last close."""
    a = np.full((N, 6), np.nan)
    last = prices[-1][3]
    for k in range(N):
        o, h, l, c = prices[k] if k < len(prices) else (last, last, last, last)
        a[k, :4] = (o, h, l, c)
    return a


def trade(prices, **kw):
    kw.setdefault("sq", lib.SQ_1510)
    return Trade(arm="t", und="BANKNIFTY", i=0, side=1, strike=0.0, c0=0, e=100.0, qty=30, path=path(prices),
                 meta=dict(lot=30), **kw)


def test_stop_is_checked_before_the_target():
    t = trade([(100, 150, 50, 100)], stop0=60.0, tgt=140.0)
    r = lib.replay(t)
    assert r["why"] == "stop" and r["exit_min"] == 0


def test_a_variant_only_exits_earlier_and_fills_next_open():
    t = trade([(100, 101, 99, 100), (100, 101, 99, 100), (105, 106, 104, 105)], stop0=60.0, tgt=200.0)
    base = lib.replay(t)
    r = lib.replay(t, extra=1)
    assert r["exit_min"] == 2 and r["why"] == "variant" and r["exit_min"] <= base["exit_min"]


def test_breakeven_rises_after_15_percent_and_never_widens():
    t = trade([(100, 120, 100, 118), (118, 118, 90, 92)], stop0=60.0, tgt=200.0)
    r0, r5 = lib.replay(t), lib.replay(t, be=True)
    assert r5["why"] == "stop" and r5["exit_min"] == 1
    assert r5["net"] > r0["net"]
    assert lib.be_level(t) > t.e


def test_ladder_breakeven_rung_counts_from_the_next_minute():
    t = trade([(100, 111, 99, 105), (105, 106, 90, 95)], stop0=60.0, tgt=140.0, ladder=((10.0, 0.0), (20.0, 10.0), (30.0, 20.0)))
    r = lib.replay(t)
    assert r["why"] == "stop" and r["exit_min"] == 1 and r["xavg"] > 99


def test_bh_and_bootstrap():
    q = lib.bh([0.01, 0.02, 0.5])
    assert np.all(np.diff(q[np.argsort([0.01, 0.02, 0.5])]) >= 0) and q.max() <= 1
    assert lib.boot_p(np.full(50, 10.0) + np.linspace(-1, 1, 50), B=2000) < 0.01
    assert lib.boot_p(np.zeros(10)) == 1.0


# ------------------------------------------------------------------------------------------------ synthetic grids
import copy  # noqa: E402
from datetime import date, timedelta  # noqa: E402

import arms as A  # noqa: E402
from lib import Grid  # noqa: E402

KS = np.arange(49000.0, 51600.0, 100.0)


def flat_chain(px=200.0, vol=100.0):
    arr = np.zeros((N, len(KS), 2, 6), dtype=np.float32)
    arr[..., :4] = px
    arr[..., 4] = vol
    arr[..., 5] = 1000.0
    return arr


def day(d, ix, vix=None, expiry=None, arr=None):
    e = expiry or d + timedelta(days=7)
    return dict(day=d, ix=ix, vix=np.full(N, 14.0) if vix is None else vix, fut_vol=np.zeros(N),
                chains={e: (KS.copy(), flat_chain() if arr is None else arr)}, expiries=[e], src={})


def walk_days(n=26, seed=0):
    """n random-walk BANKNIFTY sessions with a VIX series and a chain whose ATM volume varies (the release's VWAP proxy)."""
    rng = np.random.default_rng(seed)
    out, d, px = [], date(2025, 7, 1), 50000.0
    while len(out) < n:
        if d.weekday() < 5:
            c = px + np.cumsum(rng.normal(0, 12, N))
            o = np.concatenate([[px], c[:-1]])
            ix = np.column_stack([o, np.maximum(o, c) + rng.uniform(0, 6, N), np.minimum(o, c) - rng.uniform(0, 6, N), c])
            arr = flat_chain()
            arr[..., 4] = rng.integers(1, 500, (N, len(KS), 2))
            out.append(day(d, ix, vix=14 + np.cumsum(rng.normal(0, 0.02, N)), arr=arr))
            px = c[-1]
        d += timedelta(days=1)
    return out


def perturb_after(days, i, t, seed=1, delete=False):
    """A copy where everything printed after minute t of session i, and every later session, is changed (or deleted)."""
    rng = np.random.default_rng(seed)
    out = copy.deepcopy(days)

    def hit(a, sl):
        a[sl] = np.nan if delete else a[sl] * rng.uniform(0.8, 1.25, a[sl].shape)
    for j in range(i, len(out)):
        sl = slice(t + 1, None) if j == i else slice(None)
        hit(out[j]["ix"], sl)
        hit(out[j]["vix"], sl)
        hit(out[j]["fut_vol"], sl)
        for e, (ks, arr) in out[j]["chains"].items():
            hit(arr, sl)
    return out


def same(a, b):
    return np.array_equal(np.asarray(a, dtype=float), np.asarray(b, dtype=float), equal_nan=True)


# ------------------------------------------------------------------------------------------------ fix 1: expiry 15:05
def orb_day(d, expiry):
    ix = np.full((N, 4), 50000.0)
    ix[:50:2, 1] = 50050.0                 # opening range 49950 .. 50050
    ix[1:50:2, 2] = 49950.0
    ix[52:] = 50200.0                      # the 10:05 bar closes above the range -> CE
    return day(d, ix, expiry=expiry)


def test_orb_and_vixdiv_square_off_at_1505_when_the_contract_expires_today():
    d = date(2025, 7, 3)
    for exp, sq in ((d, lib.SQ_1505), (d + timedelta(days=7), lib.SQ_1510)):
        g = Grid("BANKNIFTY", days=[orb_day(d, exp)])
        for arm in ("orb", "orb_fresh"):
            ts = A.orb_family(arm, g)
            assert len(ts) == 1 and ts[0].sq == sq
            r = lib.replay(ts[0])
            assert r["why"] == "square_off" and r["exit_min"] == sq
        ix = np.full((N, 4), 50000.0)
        ix[60:] = 50200.0                  # index +0.4% by 10:29
        vix = np.full(N, 14.0)
        vix[60:] = 15.0                    # VIX +7%
        g = Grid("BANKNIFTY", days=[day(d, ix, vix=vix, expiry=exp)])
        ts = A.vixdiv("vixdiv_bn", g)
        assert len(ts) == 1 and ts[0].side == -1 and ts[0].sq == sq
        assert lib.replay(ts[0])["exit_min"] == sq


def test_sq_for_only_shortens_the_square_off_for_a_contract_expiring_today():
    d = date(2025, 7, 3)
    x = orb_day(d, d)
    nxt = d + timedelta(days=7)
    x["chains"][nxt] = (KS.copy(), flat_chain())
    x["expiries"].append(nxt)
    g = Grid("BANKNIFTY", days=[x])
    assert g.sq_for(0, lib.SQ_1510) == lib.SQ_1505
    assert g.sq_for(0, lib.SQ_1515, today_ok=False) == lib.SQ_1515


# ------------------------------------------------------------------------------------------------ fix 2: fills <= sq
def gappy(printed_until, resumes_at, px=100.0):
    a = np.full((N, 6), np.nan)
    a[:printed_until + 1, :4] = px
    a[resumes_at:, :4] = px + 50
    return a


def test_a_decision_just_before_the_square_off_never_fills_after_it():
    sq = lib.SQ_1510
    p = gappy(sq - 2, sq + 6)              # the option stops printing at sq-2 and resumes after the square-off
    px, m = lib.next_open(p, sq - 1, sq)
    assert m == sq and px == 100.0         # the last printed price, at the square-off, not the later print at sq + 6
    t = Trade(arm="t", und="BANKNIFTY", i=0, side=1, strike=0.0, c0=0, e=100.0, qty=30, path=p, sq=sq, meta=dict(lot=30))
    r = lib.replay(t, extra=sq - 1)
    assert r["exit_min"] <= sq and r["xavg"] < 101
    r0 = lib.replay(t)                     # the square-off itself, nothing printed at sq
    assert r0["why"] == "square_off" and r0["exit_min"] == sq and r0["xavg"] < 101


def test_next_open_still_takes_the_first_print_before_the_square_off():
    p = gappy(10, 20)
    assert lib.next_open(p, 10, lib.SQ_1510) == (150.0, 20)


# ------------------------------------------------------------------------------------------------ look-ahead
DAYS = walk_days()
I = len(DAYS) - 2


def test_vwap_is_point_in_time():
    vw, sd = Grid("BANKNIFTY", days=DAYS).vwap(I)
    for t in (20, 90, 200, 330):
        for delete in (False, True):
            vw2, sd2 = Grid("BANKNIFTY", days=perturb_after(DAYS, I, t, delete=delete)).vwap(I)
            assert same(vw[:t + 1], vw2[:t + 1]) and same(sd[:t + 1], sd2[:t + 1])


def test_e30_and_levels_are_point_in_time():
    g = Grid("BANKNIFTY", days=DAYS)
    for k in (30, 60, 150, 300):
        e, lv = g.e30(I, k), g.levels(I, k)
        assert np.isfinite(e) and e > 0
        for delete in (False, True):
            g2 = Grid("BANKNIFTY", days=perturb_after(DAYS, I, k - 1, delete=delete))   # known at minute k-1's close
            assert same(e, g2.e30(I, k)) and same(lv, g2.levels(I, k))


def walk_trade(days, side, c0=60, sq=lib.SQ_1510):
    ix = days[I]["ix"]
    return Trade(arm="t", und="BANKNIFTY", i=I, side=side, strike=50000.0, c0=c0, e=200.0, qty=30,
                 path=np.asarray(days[I]["chains"][days[I]["expiries"][0]][1][:, 0, 0, :], dtype=float), sq=sq,
                 ix_entry=float(ix[c0 - 1, 3]), meta=dict(lot=30))


def test_variant_minutes_are_point_in_time():
    fired = 0
    for seed in range(6):
        days = walk_days(seed=seed)
        g = Grid("BANKNIFTY", days=days)
        for side in (1, -1):
            for v in ("V1", "V2", "V3", "V4"):
                m = lib.variant_minute(g, walk_trade(days, side), v)
                fired += m is not None
                for p in range(60, lib.SQ_1510, 23):
                    m2 = lib.variant_minute(Grid("BANKNIFTY", days=perturb_after(days, I, p, seed=p)), walk_trade(days, side), v)
                    if m is not None and m <= p:
                        assert m2 == m, (seed, side, v, p)
                    else:
                        assert m2 is None or m2 > p, (seed, side, v, p)
    assert fired >= 20                     # most of the 48 cases fire, so the check is not vacuous


# ------------------------------------------------------------------------------------------------ variants V1-V4
def shaped(side, after):
    """DAYS with session I flat at the entry's index price from c0 = 60, then `after(ix, ref)` applied."""
    days = copy.deepcopy(DAYS)
    ix = days[I]["ix"]
    ref = ix[59, 3]
    ix[60:] = ref
    after(ix, ref)
    return days, Grid("BANKNIFTY", days=days)


def check_exit(g, days, side, v, want):
    t = walk_trade(days, side)
    assert lib.variant_minute(g, t, v) == want
    r = lib.run_variant(g, [t], v)[0]
    assert r["why"] == "variant" and r["exit_min"] == want + 1      # filled at the next open
    assert lib.run_variant(g, [t], "V0")[0]["exit_min"] == t.sq     # flat option: today's exits hold to 15:10


def test_v3_exits_at_the_close_of_the_45th_minute():
    for side in (1, -1):
        check_exit(Grid("BANKNIFTY", days=DAYS), DAYS, side, "V3", 60 + 44)


def test_v4_exits_when_the_favourable_move_reaches_the_expected_range():
    e30 = Grid("BANKNIFTY", days=DAYS).e30(I, 60)
    for side in (1, -1):
        def after(ix, ref, s=side):
            ix[70, 1 if s > 0 else 2] = ref + s * 0.9 * e30          # not yet
            ix[80, 1 if s > 0 else 2] = ref + s * 1.01 * e30         # used up
        days, g = shaped(side, after)
        check_exit(g, days, side, "V4", 80)
        days, g = shaped(side, lambda ix, ref, s=side: ix.__setitem__((70, 2 if s > 0 else 1), ref - s * 5 * e30))
        assert lib.variant_minute(g, walk_trade(days, side), "V4") is None   # an adverse move never counts


def test_v2_exits_when_the_index_reaches_the_next_level():
    g0 = Grid("BANKNIFTY", days=DAYS)
    e30, lv = g0.e30(I, 60), g0.levels(I, 60)
    for side in (1, -1):
        ref = DAYS[I]["ix"][59, 3]
        ahead = lv[(lv - ref) * side >= 0.25 * e30]
        target = ahead.min() if side > 0 else ahead.max()

        def after(ix, r, s=side, tg=target):
            ix[75, 1 if s > 0 else 2] = tg - s * 0.05             # just short of the level
            ix[90, 1 if s > 0 else 2] = tg                        # touches it
        days, g = shaped(side, after)
        check_exit(g, days, side, "V2", 90)


def test_v1_trails_one_band_under_the_best_band_reached():
    for side in (1, -1):
        def after(ix, ref, s=side):
            ix[60:120] = (ref + s * 4.0 * np.arange(1, 61))[:, None]    # a steady trend: the index rides the upper band
            ix[120:] = ref - s * 3000.0                                 # then a collapse through every band
        days, g = shaped(side, after)
        vw, sd = g.vwap(I)
        z = (days[I]["ix"][:, 3] - vw) / sd * side
        assert np.nanmax(z[60:120]) >= 1                                # it did reach band +1 or higher
        check_exit(g, days, side, "V1", 120)


# ------------------------------------------------------------------------------------------------ periods
def test_the_lake_holdout_is_fresh_and_embargoed(monkeypatch):
    import config as K
    monkeypatch.setattr(K, "SOURCE", "lake")
    for und in K.UNDS:
        assert K.HOLDOUT["lake"][und] > K.LAST_EXAMINED
        assert K.is_design(K.LAST_EXAMINED, und)
        assert not K.is_design(K.LAST_EXAMINED + timedelta(days=1), und)          # embargo
        assert not K.is_design(K.LAKE_HOLDOUT_START - timedelta(days=1), und)
        assert not K.is_design(K.LAKE_HOLDOUT_START, und)
    monkeypatch.setattr(K, "SOURCE", "release")
    assert K.is_design(date(2025, 8, 22), "BANKNIFTY") and not K.is_design(date(2025, 8, 25), "BANKNIFTY")
