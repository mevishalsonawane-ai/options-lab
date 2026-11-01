"""R144c: IraThinker's Flow arm (`FlowRules.kt` + `FlowArm.kt`), ported line for line, on lake 5-minute bars.

R110 priced only the ORB alert (S1) on the 2024-26 release data, without the Flow's Time Machine stage, FINNIFTY or the
arm's own exits, so the arm is ported here:
- indices NIFTY, BANKNIFTY, FINNIFTY; the index's own expiry day skipped; one trade per index a day (3 a day at most);
- 5-minute bars folded from the lake's minutes (a bar only when all 5 minutes exist; a day whose minutes have a gap
  before the decision is never decided on, as FlowArm);
- `scan` (09:15-09:30 range, the FIRST 5-minute close beyond it), `liteScore`, `timeMachine` (3,000 Monte Carlo paths
  with Java's `java.util.Random(19)`, plus the analog walk of every kept past session), `fullScore`, `alertWorthy`
  (score > 65, >= 10 points to 1R, range <= 0.8%), `vixConfirms` (India VIX's own first break UP no later than the
  index's break DOWN: puts only), `odds.passes`, `budget` (R:R >= 0.9, qty > 0, 09:30-15:00);
- history: the 60 sessions before the day (FlowRules.SESSIONS_KEPT) - the best case; the phone keeps what it recorded;
- judged when the break bar has closed (the first pass after it); the ATM option of the nearest expiry (the WEEK series)
  at the index's price then, 1 lot, bought at the open of the minute after the break bar closes;
- exits as FlowArm: the 20% premium stop resting (SL-M: a minute low at or under it sells at min(open, stop)); the index
  stop (range midpoint) / 2R index target on the minute's index high / low (sold at that minute's option close);
  flat at 15:15 (that minute's open). In a minute the resting stop first, then the index levels.
Costs: R98's charges per order (Rs 20 + GST, exchange, stamp, STT 0.15% on the sell) and R1's 0.16% half-spread a side.
The paper capital for the risk gate: Rs 5,00,000 (1% = Rs 5,000 at risk); a qty of 0 never happened at that size.

Signal source: the index (its 5-minute opening-range break) confirmed by India VIX's own break (CLAUDE.md default).
"""
from __future__ import annotations

import math
from datetime import date

import numpy as np
from numba import njit

import lake as LK

INDICES = ("NIFTY", "BANKNIFTY", "FINNIFTY")
LOTS = {
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
    "FINNIFTY": [("2000-01-01", 40), ("2024-07-24", 25), ("2024-11-28", 65), ("2025-12-31", 60)],
}
MIN_SCORE, MIN_TARGET_POINTS, MAX_RANGE_WIDTH_PCT = 65, 10.0, 0.8
PATHS, MIN_RETURNS, SESSIONS_KEPT, MAX_LOSS_PCT = 3000, 100, 60, 1.0
CAPITAL = 500_000.0
PREMIUM_STOP = 0.20
HALF_SPREAD = 0.0016
TICK = 0.05
BROKER, GST, STT, STAMP, EXCH = 20.0, 0.18, 0.0015, 0.00003, 0.000355
OR_END_ROW = LK.row(9, 30)
SQ_ROW = LK.row(15, 15)


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


# ------------------------------------------------------------------------------------------------ bars
def fold5(a: np.ndarray, upto_row: int):
    """5-minute bars (start row, O, H, L, C) of the minutes 0..upto_row; (bars, gap) with gap True if a minute is missing."""
    gap = bool(np.isnan(a[: upto_row + 1, 3]).any())
    bars = []
    for s in range(0, upto_row - 3, 5):
        g = a[s:s + 5]
        if np.isnan(g[:, 3]).any() or np.isnan(g[:, 0]).any():
            continue
        bars.append((s, g[0, 0], np.nanmax(g[:, 1]), np.nanmin(g[:, 2]), g[4, 3]))
    return bars, gap


