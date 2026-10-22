"""R98 data: one record per EXPIRY day of the lake's rolling nearest-expiry series, for all five index option
underlyings (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX), built straight from the lake's Parquet.

    python3 -I research/hunt/r98/r98data.py build [UND ...]    # writes $R98_OUT/days_<UND>.pkl

Adapted from R25 (research/hunt/r25/r25data.py on branch claude/r25-hero-deep), which covered NIFTY, BANKNIFTY
weeklies and SENSEX. Changes: all five indices; BANKNIFTY / FINNIFTY / MIDCPNIFTY monthly expiries after the Nov 2024
end of their weeklies are kept (Dhan's WEEK series follows the nearest available, monthly, expiry), and no
research.brain dependency.

The lake (Dhan "rollingoption") stores, at each minute, the contracts that were ATM-10 .. ATM+10 at that minute of the
nearest expiry. It has no expiry column. An expiry day is found from the data (`expiry_days`): the ATM time value at
15:15 is tiny on an expiry day and jumps the next session, when the series rolls to the next contract.

Grid: row t = the 1-minute bar that STARTS at 09:15 + t (375 rows). Nothing is filled: a minute without a bar is NaN.
A strike exists in a minute only while it is within ATM+-10 of that minute's spot.
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

LAKE = Path(os.environ.get("R98_LAKE", "/home/user/lake/r98data"))
OUT = Path(os.environ.get("R98_OUT", "/home/user/lake/r98"))
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}

N = 375
O, H, L, C, V, OI, IV = range(7)
TV_MAX = 0.6          # % of spot: an expiry day's ATM time value at 15:15 is below this ...
TV_JUMP = 2.0         # ... and the next session's is more than twice it (the roll to the next contract)
STRIKE_SLACK = 12     # strikes from this index's spot beyond which a row is a mislabel
# Before this date FINNIFTY's rolling series is unreliable (15:15 time value missing or negative on most days, Thursdays
# flagged as expiries while FINNIFTY expired on Tuesdays); its first clean expiry is 19 Oct 2021.
FIRST = {"FINNIFTY": date(2021, 10, 18)}


def minute(hh: int, mm: int) -> int:
    """Grid row of the bar that STARTS at hh:mm."""
    return hh * 60 + mm - (9 * 60 + 15)


@dataclass
class Day:
    day: date
    und: str
    ix: np.ndarray                              # (375, 4) index O H L C (NaN: no bar)
    opts: dict = field(default_factory=dict)    # (strike, 'C'/'P') -> (375, 7) O H L C V OI IV
    vix: np.ndarray | None = None               # (375,) VIX close
    prev_close: float = np.nan
    vix_prev: float = np.nan
    prev_range: float = np.nan                  # previous session's high - low
    n_clean: int = 0


def expiry_days(tv: pd.Series) -> list[date]:
    """tv: ATM time value at 15:15 in % of spot, by session date. An expiry day has tv < TV_MAX and the next session's
    tv > TV_JUMP x its own. The last session (no next) is called an expiry only if tv < TV_MAX / 4 (no roll seen yet)."""
    tv = tv.sort_index()
    nxt = tv.shift(-1)
    ok = (tv < TV_MAX) & (nxt > TV_JUMP * tv.clip(lower=1e-6)) & nxt.notna()
    out = [d for d, v in ok.items() if v]
    # two candidates on adjacent sessions cannot both be expiries (MIDCPNIFTY 7 and 8 Jun 2023, a thin day after a real
    # expiry): keep the one with the smaller time value
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
    parts = [_read(p) for p in sorted(LAKE.glob(f"index_1m_{und}_*.parquet"))]
    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()


def _grid(g: pd.DataFrame, cols) -> np.ndarray:
    a = np.full((N, len(cols)), np.nan)
    a[g.m.values] = g[cols].values.astype(float)
    return a


def build(und: str) -> list[Day]:
    ix = _index_frames(und)
    vx = _index_frames("INDIAVIX")
    ixd = dict(tuple(ix.groupby("d")))
    vxd = dict(tuple(vx.groupby("d")))
    sess = sorted(ixd)
    step = STEP[und]
    tvs, frames = [], {}
    for p in sorted(LAKE.glob(f"opt_{und}_WEEK_*.parquet")):
        o = _read(p)
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[(o.strike - o.spot).abs() <= STRIKE_SLACK * step]
        tvs.append(_tv_1515(o))
        frames[p] = o
    tv = pd.concat(tvs).sort_index()
    tv = tv[~tv.index.duplicated()]
    exp = {d for d in expiry_days(tv) if d >= FIRST.get(und, date.min)}
    out = []
    for p, o in frames.items():
        o = o[o.d.isin(exp)]
        for d, g in o.groupby("d", sort=True):
            if d not in ixd:
                continue
            i = sess.index(d)
            ixa = _grid(ixd[d], ["open", "high", "low", "close"])
            prev = ixd[sess[i - 1]] if i > 0 else None
            pc = float(prev.close.iloc[-1]) if prev is not None and len(prev) else np.nan
            pr = float(prev.high.max() - prev.low.min()) if prev is not None and len(prev) else np.nan
            vprev = float(vxd[sess[i - 1]].close.iloc[-1]) if i > 0 and sess[i - 1] in vxd else np.nan
            va = _grid(vxd[d], ["close"])[:, 0] if d in vxd else np.full(N, np.nan)
            # rows of another index can sit in the file with their own spot (BANKEX rows in SENSEX, 5 Apr 2024): keep a
            # strike only while it is within 12 strikes of THIS index's close in that minute
            ixc = pd.Series(ixa[:, 3])
            g = g[(g.strike - ixc.reindex(g.m.values).values).abs() <= STRIKE_SLACK * step]
            g = g.drop_duplicates(["m", "strike", "side"], keep="first")
            opts = {}
            for (k, s), h in g.groupby(["strike", "side"]):
                opts[(float(k), s)] = _grid(h, ["open", "high", "low", "close", "volume", "oi", "iv"])
            dd = Day(d, und, ixa, opts, va, pc, vprev, pr)
            dd.n_clean = clean(dd)
            out.append(dd)
    out.sort(key=lambda x: x.day)
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
        cap = np.full(N, np.nan)
        for k in ks:
            a = day.opts[(k, side)]
            lim = cap * CLEAN_MULT + CLEAN_ADD
            bad = ~np.isnan(lim) & ~np.isnan(a[:, H]) & (a[:, H] > lim + 1e-9)
            if bad.any():
                n += int(bad.sum())
                for j in (O, H, C):
                    a[bad, j] = np.fmin(a[bad, j], lim[bad])
                a[bad, L] = np.fmin(a[bad, L], a[bad, C])
            ok = ~np.isnan(a[:, H])
            cap[ok] = a[ok, H]
    return n


def path(und: str) -> Path:
    return OUT / f"days_{und}.pkl"


def load(und: str) -> list[Day]:
    return pickle.loads(path(und).read_bytes())


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import r98data as _self          # pickle Day under its module name, not __main__
    if sys.argv[1:2] == ["build"]:
        OUT.mkdir(parents=True, exist_ok=True)
        for u in (sys.argv[2:] or UNDS):
            ds = _self.build(u)
            path(u).write_bytes(pickle.dumps(ds))
            nb = sum(d.n_clean for d in ds)
            print(u, len(ds), "expiries", ds[0].day, "..", ds[-1].day, "cleaned bars", nb, flush=True)
