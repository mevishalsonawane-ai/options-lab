"""R145 trading test (PREREG_R145_NN.md section 6). Only for forecasts that passed the design gates.

Signal on the underlying (the coin's perpetual price), traded as the perpetual. EVERY trade has a stop-loss and a
profit lock (CLAUDE.md crypto rule); there is no "no stop" variant. With s = the coin's trailing volatility scaled to
the horizon h (sig_bar x sqrt(h / 5)):

  stop   1.0 s or 2.0 s below entry (in the trade's direction)
  lock   trail: once the gain has reached +1 s, exit when it falls below half its peak
         step:  once +1 s, the stop moves to entry + costs (break-even); once +2 s, to +1 s
  time   exit at the close of the bar h minutes after entry

Bars are 5 minutes. Inside a bar the stop as it stood at the bar's start is checked first against the bar's adverse
extreme (worst case: a bar touching both counts as the stop); a bar opening beyond the stop exits at its open. The lock
then updates from the bar's favourable extreme, and a close below the new floor exits at the close.

Costs (costs.py / config.py): perpetual taker fee each side, slippage per side (1 bp BTC/ETH, 2 bp SOL/BNB/XRP, 5 bp
every other coin), x stress; funding at the last settled rate known at entry for each settlement held.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config

STOPS = (1.0, 2.0)
LOCKS = ("trail", "step")
NOTIONAL_RS = 100_000
OTHER_SLIP_BPS = 5.0


def slip_bps(sym: str) -> float:
    return config.SLIP_BPS.get(sym, OTHER_SLIP_BPS)


def cost_frac(sym: str, stress: float = 1.0) -> float:
    return 2 * (config.FEES["perp"]["taker"] + slip_bps(sym) * 1e-4 * stress)


def simulate(O, H, L, C, k, j, side, s, n_bars, stop_mult, lock, be):
    """Vectorised exits. O/H/L/C: grid x coins arrays; k entry bar (entry at C[k, j]); side +-1; s vol (log units);
    be = break-even level (log) for the step lock. Returns (gross log return, exit bar offset, reason code)."""
    k, j, side, s = (np.asarray(a) for a in (k, j, side, s))
    m = len(k)
    p0 = np.log(C[k, j])
    floor = -stop_mult * s
    peak = np.zeros(m)
    done = np.zeros(m, bool)
    out = np.full(m, np.nan)
    off = np.full(m, n_bars)
    why = np.zeros(m, np.int8)          # 0 time, 1 stop/floor at bar extreme, 2 gap at open, 3 lock at close
    last = np.zeros(m)                  # last valid close gain
    T = len(C)
    for i in range(1, n_bars + 1):
        kk = np.minimum(k + i, T - 1)
        o, h, lo, c = (np.log(A[kk, j]) - p0 for A in (O, H, L, C))
        valid = np.isfinite(c) & (k + i < T)
        go = side * o
        adverse = np.where(side > 0, lo, -h)
        fav = np.where(side > 0, h, -lo)
        gc = side * c
        act = ~done & valid
        gap = act & (go <= floor)
        hit = act & ~gap & (adverse <= floor)
        out[gap], off[gap], why[gap] = go[gap], i, 2
        out[hit], off[hit], why[hit] = floor[hit], i, 1
        done |= gap | hit
        act = ~done & valid
        peak = np.where(act, np.maximum(peak, fav), peak)
        if lock == "trail":
            newf = np.where(peak >= s, np.maximum(floor, 0.5 * peak), floor)
        else:
            newf = floor.copy()
            newf = np.where(peak >= s, np.maximum(newf, be), newf)
            newf = np.where(peak >= 2 * s, np.maximum(newf, s), newf)
        floor = np.where(act, newf, floor)
        cl = act & (gc < floor) & (peak >= s)
        out[cl], off[cl], why[cl] = gc[cl], i, 3
        done |= cl
        last = np.where(valid, gc, last)
    out[~done] = last[~done]
    return out, off, why


def trades(rows: pd.DataFrame, O, H, L, C, h: int, stop_mult: float, lock: str, stress: float = 1.0) -> pd.DataFrame:
    """rows: ts, sym, bar, col, side, sig_bar, fund_rate (bps). Returns one row per trade with net return."""
    n = h // 5
    s = rows.sig_bar.to_numpy(float) * np.sqrt(h / 5)
    cf = np.array([cost_frac(x, stress) for x in rows.sym])
    g, off, why = simulate(O, H, L, C, rows.bar.to_numpy(), rows.col.to_numpy(), rows.side.to_numpy(), s, n,
                           stop_mult, lock, be=np.log1p(cf))
    side = rows.side.to_numpy(float)
    exit_ts = rows.ts.values + (off * 5).astype("timedelta64[m]")
    # settlements in (entry, exit] on the 00/08/16 UTC schedule (costs.settlements_count's rule, with the real exit;
    # coins on a 4-hour schedule are undercounted, a small effect at these holding times)
    step = config.FUNDING_INTERVAL_H * 60
    nset = (np.floor_divide(exit_ts.astype("datetime64[m]").astype(np.int64), step)
            - np.floor_divide(rows.ts.values.astype("datetime64[m]").astype(np.int64), step)).astype(float)
    fund = np.nan_to_num(rows.fund_rate.to_numpy(float)) / 1e4
    net = np.expm1(g) - cf - side * fund * nset
    out = rows[["ts", "sym", "side"]].copy()
    out["exit_ts"] = exit_ts
    out["gross"] = np.expm1(g)
    out["net"] = net
    out["why"] = why
    return out


def no_overlap(sel: pd.DataFrame, h: int) -> pd.DataFrame:
    """One position per coin at a time: drop an entry while the same coin's earlier trade (held to h at most) is open.
    Uses the maximum holding (h), a conservative rule that does not depend on the exit."""
    sel = sel.sort_values("ts")
    keep, busy = [], {}
    for i, (t, sym) in enumerate(zip(sel.ts.values, sel.sym.values)):
        if sym in busy and t < busy[sym]:
            continue
        busy[sym] = t + np.timedelta64(h, "m")
        keep.append(i)
    return sel.iloc[keep]


def in_window(ts, h: int) -> np.ndarray:
    """Boss's crypto window (00:00-09:00 IST): entry at or after 00:00 IST and exit (entry + h) by 09:00 IST."""
    ist = pd.DatetimeIndex(ts) + pd.Timedelta(hours=5, minutes=30)
    mins = ist.hour * 60 + ist.minute
    return np.asarray((mins >= 0) & (mins + h <= 9 * 60))


