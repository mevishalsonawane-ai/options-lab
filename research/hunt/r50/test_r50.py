"""R50 tests: dates and lots, the expiry rule, the engine's fills and exits on synthetic days, charges by date, metrics,
the walk-forward, and the look-ahead guard on real days (skipped when the lake cache is absent).

    python -m pytest research/hunt/r50/test_r50.py -q
"""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r50cfg as K  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r50data as DD  # noqa: E402
import r50lib as L  # noqa: E402
import r50study as S  # noqa: E402

N = D.N


# ------------------------------------------------------------------------------------------------ dates and lots
def test_window_and_split():
    assert K.period(date(2024, 11, 19)) is None          # before NSE's weekly-expiry change: never traded
    assert K.period(date(2024, 11, 20)) == "design"
    assert K.period(date(2026, 3, 31)) == "design"
    assert K.period(date(2026, 4, 1)) == "final"
    assert K.period(date(2026, 10, 13)) == "final"
    assert K.period(date(2026, 10, 14)) is None
    assert K.block(date(2024, 11, 20)) == "B0" and K.block(date(2025, 4, 1)) == "B1"
    assert K.block(date(2026, 3, 31)) == "B4" and K.block(date(2026, 4, 1)) is None


def test_lots_in_force():
    assert K.lot("NIFTY", date(2024, 11, 21)) == 25
    assert K.lot("NIFTY", date(2024, 11, 28)) == 75
    assert K.lot("NIFTY", date(2026, 1, 6)) == 65
    assert K.lot("BANKNIFTY", date(2025, 3, 27)) == 30
    assert K.lot("BANKNIFTY", date(2025, 7, 31)) == 35
    assert K.lot("BANKNIFTY", date(2026, 1, 27)) == 30
    assert K.lot("MIDCPNIFTY", date(2025, 7, 28)) == 140
    assert K.lot("MIDCPNIFTY", date(2026, 2, 23)) == 120
    assert K.lot("FINNIFTY", date(2026, 2, 24)) == 60
    assert K.lot("SENSEX", date(2025, 5, 6)) == 20


def test_sizing_modes():
    assert L.lots_for(5.0, 75, 5000, "floor") == 13
    assert L.lots_for(100.0, 75, 5000, "floor") == 0        # one lot over budget: skipped
    assert L.lots_for(100.0, 75, 2000, "min1") == 1         # R26 / R34 / R36: at least one lot
    assert L.lots_for(100.0, 75, 2000, "skip") == 0         # R35
    assert L.lots_for(20.0, 75, 2000, "skip") == 1
    assert L.lots_for(0.0, 75, 2000, "min1") == 0


# ------------------------------------------------------------------------------------------------ expiry days
def test_expiry_rule_skips_missing_and_counts_last():
    idx = [date(2026, 3, d) for d in (26, 27, 30, 31)] + [date(2026, 4, 1), date(2026, 4, 2)]
    tv = pd.Series([2.0, 1.5, 0.1, np.nan, 3.0, 0.4], index=idx)
    out = DD.expiry_days_with_last(tv)
    assert date(2026, 3, 30) in out                       # compared with 1 Apr (31 Mar has no 15:15 value)
    assert date(2026, 4, 2) in out                        # the last session, its own time value < 0.6%
    assert date(2026, 3, 27) not in out
    tv2 = pd.Series([0.1, 2.0, 0.9], index=idx[:3])
    assert DD.expiry_days_with_last(tv2) == [date(2026, 3, 26)]   # a last session at 0.9% is not an expiry


# ------------------------------------------------------------------------------------------------ synthetic days
def make_day(path, d=date(2025, 6, 5), und="NIFTY", lot=75, vol=100.0, k=25100.0, side="C", spot=25000.0):
    """One option whose 1-minute closes follow `path` (dict row -> price; flat between), open = previous close."""
    ix = np.full((N, 4), spot)
    a = np.full((N, 7), np.nan)
    rows = sorted(path)
    p = path[rows[0]]
    prev = p
    for r in range(rows[0], N):
        if r in path:
            p = path[r]
        a[r, D.O] = prev
        a[r, D.H] = max(prev, p)
        a[r, D.L] = min(prev, p)
        a[r, D.C] = p
        a[r, D.V] = vol * lot
        a[r, D.OI] = 1000.0
        prev = p
    day = D.Day(d, und, ix, {(k, side): a}, np.full(N, 14.0), spot, 14.0, lot, None, 100.0)
    day._clean = True
    return day


