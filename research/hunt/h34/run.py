"""h34 main run (PRE-HOLDOUT days only): every strategy's DEFAULT and IS-BEST entries x {ATM, 1-ITM} x the 128-exit
premium-point menu, plus the ORIGINAL-exit references, with dated charges + real half-spread.

    python3 -I research/hunt/h34/run.py plan                 # signal counts -> chunks.json
    flock <scratch>/obuy.lock python3 -I research/hunt/h34/run.py chunk K
Writes scratchpad/hunt/h34/res/<strategy>.pkl: dict(rows = per-variant summary DataFrame, X = (days x variants)
float32 daily NET, vids, days).
"""
from __future__ import annotations

import json
import os
import pickle
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import core as K  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import stats as sst  # noqa: E402
from obuy.data import market, dnum  # noqa: E402
from obuy.engine import prepare_many, positions  # noqa: E402
from obuy.lab import signals_cached  # noqa: E402

RES = os.path.join(K.OUT, "res")
MAX_ROWS = 120_000          # signal rows (x2 strikes) per chunk


def calendar():
    mk = market()
    ds = sorted({d for u in K.UNDS5 for d in mk.index(u).days if d < K.HOLD})
    return ds


def sig_sets(name, st, isb):
    b = isb.get(name, dict(si=0, ri=0, xi=0))
    sets = [("def", 0)]
    if b["si"] != 0:
        sets.append(("isb", b["si"]))
    return sets, b


def load_cells():
    C = {}
    for u in K.UNDS5:
        f = os.path.join(K.OUT, f"rand_{u}_cells.npz")
        if os.path.exists(f):
            z = np.load(f)
            C[u] = {k: z[k] for k in z.files}
    return C


def matched(tr, cells, ri, xi):
    """CLT matched random-entry test of the trades' NET vs random entries in the same (index, day, hour) cell."""
    if not len(tr) or (tr.side == 0).any():
        return np.nan, np.nan, np.nan, 0
    obs, mu, var, n = 0.0, 0.0, 0.0, 0
    for u, g in tr.groupby("und"):
        c = cells.get(u)
        if c is None:
            continue
        cid = np.array([dnum(d) for d in g.day]) * 10 + K.bucket(g.entry_min.values)
        U = c[f"cid_r{ri}"]
        pos = np.searchsorted(U, cid)
        pos = np.clip(pos, 0, len(U) - 1)
        ok = U[pos] == cid
        cnt = c[f"cnt_r{ri}"][pos].astype(float)
        ok &= cnt >= 2
        if not ok.any():
            continue
        cn = cnt[ok]
        s1 = c[f"s1_r{ri}"][pos[ok], xi].astype(float)
        s2 = c[f"s2_r{ri}"][pos[ok], xi].astype(float)
        m = s1 / cn
        v = np.maximum(s2 / cn - m ** 2, 0) * cn / (cn - 1)
        obs += g.NET.values[ok].sum()
        mu += m.sum()
        var += v.sum()
        n += int(ok.sum())
    if n < 10 or var <= 0:
        return np.nan, np.nan, np.nan, n
    z = (obs - mu) / np.sqrt(var)
    return float(1 - sst.norm.cdf(z)), obs / n, mu / n, n


def summarise(tr, vid, meta, dpos, cells, ri, xi):
    r = dict(vid=vid, **meta)
    r["trades"] = len(tr)
    x = np.zeros(len(dpos), np.float32)
    if not len(tr):
        r.update(NET=0.0, GROSS=0.0, STRESS=0.0, charges=0.0, spread=0.0, win=np.nan, p_rand=np.nan)
        return r, x
    r["NET"] = float(tr.NET.sum())
    r["GROSS"] = float(tr.gross_mid.sum())
    r["STRESS"] = float(tr.STRESS.sum())
    r["charges"] = float(tr.charges.sum())
    r["spread"] = float(tr.spread.sum())
    r["win"] = float((tr.NET > 0).mean())
    r["prem_med"] = float(np.median(tr.e_raw))
    for y, g in tr.groupby("year"):
        r[f"n_{y}"] = len(g)
        r[f"net_{y}"] = float(g.NET.sum())
        r[f"gross_{y}"] = float(g.gross_mid.sum())
    for u, g in tr.groupby("und"):
        r[f"n_{u}"] = len(g)
        r[f"net_{u}"] = float(g.NET.sum())
        r[f"gross_{u}"] = float(g.gross_mid.sum())
    bd = K.band(tr.e_raw.values)
    for b in range(4):
        m = bd == b
        r[f"n_b{b}"] = int(m.sum())
        r[f"net_b{b}"] = float(tr.NET.values[m].sum())
        r[f"gross_b{b}"] = float(tr.gross_mid.values[m].sum())
    for w in ("target", "stop", "lock", "time_stop", "square_off"):
        r[f"why_{w}"] = float((tr.why == w).mean())
    dl = tr.groupby("day").NET.sum()
    idx = [dpos[d] for d in dl.index]
    x[idx] = dl.values
    p, o, nm, nu = matched(tr, cells, ri, xi) if ri is not None else (np.nan, np.nan, np.nan, 0)
    r.update(p_rand=p, rand_obs=o, rand_null=nm, rand_n=nu)
    return r, x


