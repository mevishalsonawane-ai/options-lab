"""Synthetic tests for the one-minute spike metric and the same-contract pairing (spikes.py)."""
import os, sys

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import spikes as sp


def bars(closes, start="2026-03-02 11:00", step=1, strike=22000.0, side="C", minutes=None):
    """One contract's minute bars; minutes= gives explicit minute offsets from start (to make gaps)."""
    m = list(range(0, step * len(closes), step)) if minutes is None else minutes
    ts = pd.Timestamp(start, tz="Asia/Kolkata") + pd.to_timedelta(m, unit="min")
    return pd.DataFrame(dict(ts=ts, strike=strike, side=side, close=closes, offset=0))


def flags(df):
    x, s = sp.spike_flags(df)
    return list(x.close[s])


def test_isolated_spike_up_and_down():
    assert flags(bars([100, 101, 300, 102, 100])) == [300]
    assert flags(bars([100, 101, 30, 102, 100])) == [30]
    m = sp.spike_metric(bars([100, 101, 300, 102, 100]), "NIFTY")
    assert (m["minutes"], m["spikes"], m["share"]) == (5, 1, 0.2)


def test_threshold_is_more_than_50_percent():
    assert flags(bars([100, 150, 100])) == []  # exactly +50%: not a spike
    assert flags(bars([100, 150.5, 100])) == [150.5]
    assert flags(bars([100, 100 / 1.5, 100])) == []  # down to 1/1.5 of both: not more than 50%
    assert flags(bars([100, 60, 100])) == [60]


def test_sustained_jump_not_flagged():
    assert flags(bars([100, 100, 200, 200, 210, 205])) == []
    assert flags(bars([100, 200, 400])) == []  # keeps rising
    assert flags(bars([100, 400, 250])) == []  # next close is 2.5x the previous: it did not revert


def test_two_minute_spike_not_flagged():
    # Documented limit: only one-minute spikes count; each high minute has one high neighbour.
    assert flags(bars([100, 100, 300, 300, 100, 100])) == []


def test_empty_file_and_single_row():
    empty = bars([])
    m = sp.spike_metric(empty, "GOLD")
    assert (m["minutes"], m["spikes"], m["share"]) == (0, 0, None)
    assert m["early_share"] is None and m["late_share"] is None
    m = sp.spike_metric(bars([123.0]), "GOLD")
    assert (m["minutes"], m["spikes"]) == (1, 0)
    assert flags(bars([100, 300])) == []  # first/last bars have only one neighbour


def test_gaps_in_minutes_use_observed_neighbours():
    # minutes 0, 7, 30: the bar at minute 7 has neighbours 7 and 23 minutes away
    assert flags(bars([100, 400, 100], minutes=[0, 7, 30])) == [400]


def test_neighbours_never_cross_days_or_strikes():
    d1 = bars([100, 400], start="2026-03-02 15:28")
    d2 = bars([100, 100], start="2026-03-03 09:15")
    assert flags(pd.concat([d1, d2])) == []
    # an ATM offset changing strike (100 -> 400 -> 100) is three contracts, not a spike
    a = bars([100], strike=22000.0); b = bars([400], strike=22100.0, start="2026-03-02 11:01")
    c = bars([100], strike=22000.0, start="2026-03-02 11:02")
    assert flags(pd.concat([a, b, c])) == []
    # same strike, other side: separate contracts
    assert flags(pd.concat([bars([100, 400, 100], side="C"), bars([5, 5, 5], side="P")])) == [400]


def test_zero_and_nan_prices_left_out():
    m = sp.spike_metric(bars([100, 0, 100, np.nan, 100, -1]), "NIFTY")
    assert (m["minutes"], m["spikes"]) == (3, 0)
    # a spike next to a bad price is judged against the next valid bar
    assert flags(bars([100, 400, np.nan, 0, 100])) == [400]
    assert flags(bars([0, 0, 0])) == []


def test_duplicate_contract_minutes_counted_once():
    df = bars([100, 400, 100])
    dup = df.assign(offset=1)
    m = sp.spike_metric(pd.concat([df, dup]), "NIFTY")
    assert (m["minutes"], m["spikes"]) == (3, 1)


def test_early_late_split_nse_and_mcx():
    # NSE: early = 09:15-10:44; spike at 10:44 is early, at 10:45 late
    e = bars([100, 400, 100], start="2026-03-02 10:43")
    l = bars([100, 400, 100], start="2026-03-02 10:44", strike=22100.0)
    m = sp.spike_metric(pd.concat([e, l]), "NIFTY")
    assert m["early_window"] == "09:15-10:44"
    assert (m["early_minutes"], m["early_spikes"], m["late_minutes"], m["late_spikes"]) == (3, 1, 3, 1)
    assert m["early_share"] == pytest.approx(1 / 3) and m["share"] == pytest.approx(2 / 6)
    # MCX: the session's first 105 minutes, 09:00-10:44; evening minutes are late
    g = pd.concat([bars([100, 400, 100], start="2026-03-02 09:00"), bars([100, 400, 100], start="2026-03-02 21:00")])
    m = sp.spike_metric(g, "GOLDM")
    assert m["early_window"] == "09:00-10:44"
    assert (m["early_spikes"], m["late_spikes"], m["early_minutes"], m["late_minutes"]) == (1, 1, 3, 3)