class Scan:
    def __init__(self):
        self.ltp = math.nan
        self.or_hi = self.or_lo = math.nan
        self.setup = "NONE"
        self.entry = self.stop = self.target = math.nan
        self.brk = -1                   # the break bar's start row
        self.rsi = self.dma20 = math.nan
        self.body = self.thrust = self.run = math.nan
        self.engulf = False
        self.vwap = 0
        self.struct = 0

    @property
    def up(self):
        return self.setup == "ORB_UP"

    def has_plan(self):
        return not (math.isnan(self.entry) or math.isnan(self.stop))


def _or(bars):
    hi, lo, n = -math.inf, math.inf, 0
    for b in bars:
        if b[0] < OR_END_ROW:
            hi, lo, n = max(hi, b[2]), min(lo, b[3]), n + 1
    return hi, lo, n


def _plan(s: Scan, bars):
    pos = -1
    for p, b in enumerate(bars):
        if b[0] < OR_END_ROW:
            continue
        if b[4] > s.or_hi:
            s.setup, s.entry, s.brk, pos = "ORB_UP", b[4], b[0], p
            break
        if b[4] < s.or_lo:
            s.setup, s.entry, s.brk, pos = "ORB_DOWN", b[4], b[0], p
            break
    if pos >= 0:
        mid = (s.or_hi + s.or_lo) / 2
        risk = abs(s.entry - mid)
        s.stop = mid
        s.target = s.entry + risk if s.up else s.entry - risk
        pa(s, bars, pos)
        s.run = run_move(bars, pos, s.up)
    return pos


def scan(bars, daily_closes: list, prev_close: float) -> Scan:
    """FlowRules.scan on today's closed 5-minute bars; daily_closes: past sessions' closes, oldest first."""
    s = Scan()
    if not bars:
        return s
    s.ltp = bars[-1][4]
    hi, lo, n = _or(bars)
    if n >= 2 and hi > lo:
        s.or_hi, s.or_lo = hi, lo
        _plan(s, bars)
    closes = list(daily_closes) + [s.ltp]
    if len(closes) > 20:
        cl = [c for c in closes if c == c and c > 0][-40:]
        if len(cl) > 20:
            s.dma20 = sum(cl[-20:]) / 20
            s.rsi = rsi14(cl)
            if s.setup == "NONE":
                day_pct = (s.ltp / prev_close - 1) * 100 if prev_close == prev_close and prev_close > 0 else math.nan
                if s.rsi == s.rsi and s.rsi < 35 and day_pct == day_pct and day_pct > 0.5:
                    s.setup = "OVERSOLD_BOUNCE"
                else:
                    s.setup = "ABOVE_20DMA" if s.ltp > s.dma20 else "BELOW_20DMA"
    return s


def rsi14(cl):
    if len(cl) < 15:
        return math.nan
    gain = loss = 0.0
    for i in range(len(cl) - 14, len(cl)):
        d = cl[i] - cl[i - 1]
        if d > 0:
            gain += d
        else:
            loss -= d
    if loss == 0.0:
        return 100.0
    rs = (gain / 14) / (loss / 14)
    return 100 - 100 / (1 + rs)


def run_move(bars, pos, up):
    if pos <= 0 or pos >= len(bars):
        return math.nan
    ce, cf = bars[pos][4], bars[max(0, pos - 6)][4]
    if not cf > 0:
        return math.nan
    return (ce / cf - 1) * 100 * (1 if up else -1)


def pa(s: Scan, bars, pos):
    _, o, h, l, c = bars[pos]
    if not h > l:
        return
    s.body = ((c - o) if s.up else (o - c)) / (h - l)
    if pos > 0:
        j = bars[pos - 1]
        s.engulf = (c > j[1] and o < j[4] and c > o) if s.up else (c < j[1] and o > j[4] and c < o)
        c0 = bars[max(0, pos - 3)][4]
        if c0 > 0:
            s.thrust = (c / c0 - 1) * 100 * (1 if s.up else -1)
    s.vwap = 0                        # index bars carry no volume (Zerodha's index candles neither): paVwap stays 0
    s.struct = day_struct(bars, pos)


