"""R94 idea 3: selling MCX options for theta, defined risk first (PREREG.md 3.3, 4.2, 5-7; notes NOTES_idea3.md N3.1).

Research only, paper only. Nothing here sells an option live.

Structures (legs by offset at the entry minute, then held as fixed (segment, side, strike) contracts):
    IC  iron condor        short C+1, P-1; long C+3, P-3
    IF  iron fly           short C0, P0;   long C+2, P-2
    NS  naked strangle     short C+1, P-1  (labelled comparison, research only)
    CC  covered call       long 1 future (the book's spot) + short C+1
Horizons: D1 (17:15 -> 17:15 next session), CY (17:15 ten sessions before the option expiry -> 17:15 the session
before it). Every trade: hard stop, profit lock, take profit (not CC), time exit.

    python -m research.hunt.r94_mcx_advanced.options design     # design cells -> results/design_idea3.json
    python -m research.hunt.r94_mcx_advanced.options holdout    # the ONE holdout read (parent only, after BH)
"""
from __future__ import annotations

import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r94_mcx_advanced import common as C

BOOKS = ("CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM", "CRUDEOILM", "NATGASMINI")
CC_BOOKS = ("CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM")
STRUCTS = {
    "IC": [(0, 1, -1), (1, -1, -1), (0, 3, 1), (1, -3, 1)],
    "IF": [(0, 0, -1), (1, 0, -1), (0, 2, 1), (1, -2, 1)],
    "NS": [(0, 1, -1), (1, -1, -1)],
    "CC": [(0, 1, -1)],
}
HORIZONS = ("D1", "CY")
ENTRY_T, ENTRY_LAST = 17 * 60 + 15, 17 * 60 + 45
CY_SESSIONS = 10
PRINT_WIN_MIN = 4               # a leg printed in j-4 .. j (5 minutes up to the entry minute)
STALE_MIN = 60
IV_LOOKBACK, IV_MIN_VALID, IV_HIGH = 40, 30, 60.0
STOP_X, LOCK_AT, LOCK_FLOOR, LOCK_TRAIL, TP_AT = 1.0, 0.50, 0.25, 0.25, 0.80
CC_STOP, CC_LOCK_AT, CC_LOCK_FLOOR, CC_LOCK_TRAIL = 0.01, 0.005, 0.0025, 0.0025

DESIGN = (date(2025, 8, 5), date(2026, 3, 31))
HOLDOUT = (date(2026, 4, 1), date(2026, 10, 19))
DESIGN_FOLDS = [(date(2025, 8, 5), date(2025, 10, 31)), (date(2025, 11, 1), date(2025, 12, 31)),
                (date(2026, 1, 1), date(2026, 2, 28)), (date(2026, 3, 1), date(2026, 3, 31))]


