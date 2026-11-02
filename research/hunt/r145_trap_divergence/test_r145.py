"""R145 tests: the demand zone, the breakdown, both entries, the mirror, factors 3 and 4, V5's premium lock, the
look-ahead guard and the holdout lock, on hand-built data.

    python -m pytest research/hunt/r145_trap_divergence -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r145 as S  # noqa: E402

R = S.R
N = S.N

# Hand-built 5-minute bars (o, h, l, c), worked out by hand:
#   bar 3's low 1000 is a swing low (k = 3), confirmed at bar 6; bars 4-6 rally to 1013 (>= 1010 = 1000 + 1%), so the
#   zone qualifies; bars 7-12 drift back down; bar 13 closes 998.5 < 999.95 (the breakdown) with a low of 998, below the
#   5 bars before it (lowest 1000.5): a new 30-minute low. Its last minute e = 13*5 + 4 = 69 (10:24).
#   V1 enters at row 70, stop 998 - 0.2 = 997.8. Bar 14 closes 1002 > 1000 (the reclaim): V2 enters at row 75.
BARS = [
    (1010, 1012, 1008, 1010), (1010, 1012, 1008, 1010), (1010, 1012, 1008, 1010),
    (1005, 1006, 1000, 1004),                                   # 3 the swing low (the support)
    (1004, 1008, 1003, 1007), (1007, 1012, 1006, 1011), (1011, 1013, 1009, 1012),   # the rally
    (1012, 1012, 1008, 1009), (1009, 1009, 1005, 1006), (1006, 1007, 1003, 1004),
    (1004, 1005, 1002, 1003), (1003, 1004, 1001, 1002), (1002, 1003, 1000.5, 1001),
    (1001, 1001.5, 998, 998.5),                                 # 13 the breakdown
    (998.5, 1003, 998.2, 1002),                                 # 14 the reclaim (close above 1000)
    (1002, 1006, 1001, 1005),
]


def bars(rows=BARS, days=None):
    a = np.array(rows, dtype=float)
    o, h, l, c = a[:, 0], a[:, 1], a[:, 2], a[:, 3]
    n = len(rows)
    idx = np.arange(n)
    return R.Bars(day=np.zeros(n, int) if days is None else np.array(days), start=idx * 5, end=idx * 5 + 4,
                  o=o, h=h, l=l, c=c)


def test_the_breakdown_of_a_qualified_zone_is_found_with_its_window():
    cen = {}
    ev = S.breakdowns(bars(), cen)
    assert len(ev) == 1
    e = ev[0]
    assert (e["d"], e["t"], e["e"], e["L"], e["bd_low"], e["bd_close"]) == (0, 13, 69, 1000.0, 998.0, 998.5)
    assert cen["zones"] >= 1 and cen["rallied"] >= 1 and cen["breakdowns"] == 1 and cen["in_window"] == 1


def test_entries_next_bar_and_reclaim():
    B = bars()
    ev = S.breakdowns(B)[0]
    assert S.entry_of(B, ev, "next") == (70, pytest.approx(997.8))
    assert S.entry_of(B, ev, "reclaim") == (75, pytest.approx(997.8))
    rows = list(BARS)
    rows[14] = (998.5, 1003, 997.5, 1002)                       # bar 14's low reaches the stop before its reclaim
    B2 = bars(rows)
    assert S.entry_of(B2, S.breakdowns(B2)[0], "reclaim") is None
    rows = list(BARS)
    rows[14] = (998.5, 999.5, 998.2, 999)                       # no close back above 1000 within the bars left
    rows[15] = (999, 999.8, 998.3, 999.5)
    B3 = bars(rows)
    assert S.entry_of(B3, S.breakdowns(B3)[0], "reclaim") is None


def test_a_zone_needs_its_rally_before_its_low_is_taken():
    rows = list(BARS)
    rows[4], rows[5], rows[6] = (1004, 1006, 1003, 1005), (1005, 1007, 1004, 1006), (1006, 1008, 1005, 1007)
    rows[7] = (1007, 1007, 999, 1005)                           # the low is taken (999 < 1000) before a 1% rally
    rows[8] = (1005, 1008, 1005, 1006)                          # (and 999's own zone never rallies 1% to 1008.99)
    assert S.breakdowns(bars(rows)) == []


def test_zones_older_than_four_sessions_are_not_used():
    days_ok = [0] * 7 + [4] * (len(BARS) - 7)
    days_old = [0] * 7 + [5] * (len(BARS) - 7)
    assert len(S.breakdowns(bars(days=days_ok))) == 1
    assert S.breakdowns(bars(days=days_old)) == []


def test_no_new_30_minute_low_or_outside_the_window_is_not_a_setup():
    rows = list(BARS)
    rows[12] = (1002, 1003, 997, 1001)                          # an earlier bar already lower than the breakdown's low
    cen = {}
    assert S.breakdowns(bars(rows), cen) == [] and cen["breakdowns"] == 1 and cen["in_window"] == 0
    B = bars()
    B.end[:] = B.end + 300                                      # the breakdown bar would end after 14:59
    assert S.breakdowns(B) == []


def test_the_mirror_finds_the_bearish_twin():
    up = S.breakdowns(bars())[0]
    from types import SimpleNamespace
    b = bars()
    bear = SimpleNamespace(o=2000 - b.o, h=2000 - b.l, l=2000 - b.h, c=2000 - b.c)   # the market p' = 2000 - p
    mh, ml, mo, mc = R.mirror(bear)[-1]                         # read through R130's mirror (the sell side)
    dn = S.breakdowns(R.Bars(day=b.day, start=b.start, end=b.end, o=mo, h=mh, l=ml, c=mc))
    assert len(dn) == 1
    assert dn[0]["e"] == up["e"] and dn[0]["L"] == pytest.approx(-1000.0)


def test_the_look_ahead_guard_bars_after_the_breakdown_change_nothing():
    full = S.breakdowns(bars())[0]
    cut = S.breakdowns(bars(BARS[:14]))[0]                      # every bar after the breakdown removed
    assert full == cut
    rows = list(BARS)
    rows[14], rows[15] = (998.5, 2000, 0, 1500), (1500, 3000, 1, 2)
    assert S.breakdowns(bars(rows))[0] == full


def test_factor_3_the_put_did_not_make_a_new_high():
    e = 100
    px = np.full(N, np.nan)
    px[e - 29:e - 4] = np.linspace(40, 50, 25)                  # before: max 50 (25 traded minutes)
    px[e - 4:e + 1] = 49.0
    assert S.put_did_not_rise(px, e) is True
    px[e] = 51.0
    assert S.put_did_not_rise(px, e) is False
    px[e] = 50.0                                                # equal to the earlier high: not a new high
    assert S.put_did_not_rise(px, e) is True
    after = px.copy()
    after[e + 1:] = 999.0                                       # minutes after e are never read
    assert S.put_did_not_rise(after, e) is True
    sparse = np.full(N, np.nan)
    sparse[e - 29:e - 20] = 40.0                                # 9 traded minutes before: no data
    sparse[e] = 39.0
    assert S.put_did_not_rise(sparse, e) is None
    assert S.put_did_not_rise(None, e) is None
    quiet = px.copy()
    quiet[e - 4:e + 1] = np.nan                                 # no trade in the breakdown bar: no data
    assert S.put_did_not_rise(quiet, e) is None


def test_factor_4_put_oi_up_two_percent():
    e = 100
    oi = np.full(N, np.nan)
    oi[e - 29] = 1000.0
    oi[e] = 1020.0
    assert S.oi_building(oi, e) is True
    oi[e] = 1019.0
    assert S.oi_building(oi, e) is False
    oi2 = oi.copy()
    oi2[e + 1:] = 1e9                                           # never read
    assert S.oi_building(oi2, e) is False
    oi3 = np.full(N, np.nan)
    oi3[e - 10] = 1000.0                                        # one reading only: no data
    assert S.oi_building(oi3, e) is None
    oi4 = np.full(N, np.nan)
    oi4[e - 40] = 500.0                                         # before the window: not used
    oi4[e - 20], oi4[e - 5] = 1000.0, 1030.0
    assert S.oi_building(oi4, e) is True


def _option(closes: dict[int, float], r0: int = 100) -> np.ndarray:
    a = np.full((N, 5), np.nan)
    a[r0, 0] = 100.0
    for r, c in closes.items():
        a[r, 0] = a[r, 1] = a[r, 2] = a[r, 3] = c
        a[r, 4] = 5000.0
    a[r0 - 5:r0, 4] = 5000.0
    return a


def test_v5_lock_sells_at_60_percent_of_the_best_gain_once_rs_1000_up():
    lot, d = 65, date(2025, 1, 2)
    closes = {100: 101.0, 105: 115.0, 110: 130.0}               # up about Rs 1,950 on 65 at 130
    closes.update({111: 125.0, 112: 121.0, 113: 119.0, 115: 117.0, 120: 90.0})
    tr = S.option_lock_trade(_option(closes), 100, 200, "time", lot, d, "NIFTY")
    assert tr is not None
    net, net2, rr, why = tr
    assert (rr, why) == (115, "lock")                           # the lock (about 118) is first breached at 117
    assert net2 <= net


def test_v5_without_rs_1000_up_holds_to_the_exit_row():
    lot, d = 65, date(2025, 1, 2)
    closes = {100: 101.0, 110: 110.0, 150: 104.0, 190: 102.0}   # at most about Rs 650 up: no lock
    tr = S.option_lock_trade(_option(closes), 100, 180, "stop", lot, d, "NIFTY")
    assert tr[2:] == (150, "stop")                              # the last traded close at or before row 180
    assert S.option_lock_trade(None, 100, 180, "stop", lot, d, "NIFTY") is None


def test_the_lock_is_set_from_earlier_minutes_only():
    lot, d = 65, date(2025, 1, 2)
    # 130 and 117 in consecutive minutes: the lock from 130 is set after 130's minute, so 117 at 111 is a lock exit,
    # but a single minute that jumps up never exits on itself.
    tr = S.option_lock_trade(_option({100: 101.0, 110: 130.0, 111: 117.0}), 100, 200, "time", lot, d, "NIFTY")
    assert tr[2:] == (111, "lock")
    tr2 = S.option_lock_trade(_option({100: 101.0, 110: 130.0}), 100, 200, "time", lot, d, "NIFTY")
    assert tr2[2:] == (110, "time")


def test_claims_split_and_two_proportion():
    races = [dict(f3=True, f4=True, up=True, decided=True)] * 30 + [dict(f3=False, f4=False, up=False, decided=True)] * 30 \
        + [dict(f3=None, f4=True, up=True, decided=False)] * 5
    c = S.claims(races)
    assert c["n_races"] == 65 and c["n_decided"] == 60
    assert c["C1_f3"]["up1"] == 1.0 and c["C1_f3"]["up2"] == 0.0 and c["C1_f3"]["p_two_sided"] < 1e-6
    assert c["C4_both_vs_neither"]["n1"] == 30 and c["C4_both_vs_neither"]["n2"] == 30


def test_account_drawdown_and_worst_month():
    import pandas as pd
    T = pd.DataFrame(dict(day=[date(2025, 1, 2), date(2025, 1, 3), date(2025, 2, 3)], entry=[100, 100, 100],
                          und=["NIFTY"] * 3, net=[1000.0, -3000.0, 500.0]))
    a = S.account(T)
    assert a["max_dd"] == -3000.0 and a["worst_month"] == -2000.0


def test_the_holdout_needs_the_design_results_and_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "RESULTS", tmp_path / "results")
    monkeypatch.setattr(S, "OUT", tmp_path / "out")
    with pytest.raises(R.HoldoutError):
        S.run("holdout")
    (tmp_path / "results").mkdir()
    R.holdout_lock("start", out=tmp_path / "out", results=tmp_path / "results")
    with pytest.raises(R.HoldoutError):
        R.holdout_lock("start", out=tmp_path / "out", results=tmp_path / "results")
