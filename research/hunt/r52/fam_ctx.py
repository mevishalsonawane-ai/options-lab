"""R52 family 'ctx': rejected index-option rules whose signals read the index, sometimes with option-chain context
(IV, OI, option volume) around it. 15 Oct 2026. See FAMILY_BRIEF.md and PREREG.md.

Every signal is computed on the UNDERLYING (index minutes); chain data (IV, OI, PCR, option volume) is only a filter
around the index signal. The option bought is the strike rule's (ATM / ITM1, nearest expiry). No rule here reads an
option's own price chart.

Keys covered (original setting in one line each):
  R24-2  hunt/r24 pattern p2: 2+ levels within 0.2%, ATM IV high, own-side OI build-up high -> buy ATM, first minute
         the pattern holds (one side only), 1 per index per day; Liquidity-style exits -15% / 20-min (+5%) / 15:10
         (15:05 on expiry days); NIFTY + BANKNIFTY, expiry days traded. R24's frozen tercile cuts (step3_design.json).
  R24-3  p3: ATM IV high and the opening gap went against the option (as R24-2 otherwise).
  R24-4  p4: 2+ levels within 0.2%, expiry day, buy the CE.
  R24-5  p5: opening gap against the option, 1-2 days to expiry.
  R24-6  p6: other-side OI build-up high, realised vol (30 min) high.
  R24-7  p7: some room to the next level ahead, ATM IV high.
  R24-8  p8: far from the level behind, IV rank (252 sessions) high.
  H29-3  hunt/h29 sig_t2: at 10:00 / 11:30 / 13:00, ATM IV / HAR-RV forecast in the lowest 10% of the prior 60 days
         (same check time) -> buy ITM1 in the direction of the last 30 minutes; E2 exits -15% / +30% / 20-min (+5%) /
         15:10; <= 3 a day, expiry days skipped; 5 indices.
  OBUY_SEARCH-2  obuy_search rule #3: FINNIFTY CE ITM1, first 5-minute slot 12:00-14:30 with close > 0.3% above the
         session TWAP, 5-min EMA 9>21>50, and expiry day; SL 35% / TG 50% / 120 min (15:10 cap).
  OBUY_SEARCH-4  rule #7: NIFTY CE ATM, first slot 09:20-10:15 with 5-min RSI14 in 40-60, previous VIX close > 18,
         and PCR (ATM+-2 OI) > 1.2; SL 35% / TG 30% / 30 min.
  SCALP17-2  BANKNIFTY PE ITM1 on 5-minute bar closes: 3+ green 5-min bars (aligned streak <= -3) and bar range /
         5-day average in Q4 [0.963, 1.29); +10 / -25 premium points / 15 min; <= 5 a day, one at a time.
  SCALP17-3  NIFTY PE ITM1 on 1-minute decisions: 15-min index fall >= 8.36 bps (aligned r15 Q5) and distance above
         the day low in [9.73, 20.7) bps (dfav Q2); +20 / -15 points / 60 min; <= 5 a day.
  R9-4   hunt/r9 D2 "absorption": a 5-minute bar with ATM+-10 option volume >= 2x its 20-day time-of-day median and
         range <= 0.5x its median, after a 0.15% 30-minute move -> buy ATM in the reversal direction, out at the
         event study's horizon (60 min, exit_at); NIFTY + BANKNIFTY. Option wrapper added for R52 (the original was
         an index bp event study).
  H33-1  hunt/h33 price-shock FOLLOW: FINNIFTY, first minute 09:25-14:30 with a 1-min move >= 5 x the prior-5-session
         1-min vol and ATM+-2 option volume (5 min) >= 2x usual, 30-min cool-off; buy ITM1 3 minutes later, out after
         30 minutes (T30); expiry days traded. H33-2 (NIFTY, 3-min move, 1 minute later, +30/-15/30 min) is in the
         grid.
  H45-1  hunt/h45 headline: Liquidity 15+5 entries (BANKNIFTY 15+5-min, FINNIFTY 30+5-min, MIDCPNIFTY 15+5-min),
         ITM1, exits replaced by a +5% / -5% premium bracket, out by 15:10, the signal's own index exits off.
  H35-1  hunt/h35: the app's daily pivots (R2..S2 of the previous session), break-and-hold (two 5-min closes beyond),
         09:30-14:30, ITM1, 60-minute time exit, <= 3 a day, expiry days skipped, 5 indices. The other level families
         (H35-2..H35-8) and modes are in the grid.

LEFT (not tested here):
  SCALP17-1  random entry (no signal): a baseline, not a rule.
  R16-2   a fitted model (logistic / LightGBM, monthly walk-forward refits) on R16's own minute grids
          (hunt/r16/data.py: scratch/data/*.parquet + Yahoo / bhavcopy downloads), which are gone; not a fixed rule.

Data notes (stated, not tuned):
  R24  features come from the brain's own code (cube.build_day + the feature registry) run on the lake
       ($IRAALGO_LAKE, default /root/lake) day by day in memory; cached to R52_OUT/fam_ctx/r24_<U>.parquet. The lake
       has no futures minutes, so the futures volume-profile levels (prev_poc/vah/val) are absent, as they were for most
       of R24's own lake period. IV rank needs 252 prior sessions (data from Jan 2024): NaN until about Jan 2025,
       so R24-8 cannot fire before then.
  H29  HAR coefficients are refit on all EARLIER days only: 2025 on 2024, 2026 on 2024-25, and Nov-Dec 2024 on the
       2024 days before 20 Nov 2024 (h29's own first-two-years in-sample fit would look ahead here).
  SCALP17  the quintile cuts are the write-up's printed values (3 significant figures; the screen cache is gone);
       the original's option-liquidity filters (fill bar traded, etc.) are left to the engine's fill rules.
  OBUY_SEARCH  the original's Rs 10 minimum premium and "strike traded in the last 6 minutes" filters are left to the
       engine's fill rules; expiry days come from obuy's expiry flag.
  H45  the liqx cache of the validated BANKNIFTY / FINNIFTY port is gone; the entries come from hunt/h4/comps.py
       liq_ext_signals (the same port of liquidity_exits_3060.py, BOOKS_EXT BN 15+5, FIN 30+5, MIDCP 15+5).
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys
import warnings
from datetime import date

import numpy as np
import pandas as pd

import r52cfg as K                    # sets sys.path (repo root, research/, r52/)
from obuy import config as C
from obuy.engine import LADDER  # noqa: F401  (kept for grid readers)

RESEARCH = K.ROOT / "research"
HUNT = RESEARCH / "hunt"
SQ10, SQ15 = 15 * 60 + 10, 15 * 60 + 15
UNDS5 = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY")
NB = ("NIFTY", "BANKNIFTY")


def _mod(name, path):
    """Import an original study's file under a unique module name (several studies have a build.py)."""
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, str(path))
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m


def _frame(rows, cols=("und", "day", "sig_min", "side")):
    if not rows:
        return pd.DataFrame(columns=list(cols))
    out = pd.DataFrame(rows)
    return out.sort_values(["und", "day", "sig_min", "side"], kind="stable").reset_index(drop=True)


