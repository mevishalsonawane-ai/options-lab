"""h19: the 28 Sep - 6 Oct window under the rules IN FORCE on each date (as far as the repo's history says):
ORB family without the profit-lock ladder before 1 Oct 2026 (ladder added 1 Oct); Liquidity 15+5 ATM with no room filter
before 6 Oct (1-ITM + room >= 1 index stop from 06 Oct 09:39). Prints per-arm per-day totals for both rule sets.
    flock <scratch>/obuy.lock python3 -I research/hunt/h19/variants.py
"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE))); sys.path.insert(0, HERE); sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))
import obuy  # noqa
import pandas as pd
from datetime import date
from obuy.data import market
import sim

mk = market()
for u in ("BANKNIFTY", "FINNIFTY"):
    sim.supplement(mk, u)
days = [d for d in mk.index("BANKNIFTY").days if d >= date(2026, 9, 28)]
rows = []
for lad in (True, False):
    sim.LADDER_ON = lad
    for d in days:
        for r in sim.orb_day(mk, d):
            r.pop("path"); r["variant"] = "ladder" if lad else "no_ladder"; rows.append(r)
sim.LADDER_ON = True
for room, money, lab in ((1.0, 1, "itm1_room"), (0.0, 0, "atm_noroom")):
    sig, tr = sim.liquidity(mk, room=room, money=money)
    tr = tr[pd.to_datetime(tr.day) >= "2026-09-28"]
    for t in tr.itertuples():
        rows.append(dict(day=pd.Timestamp(t.day).date(), arm="liq_" + ("bn" if t.und == "BANKNIFTY" else "fin"), book=t.book,
                         entry_min=t.entry_min, exit_min=t.exit_min, side=t.side, strike=t.strike, lot=t.qty, entry=t.entry,
                         exit=t.exit, why=t.why, gross=t.gross, net=t.net, variant=lab, und=t.und))
df = pd.DataFrame(rows)
df.to_csv(os.path.join(sim.OUT, "variants_recent.csv"), index=False)
pd.set_option("display.width", 250)
print(df.pivot_table(index="day", columns=["variant", "arm"], values="net", aggfunc="sum").fillna(0).round(0).T.to_string())
print(df.pivot_table(index="day", columns=["variant"], values="net", aggfunc="size").fillna(0).T.to_string())
x = df[(df.variant == "atm_noroom")]
print(x[["day", "book", "entry_min", "exit_min", "side", "strike", "entry", "exit", "why", "net"]].to_string())
