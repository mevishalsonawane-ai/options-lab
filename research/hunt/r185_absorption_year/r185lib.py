"""R185 pure parts (PREREG.md sections 2-6): bulk volume classification (BVC) on the index's 1-minute bars with the
near-ATM options' volume, R184's ABSORPTION / EXHAUSTION rules unchanged, the 1-minute option trade with its fixed exits,
random-entry twins, statistics, the selection and the gates.

Signal source: the INDEX's own 1-minute price (the underlying); the activity that sizes each minute's estimated flow is
the summed traded volume of the ATM-2..ATM+2 calls and puts - "option-volume context" (OVC), because no index future has
minute volume history (PREREG s0). No rule reads an option's own price chart. Option prices only price the trade; the
premium stop and breakeven lock act on the premium. Every signal at bar r reads bars <= r only.
Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import math
import sys
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
_R184 = HERE.parent / "r184_absorption_exhaustion"
for _p in (str(_R184), str(ROOT)):
    if _p not in sys.path:
        sys.path.insert(0, _p)
import r184lib as R4  # noqa: E402  (R184's rules, grid and references, reused unchanged)
from research.obuy.config import LOT_SCHEDULE  # noqa: E402
from research.obuy.costs import Costs, Fills, _dn  # noqa: E402

N = 375                      # minute bars 09:15..15:29
SIGMA_MIN = 10               # BVC: at least 10 earlier price changes for sigma
PREM_STOP, LOCK_AT = 0.85, 1.10
END_NORMAL, END_EXPIRY = 359, 349   # exit at the close of 15:14 / 15:04
ENTRY_LATE = 2               # entry: the open of r+1, else of r+2..r+3
PX_LOOK = 5                  # exit price fallback: 5 minutes back, else 5 forward
TWIN_POOL = 60
FILLS = Fills("liq")
COSTS = Costs("dated")
GRID = R4.GRID
vid = R4.vid
BSE = {"SENSEX"}


# Amendment A (16 Nov 2026): the TRUE lot of the contract R185 trades, by trading day, from NSE's F&O bhavcopies
# (NewBrdLotQty of the nearest expiry; r185lots.py -> results/lot_sizes.json) and, for SENSEX (BSE), from the option
# volumes and OI in the lake (every contract's units a multiple of 10 to 3 Jan 2025 and on 22 - 28 Jan 2025, of 20
# otherwise). Dhan's instrument master agrees for the live contracts (NIFTY 65, BANKNIFTY 30, FINNIFTY 60, MIDCPNIFTY
# 120, SENSEX 20). The repo's shared LOT_SCHEDULE differs on some of these dates; it is left unchanged here.
TRUE_LOTS = {   # index: [(first day, lot), ...] - each lot holds until the next entry
    "NIFTY": [("2024-01-01", 25), ("2024-12-27", 75), ("2025-01-24", 25), ("2025-01-31", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2024-01-01", 15), ("2025-01-31", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
    "FINNIFTY": [("2024-01-01", 25), ("2025-01-31", 65), ("2025-12-31", 60)],
    "MIDCPNIFTY": [("2024-01-01", 50), ("2025-01-31", 120), ("2025-06-27", 140), ("2025-12-31", 120)],
    "SENSEX": [("2024-01-01", 10), ("2025-01-06", 20), ("2025-01-22", 10), ("2025-01-29", 20)],
}


def lot_of(und: str, d: date) -> int:
    lot = None
    for since, n in TRUE_LOTS[und]:
        if d >= date.fromisoformat(since):
            lot = n
    return int(lot)


def schedule_lot_of(und: str, d: date) -> int:
    """The repo's shared schedule (research/obuy/config.py), kept for the comparison only."""
    lot = None
    for since, n in LOT_SCHEDULE[und]:
        if d >= date.fromisoformat(since):
            lot = n
    return int(lot)


def charge(buy: bool, px: float, qty: int, d: date, bse: bool = False) -> float:
    if not px > 0 or qty <= 0:
        return 0.0
    return float(COSTS.charge(buy, np.array([px]), np.array([qty]), np.array([_dn(d)]), bse)[0])


