"""h37: Fibonacci bounces with EXTENSION targets (FX127 / FX162): index target at the 127.2% / 161.8% extension of
the swing, index stop at the swing origin, square-off 15:10 (obuy engine, signal levels). Time-matched random
baseline: 3 alternatives per event, same day, random minute within +-15 of the signal, coin-flip side, the same index
target / stop DISTANCES from the index close at that minute.

    OBUY_CACHE=<scratch>/hunt/h37/cache flock <scratch>/obuy.lock python3 -I research/hunt/h37/fibx.py

Writes <scratch>/hunt/h37/pre_fx.parquet, hold_fx_sealed.parquet (read only by final.py), fx_X.npy (pre-holdout daily
net, for the SPA over all variants), fx_trades.parquet (per-trade, for walk-forward / finals).
"""
from __future__ import annotations

import os
import sys
import time
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import Execution, Exits, StrikeRule, prepare_many  # noqa: E402
import evaluate as EV  # noqa: E402
import sigs  # noqa: E402

EXT = {"FX127": 0.272, "FX162": 0.618}
NR = 3
EXE = Execution(expiry="skip")
EXF = Exits(sig_levels=True, sq_off=15 * 60 + 10)


def main():
    t0 = time.time()
    mk = market()
    rng = np.random.default_rng(3737)
    ds = [np.load(os.path.join(EV.OUT, f"out_{x}.npz"))["days"] for x in EV.UNDS]
    cal = np.unique(np.concatenate([d[d < EV.HOLD] for d in ds]))
    T = len(cal)
    rows, hrows, Xcols, trs = [], [], [], []
    for u in EV.UNDS:
        t1 = time.time()
        ix = mk.index(u)
        M = ix.mat()
        z = np.load(os.path.join(EV.OUT, f"out_{u}.npz"))
        tdays = z["days"]
        dmap = {o: i for i, o in enumerate(tdays)}
        allpos = {d.toordinal(): i for i, d in enumerate(ix.days)}
        cfgs = [c for c in sigs.all_events(ix) if c["sig"].startswith("FIB_B")]
        recs = []
        for ci, c in enumerate(cfgs):
            di, mi, sd, ex = EV.events(c, dmap)
            for j in range(len(di)):
                recs.append((ci, di[j], mi[j], sd[j], ex["H"][j], ex["L"][j]))
        E = pd.DataFrame(recs, columns=["ci", "di", "mi", "side", "H", "L"])
        E["ord"] = tdays[E.di.values]
        E["day"] = [date.fromordinal(int(o)) for o in E.ord.values]
        ia = np.array([allpos[o] for o in E.ord.values])
        ref = M["c"][ia, E.mi.values]
        E["ref"] = ref
        E = E[np.isfinite(ref)].reset_index(drop=True)
        E["eid"] = np.arange(len(E))
        ia = np.array([allpos[o] for o in E.ord.values], np.int64)
        jobs, meta = [], []
        for xn, e in EXT.items():
            tgt = E.H.values + e * (E.H.values - E.L.values)
            stp = E.L.values
            real = pd.DataFrame(dict(und=u, day=E.day.values, sig_min=E.mi.values + EV.SM0, side=E.side.values,
                                     book=E.eid.values.astype(str), idx_stop=stp, idx_target=tgt, tag="real"))
            # random alternatives: same day, minute +-15, coin-flip side, same distances
            rr = []
            dt = np.abs(tgt - E.ref.values)
            ds_ = np.abs(E.ref.values - stp)
            for b in range(NR):
                m2 = np.clip(E.mi.values + rng.integers(-EV.NWIN, EV.NWIN + 1, len(E)), 0, EV.MW - 1)
                s2 = rng.choice([-1, 1], len(E))
                ref2 = M["c"][ia, m2]
                ok = np.isfinite(ref2)
                rr.append(pd.DataFrame(dict(und=u, day=E.day.values[ok], sig_min=m2[ok] + EV.SM0, side=s2[ok],
                                            book=E.eid.values[ok].astype(str), idx_stop=ref2[ok] - s2[ok] * ds_[ok],
                                            idx_target=ref2[ok] + s2[ok] * dt[ok], tag=f"r{b}")))
            sig = pd.concat([real] + rr, ignore_index=True)
            jobs.append((sig, StrikeRule(money=1), EXE, 0, None, False))
            meta.append(xn)
        packs = prepare_many(jobs)
        for (pk, _), xn in zip(packs, meta):
            tr = pk.run(EXF, EXE, chunk=6000)
            tr = tr.merge(pk.meta[["cand", "tag"]], on="cand", how="left") if "tag" not in tr else tr
            tr["eid"] = tr.book.astype(int)
            hs = EV.HS[u]
            sb = (tr.entry.values + tr.exit.values) * tr.qty.values
            tr["r"] = tr.net.values - hs * sb
            tr["st"] = tr.net.values - EV.STRESS * hs * sb
            real = tr[tr.tag == "real"].set_index("eid")
            rnd = tr[tr.tag != "real"].groupby("eid").r.mean()
            for ci, c in enumerate(cfgs):
                ev = E[E.ci == ci]
                ev = ev[ev.eid.isin(real.index)]
                if not len(ev):
                    sel_e = ev
                else:
                    X = real.loc[ev.eid.values, "exit_min"].values
                    sel = EV.greedy(ev.di.values, ev.mi.values, X)
                    sel_e = ev.iloc[sel]
                R = real.loc[sel_e.eid.values]
                net = R.r.values.astype(float)
                st = R.st.values.astype(float)
                gr = R.gross.values.astype(float)
                m0 = rnd.reindex(sel_e.eid.values).values
                m0 = np.where(np.isnan(m0), net, m0)
                di = sel_e.di.values
                pre = tdays[di] < EV.HOLD
                yr = np.array([date.fromordinal(int(o)).year for o in tdays[di]]) if len(di) else np.zeros(0, int)
                yidx = np.searchsorted(EV.YEARS, yr)
                row = dict(und=u, sig=c["sig"], fam=c["fam"], tf=str(c["tf"]), par=c["par"], exit=xn,
                           n=int(pre.sum()), net=net[pre].sum(), stress=st[pre].sum(), gross=gr[pre].sum(),
                           ss=(net[pre] ** 2).sum(), m0=m0[pre].sum(), ties=0.0)
                dd = (net - m0)[pre]
                ud, inv = np.unique(di[pre], return_inverse=True)
                dsum = np.bincount(inv, weights=dd, minlength=len(ud))
                row["nd"], row["dsum"], row["dss"] = len(ud), dsum.sum(), (dsum ** 2).sum()
                yi = yidx[pre]
                ydays = np.searchsorted(EV.YEARS, np.array([date.fromordinal(int(o)).year for o in tdays[ud]])) \
                    if len(ud) else np.zeros(0, int)
                for nm, val in (("n", np.ones(pre.sum())), ("net", net[pre]), ("stress", st[pre]), ("m0", m0[pre])):
                    bc = np.bincount(yi, weights=val, minlength=len(EV.YEARS))
                    for y, x in zip(EV.YEARS, bc):
                        row[f"{nm}_{y}"] = x
                for nm, val in (("nd", np.ones(len(ud))), ("dsum", dsum), ("dss", dsum ** 2)):
                    bc = np.bincount(ydays, weights=val, minlength=len(EV.YEARS))
                    for y, x in zip(EV.YEARS, bc):
                        row[f"{nm}_{y}"] = x
                rows.append(row)
                h = ~pre
                hrows.append(dict(und=u, sig=c["sig"], tf=str(c["tf"]), par=c["par"], exit=xn, n=int(h.sum()),
                                  net=net[h].sum(), stress=st[h].sum(), gross=gr[h].sum(), m0=m0[h].sum(), ties=0.0))
                Xcols.append(np.bincount(np.searchsorted(cal, tdays[di[pre]]), weights=net[pre], minlength=T))
                trs.append(pd.DataFrame(dict(und=u, sig=c["sig"], tf=str(c["tf"]), par=c["par"], exit=xn,
                                             ord=tdays[di], mi=sel_e.mi.values, side=sel_e.side.values, net=net,
                                             stress=st, gross=gr, m0=m0, prem=(R.entry.values * R.qty.values),
                                             pre=pre)))
        print(u, len(cfgs), "configs", len(E), "events", f"{time.time() - t1:.0f}s", flush=True)
        mk.release(u)
    pd.DataFrame(rows).to_parquet(os.path.join(EV.OUT, "pre_fx.parquet"))
    pd.DataFrame(hrows).to_parquet(os.path.join(EV.OUT, "hold_fx_sealed.parquet"))
    np.save(os.path.join(EV.OUT, "fx_X.npy"), np.stack(Xcols, axis=1).astype(np.float32))
    pd.concat(trs, ignore_index=True).to_parquet(os.path.join(EV.OUT, "fx_trades.parquet"))
    print(f"done {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
