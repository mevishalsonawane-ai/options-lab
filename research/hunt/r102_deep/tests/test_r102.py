"""R102 tests: loaders on a synthetic lake, the expiry calendar, the look-ahead guard (signals at day t do not change
when later data is removed; forward returns never cross the design end), the holdout lock, non-overlapping bars, BH,
and the facts file's schema (every fact has a sample size and its numbers are in the cited source).

    python -m pytest research/hunt/r102_deep/tests -q
"""
from __future__ import annotations

import io
import json
import sys
import zipfile
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
ROOT = HERE.parents[3]

import r102data as D  # noqa: E402
import r102desc as A  # noqa: E402
import r102pred as B  # noqa: E402


def _trading_days(start: date, n: int) -> list[date]:
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


@pytest.fixture(scope="module")
def lake(tmp_path_factory):
    """A tiny synthetic lake: 60 trading days of NIFTY and BANKNIFTY minutes, dailies, one option book, participants."""
    root = tmp_path_factory.mktemp("lake")
    rng = np.random.default_rng(1)
    days = _trading_days(date(2026, 4, 1), 90)          # runs past the design end (14 Jul 2026) into the holdout
    (root / "index_1m").mkdir(); (root / "index_daily").mkdir(); (root / "options_rolling_1m").mkdir()
    (root / "participants" / "oi").mkdir(parents=True); (root / "participants" / "fpi").mkdir()
    (root / "participants" / "bhav_index").mkdir()
    dailies, opt_rows, bhav_rows, oi_files, fpi_rows = [], [], [], {}, []
    for sym, level, step in (("NIFTY", 25000.0, 50.0), ("BANKNIFTY", 56000.0, 100.0), ("INDIAVIX", 13.0, 0)):
        rows = []
        px = level
        for d in days:
            if sym == "INDIAVIX":
                dailies.append({"symbol": sym, "date": pd.Timestamp(d), "open": px, "high": px * 1.02, "low": px * 0.98, "close": px, "volume": 0.0})
                px *= 1 + rng.normal(0, 0.02)
                continue
            o = px * (1 + rng.normal(0, 0.003))
            closes = o * np.exp(np.cumsum(rng.normal(0, 0.0003, D.N_MIN)))
            for m in range(D.N_MIN):
                c = closes[m]; op = closes[m - 1] if m else o
                hi, lo = max(op, c) * (1 + 0.0001), min(op, c) * (1 - 0.0001)
                ts = pd.Timestamp(d) + pd.Timedelta(minutes=D.OPEN_MIN + m)
                rows.append({"ts": ts.tz_localize("Asia/Kolkata"), "open": op, "high": hi, "low": lo, "close": c, "volume": 0.0})
                if sym == "BANKNIFTY" and m in (0, 5, 15, 30, 75, 165, 225, 255, 315, 345, 360, 374):
                    atm = np.floor(c / step + 0.5) * step
                    for off in (-5, 0, 5):
                        for side in ("C", "P"):
                            k = atm + off * step
                            intrinsic = max(c - k, 0) if side == "C" else max(k - c, 0)
                            prem = intrinsic + 150.0 * (1 - m / 400)
                            opt_rows.append({"ts": ts.tz_localize("Asia/Kolkata"), "offset": off, "side": side, "open": prem, "high": prem, "low": prem,
                                             "close": prem, "iv": 12.0 + (1.0 if side == "P" else 0.0) * (off < 0), "strike": k, "oi": 1000, "volume": 10, "spot": c})
            dailies.append({"symbol": sym, "date": pd.Timestamp(d), "open": o, "high": float(np.max(closes)) * 1.0001,
                            "low": float(np.min(closes)) * 0.9999, "close": float(closes[-1]), "volume": 0.0})
            px = float(closes[-1])
            # bhavcopy rows: weekly expiries on Tuesdays (NIFTY) / the last Tuesday of the month (BANKNIFTY)
            tuesdays = [x for x in days if x.weekday() == 1 and x >= d]
            if sym == "NIFTY":
                exps = tuesdays[:3]
            else:
                exps = [x for x in tuesdays if (x + timedelta(days=7)).month != x.month][:2]
            for e in exps:
                for k in np.arange(px - 5 * step, px + 6 * step, step):
                    for kind in ("CE", "PE"):
                        bhav_rows.append({"date": pd.Timestamp(d), "und": sym, "kind": kind, "expiry": pd.Timestamp(e), "strike": float(k),
                                          "open": 1.0, "high": 1.0, "low": 1.0, "close": 1.0, "settle": 1.0, "contracts": 10.0,
                                          "oi_qty": float(1000 + 100 * abs(k - px) / step), "chg_oi_qty": 0.0, "und_px": px})
        if sym != "INDIAVIX":
            df = pd.DataFrame(rows)
            for y, g in df.groupby(df.ts.dt.year):
                g.to_parquet(root / "index_1m" / f"index_1m_{sym}_{y}.parquet", index=False)
    pd.DataFrame(dailies).to_parquet(root / "index_daily" / "index_daily.parquet", index=False)
    pd.DataFrame(opt_rows).to_parquet(root / "options_rolling_1m" / "opt_BANKNIFTY_WEEK_2026.parquet", index=False)
    pd.DataFrame(bhav_rows).to_parquet(root / "participants" / "bhav_index" / "bhav_index_2026.parquet", index=False)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for i, d in enumerate(days):
            fii_long = 100000 + 1000 * i + int(rng.integers(-800, 800))
            cl, fo, fp = int(rng.integers(48000, 52000)), int(rng.integers(10, 40)), int(rng.integers(10, 40))
            text = ('"Participant wise Open Interest (no. of contracts) in Equity Derivatives as on X",,,,,,,,,,,,,,\n'
                    "Client Type,Future Index Long,Future Index Short,Future Stock Long,Future Stock Short,Option Index Call Long,"
                    "Option Index Put Long,Option Index Call Short,Option Index Put Short,Option Stock Call Long,Option Stock Put Long,"
                    "Option Stock Call Short,Option Stock Put Short,Total Long Contracts,Total Short Contracts\n"
                    f"Client,{cl},60000,1,1,10,10,10,10,1,1,1,1,1,1\nDII,100,100,1,1,0,0,0,0,0,0,0,0,1,1\n"
                    f"FII,{fii_long},80000,1,1,{fo},{fp},10,10,1,1,1,1,1,1\nPro,20000,{30000 + 500 * (i % 3)},1,1,5,5,5,5,1,1,1,1,1,1\n"
                    f"TOTAL,{cl + 100 + fii_long + 20000},{60000 + 100 + 80000 + 30000 + 500 * (i % 3)},4,4,{fo + 15},{fp + 15},25,25,3,3,3,3,4,4\n")
            z.writestr(f"fao_participant_oi_{d.strftime('%d%m%Y')}.csv", text)
            fpi_rows.append({"report_date": d.isoformat(), "eq_exch_buy_cr": 100.0, "eq_exch_sell_cr": 90.0 + (i % 2) * 20,
                             "eq_exch_net_cr": 10.0 - (i % 2) * 20, "eq_total_net_cr": 0.0})
    (root / "participants" / "oi" / "oi_2026.zip").write_bytes(buf.getvalue())
    pd.DataFrame(fpi_rows).to_csv(root / "participants" / "fpi" / "fpi_daily.csv", index=False)
    return root, days


