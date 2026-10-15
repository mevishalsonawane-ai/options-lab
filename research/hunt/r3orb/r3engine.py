"""R3 engine: the ORB family's signals (OrbRules / SweepRules / RangeFadeRules), the PREREG A6 variants, the exits
(-P / +P, the ProfitLock ladder, Boss's R19c rules, a time stop), R17's fills and the app's charges.

Grid: row t = the 1-minute bar that STARTS at 09:15 + t. 5-minute bar b covers rows 5b .. 5b+4 and closes at row 5b+5
(its decision is known then). An entry fills at row 5b+5's open (the next minute). Nothing here reads a row after the
decision to decide anything (test_r3orb.py: the look-ahead guard).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, replace

import numpy as np

import r3cfg as K
from r3data import C, H, L, N, O, V, Sess
from obuy.costs import Costs, Fills

try:  # optional speed-up; the pure-Python path gives the same numbers (test_r3orb.py)
    from numba import njit
except Exception:  # pragma: no cover
    def njit(*a, **k):
        def deco(f):
            return f
        return deco if not (a and callable(a[0])) else a[0]

TICK = 0.05
EPS = 1e-9
FILLS = Fills("liq")
COSTS = Costs("app")
SQ_ROW = 355            # 15:10 (the open of the 15:10 minute)
SQ_EXPIRY = 350         # 15:05 on an expiry day (ExpirySquareOff)
OR_BARS = 10            # 5-minute bars 09:15 .. 10:00
FADE_EDGE = 0.10
FILL_TRIES = 3
LADDER = ((0.25, 0.0), (0.50, 0.25), (0.75, 0.50))
WINDOW = {"orb": (10, 55), "orb_fresh": (10, 55), "orb_sweep": (10, 62), "range_fade": (15, 55)}
ARMS = ("orb", "orb_fresh", "orb_sweep", "range_fade")
NAMES = {"orb": "ORB", "orb_fresh": "ORB Fresh", "orb_sweep": "ORB Sweep", "range_fade": "Range Fade", "orb_uncapped": "ORB (no cap)"}
WHY = ("stop", "lock", "target", "time", "square_off", "stale")


@dataclass(frozen=True)
class Variant:
    id: str
    arm: str                       # orb | orb_fresh | orb_sweep | range_fade
    side_cap: int = 0              # entries per side a day (0 = none)
    max_entries: int = 99
    tgt_mult: float = 1.0          # target = tgt_mult x P; 0 = no fixed target
    ladder: bool = True
    lock_rs: tuple = ()            # (reach Rs, keep Rs) per lot: Boss's "+1,000 then keep 500"
    trail_rs: tuple = ()           # (from Rs, give-back Rs) per lot: the Rs 500 give-back trail
    time_stop: tuple = ()          # (minutes, share of target the peak must reach)
    itm: int = 0                   # strikes in the money
    filt: tuple = ()               # (feature, lo, hi): skip an entry whose feature is in [lo, hi)

    @property
    def win(self):
        return WINDOW[self.arm]


def baseline(arm: str) -> Variant:
    if arm == "orb_uncapped":
        return Variant("base-orb_uncapped", "orb")
    if arm in ("orb", "orb_fresh"):
        return Variant(f"base-{arm}", arm, side_cap=1)
    if arm == "orb_sweep":
        return Variant(f"base-{arm}", arm, max_entries=2, tgt_mult=2.0)
    return Variant(f"base-{arm}", arm, max_entries=2)


# ------------------------------------------------------------------------------------------------ bars and range
def bars5(ix: np.ndarray) -> np.ndarray:
    """(75, 4) O H L C of the 5-minute bars from the 1-minute rows (NaN when the bar has no minute)."""
    out = np.full((N // 5, 4), np.nan)
    for b in range(N // 5):
        blk = ix[5 * b:5 * b + 5]
        ok = np.isfinite(blk[:, 3])
        if not ok.any():
            continue
        out[b, 0] = blk[ok, 0][0]
        out[b, 1] = np.nanmax(blk[:, 1])
        out[b, 2] = np.nanmin(blk[:, 2])
        out[b, 3] = blk[ok, 3][-1]
    return out


def opening_range(b5: np.ndarray) -> tuple[float, float]:
    """OrbRules.openingRange: the bars 09:15 .. 10:00; None-like (nan) unless the 10:00 bar exists."""
    if not np.isfinite(b5[OR_BARS - 1, 3]):
        return np.nan, np.nan
    return float(np.nanmax(b5[:OR_BARS, 1])), float(np.nanmin(b5[:OR_BARS, 2]))


def signal(arm: str, b5: np.ndarray, b: int, hi: float, lo: float) -> int:
    """+1 CE / -1 PE / 0 on completed bar b (reads bars <= b only)."""
    o, h, l, c = b5[b]
    if not np.isfinite(c):
        return 0
    if arm in ("orb", "orb_fresh"):
        d = 1 if c > hi else -1 if c < lo else 0
        if d and arm == "orb_fresh" and b > 0:
            pc = b5[b - 1, 3]
            if np.isfinite(pc) and ((d > 0 and pc > hi) or (d < 0 and pc < lo)):
                return 0
        return d
    if arm == "orb_sweep":
        if h > hi + EPS and c < hi - EPS:
            return -1
        if l < lo - EPS and c > lo + EPS:
            return 1
        return 0
    w = hi - lo
    if w <= EPS:
        return 0
    if h >= hi - FADE_EDGE * w - EPS and c < hi - EPS:
        return -1
    if l <= lo + FADE_EDGE * w + EPS and c > lo + EPS:
        return 1
    return 0


# ------------------------------------------------------------------------------------------------ features (PREREG A6)
FEATURES = ("vix_prev", "vix_chg", "gap_dir", "width_atr", "t_sig", "expiry", "twap_dir")


def features(s: Sess, side: int, row: int, hi: float, lo: float) -> dict:
    """Point-in-time features at a signal whose bar closes at row+1 (reads rows <= row)."""
    ix = s.ix
    atr = s.atr14 if s.atr14 and np.isfinite(s.atr14) and s.atr14 > 0 else np.nan
    v = s.vix[:row + 1]
    fv = v[np.isfinite(v)]
    vchg = (fv[-1] / fv[0] - 1) * 100 if len(fv) >= 2 and fv[0] > 0 else np.nan
    o0 = ix[np.isfinite(ix[:, 0]), 0]
    o0 = o0[0] if len(o0) else np.nan
    cl = ix[:row + 1, 3]
    cl = cl[np.isfinite(cl)]
    c = cl[-1] if len(cl) else np.nan
    twap = cl.mean() if len(cl) else np.nan
    return dict(
        vix_prev=s.vix_prev,
        vix_chg=vchg,
        gap_dir=side * (o0 - s.prev[3]) / atr,
        width_atr=(hi - lo) / atr,
        t_sig=float(row + 1),
        expiry=1.0 if s.expiry else 0.0,
        twap_dir=side * (c - twap) / atr,
    )


# ------------------------------------------------------------------------------------------------ fills and charges
def floor_tick(x: float) -> float:
    return round(math.floor(x / TICK + 1e-6) * TICK, 2)


def ceil_tick(x: float) -> float:
    return round(math.ceil(x / TICK - 1e-6) * TICK, 2)


def vol5_lots(a: np.ndarray, row: int, lot: int) -> float:
    v = a[max(0, row - 5):row, V]
    v = v[np.isfinite(v)]
    return float(v.sum() / lot) if lot else 0.0


def buy_px(p, a, row, lot):
    return float(FILLS.buy(np.array([p]), np.array([vol5_lots(a, row, lot)]))[0])


def sell_px(p, a, row, lot, stop=False):
    return float(FILLS.sell(np.array([p]), np.array([vol5_lots(a, row, lot)]), stop=stop)[0])


def charge(buy: bool, px: float, qty: int, und: str) -> float:
    return float(COSTS.charge(buy, np.array([px]), np.array([float(qty)]), bse=und in K.BSE)[0])


def be_per_unit(e: float, qty: int, und: str) -> float:
    return float(COSTS.rt_per_unit(np.array([e]), np.array([float(qty)]), bse=und in K.BSE)[0])


def next_open(a: np.ndarray, r: int, upto: int = N) -> tuple[int, float]:
    for rr in range(r, min(upto, N)):
        if np.isfinite(a[rr, O]):
            return rr, float(a[rr, O])
    return -1, np.nan


# ------------------------------------------------------------------------------------------------ exits
@njit(cache=True)
def _walk(o, h, l, c, c0, e, trig, tgt, lad_t, be, lot, lock_a, lock_k, tr_a, tr_g, ts_row, ts_need, sq):
    """The exit walk on 1-minute rows c0 .. sq-1. Returns (row, price, why): why 0 stop, 1 lock, 2 target (sell at
    the next open), 3 time (sell at the next open), 4 square-off. Stops: the resting level vs the minute's low, filled at
    min(level, open). The peak is the best high BEFORE the current minute."""
    peak = e
    reached = False
    for r in range(c0, sq):
        if not np.isfinite(c[r]):
            continue
        op = o[r] if np.isfinite(o[r]) else c[r]
        lock = -1.0
        if lad_t > 0:
            for i in range(3):
                reach = (0.25, 0.50, 0.75)[i]
                keep = (0.0, 0.25, 0.50)[i]
                if peak >= e + reach * lad_t - 1e-9:
                    lv = max(e + keep * lad_t, be)
                    if lv < peak:
                        lock = max(lock, lv)
        if lock_a > 0 and (peak - e) * lot >= lock_a - 1e-9:
            lv = math.ceil((e + lock_k / lot) / 0.05 - 1e-6) * 0.05
            if lv < peak:
                lock = max(lock, lv)
        if tr_a > 0 and (peak - e) * lot >= tr_a - 1e-9:
            lv = math.ceil((peak - tr_g / lot) / 0.05 - 1e-6) * 0.05
            if lv < peak:
                lock = max(lock, lv)
        rest = max(trig, lock)
        if rest > 0.05 and l[r] <= rest:
            return r, min(rest, op), (0 if trig >= lock else 1)
        if tgt > 0 and r <= sq - 2 and h[r] >= e + tgt:
            return r + 1, np.nan, 2
        if ts_row > 0 and r >= ts_row and not reached:
            return r + 1, np.nan, 3
        peak = max(peak, h[r])
        if ts_need > 0 and peak >= e + ts_need - 1e-9:
            reached = True
    return sq, np.nan, 4


def simulate(s: Sess, v: Variant, side: int, sig_row: int, k: float, P: float) -> dict | None:
    """One trade: signal bar closes at sig_row + 1 (= the entry row), buys 1 lot of strike k."""
    a = s.opts.get((k, "C" if side > 0 else "P"))
    if a is None:
        return None
    a = a.astype(np.float64)
    c0, px = next_open(a, sig_row + 1, sig_row + 1 + FILL_TRIES)
    if c0 < 0 or px <= P:
        return None
    qty = s.lot
    e = buy_px(px, a, c0, s.lot)
    trig = floor_tick(e - P)
    tgt = v.tgt_mult * P if v.tgt_mult > 0 else 0.0
    lad_t = (v.tgt_mult if v.tgt_mult > 0 else (2.0 if v.arm == "orb_sweep" else 1.0)) * P if v.ladder else 0.0
    be = e + be_per_unit(e, qty, s.und)
    lock_a, lock_k = (v.lock_rs or (0.0, 0.0))
    tr_a, tr_g = (v.trail_rs or (0.0, 0.0))
    ts_row, ts_need = -1, 0.0
    if v.time_stop:
        ts_row = c0 + int(v.time_stop[0]) - 1
        ts_need = v.time_stop[1] * (tgt if tgt > 0 else lad_t if lad_t > 0 else P)
    sq = SQ_EXPIRY if s.expiry else SQ_ROW
    r, p, why = _walk(a[:, O], a[:, H], a[:, L], a[:, C], c0, e, trig, tgt, lad_t, be, float(qty),
                      float(lock_a), float(lock_k), float(tr_a), float(tr_g), ts_row, ts_need, sq)
    stale = False
    if why in (0, 1):
        x = sell_px(p, a, r, s.lot, stop=True)
    else:
        rr, p2 = next_open(a, r)
        if rr < 0:                       # the strike left the stored band: last stored price before (A3)
            fin = np.where(np.isfinite(a[:r, C]))[0]
            rr = int(fin[-1]) if len(fin) else c0
            p2 = float(a[rr, C])
            stale = True
        r, x = rr, sell_px(p2, a, rr, s.lot)
    ch = charge(True, e, qty, s.und) + charge(False, x, qty, s.und)
    gross = (x - e) * qty
    # hindsight only (report): did the option reach entry + 2 x target after the exit, before the square-off?
    ref = (tgt if tgt > 0 else P)
    after = a[r + 1:sq, H] if r + 1 < sq else np.array([])
    big = bool(np.nanmax(after) >= e + 2 * ref) if len(after) and np.isfinite(after).any() else False
    return dict(und=s.und, day=s.day, year=s.day.year, arm=v.arm, vid=v.id, side=side, sig_row=sig_row, k=k,
                entry_row=c0, exit_row=r, entry=e, exit=x, why=WHY[5] if stale else WHY[why], qty=qty,
                gross=gross, charges=ch, net=gross - ch, cut_short=(why in (1, 2)) and big, expiry=s.expiry)


def strike_for(s: Sess, side: int, itm: int) -> float:
    return s.atm - side * itm * K.STEP[s.und]


# ------------------------------------------------------------------------------------------------ the arm on one session
def run_session(s: Sess, v: Variant, feats_out: list | None = None) -> list[dict]:
    b5 = bars5(s.ix)
    hi, lo = opening_range(b5)
    if not (np.isfinite(hi) and np.isfinite(lo)) or not np.isfinite(s.atm):
        return []
    P = K.PTS[s.und]
    first, last = v.win
    out, per_side, n, free_bar = [], {1: 0, -1: 0}, 0, -1
    for b in range(first, last + 1):
        if b <= free_bar:
            continue
        if n >= v.max_entries:
            break
        side = signal(v.arm, b5, b, hi, lo)
        if not side or (v.side_cap and per_side[side] >= v.side_cap):
            continue
        row = 5 * b + 4                      # the signal bar's last minute; entry at row + 1
        f = features(s, side, row, hi, lo)
        if v.filt:
            name, flo, fhi = v.filt
            x = f[name]
            if np.isfinite(x) and flo <= x < fhi:
                continue
        t = simulate(s, v, side, row, strike_for(s, side, v.itm), P)
        if t is None:
            continue
        t["feats"] = f
        t["n_side"] = per_side[side] + 1
        t["n_day"] = n + 1
        out.append(t)
        per_side[side] += 1
        n += 1
        free_bar = t["exit_row"] // 5
    return out


def run(sessions: list, v: Variant) -> list[dict]:
    out = []
    for s in sessions:
        out += run_session(s, v)
    return out


# ------------------------------------------------------------------------------------------------ random-entry twins
def twin_net(sessions_by_key: dict, trades: list, v: Variant, rng: np.random.Generator) -> float:
    """One twin (PREREG A9): each real trade re-drawn at a random bar close in the arm's window, same session, side,
    strike rule and exits."""
    first, last = v.win
    tot = 0.0
    for t in trades:
        s = sessions_by_key[(t["und"], t["day"])]
        for _ in range(5):                   # a draw whose premium is refused is re-drawn (at most 5 times)
            b = int(rng.integers(first, last + 1))
            tw = simulate(s, v, t["side"], 5 * b + 4, strike_for(s, t["side"], v.itm), K.PTS[s.und])
            if tw is not None:
                tot += tw["net"]
                break
    return tot


# ------------------------------------------------------------------------------------------------ scoring
def score(trades: list, sessions: list) -> dict:
    """sessions: (und, day) keys of every session in the period (a session without a trade counts 0)."""
    days = sorted({d for _, d in sessions})
    by_day = {d: 0.0 for d in days}
    for t in trades:
        by_day[t["day"]] = by_day.get(t["day"], 0.0) + t["net"]
    traded_days = sorted({t["day"] for t in trades})
    dn = np.array([by_day[d] for d in days]) if days else np.zeros(0)
    eq = np.cumsum(dn) if len(dn) else np.zeros(1)
    dd = float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min()) if len(dn) else 0.0
    nets = np.array([t["net"] for t in trades]) if trades else np.zeros(0)
    wins, losses = nets[nets > 0], nets[nets <= 0]
    td = np.array([by_day[d] for d in traded_days]) if traded_days else np.zeros(0)
    return dict(
        trades=len(trades), sessions=len(sessions), days=len(days), traded_days=len(traded_days),
        trades_per_day=len(trades) / len(days) if days else 0.0,
        win_rate=float((nets > 0).mean()) if len(nets) else 0.0,
        avg_win=float(wins.mean()) if len(wins) else 0.0, avg_loss=float(losses.mean()) if len(losses) else 0.0,
        net=float(nets.sum()), per_day=float(nets.sum() / len(days)) if days else 0.0,
        green_days=float((td > 0).mean()) if len(td) else 0.0,
        worst_day=float(td.min()) if len(td) else 0.0, max_dd=dd,
        cut_short=int(sum(t["cut_short"] for t in trades)),
        exits={w: int(sum(t["why"] == w for t in trades)) for w in WHY},
    )


def t_p(trades: list, sessions: list) -> float:
    """One-sided t-test p: mean net per index-session > 0 (a session without a trade counts 0)."""
    from scipy import stats
    by = {k: 0.0 for k in sessions}
    for t in trades:
        by[(t["und"], t["day"])] += t["net"]
    x = np.array(list(by.values()))
    if len(x) < 3 or x.std() == 0:
        return 1.0
    r = stats.ttest_1samp(x, 0.0, alternative="greater")
    return float(r.pvalue)


def bh(ps: list[float]) -> list[float]:
    p = np.asarray(ps, dtype=float)
    m = len(p)
    o = np.argsort(p)
    q = np.empty(m)
    prev = 1.0
    for i in range(m - 1, -1, -1):
        prev = min(prev, p[o[i]] * m / (i + 1))
        q[o[i]] = prev
    return list(np.minimum(q, 1.0))


def with_(v: Variant, **kw) -> Variant:
    return replace(v, **kw)
