"""Tests for the gold lab pack (research/data/xau): fetch decoder, cleaning, every feature's look-ahead guard, label
alignment, the NN window builder's shapes and split leakage. Synthetic data only (no network, no Drive)."""
from __future__ import annotations

import datetime as dt
import glob
import json
import lzma
import os
import struct
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xau"))
import fetch_duka_xau_bidask as fd  # noqa: E402
import xau_bars  # noqa: E402
import xau_clean  # noqa: E402
import xau_features  # noqa: E402
import xau_labels  # noqa: E402
import xau_nn  # noqa: E402

MIN = pd.Timedelta(minutes=1)


# ----------------------------------------------------------------------------------------------------- synthetic data
def raw_frame(ts, mid, spread=0.3, wick=0.2, vol=5.0, seed=0):
    """Fetch-shaped frame from minute times and mid closes (open = previous close)."""
    rng = np.random.default_rng(seed)
    ts = pd.DatetimeIndex(ts).tz_convert("UTC") if pd.DatetimeIndex(ts).tz else pd.DatetimeIndex(ts).tz_localize("UTC")
    c = np.asarray(mid, float)
    o = np.r_[c[0], c[:-1]]
    sp = np.broadcast_to(np.asarray(spread, float), c.shape)
    wk = np.abs(rng.normal(0, wick, len(c))) if wick else np.zeros(len(c))
    h, lo = np.maximum(o, c) + wk, np.minimum(o, c) - wk
    d = {"ts_utc": ts, "ts_ist": ts.tz_convert("Asia/Kolkata")}
    for side, sgn in (("bid", -0.5), ("ask", 0.5)):
        for k, v in (("open", o), ("high", h), ("low", lo), ("close", c)):
            d[f"{k}_{side}"] = v + sgn * sp
        d[f"volume_{side}"] = np.broadcast_to(np.asarray(vol, float), c.shape).copy()
    df = pd.DataFrame(d)
    df["mid_close"] = c
    df["spread"] = df.close_ask - df.close_bid
    return df


def synth(start="2025-10-16 00:00", end="2025-10-20 14:00", seed=1, drop=0.02):
    """Thu 16 Oct 2025 .. Mon 20 Oct 2025 with the weekend closure, daily breaks and random missing minutes."""
    rng = np.random.default_rng(seed)
    ts = pd.date_range(start, end, freq="1min", tz="UTC")
    ts = ts[~xau_clean.out_of_hours(pd.Series(ts))]
    ts = ts[rng.random(len(ts)) > drop]
    mid = 4000 + np.cumsum(rng.normal(0, 0.4, len(ts)))
    vol = rng.integers(1, 40, len(ts)).astype(float)
    return raw_frame(ts, mid, spread=0.25 + np.abs(rng.normal(0, 0.05, len(ts))), vol=vol, seed=seed)


@pytest.fixture(scope="module")
def raw():
    return synth()


@pytest.fixture(scope="module")
def clean(raw):
    return xau_clean.clean(raw)


@pytest.fixture(scope="module")
def feats(clean):
    return xau_features.build_features(clean)


# ------------------------------------------------------------------------------------------------------------ decoder
def rec(sec, o, c, lo, h, v):
    return struct.pack(">IIIIIf", sec, int(round(o * 1000)), int(round(c * 1000)), int(round(lo * 1000)),
                       int(round(h * 1000)), v)


def test_decoder_reads_open_close_low_high_order():
    raw = rec(0, 4001.5, 4002.25, 4000.75, 4003.0, 12.5) + rec(60, 4002.25, 4001.0, 4000.5, 4002.5, 3.0)
    out = fd.decode_candles(raw, dt.date(2026, 3, 2))
    assert out[0] == (dt.datetime(2026, 3, 2, 0, 0), 4001.5, 4003.0, 4000.75, 4002.25, 12.5)
    assert out[1][0] == dt.datetime(2026, 3, 2, 0, 1) and out[1][1:5] == (4002.25, 4002.5, 4000.5, 4001.0)


def test_decoder_ignores_partial_record_and_empty():
    raw = rec(120, 1, 2, 0.5, 3, 1.0) + b"\x00" * 23
    assert len(fd.decode_candles(raw, dt.date(2026, 1, 5))) == 1
    assert fd.decode_candles(b"", dt.date(2026, 1, 5)) == []


