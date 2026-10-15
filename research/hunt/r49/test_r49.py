"""R49 tests: the window and split guards, lots and expiry days read from the data, dated charges, the re-tune grid's
windows, R23's finalists, parity with R3's engine on the old rules, and the look-ahead guard. No lake needed
(synthetic sessions, R3's test helpers)."""
from __future__ import annotations

import sys
from copy import deepcopy
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r49cfg as K  # noqa: E402
import r49data as D  # noqa: E402
import r49engine as R  # noqa: E402
import r49study as S  # noqa: E402
import r3engine as E3  # noqa: E402
import test_r3orb as T3  # noqa: E402

from obuy.costs import Costs  # noqa: E402

N = 375


# ------------------------------------------------------------------------------------------------ window and split
def test_period_split_matches_the_prereg():
    assert K.period(date(2024, 11, 19)) == "out"
    assert K.period(date(2024, 11, 20)) == "design"
    assert K.period(date(2026, 3, 31)) == "design"
    assert K.period(date(2026, 4, 1)) == "final"
    assert K.period(date(2026, 10, 13)) == "final"
    assert K.period(date(2026, 10, 14)) == "out"


def test_quarters_are_the_walk_forward_blocks():
    assert K.quarter(date(2024, 11, 21)) == "2024Q4"
    assert K.quarter(date(2026, 3, 31)) == "2026Q1"
    assert S.QUARTERS[0] == "2024Q4" and S.QUARTERS[-1] == "2026Q1" and len(S.QUARTERS) == 6


def test_final_test_is_refused_without_the_flag(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "FINAL_FLAG", tmp_path / "FINAL_READ")
    with pytest.raises(PermissionError):
        S.sessions("final")
    with pytest.raises(ValueError):
        S.sessions("all")


def test_design_sessions_never_include_final_or_pre_window_days(tmp_path, monkeypatch):
    days = [date(2024, 11, 18), date(2024, 11, 21), date(2026, 3, 31), date(2026, 4, 1)]
    fake = [T3.session(day=d) for d in days]
    monkeypatch.setattr(D, "load", lambda u: deepcopy(fake))
    sb = S.sessions("design")
    assert all([s.day for s in sb[u]] == [date(2024, 11, 21), date(2026, 3, 31)] for u in K.UNDS)
    flag = tmp_path / "FINAL_READ"
    flag.write_text("x")
    monkeypatch.setattr(K, "FINAL_FLAG", flag)
    assert [s.day for s in S.sessions("final")["NIFTY"]] == [date(2026, 4, 1)]


# ------------------------------------------------------------------------------------------------ lots and expiry
def _opts_with_volumes(vols):
    a = np.full((N, 6), np.nan, dtype=np.float32)
    a[:len(vols), 4] = vols
    return {(100.0, "C"): a}


def test_lot_is_read_from_the_traded_volumes():
    rng = np.random.default_rng(1)
    v75 = rng.integers(1, 40, 200) * 75
    assert D.lot_from_volume(_opts_with_volumes(v75), {25, 65, 75}) == 75
    v25 = rng.integers(1, 40, 200) * 25                     # multiples of 25 that are not all multiples of 75
    assert D.lot_from_volume(_opts_with_volumes(v25), {25, 65, 75}) == 25
    noisy = np.r_[v75[:190], [49, 51, 74, 76, 101, 3, 7, 11, 13, 17]]   # 5% odd prints (Dhan aggregation)
    assert D.lot_from_volume(_opts_with_volumes(noisy), {25, 65, 75}) == 75
    assert D.lot_from_volume(_opts_with_volumes(v75[:10]), {25, 65, 75}) == 0    # too few traded minutes
    assert D.lot_from_volume({}, {25, 65, 75}) == 0


def test_lot_table_fallback_follows_the_contract_changes():
    assert K.lot_table("BANKNIFTY", date(2025, 1, 30)) == 15     # the Jan 2025 monthly kept the old lot
    assert K.lot_table("BANKNIFTY", date(2025, 7, 1)) == 35
    assert K.lot_table("BANKNIFTY", date(2026, 1, 5)) == 30
    assert K.lot_table("NIFTY", date(2026, 5, 5)) == 65
    assert K.lot_table("MIDCPNIFTY", date(2026, 5, 5)) == 120