# ------------------------------------------------------------------------------------------------ book
class Book:
    """One option book on its minute grid (minutes with a near-future spot).

    ts, F (spot), tod, day (datetime64 day), days (sorted unique), dfirst / dlast (grid index range of each day),
    exp_days, seg (option expiry segment per minute: changes the session after an expiry day), K[(cp, off)] (strike
    rows present that minute, NaN if none), has_side[cp], iv0 (ATM IV, mean of C0 and P0), legs[(seg, cp, strike)] =
    (grid indices, closes) of printed, unflagged minutes."""

    def __init__(self, sym: str, raw: pd.DataFrame, exp_days=None, flag: bool = True):
        self.sym = sym
        raw = raw[raw.offset.between(-3, 3)].copy()
        sp = raw[raw.spot > 0].groupby("ts").spot.median().sort_index()
        self.ts = sp.index.values.astype("datetime64[ns]")
        self.F = sp.values.astype(float)
        t = pd.DatetimeIndex(self.ts)
        self.tod = (t.hour * 60 + t.minute).values.astype(np.int32)
        self.day = t.normalize().values
        self.days = np.unique(self.day)
        b = np.r_[0, np.nonzero(self.day[1:] != self.day[:-1])[0] + 1, len(self.ts)]
        self.dfirst, self.dlast = b[:-1].astype(int), (b[1:] - 1).astype(int)
        n = len(self.ts)
        pos = pd.Series(np.arange(n), index=pd.DatetimeIndex(self.ts))
        raw = raw[raw.ts.isin(pos.index)]
        raw["i"] = pos.loc[raw.ts].values
        raw["cp"] = np.where(raw.side.astype(str) == "C", 0, 1).astype(np.int8)
        self.K, self.has_side = {}, {0: np.zeros(n, bool), 1: np.zeros(n, bool)}
        for (cp, k), g in raw.groupby(["cp", "offset"]):
            a = np.full(n, np.nan)
            a[g.i.values] = g.strike.values
            self.K[(int(cp), int(k))] = a
            self.has_side[int(cp)][g.i.values] = True
        for cp in (0, 1):
            for k in range(-3, 4):
                self.K.setdefault((cp, k), np.full(n, np.nan))
        # ATM straddle and IV per minute (offset 0 rows)
        a0 = raw[raw.offset == 0]
        cl = a0.pivot_table(index="i", columns="cp", values="close", aggfunc="last")
        strad = np.full(n, np.nan)
        if 0 in cl and 1 in cl:
            strad[cl.index.values] = (cl[0] + cl[1]).values
        self.iv0 = np.full(n, np.nan)
        if "iv" in a0:
            iv = a0[a0.iv > 0].pivot_table(index="i", columns="cp", values="iv", aggfunc="last")
            if 0 in iv and 1 in iv:
                self.iv0[iv.index.values] = ((iv[0] + iv[1]) / 2).values
        if exp_days is None:
            from research.hunt.r53.mcx.data import detect_expiries
            exp_days = detect_expiries(self.day, strad)
        self.exp_days = np.array(sorted(pd.to_datetime(list(exp_days)).normalize().values), dtype="datetime64[ns]")
        self.seg = np.searchsorted(self.exp_days, self.day, side="left").astype(np.int32)
        # printed, unflagged legs per contract (segment, side, strike)
        P = raw[(raw.volume > 0) & (raw.close > 0)].copy()
        if flag and len(P):
            from research.hunt.r42.spikes import flag_spikes
            fl = flag_spikes(P[["ts", "side", "strike", "close", "high", "low"]])
            P = P[~fl.f1.reindex(P.index).fillna(False).astype(bool).values]
        P["seg"] = self.seg[P.i.values]
        P["k"] = np.round(P.strike.values.astype(float), 4)
        P = P.sort_values(["seg", "cp", "k", "i"]).drop_duplicates(["seg", "cp", "k", "i"], keep="last")
        self.legs = {}
        for key, g in P.groupby(["seg", "cp", "k"], sort=False):
            self.legs[(int(key[0]), int(key[1]), float(key[2]))] = (g.i.values.astype(np.int64),
                                                                   g.close.values.astype(float))
        self.iv_ref, self.iv_pct = self._iv_regime()

    # ---- days
    def day_index(self, d) -> int:
        d = np.datetime64(pd.Timestamp(d).normalize(), "ns")
        i = int(np.searchsorted(self.days, d))
        return i if i < len(self.days) and self.days[i] == d else -1

    def minutes(self, di: int) -> np.ndarray:
        return np.arange(self.dfirst[di], self.dlast[di] + 1)

    def first_at(self, di: int, tmin: int, until: int | None = None) -> int:
        ix = self.minutes(di)
        ok = ix[self.tod[ix] >= tmin]
        if until is not None:
            ok = ok[self.tod[ok] <= until]
        return int(ok[0]) if len(ok) else -1

    def exp_index(self) -> list[int]:
        return [i for i in (self.day_index(d) for d in self.exp_days) if i >= 0]

    # ---- IV regime (N3.1 item 1)
    def _iv_regime(self):
        nd = len(self.days)
        ref = np.full(nd, np.nan)
        for di in range(nd):
            j = self.first_at(di, ENTRY_T)
            if j < 0:
                continue
            if np.isfinite(self.iv0[j]):
                ref[di] = self.iv0[j]
                continue
            lo = self.dfirst[di]
            for q in range(j - 1, lo - 1, -1):
                if self.ts[j] - self.ts[q] > np.timedelta64(5, "m"):
                    break
                if np.isfinite(self.iv0[q]):
                    ref[di] = self.iv0[q]
                    break
        pct = np.full(nd, np.nan)
        for di in range(IV_LOOKBACK, nd):
            cur = ref[di]
            prior = ref[di - IV_LOOKBACK:di]
            prior = prior[np.isfinite(prior)]
            if np.isfinite(cur) and len(prior) >= IV_MIN_VALID:
                pct[di] = 100.0 * (np.mean(prior < cur) + 0.5 * np.mean(prior == cur))
        return ref, pct

    # ---- leg prices
    def last_print(self, seg: int, cp: int, k: float, J: np.ndarray):
        """Last printed close at or before each grid index in J, and that print's grid index (-1 if none)."""
        lg = self.legs.get((int(seg), int(cp), round(float(k), 4)))
        if lg is None:
            return np.full(len(J), np.nan), np.full(len(J), -1, dtype=np.int64)
        idx, cl = lg
        p = np.searchsorted(idx, J, side="right") - 1
        ok = p >= 0
        return np.where(ok, cl[np.clip(p, 0, None)], np.nan), np.where(ok, idx[np.clip(p, 0, None)], -1)

    def in_window(self, cp: int, k: float, J: np.ndarray) -> np.ndarray:
        S = np.column_stack([self.K[(cp, o)][J] for o in range(-3, 4)])
        hit = np.isclose(S, k, rtol=0, atol=1e-6).any(axis=1)
        return hit | ~self.has_side[cp][J]

    def value(self, seg: int, cp: int, k: float, J: np.ndarray, short: bool, long_mode: str = "convex"):
        """Leg value per minute (N3.1 item 5, A3.1): last print in window; out of window short = intrinsic + the window
        edge's time value (the no-arbitrage upper bound), long = the convexity lower bound (A3.1) or intrinsic only
        (long_mode "intrinsic", PREREG as registered). Returns value, out-of-window mask, stale mask,
        count of out-of-window minutes whose edge never printed."""
        px, last = self.last_print(seg, cp, k, J)
        inw = self.in_window(cp, k, J)
        stale = inw & ((last < 0) | ((self.ts[J] - self.ts[np.clip(last, 0, None)]) >= np.timedelta64(STALE_MIN, "m")))
        v = px.copy()
        oow = ~inw
        miss = 0
        if oow.any():
            Fo = self.F[J[oow]]
            intr = np.maximum(0.0, Fo - k) if cp == 0 else np.maximum(0.0, k - Fo)
            if not short and long_mode == "intrinsic":
                v[oow] = intr                                   # PREREG 3.3 as registered (loose lower bound)
            elif not short:
                # A3.1: convexity lower bound from the two nearest in-window strikes of the same side
                out = intr.copy()
                for n, m in enumerate(J[oow]):
                    avail = np.array([self.K[(cp, o)][m] for o in range(-3, 4)])
                    avail = np.sort(avail[np.isfinite(avail)])
                    if len(avail) < 2:
                        miss += 1
                        continue
                    k1, k2 = (avail[-2], avail[-1]) if k > avail[-1] else (avail[0], avail[1])
                    p1, _ = self.last_print(seg, cp, k1, np.array([m]))
                    p2, _ = self.last_print(seg, cp, k2, np.array([m]))
                    if not (np.isfinite(p1[0]) and np.isfinite(p2[0])):
                        miss += 1
                        continue
                    ke, pe = (k2, p2[0]) if k > avail[-1] else (k1, p1[0])
                    slope = (p2[0] - p1[0]) / (k2 - k1)
                    slope = min(0.0, max(-1.0, slope)) if cp == 0 else min(1.0, max(0.0, slope))     # A3.2 item 5
                    lb = pe + slope * (k - ke)
                    ub = pe + (max(0.0, ke - k) if cp == 0 else max(0.0, k - ke))   # the short-leg upper bound
                    out[n] = min(max(out[n], lb, 0.0), max(ub, out[n]))
                v[oow] = out
            else:
                out = intr.copy()
                for n, m in enumerate(J[oow]):
                    avail = np.array([self.K[(cp, o)][m] for o in range(-3, 4)])
                    avail = avail[np.isfinite(avail)]
                    if not len(avail):
                        miss += 1
                        continue
                    edge = avail.max() if k > avail.max() else avail.min()
                    ep, _ = self.last_print(seg, cp, edge, np.array([m]))
                    if not np.isfinite(ep[0]):
                        miss += 1
                        continue
                    ei = max(0.0, self.F[m] - edge) if cp == 0 else max(0.0, edge - self.F[m])
                    out[n] += max(0.0, ep[0] - ei)
                v[oow] = out
        return v, oow, stale, miss