def test_day_rows_lzma_and_zero_based_month_url():
    seen = []
    payload = lzma.compress(rec(0, 10, 11, 9, 12, 2.0))

    def fake(url):
        seen.append(url)
        return payload
    out = fd.day_rows(dt.date(2026, 1, 7), "BID", fetch=fake)
    assert seen[0].endswith("/XAUUSD/2026/00/07/BID_candles_min_1.bi5")
    assert out[0][1:] == (10.0, 12.0, 9.0, 11.0, 2.0)
    assert fd.day_rows(dt.date(2026, 1, 7), "ASK", fetch=lambda u: b"") == []
    assert fd.day_rows(dt.date(2026, 1, 7), "ASK", fetch=lambda u: None) is None


def test_merge_sides_drops_closed_minutes_and_sets_mid_spread():
    t = dt.datetime(2026, 3, 2)
    bid = [(t, 10, 11, 9, 10.5, 1.0), (t + dt.timedelta(minutes=1), 10.5, 10.5, 10.5, 10.5, 0.0)]
    ask = [(t, 10.2, 11.2, 9.2, 10.8, 2.0), (t + dt.timedelta(minutes=1), 10.8, 10.8, 10.8, 10.8, 0.0)]
    m = fd.merge_sides(bid, ask)
    assert len(m) == 1 and str(m.ts_utc.dt.tz) == "UTC"
    assert m.mid_close.iloc[0] == pytest.approx(10.65) and m.spread.iloc[0] == pytest.approx(0.3)


def test_get_retries_then_gives_up():
    import urllib.error
    calls = []

    def boom(*a, **k):
        calls.append(1)
        raise urllib.error.HTTPError("u", 500, "Server error", {}, None)  # 429 has its own, longer budget (below)
    assert fd.get("https://x", tries=3, opener=boom, sleep=lambda s: None) is None and len(calls) == 3

    def nf(*a, **k):
        raise urllib.error.HTTPError("u", 404, "nf", {}, None)
    assert fd.get("https://x", tries=3, opener=nf, sleep=lambda s: None) == b""

    def busy(*a, **k):  # 503 "No server is available" is the datafeed being busy, never "no file"
        raise urllib.error.HTTPError("u", 503, "busy", {}, None)
    assert fd.get("https://x", tries=3, opener=busy, sleep=lambda s: None, throttled_tries=4) is None


# ----------------------------------------------------------------------------------------------------------- cleaning
def S(*ts):
    return pd.Series(pd.to_datetime(list(ts), utc=True))


def test_sessions_winter_and_summer():
    # 20 Jan 2026 (no DST): London 08:00 = 08:00 UTC, New York 08:00 = 13:00 UTC, London 16:30 = 16:30 UTC
    w = xau_clean.session_label(S("2026-01-20 03:00", "2026-01-20 08:00", "2026-01-20 13:00", "2026-01-20 16:30",
                                  "2026-01-20 21:59", "2026-01-20 22:00", "2026-01-20 23:59", "2026-01-21 00:00"))
    assert list(w) == ["asia", "london", "overlap", "ny", "ny", "off", "off", "asia"]
    # 15 Jul 2026 (DST both): London 08:00 = 07:00 UTC, New York 08:00 = 12:00 UTC, NY 17:00 = 21:00 UTC
    s = xau_clean.session_label(S("2026-07-15 06:59", "2026-07-15 07:00", "2026-07-15 12:00", "2026-07-15 15:30",
                                  "2026-07-15 21:00"))
    assert list(s) == ["asia", "london", "overlap", "ny", "off"]


def test_trading_day_rolls_at_17_new_york():
    d = xau_clean.trading_day(S("2026-01-20 21:59", "2026-01-20 22:00", "2026-01-18 23:00"))
    assert [str(x) for x in d] == ["2026-01-20", "2026-01-21", "2026-01-19"]  # Sunday evening -> Monday


def test_out_of_hours():
    f = xau_clean.out_of_hours(S("2026-01-23 21:59", "2026-01-23 22:00", "2026-01-24 12:00", "2026-01-25 22:59",
                                 "2026-01-25 23:00", "2026-01-20 22:30", "2026-01-20 23:00"))
    assert list(f) == [False, True, True, True, False, True, False]


