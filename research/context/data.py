"""Per-session building blocks for the market-context studies, from the source chosen in config.SOURCE.

    python -m research.context.data            # build/refresh the cache for BANKNIFTY and NIFTY (a few minutes)

For every session of each underlying it keeps (in the local cache, never in git):
  - the index's 375 one-minute bars (09:15 .. 15:29 bar starts; NaN where the source has no bar - never filled),
  - India VIX per minute (last value at or before the minute, within the day),
  - the nearest option expiry on or after the day and whether today IS that expiry (the expiring contract),
  - the ATM strike from the 09:20 bar and that strike's call and put per minute (close, volume) - the straddle and the
    contract the ORB arms buy,
  - open interest by strike and side at 09:20 and at the last minute (max pain, the max-OI strikes).
Nothing is looked up from the future: every later study reads a session only from its own bars and earlier sessions.
"""
from __future__ import annotations

import pickle
from datetime import date

import numpy as np
import pandas as pd

from research.brain import adapters
from research.brain.config import STRIKE_STEP, paths

from . import config as C

W = 375
OPEN_MIN = 9 * 60 + 15


def minute_of(ts: pd.Series) -> np.ndarray:
    t = pd.to_datetime(ts)
    return (t.dt.hour * 60 + t.dt.minute - OPEN_MIN).to_numpy()


def grid(frame: pd.DataFrame, col: str) -> np.ndarray:
    out = np.full(W, np.nan)
    if frame is None or len(frame) == 0:
        return out
    m = minute_of(frame.ts)
    ok = (m >= 0) & (m < W)
    out[m[ok]] = frame[col].to_numpy(dtype=float)[ok]
    return out


def ffill(a: np.ndarray) -> np.ndarray:
    s = pd.Series(a).ffill().to_numpy()
    return s


def session(dd) -> dict | None:
    ix = dd.index
    if ix is None or len(ix) < 300:
        return None
    o, h, l, c = (grid(ix, k) for k in ("open", "high", "low", "close"))
    if not np.isfinite(c[5]) or not np.isfinite(c[-30:]).any():
        return None
    vix = ffill(grid(dd.vix, "close")) if len(dd.vix) else np.full(W, np.nan)
    step = STRIKE_STEP[dd.und]
    out = dict(day=dd.day, o=o, h=h, l=l, c=c, vix=vix, step=step)
    op = dd.options
    if op is None or len(op) == 0:
        out.update(expiry=None, is_expiry=False)
        return out
    op = op[pd.to_datetime(op.expiry).dt.date >= dd.day]
    exps = sorted(set(pd.to_datetime(op.expiry).dt.date))
    if not exps:
        out.update(expiry=None, is_expiry=False)
        return out
    e = exps[0]
    out.update(expiry=e, is_expiry=(e == dd.day))
    oe = op[pd.to_datetime(op.expiry).dt.date == e]
    atm = float(np.round(c[5] / step) * step)                 # the 09:20 bar's close (bar start 09:20 = minute 5)
    out["atm"] = atm
    for side in ("C", "P"):
        leg = oe[(oe.side == side) & (oe.strike == atm)]
        out[f"{side}_c"] = ffill(grid(leg, "close"))
        out[f"{side}_v"] = np.nan_to_num(grid(leg, "volume"))
    # OI by strike at 09:20 and at the day's last option minute
    oe = oe.assign(m=minute_of(oe.ts))
    for tag, upto in (("oi_open", 5), ("oi_close", W - 1)):
        x = oe[oe.m <= upto].sort_values("m").groupby(["strike", "side"]).oi.last().dropna()
        out[tag] = {(float(k), s): float(v) for (k, s), v in x.items()}
    # every strike's close per minute for the expiring contract (the pin study needs the full ladder on expiry days)
    if out["is_expiry"]:
        lad = {}
        for (k, s), g in oe.groupby(["strike", "side"]):
            lad[(float(k), s)] = ffill(grid(g, "close"))
        out["ladder"] = lad
    return out


def build(und: str, force: bool = False) -> list[dict]:
    f = C.cache() / f"sessions_{und}.pkl"
    if f.exists() and not force:
        return load(und)
    ads = adapters.pick(paths(), und, C.SOURCE)
    if not ads:
        raise SystemExit(f"no data for {und} from source {C.SOURCE}")
    got: dict[date, dict] = {}
    for ad in ads:                       # priority order: the lake first when it is there
        ss = ad.sessions(und) if hasattr(ad, "sessions") else []
        if not ss:
            continue
        for dd in ad.load(und, ss[0], ss[-1]):
            if dd.day in got:
                continue
            s = session(dd)
            if s is not None:
                got[dd.day] = s
    out = [got[d] for d in sorted(got)]
    with open(f, "wb") as fh:
        pickle.dump(out, fh, protocol=4)
    print(und, len(out), "sessions", out[0]["day"], "..", out[-1]["day"])
    return out


def load(und: str) -> list[dict]:
    with open(C.cache() / f"sessions_{und}.pkl", "rb") as fh:
        return pickle.load(fh)


def daily(sessions: list[dict]) -> pd.DataFrame:
    """One row per session: day OHLC, previous close, gap, ranges, VIX, expiry - every field known by its own time."""
    rows = []
    prev = None
    for s in sessions:
        c, h, l, o = s["c"], s["h"], s["l"], s["o"]
        op = o[np.isfinite(o)][0]
        cl = c[np.isfinite(c)][-1]
        hi, lo = np.nanmax(h), np.nanmin(l)
        r = dict(day=s["day"], open=op, high=hi, low=lo, close=cl, c0920=c[5], c1000=c[45] if np.isfinite(c[45]) else np.nan,
                 hi_1h=np.nanmax(h[:60]), lo_1h=np.nanmin(l[:60]), c1430=c[315], c1525=c[370],
                 vix_open=s["vix"][5] if np.isfinite(s["vix"][5]) else np.nan,
                 vix_close=s["vix"][np.isfinite(s["vix"])][-1] if np.isfinite(s["vix"]).any() else np.nan,
                 expiry=s.get("expiry"), is_expiry=bool(s.get("is_expiry")))
        if prev is not None:
            pc = prev["close"]
            r.update(prev_close=pc, prev_range_pct=(prev["high"] - prev["low"]) / prev["close"] * 100,
                     prev_vix_close=prev["vix_close"], gap_pct=(op - pc) / pc * 100)
        rows.append(r)
        prev = r
    d = pd.DataFrame(rows)
    d["range_pct"] = (d.high - d.low) / d.prev_close * 100
    d["abs_gap_pct"] = d.gap_pct.abs()
    d["oc_pct"] = (d.close - d.open) / d.open * 100
    d["abs_oc_pct"] = d.oc_pct.abs()
    d["cc_pct"] = (d.close - d.prev_close) / d.prev_close * 100
    d["fh_range_pct"] = (d.hi_1h - d.lo_1h) / d.open * 100
    return d


if __name__ == "__main__":
    import sys
    for u in C.UNDS:
        build(u, force="--force" in sys.argv)
