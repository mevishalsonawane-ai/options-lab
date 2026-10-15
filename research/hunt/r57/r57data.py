"""R57 data: the lake's index minutes and HELD option contracts (cleaned), chain context, VIX, daily ATR and brain
features, one store per index.

    python research/hunt/r57/r57data.py build [UND ...]     # writes <R57_OUT>/day_<UND>.pkl and feat_<UND>.pkl

The day store is R51's (r51data.Day: idx (4, 375), atm (375,), con {(side, strike): (5, 375)}, expiry), so R51's engine
prices R57's trades the same way. R57 adds, per day:
  ctx   {"pcr5", "pcr10", "iv_atm", "iv_skew"}: (375,) option-chain context at each minute (latest value at or before it)
  vix   (375,) India VIX close carried forward within the day; vix_prev: the previous session's last VIX close
  atr   ATR14 of the 14 completed daily candles before the day (SoloMidday's ShadowRules.atr14)
  dte   sessions to the index's next expiry day (0 on it; NaN past the last known expiry)

Cleaning (PREREG section 2), before any contract is built:
  - |strike - spot| > 12 steps: mislabelled rows (R25);
  - foreign rows: an option row whose own spot is more than 3% off the index close at that minute (R47 foreign_rows);
  - bad ticks: a contract-minute whose close is more than 50% away from both neighbouring closes of the same contract
    that day, the neighbours within 50% of each other (the lake's spikes.py definition), is dropped.
"""
from __future__ import annotations

import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

import r57cfg as K
import r51data
from r51data import Day, expiry_days, _read  # noqa: F401  (Day: the pickled class)

TV_ROW = K.row(15, 15)
FEATURES_BRAIN = ("ret_15m", "ret_60m", "ret_day", "gap_pct", "day_range_vs_adr10", "pos_in_day_range", "rv_30m",
                  "er_day", "dist_tpavg_pct", "range_30m_pct", "dist_prev_close_pct", "or30_state")


def _years():
    return range(K.WARM_START.year, K.END.year + 1)


# ------------------------------------------------------------------------------------------------ cleaning (pure)
def foreign_mask(spot: np.ndarray, ref: np.ndarray, tol: float = K.FOREIGN_TOL) -> np.ndarray:
    """True for rows whose own spot is more than `tol` off the index reference price (or has no reference)."""
    with np.errstate(all="ignore"):
        r = np.asarray(spot, float) / np.asarray(ref, float)
    return ~(np.isfinite(r) & (np.abs(r - 1.0) <= tol))


def spike_mask(day, side, strike, r, close, jump: float = K.SPIKE_JUMP) -> np.ndarray:
    """spikes.py rule on contract-minutes (rows already unique per day/side/strike/minute): True where the close is more
    than `jump`x away from both neighbouring closes of the same contract that day, the neighbours within `jump`x of each
    other. The first and last bar of a contract-day are never spikes."""
    x = pd.DataFrame(dict(day=day, side=side, strike=strike, r=r, c=np.asarray(close, float)))
    x["i"] = np.arange(len(x))
    x = x.sort_values(["day", "side", "strike", "r"], kind="stable")
    g = x.groupby(["day", "side", "strike"], sort=False, observed=True).c
    p, n, c = g.shift(1), g.shift(-1), x.c
    hi, lo = np.maximum(p, n), np.minimum(p, n)
    sp = (((c > jump * hi) | (c * jump < lo)) & (hi <= jump * lo)).fillna(False).values
    out = np.zeros(len(x), bool)
    out[x.i.values] = sp
    return out


def ffill_row(a: np.ndarray) -> np.ndarray:
    """Carry the last finite value forward along a (375,) row (point in time: never backwards)."""
    s = pd.Series(a, dtype=float).ffill()
    return s.values


# ------------------------------------------------------------------------------------------------ index, VIX, ATR
def index_days(und: str) -> dict:
    return r51data.index_days(und)


def daily_atr14(days: dict) -> dict:
    """ATR14 for each day: mean true range of the 14 completed daily candles before it (needs 14)."""
    ds = sorted(days)
    H, L, C = [], [], []
    for d in ds:
        a = days[d].idx
        H.append(np.nanmax(a[1]) if np.isfinite(a[1]).any() else np.nan)
        L.append(np.nanmin(a[2]) if np.isfinite(a[2]).any() else np.nan)
        cc = a[3][np.isfinite(a[3])]
        C.append(cc[-1] if len(cc) else np.nan)
    H, L, C = map(np.array, (H, L, C))
    pc = np.r_[np.nan, C[:-1]]
    tr = np.fmax(H - L, np.fmax(np.abs(H - pc), np.abs(L - pc)))
    tr[0] = H[0] - L[0]
    out = {}
    for i, d in enumerate(ds):
        w = tr[max(0, i - 14):i]
        out[d] = float(np.mean(w)) if len(w) == 14 and np.isfinite(w).all() else np.nan
    return out


