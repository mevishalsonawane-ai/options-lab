"""R140 Amendment F (PREREG.md, commit 0243f22): Boss's Pine v6 "5-Step Liquidity Sweep POI" indicator, ported as
written and backtested on XAUUSD (information only; not tradable via Zerodha).

Signal source: XAUUSD spot candles (the underlying). The script gives SL (the signal candle's low / high) and TP (the
last swing high / low); a 12-hour cap is added, and F2 / F4 / F5 add the house 60% profit lock after +1R.

    python -m research.hunt.r140_gold_0600.r140p design
    python -m research.hunt.r140_gold_0600.r140p holdout     # 1 Apr - 30 Oct 2026, one read, passing rules only
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime

import numpy as np
import pandas as pd

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140g as G

PIVOT, ATR_LEN, ZONE_ATR, RR_MIN, HTF_EMA = 3, 14, 0.50, 1.5, 50
CAP_MIN = 12 * 60
LOCK_R, KEEP = 1.0, 0.60
RULES = {"F1": (15, False, False), "F2": (15, True, False), "F3": (5, False, False), "F4": (5, True, False),
         "F5": (15, True, True)}                       # (chart minutes, lock, 06:00-08:00 IST entries only)
MIN_TRADES = {"F1": 100, "F2": 100, "F3": 100, "F4": 100, "F5": 30}
HOLD = (date(2026, 4, 1), date(2026, 10, 30))
N_TWIN_DAYS = 40


# ---------------------------------------------------------------------------------------------- candles
def bars(df: pd.DataFrame, minutes: int) -> pd.DataFrame:
    r = df.resample(f"{minutes}min", label="left", closed="left")
    b = pd.DataFrame({"open": r.open.first(), "high": r.high.max(), "low": r.low.min(), "close": r.close.last()})
    return b.dropna()


def rma_atr(b: pd.DataFrame, n: int = ATR_LEN) -> np.ndarray:
    """Pine ta.atr: Wilder's RMA of the true range (seeded with the SMA of the first n values)."""
    h, l, c = b.high.values, b.low.values, b.close.values
    pc = np.concatenate([[np.nan], c[:-1]])
    tr = np.where(np.isnan(pc), h - l, np.maximum(h - l, np.maximum(np.abs(h - pc), np.abs(l - pc))))
    out = np.full(len(tr), np.nan)
    if len(tr) >= n:
        out[n - 1] = tr[:n].mean()
        for i in range(n, len(tr)):
            out[i] = (out[i - 1] * (n - 1) + tr[i]) / n
    return out


def htf_trend(df: pd.DataFrame, b: pd.DataFrame, minutes: int) -> np.ndarray:
    """+1 / -1 / 0 at each chart candle's close: the last COMPLETED 60m close vs its EMA 50 (lookahead_off history)."""
    h = bars(df, 60)
    ema = h.close.ewm(span=HTF_EMA, adjust=False).mean()
    trend = np.sign(h.close - ema).values
    h_close_time = (h.index + pd.Timedelta(minutes=60)).values
    c_close_time = (b.index + pd.Timedelta(minutes=minutes)).values
    k = np.searchsorted(h_close_time, c_close_time, side="right") - 1
    out = np.where(k >= HTF_EMA - 1, trend[np.clip(k, 0, None)], 0.0)
    return out


def signals(df: pd.DataFrame, minutes: int) -> list[dict]:
    """The script's validBuy / validSell, candle by candle, in order (state carried like Pine's var)."""
    b = bars(df, minutes)
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    atr = rma_atr(b)
    tr = htf_trend(df, b, minutes)
    L = PIVOT
    last_hi = last_lo = np.nan
    prot_lo = prot_hi = np.nan
    out = []
    for i in range(len(b)):
        j = i - L
        if j - L >= 0:
            win_h = h[j - L:i + 1]
            if all(h[j] > x for k, x in enumerate(win_h) if k != L):
                last_hi = h[j]
            win_l = l[j - L:i + 1]
            if all(l[j] < x for k, x in enumerate(win_l) if k != L):
                last_lo = l[j]
        if not np.isfinite(atr[i]) or i == 0:
            continue
        z = atr[i] * ZONE_ATR
        in_dem = np.isfinite(last_lo) and l[i] <= last_lo + z and h[i] >= last_lo - z
        in_sup = np.isfinite(last_hi) and h[i] >= last_hi - z and l[i] <= last_hi + z
        bull_sw = np.isfinite(last_lo) and l[i] < last_lo and c[i] > last_lo
        bear_sw = np.isfinite(last_hi) and h[i] > last_hi and c[i] < last_hi
        bull_cf = c[i] > o[i] and c[i] > h[i - 1]
        bear_cf = c[i] < o[i] and c[i] < l[i - 1]
        buy = tr[i] > 0 and in_dem and bull_sw and bull_cf
        sell = tr[i] < 0 and in_sup and bear_sw and bear_cf
        if buy:
            prot_lo = l[i]
        if sell:
            prot_hi = h[i]
        t_close = b.index[i] + pd.Timedelta(minutes=minutes)
        if buy and np.isfinite(last_hi):
            risk, reward = c[i] - prot_lo, last_hi - c[i]
            if risk > 0 and reward > 0 and reward / risk >= RR_MIN:
                out.append(dict(t=t_close, side=1, sl=float(prot_lo), tp=float(last_hi), close=float(c[i])))
        if sell and np.isfinite(last_lo):
            risk, reward = prot_hi - c[i], c[i] - last_lo
            if risk > 0 and reward > 0 and reward / risk >= RR_MIN:
                out.append(dict(t=t_close, side=-1, sl=float(prot_hi), tp=float(last_lo), close=float(c[i])))
    return out


