"""Stale repeated print tests (synthetic data, no lake): lakeclean step 3 (research/hunt/lakestale/PREREG.md).

    python -m pytest research/hunt/lakestale -q

A stale run far from its neighbours is caught (the R77 case, rebuilt); genuine quiet prints are kept (a flat run inside
the band, a cheap far-OTM option at 0.05, a short run, a run with no usable neighbour, a run with volume); the band reads
no row after the minute (look-ahead guard); the cleaned store changes option prices only, never the index bars, the ATM
label or the expiry flag that the studies' signals and day filters read.
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parent / "r51"))

import lakeclean as LC  # noqa: E402
import r51cfg as K  # noqa: E402
import r51data  # noqa: E402

D1 = date(2026, 9, 28)


def bars(day, side, strike, r0, prices, vol, spot=24720.0, flat=True):
    """One contract's minutes from row r0: price p per minute (flat bars when `flat`), volume vol (scalar or list)."""
    vol = vol if isinstance(vol, (list, tuple)) else [vol] * len(prices)
    out = []
    for i, (p, v) in enumerate(zip(prices, vol)):
        hi, lo = (p, p) if flat else (p * 1.02, p * 0.98)
        out.append(dict(day=day, r=r0 + i, side=side, strike=float(strike), spot=spot, open=p, high=hi, low=lo,
                        close=p, volume=float(v)))
    return out


def frame(recs):
    return pd.DataFrame(recs, columns=["day", "r", "side", "strike", "spot", "open", "high", "low", "close", "volume"])


def r77_case(stale_len=13, stale_px=1855.85):
    """FINNIFTY 28 Sep 2026 rebuilt: 24600 / 24700 calls trade every minute near 215 / 170; the 24650 call shows
    `stale_px` flat at zero volume for `stale_len` minutes from row 25 (09:40), then trades near 192."""
    n = 40
    recs = bars(D1, "C", 24600, 20, [215.0] * n, 300, flat=False)
    recs += bars(D1, "C", 24700, 20, [170.0] * n, 300, flat=False)
    recs += bars(D1, "C", 24650, 25, [stale_px] * stale_len, 0)
    recs += bars(D1, "C", 24650, 25 + stale_len, [192.45] * 10, 360, flat=False)
    return frame(recs)


# ------------------------------------------------------------------------------------------------ runs
def test_stale_runs_length_and_place():
    df = frame(bars(D1, "C", 100, 0, [5.0, 5.0, 5.0, 6.0, 6.0, 7.0], [0, 0, 0, 0, 0, 10]))
    length, place = LC.stale_runs(df)
    assert length.tolist() == [3, 3, 3, 2, 2, 0]           # a new price starts a new run; volume ends it
    assert place.tolist() == [1, 2, 3, 1, 2, 0]


def test_stale_runs_split_by_contract_and_not_flat():
    recs = bars(D1, "C", 100, 0, [5.0] * 3, 0) + bars(D1, "C", 150, 3, [5.0] * 3, 0)
    recs += bars(D1, "P", 100, 0, [5.0] * 3, 0, flat=False)
    length, _ = LC.stale_runs(frame(recs))
    assert length.tolist() == [3, 3, 3, 3, 3, 3, 0, 0, 0]


def test_stale_runs_order_and_duplicates():
    df = frame(bars(D1, "C", 100, 0, [5.0] * 4, 0))
    dup = df.iloc[[1]].assign(close=9.0, open=9.0, high=9.0, low=9.0)  # a later duplicate of minute 1 (store keeps first)
    df2 = pd.concat([df.iloc[::-1], dup], ignore_index=True)
    length, place = LC.stale_runs(df2)
    assert length.tolist() == [4, 4, 4, 4, 0]
    assert place.tolist() == [4, 3, 2, 1, 0]


def test_empty_frames():
    e = frame([])
    assert LC.stale_runs(e)[0].shape == (0,)
    assert LC.stale_mask(e).shape == (0,)
    k, d = LC.drop_stale(e)
    assert len(k) == 0 and len(d) == 0


# ------------------------------------------------------------------------------------------------ caught
def test_the_r77_stale_run_is_caught():
    df = r77_case()
    kept, dropped = LC.drop_stale(df)
    assert len(dropped) == 13
    assert (dropped.close == 1855.85).all() and (dropped.strike == 24650.0).all()
    k = kept[kept.strike == 24650.0]
    assert k.r.min() == 38 and (k.close == 192.45).all()   # the real trades from 09:53 stay


def test_a_stale_price_far_below_the_band_is_caught():
    df = r77_case(stale_px=40.0)                           # below the 24700 call (170): impossible for the 24650 call
    assert LC.stale_mask(df).sum() == 13


