"""R209 pure parts (PREREG.md): the input table built from in-memory day arrays (so the look-ahead guard can be tested),
the labels, the walk-forward split, the day-block AUC bootstrap, top-20% accuracy and the gates. No file I/O here.
Plain-English notes, 20 Nov 2026.

Day arrays: idx[und][day] = (4, 375) O H L C; vix[day] = (4, 375); opt[und][day] = dict of (375,) arrays
(civ, piv, skew_c, skew_p, coi, poi, cvol, pvol, strad, spot) - minute r of the nearest expiry's chain, NaN where unknown.
"""
from __future__ import annotations

import math
import warnings

import numpy as np
import pandas as pd

import r209cfg as K

N = K.NROWS
E_ROWS = np.array(K.DECISION)


# ================================================================================================ small helpers
def ffill(a: np.ndarray) -> np.ndarray:
    """Forward-fill NaN along a 1-D array (leading NaN stay NaN)."""
    a = np.asarray(a, float)
    ok = np.isfinite(a)
    if not ok.any():
        return np.full(len(a), np.nan)
    idx = np.where(ok, np.arange(len(a)), 0)
    np.maximum.accumulate(idx, out=idx)
    out = a[idx]
    out[: int(np.argmax(ok))] = np.nan
    return out


def ratio(a, b):
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.where((b != 0) & np.isfinite(a) & np.isfinite(b), a / b - 1.0, np.nan)


def div(a, b):
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.where((b != 0) & np.isfinite(a) & np.isfinite(b), a / b, np.nan)


def ema(x: np.ndarray, n: int) -> np.ndarray:
    out = np.full(len(x), np.nan)
    a = 2.0 / (n + 1.0)
    e = math.nan
    for i, v in enumerate(np.asarray(x, float)):
        if v == v:
            e = v if e != e else e + a * (v - e)
        out[i] = e
    return out


def supertrend_dir(h, l, c, n=10, mult=3.0) -> np.ndarray:
    """+1 / -1 / 0 (unknown); a missing bar carries the last direction (as R202)."""
    h, l, c = (np.asarray(v, float) for v in (h, l, c))
    out = np.zeros(len(c))
    atr = math.nan
    seed = []
    pc = ub = lb = math.nan
    d = 0
    for i in range(len(c)):
        if not (c[i] == c[i] and h[i] == h[i] and l[i] == l[i]):
            out[i] = d
            continue
        tr = h[i] - l[i] if pc != pc else max(h[i] - l[i], abs(h[i] - pc), abs(l[i] - pc))
        if atr != atr:
            seed.append(tr)
            if len(seed) >= n:
                atr = float(np.mean(seed))
        else:
            atr = (atr * (n - 1) + tr) / n
        if atr == atr:
            mid = (h[i] + l[i]) / 2.0
            bu, bl = mid + mult * atr, mid - mult * atr
            if ub != ub:
                ub, lb = bu, bl
                d = 1 if c[i] >= mid else -1
            else:
                ub = bu if (bu < ub or pc > ub) else ub
                lb = bl if (bl > lb or pc < lb) else lb
                if d == 1 and c[i] < lb:
                    d = -1
                elif d == -1 and c[i] > ub:
                    d = 1
        pc = c[i]
        out[i] = d
    return out


# ================================================================================================ one index's own inputs
def own_table(days: dict, und: str) -> pd.DataFrame:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        return _own_table(days, und)


