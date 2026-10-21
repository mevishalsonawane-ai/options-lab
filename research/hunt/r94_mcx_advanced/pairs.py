"""R94 idea 2: sector-index pairing, tested through labelled PROXIES (PREREG 3.2, NOTES N1). Research only.

MCX BULLDEX / METLDEX / ENRGDEX history is not available point in time (PREREG 2), so the pairs inside each index are
tested: GOLD vs SILVER (BULLDEX), COPPER / ZINC / ALUMINIUM (METLDEX; they FAIL Boss's liquidity rule), CRUDEOIL vs
NATURALGAS (ENRGDEX; not an economic pair). Signal source: the two roll-adjusted continuous futures (their log ratio).
Rs 10 lakh notional a leg, equal rupees.

    python -m research.hunt.r94_mcx_advanced.pairs design      # -> results/design_idea2.json
"""
from __future__ import annotations

import pickle
import sys
from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r94_mcx_advanced import common as C

DESIGN = (date(2012, 1, 1), date(2021, 12, 31))
HOLDOUT = (date(2022, 1, 1), date(2026, 10, 16))
FOLDS = [(date(y, 1, 1), date(y, 12, 31)) for y in range(2013, 2022)]
NOTIONAL = 1_000_000.0
PAIRS = {"GS": ("GOLD", "SILVER"), "CZ": ("COPPER", "ZINC"), "CA": ("COPPER", "ALUMINIUM"),
         "ZA": ("ZINC", "ALUMINIUM"), "CN": ("CRUDEOIL", "NATURALGAS")}
LABEL = {"GS": "BULLDEX proxy (liquid)", "CZ": "METLDEX proxy (fails liquidity rule)",
         "CA": "METLDEX proxy (fails liquidity rule)", "ZA": "METLDEX proxy (fails liquidity rule)",
         "CN": "ENRGDEX proxy (not an economic pair)"}
WINDOWS = (60, 120)
ENTRY_Z, STOP_Z, MAX_ROWS = 2.0, 3.5, 20
ROLL_OI = 3.0
# margin as a share of notional (MCX_GUIDE 1a, Zerodha 8 Oct 2026: margin / (price x lot units))
MARGIN_RATE = {"GOLD": 0.0925, "SILVER": 0.1275, "CRUDEOIL": 0.313, "NATURALGAS": 0.167, "COPPER": 0.093,
               "ZINC": 0.0935, "ALUMINIUM": 0.092}


@dataclass
class Leg:
    sym: str
    dates: np.ndarray        # datetime64[ns] normalized
    o: np.ndarray
    c: np.ndarray
    roll: np.ndarray         # True: first row of a new contract
    idx: np.ndarray          # roll-adjusted close index
    idx_open: np.ndarray     # roll-adjusted index at the open


def load_daily(sym: str, lake=None) -> pd.DataFrame:
    d = pd.read_parquet((lake or C.lake_dir()) / "fut_daily_continuous" / "fut_daily_continuous.parquet")
    d = d[(d.symbol == sym) & (d.expiry_code == 0)].copy()
    d["date"] = pd.to_datetime(d.date).dt.normalize()
    d = d[(d.close > 0) & (d.open > 0)].drop_duplicates("date", keep="last").sort_values("date")
    return d[["date", "open", "high", "low", "close", "volume", "oi"]].reset_index(drop=True)


def mark_rolls(oi) -> np.ndarray:
    """A roll row: open interest > 3x the previous row's (the expired contract was replaced; PREREG 3.2)."""
    oi = np.asarray(oi, dtype=float)
    r = np.full(len(oi), np.nan)
    with np.errstate(divide="ignore", invalid="ignore"):
        r[1:] = oi[1:] / oi[:-1]
    return np.isfinite(r) & (r > ROLL_OI)


def make_leg(sym: str, d: pd.DataFrame) -> Leg:
    o, c = d.open.to_numpy(float), d.close.to_numpy(float)
    roll = mark_rolls(d.oi.to_numpy(float))
    ret = np.zeros(len(c))
    ret[1:] = np.where(roll[1:], c[1:] / o[1:] - 1, c[1:] / c[:-1] - 1)
    idx = np.cumprod(1 + ret)
    idx_open = np.empty(len(c))
    idx_open[0] = idx[0] * o[0] / c[0]
    idx_open[1:] = np.where(roll[1:], idx[:-1], idx[:-1] * o[1:] / c[:-1])
    return Leg(sym, d.date.values, o, c, roll, idx, idx_open)


