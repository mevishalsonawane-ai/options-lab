"""R19b data: per-session Liquidity 15+5 signals (BANKNIFTY 15m / 5m books) with the option minutes they need.

    python3 -m research.hunt.r19b.data          # builds <IRAALGO_CACHE>/r19b/recs.pkl from the research-data release

Read through research/brain/adapters/release.py (download first: `python -m research.brain.cube fetch`). The release
holds the nearest expiry AFTER the day; the arm skips expiry days, so that is the contract the arm trades. No Dhan API.
"""
from __future__ import annotations

import os
import pickle
from collections import deque
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

from research.brain import calendar as CAL
from research.brain import config as BC
from research.brain.adapters.release import ReleaseAdapter

from . import sim as S

UND = "BANKNIFTY"
# NSE lot in force (research/obuy/config.py LOT_SCHEDULE)
LOTS = [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35)]


def lot_of(d: date) -> int:
    out = 0
    for s, n in LOTS:
        if d >= date.fromisoformat(s):
            out = n
    return out


def out_dir() -> Path:
    p = Path(os.environ.get("R19B_OUT", BC.paths().root / "r19b")).expanduser()
    p.mkdir(parents=True, exist_ok=True)
    return p


def _minute(ts) -> np.ndarray:
    t = pd.to_datetime(ts)
    return (t.dt.hour * 60 + t.dt.minute).values.astype(int)


def build(raw: Path | None = None) -> list[dict]:
    raw = raw or BC.paths().raw_release
    ad = ReleaseAdapter(raw)
    ss = ad.sessions(UND)
    sessions = set(ss)
    rec = []
    hist: deque = deque()          # (day, today's 1-minute bars)
    for dd in ad.load(UND, ss[0], ss[-1]):
        d = dd.day
        ix = dd.index.sort_values("ts")
        m = _minute(ix.ts)
        keep = (m >= 555) & (m <= 929)
        today1 = [(d, int(mm), float(o), float(h), float(lo), float(c))
                  for mm, o, h, lo, c in zip(m[keep], ix.open.values[keep], ix.high.values[keep], ix.low.values[keep],
                                              ix.close.values[keep])]
        while hist and hist[0][0] < d - timedelta(days=10):
            hist.popleft()
        exp, _ = CAL.next_expiry(UND, d, sessions)
        is_exp = exp == d
        if hist and not is_exp and today1:
            first = {}
            for b in [x for h_ in hist for x in h_[1]] + today1:
                first.setdefault((b[0], b[1]), b)
            ones = sorted(first.values(), key=lambda b: (b[0], b[1]))
            books = {}
            need = set()
            for book, tf in S.BOOKS:
                sg = S.signals(ones, d, tf)
                if sg:
                    books[book] = sg
                    need |= {(s["strike"], s["right"]) for s in sg}
            if books:
                o = dd.options
                o = o[pd.to_datetime(o.expiry).dt.date > d]
                opts = {}
                if len(o):
                    near = pd.to_datetime(o.expiry).dt.date.min()
                    o = o[pd.to_datetime(o.expiry).dt.date == near]
                    for (k, side), g in o.groupby(["strike", "side"]):
                        key = (int(round(k)), side)
                        if key not in need:
                            continue
                        g = g.sort_values("ts")
                        mm = _minute(g.ts)
                        ok = (mm >= 555) & (mm <= 929) & np.isfinite(g.close.values)
                        if ok.sum():
                            opts[key] = (mm[ok], g.open.values[ok].astype(float), g.high.values[ok].astype(float),
                                         g.low.values[ok].astype(float), g.close.values[ok].astype(float))
                rec.append(dict(day=d, lot=lot_of(d), imin={b[1]: (b[3], b[4]) for b in today1}, books=books, opts=opts))
        hist.append((d, today1))
    return rec


def books_count(rec):
    return sum(len(r["books"]) for r in rec)


def load(rebuild: bool = False) -> list[dict]:
    p = out_dir() / "recs.pkl"
    if p.exists() and not rebuild:
        with open(p, "rb") as f:
            return pickle.load(f)
    rec = build()
    with open(p, "wb") as f:
        pickle.dump(rec, f)
    return rec


if __name__ == "__main__":
    r = load(rebuild=True)
    print(f"{len(r)} sessions with signals, {books_count(r)} book-days; {r[0]['day']} .. {r[-1]['day']}")
