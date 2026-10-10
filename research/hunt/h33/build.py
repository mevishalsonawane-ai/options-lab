"""h33 events + option trade table (heavy data pass). See PREREG.md.

    flock <scratch>/obuy.lock python3 -I research/hunt/h33/build.py

1. Price shocks per index (W in 1,3; Z in 5,8; 30-min declustering), with vb at the shock minute.
2. News events (news_events.parquet) with the first move and its z for N = 1, 3, 5.
3. Candidates = every (index, day, signal minute) used by any event x both sides, plus a random bank (8 random
   minutes 09:25-14:30 per index-day, coin-flip side). 1-ITM nearest, expiry allowed, app fills + app charges, 1 lot.
4. All 8 exits on every candidate -> trades.parquet (spread is added in the analysis).
Writes <scratch>/hunt/h33/{shocks,newsev,trades}.parquet. Holdout rows are written but only test.py --holdout reads them.
"""
from __future__ import annotations

import os
import sys
import time

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import ddate, market  # noqa: E402
from obuy.engine import LADDER, Execution, Exits, StrikeRule, prepare_many  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h33")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
SQ = 15 * 60 + 10
EXITS = {
    "P15": Exits(tgt_pts=15, stop_pts=15, time_stop=30, time_gain=None, sq_off=SQ),
    "P20": Exits(tgt_pts=20, stop_pts=15, time_stop=30, time_gain=None, sq_off=SQ),
    "P25": Exits(tgt_pts=25, stop_pts=15, time_stop=30, time_gain=None, sq_off=SQ),
    "P30": Exits(tgt_pts=30, stop_pts=15, time_stop=30, time_gain=None, sq_off=SQ),
    "LIQ": Exits(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=SQ),
    "LOCK": Exits(stop_pts=15, ladder=LADDER, ladder_ref_pts=30, time_stop=30, time_gain=None, sq_off=SQ),
    "T15": Exits(time_stop=15, time_gain=None, sq_off=SQ),
    "T30": Exits(time_stop=30, time_gain=None, sq_off=SQ),
}
RULE = StrikeRule(1, series="near")
EXE = Execution(expiry="allow")
T0, T1 = 9 * 60 + 25, 14 * 60 + 30
NS = (1, 3, 5)


def price_shocks(u, f):
    out = []
    days = f["days"]
    for W in (1, 3):
        z = f[f"z{W}"]
        for Z in (5, 8):
            for i in range(len(days)):
                zz = z[i]
                nxt = T0
                for col in np.flatnonzero(np.abs(np.nan_to_num(zz)) >= Z):
                    m = col + C.OPEN_M
                    if m < nxt or m > T1:
                        continue
                    out.append(dict(und=u, day=ddate(days[i]), tmin=m, W=W, Z=Z, dir=int(np.sign(zz[col])),
                                    z=float(zz[col]), vb=float(f["vb"][i, col])))
                    nxt = m + 30
    return pd.DataFrame(out)


