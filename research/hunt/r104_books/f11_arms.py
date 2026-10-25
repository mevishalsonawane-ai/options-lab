"""R104 families F11 (exit overlays and entry filters on the arms) and F9 (money management), PREREG.md sections 5 / 7.

The arms are R101's B0: Liquidity 15+5 (LIQ), R72 Breadth Donchian (R72), R51 Donchian 20 (R51), R52 Open outside value
(R52), priced on R101's engine (R51 Amendment A clean stores, R64 simulator, liq fills, dated charges) over R101's window.
B0 must reproduce R101's published design figures before any overlay runs (asserted in `units()`).

    python research/hunt/r104_books/f11_arms.py build            # design units of the four arms + B0 parity
    python research/hunt/r104_books/f11_arms.py design           # every F11 / F9 test on the design period

Overlays re-price each candidate with an extra FORCED exit row computed from the index path (the earliest of the arm's
own forced row and the overlay's); filters drop candidates before the arm's own one-position walk (R80 / R101 method).
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import date, timedelta
from functools import lru_cache
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r101_arms_enhance"))
sys.path.insert(0, str(HERE))

import r101cfg as K101  # noqa: E402

import r104cfg as K  # noqa: E402

OUTA = K.OUT / "arms"
ARMS = ("LIQ", "R72", "R51", "R52")


def _mods():
    import r101arms as A
    import r101study as S
    return A, S


def units(arm: str, phase: str = "design") -> list:
    """The arm's priced candidates for the phase (cached). The final phase is built only by the holdout read."""
    OUTA.mkdir(parents=True, exist_ok=True)
    p = OUTA / f"units_{arm}_{phase}.pkl"
    if p.exists():
        return pickle.loads(p.read_bytes())
    if phase != "design" and not K.HOLDOUT_FLAG.exists():
        raise PermissionError("the arms' final period is locked until the R104 holdout read")
    A, S = _mods()
    lo, hi = (K101.START, K101.DESIGN_END) if phase == "design" else (K101.FINAL_START, K101.END)
    if phase != "design":
        import common as C
        C.open_final()
    u = A.UNITS[arm](lo, hi)
    b0 = S.run_variant(u, None)
    par = S.check_b0(arm, b0, phase, u)
    assert par["reproduced"], (arm, phase, par)
    p.write_bytes(pickle.dumps(u, protocol=pickle.HIGHEST_PROTOCOL))
    (OUTA / f"parity_{arm}_{phase}.json").write_text(json.dumps(par, default=str))
    return u


# ================================================================================================ shared data
_D = {}


def day(und, d):
    """R51's clean store Day (the arms' own prices)."""
    if und not in _D:
        import r51data
        _D[und] = r51data.load(und)
    return _D[und].get(d)


def reprice(arm, und, D, c, fx_new):
    """Re-price one candidate with an extra forced exit row (the earlier of its own and fx_new)."""
    A, _S = _mods()
    M = A.mods()
    own = int(c.get("fx", -1))
    fx = fx_new if own < 0 else min(own, fx_new)
    if arm == "LIQ":
        return M["E"].run_day(und, D, [c["side"]], [c["strike"]], [c["er"]], M["fam_liq"].EX, [fx])[0]
    ex = A._oov_l25() if arm == "R52" else A._xl()
    return M["G"].run_day(und, D, [c["side"]], [c["strike"]], [c["er"]], ex, [fx])[0]


def walk(arm, units_, res_by_unit=None, keep_by_unit=None):
    _A, S = _mods()
    trades = []
    for g, (key, cands, res, how) in enumerate(units_):
        rs = res_by_unit[g] if res_by_unit is not None else res
        kp = keep_by_unit[g] if keep_by_unit is not None else None
        for tt in S.WALK[how](cands, rs, kp):
            tt = dict(tt)
            tt["arm"] = arm
            tt["day"] = tt["day"] if isinstance(tt["day"], date) else tt["day"].date()
            trades.append(tt)
    trades.sort(key=lambda x: (x["day"], x["und"], x["er"]))
    return trades


# ------------------------------------------------------------------------------------------------ index helpers
@lru_cache(None)
def daily_frame(und):
    import r104data as DA
    return DA.index_daily(und)


def prev_daily(und, d, n):
    f = daily_frame(und)
    x = f[f.index < d]
    return x.iloc[-n:] if len(x) >= n else None


_EXP30 = {}


def exp30(und, d):
    """Per row: the mean high-low of the next 30 minutes from that row over the previous 20 sessions (R16 stand-in)."""
    key = (und, d)
    if key in _EXP30:
        return _EXP30[key]
    import r51data
    days = sorted(k for k in r51data.load(und) if k < d)[-20:]
    acc = np.zeros(375)
    n = np.zeros(375)
    for x in days:
        a = r51data.load(und)[x].idx
        for r in range(0, 345, 5):
            h, l = np.nanmax(a[1, r:r + 30]), np.nanmin(a[2, r:r + 30])
            if np.isfinite(h) and np.isfinite(l):
                acc[r:r + 5] += h - l
                n[r:r + 5] += 1
    out = np.where(n > 0, acc / np.maximum(n, 1), np.nan)
    _EXP30[key] = out
    return out


def bars5_day(D):
    """5-minute bars of one day: (o, h, l, c, first row, last row) arrays."""
    a = D.idx
    o, h, l, c, r0, r1 = [], [], [], [], [], []
    for b in range(75):
        seg = a[:, 5 * b:5 * b + 5]
        ok = np.isfinite(seg[3])
        if not ok.any():
            continue
        o.append(seg[0][ok][0]); h.append(np.nanmax(seg[1])); l.append(np.nanmin(seg[2])); c.append(seg[3][ok][-1])
        r0.append(5 * b); r1.append(5 * b + 4)
    return tuple(np.array(x, float) for x in (o, h, l, c)) + (np.array(r0), np.array(r1))


