"""R164 tests: synthetic tick-zone rows only (no recording is read).

    python -m pytest research/hunt/r164_donchian_orderflow/tests -q
"""
from __future__ import annotations

import gzip
import json
from datetime import date

import numpy as np
import pytest

from research.hunt.r164_donchian_orderflow import coverage as C
from research.hunt.r164_donchian_orderflow import flow as F
from research.hunt.r164_donchian_orderflow import study as S

T0 = 1_760_000_000  # an epoch second


def row(k, s, ltp, v=None, oi=None, bq=None, aq=None, bid=None, ask=None):
    r = {"t": "T", "k": k, "s": s, "ltp": ltp, "v": v, "oi": oi}
    if bq is not None:
        r["b"] = [[bid if bid is not None else ltp - 0.05, bq, 1]] + [[ltp - 0.1 * i, 0, 0] for i in range(2, 6)]
    if aq is not None:
        r["a"] = [[ask if ask is not None else ltp + 0.05, aq, 1]] + [[ltp + 0.1 * i, 0, 0] for i in range(2, 6)]
    return r


def series(rows):
    ss, _ = F.series_from_rows(rows)
    return ss


# ================================================================================================ the reads
def test_depth_imbalance_mean_over_window_and_missing():
    rows = [row(1, T0 + i, 100, bq=300, aq=100) for i in range(60)]          # (300-100)/400 = 0.5
    s = series(rows)[1]
    assert s.depth_imbalance(T0 + 59, 60) == pytest.approx(0.5)
    assert s.depth_imbalance(T0 - 10, 60) is None                            # before any row
    nodepth = series([row(2, T0 + i, 100) for i in range(5)])[2]
    assert nodepth.depth_imbalance(T0 + 4, 60) is None


def test_tick_rule_delta_ratio_signs_volume():
    # price rises each second, 10 traded each second -> all buys
    up = series([row(1, T0 + i, 100 + i, v=10 * i) for i in range(30)])[1]
    assert up.delta_ratio(T0 + 29, 60) == pytest.approx(1.0)
    dn = series([row(1, T0 + i, 100 - i, v=10 * i) for i in range(30)])[1]
    assert dn.delta_ratio(T0 + 29, 60) == pytest.approx(-1.0)
    # unchanged price keeps the previous side; a falling cumulative volume (a reset) counts as 0
    flat = series([row(1, T0, 100, v=0), row(1, T0 + 1, 101, v=10), row(1, T0 + 2, 101, v=20), row(1, T0 + 3, 100, v=5)])[1]
    assert flat.delta_ratio(T0 + 3, 60) == pytest.approx(1.0)
    assert series([row(1, T0, 100, v=None)])[1].delta_ratio(T0, 60) is None


def test_oi_change_and_missing():
    s = series([row(1, T0, 100, oi=1000), row(1, T0 + 900, 100, oi=990)])[1]
    assert s.oi_change(T0 + 900, 900) == pytest.approx(-0.01)
    assert s.oi_change(T0 + 100, 900) is None                                 # nothing 15 minutes before


def test_large_prints_need_200_earlier_sizes_and_use_earlier_minutes_only():
    rows, v = [], 0
    for i in range(400):
        v += 10
        rows.append(row(1, T0 - (T0 % 60) + i, 100 + (i % 2) * 0.05, v=v))
    s = series(rows)[1]
    base = T0 - (T0 % 60)
    assert s.large_net(base + 100, 300) is None                               # fewer than 200 earlier sizes
    # a large sell print at second 380 (price down)
    rows2 = list(rows)
    rows2[380] = row(1, base + 380, 99, v=rows[380]["v"] + 1000)              # 1,010 traded in that second
    for j in range(381, 400):
        rows2[j] = dict(rows2[j], v=rows2[j]["v"] + 1000)                     # later seconds trade 10 each, as before
    s2 = series(rows2)[1]
    assert s2.large_net(base + 399, 300) == -1
    assert s2.large_net(base + 379, 300) == 0                                 # not yet printed


