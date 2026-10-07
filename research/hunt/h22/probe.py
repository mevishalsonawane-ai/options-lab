import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE))); sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h19"))
import obuy
from obuy.data import market
from datetime import date
import sim as H19
mk = market()
for u in ("NIFTY", "BANKNIFTY"):
    print(u, "supp", H19.supplement(mk, u))
    ix = mk.index(u)
    print(u, ix.days[0], ix.days[-6:])
    for d in ix.days[-6:]:
        x = ix.d[d]; ch = mk.options(u).chain(d, "near")
        print(" ", d, len(x["m"]), x["m"][0], x["m"][-1], x.get("exp"), ix.lot(d), None if ch is None else (ch.series, ch.K.min(), ch.K.max()))
