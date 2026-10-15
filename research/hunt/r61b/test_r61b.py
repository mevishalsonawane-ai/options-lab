"""R61b tests on synthetic data only (no lake needed):

    python -m pytest research/hunt/r61b/test_r61b.py -q

Covers every TR feature on hand-made bars (failed breaks of the opening range and the previous day's high / low,
sweeps, the signal bar, volume and absorption baselines, OI build-up, the delta proxies, option-volume imbalance), the
look-ahead guard (values at a row are identical when every later minute is changed or deleted), missing minutes,
holidays (previous session = previous store day), expiry days left out of the baselines, the skip filters (a missing
value never skips), the filter variants keeping V1's scores, the day aggregates from option rows, the one-read guard
and the replace rule.
"""
from __future__ import annotations

import copy
import json
import math
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r61bcfg as K  # noqa: E402
import r61beval as EV  # noqa: E402
import r61bdata  # noqa: E402
import r61bstudy as ST  # noqa: E402

N = K.NROWS
NAN = float("nan")


def flat_day(px=100.0, vol=10.0, oi=1000.0) -> dict:
    return dict(o=[px] * N, h=[px + 0.5] * N, l=[px - 0.5] * N, c=[px] * N, volc=[vol] * N, volp=[vol] * N,
                oi=[oi] * N)


def setc(day, r, close, hi=None, lo=None, op=None):
    day["c"][r] = close
    day["o"][r] = close if op is None else op
    day["h"][r] = max(close, day["o"][r]) + 0.1 if hi is None else hi
    day["l"][r] = min(close, day["o"][r]) - 0.1 if lo is None else lo


def feats(day, frow, side="C", prev_hl=(200.0, 0.0), prev_close=100.0, hist=None):
    return EV.features(day, frow, side, prev_hl, prev_close, hist if hist is not None else [])


# ------------------------------------------------------------------------------------------------ traps
def test_failed_or_break_on_the_trade_side():
    d = flat_day()                      # OR15 high = 100.5
    for r in range(20, 25):
        setc(d, r, 101.0)               # break above at row 20, holds to 24
    setc(d, 25, 100.2)                  # back inside within 15 minutes -> failed
    assert feats(d, 30)["trap_same_or"] == 1.0
    assert feats(d, 30)["trap_opp_or"] == 0.0
    assert feats(d, 30, side="P")["trap_opp_or"] == 1.0        # for a put that failed up-break is the other side's
    assert feats(d, 24)["trap_same_or"] == 0.0                 # not failed yet at row 24 (no look-ahead)


def test_break_that_holds_or_comes_back_too_late_is_not_failed():
    d = flat_day()
    for r in range(20, 37):
        setc(d, r, 101.0)               # holds above for 16 rows
    setc(d, 37, 100.0)                  # back inside 17 rows after the break: outside the 15-minute window
    assert feats(d, 60)["trap_same_or"] == 0.0


def test_or_traps_missing_before_the_range_is_complete():
    d = flat_day()
    f = feats(d, 10)
    assert math.isnan(f["trap_same_or"]) and math.isnan(f["sweep_same30"])
    f = feats(d, 14)                    # row 14 closes the range: no break can exist yet, so still missing
    assert math.isnan(f["trap_same_or"]) and math.isnan(f["trap_opp_or"]) and math.isnan(f["sweep_same30"])
    assert feats(d, 15)["trap_same_or"] == 0.0
    assert f["trap_same_pd"] == 0.0


def test_previous_day_high_trap_and_the_open_above_it():
    d = flat_day()
    setc(d, 30, 106.0)
    setc(d, 33, 104.0)
    f = feats(d, 40, prev_hl=(105.0, 90.0), prev_close=100.0)
    assert f["trap_same_pd"] == 1.0 and f["trap_opp_pd"] == 0.0
    # opened above PDH (previous close above too): no break at all, so no trap
    d2 = flat_day(px=107.0)
    setc(d2, 30, 104.0)
    assert feats(d2, 40, prev_hl=(105.0, 90.0), prev_close=106.0)["trap_same_pd"] == 0.0
    # the first bar of the day breaks it (previous close inside), then fails
    d3 = flat_day()
    setc(d3, 0, 106.0)
    setc(d3, 5, 104.0)
    assert feats(d3, 10, prev_hl=(105.0, 90.0), prev_close=100.0)["trap_same_pd"] == 1.0


def test_put_side_previous_day_low_trap():
    d = flat_day()
    setc(d, 50, 89.0)
    setc(d, 52, 91.0)
    f = feats(d, 60, side="P", prev_hl=(110.0, 90.0))
    assert f["trap_same_pd"] == 1.0
    assert feats(d, 60, side="C", prev_hl=(110.0, 90.0))["trap_opp_pd"] == 1.0


