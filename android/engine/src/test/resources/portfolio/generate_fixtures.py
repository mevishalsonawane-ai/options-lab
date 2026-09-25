"""
Reference fixtures for the Kotlin port of IraAlgo's Portfolio Backtester,
Portfolio Analyzer and SIP Backtester (com.optionslab.engine.portfolio).

Runs the real Python service functions on synthetic daily bars and dumps the
inputs and outputs as gzipped JSON. Only the data access is replaced: the
DuckDB read is swapped for the fixture bars, the broker holdings call for fixed
rows, and today's date for a fixed one. Every computation is IraAlgo's own.

Each scenario is run twice. ``api`` is the payload exactly as the endpoint
returns it (display rounding included); ``raw`` is the same run with every
``round()`` and the service's ``_clean``/``_curve`` rounding turned into the
identity, so the Kotlin port can be checked at full precision as well.

Run from any scratch directory (the service imports create sqlite/log files in
the working directory):

    cd <scratch> && API_KEY_PEPPER=$(printf '0%.0s' {1..64}) \
      DATABASE_URL=sqlite:///x.db SANDBOX_DATABASE_URL=sqlite:///s.db \
      LOGS_DATABASE_URL=sqlite:///l.db LATENCY_DATABASE_URL=sqlite:///t.db \
      LOG_DIR=log PYTHONPATH=/home/user/finalproducttradingapp \
      uv run --project /home/user/finalproducttradingapp python \
      <this file> <output dir>
"""

from __future__ import annotations

import builtins
import gzip
import json
import math
import sys
import types
from datetime import date, datetime

import numpy as np
import pandas as pd

import dotenv

dotenv.load_dotenv = lambda *a, **k: False
dotenv.main.load_dotenv = dotenv.load_dotenv

import portfolio.compare as p_compare  # noqa: E402
import portfolio.crisis as p_crisis  # noqa: E402
import portfolio.data as p_data  # noqa: E402
import portfolio.grouping as p_grouping  # noqa: E402
import portfolio.health as p_health  # noqa: E402
import portfolio.holdings as p_holdings  # noqa: E402
import services.portfolio_service as ps  # noqa: E402
import services.sip_service as ss  # noqa: E402
import sip.analytics as s_analytics  # noqa: E402
import sip.engine as s_engine  # noqa: E402
from openstatz import stats as st  # noqa: E402
from scipy.special import ndtri  # noqa: E402
from sip.schedule import build_schedule  # noqa: E402
from sip.xirr import XirrError, xirr  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "."

# ── synthetic market ─────────────────────────────────────────────────────────

rng = np.random.default_rng(20240917)
days = pd.bdate_range("2018-06-01", "2025-09-30")
# Market holidays: every symbol is shut on these.
holidays = set(rng.choice(len(days), size=110, replace=False).tolist())
sessions = [d for i, d in enumerate(days) if i not in holidays]
n = len(sessions)

market = rng.normal(0.00045, 0.010, n)
# A crash and a rebound, placed on the real COVID dates so the crisis tables fire.
for i, d in enumerate(sessions):
    if pd.Timestamp("2020-02-20") <= d <= pd.Timestamp("2020-03-23"):
        market[i] -= 0.012
    if pd.Timestamp("2020-03-24") <= d <= pd.Timestamp("2020-06-30"):
        market[i] += 0.004
    if pd.Timestamp("2022-01-17") <= d <= pd.Timestamp("2022-06-17"):
        market[i] -= 0.0015
bank = rng.normal(0.0, 0.009, n)

specs = {
    # symbol: (start price, market beta, bank loading, idio vol, drift)
    "RELIANCE": (1150.0, 1.05, 0.0, 0.011, 0.0002),
    "INFY": (690.0, 0.85, 0.0, 0.012, 0.0003),
    "HDFCBANK": (1020.0, 1.0, 1.0, 0.007, 0.0),
    "ICICIBANK": (330.0, 1.1, 1.05, 0.008, 0.0001),
    "SBIN": (280.0, 1.2, 1.1, 0.010, 0.0),
    "NIFTYBEES": (118.0, 1.0, 0.0, 0.0015, 0.0),
    "GOLDBEES": (29.5, -0.05, 0.0, 0.008, 0.0003),
    "LATECO": (412.0, 1.3, 0.0, 0.018, 0.0004),
    "MRF": (68000.0, 0.7, 0.0, 0.010, 0.0001),
    "SPLITCO": (2400.0, 0.9, 0.0, 0.012, 0.0002),
    "SUSPENDED": (540.0, 0.8, 0.2, 0.013, 0.0001),
}


