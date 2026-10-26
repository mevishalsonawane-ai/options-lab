"""R110 pricing tests (synthetic data, no network, no release files)."""
from __future__ import annotations

import os
import sys
from datetime import date

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r110study as S  # noqa: E402

D = date(2025, 3, 3)          # a Monday
EXP = date(2025, 3, 27)


class FakeMarket(S.Market):
    """A one-day market: a flat index at 22,000 and one CE / PE at 22,000 priced 100, volume 0."""

    def __init__(self, und="NIFTY", ix_path=None, opt_close=None, expiries=(EXP,)):
        self.und = und
        ix = np.full((4, S.W), 22000.0)
        if ix_path is not None:
            ix[:] = ix_path
        self.ix = {D: ix}
        self.days = [D]
        a = np.full((5, S.W), 100.0)
        a[4] = 0.0
        if opt_close is not None:
            a[0:4] = opt_close
        self.opt = {D: {(EXP, 22000.0, "CE"): a.copy(), (EXP, 22000.0, "PE"): a.copy()}}
        self.expiry = {D: EXP}
        self._exps = set(expiries)

    # is_monthly reads mk.expiry's values; give it the listed set
    @property
    def all_expiries(self):
        return self._exps


def flat_fill():
    return S.Fill(0.0)          # no slippage: prices exactly as printed


def test_lot_schedule():
    assert S.lot("NIFTY", date(2024, 11, 27)) == 25
    assert S.lot("NIFTY", date(2024, 11, 28)) == 75
    assert S.lot("BANKNIFTY", date(2025, 6, 27)) == 35


def test_entry_is_the_minute_after_the_signal_bar_closes():
    # a 5-minute bar starting 09:55 IST closes 10:00: entry at the 10:00 minute (index 45)
    ts = int(np.datetime64("2025-03-03T04:25:00").astype("datetime64[s]").astype(int))   # 09:55 IST
    d, m = S.entry_min_after(ts, 300)
    assert d == D and m == 45


def test_flat_market_loses_only_charges():
    mk = FakeMarket()
    t = S.long_option(mk, D, "CE", 22000, 30, fill=flat_fill())
    assert t["why"] == "squareoff" and t["gross"] == 0
    assert t["net"] == -t["charges"] and t["charges"] > 40      # Rs 20 + Rs 20 brokerage plus statutory


def test_index_stop_before_target_in_the_same_minute():
    ix = np.full((4, S.W), 22000.0)
    ix[1, 40] = 22100          # high reaches the target
    ix[2, 40] = 21900          # low reaches the stop, same minute
    mk = FakeMarket(ix_path=ix)
    t = S.long_option(mk, D, "CE", 22000, 30, idx_stop=21950, idx_target=22050, fill=flat_fill())
    assert t["why"] == "index_stop" and t["exit_min"] == 41      # out at the NEXT minute's open


def test_premium_stop_and_time_exit():
    px = np.full((4, S.W), 100.0)
    px[:, 50:] = 65.0          # premium falls 35% from minute 50
    mk = FakeMarket(opt_close=px)
    t = S.long_option(mk, D, "CE", 22000, 30, prem_stop_mult=0.70, exit_min=89, fill=flat_fill())
    assert t["why"] == "prem_stop" and t["exit_min"] == 50 and t["fill_out"] == 65.0
    mk2 = FakeMarket()
    t2 = S.long_option(mk2, D, "CE", 22000, 30, prem_stop_mult=0.70, exit_min=89, fill=flat_fill())
    assert t2["why"] == "time" and t2["exit_min"] == 89


def test_no_look_ahead_prices_before_entry_never_used():
    # a huge spike BEFORE the entry minute must change nothing
    px = np.full((4, S.W), 100.0)
    px[:, 10] = 900.0
    a = S.long_option(FakeMarket(opt_close=px), D, "CE", 22000, 30, exit_min=60, fill=flat_fill())
    b = S.long_option(FakeMarket(), D, "CE", 22000, 30, exit_min=60, fill=flat_fill())
    assert a["net"] == b["net"]
    ix = np.full((4, S.W), 22000.0)
    ix[1:3, 10] = [23000, 21000]   # an index stop/target crossing before entry is ignored
    c = S.long_option(FakeMarket(ix_path=ix), D, "CE", 22000, 30, idx_stop=21950, idx_target=22050, fill=flat_fill())
    assert c["why"] == "squareoff"


def test_expiry_day_squares_off_at_1505():
    mk = FakeMarket()
    mk.expiry = {D: D}
    mk.opt = {D: {(D, 22000.0, "CE"): v for (e, k, r), v in FakeMarket().opt[D].items() if r == "CE"}}
    t = S.long_option(mk, D, "CE", 22000, 30, fill=flat_fill())
    assert t["exit_min"] == S.SQ_EXP
    assert S.long_option(mk, D, "CE", 22000, S.SQ_EXP, fill=flat_fill()) is None   # no entry at/after the square-off


def test_strike_too_far_is_no_trade():
    mk = FakeMarket()
    assert S.long_option(mk, D, "CE", 22200, 30, fill=flat_fill()) is None


def test_slippage_doubles():
    p = 100.0
    one, two = S.Fill(1.0).buy(p, 0.0), S.Fill(2.0).buy(p, 0.0)
    assert one > p and round(two - p, 2) == round(2 * (one - p), 2)
    assert S.Fill(1.0).sell(p, 0.0, stop=True) < S.Fill(1.0).sell(p, 0.0)


def test_is_monthly():
    mk = FakeMarket("NIFTY")
    assert not S.is_monthly(mk, date(2025, 3, 6))            # a weekly
    assert S.is_monthly(mk, date(2025, 3, 27))               # last Thursday
    assert S.is_monthly(mk, date(2025, 4, 24))
    assert not S.is_monthly(mk, date(2025, 4, 30))           # the 1 May weekly moved back: not April's monthly
    assert S.is_monthly(mk, date(2025, 9, 30))               # last Tuesday (from Sep 2025)
    assert S.is_monthly(mk, date(2026, 3, 30))               # 31 Mar 2026 holiday: the Monday
    assert not S.is_monthly(mk, date(2026, 4, 21))
    bn = FakeMarket("BANKNIFTY")
    assert not S.is_monthly(bn, date(2024, 4, 30), date(2024, 4, 29))   # BANKNIFTY weeklies still listed
    assert S.is_monthly(bn, date(2024, 12, 24), date(2024, 12, 2))
    assert not S.is_monthly(FakeMarket("FINNIFTY"), date(2025, 3, 27), date(2025, 3, 3))


def test_bh_and_bootstrap():
    q = S.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[3] == pytest.approx(0.5)
    import pandas as pd
    rng = np.random.default_rng(1)
    assert S.boot_p(pd.Series([100.0] * 30), 30, rng) == 0.0
    assert S.boot_p(pd.Series([-100.0] * 30), 30, rng) == 1.0


def test_index_points_direction():
    ix = np.full((4, S.W), 22000.0)
    ix[:, 60:] = 21900.0
    mk = FakeMarket(ix_path=ix)
    up = S.index_points(mk, D, True, 30, exit_min=89)
    dn = S.index_points(mk, D, False, 30, exit_min=89)
    assert up["pts"] == -100 and dn["pts"] == 100 and dn["net"] == 100 * S.lot("NIFTY", D)