def _ffill_rows(a):
    """forward-fill NaN along axis 1 (leading NaN stay NaN)."""
    return pd.DataFrame(np.asarray(a, dtype=np.float64).T).ffill().values.T


# ================================================================================================ R24 (hunt/r24)
R24_DIR = HUNT / "r24"
R24_FEATS = ["lvl_above_dist_pct", "lvl_below_dist_pct", "lvl_near_count", "atm_iv", "iv_rank_252", "oi_build_ce_pct",
             "oi_build_pe_pct", "gap_pct", "rv_30m", "dte_days", "minute_of_day"]
_R24 = {}


def _r24_imports():
    if str(R24_DIR) not in sys.path:
        sys.path.insert(0, str(R24_DIR))
    import items as I            # hunt/r24/items.py
    import study as ST           # hunt/r24/study.py (fires)
    return I, ST


def r24_design():
    """R24's frozen final rules and tercile cuts (hunt/r24/results/step3_design.json)."""
    if "design" not in _R24:
        d = json.loads((R24_DIR / "results" / "step3_design.json").read_text())
        pats = {r["id"].split("-")[1]: tuple((n, int(c)) for n, c in r["items"]) for r in d["final"]}
        fam = {r["id"].split("-")[1]: r["family"] for r in d["final"]}
        cuts = {tuple(k.split("|")): tuple(v) for k, v in d["cuts"].items()}
        _R24["design"] = (pats, fam, cuts)
    return _R24["design"]


def _lake_adapter(lake):
    """brain LakeAdapter that needs only the lake files (the repo's CATALOG.json is not checked in)."""
    from research.brain.adapters.lake import LakeAdapter

    class A(LakeAdapter):
        def available(self, und):
            return bool(self._files(rf"index_1m_{und}_\d{{4}}\.parquet"))
    return A(lake)


def r24_features(und, lake=None, cache=True) -> pd.DataFrame:
    """The brain features R24's patterns read, one row per cube minute: ts + R24_FEATS (the other R24 items, unused by
    p2..p8, are NaN). Built exactly as the brain does (cube.build_day on the lake, features.registry), one calendar
    month at a time with 260 sessions of minute history (iv_rank / levels) and that month's chain."""
    if und in _R24:
        return _R24[und]
    from research.brain import cube
    from research.brain.features import load_all, registry
    lake = os.environ.get("IRAALGO_LAKE", "/root/lake") if lake is None else lake
    out_dir = K.OUT / "fam_ctx"
    ad = _lake_adapter(lake)
    sess = ad.sessions(und)
    p = out_dir / f"r24_{und}_{len(sess)}_{sess[-1] if sess else 'none'}.parquet"
    if cache and p.exists():
        F = pd.read_parquet(p)
        _R24[und] = F
        return F
    load_all()
    ss = set(sess)
    Ms, Cs = {}, {}
    keep_c = ["ts", "offset", "side", "strike", "ltp", "oi", "oi_chg_day", "iv", "flag"]
    for dd in ad.load(und, sess[0], sess[-1]):
        m, ch, _ = cube.build_day(dd, ss)
        Ms[dd.day] = m
        Cs[dd.day] = ch[keep_c] if len(ch) else ch
    days = sorted(Ms)
    parts = []
    months = sorted({(d.year, d.month) for d in days})
    names_a = [n for n in R24_FEATS if n != "iv_rank_252"]
    for (y, mo) in months:
        md = [d for d in days if (d.year, d.month) == (y, mo)]
        i0, i1 = days.index(md[0]), days.index(md[-1]) + 1
        chs = [Cs[d] for d in md if len(Cs[d])]
        Cc = pd.concat(chs, ignore_index=True) if chs else None
        Ma = pd.concat([Ms[d] for d in days[max(0, i0 - 115):i1]], ignore_index=True)     # levels: 110 sessions
        Fa = registry.compute(Ma, Cc, und, names=names_a)
        Mb = pd.concat([Ms[d] for d in days[max(0, i0 - 260):i1]], ignore_index=True)     # IV rank: 252 sessions
        Fb = registry.compute(Mb, None, und, names=["iv_rank_252"])
        keep = set(md)
        Fa = Fa[Fa.ts.dt.date.isin(keep)].reset_index(drop=True)
        Fb = Fb[Fb.ts.dt.date.isin(keep)].reset_index(drop=True)
        assert (Fa.ts.values == Fb.ts.values).all()
        Fa["iv_rank_252"] = Fb.iv_rank_252.values
        parts.append(Fa)
    F = pd.concat(parts, ignore_index=True)
    for c in R24_FEATS:
        F[c] = F[c].astype(np.float32)
    if cache:
        out_dir.mkdir(parents=True, exist_ok=True)
        F.to_parquet(p, index=False)
    _R24[und] = F
    return F


class _MiniData:
    """The two members of hunt/r24/study.Data that study.fires reads."""

    def __init__(self, mf):
        self.mf = mf.reset_index(drop=True)
        self.dord = np.array([d.toordinal() for d in self.mf.d.values], dtype=np.int64)

    def mask_days(self, days):
        return np.isin(self.dord, np.array([d.toordinal() for d in days], dtype=np.int64))


def r24_fires_from(feats: dict, pattern: tuple, cuts: dict) -> pd.DataFrame:
    """feats {und: brain feature rows (ts + features)} -> R24 entries (und, d, entry_row, side, offset) with R24's own
    code: r24data.frame_from (decision minute -> entry minute + 1, both sides), items.raw_values / items.codes with
    the frozen cuts, study.fires (first minute the pattern holds for exactly one side, per index and session)."""
    I, ST = _r24_imports()
    import r24data as DA
    frames = []
    for u, F in feats.items():
        F = F.copy()
        if not len(F):
            continue
        for c in I.FEATURES + ["minute_of_day"]:
            F[c] = F[c].astype(float) if c in F else np.nan
        frames.append(DA.frame_from(F, u))
    frames = [f for f in frames if len(f)]
    if not frames:
        return pd.DataFrame(columns=["und", "d", "entry_row", "side", "offset", "dte"])
    mf = pd.concat(frames, ignore_index=True)
    D = _MiniData(mf)
    vals = I.raw_values(D.mf, D.mf.side.values)
    allc = {}
    for it in I.ITEMS:
        if it.kind == "t":
            for u in ("NIFTY", "BANKNIFTY"):
                allc[(it.name, u)] = cuts.get((it.name, u), (np.nan, np.nan))
    codes = I.codes(vals, D.mf.side.values, D.mf.und.values, D.mf.entry_row.values, allc)
    f = ST.fires(D, codes, pattern, set(D.mf.d))
    if len(f):
        key = D.mf.set_index(["und", "d", "entry_row", "side"]).dte_days
        f["dte"] = key.reindex(pd.MultiIndex.from_frame(f[["und", "d", "entry_row", "side"]])).values
    else:
        f["dte"] = []
    return f


