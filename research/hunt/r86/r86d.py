"""R86d: the BANKNIFTY seller detector, improved (D1-D4) and judged ONCE on the unread 2025-26 window.
PREREG: research/hunt/r86/PREREG_D.md (commit 544ddd5).

    python -m research.hunt.r86.r86d design    # post-hoc design walk-forward (folds 2022-2024) + D0 == C6 check
    python -m research.hunt.r86.r86d final     # ONCE: 1 Jan 2025 - data end, all variants together
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r86 import r86b as RB
from research.hunt.r86 import r86c as C
from research.hunt.r86 import r86lib as L
from research.hunt.r86.r86study import summ
from research.obuy.config import LOT_SCHEDULE

OUT = Path(__file__).resolve().parent / "results"
SEED = 864
NTWIN = int(os.environ.get("R86_NTWIN", "500"))
FINAL_FROM = pd.Timestamp("2025-01-01")
DESIGN_FOLDS = [2022, 2023, 2024]
COLS = [f"P{i}" for i in range(1, 13) if i != 10] + ["gap", "mv", "tv_ratio", "vix_chg"]
VARIANTS = {   # id: (training pool, top share, exit minute, rule)
    "D0": ("BANKNIFTY", 0.10, 675, "model"),
    "D1": ("POOLED", 0.10, 675, "model"),
    "D2": ("BANKNIFTY", 0.10, 915, "model"),
    "D3": (None, None, 915, "rule"),
    "D4": ("POOLED", 0.20, 915, "model"),
}
GATED = ["D1", "D2", "D3", "D4"]


def table(und):
    bk = RB.book(und)
    day_of = np.repeat(np.arange(len(bk.days)), bk.end - bk.start)
    d = RB.day_table(bk, und)
    gi, sg = RB.bursts(bk, und)
    x = C.prep_detector_table(bk, d, und, gi, sg, day_of, 584)
    x["breadth_dn"] = x.day.map(C.basket_breadth_down(und, 584))
    st, fb = C.staircase_and_bounce(bk, x, 584)
    x["stair"], x["failed_bounce"], x["bn_lead"] = st, fb, False
    fl = C.seller_flags(x, und)
    for c in fl.columns[1:]:
        x[c] = fl[c].fillna(False).astype(float)
    x["und"] = und
    return bk, x


def d3_rule(x: pd.DataFrame) -> np.ndarray:
    """Pattern P2: gap down <= -0.5% and the 09:15-09:44 move down."""
    return ((x.gap <= -0.005) & (x.mv < 0)).to_numpy()


def select(xb, xp, vid, a, b):
    """Rows of BANKNIFTY table xb (days in [a, b]) the variant shorts; training rows end before a."""
    pool, top, _, kind = VARIANTS[vid]
    te = xb[(xb.day >= a) & (xb.day <= b)].dropna(subset=COLS)
    if kind == "rule":
        return te[d3_rule(te)], None
    tr = (xp if pool == "POOLED" else xb)
    tr = tr[(tr.day < a) & tr.FII_SELL_known].dropna(subset=COLS)
    if len(tr) < 150 or tr.FII_SELL.sum() < 10 or len(te) == 0:
        return te.iloc[:0], None
    ptr, pte = C.fit_predict(tr, te, COLS, "FII_SELL")
    thr = np.quantile(ptr, 1 - top)
    known = te.FII_SELL_known.to_numpy()
    a_ = C.auc(te.FII_SELL.to_numpy()[known], pte[known]) if known.any() else None
    return te[(pte >= thr) & (te.mv.to_numpy() < 0)], a_


def trades(bk, sel, exit_mod):
    es, last, stops, ks = [], [], [], []
    for _, r in sel.iterrows():
        k = int(r.k)
        sl = bk.day_slice(k)
        i_e, i_x = RB.idx(bk.mod[sl], 585), RB.idx(bk.mod[sl], exit_mod)
        if i_e is None or i_x is None:
            continue
        ent = bk.o[sl][i_e]
        dist = max(0.5 * r.rng * ent, 0.0015 * ent)
        es.append(sl.start + i_e); last.append(sl.start + i_x); stops.append(ent + dist); ks.append(k)
    if not es:
        return pd.DataFrame(), [], np.array([])
    e = np.array(es)
    tr = C.fut_trade(bk, "BANKNIFTY", e, np.array(last), np.full(len(e), -1), np.array(stops))
    return tr, ks, np.abs(bk.o[e] - np.array(stops)) / bk.o[e]


def twins(bk, pool_k, ks, sp, exit_mod, n=NTWIN):
    nby = {}
    for k in ks:
        nby[bk.days[k].year] = nby.get(bk.days[k].year, 0) + 1

    def exit_fn(k, ee):
        sl = bk.day_slice(k)
        j = RB.idx(bk.mod[sl], exit_mod)
        return sl.start + j if j is not None else C.last1515(bk, k)
    old = C.NTWIN, C.SEED
    C.NTWIN, C.SEED = n, SEED
    try:
        return C.day_twins_minute(bk, "BANKNIFTY", pool_k, nby, -1, sp, 585, 585, exit_fn)
    finally:
        C.NTWIN, C.SEED = old


def put_pnl(opts, bk, tr):
    """The same signal as an ATM put: strike nearest the 09:44 spot, bought at the 09:45 open, sold at the exit minute."""
    out = []
    for _, t in tr.iterrows():
        day = pd.Timestamp(t.day)
        ox = opts.get(day)
        if ox is None:
            continue
        atm = ox[(ox["mod"] == 584) & (ox.offset == 0) & (ox.side == "P")]
        if len(atm) == 0:
            continue
        K = float(atm.strike.iloc[0])
        x = ox[(ox.strike == K) & (ox.side == "P")]
        ent = x[x["mod"] == 585]
        xmod = int(bk.mod[int(t.xi)])
        ex = x[(x["mod"] <= xmod) & (x["mod"] >= 585)].sort_values("mod")
        if len(ent) == 0 or len(ex) == 0 or not ent.open.iloc[0] > 0:
            continue
        units = L.lot_on(LOT_SCHEDULE["BANKNIFTY"], day)
        dn = (day.date() - date(1970, 1, 1)).days
        eb = float(ent.open.iloc[0]) * 1.0005 + 0.05
        xs = max(float(ex.close.iloc[-1]) * 0.9995 - 0.05, 0.0)
        ch = float(C.OPT_COSTS.charge(True, eb, units, dn)) + float(C.OPT_COSTS.charge(False, xs, units, dn))
        out.append((xs - eb) * units - ch)
    return summ(out)


def evaluate(bk, xb, xp, periods, opts=None):
    res = {}
    for vid, (_, _, exit_mod, _) in VARIANTS.items():
        sels, aucs = [], {}
        for name, a, b in periods:
            s, au = select(xb, xp, vid, a, b)
            sels.append(s)
            aucs[name] = au
        sel = pd.concat(sels) if sels else xb.iloc[:0]
        tr, ks, sp = trades(bk, sel, exit_mod)
        a0, b0 = periods[0][1], periods[-1][2]
        pool = list(xb[(xb.day >= a0) & (xb.day <= b0)].dropna(subset=COLS).k.astype(int))
        tw = twins(bk, pool, ks, sp, exit_mod) if len(tr) else np.zeros(NTWIN)
        nets = tr.net.to_numpy() if len(tr) else np.array([])
        s = summ(nets)
        yrs = pd.DatetimeIndex(tr.day).year if len(tr) else np.array([])
        s.update(twin_mean=round(float(tw.mean()), 0), p=L.twin_p(float(nets.sum()), tw),
                 by_year={str(y): round(float(nets[yrs == y].sum()), 0) for y in sorted(set(yrs))},
                 n_by_year={str(y): int((yrs == y).sum()) for y in sorted(set(yrs))}, auc=aucs,
                 exit_kinds=tr.kind.value_counts().to_dict() if len(tr) else {},
                 trades=[{"day": str(pd.Timestamp(t.day).date()), "net": round(float(t.net), 0), "kind": t.kind}
                         for _, t in tr.iterrows()])
        if opts is not None and len(tr):
            s["as_atm_put"] = put_pnl(opts, bk, tr)
        res[vid] = s
    q = L.bh([res[v]["p"] for v in GATED])
    for v, qq in zip(GATED, q):
        res[v]["bh_q"] = qq
    return res


def load():
    bkb, xb = table("BANKNIFTY")
    _, xn = table("NIFTY")
    xp = pd.concat([xn, xb], ignore_index=True)
    return bkb, xb, xp


def design():
    bk, xb, xp = load()
    periods = [(str(y), pd.Timestamp(f"{y}-01-01"), pd.Timestamp(f"{y}-12-31")) for y in DESIGN_FOLDS]
    res = evaluate(bk, xb, xp, periods)
    d0 = res["D0"]
    res["_D0_reproduces_C6"] = bool(d0["n"] == 13 and abs(d0["net"] - 36188) < 1)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "d_design.json").write_text(json.dumps(res, indent=1, default=str))
    for v in VARIANTS:
        r = res[v]
        print(f"{v} n={r['n']} net={r['net']} win={r['win']} wo5={r['net_wo_top5']} twins={r['twin_mean']} p={r['p']:.3f}")
    print("D0 reproduces C6:", res["_D0_reproduces_C6"])


def final():
    flag = OUT / "FINAL_D_READ"
    if flag.exists():
        raise SystemExit("R86d final already read once (results/FINAL_D_READ); a second read is refused")
    des = json.loads((OUT / "d_design.json").read_text())
    if not des.get("_D0_reproduces_C6"):
        raise SystemExit("design run missing or D0 does not reproduce C6: fix before reading the final")
    flag.write_text("read once on " + pd.Timestamp.now(tz="Asia/Kolkata").isoformat() + "\n")
    bk, xb, xp = load()
    end = xb.day.max()
    opts = C.opt_day_tables("BANKNIFTY")
    res = evaluate(bk, xb, xp, [("final", FINAL_FROM, end)], opts)
    for v in GATED:
        r = res[v]
        r["passes"] = bool(r["n"] > 0 and r["net"] > 0 and r["net"] > r["twin_mean"] and r["p"] < 0.10
                           and r["bh_q"] < 0.10 and r["net_wo_top5"] > 0 and des[v]["net"] > 0)
    res["_final_window"] = [str(FINAL_FROM.date()), str(end.date())]
    (OUT / "d_final.json").write_text(json.dumps(res, indent=1, default=str))
    for v in VARIANTS:
        r = res[v]
        print(f"{v} n={r['n']} net={r['net']} win={r['win']} wo5={r['net_wo_top5']} twins={r['twin_mean']} p={r['p']:.3f} "
              f"q={r.get('bh_q')} pass={r.get('passes')} put={r.get('as_atm_put')}")


if __name__ == "__main__":
    {"design": design, "final": final}.get(sys.argv[1] if len(sys.argv) > 1 else "design", lambda: print(__doc__))()
