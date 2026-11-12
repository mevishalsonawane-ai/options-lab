"""R165 pure helpers (PREREG.md): Black-Scholes Greeks and implied vol, the decision-minute delta of a contract, the
target-delta strike chooser, the index-stop trigger row, the tick-rule order-flow proxy, and the gates.

Plain numpy only (no stores); test_r165.py covers every function. 12 Nov 2026.
"""
from __future__ import annotations

import math
from datetime import date

import numpy as np

R = 0.065                              # risk-free rate a year (PREREG section 2)
YEAR_MIN = 365.0 * 1440.0              # calendar minutes a year
OPEN_MIN = 9 * 60 + 15                 # row 0 = 09:15
EXPIRY_MIN = 15 * 60 + 30              # the contract settles at 15:30 on its expiry day
LOOKBACK = 5                           # a premium may be the last finite close up to 5 minutes old
IV_LO, IV_HI = 0.01, 3.0
BANDS = {"DB1": (0.45, 0.55, False), "DB2": (0.55, 0.65, False), "DB3": (0.65, 0.80, True)}   # (lo, hi, hi inclusive)
TARGETS = {"SD50": 0.50, "SD60": 0.60, "SD70": 0.70, "SD80": 0.80}
TWIN_BAND = (0.40, 0.85)
STOP_FRAC = 0.15


# ================================================================================================ Black-Scholes
def _ncdf(x: float) -> float:
    return 0.5 * math.erfc(-x / math.sqrt(2.0))


def _npdf(x: float) -> float:
    return math.exp(-0.5 * x * x) / math.sqrt(2.0 * math.pi)


def bs_price(side: str, S: float, K: float, T: float, iv: float, r: float = R) -> float:
    """European price; at T <= 0 or iv <= 0 the discounted intrinsic value."""
    if T <= 0 or iv <= 0:
        return max(S - K, 0.0) if side == "C" else max(K - S, 0.0)
    sq = iv * math.sqrt(T)
    d1 = (math.log(S / K) + (r + 0.5 * iv * iv) * T) / sq
    d2 = d1 - sq
    if side == "C":
        return S * _ncdf(d1) - K * math.exp(-r * T) * _ncdf(d2)
    return K * math.exp(-r * T) * _ncdf(-d2) - S * _ncdf(-d1)


def implied_vol(side: str, P: float, S: float, K: float, T: float, r: float = R) -> float:
    """The option's own IV from its price by bisection in [1%, 300%]; NaN when P is missing, not above the lowest
    model price (below intrinsic) or above the highest."""
    if not (np.isfinite(P) and np.isfinite(S) and np.isfinite(K)) or P <= 0 or T <= 0 or S <= 0 or K <= 0:
        return float("nan")
    lo, hi = IV_LO, IV_HI
    if P <= bs_price(side, S, K, T, lo, r) or P >= bs_price(side, S, K, T, hi, r):
        return float("nan")
    for _ in range(60):
        mid = 0.5 * (lo + hi)
        if bs_price(side, S, K, T, mid, r) < P:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


def greeks(side: str, S: float, K: float, T: float, iv: float, r: float = R) -> dict:
    """|delta|, gamma, theta per calendar day, vega per 1 vol point. NaN when iv or T is not usable."""
    nan = float("nan")
    if not (np.isfinite(iv) and iv > 0 and T > 0):
        return dict(delta=nan, gamma=nan, theta=nan, vega=nan)
    sq = iv * math.sqrt(T)
    d1 = (math.log(S / K) + (r + 0.5 * iv * iv) * T) / sq
    d2 = d1 - sq
    pdf = _npdf(d1)
    gamma = pdf / (S * sq)
    vega = S * pdf * math.sqrt(T) / 100.0
    if side == "C":
        delta = _ncdf(d1)
        theta = -S * pdf * iv / (2 * math.sqrt(T)) - r * K * math.exp(-r * T) * _ncdf(d2)
    else:
        delta = 1.0 - _ncdf(d1)                    # |put delta| = N(-d1)
        theta = -S * pdf * iv / (2 * math.sqrt(T)) + r * K * math.exp(-r * T) * _ncdf(-d2)
    return dict(delta=float(delta), gamma=float(gamma), theta=float(theta / 365.0), vega=float(vega))