def r24_pattern(mk, pattern="p3", unds=NB, lake=None):
    """R24 rule p<k>: buy (ATM unless the pattern names a moneyness) at the first entry minute whose decision-minute
    items match; sig_min = the decision minute (entry row - 1); expiry days square off at 15:05 (exit_at 905)."""
    pats, _, cuts = r24_design()
    pat = pats[pattern]
    getf = getattr(mk, "r24_features", None)
    feats = {u: (getf(u) if getf else r24_features(u, lake)) for u in unds}
    f = r24_fires_from(feats, pat, cuts)
    rows = []
    for r in f.itertuples():
        sm = int(C.OPEN_M + r.entry_row - 1)
        exp = bool(np.isfinite(r.dte) and r.dte < 0.5)
        rows.append(dict(und=r.und, day=r.d, sig_min=sm, side=int(r.side), book=f"r24{pattern}_{r.und}",
                         exit_at=float(15 * 60 + 5) if exp else np.nan, tag=f"off={int(r.offset)}"))
    return _frame(rows)


# ================================================================================================ H29-3 (hunt/h29)
H29_CHECKS = (599, 689, 779)          # bars closing at 10:00, 11:30, 13:00 (h29 rules.CHECKS)
_H29 = {}


def _h29_panel(mk, und):
    """Per (day, minute): S (index close, ffill), ATM straddle IV on the forward (h29 build.py's own chain_core /
    straddle_iv, trading-time T), exp, real. A port of h29 build.build without the files it writes."""
    key = (id(mk), und)
    if key in _H29:
        return _H29[key]
    B = _mod("h29_build", HUNT / "h29" / "build.py")
    ix = mk.index(und)
    op = mk.options(und)
    step = C.STEP[und]
    M = ix.mat()
    days = ix.days
    exp_days = np.array([d for d in days if ix.d[d]["exp"]])
    pos = {d: i for i, d in enumerate(days)}
    mins = C.OPEN_M + np.arange(C.W)
    rows = []
    for i, d in enumerate(days):
        x = ix.d[d]
        k = np.searchsorted(exp_days, d)
        if k >= len(exp_days):
            continue
        ed = exp_days[k]
        if (ed - d).days > 40 or ed not in pos:
            continue
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        S = pd.Series(M["c"][i]).ffill().values
        if np.isnan(S).all():
            continue
        tmin = (C.LAST_M + 1 - mins) + 375.0 * (pos[ed] - i)
        T = np.maximum(tmin, 1.0) / B.YEAR_MIN
        with np.errstate(all="ignore"):
            _, _, _, F, K0, strad, _ = B.chain_core(ch, S, step)
            iv = B.straddle_iv(strad, F, K0, T)
        rows.append((d, S, iv, bool(x["exp"]), bool(x["real"])))
    mk.release(und)
    _H29[key] = rows
    return rows


def _h29_har(rvs: dict) -> pd.DataFrame:
    """h29 features.har_all (pooled log-HAR(1, 5, 22) of the daily 5-min RV), refit on EARLIER days only: days before
    20 Nov 2024 on themselves (warm-up, never traded), 20 Nov - 31 Dec 2024 on the days before 20 Nov 2024, then each
    calendar year on all earlier days. Returns und, day, har (annualised)."""
    parts = []
    for und, r in rvs.items():
        if not len(r):
            continue
        r = r.sort_values("day").reset_index(drop=True)
        s = pd.Series(r.rv.values)
        parts.append(pd.DataFrame(dict(und=und, day=r.day, y=np.log(np.maximum(r.rv.values, 1e-10)),
                                       x1=np.log(np.maximum(s.shift(1), 1e-10)),
                                       x5=np.log(np.maximum(s.shift(1).rolling(5).mean(), 1e-10)),
                                       x22=np.log(np.maximum(s.shift(1).rolling(22).mean(), 1e-10)))))
    if not parts:
        return pd.DataFrame(columns=["und", "day", "har"])
    H = pd.concat(parts, ignore_index=True).dropna().reset_index(drop=True)
    seg = np.array([(-1 if d < K.FIRST_TRADE else (2024 if d.year == 2024 else d.year)) for d in H.day])
    pred = np.full(len(H), np.nan)
    for g in sorted(set(seg)):
        te = seg == g
        if g == -1:
            tr = te
        elif g == 2024:
            tr = seg == -1
        else:
            tr = np.array([d < date(g, 1, 1) for d in H.day])
        if tr.sum() < 30:
            continue
        X = np.c_[np.ones(tr.sum()), H.loc[tr, ["x1", "x5", "x22"]].values]
        b, *_ = np.linalg.lstsq(X, H.y.values[tr], rcond=None)
        s2 = np.var(H.y.values[tr] - X @ b)
        Xt = np.c_[np.ones(te.sum()), H.loc[te, ["x1", "x5", "x22"]].values]
        pred[te] = Xt @ b + 0.5 * s2
    H["har"] = np.sqrt(252 * np.exp(pred))
    return H[["und", "day", "har"]]


def h29_cheap(mk, q=0.1, dirn="mom30", unds=UNDS5):
    """h29 rules.sig_t2 on R52's data: cheap1 = straddle IV / HAR forecast; at each check time, its rank among the
    prior 60 sessions' values at that time (>= 40 needed, strictly below); rank <= q -> buy in the direction of the
    last 30 minutes ('mom30') or of the move since the open ('day'). Expiry days and non-real index days skipped."""
    pans = {u: _h29_panel(mk, u) for u in unds}
    rvs = {}
    for u, rows in pans.items():
        rv = []
        for d, S, iv, ex, real in rows:
            if len(S) != C.W or np.isnan(S).any():
                continue
            x = np.log(S[::5])
            rv.append((d, float(np.sum(np.diff(x) ** 2))))
        rvs[u] = pd.DataFrame(rv, columns=["day", "rv"])
    H = _h29_har(rvs)
    har = {(r.und, r.day): r.har for r in H.itertuples()}
    out = []
    for u, rows in pans.items():
        for m in H29_CHECKS:
            col = m - C.OPEN_M
            g = []
            for d, S, iv, ex, real in rows:
                h = har.get((u, d), np.nan)
                c1 = iv[col] / h if np.isfinite(h) else np.nan
                s30 = S[col - 30] if col >= 30 else np.nan
                g.append((d, c1, S[col], s30, S[0], ex, real))
            v = np.array([x[1] for x in g], dtype=float)
            for i, (d, c1, s, s30, op_, ex, real) in enumerate(g):
                past = v[max(0, i - 60):i]
                past = past[np.isfinite(past)]
                if len(past) < 40 or not np.isfinite(c1) or ex or not real:
                    continue
                if (past < c1).mean() > q:
                    continue
                mv = (s - s30) if dirn == "mom30" else (s - op_)
                sd = np.sign(mv)
                if sd != 0 and np.isfinite(sd):
                    out.append(dict(und=u, day=d, sig_min=int(m), side=int(sd), book=f"h29c_{u}"))
    return _frame(out)


