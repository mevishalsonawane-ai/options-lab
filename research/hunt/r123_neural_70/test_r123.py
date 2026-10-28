"""R123 tests (PREREG section 8): point-in-time guard, labels after t only, the exit simulator, scalers on training
rows only, the holdout lock, the portfolio limits, twins and BH. Synthetic data only (no lake needed).

    python -m pytest research/hunt/r123_neural_70/test_r123.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r123cfg as K  # noqa: E402
import r123data as D  # noqa: E402
import r123net as M  # noqa: E402
import r123rules as R  # noqa: E402

DAY = date(2025, 6, 10)


# ------------------------------------------------------------------------------------------------ synthetic market
def _grid(days, base, rng):
    n = len(days)
    steps = rng.normal(0, base * 0.0004, (n, K.N)).cumsum(1) + base
    c = steps
    o = np.concatenate([c[:, :1], c[:, :-1]], 1)
    h = np.maximum(o, c) + abs(rng.normal(0, base * 0.0002, (n, K.N)))
    lo = np.minimum(o, c) - abs(rng.normal(0, base * 0.0002, (n, K.N)))
    return D.Grid(list(days), o, h, lo, c)


def _market(seed=0, ndays=30):
    rng = np.random.default_rng(seed)
    days = [DAY - timedelta(days=ndays - 1 - i) for i in range(ndays)]
    grids = {u: _grid(days, b, rng) for u, b in zip(K.UNDS, (25000, 55000, 26000, 13000, 82000))}
    vix = _grid(days, 14.0, rng)
    return grids, vix


def _chain(seed=1):
    rng = np.random.default_rng(seed)
    ch = {k: np.abs(rng.normal(100, 20, (K.N, D.OFFS, 2))) for k in ("close", "oi", "iv", "volume")}
    ch["oi"] *= 1000
    ch["close"][rng.random((K.N, D.OFFS, 2)) < 0.05] = np.nan
    return ch


def _perturb_after(grids, vix, ch, i, t, rng):
    g2 = {}
    for u, g in grids.items():
        a = [x.copy() for x in (g.o, g.h, g.l, g.c)]
        for x in a:
            x[i, t + 1:] = rng.normal(1e5, 1e4, K.N - t - 1)
            x[i + 1:] = rng.normal(1e5, 1e4, x[i + 1:].shape)
        g2[u] = D.Grid(g.days, *a)
    v = [x.copy() for x in (vix.o, vix.h, vix.l, vix.c)]
    for x in v:
        x[i, t + 1:] = 99.0
        x[i + 1:] = 99.0
    ch2 = {k: x.copy() for k, x in ch.items()}
    for x in ch2.values():
        x[t + 1:] = 7.0
    return g2, D.Grid(vix.days, *v), ch2


def test_features_point_in_time():
    grids, vix = _market()
    ch = _chain()
    rng = np.random.default_rng(5)
    i = 25
    ctx = dict(to_expiry=2, is_expiry=False, is_budget=False)
    for und in ("NIFTY", "SENSEX"):
        for t in (K.DECISION_ROWS[0], 154, K.DECISION_ROWS[-1]):
            I = D.IndexData(und, grids, vix)
            f1 = D.tab_features(I, i, t, ctx, ch)
            g2, v2, ch2 = _perturb_after(grids, vix, ch, i, t, rng)
            I2 = D.IndexData(und, g2, v2)
            f2 = D.tab_features(I2, i, t, ctx, ch2)
            assert set(f1) == set(D.TAB_COLS)
            for k in D.TAB_COLS:
                a, b = f1[k], f2[k]
                assert (np.isnan(a) and np.isnan(b)) or a == pytest.approx(b, rel=1e-12, abs=1e-12), (und, t, k)
            p = I.gpos(i, t)
            np.testing.assert_array_equal(I.seq[p - K.SEQ_N + 1:p + 1], I2.seq[p - K.SEQ_N + 1:p + 1])


def test_feature_changes_when_bar_t_changes():
    """The guard is not vacuous: changing bar t itself does change the inputs."""
    grids, vix = _market()
    i, t = 25, 154
    I = D.IndexData("NIFTY", grids, vix)
    f1 = D.tab_features(I, i, t, dict(to_expiry=1, is_expiry=False, is_budget=False), None)
    grids["NIFTY"].c[i, t] *= 1.01
    I2 = D.IndexData("NIFTY", grids, vix)
    f2 = D.tab_features(I2, i, t, dict(to_expiry=1, is_expiry=False, is_budget=False), None)
    assert f1["from_open"] != f2["from_open"]


def test_seqstore_window_ends_at_t_and_pads():
    a = np.arange(1000 * 7, dtype=np.float32).reshape(1000, 7)
    a[:, -1] = 1
    st = M.SeqStore({"NIFTY": a})
    fp = st.flat(np.array(["NIFTY", "NIFTY"]), np.array([500, 3]))
    W = st.A[fp[:, None] + np.arange(-K.SEQ_N + 1, 1)[None, :]]
    np.testing.assert_array_equal(W[0, -1], a[500])
    np.testing.assert_array_equal(W[0, 0], a[500 - K.SEQ_N + 1])
    assert (W[1, :K.SEQ_N - 4, -1] == 0).all() and (W[1, -4:, -1] == 1).all()


def test_index_labels_read_after_t_only():
    grids, vix = _market()
    i, t = 25, 100
    I = D.IndexData("NIFTY", grids, vix)
    y1 = D.index_labels(I, i, t)
    g = grids["NIFTY"]
    g.h[i, :t] += 500
    g.l[i, :t] -= 500
    g.h[i, t + 61:] += 500
    I2 = D.IndexData("NIFTY", grids, vix)
    y2 = D.index_labels(I2, i, t)
    for k in y1:
        assert y1[k] == pytest.approx(y2[k])
    g.h[i, t + 5] += 500
    assert D.index_labels(D.IndexData("NIFTY", grids, vix), i, t)["y_range_30"] > y1["y_range_30"]


# ------------------------------------------------------------------------------------------------ the simulator
def _bars(prices, vol=10000.0):
    a = np.full((K.N, 5), np.nan)
    for r, (o, h, lo, c) in prices.items():
        a[r] = (o, h, lo, c, vol)
    return a


def _flat(p=100.0, rows=range(0, 200)):
    return {r: (p, p, p, p) for r in rows}


@pytest.mark.parametrize("und,lot", [("NIFTY", 65), ("BANKNIFTY", 35), ("SENSEX", 20)])
@pytest.mark.parametrize("x,y", [(1000.0, 1000.0), (1000.0, 2000.0), (2000.0, 1000.0)])
def test_target_and_stop_net_close_to_x_and_y(und, lot, x, y):
    buy = 150.0
    pt, sp = D.target_stop(buy, lot, x, y, 1e9, DAY, und)
    d = DAY
    ch_b = D.charge(True, buy, lot, d, und)
    sell_t = float(D.FILLS.sell(np.array([pt]), np.array([1e9]))[0])
    net_t = (sell_t - buy) * lot - ch_b - D.charge(False, sell_t, lot, d, und)
    assert x - 1 <= net_t <= x + D.TICK * lot * 1.2 + 1                  # paisa rounding of the fills
    sell_s = float(D.FILLS.sell(np.array([sp]), np.array([1e9]), stop=True)[0])
    net_s = (sell_s - buy) * lot - ch_b - D.charge(False, sell_s, lot, d, und)
    assert -y - 1 <= net_s <= -y + D.TICK * lot * 1.2 + 1


def test_simulate_target_stop_timeout_and_stop_first():
    lot, und = 65, "NIFTY"
    pr = _flat(100.0)
    a = _bars(pr)
    r = D.simulate(a, 10, 40, lot, DAY, und, 1000, 1000)
    assert r["cls"] == K.NEITHER and r["exit_row"] == 40 and r["net"] < 0         # costs only
    pt, sp = r["pt"], r["sp"]
    b = dict(pr)
    b[20] = (100, pt + 1, 100, 100)
    r = D.simulate(_bars(b), 10, 40, lot, DAY, und, 1000, 1000)
    assert r["cls"] == K.WIN and r["exit_row"] == 20 and r["net"] >= 1000
    b[15] = (100, 100, sp - 1, 100)
    r = D.simulate(_bars(b), 10, 40, lot, DAY, und, 1000, 1000)
    assert r["cls"] == K.LOSS and r["exit_row"] == 15 and -1000 - 30 <= r["net"] <= -1000 + 10
    b2 = dict(pr)
    b2[20] = (100, pt + 1, sp - 1, 100)                                              # both in one bar: the stop
    r = D.simulate(_bars(b2), 10, 40, lot, DAY, und, 1000, 1000)
    assert r["cls"] == K.LOSS
    b3 = dict(pr)
    b3[20] = (sp - 10, sp - 9, sp - 11, sp - 10)                                     # gap through the stop
    r = D.simulate(_bars(b3), 10, 40, lot, DAY, und, 1000, 1000)
    assert r["cls"] == K.LOSS and r["net"] < -1000 - 300
    b4 = dict(pr)
    b4[45] = (100, pt + 50, 100, 100)                                                # after the window: ignored
    r = D.simulate(_bars(b4), 10, 40, lot, DAY, und, 1000, 1000)
    assert r["cls"] == K.NEITHER


def test_simulate_needs_entry_bar_and_skips_missing():
    a = _bars(_flat(100.0))
    a[11] = np.nan
    assert D.simulate(a, 10, 40, 65, DAY, "NIFTY", 1000, 1000) is None
    a = _bars(_flat(100.0))
    a[30:41] = np.nan
    r = D.simulate(a, 10, 40, 65, DAY, "NIFTY", 1000, 1000)
    assert r["cls"] == K.NEITHER and r["exit_row"] == 29


def test_slippage_x2_is_worse():
    a = _bars(_flat(100.0))
    r = D.simulate(a, 10, 40, 65, DAY, "NIFTY", 1000, 1000)
    assert r["net_x2"] < r["net"]


def test_trade_labels_square_off_and_undefined():
    grids, vix = _market()
    I = D.IndexData("NIFTY", grids, vix)

    class O:
        contracts = {}
    lab = D.trade_labels(O(), I, 25, 300, False)
    assert all(v == K.UNDEF for k, v in lab.items() if k.startswith("cls_"))
    k = D.atm(I.cf[I.gpos(25, K.EXIT_LAST_EXPIRY - 20)], K.STEP["NIFTY"])
    O.contracts = {("C", k): _bars(_flat(100.0, range(K.N))), ("P", k): _bars(_flat(100.0, range(K.N)))}
    lab = D.trade_labels(O(), I, 25, K.EXIT_LAST_EXPIRY - 40, True)               # expiry: 30 ok, 60 passes 15:05
    for j in range(len(K.CS)):
        h = K.CELLS[j // 2][2]
        assert (lab[f"cls_{j}"] == K.UNDEF) == (h == 60)


def test_atm_half_up():
    assert D.atm(25024.9, 50) == 25000 and D.atm(25025.0, 50) == 25050


# ------------------------------------------------------------------------------------------------ scalers
def test_tab_scaler_fits_training_rows_only():
    rng = np.random.default_rng(0)
    tr = rng.normal(0, 1, (500, 4))
    t1 = M.Tab().fit(tr)
    te = rng.normal(5, 3, (200, 4))
    t2 = M.Tab().fit(tr)
    np.testing.assert_allclose(t1.transform(te), t2.transform(te))
    np.testing.assert_allclose(t1.mu, tr.mean(0))


def test_seqstore_fit_reads_given_rows_only():
    a = np.random.default_rng(0).normal(0, 1, (2000, 7)).astype(np.float32)
    a[:, -1] = 1
    a[1500:, :5] = 1000
    st = M.SeqStore({"NIFTY": a}).fit(M.SeqStore({"NIFTY": a}).flat(np.array(["NIFTY"] * 500),
                                                                   np.arange(200, 700)))
    assert abs(st.mu[0]) < 0.5


# ------------------------------------------------------------------------------------------------ holdout lock
def test_holdout_lock(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "OUT", tmp_path)
    monkeypatch.setattr(K, "RESULTS", tmp_path / "results")
    with pytest.raises(D.HoldoutError):
        D.build("holdout")
    D.holdout_gate("start")
    assert D.holdout_open()
    D.holdout_gate("done")
    with pytest.raises(D.HoldoutError):
        D.holdout_gate("start")
    (tmp_path / "HOLDOUT_READ.json").unlink()
    (tmp_path / "results").mkdir()
    (tmp_path / "results" / "holdout.json").write_text("{}")
    with pytest.raises(D.HoldoutError):
        D.holdout_gate("start")


def test_periods_disjoint():
    assert K.part(date(2026, 3, 15)) == "design" and K.part(date(2026, 3, 20)) == "embargo"
    assert K.part(date(2026, 4, 1)) == "holdout" and K.fold_of(date(2026, 4, 1)) is None
    assert K.fold_of(date(2024, 5, 5)) == "F2"


# ------------------------------------------------------------------------------------------------ rules
def _frame(n_rows=6, und="NIFTY", day=DAY):
    rows = []
    for k in range(n_rows):
        r = dict(und=und, day=day, row=K.DECISION_ROWS[k], close=25000.0, rg60=np.log(0.5))
        for j in range(len(K.CS)):
            r[f"cls_{j}"] = K.WIN
            r[f"net_{j}"] = 1000.0 + j
            r[f"netx2_{j}"] = 990.0
            r[f"exit_{j}"] = K.DECISION_ROWS[k] + 3
            r[f"pw_{j}"] = 0.5
            r[f"ev_{j}"] = 0.0
        rows.append(r)
    return pd.DataFrame(rows)


def test_candidates_p_rule_side_threshold_and_validity():
    F = _frame(3)
    c = K.RULES["S1"]["cell"]
    F.loc[0, f"pw_{2 * c}"], F.loc[0, f"pw_{2 * c + 1}"] = 0.72, 0.80
    F.loc[0, f"cls_{2 * c + 1}"] = K.UNDEF                       # the better side cannot be filled
    F.loc[1, f"pw_{2 * c}"] = 0.69
    F.loc[2, f"pw_{2 * c + 1}"] = 0.75
    C = R.candidates(F, "S1")
    assert list(C.row) == [F.row[0], F.row[2]] and list(C.j) == [2 * c, 2 * c + 1]


def test_room_filter():
    F = _frame(2)
    c = K.RULES["S2"]["cell"]
    F[f"pw_{2 * c}"] = 0.9
    F.loc[0, "rg60"] = np.log(0.5)         # 125 pts x 0.5 x 65 = 4,062 >= 2,000
    F.loc[1, "rg60"] = np.log(0.1)         # 25 pts -> 812 < 2,000
    assert list(R.candidates(F, "S2").row) == [F.row[0]]


def test_ev_rule_and_morning_rows():
    F = _frame(5)
    F.loc[3, "ev_7"] = 400.0
    F.loc[0, "ev_5"] = 350.0
    C = R.candidates(F, "S4")
    assert list(C.j) == [5, 7]
    C5 = R.candidates(F, "S5")
    assert list(C5.row) == [F.row[0]]


def test_portfolio_one_open_per_index_and_day_cap():
    rows = []
    for k, u in enumerate(["NIFTY", "NIFTY", "BANKNIFTY", "SENSEX", "FINNIFTY", "MIDCPNIFTY", "NIFTY", "BANKNIFTY"]):
        rows.append(dict(und=u, day=DAY, row=K.DECISION_ROWS[k // 2], j=0, score=0.8 - k * 0.01, cls=0,
                         net=1.0, netx2=1.0, exit=K.DECISION_ROWS[k // 2] + 30))
    T = R.portfolio(pd.DataFrame(rows))
    assert len(T) <= K.DAY_MAX
    for u, g in T.groupby("und"):
        rr = g.sort_values("row")
        assert (rr.row.values[1:] >= rr.exit.values[:-1]).all()


def test_twins_same_pools_and_deterministic():
    F = pd.concat([_frame(6), _frame(6, und="BANKNIFTY")], ignore_index=True)
    pools = R.twin_pools(F)
    T = R.candidates(F.assign(**{f"ev_{j}": 500.0 if j == 3 else 0.0 for j in range(len(K.CS))}), "S4").head(4)
    t1, t2 = R.twins(T, pools, n=200), R.twins(T, pools, n=200)
    np.testing.assert_array_equal(t1, t2)
    nets = 1000.0 + np.arange(len(K.CS))
    assert t1.min() >= 4 * nets[2] and t1.max() <= 4 * nets[3]       # cell 1 (j 2 or 3) only


def test_bh_and_p():
    q = R.bh([0.01, 0.04, 0.03, 0.5])
    assert q == pytest.approx([0.04, 0.0533333, 0.0533333, 0.5], rel=1e-4)
    assert R.p_vs(10.0, np.array([1.0, 2.0, 11.0])) == pytest.approx(2 / 4)


def test_stats_drawdown_and_month():
    T = pd.DataFrame(dict(und="NIFTY", day=[DAY, DAY, DAY + timedelta(days=1)], j=0, cls=[0, 1, 0],
                          net=[1000.0, -3000.0, 500.0], netx2=[990.0, -3010.0, 490.0]))
    s = R.stats(T, [DAY, DAY + timedelta(days=1), DAY + timedelta(days=2)])
    assert s["max_dd"] == -3000.0 and s["worst_day"] == -2000.0 and s["win_rate"] == pytest.approx(2 / 3)
    assert s["per_session"] == pytest.approx(-1500 / 3)


# ------------------------------------------------------------------------------------------------ Amendment 1 (N2)
def test_daily_store_reads_only_earlier_sessions():
    grids, vix = _market()
    a = M.daily_bars(grids["NIFTY"], vix)
    st = M.DailyStore({"NIFTY": a})
    i = 25
    fp = st.flat(np.array(["NIFTY"]), np.array([i * K.N + 200]))
    W = st.A[fp[:, None] + np.arange(-M.DailyStore.SLOW_N, 0)[None, :]][0]
    np.testing.assert_array_equal(W[-1], a[i - 1])
    np.testing.assert_array_equal(W[0], a[i - M.DailyStore.SLOW_N])
    grids["NIFTY"].c[i:] *= 1.05                                         # today and later change: window does not
    grids["NIFTY"].h[i:] *= 1.05
    b = M.daily_bars(grids["NIFTY"], vix)
    W2 = M.DailyStore({"NIFTY": b}).A[fp[:, None] + np.arange(-M.DailyStore.SLOW_N, 0)[None, :]][0]
    np.testing.assert_array_equal(W, W2)


def test_daily_store_pads_first_sessions():
    grids, vix = _market()
    st = M.DailyStore({"NIFTY": M.daily_bars(grids["NIFTY"], vix)})
    W = st.window(st.flat(np.array(["NIFTY"]), np.array([3 * K.N + 10])))[0]
    assert (W[:M.DailyStore.SLOW_N - 3, -1] == 0).all()


def test_temperatures_fit_on_given_rows_and_recover_scale():
    rng = np.random.default_rng(0)
    n = 4000
    true = rng.normal(0, 1, (n, 1, 3))
    y = np.array([rng.choice(3, p=np.exp(z) / np.exp(z).sum()) for z in true[:, 0]])
    L = np.stack([true * 2.0, true * 2.0])                              # over-confident by 2x, 2 seeds
    cls = np.full((n, 1), -1)
    cls[:, 0] = y
    T = M.fit_temperatures(L, cls)
    assert 1.6 <= T[0] <= 2.4
    cls2 = cls.copy()
    cls2[:50] = -1                                                       # too few rows: left at 1
    assert M.fit_temperatures(L[:, :120], cls2[:120])[0] == 1.0


def test_abstention_drops_only_the_chosen_side():
    F = _frame(3)
    c = K.RULES["S1"]["cell"]
    F[f"pw_{2 * c}"] = 0.8
    F[f"pw_{2 * c + 1}"] = 0.75
    for j in range(len(K.CS)):
        F[f"abst_{j}"] = False
    F.loc[0, f"abst_{2 * c}"] = True                                     # chosen side abstains: no trade at all
    F.loc[1, f"abst_{2 * c + 1}"] = True                                 # the other side abstains: trade stands
    C = R.candidates(F, "S1")
    assert list(C.row) == [F.row[1], F.row[2]] and (C.j == 2 * c).all()


def test_attach_marks_abstention_above_threshold():
    F = _frame(2)
    P = np.full((2, len(K.CS), 3), 1 / 3)
    sp = np.zeros((2, len(K.CS)))
    sp[0, 3] = K.ABSTAIN + 0.01
    sp[1, 3] = K.ABSTAIN
    out = R.attach(F, dict(probs=P, range=np.zeros((2, 2)), dir=np.full((2, 2), .5), spread=sp), {})
    assert bool(out.abst_3[0]) and not bool(out.abst_3[1])


def test_n2_forward_shapes_and_gate():
    torch = pytest.importorskip("torch")
    gi = {k: list(range(3)) for k in M.SPECIALISTS}
    net = M.make_multi(gi)
    out = net(torch.zeros(4, K.SEQ_N, 7), torch.zeros(4, 10), torch.zeros(4, M.DailyStore.SLOW_N, 7))
    assert out[0].shape == (4, len(K.CS), 3) and out[5].shape == (4, 4)
    assert torch.allclose(out[5].sum(1), torch.ones(4))


def test_tcn_is_causal():
    torch = pytest.importorskip("torch")
    torch.manual_seed(0)
    net = M.make_net(5).eval()
    x = torch.randn(2, K.SEQ_N, 7)
    tab = torch.zeros(2, 5)
    # the first minute of the window reaches the output (the whole window is read), and each conv is left-padded
    # only, so a minute never sees a later one
    y1 = net(x, tab)[0]
    x2 = x.clone()
    x2[:, 0] += 5
    assert not torch.allclose(net(x2, tab)[0], y1)
    h = net.inp(x.transpose(1, 2))
    cv = net.convs[0]
    h2 = h.clone()
    h2[:, :, 50:] += 3
    np.testing.assert_allclose(cv(h)[:, :, :50].detach().numpy(), cv(h2)[:, :, :50].detach().numpy(), atol=1e-6)


# ------------------------------------------------------------------------------------------------ Amendment 2
def test_zero_volume_minutes_are_not_prints():
    rows = []
    for m in range(10):
        rows.append(dict(m=m, offset=0, side="P", strike=25000.0, spot=25000.0, open=100.0, high=100.0, low=100.0,
                         close=100.0, volume=0 if m in (3, 4) else 650, oi=1, iv=10.0))
    o = D.DayOptions(pd.DataFrame(rows), 50.0)
    a = o.contracts[("P", 25000.0)]
    assert np.isnan(a[3]).all() and np.isnan(a[4]).all() and np.isfinite(a[5]).all()
    assert D.simulate(a, 2, 8, 65, DAY, "NIFTY", 1000, 1000) is None             # no traded bar to buy at


def test_lake_cleaning_drops_foreign_rows():
    rows = []
    for m in range(10):
        for sp in (25000.0, 13000.0):                                           # 13000: another index's row
            rows.append(dict(d=DAY, m=m, offset=0, side="C", strike=25000.0 if sp > 20000 else 13000.0, spot=sp,
                             open=100.0, high=101.0, low=99.0, close=100.0, volume=650, oi=1, iv=10.0))
    LO = D.LakeOptions.__new__(D.LakeOptions)
    LO.und, LO.year, LO.by_day = "NIFTY", DAY.year, {DAY: pd.DataFrame(rows)}
    lg = {}
    o = LO.day(DAY, np.full(K.N, 25000.0), lg)
    assert set(o.contracts) == {("C", 25000.0)} and lg["foreign"] == 10
