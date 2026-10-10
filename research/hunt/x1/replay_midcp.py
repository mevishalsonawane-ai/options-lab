"""X1: MIDCPNIFTY Liquidity 15+5 for 1 Sep - 8 Oct 2026 (the cached MIDCP index holds 6 Oct only up to 13:14 from option
spots - not 'real' - which blocks the port for 10 days; drop it and re-add 6-8 Oct from the overlay).
    flock <scratch>/obuy.lock python3 -I research/hunt/x1/replay_midcp.py"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import replay as R  # sets OBUY_DATA / OBUY_CACHE
import pandas as pd
from datetime import date
from obuy.data import market
import sim

mk = market()
ix = mk.index("MIDCPNIFTY")
print("MIDCP cached last days", ix.days[-3:], [ix.d[d].get("real") for d in ix.days[-3:]], [len(ix.d[d]["m"]) for d in ix.days[-3:]])
for d in [d for d in ix.days if d >= date(2026, 10, 6)]:
    del ix.d[d]
ix.days = sorted(ix.d); ix.pos = {d: i for i, d in enumerate(ix.days)}; ix._mat = None; ix._daily = None
print("supplement", sim.supplement(mk, "MIDCPNIFTY"))
rows = []
for room, money, lab in ((1.0, 1, "itm1_room"), (0.0, 0, "atm_noroom")):
    sig, tr = R.liq(mk, ("MIDCPNIFTY",), room, money)
    for t in tr.itertuples():
        rows.append(dict(day=pd.Timestamp(t.day).date(), arm="liq_midcp", book=t.book, sig_bar=t.sig_min, entry_min=t.entry_min,
                         exit_min=t.exit_min, side=t.side, strike=t.strike, lot=t.qty, entry=t.entry, exit=t.exit, why=t.why,
                         gross=t.gross, charges=t.charges, net=t.net, variant=lab, und=t.und))
df = pd.DataFrame(rows)
df.to_csv(f"{R.SCR}/hunt/x1/replay_midcp.csv", index=False)
x = df[df.day >= date(2026, 9, 28)]
print(x.pivot_table(index="day", columns="variant", values="net", aggfunc=["sum", "size"]).fillna(0).round(0).to_string())
for t in x[x.day >= date(2026, 10, 6)].itertuples():
    print(t.variant, t.day, t.book, t.entry_min // 60, t.entry_min % 60, t.strike, t.side, t.entry, t.exit, t.why, round(t.net))
