"""R11 move anatomy, Part A: big index candles and what every feature we have did before and during them, against
matched ordinary moments (same index, same minute of the day, other days of the same period, no big move near).

    python3 -I research/hunt/r11/anatomy.py design   -> <scratch>/hunt/r11/{events,tests,hits,recall}_design.*, A_design.log
    python3 -I research/hunt/r11/anatomy.py hold     -> the same with the holdout rows (run ONCE; writes HOLD_READ)

Design = days before 2025-10-01 (all thresholds, test choices and lead labels come from here).
Holdout = 2025-10-01 .. 2026-10-06, read once with the same code (the HOLD flag file records that).
Everything a feature uses at minute t is known at the close of minute t (prior-day levels from the prior day).
"""
from __future__ import annotations

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, RES)
sys.path.insert(0, os.path.join(RES, "hunt", "r9"))
sys.path.insert(0, os.path.join(RES, "hunt", "r10"))
sys.path.append("/root/.local/lib/python3.11/site-packages")
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import lib9  # noqa: E402   r9: volume profile, value area, HVN/LVN
import feats as r10f  # noqa: E402   r10: VWAP family, market-profile day structure

OUT = os.path.join(C.SCRATCH, "hunt", "r11")
W = C.W
HOLD0 = pd.Timestamp("2025-10-01")
HOLD1 = pd.Timestamp("2026-10-06")
ROUND = {"BANKNIFTY": 500.0, "NIFTY": 100.0}
LAST_EV = 359            # 15:14 - the closing-auction freeze starts 15:15 (since Aug 2026)
WINS = {"W30": (-30, -16), "W15": (-15, -6), "W5": (-5, -2), "W1": (-1, -1), "EV": (0, None)}
NCTRL = 5
RNG = np.random.default_rng(11)
LOG = None


