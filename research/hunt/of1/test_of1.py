"""OF1 unit tests on synthetic data (no downloads): python3 -m pytest research/hunt/of1/test_of1.py -q"""
from __future__ import annotations

import gzip
import json
import os
import sys
from datetime import date

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import build as BD  # noqa: E402
import buildup as BU  # noqa: E402
import footprints as FP  # noqa: E402
import load as LD  # noqa: E402
import pnl as P  # noqa: E402
import rules as RU  # noqa: E402
import signals as SG  # noqa: E402
import stats as ST  # noqa: E402


# ---- participant files ---------------------------------------------------------------------------------------------------
OLD = """"Participant wise Open Interest (no. of contracts) in Equity Derivatives as on January 02, 2012",,,,,,,,,,,,,,
CLIENT_TYPE,Future Index Long,Future Index Short,Future Stock Long,Future Stock Short,Option Index Call Long,Option Index Put Long,Option Index Call Short,Option Index Put Short,Option Stock Call Long,Option Stock Put Long,Option Stock Call Short,Option Stock Put Short,Total Long Contracts,Total Short Contracts
Client,10,4,0,0,5,5,2,1,0,0,0,0,20,7
FII ,6,1,0,0,1,2,3,4,0,0,0,0,9,8
DII,0,3,0,0,0,0,0,0,0,0,0,0,0,3
Pro,1,9,0,0,1,0,2,2,0,0,0,0,2,13
Total,17,17,0,0,7,7,7,7,0,0,0,0,31,31
"""
NEW = OLD.replace("CLIENT_TYPE", "Client Type").replace("Future Stock Short,", "Future Stock Short\t,").replace(
    "Total,17", "TOTAL,17").replace("FII ,", "FII,")


@pytest.mark.parametrize("text", [OLD, NEW])
def test_parse_participant_both_layouts(text):
    df = LD.parse_participant(text)
    assert list(df.index) == ["Client", "FII", "DII", "Pro", "TOTAL"]
    assert df.loc["FII", "fut_idx_long"] == 6 and df.loc["Pro", "fut_idx_short"] == 9
    assert LD.check_participant(df) == []


def test_participant_checks_and_errors():
    df = LD.parse_participant(OLD)
    df.loc["TOTAL", "fut_idx_long"] += 1                   # NSE's one-contract rounding: tolerated
    assert LD.check_participant(df) == []
    df.loc["TOTAL", "fut_idx_long"] += 5
    assert any("fut_idx_long" in e for e in LD.check_participant(df))
    with pytest.raises(ValueError):
        LD.parse_participant("<html>blocked</html>")
    with pytest.raises(ValueError):
        LD.parse_participant(OLD.replace("Pro,1,9", "Xyz,1,9"))      # a missing group


def _wide_from(texts: dict[date, str]) -> pd.DataFrame:
    rows = []
    for d, t in texts.items():
        x = LD.parse_participant(t).reset_index(names="group")
        x.insert(0, "date", d)
        rows.append(x)
    return LD.wide(pd.concat(rows, ignore_index=True))


def test_wide_nets():
    w = _wide_from({date(2012, 1, 2): OLD})
    r = w.iloc[0]
    assert r["FII.fut_net"] == 5 and r["Client.fut_net"] == 6 and r["Pro.fut_net"] == -8
    assert r["FII.put_write"] == 2 and r["FII.opt_stance"] == (1 - 3) - (2 - 4)
    assert sum(r[f"{g}.fut_net"] for g in LD.GROUPS) == 0
    assert r["TOTAL.fut_net"] == 0


def test_index_close_parse():
    txt = ("Index Name,Index Date,Open Index Value,High Index Value,Low Index Value,Closing Index Value,x\n"
           "Nifty 50,09-10-2026,100,110,90,105,1\nNifty Bank,09-10-2026,200,220,190,210,1\nIndia VIX,09-10-2026,13,14,12,13.5,0\n"
           "Nifty 50 Equal,09-10-2026,1,1,1,1,1\n")
    got = LD.parse_index_close(txt)
    assert got["NIFTY"] == (100, 110, 90, 105) and got["BANKNIFTY"][3] == 210 and got["VIX"][3] == 13.5


