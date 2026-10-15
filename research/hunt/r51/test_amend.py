"""R51 Amendment A tests (synthetic data, no lake): the shared lake cleaning (research/hunt/lakeclean.py: foreign rows,
stray prints, the touched-trade check), the cleaned store build, the re-priced targets, BH with re-priced p, the
one-read guard and the doubled spread / slippage of the engine.

    python -m pytest research/hunt/r51 -q
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import amend as A  # noqa: E402
import common as C  # noqa: E402
import engine as E  # noqa: E402
import r51cfg as K  # noqa: E402
import r51data  # noqa: E402

LC = r51data.lakeclean
D1, D2 = date(2025, 6, 2), date(2025, 6, 3)
NAN = np.nan


def rows(spec):
    """spec: (day, r, side, strike, spot, open, high, low, close)."""
    return pd.DataFrame(spec, columns=["day", "r", "side", "strike", "spot", "open", "high", "low", "close"])


# ------------------------------------------------------------------------------------------------ foreign rows
def test_ref_closes_minute_ffill_and_missing():
    c = np.full(375, NAN)
    c[0], c[5] = 100.0, 110.0
    ref = LC.ref_closes({D1: c}, [D1, D1, D1, D1, D2, D1], [0, 3, 5, 9, 0, -1])
    assert ref[0] == 100.0 and ref[1] == 100.0 and ref[2] == 110.0 and ref[3] == 110.0   # later minutes: last close
    assert np.isnan(ref[4]) and np.isnan(ref[5])                                          # unknown day / bad row


def test_ref_closes_no_close_before_first_minute():
    c = np.full(375, NAN)
    c[10] = 50.0
    assert np.isnan(LC.ref_closes({D1: c}, [D1], [3])[0])
    assert len(LC.ref_closes({}, [D1], [3])) == 1 and len(LC.ref_closes({D1: c}, [], [])) == 0


def test_foreign_mask_threshold():
    ref = np.array([100.0, 100.0, 100.0, 100.0, NAN, 100.0])
    spot = np.array([102.9, 103.1, 96.9, 50.0, 100.0, NAN])
    assert LC.foreign_mask(spot, ref).tolist() == [False, True, True, True, True, True]


def test_drop_foreign_keeps_own_rows():
    c = np.full(375, 24000.0)
    df = rows([(D1, 0, "C", 24000.0, 24010.0, 100, 101, 99, 100), (D1, 0, "C", 13000.0, 13005.0, 90, 91, 89, 90)])
    kept, bad = LC.drop_foreign(df, {D1: c})
    assert kept.strike.tolist() == [24000.0] and bad.strike.tolist() == [13000.0]
    e, b = LC.drop_foreign(df.iloc[:0], {D1: c})
    assert len(e) == 0 and len(b) == 0


# ------------------------------------------------------------------------------------------------ stray prints
def test_stray_ref_is_median_within_contract_and_keeps_order():
    df = rows([(D1, 2, "C", 100.0, 1, 0, 0, 0, 30.0), (D1, 0, "C", 100.0, 1, 0, 0, 0, 10.0),
               (D1, 1, "C", 100.0, 1, 0, 0, 0, 50.0), (D1, 1, "P", 100.0, 1, 0, 0, 0, 999.0),
               (D2, 0, "C", 100.0, 1, 0, 0, 0, 7.0)])
    ref = LC.stray_ref(df)
    # contract C100 on D1, minute order closes 10, 50, 30: medians (10,10,50)=10, (10,50,30)=30, (50,30,30)=30
    assert ref.tolist() == [30.0, 10.0, 30.0, 999.0, 7.0]
    assert len(LC.stray_ref(df.iloc[:0])) == 0


def test_stray_open_mask_needs_both_ticks_and_percent():
    o = np.array([500.0, 61.0, 0.30, 100.0, 5.0])
    ref = np.array([60.0, 60.0, 0.20, 60.0, 0.0])
    # 500 vs 60 stray; 61 vs 60 no; 0.30 vs 0.20 is +50% but exactly 2 ticks -> no; 100 vs 60 stray; ref 0 -> stray
    assert LC.stray_open_mask(o, ref).tolist() == [True, False, False, True, True]


def test_fix_stray_drops_open_caps_high_leaves_low():
    df = rows([(D1, 0, "C", 100.0, 1, 60.0, 61.0, 59.0, 60.0), (D1, 1, "C", 100.0, 1, 500.0, 500.0, 59.0, 60.0),
               (D1, 2, "C", 100.0, 1, 60.0, 90.0, 1.0, 60.0), (D1, 3, "C", 100.0, 1, 60.0, 60.5, 59.5, 60.0)])
    kept, stray, capped = LC.fix_stray(df)
    assert stray.r.tolist() == [1]
    assert capped.r.tolist() == [2] and capped.high.tolist() == [90.0]          # logged as it was
    assert kept.r.tolist() == [0, 2, 3]
    assert kept.set_index("r").high[2] == pytest.approx(72.0)                   # 60 x 1.2
    assert kept.set_index("r").low[2] == 1.0                                    # a stray low is kept (only hurts)
    e = LC.fix_stray(df.iloc[:0])
    assert all(len(x) == 0 for x in e)


def test_clean_runs_foreign_first_and_logs_reasons():
    c = np.full(375, 100.0)
    df = rows([(D1, 0, "C", 100.0, 100.0, 60.0, 61.0, 59.0, 60.0), (D1, 1, "C", 100.0, 100.0, 500.0, 500.0, 59.0, 60.0),
               (D1, 2, "C", 100.0, 100.0, 60.0, 61.0, 59.0, 60.0), (D1, 1, "C", 50.0, 50.0, 5.0, 5.0, 5.0, 5.0)])
    kept, after1, log = LC.clean(df, {D1: c})
    assert len(after1) == 3 and len(kept) == 2
    assert sorted(log.why) == ["foreign", "stray_open"]
    assert LC.counts(log, len(df)) == dict(rows=4, foreign=1, stray_open=1, high_capped=0)
    assert LC.counts(log.iloc[:0], 0) == dict(rows=0, foreign=0, stray_open=0, high_capped=0)


def test_touched_window_and_foreign_contract():
    log = pd.DataFrame(dict(day=[D1, D1, D1], r=[10, 50, 20], side=["C", "C", "P"], strike=[100.0, 100.0, 100.0],
                            why=["stray_open", "high_capped", "foreign"]))
    T = pd.DataFrame(dict(day=[D1, D1, D1, D2], side=["C", "C", "P", "C"], strike=[100.0, 100.0, 100.0, 200.0],
                          er=[5, 11, 20, 0], xr=[10, 40, 25, 10], net=[1.0, 2.0, 3.0, 4.0]))
    keys = {(D1, "C", 100.0), (D1, "P", 100.0)}
    t = LC.touched(T, log, keys)
    assert t.stray_open.tolist() == [True, False, False, False]      # exit minute counts
    assert t.high_capped.tolist() == [False, False, False, False]    # minute 50 is after the exit
    assert t.foreign.tolist() == [False, False, True, False]         # entry minute counts
    assert t.foreign_contract.tolist() == [False, False, False, True]
    assert t["any"].tolist() == [True, False, True, True]
    assert LC.touched(T, log.iloc[:0])["any"].sum() == 0


# ------------------------------------------------------------------------------------------------ cleaned store build
def _write_lake(tmp: Path):
    ts = pd.date_range("2025-06-02 09:15", periods=4, freq="1min", tz="Asia/Kolkata")
    pd.DataFrame(dict(ts=ts, open=24000.0, high=24005.0, low=23995.0, close=24000.0)).to_parquet(
        tmp / "index_1m_NIFTY_2025.parquet")
    rec = []
    for i, t in enumerate(ts):
        for side in ("CE", "PE"):
            o = 500.0 if (side == "CE" and i == 2) else 100.0         # a stray open at 09:17 on the call
            rec.append(dict(ts=t, offset=0, side=side, strike=24000.0, spot=24000.0, open=o, high=max(o, 101.0),
                            low=99.0, close=100.0, volume=1000.0))
    # a foreign ATM row at 09:15 (another underlying's level), listed FIRST so it would win the ATM label
    rec.insert(0, dict(ts=ts[0], offset=0, side="CE", strike=13000.0, spot=13010.0, open=50.0, high=50.0, low=50.0,
                       close=50.0, volume=10.0))
    pd.DataFrame(rec).to_parquet(tmp / "opt_NIFTY_WEEK_2025.parquet")


def test_add_options_clean_vs_original(tmp_path, monkeypatch):
    _write_lake(tmp_path)
    monkeypatch.setattr(K, "LAKE", tmp_path)
    days = r51data.index_days("NIFTY")
    assert r51data.add_options("NIFTY", days) is None
    Dd = days[D1]
    assert Dd.atm[0] == 13000.0                                   # original: the foreign row labels the ATM
    assert Dd.contract("C", 24000.0)[0, 2] == 500.0
    days = r51data.index_days("NIFTY")
    log = r51data.add_options("NIFTY", days, clean=True)
    Dc = days[D1]
    assert Dc.atm[0] == 24000.0 and Dc.contract("C", 13000.0) is None
    assert np.isnan(Dc.contract("C", 24000.0)[0, 2])               # the stray minute is gone ...
    assert Dc.atm[2] == 24000.0                                    # ... but its strike label stays
    assert Dc.contract("P", 24000.0)[0, 2] == 100.0
    assert LC.counts(log, log.attrs["rows"]) == dict(rows=9, foreign=1, stray_open=1, high_capped=0)


# ------------------------------------------------------------------------------------------------ targets, BH, guard
def _fd():
    final = {"PASS": dict(q=0.02, p=0.001, pass_=True), "NEAR": dict(q=0.20, p=0.01), "FAR": dict(q=0.21, p=0.02),
             "REF": dict(q=None, p=0.5), "MCX": dict(q=0.1, p=0.01), "NOP": dict(q=None, p=None)}
    final["PASS"]["pass"] = True
    design = {"PASS": dict(kind="retune", module="r44"), "NEAR": dict(kind="orig", module="r29"),
              "FAR": dict(kind="orig", module="r29"), "REF": dict(kind="ref", module="liq"),
              "MCX": dict(kind="orig", module="m4"), "NOP": dict(kind="orig", module="r29")}
    return final, design


def test_targets_pass_and_near_misses_only():
    final, design = _fd()
    assert A.targets(final, design) == ["PASS", "NEAR"]
    assert A.gated(final, design) == ["PASS", "NEAR", "FAR", "MCX"]


def test_requalify_substitutes_p():
    final, design = _fd()
    q0 = A.requalify(final, design, {})
    assert q0 == {n: round(float(x), 4) for n, x in zip(["PASS", "NEAR", "FAR", "MCX"],
                                                          C.bh([0.001, 0.01, 0.02, 0.01]))}
    q1 = A.requalify(final, design, {"PASS": 0.5})
    assert q1["PASS"] > q0["PASS"] and q1["FAR"] >= q0["FAR"]


def test_requalify_reproduces_the_committed_q():
    fin = json.loads((K.RESULTS / "final.json").read_text())
    des = json.loads((K.RESULTS / "design.json").read_text())
    q = A.requalify(fin, des, {})
    assert len(q) == 125
    assert all(abs(q[n] - fin[n]["q"]) < 1e-4 for n in q)
    assert len(A.targets(fin, des)) == 27 and A.targets(fin, des)[0] == A.PASSING


def test_check_key_refusals(tmp_path, monkeypatch):
    pre = tmp_path / "PREREG.md"
    flag = tmp_path / "FINAL_READ"
    out = tmp_path / "amend_clean.json"
    monkeypatch.setattr(A, "PREREG", pre)
    monkeypatch.setattr(A, "OUT_JSON", out)
    monkeypatch.setattr(K, "FINAL_FLAG", flag)
    pre.write_text("no amendment")
    with pytest.raises(RuntimeError, match="was not"):
        A.check_key(check_git=False)
    flag.write_text("read")
    with pytest.raises(RuntimeError, match="not in PREREG"):
        A.check_key(check_git=False)
    pre.write_text(A.MARK + "\n")
    A.check_key(check_git=False)
    out.write_text("{}")
    with pytest.raises(RuntimeError, match="already made"):
        A.check_key(check_git=False)


def test_the_committed_prereg_holds_the_amendment():
    assert A.MARK in A.PREREG.read_text()


# ------------------------------------------------------------------------------------------------ doubled spread
def _con(o=100.0, vol=0.0):
    a = np.full((5, K.NROWS), float(o))
    a[4] = vol
    return a


def _sim(Cn, slip, **kw):
    a = dict(e=10, sq=300, fx=-1, stop_f=-1.0, tgt_f=-1.0, lk_trig=-1.0, lk_keep=0.0, lk_trail=0.0, steps=E.NOLOCK,
             tmax=30, need=-1.0, cstop=-1.0, tick=K.TICK, qty=75.0)
    a.update(kw)
    return E.simulate(Cn, *a.values(), *slip)


def test_default_fills_are_slip1_and_slip2_is_double():
    Cn = _con()
    base = E.simulate(Cn, 10, 300, -1, -1.0, -1.0, -1.0, 0.0, 0.0, E.NOLOCK, 30, -1.0, -1.0, K.TICK, 75.0)
    assert _sim(Cn, E.SLIP1) == base
    assert base[2] == pytest.approx(100.05 + 4 * 0.05) and base[3] == pytest.approx(99.95 - 4 * 0.05)
    two = _sim(Cn, E.SLIP2)
    assert two[2] == pytest.approx(100.10 + 8 * 0.05) and two[3] == pytest.approx(99.90 - 8 * 0.05)


def test_slip2_stop_and_target():
    Cn = _con()
    Cn[2, 20] = 80.0                                         # low through a -15% stop at 85
    s1, s2 = _sim(Cn, E.SLIP1, stop_f=0.15), _sim(Cn, E.SLIP2, stop_f=0.15)
    assert s1[6] == s2[6] == E.R_STOP
    assert s1[3] == pytest.approx(round(85 * 0.999, 2) - 8 * 0.05)
    assert s2[3] == pytest.approx(round(85 * 0.998, 2) - 16 * 0.05)
    Cn = _con()
    Cn[1, 20] = 140.0                                        # high through a +30% target: limit, no slippage
    t1, t2 = _sim(Cn, E.SLIP1, tgt_f=0.30), _sim(Cn, E.SLIP2, tgt_f=0.30)
    assert t1[6] == t2[6] == E.R_TARGET and t1[3] == t2[3] == pytest.approx(130.0)


def test_run_day_uses_module_slip(monkeypatch):
    from r51data import Day
    D = Day(D1, np.full((4, K.NROWS), 24000.0), np.full(K.NROWS, 24000.0), {("C", 24000.0): _con().astype(np.float32)})
    ex = E.Exits(tmax=30)
    E._STACKS.clear()
    a = E.run_day("NIFTY", D, ["C"], [24000.0], [10], ex)[0]
    monkeypatch.setattr(E, "SLIP", E.SLIP2)
    b = E.run_day("NIFTY", D, ["C"], [24000.0], [10], ex)[0]
    assert b["rE"] > a["rE"] and b["rX"] < a["rX"] and b["net"] < a["net"]
