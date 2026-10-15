"""R62 mechanical definitions (PREREG sections 3-5): bars, fractal swings, structure (BOS / ChoCH), anchored legs, the
leg's volume profile (POC, value area, LVN), 5-minute FVGs and Order Blocks, and the setup walk that turns them into
entry candidates. Pure numpy on one instrument's minute grid; nothing here reads files or prices a trade.

Time is a flat minute index t = session * 375 + row. A bar is known only at its last (nominal) row; a swing at bar i is
known only at the close of bar i + N. Every function below uses only what was known at the time it acts.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

import r62cfg as K

O, H, L, C = range(4)


# ------------------------------------------------------------------------------------------------ bars
@dataclass
class Bars:
    di: np.ndarray        # session index
    r0: np.ndarray        # first row of the bar's slot
    rend: np.ndarray      # nominal last row of the slot (the bar is known at its close)
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    t: np.ndarray         # flat time of the bar's close: di * 375 + rend

    def __len__(self):
        return len(self.t)


def make_bars(M: np.ndarray, tf: int) -> Bars:
    """M: (nd, 375, 4) one-minute O H L C (NaN = missing minute). Bars of tf minutes aligned to 09:15; the last slot of a
    session may be shorter (the 1-hour 15:15 bar). A slot with no minute makes no bar."""
    nd = M.shape[0]
    starts = np.arange(0, K.NROWS, tf)
    out = {k: [] for k in ("di", "r0", "rend", "o", "h", "l", "c")}
    for d in range(nd):
        for r0 in starts:
            r1 = min(r0 + tf, K.NROWS)
            seg = M[d, r0:r1]
            ok = np.isfinite(seg[:, C])
            if not ok.any():
                continue
            s = seg[ok]
            out["di"].append(d)
            out["r0"].append(r0)
            out["rend"].append(r1 - 1)
            out["o"].append(s[0, O])
            out["h"].append(np.nanmax(s[:, H]))
            out["l"].append(np.nanmin(s[:, L]))
            out["c"].append(s[-1, C])
    a = {k: np.asarray(v, dtype=np.int64 if k in ("di", "r0", "rend") else np.float64) for k, v in out.items()}
    return Bars(a["di"], a["r0"], a["rend"], a["o"], a["h"], a["l"], a["c"], a["di"] * K.NROWS + a["rend"])


# ------------------------------------------------------------------------------------------------ swings and structure
def swings(h: np.ndarray, l: np.ndarray, n: int = K.FRACTAL_N) -> tuple[np.ndarray, np.ndarray]:
    """Fractal swings: bar i is a swing high when its high is above the n highs before it and >= the n highs after it
    (mirror for lows). The last n bars can never be swings yet."""
    m = len(h)
    sh, sl = np.zeros(m, bool), np.zeros(m, bool)
    for i in range(n, m - n):
        if all(h[i] > h[i - k] for k in range(1, n + 1)) and all(h[i] >= h[i + k] for k in range(1, n + 1)):
            sh[i] = True
        if all(l[i] < l[i - k] for k in range(1, n + 1)) and all(l[i] <= l[i + k] for k in range(1, n + 1)):
            sl[i] = True
    return sh, sl


@dataclass
class Structure:
    sh: np.ndarray        # swing high at bar i (known at i + n)
    sl: np.ndarray
    state: np.ndarray     # trend after bar i's close: +1 bullish, -1 bearish, 0 none yet
    brk: np.ndarray       # +1 bullish break at bar i, -1 bearish, 0 none
    choch: np.ndarray     # True when that break was a ChoCH (it flipped the state)
    n: int = K.FRACTAL_N


def structure(b: Bars, n: int = K.FRACTAL_N) -> Structure:
    """BOS / ChoCH state machine (PREREG 4). At each close: add the swings confirmed now (bar t - n), then a close
    beyond the latest unbroken swing high (low) is a bullish (bearish) break."""
    sh, sl = swings(b.h, b.l, n)
    m = len(b)
    state = np.zeros(m, np.int8)
    brk = np.zeros(m, np.int8)
    choch = np.zeros(m, bool)
    hi_px, lo_px = np.nan, np.nan         # the latest unbroken confirmed swing high / low
    st = 0
    for t in range(m):
        i = t - n
        if i >= 0:
            if sh[i]:
                hi_px = b.h[i]
            if sl[i]:
                lo_px = b.l[i]
        if np.isfinite(hi_px) and b.c[t] > hi_px:
            choch[t] = st == -1
            st, brk[t], hi_px = 1, 1, np.nan
        elif np.isfinite(lo_px) and b.c[t] < lo_px:
            choch[t] = st == 1
            st, brk[t], lo_px = -1, -1, np.nan
        state[t] = st
    return Structure(sh, sl, state, brk, choch, n)


def state_at(b: Bars, s: Structure, t: np.ndarray) -> np.ndarray:
    """The structure state known at flat times t: after the last bar that closed at or before t (0 before any)."""
    j = np.searchsorted(b.t, np.asarray(t), side="right") - 1
    return np.where(j >= 0, s.state[np.clip(j, 0, None)], 0)


# ------------------------------------------------------------------------------------------------ legs
@dataclass
class Leg:
    dir: int              # +1 bullish (swing low -> swing high), -1 bearish
    l: int                # first structure bar of the leg
    h: int                # last structure bar of the leg (the confirmed swing)
    t0: int               # flat time of the leg's first minute
    t1: int               # flat time of the leg's last minute
    active_t: int         # flat time it becomes known (close of bar h + n)
    lo: float
    hi: float
    zones: dict = field(default_factory=dict)


def legs(b: Bars, s: Structure) -> list:
    """Anchored legs (PREREG 4): a swing high confirmed while bullish, anchored at the latest swing low before it, with a
    bullish break in between (mirror for bearish)."""
    out = []
    n = s.n
    for k in range(n, len(b)):
        i = k - n
        for d, here, other in ((1, s.sh, s.sl), (-1, s.sl, s.sh)):
            if not here[i] or s.state[k] != d:
                continue
            prev = np.flatnonzero(other[:i])
            if not len(prev):
                continue
            a = int(prev[-1])
            if not (s.brk[a + 1:i + 1] == d).any():
                continue
            lo, hi = float(b.l[a:i + 1].min()), float(b.h[a:i + 1].max())
            if not hi > lo:
                continue
            t0 = int(b.di[a] * K.NROWS + b.r0[a])
            out.append(Leg(d, a, i, t0, int(b.t[i]), int(b.t[k]), lo, hi))
    return out


# ------------------------------------------------------------------------------------------------ profile
@dataclass
class Profile:
    vol: np.ndarray       # (rows,) volume per row
    lo: float
    size: float
    poc: int
    val: int              # value-area rows [val, vah]
    vah: int

    def edges(self, k0: int, k1: int) -> tuple[float, float]:
        return self.lo + k0 * self.size, self.lo + (k1 + 1) * self.size


def profile(mh: np.ndarray, ml: np.ndarray, mv: np.ndarray, lo: float, hi: float, rows: int = K.PROFILE_ROWS,
            va: float = K.VALUE_AREA, min_cover: float = K.PROFILE_MIN_COVER) -> Profile | None:
    """Volume profile of the leg's minutes (mh, ml, mv: the minutes present; mv NaN = no volume known). Each minute's
    volume is spread evenly over the rows its high-low range touches. None when < min_cover of the minutes have a
    volume or there is no volume at all."""
    ok = np.isfinite(mh) & np.isfinite(ml)
    mh, ml, mv = mh[ok], ml[ok], mv[ok]
    if not len(mh) or not hi > lo:
        return None
    has = np.isfinite(mv)
    if has.mean() < min_cover:
        return None
    v = np.where(has & (mv > 0), mv, 0.0)
    if not v.sum() > 0:
        return None
    size = (hi - lo) / rows
    k0 = np.clip(np.floor((ml - lo) / size).astype(np.int64), 0, rows - 1)
    k1 = np.clip(np.floor((mh - lo) / size).astype(np.int64), 0, rows - 1)
    vol = np.zeros(rows)
    span = k1 - k0 + 1
    per = v / span
    # spread: add per to every row k0..k1 (difference array)
    diff = np.zeros(rows + 1)
    np.add.at(diff, k0, per)
    np.add.at(diff, k1 + 1, -per)
    vol = np.cumsum(diff)[:rows]
    vol[np.abs(vol) < 1e-9 * v.sum()] = 0.0
    top = vol.max()
    cand = np.flatnonzero(vol >= top * (1 - 1e-12))
    mid = (rows - 1) / 2
    poc = int(min(cand, key=lambda k: (abs(k - mid), k)))
    tot, inside, a, z = vol.sum(), vol[poc], poc, poc
    while inside < va * tot - 1e-12 and (a > 0 or z < rows - 1):
        up = vol[z + 1] if z < rows - 1 else -1.0
        dn = vol[a - 1] if a > 0 else -1.0
        if up >= dn:
            z += 1
            inside += vol[z]
        else:
            a -= 1
            inside += vol[a]
    return Profile(vol, lo, size, poc, a, z)


def lvn_zone(p: Profile, d: int, frac: float = K.LVN_FRAC, edge: int = K.LVN_EDGE) -> tuple[float, float] | None:
    """The leg's LVN zone: the highest (bullish leg) / lowest (bearish) contiguous run of inner rows whose volume is
    < frac of the POC row's. None when there is no inner LVN."""
    rows = len(p.vol)
    low = p.vol < frac * p.vol[p.poc]
    idx = [k for k in range(edge, rows - edge) if low[k]]
    if not idx:
        return None
    runs, cur = [], [idx[0]]
    for k in idx[1:]:
        if k == cur[-1] + 1:
            cur.append(k)
        else:
            runs.append(cur)
            cur = [k]
    runs.append(cur)
    r = runs[-1] if d > 0 else runs[0]
    return p.edges(r[0], r[-1])


