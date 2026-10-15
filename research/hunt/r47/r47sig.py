"""R47 signal families (r47cfg.FAMILIES): events (session index, decision minute, direction[, contract]) from data
up to the decision minute only.

A decision on minute m's close fills at minute m+1 (r47price). "At hh:mm" means the close of the minute before hh:mm.
5-minute bar b covers minutes 5b .. 5b+4 and is decided on minute 5b+4's close.
Direction: +1 calls / bull spread, -1 puts / bear spread, 0 straddle / strangle.

Signal source of each family: r47cfg.FAMILIES[...]["label"] (U underlying, C option chain, O option's own chart -
NON-DEFAULT, E calendar / event timing, M model).
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

import r47cfg as K

mi = K.mi
POS_M = K.mi(15, 20) - 1              # positional decisions: the 15:19 minute's close (fill 15:20)

# RBI policy and budget days before 2024 (research/brain/calendar_data/events.csv has 2024 on). Dates from the RBI's
# MPC press releases and indiabudget.gov.in; 2022-05-04 was an unscheduled RBI decision (announced at 14:00).
EXTRA_EVENTS = (
    "2021-08-06", "2021-10-08", "2021-12-08", "2022-02-10", "2022-04-08", "2022-05-04", "2022-06-08", "2022-08-05",
    "2022-09-30", "2022-12-07", "2023-02-08", "2023-04-06", "2023-06-08", "2023-08-10", "2023-10-06", "2023-12-08",
    "2022-02-01", "2023-02-01",
)


@dataclass
class Events:
    di: np.ndarray          # int64 session index
    m: np.ndarray           # int64 decision minute
    d: np.ndarray           # int8 direction
    cid: np.ndarray         # int64 contract (O families: the contract whose chart signalled; else -1)
    win: tuple = (0, K.N - 1)     # decision-minute window (random twins draw from it)
    days: str = "any"             # 'any' | 'expiry' | 'event' (random twins draw from these sessions)

    def __len__(self):
        return len(self.di)


def _ev(rows, win, days="any"):
    if not rows:
        z = np.zeros(0, dtype=np.int64)
        return Events(z, z.copy(), np.zeros(0, dtype=np.int8), z.copy(), win, days)
    a = np.array(rows, dtype=np.int64).reshape(-1, 4)
    o = np.lexsort((a[:, 1], a[:, 0]))
    a = a[o]
    return Events(a[:, 0], a[:, 1], a[:, 2].astype(np.int8), a[:, 3], win, days)


# ------------------------------------------------------------------------------------------------ helpers
def bars5(o, h, l, c):
    """75 five-minute bars (o, h, l, c) of one session's minutes (NaN bars where no minute)."""
    O = o.reshape(75, 5)
    H = h.reshape(75, 5)
    L = l.reshape(75, 5)
    C = c.reshape(75, 5)
    bo = np.full(75, np.nan)
    bc = np.full(75, np.nan)
    with np.errstate(all="ignore"):
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            bh = np.nanmax(H, axis=1)
            bl = np.nanmin(L, axis=1)
    for b in range(75):
        ok = np.flatnonzero(np.isfinite(C[b]))
        if len(ok):
            bo[b] = O[b, ok[0]]
            bc[b] = C[b, ok[-1]]
    return bo, bh, bl, bc


def twap(h, l, c):
    """Running mean of the minutes' typical price (H+L+C)/3 (the index has no volume, so this stands in for VWAP)."""
    tp = (h + l + c) / 3.0
    ok = np.isfinite(tp)
    cs = np.cumsum(np.where(ok, tp, 0.0))
    n = np.cumsum(ok)
    with np.errstate(all="ignore"):
        return np.where(n > 0, cs / np.maximum(n, 1), np.nan)


def ema(x, n):
    a = 2.0 / (n + 1.0)
    out = np.full(len(x), np.nan)
    prev = np.nan
    for i, v in enumerate(x):
        if not np.isfinite(v):
            out[i] = prev
            continue
        prev = v if not np.isfinite(prev) else a * v + (1 - a) * prev
        out[i] = prev
    return out


def prev_rank(x: np.ndarray, look: int = 60, min_n: int = 40) -> np.ndarray:
    """For each session i: share of the previous `look` sessions' finite values below x[i] (NaN if < min_n)."""
    out = np.full(len(x), np.nan)
    for i in range(len(x)):
        if not np.isfinite(x[i]):
            continue
        w = x[max(0, i - look):i]
        w = w[np.isfinite(w)]
        if len(w) >= min_n:
            out[i] = float(np.mean(w < x[i]))
    return out


