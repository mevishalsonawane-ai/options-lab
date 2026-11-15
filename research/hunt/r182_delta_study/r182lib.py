"""R182 pure parts (PREREG.md sections 3-6): minute bars and delta features from the 1-second futures rows, forward
returns, the day-block bootstrap and the within-day circular-shift null, the arms' paper trades rebuilt from their orders,
the delta features at each trade's entry second, the random-skip twins and Benjamini-Hochberg with the prior tests.

Signal source: the index near FUTURE's order flow (tick-rule delta, the app's own 1-second recorder). The arms trade the
option; their outcomes are the option's real paper fills. Every feature at a minute close (or at an entry second) reads
data strictly before it. Plain-English notes, 15 Nov 2026.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from datetime import date

import numpy as np

from research.obuy.costs import Costs, _dn

INDICES = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
N = 375                      # minute bars 09:15..15:29
IST = 19800
SECS_0915 = 9 * 3600 + 15 * 60
THIN_ROWS = 20               # a minute with fewer recorded seconds is "thin" (quality count only)
SCALE_MIN = 15               # minutes of the day needed before a delta is scaled (point in time)
HORIZONS = (1, 5, 15, 30)
WARM, LAST_SIG = 15, N - 16  # Part A sample: minute closes 09:30..15:14 (the 15-minute return stays in the day)
COSTS = Costs("dated")
ABSORB_SHARE, ABSORB_MULT, ABSORB_MIN, ABSORB_RMIN, ABSORB_RFRAC = 0.6, 2.0, 5, 0.10, 0.0002  # TickZone's constants
TYP_MIN = 10                 # earlier 5-minute windows needed for the "typical" 5-minute delta (variant V1)
M_PRIOR = 4066               # tests counted before R182 (after R181)


def day_epoch_0915(d: date) -> int:
    """Epoch seconds of 09:15:00 IST on day d."""
    return (d - date(1970, 1, 1)).days * 86400 - IST + SECS_0915


def epoch_of(day: str, hms: str) -> int:
    h, m, s = (int(x) for x in hms.split(":"))
    return day_epoch_0915(date.fromisoformat(day)) - SECS_0915 + h * 3600 + m * 60 + s


# ------------------------------------------------------------------------------------------------ minute bars
@dataclass
class Bars:
    c: np.ndarray        # last traded price at the minute's end (NaN: no recorded second)
    d: np.ndarray        # tick-rule delta (buy - sell), 0 where no second
    dq: np.ndarray       # quote-rule delta
    v: np.ndarray
    rows: np.ndarray     # recorded seconds
    obi: np.ndarray      # mean 5-level depth imbalance
    absorb: np.ndarray = field(default=None)  # absorption direction: +1 selling absorbed (bullish), -1 buying absorbed


def minute_bars(sec: dict, t0: int, n: int = N) -> Bars:
    """Minute i holds the seconds t0 + 60 i .. t0 + 60 i + 59."""
    es = np.asarray(sec["epoch_sec"], np.int64)
    i = (es - t0) // 60
    ok = (i >= 0) & (i < n)
    i = i[ok]
    last = np.asarray(sec["last"], float)[ok]
    buy, sell = np.asarray(sec["buy_vol"], float)[ok], np.asarray(sec["sell_vol"], float)[ok]
    qb, qs = np.asarray(sec["q_buy_vol"], float)[ok], np.asarray(sec["q_sell_vol"], float)[ok]
    vol = np.asarray(sec["vol"], float)[ok]
    dimb = np.asarray(sec["depth_imb"], float)[ok]
    c = np.full(n, np.nan)
    good = np.isfinite(last) & (last > 0)
    # rows are in time order: the last good price of each minute wins
    c[i[good]] = last[good]
    d = np.bincount(i, weights=np.nan_to_num(buy - sell), minlength=n)[:n]
    dq = np.bincount(i, weights=np.nan_to_num(qb - qs), minlength=n)[:n]
    v = np.bincount(i, weights=np.nan_to_num(vol), minlength=n)[:n]
    rows = np.bincount(i, minlength=n)[:n].astype(float)
    fin = np.isfinite(dimb)
    s = np.bincount(i[fin], weights=dimb[fin], minlength=n)[:n]
    k = np.bincount(i[fin], minlength=n)[:n]
    obi = np.where(k > 0, s / np.maximum(k, 1), np.nan)
    return Bars(c, d, dq, v, rows, obi)


def absorb_minutes(fp: dict, t0: int, n: int = N) -> np.ndarray:
    """TickZone's absorption rule on the footprint (>= 60% of a >= 2x-average minute at one price, range <= max(0.10,
    0.0002 x price), >= 5 earlier minutes), with its direction: buying absorbed at that price = -1, selling absorbed = +1."""
    out = np.zeros(n)
    prior_v, prior_n = 0.0, 0
    for i in range(n):
        x = fp.get(t0 + 60 * i)
        if x is None:
            continue
        px, b, s = x
        vol = float(np.sum(b) + np.sum(s))
        if vol <= 0:
            continue
        if prior_n >= ABSORB_MIN and vol >= ABSORB_MULT * prior_v / prior_n:
            pv = b + s
            k = int(np.argmax(pv))
            rng = float(np.max(px) - np.min(px))
            if pv[k] / vol >= ABSORB_SHARE and rng <= max(ABSORB_RMIN, ABSORB_RFRAC * float(px[k])) + 1e-9:
                out[i] = -float(np.sign(b[k] - s[k]))
        prior_v += vol
        prior_n += 1
    return out


def scale_series(d: np.ndarray, rows: np.ndarray) -> np.ndarray:
    """s[i] = mean |delta| of the day's minutes before i with >= THIN_ROWS seconds (NaN until SCALE_MIN of them)."""
    n = len(d)
    ok = rows >= THIN_ROWS
    a = np.where(ok, np.abs(d), 0.0)
    cs = np.concatenate([[0.0], np.cumsum(a)])[:n]
    cn = np.concatenate([[0], np.cumsum(ok)])[:n]
    with np.errstate(invalid="ignore", divide="ignore"):
        s = np.where(cn >= SCALE_MIN, cs / np.maximum(cn, 1), np.nan)
    s[s <= 0] = np.nan
    return s


