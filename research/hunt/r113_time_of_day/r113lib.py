"""R113 pure parts (PREREG.md sections 2-5): buckets, day labels, bootstrap bands, cross-arm patterns, the Part D
filters, random-drop twins, Benjamini-Hochberg and the gates. Plain Python / numpy / pandas on plain inputs; no file
or network access, so test_r113.py covers all of it. 26 Oct 2026.
"""
from __future__ import annotations

from datetime import date, datetime

import numpy as np
import pandas as pd

import r113cfg as K

COLS = ["arm", "und", "day", "entry_ts", "exit_ts", "side", "net_rs", "period", "expiry"]


# ================================================================================================ buckets
def minute_of(ts) -> int:
    t = pd.Timestamp(ts)
    return int(t.hour * 60 + t.minute)


def bucket(minute: int, buckets) -> str | None:
    for name, lo, hi in buckets:
        if lo <= minute < hi:
            return name
    return None


def time_buckets(und: str):
    return K.MCX_BUCKETS if str(und).upper() in K.MCX_UNDS else K.NSE_BUCKETS


def hold_bucket(minutes: float, overnight: bool) -> str:
    if overnight:
        return "overnight"
    return bucket(max(int(minutes), 0), K.HOLD_BUCKETS) or ">120m"


def gap_label(open_: float, prev_close: float) -> str | None:
    """Gap up / down / flat on today's open vs the previous close (R102's 0.25% line). Known at the open."""
    if not (np.isfinite(open_) and np.isfinite(prev_close)) or prev_close <= 0:
        return None
    g = (open_ / prev_close - 1) * 100
    return "gap up" if g > K.GAP_LINE else ("gap down" if g < -K.GAP_LINE else "flat")


def body_label(o: float, h: float, l: float, c: float) -> str | None:
    """Trend / mixed / range day from the whole day's bar: AFTER THE FACT, never a rule (PREREG section 2.8)."""
    if not all(np.isfinite(x) for x in (o, h, l, c)) or h - l <= 0:
        return None
    s = abs(c - o) / (h - l)
    return "trend day" if s >= K.TREND_BODY else ("range day" if s <= K.RANGE_BODY else "mixed")


def vix_cuts(vix_close: pd.Series) -> tuple:
    """Tercile cuts from the DESIGN days' VIX closes only (index = date). No final day is used (look-ahead guard)."""
    s = vix_close[[K.START <= d <= K.DESIGN_END for d in vix_close.index]].dropna()
    return float(s.quantile(1 / 3)), float(s.quantile(2 / 3))


def prev_vix(vix_close: pd.Series, d: date) -> float:
    """The last VIX close STRICTLY before day d (the previous session's)."""
    s = vix_close[[x < d for x in vix_close.index]]
    return float(s.iloc[-1]) if len(s) else float("nan")


def vix_label(v: float, cuts: tuple) -> str | None:
    if not np.isfinite(v):
        return None
    return "VIX low" if v <= cuts[0] else ("VIX high" if v > cuts[1] else "VIX mid")


def label_trades(df: pd.DataFrame, days: dict, vix: pd.Series, cuts: tuple) -> pd.DataFrame:
    """Add every bucket column. days[(und, day)] = (open, high, low, close, prev_close) of the underlying."""
    df = df.copy()
    et = pd.to_datetime(df.entry_ts)
    xt = pd.to_datetime(df.exit_ts)
    df["day"] = pd.to_datetime(df.day).dt.date
    df["entry_min"] = et.dt.hour * 60 + et.dt.minute
    df["exit_min"] = xt.dt.hour * 60 + xt.dt.minute
    df["entry_b"] = [bucket(m, time_buckets(u)) for m, u in zip(df.entry_min, df.und)]
    df["exit_b"] = [bucket(m, time_buckets(u)) for m, u in zip(df.exit_min, df.und)]
    hold = (xt - et).dt.total_seconds() / 60
    over = (xt.dt.date != et.dt.date).values
    df["hold_min"] = hold.values
    df["hold_b"] = [hold_bucket(h, o) for h, o in zip(hold, over)]
    df["weekday"] = [K.WEEKDAYS[d.weekday()] for d in df.day]
    df["expiry_b"] = ["expiry day" if str(x) in ("1", "1.0", "True") else ("not expiry" if str(x) in ("0", "0.0", "False")
                      else None) for x in df.expiry]
    g, b = [], []
    for u, d in zip(df.und, df.day):
        r = days.get((str(u).upper(), d))
        if r is None:
            g.append(None); b.append(None)
            continue
        o, h, l, c, pc = r
        g.append(gap_label(o, pc)); b.append(body_label(o, h, l, c))
    df["gap_b"], df["body_b"] = g, b
    df["vix_b"] = [vix_label(prev_vix(vix, d), cuts) for d in df.day]
    return df