# ------------------------------------------------------------------------------------------------ BVC (PREREG s2)
def _phi(z: np.ndarray) -> np.ndarray:
    return 0.5 * (1.0 + np.vectorize(math.erf)(z / math.sqrt(2.0)))


def bvc(c, V):
    """D[i] = V[i] x (2 Phi(dP[i] / sigma[i]) - 1); sigma[i] = std (ddof 1) of the day's dP over bars 1..i-1 (>= 10
    finite), so it reads bars < i only for sigma and bar i itself for dP. Returns (D, sigma, thin)."""
    c = np.asarray(c, float)
    V = np.asarray(V, float)
    n = len(c)
    dP = np.full(n, np.nan)
    dP[1:] = c[1:] - c[:-1]
    ok = np.isfinite(dP)
    x = np.where(ok, dP, 0.0)
    cnt = np.concatenate([[0], np.cumsum(ok)])[:-1]
    s1 = np.concatenate([[0.0], np.cumsum(x)])[:-1]
    s2 = np.concatenate([[0.0], np.cumsum(x * x)])[:-1]
    sigma = np.full(n, np.nan)
    m = cnt >= SIGMA_MIN
    var = (s2[m] - s1[m] ** 2 / cnt[m]) / (cnt[m] - 1)
    sigma[m] = np.sqrt(np.clip(var, 0.0, None))
    D = np.full(n, np.nan)
    g = ok & np.isfinite(sigma) & (sigma > 0) & np.isfinite(V)
    D[g] = V[g] * (2.0 * _phi(dP[g] / sigma[g]) - 1.0)
    thin = ~np.isfinite(c) | ~(V > 0) | ~np.isfinite(D)
    return D, sigma, thin


@dataclass
class Bars:
    """What R184's rules read: c, h, l, d and `rows` (R184 calls a bar thin when rows < 20 or no close)."""
    c: np.ndarray
    h: np.ndarray
    l: np.ndarray
    d: np.ndarray
    rows: np.ndarray


def bars_of(o, h, l, c, V) -> Bars:
    D, _, thin = bvc(c, V)
    return Bars(c=np.asarray(c, float), h=np.asarray(h, float), l=np.asarray(l, float),
                d=np.where(thin, np.nan, D), rows=np.where(thin, 0, 60))


def signals(B: Bars, v: dict) -> list:
    return R4.signals(B, v)


# ------------------------------------------------------------------------------------------------ one day's data
@dataclass
class Day:
    day: date
    und: str
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    V: np.ndarray
    step: float
    lot: int
    expiry: bool
    opt: dict = field(default_factory=dict)   # side 'C'/'P' -> {strike: (o, h, l, c, v) arrays of N}
    _bars: Bars | None = None

    @property
    def bars(self) -> Bars:
        if self._bars is None:
            self._bars = bars_of(self.o, self.h, self.l, self.c, self.V)
        return self._bars


def atm(x: float, step: float) -> float:
    return math.floor(x / step + 0.5) * step


def _px_close(a_c, a_o, j: int):
    """An exit price at minute j: its close, else the last close in the 5 minutes before, else the first open in the 5
    minutes after."""
    if 0 <= j < N and np.isfinite(a_c[j]):
        return float(a_c[j])
    for k in range(j - 1, max(-1, j - PX_LOOK - 1), -1):
        if np.isfinite(a_c[k]):
            return float(a_c[k])
    for k in range(j + 1, min(N, j + PX_LOOK + 1)):
        if np.isfinite(a_o[k]):
            return float(a_o[k])
    return None


