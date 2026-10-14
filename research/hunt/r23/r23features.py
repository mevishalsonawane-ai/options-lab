"""R23 Step 1: the pre-break features and the break's outcome (PREREG.md part A2). 13 Oct 2026.

    features(s, rg, side, row)   every feature known at decision row `row` (the break candle's last minute); reads only
                                 rows <= row of the day (option flow rows <= min(row, 59)), earlier sessions, the VIX
                                 previous close and VIX minutes <= row (test_r23.py's look-ahead guard checks it)
    outcome(ix, rg, side, row, atr)   MFE / MAE to 14:59 and the class RUN / FAKE / CHOP (read AFTER the decision)
"""
from __future__ import annotations

import numpy as np

from r23ranges import bars

OUT_LAST_ROW = 344           # outcome window: 1-minute highs / lows through the minute labelled 14:59
RUN_ATR = 0.5
FAKE_MFE_ATR = 0.25
FAKE_BARS = 6                # a 5-min close back inside within 30 minutes
TEST_TOL = 0.10              # an edge test: a 5-min extreme within 0.10 x range of the edge
FLOW_ROW_MAX = 59

FEATURES = ["w_atr", "w_prev", "gap_al", "gap_abs", "gap_filled", "c5_body", "c5_al", "c15_body", "c15_al", "c30_body",
            "c30_al", "c15_wick_with", "ovol45_rel", "ovol15_rel", "oi_flow_al", "oi_chg", "vix_prev", "vix_chg",
            "or_inside_pd", "beyond_pd", "tests", "t_break", "piv_al", "twap_al", "expiry", "brk_str", "brk_body",
            "pd_dir_al"]
BOOL = {"gap_filled", "or_inside_pd", "beyond_pd", "expiry"}


def _candle(ix, n):
    m = ix[:n]
    o, h, lo, c = float(m[0, 0]), float(np.max(m[:, 1])), float(np.min(m[:, 2])), float(m[-1, 3])
    return o, h, lo, c


def _body(o, h, lo, c):
    return abs(c - o) / (h - lo) if h - lo > 1e-9 else 0.0


def features(s, rg: dict, side: int, row: int) -> dict:
    ix = s.ix
    atr = s.atr
    po, ph, pl, pc = s.prev
    hi, lo = rg["hi"], rg["lo"]
    w = hi - lo
    edge = hi if side > 0 else lo
    c = float(ix[row, 3])
    f = {}
    f["w_atr"] = w / atr
    f["w_prev"] = w / (ph - pl) if ph - pl > 1e-9 else np.nan
    gap = (float(ix[0, 0]) - pc) / atr
    f["gap_al"] = side * gap
    f["gap_abs"] = abs(gap)
    seen = ix[:row + 1]
    f["gap_filled"] = float(np.min(seen[:, 2]) <= pc <= np.max(seen[:, 1]))
    for name, n in (("c5", 5), ("c15", 15), ("c30", 30)):
        o, h, l_, cc = _candle(ix, n)
        f[f"{name}_body"] = _body(o, h, l_, cc)
        f[f"{name}_al"] = float(side * np.sign(cc - o))
    o, h, l_, cc = _candle(ix, 15)
    wick = (h - max(o, cc)) if side > 0 else (min(o, cc) - l_)
    f["c15_wick_with"] = wick / (h - l_) if h - l_ > 1e-9 else 0.0
    # option flow (traded indices only)
    f["ovol45_rel"] = f["ovol15_rel"] = f["oi_flow_al"] = f["oi_chg"] = np.nan
    if s.flow is not None:
        vol, oc, op = s.flow
        if len(s.ovol_hist) >= 10:
            m45 = np.median([x[0] for x in s.ovol_hist])
            m15 = np.median([x[1] for x in s.ovol_hist])
            if row >= 44 and m45 > 0:
                f["ovol45_rel"] = float(vol[:45].sum()) / m45
            if row >= 14 and m15 > 0:
                f["ovol15_rel"] = float(vol[:15].sum()) / m15
        t = min(row, FLOW_ROW_MAX)
        tot0 = float(oc[0] + op[0])
        if tot0 > 0:
            f["oi_flow_al"] = side * (float(op[t] - oc[t]) - float(op[0] - oc[0])) / tot0
            f["oi_chg"] = (float(oc[t] + op[t]) - tot0) / tot0
    f["vix_prev"] = float(s.ctx.vix)
    f["vix_chg"] = np.nan
    if s.vix_min is not None and np.isfinite(s.vix_min[0]) and np.isfinite(s.vix_min[row]) and s.vix_min[0] > 0:
        f["vix_chg"] = float(s.vix_min[row] / s.vix_min[0] - 1.0)
    f["or_inside_pd"] = float(hi <= ph and lo >= pl)
    f["beyond_pd"] = float(c > ph) if side > 0 else float(c < pl)
    # edge tests between the range end and the break candle (5-min candles)
    _, H, L, C = bars(ix, 5)
    k_end = (rg["end"] + 1 + 4) // 5
    k_brk = (row + 1) // 5 - 1
    tests = 0
    for k in range(k_end, k_brk):
        ext = H[k] if side > 0 else L[k]
        if side * (ext - (edge - side * TEST_TOL * w)) >= 0 and not side * (C[k] - edge) > 0:
            tests += 1
    f["tests"] = float(tests)
    f["t_break"] = float(row + 1)
    piv = (ph + pl + pc) / 3.0
    f["piv_al"] = side * (c - piv) / atr
    f["twap_al"] = side * (c - float(np.mean(ix[:row + 1, 3]))) / atr
    f["expiry"] = np.nan if s.ctx.expiry is None else float(bool(s.ctx.expiry))
    f["brk_str"] = side * (c - edge) / atr
    bo = float(ix[row - 4, 0])
    bh, bl = float(np.max(ix[row - 4:row + 1, 1])), float(np.min(ix[row - 4:row + 1, 2]))
    f["brk_body"] = _body(bo, bh, bl, c)
    f["pd_dir_al"] = side * (pc - po) / atr
    return f


def outcome(ix: np.ndarray, rg: dict, side: int, row: int, atr: float) -> dict:
    """MFE / MAE (ATR14) from the break close through 14:59 and the class."""
    c = float(ix[row, 3])
    seg = ix[row + 1:OUT_LAST_ROW + 1]
    if len(seg) == 0:
        return dict(mfe=0.0, mae=0.0, cls="CHOP", back30=False)
    if side > 0:
        mfe, mae = (np.max(seg[:, 1]) - c) / atr, (np.min(seg[:, 2]) - c) / atr
    else:
        mfe, mae = (c - np.min(seg[:, 2])) / atr, (c - np.max(seg[:, 1])) / atr
    mfe, mae = max(float(mfe), 0.0), min(float(mae), 0.0)
    edge = rg["hi"] if side > 0 else rg["lo"]
    k = (row + 1) // 5 - 1
    _, _, _, C = bars(ix, 5)
    back = any(not side * (C[j] - edge) > 0 for j in range(k + 1, min(k + 1 + FAKE_BARS, len(C))))
    cls = "RUN" if mfe >= RUN_ATR else ("FAKE" if back and mfe < FAKE_MFE_ATR else "CHOP")
    return dict(mfe=mfe, mae=mae, cls=cls, back30=bool(back), r10=mfe >= 1.0, r15=mfe >= 1.5)