def summary(t: pd.DataFrame) -> dict:
    if not len(t):
        return dict(trades=0)
    rs = t.net.to_numpy() * NOTIONAL_RS
    eq = np.cumsum(rs[np.argsort(t.exit_ts.values)])
    dd = float(np.min(eq - np.maximum.accumulate(np.r_[0, eq])[1:])) if len(eq) else 0.0
    mon = pd.Series(rs, index=pd.DatetimeIndex(t.ts).to_period("M")).groupby(level=0).sum()
    return dict(trades=int(len(t)), win=float((t.net > 0).mean()), net_pct=float(t.net.mean() * 100),
                net_rs=float(rs.mean()), total_rs=float(rs.sum()), max_dd_rs=dd, worst_month_rs=float(mon.min()),
                worst_month=str(mon.idxmin()), months=int(len(mon)), months_pos=int((mon > 0).sum()),
                exits=t.why.value_counts().sort_index().to_dict())


def twins_p(strategy_mean: float, cand: pd.DataFrame, picks_per_ts: pd.Series, n: int = 1000, seed: int = 0):
    """Random twins: at each entry time, the same number of trades on random coins with random sides, the same exits.
    cand: net per candidate (ts, side) from simulating every available row both ways. All n draws are made at once
    per entry time. Returns (p, twin means)."""
    rng = np.random.default_rng(seed)
    tot = np.zeros(n)
    cnt = 0
    groups = dict(tuple(cand.groupby("ts").net))
    for t, k in picks_per_ts.items():
        v = groups.get(t)
        if v is None or not len(v):
            continue
        v = v.to_numpy()
        k = min(int(k), len(v))
        idx = np.argsort(rng.random((n, len(v))), axis=1)[:, :k]        # k distinct candidates per draw
        tot += v[idx].sum(axis=1)
        cnt += k
    means = tot / max(cnt, 1)
    return float((np.sum(means >= strategy_mean) + 1) / (n + 1)), means
