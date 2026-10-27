"""Liquidity paper check tests (27 Oct 2026). Pure: no network, no cache, no real paper data (hand-made bars and a
hand-made diagnostics file)."""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))

import lpdata  # noqa: E402
import lpreplay as RP  # noqa: E402
import r51cfg as K  # noqa: E402
import r51data  # noqa: E402


# ------------------------------------------------------------------------------------------------ data
def test_to_rows_keeps_only_the_session_grid():
    cs = [("2026-10-26T09:14", 1, 1, 1, 1, 0), ("2026-10-26T09:15", 10, 12, 9, 11, 5),
          ("2026-10-26T15:29", 20, 21, 19, 20, 0), ("2026-10-26T15:30", 30, 30, 30, 30, 0),
          ("2026-10-26T10:00", 5, 5, 5, 0, 0)]                              # a zero close is not a trade
    out = lpdata.to_rows(cs)
    a = out[date(2026, 10, 26)]
    assert a.shape == (5, K.NROWS)
    assert list(a[:, 0]) == [10, 12, 9, 11, 5] and a[3, K.NROWS - 1] == 20
    assert np.isnan(a[3, K.row(10, 0)])
    assert np.isfinite(a[3]).sum() == 2


def test_to_rows_drops_untraded_option_minutes_only_when_asked():
    cs = [("2026-10-19T09:31", 246.2, 246.2, 230.6, 230.6, 600), ("2026-10-19T09:32", 230.6, 230.6, 230.6, 230.6, 0)]
    a = lpdata.to_rows(cs, traded_only=True)[date(2026, 10, 19)]
    assert a[3, K.row(9, 31)] == 230.6 and np.isnan(a[3, K.row(9, 32)])
    b = lpdata.to_rows(cs)[date(2026, 10, 19)]                          # index candles: volume is always 0
    assert b[3, K.row(9, 32)] == 230.6


def test_to_rows_empty():
    assert lpdata.to_rows([]) == {}


def test_contracts_only_the_october_expiry():
    ms = lambda d: int(np.datetime64(d).astype("datetime64[ms]").astype(np.int64)) + 15 * 3600 * 1000  # noqa: E731
    master = [dict(segment="NSE_FO", underlying_symbol="FINNIFTY", instrument_type="CE", expiry=ms("2026-10-27"),
                   strike_price=24400.0, instrument_key="NSE_FO|1"),
              dict(segment="NSE_FO", underlying_symbol="FINNIFTY", instrument_type="CE", expiry=ms("2026-11-23"),
                   strike_price=24400.0, instrument_key="NSE_FO|2"),
              dict(segment="NSE_FO", underlying_symbol="FINNIFTY", instrument_type="FUT", expiry=ms("2026-10-27"),
                   strike_price=0, instrument_key="NSE_FO|3"),
              dict(segment="NSE_FO", underlying_symbol="NIFTY", instrument_type="PE", expiry=ms("2026-10-27"),
                   strike_price=24400.0, instrument_key="NSE_FO|4")]
    assert lpdata.contracts(master, "FINNIFTY") == {("C", 24400.0): "NSE_FO|1"}
    with pytest.raises(AssertionError):
        lpdata.contracts(master, "MIDCPNIFTY")


# ------------------------------------------------------------------------------------------------ replay helpers
def test_walk_book_one_position_at_a_time_and_no_fill_skipped():
    cands = [dict(er=10, side="C", fx=5), dict(er=12, side="P"), dict(er=20, side="C"), dict(er=30, side="P")]
    res = [dict(er=10, xr=20), dict(er=12, xr=14), dict(er=20, xr=25), None]
    out = RP.walk_book(cands, res)
    assert [t["er"] for t in out] == [10]                          # 12 and 20 fall inside the open trade (er <= xr)
    assert "fx" not in out[0] and out[0]["side"] == "C"
    assert RP.walk_book([], []) == []


def test_hhmm():
    assert RP.hhmm(0) == "09:15" and RP.hhmm(K.row(15, 10)) == "15:10"


