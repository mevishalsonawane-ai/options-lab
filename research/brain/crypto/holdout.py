"""The locked crypto holdout and design-only splits (13 Oct 2026).

Holdout: every minute from config.HOLDOUT_START (1 Apr 2026 00:00 UTC) on. Design: everything before, minus the rows
whose label window reaches into the holdout (purged here, so a design label never reads a holdout price).

    design(frame, horizon_min)        design rows only; raises if a frame passed as design data has holdout rows left
    assert_design(frame)              raises if any row is in the holdout (studies call it on everything they read)
    walk_forward(ts, ...)             research/brain/split.walk_forward on design rows (expanding, purged, embargoed)
    read_holdout(idea, frame)         the ONE read per pre-registered idea: records it in <cache>/crypto/study/
                                      holdout_reads.json and refuses a second read of the same idea

No code in this package calls read_holdout yet: the brain was built and studied on design data only.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from .. import split
from . import config


class HoldoutError(RuntimeError):
    pass


def _ts(frame) -> pd.DatetimeIndex:
    return pd.DatetimeIndex(frame["ts"] if isinstance(frame, pd.DataFrame) else frame)


def design(frame: pd.DataFrame, horizon_min: int = 0) -> pd.DataFrame:
    """Rows with ts + horizon < HOLDOUT_START (so the label window ends before the holdout)."""
    t = _ts(frame)
    return frame[np.asarray(t + pd.Timedelta(minutes=horizon_min) < config.HOLDOUT_START)]


def assert_design(frame) -> None:
    t = _ts(frame)
    if len(t) and t.max() >= config.HOLDOUT_START:
        raise HoldoutError(f"{int((t >= config.HOLDOUT_START).sum())} rows on or after the locked holdout "
                           f"({config.HOLDOUT_START}); design work may not read them")


def walk_forward(ts, n_splits: int = 5, horizon_min: int = 1440, embargo_days: int = 1, min_train_blocks: int = 1):
    assert_design(pd.DatetimeIndex(ts))
    return split.walk_forward(ts, n_splits, pd.Timedelta(minutes=horizon_min), pd.Timedelta(days=embargo_days),
                              min_train_blocks)


def _log(root: Path) -> Path:
    return config.paths(root).study / "holdout_reads.json"


def read_holdout(idea: str, frame: pd.DataFrame, root=None) -> pd.DataFrame:
    """The holdout rows of frame, once per idea. A second call for the same idea raises."""
    p = _log(config.paths(root).root)
    reads = json.loads(p.read_text()) if p.exists() else {}
    if idea in reads:
        raise HoldoutError(f"holdout already read for {idea} on {reads[idea]}; a new look needs a new idea and entry")
    reads[idea] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(reads, indent=1))
    return frame[np.asarray(_ts(frame) >= config.HOLDOUT_START)]