def roll_sum(x: np.ndarray, w: int) -> np.ndarray:
    cs = np.concatenate([[0.0], np.cumsum(x)])
    out = np.full(len(x), np.nan)
    out[w - 1:] = cs[w:] - cs[:-w]
    return out


def features(b: Bars) -> dict:
    """Each minute close's delta features (point in time: minute i's close reads minutes <= i only)."""
    n = len(b.c)
    s = scale_series(b.d, b.rows)
    has = (b.rows > 0).astype(float)
    cov5, cov15 = roll_sum(has, 5), roll_sum(has, 15)
    d5, d15 = roll_sum(b.d, 5), roll_sum(b.d, 15)
    d5 = np.where(cov5 >= 4, d5, np.nan)
    d15 = np.where(cov15 >= 12, d15, np.nan)
    cvd = np.cumsum(b.d)
    slope = np.full(n, np.nan)
    t = np.arange(15) - 7.0
    for i in range(14, n):
        if cov15[i] >= 12:
            slope[i] = float(np.dot(t, cvd[i - 14:i + 1])) / float(np.dot(t, t))
    ret15 = np.full(n, np.nan)
    ret15[15:] = np.log(b.c[15:] / b.c[:-15])
    sd, sr = np.sign(d15), np.sign(ret15)
    div = np.where(np.isfinite(sd) & np.isfinite(sr) & (sd != 0) & (sr != 0) & (sd == -sr), sd, 0.0)
    div[~(np.isfinite(d15) & np.isfinite(ret15))] = np.nan
    ab = b.absorb if b.absorb is not None else np.zeros(n)
    with np.errstate(invalid="ignore", divide="ignore"):
        out = dict(D1=np.where(b.rows > 0, b.d / s, np.nan), D5=d5 / s, D15=d15 / s, CVDslope15=slope / s, Div15=div,
                   OBI=np.where(b.rows > 0, b.obi, np.nan), Absorb5=roll_sum(ab, 5), DQ1=np.where(b.rows > 0, b.dq / s, np.nan))
    return out


def fwd_returns(c: np.ndarray, h: int) -> np.ndarray:
    out = np.full(len(c), np.nan)
    with np.errstate(invalid="ignore", divide="ignore"):
        out[:-h] = np.log(c[h:] / c[:-h])
    return out


def same_minute_return(c: np.ndarray) -> np.ndarray:
    out = np.full(len(c), np.nan)
    with np.errstate(invalid="ignore", divide="ignore"):
        out[1:] = np.log(c[1:] / c[:-1])
    return out


