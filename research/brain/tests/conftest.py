"""Shared fixtures: a cube built from the committed 4-session slice of the research-data release (24-27 Mar 2025,
BANKNIFTY, 27 Mar an expiry day; options trimmed to strikes within 500 points of 09:20)."""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from research.brain import cube  # noqa: E402
from research.brain.adapters.release import ReleaseAdapter  # noqa: E402

FIX = Path(__file__).resolve().parent / "fixtures"
LAYOUT = {"BANKNIFTY": dict(main=["banknifty_fixture.parquet"], expiry="expiry_days_fixture.parquet",
                            extra="extra_series_fixture.parquet", fut="BNFUT", vix="INDIAVIX")}
DAYS = [date(2025, 3, 24), date(2025, 3, 25), date(2025, 3, 26), date(2025, 3, 27)]


@pytest.fixture(scope="session")
def adapter():
    return ReleaseAdapter(FIX, LAYOUT)


@pytest.fixture(scope="session")
def built(tmp_path_factory, adapter):
    root = tmp_path_factory.mktemp("cache")
    res = cube.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], adapter_list=[adapter], verbose=False)
    M, C = cube.load(root, "BANKNIFTY")
    return dict(root=root, M=M, C=C, res=res)


@pytest.fixture(scope="session")
def daydata(adapter):
    return {d.day: d for d in adapter.load("BANKNIFTY", DAYS[0], DAYS[-1])}
