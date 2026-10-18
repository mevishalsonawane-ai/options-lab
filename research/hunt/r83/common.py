"""R83 shared harness (PREREG.md, 18 Oct 2026): data loaders (US benchmark proxy minutes, USD/INR, MCX daily), MCX
session hours, strict MCX futures costs, the split / walk-forward folds, random-twin p, Benjamini-Hochberg across all
variants, the pass gates and the one-time final lock.

A trade is a dict with at least: entry_ts (naive IST Timestamp), exit_ts, side (+1/-1), net (Rs after costs), gross,
why (exit reason). Charges, BH, twin p and summary() are R53's (research/hunt/r53/common.py), reused unchanged.
Environment: R83_DATA (default /dev/shm): proxy/<SYM>.parquet, yahoo/<SYM>.json, lake/... .
"""
from __future__ import annotations

import json
import os
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r53 import common as R53

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
DATA = Path(os.environ.get("R83_DATA", "/dev/shm"))

DESIGN = (date(2016, 1, 1), date(2022, 12, 31))
DESIGN_DAILY = (date(2012, 1, 1), date(2022, 12, 31))
FOLDS = [(date(y, 1, 1), date(y, 12, 31)) for y in (2019, 2020, 2021, 2022)]
FINAL = (date(2023, 1, 1), date(2026, 10, 13))
MCX_REAL = (date(2025, 8, 5), date(2026, 10, 13))
N_TWINS = 200
SEED = 20261018
N_VARIANTS = 32                       # PREREG section 4; amendments add to it

# futures half-spread (fraction of price, paid on entry and on exit) and extra stop slippage (PREREG section 2)
HALF_SPREAD = {"CRUDE": 0.0003, "NG": 0.0006, "COPPER": 0.0005}
STOP_SLIP = 0.0002
# lot units and the proxy -> MCX price factor (MCX price = proxy x USD/INR x factor)
LOT = {"CRUDE": 10.0, "NG": 250.0, "COPPER": 2500.0}
FACTOR = {"CRUDE": 1.0, "NG": 1.0, "COPPER": 2.20462}
PROXY = {"CRUDE": "LIGHTCMDUSD", "NG": "GASCMDUSD", "COPPER": "COPPERCMDUSD", "BRENT": "BRENTCMDUSD"}
PROXY_SCALE = 1e3                     # Dukascopy integer prices / 1e3 (checked against Yahoo in test_r83.py data notes)

summary = R53.summary
twin_p = R53.twin_p
bh = R53.bh
day_of = R53.day_of
mcx_fut_charges = R53.mcx_fut_charges
mcx_opt_charges = R53.mcx_opt_charges


# ------------------------------------------------------------------------------------------------ split
def part_of(d, daily: bool = False) -> str | None:
    d = day_of(d)
    lo = (DESIGN_DAILY if daily else DESIGN)[0]
    if lo <= d <= DESIGN[1]:
        return "design"
    if FINAL[0] <= d <= FINAL[1]:
        return "final"
    return None


def in_part(trades: list[dict], part: str, daily: bool = False) -> list[dict]:
    return [t for t in trades if part_of(t["entry_ts"], daily) == part]


# ------------------------------------------------------------------------------------------------ MCX hours
def us_winter(d) -> bool:
    """McxSession.usWinter: New York is on standard time that day."""
    from zoneinfo import ZoneInfo
    ts = pd.Timestamp(day_of(d)) + pd.Timedelta(hours=12)
    return ts.tz_localize(ZoneInfo("America/New_York")).dst() == pd.Timedelta(0)


def mcx_close_min(d) -> int:
    """MCX close, minutes after midnight IST: 23:30 in US summer time, 23:55 in US winter time."""
    return 23 * 60 + (55 if us_winter(d) else 30)


MCX_OPEN_MIN = 9 * 60


def flat_min(d) -> int:
    """Intraday rules are flat 15 minutes before the close (PREREG section 1)."""
    return mcx_close_min(d) - 15


def et_to_ist_min(d, hh: int, mm: int) -> int:
    """A New York clock time on day d as minutes after midnight IST (DST-exact). May exceed 24 h (next IST day)."""
    from zoneinfo import ZoneInfo
    t = pd.Timestamp(year=d.year, month=d.month, day=d.day, hour=hh, minute=mm, tz=ZoneInfo("America/New_York"))
    ti = t.tz_convert("Asia/Kolkata")
    return int((ti.normalize().tz_localize(None) - pd.Timestamp(d)).days * 1440 + ti.hour * 60 + ti.minute)


