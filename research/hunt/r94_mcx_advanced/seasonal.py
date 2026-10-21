"""R94 idea 4: bullion seasonals on the daily continuous futures, 2012 - 2026 (PREREG 3.4). Research only.

Signal source: the calendar only (Diwali, Akshaya Tritiya, the wedding months). Trade: 1 lot of the mini (GOLDM units
10 on the GOLD price, SILVERM units 5 on the SILVER price), long or short, with a resting 2 x ATR14 hard stop.
Twins: random windows of the same length, side and stop inside the same calendar year.

    python -m research.hunt.r94_mcx_advanced.seasonal design     # -> results/design_idea4.json
"""
from __future__ import annotations

import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r94_mcx_advanced import common as C
from research.hunt.r94_mcx_advanced import pairs as P

DESIGN = (date(2012, 1, 1), date(2021, 12, 31))
HOLDOUT = (date(2022, 1, 1), date(2026, 10, 16))
FOLDS = [(date(2012, 1, 1), date(2014, 12, 31)), (date(2015, 1, 1), date(2017, 12, 31)),
         (date(2018, 1, 1), date(2021, 12, 31))]

# Calendar facts (Lakshmi Puja / NSE-MCX Muhurat day; Akshaya Tritiya), written 21 Oct 2026 from the Hindu calendar.
DIWALI = ["2012-11-13", "2013-11-03", "2014-10-23", "2015-11-11", "2016-10-30", "2017-10-19", "2018-11-07",
          "2019-10-27", "2020-11-14", "2021-11-04", "2022-10-24", "2023-11-12", "2024-11-01", "2025-10-21"]
AKSHAYA = ["2012-04-24", "2013-05-13", "2014-05-02", "2015-04-21", "2016-05-09", "2017-04-28", "2018-04-18",
           "2019-05-07", "2020-04-26", "2021-05-14", "2022-05-03", "2023-04-22", "2024-05-10", "2025-04-30",
           "2026-04-19"]
WEDDING_MONTHS = (11, 12, 1, 2, 4, 5)
WIN_ROWS = 10
STOP_ATR = 2.0
MINI = {"GOLD": ("GOLDM", 10.0), "SILVER": ("SILVERM", 5.0)}

CELLS = {"DW-PRE": ("diwali", "pre", 1), "DW-POST": ("diwali", "post", -1), "AT-PRE": ("akshaya", "pre", 1),
         "WED": ("wedding", None, 1)}


class Series:
    """One continuous future's daily rows with roll flags and ATR14 (to the previous row)."""

    def __init__(self, sym: str, d: pd.DataFrame):
        self.sym = sym
        self.dates = pd.DatetimeIndex(d.date.values)
        self.o, self.h, self.l, self.c = (d[k].to_numpy(float) for k in ("open", "high", "low", "close"))
        self.roll = P.mark_rolls(d.oi.to_numpy(float))
        pc = np.r_[np.nan, self.c[:-1]]
        tr = np.maximum(self.h - self.l, np.maximum(np.abs(self.h - pc), np.abs(self.l - pc)))
        tr = np.where(self.roll | np.isnan(pc), self.h - self.l, tr)
        self.atr = pd.Series(tr).rolling(14, min_periods=14).mean().shift(1).to_numpy()   # known before the open
        self.mini, self.units = MINI[sym]

    def __len__(self):
        return len(self.c)


# ------------------------------------------------------------------------------------------------ windows
def event_window(s: Series, day: str, kind: str, n: int = WIN_ROWS):
    """(entry row, exit row) of the n rows strictly before ('pre') or after ('post') the date; the date's own row (a
    Muhurat session) is never used. None if the data do not hold the whole window."""
    d = pd.Timestamp(day)
    if kind == "pre":
        k = int(s.dates.searchsorted(d, side="left"))         # first row on / after the date
        a, b = k - n, k - 1
    else:
        k = int(s.dates.searchsorted(d, side="right"))        # first row strictly after the date
        a, b = k, k + n - 1
    if a < 0 or b >= len(s) or a > b:
        return None
    return a, b


def month_windows(s: Series, months=WEDDING_MONTHS, last_complete: pd.Timestamp | None = None):
    """(entry row, exit row, 'YYYY-MM') for each complete calendar month in `months` (first open to last close)."""
    per = s.dates.to_period("M")
    end = pd.Timestamp(last_complete) if last_complete is not None else s.dates[-1]
    out = []
    for p in pd.unique(per):
        if p.month not in months or p.end_time.normalize() > end:
            continue
        ix = np.nonzero(per == p)[0]
        out.append((int(ix[0]), int(ix[-1]), str(p)))
    return out


# ------------------------------------------------------------------------------------------------ one trade
def _cost(s: Series, price: float) -> float:
    rs = price * s.units
    return C.fut_leg_charges(rs, rs) + 2 * C.fut_half_spread(s.mini, price) * s.units


