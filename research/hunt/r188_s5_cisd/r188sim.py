"""R188 simulator (PREREG.md): S5 Trail and CISD + network, base and each lever, from the extracted bars.
Plain-English notes, 17 Nov 2026.

S5's exit is R128's convex_exit (T2-S5x1) and CISD's is R132's sim_exit (E3); each is rewritten as a minute loop with
the levers as options that are off by default, and with every option off it gives the original's numbers (parity
tests). The decision of a minute only ever reads that minute and earlier ones; a level reached in a minute acts from the
next minute.

Sides: +1 call, -1 put. Index arrays are in the side's coordinates (a put's index is mirrored, as R131 does), so
"back through the entry level" is one test for both sides.
"""
from __future__ import annotations

import math
import pickle
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r188cfg as C  # noqa: E402
import r131 as R  # noqa: E402

K, D3 = R.K, R.D3
O_, H_, L_, C_, V_, OI_ = range(6)
FILLS = D3.FILLS


# ------------------------------------------------------------------------------------------------ the lever set
@dataclass(frozen=True)
class Ex:
    """Exit options (all off = the arm's own exits)."""
    sq: int = C.APP_SQ              # square-off row (15:15 app; 15:20 research)
    be10: bool = False
    l500: bool = False
    lbe1000: bool = False
    idx: bool = False
    lock_from: float = C.LOCK_FROM  # the 60% lock's start (LK500: 500)
    st1500: bool = False


@dataclass(frozen=True)
class Var:
    name: str
    ex: Ex = field(default_factory=Ex)
    skips: tuple = ()               # entry filters: FT3PO, VIX313, EXP, ADX20
    nthr: float = 0.0               # CISD: mean EV of the side's 8 cells must be > nthr


def variant(name: str, sq: int = C.APP_SQ, nthr: float = 0.0) -> Var:
    """The base or one lever (PREREG section 3)."""
    ex = dict(sq=sq)
    if name == "BE10":
        ex["be10"] = True
    elif name == "L500":
        ex["l500"] = True
    elif name == "LBE1000":
        ex["lbe1000"] = True
    elif name == "IDX":
        ex["idx"] = True
    elif name == "LK500":
        ex["lock_from"] = 500.0
    elif name == "ST1500":
        ex["st1500"] = True
    skips = (name,) if name in C.ENTRY_LEVERS else ()
    return Var(name, Ex(**ex), skips, nthr if name == "NTHR" else 0.0)


def combine(name: str, parts: list, nthr: float, sq: int = C.APP_SQ) -> Var:
    """COMBO: the exit lever (at most one) + the entry filters (+ NTHR)."""
    ex = dict(sq=sq)
    skips, th = [], 0.0
    for p in parts:
        v = variant(p, sq, nthr)
        if p in C.EXIT_LEVERS:
            ex.update({k: getattr(v.ex, k) for k in ("be10", "l500", "lbe1000", "idx", "lock_from", "st1500")
                       if getattr(v.ex, k) != getattr(Ex(), k)})
        elif p == "NTHR":
            th = nthr
        else:
            skips.append(p)
    return Var(name, Ex(**ex), tuple(skips), th)


# ------------------------------------------------------------------------------------------------ money helpers
def vol5(a, r, lot):
    return float(np.nansum(a[max(r - 5, 0):r, V_])) / lot


def charge(buy, px, lot, d, und):
    return D3.charge(buy, px, lot, d, und)


def lock_premium(buy, lot, net, v0, d, und):
    """The raw premium whose stop sale nets `net` after both legs' charges (R123's target_stop with y = -net)."""
    return D3.target_stop(buy, lot, 0.0, -net, v0, d, und)[1]


def net_at(buy, px, lot, d, und):
    """Open net at a premium, before the sale's slippage (R186's trigger 'net')."""
    return (px - buy) * lot - charge(True, buy, lot, d, und) - charge(False, px, lot, d, und)


def step_lock(best: float) -> float | None:
    """R186's L500: +500 at +1,000, +1,000 at +1,500, ... (Rs 500 under the last Rs 500 step reached)."""
    if best < 1000.0:
        return None
    return 500.0 * math.floor((best - 1000.0) / 500.0) + 500.0


def sell(px, vol, stop):
    return float(FILLS.sell(np.array([px]), np.array([vol]), stop=stop)[0])


def result(buy, p0, raw, s, lot, d, und, r, why):
    net = (s - buy) * lot - charge(True, buy, lot, d, und) - charge(False, s, lot, d, und)
    slip = (buy - p0 + raw - s) * lot
    return dict(net=net, netx2=net - slip, exit=int(r), why=why)


