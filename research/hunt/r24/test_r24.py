"""R24 tests (PREREG.md): the outcome maths on a known option path, the cost model, the label / feature separation, the
miner on synthetic data with a planted pattern, the rule entries and the per-rule numbers. 13 Oct 2026.

    python -m pytest research/hunt/r24/test_r24.py -q
"""
from __future__ import annotations

import ast
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import items as I  # noqa: E402
import mine as MN  # noqa: E402
import r24cfg as K  # noqa: E402
import r24data as DA  # noqa: E402
import study as S  # noqa: E402
import whatif as W  # noqa: E402
from research.obuy.costs import Costs, adverse_bps  # noqa: E402

N = 375
NAN = np.nan


def flat_path(p=100.0, vol=10_000.0):
    """A contract that trades every minute at p (O = H = L = C)."""
    a = np.full((5, N), p)
    a[4] = vol
    return a


def run(a, rows, lot=50, hs=0.0016, sq=K.ROW_1510, mult=1.0):
    return W.contract_outcomes(a[0], a[1], a[2], a[3], a[4], np.array(rows, dtype=np.int64), lot, hs, sq, mult)


def col(out, name, i=0):
    return out[i, W.COLS.index(name)]


# ------------------------------------------------------------------------------------------------ the cost model
@pytest.mark.parametrize("price,qty", [(0.05, 75), (1.2, 65), (57.35, 50), (250.0, 35), (812.4, 30)])
def test_charges_match_the_app_model(price, qty):
    c = Costs("app")
    for buy in (True, False):
        assert W.charge(buy, price, qty) == pytest.approx(float(c.charge(buy, np.array([price]), np.array([qty]))[0]),
                                                           abs=0.011)
    assert W.rt_per_unit(price, qty) == pytest.approx(float(c.rt_per_unit(np.array([price]), np.array([float(qty)]))[0]),
                                                      abs=0.001)


def test_fills_spread_by_moneyness_and_volume():
    # liquid ATM at Rs 100: 5 bps + max(0.16% x 100, 1 tick) = 100.05 + 0.16
    assert W.buy_fill(100.0, K.HS_BY_MNY[0], 50.0, 1.0) == pytest.approx(100.21)
    assert W.buy_fill(100.0, K.HS_BY_MNY[0], 50.0, 1.0) == pytest.approx(
        float(adverse_bps(np.array([100.0]), 5, True)[0]) + 0.16)
    # OTM3 is wider: 0.35%
    assert W.buy_fill(100.0, K.HS_BY_MNY[-3], 50.0, 1.0) == pytest.approx(100.05 + 0.35)
    # a Rs 2 option: the tick floor binds (1 tick liquid, 2 thin, 4 untraded)
    assert W.buy_fill(2.0, 0.0016, 50.0, 1.0) == pytest.approx(2.0 + 0.05)
    assert W.buy_fill(2.0, 0.0016, 5.0, 1.0) == pytest.approx(2.0 + 0.10)
    assert W.buy_fill(2.0, 0.0016, 0.0, 1.0) == pytest.approx(2.0 + 0.20)
    # sells: -5 bps (stops -10 bps and twice the ticks), never below zero; stress doubles the half-spread
    assert W.sell_fill(100.0, 0.0016, 50.0, False, 1.0) == pytest.approx(99.95 - 0.16)
    assert W.sell_fill(100.0, 0.0016, 50.0, True, 1.0) == pytest.approx(99.90 - 0.16)
    assert W.sell_fill(0.05, 0.0016, 0.0, True, 1.0) == 0.0
    assert W.buy_fill(100.0, 0.0016, 50.0, 2.0) == pytest.approx(100.05 + 0.32)
    assert set(K.HS_BY_MNY) == set(range(-3, 4))


# ------------------------------------------------------------------------------------------------ outcome maths
def test_flat_path_loses_exactly_the_costs():
    a = flat_path(100.0)
    out = run(a, [50])
    e = col(out, "e")
    assert col(out, "fill_row") == 50 and e == pytest.approx(100.21)
    x = W.sell_fill(100.0, 0.0016, 10_000 * 5 / 50, False, 1.0)
    want = (x - e) * 50 - W.charge(True, e, 50) - W.charge(False, x, 50)
    for c in ("net_rs_15", "net_rs_30", "net_rs_60", "net_rs_1505", "net_rs_1520"):
        assert col(out, c) == pytest.approx(want)
    assert col(out, "mfe_pct") == pytest.approx((100 - e) / e * 100)
    assert col(out, "t_best") == 0
    assert col(out, "orb_why") == W.SQUARE and col(out, "orb_row") == K.ROW_1510
    assert col(out, "liq_why") == W.TIME                         # +5% never reached by minute 20


