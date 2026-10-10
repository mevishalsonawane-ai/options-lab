"""h21: smart entries + adaptive (FIXED-mechanics) profit locks for the four old ORB-family arms. See PREREG.md.

    OBUY_CACHE=<scratch>/hunt/h21/cache flock <scratch>/obuy.lock python3 -I research/hunt/h21/sim.py prep
Outputs <scratch>/hunt/h21/: packs.pkl (real + random-entry candidates, contract paths), feats.parquet (per candidate).
Exits: exits(pk, spec, mode, kappa) - 'fixed' = the app after the h20 F1/F2 fix (resting SL-M moved to each rung, peak
from minute highs counted from the next minute, lock fills at min(level, open) -10 bps; target app-side on the minute
close -> next open), 'app' = the app today (h20 app mode: lock decided on closes, market sell next open).
"""
from __future__ import annotations

import dataclasses
import importlib.util
import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RESEARCH = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, RESEARCH)
sys.path.append("/root/.local/lib/python3.11/site-packages")

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import obuy  # noqa: E402,F401
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, Fills, floor_tick  # noqa: E402
from obuy.engine import Execution, StrikeRule, prepare_many, Pack, Store  # noqa: E402

_spec = importlib.util.spec_from_file_location("h20sim", os.path.join(os.path.dirname(HERE), "h20", "sim.py"))
H20 = importlib.util.module_from_spec(_spec)
sys.modules["h20sim"] = H20
_spec.loader.exec_module(H20)

SCR = os.path.join(C.SCRATCH, "hunt")
OUT = os.path.join(SCR, "h21")
NEG = -1e18
SQ = 15 * 60 + 10
HOLDOUT = pd.Timestamp("2025-10-01").date()
ARMS = H20.ARMS            # arm: (kind, k0, k1, max/day, target)
ARM_ORDER = ("orb", "orb_fresh", "orb_sweep", "range_fade")
EXE = Execution(fills=Fills(), costs=Costs(), expiry="allow", min_premium=40.0)
KAPPA, IMP_MAX = 0.02, 0.10
POOL_K = 5


# ------------------------------------------------------------------------------------------------ prep
def random_signals(s, kind, seed):
    k0, k1 = [(v[1], v[2]) for v in ARMS.values() if v[0] == kind][0]
    rng = np.random.default_rng(seed)
    n = len(s)
    rep = np.repeat(np.arange(n), POOL_K)
    k = rng.integers(k0, k1 + 1, len(rep))
    side = rng.choice((1, -1), len(rep))
    r = s.iloc[rep].reset_index(drop=True)
    r = r.assign(sig_min=C.OPEN_M + 5 * k + 4, side=side, tag=rep.astype(str))
    return r.drop(columns=["ref_spot"])


def features(mk):
    """Per (day, sig_min) features, all known at the signal minute (past-only ranks)."""
    from obuy.strategies.common import day_arrays, daily_atr
    ix = mk.index("BANKNIFTY")
    atr = daily_atr(ix, 14)
    dl = ix.daily()
    sma20 = dl.close.rolling(20).mean()
    prevc = dl.close.shift(1)
    dird = np.sign(prevc - sma20.shift(1)).to_dict()
    vmin = mk.vix.minutes()
    days = [d for d in ix.days if ix.d[d]["real"]]
    rows = []
    for d in days:
        o, h, l, c = day_arrays(ix, d)
        if np.isnan(c[:50]).sum() > 10:
            continue
        a = atr.get(d, np.nan)
        orw = (np.nanmax(h[:50]) - np.nanmin(l[:50])) / a if a and np.isfinite(a) else np.nan
        o0 = o[np.nonzero(np.isfinite(o))[0][0]]
        c29 = pd.Series(c[:30]).ffill().values[-1]
        s30 = (c29 - o0) / a if a and np.isfinite(a) else np.nan
        rows.append(dict(day=d, orw=orw, s30=s30, dird=dird.get(d, np.nan), vprev=mk.vix.prev_close(d)))
    D = pd.DataFrame(rows)
    for f, src in (("orw_rk", "orw"), ("s30_rk", "s30")):
        v = D[src].abs().values if f == "s30_rk" else D[src].values
        rk = np.full(len(v), np.nan)
        for i in range(60, len(v)):
            past = v[:i][np.isfinite(v[:i])]
            if len(past) >= 60 and np.isfinite(v[i]):
                rk[i] = (past < v[i]).mean()
        D[f] = rk
    # gamma (h18 B_lvl), past-only tercile cut points
    P = pd.read_parquet(os.path.join(SCR, "h18", "cache", "h18", "panel_BANKNIFTY.parquet")) \
        if os.path.exists(os.path.join(SCR, "h18", "cache", "h18", "panel_BANKNIFTY.parquet")) else None
    G = {}
    if P is not None:
        P = P[P.ok & (P.gtot > 0)].sort_values(["col", "day"]).reset_index(drop=True)
        lg = np.log(P.gtot)
        med = lg.groupby(P.col).transform(lambda s: s.shift(1).rolling(20, min_periods=10).median())
        P["B"] = lg - med
        P = P[np.isfinite(P.B)].sort_values(["day", "col"])
        acc = []
        for dts, g in P.groupby("day"):
            dd = dts.date()
            allp = np.concatenate(acc) if acc else np.array([])
            cuts = (np.quantile(allp, 1 / 3), np.quantile(allp, 2 / 3)) if len(allp) >= 60 * 20 else (np.nan, np.nan)
            G[dd] = (g.col.values, g.B.values, cuts)
            acc.append(g.B.values)
    return D.set_index("day"), G, vmin, ix