def sgn(c):
    return 1 if c["side"] == "C" else -1


# ================================================================================================ overlays
def ov_trail(D, c, r, level_fn):
    """Generic index stop walker: level_fn(m) -> the stop known at minute m (NaN none); exit at m + 1 on a touch."""
    s = sgn(c)
    for m in range(int(r["er"]), int(r["xr"])):
        lv = level_fn(m)
        if lv != lv:
            continue
        lo, hi = D.idx[2, m], D.idx[1, m]
        if (s > 0 and lo <= lv) or (s < 0 and hi >= lv):
            return m + 1
    return -1


def ov_wyckoff08(arm, und, D, c, r):
    """Swing-low stop: 0.1 ATR(5m) beyond the last confirmed 1-bar fractal before entry, raised under each new
    reaction low after a new high in favour."""
    o, h, l, cl, r0, r1 = bars5_day(D)
    import r104ind as I
    a = I.atr(h, l, cl, 14)
    s = sgn(c)
    er = int(r["er"])
    nb = len(cl)
    lvl = np.full(375, np.nan)
    stop = np.nan
    best = -np.inf
    for b in range(2, nb):
        if r1[b] + 1 > int(r["xr"]):
            break
        j = b - 1                                   # bar j is a fractal once bar b closes
        if s > 0 and l[j] < l[j - 1] and l[j] < l[b]:
            sw = l[j]
        elif s < 0 and h[j] > h[j - 1] and h[j] > h[b]:
            sw = h[j]
        else:
            sw = np.nan
        aa = a[b] if np.isfinite(a[b]) else np.nanmean(h - l)
        if r1[b] < er:
            if sw == sw:
                stop = sw - s * 0.1 * aa
            continue
        newx = (h[b] > best) if s > 0 else (-l[b] > best)
        best = max(best, h[b] if s > 0 else -l[b])
        if sw == sw and newx:
            cand = sw - s * 0.1 * aa
            if stop != stop or (s > 0 and cand > stop) or (s < 0 and cand < stop):
                stop = cand
        if r1[b] + 1 < 375:
            lvl[r1[b] + 1:] = stop
    if stop == stop:
        lvl[:max(er, 0)] = np.nan
        first = np.flatnonzero(np.isfinite(lvl))
        if not len(first):
            lvl[er:] = stop
    return ov_trail(D, c, r, lambda m: lvl[m])


def ov_wyckoff09(arm, und, D, c, r):
    e = exp30(und, r["day"])
    er = int(r["er"])
    dist = e[er] if er < 375 else np.nan
    if dist != dist or dist <= 0:
        return -1
    s = sgn(c)
    S0 = D.idx[0, er]
    stop = S0 - s * dist
    best = S0
    frozen = False
    for m in range(er, int(r["xr"])):
        lo, hi = D.idx[2, m], D.idx[1, m]
        if not np.isfinite(lo):
            continue
        if (s > 0 and lo <= stop) or (s < 0 and hi >= stop):
            return m + 1
        if not frozen:
            best = max(best, hi) if s > 0 else min(best, lo)
            cand = best - s * dist
            if (cand - stop) * s >= 0.25 * dist:
                stop = stop + s * 0.25 * dist * np.floor((cand - stop) * s / (0.25 * dist))
            if (stop - S0) * s >= 0.1 * dist:
                frozen = True
    return -1


def ov_wyckoff10(arm, und, D, c, r):
    e = exp30(und, r["day"])
    er = int(r["er"])
    if er + 20 >= int(r["xr"]) or not np.isfinite(e[er]):
        return -1
    s = sgn(c)
    S0 = D.idx[0, er]
    seg_h, seg_l = D.idx[1, er:er + 20], D.idx[2, er:er + 20]
    fav = (np.nanmax(seg_h) - S0) if s > 0 else (S0 - np.nanmin(seg_l))
    return er + 20 if fav < 0.25 * e[er] else -1


def ov_wyckoff12(arm, und, D, c, r):
    t0 = (14 * 60 + 50 if D.expiry else 15 * 60) - 555
    er, xr = int(r["er"]), int(r["xr"])
    if not (er < t0 < xr):
        return -1
    con = D.con.get((c["side"], float(c["strike"])))
    if con is None or not np.isfinite(con[3, t0]) or not con[3, t0] > r["E"]:
        return -1
    s = sgn(c)
    tr = np.nan_to_num(D.idx[1] - D.idx[2])
    best = D.idx[3, t0]
    for m in range(t0 + 1, xr):
        dist = 0.25 * tr[max(0, m - 15):m].sum()
        lo, hi = D.idx[2, m], D.idx[1, m]
        if not np.isfinite(lo):
            continue
        if (s > 0 and lo <= best - dist) or (s < 0 and hi >= best + dist):
            return m + 1
        best = max(best, hi) if s > 0 else min(best, lo)
    return -1


def ov_hk11(arm, und, D, c, r):
    er = int(r["er"])
    return er + 15 if er + 15 < int(r["xr"]) else -1


def ov_hk12(arm, und, D, c, r):
    s = sgn(c)
    er = int(r["er"])
    a = D.idx
    lvl = np.full(375, np.nan)
    b0 = er // 60
    seg = slice(60 * b0, er)
    stop = (np.nanmin(a[2, seg]) if s > 0 else np.nanmax(a[1, seg])) if er > 60 * b0 else np.nan
    lvl[er:] = stop
    for b in range(b0, 7):
        end = 60 * b + 60
        if end >= 375 or end <= er:
            continue
        cand = np.nanmin(a[2, 60 * b:end]) if s > 0 else np.nanmax(a[1, 60 * b:end])
        if stop != stop or (s > 0 and cand > stop) or (s < 0 and cand < stop):
            stop = cand
        lvl[end:] = stop
    return ov_trail(D, c, r, lambda m: lvl[m])