def bars_for(symbol: str, rets: np.ndarray, start_price: float, keep) -> list:
    out = []
    price = start_price
    for i, d in enumerate(sessions):
        prev = price
        price = prev * (1.0 + rets[i])
        if not keep(i, d):
            continue
        close = round(price, 2)
        o = round(prev * (1.0 + rets[i] * 0.3), 2)
        h = round(max(o, close) * (1.0 + abs(rets[i]) * 0.5 + 0.002), 2)
        low = round(min(o, close) * (1.0 - abs(rets[i]) * 0.5 - 0.002), 2)
        vol = int(1e5 + (abs(rets[i]) * 1e7))
        out.append([d.date().isoformat(), o, h, low, close, vol])
    return out


bars: dict[str, list] = {}
for sym, (p0, beta, bl, iv, drift) in specs.items():
    r = beta * market + bl * bank + rng.normal(drift, iv, n)
    if sym == "SPLITCO":
        # An unadjusted 1:2 split: the kind of step the loader flags.
        r[sessions.index(pd.Timestamp("2023-06-13"))] = -0.5
    gaps = set()
    if sym == "INFY":
        gaps = set(rng.choice(n, size=9, replace=False).tolist())
    if sym == "SUSPENDED":
        # A suspension: five weeks without a single session, so weekly and
        # monthly bins come up empty and SIP dates pile onto one session.
        gaps = {i for i, d in enumerate(sessions)
                if pd.Timestamp("2022-07-04") <= d <= pd.Timestamp("2022-08-05")}
    listed = pd.Timestamp("2021-03-15") if sym == "LATECO" else None
    bars[sym] = bars_for(
        sym, r, p0,
        lambda i, d, g=gaps, lst=listed: i not in g and (lst is None or d >= lst),
    )

bars["INFY"].insert(400, list(bars["INFY"][400]))  # a re-ingested session
bars["INFY"][401][4] = round(bars["INFY"][401][4] * 1.001, 2)  # last write wins
bars["RELIANCE"][777][4] = None  # an unparseable close is dropped

# The benchmark trades on the same calendar but is missing a few sessions, which
# the service forward-fills onto the portfolio's sessions.
bench_missing = set(rng.choice(n, size=6, replace=False).tolist())
bars["NIFTY"] = bars_for(
    "NIFTY", market + rng.normal(0.0, 0.001, n), 10800.0,
    lambda i, d: i not in bench_missing,
)
bars["SENSEX"] = bars_for("SENSEX", market * 0.98, 36000.0, lambda i, d: i >= 30)

NAMES = {
    "RELIANCE": "RELIANCE INDUSTRIES LTD", "INFY": "INFOSYS LIMITED",
    "HDFCBANK": "HDFC BANK LTD", "ICICIBANK": "ICICI BANK LTD.",
    "SBIN": "STATE BANK OF INDIA", "NIFTYBEES": "NIP IND ETF NIFTY BEES",
    "GOLDBEES": "NIP IND ETF GOLD BEES", "LATECO": "LATE LISTING CO",
    "MRF": "MRF LTD", "SPLITCO": "SPLIT CO LTD",
}

# ── data access replaced by the fixture ─────────────────────────────────────


def fake_closes(symbols, exchanges, start_date, end_date, interval):
    out = {}
    lo, hi = date.fromisoformat(start_date), date.fromisoformat(end_date)
    for symbol in symbols:
        rows = [
            b for b in bars.get(symbol, [])
            if lo <= date.fromisoformat(b[0]) <= hi
        ]
        if not rows:
            raise p_data.MissingHistory(
                f"{symbol}: no {interval} history in Historify for "
                f"{start_date}..{end_date}. Ingest it, or run with the broker "
                f"API as the source."
            )
        series = pd.Series(
            pd.to_numeric(pd.Series([b[4] for b in rows]), errors="coerce").to_numpy(),
            index=pd.DatetimeIndex(pd.to_datetime([b[0] for b in rows])).normalize(),
            name=symbol,
        ).dropna()
        out[symbol] = series[~series.index.duplicated(keep="last")].sort_index()
    return out


