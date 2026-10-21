"""Tests for the stale-print measurement tools (synthetic data, no lake): measure.py end to end on a tiny lake (the R77
case), its run grouping; compare.py's matching, tolerances and trade diff; rerun.py's argument guard. 18 Oct 2026.

    python -m pytest research/hunt/lakestale -q
"""
from __future__ import annotations

import sys
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parent / "r51"))

import compare as CP  # noqa: E402
import measure as MS  # noqa: E402
import rerun as RR  # noqa: E402
from test_lakestale import D1, bars, frame, r77_case  # noqa: E402


# ------------------------------------------------------------------------------------------------ measure
def _ts(day: date, r: int) -> datetime:
    return datetime(day.year, day.month, day.day, 9, 15) + timedelta(minutes=int(r))


def tiny_lake(tmp_path: Path, df: pd.DataFrame, spot: float = 24720.0) -> Path:
    """index_1m and opt WEEK parquet files for FINNIFTY 2026 holding `df`'s rows (the lake's own columns)."""
    rows = range(0, 120)
    idx = pd.DataFrame(dict(ts=[_ts(D1, r) for r in rows], open=spot, high=spot, low=spot, close=spot))
    idx.to_parquet(tmp_path / "index_1m_FINNIFTY_2026.parquet", index=False)
    o = df.assign(ts=[_ts(d, r) for d, r in zip(df.day, df.r)], offset=0)
    o = o[["ts", "offset", "side", "strike", "spot", "open", "high", "low", "close", "volume"]]
    o.to_parquet(tmp_path / "opt_FINNIFTY_WEEK_2026.parquet", index=False)
    return tmp_path


def test_measure_end_to_end_counts_the_r77_case(tmp_path, monkeypatch):
    monkeypatch.setattr(MS.K, "LAKE", tiny_lake(tmp_path, r77_case()))
    monkeypatch.setattr(MS, "OUT_JSON", tmp_path / "measure.json")
    out = MS.main(["FINNIFTY"])
    t = out["by_index"]["FINNIFTY"]
    assert t["flagged_rows"] == 13 and t["flagged_with_dups"] == 13 and t["causal_flagged"] == 13 - 4
    assert t["contracts"] == 1 and t["days"] == 1 and t["runs"] == 1 and t["flagged_above"] == 13
    assert t["stale_run_rows"] == 13 and t["kept_in_band"] == 0 and t["kept_no_neighbour"] == 0
    big = out["largest_runs"][0]
    assert (big["price"], big["n"], big["start"], big["end"]) == (1855.85, 13, "09:40", "09:52")
    assert big["next_trade"] == 192.45 and big["next_trade_at"] == "09:53"   # information only
    assert (tmp_path / "measure.json").exists()


def test_measure_keeps_a_quiet_correct_run(tmp_path, monkeypatch):
    monkeypatch.setattr(MS.K, "LAKE", tiny_lake(tmp_path, r77_case(stale_px=198.60)))
    monkeypatch.setattr(MS, "OUT_JSON", tmp_path / "measure.json")
    t = MS.main(["FINNIFTY"])["by_index"]["FINNIFTY"]
    assert t["flagged_rows"] == 0 and t["runs"] == 0 and t["stale_run_rows"] == 13 and t["kept_in_band"] == 13


def test_measure_with_no_option_file(tmp_path, monkeypatch):
    tiny_lake(tmp_path, r77_case())
    (tmp_path / "opt_FINNIFTY_WEEK_2026.parquet").unlink()
    monkeypatch.setattr(MS.K, "LAKE", tmp_path)
    t, runs = MS.measure("FINNIFTY")
    assert t["rows"] == 0 and t["flagged_rows"] == 0 and runs == []


def test_runs_of_splits_by_gap_price_and_contract():
    recs = bars(D1, "C", 100, 10, [5.0] * 3, 0) + bars(D1, "C", 100, 14, [5.0] * 2, 0)       # gap at 13
    recs += bars(D1, "C", 100, 16, [6.0] * 2, 0) + bars(D1, "P", 100, 10, [5.0] * 2, 0)      # new price; other side
    df = frame(recs)
    runs = MS.runs_of(df, np.arange(len(df)))
    assert sorted((x["side"], x["r0"], x["r1"], x["n"], x["price"]) for x in runs) == [
        ("C", 10, 12, 3, 5.0), ("C", 14, 15, 2, 5.0), ("C", 16, 17, 2, 6.0), ("P", 10, 11, 2, 5.0)]


