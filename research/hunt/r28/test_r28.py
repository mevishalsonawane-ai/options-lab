"""R28 tests: periods, the lake builder, the filters, twins, BH and the holdout guard (no lake needed; synthetic data).

    python -m pytest research/hunt/r28/test_r28.py -q
"""
import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r28cfg as K  # noqa: E402
import r28gex as G  # noqa: E402
import study as S  # noqa: E402
import describe as DS  # noqa: E402
from research.brain import bs, config, gex  # noqa: E402


# ------------------------------------------------------------------------------------------------ periods and clock
def test_clock_rows_are_the_bars_closing_at_the_time():
    assert K.row_closing(9, 16) == 0 and K.CLOCK["10:00"] == 44 and K.CLOCK["13:45"] == 269
    assert K.CLOCK["15:15"] == 359 and K.CLOCK["09:20"] == 4


def test_periods_and_embargoes():
    assert K.part_a(date(2022, 12, 30)) == "holdout" and K.part_a(date(2023, 1, 10)) == "embargo"
    assert K.part_a(date(2023, 1, 16)) == "design" and K.part_a(date(2026, 10, 12)) == "design"
    assert K.part_h(date(2024, 1, 1)) == "holdout" and K.part_h(date(2025, 3, 31)) == "holdout"
    assert K.part_h(date(2023, 12, 20)) == "embargo" and K.part_h(date(2025, 4, 10)) == "embargo"
    assert K.part_h(date(2022, 6, 1)) == "design" and K.part_h(date(2025, 4, 15)) == "design"
    # Step 2 reads neither holdout nor embargo
    assert not K.is_describe(date(2022, 6, 1)) and not K.is_describe(date(2024, 6, 1))
    assert K.is_describe(date(2023, 6, 1)) and K.is_describe(date(2026, 1, 5))
    assert K.wf_h(date(2021, 9, 1)) == "2021-22" and K.wf_h(date(2026, 1, 1)) == "2025-26"


# ------------------------------------------------------------------------------------------------ builder
def test_expiry_days_from_time_value():
    d = [date(2024, 1, i) for i in (1, 2, 3, 4, 5)]
    tv = pd.Series([1.0, 0.2, 1.1, 0.9, 0.1], index=d)        # the 2nd is an expiry; the last session never is
    assert G.expiry_days(tv) == [date(2024, 1, 2)]


def test_next_expiry_on_or_after():
    e = [date(2024, 1, 4), date(2024, 1, 11)]
    assert G.next_on_or_after(e, date(2024, 1, 4), "NIFTY") == date(2024, 1, 4)
    assert G.next_on_or_after(e, date(2024, 1, 5), "NIFTY") == date(2024, 1, 11)
    assert G.next_on_or_after(e, date(2024, 1, 12), "NIFTY") is not None        # the calendar's rule after the data


def lake_day(spot0=22000.0, minutes=range(0, 60), iv=0.15, day=date(2024, 3, 5), exp=date(2024, 3, 7)):
    rows, spot = [], np.full(375, np.nan)
    for m in minutes:
        s = spot0 * (1 + 0.0001 * np.sin(m / 7))
        spot[m] = s
        atm = round(s / 50) * 50
        T = gex.years_to_expiry(day, np.array([m]), exp)[0]
        for o in range(-10, 11):
            k = atm + 50 * o
            for side in ("C", "P"):
                px = float(bs.price(s, k, T, iv, side == "C", config.RISK_FREE, config.DIV_YIELD))
                rows.append(dict(minute=m, offset=o, side=side, strike=k, close=round(px, 2), volume=50,
                                 oi=1e6 + 1e4 * m + 5e4 * o * (1 if side == "C" else -1), spot=s))
    return pd.DataFrame(rows), spot, day, exp


