"""R73 entry filters on Donchian 20 (PREREG.md section 2). Signal source: the INDEX, every rule.

Donchian itself is unchanged (a 5-minute index close beyond the previous 20 bars' channel, hunt/r44/ind.py); R73 only
decides whether to TAKE each of its signals, and (for the waits) when.

Inputs: the continuous series of 5-minute index bars as R51's `fam_r44.bars5` builds them (day, bar-of-day b, O, H, L,
C; missing bars skipped) and each session's 1-minute index candles (`Day.idx`, (4, 375) O H L C on the 09:15 grid).

  A. trend filters T1-T8, decided at the signal bar's CLOSE: every value at bar i uses 5-minute bars 0..i only, a
     15-minute / 1-hour bar counts only once its last 5-minute bar has closed, and T8 reads 1-minute candles up to the
     signal bar's last minute (5b + 4). test_r73.py's look-ahead guard proves it.
  B. waits W{N}{H,D,HD}: the N one-minute candles after the signal bar (rows 5(b+1) .. 5(b+1)+N-1) must all exist and
     pass; the entry is then at the open of row 5(b+1)+N. Nothing at or after the entry row is read.

Pure: numpy only.
"""
from __future__ import annotations

import numpy as np

N_DON = 20                                  # Donchian 20
TRENDS = ("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8")
WAITS = tuple(f"W{n}{k}" for n in (2, 3, 5) for k in ("H", "D", "HD"))
NAMES = {
    "T1": "5-min close beyond its EMA 20",
    "T2": "5-min close beyond its EMA 50",
    "T3": "15-min EMA 20 rising (falling for puts)",
    "T4": "1-hour EMA 20 rising (falling for puts)",
    "T5": "ADX 14 above 20",
    "T6": "ADX 14 above 25",
    "T7": "Supertrend (10, 3) on 5-min bars with the break",
    "T8": "close beyond the day's average typical price (time-weighted VWAP stand-in; the index has no volume)",
    "C": "best trend filter + best wait (chosen on design)",
}
for _n in (2, 3, 5):
    NAMES[f"W{_n}H"] = f"wait {_n} one-minute candles; all close beyond the broken channel edge"
    NAMES[f"W{_n}D"] = f"wait {_n} one-minute candles; the last closes beyond the signal bar's close"
    NAMES[f"W{_n}HD"] = f"wait {_n} one-minute candles; both (break holds and price kept going)"


# ------------------------------------------------------------------------------------------------ indicators
def ema(x, n):
    """adjust=False: e[0] = x[0], e[t] = a x[t] + (1 - a) e[t-1] (as hunt/r44/ind.py and r71rules)."""
    out = np.empty(len(x))
    if not len(x):
        return out
    a = 2.0 / (n + 1.0)
    out[0] = x[0]
    for t in range(1, len(x)):
        out[t] = a * x[t] + (1.0 - a) * out[t - 1]
    return out


def adx(h, l, c, n=14):
    """Wilder ADX, line for line hunt/r44/ind.py `adx` (pure Python here; test_r73 checks they agree). NaN before
    bar 2n - 1."""
    T = len(c)
    out = np.full(T, np.nan)
    if T < 2 * n:
        return out
    sp = sm = st = dxs = a = 0.0
    for t in range(1, T):
        up = h[t] - h[t - 1]
        dn = l[t - 1] - l[t]
        pdm = up if (up > dn and up > 0) else 0.0
        mdm = dn if (dn > up and dn > 0) else 0.0
        tr = max(h[t] - l[t], abs(h[t] - c[t - 1]), abs(l[t] - c[t - 1]))
        if t <= n:
            sp += pdm
            sm += mdm
            st += tr
            if t < n:
                continue
        else:
            sp = sp - sp / n + pdm
            sm = sm - sm / n + mdm
            st = st - st / n + tr
        pdi = 100.0 * sp / st if st > 0 else 0.0
        mdi = 100.0 * sm / st if st > 0 else 0.0
        dx = 100.0 * abs(pdi - mdi) / (pdi + mdi) if (pdi + mdi) > 0 else 0.0
        if t < 2 * n - 1:
            dxs += dx
        elif t == 2 * n - 1:
            dxs += dx
            a = dxs / n
            out[t] = a
        else:
            a = (a * (n - 1) + dx) / n
            out[t] = a
    return out


def supertrend_dir(h, l, c, n=10, mult=3.0) -> np.ndarray:
    """+1 up / -1 down per bar, 0 until the ATR exists (hunt/r63/r63rules.py `supertrend_dir`: Wilder ATR, hl2 bands)."""
    m = len(c)
    d = np.zeros(m, np.int64)
    if m == 0:
        return d
    tr = np.empty(m)
    tr[0] = h[0] - l[0]
    for i in range(1, m):
        tr[i] = max(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))
    atr = np.full(m, np.nan)
    if m >= n:
        atr[n - 1] = tr[:n].mean()
        for i in range(n, m):
            atr[i] = (atr[i - 1] * (n - 1) + tr[i]) / n
    up_b = dn_b = np.nan
    cur = 0
    for i in range(m):
        if not np.isfinite(atr[i]):
            continue
        mid = (h[i] + l[i]) / 2.0
        bu, bd = mid - mult * atr[i], mid + mult * atr[i]
        if cur == 0:
            up_b, dn_b, cur = bu, bd, 1 if c[i] >= mid else -1
        else:
            up_b = max(bu, up_b) if c[i - 1] > up_b else bu
            dn_b = min(bd, dn_b) if c[i - 1] < dn_b else bd
            if cur == -1 and c[i] > dn_b:
                cur = 1
            elif cur == 1 and c[i] < up_b:
                cur = -1
        d[i] = cur
    return d


