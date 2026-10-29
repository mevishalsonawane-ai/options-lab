"""R136: the ICT / SMC books' concepts as network inputs (PREREG.md section "The new inputs"), per decision row
(index, session, decision minute t). Every input uses index minutes up to and including minute t only (wicks).

    features(und, last_day) -> DataFrame [und, day, row] + ICT_COLS
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
sys.path.insert(0, str(HERE.parent / "r135_ict_facts"))
import r131 as R  # noqa: E402
import r135 as F35  # noqa: E402

K, D3 = R.K, R.D3
N = R.N
TICK = 0.05
OR_END = K.row(9, 44)
DEC_ROWS = list(K.K3.DECISION_ROWS)
LVL = ("pdh", "pdl", "pwh", "pwl", "pmh", "pml")
ICT_COLS = ([f"ict_d_{x}" for x in LVL] + [f"ict_sw_{x}" for x in LVL] +
            ["ict_d_orh", "ict_d_orl", "ict_sw_orh", "ict_sw_orl", "ict_d_open", "ict_pos_day",
             "ict_d_sh", "ict_d_sl", "ict_strong_sh", "ict_strong_sl", "ict_bos_dir", "ict_bos_strong", "ict_pos_leg",
             "ict_sweep5", "ict_disp5",
             "ict_fvg_up_d", "ict_fvg_dn_d", "ict_fvg_up_n", "ict_fvg_dn_n",
             "ict_d_sma200", "ict_ret20", "ict_tdi_z", "ict_tdi_x"])
assert len(ICT_COLS) == 35


# ------------------------------------------------------------------------------------------------ 15-minute structure
def structure_events(ch: R.Chart):
    """Per swing: swept (at its bar) and the bar by which it broke structure; and the break-of-structure events
    (first close beyond a swing) with their direction and whether the broken swing was strong at that bar."""
    k = ch.k
    sh, sl = ch.sh, ch.sl
    win = lambda i: int(np.searchsorted(ch.day, ch.day[i] + R.SESSIONS_BACK + 1)) - 1  # noqa: E731

    first_bar = np.searchsorted(ch.day, np.arange(int(ch.day[-1]) + 2)) if ch.T else np.array([0])

    def window_piv(piv, i):
        a = int(np.searchsorted(piv, first_bar[max(int(ch.day[i]) - R.SESSIONS_BACK, 0)]))
        b = int(np.searchsorted(piv, i - k))            # pivots j < i - k (confirmed before bar i)
        return piv[a:b]

    def swept_hi(i):
        prev = window_piv(sh, i)
        return any(ch.h[i] >= ch.h[j] + TICK and ch.h[j + 1:i].max(initial=-np.inf) < ch.h[j] + TICK for j in prev)

    def swept_lo(i):
        prev = window_piv(sl, i)
        return any(ch.l[i] <= ch.l[j] - TICK and ch.l[j + 1:i].min(initial=np.inf) > ch.l[j] - TICK for j in prev)

    strong_bar = {}                      # ('H'|'L', i) -> the bar from which it is strong (or absent)
    for i in sh:
        i = int(i)
        if not swept_hi(i):
            continue
        lows = sl[sl < i]
        if not len(lows):
            continue
        ref = ch.l[int(lows[-1])]
        for t in range(i + 1, min(win(i), ch.T - 1) + 1):
            if ch.h[t] > ch.h[i]:
                break
            if ch.c[t] < ref:
                strong_bar[("H", i)] = t
                break
    for i in sl:
        i = int(i)
        if not swept_lo(i):
            continue
        highs = sh[sh < i]
        if not len(highs):
            continue
        ref = ch.h[int(highs[-1])]
        for t in range(i + 1, min(win(i), ch.T - 1) + 1):
            if ch.l[t] < ch.l[i]:
                break
            if ch.c[t] > ref:
                strong_bar[("L", i)] = t
                break
    bos = []                             # (bar, dir, strong)
    for typ, piv, sgn in (("H", sh, 1), ("L", sl, -1)):
        for i in piv:
            i = int(i)
            lv = ch.h[i] if sgn > 0 else ch.l[i]
            t = next((x for x in range(i + k + 1, min(win(i), ch.T - 1) + 1)
                      if (ch.c[x] > lv if sgn > 0 else ch.c[x] < lv)), None)
            if t is not None:
                sb = strong_bar.get((typ, i))
                bos.append((t, sgn, int(sb is not None and sb < t)))
    bos.sort()
    return strong_bar, bos


def fvg_list(ch: R.Chart):
    """15-minute FVGs: (formed bar j+1, mid, half-fill bar or a big number)."""
    out = []
    for j in range(1, ch.T - 1):
        if not ch.same(j - 1, j + 1):
            continue
        if ch.l[j + 1] - ch.h[j - 1] >= R.fvg_min(ch.l[j + 1]):            # bullish: price above it
            lo, hi, bull = ch.h[j - 1], ch.l[j + 1], True
        elif ch.l[j - 1] - ch.h[j + 1] >= R.fvg_min(ch.h[j + 1]):          # bearish: price below it
            lo, hi, bull = ch.h[j + 1], ch.l[j - 1], False
        else:
            continue
        mid = (lo + hi) / 2
        last = min(ch.T - 1, int(np.searchsorted(ch.day, ch.day[j] + R.SESSIONS_BACK + 1)) - 1)
        f = next((t for t in range(j + 2, last + 1) if (ch.l[t] <= mid if bull else ch.h[t] >= mid)), 10 ** 9)
        out.append((j + 1, mid, f, int(ch.day[j]), last))
    return out


def rsi_wilder(c: np.ndarray, n: int = 50) -> np.ndarray:
    d = np.diff(c, prepend=c[0])
    up, dn = np.clip(d, 0, None), np.clip(-d, 0, None)
    a = 1.0 / n
    au = pd.Series(up).ewm(alpha=a, adjust=False).mean().values
    ad = pd.Series(dn).ewm(alpha=a, adjust=False).mean().values
    rs = au / np.where(ad > 0, ad, np.nan)
    out = 100 - 100 / (1 + rs)
    out[ad == 0] = 100.0
    out[:n] = np.nan
    return out


# ------------------------------------------------------------------------------------------------ the builder
def features(und: str, last_day, days_keep=None, g=None) -> pd.DataFrame:
    g = g if g is not None else D3.load_grid(und, last_day)
    D = len(g.days)
    lv, dh, dl = F35.period_levels(g.days, g.h, g.l)
    dclose = np.array([D3._last(g.c[d]) for d in range(D)])
    sides = R.sides_of(g)
    S = sides[1]
    c15 = S.chart(15)
    c5 = S.chart(5)
    strong_bar, bos = structure_events(c15)
    bos_bars = np.array([b[0] for b in bos]) if bos else np.array([], dtype=int)
    fvgs = fvg_list(c15)
    fv_b = np.array([f[0] for f in fvgs], dtype=int)
    fv_m = np.array([f[1] for f in fvgs], dtype=float)
    fv_f = np.array([f[2] for f in fvgs], dtype=np.int64)
    fv_last = np.array([f[4] for f in fvgs], dtype=int)
    first15 = np.searchsorted(c15.day, np.arange(D + 1))
    # 5-minute sweeps with displacement, both sides (R130/R131 sweep of the recent untaken swing)
    sweeps = []                                                       # (bar, sign, disp-known-bar, disp)
    for sd, Sx in sides.items():
        ch = Sx.chart(5)
        st = R.Struct(ch)
        for t in range(ch.T):
            st.new_bar(t)
            L = st.recent_low()
            if L is not None and ch.l[t] <= L - TICK:
                disp = bool(t + 2 < ch.T and ch.same(t, t + 2) and ch.l[t + 2] - ch.h[t] >= R.fvg_min(ch.l[t + 2]))
                sweeps.append((t, sd, t + 2, disp))
            st.after_bar(t)
    sweeps.sort()
    sw_bars = np.array([s[0] for s in sweeps]) if sweeps else np.array([], dtype=int)
    # TDI on 5-minute closes
    rsi = rsi_wilder(c5.c, 50)
    rs = pd.Series(rsi)
    mid = rs.rolling(50).mean().values
    sdv = rs.rolling(50).std().values
    z = (rsi - mid) / np.where(sdv > 0, sdv, np.nan)
    gend15, gend5 = c15.gend, c5.gend
    rows = []
    for d in range(D):
        if days_keep is not None and not days_keep(g.days[d]):
            continue
        H, L, O, C = g.h[d], g.l[d], g.o[d], g.c[d]
        if np.isfinite(C).sum() < 30:
            continue
        hi_s = np.fmax.accumulate(np.where(np.isfinite(H), H, -np.inf))
        lo_s = np.fmin.accumulate(np.where(np.isfinite(L), L, np.inf))
        cl = D3.ffill_rows(C[None, :])[0]
        op = D3._first(O)
        orh = np.nanmax(H[:OR_END + 1]) if np.isfinite(H[:OR_END + 1]).any() else np.nan
        orl = np.nanmin(L[:OR_END + 1]) if np.isfinite(L[:OR_END + 1]).any() else np.nan
        sma = np.nanmean(dclose[d - 200:d]) if d >= 200 else np.nan
        r20 = (dclose[d - 1] / dclose[d - 21] - 1) if d >= 21 else np.nan
        for t in DEC_ROWS:
            px = cl[t]
            if not np.isfinite(px):
                continue
            rec = dict(und=und, day=g.days[d], row=t)
            for n_, x in enumerate(LVL):
                Lv = lv[d, n_]
                rec[f"ict_d_{x}"] = (px - Lv) / px if np.isfinite(Lv) else np.nan
                if not (np.isfinite(Lv) and np.isfinite(op)):
                    rec[f"ict_sw_{x}"] = np.nan
                elif n_ % 2 == 0:
                    rec[f"ict_sw_{x}"] = float(op < Lv and hi_s[t] >= Lv + TICK)
                else:
                    rec[f"ict_sw_{x}"] = float(op > Lv and lo_s[t] <= Lv - TICK)
            if t > OR_END and np.isfinite(orh):
                rec["ict_d_orh"], rec["ict_d_orl"] = (px - orh) / px, (px - orl) / px
                rec["ict_sw_orh"] = float(np.nanmax(np.where(np.isfinite(H[OR_END + 1:t + 1]), H[OR_END + 1:t + 1], -np.inf),
                                                    initial=-np.inf) >= orh + TICK)
                rec["ict_sw_orl"] = float(np.nanmin(np.where(np.isfinite(L[OR_END + 1:t + 1]), L[OR_END + 1:t + 1], np.inf),
                                                    initial=np.inf) <= orl - TICK)
            else:
                rec["ict_d_orh"] = rec["ict_d_orl"] = rec["ict_sw_orh"] = rec["ict_sw_orl"] = np.nan
            rec["ict_d_open"] = (px - op) / px if np.isfinite(op) else np.nan
            rng = hi_s[t] - lo_s[t]
            rec["ict_pos_day"] = (px - lo_s[t]) / rng if rng > 0 else 0.5
            gm = d * N + t
            # 15-minute state: the last bar completed by minute t
            b = int(np.searchsorted(gend15, gm, side="right")) - 1
            rec.update(_structure_at(c15, b, px, strong_bar, bos, bos_bars))
            # open FVGs at bar b
            up_m, dn_m = [], []
            if b >= 0 and len(fv_b):
                a0 = int(np.searchsorted(fv_b, first15[max(int(c15.day[b]) - R.SESSIONS_BACK, 0)]))
                a1 = int(np.searchsorted(fv_b, b, side="right"))
                sel = np.arange(a0, a1)
                sel = sel[(fv_f[sel] > b) & (fv_last[sel] >= b)]
                up_m = fv_m[sel][fv_m[sel] > px].tolist()
                dn_m = fv_m[sel][fv_m[sel] < px].tolist()
            rec["ict_fvg_up_d"] = (min(up_m) - px) / px if up_m else np.nan
            rec["ict_fvg_dn_d"] = (px - max(dn_m)) / px if dn_m else np.nan
            rec["ict_fvg_up_n"], rec["ict_fvg_dn_n"] = float(len(up_m)), float(len(dn_m))
            # 5-minute sweep + displacement in the last 6 completed bars
            b5 = int(np.searchsorted(gend5, gm, side="right")) - 1
            rec["ict_sweep5"] = rec["ict_disp5"] = 0.0
            if b5 >= 0 and len(sw_bars):
                lo_i = int(np.searchsorted(sw_bars, b5 - 5))
                hi_i = int(np.searchsorted(sw_bars, b5, side="right"))
                if hi_i > lo_i:
                    s_bar, sgn, known, disp = sweeps[hi_i - 1]
                    if c5.day[s_bar] == c5.day[b5]:
                        rec["ict_sweep5"] = float(sgn)
                        rec["ict_disp5"] = float(disp and known <= b5)
            rec["ict_d_sma200"] = (px - sma) / px if np.isfinite(sma) else np.nan
            rec["ict_ret20"] = r20
            rec["ict_tdi_z"] = float(z[b5]) if b5 >= 0 and np.isfinite(z[b5]) else np.nan
            x = 0.0
            if b5 >= 3:
                dd = rsi[b5 - 3:b5 + 1] - mid[b5 - 3:b5 + 1]
                if np.isfinite(dd).all():
                    sg = np.sign(dd)
                    ch_ = np.flatnonzero(sg[1:] != sg[:-1])
                    if len(ch_):
                        x = float(sg[ch_[-1] + 1])
            rec["ict_tdi_x"] = x
            rows.append(rec)
    return pd.DataFrame(rows, columns=["und", "day", "row"] + ICT_COLS)


def _structure_at(ch: R.Chart, b: int, px: float, strong_bar: dict, bos: list, bos_bars: np.ndarray) -> dict:
    out = dict(ict_d_sh=np.nan, ict_d_sl=np.nan, ict_strong_sh=np.nan, ict_strong_sl=np.nan, ict_bos_dir=0.0,
               ict_bos_strong=0.0, ict_pos_leg=np.nan)
    if b < 0:
        return out
    k = ch.k
    day_b = int(ch.day[b])
    ish = ch.sh[max(int(np.searchsorted(ch.sh, b - k, side="right")) - 1, 0):int(np.searchsorted(ch.sh, b - k, side="right"))]
    isl = ch.sl[max(int(np.searchsorted(ch.sl, b - k, side="right")) - 1, 0):int(np.searchsorted(ch.sl, b - k, side="right"))]
    ish = ish[ch.day[ish] >= day_b - R.SESSIONS_BACK] if len(ish) else ish
    isl = isl[ch.day[isl] >= day_b - R.SESSIONS_BACK] if len(isl) else isl
    if len(ish):
        i = int(ish[-1])
        out["ict_d_sh"] = (px - ch.h[i]) / px
        sb = strong_bar.get(("H", i))
        out["ict_strong_sh"] = float(sb is not None and sb <= b)
    if len(isl):
        i = int(isl[-1])
        out["ict_d_sl"] = (px - ch.l[i]) / px
        sb = strong_bar.get(("L", i))
        out["ict_strong_sl"] = float(sb is not None and sb <= b)
    if len(ish) and len(isl):
        Hh, Ll = ch.h[int(ish[-1])], ch.l[int(isl[-1])]
        if Hh > Ll:
            out["ict_pos_leg"] = float(np.clip((px - Ll) / (Hh - Ll), -1.0, 2.0))
    j = int(np.searchsorted(bos_bars, b, side="right")) - 1
    if j >= 0 and ch.day[bos[j][0]] >= day_b - R.SESSIONS_BACK:
        out["ict_bos_dir"], out["ict_bos_strong"] = float(bos[j][1]), float(bos[j][2])
    return out
