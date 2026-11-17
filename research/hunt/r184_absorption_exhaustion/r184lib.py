"""R184 pure parts (PREREG.md sections 2-6): the day's own order-flow references, the ABSORPTION and EXHAUSTION signals
on the index future, the option trade with its fixed exits (-15% premium stop, breakeven lock at +10%, index stop, time
stop, day end), random-entry twins, the do-nothing bootstrap and Benjamini-Hochberg.

Signal source: the NIFTY / BANKNIFTY near index FUTURE's own minute bars and tick-rule delta (the underlying). The
traded instrument is the recorded near-ATM option (CE for a call, PE for a put). Option prices only price the trade; the
premium stop and the breakeven lock act on the premium. Every signal at bar r reads bars <= r only.

Reuses R181's minute bars, option series, strike pick, liquidity fills and dated charges (r181lib).
Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import itertools
import math
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

_R181 = Path(__file__).resolve().parents[1] / "r181_orderflow_cases"
if str(_R181) not in sys.path:
    sys.path.insert(0, str(_R181))
import r181lib as R1  # noqa: E402

N = R1.N
THIN_ROWS = 20
MIN_REF = 20
FIRST_SIG, LAST_SIG = 25, 329          # 09:40 .. 14:44
MAX_PER_DAY = 4
PREM_STOP = 0.85
LOCK_AT = 1.10
LOT = R1.LOT
STEP = R1.STEP
FILLS = R1.FILLS

# ------------------------------------------------------------------------------------------------ the grids (PREREG s3/4)
ABS_GRID = [dict(setup="ABS", w=w, k=k, beta=b, confirm=c, T=T)
            for w, k, b, c, T in itertools.product((3, 5), (2, 3), (3, 6), ("none", "turn"), (15, 30))]
EXH_GRID = [dict(setup="EXH", X=X, k=k, beta=b, confirm=c, T=T)
            for X, k, b, c, T in itertools.product((15, 30), (2, 3), (3, 6), ("flip", "flip+close"), (15, 30))]
GRID = {"ABS": ABS_GRID, "EXH": EXH_GRID}


def vid(v: dict) -> str:
    if v["setup"] == "ABS":
        return f"ABS w{v['w']} k{v['k']} b{v['beta']} {v['confirm']} T{v['T']}"
    return f"EXH X{v['X']} k{v['k']} b{v['beta']} {v['confirm']} T{v['T']}"


# ------------------------------------------------------------------------------------------------ references
def thin_mask(rows, c) -> np.ndarray:
    return (np.asarray(rows, float) < THIN_ROWS) | ~np.isfinite(np.asarray(c, float))


def ref1(d, thin) -> np.ndarray:
    """ref1[i] = mean |D| of the day's non-thin bars 0..i-1 (at least 20), else NaN. Reads bars < i only."""
    a = np.where(thin, 0.0, np.abs(np.nan_to_num(np.asarray(d, float))))
    cnt = np.concatenate([[0], np.cumsum(~np.asarray(thin, bool))])[:-1]
    s = np.concatenate([[0.0], np.cumsum(a)])[:-1]
    out = np.full(len(a), np.nan)
    ok = cnt >= MIN_REF
    out[ok] = s[ok] / cnt[ok]
    return out


def window_sums(d, thin, w: int) -> np.ndarray:
    """S_w[j] = D[j-w+1..j] summed when all w bars are non-thin, else NaN."""
    n = len(d)
    d = np.nan_to_num(np.asarray(d, float))
    out = np.full(n, np.nan)
    for j in range(w - 1, n):
        if not np.any(thin[j - w + 1:j + 1]):
            out[j] = d[j - w + 1:j + 1].sum()
    return out


def ref_w(S) -> np.ndarray:
    """ref_w[i] = mean |S_w[j]| over the defined windows ending at j <= i-1 (at least 20), else NaN."""
    ok = np.isfinite(S)
    a = np.where(ok, np.abs(np.nan_to_num(S)), 0.0)
    cnt = np.concatenate([[0], np.cumsum(ok)])[:-1]
    s = np.concatenate([[0.0], np.cumsum(a)])[:-1]
    out = np.full(len(S), np.nan)
    m = cnt >= MIN_REF
    out[m] = s[m] / cnt[m]
    return out


