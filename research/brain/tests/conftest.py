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

import json  # noqa: E402

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from research.brain import cube  # noqa: E402
from research.brain.adapters.lake import LakeAdapter  # noqa: E402
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


# --- the same fixture in the Dhan data lake's schema ---------------------------------------------------------------
NEAR, NEXT = date(2025, 3, 27), date(2025, 4, 24)      # the March future expires on the fixture's last day (roll day)
NEXT_PREMIUM = 250.0


def to_lake(tmp: Path) -> Path:
    """Write the release fixture as lake files (same real prices; tz-aware IST; Dhan's rolling ATM offsets; no
    option expiry column). The futures file holds two contracts. Their prices are the release's BNFUT (the April one
    shifted by NEXT_PREMIUM) and their VOLUME IS SYNTHETIC (seeded random, test-only: the release has no futures
    volume) so the volume, VWAP and profile code paths run in CI. Nothing synthetic is ever written to the cache."""
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
    o = pd.concat([m[m.right != "IX"], e[e.right != "IX"].drop(columns="underlying")])
    o = o[o.expiry == o.groupby("day").expiry.transform("min")]
    spot = ix.set_index("ts").close
    o = o.assign(spot=o.ts.map(spot)).dropna(subset=["spot"])
    o["offset"] = ((o.strike - (o.spot / 100).round() * 100) / 100).astype(int)
    o = o[o.offset.abs() <= 10]
    pd.DataFrame(dict(ts=tz(o.ts), offset=o.offset, side=o.right.str[0], open=o.open, high=o.high, low=o.low,
                      close=o.close, iv=np.nan, volume=o.volume, strike=o.strike, oi=o.open_interest,
                      spot=o.spot)).to_parquet(tmp / "opt_BANKNIFTY_WEEK_2025.parquet")
    f = x[x.series == "BNFUT"].reset_index(drop=True)
    rng = np.random.default_rng(42)
    parts = []
    for exp, add, scale in ((NEAR, 0.0, 1.0), (NEXT, NEXT_PREMIUM, 0.3)):
        mv = np.abs(f.close.diff().fillna(0).values)
        vol = np.round((20000 + 4000 * mv) * rng.uniform(0.5, 1.5, len(f)) * scale)
        parts.append(pd.DataFrame(dict(symbol=f"BANKNIFTY-{exp:%b%Y}-FUT", expiry=pd.Timestamp(exp), security_id=1,
                                       ts=tz(f.ts), open=f.open + add, high=f.high + add, low=f.low + add,
                                       close=f.close + add, volume=vol, oi=1e6)))
    pd.concat(parts, ignore_index=True).to_parquet(tmp / "fut_1m_live_index.parquet")
    cat = tmp / "CATALOG.json"
    cat.write_text(json.dumps(dict(release_tag="data-test", files=[])))
    return cat


@pytest.fixture(scope="session")
def lake(tmp_path_factory):
    d = tmp_path_factory.mktemp("lake")
    return LakeAdapter(d, catalog=to_lake(d))


@pytest.fixture(scope="session")
def lake_built(tmp_path_factory, lake):
    root = tmp_path_factory.mktemp("lake_cache")
    cube.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], adapter_list=[lake], verbose=False)
    M, C = cube.load(root, "BANKNIFTY")
    return dict(root=root, M=M, C=C)