def align(a: Leg, b: Leg) -> pd.DataFrame:
    """Common dates; the roll-adjusted levels (no return is lost on a date one leg lacks)."""
    A = pd.DataFrame({"date": a.dates, "ia": a.idx, "ioa": a.idx_open, "ra": a.roll, "pa": a.o})
    B = pd.DataFrame({"date": b.dates, "ib": b.idx, "iob": b.idx_open, "rb": b.roll, "pb": b.o})
    m = A.merge(B, on="date").sort_values("date").reset_index(drop=True)
    # a roll on a date the other leg lacks is carried to the next common date (it still costs a round trip)
    for col, leg in (("ra", a), ("rb", b)):
        rolled = pd.Series(leg.roll, index=pd.DatetimeIndex(leg.dates))
        cum = rolled.cumsum()
        at = cum.reindex(pd.DatetimeIndex(m.date)).to_numpy()
        m[col] = np.r_[False, np.diff(at) > 0]
    m["r"] = np.log(m.ia / m.ib)
    return m


def add_z(m: pd.DataFrame, w: int) -> pd.DataFrame:
    m = m.copy()
    prev = m.r.shift(1)
    mu = prev.rolling(w, min_periods=w).mean()
    sd = prev.rolling(w, min_periods=w).std(ddof=1)
    m["sd"] = sd
    m["z"] = (m.r - mu) / sd
    return m


def _cost(sym: str, price: float) -> float:
    """One round trip of Rs 10 lakh notional on one leg: charges + two half-spreads (rupees)."""
    hs = C.fut_half_spread(sym, price) / price
    return C.fut_leg_charges(NOTIONAL, NOTIONAL) + 2 * hs * NOTIONAL


def _trade(pid, m, e, x, side, why) -> dict:
    """side +1 = long A / short B; filled at the open of row e, closed at the open of row x."""
    a, b = PAIRS[pid]
    ra = m.ioa.values[x] / m.ioa.values[e] - 1
    rb = m.iob.values[x] / m.iob.values[e] - 1
    gross = side * NOTIONAL * (ra - rb)
    rolls_a = int(m.ra.values[e + 1:x + 1].sum())
    rolls_b = int(m.rb.values[e + 1:x + 1].sum())
    cost = (1 + rolls_a) * _cost(a, m.pa.values[e]) + (1 + rolls_b) * _cost(b, m.pb.values[e])
    sd = m.sd.values[e - 1] if e >= 1 else np.nan
    risk = (STOP_Z - ENTRY_Z) * sd * NOTIONAL + _cost(a, m.pa.values[e]) + _cost(b, m.pb.values[e])
    return {"entry_ts": pd.Timestamp(m.date.values[e]), "exit_ts": pd.Timestamp(m.date.values[x]), "side": int(side),
            "gross": float(gross), "net": float(gross - cost), "costs": float(cost), "why": why, "e": int(e),
            "x": int(x), "hold_rows": int(x - e), "rolls": rolls_a + rolls_b, "risk_rs": float(risk),
            "margin_rs": NOTIONAL * (MARGIN_RATE[a] + MARGIN_RATE[b])}


def _manage(pid, m, e, side, hold=None):
    """Position filled at the open of row e (signal at the close of e - 1). Exits at the next open after the close that
    triggers them: the stop (|z| >= 3.5 on the adverse side), z crossing 0 (or the twin's holding time), 20 rows."""
    Z = m.z.values
    n = len(Z)
    for t in range(e, n - 1):
        z = Z[t]
        if np.isfinite(z) and ((side == -1 and z >= STOP_Z) or (side == 1 and z <= -STOP_Z)):
            return _trade(pid, m, e, t + 1, side, "stop")
        if hold is not None:
            if t + 1 - e >= hold:
                return _trade(pid, m, e, t + 1, side, "hold")
        elif np.isfinite(z) and ((side == -1 and z <= 0) or (side == 1 and z >= 0)):
            return _trade(pid, m, e, t + 1, side, "signal")
        if t + 1 - e >= MAX_ROWS:
            return _trade(pid, m, e, t + 1, side, "time")
    return None


