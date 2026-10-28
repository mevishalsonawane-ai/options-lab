"""R122 inputs (PREREG section 2): point-in-time context for every trade of the 14 arms. 28 Oct 2026.

Every input reads only the index's bars BEFORE the entry minute (the last closed minute is entry - 1), daily values
of earlier sessions, India VIX the same way, and the other arms' entries strictly before the entry minute.
Directional inputs are signed by the trade's side (+ = in its favour).

    python research/hunt/r122_context_gate/r122feat.py build        # -> $IRAALGO_CACHE/r122/trades.pkl
"""
from __future__ import annotations

import math
import pickle
import sys
from bisect import bisect_left
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
for p in (HUNT / "r120_time_machine_gate", HUNT / "r121_time_machine_setup", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))
import r120cfg as K  # noqa: E402

ARMS = ("R72", "R51", "R52", "LIQ", "SOLO", "HERO", "ORB_ORB", "ORB_FRESH", "ORB_SWEEP", "ORB_FADE", "VIXDIV",
        "PINE_ST_EMA_5M", "PINE_ST_EMA_15M", "PINE_MATRIX_5M")
DUPLICATE = {"ORB_FRESH": "ORB_ORB"}          # identical list on BANKNIFTY: never counted twice
CACHE = K.OUT.parent / "r122"

FEATURES = ("tm_win", "tm_target", "setup_r", "setup_n", "gap", "move_open", "trend20", "rvol30", "from_high",
            "from_low", "minute", "weekday", "vix_prev", "vix_chg", "nr7", "inside", "pd_break",
            "pd_outside", "st5", "ema5", "don5", "last3", "agree60", "oppose60", "side")


# ---------------------------------------------------------------- pure helpers (tested)

def daily_table(days: dict) -> dict:
    """{date: dict(open, high, low, close)} from the 1-minute day dicts (minute/open/high/low/close arrays)."""
    out = {}
    for d in sorted(days):
        b = days[d]
        if len(b["close"]) == 0:
            continue
        out[d] = dict(open=float(b["open"][0]), high=float(np.max(b["high"])), low=float(np.min(b["low"])),
                      close=float(b["close"][-1]))
    return out


def prior_daily(dt: dict, d) -> dict:
    """Values known before session d opens: previous close/high/low, ATR14 (mean range of the 14 sessions before),
    the 20-session average of closes, NR7 and inside day of the previous session."""
    ds = sorted(x for x in dt if x < d)
    if not ds:
        return {}
    prev = dt[ds[-1]]
    rng = [dt[x]["high"] - dt[x]["low"] for x in ds[-14:]]
    closes = [dt[x]["close"] for x in ds[-20:]]
    last7 = [dt[x]["high"] - dt[x]["low"] for x in ds[-7:]]
    inside = len(ds) >= 2 and prev["high"] <= dt[ds[-2]]["high"] and prev["low"] >= dt[ds[-2]]["low"]
    return dict(prev_close=prev["close"], prev_high=prev["high"], prev_low=prev["low"], atr=float(np.mean(rng)),
                sma20=float(np.mean(closes)), n20=len(closes), nr7=float(len(last7) == 7 and last7[-1] == min(last7)),
                inside=float(inside))


def fold5(days: dict) -> tuple:
    """A continuous 5-minute series across sessions: (keys [(date, start minute)], open, high, low, close)."""
    keys, o, h, l, c = [], [], [], [], []
    for d in sorted(days):
        b = days[d]
        m = b["minute"]
        start = m - m % 5
        for s in np.unique(start):
            sel = start == s
            keys.append((d, int(s)))
            o.append(b["open"][sel][0]); h.append(b["high"][sel].max()); l.append(b["low"][sel].min())
            c.append(b["close"][sel][-1])
    return keys, np.array(o), np.array(h), np.array(l), np.array(c)