def strat_jobs(name, st, isb):
    """Menu jobs (sig set x ATM/1-ITM) and original-exit reference jobs: orig_def = (s0, r0, x0), orig_isb = the
    IS-best (si, ri, xi). Info tuples: (kind, tag, si, ri, xi)."""
    sets, b = sig_sets(name, st, isb)
    jobs, info = [], []
    exe = K.exe_h34(st.exe)
    refs = {("orig_def", 0, 0, 0)}
    refs.add(("orig_isb", b["si"], b["ri"], b["xi"]))
    if b["si"] == 0 and (b["ri"], b["xi"]) == (0, 0):
        refs = {("orig_isb", 0, 0, 0)}
    for tag, si in sets:
        raw = signals_cached(st, st.sig_grid[si])
        market().release()
        s = K.prep_signals(raw)
        for ri, rule in enumerate(K.RULES):
            jobs.append((s, rule, exe, 0, None, False))
            info.append(("menu", tag, si, ri, None))
        for kind, rsi, rri, rxi in sorted(refs):
            if rsi == si:
                jobs.append((K.period(raw), st.rules[rri], exe, 0, None, False))
                info.append((kind, tag, si, rri, rxi))
    return jobs, info, b


def plan():
    isb = json.load(open(os.path.join(K.OUT, "is_best.json")))
    rows = []
    for name, st in K.strategies():
        sets, _ = sig_sets(name, st, isb)
        n = 0
        for tag, si in sets:
            t0 = time.time()
            s = signals_cached(st, st.sig_grid[si])
            market().release()
            n += len(K.prep_signals(s))
            print(f"{name:26s} {tag} s{si}: {len(s)} signals ({time.time() - t0:.0f}s)", flush=True)
        rows.append((name, n))
    chunks, cur, tot = [], [], 0
    for name, n in rows:
        if cur and tot + n > MAX_ROWS:
            chunks.append(cur)
            cur, tot = [], 0
        cur.append(name)
        tot += n
    if cur:
        chunks.append(cur)
    json.dump(dict(chunks=chunks, counts=rows), open(os.path.join(K.OUT, "chunks.json"), "w"), indent=1)
    print(len(chunks), "chunks", [len(c) for c in chunks])


def chunk(k, names=None, res=None):
    global RES
    RES = res or RES
    os.makedirs(RES, exist_ok=True)
    isb = json.load(open(os.path.join(K.OUT, "is_best.json")))
    names = names or json.load(open(os.path.join(K.OUT, "chunks.json")))["chunks"][k]
    S = dict(K.strategies())
    cal = calendar()
    dpos = {d: i for i, d in enumerate(cal)}
    cells = load_cells()
    t0 = time.time()
    todo = [n for n in names if not os.path.exists(os.path.join(RES, f"{n}.pkl"))]
    if not todo:
        print(f"chunk {k}: nothing to do", flush=True)
        return
    alljobs, owner = [], []
    for name in todo:
        jobs, info, b = strat_jobs(name, S[name], isb)
        for j, i in zip(jobs, info):
            alljobs.append(j)
            owner.append((name, i, b))
    print(f"chunk {k}: {todo}, {len(alljobs)} jobs, {sum(len(j[0]) for j in alljobs)} signal rows", flush=True)
    packs = prepare_many(alljobs)
    print(f"packs ready {time.time() - t0:.0f}s, store {packs[0][0].store.nbytes() / 1e6:.0f} MB", flush=True)
    for name in todo:
        st = S[name]
        exe = K.exe_h34(st.exe)
        rows, cols, vids = [], [], []
        for (pk, _), (nm, (kind, tag, si, ri, oxi), b) in zip(packs, owner):
            if nm != name:
                continue
            if kind == "menu":
                res = K.run_menu(pk, K.MENU, exe) if len(pk) else {i: pd.DataFrame() for i in range(K.NX)}
                for xi in range(K.NX):
                    tr = res[xi]
                    if len(tr):
                        tr = K.post(positions(tr, **st.pos))
                    vid = f"{name}|{tag}|{K.RLAB[ri]}|x{xi}"
                    meta = dict(strategy=name, kind="menu", sig=tag, si=si, rule=K.RLAB[ri], xi=xi, exit=K.MLAB[xi])
                    r, x = summarise(tr if len(tr) else pd.DataFrame(), vid, meta, dpos, cells, ri, xi)
                    rows.append(r)
                    cols.append(x)
                    vids.append(vid)
                del res
            else:
                ex = st.exits[oxi]
                tr = pk.run(ex, exe) if len(pk) else pd.DataFrame()
                if len(tr):
                    tr = K.post(positions(tr, **st.pos))
                vid = f"{name}|{kind}|s{si}|r{ri}|x{oxi}"
                meta = dict(strategy=name, kind=kind, sig=tag, si=si, rule=st.rules[ri].label(), xi=oxi, exit=ex.label()[:80])
                r, x = summarise(tr if len(tr) else pd.DataFrame(), vid, meta, dpos, {}, None, None)
                rows.append(r)
                cols.append(x)
                vids.append(vid)
        R = pd.DataFrame(rows)
        with open(os.path.join(RES, f"{name}.pkl"), "wb") as f:
            pickle.dump(dict(rows=R, X=np.stack(cols, axis=1), vids=vids, days=cal), f, protocol=4)
        m = R[R.kind == "menu"]
        o = R[R.kind != "menu"]
        print(f"{name:26s} menu {len(m)} variants, best NET {m.NET.max():,.0f}, median {m.NET.median():,.0f}, "
              f"positive {(m.NET > 0).mean():.0%}; orig NET {list(o.NET.round(0))}  ({time.time() - t0:.0f}s)", flush=True)
    market().release()


if __name__ == "__main__":
    if sys.argv[1] == "plan":
        plan()
    elif sys.argv[1] == "test":
        chunk(-1, sys.argv[2:], os.path.join(K.OUT, "res_test"))
    else:
        chunk(int(sys.argv[2]))