# ------------------------------------------------------------------------------------------------ statistics
def rank(x: np.ndarray) -> np.ndarray:
    """Average ranks of the finite values (NaN stays NaN)."""
    out = np.full(len(x), np.nan)
    f = np.isfinite(x)
    v = x[f]
    o = np.argsort(v, kind="mergesort")
    r = np.empty(len(v))
    r[o] = np.arange(1, len(v) + 1)
    # ties: average
    sv = v[o]
    i = 0
    while i < len(sv):
        j = i
        while j + 1 < len(sv) and sv[j + 1] == sv[i]:
            j += 1
        if j > i:
            r[o[i:j + 1]] = (i + j + 2) / 2.0
        i = j + 1
    out[f] = r
    return out


def pearson(x: np.ndarray, y: np.ndarray) -> float:
    f = np.isfinite(x) & np.isfinite(y)
    if f.sum() < 3:
        return float("nan")
    a, b = x[f] - x[f].mean(), y[f] - y[f].mean()
    den = math.sqrt(float(np.dot(a, a)) * float(np.dot(b, b)))
    return float(np.dot(a, b)) / den if den > 0 else float("nan")


def spearman_days(xs: list, ys: list) -> float:
    """Spearman over the pooled days: Pearson of ranks taken over each pooled series' finite values."""
    x, y = np.concatenate(xs), np.concatenate(ys)
    f = np.isfinite(x) & np.isfinite(y)
    return pearson(rank(np.where(f, x, np.nan)), rank(np.where(f, y, np.nan)))


def day_bootstrap(xs: list, ys: list, stat=spearman_days, draws: int = 2000, seed: int = 0) -> dict:
    """Resample whole days with replacement: the 95% interval and the bootstrap standard error of stat."""
    rng = np.random.default_rng(seed)
    k = len(xs)
    obs = stat(xs, ys)
    bs = np.empty(draws)
    for j in range(draws):
        idx = rng.integers(0, k, k)
        bs[j] = stat([xs[i] for i in idx], [ys[i] for i in idx])
    bs = bs[np.isfinite(bs)]
    if len(bs) < 2:              # a constant feature (e.g. no absorption at all on an index): no correlation exists
        return dict(r=obs, lo=float("nan"), hi=float("nan"), se=float("nan"), z=float("nan"), p=float("nan"), n_days=k,
                    n=int(sum(int((np.isfinite(a) & np.isfinite(b)).sum()) for a, b in zip(xs, ys))))
    se = float(np.std(bs, ddof=1)) if len(bs) > 1 else float("nan")
    z = obs / se if se and se > 0 else float("nan")
    p = float(math.erfc(abs(z) / math.sqrt(2))) if np.isfinite(z) else float("nan")
    return dict(r=obs, lo=float(np.percentile(bs, 2.5)), hi=float(np.percentile(bs, 97.5)), se=se, z=z, p=p, n_days=k,
                n=int(sum(int((np.isfinite(a) & np.isfinite(b)).sum()) for a, b in zip(xs, ys))))


def shift_null(xs: list, ys: list, draws: int = 10000, seed: int = 1, min_shift: int = 30) -> float:
    """Two-sided p of the pooled rank correlation against within-day circular shifts of the feature (each day its own
    random shift of min_shift..n-min_shift minutes): keeps each series' autocorrelation, breaks the timing."""
    rng = np.random.default_rng(seed)
    # ranks once over the pooled finite values (the statistic is the Pearson of these ranks on the pairs present)
    rx = rank(np.concatenate(xs))
    ry = rank(np.concatenate(ys))
    bounds = np.cumsum([0] + [len(a) for a in xs])
    rxs = [rx[bounds[i]:bounds[i + 1]] for i in range(len(xs))]
    rys = [ry[bounds[i]:bounds[i + 1]] for i in range(len(ys))]
    obs = pearson(np.concatenate(rxs), np.concatenate(rys))
    hit = 0
    for _ in range(draws):
        sx = [np.roll(a, int(rng.integers(min_shift, len(a) - min_shift))) for a in rxs]
        r = pearson(np.concatenate(sx), np.concatenate(rys))
        if np.isfinite(r) and abs(r) >= abs(obs) - 1e-12:
            hit += 1
    return (hit + 1) / (draws + 1)


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


