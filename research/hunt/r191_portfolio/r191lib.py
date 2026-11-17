"""R191 portfolio maths (PREREG.md sections 3-7). Pure functions, no file reads. Plain-English notes, 17 Nov 2026.

A trade is a dict:
    comp   "N" Night, "L" Liquidity 15+5, "R" R72 + R151 picker
    und    index name
    day    decision day (date); close  the day its money is booked (Night: the next session; intraday: the same day)
    era    "A" / "B" / "C"
    net    Rs at 1 lot after charges and slippage
    t_dec  decision time (day ordinal + fraction), t_known  when its closed P&L may be used (PREREG section 3)
    prem   premium x lot at entry (Night only; None when unknown)
"""
from __future__ import annotations

import heapq
import math
from collections import defaultdict
from dataclasses import dataclass
from datetime import date

import numpy as np

ACCOUNT = 56000.0
MONTH_STOP = -13000.0
ACC_DD = 40000.0
HALT = -8000.0                 # month halt (PREREG section 4)
BRAKE = 25000.0                # drawdown brake
EC_N = 20                      # equity-curve filter: last 20 candidate trades
MIN_TRADES = 40
GATE_P = 0.05
GATE_Q = 0.10
TWINS = 2000
SEED = 191
M_TOTAL = 4762 + 8             # R190's family total + R191's 8 variants
COMP_ORDER = {"N": 0, "L": 1, "R": 2}

# fractions of a day (PREREG section 3)
F_INTRA_DEC, F_NIGHT_DEC, F_NIGHT_KNOWN, F_INTRA_KNOWN = 0.3, 0.6, 0.5, 0.7


def night_trade(und: str, day: date, nd: date, era: str, net: float, prem=None, side=0) -> dict:
    return dict(comp="N", und=und, day=day, close=nd, era=era, net=float(net), prem=prem, side=side,
                t_dec=day.toordinal() + F_NIGHT_DEC, t_known=nd.toordinal() + F_NIGHT_KNOWN)


def intraday_trade(comp: str, und: str, day: date, era: str, net: float, er: int = 0, side=None) -> dict:
    return dict(comp=comp, und=und, day=day, close=day, era=era, net=float(net), prem=None, side=side, er=er,
                t_dec=day.toordinal() + F_INTRA_DEC, t_known=day.toordinal() + F_INTRA_KNOWN)


def month_of(d: date) -> tuple:
    return (d.year, d.month)


def order_key(t: dict) -> tuple:
    return (t["t_dec"], COMP_ORDER.get(t["comp"], 9), t.get("er", 0), t["und"])


# ================================================================================================ static filters
def one_a_night(night: list, account: float = ACCOUNT) -> list:
    """At most one Night position a night: NIFTY when both signal; only if premium x lot fits the account (an unknown
    premium is kept)."""
    by = {}
    for t in sorted(night, key=lambda x: (x["day"], x["und"] != "NIFTY")):
        if t["day"] in by:
            continue
        if t.get("prem") is not None and t["prem"] > account:
            continue
        by[t["day"]] = t
    return [by[d] for d in sorted(by)]


def vix_skip(trades: list, high_days: set, comps=("N", "L")) -> list:
    """Drop Night and Liquidity entries on a day whose VIX flag is high (the flag uses the previous close)."""
    return [t for t in trades if not (t["comp"] in comps and t["day"] in high_days)]


# ================================================================================================ the simulator
@dataclass(frozen=True)
class Rules:
    one_night: bool = False
    month_halt: bool = False
    brake: bool = False
    ec: bool = False
    vix: bool = False
    halt: float = HALT
    brake_dd: float = BRAKE
    ec_n: int = EC_N


def candidates(streams: dict, comps: tuple, rules: Rules, high_days: set = frozenset()) -> list:
    """The variant's candidate trades after its static filters, in decision order."""
    out = []
    for c in comps:
        tr = list(streams.get(c) or [])
        if c == "N" and rules.one_night:
            tr = one_a_night(tr)
        out += tr
    if rules.vix:
        out = vix_skip(out, set(high_days))
    return sorted(out, key=order_key)