def _own_table(days: dict, und: str) -> pd.DataFrame:
    """P inputs and the labels for every decision point of one index. Every input reads minutes < e (and 5-minute bars
    that end before e, across sessions); the labels read minutes up to e + 14 / e + 29."""
    ds = sorted(days)
    nd = len(ds)
    A = np.full((nd, 4, N), np.nan)
    for i, d in enumerate(ds):
        A[i] = days[d]
    O, H, L, C = A[:, 0], A[:, 1], A[:, 2], A[:, 3]
    cf = ffill(C.reshape(-1))                                   # last known close, across sessions
    lr = np.diff(np.log(cf), prepend=np.nan)
    lr[~np.isfinite(lr)] = 0.0
    cs, cs2 = np.cumsum(lr), np.cumsum(lr * lr)
    with np.errstate(all="ignore"):
        hs = np.fmax.accumulate(np.where(np.isfinite(H), H, -np.inf), axis=1)
        ls = np.fmin.accumulate(np.where(np.isfinite(L), L, np.inf), axis=1)
        tp = (H + L + C) / 3.0
        okt = np.isfinite(tp)
        tw = np.cumsum(np.where(okt, tp, 0.0), axis=1) / np.maximum(np.cumsum(okt, axis=1), 1)
        tw[np.cumsum(okt, axis=1) == 0] = np.nan
    first_open = np.array([o[np.isfinite(o)][0] if np.isfinite(o).any() else np.nan for o in O])
    day_last = np.array([cf[(i + 1) * N - 1] for i in range(nd)])
    prev_close = np.concatenate([[np.nan], day_last[:-1]])
    prev2 = np.concatenate([[np.nan, np.nan], day_last[:-2]])
    # 5-minute bars, continuous
    B = A.reshape(nd, 4, 75, 5)
    with np.errstate(all="ignore"):
        bh = np.nanmax(B[:, 1], axis=2).reshape(-1)
        bl = np.nanmin(B[:, 2], axis=2).reshape(-1)
    bc = np.array([cf[min((k // 75) * N + (k % 75) * 5 + 4, len(cf) - 1)] if np.isfinite(B[k // 75, 3, k % 75]).any() else np.nan
                   for k in range(nd * 75)])
    st = supertrend_dir(bh, bl, bc)
    e20, e50 = ema(bc, 20), ema(bc, 50)
    s = pd.Series(bh).rolling(20, min_periods=20).max().to_numpy()
    t = pd.Series(bl).rolling(20, min_periods=20).min().to_numpy()
    # last-15-minute range at each decision minute, and its 20-session average (previous sessions only)
    R15 = np.full((nd, len(E_ROWS)), np.nan)
    with np.errstate(all="ignore"):
        for j, e in enumerate(E_ROWS):
            lo = max(0, e - 15)
            R15[:, j] = np.nanmax(H[:, lo:e], axis=1) - np.nanmin(L[:, lo:e], axis=1)
    R15m = pd.DataFrame(R15).shift(1).rolling(20, min_periods=10).mean().to_numpy()
    rows = []
    for i, d in enumerate(ds):
        g = i * N + E_ROWS                                         # global row of minute e
        gm = g - 1
        c1 = cf[gm]

        def back(k):
            j = gm - k
            return np.where(j >= 0, cf[np.maximum(j, 0)], np.nan)
        def rvol(k):
            j0 = np.maximum(gm - k, 0)
            n_ = gm - j0
            m1 = (cs[gm] - cs[j0]) / np.maximum(n_, 1)
            m2 = (cs2[gm] - cs2[j0]) / np.maximum(n_, 1)
            return np.sqrt(np.maximum(m2 - m1 * m1, 0.0))
        hsd, lsd = hs[i, E_ROWS - 1], ls[i, E_ROWS - 1]
        hsd = np.where(np.isfinite(hsd), hsd, np.nan)
        lsd = np.where(np.isfinite(lsd), lsd, np.nan)
        kb = i * 75 + E_ROWS // 5 - 1                              # last complete 5-minute bar
        with np.errstate(all="ignore"):
            pos = (c1 - lsd) / (hsd - lsd)
            don = (c1 - t[kb]) / (s[kb] - t[kb])
        # labels: a print must exist after minute e - 1 and up to the horizon (R206)
        cday = C[i]
        up15 = np.full(len(E_ROWS), np.nan)
        up30 = np.full(len(E_ROWS), np.nan)
        m15 = np.full(len(E_ROWS), np.nan)
        for j, e in enumerate(E_ROWS):
            for hz, arr in ((15, up15), (30, up30)):
                end = e + hz - 1
                if end > N - 1:
                    continue
                seg = cday[e:end + 1]
                ok = np.flatnonzero(np.isfinite(seg))
                if len(ok) and c1[j] == c1[j]:
                    mv = seg[ok[-1]] / c1[j] - 1.0
                    arr[j] = 1.0 if mv > 0 else 0.0
                    if hz == 15:
                        m15[j] = mv
        rows.append(pd.DataFrame(dict(
            und=und, day=d, e=E_ROWS,
            r1=ratio(c1, back(1)), r5=ratio(c1, back(5)), r15=ratio(c1, back(15)), r30=ratio(c1, back(30)), r60=ratio(c1, back(60)),
            ropen=ratio(c1, first_open[i]), gap=ratio(np.full(len(E_ROWS), first_open[i]), prev_close[i]),
            prev_ret=ratio(np.full(len(E_ROWS), prev_close[i]), prev2[i]),
            pos=pos, dvwap=ratio(c1, tw[i, E_ROWS - 1]), rv30=rvol(30), rng15=div(R15[i], R15m[i]), don=don,
            st=st[kb], emad=div(e20[kb] - e50[kb], c1), tod=E_ROWS.astype(float), dow=float(d.weekday()),
            up15=up15, up30=up30, m15=m15)))
    return pd.concat(rows, ignore_index=True)


def vix_table(days: dict) -> pd.DataFrame:
    ds = sorted(days)
    C = np.stack([days[d][3] for d in ds])
    O = np.stack([days[d][0] for d in ds])
    cf = ffill(C.reshape(-1))
    rows = []
    for i, d in enumerate(ds):
        gm = i * N + E_ROWS - 1
        v = cf[gm]
        fo = O[i][np.isfinite(O[i])]
        fo = fo[0] if len(fo) else np.nan

        def back(k):
            j = gm - k
            return np.where(j >= 0, cf[np.maximum(j, 0)], np.nan)
        rows.append(pd.DataFrame(dict(day=d, e=E_ROWS, vix=v, vix5=ratio(v, back(5)), vix15=ratio(v, back(15)),
                                      vix60=ratio(v, back(60)), vixo=ratio(v, fo))))
    return pd.concat(rows, ignore_index=True)


OPT_KEYS = ("civ", "piv", "skew_c", "skew_p", "coi", "poi", "cvol", "pvol", "strad", "spot")


def opt_table(days: dict, und: str) -> pd.DataFrame:
    """O inputs at each decision point: the chain's values at minute e - 1 (the last known within the day), their changes
    from minute e - 16 / e - 31, and put / call volume over minutes e - 15 .. e - 1."""
    rows = []
    for d in sorted(days):
        a = {k: np.asarray(days[d][k], float) for k in OPT_KEYS}
        f = {k: ffill(a[k]) for k in ("civ", "piv", "skew_c", "skew_p", "coi", "poi", "strad", "spot")}
        at = lambda k, lag: np.where(E_ROWS - 1 - lag >= 0, f[k][np.maximum(E_ROWS - 1 - lag, 0)], np.nan)  # noqa: E731
        skew = lambda lag: at("skew_p", lag) - at("skew_c", lag)  # noqa: E731
        pcr = lambda lag: div(at("poi", lag), at("coi", lag))  # noqa: E731
        sp = lambda lag: div(at("strad", lag), at("spot", lag))  # noqa: E731
        cv = np.nan_to_num(a["cvol"])
        pv = np.nan_to_num(a["pvol"])
        ccv, cpv = np.concatenate([[0], np.cumsum(cv)]), np.concatenate([[0], np.cumsum(pv)])
        lo = np.maximum(E_ROWS - 15, 0)
        vc, vp = ccv[E_ROWS] - ccv[lo], cpv[E_ROWS] - cpv[lo]
        has = np.isfinite(a["cvol"]).any() and np.isfinite(a["pvol"]).any()
        rows.append(pd.DataFrame(dict(
            und=und, day=d, e=E_ROWS,
            civ=at("civ", 0), piv=at("piv", 0), skew=skew(0),
            civ15=at("civ", 0) - at("civ", 15), piv15=at("piv", 0) - at("piv", 15), skew15=skew(0) - skew(15),
            pcr=pcr(0), pcr15=pcr(0) - pcr(15), pcr30=pcr(0) - pcr(30),
            coi15=ratio(at("coi", 0), at("coi", 15)), poi15=ratio(at("poi", 0), at("poi", 15)),
            strad=sp(0), strad15=sp(0) - sp(15),
            pcv=div(vp, vc) if has else np.full(len(E_ROWS), np.nan))))
    return pd.concat(rows, ignore_index=True) if rows else pd.DataFrame(columns=["und", "day", "e"])


P_COLS = ["r1", "r5", "r15", "r30", "r60", "ropen", "gap", "prev_ret", "pos", "dvwap", "rv30", "rng15", "don", "st", "emad", "tod", "dow"]
V_COLS = ["vix", "vix5", "vix15", "vix60", "vixo"]
O_COLS = ["civ", "piv", "skew", "civ15", "piv15", "skew15", "pcr", "pcr15", "pcr30", "coi15", "poi15", "strad", "strad15", "pcv"]
X_COLS = [f"x{k}_{h}" for k in range(1, 5) for h in ("r5", "r15", "r60")] + ["up5", "up15n", "nifty5", "nifty15"]
U_COLS = [f"is_{u}" for u in K.UNDS]
GROUPS = {"P": P_COLS + U_COLS, "PX": P_COLS + U_COLS + X_COLS, "PXV": P_COLS + U_COLS + X_COLS + V_COLS,
          "ALL": P_COLS + U_COLS + X_COLS + V_COLS + O_COLS}


def build(idx: dict, vix: dict, opt: dict) -> pd.DataFrame:
    """The full input table: one row per (index, day, decision minute)."""
    own = {u: own_table(idx[u], u) for u in K.UNDS if u in idx and idx[u]}
    wide = None
    for u, t in own.items():
        x = t[["day", "e", "r5", "r15", "r60"]].rename(columns={c: f"{u}_{c}" for c in ("r5", "r15", "r60")})
        wide = x if wide is None else wide.merge(x, on=["day", "e"], how="outer")
    out = []
    for u, t in own.items():
        t = t.merge(wide, on=["day", "e"], how="left")
        others = [o for o in K.UNDS if o != u]
        for k, o in enumerate(others, start=1):
            for h in ("r5", "r15", "r60"):
                t[f"x{k}_{h}"] = t[f"{o}_{h}"] if f"{o}_{h}" in t else np.nan
        r5s = np.stack([t[f"{o}_r5"].to_numpy() if f"{o}_r5" in t else np.full(len(t), np.nan) for o in K.UNDS])
        r15s = np.stack([t[f"{o}_r15"].to_numpy() if f"{o}_r15" in t else np.full(len(t), np.nan) for o in K.UNDS])
        t["up5"] = np.nansum(r5s > 0, axis=0).astype(float)
        t["up15n"] = np.nansum(r15s > 0, axis=0).astype(float)
        t["nifty5"] = t["NIFTY_r5"] if "NIFTY_r5" in t else np.nan
        t["nifty15"] = t["NIFTY_r15"] if "NIFTY_r15" in t else np.nan
        t = t.drop(columns=[c for c in t.columns if any(c.startswith(f"{o}_") for o in K.UNDS)])
        for v in K.UNDS:
            t[f"is_{v}"] = 1.0 if v == u else 0.0
        if u in opt and opt[u]:
            t = t.merge(opt_table(opt[u], u).drop(columns=["und"]), on=["day", "e"], how="left")
        else:
            for c in O_COLS:
                t[c] = np.nan
        out.append(t)
    df = pd.concat(out, ignore_index=True)
    if vix:
        df = df.merge(vix_table(vix), on=["day", "e"], how="left")
    else:
        for c in V_COLS:
            df[c] = np.nan
    return df.sort_values(["day", "e", "und"]).reset_index(drop=True)


# ================================================================================================ splits and statistics
def quarter_end(q):
    y, m = q.year, q.month + 3
    if m > 12:
        y, m = y + 1, m - 12
    return type(q)(y, m, 1)


def wf_split(days: pd.Series, q) -> tuple:
    """(train mask, test mask): train = TRAIN_FROM <= day < q; test = q <= day < the next quarter's first day."""
    d = pd.to_datetime(days).dt.date
    tr = (d >= K.TRAIN_FROM) & (d < q)
    te = (d >= q) & (d < quarter_end(q))
    return tr.to_numpy(), te.to_numpy()


def auc(y, p) -> float:
    y, p = np.asarray(y, float), np.asarray(p, float)
    ok = np.isfinite(y) & np.isfinite(p)
    y, p = y[ok], p[ok]
    n1, n0 = int((y == 1).sum()), int((y == 0).sum())
    if n1 == 0 or n0 == 0:
        return math.nan
    r = pd.Series(p).rank().to_numpy()
    return float((r[y == 1].sum() - n1 * (n1 + 1) / 2.0) / (n1 * n0))


def boot_auc(y, p, day_id, n: int, rng) -> dict:
    """Day-block bootstrap of the AUC: whole days resampled; p = share of draws with AUC <= 0.5."""
    y, p, day_id = np.asarray(y, float), np.asarray(p, float), np.asarray(day_id, int)
    a0 = auc(y, p)
    days = np.unique(day_id)
    groups = [np.flatnonzero(day_id == d) for d in days]
    out = np.empty(n)
    for i in range(n):
        pick = rng.integers(0, len(days), len(days))
        ix = np.concatenate([groups[k] for k in pick])
        out[i] = auc(y[ix], p[ix])
    ok = out[np.isfinite(out)]
    se = float(ok.std(ddof=1)) if len(ok) > 1 else 0.0
    return dict(auc=a0, p=float((1 + int((ok <= 0.5).sum())) / (len(ok) + 1)), se=se,
                p_normal=float(0.5 * math.erfc(((a0 - 0.5) / se) / math.sqrt(2.0))) if se > 0 else (0.0 if a0 > 0.5 else 1.0),
                lo=float(np.percentile(ok, 2.5)), hi=float(np.percentile(ok, 97.5)))


def accuracy(y, p) -> float:
    y, p = np.asarray(y, float), np.asarray(p, float)
    ok = np.isfinite(y) & np.isfinite(p)
    return float(((p[ok] >= 0.5) == (y[ok] == 1)).mean()) if ok.any() else math.nan


def top_accuracy(y, p, top: float = K.TOP) -> tuple:
    """(accuracy of the most confident `top` share of calls, how many)."""
    y, p = np.asarray(y, float), np.asarray(p, float)
    ok = np.isfinite(y) & np.isfinite(p)
    y, p = y[ok], p[ok]
    if not len(y):
        return math.nan, 0
    conf = np.abs(p - 0.5)
    k = max(1, int(round(top * len(y))))
    ix = np.argsort(-conf, kind="mergesort")[:k]
    return float(((p[ix] >= 0.5) == (y[ix] == 1)).mean()), k


def bh_total(p, m_total: int = K.M_TOTAL) -> list:
    p = np.asarray(p, float)
    n = len(p)
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return [float(x) for x in np.minimum(q, 1.0)]


def gate_t1(hold: dict, top_hold: float, wf_tops: list, q) -> dict:
    fails = []
    if not (hold["auc"] > 0.5 and hold["p"] < K.GATE_P):
        fails.append(f"holdout AUC {hold['auc']:.4f} with p {hold['p']:.4f} (needs above 0.5 with p < {K.GATE_P})")
    if not hold["auc"] >= K.AUC_MIN:
        fails.append(f"holdout AUC {hold['auc']:.4f} < {K.AUC_MIN}")
    if not (top_hold == top_hold and top_hold >= K.TOP_ACC):
        fails.append(f"holdout top-20% accuracy {top_hold * 100:.1f}% < {K.TOP_ACC * 100:.0f}%")
    up = sum(1 for a in wf_tops if a == a and a >= K.TOP_ACC)
    if up < K.TOP_QUARTERS:
        fails.append(f"walk-forward top-20% accuracy >= {K.TOP_ACC * 100:.0f}% in {up} of {len(wf_tops)} quarters < {K.TOP_QUARTERS}")
    if q is None or not q <= K.GATE_Q:
        fails.append("BH q " + ("n/a" if q is None else f"{q:.3g}") + f" > {K.GATE_Q}")
    return dict(passes=not fails, fails=fails, quarters_up=up, q=q)


def gate_t2(r: dict, q) -> dict:
    fails = []
    if r["d"] is None or not (r["d"] > 0 and r["p"] < K.GATE_P):
        fails.append(f"difference {('n/a' if r['d'] is None else format(r['d'] * 100, '+.1f'))} points with p {r['p']:.4f}")
    edge = None if r["rate_a"] is None or r["rate_src"] is None else r["rate_a"] - r["rate_src"]
    if edge is None or edge < K.T2_EDGE - 1e-12:
        fails.append(f"favoured signals {('n/a' if edge is None else format(edge * 100, '+.1f'))} points above the source's own (needs 3)")
    if q is None or not q <= K.GATE_Q:
        fails.append("BH q " + ("n/a" if q is None else f"{q:.3g}") + f" > {K.GATE_Q}")
    return dict(passes=not fails, fails=fails, edge=edge, q=q)


def choose_t(nets_by_t: dict) -> float:
    """T3's threshold: the highest design net; ties go to the lowest t."""
    best = max(nets_by_t.values())
    return min(t for t, v in nets_by_t.items() if v == best)
