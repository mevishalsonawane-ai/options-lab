"""R154 data: the early store (first option day - 19 Nov 2024) and one loader for every session R154 reads.

    python research/hunt/r154_scalp_10_15/r154data.py build-early [UND ...]   # writes $R154_OUT/early/day_<UND>.pkl

The early store is built by R51's own code (r51data.index_days / add_options with Amendment A cleaning), with r51cfg's
window pointed at 2021 - 2024 for the call only, then each session is kept to the contracts within 2 strikes of its
ATM range (a 1-ITM entry can only buy those). From 20 Nov 2024 the loader returns R51's clean stores, exactly the ones
the arms (R101 - R153) are priced on; in the post run (R154_POST=1) R106's clean_post stores to 19 Oct.
"""
from __future__ import annotations

import pickle
import sys
from datetime import date, timedelta

import numpy as np

import r154cfg as K

_EARLY_END = K.ARM_START - timedelta(days=1)        # 19 Nov 2024
_M: dict = {}


def mods():
    """R152's module set (R106's mods first, so the post run points r51cfg at the 19 Oct stores)."""
    if not _M:
        import r152study as S52
        M = S52.mods()
        import r51cfg
        import r106engine as X
        _M.update(M, S52=S52, r51cfg=r51cfg, X=X)
    return _M


def prune(D, step: float, keep: int = 2) -> None:
    """Keep only the contracts whose strike lies within `keep` strikes of the session's ATM range (in place)."""
    if D.atm is None or not np.isfinite(D.atm).any():
        D.con = {}
        return
    lo = float(np.nanmin(D.atm)) - keep * step - 1e-6
    hi = float(np.nanmax(D.atm)) + keep * step + 1e-6
    D.con = {k: v for k, v in D.con.items() if lo <= k[1] <= hi}


def build_early(und: str) -> dict:
    M = mods()
    R, r51data = M["r51cfg"], M["r51data"]
    saved = (R.WARM_START, R.START, R.END)
    try:
        # read a little past 19 Nov so the expiry test (which needs the next session) sees 19 Nov's successor
        R.WARM_START, R.START, R.END = K.EARLY_WARM, K.EARLY_START, _EARLY_END + timedelta(days=14)
        days = r51data.index_days(und)
        r51data.add_options(und, days, clean=True, stale=False)
    finally:
        R.WARM_START, R.START, R.END = saved
    days = {d: D for d, D in days.items() if d <= _EARLY_END}
    for D in days.values():
        if D.d >= K.EARLY_START:
            prune(D, R.STEP[und])
        else:
            D.atm, D.con = None, {}
    K.EARLY.mkdir(parents=True, exist_ok=True)
    with open(K.EARLY / f"day_{und}.pkl", "wb") as f:
        pickle.dump(days, f, protocol=pickle.HIGHEST_PROTOCOL)
    opt = [d for d, D in days.items() if D.atm is not None and D.con]
    return dict(und=und, days=len(days), option_days=len(opt), first=str(min(opt)) if opt else None,
                last=str(max(opt)) if opt else None, expiry_days=sum(D.expiry for D in days.values()))


_E: dict = {}


def early(und: str) -> dict:
    if und not in _E:
        p = K.EARLY / f"day_{und}.pkl"
        _E[und] = pickle.loads(p.read_bytes()) if p.exists() else {}
    return _E[und]


def sessions(und: str, lo: date, hi: date, warm: bool = False) -> list:
    """[(date, Day)] in date order for lo..hi: the early store before 20 Nov 2024, R51's stores from it. warm=True also
    returns days without options (index-only warm-up days); otherwise only days the option store covers."""
    M = mods()
    out = {}
    if lo < K.ARM_START:
        out.update({d: D for d, D in early(und).items() if lo <= d <= hi})
    if hi >= K.ARM_START:
        out.update({d: D for d, D in M["r51data"].load(und).items() if max(lo, K.ARM_START) <= d <= hi})
    res = sorted(out.items())
    if not warm:
        first = K.FIRST_OPTION_DAY.get(und, K.EARLY_START)
        res = [(d, D) for d, D in res if D.atm is not None and D.con and d >= first]
    return res


if __name__ == "__main__":
    mods()                           # puts r51data on the path (the Day class pickles under its module name)
    if len(sys.argv) >= 2 and sys.argv[1] == "build-early":
        for u in sys.argv[2:] or K.UNDS:
            print(build_early(u), flush=True)
    else:
        raise SystemExit(__doc__)
