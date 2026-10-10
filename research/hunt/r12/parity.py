"""R12: the replay's ORIGINAL exits must reproduce each source study's trades (net before the h24 spread)."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, pandas as pd
import lib12 as L

for name in (sys.argv[1:] or L.ARMS):
    a = L.Arm(name)
    F = L.trade_features(a)
    L.attach(a, F)
    tr = L.replay(a)
    mine = tr.net.values + (a.hs * (a.e1 + tr.exit.values) * a.qty)
    src = a.src["net"]
    d = mine - src
    exact = np.mean(np.abs(d) < 0.05)
    why = pd.Series(tr.why.values).value_counts().to_dict()
    print(f"{name:11s} n={a.n:5d} src={src.sum():12,.0f} mine={mine.sum():12,.0f} diff={d.sum():9,.0f} exact={exact:.3f} "
          f"spread={(a.hs*(a.e1+tr.exit.values)*a.qty).sum():9,.0f} why={why}", flush=True)
    if exact < 0.99:
        bad = np.argsort(-np.abs(d))[:5]
        for b in bad:
            print("   ", b, a.day[b].date(), a.c0[b], "src", a.src["why"][b], a.src.get("exit_min", [np.nan]*a.n)[b], a.src["exit"][b], round(src[b],1),
                  "mine", tr.why.values[b], tr.xcol.values[b] + 555, tr.exit.values[b], round(mine[b],1))