def sessions_from(u, first=None):
    """Session indices tradable in the study (from FIRST_DAY; the earlier ones are warm-up)."""
    first = K.FIRST_DAY if first is None else first
    return range(int(np.searchsorted(u.days, first.toordinal())), len(u.days))


def last_close_before(arr, m):
    """Last finite value at index <= m."""
    ok = np.flatnonzero(np.isfinite(arr[:m + 1]))
    return arr[ok[-1]] if len(ok) else np.nan


# ------------------------------------------------------------------------------------------------ U families
def u1_orb(u, or_min):
    rows = []
    nb = or_min // 5
    last_b = (mi(13, 30) - 4) // 5
    for di in sessions_from(u):
        bo, bh, bl, bc = bars5(u.O[di], u.H[di], u.L[di], u.C[di])
        if not np.isfinite(bc[nb - 1]):
            continue
        hi, lo = np.nanmax(bh[:nb]), np.nanmin(bl[:nb])
        up = dn = False
        for b in range(nb, last_b + 1):
            if not np.isfinite(bc[b]):
                continue
            if not up and bc[b] > hi:
                rows.append((di, 5 * b + 4, 1, -1))
                up = True
            if not dn and bc[b] < lo:
                rows.append((di, 5 * b + 4, -1, -1))
                dn = True
    return _ev(rows, (or_min + 4, mi(13, 30)))


def u2_gap(u, th, how):
    rows = []
    for di in sessions_from(u):
        pc, op = u.dclose[di - 1], u.O[di, 0]
        if not (np.isfinite(pc) and np.isfinite(op) and np.isfinite(u.C[di, 4])):
            continue
        gp = (op / pc - 1) * 100
        if abs(gp) > th:
            s = int(np.sign(gp)) * (1 if how == "follow" else -1)
            rows.append((di, 4, s, -1))
    return _ev(rows, (4, 4))


def u3_lateday(u, th, how):
    rows = []
    for di in sessions_from(u):
        op, c = u.O[di, 0], last_close_before(u.C[di], POS_M)
        if not (np.isfinite(op) and np.isfinite(c)):
            continue
        mv = (c / op - 1) * 100
        if abs(mv) > th:
            rows.append((di, POS_M, int(np.sign(mv)) * (1 if how == "follow" else -1), -1))
    return _ev(rows, (POS_M, POS_M))


def u4_donchian(u, n):
    rows = []
    for di in sessions_from(u):
        if di < n:
            continue
        c = last_close_before(u.C[di], POS_M)
        w = u.dclose[di - n:di]
        if not np.isfinite(c) or not np.isfinite(w).all():
            continue
        if c > w.max():
            rows.append((di, POS_M, 1, -1))
        elif c < w.min():
            rows.append((di, POS_M, -1, -1))
    return _ev(rows, (POS_M, POS_M))


def u5_daily_mr(u, kind):
    rows = []
    for di in sessions_from(u):
        c = last_close_before(u.C[di], POS_M)
        p1, p2, p3 = u.dclose[di - 1], u.dclose[di - 2], u.dclose[di - 3]
        if not np.isfinite([c, p1, p2, p3]).all():
            continue
        if kind == "rsi2":       # Cutler RSI over the last 2 changes (today's to 15:19)
            ch = np.array([c - p1, p1 - p2])
            g, lo = ch[ch > 0].sum(), -ch[ch < 0].sum()
            if g + lo <= 0:
                continue
            r = 100 * g / (g + lo)
            s = 1 if r < 10 else -1 if r > 90 else 0
        else:
            s = 1 if (c < p1 < p2 < p3) else -1 if (c > p1 > p2 > p3) else 0
        if s:
            rows.append((di, POS_M, s, -1))
    return _ev(rows, (POS_M, POS_M))