def test_hm():
    assert MS.hm(0) == "09:15" and MS.hm(25) == "09:40" and MS.hm(374) == "15:29"


# ------------------------------------------------------------------------------------------------ compare
def row(**kw):
    base = dict(d_trades=10, d_net=100.0, wf_net=50.0, f_trades=5, f_net=20.0, p=0.04, q=0.08, verdict="PASS")
    base.update(kw)
    return base


def test_same_uses_strict_p_q_and_rupee_rounding():
    assert CP.same(row(), row(f_net=20.4))                  # rupee rounding
    assert not CP.same(row(), row(f_net=21.0))
    assert not CP.same(row(), row(p=0.042))                 # a small p move is a change
    assert not CP.same(row(), row(q=0.0801))
    assert not CP.same(row(), row(verdict="FAIL"))
    assert CP.same(None, None) and not CP.same(row(), None)
    assert CP.same(row(p=None), row(p=None)) and not CP.same(row(p=None), row(p=0.1))


def test_delta_lists_only_what_moved():
    assert CP.delta(row(), row(f_net=120.0, p=0.05, verdict="FAIL")) == dict(f_net=100.0, p=0.01, verdict=["PASS", "FAIL"])
    assert CP.delta(row(), row()) == {}


def _t(und="FINNIFTY", day=D1, side="C", strike=24650.0, er=25, net=-100.0, E=1855.85):
    return dict(und=und, day=day, side=side, strike=strike, er=er, net=net, E=E)


def test_trade_diff_matches_changes_and_replacements():
    a = [_t(), _t(er=40, net=10.0), _t(strike=24700.0, net=5.0)]
    b = [_t(er=232, net=-314.0, E=162.7), _t(er=40, net=25.0), _t(strike=24700.0, net=5.0)]
    d = CP.trade_diff(a, b)
    assert (d["clean_trades"], d["stale_trades"], d["changed"], d["net_change"]) == (3, 3, 1, 15)
    assert (d["only_clean"], d["only_clean_net"], d["only_stale"], d["only_stale_net"]) == (1, -100, 1, -314)
    assert d["largest"][0]["er"] == 40
    assert CP.trade_diff(None, b) is None


def test_trade_diff_reads_dataframes_and_timestamps(tmp_path):
    T = pd.DataFrame([_t(day=pd.Timestamp(D1))])
    p = tmp_path / "t.pkl"
    T.to_pickle(p)
    a = CP._trades(p)
    d = CP.trade_diff(a, [_t()])                            # a Timestamp day matches a date day
    assert d["changed"] == 0 and d["only_clean"] == 0 and d["only_stale"] == 0
    assert CP._trades(tmp_path / "missing.pkl") is None


def test_rows_r76_verdict_words():
    s = {"res": {"B0": dict(design=dict(trades=1, net=1), wf=dict(net=1), final=dict(trades=1, net=1)),
                 "T25": dict(design=dict(trades=1, net=0), wf=dict(net=0), final=dict(trades=1, net=0), exit_p=0.5,
                             exit_q=0.9, decision=dict(recommended=False, harmless=False)),
                 "L35": dict(design=dict(trades=1, net=2), wf=dict(net=2), final=dict(trades=1, net=2), exit_p=0.01,
                             exit_q=0.05, decision=dict(recommended=True, harmless=True))}}
    r = CP.rows_r76(s)
    assert r["B0"]["verdict"] is None and r["T25"]["verdict"] == "not recommended" and r["L35"]["verdict"] == "recommended"
    assert CP.rows_r76(None) == {}


# ------------------------------------------------------------------------------------------------ rerun
@pytest.mark.parametrize("argv", [[], ["clean"], ["dirty", "r51", "final"], ["stale", "r99", "final"],
                                  ["stale", "r72", "both"]])
def test_rerun_refuses_bad_arguments(argv):
    with pytest.raises(SystemExit):
        RR.main(argv)


def test_r51_rerun_has_no_design_phase(tmp_path):
    with pytest.raises(SystemExit):
        RR.run_r51(tmp_path, "design")