# ------------------------------------------------------------------------------------------------ signals
@dataclass
class Sig:
    r: int            # signal bar (its close is the last data read)
    side: str         # 'L' = buy a CALL, 'S' = buy a PUT
    stop: float       # index stop level (futures points)
    e: float          # futures close at the signal bar


def absorption(B, w: int, k: float, beta: float, confirm: str) -> list:
    """PREREG s3 ABSORPTION. Selling absorbed (heavy negative w-minute delta, the window's low within the band of the
    close before it) -> CALL; buying absorbed -> PUT. 'turn' waits for the next bar's close in the trade's direction."""
    c, h, l = B.c, B.h, B.l
    thin = thin_mask(B.rows, c)
    S = window_sums(B.d, thin, w)
    R = ref_w(S)
    bb = beta / 1e4
    out = []
    for r in range(w, N):
        p = r - w
        if not np.isfinite(S[r]) or not np.isfinite(R[r]) or R[r] <= 0 or not np.isfinite(c[p]):
            continue
        band = bb * c[p]
        side = None
        if S[r] <= -k * R[r] and np.nanmin(l[p + 1:r + 1]) >= c[p] - band:
            side = "L"
        elif S[r] >= k * R[r] and np.nanmax(h[p + 1:r + 1]) <= c[p] + band:
            side = "S"
        if side is None:
            continue
        sr = r
        if confirm == "turn":
            sr = r + 1
            if sr >= N or thin[sr]:
                continue
            if not ((c[sr] > c[r]) if side == "L" else (c[sr] < c[r])):
                continue
        if not FIRST_SIG <= sr <= LAST_SIG:
            continue
        if side == "L":
            stop = float(np.nanmin(l[p + 1:sr + 1]) - band)
        else:
            stop = float(np.nanmax(h[p + 1:sr + 1]) + band)
        out.append(Sig(sr, side, stop, float(c[sr])))
    return _dedupe(out)


def exhaustion(B, X: int, k: float, beta: float, confirm: str) -> list:
    """PREREG s3 EXHAUSTION. A climax bar c (new X-minute low with a selling spike >= k x ref1), then within 3 bars:
    selling dries up (each D > D[c]/2), price stalls (no low beyond the band), delta flips (D[r] > 0) -> CALL.
    Mirror at a high -> PUT. 'flip+close' also needs C[r] beyond C[c]."""
    c, h, l, d = B.c, B.h, B.l, np.nan_to_num(B.d)
    thin = thin_mask(B.rows, c)
    R = ref1(d, thin)
    bb = beta / 1e4
    out = []
    for cb in range(X, N):
        if thin[cb] or not np.isfinite(R[cb]) or R[cb] <= 0:
            continue
        lw, hw = l[cb - X:cb], h[cb - X:cb]
        if np.isfinite(lw).sum() < X / 2:
            continue
        side = None
        if d[cb] < 0 and abs(d[cb]) >= k * R[cb] and l[cb] < np.nanmin(lw):
            side = "L"
        elif d[cb] > 0 and d[cb] >= k * R[cb] and h[cb] > np.nanmax(hw):
            side = "S"
        if side is None:
            continue
        band = bb * c[cb]
        for r in range(cb + 1, min(cb + 3, N - 1) + 1):
            seg = slice(cb + 1, r + 1)
            if np.any(thin[seg]):
                break
            if side == "L":
                if not np.all(d[seg] > d[cb] / 2) or np.nanmin(l[seg]) < l[cb] - band:
                    break
                fired = d[r] > 0 and (confirm == "flip" or c[r] > c[cb])
            else:
                if not np.all(d[seg] < d[cb] / 2) or np.nanmax(h[seg]) > h[cb] + band:
                    break
                fired = d[r] < 0 and (confirm == "flip" or c[r] < c[cb])
            if fired:
                if FIRST_SIG <= r <= LAST_SIG:
                    stop = (np.nanmin(l[cb:r + 1]) - band) if side == "L" else (np.nanmax(h[cb:r + 1]) + band)
                    out.append(Sig(r, side, float(stop), float(c[r])))
                break
    return _dedupe(out)


