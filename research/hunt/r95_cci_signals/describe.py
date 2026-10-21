"""R95 descriptive study (PREREG section 6): the CCI in price terms, how often it fires, what price does next, and the
divergence labels' repaint in time. No gate; design first, the holdout only inside `study.py final`.

All on the UNDERLYING, base indicator (CCI 25 of hlc3, +-200). Forward returns are signed in the signal's direction
(BUY up, SELL down) and measured from the entry (the next minute's open after the signal bar).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import cfg as K
from . import cci as C
from . import price as PR
from . import rules as R


def _q(x) -> dict:
    x = np.asarray([v for v in x if np.isfinite(v)], float)
    if not len(x):
        return dict(n=0)
    return dict(n=int(len(x)), mean=round(float(x.mean()), 3), p25=round(float(np.percentile(x, 25)), 3),
                median=round(float(np.median(x)), 3), p75=round(float(np.percentile(x, 75)), 3))


def forward(I, B: dict, a_bar: np.ndarray, sides: np.ndarray, atr: np.ndarray, h: int) -> pd.DataFrame:
    """Per (bar, side): the entry at the next minute's open, then (a) the continuous view: the close of bar + h and the
    extremes of bars +1 .. +h (may cross sessions), (b) the session view: cut at the session's flat minute."""
    P = I.P
    mo, mh, ml, mc, mday = P.mo, P.mh, P.ml, P.mc, P.mday
    nb = len(B["o"])
    rows = []
    for i, s in zip(a_bar, sides):
        i, s = int(i), int(s)
        t0 = int(B["e"][i]) + 1
        if i + h >= nb or t0 >= len(mo) or int(mday[t0]) != int(B["day"][i]):
            continue
        E = float(mo[t0])
        d = int(B["day"][i])
        A = float(atr[i]) if np.isfinite(atr[i]) and atr[i] > 0 else np.nan
        hi = float(B["h"][i + 1:i + h + 1].max())
        lo = float(B["l"][i + 1:i + h + 1].min())
        ret = s * (float(B["c"][i + h]) - E)
        mfe = (hi - E) if s > 0 else (E - lo)
        mae = (E - lo) if s > 0 else (hi - E)
        sq = int(I.sq[d])
        endm = int(B["e"][i + h])
        cut = int(B["day"][i + h]) != d or endm >= sq
        if t0 >= sq:
            continue
        if cut:
            X, seg = float(mo[sq]), slice(t0, sq)
        else:
            X, seg = float(mc[endm]), slice(t0, endm + 1)
        shi, slo = float(mh[seg].max()), float(ml[seg].min())
        rows.append(dict(i=i, side=s, ret=ret, mfe=mfe, mae=mae, ret_atr=ret / A, mfe_atr=mfe / A, mae_atr=mae / A,
                         cross=bool(int(B["day"][i + h]) != d), s_ret=s * (X - E),
                         s_mfe=(shi - E) if s > 0 else (E - slo), s_mae=(E - slo) if s > 0 else (shi - E), s_cut=cut))
    return pd.DataFrame(rows)


def _fw_summary(F: pd.DataFrame) -> dict:
    if not len(F):
        return dict(n=0)
    return dict(n=int(len(F)), win=round(float((F.ret > 0).mean()), 4), ret=_q(F.ret), ret_atr=_q(F.ret_atr),
                mfe=_q(F.mfe), mae=_q(F.mae), mfe_atr=_q(F.mfe_atr), mae_atr=_q(F.mae_atr),
                cross_share=round(float(F.cross.mean()), 4), s_win=round(float((F.s_ret > 0).mean()), 4),
                s_ret=_q(F.s_ret), s_mfe=_q(F.s_mfe), s_mae=_q(F.s_mae), s_cut_share=round(float(F.s_cut.mean()), 4))


def in_window(I, B: dict, a: int, b: int) -> np.ndarray:
    """Bars whose session is inside [a, b], trade-allowed, and whose next minute is inside the entry window."""
    P = I.P
    first, last = I.win()
    e1 = np.minimum(B["e"] + 1, len(P.mt) - 1)
    same = P.mday[e1] == B["day"]
    tod = P.mt[e1]
    d = B["day"]
    return (d >= a) & (d <= b) & I.day_ok[d] & same & (tod >= first) & (tod <= last) & (e1 < I.sq[d])


def mapping(I, Ind: C.Ind, sig: np.ndarray, sides: np.ndarray) -> dict:
    """The price meaning of the CCI at the signal bar (i) and the bar before it (i - 1)."""
    tp = Ind.tp
    m = C.sma(tp, Ind.n)
    md = C.mean_dev(tp, Ind.n)
    sd = C.std_dev(tp, Ind.n)
    A = Ind.atr
    out = {}
    for name, s in (("buy", 1), ("sell", -1)):
        ix = sig[sides == s]
        if not len(ix):
            out[name] = dict(n=0)
            continue
        out[name] = dict(
            n=int(len(ix)),
            cci_prev=_q(Ind.cci[ix - 1]), cci_now=_q(Ind.cci[ix]),
            dist_prev_pts=_q(tp[ix - 1] - m[ix - 1]), dist_now_pts=_q(tp[ix] - m[ix]),
            dist_prev_atr=_q((tp[ix - 1] - m[ix - 1]) / A[ix - 1]), dist_now_atr=_q((tp[ix] - m[ix]) / A[ix]),
            close_minus_mean_pts=_q(Ind.B["c"][ix] - m[ix]), md_pts=_q(md[ix]), md_over_sd=_q(md[ix] / sd[ix]),
            atr_pts=_q(A[ix]))
    return out