def say(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    LOG.write(s + "\n")
    LOG.flush()


# ------------------------------------------------------------------------------------------------ helpers
def roll_tod(X, win=40, minp=15):
    """robust z vs the same minute of the previous `win` days: (X - median) / (IQR/1.349)."""
    df = pd.DataFrame(X)
    r = df.rolling(win, min_periods=minp)
    med = r.median().shift(1).values
    q1 = r.quantile(0.25).shift(1).values
    q3 = r.quantile(0.75).shift(1).values
    sc = (q3 - q1) / 1.349
    sc = np.where(sc > 0, sc, np.nan)
    return np.clip((X - med) / sc, -6, 6)          # winsorised: thin baselines (OI steps) must not explode


def sigma_tod(A, win=60, minp=20, sm=2):
    """causal scale of |returns|: mean over previous days of |r| at the same column (+-sm columns)."""
    B = pd.DataFrame(A).T.rolling(2 * sm + 1, center=True, min_periods=1).mean().T
    return B.rolling(win, min_periods=minp).mean().shift(1).values


def cum_true(mask):
    return np.concatenate([np.zeros((mask.shape[0], 1)), np.cumsum(mask, axis=1)], axis=1)


def any_in(cs, i, a, b):
    a = max(a, 0); b = min(b, W - 1)
    if b < a:
        return False
    return cs[i, b + 1] - cs[i, a] > 0


def cluster_t(D, g):
    """mean of D with day-clustered SE; returns mean, t, p (two-sided normal)."""
    from scipy.stats import norm
    ok = np.isfinite(D)
    D, g = D[ok], g[ok]
    n = len(D)
    if n < 10:
        return np.nan, np.nan, np.nan, n
    m = D.mean()
    s = pd.Series(D - m).groupby(g).sum().values
    G = len(s)
    se = np.sqrt((s ** 2).sum() * G / max(G - 1, 1)) / n
    t = m / se if se > 0 else np.nan
    return m, t, 2 * norm.sf(abs(t)), n


def bh(p):
    p = np.asarray(p, float)
    q = np.full(len(p), np.nan)
    ok = np.isfinite(p)
    pp = p[ok]
    o = np.argsort(pp)
    r = pp[o] * len(pp) / (np.arange(len(pp)) + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    qq = np.empty(len(pp)); qq[o] = np.minimum(r, 1)
    q[ok] = qq
    return q


# ------------------------------------------------------------------------------------------------ panel + features
def load(u):
    z = np.load(os.path.join(OUT, f"panel_{u}.npz"))
    P = {k: z[k] for k in z.files}
    for k in P:
        if P[k].dtype == np.float32:
            P[k] = P[k].astype(np.float64)
    P["date"] = pd.to_datetime(P["days"], unit="D")
    ok = P["real"] & (np.isnan(P["h"][:, :345]).mean(1) < 0.2) & (P["date"] <= HOLD1)
    for k in list(P):
        if isinstance(P[k], np.ndarray) and len(P[k]) == len(ok):
            P[k] = P[k][ok]
    P["date"] = pd.DatetimeIndex(P["date"][ok])
    for k in ("optv", "cev2", "pev2", "hwv", "futv"):
        P[k] = np.where(P[k] < 0, np.nan, P[k])       # a few int32-overflowed volume cells in the raw data
    P["u"] = u
    P["nd"] = int(ok.sum())
    P["hold"] = np.asarray(P["date"] >= HOLD0)
    hh = np.where(np.isfinite(P["h"]), P["h"], P["c"])
    ll = np.where(np.isfinite(P["l"]), P["l"], P["c"])
    P["hh"], P["ll"] = hh, ll
    return P


def features(P):
    """{name: (array [nd, W], kind)}; kind 'mag' (bigger = more) or 'sgn' (signed: + = up)."""
    u = P["u"]
    c, o = P["c"], P["o"]
    nd = P["nd"]
    r1 = np.full_like(c, np.nan)
    r1[:, 1:] = np.log(c[:, 1:] / c[:, :-1])
    r1[:, 0] = np.log(c[:, 0] / o[:, 0])
    P["r1"] = r1
    F = {}
    z = roll_tod
    F["absret"] = (z(np.abs(r1)), "mag")                                   # realised volatility (clustering)
    F["ret"] = (z(r1), "sgn")                                              # drift in the move's direction
    rng30 = pd.DataFrame(P["hh"].T).rolling(30, min_periods=10).max().T.values - \
        pd.DataFrame(P["ll"].T).rolling(30, min_periods=10).min().T.values
    F["range30"] = (z(rng30 / c * 1e4), "mag")                             # squeeze (low) / expansion (high)
    lv = np.log1p(P["optv"])
    F["optvol"] = (z(lv), "mag")                                           # option volume ATM+-10
    F["flow"] = (z(P["flow"] / np.maximum(P["optv"], 1)), "sgn")           # signed option flow (tick rule)
    tot2 = P["cev2"] + P["pev2"]
    F["ce_pe_vol"] = (z((P["cev2"] - P["pev2"]) / np.maximum(tot2, 1)), "sgn")
    # OI is published in steps (about every 3 min): use 5-minute sums
    r5 = lambda a: pd.DataFrame(a.T).rolling(5, min_periods=1).sum().T.values  # noqa: E731
    F["oi_build"] = (z(r5(P["pedoi2"] - P["cedoi2"]) / np.maximum(P["oi2"], 1) * 1e3), "sgn")   # put writing - call writing
    F["oi_activity"] = (z(r5(np.abs(P["cedoi2"]) + np.abs(P["pedoi2"])) / np.maximum(P["oi2"], 1) * 1e3), "mag")
    F["prem_spread"] = (z(P["ceret"] - P["peret"]), "sgn")                 # ATM CE minus PE premium move
    div = np.diff(P["iv"], axis=1, prepend=np.nan)
    F["iv_chg"] = (z(div), "sgn")
    F["basis_chg"] = (z(np.diff(P["basis"], axis=1, prepend=np.nan) / c * 1e4), "sgn")   # option-implied forward lead
    F["vix_chg"] = (z(np.diff(P["vix"], axis=1, prepend=np.nan)), "sgn")
    F["gamma_conc"] = (z(P["oi2"] / np.maximum(P["oi10"], 1)), "mag")     # OI near spot share (h18: calmer)
    # delta proxy: minute return sign x option volume (labelled PROXY: no trade sides exist in our history)
    dp = np.sign(r1) * P["optv"]
    F["delta_proxy"] = (z(dp), "sgn")
    # VWAP family (r10): option-volume-weighted session VWAP, z = (c - VWAP)/SD
    vf = r10f.vwap_family(P["hh"], P["ll"], c, np.nan_to_num(P["optv"]))
    vwz = (c - vf["vw"]) / np.where(vf["vw_sd"] > 0, vf["vw_sd"], np.nan)
    vwz[:, :15] = np.nan                     # SD around VWAP is ~0 in the first minutes
    vwz = np.clip(vwz, -6, 6)
    F["vwap_z"] = (vwz, "sgn")
    F["vwap_absz"] = (np.abs(vwz), "mag")
    # heavyweights (Oct 2024 on)
    F["hw_vol"] = (z(np.log1p(P["hwv"])), "mag")
    F["hw_lead"] = (z((P["hwr"] - r1) * 1e4), "sgn")                       # heavyweights ahead of the index print
    # futures (2026-07-29 on only)
    F["fut_vol"] = (roll_tod(np.log1p(P["futv"]), win=40, minp=10), "mag")
    doi = np.diff(P["futoi"], axis=1, prepend=np.nan)
    F["fut_oi_chg"] = (roll_tod(doi, win=40, minp=10), "sgn")
    F["fut_oi_abs"] = (roll_tod(np.abs(doi), win=40, minp=10), "mag")
    fr = np.diff(np.log(P["futc"]), axis=1, prepend=np.nan)
    F["fut_delta"] = (roll_tod(np.sign(fr) * P["futv"], win=40, minp=10), "sgn")
    F["fut_basis_chg"] = (roll_tod(np.diff(P["futc"] - c, axis=1, prepend=np.nan) / c * 1e4, win=40, minp=10), "sgn")
    # round numbers: distance to the nearest multiple as a share of the grid (0 = on it, 0.5 = midway; uniform if no effect)
    g = ROUND[u]
    F["round_dist"] = (np.abs(c / g - np.round(c / g)), "mag")
    # prior-day volume profile (r9 code; weight = option volume): position vs value area, POC, HVN/LVN
    vapos = np.full((nd, W), np.nan); pocd = np.full((nd, W), np.nan)
    lvnd = np.full((nd, W), np.nan); hvnd = np.full((nd, W), np.nan)
    LP = dict(hh=P["hh"], ll=P["ll"], c=c, optv=P["optv"])
    for i in range(1, nd):
        L = lib9.levels(LP, i, "optv")
        if L is None:
            continue
        x = c[i]
        vapos[i] = np.where(x > L["VAH"], 1.0, np.where(x < L["VAL"], -1.0, 0.0))
        pocd[i] = (x - L["POC"]) / x * 1e4
        if len(L["LVN"]):
            lvnd[i] = np.min(np.abs(x[:, None] - L["LVN"][None, :]), axis=1) / x * 1e4
        if len(L["HVN"]):
            hvnd[i] = np.min(np.abs(x[:, None] - L["HVN"][None, :]), axis=1) / x * 1e4
    # distances in units of the prior day's high-low range (so a wild regime does not look like 'far from value')
    prng = np.full(nd, np.nan)
    prng[1:] = ((np.nanmax(P["hh"], 1) - np.nanmin(P["ll"], 1)) / c[:, -1] * 1e4)[:-1]
    P["prng"] = prng
    F["va_pos"] = (vapos, "sgn")
    F["poc_dist"] = (np.abs(pocd) / prng[:, None], "mag")
    F["lvn_dist"] = (lvnd / prng[:, None], "mag")
    F["hvn_dist"] = (hvnd / prng[:, None], "mag")
    # market profile (r10 day structure): IB position, opening type direction, IB width
    ds = r10f.day_structure(o, P["hh"], P["ll"], c)
    ibpos = np.where(c > ds["IBH"][:, None], 1.0, np.where(c < ds["IBL"][:, None], -1.0, 0.0))
    ibpos[:, :60] = np.nan
    F["ib_pos"] = (ibpos, "sgn")
    # first break of the IB this minute (+1 up, -1 down)
    upb = (c > ds["IBH"][:, None]); dnb = (c < ds["IBL"][:, None])
    upb[:, :60] = False; dnb[:, :60] = False
    fu = upb & (np.cumsum(upb, axis=1) == 1); fd = dnb & (np.cumsum(dnb, axis=1) == 1)
    ib1 = fu.astype(float) - fd.astype(float)
    ib1[:, :60] = np.nan
    F["ib_first_break"] = (ib1, "sgn")
    od = np.repeat(ds["odir"][:, None].astype(float), W, axis=1); od[:, :30] = np.nan
    F["open_type_dir"] = (od, "sgn")
    ibr = np.repeat(ds["ib_ratio"][:, None], W, axis=1); ibr[:, :60] = np.nan
    F["ib_width"] = (ibr, "mag")
    # dealer gamma from the prior day's bhavcopy (r9 gex.parquet, convention A) - day level
    gx = pd.read_parquet(os.path.join(C.SCRATCH, "hunt", "r9", "gex.parquet"))
    gx = gx[gx.und == u].sort_values("day")
    gd = pd.to_datetime(gx.day).values
    idx = np.searchsorted(gd, P["date"].values) - 1          # strictly before the session
    gneg = np.full(nd, np.nan); fd_ = np.full(nd, np.nan); flip = np.full(nd, np.nan)
    okg = idx >= 0
    gneg[okg] = (gx.gexA.values[idx[okg]] < 0).astype(float)
    flip[okg] = gx.flip.values[idx[okg]]
    F["gex_negative"] = (np.repeat(gneg[:, None], W, axis=1), "mag")
    fdist = np.abs(c / flip[:, None] - 1) * 1e4 / prng[:, None]
    F["flip_dist"] = (fdist, "mag")
    side = np.sign(c - flip[:, None])
    F["above_flip"] = (side, "sgn")
    P["ds"] = ds
    P["vw"] = vf["vw"]
    P["vw_sd"] = vf["vw_sd"]
    return F


# ------------------------------------------------------------------------------------------------ events
def events(P):
    c, r1 = P["c"], P["r1"]
    nd = P["nd"]
    des = ~P["hold"]
    s1 = sigma_tod(np.abs(r1))
    x1 = r1 / s1
    elig = np.zeros((nd, W), bool); elig[:, 1:LAST_EV + 1] = True
    x1e = np.where(elig, x1, np.nan)
    q95, q99 = np.nanpercentile(np.abs(x1e[des]), [95, 99])
    P["x1"], P["q95"], P["q99"] = x1, q95, q99
    big5 = np.abs(np.nan_to_num(x1e)) >= q95
    P["big5"], P["big1"] = big5, np.abs(np.nan_to_num(x1e)) >= q99
    # 5-minute candles on the 09:15 grid
    nb = W // 5
    C5 = c[:, 4::5][:, :nb]
    r5 = np.full((nd, nb), np.nan)
    r5[:, 1:] = np.log(C5[:, 1:] / C5[:, :-1])
    s5 = sigma_tod(np.abs(r5), sm=1)
    x5 = r5 / s5
    el5 = np.zeros((nd, nb), bool); el5[:, 1:72] = True
    x5e = np.where(el5, x5, np.nan)
    p95, p99 = np.nanpercentile(np.abs(x5e[des]), [95, 99])
    say(f"{P['u']} thresholds (design): 1-min |x| top5% {q95:.2f} top1% {q99:.2f} sigma; 5-min top5% {p95:.2f} top1% {p99:.2f}")
    rows = []
    cs5 = cum_true(big5)
    for kind, X, thr, dur, scale in (("m1_top1", x1e, q99, 1, 1), ("m1_top5", x1e, q95, 1, 1),
                                     ("m5_top1", x5e, p99, 5, 5), ("m5_top5", x5e, p95, 5, 5)):
        for i in range(nd):
            last = -999
            for k in np.nonzero(np.abs(np.nan_to_num(X[i])) >= thr)[0]:
                t = int(k * scale)
                if t - last < 10:
                    last = t
                    continue
                last = t
                ret = r1[i, t] if dur == 1 else r5[i, k]
                rows.append(dict(kind=kind, i=i, t=t, dur=dur, dir=int(np.sign(ret)), ret_bp=ret * 1e4,
                                 x=float(X[i, k]), prior_big=bool(any_in(cs5, i, t - 30, t - 1))))
    # trend legs: >= 0.4% within <= 30 min, no 0.15% pullback (closes)
    for i in range(nd):
        x = c[i]
        for d in (1, -1):
            t = 1
            while t <= LAST_EV:
                s = t
                ext = x[s]
                hit = None
                for e in range(s + 1, min(s + 31, W)):
                    if d * (x[e] - ext) > 0:
                        ext = x[e]
                    if d * (ext - x[e]) / x[s] > 0.0015:
                        break
                    if d * (x[e] - x[s]) / x[s] >= 0.004:
                        hit = e
                        break
                if hit is None:
                    t += 1
                    continue
                seg = x[s:hit + 1]
                s2 = s + int(np.argmin(d * seg))
                # extend to the extreme before the first 0.15% pullback
                ext, e2 = x[hit], hit
                for e in range(hit + 1, W):
                    if d * (x[e] - ext) > 0:
                        ext, e2 = x[e], e
                    if d * (ext - x[e]) / x[s2] > 0.0015:
                        break
                rows.append(dict(kind="leg", i=i, t=s2 + 1, dur=e2 - s2, dir=d, ret_bp=d * abs(x[e2] / x[s2] - 1) * 1e4,
                                 x=np.nan, prior_big=bool(any_in(cs5, i, s2 + 1 - 30, s2))))
                t = e2 + 1
    E = pd.DataFrame(rows)
    E = E[(E.t >= 1) & (E.t <= LAST_EV)].reset_index(drop=True)
    E["und"] = P["u"]
    E["date"] = P["date"][E.i.values]
    E["hold"] = P["hold"][E.i.values]
    E["exp"] = P["exp"][E.i.values]
    E["hhmm"] = [C.hm(C.OPEN_M + t) for t in E.t]
    return E


def controls(P, E):
    """NCTRL control days per event: same minute, same period, no top-5% minute in [t-30, t+dur+10]."""
    cs = cum_true(P["big5"])
    # legs also count as busy
    nd = P["nd"]
    hold = P["hold"]
    pools = {False: np.nonzero(~hold)[0], True: np.nonzero(hold)[0]}
    out = np.full((len(E), NCTRL), -1)
    for n, (i, t, dur, h) in enumerate(zip(E.i.values, E.t.values, E.dur.values, E.hold.values)):
        pool = pools[bool(h)]
        got = []
        for _ in range(60):
            j = int(pool[RNG.integers(len(pool))])
            if j == i or j in got:
                continue
            if any_in(cs, j, t - 30, t + int(dur) + 10):
                continue
            got.append(j)
            if len(got) == NCTRL:
                break
        out[n, :len(got)] = got
    return out


def cums(A):
    ok = np.isfinite(A)
    S = np.concatenate([np.zeros((A.shape[0], 1)), np.cumsum(np.where(ok, A, 0.0), axis=1)], axis=1)
    N = np.concatenate([np.zeros((A.shape[0], 1)), np.cumsum(ok, axis=1)], axis=1)
    return S, N


def controls_same_day(P, E, k=3):
    """up to k minutes of the SAME day, >= 45 min away, no top-5% minute in [t'-30, t'+dur+10] (regime-matched)."""
    cs = cum_true(P["big5"])
    R = np.full((len(E), k), -1); T = np.full((len(E), k), 0)
    for n, (i, t, dur) in enumerate(zip(E.i.values, E.t.values, E.dur.values)):
        cand = [tt for tt in range(1, LAST_EV + 1) if abs(tt - t) >= 45 and (tt >= 31 or t < 31)
                and not any_in(cs, i, tt - 30, tt + int(dur) + 10)]
        if not cand:
            continue
        pick = RNG.choice(cand, size=min(k, len(cand)), replace=False)
        R[n, :len(pick)] = i
        T[n, :len(pick)] = pick
    return R, T


def winvals(A, rows, ts, durs, w, SN=None):
    """mean of A over the window w around each (row, t); NaN when the window leaves the session or has no data."""
    S, N = SN if SN is not None else cums(A)
    a, b = WINS[w]
    rows = np.asarray(rows); ts = np.asarray(ts); durs = np.asarray(durs)
    lo = ts + a
    hi = ts + (durs.astype(int) - 1 if b is None else b)
    hi = np.minimum(hi, W - 1)
    ok = (rows >= 0) & (lo >= 0) & (hi >= lo)
    out = np.full(len(rows), np.nan)
    r, l_, h_ = rows[ok], lo[ok], hi[ok]
    n = N[r, h_ + 1] - N[r, l_]
    s = S[r, h_ + 1] - S[r, l_]
    out[ok] = np.where(n > 0, s / np.maximum(n, 1), np.nan)
    return out


def test_all(P, F, E, CR, CTT, label):
    rows = []
    SNS = {fn: cums(A) for fn, (A, typ) in F.items()}
    for (kind, hold), g in E.groupby(["kind", "hold"]):
        idx = g.index.values
        s = g.dir.values.astype(float)
        for fn, (A, typ) in F.items():
            SN = SNS[fn]
            for w in WINS:
                ev = winvals(A, g.i.values, g.t.values, g.dur.values, w, SN)
                cv = np.column_stack([winvals(A, CR[idx, k], CTT[idx, k], g.dur.values, w, SN) for k in range(CR.shape[1])])
                cm = np.nanmean(cv, axis=1) if np.isfinite(cv).any() else np.full(len(ev), np.nan)
                sgn = s if typ == "sgn" else 1.0
                D = sgn * (ev - cm)
                m, t_, p, n = cluster_t(D, g.date.values)
                ea = sgn * ev
                ca = (sgn[:, None] if typ == "sgn" else 1.0) * cv
                okc = np.isfinite(ca)
                rows.append(dict(und=P["u"], ctl=label, kind=kind, hold=hold, feat=fn, typ=typ, win=w, n=n, eff=m, t=t_, p=p,
                                 ev_mean=np.nanmean(ea) if np.isfinite(ea).any() else np.nan,
                                 ct_mean=np.nanmean(ca) if okc.any() else np.nan,
                                 ev_gt1=np.nanmean(ea[np.isfinite(ea)] > 1) if np.isfinite(ea).any() else np.nan,
                                 ct_gt1=np.mean(ca[okc] > 1) if okc.any() else np.nan))
    return pd.DataFrame(rows)


LAGF = ["absret", "ret", "range30", "optvol", "flow", "ce_pe_vol", "prem_spread", "basis_chg", "iv_chg", "vix_chg",
        "delta_proxy", "oi_activity", "oi_build", "hw_vol", "hw_lead", "gamma_conc", "vwap_z", "fut_vol", "fut_delta", "fut_oi_chg"]


def lag_profile(P, F, E, CT):
    """effect (event minus other-day controls, aligned for signed features) at each single lag -30..+4."""
    rows = []
    for (kind, hold), g in E[E.kind.isin(["m1_top1", "m1_top5", "m5_top5", "leg"])].groupby(["kind", "hold"]):
        idx = g.index.values
        s = g.dir.values.astype(float)
        for fn in LAGF:
            A, typ = F[fn]
            sg = s if typ == "sgn" else 1.0
            for lag in range(-30, 5):
                tt = g.t.values + lag
                ok = (tt >= 0) & (tt < W)
                ev = np.full(len(g), np.nan); ev[ok] = A[g.i.values[ok], tt[ok]]
                cv = np.full((len(g), CT.shape[1]), np.nan)
                for k in range(CT.shape[1]):
                    r = CT[idx, k]
                    o2 = ok & (r >= 0)
                    cv[o2, k] = A[r[o2], tt[o2]]
                with np.errstate(all="ignore"):
                    D = sg * (ev - np.nanmean(cv, axis=1))
                m, t_, p, n = cluster_t(D, g.date.values)
                rows.append(dict(und=P["u"], kind=kind, hold=hold, feat=fn, lag=lag, eff=m, t=t_, p=p, n=n))
    return pd.DataFrame(rows)


# level / flag features: a 'spike' is the flag being on (|value| >= 1), not a z of 2
SPIKE = {"gex_negative": 1.0, "va_pos": 1.0, "ib_pos": 1.0, "ib_first_break": 1.0, "open_type_dir": 1.0, "above_flip": 1.0}


def hits(P, F, signs):
    """spike -> big move within h minutes. Spike: z >= 2 (mag) or sign(effect)*z >= 2 (sgn, predicts that direction)."""
    rows = []
    x1, q95, q99 = P["x1"], P["q95"], P["q99"]
    nd = P["nd"]
    xe = np.full((nd, W), np.nan); xe[:, 1:LAST_EV + 1] = x1[:, 1:LAST_EV + 1]
    xe = np.nan_to_num(xe)
    for lvl, q in (("top5", q95), ("top1", q99)):
        up = xe >= q; dn = xe <= -q
        anyb = up | dn
        cu, cd, ca = cum_true(up), cum_true(dn), cum_true(anyb)
        for h in (5, 15):
            # outcome at (i, t): a big minute in (t, t+h]
            def fwd(cs):
                o = np.zeros((nd, W), bool)
                for t in range(W):
                    a, b = t + 1, min(t + h, W - 1)
                    if b >= a:
                        o[:, t] = cs[:, b + 1] - cs[:, a] > 0
                return o
            Fu, Fd, Fa = fwd(cu), fwd(cd), fwd(ca)
            prior = np.zeros((nd, W), bool)
            for t in range(W):
                a, b = max(t - 15, 0), t
                prior[:, t] = ca[:, b + 1] - ca[:, a] > 0
            valid = np.zeros((nd, W), bool); valid[:, 30:LAST_EV - h + 1] = True
            for split in ((False,) if P.get("hold_mask_off") else (False, True)):
                rs = (P["hold"] == split)
                for fn, (A, typ) in F.items():
                    sg = signs.get(fn, 1.0) if typ == "sgn" else 1.0
                    thr = SPIKE.get(fn, 2.0)
                    Z = sg * A
                    if fn == "round_dist":                       # 'spike' = within 5% of the grid of a round number
                        Z = np.where(np.isfinite(A), 1.0 - A / 0.05, np.nan)
                        thr = 0.0
                    for quiet in (False, True):
                        base_ok = valid & rs[:, None] & (~prior if quiet else True)
                        spike = (np.nan_to_num(Z, nan=-9) >= thr) & base_ok
                        if typ == "sgn":
                            spike_dn = (np.nan_to_num(Z, nan=9) <= -thr) & base_ok
                            n_sp = spike.sum() + spike_dn.sum()
                            hit = (spike & Fu).sum() + (spike_dn & Fd).sum()
                            # base: same direction-specific move rate at the spikes' minutes
                            bu = np.nanmean(np.where(base_ok, Fu, np.nan), axis=0)
                            bd = np.nanmean(np.where(base_ok, Fd, np.nan), axis=0)
                            base = (spike.sum(0) * bu + spike_dn.sum(0) * bd)
                            base = np.nansum(base) / max(n_sp, 1)
                            any_hit = ((spike | spike_dn) & Fa).sum()
                        else:
                            n_sp = spike.sum()
                            hit = (spike & Fa).sum()
                            ba = np.nanmean(np.where(base_ok, Fa, np.nan), axis=0)
                            base = np.nansum(spike.sum(0) * ba) / max(n_sp, 1)
                            any_hit = hit
                        has = np.isfinite(A) & base_ok
                        rows.append(dict(und=P["u"], lvl=lvl, h=h, hold=split, quiet=quiet, feat=fn, typ=typ,
                                         minutes=int(has.sum()), spikes=int(n_sp), spike_rate=n_sp / max(has.sum(), 1),
                                         hit=hit / max(n_sp, 1), base=base, lift=(hit / max(n_sp, 1)) / base if base > 0 else np.nan,
                                         any_dir_hit=any_hit / max(n_sp, 1)))
    return pd.DataFrame(rows)


def recall(P, F, E, signs):
    """share of (clean) big moves preceded by a spike in the 5 / 15 minutes before."""
    rows = []
    for (kind, hold), g in E[E.kind.isin(["m1_top1", "m1_top5", "leg"]) & (E.t >= 31)].groupby(["kind", "hold"]):
        for fn, (A, typ) in F.items():
            sg = signs.get(fn, 1.0) if typ == "sgn" else 1.0
            for h in (5, 15):
                vals = []
                for i, t, d in zip(g.i.values, g.t.values, g.dir.values):
                    seg = A[i, t - h:t]
                    if not np.isfinite(seg).any():
                        continue
                    z = (sg * d if typ == "sgn" else 1.0) * seg
                    vals.append(np.nanmax(z) >= 2)
                rows.append(dict(und=P["u"], kind=kind, hold=hold, feat=fn, h=h, n=len(vals),
                                 recall=np.mean(vals) if vals else np.nan))
    return pd.DataFrame(rows)


def main(mode):
    global LOG
    LOG = open(os.path.join(OUT, f"A_{mode}.log"), "w")
    if mode == "hold":
        assert not os.path.exists(os.path.join(OUT, "HOLD_READ")), "the holdout was already read"
    allE, allT, allH, allR, allL = [], [], [], [], []
    for u in ("BANKNIFTY", "NIFTY"):
        t0 = time.time()
        P = load(u)
        F = features(P)
        E = events(P)
        if mode == "design":
            E = E[~E.hold].reset_index(drop=True)
            P["hold_mask_off"] = True
        CT = controls(P, E)
        CR2, CT2 = controls_same_day(P, E)
        say(u, "days", P["nd"], "design", int((~P["hold"]).sum()), "holdout", int(P["hold"].sum()),
            f"{time.time() - t0:.0f}s")
        say(E.groupby(["kind", "hold"]).agg(n=("t", "size"), up=("dir", lambda s: (s > 0).mean()),
                                            prior_big=("prior_big", "mean"), med_bp=("ret_bp", lambda s: s.abs().median())).to_string())
        T = pd.concat([test_all(P, F, E, CT, np.repeat(E.t.values[:, None], CT.shape[1], axis=1), "other_day"),
                       test_all(P, F, E, CR2, CT2, "same_day")], ignore_index=True)
        allT.append(T)
        allL.append(lag_profile(P, F, E, CT))
        # signs for the hit test: the design effect sign of the pooled 1-min top-5% events in the 5 minutes before
        sel = T[(T.ctl == "other_day") & (T.kind == "m1_top5") & (~T.hold) & (T.win == "W5") & (T.typ == "sgn")]
        signs = {r.feat: (1.0 if r.eff >= 0 else -1.0) for r in sel.itertuples()}
        H = hits(P, F, signs)
        allH.append(H)
        allR.append(recall(P, F, E, signs))
        # per-event feature snapshot for the stories (design-agnostic, raw + z)
        snap = {}
        for fn, (A, typ) in F.items():
            SN = cums(A)
            for w in WINS:
                snap[f"{fn}|{w}"] = winvals(A, E.i.values, E.t.values, E.dur.values, w, SN)
        S = pd.DataFrame(snap)
        # raw context for stories
        c = P["c"]
        S["px"] = c[E.i.values, np.maximum(E.t.values - 1, 0)]
        S["vix_lvl"] = P["vix"][E.i.values, np.maximum(E.t.values - 1, 0)]
        S["vwap"] = P["vw"][E.i.values, np.maximum(E.t.values - 1, 0)]
        S["ibh"] = P["ds"]["IBH"][E.i.values]; S["ibl"] = P["ds"]["IBL"][E.i.values]
        S["otype"] = P["ds"]["otype"][E.i.values]; S["odir"] = P["ds"]["odir"][E.i.values]
        # what happened next: 15 / 30 min after the event candle ends, in the move's direction (bp)
        endt = np.minimum(E.t.values + E.dur.values - 1, W - 1)
        for hz in (15, 30):
            j = np.minimum(endt + hz, W - 1)
            S[f"after{hz}_bp"] = E.dir.values * (c[E.i.values, j] / c[E.i.values, endt] - 1) * 1e4
        allE.append(pd.concat([E.reset_index(drop=True), S], axis=1))
        np.save(os.path.join(OUT, f"ctrl_{u}.npy"), CT)
        say(u, "done", f"{time.time() - t0:.0f}s")
    E = pd.concat(allE, ignore_index=True)
    T = pd.concat(allT, ignore_index=True)
    H = pd.concat(allH, ignore_index=True)
    R = pd.concat(allR, ignore_index=True)
    # BH over every design test that has data
    L = pd.concat(allL, ignore_index=True)
    L.to_csv(os.path.join(OUT, f"lags_{mode}.csv"), index=False)
    des = (~T.hold) & T.p.notna()
    T.loc[des, "q"] = bh(T.loc[des, "p"].values)
    E.to_parquet(os.path.join(OUT, f"events_{mode}.parquet"))
    T.to_csv(os.path.join(OUT, f"tests_{mode}.csv"), index=False)
    H.to_csv(os.path.join(OUT, f"hits_{mode}.csv"), index=False)
    R.to_csv(os.path.join(OUT, f"recall_{mode}.csv"), index=False)
    say("design tests", int(des.sum()), "BH q<=0.05:", int((T.q <= 0.05).sum()))
    if mode == "hold":
        open(os.path.join(OUT, "HOLD_READ"), "w").write(time.strftime("%Y-%m-%d %H:%M:%S") + " holdout read once with anatomy.py\n")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