# ================================================================================================ OBUY_SEARCH (obuy_search/build.py)
OBS_SLOTS = np.arange(560, 871, 5)          # decision (fill) minutes 09:20 .. 14:30; signal bar = slot - 1
OBS_WINDOWS = {"0920-1015": (0, 11), "1015-1200": (11, 32), "1200-1430": (32, 63), "0920-1430": (0, 63)}
OBS_STEP = {"NIFTY": 50, "BANKNIFTY": 100, "FINNIFTY": 50}


def _obs_ema(x, n):
    """obuy_search build.ema: seeded with the first value, continuous."""
    a = 2.0 / (n + 1)
    out = np.empty_like(x)
    v = x[0]
    for i in range(len(x)):
        v = v + a * (x[i] - v)
        out[i] = v
    return out


def _obs_rsi(c, n=14):
    """obuy_search build.rsi (u, d start at 0, continuous)."""
    d = np.diff(c, prepend=c[0])
    up, dn = np.clip(d, 0, None), np.clip(-d, 0, None)
    au, ad = np.empty_like(c), np.empty_like(c)
    u = dd = 0.0
    for i in range(len(c)):
        u = u + (up[i] - u) / n
        dd = dd + (dn[i] - dd) / n
        au[i], ad[i] = u, dd
    return 100 - 100 / (1 + au / np.maximum(ad, 1e-9))


def _obs_index(M_row):
    """obuy_search build.ffill_ohlc on the index (missing minutes -> o=h=l=c=previous close; leading minutes take the
    first bar); None for a partial day (no close in the first 5 minutes)."""
    X = np.stack([M_row[k] for k in ("o", "h", "l", "c")], axis=1).astype(float)
    miss = np.isnan(X[:, 3])
    if miss.all() or np.isnan(X[:5, 3]).all():
        return None
    idx = np.where(~miss, np.arange(len(X)), 0)
    np.maximum.accumulate(idx, out=idx)
    first = int(np.argmax(~miss))
    c = X[idx, 3]
    for j in range(4):
        X[miss, j] = c[miss]
    X[:first] = X[first]
    return X


def obs_rule(mk, und="FINNIFTY", side=1, atoms=("vwap_far_with", "ema5_with", "expiry_day"), window="1200-1430"):
    """obuy_search's first-slot rule: the first 5-minute decision slot in the window where all atoms hold for the side
    (one per day). Atoms ported from obuy_search/build.py process(): vwap_far_with, ema5_with, expiry_day, rsi_mid,
    vix_high, pcr_with. sig_min = slot - 1 (the last closed 1-minute bar; the fill is the slot's open)."""
    ok_atoms = {"vwap_far_with", "ema5_with", "expiry_day", "rsi_mid", "vix_high", "pcr_with"}
    bad = set(atoms) - ok_atoms
    if bad:
        raise ValueError(f"atoms not ported: {sorted(bad)}")
    ix = mk.index(und)
    M = ix.mat()
    recs = []
    for i, d in enumerate(ix.days):
        X = _obs_index({k: M[k][i] for k in ("o", "h", "l", "c")})
        if X is not None:
            recs.append((d, X, bool(ix.d[d]["exp"])))
    if not recs:
        return _frame([])
    NM = C.W
    nb5 = NM // 5
    c5 = np.array([r[1][b:b + 5][-1, 3] for r in recs for b in range(0, NM, 5)])
    e5a, e5b, e5c = _obs_ema(c5, 9), _obs_ema(c5, 21), _obs_ema(c5, 50)
    r5 = _obs_rsi(c5)
    vx = mk.vix.daily if "vix_high" in atoms else pd.DataFrame(dict(close=[]))
    vx = vx[vx.close > 0]
    vdays = list(vx.index)
    a, b = OBS_WINDOWS[window]
    s = 1 if side > 0 else -1
    step = OBS_STEP.get(und, C.STEP[und])
    need_pcr = "pcr_with" in atoms
    op = mk.options(und) if need_pcr else None
    rows = []
    for di, (d, X, exp) in enumerate(recs):
        if di < 1:
            continue
        k = np.searchsorted(vdays, d)
        vprev = float(vx.close.iloc[k - 1]) if k > 0 else np.nan
        tp = (X[:, 1] + X[:, 2] + X[:, 3]) / 3
        twap = np.cumsum(tp) / np.arange(1, NM + 1)
        oiC = oiP = Ks = None
        if need_pcr:
            ch = op.chain(d, "near")
            if ch is None:
                continue
            Ks = ch.K
            oiC, oiP = _ffill_rows(ch.oi["C"]), _ffill_rows(ch.oi["P"])
        for si in range(a, b):
            t = int(OBS_SLOTS[si])
            j = t - 1 - C.OPEN_M
            cl = X[j, 3]
            b5 = di * nb5 + (t - C.OPEN_M) // 5 - 1
            ok = True
            for at in atoms:
                if at == "expiry_day":
                    ok = exp
                elif at == "vwap_far_with":
                    ok = (cl - twap[j]) / twap[j] * 100 * s > 0.3
                elif at == "ema5_with":
                    ok = b5 >= 50 and ((e5a[b5] > e5b[b5] > e5c[b5]) if s > 0 else (e5a[b5] < e5b[b5] < e5c[b5]))
                elif at == "rsi_mid":
                    ok = b5 >= 50 and 40 <= r5[b5] <= 60
                elif at == "vix_high":
                    ok = vprev == vprev and vprev > 18
                elif at == "pcr_with":
                    ok = _obs_pcr_with(Ks, oiC, oiP, cl, j, step, s)
                if not ok:
                    break
            if ok:
                rows.append(dict(und=und, day=d, sig_min=t - 1, side=s, book=f"obs_{und}"))
                break
    if need_pcr:
        mk.release(und)
    return _frame(rows)


def _obs_pcr_with(Ks, oiC, oiP, cl, j, step, s):
    """PCR over ATM+-2 (ATM from the close of minute j) of the OI carried to minute j; >= 3 strikes with both legs:
    pcr > 1.2 is 'with' a call, < 1/1.2 'with' a put (obuy_search build.py)."""
    atm = int(np.floor(cl / step + 0.5)) * step
    co = po = 0.0
    n = 0
    for kk in range(atm - 2 * step, atm + 3 * step, step):
        i = int(np.searchsorted(Ks, kk))
        if i >= len(Ks) or Ks[i] != kk:
            continue
        c, p = oiC[i, j], oiP[i, j]
        if np.isnan(c) or np.isnan(p):
            continue
        n += 1
        co += c
        po += p
    if n < 3 or co <= 0 or po <= 0:
        return False
    pcr = po / co
    return pcr > 1.2 if s > 0 else pcr < 1 / 1.2


