"""R57 candidate setups (PREREG section 3) and the index-level exits, priced on one HELD contract by R51's engine.

Every setup's signal is read from the UNDERLYING index, except C4 (option chain OI: NON-DEFAULT, only in variant A4).
A candidate is the trade the plain rule would take; its label is that trade's net P&L (Rs, 1 lot, after real charges
and R17 fills). Nothing after DESIGN_END is ever priced unless the one final read has been opened (`open_final()`).
"""
from __future__ import annotations

import importlib.util
import sys
from datetime import date

import numpy as np
from numba import njit

import r57cfg as K
import engine as E
import r57data

# ------------------------------------------------------------------------------------------------ the period guard
_FINAL_OPEN = False


def open_final():
    global _FINAL_OPEN
    _FINAL_OPEN = True


def check_range(lo: date, hi: date):
    if lo < K.START:
        raise PermissionError(f"R57 trades nothing before {K.START} (asked for {lo})")
    if hi > K.DESIGN_END and not _FINAL_OPEN:
        raise PermissionError(f"R57: {hi} is in the final test (from {K.FINAL_START}); read only by `r57study.py final`")


def days_in(und: str, lo: date, hi: date) -> list:
    """Tradable sessions of an index (option data present, not its expiry day) in [lo, hi], after the guard."""
    check_range(lo, hi)
    days = r57data.load(und)
    return [d for d in sorted(days) if lo <= d <= hi and days[d].atm is not None and not days[d].expiry]


# ------------------------------------------------------------------------------------------------ exits
SOLO_LAYER = ((0.40, 0.10),)            # once +40% premium, +10% locked (with the -30% stop)
EXITS = {
    "S1": E.Exits(sq_row=K.row(14, 30)),                                            # + index stop / breakeven lock
    "S2": E.Exits(stop=0.15, target=0.30, tmax=30, sq_row=K.row(15, 15)),
    "S3": E.Exits(stop=0.30, steps=SOLO_LAYER, tmax=60),
    "S4": E.Exits(stop=0.25, target=0.50, tmax=60, steps=SOLO_LAYER),
    "C4": E.Exits(stop=0.30, steps=((0.60, 0.30),), sq_row=K.row(15, 15)),
}
MONEY = {"S1": 4, "S2": 1, "S3": 1, "S4": 1, "C4": 3}
SOURCE = {"S1": "index 1-min (SoloMidday)", "S2": "index 5-min (Donchian 20)", "S3": "index 5-min + previous day's "
          "value area (H37-10)", "S4": "index 5-min (15-min opening range)", "C4": "option chain OI (NON-DEFAULT)"}
# index exits: (stop in ATR14, breakeven earned at this many ATR14 its way (<0: no lock))
IDX_EXIT = {"S1": (0.3, 0.45)}
B_EXITS = {h: E.Exits(stop=0.25, steps=((0.30, 0.10),), tmax=h) for h in (30, 60)}
B_IDX = (0.3, -1.0)
B_TIMES = (K.row(9, 45), K.row(10, 30), K.row(12, 0), K.row(13, 30))

S1_ROW = K.row(12, 0)
S1_MIN_MINUTES = 100
S1_FIRST_BY = 1                          # the first minute by 09:16


@njit(cache=True)
def index_exit(o, h, l, c, er, side, stop_d, earn_d, end):
    """SoloMidday.walk on the index minutes from the entry minute `er` (bars starting before `end`): out when a 1-min
    close is at or beyond entry -/+ stop_d; once the index has gone earn_d its way (earned by the bars BEFORE the one
    checked; earn_d < 0: never), out when a bar touches the entry level. Returns the row to sell at (the next minute's
    open), or -1 (no index exit before `end`). The entry level is the open of `er` (else the close before it)."""
    ent = o[er]
    if not (ent == ent):
        ent = c[er - 1] if er > 0 else np.nan
    if not (ent == ent):
        return -1
    stop = ent - side * stop_d
    best = 0.0
    locked = False
    for m in range(er, end):
        if not (c[m] == c[m]):
            continue
        fav = (h[m] - ent) if side > 0 else (ent - l[m])
        if (c[m] - stop) * side <= 0:
            return m + 1
        if locked and ((side > 0 and l[m] <= ent) or (side < 0 and h[m] >= ent)):
            return m + 1
        if fav > best:
            best = fav
        if earn_d >= 0 and best >= earn_d - 1e-9:
            locked = True
    return -1


