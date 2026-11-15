"""R181 pure parts (PREREG.md sections 2-6): minute bars from 1-second rows, the prior day's volume profile, the six
setups (Cases 1-5 and the Bonus A+) with their order-flow (OF) conditions, the second-by-second exit, fills and charges,
and the twins' statistics.

Signal on the index futures (price and tick-rule order flow); the traded instrument is the ATM option of the nearest
expiry (CLAUDE.md "signal on the underlying, trade the option"). Every signal at bar r reads bars <= r only.
Plain-English notes, 15 Nov 2026.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date

import numpy as np

from research.obuy.costs import Costs, Fills, _dn

N = 375                      # minute bars 09:15..15:29
FIRST_SIG, LAST_SIG = 10, 329  # signal bars 09:25..14:44
THIN_ROWS = 20
REF_WIN, REF_MIN = 30, 10
BIG = 1.5
VA_SHARE = 0.70
MAX_EPISODES = 3
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}
LOT = {"NIFTY": 65, "BANKNIFTY": 30}
FILLS = Fills("liq")
COSTS = Costs("dated")
SECS_0915 = 9 * 3600 + 15 * 60
IST = 19800


def day_epoch_0915(d: date) -> int:
    """Epoch seconds of 09:15:00 IST on day d."""
    return (d - date(1970, 1, 1)).days * 86400 - IST + SECS_0915


# ------------------------------------------------------------------------------------------------ minute bars
@dataclass
class Bars:
    """One index's futures minute bars for one day (NaN = no bar), plus its derived order-flow arrays."""
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    v: np.ndarray
    d: np.ndarray            # delta (buy - sell), 0 where no bar
    rows: np.ndarray         # recorded seconds in the minute
    cvd: np.ndarray = field(default=None)
    thin: np.ndarray = field(default=None)
    ref: np.ndarray = field(default=None)

    def __post_init__(self):
        self.derive()

    def derive(self):
        self.thin = (self.rows < THIN_ROWS) | ~np.isfinite(self.c)
        self.cvd = np.cumsum(np.nan_to_num(self.d))
        self.ref = ref_abs_delta(self.d, self.thin)


def ref_abs_delta(d: np.ndarray, thin: np.ndarray) -> np.ndarray:
    """ref[i] = mean |D| of non-thin bars i-30..i-1 (at least 10 of them), else NaN. Reads bars < i only."""
    n = len(d)
    out = np.full(n, np.nan)
    a = np.where(thin, np.nan, np.abs(np.asarray(d, float)))
    for i in range(n):
        w = a[max(0, i - REF_WIN):i]
        w = w[np.isfinite(w)]
        if len(w) >= REF_MIN:
            out[i] = w.mean()
    return out


def bars_from_seconds(sec, last, hi, lo, vol, buy, sell, t0: int) -> Bars:
    """Minute bars from one instrument's 1-second rows (sorted by sec). t0 = epoch of 09:15:00."""
    sec = np.asarray(sec, np.int64)
    m = (sec - t0) // 60
    k = (m >= 0) & (m < N)
    sec, m = sec[k], m[k]
    last, hi, lo = (np.asarray(x, float)[k] for x in (last, hi, lo))
    vol, buy, sell = (np.nan_to_num(np.asarray(x, float)[k]) for x in (vol, buy, sell))
    o, h, l, c = (np.full(N, np.nan) for _ in range(4))
    v, dd, rows = np.zeros(N), np.zeros(N), np.zeros(N)
    top = np.fmax(last, hi)
    bot = np.fmin(last, lo)
    for i in np.unique(m):
        s = m == i
        lp = last[s]
        ok = np.isfinite(lp)
        if not ok.any():
            continue
        o[i], c[i] = lp[ok][0], lp[ok][-1]
        h[i], l[i] = np.nanmax(top[s]), np.nanmin(bot[s])
        v[i], dd[i], rows[i] = vol[s].sum(), buy[s].sum() - sell[s].sum(), s.sum()
    return Bars(o, h, l, c, v, dd, rows)


