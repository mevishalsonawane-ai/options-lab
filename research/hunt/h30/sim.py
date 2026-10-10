"""h30 step 2: signals -> trades -> 19 exits, for one underlying, plus 20 random entries per chart (same contract, same
day, same exits). Saves compact arrays to scratchpad/hunt/h30/sim_<U>.npz.

python3 -I research/hunt/h30/sim.py NIFTY
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy.costs import Costs, Fills  # noqa: E402
from obuy.data import dnum  # noqa: E402
from obuy.engine import LADDER, Execution, Exits, Pack, Store  # noqa: E402
import sig  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h30")
HS = {"BANKNIFTY": 0.0016, "NIFTY": 0.0016, "MIDCPNIFTY": 0.0021, "FINNIFTY": 0.0042, "SENSEX": 0.0020}
NRAND = 20


def exit_menu():
    m = [("ARM", Exits(stop_pct=0.15, time_stop=20, time_gain=0.05, sig_levels=False)),
         ("P15_30", Exits(stop_pct=0.15, tgt_pct=0.30, sig_levels=False)),
         ("LADDER", Exits(stop_pct=0.15, ladder=LADDER, ladder_ref_pct=0.30, sig_levels=False)),
         ("VWAPX", Exits(stop_pct=0.30, sig_levels=True)),
         ("T15", Exits(time_stop=15, time_gain=None, sig_levels=False)),
         ("T30", Exits(time_stop=30, time_gain=None, sig_levels=False)),
         ("T60", Exits(time_stop=60, time_gain=None, sig_levels=False))]
    for t in (15, 20, 25, 30):
        for s in (10, 15, 20):
            m.append((f"PT{t}_{s}", Exits(stop_pts=s, tgt_pts=t, sig_levels=False)))
    return m


def first_valid_from(O, rows, col, delay=2):
    c0 = np.full(len(rows), -1)
    for k in range(delay + 1):
        cc = np.minimum(col + k, sig.W - 1)
        ok = (c0 < 0) & ~np.isnan(O[rows, cc]) & (col + k <= sig.W - 1)
        c0 = np.where(ok, cc, c0)
    return c0


def run(und):
    t0 = time.time()
    P = np.load(os.path.join(OUT, f"paths_{und}.npy"))
    meta = pd.read_pickle(os.path.join(OUT, f"meta_{und}.pkl"))
    meta = meta.reset_index(drop=True)
    lim = int(os.environ.get("H30_N", "0"))
    if lim:
        P, meta = P[:lim], meta.iloc[:lim].copy()
        meta["pair"] = np.where(meta.pair < lim, meta.pair, -1)
        und_tag = und + "_test"
    else:
        und_tag = und
    N = len(meta)
    D = sig.Day(P)
    DEFS = sig.defs()
    ENT = np.stack([sig.entries(D, meta, f, tf) for f, tf in DEFS])          # (54, N) entry column or -1
    print(und, "signals", ENT.shape, "fire rate", (ENT >= 0).mean(axis=1).round(2).tolist(), f"{time.time()-t0:.0f}s",
          flush=True)
    rng = np.random.default_rng(30 + len(und))
    RND = rng.integers(sig.E_LO, sig.E_HI + 1, size=(N, NRAND))
    O = D.O
    # fills: first bar within 2 minutes
    rr = np.repeat(np.arange(N)[None, :], len(DEFS), axis=0)
    real_c0 = np.where(ENT >= 0, first_valid_from(O, rr.ravel(), np.maximum(ENT, 0).ravel()).reshape(ENT.shape), -1)
    rnd_c0 = first_valid_from(O, np.repeat(np.arange(N), NRAND), RND.ravel()).reshape(N, NRAND)
    # unique (row, c0)
    keys = np.concatenate([(rr * 1000 + real_c0)[real_c0 >= 0], (np.arange(N)[:, None] * 1000 + rnd_c0)[rnd_c0 >= 0]])
    U = np.unique(keys)
    urow, uc0 = U // 1000, U % 1000
    print(und, "unique trades", len(U), flush=True)
    real_u = np.where(real_c0 >= 0, np.searchsorted(U, rr * 1000 + real_c0), -1).astype(np.int32)
    rnd_u = np.where(rnd_c0 >= 0, np.searchsorted(U, np.arange(N)[:, None] * 1000 + rnd_c0), -1).astype(np.int32)

    hs = HS[und]
    bse = und in C.BSE
    e_raw = np.round(O[urow, uc0], 2)
    e_fill = np.round(e_raw * 1.0005 * (1 + hs), 2)
    lot = meta.lot.values[urow].astype(float)
    # VWAP-loss exit: first 1-min close below VWAP at/after the entry column -> exit at the next open
    below = D.Cf < D.vwap
    nxt = np.where(below, np.arange(sig.W)[None, :], 10 ** 6)
    nxt = np.minimum.accumulate(nxt[:, ::-1], axis=1)[:, ::-1]
    jb = nxt[urow, uc0]
    vx_at = np.where(jb < 10 ** 6, jb + 1 + C.OPEN_M, np.nan)

    st = Store()
    st.arr = {k: P[:, i, :] for i, k in enumerate(("O", "H", "L", "Cl", "V"))}
    st.arr["IH"] = np.full((1, sig.W), np.nan, np.float32)
    st.arr["IL"] = np.full((1, sig.W), np.nan, np.float32)
    days = meta.day.values[urow]
    m = pd.DataFrame(dict(cand=np.arange(len(U)), parent=-1, und=und, book="b", day=days,
                          dn=[dnum(d) for d in days], sig_min=uc0 - 1 + C.OPEN_M, gate=uc0 + C.OPEN_M, side=1,
                          right="C", strike=meta.strike.values[urow], lot=lot, qty=lot, tag="", c0=uc0, e=e_fill,
                          e1=e_fill, e2=np.nan, ref=np.nan, idx_stop=np.nan, idx_target=np.nan, exit_at=np.nan,
                          bse=bse, crow=urow, crow2=urow, irow=0))
    pk = Pack(m, st, 1)
    m2 = m.copy()
    m2["exit_at"] = vx_at
    pk2 = Pack(m2, st, 1)
    exe = Execution(fills=Fills("flat", pts=0.0), costs=Costs("app"))
    co = Costs("app")
    EX = exit_menu()
    G = np.full((len(U), len(EX)), np.nan, np.float32)
    NET = np.full((len(U), len(EX)), np.nan, np.float32)
    XM = np.full((len(U), len(EX)), -1, np.int16)
    for j, (name, ex) in enumerate(EX):
        tr = (pk2 if name == "VWAPX" else pk).run(ex, exe)
        cid = tr.cand.values.astype(int)
        x_raw = tr["exit"].values
        stop = (tr.why.values == "stop")
        x_fill = np.round(x_raw * np.where(stop, 1 - 0.001, 1 - 0.0005) * (1 - hs), 2)
        q = tr.qty.values
        chg = co.charge(True, e_fill[cid], q, None, bse) + co.charge(False, x_fill, q, None, bse)
        G[cid, j] = (x_raw - e_raw[cid]) * q
        NET[cid, j] = (x_fill - e_fill[cid]) * q - chg
        XM[cid, j] = tr.exit_min.values - C.OPEN_M
        print(f"  {und} {name}: {len(tr)} trades, {time.time()-t0:.0f}s", flush=True)
    np.savez(os.path.join(OUT, f"sim_{und_tag}.npz"), ENT=ENT.astype(np.int16), real_u=real_u, rnd_u=rnd_u, urow=urow.astype(np.int32),
             uc0=uc0.astype(np.int16), e_raw=e_raw.astype(np.float32), qty=lot.astype(np.float32), G=G, NET=NET, XM=XM)
    print(und, "done", f"{time.time()-t0:.0f}s", flush=True)


if __name__ == "__main__":
    for u in sys.argv[1:]:
        run(u)