# ---------------------------------------------------------------------------------------------- trades
def sim_fixed(X: R.Xau, i_in: int, i_cap: int, side: int, sl: float, tp: float, lock: bool, lock_r: float = LOCK_R):
    """Entry at the open of minute i_in; SL / TP prices fixed; optional 60% lock after +1R; time exit at i_cap's open.
    A minute touching both the SL (or lock) and the TP counts as the SL (or lock)."""
    e = X.o[i_in]
    risk = side * (e - sl)
    if risk <= 0 or side * (tp - e) <= 0:
        return None
    peak, lk = 0.0, None
    for j in range(i_in, i_cap):
        hi, lo, op = X.h[j], X.l[j], X.o[j]
        adv, fav = (lo, hi) if side > 0 else (hi, lo)
        lvl = lk if lk is not None else sl
        if side * (adv - lvl) <= 0:
            return (min(op, lvl) if side > 0 else max(op, lvl)), ("lock" if lk is not None else "sl"), j
        if side * (fav - tp) >= 0:
            return (max(op, tp) if side > 0 else min(op, tp)), "tp", j
        if lock:
            peak = max(peak, side * (fav - e))
            if peak >= lock_r * risk:
                new = e + side * KEEP * peak
                lk = new if lk is None else (max(lk, new) if side > 0 else min(lk, new))
                if side * (X.c[j] - lk) <= 0:
                    return X.c[j], "lock", j
    return X.o[i_cap], "time", i_cap


def ist_date(t_utc: pd.Timestamp) -> date:
    return (t_utc + pd.Timedelta(hours=5, minutes=30)).date()


def run_trades(X, sigs, lock, window_only, period, lock_r: float = LOCK_R):
    out, busy_until = [], None
    for s in sigs:
        d = ist_date(s["t"])
        if not R.in_p(d, period):
            continue
        if window_only:
            m = (s["t"].hour * 60 + s["t"].minute)
            if not (R.W_START_UTC <= m < R.W_END_UTC):
                continue
        if busy_until is not None and s["t"] < busy_until:
            continue
        i_in = X.ix_at_or_after(s["t"])
        if i_in >= len(X.ts) or X.ts[i_in] - np.datetime64(s["t"]) > np.timedelta64(5, "m"):
            continue
        i_cap = X.ix_at_or_after(s["t"] + pd.Timedelta(minutes=CAP_MIN))
        if i_cap >= len(X.ts):
            continue
        r = sim_fixed(X, i_in, i_cap, s["side"], s["sl"], s["tp"], lock, lock_r)
        if r is None:
            continue
        px, why, j_exit = r
        e = X.o[i_in]
        g = s["side"] * (px - e) * R.XAU_LOT_OZ
        out.append(dict(day=d, t_in=s["t"], side=s["side"], gross=g, net=g - R.XAU_COST, net2=g - 2 * R.XAU_COST,
                        why=why, entry=float(e), stop_usd=float(abs(e - s["sl"])), tp_usd=float(abs(s["tp"] - e))))
        busy_until = pd.Timestamp(X.ts[j_exit]) + pd.Timedelta(minutes=1)     # flat after the exit minute
    return out