# ------------------------------------------------------------------------------------------------ look-ahead guard
def _synthetic(seed: int, days: list) -> dict:
    rng = np.random.default_rng(seed)
    out, px = {}, 24000.0
    for d in days:
        steps = rng.normal(0, 6, K.NROWS)
        c = px + np.cumsum(steps)
        o = np.concatenate([[px], c[:-1]])
        h = np.maximum(o, c) + rng.uniform(0, 4, K.NROWS)
        lo = np.minimum(o, c) - rng.uniform(0, 4, K.NROWS)
        D = r51data.Day(d, np.vstack([o, h, lo, c]))
        D.atm = np.floor(c / 50 + 0.5) * 50
        out[d] = D
        px = float(c[-1])
    return out


def _sessions(n: int) -> list:
    d, out = date(2026, 9, 21), []
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def test_signals_do_not_read_the_future():
    """A signal's entry row, side, strike, index stop and target must not change when every index minute after its
    breaking bar is replaced (the levels may only use bars that had closed). The day's later bars are scrambled."""
    days = _sessions(12)
    base = _synthetic(7, days)
    stores = {u: base for u in RP.LIQ_BOOKS}
    _E, fam_liq = RP.load(stores)
    d = days[-1]
    found = 0
    for tf in (5, 15, 30):
        sig = fam_liq.signals("FINNIFTY", tf, 1.0).get(d, [])
        for s in sig:
            cut = s["er"]                                       # the first minute after the breaking bar closed
            other = {k: r51data.Day(k, v.idx.copy(), v.atm) for k, v in base.items()}
            rng = np.random.default_rng(99)
            noise = rng.normal(0, 40, (K.NROWS - cut))
            for i in range(4):
                other[d].idx[i, cut:] = base[d].idx[i, cut:] + noise
            other[d].idx[1, cut:] = np.maximum(other[d].idx[1, cut:], other[d].idx[[0, 3], cut:].max(axis=0))
            other[d].idx[2, cut:] = np.minimum(other[d].idx[2, cut:], other[d].idx[[0, 3], cut:].min(axis=0))
            _E, fam_liq = RP.load({u: other for u in RP.LIQ_BOOKS})
            again = fam_liq.signals("FINNIFTY", tf, 1.0).get(d, [])
            same = [x for x in again if x["er"] == s["er"] and x["side"] == s["side"]]
            assert same, (tf, s["er"])
            for k in ("strike", "idx_stop", "close"):
                assert same[0][k] == s[k]
            assert (np.isnan(same[0]["target"]) and np.isnan(s["target"])) or same[0]["target"] == s["target"]
            found += 1
            _E, fam_liq = RP.load(stores)
    assert found > 0, "the synthetic days gave no signal to test"


# ------------------------------------------------------------------------------------------------ comparison
import lpcompare as CP  # noqa: E402


def _diag(day="2026-10-26", trades=(), decisions=()):
    return dict(kind="iraalgo-diagnostics", day=day, decisions=list(decisions),
                arms=[dict(key="liquidity", trades=list(trades))], orders=[], health={}, timeline=[], other_arms=[])


def _dec(at, arm, kind, verdict=""):
    return dict(at=at, arm=arm, kind=kind, verdict=verdict, outcome="", bar=None)


def _res(day="2026-10-26", und="FINNIFTY", book=5, side="C", strike=24400.0, sig="10:10", entry="10:10", exit_="10:14",
         E=246.65, X=226.5, high=246.65, why="rule_exit"):
    pct = round(100 * (X / E - 1), 2)
    return dict(day=day, und=und, book=book, side=side, strike=strike, signal_end=sig, entry=entry, exit=exit_, why=why,
                E=E, X=X, pct=pct, pct_net=pct - 0.6, entry_high=high, qty=60)