def u6_ema(u, f, s, vwap):
    rows = []
    b0, b1 = (mi(9, 45) - 4) // 5, (mi(14, 30) - 4) // 5
    for di in sessions_from(u):
        bo, bh, bl, bc = bars5(u.O[di], u.H[di], u.L[di], u.C[di])
        ef, es = ema(bc, f), ema(bc, s)
        tw = twap(u.H[di], u.L[di], u.C[di])
        for b in range(max(b0, 1), b1 + 1):
            if not np.isfinite([ef[b], es[b], ef[b - 1], es[b - 1], bc[b]]).all():
                continue
            up = ef[b] > es[b] and ef[b - 1] <= es[b - 1]
            dn = ef[b] < es[b] and ef[b - 1] >= es[b - 1]
            v = tw[5 * b + 4]
            if up and (not vwap or bc[b] > v):
                rows.append((di, 5 * b + 4, 1, -1))
            elif dn and (not vwap or bc[b] < v):
                rows.append((di, 5 * b + 4, -1, -1))
    return _ev(rows, (mi(9, 45), mi(14, 30)))


def u7_bigbar(u, k, how):
    rows = []
    b0, b1 = (mi(9, 35) - 4) // 5, (mi(14, 30) - 4) // 5
    prev_rng = []
    for di in sessions_from(u, date(2021, 7, 1)):
        bo, bh, bl, bc = bars5(u.O[di], u.H[di], u.L[di], u.C[di])
        rng = bh - bl
        for b in range(75):
            if not np.isfinite(rng[b]):
                continue
            hist = prev_rng[-20:]
            if b0 <= b <= b1 and len(hist) == 20 and di >= sessions_from(u).start:
                med = float(np.median(hist))
                if med > 0 and rng[b] > k * med:
                    pos = (bc[b] - bl[b]) / rng[b]
                    s = 1 if pos >= 0.75 else -1 if pos <= 0.25 else 0
                    if s:
                        rows.append((di, 5 * b + 4, s * (1 if how == "follow" else -1), -1))
            prev_rng.append(float(rng[b]))
    return _ev(rows, (mi(9, 35), mi(14, 30)))


def u8_pdhl(u, how):
    rows = []
    b0, b1 = (mi(9, 20) - 4) // 5, (mi(14, 30) - 4) // 5
    for di in sessions_from(u):
        ph, pl = np.nanmax(u.H[di - 1]) if np.isfinite(u.H[di - 1]).any() else np.nan, \
            np.nanmin(u.L[di - 1]) if np.isfinite(u.L[di - 1]).any() else np.nan
        if not (np.isfinite(ph) and np.isfinite(pl)):
            continue
        bo, bh, bl, bc = bars5(u.O[di], u.H[di], u.L[di], u.C[di])
        up = dn = False
        for b in range(b0, b1 + 1):
            if not np.isfinite(bc[b]):
                continue
            if how == "break":
                if not up and bc[b] > ph:
                    rows.append((di, 5 * b + 4, 1, -1))
                    up = True
                if not dn and bc[b] < pl:
                    rows.append((di, 5 * b + 4, -1, -1))
                    dn = True
            else:
                if not up and bh[b] > ph and bc[b] < ph:
                    rows.append((di, 5 * b + 4, -1, -1))
                    up = True
                if not dn and bl[b] < pl and bc[b] > pl:
                    rows.append((di, 5 * b + 4, 1, -1))
                    dn = True
    return _ev(rows, (mi(9, 20), mi(14, 30)))


def u9_vix(u, th):
    rows = []
    for di in sessions_from(u):
        v, pv = last_close_before(u.vixC[di], POS_M), u.vix_dclose[di - 1]
        if np.isfinite(v) and np.isfinite(pv) and pv > 0 and v / pv - 1 > th:
            rows.append((di, POS_M, 1, -1))
    return _ev(rows, (POS_M, POS_M))


def u10_trendday(u, th, at):
    rows = []
    m = mi(*at) - 1
    for di in sessions_from(u):
        op, c = u.O[di, 0], u.C[di, m]
        tw = twap(u.H[di], u.L[di], u.C[di])[m]
        if not np.isfinite([op, c, tw]).all():
            continue
        mv = (c / op - 1) * 100
        if mv > th and c > tw:
            rows.append((di, m, 1, -1))
        elif mv < -th and c < tw:
            rows.append((di, m, -1, -1))
    return _ev(rows, (m, m))


# ------------------------------------------------------------------------------------------------ C families
def chain_series(u, m, series="WEEK"):
    st = u.stores[series]
    with np.errstate(all="ignore"):
        pcr = st.oi_p[:, m] / st.oi_c[:, m]
    return pcr