def entry_side(z: float) -> int:
    """z >= +2: short A / long B (-1); z <= -2: long A / short B (+1); else none."""
    if not np.isfinite(z):
        return 0
    return -1 if z >= ENTRY_Z else (1 if z <= -ENTRY_Z else 0)


def simulate(pid, m, window) -> list[dict]:
    a, b = pd.Timestamp(window[0]), pd.Timestamp(window[1])
    out, t, n = [], 0, len(m)
    D, Z = m.date.values, m.z.values
    while t < n - 1:
        side = entry_side(Z[t])
        if side and a <= pd.Timestamp(D[t + 1]) <= b:
            tr = _manage(pid, m, t + 1, side)
            if tr is None:
                break
            out.append(tr)
            t = tr["x"]              # flat from the open of row x; a new signal may come at its close
            continue
        t += 1
    return out


def valid_entries(m, window) -> np.ndarray:
    a, b = np.datetime64(pd.Timestamp(window[0])), np.datetime64(pd.Timestamp(window[1]))
    D = m.date.values
    ok = np.zeros(len(m), dtype=bool)
    ok[1:] = np.isfinite(m.z.values[:-1]) & (D[1:] >= a) & (D[1:] <= b)
    return np.nonzero(ok)[0]           # fill rows


def twins(pid, m, window, trades, n_sets=C.N_TWINS, seed=C.SEED) -> np.ndarray:
    if not trades:
        return np.array([])
    rng = np.random.default_rng(seed)
    cand = valid_entries(m, window)
    holds = np.array([t["hold_rows"] for t in trades])
    cache, tot = {}, np.zeros(n_sets)
    for s in range(n_sets):
        e = rng.choice(cand, size=len(trades))
        sd = rng.choice([-1, 1], size=len(trades))
        h = rng.choice(holds, size=len(trades))
        x = 0.0
        for ee, ss, hh in zip(e, sd, h):
            k = (int(ee), int(ss), int(hh))
            if k not in cache:
                tr = _manage(pid, m, int(ee), int(ss), hold=int(hh))
                cache[k] = tr["net"] if tr else 0.0
            x += cache[k]
        tot[s] = x
    return tot


def cells(part: str, n_sets: int = C.N_TWINS) -> dict:
    window = DESIGN if part == "design" else HOLDOUT
    legs = {s: make_leg(s, load_daily(s)) for s in {x for p in PAIRS.values() for x in p}}
    out = {}
    for pid, (a, b) in PAIRS.items():
        m0 = align(legs[a], legs[b])
        for w in WINDOWS:
            m = add_z(m0, w)
            tr = simulate(pid, m, window)
            tw = twins(pid, m, window, tr, n_sets=n_sets)
            s = C.summary(tr)
            out[f"p-{pid}-W{w}"] = {
                "trades": tr, "p": C.twin_p(s["net"], tw) if tr else 1.0,
                "folds": C.fold_nets(tr, FOLDS) if part == "design" else [],
                "extra": {"label": LABEL[pid], "rows": int(((m.date >= pd.Timestamp(window[0]))
                                                           & (m.date <= pd.Timestamp(window[1]))).sum()),
                          "exits": pd.Series([t["why"] for t in tr]).value_counts().to_dict() if tr else {},
                          "twin_mean": float(np.mean(tw)) if len(tw) else None,
                          "rolls_paid": int(sum(t["rolls"] for t in tr)),
                          "liquid": a in C.LIQUID and b in C.LIQUID}}
    return out


def main(argv):
    if argv[:1] != ["design"]:
        print(__doc__)
        return 2
    res = cells("design")
    with open(C.cache_dir() / "idea2_design_trades.pkl", "wb") as f:
        pickle.dump({k: v["trades"] for k, v in res.items()}, f)
    C.save("design_idea2.json", {k: {"summary": C.summary(v["trades"]), "p": v["p"], "folds": v["folds"],
                                     "extra": v["extra"]} for k, v in res.items()})
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