# ------------------------------------------------------------------------------------------------ simulator
def _hs_vec(book: str, v: np.ndarray, tod: np.ndarray, off: int) -> np.ndarray:
    a, b = C.OPT_HALF[C.FAMILY[book]]
    rate = np.where(tod >= 17 * 60, a, b) * (1.0 + 0.5 * abs(int(off)))
    return np.maximum(C.OPT_TICK[book], rate * np.maximum(v, 0.0))


def try_entry(bk: Book, struct: str, di: int):
    """First candidate minute 17:15 .. 17:45 of day di where every leg printed in its last 5 minutes (N3.1 item 8).
    Returns (j, legs [(cp, off, sign, strike, price)]) or (None, reason)."""
    j0 = bk.first_at(di, ENTRY_T, ENTRY_LAST)
    if j0 < 0:
        return None, "no_minute"
    cands = [j for j in bk.minutes(di) if ENTRY_T <= bk.tod[j] <= ENTRY_LAST]
    for j in cands:
        legs, ok = [], True
        for cp, off, sg in STRUCTS[struct]:
            k = bk.K[(cp, off)][j]
            if not np.isfinite(k):
                ok = False
                break
            px, last = bk.last_print(bk.seg[j], cp, k, np.array([j]))
            if last[0] < 0 or bk.ts[j] - bk.ts[last[0]] > np.timedelta64(PRINT_WIN_MIN, "m"):
                ok = False
                break
            legs.append((cp, off, sg, float(k), float(px[0])))
        if ok:
            return j, legs
    return None, "no_print"


