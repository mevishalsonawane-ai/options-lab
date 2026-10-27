"""R118 atlas builder (PREREG.md): every option on every expiry day, descriptive only.

    python3 research/hunt/r118_expiry_atlas/atlas.py build      # -> $R118_OUT/{marks,days,index}_<UND>.parquet, minutes_<UND>.pkl

Inputs: R98's expiry-day records ($R98_OUT/days_<UND>.pkl, `r98data.py build`) and the lake's index minutes ($R98_LAKE).
Outputs stay on the cache (never in git); `report.py` turns them into the small tables in results/.

Three tables per index:
  - marks: one row per option per 15-minute mark (09:30 .. 15:15). Columns without a prefix are known AT the mark (data up
    to and including that minute's bar); columns starting with "y_" are outcomes AFTER the mark.
  - days: one row per expiry day (state at the open, the straddle, OI walls, max pain, the close).
  - minutes: per day, 375-minute series (straddle, ATM IV, parity, volume and OI by distance bucket).
And `index`: one row per session (expiry or not) from the lake's index minutes, for the expiry-vs-normal comparison.

Grid: row t = the 1-minute bar that STARTS at 09:15 + t (375 rows, to the bar starting 15:29).
"""
from __future__ import annotations

import os
import pickle
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
CACHE = Path(os.environ.get("IRAALGO_CACHE", "~/.cache/iraalgo")).expanduser()
os.environ.setdefault("R98_LAKE", str(CACHE / "lake"))
os.environ.setdefault("R98_OUT", str(CACHE / "r98"))
OUT = Path(os.environ.get("R118_OUT", CACHE / "r118")).expanduser()
sys.path.insert(0, str(HERE.parent / "r98"))
sys.path.insert(0, str(HERE))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402

N = R.N
MARKS = list(range(R.minute(9, 30), R.minute(15, 15) + 1, 15))     # 24 marks
T_OPEN = 0
T_LAST = N - 1                                                     # the bar starting 15:29
END_PRINT_FROM = R.minute(15, 20)                                  # a print from here counts as the option's end price
EM_BUCKETS = [0.0, 0.25, 0.5, 1.0, 1.5, 2.0, 3.0, np.inf]          # OTM distance in straddles (ITM is its own bucket)
EM_LABELS = ["ITM", "0-0.25", "0.25-0.5", "0.5-1", "1-1.5", "1.5-2", "2-3", "3+"]


def em_bucket(d: float) -> int:
    """0 = ITM (d < 0), then the OTM buckets of EM_BUCKETS."""
    if not d == d:
        return -1
    if d < 0:
        return 0
    return 1 + int(np.searchsorted(EM_BUCKETS, d, side="right") - 1)


def em_buckets(d: np.ndarray) -> np.ndarray:
    """Vector form of em_bucket."""
    d = np.asarray(d, float)
    b = np.full(d.shape, -1, dtype=int)
    ok = ~np.isnan(d)
    b[ok & (d < 0)] = 0
    pos = ok & (d >= 0)
    b[pos] = np.searchsorted(EM_BUCKETS, d[pos], side="right")
    return b


def intrinsic(side: str, k: float, s):
    s = np.asarray(s, float)
    return np.maximum(s - k, 0.0) if side == "C" else np.maximum(k - s, 0.0)


def value_path(a: np.ndarray, side: str, k: float, sp: np.ndarray) -> np.ndarray:
    """The option's value per minute: its close where it printed, never below intrinsic; intrinsic where it did not print
    (it was then outside the stored ATM+-10 band: far OTM is worth about 0, deep ITM about intrinsic)."""
    c = a[:, R.C]
    iv = intrinsic(side, k, sp)
    out = np.where(np.isnan(c), iv, np.fmax(c, iv))
    return np.where(np.isnan(sp) & np.isnan(c), np.nan, out)


def end_value(a: np.ndarray, side: str, k: float, sp: np.ndarray) -> float:
    """The option's last price: its last close if it printed from 15:20 on; else intrinsic at the last spot."""
    c = a[END_PRINT_FROM:, R.C]
    ok = ~np.isnan(c)
    if ok.any():
        return float(c[ok][-1])
    s = sp[~np.isnan(sp)]
    return float(intrinsic(side, k, s[-1])) if len(s) else np.nan


def suffix_stats(v: np.ndarray):
    """For each t: max / argmax / min of v[t+1:] (NaN-aware; NaN and -1 when nothing follows)."""
    n = len(v)
    mx, am, mn = np.full(n, np.nan), np.full(n, -1), np.full(n, np.nan)
    cur_mx, cur_am, cur_mn = np.nan, -1, np.nan
    for t in range(n - 1, -1, -1):
        mx[t], am[t], mn[t] = cur_mx, cur_am, cur_mn
        x = v[t]
        if x == x:
            if not cur_mx == cur_mx or x >= cur_mx:
                cur_mx, cur_am = x, t
            if not cur_mn == cur_mn or x < cur_mn:
                cur_mn = x
    return mx, am, mn