# ------------------------------------------------------------------------------------------------ profile
def volume_profile(prices, vols, step: float, pdh: float, pdl: float) -> dict | None:
    """{poc, vah, val, pdh, pdl} from (price, volume) pairs of one session; bin = step / 5. None when empty."""
    p = np.asarray(prices, float)
    w = np.asarray(vols, float)
    ok = np.isfinite(p) & (w > 0)
    if not ok.any():
        return None
    p, w = p[ok], w[ok]
    b = step / 5.0
    idx = np.floor(p / b + 1e-9).astype(np.int64)
    base = int(idx.min())
    counts = np.bincount(idx - base, weights=w).astype(float)
    n = len(counts)
    mid = (p.max() + p.min()) / 2.0
    best = np.flatnonzero(counts == counts.max())
    centers = (base + best + 0.5) * b
    poc = int(best[np.argmin(np.abs(centers - mid))])
    a = z = poc
    acc, total = counts[poc], counts.sum()
    while acc < VA_SHARE * total - 1e-9:
        up = counts[z + 1] if z + 1 < n else -1.0
        dn = counts[a - 1] if a - 1 >= 0 else -1.0
        if up >= dn:
            z += 1
            acc += up
        else:
            a -= 1
            acc += dn
    return dict(poc=float((base + poc + 0.5) * b), vah=float((base + z + 1) * b), val=float((base + a) * b),
                pdh=float(pdh), pdl=float(pdl))


def shift_levels(lv: dict, delta: float) -> dict:
    return {k: v + delta for k, v in lv.items()}


# ------------------------------------------------------------------------------------------------ footprint helpers
def absorb_formula(bars: Bars, fp: dict) -> np.ndarray:
    """The app's README absorb rule rebuilt from the footprint: a bar with volume >= 2.0 x the mean of today's earlier
    bars (at least 10), >= 60% of its footprint volume at one price, and range <= max(0.1, 0.0002 x close)."""
    out = np.zeros(N, bool)
    v = bars.v
    for i in range(N):
        if i not in fp or not np.isfinite(bars.c[i]):
            continue
        prev = v[:i][np.isfinite(bars.c[:i])]
        if len(prev) < 10 or v[i] < 2.0 * prev.mean() or v[i] <= 0:
            continue
        _, b, s = fp[i]
        tot = b + s
        if tot.sum() <= 0 or tot.max() < 0.6 * tot.sum():
            continue
        if bars.h[i] - bars.l[i] <= max(0.1, 2e-4 * bars.c[i]):
            out[i] = True
    return out


def stacked(fp_bar, row: float, lot: int, side: str):
    """Stacked diagonal imbalances in one bar's footprint (prices, buys, sells), rows of `row` points.
    Buy (side 'L'): row k's buy >= 3 x row k-1's sell and >= 2 lots, k-1 inside the bar's traded rows.
    Sell ('S'): row k's sell >= 3 x row k+1's buy and >= 2 lots, k+1 inside the traded rows.
    Returns (low, high) price span of the first run of >= 3 consecutive imbalanced rows, or None."""
    if fp_bar is None:
        return None
    p, b, s = fp_bar
    if len(p) == 0:
        return None
    k = np.floor(np.asarray(p, float) / row + 1e-9).astype(np.int64)
    k0, k1 = int(k.min()), int(k.max())
    nb = np.bincount(k - k0, weights=b, minlength=k1 - k0 + 1)
    ns = np.bincount(k - k0, weights=s, minlength=k1 - k0 + 1)
    n = len(nb)
    imb = np.zeros(n, bool)
    for j in range(n):
        if side == "L" and j >= 1:
            imb[j] = nb[j] >= 3 * ns[j - 1] and nb[j] >= 2 * lot
        elif side == "S" and j + 1 < n:
            imb[j] = ns[j] >= 3 * nb[j + 1] and ns[j] >= 2 * lot
    run = 0
    for j in range(n):
        run = run + 1 if imb[j] else 0
        if run >= 3:
            e = j
            while e + 1 < n and imb[e + 1]:
                e += 1
            st = j - run + 1
            return ((k0 + st) * row, (k0 + e + 1) * row)
    return None