# ------------------------------------------------------------------------------------------------ S5's exit
def s5_exit(a, t, last, lot, d, und, x, y, ex: Ex, ixc=None, ixo=None):
    """R128 convex_exit mode x1 with the levers. a: (375, >=5) option bars; bought at the open of t + 1; last: the
    square-off row. ixc / ixo: the index's minute closes / opens in the side's coordinates (IDX only)."""
    r0 = t + 1
    if r0 > last or not (np.isfinite(a[r0, O_]) and a[r0, O_] > 0):
        return None
    p0 = float(a[r0, O_])
    buy = float(FILLS.buy(np.array([p0]), np.array([vol5(a, r0, lot)]))[0])
    v0 = vol5(a, r0, lot)
    y_eff = 1500.0 if (ex.st1500 and y == 2000.0) else y
    sp = D3.target_stop(buy, lot, x, y_eff, v0, d, und)[1]
    cb = charge(True, buy, lot, d, und)
    be = lock_premium(buy, lot, 0.0, v0, d, und) if (ex.be10 or ex.lbe1000) else None
    dyn, dyn_why = -np.inf, None
    best_hi = -np.inf
    ix_lvl = float(ixo[r0]) if ex.idx and ixo is not None else np.nan
    idx_due = False

    def mark(px):
        s = sell(px, v0, False)
        return (s - buy) * lot - cb - charge(False, s, lot, d, und)

    peak = -np.inf
    for r in range(r0, last + 1):
        if idx_due and np.isfinite(a[r, O_]):
            raw = float(a[r, O_])
            return result(buy, p0, raw, sell(raw, vol5(a, r, lot), True), lot, d, und, r, "idx")
        if not (np.isfinite(a[r, L_]) and np.isfinite(a[r, C_])):
            continue
        lvl = sp if sp is not None else -np.inf
        why = "stop"
        if dyn > lvl:
            lvl, why = dyn, dyn_why
        if np.isfinite(lvl) and a[r, L_] <= lvl:
            raw = float(min(lvl, a[r, O_])) if np.isfinite(a[r, O_]) else float(lvl)
            return result(buy, p0, raw, sell(raw, vol5(a, r, lot), True), lot, d, und, r, why)
        m = mark(float(a[r, C_]))
        peak = max(peak, m)
        if peak >= ex.lock_from and m <= C.LOCK_KEEP * peak:
            raw = float(a[r, C_])
            return result(buy, p0, raw, sell(raw, vol5(a, r, lot), False), lot, d, und, r, "trail")
        # levels from this minute's high act from the next minute
        hi = float(a[r, H_]) if np.isfinite(a[r, H_]) else float(a[r, C_])
        best_hi = max(best_hi, net_at(buy, hi, lot, d, und))
        if ex.be10 and hi >= (1 + C.BE_X) * p0 and be is not None and be > dyn:
            dyn, dyn_why = be, "be10"
        if ex.lbe1000 and best_hi >= 1000.0 and be is not None and be > dyn:
            dyn, dyn_why = be, "lbe1000"
        if ex.l500:
            lk = step_lock(best_hi)
            if lk is not None:
                p = lock_premium(buy, lot, lk, v0, d, und)
                if p is not None and p > dyn:
                    dyn, dyn_why = p, "l500"
        if ex.idx and best_hi >= 1000.0 and ixc is not None and np.isfinite(ixc[r]) and ixc[r] < ix_lvl:
            idx_due = True
    fin = np.flatnonzero(np.isfinite(a[r0:last + 1, C_]))
    if not len(fin):
        return None
    rr = r0 + int(fin[-1])
    raw = float(a[rr, C_])
    return result(buy, p0, raw, sell(raw, vol5(a, rr, lot), False), lot, d, und, rr, "close")