def atm_series(day, sp):
    """Per minute: ATM strike, straddle (em), ATM vendor IV (mean of call and put), parity basis (K + C - P - spot)."""
    step = R.STEP[day.und]
    katm, em, aiv, par = (np.full(N, np.nan) for _ in range(4))
    ff = {}

    def g(key, col):
        kk = (key, col)
        if kk not in ff:
            a = day.opts.get(key)
            ff[kk] = L.ffill(a[:, col], 3) if a is not None else None
        return ff[kk]
    for t in range(N):
        s = sp[t]
        if not s == s:
            continue
        k = float(round(s / step) * step)
        katm[t] = k
        c, p = g((k, "C"), R.C), g((k, "P"), R.C)
        if c is None or p is None:
            continue
        em[t] = c[t] + p[t]
        par[t] = k + c[t] - p[t] - s
        ci, pi = g((k, "C"), R.IV), g((k, "P"), R.IV)
        aiv[t] = np.nanmean([ci[t], pi[t]]) if not (np.isnan(ci[t]) and np.isnan(pi[t])) else np.nan
    return katm, em, aiv, par


def oi_at(a: np.ndarray, t: int) -> float:
    x = a[:t + 1, R.OI]
    ok = ~np.isnan(x)
    return float(x[ok][-1]) if ok.any() else np.nan


def walls_and_pain(day, t: int):
    """From the stored chain's OI at minute t (latest print <= t): (call wall strike, put wall strike, max pain strike,
    put/call OI ratio). Max pain = the strike minimising what option buyers would be paid at expiry, over stored strikes."""
    oc, op = {}, {}
    for (k, s), a in day.opts.items():
        o = oi_at(a, t)
        if o == o and o > 0:
            (oc if s == "C" else op)[k] = o
    if not oc or not op:
        return np.nan, np.nan, np.nan, np.nan
    cw = max(oc, key=oc.get)
    pw = max(op, key=op.get)
    ks = sorted(set(oc) | set(op))
    pay = [sum(o * max(x - k, 0) for k, o in oc.items()) + sum(o * max(k - x, 0) for k, o in op.items()) for x in ks]
    mp = ks[int(np.argmin(pay))]
    return float(cw), float(pw), float(mp), sum(op.values()) / sum(oc.values())


def build_day(day, all_days):
    und, step = day.und, R.STEP[day.und]
    sp = L.spot(day)
    katm, em, aiv, par = atm_series(day, sp)
    ok = ~np.isnan(sp)
    if not ok.any():
        return [], None, None
    s_open = float(day.ix[0, R.O]) if day.ix[0, R.O] == day.ix[0, R.O] else float(sp[ok][0])
    s_end = float(sp[ok][-1])
    smx, _, smn = suffix_stats(sp)
    rows = []
    for (k, side), a in day.opts.items():
        c = L.ffill(a[:, R.C], 3)
        vp = value_path(a, side, k, sp)
        mx, am, mn = suffix_stats(vp)
        endv = end_value(a, side, k, sp)
        vol = np.nan_to_num(a[:, R.V])
        cvol = np.cumsum(vol)
        oi0 = np.nan
        for t in MARKS:
            px, s, e = c[t], sp[t], em[t]
            if not (px == px and px > 0 and s == s and e == e and e > 0):
                continue
            if not L.traded(a, t):
                continue
            otm = (k - s) if side == "C" else (s - k)
            if not oi0 == oi0:
                oi0 = oi_at(a, MARKS[0])
            oi = oi_at(a, t)
            ivv = a[t, R.IV] if a[t, R.IV] == a[t, R.IV] else np.nan
            t5 = min(t + 5, T_LAST)
            rows.append(dict(
                und=und, day=day.day, t=t, side=side, strike=k, px=float(px),
                dist_k=otm / step, dist_pct=otm / s * 100, dist_em=otm / e, em_pct=e / s * 100,
                tv=float(px - intrinsic(side, k, s)), iv=ivv, atm_iv=aiv[t],
                iv_ratio=ivv / aiv[t] if (ivv == ivv and aiv[t] == aiv[t] and aiv[t] > 0) else np.nan,
                vol5=float(vol[max(0, t - 4):t + 1].sum()), volcum=float(cvol[t]), oi=oi,
                oi_chg=(oi / oi0 - 1) if (oi == oi and oi0 == oi0 and oi0 > 0) else np.nan,
                idx_move_em=(s - s_open) / e, idx_move_pct=(s - s_open) / s_open * 100,
                # outcomes after the mark
                y_end=endv, y_max=mx[t], y_tmax=int(am[t]), y_min=mn[t],
                y_px5=float(c[t5]) if c[t5] == c[t5] else np.nan, y_s5=float(sp[t5]) if sp[t5] == sp[t5] else np.nan,
                y_s_end=s_end, y_up_em=(smx[t] - s) / e if smx[t] == smx[t] else np.nan,
                y_dn_em=(s - smn[t]) / e if smn[t] == smn[t] else np.nan))
    # the day
    t930, t1200 = R.minute(9, 30), R.minute(12, 0)
    cw9, pw9, mp9, pcr9 = walls_and_pain(day, t930)
    cw12, pw12, mp12, pcr12 = walls_and_pain(day, t1200)
    hi, lo = np.nanmax(day.ix[:, R.H]), np.nanmin(day.ix[:, R.L])
    drow = dict(und=und, day=day.day, monthly=L.is_monthly(day, all_days), vix_prev=day.vix_prev,
                gap_pct=(s_open / day.prev_close - 1) * 100 if day.prev_close == day.prev_close else np.nan,
                s_open=s_open, s_end=s_end, s930=sp[t930], s1200=sp[t1200], s1300=sp[R.minute(13, 0)],
                em930=em[t930], em1200=em[t1200], range_pct=(hi - lo) / s_open * 100,
                cw930=cw9, pw930=pw9, mp930=mp9, pcr930=pcr9, cw1200=cw12, pw1200=pw12, mp1200=mp12, pcr1200=pcr12,
                step=step, n_opts=len(day.opts), n_clean=day.n_clean)
    # minute series
    vol_b = np.zeros((2, len(EM_LABELS), N))          # side x distance bucket x minute: volume (units)
    turn_b = np.zeros((2, len(EM_LABELS), N))         # premium turnover (volume x close)
    oi_side = np.zeros((2, N))
    noprint = np.zeros((len(EM_LABELS), N))
    present = np.zeros((len(EM_LABELS), N))
    for (k, side), a in day.opts.items():
        si = 0 if side == "C" else 1
        otm = ((k - sp) if side == "C" else (sp - k)) / em
        b = em_buckets(otm)
        okb = b >= 0
        v = np.nan_to_num(a[:, R.V])
        cl = np.nan_to_num(a[:, R.C])
        idx = np.nonzero(okb)[0]
        np.add.at(vol_b[si], (b[idx], idx), v[idx])
        np.add.at(turn_b[si], (b[idx], idx), v[idx] * cl[idx])
        oi_side[si] += np.nan_to_num(L.ffill(a[:, R.OI], 30))
        inb = idx[~np.isnan(L.ffill(a[:, R.C], 30)[idx])]     # in the stored chain around then
        np.add.at(present, (b[inb], inb), 1)
        nop = inb[np.isnan(a[inb, R.C])]
        np.add.at(noprint, (b[nop], nop), 1)
    mins = dict(und=und, day=day.day, em_pct=em / sp * 100, atm_iv=aiv, parity_bps=par / sp * 1e4, spot=sp,
                vol_b=vol_b.astype(np.float32), turn_b=turn_b.astype(np.float32), oi_side=oi_side.astype(np.float32),
                noprint=noprint.astype(np.int16), present=present.astype(np.int16))
    return rows, drow, mins


