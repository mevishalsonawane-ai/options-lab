"""Sources -> DayData. `pick()` returns the adapters to use, in priority order, skipping any that are absent."""
from __future__ import annotations

from ..config import Paths
from .base import DayData  # noqa: F401
from .lake import LakeAdapter
from .release import ReleaseAdapter


def pick(p: Paths, und: str, source: str = "auto") -> list:
    """source: "release", "lake" or "auto" (lake first where it has the day, release for the rest)."""
    out = []
    if source in ("lake", "auto"):
        lk = LakeAdapter(p.lake)
        if lk.available(und):
            out.append(lk)
        elif source == "lake":
            print(f"data lake not present for {und} (no research/data/CATALOG.json or no files in {p.lake}); skipping")
    if source in ("release", "auto"):
        rl = ReleaseAdapter(p.raw_release)
        if rl.available(und):
            out.append(rl)
        elif source == "release":
            print(f"research-data release files for {und} not in {p.raw_release}; run `python -m research.brain.cube fetch`")
    return out