# ================================================================================================ SCALP17 (obuy/scalp17.py)
def _s17_days(mk, und):
    """Per usable day (scalp17 build: from the 6th day, <= 20% missing closes in 09:16-15:00, real index): the dense
    o/h/l/c, the forward-filled close and the previous 5 days' mean 5-minute range."""
    ix = mk.index(und)
    M = ix.mat()
    h5 = np.full((len(ix.days), 75), np.nan)
    l5 = h5.copy()
    with np.errstate(all="ignore"), warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        for k in range(75):
            h5[:, k] = np.nanmax(M["h"][:, 5 * k:5 * k + 5], axis=1)
            l5[:, k] = np.nanmin(M["l"][:, 5 * k:5 * k + 5], axis=1)
        rng5 = np.nanmean(h5 - l5, axis=1)
    for di, d in enumerate(ix.days):
        if di < 5:
            continue
        o, h, l, c = (M[k][di] for k in ("o", "h", "l", "c"))
        if np.isnan(c[1:346]).mean() > 0.2 or not ix.d[d]["real"]:
            continue
        with np.errstate(all="ignore"):
            r5p = np.nanmean(rng5[di - 5:di])
        yield d, o, h, l, c, pd.Series(c).ffill().values, h5[di], l5[di], r5p


def scalp17_r15dfav(mk, und="NIFTY", side=-1, r15_min=8.36, dfav_lo=9.73, dfav_hi=20.7):
    """SCALP17 best 1-minute rule: decisions on minutes 09:16-15:00; r15 (15-minute index move, bps, aligned with the
    side) in Q5 (>= r15_min) and dfav (bps from the day's extreme in the trade's favour: the low for a PE) in Q2."""
    s = np.arange(1, 346)
    rows = []
    for d, o, h, l, c, cff, _, _, _ in _s17_days(mk, und):
        cs = cff[s]
        with np.errstate(all="ignore"):
            r15 = (cs - cff[np.maximum(s - 15, 0)]) / cs * 1e4 * side
            hi = np.fmax.accumulate(np.where(np.isnan(h), -np.inf, h))[s]
            lo = np.fmin.accumulate(np.where(np.isnan(l), np.inf, l))[s]
            dfav = ((hi - cs) / cs if side > 0 else (cs - lo) / cs) * 1e4
            ok = (r15 >= r15_min) & (dfav >= dfav_lo) & (dfav < dfav_hi)
        for j in np.flatnonzero(ok):
            rows.append(dict(und=und, day=d, sig_min=int(C.OPEN_M + s[j]), side=int(side), book=f"s17m_{und}"))
    return _frame(rows)


def scalp17_streak(mk, und="BANKNIFTY", side=-1, stk_max=-3, rng_lo=0.963, rng_hi=1.29):
    """SCALP17 best 5-minute rule: at each 5-minute bar close (bar k >= 1), the bar-colour streak aligned with the side
    <= stk_max (for a PE: 3+ green bars, a fade) and the bar's range / previous 5 days' mean 5-minute range in Q4."""
    rows = []
    for d, o, h, l, c, cff, hh5, ll5, r5p in _s17_days(mk, und):
        o5 = o[0::5]
        c5 = np.array([cff[5 * k + 4] for k in range(75)])
        col = np.sign(c5 - o5)
        stk = np.zeros(75)
        for j in range(75):
            if col[j] == 0 or np.isnan(col[j]):
                stk[j] = 0
            elif j and np.sign(stk[j - 1]) == col[j]:
                stk[j] = stk[j - 1] + col[j]
            else:
                stk[j] = col[j]
        for k in range(1, 69):                       # bar k ends at column 5k+4 <= 345 (15:00)
            with np.errstate(all="ignore"):
                rg = (hh5[k] - ll5[k]) / r5p
            if not np.isfinite(rg) or not (rng_lo <= rg < rng_hi):
                continue
            if stk[k] * side <= stk_max:
                rows.append(dict(und=und, day=d, sig_min=int(C.OPEN_M + 5 * k + 4), side=int(side),
                                 book=f"s17f_{und}"))
    return _frame(rows)


# ================================================================================================ R9-4 (hunt/r9 flowproxy)
R9_MOVE = 0.0015
_OPTV = {}


def _r9_optv(mk, und):
    """hunt/r9 build.py: per day and minute, ATM+-10 (ATM from the forward/back-filled index close) call + put volume of
    the nearest series. {day: (W,) array}."""
    key = (id(mk), und)
    if key in _OPTV:
        return _OPTV[key]
    ix = mk.index(und)
    M = ix.mat()
    op = mk.options(und)
    step = C.STEP[und]
    out = {}
    for i, d in enumerate(ix.days):
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        sp = pd.Series(M["c"][i]).ffill().bfill().values
        if not np.isfinite(sp).any():
            sp = pd.Series(ch.spot).ffill().bfill().values
        if not np.isfinite(sp).any():
            continue
        atm = np.round(sp / step) * step
        dist = np.abs(ch.K.astype(float)[:, None] - atm[None, :]) / step
        vC, vP = np.nan_to_num(ch.v["C"]), np.nan_to_num(ch.v["P"])
        out[d] = ((vC + vP) * (dist <= 10.5)).sum(0).astype(np.float32)
    mk.release(und)
    _OPTV[key] = out
    return out


def r9_absorption(mk, horizon=60, unds=NB):
    """hunt/r9 flowproxy.events_abs, ported (its module needs lib9's panels): 5-minute bars k = 7 .. 62; a bar whose
    option volume is >= 2x the median of the same bar over the previous 20 days and whose range is <= 0.5x that bar's
    median range, after a >= 0.15% move over the 30 minutes before the bar -> event at the bar's last minute, direction
    = reversal of the move; events >= 15 minutes apart per day and direction. Option wrapper (R52): buy in the reversal
    direction, out at the open after `horizon` minutes (exit_at = sig_min + horizon + 1, the index study's c[e + h])."""
    rows = []
    last_k = (14 * 60 + 45 - C.OPEN_M) // 5
    for u in unds:
        ix = mk.index(u)
        M = ix.mat()
        nd = len(ix.days)
        c = pd.DataFrame(M["c"]).T.ffill().T.values
        hh = np.where(np.isfinite(M["h"]), M["h"], c)
        ll = np.where(np.isfinite(M["l"]), M["l"], c)
        okd = np.array([bool(ix.d[d]["real"]) for d in ix.days]) & (np.isnan(M["c"][:, :345]).mean(1) < 0.2)
        ov = _r9_optv(mk, u)
        vol = np.full((nd, C.W), np.nan)
        for i, d in enumerate(ix.days):
            if d in ov:
                vol[i] = ov[d]
        nb = C.W // 5
        with np.errstate(all="ignore"):
            V = np.full((nd, nb), np.nan)
            RG = np.full((nd, nb), np.nan)
            for i in range(nd):
                v = vol[i][:nb * 5].reshape(nb, 5)
                V[i] = np.where(np.isfinite(v).any(1), np.nansum(v, 1), np.nan)
                RG[i] = np.nanmax(hh[i][:nb * 5].reshape(nb, 5), 1) - np.nanmin(ll[i][:nb * 5].reshape(nb, 5), 1)
        mv = pd.DataFrame(V).rolling(20, min_periods=10).median().shift(1).values
        mr = pd.DataFrame(RG).rolling(20, min_periods=10).median().shift(1).values
        for di in range(nd):
            if not (okd[di] and np.isfinite(vol[di]).any()):
                continue
            cc = c[di]
            last = {}
            for k in range(7, last_k):
                s0, e = 5 * k, 5 * k + 4
                if not (np.isfinite(cc[s0 - 1]) and np.isfinite(cc[s0 - 31]) and np.isfinite(cc[e])):
                    continue
                m = cc[s0 - 1] / cc[s0 - 31] - 1
                if abs(m) < R9_MOVE or not (np.isfinite(mv[di, k]) and np.isfinite(V[di, k]) and mv[di, k] > 0):
                    continue
                if not (V[di, k] / mv[di, k] >= 2 and RG[di, k] <= 0.5 * mr[di, k]):
                    continue
                rev = int(-np.sign(m))
                if rev in last and e - last[rev] < 15:
                    continue
                last[rev] = e
                sm = int(C.OPEN_M + e)
                rows.append(dict(und=u, day=ix.days[di], sig_min=sm, side=rev, book=f"r9abs_{u}",
                                 exit_at=float(sm + horizon + 1)))
    return _frame(rows)