def ov_aziz08(arm, und, D, c, r):
    o, h, l, cl, r0, r1 = bars5_day(D)
    s = sgn(c)
    er, xr = int(r["er"]), int(r["xr"])
    best = -np.inf
    fails = 0
    nopen = 0
    for b in range(1, len(cl)):
        if r0[b] < er:
            continue
        if r1[b] + 1 >= xr:
            break
        nopen += 1
        x = h[b] if s > 0 else -l[b]
        newx = x > best
        best = max(best, x)
        weak = (not newx) and ((l[b] < l[b - 1]) if s > 0 else (h[b] > h[b - 1]))
        fails = fails + 1 if weak else 0
        if nopen >= 2 and fails >= 2:
            return r1[b] + 1
    return -1


def levels(und, d):
    """Aziz's pre-marked levels for the day: previous close, yesterday's high / low, two days ago high / low, daily
    SMA 50 / 200 (previous sessions), and the round numbers within 3 steps."""
    f = daily_frame(und)
    x = f[f.index < d]
    if len(x) < 200:
        return [], np.nan
    import r104ind as I
    atr = I.atr(x.high.values, x.low.values, x.close.values, 14)[-1]
    lv = [x.close.iloc[-1], x.high.iloc[-1], x.low.iloc[-1], x.high.iloc[-2], x.low.iloc[-2],
          x.close.iloc[-50:].mean(), x.close.iloc[-200:].mean()]
    rnd = {"NIFTY": 100, "BANKNIFTY": 500, "FINNIFTY": 100, "MIDCPNIFTY": 50, "SENSEX": 500}[und]
    base = round(x.close.iloc[-1] / rnd) * rnd
    lv += [base + j * rnd for j in range(-6, 7)]
    return lv, atr


def ov_aziz10(arm, und, D, c, r):
    lv, atr = levels(und, r["day"])
    if not lv or atr != atr:
        return -1
    s = sgn(c)
    er = int(r["er"])
    S0 = D.idx[0, er]
    beyond = [v for v in lv if (v - S0) * s >= 0.2 * atr]
    if not beyond:
        return -1
    tgt = min(beyond) if s > 0 else max(beyond)
    for m in range(er, int(r["xr"])):
        if (s > 0 and D.idx[1, m] >= tgt) or (s < 0 and D.idx[2, m] <= tgt):
            return m + 1
    return -1


_B5ALL = {}


def breadth_lows(d):
    """{und: (lows, highs, 20-bar min low before, 20-bar max high before, r1)} for the five indices on day d."""
    if d in _B5ALL:
        return _B5ALL[d]
    import r51data
    out = {}
    for u in K101.UNDS:
        st = r51data.load(u)
        prevs = sorted(x for x in st if x <= d)[-2:]
        H, L, R1 = [], [], []
        for x in prevs:
            o, h, l, cl, r0, r1 = bars5_day(st[x])
            if x == d:
                R1 = list(r1)
                n_today = len(h)
            H += list(h); L += list(l)
        if not R1:
            continue
        H, L = np.array(H), np.array(L)
        k0 = len(H) - n_today
        mn = np.array([L[max(0, k - 20):k].min() if k >= 1 else np.nan for k in range(k0, len(L))])
        mx = np.array([H[max(0, k - 20):k].max() if k >= 1 else np.nan for k in range(k0, len(H))])
        out[u] = (L[k0:], H[k0:], mn, mx, np.array(R1))
    _B5ALL[d] = out
    return out


def ov_wyckoff06(arm, und, D, c, r):
    s = sgn(c)
    bl = breadth_lows(r["day"])
    if und not in bl:
        return -1
    er, xr = int(r["er"]), int(r["xr"])
    r1 = bl[und][4]
    opened = 0
    for j, end in enumerate(r1):
        if end < er:
            continue
        if end + 1 >= xr:
            break
        opened += 1
        if opened < 2:
            continue
        n = 0
        for u, (L, H, mn, mx, R1) in bl.items():
            if u == und or j >= len(L):
                continue
            if s > 0 and L[j] < mn[j]:
                n += 1
            if s < 0 and H[j] > mx[j]:
                n += 1
        if n >= 3:
            return int(end) + 1
    return -1


OVERLAYS = {
    "book-wyckoff-06": (ov_wyckoff06, ("LIQ", "R72")),
    "book-wyckoff-08": (ov_wyckoff08, ("R72", "R51", "LIQ")),
    "book-wyckoff-09": (ov_wyckoff09, ARMS),
    "book-wyckoff-10": (ov_wyckoff10, ARMS),
    "book-wyckoff-12": (ov_wyckoff12, ARMS),
    "book-heitkoetter-11": (ov_hk11, ("R51", "R72")),
    "book-heitkoetter-12": (ov_hk12, ("R72", "R51")),
    "book-aziz-08": (ov_aziz08, ARMS),
    "book-aziz-10": (ov_aziz10, ARMS),
}


def run_overlay(rid, phase="design"):
    fn, arms = OVERLAYS[rid]
    out = {}
    for arm in arms:
        U = units(arm, phase)
        new = []
        changed = 0
        for key, cands, res, how in U:
            und, d = key[0], key[1]
            D = day(und, d)
            rr = []
            for c, r0 in zip(cands, res):
                if r0 is None:
                    rr.append(None)
                    continue
                r = dict(r0, day=d)
                fx = fn(arm, und, D, c, r)
                if 0 <= fx < int(r["xr"]):
                    rr.append(reprice(arm, und, D, c, fx))
                    changed += 1
                else:
                    rr.append(r0)
            new.append(rr)
        out[arm] = dict(b0=walk(arm, U), var=walk(arm, U, res_by_unit=new), changed=changed)
    return out