T0 = R.close_row(13, 30)


def run(day, exit_, budget=5000.0, size="floor", ref=None, sgn=1, k=25100.0):
    a = day.opts[(k, "C" if sgn > 0 else "P")]
    ref = float(a[T0, D.C]) if ref is None else ref
    return L.simulate(day, k, sgn, T0, ref, budget, exit_, size)


def test_next_minute_fill_with_spread():
    day = make_day({0: 4.0})
    tr = run(day, "hold")
    assert tr.fill_row == T0 + 1                          # the signal minute's close, filled on the next minute
    assert tr.entry == pytest.approx(4.05, abs=0.011)     # open 4.00 + 5 bps (half-even to the paisa) + 1 tick
    assert tr.exits[-1][0] == L.EXIT_ROW and tr.exits[-1][3] == "1505"
    assert tr.net < 0                                     # flat price: spread and charges lose


def test_entry_never_chases():
    day = make_day({0: 4.0, T0 + 1: 6.0})                 # gaps 50% above the reference on the next minutes
    day.opts[(25100.0, "C")][T0 + 1:T0 + 4, [D.O, D.L]] = 6.0
    assert run(day, "hold", ref=4.0) is None


def test_s50_stop_sells_at_next_open_gap_worse():
    day = make_day({0: 10.0, T0 + 5: 4.9, T0 + 6: 2.0})
    tr = run(day, "s50")
    r, q, px, why = tr.exits[-1]
    assert why == "stop" and r == T0 + 6
    assert px < 4.9 and px == pytest.approx(L.R.sell_px(4.9, 100.0), abs=0.01)   # the next open (= prior close)


def test_hold_has_no_stop():
    day = make_day({0: 10.0, T0 + 5: 1.0})
    tr = run(day, "hold")
    assert tr.exits[-1][3] == "1505"


def test_e2_target_needs_confirmation():
    day = make_day({0: 5.0})
    a = day.opts[(25100.0, "C")]
    a[T0 + 10, D.H] = 30.0                                # a single freak print, close stays 5
    tr = run(day, "E2")
    assert tr.exits[-1][3] == "1505"
    day = make_day({0: 5.0, T0 + 10: 25.0})               # a real move: 5x the fill, closes there
    tr = run(day, "E2")
    assert tr.exits[0][3] == "target" and len(tr.exits) == 1
    assert tr.exits[0][2] == pytest.approx(R.floor_tick(4 * tr.entry))


def test_e3_half_then_lock():
    day = make_day({0: 5.0, T0 + 10: 16.0, T0 + 20: 9.0})
    tr = run(day, "E3")
    whys = [x[3] for x in tr.exits]
    assert whys[0] == "target" and whys[-1] == "lock"     # half at 2x; best 3x+ locks 2x; the close at 9 sells
    assert tr.exits[0][1] + tr.exits[-1][1] == tr.qty


def test_tr25_trail():
    day = make_day({0: 5.0, T0 + 10: 10.0, T0 + 20: 7.4})
    tr = run(day, "tr25")
    assert tr.exits[-1][3] == "trail" and tr.exits[-1][0] == T0 + 21
    day = make_day({0: 5.0, T0 + 10: 3.7})                # 25% below the fill: the trail is also the first stop
    assert run(day, "tr25").exits[-1][3] == "trail"


def test_app_exits_half_at_5x_and_stop():
    day = make_day({0: 2.0, T0 + 10: 11.0})
    tr = run(day, "app")
    assert tr.exits[0][3] == "target" and tr.exits[0][1] < tr.qty
    day = make_day({0: 2.0, T0 + 10: 0.75})
    assert run(day, "app").exits[-1][3] == "stop"


def test_worthless_exit_pays_no_sell_charges():
    day = make_day({0: 2.0, T0 + 10: 0.0})
    day.opts[(25100.0, "C")][T0 + 10:, :4] = 0.0
    tr = run(day, "hold")
    assert tr.exits[-1][2] == 0.0
    assert tr.charges == pytest.approx(L.charge(True, tr.entry, tr.qty, "NIFTY", day.day))


def test_dated_charges():
    before = L.charge(False, 100.0, 750, "NIFTY", date(2026, 3, 31))
    after = L.charge(False, 100.0, 750, "NIFTY", date(2026, 4, 1))
    assert after - before == pytest.approx(75000 * 0.0005, abs=0.02)      # STT 0.1% -> 0.15% of the sell premium
    assert L.charge(False, 100.0, 200, "SENSEX", date(2025, 6, 5)) != L.charge(False, 100.0, 200, "NIFTY",
                                                                               date(2025, 6, 5))   # BSE rates


