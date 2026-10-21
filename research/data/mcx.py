#!/usr/bin/env python3
"""MCX futures and options from the data lake: loader, session rule and continuous futures (19 Oct 2026).

The lake keeps every MCX futures contract Dhan serves at 1 minute in `fut_1m_live_mcx.parquet` (one row per contract
and minute: symbol, expiry, security_id, ts, OHLC, volume, oi). Dhan serves a contract's minutes only while it is
listed, so the history of each contract starts at its listing and contracts that expired before they were first
downloaded are missing (see README "Known gaps").

Continuous series (one contract per day, the roll recorded):
  * rule "volume" (default, the file `fut_1m_mcx_continuous.parquet`): on each trading day the series holds the contract
    that traded the most lots on the PREVIOUS trading day among contracts not yet expired (switching away from the
    held contract only when another traded at least 1.5x its volume, so two thin far months do not flip). The choice uses only data known at the day's open, so there is no look-ahead.
  * rule "expiry": the nearest contract not yet expired (switches the day after expiry, like Dhan's daily expiryCode 0).
  Column `near_share` = the held contract's volume that day / Dhan's near-month daily volume: about 1 while the series
  holds the near month. Before `near_month_from` (per symbol, in the file's QA) it holds a FAR month, because the near
  contracts of that time expired before the lake first downloaded them: use the series from that day on.
  Every switch is a row of the rolls table: date, from/to contract, both contracts' closes at the last minute both
  traded before the switch, and the gap (to - from). `back_adjust` subtracts nothing from the newest prices and shifts
  older prices by the later gaps (additive), for indicators that must not see roll jumps.

Session (as android/engine/.../mcx/McxSession.kt): 09:00 to 23:30 while the US is on summer time, 09:00 to 23:55 while
it is on winter time; bars are stamped at the start of the minute, so the last regular bar is 23:29 / 23:54.

Usage:  python -I research/data/mcx.py build LAKE_DIR      (writes the continuous file and the contracts table)
"""
from __future__ import annotations

import glob
import os
import sys

import numpy as np
import pandas as pd

IST = "Asia/Kolkata"
FUTURES = ("GOLD", "GOLDM", "SILVER", "SILVERM", "CRUDEOIL", "CRUDEOILM", "NATURALGAS", "NATGASMINI",
           "COPPER", "ZINC", "ALUMINIUM")
OPTIONS = ("GOLD", "GOLDM", "SILVER", "SILVERM", "CRUDEOIL", "CRUDEOILM", "NATURALGAS", "NATGASMINI", "COPPER", "ZINC")
OPEN_MIN = 9 * 60
SWITCH = 1.5  # leave the held contract only for one that traded at least 1.5x its volume the previous day
CONT_FILE, CONTRACTS_FILE, ROLLS_FILE = "fut_1m_mcx_continuous.parquet", "fut_mcx_contracts.parquet", "fut_mcx_rolls.parquet"


def us_summer(d) -> bool:
    """US daylight-saving time on date d (second Sunday of March to first Sunday of November)."""
    d = pd.Timestamp(d).normalize()
    mar = pd.Timestamp(d.year, 3, 1)
    start = mar + pd.Timedelta(days=(6 - mar.weekday()) % 7 + 7)
    nov = pd.Timestamp(d.year, 11, 1)
    end = nov + pd.Timedelta(days=(6 - nov.weekday()) % 7)
    return bool(start <= d < end)


def close_min(d) -> int:
    """Minute of day at which the MCX session closes on date d (23:30 summer, 23:55 winter)."""
    return 23 * 60 + (30 if us_summer(d) else 55)


def in_session(ts: pd.Series) -> pd.Series:
    """True for bars inside 09:00 <= t < close (close by the US-time rule) on Monday to Friday."""
    t = ts.dt.tz_convert(IST) if ts.dt.tz is not None else ts
    hm = t.dt.hour * 60 + t.dt.minute
    days = t.dt.normalize().dt.tz_localize(None) if t.dt.tz is not None else t.dt.normalize()
    closes = days.map({d: close_min(d) for d in days.unique()})
    return (hm >= OPEN_MIN) & (hm < closes) & (t.dt.dayofweek < 5)


