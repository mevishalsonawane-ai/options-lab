"""R195 candidates (PREREG.md section 4): each rule turns one day into an engine Spec (or None = no trade).

Every filter reads only sessions BEFORE the trade day, plus the trade day's minutes up to the decision minute
(look-ahead guard in test_r195.py). Signal sources: none directional (S, E, P, B0), the index (T1, T2, V1), India VIX
(S2) and implied-vs-realised volatility (S3, V2) as filters. N1's network is in r195nn.py and filters S4.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

import r195cfg as K
from r195engine import Leg, Spec, years_left

R0920, R1015, R1330, R1515 = K.row(9, 20), K.row(10, 15), K.row(13, 30), K.row(15, 15)

CANDIDATES = ["S1-NIFTY", "S1-BANKNIFTY", "S1-FINNIFTY", "S1-MIDCPNIFTY", "S1-SENSEX", "S2-NIFTY", "S3-NIFTY",
              "S4-NIFTY", "E1-NIFTY", "E2-NIFTY", "E3-SENSEX", "T1-NIFTY", "T1-BANKNIFTY", "T2-NIFTY", "V1-NIFTY",
              "V2-NIFTY", "P1-NIFTY", "N1-NIFTY"]
BASELINES = ["B0-NIFTY", "B0-BANKNIFTY", "B0-FINNIFTY", "B0-MIDCPNIFTY", "B0-SENSEX"]
TESTS = CANDIDATES + BASELINES
HELPERS = ["V0-NIFTY"]          # the unfiltered 09:20 condor: the base of V1 / V2's random-day twins (not a test)

# The kind of twin each test gets (PREREG 6.5) and its base / grid.
TWIN = {"S1": "timing", "S4": "timing", "E1": "timing", "E2": "timing", "E3": "timing", "P1": "timing", "B0": "timing",
        "S2": "days", "S3": "days", "V1": "days", "V2": "days", "N1": "days", "T1": "side", "T2": "side"}
BASE = {"S2-NIFTY": "S1-NIFTY", "S3-NIFTY": "S1-NIFTY", "V1-NIFTY": "V0-NIFTY", "V2-NIFTY": "V0-NIFTY",
        "N1-NIFTY": "S4-NIFTY"}
GRID = {"S1": range(R0920, K.row(13, 0) + 1, 5), "S4": range(R0920, K.row(13, 0) + 1, 5),
        "B0": range(R0920, K.row(13, 0) + 1, 5), "E1": range(R0920, K.row(13, 0) + 1, 5),
        "E2": range(R0920, K.row(14, 30) + 1, 5), "E3": range(R0920, K.row(13, 0) + 1, 5),
        "P1": range(K.row(13, 0), R1515 + 1, 5)}
DECIDE = {"S1": R0920, "S2": R0920, "S3": R0920, "S4": R0920, "N1": R0920, "E1": R0920, "E2": R1330, "E3": R0920,
          "T1": R1015, "T2": R0920, "V0": R0920, "V1": R0920, "V2": R0920, "P1": R1515, "B0": R0920}


def fam(cid: str) -> str:
    return cid.split("-")[0]


def und_of(cid: str) -> str:
    return cid.split("-", 1)[1]


# ------------------------------------------------------------------------------------------------ daily features
@dataclass
class Daily:
    """Per-index session table (index sessions from the warm-up on) and causal features by day."""
    days: list
    pos: dict
    open_: np.ndarray
    high: np.ndarray
    low: np.ndarray
    close: np.ndarray
    vix920: np.ndarray


def _last(a: np.ndarray) -> float:
    ok = np.flatnonzero(np.isfinite(a))
    return float(a[ok[-1]]) if len(ok) else float("nan")


def daily(store: dict, vix: dict) -> Daily:
    ds = sorted(d for d, D in store.items() if np.isfinite(D.idx[3]).any())
    o, h, l, c, v = (np.full(len(ds), np.nan) for _ in range(5))
    for i, d in enumerate(ds):
        x = store[d].idx
        f = np.flatnonzero(np.isfinite(x[0]))
        o[i] = x[0, f[0]] if len(f) else np.nan
        h[i], l[i], c[i] = np.nanmax(x[1]), np.nanmin(x[2]), _last(x[3])
        vv = vix.get(d)
        if vv is not None:
            v[i] = _last(vv[3, : R0920 + 1])
    return Daily(ds, {d: i for i, d in enumerate(ds)}, o, h, l, c, v)


def rv(Dl: Daily, i: int, n: int) -> float:
    """Annualised std of the n daily log returns ending at yesterday's close (sessions before i only)."""
    if i - n - 1 < 0:
        return float("nan")
    cl = Dl.close[i - n - 1: i]
    r = np.diff(np.log(cl))
    r = r[np.isfinite(r)]
    return float(np.std(r, ddof=1) * math.sqrt(252)) if len(r) >= n - 2 else float("nan")


