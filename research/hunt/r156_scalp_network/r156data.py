"""R156 examples (PREREG sections 1-2): for every 5-minute moment x side, the network's inputs and the scalp's real result
under R154's 24 exit rules.

    python research/hunt/r156_scalp_network/r156data.py build [UND ...]        # -> $R156_OUT/ex_<UND>.npz (to 13 Oct)
    R156_POST=1 python research/hunt/r156_scalp_network/r156data.py build      # -> ex_<UND>_post.npz (14-19 Oct)

Arrays per index (one row per moment x side with a usable scale):
  day (ordinal), s (decision row), side (1 call / 0 put), seq (n, 60, 5) float16, tab (n, 43) float32,
  net (n, 24) float32 rupees (NaN = no fill), xr (n, 24) int16 exit row (-1 = no fill)
Every input is read at or before s; labels are R154's engine results from the entry at s + 1.
"""
from __future__ import annotations

import sys
from datetime import date

import numpy as np

import r156cfg as K

K4 = K.K4
NROWS = 375


# ================================================================================================ pure inputs
def day_abs_ret(idx: np.ndarray) -> float:
    """Mean absolute 1-minute log return of a session (printed minutes only); NaN with fewer than 30."""
    c = idx[3]
    f = np.flatnonzero(np.isfinite(c) & (c > 0))
    if len(f) < 30:
        return np.nan
    r = np.abs(np.diff(np.log(c[f])))
    return float(r.mean())


def scales(day_arrays: list, n: int = K.SCALE_N, min_n: int = K.SCALE_MIN) -> np.ndarray:
    """Per session: the mean of the previous n sessions' mean absolute 1-minute returns (never the session itself);
    NaN with fewer than min_n earlier sessions."""
    a = np.array([day_abs_ret(x) for x in day_arrays])
    out = np.full(len(a), np.nan)
    for i in range(len(a)):
        w = a[max(0, i - n):i]
        w = w[np.isfinite(w)]
        if len(w) >= min_n:
            out[i] = w.mean()
    return out


def sequence(idx: np.ndarray, s: int, scale: float, side: str, n: int = K.SEQ_LEN) -> np.ndarray:
    """(n, 5) for the minutes s - n + 1 .. s of one session: close-to-close log return (the first printed minute: from its
    open), (high - low) / close, (close - open) / close, each divided by `scale` and clipped; the close's place in the
    bar's range (-0.5 .. 0.5); a printed flag. Minutes before 09:15 or without a print are zeros. A put is mirrored."""
    out = np.zeros((n, K.SEQ_CH), np.float32)
    o, h, lo, c = idx[0], idx[1], idx[2], idx[3]
    sgn = 1.0 if side == "C" else -1.0
    prev = np.nan
    start = s - n + 1
    for t in range(max(0, start - 30), start):                 # the last close before the window (same session)
        if t >= 0 and np.isfinite(c[t]):
            prev = c[t]
    for j, t in enumerate(range(start, s + 1)):
        if t < 0 or not (np.isfinite(c[t]) and np.isfinite(o[t]) and np.isfinite(h[t]) and np.isfinite(lo[t]) and c[t] > 0):
            continue
        base = prev if np.isfinite(prev) else o[t]
        ret = np.log(c[t] / base) / scale if base > 0 else 0.0
        rng = h[t] - lo[t]
        out[j, 0] = sgn * np.clip(ret, -K.CLIP, K.CLIP)
        out[j, 1] = np.clip(rng / c[t] / scale, 0, K.CLIP)
        out[j, 2] = sgn * np.clip((c[t] - o[t]) / c[t] / scale, -K.CLIP, K.CLIP)
        out[j, 3] = sgn * (((c[t] - lo[t]) / rng - 0.5) if rng > 0 else 0.0)
        out[j, 4] = 1.0
        prev = c[t]
    return out