@pytest.fixture(autouse=True)
def _use_lake(lake, tmp_path, monkeypatch):
    root, _ = lake
    monkeypatch.setattr(D, "LAKE", root)
    monkeypatch.setattr(D, "OUT", tmp_path / "results")
    monkeypatch.setattr(D, "HOLDOUT_FLAG", tmp_path / "results" / "HOLDOUT_READ")
    for fn in (D.daily, D.minutes, D.bhav, D.expiries, D.participants, D.fpi):
        fn.cache_clear()
    yield


def test_loaders_shapes(lake):
    root, days = lake
    d = D.daily("NIFTY")
    assert len(d) == len(days) and list(d.columns) == ["open", "high", "low", "close"]
    m = D.minutes("BANKNIFTY")
    assert set(m.m) == set(range(D.N_MIN)) and m.day.nunique() == len(days)
    assert D.minute_index(9, 20) == 5 and D.minute_index(15, 29) == 374
    o = D.options("BANKNIFTY", 2026, offsets=[0], minutes_=[5])
    assert set(o.offset) == {0} and set(o.m) == {5} and set(o.side) == {"C", "P"}
    p = D.participants()
    assert len(p) == len(days) and 1000 - 1600 <= p["FII_fut_idx_long"].iloc[1] - p["FII_fut_idx_long"].iloc[0] <= 1000 + 1600
    assert len(D.fpi()) == len(days)