# ------------------------------------------------------------------------------------------------ setups
@dataclass
class Ep:
    case: str
    side: str            # 'L' (buy CE) or 'S' (buy PE)
    b: int
    r: int
    stop: float
    target: float
    of: bool
    ofv: bool | None = None    # app-absorb-formula variant (C1, C2, A+)
    rr_ok: bool = True
    extra: dict = field(default_factory=dict)


def _big(B: Bars, j: int) -> bool:
    return bool(np.isfinite(B.ref[j]) and abs(B.d[j]) >= BIG * B.ref[j])


def absorption_bar(B: Bars, j: int, lvl: float) -> bool:
    """Guide Pattern 1 (Amendment 1): bar j below the level, heavy net selling (D < 0, big), yet its low is not below
    the previous bar's low (no new low despite the selling). Reads bars j-1 and j only."""
    if j < 1 or B.thin[j] or not np.isfinite(B.l[j - 1]):
        return False
    return bool(B.l[j] < lvl and B.d[j] < 0 and _big(B, j) and B.l[j] >= B.l[j - 1])


def exhaustion_bar(B: Bars, j: int, lvl: float, n: int = 5) -> bool:
    """Guide Pattern 2 (Amendment 1): bars j-4..j all non-thin with D > 0, D strictly falling and highs strictly rising,
    and H[j] above the level. Reads bars j-4..j only."""
    if j < n - 1 or not B.h[j] > lvl:
        return False
    w = range(j - n + 1, j + 1)
    if any(B.thin[k] or not B.d[k] > 0 for k in w):
        return False
    d, h = B.d[j - n + 1:j + 1], B.h[j - n + 1:j + 1]
    return bool(np.all(np.diff(d) < 0) and np.all(np.diff(h) > 0))


def rr_ok(side: str, e: float, stop: float, target: float) -> bool:
    if side == "L":
        return bool(target > e and stop < e and (target - e) >= 2 * (e - stop) - 1e-9)
    return bool(target < e and stop > e and (e - target) >= 2 * (stop - e) - 1e-9)


def case1(B: Bars, lv: dict, und: str, absorbf=None, side="L") -> list:
    """Case 1 (side 'L', at VAL) and Case 2 (side 'S', at VAH): failed break + absorption / exhaustion."""
    lvl = lv["val"] if side == "L" else lv["vah"]
    buf = STEP[und] / 10
    out, b = [], 1
    C, H, L = B.c, B.h, B.l
    while b < N and len(out) < MAX_EPISODES:
        brk = (C[b - 1] >= lvl and C[b] < lvl) if side == "L" else (C[b - 1] <= lvl and C[b] > lvl)
        if not brk:
            b += 1
            continue
        r = None
        for x in range(b + 1, min(b + 15, N - 1) + 1):
            if (C[x] > lvl) if side == "L" else (C[x] < lvl):
                r = x
                break
        if r is None:
            b += 16
            continue
        if r > LAST_SIG:
            break
        if r >= FIRST_SIG:
            js = range(b, r + 1)
            if side == "L":
                stop = np.nanmin(L[b:r + 1]) - buf
                hit = any(absorption_bar(B, j, lvl) for j in js)
                of = hit and (not B.thin[r]) and B.d[r] > 0
                ofv = None if absorbf is None else any(absorbf[j] and L[j] < lvl for j in js)
            else:
                stop = np.nanmax(H[b:r + 1]) + buf
                hit = any(exhaustion_bar(B, j, lvl) for j in js)
                of = hit and (not B.thin[r]) and B.d[r] < 0
                ofv = None if absorbf is None else any(absorbf[j] and H[j] > lvl for j in js)
            tgt = lv["poc"]
            out.append(Ep("C1" if side == "L" else "C2", side, b, r, float(stop), float(tgt), bool(of),
                          None if ofv is None else bool(ofv), rr_ok(side, C[r], stop, tgt)))
        b = r + 1
    return out


