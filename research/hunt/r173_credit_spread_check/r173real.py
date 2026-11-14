"""R173 Part B (PREREG.md section 3): Boss's credit-spread rules on REAL 1-minute option prices (the R171 release store).

Signal source: the index. Entries and the target / stop are read on the index; the two option legs are only traded.
Every entry decision reads index minutes BEFORE the fill minute (test_r173.py's look-ahead guard checks it).
"""
from __future__ import annotations

import math
import sys
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", ROOT / "research" / "hunt" / "r51", ROOT / "research" / "hunt" / "r171_liquidity_gates"):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import r171data as D  # noqa: E402
from obuy.costs import Costs, Fills  # noqa: E402

from options_lab import costs as OC  # noqa: E402

COSTS = Costs("dated")
FILLS = Fills("liq")
REGIMES = ("roll", "quoted", "stress")
N = 375
O, H, L, C, V = 0, 1, 2, 3, 4

# their constants (both specs)
IDX_STOP_EXT = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "NIFTY": 15.0, "SENSEX": 50.0, "MIDCPNIFTY": 8.0}
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}
SHORT_STEPS, WING_STEPS = 2, 3            # short at ATM -/+ 2 steps, wing 3 steps further out
R72_TARGET, R72_CAT = 3.2, 4.5
LIQ_FLOOR, LIQ_DEFAULT, LIQ_CAT = 0.8, 2.0, 4.5
FIRST_ENTRY, LAST_ENTRY = 5, 315          # 09:20 .. 14:30
SQUARE_OFF = 355                          # 15:10
FILL_SLACK = 3
N_CH, WARM, M_BREADTH = 20, 60, 4
BREADTH_UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")     # MIDCPNIFTY: no data, counts as not breaking
TRADED = ("NIFTY", "BANKNIFTY")
EXPOSURE = 0.02                           # exposure margin, share of the short leg's notional (normal day)
R103_MARGIN = 0.025
ACCOUNT = 56_000.0


def dnum(d: date) -> int:
    return (d - date(1970, 1, 1)).days


# ================================================================================================ their day arrays
def their_I(ix: np.ndarray) -> dict | None:
    """Their build_days: a day with >= 60 index minutes; close forward / back filled, open / high / low filled with it."""
    if np.isfinite(ix[:, C]).sum() < 60:
        return None
    cl = pd.Series(ix[:, C]).ffill().bfill().to_numpy()
    out = {"close": cl}
    for k, j in (("open", O), ("high", H), ("low", L)):
        a = ix[:, j]
        out[k] = np.where(np.isfinite(a), a, cl)
    return out


@dataclass
class Book:
    """One index: its usable days (their filter) with the filled index arrays and the release Day (options)."""
    und: str
    days: list            # [date]
    I: list               # [dict]
    raw: list             # [r171data.Day]
    exp_days: set = field(default_factory=set)


def books(store: dict) -> dict:
    out = {}
    for u in BREADTH_UNDS:
        ds, Is, raws = [], [], []
        for d in store[u]:
            I = their_I(d.ix)
            if I is None:
                continue
            ds.append(d.day)
            Is.append(I)
            raws.append(d)
        out[u] = Book(u, ds, Is, raws, D.expiry_days(store[u]) if u in TRADED else set())
    return out


# ================================================================================================ R72 signals
def bars5(I_list: list) -> np.ndarray:
    """Their build_days_5m: 75 five-minute bars a day, (o, h, l, c), concatenated across days."""
    out = np.empty((len(I_list) * 75, 4))
    for di, I in enumerate(I_list):
        for b in range(75):
            s = 5 * b
            out[di * 75 + b] = (I["open"][s], I["high"][s:s + 5].max(), I["low"][s:s + 5].min(), I["close"][s + 4])
    return out


def donchian_flags(b: np.ndarray):
    """Their build_panel_signals per index: up / dn = close beyond the previous 20 bars' high / low (bars >= 20)."""
    T = len(b)
    chi = np.full(T, np.nan)
    clo = np.full(T, np.nan)
    for i in range(N_CH, T):
        chi[i] = b[i - N_CH:i, 1].max()
        clo[i] = b[i - N_CH:i, 2].min()
    idx = np.arange(T)
    with np.errstate(invalid="ignore"):
        up = (b[:, 3] > chi) & (idx >= N_CH)
        dn = (b[:, 3] < clo) & (idx >= N_CH)
    return up, dn


