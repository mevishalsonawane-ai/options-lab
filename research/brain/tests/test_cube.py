from datetime import date

import numpy as np
import pandas as pd

from research.brain import bs, config, cube
from research.brain.adapters import pick
from research.brain.config import paths
from research.brain.guard import check_cube

from conftest import DAYS, FIX


def test_grid_and_flags(built):
    M, C = built["M"], built["C"]
    assert built["res"]["built"] == len(DAYS)
    assert M.groupby("day").size().eq(config.SESSION_MINUTES).all()
    for d, g in M.groupby("day"):
        assert g.ts.iloc[0].strftime("%H:%M") == "09:15" and g.ts.iloc[-1].strftime("%H:%M") == "15:29"
    for col in ("flag", "fut_flag", "vix_flag"):
        assert set(M[col]) <= set(config.FLAGS)
    assert set(C.flag) <= set(config.FLAGS)
    assert len(C) == len(M) * 21 * 2


def test_nothing_invented(built, daydata):
    M, C = built["M"], built["C"]
    # a gap row has no values
    assert M.loc[M.flag == "gap", ["open", "high", "low", "close"]].isna().all().all()
    assert C.loc[C.flag == "gap", "ltp"].isna().all()
    # every LTP is a real traded close of that contract earlier the same day (or this minute)
    for d, dd in daydata.items():
        o = dd.options
        traded = o[~((o.volume == 0) & (o.open == o.close) & (o.high == o.low) & (o.high == o.close))]
        c = C[(C.ts.dt.date == d) & C.ltp.notna()]
        real = set(zip(traded.strike, traded.side, traded.close.round(4)))
        got = set(zip(c.strike, c.side, c.ltp.round(4)))
        assert got <= real
        assert (c.loc[c.flag == "ok", "ltp_age"] == 0).all()
        assert (c.loc[c.flag == "stale", "ltp_age"] > 0).all()


def test_expiry_and_iv(built):
    M, C = built["M"], built["C"]
    e = M.groupby("day").agg(expiry=("expiry", "first"), is_exp=("is_expiry_day", "first"), dte=("dte_days", "first"))
    assert e.loc[pd.Timestamp("2025-03-27")].expiry == pd.Timestamp("2025-03-27")   # the expiring contract, not the next
    assert e.loc[pd.Timestamp("2025-03-27")].is_exp and e.loc[pd.Timestamp("2025-03-27")].dte == 0
    assert not e.loc[pd.Timestamp("2025-03-24")].is_exp
    ok = C[C.iv.notna()]
    assert len(ok) > 0.6 * (C.flag == "ok").sum()
    S = M.set_index("ts").close.reindex(ok.ts).values
    T = ((ok.expiry + pd.Timedelta(hours=15, minutes=30) - (ok.ts + pd.Timedelta(minutes=1))).dt.total_seconds()
         / (365 * 86400)).values
    back = bs.price(S, ok.strike.values, T, ok.iv.values, ok.side.values == "C", config.RISK_FREE, config.DIV_YIELD)
    assert np.allclose(back, ok.close.values, rtol=1e-3, atol=0.05)
    assert (ok.iv.between(config.IV_MIN, config.IV_MAX)).all()
    atm = C[(C.offset == 0) & (C.flag == "ok")]
    assert atm.delta[atm.side == "C"].between(0.2, 0.8).mean() > 0.95


def test_basis_and_vix_present(built):
    M = built["M"]
    assert M.vix.notna().mean() > 0.99 and M.basis.notna().mean() > 0.95
    assert M.basis_pct.abs().max() < 2


def test_incremental_build(built, adapter):
    res = cube.build(built["root"], "BANKNIFTY", DAYS[0], DAYS[-1], adapter_list=[adapter], verbose=False)
    assert res["built"] == 0 and res["skipped"] == len(DAYS)


def test_cube_rows_are_point_in_time(daydata):
    sessions = set(daydata)
    for d in (date(2025, 3, 24), date(2025, 3, 27)):
        g = pd.date_range(f"{d} 09:15", periods=375, freq="min")
        cuts = [g[0], g[4], g[14], g[200], g[373]]
        bad = check_cube(daydata[d], cuts, sessions)
        assert not bad, bad[:10]


def test_lake_absent_is_skipped(tmp_path):
    p = paths(tmp_path)
    ads = pick(p, "BANKNIFTY", "lake")
    assert ads == []
    assert cube.build(tmp_path, "BANKNIFTY", DAYS[0], DAYS[-1], source="lake", verbose=False)["built"] == 0
