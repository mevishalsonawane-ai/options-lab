"""R29 Steps 1-2 (PREREG.md): mine the 140 candidates on discovery (2021-22), rank on selection (2024), survivors.

    python3 research/hunt/r29/mine.py events          # pattern events of every index (features only, no outcome)
    python3 research/hunt/r29/mine.py trades [UND..]  # simulate discovery + selection events, gate, 20 twins each
    python3 research/hunt/r29/mine.py rank            # candidate table, BH, survivors -> results/mining.json, mining.csv

Reads only the parts "discovery" and "selection" (r29data.check_part refuses anything else here).
"""
from __future__ import annotations

import json
import sys

import numpy as np
import pandas as pd
from scipy import stats

import r29cfg as K
import r29data as D
import engine as EN
import patterns as PT
import sim as S

MINE_PARTS = K.DESIGN


def events(unds=K.UNDS):
    for u in unds:
        M = D.load_index(u)
        E = PT.events(M, u)
        E.to_parquet(K.OUT / f"events_{u}.parquet")
        B = PT.ema_cross_events(M, u)
        B.to_parquet(K.OUT / f"events_B0_{u}.parquet")
        print(u, len(E), "events;", len(B), "B0 events", flush=True)


def trades(unds=K.UNDS):
    for u in unds:
        op = D.load_options(u)
        if op is None:
            continue
        M = D.load_index(u)
        E = pd.read_parquet(K.OUT / f"events_{u}.parquet")
        E["day"] = pd.to_datetime(E.day).dt.date
        E = pd.concat([EN.events_for(u, p, E) for p in MINE_PARTS])
        T = EN.simulate_events(u, E, M, op)
        G = EN.gate(T)
        tn, tm = EN.twins(u, G, M, op, K.TWINS_MINE)
        G["twin_net"] = np.nanmean(tn, axis=1)
        G["twin_mv30"] = np.nanmean(tm, axis=1)
        G["twin_n"] = np.isfinite(tn).sum(axis=1)
        G.to_parquet(K.OUT / f"mine_trades_{u}.parquet")
        print(u, "events", len(E), "trades(ungated x modes)", len(T), "gated", len(G), flush=True)


def day_clustered_p(adj: np.ndarray, key: np.ndarray) -> tuple[float, float, int]:
    """One-sided p (H1: mean > 0) of a t-test on per-day means (days = clusters). Returns (t, p, n_days)."""
    s = pd.Series(adj).groupby(key).mean().dropna()
    n = len(s)
    if n < 3 or s.std(ddof=1) == 0:
        return np.nan, np.nan, n
    t = s.mean() / (s.std(ddof=1) / np.sqrt(n))
    return float(t), float(stats.t.sf(t, n - 1)), n


def bh(p: np.ndarray) -> np.ndarray:
    """Benjamini-Hochberg q-values (NaN p stays NaN and is not counted)."""
    p = np.asarray(p, float)
    ok = np.isfinite(p)
    q = np.full(len(p), np.nan)
    m = ok.sum()
    if not m:
        return q
    ps = p[ok]
    o = np.argsort(ps)
    r = ps[o] * m / np.arange(1, m + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    qq = np.empty(m)
    qq[o] = np.minimum(r, 1.0)
    q[ok] = qq
    return q


def summarise(G: pd.DataFrame) -> dict:
    adj = G.ATM_X1_net - G.twin_net
    t, p, nd = day_clustered_p(adj.values, (G.und.astype(str) + "|" + G.day.astype(str)).values)
    out = dict(n=len(G), days=nd, lift30=float(np.nanmean(G.mv30 - G.twin_mv30)) if len(G) else np.nan,
               mv15=float(G.mv15.mean()), mv30=float(G.mv30.mean()), mv60=float(G.mv60.mean()),
               mfe60=float(G.mfe60.mean()), mae60=float(G.mae60.mean()),
               exp=float(G.ATM_X1_net.mean()), twin=float(G.twin_net.mean()), adj=float(adj.mean()),
               win=float((G.ATM_X1_net > 0).mean()), t=t, p=p)
    for opt in EN.OPTS:
        for x in S.EXIT_NAMES:
            out[f"{opt}_{x}"] = float(G[f"{opt}_{x}_net"].mean())
    return out


def rank():
    G = pd.concat([pd.read_parquet(K.OUT / f"mine_trades_{u}.parquet") for u in K.UNDS
                   if (K.OUT / f"mine_trades_{u}.parquet").exists()], ignore_index=True)
    assert set(G.part) <= set(MINE_PARTS), set(G.part)
    rows = []
    for (pat, sign, mode), g in G.groupby(["pattern", "sign", "mode"]):
        r = dict(pattern=pat, sign="bull" if sign > 0 else "bear", mode=mode)
        for part in MINE_PARTS:
            for k, v in summarise(g[g.part == part]).items():
                r[f"{part}_{k}"] = v
        for u in K.UNDS:
            gs = g[(g.und == u) & (g.part == "selection")]
            r[f"sel_adj_{u}"] = float((gs.ATM_X1_net - gs.twin_net).mean()) if len(gs) else np.nan
            r[f"sel_n_{u}"] = len(gs)
        rows.append(r)
    R = pd.DataFrame(rows)
    assert len(R) <= 140
    R["selection_q"] = bh(R.selection_p.values)
    R["survivor"] = ((R.discovery_adj > 0) & (R.selection_adj > 0) & (R.selection_n >= 100)
                     & (R.selection_q <= 0.10))
    R = R.sort_values("selection_adj", ascending=False).reset_index(drop=True)
    K.RESULTS.mkdir(exist_ok=True)
    R.to_csv(K.RESULTS / "mining.csv", index=False, float_format="%.4g")
    base = {part: summarise_random(G[G.part == part]) for part in MINE_PARTS}
    out = dict(candidates=len(R), survivors=int(R.survivor.sum()),
               trades={p: int((G.part == p).sum()) for p in MINE_PARTS},
               random_baseline=base,
               top=R.head(20)[["pattern", "sign", "mode", "discovery_n", "discovery_adj", "selection_n",
                               "selection_adj", "selection_exp", "selection_lift30", "selection_p",
                               "selection_q", "survivor"]].to_dict("records"),
               survivor_list=R[R.survivor][["pattern", "sign", "mode", "selection_adj", "selection_q"]]
               .to_dict("records"))
    (K.RESULTS / "mining.json").write_text(json.dumps(out, indent=1, default=float))
    print(json.dumps({k: out[k] for k in ("candidates", "survivors", "trades", "random_baseline")}, indent=1,
                     default=float))
    return R


def summarise_random(G: pd.DataFrame) -> dict:
    """What a random-minute trade made in the same period (the twins' mean), for the 'costs vs edge' line."""
    return dict(twin_net_mean=float(G.twin_net.mean()) if len(G) else np.nan, trades=len(G))


if __name__ == "__main__":
    cmd = sys.argv[1]
    args = sys.argv[2:] or K.UNDS
    {"events": events, "trades": trades}.get(cmd, lambda *_: rank())(args) if cmd != "rank" else rank()
