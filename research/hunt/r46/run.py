"""R46 run (PREREG.md). Research only; nothing in the app trades crypto.

    python -m research.hunt.r46.run design      design period only (the holdout's bars are never loaded)
    python -m research.hunt.r46.run holdout     the ONE holdout read (refuses a second); also the claimed window

Writes results/<part>.json (statistics only, no market data) and caches pools in R46_OUT (never git).
"""
from __future__ import annotations

import json
import sys

import numpy as np
import pandas as pd

from . import book as B
from . import data as D
from . import ind
from . import nn
from . import pool as PL
from . import r46cfg as K
from .exits import from_cfg

EXITS = {k: from_cfg(v) for k, v in K.OPT_EXITS.items()}
PERP_EX = from_cfg(K.PERP_EXIT)


def span(part: str):
    return K.DESIGN if part == "design" else K.HOLDOUT


def underlying(end: pd.Timestamp):
    spot = D.load_1m("spot", end)
    perp = D.load_1m("perp", end)
    b5 = ind.to_5min(spot)
    S = ind.signals(b5)
    F = ind.nn_features(b5, S, perp.close.groupby(perp.index.floor("5min")).last())
    y = ind.nn_label(spot.close, pd.DatetimeIndex(S.entry))
    return spot, perp, b5, S, F, y


def option_pools(spot: pd.DataFrame, part: str) -> pd.DataFrame:
    a, b = span(part)
    path = K.OUT / f"pool_{part}.parquet"
    if path.exists():
        return pd.read_parquet(path)
    so = spot.open
    out = []
    E = (a - pd.Timedelta(days=1)).normalize() + pd.Timedelta(hours=K.EXPIRY_H_UTC)
    last = b.normalize() + pd.Timedelta(days=1, hours=K.EXPIRY_H_UTC)
    while E <= last:
        ent = D.entries_for_expiry(E)
        if ent[-1] >= a and ent[0] < b:
            s = so[(so.index >= ent[0]) & (so.index <= E)]
            day = D.OptDay(E, spot_lohi=(s.min(), s.max()) if s.notna().any() else None)
            if day.ok:
                for typ in ("C", "P"):
                    r = PL.option_pool_day(day, so, typ, EXITS)
                    if len(r):
                        out.append(r)
        E += pd.Timedelta(days=1)
    P = pd.concat(out, ignore_index=True)
    P = P[(P.entry >= a) & (P.entry < b) & ((~P.ok) | (P.exit_ts < b))]
    K.OUT.mkdir(parents=True, exist_ok=True)
    P.to_parquet(path, index=False)
    return P


def perp_pools(perp: pd.DataFrame, part: str) -> dict:
    a, b = span(part)
    ent = pd.date_range(a, b - pd.Timedelta(minutes=K.HOLD_MIN + K.BAR_MIN), freq=f"{K.BAR_MIN}min")
    out = {}
    for side in (1, -1):
        P = PL.perp_pool(perp, ent, side, PERP_EX)
        P = P[(~P.ok) | (P.exit_ts < b)]
        out["L" if side > 0 else "S"] = P.set_index("entry")
    return out


def nn_probs(S: pd.DataFrame, F: pd.DataFrame, y: np.ndarray, part: str):
    a, b = span(part)
    months = list(pd.date_range(a, b - pd.Timedelta(days=1), freq="MS"))
    q = (S.call | S.put)
    prob, log = nn.walk_forward(F, y, pd.DatetimeIndex(S.entry), months, q)
    return prob, log


def variants(S: pd.DataFrame, prob: pd.Series, part: str):
    """Signal entry minutes per (signal variant, side)."""
    a, b = span(part)
    inside = (S.entry >= a) & (S.entry < b)
    trained = prob.notna()
    v = {}
    for side, col in (("C", "call"), ("P", "put")):
        base = S[col] & inside
        v[("A", side)] = pd.DatetimeIndex(S.entry[base])
        nn_ok = (prob > K.NN_THRESHOLD) if side == "C" else (prob < 1 - K.NN_THRESHOLD)
        v[("B", side)] = pd.DatetimeIndex(S.entry[base & trained & nn_ok])
        v[("A@B", side)] = pd.DatetimeIndex(S.entry[base & trained])      # A on the months B could trade
    return v


def days_in(a, b) -> int:
    return int((b - a) / pd.Timedelta(days=1))