def test_event_clock_uses_listed_calendar_only():
    ev = xau_clean.load_events()
    assert set(ev.event) == {"CPI", "NFP", "FOMC"} and ev.ts_utc.is_monotonic_increasing
    # CPI 14 Oct 2026 08:30 New York = 12:30 UTC; the 12:29 bar closes at 12:30 -> 0 minutes to go
    nxt, last, typ = xau_clean.event_clock(S("2026-10-14 12:00", "2026-10-14 12:29", "2026-10-14 12:30"), ev)
    assert list(nxt[:2]) == [29, 0] and typ[0] == "CPI"
    assert last[2] == 1 and typ[2] == "FOMC"


def test_clean_flags_spike_not_sustained_jump():
    ts = pd.date_range("2026-01-20 03:00", periods=200, freq="1min", tz="UTC")
    mid = 4000 + np.sin(np.arange(200)) * 0.2
    mid[100] += 15          # one-minute spike that reverts
    mid[150:] += 15         # a sustained jump: not a bad tick
    df = xau_clean.clean(raw_frame(ts, mid, wick=0))
    bad = np.flatnonzero(df.flag_bad_tick)
    assert 100 in bad and 150 not in bad and 151 not in bad


def test_clean_flags_stale_crossed_zero_volume_wide_spread():
    ts = pd.date_range("2026-01-20 03:00", periods=120, freq="1min", tz="UTC")
    mid = 4000 + np.arange(120) * 0.1
    mid[50:56] = mid[50]     # 6 unchanged closes
    sp = np.full(120, 0.3)
    sp[80] = -0.1            # crossed
    sp[90] = 5.0             # wide
    df0 = raw_frame(ts, mid, spread=sp, wick=0)
    df0.loc[70, "volume_ask"] = 0.0
    df = xau_clean.clean(df0)
    assert list(np.flatnonzero(df.flag_stale)) == [54, 55]
    assert df.flag_crossed[80] and not df.flag_crossed[79]
    assert df.flag_zero_vol_side[70] and df.flag_zero_vol_side.sum() == 1
    assert df.flag_wide_spread[90] and not df.flag_wide_spread[89]
    assert (df.spread - (df.close_ask - df.close_bid)).abs().max() < 1e-12
    assert np.allclose(df.close, (df.close_bid + df.close_ask) / 2)


def test_clean_gaps_and_clock(clean):
    assert clean.ts_utc.is_monotonic_increasing and clean.ts_utc.is_unique
    wk = clean[clean.weekend_open]
    assert len(wk) == 1 and wk.ts_utc.iloc[0].weekday() == 6 and wk.gap_min.iloc[0] > 24 * 60
    assert not clean.flag_out_of_hours.any()
    i = 500
    assert clean.ist_minute[i] == (clean.ts_utc[i].tz_convert("Asia/Kolkata").hour * 60
                                   + clean.ts_utc[i].tz_convert("Asia/Kolkata").minute)


# --------------------------------------------------------------------------------------------------------------- bars
def test_bars_aggregate_minutes(clean):
    b = xau_bars.make_bars(clean, "15m")
    k = clean.ts_utc.dt.floor("15min")
    first = b.iloc[10]
    rows = clean[k == first.ts_utc]
    assert first.open == rows.open.iloc[0] and first.close == rows.close.iloc[-1]
    assert first.high == rows.high.max() and first.volume == rows.volume.sum() and first.n_minutes == len(rows)
    assert first.ts_end == first.ts_utc + pd.Timedelta("15min")
    d = xau_bars.make_bars(clean, "1d")
    assert d.trading_day.is_monotonic_increasing and d.n_minutes.sum() == len(clean)
    for tf in xau_bars.TIMEFRAMES:
        assert xau_bars.make_bars(clean, tf).n_minutes.sum() == len(clean)