# ================================================================================================ H33-1 (hunt/h33)
_H33 = {}


def _h33_feats(mk, und, need_vb=True):
    """hunt/h33 feats.build without its file: c (dense closes), sigma from the previous 5 sessions' 1-minute returns
    (09:20-15:25), z1 / z3, and vb = ATM+-2 call + put volume of the 5 minutes ending at t (h26 build.day_feats vc5 +
    vp5) / the median over the previous 5 sessions of each day's median V5 (columns 15..364)."""
    key = (id(mk), und, need_vb)
    if key in _H33:
        return _H33[key]
    from obuy.data import dnum
    ix = mk.index(und)
    M = ix.mat()
    c = M["c"].astype(np.float64)
    n = len(ix.days)
    r = np.full_like(c, np.nan)
    r[:, 1:] = c[:, 1:] / c[:, :-1] - 1
    with np.errstate(all="ignore"):
        dstd = np.nanstd(r[:, 5:371], axis=1)
    sig = np.full(n, np.nan)
    for i in range(5, n):
        s = dstd[i - 5:i]
        if np.isfinite(s).sum() >= 3:
            sig[i] = np.sqrt(np.nanmean(s ** 2))
    z = {}
    for k in (1, 3):
        dd = np.full_like(c, np.nan)
        dd[:, k:] = c[:, k:] / c[:, :-k] - 1
        z[k] = dd / (sig[:, None] * np.sqrt(k))
    vb = np.full_like(c, np.nan)
    if need_vb:
        H26 = _mod("h26_build", HUNT / "h26" / "build.py")
        op = mk.options(und)
        step = C.STEP[und]
        V5 = np.full_like(c, np.nan)
        for i, d in enumerate(ix.days):
            ch = op.chain(d, "near")
            if ch is None or len(ch.K) < 5:
                continue
            sp = M["c"][i].copy()
            if np.isnan(sp).all():
                sp = ch.spot.copy()
            sp = _ffill_rows(sp[None, :])[0].copy()
            if np.isnan(sp[0]):
                first = np.flatnonzero(~np.isnan(sp))
                if not len(first):
                    continue
                sp[:first[0]] = sp[first[0]]
            with np.errstate(all="ignore"):
                X = H26.day_feats(ch, sp, step)
            V5[i] = X[H26.FEATS.index("vc5")].astype(np.float64) + X[H26.FEATS.index("vp5")].astype(np.float64)
        mk.release(und)
        with np.errstate(all="ignore"):
            med = np.nanmedian(V5[:, 15:365], axis=1)
        base = np.full(n, np.nan)
        for i in range(5, n):
            m = med[i - 5:i]
            if np.isfinite(m).sum() >= 3:
                base[i] = np.nanmedian(m)
        vb = V5 / base[:, None]
    f = dict(days=np.array([dnum(d) for d in ix.days]), c=c, sig=sig, z1=z[1], z3=z[3], vb=vb)
    _H33[key] = f
    return f


def h33_shock(mk, und="FINNIFTY", W=1, Z=5, VB=1, N=3, orient="follow"):
    """hunt/h33 arm P (test.signals): shocks from h33 build.price_shocks (first minute 09:25-14:30 with |z_W| >= Z, the
    next one 30 minutes later), kept when vb >= 2 if VB; sig_min = shock minute + N; side = shock direction (follow)
    or against it (fade)."""
    Bm = _mod("h33_build", HUNT / "h33" / "build.py")
    f = _h33_feats(mk, und, need_vb=bool(VB))
    sh = Bm.price_shocks(und, f)
    if not len(sh):
        return _frame([])
    e = sh[(sh.W == W) & (sh.Z == Z)]
    if VB:
        e = e[e.vb >= 2]
    s = 1 if orient == "follow" else -1
    rows = [dict(und=und, day=r.day, sig_min=int(r.tmin + N), side=int(r.dir * s), book=f"h33_{und}")
            for r in e.itertuples()]
    return _frame(rows)


# ================================================================================================ H45-1 (hunt/h45)
def h45_liq(mk, unds=("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")):
    """hunt/h45 entry set 'liq': Liquidity 15+5 entries (hunt/h4 comps.liq_ext_signals, the port of
    liquidity_exits_3060.py), with h45's entry_sets clean-up (no explicit strike: the strike rule picks it; the index
    stop / target / failed-break columns stay but the bracket exits ignore them, sig_levels=False)."""
    sys.path.insert(0, str(HUNT / "h4")) if str(HUNT / "h4") not in sys.path else None
    comps = _mod("h4_comps", HUNT / "h4" / "comps.py")
    s = comps.liq_ext_signals(mk, unds=tuple(unds))
    if not len(s):
        return _frame([])
    s = s.drop(columns=[c for c in ("strike", "exit_day", "exit_min") if c in s.columns])
    return s.sort_values(["und", "day", "sig_min", "side"], kind="stable").reset_index(drop=True)


# ================================================================================================ H35-1 (hunt/h35)
H35_ISTOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "NIFTY": 15.0, "SENSEX": 50.0, "MIDCPNIFTY": 8.0}   # h35 run.py
_H35 = {}


def _h35_events(mk, und):
    key = (id(mk), und)
    if key not in _H35:
        L = _mod("h35_levels", HUNT / "h35" / "levels.py")
        rows = []
        L.run_und(mk, und, rows)
        _H35[key] = pd.DataFrame(rows, columns=["und", "day", "sig_min", "side", "fam", "mode", "lev"])
    return _H35[key]


def h35_levels(mk, fam="pivD", mode="brk", unds=UNDS5):
    """hunt/h35 levels.run_und events (the app's levels ported from the Kotlin; break-and-hold / retest / bounce on
    closed 5-minute bars, signals 09:30-14:30) as h35 run.signals: one family and mode, the first 6 signals per index
    and day, book per family/mode/index, index stop = level -/+ the index stop unit."""
    out = []
    for u in unds:
        E = _h35_events(mk, u)
        E = E[(E.fam == fam) & (E["mode"] == mode)].sort_values(["und", "day", "sig_min"], kind="stable")
        E = E[E.groupby(["und", "day"]).cumcount() < 6]
        out += [dict(und=r.und, day=r.day, sig_min=int(r.sig_min), side=int(r.side), book=f"{fam}_{mode}_{r.und}",
                     idx_stop=float(r.lev - r.side * H35_ISTOP[r.und])) for r in E.itertuples()]
    return _frame(out)