def r72_signals(bk: dict) -> dict:
    """{und: [(day, bar, side)]} in time order: a bar where >= 4 indices (of the 4 with data) break the same way, on each
    breaking index, after its 60-bar warm-up (their rule with MIDCPNIFTY not breaking)."""
    flags, pos = {}, {}
    for u in BREADTH_UNDS:
        up, dn = donchian_flags(bars5(bk[u].I))
        flags[u] = (up, dn)
        pos[u] = {d: k for k, d in enumerate(bk[u].days)}
    keys = sorted({(d, b) for u in BREADTH_UNDS for d in bk[u].days for b in range(75)})
    sigs = {u: [] for u in BREADTH_UNDS}
    for d, b in keys:
        for s, f in ((1, 0), (-1, 1)):
            hit = []
            for u in BREADTH_UNDS:
                k = pos[u].get(d)
                if k is None:
                    continue
                j = k * 75 + b
                if j >= WARM and bool(flags[u][f][j]):
                    hit.append(u)
            if len(hit) >= M_BREADTH:
                for u in hit:
                    sigs[u].append((d, b, s))
    return sigs


def r72_entries(bk: dict, sigs: dict, start: date, end: date) -> list:
    """Their C1_Enh_FreshBreadth_CreditSpread entry filter (fresh, 09:20 - 14:30, TWAP side) WITHOUT the look-ahead:
    the TWAP is the mean of the 1-minute closes BEFORE the entry minute. Caps are applied in the walk (walk_r72)."""
    out = []
    for u in TRADED:
        B = bk[u]
        pos = {d: k for k, d in enumerate(B.days)}
        by_day: dict = {}
        for d, b, s in sigs[u]:
            if start <= d <= end:
                by_day.setdefault(d, []).append((b, s))
        for d, lst in sorted(by_day.items()):
            if d in B.exp_days:
                continue
            I = B.I[pos[d]]
            prev = {1: -99, -1: -99}
            for b, s in lst:
                consec = b == prev[s] + 1
                prev[s] = b
                if consec:
                    continue
                e = (b + 1) * 5
                if e < FIRST_ENTRY or e > LAST_ENTRY:
                    continue
                ix0 = I["open"][e]
                twap = I["close"][:e].mean()
                if s * (ix0 - twap) < 0:
                    continue
                out.append(dict(und=u, day=d, k=pos[d], e=e, side=s, ix0=float(ix0),
                                target=float(ix0 + s * R72_TARGET * IDX_STOP_EXT[u]),
                                stop=float(ix0 - s * R72_CAT * IDX_STOP_EXT[u]), newlvl=()))
    return out


