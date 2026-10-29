"""Tests for R131 (PREREG.md): the pivot rule, every strategy's bullish trigger on hand-built bars, the mirror, the
targets, the one-trade-at-a-time sequencer, the holdout lock and the look-ahead guard. No lake data needed."""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r131 as R  # noqa: E402

N = R.N


def grid(days_bars: list, tf: int = 5):
    """A grid from per-day lists of (o, h, l, c) bars of tf minutes, from 09:15. Each bar's minutes: the open, then
    the low and the high (green: low first; red: high first), then the close twice."""
    D = len(days_bars)
    a = {k: np.full((D, N), np.nan) for k in "ohlc"}
    for d, bars in enumerate(days_bars):
        for b, (o, h, l, c) in enumerate(bars):
            r = b * tf
            path = [o, l, h] if c >= o else [o, h, l]
            path += [c] * (tf - 3)
            for j, p in enumerate(path):
                for k in "ohlc":
                    a[k][d, r + j] = p
    days = [date(2026, 1, 5) + timedelta(days=i) for i in range(D)]
    return SimpleNamespace(days=days, o=a["o"], h=a["h"], l=a["l"], c=a["c"])


def flat(n, p=100.0):
    return [(p, p + 0.5, p - 0.5, p)] * n


def bull_cands(g, cfg):
    return R.DETECTORS[cfg["s"]](R.sides_of(g)[1], cfg)


# ------------------------------------------------------------------------------------------------ primitives
def test_swing_highs_matches_r130_loop():
    rng = np.random.default_rng(1)
    h = np.cumsum(rng.normal(size=400))
    k = 3
    ref = [i for i in range(k, len(h) - k) if h[i] > h[i - k:i].max() and h[i] >= h[i + 1:i + k + 1].max()]
    assert R.swing_highs(h, k).tolist() == ref
    assert R.swing_highs(np.array([1.0, 2.0]), 3).tolist() == []          # too short


def test_make_bars_aggregates_and_skips_empty_bars():
    g = grid([[(100, 102, 99, 101), (101, 103, 100, 100.5)]])
    B = R.make_bars(g.o, g.h, g.l, g.c, 5)
    assert len(B.day) == 2                                                  # the other 73 bars have no data
    assert (B.o[0], B.h[0], B.l[0], B.c[0]) == (100, 102, 99, 101)
    assert (B.start[1], B.end[1]) == (5, 9)


def test_atr_uses_only_earlier_bars():
    g = grid([[(100, 101, 99, 100)] * 14 + [(100, 150, 50, 100)]])
    ch = R.Chart(R.make_bars(g.o, g.h, g.l, g.c, 5))
    assert np.isnan(ch.atr[13]) and ch.atr[14] == pytest.approx(2.0)      # the wide bar 14 is not in its own ATR


def test_empty_grid_gives_no_candidates():
    g = SimpleNamespace(days=[date(2026, 1, 5)], **{k: np.full((1, N), np.nan) for k in "ohlc"})
    S = R.sides_of(g)
    for v, cfg in R.VARIANTS.items():
        assert R.candidates(S, cfg) == [], v


# ------------------------------------------------------------------------------------------------ the strategies
SWEEP_CHOCH = [(105, 106, 104, 105), (105, 106, 103, 104), (104, 105, 102, 103), (103, 104, 100, 101),
               (101, 104, 101, 103), (103, 107, 102, 106), (106, 110, 105, 109), (109, 109, 104, 105),
               (105, 106, 103, 104), (104, 105, 102, 103),
               (103, 104, 99, 100),            # 10: sweeps the swing low 100 (pivot 3)
               (100, 106, 100, 105),
               (105, 111, 104, 110.5)]         # 12: closes above the swing high 110 (pivot 6): the CHoCH


def test_silver_bullet_sweep_then_choch():
    g = grid([SWEEP_CHOCH + flat(20, 110)])
    c = bull_cands(g, dict(s="sb", tf=5, target="S"))
    assert len(c) == 1
    assert c[0].r0 == 12 * 5 + 5 and c[0].stop == pytest.approx(99 - 99 * 0.0002)
    # outside the Indian window (10:15 is after 09:45 but the sweep bar 10 starts at 10:05, CHoCH ends 10:19): dropped
    assert bull_cands(g, dict(s="sb", tf=5, target="S", window=(R.K.row(9, 45), R.K.row(10, 4)))) == []


