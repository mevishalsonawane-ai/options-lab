"""Tests for the R53 gold book (rules.py, twins.py, run.py) on synthetic 1-minute bars: each adapter against the study's
own code, fills (next bar, ask/bid, gap fills), swap counting, weekend / holiday gaps, empty data, the period filter,
the twins, the final-test lock and the look-ahead guard (truncating or changing the data after a trade's fill must not
change that trade or any earlier one)."""
from __future__ import annotations

import json
import sys
import warnings

import numpy as np
import pandas as pd
import pytest

from research.hunt.r53 import common as C
from research.hunt.r53.gold import rules as R
from research.hunt.r53.gold import run as RUN
from research.hunt.r53.gold import twins as T

warnings.filterwarnings("ignore")


# ------------------------------------------------------------------------------------------------ synthetic gold
def synth(n_days=75, seed=1, start="2025-01-05", holidays=(), drift_every=1500):
    """Gold-like 1-minute BID bars: Sunday 22:00 to Friday 21:00 UTC with the daily 21:00-22:00 break, a random walk with
    changing drift (so trends, flips and sweeps happen), occasional gaps at the opens. Index: naive UTC."""
    rng = np.random.default_rng(seed)
    t0 = pd.Timestamp(start)
    idx = []
    for k in range(n_days):
        day = t0 + pd.Timedelta(days=k)
        if day.normalize() in [pd.Timestamp(h) for h in holidays]:
            continue
        dow = day.dayofweek
        mins = []
        if dow == 6:
            mins = range(22 * 60, 24 * 60)
        elif dow in (0, 1, 2, 3):
            mins = list(range(0, 21 * 60)) + list(range(22 * 60, 24 * 60))
        elif dow == 4:
            mins = range(0, 21 * 60)
        idx += [day + pd.Timedelta(minutes=m) for m in mins]
    idx = pd.DatetimeIndex(idx)
    n = len(idx)
    drift = np.repeat(rng.normal(0, 0.06, n // drift_every + 1), drift_every)[:n]
    step = drift + rng.normal(0, 0.45, n)
    gaps = np.r_[0, np.diff(idx.values).astype("timedelta64[m]").astype(int) > 1]
    step += gaps * rng.normal(0, 4.0, n)
    close = 2600 + np.cumsum(step)
    open_ = np.r_[close[0], close[:-1]] + gaps * rng.normal(0, 1.0, n)
    hi = np.maximum(open_, close) + np.abs(rng.normal(0, 0.25, n))
    lo = np.minimum(open_, close) - np.abs(rng.normal(0, 0.25, n))
    return pd.DataFrame({"open": open_, "high": hi, "low": lo, "close": close, "volume": rng.uniform(0.01, 1.0, n)}, index=idx)


def to_csv(df, path):
    out = df.copy()
    out.insert(0, "timestamp", (out.index.astype("datetime64[ms]").astype(np.int64)))
    out.to_csv(path, index=False)
    return str(path)


@pytest.fixture(scope="module")
def data():
    return synth()


@pytest.fixture(scope="module")
def ctx(data):
    return R.Ctx(data)


ALL = [(r, v) for r, s in R.RULES.items() for v in s["variants"]]


# ------------------------------------------------------------------------------------------------ loader
def test_load_matches_shared_loader_and_keeps_volume(tmp_path, data):
    p = to_csv(data.iloc[:5000], tmp_path / "x.csv")
    b = R.load(p)
    ref = R.lg.read(p)
    pd.testing.assert_frame_equal(b[R.OHLC], ref)
    assert np.allclose(b.volume.values, data.volume.values[:5000])
    cut = R.load(p, end=str(data.index[3000]))
    assert cut.index.max() < data.index[3000] and len(cut) == 3000


# ------------------------------------------------------------------------------------------------ adapter faithfulness
@pytest.mark.parametrize("rule,var", [k for k in ALL if k[0] not in ("GB6", "GB12", "GB13")])
def test_adapter_equals_the_study_function(ctx, rule, var):
    """With the study's own fills (gap=False), every adapter gives exactly the study function's trades."""
    mine = sorted(t["net"] for t in RUN.study_fn_for_repro(ctx, rule, var))
    own = sorted(RUN.study_own(ctx, rule, var))
    assert len(mine) == len(own)
    assert np.allclose(mine, own, atol=1e-6)


def test_some_rules_trade_on_synthetic_data(ctx):
    n = {r: len(RUN.study_fn_for_repro(ctx, r, "orig")) for r in ("GB1", "GB2", "GB3", "GB5-1h", "GB6", "GB7", "GB9",
                                                                  "GB10", "GB11", "GB12", "GB13")}
    assert all(v > 0 for v in n.values()), n


def _row(lines, start):
    row = next(x for x in lines if x.startswith(start))
    cells = [c.strip() for c in row.strip("|").split("|")]
    return cells


def test_gb6_matches_gold_reopen_on_synthetic(tmp_path, data, monkeypatch):
    import gold_reopen
    p = to_csv(data, tmp_path / "x.csv")
    lines = gold_reopen.by_reopen(p)
    cells = _row(lines, "| reopen +5 min | reopen +120 min")
    years = len(cells) - 8
    total, n = float(cells[2 + years].replace(",", "")), int(cells[3 + years])
    mine = R.gb6_reopen(R.Ctx(R.load(p)), 5, 120)
    assert len(mine) == n and abs(sum(t["net"] for t in mine) - total) < 1
    out = tmp_path / "o.md"
    monkeypatch.setattr(sys, "argv", ["x", p, str(out)])
    gold_reopen.main()
    cells = _row(out.read_text().splitlines(), "| 23:00-23:55")
    total, n = float(cells[2 + years].replace(",", "")), int(cells[3 + years])
    mine = R.gb6_fixed(R.Ctx(R.load(p)), "23:00", "23:55")
    assert len(mine) == n and abs(sum(t["net"] for t in mine) - total) < 1


def test_gb12_matches_gold_mtf_lock_baseline_on_synthetic(tmp_path, monkeypatch):
    """gold_mtf_lock.main prints its baseline (no stop, no lock, 8 candles): the copied loop must give the same. Its
    main() needs a fitting and a held-out Oct-Sep year, so the bars straddle 1 Oct."""
    import gold_mtf_lock
    p = to_csv(synth(n_days=90, seed=7, start="2025-08-17"), tmp_path / "x.csv")
    monkeypatch.setattr(sys, "argv", ["x", p, str(tmp_path / "o.md")])
    gold_mtf_lock.main()
    lines = (tmp_path / "o.md").read_text().splitlines()
    cells = _row(lines, "| stop none, lock none, max 4 h")
    years = len(cells) - 8
    total, n = float(cells[1 + years].replace(",", "")), int(cells[2 + years])
    mine = R.gb12(R.Ctx(R.load(p)), "none", 0, 8)
    assert len(mine) == n and abs(sum(t["net"] for t in mine) - total) < 1


def _F(o, h, l, c, atr=1.0, gap_after=None):
    n = len(c)
    return dict(o=np.array(o, float), hi=np.array(h, float), lo=np.array(l, float), c=np.array(c, float),
                atr=np.full(n, atr), gap_after=np.zeros(n, bool) if gap_after is None else np.array(gap_after))


def test_gb12_keep_half_and_giveback_locks_by_hand():
    # entry after candle 0 at o[1] + 0.15 = 100.15; the high (bid) reaches 103.15 (3 A up) -> keep half: stop 101.65
    F = _F(o=[100, 100, 101, 103, 102], h=[100, 101, 103.3, 103.3, 102], l=[100, 99.9, 100.9, 101.9, 101.0], c=[100, 101, 103, 102, 101.2])
    e, (j, px, why) = R.mtf_exit(F, 0, "none", 0, "half", 0, 16)
    assert e == pytest.approx(100.15)
    assert why == "stop/lock" and j == 4 and px == pytest.approx(100.15 + 0.5 * (103.15 - 100.15))
    # giveback 1.5 A once 1 A up: peak 103.15 -> stop 101.65; candle 4 OPENS below it (101.85 bid? no: 102-0.15) ...
    e, (j, px, why) = R.mtf_exit(F, 0, "none", 0, "giveback", 1.5, 16)
    assert why == "stop/lock" and px == pytest.approx(min(103.15 - 1.5, 102 - 0.15))
    # gap through the lock: the candle opens far below -> filled at its open (the worse price)
    F2 = _F(o=[100, 100, 101, 95], h=[100, 101, 103.3, 95.5], l=[100, 99.9, 100.9, 94], c=[100, 101, 103, 95])
    _, (j, px, why) = R.mtf_exit(F2, 0, "none", 0, "half", 0, 16)
    assert j == 3 and px == pytest.approx(95 - 0.15)
    # the daily break ends the trade at that candle's close; time limit too
    F3 = _F(o=[100] * 6, h=[100.2] * 6, l=[99.8] * 6, c=[100] * 6, gap_after=[0, 0, 1, 0, 0, 0])
    _, (j, px, why) = R.mtf_exit(F3, 0, "none", 0, "half", 0, 16)
    assert (j, why) == (2, "break")
    _, (j, px, why) = R.mtf_exit(_F(o=[100] * 30, h=[100.2] * 30, l=[99.8] * 30, c=[100] * 30), 0, "none", 0, "half", 0, 16)
    assert (j, why) == (16, "time")


def test_gb13_uses_the_study_run_and_charges_swap(ctx):
    tr = R.gb13(ctx, 3)
    h1, up, newbar, A = ctx.get("trend", None)
    raw = R.tl.run(h1, up, newbar, A, "giveback", 3, False)
    assert len(tr) == len(raw)
    for t, (t0, e, t1, x) in zip(tr, raw):
        assert t["net"] == pytest.approx(100 * (x - e - R.COMM - R.SWAP * R.gt.nights(t0, t1)))


# ------------------------------------------------------------------------------------------------ fills
@pytest.mark.parametrize("rule,var", ALL)
def test_next_bar_fill_at_the_ask(data, ctx, rule, var):
    tr = R.RULES[rule]["variants"][var]["fn"](ctx)
    for t in tr:
        assert t["entry_ts"] >= t["signal_ts"] - pd.Timedelta(minutes=0), t
        assert t["exit_ts"] >= t["entry_ts"]
        assert t["entry"] == pytest.approx(data.open.loc[t["entry_ts"]] + 0.30), (rule, var, t)
        assert t["side"] == 1


def _minutes(rows):
    idx = pd.date_range("2025-03-03 08:00", periods=len(rows), freq="1min")
    mid = pd.DataFrame(rows, columns=["open", "high", "low", "close"], index=idx)
    return R.gs.Minutes(mid)


def test_walk_stop_target_gap_and_cutoff():
    # mid bars; a long bought at minute 0's open: ask 100.15
    M = _minutes([(100, 100.5, 99.9, 100.2), (100.2, 100.4, 99.0, 99.2), (99.2, 99.4, 99.1, 99.3)])
    usd, why, i, px = R.walk(M, 0, 1, 99.5, 102, gap=True)
    assert (why, i, px) == ("stop", 1, 99.5) and usd == pytest.approx(99.5 - 100.15 - 0.07)
    # the minute opens through the stop: R53 fills at that open (bid), the study at the stop
    M = _minutes([(100, 100.5, 99.9, 100.2), (98.0, 98.4, 97.9, 98.2)])
    assert R.walk(M, 0, 1, 99.5, 102, gap=True)[3] == pytest.approx(98.0 - 0.15)
    assert R.walk(M, 0, 1, 99.5, 102, gap=False)[3] == pytest.approx(99.5)
    # stop first when one minute touches both
    M = _minutes([(100, 100.5, 99.9, 100.2), (100.2, 103, 99, 100)])
    assert R.walk(M, 0, 1, 99.5, 102, gap=True)[1] == "stop"
    M = _minutes([(100, 100.5, 99.9, 100.2), (100.2, 103, 100, 100)])
    usd, why, i, px = R.walk(M, 0, 1, 99.5, 102, gap=True)
    assert why == "target" and px == 102
    # 20:40 UTC cut-off at that minute's open (bid)
    idx = pd.date_range("2025-03-03 20:38", periods=4, freq="1min")
    mid = pd.DataFrame([(100, 100.1, 99.9, 100)] * 4, columns=["open", "high", "low", "close"], index=idx)
    usd, why, i, px = R.walk(R.gs.Minutes(mid), 0, 1, 90, 110)
    assert why == "cut-off" and i == 2 and px == pytest.approx(99.85)


def test_ai_exit_gap_fill_is_the_worse_price():
    o = np.array([100, 100, 95.0]); h = np.array([101, 100.5, 96]); l = np.array([99, 99.6, 94]); c = np.array([100, 100.2, 95])
    a = np.ones(3)
    j, px, why = R.ai_exit(o, h, l, c, a, 0, 1, gap=True)    # stop = 99 (close 100 - 1 ATR); bar 2 opens at 95
    assert (j, why) == (2, "stop") and px == pytest.approx(95 - 0.15)
    assert R.ai_exit(o, h, l, c, a, 0, 1, gap=False)[1] == pytest.approx(99)


# ------------------------------------------------------------------------------------------------ swap
def test_swap_nights_counting():
    n = R.nights
    assert n("2025-03-05 20:00", "2025-03-05 22:00") == 3          # a Wednesday rollover counts three
    assert n("2025-03-04 20:00", "2025-03-04 22:00") == 1          # Tuesday
    assert n("2025-03-07 20:00", "2025-03-10 22:00") == 2          # Friday + Monday, the weekend free
    assert n("2025-03-03 21:00", "2025-03-04 20:59") == 0          # the 21:00 at the entry itself is not passed
    assert n("2025-03-03 10:00", "2025-03-03 20:00") == 0
    rng = np.random.default_rng(3)
    t0 = pd.Timestamp("2025-01-01") + pd.to_timedelta(rng.integers(0, 60 * 24 * 300, 400), unit="min")
    t1 = t0 + pd.to_timedelta(rng.integers(0, 60 * 24 * 20, 400), unit="min")
    vec = R.nights_vec(t0.values, t1.values)
    assert all(int(v) == R.gt.nights(a, b) for v, a, b in zip(vec, t0, t1))


def test_swap_only_where_the_study_charged_it(ctx):
    for t in R.gb13(ctx, 3):
        assert t["net"] == pytest.approx(100 * (t["exit"] - t["entry"] - R.COMM) - 40 * t["nights"])
    for t in R.gb_tas(ctx, 30, False):          # TAS held overnight but its study charged no swap
        assert t["net"] == pytest.approx(100 * (t["exit"] - t["entry"] - R.COMM))


# ------------------------------------------------------------------------------------------------ gaps, holidays, empty
def test_holiday_and_weekend_gaps():
    d = synth(n_days=40, seed=5, holidays=("2025-01-20",))
    c = R.Ctx(d)
    for rule, var in ALL:
        for t in R.RULES[rule]["variants"][var]["fn"](c):
            assert t["entry_ts"].normalize() != pd.Timestamp("2025-01-20")
            assert t["entry_ts"].dayofweek < 5 or (rule == "GB6" and t["entry_ts"].dayofweek == 6)
    # intraday walk rules never hold over a weekend or a holiday
    for t in R.gb1(c, "2R") + R.gb2(c, 1.0) + R.gb11(c, "2R") + R.gb_liq_india(c):
        assert t["exit_ts"].normalize() == t["entry_ts"].normalize()


def test_empty_and_too_short_data():
    tiny = synth(n_days=3, seed=2)
    c = R.Ctx(tiny)
    for rule, var in ALL:
        out = R.RULES[rule]["variants"][var]["fn"](c)
        assert isinstance(out, list)
    assert C.summary([])["trades"] == 0
    assert np.all(T.random_twins([], 0, "design", np.random.default_rng(0)) == 0)
    M = T.Minutes(R.Ctx(tiny).mid)
    assert np.all(T.hold_twins(M, [], "design", R._any_time, False, np.random.default_rng(0)) == 0)
    assert C.twin_p(0.0, np.zeros(500)) == 1.0


# ------------------------------------------------------------------------------------------------ periods and twins
def test_period_filter_drops_warmup_trades():
    tr = [{"entry_ts": pd.Timestamp("2024-11-19 23:00"), "net": 5}, {"entry_ts": pd.Timestamp("2024-11-20 00:05"), "net": 1},
          {"entry_ts": pd.Timestamp("2026-03-31 23:00"), "net": 2}, {"entry_ts": pd.Timestamp("2026-04-01 00:01"), "net": 3}]
    assert [t["net"] for t in RUN.period_trades(tr, "design")] == [1, 2]
    assert [t["net"] for t in RUN.period_trades(tr, "final")] == [3]


def test_random_twins_use_only_the_period():
    cands = [(pd.Timestamp("2024-06-01"), 1000.0)] * 50 + [(pd.Timestamp("2025-06-02 10:00"), -1.0)] * 50 + \
            [(pd.Timestamp("2026-05-01"), 7.0)] * 50
    tw = T.random_twins(cands, 4, "design", np.random.default_rng(0))
    assert np.allclose(tw, -4.0) and len(tw) == C.N_TWINS
    assert np.allclose(T.random_twins(cands, 2, "final", np.random.default_rng(0)), 14.0)


def test_hold_twins_window_period_and_costs():
    idx = pd.date_range("2025-06-02 00:00", "2025-06-06 20:59", freq="1min")
    idx = idx[(idx.hour != 21)]
    mid = pd.DataFrame({"open": 100.0, "high": 100.0, "low": 100.0, "close": 100.0}, index=idx)
    M = T.Minutes(mid)
    tw = T.hold_twins(M, [30, 60], "design", R._india, False, np.random.default_rng(1))
    assert np.allclose(tw, 2 * 100 * (-0.30 - 0.07))            # flat price: each twin trade pays the spread + $7
    # swap: a 23-hour hold passes at most one rollover (x3 on Wednesday); some twins must pay it
    tw = T.hold_twins(M, [23 * 60], "design", R._any_time, True, np.random.default_rng(2), n=200)
    assert set(np.round(tw, 6)) <= {-77.0, -157.0, -37.0} and (tw < -37.5).any() and np.isclose(tw, -157).any()
    # a period without data gives zero twins (p = 1 for a losing rule)
    assert np.all(T.hold_twins(M, [30], "final", R._any_time, False, np.random.default_rng(1)) == 0)


def test_hold_twin_entries_respect_the_window():
    idx = pd.date_range("2025-06-02 00:00", "2025-06-03 20:59", freq="1min")
    mid = pd.DataFrame({"open": np.arange(len(idx), dtype=float), "high": 0.0, "low": 0.0, "close": 0.0}, index=idx)
    M = T.Minutes(mid)
    # price = minute number, hold 1 minute: every twin trade nets 100 * (1 - 0.30 - 0.07) wherever it starts ...
    tw = T.hold_twins(M, [1], "design", R._india, False, np.random.default_rng(4), n=50)
    assert np.allclose(tw, 100 * (1 - 0.37))
    # ... and India-hours entries lie in 03:50-09:00
    ok = np.asarray(R._india(M.mod, M.dow))
    assert M.mod[ok].min() == 3 * 60 + 50 and M.mod[ok].max() == 9 * 60


def test_twin_candidates_are_buys_inside_the_window(ctx):
    c1 = R.gb1_candidates(ctx, "2R")
    assert len(c1) > 100
    for t, _ in c1:
        t = pd.Timestamp(t)          # the entry: the open after a signal bar closing 07:00-16:00 (M15)
        assert 7 * 60 <= t.hour * 60 + t.minute <= 16 * 60 and t.dayofweek < 5
    for t, _ in R.gb11_candidates(ctx, "2R"):
        t = pd.Timestamp(t)
        assert t.dayofweek < 5 and t.hour * 60 + t.minute < 20 * 60 + 40


# ------------------------------------------------------------------------------------------------ look-ahead guard
def _key(t):
    return (t["entry_ts"], round(t["entry"], 6))


def _closed_before(trs, T_):
    return sorted((_key(t), t["exit_ts"], round(t["net"], 6)) for t in trs if t["exit_ts"] < T_ - pd.Timedelta(minutes=1))


@pytest.mark.parametrize("rule", list(R.RULES))
def test_no_look_ahead(data, ctx, rule):
    """Pick trades from the middle of the run. (1) Truncate the data one minute after the fill (the signal bar and the
    entry minute are kept): the trade and every earlier entry must be unchanged. (2) Replace every bar after the fill
    with a different random walk: the same, and every trade that closed before it is unchanged too."""
    fn = R.RULES[rule]["variants"]["orig"]["fn"]
    n_days = 75
    full = fn(ctx)
    if len(full) < 3:                       # slow rules (GB8 trades ~2 times in 75 days): a longer synthetic history
        n_days = 200
        data = synth(n_days=n_days)
        full = fn(R.Ctx(data))
    assert len(full) >= 3
    picks = [full[len(full) // 2], full[(3 * len(full)) // 4]]
    for tr in picks:
        T_ = tr["entry_ts"] + pd.Timedelta(minutes=1)
        before = sorted(_key(t) for t in full if t["entry_ts"] <= tr["entry_ts"])
        alt = synth(n_days=n_days, seed=99)
        pert = data.copy()
        after = pert.index >= T_
        shift = pert.close[~after].iloc[-1] - alt.close[after].iloc[0]
        for k in R.OHLC:
            pert.loc[after, k] = alt.loc[pert.index[after], k].values + shift
        p_tr = fn(R.Ctx(pert))
        assert sorted(_key(t) for t in p_tr if t["entry_ts"] <= tr["entry_ts"]) == before, rule
        assert _closed_before(p_tr, T_) == _closed_before(full, T_), rule
        if rule in ("GB7", "GB10", "GB8", "GB9", "GB6"):
            # liquidity_gold.sessions drops a session with < 60% of its minutes, and gold_reopen needs the exit minute
            # to exist: both read the data's completeness after the fill (see NOTES), so they get the perturbation only
            continue
        t_tr = fn(R.Ctx(data[data.index < T_]))
        assert sorted(_key(t) for t in t_tr if t["entry_ts"] <= tr["entry_ts"]) == before, rule


# ------------------------------------------------------------------------------------------------ final-test lock
def test_final_refuses_a_second_read(tmp_path, monkeypatch):
    monkeypatch.setattr(C, "RESULTS", tmp_path)
    des = {r: {"design_pick": None, "walk_forward": {"orig_wf_net": 0, "retune_wf_net": 0}} for r in R.RULES}
    (tmp_path / "gold_design.json").write_text(json.dumps(des))
    (tmp_path / "final_read_gold.json").write_text(json.dumps({"GB1:orig": "2026-10-15T00:00:00+00:00"}))
    with pytest.raises(C.FinalReadError):
        RUN.final("does-not-matter.csv")


def test_final_needs_the_design_first(tmp_path, monkeypatch):
    monkeypatch.setattr(C, "RESULTS", tmp_path)
    with pytest.raises(SystemExit):
        RUN.final("x.csv")


def test_fast_walk_equals_the_study_walk(ctx):
    """The vectorised walk used for twin candidates gives walk()'s exit to the cent, for stops, targets, signal exits,
    the 20:40 cut-off, gap fills on and off."""
    M, W = ctx.M, R.FastWalk(ctx.M)
    rng = np.random.default_rng(11)
    for i0 in rng.integers(0, len(M.t) - 2, 400):
        e = M.o[i0] + R.H
        stop = e - rng.uniform(0.2, 6) if rng.random() < 0.9 else None
        target = e + rng.uniform(0.2, 12) if rng.random() < 0.8 else None
        exit_at = int(i0 + rng.integers(0, 300)) if rng.random() < 0.3 else None
        for gap in (True, False):
            usd = R.walk(M, i0, 1, stop, target, exit_at, gap)[0]
            assert W(i0, stop, target, exit_at, gap) == pytest.approx(usd, abs=1e-9)
