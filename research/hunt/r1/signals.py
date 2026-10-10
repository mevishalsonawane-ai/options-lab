"""R1 signals: the 15 internet ideas of PREREG.md -> trade requests for lib.run_engine.

Every request: var, idea, di, s (decision column; entry at the open of s+1), side (0 CE / 1 PE), x (index exit column,
-1 = none), sp, tp (NaN), liq, lo, hi (random-baseline entry window; lo == hi = same minute, random side only),
idx_exit (True when x came from an index rule, so the random twin holds the same number of minutes).
"""
from __future__ import annotations

import glob
import os

import numpy as np
import pandas as pd

import lib as L
from lib import col, SQ

NAN = float("nan")


class Req:
    def __init__(self):
        self.rows = []

    def add(self, var, di, s, side, x=-1, liq=False, lo=None, hi=None, idx_exit=False):
        lo = s if lo is None else lo
        hi = s if hi is None else hi
        self.rows.append((var, var.split("|")[0], di, int(s), int(side), int(x), NAN, NAN, bool(liq), int(lo), int(hi),
                          bool(idx_exit)))

    def frame(self):
        return pd.DataFrame(self.rows, columns=["var", "idea", "di", "s", "side", "x", "sp", "tp", "liq", "lo", "hi",
                                                "idx_exit"])


def first_touch(arr_bool, start, stop):
    """first index j in [start, stop) with arr_bool[j], else -1."""
    if start >= stop:
        return -1
    seg = arr_bool[start:stop]
    j = np.argmax(seg)
    return start + j if seg[j] else -1


def add_exits(R, base, di, s, side, nat_x, lo=None, hi=None, names=("native", "EOD", "LIQ"), eod_x=-1):
    """native (index rule, column of exit or -1 -> EOD), EOD (optionally with an index stop: eod_x), LIQ."""
    for nm in names:
        if nm == "native":
            R.add(f"{base}|native", di, s, side, x=nat_x, lo=lo, hi=hi, idx_exit=nat_x >= 0)
        elif nm == "EOD":
            R.add(f"{base}|EOD", di, s, side, x=eod_x, lo=lo, hi=hi, idx_exit=eod_x >= 0)
        elif nm == "LIQ":
            R.add(f"{base}|LIQ", di, s, side, liq=True, lo=lo, hi=hi)


def index_exit(P, di, s, stop=None, tgt=None, side=0, until=SQ):
    """first bar j > s where the index low (CE) / high (PE) touches stop, or high/low touches tgt -> exit at j+1."""
    h, lw = P["h"][di], P["l"][di]
    rng = np.arange(s + 1, until)
    if len(rng) == 0:
        return -1
    hit = np.zeros(len(rng), bool)
    if side == 0:
        if stop is not None:
            hit |= np.nan_to_num(lw[rng], nan=1e18) <= stop
        if tgt is not None:
            hit |= np.nan_to_num(h[rng], nan=-1) >= tgt
    else:
        if stop is not None:
            hit |= np.nan_to_num(h[rng], nan=-1) >= stop
        if tgt is not None:
            hit |= np.nan_to_num(lw[rng], nan=1e18) <= tgt
    if not hit.any():
        return -1
    return int(rng[np.argmax(hit)] + 1)


