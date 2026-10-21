"""R92 tests: the clock and slots, the 257 pre-registered cells, windows, the arm loop's modes (ANY / FIRST / APP /
ATTR), the cap, cooldown and refusals, day types, exit times, the twins' exclusion, the statistics, the holdout lock,
parity with R3 / R49's engine on the app rules, and the look-ahead guard. No lake needed (synthetic sessions, R3's
test helpers)."""
from __future__ import annotations

import sys
from collections import Counter
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r92cfg as K  # noqa: E402
import r92data as D  # noqa: E402
import r92engine as G  # noqa: E402
import r92study as S  # noqa: E402
import r3engine as E3  # noqa: E402
import r49engine as R49  # noqa: E402
import test_r3orb as T3  # noqa: E402

N = 375


def rsess(seed, und="BANKNIFTY", day=date(2024, 1, 10), expiry=False):
    s = T3.random_session(seed)
    s.und, s.day, s.expiry = und, day, expiry
    s.opts = {k: a.astype(np.float64) for k, a in s.opts.items()}
    return s


def table_from(net_value=100.0, refused=(), exit_row=None):
    net = np.full((2, G.NB), net_value)
    xr = np.full((2, G.NB), -1, dtype=np.int32)
    for b in range(G.NB):
        xr[:, b] = exit_row if exit_row is not None else min(5 * b + 6, 354)
    for j, b in refused:
        xr[j, b] = -1
    return G.Table(net, xr, np.zeros((2, G.NB)), np.zeros((2, G.NB), dtype=np.int8))


# ------------------------------------------------------------------------------------------------ split and lock
def test_period_split_and_unseen_block():
    assert K.period(date(2021, 7, 30)) == "out"
    assert K.period(date(2021, 8, 2)) == "design"
    assert K.period(date(2026, 3, 31)) == "design"
    assert K.period(date(2026, 4, 1)) == "holdout"
    assert K.period(date(2026, 10, 19)) == "holdout"
    assert K.period(date(2026, 10, 20)) == "out"
    assert K.UNSEEN_START == date(2026, 10, 13)


def test_holdout_is_locked_without_the_flag(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "HOLDOUT_FLAG", tmp_path / "HOLDOUT_READ")
    with pytest.raises(PermissionError):
        D.sessions("NIFTY", "holdout")
    with pytest.raises(PermissionError):
        S.load_records("holdout")
    with pytest.raises(ValueError):
        D.sessions("NIFTY", "final")


def test_holdout_refuses_a_second_read(tmp_path, monkeypatch):
    flag = tmp_path / "HOLDOUT_READ"
    flag.write_text("read\n")
    monkeypatch.setattr(K, "HOLDOUT_FLAG", flag)
    with pytest.raises(SystemExit):
        S.holdout()


def test_r3_hour_indices_and_fresh_indices_are_disjoint():
    assert not set(K.R3_HOUR_UNDS) & set(K.FRESH_UNDS)
    assert set(K.R3_HOUR_UNDS) | set(K.FRESH_UNDS) == set(K.UNDS)


# ------------------------------------------------------------------------------------------------ clock and slots
def test_entry_minute_is_the_signal_bar_close():
    assert G.hm(G.entry_min(10)) == "10:10"          # bar 10 starts 10:05, closes 10:10
    assert G.hm(G.entry_min(55)) == "13:55"
    assert G.hm(G.entry_min(62)) == "14:30"
    assert G.hm(G.entry_min(15)) == "10:35"