def vix_ok(Dl: Daily, i: int) -> bool:
    hist = Dl.vix920[max(0, i - 250): i]
    hist = hist[np.isfinite(hist)]
    v = Dl.vix920[i]
    return bool(len(hist) >= 120 and np.isfinite(v) and v >= np.median(hist))


def calm_ok(Dl: Daily, i: int) -> bool:
    if i < 22:
        return False
    rng = Dl.high - Dl.low
    y, base = rng[i - 1], rng[i - 21: i - 1]
    gap = Dl.open_[i] / Dl.close[i - 1] - 1
    return bool(np.isfinite(y) and np.isfinite(base).sum() >= 15 and y <= 0.8 * np.nanmean(base)
                and np.isfinite(gap) and abs(gap) <= 0.003)


def trend_up(Dl: Daily, i: int) -> bool | None:
    if i < 20:
        return None
    sma = np.nanmean(Dl.close[i - 20: i])
    y = Dl.close[i - 1]
    return None if not (np.isfinite(sma) and np.isfinite(y)) else bool(y > sma)


# ------------------------------------------------------------------------------------------------ strikes
def atm(D, und: str, r: int) -> float | None:
    step = K.STEP[und]
    k0 = D.atm[r] if D.atm is not None and np.isfinite(D.atm[r]) else np.nan
    if not np.isfinite(k0):
        px = D.idx[3, r]
        if not np.isfinite(px):
            return None
        k0 = math.floor(px / step + 0.5) * step
    return float(k0)


def close_at(D, side: str, k: float, r: int) -> float:
    con = D.con.get((side, float(k)))
    if con is None:
        return float("nan")
    c = con[3, max(0, r - 5): r + 1]
    ok = np.flatnonzero(np.isfinite(c))
    return float(c[ok[-1]]) if len(ok) else float("nan")


def straddle(D, und: str, r: int):
    k0 = atm(D, und, r)
    if k0 is None:
        return None, float("nan")
    return k0, close_at(D, "C", k0, r) + close_at(D, "P", k0, r)


def iv_rv_ok(D, und: str, Dl: Daily, i: int, d, expiry, r: int = R0920) -> bool:
    k0, s = straddle(D, und, r)
    spot = D.idx[3, r]
    t = float(years_left(d, expiry, r))
    r20 = rv(Dl, i, 20)
    if k0 is None or not (np.isfinite(s) and np.isfinite(spot) and np.isfinite(r20)) or t <= 0 or r20 <= 0:
        return False
    iv = s / (0.7979 * spot * math.sqrt(t))
    return bool(iv >= 1.2 * r20)