def repaint(I, Ind: C.Ind, a: int, b: int, tf: int) -> dict:
    """Divergence labels: forward returns and an option trade (1-ITM, CCI-zero exit, stop beyond the pivot) entered at
    the LABEL bar (the pivot, hindsight) vs at the CONFIRMATION bar (pivot + 5, when it is known)."""
    B = Ind.B
    P = I.P
    first, last = I.win()
    ok = in_window(I, B, a, b)
    up, dn = R._next_cross(Ind.cci)
    out = {}
    for tag in ("label", "confirm"):
        fw_rows, trades = {h: [] for h in K.HORIZONS}, []
        for dv in Ind.divs:
            if not (a <= int(B["day"][dv.q]) <= b):
                continue
            x = dv.p if tag == "label" else dv.q
            for h in K.HORIZONS:
                if x + h < len(B["c"]):
                    fw_rows[h].append(dv.side * (float(B["c"][x + h]) - float(B["c"][x])))
            if not ok[x]:
                continue
            d = int(B["day"][x])
            t0 = int(B["e"][x]) + 1
            ext = float(B["l"][dv.p]) if dv.side > 0 else float(B["h"][dv.p])
            stop = ext - I.tick if dv.side > 0 else ext + I.tick
            E = float(P.mo[t0])
            if not dv.side * (E - stop) > 0:
                continue
            k = int(up[x] if dv.side > 0 else dn[x])
            xb = int(B["e"][k]) if k < len(B["o"]) and int(B["day"][k]) == d else -1
            xm, why, px = R.walk(P.mo, P.mh, P.ml, P.mc, t0, dv.side, stop, np.nan, xb, int(I.sq[d]))
            trades.append(dict(day=d, ts=t0 - 1, t=t0, side=dv.side, xm=int(xm), why=R.WHY[int(why)],
                               pts=float(dv.side * (px - E))))
        O = [o for o in PR.option(I, trades) if o["filled"]]
        net = np.array([o["net"] for o in O])
        pts = np.array([t["pts"] for t in trades])
        out[tag] = dict(divergences=int(sum(1 for dv in Ind.divs if a <= int(B["day"][dv.q]) <= b)),
                        fwd={str(h): dict(n=len(v), win=round(float(np.mean(np.array(v) > 0)), 4) if v else None,
                                          mean_pts=round(float(np.mean(v)), 3) if v else None)
                             for h, v in fw_rows.items()},
                        und=dict(trades=int(len(pts)), win=round(float((pts > 0).mean()), 4) if len(pts) else None,
                                 mean_pts=round(float(pts.mean()), 3) if len(pts) else None),
                        opt=dict(trades=int(len(net)), win=round(float((net > 0).mean()), 4) if len(net) else None,
                                 net=round(float(net.sum()), 0), per_trade=round(float(net.mean()), 1) if len(net) else None))
    L, Cf = out["label"], out["confirm"]
    out["inflation"] = dict(
        und_win=None if L["und"]["win"] is None or Cf["und"]["win"] is None else round(L["und"]["win"] - Cf["und"]["win"], 4),
        und_pts=None if L["und"]["mean_pts"] is None or Cf["und"]["mean_pts"] is None
        else round(L["und"]["mean_pts"] - Cf["und"]["mean_pts"], 3),
        opt_win=None if L["opt"]["win"] is None or Cf["opt"]["win"] is None else round(L["opt"]["win"] - Cf["opt"]["win"], 4),
        opt_net=round(L["opt"]["net"] - Cf["opt"]["net"], 0))
    return out


def describe(I, lo, hi, day_range) -> dict:
    a, b = day_range(I.P, lo, hi)
    n_sess = int(I.day_ok[a:b + 1].sum())
    res = dict(sessions=n_sess)
    for tf in K.TIMEFRAMES:
        Ind = I.ind(tf)
        B = Ind.B
        d = B["day"]
        inday = (d >= a) & (d <= b) & I.day_ok[d]
        win = in_window(I, B, a, b)
        sig = np.flatnonzero((Ind.buy | Ind.sell) & inday)
        sides = np.where(Ind.buy[sig], 1, -1)
        r = dict(signals=int(len(sig)), buy=int((sides > 0).sum()), sell=int((sides < 0).sum()),
                 per_session=round(len(sig) / max(n_sess, 1), 3),
                 in_window_share=round(float(win[sig].mean()), 4) if len(sig) else None,
                 mapping=mapping(I, Ind, sig, sides))
        sw = sig[win[sig]]
        ss = np.where(Ind.buy[sw], 1, -1)
        allb = np.flatnonzero(win)
        fw = {}
        for h in K.HORIZONS:
            fs = forward(I, B, sw, ss, Ind.atr, h)
            base_l = forward(I, B, allb, np.ones(len(allb), int), Ind.atr, h)
            base_s = forward(I, B, allb, -np.ones(len(allb), int), Ind.atr, h)
            fw[str(h)] = dict(
                buy=_fw_summary(fs[fs.side > 0]) if len(fs) else dict(n=0),
                sell=_fw_summary(fs[fs.side < 0]) if len(fs) else dict(n=0),
                base_long=_fw_summary(base_l), base_short=_fw_summary(base_s))
        r["forward"] = fw
        r["repaint"] = repaint(I, Ind, a, b, tf)
        res[str(tf)] = r
    return res
