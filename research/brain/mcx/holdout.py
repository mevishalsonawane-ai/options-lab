"""The locked MCX holdout (PREREG section 2): sessions from 1 Jul 2026. Design = sessions up to 23 Jun 2026.

    assert_design(frame)          raises if any row is after the design end (studies call it on what they fit)
    read_holdout(idea, root)      the ONE read per idea: recorded in <cache>/mcx/study/holdout_reads.json and in
                                  research/brain/mcx/results/holdout_reads.json (committed); a second read raises
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

from . import config

RESULTS = Path(__file__).resolve().parent / "results"


class HoldoutError(RuntimeError):
    pass


def assert_design(frame) -> None:
    ts = pd.DatetimeIndex(frame["ts"] if isinstance(frame, pd.DataFrame) else frame)
    if len(ts) and ts.max().date() > config.DESIGN_END:
        n = int((ts.date > config.DESIGN_END).sum())
        raise HoldoutError(f"{n} rows after the design end {config.DESIGN_END}; design work may not read them")


def read_holdout(idea: str, root=None, log: Path | None = None) -> None:
    """Record the one holdout read of an idea (call before computing anything on holdout rows)."""
    logs = [config.paths(root).study / "holdout_reads.json", log or RESULTS / "holdout_reads.json"]
    for p in logs:
        if p.exists() and idea in json.loads(p.read_text()):
            raise HoldoutError(f"holdout already read for {idea}: {json.loads(p.read_text())[idea]}")
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    for p in logs:
        r = json.loads(p.read_text()) if p.exists() else {}
        r[idea] = now
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(r, indent=1))
