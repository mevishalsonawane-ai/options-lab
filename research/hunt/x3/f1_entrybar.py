"""x3 fills check: the entry bar the obuy engine fills at (1-ITM 'near', open of sig_min+1), on a fixed random sample of
non-expiry days x minutes 09:15-15:00 x both sides (the h25 outcome-table universe).
  - how often that bar is a carried quote (volume 0, o=h=l=c) rather than a trade print;
  - for stale bars: stale price vs the next real print (what you would really pay), signed by the next index move;
  - gap between a print's open and the previous print's close (is the open a fresh, continuous price?).
    python3 -I research/hunt/x3/f1_entrybar.py
"""
import sys, os
sys.path.insert(0, "/home/user/options-lab/research")
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pandas as pd
from obuy import config as C
from obuy.data import market
rng = np.random.default_rng(33)
mk = market()
rows = []
for u in ("NIFTY", "BANKNIFTY", "FINNIFTY"):
    ix = mk.index(u); op = mk.options(u); M = ix.mat(); step = C.STEP[u]
    days = [d for d in ix.days if not ix.d[d]["exp"] and d.year >= 2021]
    pick = sorted(rng.choice(len(days), min(240, len(days)), replace=False))
    for i in pick:
        d = days[i]; ch = op.chain(d, "near")
        if ch is None: continue
        p = ix.pos[d]; ic = M["c"][p]
        for m in rng.choice(np.arange(555, 901), 12, replace=False):
            c = m - 555
            ref = ic[:c + 1][~np.isnan(ic[:c + 1])]
            if not len(ref): continue
            atm = np.floor(ref[-1] / step + 0.5) * step
            for side in (1, -1):
                r = "C" if side > 0 else "P"; k = int(atm - side * step)
                j = ch.kpos(k)
                if j < 0: continue
                O, Cl, V = ch.o[r][j], ch.c[r][j], ch.v[r][j]
                c0 = c + 1
                ok = np.nonzero(~np.isnan(O[c0:c0 + 3]))[0]
                if not len(ok): continue
                c0 += int(ok[0])
                stale = not (V[c0] > 0)
                nxt = np.nonzero(V[c0:] > 0)[0]
                nprint = O[c0 + nxt[0]] if len(nxt) else np.nan
                lag = int(nxt[0]) if len(nxt) else -1
                prev = np.nonzero(V[:c0] > 0)[0]
                pc = Cl[prev[-1]] if len(prev) else np.nan
                rows.append(dict(und=u, year=d.year, ser=ch.series, side=side, m=m, stale=stale, e=O[c0], nprint=nprint,
                                 lag=lag, prev_close=pc, gap_prev=(O[c0] / pc - 1) if (len(prev) and not stale) else np.nan))
    mk.release(u)
    print(u, "done", flush=True)
R = pd.DataFrame(rows)
R.to_parquet("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/x3/f1.parquet")
R["stale_vs_next_pct"] = np.where(R.stale, 100 * (R.e / R.nprint - 1), np.nan)
g = R.groupby(["und", "ser"]).agg(n=("e", "size"), stale_pct=("stale", "mean"), stale_vs_next_mean=("stale_vs_next_pct", "mean"),
                                  stale_vs_next_absmed=("stale_vs_next_pct", lambda s: s.abs().median()),
                                  lag_med=("lag", lambda s: s[s > 0].median()),
                                  gap_prev_abs_med_pct=("gap_prev", lambda s: 100 * s.abs().median()),
                                  gap_prev_mean_pct=("gap_prev", lambda s: 100 * s.mean()))
g["stale_pct"] *= 100
print(g.round(3).to_string())
print(R.groupby(["und", "year"]).stale.mean().unstack().mul(100).round(1).to_string())
