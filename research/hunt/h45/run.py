"""h45: "1 lot, exit at +5% or -5%" - symmetric % premium brackets on Liquidity 15+5, random, h18_r60 and the h43 CHAMPION.

    flock <scratch>/obuy.lock python3 -I research/hunt/h45/run.py <set>     set in: liq h18 champ rand
Writes scratchpad/hunt/h45/tr_<set>.parquet: one row per (trade, strike rule, exit), pre-holdout AND holdout rows
(the column `hold` marks them; analyse.py reads the holdout rows only in its final, once-only step).
See PREREG.md.
"""
from __future__ import annotations

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
H34 = os.path.join(os.path.dirname(HERE), "h34")
sys.path.insert(0, H34)
sys.path.insert(1, os.path.join(os.path.dirname(HERE), "h43"))
import core as K  # noqa: E402   (h34: costs/post/strike rules/strategies)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import Execution, Exits, prepare_many, run_exits, positions  # noqa: E402
from obuy.lab import signals_cached  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h45")
BRK = ((0.03, 0.03), (0.05, 0.05), (0.07, 0.07), (0.10, 0.10), (0.05, 0.03), (0.10, 0.05))   # (target, stop)
TST = (None, 15, 30, 60)
MENU, MLAB = [], []
for t, s in BRK:
    for ts in TST:
        MENU.append(Exits(stop_pct=s, tgt_pct=t, time_stop=ts, time_gain=None, sq_off=K.SQ10, sig_levels=False))
        MLAB.append(f"+{t * 100:g}/-{s * 100:g}/{('t' + str(ts)) if ts else '1510'}")


def entry_sets(name):
    """[(signals, Execution, pos dict)] for one entry set (all days, pre + holdout)."""
    S = dict(K.strategies())
    mk = market()
    if name == "liq":
        st = S["liquidity15_5"]
        a = signals_cached(st, st.sig_grid[0])
        b = signals_cached(S["liq_ext"], {})
        b = b[b.und == "MIDCPNIFTY"]
        out = [(a, st.exe, st.pos), (b, S["liq_ext"].exe, S["liq_ext"].pos)]
    elif name == "h18":
        st = S["h18_r60"]
        out = [(signals_cached(st, st.sig_grid[0]), st.exe, st.pos)]
    elif name == "champ":
        import sigs
        s = sigs.make(mk.index("BANKNIFTY"), "champ", 5)
        out = [(s, Execution(expiry="skip"), dict(one_at_a_time=True, max_per_day=2))]
    elif name == "rand":
        rng = np.random.default_rng(45)
        rows = []
        for u in K.UNDS5:
            for d in mk.index(u).days:
                m = rng.integers(9 * 60 + 16, 15 * 60 + 5, 12)
                sd = rng.choice((1, -1), 12)
                rows += [dict(und=u, day=d, sig_min=int(m[k]) - 1, side=int(sd[k]), book=f"r{k}") for k in range(12)]
        out = [(pd.DataFrame(rows), Execution(expiry="allow"), None)]
    market().release()
    res = []
    for s, exe, pos in out:
        s = s.copy()
        for c in ("strike", "exit_day", "exit_min"):
            if c in s.columns:
                s = s.drop(columns=c)
        res.append((s.reset_index(drop=True), exe, pos))
    return res


def run(name):
    t0 = time.time()
    keep = ["und", "day", "entry_min", "exit_min", "side", "book", "why", "qty", "lot", "e_raw", "gross_mid", "NET",
            "STRESS", "net", "exit", "entry"]
    allr = []
    for s, exe0, pos in entry_sets(name):
        exe = K.exe_h34(exe0)
        packs = prepare_many([(s, r, exe, 0, None, False) for r in K.RULES])
        print(name, "signals", len(s), "packs", [len(p[0]) for p in packs], f"{time.time() - t0:.0f}s", flush=True)
        for ri, (pk, _) in enumerate(packs):
            acc = {xi: [] for xi in range(len(MENU))}
            for a in range(0, len(pk), 6000):
                sub = K._Sub(pk, slice(a, min(a + 6000, len(pk))))
                n = len(sub.meta)
                H, O = sub.get("H", None), sub.get("O", None)
                pos_of = {c: i for i, c in enumerate(sub.meta.cand.values)}
                for xi, ex in enumerate(MENU):
                    tr = run_exits(sub, slice(0, n), ex, exe)
                    if not len(tr):
                        continue
                    tr = K.post(tr)
                    rr = np.array([pos_of[c] for c in tr.cand.values])
                    col = tr.exit_min.values - 555
                    e = tr.entry.values
                    tgt = e * (1 + ex.tgt_pct)
                    trig = np.floor(e * (1 - ex.stop_pct) / 0.05 + 1e-9) * 0.05
                    st = tr.why.values == "stop"
                    hb, ob = H[rr, col], O[rr, col]
                    tie = st & (hb >= tgt - 1e-9) & (ob > trig) & (ob < tgt)
                    gapup = st & (ob >= tgt - 1e-9)          # bar opened above the target: target was really first
                    tfill = np.maximum(tgt, ob) * 0.9995
                    hs = tr.und.map(K.HS).values
                    tr["tgain"] = np.where(tie | gapup, (tfill - tr.exit.values) * tr.qty.values * (1 - hs), 0.0)
                    tr["tie"], tr["gapup"] = tie, gapup
                    acc[xi].append(tr)
                del sub
            for xi, v in acc.items():
                if not v:
                    continue
                tr = pd.concat(v, ignore_index=True)
                if pos is not None:
                    tr = positions(tr, **pos)
                tr = tr[keep + ["tgain", "tie", "gapup"]].copy()
                tr["rule"], tr["xi"] = ri, xi
                allr.append(tr)
            print(name, K.RLAB[ri], f"{time.time() - t0:.0f}s", flush=True)
    D = pd.concat(allr, ignore_index=True)
    D["hold"] = pd.to_datetime(D.day).dt.date >= K.HOLD
    D["set"] = name
    for c in ("gross_mid", "NET", "STRESS", "net", "tgain", "e_raw", "exit", "entry"):
        D[c] = D[c].astype(np.float32)
    D.to_parquet(os.path.join(OUT, f"tr_{name}.parquet"))
    print(name, "rows", len(D), f"{time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    run(sys.argv[1])
