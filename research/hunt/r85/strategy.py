"""R85 Part 2 harness: BUY 1 lot of the 1-ITM call / put on a candle signal read on the MCX future (PREREG section 4).

A signal = (instrument, grid minute j of the signal candle's LAST minute, side +1 call / -1 put, hold minutes,
underlying stop level stopF). The trade:
- strike: 1-ITM of the nearest monthly option, read at minute j (R53 Market.strike: offset -1 call / +1 put);
- entry: the open of the first PRINTED minute of that contract in (j, j + 2]; none -> no trade; never on an option
  expiry day;
- stop: premium -X% (on the printed minute's low; a gap through fills at the worse of open and level), or the future's
  minute close back through stopF (sold at the option's price that minute, R53 price_exit);
- profit lock: once the premium's high reaches +L%, the stop rises to entry + L/2, then trails at peak - L/2 (in
  premium %, peak = highest printed high since entry). The stop is checked before the peak updates within a minute
  (conservative);
- time exit: entry + hold minutes, or 10 minutes before the session close (R53 day_cut), whichever first.
Costs (PREREG section 2/4): McxCosts option charges (R53 common.mcx_opt_charges) + half-spread on the premium at entry
and exit: CRUDEOIL 0.30% / 0.60%, NATURALGAS 0.45% / 0.90%, GOLDM / SILVERM 0.30% / 0.60% (from / before 17:00 IST).
The futures move (side x points x mini units) is kept as a diagnostic only.
"""
from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r53 import common as C53
from research.hunt.r53.mcx.sim import price_exit, strike_offset

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
DESIGN = (date(2025, 8, 5), date(2026, 3, 31))
FOLDS = [(date(2025, 11, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 2, 28)),
         (date(2026, 3, 1), date(2026, 3, 31))]
FINAL = (date(2026, 4, 1), date(2026, 12, 31))       # to the newest lake day
N_TWINS = 500
SEED = 20261019
STOP_MONTH = -13000.0

# signal instrument -> option book traded, units per premium point of 1 traded lot (mini where it exists)
OPTION_BOOK = {"GOLD": ("GOLDM", 10.0), "GOLDM": ("GOLDM", 10.0), "SILVER": ("SILVERM", 5.0),
               "SILVERM": ("SILVERM", 5.0), "CRUDEOIL": ("CRUDEOIL", 10.0), "NATURALGAS": ("NATURALGAS", 250.0)}
HALF = {"CRUDEOIL": (0.0030, 0.0060), "NATURALGAS": (0.0045, 0.0090), "GOLDM": (0.0030, 0.0060),
        "SILVERM": (0.0030, 0.0060)}
FUT_UNITS = {"GOLD": 10.0, "GOLDM": 10.0, "SILVER": 5.0, "SILVERM": 5.0, "CRUDEOIL": 10.0, "NATURALGAS": 250.0}


def half_spread(book: str, tod: int) -> float:
    a, b = HALF[book]
    return a if tod >= 17 * 60 else b


def opt_cost(book: str, buy_rs: float, sell_rs: float, tod_in: int, tod_out: int) -> float:
    return (C53.mcx_opt_charges(buy_rs, sell_rs) + half_spread(book, tod_in) * buy_rs
            + half_spread(book, tod_out) * sell_rs)


def sim_buy(om, fm, j: int, side: int, hold: int, X: float, L: float, stopF: float = float("nan"),
            book: str = "GOLDM", units: float = 10.0, fut_units: float = 10.0, wait: int = 2):
    """One option buy. om: the option book's Market (strikes, legs); fm: the signal instrument's Market (F, ts). When
    the two are the same instrument om is fm. Minutes are matched by timestamp. Returns a trade dict or None."""
    ts_j = fm.ts[j]
    jo = int(np.searchsorted(om.ts, ts_j))
    if jo >= len(om.ts) or om.ts[jo] != ts_j:
        return None
    d = om.day[jo]
    if om.is_exp[jo]:
        return None
    cut = om.day_cut[d]
    exit_i = cut
    e = jo + 1
    if e >= len(om.ts) or om.day[e] != d or exit_i <= e:
        return None
    cp = 0 if side > 0 else 1
    k = om.strike(cp, strike_offset(side, "itm1"), jo)
    if not np.isfinite(k):
        return None
    lg = om.leg(d, cp, k)
    if lg is None:
        return None
    ii, V = lg
    a = np.searchsorted(ii, e)
    if a >= len(ii) or ii[a] > e + wait or ii[a] > exit_i:
        return None
    i_in = int(ii[a])
    fill = float(V[a, 0])
    if not fill > 0:
        return None
    exit_i = min(cut, i_in + hold)
    z = np.searchsorted(ii, exit_i, side="right")
    pi, PV = ii[a:z], V[a:z]
    stop = fill * (1 - X)
    peak = fill
    locked = False
    t_opt, px_opt, why_opt = None, None, None
    for q in range(len(pi)):
        o_, h_, l_ = PV[q, 0], PV[q, 1], PV[q, 2]
        if l_ <= stop:
            t_opt = int(pi[q])
            px_opt = stop if q == 0 else min(o_, stop)
            why_opt = "lock" if locked else "opt_stop"
            break
        peak = max(peak, h_)
        if not locked and peak >= fill * (1 + L):
            locked = True
        if locked:
            stop = max(stop, fill * (1 + L / 2), peak - fill * L / 2)
    # underlying stop on the future's minute close (signal instrument), mapped to the option grid by time
    t_fut = None
    if np.isfinite(stopF):
        i0 = int(np.searchsorted(fm.ts, om.ts[i_in]))
        i1 = int(np.searchsorted(fm.ts, om.ts[exit_i], side="right"))
        F = fm.F[i0:i1]
        hit = np.nonzero(side * (F - stopF) <= 0)[0]
        if len(hit):
            t_fut = int(np.searchsorted(om.ts, fm.ts[i0 + hit[0]]))
    ev = [(exit_i, 2, "time")]
    if t_opt is not None:
        ev.append((t_opt, 0, why_opt))
    if t_fut is not None:
        ev.append((t_fut, 1, "fut_stop"))
    t, _, why = min(ev)
    stale = False
    if why in ("opt_stop", "lock"):
        px = float(px_opt)
    else:
        px, t, stale = price_exit(om, ii, V, t)
        if not np.isfinite(px):
            return None
    t = min(t, len(om.ts) - 1)
    buy, sell = fill * units, px * units
    cost = opt_cost(book, buy, sell, int(om.tod[i_in]), int(om.tod[t]))
    # futures diagnostic: the signal future from entry to exit minute
    fi = int(np.searchsorted(fm.ts, om.ts[i_in]))
    fx = min(int(np.searchsorted(fm.ts, om.ts[t], side="right")) - 1, len(fm.ts) - 1)
    fut_pts = side * (fm.F[fx] - fm.F[fi]) if fx >= fi else float("nan")
    return dict(entry_ts=pd.Timestamp(om.ts[i_in]), exit_ts=pd.Timestamp(om.ts[t]), side=int(side), strike=float(k),
                fill=fill, exit=px, why=why, stale=bool(stale), gross=sell - buy, cost=cost, net=sell - buy - cost,
                fut_gross=float(fut_pts * fut_units), hold_min=int(t - i_in))


