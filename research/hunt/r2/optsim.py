"""R2 option-buy simulator shared by lead_cand.py (copy of cand.opt_trade, frozen)."""
import numpy as np
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/m3")
import m3lib as M
from lib import *


def opt_trade(m, com, side, e, x_end, stop=None, wait=30):
    """Buy 1-ITM option (call side>0, put side<0) at first printed minute in [e, e+5]; exit at x_end (minute index) or
    -30% stop on the 1-min low. Returns gross, net Rs or None."""
    cp = 0 if side > 0 else 1
    k = m.itm[cp][e - 1]
    if not np.isfinite(k) or x_end <= e: return None
    o, h, lo, c, v = m.leg(cp, k, e, x_end + 10)
    pr = np.isfinite(c) & (v > 0)
    ins = np.where(pr[:wait + 1])[0]
    if not len(ins): return None
    i0 = ins[0]; fill = o[i0]
    if not (fill > 0): return None
    n_end = x_end - e
    px_out = None
    if stop is not None:
        sp = fill * (1 - stop)
        hit = np.where(pr[i0:n_end + 1] & (lo[i0:n_end + 1] <= sp))[0]
        if len(hit):
            j = i0 + hit[0]; px_out = min(o[j], sp) if j > i0 else sp; t_out = j
    if px_out is None:
        after = np.where(pr[n_end:])[0]
        if len(after):
            px_out = c[n_end + after[0]] if after[0] == 0 else o[n_end + after[0]]
        else:
            prev = np.where(pr[:n_end])[0]
            if not len(prev): return None
            px_out = c[prev[-1]]
        t_out = n_end
    mult = OPT_MULT[com]
    tod_in = m.tod[e + i0]; tod_out = m.tod[min(e + t_out, len(m.tod) - 1)]
    s_in = OPT_SPREAD[com][0 if tod_in >= 17 * 60 else 1]; s_out = OPT_SPREAD[com][0 if tod_out >= 17 * 60 else 1]
    b, s = fill * mult, px_out * mult
    gross = s - b
    net = gross - M.opt_charges(b, s) - 0.5 * s_in * b - 0.5 * s_out * s
    return gross, net, b