def test_silver_bullet_no_choch_after_12_bars():
    late = SWEEP_CHOCH[:12] + flat(12, 104) + [(104, 111, 104, 110.5)] + flat(5, 110)
    assert bull_cands(grid([late]), dict(s="sb", tf=5, target="S")) == []


def test_cameron_poke_fvg_with_trend_filter():
    # 7 earlier days with falling closes (a downtrend), yesterday's low 95
    days = [flat(75, 120 - 3 * i) for i in range(6)] + [[(100, 101, 95, 100)] + flat(74, 100)]
    today = [(98, 99, 97, 98), (98, 98.5, 94, 94.5),            # 1: pokes below 95
             (94.5, 95.5, 94.2, 95.4), (95.4, 97, 95.2, 96.8),  # 2..3
             (96.8, 98.6, 96.0, 98.4)]                           # 4: low 96.0 > high[2] 95.5: an FVG, close > 95
    days.append(today + flat(40, 98))
    g = grid(days)
    c = bull_cands(g, dict(s="cam", tf=5, target="S", trend=True))
    assert [(x.d, x.r0) for x in c] == [(7, 4 * 5 + 5)]           # bars 2, 3, 4: low[4] 96.0 > high[2] 95.5
    assert c[0].stop == pytest.approx(R.stop_below(94.0))


def test_cameron_trend_filter_blocks_an_uptrend():
    days = [flat(75, 90 + 3 * i) for i in range(6)] + [[(100, 101, 95, 100)] + flat(74, 100)]
    today = [(98, 99, 97, 98), (98, 98.5, 94, 94.5), (94.5, 95.5, 94.2, 95.4), (95.4, 97, 95.2, 96.8),
             (96.8, 98.6, 96.0, 98.4)]
    days.append(today + flat(40, 98))
    assert bull_cands(grid(days), dict(s="cam", tf=5, target="S", trend=True)) == []
    assert len(bull_cands(grid(days), dict(s="cam", tf=5, target="S", trend=False))) == 1


CISD_DAY = flat(14, 110) + [(110, 111, 109.5, 110.8),      # 14: the last green candle (high 111)
                            (110.8, 110.9, 108, 108.2),    # 15..17: a red run, drop 110.8 -> 103 >= 2 ATR (ATR 1)
                            (108.2, 108.3, 105, 105.1),
                            (105.1, 105.2, 103, 103.2),
                            (103.2, 106, 103.1, 105.9),    # 18: green, close 105.9 < 111 and > the run's open? no
                            (105.9, 112, 105.5, 111.5)]    # 19: close 111.5 > 111 (as stated) and > 110.8 (ICT)


def test_cisd_as_stated_and_ict():
    g = grid([CISD_DAY + flat(20, 111)])
    c1 = bull_cands(g, dict(s="cisd", tf=5, target="S", level="green"))
    c2 = bull_cands(g, dict(s="cisd", tf=5, target="S", level="open"))
    assert [x.r0 for x in c1] == [19 * 5 + 5]
    assert [x.r0 for x in c2] == [19 * 5 + 5]
    assert c1[0].stop == pytest.approx(R.stop_below(103.0))


def test_cisd_needs_a_rapid_drop():
    slow = flat(14, 110) + [(110, 111, 109.5, 110.8), (110.8, 110.9, 110.0, 110.2), (110.2, 110.3, 109.9, 110.0),
                            (110, 112, 109.9, 111.5)]
    assert bull_cands(grid([slow + flat(20, 111)]), dict(s="cisd", tf=5, target="S", level="green")) == []


