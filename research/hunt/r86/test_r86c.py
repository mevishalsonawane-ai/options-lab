"""R86c tests: synthetic data only. The straddle holds the SAME strike while the rolling offset changes; month-end
detection; the option-volume spike needs OI up and the index confirming; the early (09:45 / 12:00) features and the
seller flags with a LOOK-AHEAD GUARD; AUC.

    python -m pytest research/hunt/r86/test_r86c.py -q
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r86 import r86c as C
from research.hunt.r86.r86study import MinuteBook

DAY = pd.Timestamp("2023-03-01")


def _opt_day(k_atm_1129=18000.0, k_after=18100.0):
    rows = []
    for mod in range(689, 916):
        atm = k_atm_1129 if mod < 700 else k_after               # spot moves: the ATM strike changes at 11:40
        for strike in (18000.0, 18100.0):
            off = int((strike - atm) / 100)
            for sd, px in (("C", 100.0 + (mod - 689) * 0.1), ("P", 90.0)):
                rows.append(dict(mod=mod, offset=off, side=sd, strike=strike, open=px, close=px, volume=10, oi=100))
    return pd.DataFrame(rows)


def test_straddle_holds_the_same_strike():
    o = _opt_day()
    p = C.straddle_pnl(o, "NIFTY", DAY)
    assert p["K"] == 18000.0                                       # the 11:29 ATM, not the later one
    # call 100.1 -> 122.6 (mod 690 open .. 915 close), put flat: gross before costs = (22.5) per unit
    assert p["net"] < 22.5 * 75 and p["net"] > 0
    assert C.straddle_pnl(o[o["mod"] != 690], "NIFTY", DAY) is None          # no entry print -> no trade
    assert C.straddle_pnl(o[o["mod"] != 689], "NIFTY", DAY) is None          # no 11:29 ATM -> no trade


def test_month_end_flags_skip_holidays():
    days = pd.Series(pd.to_datetime(["2024-03-27", "2024-03-28", "2024-04-01", "2024-04-30", "2024-05-02"]))
    me = C.month_end_flags(days)
    assert me.tolist()[:4] == [False, True, False, True]        # 28 Mar is March's last session (29 Mar holiday)


def test_auc():
    assert C.auc([1, 0, 1, 0], [0.9, 0.1, 0.8, 0.2]) == pytest.approx(1.0)
    assert C.auc([1, 0], [0.1, 0.9]) == pytest.approx(0.0)
    assert C.auc([0, 0], [0.1, 0.9]) is None


def _book(ndays=3, seed=0):
    g = np.random.default_rng(seed)
    rows = []
    for k in range(ndays):
        day = pd.Timestamp("2023-03-01") + pd.Timedelta(days=k)
        px = 100 + np.cumsum(g.normal(0, 0.05, 375))
        for i, mod in enumerate(range(555, 930)):
            rows.append(dict(day=day, mod=mod, open=px[i] - 0.01, high=px[i] + 0.03, low=px[i] - 0.03, close=px[i],
                             tv=1e6, up=30, dn=29, n=59))
    m = pd.DataFrame(rows)
    bk = MinuteBook(m)
    bk.tv, bk.up, bk.dn, bk.n = (bk.m[c].to_numpy(float).copy() for c in ("tv", "up", "dn", "n"))
    bk.o, bk.h, bk.l, bk.c = (a.copy() for a in (bk.o, bk.h, bk.l, bk.c))
    return bk


def _dtab(bk):
    return pd.DataFrame({"k": range(len(bk.days)), "day": bk.days, "open": [bk.o[bk.start[k]] for k in range(len(bk.days))],
                         "prev_close": [np.nan] + [bk.c[bk.end[k] - 1] for k in range(len(bk.days) - 1)]})


@pytest.mark.parametrize("upto", [584, 719])
def test_early_features_lookahead_guard(upto, monkeypatch):
    bk = _book()
    monkeypatch.setattr(C.D, "minutes", lambda und: pd.DataFrame({"day": bk.days.repeat(2), "mod": [upto, 929] * 3,
                                                                   "close": [15.0, 15.0] * 3}))
    d = _dtab(bk)
    day_of = np.repeat(np.arange(len(bk.days)), bk.end - bk.start)
    gi, sg = np.array([bk.start[1] + upto - 555 - 2, bk.start[1] + upto - 555 + 20]), np.array([-1, 1])
    a = C.early_features(bk, d, gi, sg, day_of, upto)
    # perturb every minute after the decision on day 1 (and the later burst): the features must not change
    sl = bk.day_slice(1)
    after = np.arange(sl.start, sl.stop)[bk.mod[sl] > upto]
    bk.c[after] *= 1.5
    bk.h[after] *= 1.6
    bk.l[after] *= 0.5
    bk.tv[after] *= 9
    b = C.early_features(bk, d, gi, sg, day_of, upto)
    cols = ["mv", "rng", "er", "tv_early", "first30", "ub", "db", "pos", "dn_tv", "mv_1h"]
    pd.testing.assert_frame_equal(a[cols].iloc[[1]], b[cols].iloc[[1]])
    assert a.db.iloc[1] == 1 and a.ub.iloc[1] == 0            # the later up-burst is after the decision


def test_seller_flags_and_staircase():
    x = pd.DataFrame({"day": [DAY, DAY], "gap": [-0.006, 0.001], "mv": [-0.005, 0.002], "db": [1, 0],
                      "breadth_dn": [0.85, 0.3], "tv_ratio": [1.6, 1.0], "stair": [True, False], "pos": [0.1, 0.9],
                      "vix_chg": [0.06, 0.0], "bn_lead": [True, False], "failed_bounce": [True, False],
                      "dn_tv": [True, False]})
    f = C.seller_flags(x, "NIFTY")
    assert f.iloc[0, 1:].astype(bool).all() and not f.iloc[1, 1:].astype(bool).any()
    assert not C.seller_flags(x, "BANKNIFTY").P10.astype(bool).any()


def test_staircase_and_failed_bounce():
    bk = _book(1)
    sl = bk.day_slice(0)
    n = 30
    down = 100 - np.arange(n) * 0.05                  # 09:15-09:44 straight down: every 5-min bar a lower low
    bk.o[sl.start:sl.start + n] = down + 0.01
    bk.c[sl.start:sl.start + n] = down
    bk.h[sl.start:sl.start + n] = down + 0.02
    bk.l[sl.start:sl.start + n] = down - 0.02
    st, fb = C.staircase_and_bounce(bk, pd.DataFrame({"k": [0]}), 584)
    assert st == [True] and fb == [False]


def test_fit_predict_shapes():
    g = np.random.default_rng(3)
    tr = pd.DataFrame({"a": g.normal(size=200), "b": g.normal(size=200)})
    tr["y"] = tr.a + g.normal(scale=0.5, size=200) > 1
    te = tr.iloc[:20]
    ptr, pte = C.fit_predict(tr, te, ["a", "b"], "y")
    assert len(ptr) == 200 and len(pte) == 20 and C.auc(tr.y, ptr) > 0.8


def _c4_case(oi_up=True, index_up=True):
    days = pd.bdate_range("2023-03-01", periods=25)
    rows, orows = [], {}
    for k, day in enumerate(days):
        for mod in range(555, 930):
            drift = 0.02 if (k == 24 and 580 <= mod <= 584) else 0.0
            px = 100 + (mod - 555) * (drift if index_up else -drift)
            rows.append(dict(day=day, mod=mod, open=px, high=px + 0.01, low=px - 0.01, close=px, tv=1.0, up=1, dn=1, n=2))
        o = []
        for mod in range(555, 930):
            spike = k == 24 and 580 <= mod <= 584
            o.append(dict(mod=mod, offset=3, side="C", strike=100.0 + 300, open=5.0, close=5.0,
                          volume=500 if spike else 10, oi=1000 + (mod - 579 if spike and oi_up else 0)))
        orows[day] = pd.DataFrame(o)
    bk = MinuteBook(pd.DataFrame(rows))
    bk.tv, bk.up, bk.dn, bk.n = (bk.m[c].to_numpy(float) for c in ("tv", "up", "dn", "n"))
    return bk, orows


def test_c4_spike_needs_oi_up_and_index_confirming(monkeypatch):
    from research.hunt.r86 import r86b as RB
    monkeypatch.setattr(C, "FOLDS", [2023])
    monkeypatch.setattr(C, "NTWIN", 2)
    monkeypatch.setattr(RB, "NTWIN", 2)
    bk, opts = _c4_case()
    s = C.c4(bk, "NIFTY", opts)
    assert s["n"] == 1 and s["spike_windows"] == 1
    bk, opts = _c4_case(oi_up=False)
    assert C.c4(bk, "NIFTY", opts)["n"] == 0                    # volume without new OI: not a new buyer
    bk, opts = _c4_case(index_up=False)
    assert C.c4(bk, "NIFTY", opts)["n"] == 0                    # the index did not confirm