def test_builder_day_equals_the_brain_core_and_is_point_in_time():
    o, spot, day, exp = lake_day()
    full = G.day_series(G.clean(o, "NIFTY", spot), spot, day, exp, True)
    d = gex.last_trade(o[["minute", "offset", "side", "strike", "close", "volume", "oi"]])
    d["n"] = gex.contract_flow(d)
    core = gex.session(d, spot, day, exp, True)
    assert np.allclose(full.net_cr, core.net_cr, equal_nan=True)
    t = 30
    s2 = spot.copy()
    s2[t + 1:] = np.nan
    cut = G.day_series(G.clean(o[o.minute <= t], "NIFTY", s2), s2, day, exp, True)
    for c in gex.OUT_COLS:
        assert np.allclose(full[c].values[:t + 1], cut[c].values[:t + 1], equal_nan=True), c


def test_clean_drops_mislabelled_strikes_and_duplicates():
    o, spot, day, exp = lake_day(minutes=range(0, 2))
    bad = o.iloc[:1].copy()
    bad["strike"] = 15000.0                                         # far from spot: another index's row
    dup = o.iloc[:1].copy()
    x = G.clean(pd.concat([o, bad, dup]), "NIFTY", spot)
    assert (x.strike != 15000.0).all() and not x.duplicated(["minute", "offset", "side"]).any()


def test_missing_series_gives_nan_rows():
    r = G.day_series(None, np.full(375, 1.0), date(2024, 1, 1), None, False)
    assert len(r) == 375 and r.net_cr.isna().all()


def test_add_norms_reads_previous_sessions_only():
    rows = []
    for i in range(15):
        rows.append(pd.DataFrame(dict(day=date(2024, 1, 1) + pd.Timedelta(days=i), minute=np.arange(375),
                                      near_net_cr=np.full(375, 2.0 if i < 14 else 6.0), near_s2_cr=1.0)))
    df = G.add_norms(pd.concat(rows, ignore_index=True))
    last = df[df.day == df.day.max()]
    assert np.allclose(last.near_norm, 3.0)                          # 6 / median(|2|) of earlier days
    assert df[df.day == df.day.min()].near_norm.isna().all()


# ------------------------------------------------------------------------------------------------ filters and stats
def frame(nets, gexv, col="near_net_cr", und="NIFTY"):
    days = [date(2023, 3, 1) + pd.Timedelta(days=i) for i in range(len(nets))]
    t = pd.DataFrame(dict(und=und, day=days, net=nets, net_x2=np.array(nets) - 10.0))
    for c in S.GEX_COLS:
        t[c] = np.nan
    t[col] = gexv
    return t, days


def test_missing_gex_is_kept_and_counted():
    f = S.FILTERS[0]                                                # G1: keep S1 < 0
    t, days = frame([100, -50, 30], [-1.0, 2.0, np.nan])
    k = S.kept_mask(f, t)
    assert k.tolist() == [True, False, True]
    e = S.evaluate(f, t, days, twins=False)
    assert e["missing_gex"] == 1 and e["skipped_n"] == 1
    assert e["delta_day"] == pytest.approx(50 / 3)


def test_norm_filters_skip_only_strong_values():
    g2 = next(f for f in S.FILTERS if f.id == "G2")
    g6 = next(f for f in S.FILTERS if f.id == "G6")
    t, _ = frame([1, 1, 1, 1], [1.5, 0.5, -1.5, np.nan], col="near_norm")
    assert S.kept_mask(g2, t).tolist() == [False, True, True, True]
    assert S.kept_mask(g6, t).tolist() == [True, True, False, True]
    g4 = next(f for f in S.FILTERS if f.id == "G4")
    t, _ = frame([1, 1, 1], [1.0, 0.0, np.nan], col="near_spot_above_flip")
    assert S.kept_mask(g4, t).tolist() == [False, True, True]


