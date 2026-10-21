"""R3 data: one record per trading session of an index, built straight from the lake's Parquet (2021-2026).

    python3 research/hunt/r3orb/r3data.py build [UND ...]    # writes <OUT>/sess_<UND>.pkl

The lake's options (Dhan "rollingoption", opt_<UND>_WEEK_<YEAR>) hold, at each minute, the contracts that were
ATM-10 .. ATM+10 of the NEAREST expiry on or after the day. That is the ORB family's own contract rule
(OrbRules.expiryOnOrAfter: on an expiry day the option expiring that day). BANKNIFTY / FINNIFTY / MIDCPNIFTY weeklies ended
in Nov 2024; after that the nearest expiry is the monthly, as the app would trade.

An expiry day is found from the data (as R25): the ATM time value at 15:15 is under 0.6% of spot and the next session's
is more than twice it (the series rolls to the next contract).

Grid: row t = the 1-minute bar that STARTS at 09:15 + t (375 rows); row t is known once that minute is over.
Nothing is filled: a minute without a bar is NaN. Only the strikes within KEEP steps of the 09:20 5-minute bar's ATM are
kept (the ORB family's strike is that ATM, the 1-ITM variant one step in); `edge` keeps the outermost stored strike's
close on each side for a held strike that leaves the stored range.

Holdout safety: `load(und)` refuses a holdout index (r3cfg.HOLDOUT_UNDS) unless part="holdout", which only
`study.py holdout` passes.
"""
from __future__ import annotations

import math
import pickle
import sys
from dataclasses import dataclass, field
from datetime import date

import numpy as np
import pandas as pd

import r3cfg as K

N = 375
O, H, L, C, V, OI = range(6)
TV_MAX = 0.6
TV_JUMP = 2.0
STRIKE_SLACK = 12
KEEP = 4                     # strikes kept on each side of the day's ATM
SPOT_ROW = 9                 # close of the 09:20-09:25 5-minute bar (OrbRules.strikeBar) = the 09:24 minute's close


def minute(hh: int, mm: int) -> int:
    """Grid row of the 1-minute bar that STARTS at hh:mm."""
    return hh * 60 + mm - (9 * 60 + 15)


@dataclass
class Sess:
    day: date
    und: str
    ix: np.ndarray                               # (375, 4) index O H L C
    vix: np.ndarray                              # (375,) India VIX close (NaN when missing)
    opts: dict = field(default_factory=dict)     # (strike, 'C'|'P') -> (375, 6) float32 O H L C V OI
    edge: np.ndarray | None = None               # (375, 2) close of the outermost stored strike: [:,0] CE side, [:,1] PE
    expiry: bool = False                         # the nearest expiry is today
    lot: int = 0
    prev: tuple = (np.nan, np.nan, np.nan, np.nan)   # previous session O H L C
    atr14: float = np.nan                        # mean true range of the 14 sessions before today
    vix_prev: float = np.nan                     # previous session's last VIX
    atm: float = np.nan                          # ATM of the 09:20 bar's close (half-up)


def atm_strike(spot: float, step: float) -> float:
    """OrbRules.atmStrike: half-up, floor(spot / step + 0.5) * step."""
    return math.floor(spot / step + 0.5) * step


def expiry_days(tv: pd.Series) -> set:
    tv = tv.sort_index()
    nxt = tv.shift(-1)
    ok = (tv < TV_MAX) & (nxt > TV_JUMP * tv.clip(lower=1e-6)) & nxt.notna()
    return {d for d, v in ok.items() if v}