def last_finite(a: np.ndarray, s: int, stale: int = 5) -> float:
    seg = a[max(0, s - stale + 1): s + 1]
    f = np.flatnonzero(np.isfinite(seg))
    return float(seg[f[-1]]) if len(f) else np.nan


def extras(idx: np.ndarray, s: int, side: str, scale: float, vix: np.ndarray | None, prem: float, expiry: bool,
           weekday: int, und_i: int) -> np.ndarray:
    """The 16 numbers after R155's 27: VIX level and change since the open, the scale, the range so far and the move
    since the open (scale units, the move side-signed), the premium as a share of the index, expiry, weekday, 10 / 12 /
    15 points in scale units, and the index's 5 flags."""
    sgn = 1.0 if side == "C" else -1.0
    c = idx[3]
    f = np.flatnonzero(np.isfinite(c[:s + 1]))
    px = float(c[f[-1]]) if len(f) else np.nan
    x = np.full(16, np.nan, np.float32)
    if vix is not None:
        v1 = last_finite(vix, s)
        g = np.flatnonzero(np.isfinite(vix[:s + 1]))
        x[0] = v1
        if len(g) and np.isfinite(v1) and vix[g[0]] > 0:
            x[1] = v1 / vix[g[0]] - 1
    x[2] = scale * 1e4
    if np.isfinite(px) and px > 0:
        hi, lo_ = np.nanmax(idx[1, :s + 1]), np.nanmin(idx[2, :s + 1])
        x[3] = (hi - lo_) / px / scale
        op = idx[0, f[0]]
        x[4] = sgn * (px / op - 1) / scale if np.isfinite(op) and op > 0 else np.nan
        x[5] = prem / px * 100 if np.isfinite(prem) else np.nan
        for j, p in enumerate(K.SIZES):
            x[8 + j] = p / px / scale
    x[6] = 1.0 if expiry else 0.0
    x[7] = weekday / 4.0
    x[11:16] = 0.0
    x[11 + und_i] = 1.0
    return x


# ================================================================================================ labels
def labels(und: str, D, er: int, side: str, strike: float, M: dict) -> tuple:
    """(net (24,), exit row (24,)) of the scalp entered at row er under every exit rule: R154's `r154engine.price` with
    the contract, quantity, entry fill and charges read once (test_r156.py checks it against `price` rule by rule)."""
    E, X, R, Y = M["E"], M["X"], M["r51cfg"], M["Y"]
    net = np.full(K.NR, np.nan, np.float32)
    xr = np.full(K.NR, -1, np.int16)
    sq = Y.sq_row(D)
    if er >= sq or strike is None:
        return net, xr
    st = E.stack(und, D)
    ci = st.idx(side, strike)
    if ci < 0:
        return net, xr
    C = st.S[ci]
    qty = float(R.lot(und, D.d))
    raw, real, ef, _ = X.entry_info(C, int(er), int(sq), qty)
    if ef < 0:
        return net, xr
    rE, rX = np.full(K.NR, np.nan), np.full(K.NR, np.nan)
    sgn = 1 if side == "C" else -1
    for j, rid in enumerate(K.RULE_IDS):
        rule = K.RULES[rid]
        T = int(rule["tmax"])
        fx, tgt_abs, stop_abs = -1, -1.0, -1.0
        if rule["reading"] == "A":
            tgt_abs = Y.tick_up(real + rule["target"])
            stop_abs = Y.tick_down(real - rule["stop"])
            if stop_abs <= 0:
                stop_abs = Y.TICK
        else:
            m, _, _ = Y.index_trigger(D.idx, int(ef), int(ef + T - 1), int(sq), sgn, float(rule["target"]), float(rule["stop"]))
            if m >= 0:
                fx = m + 1
        _, _, a, b, _, r1, r = X.simulate(C, int(er), int(sq), int(fx), -1.0, -1.0, 0.0, 0.0, 0.0, Y.NO_STEPS, T, -1.0,
                                          -1.0, Y.TICK, qty, 0.0, float(stop_abs), float(tgt_abs), *E.SLIP)
        if r == E.R_NOFILL:
            continue
        rE[j], rX[j], xr[j] = a, b, r1
    ok = np.isfinite(rE)
    if ok.any():
        ch = E.charges(und, rE[ok], rX[ok], qty, D.d)
        net[ok] = ((rX[ok] - rE[ok]) * qty - ch).astype(np.float32)
    return net, xr