def news_moves(ev, F):
    rows = []
    for r in ev.itertuples():
        f = F[r.und]
        i = np.searchsorted(f["days"], (pd.Timestamp(r.day) - pd.Timestamp("1970-01-01")).days)
        if i >= len(f["days"]) or ddate(f["days"][i]) != r.day:
            continue
        c, sg = f["c"][i], f["sig"][i]
        col = r.hmin - C.OPEN_M
        for N in NS:
            if col - 1 < 0 or col + N >= C.W:
                continue
            a, b = c[col - 1], c[col + N]
            if not (np.isfinite(a) and np.isfinite(b) and np.isfinite(sg)):
                continue
            mv = b / a - 1
            rows.append(dict(cls=r.cls, und=r.und, day=r.day, hmin=r.hmin, n20=r.n20, nsrc=r.nsrc, slug=r.slug, N=N,
                             move=mv, zN=mv / (sg * np.sqrt(N + 1)), dir=int(np.sign(mv)) or 1))
    return pd.DataFrame(rows)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "price"      # 'price': shocks + random bank; 'news': news events only
    t0 = time.time()
    F = {u: dict(np.load(os.path.join(OUT, f"feats_{u}.npz"))) for u in UNDS}
    if mode == "price":
        sh = pd.concat([price_shocks(u, F[u]) for u in UNDS], ignore_index=True)
        sh.to_parquet(os.path.join(OUT, "shocks.parquet"))
        print("shocks", sh.groupby(["und", "W", "Z"]).size().unstack([1, 2]).to_string(), flush=True)
        cand = [sh.assign(sig_min=sh.tmin + N)[["und", "day", "sig_min"]] for N in NS]
    else:
        ev = pd.read_parquet(os.path.join(OUT, "news_events.parquet"))
        ev["day"] = pd.to_datetime(ev.day).dt.date
        nm = news_moves(ev, F)
        nm.to_parquet(os.path.join(OUT, "newsev.parquet"))
        print("news event-N rows", len(nm), flush=True)
        cand = [nm.assign(sig_min=nm.hmin + nm.N)[["und", "day", "sig_min"]]]
    rng = np.random.default_rng(33)
    cand = pd.concat(cand).drop_duplicates()
    cand = pd.concat([cand.assign(side=1), cand.assign(side=-1)])
    rb = []
    for u in (UNDS if mode == "price" else []):
        for d in F[u]["days"]:
            for m, s in zip(rng.integers(T0, T1 + 1, 8), rng.choice([-1, 1], 8)):
                rb.append((u, ddate(d), int(m), int(s)))
    rb = pd.DataFrame(rb, columns=["und", "day", "sig_min", "side"]).assign(rand=True)
    if not len(rb):
        rb = pd.DataFrame(columns=["und", "day", "sig_min", "side", "rand"])
    allc = pd.concat([cand.assign(rand=False), rb]).drop_duplicates(["und", "day", "sig_min", "side", "rand"])
    print("candidates", len(allc), "random bank", len(rb), flush=True)
    mk = market()
    res = []
    for u in UNDS:
        if not (allc.und == u).any():
            continue
        sig = allc[allc.und == u].reset_index(drop=True)
        sig["book"] = [f"c{i}" for i in range(len(sig))]
        sig["tag"] = np.where(sig.rand, "R", "E")
        (pk, _), = prepare_many([(sig[["und", "day", "sig_min", "side", "book", "tag"]], RULE, EXE, 0, None, False)])
        print(u, "packed", len(pk), f"{time.time() - t0:.0f}s", flush=True)
        for xn, ex in EXITS.items():
            tr = pk.run(ex, EXE)
            # premium exits are resting orders on the option's own 1-min HIGH/LOW (engine intrabar=True), stop first
            # when both are touched in one minute; 'tie' marks a stop/lock exit whose minute's HIGH also reached the
            # target (counted for the report)
            tr["tie"] = False
            if ex.tgt_pts and len(tr):
                Hm = pk.get("H", slice(None))
                pos = pd.Index(pk.meta.cand).get_indexer(tr.cand)
                hx = Hm[pos, (tr.exit_min - C.OPEN_M).clip(0, C.W - 1).values]
                tr["tie"] = tr.why.isin(["stop", "lock"]).values & (hx >= tr.entry.values + ex.tgt_pts - 1e-9)
                del Hm
            tr = tr[["und", "day", "sig_min", "side", "tag", "qty", "entry_min", "exit_min", "entry", "exit", "why",
                     "gross", "net", "tie"]].copy()
            tr["X"] = xn
            res.append(tr)
        mk.release(u)
        del pk
    tr = pd.concat(res, ignore_index=True)
    for c in ("entry", "exit", "gross", "net"):
        tr[c] = tr[c].astype(np.float32)
    for c in ("sig_min", "entry_min", "exit_min", "qty"):
        tr[c] = tr[c].astype(np.int32)
    tr["side"] = tr.side.astype(np.int8)
    tr.to_parquet(os.path.join(OUT, f"trades_{mode}.parquet"))
    print("trades", len(tr), f"{time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