# ================================================================================================ filters
def _ses_of(c):
    return c["er"]


def daily_closes(und):
    return daily_frame(und).close


def f_streak(n):
    def keep(arm, und, d, c):
        x = prev_daily(und, d, n + 1)
        if x is None:
            return True
        ch = np.sign(np.diff(x.close.values))
        if (ch > 0).all() and c["side"] == "C":
            return False
        if (ch < 0).all() and c["side"] == "P":
            return False
        return True
    return keep


def f_gap(mode):
    def keep(arm, und, d, c):
        x = prev_daily(und, d, 1)
        D = day(und, d)
        if x is None or D is None:
            return True
        yh, yl = x.high.iloc[-1], x.low.iloc[-1]
        if mode == "open":
            o = D.idx[0, np.flatnonzero(np.isfinite(D.idx[0]))[0]]
            up, dn = o > yh, o < yl
        else:
            r = int(c["er"]) - 1
            up = np.nanmin(D.idx[2, :r + 1]) > yh
            dn = np.nanmax(D.idx[1, :r + 1]) < yl
        return not ((up and c["side"] == "C") or (dn and c["side"] == "P"))
    return keep


def f_swing3(arm, und, d, c):
    x = prev_daily(und, d, 30)
    D = day(und, d)
    if x is None or D is None:
        return True
    st = 0
    hh, ll, cc = x.high.values, x.low.values, x.close.values
    for i in range(3, len(x)):
        if hh[i] > hh[i - 3:i].max():
            st = 1
        elif ll[i] < ll[i - 3:i].min():
            st = -1
    H3, L3 = hh[-3:].max(), ll[-3:].min()
    r = int(c["er"]) - 1
    seg_h, seg_l = D.idx[1, :r + 1], D.idx[2, :r + 1]
    for m in range(r + 1):
        if seg_h[m] > H3:
            st = 1
        elif seg_l[m] < L3:
            st = -1
    return (st > 0 and c["side"] == "C") or (st < 0 and c["side"] == "P")


def f_news(arm, und, d, c):
    import r104data as DA
    e = DA.events()
    e = e[(e.date == d) & e.kind.isin(["budget", "rbi", "us_cpi", "fomc"])]
    t = 555 + int(c["er"])
    for _, row in e.iterrows():
        try:
            hh, mm = map(int, str(row.ist_time).split(":"))
        except ValueError:
            continue
        if abs(t - (hh * 60 + mm)) <= 15:
            return False
    return True


@lru_cache(None)
def trading_days():
    import r104data as DA
    return sorted(DA.index_daily("NIFTY").index)


def f_holiday(arm, und, d, c):
    import r104data as DA
    td = trading_days()
    i = td.index(d) if d in td else None
    if i is None or i == 0 or i >= len(td) - 1:
        return True
    prev_d, next_d = td[i - 1], td[i + 1]
    def weekdays_between(a, b):
        return sum(1 for k in range(1, (b - a).days) if (a + timedelta(days=k)).weekday() < 5)
    if weekdays_between(prev_d, d) > 0 or weekdays_between(d, next_d) > 0:
        return False
    e = DA.events()
    if (e[e.kind.isin(["budget", "rbi", "us_cpi"])].date == next_d).any():
        return False
    return True


def f_52w(neutral_both):
    def keep(arm, und, d, c):
        x = prev_daily(und, d, 253)
        if x is None:
            return True
        cl, hh, ll = x.close.values, x.high.values, x.low.values
        last = cl[-1]
        up_brk = any(cl[-k] > cl[-k - 20:-k].max() for k in range(1, 11))
        dn_brk = any(cl[-k] < cl[-k - 20:-k].min() for k in range(1, 11))
        long_only = last >= 0.98 * hh[-252:].max() and up_brk
        short_only = last <= 1.02 * ll[-252:].min() and dn_brk
        if long_only:
            return c["side"] == "C"
        if short_only:
            return c["side"] == "P"
        return neutral_both
    return keep


def f_secondary(arm, und, d, c):
    """Skip NIFTY / BANKNIFTY calls for 60 minutes after MIDCPNIFTY or FINNIFTY rose >= 1% in 60 minutes while NIFTY and
    BANKNIFTY each rose <= 0.2% and sat below their session highs (after 10:15)."""
    if und not in ("NIFTY", "BANKNIFTY") or c["side"] != "C":
        return True
    r = int(c["er"]) - 1
    Ds = {u: day(u, d) for u in ("NIFTY", "BANKNIFTY", "MIDCPNIFTY", "FINNIFTY")}
    if any(v is None for v in Ds.values()):
        return True
    for m in range(max(60, r - 60), r + 1):
        if m + 555 < 10 * 60 + 15:
            continue
        sec = any(np.isfinite(Ds[u].idx[3, m]) and np.isfinite(Ds[u].idx[3, m - 60]) and
                  Ds[u].idx[3, m] / Ds[u].idx[3, m - 60] - 1 >= 0.01 for u in ("MIDCPNIFTY", "FINNIFTY"))
        if not sec:
            continue
        lead = all(np.isfinite(Ds[u].idx[3, m]) and Ds[u].idx[3, m] / Ds[u].idx[3, m - 60] - 1 <= 0.002 and
                   Ds[u].idx[3, m] < np.nanmax(Ds[u].idx[1, :m + 1]) for u in ("NIFTY", "BANKNIFTY"))
        if lead:
            return False
    return True


def f_reward(ratio):
    """LIQ only: reward to the next level (Aziz's list) >= ratio x the risk to the arm's index stop."""
    def keep(arm, und, d, c):
        lv, atr = levels(und, d)
        if not lv or "idx_stop" not in c:
            return True
        s = 1 if c["side"] == "C" else -1
        e = float(c["close"])
        risk = abs(e - float(c["idx_stop"]))
        beyond = [v for v in lv if (v - e) * s > 0]
        if not beyond or risk <= 0:
            return False
        rew = (min(beyond) - e) if s > 0 else (e - max(beyond))
        return rew >= ratio * risk
    return keep


