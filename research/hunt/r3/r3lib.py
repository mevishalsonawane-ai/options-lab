"""R3 library: GOLDM EMA(8)/EMA(21)+ADX(14)>15 signal, futures-level forward returns, option-level simulation.
Conventions (fixed before any result was looked at):
- Bars anchored at 09:00 IST: a tf-minute bar covers minutes [540+k*tf, 540+(k+1)*tf-1]; label = bar START (as charts show);
  its close is known at its last minute (sig_min); entry = the next minute (sig_min+1) open.
- EMA = pandas ewm(span, adjust=False) on bar closes; ADX(14) Wilder; indicators carry over night (continuous bars).
- Cross: (e8-e21) changes sign on this bar's close and ADX > 15 on this bar -> side +1 (CE) / -1 (PE).
"""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import datetime as dt
from pathlib import Path
import numpy as np
import pandas as pd

SP = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt")
R3 = SP / "r3"
OPEN = 540
GOLDM_EXP = [dt.date.fromisoformat(x) for x in ['2025-08-27', '2025-09-19', '2025-10-29', '2025-11-28', '2025-12-29',
             '2026-01-29', '2026-02-26', '2026-03-25', '2026-04-28', '2026-05-29', '2026-06-26', '2026-07-29',
             '2026-08-28', '2026-09-25', '2026-10-29']]  # M4 build log (inferred from straddle jumps)
US_DST = [(dt.date(y, 3, 8 + (6 - dt.date(y, 3, 8).weekday()) % 7), dt.date(y, 11, 1 + (6 - dt.date(y, 11, 1).weekday()) % 7))
          for y in range(2014, 2028)]  # 2nd Sun Mar .. 1st Sun Nov


def us_dst(d):
    return any(a <= d < b for a, b in US_DST)


def sess_end(d):
    """MCX close in minutes-of-day IST: 23:30 during US DST, 23:55 otherwise."""
    return 23 * 60 + 30 if us_dst(d) else 23 * 60 + 55


def ema(x, n):
    return pd.Series(x).ewm(span=n, adjust=False).mean().values


def adx(h, l, c, n=14):
    h, l, c = (pd.Series(a) for a in (h, l, c))
    pc = c.shift(1)
    tr = pd.concat([h - l, (h - pc).abs(), (l - pc).abs()], axis=1).max(axis=1)
    up, dn = h.diff(), -l.diff()
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    mdm = np.where((dn > up) & (dn > 0), dn, 0.0)
    a = 1.0 / n
    atr = tr.ewm(alpha=a, adjust=False).mean()
    pdi = 100 * pd.Series(pdm).ewm(alpha=a, adjust=False).mean() / atr
    mdi = 100 * pd.Series(mdm).ewm(alpha=a, adjust=False).mean() / atr
    dx = 100 * (pdi - mdi).abs() / (pdi + mdi).replace(0, np.nan)
    return dx.ewm(alpha=a, adjust=False).mean().values


def minute_frame(ts, o, h, l, c):
    """-> DataFrame indexed 0..n with day (date), tod (min of day IST), o h l c; session-filtered 09:00..close-1."""
    m = pd.DataFrame(dict(o=o, h=h, l=l, c=c))
    ts = pd.DatetimeIndex(ts)
    m["day"] = ts.tz_localize(None).normalize().date if ts.tz is not None else ts.normalize().date
    m["tod"] = ts.hour * 60 + ts.minute
    end = m.day.map(lambda d: sess_end(d))
    m = m[(m.tod >= OPEN) & (m.tod < end) & (pd.Series([d.weekday() < 5 for d in m.day], index=m.index))]
    return m.reset_index(drop=True)


def make_bars(m, tf):
    m = m.copy()
    m["b"] = (m.tod - OPEN) // tf
    g = m.groupby(["day", "b"], sort=True)
    B = g.agg(o=("o", "first"), h=("h", "max"), l=("l", "min"), c=("c", "last"), last=("tod", "max")).reset_index()
    B["start"] = OPEN + B.b * tf
    B["sig_min"] = B.start + tf - 1
    end = B.day.map(sess_end)
    B["sig_min"] = np.minimum(B.sig_min, end - 1)
    return B.reset_index(drop=True)


def signals(B, fast=8, slow=21, thr=15.0):
    e1, e2 = ema(B.c.values, fast), ema(B.c.values, slow)
    ad = adx(B.h.values, B.l.values, B.c.values)
    d = e1 - e2
    dp = np.r_[np.nan, d[:-1]]
    up = (d > 0) & (dp <= 0) & (ad > thr)
    dn = (d < 0) & (dp >= 0) & (ad > thr)
    S = B[up | dn].copy()
    S["side"] = np.where(up, 1, -1)[up | dn]
    S["adx"] = ad[up | dn]
    return S.reset_index(drop=True)


def grid(m):
    """C[day_index, minute-540] closes, forward-filled within each day; days list."""
    days = sorted(m.day.unique())
    di = {d: i for i, d in enumerate(days)}
    C = np.full((len(days), 24 * 60 - OPEN), np.nan)
    C[m.day.map(di).values, m.tod.values - OPEN] = m.c.values
    C = pd.DataFrame(C.T).ffill().values.T
    return days, di, C


def fwd(m, S, H=60, cut=23 * 60 + 15, G=None):
    """H-min forward return (bp, signed by side) from the signal bar close (sig_min, ~ the next minute's entry) to the
    close at sig_min+H, capped at the 23:15 square-off (and session end - 1)."""
    days, di, C = G or grid(m)
    S = S.copy()
    d = S.day.map(di).values
    t0 = S.sig_min.values
    ends = np.array([sess_end(x) - 1 for x in S.day])
    t1 = np.minimum(np.minimum(t0 + H, cut), ends)
    ok = t1 > t0
    a = C[d, t0 - OPEN]
    b = C[d, np.where(ok, t1, t0) - OPEN]
    r = (b / a - 1) * 1e4
    r[~ok] = np.nan
    S["raw_bp"] = r
    S["bp"] = r * S.side.values
    return S


def sess_label(t):
    return np.where(t < 15 * 60, "AM", "PM")


def boot_ci(x, days=None, B=4000, seed=0):
    """Mean and 95% CI; resampling whole days (block) when days given."""
    x = np.asarray(x, float)
    ok = ~np.isnan(x)
    x = x[ok]
    if len(x) < 3:
        return (np.nan, np.nan, np.nan)
    rng = np.random.default_rng(seed)
    if days is None:
        bs = rng.choice(x, (B, len(x))).mean(1)
    else:
        dd = np.asarray(days)[ok]
        u, inv = np.unique(dd, return_inverse=True)
        sums = np.bincount(inv, weights=x, minlength=len(u)); cnt = np.bincount(inv, minlength=len(u))
        idx = rng.integers(0, len(u), (B, len(u)))
        bs = sums[idx].sum(1) / np.maximum(cnt[idx].sum(1), 1)
    return (x.mean(), np.percentile(bs, 2.5), np.percentile(bs, 97.5))