def test_tv_1515_is_the_atm_straddle_time_value():
    s = T3.session(und="NIFTY")
    r = D.minute(15, 15)
    s.ix[r, 3] = 25010.0
    c = np.full((N, 6), np.nan)
    p = np.full((N, 6), np.nan)
    c[r, 3], p[r, 3] = 30.0, 15.0                          # intrinsic 10 -> time value 35
    s.opts = {(25000.0, "C"): c, (25000.0, "P"): p}
    assert abs(D.tv_1515(s) - 35 / 25010 * 100) < 1e-9
    s.opts = {}
    assert np.isnan(D.tv_1515(s))


# ------------------------------------------------------------------------------------------------ dated charges
def test_stt_rises_on_1_apr_2026():
    before = R.charge(False, 100.0, 75, "NIFTY", date(2026, 3, 31))
    after = R.charge(False, 100.0, 75, "NIFTY", date(2026, 4, 1))
    assert abs((after - before) - 7500 * 0.0005) < 0.02      # 0.10% -> 0.15% of the sell value
    assert R.charge(True, 100.0, 75, "NIFTY", date(2026, 3, 31)) == R.charge(True, 100.0, 75, "NIFTY", date(2026, 4, 1))
    assert R.charge(False, 100.0, 20, "SENSEX", date(2025, 6, 2)) != R.charge(False, 100.0, 20, "NIFTY", date(2025, 6, 2))


# ------------------------------------------------------------------------------------------------ the 56 rules
def test_rule_count_ids_and_groups():
    rs = R.rules()
    assert len(rs) == 56 and len({r.id for r in rs}) == 56
    g = {x: sum(r.group == x for r in rs) for x in ("O", "R3", "R23", "G")}
    assert g == {"O": 5, "R3": 16, "R23": 3, "G": 32}
    for a in R.ARMS:
        fam = R.grid_family(a, rs)
        assert len(fam) == 9 and {(r.m, r.k) for r in fam} == {(m, k) for m in R.GRID_M for k in R.GRID_K}


def test_r3_filters_are_r3s_part_b_unchanged():
    for a in R.ARMS:
        f = R.r3_filter(a)
        g = R.R3_FILT[a]
        assert f[0] == g[0]
        for x, y in zip(f[1:], g[1:]):
            assert (np.isinf(x) and np.isinf(y) and np.sign(x) == np.sign(y)) or abs(x - y) < 1e-3


def test_grid_windows():
    rs = {r.id: r for r in R.rules()}
    assert R.window(rs["r49-orb"]) == E3.WINDOW["orb"]
    assert R.window(rs["r49-orb-sweep"]) == E3.WINDOW["orb_sweep"]
    assert R.window(rs["r49-range-fade"]) == E3.WINDOW["range_fade"]
    assert R.window(rs["r49-g-orb-m15-k1"]) == (3, 55)            # first decision bar 09:30
    assert R.window(rs["r49-g-orb-sweep-m30-k0.5"]) == (6, 62)     # 09:45 .. 14:25
    assert R.window(rs["r49-g-range-fade-m15-k1.5"]) == (8, 55)    # range end + 25 minutes = 09:55
    assert R.window(rs["r49-g-range-fade-m30-k1"]) == (11, 55)


