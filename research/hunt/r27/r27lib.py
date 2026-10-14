"""R27 library: R3's trades, the 09:16 sale (B0) and the pre-registered smart exits (PREREG.md), costs, metrics, stats.

Timing (no look-ahead; test_r27.py guards it):
  - The decision is made at 09:16:00 on what is known then: the next session's 09:15 bar (row 0: index, VIX, options)
    and everything from the entry day. B0 sells at the option's 09:16 open (first bar 09:16 .. 09:21), as research X2.
  - A held option is watched from the 09:16 bar on. Premium levels are resting orders on the option's own 1-minute
    HIGH / LOW (stop first in a tie; a gap through a level fills at the open). Index levels are read on the index's
    1-minute HIGH / LOW of row t and sold at the option's next open (row t + 1): the app sees the minute, then sells.
  - Every hold has a stop and a time cap (10:30 open): never open-ended.
Costs: app fills (+5 bps buy, -5 bps sell, -10 bps resting stop; a target limit fills at its level), app charges
(obuy Costs('app'), STT 0.15%), plus the h24 half-spread on the entry and exit raw prices (0.16%); STRESS 1.5x spread.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np
from scipy import stats as sst

import r27cfg as K
from r27data import C, H, L, MORN, O, Night, first_valid, minute

from obuy.costs import Costs, Fills  # noqa: E402

FILL, COST = Fills("app"), Costs("app")
CAP_1030 = minute(10, 30)
TICK = 0.05


# ------------------------------------------------------------------------------------------------ variants
@dataclass(frozen=True)
class Spec:
    id: str
    hold: str                  # 'always' | 'agree' (gap and the 09:15 bar both with the position) | 'never' (B0)
    target: str = ""           # 'idx' | 'prem' | ''
    pts: float = 0.0           # target size (index points NIFTY / premium Rs per unit)
    bn: str = "scaled"         # BANKNIFTY index points: 'scaled' (pts x BN / NIFTY level) or 'raw'
    stop: str = ""             # 'idx' (pts against) | 'idx_open' (index back through its 09:15 open) | 'prem' | 'lot'
    stop_pts: float = 0.0      # 'idx' / 'prem': points; 'lot': rupees on the lot below the 09:16 value
    lock: bool = False         # Boss's floor: from +Rs 1,000 on the lot (vs the 15:20 buy), Rs 500 below the best
    cap: int = CAP_1030


B0 = Spec("B0", "never")
VARIANTS = (
    Spec("V1_H1_I20s", "always", "idx", 20, "scaled", "idx", 20),
    Spec("V2_H1_I30s", "always", "idx", 30, "scaled", "idx", 30),
    Spec("V3_H1_I20r", "always", "idx", 20, "raw", "idx", 20),
    Spec("V4_H1_I30r", "always", "idx", 30, "raw", "idx", 30),
    Spec("V5_H1_P20", "always", "prem", 20, "", "prem", 20),
    Spec("V6_H1_P30", "always", "prem", 30, "", "prem", 30),
    Spec("V7_H2_I30s", "agree", "idx", 30, "scaled", "idx_open", 0),
    Spec("V8_H3_LOCK", "always", "", 0, "", "lot", 1000, lock=True),
)
BY_ID = {s.id: s for s in VARIANTS}
LOCK_ON, LOCK_GIVE = 1000.0, 500.0


def idx_pts(n: Night, spec: Spec, pts: float) -> float:
    if n.und == "BANKNIFTY" and spec.bn == "scaled":
        return pts * n.x / n.x_nifty if (np.isfinite(n.x_nifty) and n.x_nifty > 0) else np.nan
    return pts


# ------------------------------------------------------------------------------------------------ decision (09:16)
def morning(n: Night, side: int) -> dict:
    """What is known at 09:16:00 (rows <= 0 of the next session plus the entry day), in the position's favour (+)."""
    ix, opt = n.ix, n.opt.get(side)
    o0, c0 = ix[0, O], ix[0, C]
    v0 = n.vix[0] if n.vix is not None else np.nan
    oi0 = opt[0, 4] if opt is not None else np.nan
    last_c, last_oi = n.last_d.get(side, (np.nan, np.nan))
    return dict(
        gap=side * (o0 / n.d_close - 1) if np.isfinite(o0) else np.nan,
        first=side * (c0 / o0 - 1) if np.isfinite(o0) and np.isfinite(c0) else np.nan,
        vix_chg=(v0 / n.vix_prev - 1) if np.isfinite(v0) and np.isfinite(n.vix_prev) and n.vix_prev > 0 else np.nan,
        oi_chg=(oi0 / last_oi - 1) if np.isfinite(oi0) and np.isfinite(last_oi) and last_oi > 0 else np.nan,
        vs_high=side * (c0 / n.d_high_all - 1) if side > 0 else side * (c0 / n.d_low_all - 1),
        vs_close=side * (c0 / n.d_close - 1) if np.isfinite(c0) else np.nan,
    )