def vix_days() -> dict:
    parts = [_read(K.LAKE / f"index_1m_INDIAVIX_{y}.parquet", ["ts", "close"]) for y in _years()
             if (K.LAKE / f"index_1m_INDIAVIX_{y}.parquet").exists()]
    if not parts:
        return {}
    x = pd.concat(parts, ignore_index=True)
    x = x[(x.close > 0)].drop_duplicates(["day", "r"])
    out = {}
    for d, g in x.groupby("day", sort=True):
        a = np.full(K.NROWS, np.nan)
        a[g.r.values] = g.close.values
        out[d] = a
    return out


def dte_map(days: dict) -> dict:
    ds = sorted(d for d in days if days[d].atm is not None)
    exp = [i for i, d in enumerate(ds) if days[d].expiry]
    out, j = {}, 0
    for i, d in enumerate(ds):
        while j < len(exp) and exp[j] < i:
            j += 1
        out[d] = float(exp[j] - i) if j < len(exp) else np.nan
    return out


# ------------------------------------------------------------------------------------------------ options
def add_options(und: str, days: dict) -> dict:
    """Held contracts (cleaned) + chain context + expiry flags. Returns cleaning counts."""
    step = K.STEP[und]
    tv, counts = {}, dict(rows=0, mislabelled=0, foreign=0, spikes=0)
    for y in _years():
        p = K.LAKE / f"opt_{und}_WEEK_{y}.parquet"
        if not p.exists():
            continue
        o = _read(p, ["ts", "offset", "side", "strike", "spot", "open", "high", "low", "close", "volume", "oi", "iv"])
        o = o[(o.day >= K.START) & (o.day <= K.END)]
        o = o[o.day.isin(days.keys())]
        counts["rows"] += len(o)
        o["side"] = o.side.astype(str).str[0].str.upper()
        bad = (o.strike - o.spot).abs() > 12 * step
        counts["mislabelled"] += int(bad.sum())
        o = o[~bad]
        # foreign rows: the index close at that minute, carried forward within the day
        ref = np.full(len(o), np.nan)
        dd, rr = o.day.values, o.r.values
        cache = {}
        for d in np.unique(dd):
            cache[d] = ffill_row(days[d].idx[3])
        for d in cache:
            m = dd == d
            ref[m] = cache[d][rr[m]]
        fm = foreign_mask(o.spot.values, ref)
        counts["foreign"] += int(fm.sum())
        o = o[~fm]
        # bad ticks on contract-minutes
        u = o.drop_duplicates(["day", "side", "strike", "r"], keep="first")
        sm = spike_mask(u.day.values, u.side.values, u.strike.values, u.r.values, u.close.values)
        spikes = u[sm][["day", "side", "strike", "r"]]
        counts["spikes"] += len(spikes)
        if len(spikes):
            key = pd.MultiIndex.from_frame(o[["day", "side", "strike", "r"]])
            o = o[~key.isin(pd.MultiIndex.from_frame(spikes))]
        for d, g in o.groupby("day", sort=True):
            D = days[d]
            a0 = g[g.offset == 0].drop_duplicates(["side", "r"])
            atm = np.full(K.NROWS, np.nan)
            a0c = a0.drop_duplicates("r")
            atm[a0c.r.values] = a0c.strike.values
            D.atm = atm
            # chain context (point in time: the latest minute's value at or before each row)
            ctx = {}
            for nm, lim in (("pcr5", 5), ("pcr10", 10)):
                w = g[g.offset.abs() <= lim]
                s = w.groupby(["r", "side"]).oi.sum().unstack("side")
                a = np.full(K.NROWS, np.nan)
                if "C" in s and "P" in s:
                    with np.errstate(all="ignore"):
                        v = (s["P"] / s["C"]).replace([np.inf, -np.inf], np.nan)
                    a[v.index.values] = v.values
                ctx[nm] = ffill_row(a)
            ivc = a0[a0.side == "C"].set_index("r").iv
            ivp = a0[a0.side == "P"].set_index("r").iv
            a = np.full(K.NROWS, np.nan)
            both = ivc.index.intersection(ivp.index)
            a[both.values] = (ivc[both].values + ivp[both].values) / 2
            ctx["iv_atm"] = ffill_row(a)
            pm = g[(g.side == "P") & (g.offset == -3)].drop_duplicates("r").set_index("r").iv
            cm = g[(g.side == "C") & (g.offset == 3)].drop_duplicates("r").set_index("r").iv
            a = np.full(K.NROWS, np.nan)
            both = pm.index.intersection(cm.index)
            a[both.values] = pm[both].values - cm[both].values
            ctx["iv_skew"] = ffill_row(a)
            for k_ in ctx:
                ctx[k_] = np.where(np.isfinite(ctx[k_]) & (ctx[k_] > 0) | (k_ == "iv_skew"), ctx[k_], np.nan)
            D.ctx = {k_: v.astype(np.float32) for k_, v in ctx.items()}
            gg = g.drop_duplicates(["side", "strike", "r"], keep="first")
            for (s_, k), h in gg.groupby(["side", "strike"], sort=False):
                c = np.full((5, K.NROWS), np.nan, dtype=np.float32)
                for i, col in enumerate(("open", "high", "low", "close", "volume")):
                    c[i, h.r.values] = h[col].values
                D.con[(s_, float(k))] = c
            a = a0c[(a0c.r <= TV_ROW) & (a0c.r >= TV_ROW - 30)]
            if len(a):
                last = a.iloc[a.r.values.argmax()]
                k = float(last.strike)
                ce, pe = D.con.get(("C", k)), D.con.get(("P", k))
                r_ = int(last.r)
                if ce is not None and pe is not None and np.isfinite(ce[3, r_]) and np.isfinite(pe[3, r_]):
                    spot = float(last.spot)
                    tv[d] = (ce[3, r_] + pe[3, r_] - abs(spot - k)) / spot * 100
        del o
    for d in expiry_days(pd.Series(tv)):
        days[d].expiry = True
    return counts