def test_known_path_fixed_exits_mfe_mae():
    a = flat_path(100.0)
    a[:, 60:] = 120.0                    # +20 from minute 60
    a[4] = 10_000.0
    a[1, 90] = 150.0                     # one spike high at minute 90 (the best), a low dip at minute 55
    a[2, 55] = 95.0
    out = run(a, [50], sq=K.ROW_1510)
    e = col(out, "e")
    assert col(out, "fill_row") == 50
    # +15 -> sold at minute 65's open (120); +60 -> minute 110 (120)
    x120 = W.sell_fill(120.0, 0.0016, 1000.0, False, 1.0)
    want = (x120 - e) * 50 - W.charge(True, e, 50) - W.charge(False, x120, 50)
    assert col(out, "net_rs_15") == pytest.approx(want)
    assert col(out, "net_rs_60") == pytest.approx(want)
    assert col(out, "net_rs_1505") == pytest.approx(want)
    assert col(out, "mfe_pct") == pytest.approx((150 - e) / e * 100)
    assert col(out, "mfe_rs") == pytest.approx((150 - e) * 50)
    assert col(out, "t_best") == 40                              # minute 90 - fill 50
    assert col(out, "mae_pct") == pytest.approx((95 - e) / e * 100)
    assert col(out, "t_worst") == 5
    # orb: the 150 high reaches the +40 target (e + 40 = 140.21) -> sold at minute 91's open (120)
    assert col(out, "orb_why") == W.TARGET and col(out, "orb_row") == 91
    # sweep (+80 target never reached): peak 150 >= e + 0.5 x 80 -> lock at e + 20 = 120.21 (ceil tick 120.25) after
    # the spike, and 120 <= 120.25 on minute 91 -> sold there at the lock
    assert col(out, "sweep_why") == W.LOCK and col(out, "sweep_row") == 91
    # liq: the 95 low at minute 55 is above the -15% stop (85.15); at minute 70 (fill + 20) the close 120 >= e x 1.05
    # -> it stays, and rides to 15:10
    assert col(out, "liq_why") == W.SQUARE


def test_stops_and_expiry_square_off():
    a = flat_path(100.0)
    a[2, 55] = 50.0                      # a dip through both stops at minute 55
    out = run(a, [50], sq=K.ROW_1505)
    e = col(out, "e")
    assert col(out, "orb_why") == W.STOP and col(out, "orb_row") == 55
    trig = W.floor_tick(e - 40)
    x = W.sell_fill(trig, 0.0016, 1000.0, True, 1.0)
    assert col(out, "orb_net") == pytest.approx((x - e) * 50 - W.charge(True, e, 50) - W.charge(False, x, 50))
    assert col(out, "liq_why") == W.STOP
    b = flat_path(100.0)
    out = run(b, [50], sq=K.ROW_1505)
    assert col(out, "orb_row") == K.ROW_1505                     # expiry day: out at 15:05
    # a premium <= 40 has no -40 stop (OrbRules.stopTrigger): a dip to 1 does not stop it out
    c = flat_path(30.0)
    c[2, 55] = 1.0
    out = run(c, [50])
    assert col(out, "orb_why") != W.STOP


def test_no_fill_and_fill_window():
    a = flat_path(100.0)
    a[:4, 50:53] = NAN                  # no trade in the entry minute .. +2: no fill
    a[4, 50:53] = NAN
    out = run(a, [50, 51])
    assert col(out, "fill_row", 0) == -1 and np.isnan(col(out, "e", 0))
    assert col(out, "fill_row", 1) == 53                      # entry 51: fills at 53, the third minute
    out = run(a, [49])
    assert col(out, "fill_row") == 49


def test_outcome_ignores_prices_before_the_fill():
    """The what-if fill is at the entry minute's open or later: prices before it (the decision minute included) never
    change an outcome (volumes before it set only the spread, a cost known at the decision)."""
    a = flat_path(100.0)
    a[:, 120:] = 130.0
    a[4] = 10_000.0
    b = a.copy()
    b[:4, :80] *= 1.7                    # perturb every price before the fill minute 80
    oa, ob = run(a, [80]), run(b, [80])
    assert np.allclose(oa, ob, equal_nan=True)