# ------------------------------------------------------------------------------------------------ loaders
def load_proxy(key: str, data: Path | None = None) -> pd.DataFrame:
    """US benchmark proxy 1-minute bars: columns o, h, l, c (USD), ts (naive IST), day (date, IST), m (minute of the IST
    day). Only minutes with volume (as downloaded). Sorted, de-duplicated."""
    data = Path(data or DATA)
    df = pd.read_parquet(data / "proxy" / f"{PROXY[key]}.parquet")
    df = df.drop_duplicates("t").sort_values("t")
    for c in "ohlc":
        df[c] = df[c] / PROXY_SCALE
    ts = pd.to_datetime(df.t, unit="s", utc=True).dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    out = pd.DataFrame({"ts": ts.values, "o": df.o.values, "h": df.h.values, "l": df.l.values, "c": df.c.values})
    out["day"] = out.ts.dt.date
    out["m"] = out.ts.dt.hour * 60 + out.ts.dt.minute
    return out.reset_index(drop=True)


def mcx_hours(df: pd.DataFrame) -> pd.DataFrame:
    """Keep minutes inside MCX hours (09:00 to the close, IST) on weekdays."""
    close = {d: mcx_close_min(d) for d in df.day.unique()}
    cm = df.day.map(close)
    wk = pd.to_datetime(df.day).dt.weekday < 5
    return df[(df.m >= MCX_OPEN_MIN) & (df.m < cm) & wk].reset_index(drop=True)


def yahoo_daily(sym: str, data: Path | None = None) -> pd.DataFrame:
    """Yahoo chart JSON (saved as yahoo/y_<sym>.json) -> date, open, high, low, close (exchange dates)."""
    data = Path(data or DATA)
    js = json.loads((data / "yahoo" / f"y_{sym}.json").read_text())["chart"]["result"][0]
    q = js["indicators"]["quote"][0]
    df = pd.DataFrame({"t": js["timestamp"], "open": q["open"], "high": q["high"], "low": q["low"], "close": q["close"]})
    tz = js["meta"].get("exchangeTimezoneName", "UTC")
    df["date"] = pd.to_datetime(df.t, unit="s", utc=True).dt.tz_convert(tz).dt.date
    return df.dropna(subset=["close"]).drop_duplicates("date", keep="last").sort_values("date").reset_index(drop=True)


_INR = None


def usdinr_prev(d, data: Path | None = None) -> float:
    """USD/INR close of the last Yahoo day strictly before d (no look-ahead)."""
    global _INR
    if _INR is None:
        y = yahoo_daily("INR=X", data)
        y = y[(y.close > 30) & (y.close < 150)]
        _INR = pd.Series(y.close.values, index=pd.to_datetime(y.date))
    s = _INR[_INR.index < pd.Timestamp(day_of(d))]
    return float(s.iloc[-1]) if len(s) else float(_INR.iloc[0])


def mcx_daily(sym: str, data: Path | None = None) -> pd.DataFrame:
    """Lake fut_daily_continuous, expiry_code 0, for CRUDEOIL / NATURALGAS: date, open, high, low, close, oi."""
    data = Path(data or DATA)
    d = pd.read_parquet(data / "lake" / "fut_daily_continuous" / "fut_daily_continuous.parquet")
    d = d[(d.symbol == sym) & (d.expiry_code == 0)].copy()
    d["date"] = pd.to_datetime(d.date).dt.date
    return d.sort_values("date").drop_duplicates("date").reset_index(drop=True)


# ------------------------------------------------------------------------------------------------ costs
def fut_net(mkt: str, side: int, entry_px: float, exit_px: float, notional_inr: float, stop: bool = False) -> tuple[float, float]:
    """(gross, net) in Rs for one futures round trip of one lot. notional_inr = entry price in Rs x lot units.
    Costs: McxCosts charges on both legs, half-spread on entry and exit, STOP_SLIP extra on a stop exit."""
    gross = side * (exit_px / entry_px - 1.0) * notional_inr
    exit_notional = notional_inr * exit_px / entry_px
    buy_rs, sell_rs = (notional_inr, exit_notional) if side > 0 else (exit_notional, notional_inr)
    cost = mcx_fut_charges(buy_rs, sell_rs) + HALF_SPREAD[mkt] * (notional_inr + exit_notional)
    if stop:
        cost += STOP_SLIP * exit_notional
    return gross, gross - cost


def proxy_notional(mkt: str, px_usd: float, d) -> float:
    """Rs value of one MCX lot at a proxy price: price x previous USD/INR x factor x lot units."""
    return px_usd * usdinr_prev(d) * FACTOR[mkt] * LOT[mkt]