FILTERS = {
    "book-wyckoff-11": ({"4d": f_streak(4), "3d": f_streak(3)}, ARMS),
    "book-wyckoff-13": ({"base": f_secondary}, ARMS),
    "book-carli-04": ({"open": f_gap("open"), "window": f_gap("window")}, ARMS),
    "book-tom-03": ({"base": f_swing3}, ("R51", "R72", "LIQ")),
    "book-tom-18": ({"base": f_news}, ARMS),
    "book-heitkoetter-16": ({"base": f_holiday}, ARMS),
    "book-porwal-07": ({"base": f_52w(False), "neutral": f_52w(True)}, ("R51", "R72")),
    "book-aziz-11": ({"base": f_reward(2.0)}, ("LIQ",)),
    "book-douglas-05": ({"base": f_reward(3.0)}, ("LIQ",)),
}


def run_filter(rid, phase="design"):
    fns, arms = FILTERS[rid]
    out = {}
    for vn, fn in fns.items():
        per = {}
        for arm in arms:
            U = units(arm, phase)
            keeps = [[bool(fn(arm, key[0], key[1], c)) for c in cands] for key, cands, res, how in U]
            removed = sum(sum(1 for k in kp if not k) for kp in keeps)
            per[arm] = dict(b0=walk(arm, U), var=walk(arm, U, keep_by_unit=keeps), removed=removed, keeps=keeps)
        out[vn] = per
    return out


def run_sequential(rid, phase="design", any_loss=False):
    """Porwal 12 / Heitkoetter 15: day rules that depend on the walked trades or the signal order."""
    out = {}
    arms = ARMS
    for arm in arms:
        U = units(arm, phase)
        b0 = walk(arm, U)
        if rid == "book-heitkoetter-15":
            keeps = []
            for key, cands, res, how in U:
                am = pm = False
                kp = []
                for c in cands:
                    t = 555 + int(c["er"])
                    if t < 12 * 60 + 30:
                        kp.append(not am)
                        am = True
                    else:
                        kp.append(not pm)
                        pm = True
                keeps.append(kp)
            var = walk(arm, U, keep_by_unit=keeps)
        else:
            # stop for the day (per arm, all its indices) after two stop-loss exits (any two losses: variant)
            by_day = defaultdict(list)
            for t_ in b0:
                by_day[t_["day"]].append(t_)
            var = []
            for d, ts in sorted(by_day.items()):
                ts = sorted(ts, key=lambda x: x["er"])
                hit = []
                for t_ in ts:
                    if len([x for x in hit if x["xr"] <= t_["er"]]) >= 2:
                        continue
                    var.append(t_)
                    bad = t_["net"] < 0 if any_loss else t_["why"] == "stop"
                    if bad:
                        hit.append(t_)
        out[arm] = dict(b0=b0, var=var)
    return out


# ================================================================================================ evaluation
def daily_series(trades, lots=None):
    s = defaultdict(float)
    for i, t_ in enumerate(trades):
        s[t_["day"]] += t_["net"] * (1 if lots is None else lots[i])
    return s


def quarter_of(d):
    for i, (a, b) in enumerate(K101.WF_Q):
        if a <= d <= b:
            return i
    return -1


def delta_stats(per_arm):
    """Pooled over the arms: B0 and variant nets, the daily delta series, per-quarter deltas, the bootstrap p."""
    import r104stats as ST
    b0, var = [], []
    for arm, r in per_arm.items():
        b0 += r["b0"]
        var += r["var"]
    sb, sv = daily_series(b0), daily_series(var)
    days = sorted(set(sb) | set(sv))
    delta = np.array([sv.get(d, 0.0) - sb.get(d, 0.0) for d in days])
    q = [sum(sv.get(d, 0) - sb.get(d, 0) for d in days if quarter_of(d) == i) for i in range(4)]
    p = ST.boot_p(delta)
    wb = np.mean([t_["net"] > 0 for t_ in b0]) if b0 else None
    wv = np.mean([t_["net"] > 0 for t_ in var]) if var else None
    return dict(b0_n=len(b0), b0_net=float(sum(t_["net"] for t_ in b0)), b0_win=wb, var_n=len(var),
                var_net=float(sum(t_["net"] for t_ in var)), var_win=wv, delta=float(delta.sum()), q=q, p=p,
                changed=sum(r.get("changed", 0) for r in per_arm.values()),
                removed=sum(r.get("removed", 0) for r in per_arm.values()))


def filter_twin_p(rid, vn, per_arm, n=200, seed=K.SEED):
    """Kept trades vs random removals of the same count of candidate signals (per arm), pooled total net."""
    rng = np.random.default_rng(seed)
    act = sum(sum(t_["net"] for t_ in r["var"]) for r in per_arm.values())
    tot = np.zeros(n)
    for arm, r in per_arm.items():
        U = units(arm)
        ids = [(g, j) for g, (_k, cands, _r, _h) in enumerate(U) for j in range(len(cands))]
        k = r["removed"]
        for i in range(n):
            pick = set(rng.choice(len(ids), size=k, replace=False).tolist()) if k else set()
            drop = {ids[x] for x in pick}
            keeps = [[(g, j) not in drop for j in range(len(cands))] for g, (_k, cands, _r, _h) in enumerate(U)]
            tot[i] += sum(t_["net"] for t_ in walk(arm, U, keep_by_unit=keeps))
    return float((1 + np.sum(tot >= act)) / (n + 1))