def test_day_table_on_the_brain_fixture(built):
    """One real session of the committed fixture through the cube -> what-if rows with sane, labelled outcomes."""
    M, C = built["M"], built["C"]
    d = date(2025, 3, 25)
    t = W.day_table("BANKNIFTY", d, M[M.ts.dt.date == d], C[C.ts.dt.date == d])
    n = K.ENTRY_LAST - K.ENTRY_FIRST + 1
    assert len(t) % 14 == 0 and n * 14 - 5 * 14 <= len(t) <= n * 14     # a decision minute without an index bar: none
    f = t[t.filled]
    assert len(f) > 0.5 * len(t)
    assert (f.fill_row >= f.entry_row).all() and (f.fill_row <= f.entry_row + 2).all()
    assert (f.ts_decision == pd.to_datetime(f.day) + pd.to_timedelta(9 * 60 + 15 + f.entry_row - 1, "min")).all()
    assert (f.mae_pct <= f.mfe_pct + 1e-6).all() and (f.mae_pct <= 0.01).all()
    assert (f.net_rs_30 <= f.mfe_rs + 1e-3).all()              # can never sell above the best traded price
    assert set(f.mny.unique()) <= set(range(-3, 4))
    assert (t.lot == 30).all() and not t.is_expiry.any()       # 25 Mar 2025: BANKNIFTY lot 30, not an expiry day


def test_month_series_matches_the_cube_path(built):
    """The MONTH path (raw lake rows -> month_chain -> day_table with the monthly expiry) gives the same outcomes as the
    cube path on the same contracts; the monthly expiry is the last scheduled expiry of the month."""
    M, C = built["M"], built["C"]
    d = date(2025, 3, 25)
    Md, Cd = M[M.ts.dt.date == d], C[C.ts.dt.date == d]
    raw = Cd[["ts", "strike", "side", "open", "high", "low", "close", "volume"]].copy()
    raw["ts"] = raw.ts.dt.tz_localize("Asia/Kolkata")
    raw.loc[Cd.flag.values != "ok", "volume"] = 0                # the lake: untraded minutes carry volume 0
    a = W.day_table("BANKNIFTY", d, Md, Cd)
    b = W.day_table("BANKNIFTY", d, Md, W.month_chain(raw), expiry=date(2025, 3, 27))
    assert len(a) == len(b) and a.filled.sum() == b.filled.sum()
    for c in ("e", "net_rs_30", "net_rs_1505", "orb_net", "liq_net", "mfe_pct"):
        assert np.allclose(a[c].values, b[c].values, equal_nan=True), c
    assert W.monthly_expiry("BANKNIFTY", d) == date(2025, 3, 27)
    assert W.monthly_expiry("NIFTY", date(2024, 1, 10)) == date(2024, 1, 25)     # last Thursday weekly of Jan 2024
    assert W.monthly_expiry("NIFTY", date(2024, 1, 26)) == date(2024, 2, 29)
    assert W.month_chain(raw.iloc[:0]).empty


def test_holdout_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "HOLD_FLAG", tmp_path / "HOLDOUT_READ")
    monkeypatch.setattr(K, "RESULTS", tmp_path)
    (tmp_path / "step3_holdout.json").write_text("{}")             # the committed result alone refuses a re-read
    with pytest.raises(SystemExit):
        S.holdout(None)


# ------------------------------------------------------------------------------------------------ separation
def test_items_read_features_only():
    """items.py and the minute frame never read a what-if outcome or a y_ label."""
    import inspect
    banned = ("net_rs", "net_pct", "mfe", "mae", "orb_net", "sweep_net", "liq_net", "t_best", "y_")
    for node in ast.walk(ast.parse((HERE / "items.py").read_text())):
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            assert not node.value.startswith(banned) and "whatif" not in node.value, node.value
        if isinstance(node, ast.Attribute):
            assert not node.attr.startswith(banned), node.attr
    import re
    src = inspect.getsource(DA.frame_from) + inspect.getsource(DA.minute_frame)
    assert not any(re.search(r"\b" + b, src) for b in banned) and "whatif" not in src
    assert not any(f.startswith("y_") for f in I.FEATURES)
    from research.brain.features import REGISTRY, load_all
    load_all()
    assert set(I.FEATURES) <= set(REGISTRY)                      # every input is a registered (guarded) feature


def _features(days, seed=0):
    rng = np.random.default_rng(seed)
    rows = []
    for d in days:
        for m in range(0, 375):
            ts = pd.Timestamp(d) + pd.Timedelta(minutes=9 * 60 + 15 + m)
            r = {f: rng.normal() for f in I.FEATURES}
            r.update(ts=ts, minute_of_day=m, or15_state=float(rng.integers(-1, 2)), lvl_near_count=float(rng.integers(0, 4)),
                     dte_days=float(rng.integers(0, 8)), em_vix_1d_pct=1.0)
            rows.append(r)
    return pd.DataFrame(rows)


