"""x3 data check: does one (day, strike, right) row of a 'near' chain behave like ONE contract all day? Count
minute-to-minute print jumps that the index cannot explain (|option move| > 4 x |delta-free bound| ...): we use
|dlog option| > 20% while |dlog index| < 0.15%, on strikes within ATM+-3 (all 'near' days 2021-2026, every 4th day)."""
import sys
sys.path.insert(0, "/home/user/options-lab/research")
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pandas as pd
from obuy import config as C
from obuy.data import market
mk = market(); rows = []; ex = []
for u in ("NIFTY", "BANKNIFTY"):
    ix = mk.index(u); op = mk.options(u); M = ix.mat(); step = C.STEP[u]
    for d in ix.days[::4]:
        if d.year < 2021: continue
        ch = op.chain(d, "near")
        if ch is None: continue
        ic = M["c"][ix.pos[d]]
        mid = np.nanmedian(ic)
        sel = np.nonzero(np.abs(ch.K - mid) <= 3 * step)[0]
        for r in ("C", "P"):
            for j in sel:
                c = np.where(ch.v[r][j] > 0, ch.c[r][j], np.nan)
                ok = np.nonzero(~np.isnan(c))[0]
                if len(ok) < 10: continue
                a, b = ok[:-1], ok[1:]
                cons = (b - a) == 1
                dr = np.log(c[b] / c[a]); di = np.log(ic[b] / ic[a])
                big = cons & (np.abs(dr) > 0.20) & (np.abs(di) < 0.0015) & (c[a] > 20)
                rows.append(dict(und=u, year=d.year, exp=ix.d[d]["exp"], pairs=int(cons.sum()), jumps=int(big.sum())))
                for q in np.nonzero(big)[0][:2]:
                    ex.append((u, str(d), int(ch.K[j]), r, int(a[q]) + 555, float(c[a[q]]), float(c[b[q]]), float(ic[a[q]]), float(ic[b[q]])))
    mk.release(u)
R = pd.DataFrame(rows)
g = R.groupby(["und", "exp"])[["pairs", "jumps"]].sum(); g["per_100k"] = 1e5 * g.jumps / g.pairs
print(g.to_string())
print("examples:", *ex[:12], sep="\n  ")