# ------------------------------------------------------------------------------------------------ FVG and OB (5-minute)
def fvgs(b: Bars, a: int, z: int, d: int) -> list:
    """FVGs of direction d whose three bars lie in [a, z]: bullish L[i] > H[i-2] -> (H[i-2], L[i]); bearish mirror."""
    out = []
    for i in range(max(a + 2, 2), z + 1):
        if d > 0 and b.l[i] > b.h[i - 2]:
            out.append((float(b.h[i - 2]), float(b.l[i])))
        elif d < 0 and b.h[i] < b.l[i - 2]:
            out.append((float(b.h[i]), float(b.l[i - 2])))
    return out


def order_blocks(b: Bars, s: Structure, a: int, z: int, d: int, lookback: int = K.OB_LOOKBACK) -> list:
    """Clean OBs of direction d inside [a, z] (PREREG 4): for each internal break of direction d at bar k in [a, z], the
    last opposite-colour candle among the `lookback` bars before k (and >= a); full range; clean = no later bar up to z
    traded back into it."""
    out = []
    for k in range(a, z + 1):
        if s.brk[k] != d:
            continue
        m = -1
        for j in range(k - 1, max(a, k - lookback) - 1, -1):
            if (d > 0 and b.c[j] < b.o[j]) or (d < 0 and b.c[j] > b.o[j]):
                m = j
                break
        if m < 0:
            continue
        lo, hi = float(b.l[m]), float(b.h[m])
        if k < z:
            if d > 0 and b.l[k + 1:z + 1].min() <= hi:
                continue
            if d < 0 and b.h[k + 1:z + 1].max() >= lo:
                continue
        out.append((lo, hi))
    return out