def trade(D: Day, r: int, side: str, stop: float, T: int):
    """PREREG s3: one 1-lot trade from a signal at bar r. Returns (dict, None) or (None, reason)."""
    if not np.isfinite(D.c[r]):
        return None, "no_spot"
    # 16 Nov (FINNIFTY final stage): a day whose option strikes give no step (no near strikes recorded) has no ATM to buy.
    if D.step is None or not np.isfinite(D.step) or D.step <= 0:
        return None, "no_strike"
    K = atm(float(D.c[r]), D.step)
    cont = D.opt["C" if side == "L" else "P"].get(K)
    if cont is None:
        return None, "no_strike"
    co, ch, cl, cc, cv = cont
    e = None
    for j in range(r + 1, min(N, r + 2 + ENTRY_LATE)):
        if np.isfinite(co[j]) and co[j] > 0:
            e = j
            break
    end = END_EXPIRY if D.expiry else END_NORMAL
    if e is None or e > end:
        return None, "no_price"
    lot, d, bse = D.lot, D.day, D.und in BSE
    raw_e = float(co[e])
    ve = float(np.nansum(cv[max(0, r - 4):r + 1])) / lot
    fill = float(FILLS.buy(np.array([raw_e]), np.array([ve]))[0])
    be = fill + (charge(True, fill, lot, d, bse) + charge(False, fill, lot, d, bse)) / lot
    cap = min(r + T, end)
    why_cap = "time" if r + T <= end else "day_end"
    locked, x, why, raw_x = False, None, None, None
    for j in range(e, cap + 1):
        cands = []
        if np.isfinite(cl[j]):
            op = co[j] if np.isfinite(co[j]) else np.inf
            if cl[j] <= PREM_STOP * fill:
                cands.append((min(op, PREM_STOP * fill), 0, "prem_stop"))
            if locked and cl[j] <= be:
                cands.append((min(op, be), 1, "breakeven"))
        hit = (D.l[j] <= stop) if side == "L" else (D.h[j] >= stop)
        if np.isfinite(D.l[j]) and np.isfinite(D.h[j]) and hit:
            px = _px_close(cc, co, j)
            if px is not None:
                cands.append((px, 2, "index_stop"))
        if cands:
            raw_x, _, why = min(cands)
            x = j
            break
        if np.isfinite(ch[j]) and ch[j] >= LOCK_AT * fill:
            locked = True
    if x is None:
        x, why = cap, why_cap
        raw_x = _px_close(cc, co, cap)
        if raw_x is None:
            return None, "no_exit_price"
    raw_x = float(max(raw_x, 0.0))
    vx = float(np.nansum(cv[max(0, x - 4):x + 1])) / lot
    st = why in ("prem_stop", "breakeven", "index_stop")
    nets = []
    for slip in (1.0, 2.0):
        B = fill if slip == 1.0 else round(raw_e + slip * (fill - raw_e), 2)
        S1 = float(FILLS.sell(np.array([raw_x]), np.array([vx]), stop=st)[0])
        S = S1 if slip == 1.0 else max(round(raw_x - slip * (raw_x - S1), 2), 0.0)
        nets.append((S - B) * lot - charge(True, B, lot, d, bse) - charge(False, S, lot, d, bse))
    return dict(r=int(r), e=int(e), x=int(x), side=side, strike=K, why=why, raw_e=round(raw_e, 2),
                raw_x=round(raw_x, 2), fill=round(fill, 2), net=round(nets[0], 2), net2=round(nets[1], 2),
                lot=lot, stop=round(float(stop), 2), dist=round(abs(float(D.c[r]) - float(stop)), 2)), None


def take_day(sigs: list, D: Day, T: int, max_per_day: int = R4.MAX_PER_DAY):
    """R184's taking rule: time order, one trade at a time (next entry after the last exit minute), at most 4 a day."""
    out, unpriced, last_x = [], {}, -1
    for s in sigs:
        if len(out) >= max_per_day:
            break
        if s.r + 1 <= last_x:
            continue
        t, why = trade(D, s.r, s.side, s.stop, T)
        if t is None:
            unpriced[why] = unpriced.get(why, 0) + 1
            continue
        t["day"] = D.day.isoformat()
        out.append(t)
        last_x = t["x"]
    return out, unpriced


def twin(D2: Day, r: int, side: str, dist: float, T: int):
    """The twin of a rule trade on day D2: same bar, side, index-stop distance (from D2's own close at r), exits."""
    c = D2.c[r]
    if not np.isfinite(c):
        return None
    t, _ = trade(D2, r, side, c - dist if side == "L" else c + dist, T)
    return None if t is None else t["net"]