def decide(n: Night, side: int, spec: Spec) -> bool:
    """Hold past 09:16? Reads only morning() (rows <= 0)."""
    if spec.hold == "never":
        return False
    if spec.hold == "always":
        return True
    m = morning(n, side)
    return bool(m["gap"] > 0 and m["first"] > 0)


# ------------------------------------------------------------------------------------------------ exits
@dataclass
class Exit:
    raw: float
    fill: float
    row: int
    why: str


def b0_exit(n: Night, side: int) -> Exit | None:
    k, p = first_valid(n.opt[side][:, O], 1, 6)
    if k < 0:
        return None
    return Exit(p, float(FILL.sell(p)), k, "b0_0916")


def _next_open(opt: np.ndarray, t: int, why: str, stop: bool) -> Exit:
    k, p = first_valid(opt[:, O], t, 6)
    if k < 0:                                      # no bar within 6 minutes: the next one there is, else the last close
        k, p = first_valid(opt[:, O], t, MORN - t)
        if k < 0:
            seg = np.nonzero(np.isfinite(opt[:t, C]))[0]
            k = int(seg[-1]) if len(seg) else 0
            p = float(opt[k, C])
            why += "_lastclose"
    return Exit(p, float(FILL.sell(p, stop=stop)), k, why)


def hold_exit(n: Night, side: int, spec: Spec, entry_raw: float, start: Exit) -> Exit:
    """Watch the held option from the 09:16 bar (start.row) to the cap; see the module note for fills."""
    opt, ix = n.opt[side], n.ix
    qty = n.qty
    p0, k0 = start.raw, start.row
    i0, open0 = ix[0, C], ix[0, O]
    tgt_p = stp_p = np.nan
    if spec.target == "prem":
        tgt_p = p0 + spec.pts
    if spec.stop == "prem":
        stp_p = p0 - spec.stop_pts
    elif spec.stop == "lot":
        stp_p = p0 - spec.stop_pts / qty
    tgt_i = stp_i = np.nan
    if spec.target == "idx":
        tgt_i = i0 + side * idx_pts(n, spec, spec.pts)
    if spec.stop == "idx":
        stp_i = i0 - side * idx_pts(n, spec, spec.stop_pts)
    elif spec.stop == "idx_open":
        stp_i = open0
    best = -np.inf
    floor = -np.inf
    if spec.lock and (p0 - entry_raw) * qty >= LOCK_ON:
        best = p0
        floor = _floor(best, entry_raw, qty)
    for t in range(k0, spec.cap):
        o, h, lo = opt[t, O], opt[t, H], opt[t, L]
        if np.isfinite(h):
            stop_lvl = np.nanmax([stp_p, floor]) if (np.isfinite(stp_p) or np.isfinite(floor)) else np.nan
            if t > k0 and np.isfinite(o):
                if np.isfinite(stop_lvl) and o <= stop_lvl:
                    return Exit(o, float(FILL.sell(o, stop=True)), t, "lock_gap" if floor >= stp_p else "stop_gap")
                if np.isfinite(tgt_p) and o >= tgt_p:
                    return Exit(o, float(FILL.sell(o)), t, "target_gap")
            if np.isfinite(stop_lvl) and lo <= stop_lvl:
                return Exit(stop_lvl, float(FILL.sell(stop_lvl, stop=True)), t, "lock" if floor >= stp_p else "stop")
            if np.isfinite(tgt_p) and h >= tgt_p:
                return Exit(tgt_p, tgt_p, t, "target")
            if spec.lock:                                  # the floor moves from the next minute (R19c)
                best = max(best, h)
                if (best - entry_raw) * qty >= LOCK_ON:
                    floor = max(floor, _floor(best, entry_raw, qty))
        ih, il = ix[t, H], ix[t, L]
        if np.isfinite(ih) and np.isfinite(il):
            hit_s = np.isfinite(stp_i) and ((il <= stp_i) if side > 0 else (ih >= stp_i))
            hit_t = np.isfinite(tgt_i) and ((ih >= tgt_i) if side > 0 else (il <= tgt_i))
            if hit_s or hit_t:
                return _next_open(opt, t + 1, "idx_stop" if hit_s else "idx_target", stop=hit_s)
    return _next_open(opt, spec.cap, "time_1030", stop=False)


