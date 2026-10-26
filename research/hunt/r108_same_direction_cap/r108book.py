"""R108 book (PREREG sections 2 and 4), pure: the paper book's walk as the app runs it, with the same-direction cap or one
of the seven alternatives, plus the risk maths, random-refusal twins, BH and the gates. test_r108.py covers every function.

A candidate is a dict with at least:
    arm   'LIQ' / 'R72' / 'R51' / 'R52'        und   the index            side  'C' / 'P'
    er    the decision row (minute index from 09:15; the bar close the arm decides at)
    g     its unit (one index-day-book of one arm)  how   the arm's taking rule: 'book' / 'side2' / 'one' (R101)
    j     its place in the unit (time order)        strike, book (Liquidity's book), risk (rupees to its stop, 1 lot)
and its priced result r (None = no fill) with er (fill row), xr (exit row), net (1 lot, after charges).

The app's minute (PREREG section 2): Liquidity's books first (the ORB step), then R72, R51, R52 (the passed arms' pass);
inside an arm the indices in the app's order. A passed arm's refused entry becomes a WOULD-BE trade (its own caps count it;
the account does not); Liquidity's refused entry books nothing (its book stays free).
"""
from __future__ import annotations

import math
from collections import defaultdict
from datetime import date

import numpy as np

PASSED = ("R72", "R51", "R52")
APP_ARM_ORDER = ("LIQ", "R72", "R51", "R52")
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
LIQ_BOOK_ORDER = (("BANKNIFTY", 15), ("BANKNIFTY", 5), ("FINNIFTY", 30), ("FINNIFTY", 5), ("MIDCPNIFTY", 15),
                  ("MIDCPNIFTY", 5))
# R72's window (PassedArmsHost.entryMinute / placeKeptFor): minute 0-2 of each 5-minute bar close, 09:20 to 15:12.
R72_FIRST_ROW = 5
R72_LAST_ROW = 357
R72_FILL_SLACK = 2            # DonchianArm.FILL_SLACK_MINUTES

# outcomes
REAL, WB_RULE, WB_HELD, DROP, OWN_SKIP, NO_FILL = "real", "wb_rule", "wb_held", "drop", "own_skip", "no_fill"


def direction(side: str) -> int:
    return 1 if str(side).upper().startswith("C") else -1


def in_r72_window(row: int) -> bool:
    return R72_FIRST_ROW <= row <= R72_LAST_ROW and (row - R72_FIRST_ROW) % 5 <= 2


def liq_book_rank(c: dict) -> int:
    key = (c["und"], int(c.get("book") or 0))
    return LIQ_BOOK_ORDER.index(key) if key in LIQ_BOOK_ORDER else len(LIQ_BOOK_ORDER)


def order_key(c: dict, arm_order=APP_ARM_ORDER) -> tuple:
    """The app's order inside one decision minute: arm, then index (Liquidity: its book order), then time in the unit."""
    a = arm_order.index(c["arm"])
    sub = liq_book_rank(c) if c["arm"] == "LIQ" else UNDS.index(c["und"])
    return (c["er"], a, sub, c["g"], c["j"])


# ------------------------------------------------------------------------------------------------ the rules
class Rule:
    """Decides whether an automatic entry may open. `refuse(ctx, c)` -> None (may go) or the reason."""
    name = "rule"
    keeps_r72 = False         # the app's "last place kept for R72"
    arm_order = None          # None = the app's order

    def refuse(self, ctx: "Ctx", c: dict):
        return None


class NoCap(Rule):
    name = "NOCAP"


class CountCap(Rule):
    """At most `max_n` open a way across all indices (the app's SameDirectionCap.refusal), R72's place kept if asked.
    counted: the arms the cap counts and refuses (None = all)."""
    def __init__(self, max_n: int = 2, keep_r72: bool = True, counted=None, name: str | None = None):
        self.max_n, self.keeps_r72, self.counted = max_n, keep_r72, counted
        self.name = name or f"CAP{max_n}"

    def refuse(self, ctx, c):
        if self.counted is not None and c["arm"] not in self.counted:
            return None
        d = direction(c["side"])
        same = [p for p in ctx.open_real(c["er"]) if p["dir"] == d and (self.counted is None or p["arm"] in self.counted)]
        return count_refusal(same, self.max_n, c, ctx, self.keeps_r72)


