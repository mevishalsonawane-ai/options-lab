"""R52 Amendment 2 tests (no lake): the cleaned lake -> obuy layout, twice the spread / slippage, BH with the re-priced
p, the one-read guard and the touched-trade check on obuy trades.

    python -m pytest research/hunt/r52 -q
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import lake2obuy as LO  # noqa: E402
import r52amend as A  # noqa: E402
import r52cfg as K  # noqa: E402
import r52lab as L  # noqa: E402

D1 = date(2025, 6, 2)


def _lake(ts, rec):
    df = pd.DataFrame(rec)
    df["ts"] = ts
    return df


def test_index_closes_reads_grid(tmp_path, monkeypatch):
    ts = pd.date_range("2025-06-02 09:15", periods=3, freq="1min", tz="Asia/Kolkata").append(
        pd.DatetimeIndex([pd.Timestamp("2025-06-02 15:45", tz="Asia/Kolkata")]))
    pd.DataFrame(dict(ts=ts, close=[100.0, 0.0, 102.0, 999.0])).to_parquet(tmp_path / "index_1m_NIFTY_2025.parquet")
    monkeypatch.setattr(K, "LAKE", tmp_path)
    c = LO.index_closes("NIFTY")
    assert list(c) == [D1]
    assert c[D1][0] == 100.0 and np.isnan(c[D1][1]) and c[D1][2] == 102.0      # zero close dropped, 15:45 outside
    assert LO.index_closes("SENSEX") == {}


def test_clean_frame_drops_foreign_and_stray_keeps_out_of_grid():
    ts = pd.DatetimeIndex([pd.Timestamp(f"2025-06-02 09:{m}", tz="Asia/Kolkata") for m in (15, 16, 17, 18)]
                          + [pd.Timestamp("2025-06-02 09:15", tz="Asia/Kolkata"),
                             pd.Timestamp("2025-06-02 09:10", tz="Asia/Kolkata")])
    df = _lake(ts, dict(offset=[0, 0, 0, 0, 0, 0], side=["CE"] * 6, strike=[24000.0] * 4 + [13000.0, 24000.0],
                        spot=[24000.0] * 4 + [13000.0, 24000.0], open=[100.0, 500.0, 100.0, 100.0, 50.0, 7.0],
                        high=[101.0, 500.0, 150.0, 101.0, 50.0, 7.0], low=[99.0] * 6, close=[100.0] * 6,
                        volume=[1.0] * 6, oi=[1.0] * 6, iv=[1.0] * 6))
    closes = {D1: np.full(375, 24000.0)}
    out, log, n = LO.clean_frame(df, closes)
    assert n == 5                                                   # the 09:10 row is outside the grid
    assert out.index.tolist() == [0, 2, 3, 5]                       # 09:16 stray open, the foreign row dropped
    assert out.loc[2, "high"] == pytest.approx(120.0)               # capped at 100 x 1.2
    assert out.loc[5, "open"] == 7.0                                # out-of-grid row untouched
    assert sorted(log.why) == ["foreign", "high_capped", "stray_open"]
    assert list(out.columns) == list(df.columns) and out.side.tolist() == ["CE"] * 4


def test_fills2x_doubles_bps_and_ticks():
    f2, f1 = A.Fills2x(), L.FILLS
    p, v = np.array([100.0]), np.array([0.0])
    assert f1.buy(p, v)[0] == pytest.approx(100.25) and f2.buy(p, v)[0] == pytest.approx(100.50)
    assert f1.sell(p, np.array([30.0]))[0] == pytest.approx(99.90) and f2.sell(p, np.array([30.0]))[0] == pytest.approx(99.80)
    assert f2.sell(p, np.array([30.0]), stop=True)[0] == pytest.approx(99.60)


def test_fills2x_reaches_the_rules_execution(monkeypatch):
    r = A.variants()[0]
    assert r.exe().fills == L.FILLS
    monkeypatch.setattr(L, "FILLS", A.Fills2x())
    assert isinstance(r.exe().fills, A.Fills2x)


def test_variants_are_the_frozen_settings():
    assert sorted(r.vid for r in A.variants()) == ["g17", "orig"]
    d = json.loads((K.RESULTS / "design.json").read_text())
    assert d[A.KEY]["pick"] == A.PASSING


def test_requalify_reproduces_and_substitutes():
    fin = json.loads((K.RESULTS / "final.json").read_text())
    q = A.requalify(fin, {})
    assert len(q) == 188 and all(abs(q[i] - fin[i]["q"]) < 1e-9 for i in q)
    q2 = A.requalify(fin, {"H37-10:g17": 0.9})
    assert q2["H37-10:g17"] > q["H37-10:g17"]
    assert all(q2[i] >= q[i] - 1e-12 for i in q)


def test_check_key_refusals(tmp_path, monkeypatch):
    pre, flag, out = tmp_path / "PREREG.md", tmp_path / "FINAL_READ", tmp_path / "amend_clean.json"
    monkeypatch.setattr(A, "PREREG", pre)
    monkeypatch.setattr(A, "OUT_JSON", out)
    monkeypatch.setattr(K, "FINAL_FLAG", flag)
    pre.write_text("nothing")
    with pytest.raises(RuntimeError, match="was not"):
        A.check_key(check_git=False)
    flag.write_text("read")
    with pytest.raises(RuntimeError, match="not in PREREG"):
        A.check_key(check_git=False)
    pre.write_text(A.MARK)
    A.check_key(check_git=False)
    out.write_text("{}")
    with pytest.raises(RuntimeError, match="already made"):
        A.check_key(check_git=False)
    assert A.MARK in (HERE / "PREREG.md").read_text()


def test_touched_on_obuy_trades(tmp_path, monkeypatch):
    monkeypatch.setattr(A.OC, "DATA", str(tmp_path))
    pd.DataFrame(dict(day=[D1, D1], r=[5, 100], side=["C", "P"], strike=[24000.0, 24000.0],
                      why=["foreign", "stray_open"])).to_parquet(tmp_path / "clean_log_NIFTY_WEEK.parquet")
    T = pd.DataFrame(dict(day=[pd.Timestamp(D1)] * 3, side=[1, -1, -1], strike=[24000.0] * 3, und=["NIFTY"] * 3,
                          entry_min=[555 + 5, 555 + 50, 555 + 0], exit_min=[555 + 20, 555 + 99, 555 + 100],
                          net=[10.0, 20.0, -5.0]))
    rr = A.to_rows(T)
    assert rr.side.tolist() == ["C", "P", "P"] and rr.er.tolist() == [5, 50, 0] and rr.day.iloc[0] == D1
    t = A.touched(T)
    assert t["trades"] == 3 and t["any"]["trades"] == 2 and t["any"]["net"] == 5.0
    assert t["foreign"]["trades"] == 1 and t["stray_open"]["trades"] == 1 and t["high_capped"]["trades"] == 0
    T2 = T.assign(und="SENSEX")                                     # no log for the index: nothing touched
    assert A.touched(T2)["any"]["trades"] == 0
    assert A.touched(T.iloc[:0]) == dict(trades=0)


def test_raw_and_run_refuse_the_wrong_layout(tmp_path, monkeypatch):
    monkeypatch.setattr(A.OC, "DATA", str(tmp_path))
    with pytest.raises(SystemExit):
        A.run()                                                     # not the cleaned layout
    (tmp_path / "CLEANED").write_text("x")
    with pytest.raises(SystemExit):
        A.raw()                                                     # not the original layout
