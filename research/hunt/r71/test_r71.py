"""R71 tests: synthetic bars only (no lake, no secrets). Each rule on a hand-made chart, the look-ahead guard for every
rule, and the study's pure pieces (stats, walk-forward picks, BH, gates, index stop, overlap, correlation).

    python -m pytest research/hunt/r71/test_r71.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r71cfg as R  # noqa: E402
import r71rules as P  # noqa: E402
import r71study as S  # noqa: E402

D0 = date(2025, 1, 6)


def series(days_bars):
    """[(day, [(o, h, l, c), ...]), ...] -> the flat arrays fam_r44.bars5 gives (d, b, o, h, l, c)."""
    d, b, o, h, l, c = [], [], [], [], [], []
    for day, bars in days_bars:
        for k, (o_, h_, l_, c_) in enumerate(bars):
            d.append(day)
            b.append(k)
            o.append(o_)
            h.append(h_)
            l.append(l_)
            c.append(c_)
    return np.array(d, dtype=object), np.array(b), np.array(o, float), np.array(h, float), np.array(l, float), \
        np.array(c, float)


def warm_days(n=2, px=100.0, rng=None):
    """n quiet days of 75 bars (so WARM bars have passed), a gentle random walk."""
    rng = rng or np.random.default_rng(0)
    out = []
    for k in range(n):
        bars = []
        for _ in range(75):
            step = rng.normal(0, 0.2)
            o_, c_ = px, px + step
            bars.append((o_, max(o_, c_) + 0.1, min(o_, c_) - 0.1, c_))
            px = c_
        out.append((D0 + timedelta(days=k), bars))
    return out, px


def flat_bars(px, n):
    return [(px, px + 0.1, px - 0.1, px) for _ in range(n)]


def day_after(days):
    return days[-1][0] + timedelta(days=1)


# ------------------------------------------------------------------------------------------------ each rule
def test_inside_bar_breaks_up():
    w, px = warm_days()
    bars = [(px, px + 5, px - 5, px)] * 3            # 15-min bar M: 09:15-09:29, high px+5, low px-5
    bars += [(px, px + 2, px - 2, px)] * 3           # bar I inside M
    bars += [(px, px + 3, px - 1, px + 1), (px + 1, px + 7, px, px + 6)]   # 2nd bar after I closes above M's high
    bars += flat_bars(px + 6, 75 - len(bars))
    sig = P.inside_bar(*series(w + [(day_after(w), bars)]), k=3)
    today = [s for s in sig if s[0] >= 150]
    assert today[0][0] == 150 + 7 and today[0][1] == 1 and today[0][2] == pytest.approx(px - 5)


def test_inside_bar_needs_the_break_within_30_minutes():
    w, px = warm_days()
    bars = [(px, px + 5, px - 5, px)] * 3 + [(px, px + 2, px - 2, px)] * 3 + flat_bars(px, 6)
    bars += [(px, px + 9, px, px + 8)] + flat_bars(px + 8, 75 - len(bars) - 1)
    sig = P.inside_bar(*series(w + [(day_after(w), bars)]), k=3)
    assert not [s for s in sig if s[0] >= 150 and s[2] == pytest.approx(px - 5)]    # M's break came too late


def test_nr_bar_smallest_of_seven():
    w, px = warm_days()
    bars = []
    for g in range(6):                                # six wide 15-min bars
        bars += [(px, px + 4, px - 4, px)] * 3
    bars += [(px, px + 1, px - 1, px)] * 3            # the 7th is the narrowest
    bars += [(px, px + 0.5, px - 3, px - 2)]          # first 5-min close below it -> put
    bars += flat_bars(px - 2, 75 - len(bars))
    sig = [s for s in P.nr_bar(*series(w + [(day_after(w), bars)]), n=7) if s[0] >= 150]
    assert sig[0] == (150 + 21, -1, pytest.approx(px + 1))


def test_squeeze_release_up():
    rng = np.random.default_rng(1)
    w, px = warm_days(rng=rng)
    bars = []
    for k in range(30):                               # very quiet: the squeeze is on
        bars.append((px, px + 0.05, px - 0.05, px + (0.01 if k % 2 else -0.01)))
    bars.append((px, px + 6, px, px + 5.5))           # an explosive close above the upper band
    bars += flat_bars(px + 5.5, 75 - len(bars))
    sig = [s for s in P.squeeze(*series(w + [(day_after(w), bars)]), s_min=6) if s[0] >= 150]
    assert sig and sig[0][0] == 150 + 30 and sig[0][1] == 1 and sig[0][2] < px + 5.5


def test_flag_after_impulse():
    w, px = warm_days()
    bars = flat_bars(px, 10)
    bars += [(px, px + 3.2, px - 0.1, px + 3), (px + 3, px + 6.2, px + 2.9, px + 6), (px + 6, px + 9.2, px + 5.9, px + 9)]
    top = px + 9.2
    bars += [(px + 9, px + 9.1, px + 7.5, px + 8)] * 4    # flag: below the pole high, < 50% retrace
    bars += [(px + 8, px + 10, px + 7.9, px + 9.8)]       # close above the pole high
    bars += flat_bars(px + 9.8, 75 - len(bars))
    sig = [s for s in P.flag(*series(w + [(day_after(w), bars)]), K=2.0) if s[0] >= 150]
    assert sig[0][0] == 150 + 17 and sig[0][1] == 1 and sig[0][2] == pytest.approx(px + 7.5)
    assert top > px + 9


def test_flag_dies_on_deep_retrace():
    w, px = warm_days()
    bars = flat_bars(px, 10)
    bars += [(px, px + 3.2, px - 0.1, px + 3), (px + 3, px + 6.2, px + 2.9, px + 6), (px + 6, px + 9.2, px + 5.9, px + 9)]
    bars += [(px + 9, px + 9.1, px + 2, px + 3)] * 4      # retraces far more than half
    bars += [(px + 3, px + 10, px + 3, px + 9.8)]
    bars += flat_bars(px + 9.8, 75 - len(bars))
    assert [s for s in P.flag(*series(w + [(day_after(w), bars)]), K=2.0) if s[0] >= 150] == []


def test_double_bottom_neckline_break():
    w, px = warm_days()
    v = [0, -1, -2, -3, -4, -3, -2, -1, 0, 1, 2, 1, 0, -1, -2, -3, -3.9, -3, -2, -1, 0, 1, 2, 3]
    bars = flat_bars(px, 5)
    for x in v:
        bars.append((px + x, px + x + 0.3, px + x - 0.3, px + x))
    bars += flat_bars(px + 3, 75 - len(bars))
    sig = [s for s in P.double_bottom(*series(w + [(day_after(w), bars)]), T_=0.5) if s[0] >= 150]
    assert sig and sig[0][1] == 1 and sig[0][2] == pytest.approx(px - 4.3)
    assert sig[0][0] == 150 + 5 + 23                    # the first close above the neckline (px + 2.3) is +3


def test_heikin_ashi_flip_with_trend():
    w, px = warm_days(n=3)
    bars = []
    for g in range(16):                               # a steady uptrend of 15-min bars
        bars += [(px, px + 1.1, px - 0.1, px + 1)] * 3
        px += 1
    for _ in range(4):                                # a pullback: red Heikin-Ashi bars
        bars += [(px, px + 0.1, px - 1.1, px - 1)] * 3
        px -= 1
    bars += [(px + 2, px + 4.1, px + 2, px + 4)] * 3  # gaps up green: HA low = HA open (no lower wick)
    bars += flat_bars(px + 2.9, 75 - len(bars))
    sig = [s for s in P.ha_flip(*series(w + [(day_after(w), bars)]), L=20) if s[0] >= 225]
    assert any(s[1] == 1 for s in sig)


def test_three_bar_reversal_at_day_low():
    w, px = warm_days()
    bars = flat_bars(px, 8)
    bars += [(px, px + 0.2, px - 3, px - 2.5), (px - 2.5, px - 2, px - 2.9, px - 2.2), (px - 2.2, px + 0.6, px - 2.3, px + 0.5)]
    bars += flat_bars(px + 0.5, 75 - len(bars))
    sig = [s for s in P.three_bar(*series(w + [(day_after(w), bars)]), start_b=6) if s[0] >= 150]
    assert sig[0] == (150 + 10, 1, pytest.approx(px - 3))


def test_three_bar_respects_start():
    w, px = warm_days()
    bars = flat_bars(px, 2)
    bars += [(px, px + 0.2, px - 3, px - 2.5), (px - 2.5, px - 2, px - 2.9, px - 2.2), (px - 2.2, px + 0.6, px - 2.3, px + 0.5)]
    bars += flat_bars(px + 0.5, 75 - len(bars))
    assert [s for s in P.three_bar(*series(w + [(day_after(w), bars)]), start_b=6) if 150 <= s[0] < 160] == []


def test_swing_structure_higher_high_higher_low():
    w, px = warm_days()
    path = [0, 2, 4, 2, 0, 1, 3, 6, 4, 2, 3, 5, 7]   # 15-min closes: HH 4 -> 6, HL 0 -> 2, then a break above 6
    bars = []
    for x in path:
        bars += [(px + x, px + x + 0.2, px + x - 0.2, px + x)] * 3
    bars += flat_bars(px + 7, 75 - len(bars))
    sig = [s for s in P.swing_structure(*series(w + [(day_after(w), bars)]), W=2) if s[0] >= 150 and s[1] == 1]
    assert (150 + 36, 1, pytest.approx(px + 2 - 0.2)) in sig      # 15-min bar 12 closes above the HH (px + 6.2)


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("rule", list(P.RULES))
@pytest.mark.parametrize("param_i", [0, 1])
def test_look_ahead_guard(rule, param_i):
    """A rule's signals at or before bar t never change when every bar after t is replaced."""
    rng = np.random.default_rng(7)
    days = []
    px = 100.0
    for k in range(8):
        bars = []
        for _ in range(75):
            step = rng.normal(0, 0.6) + (0.15 if k % 2 else -0.15)
            o_, c_ = px, px + step
            bars.append((o_, max(o_, c_) + abs(rng.normal(0, 0.3)), min(o_, c_) - abs(rng.normal(0, 0.3)), c_))
            px = c_
        days.append((D0 + timedelta(days=k), bars))
    d, b, o, h, l, c = series(days)
    param = P.RULES[rule][3][param_i]
    full = P.signals(rule, param, d, b, o, h, l, c)
    for t in (180, 260, 333, 410, 520):
        o2, h2, l2, c2 = o.copy(), h.copy(), l.copy(), c.copy()
        n = len(c) - t - 1
        c2[t + 1:] = c[t + 1:] + rng.normal(0, 3, n)
        o2[t + 1:] = c2[t:-1]
        h2[t + 1:] = np.maximum(o2[t + 1:], c2[t + 1:]) + 1
        l2[t + 1:] = np.minimum(o2[t + 1:], c2[t + 1:]) - 1
        cut = P.signals(rule, param, d, b, o2, h2, l2, c2)
        assert [s for s in full if s[0] <= t] == [s for s in cut if s[0] <= t], (rule, param, t)