def count_refusal(same: list, cap: int, c: dict, ctx: "Ctx", keep_r72: bool):
    """SameDirectionCap.refusal: full; else the last place kept for R72 while it may still want it."""
    cap = max(1, cap)
    if len(same) >= cap:
        return f"cap: {len(same)} open"
    if keep_r72 and c["arm"] != "R72" and len(same) >= cap - 1 and not any(p["arm"] == "R72" for p in same):
        if ctx.r72_kept(c):
            return "cap: last place kept for R72"
    return None


class IndexCap(Rule):
    """At most `per_index` open a way on the same index across arms; optionally also at most `across` a way in all
    (R72's place kept for that count only)."""
    def __init__(self, per_index: int = 1, across: int | None = None, name: str | None = None):
        self.per_index, self.across = per_index, across
        self.keeps_r72 = across is not None
        self.name = name or (f"IDX{per_index}" + (f"C{across}" if across else ""))

    def refuse(self, ctx, c):
        d = direction(c["side"])
        same = [p for p in ctx.open_real(c["er"]) if p["dir"] == d]
        if sum(1 for p in same if p["und"] == c["und"]) >= self.per_index:
            return "index cap"
        if self.across is not None:
            return count_refusal(same, self.across, c, ctx, True)
        return None


class RiskCap(Rule):
    """Open stop-risk a way plus the new entry's at most `limit` rupees."""
    def __init__(self, limit: float = 6500.0, name: str = "RISK"):
        self.limit, self.name = limit, name

    def refuse(self, ctx, c):
        d = direction(c["side"])
        used = sum(p["risk"] for p in ctx.open_real(c["er"]) if p["dir"] == d)
        return "risk cap" if used + c["risk"] > self.limit + 1e-9 else None


class EdgeCap(CountCap):
    """Cap 2, no kept place; inside a minute the arms decide by their per-trade net so far (ctx.edge_order)."""
    def __init__(self, max_n: int = 2, name: str = "EDGE"):
        super().__init__(max_n, keep_r72=False, name=name)
        self.uses_edge = True


class Stops(Rule):
    """No same-direction cap; no new entry once the day's / week's / month's closed net reaches its limit."""
    def __init__(self, day: float = -6500.0, week: float = -20000.0, month: float = -13000.0, name: str = "STOPS"):
        self.day, self.week, self.month, self.name = day, week, month, name

    def refuse(self, ctx, c):
        dn, wn, mn = ctx.closed_nets(c["er"])
        if dn <= self.day:
            return "day stop"
        if wn <= self.week:
            return "week stop"
        if mn <= self.month:
            return "month stop"
        return None


def make_rule(name: str) -> Rule:
    return {
        "NOCAP": lambda: NoCap(),
        "CAP2": lambda: CountCap(2),
        "CAP3": lambda: CountCap(3),
        "IDX1": lambda: IndexCap(1),
        "IDX1C3": lambda: IndexCap(1, 3),
        "RISK": lambda: RiskCap(6500.0),
        "EDGE": lambda: EdgeCap(2),
        "STOPS": lambda: Stops(),
        "EXEMPT": lambda: CountCap(2, keep_r72=True, counted=("R72", "R51"), name="EXEMPT"),
    }[name]()


# ------------------------------------------------------------------------------------------------ one day
def week_key(d: date) -> tuple:
    y, w, _ = d.isocalendar()
    return (y, w)