def forced_row(D, er: int, side: int, spec, end: int) -> int:
    if spec is None or not np.isfinite(D.atr):
        return -1
    stop_a, earn_a = spec
    a = D.idx
    return int(index_exit(a[0], a[1], a[2], a[3], int(er), int(side), stop_a * D.atr,
                          earn_a * D.atr if earn_a >= 0 else -1.0, int(end)))


def sgn(side: str) -> int:
    return 1 if side == "C" else -1


# ------------------------------------------------------------------------------------------------ S1 SoloMidday
def solo_atm(und: str, px: float) -> float:
    step = K.STEP[und]
    return float(np.floor(px / step + 0.5) * step)


def s1_signal(und: str, D):
    """SoloMidday.signal at 12:00 on a day store; None or dict(side, strength, index, strikes)."""
    a = D.idx
    if not np.isfinite(D.atr) or D.atr <= 0:
        return None
    pre = a[:, :S1_ROW]
    ok = np.flatnonzero(np.isfinite(pre[3]))
    if len(ok) < S1_MIN_MINUTES or ok[0] > S1_FIRST_BY or ok[-1] != S1_ROW - 1:
        return None
    op = pre[0, ok[0]]
    c = pre[3, S1_ROW - 1]
    hi, lo = np.nanmax(pre[1]), np.nanmin(pre[2])
    mv = c - op
    if abs(mv) < 0.5 * D.atr:
        return None
    s = 1 if mv > 0 else -1
    if hi <= lo:
        return None
    pos = (hi - c) / (hi - lo) if s > 0 else (c - lo) / (hi - lo)
    if pos > 0.25:
        return None
    idx = a[0, S1_ROW] if np.isfinite(a[0, S1_ROW]) else c
    w = solo_atm(und, idx) - s * 4 * K.STEP[und]
    st = K.STEP[und]
    return dict(side="C" if s > 0 else "P", strength=abs(mv) / D.atr, index=idx, strikes=[w, w + s * st, w - s * st])


# ------------------------------------------------------------------------------------------------ S3 / C4 helpers
_H37 = None


def h37():
    global _H37
    if _H37 is None:
        spec = importlib.util.spec_from_file_location("r57_h37_sigs", K.ROOT / "research" / "hunt" / "h37" / "sigs.py")
        _H37 = importlib.util.module_from_spec(spec)
        sys.modules["r57_h37_sigs"] = _H37
        spec.loader.exec_module(_H37)
    return _H37


_LEV: dict = {}


def prev_value_area(und: str) -> dict:
    """{day: (POC, VAH, VAL) of the PREVIOUS session} from h37 sigs.profile_levels (30-minute TPO, 70%)."""
    if und not in _LEV:
        days = r57data.load(und)
        ds = sorted(days)
        M = np.stack([days[d].idx for d in ds])
        lev = h37().profile_levels(M[:, 1, :], M[:, 2, :], M[:, 3, :])
        _LEV[und] = {d: (lev[i - 1] if i else np.full(3, np.nan)) for i, d in enumerate(ds)}
    return _LEV[und]


def prev_rank(x: np.ndarray, look: int = 60, min_n: int = 40) -> np.ndarray:
    """R47 prev_rank: share of the previous `look` sessions' finite values below x[i] (NaN if fewer than min_n)."""
    out = np.full(len(x), np.nan)
    for i in range(len(x)):
        if not np.isfinite(x[i]):
            continue
        w = x[max(0, i - look):i]
        w = w[np.isfinite(w)]
        if len(w) >= min_n:
            out[i] = float(np.mean(w < x[i]))
    return out


_PCR: dict = {}


def c4_rank(und: str = "BANKNIFTY") -> dict:
    """{day: rank of the 10:14 put/call OI ratio (ATM-10..+10) among the previous 60 sessions} (option days only)."""
    if und not in _PCR:
        days = r57data.load(und)
        ds = [d for d in sorted(days) if days[d].ctx is not None]
        x = np.array([days[d].ctx["pcr10"][K.row(10, 15) - 1] for d in ds], float)
        rk = prev_rank(x)
        _PCR[und] = dict(zip(ds, rk))
    return _PCR[und]