def simulate(bk: Book, struct: str, di_in: int, di_out: int, valuation: str = "A1"):
    """One trade entered on grid day di_in, time exit on grid day di_out. Returns (trade dict, None) or
    (None, skip reason). valuation "A1" = NOTES_idea3 A3.1 (convexity bound for long legs out of window, each IC / IF
    vertical capped at its width); "prereg" = PREREG 3.3 as registered (long legs out of window at intrinsic)."""
    book = bk.sym
    u = C.OPT_UNITS[book]
    j, legs = try_entry(bk, struct, di_in)
    if j is None:
        return None, legs
    jx = bk.first_at(di_out, ENTRY_T)
    if jx < 0:
        jx = int(bk.dlast[di_out])
    if jx <= j:
        return None, "no_exit"
    seg = int(bk.seg[j])
    if int(bk.seg[jx]) != seg:
        return None, "cross_expiry"
    J = np.arange(j + 1, jx + 1)
    tod_e, todJ = int(bk.tod[j]), bk.tod[J]
    credit_pts = 0.0
    fills, marks, mids, hss, oow_n, stale_n, miss_n = [], [], [], [], 0, 0, 0
    close_cost = np.zeros(len(J))          # rupees to close the option legs at each minute (fills)
    for cp, off, sg, k, p in legs:
        hs = C.opt_half_spread(book, p, tod_e, off)
        f = p - hs if sg < 0 else p + hs
        fills.append(f)
        credit_pts += f if sg < 0 else -f
        v, oow, stale, miss = bk.value(seg, cp, k, J, short=sg < 0,
                                       long_mode="convex" if valuation == "A1" else "intrinsic")
        v = pd.Series(v).ffill().fillna(p).values           # never NaN after a printed entry
        h = _hs_vec(book, v, todJ, off)
        cl = v + h if sg < 0 else np.maximum(0.0, v - h)
        close_cost += (cl if sg < 0 else -cl) * u
        marks.append(cl)
        mids.append(v)
        hss.append(h)
        oow_n += int(oow.sum())
        stale_n += int(stale.sum())
        miss_n += miss
    credit = credit_pts * u
    vert = None
    if valuation == "A1" and struct in ("IC", "IF"):
        wl = max(abs(legs[2][3] - legs[0][3]), abs(legs[3][3] - legs[1][3]))
        if credit >= wl * u:
            return None, "arb_credit"                     # A3.2 item 6: a quote no order could fill
        # A3.1: a vertical (short leg i, its wing i + 2) never costs more than its width (+ both half-spreads) to close
        vert = []
        close_cost = np.zeros(len(J))
        for a, b in ((0, 2), (1, 3)):
            w = abs(legs[b][3] - legs[a][3])
            close_cost += (np.clip(mids[a] - mids[b], 0.0, w) + hss[a] + hss[b]) * u      # A3.2 item 4
            vert.append((a, b, w))
        # ... and the whole IC / IF never more than its larger width (+ every half-spread): only one side pays at expiry
        wmax = max(w for _, _, w in vert)
        close_cost = np.minimum(close_cost, (wmax + sum(hss)) * u)
    fut = None
    if struct == "CC":
        fhs = C.fut_half_spread(book, bk.F[j])
        f_in = bk.F[j] + fhs
        fhsJ = np.array([C.fut_half_spread(book, x) for x in bk.F[J]])
        fut_pnl = (bk.F[J] - fhsJ - f_in) * u
        pnl = fut_pnl + credit - close_cost
        notional = bk.F[j] * u
        stop_amt = CC_STOP * notional
        lock_at, floor0, trail = CC_LOCK_AT * notional, CC_LOCK_FLOOR * notional, CC_LOCK_TRAIL * notional
        tp_at = np.inf
        fut = (f_in, fhsJ)
    else:
        if credit <= 0:
            return None, "no_credit"
        pnl = credit - close_cost
        notional = float("nan")
        stop_amt = STOP_X * credit
        lock_at, floor0, trail, tp_at = LOCK_AT * credit, LOCK_FLOOR * credit, LOCK_TRAIL * credit, TP_AT * credit
    peak = np.maximum.accumulate(pnl)
    locked = peak >= lock_at
    floor = np.maximum(floor0, peak - trail)
    trig_stop = pnl <= -stop_amt
    trig_tp = pnl >= tp_at
    trig_lock = locked & (pnl <= floor)
    any_t = trig_stop | trig_tp | trig_lock
    if any_t.any():
        x = int(np.argmax(any_t))
        reason = "stop" if trig_stop[x] else ("tp" if trig_tp[x] else "lock")
    else:
        x, reason = len(J) - 1, "time"
    jout = int(J[x])
    # rupees: fills and charges
    net = float(pnl[x])
    gross = 0.0
    charges = 0.0
    est_ch = 0.0
    for (cp, off, sg, k, p), f, cl, v in zip(legs, fills, marks, mids):
        if vert is None:
            gross += ((p - v[x]) if sg < 0 else (v[x] - p)) * u
        if sg < 0:
            charges += C.opt_leg_charges(cl[x] * u, f * u)
        else:
            charges += C.opt_leg_charges(f * u, cl[x] * u)
        est_ch += C.opt_leg_charges(p * u, p * u)
    if vert is not None:
        wmax = max(w for _, _, w in vert)
        mid_close = min(sum(min(max(mids[a][x] - mids[b][x], 0.0), w) for a, b, w in vert), wmax)
        gross += (sum(legs[a][4] - legs[b][4] for a, b, _ in vert) - mid_close) * u
    if fut is not None:
        f_in, fhsJ = fut
        f_out = bk.F[jout] - fhsJ[x]
        gross += (bk.F[jout] - bk.F[j]) * u
        charges += C.fut_leg_charges(f_in * u, f_out * u)
        est_ch += C.fut_leg_charges(bk.F[j] * u, bk.F[j] * u)
    net -= charges
    strikes = {(cp, off): k for cp, off, sg, k, p in legs}
    if struct == "IC":
        width = max(strikes[(0, 3)] - strikes[(0, 1)], strikes[(1, -1)] - strikes[(1, -3)])
        maxloss = width * u - credit
        margin, risk = maxloss, min(credit, maxloss) + est_ch
    elif struct == "IF":
        width = max(strikes[(0, 2)] - strikes[(0, 0)], strikes[(1, 0)] - strikes[(1, -2)])
        maxloss = width * u - credit
        margin, risk = maxloss, min(credit, maxloss) + est_ch
    elif struct == "NS":
        margin, risk = C.FUT_MARGIN[book], credit + est_ch
    else:
        margin, risk = C.FUT_MARGIN[book], stop_amt + est_ch
    di = di_in
    return {
        "entry_ts": pd.Timestamp(bk.ts[j]), "exit_ts": pd.Timestamp(bk.ts[jout]), "net": net, "gross": float(gross),
        "charges": float(charges), "risk_rs": float(risk), "margin_rs": float(margin), "credit": float(credit),
        "reason": reason, "stale_min": stale_n, "oow_min": oow_n, "edge_missing": miss_n,
        "iv_pct": float(bk.iv_pct[di]) if np.isfinite(bk.iv_pct[di]) else float("nan"),
        "wait_min": int((bk.ts[j] - bk.ts[bk.first_at(di, ENTRY_T)]) / np.timedelta64(1, "m")),
        "notional": float(notional), "book": book, "struct": struct,
    }, None


