"""The lake adapter, on lake-format files made from the release fixture (same real prices, Dhan's planned schema:
tz-aware IST, rolling ATM offset per minute, side C/P, no expiry column)."""
import json

import numpy as np
import pandas as pd

from research.brain import cube
from research.brain.adapters.lake import LakeAdapter

from conftest import DAYS, FIX


def _to_lake(tmp):
    m = pd.read_parquet(FIX / "banknifty_fixture.parquet")
    e = pd.read_parquet(FIX / "expiry_days_fixture.parquet")
    x = pd.read_parquet(FIX / "extra_series_fixture.parquet")
    tz = lambda s: pd.to_datetime(s).dt.tz_localize("Asia/Kolkata")
    ix = m[m.right == "IX"]
    pd.DataFrame(dict(ts=tz(ix.ts), open=ix.open, high=ix.high, low=ix.low, close=ix.close, volume=0)).to_parquet(
        tmp / "index_1m_BANKNIFTY_2025.parquet")
    v = x[x.series == "INDIAVIX"]
    pd.DataFrame(dict(ts=tz(v.ts), open=v.open, high=v.high, low=v.low, close=v.close, volume=0)).to_parquet(
        tmp / "index_1m_INDIAVIX_2025.parquet")
    # options: the nearest expiry per day (the expiring one on 27 Mar), as Dhan's rolling ATM offsets
    o = pd.concat([m[m.right != "IX"], e[e.right != "IX"].drop(columns="underlying")])
    o = o[o.expiry == o.groupby("day").expiry.transform("min")]
    spot = ix.set_index("ts").close
    o = o.assign(spot=o.ts.map(spot)).dropna(subset=["spot"])
    o["offset"] = ((o.strike - (o.spot / 100).round() * 100) / 100).astype(int)
    o = o[o.offset.abs() <= 10]
    pd.DataFrame(dict(ts=tz(o.ts), offset=o.offset, side=o.right.str[0], open=o.open, high=o.high, low=o.low,
                      close=o.close, iv=np.nan, volume=o.volume, strike=o.strike, oi=o.open_interest,
                      spot=o.spot)).to_parquet(tmp / "opt_BANKNIFTY_WEEK_2025.parquet")
    cat = tmp / "CATALOG.json"
    cat.write_text(json.dumps(dict(release_tag="data-test", files=[])))
    return cat


def test_lake_adapter_builds_same_index_and_vix(tmp_path, built):
    lake = tmp_path / "lake"
    lake.mkdir()
    cat = _to_lake(lake)
    ad = LakeAdapter(lake, catalog=cat)
    assert ad.available("BANKNIFTY") and ad.sessions("BANKNIFTY") == DAYS
    root = tmp_path / "cache"
    res = cube.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], adapter_list=[ad], verbose=False)
    assert res["built"] == len(DAYS)
    M, C = cube.load(root, "BANKNIFTY")
    R = built["M"]
    for col in ("close", "vix", "flag", "expiry", "is_expiry_day"):
        assert (M[col].astype(str).values == R[col].astype(str).values).all(), col
    assert M.volume.isna().all()               # Dhan index volume 0 -> "no volume", not zero volume
    # ATM chain prices agree with the release-built cube where both have a traded bar
    a = C[(C.offset.abs() <= 3) & (C.flag == "ok")].set_index(["ts", "side", "offset"]).ltp
    b = built["C"][(built["C"].offset.abs() <= 3) & (built["C"].flag == "ok")].set_index(["ts", "side", "offset"]).ltp
    j = a.to_frame("a").join(b.to_frame("b"), how="inner")
    assert len(j) > 1000 and np.allclose(j.a, j.b)


def test_lake_missing_catalog_not_available(tmp_path):
    assert not LakeAdapter(tmp_path, catalog=tmp_path / "nope.json").available("BANKNIFTY")