def test_family_has_eight_rules_with_two_point_grids():
    assert len(P.RULES) == 8
    for fn, name, orig, grid in P.RULES.values():
        assert orig == grid[0] and len(grid) == 2


# ------------------------------------------------------------------------------------------------ study pieces
def tr(day, net, und="NIFTY", side="C", er=30, why="stop"):
    return dict(day=day, net=net, und=und, side=side, er=er, why=why)


def test_stats_basic():
    t = [tr(D0, 100), tr(D0, -50), tr(D0 + timedelta(1), -30), tr(D0 + timedelta(2), 10)]
    s = S.stats(t, months=2.0)
    assert s["trades"] == 4 and s["net"] == 30 and s["win"] == 0.5
    assert s["worst_day"] == -30 and s["max_dd"] == 30 and s["green_days"] == pytest.approx(2 / 3, abs=1e-4)
    assert s["per_month"] == 2.0 and s["avg_win"] == 55 and s["avg_loss"] == -40
    assert S.stats([])["trades"] == 0


def test_bh_matches_hand():
    q = S.bh([0.01, 0.04, 0.03, 0.5])
    assert q == pytest.approx([0.04, 0.0533333, 0.0533333, 0.5], abs=1e-6)


def test_wf_picks_use_only_days_before_the_fold():
    a = [tr(date(2025, 1, 10), 10)] * 25 + [tr(date(2025, 5, 1), -1000)]
    b = [tr(date(2025, 1, 10), 5)] * 25 + [tr(date(2025, 5, 1), 999)]
    picks = S.wf_picks({"a": a, "b": b}, "a")
    assert picks[0] == "a"                            # before Apr 2025 'a' had the better net
    assert picks[1] == "b"                            # by Jul 2025 the April trades count
    assert S.wf_trades({"a": a, "b": b}, picks)[0]["net"] == -1000