# ------------------------------------------------------------------------------------------------ the arms' trades
SYM = re.compile(r"^(MIDCPNIFTY|BANKNIFTY|FINNIFTY|NIFTY|SENSEX|BANKEX)(\d{2}[A-Z]{3}\d{2})(\d+(?:\.\d+)?)(CE|PE)$")


def arm_of(source: str) -> str | None:
    s = source or ""
    if s.startswith("R51 Donchian 20"):
        return "R51"
    if s.startswith("R72 Breadth Donchian"):
        return "R72"
    if s.startswith("Liquidity"):
        return "Liquidity"
    return None


def is_squareoff(source: str) -> bool:
    return (source or "").lower().startswith("auto square-off")


def parse_symbol(sym: str):
    m = SYM.match(sym or "")
    if not m:
        return None
    return dict(und=m.group(1), expiry=m.group(2), strike=float(m.group(3)), right=m.group(4))


def rebuild_trades(orders: list[dict]) -> tuple[list[dict], dict]:
    """Each filled paper BUY is a trade; its arm's filled SELLs on the same symbol close it first-in first-out (partial
    sells allowed), then the 15:15 auto square-off closes what is left (first-in first-out across the arms). A trade not
    closed by the day's end, a SELL with nothing open, and an unreadable symbol are counted and left out."""
    stats = dict(unclosed=0, orphan_sell_qty=0, bad_symbol=0, buys=0)
    by_day: dict[str, list] = {}
    for k, o in enumerate(orders):
        if o["filled"] <= 0 or o["status"] != "complete" or o["avg"] <= 0:
            continue
        by_day.setdefault(o["day"], []).append((o["at"], 0 if o["side"] == "BUY" else 1, k, o))
    trades = []
    for day in sorted(by_day):
        ev = sorted(by_day[day])
        open_: dict[str, list] = {}           # symbol -> open trades (oldest first)
        for at, _, _, o in ev:
            arm = arm_of(o["source"])
            if o["side"] == "BUY":
                if arm is None:
                    continue
                stats["buys"] += 1
                ps = parse_symbol(o["symbol"])
                if ps is None:
                    stats["bad_symbol"] += 1
                    continue
                t = dict(arm=arm, day=day, symbol=o["symbol"], und=ps["und"], right=ps["right"], strike=ps["strike"],
                         entry_at=at, entry_px=o["avg"], qty=o["filled"], left=o["filled"], exits=[])
                open_.setdefault(o["symbol"], []).append(t)
                trades.append(t)
            else:
                q = o["filled"]
                for t in open_.get(o["symbol"], []):
                    if q <= 0:
                        break
                    if t["left"] <= 0 or (arm is not None and t["arm"] != arm):
                        continue
                    take = min(q, t["left"])
                    t["exits"].append((at, o["avg"], take))
                    t["left"] -= take
                    q -= take
                stats["orphan_sell_qty"] += q
    done = []
    for t in trades:
        if t["left"] > 0:
            stats["unclosed"] += 1
            continue
        done.append(finish(t))
    return done, stats


def finish(t: dict) -> dict:
    q = sum(x[2] for x in t["exits"])
    ex = sum(x[1] * x[2] for x in t["exits"]) / q
    dn = _dn(date.fromisoformat(t["day"]))
    bse = t["und"] in ("SENSEX", "BANKEX")
    ch = float(COSTS.charge(True, np.array([t["entry_px"]]), np.array([t["qty"]]), np.array([dn]), bse)[0])
    for at, px, qq in t["exits"]:
        ch += float(COSTS.charge(False, np.array([px]), np.array([qq]), np.array([dn]), bse)[0])
    gross = sum((px - t["entry_px"]) * qq for _, px, qq in t["exits"])
    out = {k: v for k, v in t.items() if k not in ("left",)}
    out.update(exit_px=ex, exit_at=max(x[0] for x in t["exits"]), points=ex - t["entry_px"],
               pct=(ex - t["entry_px"]) / t["entry_px"] * 100, gross=gross, charges=ch, net=gross - ch,
               side=1 if t["right"] == "CE" else -1)
    out["win"] = out["net"] > 0
    return out