# ---- MTM maths ---------------------------------------------------------------------------------------------------------
def _rows(d, items):
    """items: (und, kind, expiry, strike, settle, oi_qty[, contracts, h, l, c])"""
    out = []
    for it in items:
        u, k, e, s, st, oi = it[:6]
        c = it[6] if len(it) > 6 else 10.0
        h, l, cl = (it[7], it[8], it[9]) if len(it) > 9 else (st, st, st)
        out.append(dict(date=d, und=u, kind=k, expiry=e, strike=float(s), open=st, high=h, low=l, close=cl, settle=st,
                        contracts=c, oi_qty=float(oi), chg_oi_qty=0.0, und_px=np.nan))
    return pd.DataFrame(out)


D0, D1, E0, E1 = date(2025, 1, 6), date(2025, 1, 7), date(2025, 1, 6), date(2025, 1, 30)


def test_pool_step_futures_and_expired():
    prev = _rows(D0, [("NIFTY", "FUT", E1, 0, 100.0, 1000), ("NIFTY", "FUT", E0, 0, 99.0, 500)])   # E0 expired today
    cur = _rows(D1, [("NIFTY", "FUT", E1, 0, 110.0, 1200)])
    s = P.pool_step(prev, cur, D0)
    assert s["fut_oi_dS"] == pytest.approx(1000 * 10.0)
    assert s["fut_oi"] == 1000 and s["lost_oi"] == 0


def test_group_pnl_zero_sum_and_values():
    w = _wide_from({D0: OLD, D1: OLD})
    prev = _rows(D0, [("NIFTY", "FUT", E1, 0, 100.0, 1700)])                    # 17 contracts x 100 units
    cur = _rows(D1, [("NIFTY", "FUT", E1, 0, 110.0, 1700)])
    po = pd.DataFrame([{**P.pool_step(prev, cur, D0), "date": D1, "prev": D0}])
    po.index = pd.to_datetime(po.date)
    pc = P.per_contract(po, w)
    assert pc.fut.iloc[0] == pytest.approx(1700 * 10 / 17)                       # = lot 100 x 10 points
    g = P.group_pnl(w, pc, pd.to_datetime(po.prev))
    assert g["FII.fut"].iloc[0] == pytest.approx(5 * 1000)
    assert g["Pro.fut"].iloc[0] == pytest.approx(-8 * 1000)
    assert sum(g[f"{x}.fut"].iloc[0] for x in P.GROUPS) == pytest.approx(0.0)


def test_options_buckets_and_rematch():
    prev = _rows(D0, [("NIFTY", "FUT", E1, 0, 100.0, 100), ("NIFTY", "CE", date(2025, 1, 29), 100, 5.0, 700),
                      ("NIFTY", "CE", date(2025, 1, 29), 120, 1.0, 0), ("NIFTY", "CE", date(2025, 1, 29), 130, 0.5, 300)])
    # NSE re-dated the monthly from 29 to 30 Jan: the contract reappears under the new date
    cur = _rows(D1, [("NIFTY", "FUT", E1, 0, 101.0, 100), ("NIFTY", "CE", E1, 100, 6.0, 700), ("NIFTY", "CE", E1, 130, 0.25, 300)])
    s = P.pool_step(prev, cur, D0)
    assert s["lost_oi"] == 0
    assert s["opt_CE_all_oi_dS"] == pytest.approx(700 * 1.0 + 300 * -0.25)
    assert s["opt_CE_near_oi"] == 700 and s["opt_CE_far_oi"] == 300               # 130 is 30% away from spot 100


def test_lost_oi_counted_not_guessed():
    prev = _rows(D0, [("NIFTY", "CE", E1, 100, 5.0, 700)])
    cur = _rows(D1, [("NIFTY", "CE", E1, 200, 5.0, 700)])
    s = P.pool_step(prev, cur, D0)
    assert s["lost_oi"] == 700 and s["opt_CE_all_oi"] == 0