def case3(B: Bars, lv: dict, und: str, fp: dict, side: str) -> list:
    """Case 3: breakout across VAH (long) / VAL (short), OF = stacked imbalances in the breakout bar, then a retest."""
    step = STEP[und]
    buf, tol, row = step / 10, step / 5, step / 25
    lvl = lv["vah"] if side == "L" else lv["val"]
    C, H, L = B.c, B.h, B.l
    out, b = [], 1
    while b < N and len(out) < MAX_EPISODES:
        brk = (C[b - 1] <= lvl and C[b] > lvl) if side == "L" else (C[b - 1] >= lvl and C[b] < lvl)
        if not brk:
            b += 1
            continue
        r, died = None, None
        for x in range(b + 1, min(b + 30, N - 1) + 1):
            if side == "L":
                if C[x] <= lvl - tol:
                    died = x
                    break
                if L[x] <= lvl + tol and C[x] > lvl:
                    r = x
                    break
            else:
                if C[x] >= lvl + tol:
                    died = x
                    break
                if H[x] >= lvl - tol and C[x] < lvl:
                    r = x
                    break
        if r is None:
            b = (died + 1) if died is not None else b + 1
            continue
        if r > LAST_SIG:
            break
        if r >= FIRST_SIG:
            e = C[r]
            span = stacked(fp.get(b), row, LOT[und], side)
            if side == "L":
                stop = min(np.nanmin(L[b:r + 1]), lvl - tol) - buf
                tgt = e + 2 * (e - stop)
                of = span is not None and span[1] >= lvl - tol and span[0] <= H[b]
            else:
                stop = max(np.nanmax(H[b:r + 1]), lvl + tol) + buf
                tgt = e - 2 * (stop - e)
                of = span is not None and span[0] <= lvl + tol and span[1] >= L[b]
            out.append(Ep("C3", side, b, r, float(stop), float(tgt), bool(of), None, rr_ok(side, e, stop, tgt)))
        b = r + 1
    return out


def case4(B: Bars, lv: dict, und: str, side: str) -> list:
    """Case 4: double bottom at POC with a CVD higher low (long) / double top with a CVD lower high (short)."""
    step = STEP[und]
    buf, tol = step / 10, step / 5
    poc = lv["poc"]
    C, H, L, cvd = B.c, B.h, B.l, B.cvd
    X = L if side == "L" else H                 # the extreme series
    sg = 1.0 if side == "L" else -1.0           # long: lows from above; short: highs from below
    cands = []
    for j1 in range(3, N):
        if not np.isfinite(X[j1]) or abs(X[j1] - poc) > tol:
            continue
        w = X[j1 - 3:j1]
        if not (sg * X[j1] <= np.nanmin(sg * w)) if np.isfinite(w).any() else True:
            continue
        lead = (H if side == "L" else L)[max(0, j1 - 15):j1]
        if not np.isfinite(lead).any():
            continue
        if side == "L" and not np.nanmax(lead) >= poc + 2 * tol:
            continue
        if side == "S" and not np.nanmin(lead) <= poc - 2 * tol:
            continue
        j2 = None
        for k in range(j1 + 5, min(j1 + 60, N - 1) + 1):
            if not np.isfinite(X[k]) or abs(X[k] - poc) > tol or abs(X[k] - X[j1]) > tol:
                continue
            w2 = X[k - 3:k]
            if np.isfinite(w2).any() and not sg * X[k] <= np.nanmin(sg * w2):
                continue
            mid = slice(j1 + 1, k)
            if side == "L":
                bounce = np.nanmax(H[mid]) >= max(L[j1], L[k]) + 2 * tol
                under = np.nanmin(L[mid]) >= min(L[j1], L[k]) - tol
            else:
                bounce = np.nanmin(L[mid]) <= min(H[j1], H[k]) - 2 * tol
                under = np.nanmax(H[mid]) <= max(H[j1], H[k]) + tol
            if bounce and under:
                j2 = k
                break
        if j2 is None:
            continue
        r = None
        for x in range(j2 + 1, min(j2 + 10, N - 1) + 1):
            if side == "L":
                if L[x] < L[j2]:
                    break
                if C[x] > H[j2]:
                    r = x
                    break
            else:
                if H[x] > H[j2]:
                    break
                if C[x] < L[j2]:
                    r = x
                    break
        if r is None or r < FIRST_SIG or r > LAST_SIG:
            continue
        if side == "L":
            stop = min(L[j1], L[j2]) - buf
            tgt = np.nanmax(H[:r + 1])
            of = cvd[j2] > cvd[j1]
        else:
            stop = max(H[j1], H[j2]) + buf
            tgt = np.nanmin(L[:r + 1])
            of = cvd[j2] < cvd[j1]
        cands.append(Ep("C4", side, j1, r, float(stop), float(tgt), bool(of), None, rr_ok(side, C[r], stop, tgt),
                        dict(j2=j2)))
    # Causal selection: in signal-bar order (ties: the earliest j1), a candidate counts only if its first low comes after
    # the previous counted signal. Whether a signal at r counts then depends on bars <= r only.
    out, after = [], -1
    for e in sorted(cands, key=lambda e: (e.r, e.b)):
        if len(out) >= MAX_EPISODES:
            break
        if e.b > after and (not out or e.r > out[-1].r):
            out.append(e)
            after = e.r
    return out


