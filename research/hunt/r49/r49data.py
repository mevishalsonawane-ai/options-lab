"""R49 data: R3's session records (research/hunt/r3orb/r3data.py), built for 20 Nov 2024 .. 13 Oct 2026 only.

    python3 research/hunt/r49/r49data.py build [UND ...]    # writes <R49_OUT>/sess_<UND>.pkl
    python3 research/hunt/r49/r49data.py summary            # sessions, expiry days and lots per index (no prices)

Two changes on top of R3's builder (PREREG A1):
- the lot is the day's contract lot read from the data: the GCD of the traded volumes of the day's stored contracts
  (a trade is always a whole number of lots). The calendar table r49cfg.LOTS is the fallback when that GCD is not a
  known lot of the index (a day with too few trades);
- R3's expiry rule (ATM time value at 15:15 under 0.6% of spot AND the next session's more than twice it) misses an
  expiry when the next session has no 15:15 prices (FINNIFTY 30 Mar and 25 Aug 2026) and on the lake's last session
  (NIFTY 13 Oct 2026). A session is ALSO an expiry day when its own 15:15 ATM time value (from the stored contracts) is
  under 0.15% of spot. In this window no non-expiry day is that low (lowest 0.166%, SENSEX the day before an expiry;
  `summary` prints the check).
Every option row is one fixed contract: (strike, side) of the day's nearest expiry, held from entry to exit.
"""
from __future__ import annotations

import pickle
import sys

import numpy as np

import r49cfg as K
import r3cfg as K3
import r3data as D3
from r3data import C, V, Sess, minute  # noqa: F401  (re-exported for the engine and tests)

TV_LOW = 0.15


def lot_from_volume(opts: dict, known) -> int:
    """The largest known lot of the index that divides at least 90% of the day's non-zero traded volumes (units) of
    the stored contracts; 0 when none does or fewer than 50 minutes traded. (A plain GCD is too fragile: a few minutes
    a day carry volumes like 49 or 51.)"""
    vals = []
    for a in opts.values():
        v = a[:, V]
        vals.append(v[np.isfinite(v) & (v > 0)])
    v = np.rint(np.concatenate(vals)).astype(np.int64) if vals else np.zeros(0, dtype=np.int64)
    if len(v) < 50:
        return 0
    for lot in sorted(known, reverse=True):
        if (v % lot == 0).mean() >= 0.9:
            return lot
    return 0


def tv_1515(s: Sess) -> float:
    """ATM straddle time value at 15:15 as % of spot, ATM = the stored strike nearest the 15:15 index close."""
    r = minute(15, 15)
    spot = s.ix[r, 3]
    if not np.isfinite(spot):
        return np.nan
    ks = sorted({k for k, _ in s.opts})
    if not ks:
        return np.nan
    k = min(ks, key=lambda x: abs(x - spot))
    c, p = s.opts.get((k, "C")), s.opts.get((k, "P"))
    if c is None or p is None or not (np.isfinite(c[r, C]) and np.isfinite(p[r, C])):
        return np.nan
    return float((c[r, C] + p[r, C] - abs(spot - k)) / spot * 100)


def build(und: str) -> list[Sess]:
    K3.LAKE, K3.FIRST_DAY, K3.LAST_DAY = K.LAKE, K.FIRST_DAY, K.LAST_DAY
    ss = [s for s in D3.build(und) if K.FIRST_DAY <= s.day <= K.LAST_DAY]
    for s in ss:
        g = lot_from_volume(s.opts, K.KNOWN_LOTS[und])
        s.lot = g or K.lot_table(und, s.day)
        tv = tv_1515(s)
        if np.isfinite(tv) and tv < TV_LOW:
            s.expiry = True
    return ss


def path(und: str):
    return K.OUT / f"sess_{und}.pkl"


def load(und: str) -> list[Sess]:
    return pickle.loads(path(und).read_bytes())


def summary():
    for u in K.UNDS:
        ss = load(u)
        tv = {s.day: tv_1515(s) for s in ss}
        e = [s for s in ss if s.expiry]
        ne = [s for s in ss[:-1] if not s.expiry]
        lots = {}
        for s in ss:
            lots.setdefault(s.lot, [s.day, s.day])[1] = s.day
        src = sum(lot_from_volume(s.opts, K.KNOWN_LOTS[u]) > 0 for s in ss)
        print(u, len(ss), ss[0].day, ss[-1].day, "design", sum(K.period(s.day) == "design" for s in ss),
              "final", sum(K.period(s.day) == "final" for s in ss), "expiry days", len(e),
              "| max expiry-day tv", round(max(tv[s.day] for s in e if np.isfinite(tv[s.day])), 3),
              "min other-day tv", round(min(tv[s.day] for s in ne if np.isfinite(tv[s.day])), 3),
              "| last day tv", round(tv[ss[-1].day], 3), "expiry", ss[-1].expiry,
              "| lots", {k: (str(a), str(b)) for k, (a, b) in lots.items()}, "from data", src, flush=True)


if __name__ == "__main__":
    import r49data as _self
    if sys.argv[1:2] == ["build"]:
        K.OUT.mkdir(parents=True, exist_ok=True)
        for u in (sys.argv[2:] or K.UNDS):
            ss = _self.build(u)
            path(u).write_bytes(pickle.dumps(ss))
            print(u, len(ss), ss[0].day, ss[-1].day, "expiry days", sum(s.expiry for s in ss), flush=True)
    elif sys.argv[1:2] == ["summary"]:
        summary()