def test_expiry_calendar_from_bhav(lake):
    root, days = lake
    e = D.expiries("NIFTY")
    tues = {d for d in days if d.weekday() == 1}
    assert set(e[e.is_expiry].day) <= tues
    assert e.days_to_expiry.min() == 0 and e.days_to_expiry.max() <= 7
    b = D.expiries("BANKNIFTY")
    # the last Tuesday of each month only, and every one of them is monthly
    assert all(b[b.is_expiry].is_monthly_expiry)
    assert all((x + timedelta(days=7)).month != x.month for x in b[b.is_expiry].day)


def test_holdout_lock_and_periods(lake, tmp_path):
    assert D.period_of(date(2026, 7, 14)) == "design" and D.period_of(date(2026, 7, 15)) == "holdout"
    assert D.period_of(date(2026, 10, 20)) == "after"
    df = pd.DataFrame({"day": [date(2026, 7, 14), date(2026, 7, 15), date(2026, 10, 19), date(2026, 10, 20)], "x": [1, 2, 3, 4]})
    assert D.cut(df, "design").x.tolist() == [1]
    with pytest.raises(PermissionError):
        D.cut(df, "holdout")
    with pytest.raises(PermissionError):
        D.cut(df, "all")
    D.HOLDOUT_FLAG.parent.mkdir(parents=True, exist_ok=True)
    D.HOLDOUT_FLAG.write_text("read\n")
    assert D.cut(df, "holdout").x.tolist() == [2, 3]
    assert D.cut(df, "all").x.tolist() == [1, 2, 3]          # never the days after the holdout
    with pytest.raises(ValueError):
        D.cut(df, "future")


def test_second_holdout_read_refused(lake, tmp_path, monkeypatch, capsys):
    D.HOLDOUT_FLAG.parent.mkdir(parents=True, exist_ok=True)
    D.HOLDOUT_FLAG.write_text("read\n")
    assert B.main(["r102pred.py", "holdout"]) == 2


def test_bar_returns_non_overlapping_inside_session(lake):
    m = D.minutes("NIFTY")
    rr = A.bar_returns(m, 15)
    per_day = rr.groupby(level=0).size()
    assert per_day.max() == D.N_MIN // 15 - 1          # 25 bars a day -> 24 returns, none spanning the open
    one = m[m.day == m.day.iloc[0]]
    c = one.set_index("m").close
    expect = (c[29] / c[14] - 1) * 100
    assert abs(rr.loc[(m.day.iloc[0], 1)] - expect) < 1e-9


def test_lookahead_guard_participant_signals(lake, monkeypatch):
    """Every signal at day t is the same whether or not the days after t exist."""
    monkeypatch.setattr(B, "LEVEL_WINDOW", 30)
    monkeypatch.setattr(B, "LEVEL_MIN", 20)
    full = B.participant_rules()
    p, f = D.participants(), D.fpi()
    cut_at = p.index[40]
    monkeypatch.setattr(D, "participants", lambda: p[p.index <= cut_at])
    monkeypatch.setattr(D, "fpi", lambda: f[f.index <= cut_at])
    trunc = B.participant_rules()
    for k in full:
        a, b = full[k][full[k].index <= cut_at], trunc[k]
        pd.testing.assert_series_equal(a.dropna(), b.dropna(), check_names=False)
        assert a.dropna().size > 0


def test_forward_returns_never_cross_the_design_end(lake):
    fr = B.fwd_returns("NIFTY", "design")
    assert all(d <= D.DESIGN_END for d in fr.day)
    d = D.daily("NIFTY")
    last_design = max(x for x in d.index if x.date() <= D.DESIGN_END)
    # the 1-day return needs close(t+1) inside the design period: the last design day has none
    assert np.isnan(fr.loc[last_design, "r1"]) and np.isnan(fr.loc[last_design, "r5"])
    i = d.index.get_loc(last_design)
    assert np.isfinite(fr.loc[d.index[i - 1], "r1"]) and np.isnan(fr.loc[d.index[i - 4], "r5"]) and np.isfinite(fr.loc[d.index[i - 5], "r5"])