# ------------------------------------------------------------------------------------------------ ideas
def n1_noise(P, R, days_ok):
    c = P["c"]
    dop = P["o"][:, 0]
    rel = np.abs(c / dop[:, None] - 1)
    rel[~P["ok"]] = np.nan
    sig = np.full_like(rel, np.nan)
    okrows = np.nonzero(P["ok"])[0]
    for k, di in enumerate(okrows):
        if k >= 14:
            sig[di] = np.nanmean(rel[okrows[k - 14:k]], 0)
    twap = np.cumsum(np.nan_to_num(c), 1) / np.arange(1, c.shape[1] + 1)
    checks = [col(10, 0) - 1 + 30 * i for i in range(10)]          # 10:00 .. 14:30
    for di in days_ok:
        if not np.isfinite(sig[di, 44]) or not np.isfinite(P["pclose"][di]):
            continue
        o0, pc = dop[di], P["pclose"][di]
        for m in (1.0, 1.5):
            up = max(o0, pc) * (1 + m * sig[di])
            dn = min(o0, pc) * (1 - m * sig[di])
            for s in checks:
                side = 0 if c[di, s] > up[s] else (1 if c[di, s] < dn[s] else -1)
                if side < 0:
                    continue
                base = f"N1_NOISE|m{m}"
                for tr in ("trail30", "trail1"):
                    js = [j for j in (range(s + 30, 345, 30) if tr == "trail30" else range(s + 1, SQ - 1))]
                    x = -1
                    for j in js:
                        lvl_up = max(up[j], twap[di, j])
                        lvl_dn = min(dn[j], twap[di, j])
                        if (side == 0 and c[di, j] < lvl_up) or (side == 1 and c[di, j] > lvl_dn):
                            x = j + 1
                            break
                    R.add(f"{base}|{tr}", di, s, side, x=x, lo=44, hi=314, idx_exit=x >= 0)
                R.add(f"{base}|EOD", di, s, side, lo=44, hi=314)
                R.add(f"{base}|LIQ", di, s, side, liq=True, lo=44, hi=314)
                break


def n2_wvb(P, R, days_ok):
    c = P["c"]
    for di in days_ok:
        rg = P["phigh"][di] - P["plow"][di]
        o0 = P["o"][di, 0]
        if not np.isfinite(rg) or not np.isfinite(o0):
            continue
        for k in (0.25, 0.5, 0.75):
            up, dn = o0 + k * rg, o0 - k * rg
            seg = c[di, 0:315]
            ju = first_touch(seg > up, 0, 315)
            jd = first_touch(seg < dn, 0, 315)
            cand = [(j, sd) for j, sd in ((ju, 0), (jd, 1)) if j >= 0]
            if not cand:
                continue
            s, side = min(cand)
            nat = index_exit(P, di, s, stop=o0, side=side)
            add_exits(R, f"N2_WVB|k{k}", di, s, side, nat, lo=0, hi=314)


def n3_hks(P, R, days_ok):
    o, c = P["o"], P["c"]
    nslot = 12
    ret = np.full((P["nd"], nslot), np.nan)
    for k in range(nslot):
        a, b = 30 * k, min(30 * k + 29, 374)
        ret[:, k] = c[:, b] / o[:, a] - 1
    ret[~P["ok"]] = np.nan
    okrows = np.nonzero(P["ok"])[0]
    pos = {di: i for i, di in enumerate(okrows)}
    for di in days_ok:
        i = pos.get(di)
        if i is None or i < 40:
            continue
        for Lb in (20, 40):
            hist = ret[okrows[i - Lb:i]]
            mu = np.nanmean(hist, 0)
            sd = np.nanstd(hist, 0, ddof=1)
            t = mu / (sd / np.sqrt(Lb))
            for thr in (0.0, 1.0):
                for k in range(nslot):
                    if not np.isfinite(t[k]) or abs(t[k]) <= thr:
                        continue
                    s = max(30 * k - 1, 0)
                    x = min(30 * k + 30, SQ)
                    if s >= 314:
                        continue
                    R.add(f"N3_HKS|L{Lb}|t{thr}", di, s, 0 if t[k] > 0 else 1, x=x, lo=0, hi=313, idx_exit=True)


def n4_rod(P, R, days_ok):
    c = P["c"]
    for di in days_ok:
        for T, s in (("1430", col(14, 30) - 1), ("1440", col(14, 40) - 1)):
            for base_nm, b in (("pc", P["pclose"][di]), ("op", P["o"][di, 0])):
                if not np.isfinite(b):
                    continue
                r = c[di, s] / b - 1
                for thr in (0.0, 0.005):
                    if abs(r) > thr:
                        R.add(f"N4_ROD|{T}|{base_nm}|thr{thr}", di, s, 0 if r > 0 else 1)
                if T == "1440" and base_nm == "pc" and P["exp"][di] and r != 0:
                    R.add("N4_ROD|1440|pc|expiry", di, s, 0 if r > 0 else 1)


