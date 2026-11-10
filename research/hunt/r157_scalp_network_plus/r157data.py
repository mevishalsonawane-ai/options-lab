"""R157 extra examples (PREREG section 0): the cross-index sequence and the labels of 28 exits at 1 and 3 lots, row for
row with R156's examples.

    python research/hunt/r157_scalp_network_plus/r157data.py build [UND ...]       # -> $R157_OUT/x_<UND>.npz
    R157_POST=1 python research/hunt/r157_scalp_network_plus/r157data.py build     # -> x_<UND>_post.npz

  xseq (n, 60, 8) float16: for each of the other four indices (in K.UNDS order), its 1-minute return channel and its
       printed flag, side-signed and in its own scale (zeros when that index has no session or no scale that day)
  net  (n, 56) float32 rupees for the position (1 lot: columns 0-27; 3 lots: 28-55); xr (n, 56) int16 exit rows
"""
from __future__ import annotations

import sys

import numpy as np

import r157cfg as K
import r156data as DT6

K4, K6 = K.K4, K.K6


def cross_sequence(others: list, s: int, side: str) -> np.ndarray:
    """(60, 8): for each other index (idx array or None, scale), its return channel and printed flag up to minute s."""
    out = np.zeros((K6.SEQ_LEN, 2 * len(others)), np.float32)
    for j, (idx, scale) in enumerate(others):
        if idx is None or not np.isfinite(scale):
            continue
        q = DT6.sequence(idx, s, scale, side)
        out[:, 2 * j], out[:, 2 * j + 1] = q[:, 0], q[:, 4]
    return out


def run_exit(C, er: int, sq: int, qty: float, real: float, raw: float, p: float, tmax: int, M: dict) -> tuple:
    """A run exit (PREREG enhancement 3): a stop p points below the buy fill; once the premium has been p above the entry
    price the stop trails p points below the highest premium (never below the entry price); time stop; square-off.
    Returns (real entry, real exit, exit row, reason)."""
    E, X, Y = M["E"], M["X"], M["Y"]
    stop_abs = Y.tick_down(real - p)
    if stop_abs <= 0:
        stop_abs = Y.TICK
    _, _, a, b, _, r1, r = X.simulate(C, int(er), int(sq), -1, -1.0, -1.0, p * qty, 0.0, p * qty, Y.NO_STEPS, int(tmax), -1.0,
                                      -1.0, Y.TICK, qty, 0.0, float(stop_abs), -1.0, *E.SLIP)
    return a, b, r1, r


def labels(und: str, D, er: int, side: str, strike, lots: int, M: dict) -> tuple:
    """(net (28,), exit row (28,)) at `lots` lots: R156's labels for the 24 fixed exits, then the 4 run exits."""
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
    qty = float(R.lot(und, D.d)) * lots
    raw, real, ef, _ = X.entry_info(C, int(er), int(sq), qty)
    if ef < 0:
        return net, xr
    rE, rX = np.full(K.NR, np.nan), np.full(K.NR, np.nan)
    sgn = 1 if side == "C" else -1
    for j, rid in enumerate(K.RULE_IDS):
        if rid in K.RUN:
            a, b, r1, r = run_exit(C, er, sq, qty, real, raw, K.RUN[rid]["p"], K.RUN[rid]["tmax"], M)
        else:
            rule = K6.RULES[rid]
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
        net[ok] = ((rX[ok] - rE[ok]) * qty - E.charges(und, rE[ok], rX[ok], qty, D.d)).astype(np.float32)
    return net, xr


def build(und: str) -> dict:
    import r155study as S5
    from datetime import date
    M = S5.mods()
    e6 = DT6.load(und, K.POST)
    lo, hi = (K4.POST_START, K4.POST_END) if K.POST else (K4.EARLY_START, K4.FINAL_END)
    days = dict(M["DATA"].sessions(und, lo, hi))
    other = []
    for u in K.UNDS:
        if u == und:
            continue
        ds = M["DATA"].sessions(u, K4.EARLY_WARM, hi, warm=True)
        sc = DT6.scales([D.idx for _, D in ds])
        other.append({d: (D.idx, sc[i]) for i, (d, D) in enumerate(ds)})
    n = len(e6["day"])
    xseq = np.zeros((n, K6.SEQ_LEN, 8), np.float16)
    net = np.full((n, K.NCOL), np.nan, np.float32)
    xr = np.full((n, K.NCOL), -1, np.int16)
    for i in range(n):
        d = date.fromordinal(int(e6["day"][i]))
        D = days[d]
        s = int(e6["s"][i])
        side = "C" if e6["side"][i] == 1 else "P"
        k = M["E"].strike_for(D, und, side, s, K4.ITM)
        for li, lots in enumerate(K.LOTS):
            a, b = labels(und, D, s + 1, side, k, lots, M)
            net[i, li * K.NR:(li + 1) * K.NR], xr[i, li * K.NR:(li + 1) * K.NR] = a, b
        xseq[i] = cross_sequence([o.get(d, (None, np.nan)) for o in other], s, side)
    same = np.allclose(net[:, :24], e6["net"], equal_nan=True, atol=0.01)
    assert same, "the 24 fixed exits at 1 lot must equal R156's labels"
    K.OUT.mkdir(parents=True, exist_ok=True)
    np.savez(K.OUT / f"x_{und}{'_post' if K.POST else ''}.npz", xseq=xseq, net=net, xr=xr)
    return dict(und=und, examples=n, r156_labels_equal=bool(same), labelled=float(np.isfinite(net).mean()))


def load(und: str, post: bool = False) -> dict:
    z = np.load(K.OUT / f"x_{und}{'_post' if post else ''}.npz")
    return {k: z[k] for k in z.files}


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            print(build(u), flush=True)
    else:
        raise SystemExit(__doc__)
