"""R49 engine: R3's ORB-family engine (research/hunt/r3orb/r3engine.py) with the PREREG A3 / A4 additions:
- charges in force on the trade date (Costs("dated"): STT 0.1% to 31 Mar 2026, 0.15% from 1 Apr 2026);
- the re-tune grid: opening-range length M (15 / 30 / 50 minutes) and the stop / target scale k (0.5 / 1 / 1.5);
- R23's three frozen finalists (the R22 15-minute range and the ORB range, with the TWAP / break-strength filters).

Every signal is read from the INDEX (1-minute spot, 5- or 15-minute bars built from it); the option bought is one held
contract (strike, side) of the day's nearest expiry from entry to exit. Grid rows as in R3: row t = the 1-minute bar
that STARTS at 09:15 + t; a 5-minute bar b closes at row 5b + 5 and its entry fills at that row's open.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import numpy as np

import r49cfg as K
import r3cfg as K3
import r3engine as E
from r3data import C, H, L, O, Sess
from obuy.costs import Costs

COSTS = Costs("dated")
P0 = K3.PTS                          # the app's stop points per index (BANKNIFTY 40; R3's scaling for the others)
STEP = K3.STEP
LAST_BAR = {"orb": 55, "orb_fresh": 55, "orb_sweep": 62, "range_fade": 55}
R23_F1, R23_F2 = 0.21982, 0.03538    # R23 part B cuts (HUNT_R23_ORB_RANGE.md), unchanged
EPOCH = date(1970, 1, 1)


@dataclass(frozen=True)
class Rule:
    id: str
    group: str                       # O | R3 | R23 | G
    v: E.Variant                     # the arm, cap, exits, strike and filter (R3's Variant)
    m: int = 50                      # opening-range minutes (50 = the app's 09:15 .. 10:04)
    k: float = 1.0                   # stop / target scale on P
    r23: str = ""                    # '' | 'r22-f1' | 'r22-f2' | 'orb-f2'

    @property
    def arm(self):
        return self.v.arm


# ------------------------------------------------------------------------------------------------ charges (dated)
def dn(d: date) -> int:
    return (d - EPOCH).days


def charge(buy: bool, px: float, qty: int, und: str, d: date) -> float:
    return float(COSTS.charge(buy, np.array([px]), np.array([float(qty)]), np.array([dn(d)]), bse=und in K.BSE)[0])


def be_per_unit(e: float, qty: int, und: str, d: date) -> float:
    return float(COSTS.rt_per_unit(np.array([e]), np.array([float(qty)]), np.array([dn(d)]), bse=und in K.BSE)[0])


# ------------------------------------------------------------------------------------------------ one trade
def simulate(s: Sess, v: E.Variant, side: int, sig_row: int, k: float, P: float) -> dict | None:
    """R3's simulate (r3engine.simulate) with the charges of the trade date. One held contract (k, side)."""
    a = s.opts.get((k, "C" if side > 0 else "P"))
    if a is None:
        return None
    if a.dtype != np.float64:
        a = a.astype(np.float64)
    c0, px = E.next_open(a, sig_row + 1, sig_row + 1 + E.FILL_TRIES)
    if c0 < 0 or px <= P:
        return None
    qty = s.lot
    e = E.buy_px(px, a, c0, s.lot)
    trig = E.floor_tick(e - P)
    tgt = v.tgt_mult * P if v.tgt_mult > 0 else 0.0
    lad_t = (v.tgt_mult if v.tgt_mult > 0 else (2.0 if v.arm == "orb_sweep" else 1.0)) * P if v.ladder else 0.0
    be = e + be_per_unit(e, qty, s.und, s.day)
    lock_a, lock_k = (v.lock_rs or (0.0, 0.0))
    tr_a, tr_g = (v.trail_rs or (0.0, 0.0))
    ts_row, ts_need = -1, 0.0
    if v.time_stop:
        ts_row = c0 + int(v.time_stop[0]) - 1
        ts_need = v.time_stop[1] * (tgt if tgt > 0 else lad_t if lad_t > 0 else P)
    sq = E.SQ_EXPIRY if s.expiry else E.SQ_ROW
    r, p, why = E._walk(a[:, O], a[:, H], a[:, L], a[:, C], c0, e, trig, tgt, lad_t, be, float(qty),
                        float(lock_a), float(lock_k), float(tr_a), float(tr_g), ts_row, ts_need, sq)
    stale = False
    if why in (0, 1):
        x = E.sell_px(p, a, r, s.lot, stop=True)
    else:
        rr, p2 = E.next_open(a, r)
        if rr < 0:                       # the strike left the stored band: last stored price before (A4)
            fin = np.where(np.isfinite(a[:r, C]))[0]
            rr = int(fin[-1]) if len(fin) else c0
            p2 = float(a[rr, C])
            stale = True
        r, x = rr, E.sell_px(p2, a, rr, s.lot)
    ch = charge(True, e, qty, s.und, s.day) + charge(False, x, qty, s.und, s.day)
    gross = (x - e) * qty
    ref = (tgt if tgt > 0 else P)
    after = a[r + 1:sq, H] if r + 1 < sq else np.array([])
    big = bool(np.nanmax(after) >= e + 2 * ref) if len(after) and np.isfinite(after).any() else False
    return dict(und=s.und, day=s.day, year=s.day.year, arm=v.arm, vid=v.id, side=side, sig_row=sig_row, k=k,
                entry_row=c0, exit_row=r, entry=e, exit=x, why=E.WHY[5] if stale else E.WHY[why], qty=qty,
                gross=gross, charges=ch, net=gross - ch, cut_short=(why in (1, 2)) and big, expiry=s.expiry)