def twin_pools(trades: list, days: dict, period: list, T: int, rng, pool: int = TWIN_POOL) -> list:
    """For each rule trade: the twin nets on up to `pool` other days of the same period, drawn at random."""
    out = []
    for t in trades:
        others = [d for d in period if d != t["day"]]
        pick = others if len(others) <= pool else [others[i] for i in sorted(rng.choice(len(others), pool, replace=False))]
        nets = []
        for d in pick:
            x = twin(days[d], t["r"], t["side"], t["dist"], T)
            if x is not None:
                nets.append(x)
        out.append(nets)
    return out


# ------------------------------------------------------------------------------------------------ statistics
def max_drawdown(nets) -> float:
    eq = np.cumsum(np.asarray(nets, float))
    if not len(eq):
        return 0.0
    peak = np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:]
    return float(np.max(peak - eq))


def summary(trades: list) -> dict:
    if not trades:
        return dict(n=0, win=None, net=0.0, net2=0.0, mean=None, mean2=None, avg_win=None, avg_loss=None, sd=None,
                    worst_day=None, worst_month=dict(month=None, net=0.0), max_dd=0.0, by_side={}, by_exit={},
                    days_traded=0)
    tr = sorted(trades, key=lambda t: (t["day"], t["e"]))
    x = np.array([t["net"] for t in tr], float)
    x2 = np.array([t["net2"] for t in tr], float)
    w, lo = x[x > 0], x[x <= 0]
    per, mon = {}, {}
    for t in tr:
        per[t["day"]] = per.get(t["day"], 0.0) + t["net"]
        mon[t["day"][:7]] = mon.get(t["day"][:7], 0.0) + t["net"]
    wm = min(mon.items(), key=lambda kv: kv[1])
    by_exit = {}
    for t in tr:
        e = by_exit.setdefault(t["why"], dict(n=0, net=0.0))
        e["n"] += 1
        e["net"] = round(e["net"] + t["net"], 2)
    return dict(n=int(len(x)), win=round(float((x > 0).mean()), 4), net=round(float(x.sum()), 0),
                net2=round(float(x2.sum()), 0), mean=round(float(x.mean()), 1), mean2=round(float(x2.mean()), 1),
                avg_win=round(float(w.mean()), 0) if len(w) else None,
                avg_loss=round(float(lo.mean()), 0) if len(lo) else None,
                sd=round(float(x.std(ddof=1)), 1) if len(x) > 1 else None,
                worst_day=round(min(per.values()), 0), best_day=round(max(per.values()), 0), days_traded=len(per),
                worst_month=dict(month=wm[0], net=round(wm[1], 0)), max_dd=round(max_drawdown(x), 0),
                months={k: round(v, 0) for k, v in sorted(mon.items())},
                by_side={s: dict(n=int(sum(t["side"] == s for t in tr)),
                                 net=round(sum(t["net"] for t in tr if t["side"] == s), 0)) for s in ("L", "S")},
                by_exit=by_exit)


def twin_mean(pools: list):
    v = [x for p in pools for x in p]
    return float(np.mean(v)) if v else None


def twin_test(rule_nets: list, pools: list, rng, draws: int = 1000) -> dict:
    """T1: p = (1 + sets with mean >= the rule's mean) / (1 + draws); one twin per rule trade per set."""
    if len(rule_nets) < 3:
        return dict(p=1.0, mean=None, p10=None, p90=None, n_sets=0)
    obs = float(np.mean(rule_nets))
    idx = [np.asarray(p, float) for p in pools if p]
    if not idx:
        return dict(p=1.0, mean=None, p10=None, p90=None, n_sets=0)
    means = np.empty(draws)
    for k in range(draws):
        means[k] = np.mean([a[rng.integers(len(a))] for a in idx])
    return dict(p=float((1 + np.sum(means >= obs - 1e-9)) / (1 + draws)), mean=round(float(means.mean()), 1),
                p10=round(float(np.percentile(means, 10)), 1), p90=round(float(np.percentile(means, 90)), 1),
                n_sets=draws, pooled_mean=round(twin_mean(pools), 1))