def test_book_parsing():
    assert sp.book("x/opt_GOLD_MONTH_2026.parquet") == ("GOLD", "MONTH", 2026)
    assert sp.book("stkopt_M&M_MONTH.parquet") == ("M&M", "MONTH", None)
    assert sp.book("index_1m_NIFTY_2024.parquet") is None
    assert sp.exchange("SILVERM") == "MCX" and sp.exchange("BANKNIFTY") == "NSE"


def test_same_contract_pairs_by_hash():
    E = lambda f, h: dict(file=f, sha256=h)
    cat = [E("opt_BANKNIFTY_WEEK_2025.parquet", "aa"), E("opt_BANKNIFTY_MONTH_2025.parquet", "aa"),
           E("opt_BANKNIFTY_WEEK_2026.parquet", "b1"), E("opt_BANKNIFTY_MONTH_2026.parquet", "b2"),
           E("opt_MIDCPNIFTY_WEEK_2022.parquet", "cc"), E("opt_MIDCPNIFTY_MONTH_2022.parquet", "cc"),
           E("opt_NIFTY_WEEK_2025.parquet", "dd"), E("opt_FINNIFTY_MONTH_2025.parquet", "dd"),  # other underlying
           E("opt_SENSEX_WEEK_2024.parquet", "ee"), E("opt_SENSEX_WEEK_2025.parquet", "ee"),    # other year
           E("stkopt_INFY_MONTH.parquet", "aa")]
    p = sp.same_contract_pairs(cat)
    assert set(p) == {"opt_BANKNIFTY_WEEK_2025.parquet", "opt_BANKNIFTY_MONTH_2025.parquet",
                      "opt_MIDCPNIFTY_WEEK_2022.parquet", "opt_MIDCPNIFTY_MONTH_2022.parquet"}
    assert p["opt_BANKNIFTY_WEEK_2025.parquet"][0] == "opt_BANKNIFTY_MONTH_2025.parquet"
    assert "20 Nov 2024" in p["opt_BANKNIFTY_MONTH_2025.parquet"][1]
    assert "only monthly options in 2022" in p["opt_MIDCPNIFTY_WEEK_2022.parquet"][1]
    assert all("never count both" in r for _, r in p.values())


def test_validate_spikes_section_is_rewritten_in_place(tmp_path, monkeypatch):
    import json
    import validate as v
    m = lambda n, k: dict(minutes=n, spikes=k, share=k / n, early_minutes=n // 2, early_spikes=k, early_share=2 * k / n,
                          late_minutes=n - n // 2, late_spikes=0, late_share=0.0, early_window="09:00-10:44")
    cat = {"files": [dict(file="opt_GOLD_MONTH_2026.parquet", sha256="a", qa={"spikes": m(1000, 2)}),
                     dict(file="opt_GOLD_MONTH_2025.parquet", sha256="b", qa={"spikes": m(1000, 0)}),
                     dict(file="opt_FINNIFTY_WEEK_2026.parquet", sha256="c", same_contract_as="opt_FINNIFTY_MONTH_2026.parquet",
                          qa={"spikes": m(400, 1)}),
                     dict(file="opt_FINNIFTY_MONTH_2026.parquet", sha256="c", same_contract_as="opt_FINNIFTY_WEEK_2026.parquet",
                          qa={"spikes": m(400, 1)}),
                     dict(file="index_daily.parquet", sha256="d", qa={})]}
    (tmp_path / "CATALOG.json").write_text(json.dumps(cat))
    out = tmp_path / "VALIDATION.md"
    out.write_text("# V\n\n## Summary\n\n- a\n- No prices were invented.\n\n## 5. QA\n\n| x |\n")
    (tmp_path / "VALIDATION.json").write_text(json.dumps({"keep": 1, "spikes_old": 2}))
    for k, val in (("HERE", str(tmp_path)), ("OUT", str(out)), ("LAKE", None), ("R", {}), ("md", [])):
        monkeypatch.setattr(v, k, val)
    v.spikes_only()
    first = out.read_text()
    v.spikes_only()
    assert out.read_text() == first  # idempotent: section and summary line replaced, not added again
    assert first.count("## 6. Bad ticks") == 1 and first.count("- Bad ticks (one-minute spikes") == 1
    assert "| GOLD MONTH | MCX | 2 | 2000 | 2 | 0.100% | 0.200% | 0.000% |" in first
    assert "opt_FINNIFTY_MONTH_2026.parquet = opt_FINNIFTY_WEEK_2026.parquet" in first and "### Known issues" in first
    assert first.index("- Bad ticks") < first.index("- No prices were invented") < first.index("## 5. QA")
    r = json.loads((tmp_path / "VALIDATION.json").read_text())
    assert r["keep"] == 1 and "spikes_old" not in r and r["spikes_GOLD_MONTH"]["spikes"] == 2