class Ctx:
    """What the rule may see at a decision: the real positions open at that minute, R72's due breaks, the closed nets."""
    def __init__(self, day: date, prior: dict):
        self.day = day
        self.real: list = []                 # dicts: arm, und, dir, strike, dec, xr, risk, net
        self.prior = prior                   # {'week': net, 'month': net} closed before this day
        self.r72_due_ways: set = set()       # ways R72 had a break due at the start of this minute's pass
        self.r72_pass_started = False        # Liquidity decides before R72 has looked at the bar

    def open_real(self, row: int) -> list:
        """Real positions decided at or before `row` and not yet out (exit row inclusive, R101's convention)."""
        return [p for p in self.real if p["dec"] <= row <= p["xr"]]

    def r72_kept(self, c: dict) -> bool:
        """PassedArmsHost.placeKeptFor: R72's window, and (before its pass) always, (after it) a due break that way."""
        if not in_r72_window(c["er"]):
            return False
        if c["arm"] == "LIQ" or not self.r72_pass_started:
            return True
        return direction(c["side"]) in self.r72_due_ways

    def closed_nets(self, row: int) -> tuple:
        today = sum(p["net"] for p in self.real if p["xr"] < row)
        return today, self.prior["week"] + today, self.prior["month"] + today


def walk_day(cands: list, priced: list, rule: Rule, prior: dict | None = None, day: date | None = None,
             arm_order=APP_ARM_ORDER, max_per_side: int = 2, held_rule: bool = True, would_be: bool = True) -> list:
    """One day of the paper book. cands[i] / priced[i]: the day's candidates of all arms and their results. Returns a
    list of records (one per candidate the walk looked at): dict(c=cand, r=result, out=outcome, why=reason).
    held_rule False: no "one option, one arm" rule; would_be False: a passed arm's refused entry books nothing and frees its
    book like Liquidity's (both False plus R107's order and no kept place = R107's model, for the attribution only)."""
    prior = prior or {"week": 0.0, "month": 0.0}
    ctx = Ctx(day, prior)
    idx = sorted(range(len(cands)), key=lambda i: order_key(cands[i], arm_order))
    busy, n_side, one_done = {}, {}, set()
    recs = []
    # R72's breaks that could still fill (decision row .. +slack); one with no fill stays due through its window
    r72 = [i for i in idx if cands[i]["arm"] == "R72"]
    decided = set()
    by_row = defaultdict(list)
    for i in idx:
        by_row[cands[i]["er"]].append(i)
    for row in sorted(by_row):
        ctx.r72_pass_started = False
        ctx.r72_due_ways = {direction(cands[i]["side"]) for i in r72
                            if cands[i]["er"] <= row <= cands[i]["er"] + R72_FILL_SLACK and i not in decided}
        for i in by_row[row]:
            c, r = cands[i], priced[i]
            if c["arm"] != "LIQ":
                ctx.r72_pass_started = True
            if r is None:
                recs.append(dict(c=c, r=None, out=NO_FILL, why="no fill"))
                if c["er"] + R72_FILL_SLACK < row:
                    decided.add(i)
                continue
            decided.add(i)
            how = c["how"]
            bk = (c["g"], c.get("book")) if how == "book" else ((c["g"], c["side"]) if how == "side2" else (c["g"],))
            if row <= busy.get(bk, -1) or (how == "side2" and n_side.get(bk, 0) >= max_per_side) or \
                    (how == "one" and c["g"] in one_done):
                recs.append(dict(c=c, r=r, out=OWN_SKIP, why="own rule"))
                continue
            d = direction(c["side"])
            held = held_rule and c["arm"] in PASSED and any(
                p["arm"] in PASSED and p["arm"] != c["arm"] and p["und"] == c["und"] and p["dir"] == d
                and p.get("strike") == c.get("strike") for p in ctx.open_real(row))
            why = None if held else rule.refuse(ctx, c)
            if held:
                out = WB_HELD
            elif why is not None:
                out = WB_RULE if (c["arm"] in PASSED and would_be) else DROP
            else:
                out = REAL
            if out == DROP:
                recs.append(dict(c=c, r=r, out=DROP, why=why))
                continue
            busy[bk] = r["xr"]
            n_side[bk] = n_side.get(bk, 0) + 1
            one_done.add(c["g"])
            if out == REAL:
                ctx.real.append(dict(arm=c["arm"], und=c["und"], dir=d, strike=c.get("strike"), dec=row, xr=r["xr"],
                                     risk=float(c.get("risk", 0.0)), net=float(r["net"])))
            recs.append(dict(c=c, r=r, out=out, why=why or ("held by another arm" if held else None)))
    return recs