# ---------------------------------------------------------------------------------------------- features + look-ahead
def test_structure_bos_choch():
    # highs/lows shaped so a swing high forms, is broken (BOS up), then a swing low breaks (CHoCH down)
    c = np.array([10, 11, 12, 15, 12, 11, 10, 11, 12, 13, 16, 17, 16, 15, 14, 13, 12, 9, 8, 8, 8], float)
    b = pd.DataFrame({"high": c + 0.5, "low": c - 0.5, "close": c})
    s = xau_features.structure(b, piv=3)
    ev = s.event.to_numpy()
    assert s.swing_hi[6] == 15.5 and np.isnan(s.swing_hi[5])         # bar 3's high confirmed 3 bars later
    i_up = int(np.flatnonzero(ev > 0)[0])
    assert c[i_up] > 15.5 and ev[i_up] == 1 and s.trend[i_up] == 1    # first break with no trend = BOS
    i_dn = int(np.flatnonzero(ev < 0)[0])
    assert ev[i_dn] == -2 and s.trend[i_dn] == -1                     # break down against an up trend = CHoCH


def test_value_area():
    close = np.array([100.1, 100.1, 100.1, 100.6, 99.6, 101.6, 98.1])
    vol = np.array([10, 10, 10, 8, 6, 1, 1], float)
    poc, vah, val = xau_features.value_area(close, vol, bin_=0.5, share=0.7)
    assert poc == 100.25 and val <= 100.0 and vah >= 100.5
    assert vah - val <= 1.5


def test_returns_are_clock_based(clean, feats):
    i = 2000
    t = clean.ts_utc[i]
    j = clean.index[clean.ts_utc <= t - pd.Timedelta(minutes=60)][-1]
    assert feats.ret_60[i] == pytest.approx(np.log(clean.close[i] / clean.close[j]))


def test_htf_uses_completed_bars_only(clean, feats):
    b = xau_bars.make_bars(clean, "15m")
    atr = xau_features.wilder_atr(b.high, b.low, b.close)
    for i in (1500, 1501, 2222):
        close_t = clean.ts_utc[i] + MIN
        k = b.index[b.ts_end <= close_t][-1]
        assert feats.atr14_15m[i] == pytest.approx(atr[k])


GUARD_CUTS = None


def _cut_points(clean):
    rng = np.random.default_rng(7)
    picks = set(rng.choice(np.arange(300, len(clean) - 1), 14, replace=False).tolist())
    picks.add(int(np.flatnonzero(clean.weekend_open)[0]))                                 # the weekend open
    picks.add(int(np.flatnonzero(clean.weekend_open)[0]) + 30)
    picks.add(int(np.flatnonzero((clean.utc_minute % 15 == 14).to_numpy())[40]))          # a 15m bar's last minute
    picks.add(int(np.flatnonzero((clean.session != clean.session.shift()).to_numpy())[5]))  # a session change
    return sorted(picks)


@pytest.fixture(scope="module")
def truncated(raw, clean, feats):
    out = {}
    for k in _cut_points(clean):
        c = xau_clean.clean(raw.iloc[:k + 1])
        out[k] = xau_features.build_features(c).iloc[k]
    return out


FEATURE_COLS = list(xau_features.build_features(xau_clean.clean(synth(end="2025-10-17 03:00"))).columns[1:])


@pytest.mark.parametrize("col", FEATURE_COLS)
def test_lookahead_guard(col, feats, truncated):
    """Recomputing on data cut right after row k leaves row k's value unchanged, for every feature."""
    for k, row in truncated.items():
        a, b = feats[col].iloc[k], row[col]
        if isinstance(a, (float, np.floating)) and np.isnan(a):
            assert isinstance(b, (float, np.floating)) and np.isnan(b), (col, k)
        elif isinstance(a, (float, np.floating)):
            assert b == pytest.approx(a, rel=1e-9, abs=1e-9), (col, k)
        else:
            assert a == b, (col, k)


def test_feature_store_excludes_next_row_flags(feats):
    assert "flag_bad_tick" not in feats and "bad_tick_lag1" in feats
    assert len(set(feats.columns) & set(xau_labels.FWD_H)) == 0
    assert not any(c.startswith(("fwd_", "long_", "short_")) for c in feats.columns)


