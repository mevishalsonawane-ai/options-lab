"""h43: the outside report's 5 BANKNIFTY strategies as obuy signal sets.

Indicators are computed on the continuous BANKNIFTY spot bar series (all sessions back to back, expiry days included,
so EMA / ADX / Bollinger carry over from the previous day as a pandas-style backtester would). Bars are anchored at
09:15: a tf-minute bar covers minutes [555 + k*tf, 555 + (k+1)*tf - 1]; its CLOSE is known at its last minute, which
is the signal minute (sig_min); the engine fills at the option's open at sig_min + 1 (= the next bar's open).

Strategies (as the report states them; ambiguities resolved as written in PREREG.md):
  ema  : EMA9 crosses EMA21 on the bar close and ADX(14) > 20 -> CE (up) / PE (down). max 2/day.
  bb   : bar close above upper BB(20,2) -> CE, below lower -> PE (state: any such close while flat). max 2/day.
  orb15: range 09:15-09:29 (spot 1-min highs/lows); first bar (starting >= 09:30) closing beyond -> CE/PE. max 1/day.
  pe   : bar closing at or after 14:00 with 15 <= ADX(14) <= 25 -> PE (state). max 2/day.
  orb30: range 09:15-09:44; first bar (starting >= 09:45) closing beyond -> CE/PE. max 1/day.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

OPEN = 555
W = 375


def spot_series(ix):
    """Continuous 1-min OHLC (n_days*375,) forward-filled, plus the day list."""
    M = ix.mat()
    o, h, l, c = (M[k].reshape(-1).copy() for k in ("o", "h", "l", "c"))
    c = pd.Series(c).ffill().values
    for a in (o, h, l):
        bad = np.isnan(a)
        a[bad] = c[bad]
    return ix.days, o, h, l, c


def bars(days, o, h, l, c, tf):
    """tf-minute bars per day (incomplete last bar of the day kept: 375 = 25*15 = 75*5)."""
    D = len(days)
    nb = int(np.ceil(W / tf))
    pad = nb * tf - W
    def rs(a, fill):
        a = a.reshape(D, W)
        if pad:
            a = np.concatenate([a, np.full((D, pad), np.nan)], axis=1)
        return a.reshape(D, nb, tf)
    O = rs(o, np.nan)[:, :, 0]
    H = np.nanmax(rs(h, np.nan), axis=2)
    L = np.nanmin(rs(l, np.nan), axis=2)
    Cb = rs(c, np.nan)
    last = np.minimum(np.arange(nb) * tf + tf - 1, W - 1)
    Cl = c.reshape(D, W)[:, last]
    df = pd.DataFrame(dict(o=O.ravel(), h=H.ravel(), l=L.ravel(), c=Cl.ravel()))
    df["di"] = np.repeat(np.arange(D), nb)
    df["sig_min"] = np.tile(OPEN + last, D)
    df["start"] = np.tile(OPEN + np.arange(nb) * tf, D)
    return df


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


def make(ix, strat, tf, reset=False):
    """reset=True (part-A diagnostic only): indicators restart every session (no overnight carry)."""
    days, o, h, l, c = spot_series(ix)
    b = bars(days, o, h, l, c, tf)
    if reset:
        parts = [make_b(b[b.di == i].reset_index(drop=True), strat, days, h, l) for i in b.di.unique()]
        return _fmt(pd.concat(parts), days)
    return _fmt(make_b(b, strat, days, h, l), days)


def make_b(b, strat, days, h, l):
    out = None
    if strat in ("ema", "champ", "champM"):
        fast, thr = (9, 20) if strat == "ema" else (8, 15)
        e9, e21 = ema(b.c.values, fast), ema(b.c.values, 21)
        ad = adx(b.h.values, b.l.values, b.c.values)
        d = e9 - e21
        dp = np.r_[np.nan, d[:-1]]
        up = (d > 0) & (dp <= 0) & (ad > thr)
        dn = (d < 0) & (dp >= 0) & (ad > thr)
        out = b[up | dn].assign(side=np.where(up, 1, -1)[up | dn])
    elif strat == "bb":
        m = pd.Series(b.c.values).rolling(20).mean().values
        s = pd.Series(b.c.values).rolling(20).std(ddof=0).values
        up = b.c.values > m + 2 * s
        dn = b.c.values < m - 2 * s
        out = b[up | dn].assign(side=np.where(up, 1, -1)[up | dn])
    elif strat in ("orb15", "orb30"):
        R = 15 if strat == "orb15" else 30
        D = len(days)
        hh = h.reshape(D, W)[:, :R].max(axis=1)
        ll = l.reshape(D, W)[:, :R].min(axis=1)
        x = b[b.start >= OPEN + R].copy()
        x["H"], x["L"] = hh[x.di.values], ll[x.di.values]
        up = x.c.values > x.H.values
        dn = x.c.values < x.L.values
        x = x[up | dn].assign(side=np.where(up, 1, -1)[up | dn])
        out = x.groupby("di", sort=False).head(1)        # max 1/day: the first breakout only
    elif strat == "pe":
        ad = adx(b.h.values, b.l.values, b.c.values)
        ok = (b.sig_min.values >= 14 * 60) & (ad >= 15) & (ad <= 25)
        out = b[ok].assign(side=-1)
    return out


def _fmt(out, days):
    out = out[out.sig_min <= 15 * 60 + 13]                 # an entry needs a fill before the 15:15 square-off
    dd = np.array(days, dtype=object)
    sig = pd.DataFrame(dict(und="BANKNIFTY", day=dd[out.di.values], sig_min=out.sig_min.values.astype(int),
                            side=out.side.values.astype(int)))
    sig["book"] = "bn"
    return sig


STRATS = {  # name -> (max per day, exit key)
    "ema": (2, "T40"), "bb": (2, "T15"), "orb15": (1, "T40"), "pe": (2, "T30"), "orb30": (1, "EOD60"),
    "champ": (2, "CHAMP"), "champM": (2, "CHAMP"),     # champM = the same on the nearest MONTHLY (tab_month.npz)
}
TFS = (1, 5, 15)


def variants():
    v = [(s, tf, STRATS[s][1]) for s in STRATS if not s.startswith("champ") for tf in TFS]
    v += [("orb30", tf, "EOD120") for tf in TFS]
    v += [("orb30", tf, "T40") for tf in TFS]      # PREREG amendment: part A shows the report's orb30 had a time stop
    # amendment 2: the parameter document's per-timeframe time stops, and the CHAMPION (nearest and monthly)
    v += [("ema", 15, "T60"), ("bb", 5, "T30"), ("bb", 15, "T45"), ("orb15", 15, "T60"), ("pe", 15, "T45"),
          ("orb30", 1, "T20"), ("champ", 5, "CHAMP"), ("champM", 5, "CHAMP")]
    return v