def day_struct(bars, pos):
    ph, pl = [], []
    for p in range(1, pos):
        h0, h1, h2 = bars[p - 1][2], bars[p][2], bars[p + 1][2]
        l0, l1, l2 = bars[p - 1][3], bars[p][3], bars[p + 1][3]
        if h1 >= h0 and h1 >= h2:
            ph.append(h1)
        if l1 <= l0 and l1 <= l2:
            pl.append(l1)
    if len(ph) < 2 or len(pl) < 2:
        return 0
    if ph[-1] > ph[-2] and pl[-1] > pl[-2]:
        return 1
    if ph[-1] < ph[-2] and pl[-1] < pl[-2]:
        return -1
    return 0


def hour_of(row: int) -> float:
    m = row + 9 * 60 + 15
    return m // 60 + (m % 60) / 60.0


def lite_score(s: Scan) -> int:
    if s.setup == "OVERSOLD_BOUNCE":
        return int(max(35.0, min(68.0, 75 - s.rsi)))
    if not s.has_plan():
        return {"ABOVE_20DMA": 45, "BELOW_20DMA": 35}.get(s.setup, 25)
    up = s.up
    score = 25
    width = s.or_hi - s.or_lo
    pen = (s.entry - s.or_hi) if up else (s.or_lo - s.entry)
    ratio = pen / width if width > 0 else 0.0
    score += int(max(0.0, min(20.0, 5 + ratio * 20)))
    if s.dma20 == s.dma20 and s.ltp == s.ltp:
        score += 18 if ((s.ltp > s.dma20) if up else (s.ltp < s.dma20)) else 4
    else:
        score += 10
    if s.rsi == s.rsi:
        r = s.rsi if up else 100 - s.rsi
        score += 15 if 50.0 <= r <= 75.0 else 3 if r > 85 else 8 if r > 75 else 9
    else:
        score += 8
    if s.ltp == s.ltp and s.ltp > 0 and width > 0:
        w = width / s.ltp * 100
        score += 12 if 0.2 <= w <= 1.0 else 5 if w < 0.2 else 7
    h = hour_of(s.brk)
    score += 8 if h < 10.5 else 5 if h < 13.0 else 1
    return max(0, min(100, score))


def feat(s: Scan, score: int):
    return dict(width=(s.or_hi - s.or_lo) / s.entry * 100 if s.entry > 0 else math.nan, score=score, body=s.body,
                thrust=s.thrust, run=s.run, hour=hour_of(s.brk) if s.brk >= 0 else math.nan, struct=s.struct,
                vwap=s.vwap, engulf=1 if s.engulf else 0)


def feat_distance(a, b) -> float:
    terms, n = [], 0
    for k, sc in (("width", 1.0), ("score", 25.0), ("body", 0.4), ("thrust", 1.0), ("run", 1.5), ("hour", 2.0)):
        x, y = float(a[k]), float(b[k])
        if x == x and y == y:
            terms.append(abs(x - y) / sc)
            n += 1
    terms.append(0.0 if a["struct"] == b["struct"] else 0.7 if 0 in (a["struct"], b["struct"]) else 1.4)
    terms.append(0.0 if a["vwap"] == b["vwap"] else 0.5 if 0 in (a["vwap"], b["vwap"]) else 1.0)
    terms.append(0.0 if a["engulf"] == b["engulf"] else 0.5)
    n += 3
    if n < 5:
        return math.inf
    return math.sqrt(sum(t * t for t in terms))


# ------------------------------------------------------------------------------------------------ Java's Random
MASK48 = (1 << 48) - 1
MULT = 0x5DEECE66D