# -------------------------------------------------------------------------------------------------------------- labels
def test_labels_forward_returns_and_entry(clean, feats):
    lab = xau_labels.build_labels(clean, feats.atr14_15m)
    assert (lab.ts_utc == feats.ts_utc).all() and len(lab) == len(clean)
    i = 1000
    t = clean.ts_utc[i]
    j = clean.index[clean.ts_utc <= t + pd.Timedelta(minutes=15)][-1]
    assert lab.fwd_ret_15[i] == pytest.approx(np.log(clean.close[j] / clean.close[i]), rel=1e-5)
    win = clean[(clean.ts_utc > t) & (clean.ts_utc <= t + pd.Timedelta(minutes=60))]
    ea = clean.open_ask[i + 1]
    if clean.ts_utc[i + 1] - t <= pd.Timedelta(minutes=5):
        assert lab.long_mfe_60[i] == pytest.approx(win.high_bid.max() - ea, abs=1e-3)
        assert lab.long_mae_60[i] == pytest.approx(ea - win.low_bid.min(), abs=1e-3)
        assert lab.short_mae_60[i] == pytest.approx(win.high_ask.max() - clean.open_bid[i + 1], abs=1e-3)


def _tiny(mid, spread=0.2, start="2026-01-20 03:00"):
    ts = pd.date_range(start, periods=len(mid), freq="1min", tz="UTC")
    return xau_clean.clean(raw_frame(ts, mid, spread=spread, wick=0))


def test_hit_target_before_stop_net_of_spread():
    mid = np.full(300, 100.0)
    mid[10] = 101.05            # long: entry ask 100.1 at row 1's open; target bid >= 101.1 needs mid >= 101.2
    mid[20] = 101.25            # so row 20 is the target touch, row 10 is not
    df = _tiny(mid)
    lab = xau_labels.build_labels(df, np.full(len(df), 1.0))
    assert lab.long_hit_60[0] == 1 and lab.long_tth_60[0] == 20
    # short: entry bid 99.9, stop when the ask reaches 100.9: row 10's ask 101.15 stops it first
    assert lab.short_hit_60[0] == -1 and lab.short_tth_60[0] == 10


def test_hit_same_minute_counts_as_stop_and_gaps():
    mid = np.full(300, 100.0)
    df0 = raw_frame(pd.date_range("2026-01-20 03:00", periods=300, freq="1min", tz="UTC"), mid, spread=0.2, wick=0)
    df0.loc[5, ["high_bid", "high_ask"]] += 3
    df0.loc[5, ["low_bid", "low_ask"]] -= 3
    df = xau_clean.clean(df0)
    lab = xau_labels.build_labels(df, np.full(len(df), 1.0))
    assert lab.long_hit_60[0] == -1 and lab.short_hit_60[0] == -1
    assert np.isnan(lab.long_hit_60[1:].iloc[-1])  # the last row has no next row to enter on
    # a gap longer than the entry wait -> no entry
    gap = df0.drop(index=range(101, 110)).reset_index(drop=True)
    lab2 = xau_labels.build_labels(xau_clean.clean(gap), np.full(len(gap), 1.0))
    assert np.isnan(lab2.long_mfe_60[100]) and not np.isnan(lab2.long_mfe_60[99])


def test_labels_are_not_known_at_t():
    """Changing only the future changes the label, never the features (sanity of the separation)."""
    rng = np.random.default_rng(3)
    mid = 4000 + np.cumsum(rng.normal(0, 0.3, 600))
    a = _tiny(mid)
    mid2 = mid.copy()
    mid2[401:] += 5
    b = _tiny(mid2)
    fa, fb = xau_features.build_features(a), xau_features.build_features(b)
    pd.testing.assert_frame_equal(fa.iloc[:401], fb.iloc[:401])
    la, lb = xau_labels.build_labels(a, fa.atr14_15m), xau_labels.build_labels(b, fb.atr14_15m)
    assert la.fwd_ret_5.iloc[399] != lb.fwd_ret_5.iloc[399]