def mcx_notional(mkt: str, px_rs: float) -> float:
    return px_rs * LOT[mkt]


# ------------------------------------------------------------------------------------------------ indicators
def atr(df: pd.DataFrame, n: int = 14) -> pd.Series:
    """Wilder-free simple ATR of daily bars (columns high, low, close): mean of the true range over n bars, including
    the bar itself. Callers shift it by one bar to use 'the previous day's ATR'."""
    pc = df.close.shift(1)
    tr = pd.concat([df.high - df.low, (df.high - pc).abs(), (df.low - pc).abs()], axis=1).max(axis=1)
    return tr.rolling(n, min_periods=n).mean()


def daily_from_minutes(df: pd.DataFrame) -> pd.DataFrame:
    """IST-day OHLC from proxy minutes (any hours given)."""
    g = df.groupby("day")
    return pd.DataFrame({"open": g.o.first(), "high": g.h.max(), "low": g.l.min(), "close": g.c.last()}).reset_index()


# ------------------------------------------------------------------------------------------------ walk-forward
def net_between(tr: list[dict], a: date, b: date) -> float:
    return float(sum(t["net"] for t in tr if a <= day_of(t["entry_ts"]) <= b))


def walk_forward(variant_trades: dict[str, list[dict]], primary: str, daily: bool = False) -> dict:
    """PREREG section 3: for each fold year, the variant with the best design net before the fold (from the design start);
    sum of the picked variants' fold nets. Also the design-pick (best full-design net; ties -> primary)."""
    lo = (DESIGN_DAILY if daily else DESIGN)[0]
    picks, wf = [], 0.0
    for a, b in FOLDS:
        end = date(a.year - 1, 12, 31)
        sc = {v: net_between(tr, lo, end) for v, tr in variant_trades.items()}
        best = max(sc.values())
        pick = primary if sc[primary] == best else sorted(v for v, s in sc.items() if s == best)[0]
        picks.append(pick)
        wf += net_between(variant_trades[pick], a, b)
    full = {v: net_between(tr, lo, DESIGN[1]) for v, tr in variant_trades.items()}
    best = max(full.values())
    frozen = primary if full[primary] == best else sorted(v for v, s in full.items() if s == best)[0]
    return {"wf_net": wf, "wf_picks": picks, "design_net": full, "frozen": frozen,
            "fold_net": {v: [net_between(tr, a, b) for a, b in FOLDS] for v, tr in variant_trades.items()}}


# ------------------------------------------------------------------------------------------------ twins
def twin_dates(day: date, pool: list[date], rng: np.random.Generator, n: int = N_TWINS) -> list[date]:
    """n random days from pool in the same calendar year as day (pool should exclude event days for event rules)."""
    same = [d for d in pool if d.year == day.year and d != day]
    if not same:
        return []
    return list(rng.choice(np.array(same, dtype=object), size=n, replace=True))


# ------------------------------------------------------------------------------------------------ final lock
class FinalReadError(RuntimeError):
    pass


def open_final(idea: str) -> None:
    """Record the one final read of an idea; a second read raises."""
    p = RESULTS / f"final_read_{idea}.json"
    if p.exists():
        raise FinalReadError(f"final already read for {idea}: {p.read_text()}")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps({"idea": idea, "read_utc": datetime.now(timezone.utc).isoformat(timespec="seconds")}))


# ------------------------------------------------------------------------------------------------ gates
def top5_removed(trades: list[dict]) -> float:
    x = sorted((t["net"] for t in trades), reverse=True)
    return float(sum(x[5:]))


def gates(final_net, base_final_net, wf_net, base_wf_net, p, q, top5_rest, n_final, min_trades: int = 30) -> dict:
    g = {
        "final_pos_and_beats_base": final_net > 0 and final_net > base_final_net,
        "wf_pos_and_beats_base": wf_net > 0 and wf_net > base_wf_net,
        "twin_p<0.10": p is not None and p < 0.10,
        "bh_q<0.10": q is not None and q < 0.10,
        "not_top5": top5_rest > 0,
        f"trades>={min_trades}": n_final >= min_trades,
    }
    return {"gates": g, "verdict": "PASS" if all(g.values()) else "fail"}


def save_json(name: str, obj) -> Path:
    RESULTS.mkdir(parents=True, exist_ok=True)
    p = RESULTS / name

    def conv(o):
        if isinstance(o, (np.integer,)):
            return int(o)
        if isinstance(o, (np.floating,)):
            return float(o)
        if isinstance(o, (date, datetime, pd.Timestamp)):
            return str(o)
        raise TypeError(type(o))
    p.write_text(json.dumps(obj, indent=1, default=conv))
    return p