def entry_plan(bk: Book, horizon: str, part: tuple) -> list[tuple[int, int]]:
    """(entry day, exit day) grid-day pairs whose entry day lies in the window part = (first, last date)."""
    lo, hi = np.datetime64(pd.Timestamp(part[0]), "ns"), np.datetime64(pd.Timestamp(part[1]), "ns")
    ex = set(bk.exp_index())
    out = []
    if horizon == "D1":
        for di in range(len(bk.days) - 1):
            if di in ex or (di + 1) in ex:
                continue
            out.append((di, di + 1))
    else:
        for e in sorted(ex):
            if e - CY_SESSIONS >= 0:
                out.append((e - CY_SESSIONS, e - 1))
    return [(a, b) for a, b in out if lo <= bk.days[a] <= hi]


def run_book(bk: Book, struct: str, horizon: str, part: tuple, valuation: str = "A1"):
    trades, skips = [], {}
    for a, b in entry_plan(bk, horizon, part):
        t, why = simulate(bk, struct, a, b, valuation)
        if t is None:
            skips[why] = skips.get(why, 0) + 1
        else:
            t["horizon"] = horizon
            trades.append(t)
    return trades, skips


# ------------------------------------------------------------------------------------------------ cells
def cell_ids() -> list[str]:
    out = []
    for book in BOOKS:
        for s in ("IC", "IF", "NS") + (("CC",) if book in CC_BOOKS else ()):
            for h in HORIZONS:
                for v in ("ALL", "HIGH"):
                    out.append(f"o-{s}-{book}-{h}-{v}")
    return out