def case5(B: Bars, lv: dict, und: str, side: str) -> list:
    """Case 5: stop-run below PDL that closes back above (long) / above PDH back below (short); OF = trapped flow."""
    buf = STEP[und] / 10
    C, H, L = B.c, B.h, B.l
    lvl = lv["pdl"] if side == "L" else lv["pdh"]
    out, b = [], 1
    while b < N and len(out) < MAX_EPISODES:
        sw = (L[b] < lvl - buf and C[b - 1] > lvl) if side == "L" else (H[b] > lvl + buf and C[b - 1] < lvl)
        if not sw:
            b += 1
            continue
        r = None
        for x in range(b, min(b + 5, N - 1) + 1):
            if (C[x] > lvl) if side == "L" else (C[x] < lvl):
                r = x
                break
        if r is None:
            b += 1
            continue
        if r > LAST_SIG:
            break
        if r >= FIRST_SIG:
            e = C[r]
            lv3 = [lv["val"], lv["poc"], lv["vah"]]
            if side == "L":
                above = [x for x in lv3 if x > e]
                tgt = min(above) if above else None
                stop = np.nanmin(L[b:r + 1]) - buf
            else:
                below = [x for x in lv3 if x < e]
                tgt = max(below) if below else None
                stop = np.nanmax(H[b:r + 1]) + buf
            if tgt is not None:
                s = float(np.sum(B.d[b:r + 1]))
                okf = np.isfinite(B.ref[r]) and not B.thin[b:r + 1].any()
                of = okf and (s <= -B.ref[r] if side == "L" else s >= B.ref[r])
                out.append(Ep("C5", side, b, r, float(stop), float(tgt), bool(of), None, rr_ok(side, e, stop, tgt)))
        b = r + 1
    return out


def oi_wall_ok(chain: list | None, side: str, level_fut: float, basis: float, step: float) -> bool:
    """A+: the biggest-OI PE (long) / CE (short) of the chain rows [strike, right, ltp, oi, ...] within one step of the
    level in spot terms."""
    if not chain or not np.isfinite(basis):
        return False
    right = "PE" if side == "L" else "CE"
    rows = [x for x in chain if len(x) > 3 and x[1] == right and x[3] is not None]
    if not rows:
        return False
    k = max(rows, key=lambda x: x[3])[0]
    return abs(float(k) - (level_fut - basis)) <= step + 1e-9


# ------------------------------------------------------------------------------------------------ option leg
def atm(spot: float, step: float) -> float:
    return math.floor(spot / step + 0.5) * step