def test_spread_stress_costs_more():
    day = make_day({0: 3.0, T0 + 20: 6.0}, vol=1.0)
    a = run(day, "hold")
    b = L.simulate(day, 25100.0, 1, T0, 3.0, 5000.0, "hold", "floor", 2.0)
    assert b.net < a.net


# ------------------------------------------------------------------------------------------------ metrics, twins, WF
class _T:
    def __init__(self, net, d, peak=1.0, xm=0.0):
        self.net, self.day, self.peak_mult, self.best_exit_mult, self.risked = net, d, peak, xm, 1000.0


def test_metrics_lottery_view():
    ts = [_T(10000, date(2025, 1, 2), 12, 11), _T(-1000, date(2025, 1, 2)), _T(-2000, date(2025, 1, 9)),
          _T(500, date(2025, 1, 16), 5.5, 1)]
    m = L.metrics(ts, 10)
    assert m["net"] == 7500 and m["trades"] == 4 and m["win"] == 50.0
    assert m["top1"] == pytest.approx(10000 / 7500 * 100, abs=1) and m["ex1"] == -2500
    assert m["ex5"] == 0 and m["ex3"] == -2000
    assert m["hero5"] == 2 and m["hero10"] == 1 and m["banked5"] == 1
    assert m["worst_day"] == -2000 and m["max_dd"] == 2000 and m["per_day"] == 750
    assert L.metrics([], 5)["net"] == 0.0


def test_max_dd():
    assert L.max_dd(np.array([100.0, -50, -80, 200, -10])) == 130
    assert L.max_dd(np.array([-100.0])) == 100


def test_p_value():
    tw = np.array([1.0, 2, 3, 4])
    assert L.p_value(3.0, tw) == pytest.approx(3 / 5)
    assert L.p_value(10.0, tw) == pytest.approx(1 / 5)


def test_walk_forward_reads_earlier_blocks_only():
    bl = [b for b, _, _ in K.BLOCKS]
    a = {"m": {"net": 0}, "blocks": dict(zip(bl, [100, -50, -50, -50, -50]))}
    b = {"m": {"net": 1}, "blocks": dict(zip(bl, [0, 60, 60, -200, 81]))}
    wf = S.walk_forward({"a": a, "b": b})
    assert wf["picks"]["B1"] == "a"                        # chosen on B0 only, before B1 is seen
    assert wf["picks"]["B2"] == "b"                        # B0..B1: a 50, b 60
    assert wf["picks"]["B3"] == "b" and wf["picks"]["B4"] == "a"     # B0..B3: a -50, b -80
    assert wf["wf_net"] == -50 + 60 - 200 - 50
    assert wf["chosen"] == "b"


def test_gates():
    r = {"m": {"net": 100, "trades": 50, "ex5": 10}, "p": 0.01, "q": 0.05}
    assert S.gates(r, 1.0)["pass"]
    assert not S.gates(r, -1.0)["pass"]
    r2 = {"m": {"net": 100, "trades": 30, "ex5": 10}, "p": 0.01, "q": 0.05}
    g = S.gates(r2, 1.0)
    assert not g["pass"] and g["too_few"]


# ------------------------------------------------------------------------------------------------ look-ahead (real days)
def _real_days():
    if not DD.days_path("NIFTY").exists():
        pytest.skip("lake cache absent")
    out = []
    for u in ("NIFTY", "SENSEX", "BANKNIFTY"):
        out += DD.load(u, "design")[:12]
    return out


@pytest.mark.parametrize("rid", ["O-H0", "O-V4", "O-L1", "O-P", "O-B1", "O-S3", "O-S5", "O-K1", "O-T1", "O-E821"])
def test_no_look_ahead(rid):
    import r50rules as RR
    rs = RR.BY_ID[rid].with_(unds=K.UNDS)
    seen = 0
    for day in _real_days():
        sig = L.signals(day, rs)
        if not sig:
            continue
        s = sig[0]
        for cut in (L.L26.masked(day, s.row), L.L26.poisoned(day, s.row)):
            again = L.signals(cut, rs)
            assert again and (again[0].row, again[0].side, again[0].strike) == (s.row, s.side, s.strike), (rid, day.day)
        seen += 1
    if rid in ("O-H0", "O-L1", "O-E821", "O-K1", "O-T1"):
        assert seen > 0