p_data._closes_from_duckdb = fake_closes
ps._symbol_names = lambda symbols: {s: NAMES.get(s, "") for s in symbols}


class FixedDate(date):
    @classmethod
    def today(cls):
        return date(2025, 9, 26)


ps.date = FixedDate

# ── raw mode: every display rounding becomes the identity ───────────────────

ROUNDED_MODULES = [ps, ss, p_grouping, p_health, p_crisis, p_compare, p_holdings,
                   s_engine, s_analytics]
orig_clean, orig_curve = ps._clean, ps._curve


def raw_clean(value):
    if isinstance(value, float):
        if value != value or value in (float("inf"), float("-inf")):
            return None
        return value
    if isinstance(value, dict):
        return {k: raw_clean(v) for k, v in value.items()}
    if isinstance(value, list):
        return [raw_clean(v) for v in value]
    return value


def raw_curve(series):
    return [{"date": s.date().isoformat(), "value": float(v)} for s, v in series.items()]


def set_raw(on: bool) -> None:
    for mod in ROUNDED_MODULES:
        if on:
            mod.round = lambda x, nd=None: builtins.round(x) if nd is None else x
        elif "round" in mod.__dict__:
            del mod.round
    ps._clean = raw_clean if on else orig_clean
    ps._curve = raw_curve if on else orig_curve


def jsonable(value):
    if isinstance(value, dict):
        return {str(k): jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [jsonable(v) for v in value]
    if isinstance(value, (np.floating,)):
        value = float(value)
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.bool_,)):
        return bool(value)
    if isinstance(value, float) and not math.isfinite(value):
        return {"nonfinite": repr(value)}
    if isinstance(value, (date, datetime, pd.Timestamp)):
        return str(value)
    return value


def dump(name: str, payload) -> None:
    with gzip.open(f"{OUT}/{name}.json.gz", "wt", encoding="utf-8") as fh:
        json.dump(jsonable(payload), fh, separators=(",", ":"), allow_nan=False)
    print("wrote", name)


def guarded(fn, generic, **kwargs):
    """The resource layer's catch-all: an uncaught exception becomes a 500."""
    try:
        return fn(**kwargs)
    except Exception as exc:  # noqa: BLE001
        return False, {"status": "error", "message": generic,
                       "exception": type(exc).__name__}, 500


def both(fn, generic="Backtest failed.", **kwargs):
    p_data.clear_price_cache()
    set_raw(False)
    ok, api, status = guarded(fn, generic, **kwargs)
    p_data.clear_price_cache()
    set_raw(True)
    ok2, raw, status2 = guarded(fn, generic, **kwargs)
    set_raw(False)
    assert ok == ok2 and status == status2
    return {"ok": ok, "status": status, "api": api, "raw": raw}


# ── portfolio scenarios ─────────────────────────────────────────────────────