# ------------------------------------------------------------------------------------------------ windows
def window(rule: Rule) -> tuple[int, int]:
    """First and last 5-minute decision bar of an ORB-family rule with an M-minute range (PREREG A3, group G)."""
    nb = rule.m // 5
    first = nb + (5 if rule.arm == "range_fade" else 0)
    return first, LAST_BAR[rule.arm]


def decision_rows(rule: Rule) -> list[int]:
    """Every row at which the rule can decide (the last minute of a decision bar); the twins draw from these."""
    if rule.r23.startswith("r22"):
        return [15 * j + 14 for j in range(3, 19)]          # 15-minute candles 10:00 .. 13:45 (close by 14:00)
    if rule.r23 == "orb-f2":
        return [5 * b + 4 for b in range(10, 56)]
    f, l = window(rule)
    return [5 * b + 4 for b in range(f, l + 1)]


def bars15(ix: np.ndarray) -> np.ndarray:
    out = np.full((ix.shape[0] // 15, 4), np.nan)
    for j in range(len(out)):
        blk = ix[15 * j:15 * j + 15]
        ok = np.isfinite(blk[:, 3])
        if not ok.any():
            continue
        out[j] = blk[ok, 0][0], np.nanmax(blk[:, 1]), np.nanmin(blk[:, 2]), blk[ok, 3][-1]
    return out


def strike_for(s: Sess, side: int, itm: int) -> float:
    return s.atm - side * itm * STEP[s.und]


# ------------------------------------------------------------------------------------------------ the rule on one session
def _b5(s: Sess) -> np.ndarray:
    b = getattr(s, "_b5", None)
    if b is None:
        b = E.bars5(s.ix)
        s._b5 = b
    return b


def run_session(s: Sess, rule: Rule) -> list[dict]:
    if rule.r23:
        return run_r23(s, rule)
    v = rule.v
    b5 = _b5(s)
    nb = rule.m // 5
    if not np.isfinite(b5[nb - 1, 3]) or not np.isfinite(s.atm):
        return []
    hi, lo = float(np.nanmax(b5[:nb, 1])), float(np.nanmin(b5[:nb, 2]))
    P = P0[s.und] * rule.k
    first, last = window(rule)
    out, per_side, n, free_bar = [], {1: 0, -1: 0}, 0, -1
    for b in range(first, last + 1):
        if b <= free_bar:
            continue
        if n >= v.max_entries:
            break
        side = E.signal(v.arm, b5, b, hi, lo)
        if not side or (v.side_cap and per_side[side] >= v.side_cap):
            continue
        row = 5 * b + 4
        if v.filt:
            name, flo, fhi = v.filt
            x = E.features(s, side, row, hi, lo)[name]
            if np.isfinite(x) and flo <= x < fhi:
                continue
        t = simulate(s, v, side, row, strike_for(s, side, v.itm), P)
        if t is None:
            continue
        t["n_side"], t["n_day"] = per_side[side] + 1, n + 1
        out.append(t)
        per_side[side] += 1
        n += 1
        free_bar = t["exit_row"] // 5
    return out


def r23_filters(s: Sess, side: int, row: int, c: float, edge: float, which: str) -> bool:
    """R23 f1 (distance from the morning TWAP) and f2 (f1 and break strength), both / ATR14, at the decision row."""
    atr = s.atr14
    if not (np.isfinite(atr) and atr > 0):
        return False
    cl = s.ix[:row + 1, 3]
    cl = cl[np.isfinite(cl)]
    if not len(cl):
        return False
    ok = side * (c - cl.mean()) / atr > R23_F1
    if which.endswith("f2"):
        ok = ok and side * (c - edge) / atr > R23_F2
    return bool(ok)


def run_r23(s: Sess, rule: Rule) -> list[dict]:
    """R23 finalists: the first signal of each side (seen while flat) is the only one; a side whose first signal fails
    the filter or is refused is done for the day. One position at a time (the arm's own rule)."""
    if not np.isfinite(s.atm):
        return []
    v = rule.v
    P = P0[s.und]
    if rule.r23.startswith("r22"):
        if not np.isfinite(s.ix[44, 3]):
            return []
        hi, lo = float(np.nanmax(s.ix[:45, 1])), float(np.nanmin(s.ix[:45, 2]))
        bars, size, js = bars15(s.ix), 15, range(3, 19)
    else:
        b5 = _b5(s)
        if not np.isfinite(b5[9, 3]):
            return []
        hi, lo = float(np.nanmax(b5[:10, 1])), float(np.nanmin(b5[:10, 2]))
        bars, size, js = b5, 5, range(10, 56)
    out, done, free_row = [], {1: False, -1: False}, -1
    for j in js:
        row = size * j + size - 1
        if row <= free_row:
            continue
        c = bars[j, 3]
        if not np.isfinite(c):
            continue
        side = 1 if c > hi else -1 if c < lo else 0
        if not side or done[side]:
            continue
        done[side] = True
        if not r23_filters(s, side, row, c, hi if side > 0 else lo, rule.r23):
            continue
        t = simulate(s, v, side, row, strike_for(s, side, 0), P)
        if t is None:
            continue
        t["n_side"], t["n_day"] = 1, len(out) + 1
        out.append(t)
        free_row = (t["exit_row"] // size) * size + size - 1
    return out


def run(sessions: list, rule: Rule) -> list[dict]:
    out = []
    for s in sessions:
        out += run_session(s, rule)
    return out


# ------------------------------------------------------------------------------------------------ twins
def twin_by_und(by_key: dict, trades: list, rule: Rule, rng: np.random.Generator) -> dict:
    """One random-entry twin (PREREG A5): each real trade re-drawn at a random decision row of the rule, same session,
    side, strike rule, stop / target and exits. Returns the twin's net per index."""
    rows = decision_rows(rule)
    P_k = rule.k if not rule.r23 else 1.0
    out = {}
    for t in trades:
        s = by_key[(t["und"], t["day"])]
        for _ in range(5):
            r = rows[int(rng.integers(len(rows)))]
            tw = simulate(s, rule.v, t["side"], r, strike_for(s, t["side"], rule.v.itm), P0[s.und] * P_k)
            if tw is not None:
                out[s.und] = out.get(s.und, 0.0) + tw["net"]
                break
    return out


# ------------------------------------------------------------------------------------------------ the 56 rules
ARMS = E.ARMS
ARM_ID = {"orb": "orb", "orb_fresh": "orb-fresh", "orb_sweep": "orb-sweep", "range_fade": "range-fade"}
R3_FILT = {  # R3 Part B (research/hunt/r3orb/results/partb.json), unchanged
    "orb": ("vix_chg", -0.8638, 1.0372),
    "orb_fresh": ("vix_chg", -0.8638, 1.0372),
    "orb_sweep": ("vix_chg", float("-inf"), -0.9926),
    "range_fade": ("expiry", 0.5, float("inf")),
}
GRID_M = (15, 30, 50)
GRID_K = (0.5, 1.0, 1.5)


def r3_filter(arm: str) -> tuple:
    """R3's frozen Part B filter at full precision from its partb.json (the table above is the rounded check)."""
    import json
    pb = json.loads((K3.RESULTS / "partb.json").read_text())[arm]["pick"]
    return (pb["feature"], float(pb["skip"][0]), float(pb["skip"][1]))


def rules() -> list[Rule]:
    out = []
    for a in ARMS:
        b = E.baseline(a)
        out.append(Rule(f"r49-{ARM_ID[a]}", "O", E.with_(b, id=f"r49-{ARM_ID[a]}")))
    out.append(Rule("r49-orb-uncapped", "O", E.with_(E.baseline("orb_uncapped"), id="r49-orb-uncapped")))
    ex = dict(tgt_mult=0.0, lock_rs=(1000.0, 500.0), trail_rs=(1000.0, 500.0), time_stop=(45, 0.25))
    st = dict(itm=1, side_cap=1)
    for a in ARMS:
        b = E.baseline(a)
        f = r3_filter(a)
        for x, kw in (("F", dict(filt=f)), ("E", ex), ("S", st), ("C", dict(filt=f, **ex, **st))):
            i = f"r49-r3-{ARM_ID[a]}-{x.lower()}"
            out.append(Rule(i, "R3", E.with_(b, id=i, **kw)))
    ob = E.baseline("orb")
    for w in ("r22-f2", "r22-f1", "orb-f2"):
        i = f"r49-r23-{w}"
        out.append(Rule(i, "R23", E.with_(ob, id=i), r23=w))
    for a in ARMS:
        b = E.baseline(a)
        for m in GRID_M:
            for k in GRID_K:
                if (m, k) == (50, 1.0):
                    continue
                i = f"r49-g-{ARM_ID[a]}-m{m}-k{k:g}"
                out.append(Rule(i, "G", E.with_(b, id=i), m=m, k=k))
    assert len(out) == 56, len(out)
    return out


def grid_family(arm: str, rs: list[Rule]) -> list[Rule]:
    """The 9 (M, k) rules of an arm: its 8 grid rules and the old rule (M 50, k 1)."""
    return [r for r in rs if r.arm == arm and ((r.group == "G") or (r.group == "O" and r.id == f"r49-{ARM_ID[arm]}"))]


# ------------------------------------------------------------------------------------------------ scoring helpers
score = E.score
bh = E.bh
t_p = E.t_p


def top5_share(trades: list) -> float:
    """Sum of the 5 best trades / net (>= 1 means the net needs them)."""
    nets = sorted((t["net"] for t in trades), reverse=True)
    net = sum(nets)
    if net <= 0:
        return float("inf")
    return float(sum(nets[:5]) / net)