def c1_pcr(u, at, how):
    m = mi(*at) - 1
    rk = prev_rank(chain_series(u, m))
    rows = []
    for di in sessions_from(u):
        r = rk[di]
        if not np.isfinite(r):
            continue
        s = 1 if r >= 0.8 else -1 if r <= 0.2 else 0
        if s:
            rows.append((di, m, s * (1 if how == "follow" else -1), -1))
    return _ev(rows, (m, m))


def c2_ivcheap(u, pct, when):
    m = POS_M if when == "pos" else mi(9, 30) - 1
    rk = prev_rank(u.stores["WEEK"].iv_atm[:, m].astype(np.float64))
    rows = [(di, m, 0, -1) for di in sessions_from(u) if np.isfinite(rk[di]) and rk[di] < pct]
    return _ev(rows, (m, m))


def c3_oishift(u, how):
    st = u.stores["WEEK"]
    m0, m1 = mi(9, 20) - 1, mi(11, 0) - 1
    with np.errstate(all="ignore"):
        x = ((st.oi_p[:, m1] - st.oi_p[:, m0]) - (st.oi_c[:, m1] - st.oi_c[:, m0])) / (st.oi_p[:, m0] + st.oi_c[:, m0])
    rk = prev_rank(x)
    rows = []
    for di in sessions_from(u):
        r = rk[di]
        if not np.isfinite(r):
            continue
        s = 1 if r >= 0.8 else -1 if r <= 0.2 else 0
        if s:
            rows.append((di, m1, s * (1 if how == "follow" else -1), -1))
    return _ev(rows, (m1, m1))


# ------------------------------------------------------------------------------------------------ O families (NON-DEFAULT)
def contract_minutes(st, cid, di):
    """One contract's minute O H L C on session di (NaN where no row)."""
    s, e = st.cstart[cid], st.cend[cid]
    g = st.g[s:e]
    a, b = np.searchsorted(g, di * K.N), np.searchsorted(g, (di + 1) * K.N)
    out = [np.full(K.N, np.nan) for _ in range(4)]
    mm = (g[a:b] - di * K.N).astype(np.int64)
    for arr, src in zip(out, (st.o, st.h, st.l, st.c)):
        arr[mm] = src[s + a:s + b]
    return out


def open_atm(st, di, side):
    """The contract that was ATM (offset 0) at 09:15 (or the first minute with one, up to 09:17), side 0 C / 1 P."""
    for m in range(3):
        cc = st.offcid[di, m, side, 10]
        if cc >= 0:
            return int(cc)
    return -1


def o1_prem_orb(u, or_min):
    st = u.stores["WEEK"]
    rows = []
    nb = or_min // 5
    last_b = (mi(13, 30) - 4) // 5
    for di in sessions_from(u):
        for side, s in ((0, 1), (1, -1)):
            cc = open_atm(st, di, side)
            if cc < 0:
                continue
            o, h, l, c = contract_minutes(st, cc, di)
            bo, bh, bl, bc = bars5(o, h, l, c)
            if not np.isfinite(bh[:nb]).any():
                continue
            hi = np.nanmax(bh[:nb])
            for b in range(nb, last_b + 1):
                if np.isfinite(bc[b]) and bc[b] > hi:
                    rows.append((di, 5 * b + 4, s, cc))
                    break
    return _ev(rows, (or_min + 4, mi(13, 30)))


def o2_prem_mom(u, x):
    st = u.stores["WEEK"]
    rows = []
    b0, b1 = (mi(9, 30) - 4) // 5, (mi(13, 30) - 4) // 5
    for di in sessions_from(u):
        for side, s in ((0, 1), (1, -1)):
            cc = open_atm(st, di, side)
            if cc < 0:
                continue
            o, h, l, c = contract_minutes(st, cc, di)
            ok = np.flatnonzero(np.isfinite(o[:3]))
            if not len(ok):
                continue
            p0 = o[ok[0]]
            bo, bh, bl, bc = bars5(o, h, l, c)
            for b in range(b0, b1 + 1):
                if np.isfinite(bc[b]) and bc[b] >= p0 * (1 + x):
                    rows.append((di, 5 * b + 4, s, cc))
                    break
    return _ev(rows, (mi(9, 30), mi(13, 30)))


# ------------------------------------------------------------------------------------------------ E families
def event_days(u) -> set[int]:
    ev = pd.read_csv(K.ROOT / "research" / "brain" / "calendar_data" / "events.csv")
    dates = set(ev[ev.kind.isin(["rbi_policy", "budget"])].date) | set(EXTRA_EVENTS)
    out = set()
    for x in dates:
        o = date.fromisoformat(str(x)).toordinal()
        k = int(np.searchsorted(u.days, o))
        if k < len(u.days) and u.days[k] == o:
            out.add(k)
    return out