def _dedupe(sigs: list) -> list:
    """Time order; one signal per bar (a call before a put on the rare tie: the trade-taking keeps only one anyway)."""
    seen, out = set(), []
    for s in sorted(sigs, key=lambda s: (s.r, s.side)):
        if s.r in seen:
            continue
        seen.add(s.r)
        out.append(s)
    return out


def signals(B, v: dict) -> list:
    if v["setup"] == "ABS":
        return absorption(B, v["w"], v["k"], v["beta"], v["confirm"])
    return exhaustion(B, v["X"], v["k"], v["beta"], v["confirm"])


# ------------------------------------------------------------------------------------------------ the option trade
def first_true_sec(sec, mask):
    k = np.flatnonzero(mask)
    return (int(sec[k[0]]), int(k[0])) if len(k) else (None, None)


def exit_path(opt, fut, entry_s: int, cap_s: int, side: str, idx_stop: float, fill: float, be: float):
    """(exit second, reason) of the first exit strictly after the entry second and before cap_s (PREREG s4):
    premium stop (last <= 0.85 x fill), breakeven after the lock (last >= 1.10 x fill, then last <= be at a LATER row),
    index stop on the future's second high / low. None of them: (cap_s, 'time')."""
    a = int(np.searchsorted(opt.sec, entry_s, side="right"))
    z = int(np.searchsorted(opt.sec, cap_s, side="left"))
    osec, olast = opt.sec[a:z], opt.last[a:z]
    good = np.isfinite(olast) & (olast > 0)
    osec, olast = osec[good], olast[good]
    cands = []
    s15, _ = first_true_sec(osec, olast <= PREM_STOP * fill)
    if s15 is not None:
        cands.append((s15, 0, "prem_stop"))
    _, kl = first_true_sec(osec, olast >= LOCK_AT * fill)
    if kl is not None:
        sbe, _ = first_true_sec(osec[kl + 1:], olast[kl + 1:] <= be)
        if sbe is not None:
            cands.append((sbe, 0, "breakeven"))
    fsec, fhi, flo = fut
    fa = int(np.searchsorted(fsec, entry_s, side="right"))
    fz = int(np.searchsorted(fsec, cap_s, side="left"))
    hit = (flo[fa:fz] <= idx_stop) if side == "L" else (fhi[fa:fz] >= idx_stop)
    si, _ = first_true_sec(fsec[fa:fz], hit)
    if si is not None:
        cands.append((si, 0, "index_stop"))
    if not cands:
        return cap_s, "time"
    s, _, why = min(cands)
    return s, why


def trade(opt, fut, entry_s: int, end_s: int, side: str, idx_stop: float, T: int, lot: int, d):
    """One trade, 1 lot. Returns a dict or None (no entry price)."""
    raw_e = opt.px(entry_s, back=30, fwd=10)
    if raw_e is None:
        return None
    ve = opt.vol5_lots(entry_s, lot)
    fill = float(FILLS.buy(np.array([raw_e]), np.array([ve]))[0])
    be = fill + (R1.charge(True, fill, lot, d) + R1.charge(False, fill, lot, d)) / lot
    cap = min(end_s, entry_s + 60 * T)
    why_cap = "time" if entry_s + 60 * T <= end_s else "day_end"
    xs, why = exit_path(opt, fut, entry_s, cap, side, idx_stop, fill, be)
    if why == "time":
        why = why_cap
    if why in ("prem_stop", "breakeven"):
        i = int(np.searchsorted(opt.sec, xs, side="left"))
        raw_x = float(opt.last[i])
    else:
        raw_x = opt.px(xs)
        if raw_x is None:
            raw_x = opt.px_any_after(xs)
    if raw_x is None:
        return None
    vx = opt.vol5_lots(xs, lot)
    st = why in ("prem_stop", "breakeven", "index_stop")
    n1 = R1.net_of(raw_e, raw_x, ve, vx, st, lot, d, 1.0)
    n2 = R1.net_of(raw_e, raw_x, ve, vx, st, lot, d, 2.0)
    qe, qx = opt.quote(entry_s, back=30), opt.quote(xs)
    nba = None
    if qe and qx and np.isfinite(qe[1]) and np.isfinite(qx[0]) and qe[1] > 0 and qx[0] >= 0:
        nba = (qx[0] - qe[1]) * lot - R1.charge(True, qe[1], lot, d) - R1.charge(False, qx[0], lot, d)
    return dict(exit_s=int(xs), why=why, raw_e=raw_e, raw_x=raw_x, fill=round(fill, 2), net=round(n1, 2),
                net2=round(n2, 2), net_ba=None if nba is None else round(float(nba), 2), hold_s=int(xs - entry_s))


