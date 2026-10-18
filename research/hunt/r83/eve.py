"""R83 E10 (PREREG section 4): an ATR-percentile regime filter on the app's NG evening breakout (EVE).

EVE exactly as McxEveRules (android/engine/.../mcx/McxArmRules.kt, read only):
- range: the future's 1-minute closes from 17:00 to before 19:00 IST, at least 60 of them, positive width;
- signal: the first 1-minute close beyond the range from 19:00 to 22:00 (up: long, down: short), once a day;
- entry: the next minute (proxy: that minute's OPEN; the proxy has opens, the app buys at the next minute's open);
- exits, first reached, in the app's order: the close back at the range middle (stop), the close at edge + 1 width
  (target), 120 minutes held, flat by 23:15 (or 15 minutes before an earlier close). Exits fill at that minute's close.
Signal source and fills: the Henry Hub proxy (Dukascopy GASCMDUSD), traded as 1 NATGASMINI future at MCX costs
(common.fut_net('NG', ...)). The app trades the 1-ITM option; that is replayed on real MCX minutes at the final read.

Regime (E10): the previous IST day's ATR14 (daily bars from proxy minutes) and its percentile rank among the 250 daily
ATR values ending on that previous day (fraction strictly below it, x100). calm < 50 (primary), busy >= 50. Days with
fewer than 250 earlier ATR values have no regime: the filtered variants skip them; the baseline (unfiltered) trades.

Also holds the shared minute-grid helpers used by liq.py: per-day arrays and a close-based walker for twins.

    python -m research.hunt.r83.eve design      # design + walk-forward + design twins -> results/E10_design.json
    python -m research.hunt.r83.eve final       # ONE read: final + MCX-real replay -> results/E10_final.json
"""
from __future__ import annotations

import sys
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r83 import common as C

RANGE_FROM, RANGE_TO, ENTRY_UNTIL, FLAT_BY = 17 * 60, 19 * 60, 22 * 60, 23 * 60 + 15
MAX_HOLD, MIN_RANGE = 120, 60
VARIANTS = ["calm", "busy"]          # primary first
BASE = "eve_all"


# ------------------------------------------------------------------------------------------------ minute grid
class Days:
    """Proxy minutes grouped by IST day: for each day sorted arrays m (minute of day), o, h, l, c."""

    def __init__(self, df: pd.DataFrame):
        df = df.sort_values("ts")
        self.d = {}
        for day, g in df.groupby("day", sort=True):
            self.d[day] = (g.m.to_numpy(np.int32), g.o.to_numpy(float), g.h.to_numpy(float), g.l.to_numpy(float),
                           g.c.to_numpy(float))
        self.days = sorted(self.d)

    def get(self, day):
        return self.d.get(day)


def walk_close(arr, k0: int, side: int, fill: float, stop: float, target: float, exit_m: int):
    """From index k0 (the entry minute, included), the first of: close at/through stop, close at/through target,
    the first minute at or after exit_m (its close). Returns (k, price, why, is_stop). Stop before target on a tie."""
    m, o, h, l, c = arr
    for k in range(k0, len(m)):
        if m[k] >= exit_m:
            return k, c[k], "time", False
        if np.isfinite(stop) and side * (c[k] - stop) <= 0:
            return k, c[k], "stop", True
        if np.isfinite(target) and side * (c[k] - target) >= 0:
            return k, c[k], "target", False
    k = len(m) - 1
    return k, c[k], "end", False


# ------------------------------------------------------------------------------------------------ EVE
def eve_flat(day) -> int:
    return min(FLAT_BY, C.mcx_close_min(day) - 15)


def eve_signal(arr):
    """(j, side, hi, lo) on one day's arrays, or None. Only minutes up to the signal minute are read."""
    m, o, h, l, c = arr
    r = (m >= RANGE_FROM) & (m < RANGE_TO)
    if r.sum() < MIN_RANGE:
        return None
    hi, lo = c[r].max(), c[r].min()
    if not hi - lo > 0:
        return None
    for j in np.nonzero((m >= RANGE_TO) & (m <= ENTRY_UNTIL))[0]:
        side = 1 if c[j] > hi else (-1 if c[j] < lo else 0)
        if side:
            return int(j), side, float(hi), float(lo)
    return None


def eve_trade(day, arr, mkt="NG"):
    """One EVE trade on a day (dict) or None."""
    s = eve_signal(arr)
    if s is None:
        return None
    j, side, hi, lo = s
    m, o, h, l, c = arr
    k0 = j + 1
    flat = eve_flat(day)
    if k0 >= len(m) or m[k0] >= flat:
        return None
    fill = o[k0]
    edge = hi if side > 0 else lo
    stop, target = (hi + lo) / 2, edge + side * (hi - lo)
    exit_m = min(int(m[k0]) + MAX_HOLD, flat)
    k, px, why, is_stop = walk_close(arr, k0, side, fill, stop, target, exit_m)
    notional = C.proxy_notional(mkt, fill, day)
    gross, net = C.fut_net(mkt, side, fill, px, notional, stop=is_stop)
    ts0 = pd.Timestamp(day) + pd.Timedelta(minutes=int(m[k0]))
    return dict(entry_ts=ts0, exit_ts=pd.Timestamp(day) + pd.Timedelta(minutes=int(m[k])), side=side, fill=fill,
                exit=px, why=why, gross=gross, net=net, entry_m=int(m[k0]), exit_m_rule=exit_m,
                ds=abs(fill - stop) / fill, dt=abs(target - fill) / fill, hold=int(m[k] - m[k0]))