def test_wf_falls_back_to_original_with_few_trades():
    a = [tr(date(2025, 1, 10), 10)] * 5
    assert S.wf_picks({"a": a, "b": a}, "b") == ["b"] * 4
    assert S.final_pick({"a": a}, "zz") == "zz"


def test_gates():
    good = dict(net=1000, trades=50, ex_top5=10)
    assert S.gates(good, 1, 1, 0.01, 0.05) == []
    f = S.gates(dict(net=1000, trades=30, ex_top5=-1), 0, 1, 0.2, 0.3)
    assert "fewer than 40 trades" in f and "top 5 >= net" in f and "design net <= 0" in f and "BH q > 0.10" in f
    assert "twin p >= 0.05" in S.gates(good, 1, 1, None, None)


def test_index_exit_row():
    cl = np.array([100, 101, np.nan, 99, 98, 97], float)
    assert S.index_exit_row(cl, 1, 99.5, 1, 6) == 4         # minute 3 closes below -> sold at 4's open
    assert S.index_exit_row(cl, 1, 99.5, 1, 4) == -1        # ... but the square-off comes first
    assert S.index_exit_row(cl, 0, 100.5, -1, 6) == 2       # a put: minute 1 closes above
    assert S.index_exit_row(cl, 0, 50, 1, 6) == -1


