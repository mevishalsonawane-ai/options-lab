"""R103 data: one record per SESSION (every trading day, not only expiries) of the lake's rolling nearest-expiry option
series for NIFTY and BANKNIFTY, built straight from the lake's Parquet (PREREG.md section 1).

    python3 -I research/hunt/r103_selling/r103data.py build [UND ...]    # writes $R103_OUT/days_<UND>_<YEAR>.pkl

Adapted from R98 (research/hunt/r98/r98data.py), which kept expiry days only. Here every session is kept, arrays are
float32 (5 years x 2 indices fit in memory), and each day carries its expiry flag and days-to-expiry.

Grid: row t = the 1-minute bar that STARTS at 09:15 + t (375 rows). Nothing is filled: a minute without a bar is NaN.
A strike exists in a minute only while it is within ATM+-10 of that minute's spot.

Expiry days come from the data (R98's rule on the 15:15 ATM time value and the next session's jump). The expiry
date of a contract is fixed weeks ahead, so flagging the day is not look-ahead; the flag is only used to pick the
15:05 close and the EXPIRY day set.
"""
from __future__ import annotations

import os
import pickle
import sys
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

LAKE = Path(os.environ.get("R103_LAKE", "/home/user/lake"))
OUT = Path(os.environ.get("R103_OUT", "/home/user/lake/r103"))
UNDS = ("NIFTY", "BANKNIFTY")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}
# one option file per year, never WEEK and MONTH of the same contracts (README "Same contract in two files")
FILES = {
    "NIFTY": {y: f"opt_NIFTY_WEEK_{y}.parquet" for y in range(2021, 2027)},
    "BANKNIFTY": {**{y: f"opt_BANKNIFTY_WEEK_{y}.parquet" for y in range(2021, 2025)},
                  **{y: f"opt_BANKNIFTY_MONTH_{y}.parquet" for y in (2025, 2026)}},
}
# Lots in force on each date (research/hunt/r51/r51cfg.py LOTS; NSE's Dec 2025 revision verified 22 Oct 2026 against
# the Zerodha bulletin of 4 Oct 2025: NIFTY 75 -> 65, BANKNIFTY 35 -> 30, first new-lot weekly expiry 6 Jan 2026).
LOTS = {
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
}

N = 375
O, H, L, C, V, OI, IV = range(7)
TV_MAX = 0.6          # % of spot: an expiry day's ATM time value at 15:15 is below this ...
TV_JUMP = 2.0         # ... and the next session's is more than twice it (the roll to the next contract)
STRIKE_SLACK = 12     # strikes from this index's close beyond which a row is a mislabel (another index's rows)


def minute(hh: int, mm: int) -> int:
    """Grid row of the bar that STARTS at hh:mm."""
    return hh * 60 + mm - (9 * 60 + 15)


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


@dataclass
class Day:
    day: date
    und: str
    ix: np.ndarray                              # (375, 4) index O H L C (NaN: no bar), float32
    opts: dict = field(default_factory=dict)    # (strike, 'C'/'P') -> (375, 7) O H L C V OI IV, float32
    vix: np.ndarray | None = None               # (375,) VIX close
    prev_close: float = np.nan
    vix_prev: float = np.nan
    expiry: bool = False
    dte: int = -1                               # sessions to the next expiry (0 on the expiry day; -1 unknown)
    n_clean: int = 0


def expiry_days(tv: pd.Series) -> list[date]:
    """tv: ATM time value at 15:15 in % of spot, by session date (R98's rule)."""
    tv = tv.sort_index()
    nxt = tv.shift(-1)
    ok = (tv < TV_MAX) & (nxt > TV_JUMP * tv.clip(lower=1e-6)) & nxt.notna()
    out = [d for d, v in ok.items() if v]
    pos = {d: i for i, d in enumerate(tv.index)}
    drop = {a if tv[a] > tv[b] else b for a, b in zip(out, out[1:]) if pos[b] - pos[a] == 1}
    out = [d for d in out if d not in drop]
    if len(tv) and tv.iloc[-1] < TV_MAX / 4:
        out.append(tv.index[-1])
    return out


def _tv_1515(o: pd.DataFrame) -> pd.Series:
    x = o[(o.offset == 0) & (o.m == minute(15, 15))]
    if x.empty:
        return pd.Series(dtype=float)
    p = x.pivot_table(index="d", columns="side", values="close", aggfunc="first", observed=True)
    s = x.groupby("d").agg(spot=("spot", "first"), k=("strike", "first"))
    j = p.join(s)
    if "C" not in j or "P" not in j:
        return pd.Series(dtype=float)
    return (j.C + j.P - (j.spot - j.k).abs()) / j.spot * 100