# ================================================================================================ LIQ-2 signals
def liq_entries(bk: dict, start: date, end: date, und_list=TRADED) -> list:
    """Their Enhanced LIQ-2 entries on 15-minute bars (zones from indicator/liquidity.py, which their port copies), one
    position at a time per index. Exits are resolved by the spread simulator: the index target / catastrophe / 15:10
    and a NEW same-side level, which is known at the CLOSE of the bar that confirms it (their script exits at that bar's
    first minute: a look-ahead, removed here). The one-at-a-time book is walked with the INDEX exit minute (no option
    data needed to know when the index position would close), so it is the same for real and modelled prices."""
    from indicator.liquidity import pool_zones, swing_zones
    out = []
    for u in und_list:
        B = bk[u]
        rows = []
        for di, I in enumerate(B.I):
            for s in range(0, N, 15):
                e = min(s + 15, N)
                rows.append((di, s, e, I["open"][s], I["high"][s:e].max(), I["low"][s:e].min(), I["close"][e - 1]))
        b15 = pd.DataFrame(rows, columns=["di", "s", "e", "open", "high", "low", "close"])
        z15 = swing_zones(b15, 20, "full") + pool_zones(b15, 2, 5, 10)
        DI, S, E = b15.di.values, b15.s.values, b15.e.values
        known: dict = {}
        brk: dict = {}
        for z in z15:
            known.setdefault(z.known, []).append(z)
            if z.broken >= 0:
                brk.setdefault(z.broken, []).append(z)
        busy_until = (-1, -1)                                  # (day index, minute) the open position exits at
        for i in range(len(b15)):
            di = DI[i]
            if (di, E[i]) <= busy_until:
                continue
            if not (FIRST_ENTRY <= S[i] <= LAST_ENTRY):
                continue
            d = B.days[di]
            if not (start <= d <= end):
                continue
            cand = [z for z in brk.get(i, []) if z.kind == "pool" and any(
                q.kind == "swing" and q.side == z.side and q.known <= i and (q.broken < 0 or q.broken >= i)
                and q.bottom <= z.top and z.bottom <= q.top for q in z15)]
            if not cand:
                continue
            z = cand[0]
            sg = z.side
            I = B.I[di]
            e = int(min(E[i], N - 1))
            ix = float(I["open"][e])
            istop = IDX_STOP_EXT[u]
            ahead = [q.edge for q in z15 if q.side == sg and q.known <= i and (q.broken < 0 or q.broken > i)
                     and sg * (q.edge - ix) >= LIQ_FLOOR * istop]
            target = (min(ahead) if sg > 0 else max(ahead)) if ahead else ix + sg * LIQ_DEFAULT * istop
            stop = float(z.edge - sg * LIQ_CAT * istop)
            # every bar of the day whose close confirms a new same-side level: an exit trigger at that bar's last minute
            # (index_exit only looks from the fill minute on, so for this trade only the bars after bar i can act; a
            # random twin entering earlier uses the earlier ones too)
            nl = tuple(int(E[j] - 1) for j in np.flatnonzero(DI == di) if any(q.side == sg for q in known.get(j, [])))
            ent = dict(und=u, day=d, k=di, e=e, side=sg, ix0=ix, target=float(target), stop=stop, newlvl=nl,
                       level=float(z.edge))
            xm, _ = index_exit(I, e, sg, ent["target"], stop, nl)
            busy_until = (di, xm)
            out.append(ent)
    return out


# ================================================================================================ the spread
def index_exit(I: dict, e: int, sg: int, target: float, stop: float, newlvl=()) -> tuple[int, str]:
    """The minute the index exit is SEEN and why. Checked from the entry minute; catastrophe first, then target, then a
    new level confirmed at that minute's close; nothing by 15:10 -> 'sq' at minute 355 (filled at its open)."""
    nl = set(newlvl)
    for m in range(e, SQUARE_OFF):
        lo, hi = I["low"][m], I["high"][m]
        if (sg > 0 and lo < stop) or (sg < 0 and hi > stop):
            return m, "catastrophe"
        if (sg > 0 and hi >= target) or (sg < 0 and lo <= target):
            return m, "target"
        if m in nl:
            return m, "new_level"
    return SQUARE_OFF, "sq"


def strikes(spot: float, sg: int, step: float) -> tuple[float, float, str]:
    atm = math.floor(spot / step + 0.5) * step
    ks = atm - sg * SHORT_STEPS * step
    kw = ks - sg * WING_STEPS * step
    return ks, kw, ("P" if sg > 0 else "C")


def first_print(A: np.ndarray, r: int, slack: int = FILL_SLACK) -> int:
    for j in range(r, min(r + slack, N)):
        if math.isfinite(A[j, O]) and A[j, O] > 0:
            return j
    return -1


def vol5_lots(A: np.ndarray, r: int, lot: int) -> float:
    v = A[max(r - 5, 0):r, V]
    return float(np.nansum(v)) / lot


def last_close(A: np.ndarray, r: int) -> float:
    c = A[:r + 1, C]
    ok = np.flatnonzero(np.isfinite(c) & (c > 0))
    return float(c[ok[-1]]) if len(ok) else math.nan