# ------------------------------------------------------------------------------------------------ candidates
def _sim(und, D, cands, setup):
    """Price candidates of one setup on one day; one open position a side (the rule's own taking), labels attached."""
    if not cands:
        return []
    ex = EXITS[setup]
    spec = IDX_EXIT.get(setup)
    sq = E.sq_row(D, ex)
    for c in cands:
        c["fx"] = forced_row(D, c["er"], sgn(c["side"]), spec, sq)
    if setup == "S2":
        import common as C51
        taken = C51.take(und, D, cands, ex, scope="side", max_per_side=2)
    else:
        res = E.run_day(und, D, [c["side"] for c in cands], [c["strike"] for c in cands], [c["er"] for c in cands], ex,
                        [c["fx"] for c in cands])
        taken = []
        for c, r in zip(cands, res):
            if r is not None:
                r.update({k: v for k, v in c.items() if k not in r and k != "fx"})
                taken.append(r)
    for t in taken:
        t["setup"] = setup
        t["money"] = MONEY[setup]
    return taken


def s1_candidates(und, D):
    s = s1_signal(und, D)
    if s is None:
        return []
    for k in s["strikes"]:                           # pick_leg: 4-ITM, then one step either way (less ITM first)
        if ("C" if s["side"] == "C" else "P", float(k)) in D.con:
            out = _sim(und, D, [dict(er=S1_ROW, side=s["side"], strike=float(k), strength=s["strength"])], "S1")
            if out:
                return out
    return []


_DON: dict = {}


def donchian(und):
    if und not in _DON:
        import fam_r44
        _DON[und] = fam_r44.signal_list(und, "donchian20")
    return _DON[und]


def s2_candidates(und, D):
    sig = donchian(und).get(D.d, [])
    cands = []
    for b, side in sig:
        er = 5 * (b + 1)
        if er < 5 or er > K.row(15, 10):
            continue
        k = E.strike_for(D, und, side, er - 1, MONEY["S2"])
        if k is not None:
            cands.append(dict(er=er, side=side, strike=k))
    return _sim(und, D, cands, "S2")


def s3_candidates(und, D):
    lev = prev_value_area(und).get(D.d)
    if lev is None or not np.isfinite(lev).all():
        return []
    _, vah, val = lev
    a = D.idx
    ok = np.flatnonzero(np.isfinite(a[0, :5]))
    okc = np.flatnonzero(np.isfinite(a[3, :5]))
    if not len(ok) or not len(okc):
        return []
    op, c5 = a[0, ok[0]], a[3, okc[-1]]
    side = "C" if (op > vah and c5 > vah) else "P" if (op < val and c5 < val) else None
    if side is None:
        return []
    k = E.strike_for(D, und, side, 4, MONEY["S3"])
    return _sim(und, D, [dict(er=5, side=side, strike=k)], "S3") if k is not None else []


def s4_candidates(und, D):
    a = D.idx
    hi, lo = np.nanmax(a[1, :15]) if np.isfinite(a[1, :15]).any() else np.nan, \
        np.nanmin(a[2, :15]) if np.isfinite(a[2, :15]).any() else np.nan
    if not (np.isfinite(hi) and np.isfinite(lo)):
        return []
    for b in range(3, 57):                           # bars 09:30 .. 13:55 (closes by 13:59)
        seg = a[3, 5 * b:5 * b + 5]
        okc = np.flatnonzero(np.isfinite(seg))
        if not len(okc):
            continue
        c = seg[okc[-1]]
        side = "C" if c > hi else "P" if c < lo else None
        if side:
            er = 5 * (b + 1)
            k = E.strike_for(D, und, side, er - 1, MONEY["S4"])
            return _sim(und, D, [dict(er=er, side=side, strike=k)], "S4") if k is not None else []
    return []


def c4_candidates(und, D):
    if und != "BANKNIFTY":
        return []
    r = c4_rank(und).get(D.d, np.nan)
    if not np.isfinite(r):
        return []
    side = "C" if r >= 0.8 else "P" if r <= 0.2 else None
    if side is None:
        return []
    er = K.row(10, 15)
    k = E.strike_for(D, und, side, er - 1, MONEY["C4"])
    return _sim(und, D, [dict(er=er, side=side, strike=k)], "C4") if k is not None else []


GEN = {"S1": s1_candidates, "S2": s2_candidates, "S3": s3_candidates, "S4": s4_candidates, "C4": c4_candidates}


def candidates(lo: date, hi: date, setups=("S1", "S2", "S3", "S4", "C4"), unds=K.UNDS) -> list:
    out = []
    for und in unds:
        days = r57data.load(und)
        for d in days_in(und, lo, hi):
            D = days[d]
            for s in setups:
                out += GEN[s](und, D)
    return out


