"""X1: the forward paper-test window (6-8 Oct 2026, see the report for why 6 Oct) replayed: per arm trades, net, win rate,
next to Boss's paper tests. ORB family 'be_chg' (app ladder with breakeven = entry + charges, resting stop moved up);
Liquidity: 6 Oct ATM / no room (the rules until 6 Oct 15:09), 7-8 Oct 1-ITM + room; BN, FIN, MIDCP.
    python3 -I research/hunt/x1/window.py"""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import pandas as pd
SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
APP = {"orb": (30, 5067.59, .60), "orb_fresh": (3, 2426.52, None), "orb_sweep": (6, -4497.99, None),
       "range_fade": (6, -1256.14, None), "liquidity": (20, -6186.42, .30)}
r = pd.read_csv(f"{SCR}/hunt/x1/replay_trades.csv"); m = pd.read_csv(f"{SCR}/hunt/x1/replay_midcp.csv")
r = pd.concat([r[r.arm != "liq_midcp"], m]); r["day"] = r.day.astype(str)
w = r[r.day.between("2026-10-06", "2026-10-08")]
orb = w[w.variant == "be_chg"]
liq = w[w.arm.str.startswith("liq") & (((w.day == "2026-10-06") & (w.variant == "atm_noroom")) | ((w.day > "2026-10-06") & (w.variant == "itm1_room")))]
liq = liq.assign(arm2="liquidity")
for a, g in list(orb.groupby("arm")) + [("liquidity", liq)]:
    n, s, wr = APP[a]
    print(f"{a:11s} replay {len(g):3d} trades {g.net.sum():+8,.0f} won {(g.net > 0).mean():.0%} | app {n:3d} trades {s:+9,.0f} won {wr if wr else '-'}"
          f" | by day {g.groupby('day').net.agg(['size','sum']).round(0).values.tolist()}")
print(liq.groupby(["day", "arm"]).net.agg(["size", "sum"]).round(0))
fr = orb[(orb.arm == "orb_fresh") & (orb.day < "2026-10-08")]
print("fresh 6-7 Oct (8 Oct parked by Jarvis):", len(fr), round(fr.net.sum()))