# ------------------------------------------------------------------------------------------------------------ NN pack
@pytest.fixture(scope="module")
def nn_pack(tmp_path_factory):
    raw = synth(start="2025-10-13 00:00", end="2025-10-25 00:00", seed=4, drop=0.01)
    c = xau_clean.clean(raw)
    f = xau_features.build_features(c)
    lab = xau_labels.build_labels(c, f.atr14_15m)
    sp = xau_nn.make_splits(c.ts_utc.iloc[0], c.ts_utc.iloc[-1], test=pd.DateOffset(days=3),
                            val=pd.DateOffset(days=3), fold=pd.DateOffset(days=1))
    out = tmp_path_factory.mktemp("nn")
    s = xau_nn.build(c, f, lab, str(out), splits=sp, log=lambda *a: None)
    return c, f, lab, sp, out, s


def test_splits_are_ordered_and_disjoint(nn_pack):
    *_, sp, out, s = nn_pack
    tr, va, te = (pd.Timestamp(x) for x in (sp["train"][1], sp["validation"][1], sp["final_test"][1]))
    assert pd.Timestamp(sp["train"][0]) < tr == pd.Timestamp(sp["validation"][0]) < va == pd.Timestamp(
        sp["final_test"][0]) < te
    for fo in sp["walk_forward_folds"]:
        assert pd.Timestamp(fo["validation"][1]) <= pd.Timestamp(sp["final_test"][0])
        assert pd.Timestamp(fo["validation"][0]) >= pd.Timestamp(sp["validation"][0])
    assert json.load(open(out / "splits.json")) == sp
    real = xau_nn.make_splits(pd.Timestamp("2025-10-15 00:00", tz="UTC"), pd.Timestamp("2026-10-15 20:59", tz="UTC"))
    assert real["final_test"] == ["2026-07-16T00:00:00+00:00", "2026-10-16T00:00:00+00:00"]
    assert real["validation"][0] == "2026-04-16T00:00:00+00:00" and len(real["walk_forward_folds"]) == 3


def test_window_shapes(nn_pack):
    *_, out, s = nn_pack
    files = sorted(glob.glob(str(out / "train_*.npz")) + glob.glob(str(out / "validation_*.npz")))
    assert files and sum(s["shards"].values()) > 0
    z = np.load(files[0])
    n = len(z["ts"])
    assert z["x1"].shape == (n, 120, len(xau_nn.STEP1)) and z["x1"].dtype == np.float16
    assert z["x15"].shape == (n, 48, len(xau_nn.STEP15))
    assert z["ctx"].shape == (n, len(xau_nn.CTX)) and z["y"].shape == (n, len(xau_nn.LABELS))
    assert np.isfinite(z["x1"].astype(float)).all() and np.abs(z["x1"].astype(float)).max() <= xau_nn.CLIP
    g = sorted(glob.glob(str(out / "gaf64_*.npz")))
    if g:
        zg = np.load(g[0])
        assert zg["gaf"].shape[1:] == (64, 64) and zg["gaf"].dtype == np.uint8


def test_window_content_matches_source(nn_pack):
    c, f, lab, sp, out, s = nn_pack
    z = np.load(sorted(glob.glob(str(out / "train_*.npz")))[0])
    ts = c.ts_utc.to_numpy("datetime64[ns]").astype("int64")
    i = int(np.searchsorted(ts, z["ts"][3]))
    st = json.load(open(out / "norm_stats.json"))["step1"]["ret_1"]
    want = np.clip((f.ret_1.to_numpy()[i - 119:i + 1] - st[0]) / st[1], -8, 8)
    assert np.allclose(z["x1"][3][:, 0].astype(float), np.nan_to_num(want), atol=2e-2)
    assert z["win_start"][3] == ts[i - 119]
    ylab = lab[xau_nn.LABELS].to_numpy(np.float32)[i]
    assert np.allclose(z["y"][3], ylab, equal_nan=True)


def test_no_window_crosses_a_split_boundary(nn_pack):
    *_, sp, out, s = nn_pack
    for p in ("train", "validation", "final_test"):
        a, b = (pd.Timestamp(x).value for x in sp[p])
        for fn in glob.glob(str(out / f"{p}_*.npz")):
            z = np.load(fn)
            assert (z["ts"] >= a).all() and (z["win_start"] >= a).all() and (z["h15_start"] >= a).all()
            assert (z["label_end"] <= b).all() and (z["ts"] < b).all()
        for fn in glob.glob(str(out / f"gaf64_{p}_*.npz")):
            z = np.load(fn)
            assert (z["ts"] >= a + 63 * 60 * 10**9).all()


