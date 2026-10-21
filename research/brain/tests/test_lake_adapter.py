"""The lake adapter, on lake-format files made from the release fixture (same real prices, Dhan's planned schema:
tz-aware IST, rolling ATM offset per minute, side C/P, no expiry column)."""
import numpy as np

from research.brain.adapters.lake import LakeAdapter

from conftest import DAYS


def test_lake_adapter_builds_same_index_and_vix(lake, lake_built, built):
    assert lake.available("BANKNIFTY") and lake.sessions("BANKNIFTY") == DAYS
    M, C = lake_built["M"], lake_built["C"]
    assert M.day.nunique() == len(DAYS)
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