@dataclass
class Spread:
    strat: str
    und: str
    day: date
    side: int
    e: int                 # intended fill minute
    fill: int              # actual fill minute (both legs)
    xm: int                # exit seen
    xfill: int             # exit fill minute
    why: str
    ks: float
    kw: float
    right: str
    exp: date | None
    dte: int
    lot: int
    spot: float
    s_in: float            # raw prices
    w_in: float
    s_out: float
    w_out: float
    stale_exit: bool
    net: float             # R17 fills + dated charges
    net_reg: dict          # options_lab spread law regimes + dated charges
    margin: float
    margin_r103: float
    gross_pts: float       # raw credit change per unit (before costs)
    idx_pts: float         # index points in the break direction, entry open -> exit fill minute open


def charges(sell: bool, px: float, qty: int, dn: int) -> float:
    return float(COSTS.charge(not sell, np.array([px]), np.array([qty]), np.array([dn]))[0])


def simulate(ent: dict, B: Book, strat: str) -> Spread | str:
    """One spread on real prices, or the reason it could not be traded ('no_strike' / 'no_fill')."""
    u, sg, e = ent["und"], ent["side"], ent["e"]
    d = B.raw[ent["k"]]
    I = B.I[ent["k"]]
    spot = float(I["close"][e - 1])
    ks, kw, right = strikes(spot, sg, STEP[u])
    As, Aw = d.opts.get((ks, right)), d.opts.get((kw, right))
    if As is None or Aw is None:
        return "no_strike"
    f = -1
    for j in range(e, min(e + FILL_SLACK, SQUARE_OFF)):
        if first_print(As, j, 1) == j and first_print(Aw, j, 1) == j:
            f = j
            break
    if f < 0:
        return "no_fill"
    xm, why = index_exit(I, f, sg, ent["target"], ent["stop"], ent.get("newlvl", ()))
    xr = xm + 1 if why != "sq" else SQUARE_OFF
    stale = False
    outs = []
    for A in (As, Aw):
        j = first_print(A, xr)
        if j >= 0:
            outs.append(float(A[j, O]))
        else:
            outs.append(last_close(A, min(xr, N - 1)))
            stale = True
    s_in, w_in = float(As[f, O]), float(Aw[f, O])
    s_out, w_out = outs
    lot = int(d.lot)
    dn = dnum(d.day)
    stop = why == "catastrophe"
    # R17 fills on each order
    v_s_in, v_w_in = vol5_lots(As, f, lot), vol5_lots(Aw, f, lot)
    v_s_out, v_w_out = vol5_lots(As, xr, lot), vol5_lots(Aw, xr, lot)
    fs_in = float(FILLS.sell(s_in, v_s_in))
    fw_in = float(FILLS.buy(w_in, v_w_in))
    fs_out = float(FILLS.buy(s_out, v_s_out)) if not stop else _stop_buy(s_out, v_s_out)
    fw_out = float(FILLS.sell(w_out, v_w_out, stop=stop))
    ch = (charges(True, fs_in, lot, dn) + charges(False, fw_in, lot, dn)
          + charges(False, fs_out, lot, dn) + charges(True, fw_out, lot, dn))
    net = ((fs_in - fs_out) - (fw_in - fw_out)) * lot - ch
    net_reg = {}
    for g in REGIMES:
        hs = lambda p: OC.spread_per_unit(premium=float(max(p, 0.0)), regime=g) / 2.0  # noqa: E731
        a, b_, c_, d_ = s_in - hs(s_in), w_in + hs(w_in), s_out + hs(s_out), max(w_out - hs(w_out), 0.0)
        chg = (charges(True, max(a, 0.0), lot, dn) + charges(False, b_, lot, dn)
               + charges(False, c_, lot, dn) + charges(True, d_, lot, dn))
        net_reg[g] = ((a - c_) - (b_ - d_)) * lot - chg
    width = abs(ks - kw)
    credit = fs_in - fw_in
    margin = max(width - credit, 0.0) * lot + EXPOSURE * spot * lot
    exp = d.exp
    dte = (exp - d.day).days if exp else -1
    idx_pts = sg * (float(I["open"][min(xr, N - 1)]) - float(I["open"][f]))
    return Spread(strat, u, d.day, sg, e, f, xm, xr, why, ks, kw, right, exp, dte, lot, spot, s_in, w_in, s_out, w_out,
                  stale, net, net_reg, margin, R103_MARGIN * spot * lot, (s_in - s_out) - (w_in - w_out), idx_pts)