def e1_event_vol(u, when):
    rows = []
    start = sessions_from(u).start
    for di in sorted(event_days(u)):
        if when == "eve" and di - 1 >= start:
            rows.append((di - 1, POS_M, 0, -1))
        elif when == "day" and di >= start:
            rows.append((di, 4, 0, -1))
    return _ev(rows, (POS_M, POS_M) if when == "eve" else (4, 4), "event")


def expiry_set(u, series="WEEK") -> set[int]:
    return set(int(x) for x in u.stores[series].exp_di)


def e2_expiry_vol(u, at, series="WEEK"):
    m = mi(*at) - 1
    ex = expiry_set(u, series)
    rows = [(di, m, 0, -1) for di in sessions_from(u) if di in ex]
    return _ev(rows, (m, m), "expiry")


def e3_expiry_hero(u, at, th):
    m = mi(*at) - 1
    ex = expiry_set(u)
    rows = []
    for di in sessions_from(u):
        if di not in ex:
            continue
        op, c = u.O[di, 0], u.C[di, m]
        if np.isfinite(op) and np.isfinite(c) and abs(c / op - 1) * 100 > th:
            rows.append((di, m, int(np.sign(c - op)), -1))
    return _ev(rows, (m, m), "expiry")


SIGNALS = {
    "U1_orb": u1_orb, "U2_gap": u2_gap, "U3_lateday": u3_lateday, "U4_donchian": u4_donchian,
    "U5_daily_mr": u5_daily_mr, "U6_ema": u6_ema, "U7_bigbar": u7_bigbar, "U8_pdhl": u8_pdhl, "U9_vix": u9_vix,
    "U10_trendday": u10_trendday, "C1_pcr": c1_pcr, "C2_ivcheap": c2_ivcheap, "C3_oishift": c3_oishift,
    "O1_prem_orb": o1_prem_orb, "O2_prem_mom": o2_prem_mom, "E1_event_vol": e1_event_vol,
    "E2_expiry_vol": e2_expiry_vol, "E3_expiry_hero": e3_expiry_hero,
}


# ------------------------------------------------------------------------------------------------ day features (filters)
def day_features(u) -> dict:
    """Per session, known before it opens: previous VIX close, weekday, sessions to the near expiry, last-3-sessions of
    the month, event day +-1."""
    nd = len(u.days)
    vix_prev = np.r_[np.nan, u.vix_dclose[:-1]]
    dts = [date.fromordinal(int(x)) for x in u.days]
    dow = np.array([d.weekday() for d in dts])
    ex = np.array(sorted(expiry_set(u)), dtype=np.int64)
    k = np.searchsorted(ex, np.arange(nd))
    dte = np.where(k < len(ex), ex[np.minimum(k, len(ex) - 1)] - np.arange(nd), 99)
    ym = np.array([d.year * 12 + d.month for d in dts])
    monthend = np.zeros(nd, dtype=bool)
    for i in range(nd):
        later = ym[i + 1:i + 4]
        monthend[i] = int(np.sum(later == ym[i])) < 3
    evd = event_days(u)
    event = np.array([any(abs(i - e) <= 1 for e in evd) for i in range(nd)])
    return dict(vix_prev=vix_prev, dow=dow, dte=dte, monthend=monthend, event=event)


def filter_mask(name: str, f: dict, di: np.ndarray) -> np.ndarray:
    di = np.asarray(di, dtype=np.int64)
    if name == "all":
        return np.ones(len(di), dtype=bool)
    v = f["vix_prev"][di]
    if name == "vix_lo":
        return v < K.VIX_CUTS[0]
    if name == "vix_mid":
        return (v >= K.VIX_CUTS[0]) & (v <= K.VIX_CUTS[1])
    if name == "vix_hi":
        return v > K.VIX_CUTS[1]
    if name.startswith("dow"):
        return f["dow"][di] == int(name[3:])
    if name == "dte0":
        return f["dte"][di] == 0
    if name == "dte1":
        return f["dte"][di] == 1
    if name == "dte2p":
        return f["dte"][di] >= 2
    if name == "monthend":
        return f["monthend"][di]
    if name == "event":
        return f["event"][di]
    raise ValueError(name)
