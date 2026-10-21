"""Tests for the R83 shared harness (synthetic data only; no downloads needed)."""
from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r83 import common as C


def test_costs_match_app_mccxcosts_doc():
    # McxCosts.kt doc: a 1-lot CRUDEOIL futures round trip at Rs 8,552 (Rs 8,55,200 a side) is Rs 194;
    # a CRUDEOIL ATM option round trip on Rs 24,200 of premium is Rs 84.
    assert C.mcx_fut_charges(855200, 855200) == pytest.approx(194, abs=1.0)
    assert C.mcx_opt_charges(24200, 24200) == pytest.approx(84, abs=1.0)


def test_fut_net_flat_trade_costs_spread_and_charges():
    notional = 70 * 83 * 10
    g, n = C.fut_net("CRUDE", 1, 70.0, 70.0, notional)
    assert g == 0
    spread = 2 * C.HALF_SPREAD["CRUDE"] * notional
    assert -n == pytest.approx(C.mcx_fut_charges(notional, notional) + spread)
    # a stop exit pays more; natgas pays a wider spread than crude
    _, ns = C.fut_net("CRUDE", 1, 70.0, 70.0, notional, stop=True)
    assert ns < n
    _, nn = C.fut_net("NG", 1, 3.0, 3.0, notional)
    assert nn < n


def test_fut_net_short_side_sign():
    g, _ = C.fut_net("CRUDE", -1, 100.0, 99.0, 100000)
    assert g == pytest.approx(1000.0)


def test_mcx_close_follows_us_dst():
    assert C.mcx_close_min(date(2026, 7, 1)) == 23 * 60 + 30
    assert C.mcx_close_min(date(2026, 12, 1)) == 23 * 60 + 55
    assert C.flat_min(date(2026, 7, 1)) == 23 * 60 + 15


def test_et_to_ist_dst_exact():
    assert C.et_to_ist_min(date(2026, 7, 1), 10, 30) == 20 * 60       # EDT: 10:30 ET = 20:00 IST
    assert C.et_to_ist_min(date(2026, 1, 7), 10, 30) == 21 * 60       # EST: 10:30 ET = 21:00 IST
    assert C.et_to_ist_min(date(2026, 1, 7), 16, 30) == 24 * 60 + 3 * 60  # next IST day 03:00


def test_split_and_parts():
    assert C.part_of(date(2016, 3, 1)) == "design"
    assert C.part_of(date(2014, 3, 1)) is None
    assert C.part_of(date(2014, 3, 1), daily=True) == "design"
    assert C.part_of(date(2024, 3, 1)) == "final"
    assert C.part_of(date(2026, 10, 14)) is None


def _tr(d, net):
    return {"entry_ts": pd.Timestamp(d), "net": net}


def test_walk_forward_picks_from_past_only():
    a = [_tr("2018-06-01", 100), _tr("2019-06-01", -50), _tr("2020-06-01", -50), _tr("2021-06-01", -50), _tr("2022-06-01", -50)]
    b = [_tr("2018-06-01", 10), _tr("2019-06-01", 500), _tr("2020-06-01", 500), _tr("2021-06-01", 500), _tr("2022-06-01", 500)]
    wf = C.walk_forward({"a": a, "b": b}, "a")
    # fold 2019 picks a (only 2018 known): -50; 2020: b leads (510 vs 50): +500; 2021, 2022: b
    assert wf["wf_picks"] == ["a", "b", "b", "b"]
    assert wf["wf_net"] == pytest.approx(-50 + 1500)
    assert wf["frozen"] == "b"


def test_final_lock_once(tmp_path, monkeypatch):
    monkeypatch.setattr(C, "RESULTS", tmp_path)
    C.open_final("EX")
    with pytest.raises(C.FinalReadError):
        C.open_final("EX")


def test_gates_and_top5():
    tr = [_tr("2024-01-01", x) for x in (1000, 900, 800, 700, 600, 10, 10, -5)]
    assert C.top5_removed(tr) == 15
    g = C.gates(4000, 0, 100, 0, 0.05, 0.08, 15, 40)
    assert g["verdict"] == "PASS"
    assert C.gates(4000, 5000, 100, 0, 0.05, 0.08, 15, 40)["verdict"] == "fail"
    assert C.gates(4000, 0, 100, 0, 0.05, 0.08, -1, 40)["verdict"] == "fail"


def test_atr_uses_only_past_and_present():
    df = pd.DataFrame({"high": np.arange(30) + 2.0, "low": np.arange(30) + 0.0, "close": np.arange(30) + 1.0})
    a = C.atr(df, 14)
    df2 = df.copy()
    df2.loc[20:, ["high", "low", "close"]] *= 3
    b = C.atr(df2, 14)
    assert a.iloc[:20].equals(b.iloc[:20])