# ================================================================================================ money management
def mm_trades(phase="design"):
    """All four arms' B0 trades (1 lot each), with the exit time as a minute of the day."""
    out = []
    for arm in ARMS:
        out += walk(arm, units(arm, phase))
    out.sort(key=lambda t_: (t_["day"], t_["er"], t_["arm"]))
    return out


STOPS = {"LIQ": 0.15, "R72": 0.15, "R51": 0.15, "R52": 0.30}


def equity_stats(trades, lots):
    s = defaultdict(float)
    mon = defaultdict(float)
    for t_, n in zip(trades, lots):
        s[t_["day"]] += t_["net"] * n
        mon[(t_["day"].year, t_["day"].month)] += t_["net"] * n
    days = sorted(s)
    eq = np.cumsum([s[d] for d in days]) if days else np.zeros(1)
    dd = float(np.min(eq - np.maximum.accumulate(np.r_[0.0, eq])[1:])) if len(days) else 0.0
    return dict(net=float(eq[-1]) if len(days) else 0.0, maxdd=dd, worst_month=float(min(mon.values())) if mon else 0.0,
                lots=float(sum(lots)), trades=int(sum(1 for n in lots if n > 0)))


def mm_weekly_goal(trades, mult):
    lots = []
    wins = []
    week_pnl = defaultdict(float)
    stopped = set()
    by_arm_wins = defaultdict(list)
    for t_ in trades:
        wk = (t_["arm"],) + tuple(t_["day"].isocalendar()[:2])
        med = np.median(by_arm_wins[t_["arm"]]) if len(by_arm_wins[t_["arm"]]) >= 20 else None
        if wk in stopped:
            lots.append(0)
        else:
            lots.append(1)
            week_pnl[wk] += t_["net"]
            if med is not None and week_pnl[wk] >= mult * med:
                stopped.add(wk)
        if t_["net"] > 0:
            by_arm_wins[t_["arm"]].append(t_["net"])
    return lots


def mm_day_giveback(trades):
    """Aziz 12 on the combined account: realised P&L at 10:15 > 0, then realised falls > 30% below it: no new entries."""
    lots = []
    by_day = defaultdict(list)
    for i, t_ in enumerate(trades):
        by_day[t_["day"]].append(i)
    out = [1] * len(trades)
    for d, idxs in by_day.items():
        idxs = sorted(idxs, key=lambda i: trades[i]["er"])
        real = []                        # (exit row, net)
        for i in idxs:
            t_ = trades[i]
            er = t_["er"]
            at1015 = sum(n for x, n in real if x <= 60)
            now = sum(n for x, n in real if x <= er)
            if er > 60 and at1015 > 0 and now < 0.7 * at1015:
                out[i] = 0
                continue
            real.append((t_["xr"], t_["net"]))
    return out


def mm_risk_pct(trades, cap=500000.0):
    """Aziz 13: lots = floor(1% equity / (stop risk of 1 lot)); skip if 1 lot > 2%; stop the month at -6%."""
    eq = cap
    out = []
    month_start, cur_month, month_pnl = eq, None, 0.0
    for t_ in trades:
        mo = (t_["day"].year, t_["day"].month)
        if mo != cur_month:
            cur_month, month_start, month_pnl = mo, eq, 0.0
        risk1 = t_["rE"] * STOPS[t_["arm"]] * t_["qty"]
        # the month stop: realised P&L this month plus this trade's risk would pass -6% of the month's start
        if month_pnl - risk1 <= -0.06 * month_start or risk1 <= 0 or risk1 > 0.02 * eq:
            out.append(0)
            continue
        n = int(np.floor(0.01 * eq / risk1))
        out.append(n)
        eq += t_["net"] * n
        month_pnl += t_["net"] * n
    return out


def mm_fixed_risk(trades, R):
    k = len(ARMS)
    out = []
    for t_ in trades:
        risk1 = t_["rE"] * STOPS[t_["arm"]] * t_["qty"]
        out.append(int(np.floor((R / k) / risk1)) if risk1 > 0 else 0)
    return out


def mm_blocks(trades, design_only_hist=True):
    """Douglas 01: per arm, after each block of 20 taken trades, compare the block's wins and net with the 5th
    percentile of 20-trade blocks bootstrapped from the arm's EARLIER trades; below either -> skip the next 20 signals."""
    rng = np.random.default_rng(K.SEED)
    out = [1] * len(trades)
    hist = defaultdict(list)
    block = defaultdict(list)
    pause = defaultdict(int)
    for i, t_ in enumerate(trades):
        a = t_["arm"]
        if pause[a] > 0:
            out[i] = 0
            pause[a] -= 1
            hist[a].append(t_["net"])
            continue
        block[a].append(t_["net"])
        if len(block[a]) == 20:
            h = np.array(hist[a])
            if len(h) >= 60:
                sims = rng.choice(h, size=(1000, 20), replace=True)
                lo_net = np.percentile(sims.sum(axis=1), 5)
                lo_win = np.percentile((sims > 0).sum(axis=1), 5)
                b = np.array(block[a])
                if b.sum() < lo_net or (b > 0).sum() < lo_win:
                    pause[a] = 20
            block[a] = []
        hist[a].append(t_["net"])
    return out


def mm_cusum(trades, design_stats):
    out = [1] * len(trades)
    S = defaultdict(float)
    U = defaultdict(float)
    skip = defaultdict(int)
    for i, t_ in enumerate(trades):
        a = t_["arm"]
        mu, sd, h = design_stats[a]
        if sd is None:
            continue
        if skip[a] > 0:
            out[i] = 0
            skip[a] -= 1
        z = (t_["net"] - mu) / sd
        S[a] = max(0.0, S[a] - z - 0.5)
        U[a] = max(0.0, U[a] + z - 0.5)
        if S[a] > h and skip[a] == 0:
            skip[a] = 20
            S[a] = 0.0
    return out