def _paper(at="10:10:00", entry=256.90, exit_=215.05, pct=-16.29, right="CE"):
    return dict(account="paper", right=right, entry_at=at, entry=entry, exit_at="10:13:32", exit=exit_, why="stop",
                points=exit_ - entry, pct=pct, open=False)


def test_fill_above_the_minute_high_is_an_app_bug():
    day = _diag(trades=[_paper()], decisions=[_dec("10:10:00", "Liquidity 5m FINNIFTY", "taken", "entered")])
    out = CP.compare([day], [_res()])
    r = out["rows"][0]
    assert r["app"] == "taken" and r["cls"] == "app_bug" and r["diff_pct"] == round(-16.29 - r["research_pct"], 2)
    assert out["summary"]["paper_sum_pct"] == -16.29 and out["paper_only"] == []


def test_a_fill_inside_the_minute_is_execution_and_a_stale_research_exit_is_data():
    day = _diag(trades=[_paper(entry=246.0, pct=-12.0)],
                decisions=[_dec("10:10:00", "Liquidity 5m FINNIFTY", "taken", "entered")])
    assert CP.compare([day], [_res()])["rows"][0]["cls"] == "execution"
    assert CP.compare([day], [_res(why="missing")])["rows"][0]["cls"] == "data_feed"


def test_missed_trades_take_the_reason_of_the_app_decision():
    decs = [_dec("09:25:00", "Liquidity 5m FINNIFTY", "order_refused", "order_refused: same-direction cap: 2 puts"),
            _dec("10:00:01", "Liquidity 5m MIDCPNIFTY", "order_refused",
                 "order_refused: same-direction cap: the last call place is kept for R72 Breadth Donchian"),
            _dec("09:30:03", "Liquidity 5m", "order_refused",
                 "order_refused: no price to fill at; the order was cancelled"),
            _dec("14:16:47", "Liquidity 5m FINNIFTY", "day_lock", "stopped_for_today")]
    res = [_res(side="P", strike=24250.0, sig="09:25", entry="09:25", exit_="09:29", E=159.5, X=135.57),
           _res(und="MIDCPNIFTY", strike=13025.0, sig="10:00", entry="10:00", exit_="10:19", E=125.5, X=130.5),
           _res(und="BANKNIFTY", strike=55700.0, sig="09:30", entry="09:30", exit_="09:49", E=518.45, X=454.15),
           _res(und="MIDCPNIFTY", strike=13075.0, sig="14:20", entry="14:20", exit_="14:30", E=97.15, X=86.55),
           _res(und="MIDCPNIFTY", strike=13100.0, sig="11:00", entry="11:00", exit_="11:10", E=97.15, X=86.55)]
    rows = CP.compare([_diag(decisions=decs)], res)["rows"]
    assert [r["cls"] for r in rows] == ["rule", "rule", "data_feed", "rule", "unexplained"]
    assert "R72" in rows[1]["why"] and "loss limit" in rows[3]["why"]
    assert rows[0]["diff_pct"] == 15.0                            # a missed loser counts in the app's favour


def test_no_twin_only_from_its_first_session_and_longer_book_first():
    a = _res(day="2026-10-23", und="BANKNIFTY", book=15, side="P", strike=54500.0, sig="10:00", entry="10:00",
             exit_="15:10", E=364.15, X=532.05)
    b = dict(a, book=5, exit="11:35")
    early = dict(a, day="2026-10-19"), dict(b, day="2026-10-19")
    assert CP.twins([b, a]) == {0}                                 # the 5-min book's entry is the twin
    assert CP.twins(list(early)) == set()                          # 19 Oct: no-twin was not on yet
    assert CP.twins([]) == set()


def test_days_without_liquidity_decisions_are_not_covered():
    quiet = _diag(day="2026-10-24")
    assert CP.compare([quiet], [_res(day="2026-10-24")])["rows"] == []


def test_output_refused_inside_the_repository(tmp_path):
    with pytest.raises(SystemExit):
        CP.main(["x", str(tmp_path), str(tmp_path / "r.json"), str(HERE / "x.json")])