def test_expiry_settle_turns_underlying_into_intrinsic():
    d = date(2026, 10, 6)
    df = _rows(d, [("NIFTY", "CE", d, 22700, 22776.1, 10), ("NIFTY", "PE", d, 22800, 22776.1, 10),
                   ("NIFTY", "CE", d, 22900, 22776.1, 10), ("NIFTY", "CE", date(2026, 10, 13), 22900, 50.0, 10)])
    df["eff_expiry"] = df.expiry
    out = BD.expiry_settle(df, d)
    assert out.settle.tolist()[:3] == pytest.approx([76.1, 23.9, 0.0])
    assert out.settle.iloc[3] == 50.0 and np.isnan(out.und_final.iloc[3])
    assert out.und_final.iloc[0] == pytest.approx(22776.1)
    df2 = df.copy()
    df2.loc[1, "settle"] = 10.0                                                     # premiums already: left alone
    assert BD.expiry_settle(df2, d).settle.iloc[0] == pytest.approx(22776.1)


def test_eff_map_holidays_and_future():
    days = [date(2025, 1, 1), date(2025, 1, 2), date(2025, 1, 6)]
    m = BD.eff_map(days, [date(2025, 1, 2), date(2025, 1, 4), date(2025, 3, 27)])
    assert m[date(2025, 1, 2)] == date(2025, 1, 2)
    assert m[date(2025, 1, 4)] == date(2025, 1, 2)          # a Saturday/holiday expiry moves to the session before
    assert m[date(2025, 3, 27)] == date(2025, 3, 27)        # after the data: unknown future, unchanged


# ---- build-up ---------------------------------------------------------------------------------------------------------
def test_classify_all_labels():
    got = BU.classify([1, -1, 1, -1, 0, 1, np.nan], [5, 5, -5, -5, 5, 0, 5])
    assert list(got) == ["long_buildup", "short_buildup", "short_covering", "long_unwinding", "neutral", "neutral", "new"]
    assert BU.classify([], []).shape == (0,)


def test_expiry_outcomes_and_finals():
    adds = pd.DataFrame([dict(und="NIFTY", expiry=E1, strike=100.0, kind="CE", settle=5.0, add_qty=10),
                         dict(und="NIFTY", expiry=E1, strike=110.0, kind="CE", settle=2.0, add_qty=20)])
    fin = pd.DataFrame([dict(und="NIFTY", expiry=E1, strike=100.0, kind="CE", final=8.0),
                        dict(und="NIFTY", expiry=E1, strike=110.0, kind="CE", final=0.0)])
    j = BU.expiry_outcomes(adds, fin)
    assert j.long_pnl_rs.tolist() == [30.0, -40.0]
    chain = pd.DataFrame({"strike": [100, 100, 110, 110], "kind": ["CE", "PE", "CE", "PE"], "settle": [8.0, 0.0, 0.0, 2.0]})
    assert BU.implied_final(chain) == pytest.approx(108.0)
    assert np.isnan(BU.implied_final(chain[chain.kind == "CE"]))


def test_moneyness():
    b = BU.moneyness_bucket(["CE", "CE", "PE", "CE", "PE"], [100, 102, 100, 90, 95], [100, 100, 100, 100, 100])
    assert list(b) == ["ATM", "OTM1-3%", "ATM", "ITM", "OTM>3%"]


def test_walls_mirror_and_tests():
    c = pd.DataFrame({"strike": [95, 100, 105, 110, 95, 90], "kind": ["CE", "CE", "CE", "CE", "PE", "PE"],
                      "oi_qty": [1, 5, 9, 9, 7, 3]})
    w = BU.walls(c, 101.0)
    assert w["call_wall"] == 105 and w["put_wall"] == 95 and w["kstar"] == 105   # tie at 9 -> nearer strike
    assert BU.mirror(105, 101.0, 5) == 95.0
    p = BU.breach_baseline(np.array([1.0, 1.0, 1.0]), np.array([2.0, 0.5, 3.0]), "call")
    assert p.tolist() == pytest.approx([0.5, 1.0, 0.5])
    assert BU.poisson_binomial_p(0, np.full(50, 0.5)) < 0.01
    assert BU.poisson_binomial_p(50, np.full(50, 0.5)) == pytest.approx(1.0)
    assert BU.signflip_p(np.full(30, -1.0)) < 0.01
    assert BU.signflip_p(np.full(30, 1.0)) > 0.99