def simulate_idx(cands: list, rules: Rules, nets=None) -> list:
    """Walk the candidates in decision order and return the indices of the trades taken. Every state rule reads only
    trades whose known time is strictly before the decision (PREREG section 3). nets: optional replacement nets
    aligned with cands (the twins)."""
    v = [t["net"] for t in cands] if nets is None else [float(x) for x in nets]
    taken = []
    pend_taken, pend_virt = [], []          # heaps of (t_known, index)
    eq, peak = 0.0, 0.0
    month_book = defaultdict(float)         # visible taken net by booking month
    virt_month = defaultdict(float)         # visible candidate (paper) net by booking month
    ec_hist = defaultdict(list)             # visible candidate nets per component, in known order
    halted, halt_month = False, None
    for i, t in enumerate(cands):
        now = t["t_dec"]
        while pend_taken and pend_taken[0][0] < now:
            _, j = heapq.heappop(pend_taken)
            eq += v[j]
            peak = max(peak, eq)
            month_book[month_of(cands[j]["close"])] += v[j]
        while pend_virt and pend_virt[0][0] < now:
            _, j = heapq.heappop(pend_virt)
            virt_month[month_of(cands[j]["close"])] += v[j]
            ec_hist[cands[j]["comp"]].append(v[j])
        m = month_of(t["day"])
        ok = True
        if rules.brake:
            if halted and m != halt_month:
                prev = (m[0] - 1, 12) if m[1] == 1 else (m[0], m[1] - 1)
                if virt_month.get(prev, 0.0) > 0:
                    halted, peak = False, eq
                else:
                    halt_month = m
            if not halted and eq - peak <= -rules.brake_dd:
                halted, halt_month = True, m
            if halted:
                ok = False
        if ok and rules.month_halt and month_book.get(m, 0.0) <= rules.halt:
            ok = False
        if ok and rules.ec:
            h = ec_hist[t["comp"]]
            if len(h) >= rules.ec_n and not sum(h[-rules.ec_n:]) > 0:
                ok = False
        heapq.heappush(pend_virt, (t["t_known"], i))
        if ok:
            taken.append(i)
            heapq.heappush(pend_taken, (t["t_known"], i))
    return taken


def simulate(cands: list, rules: Rules) -> list:
    """The trades taken (see simulate_idx)."""
    return [cands[i] for i in simulate_idx(cands, rules)]


def run_variant(streams: dict, comps: tuple, rules: Rules, high_days: set = frozenset()) -> list:
    return simulate(candidates(streams, comps, rules, high_days), rules)


# ================================================================================================ statistics
def max_drawdown(daily) -> float:
    eq, peak, dd = 0.0, 0.0, 0.0
    for x in daily:
        eq += x
        peak = max(peak, eq)
        dd = max(dd, peak - eq)
    return dd


def stats(trades: list) -> dict:
    """Numbers for one set of trades, booked on their close day."""
    n = len(trades)
    if not n:
        return dict(trades=0, win=0.0, net=0.0, per_trade=0.0, avg_win=0.0, avg_loss=0.0, net_wo_top5=0.0,
                    worst_day=0.0, worst_month=0.0, max_dd=0.0, months=0, months_up=0, months_up_pct=0.0)
    nets = np.array([t["net"] for t in trades], float)
    days, months = defaultdict(float), defaultdict(float)
    for t in trades:
        days[t["close"]] += t["net"]
        months[month_of(t["close"])] += t["net"]
    dser = [days[d] for d in sorted(days)]
    wins, losses = nets[nets > 0], nets[nets <= 0]
    up = sum(1 for v in months.values() if v > 0)
    return dict(trades=n, win=float(len(wins) / n * 100), net=float(nets.sum()), per_trade=float(nets.mean()),
                avg_win=float(wins.mean()) if len(wins) else 0.0, avg_loss=float(losses.mean()) if len(losses) else 0.0,
                net_wo_top5=float(nets.sum() - np.sort(nets)[::-1][:5].sum()), worst_day=float(min(dser)),
                worst_month=float(min(months.values())), max_dd=float(max_drawdown(dser)), months=len(months),
                months_up=up, months_up_pct=float(up / len(months) * 100))