def _floor(best: float, entry_raw: float, qty: int) -> float:
    """Boss's floor (R19c F1000_500): Rs 500 on the lot below the best, as an option price, on the tick rounded up."""
    lvl = best - LOCK_GIVE / qty
    return round(math.ceil(lvl / TICK - 1e-9) * TICK, 2)


@dataclass
class Trade:
    und: str
    d: object
    nd: object
    side: int
    qty: int
    held: bool
    why: str
    entry: float
    efill: float
    exit: float
    xfill: float
    b0_exit: float
    gross: float
    net: float
    net15: float
    net_b0: float
    row: int
    mfe_held: float = np.nan


def cost_net(n: Night, e: float, ef: float, x: float, xf: float, mult: float = 1.0) -> float:
    q = n.qty
    sp = K.HALF_SPREAD[n.und] * (e + x) * q
    return (xf - ef) * q - float(COST.charge(True, ef, q)) - float(COST.charge(False, xf, q)) - mult * sp


def trade(n: Night, side: int, spec: Spec, held: bool | None = None) -> Trade | None:
    """One night's trade under spec (held forced for the random twin). None when R3 could not trade it (no entry or no
    09:16 bar): those nights drop from B0 and every variant alike."""
    if side not in n.opt or side not in n.entry or not np.isfinite(n.entry[side]) or n.ix is None:
        return None
    e = n.entry[side]
    ef = float(FILL.buy(e))
    b0 = b0_exit(n, side)
    if b0 is None:
        return None
    h = decide(n, side, spec) if held is None else held
    x = hold_exit(n, side, spec, e, b0) if h else b0
    q = n.qty
    return Trade(n.und, n.d, n.nd, side, q, h, x.why, e, ef, x.raw, x.fill, b0.raw, (x.raw - e) * q,
                 cost_net(n, e, ef, x.raw, x.fill), cost_net(n, e, ef, x.raw, x.fill, K.STRESS),
                 cost_net(n, e, ef, b0.raw, b0.fill), x.row)


def r3_nights(nights: list[Night]) -> list[Night]:
    return [n for n in nights if n.r3 != 0]


# ------------------------------------------------------------------------------------------------ metrics
def by_day(trades: list[Trade], col: str = "net") -> dict:
    out: dict = {}
    for t in trades:
        out[t.d] = out.get(t.d, 0.0) + getattr(t, col)
    return out


def max_dd(x) -> float:
    eq = np.cumsum(np.asarray(x, float))
    if not len(eq):
        return 0.0
    return float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min())