@njit(cache=True)
def _mc(rets, entry, stop, target, up, bars_left, paths, seed0):
    """The Monte Carlo of FlowRules.timeMachine with java.util.Random(seed) (nextInt(bound), exactly)."""
    seed = (seed0 ^ 0x5DEECE66D) & ((1 << 48) - 1)
    n = rets.shape[0]
    tf = sf = nn = 0
    for _p in range(paths):
        price = entry
        outcome = 0
        for _b in range(bars_left):
            # nextInt(n)
            if (n & -n) == n:
                seed = (seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
                r = (n * (seed >> 17)) >> 31
            else:
                while True:
                    seed = (seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
                    bits = seed >> 17
                    val = bits % n
                    if bits - val + (n - 1) < (1 << 31):
                        break
                r = val
            price *= math.exp(rets[r])
            if (price >= target) if up else (price <= target):
                outcome = 1
                break
            if (price <= stop) if up else (price >= stop):
                outcome = -1
                break
        if outcome > 0:
            tf += 1
        elif outcome < 0:
            sf += 1
        else:
            nn += 1
    return tf, sf, nn


def java_next_ints(seed0: int, bound: int, k: int) -> list:
    """Pure-Python java.util.Random(seed0).nextInt(bound) x k (for the tests)."""
    seed = (seed0 ^ MULT) & MASK48
    out = []
    for _ in range(k):
        while True:
            seed = (seed * MULT + 0xB) & MASK48
            bits = seed >> 17
            if (bound & -bound) == bound:
                out.append((bound * bits) >> 31)
                break
            val = bits % bound
            if bits - val + (bound - 1) < (1 << 31):
                out.append(val)
                break
    return out


def scan_day_only(bars):
    if len(bars) < 10:
        return None
    s = Scan()
    hi, lo, n = _or(bars)
    if n < 2 or hi <= lo:
        return None
    s.or_hi, s.or_lo = hi, lo
    pos = _plan(s, bars)
    if pos < 0:
        return None
    return s


def walk_to_close(bars, past: Scan) -> int:
    for b in bars:
        if b[0] < past.brk:
            continue
        if past.up:
            if b[3] <= past.stop:
                return -1
            if b[2] >= past.target:
                return 1
        else:
            if b[2] >= past.stop:
                return -1
            if b[3] <= past.target:
                return 1
    return 0


def time_machine(hist: list, today_bars, s: Scan, paths: int = PATHS) -> dict:
    """hist: [bars of each kept past session], oldest first."""
    o = dict(ran=False, tf=0.0, sf=0.0, a_fires=0, a_t=0, a_s=0, returns=0)
    if not s.has_plan() or abs(s.entry - s.stop) <= 0:
        return o
    for past_bars in hist:
        past = scan_day_only(past_bars)
        if past is None or past.up != s.up:
            continue
        o["a_fires"] += 1
        res = walk_to_close(past_bars, past)
        if res > 0:
            o["a_t"] += 1
        elif res < 0:
            o["a_s"] += 1
    rets = []
    for day_bars in hist + [today_bars]:
        prev = math.nan
        for b in day_bars:
            c = b[4]
            if c == c and prev == prev and prev > 0:
                rets.append(math.log(c / prev))
            if c == c:
                prev = c
    o["returns"] = len(rets)
    if len(rets) < MIN_RETURNS:
        return o
    trig_min = s.brk + 9 * 60 + 15
    bars_left = max(1, (15 * 60 + 30 - trig_min) * 60 // 300)
    tf, sf, nn = _mc(np.array(rets, dtype=np.float64), s.entry, s.stop, s.target, s.up, bars_left, paths, 19)
    o.update(tf=tf * 100.0 / paths, sf=sf * 100.0 / paths, ran=True)
    return o


def full_score(s: Scan, o: dict) -> int:
    lite = lite_score(s)
    if not o["ran"]:
        return lite
    tm = o["tf"] * 0.7
    dec = o["a_t"] + o["a_s"]
    if o["a_fires"] >= 3:
        tm += o["a_t"] * 30.0 / dec if dec > 0 else 12.0
    elif o["a_fires"] > 0:
        tm += o["a_t"] * 15.0 / dec + 5 if dec > 0 else 10.0
    else:
        tm += 12.0
    return max(0, min(100, int(math.floor(0.5 * lite + 0.5 * tm + 0.5))))


def range_narrow(s: Scan) -> bool:
    return s.entry > 0 and s.or_hi > s.or_lo and (s.or_hi - s.or_lo) / s.entry * 100.0 <= MAX_RANGE_WIDTH_PCT


# ------------------------------------------------------------------------------------------------ the run
def judge_day(und, d, ix, vix, hist, closes, prev_close):
    """The flow's verdict on day d at the first pass after the index's break bar closed: a dict, or None."""
    a = ix.get(d)
    v = vix.get(d)
    if a is None or v is None:
        return None
    bars_all, _ = fold5(a, LK.N - 1)
    first = scan_day_only(bars_all) if bars_all else None
    if first is None or first.setup != "ORB_DOWN":
        return None                            # no break, or a breakout (IraThinker's gate takes breakdowns only)
    now_row = first.brk + 5                    # the break bar has closed: the first pass
    if now_row >= LK.row(15, 0):
        return None                            # the entry window 09:30-15:00
    bars, gap = fold5(a, now_row - 1)
    if gap:
        return None                            # FlowArm never decides on a day with a missing minute
    s = scan(bars, closes, prev_close)
    if s.setup != "ORB_DOWN" or s.brk != first.brk:
        return None
    if abs(s.target - s.entry) < MIN_TARGET_POINTS or not range_narrow(s):
        return dict(stage="IRASCORE", s=s)
    vb, _ = fold5(v, now_row - 1)
    vs = scan(vb, [], math.nan)
    if not (vs.setup == "ORB_UP" and vs.brk >= 0 and vs.brk + 5 <= now_row and vs.brk <= s.brk):
        return dict(stage="VIX", s=s)
    odds = time_machine(hist, bars, s)
    score = full_score(s, odds) if odds["ran"] else lite_score(s)
    if not score > MIN_SCORE:
        return dict(stage="IRASCORE", s=s, score=score, odds=odds)
    if not (odds["ran"] and odds["tf"] >= odds["sf"]):
        return dict(stage="TIME_MACHINE", s=s, score=score, odds=odds)
    risk = abs(s.entry - s.stop)
    qty = int(CAPITAL * MAX_LOSS_PCT / 100.0 / risk) if risk > 0 else 0
    rr = abs(s.target - s.entry) / risk if risk > 0 else 0
    if not (qty > 0 and rr >= 0.9):
        return dict(stage="RISK", s=s, score=score, odds=odds)
    return dict(stage="TAKE", s=s, score=score, odds=odds, now_row=now_row)


def charges(buy: float, sell: float) -> float:
    c = BROKER * (1 + GST) + buy * EXCH * (1 + GST) + buy * STAMP
    if sell > 0:
        c += BROKER * (1 + GST) + sell * EXCH * (1 + GST) + sell * STT
    return c


def trade(und, d, s: Scan, now_row, ix_day, ch, stop_frac: float | None = PREMIUM_STOP) -> dict | None:
    """The paper trade: ATM PE of the nearest expiry at the index's price, bought at the open of minute now_row.
    stop_frac: the premium stop under the fill (the arm's 20%); None = no premium stop (part C's C4c)."""
    if ch is None:
        return None
    spot = ix_day[now_row - 1, 3]
    k = float(round(spot / LK.STEP[und]) * LK.STEP[und])
    a = ch.get((k, "P"))
    if a is None:
        return None
    er = next((r for r in range(now_row, min(now_row + 3, LK.N)) if a[r, LK.O] == a[r, LK.O]), None)
    if er is None:
        return None
    e = a[er, LK.O] * (1 + HALF_SPREAD)
    stop = max(TICK, math.floor(e * (1 - stop_frac) / TICK + 1e-9) * TICK) if stop_frac is not None else -1.0
    tgt = s.entry - 2 * abs(s.stop - s.entry)
    xr, xp, why = None, None, None
    best = e
    for j in range(er, LK.N):
        if j >= SQ_ROW:
            xr = j
            xp = a[j, LK.O] if a[j, LK.O] == a[j, LK.O] else _last(a[:, LK.C], j)
            why = "15:15"
            break
        if j > er and a[j, LK.L] == a[j, LK.L] and a[j, LK.L] <= stop:
            xr, xp, why = j, min(a[j, LK.O], stop) if a[j, LK.O] == a[j, LK.O] else stop, "premium stop"
            break
        if a[j, LK.C] == a[j, LK.C]:
            best = max(best, a[j, LK.H])
        hi, lo = ix_day[j, 1], ix_day[j, 2]
        if hi == hi and hi >= s.stop:
            xr, xp, why = j, _last(a[:, LK.C], j), "index stop"
            break
        if lo == lo and lo <= tgt:
            xr, xp, why = j, _last(a[:, LK.C], j), "index target"
            break
    if xr is None:
        xr, xp, why = LK.N - 1, _last(a[:, LK.C], LK.N - 1), "15:15"
    q = lot(und, d)
    x = xp * (1 - HALF_SPREAD)
    net = (x - e) * q - charges(e * q, x * q)
    return dict(arm="FLOW", und=und, day=d, side="P", strike=k, sig_row=int(s.brk), entry_row=int(er), entry_px=float(e),
                exit_row=int(xr), exit_kind=why, net=float(net), reach=float(best / e), lot=q)


def _last(c, j):
    ok = np.nonzero(~np.isnan(c[: j + 1]))[0]
    return float(c[ok[-1]]) if len(ok) else 0.0


def build(lo=date(2022, 9, 1), hi=date(2026, 10, 19), stop_fracs=None):
    """The arm's trades and its stage counts; with stop_fracs, ({stop: trades}, stages) for the same entries."""
    multi = stop_fracs is not None
    stop_fracs = list(stop_fracs) if multi else [PREMIUM_STOP]
    res = {sf: [] for sf in stop_fracs}
    vix = LK.index_minutes("INDIAVIX")
    out, stages = [], {}
    for und in INDICES:
        ix = LK.index_minutes(und)
        dl = LK.daily(und)
        exp = set(LK.expiry_days(und))
        days = sorted(d for d in ix if lo <= d <= hi)
        allsess = sorted(ix)
        bars_cache = {}
        cand = []
        for d in days:
            if d in exp:
                continue
            i = allsess.index(d)
            past = allsess[max(0, i - SESSIONS_KEPT):i]
            hist = []
            for p in past:
                if p not in bars_cache:
                    bars_cache[p] = fold5(ix[p], LK.N - 1)[0]
                hist.append(bars_cache[p])
            closes = list(dl.close[dl.index < d].values[-60:])
            prev = closes[-1] if closes else math.nan
            v = judge_day(und, d, ix, vix, hist, closes, prev)
            if v is None:
                continue
            stages[(und, v["stage"])] = stages.get((und, v["stage"]), 0) + 1
            if v["stage"] == "TAKE":
                cand.append((d, v))
        ch = LK.chains(und, "WEEK", {d for d, _ in cand})
        for d, v in cand:
            for sf in stop_fracs:
                t = trade(und, d, v["s"], v["now_row"], ix[d], ch.get(d), sf)
                if t is not None:
                    t.update(score=v["score"], tf=v["odds"]["tf"], sf=v["odds"]["sf"], idx_entry=v["s"].entry,
                             idx_stop=v["s"].stop, or_width=(v["s"].or_hi - v["s"].or_lo) / v["s"].entry * 100)
                    res[sf].append(t)
        print(und, "candidates", len(cand), "trades", sum(t["und"] == und for t in res[stop_fracs[0]]), flush=True)
    st = {f"{a}|{b}": n for (a, b), n in stages.items()}
    res = {sf: sorted(v, key=lambda t: (t["day"], t["und"])) for sf, v in res.items()}
    return (res, st) if multi else (res[PREMIUM_STOP], st)