# ================================================================================================ time and prices
def years_to_expiry(day: date, row: int, expiry: date) -> float:
    """Calendar years from the END of minute `row` of `day` to 15:30 on `expiry` (0 at or after it)."""
    mins = (expiry - day).days * 1440.0 + EXPIRY_MIN - (OPEN_MIN + row + 1)
    return max(mins, 0.0) / YEAR_MIN


def next_expiry(day: date, expiry_days) -> date | None:
    """The first expiry day on or after `day` (the exchange calendar, known in advance)."""
    later = [d for d in expiry_days if d >= day]
    return min(later) if later else None


def last_close(series: np.ndarray, row: int, lookback: int = LOOKBACK) -> float:
    """The last finite value at or before `row` and at most `lookback` minutes old; NaN otherwise."""
    if row < 0:
        return float("nan")
    lo = max(0, row - lookback)
    seg = np.asarray(series[lo: row + 1], float)
    ok = np.flatnonzero(np.isfinite(seg))
    return float(seg[ok[-1]]) if ok.size else float("nan")


def contract_greeks(side: str, K: float, opt_close: np.ndarray, idx_close: np.ndarray, er: int, day: date,
                    expiry: date | None) -> dict:
    """PREREG section 2: everything at the decision minute er - 1 (nothing from row er on is read)."""
    nan = float("nan")
    dec = int(er) - 1
    S = last_close(idx_close, dec)
    P = last_close(opt_close, dec)
    T = years_to_expiry(day, dec, expiry) if expiry is not None else 0.0
    iv = implied_vol(side, P, S, float(K), T)
    g = greeks(side, S, float(K), T, iv)
    return dict(S=S, P=P, T=T, iv=iv, **g) if np.isfinite(iv) else dict(S=S, P=P, T=T, iv=nan, delta=nan, gamma=nan,
                                                                         theta=nan, vega=nan)


# ================================================================================================ rules
def in_band(delta: float, rule: str) -> bool:
    """DB*: True when the delta is in the band; an unknown delta is kept (True)."""
    if not np.isfinite(delta):
        return True
    lo, hi, inc = BANDS[rule]
    return lo <= delta and (delta <= hi if inc else delta < hi)


def choose_strike(today: float, deltas: dict, target: float) -> float:
    """SD*: the strike whose delta is nearest `target`; ties to the strike nearer today's; today's when none known.
    deltas: {strike: |delta|}."""
    ok = [(abs(d - target), abs(k - today), k) for k, d in deltas.items() if np.isfinite(d)]
    return float(min(ok)[2]) if ok else float(today)


def twin_strikes(today: float, deltas: dict, band=TWIN_BAND) -> list:
    """The strike twin's pool: strikes whose delta is in the band (today's alone when none)."""
    pool = sorted(float(k) for k, d in deltas.items() if np.isfinite(d) and band[0] <= d <= band[1])
    return pool or [float(today)]


def index_stop_points(premium: float, delta: float, frac: float = STOP_FRAC) -> float:
    """XI*: P = frac x premium / delta (index points); NaN when unknown."""
    if not (np.isfinite(premium) and np.isfinite(delta) and delta > 0 and premium > 0):
        return float("nan")
    return frac * premium / delta


def index_stop_row(idx_close: np.ndarray, side: str, level: float, er: int, end: int) -> int:
    """The row whose OPEN sells the option: one after the first index close at or beyond `level` in rows er .. end - 1
    (call: close <= level; put: close >= level); -1 when never (or the level is unknown)."""
    if not np.isfinite(level):
        return -1
    c = np.asarray(idx_close[int(er): int(end)], float)
    hit = (c <= level) if side == "C" else (c >= level)
    hit &= np.isfinite(c)
    i = np.flatnonzero(hit)
    return int(er + i[0] + 1) if i.size else -1