def _whatif_for(mf, seed=1):
    rng = np.random.default_rng(seed)
    g = mf[["und", "d", "entry_row", "side"]].drop_duplicates()
    rows = []
    for o in K.OFFSETS:
        x = g.copy()
        x["offset"] = o
        rows.append(x)
    t = pd.concat(rows, ignore_index=True)
    n = len(t)
    t["mny"] = np.where(t.side > 0, -t.offset, t.offset)
    t["filled"] = True
    t["e"], t["lot"] = 100.0, 50
    for c in S.OUTCOLS:
        if c not in t and c not in ("key", "period"):
            t[c] = rng.normal(0, 100, n)
    t["period"] = t.d.map(K.period_of)
    t["key"] = DA.key(t.und.map(DA.UND_I).values, [d.toordinal() for d in t.d], t.entry_row.values, t.side.values,
                      t.offset.values)
    return t


def test_minute_frame_uses_the_decision_minute():
    F = _features([date(2024, 1, 2)])
    mf = DA.frame_from(F, "NIFTY")
    assert (mf.entry_row == mf.minute_of_day + 1).all()
    assert mf.entry_row.min() == K.ENTRY_FIRST and mf.entry_row.max() == K.ENTRY_LAST
    assert set(mf.side) == {1, -1} and len(mf) == 2 * (K.ENTRY_LAST - K.ENTRY_FIRST + 1)
    assert not any(c.startswith("y_") or c.startswith("net_") for c in mf.columns)


def test_codes_do_not_depend_on_outcomes():
    days = [date(2024, 1, 2), date(2024, 1, 3)]
    mf = DA.frame_from(_features(days), "NIFTY")
    wi = _whatif_for(mf)
    D1 = S.Data(wi=wi, mf=mf)
    wi2 = wi.copy()
    for c in ("net_rs_1505", "net_pct_1505", "orb_net", "mfe_pct", "liq_net"):
        wi2[c] = wi2[c].values[::-1] * 7.0                       # scramble every outcome
    D2 = S.Data(wi=wi2, mf=mf)
    c1, c2 = D1.cuts(set(days)), D2.cuts(set(days))
    assert c1 == c2
    z1, o1, _ = S.mining_rows(D1, D1.codes(c1), set(days))
    z2, o2, _ = S.mining_rows(D2, D2.codes(c2), set(days))
    assert z1.equals(z2)
    assert not np.allclose(o1.net_rs_1505.values, o2.net_rs_1505.values)


# ------------------------------------------------------------------------------------------------ the miner
def test_miner_finds_a_planted_pattern_and_separates_common_from_lift():
    rng = np.random.default_rng(3)
    n, m = 60_000, 6
    Z = rng.integers(0, 3, size=(n, m)).astype(np.int8)
    Z[:, 5] = (rng.random(n) < 0.8).astype(np.int8)            # item f5 = 1 on 80% of rows, no effect
    Z[rng.random(n) < 0.02, 2] = -1                               # some missing
    day = rng.integers(0, 400, n)
    planted = (Z[:, 1] == 2) & (Z[:, 3] == 0)
    p = np.where(planted, 0.30, 0.05)
    win = rng.random(n) < p
    lose = rng.random(n) < 0.1
    net = np.where(win, 500.0, -50.0)
    names = [f"f{j}" for j in range(m)]
    res = MN.mine(Z, [3, 3, 3, 3, 3, 2], names, day, win, lose, {"orb": net}, max_items=3, min_support=0.005,
                  min_days=100)
    top = res[res["size"] <= 2].sort_values("lift_w", ascending=False).iloc[0]
    assert top["items"] == (("f1", 2), ("f3", 0))
    assert top.lift_w > 2.5
    single = res[res["size"] == 1]
    common = single.sort_values("cover_w", ascending=False).iloc[0]
    assert common["items"] == (("f5", 1),)                      # most common among winners ...
    assert 0.9 < common.lift_w < 1.1                              # ... is just common: no lift
    r = res[res["items"] == (("f1", 2), ("f3", 0))].iloc[0]
    assert r.n == planted.sum() and r.mean_orb == pytest.approx(net[planted].mean())
    assert r.days == len(set(day[planted]))
    # support and day floors are honoured
    assert (res.support >= 0.005).all() and (res.days >= 100).all()
    # a missing code never forms a cell
    assert not any(c < 0 for it in res["items"] for _, c in it)