def _find(lake: str, name: str) -> list[str]:
    """Files called `name` (a glob) at the lake root or one dataset folder down (Drive layout)."""
    return sorted(set(glob.glob(os.path.join(lake, name)) + glob.glob(os.path.join(lake, "*", name))))


def load_futures(lake: str, symbols=None, session_only=False) -> pd.DataFrame:
    """Every MCX futures contract minute: symbol, expiry (date), security_id, ts (IST), OHLC, volume, oi."""
    fs = _find(lake, "fut_1m_live_mcx.parquet")
    if not fs:
        raise FileNotFoundError(f"fut_1m_live_mcx.parquet not under {lake}")
    df = pd.read_parquet(fs[0])
    df["symbol"] = df["symbol"].astype(str)
    if symbols is not None:
        df = df[df.symbol.isin([symbols] if isinstance(symbols, str) else list(symbols))]
    df["expiry"] = pd.to_datetime(df["expiry"].astype(str)).dt.date
    if session_only and len(df):
        df = df[in_session(df.ts)]
    return df.sort_values(["symbol", "expiry", "ts"]).reset_index(drop=True)


def contracts(fut: pd.DataFrame) -> pd.DataFrame:
    """One row per contract: symbol, expiry, security_id, first, last, minutes, traded_minutes, volume."""
    if not len(fut):
        return pd.DataFrame(columns=["symbol", "expiry", "security_id", "first", "last", "minutes",
                                     "traded_minutes", "volume"])
    g = fut.assign(_t=fut.volume > 0).groupby(["symbol", "expiry", "security_id"], observed=True)
    return g.agg(first=("ts", "min"), last=("ts", "max"), minutes=("ts", "size"), traded_minutes=("_t", "sum"),
                 volume=("volume", "sum")).reset_index()


def choose(daily: pd.DataFrame, rule: str = "volume") -> pd.Series:
    """Contract (expiry) held on each trading day of one symbol.

    daily: one row per (day, expiry) with the day's volume, for contracts that have a bar that day. Returns a Series
    day -> expiry. "volume": the contract with the most volume on the previous trading day among contracts with
    expiry >= day, switching away from the held contract only when the other traded at least SWITCH (1.5) times its
    volume (it may step back to a nearer expiry: early in the lake's history only far months exist, and the
    near month must win once it is the busiest); on the first day (no previous day) the nearest expiry.
    "expiry": the nearest expiry >= day that has a bar that day."""
    days = sorted(daily.day.unique())
    by_day = {d: g for d, g in daily.groupby("day")}
    held, out, prev = None, {}, None
    for d in days:
        today = by_day[d]
        alive = today[today.expiry >= d]
        if not len(alive):
            prev = d
            continue
        if rule == "expiry":
            held = alive.expiry.min()
        else:
            cand = None
            if prev is not None:
                y = by_day[prev]
                y = y[(y.expiry >= d) & y.expiry.isin(alive.expiry)]
                if len(y) and y.volume.max() > 0:
                    cand = y.sort_values(["volume", "expiry"], ascending=[False, True]).expiry.iloc[0]
                    hv = y.volume[y.expiry == held].sum() if held in set(y.expiry) else None
                    if hv is not None and cand != held and y.volume.max() < SWITCH * hv:
                        cand = held  # not clearly busier: stay (two thin far months would flip daily)
            if cand is None:  # first day, or nothing traded yesterday: keep the held contract if alive, else nearest
                cand = held if held in set(alive.expiry) else alive.expiry.min()
            held = cand
        out[d] = held
        prev = d
    return pd.Series(out, name="expiry", dtype=object)


