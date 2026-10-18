"""R51 data: the lake's 1-minute index bars and HELD option contracts, one store per index.

    python research/hunt/r51/r51data.py build [UND ...]      # writes <OUT>/day_<UND>.pkl

For every session the store keeps
  idx      (4, 375) index O H L C on the 09:15..15:29 minute grid (NaN where the lake has no minute)
  atm      (375,) the strike that was ATM at that minute (offset 0 of the rolling series; NaN if none)
  con      {(side, strike): (5, 375) O H L C V of that ONE contract}, side 'C' / 'P'
  expiry   True when the nearest expiry settles that day

A contract's minutes are gathered from every offset row that carried its strike, so a trade is always priced on the
strike bought at entry, never on the rolling ATM series (research/HUNT_R45_PREMIUM_SCALP_AUDIT.md). Only the WEEK files
are read: for BANKNIFTY, FINNIFTY and MIDCPNIFTY the WEEK and MONTH files are the same contract in this window, and
for NIFTY / SENSEX WEEK is the nearest (weekly) expiry the arms trade. Index days start at r51cfg.WARM_START (warm-up);
option contracts start at r51cfg.START.
"""
from __future__ import annotations

import json
import pickle
import sys
from dataclasses import dataclass, field
from datetime import date

import numpy as np
import pandas as pd

import r51cfg as K

sys.path.insert(0, str(K.HERE.parent))
import lakeclean  # noqa: E402

EXPIRY_TV_MAX, EXPIRY_TV_JUMP = 0.6, 2.0          # R25 / R29 / R44 straddle rule
TV_ROW = K.row(15, 15)


@dataclass
class Day:
    d: date
    idx: np.ndarray                                # (4, 375)
    atm: np.ndarray | None = None                  # (375,)
    con: dict = field(default_factory=dict)        # (side, strike) -> (5, 375)
    expiry: bool = False

    def contract(self, side: str, strike: float):
        return self.con.get((side, float(strike)))


def _read(path, cols):
    df = pd.read_parquet(path, columns=cols)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["day"] = ts.dt.date
    df["r"] = (ts.dt.hour * 60 + ts.dt.minute - K.OPEN_MIN).astype(np.int32)
    df = df[(df.r >= 0) & (df.r < K.NROWS)]
    return df.drop(columns=["ts"])


def _years():
    return range(K.WARM_START.year, K.END.year + 1)


def index_days(und: str) -> dict:
    parts = []
    for y in _years():
        p = K.LAKE / f"index_1m_{und}_{y}.parquet"
        if p.exists():
            parts.append(_read(p, ["ts", "open", "high", "low", "close"]))
    x = pd.concat(parts, ignore_index=True)
    x = x[(x.day >= K.WARM_START) & (x.day <= K.END) & (x.close > 0)]
    x = x.drop_duplicates(["day", "r"])
    out = {}
    for d, g in x.groupby("day", sort=True):
        a = np.full((4, K.NROWS), np.nan)
        for i, c in enumerate(("open", "high", "low", "close")):
            a[i, g.r.values] = g[c].values
        out[d] = Day(d, a)
    return out


def expiry_days(tv: pd.Series) -> set:
    """ATM straddle time value (% of spot) at 15:15 below 0.6% and the next session's above 2x it.
    Known limit (review, 15 Oct): the lake's last session has no next session, so it is never flagged (13 Oct 2026 was a
    NIFTY weekly expiry: 2 trades of R44-donchian20-RT, +Rs 1,126; see HUNT_R51_CANCELLED_RECENT.md)."""
    tv = tv.sort_index()
    nxt = tv.shift(-1)
    ok = (tv < EXPIRY_TV_MAX) & (nxt > EXPIRY_TV_JUMP * tv.clip(lower=1e-6)) & nxt.notna()
    return {d for d, v in ok.items() if v}


