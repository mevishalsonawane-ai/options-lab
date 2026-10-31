"""Tests for R139 Amendment A (PREREG_A.md): S3's events with their parts, each add-on's flag on hand-built bars, the
chain flow flags on a made-up chain, the order-block entry, the day stop, K3's rule, the lock and the look-ahead guard."""
from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
import r139a as A  # noqa: E402
from test_r131 import random_grid, truncate  # noqa: E402
from test_r139 import sides, zig  # noqa: E402

R, B = A.R, A.B
DOWN_UP = [111, 106, 109, 103, 107, 100, 109]          # LH 107, LL 100, then a close above 107.1 (S3)


def test_events_match_s3():
    S = sides([zig(DOWN_UP)])[1]
    ev = A.choch_events(S)
    s3 = B.det_choch(S)
    assert [(e["c"].d, e["c"].r0, e["c"].stop) for e in ev] == [(c.d, c.r0, c.stop) for c in s3]
    e = ev[0]
    ch = S.chart(15)
    assert ch.l[e["b"]] == pytest.approx(99.9) and ch.h[e["i"]] == pytest.approx(107.1)


def test_smc_flags_on_the_plain_zigzag():
    S = sides([zig(DOWN_UP)])[1]
    e = A.choch_events(S)[0]
    f = A.smc_flags(S, e)
    assert f["L1"] is True          # the leg down from 107 took the earlier swing low 102.9
    assert f["L4"] is False         # the close (about 106) is above the middle of 109.1 .. 99.9
    assert set(f) == {"L1", "L2", "L3", "L4", "D2"}


def test_no_sweep_when_the_low_holds():
    pts = [111, 106, 109, 104, 107, 104.5, 109]           # the second low (104.4) is above the first (103.9)
    S = sides([zig(pts)])[1]
    ev = A.choch_events(S)
    for e in ev:
        assert A.smc_flags(S, e)["L1"] is False


def test_displacement():
    bars = zig([111, 106, 109, 103, 107, 100])
    bars += [(100, 100.4, 99.95, 100.3), (100.3, 108.5, 100.3, 108.4), (108.4, 109, 108.2, 108.8)]   # one big bar
    bars += [(108.8, 109, 108.5, 108.9)] * 3
    S = sides([bars])[1]
    ev = A.choch_events(S)
    assert len(ev) == 1
    f = A.smc_flags(S, ev[0])
    assert f["L2"] is True
    assert f["L3"] is False         # the gap completes one bar AFTER the CHoCH bar: not known yet


def test_fair_value_gap_known_at_the_choch():
    bars = zig([111, 106, 109, 103, 107, 100])
    bars += [(100, 100.4, 99.95, 100.3), (100.3, 107.05, 100.3, 107.0), (107.0, 109, 107.5, 108.8)]
    bars += [(108.8, 109, 108.5, 108.9)] * 3
    S = sides([bars])[1]
    ev = A.choch_events(S)
    assert len(ev) == 1
    f = A.smc_flags(S, ev[0])
    assert f["L3"] is True and f["L2"] is False


def test_order_block_entry():
    bars = zig([111, 106, 109, 103, 107, 100, 109])
    bars += [(109, 109.2, 102, 102.5)] + [(102.5, 103, 102.2, 102.8)] * 3      # comes back to the block
    S = sides([bars])[1]
    e = A.choch_events(S)[0]
    c = A.ob_entry(S, e)
    ch = S.chart(15)
    ob = next(x for x in range(e["b"], e["i"] - 1, -1) if ch.red[x])
    assert c is not None
    assert S.ml[0][c.r0 - 1] <= ch.h[ob]
    assert c.r0 > (e["t"] + 1) * 15
    assert c.stop == pytest.approx(R.stop_below(min(ch.l[ob], ch.l[e["b"]])))


def chain(vol_c, vol_p, oi_c0, oi_p0, oi_c1, oi_p1):
    """A made-up chain: 375 minutes x 21 offsets x 2 sides."""
    shp = (R.N, 21, 2)
    vol = np.full(shp, 1.0)
    vol[100:115, :, 0] = vol_c
    vol[100:115, :, 1] = vol_p
    oi = np.full(shp, np.nan)
    oi[:100, :, 0], oi[:100, :, 1] = oi_c0, oi_p0
    oi[100:, :, 0], oi[100:, :, 1] = oi_c1, oi_p1
    cl = np.full(shp, 100.0)
    return SimpleNamespace(chain=dict(volume=vol, oi=oi, close=cl, iv=np.full(shp, 0.2)))