def walk_period(days: list, day_cands: dict, rule: Rule, edge_order=None, arm_order=APP_ARM_ORDER, **kw) -> dict:
    """Walks the days in order, carrying the week's and month's closed net (for STOPS). day_cands[d] = (cands, priced).
    edge_order(d) -> the arm order for that day (EDGE) or None. Returns {day: recs}."""
    out = {}
    week, month, wk, mo = 0.0, 0.0, None, None
    for d in days:
        if week_key(d) != wk:
            wk, week = week_key(d), 0.0
        if (d.year, d.month) != mo:
            mo, month = (d.year, d.month), 0.0
        cands, priced = day_cands.get(d, ([], []))
        order = edge_order(d) if (edge_order is not None and getattr(rule, "uses_edge", False)) else arm_order
        recs = walk_day(cands, priced, rule, {"week": week, "month": month}, d, order, **kw)
        net = sum(x["r"]["net"] for x in recs if x["out"] == REAL)
        week += net
        month += net
        out[d] = recs
    return out


def edge_orders(days: list, records: dict, default=APP_ARM_ORDER) -> dict:
    """EDGE's arm order per day: by each arm's per-trade net over its trades (real and would-be, the arm's own record)
    that CLOSED on earlier days (records[d] = [(arm, net)]); an arm with no history keeps its place in `default`."""
    tot, n = defaultdict(float), defaultdict(int)
    out = {}
    for d in sorted(set(days) | set(records)):
        if d in days:
            def key(a):
                has = n[a] > 0
                return (0 if has else 1, -(tot[a] / n[a]) if has else 0.0, default.index(a))
            ranked = sorted(default, key=key)
            # arms with no history keep the app's relative order after those that have one
            out[d] = tuple(ranked)
        for a, x in records.get(d, []):
            tot[a] += x
            n[a] += 1
    return out


# ------------------------------------------------------------------------------------------------ series and risk
def trades_of(walk: dict, outs=(REAL,)) -> list:
    res = []
    for d, recs in walk.items():
        for x in recs:
            if x["out"] in outs:
                t = dict(x["r"])
                t.update(day=d, arm=x["c"]["arm"], und=x["c"]["und"], side=x["c"]["side"], er_sig=x["c"]["er"],
                         book=x["c"].get("book"), strike=x["c"].get("strike"), out=x["out"])
                res.append(t)
    return res


def daily_vector(trades: list, sessions: list) -> np.ndarray:
    ix = {d: i for i, d in enumerate(sessions)}
    v = np.zeros(len(sessions))
    for t in trades:
        i = ix.get(t["day"])
        if i is not None:
            v[i] += t["net"]
    return v


def max_dd(v: np.ndarray) -> float:
    eq = np.concatenate([[0.0], np.cumsum(v)])
    return float((np.maximum.accumulate(eq) - eq).max())


def ratio(net: float, risk: float) -> float:
    if risk > 0:
        return net / risk
    return math.inf if net > 0 else 0.0


def dd_breaches(v: np.ndarray, limit: float = 40_000.0) -> int:
    """Separate times the drawdown reached `limit` (a new breach needs a new equity high first)."""
    eq = np.concatenate([[0.0], np.cumsum(v)])
    peak, n, inside = 0.0, 0, False
    for x in eq:
        if x > peak:
            peak, inside = x, False
        if not inside and peak - x >= limit:
            n, inside = n + 1, True
    return n


