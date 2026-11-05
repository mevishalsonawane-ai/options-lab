"""R149 (PREREG.md): Night's signal on the nights it skips for expiry, priced three ways (E1-E3).

    python -m research.hunt.r149_night_expiry.study build     # <OUT>/r149_trades.pkl (needs R147's nights + the lake)
    python -m research.hunt.r149_night_expiry.study run       # results/results.json
"""
from __future__ import annotations

import hashlib
import json
import pickle
import sys
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r147_night_plus import cfg as K7
from research.hunt.r147_night_plus import data as D7
from research.hunt.r147_night_plus import study as S7
from research.hunt.r148_night_enhance.study import BOOK, costs_net

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
OUT = K7.OUT / "r149_trades.pkl"
SEED, TWINS = 149, 2000
KEEP = lambda o: (o.m < K7.MORN + 4) | (o.m >= K7.R1519 - 9) | (o.m == K7.R1515)  # noqa: E731


def prereg_sha() -> str:
    return hashlib.sha256((HERE / "PREREG.md").read_bytes()).hexdigest()


def choose(why: str, d, nd, month_exp: set, und: str) -> dict:
    """Which variants apply to a skipped night, and with which series: {variant: 'TRADE' | 'MONTH'}."""
    out = {}
    month_ok = d not in month_exp and nd not in month_exp
    if why == "contract_expires_next_session":
        out["E1"] = "TRADE"
        if month_ok and K7.TRADE_SERIES[und] != "MONTH":
            out["E2"] = "MONTH"
    elif why == "entry_on_expiry_day" and month_ok:
        out["E3"] = "MONTH"
    return out


def build() -> None:
    rows = []
    for und, rule in BOOK.items():
        recs = pickle.loads((K7.OUT / f"nights_{und}.pkl").read_bytes())
        ixs = D7.index_days(und)
        ixc = pd.Series({(d, m): a[m, K7.C] for d, a in ixs.items() for m in range(K7.N) if np.isfinite(a[m, K7.C])})
        trade = D7.opt_frames(und, K7.TRADE_SERIES[und], KEEP, ixc)
        month = trade if K7.TRADE_SERIES[und] == "MONTH" else D7.opt_frames(und, "MONTH", KEEP, ixc)
        month_exp = D7.expiry_days(D7.tv_1515(month))
        by = {"TRADE": dict(tuple(trade.groupby("d"))), "MONTH": dict(tuple(month.groupby("d")))}
        for r in recs:
            side = r[rule]
            if r["tradable"] or side == 0 or not r["why"]:
                continue
            for var, ser in choose(r["why"], r["d"], r["nd"], month_exp, und).items():
                g0, g1 = by[ser].get(r["d"]), by[ser].get(r["nd"])
                rec = {"und": und, "d": r["d"], "nd": r["nd"], "part": r["part"], "var": var, "side": side,
                       "qty": r["qty"], "why_skipped": r["why"]}
                for s in (1, -1):
                    k = D7.atm(r["x"], K7.STEP[und]) - s * K7.STEP[und]
                    e, x, w = (np.nan, np.nan, "no_series") if g0 is None or g1 is None else D7.price_leg(g0, g1, k, s)
                    rec[f"e{s}"], rec[f"x{s}"], rec[f"w{s}"] = e, x, w
                rows.append(rec)
        print(und, "monthly expiries", len(month_exp), "rows", sum(1 for x in rows if x["und"] == und), flush=True)
    OUT.write_bytes(pickle.dumps(rows))


def nets(t: pd.DataFrame, mult: float = 1.0) -> tuple[np.ndarray, np.ndarray]:
    c, p = [], []
    for r in t.itertuples():
        for s, acc in ((1, c), (-1, p)):
            e, x = getattr(r, f"e{s}"), getattr(r, f"x{s}")
            if not (np.isfinite(e) and np.isfinite(x)):
                acc.append(np.nan)
                continue
            v = costs_net(r.und, r.qty, e, x)
            if mult != 1.0:
                v -= (mult - 1) * K7.HALF_SPREAD[r.und] * (e + x) * r.qty
            acc.append(v)
    return np.array(c), np.array(p)


def block(t: pd.DataFrame, rng) -> dict:
    c, p = nets(t)
    cs, ps = nets(t, K7.STRESS)
    ok = np.isfinite(c) & np.isfinite(p)
    s = t.side.to_numpy()
    x = np.where(s > 0, c, p)[ok]
    st = np.where(s > 0, cs, ps)[ok]
    prem = np.array([getattr(r, "e1") if r.side > 0 else getattr(r, "e-1") for r in t.itertuples()], float)[ok] * t.qty.to_numpy()[ok]
    dts = t.d.to_numpy()[ok]
    m = S7.metrics(dts, x, prem)
    m["stress_net_per_trade"] = float(st.mean()) if len(st) else None
    m["unpriced"] = int((~ok).sum())
    m["twin_p"] = S7.twin_side_p(c[ok], p[ok], float(x.sum()), rng) if len(x) else None
    return m


def run() -> None:
    rng = np.random.default_rng(SEED)
    t = pd.DataFrame(pickle.loads(OUT.read_bytes()))
    res = {"prereg_sha256": prereg_sha(), "run": datetime.now().isoformat(timespec="seconds"), "design": {}, "check": {}}
    for part, key in (("design", "design"), ("hold", "check")):
        tp = t[t.part == part]
        for var in ("E1", "E2", "E3"):
            tv = tp[tp["var"] == var]
            res[key][var] = {"pooled": block(tv, rng) if len(tv) else {"trades": 0},
                             "per_index": {u: block(g, rng) for u, g in tv.groupby("und")}}
    pooled = [res["design"][v]["pooled"] for v in ("E1", "E2", "E3")]
    for r, q in zip(pooled, S7.bh([r.get("twin_p") if r.get("twin_p") is not None else np.nan for r in pooled])):
        r["bh_q"] = q
    for v in ("E1", "E2", "E3"):
        dsg, chk = res["design"][v]["pooled"], res["check"][v]["pooled"]
        g = {"design_trades_60": dsg.get("trades", 0) >= 60, "design_stress_pos": (dsg.get("stress_net_per_trade") or -1) > 0,
             "design_years_2of3": sum(1 for y in K7.DESIGN_YEARS if dsg.get("by_year", {}).get(y, 0) > 0) >= 2,
             "design_twin": (dsg.get("twin_p") or 1) <= 0.05, "design_bh": (dsg.get("bh_q") if dsg.get("bh_q") is not None else 1) <= 0.10,
             "check_trades_30": chk.get("trades", 0) >= 30, "check_stress_pos": (chk.get("stress_net_per_trade") or -1) > 0,
             "check_twin": (chk.get("twin_p") or 1) <= 0.05}
        res["design"][v]["gate"], res["design"][v]["pass"] = g, all(g.values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    S7._dump(res, RESULTS / "results.json")


if __name__ == "__main__":
    {"build": build, "run": run}[sys.argv[1]]()