def continuous(fut: pd.DataFrame, symbol: str, rule: str = "volume") -> tuple[pd.DataFrame, pd.DataFrame]:
    """(minutes, rolls) of one symbol's continuous series. minutes: the held contract's bars with a `roll` flag on the
    first bar after a switch. rolls: day, from_expiry, to_expiry, at (last minute both traded before the switch),
    from_close, to_close, gap = to_close - from_close (NaN when they never traded the same minute)."""
    f = fut[fut.symbol == symbol]
    cols = ["day", "from_expiry", "to_expiry", "from_security_id", "to_security_id", "at", "from_close", "to_close", "gap"]
    if not len(f):
        return f.assign(roll=pd.Series(dtype=bool)), pd.DataFrame(columns=cols)
    f = f.assign(day=f.ts.dt.tz_convert(IST).dt.date)
    daily = f.groupby(["day", "expiry"], as_index=False).volume.sum()
    pick = choose(daily, rule)
    m = f.merge(pick.rename("held").rename_axis("day").reset_index(), on="day")
    m = m[m.expiry == m.held].drop(columns=["held"]).sort_values("ts").reset_index(drop=True)
    sw = m.expiry.ne(m.expiry.shift()) & m.index.to_series().gt(0)
    m["roll"] = sw.to_numpy()
    rolls = []
    for i in np.flatnonzero(m.roll.to_numpy()):
        a, b = m.iloc[i - 1], m.iloc[i]
        both = f[(f.expiry.isin([a.expiry, b.expiry])) & (f.ts < b.ts)].pivot_table(
            index="ts", columns="expiry", values="close", aggfunc="last")
        both = both[[a.expiry, b.expiry]].dropna() if {a.expiry, b.expiry} <= set(both.columns) else both.iloc[:0]
        if len(both):
            at = both.index[-1]
            fc, tc = float(both.loc[at, a.expiry]), float(both.loc[at, b.expiry])
        else:
            at, fc, tc = pd.NaT, np.nan, np.nan
        rolls.append(dict(day=b.day, from_expiry=a.expiry, to_expiry=b.expiry, from_security_id=int(a.security_id),
                          to_security_id=int(b.security_id), at=at, from_close=fc, to_close=tc, gap=tc - fc))
    return m.drop(columns=["day"]), pd.DataFrame(rolls, columns=cols)


def near_share(minutes: pd.DataFrame, daily: pd.DataFrame | None) -> pd.Series:
    """Per minute row: the held contract's volume that day / Dhan's near-month (expiryCode 0) daily volume that day
    (same units, lots; checked equal on 12-16 Oct 2026). About 1 when the series holds the near month; near 0 when it
    holds a far month because the near contracts of that time had expired before they could be downloaded. NaN when
    the daily series has no volume for that day."""
    if daily is None or not len(minutes):
        return pd.Series(np.nan, index=minutes.index, dtype="float32")
    d = minutes.ts.dt.tz_convert(IST).dt.date
    held = minutes.groupby(d).volume.sum()
    dv = daily.assign(_d=pd.to_datetime(daily.date).dt.date).groupby("_d").volume.sum()
    ratio = (held / dv.reindex(held.index)).where(lambda x: np.isfinite(x))
    return d.map(ratio).astype("float32")


def near_month_from(minutes: pd.DataFrame, share: float = 0.5):
    """First day on which the held contract traded at least `share` of the near month's volume (None if never)."""
    if "near_share" not in minutes or not len(minutes):
        return None
    d = minutes.ts.dt.tz_convert(IST).dt.date
    ok = minutes.near_share.groupby(d).first() >= share
    return min(ok.index[ok]) if ok.any() else None


def back_adjust(minutes: pd.DataFrame, rolls: pd.DataFrame) -> pd.DataFrame:
    """Additive back-adjustment: bars before each roll are shifted by that roll's gap, so the newest contract keeps its
    real prices and the series has no roll jumps. Rolls with an unknown gap shift nothing (stated in `adj`)."""
    out = minutes.copy()
    out["adj"] = 0.0
    for _, r in rolls.iterrows():
        if pd.notna(r.gap):
            out.loc[out.ts.dt.tz_convert(IST).dt.date < r.day, "adj"] += r.gap
    for c in ("open", "high", "low", "close"):
        out[c] = out[c] + out["adj"]
    return out