# ================================================================================================ SPECS
LIQ_X = dict(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=SQ10)           # R24 liq family / Liquidity arm
ORB_X = dict(stop_pts=40.0, tgt_pts=40.0, ladder=LADDER, ladder_ref_pts=40.0, sq_off=SQ10)   # R24 'orb' family
SWEEP_X = dict(stop_pts=40.0, tgt_pts=80.0, ladder=LADDER, ladder_ref_pts=80.0, sq_off=SQ10)  # R24 'sweep' family
R24_WHY = "R24's frozen final rule {p} (hunt/r24/results/step3_design.json), exit family 'liq' as chosen on R24 design"
R24_SRC = {
    "p2": "index + option-chain context (ATM IV, own-side OI build-up)",
    "p3": "index + option-chain context (ATM IV)",
    "p4": "index",
    "p5": "index",
    "p6": "index + option-chain context (other-side OI build-up)",
    "p7": "index + option-chain context (ATM IV)",
    "p8": "index + option-chain context (IV rank)",
}
R24_TITLE = {
    "p2": "R24 p2: 2+ levels near + ATM IV high + own-side OI building",
    "p3": "R24 p3: ATM IV high + opening gap against the option",
    "p4": "R24 p4: 2+ levels near on expiry day, buy CE",
    "p5": "R24 p5: opening gap against the option, 1-2 DTE",
    "p6": "R24 p6: other-side OI building + high realised vol",
    "p7": "R24 p7: some room to the next level ahead + ATM IV high",
    "p8": "R24 p8: far from the level behind + IV rank high",
}

SPECS = []
for _p in ("p2", "p3", "p4", "p5", "p6", "p7", "p8"):
    SPECS.append(dict(
        key=f"R24-{_p[1]}", title=R24_TITLE[_p], source=R24_SRC[_p], why=R24_WHY.format(p=_p),
        orig=dict(fn=r24_pattern, params=dict(pattern=_p), money=0, series="near", unds=NB, exits=dict(LIQ_X),
                  expiry="allow", pos=dict(one_at_a_time=True, max_per_day=1), window=(9 * 60 + 19, 14 * 60 + 44)),
        # R24's own alternatives: the two other exit families every pattern was scored with (orb 40/40, sweep 40/80)
        grid=[dict(exits=dict(ORB_X)), dict(exits=dict(SWEEP_X))],
    ))

H29_E = [dict(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=SQ10),                       # E1
         dict(stop_pct=0.15, tgt_pct=0.30, time_stop=20, time_gain=0.05, sq_off=SQ10),         # E2
         dict(stop_pct=0.15, ladder=LADDER, ladder_ref_pct=0.15, sq_off=SQ10),                 # E3
         dict(stop_pct=0.30, time_stop=30, time_gain=None, sq_off=SQ10)]                       # E4
SPECS.append(dict(
    key="H29-3", title="h29: buy only when IV is cheap (IV/HAR rank lowest 10%), side from the last 30 min",
    source="index + option-chain context (ATM straddle IV)",
    why="the write-up's best test-2a variant (HUNT_H29.md: q 10%, last-30-min move, E2); hunt/h29/rules.py sig_t2",
    orig=dict(fn=h29_cheap, params=dict(q=0.1, dirn="mom30"), money=1, series="near", unds=UNDS5, exits=H29_E[1],
              expiry="skip", pos=dict(one_at_a_time=True, max_per_day=3), window=(9 * 60 + 30, 14 * 60 + 30)),
    # h29's own test-2a grid: q 0.1 / 0.2 x direction mom30 / since open x exits E1-E4 (16; the original first)
    grid=[dict(params=dict(q=q, dirn=dr), exits=H29_E[x]) for q in (0.1, 0.2) for dr in ("mom30", "day")
          for x in range(4) if not (q == 0.1 and dr == "mom30" and x == 1)],
))

OBS_X = dict(stop_pct=0.35, tgt_pct=0.50, time_stop=120, time_gain=None, sq_off=SQ10)
SPECS.append(dict(
    key="OBUY_SEARCH-2", title="obuy_search #3: FINNIFTY expiry-day afternoon CE (TWAP + 5-min EMA with)",
    source="index", why="OBUY_SEARCH.md rule #3 as listed (in-sample t 3.81, 41 trades)",
    orig=dict(fn=obs_rule, params=dict(und="FINNIFTY", side=1, atoms=("vwap_far_with", "ema5_with", "expiry_day"),
                                       window="1200-1430"),
              money=1, series="near", unds=("FINNIFTY",), exits=OBS_X, expiry="allow",
              pos=dict(one_at_a_time=True, max_per_day=1), window=(12 * 60 - 1, 14 * 60 + 29)),
    # the study's own menu around it: strike ATM, stop 15/25%, target 30/100%/none, time 30/60 min/15:10
    grid=[dict(money=0)]
         + [dict(exits={**OBS_X, "stop_pct": s}) for s in (0.15, 0.25)]
         + [dict(exits={**OBS_X, "tgt_pct": g}) for g in (0.30, 1.00, None)]
         + [dict(exits={**OBS_X, "time_stop": t}) for t in (30, 60, None)],
))
OBS4_X = dict(stop_pct=0.35, tgt_pct=0.30, time_stop=30, time_gain=None, sq_off=SQ10)
SPECS.append(dict(
    key="OBUY_SEARCH-4", title="obuy_search #7: NIFTY morning CE (RSI mid + VIX > 18 + PCR with)",
    source="index + option-chain context (PCR of OI) + VIX", why="OBUY_SEARCH.md rule #7 as listed (the only holdout winner)",
    orig=dict(fn=obs_rule, params=dict(und="NIFTY", side=1, atoms=("rsi_mid", "vix_high", "pcr_with"),
                                       window="0920-1015"),
              money=0, series="near", unds=("NIFTY",), exits=OBS4_X, expiry="skip",
              pos=dict(one_at_a_time=True, max_per_day=1), window=(9 * 60 + 19, 10 * 60 + 9)),
    grid=[dict(money=1)]
         + [dict(exits={**OBS4_X, "stop_pct": s}) for s in (0.15, 0.25)]
         + [dict(exits={**OBS4_X, "tgt_pct": g}) for g in (0.50, 1.00, None)]
         + [dict(exits={**OBS4_X, "time_stop": t}) for t in (60, 120, None)],
))


def _pts(T, S, L):
    return dict(tgt_pts=float(T), stop_pts=float(S), time_stop=int(L), time_gain=None, sq_off=SQ10)