def test_twin_p_small_when_the_filter_skips_the_worst_days():
    rng = np.random.default_rng(0)
    nets = rng.normal(0, 100, 300)
    gexv = np.where(nets < -80, 1.0, -1.0)                           # skips exactly the big losers
    t, days = frame(nets, gexv)
    p, mean = S.twin_p(t, S.kept_mask(S.FILTERS[0], t), days, n=500, tag="x")
    assert p < 0.01 and mean < 1
    t, days = frame(nets, np.full(300, -1.0))                        # skips nothing: no test
    assert np.isnan(S.twin_p(t, S.kept_mask(S.FILTERS[0], t), days, n=50)[0])


def test_twin_p_uses_the_real_change_when_a_day_is_partly_skipped():
    days = [date(2023, 3, 1) + pd.Timedelta(days=i) for i in range(40)]
    t = pd.DataFrame(dict(und="BANKNIFTY", day=[d for d in days for _ in (0, 1)], net=np.tile([-500.0, 300.0], 40)))
    keep = np.tile([False, True], 40)                    # skips the losing trade of every day, keeps the winner
    p, _ = S.twin_p(t, keep, days, n=500, tag="partial")
    assert p < 0.05                                      # the unit version called this "skip whole days": +200/day lost


def test_metrics_drawdown_and_green_days():
    t, days = frame([100, -300, 50, 0], [0, 0, 0, 0])
    m = S.metrics(t, days + [date(2030, 1, 1)])
    assert m["max_dd"] == -300 and m["worst_day"] == -300 and m["sessions"] == 5
    assert m["green_days_pct"] == pytest.approx(50.0) and m["per_day"] == pytest.approx(-150 / 5)


def test_bh_and_the_joined_family_only_raises_q():
    q = S.bh({"a": 0.01, "b": 0.04, "c": 0.5})
    assert q["a"] == pytest.approx(0.03) and q["b"] == pytest.approx(0.06) and q["c"] == pytest.approx(0.5)
    q2 = S.bh({"a": 0.01, "b": 0.04, "c": 0.5, "x": 1.0, "y": 1.0})
    assert all(q2[k] >= q[k] - 1e-12 for k in q)
    jf = S.joined_family()
    assert sum(1 for k in jf["p"] if k.startswith("R9 ")) == 68


def test_holdout_read_is_refused_twice(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "FLAG", tmp_path / "flag")
    monkeypatch.setattr(S, "HOLDOUT_JSON", tmp_path / "holdout.json")
    (tmp_path / "flag").write_text("x")
    with pytest.raises(SystemExit):
        S.cmd_holdout()


def test_filters_read_only_times_at_or_before_the_decision():
    import r28arms  # noqa: F401  (module imports cleanly; arm rows use these clock rows)
    # ORB / Range Fade decide from the 10:05 bar on (row >= 49); the GEX row is 10:00's (44)
    assert K.CLOCK["10:00"] < 5 * 10 + 4
    # Night buys at 15:20; the GEX is 15:15's; the lottery buys at 14:30, the GEX is 13:45's
    assert K.CLOCK["15:15"] < 15 * 60 + 20 - 555 and K.CLOCK["13:45"] < 14 * 60 + 30 - 555


# ------------------------------------------------------------------------------------------------ describe helpers
def test_orb_break_run_and_fail():
    a = np.full((375, 4), 100.0)
    a[:45, 1], a[:45, 2] = 101.0, 99.0                             # range 99..101 to 10:00
    a[45:, :] = 100.0
    a[49, 3] = 102.0                                                 # 10:00-10:04 bar closes above
    a[50:120, 1] = 104.0                                             # runs 3 points beyond the edge
    r = DS.orb_break(a, 4.0)
    assert r["brk"] == 1 and r["run"] == 1.0 and r["fail"] == 1.0      # back inside at the next 5-min close too
    assert DS.orb_break(a, np.nan)["brk"] == 0
    flat = np.full((375, 4), 100.0)
    assert DS.orb_break(flat, 4.0)["brk"] == 0


def test_describe_bh_handles_nan():
    q = DS.bh([0.01, np.nan, 0.02])
    assert np.isnan(q[1]) and q[0] == pytest.approx(0.02) and q[2] == pytest.approx(0.02)
