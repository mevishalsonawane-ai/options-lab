"""R50 data: R43's expiry-day records (R25 `Day`s) and 5-minute index bars for the 5 indices, 2024 onward.

    python3 research/hunt/r50/r50data.py build [UND ...]   # <OUT>/days_<UND>.pkl, <OUT>/bars5_<UND>.pkl

Expiry days: R25's time-value test (`r25data.expiry_days`), run on the sessions with a 15:15 ATM price. The lake's last session has no next session, so R25's test
never calls it an expiry; R50 counts it when its own 15:15 ATM time value is below 0.6% of spot (PREREG.md; it adds
NIFTY's 13 Oct 2026 weekly expiry). Days before 20 Nov 2024 stay in the pickles only to warm up the GEX scale (R36
needs the previous 20 expiries); `load()` never returns them for trading.
"""
from __future__ import annotations

import pickle
import sys

import pandas as pd

import r50cfg as K
import r25data as D
import r43data as D43


_R25_EXPIRY_DAYS = D.expiry_days


def expiry_days_with_last(tv: pd.Series) -> list:
    """R25's rule on the sessions that HAVE a 15:15 time value (a session with no 15:15 ATM price is skipped, so an expiry
    is compared with the next session that has one), plus the last session when its own time value is < TV_MAX (it has
    no next session to compare)."""
    tv = tv.dropna().sort_index()
    out = _R25_EXPIRY_DAYS(tv)
    if len(tv) and tv.iloc[-1] < D.TV_MAX:
        out.append(tv.index[-1])
    return out


def build_days(und: str) -> list:
    saved = D.expiry_days
    D.expiry_days = expiry_days_with_last
    try:
        return D43.build_days(und)
    finally:
        D.expiry_days = saved


def days_path(und: str):
    return K.OUT / f"days_{und}.pkl"


def bars_path(und: str):
    return K.OUT / f"bars5_{und}.pkl"


def _prep(days: list) -> list:
    for d in days:
        D.clean(d)
        d.lot = K.lot(d.und, d.day)          # R50's lot table (the pickle holds the builder's)
    return days


def load(und: str, part: str) -> list:
    """part: 'design', 'final' (only study.py final asks), 'window' (both) or 'warm' (every day, incl. the GEX warm-up
    days before 20 Nov 2024 - never traded)."""
    days = pickle.loads(days_path(und).read_bytes())
    if part == "warm":
        return _prep(days)
    if part == "window":
        days = [d for d in days if K.period(d.day) is not None]
    elif part in ("design", "final"):
        days = [d for d in days if K.period(d.day) == part]
    else:
        raise ValueError(part)
    return _prep(days)


def calendar(und: str) -> list:
    """Every in-window expiry date (no prices): safe for counts in any period."""
    return [d.day for d in pickle.loads(days_path(und).read_bytes()) if K.period(d.day) is not None]


def load_bars(und: str) -> pd.DataFrame:
    return pickle.loads(bars_path(und).read_bytes())


if __name__ == "__main__":
    import r50data as _self
    if sys.argv[1:2] == ["build"]:
        K.OUT.mkdir(parents=True, exist_ok=True)
        for u in (sys.argv[2:] or K.UNDS):
            if not bars_path(u).exists():
                b = D43.bars5_from_minutes(D._index_frames(u))
                bars_path(u).write_bytes(pickle.dumps(b))
            ds = _self.build_days(u)
            days_path(u).write_bytes(pickle.dumps(ds))
            print(u, "days", len(ds), ds[0].day, ds[-1].day, "in window", sum(K.period(d.day) is not None for d in ds),
                  flush=True)