def cusum_h(nets, arl=300, rng=None):
    """h giving an in-control average run length of `arl` trades on the arm's bootstrapped trades."""
    rng = rng or np.random.default_rng(K.SEED)
    nets = np.asarray(nets, float)
    mu, sd = nets.mean(), nets.std()
    z = (nets - mu) / sd
    def run_len(h):
        lens = []
        for _ in range(200):
            S, n = 0.0, 0
            while n < 5000:
                S = max(0.0, S - rng.choice(z) - 0.5)
                n += 1
                if S > h:
                    break
            lens.append(n)
        return np.mean(lens)
    lo, hi = 0.5, 15.0
    for _ in range(14):
        mid = (lo + hi) / 2
        if run_len(mid) < arl:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def mm_kelly(trades, c, capital=500000.0):
    """Bjork 04: lots = floor(c x f x capital / (premium x lot)), f = mean / variance of per-trade return on premium over
    the trailing 12 months (>= 60 trades, mean > 0), else 1 lot; capital floor at 75% of its peak -> 1 lot."""
    out = []
    eq, peak = capital, capital
    hist = defaultdict(list)
    for t_ in trades:
        a = t_["arm"]
        h = [(d, x) for d, x in hist[a] if (t_["day"] - d).days <= 365]
        hist[a] = h
        rets = np.array([x for _d, x in h])
        prem = t_["rE"] * t_["qty"]
        n = 1
        if len(rets) >= 60 and rets.mean() > 0 and rets.var() > 0 and eq >= 0.75 * peak:
            f = rets.mean() / rets.var()
            n = max(1, int(np.floor(c * f * eq / prem)))
        out.append(n)
        eq += t_["net"] * n
        peak = max(peak, eq)
        hist[a].append((t_["day"], t_["net"] / prem if prem > 0 else 0.0))
    return out


def mm_throttle(trades, design_f):
    """Chan 05: multiplier = clip(f_trail / f_design, 0, 1) on daily net per lot (126 sessions); lots = floor(1 x m)."""
    out = []
    daily = defaultdict(lambda: defaultdict(float))
    for t_ in trades:
        a = t_["arm"]
        past = [(d, v) for d, v in daily[a].items() if d < t_["day"]][-126:]
        n = 1
        if len(past) >= 40 and design_f.get(a):
            v = np.array([x for _d, x in past])
            ft = v.mean() / v.var() if v.var() > 0 else 0.0
            m = min(max(ft / design_f[a], 0.0), 1.0)
            n = int(np.floor(m))
        out.append(n)
        daily[a][t_["day"]] += t_["net"]
    return out


def mm_worst_day_cap(trades):
    """Chan 06: max lots per arm = floor(Rs 13,000 x share / worst single-day loss per lot so far), share = 1/4."""
    out = []
    daily = defaultdict(lambda: defaultdict(float))
    for t_ in trades:
        a = t_["arm"]
        past = [v for d, v in daily[a].items() if d < t_["day"]]
        worst = -min(past) if past and min(past) < 0 else 0.0
        n = 1 if worst <= 0 else min(1, int(np.floor(13000.0 / 4 / worst)))
        out.append(n)
        daily[a][t_["day"]] += t_["net"]
    return out


def _monthly_weights(trades, method):
    """{(year, month): {arm: lots}} from the trailing daily P&L of the arms (before the month)."""
    daily = defaultdict(lambda: defaultdict(float))
    for t_ in trades:
        daily[t_["arm"]][t_["day"]] += t_["net"]
    months = sorted({(t_["day"].year, t_["day"].month) for t_ in trades})
    out = {}
    prev_w = None
    for y, m in months:
        start = date(y, m, 1)
        days = sorted({d for a in ARMS for d in daily[a] if d < start})
        win = days[-(250 if method == "mv" else 60):]
        if len(win) < 40:
            out[(y, m)] = {a: 1 for a in ARMS}
            continue
        X = np.array([[daily[a].get(d, 0.0) for a in ARMS] for d in win])
        mu = X.mean(axis=0)
        Sg = np.cov(X.T)
        if method == "mv":
            Sg = 0.5 * Sg + 0.5 * np.diag(np.diag(Sg))
            w = np.linalg.pinv(Sg) @ mu
            w = np.clip(w, 0, None)
            if w.sum() > 0:
                w = w / w.max() * 3
            lots = {a: int(min(3, round(x))) for a, x in zip(ARMS, w)}
            # R72 and R51 share a cap (their daily correlation is high)
            if lots["R72"] + lots["R51"] > 3:
                lots["R51"] = max(0, 3 - lots["R72"])
        else:
            inv = np.linalg.pinv(Sg)
            w = inv @ np.ones(len(ARMS))
            w = np.clip(w, 0, None)
            w = w / w.sum() if w.sum() > 0 else np.ones(len(ARMS)) / len(ARMS)
            w = np.minimum(w, 0.5)
            w = w / w.sum()
            if prev_w is not None and np.max(np.abs(w - prev_w)) <= 0.10:
                w = prev_w
            prev_w = w
            lots = {a: int(round(x * len(ARMS))) for a, x in zip(ARMS, w)}
        out[(y, m)] = lots
    return out


def mm_weights(trades, method):
    W = _monthly_weights(trades, method)
    return [W[(t_["day"].year, t_["day"].month)][t_["arm"]] for t_ in trades]


def mm_cushion(trades, k, capital=500000.0):
    """Karatzas 01 on LIQ: lots = floor(k x (capital - floor) / premium of 1 lot), floor = peak - 40,000."""
    out = []
    eq, peak = capital, capital
    for t_ in trades:
        if t_["arm"] != "LIQ":
            out.append(1)
            continue
        prem = t_["rE"] * t_["qty"]
        cushion = eq - (peak - 40000.0)
        n = int(np.floor(k * cushion / prem)) if prem > 0 and cushion > 0 else 0
        out.append(n)
        eq += t_["net"] * n
        peak = max(peak, eq)
    return out