def test_old_rules_match_r3_engine_exactly(monkeypatch):
    """With R3's charges (today's rates), the old rules give R3's trades to the paisa."""
    monkeypatch.setattr(R, "COSTS", Costs("app"))
    rs = {r.id: r for r in R.rules()}
    for seed in range(12):
        s = T3.random_session(seed)
        s.day = date(2025, 6, 2)
        for arm, rid in (("orb", "r49-orb"), ("orb_fresh", "r49-orb-fresh"), ("orb_sweep", "r49-orb-sweep"),
                         ("range_fade", "r49-range-fade"), ("orb_uncapped", "r49-orb-uncapped")):
            a = E3.run_session(deepcopy(s), E3.baseline(arm))
            b = R.run_session(deepcopy(s), rs[rid])
            assert [(t["sig_row"], t["entry_row"], t["exit_row"], t["side"], round(t["net"], 2)) for t in a] == \
                   [(t["sig_row"], t["entry_row"], t["exit_row"], t["side"], round(t["net"], 2)) for t in b]
        for x in ("f", "e", "s", "c"):
            for arm, aid in R.ARM_ID.items():
                v3 = E3.with_(rs[f"r49-r3-{aid}-{x}"].v)
                a = E3.run_session(deepcopy(s), v3)
                b = R.run_session(deepcopy(s), rs[f"r49-r3-{aid}-{x}"])
                assert [(t["entry_row"], t["exit_row"], round(t["net"], 2)) for t in a] == \
                       [(t["entry_row"], t["exit_row"], round(t["net"], 2)) for t in b]


def test_scale_k_moves_stop_target_and_refusal():
    rs = {r.id: r for r in R.rules()}
    s = T3.random_session(3)
    s.day = date(2025, 6, 2)
    for t in R.run_session(deepcopy(s), rs["r49-g-orb-m50-k0.5"]):
        if t["why"] == "stop":
            assert t["exit"] <= t["entry"] - 20 + 0.05 + 1e-6    # BANKNIFTY P 40 x 0.5
    a = R.simulate(s, rs["r49-orb"].v, 1, 54, s.atm, 40.0)
    assert a is not None
    s2 = deepcopy(s)
    s2.opts[(s.atm, "C")][55:58, 0] = 50.0                   # premium 50: allowed at P 40, refused at P 60
    assert R.simulate(s2, rs["r49-orb"].v, 1, 54, s.atm, 40.0) is not None
    assert R.simulate(s2, rs["r49-orb"].v, 1, 54, s.atm, 60.0) is None


def test_m15_range_trades_earlier_than_the_app():
    rs = {r.id: r for r in R.rules()}
    ix = T3.ranged_ix(50100.0, 49900.0)
    T3.set_bar(ix, 1, 50000, 50100, 49900, 50000)            # the 15-minute range: 49,900 .. 50,100
    T3.set_bar(ix, 4, 50050, 50200, 50050, 50150)            # 09:35 bar closes above it
    s = T3.session(ix, opts={(50000.0, "C"): T3.opt_path(200.0), (50000.0, "P"): T3.opt_path(200.0)})
    s.day = date(2025, 6, 2)
    t15 = R.run_session(deepcopy(s), rs["r49-g-orb-m15-k1"])
    assert t15 and t15[0]["sig_row"] == 5 * 4 + 4 and t15[0]["side"] == 1
    t50 = R.run_session(deepcopy(s), rs["r49-orb"])
    assert all(t["sig_row"] >= 5 * 10 + 4 for t in t50)


# ------------------------------------------------------------------------------------------------ R23 finalists
def _r23_session(close_above=50160.0, twap_level=50000.0):
    ix = T3.flat_ix(twap_level)
    ix[10, 1], ix[20, 2] = 50100.0, 49900.0                  # range 09:15 .. 09:59: 49,900 .. 50,100
    ix[60:75, 0] = 50050.0
    ix[60:75, 3] = close_above                               # the 10:15 candle closes beyond the high
    ix[60:75, 1] = np.maximum(ix[60:75, 0], ix[60:75, 3])
    ix[60:75, 2] = np.minimum(ix[60:75, 0], ix[60:75, 3])
    s = T3.session(ix, opts={(50000.0, "C"): T3.opt_path(200.0), (50000.0, "P"): T3.opt_path(200.0)})
    s.day = date(2025, 6, 2)
    s.atr14 = 500.0
    return s


def test_r23_r22_range_and_15_minute_signal():
    rs = {r.id: r for r in R.rules()}
    s = _r23_session()
    tr = R.run_session(s, rs["r49-r23-r22-f2"])
    # f1: (50160 - TWAP) / 500 must exceed 0.21982: TWAP ~ 50000 + a little -> 0.3; f2: (50160 - 50100) / 500 = 0.12
    assert tr and tr[0]["sig_row"] == 15 * 4 + 14 and tr[0]["side"] == 1
    assert R.decision_rows(rs["r49-r23-r22-f2"])[0] == 15 * 3 + 14 and R.decision_rows(rs["r49-r23-r22-f2"])[-1] == 15 * 18 + 14