def overlaps(z1, z2) -> bool:
    return z1[0] <= z2[1] and z2[0] <= z1[1]


# ------------------------------------------------------------------------------------------------ the setup walk
@dataclass
class Cand:
    di: int               # session of the signal
    s: int                # signal row (5-minute bar's last row); the fill is from s + 1
    side: int             # +1 long / CE, -1 short / PE
    leg: int              # leg id (one trade per leg per variant)
    zone: str             # 'poc' / 'lvn'
    conf: bool            # OB on POC / FVG on LVN
    htf: bool             # 1-hour structure agrees
    stop: float
    st_tgt: float
    zlo: float
    zhi: float
    act_di: int = -1      # session the leg became active in


def leg_zones(leg: Leg, M: np.ndarray, V: np.ndarray, b5: Bars, s5: Structure) -> dict:
    """POC and LVN zones of one leg with their confluence flags: {'poc': (lo, hi, conf), 'lvn': (lo, hi, conf)}."""
    nd = M.shape[0]
    f0, f1 = leg.t0, leg.t1
    mh = M[:, :, H].reshape(-1)[f0:f1 + 1]
    ml = M[:, :, L].reshape(-1)[f0:f1 + 1]
    mv = V.reshape(-1)[f0:f1 + 1]
    assert mh.shape[0] <= nd * K.NROWS
    p = profile(mh, ml, mv, leg.lo, leg.hi)
    if p is None:
        return {}
    a = int(np.searchsorted(b5.t, f0, side="left"))
    z = int(np.searchsorted(b5.t, f1, side="right") - 1)
    out = {}
    pz = p.edges(p.poc, p.poc)
    obs = order_blocks(b5, s5, a, z, leg.dir) if z >= a else []
    out["poc"] = (pz[0], pz[1], any(overlaps(pz, ob) for ob in obs))
    lz = lvn_zone(p, leg.dir)
    if lz is not None:
        fv = fvgs(b5, a, z, leg.dir) if z >= a else []
        out["lvn"] = (lz[0], lz[1], any(overlaps(lz, g) for g in fv))
    out["_profile"] = p
    return out