def n5_noon(P, R, days_ok):
    for di in days_ok:
        for T, s in (("1200", col(12, 0) - 1), ("1300", col(13, 0) - 1)):
            R.add(f"N5_NOON|{T}|CE", di, s, 0, lo=0, hi=314)
            R.add(f"N5_NOON|{T}|PE", di, s, 1, lo=0, hi=314)
            R.add(f"N5_NOON|{T}|STRAD", di, s, 0, lo=0, hi=314)
            R.add(f"N5_NOON|{T}|STRAD", di, s, 1, lo=0, hi=314)


def n6_fvg(P, R, days_ok, B5):
    O5, H5, L5, C5 = B5
    for di in days_ok:
        for T0 in ("0930", "1000"):
            t0 = col(9, 30) - 1 if T0 == "0930" else col(10, 0) - 1
            for g in (0.0, 0.0005):
                found = None
                for k in range(2, 59):
                    if 5 * k + 4 < t0:
                        continue
                    px = C5[di, k]
                    if not np.isfinite(px):
                        continue
                    if L5[di, k] > H5[di, k - 2] and (L5[di, k] - H5[di, k - 2]) / px >= g:
                        found = (k, 0, L5[di, k], L5[di, k - 2])          # side, gap edge, stop (bar-1 low)
                    elif H5[di, k] < L5[di, k - 2] and (L5[di, k - 2] - H5[di, k]) / px >= g:
                        found = (k, 1, H5[di, k], H5[di, k - 2])
                    if found:
                        break
                if not found:
                    continue
                k, side, edge, stop = found
                a = 5 * k + 5
                if side == 0:
                    j = first_touch(np.nan_to_num(P["l"][di], nan=1e18) <= edge, a, 315)
                else:
                    j = first_touch(np.nan_to_num(P["h"][di], nan=-1) >= edge, a, 315)
                if j < 0:
                    continue
                ent = P["c"][di, j]
                Rr = (ent - stop) if side == 0 else (stop - ent)
                if not np.isfinite(Rr) or Rr <= 0:
                    continue
                tgt = ent + 2 * Rr if side == 0 else ent - 2 * Rr
                nat = index_exit(P, di, j, stop=stop, tgt=tgt, side=side)
                eodx = index_exit(P, di, j, stop=stop, side=side)
                add_exits(R, f"N6_FVG|{T0}|g{g}", di, j, side, nat, lo=15, hi=314, eod_x=eodx)


