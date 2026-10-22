"""R103 Amendment 2 (stale-leg check): reprice every exit leg that had no traded open within FILL_LOOK minutes of its
exit order with Black-Scholes at the implied volatility of its last print, using the index at the exit minute and the
time left to expiry, instead of the frozen last close the engine uses.

Why: the lake keeps only ATM +-10 strikes at each minute. A held wing that drifts outside that window stops printing,
and the engine (as pre-registered) marks and exits it at its last close, which can be 30+ minutes old. A long wing
frozen high overstates the seller's profit. This module measures how much.

    R103_LAKE=<lake> R103_OUT=<cache> R102_LAKE=<lake> python research/hunt/r103_sell/r103stale.py design
    ... r103stale.py holdout        # after r103study.py holdout; the passers only

Writes results/stale_<part>.json. Pure helpers (bs, implied_vol, reprice) are tested in tests/test_r103.py.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from datetime import date
from math import exp, log, sqrt
from pathlib import Path

import numpy as np
from scipy.optimize import brentq
from scipy.stats import norm

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r103data as RD  # noqa: E402
import r103engine as E  # noqa: E402
import r103study as S  # noqa: E402

RATE = 0.065
CLOSE_ROW = 375                   # 15:30, the expiry settlement minute


def bs(spot: float, k: float, t: float, vol: float, call: bool) -> float:
    if t <= 0 or vol <= 0:
        return max(spot - k, 0.0) if call else max(k - spot, 0.0)
    d1 = (log(spot / k) + (RATE + vol * vol / 2) * t) / (vol * sqrt(t))
    d2 = d1 - vol * sqrt(t)
    if call:
        return spot * norm.cdf(d1) - k * exp(-RATE * t) * norm.cdf(d2)
    return k * exp(-RATE * t) * norm.cdf(-d2) - spot * norm.cdf(-d1)


def implied_vol(p: float, spot: float, k: float, t: float, call: bool) -> float | None:
    intr = max(spot - k, 0.0) if call else max(k - spot, 0.0)
    if not (np.isfinite(p) and np.isfinite(spot)) or t <= 0 or p <= intr + 0.05:
        return None
    try:
        return brentq(lambda v: bs(spot, k, t, v, call) - p, 0.005, 5.0)
    except ValueError:
        return None


def years_left(d: date, expiry: date, row: int) -> float:
    """Calendar time from minute `row` of day d to 15:30 on the expiry day, in years."""
    return ((expiry - d).days * 1440 + (CLOSE_ROW - row)) / (365.0 * 1440)


def reprice(t: dict, D, expiry: date | None) -> tuple[float, int, int]:
    """(net with stale exit legs repriced, legs repriced, stale legs that could not be repriced). A repriced leg is
    filled at the model price with the engine's thin-contract slippage (4 ticks + 5 bps) and the same charges rule."""
    net, fixed, fail = float(t["net"]), 0, 0
    xr, q, d = t["xr"], t["lot"], t["day"]
    for (side, k, sg), x in zip(t["legs"], t["exitpx"]):
        con = D.con[(side, float(k))]
        if np.isfinite(con[0, xr: xr + E.FILL_LOOK + 1]).any():
            continue                                          # the engine had a real open: nothing to fix
        ok = np.flatnonzero(np.isfinite(con[3, : xr + 1]))
        s1 = D.idx[3, min(xr, E.NROWS - 1)]
        if not len(ok) or expiry is None or not np.isfinite(s1):
            fail += 1
            continue
        r0 = int(ok[-1])
        vol = implied_vol(float(con[3, r0]), float(D.idx[3, r0]), float(k), years_left(d, expiry, r0), side == "C")
        if vol is None:
            fail += 1
            continue
        p1 = max(bs(float(s1), float(k), years_left(d, expiry, xr), vol, side == "C"), E.TICK)
        x1 = E.slip(p1, sg < 0, 0.0)
        net += sg * (x1 - x) * q - (E.charges(x1, q, sg < 0, d) - E.charges(x, q, sg < 0, d))
        fixed += 1
    return net, fixed, fail


def run(part: str) -> dict:
    if part == "holdout" and not S.HOLDOUT_FLAG.exists():
        raise PermissionError("the holdout is locked (PREREG.md): run r103study.py holdout first")
    os.environ.setdefault("R102_LAKE", str(RD.LAKE))
    sys.path.insert(0, str(HERE.parent / "r102_deep"))
    import r102data as B
    B.LAKE = RD.LAKE
    with open(RD.OUT / f"trades_{part}.pkl", "rb") as f:
        tr = pickle.load(f)
    out = {}
    for u in RD.UNDS:
        e = B.expiries(u)
        exp_of = dict(zip(e.day, e.expiry.dt.date))
        days = RD.load(u)
        for tid, ts in sorted(tr.items()):
            if not tid.startswith(u + "-") or not ts:
                continue
            nets, fixed, fail, fresh = [], 0, 0, []
            for t in ts:
                n, fx, fl = reprice(t, days[t["day"]], exp_of.get(t["day"]))
                nets.append(n)
                fixed += fx
                fail += fl
                if fx == 0 and fl == 0:
                    fresh.append(t["net"])
            a = np.array(nets)
            post = np.array([t["day"] >= S.NEW_REGIME for t in ts])
            out[tid] = dict(n=len(ts), net_as_run=round(sum(t["net"] for t in ts)), net_repriced=round(float(a.sum())),
                            win_repriced=round(float((a > 0).mean()), 3), post_regime_repriced=round(float(a[post].sum())),
                            legs_repriced=fixed, legs_not_repriceable=fail, trades_with_stale_leg=len(ts) - len(fresh),
                            fresh_only_n=len(fresh), fresh_only_net=round(float(np.sum(fresh))) if fresh else 0,
                            fresh_only_win=round(float(np.mean(np.array(fresh) > 0)), 3) if fresh else None)
    S.RESULTS.mkdir(parents=True, exist_ok=True)
    (S.RESULTS / f"stale_{part}.json").write_text(json.dumps(out, indent=1) + "\n")
    return out


if __name__ == "__main__":
    part = sys.argv[1] if len(sys.argv) > 1 else ""
    if part not in ("design", "holdout"):
        raise SystemExit(__doc__)
    for k, v in run(part).items():
        print(k, v)