# ---- statistics ---------------------------------------------------------------------------------------------------------
def test_ols_hac_and_bh():
    rng = np.random.default_rng(0)
    x = rng.normal(size=2000)
    y = 0.5 * x + rng.normal(size=2000)
    r = ST.ols_hac(y, x, 1)
    assert r["b"] == pytest.approx(0.5, abs=0.05) and r["p"] < 1e-10 and r["n"] == 2000
    assert np.isnan(ST.ols_hac([1, 2], [1, 2])["b"])
    q = ST.bh([0.01, 0.04, 0.03, np.nan, 0.5])
    assert q[0] == pytest.approx(0.04) and q[1] == pytest.approx(0.04 * 4 / 3) and q[2] == pytest.approx(0.04 * 4 / 3) and np.isnan(q[3])
    assert ST.one_sided(0.04, 1.0, 1) == pytest.approx(0.02) and ST.one_sided(0.04, -1.0, 1) == pytest.approx(0.98)


# ---- look-ahead guard ---------------------------------------------------------------------------------------------------
def _synthetic_wide(n=200, seed=3):
    rng = np.random.default_rng(seed)
    idx = pd.bdate_range("2020-01-01", periods=n)
    w = pd.DataFrame(index=idx)
    for g in SG.GROUPS:
        for c in ("fut_net", "long_share", "opt_stance", "put_write"):
            w[f"{g}.{c}"] = np.cumsum(rng.normal(size=n))
    return w


def _ix(n=200, seed=4):
    rng = np.random.default_rng(seed)
    idx = pd.bdate_range("2020-01-01", periods=n)
    c = 100 * np.exp(np.cumsum(rng.normal(0, 0.01, n)))
    o = c * (1 + rng.normal(0, 0.002, n))
    ix = pd.DataFrame({"NIFTY_open": o, "NIFTY_close": c, "BANKNIFTY_open": o * 2, "BANKNIFTY_close": c * 2,
                       "VIX_close": 15 + rng.normal(0, 1, n)}, index=idx)
    return ix


def test_signals_do_not_look_ahead():
    w = _synthetic_wide()
    fpi = pd.DataFrame({"eq_exch_net_cr": np.random.default_rng(5).normal(size=len(w))}, index=w.index)
    full = SG.build(w, fpi)
    rng = np.random.default_rng(6)
    for cut in (w.index[80], w.index[150]):
        trunc = SG.build(w[w.index <= cut], fpi[fpi.index <= cut])
        pert_w = w.copy()
        pert_f = fpi.copy()
        pert_w.loc[pert_w.index > cut] = rng.normal(0, 50, pert_w.loc[pert_w.index > cut].shape)
        pert_f.loc[pert_f.index > cut] = 1e6
        pert = SG.build(pert_w, pert_f)
        for other in (trunc, pert):
            a = full.loc[full.index <= cut]
            b = other.loc[other.index <= cut]
            pd.testing.assert_frame_equal(a, b)
    assert full[SG.SIGNALS].iloc[100].notna().all()


def test_fpi_counts_only_on_its_reporting_date():
    w = _synthetic_wide(120)
    f = pd.DataFrame({"eq_exch_net_cr": np.ones(120)}, index=w.index)
    f = f.drop(w.index[70])                                    # not reported that day
    s = SG.build(w, f)
    assert np.isnan(s.loc[w.index[70], "S5_FPI"])             # no carrying forward of an older report