def test_crt_outside_close_then_choch_on_15_minutes():
    yday = [(100, 101, 95, 100)] + flat(24, 100)                               # 15-min bars; yesterday's low 95
    today = [(100, 101, 99, 100.5), (100.5, 101, 98, 98.5), (98.5, 99, 96, 96.5), (96.5, 97, 94, 94.5),
             (94.5, 98, 94.4, 97.8),                                           # 4..: rising
             (97.8, 99.5, 97.5, 99.2), (99.2, 99.4, 97, 97.5)]
    # a swing high at bar 1 of today? build an explicit one: highs 101,101 ... use yesterday's flat highs 100.5
    today += [(97.5, 97.6, 93.0, 93.2),                                        # 7: closes below 95 (outside)
              (93.2, 96, 93.1, 95.8), (95.8, 101.5, 95.5, 101.2)]              # 9: closes above the swing high
    g = grid([yday, today + flat(10, 101)], tf=15)
    c = bull_cands(g, dict(s="crt", tf=15, target="S", rng="day"))
    assert len(c) == 1 and c[0].r0 == 9 * 15 + 15 and c[0].stop == pytest.approx(R.stop_below(93.0))


def test_crt_4h_waits_for_the_candle_to_close():
    bars = [(100, 101, 99, 100)] * 16 + [(100, 100.2, 96, 96.5), (96.5, 104, 96.4, 103.8)] + flat(7, 103)
    g = grid([bars], tf=15)
    assert bull_cands(g, dict(s="crt", tf=15, target="S", rng="4h")) == []      # all before 13:15


def test_amd_box_false_break_cisd():
    box = [(100, 100.4, 99.6, 100.1)] * 14 + [(100, 100.3, 99.7, 99.9)] * 12   # ATR 0.8, box range 0.6
    move = [(99.9, 100.0, 99.0, 99.1),         # 26: red, breaks the box low 99.7 (the false break)
            (99.1, 99.2, 98.6, 98.7),          # 27: red, the lowest low
            (98.7, 100.2, 98.65, 100.1)]       # 28: closes above the run's first open 99.9 (ICT CISD)
    g = grid([box + move + flat(20, 100)])
    c = bull_cands(g, dict(s="amd", tf=5, target="S", level="open"))
    assert [x.r0 for x in c] == [28 * 5 + 5] and c[0].stop == pytest.approx(R.stop_below(98.6))


def test_ifvg_touch_and_close():
    bars = flat(5, 105) + [(105, 105.2, 104.8, 104.9),     # 5
                           (104.9, 104.95, 102, 102.1),    # 6: big red
                           (102.1, 103.5, 101.5, 101.8),   # 7: high 103.5 < low[5] 104.8: a bearish FVG [103.5, 104.8]
                           (101.8, 105.5, 101.7, 105.3),   # 8: closes above 104.8: inverted (support)
                           (105.3, 106, 105.1, 105.8),     # 9
                           (105.8, 105.9, 104.6, 105.2)]   # 10: dips to 104.6 <= 104.8 (the retest)
    g = grid([bars + flat(20, 105.5)])
    c1 = bull_cands(g, dict(s="ifvg", tf=5, target="S", trig="touch"))
    c2 = bull_cands(g, dict(s="ifvg", tf=5, target="S", trig="close"))
    # bar 10 is red: its minutes are open, high, low (row 52), close -> the touch is minute 52, entry 53
    assert [x.r0 for x in c1] == [10 * 5 + 2 + 1] and c1[0].stop == pytest.approx(R.stop_below(103.5))
    assert [x.r0 for x in c2] == [10 * 5 + 5]                  # bar 10 closes 105.2 > 104.8


def test_turtle_soup_sweep_then_cisd():
    # bar 9 made green, so the drop run is bar 10 alone and ICT's level is its open (103)
    bars = SWEEP_CHOCH[:9] + [(103, 104.5, 102, 104)] + [(103, 103.2, 99, 99.5),   # 10: red, sweeps 100
                               (99.5, 102, 99.4, 101.8),      # 11: closes below 103
                               (101.8, 104, 101.5, 103.5)]    # 12: close 103.5 > 103 (the open of red bar 10)
    g = grid([bars + flat(20, 103)])
    c = bull_cands(g, dict(s="ts", tf=5, target="S", lvl="swing"))
    assert [x.r0 for x in c] == [12 * 5 + 5] and c[0].stop == pytest.approx(R.stop_below(99.0))