def twins(X, trades, period, lock, rng, n_sets=R.N_TWINS, lock_r: float = LOCK_R):
    """For each trade, N_TWIN_DAYS random other weekdays at the same clock time, both sides, the same $ SL / TP."""
    if not trades:
        return 1.0, 0.0
    days = pd.date_range(period[0], period[1], freq="B")
    L = np.full((len(trades), N_TWIN_DAYS), np.nan)
    S = np.full((len(trades), N_TWIN_DAYS), np.nan)
    for a, t in enumerate(trades):
        clock = t["t_in"] - t["t_in"].normalize()
        picks = rng.choice(len(days), size=N_TWIN_DAYS * 2, replace=False)
        k = 0
        for p in picks:
            if k >= N_TWIN_DAYS:
                break
            tt = days[p] + clock
            i_in = X.ix_at_or_after(tt)
            if i_in >= len(X.ts) or X.ts[i_in] - np.datetime64(tt) > np.timedelta64(5, "m"):
                continue
            i_cap = X.ix_at_or_after(tt + pd.Timedelta(minutes=CAP_MIN))
            if i_cap >= len(X.ts):
                continue
            e = X.o[i_in]
            vals = []
            for side, M in ((1, L), (-1, S)):
                r = sim_fixed(X, i_in, i_cap, side, e - side * t["stop_usd"], e + side * t["tp_usd"], lock, lock_r)
                vals.append(np.nan if r is None else side * (r[0] - e) * R.XAU_LOT_OZ - R.XAU_COST)
            if np.isfinite(vals).all():
                L[a, k], S[a, k] = vals
                k += 1
    share = float(np.mean([t["side"] > 0 for t in trades]))
    total = float(sum(t["net"] for t in trades))
    tot = np.empty(n_sets)
    n = len(trades)
    for s in range(n_sets):
        col = rng.integers(0, N_TWIN_DAYS, size=n)
        lg = rng.random(n) < share
        lv, sv = L[np.arange(n), col], S[np.arange(n), col]
        tot[s] = np.nansum(np.where(lg, lv, sv))
    return (1 + int((tot >= total).sum())) / (n_sets + 1), float(np.median(tot))


def main(mode):
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    X = R.Xau(R.load_xau())
    sig = {m: signals(X.df, m) for m in (15, 5)}
    if mode == "design":
        rng = np.random.default_rng(R.SEED + 6)
        prior = {}
        for fn in ("design.json", "amendA_design.json", "amendE_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text())["variants"].items()})
        for fn in ("amendB_design.json", "amendD_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text()).items()})
        res, p = {}, {}
        for k, (m, lock, win) in RULES.items():
            ts = run_trades(X, sig[m], lock, win, R.XAU_DESIGN)
            s = R.summarise(k, ts, R.XAU_FOLDS, False)
            s["twin_p"], s["twin_median_total"] = twins(X, ts, R.XAU_DESIGN, lock, rng)
            s["sizing_1000"] = G.report(ts) if ts else {}
            s["signals_total"] = len([x for x in sig[m] if R.in_p(ist_date(x["t"]), R.XAU_DESIGN)])
            s["exits"] = {w: sum(t["why"] == w for t in ts) for w in ("tp", "sl", "lock", "time")}
            s["avg_rr"] = float(np.mean([t["tp_usd"] / t["stop_usd"] for t in ts])) if ts else 0.0
            s["avg_stop_usd"] = float(np.mean([t["stop_usd"] for t in ts])) if ts else 0.0
            p[k] = s["twin_p"]
            res[k] = s
        q = R.bh({**prior, **p})                                 # BH over 95
        for k, s in res.items():
            s["q"] = q[k]
            g = R.gates(s, s["twin_p"], q[k], "A4")
            g["trades"] = s["n"] >= MIN_TRADES[k]
            g["all"] = all(v for kk, v in g.items() if kk != "all")
            s["gates"] = g
        (R.RESULTS / "amendF_design.json").write_text(json.dumps(R._clean(res), indent=1))
        (R.RESULTS / "amendF_design.md").write_text("# R140 Amendment F design results (Boss's Pine script)\n\n"
                                                    + R.table_md(res) + "\n")
        print(R.table_md(res))
        for k, s in res.items():
            print(k, s["signals_total"], s["exits"], round(s["avg_rr"], 2), round(s["avg_stop_usd"], 2),
                  json.dumps(R._clean(s["sizing_1000"].get("5x", {}))))
    elif mode == "holdout":
        lock_f = R.RESULTS / "amendF_holdout.lock"
        if lock_f.exists():
            raise SystemExit("Amendment F holdout already read: " + lock_f.read_text())
        dz = json.loads((R.RESULTS / "amendF_design.json").read_text())
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock_f.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment F rule passed the design gates; the holdout is not read")
            return
        res = {}
        for k in passing:
            m, lock, win = RULES[k]
            ts = run_trades(X, sig[m], lock, win, HOLD)
            res[k] = R.summarise(k, ts, (HOLD,), False) | {"sizing_1000": G.report(ts)}
        (R.RESULTS / "amendF_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(json.dumps(R._clean(res), indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
