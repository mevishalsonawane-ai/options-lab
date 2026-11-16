"""R188 tests (PREREG section 6). Synthetic bars only, except the parity tests, which read results/parity.json."""
from __future__ import annotations

import json
import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r188cfg as C  # noqa: E402
import r188sim as S  # noqa: E402

D = date(2026, 6, 10)          # a Wednesday, not an expiry in these tests
U = "NIFTY"
LOT = C.LOT[U]                  # 65


def bars(prices, start=0, vol=10000.0, oi=None):
    """(375, 6) option bars: open = high = low = close = price from row `start` (flat minutes), unless a list of
    (o, h, l, c) tuples is given."""
    a = np.full((C.N, 6), np.nan)
    for k, p in enumerate(prices):
        r = start + k
        o, h, l_, c = p if isinstance(p, tuple) else (p, p, p, p)
        a[r, :5] = (o, h, l_, c, vol)
        a[r, 5] = np.nan if oi is None else oi[k]
    return a


def flat_after(a, r, px):
    """Fill every row after r up to the square-off with a flat price."""
    for k in range(r + 1, C.N):
        if not np.isfinite(a[k, 0]):
            a[k, :5] = (px, px, px, px, 10000.0)
    return a


# ------------------------------------------------------------------------------------------------ settings
def test_bh_count_and_q():
    assert len(C.TESTS) == 23 and C.M_TOTAL == 4925
    q = S.bh_q([1 / 501, 0.5])
    assert q[0] == pytest.approx(1 / 501 * 4925) or q[0] == 1.0
    assert all(0 <= x <= 1 for x in q)
    # the smallest possible q with 500 twins and 23 tests is above 0.10 (PREREG: no lever can pass gate 4)
    assert (1 / 501) * 4925 / 23 > 0.10


def test_periods():
    assert C.period_of(date(2023, 1, 2)) == "design"
    assert C.period_of(date(2026, 3, 20)) is None          # embargo
    assert C.period_of(date(2026, 4, 1)) == "final"
    assert C.period_of(date(2026, 10, 20)) is None
    assert C.period_of(date(2026, 10, 21)) == "new"
    assert C.APP_SQ == C.row(15, 14) and C.RES_SQ == C.row(15, 19)


def test_step_lock_levels():
    assert S.step_lock(999.0) is None
    assert S.step_lock(1000.0) == 500.0
    assert S.step_lock(1499.0) == 500.0
    assert S.step_lock(1500.0) == 1000.0
    assert S.step_lock(2600.0) == 2000.0


def test_variants_and_combo():
    assert S.variant("base").ex == S.Ex()
    assert S.variant("LK500").ex.lock_from == 500.0
    assert S.variant("FT3PO").skips == ("FT3PO",)
    assert S.variant("NTHR", nthr=100.0).nthr == 100.0 and S.variant("BE10", nthr=100.0).nthr == 0.0
    v = S.combine("COMBO", ["L500", "VIX313", "NTHR"], 50.0)
    assert v.ex.l500 and v.skips == ("VIX313",) and v.nthr == 50.0 and not v.ex.be10


# ------------------------------------------------------------------------------------------------ S5 exit
def test_s5_square_off_app_vs_research():
    t = C.row(9, 19)
    a = flat_after(bars([100.0] * 3, start=t + 1), t + 3, 100.0)
    a[C.APP_SQ, 3] = 101.0
    a[C.RES_SQ, 3] = 103.0
    app = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex())
    res = S.s5_exit(a, t, C.RES_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex(sq=C.RES_SQ))
    assert app["exit"] == C.APP_SQ and res["exit"] == C.RES_SQ and res["net"] > app["net"]


def test_s5_stop_and_st1500():
    t = C.row(9, 19)
    # falls steadily: -Rs 1,500 is reached before -Rs 2,000
    path = [100.0 - 0.5 * k for k in range(100)]
    a = flat_after(bars(path, start=t + 1), t + 100, path[-1])
    base = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex())
    st = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex(st1500=True))
    assert base["why"] == "stop" and st["why"] == "stop"
    assert st["exit"] < base["exit"]
    assert -1700 < st["net"] < -1400 and -2200 < base["net"] < -1900
    # a -Rs 1,000 cell is unchanged by ST1500
    b1 = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 1000.0, S.Ex())
    s1 = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 1000.0, S.Ex(st1500=True))
    assert b1 == s1