# ================================================================================================ twins, BH
def twin_nets(streams: dict, comps: tuple, rules: Rules, high_days: set, rng, n: int = TWINS) -> np.ndarray:
    cands = candidates(streams, comps, rules, high_days)
    months = sorted({month_of(t["day"]) for t in cands})
    mi = {m: j for j, m in enumerate(months)}
    base = np.array([t["net"] for t in cands], float)
    col = np.array([mi[month_of(t["day"])] for t in cands], int)
    out = np.empty(n)
    for i in range(n):
        sg = np.where(rng.random(len(months)) < 0.5, 1.0, -1.0)
        nets = base * sg[col] if len(cands) else base
        out[i] = float(nets[simulate_idx(cands, rules, nets)].sum()) if len(cands) else 0.0
    return out


def norm_tail(z: float) -> float:
    return 0.5 * math.erfc(z / math.sqrt(2.0))


def twin_p(rule_net: float, twins) -> tuple:
    """(empirical p, normal-tail p), one-sided."""
    tw = np.asarray(twins, float)
    if not len(tw):
        return 1.0, 1.0
    k = int((tw >= rule_net - 1e-9).sum())
    pe = (1 + k) / (len(tw) + 1)
    sd = float(tw.std(ddof=1)) if len(tw) > 1 else 0.0
    pn = (1.0 if rule_net <= float(tw.mean()) else 0.0) if sd <= 0 else norm_tail((rule_net - float(tw.mean())) / sd)
    return pe, pn


def bh_total(p, m_total: int = M_TOTAL) -> np.ndarray:
    """Benjamini-Hochberg q with m_total tests; earlier tests count as p = 1."""
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


# ================================================================================================ the gate
def era_fails(e: str, s: dict | None, p_emp: float | None, account: bool) -> list:
    if s is None:
        return [f"era {e}: not computed"]
    f = []
    if s["trades"] < MIN_TRADES:
        f.append(f"era {e}: {s['trades']} trades < {MIN_TRADES}")
    if not s["net"] > 0:
        f.append(f"era {e}: net {s['net']:,.0f} <= 0")
    if not s["net_wo_top5"] > 0:
        f.append(f"era {e}: net without the top 5 trades {s['net_wo_top5']:,.0f} <= 0")
    if p_emp is None or not p_emp < GATE_P:
        f.append(f"era {e}: twin p {'n/a' if p_emp is None else f'{p_emp:.3f}'} >= {GATE_P}")
    if account:
        if not s["max_dd"] < ACC_DD:
            f.append(f"era {e}: max drawdown Rs {s['max_dd']:,.0f} >= Rs 40k")
        if not s["worst_month"] > MONTH_STOP:
            f.append(f"era {e}: worst month Rs {s['worst_month']:,.0f} <= -Rs 13k")
    return f


def gate(eras: dict, p_emp: dict, q: float) -> list:
    """PREREG section 7. eras: {'A','B','C'} -> stats; p_emp: era -> empirical twin p."""
    f = []
    for e in ("A", "B", "C"):
        f += era_fails(e, eras.get(e), p_emp.get(e), account=e in ("B", "C"))
    if not q <= GATE_Q:
        f.append(f"BH q {q:.3g} > {GATE_Q}")
    return f


def b_pick(era_b: dict) -> str | None:
    """PREREG section 8: among variants passing items 1-4 and 6 on era B, the highest net / max drawdown."""
    best, score = None, -math.inf
    for vid in sorted(era_b):
        d = era_b[vid]
        if era_fails("B", d["stats"], d["p_emp"], account=True):
            continue
        s = d["stats"]["net"] / max(d["stats"]["max_dd"], 1.0)
        if s > score:
            best, score = vid, s
    return best
