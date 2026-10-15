"""R60 index half (PREREG section 3, I1-I5): SoloMidday and A2 on SENSEX and MIDCPNIFTY, on R57's code.

    python research/hunt/r60/r60idx.py build [UND ...]     # R57 day stores + brain features for the five indices

R57's modules are used unchanged; r60cfg.point_r57() points them at R60's cache, window and five indices first.
"""
from __future__ import annotations

import pickle
import sys
from collections import defaultdict
from datetime import date

import numpy as np

import r60cfg as C

K = C.point_r57()
import r57data  # noqa: E402
import r57model as M  # noqa: E402
import r57setups as S  # noqa: E402
import r57study as T  # noqa: E402

SETUPS = ("S1", "S2", "S3", "S4")
A2 = T.VARIANTS["A2"]


def load_all():
    for u in C.IDX_UNDS:
        r57data.load(u)


def calendar(hi: date) -> list:
    s = set()
    for u in C.IDX_UNDS:
        s.update(d for d, D in r57data.load(u).items() if D.atm is not None and C.START <= d <= hi)
    return sorted(s)


def build_rows(part: str) -> list:
    """Candidates S1-S4 on the five indices with features and labels (R57 a_rows), cached per part."""
    lo, hi = (C.START, C.DESIGN_END) if part == "design" else (C.FINAL_START, C.END)
    C.check_range(lo, hi)
    path = K.OUT / f"rows_{part}.pkl"
    if path.exists():
        with open(path, "rb") as f:
            return pickle.load(f)
    load_all()
    if part == "final":
        S.open_final()
    rows = T.a_rows(S.candidates(lo, hi, setups=SETUPS, unds=C.IDX_UNDS))
    with open(path, "wb") as f:
        pickle.dump(rows, f, protocol=pickle.HIGHEST_PROTOCOL)
    return rows


# ------------------------------------------------------------------------------------------------ I1 - I3
def solo_pick(rows: list, unds, lo: date, hi: date) -> list:
    """SoloMidday on `unds`: the day's S1 signals, strongest first (ties A-Z), one a day. Expiry days were never
    candidates (r57setups.days_in), so an index on its expiry day passes the turn."""
    by = defaultdict(list)
    for r in rows:
        if r["setup"] == "S1" and r["und"] in unds and lo <= r["day"] <= hi:
            by[r["day"]].append(r)
    return [sorted(v, key=lambda r: (-r["strength"], r["und"]))[0] for _, v in sorted(by.items())]


# ------------------------------------------------------------------------------------------------ I4 / I5
def a2_scores(rows: list, months: list, cal: list):
    """A2's purged walk-forward scores and fold thresholds over `months` (R57 run_a, with SENSEX's one-hot)."""
    names = M.names_a(False)
    X = M.matrix([r["f"] for r in rows], names)
    y = np.array([r["y_win"] for r in rows])
    days = np.array([r["day"] for r in rows], dtype=object)
    sc, thr, _ = M.walk(days, X, y, A2["kind"], months, cal)
    tested = np.isfinite(sc)
    return sc, thr, dict(auc=M.auc(y[tested], sc[tested]), rows=len(rows), tested=int(tested.sum()), features=names)


def a2_take(rows: list, sc, thr, only=None) -> list:
    """R57 select_a over all rows, or only the rows of index `only` (I5)."""
    if only is None:
        return T.select_a(rows, sc, thr, A2["kind"])
    ix = [i for i, r in enumerate(rows) if r["und"] == only]
    return T.select_a([rows[i] for i in ix], sc[ix], thr[ix], A2["kind"])


def run_all(rows: list, months: list, cal: list, lo: date, hi: date) -> dict:
    """Trades of I1-I5 on [lo, hi] (I4/I5 scored on `months`)."""
    sc, thr, info = a2_scores(rows, months, cal)
    return {"I1": solo_pick(rows, ("SENSEX",), lo, hi), "I2": solo_pick(rows, ("MIDCPNIFTY",), lo, hi),
            "I3": solo_pick(rows, C.IDX_UNDS, lo, hi), "I4": a2_take(rows, sc, thr),
            "I5": a2_take(rows, sc, thr, only="SENSEX")}, info


def twin_p(trades: list, n: int = C.TWINS):
    """R57 twin test (09:20-14:00, coin-flip side, the setup's strike rule and exits)."""
    return T.twin_p(trades, "A", n)


def capital(trades: list) -> dict:
    x = [t["rE"] * t["qty"] for t in trades]
    return dict(median=round(float(np.median(x))), max=round(float(np.max(x)))) if x else dict(median=None, max=None)


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or C.IDX_UNDS:
            print(r57data.build(u), flush=True)