def htf_slope(d, b, c, k: int, n: int = 20) -> np.ndarray:
    """At each 5-minute bar i: +1 if the EMA n of the k x 5-minute bars (09:15-anchored, slot = b // k, close = the
    slot's last 5-minute close; continuous across days) is higher on the last FULLY CLOSED slot than on the one before,
    -1 if lower, 0 if equal or not yet known. A slot is fully closed at bar i once its last 5-minute bar (b % k = k-1)
    has closed, or once a bar of a later slot or day has (a slot whose last bar is missing)."""
    T = len(c)
    out = np.zeros(T, np.int64)
    if not T:
        return out
    slot_last, slot_c, slot_day, slot_id = [], [], [], []
    for i in range(T):
        s = int(b[i]) // k
        if slot_last and slot_day[-1] == d[i] and slot_id[-1] == s:
            slot_last[-1], slot_c[-1] = i, c[i]
        else:
            slot_last.append(i)
            slot_c.append(c[i])
            slot_day.append(d[i])
            slot_id.append(s)
    e = ema(np.array(slot_c, float), n)
    g = 0                                       # index of the slot holding bar i
    for i in range(T):
        while slot_last[g] < i:
            g += 1
        full = g if int(b[i]) % k == k - 1 else g - 1
        if full >= 1:
            out[i] = 1 if e[full] > e[full - 1] else (-1 if e[full] < e[full - 1] else 0)
    return out


def tp_avg(idx: np.ndarray, last_row: int) -> float:
    """T8: the mean of (H + L + C) / 3 over the session's 1-minute candles 0..last_row that have all three (NaN if
    none)."""
    seg = idx[1:4, :last_row + 1]
    ok = np.isfinite(seg).all(axis=0)
    if not ok.any():
        return float("nan")
    return float(seg[:, ok].mean(axis=0).mean())


# ------------------------------------------------------------------------------------------------ the Donchian break
def channel(h, l, n=N_DON):
    """(hi, lo): the highest high / lowest low of the PREVIOUS n bars (NaN for the first n bars), the edges Donchian's
    close breaks (hunt/r44/ind.py `donchian`)."""
    T = len(h)
    hi, lo = np.full(T, np.nan), np.full(T, np.nan)
    for i in range(n, T):
        hi[i] = h[i - n:i].max()
        lo[i] = l[i - n:i].min()
    return hi, lo


def features(d, b, o, h, l, c) -> dict:
    """Every per-bar value the trend filters and the waits read, from the 5-minute series (bars 0..i at bar i)."""
    h, l, c = (np.asarray(x, float) for x in (h, l, c))
    hi, lo = channel(h, l)
    return dict(c=c, e20=ema(c, 20), e50=ema(c, 50), adx=adx(h, l, c, 14), st=supertrend_dir(h, l, c, 10, 3.0),
                s15=htf_slope(d, b, c, 3), s60=htf_slope(d, b, c, 12), hi=hi, lo=lo)


def trend_ok(rule: str, F: dict, i: int, s: int, tp: float = float("nan")) -> bool:
    """A trend filter at signal bar i (s = +1 call / -1 put). tp: the day's average typical price to bar i (T8). A NaN
    value (warm-up) fails."""
    x = F["c"][i]
    if rule == "T1":
        return bool(s * (x - F["e20"][i]) > 0)
    if rule == "T2":
        return bool(s * (x - F["e50"][i]) > 0)
    if rule == "T3":
        return int(F["s15"][i]) == s
    if rule == "T4":
        return int(F["s60"][i]) == s
    if rule == "T5":
        return bool(F["adx"][i] > 20)
    if rule == "T6":
        return bool(F["adx"][i] > 25)
    if rule == "T7":
        return int(F["st"][i]) == s
    if rule == "T8":
        return bool(s * (x - tp) > 0)
    raise ValueError(rule)


def parse_wait(rule: str):
    """'W5HD' -> (5, True, True)."""
    n = int(rule[1])
    k = rule[2:]
    return n, "H" in k, "D" in k


def wait_entry(idx: np.ndarray, b: int, s: int, level: float, sig_close: float, rule: str) -> int | None:
    """A wait after the 5-minute signal bar b of the day: the entry row 5(b+1)+N if the N one-minute candles
    5(b+1) .. 5(b+1)+N-1 all exist and (H) all close beyond `level` (the broken channel edge) and / or (D) the N-th
    closes beyond `sig_close` (the signal bar's close); None otherwise (a missing candle, or past the session).
    Reads index closes of those N rows only."""
    n, need_h, need_d = parse_wait(rule)
    r0 = 5 * (b + 1)
    if r0 + n >= idx.shape[1]:
        return None
    cl = idx[3, r0:r0 + n]
    if not np.isfinite(cl).all():
        return None
    if need_h and not (s * (cl - level) > 0).all():
        return None
    if need_d and not s * (cl[-1] - sig_close) > 0:
        return None
    return r0 + n