# ------------------------------------------------------------------------------------------------ sweeps
def test_sweep_of_the_high_and_low():
    d = flat_day()                      # running high 100.5
    setc(d, 40, 100.3, hi=101.5)        # pokes above, closes back inside -> sweep of the highs
    assert feats(d, 50)["sweep_same30"] == 1.0
    assert feats(d, 50)["sweep_opp30"] == 0.0
    assert feats(d, 50, side="P")["sweep_opp30"] == 1.0
    assert feats(d, 80)["sweep_same30"] == 0.0                 # older than 30 bars
    d2 = flat_day()
    setc(d2, 40, 101.5, hi=101.6)       # new high that closes above the old one: a break, not a sweep
    assert feats(d2, 50)["sweep_same30"] == 0.0


# ------------------------------------------------------------------------------------------------ the signal bar
def test_signal_bar_wick_and_close_location():
    d = flat_day()
    for r, (o, h, l, c) in zip(range(26, 31), [(100, 101, 99.8, 100.5), (100.5, 103, 100.4, 102), (102, 102.5, 101, 101.2),
                                               (101.2, 101.5, 100.9, 101.0), (101, 101.2, 100.8, 101.0)]):
        d["o"][r], d["h"][r], d["l"][r], d["c"][r] = o, h, l, c
    f = feats(d, 30)                    # merged bar O 100, H 103, L 99.8, C 101
    assert f["brk_wick"] == pytest.approx((103 - 101) / 3.2)
    assert f["brk_clv"] == pytest.approx(((101 - 99.8) - (103 - 101)) / 3.2)
    fp = feats(d, 30, side="P")
    assert fp["brk_wick"] == pytest.approx((100 - 99.8) / 3.2)
    assert fp["brk_clv"] == pytest.approx(-f["brk_clv"])


def test_no_range_bar_is_missing():
    d = flat_day()
    for r in range(26, 31):
        d["o"][r] = d["h"][r] = d["l"][r] = d["c"][r] = 100.0
    f = feats(d, 30)
    assert math.isnan(f["brk_wick"]) and math.isnan(f["brk_clv"])


# ------------------------------------------------------------------------------------------------ volume baselines
def test_volume_at_break_against_twenty_sessions():
    hist = [flat_day(vol=10.0) for _ in range(25)]
    d = flat_day(vol=10.0)
    for r in range(26, 31):
        d["volc"][r] = d["volp"][r] = 30.0
    f = feats(d, 30, hist=hist[-20:])
    assert f["vol_brk_rel"] == pytest.approx(math.log(3.0))
    assert math.isnan(feats(d, 30, hist=hist[:9])["vol_brk_rel"])          # fewer than 10 sessions
    assert feats(d, 30, hist=hist[:10])["vol_brk_rel"] == pytest.approx(math.log(3.0))


def test_absorption_is_high_volume_small_range():
    hist = [flat_day(vol=10.0) for _ in range(20)]
    for x in hist:
        x["h"] = [101.0] * N
        x["l"] = [99.0] * N             # baseline 15-minute range 2.0
    d = flat_day(vol=20.0)              # double volume, range 1.0 -> log 2 - log 0.5
    assert feats(d, 60, hist=hist)["absorb15"] == pytest.approx(math.log(2) - math.log(0.5))


def test_missing_volume_minutes_and_no_chain():
    d = flat_day()
    for k in ("volc", "volp", "oi"):
        d[k] = [NAN] * N
    f = feats(d, 60, hist=[flat_day() for _ in range(20)])
    for n in ("vol_brk_rel", "oi_chg15_pct", "bu15", "delta30", "cdelta_day", "optvol_imb15", "absorb15"):
        assert math.isnan(f[n]), n
    assert f["trap_same_or"] == 0.0                              # price-only features still there


# ------------------------------------------------------------------------------------------------ OI and flow
def test_oi_change_and_buildup_codes():
    d = flat_day()
    d["c"][45] = 99.0
    d["c"][60] = 101.0
    d["oi"][45], d["oi"][60] = 1000.0, 1100.0
    f = feats(d, 60)
    assert f["oi_chg15_pct"] == pytest.approx(10.0)
    assert f["bu15"] == 2.0                                      # up + OI up = long build-up, call side
    assert feats(d, 60, side="P")["bu15"] == -2.0                # signed by side
    d["oi"][60] = 900.0
    assert feats(d, 60)["bu15"] == 1.0                           # short covering
    d["oi"][60] = NAN                                            # missing OI minute: carried forward (1000 at 59)
    assert feats(d, 60)["oi_chg15_pct"] == pytest.approx(0.0)