# ================================================================================================ bucket statistics
def day_band(nets: np.ndarray, days: np.ndarray, boot: int = K.BOOT, band: float = K.BAND, seed: int = K.SEED):
    """90% band of a bucket's total net from resampling its DAYS with replacement (trades of a day move together)."""
    if len(nets) == 0:
        return (0.0, 0.0)
    s = pd.Series(nets).groupby(pd.Series(days)).sum().values
    rng = np.random.default_rng(seed)
    tot = s[rng.integers(0, len(s), size=(boot, len(s)))].sum(axis=1)
    a = (1 - band) / 2
    return float(np.quantile(tot, a)), float(np.quantile(tot, 1 - a))


def describe(df: pd.DataFrame, col: str, order=None) -> list:
    """Rows per bucket: trades, wins, win rate, net, average, 90% day-bootstrap band, small flag, share of the arm's net."""
    total = float(df.net_rs.sum()) if len(df) else 0.0
    keys = list(order) if order is not None else sorted(df[col].dropna().unique(), key=str)
    out = []
    for k in keys:
        g = df[df[col] == k]
        n = len(g)
        if n == 0 and order is None:
            continue
        nets = g.net_rs.values.astype(float)
        lo, hi = day_band(nets, g.day.astype(str).values)
        out.append(dict(bucket=k, trades=n, win=(round(float((nets > 0).mean()), 3) if n else None),
                        net=round(float(nets.sum())), avg=(round(float(nets.mean())) if n else None),
                        band=(round(lo), round(hi)), small=n < K.SMALL_N,
                        sure=(n >= K.SMALL_N and (lo > 0 or hi < 0)), share=(round(float(nets.sum()) / total, 3) if total
                                                                             else None)))
    return out


ORDERS = {
    "hold_b": [b[0] for b in K.HOLD_BUCKETS] + ["overnight"],
    "weekday": list(K.WEEKDAYS[:5]),
    "expiry_b": ["expiry day", "not expiry"],
    "gap_b": ["gap up", "flat", "gap down"],
    "vix_b": ["VIX low", "VIX mid", "VIX high"],
    "body_b": ["trend day", "mixed", "range day"],
}


def time_order(df: pd.DataFrame) -> list:
    mcx = len(df) and all(str(u).upper() in K.MCX_UNDS for u in df.und)
    return [b[0] for b in (K.MCX_BUCKETS if mcx else K.NSE_BUCKETS)]


def describe_all(df: pd.DataFrame) -> dict:
    to = time_order(df)
    out = {"entry_b": describe(df, "entry_b", to), "exit_b": describe(df, "exit_b", to)}
    for col, order in ORDERS.items():
        out[col] = describe(df, col, order)
    return out


# ================================================================================================ cross-arm patterns
def daily_series(df: pd.DataFrame) -> pd.Series:
    return df.groupby("day").net_rs.sum()


def pair_corr(a: pd.Series, b: pd.Series) -> tuple:
    """Pearson on the days either arm traded (0 for the arm that did not); (r, n_days)."""
    idx = a.index.union(b.index)
    x, y = a.reindex(idx, fill_value=0.0), b.reindex(idx, fill_value=0.0)
    if len(idx) < 3 or x.std() == 0 or y.std() == 0:
        return (float("nan"), int(len(idx)))
    return (float(np.corrcoef(x, y)[0, 1]), int(len(idx)))


def top_share(values, frac: float = 0.10) -> float | None:
    """Share of the total net made by the best `frac` of items (days or trades); None if the total is not positive."""
    v = np.sort(np.asarray(values, float))[::-1]
    tot = v.sum()
    if len(v) == 0 or tot <= 0:
        return None
    k = max(1, int(np.ceil(frac * len(v))))
    return float(v[:k].sum() / tot)