def choose_strike(U: dict, r: int, entry_s: int, side: str, step: float):
    """ATM from the index at minute r; the recorded strike (ATM, else nearest within 2 steps, tie in the money)."""
    spot = U["spot"][r]
    if not np.isfinite(spot):
        return None, "no_spot"
    a = R1.atm(spot, step)
    right = "CE" if side == "L" else "PE"
    live = [k for (rt, k), s in U["opts"].items() if rt == right and s.recorded_near(entry_s)]
    k = R1.pick_strike(live, a, step, side)
    if k is None:
        return None, "no_strike"
    return (right, k), "ok"


def take_day(sigs: list, U: dict, t0: int, end_s: int, und: str, d, T: int):
    """Signals of one variant, index and day in time order: one trade at a time, at most 4 a day (PREREG s3)."""
    trades, unpriced = [], {}
    next_ok = -1
    for s in sigs:
        if len(trades) >= MAX_PER_DAY:
            break
        entry = t0 + 60 * (s.r + 1)
        if entry < next_ok:
            continue
        key, why = choose_strike(U, s.r, entry, s.side, STEP[und])
        if key is None:
            unpriced[why] = unpriced.get(why, 0) + 1
            continue
        res = trade(U["opts"][key], U["fut"], entry, end_s, s.side, s.stop, T, LOT[und], d)
        if res is None:
            unpriced["no_price"] = unpriced.get("no_price", 0) + 1
            continue
        res.update(r=int(s.r), side=s.side, e=s.e, stop=round(s.stop, 2), dist=round(abs(s.e - s.stop), 2),
                   strike=key[1], entry_s=int(entry))
        trades.append(res)
        next_ok = res["exit_s"]
    return trades, unpriced


def twin_trade(U: dict, t0: int, end_s: int, und: str, d, r: int, side: str, dist: float, T: int):
    """The twin of a rule trade on another day: same bar r, side, index-stop distance and exits."""
    c = U["bars"].c[r]
    if not np.isfinite(c):
        return None
    entry = t0 + 60 * (r + 1)
    if entry >= end_s:
        return None
    key, _ = choose_strike(U, r, entry, side, STEP[und])
    if key is None:
        return None
    stop = c - dist if side == "L" else c + dist
    return trade(U["opts"][key], U["fut"], entry, end_s, side, stop, T, LOT[und], d)