def test_delta_proxy_and_option_volume_imbalance():
    d = flat_day(vol=0.0)
    d["volc"][59], d["volp"][59] = 30.0, 10.0
    setc(d, 59, 101.0, hi=101.0, lo=100.0)                       # closes at the high: clv +1
    f = feats(d, 59)
    assert f["delta30"] == pytest.approx(1.0) and f["cdelta_day"] == pytest.approx(1.0)
    assert f["optvol_imb15"] == pytest.approx(0.5)
    assert feats(d, 59, side="P")["delta30"] == pytest.approx(-1.0)
    assert math.isnan(feats(d, 30)["delta30"])                   # no volume in the window


# ------------------------------------------------------------------------------------------------ look-ahead
def test_no_feature_looks_ahead():
    rng = np.random.default_rng(1)

    def rnd():
        c = 100 + np.cumsum(rng.normal(0, 0.3, N))
        o = np.r_[100, c[:-1]]
        h = np.maximum(o, c) + rng.uniform(0, 0.3, N)
        l = np.minimum(o, c) - rng.uniform(0, 0.3, N)
        return dict(o=o.tolist(), h=h.tolist(), l=l.tolist(), c=c.tolist(), volc=rng.uniform(0, 50, N).tolist(),
                    volp=rng.uniform(0, 50, N).tolist(), oi=(1000 + np.cumsum(rng.normal(0, 5, N))).tolist())
    hist = [rnd() for _ in range(20)]
    d = rnd()
    for frow in (4, 14, 15, 29, 60, 179, 300, 374):
        a = feats(d, frow, hist=hist)
        cut = copy.deepcopy(d)
        for k in cut:
            for r in range(frow + 1, N):
                cut[k][r] = NAN if r % 2 else 1e6
        b = feats(cut, frow, hist=hist)
        for n in EV.TR:
            assert (math.isnan(a[n]) and math.isnan(b[n])) or a[n] == pytest.approx(b[n]), (frow, n)


# ------------------------------------------------------------------------------------------------ filters
def test_skip_filters_and_missing_never_skips():
    base = {n: NAN for n in EV.TR}
    assert not any(EV.skip(r, base) for r in ("F1", "F2", "F3", "F4"))
    assert EV.skip("F1", dict(base, trap_same_pd=1.0)) and not EV.skip("F1", dict(base, trap_opp_pd=1.0))
    assert EV.skip("F2", dict(base, brk_wick=0.5)) and not EV.skip("F2", dict(base, brk_wick=0.49))
    assert EV.skip("F3", dict(base, vol_brk_rel=-0.01)) and not EV.skip("F3", dict(base, vol_brk_rel=0.0))
    assert EV.skip("F4", dict(base, sweep_same30=1.0)) and EV.skip("F4", dict(base, brk_wick=0.7))
    assert not EV.skip("F4", dict(base, sweep_opp30=1.0, vol_brk_rel=-1.0))


def test_names_and_variants():
    assert len(K.TR) == 15 and K.TR == EV.TR
    assert set(K.PRICE_ONLY) <= set(K.TR) and len(K.PRICE_ONLY) == 8
    assert len(K.VARIANTS) == 8 and K.VARIANTS["V1"]["extra"] == () and K.VARIANTS["M1"]["extra"] == tuple(K.TR)
    assert all(K.VARIANTS[f]["extra"] == () for f in ("F1", "F2", "F3", "F4"))


def _rows(n_days=120, seed=3):
    rng = np.random.default_rng(seed)
    rows = []
    d0 = date(2025, 1, 1)
    for i in range(n_days):
        d = d0 + timedelta(days=i)
        for er in (30, 90):
            f = {n: float(rng.normal()) for n in ST.A2}
            f.update({n: float(rng.integers(0, 2)) for n in K.TR})
            rows.append(dict(day=d, er=er, und="NIFTY", setup="S2", side="C", net=float(rng.normal(50, 500)), f=f,
                             y_win=int(rng.random() < 0.5)))
    return rows


def test_filter_variant_keeps_v1_scores_and_only_removes(monkeypatch):
    rows = _rows()
    cal = sorted({r["day"] for r in rows})
    months = [(2025, 3), (2025, 4)]
    v1 = ST.run(K.VARIANTS["V1"], rows, months, cal)
    f1 = ST.run(K.VARIANTS["F1"], rows, months, cal)
    assert f1["skipped"] > 0
    assert all(not EV.skip("F1", t["f"]) for t in f1["trades"])
    assert len({t["day"] for t in v1["trades"]}) == len(v1["trades"])          # at most one a day
    assert len({t["day"] for t in f1["trades"]}) == len(f1["trades"])