def candidates(M: np.ndarray, V: np.ndarray, zones=K.ZONES) -> tuple[list, dict]:
    """All entry candidates of one instrument (PREREG 5), in time order, and some counts.
    M: (nd, 375, 4) minute O H L C; V: (nd, 375) the volume source (NaN = unknown)."""
    b5, b15, b60 = make_bars(M, K.TF_EXEC), make_bars(M, K.TF_STRUCT), make_bars(M, K.TF_HTF)
    s5, s15, s60 = structure(b5), structure(b15), structure(b60)
    lg = sorted(legs(b15, s15), key=lambda x: x.active_t)
    st15_5 = state_at(b15, s15, b5.t)
    st60_5 = state_at(b60, s60, b5.t)
    out, cnt = [], dict(legs=len(lg), profiles=0, poc=0, lvn=0, poc_conf=0, lvn_conf=0)
    for li, leg in enumerate(lg):
        nxt = lg[li + 1].active_t if li + 1 < len(lg) else 1 << 62
        zs = leg_zones(leg, M, V, b5, s5)
        leg.zones = zs
        if not zs:
            continue
        cnt["profiles"] += 1
        d = leg.dir
        j0 = int(np.searchsorted(b5.t, leg.active_t, side="right") - 1)    # the 5-minute bar closing at activation
        if j0 < 0:
            continue
        act_di = int(leg.active_t // K.NROWS)
        stop = leg.lo * (1 - K.STOP_BUFFER) if d > 0 else leg.hi * (1 + K.STOP_BUFFER)
        st_tgt = leg.hi if d > 0 else leg.lo
        for zname in zones:
            if zname not in zs:
                continue
            zlo, zhi, conf = zs[zname]
            cnt[zname] += 1
            cnt[zname + "_conf"] += int(conf)
            edge = zhi if d > 0 else zlo
            if not ((d > 0 and b5.c[j0] > edge) or (d < 0 and b5.c[j0] < edge)):
                continue
            for j in range(j0 + 1, len(b5)):
                if b5.t[j] >= nxt or b5.di[j] > act_di + 1:
                    break
                if (d > 0 and b5.c[j] < leg.lo) or (d < 0 and b5.c[j] > leg.hi):
                    break
                if st15_5[j] != d:
                    break
                if j - 1 <= j0:
                    continue
                touched = b5.l[j - 1] <= zhi if d > 0 else b5.h[j - 1] >= zlo
                confirm = (b5.c[j] > b5.o[j] and b5.c[j] > zhi) if d > 0 else (b5.c[j] < b5.o[j] and b5.c[j] < zlo)
                if touched and confirm and K.SIG_FIRST <= b5.rend[j] <= K.SIG_LAST:
                    out.append(Cand(int(b5.di[j]), int(b5.rend[j]), d, li, zname, bool(conf), bool(st60_5[j] == d),
                                    float(stop), float(st_tgt), float(zlo), float(zhi), act_di))
    out.sort(key=lambda c: (c.di, c.s, c.zone))
    return out, cnt
