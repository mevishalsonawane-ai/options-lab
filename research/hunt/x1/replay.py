"""X1: replay the app's arms on 1 Sep - 8 Oct 2026 with h19's validated ports (ORB family; Liquidity 15+5 BN/FIN via the
h4 port + obuy engine) plus MIDCPNIFTY Liquidity (h4 ext port, index stop 8), on the overlay data that adds 6 Oct pm,
7 Oct and 8 Oct (overlay.py). Writes scratchpad/hunt/x1/replay_trades.csv.
    flock <scratch>/obuy.lock python3 -I research/hunt/x1/replay.py
Variants: orb ladder as h19 (breakeven = entry) and 'be_chg' (breakeven = entry + round-trip charges, the app since 6 Oct);
Liquidity 'itm1_room' (app since 6 Oct 15:09) and 'atm_noroom' (app 1-6 Oct)."""
import os, sys
SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
os.environ["OBUY_DATA"] = f"{SCR}/hunt/x1/data"
os.environ["OBUY_CACHE"] = f"{SCR}/hunt/x1/cache"
HERE = os.path.dirname(os.path.abspath(__file__))
R = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, R); sys.path.insert(0, os.path.join(R, "hunt", "h19")); sys.path.insert(0, os.path.join(R, "hunt", "h4"))
import shutil
os.makedirs(os.environ["OBUY_CACHE"], exist_ok=True)
if not os.path.exists(f"{os.environ['OBUY_CACHE']}/ix_MIDCPNIFTY.pkl"):
    shutil.copy(f"{SCR}/obuy_cache/ix_MIDCPNIFTY.pkl", os.environ["OBUY_CACHE"])
import obuy  # noqa
import numpy as np, pandas as pd
from datetime import date
from obuy.data import market
from obuy.costs import Costs, Fills
import sim, comps

START = date(2026, 9, 1)


def liq(mk, unds, room, money):
    from obuy.engine import positions, prepare_many, Execution, StrikeRule
    from obuy.strategies import liquidity as LQ
    sig = comps.liq_ext_signals(mk, unds=unds, room=room)
    sig = sig[pd.to_datetime(sig.day) >= pd.Timestamp(START)]
    if money != 1:
        sig = sig.drop(columns=["strike"])
    exe = Execution(expiry="skip", fills=Fills(), costs=Costs())
    [(pk, _)] = prepare_many([(sig, StrikeRule(money=money), exe, 0, (comps.WIN_FROM - 1, comps.WIN_TO - 1), False)])
    tr = pk.run(LQ.ARM_EXITS, exe)
    return sig, positions(tr, one_at_a_time=True)


def main():
    mk = market()
    for u in ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"):
        print("supplement", u, sim.supplement(mk, u), flush=True)
    rows = []
    days = [d for d in mk.index("BANKNIFTY").days if d >= START]
    for var, floor in (("ladder", False), ("be_chg", True)):
        if floor:
            base_fill = sim.fill
            def lock_level_chg(e, tgt, peak, _ll=sim.lock_level):
                lv = _ll(e, tgt, peak)
                if lv is None:
                    return None
                # breakeven rung = entry + round-trip charges per unit (ProfitLock.kt:52-58, 6 Oct)
                be = e + (sim.COST.charge_exact(True, e, 30) + sim.COST.charge_exact(False, e, 30)) / 30.0
                lv = max(lv, be) if abs(lv - e) < 1e-6 else lv
                return lv if lv < peak else None
            sim.lock_level = lock_level_chg
        for d in days:
            for r in sim.orb_day(mk, d):
                r.pop("path"); r["variant"] = var; rows.append(r)
        if floor:
            sim.lock_level = lock_level_chg.__defaults__[0]
    for room, money, lab in ((1.0, 1, "itm1_room"), (0.0, 0, "atm_noroom")):
        for unds in (("BANKNIFTY", "FINNIFTY"), ("MIDCPNIFTY",)):
            sig, tr = liq(mk, unds, room, money)
            for t in tr.itertuples():
                a = {"BANKNIFTY": "liq_bn", "FINNIFTY": "liq_fin", "MIDCPNIFTY": "liq_midcp"}[t.und]
                rows.append(dict(day=pd.Timestamp(t.day).date(), arm=a, book=t.book, sig_bar=t.sig_min, entry_min=t.entry_min,
                                 exit_min=t.exit_min, side=t.side, strike=t.strike, lot=t.qty, entry=t.entry, exit=t.exit,
                                 why=t.why, gross=t.gross, charges=t.charges, net=t.net, variant=lab, und=t.und))
    df = pd.DataFrame(rows)
    df.to_csv(f"{SCR}/hunt/x1/replay_trades.csv", index=False)
    pd.set_option("display.width", 250)
    x = df[df.day >= date(2026, 9, 28)]
    print(x.pivot_table(index="day", columns=["variant", "arm"], values="net", aggfunc="sum").fillna(0).round(0).T.to_string())
    print(x.pivot_table(index="day", columns=["variant", "arm"], values="net", aggfunc="size").fillna(0).T.to_string())


if __name__ == "__main__":
    main()