# ------------------------------------------------------------------------------------------------ CISD's exit
def cisd_exit(a, r0, stop_idx, mh, ml, ex: Ex, lot, d, und, ixc=None):
    """R132 sim_exit with E3 (the index stop, the 60% lock after +Rs 1,000 on earlier minutes' highs) and the levers.
    mh / ml: the side's index minute highs / lows; ixc = (the side's index minute closes, the index open of the fill
    minute) for IDX."""
    last = ex.sq
    op = a[r0, O_]
    if not (np.isfinite(op) and op > 0):
        return None
    cv, li = R._contract_aux(a[:, :5])
    v_in = (cv[r0] - cv[max(r0 - 5, 0)]) / lot
    B = float(FILLS.buy(np.array([op]), np.array([v_in]))[0])
    be = lock_premium(B, lot, 0.0, v_in, d, und) if (ex.be10 or ex.lbe1000) else None
    ix_lvl = ixc[1] if ixc is not None else np.nan

    def sell_at(m, px, stop):
        s = sell(px, (cv[m] - cv[max(m - 5, 0)]) / lot, stop)
        net = (s - B) * lot - charge(True, B, lot, d, und) - charge(False, s, lot, d, und)
        slip = (B - op + px - s) * lot
        return net, net - slip

    peak_prev = -np.inf          # the best high of EARLIER minutes
    best_net_prev = -np.inf
    idx_due = False
    for m in range(r0, last + 1):
        o, h, lo = a[m, O_], a[m, H_], a[m, L_]
        if idx_due and np.isfinite(o):
            net, net2 = sell_at(m, float(o), True)
            return dict(net=net, netx2=net2, exit=m, why="idx")
        # the premium level from earlier minutes
        lev, why = -np.inf, None
        if ex.st1500:
            lev, why = B - 1500.0 / lot, "pstop"
        gain = (peak_prev - B) * lot
        if gain >= ex.lock_from:
            lk = B + C.LOCK_KEEP * gain / lot
            if lk > lev:
                lev, why = lk, "lock"
        if ex.be10 and peak_prev >= (1 + C.BE_X) * op and be is not None and be > lev:
            lev, why = be, "be10"
        if ex.lbe1000 and best_net_prev >= 1000.0 and be is not None and be > lev:
            lev, why = be, "lbe1000"
        if ex.l500:
            st = step_lock(best_net_prev)
            if st is not None:
                p = lock_premium(B, lot, st, v_in, d, und)
                if p is not None and p > lev:
                    lev, why = p, "l500"
        outs = []
        hit = False
        if np.isfinite(lo) and lev > -np.inf and lo <= lev:
            hit = True
            px = min(lev, o) if np.isfinite(o) else lev
            outs.append((why, sell_at(m, float(px), True)))
        if np.isfinite(ml[m]) and ml[m] <= stop_idx:
            hit = True
            rr = li[m]
            if rr >= r0:
                outs.append(("istop", sell_at(m, float(a[rr, C_]), True)))
        if outs:
            why, (net, net2) = min(outs, key=lambda z: z[1][0])
            return dict(net=net, netx2=net2, exit=m, why=why)
        if hit:                       # R132: an index stop with no option price yet goes straight to the square-off
            break
        if np.isfinite(h):
            peak_prev = max(peak_prev, float(h))
            best_net_prev = max(best_net_prev, net_at(B, float(h), lot, d, und))
        if ex.idx and best_net_prev >= 1000.0 and ixc is not None and np.isfinite(ixc[0][m]) and ixc[0][m] < ix_lvl:
            idx_due = True
    rr = li[last]
    if rr < r0:
        return None
    net, net2 = sell_at(int(rr), float(a[rr, C_]), False)
    return dict(net=net, netx2=net2, exit=int(rr), why="time")


# ------------------------------------------------------------------------------------------------ entry features
def last_ok(x, r, back=5):
    lo = max(0, r - back + 1)
    seg = x[lo:r + 1]
    ok = np.flatnonzero(np.isfinite(seg))
    return float(seg[ok[-1]]) if len(ok) else math.nan


def long_unwinding(a, dec, w=C.FT3_WIN) -> bool | None:
    """R144c's quadrant (features.contract_feats): price and OI both fell over the w minutes to `dec`. None: unknown."""
    if a is None or dec < 0:
        return None
    lo = max(0, dec - w + 1)
    c0, c1 = last_ok(a[:, C_], lo), last_ok(a[:, C_], dec)
    o0, o1 = last_ok(a[:, OI_], lo), last_ok(a[:, OI_], dec)
    if not all(v == v for v in (c0, c1, o0, o1)):
        return None
    return (c1 < c0) and (o1 < o0)


def vix_change(vix, i, dec):
    """India VIX's change today to the decision: last close at or before dec vs the previous session's last close."""
    if vix is None or i is None or i < 1:
        return math.nan
    now = last_ok(vix[i], dec)
    prev = D3._last(vix[i - 1])
    return now / prev - 1 if now == now and prev == prev and prev > 0 else math.nan