def _read(path, cols=None) -> pd.DataFrame:
    df = pd.read_parquet(path, columns=cols)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["ts"] = ts
    df["d"] = ts.dt.date
    df["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
    return df[(df.m >= 0) & (df.m < N)]


def _index_frames(und: str) -> pd.DataFrame:
    parts = [_read(p) for p in sorted((LAKE / "index_1m").glob(f"index_1m_{und}_*.parquet"))]
    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()


def _grid(g: pd.DataFrame, cols) -> np.ndarray:
    a = np.full((N, len(cols)), np.nan, dtype=np.float32)
    a[g.m.values] = g[cols].values.astype(np.float32)
    return a


def dte_of(sessions: list[date], expiries: set[date]) -> dict[date, int]:
    """Sessions to the next expiry for every session (0 on an expiry day; -1 after the last known expiry)."""
    out, nxt = {}, None
    for d in reversed(sessions):
        if d in expiries:
            nxt = 0
        elif nxt is not None:
            nxt += 1
        out[d] = -1 if nxt is None else nxt
    return out


def build(und: str, years=None) -> dict[int, list[Day]]:
    ix = _index_frames(und)
    vx = _index_frames("INDIAVIX")
    ixd = dict(tuple(ix.groupby("d")))
    vxd = dict(tuple(vx.groupby("d")))
    sess = sorted(ixd)
    step = STEP[und]
    tvs, frames = [], {}
    for y, name in sorted(FILES[und].items()):
        p = LAKE / "options_rolling_1m" / name
        if not p.exists():
            continue
        o = _read(p)
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[(o.strike - o.spot).abs() <= STRIKE_SLACK * step]
        tvs.append(_tv_1515(o))
        frames[y] = o
    tv = pd.concat(tvs).sort_index()
    tv = tv[~tv.index.duplicated()]
    exp = set(expiry_days(tv))
    dte = dte_of(sess, exp)
    out = {}
    for y, o in frames.items():
        if years and y not in years:
            continue
        days = []
        for d, g in o.groupby("d", sort=True):
            if d not in ixd:
                continue
            i = sess.index(d)
            ixa = _grid(ixd[d], ["open", "high", "low", "close"])
            prev = ixd[sess[i - 1]] if i > 0 else None
            pc = float(prev.close.iloc[-1]) if prev is not None and len(prev) else np.nan
            vprev = float(vxd[sess[i - 1]].close.iloc[-1]) if i > 0 and sess[i - 1] in vxd else np.nan
            va = _grid(vxd[d], ["close"])[:, 0] if d in vxd else np.full(N, np.nan, dtype=np.float32)
            ixc = pd.Series(ixa[:, 3].astype(float))
            g = g[(g.strike - ixc.reindex(g.m.values).values).abs() <= STRIKE_SLACK * step]
            g = g.drop_duplicates(["m", "strike", "side"], keep="first")
            opts = {}
            for (k, s), h in g.groupby(["strike", "side"], observed=True):
                opts[(float(k), s)] = _grid(h, ["open", "high", "low", "close", "volume", "oi", "iv"])
            dd = Day(d, und, ixa, opts, va, pc, vprev, d in exp, dte[d])
            dd.n_clean = clean(dd)
            days.append(dd)
        out[y] = days
    return out


CLEAN_MULT, CLEAN_ADD = 1.25, 0.10


def clean(day: Day) -> int:
    """R25's bad-print rule, same-minute only (no look-ahead): an option cannot be worth more than a nearer-the-money
    option of the same side and expiry. Each bar's open, high and close are capped at 1.25 x + Rs 0.10 the high of the
    nearest stored nearer strike in the same minute. Returns the number of bars changed."""
    n = 0
    for side in ("C", "P"):
        ks = sorted(k for k, s in day.opts if s == side)
        if side == "P":
            ks = ks[::-1]
        cap = np.full(N, np.nan, dtype=np.float32)
        for k in ks:
            a = day.opts[(k, side)]
            lim = cap * CLEAN_MULT + CLEAN_ADD
            bad = ~np.isnan(lim) & ~np.isnan(a[:, H]) & (a[:, H] > lim + 1e-6)
            if bad.any():
                n += int(bad.sum())
                for j in (O, H, C):
                    a[bad, j] = np.fmin(a[bad, j], lim[bad])
                a[bad, L] = np.fmin(a[bad, L], a[bad, C])
            ok = ~np.isnan(a[:, H])
            cap[ok] = a[ok, H]
    return n


def path(und: str, year: int) -> Path:
    return OUT / f"days_{und}_{year}.pkl"


def load(und: str, years=None) -> list[Day]:
    out = []
    for y in sorted(FILES[und]):
        if years and y not in years:
            continue
        p = path(und, y)
        if p.exists():
            out.extend(pickle.loads(p.read_bytes()))
    out.sort(key=lambda x: x.day)
    return out


def have(und: str) -> bool:
    return any(path(und, y).exists() for y in FILES[und])


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import r103data as _self          # pickle Day under its module name, not __main__
    if sys.argv[1:2] == ["build"]:
        OUT.mkdir(parents=True, exist_ok=True)
        for u in (sys.argv[2:] or UNDS):
            by_year = _self.build(u)
            for y, ds in sorted(by_year.items()):
                path(u, y).write_bytes(pickle.dumps(ds))
                ne = sum(d.expiry for d in ds)
                nb = sum(d.n_clean for d in ds)
                print(u, y, len(ds), "sessions", ne, "expiries", ds[0].day if ds else "-", "..",
                      ds[-1].day if ds else "-", "cleaned bars", nb, flush=True)