def test_norm_stats_fitted_on_train_only(nn_pack):
    c, f, lab, sp, out, s = nn_pack
    st = json.load(open(out / "norm_stats.json"))
    assert st["fitted_on"] == sp["train"]
    f2 = f.copy()
    late = (c.ts_utc >= pd.Timestamp(sp["validation"][0])).to_numpy()
    f2.loc[late, "spread_bp"] = 1e6     # wreck validation / test: the train statistics must not move
    tmp = out.parent / "nn_b"
    xau_nn.build(c, f2, lab, str(tmp), splits=sp, gaf=False, log=lambda *a: None)
    st2 = json.load(open(tmp / "norm_stats.json"))
    assert st2["step1"]["spread_bp"] == st["step1"]["spread_bp"]


def test_in_span_helper():
    meta = {"ts": np.array([10, 20]), "win_start": np.array([5, 15]), "h15_start": np.array([1, 15]),
            "label_end": np.array([30, 40])}
    a, b = pd.Timestamp(2, unit="ns", tz="UTC"), pd.Timestamp(35, unit="ns", tz="UTC")
    assert list(xau_nn.in_span(meta, a, b)) == [False, False]
    a = pd.Timestamp(0, unit="ns", tz="UTC")
    assert list(xau_nn.in_span(meta, a, b)) == [True, False]


def test_gasf_properties():
    x = np.array([[1.0, 2.0, 3.0, 2.0]])
    g = xau_nn.gasf(x)[0].astype(float) / 127.5 - 1
    assert np.allclose(g, g.T, atol=1e-2)
    s = np.array([-1, 0, 1, 0])
    assert np.allclose(np.diag(g), 2 * s ** 2 - 1, atol=1e-2)  # cos(2 phi) = 2 x^2 - 1


# ------------------------------------------------------------------------------------------------- build + overview
def test_build_end_to_end(tmp_path, monkeypatch):
    import xau_build
    raw = synth(start="2025-10-13 00:00", end="2025-10-22 00:00", seed=5)
    raw.to_parquet(tmp_path / "raw.parquet")
    calls = []
    monkeypatch.setattr(xau_build.subprocess, "run",
                        lambda cmd, **k: calls.append(cmd) or type("R", (), {"returncode": 0})())
    rc = xau_build.main([str(tmp_path / "raw.parquet"), str(tmp_path / "out"), "--guard-cuts", "2", "--upload",
                         "--remote", "gdrive:X/xauusd_1m"])
    assert rc == 0
    out = tmp_path / "out"
    for f in ("xauusd_1m_clean.parquet", "xauusd_1m_features.parquet", "xauusd_1m_labels.parquet", "qa.json",
              "overview.html", "README.md", "us_events.csv", "MANIFEST.txt", "nn/splits.json", "nn/norm_stats.json",
              "bars/xauusd_1d.parquet"):
        assert (out / f).exists(), f
    qa = json.load(open(out / "qa.json"))
    assert qa["rows"] == len(raw) and qa["guard_check"]["mismatches"] == []
    assert qa["gaps"]["weekend"] == 1 and qa["gaps"]["other_count"] == 0 and qa["gaps"]["missing_weekdays"] == []
    card = (out / "README.md").read_text()
    assert "<!-- STATS -->" not in card and "| 1-minute rows |" in card
    page = (out / "overview.html").read_text()
    assert "<script src" not in page and "http" not in page and len(page) < 300_000
    feat = pd.read_parquet(out / "xauusd_1m_features.parquet")
    lab = pd.read_parquet(out / "xauusd_1m_labels.parquet")
    assert (feat.ts_utc == lab.ts_utc).all() and not set(feat.columns[1:]) & set(lab.columns[1:])
    assert [c[:3] for c in calls] == [["rclone", "copy", str(out)], ["rclone", "copy", str(tmp_path / "raw.parquet")],
                                      ["rclone", "check", str(out)]]