# ------------------------------------------------------------------------------------------------ summaries
def day_of(ts) -> date:
    return pd.Timestamp(ts).date()


def in_period(trades, a: date, b: date):
    return [t for t in trades if a <= day_of(t["entry_ts"]) <= b]


def summary(trades) -> dict:
    s = C53.summary([dict(t) for t in trades])
    if trades:
        df = pd.DataFrame(trades)
        mon = df.groupby(df.entry_ts.map(lambda x: pd.Timestamp(x).strftime("%Y-%m"))).net.sum()
        s.update(gross=float(df.gross.sum()), cost=float(df.cost.sum()), fut_gross=float(df.fut_gross.sum()),
                 months_below_13k=int((mon < STOP_MONTH).sum()), worst_month=float(mon.min()),
                 top5_removed=float(np.sort(df.net.values)[:-5].sum()) if len(df) > 5 else float("nan"),
                 stale=int(df.stale.sum()), exits=df.why.value_counts().to_dict())
    return s


def walk_forward(variant_trades: dict, primary: str) -> dict:
    """Each fold's variant = best design net from 5 Aug 2025 to the day before the fold; OOS nets summed."""
    picks, oos = [], 0.0
    for a, b in FOLDS:
        scores = {v: sum(t["net"] for t in in_period(tr, DESIGN[0], a - pd.Timedelta(days=1).to_pytimedelta()))
                  for v, tr in variant_trades.items()}
        best = max(scores.values())
        pick = primary if scores[primary] == best else sorted(v for v, s in scores.items() if s == best)[0]
        picks.append(pick)
        oos += sum(t["net"] for t in in_period(variant_trades[pick], a, b))
    full = {v: float(sum(t["net"] for t in in_period(tr, *DESIGN))) for v, tr in variant_trades.items()}
    best = max(full.values())
    frozen = primary if full[primary] == best else sorted(v for v, s in full.items() if s == best)[0]
    return {"wf_net": float(oos), "wf_picks": picks, "design_net": full, "frozen": frozen}


def twin_p(rule_net: float, twins) -> float:
    t = np.asarray(list(twins), float)
    return float((1 + np.sum(t >= rule_net)) / (len(t) + 1))


def twins(om, fm, signals, X, L, period, book, units, fut_units, n=N_TWINS, seed=SEED) -> list[float]:
    """Random-entry twins: each signal moved to a random non-expiry day of the same period at the same minute of day
    (the last grid minute at or before it), same side, hold and stop distance in points. Returns the twin totals."""
    rng = np.random.default_rng(seed)
    pool = [d for d in om.days if period[0] <= pd.Timestamp(d).date() <= period[1]
            and not om.is_exp[om.day_first[d]]]
    pool = np.array(pool)
    out = []
    for _ in range(n):
        tot = 0.0
        for s in signals:
            d = pool[rng.integers(len(pool))]
            tod = int(fm.tod[s["j"]])
            jf = _at_or_before(fm, d, tod)
            if jf < 0:
                continue
            dist = fm.F[s["j"]] - s["stopF"] if np.isfinite(s["stopF"]) else float("nan")
            t = sim_buy(om, fm, jf, s["side"], s["hold"], X, L, fm.F[jf] - dist, book, units, fut_units)
            if t is not None:
                tot += t["net"]
        out.append(tot)
    return out


def _at_or_before(m, d, tmin) -> int:
    if d not in m.day_first:
        return -1
    a, z = m.day_first[d], m.day_last[d]
    tod = m.tod[a:z + 1]
    k = np.searchsorted(tod, tmin, side="right") - 1
    return int(a + k) if k >= 0 else -1


def save(name: str, obj) -> Path:
    RESULTS.mkdir(parents=True, exist_ok=True)
    p = RESULTS / name
    p.write_text(json.dumps(obj, indent=1, default=str, sort_keys=True))
    return p