PORTFOLIO = {
    "p1_quarterly_india_costs_rf": dict(
        holdings=[
            {"symbol": "RELIANCE", "exchange": "NSE", "weight": 40},
            {"symbol": "INFY", "exchange": "NSE", "weight": 25},
            {"symbol": "HDFCBANK", "exchange": "NSE", "weight": 20},
            {"symbol": "GOLDBEES", "exchange": "NSE", "weight": 15},
        ],
        start_date="2019-01-01", end_date="2025-06-30", benchmark="NIFTY",
        rebalance="quarterly", initial_capital=1_000_000.0, risk_free_rate=0.06,
    ),
    "p2_late_listing_monthly_drift_flat": dict(
        holdings=[
            {"symbol": "LATECO", "exchange": "NSE", "weight": 0.3},
            {"symbol": "ICICIBANK", "exchange": "NSE", "weight": 0.3},
            {"symbol": "SBIN", "exchange": "BSE", "weight": 0.2},
            {"symbol": "NIFTYBEES", "exchange": "NSE", "weight": 0.2},
        ],
        start_date="2019-06-01", end_date="2025-09-30", rebalance="monthly",
        drift_band=0.05, cost_model="flat_bps", cost_bps=10.0, slippage=0.0005,
    ),
    "p3_bse_overrides_short": dict(
        holdings=[
            {"symbol": "MRF", "exchange": "BSE", "weight": 60},
            {"symbol": "GOLDBEES", "exchange": "NSE", "weight": 40},
        ],
        start_date="2024-11-01", end_date="2025-06-15", benchmark="SENSEX",
        benchmark_exchange="BSE_INDEX", rebalance="yearly", drift_band=0.02,
        brokerage_pct=0.0003, cost_exchange="BSE",
        charge_overrides={"stt": {"rate": 0.0012}, "unknown": {"rate": 1.0},
                          "stamp_duty": {"rate": None, "cap": 5.0}},
        gst_rate=0.12, slippage=0.0002, initial_capital=250_000.0, risk_free_rate=0.05,
    ),
    "p4_banks_split_never": dict(
        holdings=[
            {"symbol": "HDFCBANK", "exchange": "NSE", "weight": 35},
            {"symbol": "ICICIBANK", "exchange": "NSE", "weight": 30},
            {"symbol": "SBIN", "exchange": "NSE", "weight": 25},
            {"symbol": "SPLITCO", "exchange": "NSE", "weight": 10},
        ],
        start_date="2018-06-01", end_date="2025-09-30", benchmark="NIFTY",
        rebalance="never",
    ),
    "p5_tiny_window_missing_benchmark": dict(
        holdings=[
            {"symbol": "RELIANCE", "exchange": "NSE", "weight": 50},
            {"symbol": "INFY", "exchange": "NSE", "weight": 50},
        ],
        start_date="2025-07-01", end_date="2025-08-12", benchmark="NOSUCHINDEX",
        rebalance="monthly", cost_model="flat_bps", cost_bps=25.0,
    ),
    "p6_brokerage_flat_override_monthly": dict(
        holdings=[
            {"symbol": "NIFTYBEES", "exchange": "NSE", "weight": 70},
            {"symbol": "GOLDBEES", "exchange": "NSE", "weight": 30},
        ],
        start_date="2020-01-01", end_date="2023-12-31", benchmark="NIFTY",
        rebalance="monthly", charge_overrides={"brokerage": {"flat": 20.0}},
        risk_free_rate=0.065, initial_capital=500_000.0,
    ),
    "p7_suspension_late_benchmark": dict(
        holdings=[
            {"symbol": "SUSPENDED", "exchange": "NSE", "weight": 55},
            {"symbol": "RELIANCE", "exchange": "NSE", "weight": 45},
        ],
        start_date="2018-06-01", end_date="2023-03-31", benchmark="SENSEX",
        benchmark_exchange="BSE_INDEX", rebalance="quarterly", drift_band=0.03,
        cost_model="flat_bps", cost_bps=15.0,
    ),
    "p8_nine_holdings_yearly": dict(
        holdings=[
            {"symbol": s, "exchange": "NSE", "weight": w}
            for s, w in [("RELIANCE", 14), ("INFY", 12), ("HDFCBANK", 11), ("ICICIBANK", 11),
                         ("SBIN", 9), ("NIFTYBEES", 13), ("GOLDBEES", 10), ("MRF", 8),
                         ("SUSPENDED", 12)]
        ],
        start_date="2018-09-01", end_date="2025-09-30", benchmark="NIFTY",
        rebalance="yearly", drift_band=0.04, risk_free_rate=0.055,
        initial_capital=2_500_000.0, mc_simulations=300, walk_window_years=2.0,
        walk_step_years=0.25,
    ),
    # Error paths.
    "e1_duplicate_symbol": dict(
        holdings=[{"symbol": "INFY", "weight": 1}, {"symbol": "infy", "weight": 1}],
        start_date="2020-01-01", end_date="2021-01-01",
    ),
    "e2_missing_history": dict(
        holdings=[{"symbol": "RELIANCE", "weight": 1}, {"symbol": "GHOST", "weight": 1}],
        start_date="2020-01-01", end_date="2021-01-01",
    ),
    "e3_zero_weights": dict(
        holdings=[{"symbol": "RELIANCE", "weight": 0}, {"symbol": "INFY", "weight": 0}],
        start_date="2020-01-01", end_date="2021-01-01",
    ),
    "e4_bad_exchange": dict(
        holdings=[{"symbol": "RELIANCE", "exchange": "MCX", "weight": 1}],
        start_date="2020-01-01", end_date="2021-01-01",
    ),
    "e5_no_overlap": dict(
        holdings=[{"symbol": "LATECO", "weight": 1}, {"symbol": "RELIANCE", "weight": 1}],
        start_date="2019-01-01", end_date="2021-03-15",
    ),
    "e6_negative_override": dict(
        holdings=[{"symbol": "RELIANCE", "weight": 1}],
        start_date="2020-01-01", end_date="2021-01-01",
        charge_overrides={"stt": {"rate": -0.01}},
    ),
}