def add_options(und: str, days: dict, series: str = "WEEK", clean: bool = False, stale: bool = False):
    """Fill each Day's atm / con / expiry. clean (Amendment A): drop foreign rows and stray prints first (the ATM label
    from the rows after the foreign step only); stale (with clean, 18 Oct 2026): also drop stale repeated zero-volume
    prints (lakeclean step 3; prices only, the ATM label and expiry test are unchanged); returns the log of every
    dropped or capped row (None when not clean)."""
    step = K.STEP[und]
    tv = {}
    logs, total = [], 0
    closes = {d: D.idx[3] for d, D in days.items()} if clean else None
    for y in _years():
        p = K.LAKE / f"opt_{und}_{series}_{y}.parquet"
        if not p.exists():
            continue
        o = _read(p, ["ts", "offset", "side", "strike", "spot", "open", "high", "low", "close", "volume"])
        o = o[(o.day >= K.START) & (o.day <= K.END)]
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[(o.strike - o.spot).abs() <= 12 * step]                    # mislabelled rows (R25)
        o = o[o.day.isin(days.keys())]
        lab = o
        if clean:
            total += len(o)
            o, lab, log = lakeclean.clean(o, closes, stale=stale)
            logs.append(log)
        labs = dict(tuple(lab[lab.offset == 0].groupby("day", sort=True)))
        # clean: the expiry test reads its 15:15 straddle from the same rows as the ATM label (a dropped stray-open row
        # must not hide an expiry day; that would change which days a rule skips, not just its prices)
        tvc = (lab.drop_duplicates(["day", "side", "strike", "r"]).set_index(["day", "side", "strike", "r"]).close
               if clean else None)
        for d, g in o.groupby("day", sort=True):
            if d not in days:
                continue
            D = days[d]
            a0 = labs.get(d, g.iloc[:0]).drop_duplicates("r")
            atm = np.full(K.NROWS, np.nan)
            atm[a0.r.values] = a0.strike.values
            D.atm = atm
            g = g.drop_duplicates(["side", "strike", "r"], keep="first")
            for (s, k), h in g.groupby(["side", "strike"], sort=False):
                c = np.full((5, K.NROWS), np.nan, dtype=np.float32)
                for i, col in enumerate(("open", "high", "low", "close", "volume")):
                    c[i, h.r.values] = h[col].values
                D.con[(s, float(k))] = c
            # straddle time value at 15:15 (the last ATM minute at or before it, within 30 minutes)
            a = a0[(a0.r <= TV_ROW) & (a0.r >= TV_ROW - 30)]
            if len(a):
                last = a.iloc[a.r.values.argmax()]
                k = float(last.strike)
                rr = int(last.r)
                if clean:
                    cc, pc = tvc.get((d, "C", k, rr), np.nan), tvc.get((d, "P", k, rr), np.nan)
                else:
                    ce, pe = D.con.get(("C", k)), D.con.get(("P", k))
                    cc = ce[3, rr] if ce is not None else np.nan
                    pc = pe[3, rr] if pe is not None else np.nan
                if np.isfinite(cc) and np.isfinite(pc):
                    spot = float(last.spot)
                    tv[d] = (cc + pc - abs(spot - k)) / spot * 100
    for d in expiry_days(pd.Series(tv)):
        days[d].expiry = True
    if not clean:
        return None
    log = pd.concat(logs, ignore_index=True) if logs else pd.DataFrame(columns=["day", "r", "side", "strike", "why"])
    log.attrs["rows"] = total
    return log


def build(und: str) -> dict:
    days = index_days(und)
    log = add_options(und, days, clean=K.CLEAN, stale=K.STALE)
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / f"day_{und}.pkl", "wb") as f:
        pickle.dump(days, f, protocol=pickle.HIGHEST_PROTOCOL)
    tradable = [d for d, D in days.items() if d >= K.START and D.atm is not None]
    out = dict(und=und, days=len(days), option_days=len(tradable), first=str(min(tradable)), last=str(max(tradable)),
               expiry_days=sum(D.expiry for D in days.values()))
    if log is not None:                     # Amendment A: what the cleaning removed (outside git, with the stores)
        out["clean"] = lakeclean.counts(log, log.attrs["rows"])
        log.to_parquet(K.OUT / f"clean_log_{und}.parquet", index=False)
        (K.OUT / f"clean_counts_{und}.json").write_text(json.dumps(out["clean"]))
    return out


_CACHE: dict = {}


def load(und: str) -> dict:
    if und not in _CACHE:
        with open(K.OUT / f"day_{und}.pkl", "rb") as f:
            _CACHE[und] = pickle.load(f)
    return _CACHE[und]


if __name__ == "__main__":
    import r51data as _self          # pickle the Day class under its module name, not __main__
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            print(_self.build(u), flush=True)
