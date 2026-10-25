"""R104 gate 6 (PREREG.md section 7): the random-entry twin for the tests that pass gates 1-5 in design, BEFORE the
holdout. 500 draws of one twin per real position; p = (1 + #{twin mean net >= real mean net}) / (draws + 1).

A twin keeps the real position's day (intraday markets: a uniform random minute in the rule's default window of that
session; daily markets: a uniform random session in the same calendar month), takes a coin-flip side, and keeps the same
exits: the same stop and target DISTANCES from its own entry, the same relative time exit, the same break-even multiple
and the rule's own trail / exit arrays for its side. A pool of 20 twins per position is priced once; draws sample from
the pool. VOL tests: 500 random day-subsets of the unfiltered structure are not available for every structure, so VOL
passers (if any) use random days in the same fold with the same structure (labelled).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r104cfg as K  # noqa: E402
import r104data as DA  # noqa: E402
import r104run as R  # noqa: E402
import r104sim as SIM  # noqa: E402

POOL = 20


def _window(ctx, ses):
    s = ctx.s
    a, z = int(s.s0[ses]), int(s.s1[ses])
    if ctx.market in ("IDX", "IDXF"):
        lo, hi = 9 * 60 + 30, 14 * 60 + 30
    else:
        lo = s.tod[a] + 15
        hi = s.tod[min(int(s.sq[ses]), s.n - 1)] - 30
    rows = np.flatnonzero((s.tod[a:z + 1] >= lo) & (s.tod[a:z + 1] <= hi)) + a
    return rows


def twin_intents(ctx, real_intents, executed, rng):
    """POOL twins per executed real sub-lot group."""
    s = ctx.s
    by_grp = {}
    for t in real_intents:
        by_grp.setdefault(t[9], []).append(t)
    out = []
    daily = ctx.market in ("DLY", "MCXD", "STK")
    for e in executed:
        grp = by_grp.get(e["grp"])
        if not grp:
            continue
        f = e["f"]
        ses = int(s.ses[f])
        for j in range(POOL):
            side = int(rng.choice([-1, 1]))
            if daily:
                d = s.days[ses]
                same = [i for i, x in enumerate(s.days) if x.year == d.year and x.month == d.month]
                start = int(rng.choice(same))
            else:
                w = _window(ctx, ses)
                if not len(w):
                    continue
                start = int(rng.choice(w))
            ref = float(s.o[start]) if start < s.n else np.nan
            g = f"tw{e['grp']}_{j}"
            for t in grp:
                st0, sd0, kind, lvl, vend, stop, tgt, tmax, be, _g, w_, r_ = t
                base = lvl if kind != 0 and lvl == lvl else r_
                sd_dist = abs(base - stop) if stop == stop else np.nan
                tg_dist = abs(tgt - base) if tgt == tgt else np.nan
                n_stop = ref - side * sd_dist if sd_dist == sd_dist else np.nan
                n_tgt = ref + side * tg_dist if tg_dist == tg_dist else np.nan
                if tmax >= 0:
                    n_tmax = start + max(int(tmax) - int(st0), 1)
                else:
                    n_tmax = tmax
                out.append((start, side, 0, np.nan, -1, float(n_stop), float(n_tgt), int(n_tmax), float(be), g, w_, ref))
    return out


def twin_p(rid, market, variant, draws=500, seed=K.SEED):
    """Gate 6 for one design passer."""
    imp = R.IMPL[(rid, market)]
    var = imp["variants"][variant]
    rng = np.random.default_rng(seed)
    real_means = []
    pools = []
    for inst in R.instruments(market, imp["insts"]):
        ctx = R.make_ctx(market, inst)
        kw = {k: v for k, v in var.items() if not k.startswith("_")}
        intents, trail, xflag = imp["fn"](ctx, **kw)
        s = ctx.s
        keep = [t for t in intents if 0 <= t[0] < s.n and K.period_of(market, ctx.day(int(t[0]))) == "design"]
        ex = SIM.execute(s.o, s.h, s.l, keep, R._end_of(ctx, var.get("_multiday", False)), trail, xflag,
                         max_side_day=var.get("_max_side_day", 2), day_of=lambda i: s.ses[i],
                         one_pos=var.get("_one_pos", True), tab=var.get("_tab", False), tmode=var.get("_tmode", 0), c=s.c)
        tw = twin_intents(ctx, keep, ex, rng)
        # price the twins one group at a time (no position walk between twins: each is an independent draw)
        priced = {}
        for t in tw:
            res = SIM.execute(s.o, s.h, s.l, [t], R._end_of(ctx, var.get("_multiday", False)), trail, xflag,
                              day_of=lambda i: s.ses[i], tab=var.get("_tab", False), tmode=var.get("_tmode", 0), c=s.c)
            if not res:
                continue
            e = res[0]
            net = _price(ctx, market, inst, imp, e)
            if net is not None:
                priced[t[9]] = priced.get(t[9], 0.0) + net          # sub-lots of one twin sum (weights add to 1)
        by_real = {}
        for g, v in priced.items():
            by_real.setdefault(g.rsplit("_", 1)[0], []).append(v)
        pools += [v for v in by_real.values() if v]
    real = pd.read_parquet(K.OUT / "trades" / f"{rid}__{market}__{variant}__design.parquet")
    real_mean = float(real.net.mean())
    if not pools:
        return dict(p=1.0, real_mean=real_mean, twin_mean=None, pools=0)
    means = np.array([np.mean([rng.choice(p) for p in pools]) for _ in range(draws)])
    return dict(p=float((1 + np.sum(means >= real_mean)) / (draws + 1)), real_mean=real_mean,
                twin_mean=float(means.mean()), pools=len(pools))


def _price(ctx, market, inst, imp, e):
    s = ctx.s
    d_in = ctx.day(e["f"])
    d_out = ctx.day(min(e["x"], s.n - 1))
    stop_x = e["reason"] == "stop"
    if market == "IDX":
        D = DA.store(inst).get(d_in)
        if D is None:
            return None
        e_row = int(s.tod[e["f"]] - K.NSE_OPEN)
        x_row = int(s.tod[min(e["x"], s.n - 1)] - K.NSE_OPEN) + e["touch"]
        if d_out != d_in:
            x_row = 10 ** 6
        pr = SIM.price_option(D, inst, d_in, e["side"], e_row, x_row, e["fpx"], imp["money"], K.lot(inst, d_in), D.expiry)
    elif market in ("IDXF", "DLY"):
        pr = SIM.price_index_future(inst, d_in, d_out, e["side"], e["fpx"], e["xpx"], stop_x, False, K.lot(inst, d_in))
    elif market in ("MCXI", "MCXD"):
        pr = SIM.price_mcx_future(inst, e["side"], e["fpx"], e["xpx"], stop_x, False, R.FUT_UNITS[inst])
    elif market == "CRY":
        pr = SIM.price_crypto(e["side"], e["fpx"], e["xpx"], stop_x, False)
    else:
        pr = SIM.price_stock(e["side"], e["fpx"], e["xpx"], stop_x, False, d_in)
    if pr is None or not np.isfinite(pr["net"]):
        return None
    return pr["net"] * e["w"]