# ── SIP scenarios ───────────────────────────────────────────────────────────

SIP = {
    "s1_monthly_stepup_benchmark": dict(
        symbol="RELIANCE", exchange="NSE", start_date="2019-01-01",
        end_date="2025-06-30", amount=10000, day_of_month=5, step_up_percent=10.0,
        benchmark="NIFTY",
    ),
    "s2_weekly_flat_bps": dict(
        symbol="INFY", exchange="NSE", start_date="2020-02-03",
        end_date="2024-02-02", amount=2500, frequency="weekly", slippage=0.001,
    ),
    "s3_quarterly_late_listing": dict(
        symbol="LATECO", exchange="NSE", start_date="2019-01-01",
        end_date="2025-09-30", amount=15000, frequency="quarterly",
        day_of_month=28, brokerage_percent=0.1, brokerage_flat=5.0,
        benchmark="NIFTY", cost_exchange="BSE", gst_rate=0.12,
    ),
    "s4_fortnightly_expensive_share": dict(
        symbol="MRF", exchange="BSE", start_date="2021-01-01",
        end_date="2023-12-31", amount=40000, frequency="fortnightly",
        charge_overrides={"brokerage": {"rate": 0.0005, "cap": 20.0}},
        benchmark="GHOSTINDEX",
    ),
    "s5_monthly_no_grids": dict(
        symbol="GOLDBEES", exchange="NSE", start_date="2018-06-01",
        end_date="2025-09-30", amount=5000, include_grids=False, slippage=0.0005,
    ),
    "s6_long_crisis_window": dict(
        symbol="HDFCBANK", exchange="NSE", start_date="2018-06-15",
        end_date="2025-09-30", amount=20000, day_of_month=15, step_up_percent=5.0,
        benchmark="NIFTY",
    ),
    "s7_weekly_through_suspension": dict(
        symbol="SUSPENDED", exchange="NSE", start_date="2021-01-04",
        end_date="2023-12-29", amount=3000, frequency="weekly", step_up_percent=8.0,
        benchmark="SENSEX", benchmark_exchange="BSE_INDEX",
    ),
    "se1_cannot_afford": dict(
        symbol="MRF", exchange="NSE", start_date="2021-01-01",
        end_date="2021-12-31", amount=5000,
    ),
    "se2_bad_day": dict(
        symbol="INFY", exchange="NSE", start_date="2021-01-01",
        end_date="2021-12-31", amount=5000, day_of_month=31,
    ),
    "se3_end_before_start": dict(
        symbol="INFY", exchange="NSE", start_date="2021-01-01",
        end_date="2020-12-31", amount=5000,
    ),
    "se4_missing_symbol": dict(
        symbol="GHOST", exchange="NSE", start_date="2021-01-01",
        end_date="2021-12-31", amount=5000,
    ),
    "se5_bad_amount": dict(
        symbol="INFY", exchange="NSE", start_date="2021-01-01",
        end_date="2021-12-31", amount=-5,
    ),
    # IraAlgo's SIP path calls .charge() on the flat model, which it lacks, so
    # the endpoint answers 500. Recorded so the port fails the same way.
    "se7_flat_bps_crashes": dict(
        symbol="INFY", exchange="NSE", start_date="2021-01-01",
        end_date="2021-12-31", amount=5000, cost_model="flat_bps", cost_bps=5.0,
    ),
    "se6_too_short": dict(
        symbol="INFY", exchange="NSE", start_date="2021-01-02",
        end_date="2021-01-03", amount=5000, day_of_month=15,
    ),
}

# ── analyzer (live holdings) ────────────────────────────────────────────────

