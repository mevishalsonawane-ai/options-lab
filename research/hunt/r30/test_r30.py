"""R30 unit tests on synthetic data: python3 -m pytest research/hunt/r30/test_r30.py -q

The chain features are point in time (future rows deleted or perturbed change nothing at or before t), F1's holdout is
refused, tercile cut points come from training rows only, the combination cell statistics, the trade gate, the twins
and the debit-spread fills."""
from __future__ import annotations

import os
import sys
from datetime import date

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r30cfg as K  # noqa: E402
import r30data as RD  # noqa: E402
import models as MO  # noqa: E402

N = 375


def _lake(tmp, und="NIFTY", days=("2025-03-03", "2025-03-04"), seed=1):
    rng = np.random.default_rng(seed)
    rows = []
    for d in days:
        spot = 22000 + np.cumsum(rng.normal(0, 5, N))
        for m in range(N):
            atm = round(spot[m] / 50) * 50
            for off in range(-10, 11):
                k = atm + off * 50
                for side in "CP":
                    intr = max(spot[m] - k, 0) if side == "C" else max(k - spot[m], 0)
                    px = intr + 60 * np.exp(-abs(spot[m] - k) / 150)
                    ts = pd.Timestamp(d) + pd.Timedelta(hours=9, minutes=15 + m)
                    rows.append(dict(ts=ts.tz_localize("Asia/Kolkata"), offset=off, side=side, strike=float(k),
                                     spot=spot[m], open=px, high=px, low=px, close=px,
                                     volume=int(rng.integers(0, 5000)), oi=int(1e5 + rng.integers(0, 5e4)),
                                     iv=float(15 + rng.normal(0, 1))))
    df = pd.DataFrame(rows)
    df["side"] = df.side.astype("category")
    df.to_parquet(tmp / f"opt_{und}_WEEK_2025.parquet")
    return df


def test_chain_features_are_point_in_time(tmp_path, monkeypatch):
    df = _lake(tmp_path)
    monkeypatch.setattr(K, "LAKE", tmp_path)
    full = RD.chain_features("NIFTY")
    assert len(full) == 2 * 64 and set(RD.CHAIN) <= set(full.columns)
    assert full[RD.CHAIN].notna().mean().min() > 0.5
    t = pd.Timestamp("2025-03-04 11:02").tz_localize("Asia/Kolkata")
    for mode in ("deleted", "perturbed"):
        d2 = df.copy()
        if mode == "deleted":
            d2 = d2[d2.ts <= t]
        else:
            fut = d2.ts > t
            for c in ("close", "oi", "iv", "volume", "spot"):
                d2[c] = d2[c].astype(float); d2.loc[fut, c] = d2.loc[fut, c] * 1.3 + 7
        d2.to_parquet(tmp_path / "opt_NIFTY_WEEK_2025.parquet")
        got = RD.chain_features("NIFTY")
        cut = (pd.to_datetime(full.day) < pd.Timestamp("2025-03-04")) | (full.m <= 11 * 60 + 2 - 555)
        a = full[cut].reset_index(drop=True)
        b = got.merge(a[["day", "m"]], on=["day", "m"]).reset_index(drop=True)
        pd.testing.assert_frame_equal(a[RD.CHAIN], b[RD.CHAIN], check_exact=False, rtol=1e-12)


def test_f1_holdout_is_refused_and_skipped(tmp_path, monkeypatch):
    with pytest.raises(PermissionError):
        K.check_day(date(2023, 6, 1))
    K.check_day(date(2024, 7, 1))
    assert not K.allowed(date(2024, 6, 30)) and K.allowed(date(2022, 12, 30)) and not K.allowed(date(2021, 7, 1))
    _lake(tmp_path, days=("2024-06-28", "2024-07-01"))
    monkeypatch.setattr(K, "LAKE", tmp_path)
    X = RD.chain_features("NIFTY")
    assert set(pd.to_datetime(X.day).dt.date) == {date(2024, 7, 1)}


def test_folds_never_test_on_training_rows():
    for name, tr_end, a, b in K.FOLDS:
        assert tr_end <= a and a <= b
        assert not (a <= K.F1_HOLD[1] and b >= K.F1_HOLD[0])


def test_terciles_use_training_cut_points_only():
    tr = pd.DataFrame(dict(x=np.arange(300.0)))
    te = pd.DataFrame(dict(x=np.array([-5.0, 150.0, 1e6, np.nan])))
    btr, bte = MO.terciles(tr, te, ["x"])
    assert list(bte["x"]) == [0, 1, 2, -1]
    te2 = pd.DataFrame(dict(x=np.array([1e9] * 4)))
    assert list(MO.terciles(tr, te2, ["x"])[1]["x"]) == [2, 2, 2, 2]     # test values never move the cut points