def subset_twin_p(pool: list[dict], chosen: list[dict], n: int = C.N_TWINS, seed: int = C.SEED) -> float:
    pool_x = np.array([t["net"] for t in pool], float)
    k = len(chosen)
    if k == 0 or len(pool_x) < k:
        return float("nan")
    rng = np.random.default_rng(seed)
    sums = np.array([pool_x[rng.choice(len(pool_x), k, replace=False)].sum() for _ in range(n)])
    return C.twin_p(float(sum(t["net"] for t in chosen)), sums)


def _extra(trades: list[dict], attempts: int, skips: dict) -> dict:
    if not trades:
        return {"attempts": attempts, "skips": skips}
    x = np.sort([t["net"] for t in trades])
    k = max(1, int(np.ceil(0.05 * len(x))))
    marg = np.median([t["margin_rs"] for t in trades])
    reasons = pd.Series([t["reason"] for t in trades]).value_counts().to_dict()
    return {
        "attempts": attempts, "skips": skips, "exit_reasons": reasons,
        "credit_mean": float(np.mean([t["credit"] for t in trades])),
        "charges_mean": float(np.mean([t["charges"] for t in trades])),
        "stale_min": int(sum(t["stale_min"] for t in trades)),
        "oow_min": int(sum(t["oow_min"] for t in trades)),
        "trades_with_oow": int(sum(t["oow_min"] > 0 for t in trades)),
        "edge_missing": int(sum(t["edge_missing"] for t in trades)),
        "tail_worst5pct_mean": float(x[:k].mean()), "tail_n": k,
        "return_on_median_margin": float(x.sum() / marg) if marg > 0 else None,
        "wait_min_mean": float(np.mean([t["wait_min"] for t in trades])),
    }