def pick_strike(strikes_live: list, a: float, step: float, side: str):
    """The ATM if recorded, else the recorded strike nearest within 2 steps (tie: in the money). None if none."""
    if not strikes_live:
        return None
    best = None
    for k in strikes_live:
        dist = abs(k - a)
        if dist > 2 * step + 1e-9:
            continue
        itm = (k < a) if side == "L" else (k > a)
        key = (dist, 0 if itm else 1)
        if best is None or key < best[0]:
            best = (key, k)
    return None if best is None else best[1]


@dataclass
class Series:
    """One option contract's 1-second rows (sorted by sec)."""
    sec: np.ndarray
    last: np.ndarray
    vol: np.ndarray
    bid: np.ndarray
    ask: np.ndarray

    def px(self, s: int, back=120, fwd=60):
        i = int(np.searchsorted(self.sec, s, side="right")) - 1
        if i >= 0 and s - self.sec[i] <= back and np.isfinite(self.last[i]) and self.last[i] > 0:
            return float(self.last[i])
        j = i + 1
        if j < len(self.sec) and self.sec[j] - s <= fwd and np.isfinite(self.last[j]) and self.last[j] > 0:
            return float(self.last[j])
        return None

    def px_any_after(self, s: int):
        """The next priced row after s; else the last known price."""
        j = int(np.searchsorted(self.sec, s, side="left"))
        ok = np.isfinite(self.last) & (self.last > 0)
        nxt = np.flatnonzero(ok[j:])
        if len(nxt):
            return float(self.last[j + nxt[0]])
        prv = np.flatnonzero(ok[:j])
        return float(self.last[prv[-1]]) if len(prv) else None

    def quote(self, s: int, back=120):
        i = int(np.searchsorted(self.sec, s, side="right")) - 1
        if i >= 0 and s - self.sec[i] <= back:
            return float(self.bid[i]), float(self.ask[i])
        return None

    def vol5_lots(self, s: int, lot: int) -> float:
        a = int(np.searchsorted(self.sec, s - 300, side="left"))
        z = int(np.searchsorted(self.sec, s, side="left"))
        return float(np.nansum(self.vol[a:z])) / lot

    def recorded_near(self, s: int, window=600) -> bool:
        a = int(np.searchsorted(self.sec, s - window, side="left"))
        z = int(np.searchsorted(self.sec, s, side="right"))
        return z > a


def charge(buy: bool, px: float, qty: int, d: date) -> float:
    if not px > 0 or qty <= 0:
        return 0.0
    return float(COSTS.charge(buy, np.array([px]), np.array([qty]), np.array([_dn(d)]))[0])


def net_of(raw_e, raw_x, v_e, v_x, stop_exit, lot, d, slip=1.0):
    """Net rupees of one lot: liquidity fills (slippage x slip), dated charges."""
    B = float(FILLS.buy(np.array([raw_e]), np.array([v_e]))[0])
    S = float(FILLS.sell(np.array([raw_x]), np.array([v_x]), stop=stop_exit)[0])
    if slip != 1.0:
        B = round(raw_e + slip * (B - raw_e), 2)
        S = max(round(raw_x - slip * (raw_x - S), 2), 0.0)
    return (S - B) * lot - charge(True, B, lot, d) - charge(False, S, lot, d)


def first_exit(fsec, fhi, flo, start: int, end: int, side: str, stop: float, target: float):
    """(second, why) of the first futures second in [start, end) that hits the stop (checked first) or the target; else
    (end, 'time'). fhi / flo = each second's high / low (last folded in)."""
    a = int(np.searchsorted(fsec, start, side="left"))
    z = int(np.searchsorted(fsec, end, side="left"))
    hi, lo = fhi[a:z], flo[a:z]
    if side == "L":
        st, tg = lo <= stop, hi >= target
    else:
        st, tg = hi >= stop, lo <= target
    any_ = st | tg
    if not any_.any():
        return end, "time"
    k = int(np.argmax(any_))
    return int(fsec[a + k]), ("stop" if st[k] else "target")