# R183's bars5 / series5 / adx, copied unchanged from claude/r183-sideways-filter (r183lib.py, 90d372c43)
N_PREV5 = 3


def bars5(idx: np.ndarray, s: int | None = None) -> np.ndarray:
    n = idx.shape[1] if s is None else max(0, min(int(s), idx.shape[1]))
    out = []
    for a in range(0, n - 4, 5):
        if a + 4 >= n:
            break
        o, h, l_, c = (idx[i, a:a + 5] for i in range(4))
        ok = np.isfinite(c)
        if not ok.any():
            continue
        fo = o[np.isfinite(o)]
        out.append((fo[0] if len(fo) else c[ok][0], np.nanmax(h) if np.isfinite(h).any() else np.nanmax(c),
                    np.nanmin(l_) if np.isfinite(l_).any() else np.nanmin(c), c[ok][-1]))
    return np.array(out, float).T.reshape(4, -1)


def series5(prev: list, today: np.ndarray, s: int) -> np.ndarray:
    parts = [bars5(p) for p in prev[-N_PREV5:]] + [bars5(today, s)]
    return np.concatenate(parts, axis=1) if parts else np.zeros((4, 0))


def adx(h: np.ndarray, l_: np.ndarray, c: np.ndarray, n: int = 14) -> float:
    k = len(c)
    if k < 2 * n + 1:
        return float("nan")
    up, dn = h[1:] - h[:-1], l_[:-1] - l_[1:]
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    ndm = np.where((dn > up) & (dn > 0), dn, 0.0)
    tr = np.maximum.reduce([h[1:] - l_[1:], np.abs(h[1:] - c[:-1]), np.abs(l_[1:] - c[:-1])])
    s_tr, s_p, s_n = tr[:n].sum(), pdm[:n].sum(), ndm[:n].sum()
    dx = []

    def _dx(a, b, t):
        if t <= 0:
            return 0.0
        pi, ni = 100 * a / t, 100 * b / t
        return 0.0 if pi + ni == 0 else 100 * abs(pi - ni) / (pi + ni)

    dx.append(_dx(s_p, s_n, s_tr))
    for i in range(n, len(tr)):
        s_tr = s_tr - s_tr / n + tr[i]
        s_p = s_p - s_p / n + pdm[i]
        s_n = s_n - s_n / n + ndm[i]
        dx.append(_dx(s_p, s_n, s_tr))
    a = float(np.mean(dx[:n]))
    for x in dx[n:]:
        a = (a * (n - 1) + x) / n
    return a


def adx_at(g, i, s) -> float:
    """ADX(14) on the index's 5-minute series: the previous 3 sessions + today's complete bars before row s."""
    if i is None or i < 1:
        return math.nan
    prev = [np.stack([g.o[k], g.h[k], g.l[k], g.c[k]]) for k in range(max(0, i - N_PREV5), i)]
    today = np.stack([g.o[i], g.h[i], g.l[i], g.c[i]])
    S = series5(prev, today, s)
    return adx(S[1], S[2], S[3]) if S.shape[1] else math.nan


def skip_by(v: Var, f: dict) -> bool:
    """Whether a variant's entry filters skip a setup with features f (unknown: never skips)."""
    for s in v.skips:
        if s == "FT3PO" and f.get("unwind") is True:
            return True
        if s == "VIX313" and f.get("vix_chg", math.nan) < C.VIX_CUT:
            return True
        if s == "EXP" and f.get("expiry"):
            return True
        if s == "ADX20" and f.get("adx", math.nan) < C.ADX_CUT:
            return True
    return False


# ------------------------------------------------------------------------------------------------ the market store
class Store:
    """Index grids, India VIX, the extracted bars and the expiry days, per index."""

    def __init__(self, unds=C.UNDS):
        self.g, self.pos, self.bars, self.wexp, self.mexp = {}, {}, {}, {}, {}
        self.vix = D3.load_grid("INDIAVIX", C.END)
        self.vpos = self.vix.pos()
        for u in unds:
            g = D3.load_grid(u, C.END)
            self.g[u], self.pos[u] = g, g.pos()
            with open(C.BARS / f"{u}.pkl", "rb") as f:
                b = pickle.load(f)
            self.bars[u], self.wexp[u], self.mexp[u] = b["bars"], b["wexp"], b["mexp"]

    def vix_rows(self, u, i):
        d = self.g[u].days[i]
        j = self.vpos.get(d)
        return None if j is None else j

    def feats(self, u, i, dec, a, entry_row) -> dict:
        d = self.g[u].days[i]
        j = self.vpos.get(d)
        return dict(unwind=long_unwinding(a, dec), expiry=d in self.wexp[u],
                    vix_chg=vix_change(self.vix.c, j, dec) if j is not None else math.nan,
                    adx=adx_at(self.g[u], i, entry_row - 1))