def test_targets_start_after_the_signal():
    ix = _ix()
    full = SG.targets(ix, "NIFTY")
    t = ix.index[100]
    pert = ix.copy()
    pert.loc[pert.index <= t, ["NIFTY_open", "NIFTY_close"]] *= 1.5            # change everything up to t
    after = SG.targets(pert, "NIFTY")
    assert full.loc[t, "oc1"] == pytest.approx(after.loc[t, "oc1"])           # oc1(t) reads only t+1
    pert2 = ix.copy()
    pert2.loc[ix.index[101], "NIFTY_close"] *= 1.1
    assert SG.targets(pert2, "NIFTY").loc[t, "oc1"] != pytest.approx(full.loc[t, "oc1"])
    assert np.isnan(full["oc1"].iloc[-1]) and full["cc5"].iloc[-5:].isna().all()


def test_regimes_point_in_time():
    ix = _ix(400)
    full = SG.regimes(ix, [ix.index[30]])
    cut = ix.index[300]
    part = SG.regimes(ix[ix.index <= cut], [ix.index[30]])
    pd.testing.assert_frame_equal(full.loc[full.index <= cut], part)
    assert full.loc[ix.index[30], "expiry_week"]


# ---- rules -------------------------------------------------------------------------------------------------------------
def test_bar_pessimistic_order():
    assert RU.simulate_bar(100, 150, 60, 140) == (70.0, "stop")             # low hit the stop: stop, even with a big high
    px, why = RU.simulate_bar(100, 150, 80, 110)                            # trigger reached, close below lock
    assert why == "lock" and px == pytest.approx(115.0)
    assert RU.simulate_bar(100, 150, 80, 145) == (145.0, "lock")            # close above lock
    assert RU.simulate_bar(100, 120, 80, 90) == (90.0, "close")
    assert RU.fill_buy(100.0) == pytest.approx(100.15) and RU.fill_sell(100.0) == pytest.approx(99.85)
    assert RU.fill_sell(0.1, stop=True) == 0.0


def test_trade_and_twin():
    d = date(2025, 3, 4)
    rows = _rows(d, [("NIFTY", "CE", date(2025, 3, 6), 22500, 100.0, 1000, 500, 150, 90, 130),
                     ("NIFTY", "PE", date(2025, 3, 6), 22500, 100.0, 1000, 500, 105, 60, 70),
                     ("NIFTY", "CE", date(2025, 3, 4), 22500, 1.0, 1000, 500)])              # expiring today: not used
    rows["open"] = 100.0
    t = RU.trade(rows, "NIFTY", d, 22510.0, +1)
    assert t["why"] == "lock" and t["lot"] == 75 and t["charges"] > 0 and t["net"] == pytest.approx(t["gross"] - t["charges"])
    assert RU.trade(rows, "NIFTY", d, 22510.0, -1)["why"] == "stop"
    assert RU.trade(rows, "NIFTY", d, 30000.0, +1) is None                   # no strike near the money
    a = pd.DataFrame({"date": range(40), "net": np.full(40, 100.0)})
    b = pd.DataFrame({"date": range(40), "net": np.full(40, -100.0)})
    assert RU.twin_p(a, b) < 0.01 and RU.twin_p(b, a) > 0.99


# ---- footprints on synthetic ticks ------------------------------------------------------------------------------------
def _write_day(tmp_path, rows_by_token, inst):
    d = tmp_path / "2026-10-13"
    d.mkdir()
    with gzip.open(d / "ticks.jsonl.gz", "wt") as fh:
        fh.write(json.dumps({"t": "H", "schema": 1}) + "\n")
        fh.write(json.dumps({"t": "I", "s": 0, "list": inst}) + "\n")
        for r in rows_by_token:
            fh.write(json.dumps(r) + "\n")
        fh.write("not json\n")
    return d


def _book(px, qb=100, qa=100, step=0.05):
    b = [[round(px - step * (i + 1), 2), qb, 1] for i in range(5)]
    a = [[round(px + step * (i + 1), 2), qa, 1] for i in range(5)]
    return b, a


