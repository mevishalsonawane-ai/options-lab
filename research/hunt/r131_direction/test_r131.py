"""R131 tests (PREREG section 8): look-ahead guard, publication lags, targets after t only, selective-call cut,
day-clustered test, BH, gates, the holdout lock and the money arithmetic. Synthetic data only (no lake needed).

    python3 -m pytest research/hunt/r131_direction -q
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[2]))
import r131cfg as K  # noqa: E402
import r131feat as F  # noqa: E402
import r131model as M  # noqa: E402
import r131money as Y  # noqa: E402
import r131src as S  # noqa: E402

DAYS = pd.bdate_range("2021-01-01", periods=320)
D0 = DAYS[250]


# ------------------------------------------------------------------------------------------------ synthetic sources
def _walk(rng, n, base, vol=0.01):
    return base * np.exp(np.cumsum(rng.normal(0, vol, n)))


def _sources(seed=0):
    rng = np.random.default_rng(seed)
    n = len(DAYS)
    idx = []
    for u, b in zip(K.UNDS + ("INDIAVIX",), (25000, 55000, 26000, 13000, 82000, 14)):
        c = _walk(rng, n, b)
        o = c * np.exp(rng.normal(0, 0.003, n))
        h = np.maximum(o, c) * 1.004
        lo = np.minimum(o, c) * 0.996
        idx.append(pd.DataFrame({"symbol": u, "date": DAYS, "open": o, "high": h, "low": lo, "close": c}))
    idx = pd.concat(idx, ignore_index=True)
    nse = []
    for key in ("NIFTY", "MID100", "SMALL100", "N50USD", "GSEC10"):
        c = idx[idx.symbol == "NIFTY"].close.values if key == "NIFTY" else _walk(rng, n, 1000)
        nse.append(pd.DataFrame({"key": key, "date": DAYS, "open": c, "high": c, "low": c, "close": c}))
    nse = pd.concat(nse, ignore_index=True)
    part = {"date": DAYS}
    for g in S.GROUPS:
        for k in S.PART_COLS:
            part[f"{g}_{k}"] = rng.integers(1000, 100000, n).astype(float)
    part = pd.DataFrame(part)
    fpi = pd.DataFrame({"date": DAYS, "eq_total_net_cr": rng.normal(0, 1000, n)})
    fut = []
    for u in K.UNDS + F.MCX:
        base = idx[idx.symbol == u].close.values if u in K.UNDS else _walk(rng, n, 5000)
        fut.append(pd.DataFrame({"symbol": u, "date": DAYS, "close": base * 1.002, "oi": rng.integers(1e5, 2e5, n).astype(float)}))
    fut = pd.concat(fut, ignore_index=True)
    eq = pd.concat([pd.DataFrame({"symbol": f"S{i}", "date": DAYS, "close": _walk(rng, n, 100)}) for i in range(20)])
    sf = pd.concat([pd.DataFrame({"symbol": f"S{i}", "date": DAYS, "close": _walk(rng, n, 100),
                                  "oi": rng.integers(1e5, 2e5, n).astype(float)}) for i in range(20)])
    chain = []
    for u in K.NSE_UNDS:
        chain.append(pd.DataFrame({"und": u, "date": DAYS, **{k: rng.normal(1, 0.1, n) for k in (
            "ch_pcr", "ch_pcr_vol", "ch_maxc", "ch_maxp", "ch_strad", "ch_skew", "fut_roll")}}))
    chain = pd.concat(chain, ignore_index=True)
    return {"index_daily": idx, "nse_indices": nse, "participants": part, "fpi": fpi, "fut_daily": fut,
            "equity_daily": eq, "stock_fut": sf, "chain": chain}


EVENING = {"nse_indices", "participants", "chain", "stock_fut"}
EXP = {u: set(DAYS[::5]) for u in K.NSE_UNDS}


def _panels(srcs, kind):
    mw, cls, mcx = F.market_wide(srcs)
    out = {}
    for u in K.UNDS:
        p, c, _ = F.und_panel(u, srcs, mw, cls, mcx, srcs["chain"], EXP)
        out[u] = F.assemble(p, c, kind, set())
    return out


def _perturb(srcs, when, which=None, seed=99):
    """Replace every value dated `when(date)` in the chosen sources by noise (dates kept)."""
    rng = np.random.default_rng(seed)
    out = {}
    for k, df in srcs.items():
        df = df.copy()
        if which is None or k in which:
            m = when(pd.to_datetime(df.date))
            for c in df.columns:
                if c in ("date", "symbol", "und", "key"):
                    continue
                df[c] = df[c].astype(float)
                df.loc[m, c] = rng.uniform(1, 1e5, int(m.sum()))
        out[k] = df
    return out


def _same(a, b, rows):
    for u in K.UNDS:
        x, y = a[u].loc[rows], b[u].loc[rows]
        pd.testing.assert_frame_equal(x, y, check_exact=False, rtol=1e-12)


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_close_rows_ignore_everything_after_the_decision_day():
    srcs = _sources()
    base = _panels(srcs, "close")
    alt = _panels(_perturb(srcs, lambda d: d > D0), "close")
    _same(base, alt, DAYS[DAYS <= D0])


def test_close_rows_ignore_evening_files_of_the_decision_day():
    srcs = _sources()
    base = _panels(srcs, "close")
    alt = _perturb(srcs, lambda d: d >= D0, which=EVENING - {"nse_indices"})
    # NSE's index file is also the same-day DATA CHECK of the index closes (PREREG section 2: a close more than 0.5%
    # off NSE's own blanks the day; cleaning, not a signal), so only its non-index series are perturbed here
    n = alt["nse_indices"]
    m = (pd.to_datetime(n.date) >= D0) & ~n.key.isin(K.UNDS)
    n.loc[m, "close"] = 1.0 + np.arange(int(m.sum()))
    # MCX and futures OI are evening too: perturb the MCX rows and the futures OI of D0 onward
    f = alt["fut_daily"]
    m = (pd.to_datetime(f.date) >= D0)
    f.loc[m & f.symbol.isin(F.MCX), "close"] = 1.0 + np.arange(int((m & f.symbol.isin(F.MCX)).sum()))
    f.loc[m, "oi"] = 7.0 + np.arange(int(m.sum()))
    prev = DAYS[DAYS < D0][-1]
    alt = _perturb(alt, lambda d: d >= prev, which={"fpi"})          # FPI: two trading days
    _same(base, _panels(alt, "close"), DAYS[DAYS <= D0])


def test_close_rows_do_see_their_own_close():
    srcs = _sources()
    base = _panels(srcs, "close")
    alt = _panels(_perturb(srcs, lambda d: d == D0, which={"index_daily"}), "close")
    assert not np.allclose(base["NIFTY"].loc[D0, "idx_r1"], alt["NIFTY"].loc[D0, "idx_r1"])


def test_morning_rows_ignore_the_traded_day_and_later():
    srcs = _sources()
    E = DAYS[260]
    base = _panels(srcs, "morning")
    alt = _perturb(srcs, lambda d: d >= E)
    prev = DAYS[DAYS < E][-1]
    alt = _perturb(alt, lambda d: d >= prev, which={"fpi"})
    _same(base, _panels(alt, "morning"), DAYS[DAYS <= E])


def test_morning_rows_see_the_previous_evening():
    srcs = _sources()
    E = DAYS[260]
    prev = DAYS[DAYS < E][-1]
    base = _panels(srcs, "morning")
    alt = _panels(_perturb(srcs, lambda d: d == prev, which={"participants"}), "morning")
    assert base["NIFTY"].loc[E, "part_FII_net"] != alt["NIFTY"].loc[E, "part_FII_net"]


def test_shuffling_the_future_never_changes_a_prediction():
    rng = np.random.default_rng(3)
    n = 900
    days = pd.Series(pd.bdate_range("2020-01-01", periods=n))
    df = pd.DataFrame({"und": "NIFTY", "day": days, "move": rng.normal(0, 1, n), "u_NIFTY": 1.0,
                       "idx_a": rng.normal(0, 1, n), "idx_b": rng.normal(0, 1, n)})
    df["y"] = (df.move > 0).astype(int)
    a = pd.Timestamp("2023-01-01")
    sys.path.insert(0, str(HERE))
    import run as R
    folds = dict(K.FOLDS)
    K.FOLDS = {"F1": (a.date(), pd.Timestamp("2023-06-30").date())}
    try:
        for model in K.MODELS:
            p1 = R.run_one("GAP", df, "IDX", model, "wf")
            df2 = df.copy()
            fut = df2.day > pd.Timestamp("2023-06-30")
            df2.loc[fut, ["idx_a", "idx_b", "move"]] = rng.permutation(df2.loc[fut, ["idx_a", "idx_b", "move"]].values)
            df2.loc[fut, "y"] = 1 - df2.loc[fut, "y"]
            emb = (df2.day >= a - pd.Timedelta(days=K.EMBARGO_DAYS)) & (df2.day < a)
            df2.loc[emb, "y"] = 1 - df2.loc[emb, "y"]                     # embargoed rows never train
            p2 = R.run_one("GAP", df2, "IDX", model, "wf")
            np.testing.assert_allclose(p1.p.values, p2.p.values)
            np.testing.assert_allclose(p1.cut_20.values, p2.cut_20.values)
    finally:
        K.FOLDS = folds


# ------------------------------------------------------------------------------------------------ intraday
def test_eq_breadth_reads_bars_up_to_t_only():
    rng = np.random.default_rng(1)
    c = rng.uniform(90, 110, (15, K.N))
    o = c.copy()
    pc = rng.uniform(90, 110, 15)
    t = 100
    a = F.eq_breadth(c, o, pc, t)
    c2, o2 = c.copy(), o.copy()
    c2[:, t + 1:] = 1e6
    o2[:, t + 1:] = 1e6
    assert a == F.eq_breadth(c2, o2, pc, t)
    assert 0 <= a["eq_up"] <= 1


def test_intra_target_reads_bars_after_t_only():
    c = np.linspace(100, 110, K.N)
    t = 104
    full = F.intra_targets(c, t, "R11")
    c2 = c.copy()
    c2[:t] = 5.0
    assert F.intra_targets(c2, t, "R11") == pytest.approx(full)
    assert full == pytest.approx(np.log(110 / c[t]) * 100)
    assert F.intra_targets(c, t, "M60") == pytest.approx(np.log(c[t + 60] / c[t]) * 100)
    c3 = c.copy()
    c3[t + 60] = np.nan
    assert np.isnan(F.intra_targets(c3, t, "M60"))
    c4 = c.copy()
    c4[300:] = np.nan                                                   # market closed early: last bar is the end
    assert F.intra_targets(c4, t, "DAY") == pytest.approx(np.log(c[299] / c[t]) * 100)


def test_daily_targets_and_data_check():
    idx = pd.DataFrame({"open": [100, 101, 102, 99.0], "high": [101, 102, 103, 100.0], "low": [99, 100, 101, 98.0],
                        "close": [100.5, 101.5, 102.0, 99.5]}, index=pd.bdate_range("2024-01-01", periods=4))
    nse = pd.DataFrame({"key": "NIFTY", "date": idx.index, "close": [100.5, 110.0, 102.0, 99.5]})
    idx.iloc[3, 0] = 102.0                                              # stale open = previous close
    out, logd = F.nse_check(idx, nse, "NIFTY")
    assert logd["close_mismatch"] == 1 and logd["stale_open"] == 1
    assert out.iloc[1].isna().all() and np.isnan(out.open.iloc[3])
    p = pd.DataFrame({"_open": out.open, "_close": out.close})
    t = F.daily_targets(p)
    assert np.isnan(t.C2C.iloc[0]) and np.isnan(t.GAP.iloc[0]) and np.isnan(t.GAP.iloc[2])
    assert t.C2C.iloc[2] == pytest.approx(np.log(99.5 / 102.0) * 100)
    assert np.isnan(t.C2C.iloc[3]) and np.isnan(t.GAP.iloc[3])          # no next day


def test_parsers():
    txt = ("Index Name,Index Date,Open Index Value,High Index Value,Low Index Value,Closing Index Value,Points Change,"
           "Change(%),Volume,Turnover (Rs. Cr.),P/E,P/B,Div Yield\n"
           "Nifty 50,01-04-2025,23341.1,23565.15,23136.4,23165.7,-353.65,-1.5,1,1,1,1,1\n"
           "Nifty 50,01-04-2025,23341.1,23565.15,23136.4,23165.7,-353.65,-1.5,1,1,1,1,1\n"
           "Nifty50 USD,01-04-2025,-,-,-,9999.5,1,1,-,-,-,-,-\n")
    d = S.parse_nse_indices_text(txt)
    assert set(d.key) == {"NIFTY", "N50USD"}
    assert d[d.key == "N50USD"].close.iloc[0] == 9999.5 and np.isnan(d[d.key == "N50USD"].open.iloc[0])
    assert d.date.iloc[0] == pd.Timestamp("2025-04-01")
    p = ('""Participant wise Open Interest"",,,,,,,,,,,,,,\n'
         "Client Type,Future Index Long,Future Index Short,Future Stock Long,Future Stock Short\t,Option Index Call Long,"
         "Option Index Put Long,Option Index Call Short,Option Index Put Short,Option Stock Call Long,Option Stock Put "
         "Long,Option Stock Call Short,Option Stock Put Short,Total Long Contracts\t,Total Short Contracts\n"
         "Client,1,2,0,0,3,4,5,6,0,0,0,0,0,0\nDII,7,8,0,0,9,10,11,12,0,0,0,0,0,0\n"
         "FII,13,14,0,0,15,16,17,18,0,0,0,0,0,0\nPro,19,20,0,0,21,22,23,24,0,0,0,0,0,0\n"
         "TOTAL,40,44,0,0,48,52,56,60,0,0,0,0,0,0\n")
    r = S.parse_participant_text(p, "2025-04-01")
    assert S.parse_participant_text(p.replace("TOTAL,40", "TOTAL,41"), "2025-04-01") is None
    assert r["FII_fil"] == 13 and r["FII_ps"] == 18 and r["Client_cl"] == 3 and r["Pro_cs"] == 23


def test_chain_day():
    d = pd.Timestamp("2025-01-01")
    e1, e2 = pd.Timestamp("2025-01-02"), pd.Timestamp("2025-01-30")
    rows = [dict(date=d, kind="FUT", expiry=e2, strike=0, close=101, settle=101, contracts=1, oi_qty=300),
            dict(date=d, kind="FUT", expiry=pd.Timestamp("2025-02-27"), strike=0, close=102, settle=102, contracts=1,
                 oi_qty=100)]
    for k, cs, ps, coi, poi in ((97, 4.0, 1.0, 10, 50), (100, 2.0, 2.0, 30, 30), (103, 0.5, 4.0, 60, 5)):
        rows.append(dict(date=d, kind="CE", expiry=e1, strike=k, close=cs, settle=cs, contracts=2, oi_qty=coi))
        rows.append(dict(date=d, kind="PE", expiry=e1, strike=k, close=ps, settle=ps, contracts=1, oi_qty=poi))
    rows.append(dict(date=d, kind="CE", expiry=e2, strike=100, close=9, settle=9, contracts=1, oi_qty=10 ** 6))
    r = S.chain_day(pd.DataFrame(rows), 100.0)
    assert r["ch_pcr"] == pytest.approx(85 / 100)
    assert r["ch_maxc"] == pytest.approx(3.0) and r["ch_maxp"] == pytest.approx(-3.0)
    assert r["ch_strad"] == pytest.approx(4.0)
    assert r["ch_skew"] == pytest.approx(np.log(1.0 / 0.5))
    assert r["fut_roll"] == pytest.approx(0.25)


# ------------------------------------------------------------------------------------------------ models and tests
def test_conf_cut_from_training_only():
    p = np.array([0.5, 0.55, 0.6, 0.7, 0.9])
    assert M.conf_cut(p, 1.0) == -1.0
    assert M.conf_cut(p, 0.2) == pytest.approx(np.quantile(np.abs(p - 0.5), 0.8))


def test_cluster_p_is_invariant_to_copies_within_a_day():
    rng = np.random.default_rng(0)
    h = (rng.random(400) < 0.58).astype(float)
    d = np.arange(400)
    p1 = M.cluster_p(h, d, 0.5)
    p5 = M.cluster_p(np.repeat(h, 5), np.repeat(d, 5), 0.5)
    assert p1 == pytest.approx(p5)
    assert p1 < M.binom_p(int(np.repeat(h, 5).sum()), 2000, 0.5) * 1e6
    assert M.cluster_p(np.zeros(10), np.arange(10), 0.5) > 0.99


def test_bh():
    q = M.bh(np.array([0.01, 0.04, 0.03, 0.5]))
    np.testing.assert_allclose(q, [0.04, 0.04 * 4 / 3, 0.04 * 4 / 3, 0.5])


def test_gate():
    good = {"n": 200, "hit": 0.63, "q": 0.01, "folds": {f: {"n": 50, "hit": 0.62} for f in K.FOLDS}}
    hold = {"n": 40, "hit": 0.61, "p0": 0.55}
    assert M.gate(good, hold, "DAY")["pass"]
    assert not M.gate(good, hold, "PRE")["pass"]                       # not tradeable
    assert not M.gate(good, {"n": 40, "hit": 0.58, "p0": 0.55}, "DAY")["pass"]
    bad = dict(good, folds={"F1": {"n": 50, "hit": 0.7}, "F2": {"n": 50, "hit": 0.7}, "F3": {"n": 5, "hit": 0.9},
                            "F4": {"n": 50, "hit": 0.5}})
    assert not M.gate(bad, hold, "DAY")["pass"]
    assert not M.gate(dict(good, n=99), hold, "DAY")["pass"]
    assert not M.gate(dict(good, q=0.2), hold, "DAY")["pass"]


def test_evaluate_tiny_moves_and_ties():
    df = pd.DataFrame({"y": [1, 0, 1, 1], "call": [1, 1, 1, 0], "move": [0.5, -0.02, 0.01, 0.3],
                       "day": [1, 1, 2, 3], "fold": "F1", "und": "NIFTY"})
    r = M.evaluate(df, 0.75, 0.10)
    assert r["n"] == 4 and r["hit"] == 0.5 and r["n_big"] == 2 and r["hit_big"] == 0.5 and r["days"] == 3


def test_feature_sets():
    df = pd.DataFrame(columns=["u_NIFTY", "idx_r1", "vix_lvl", "ri_gap", "brd_up", "rb_br_up", "eq_up", "part_x",
                               "fpi_net", "fut_basis", "ch_pcr", "rc_pcr", "glob_usd1", "mcx_gold", "cal_dow",
                               "rk_tod", "move", "y", "B1", "day"])
    assert F.feature_cols(df, "IDX") == ["u_NIFTY", "idx_r1", "vix_lvl", "ri_gap"]
    assert set(F.feature_cols(df, "IDX+PART")) == {"u_NIFTY", "idx_r1", "vix_lvl", "ri_gap", "part_x", "fpi_net"}
    allc = F.feature_cols(df, "ALL")
    assert "move" not in allc and "y" not in allc and "B1" not in allc and len(allc) == 16


def test_holdout_lock(tmp_path, monkeypatch):
    import run as R
    monkeypatch.setattr(K, "RESULTS", tmp_path)
    R.holdout_gate("start")
    R.holdout_gate("done")
    with pytest.raises(RuntimeError):
        R.holdout_gate("start")
    assert json.loads((tmp_path / "HOLDOUT_READ.json").read_text())["status"] == "done"


# ------------------------------------------------------------------------------------------------ money
def test_fut_trade_arithmetic():
    from datetime import date
    net = Y.fut_trade(100.0, 110.0, 10, 1, date(2025, 1, 1), date(2025, 1, 2))
    gross = 100.0
    slip = 210 * K.FUT_SLIP * 10
    ch = Y.fut_charges(100.0, 110.0, 10, 1, date(2025, 1, 1), date(2025, 1, 2))
    exch = 2100 * K.FUT_EXCH
    sebi = 2100 * 1e-6
    assert ch == pytest.approx(40 + exch + sebi + 1100 * 0.0002 + 1000 * 0.00002 + 0.18 * (40 + exch + sebi))
    assert net == pytest.approx(gross - slip - ch)
    short = Y.fut_trade(100.0, 110.0, 10, -1, date(2023, 1, 1), date(2023, 1, 2))
    assert short < -100                                                 # short loses the 10 points
    assert Y.stt_fut(date(2023, 1, 1)) == 0.0001 and Y.stt_fut(date(2024, 5, 1)) == 0.000125
    assert Y.fut_trade(np.nan, 1.0, 1, 1, date(2025, 1, 1), date(2025, 1, 2)) is None


def test_opt_trade_skips_untraded_minutes():
    from datetime import date

    class Fl:
        def buy(self, p, v):
            return p + 0.05

        def sell(self, p, v):
            return p - 0.05

    a = np.full((K.N, 5), np.nan)
    a[10] = [10, 10, 10, 10.0, 100]
    a[70] = [12, 12, 12, 12.0, 100]
    ch = lambda b, px, q, d, u: 20.0  # noqa: E731
    r = Y.opt_trade(a, 10, 70, 50, date(2025, 1, 1), "NIFTY", Fl(), ch)
    assert r["net"] == pytest.approx((11.95 - 10.05) * 50 - 40)
    assert Y.opt_trade(a, 10, 69, 50, date(2025, 1, 1), "NIFTY", Fl(), ch) is None
    assert Y.opt_trade(None, 10, 70, 50, date(2025, 1, 1), "NIFTY", Fl(), ch) is None


# ------------------------------------------------------------------------------------------------ Amendment 1
def test_ovn_target_entry_reads_day_d_up_to_t_only():
    c = np.linspace(100, 101, K.N)
    o2 = np.full(K.N, np.nan)
    o2[3:] = 102.0
    c2 = np.full(K.N, 103.0)
    c2[-5:] = np.nan
    t = K.row(14, 54)
    g = F.ovn_target(c, t, o2, c2, "GAP_T")
    assert g == pytest.approx(np.log(102.0 / c[t]) * 100)
    assert F.ovn_target(c, t, o2, c2, "C2C_T") == pytest.approx(np.log(103.0 / c[t]) * 100)
    c_late = c.copy()
    c_late[t + 1:] = 1e6                                               # D after 14:54 is never read
    assert F.ovn_target(c_late, t, o2, c2, "GAP_T") == pytest.approx(g)
    assert np.isnan(F.ovn_target(c, t, np.full(K.N, np.nan), c2, "GAP_T"))


def test_opt_overnight_uses_both_days_charges():
    from datetime import date
    a_in = np.full((K.N, 5), np.nan)
    a_out = np.full((K.N, 5), np.nan)
    a_in[339] = [50, 50, 50, 50.0, 10]
    a_out[0] = [60, 60, 60, 60.0, 10]
    seen = []

    class Fl:
        def buy(self, p, v):
            return p

        def sell(self, p, v):
            return p

    def ch(b, px, q, d, u):
        seen.append((b, d))
        return 10.0
    r = Y.opt_overnight(a_in, 339, a_out, 0, 20, date(2025, 1, 1), date(2025, 1, 2), "NIFTY", Fl(), ch)
    assert r["net"] == pytest.approx(10 * 20 - 20)
    assert seen == [(True, date(2025, 1, 1)), (False, date(2025, 1, 2))]
    a_out[0, 3] = np.nan
    assert Y.opt_overnight(a_in, 339, a_out, 0, 20, date(2025, 1, 1), date(2025, 1, 2), "NIFTY", Fl(), ch) is None