# ================================================================================================ look-ahead guard
def _day(n=1200, k_f=1, k_o=2):
    rng = np.random.default_rng(0)
    rows, vf, vo, pf, po = [], 0, 0, 20000.0, 150.0
    for i in range(n):
        pf += rng.normal(); po += rng.normal() * 0.3
        vf += int(rng.integers(0, 50)); vo += int(rng.integers(0, 500))
        rows.append(row(k_f, T0 + i, round(pf, 1), v=vf, oi=100000 + i, bq=int(rng.integers(1, 900)), aq=int(rng.integers(1, 900))))
        rows.append(row(k_o, T0 + i, round(po, 2), v=vo, oi=5000, bq=int(rng.integers(1, 900)), aq=int(rng.integers(1, 900))))
    return rows


def test_lookahead_guard_entry_reads_ignore_rows_after_t0():
    rows = _day()
    t0 = T0 + 1000
    ss = series(rows)
    a = F.entry_reads(ss[1], ss[2], t0, +1)
    # scramble everything after t0
    later = [dict(r, ltp=r["ltp"] * 3, v=(r["v"] or 0) * 5, oi=1) if r["s"] > t0 else r for r in rows]
    later = [dict(r, b=[[1, 1, 1]], a=[[2, 99999, 1]]) if r["s"] > t0 else r for r in later]
    ss2 = series(later)
    b = F.entry_reads(ss2[1], ss2[2], t0, +1)
    assert a == b


def test_lookahead_guard_exit_decision_ignores_the_future_and_sells_later():
    rows = []
    for i in range(400):
        # the option's book turns against the trade from second 100
        bq, aq = (100, 900) if i >= 100 else (500, 500)
        rows.append(row(2, T0 + i, 150, v=i, bq=bq, aq=aq, bid=149.5, ask=150.5))
    ss = series(rows)
    e = F.flip_exit("X2", None, ss[2], T0, T0 + 399, +1)
    assert e is not None
    decided, sold, px = e
    assert decided == T0 + 129                                                # 30 consecutive seconds from 100
    assert sold > decided and px == pytest.approx(149.5)
    # changing anything after the decision (but the sale row) does not move the decision
    rows2 = [dict(r, a=[[150.5, 1, 1]]) if r["s"] > decided + 1 else r for r in rows]
    assert F.flip_exit("X2", None, series(rows2)[2], T0, T0 + 399, +1)[0] == decided


def test_exit_never_later_than_own_exit_and_gaps_restart_the_count():
    rows = []
    for i in range(300):
        if 120 <= i < 125:
            continue                                                          # a 5-second gap
        bq, aq = (100, 900) if i >= 100 else (500, 500)
        rows.append(row(2, T0 + i, 150, bq=bq, aq=aq))
    ss = series(rows)
    e = F.flip_exit("X2", None, ss[2], T0, T0 + 299, +1)
    assert e[0] == T0 + 125 + 29                                              # restarted after the gap
    assert F.flip_exit("X2", None, ss[2], T0, T0 + 140, +1) is None           # own exit comes first
    assert F.flip_exit("X1", None, ss[2], T0, T0 + 299, +1) is None           # no future: no opinion


def test_x1_and_x3_on_future_selling():
    rows = []
    for i in range(400):
        pf = 20000 - (i if i >= 100 else 0)                                    # the future falls from 100: sells
        rows.append(row(1, T0 + i, pf, v=10 * i))
        bq, aq = (100, 900) if i >= 200 else (500, 500)
        rows.append(row(2, T0 + i, 150, v=i, bq=bq, aq=aq))
    ss = series(rows)
    x1 = F.flip_exit("X1", ss[1], ss[2], T0, T0 + 399, +1)
    x3 = F.flip_exit("X3", ss[1], ss[2], T0, T0 + 399, +1)
    assert x1 is not None and x3 is not None and x3[0] > x1[0]
    assert F.flip_exit("X1", ss[1], ss[2], T0, T0 + 399, -1) is None          # a put: the flow agrees