def test_order_block_and_bos():
    bars = flat(14, 100) + [(100, 100.2, 99.0, 99.2),           # 14: the last red bar (the order block 99.0-100.2)
                            (99.2, 101.5, 99.1, 101.4),         # 15..16: a green impulse 99.2 -> 103 (>= 2 ATR)
                            (101.4, 103.2, 101.3, 103.0),
                            (103.0, 103.1, 101.0, 101.5),       # 17
                            (101.5, 101.6, 100.1, 100.4)]       # 18: back to 100.1 <= 100.2: the touch
    g = grid([bars + flat(20, 101)])
    c = bull_cands(g, dict(s="ob", tf=5, target="S", bos=False))
    assert [x.r0 for x in c] == [18 * 5 + 2 + 1] and c[0].stop == pytest.approx(R.stop_below(99.0))
    # no swing high was known before the impulse here, so the BOS variant finds nothing
    assert bull_cands(g, dict(s="ob", tf=5, target="S", bos=True)) == []


def test_breaker_from_a_failed_bearish_block():
    bars = flat(14, 100) + [(100, 101.0, 99.8, 100.8),          # 14: the last green bar (bearish OB 99.8-101.0)
                            (100.8, 100.9, 98.5, 98.6),         # 15..16: a red impulse
                            (98.6, 98.7, 96.8, 97.0),
                            (97.0, 101.6, 96.9, 101.5),         # 17: closes above 101.0: the block failed
                            (101.5, 102.5, 101.4, 102.3),       # 18
                            (102.3, 102.4, 100.9, 101.6)]       # 19: back to 100.9 <= 101.0: the retest
    g = grid([bars + flat(20, 101.5)])
    c = bull_cands(g, dict(s="brk", tf=5, target="S", trig="touch"))
    assert [x.r0 for x in c] == [19 * 5 + 2 + 1] and c[0].stop == pytest.approx(R.stop_below(99.8))
    c3 = bull_cands(g, dict(s="brk", tf=5, target="S", trig="close"))
    assert [x.r0 for x in c3] == [19 * 5 + 5]


def test_ote_pullback_to_the_fib():
    ch_bars = [(105, 105.5, 104.5, 105)] * 14 + [
        (105, 105.2, 104.0, 104.2), (104.2, 104.3, 103.0, 103.2), (103.2, 103.4, 102.0, 102.2),   # 14..16
        (102.2, 102.3, 100.0, 100.5),                                                              # 17: swing low 100
        (100.5, 103.0, 100.4, 102.8), (102.8, 104.5, 102.5, 104.4), (104.4, 106.0, 104.2, 105.8),  # 18..20
        (105.8, 106.2, 104.8, 105.0),                                                              # 21: swing high 106.2
        (105.0, 105.5, 103.5, 103.8), (103.8, 104.0, 102.5, 102.8), (102.8, 103.5, 102.2, 103.2),  # 22..24
        (103.2, 103.4, 101.0, 101.2),                                                              # 25: swing low 101
        (101.2, 104.0, 101.1, 103.8), (103.8, 106.0, 103.6, 105.9), (105.9, 108.0, 105.8, 107.8),  # 26..28
        (107.8, 110.0, 107.6, 109.8),                                                              # 29: swing high 110
        (109.8, 109.9, 108.5, 108.8), (108.8, 109.0, 107.9, 108.2), (108.2, 108.6, 107.6, 107.9),  # 30..32
        (107.9, 108.0, 104.2, 104.5)]                                                              # 33: 104.2 <= 104.438
    g = grid([ch_bars + flat(20, 107)])
    c = bull_cands(g, dict(s="ote", tf=5, target="S", fib=0.618))
    leg = 110.0 - 101.0
    assert len(c) == 1 and c[0].stop == pytest.approx(R.stop_below(101.0))
    assert 110.0 - 0.618 * leg == pytest.approx(104.438)
    assert c[0].r0 == 33 * 5 + 2 + 1                            # red bar 33: open, high, low (the touch), close