# ------------------------------------------------------------------------------------------------ family B points
def b_points(lo: date, hi: date, unds=K.UNDS) -> list:
    """Every decision point (index, day, time) with both the call and the put priced for H 30 / 60 and 1-ITM / ATM
    (B's exits), plus the index-direction labels. One dict per point."""
    out = []
    for und in unds:
        days = r57data.load(und)
        for d in days_in(und, lo, hi):
            D = days[d]
            if not np.isfinite(D.atr):
                continue
            a = D.idx
            for er in B_TIMES:
                p = dict(und=und, day=d, er=er, frow=er - 1)
                ent = a[0, er] if np.isfinite(a[0, er]) else a[3, er - 1]
                if not np.isfinite(ent):
                    continue
                p["ent"] = float(ent)
                for H in (30, 60):
                    seg = a[3, er:er + H]
                    okc = np.flatnonzero(np.isfinite(seg))
                    p[f"mv{H}"] = float(seg[okc[-1]] - ent) if len(okc) else np.nan
                    ex = B_EXITS[H]
                    sq = E.sq_row(D, ex)
                    for money in (1, 0):
                        for side in ("C", "P"):
                            k = E.strike_for(D, und, side, er - 1, money)
                            fx = forced_row(D, er, sgn(side), B_IDX, sq)
                            r = E.run_day(und, D, [side], [k], [er], ex, [fx])[0] if k is not None else None
                            key = f"{side}{H}m{money}"
                            p[key] = r
                            c = D.contract(side, k) if k is not None else None
                            pr = float(c[3, er - 1]) if c is not None and np.isfinite(c[3, er - 1]) else np.nan
                            v5 = float(np.nansum(c[4, max(er - 5, 0):er])) if c is not None else 0.0
                            p[f"prem_{side}{money}"] = pr
                            p[f"v5_{side}{money}"] = v5
                out.append(p)
    return out


# ------------------------------------------------------------------------------------------------ costs and twins
def cost_2x(t: dict) -> float:
    """Net at 2x costs: charges and fill slippage (spread + bps) both doubled."""
    q = t["qty"]
    slip = ((t["rE"] - t["E"]) + (t["X"] - t["rX"])) * q
    ch = (t["rX"] - t["rE"]) * q - t["net"]
    return t["net"] - slip - ch


def expected_cost(und: str, D, prem: float, v5: float) -> float:
    """B's expected round trip in Rs for 1 lot at premium `prem`: charges + R17 bps and half-spread both ways."""
    q = float(K.lot(und, D.d))
    lots5 = v5 / q
    ticks = 1.0 if lots5 >= 20 else 2.0 if lots5 > 0 else 4.0
    slip = 2 * (prem * 0.0005 + ticks * 0.05) * q
    return float(E.charges(und, prem, prem, q, D.d)[0]) + slip


def twin_totals(trades: list, n: int, rng, first: int, last: int) -> np.ndarray:
    """Random-entry twins: each trade replaced by one on the same day and index, entry row uniform in [first, last],
    side by coin flip, the same strike rule (money) and the same exits (index exits recomputed from the twin's entry).
    Returns (n,) total nets."""
    tot = np.zeros(n)
    for t in trades:
        und, d = t["und"], t["day"]
        D = r57data.load(und)[d]
        ex = t["exits"]
        spec = t.get("idx_spec")
        sq = E.sq_row(D, ex)
        rows = rng.integers(first, last + 1, size=n)
        sides = np.where(rng.integers(0, 2, size=n) == 1, "C", "P")
        st = E.stack(und, D)
        ci = np.empty(n, np.int64)
        fx = np.empty(n, np.int64)
        for i in range(n):
            k = E.strike_for(D, und, sides[i], int(rows[i]) - 1, t["money"])
            ci[i] = st.idx(sides[i], k)
            fx[i] = forced_row(D, int(rows[i]), sgn(sides[i]), spec, sq)
        qty = float(K.lot(und, d))
        res, er, xr, rs = E.simulate_many(st.S, ci, rows.astype(np.int64), sq, fx, ex.stop, ex.target, ex.lock_trig,
                                          ex.lock_keep, ex.lock_trail, ex.steps_arr(), ex.tmax, ex.need,
                                          ex.close_stop, K._K51.TICK, qty)
        ok = rs != E.R_NOFILL
        rE, rX = np.nan_to_num(res[:, 2]), np.nan_to_num(res[:, 3])
        tot += np.where(ok, (rX - rE) * qty - E.charges(und, rE, rX, qty, d), 0.0)
    return tot
