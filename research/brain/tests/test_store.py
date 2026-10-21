"""Feature and label stores on the fixture: written once, then incremental (nothing recomputed)."""
from datetime import date

from research.brain import labels
from research.brain.features import store

from conftest import DAYS


def test_feature_and_label_stores_are_incremental(built):
    root = built["root"]
    r1 = store.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], verbose=False)
    r2 = store.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], verbose=False)
    assert r1["built"] == len(DAYS) and r2["built"] == 0
    F = store.load(root, "BANKNIFTY")
    assert len(F) == 375 * len(DAYS) and not any(c.startswith("y_") for c in F.columns)
    assert labels.build(root, "BANKNIFTY", date(2000, 1, 1), date(2099, 1, 1)) == len(DAYS)
    assert labels.build(root, "BANKNIFTY", date(2000, 1, 1), date(2099, 1, 1)) == 0
    assert (root / "labels").exists() and not (root / "features" / "BANKNIFTY" / "labels").exists()