# ------------------------------------------------------------------------------------------------ mirror, targets, sequencer
def test_mirror_bear_equals_bull_on_mirrored_index():
    rng = np.random.default_rng(7)
    g = random_grid(rng, 6)
    m = SimpleNamespace(days=g.days, o=-g.o, h=-g.l, l=-g.h, c=-g.c)
    for v in ("S1-V1", "S3-V2", "S6-V1", "S8-V1"):
        cfg = R.VARIANTS[v]
        a = R.DETECTORS[cfg["s"]](R.sides_of(g)[-1], cfg)
        b = R.DETECTORS[cfg["s"]](R.sides_of(m)[1], cfg)
        assert [(x.d, x.r0, round(x.stop, 6)) for x in a] == [(x.d, x.r0, round(x.stop, 6)) for x in b], v


def test_struct_target_skips_taken_highs():
    g = grid([SWEEP_CHOCH + flat(20, 110)])
    S = R.sides_of(g)[1]
    ch = S.chart(5)
    # at the CHoCH entry (row 65) the only swing high (110, pivot 6) was traded above by bar 12 (111): no target
    assert R.struct_target(S, ch, 0, 65, 110.5) is None
    # before the sweep (row 50) it is untaken and above 100
    assert R.struct_target(S, ch, 0, 50, 100.0) == 110.0
    assert R.struct_target(S, ch, 0, 50, 115.0) is None


def test_exit_stop_first_in_one_minute():
    mh = np.array([101, 103, 99], dtype=float)
    ml = np.array([100, 98, 98], dtype=float)
    mo = np.array([100, 101, 99], dtype=float)
    assert R.exit_on(mh, ml, mo, 0, 98.5, 102.5, 2)[:2] == (1, "stop")
    assert R.exit_on(mh, ml, mo, 0, 97.0, 102.5, 2)[:2] == (1, "target")
    assert R.exit_on(mh, ml, mo, 0, 90.0, 120.0, 2)[:2] == (2, "time")


def test_sequencer_one_trade_at_a_time_and_opposite_same_minute():
    g = grid([flat(75, 100)])
    S = R.sides_of(g)
    C = R.Cand
    cands = [(1, C(0, 20, 98.0, 5)), (1, C(0, 25, 98.0, 5)),            # the second enters while the first is open
             (1, C(0, 300, 98.0, 5)), (-1, C(0, 300, -102.0, 5)),       # both sides in one minute: neither
             (1, C(0, 365, 98.0, 5))]                                    # after 15:00: late
    tr, sk = R.sequence(S, cands, dict(target="2R"), set(), g.days, lambda d: True)
    assert len(tr) == 1 and tr[0]["entry"] == 20 and tr[0]["why"] == "time"
    assert sk["busy"] == 1 and sk["both"] == 1 and sk["late"] == 1
    tr2, sk2 = R.sequence(S, cands[:1], dict(target="2R"), {0}, g.days, lambda d: True)
    assert tr2 == [] and sk2["no_contract"] == 1                         # a blocked (no contract) day
    tr3, sk3 = R.sequence(S, [(1, C(0, 20, 100.5, 5))], dict(target="2R"), set(), g.days, lambda d: True)
    assert tr3 == [] and sk3["no_room"] == 1                             # the open is already at the stop


def test_holdout_lock_refuses_a_second_read(tmp_path):
    R.holdout_lock("start", out=tmp_path, results=tmp_path)
    with pytest.raises(R.HoldoutError):
        R.holdout_lock("start", out=tmp_path, results=tmp_path)
    R.holdout_lock("done", out=tmp_path, results=tmp_path)
    with pytest.raises(R.HoldoutError):
        R.holdout_lock("start", out=tmp_path, results=tmp_path)


def test_thirty_variants_three_per_strategy():
    assert len(R.VARIANTS) == 30
    for s in R.STRATS:
        assert sum(v["s"] == s for v in R.VARIANTS.values()) == 3


