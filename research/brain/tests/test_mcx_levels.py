"""MCX brain levels (MB1): data helpers, the L4 futures simulator and costs, rule signals, walk-forward picks, the
encoder, one walk-forward fold end to end, the US-open rows and the cards renderer. Seeded synthetic data."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mcx_synth as S  # noqa: E402

from research.brain.mcx import clean, config, cube, data as D, features as F, l4, l5, labels, levels as LV  # noqa: E402
from research.brain.mcx import session_event as SE  # noqa: E402


def _cube_rows(closes, start="2025-10-06 09:00", seg="S0"):
    ts = pd.date_range(start, periods=len(closes), freq="min")
    return pd.DataFrame({"ts": ts, "close": np.asarray(closes, float), "flag": "ok", "seg": seg})


# ------------------------------------------------------------------------------------------------ data helpers
def test_decision_mask_every_15_minutes():
    m = _cube_rows(np.full(870, 100.0))
    dec = D.decision_mask(m)
    tods = (m.ts.dt.hour * 60 + m.ts.dt.minute)[dec]
    assert tods.min() == 570 and (tods % 15 == 0).all() and tods.max() <= 23 * 60 + 30 - 75
    m.loc[m.ts.dt.strftime("%H:%M") == "10:00", "close"] = np.nan
    assert not D.decision_mask(m)[(m.ts.dt.strftime("%H:%M") == "10:00").to_numpy()].any()


def test_folds_and_blocks():
    days = [d for d in S.days(60)] + [date(2026, 7, 2)]          # a holdout day is ignored
    fo = D.folds(days)
    assert len(fo) == 4 and fo[0]["test_lo"] > S.days(60)[0] and fo[-1]["test_hi"] == S.days(60)[-1]


def test_boot_p_direction():
    rng = np.random.default_rng(0)
    sess = np.repeat(np.arange(50), 10)
    assert D.boot_p(rng.normal(0.5, 1, 500), sess) < 0.01
    assert D.boot_p(rng.normal(-0.5, 1, 500), sess) > 0.99
    assert np.isnan(D.boot_p(np.array([np.nan]), np.array([0])))


def test_binom_and_logloss():
    assert D.binom_p(70, 100) < 0.001 and D.binom_p(50, 100) > 0.4
    assert D.binom_p(30, 100, two_sided=True) < 0.001
    ll = D.logloss_rows(np.array([1, 0]), np.array([0.9, 0.1]))
    assert np.allclose(ll, -np.log(0.9))


# ------------------------------------------------------------------------------------------------ L4 simulator
def _path(closes, start="2025-10-06 09:00"):
    m = _cube_rows(closes, start)
    return l4.Path_(m)


def test_sim_stop_target_time():
    c = np.full(870, 100.0)
    c[11:20] = 100.0
    c[20] = 99.0                                                      # -1%: stop at 0.5%
    t = l4.sim_fut(_path(c), 10, +1, 0.5)
    assert t["why"] == "stop" and t["exit_row"] == 20 and t["entry_row"] == 11
    c2 = np.full(870, 100.0)
    c2[30] = 101.0
    t = l4.sim_fut(_path(c2), 10, +1, 0.5, tgt_pct=0.8)
    assert t["why"] == "target" and t["exit_row"] == 30
    t = l4.sim_fut(_path(np.full(870, 100.0)), 10, -1, 0.5)
    assert t["why"] == "time" and t["exit_row"] == 11 + l4.HOLD


def test_sim_entry_wait_and_flat():
    c = np.full(870, 100.0)
    c[11:20] = np.nan
    assert l4.sim_fut(_path(c), 10, 1, 0.5) is None                  # no close within 5 minutes: no trade
    P = _path(np.full(870, 100.0))
    i = 870 - 30                                                      # 23:00 bar: flat row is 23:14
    t = l4.sim_fut(P, i, 1, 0.5)
    assert P.tod[t["exit_row"]] == 23 * 60 + 30 - 15 - 1


def test_sim_target_price_level_and_segment_break():
    c = np.full(870, 100.0)
    c[40] = 99.4
    t = l4.sim_fut(_path(c), 10, -1, 1.0, tgt_px=99.5)                # short to the session mean
    assert t["why"] == "target" and t["exit_px"] == 99.4
    m = _cube_rows(np.full(870, 100.0))
    m.loc[30:, "seg"] = "S1"
    t = l4.sim_fut(l4.Path_(m), 10, 1, 0.5)
    assert t["exit_row"] == 29


def test_fut_net_costs():
    t = dict(side=1, entry_px=100_000.0, exit_px=100_500.0, why="time")
    r = l4.fut_net("GOLDM", t)
    from research.hunt.r53 import common as R53
    want = R53.mcx_fut_charges(100_000 * 10, 100_500 * 10) + 2 * 10 * R53.fut_half_spread(100_000.0, 1.0, "gold")
    assert r["gross"] == pytest.approx(5000.0) and r["cost"] == pytest.approx(want)
    assert r["net"] == pytest.approx(r["gross"] - r["cost"])
    s = l4.fut_net("GOLDM", {**t, "why": "stop"})
    assert s["cost"] == pytest.approx(r["cost"] + 0.0002 * 100_500 * 10)
    c = l4.fut_net("CRUDEOIL", dict(side=-1, entry_px=6000.0, exit_px=6000.0, why="time"))
    assert c["gross"] == 0 and c["cost"] >= 2 * 1.0 * 10                # Rs 1/bbl half-spread x 2 x 10 bbl


def test_rule_signals():
    o = pd.DataFrame({"ts": pd.date_range("2025-10-06 10:00", periods=4, freq="15min"), "row": [0, 1, 2, 3],
                      "size_comm": np.log([0.5, 0.5, 0.2, 0.2]), "p_dir": [0.6, 0.4, 0.5, 0.62],
                      "ret_30m": [0.3, -0.1, 0.0, 0.2], "dist_session_mean": [0.0, 0.0, 0.5, -0.1],
                      "iv_range_60m": [0.1, 0.5, 0.1, 0.5], "thr_size_q67": np.log(0.4), "thr_size_q80": np.log(0.6),
                      "thr_size_q33": np.log(0.3), "thr_ratio_q90": 3.0, "thr_ratio_q80": 2.0})
    b1 = l4.signals("B1", dict(thr=0.55), o)
    assert list(b1.side) == [1, -1, 1]
    b2 = l4.signals("B2", dict(q="size_q67"), o)
    assert list(b2.row) == [0] and b2.side.iloc[0] == 1             # 0.3 >= 0.33 x 0.5; row 1 too small
    b3 = l4.signals("B3", dict(k=1.0), o)
    assert list(b3.row) == [2] and b3.side.iloc[0] == -1             # 0.5 >= 0.2 above the mean: fade down
    assert list(l4.signals("B4", dict(q="ratio_q90"), o).row) == [0]          # ratios 5, 1, 2, 0.4 vs 3
    assert list(l4.signals("B4", dict(q="ratio_q80"), o).row) == [0, 2]       # vs 2


def test_walk_forward_picks_from_earlier_blocks():
    a = [dict(block=b, net=n) for b, n in [(0, 10), (1, -5), (2, -5), (3, -5)]]
    b = [dict(block=b, net=n) for b, n in [(0, 0), (1, 20), (2, 20), (3, 20)]]
    wf = l4.walk_forward([a, b])
    assert wf["wf_picks"] == [0, 1, 1] and wf["wf_net"] == -5 + 20 + 20 and wf["frozen"] == 1


def test_run_variant_one_trade_at_a_time():
    m = _cube_rows(np.full(870, 100.0))
    P = l4.Path_(m)
    o = pd.DataFrame({"ts": m.ts.iloc[[60, 75, 150]].values, "row": [60, 75, 150], "size_comm": np.log(0.5),
                      "p_dir": 0.7, "block": 0})
    tr = l4.run_variant("B1", dict(thr=0.55), "GOLDM", o, P, None)
    assert [t["entry_row"] for t in tr] == [61, 151]                  # the 75 signal fires while 61..121 is open


def test_summarize_and_top5():
    tr = [dict(entry_ts=pd.Timestamp("2025-10-06 10:00") + pd.Timedelta(days=i), net=float(n), gross=float(n) + 1,
               cost=1.0, why="time", block=i % 2) for i, n in enumerate([100, -10, 5, 5, 5, 5, 5, -1])]
    s = l4.summarize(tr)
    assert s["trades"] == 8 and s["top5_removed"] == pytest.approx(5 + -1 + -10)
    assert s["by_block"] == {0: 115.0, 1: -10.0 + 5 + 5 - 1}


# ------------------------------------------------------------------------------------------------ levels 1-3
def test_encoder_state_shape_and_determinism():
    X = np.random.default_rng(0).normal(size=(300, 24, 6)).astype(np.float32)
    e1, e2 = LV.Encoder().fit(X), LV.Encoder().fit(X)
    s = e1.state(X[:5])
    assert s.shape == (5, 16) and np.allclose(s, e2.state(X[:5])) and np.all(np.abs(s) <= 1)
    assert e1.pcs(X[:5]).shape == (5, 16)


@pytest.fixture(scope="module")
def fold_out():
    syms, frames, enc = {}, {}, {}
    for k, name in enumerate(["GOLDM", "CRUDEOIL"]):
        sp = S.spot_minutes(24, seed=k)
        sf, _ = clean.spot_frame(S.option_rows(sp))
        m = cube.build_frame(sf, None, name, clean.option_expiries(sf))
        f = F.compute(m, name)
        y = labels.compute(m)
        Sm = D.Sym(name, m.reset_index(drop=True), f, y, D.decision_mask(m))
        syms[name] = Sm
        frames[name] = LV.decision_frame(Sm)
        enc[name] = LV.encoder_inputs(Sm, frames[name])
    days = S.days(24)
    train, test = set(days[:18]), set(days[18:])
    o, info = LV.run_fold(frames, enc, train, test, log=lambda *a: None)
    return o, info, syms, test


def test_run_fold_outputs(fold_out):
    o, info, _, test = fold_out
    assert set(o.sym) == {"GOLDM", "CRUDEOIL"} and set(o.day) <= test
    for c in ("A_hgb", "A_ridge", "A_hgbS", "size_comm", "b_tod", "b_last", "b_iv", "p_dir", "act", "band_lo",
              "regime", "thr_size_q67"):
        assert c in o, c
    assert np.isfinite(o.A_hgb).all() and o.p_dir.between(0, 1).all() and set(o.act) <= {-1, 0, 1}
    assert (o.band_hi >= o.band_lo).all()
    th = info["thresholds"]["GOLDM"]
    assert th["train_sessions"] == 18 and th["inner_cut"] < str(min(test))


def test_run_fold_ignores_test_labels(fold_out):
    """Scrambling the test sessions' labels changes no forecast (models see training sessions only)."""
    o, _, syms, test = fold_out
    frames, enc = {}, {}
    for name, Sm in syms.items():
        y = Sm.y.copy()
        late = Sm.cube.ts.dt.date.isin(test).to_numpy()
        for c in y.columns:
            if c.startswith("y_"):
                y.loc[late, c] = np.random.default_rng(1).normal(size=late.sum())
        S2 = D.Sym(name, Sm.cube, Sm.feats, y, Sm.dec)
        frames[name] = LV.decision_frame(S2)
        enc[name] = LV.encoder_inputs(S2, frames[name])
    days = S.days(24)
    o2, _ = LV.run_fold(frames, enc, set(days[:18]), test, log=lambda *a: None)
    for c in ("A_hgb", "A_ridge", "size_comm", "p_dir"):
        assert np.allclose(o[c], o2[c], equal_nan=True), c


def test_us_open_rows(fold_out):
    _, _, syms, _ = fold_out
    d = SE.us_open_rows(syms["GOLDM"])
    tod = d.ts.dt.hour * 60 + d.ts.dt.minute
    assert set(tod) <= {18 * 60 + 59, 19 * 60 + 59}                  # 09:30 New York minus one minute, IST
    assert d.day.is_unique and d.y_cont.dropna().isin([0, 1]).all()


def test_cards_render():
    out = {"cards": [dict(symbol="ZINC", name="zinc", modelled=False,
                          hourly_range=[dict(hour="09:00", median_pct=0.2, sessions=30),
                                        dict(hour="20:00", median_pct=0.5, sessions=30)])]}
    md = l5.render(out)
    assert "busiest from 20:00" in md and "not modelled" in md