def cand_feats(sig, D, G, vmin, ix):
    from obuy.strategies.common import day_arrays
    out = []
    for d, g in sig.groupby("day", sort=False):
        o, h, l, c = day_arrays(ix, d)
        cf = pd.Series(c).ffill().values
        vm = vmin.get(d)
        vmf = pd.Series(vm).ffill().values if vm is not None else None
        gg = G.get(d)
        for i, r in zip(g.index, g.itertuples()):
            col = int(r.sig_min) - C.OPEN_M
            d60 = np.sign(cf[col] - cf[col - 60]) if col >= 60 else np.nan
            vx = vmf[col] if vmf is not None else np.nan
            bl = blt = np.nan
            if gg is not None:
                j = np.searchsorted(gg[0], col, side="right") - 1
                if j >= 0:
                    bl = gg[1][j]
                    blt = 0 if not np.isfinite(gg[2][0]) else (0 if bl < gg[2][0] else 1 if bl <= gg[2][1] else 2)
                    if not np.isfinite(gg[2][0]):
                        blt = np.nan
            out.append((i, d60, vx, bl, blt))
    a = pd.DataFrame(out, columns=["i", "dir60", "vix", "blvl", "gter"]).set_index("i").sort_index()
    f = sig[["day", "sig_min", "side"]].join(a)
    f = f.join(D, on="day")
    f["vchg"] = f.vix / f.vprev - 1
    return f


def prep():
    from obuy.data import market
    os.makedirs(OUT, exist_ok=True)
    mk = market()
    D, G, vmin, ix = features(mk)
    jobs, keys, feats = [], [], {}
    for j, arm in enumerate(ARM_ORDER):
        kind = ARMS[arm][0]
        s = H20.signals(mk, kind).reset_index(drop=True)
        s["book"] = arm
        feats[arm] = cand_feats(s, D, G, vmin, ix)
        r = random_signals(s, kind, 100 + j)
        print(arm, len(s), len(r), flush=True)
        jobs.append((s, StrikeRule(0), EXE, 0, None, False)); keys.append((arm, "real"))
        jobs.append((r, StrikeRule(0), EXE, 0, None, False)); keys.append((arm, "rand"))
    mk.release()
    packs = prepare_many(jobs)
    res = {k: pk.meta for k, (pk, _) in zip(keys, packs)}
    store = packs[0][0].store
    with open(os.path.join(OUT, "packs.pkl"), "wb") as f:
        pickle.dump((res, store.arr), f, protocol=4)
    with open(os.path.join(OUT, "feats.pkl"), "wb") as f:
        pickle.dump(feats, f, protocol=4)
    print("saved", sum(v.nbytes for v in store.arr.values()) / 1e6, "MB", {k: len(v) for k, v in res.items()})


def load():
    with open(os.path.join(OUT, "packs.pkl"), "rb") as f:
        res, arr = pickle.load(f)
    with open(os.path.join(OUT, "feats.pkl"), "rb") as f:
        feats = pickle.load(f)
    st = Store()
    st.arr = arr
    return {k: Pack(m, st, 1) for k, m in res.items()}, feats