# ------------------------------------------------------------------------------------------------ entry-second features
def entry_features(sec: dict, fp_abs: np.ndarray | None, t0: int, s_entry: int, side: int) -> dict:
    """The index future's flow strictly before the entry second s_entry (the window s_entry - w .. s_entry - 1), aligned to
    the trade's side (+1 CE, -1 PE): positive = the flow agrees with the trade."""
    es = np.asarray(sec["epoch_sec"], np.int64)
    d = np.nan_to_num(np.asarray(sec["buy_vol"], float) - np.asarray(sec["sell_vol"], float))
    out = {}
    for w, key in ((300, "d5"), (900, "d15")):
        lo, hi = np.searchsorted(es, max(s_entry - w, t0), "left"), np.searchsorted(es, s_entry, "left")
        out[key + "_rows"] = int(hi - lo)
        out[key] = float(np.sum(d[lo:hi])) if hi > lo else float("nan")
    out["a5"] = side * out["d5"] if np.isfinite(out["d5"]) else float("nan")
    out["a15"] = side * out["d15"] if np.isfinite(out["d15"]) else float("nan")
    # typical |5-minute delta| of the day before the entry minute: the 5-minute windows ending at the earlier minute ends
    m_entry = (s_entry - t0) // 60
    ends = [t0 + 60 * (k + 1) for k in range(4, int(m_entry))]
    vals = []
    for e in ends:
        lo, hi = np.searchsorted(es, e - 300, "left"), np.searchsorted(es, e, "left")
        if hi > lo:
            vals.append(abs(float(np.sum(d[lo:hi]))))
    out["typ5"] = float(np.median(vals)) if len(vals) >= TYP_MIN else float("nan")
    # absorption against the side in the last 5 completed minutes (minutes whose end is <= s_entry)
    against = float("nan")       # no footprint (FINNIFTY, MIDCPNIFTY): no absorption label
    if fp_abs is not None:
        against = 0
        last_done = (s_entry - t0) // 60 - 1
        for k in range(int(last_done) - 4, int(last_done) + 1):
            if 0 <= k < len(fp_abs) and fp_abs[k] == -side:
                against += 1
    out["absorb_against5"] = against
    return out


def labels(f: dict | None) -> dict:
    """The pre-registered skip rules (PREREG section 5). None = no label (the trade is kept)."""
    if f is None or not np.isfinite(f.get("a5", float("nan"))) or f.get("d5_rows", 0) == 0:
        return dict(P=None, V1=None, V2=None, V3=None, group="no label")
    a5 = f["a5"]
    if a5 == 0:      # no futures volume signed in the 5 minutes: no opinion, the trade is kept and is not a twin candidate
        return dict(P=None, V1=None, V2=None, V3=None, group="zero")
    P = bool(a5 < 0)
    V1 = bool(np.isfinite(f["typ5"]) and a5 < -f["typ5"]) if np.isfinite(f["typ5"]) else None
    V2 = bool(f["a15"] < 0) if np.isfinite(f["a15"]) and f.get("d15_rows", 0) > 0 else None
    V3 = bool(f["absorb_against5"] > 0) if np.isfinite(f["absorb_against5"]) else None
    group = "agrees" if a5 > 0 else "disagrees"
    return dict(P=P, V1=V1, V2=V2, V3=V3, group=group)


def summary(nets) -> dict:
    x = np.asarray(nets, float)
    if len(x) == 0:
        return dict(n=0, win=None, net=0.0, avg=None)
    return dict(n=int(len(x)), win=float((x > 0).mean() * 100), net=float(x.sum()), avg=float(x.mean()))


def random_skip_p(nets_labelled, skip_mask, draws: int = 10000, seed: int = 7) -> dict:
    """Twins: skip the same number of labelled trades at random. p = share of draws whose kept net is >= the rule's kept
    net (one-sided: the rule helps), with +1 smoothing."""
    x = np.asarray(nets_labelled, float)
    m = np.asarray(skip_mask, bool)
    k = int(m.sum())
    n = len(x)
    if n == 0 or k == 0 or k == n:
        return dict(k=k, n=n, p=None, twin_kept_mean=None)
    rng = np.random.default_rng(seed)
    kept_obs = float(x[~m].sum())
    tot = float(x.sum())
    sk = np.empty(draws)
    for j in range(draws):
        sk[j] = x[rng.choice(n, k, replace=False)].sum()
    kept = tot - sk
    p = (int((kept >= kept_obs - 1e-9).sum()) + 1) / (draws + 1)
    return dict(k=k, n=n, p=float(p), kept_obs=kept_obs, twin_kept_mean=float(kept.mean()),
                twin_kept_lo=float(np.percentile(kept, 2.5)), twin_kept_hi=float(np.percentile(kept, 97.5)))