def mm_rules(trades, design_trades):
    """{rule: {variant: lots list}} on `trades` (statistics fitted only on design_trades or on earlier trades)."""
    ds = {}
    df = {}
    rng = np.random.default_rng(K.SEED)
    for a in ARMS:
        nets = [t_["net"] for t_ in design_trades if t_["arm"] == a]
        ds[a] = (np.mean(nets), np.std(nets), cusum_h(nets, rng=rng)) if len(nets) >= 200 else (0.0, None, 0.0)
        dd = defaultdict(float)
        for t_ in design_trades:
            if t_["arm"] == a:
                dd[t_["day"]] += t_["net"]
        v = np.array(list(dd.values()))
        df[a] = v.mean() / v.var() if len(v) > 1 and v.var() > 0 else None
    return {
        "book-heitkoetter-14": {f"m{m}": mm_weekly_goal(trades, m) for m in (0.5, 1.0, 2.0)},
        "book-aziz-12": {"base": mm_day_giveback(trades)},
        "book-aziz-13": {"base": mm_risk_pct(trades)},
        "book-douglas-01": {"base": mm_blocks(trades)},
        "book-douglas-02": {"R2000": mm_fixed_risk(trades, 2000.0), "R650": mm_fixed_risk(trades, 650.0)},
        "book-fmaqf-04": {"base": mm_cusum(trades, ds)},
        "book-fmbjork-04": {"c25": mm_kelly(trades, 0.25), "c50": mm_kelly(trades, 0.5)},
        "book-fmbjork-05": {"base": mm_weights(trades, "mv")},
        "book-fmchan-05": {"base": mm_throttle(trades, df)},
        "book-fmchan-06": {"base": mm_worst_day_cap(trades)},
        "book-fmoptim-02": {"base": mm_weights(trades, "minvar")},
        "book-fmkaratzas-01": {"k05": mm_cushion(trades, 0.5), "k10": mm_cushion(trades, 1.0)},
    }


def mm_twin_p(trades, lots, n=500, seed=K.SEED):
    """Random exposure cuts of the same size: zero out as many lot-units at random as the rule removed; p of a drawdown
    cut at least as large as the rule's (one-sided)."""
    base = equity_stats(trades, [1] * len(trades))
    act = equity_stats(trades, lots)
    cut_units = sum(max(0, 1 - n_) for n_ in lots)
    if cut_units == 0:
        return 1.0
    rng = np.random.default_rng(seed)
    better = 0
    for _ in range(n):
        idx = rng.choice(len(trades), size=cut_units, replace=False)
        lt = np.ones(len(trades))
        lt[idx] = 0
        r = equity_stats(trades, lt)
        if r["maxdd"] >= act["maxdd"]:
            better += 1
    return float((1 + better) / (n + 1))


def run_mm(phase="design"):
    trades = mm_trades(phase)
    design = mm_trades("design") if phase != "design" else trades
    out = {}
    base = equity_stats(trades, [1] * len(trades))
    for rid, vs in mm_rules(trades, design).items():
        res = {}
        for vn, lots in vs.items():
            st = equity_stats(trades, lots)
            adds = any(n_ > 1 for n_ in lots)
            res[vn] = dict(st, adds_risk=adds, twin_p=None if adds else mm_twin_p(trades, lots))
            # per-quarter (design walk-forward quarters): net and drawdown
            if phase == "design":
                qs = []
                for a, b in K101.WF_Q:
                    sel = [i for i, t_ in enumerate(trades) if a <= t_["day"] <= b]
                    tt = [trades[i] for i in sel]
                    qs.append(dict(b0=equity_stats(tt, [1] * len(tt)), var=equity_stats(tt, [lots[i] for i in sel])))
                res[vn]["quarters"] = qs
        out[rid] = dict(base=base, variants=res)
    return out


# ================================================================================================ the design run
def design_all():
    res = {}
    for rid in OVERLAYS:
        per = run_overlay(rid)
        res[f"{rid}|ARM"] = dict(kind="overlay", variants={"base": delta_stats(per)})
        print(rid, res[f"{rid}|ARM"]["variants"]["base"], flush=True)
        _save(res)
    for rid in FILTERS:
        allv = run_filter(rid)
        vres = {}
        for vn, per in allv.items():
            st = delta_stats(per)
            st["twin_p"] = filter_twin_p(rid, vn, per)
            vres[vn] = st
        res[f"{rid}|ARM"] = dict(kind="filter", variants=vres)
        print(rid, {k: (v["delta"], v["p"], v["twin_p"]) for k, v in vres.items()}, flush=True)
        _save(res)
    for rid, kw in (("book-heitkoetter-15", {}), ("book-porwal-12", {})):
        if rid == "book-porwal-12":
            per_s = run_sequential(rid)
            per_l = run_sequential(rid, any_loss=True)
            res[f"{rid}|ARM"] = dict(kind="filter", variants={"stops": delta_stats(per_s), "losses": delta_stats(per_l)})
        else:
            per = run_sequential(rid)
            res[f"{rid}|ARM"] = dict(kind="filter", variants={"base": delta_stats(per)})
        print(rid, res[f"{rid}|ARM"], flush=True)
        _save(res)
    mm = run_mm("design")
    for rid, r in mm.items():
        res[f"{rid}|MM"] = dict(kind="mm", **r)
    _save(res)
    return res


def _save(res):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design_f11_arms.json").write_text(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "build":
        for a in (sys.argv[2:] or ARMS):
            u = units(a, "design")
            print(a, len(u), "units", flush=True)
    elif cmd == "design":
        design_all()
    else:
        raise SystemExit(__doc__)
