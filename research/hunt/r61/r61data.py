"""R61 data: R57's day stores (rebuilt in R61's cache) plus R61's chain snapshots and features, one store per index,
and the cross-day context (ranks, events, other index expiries, participant OI).

    python research/hunt/r61/r61data.py build [UND ...]     # R57 day store + chain_<UND>.pkl for each index
    python research/hunt/r61/r61data.py context              # ctx.pkl (needs all four chain stores)

chain_<UND>.pkl: {day: dict(f={name: (375,) float32} from r61chain.day_features,
                            snap={"strike", "iv", "close": (2, 21, 375) float32} for the held contract's Greeks,
                            term=(375,) float32 or None)}
The option rows are cleaned exactly as R57 (r57data.foreign_mask / spike_mask, |strike - spot| <= 12 steps) before any
chain feature is computed.
"""
from __future__ import annotations

import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

import r61cfg as K
import r57data
import r61chain as C
import r61events
from r51data import _read

SNAP_KEEP = ("strike", "iv", "close")


def _years():
    return range(K.START.year, K.END.year + 1)


# ------------------------------------------------------------------------------------------------ cleaning (R57's)
def clean(und: str, o: pd.DataFrame, days: dict) -> tuple:
    """R57's cleaning on raw option rows of one file. Returns (rows kept, counts)."""
    step = K.K57.STEP[und]
    counts = dict(rows=len(o), mislabelled=0, foreign=0, spikes=0)
    o = o[(o.day >= K.START) & (o.day <= K.END) & o.day.isin(days.keys())].copy()
    o["side"] = o.side.astype(str).str[0].str.upper()
    bad = (o.strike - o.spot).abs() > 12 * step
    counts["mislabelled"] = int(bad.sum())
    o = o[~bad]
    ref = np.full(len(o), np.nan)
    dd, rr = o.day.values, o.r.values
    for d in np.unique(dd):
        m = dd == d
        ref[m] = r57data.ffill_row(days[d].idx[3])[rr[m]]
    fm = r57data.foreign_mask(o.spot.values, ref)
    counts["foreign"] = int(fm.sum())
    o = o[~fm]
    u = o.drop_duplicates(["day", "side", "strike", "r"], keep="first")
    sm = r57data.spike_mask(u.day.values, u.side.values, u.strike.values, u.r.values, u.close.values)
    spikes = u[sm][["day", "side", "strike", "r"]]
    counts["spikes"] = len(spikes)
    if len(spikes):
        key = pd.MultiIndex.from_frame(o[["day", "side", "strike", "r"]])
        o = o[~key.isin(pd.MultiIndex.from_frame(spikes))]
    return o, counts


def atm_iv_minutes(g: pd.DataFrame) -> np.ndarray:
    """(375,) ATM IV (mean of offset-0 call and put IV), slot-carried forward (for the MONTH file's term slope)."""
    snap = C.snapshot(g)
    ivc, ivp = snap["snap"]["iv"][0, 10], snap["snap"]["iv"][1, 10]
    return np.where((ivc > 0) & (ivp > 0), (ivc + ivp) / 2, np.nan)


# ------------------------------------------------------------------------------------------------ build
COLS = ["ts", "offset", "side", "strike", "spot", "close", "volume", "oi", "iv"]


def build_chain(und: str, days: dict, with_flip: bool = True) -> dict:
    out, counts = {}, dict(rows=0, mislabelled=0, foreign=0, spikes=0)
    term = {}
    if und == "NIFTY":                     # NIFTY's monthly contract differs from its weekly one: term structure
        for y in _years():
            p = K.LAKE / f"opt_{und}_MONTH_{y}.parquet"
            if p.exists():
                o, _ = clean(und, _read(p, COLS), days)
                for d, g in o.groupby("day", sort=True):
                    term[d] = atm_iv_minutes(g)
                del o
    for y in _years():
        p = K.LAKE / f"opt_{und}_WEEK_{y}.parquet"
        if not p.exists():
            continue
        o, c = clean(und, _read(p, COLS), days)
        for k in counts:
            counts[k] += c[k]
        for d, g in o.groupby("day", sort=True):
            D = days[d]
            snap = C.snapshot(g)
            f = C.day_features(snap, D.idx[3], D.dte, with_flip=with_flip)
            if und == "NIFTY":
                tw = term.get(d)
                f["term_slope"] = (tw - f["iv_atm"]) if tw is not None else np.full(K.NROWS, np.nan)
            else:
                f["term_slope"] = np.full(K.NROWS, np.nan)
            out[d] = dict(f={k: v.astype(np.float32) for k, v in f.items()},
                          snap={k: snap["snap"][k].astype(np.float32) for k in SNAP_KEEP})
        del o
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / f"chain_{und}.pkl", "wb") as fh:
        pickle.dump(out, fh, protocol=pickle.HIGHEST_PROTOCOL)
    return dict(und=und, chain_days=len(out), **counts)