def test_cells_statistics():
    a = np.array([0, 0, 1, 1, 2, 2, -1, 0], np.int8)
    b = np.array([0, 0, 0, 1, 2, 2, 0, 0], np.int8)
    y = np.array([1.0, 3.0, 5.0, -2.0, 4.0, 4.0, 100.0, -1.0])
    day = np.array([0, 1, 2, 3, 4, 4, 5, 6])
    yr = np.array([0, 1, 0, 1, 0, 1, 0, 1])
    n, mean, nd, miny = MO._cells([a, b], np.ones(8, bool), y, day, yr, 2, 9)
    assert n[0] == 3 and mean[0] == pytest.approx(1.0) and nd[0] == 3 and miny[0] == pytest.approx(1.0)  # yr0 1, yr1 2
    assert n[8] == 2 and nd[8] == 1 and miny[8] == 4.0                                    # code 2*3+2
    assert n.sum() == 7                                                                   # the NaN row is out


def _te(nets_c, nets_p, ms, day=date(2025, 1, 2), und="NIFTY", xrow=None):
    k = len(ms)
    return pd.DataFrame(dict(und=und, day=day, m=ms, qty=75, C_X1=nets_c, P_X1=nets_p, C_X1_row=xrow or [m + 30 for m in ms],
                             P_X1_row=xrow or [m + 30 for m in ms], C_X1_2x=nets_c, P_X1_2x=nets_p,
                             C_X1_post=np.nan, P_X1_post=np.nan, C_X1_exit=1.0, P_X1_exit=1.0, C_X1_entry=1.0,
                             P_X1_entry=1.0, C_X4=nets_c, P_X4=nets_p, C_X4_row=[m + 31 for m in ms],
                             P_X4_row=[m + 31 for m in ms]))


def test_trade_gate_one_at_a_time_three_a_day():
    te = _te([10.0] * 6, [-5.0] * 6, [14, 19, 44, 79, 114, 149])
    side = np.array(["C"] * 6)
    Tr = MO.trade(te, side, "X1")
    assert list(Tr.m) == [14, 44, 79]                       # 19 busy (exit 44), cap of 3 a day
    side2 = np.array(["", "P", "", "", "C", ""])
    Tr2 = MO.trade(te, side2, "X1")
    assert list(Tr2.side) == ["P", "C"] and list(Tr2.net) == [-5.0, 10.0]


def test_twin_p():
    te = _te([0.0] * 64, [0.0] * 64, K.GRID)
    Tr = pd.DataFrame(dict(und="NIFTY", day=date(2025, 1, 2), side=["C"] * 5, net=[100.0] * 5))
    p, tw = MO.twin_p(Tr, te, "X1", n_sets=200)
    assert p == 0.0 and tw == 0.0
    p2, _ = MO.twin_p(Tr.assign(net=-1.0), te, "X1", n_sets=200)
    assert p2 == 1.0


def test_spread_trade_fills_and_exit():
    class Op:
        atm = {date(2025, 1, 2).toordinal(): np.full(N, 22000.0)}
        expiry = set()

    def P(px):
        a = np.full((5, N), np.nan)
        a[0] = a[1] = a[2] = a[3] = px
        a[4] = 1e6
        return a
    long = np.full(N, 100.0)
    short = np.full(N, 40.0)
    long[30:] = 140.0                                      # spread 60 -> 100: +60% target at the close of row 30
    paths = {("C", 22000.0): P(long), ("C", 22100.0): P(short)}

    class Paths:
        def get(self, d, s, k):
            return paths.get((s, k))
    net, debit = RD._spread_trade(Op(), Paths(), "NIFTY", date(2025, 1, 2).toordinal(), 20, "C", 75, 365)
    assert debit == pytest.approx((round(100 * 1.0005, 2) + 0.05 - (round(40 * 0.9995, 2) - 0.05)) * 75)
    assert 2500 < net < 3000                                # ~ +40 a unit x 75 less spreads and 4 orders' charges
    short[:] = np.nan
    paths[("C", 22100.0)] = P(short)
    assert np.isnan(RD._spread_trade(Op(), Paths(), "NIFTY", date(2025, 1, 2).toordinal(), 20, "C", 75, 365)[0])


def test_feature_cols_exclude_labels():
    T = pd.DataFrame(dict(day=[1], m=[14], qty=[75], mv30=[1.0], absmv30=[1.0], und=["NIFTY"], year=[2025],
                          C_X1=[1.0], P_SPR=[1.0], rib5_stack=[1.0], atm_iv=[15.0], und_id=[0]))
    assert MO.feature_cols(T) == ["rib5_stack", "atm_iv", "und_id"]
    assert MO.is_ribbon("rib5_stack") and MO.is_ribbon("cls15_order") and not MO.is_ribbon("atm_iv")