# ------------------------------------------------------------------------------------------------ brain features
def minutes_frame(days: dict) -> pd.DataFrame:
    """The brain cube's minutes layout (375 rows a session, flag 'ok' / 'gap') from the index day store."""
    rows = []
    for d in sorted(days):
        a = days[d].idx
        base = pd.Timestamp(d) + pd.Timedelta(minutes=K.OPEN_MIN)
        ok = np.isfinite(a[3]) & np.isfinite(a[0])
        rows.append(pd.DataFrame(dict(ts=base + pd.to_timedelta(np.arange(K.NROWS), unit="min"), day=d,
                                      minute=np.arange(K.NROWS), open=a[0], high=a[1], low=a[2], close=a[3],
                                      flag=np.where(ok, "ok", "gap"))))
    return pd.concat(rows, ignore_index=True)


def brain_features(und: str, days: dict) -> dict:
    """{day: (375, len(FEATURES_BRAIN)) float32} computed with research.brain.features (point in time)."""
    from research.brain.features import registry
    m = minutes_frame(days)
    f = registry.compute(m, None, und, names=list(FEATURES_BRAIN))
    arr = f[list(FEATURES_BRAIN)].to_numpy(np.float32)
    out = {}
    ds = sorted(days)
    for i, d in enumerate(ds):
        out[d] = arr[i * K.NROWS:(i + 1) * K.NROWS]
    return out


# ------------------------------------------------------------------------------------------------ build / load
def build(und: str) -> dict:
    days = index_days(und)
    counts = add_options(und, days)
    atr = daily_atr14(days)
    vix = vix_days()
    vd = sorted(vix)
    vprev = {}
    for i, d in enumerate(vd):
        if i:
            pa = vix[vd[i - 1]]
            pa = pa[np.isfinite(pa)]
            vprev[d] = float(pa[-1]) if len(pa) else np.nan
    dte = dte_map(days)
    for d, D in days.items():
        D.atr = atr.get(d, np.nan)
        D.vix = ffill_row(vix[d]).astype(np.float32) if d in vix else np.full(K.NROWS, np.nan, np.float32)
        D.vix_prev = vprev.get(d, np.nan)
        D.dte = dte.get(d, np.nan)
        if not hasattr(D, "ctx"):
            D.ctx = None
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / f"day_{und}.pkl", "wb") as f:
        pickle.dump(days, f, protocol=pickle.HIGHEST_PROTOCOL)
    feats = brain_features(und, days)
    with open(K.OUT / f"feat_{und}.pkl", "wb") as f:
        pickle.dump(dict(names=FEATURES_BRAIN, days=feats), f, protocol=pickle.HIGHEST_PROTOCOL)
    tradable = [d for d, D in days.items() if d >= K.START and D.atm is not None]
    return dict(und=und, days=len(days), option_days=len(tradable), first=str(min(tradable)), last=str(max(tradable)),
                expiry_days=sum(D.expiry for D in days.values()), **counts)


_CACHE: dict = {}


def load(und: str) -> dict:
    if und not in _CACHE:
        with open(K.OUT / f"day_{und}.pkl", "rb") as f:
            _CACHE[und] = pickle.load(f)
        r51data._CACHE[und] = _CACHE[und]
    return _CACHE[und]


_FEAT: dict = {}


def load_feat(und: str) -> dict:
    if und not in _FEAT:
        with open(K.OUT / f"feat_{und}.pkl", "rb") as f:
            _FEAT[und] = pickle.load(f)
    return _FEAT[und]


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            print(build(u), flush=True)