SPECS.append(dict(
    key="SCALP17-2", title="SCALP17: BANKNIFTY PE fade after 3 green 5-min bars (range Q4)",
    source="index", why="SCALP17.md best 5-minute rule (+10 / -25 / 15 min)",
    orig=dict(fn=scalp17_streak, params=dict(und="BANKNIFTY", side=-1), money=1, series="near", unds=("BANKNIFTY",),
              exits=_pts(10, 25, 15), expiry="allow", pos=dict(one_at_a_time=True, max_per_day=5),
              window=(9 * 60 + 15, 15 * 60)),
    # SCALP17's own T/S/L shortlist for this cell (SCALP17.md table), incl. Boss's range +15 / -30 / 15
    grid=[dict(exits=_pts(*x)) for x in ((10, 30, 15), (10, 30, 30), (10, 30, 60), (10, 25, 30), (10, 25, 60),
                                          (15, 25, 15), (15, 30, 15), (10, 20, 15), (17, 25, 15), (15, 20, 15))],
))
SPECS.append(dict(
    key="SCALP17-3", title="SCALP17: NIFTY PE on a 15-min fall, not far off the day low",
    source="index", why="SCALP17.md best 1-minute rule (+20 / -15 / 60 min)",
    orig=dict(fn=scalp17_r15dfav, params=dict(und="NIFTY", side=-1), money=1, series="near", unds=("NIFTY",),
              exits=_pts(20, 15, 60), expiry="allow", pos=dict(one_at_a_time=True, max_per_day=5),
              window=(9 * 60 + 16, 15 * 60)),
    grid=[dict(exits=_pts(*x)) for x in ((25, 15, 15), (20, 15, 15), (25, 15, 60), (20, 15, 30), (15, 15, 60),
                                          (20, 20, 60), (17, 15, 60), (25, 20, 60))],
))
SPECS.append(dict(
    key="R9-4", title="R9 absorption proxy: heavy option volume, narrow 5-min bar after a move -> reversal",
    source="index + option-chain context (option volume)",
    why="HUNT_R9_VOLUME_GAMMA.md D2 (NIFTY best at 60 min, +4.7 bp); option wrapper ATM, exit at the horizon",
    orig=dict(fn=r9_absorption, params=dict(horizon=60), money=0, series="near", unds=NB,
              exits=dict(sq_off=SQ15), expiry="allow", pos=dict(one_at_a_time=True), window=(9 * 60 + 54, 14 * 60 + 29)),
    # the event study's own horizons and the two strikes the catalogue names (ATM / 1-ITM)
    grid=[dict(params=dict(horizon=15)), dict(params=dict(horizon=30)), dict(money=1),
          dict(money=1, params=dict(horizon=30)), dict(unds=("NIFTY",))],
))
H33_X = {"T30": dict(time_stop=30, time_gain=None, sq_off=SQ10), "T15": dict(time_stop=15, time_gain=None, sq_off=SQ10),
         "P30": dict(tgt_pts=30.0, stop_pts=15.0, time_stop=30, time_gain=None, sq_off=SQ10),
         "LIQ": dict(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=SQ10)}
SPECS.append(dict(
    key="H33-1", title="h33 price-shock follow (FINNIFTY 1-min z>=5 + option-volume burst, +3 min, T30)",
    source="index + option-chain context (option volume)",
    why="HUNT_H33.md best price-shock variant (FIN W1 Z5 VB N3 follow T30); H33-2 (NIFTY W3 Z5 N1 P30) in the grid",
    orig=dict(fn=h33_shock, params=dict(und="FINNIFTY", W=1, Z=5, VB=1, N=3, orient="follow"), money=1, series="near",
              unds=("FINNIFTY",), exits=H33_X["T30"], expiry="allow", pos=dict(one_at_a_time=True),
              window=(9 * 60 + 25, 14 * 60 + 35)),
    # h33's own 3,072-variant grid around it (one change at a time) + the H33-2 NIFTY variant
    grid=[dict(params=dict(N=1)), dict(params=dict(N=5)), dict(params=dict(VB=0)), dict(params=dict(Z=8)),
          dict(params=dict(W=3)), dict(params=dict(orient="fade")), dict(exits=H33_X["T15"]), dict(exits=H33_X["P30"]),
          dict(exits=H33_X["LIQ"]),
          dict(params=dict(und="NIFTY", W=3, Z=5, VB=1, N=1), unds=("NIFTY",), exits=H33_X["P30"]),
          dict(params=dict(und="NIFTY", W=3, Z=5, VB=0, N=1), unds=("NIFTY",), exits=H33_X["P30"])],
))


def _brk(t, s, ts):
    return dict(stop_pct=s, tgt_pct=t, time_stop=ts, time_gain=None, sq_off=SQ10, sig_levels=False)


SPECS.append(dict(
    key="H45-1", title="h45: Liquidity 15+5 entries with a symmetric +5% / -5% premium bracket",
    source="index", why="h45 PREREG headline variant (+5% / -5%, 15:10, 1-ITM) on its Liquidity entry set",
    orig=dict(fn=h45_liq, params={}, money=1, series="near", unds=("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"),
              exits=_brk(0.05, 0.05, None), expiry="skip", pos=dict(one_at_a_time=True),
              window=(9 * 60 + 19, 13 * 60 + 59)),
    # h45's own menu at the app strike: 6 brackets x 4 time stops (23 besides the original)
    grid=[dict(exits=_brk(t, s, ts)) for t, s in ((0.03, 0.03), (0.05, 0.05), (0.07, 0.07), (0.10, 0.10),
                                                   (0.05, 0.03), (0.10, 0.05)) for ts in (None, 15, 30, 60)
          if not (t == 0.05 and s == 0.05 and ts is None)],
))

H35_X = [dict(stop_pts=float(s), tgt_pts=float(t), sig_levels=False) for t in (15, 20, 25, 30) for s in (10, 15, 20)]
H35_X += [dict(LIQ_X)]
H35_X += [dict(stop_pts=40.0, tgt_pts=40.0, ladder=LADDER, ladder_ref_pts=40.0, sig_levels=False)]
H35_X += [dict(time_stop=m, time_gain=None, sig_levels=False) for m in (15, 30, 60)]          # h35 run.EXITS x0..x16
SPECS.append(dict(
    key="H35-1", title="h35: the app's S/R levels - daily pivots break-and-hold",
    source="index",
    why="HUNT_H35.md pivD break (7,525 trades, best gross exit = time 60); the other families / modes are in the grid",
    orig=dict(fn=h35_levels, params=dict(fam="pivD", mode="brk"), money=1, series="near", unds=UNDS5,
              exits=H35_X[16], expiry="skip", pos=dict(one_at_a_time=True, max_per_day=3),
              window=(9 * 60 + 30, 14 * 60 + 30)),
    grid=[dict(params=dict(mode="rt")), dict(params=dict(mode="bo"))]
         + [dict(exits=H35_X[x]) for x in (0, 4, 8, 11, 12, 13, 14, 15)]
         + [dict(params=dict(fam=f)) for f in ("pivW", "brain", "struct", "maxoi", "maxpain", "round", "vix")]
         + [dict(params=dict(fam="round", mode="rt"), exits=H35_X[14])],
))