# ------------------------------------------------------------------------------------------------ S5 portfolio
def s5_picks(P: pd.DataFrame, st: Store) -> pd.DataFrame:
    """Every S5 decision with a pick: the best FILLABLE cell-side (contract present, a priced open at t + 1, the
    cell's window before the expiry-day / 15:20 label square-off, R123's rule) with EV >= Rs 300."""
    rows = []
    M = P[P.row.isin(C.MORNING_ROWS)]
    for r in M.itertuples(index=False):
        u = r.und
        i = st.pos[u].get(r.day)
        if i is None:
            continue
        g = st.g[u]
        c = D3._last(g.c[i][:r.row + 1])
        if not np.isfinite(c):
            continue
        k = float(D3.atm(c, C.STEP[u]))
        is_exp = r.day in st.wexp[u]
        last_lab = K.K3.EXIT_LAST_EXPIRY if is_exp else K.K3.EXIT_LAST
        best, bj = -np.inf, None
        for j in range(16):
            x_, y_, h_ = K.K3.CELLS[j // 2]
            a = st.bars[u].get(("WEEK", r.day, "C" if j % 2 == 0 else "P", k))
            ok = a is not None and r.row + 1 < C.N and np.isfinite(a[r.row + 1, O_]) and a[r.row + 1, O_] > 0 \
                and r.row + h_ <= last_lab
            ev = getattr(r, f"ev_{j}")
            if ok and np.isfinite(ev) and ev > best:
                best, bj = ev, j
        if bj is not None and best >= C.EV_MIN:
            rows.append(dict(und=u, day=r.day, row=int(r.row), j=bj, score=float(best), strike=k, i=i,
                             expiry=is_exp))
    return pd.DataFrame(rows)


def s5_run(picks: pd.DataFrame, st: Store, v: Var, entries: pd.DataFrame | None = None) -> pd.DataFrame:
    """S5 trades of one variant. entries=None: the app's portfolio (one open trade per index until its own exit,
    at most 5 a day; a filter-skipped pick holds the index until its would-be exit and counts in the cap).
    entries given (R123's saved trades): exactly those entries, no portfolio (R128's parity replay)."""
    out = []
    src = entries if entries is not None else picks.sort_values(["day", "row", "score"], ascending=[True, True, False])
    for day, gday in src.groupby("day", sort=True):
        busy, n = {}, 0
        for r in gday.itertuples(index=False):
            if entries is None and (n >= C.DAY_MAX or busy.get(r.und, -1) > r.row):
                continue
            u = r.und
            i = st.pos[u][day]
            g = st.g[u]
            k = float(D3.atm(D3._last(g.c[i][:r.row + 1]), C.STEP[u]))
            side = "C" if r.j % 2 == 0 else "P"
            a = st.bars[u].get(("WEEK", day, side, k))
            if a is None:
                continue
            x, y, _h = K.K3.CELLS[int(r.j) // 2]
            is_exp = day in st.wexp[u]
            last = C.SQ_EXPIRY if is_exp else v.ex.sq
            sg = 1 if side == "C" else -1
            res = s5_exit(a, int(r.row), last, C.LOT[u], day, u, x, y, v.ex, sg * g.c[i], sg * g.o[i])
            if res is None:
                continue
            f = st.feats(u, i, int(r.row), a, int(r.row) + 1)
            n += 1
            busy[u] = res["exit"]
            out.append(dict(und=u, day=day, row=int(r.row), entry=int(r.row) + 1, j=int(r.j), side=sg,
                            period=C.period_of(day), skipped=skip_by(v, f), **res, **f))
    T = pd.DataFrame(out)
    return T


# ------------------------------------------------------------------------------------------------ CISD portfolio
def view_ev(P: pd.DataFrame) -> dict:
    """{(und, day, row): (mean EV of the call cells, of the put cells)} (R132 r132nn.attach)."""
    ev_c = P[[f"ev_{2 * c}" for c in range(8)]].mean(axis=1).values
    ev_p = P[[f"ev_{2 * c + 1}" for c in range(8)]].mean(axis=1).values
    return {(u, d, int(r)): (a, b) for u, d, r, a, b in zip(P.und.values, P.day.values, P.row.values, ev_c, ev_p)}


def cisd_run(cands: pd.DataFrame, st: Store, views: dict, v: Var) -> pd.DataFrame:
    """CISD trades of one variant: one at a time per index BEFORE the filter (R133 / the app's Phantom); a setup the
    network or a filter refuses keeps the index busy until its simulated exit. Rows: every simulated setup with
    kept = passes the network and the variant's filters."""
    out = []
    for (u, day), gd in cands.groupby(["und", "day"], sort=True):
        i = st.pos[u].get(day)
        if i is None:
            continue
        g = st.g[u]
        blocked = day in st.wexp[u] and day in st.mexp[u]
        series = "MONTH" if day in st.wexp[u] else "WEEK"
        busy = -1
        for r in gd.sort_values(["entry", "side"]).itertuples(index=False):
            if r.why != "ok" or r.entry <= busy or blocked:
                continue
            side = "C" if r.side > 0 else "P"
            a = st.bars[u].get((series, day, side, float(r.strike))) if np.isfinite(r.strike) else None
            if a is None:
                continue
            sg = int(r.side)
            mh, ml = (g.h[i], g.l[i]) if sg > 0 else (-g.l[i], -g.h[i])
            ixc = (sg * g.c[i], sg * g.o[i][r.entry])
            res = cisd_exit(a, int(r.entry), float(r.stop), mh, ml, v.ex, C.LOT[u], day, u, ixc)
            if res is None:
                continue
            busy = res["exit"]
            vr = R132_view_row(int(r.entry))
            evs = views.get((u, day, vr)) if vr is not None else None
            ev_side = np.nan if evs is None else (evs[0] if sg > 0 else evs[1])
            f = st.feats(u, i, int(r.entry) - 1, a, int(r.entry))
            kept = bool(np.isfinite(ev_side) and ev_side > v.nthr and not skip_by(v, f))
            out.append(dict(und=u, day=day, entry=int(r.entry), side=sg, series=series, period=C.period_of(day),
                            ev_side=ev_side, kept=kept, **res, **f))
    return pd.DataFrame(out)


def R132_view_row(r0: int):
    """r132nn.view_row: the latest decision row t <= r0 - 1, at most 5 minutes old."""
    first, step, lastv = K.row(9, 19), 5, K.row(14, 54)
    if r0 - 1 < first:
        return None
    t = min(first + step * ((r0 - 1 - first) // step), lastv)
    return t if r0 - (t + 1) <= 5 else None


# ------------------------------------------------------------------------------------------------ statistics
def money(T: pd.DataFrame) -> dict:
    if T is None or not len(T):
        return dict(n=0, win=0.0, total=0.0, avg_net=0.0, avg_win=0.0, avg_loss=0.0, worst_month=0.0,
                    worst_month_name="", max_dd=0.0, total_x2=0.0, exits={}, account_ok=True, days=0)
    T = T.sort_values(["day", "entry"])
    net = T.net.values
    w = net > 0
    eq = np.cumsum(net)
    dd = float(min((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min(), 0.0))
    mo = T.assign(mo=[f"{d.year}-{d.month:02d}" for d in T.day]).groupby("mo").net.sum()
    wm = float(mo.min())
    return dict(n=int(len(T)), win=float(w.mean()), total=float(net.sum()), avg_net=float(net.mean()),
                avg_win=float(net[w].mean()) if w.any() else 0.0, avg_loss=float(net[~w].mean()) if (~w).any() else 0.0,
                worst_month=wm, worst_month_name=str(mo.idxmin()), max_dd=dd,
                total_x2=float(T.netx2.sum()), exits={k: int(x) for k, x in T.why.value_counts().items()},
                account_ok=bool(dd > C.ACCOUNT_DD and wm > C.ACCOUNT_MONTH), days=int(T.day.nunique()))


def better_risk(s: dict, b: dict) -> bool:
    return s["worst_month"] > b["worst_month"] or s["max_dd"] > b["max_dd"]


def part_gates(s: dict, b: dict) -> dict:
    return dict(net=s["total"] >= b["total"], win=s["win"] > b["win"], risk=better_risk(s, b))


def bh_q(ps: list, m_total: int = C.M_TOTAL) -> list:
    """Benjamini-Hochberg q with the earlier tests taken as p = 1 (R187's bh_q)."""
    p = np.asarray(ps, float)
    n = len(p)
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return [float(x) for x in np.minimum(q, 1.0)]