# ------------------------------------------------------------------------------------------------ statistics
def summary(trades: list, days: list | None = None) -> dict:
    if not trades:
        return dict(n=0, win=None, net=0.0, net2=0.0, net_ba=None, mean=None, mean2=None, avg_win=None, avg_loss=None,
                    sd=None, worst_day=None, best_day=None, by_side={}, by_exit={})
    x = np.array([t["net"] for t in trades], float)
    x2 = np.array([t["net2"] for t in trades], float)
    ba = [t["net_ba"] for t in trades if t.get("net_ba") is not None]
    w, lo = x[x > 0], x[x <= 0]
    per = {}
    for t in trades:
        per[t["day"]] = per.get(t["day"], 0.0) + t["net"]
    by_side = {s: dict(n=sum(t["side"] == s for t in trades),
                       net=round(sum(t["net"] for t in trades if t["side"] == s), 0)) for s in ("L", "S")}
    by_exit = {}
    for t in trades:
        e = by_exit.setdefault(t["why"], dict(n=0, net=0.0))
        e["n"] += 1
        e["net"] = round(e["net"] + t["net"], 2)
    return dict(n=int(len(x)), win=round(float((x > 0).mean()), 3), net=round(float(x.sum()), 0),
                net2=round(float(x2.sum()), 0), net_ba=round(float(sum(ba)), 0) if ba else None, n_ba=len(ba),
                mean=round(float(x.mean()), 1), mean2=round(float(x2.mean()), 1),
                avg_win=round(float(w.mean()), 0) if len(w) else None,
                avg_loss=round(float(lo.mean()), 0) if len(lo) else None,
                sd=round(float(x.std(ddof=1)), 1) if len(x) > 1 else None,
                worst_day=round(min(per.values()), 0), best_day=round(max(per.values()), 0), days_traded=len(per),
                by_side=by_side, by_exit=by_exit)


def twin_test(rule_nets: list, twin_options: list, rng, draws: int = 1000) -> dict:
    """T1. twin_options[i] = the priced twin nets of rule trade i (one per other day). Each draw picks one at random per
    trade (trades with no priced twin are left out of that set). p = (1 + sets with mean >= the rule's mean) / (1 + draws)."""
    if len(rule_nets) < 3:
        return dict(p=1.0, mean=None, p10=None, p90=None, n_sets=0)
    obs = float(np.mean(rule_nets))
    means = []
    for _ in range(draws):
        v = [opts[rng.integers(len(opts))] for opts in twin_options if opts]
        if v:
            means.append(float(np.mean(v)))
    if not means:
        return dict(p=1.0, mean=None, p10=None, p90=None, n_sets=0)
    m = np.array(means)
    return dict(p=float((1 + np.sum(m >= obs - 1e-9)) / (1 + draws)), mean=round(float(m.mean()), 1),
                p10=round(float(np.percentile(m, 10)), 1), p90=round(float(np.percentile(m, 90)), 1),
                n_sets=len(means), win=round(float(np.mean([x > 0 for o in twin_options for x in o])), 3)
                if any(twin_options) else None)


def zero_test(day_sums: list, rng, draws: int = 10000, n_trades: int | None = None) -> float:
    """T2. Day-block bootstrap of the per-day net sums (days with no trade = 0): share of resampled means <= 0."""
    a = np.asarray(day_sums, float)
    if (n_trades is not None and n_trades < 3) or not len(a):
        return 1.0
    idx = rng.integers(len(a), size=(draws, len(a)))
    m = a[idx].mean(axis=1)
    return float((1 + np.sum(m <= 0)) / (1 + draws))


def power(sd, n) -> dict:
    """Smallest mean edge a trade detectable with 80% power: one-sided 5% (2.49 sd / sqrt n) and at the BH rank-1 level
    for m = 4,512 (z 3.85: 4.69 sd / sqrt n)."""
    if not sd or not n or n < 2:
        return dict(mde_5pct=None, mde_bh=None)
    return dict(mde_5pct=round(2.49 * sd / math.sqrt(n), 0), mde_bh=round((3.85 + 0.84) * sd / math.sqrt(n), 0))


def bh_with_prior(p, m_total: int):
    return R1.bh_with_prior(p, m_total)


def select(rows: list, min_trades: int = 12) -> dict:
    """PREREG s5: rows = [{vid, i (grid order), n, net}]. Highest net among n >= 12 (tie: more trades, then grid order);
    none with 12: the most trades (tie: higher net, then grid order)."""
    ok = [r for r in rows if r["n"] >= min_trades]
    if ok:
        return min(ok, key=lambda r: (-r["net"], -r["n"], r["i"]))
    return min(rows, key=lambda r: (-r["n"], -r["net"], r["i"]))
