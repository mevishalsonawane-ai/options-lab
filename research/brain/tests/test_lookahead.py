"""LOOK-AHEAD GUARD (ARCHITECTURE.md, additions item 2).

(a) every registered feature, recomputed with all rows after t deleted and with them perturbed, is identical at every
    row <= t; a deliberately leaky canary feature must be caught;
(b) labels never enter feature frames;
(c) train/test splits purge overlapping label windows and embargo after the test block.
"""
import ast
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from research.brain import labels, split
from research.brain.features import REGISTRY, compute, load_all
from research.brain.features.registry import Feature
from research.brain.guard import check_features

BRAIN = Path(__file__).resolve().parents[1]


def _cuts(M):
    ts = M.ts.sort_values().reset_index(drop=True)
    d = ts.dt.normalize()
    firsts, lasts = ts.groupby(d).first(), ts.groupby(d).last()
    pick = [firsts.iloc[0], firsts.iloc[0] + pd.Timedelta(minutes=14), lasts.iloc[0],
            firsts.iloc[1] + pd.Timedelta(minutes=5), firsts.iloc[1] + pd.Timedelta(minutes=167),
            firsts.iloc[2] + pd.Timedelta(minutes=29), lasts.iloc[2] - pd.Timedelta(minutes=1),
            firsts.iloc[3], firsts.iloc[3] + pd.Timedelta(minutes=118), lasts.iloc[3] - pd.Timedelta(minutes=61)]
    return pick


def test_every_feature_is_point_in_time(built):
    load_all()
    assert len(REGISTRY) >= 40
    bad = check_features(built["M"], built["C"], "BANKNIFTY", _cuts(built["M"]))
    assert not bad, f"features reading the future: {sorted(bad)}"


def test_guard_catches_a_leaky_feature(built):
    def leaky(ctx):
        return np.r_[ctx.c[1:], np.nan]            # next minute's close: look-ahead
    def leaky_day(ctx):
        return ctx.groupby_day(ctx.h).transform("max").values   # the session's final high: look-ahead
    REGISTRY["_canary_next"] = Feature("_canary_next", "price", 1, (), "0m", "", leaky)
    REGISTRY["_canary_dayhigh"] = Feature("_canary_dayhigh", "price", 1, (), "0m", "", leaky_day)
    try:
        bad = check_features(built["M"], built["C"], "BANKNIFTY", _cuts(built["M"])[:4],
                             names=["_canary_next", "_canary_dayhigh", "ret_1m"])
    finally:
        del REGISTRY["_canary_next"], REGISTRY["_canary_dayhigh"]
    assert set(bad) == {"_canary_next", "_canary_dayhigh"}


def test_labels_never_in_feature_frames(built):
    F = compute(built["M"], built["C"], "BANKNIFTY")
    L = labels.compute(built["M"])
    fcols, lcols = set(F.columns) - {"ts"}, set(L.columns) - {"ts"}
    assert not any(c.startswith(labels.PREFIX) for c in fcols)
    assert all(c.startswith(labels.PREFIX) for c in lcols)
    assert not fcols & lcols
    assert not any(n.startswith(labels.PREFIX) for n in REGISTRY)
    # no feature module imports the labels module (or split, the only place they meet)
    for p in (BRAIN / "features").glob("*.py"):
        tree = ast.parse(p.read_text())
        for node in ast.walk(tree):
            if isinstance(node, (ast.Import, ast.ImportFrom)):
                names = [a.name for a in node.names] + [getattr(node, "module", "") or ""]
                assert not any(n.split(".")[-1] in ("labels", "split") for n in names), p.name
    J = split.join_forward(F, L)
    assert len(J) == len(F)
    with pytest.raises(ValueError):
        split.join_forward(F.assign(y_leak=1.0), L)
    with pytest.raises(ValueError):
        split.join_forward(F, L.assign(ret_1m=1.0))


def test_labels_look_forward_within_session(built):
    M = built["M"].sort_values("ts").reset_index(drop=True)
    L = labels.compute(M)
    c = M.close.values
    i = 100
    assert np.isclose(L.y_ret_15m[i], np.log(c[i + 15] / c[i]) * 100)
    assert np.isclose(L.y_mfe_15m[i], (M.high.values[i + 1:i + 16].max() / c[i] - 1) * 100)
    last = M.groupby("day").tail(15).index
    assert L.y_ret_15m[last].isna().all()          # never crosses into the next session


@pytest.mark.parametrize("kind", ["walk_forward", "purged_kfold"])
def test_splits_purge_and_embargo(built, kind):
    ts = pd.DatetimeIndex(built["M"].ts)
    h, emb = pd.Timedelta(minutes=labels.MAX_HORIZON_MIN), pd.Timedelta(days=1)
    folds = list(getattr(split, kind)(ts, n_splits=3, horizon=h, embargo=emb))
    assert folds
    for tr, te in folds:
        assert len(te) and len(tr)
        assert not set(tr) & set(te)
        lo, hi = ts[te].min(), ts[te].max()
        t = ts[tr]
        assert not ((t + h >= lo) & (t <= hi)).any(), "label window of a training row reaches the test block"
        assert not ((t > hi) & (t <= hi + emb)).any(), "training row inside the embargo"
        if kind == "walk_forward":
            assert (t < lo).all()


def test_feature_metadata_complete():
    load_all()
    for f in REGISTRY.values():
        assert f.description and f.inputs and f.lookback and f.version >= 1, f.name


def test_features_md_up_to_date():
    from research.brain.features.__main__ import FEATURES_MD
    from research.brain.features.registry import catalogue_md
    assert FEATURES_MD.read_text() == catalogue_md() + "\n", "run: python -m research.brain.features catalog"