def cells(part: str, books=BOOKS) -> dict:
    win = DESIGN if part == "design" else HOLDOUT
    out = {}
    for book in books:
        bk = load_book(book)
        for s in ("IC", "IF", "NS") + (("CC",) if book in CC_BOOKS else ()):
            for h in HORIZONS:
                tr, skips = run_book(bk, s, h, win)
                # PREREG-as-registered valuation, reported next to the amended one (A3.1); only IC / IF differ
                tr0 = run_book(bk, s, h, win, "prereg")[0] if s in ("IC", "IF") else tr
                att = len(entry_plan(bk, h, win))
                folds = C.fold_nets(tr, DESIGN_FOLDS) if part == "design" else []
                p_all = C.block_bootstrap_p([t["net"] for t in tr]) if len(tr) >= 3 else float("nan")
                out[f"o-{s}-{book}-{h}-ALL"] = {
                    "trades": tr, "p": p_all if np.isfinite(p_all) else 1.0,
                    "p_detail": {"bootstrap": p_all}, "folds": folds,
                    "extra": {**_extra(tr, att, skips), "prereg_valuation": C.summary(tr0)},
                    "min_trades": 40 if h == "D1" else 10}
                pool = [t for t in tr if np.isfinite(t["iv_pct"])]
                hi = [t for t in pool if t["iv_pct"] >= IV_HIGH]
                lo = [t for t in pool if t["iv_pct"] < IV_HIGH]
                p_b = C.block_bootstrap_p([t["net"] for t in hi]) if len(hi) >= 3 else float("nan")
                p_t = subset_twin_p(pool, hi)
                p_hi = max(p_b, p_t) if np.isfinite(p_b) and np.isfinite(p_t) else float("nan")
                ex = _extra(hi, len(pool), {})
                ex["low_descriptive"] = C.summary(lo)
                ex["pool_with_pct"] = len(pool)
                ex["prereg_valuation"] = C.summary([t for t in tr0 if np.isfinite(t["iv_pct"]) and t["iv_pct"] >= IV_HIGH])
                out[f"o-{s}-{book}-{h}-HIGH"] = {
                    "trades": hi, "p": p_hi if np.isfinite(p_hi) else 1.0,
                    "p_detail": {"bootstrap": p_b, "subset_twin": p_t},
                    "folds": C.fold_nets(hi, DESIGN_FOLDS) if part == "design" else [], "extra": ex,
                    "min_trades": 40 if h == "D1" else 10}
    return out


def report(cs: dict) -> dict:
    """JSON-ready per cell: summary, p, folds, extras (no trade lists)."""
    return {k: {"summary": C.summary(v["trades"]), "p": v["p"], "p_detail": v["p_detail"], "folds": v["folds"],
                "extra": v["extra"], "min_trades": v["min_trades"]} for k, v in cs.items()}


# ------------------------------------------------------------------------------------------------ loading
def load_book(sym: str, rebuild: bool = False) -> Book:
    p = C.cache_dir() / f"idea3_book_{sym}.pkl"
    if p.exists() and not rebuild:
        with open(p, "rb") as f:
            bk = Book.__new__(Book)          # the state dict, so the cache loads under -m and as a module alike
            bk.__dict__.update(pickle.load(f))
            return bk
    from research.hunt.r53.mcx.data import lake_options
    bk = Book(sym, lake_options(sym, C.lake_dir()))
    with open(p, "wb") as f:
        pickle.dump(bk.__dict__, f, protocol=pickle.HIGHEST_PROTOCOL)
    return bk


def main(argv):
    what = argv[1] if len(argv) > 1 else "design"
    if what == "coverage":
        for s in BOOKS:
            bk = load_book(s)
            print(s, len(bk.ts), "minutes", len(bk.days), "days, expiries:", [str(d)[:10] for d in bk.exp_days])
        return
    if what == "design":
        cs = cells("design")
    elif what == "holdout":
        C.open_holdout("idea3", cell_ids())          # the ONE read: refuses a second
        cs = cells("holdout")
    else:
        raise SystemExit(f"unknown command {what}")
    with open(C.cache_dir() / f"idea3_{what}_trades.pkl", "wb") as f:
        pickle.dump({k: v["trades"] for k, v in cs.items()}, f)
    C.save(f"{what}_idea3.json", report(cs))
    for k, v in report(cs).items():
        s = v["summary"]
        print(f"{k:32s} n {s['trades']:4d} win {s['win_rate'] if s['win_rate'] is None else round(s['win_rate'], 2)} "
              f"net {s['net']:10.0f} p {v['p']:.3f}")


if __name__ == "__main__":
    main(sys.argv)
