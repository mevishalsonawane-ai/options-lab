"""R92 data: R3's session records (research/hunt/r3orb/r3data.py) for every index, 1 Aug 2021 .. 19 Oct 2026, with
R49's two fixes (research/hunt/r49/r49data.py):
- the lot is the day's contract lot read from the data (the largest known lot that divides >= 90% of the day's traded
  volumes), the calendar table only as the fallback;
- a session is also an expiry day when its own 15:15 ATM time value is under 0.15% of spot.

    python3 research/hunt/r92_orb_time/r92data.py build [UND ...]   # writes <R92_OUT>/sess_<UND>.pkl
    python3 research/hunt/r92_orb_time/r92data.py summary           # sessions, expiry days, lots (no prices)

Holdout safety: `sessions(part)` serves the design part only, unless the holdout read flag exists (set only by
`r92study.py holdout`, which refuses a second read).
"""
from __future__ import annotations

import pickle
import sys
from datetime import date

import numpy as np

import r92cfg as K
import r3cfg as K3
import r3data as D3
import r49cfg as K49
import r49data as D49
from r3data import Sess  # noqa: F401  (pickles need the class importable)


def lot_table(und: str, d: date) -> int:
    return K3.lot(und, d) if d < K.R49_FROM else K49.lot_table(und, d)


def build(und: str) -> list:
    K3.LAKE, K3.FIRST_DAY, K3.LAST_DAY = K.LAKE, K.FIRST_DAY, K.LAST_DAY
    ss = [s for s in D3.build(und) if K.FIRST_DAY <= s.day <= K.LAST_DAY]
    for s in ss:
        g = D49.lot_from_volume(s.opts, K.KNOWN_LOTS[und])
        s.lot = g or lot_table(und, s.day)
        s.lot_src = "data" if g else "table"
        tv = D49.tv_1515(s)
        if np.isfinite(tv) and tv < D49.TV_LOW:
            s.expiry = True
    return ss


def path(und: str):
    return K.OUT / f"sess_{und}.pkl"


def load_all(und: str) -> list:
    return pickle.loads(path(und).read_bytes())


def sessions(und: str, part: str = "design") -> list:
    """The sessions of one index in one period. 'holdout' only after `r92study.py holdout` has written the flag."""
    if part not in ("design", "holdout"):
        raise ValueError(part)
    if part == "holdout" and not K.HOLDOUT_FLAG.exists():
        raise PermissionError("the holdout is locked: only `r92study.py holdout` may read it (PREREG A2)")
    return [s for s in load_all(und) if K.period(s.day) == part]


def calendar(und: str) -> list:
    """(day, expiry) of every session, both periods: dates only, no prices (day-type features need the calendar)."""
    return [(s.day, bool(s.expiry)) for s in load_all(und)]


def summary():
    for u in K.UNDS:
        ss = load_all(u)
        lots = {}
        for s in ss:
            lots.setdefault(s.lot, [s.day, s.day])[1] = s.day
        print(u, len(ss), ss[0].day, ss[-1].day,
              "design", sum(K.period(s.day) == "design" for s in ss),
              "holdout", sum(K.period(s.day) == "holdout" for s in ss),
              "unseen(13-19 Oct)", sum(s.day >= K.UNSEEN_START for s in ss),
              "expiry", sum(s.expiry for s in ss),
              "lot from data", sum(getattr(s, "lot_src", "") == "data" for s in ss),
              "| lots", {k: (str(a), str(b)) for k, (a, b) in sorted(lots.items(), key=lambda x: x[1][0])}, flush=True)


if __name__ == "__main__":
    import r92data as _self          # pickle under the module name, not __main__
    if sys.argv[1:2] == ["build"]:
        K.OUT.mkdir(parents=True, exist_ok=True)
        for u in (sys.argv[2:] or K.UNDS):
            ss = _self.build(u)
            path(u).write_bytes(pickle.dumps(ss))
            print(u, len(ss), ss[0].day, ss[-1].day, "expiry days", sum(s.expiry for s in ss), flush=True)
    elif sys.argv[1:2] == ["summary"]:
        summary()