# ================================================================================================ build
_VIX: dict = {}


def vix_days() -> dict:
    """{date: (375,) India VIX 1-minute closes} from the lake."""
    if not _VIX:
        import pandas as pd
        import r51cfg
        parts = []
        for y in range(2021, 2027):
            p = r51cfg.LAKE / f"index_1m_INDIAVIX_{y}.parquet"
            if p.exists():
                parts.append(pd.read_parquet(p, columns=["ts", "close"]))
        x = pd.concat(parts, ignore_index=True)
        ts = pd.to_datetime(x.ts)
        if ts.dt.tz is not None:
            ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        x["day"] = ts.dt.date
        x["r"] = ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)
        x = x[(x.r >= 0) & (x.r < NROWS) & (x.close > 0)]
        for d, g in x.groupby("day"):
            a = np.full(NROWS, np.nan)
            a[g.r.values] = g.close.values
            _VIX[d] = a
    return _VIX


def build(und: str) -> dict:
    import r155study as S5
    M = S5.mods()
    FE = M["FE"]
    lo, hi = (K4.POST_START, K4.POST_END) if K.POST else (K4.EARLY_START, K4.FINAL_END)
    F = S5.feat_for(und, hi)
    sc = scales([D.idx for _, D in F.days])
    vix = vix_days()
    ui = K.UNDS.index(und)
    rows = dict(day=[], s=[], side=[], seq=[], tab=[], net=[], xr=[])
    for d, D in M["DATA"].sessions(und, lo, hi):
        i = F.pos[d]
        if not np.isfinite(sc[i]):
            continue
        st = M["E"].stack(und, D)
        for s in K.GRID:
            for side in ("C", "P"):
                k = M["E"].strike_for(D, und, side, s, K4.ITM)
                x, _ = F.decide(d, s, side, k)
                ci = st.idx(side, k) if k is not None else -1
                prem = last_finite(st.S[ci][3], s) if ci >= 0 else np.nan
                tab = np.concatenate([FE.vector(x).astype(np.float32),
                                      extras(D.idx, s, side, sc[i], vix.get(d), prem, D.expiry, d.weekday(), ui)])
                net, xr = labels(und, D, s + 1, side, k, M)
                rows["day"].append(d.toordinal()); rows["s"].append(s); rows["side"].append(1 if side == "C" else 0)
                rows["seq"].append(sequence(D.idx, s, sc[i], side).astype(np.float16))
                rows["tab"].append(tab); rows["net"].append(net); rows["xr"].append(xr)
        F._lv.clear()
    out = dict(day=np.array(rows["day"], np.int32), s=np.array(rows["s"], np.int16), side=np.array(rows["side"], np.int8),
               seq=np.stack(rows["seq"]), tab=np.stack(rows["tab"]).astype(np.float32), net=np.stack(rows["net"]),
               xr=np.stack(rows["xr"]))
    K.OUT.mkdir(parents=True, exist_ok=True)
    np.savez(K.OUT / f"ex_{und}{'_post' if K.POST else ''}.npz", **out)
    return dict(und=und, examples=len(out["day"]), sessions=len(np.unique(out["day"])),
                labelled=float(np.isfinite(out["net"]).mean()))


def load(und: str, post: bool = False) -> dict:
    z = np.load(K.OUT / f"ex_{und}{'_post' if post else ''}.npz")
    return {k: z[k] for k in z.files}


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            print(build(u), flush=True)
    else:
        raise SystemExit(__doc__)
