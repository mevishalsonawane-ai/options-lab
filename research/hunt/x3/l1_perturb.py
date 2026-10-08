"""x3 look-ahead audit of the two shared signal builders (h25 sigs.compute: 61 candles, chart patterns, MAs,
indicators; h37 sigs.all_events: Fibonacci/Gann/harmonics/Elliott/Renko/P&F/Market Profile/Wyckoff).
For one test day at a time, the index minutes AFTER column CUT of that day (and every later day) are randomly
perturbed; every signal on bars that END before CUT on that day, and on all earlier days, must be identical.
    python3 -I research/hunt/x3/l1_perturb.py [h25|h37]
"""
import sys, os, importlib.util
sys.path.insert(0, "/home/user/options-lab/research")
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np
from obuy.data import market
H = "/home/user/options-lab/research/hunt"
def load(name, path):
    sys.path.insert(0, os.path.dirname(path))
    s = importlib.util.spec_from_file_location(name, path); m = importlib.util.module_from_spec(s); s.loader.exec_module(m); return m
which = sys.argv[1] if len(sys.argv) > 1 else "h25"
mk = market(); rng = np.random.default_rng(3)
CUT = 150
for u in (sys.argv[2].split(",") if len(sys.argv) > 2 else ("NIFTY", "BANKNIFTY")):
    ix = mk.index(u)
    M0 = {k: v.copy() for k, v in ix.mat().items()}
    nd = len(ix.days)
    for day in (nd - 400, nd - 200, nd - 30):
        M1 = {k: v.copy() for k, v in M0.items()}
        f = rng.uniform(0.97, 1.03, size=M1["c"][day:, :].shape)
        for k in M1:
            a = M1[k][day:, :].copy()
            a[0, :CUT] = M0[k][day, :CUT]            # the test day's morning stays clean
            a[0, CUT:] *= f[0, CUT:]; a[1:] *= f[1:]
            M1[k][day:, :] = a
        msk = np.zeros_like(M1["c"], bool); msk[day, CUT:] = True; msk[day + 1:, :] = True     # clamp only perturbed cells
        M1["h"] = np.where(msk, np.fmax(M1["h"], np.fmax(M1["o"], M1["c"])), M1["h"])
        M1["l"] = np.where(msk, np.fmin(M1["l"], np.fmin(M1["o"], M1["c"])), M1["l"])
        bad = 0; checked = 0
        if which == "h25":
            S = load("s25", f"{H}/h25/sigs.py")
            for tf in (1, 3, 5, 15, 30, 60):
                Ba, Bb = S.bars(M0, tf), S.bars(M1, tf)
                Sa, Sb = S.compute(Ba), S.compute(Bb)
                keep = (Ba["dpos"] < day) | ((Ba["dpos"] == day) & (Ba["col_end"] < CUT))
                for nm in Sa:
                    x, y = np.asarray(Sa[nm])[keep], np.asarray(Sb[nm])[keep]
                    checked += 1
                    if not np.array_equal(np.nan_to_num(x, nan=-9), np.nan_to_num(y, nan=-9)):
                        bad += 1; print("  DIFF", u, day, tf, nm, int((x != y).sum()))
        else:
            S = load("s37", f"{H}/h37/sigs.py")
            class F: pass
            def ev(M):
                ix._mat = M
                return S.all_events(ix)
            A, B = ev(M0), ev(M1)
            ix._mat = M0
            dord = ix.days[day].toordinal()
            for ra, rb in zip(A, B):
                checked += 1
                ka = {(o, m, s) for o, m, s in zip(ra["ord"], ra["mi"], ra["side"]) if o < dord or (o == dord and m < CUT)}
                kb = {(o, m, s) for o, m, s in zip(rb["ord"], rb["mi"], rb["side"]) if o < dord or (o == dord and m < CUT)}
                if ka != kb:
                    bad += 1; print("  DIFF", u, day, ra["sig"], ra["tf"], ra["par"], len(ka ^ kb))
        print(which, u, "test day", ix.days[day], "signal sets checked", checked, "with differences", bad, flush=True)