def trade(s: Series, a: int, b: int, side: int, why: str = "") -> dict | None:
    """Enter at open[a], exit at close[b], resting stop STOP_ATR x ATR14 (at a) on the roll-adjusted path: exit at the
    stop price, or at the open when the open gaps through it. A roll row inside the hold closes the old contract at the
    previous close and re-opens at the new contract's open (the switch gap is not earned; one more round trip)."""
    atr = s.atr[a]
    if not np.isfinite(atr) or atr <= 0:
        return None
    dist = STOP_ATR * atr
    pts = 0.0                       # realised points of finished contract segments
    ref = s.o[a]                    # entry price of the current segment
    cost = _cost(s, s.o[a])
    gross_pts = None
    why_out = "time"
    for t in range(a, b + 1):
        if t > a and s.roll[t]:
            pts += side * (s.c[t - 1] - ref)
            ref = s.o[t]
            cost += _cost(s, s.o[t])
        # stop price on today's contract: the price at which the total open loss (points) reaches dist
        stop_px = ref + (-dist - pts) / side
        if t > a:
            if (side == 1 and s.o[t] <= stop_px) or (side == -1 and s.o[t] >= stop_px):
                gross_pts = pts + side * (s.o[t] - ref)
                why_out, b = "stop-gap", t
                break
        if (side == 1 and s.l[t] <= stop_px) or (side == -1 and s.h[t] >= stop_px):
            gross_pts = pts + side * (stop_px - ref)
            why_out, b = "stop", t
            break
    if gross_pts is None:
        gross_pts = pts + side * (s.c[b] - ref)
    gross = gross_pts * s.units
    return {"entry_ts": s.dates[a], "exit_ts": s.dates[b], "side": int(side), "gross": float(gross),
            "net": float(gross - cost), "costs": float(cost), "why": why_out, "a": int(a), "b": int(b),
            "risk_rs": float(dist * s.units + _cost(s, s.o[a])),
            "margin_rs": float(s.o[a] * s.units * P.MARGIN_RATE[s.sym])}


# ------------------------------------------------------------------------------------------------ cells
def rule_windows(s: Series, cell: str):
    """[(entry row, exit row, side, year, label)] for every event of the cell."""
    kind, where, side = CELLS[cell]
    out = []
    if kind == "wedding":
        for a, b, lab in month_windows(s):
            out.append((a, b, side, int(lab[:4]), lab))
        return out
    days = DIWALI if kind == "diwali" else AKSHAYA
    for d in days:
        w = event_window(s, d, where)
        if w:
            out.append((w[0], w[1], side, pd.Timestamp(d).year, d))
    return out


def in_window(dt, window) -> bool:
    return window[0] <= pd.Timestamp(dt).date() <= window[1]


def twins(s: Series, cell: str, wins, n_sets=C.N_TWINS, seed=C.SEED) -> np.ndarray:
    """Random windows inside the same calendar year: same length (rows), side and stop. WED: k random complete
    calendar months of the same year (k = the wedding months traded that year)."""
    if not wins:
        return np.array([])
    rng = np.random.default_rng(seed)
    years = pd.DatetimeIndex(s.dates).year.values
    cache, tot = {}, np.zeros(n_sets)
    if CELLS[cell][0] == "wedding":
        allm = month_windows(s, months=tuple(range(1, 13)))
        by_year = {}
        for a, b, lab in allm:
            by_year.setdefault(int(lab[:4]), []).append((a, b))
        k_year = pd.Series([w[3] for w in wins]).value_counts().to_dict()
        for n in range(n_sets):
            x = 0.0
            for y, k in k_year.items():
                pool = by_year[y]
                for j in rng.choice(len(pool), size=min(k, len(pool)), replace=False):
                    a, b = pool[j]
                    key = (a, b, wins[0][2])
                    if key not in cache:
                        t = trade(s, a, b, wins[0][2])
                        cache[key] = t["net"] if t else 0.0
                    x += cache[key]
            tot[n] = x
        return tot
    for n in range(n_sets):
        x = 0.0
        for a, b, side, y, _ in wins:
            ln = b - a
            rows = np.nonzero(years == y)[0]
            rows = rows[(rows + ln < len(s)) & (rows >= 15)]
            st = int(rng.choice(rows))
            key = (st, st + ln, side)
            if key not in cache:
                t = trade(s, st, st + ln, side)
                cache[key] = t["net"] if t else 0.0
            x += cache[key]
        tot[n] = x
    return tot


def cells(part: str, n_sets: int = C.N_TWINS) -> dict:
    window = DESIGN if part == "design" else HOLDOUT
    out = {}
    for sym in ("GOLD", "SILVER"):
        s = Series(sym, P.load_daily(sym))
        for cell in CELLS:
            wins = [w for w in rule_windows(s, cell) if in_window(s.dates[w[0]], window)]
            tr = []
            for a, b, side, y, lab in wins:
                t = trade(s, a, b, side)
                if t:
                    t["event"] = lab
                    tr.append(t)
            tw = twins(s, cell, wins, n_sets=n_sets)
            sm = C.summary(tr)
            out[f"s-{cell}-{sym}"] = {
                "trades": tr, "p": C.twin_p(sm["net"], tw) if tr else 1.0,
                "folds": C.fold_nets(tr, FOLDS) if part == "design" else [],
                "extra": {"events": [t["event"] for t in tr],
                          "per_event": {t["event"]: round(t["net"]) for t in tr},
                          "exits": pd.Series([t["why"] for t in tr]).value_counts().to_dict() if tr else {},
                          "twin_mean": float(np.mean(tw)) if len(tw) else None,
                          "twin_p90": float(np.percentile(tw, 90)) if len(tw) else None}}
    return out


def main(argv):
    if argv[:1] != ["design"]:
        print(__doc__)
        return 2
    res = cells("design")
    with open(C.cache_dir() / "idea4_design_trades.pkl", "wb") as f:
        pickle.dump({k: v["trades"] for k, v in res.items()}, f)
    C.save("design_idea4.json", {k: {"summary": C.summary(v["trades"]), "p": v["p"], "folds": v["folds"],
                                     "extra": v["extra"]} for k, v in res.items()})
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