# ------------------------------------------------------------------------------------------------ exits
@dataclasses.dataclass(frozen=True)
class Spec:
    name: str
    tgt: str = "arm"                # 'arm' (40 / Sweep 80) | 'none' | 'atr'
    tgt_atr: float = 0.0            # target = tgt_atr x A clipped [15, 120]
    ladder: str = "app"             # 'app' (25/50/75% of target) | 'atr' | 'none'
    lad_atr: tuple = ()             # ((reach_A, lock_A), ...)
    lock_after: int = 0             # minutes after entry before any lock/trail is active
    trail: tuple | None = None      # (arm_A, k_A)


def imp(v5, kappa):
    return np.minimum(IMP_MAX, kappa * np.sqrt(1.0 / np.maximum(v5, 1.0)))


def exits(pk: Pack, sp: Spec, mode="fixed", kappa=KAPPA, chunk=6000):
    out = [_exits(pk, slice(i, min(i + chunk, len(pk))), sp, mode, kappa) for i in range(0, len(pk), chunk)]
    return pd.concat(out, ignore_index=True) if out else pd.DataFrame()


def _exits(pk, sl, sp, mode, kappa):
    meta = pk.meta.iloc[sl].reset_index(drop=True)
    N = len(meta)
    O, H, L, Cl, V = (pk.get(k, sl) for k in ("O", "H", "L", "Cl", "V"))
    c0 = meta.c0.values.astype(np.int64)
    lot = meta.lot.values.astype(np.float64)
    qty = meta.qty.values.astype(np.float64)
    dn = meta.dn.values
    rows = np.arange(N)
    cols = np.arange(C.W)[None, :]
    Vn = np.nan_to_num(V) / lot[:, None]
    cs = np.concatenate([np.zeros((N, 1)), np.cumsum(Vn, axis=1)], axis=1)
    kk = np.arange(C.W)
    V5 = cs[:, kk] - cs[:, np.maximum(kk - 5, 0)]
    e_raw = O[rows, c0]
    e = np.round(meta.e1.values.astype(np.float64) * (1 + imp(V5[rows, c0], kappa)), 2)
    # premium ATR known at entry
    A = np.full(N, np.nan)
    for b in range(6):
        lo_, hi_ = c0 - 30 + 5 * b, c0 - 25 + 5 * b
        idx = np.clip(np.arange(5)[None, :] + lo_[:, None], 0, C.W - 1)
        hh, ll = H[rows[:, None], idx], L[rows[:, None], idx]
        rg = np.nanmax(np.where(np.isnan(hh), -np.inf, hh), axis=1) - np.nanmin(np.where(np.isnan(ll), np.inf, ll), axis=1)
        rg = np.where(np.isfinite(rg) & (lo_ >= 0), rg, np.nan)
        if b == 0:
            acc = np.nan_to_num(rg); cnt = np.isfinite(rg).astype(float)
        else:
            acc += np.nan_to_num(rg); cnt += np.isfinite(rg)
    A = np.where(cnt >= 3, acc / np.maximum(cnt, 1), np.nan)
    idx = np.clip(np.arange(30)[None, :] + (c0 - 30)[:, None], 0, C.W - 1)
    r1 = np.nanmean(H[rows[:, None], idx] - L[rows[:, None], idx], axis=1) * np.sqrt(5)
    A = np.where(np.isfinite(A), A, r1)
    A = np.clip(np.where(np.isfinite(A), A, 0.04 * e), 5.0, 60.0)

    tp = np.where(meta.book.values == "orb_sweep", 80.0, 40.0)
    valid = ~np.isnan(Cl)
    after = cols >= c0[:, None]
    sqc = SQ - C.OPEN_M
    barok = valid & after & (cols < sqc)
    t = floor_tick(e - 40.0)
    trig = np.where((t > C.TICK + 1e-9) & (t < e), t, np.nan)
    trig_m = np.where(np.isnan(trig), NEG, trig)[:, None]
    if sp.tgt == "arm":
        tgt = e + tp
    elif sp.tgt == "atr":
        tgt = e + np.clip(sp.tgt_atr * A, 15.0, 120.0)
    else:
        tgt = np.full(N, np.inf)
    be = e + np.maximum(EXE.costs.rt_per_unit(e, qty, dn, False), 0.0)
    src = H if mode == "fixed" else Cl
    hm = np.where(barok, src, NEG)
    inc = np.maximum.accumulate(hm, axis=1)
    peak = np.empty_like(inc)
    peak[:, 0] = NEG
    peak[:, 1:] = inc[:, :-1]
    peak = np.maximum(peak, e[:, None])
    lock = np.full((N, C.W), NEG)
    if sp.ladder == "app":
        rungs = [(a * tp, b * tp) for a, b in ((0.25, 0.0), (0.5, 0.25), (0.75, 0.5))]
    elif sp.ladder == "atr":
        rungs = [(a * A, b * A) for a, b in sp.lad_atr]
    else:
        rungs = []
    for a, b in rungs:
        lv = np.maximum(e + b, be)
        armed = peak >= (e + a - 1e-9)[:, None]
        lock = np.maximum(lock, np.where(armed & (lv[:, None] < peak), lv[:, None], NEG))
    if sp.trail:
        a, k = sp.trail
        lv = np.maximum(peak - (k * A)[:, None], be[:, None])
        armed = peak >= (e + a * A - 1e-9)[:, None]
        lock = np.maximum(lock, np.where(armed & (lv < peak), lv, NEG))
    if sp.lock_after:
        lock = np.where(cols >= (c0 + sp.lock_after)[:, None], lock, NEG)
    BIG = C.W

    def first(m):
        return np.where(m.any(axis=1), m.argmax(axis=1), BIG)

    okc = barok & (cols <= sqc - 2)
    k_sq = np.maximum(sqc - 1, c0)
    if mode == "fixed":
        rest = np.maximum(trig_m, lock)
        kb = first(barok & (L <= rest))
        k_lk = np.full(N, BIG)
    else:
        rest = np.broadcast_to(trig_m, (N, C.W))
        kb = first(barok & (L <= trig_m))
        k_lk = first(okc & (Cl <= lock))
    k_tg = first(okc & (Cl >= tgt[:, None]))
    stack = np.stack([k_lk, k_tg, k_sq])
    which = stack.argmin(axis=0)
    km = stack[which, rows]
    names = np.array(["lock", "target", "square_off"], dtype=object)
    bar = kb <= km
    why = np.where(bar, "", names[which]).astype(object)
    xcol = np.where(bar, kb, 0)
    raw = np.full(N, np.nan)
    if bar.any():
        b = np.nonzero(bar)[0]
        k = kb[b]
        lvl = rest[b, k]
        raw[b] = np.minimum(lvl, O[b, k])
        why[b] = np.where(trig_m[b, 0] >= lvl - 1e-9, "stop", "lock")
    mm = np.nonzero(~bar)[0]
    if len(mm):
        nv = np.where(valid, cols, BIG)
        nv = np.minimum.accumulate(nv[:, ::-1], axis=1)[:, ::-1]
        lvx = np.maximum.accumulate(np.where(valid, cols, -1), axis=1)[:, -1]
        k1 = np.minimum(km[mm] + 1, C.W - 1)
        nxt = np.where(km[mm] + 1 <= C.W - 1, nv[mm, k1], BIG)
        has = nxt < BIG
        col = np.where(has, nxt, lvx[mm])
        xcol[mm] = col
        raw[mm] = np.where(has, O[mm, col], Cl[mm, col])
    x = EXE.fills.sell(raw, None, stop=False)
    x = np.where(bar, EXE.fills.sell(raw, None, stop=True), x)
    x = np.round(x * (1 - imp(V5[rows, np.minimum(xcol, C.W - 1)], kappa)), 2)
    chg = EXE.costs.charge(True, e, qty, dn, False) + EXE.costs.charge(False, x, qty, dn, False)
    res = pd.DataFrame(dict(cand=meta.cand.values, tag=meta.tag.values, book=meta.book.values, day=meta.day.values,
                            sig_min=meta.sig_min.values, side=meta.side.values, lot=lot, qty=qty,
                            entry_min=c0 + C.OPEN_M, exit_min=xcol + C.OPEN_M, entry=e, exit=x, why=why,
                            gross=(raw - e_raw) * qty, charges=chg, net=(x - e) * qty - chg, A=A))
    res["year"] = [d.year for d in res.day]
    return res[c0 < sqc - 1].reset_index(drop=True)


if __name__ == "__main__":
    if sys.argv[1] == "prep":
        prep()