def test_footprint_detectors(tmp_path):
    t0 = 1_791_000_000
    rows = []
    vol, px = 0, 100.0
    for i in range(1200):
        s = t0 + i
        dv = 10
        if i == 400:
            dv = 5000                                   # a large print on an up-tick
            px += 0.05
        elif i == 500:
            px += 0.30                                  # sweep: through 5 ask levels with volume
        elif i % 7 == 0:
            px += 0.05
        elif i % 7 == 3:
            px -= 0.05
        vol += dv
        b, a = _book(px)
        rows.append({"t": "T", "s": s, "k": 1, "ltp": round(px, 2), "v": vol, "tbq": 1000, "tsq": 900, "b": b, "a": a})
    # option token 2: an iceberg bid at 50.0 refilled 4 times while ~3,000 trade at it, from second 700
    orows = []
    ov = 0
    for i in range(1200):
        s = t0 + i
        q = 300
        price = 50.05
        if 700 <= i < 800:
            price = 50.0
            j = i - 700
            q = 100 if j % 20 < 5 and j > 0 else 300          # dips and refills
            ov += 40
        else:
            ov += 5
        orows.append({"t": "T", "s": s, "k": 2, "ltp": price, "v": ov, "b": [[50.0, q, 3], [49.95, 100, 1]],
                      "a": [[50.10, 200, 1], [50.15, 100, 1]]})
    inst = [[1, "NIFTYFUT", "NIFTY", "FUT", None, None], [2, "NIFTY24000PE", "NIFTY", "PE", 24000, "2026-10-20"]]
    d = _write_day(tmp_path, rows + orows, inst)
    ev = FP.detect_day(d)
    kinds = ev.groupby(["inst_kind", "kind"]).size()
    assert kinds.get(("FUT", "large"), 0) >= 1
    big = ev[(ev.inst_kind == "FUT") & (ev.kind == "large")]
    assert (big.s == t0 + 400).any() and (big.side == 1).all()
    sw = ev[(ev.inst_kind == "FUT") & (ev.kind == "sweep")]
    assert (sw.s == t0 + 500).any() and (sw[sw.s == t0 + 500].side == 1).all()
    ice = ev[(ev.inst_kind == "PE") & (ev.kind == "iceberg")]
    assert len(ice) == 1 and ice.side.iloc[0] == 1 and ice.s.iloc[0] - t0 == 745     # refills at 705, 725, 745: the 3rd fires it
    # outcomes: signed future move; NaN past the end of the recording
    r = big[big.s == t0 + 400].iloc[0]
    fut = pd.DataFrame(rows)
    p0 = fut.loc[fut.s == t0 + 400, "ltp"].iloc[0]
    p1 = fut.loc[fut.s == t0 + 460, "ltp"].iloc[0]
    assert r["fwd1m"] == pytest.approx(p1 - p0)
    late = FP.outcomes(pd.DataFrame([{"s": t0 + 1190, "side": -1}]), fut)
    assert np.isnan(late.fwd1m.iloc[0])


def test_large_print_threshold_is_point_in_time():
    t0 = 0
    base = [{"s": t0 + i, "ltp": 100.0 + (0.05 if i % 2 else 0), "v": 10 * (i + 1)} for i in range(300)]
    s1 = pd.DataFrame(base + [{"s": 300, "ltp": 100.1, "v": 3000 + 30}])
    s2 = pd.DataFrame(base + [{"s": 300, "ltp": 100.1, "v": 3000 + 30}, {"s": 301, "ltp": 100.15, "v": 9_000_000}])
    a = FP.large_prints(s1)
    b = FP.large_prints(s2)
    assert (a.s == 300).any() and (b.s == 300).any()                 # a later, bigger print never changes earlier ones
    assert FP.large_prints(pd.DataFrame(base[:150])).empty            # fewer than 200 earlier seconds: no threshold


def test_absorption_minute():
    rows = []
    v = 0
    for m in range(10):
        for sec in range(60):
            dv = 5 if m < 8 else 60                       # minute 8: 12x the average, all at one price, flat
            px = 100.0 + (0.05 * (sec % 2) if m < 8 else 0.0)
            if m == 8 and sec == 0:
                px = 100.0
            v += dv
            rows.append({"s": m * 60 + sec, "ltp": px, "v": v})
    # make minute 8's ticks down-ticks first so the delta is negative (sellers hitting a passive buyer)
    s = pd.DataFrame(rows)
    s.loc[(s.s >= 480) & (s.s < 540), "ltp"] = 100.0
    s.loc[s.s == 479, "ltp"] = 100.05
    ev = FP.absorption(s)
    assert len(ev) >= 1 and (ev.s == 539).any() and ev.loc[ev.s == 539, "side"].iloc[0] == 1