def tick_signed_volume(close: np.ndarray, vol: np.ndarray) -> np.ndarray:
    """The tick-rule proxy per minute: +volume when the close rose from the previous finite close, -volume when it fell,
    the previous sign when unchanged (0 before the first change); 0 on a missing minute."""
    c = np.asarray(close, float)
    v = np.nan_to_num(np.asarray(vol, float), nan=0.0)
    out = np.zeros(len(c))
    prev, sign = np.nan, 0.0
    for i in range(len(c)):
        if not np.isfinite(c[i]):
            continue
        if np.isfinite(prev):
            if c[i] > prev:
                sign = 1.0
            elif c[i] < prev:
                sign = -1.0
        out[i] = sign * v[i]
        prev = c[i]
    return out


def flow_before(signed: np.ndarray, er: int, n: int) -> float:
    """The proxy delta of the n minutes before the entry row (rows er-n .. er-1)."""
    lo = max(0, int(er) - n)
    return float(np.sum(signed[lo: int(er)]))


def flow_exit_row(signed: np.ndarray, vol: np.ndarray, fill: int, er: int, end: int) -> int:
    """OF3: the row whose open sells: one after the first row (from the fill) where the cumulative proxy delta since the
    fill is below -V5 (V5 = the option's volume in the 5 minutes before entry); -1 when never or V5 is 0."""
    v5 = float(np.nansum(np.asarray(vol, float)[max(0, int(er) - 5): int(er)]))
    if v5 <= 0 or fill < 0:
        return -1
    cum = np.cumsum(signed[int(fill): int(end)])
    i = np.flatnonzero(cum < -v5)
    return int(fill + i[0] + 1) if i.size else -1


# ================================================================================================ statistics and gates
def stats(trades: list) -> dict:
    if not trades:
        return dict(trades=0, win=None, net=0.0, per_trade=None, worst_month=0.0, max_dd=0.0)
    net = np.array([float(t["net"]) for t in trades])
    mon, day = {}, {}
    for t in trades:
        mon[(t["day"].year, t["day"].month)] = mon.get((t["day"].year, t["day"].month), 0.0) + float(t["net"])
        day[t["day"]] = day.get(t["day"], 0.0) + float(t["net"])
    eq = np.concatenate([[0.0], np.cumsum([day[d] for d in sorted(day)])])
    return dict(trades=int(len(net)), win=round(float((net > 0).mean()), 4), net=round(float(net.sum())),
                per_trade=round(float(net.mean()), 1), worst_month=round(float(min(mon.values()))),
                max_dd=round(float((np.maximum.accumulate(eq) - eq).max())))


def win_p(actual: float, twins) -> float:
    """(1 + twins with a win rate >= the rule's) / (n + 1)."""
    t = np.asarray(twins, float)
    return float((1 + int((t >= actual - 1e-12).sum())) / (len(t) + 1))


def bh(ps, m: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q-values of ps among m tests (the rest taken as p = 1)."""
    p = np.asarray(ps, float)
    n = len(p)
    m = n if m is None else m
    order = np.argsort(p)
    q = np.empty(n)
    run = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        run = min(run, p[i] * m / rank)
        q[i] = run
    return np.minimum(q, 1.0)


def gates(rule: dict, base: dict, picker_final_net: float | None, p: float, q: float, min_final: int = 40,
          win_up: float = 0.05) -> dict:
    """PREREG section 4: rule / base = {"wf": stats, "final": stats} at the same lots."""
    fails = []
    for per in ("wf", "final"):
        r, b = rule[per], base[per]
        if not r["trades"] or r["win"] is None or b["win"] is None or r["win"] < b["win"] + win_up - 1e-12:
            fails.append(f"{per}: win not up 5 points")
        if r["net"] < b["net"]:
            fails.append(f"{per}: net lower than today's")
    if picker_final_net is not None and rule["final"]["net"] < picker_final_net:
        fails.append("final: net lower than R151's picker")
    if rule["final"]["trades"] < min_final:
        fails.append(f"< {min_final} final trades")
    if not q <= 0.10:
        fails.append(f"BH q {q:.3g} > 0.10")
    return dict(passed=not fails, fails=fails, p=p, q=q)