HOLDING_ROWS = [
    {"symbol": "reliance", "exchange": "NSE", "quantity": "40", "average_price": "2210.5",
     "last_price": 2450.0, "pnl": "9580", "product": "CNC"},
    {"symbol": "INFY", "exchange": "NSE", "quantity": 25, "average_price": 1400,
     "ltp": "1510.25", "pnl": 2756.25, "product": "CNC"},
    {"symbol": "HDFCBANK", "exchange": "NSE", "quantity": 30, "average_price": 1500,
     "last_price": None, "pnl": 1500, "product": "CNC"},
    {"symbol": "GOLDBEES", "exchange": "BSE", "quantity": 500, "average_price": 50,
     "last_price": 62.5, "pnl": 6250},
    {"symbol": "GOLDM", "exchange": "MCX", "quantity": 1, "average_price": 70000,
     "last_price": 72000, "pnl": 2000},
    {"symbol": "", "exchange": "NSE", "quantity": 1, "average_price": 1, "last_price": 1},
    {"symbol": "ZERO", "exchange": "NSE", "quantity": 0, "average_price": 1, "last_price": 1},
    {"symbol": "NOPRICE", "exchange": "NSE", "quantity": 3, "average_price": "-",
     "last_price": "", "pnl": 0},
]

ANALYZER = {
    "a1_default": dict(rows=HOLDING_ROWS, kwargs=dict(lookback_days=365)),
    "a2_long_no_bench_rf": dict(
        rows=[r for r in HOLDING_ROWS if r["symbol"] != "HDFCBANK"],
        kwargs=dict(lookback_days=1500, benchmark=None, risk_free_rate=0.04),
    ),
    "a3_no_cost_basis": dict(
        rows=[
            {"symbol": "SBIN", "exchange": "NSE", "quantity": 100, "last_price": 810.0, "pnl": 1200},
            {"symbol": "ICICIBANK", "exchange": "NSE", "quantity": 50, "last_price": 1250.0, "pnl": -300},
        ],
        kwargs=dict(lookback_days=200, benchmark="SENSEX", benchmark_exchange="BSE_INDEX"),
    ),
    "a4_only_unpriceable": dict(
        rows=[{"symbol": "GOLDM", "exchange": "MCX", "quantity": 1, "last_price": 72000, "pnl": 0}],
        kwargs=dict(lookback_days=365),
    ),
    "a5_nothing_usable": dict(rows=[{"symbol": "X", "quantity": 0}], kwargs={}),
    "a6_broker_error": dict(rows=None, kwargs={}),
}


def run_analyzer(rows, kwargs):
    fake = types.ModuleType("services.holdings_service")

    def get_holdings(**_kw):
        if rows is None:
            return False, {"status": "error", "message": "broker session expired"}, 401
        return True, {"status": "success", "data": {"holdings": rows}}, 200

    fake.get_holdings = get_holdings
    sys.modules["services.holdings_service"] = fake
    return both(ps.analyse_live_holdings, **kwargs)


# ── unit-level references ───────────────────────────────────────────────────