def test_bad_days_left_out_not_repaired():
    ok = OLD.replace("DII,0,3,0,0,0,0,0,0", "DII,0,3,0,0,0,0,0,0")
    swapped = OLD.replace("FII ,6,1,0,0,1,2,3,4,0,0,0,0,9,8", "FII ,6,1,0,0,0,0,3,4,0,0,0,0,6,8").replace(
        "DII,0,3,0,0,0,0,0,0,0,0,0,0,0,3", "DII,0,3,0,0,1,2,0,0,0,0,0,0,3,3")
    w = _wide_from({date(2012, 1, 2): swapped, date(2012, 1, 3): ok})
    bad = LD.bad_days(w, ["fao_participant_oi_04012012.csv: could not convert string to float: 'NA'"])
    assert set(bad) == {pd.Timestamp("2012-01-02"), pd.Timestamp("2012-01-04")}
    assert "NA" in bad[pd.Timestamp("2012-01-04")]


def test_old_bhavcopy_expiry_formats():
    import fetch as FE
    got = FE._old_expiry(pd.Series(["31-May-12", " 28-Jun-2012 "]))
    assert got.tolist() == [date(2012, 5, 31), date(2012, 6, 28)]
    with pytest.raises(ValueError):
        FE._old_expiry(pd.Series(["2012/05/31"]))


def test_expiry_settle_zero_era_uses_parity():
    d = date(2019, 6, 27)
    rows = []
    S = 11841.55
    for k in (11700, 11750, 11800, 11850, 11900, 11950):
        ce, pe = max(S - k, 0) + 0.5, max(k - S, 0) + 0.5
        rows += [("NIFTY", "CE", d, k, 0.0, 10, 10, ce, ce, ce), ("NIFTY", "PE", d, k, 0.0, 10, 10, pe, pe, pe)]
    df = _rows(d, rows)
    df["close"] = df["high"]
    df["eff_expiry"] = df.expiry
    out = BD.expiry_settle(df, d)
    assert out.und_final.iloc[0] == pytest.approx(S) and (out.final_src == "parity").all()
    put = out[(out.kind == "PE") & (out.strike == 11950)].settle.iloc[0]
    assert put == pytest.approx(11950 - S)                                     # not the strike (the old bug)
    bare = df[df.strike == 11700].copy()                                       # too few strikes: cannot value
    assert BD.expiry_settle(bare, d).settle.isna().all()


def test_options_marked_to_close_futures_to_settle():
    d = date(2018, 2, 1)
    df = _rows(d, [("NIFTY", "PE", date(2018, 12, 27), 7400, 0.0, 100), ("NIFTY", "FUT", date(2018, 2, 22), 0, 10950.0, 100)])
    df.loc[0, "close"] = 310.0
    df.loc[1, "close"] = 10940.0
    df["eff_expiry"] = df.expiry
    out = BD.option_close_mtm(df, d)
    assert out.settle.tolist() == [310.0, 10950.0] and out.nse_settle.tolist() == [0.0, 10950.0]


def test_expiry_settle_prefers_official_index_close():
    d = date(2018, 10, 11)
    rows = []
    for k in (24700, 24800, 24900):
        rows += [("BANKNIFTY", "CE", d, k, 0.0, 10, 10, 900.0, 900.0, 900.0), ("BANKNIFTY", "PE", d, k, 0.0, 10, 10, 5.0, 5.0, 5.0)]
    df = _rows(d, rows)                                    # stale closes: parity would say ~25,600
    df["eff_expiry"] = df.expiry
    out = BD.expiry_settle(df, d, {"BANKNIFTY": 24783.95})
    assert (out.final_src == "index_close").all() and out.und_final.iloc[0] == pytest.approx(24783.95)
    assert out[(out.kind == "CE") & (out.strike == 24700)].settle.iloc[0] == pytest.approx(83.95)