def _read(path) -> pd.DataFrame:
    df = pd.read_parquet(path)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["d"] = ts.dt.date
    df["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
    return df[(df.m >= 0) & (df.m < N)].drop(columns=["ts"])


def _index(und: str) -> dict:
    parts = [_read(p) for p in sorted(K.LAKE.glob(f"index_1m_{und}_*.parquet"))]
    if not parts:
        return {}
    df = pd.concat(parts, ignore_index=True)
    out = {}
    for d, g in df.groupby("d", sort=True):
        a = np.full((N, 4), np.nan)
        a[g.m.values] = g[["open", "high", "low", "close"]].values.astype(float)
        out[d] = a
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


def build(und: str) -> list[Sess]:
    step = K.STEP[und]
    ixd = _index(und)
    vxd = _index("INDIAVIX")
    sess = sorted(d for d in ixd if K.FIRST_DAY <= d <= K.LAST_DAY or d < K.FIRST_DAY)
    files = sorted(K.LAKE.glob(f"opt_{und}_WEEK_*.parquet"))
    frames, tvs = [], []
    for p in files:
        o = _read(p)
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[(o.strike - o.spot).abs() <= STRIKE_SLACK * step]
        tvs.append(_tv_1515(o))
        frames.append(o)
    tv = pd.concat(tvs).sort_index()
    tv = tv[~tv.index.duplicated()]
    exp = expiry_days(tv)
    # daily bars for ATR14 / previous day
    daily = {d: (np.nanmax(a[:, 1]) if np.isfinite(a[:, 1]).any() else np.nan, np.nanmin(a[:, 2]) if np.isfinite(a[:, 2]).any() else np.nan,
                 a[np.isfinite(a[:, 3]), 3][-1] if np.isfinite(a[:, 3]).any() else np.nan,
                 a[np.isfinite(a[:, 0]), 0][0] if np.isfinite(a[:, 0]).any() else np.nan) for d, a in ixd.items()}
    days_all = sorted(ixd)
    tr = {}
    for i, d in enumerate(days_all):
        h, lo, c, _ = daily[d]
        pc = daily[days_all[i - 1]][2] if i else np.nan
        tr[d] = max(h - lo, abs(h - pc), abs(lo - pc)) if np.isfinite(pc) else h - lo
    out = []
    for o in frames:
        for d, g in o.groupby("d", sort=True):
            if d not in ixd or not (K.FIRST_DAY <= d <= K.LAST_DAY):
                continue
            ix = ixd[d]
            i = days_all.index(d)
            if i < 15:
                continue
            spot = ix[SPOT_ROW, 3]
            if not np.isfinite(spot):
                continue
            k0 = atm_strike(spot, step)
            ixc = pd.Series(ix[:, 3])
            g = g[(g.strike - ixc.reindex(g.m.values).values).abs() <= STRIKE_SLACK * step]
            g = g.drop_duplicates(["m", "strike", "side"], keep="first")
            edge = np.full((N, 2), np.nan)
            ec = g[(g.side == "C") & (g.offset == 10)]
            ep = g[(g.side == "P") & (g.offset == -10)]
            edge[ec.m.values, 0] = ec.close.values
            edge[ep.m.values, 1] = ep.close.values
            gk = g[(g.strike - k0).abs() <= KEEP * step + 1e-6]
            opts = {}
            for (k, s), h in gk.groupby(["strike", "side"]):
                a = np.full((N, 6), np.nan, dtype=np.float32)
                a[h.m.values] = h[["open", "high", "low", "close", "volume", "oi"]].values.astype(np.float32)
                opts[(float(k), s)] = a
            pd_ = days_all[i - 1]
            ph, pl, pc, po = daily[pd_]
            atr = float(np.mean([tr[days_all[j]] for j in range(i - 14, i)]))
            vprev = np.nan
            for j in range(i - 1, max(i - 4, -1), -1):
                v = vxd.get(days_all[j])
                if v is not None and np.isfinite(v[:, 3]).any():
                    vprev = float(v[np.isfinite(v[:, 3]), 3][-1])
                    break
            va = vxd[d][:, 3].copy() if d in vxd else np.full(N, np.nan)
            out.append(Sess(d, und, ix, va, opts, edge, d in exp, K.lot(und, d), (po, ph, pl, pc), atr, vprev, k0))
    out.sort(key=lambda s: s.day)
    # one record per day (a day can sit in two year files only by mistake)
    uniq = {}
    for s in out:
        uniq.setdefault(s.day, s)
    return [uniq[d] for d in sorted(uniq)]


def path(und: str):
    return K.OUT / f"sess_{und}.pkl"


def load(und: str, part: str = "design") -> list[Sess]:
    """part 'design' serves only design indices; 'holdout' serves a holdout index and is passed by study.py holdout
    alone (after it writes the read flag)."""
    if K.is_holdout_und(und) and part != "holdout":
        raise PermissionError(f"{und} is a holdout index (PREREG.md): only `study.py holdout` may read it")
    if part == "holdout" and not K.HOLDOUT_FLAG.exists():
        raise PermissionError("the holdout read flag is not set: run `study.py holdout`")
    return pickle.loads(path(und).read_bytes())


def calendar(und: str) -> list[tuple[date, bool]]:
    """Dates and expiry flags only (no prices): safe for counts on any index."""
    return [(s.day, s.expiry) for s in pickle.loads(path(und).read_bytes())]


if __name__ == "__main__":
    import r3data as _self          # pickle Sess under its module name, not __main__
    if sys.argv[1:2] == ["build"]:
        K.OUT.mkdir(parents=True, exist_ok=True)
        for u in (sys.argv[2:] or K.UNDS):
            ss = _self.build(u)
            path(u).write_bytes(pickle.dumps(ss))
            print(u, len(ss), ss[0].day if ss else "-", ss[-1].day if ss else "-", "expiry days", sum(s.expiry for s in ss),
                  flush=True)