def units() -> dict:
    out: dict = {}
    out["ndtri"] = [[p, float(ndtri(p))] for p in
                    (0.05, 0.01, 0.001, 1e-10, 0.2, 0.5, 0.7, 0.95, 0.999, 0.9999999)]

    g = np.random.default_rng(12345)
    seqs = []
    for high in (7, 1000, 1581, 2**31, 5_000_000_000, 1):
        seqs.append({"high": high, "values": g.integers(0, high, size=40).tolist()})
    out["pcg64_seed12345"] = seqs
    g2 = np.random.default_rng(7)
    out["pcg64_seed7_next64"] = [str(int(v)) for v in g2.bit_generator.random_raw(8)]

    qrng = np.random.default_rng(3)
    quant = []
    for size in (1, 2, 5, 17, 256, 1001):
        a = qrng.normal(0, 1, size)
        quant.append({
            "values": a.tolist(),
            "quantile": {str(q): float(pd.Series(a).quantile(q)) for q in (0.05, 0.25, 0.5, 0.75, 0.95)},
            "percentile": {str(q): float(np.percentile(a, q)) for q in (5, 25, 75, 95)},
            "median": float(np.median(a)),
            "sum": float(a.sum()),
            "std": float(pd.Series(a).std()),
            "skew": float(pd.Series(a).skew()),
            "kurt": float(pd.Series(a).kurtosis()),
        })
    out["numeric"] = quant

    cases = [
        [("2008-01-01", -10000), ("2008-03-01", 2750), ("2008-10-30", 4250),
         ("2009-02-15", 3250), ("2009-04-01", 2750)],
        [(f"2020-{m:02d}-01", -5000) for m in range(1, 13)] + [("2021-01-01", 64000)],
        [("2020-01-01", -100000), ("2023-01-01", 100000)],
        [("2021-01-01", -1000), ("2022-01-01", 2000)],
        [("2020-01-01", -1000), ("2021-01-01", 2000)],
        [("2020-01-01", -100000), ("2022-01-01", 60000)],
        [],
        [("2020-01-01", -1000)],
        [("2020-01-01", -1000), ("2020-01-01", 2000)],
        [("2020-01-01", -1000), ("2021-01-01", -2000)],
        [("2020-01-01", 1000), ("2021-01-01", 2000)],
        [("2020-01-01", -1000), ("2020-01-02", 5000)],
        [("2020-01-01", -1000), ("2020-06-01", 1)],
        [("2020-01-01", -1000), ("2020-02-01", 0.0), ("2021-01-01", 1500)],
        [("2021-01-01", 3000), ("2020-01-01", -1000), ("2020-06-30", -1000)],
    ]
    xr = []
    for flows in cases:
        try:
            xr.append({"flows": flows, "rate": xirr(flows)})
        except XirrError as exc:
            xr.append({"flows": flows, "error": str(exc)})
    out["xirr"] = xr

    sess = pd.bdate_range("2020-01-01", "2022-12-31")
    sched = []
    for kw in (
        dict(frequency="monthly", day_of_month=5),
        dict(frequency="quarterly", day_of_month=28, step_up_percent=12.5),
        dict(frequency="weekly"),
        dict(frequency="fortnightly", step_up_percent=7.0),
    ):
        s = build_schedule(sess, date(2020, 1, 3), date(2022, 11, 20), 1000.0, **kw)
        sched.append({"kwargs": kw, "installments": [
            [str(i.requested), str(i.executed), i.amount] for i in s]})
    out["schedule"] = sched

    # openstatz on awkward series: all-positive, a constant, one that never
    # exceeds 1 as a price path, an rf > 0 run, and a series looking like prices.
    idx = pd.bdate_range("2021-01-04", periods=300)
    srng = np.random.default_rng(99)
    series = {
        "normal": pd.Series(srng.normal(0.0005, 0.012, 300), index=idx),
        "positive": pd.Series(np.abs(srng.normal(0.001, 0.002, 300)), index=idx),
        "losing": pd.Series(-np.abs(srng.normal(0.001, 0.003, 300)), index=idx),
        "with_zeros": pd.Series(np.where(srng.random(300) < 0.3, 0.0,
                                         srng.normal(0, 0.01, 300)), index=idx),
    }
    stats = {}
    for name, r in series.items():
        row = {"returns": r.tolist(), "dates": [d.date().isoformat() for d in r.index]}
        for rf in (0.0, 0.07):
            row[f"summary_rf{rf}"] = ps._clean(__import__("portfolio.analytics", fromlist=["summary"]).summary(r, None, rf=rf))
            row[f"rolling_sharpe_rf{rf}"] = st.rolling_sharpe(r, rf=rf, rolling_period=60).dropna().tolist()
        row["rolling_vol"] = st.rolling_volatility(r, rolling_period=60).dropna().tolist()
        row["drawdown"] = st.to_drawdown_series(r).tolist()
        row["max_drawdown_prices"] = float(st.max_drawdown((1.0 + r).cumprod()))
        row["max_drawdown_returns"] = float(st.max_drawdown(r))
        stats[name] = row
    out["openstatz"] = stats
    return out


def main() -> None:
    dump("prices", {"bars": bars, "names": NAMES})
    for name, kwargs in PORTFOLIO.items():
        dump(name, {"kind": "portfolio", "request": kwargs,
                    **both(ps.run_portfolio_backtest, **kwargs)})
    for name, kwargs in SIP.items():
        dump(name, {"kind": "sip", "request": kwargs,
                    **both(ss.run_sip_backtest, "SIP backtest failed.", **kwargs)})
    for name, spec in ANALYZER.items():
        dump(name, {"kind": "analyzer", "request": spec,
                    "today": "2025-09-26", **run_analyzer(spec["rows"], spec["kwargs"])})
    dump("units", units())


if __name__ == "__main__":
    main()