def load_continuous(lake: str, symbol: str, rule: str = "volume", adjust: bool = False):
    """(minutes, rolls) for one symbol. The volume rule reads the built file when present; else it is computed."""
    fs, rs = _find(lake, CONT_FILE), _find(lake, ROLLS_FILE)
    if rule == "volume" and fs and rs:
        m = pd.read_parquet(fs[0], filters=[("symbol", "==", symbol)])
        r = pd.read_parquet(rs[0], filters=[("symbol", "==", symbol)]).drop(columns=["symbol"])
        m["symbol"] = m["symbol"].astype(str)
        m["expiry"] = pd.to_datetime(m["expiry"].astype(str)).dt.date
        for c in ("day", "from_expiry", "to_expiry"):
            r[c] = pd.to_datetime(r[c].astype(str)).dt.date
    else:
        m, r = continuous(load_futures(lake, symbol), symbol, rule)
    return (back_adjust(m, r) if adjust else m), r


def load_options(lake: str, symbol: str, years=None) -> pd.DataFrame:
    """MCX rolling options (nearest monthly expiry, ATM-5..ATM+5, 1 minute; Dhan starts mid-2025)."""
    fs = _find(lake, f"opt_{symbol}_MONTH_*.parquet")
    if years is not None:
        ys = {int(y) for y in ([years] if isinstance(years, int) else years)}
        fs = [f for f in fs if int(f[:-8].rsplit("_", 1)[-1]) in ys]
    if not fs:
        return pd.DataFrame()
    return pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True).sort_values(["ts", "offset", "side"],
                                                                                      kind="stable").reset_index(drop=True)


def build(lake: str, out: str | None = None) -> dict:
    """Write the continuous file (volume rule, every symbol), the rolls table and the contracts table."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import lake_build as LB
    out = out or lake
    fut = load_futures(lake)
    dfs = _find(lake, "fut_daily_continuous.parquet")
    daily = pd.read_parquet(dfs[0]) if dfs else None
    if daily is not None:
        daily = daily[daily.expiry_code == 0] if "expiry_code" in daily else daily
    parts, rparts, stats = [], [], {}
    outside = 0
    for s in sorted(fut.symbol.unique()):
        f = fut[fut.symbol == s]
        ok = in_session(f.ts)
        outside += int((~ok).sum())
        m, r = continuous(f[ok], s)
        m["near_share"] = near_share(m, None if daily is None else daily[daily.symbol.astype(str) == s])
        parts.append(m)
        rparts.append(r.assign(symbol=s))
        nf = near_month_from(m)
        stats[s] = dict(rows=int(len(m)), first=str(m.ts.min()), last=str(m.ts.max()), rolls=int(len(r)),
                        near_month_from=str(nf) if nf else None,
                        rows_from_near_month=int((m.ts.dt.tz_convert(IST).dt.date >= nf).sum()) if nf else 0)
    cont = pd.concat(parts, ignore_index=True)
    cont["symbol"] = cont["symbol"].astype("category")
    cont["expiry"] = cont["expiry"].astype(str)
    q = LB.qa(cont, ["symbol"], True)
    q["per_symbol"] = stats
    q["rows_dropped"] = {"outside_mcx_session": outside}
    LB.write(cont, out, CONT_FILE, dict(dataset="fut_1m_mcx_continuous", rule="volume (previous day, no look-ahead)",
                                        source="built from fut_1m_live_mcx.parquet", qa=q))
    rolls = pd.concat(rparts, ignore_index=True)
    for c in ("day", "from_expiry", "to_expiry"):
        rolls[c] = rolls[c].astype(str)
    LB.write(rolls, out, ROLLS_FILE, dict(dataset="fut_mcx_rolls", source="mcx.py build",
                                          qa={"rows": int(len(rolls))}))
    ct = contracts(fut)
    ct["expiry"] = ct["expiry"].astype(str)
    LB.write(ct, out, CONTRACTS_FILE, dict(dataset="fut_mcx_contracts", source="mcx.py build",
                                           qa={"rows": int(len(ct))}))
    return stats


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "build":
        for k, v in build(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None).items():
            print(k, v)
    else:
        raise SystemExit(__doc__)