def up_then_down(t, top=130.0, end=95.0):
    up = list(np.linspace(100.0, top, 30))
    down = list(np.linspace(top, end, 40))
    return flat_after(bars(up + down, start=t + 1), t + 70, end)


def test_s5_trail_lock_and_lk500():
    t = C.row(9, 19)
    a = up_then_down(t)
    base = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex())
    assert base["why"] == "trail" and base["net"] > 0
    # a small run (peak about +Rs 700): no lock at +1,000; LK500 locks it
    b = up_then_down(t, top=111.5, end=95.0)
    x = S.s5_exit(b, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex())
    y = S.s5_exit(b, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex(lock_from=500.0))
    assert x["why"] != "trail" and y["why"] == "trail" and y["net"] > x["net"]


def test_s5_be10_lbe1000_l500():
    t = C.row(9, 19)
    # up 12% (about +Rs 700 on 65), then down below the entry: BE10 sells near Rs 0; base holds to its stop
    path = list(np.linspace(100.0, 112.0, 10)) + list(np.linspace(112.0, 60.0, 60))
    a = flat_after(bars(path, start=t + 1), t + 70, 60.0)
    base = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex())
    be = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex(be10=True))
    assert be["why"] == "be10" and abs(be["net"]) < 150 and base["net"] < -1500
    lb = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex(lbe1000=True))
    assert lb["why"] == "stop"                      # never +1,000: LBE1000 does nothing
    # up to +Rs 1,600ish, then down: L500 locks +1,000 before the 60% trail (which keeps about +960)
    path = list(np.linspace(100.0, 126.0, 26)) + list(np.linspace(126.0, 90.0, 60))
    b = flat_after(bars(path, start=t + 1), t + 86, 90.0)
    l5 = S.s5_exit(b, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex(l500=True))
    assert l5["why"] == "l500" and 850 < l5["net"] < 1100


def test_s5_idx_lock():
    t = C.row(9, 19)
    path = list(np.linspace(100.0, 120.0, 20)) + [120.0] * 60
    a = flat_after(bars(path, start=t + 1), t + 80, 120.0)
    ixo = np.full(C.N, 1000.0)
    ixc = np.full(C.N, 1005.0)
    ixc[t + 30] = 999.0                                # the index closes back below the fill minute's open
    v = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex(idx=True), ixc, ixo)
    assert v["why"] == "idx" and v["exit"] == t + 31
    base = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, S.Ex(), ixc, ixo)
    assert base["why"] == "close"


def test_s5_look_ahead_guard():
    """The exit at minute e does not change when every bar after e is replaced by noise."""
    t = C.row(9, 24)
    rng = np.random.default_rng(1)
    for ex in (S.Ex(), S.Ex(be10=True), S.Ex(l500=True), S.Ex(lbe1000=True), S.Ex(lock_from=500.0), S.Ex(st1500=True)):
        a = up_then_down(t)
        r1 = S.s5_exit(a, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, ex)
        b = a.copy()
        e = r1["exit"]
        b[e + 1:, :4] = rng.uniform(1, 300, size=(C.N - e - 1, 4))
        r2 = S.s5_exit(b, t, C.APP_SQ, LOT, D, U, 1000.0, 2000.0, ex)
        assert r1 == r2


# ------------------------------------------------------------------------------------------------ CISD exit
def cisd_setup(r0, n=80):
    mh = np.full(C.N, 1010.0)
    ml = np.full(C.N, 1000.0)
    ixc = (np.full(C.N, 1005.0), 1002.0)
    return mh, ml, ixc