def zero_test(day_sums: list, rng, draws: int = 10000, n_trades: int | None = None) -> float:
    a = np.asarray(day_sums, float)
    if (n_trades is not None and n_trades < 3) or not len(a):
        return 1.0
    m = a[rng.integers(len(a), size=(draws, len(a)))].mean(axis=1)
    return float((1 + np.sum(m <= 0)) / (1 + draws))


def power(sd, n, m_total: int) -> dict:
    if not sd or not n or n < 2:
        return dict(mde_5pct=None, mde_bh=None)
    from statistics import NormalDist
    z = NormalDist().inv_cdf(1 - 0.10 / m_total)
    return dict(mde_5pct=round(2.49 * sd / math.sqrt(n), 0), mde_bh=round((z + 0.84) * sd / math.sqrt(n), 0),
                z_bh=round(z, 2))


def bh_with_prior(p, m_total: int):
    """q = p x m / rank among these tests (the others taken as larger), step-up, capped at 1; and q within."""
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)

    def q_of(m):
        q = p[o] * m / np.arange(1, n + 1)
        q = np.minimum.accumulate(q[::-1])[::-1]
        out = np.empty(n)
        out[o] = np.minimum(q, 1.0)
        return out
    return q_of(max(m_total, n)), q_of(n)


# ------------------------------------------------------------------------------------------------ selection and gates
def eligible(row: dict, min_half: int = 30) -> bool:
    """PREREG s5: in each half >= 30 trades, net > 0 and mean above the twins' mean."""
    for h in ("H1", "H2"):
        x = row[h]
        if x["n"] < min_half or not x["net"] > 0 or x["twin_mean"] is None or not x["mean"] > x["twin_mean"]:
            return False
    return True


def select(rows: list, min_half: int = 30) -> dict:
    """rows: [{i, vid, n, net, H1 {...}, H2 {...}}]. Eligible -> highest net (tie: more trades, grid order). None: the
    fallback - highest net among >= 30 trades in each half, else the most trades."""
    ok = [r for r in rows if eligible(r, min_half)]
    if ok:
        return dict(**min(ok, key=lambda r: (-r["net"], -r["n"], r["i"])), passed_selection=True)
    big = [r for r in rows if r["H1"]["n"] >= min_half and r["H2"]["n"] >= min_half]
    if big:
        return dict(**min(big, key=lambda r: (-r["net"], -r["n"], r["i"])), passed_selection=False)
    return dict(**min(rows, key=lambda r: (-r["n"], -r["net"], r["i"])), passed_selection=False)


GATE_PERIODS = ("early", "final", "six", "year")
BETTER = ("early", "final", "year")


def gates(passed_sel: bool, per: dict, q1: float, q2: float) -> dict:
    """PREREG s6 'passes the gates'. per[period] = summary + twin_mean."""
    fails = []
    if not passed_sel:
        fails.append("nothing passed the selection")
    if not all(per[p]["net"] > 0 for p in GATE_PERIODS) or not per["year"]["net2"] > 0:
        fails.append("not positive in every period (or the year at 2x slippage)")
    if not all(per[p]["mean"] is not None and per[p]["twin_mean"] is not None and per[p]["mean"] > per[p]["twin_mean"]
               for p in BETTER):
        fails.append("not above the twins in early, final and the year")
    if not (q1 <= 0.10 and q2 <= 0.10):
        fails.append(f"BH q twins {q1:.2f} / zero {q2:.2f} > 0.10")
    if per["final"]["n"] < 40:
        fails.append("< 40 trades in the final period")
    risk = [p for p in GATE_PERIODS if per[p]["max_dd"] > 40000 or per[p]["worst_month"]["net"] < -13000]
    if risk:
        fails.append("outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in " + ", ".join(risk))
    return dict(passed=not fails, fails=fails)


def verdict(passed_sel: bool, g: dict, year: dict, twin_m, p1: float) -> str:
    if g["passed"]:
        return "passes the gates"
    if year["n"] < 20:
        return "too few trades"
    if (passed_sel and year["n"] >= 40 and year["mean2"] > 0 and twin_m is not None and year["mean"] > twin_m
            and p1 <= 0.20):
        return "worth a forward test"
    return "no evidence" if passed_sel else "no evidence (nothing passed the selection)"