def test_puts_use_the_mirrored_band():
    n = 30
    recs = bars(D1, "P", 24600, 0, [150.0] * n, 300, flat=False) + bars(D1, "P", 24700, 0, [200.0] * n, 300, flat=False)
    recs += bars(D1, "P", 24650, 5, [900.0] * 8, 0)      # above P(24700)
    recs += bars(D1, "P", 24650, 13, [30.0] * 8, 0)      # below P(24600)
    recs += bars(D1, "P", 24650, 21, [175.0] * 8, 0)     # inside: quiet but right
    m = LC.stale_mask(frame(recs))
    df = frame(recs)
    assert df[m].close.tolist() == [900.0] * 8 + [30.0] * 8


def test_causal_mode_only_flags_from_the_min_run_th_row():
    df = r77_case()
    m = LC.stale_mask(df, causal=True)
    assert m.sum() == 13 - (LC.STALE_MIN_RUN - 1)
    assert df[m].r.min() == 25 + LC.STALE_MIN_RUN - 1


# ------------------------------------------------------------------------------------------------ kept
def test_a_flat_but_correct_run_is_kept():
    df = r77_case(stale_px=198.60)                         # inside [170, 215] (and within 50% of it anyway)
    assert not LC.stale_mask(df).any()
    df = r77_case(stale_px=300.0)                          # outside the band but not by 50%: kept
    assert not LC.stale_mask(df).any()


def test_a_cheap_far_otm_option_at_minimum_tick_is_kept():
    n = 30
    recs = bars(D1, "C", 26000, 0, [0.10] * n, 50, flat=False) + bars(D1, "C", 26100, 0, [0.05] * n, 50, flat=False)
    recs += bars(D1, "C", 26050, 0, [0.20] * n, 0)        # 0.20 > 1.5 x 0.10, but within 2 ticks + 50%
    assert not LC.stale_mask(frame(recs)).any()


def test_a_short_run_is_kept():
    df = r77_case(stale_len=LC.STALE_MIN_RUN - 1)
    assert not LC.stale_mask(df).any()
    df = r77_case(stale_len=LC.STALE_MIN_RUN)
    assert LC.stale_mask(df).sum() == LC.STALE_MIN_RUN


def test_no_usable_neighbour_keeps_the_row():
    df = r77_case()
    lone = df[df.strike == 24650.0]                        # no other strike that day
    assert not LC.stale_mask(lone).any()
    old = df.copy()                                        # neighbours trade only long before the run
    nb = old.strike != 24650.0
    old.loc[nb & (old.r > 25 - LC.STALE_AGE - 5), "volume"] = 0.0
    assert not LC.stale_mask(old).any()


def test_one_side_of_the_band_is_enough():
    df = r77_case()
    up = df[df.strike != 24700.0]                          # only the lower strike: hi = C(24600) = 215
    assert LC.stale_mask(up).sum() == 13


def test_neighbour_on_another_day_or_side_is_not_used():
    df = r77_case()
    lone = df[df.strike == 24650.0]
    other = pd.concat([lone, frame(bars(date(2026, 9, 29), "C", 24600, 20, [215.0] * 40, 300, flat=False)),
                       frame(bars(D1, "P", 24600, 20, [215.0] * 40, 300, flat=False))], ignore_index=True)
    assert not LC.stale_mask(other).any()


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_band_reads_nothing_after_the_minute():
    df = r77_case()
    cand = np.flatnonzero((df.strike == 24650.0).to_numpy() & (df.volume == 0).to_numpy())
    lo, hi = LC.neighbour_band(df, cand)
    fut = df.copy()
    # change every neighbour row AFTER the last candidate minute: the band of every candidate must not move
    last = int(df.r.to_numpy()[cand].max())
    nb = (fut.strike != 24650.0) & (fut.r > last)
    fut.loc[nb, ["open", "high", "low", "close"]] = 5000.0
    lo2, hi2 = LC.neighbour_band(fut, cand)
    assert np.allclose(lo, lo2, equal_nan=True) and np.allclose(hi, hi2, equal_nan=True)
    # and minute by minute: rewriting the neighbours from minute r+1 on leaves the band at minute r unchanged
    for j in cand:
        r = int(df.r.iloc[j])
        f = df.copy()
        f.loc[(f.strike != 24650.0) & (f.r > r), ["open", "high", "low", "close"]] = 1.0
        a, b = LC.neighbour_band(f, [j])
        assert np.isclose(a[0], lo[cand.tolist().index(j)]) and np.isclose(b[0], hi[cand.tolist().index(j)])


def test_causal_flags_do_not_depend_on_later_rows():
    df = r77_case()
    full = LC.stale_mask(df, causal=True)
    for cut in range(26, 60):
        part = df[df.r <= cut]
        m = LC.stale_mask(part, causal=True)
        ref = full[(df.r <= cut).to_numpy()]
        assert (m == ref).all(), cut