# ------------------------------------------------------------------------------------------------ the specs
def spec_straddle(D, und, r, stop_leg=None, wings=None, side_stop=None, prem_stop=None, tag=""):
    k0, s = straddle(D, und, r)
    if k0 is None or not np.isfinite(s):
        return None
    st = K.STEP[und]
    if wings is None:
        legs = [Leg("C", k0, -1, 0), Leg("P", k0, -1, 1)]
        stops = {0: ("leg", stop_leg), 1: ("leg", stop_leg)} if stop_leg else {}
        return Spec(legs, r, stops, None, tag)
    if side_stop:      # S4: two independent sides
        legs = [Leg("C", k0, -1, 0), Leg("C", k0 + wings * st, 1, 0), Leg("P", k0, -1, 1), Leg("P", k0 - wings * st, 1, 1)]
        return Spec(legs, r, {0: ("leg", side_stop), 1: ("leg", side_stop)}, None, tag)
    legs = [Leg("C", k0, -1), Leg("P", k0, -1), Leg("C", k0 + wings * st, 1), Leg("P", k0 - wings * st, 1)]
    return Spec(legs, r, {0: ("prem", prem_stop)} if prem_stop else {}, None, tag)


def spec_spread(D, und, r, bull: bool, index_level=None, tag=""):
    k0 = atm(D, und, r)
    if k0 is None:
        return None
    st = K.STEP[und]
    if bull:
        legs = [Leg("P", k0 - st, -1), Leg("P", k0 - 5 * st, 1)]
        ix = (1, index_level) if index_level is not None else None
    else:
        legs = [Leg("C", k0 + st, -1), Leg("C", k0 + 5 * st, 1)]
        ix = (-1, index_level) if index_level is not None else None
    return Spec(legs, r, {0: ("maxloss", 0.5)}, ix, tag)


def spec_condor(D, und, r, tag=""):
    k0, s = straddle(D, und, r)
    if k0 is None or not np.isfinite(s):
        return None
    st = K.STEP[und]
    a = int(min(4, max(1, math.floor(0.5 * s / st + 0.5))))
    legs = [Leg("C", k0 + a * st, -1), Leg("P", k0 - a * st, -1), Leg("C", k0 + (a + 3) * st, 1),
            Leg("P", k0 - (a + 3) * st, 1)]
    return Spec(legs, r, {0: ("maxloss", 0.5)}, None, tag)


def t1_signal(D, r: int = R1015):
    o = D.idx[0, 0]
    c = D.idx[3, r]
    if not (np.isfinite(o) and np.isfinite(c)):
        return None
    x = c / o - 1
    return True if x >= 0.0025 else (False if x <= -0.0025 else None)


def spec_for(cid: str, D, d, Dl: Daily, expiry, decide: int | None = None, side: bool | None = None):
    """The rule's Spec for day d (None = no trade). decide / side override the rule's own minute / side (twins only)."""
    f, und = fam(cid), und_of(cid)
    i = Dl.pos.get(d)
    if i is None or D.atm is None:
        return None
    r = DECIDE[f] if decide is None else decide
    if f in ("E1", "E2", "E3") and not D.expiry:
        return None
    if f == "P1" and D.expiry:
        return None
    if f == "B0":
        return spec_straddle(D, und, r, tag=cid)
    if f in ("S1", "S2", "S3"):
        if f == "S2" and not vix_ok(Dl, i):
            return None
        if f == "S3" and not iv_rv_ok(D, und, Dl, i, d, expiry):
            return None
        return spec_straddle(D, und, r, stop_leg=1.25, tag=cid)
    if f in ("S4", "N1"):
        return spec_straddle(D, und, r, wings=6, side_stop=1.30, tag=cid)
    if f in ("E1", "E2", "E3"):
        return spec_straddle(D, und, r, wings=4, prem_stop=1.5, tag=cid)
    if f == "P1":
        return spec_straddle(D, und, r, wings=4, tag=cid)
    if f == "T1":
        sig = t1_signal(D)
        if sig is None:
            return None
        return spec_spread(D, und, r, sig if side is None else side, index_level=float(D.idx[0, 0]), tag=cid)
    if f == "T2":
        sig = trend_up(Dl, i)
        if sig is None:
            return None
        return spec_spread(D, und, r, sig if side is None else side, tag=cid)
    if f in ("V0", "V1", "V2"):
        if f == "V1" and not calm_ok(Dl, i):
            return None
        if f == "V2" and not iv_rv_ok(D, und, Dl, i, d, expiry):
            return None
        return spec_condor(D, und, r, tag=cid)
    raise KeyError(cid)