def first_hour_share(df: pd.DataFrame) -> float | None:
    tot = df.net_rs.sum()
    if not len(df) or tot == 0:
        return None
    return float(df[df.entry_min < K.FIRST_HOUR_END].net_rs.sum() / tot)


# ================================================================================================ Part D (rules)
def in_drop(minute: int, drop) -> bool:
    return any(lo <= minute < hi for lo, hi in drop)


def row_minute(er: int) -> int:
    """The R51-store row (minutes after 09:15) as minutes after midnight."""
    return 555 + int(er)


def keep_mask(cands_minutes: list, drop) -> list:
    """True for a candidate whose ENTRY (fill) minute is outside the dropped windows. The candidate's entry row is its
    own decision minute; nothing later is read (look-ahead guard)."""
    return [not in_drop(m, drop) for m in cands_minutes]


def random_drop(n_total: int, k: int, rng) -> np.ndarray:
    """Indices of k candidates removed at random from n_total (the random twin of a rule that removes k)."""
    k = min(max(int(k), 0), n_total)
    return rng.choice(n_total, size=k, replace=False) if k else np.array([], int)


def twin_p(rule_net: float, twin_nets) -> float:
    t = np.asarray(twin_nets, float)
    return float((1 + int((t >= rule_net).sum())) / (len(t) + 1))


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q; m_total > len(p) counts the earlier tests as p = 1 (R106's convention)."""
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    m = max(m_total or n, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


def qualifies(design_window_net: float) -> bool:
    """PREREG section 5: a rule is tested for an arm only if the entries it removes lost money in design."""
    return bool(design_window_net < 0)


def gates(design: dict, wf: dict, final: dict, b0_wf: dict, b0_final: dict, q: float) -> dict:
    fails = []
    if not (design["net"] > 0 and wf["net"] > 0 and final["net"] > 0):
        fails.append("not positive in design, walk-forward and final")
    if not (wf["net"] > b0_wf["net"] and final["net"] > b0_final["net"]):
        fails.append("not above today's rules in walk-forward and final")
    if not q <= K.GATE_Q:
        fails.append(f"random-twin BH q {q:.3f} > {K.GATE_Q}")
    if final["trades"] < K.MIN_FINAL:
        fails.append(f"< {K.MIN_FINAL} final trades")
    return dict(passed=not fails, fails=fails)


def summary(trades: list) -> dict:
    nets = np.array([t["net"] for t in trades], float)
    return dict(trades=int(len(nets)), net=round(float(nets.sum())) if len(nets) else 0,
                win=round(float((nets > 0).mean()), 4) if len(nets) else None)


# ================================================================================================ the phone's paper record
def pair_orders(orders: list, day: str) -> list:
    """Paper round trips from one diagnostics day: each completed BUY of a (source, symbol) closed by the next completed
    SELL of it (options and long futures), or a SELL closed by the next BUY (a short future). Untrusted data: only the
    fields used are read; malformed rows are skipped. Points = exit - entry (sign by direction); pct of the entry."""
    rows = []
    for o in orders or []:
        try:
            if str(o.get("account")) != "paper" or str(o.get("status")) != "complete":
                continue
            rows.append((str(o["at"]), str(o.get("source")), str(o.get("symbol")), str(o.get("side")).upper(),
                         float(o["avg"]) if o.get("avg") is not None else float("nan")))
        except (KeyError, TypeError, ValueError):
            continue
    rows.sort()
    open_, out = {}, []
    for at, src, sym, side, px in rows:
        key = (src, sym)
        if key in open_:
            at0, side0, px0 = open_.pop(key)
            if side == side0:                     # a second entry the same way: keep the first, start over
                open_[key] = (at0, side0, px0)
                continue
            sgn = 1 if side0 == "BUY" else -1
            pts = sgn * (px - px0)
            out.append(dict(day=day, source=src, symbol=sym, entry_at=at0, exit_at=at, dir=side0, points=pts,
                            pct=(pts / px0 * 100 if px0 and np.isfinite(px0) else float("nan"))))
        else:
            open_[key] = (at, side, px)
    return out


def hhmm_minute(at: str) -> int:
    t = datetime.strptime(at[:5], "%H:%M")
    return t.hour * 60 + t.minute