def test_hour_slots_match_r3s_hour_row_clock():
    """R3's anatomy: hour = 9 + (15 + t_sig) // 60 with t_sig = 5b + 5 (the signal bar's close)."""
    for h, sl in zip(range(10, 15), G.HOURS):
        bs = set(G.bars_in(*sl))
        r3 = {b for b in range(10, 63) if 9 + (15 + 5 * b + 5) // 60 == h}
        assert bs & set(range(10, 63)) == r3


def test_slots_are_disjoint_and_cover_the_window():
    for grp in (G.HALF, G.QUARTER):
        seen = Counter(b for sl in grp for b in G.bars_in(*sl))
        assert all(v == 1 for v in seen.values())
        assert set(range(10, 63)) <= set(seen)


def test_windows_match_the_app_and_r49():
    for arm in G.ARMS:
        assert G.window(arm) == E3.WINDOW[arm]
    for m in (15, 30, 50):
        for arm in G.ARMS:
            rule = R49.Rule("x", "G", E3.baseline(arm), m=m)
            assert G.window(arm, m) == R49.window(rule)
    assert G.window("orb", ext=True) == (10, 62)
    assert G.window("orb_sweep", last_entry="13:15") == (10, 47)
    assert G.window("range_fade", 75) == (20, 55)


# ------------------------------------------------------------------------------------------------ the cells
def test_257_cells_as_preregistered():
    cs = G.cells()
    assert len(cs) == 257
    fam = Counter(c.family for c in cs)
    assert fam == {"T": 166, "R": 15, "D": 36, "X": 9, "C": 30, "H12": 1}
    t = Counter((c.arm, c.mode) for c in cs if c.family == "T")
    assert t == {("orb", "ANY"): 32, ("orb", "FIRST"): 14, ("orb_fresh", "ANY"): 32, ("orb_sweep", "ANY"): 32,
                 ("orb_sweep", "FIRST"): 14, ("range_fade", "ANY"): 29, ("range_fade", "FIRST"): 13}
    ids = [c.id for c in cs]
    assert len(set(ids)) == len(ids) and all(i.startswith("r92-") for i in ids)


def test_ext_labels_and_fade_slots():
    cs = {c.id: c for c in G.cells()}
    assert cs["r92-t-orb-any-h-1400-1430"].ext and "EXT" in cs["r92-t-orb-any-h-1400-1430"].label
    assert not cs["r92-t-sweep-any-h-1400-1430"].ext
    assert "r92-t-fade-any-30-1000-1029" not in cs
    assert "r92-t-fade-any-15-1030-1044" in cs
    assert not cs["r92-t-orb-any-h-1200-1259"].ext
    h12 = cs["r92-h12-orb-1200-1259"]
    assert h12.mode == "ATTR" and h12.unds == K.FRESH_UNDS and h12.win() == (10, 55)


def test_exit_cells_cut_the_last_entry_15_minutes_before():
    cs = {c.id: c for c in G.cells()}
    assert cs["r92-x-orb-1330"].win() == (10, 47)            # last entry 13:15
    assert cs["r92-x-sweep-1430"].win() == (10, 59)          # last entry 14:15
    assert cs["r92-x-orb-1510"].win() == (10, 55)


# ------------------------------------------------------------------------------------------------ the arm loop
def always_beyond(first=10):
    sig = np.zeros(G.NB, dtype=np.int8)
    sig[first:] = 1
    return sig


def test_any_mode_ignores_signals_outside_the_slot_without_using_the_cap():
    sig = always_beyond()
    tab = table_from()
    app = G.run_arm(sig, tab, 10, 55)
    assert app == [(1, 10)]
    any12 = G.run_arm(sig, tab, 10, 55, set(G.bars_in("12:00", "12:59")))
    assert any12 == [(1, 32)]                                # the first allowed bar, 12:00


def test_first_mode_keeps_only_the_days_first_trade_in_its_slot():
    c10 = G.Cell("a", "T", "orb", "FIRST", ("10:00", "10:59"), ext=True)
    c12 = G.Cell("b", "T", "orb", "FIRST", ("12:00", "12:59"), ext=True)
    sig = always_beyond()
    sig[40] = -1                                             # a PE signal at 12:40
    tab = table_from()
    class S_: expiry = False
    assert G.cell_trades(c10, S_, sig, tab, {}) == [(1, 10)]
    assert G.cell_trades(c12, S_, sig, tab, {}) == []


def test_attr_mode_is_the_app_trades_in_the_slot():
    sig = np.zeros(G.NB, dtype=np.int8)
    sig[12], sig[35] = 1, -1                                 # CE 10:20, PE 12:15
    tab = table_from()
    c = G.Cell("h", "H12", "orb", "ATTR", ("12:00", "12:59"))
    class S_: expiry = False
    assert G.cell_trades(c, S_, sig, tab, {}) == [(-1, 35)]


def test_cap_one_a_side_two_a_day_cooldown_and_refusals():
    sig = np.zeros(G.NB, dtype=np.int8)
    sig[10:20], sig[20:40] = 1, -1
    tab = table_from(exit_row=0)
    tab.exit_row[0, 10] = 120                                # first CE trade exits at row 120 (bar 24)
    tab.exit_row[1, :] = 300
    tab.exit_row[1, 25] = -1                                 # the PE at bar 25 is refused: no cap used
    tr = G.run_arm(sig, tab, 10, 55)
    assert tr == [(1, 10), (-1, 26)]                         # bars 11-24 cooled down, 25 refused, 26 taken


def test_max_two_a_day():
    sig = np.zeros(G.NB, dtype=np.int8)
    sig[10], sig[11] = 1, -1
    tab = table_from(exit_row=0)
    tab.exit_row[:, :] = 52                                  # exits inside bar 10
    tr = G.run_arm(sig, tab, 10, 55)
    assert len(tr) == 2


def test_day_filter_expiry_and_types():
    c = G.Cell("e", "C", "orb", "ANY", ("10:00", "10:59"), day=("expiry", True))
    sig, tab = always_beyond(), table_from()
    class Exp: expiry = True
    class Non: expiry = False
    assert G.cell_trades(c, Exp, sig, tab, {}) == [(1, 10)]
    assert G.cell_trades(c, Non, sig, tab, {}) == []
    d = G.Cell("w", "D", "orb", "APP", day=("weekday", 2))
    assert G.cell_trades(d, Non, sig, tab, {"weekday": 2}) == [(1, 10)]
    assert G.cell_trades(d, Non, sig, tab, {"weekday": 3}) == []
    assert G.cell_trades(d, Non, sig, tab, None) == []


# ------------------------------------------------------------------------------------------------ day types
def test_day_types_dte_weekday_month_position():
    days = [date(2026, 9, d) for d in (1, 2, 3, 4, 7, 8, 9, 10, 11, 14, 15, 16, 17, 18, 21, 22, 23, 24, 25, 28, 29, 30)]
    days += [date(2026, 10, d) for d in (1, 5, 6, 7, 8)]
    exp = {date(2026, 9, 8), date(2026, 9, 29), date(2026, 10, 6)}
    cal = [(d, d in exp) for d in days]
    dt = G.day_types(cal, last_day=date(2026, 10, 8))
    assert dt[date(2026, 9, 8)]["dte"] == 0
    assert dt[date(2026, 9, 7)]["dte"] == 1
    assert dt[date(2026, 9, 4)]["dte"] == 2
    assert dt[date(2026, 9, 1)]["dte"] == 3                  # 3+ (5 sessions)
    assert dt[date(2026, 9, 14)]["dte"] == 3
    assert dt[date(2026, 10, 6)]["dte"] == 0
    assert dt[date(2026, 10, 7)]["dte"] is None              # no expiry after it in the data, 1 session left: unknown
    assert dt[date(2026, 10, 8)]["dte"] is None
    assert dt[date(2026, 9, 1)]["weekday"] == 1
    assert dt[date(2026, 9, 3)]["mpos"] == "first3" and dt[date(2026, 9, 4)]["mpos"] == "mid"
    assert dt[date(2026, 9, 28)]["mpos"] == "last3" and dt[date(2026, 9, 25)]["mpos"] == "mid"
    assert dt[date(2026, 10, 8)]["mpos"] == "mid"            # October is still open on 8 Oct: no last3


def test_day_types_read_dates_only():
    """The calendar the day types read carries no prices."""
    cal = [(date(2026, 9, 1), False), (date(2026, 9, 2), True)]
    dt = G.day_types(cal, last_day=date(2026, 9, 30))
    assert set(dt[date(2026, 9, 1)]) == {"weekday", "dte", "mpos"}


# ------------------------------------------------------------------------------------------------ one trade
@pytest.mark.parametrize("seed", range(6))
def test_simulate_matches_r49_at_the_apps_square_off(seed):
    s = rsess(seed, expiry=bool(seed % 2))
    s.lot = 15
    for arm, tm in (("orb", 1.0), ("orb_sweep", 2.0)):
        v = E3.baseline(arm)
        for side in (1, -1):
            for b in range(10, 63, 3):
                t1 = G.simulate(s, tm, side, 5 * b + 4, 40.0)
                t2 = R49.simulate(s, v, side, 5 * b + 4, s.atm, 40.0)
                assert (t1 is None) == (t2 is None)
                if t1:
                    assert t1["exit_row"] == t2["exit_row"] and abs(t1["net"] - t2["net"]) < 1e-9


def test_earlier_square_off_and_expiry():
    s = rsess(3)
    for side in (1, -1):
        t = G.simulate(s, 1.0, side, 5 * 20 + 4, 40.0, G.SQ["13:30"])
        assert t is not None and t["exit_row"] <= G.SQ["13:30"]
    s.expiry = True
    for x in ("14:30", "15:10"):
        t = G.simulate(s, 1.0, 1, 5 * 20 + 4, 40.0, G.SQ[x])
        assert t is not None and t["exit_row"] <= min(G.SQ[x], E3.SQ_EXPIRY)
    assert G.simulate(s, 1.0, 1, 5 * 60 + 4, 1.0, G.SQ["13:30"]) is None   # entry after the square-off


def test_flat_option_squares_off_and_points_follow_the_index():
    ix = T3.ranged_ix()
    ix[60:, :] += 0.0
    k = T3.D.atm_strike(ix[T3.D.SPOT_ROW, 3], 100)
    s = T3.session(ix, opts={(k, "C"): T3.opt_path(200.0)})
    s.opts = {kk: a.astype(np.float64) for kk, a in s.opts.items()}
    t = G.simulate(s, 1.0, 1, 5 * 12 + 4, 40.0)
    assert t["why"] == 4 and t["exit_row"] == E3.SQ_ROW and t["net"] < 0      # costs make a flat trade lose
    assert t["pts"] == 0.0


@pytest.mark.parametrize("seed", range(4))
def test_app_arms_match_r49s_run_session(seed):
    """run_arm on the outcome table = R49's own loop for the app rules with 1 entry a side and 2 a day."""
    s = rsess(seed + 10)
    s.lot = 15
    for arm in G.ARMS:
        for m in (15, 50, 75):
            v = E3.Variant("x", arm, side_cap=1, max_entries=2, tgt_mult=G.TMULT[arm])
            ref = R49.run_session(s, R49.Rule("x", "G", v, m=m))
            tab = G.outcome_table(s, G.TMULT[arm])
            mine = G.run_arm(G.signals(s, arm, m), tab, *G.window(arm, m))
            assert [(t["side"], t["sig_row"] // 5) for t in ref] == mine
            assert all(abs(t["net"] - tab.net[0 if t["side"] > 0 else 1, t["sig_row"] // 5]) < 1e-9 for t in ref)


# ------------------------------------------------------------------------------------------------ twins and stats
def test_twin_pool_is_outside_the_slot():
    c = G.Cell("a", "T", "orb", "ANY", ("12:00", "12:59"))
    pool = set(G.twin_pool(c))
    assert not pool & set(G.bars_in("12:00", "12:59"))
    assert pool == set(range(10, 56)) - set(G.bars_in("12:00", "12:59"))
    h = G.Cell("h", "H12", "orb", "ATTR", ("12:00", "12:59"))
    assert set(G.twin_pool(h)) == pool
    d = G.Cell("d", "D", "orb", "APP", day=("weekday", 0))
    assert set(G.twin_pool(d)) == set(range(10, 56))


def test_twins_never_use_the_own_bar_and_skip_refusals():
    net = np.zeros((2, G.NB))
    net[0, 20] = 1e6                                         # the own bar would be a jackpot
    net[0, 21] = 5.0
    ok = np.zeros((2, G.NB), dtype=bool)
    ok[0, 20] = ok[0, 21] = True
    tot = G.twins([(net, ok)], [(0, 0, 20, True)], np.arange(10, 56), 200, np.random.default_rng(1))
    assert tot.max() <= 5.0 and (tot == 5.0).any() and (tot == 0.0).any()


def test_twins_are_reproducible():
    net = np.random.default_rng(0).normal(size=(2, G.NB))
    ok = np.ones((2, G.NB), dtype=bool)
    a = G.twins([(net, ok)], [(0, 1, 30, True)], np.arange(10, 56), 50, np.random.default_rng(7))
    b = G.twins([(net, ok)], [(0, 1, 30, True)], np.arange(10, 56), 50, np.random.default_rng(7))
    assert np.array_equal(a, b)


def test_money_p_and_bh():
    assert G.money_p(np.array([100.0, 120, 90, 110, 105])) < 0.01
    assert G.money_p(np.array([-100.0, -120, -90, -110])) > 0.99
    assert G.money_p(np.array([5.0])) == 1.0
    assert np.allclose(G.bh([0.01, 0.04, 0.03, 0.5]), [0.04, 0.16 / 3, 0.16 / 3, 0.5])


def test_design_gates():
    r = dict(family="T", unds=list(K.UNDS), net=5000.0, trades=150, q=0.05, twin_p=0.01, net_ex_top5=100.0,
             per_year={"2022": [1, 1], "2023": [1, 1], "2024": [-1, 1], "2025": [1, 1]},
             per_und={u: [1, 10] for u in ("NIFTY", "BANKNIFTY", "SENSEX")} | {"FINNIFTY": [-1, 1]},
             worst_month_und={"NIFTY": ("2022-01", -12000)})
    assert S.design_gates(r)["pass"]
    assert not S.design_gates(dict(r, worst_month_und={"NIFTY": ("2022-01", -13001)}))["pass"]
    assert not S.design_gates(dict(r, trades=99))["pass"]
    assert not S.design_gates(dict(r, per_year={"2022": [1, 1], "2023": [-1, 1], "2024": [-1, 1], "2025": [1, 1]}))["pass"]


def test_nested_walk_forward_picks_on_past_years_only():
    a = dict(id="a", label="a", per_year={"2022": [100, 1], "2023": [-50, 1], "2024": [0, 0], "2025": [0, 0]})
    b = dict(id="b", label="b", per_year={"2022": [10, 1], "2023": [500, 1], "2024": [7, 1], "2025": [0, 0]})
    wf = {w["year"]: w for w in S.nested_wf([a, b])}
    assert wf[2023]["pick"] == "a" and wf[2023]["test_net"] == -50
    assert wf[2024]["pick"] == "b" and wf[2024]["test_net"] == 7


# ------------------------------------------------------------------------------------------------ the look-ahead guard
@pytest.mark.parametrize("seed", range(12))
def test_lookahead_signals_slots_and_entries(seed):
    """Every decision (each arm's signal for every range length, slot membership, the entry price) is unchanged when
    every minute after it is noise."""
    s = rsess(seed + 200)
    for arm in G.ARMS:
        for m in (15, 50, 75):
            sig = G.signals(s, arm, m)
            f, last = G.window(arm, m, ext=True)
            for b in range(f, last + 1, 2):
                p = T3.poisoned(s, 5 * b + 4)
                assert G.signals(p, arm, m)[b] == sig[b]
    for b in range(10, 63, 4):
        t = G.simulate(s, 1.0, 1, 5 * b + 4, 40.0)
        if t is None:
            continue
        p = T3.poisoned(s, t["entry_row"])
        p.opts = {k: a.astype(np.float64) for k, a in p.opts.items()}
        t2 = G.simulate(p, 1.0, 1, 5 * b + 4, 40.0)
        assert t2 is not None and t2["entry"] == t["entry"] and t2["entry_row"] == t["entry_row"]


@pytest.mark.parametrize("seed", range(4))
def test_exits_do_not_peek(seed):
    s = rsess(seed + 300)
    for b in range(10, 60, 5):
        for x in ("13:30", "14:30", "15:10"):
            t = G.simulate(s, 1.0, -1, 5 * b + 4, 40.0, G.SQ[x])
            if t is None or t["why"] not in (0, 1):
                continue
            p = T3.poisoned(s, t["exit_row"])
            p.opts = {k: a.astype(np.float64) for k, a in p.opts.items()}
            t2 = G.simulate(p, 1.0, -1, 5 * b + 4, 40.0, G.SQ[x])
            assert t2["exit_row"] == t["exit_row"] and t2["exit"] == t["exit"]
