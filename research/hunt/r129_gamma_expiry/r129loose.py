"""R129 amendment 1 (PREREG_LOOSE.md): a looser gamma expiry rule, on the Drive lake.

    python research/hunt/r129_gamma_expiry/r129loose.py      # -> results/loose.json (+ trades in $R129_OUT/loose_*.parquet)

The nearest 2x OI wall each way (within 5 strikes, at 13:45); no VIX floor; a 5-minute close beyond the wall and the TWAP from
13:50 to 15:00; 2 of 3 heavyweights beyond VWAP; the nearest OTM option at Rs 10-40; R129's trigger, exits, twins and gates.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r129 as G  # noqa: E402

K, D3, C7 = G.K, G.D3, G.C7
N = G.N
O_, H_, L_, C_, V_ = range(5)
IMB = 2.0
WALL_RANGE = 5
WIN0 = K.row(13, 45)                        # bars closing 13:50 .. 15:00 (last rows 13:49 .. 14:59)
CORRIDOR = (10.0, 40.0)
HEAVY_NEED = 2
VARIANTS = {"L1": dict(trig="option", cap="1525"), "L2": dict(trig="index", cap="1525"),
            "L3": dict(trig="index", cap="1505"), "L4": dict(trig="none", cap="1525")}


def walls(oi: dict, spot: float, step: float) -> dict:
    """{+1: bullish wall strike or None, -1: bearish wall strike or None}: the nearest strike at/above (below) spot within
    WALL_RANGE strikes whose call (put) OI is >= 2x its put (call) OI."""
    out = {1: None, -1: None}
    ups = sorted(k for k in oi if spot <= k <= spot + WALL_RANGE * step + 1e-9)
    dns = sorted((k for k in oi if spot - WALL_RANGE * step - 1e-9 <= k <= spot), reverse=True)
    for k in ups:
        c, p = (np.nan_to_num(x) for x in oi[k])
        if c > 0 and c >= IMB * p:
            out[1] = k
            break
    for k in dns:
        c, p = (np.nan_to_num(x) for x in oi[k])
        if p > 0 and p >= IMB * c:
            out[-1] = k
            break
    return out


def setup_loose(c: np.ndarray, wall: float, d: int):
    """The first 5-minute bar closing 13:50..15:00 whose close is beyond the wall in direction d and beyond the TWAP; the
    13:44 close must be on the near side. -> its last row or None."""
    c44 = D3._last(c[:G.ROW_WALL + 1])
    if not np.isfinite(c44) or (d > 0 and c44 > wall) or (d < 0 and c44 < wall):
        return None
    for e in range(WIN0 + 4, G.WIN[1] + 1, 5):
        ce = D3._last(c[e - 4:e + 1])
        if not np.isfinite(ce):
            continue
        twap = np.nanmean(c[:e + 1])
        if (d > 0 and ce > wall and ce > twap) or (d < 0 and ce < wall and ce < twap):
            return e
    return None


def heavy_ok(equity: dict, names, d, e5: int, dr: int) -> bool:
    """At least HEAVY_NEED of [names] beyond their VWAP the trade's way at row e5 (no names: True)."""
    if not names:
        return True
    good = 0
    for s in names:
        q = equity.get((s, d))
        if q is None or not (np.isfinite(q[0][e5]) and np.isfinite(q[1][e5])):
            continue
        if (dr > 0 and q[0][e5] > q[1][e5]) or (dr < 0 and q[0][e5] < q[1][e5]):
            good += 1
    return good >= HEAVY_NEED