def ema(x: np.ndarray, n: int) -> np.ndarray:
    a = 2.0 / (n + 1)
    out = np.empty_like(x)
    out[0] = x[0]
    for i in range(1, len(x)):
        out[i] = a * x[i] + (1 - a) * out[i - 1]
    return out


def supertrend_dir(h, l, c, n=10, mult=3.0) -> np.ndarray:
    """+1 / -1 (Wilder ATR, the usual band rule); 0 before it is known."""
    tr = np.maximum(h - l, np.maximum(np.abs(h - np.roll(c, 1)), np.abs(l - np.roll(c, 1))))
    tr[0] = h[0] - l[0]
    atr = np.zeros_like(c)
    atr[n - 1] = tr[:n].mean() if len(c) >= n else 0
    for i in range(n, len(c)):
        atr[i] = (atr[i - 1] * (n - 1) + tr[i]) / n
    mid = (h + l) / 2
    ub, lb = mid + mult * atr, mid - mult * atr
    fu, fl = ub.copy(), lb.copy()
    d = np.zeros(len(c))
    for i in range(n, len(c)):
        fu[i] = ub[i] if (ub[i] < fu[i - 1] or c[i - 1] > fu[i - 1]) else fu[i - 1]
        fl[i] = lb[i] if (lb[i] > fl[i - 1] or c[i - 1] < fl[i - 1]) else fl[i - 1]
        prev = d[i - 1] if d[i - 1] != 0 else 1
        d[i] = 1 if (prev < 0 and c[i] > fu[i - 1]) or (prev > 0 and c[i] >= fl[i - 1]) else -1
    return d


def last_closed_5m(keys: list, d, entry_minute: int) -> int:
    """Index of the last 5-minute bar of day d (or earlier) that closed by the entry minute (start + 5 <= entry)."""
    i = bisect_left(keys, (d, entry_minute - 4)) - 1
    return i


def counts_before(entries: list, t_min: int, side: int, window: int = 60) -> tuple:
    """(same, opposite) among [(minute, side)] with t_min - window <= minute < t_min."""
    same = opp = 0
    for m, s in entries:
        if t_min - window <= m < t_min:
            if s == side:
                same += 1
            else:
                opp += 1
    return same, opp


# ---------------------------------------------------------------- build