# ------------------------------------------------------------------------------------------------ regime
def atr_pct(daily: pd.DataFrame, n: int = 14, look: int = 250) -> pd.Series:
    """Index: day; value: percentile (0-100) of the PREVIOUS day's ATR among the `look` ATR values ending on that
    previous day (fraction strictly below). NaN without enough history. No look-ahead: day t reads bars <= t-1."""
    a = C.atr(daily, n)
    vals = a.to_numpy(float)
    out = np.full(len(vals), np.nan)
    for t in range(1, len(vals)):
        v = vals[t - 1]
        w = vals[max(0, t - look):t]
        if len(w) < look or not np.isfinite(w).all():
            continue
        out[t] = 100.0 * np.mean(w < v)
    return pd.Series(out, index=list(daily.day))


def regime_ok(variant: str, pct) -> bool:
    if variant == BASE:
        return True
    if pct is None or not np.isfinite(pct):
        return False
    return pct < 50 if variant == "calm" else pct >= 50


# ------------------------------------------------------------------------------------------------ runs
def load(mkt="NG"):
    raw = C.load_proxy(mkt)
    daily = C.daily_from_minutes(raw)
    pct = atr_pct(daily)
    D = Days(C.mcx_hours(raw))
    return D, pct


def all_trades(D: Days, pct: pd.Series, a: date, b: date, mkt="NG"):
    base = []
    for day in D.days:
        if not (a <= day <= b):
            continue
        t = eve_trade(day, D.get(day), mkt)
        if t is not None:
            t["pct"] = float(pct.get(day, np.nan))
            base.append(t)
    out = {v: [t for t in base if regime_ok(v, t["pct"])] for v in VARIANTS}
    out[BASE] = base
    return out


def twin_nets(D: Days, trades, rng, mkt="NG", n=C.N_TWINS, pool=None):
    """200 twins per trade: same side, entry at the same clock minute on a random trading day of the same calendar year
    (the first minute at or after it), same stop / target distances (fraction of the fill), same time-exit rule
    (min(entry + held cap, flat)). Returns an array (n,) of twin-set totals."""
    pool = pool or D.days
    tot = np.zeros(n)
    for t in trades:
        day0 = C.day_of(t["entry_ts"])
        ds_ = C.twin_dates(day0, pool, rng, n)
        for i, d in enumerate(ds_):
            tot[i] += _twin_one(D, d, t, mkt)
    return tot


def _twin_one(D, d, t, mkt):
    arr = D.get(d)
    if arr is None:
        return 0.0
    m, o, h, l, c = arr
    k0 = int(np.searchsorted(m, t["entry_m"]))
    flat = eve_flat(d) if "flat_rule" not in t else t["flat_rule"](d)
    if k0 >= len(m) or m[k0] >= flat or m[k0] > t["entry_m"] + 10:
        return 0.0
    side, fill = t["side"], o[k0]
    stop = fill * (1 - side * t["ds"]) if t.get("ds") is not None else np.nan
    target = fill * (1 + side * t["dt"]) if t.get("dt") is not None else np.nan
    exit_m = min(int(m[k0]) + int(t.get("cap", MAX_HOLD)), flat)
    k, px, why, is_stop = walk_close(arr, k0, side, fill, stop, target, exit_m)
    _, net = C.fut_net(mkt, side, fill, px, C.proxy_notional(mkt, fill, d), stop=is_stop)
    return net


def design():
    D, pct = load()
    V = all_trades(D, pct, C.DESIGN[0], C.DESIGN[1])
    wf = C.walk_forward({v: V[v] for v in VARIANTS}, VARIANTS[0])
    base_wf = sum(C.net_between(V[BASE], a, b) for a, b in C.FOLDS)
    rng = np.random.default_rng(C.SEED + 10)
    res = {"idea": "E10", "signal_source": "Henry Hub proxy (Dukascopy GASCMDUSD), traded as NATGASMINI future",
           "variants": {}, "baseline": BASE}
    for v in VARIANTS + [BASE]:
        tr = V[v]
        tw = twin_nets(D, tr, rng)
        net = sum(t["net"] for t in tr)
        res["variants"][v] = {**C.summary(tr), "gross": float(sum(t["gross"] for t in tr)),
                              "design_twin_p": C.twin_p(net, tw), "fold_net": wf["fold_net"].get(v) if v != BASE else
                              [C.net_between(tr, a, b) for a, b in C.FOLDS]}
    res["walk_forward"] = {k: wf[k] for k in ("wf_net", "wf_picks", "design_net", "frozen")}
    res["baseline_wf_net"] = base_wf
    res["frozen"] = wf["frozen"]
    C.save_json("E10_design.json", res)
    return res


def final():
    import json
    des = json.loads((C.RESULTS / "E10_design.json").read_text())
    frozen = des["frozen"]
    C.open_final("E10")
    D, pct = load()
    V = all_trades(D, pct, C.FINAL[0], C.FINAL[1])
    rng = np.random.default_rng(C.SEED + 11)
    out = {"idea": "E10", "frozen": frozen, "baseline": BASE}
    for v in (frozen, BASE):
        tr = V[v]
        tw = twin_nets(D, tr, rng)
        net = sum(t["net"] for t in tr)
        out[v] = {**C.summary(tr), "gross": float(sum(t["gross"] for t in tr)), "twin_p": C.twin_p(net, tw),
                  "top5_removed": C.top5_removed(tr),
                  "by_year": {str(y): float(sum(t["net"] for t in tr if t["entry_ts"].year == y)) for y in range(2023, 2027)}}
    out["wf_net"] = des["walk_forward"]["wf_net"]
    out["baseline_wf_net"] = des["baseline_wf_net"]
    from research.hunt.r83 import mcxreal
    out["mcx_real"] = mcxreal.eve_replay(pct, variants=(frozen, BASE))
    C.save_json("E10_final.json", out)
    return out


if __name__ == "__main__":
    print({"design": design, "final": final}[sys.argv[1]]())
