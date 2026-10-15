"""R52 family H25 + H37: the closest H25 candle / moving-average rules and H37 Fibonacci / Elliott / Gann / P&F /
Market Profile rules (research/HUNT_H25.md, research/HUNT_H37.md; code research/hunt/h25/, research/hunt/h37/),
re-tested on Nov 2024+.

Written 15 Oct 2026 for R52 (PREREG.md, FAMILY_BRIEF.md). Signal source: the index itself for every rule (index
1-minute bars -> 1/3/5/15/30/60-minute candles anchored 09:15, daily bars, 1-minute closes for Point & Figure). Nothing
here reads an option's own price chart or the option chain.

How the original code is reused
    H37: research/hunt/h37/sigs.py is pure numpy, so it is imported unchanged (under the private name r52_h37_sigs,
        since h25 has a sigs.py too): bars, daily, atr, zigzag, swing_signals, renko_pf, profile_levels, mp_signals,
        _win. Only the thin adapters below are new; they follow sigs.all_events `emit` (intraday: sig_min = 09:15 +
        the bar's last column; daily: the signal is known at the day's close and is bought at 09:16 the NEXT trading
        day, sig_min 09:15) and the GTC_D block inside all_events (ported line for line in `_gtc_d`, because it is not a
        function of its own), and the Renko/P&F box sizes of all_events (prev close x 0.1/0.2/0.3 %, or 0.5/1 x the
        15-minute ATR14 at the previous day's last bar).
    H25: research/hunt/h25/sigs.py imports TA-Lib at import time (from a scratch folder that no longer exists), so it
        cannot be imported here. The pieces these rules need are ported faithfully:
          - the candle builder = sigs.bars (identical to h37 sigs.bars, which is used);
          - sigs._prev / xup / xdn / sig / hma / ma and the CDL direction rule of sigs.compute (dojis that are
            always +100 get the reversal of the last 5 bars' move);
          - TA-Lib 0.8.1's SMA, EMA, WMA and its CDLDOJI, CDLLONGLEGGEDDOJI, CDLSEPARATINGLINES and CDLLADDERBOTTOM
            (ta-lib C sources, default candle settings), with TA-Lib's own running sums so the floating point is the
            same. test_fam_h25h37.py checks them against TA-Lib itself when it can be imported (bit-identical on the
            Jan 2024 - Oct 2026 lake for every index and timeframe used here, checked 15 Oct 2026).
    The original studies looked rules up in outcome tables built by the obuy engine; here the same signals go through
    obuy directly (R52's fills, charges, lots and twins). The frame of both studies is kept: 1-ITM strike of the nearest
    expiry (StrikeRule(1, 'near')), entry signals 09:15..15:00 (bar end <= 15:00), expiry days skipped, one position at
    a time per index (book), at most 5 trades a day, square-off 15:10. Exits are each study's own menu:
      H25 (outcomes.py): ARM (-15% premium stop, out after 20 min unless +5%, index stop BN 30 / FIN 15 / NIFTY 15 /
          SENSEX 50 / MIDCP 8 points, carried as the signal's idx_stop = close at sig_min -/+ those points), P20,
          PCT (-15% / +30%), LAD (-15%, ladder R = 15%, +30%), T15/T30/T60, and Amendment 1's 11 point exits.
      H37 (outcomes.py): 12 point exits P{15..30}_{10..20}, ARM (the app's liquidity.ARM_EXITS, no index stop), LAD
          (-40/+40 points, ladder R = 40 points), T15/T30/T60. No signal levels.

ORIGINAL settings
    The variants the write-ups name (gate passers / top by t) with their own index, timeframe, parameters and exit.
    H37-2 names two variants with different premium exits (NIFTY LAD, MIDCP +20/-20), which one obuy Rule cannot hold,
    so it is split by index: "H37-2@NIFTY" and "H37-2@MIDCPNIFTY" (as fam_r1 does).
    Walk-forward FAMILIES (H25-11, H37-6, H37-10) and H37-9 have no single frozen variant: the per-year WF picks
    (wf_picks.csv / wf.csv) were deleted with the scratch folders, and rebuilding them would need pre-2025 P&L. As for
    OBUY_GA-1 in r52rules ("no frozen variant named: the catalog's first setting"), the original is the FIRST setting
    in the study's own loop order (H25: TFS 1,3,5,..; EXITS ARM first. H37: KS 2 first; EXITS P15_10 first; box
    0.1 % first), on every index the family's walk-forward chose from (all five; one book per index). The grid then
    holds the rest of the family's own timeframe / parameter / exit axes, so R52's re-tune walk-forward plays the
    original WF's role. These four keys are flagged in their `why`.
GRID = the original study's own axes around the original, one axis at a time (timeframes, parameters, exits; H25-10
    also the long-legged doji, H37-9 also triple tops), <= 23 overrides. Orientation (follow / fade) is not varied.

LEFT
    none of the eleven keys asked for. Merged rows R4-11 / R4-12 (intraday Gann minutes-from-extreme, catalogue
    "CLOSE (daily only)") are not part of H37-6's daily original and are not re-tested here.
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import numpy as np
import pandas as pd

OPEN_M = 9 * 60 + 15
W = 375
MW = 346                      # signal columns 0..345 = 09:15..15:00 (h25/h37 evaluate.MW)
SQ = 15 * 60 + 10
WIN = (OPEN_M, 15 * 60)
UNDS5 = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
COLS = ["und", "day", "sig_min", "side", "book", "idx_stop"]
H37_DIR = Path(__file__).resolve().parents[1] / "h37"
_RESEARCH = str(H37_DIR.parents[1])
if _RESEARCH not in sys.path:
    sys.path.insert(0, _RESEARCH)


def _load_h37():
    """research/hunt/h37/sigs.py under a private name (h25 has a sigs.py too)."""
    if "r52_h37_sigs" in sys.modules:
        return sys.modules["r52_h37_sigs"]
    spec = importlib.util.spec_from_file_location("r52_h37_sigs", H37_DIR / "sigs.py")
    m = importlib.util.module_from_spec(spec)
    sys.modules["r52_h37_sigs"] = m
    spec.loader.exec_module(m)
    return m


S37 = _load_h37()


# ================================================================================================ TA-Lib ports
# TA-Lib 0.8.1 (ta-lib C core 0.6.x) with default candle settings. Ranges: RealBody = |c - o|, HighLow = h - l.
# CANDLEAVERAGE(set, total, i) = factor * (total / avgPeriod if avgPeriod else RANGE(set, i)).
# Output 0 (candles) / NaN (MAs) inside the lookback, as the Python wrapper returns.

def ta_sma(x, n):
    """TA_SMA: running total, out = total / n."""
    x = np.asarray(x, float)
    out = np.full(len(x), np.nan)
    if len(x) < n:
        return out
    tot = 0.0
    for i in range(n - 1):
        tot += x[i]
    tr = 0
    for i in range(n - 1, len(x)):
        tot += x[i]
        out[i] = tot / n
        tot -= x[tr]
        tr += 1
    return out


def ta_ema(x, n):
    """TA_EMA (classic compatibility): seed = SMA of the first n, then prev = (x - prev) * k + prev, k = 2/(n+1)."""
    x = np.asarray(x, float)
    out = np.full(len(x), np.nan)
    if len(x) < n:
        return out
    k = 2.0 / (n + 1)
    tot = 0.0
    for i in range(n):
        tot += x[i]
    prev = tot / n
    out[n - 1] = prev
    for i in range(n, len(x)):
        prev = (x[i] - prev) * k + prev
        out[i] = prev
    return out


def ta_wma(x, n):
    """TA_WMA (ta-lib C core bundled with TA-Lib 0.8.1, src/ta_func/ta_WMA.c): periodSum / periodSub running sums,
    rebuilt from the window (oldest first, weights 1..n) every 8 x n bars."""
    x = np.asarray(x, float)
    out = np.full(len(x), np.nan)
    if len(x) < n:
        return out
    if n == 1:
        return x.copy()
    div = float(n) * (n + 1) / 2.0
    psum = psub = 0.0
    i = 0
    for w in range(1, n):
        v = x[i]
        psub += v
        psum += v * w
        i += 1
    reseed = 8 * n
    trail_v = 0.0
    tr = 0
    while i < len(x):
        v = x[i]
        psub += v
        psub -= trail_v
        psum += v * n
        reseed -= 1
        if reseed <= 0:
            reseed = 8 * n
            psub = psum = 0.0
            rw = 1
            for j in range(i - (n - 1), i + 1):
                psub += x[j]
                psum += x[j] * rw
                rw += 1
        trail_v = x[tr]
        tr += 1
        out[i] = psum / div
        psum -= psub
        i += 1
    return out


def _realbody(o, c):
    return np.abs(c - o)


def _color(o, c):
    return np.where(c >= o, 1, -1)


def _upper(o, h, c):
    return h - np.maximum(o, c)


def _lower(o, lo, c):
    return np.minimum(o, c) - lo


def ta_cdldoji(o, h, lo, c):
    """TA_CDLDOJI: real body <= 0.1 x mean high-low of the previous 10 candles -> +100."""
    n = len(c)
    out = np.zeros(n, np.int32)
    P = 10
    if n <= P:
        return out
    rng, rb = h - lo, _realbody(o, c)
    tot = 0.0
    for i in range(0, P):
        tot += rng[i]
    tr = 0
    for i in range(P, n):
        out[i] = 100 if rb[i] <= 0.1 * (tot / P) else 0
        tot += rng[i] - rng[tr]
        tr += 1
    return out


def ta_cdllongleggeddoji(o, h, lo, c):
    """TA_CDLLONGLEGGEDDOJI: a doji (BodyDoji) with a lower OR upper shadow longer than its real body (ShadowLong,
    period 0, factor 1) -> +100."""
    n = len(c)
    out = np.zeros(n, np.int32)
    P = 10
    if n <= P:
        return out
    rng, rb = h - lo, _realbody(o, c)
    up, dn = _upper(o, h, c), _lower(o, lo, c)
    tot = 0.0
    for i in range(0, P):
        tot += rng[i]
    tr = 0
    for i in range(P, n):
        if rb[i] <= 0.1 * (tot / P) and (dn[i] > rb[i] or up[i] > rb[i]):
            out[i] = 100
        tot += rng[i] - rng[tr]
        tr += 1
    return out


def ta_cdlseparatinglines(o, h, lo, c):
    """TA_CDLSEPARATINGLINES: candle i-1 and i of opposite colours with (nearly) the same open (Equal: |open - open| <=
    0.05 x mean high-low of the 5 candles before i-1), candle i a belt hold (body > BodyLong = mean body of the 10
    previous candles; white with lower shadow, or black with upper shadow, < 0.1 x mean high-low of the 10 previous)
    -> colour(i) x 100."""
    n = len(c)
    out = np.zeros(n, np.int32)
    start = 11
    if n <= start:
        return out
    rng, rb = h - lo, _realbody(o, c)
    col = _color(o, c)
    up, dn = _upper(o, h, c), _lower(o, lo, c)
    svs = blg = eq = 0.0
    svs_t = blg_t = start - 10
    eq_t = start - 5
    for i in range(svs_t, start):
        svs += rng[i]
    for i in range(blg_t, start):
        blg += rb[i]
    for i in range(eq_t, start):
        eq += rng[i - 1]
    for i in range(start, n):
        a_eq = 0.05 * (eq / 5)
        if (col[i - 1] == -col[i] and o[i] <= o[i - 1] + a_eq and o[i] >= o[i - 1] - a_eq
                and rb[i] > 1.0 * (blg / 10)
                and ((col[i] == 1 and dn[i] < 0.1 * (svs / 10)) or (col[i] == -1 and up[i] < 0.1 * (svs / 10)))):
            out[i] = int(col[i]) * 100
        svs += rng[i] - rng[svs_t]
        blg += rb[i] - rb[blg_t]
        eq += rng[i - 1] - rng[eq_t - 1]
        svs_t += 1
        blg_t += 1
        eq_t += 1
    return out


def ta_cdlladderbottom(o, h, lo, c):
    """TA_CDLLADDERBOTTOM: three black candles with lower opens and closes, a 4th black candle with an upper shadow
    (> 0.1 x mean high-low of the 10 candles before it), a 5th white candle opening above the 4th's open and closing
    above its high -> +100."""
    n = len(c)
    out = np.zeros(n, np.int32)
    start = 14
    if n <= start:
        return out
    rng = h - lo
    col = _color(o, c)
    up = _upper(o, h, c)
    tot = 0.0
    tr = start - 10
    for i in range(tr, start):
        tot += rng[i - 1]
    for i in range(start, n):
        if (col[i - 4] == -1 and col[i - 3] == -1 and col[i - 2] == -1
                and o[i - 4] > o[i - 3] > o[i - 2] and c[i - 4] > c[i - 3] > c[i - 2]
                and col[i - 1] == -1 and up[i - 1] > 0.1 * (tot / 10)
                and col[i] == 1 and o[i] > o[i - 1] and c[i] > h[i - 1]):
            out[i] = 100
        tot += rng[i - 1] - rng[tr - 1]
        tr += 1
    return out


CDL = {"CDLDOJI": ta_cdldoji, "CDLLONGLEGGEDDOJI": ta_cdllongleggeddoji,
       "CDLSEPARATINGLINES": ta_cdlseparatinglines, "CDLLADDERBOTTOM": ta_cdlladderbottom}
NEUTRAL = {"CDLDOJI", "CDLLONGLEGGEDDOJI", "CDLRICKSHAWMAN", "CDLHIGHWAVE", "CDLSPINNINGTOP"}   # h25 sigs.NEUTRAL
MA_FN = {"SMA": ta_sma, "EMA": ta_ema, "WMA": ta_wma}


# ================================================================================================ h25 sigs.py ports
def _prev(a, k=1):
    p = np.empty_like(a)
    p[:k] = np.nan
    p[k:] = a[:-k]
    return p


def xup(a, b):
    with np.errstate(invalid="ignore"):
        return (a > b) & (_prev(a) <= _prev(b))


def xdn(a, b):
    with np.errstate(invalid="ignore"):
        return (a < b) & (_prev(a) >= _prev(b))


def sig(up, dn):
    up = np.asarray(up, bool)
    dn = np.asarray(dn, bool)
    return np.where(up & ~dn, 1, np.where(dn & ~up, -1, 0)).astype(np.int8)


def hma(c, n):
    """h25 sigs.hma: WMA(2 WMA(n/2) - WMA(n), sqrt n)."""
    h = max(n // 2, 1)
    s = max(int(np.sqrt(n)), 1)
    x = 2 * ta_wma(c, h) - ta_wma(c, n)
    out = np.full_like(c, np.nan)
    ok = ~np.isnan(x)
    if ok.sum() > s:
        out[ok] = ta_wma(x[ok], s)
    return out


def ma(T, c, n):
    return hma(c, n) if T == "HMA" else MA_FN[T](c, n)


def h25_signal(B, name):
    """One signal of h25 sigs.compute on bars B (int8 per bar: +1 bull, -1 bear). Supported: the four CDL patterns
    above and '<T>_X<a>_<b>' fast/slow crosses for T in SMA, EMA, WMA, HMA."""
    o, h, lo, c = (np.asarray(B[k], np.float64) for k in ("o", "h", "l", "c"))
    if name in CDL:
        r = CDL[name](o, h, lo, c)
        if name in NEUTRAL and not (r < 0).any():
            mv5 = c - _prev(c, 5)
            with np.errstate(invalid="ignore"):
                s = np.where(r != 0, np.where(mv5 < 0, 1, np.where(mv5 > 0, -1, 0)), 0)
        else:
            s = np.sign(r)
        return s.astype(np.int8)
    T, rest = name.split("_", 1)
    if not rest.startswith("X") or T not in ("SMA", "EMA", "WMA", "HMA"):
        raise ValueError(f"h25 signal not ported: {name}")
    a, b = (int(x) for x in rest[1:].split("_"))
    with np.errstate(invalid="ignore"):
        fa, sb = ma(T, c, a), ma(T, c, b)
        return sig(xup(fa, sb), xdn(fa, sb))


# ================================================================================================ adapters
ISTOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "NIFTY": 15.0, "SENSEX": 50.0, "MIDCPNIFTY": 8.0}   # h25 outcomes.ISTOP


def _frame(rows):
    if not rows:
        return pd.DataFrame(columns=COLS)
    out = pd.concat(rows, ignore_index=True)
    return out.sort_values(["und", "day", "sig_min", "side"], kind="stable").reset_index(drop=True)


def _rows(u, ix, B, t, side, book, istop=None):
    """Intraday events at bars t -> signal rows (sig_min = 09:15 + bar end column; only bar ends <= 15:00)."""
    t = np.asarray(t, np.int64)
    side = np.asarray(side, np.int64)
    if len(t):
        ce = np.asarray(B["col_end"])[t].astype(np.int64)
        ok = (ce >= 0) & (ce <= MW - 1) & (side != 0)
        t, side, ce = t[ok], side[ok], ce[ok]
    if not len(t):
        return None
    days = np.array(ix.days, dtype=object)[np.asarray(B["dpos"])[t]]
    df = pd.DataFrame(dict(und=u, day=days, sig_min=(OPEN_M + ce).astype(int), side=side.astype(int),
                           book=f"{book}_{u}"))
    df["idx_stop"] = np.nan
    if istop is not None:
        df["idx_stop"] = np.asarray(B["c"], float)[t] - side * istop
    return df


def _daily_rows(u, ix, B, t, side, book):
    """Daily events at day-bar t (known at that day's close) -> bought at 09:16 the NEXT trading day (sig_min 09:15),
    as h37 all_events emit(daily_mode=True)."""
    t = np.asarray(t, np.int64)
    side = np.asarray(side, np.int64)
    nxt = np.asarray(B["dpos"])[t] + 1 if len(t) else t
    ok = (nxt < len(ix.days)) & (side != 0)
    if not ok.any():
        return None
    days = np.array(ix.days, dtype=object)[nxt[ok]]
    df = pd.DataFrame(dict(und=u, day=days, sig_min=OPEN_M, side=side[ok].astype(int), book=f"{book}_{u}"))
    df["idx_stop"] = np.nan
    return df


def _bars(ix, tf):
    M = ix.mat()
    return S37.daily(M) if tf == "D" else S37.bars(M, int(tf))


# ------------------------------------------------------------------------------------------------ H25
def h25_sig(mk, unds=("SENSEX",), tf=5, name="CDLSEPARATINGLINES", orient="fade"):
    """h25 evaluate.trades_for's signal side: h25 sigs.compute(bars(tf))[name] on the whole index series, FOLLOW =
    the signal's side, FADE = the opposite option. Every row carries the ARM exit's index stop (ISTOP points against
    the close at sig_min); only the ARM exit honours it (the other h25 exits run with sig_levels=False)."""
    rows = []
    for u in unds:
        ix = mk.index(u)
        if not len(ix.days):
            continue
        B = _bars(ix, tf)
        if not len(B["c"]):
            continue
        s = h25_signal(B, name).astype(np.int64)
        if orient == "fade":
            s = -s
        t = np.nonzero(s)[0]
        rows.append(_rows(u, ix, B, t, s[t], f"h25_{name}_{orient}", istop=ISTOP[u]))
    return _frame([r for r in rows if r is not None])


# ------------------------------------------------------------------------------------------------ H37
def h37_swing(mk, unds=("NIFTY",), tf=5, k=4, name="FIB_K786"):
    """h37 sigs.swing_signals(bars(tf), atr14, k)[name] (FIB_*, EW3, ...), as sigs.all_events emits it."""
    rows = []
    for u in unds:
        ix = mk.index(u)
        if not len(ix.days):
            continue
        B = _bars(ix, tf)
        if len(B["c"]) < 2:
            continue
        A = S37.atr(B)
        ev, _ = S37.swing_signals(B, A, k, daily_mode=(tf == "D"))
        lst = ev[name]
        t = np.array([e[0] for e in lst], np.int64)
        sd = np.array([e[1] for e in lst], np.int64)
        f = _daily_rows if tf == "D" else _rows
        rows.append(f(u, ix, B, t, sd, f"h37_{name}"))
    return _frame([r for r in rows if r is not None])


def _gtc_d(B, A, k, dord):
    """Port of the GTC_D block of h37 sigs.all_events: calendar days since the last confirmed daily pivot reaching
    30/45/.../360 -> the first trading day t at or after it, signal at day t-1's close: PE if that close is above the
    pivot price, CE if below.

    ONE DEVIATION (look-ahead fix, 15 Oct 2026): the original kept the signal only if t <= b, b = the bar before the
    NEXT pivot's confirmation, so a signal decided at day t-1's close was dropped when day t itself confirmed a new
    pivot - day t's bars decided a trade bought at day t's 09:16 open (found by the look-ahead guard in
    test_fam_h25h37.py). Here the pivot only has to be the last one confirmed at day t-1's close: a <= t-1 <= b."""
    _, P = S37.swing_signals(B, A, k, daily_mode=True)
    pb, pp, pt, pc = P
    lst = []
    for j in range(len(pb)):
        a, b = S37._win(P, j, len(dord))
        for T in S37.GTC_D:
            tgt = dord[pb[j]] + T
            t = int(np.searchsorted(dord, tgt))
            if a + 1 <= t <= b + 1 and t >= 1:            # original: a + 1 <= t <= b (see above)
                lst.append((t - 1, -1 if B["c"][t - 1] > pp[j] else 1))
    lst.sort(key=lambda e: e[0])
    return lst


def h37_gtc_d(mk, unds=UNDS5, k=2):
    """H37 GTC_D: daily Gann calendar-day time cycles, reversal, bought 09:16 the next day."""
    rows = []
    for u in unds:
        ix = mk.index(u)
        if len(ix.days) < 2:
            continue
        B = _bars(ix, "D")
        allord = np.array([d.toordinal() for d in ix.days])
        lst = _gtc_d(B, S37.atr(B), k, allord[B["dpos"]])
        t = np.array([e[0] for e in lst], np.int64)
        sd = np.array([e[1] for e in lst], np.int64)
        rows.append(_daily_rows(u, ix, B, t, sd, "h37_GTC_D"))
    return _frame([r for r in rows if r is not None])


def _box(ix, par):
    """Renko / P&F box per day, as h37 all_events: 'b0.1pct' = 0.1 % of the previous close; 'b0.5atr' = 0.5 x the
    15-minute ATR14 at the previous day's last 15-minute bar."""
    M = ix.mat()
    nd = len(ix.days)
    okc = ~np.isnan(M["c"])
    lc = M["c"].shape[1] - 1 - np.argmax(okc[:, ::-1], axis=1)
    dclose = M["c"][np.arange(nd), lc]
    prevc = np.concatenate([[np.nan], dclose[:-1]])
    if par.endswith("pct"):
        return float(par[1:-3]) / 100.0 * prevc
    B15 = S37.bars(M, 15)
    if len(B15["c"]) < 2:
        return np.full(nd, np.nan)
    A15 = S37.atr(B15)
    last_of_day = np.full(nd, np.nan)
    ends = np.nonzero(np.concatenate([B15["dpos"][1:] != B15["dpos"][:-1], [True]]))[0]
    last_of_day[B15["dpos"][ends]] = A15[ends]
    atr_prev = np.concatenate([[np.nan], last_of_day[:-1]])
    return float(par[1:-3]) * atr_prev


def h37_pf(mk, unds=("SENSEX",), box="b0.1pct", name="PF_DT"):
    """h37 sigs.renko_pf on 1-minute closes (reset each day), signal at the minute whose close completes the pattern."""
    rows = []
    for u in unds:
        ix = mk.index(u)
        if not len(ix.days):
            continue
        M = ix.mat()
        R = S37.renko_pf(M["c"], M["o"], _box(ix, box))[name]
        d, m = np.nonzero(R)
        B1 = dict(dpos=d, col_end=m, c=M["c"][d, m])
        rows.append(_rows(u, ix, B1, np.arange(len(d)), R[d, m], f"h37_{name}"))
    return _frame([r for r in rows if r is not None])


def h37_mp(mk, unds=UNDS5, tf=5, name="MP_OOV"):
    """h37 sigs.mp_signals with the PREVIOUS day's 30-minute TPO POC / VAH / VAL (sigs.profile_levels)."""
    rows = []
    for u in unds:
        ix = mk.index(u)
        if not len(ix.days):
            continue
        M = ix.mat()
        B = S37.bars(M, int(tf))
        if not len(B["c"]):
            continue
        lev = S37.profile_levels(M["h"], M["l"], M["c"])
        levp = np.vstack([np.full((1, 3), np.nan), lev[:-1]])
        s = S37.mp_signals(B, M["o"], M["h"], M["l"], levp)[name].astype(np.int64)
        t = np.nonzero(s)[0]
        rows.append(_rows(u, ix, B, t, s[t], f"h37_{name}"))
    return _frame([r for r in rows if r is not None])


# ================================================================================================ exits
def _ladder():
    from obuy.engine import LADDER       # ProfitLock.LADDER
    return LADDER


H25_PTS = [(t, s) for t in (15, 20, 25, 30) for s in (10, 15, 20) if (t, s) != (20, 20)]
H25_EXITS = ["ARM", "P20", "PCT", "LAD", "T15", "T30", "T60"] + [f"P{t}_{s}" for t, s in H25_PTS]
H37_PTS = [(t, s) for t in (15, 20, 25, 30) for s in (10, 15, 20)]
H37_EXITS = [f"P{t}_{s}" for t, s in H37_PTS] + ["ARM", "LAD", "T15", "T30", "T60"]


def h25_exit(k):
    """h25 outcomes.exits / exits2 (square-off 15:10). ARM's index stop comes from the signal's idx_stop."""
    if k == "ARM":
        return dict(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=SQ)
    base = dict(sq_off=SQ, sig_levels=False)
    if k == "PCT":
        return dict(base, stop_pct=0.15, tgt_pct=0.30)
    if k == "LAD":
        return dict(base, stop_pct=0.15, ladder=_ladder(), ladder_ref_pct=0.15, tgt_pct=0.30)
    if k in ("T15", "T30", "T60"):
        return dict(base, time_stop=int(k[1:]), time_gain=None)
    if k == "P20":
        return dict(base, stop_pts=20, tgt_pts=20)
    t, s = (int(x) for x in k[1:].split("_"))
    return dict(base, stop_pts=s, tgt_pts=t)


def h37_exit(k):
    """h37 outcomes.EXITS (square-off 15:10 = obuy's default; no signal levels)."""
    if k == "ARM":
        return dict(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=SQ, sig_levels=False)  # liquidity.ARM_EXITS
    base = dict(sq_off=SQ, sig_levels=False)
    if k == "LAD":
        return dict(base, stop_pts=40, tgt_pts=40, ladder=_ladder(), ladder_ref_pts=40)
    if k in ("T15", "T30", "T60"):
        return dict(base, time_stop=int(k[1:]), time_gain=None)
    t, s = (int(x) for x in k[1:].split("_"))
    return dict(base, stop_pts=s, tgt_pts=t)


# ================================================================================================ specs
POS = dict(one_at_a_time=True, max_per_day=5)
H25_TFS = (1, 3, 5, 15, 30, 60)
H37_TFS = (5, 15, 60, "D")


def _orig(fn, params, exits, unds):
    return dict(fn=fn, params=params, money=1, series="near", unds=tuple(unds), exits=exits, expiry="skip", pos=POS,
                window=WIN)


def _h25_spec(key, title, why, u, tf, name, orient, ex, extra=()):
    unds = tuple(u) if isinstance(u, (tuple, list)) else (u,)
    grid = list(extra)
    grid += [dict(params=dict(tf=t)) for t in H25_TFS if t != tf]
    grid += [dict(exits=h25_exit(k)) for k in H25_EXITS if k != ex]
    assert len(grid) <= 23, key
    return dict(key=key, title=title, source="index", why=why,
                orig=_orig(h25_sig, dict(unds=unds, tf=tf, name=name, orient=orient), h25_exit(ex), unds), grid=grid)


def _h37_swing_spec(key, title, why, u, tf, k, name, ex, extra=()):
    unds = (u,)
    grid = list(extra)
    grid += [dict(params=dict(tf=t)) for t in H37_TFS if t != tf]
    grid += [dict(params=dict(k=kk)) for kk in S37.KS if kk != k]
    grid += [dict(exits=h37_exit(x)) for x in H37_EXITS if x != ex]
    assert len(grid) <= 23, key
    return dict(key=key, title=title, source="index", why=why,
                orig=_orig(h37_swing, dict(unds=unds, tf=tf, k=k, name=name), h37_exit(ex), unds), grid=grid)


FIRST = ("no frozen variant: the per-year walk-forward picks were not recorded (scratch wf.csv deleted) and "
         "rebuilding them needs pre-2025 P&L; as OBUY_GA-1, the original is the study's FIRST setting in its own loop "
         "order, on every index the family's walk-forward chose from; R52's re-tune walk-forward over the grid plays "
         "the original WF's role")

SPECS = [
    _h25_spec("H25-2", "H25 SENSEX 5-min CDL separating lines, FADE, arm exits",
              "original = the gate passer named in HUNT_H25.md (SENSEX 5m CDLSEPARATINGLINES fade ARM; holdout 76 tr "
              "+90/day); hunt/h25/evaluate.py trades_for('SENSEX', 5, 'CDLSEPARATINGLINES', 'fade', 'ARM')",
              "SENSEX", 5, "CDLSEPARATINGLINES", "fade", "ARM"),
    _h25_spec("H25-3", "H25 SENSEX 60-min HMA 13/34 cross, FADE, 30-min time exit",
              "original = the gate passer named in HUNT_H25.md (SENSEX 60m HMA_X13_34 fade T30; holdout 83 tr "
              "+39/day; T15 is in the grid)", "SENSEX", 60, "HMA_X13_34", "fade", "T30"),
    _h25_spec("H25-5", "H25 FINNIFTY 60-min EMA 13/34 cross, FADE, 15-min time exit",
              "original = #1 of HUNT_H25.md's top 20 by pre-holdout t (FINNIFTY 60m EMA_X13_34 fade T15; LAD and "
              "P25_10 are in the grid)", "FINNIFTY", 60, "EMA_X13_34", "fade", "T15"),
    _h25_spec("H25-6", "H25 SENSEX 5-min SMA 50/200 cross, FOLLOW, 30-min time exit",
              "original = #5 of HUNT_H25.md's top 20 (SENSEX 5m SMA_X50_200 follow T30)",
              "SENSEX", 5, "SMA_X50_200", "follow", "T30"),
    _h25_spec("H25-10", "H25 BANKNIFTY 30-min doji, FOLLOW, -15%/+30%",
              "original = the post-hoc Welch q < 0.05 variant of HUNT_H25.md (BANKNIFTY 30m CDLDOJI follow PCT); its "
              "twin (CDLLONGLEGGEDDOJI) is the first grid setting",
              "BANKNIFTY", 30, "CDLDOJI", "follow", "PCT",
              extra=[dict(params=dict(name="CDLLONGLEGGEDDOJI"))]),
    _h25_spec("H25-11", "H25 CDL ladder-bottom, FADE (walk-forward family)",
              "WF family CDLLADDERBOTTOM fade (HUNT_H25.md best WF family): " + FIRST + " (all 5 indices, 1-min, ARM)",
              UNDS5, 1, "CDLLADDERBOTTOM", "fade", "ARM"),
    _h37_swing_spec("H37-2@NIFTY", "H37 Fibonacci 78.6% break, trade the pullback (NIFTY 5-min, -40/+40 ladder)",
                    "original = the variant-gate passer of HUNT_H37.md (NIFTY 5m FIB_K786 k4 LAD; holdout 199 tr "
                    "+16/day); hunt/h37/evaluate.py trades_for('NIFTY', 'FIB_K786', 5, 'k4', 'LAD')",
                    "NIFTY", 5, 4, "FIB_K786", "LAD"),
    _h37_swing_spec("H37-2@MIDCPNIFTY", "H37 Fibonacci 78.6% break, trade the pullback (MIDCP 5-min, +20/-20)",
                    "original = the variant-gate passer of HUNT_H37.md (MIDCPNIFTY 5m FIB_K786 k4 P20_20)",
                    "MIDCPNIFTY", 5, 4, "FIB_K786", "P20_20"),
    _h37_swing_spec("H37-3", "H37 Elliott wave 3 (MIDCP 5-min, +15/-20)",
                    "original = the variant-gate passer of HUNT_H37.md (MIDCPNIFTY 5m EW3 k2 P15_20; the T60 exit of the "
                    "top-10 table is in the grid)", "MIDCPNIFTY", 5, 2, "EW3", "P15_20"),
    dict(key="H37-6", title="H37 Gann daily time cycles (30..360 calendar days from a daily pivot), reversal",
         source="index",
         why="WF family GTC_D (HUNT_H37.md): " + FIRST + " (all 5 indices, k2, P15_10)",
         orig=_orig(h37_gtc_d, dict(unds=UNDS5, k=2), h37_exit("P15_10"), UNDS5),
         grid=[dict(params=dict(k=4))] + [dict(exits=h37_exit(x)) for x in H37_EXITS if x != "P15_10"]),
    dict(key="H37-9", title="H37 Point & Figure double-top / bottom breakout, 3-box reversal (SENSEX 1-min, 60-min exit)",
         source="index",
         why="SENSEX 1-min PF_DT with the T60 exit (HUNT_H37.md top 10 by t); its box size is not named in the "
             "write-up: the original uses the study's first box (0.1 % of the previous close, as OBUY_GA-1), the "
             "other four boxes are the first grid settings",
         orig=_orig(h37_pf, dict(unds=("SENSEX",), box="b0.1pct", name="PF_DT"), h37_exit("T60"), ("SENSEX",)),
         grid=[dict(params=dict(box=b)) for b in ("b0.2pct", "b0.3pct", "b0.5atr", "b1.0atr")]
         + [dict(params=dict(name="PF_TT"))] + [dict(exits=h37_exit(x)) for x in H37_EXITS if x != "T60"]),
    dict(key="H37-10", title="H37 Market Profile: open outside prior value and go (TPO 30-min, 70% VA)",
         source="index",
         why="WF family MP_OOV (HUNT_H37.md): " + FIRST + " (all 5 indices, 5-min, P15_10)",
         orig=_orig(h37_mp, dict(unds=UNDS5, tf=5, name="MP_OOV"), h37_exit("P15_10"), UNDS5),
         grid=[dict(params=dict(tf=15))] + [dict(exits=h37_exit(x)) for x in H37_EXITS if x != "P15_10"]),
]
