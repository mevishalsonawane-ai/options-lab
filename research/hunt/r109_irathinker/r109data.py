"""R109 data: held-contract stores (R51's builder, Amendment A cleaning) on the MONTH series (and WEEK for the variant),
plus the NSE expiry calendar. No network.

    python research/hunt/r109_irathinker/r109data.py build [UND:SERIES ...]   # <R109_OUT>/day_<UND>_<SERIES>.pkl
"""
from __future__ import annotations

import os
import pickle
import sys
from datetime import date, timedelta
from functools import lru_cache

import numpy as np
import pandas as pd

import r109cfg as K

BUILDS = ("BANKNIFTY:MONTH", "FINNIFTY:MONTH", "SENSEX:MONTH", "NIFTY:MONTH", "MIDCPNIFTY:MONTH", "NIFTY:WEEK",
          "SENSEX:WEEK")


def flat():
    """A flat folder of symlinks to the lake's index and option files (R51 reads K.LAKE / <file>)."""
    f = K.OUT / "flat"
    f.mkdir(parents=True, exist_ok=True)
    for sub in ("index_1m", "options_rolling_1m"):
        for p in sorted((K.LAKE / sub).glob("*.parquet")):
            q = f / p.name
            if not q.exists():
                try:
                    q.symlink_to(p.resolve())
                except FileExistsError:          # a parallel build made it first
                    pass
    return f


def r51():
    """R51's config and store builder pointed at R109's window and folder (Amendment A cleaning on)."""
    os.environ["R51_CLEAN"] = "1"
    import r51cfg as R
    R.LAKE, R.OUT = flat(), K.OUT
    R.WARM_START, R.START, R.END = K.WARM_START, K.START, K.END
    R.CLEAN, R.STALE = True, False
    import r51data
    return R, r51data


def build(und: str, series: str) -> dict:
    R, D = r51()
    days = D.index_days(und)
    log = D.add_options(und, days, series=series, clean=True, stale=False)
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / f"day_{und}_{series}.pkl", "wb") as f:
        pickle.dump(days, f, protocol=pickle.HIGHEST_PROTOCOL)
    tr = [d for d, x in days.items() if x.atm is not None]
    return dict(und=und, series=series, days=len(days), option_days=len(tr), first=str(min(tr)), last=str(max(tr)),
                expiry_days=sum(x.expiry for x in days.values()), cleaned_rows=int(len(log)) if log is not None else 0)


_CACHE: dict = {}


def load(und: str, series: str = "MONTH") -> dict:
    key = (und, series)
    if key not in _CACHE:
        r51()                                       # the Day class must be importable for unpickling
        with open(K.OUT / f"day_{und}_{series}.pkl", "rb") as f:
            _CACHE[key] = pickle.load(f)
    return _CACHE[key]


def drop(und: str, series: str = "MONTH"):
    _CACHE.pop((und, series), None)


# ------------------------------------------------------------------------------------------------ expiry calendar
@lru_cache(None)
def bhav_expiries(und: str) -> tuple:
    """(all expiries, monthly expiries) of an NSE index from the bhavcopy rows with open interest (R102's rule: the
    monthly is the last expiry of each month). Empty for SENSEX (the files are NSE only)."""
    parts = []
    for p in sorted((K.LAKE / "participants" / "bhav_index").glob("bhav_index_*.parquet")):
        b = pd.read_parquet(p, columns=["date", "und", "kind", "expiry", "oi_qty", "contracts"])
        parts.append(b[(b.und == und) & b.kind.isin(["CE", "PE"]) & ((b.oi_qty > 0) | (b.contracts > 0))])
    if not parts:
        return (), ()
    b = pd.concat(parts)
    es = sorted({pd.Timestamp(x).date() for x in b.expiry.unique()})
    by = {}
    for e in es:
        by.setdefault((e.year, e.month), []).append(e)
    return tuple(es), tuple(sorted(max(v) for v in by.values()))


def expiry_dates(und: str, series: str) -> list:
    """The series' expiry dates. First R51's store flag (the day the series' ATM straddle time value collapses and the
    next session's jumps: the day the contract we price really expired, holiday shifts included). Then a calendar date
    where no flag lies within 3 days: NSE from the bhavcopy (monthly = the last expiry of each month for MONTH, every
    expiry for WEEK; the bhavcopy keeps holiday-shifted contracts at their old date, which the 3-day rule drops), SENSEX
    (no bhavcopy) from the BSE monthly rule for MONTH months with no flag: the last Thursday, moved to the previous
    session when that is a holiday."""
    days = load(und, series)
    flag = sorted(d for d, x in days.items() if x.expiry)
    allx, mon = bhav_expiries(und)
    if und in K.BSE:
        cal = []
        if series == "MONTH":
            sess = sorted(days)
            have = {(d.year, d.month) for d in flag}
            y, m = K.START.year, K.START.month
            while (y, m) <= (K.END.year, K.END.month + 1):
                if (y, m) not in have:
                    e = _last_thursday(y, m)
                    while e not in days and e > date(y, m, 1) and e <= sess[-1]:
                        e -= timedelta(days=1)
                    cal.append(e)
                y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    else:
        cal = list(mon if series == "MONTH" else allx)
    keep = [c for c in cal if not any(abs((c - f).days) <= 3 for f in flag)]
    return sorted(set(flag) | set(keep))


def _last_thursday(y: int, m: int) -> date:
    d = date(y + (m == 12), m % 12 + 1, 1) - timedelta(days=1)
    while d.weekday() != 3:
        d -= timedelta(days=1)
    return d


def contract_expiry(exps: list, d: date) -> date | None:
    """The nearest expiry on or after day d."""
    import bisect
    i = bisect.bisect_left(exps, d)
    return exps[i] if i < len(exps) else None


@lru_cache(None)
def daily_closes(und: str) -> pd.Series:
    x = pd.read_parquet(K.LAKE / "index_daily" / "index_daily.parquet")
    x = x[x.symbol == und].copy()
    x["date"] = pd.to_datetime(x.date).dt.date
    return x.drop_duplicates("date").set_index("date").close.sort_index()


def rv20_before(und: str, d: date) -> float:
    """IraThinker's OptionsMath.realizedVol on the daily closes BEFORE day d (point in time): population stdev of the last
    20 log returns x sqrt(252) x 100, clamped 10..90 as the engines do. NaN when fewer than 21 closes. Falls back to the
    1-minute index's daily last closes when index_daily lacks the index (MIDCPNIFTY)."""
    s = daily_closes(und)
    if len(s) < 30:
        s = _closes_from_1m(und)
    s = s[s.index < d]
    c = s.values[-21:]
    if len(c) < 21 or not np.all(c > 0):
        return float("nan")
    r = np.diff(np.log(c))
    v = float(np.sqrt(((r - r.mean()) ** 2).mean()) * np.sqrt(252) * 100)
    return max(10.0, min(v, 90.0))


@lru_cache(None)
def _closes_from_1m(und: str) -> pd.Series:
    days = load(und, "MONTH")
    out = {}
    for d, x in days.items():
        c = x.idx[3]
        ok = np.flatnonzero(np.isfinite(c))
        if len(ok):
            out[d] = float(c[ok[-1]])
    return pd.Series(out).sort_index()


if __name__ == "__main__":
    import r109data as _self  # noqa: F401
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for spec in sys.argv[2:] or BUILDS:
            u, s = spec.split(":")
            print(build(u, s), flush=True)
    else:
        raise SystemExit(__doc__)