def n7_turtle(P, R, days_ok):
    h, lw, c = P["h"], P["l"], P["c"]
    for di in days_ok:
        pl, ph = P["plow"][di], P["phigh"][di]
        if not np.isfinite(pl):
            continue
        for B in (5, 15):
            below = above = False
            sig = None
            for k in range(0, 315 // B):
                a, b = B * k, B * k + B - 1
                lo_k, hi_k, cl_k = np.nanmin(lw[di, a:b + 1]), np.nanmax(h[di, a:b + 1]), c[di, b]
                if lo_k < pl:
                    below = True
                if hi_k > ph:
                    above = True
                if b < col(9, 30) - 1:
                    continue
                if below and cl_k > pl:
                    sig = (b, 0, np.nanmin(lw[di, :b + 1]))
                elif above and cl_k < ph:
                    sig = (b, 1, np.nanmax(h[di, :b + 1]))
                if sig:
                    break
            if not sig:
                continue
            s, side, stop = sig
            ent = c[di, s]
            Rr = (ent - stop) if side == 0 else (stop - ent)
            if not np.isfinite(Rr) or Rr <= 0:
                continue
            tgt = ent + 2 * Rr if side == 0 else ent - 2 * Rr
            nat = index_exit(P, di, s, stop=stop, tgt=tgt, side=side)
            eodx = index_exit(P, di, s, stop=stop, side=side)
            add_exits(R, f"N7_TURTLE|B{B}", di, s, side, nat, lo=14, hi=314, eod_x=eodx)


def n8_ibs(P, R, days_ok):
    for di in days_ok:
        H, Lw, Cc = P["phigh"][di], P["plow"][di], P["pclose"][di]
        if not np.isfinite(H) or H <= Lw:
            continue
        ibs = (Cc - Lw) / (H - Lw)
        for lo_, hi_ in ((0.2, 0.8), (0.1, 0.9)):
            side = 0 if ibs < lo_ else (1 if ibs > hi_ else -1)
            if side < 0:
                continue
            base = f"N8_IBS|{lo_}"
            R.add(f"{base}|EOD", di, 0, side)
            R.add(f"{base}|60m", di, 0, side, x=61)
            R.add(f"{base}|LIQ", di, 0, side, liq=True)


def n9_vixspk(P, R, days_ok, vclose):
    s_v = pd.Series(vclose)
    p90 = s_v.rolling(250, min_periods=200).quantile(0.9).values
    for di in days_ok:
        if di < 2:
            continue
        v1, v2 = vclose[di - 1], vclose[di - 2]
        if not (np.isfinite(v1) and np.isfinite(v2)):
            continue
        trig = []
        if v1 / v2 - 1 > 0.10:
            trig.append("chg10")
        if v1 / v2 - 1 > 0.15:
            trig.append("chg15")
        if np.isfinite(p90[di - 1]) and v1 > p90[di - 1]:
            trig.append("p90")
        for tg in trig:
            for T, s in (("0916", 0), ("0945", col(9, 45) - 1)):
                R.add(f"N9_VIXSPK|{tg}|{T}|EOD", di, s, 0)
                R.add(f"N9_VIXSPK|{tg}|{T}|LIQ", di, s, 0, liq=True)


def n10_midday(P, R, days_ok, B5):
    O5, H5, L5, C5 = B5
    for di in days_ok:
        for B0, a in (("1130", col(11, 30)), ("1200", col(12, 0))):
            bh = np.nanmax(P["h"][di, a:col(13, 30)])
            bl = np.nanmin(P["l"][di, a:col(13, 30)])
            if not (np.isfinite(bh) and bh > bl):
                continue
            sig = None
            for k in range(col(13, 30) // 5, col(14, 45) // 5):
                if C5[di, k] > bh:
                    sig = (5 * k + 4, 0)
                elif C5[di, k] < bl:
                    sig = (5 * k + 4, 1)
                if sig:
                    break
            if not sig:
                continue
            s, side = sig
            w = bh - bl
            stop, tgt = (bl, P["c"][di, s] + w) if side == 0 else (bh, P["c"][di, s] - w)
            nat = index_exit(P, di, s, stop=stop, tgt=tgt, side=side)
            add_exits(R, f"N10_MIDDAY|{B0}", di, s, side, nat, lo=col(13, 30) - 1, hi=col(14, 45) - 1)


def n11_first2(P, R, days_ok, B5):
    O5, H5, L5, C5 = B5
    for di in days_ok:
        g0, g1 = C5[di, 0] - O5[di, 0], C5[di, 1] - O5[di, 1]
        if not (np.isfinite(g0) and np.isfinite(g1)):
            continue
        hgt = H5[di, 1] - L5[di, 1]
        if g0 > 0 and g1 > 0:
            j = first_touch(np.nan_to_num(P["h"][di], nan=-1) > H5[di, 1], 10, col(11, 0))
            side, stop, tgt = 0, L5[di, 1], H5[di, 1] + 2 * hgt
        elif g0 < 0 and g1 < 0:
            j = first_touch(np.nan_to_num(P["l"][di], nan=1e18) < L5[di, 1], 10, col(11, 0))
            side, stop, tgt = 1, H5[di, 1], L5[di, 1] - 2 * hgt
        else:
            continue
        if j < 0:
            continue
        nat = index_exit(P, di, j, stop=stop, tgt=tgt, side=side)
        eodx = index_exit(P, di, j, stop=stop, side=side)
        add_exits(R, "N11_FIRST2", di, j, side, nat, lo=10, hi=col(11, 0) - 1, eod_x=eodx)


def open_volume(P, days_ok):
    """near-ATM (+-3 strikes) CE+PE option volume in 09:15-09:19, per day (one chain pass)."""
    import lib as LL
    from obuy import data as D
    from obuy import config as C
    u = P["u"]
    op = D.market().options(u)
    step = C.STEP[u]
    out = np.full(P["nd"], np.nan)
    cur = None
    for di in days_ok:
        d = P["days"][di]
        if d.year != cur:
            D.market().release(u)
            cur = d.year
        ch = op.chain(d, "near")
        if ch is None:
            continue
        atm = round(P["c"][di, 4] / step) * step
        near = np.abs(ch.K - atm) <= 3 * step
        v = np.nansum(ch.v["C"][near, :5]) + np.nansum(ch.v["P"][near, :5])
        out[di] = v
    return out


def n12_rvol_orb(P, R, days_ok, B5, ovol):
    O5, H5, L5, C5 = B5
    okrows = [di for di in range(P["nd"]) if np.isfinite(ovol[di])]
    pos = {di: i for i, di in enumerate(okrows)}
    for di in days_ok:
        b = C5[di, 0] - O5[di, 0]
        if not np.isfinite(b) or b == 0:
            continue
        i = pos.get(di)
        rv = np.nan
        if i is not None and i >= 14:
            mu = np.nanmean(ovol[okrows[i - 14:i]])
            rv = ovol[di] / mu if mu > 0 else np.nan
        side = 0 if b > 0 else 1
        stop = L5[di, 0] if side == 0 else H5[di, 0]
        nat = index_exit(P, di, 4, stop=stop, side=side)
        for f in (0.0, 1.0, 1.5):
            if f > 0 and not (np.isfinite(rv) and rv >= f):
                continue
            R.add(f"N12_RVORB|rv{f}|native", di, 4, side, x=nat, idx_exit=nat >= 0)
            R.add(f"N12_RVORB|rv{f}|LIQ", di, 4, side, liq=True)


def n13_vixdiv(P, R, days_ok, vmin):
    for di in days_ok:
        v = vmin[di]
        fv = v[np.isfinite(v)]
        if len(fv) < 200:
            continue
        v0 = fv[0]
        for a, b in ((0.003, 0.03), (0.002, 0.02)):
            sig = None
            for s in (col(10, 30) - 1, col(11, 30) - 1, col(12, 30) - 1, col(13, 30) - 1):
                ri = P["c"][di, s] / P["o"][di, 0] - 1
                rv = v[s] / v0 - 1
                if ri > a and rv > b:
                    sig = (s, 1)
                elif ri < -a and rv < -b:
                    sig = (s, 0)
                if sig:
                    break
            if not sig:
                continue
            s, side = sig
            base = f"N13_VIXDIV|a{a}"
            R.add(f"{base}|60m", di, s, side, x=s + 61, lo=col(10, 30) - 1, hi=col(13, 30) - 1, idx_exit=True)
            R.add(f"{base}|EOD", di, s, side, lo=col(10, 30) - 1, hi=col(13, 30) - 1)
            R.add(f"{base}|LIQ", di, s, side, liq=True, lo=col(10, 30) - 1, hi=col(13, 30) - 1)


def n14_lunchrev(P, R, days_ok):
    s = col(12, 0) - 1
    for di in days_ok:
        r = P["c"][di, s] / P["o"][di, 0] - 1
        for thr in (0.003, 0.006):
            side = 1 if r > thr else (0 if r < -thr else -1)
            if side < 0:
                continue
            base = f"N14_LUNCHREV|{thr}"
            R.add(f"{base}|1330", di, s, side, x=col(13, 30))
            R.add(f"{base}|EOD", di, s, side)
            R.add(f"{base}|LIQ", di, s, side, liq=True)


NIFTY50 = ("ADANIENT ADANIPORTS APOLLOHOSP ASIANPAINT AXISBANK BAJAJ-AUTO BAJFINANCE BAJAJFINSV BEL BHARTIARTL CIPLA "
           "COALINDIA DRREDDY EICHERMOT ETERNAL GRASIM HCLTECH HDFCBANK HDFCLIFE HINDALCO HINDUNILVR ICICIBANK INDIGO "
           "INFY ITC JIOFIN JSWSTEEL KOTAKBANK LT M_M MARUTI MAXHEALTH NESTLEIND NTPC ONGC POWERGRID RELIANCE SBILIFE "
           "SBIN SHRIRAMFIN SUNPHARMA TATACONSUM TMPV TATASTEEL TCS TECHM TITAN TRENT ULTRACEMCO WIPRO").split()


def breadth(P):
    """share of NIFTY-50 stocks above their day open at 10:00 and 11:00 closes -> (nd, 2), NaN when < 40 stocks."""
    from obuy import config as C
    base = os.path.join(C.DATA, "candles", "minute", "NSE_EQ")
    dix = {d: i for i, d in enumerate(P["days"])}
    cnt = np.zeros((P["nd"], 2))
    tot = np.zeros((P["nd"], 2))
    for sym in NIFTY50:
        fs = sorted(glob.glob(os.path.join(base, sym, "*.parquet")))
        if not fs:
            continue
        x = pd.concat([pd.read_parquet(f, columns=["ts", "open", "close"]) for f in fs])
        x["ts"] = x.ts.dt.tz_localize(None)
        x["day"] = x.ts.dt.date
        x["m"] = x.ts.dt.hour * 60 + x.ts.dt.minute
        x = x[(x.m >= 555) & (x.m <= 659)].sort_values("ts")
        for d, g in x.groupby("day"):
            i = dix.get(d)
            if i is None:
                continue
            o0 = g.open.values[0]
            for k, mm in enumerate((599, 659)):          # 09:59 and 10:59 bars (decision at 10:00 / 11:00)
                gg = g[g.m <= mm]
                if len(gg) == 0:
                    continue
                tot[i, k] += 1
                cnt[i, k] += gg.close.values[-1] > o0
    out = np.where(tot >= 40, cnt / np.maximum(tot, 1), np.nan)
    return out


def n15_breadth(P, R, days_ok, br):
    for di in days_ok:
        for k, (T, s) in enumerate((("1000", col(10, 0) - 1), ("1100", col(11, 0) - 1))):
            b = br[di, k]
            if not np.isfinite(b):
                continue
            for hi_ in (0.8, 0.7):
                side = 0 if b > hi_ else (1 if b < 1 - hi_ else -1)
                if side < 0:
                    continue
                R.add(f"N15_BREADTH|{T}|{hi_}|EOD", di, s, side)
                R.add(f"N15_BREADTH|{T}|{hi_}|LIQ", di, s, side, liq=True)


ELIG = {}   # idea -> boolean mask of sessions where its data exists (set in build_all)


def build_all(P, days_ok):
    R = Req()
    B5 = L.bars5(P)
    vmin, vclose = L.vix_minutes(P["days"])
    n1_noise(P, R, days_ok)
    n2_wvb(P, R, days_ok)
    n3_hks(P, R, days_ok)
    n4_rod(P, R, days_ok)
    n5_noon(P, R, days_ok)
    n6_fvg(P, R, days_ok, B5)
    n7_turtle(P, R, days_ok)
    n8_ibs(P, R, days_ok)
    n9_vixspk(P, R, days_ok, vclose)
    n10_midday(P, R, days_ok, B5)
    n11_first2(P, R, days_ok, B5)
    ovol = open_volume(P, days_ok)
    n12_rvol_orb(P, R, days_ok, B5, ovol)
    n13_vixdiv(P, R, days_ok, vmin)
    n14_lunchrev(P, R, days_ok)
    br = breadth(P)
    n15_breadth(P, R, days_ok, br)
    elig = {k: np.ones(P["nd"], bool) for k in ("N1_NOISE N2_WVB N3_HKS N4_ROD N5_NOON N6_FVG N7_TURTLE N8_IBS "
                                                  "N9_VIXSPK N10_MIDDAY N11_FIRST2 N12_RVORB N14_LUNCHREV").split()}
    elig["N13_VIXDIV"] = np.array([np.isfinite(vmin[i]).sum() >= 200 for i in range(P["nd"])])
    elig["N15_BREADTH"] = np.isfinite(br[:, 0])
    return R.frame(), elig