def metrics(trades: list[Trade], calendar_days: int) -> dict:
    """Per trade and per day (over calendar_days sessions of the period), vs B0 on the same trades."""
    if not trades:
        return dict(trades=0)
    net = np.array([t.net for t in trades])
    b0 = np.array([t.net_b0 for t in trades])
    diff = net - b0
    held = np.array([t.held for t in trades])
    wins, loss = net[net > 0], net[net <= 0]
    dd = by_day(trades)
    db = by_day(trades, "net_b0")
    days = sorted(dd)
    dv = np.array([dd[d] for d in days])
    bv = np.array([db[d] for d in days])
    gave_back = int(np.sum((b0 > 0) & (net <= 0)))
    rescued = int(np.sum((b0 <= 0) & (net > 0)))
    big_b0 = np.array([max(t.net_b0, 0) for t in trades])
    return dict(
        trades=len(trades), held=int(held.sum()), held_pct=round(100 * held.mean(), 1),
        win_pct=round(100 * (net > 0).mean(), 1), win_pct_b0=round(100 * (b0 > 0).mean(), 1),
        avg_win=round(float(wins.mean()), 0) if len(wins) else 0.0, avg_loss=round(float(loss.mean()), 0) if len(loss) else 0.0,
        net=round(float(net.sum())), net_b0=round(float(b0.sum())), diff=round(float(diff.sum())),
        per_trade=round(float(net.mean()), 1), per_trade_b0=round(float(b0.mean()), 1),
        per_day=round(float(net.sum()) / max(calendar_days, 1), 1), per_day_b0=round(float(b0.sum()) / max(calendar_days, 1), 1),
        net15=round(float(sum(t.net15 for t in trades))),
        green_days_pct=round(100 * float((dv > 0).mean()), 1), green_days_pct_b0=round(100 * float((bv > 0).mean()), 1),
        worst_day=round(float(dv.min())), worst_day_b0=round(float(bv.min())),
        max_dd=round(max_dd(dv)), max_dd_b0=round(max_dd(bv)),
        gave_back=gave_back, rescued=rescued,
        helped=int(np.sum(diff > 0)), hurt=int(np.sum(diff < 0)), same=int(np.sum(diff == 0)),
        big_gain_b0_kept=_big_kept(net, b0),
        held_diff_mean=round(float(diff[held].mean()), 1) if held.any() else 0.0,
        n_days=len(days), calendar_days=calendar_days, max_b0=round(float(big_b0.max())),
    )


def _big_kept(net, b0):
    """Of the B0 trades in its top decile (the big overnight gains), the share of their net the variant kept."""
    if len(b0) < 10:
        return None
    cut = np.quantile(b0, 0.9)
    sel = b0 >= cut
    return round(float(net[sel].sum() / b0[sel].sum()), 3) if b0[sel].sum() > 0 else None


# ------------------------------------------------------------------------------------------------ stats
def paired_p(trades: list[Trade]) -> float:
    """One-sided t-test of the per-trade difference (variant - B0) > 0. Trades the variant left alone count as 0."""
    d = np.array([t.net - t.net_b0 for t in trades])
    if len(d) < 3 or np.std(d, ddof=1) == 0:
        return 1.0
    t = d.mean() / (d.std(ddof=1) / math.sqrt(len(d)))
    return float(sst.t.sf(t, len(d) - 1))


def bh(p) -> list:
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)
    q = np.empty(n)
    run = 1.0
    for i in range(n - 1, -1, -1):
        run = min(run, p[o[i]] * n / (i + 1))
        q[o[i]] = run
    return [float(min(x, 1.0)) for x in q]


def twin_p(nights: list[Night], spec: Spec, real_total: float, rate: float, B: int = K.TWINS, seed: int = K.SEED) -> dict:
    """Random-decision twin: hold on random nights with the same frequency (rate), same exits as spec. p = share of
    twins whose total net >= the real total ((1 + #) / (B + 1))."""
    rng = np.random.default_rng(seed)
    hold_t, sell_t = [], []
    for n in nights:
        a, b = trade(n, n.r3, spec, held=True), trade(n, n.r3, spec, held=False)
        if a is None or b is None:
            continue
        hold_t.append(a.net)
        sell_t.append(b.net)
    hv, sv = np.array(hold_t), np.array(sell_t)
    sims = np.empty(B)
    for i in range(B):
        m = rng.random(len(hv)) < rate
        sims[i] = np.where(m, hv, sv).sum()
    return dict(p=float((1 + np.sum(sims >= real_total)) / (B + 1)), twin_mean=round(float(sims.mean())))


def sessions_in(nights: list[Night]) -> int:
    return len({n.d for n in nights})