def test_bh_and_summarise():
    q = D.bh([0.01, 0.04, 0.03, 0.5])
    assert q == pytest.approx([0.04, 0.04 * 4 / 3, 0.04 * 4 / 3, 0.5])
    assert D.bh([]) == []
    rng = np.random.default_rng(0)
    signed = rng.normal(5, 1, 500)
    res = B.summarise(signed, np.repeat([2021, 2022, 2023, 2024, 2025], 100), rng.normal(0, 1, 1000))
    assert res["n"] == 500 and res["effect_bps"] == pytest.approx(5, abs=0.3) and res["p_twin"] == 0.0
    assert res["years_same_sign"] == "5/5" and res["ci95_bps"][0] < res["effect_bps"] < res["ci95_bps"][1]
    assert B.summarise(np.array([1.0] * 5), np.array([2021] * 5), np.array([]))["effect_bps"] is None


def test_descriptive_sections_run_on_synthetic_lake(lake, tmp_path):
    res = A.run("design", cache=tmp_path / "cache")
    a3 = res["a3_intraday"]["NIFTY"]
    assert a3["n_days"] > 50 and 0 < a3["range_made_by"]["10:30"]["median_share"] <= 1
    assert abs(sum(v["day_high_here_share"] for v in a3["windows"].values()) - 1) < 1e-6
    g = res["a4_gaps"]["BANKNIFTY"]
    assert g["n_days"] > 50 and 0 <= g["fill_by_close_any_size"]["gap_up"]["share"] <= 1
    s = res["a8_straddle"]["BANKNIFTY"]
    assert s["n_days"] > 50 and s["skew"]["09:20"]["put5_minus_call5_iv_pts"]["mean"] == pytest.approx(1.0)
    assert s["atm_straddle_path"]["non_expiry"]["m374"]["median_share_of_0920"] < 1
    assert res["a9_participants"]["FII_fut_net_share"]["all"]["n"] > 50
    # the cache is read back on a second run
    res2 = A.run("design", cache=tmp_path / "cache")
    assert res2["a3_intraday"] == json.loads(json.dumps(res["a3_intraday"]))   # read back from the cache (JSON keys are strings)


def test_predictive_design_runs_and_counts_70(lake, tmp_path, monkeypatch):
    monkeypatch.setattr(B, "TWINS", 20)
    monkeypatch.setattr(B, "LEVEL_WINDOW", 30)
    monkeypatch.setattr(B, "LEVEL_MIN", 20)
    res = B.run("design")
    ids = [t["id"] for t in res["tests"]]
    assert len(ids) == len(set(ids))
    groups = {g: sum(t["group"] == g for t in res["tests"]) for g in ("B1", "B2", "B3", "B4")}
    assert groups == {"B1": 12, "B2": 36, "B3": 14, "B4": 8} and res["n_tests"] == 70
    for t in res["tests"]:
        assert "q_bh" in t and "pass_design" in t and t["n"] >= 0
        if t["effect_bps"] is not None:
            assert t["last_day"] <= str(D.DESIGN_END)


def test_cost_bps():
    assert B.cost_bps("NIFTY", 25000.0) == pytest.approx(98 / (65 * 0.5) / 25000 * 1e4)
    assert B.cost_bps("BANKNIFTY", 56000.0) == pytest.approx(133 / (30 * 0.5) / 56000 * 1e4)


# ---- the facts file for Jarvis ---------------------------------------------------------------------------------------

FACTS = ROOT / "research" / "knowledge" / "index_facts.yaml"


@pytest.mark.skipif(not FACTS.is_file(), reason="facts file not written yet")
def test_facts_file_schema_and_numbers():
    import yaml
    sys.path.insert(0, str(ROOT / "research" / "knowledge"))
    import build_pack as BP
    facts = yaml.safe_load(FACTS.read_text(encoding="utf-8"))
    assert isinstance(facts, list) and len(facts) >= 20
    ids = [f["id"] for f in facts]
    assert len(ids) == len(set(ids))
    cache: dict = {}
    for f in facts:
        assert f["inst"] in ("NIFTY", "BANKNIFTY", "BOTH")
        assert isinstance(f["n"], int) and f["n"] > 0, f["id"]
        assert f["kind"] in ("descriptive", "rule", "contract")
        assert f["src"] and (ROOT / f["src"]).is_file(), f["id"]
        assert f["text"].strip() and f["period"]
        missing = [x for x in BP.numbers(f["text"]) if x not in BP.source_numbers(f["src"], cache)]
        assert not missing, f"{f['id']}: {missing} not in {f['src']}"
    assert BP.checked(facts, FACTS, {}, "index fact") == []
