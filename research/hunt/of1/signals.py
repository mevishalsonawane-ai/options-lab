"""OF1 signals, targets and regimes (PREREG §2, §3 regimes, §5). Point-in-time: a value at day t reads rows <= t only.

Information time: NSE publishes the participant files in the evening of t, NSDL's FPI row the day of its reporting date.
Every signal at t is therefore usable from the next session (t+1) on; targets start at t+1's open (primary) or t's
close (secondary, descriptive: it includes the overnight gap nobody can trade on this information).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

GROUPS = ("FII", "DII", "Pro", "Client")
WIN = 60                      # z-score window (trading days, ending at t)
REGIME_VOL = 20               # trend / range: 20-day return vs 20-day realised daily vol * sqrt(20)
VIX_MED = 250                 # high / low VIX: vs trailing 250-day median


def z(x: pd.Series, win: int = WIN) -> pd.Series:
    """(x - mean) / std over the `win` values ending at t (needs all `win`)."""
    m = x.rolling(win, min_periods=win).mean()
    s = x.rolling(win, min_periods=win).std()
    return (x - m) / s.replace(0, np.nan)


def build(wide: pd.DataFrame, fpi: pd.DataFrame | None = None) -> pd.DataFrame:
    """The 18 pre-registered signals, indexed by the participant file's date t.

    S1 z(d fut_net), S2 z(d long_share), S3 z(d opt_stance), S4 z(d put_write) for FII, DII, Pro, Client;
    S5 z(FPI cash net, reporting date == t); S6 = S1_FII + S5.
    """
    out = pd.DataFrame(index=wide.index)
    for g in GROUPS:
        out[f"S1_{g}"] = z(wide[f"{g}.fut_net"].diff())
        out[f"S2_{g}"] = z(wide[f"{g}.long_share"].diff())
        out[f"S3_{g}"] = z(wide[f"{g}.opt_stance"].diff())
        out[f"S4_{g}"] = z(wide[f"{g}.put_write"].diff())
    if fpi is not None and len(fpi) and "eq_exch_net_cr" in fpi:
        f = fpi["eq_exch_net_cr"].astype(float)
        f.index = pd.to_datetime(f.index)
        f = f[~f.index.duplicated(keep="last")].sort_index()
        # z over NSDL's own reporting days (rows <= the reporting date), then only a row REPORTED on t counts at t
        out["S5_FPI"] = z(f).reindex(wide.index)
        out["S6_FOREIGN"] = out["S1_FII"] + out["S5_FPI"]
    else:
        out["S5_FPI"] = np.nan
        out["S6_FOREIGN"] = np.nan
    return out


SIGNALS = [f"S{k}_{g}" for g in GROUPS for k in (1, 2, 3, 4)] + ["S5_FPI", "S6_FOREIGN"]


def targets(ix: pd.DataFrame, und: str, horizons=(1, 5, 20)) -> pd.DataFrame:
    """Forward returns in % indexed by t: oc1 = open(t+1) -> close(t+1); cc<h> = close(t) -> close(t+h).
    `ix`: the index-close table (load.index_closes), one row per session."""
    o = ix[f"{und}_open"].astype(float)
    c = ix[f"{und}_close"].astype(float)
    out = pd.DataFrame(index=ix.index)
    oc = (c / o - 1.0) * 100
    oc[(o <= 0) | (o == c.shift(1))] = np.nan        # an "open" equal to the previous close is a filler, not a print
    out["oc1"] = oc.shift(-1)
    for h in horizons:
        out[f"cc{h}"] = (c.shift(-h) / c - 1.0) * 100
    return out


def regimes(ix: pd.DataFrame, fut_expiries) -> pd.DataFrame:
    """Point-in-time regime labels at t (PREREG §3): trend/range, VIX high/low, expiry week (NIFTY monthly), year."""
    c = ix["NIFTY_close"].astype(float)
    r = np.log(c).diff()
    ret20 = c / c.shift(REGIME_VOL) - 1.0
    vol20 = r.rolling(REGIME_VOL, min_periods=REGIME_VOL).std() * np.sqrt(REGIME_VOL)
    out = pd.DataFrame(index=ix.index)
    out["trend"] = np.where(vol20.isna(), None, np.where(ret20.abs() > vol20, "trend", "range"))
    v = ix["VIX_close"].astype(float) if "VIX_close" in ix else pd.Series(np.nan, index=ix.index)
    med = v.rolling(VIX_MED, min_periods=120).median()
    out["vix"] = np.where(med.isna() | v.isna(), None, np.where(v > med, "high", "low"))
    wk = {(pd.Timestamp(e).isocalendar().year, pd.Timestamp(e).isocalendar().week) for e in fut_expiries}
    iso = ix.index.isocalendar()
    out["expiry_week"] = [((y, w) in wk) for y, w in zip(iso.year, iso.week)]
    out["year"] = ix.index.year
    return out