def run_index(und: str, equity: dict, log=print):
    g = D3.load_grid(und, K.HOLDOUT[1])
    vix = D3.load_grid("INDIAVIX", K.HOLDOUT[1])
    vpos = vix.pos()
    LO = D3.LakeOptions(und, K.HOLDOUT[1])
    exp = sorted(LO.expiry_days())
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(G.SEED + 50 + K.UNDS.index(und))
    pos = g.pos()
    trades, days = [], []
    for d in exp:
        i = pos.get(d)
        if i is None:
            continue
        rec = dict(und=und, day=d, stop=None)
        days.append(rec)
        h, l, c = g.h[i], g.l[i], g.c[i]
        vi = vpos.get(d)
        vx = D3._last(vix.c[vi][:G.ROW_VIX + 1]) if vi is not None else np.nan
        rec["vix"] = float(vx) if np.isfinite(vx) else np.nan
        gf = G.day_frame(LO, d, c)
        if gf is None:
            rec["stop"] = "no options"
            continue
        spot = D3._last(c[:G.ROW_WALL + 1])
        ws = walls(G.oi_at(gf, G.ROW_WALL, spot, step), spot, step)
        if ws[1] is None and ws[-1] is None:
            rec["stop"] = "no 2x wall"
            continue
        cand = [(setup_loose(c, ws[dd], dd), dd) for dd in (1, -1) if ws[dd] is not None]
        cand = [(e, dd) for e, dd in cand if e is not None]
        if not cand:
            rec["stop"] = "no break"
            continue
        e5, dr = min(cand)
        wall = ws[dr]
        rec.update(setup=True, dir=dr, wall_steps=float(abs(wall - spot) / step))
        cs, c1 = D3._last(c[:e5 + 1]), D3._last(c[:K.row(15, 19) + 1])
        rec["move_after"] = float(dr * (c1 - cs)) if np.isfinite(cs) and np.isfinite(c1) else np.nan
        if not heavy_ok(equity, G.HEAVY[und], d, e5, dr):
            rec["stop"] = "heavyweights"
            continue
        side = "C" if dr > 0 else "P"
        atm = D3.atm(cs, step)
        a = k = None
        for n in range(1, G.NEAR_MAX + 1):
            kk = float(atm + dr * step * n)
            x = gf[(gf.side == side) & (gf.strike == kk)]
            if x.empty:
                continue
            arr = np.full((N, 5), np.nan)
            for j, col in enumerate(("open", "high", "low", "close", "volume")):
                arr[x.m.values, j] = x[col].values.astype(float)
            arr[~(arr[:, V_] > 0)] = np.nan
            px = D3._last(arr[:e5 + 1, C_])
            if np.isfinite(px) and CORRIDOR[0] <= px <= CORRIDOR[1]:
                a, k = arr, kk
                break
        if a is None:
            rec["stop"] = "corridor"
            continue
        s7i, s10i = G.index_st(h, l, c)
        s7o, s10o, idx = G.traded_st(a)
        for v, cfg in VARIANTS.items():
            cap = G.CAPS[cfg["cap"]]
            if cfg["trig"] == "option":
                def flip(r, s7o=s7o, s10o=s10o):
                    return s7o[r] == -1 or s10o[r] == -1
            else:
                def flip(r):
                    return s7i[r] == -dr or s10i[r] == -dr
            if cfg["trig"] == "none":
                m = e5
            else:
                m = G.trigger(a, idx, s7o, s10o, s7i, s10i, dr, e5, G.WIN[1], cfg["trig"])
            if m is None:
                continue
            nxt = [x for x in idx if m < x <= cap]
            if not nxt:
                continue
            e = int(nxt[0])
            w = G.walk(a, e, cap, flip)
            if w is None:
                continue
            tn = G.trade_net(a, e, w[0], w[1], lot, d, und)
            if tn is None:
                continue
            tw = []
            pool = [x for x in idx if WIN0 <= x <= min(G.WIN[1] + 1, cap)]
            for _ in range(G.TWINS):
                if not pool:
                    tw.append(np.nan)
                    continue
                e2 = int(rng.choice(pool))
                w2 = G.walk(a, e2, cap, flip)
                t2 = G.trade_net(a, e2, w2[0], w2[1], lot, d, und) if w2 is not None else None
                tw.append(np.nan if t2 is None else t2[0])
            pts = dr * (D3._last(c[:w[1] + 1]) - D3._last(c[:e + 1]))
            trades.append(dict(variant=v, und=und, day=d, side=dr, strike=k, entry_row=e, exit_row=w[1], why=w[2],
                               entry=float(a[e, O_]), exit=float(w[0]), net=tn[0], netx2=tn[1], pts=float(pts),
                               mult=float(w[0] / a[e, O_]), vix=rec["vix"], wall_steps=rec["wall_steps"], twins=tw))
    log(f"{und}: {len(days)} expiry days, {len({t['day'] for t in trades})} days traded")
    return trades, days