def test_flow_flags_signs_and_surge():
    up = chain(10.0, 2.0, 1000, 1000, 1000, 1500)          # put OI up (PCR up), call volume up
    f = A.flow_flags(up, 114, 1, 65, 20000.0)
    assert f == dict(V1=True, OF1=True, OF2=True)
    g = A.flow_flags(up, 114, -1, 65, 20000.0)               # the put side reads them the other way
    assert g["OF1"] is False and g["OF2"] is False and g["V1"] is True
    early = A.flow_flags(up, 20, 1, 65, 20000.0)
    assert early["V1"] is False                               # no baseline before 09:45
    assert A.flow_flags(None, 114, 1, 65, 20000.0) == dict(V1=False, OF1=False, OF2=False)


def test_flow_flags_read_no_future_minutes():
    up = chain(10.0, 2.0, 1000, 1000, 1000, 1500)
    f1 = A.flow_flags(up, 114, 1, 65, 20000.0)
    for k in ("volume", "oi"):
        up.chain[k][115:] = 9e9
    assert A.flow_flags(up, 114, 1, 65, 20000.0) == f1


def test_day_stop_after_two_losses():
    g = random_grid(np.random.default_rng(11), 2)
    S = R.sides_of(g)
    cands = []
    for r in (30, 60, 90, 120, 150, 180):
        ref = S[1].mo[1][r]
        cands.append((1, B.C(1, r, ref - 0.5, ref + 1000)))           # a tight stop: these lose
    T = A.sequence_daystop(S, cands, set(), g.days, lambda d: True)
    assert len(T) <= 2 and all(t["pts"] < 0 for t in T)


def test_k3_rule():
    v = {"A0": dict(n=1000, avg_net=185.0)}
    for f in A.SINGLE:
        v[f] = dict(n=200, avg_net=10.0)
    v["L1"] = dict(n=400, avg_net=300.0)
    v["V1"] = dict(n=299, avg_net=900.0)                    # too few trades
    v["C3"] = dict(n=350, avg_net=186.0)
    assert A.pick_k3(v) == ["L1", "C3"]
    assert A.pick_k3({"A0": dict(n=0), **{f: dict(n=0) for f in A.SINGLE}}) == []


def test_lock_once(tmp_path, monkeypatch):
    monkeypatch.setattr(A, "OUT", tmp_path / "o")
    monkeypatch.setattr(A, "RESULTS", tmp_path / "r")
    with pytest.raises(R.HoldoutError):
        A.lock("start")
    (tmp_path / "r").mkdir()
    (tmp_path / "r" / "design_a.json").write_text("{}")
    A.lock("start")
    A.lock("done")
    with pytest.raises(R.HoldoutError):
        A.lock("start")


def test_lookahead_guard_events_flags_and_ob():
    """S3 events, their index flags and the order-block entry are the same when every later minute is deleted."""
    rng = np.random.default_rng(1391)
    g = random_grid(rng, 30)
    found = 0
    for sd in (1, -1):
        S = R.sides_of(g)[sd]
        ev = A.choch_events(S)
        found += len(ev)
        for k in rng.choice(len(ev), size=min(5, len(ev)), replace=False):
            e = ev[k]
            f = A.smc_flags(S, e)
            St = R.sides_of(truncate(g, e["c"].d, e["c"].r0))[sd]
            again = [x for x in A.choch_events(St) if (x["c"].d, x["c"].r0) == (e["c"].d, e["c"].r0)]
            assert len(again) == 1 and A.smc_flags(St, again[0]) == f
            o = A.ob_entry(S, e)
            if o is not None:
                St2 = R.sides_of(truncate(g, o.d, o.r0))[sd]
                e2 = [x for x in A.choch_events(St2) if (x["c"].d, x["c"].r0) == (e["c"].d, e["c"].r0)][0]
                o2 = A.ob_entry(St2, e2)
                assert o2 is not None and (o2.d, o2.r0, o2.stop) == (o.d, o.r0, o.stop)
    assert found