def test_entry_skips_missing_read_keeps_the_trade():
    sk = F.entry_skips({"E1": None, "E2": -0.5, "E3": -0.2, "E4": 0.3, "E5": -2, "E6": None})
    assert sk == {"E1": False, "E2": True, "E3": True, "E4": False, "E5": True, "E6": False}


def test_read_ticks_refuses_sealed_files_and_reads_gzip(tmp_path):
    with pytest.raises(ValueError):
        F.read_ticks(tmp_path / "live-2026-10-13.irlive")
    p = tmp_path / "ticks.jsonl.gz"
    with gzip.open(p, "wt") as fh:
        fh.write(json.dumps({"t": "H"}) + "\n")
        fh.write(json.dumps({"t": "I", "s": T0, "list": [[1, "NIFTY26NOVFUT", "NIFTY", "FUT", None, "2026-11-24"]]}) + "\n")
        fh.write(json.dumps(row(1, T0, 100, v=1)) + "\n")
        fh.write(json.dumps(row(1, T0, 101, v=2)) + "\n")                      # the same second twice: the last kept
    ss, inst = F.read_ticks(p)
    assert inst[1][3] == "FUT" and len(ss[1].s) == 1 and ss[1].ltp[0] == 101


# ================================================================================================ the judging
def _trades(n, wins):
    out = []
    for i in range(n):
        x = 130.0 if i < wins else 85.0
        out.append(S.Trade("R72", "NIFTY", 1, date(2026, 10, 15), T0 + i * 4000, T0 + i * 4000 + 1800, 100.0, x, 65))
    return out


def test_net_includes_dated_charges():
    t = _trades(1, 1)[0]
    assert S.net(t) < (130 - 100) * 65
    assert S.net(t, spread_mult=1.5, half_spread=1.0) < S.net(t)
    assert S.net(t, spread_mult=1.5) == pytest.approx(S.net(t))               # no spread known: no stress cost


def test_evaluate_entry_perfect_filter_passes_gates_but_small_sample_is_inconclusive():
    tr = _trades(100, 40)
    skip = np.array([i >= 40 and i < 80 for i in range(100)])                  # skips 40 losers
    r = S.evaluate_entry(tr, skip, np.random.default_rng(1), n_random=200)
    assert r["g1_win_up_5pts"] and r["g2_net_not_lower"] and r["g3_beats_random"]
    assert not r["enough"] and S.verdict(r, 0.01) == "inconclusive (too small)"
    big = _trades(200, 80)
    skip = np.array([80 <= i < 140 for i in range(200)])
    r2 = S.evaluate_entry(big, skip, np.random.default_rng(1), n_random=200)
    assert r2["enough"] and S.verdict(r2, 0.01) == "pass" and S.verdict(r2, 0.5) == "fail"
    assert r2["net_stress"]["rule"] >= r2["net_stress"]["base"]


def test_evaluate_entry_random_filter_does_not_beat_random():
    tr = _trades(200, 90)
    rng = np.random.default_rng(5)
    skip = np.zeros(200, bool); skip[rng.choice(200, 50, replace=False)] = True
    r = S.evaluate_entry(tr, skip, np.random.default_rng(2), n_random=300)
    assert r["p_random"] > 0.05 or not r["g1_win_up_5pts"]


def test_evaluate_exit_counts_changed_trades_and_random_twins():
    tr = _trades(200, 80)
    early = [(t.entry_s + 300, 99.0) if i >= 80 and i < 140 else None for i, t in enumerate(tr)]  # small loss instead of -15%
    r = S.evaluate_exit(tr, early, lambda t, s: 99.0, np.random.default_rng(3), n_random=200)
    assert r["changed"] == 60 and r["rule"]["net"] > r["base"]["net"]
    assert r["rule"]["win"] == r["base"]["win"]                               # still losers: no win gain
    assert not r["g1_win_up_5pts"]