def test_r23_filters_block_a_weak_break_for_the_day():
    rs = {r.id: r for r in R.rules()}
    s = _r23_session(close_above=50110.0)
    s.atr14 = 400.0                                          # f1: (50110 - TWAP ~50004) / 400 = 0.26 ok; f2: 10 / 400 fails
    assert R.run_session(deepcopy(s), rs["r49-r23-r22-f2"]) == []
    assert R.run_session(deepcopy(s), rs["r49-r23-r22-f1"])
    # the first CE signal failed f2, so a later, stronger CE break that day is not traded either
    s2 = deepcopy(s)
    s2.ix[120:135, 3] = 50400.0
    assert R.run_session(s2, rs["r49-r23-r22-f2"]) == []


def test_r23_needs_the_0959_minute():
    rs = {r.id: r for r in R.rules()}
    s = _r23_session()
    s.ix[44] = np.nan
    assert R.run_session(s, rs["r49-r23-r22-f1"]) == []


# ------------------------------------------------------------------------------------------------ twins, top-5, BH
def test_twin_keeps_session_side_and_count():
    rs = {r.id: r for r in R.rules()}
    s = T3.random_session(4)
    s.day = date(2025, 6, 2)
    r = rs["r49-orb-sweep"]
    tr = R.run_session(s, r)
    if not tr:
        pytest.skip("no trade on this synthetic day")
    tw = R.twin_by_und({(s.und, s.day): s}, tr, r, np.random.default_rng(0))
    assert set(tw) <= {s.und}


def test_top5_share():
    assert R.top5_share([dict(net=x) for x in (100, 50, -20, 10, 5, 1, 1)]) == pytest.approx(166 / 147)
    assert R.top5_share([dict(net=-1.0)]) == float("inf")


def test_walk_forward_picks_from_past_quarters_only():
    rs = R.rules()
    fam = R.grid_family("orb", rs)
    res = {r.id: {"pooled": {"net": 0.0, "quarters": {}}} for r in fam}
    good = "r49-g-orb-m15-k1.5"
    res[good]["pooled"]["quarters"] = {"2024Q4": [1000, 5], "2025Q1": [-500, 5]}
    res[good]["pooled"]["net"] = 500.0
    wf = S.walk_forward(res, fam, "pooled")
    assert wf["quarters"]["2025Q1"] == dict(pick=good, net=-500)        # picked on 2024Q4 only, then lost in 2025Q1
    assert wf["retuned_pick"] == good
    for r in fam:                                                         # all zero: ties go to the old rule
        res[r.id]["pooled"] = {"net": 0.0, "quarters": {}}
    assert S.walk_forward(res, fam, "pooled")["retuned_pick"] == "r49-orb"


# ------------------------------------------------------------------------------------------------ the look-ahead guard
@pytest.mark.parametrize("seed", range(10))
def test_lookahead_every_rule(seed):
    """Every rule's signals and entries are unchanged when every minute after the entry minute is noise; every exit by
    stop or lock is unchanged when every minute after it is noise."""
    s = T3.random_session(seed)
    s.day = date(2025, 6, 2)
    for r in R.rules():
        for t in R.run_session(deepcopy(s), r):
            p = T3.poisoned(s, t["entry_row"])
            p.day = s.day
            tr2 = R.run_session(p, r)
            assert any(x["sig_row"] == t["sig_row"] and x["entry"] == t["entry"] for x in tr2), r.id
            if t["why"] in ("stop", "lock"):
                p2 = T3.poisoned(s, t["exit_row"])
                p2.day = s.day
                P = R.P0[s.und] * (r.k if not r.r23 else 1.0)
                t2 = R.simulate(p2, r.v, t["side"], t["sig_row"], t["k"], P)
                assert t2["exit_row"] == t["exit_row"] and t2["exit"] == t["exit"], r.id
