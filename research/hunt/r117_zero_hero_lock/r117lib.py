"""R117 engine (PREREG.md): the existing FT3 and Hero tickets with the pre-registered profit locks.

The ENTRIES are the arms' own research code, imported and unchanged:
  - FT3: `ft3_app.pick_at` / `ft3_app.fill` (the frozen app version), R98 charges (`hero2.net_legs`, fractional quantity).
  - Hero: R54's picker and fill (`r54lib.pick` / `r54lib.enter`), R55's 12:30 ticket, R54's sell (`r54lib.sell_at`) and
    R50's dated charges (`r50lib._settle`).
R117 adds one pure function, `decide`, that turns a held option's closes into sale decisions for every exit, and the two
small loops that feed it each engine's own path.

Grid: row t = the 1-minute bar that STARTS at 09:15 + t. A decision on a bar's close sells at the open of a later bar, so
no rule reads a price it could not have seen.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date

import numpy as np

import r117cfg as K
import ft3_app as F
import r98data as R98
import r98lib as L98
from hero2 import net_legs
import r55cfg as K55  # noqa: F401  (sets R54 / R50 / R25 paths)
import r54lib as LB
import r55lib as L55
import r25lib as R25
import r50lib as L50
from r25data import C as HC, O as HO, minute

EPS = 1e-9
ROW_1400 = minute(*K.LATE_FROM)


# ------------------------------------------------------------------------------------------------ the decision rule
def decide(rule: str, e: float, c, ok_dec, ok_upd, late) -> list[tuple[int, float]]:
    """Sale decisions of exit `rule` on one held option.

    e: the fill price. c[i]: the option's close on path row i (row 0 = the fill bar; NaN = no trade). ok_dec[i]: that
    close may trigger a sale (each engine's window). ok_upd[i]: that close counts for the best close, arming and the step
    tiers (R55: proxy closes never do). late[i]: the bar starts at or after 14:00.
    Returns [(i, fraction of the quantity)]: sell at the open of the bar after row i. The rest is held to the end.

    'best' starts at the fill price (R55 T0: the 25% trail from the fill is also a 25% stop), and every check compares
    with max(best, this close), so a new high never triggers its own sale.
    """
    p = K.ALL_EXITS[rule]
    kind = p["kind"]
    out: list[tuple[int, float]] = []
    if kind == "hold":
        return out
    best = e
    held = 1.0
    armed = False          # armed_trail / stop_then_wide (widened) / half_trail (half sold)
    floor = 0.0            # step
    for i in range(len(c)):
        ci = c[i]
        if ci != ci:
            continue
        b = max(best, ci)
        if ok_dec[i]:
            sell = False
            if kind == "trail":
                sell = ci <= (1 - p["g"]) * b + EPS
            elif kind == "armed_trail":
                sell = armed and ci <= (1 - p["g"]) * b + EPS
            elif kind == "step":
                sell = floor > 0 and ci <= floor + EPS
            elif kind == "time_lock":
                sell = bool(late[i]) and b >= p["at"] * e - EPS and ci <= (1 - p["g"]) * b + EPS
            elif kind == "stop_then_wide":
                g = p["g"] if armed else p["stop"]
                sell = ci <= (1 - g) * b + EPS
            elif kind == "half_trail":
                if armed:
                    sell = ci <= (1 - p["g"]) * b + EPS
                elif ok_upd[i] and ci >= p["at"] * e - EPS:
                    out.append((i, 0.5))         # the first half, at the target close
                    held = 0.5
                    armed = True                 # the trail on the other half reads closes from the next bar
            else:
                raise ValueError(kind)
            if sell:
                out.append((i, held))
                return out
        if ok_upd[i]:
            best = max(best, ci)
            if kind == "armed_trail" and ci >= p["arm"] * e - EPS:
                armed = True
            elif kind == "stop_then_wide" and ci >= p["arm"] * e - EPS:
                armed = True
            elif kind == "step":
                for t in p["tiers"]:
                    if ci >= t * e - EPS:
                        floor = max(floor, t / 2 * e)
    return out


# ------------------------------------------------------------------------------------------------ one ticket
@dataclass
class Ticket:
    fam: str
    und: str
    day: date
    side: str
    strike: float
    sig_row: int
    fill_row: int
    entry: float
    reach: float                            # best close from the fill to the end / fill
    nets: dict = field(default_factory=dict)        # exit -> net Rs
    sold_mult: dict = field(default_factory=dict)   # exit -> quantity-weighted sale price / fill
    dec: dict = field(default_factory=dict)         # exit -> tuple of (row, fraction)
    rand: list | None = None                        # nets of a whole sale at each bar open after the fill (twins)


# FT3 -----------------------------------------------------------------------------------------------------------
def ft3_path(a: np.ndarray, kb: int):
    rows = np.arange(kb, F.T_EXIT + 1)
    c = a[rows, R98.C].astype(float)
    ok = ~np.isnan(c)
    ok_dec = ok & (rows > kb) & (rows <= F.T_EXIT - 1)
    late = rows >= R98.minute(*K.LATE_FROM)
    return rows, c, ok_dec, ok, late


def ft3_sale(a: np.ndarray, r: int) -> float:
    """Sell at the open of the first bar from row r to the 15:04 bar that has one; else the last close by then."""
    for j in range(r, F.T_EXIT + 1):
        o = a[j, R98.O]
        if o == o:
            return float(o)
    c = a[:F.T_EXIT + 1, R98.C]
    ok = ~np.isnan(c)
    return float(c[ok][-1]) if ok.any() else 0.0


def ft3_end_price(a: np.ndarray, kb: int) -> float:
    """ft3_app's exit: the last close from the fill bar to the 15:04 bar."""
    c = a[kb:F.T_EXIT + 1, R98.C]
    ok = ~np.isnan(c)
    return float(c[ok][-1]) if ok.any() else 0.0


def ft3_legs(a, kb, rows, decs) -> list[tuple[float, float]]:
    legs, left = [], 1.0
    for i, f in decs:
        legs.append((f, ft3_sale(a, int(rows[i]) + 1)))
        left -= f
    if left > EPS:
        legs.append((left, ft3_end_price(a, kb)))
    return legs


def ft3_tickets(days, exits=None, with_rand: bool = False) -> list[Ticket]:
    """One FT3 ticket per index-day (the frozen app rule), with every exit's net."""
    exits = exits or list(K.ALL_EXITS)
    out = []
    for day in days:
        sp, cache = L98.spot(day), {}
        for t in F.MARKS:
            pk = F.pick_at(day, t, sp, cache)
            if pk is None:
                continue
            k, side, px, _, _ = pk
            a = day.opts[(k, side)]
            f = F.fill(a, t, px, 1)
            if f is not None:
                e, kb = f
                rows, c, ok_dec, ok, late = ft3_path(a, kb)
                reach = float(np.nanmax(c) / e) if ok.any() else 0.0
                tk = Ticket("FT3", day.und, day.day, side, k, t, kb, e, reach)
                for x in exits:
                    d = decide(x, e, c, ok_dec, ok, late)
                    legs = ft3_legs(a, kb, rows, d)
                    tk.nets[x] = float(net_legs(day.und, e, legs))
                    tk.sold_mult[x] = sum(fr * px_ for fr, px_ in legs) / e
                    tk.dec[x] = tuple((int(rows[i]), round(fr, 3)) for i, fr in d)
                if with_rand:
                    tk.rand = [float(net_legs(day.und, e, [(1.0, float(a[r, R98.O]))]))
                               for r in range(kb + 1, F.T_EXIT) if a[r, R98.O] == a[r, R98.O]]
                out.append(tk)
            break                                            # one signal per index-day (filled or not), as ft3_app
    return out


# Hero ----------------------------------------------------------------------------------------------------------
HERO_ENTRY = "1230"
HERO_END = "1505"


def hero_net(day, k, sgn, sig_row, fr, e, exits_) -> float:
    tr = L50.Trade(day.und, day.day, "", sgn, k, sig_row, fr, e, 1, day.lot)
    tr.exits = list(exits_)
    L50._settle(tr)
    return float(tr.net)


def hero_path(day, k, side, fr, end_row):
    a = day.opts[(k, side)]
    rows = np.arange(fr, end_row)
    c = np.full(len(rows), np.nan)
    upd = np.zeros(len(rows), bool)
    for i, r in enumerate(rows):
        _, _, ci, px = R25.path_px(day, k, side, int(r))
        c[i] = ci
        upd[i] = (ci == ci) and not px
    dec = (~np.isnan(c)) & (rows > fr) & (rows <= end_row - 2)
    late = rows >= ROW_1400
    return a, rows, c, dec, upd, late


def hero_tickets(days, exits=None, with_rand: bool = False, mult: float = 1.0) -> list[Ticket]:
    """R55's 12:30 Hero ticket (1 lot CE + 1 lot PE) with every exit, out at 15:05 (R54 / R55 sells and charges)."""
    exits = exits or list(K.ALL_EXITS)
    t = L55.ENTRY_ROW[HERO_ENTRY]
    end_row = LB.bar_start(HERO_END)
    out = []
    for day in days:
        for sgn in (1, -1):
            pk = LB.pick(day, sgn, t)
            if pk is None:
                continue
            k, ref = pk
            side = "C" if sgn > 0 else "P"
            if (k, side) not in day.opts or day.lot <= 0:
                continue
            f = LB.enter(day, k, side, t, ref, end_row, mult)
            if f is None:
                continue
            fr, e = f
            if e * day.lot > K55.K54.BUDGET + EPS:
                continue
            a, rows, c, dec, upd, late = hero_path(day, k, side, fr, end_row)
            reach = float(np.nanmax(np.where(upd, c, np.nan)) / e) if upd.any() else 0.0
            tk = Ticket("HERO", day.und, day.day, side, k, t, fr, e, reach)
            for x in exits:
                if K.ALL_EXITS[x]["kind"] == "half_trail":
                    continue                                    # 1 lot cannot be halved (PREREG section 2)
                d = decide(x, e, c, dec, upd, late)
                ex = []
                for i, fr_ in d:
                    rr, p, _ = LB.sell_at(day, k, side, int(rows[i]) + 1, mult)
                    ex.append((rr, day.lot, p, "lock"))
                if not d:
                    rr, p, _ = LB.sell_at(day, k, side, end_row, mult)
                    ex.append((rr, day.lot, p, "end" + HERO_END))
                tk.nets[x] = hero_net(day, k, sgn, t, fr, e, ex)
                tk.sold_mult[x] = ex[-1][2] / e
                tk.dec[x] = tuple((int(rows[i]), round(fr_, 3)) for i, fr_ in d)
            if with_rand:
                tk.rand = []
                for r in range(fr + 1, end_row):
                    rr, p, _ = LB.sell_at(day, k, side, r, mult)
                    tk.rand.append(hero_net(day, k, sgn, t, fr, e, [(rr, day.lot, p, "twin")]))
            out.append(tk)
    return out


# ------------------------------------------------------------------------------------------------ statistics
def order(ts: list[Ticket]) -> list[Ticket]:
    return sorted(ts, key=lambda x: (x.day, x.und, x.fill_row, x.side))


def worst_run(v: np.ndarray) -> tuple[int, float]:
    """(most losing tickets in a row, the rupees of the worst run of consecutive losing tickets)."""
    n_best, cur_n, cur_s, worst = 0, 0, 0.0, 0.0
    for x in v:
        if x <= 0:
            cur_n += 1
            cur_s += x
            n_best = max(n_best, cur_n)
            worst = min(worst, cur_s)
        else:
            cur_n, cur_s = 0, 0.0
    return n_best, worst


def max_dd(v: np.ndarray) -> float:
    """Largest fall of the cumulative P&L from a previous high (the start counts as a high of 0)."""
    if not len(v):
        return 0.0
    cum = np.concatenate([[0.0], np.cumsum(v)])
    return float((np.maximum.accumulate(cum) - cum).max())


def by_day(ts: list[Ticket], x: str) -> dict:
    out: dict = {}
    for t in ts:
        out[t.day] = out.get(t.day, 0.0) + t.nets[x]
    return out


def boot_mean(ts: list[Ticket], x: str, n: int = K.BOOT, seed: int = K.SEED) -> tuple[float, float]:
    """Day-bootstrap 95% range of the mean net per ticket (calendar days resampled with replacement)."""
    if not ts:
        return math.nan, math.nan
    days = sorted({t.day for t in ts})
    s = np.array([sum(t.nets[x] for t in ts if t.day == d) for d in days])
    c = np.array([sum(1 for t in ts if t.day == d) for d in days])
    rng = np.random.default_rng(seed)
    idx = rng.integers(0, len(days), size=(n, len(days)))
    m = s[idx].sum(1) / c[idx].sum(1)
    return float(np.percentile(m, 2.5)), float(np.percentile(m, 97.5))


def metrics(ts: list[Ticket], x: str, boot: bool = True) -> dict:
    ts = order(ts)
    v = np.array([t.nets[x] for t in ts], float)
    n = len(v)
    if not n:
        return dict(tickets=0)
    bd = sorted(by_day(ts, x).values(), reverse=True)
    wr = worst_run(v)
    lo, hi = boot_mean(ts, x) if boot else (math.nan, math.nan)
    return dict(tickets=n, win=float((v > 0).mean() * 100), mean=float(v.mean()), total=float(v.sum()),
                r3=float(np.mean([t.reach >= 3 for t in ts]) * 100), r5=float(np.mean([t.reach >= 5 for t in ts]) * 100),
                r10=float(np.mean([t.reach >= 10 for t in ts]) * 100),
                sold3=float(np.mean([t.sold_mult[x] >= 3 - EPS for t in ts]) * 100),
                run_n=wr[0], run_rs=wr[1], max_dd=max_dd(v), ex3=float(v.sum() - sum(bd[:3])),
                days=len(bd), boot_lo=lo, boot_hi=hi)


def twin_totals(ts: list[Ticket], x: str, base: str, n: int = K.TWINS, seed: int = K.SEED) -> np.ndarray:
    """PREREG gate 4: tickets whose exit differs from the baseline's are sold whole at a uniformly random bar open strictly
    after the fill and before the end; the others keep the rule's net."""
    rng = np.random.default_rng(seed)
    tot = np.zeros(n)
    for t in ts:
        if t.dec[x] == t.dec[base] or not t.rand:
            tot += t.nets[x]
        else:
            r = np.asarray(t.rand, float)
            tot += r[rng.integers(0, len(r), size=n)]
    return tot


def twin_p(actual: float, twins) -> dict:
    """R115's convention: p = (1 + twins >= actual) / (n + 1); when none reaches it, the normal tail of the twins."""
    t = np.asarray(twins, float)
    n = len(t)
    hits = int((t >= actual - 1e-9).sum())
    p_mc = (1 + hits) / (n + 1)
    sd = float(t.std(ddof=1)) if n > 1 else 0.0
    if sd > 0:
        p_norm = 0.5 * math.erfc((actual - float(t.mean())) / sd / math.sqrt(2.0))
    else:
        p_norm = 0.0 if actual > float(t.mean()) else 1.0
    p = p_mc if hits > 0 else min(p_mc, p_norm)
    return dict(p=float(p), p_mc=float(p_mc), hits=hits, twin_mean=float(t.mean()), twin_sd=sd)


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q of the given p within m_total tests (the other tests taken as p = 1)."""
    p = np.asarray(p, float)
    n = len(p)
    m = max(n, int(m_total or n))
    idx = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = idx[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return q


def count_prior(entries_dir, last_day: str = K.ENTRIES_LAST_DAY, only: tuple | None = None) -> int:
    """R115's count: every family in the entries up to last_day, each the larger of its entry count and its summed
    variants_tried (a family = the id's leading 'r<N>' or its first word). only: restrict to these families."""
    import re
    from collections import defaultdict
    from pathlib import Path

    import yaml
    fam, var = defaultdict(int), defaultdict(int)
    for f in sorted(Path(entries_dir).glob("*.yaml")):
        y = yaml.safe_load(f.read_text())
        if str(y.get("date", "")) > last_day:
            continue
        i = str(y.get("id", ""))
        if i.startswith("r117"):
            continue
        m = re.match(r"r(\d+)", i)
        key = ("R" + m.group(1)) if m else re.split(r"[-_]", i)[0]
        if only is not None and key not in only:
            continue
        fam[key] += 1
        try:
            v = int(y.get("variants_tried") or 1)
        except (TypeError, ValueError):
            v = 1
        var[key] += max(v, 1)
    return sum(max(fam[k], var[k]) for k in fam)


def gates(rule: dict, base: dict, p: float, q: float) -> dict:
    """PREREG section 4. rule / base: {period: metrics}."""
    fails = []
    for per in K.PERIODS:
        if not rule[per]["tickets"] or rule[per]["mean"] <= base[per]["mean"] + EPS:
            fails.append(f"{per}: mean not above today's exit")
    f = rule["final"]
    if f["tickets"] and f["mean"] <= 0:
        fails.append("final mean <= 0")
    if f["tickets"] and f["ex3"] <= 0:
        fails.append("final <= 0 without the 3 best days")
    if f["tickets"] and f["max_dd"] > base["final"]["max_dd"] + EPS:
        fails.append("final drawdown worse")
    if p >= K.GATE_P:
        fails.append(f"twin p {p:.3g} >= {K.GATE_P}")
    if q > K.GATE_Q:
        fails.append(f"BH q {q:.3g} > {K.GATE_Q}")
    if f["tickets"] < K.MIN_FINAL:
        fails.append(f"< {K.MIN_FINAL} final tickets")
    return dict(passed=not fails, fails=fails)