def test_bh_counts_earlier_tests():
    q = S.bh([0.001, 0.02, None, 0.5], m_total=10)
    assert q[2] is None and q[0] == pytest.approx(0.01) and q[1] == pytest.approx(0.1) and q[3] == pytest.approx(1.0)
    assert S.bh([None]) == [None]


def test_trades_from_diag_joins_orders_for_the_symbol_and_drops_other_indices():
    diag = {"day": "2026-10-15",
            "arms": [{"key": "r72_breadth_donchian", "trades": [
                {"account": "paper", "right": "CE", "entry_at": "10:05:01", "entry": 100, "exit_at": "10:35:01", "exit": 110, "open": False},
                {"account": "paper", "right": "PE", "entry_at": "11:00:00", "entry": 90, "exit_at": "11:30:00", "exit": 80, "open": False},
                {"account": "paper", "right": "PE", "entry_at": "12:00:00", "entry": 90, "exit_at": "12:30:00", "exit": 80, "open": False},
                {"account": "paper", "right": "CE", "entry_at": "13:00:00", "entry": 90, "exit_at": None, "exit": None, "open": True}]},
                {"key": "orb", "trades": [{"account": "paper", "right": "CE", "entry_at": "10:05:00", "entry": 1, "exit_at": "10:06:00", "exit": 2}]}],
            "orders": [{"at": "10:05:00", "account": "paper", "side": "BUY", "symbol": "NIFTY26O2025000CE"},
                       {"at": "11:00:02", "account": "paper", "side": "BUY", "symbol": "FINNIFTY26OCT27000PE"},
                       {"at": "09:00:00", "account": "paper", "side": "BUY", "symbol": "BANKNIFTY26OCT56000PE"}]}
    out = S.trades_from_diag(diag, {"NIFTY": 65, "BANKNIFTY": 30})
    assert len(out) == 2
    (t, sym), (none_t, none_sym) = out
    assert t.arm == "R72" and t.index == "NIFTY" and t.direction == 1 and sym == "NIFTY26O2025000CE"
    assert t.exit_s - t.entry_s == 1800 and t.qty == 65
    assert none_t is None                                                     # 12:00 put: no order within 120 s


# ================================================================================================ coverage arithmetic
def test_sessions_skip_weekends_and_holidays():
    assert C.sessions(date(2026, 10, 12), date(2026, 11, 11)) == 21
    assert C.sessions(date(2026, 10, 17), date(2026, 10, 18)) == 0


def test_trades_needed_grows_with_stricter_alpha_and_bigger_skip():
    a = C.trades_needed(0.46, skip=0.3)
    assert C.trades_needed(0.46, skip=0.3, alpha=0.001) > a
    assert C.trades_needed(0.46, skip=0.5) > a
    with pytest.raises(ValueError):
        C.trades_needed(0.46, gain=0.3, skip=0.2)
    assert C.expected_covered(133, 10, share=0.5) == pytest.approx(5.0)


def test_stress_nets_are_reported_and_cost_more_with_a_spread():
    from dataclasses import replace
    tr = [replace(t, half_spread=2.0) for t in _trades(200, 80)]
    skip = np.array([80 <= i < 140 for i in range(200)])
    r = S.evaluate_entry(tr, skip, np.random.default_rng(1), n_random=50)
    assert r["net_stress"]["base"] < r["base"]["net"] and r["net_stress"]["rule"] < r["rule"]["net"]
    early = [(t.entry_s + 300, 99.0) if 80 <= i < 140 else None for i, t in enumerate(tr)]
    x = S.evaluate_exit(tr, early, lambda t, s: 99.0, np.random.default_rng(1), n_random=50)
    assert x["net_stress"]["rule"] < x["rule"]["net"] and x["g2_net_not_lower"]