def test_cisd_index_stop_and_lock():
    r0 = C.row(10, 0)
    a = up_then_down(r0 - 1)
    mh, ml, ixc = cisd_setup(r0)
    base = S.cisd_exit(a, r0, 990.0, mh, ml, S.Ex(), LOT, D, U, ixc)
    assert base["why"] == "lock" and base["net"] > 0
    ml2 = ml.copy()
    ml2[r0 + 3] = 985.0
    st = S.cisd_exit(a, r0, 990.0, mh, ml2, S.Ex(), LOT, D, U, ixc)
    assert st["why"] == "istop" and st["exit"] == r0 + 3


def test_cisd_st1500_and_square_off():
    r0 = C.row(10, 0)
    path = [100.0 - 0.5 * k for k in range(150)]
    a = flat_after(bars(path, start=r0), r0 + 149, path[-1])
    mh, ml, ixc = cisd_setup(r0)
    base = S.cisd_exit(a, r0, 900.0, mh, ml, S.Ex(), LOT, D, U, ixc)
    st = S.cisd_exit(a, r0, 900.0, mh, ml, S.Ex(st1500=True), LOT, D, U, ixc)
    assert base["why"] == "time" and base["exit"] == C.APP_SQ
    assert st["why"] == "pstop" and -1800 < st["net"] < -1400
    res = S.cisd_exit(a, r0, 900.0, mh, ml, S.Ex(sq=C.RES_SQ), LOT, D, U, ixc)
    assert res["exit"] == C.RES_SQ


def test_cisd_levers_and_look_ahead():
    r0 = C.row(10, 0)
    rng = np.random.default_rng(2)
    path = list(np.linspace(100.0, 126.0, 26)) + list(np.linspace(126.0, 90.0, 60))
    a = flat_after(bars(path, start=r0), r0 + 85, 90.0)
    mh, ml, ixc = cisd_setup(r0)
    l5 = S.cisd_exit(a, r0, 900.0, mh, ml, S.Ex(l500=True), LOT, D, U, ixc)
    assert l5["why"] in ("l500", "lock")
    lk = S.cisd_exit(a, r0, 900.0, mh, ml, S.Ex(lock_from=500.0), LOT, D, U, ixc)
    assert lk["exit"] <= S.cisd_exit(a, r0, 900.0, mh, ml, S.Ex(), LOT, D, U, ixc)["exit"]
    for ex in (S.Ex(), S.Ex(be10=True), S.Ex(l500=True), S.Ex(lbe1000=True), S.Ex(idx=True), S.Ex(st1500=True)):
        r1 = S.cisd_exit(a, r0, 900.0, mh, ml, ex, LOT, D, U, ixc)
        b = a.copy()
        e = r1["exit"]
        b[e + 1:, :4] = rng.uniform(1, 300, size=(C.N - e - 1, 4))
        assert S.cisd_exit(b, r0, 900.0, mh, ml, ex, LOT, D, U, ixc) == r1


def test_cisd_idx():
    r0 = C.row(10, 0)
    path = list(np.linspace(100.0, 120.0, 20)) + [120.0] * 60
    a = flat_after(bars(path, start=r0), r0 + 79, 120.0)
    mh, ml, _ = cisd_setup(r0)
    closes = np.full(C.N, 1005.0)
    closes[r0 + 30] = 1001.0
    v = S.cisd_exit(a, r0, 900.0, mh, ml, S.Ex(idx=True), LOT, D, U, (closes, 1002.0))
    assert v["why"] == "idx" and v["exit"] == r0 + 31


def test_no_price_at_entry():
    a = bars([100.0], start=200)
    assert S.s5_exit(a, 10, C.APP_SQ, LOT, D, U, 1000.0, 1000.0, S.Ex()) is None
    mh, ml, ixc = cisd_setup(11)
    assert S.cisd_exit(a, 11, 900.0, mh, ml, S.Ex(), LOT, D, U, ixc) is None