def simulate(fut, opt: Series, entry_s: int, end_s: int, side, stop, target, lot, d, t2=None, e_level=None):
    """One trade. fut = (sec, hi, lo). With t2 (A+, Amendment 1): when the futures touch `target` (T1) the stop moves to
    e_level and the trade runs on to t2, that stop or the time exit. Returns a dict, or None when the entry has no
    option price."""
    raw_e = opt.px(entry_s)
    if raw_e is None:
        return None
    xs, why = first_exit(fut[0], fut[1], fut[2], entry_s, end_s, side, stop, target)
    if t2 is not None and why == "target" and xs + 1 < end_s:
        xs, why = first_exit(fut[0], fut[1], fut[2], xs + 1, end_s, side, e_level, t2)
        why = {"stop": "be_stop", "target": "target2", "time": "time"}[why]
    raw_x = opt.px(xs)
    if raw_x is None:
        raw_x = opt.px_any_after(xs)
    ve, vx = opt.vol5_lots(entry_s, lot), opt.vol5_lots(xs, lot)
    st = why in ("stop", "be_stop")
    n1 = net_of(raw_e, raw_x, ve, vx, st, lot, d, 1.0)
    n2 = net_of(raw_e, raw_x, ve, vx, st, lot, d, 2.0)
    qe, qx = opt.quote(entry_s), opt.quote(xs)
    nba = None
    if qe and qx and qe[1] > 0 and qx[0] >= 0:
        nba = (qx[0] - qe[1]) * lot - charge(True, qe[1], lot, d) - charge(False, qx[0], lot, d)
    return dict(exit_s=xs, why=why, raw_e=raw_e, raw_x=raw_x, net=round(n1, 2), net2=round(n2, 2),
                net_ba=None if nba is None else round(nba, 2))


# ------------------------------------------------------------------------------------------------ statistics
def summary(nets, nets2=None) -> dict:
    x = np.asarray(nets, float)
    if not len(x):
        return dict(n=0, win=None, net=0.0, net2=0.0, mean=None, avg_win=None, avg_loss=None)
    w, lo = x[x > 0], x[x <= 0]
    y = np.asarray(nets2 if nets2 is not None else nets, float)
    return dict(n=int(len(x)), win=round(float((x > 0).mean()), 3), net=round(float(x.sum()), 0),
                net2=round(float(y.sum()), 0), mean=round(float(x.mean()), 1), mean2=round(float(y.mean()), 1),
                avg_win=round(float(w.mean()), 0) if len(w) else None,
                avg_loss=round(float(lo.mean()), 0) if len(lo) else None)


def selection_p(all_nets, of_nets, rng, n=10_000) -> float:
    """T1: share of random same-size subsets of the price-only trades whose mean >= the OF mean."""
    a = np.asarray(all_nets, float)
    k = len(of_nets)
    if k < 3 or k > len(a):
        return 1.0
    obs = float(np.mean(of_nets))
    hits = 0
    for _ in range(n):
        hits += a[rng.choice(len(a), k, replace=False)].mean() >= obs - 1e-9
    return (1 + hits) / (1 + n)


def twin_p(of_nets, twin_means) -> float:
    """T2: (1 + twin means >= the OF mean) / (1 + draws)."""
    if len(of_nets) < 3 or not len(twin_means):
        return 1.0
    obs = float(np.mean(of_nets))
    t = np.asarray(twin_means, float)
    return float((1 + np.sum(t >= obs - 1e-9)) / (1 + len(t)))


def bh_with_prior(p, m_total: int):
    """q = p x m / rank within these tests (the other tests taken as larger), step-up, capped at 1; and the q within."""
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)

    def q_of(m):
        q = p[o] * m / np.arange(1, n + 1)
        q = np.minimum.accumulate(q[::-1])[::-1]
        out = np.empty(n)
        out[o] = np.minimum(q, 1.0)
        return out
    return q_of(m_total), q_of(n)
