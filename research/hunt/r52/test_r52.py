"""R52 harness tests (no lake needed): the split guard, scoring, walk-forward, gates, the lake -> obuy layout."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r52cfg as K  # noqa: E402
import r52lab as L  # noqa: E402


def _tr(rows):
    df = pd.DataFrame(rows, columns=["day", "net", "und"])
    df["why"] = "stop"
    return df


def test_split_boundaries_and_final_lock(tmp_path, monkeypatch):
    days = [date(2024, 11, 19), date(2024, 11, 20), date(2026, 3, 31), date(2026, 4, 1), date(2026, 10, 13)]
    assert list(L.in_part(days, "design")) == [False, True, True, False, False]
    monkeypatch.setattr(K, "FINAL_FLAG", tmp_path / "FINAL_READ")
    with pytest.raises(PermissionError):
        L.in_part(days, "final")
    (tmp_path / "FINAL_READ").write_text("x")
    assert list(L.in_part(days, "final")) == [False, False, False, True, True]
    assert K.part_of(date(2024, 11, 19)) == "warmup" and K.part_of(date(2026, 4, 1)) == "final"


def test_lot_schedule_in_force():
    assert K.lot("NIFTY", date(2024, 11, 27)) == 25 and K.lot("NIFTY", date(2024, 11, 28)) == 75
    assert K.lot("NIFTY", date(2026, 1, 2)) == 65 and K.lot("BANKNIFTY", date(2025, 7, 1)) == 35


def test_score_counts_empty_days_and_drawdown():
    d1, d2, d3 = date(2025, 1, 1), date(2025, 1, 2), date(2025, 1, 3)
    res = dict(trades=_tr([(d1, 100.0, "NIFTY"), (d1, -50.0, "NIFTY"), (d3, -200.0, "NIFTY")]), pool=None,
               sessions=[("NIFTY", d1), ("NIFTY", d2), ("NIFTY", d3)])
    s = L.score(res)
    assert s["trades"] == 3 and s["days"] == 3
    assert s["net"] == pytest.approx(-150.0) and s["per_day"] == pytest.approx(-50.0)
    assert s["worst_day"] == pytest.approx(-200.0)            # day 2 had no trade: not a "worst day"
    assert s["max_dd"] == pytest.approx(-200.0)               # peak +50 after day 1, then -150
    assert s["win_rate"] == pytest.approx(1 / 3) and s["p"] == 1.0
    assert np.isnan(s["top5_share"])                          # net <= 0


def test_score_empty_rule():
    res = dict(trades=pd.DataFrame(columns=L.KEEP), pool=None, sessions=[("NIFTY", date(2025, 1, 1))])
    s = L.score(res)
    assert s["trades"] == 0 and s["net"] == 0.0 and s["by_fold"] == [0.0] * 4


def test_top5_share():
    days = [date(2025, 1, d) for d in range(1, 11)]
    res = dict(trades=_tr([(d, 100.0 if i < 9 else -100.0, "NIFTY") for i, d in enumerate(days)]), pool=None,
               sessions=[("NIFTY", d) for d in days])
    s = L.score(res)
    assert s["net"] == pytest.approx(800.0) and s["top5_share"] == pytest.approx(500 / 800)


def test_walk_forward_picks_on_past_only():
    a = _tr([(date(2025, 1, 5), 1000.0, "N"), (date(2025, 5, 1), -500.0, "N"), (date(2025, 8, 1), -10.0, "N"),
             (date(2025, 11, 1), -10.0, "N"), (date(2026, 2, 1), -10.0, "N")])
    b = _tr([(date(2025, 1, 5), 10.0, "N"), (date(2025, 5, 1), 900.0, "N")])
    wf = L.walk_forward({"a": a, "b": b})
    assert wf["picks"][0]["pick"] == "a" and wf["picks"][0]["test_net"] == pytest.approx(-500.0)
    assert wf["picks"][1]["pick"] == "b"                       # b's +900 in fold 1 is now in the past
    assert wf["net"] == pytest.approx(-500.0 + 0 + 0 + 0)


def test_gates():
    fin = dict(net=1000.0, p=0.01, trades=50, top5_share=0.5)
    assert L.gates(fin, 10.0, 0.05)["pass"]
    assert L.gates(fin, -1.0, 0.05)["verdict"] == "FAIL"
    assert L.gates({**fin, "trades": 39}, 10.0, 0.05)["verdict"] == "too few trades to judge"
    assert not L.gates({**fin, "top5_share": 1.2}, 10.0, 0.05)["pass"]
    assert not L.gates(fin, 10.0, 0.2)["pass"] and not L.gates({**fin, "p": 0.06}, 10.0, 0.01)["pass"]


def test_bh_monotone():
    q = L.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[3] == pytest.approx(0.5) and q[1] == pytest.approx(q[2])


def test_lake2obuy_layout_reads_back(tmp_path, monkeypatch):
    """A tiny fake lake -> obuy layout -> obuy's Block puts each bar on its IST day and minute, keyed by strike."""
    import lake2obuy
    from obuy import config as OC
    from obuy import data as OD
    lake = tmp_path / "lake"
    lake.mkdir()
    ts = pd.to_datetime(["2025-01-02 09:15", "2025-01-02 09:16", "2025-01-02 15:29"]).tz_localize("Asia/Kolkata")
    rows = []
    for t in ts:
        for side in ("C", "P"):
            rows.append(dict(ts=t, offset=0, side=side, open=100.0, high=101.0, low=99.0, close=100.5, iv=12.0,
                             volume=750, strike=23500.0, oi=1500, spot=23510.0))
    pd.DataFrame(rows).to_parquet(lake / "opt_NIFTY_WEEK_2025.parquet")
    pd.DataFrame(dict(ts=ts, open=1.0, high=2.0, low=0.5, close=1.5, volume=0)).to_parquet(lake / "index_1m_NIFTY_2025.parquet")
    pd.DataFrame(dict(ts=ts, open=1.0, high=2.0, low=0.5, close=13.0, volume=0)).to_parquet(lake / "index_1m_INDIAVIX_2025.parquet")
    pd.DataFrame(dict(symbol=["INDIAVIX"], date=[date(2025, 1, 2)], open=[13.0], high=[14.0], low=[12.0], close=[13.5],
                      volume=[0])).to_parquet(lake / "index_daily.parquet")
    monkeypatch.setattr(K, "LAKE", lake)
    out = tmp_path / "obuy"
    lake2obuy.convert(out, unds=("NIFTY",), years=(2025,))
    monkeypatch.setattr(OC, "DATA", str(out))
    OD._TABLES.clear()
    b = OD.Block("NIFTY", "WEEK", 2025)
    assert b.n == 6 and list(b.days) == [OD.dnum(date(2025, 1, 2))]
    assert sorted(set(b.m.tolist())) == [9 * 60 + 15, 9 * 60 + 16, 15 * 60 + 29]
    assert set(b.strike.tolist()) == {23500} and set(b.right.tolist()) == {1, -1}
    ch = OD.Chain(b, b.rows(OD.dnum(date(2025, 1, 2))), "NIFTY", "WEEK", date(2025, 1, 2))
    assert ch.c["C"][0, 0] == pytest.approx(100.5) and np.isnan(ch.c["C"][0, 2])
    OD._TABLES.clear()