def _stop_buy(p: float, vol: float) -> float:
    """A stop-type BUY (buying the short back on the catastrophe exit): +10 bps and twice the R17 ticks (Fills has no
    stop flag on buys; this mirrors its sell side)."""
    from obuy.costs import adverse_bps
    x = float(adverse_bps(np.array([p]), FILLS.bps_stop, True)[0])
    ticks = float(FILLS._ticks(np.array([vol]), np.array([p]))[0])
    return round(x + 2 * ticks * 0.05, 2)


# ================================================================================================ walks
def walk_r72(ents: list, bk: dict) -> tuple[list, dict]:
    """1 open and 1 entry per side per day per index, in time order (their caps). A break that cannot be traded (no
    strike / no fill) holds nothing (R171's convention)."""
    out, skipped = [], {"no_strike": 0, "no_fill": 0}
    taken: dict = {}
    for en in sorted(ents, key=lambda x: (x["day"], x["e"], x["und"])):
        key = (en["und"], en["day"], en["side"])
        if taken.get(key):
            continue
        r = simulate(en, bk[en["und"]], "R72-CS")
        if isinstance(r, str):
            skipped[r] += 1
            continue
        taken[key] = True
        out.append(r)
    return out, skipped


def walk_liq(ents: list, bk: dict) -> tuple[list, dict]:
    out, skipped = [], {"no_strike": 0, "no_fill": 0}
    for en in ents:
        r = simulate(en, bk[en["und"]], "LIQ2-CS")
        if isinstance(r, str):
            skipped[r] += 1
            continue
        out.append(r)
    return out, skipped


def account(trades: list, capital: float = ACCOUNT, margin_key: str = "margin") -> tuple[list, int]:
    """The Rs 56k account: in time order across indices, a spread is taken only if the margin of the spreads still open
    (exit fill minute after this fill minute, same day) plus its own fits. Positions are intraday, so days are
    independent. Returns (taken, not_taken_for_margin)."""
    taken, miss = [], 0
    by_day: dict = {}
    for t in trades:
        by_day.setdefault(t.day, []).append(t)
    for d in sorted(by_day):
        open_: list = []
        for t in sorted(by_day[d], key=lambda x: (x.fill, x.und)):
            open_ = [o for o in open_ if o.xfill > t.fill]
            used = sum(getattr(o, margin_key) for o in open_)
            if used + getattr(t, margin_key) <= capital:
                taken.append(t)
                open_.append(t)
            else:
                miss += 1
    return taken, miss


def to_frame(trades: list) -> pd.DataFrame:
    if not trades:
        return pd.DataFrame()
    rows = []
    for t in trades:
        r = {k: getattr(t, k) for k in ("strat", "und", "day", "side", "e", "fill", "xm", "xfill", "why", "ks", "kw",
                                         "right", "dte", "lot", "spot", "s_in", "w_in", "s_out", "w_out", "stale_exit",
                                         "net", "margin", "margin_r103", "gross_pts", "idx_pts")}
        for g, v in t.net_reg.items():
            r["net_" + g] = v
        rows.append(r)
    return pd.DataFrame(rows)


# ================================================================================================ descriptive: implied delta
def bs_delta(premium: float, spot: float, strike: float, right: str, t_years: float) -> float:
    """Black-76-style delta (r = 0, forward ~ spot) at the implied vol that reprices the premium; NaN if none."""
    from scipy.optimize import brentq
    from scipy.stats import norm

    def price(sig):
        sd = sig * math.sqrt(t_years)
        d1 = (math.log(spot / strike) + 0.5 * sd * sd) / sd
        d2 = d1 - sd
        if right == "C":
            return spot * norm.cdf(d1) - strike * norm.cdf(d2)
        return strike * norm.cdf(-d2) - spot * norm.cdf(-d1)

    try:
        if not (price(0.01) < premium < price(3.0)):
            return math.nan
        sig = brentq(lambda s: price(s) - premium, 0.01, 3.0)
    except (ValueError, ZeroDivisionError):
        return math.nan
    sd = sig * math.sqrt(t_years)
    d1 = (math.log(spot / strike) + 0.5 * sd * sd) / sd
    return float(norm.cdf(d1) if right == "C" else norm.cdf(d1) - 1.0)