# ------------------------------------------------------------------------------------------------ filters
def test_long_unwinding():
    a = bars([100.0] * 20, start=0, oi=[1000.0] * 20)
    assert S.long_unwinding(a, 15) is False
    b = bars(list(np.linspace(100, 90, 20)), start=0, oi=list(np.linspace(1000, 900, 20)))
    assert S.long_unwinding(b, 19) is True
    c = bars(list(np.linspace(100, 90, 20)), start=0, oi=list(np.linspace(900, 1000, 20)))
    assert S.long_unwinding(c, 19) is False
    assert S.long_unwinding(bars([100.0] * 20, start=0), 19) is None          # no OI: unknown, never skips
    # look-ahead: rows after the decision never matter
    d = b.copy()
    d[20:, :] = 1.0
    assert S.long_unwinding(d, 19) is True


def test_vix_change_and_skip():
    v = np.full((2, C.N), np.nan)
    v[0, :] = 15.0
    v[1, :10] = 14.0
    v[1, 10:] = 30.0                                  # later rows are never read
    ch = S.vix_change(v, 1, 5)
    assert ch == pytest.approx(14 / 15 - 1)
    var = S.variant("VIX313")
    assert S.skip_by(var, dict(vix_chg=ch)) is True
    assert S.skip_by(var, dict(vix_chg=-0.02)) is False
    assert S.skip_by(var, dict(vix_chg=math.nan)) is False
    assert S.skip_by(S.variant("EXP"), dict(expiry=True)) and not S.skip_by(S.variant("EXP"), dict(expiry=False))
    assert S.skip_by(S.variant("ADX20"), dict(adx=12.0)) and not S.skip_by(S.variant("ADX20"), dict(adx=math.nan))
    assert not S.skip_by(S.variant("base"), dict(adx=1.0, vix_chg=-0.5, expiry=True, unwind=True))


class G:
    def __init__(self, o, h, l_, c):
        self.o, self.h, self.l, self.c = o, h, l_, c


def test_adx_trend_vs_chop_and_look_ahead():
    k = 4
    t = np.arange(k * C.N, dtype=float).reshape(k, C.N)
    trend = 1000 + t * 0.5
    g = G(trend - 0.1, trend + 0.3, trend - 0.3, trend)
    a1 = S.adx_at(g, 3, C.row(10, 0))
    rng = np.random.default_rng(3)
    chop = 1000 + rng.normal(0, 1, size=(k, C.N))
    g2 = G(chop, chop + 0.5, chop - 0.5, chop)
    a2 = S.adx_at(g2, 3, C.row(10, 0))
    assert a1 > 40 and a2 < a1
    g3 = G(*(x.copy() for x in (g.o, g.h, g.l, g.c)))
    for x in (g3.o, g3.h, g3.l, g3.c):
        x[3, C.row(10, 0):] = 5.0
    assert S.adx_at(g3, 3, C.row(10, 0)) == pytest.approx(a1)
    assert math.isnan(S.adx_at(g, 0, 10))


# ------------------------------------------------------------------------------------------------ portfolios
class FakeStore:
    """One index, one day, synthetic bars (busy-hold test)."""

    def __init__(self, a, day):
        n = C.N
        c = np.full((1, n), 1000.0)
        self.g = {U: G(c.copy(), c + 1, c - 1, c.copy())}
        self.g[U].days = [day]
        self.pos = {U: {day: 0}}
        self.bars = {U: {("WEEK", day, "P", 1000.0): a, ("WEEK", day, "C", 1000.0): a}}
        self.wexp = {U: set()}
        self.mexp = {U: set()}

    def feats(self, u, i, dec, a, entry_row):
        return dict(unwind=None, expiry=False, vix_chg=-0.10, adx=math.nan)


def test_s5_skipped_pick_holds_the_index():
    day = date(2026, 6, 10)
    a = flat_after(bars([100.0] * 5, start=C.row(9, 20)), C.row(9, 24), 100.0)
    st = FakeStore(a, day)
    picks = pd.DataFrame([dict(und=U, day=day, row=C.row(9, 19), j=1, score=500.0),
                          dict(und=U, day=day, row=C.row(9, 24), j=1, score=500.0)])
    base = S.s5_run(picks, st, S.variant("base"))
    assert len(base) == 1                             # the first holds to the square-off
    vix = S.s5_run(picks, st, S.variant("VIX313"))
    assert len(vix) == 1 and bool(vix.skipped.iloc[0])  # skipped, and the second pick is still blocked