def build(und: str) -> dict:
    info = r57data.build(und)
    r57data._CACHE.pop(und, None)
    days = r57data.load(und)
    info.update(build_chain(und, days))
    return info


# ------------------------------------------------------------------------------------------------ cross-day context
def fii_long_share(sessions: list, raw=None) -> dict:
    """{session: (FII index-futures long share from the PREVIOUS file, its change vs the file before)} from the NSE
    participant-wise OI files (published after 18:30, so the previous session's file is known before the open)."""
    sys.path.insert(0, str(K.HERE.parent / "of1"))
    import load as of1load
    raw = K.LAKE if raw is None else raw
    part, _ = of1load.participants("oi", raw)
    if part.empty:
        return {}
    w = of1load.wide(part)
    s = w["FII.long_share"].dropna()
    fd = [x.date() for x in s.index]
    vals = s.values
    out = {}
    j = -1
    for d in sorted(sessions):
        while j + 1 < len(fd) and fd[j + 1] < d:
            j += 1
        if j >= 0:
            prev = vals[j - 1] if j >= 1 else np.nan
            out[d] = (float(vals[j]), float(vals[j] - prev) if np.isfinite(prev) else np.nan)
    return out


def context(chains: dict, stores: dict, fii: dict | None = None) -> dict:
    """{und: {day: dict(pcr_rank5, pcr_rank20, iv_rank60, vix_rank60: (375,), other_expiry_today, event_rbi_budget,
    post_us_event, fii_fut_long_share, fii_fut_long_chg: float)}}. Ranks use only sessions from START and before
    the day."""
    sessions = sorted({d for u in stores for d, D in stores[u].items() if d >= K.START and D.atm is not None})
    ev = r61events.flags(sessions)
    fii = {} if fii is None else fii
    exp = {u: {d for d, D in stores[u].items() if D.expiry} for u in stores}
    out = {}
    for u, ch in chains.items():
        ds = sorted(d for d in ch if d >= K.START)
        hp, hi, hv = [], [], []
        res = {}
        for d in ds:
            f = ch[d]["f"]
            D = stores[u][d]
            vix = np.asarray(D.vix, float) if getattr(D, "vix", None) is not None else np.full(K.NROWS, np.nan)
            e = ev.get(d, (0.0, 0.0))
            fl = fii.get(d, (np.nan, np.nan))
            res[d] = dict(pcr_rank5=C.same_row_rank(hp, f["pcr_oi"], 5, 4),
                          pcr_rank20=C.same_row_rank(hp, f["pcr_oi"], 20, 15),
                          iv_rank60=C.same_row_minmax(hi, f["iv_atm"], 60, 40),
                          vix_rank60=C.same_row_rank(hv, vix, 60, 40),
                          other_expiry_today=float(any(d in exp[v] for v in exp if v != u)),
                          event_rbi_budget=e[0], post_us_event=e[1],
                          fii_fut_long_share=fl[0], fii_fut_long_chg=fl[1])
            hp.append(np.asarray(f["pcr_oi"], float))
            hi.append(np.asarray(f["iv_atm"], float))
            hv.append(vix)
            hp, hi, hv = hp[-60:], hi[-60:], hv[-60:]
        out[u] = res
    return out


_CHAIN: dict = {}
_CTX: dict = {}


def load_chain(und: str) -> dict:
    if und not in _CHAIN:
        with open(K.OUT / f"chain_{und}.pkl", "rb") as fh:
            _CHAIN[und] = pickle.load(fh)
    return _CHAIN[und]


def load_ctx() -> dict:
    if not _CTX:
        with open(K.OUT / "ctx.pkl", "rb") as fh:
            _CTX.update(pickle.load(fh))
    return _CTX


def build_context() -> dict:
    stores = {u: r57data.load(u) for u in K.UNDS}
    chains = {u: load_chain(u) for u in K.UNDS}
    sessions = sorted({d for u in stores for d in stores[u] if d >= K.START})
    try:
        fii = fii_long_share(sessions)
    except Exception as e:                    # noqa: BLE001  missing participant files: features stay missing
        print("participant OI not available:", e)
        fii = {}
    ctx = context(chains, stores, fii)
    with open(K.OUT / "ctx.pkl", "wb") as fh:
        pickle.dump(ctx, fh, protocol=pickle.HIGHEST_PROTOCOL)
    return dict(fii_days=len(fii), **{u: len(v) for u, v in ctx.items()})


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            print(build(u), flush=True)
    elif len(sys.argv) >= 2 and sys.argv[1] == "context":
        print(build_context())
    else:
        print(__doc__)