def test_tree_and_bh():
    rng = np.random.default_rng(5)
    Z = rng.integers(0, 3, size=(20_000, 3)).astype(np.int8)
    y = (Z[:, 0] == 2) & (rng.random(20_000) < 0.6) | (rng.random(20_000) < 0.05)
    lv = MN.tree_leaves(Z, ["a", "b", "c"], y, {"x": np.ones(20_000)}, depth=2, min_leaf=0.02)
    assert lv.iloc[0].lift > 2 and "a > 1.5" in lv.iloc[0].path
    q = MN.bh([0.01, 0.04, 0.03, 0.5])
    assert np.allclose(q, [0.04, 0.16 / 3, 0.16 / 3, 0.5], atol=1e-9)
    assert MN.fixes_side((("trend", 2),), I.SIDE_FIXING) and not MN.fixes_side((("vix", 2),), I.SIDE_FIXING)


# ------------------------------------------------------------------------------------------------ rules
def test_fires_first_minute_one_side_and_moneyness():
    days = [date(2024, 1, 2)]
    mf = DA.frame_from(_features(days), "NIFTY")
    wi = _whatif_for(mf)
    D = S.Data(wi=wi, mf=mf)
    codes = D.codes(D.cuts(set(days)))
    # the pattern "trend = with" holds for a call when ret_day is high and for a put when it is low
    ent = S.fires(D, codes, (("trend", 2),), set(days))
    assert len(ent) == 1
    m = (codes.trend.values == 2)
    rows = D.mf[m]
    assert ent.entry_row.iloc[0] == rows.entry_row.min()
    ent2 = S.fires(D, codes, (("trend", 2), ("side", 1), ("mnyb", 1)), set(days))
    assert ent2.side.iloc[0] == 1 and ent2.offset.iloc[0] == -1          # ITM1 call = one strike below ATM
    ent3 = S.fires(D, codes, (("trend", 2), ("side", 0), ("mnyb", 4)), set(days))
    assert ent3.side.iloc[0] == -1 and ent3.offset.iloc[0] == -2         # OTM2-3 put -> OTM2 = two strikes below
    # a "flat" aligned trend holds for the call and the put in the same minute (the middle tercile is symmetric): the
    # minute is skipped, never both bought; here every such minute is a tie, so the pattern never trades
    e4 = S.fires(D, codes, (("trend", 1),), set(days))
    assert e4.empty
    e5 = S.fires(D, codes, (("trend", 1), ("side", 1)), set(days))
    assert len(e5) == 1 and e5.side.iloc[0] == 1
    t = S.trades(D, ent2)
    assert len(t) == 1 and t.side.iloc[0] == 1 and t.offset.iloc[0] == -1


def test_stats_numbers():
    sess = [date(2024, 1, d) for d in (1, 2, 3, 4)]
    t = pd.DataFrame(dict(d=[sess[0], sess[0], sess[2]], orb_net=[1000.0, -400.0, -900.0], orb_net_x2=[900.0, -500.0, -1000.0],
                          mfe_pct_1505=[80.0, 10.0, 60.0], e=[100.0, 100.0, 100.0], lot=[50, 50, 50]))
    st = S.stats(t, "orb", sess)
    assert st["trades"] == 3 and st["net"] == -300.0 and st["per_day"] == -75.0
    assert st["win_rate"] == pytest.approx(1 / 3) and st["avg_win"] == 1000.0 and st["avg_loss"] == -650.0
    assert st["green_days"] == 0.5 and st["worst_day"] == -900.0 and st["max_dd"] == -900.0
    # big winners cut short: reached +80% but kept +20% (< half); the +60% one ended at -18% (cut too)
    assert st["big_reached"] == 2 and st["big_cut_short"] == 2
    assert S.stats(t, "orb", sess, x2=True)["net"] == -600.0
    assert S.stats(t.iloc[[]], "orb", sess)["trades"] == 0


def test_periods_and_moneyness():
    assert K.period_of(date(2026, 4, 13)) == "design"
    assert K.period_of(date(2026, 4, 20)) == "embargo"
    assert K.period_of(date(2026, 4, 27)) == "holdout"
    assert K.mny_of(1, -2) == 2 and K.mny_of(-1, -2) == -2 and K.offset_of(-1, K.mny_of(-1, 3)) == 3
    assert I.MNYB_TO_MNY == {0: 2, 1: 1, 2: 0, 3: -1, 4: -2}