def test_offline_flags_are_a_superset_of_causal():
    df = r77_case()
    assert (LC.stale_mask(df) >= LC.stale_mask(df, causal=True)).all()


# ------------------------------------------------------------------------------------------------ clean() and the store
def test_clean_logs_stale_rows_only_when_asked():
    df = r77_case()
    closes = {D1: np.full(375, 24720.0)}
    k0, _, log0 = LC.clean(df, closes)
    assert "stale_run" not in set(log0.why) and len(k0) == len(df)
    k1, a1, log1 = LC.clean(df, closes, stale=True)
    assert (log1.why == "stale_run").sum() == 13 and len(k1) == len(df) - 13
    assert len(a1) == len(df)                                      # the label rows keep the stale minutes
    assert LC.counts(log1, len(df))["stale_run"] == 13
    assert "stale_run" not in LC.counts(log0, len(df))


def test_touched_reports_stale_runs():
    log = pd.DataFrame(dict(day=[D1], r=[30], side=["C"], strike=[24650.0], why=["stale_run"]))
    T = pd.DataFrame(dict(day=[D1, D1], side=["C", "C"], strike=[24650.0, 24650.0], er=[25, 40], xr=[35, 50]))
    t = LC.touched(T, log)
    assert t.stale_run.tolist() == [True, False] and t["any"].tolist() == [True, False]


def _write_lake(tmp: Path):
    """A NIFTY day: index flat at 24,720; calls 24600 / 24650 / 24700, the 24650 stale at 1,855.85 for 13 minutes."""
    ts0 = pd.Timestamp("2026-09-28 09:15", tz="Asia/Kolkata")
    idx = [dict(ts=ts0 + pd.Timedelta(minutes=i), open=24720.0, high=24725.0, low=24715.0, close=24720.0)
           for i in range(375)]
    pd.DataFrame(idx).to_parquet(tmp / "index_1m_NIFTY_2026.parquet")
    df = r77_case()
    recs = []
    for t in df.itertuples(index=False):
        off = 0 if t.strike == 24700.0 else (1 if t.strike == 24650.0 else 2)
        recs.append(dict(ts=ts0 + pd.Timedelta(minutes=int(t.r)), offset=-off, side="CE", strike=t.strike,
                         spot=24720.0, open=t.open, high=t.high, low=t.low, close=t.close, volume=t.volume))
    pd.DataFrame(recs).to_parquet(tmp / "opt_NIFTY_WEEK_2026.parquet")


def test_store_changes_option_prices_only(tmp_path, monkeypatch):
    """The decision inputs (index bars, ATM label, expiry flag) are identical with and without the stale step."""
    _write_lake(tmp_path)
    monkeypatch.setattr(K, "LAKE", tmp_path)
    a = r51data.index_days("NIFTY")
    la = r51data.add_options("NIFTY", a, clean=True)
    b = r51data.index_days("NIFTY")
    lb = r51data.add_options("NIFTY", b, clean=True, stale=True)
    assert (la.why == "stale_run").sum() == 0 and (lb.why == "stale_run").sum() == 13
    for d in a:
        assert np.array_equal(a[d].idx, b[d].idx, equal_nan=True)
        assert (a[d].atm is None) == (b[d].atm is None)
        if a[d].atm is not None:
            assert np.array_equal(a[d].atm, b[d].atm, equal_nan=True)
        assert a[d].expiry == b[d].expiry
    ca, cb = a[D1].contract("C", 24650.0), b[D1].contract("C", 24650.0)
    assert ca[0, 25] == np.float32(1855.85) and np.isnan(cb[0, 25:38]).all()
    assert cb[0, 38] == np.float32(192.45)
    assert np.array_equal(a[D1].contract("C", 24600.0), b[D1].contract("C", 24600.0), equal_nan=True)


def test_stale_switch_needs_clean_and_own_folder(monkeypatch):
    import importlib
    monkeypatch.setenv("R51_CLEAN", "1")
    monkeypatch.setenv("R51_STALE", "1")
    k = importlib.reload(K)
    try:
        assert k.STALE and k.OUT.name == "clean_stale"
        monkeypatch.setenv("R51_CLEAN", "0")
        k = importlib.reload(K)
        assert not k.STALE and k.OUT.name != "clean_stale"
        monkeypatch.setenv("R51_CLEAN", "1")
        monkeypatch.setenv("R51_STALE", "0")
        k = importlib.reload(K)
        assert not k.STALE and k.OUT.name == "clean"
    finally:
        monkeypatch.delenv("R51_CLEAN")
        monkeypatch.delenv("R51_STALE")
        importlib.reload(K)