def test_overlap_and_corr():
    a = [tr(D0, 10, er=30), tr(D0, 5, er=100, side="P")]
    b = [tr(D0, 3, er=40), tr(D0 + timedelta(1), 4, er=100, side="P")]
    assert S.overlap(a, b) == 0.5
    days = [D0, D0 + timedelta(1), D0 + timedelta(2)]
    assert S.daily_corr(a, a, days) == 1.0
    assert S.daily_corr([], a, days) is None
    cb = S.combined(a, b)
    assert cb["net"] == 22 and cb["worst_day"] == 4


def test_exits_are_as_registered():
    assert R.EXITS["XB"]["target"] == 0.30 and R.EXITS["XB"]["tmax"] == 30 and not R.EXITS["XB"]["steps"]
    assert R.EXITS["XL"]["target"] is None and (0.25, 0.05) in R.EXITS["XL"]["steps"]
    assert R.is_breakeven(R.EXITS["XL"]["steps"][0][1])
    assert R.EXITS["XI"]["index_stop"] and R.EXITS["XI"]["stop"] == 0.25 and R.EXITS["XI"]["tmax"] == 60
    assert R.ORIG_EXIT == "XL" and R.SEED == 71 and R.TWINS == 500


def test_periods_match_donchian():
    assert R.START == date(2024, 11, 20) and R.DESIGN_END == date(2026, 3, 31) and R.FINAL_START == date(2026, 4, 1)
    assert R.period_of(date(2026, 3, 31)) == "design" and R.period_of(date(2026, 4, 1)) == "final"
    assert R.fold_of(date(2025, 8, 1)) == 1 and R.fold_of(date(2025, 3, 1)) is None


def test_gp_names_round_trip():
    assert S.gp_parse("P4", S.gp_name(1.5, "XI")) == (1.5, "XI")
    assert S.gp_parse("P2", S.gp_name(4, "XB")) == (4, "XB")


def test_report_renders_from_small_results():
    import r71report as Rp
    s = dict(trades=40, days=10, net=-1234, per_trade=-30.9, win=0.4, avg_win=100, avg_loss=-90, worst_day=-500,
             max_dd=900, green_days=0.4, ex_top5=-2000, per_month=6.0, why={})
    des = dict(donchian={x: dict(design=s) for x in ("XB", "XL")}, rules={})
    fin = dict(end="2026-10-13", sessions=133, read="t", donchian={"XB": s, "XL": s},
               donchian_alone=dict(net=1, max_dd=2, worst_day=-3), tests={})
    for r in P.RULES:
        gp = S.gp_name(P.RULES[r][2], "XL")
        des["rules"][r] = dict(name=P.NAMES[r], original=gp, final_pick=gp, wf_picks=[gp] * 4, wf=s,
                               grid={gp: dict(design=s, folds=[1, 2, 3, 4])})
        for k in ("O", "RT"):
            fin["tests"][f"{r}-{k}"] = dict(rule=r, kind=k, setting=gp, final=s, p=0.5, q=0.9, twin_mean=-5,
                                             design_net=-1, wf_net=-1, corr_donchian=0.1, overlap_donchian=0.2,
                                             with_donchian=dict(net=1, max_dd=2, worst_day=-3), verdict="FAIL",
                                             fails=["final net <= 0"])
    md = Rp.render(des, fin)
    assert "Passed: none" in md and md.count("| P1-O") == 1 and "-Rs 1,234" in md
    assert Rp.rs(None) == "-" and Rp.pct(0.456) == "46%"