def test_gap_report_classifies(clean):
    import xau_build
    df = clean.drop(index=range(1000, 1030)).reset_index(drop=True)  # a 30-minute hole on a Thursday
    df["gap_min"] = (df.ts_utc.diff().dt.total_seconds() / 60).fillna(1.0)
    g = xau_build.gap_report(df)
    assert g["weekend"] == 1 and g["other_count"] >= 1 and g["other"][0]["minutes"] >= 30


# ------------------------------------------------------------------------------------------------- resumable fetch
def _bi5(day_secs, price):
    return lzma.compress(fd.RECORD.pack(day_secs, price, price, price, price, 1.0))


def test_get_waits_longer_on_throttle_then_succeeds():
    import urllib.error
    waits, n = [], [0]

    class R:
        def read(self):
            return b"ok"

    def throttled(*a, **k):
        n[0] += 1
        if n[0] <= 7:  # more 429s than the 6 plain network retries: must not give up
            raise urllib.error.HTTPError("u", 429, "Too Many", {}, None)
        return R()
    assert fd.get("https://x", tries=6, opener=throttled, sleep=waits.append) == b"ok"
    assert waits[0] == 30 and waits == sorted(waits) and max(waits) <= 300

    def always(*a, **k):
        raise urllib.error.HTTPError("u", 429, "Too Many", {}, None)
    assert fd.get("https://x", opener=always, sleep=lambda s: None, throttled_tries=5) is None


def test_fetch_resumes_from_cache_after_a_restart(tmp_path):
    """A first run is 'killed' after a few files; the rerun downloads only what is not cached, never re-downloads
    cached days, retries the day that failed, and gives the same parquet as one clean run."""
    days = [dt.date(2026, 3, 2) + dt.timedelta(days=i) for i in range(3)]  # Mon..Wed
    payload = {fd.URL.format(y=d.year, m=d.month - 1, d=d.day, side=s): _bi5(60 * i, 2000_000 + 1000 * i)
               for i, d in enumerate(days) for s in ("BID", "ASK")}
    cache, calls = tmp_path / "cache", []

    class Killed(Exception):
        pass

    def dying(url):
        calls.append(url)
        if len(calls) > 3:
            raise Killed
        return payload[url]
    args = [str(tmp_path / "a.parquet"), "--start", "2026-03-02", "--end", "2026-03-04", "--cache", str(cache)]
    with pytest.raises(Killed):
        fd.main(args, fetch=dying, pause=0)
    assert len(list(cache.glob("*.bi5"))) == 3 and not list(cache.glob("*.part"))

    calls2 = []

    def flaky(url):  # the rerun: Wednesday BID fails on the network
        calls2.append(url)
        return None if url.endswith("/02/04/BID_candles_min_1.bi5") else payload[url]
    fd.main(args, fetch=flaky, pause=0)
    assert len(calls2) == 3 and not set(calls2) & set(calls[:3])  # cached files are never downloaded again
    assert pd.read_parquet(tmp_path / "a.parquet").close_bid.isna().sum() == 1  # Wednesday has no BID yet

    calls3 = []
    fd.main(args, fetch=lambda u: calls3.append(u) or payload[u], pause=0)
    assert len(calls3) == 1  # only the missed file is fetched again
    clean = str(tmp_path / "b.parquet")
    fd.main([clean] + args[1:-1] + [str(tmp_path / "fresh")], fetch=lambda u: payload[u], pause=0)
    pd.testing.assert_frame_equal(pd.read_parquet(tmp_path / "a.parquet"), pd.read_parquet(clean))


def test_cache_keeps_empty_days_and_drops_corrupt_files(tmp_path):
    d = dt.date(2026, 3, 2)
    assert fd.day_rows(d, "BID", fetch=lambda u: b"", cache=str(tmp_path)) == []
    assert (tmp_path / "2026-03-02_BID.bi5").read_bytes() == b""
    assert fd.day_rows(d, "BID", fetch=lambda u: 1 / 0, cache=str(tmp_path)) == []  # cached: no fetch
    assert fd.day_rows(d, "ASK", fetch=lambda u: b"not lzma", cache=str(tmp_path)) is None
    assert not (tmp_path / "2026-03-02_ASK.bi5").exists()
    assert fd.day_rows(d, "ASK", fetch=lambda u: None, cache=str(tmp_path)) is None
    assert not (tmp_path / "2026-03-02_ASK.bi5").exists()