def _one(und):
    eq = G.load_equity(sorted({s for v in G.HEAVY.values() for s in v}))
    return run_index(und, eq)


def run():
    from multiprocessing import Pool
    with Pool(len(K.UNDS)) as pool:
        res = pool.map(_one, list(K.UNDS))
    T = pd.DataFrame([t for r in res for t in r[0]])
    Dd = pd.DataFrame([d for r in res for d in r[1]])
    G.OUT.mkdir(parents=True, exist_ok=True)
    if len(T):
        T.drop(columns=["twins"]).to_parquet(G.OUT / "loose_trades.parquet")
    Dd.to_parquet(G.OUT / "loose_days.parquet")
    setup = Dd[Dd.get("setup", pd.Series(False, index=Dd.index)).fillna(False).astype(bool)]
    out = dict(expiry_days=int(len(Dd)),
               stops={str(k): int(v) for k, v in Dd.stop.fillna("reached the trigger stage").value_counts().items()},
               stops_by_index={u: {str(k): int(v) for k, v in x.stop.fillna("reached the trigger stage").value_counts().items()}
                               for u, x in Dd.groupby("und")},
               claim=dict(setups=int(len(setup)), move_after=float(setup.move_after.mean()) if len(setup) else None,
                          after_positive=float((setup.move_after > 0).mean()) if len(setup) else None),
               variants={})
    ps = []
    for v in VARIANTS:
        X = T[T.variant == v].reset_index(drop=True) if len(T) else T
        st = C7.stats(X, max(1, len(Dd))) if len(X) else dict(n=0)
        if len(X):
            st["hit_4x"] = float((X.why == "target").mean())
            st["by_vix"] = {k: dict(n=int(len(x)), avg=float(x.net.mean())) for k, x in
                            (("vix>12", X[X.vix > 12]), ("vix<=12", X[~(X.vix > 12)])) if len(x)}
            st["by_wall"] = {k: dict(n=int(len(x)), avg=float(x.net.mean())) for k, x in
                             (("<=1 strike", X[X.wall_steps <= 1]), ("1-3", X[(X.wall_steps > 1) & (X.wall_steps <= 3)]),
                              (">3", X[X.wall_steps > 3])) if len(x)}
            st["apr_oct_2026"] = dict(n=int((X.day >= K.HOLDOUT[0]).sum()),
                                     avg=float(X.net[X.day >= K.HOLDOUT[0]].mean()) if (X.day >= K.HOLDOUT[0]).any() else None)
        st["signal"] = {"option": "option premium chart (NOT the default)", "index": "index (default)",
                        "none": "index breakout only (default)"}[VARIANTS[v]["trig"]]
        st["cfg"] = VARIANTS[v]
        out["variants"][v] = st
        ps.append(st.get("p_twins", 1.0))
    q = C7.R3.bh(ps)
    r129 = json.loads((G.RESULTS / "results.json").read_text())
    all_p = [r129["variants"][v].get("p_twins", 1.0) for v in G.VARIANTS] + ps
    q_all = C7.R3.bh(all_p)[-len(ps):]
    for v, qq, qa in zip(VARIANTS, q, q_all):
        st = out["variants"][v]
        st["q"], st["q_with_r129"] = qq, qa
        if st.get("n"):
            yrs = st["by_year"]
            st["gates"] = dict(n=st["n"] >= 300, net_pos=st["avg_net"] > 0 and st["avg_net_x2"] > 0,
                               years=sum(1 for y in range(2022, 2027) if yrs.get(y, {}).get("total", -1) > 0) >= 3,
                               q=qq <= 0.10, account=st["max_dd"] > -40_000 and st["worst_month"] > -13_000)
            st["passes"] = all(st["gates"].values())
    (G.RESULTS / "loose.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps({v: {k: out["variants"][v].get(k) for k in ("n", "win", "avg_net", "avg_net_x2", "total", "hit_4x",
                                                                  "p_twins", "q", "max_dd", "worst_month", "passes")}
                      for v in VARIANTS}, indent=1, default=str))
    print("stops", out["stops"], "claim", out["claim"])


if __name__ == "__main__":
    run()