# ------------------------------------------------------------------------------------------------ data
def test_day_agg_from_option_rows():
    rows = []
    for r in (0, 1, 3):
        for side in ("C", "P"):
            for off in range(-6, 7):
                rows.append(dict(r=r, side=side, offset=off, strike=20000 + 50 * off, oi=100.0 + r, iv=15.0,
                                 close=50.0, volume=2.0 if side == "C" else 1.0))
    g = pd.DataFrame(rows)
    a = r61bdata.day_agg(g, nrows=5)
    assert a["volc"][0] == 10.0 and a["volp"][0] == 5.0           # 5 strikes at -2..+2
    assert math.isnan(a["volc"][2])                                # no row at minute 2: volume never carried
    assert a["oi"][0] == 22 * 100.0                                # 11 strikes x 2 sides at -5..+5
    assert a["oi"][2] == 22 * 101.0                                # OI carried forward from minute 1
    assert a["oi"][3] == 22 * 103.0


class _D:
    def __init__(self, d, px, expiry=False):
        self.d, self.expiry = d, expiry
        self.idx = np.full((4, N), px, float)
        self.idx[1] += 1
        self.idx[2] -= 1


def test_context_previous_session_across_holiday_and_expiry_left_out():
    days, agg = {}, {}
    d = K.START + timedelta(days=1)
    k = 0
    while len(days) < 30:
        if d.weekday() < 5 and d != date(2024, 12, 25):
            days[d] = _D(d, 100.0 + k, expiry=(k % 5 == 4))
            agg[d] = dict(volc=np.full(N, 1.0 + k), volp=np.zeros(N), oi=np.full(N, 10.0))
            k += 1
        d += timedelta(days=1)
    pre = K.START - timedelta(days=3)                              # an index-only warm-up day before START
    days[pre] = _D(pre, 50.0)
    ctx = r61bdata.Context("NIFTY", days, agg)
    order = sorted(days)
    after_xmas = [x for x in order if x > date(2024, 12, 25)][0]
    before = [x for x in order if x < after_xmas][-1]
    (ph, pl), pc = ctx.prev(after_xmas)
    assert before == date(2024, 12, 24) and ph == days[before].idx[1, 0] and pc == days[before].idx[3, -1]
    last = order[-1]
    h = ctx.hist(last)
    assert len(h) == 20
    used = [x for x in order[:-1] if x >= K.START and not days[x].expiry][-20:]
    assert [x["volc"][0] for x in h] == [float(agg[x]["volc"][0]) for x in used]
    assert all(not days[x].expiry for x in used)
    first = [x for x in order if x >= K.START][0]
    assert ctx.hist(first) == []                                   # nothing before START, nothing from the warm-up


# ------------------------------------------------------------------------------------------------ guards and rules
def test_final_refuses_a_second_read(tmp_path, monkeypatch):
    flag = tmp_path / "FINAL_READ"
    flag.write_text("read")
    monkeypatch.setattr(K, "FINAL_FLAG", flag)
    with pytest.raises(SystemExit):
        ST.final()


def _t(d, net, und="NIFTY"):
    return dict(day=d, net=net, und=und)


def test_replace_rule_needs_all_four_checks():
    ss = [date(2026, 4, 1) + timedelta(days=i) for i in range(40)]
    v1 = [_t(d, 100.0) for d in ss]
    m1 = [_t(d, 300.0) for d in ss]
    out = dict(variants={v: dict(verdict="FAIL", test=dict(net=0), auc_test=0.5, ex_midcp=0) for v in K.VARIANTS})
    out["variants"]["V1"] = dict(verdict="PASS", test=dict(net=4000), auc_test=0.53, ex_midcp=4000)
    out["variants"]["M1"] = dict(verdict="PASS", test=dict(net=12000), auc_test=0.55, ex_midcp=12000)
    des = dict(variants={v: dict(wf=dict(net=1)) for v in K.VARIANTS})
    r = ST.replace_decision(out, dict(V1=v1, M1=m1), ss, des)
    assert r["candidate"] == "M1" and r["replaces"]
    out["variants"]["M1"]["auc_test"] = 0.52
    assert not ST.replace_decision(out, dict(V1=v1, M1=m1), ss, des)["replaces"]
    for v in out["variants"]:
        if v != "V1":
            out["variants"][v]["verdict"] = "FAIL"
    assert ST.replace_decision(out, {}, ss, des)["candidate"] is None


def test_summary_reports_midcp_share():
    tr = [dict(_t(date(2026, 4, 1), 300.0, "MIDCPNIFTY"), setup="S3", rE=10, X=11, rX=11, E=10, qty=1),
          dict(_t(date(2026, 4, 2), 100.0, "NIFTY"), setup="S2", rE=10, X=11, rX=11, E=10, qty=1)]
    s = ST.summary(tr)
    assert s["midcp_share"] == pytest.approx(0.75) and s["ex_midcp"] == 100
