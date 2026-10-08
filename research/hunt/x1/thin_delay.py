"""X1 part 2 checks on history (Liquidity 15+5 trades from h19's replay, BN + FIN, Aug 2021 - 6 Oct 2026; MIDCP from the
h4 ext port run here on the same data):
 T  apply the ThinOption gate (shipped 8 Oct for Pine only) to Liquidity's buys: >= 4 of the 6 minutes before the entry
    traded and >= 25 lots in all. Net of the trades it would refuse vs keep.
 L  entry latency: what a 1- or 2-minute later buy costs (the exits are index events, so the trade's P&L moves by the
    premium change between the signal minute's open and the later minute's open, x lot).
    flock <scratch>/obuy.lock python3 -I research/hunt/x1/thin_delay.py"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import replay as R
import numpy as np, pandas as pd
from datetime import date
from obuy.data import market
from obuy import config as C
import sim

HOLD = pd.Timestamp("2025-10-01")


def main():
    mk = market()
    t = pd.read_parquet(f"{R.SCR}/hunt/h19/trades.parquet"); t["day"] = pd.to_datetime(t.day)
    liq = t[t.arm.isin(["liq_bn", "liq_fin"])].copy()
    # MIDCP history via the ext port (validated against h24's table only loosely; used for the gate's direction only)
    R.START = date(2021, 1, 1)
    sig, tr = R.liq(mk, ("MIDCPNIFTY",), 1.0, 1)
    tr = tr.assign(day=pd.to_datetime(tr.day), arm="liq_midcp", lot=tr.qty)
    liq = pd.concat([liq, tr[["und", "day", "arm", "book", "entry_min", "exit_min", "side", "strike", "lot", "entry", "exit", "why", "gross", "net"]]])
    out = []
    for r in liq.itertuples():
        d = r.day.date()
        ch = mk.options(r.und).chain(d, "near")
        if ch is None:
            continue
        i = int(np.searchsorted(ch.K, int(r.strike)))
        if i >= len(ch.K) or ch.K[i] != int(r.strike):
            continue
        rt = "C" if r.side > 0 else "P"
        c0 = int(r.entry_min) - C.OPEN_M
        v = ch.v[rt][i]; o = ch.o[rt][i]
        win = v[max(0, c0 - 6):c0]
        traded = int(np.sum(np.nan_to_num(win) > 0)); lots = np.nansum(win) / max(1, r.lot)
        late = {k: (o[c0 + k] - o[c0]) if c0 + k < C.W and not np.isnan(o[c0 + k]) and not np.isnan(o[c0]) else np.nan for k in (1, 2)}
        out.append(dict(und=r.und, day=r.day, net=r.net, lot=r.lot, traded=traded, lots6=lots,
                        thin=(traded < 4) or (lots < 25), late1=-late[1] * r.lot, late2=-late[2] * r.lot))
        if len(out) % 500 == 0:
            mk.release()
    df = pd.DataFrame(out)
    df.to_csv(f"{R.SCR}/hunt/x1/thin_delay.csv", index=False)
    sess = pd.Index(sorted(t.day.unique()))
    nd = {"pre": (sess < HOLD).sum(), "hold": (sess >= HOLD).sum()}
    for u, g in df.groupby("und"):
        for lab, sel in (("pre", g.day < HOLD), ("hold", g.day >= HOLD)):
            x = g[sel]
            th = x[x.thin]
            print(f"{u} {lab}: trades {len(x)} net {x.net.sum():+,.0f} | ThinOption would refuse {len(th)} ({len(th)/max(1,len(x)):.0%}) "
                  f"worth {th.net.sum():+,.0f} ({th.net.mean() if len(th) else 0:+.0f}/trade); kept {x.net.sum()-th.net.sum():+,.0f} "
                  f"({(x.net.sum()-th.net.sum())/nd[lab]:+.0f}/day vs {x.net.sum()/nd[lab]:+.0f}) | entry 1 min late: {x.late1.mean():+.0f}/trade, "
                  f"2 min late: {x.late2.mean():+.0f}/trade (median {x.late1.median():+.0f} / {x.late2.median():+.0f})")


if __name__ == "__main__":
    main()