def test_cisd_refused_setup_holds_the_index():
    day = date(2026, 6, 10)
    r0 = C.row(10, 0)
    a = flat_after(bars([100.0] * 5, start=r0), r0 + 4, 100.0)
    st = FakeStore(a, day)
    cands = pd.DataFrame([dict(und=U, day=day, entry=r0, side=-1, why="ok", stop=-1100.0, strike=1000.0),
                          dict(und=U, day=day, entry=r0 + 30, side=-1, why="ok", stop=-1100.0, strike=1000.0)])
    views = {(U, day, S.R132_view_row(r0)): (0.0, -10.0)}       # the put's EV is negative: refused
    T = S.cisd_run(cands, st, views, S.variant("base"))
    assert len(T) == 1 and not T.kept.iloc[0]          # the refused setup still blocks the second


def test_view_row():
    assert S.R132_view_row(C.row(9, 19)) is None
    assert S.R132_view_row(C.row(9, 20)) == C.row(9, 19)
    assert S.R132_view_row(C.row(9, 24)) == C.row(9, 19)
    assert S.R132_view_row(C.row(9, 25)) == C.row(9, 24)
    assert S.R132_view_row(C.row(15, 0)) == C.row(14, 54)
    assert S.R132_view_row(C.row(15, 1)) is None              # the last view is more than 5 minutes old


def test_money_and_gates():
    T = pd.DataFrame(dict(day=[date(2026, 4, 1), date(2026, 4, 2), date(2026, 5, 1)], entry=[1, 1, 1],
                          net=[1000.0, -500.0, 200.0], netx2=[900.0, -600.0, 100.0], why=["trail", "stop", "close"]))
    s = S.money(T)
    assert s["n"] == 3 and s["win"] == pytest.approx(2 / 3) and s["total"] == 700.0 and s["max_dd"] == -500.0
    assert s["worst_month"] == 200.0 and s["worst_month_name"] == "2026-05"
    b = dict(total=600.0, win=0.5, worst_month=-100.0, max_dd=-800.0)
    assert all(S.part_gates(s, b).values())
    assert S.money(None)["n"] == 0


# ------------------------------------------------------------------------------------------------ study locks
def test_final_read_lock(tmp_path, monkeypatch):
    import r188study as ST
    monkeypatch.setattr(ST, "FINAL_FLAG", tmp_path / "FINAL_READ")
    monkeypatch.setattr(C, "RESULTS", tmp_path)
    (tmp_path / "design.json").write_text("{}")
    (tmp_path / "FINAL_READ").write_text("{}")
    with pytest.raises(SystemExit):
        ST.final()


def test_nthr_grid():
    assert 0.0 not in C.NTHR_GRID and len(C.NTHR_GRID) == 6
    assert C.LEVERS["CISD"][-1] == "NTHR" and "NTHR" not in C.LEVERS["S5"]


# ------------------------------------------------------------------------------------------------ parity (cache-backed)
PAR = C.RESULTS / "parity.json"


@pytest.mark.skipif(not PAR.exists(), reason="parity not run")
def test_parity_s5():
    p = json.loads(PAR.read_text())
    assert p["s5_r123_list"]["same"] and p["s5_picks"]["same"]
    r = p["s5_r128"]
    assert r["wf"]["n"] == 555 and r["holdout"]["n"] == 41
    assert abs(r["wf"]["avg_net"] - 240) < 10 and abs(r["holdout"]["avg_net"] - 561) < 10
    assert r["share_within_1"] >= 0.99


@pytest.mark.skipif(not PAR.exists(), reason="parity not run")
def test_parity_cisd():
    p = json.loads(PAR.read_text())["cisd_r133"]
    assert p["design"]["n"] == 413 and abs(p["design"]["avg_net"] - 261) < 10
    assert p["final"]["n"] == 35
    assert p["share_within_1"] >= 0.99