def index_sessions(und: str, expiries: set) -> pd.DataFrame:
    """One row per session of the index: range, times of the day's high and low, last-hour move, trend share."""
    ix = R._index_frames(und)
    out = []
    for d, g in ix.groupby("d"):
        a = R._grid(g, ["open", "high", "low", "close"])
        c = L.ffill(a[:, 3], 5)
        if np.isnan(a[:, 1]).all() or np.isnan(c).sum() > 60:
            continue
        o = a[0, 0] if a[0, 0] == a[0, 0] else c[~np.isnan(c)][0]
        hi, lo = np.nanmax(a[:, 1]), np.nanmin(a[:, 2])
        okc = c[~np.isnan(c)]
        close = okc[-1]
        c1430 = c[R.minute(14, 30)]
        c1500 = c[R.minute(15, 0)]
        out.append(dict(und=und, day=d, expiry=d in expiries, range_pct=(hi - lo) / o * 100,
                        t_high=int(np.nanargmax(a[:, 1])), t_low=int(np.nanargmin(a[:, 2])),
                        oc_pct=(close - o) / o * 100, trend=abs(close - o) / (hi - lo) if hi > lo else np.nan,
                        last60_pct=abs(close - c1430) / o * 100 if c1430 == c1430 else np.nan,
                        last30_pct=abs(close - c1500) / o * 100 if c1500 == c1500 else np.nan,
                        first60_range_pct=(np.nanmax(a[:60, 1]) - np.nanmin(a[:60, 2])) / o * 100))
    return pd.DataFrame(out)


def build_und(und: str):
    days = R.load(und)
    rows, drows, mins = [], [], []
    for d in days:
        r, dr, m = build_day(d, days)
        rows += r
        if dr is not None:
            drows.append(dr)
            mins.append(m)
    OUT.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_parquet(OUT / f"marks_{und}.parquet")
    pd.DataFrame(drows).to_parquet(OUT / f"days_{und}.parquet")
    (OUT / f"minutes_{und}.pkl").write_bytes(pickle.dumps(mins))
    index_sessions(und, {d.day for d in days}).to_parquet(OUT / f"index_{und}.parquet")
    print(und, "days", len(drows), "option-marks", len(rows), flush=True)


if __name__ == "__main__":
    if sys.argv[1:2] == ["build"]:
        with Pool(int(os.environ.get("R118_PROCS", "3"))) as p:
            p.map(build_und, sys.argv[2:] or R.UNDS)
    else:
        print(__doc__)