def build() -> list:
    import r121study as S121
    CACHE.mkdir(parents=True, exist_ok=True)
    with open(S121.CACHE / "sessions.pkl", "rb") as f:
        sessions = pickle.load(f)
    trades = []
    for arm in ARMS:
        with open(K.TRADES / f"{arm}.pkl", "rb") as f:
            allt = pickle.load(f)
        # R120's app verdict numbers (design + final trades) and R121's setup mean R
        v = {}
        p = K.TRADES / f"{arm}.v.pkl"
        if p.exists():
            with open(p, "rb") as f:
                for t in pickle.load(f):
                    v[(t["und"], t["entry_ts"], t["side"], round(t["net"], 2))] = t
        r121 = {(t["und"], t["entry_ts"], t["side"], round(t["net"], 2)): t for t in S121.with_verdicts(arm, sessions)}
        for t in allt:
            k = (t["und"], t["entry_ts"], t["side"], round(t["net"], 2))
            a, b = v.get(k, {}), r121.get(k, {})
            t = dict(t)
            t["tm_win"] = a.get("decided_win", float("nan"))
            t["tm_target"] = a.get("target_pct", float("nan"))
            t["setup_r"] = b.get("mean_V_EXITS", float("nan"))
            t["setup_n"] = float(b.get("n_V_EXITS", 0) or 0)
            trades.append(t)
    by_und = defaultdict(list)
    for t in trades:
        by_und[t["und"]].append(t)
    vix_days = S121.minute_bars("INDIAVIX")
    vix_daily = daily_table(vix_days)
    for u, ts in by_und.items():
        days = S121.minute_bars(u)
        dt = daily_table(days)
        keys, o5, h5, l5, c5 = fold5(days)
        st = supertrend_dir(h5, l5, c5)
        e20, e50 = ema(c5, 20), ema(c5, 50)
        # other arms' entries per day: [(minute, side, arm)]
        ent = defaultdict(list)
        for t in ts:
            if t["arm"] in DUPLICATE:
                continue
            ent[t["day"]].append((t["entry_ts"].hour * 60 + t["entry_ts"].minute, int(t["side"]), t["arm"]))
        pd_cache = {}
        for t in ts:
            d, s = t["day"], int(t["side"])
            m = t["entry_ts"].hour * 60 + t["entry_ts"].minute
            f = {k: float("nan") for k in FEATURES if k not in ("tm_win", "tm_target", "setup_r", "setup_n")}
            f["side"], f["minute"], f["weekday"] = float(s), float(m), float(d.weekday())
            if d not in pd_cache:
                pd_cache[d] = prior_daily(dt, d)
            pdv = pd_cache[d]
            b = days.get(d)
            if b is not None and pdv:
                j = int(np.searchsorted(b["minute"], m))          # bars [0, j) closed before the entry minute
                if j > 0:
                    last = float(b["close"][j - 1])
                    op = float(b["open"][0])
                    hi, lo = float(np.max(b["high"][:j])), float(np.min(b["low"][:j]))
                    atr = pdv["atr"] or float("nan")
                    f["gap"] = s * (op / pdv["prev_close"] - 1) * 100
                    f["move_open"] = s * (last - op) / atr
                    f["trend20"] = s * (pdv["prev_close"] / pdv["sma20"] - 1) * 100 if pdv["n20"] >= 20 else float("nan")
                    cl = b["close"][max(0, j - 31):j]
                    f["rvol30"] = float(np.std(np.diff(np.log(cl)))) * 1e4 if len(cl) > 5 else float("nan")
                    f["from_high"] = (hi - last) / atr
                    f["from_low"] = (last - lo) / atr
                    f["pd_break"] = s * (1.0 if last > pdv["prev_high"] else -1.0 if last < pdv["prev_low"] else 0.0)
                    f["pd_outside"] = float(last < pdv["prev_low"] or last > pdv["prev_high"])
                    f["nr7"], f["inside"] = pdv["nr7"], pdv["inside"]
            i5 = last_closed_5m(keys, d, m)
            if i5 >= 50:
                f["st5"] = s * st[i5]
                f["ema5"] = s * float(np.sign(e20[i5] - e50[i5]))
                hh, ll = h5[i5 - 20:i5].max(), l5[i5 - 20:i5].min()
                f["don5"] = s * ((c5[i5] - ll) / (hh - ll) * 2 - 1) if hh > ll else 0.0
                f["last3"] = s * float(np.sign(c5[i5] - o5[i5 - 2]))
            vpd = prior_daily(vix_daily, d) if vix_daily else {}
            vb = vix_days.get(d)
            if vpd:
                f["vix_prev"] = vpd["prev_close"]
                if vb is not None:
                    jv = int(np.searchsorted(vb["minute"], m))
                    if jv > 0:
                        f["vix_chg"] = (float(vb["close"][jv - 1]) / float(vb["open"][0]) - 1) * 100
            same, opp = counts_before([(mm, ss) for mm, ss, a in ent.get(d, []) if a != t["arm"]
                                       and a != DUPLICATE.get(t["arm"], "")], m, s)
            f["agree60"], f["oppose60"] = float(same), float(opp)
            t.update(f)
    with open(CACHE / "trades.pkl", "wb") as fh:
        pickle.dump(trades, fh)
    print(len(trades), "trades;", sum(t["period"] in ("design", "final") for t in trades), "in design/final", flush=True)
    return trades


def load() -> list:
    with open(CACHE / "trades.pkl", "rb") as f:
        return pickle.load(f)


if __name__ == "__main__":
    if sys.argv[1:] == ["build"]:
        build()
    else:
        print(__doc__)
