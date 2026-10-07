"""h22: h19's ORB-family port on 28 Sep - 6 Oct with and without the profit-lock ladder (the ladder reached the phone
only with the build of 1 Oct ~12:29 IST at the earliest), 1 lot, app paper fills and charges."""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE))); sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h19"))
import obuy  # noqa
import pandas as pd
from datetime import date
from obuy.data import market
import sim as H19
mk = market(); H19.supplement(mk, "BANKNIFTY")
days = [d for d in mk.index("BANKNIFTY").days if date(2026, 9, 28) <= d <= date(2026, 10, 6)]
out = []
for lad in (False, True):
    H19.LADDER_ON = lad
    for d in days:
        for r in H19.orb_day(mk, d):
            r.pop("path"); r["ladder"] = lad; out.append(r)
df = pd.DataFrame(out)
df.to_pickle(os.path.join(obuy.config.SCRATCH, "hunt", "h22", "orb_window.pkl"))
print(df.pivot_table(index=["ladder", "day"], columns="arm", values="net", aggfunc="sum").round(0).to_string())
print(df.pivot_table(index=["ladder", "day"], columns="arm", values="net", aggfunc="count").to_string())