# ------------------------------------------------------------------------------------------------ look-ahead guard
def random_grid(rng, D):
    """Random-walk minutes with quiet and busy spells (so boxes, impulses and gaps all occur)."""
    vol = np.repeat(rng.choice([0.3, 1.0, 3.0], size=(D, N // 25)), 25, axis=1)[:, :N]
    steps = rng.standard_t(4, size=(D, N)) * vol
    c = 20000 + np.cumsum(steps.ravel()).reshape(D, N)
    o = np.roll(c.ravel(), 1).reshape(D, N)
    o[0, 0] = c[0, 0]
    w = np.abs(rng.normal(size=(D, N))) * vol
    h = np.maximum(o, c) + w
    l = np.minimum(o, c) - np.abs(rng.normal(size=(D, N))) * vol
    days = [date(2026, 1, 5) + timedelta(days=i) for i in range(D)]
    return SimpleNamespace(days=days, o=o, h=h, l=l, c=c)


def truncate(g, d, r0):
    """Every minute at or after (d, r0) removed, and every later session."""
    out = {}
    for k in "ohlc":
        x = getattr(g, k)[:d + 1].copy()
        x[d, r0:] = np.nan
        out[k] = x
    return SimpleNamespace(days=g.days[:d + 1], **out)


def test_lookahead_guard_every_variant():
    rng = np.random.default_rng(131)
    g = random_grid(rng, 25)
    S = R.sides_of(g)
    found = 0
    for v, cfg in R.VARIANTS.items():
        full = R.candidates(S, cfg)
        if full:
            found += 1
        pick = [full[i] for i in rng.choice(len(full), size=min(6, len(full)), replace=False)] if full else []
        for sd, c in pick:
            St = R.sides_of(truncate(g, c.d, c.r0))
            again = [(s2, x.d, x.r0, round(x.stop, 6)) for s2, x in R.candidates(St, cfg)]
            assert (sd, c.d, c.r0, round(c.stop, 6)) in again, (v, sd, c)
    assert found == len(R.VARIANTS)                         # every variant has setups on this data


# ------------------------------------------------------------------------------------------------ twins
def test_last_before_matches_d3_last():
    x = np.array([np.nan, 1.0, np.nan, 3.0, np.nan])
    lb = R.last_before(x)
    for r in range(len(x)):
        ref = R.D3._last(x[:r]) if r else np.nan
        assert (np.isnan(ref) and np.isnan(lb[r])) or ref == lb[r]


def test_vectorised_twins_equal_the_per_trade_loop():
    rng = np.random.default_rng(3)
    g = random_grid(rng, 1)
    S = R.sides_of(g)[1]
    d0 = date(2025, 6, 2)
    step = 50.0
    contracts = {}
    for k in np.unique([R.D3.atm(x, step) for x in g.c[0]]):
        a = np.full((N, 5), np.nan)
        px = np.abs(150 + (g.c[0] - k) * 0.5 + rng.normal(size=N))
        a[:, 0], a[:, 3] = px, px + rng.normal(size=N) * 0.3
        a[:, 4] = rng.integers(0, 3000, size=N).astype(float)
        a[rng.random(N) < 0.1, :] = np.nan                       # minutes with no trade
        contracts[("C", float(k))] = a
    r1s = rng.integers(R.FIRST_TWIN, R.LAST_ENTRY + 1, size=200)
    lastc = R.last_before(g.c[0])
    got = R.twin_nets(r1s, lastc, S.mh[0], S.ml[0], S.mo[0], contracts, "C", step, 8.0, 16.0, 65, d0, "NIFTY")
    for r1, v in zip(r1s, got):
        ref1, c1 = S.mo[0][r1], R.D3._last(g.c[0][:r1])
        e1 = R.exit_on(S.mh[0], S.ml[0], S.mo[0], int(r1), ref1 - 8.0, ref1 + 16.0, R.EXIT_LAST)
        t1 = R.option_trade(contracts.get(("C", R.D3.atm(c1, step))), int(r1), e1[0], e1[1], 65, d0, "NIFTY")
        if t1 is None:
            assert np.isnan(v)
        else:
            assert v == pytest.approx(t1[0], abs=1e-6)