def summary(trades: list, sessions: list, month_limit: float = -13_000.0, dd_limit: float = 40_000.0) -> dict:
    v = daily_vector(trades, sessions)
    months = defaultdict(float)
    for d, x in zip(sessions, v):
        months[(d.year, d.month)] += x
    mv = list(months.values())
    net = float(v.sum())
    mdd = max_dd(v)
    wins = sum(1 for t in trades if t["net"] > 0)
    return dict(net=round(net), trades=len(trades), win=round(wins / len(trades), 3) if trades else None,
                worst_day=round(float(v.min())) if len(v) else 0, max_dd=round(mdd), r_dd=ratio(net, mdd),
                months=len(mv), worst_month=round(min(mv)) if mv else 0,
                months_below=int(sum(1 for x in mv if x <= month_limit)),
                days_below=int((v <= month_limit).sum()), dd_breaches=dd_breaches(v, dd_limit),
                sessions=len(sessions))


# ------------------------------------------------------------------------------------------------ twins
def twin_matrix(base_by_day: dict, k_by_day: dict, sessions: list, n_twins: int, rng, chunk: int = 20_000) -> np.ndarray:
    """(n_twins, sessions) daily nets: each day the base trades' nets (base_by_day[d], a list) with k_by_day[d] of them
    removed uniformly at random."""
    out = np.zeros((n_twins, len(sessions)), dtype=np.float64)
    for i, d in enumerate(sessions):
        nets = np.asarray(base_by_day.get(d, []), float)
        tot = nets.sum()
        k = int(min(max(k_by_day.get(d, 0), 0), len(nets)))
        if k == 0:
            out[:, i] = tot
            continue
        if k == len(nets):
            out[:, i] = 0.0
            continue
        for s in range(0, n_twins, chunk):
            e = min(n_twins, s + chunk)
            pick = np.argpartition(rng.random((e - s, len(nets))), k - 1, axis=1)[:, :k]
            out[s:e, i] = tot - nets[pick].sum(axis=1)
    return out


def matrix_r_dd(M: np.ndarray) -> np.ndarray:
    net = M.sum(axis=1)
    eq = np.concatenate([np.zeros((M.shape[0], 1)), np.cumsum(M, axis=1)], axis=1)
    mdd = (np.maximum.accumulate(eq, axis=1) - eq).max(axis=1)
    return np.where(mdd > 0, net / np.where(mdd > 0, mdd, 1.0), np.where(net > 0, np.inf, 0.0))


def twin_p(actual: float, twins: np.ndarray) -> float:
    return float((1 + int((np.asarray(twins) >= actual).sum())) / (len(twins) + 1))


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q; m_total > len(p) counts the earlier tests as p = 1."""
    p = np.asarray(p, float)
    n = len(p)
    m = max(m_total or n, n)
    if n == 0:
        return p
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


# ------------------------------------------------------------------------------------------------ gates
def gate_period(v: dict, cap2: dict) -> list:
    """PREREG gates 1-2 for one period: the failures (empty = passes)."""
    f = []
    if not v["net"] > cap2["net"]:
        f.append("net not above CAP2's")
    for k, w in (("months_below", "months at or below -Rs 13,000"), ("dd_breaches", "Rs 40,000 drawdown breaches"),
                 ("days_below", "days at or below -Rs 13,000")):
        if v[k] > cap2[k]:
            f.append(f"more {w} than CAP2 ({v[k]} vs {cap2[k]})")
    return f


def gates(per: dict, q_all: float, changed_final: int, periods=("design", "wf", "final"), q_max: float = 0.10,
          min_changed: int = 30) -> dict:
    """per[period] = (variant summary, CAP2 summary)."""
    fails = []
    for p in periods:
        fails += [f"{p}: {x}" for x in gate_period(*per[p])]
    if not q_all <= q_max:
        fails.append(f"twin BH q {q_all:.3f} > {q_max}")
    if changed_final < min_changed:
        fails.append(f"only {changed_final} final trades differ from CAP2's (< {min_changed})")
    return dict(passed=not fails, fails=fails, q_all=round(float(q_all), 4))


def changed_vs(a: list, b: list) -> int:
    """Trades in one list and not the other (by arm, index, day, signal row, side, book)."""
    def key(t):
        return (t["arm"], t["und"], t["day"], t["er_sig"], t["side"], t.get("book"))
    ka, kb = {key(t) for t in a}, {key(t) for t in b}
    return len(ka ^ kb)