def run(part: str) -> dict:
    a, b = span(part)
    if part == "holdout":
        D.open_holdout("r46")
    spot, perp, b5, S, F, y = underlying(b)
    P = option_pools(spot, part)
    PP = perp_pools(perp, part)
    prob, nnlog = nn_probs(S, F, y, part)
    sig = variants(S, prob, part)
    nd = days_in(a, b)
    res = dict(part=part, span=[str(a), str(b)], days=nd, variants={}, extra={}, nn_log=nnlog)
    sizes = []
    for (sv, side), entries in sig.items():
        for ex in EXITS:
            for mode in PL.MODES:
                pool = P[(P.side == side) & (P["mode"] == mode) & (P.exit == ex)].set_index("entry")
                T = B.select(entries, pool)
                key = f"{sv}-{'call' if side == 'C' else 'put'}-{ex}-{mode}"
                s1 = B.summary(T, nd)
                s1["claim_size"] = B.summary(T, nd, K.CLAIM_CONTRACTS)
                s1["signals"] = int(len(entries))
                s1["unfilled"] = int(sum(1 for e in entries if e in pool.index and not bool(pool.loc[e].ok)) if
                                     len(entries) else 0)
                if mode == "real" and sv in ("A", "B"):
                    p, tw = B.twin_p(T, pool)
                    s1["twin_p"] = p
                    s1["twin_mean"] = round(float(np.nanmean(tw)), 4) if len(tw) else None
                    s1["quarters"] = {str(q): round(float(g.net_after.sum()), 2)
                                      for q, g in T.groupby(T.index.to_period("Q"))} if len(T) else {}
                    win = T[(T.index + K.IST).hour < K.BOSS_WINDOW_IST[1]] if len(T) else T
                    s1["boss_window"] = B.summary(win, nd)
                    if part == "holdout":
                        c = T[(T.index >= K.CLAIM[0]) & (T.index < K.CLAIM[1])] if len(T) else T
                        s1["claim_window"] = B.summary(c, days_in(*K.CLAIM))
                        s1["claim_window"]["claim_size"] = B.summary(c, days_in(*K.CLAIM), K.CLAIM_CONTRACTS)
                    if len(T):
                        sizes.append(T.buy.to_numpy(float))
                res["variants"][key] = s1
    for side, pool in PP.items():
        es = sig[("A", "C" if side == "L" else "P")]
        T = B.select(es, pool)
        s1 = B.summary(T, nd)
        p, tw = B.twin_p(T, pool)
        s1["twin_p"] = p
        s1["quarters"] = {str(q): round(float(g.net_after.sum()), 2)
                          for q, g in T.groupby(T.index.to_period("Q"))} if len(T) else {}
        if part == "holdout":
            c = T[(T.index >= K.CLAIM[0]) & (T.index < K.CLAIM[1])] if len(T) else T
            s1["claim_window"] = B.summary(c, days_in(*K.CLAIM))
        res["variants"][f"A-perp{'long' if side == 'L' else 'short'}-P1-real"] = s1
    # family: 12 option variants (A/B x call/put x L1-L3) + 2 perp, on this part's twin p
    fam = [k for k in res["variants"] if k.endswith("-real") and not k.startswith("A@B")]
    q = B.bh([res["variants"][k].get("twin_p", np.nan) for k in fam])
    for k, qq in zip(fam, q):
        res["variants"][k]["q"] = None if not np.isfinite(qq) else round(float(qq), 4)
    # baselines and measurements
    s0, s1_ = spot.open.loc[a:].dropna(), spot.close.loc[:b - pd.Timedelta(minutes=1)].dropna()
    g = float(s1_.iloc[-1] - s0.iloc[0])
    fees = 0.001 * (s0.iloc[0] + s1_.iloc[-1])
    res["extra"]["buy_hold_1btc"] = dict(start=round(float(s0.iloc[0]), 2), end=round(float(s1_.iloc[-1]), 2),
                                         gross=round(g, 2), after_fees_tax=round(g - fees - K.TAX * max(g, 0), 2))
    if sizes:
        z = np.concatenate(sizes)
        res["extra"]["premium_per_btc"] = dict(n=int(len(z)), median=round(float(np.median(z)), 2),
                                               p10=round(float(np.quantile(z, 0.1)), 2),
                                               p90=round(float(np.quantile(z, 0.9)), 2))
    pr = prob[(S.call | S.put).to_numpy() & prob.notna().to_numpy()]
    res["extra"]["nn_prob_at_signals"] = dict(n=int(len(pr)), above_065=int((pr > 0.65).sum()),
                                              below_035=int((pr < 0.35).sum()),
                                              mean=round(float(pr.mean()), 4) if len(pr) else None)
    okp = P[(P["mode"] == "real") & (P.exit == "L1")]
    res["extra"]["fillable_share"] = {s: round(float(okp[okp.side == s].ok.mean()), 4) for s in ("C", "P")}
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / f"{part}.json").write_text(json.dumps(res, indent=1, default=str))
    return res


def main(argv=None):
    a = argv or sys.argv[1:]
    if not a or a[0] not in ("design", "holdout"):
        print(__doc__)
        return 2
    if a[0] == "holdout" and not (K.RESULTS / "design.json").exists():
        raise D.HoldoutError("run and commit the design results first")
    run(a[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
