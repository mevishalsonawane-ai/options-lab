"""h37 stage 4: the ONE holdout look (passers of either gate; top 10 by pre-holdout t for information) plus the
random-entry bar per index. Reads hold_sealed*.parquet for the first time here.

    OBUY_CACHE=<scratch>/hunt/h37/cache flock <scratch>/obuy.lock python3 -I research/hunt/h37/final.py
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.data import market  # noqa: E402
import evaluate as EV  # noqa: E402

CAP = 100_000


def tdays(mk):
    out = {}
    for u in EV.UNDS:
        o = np.array([d.toordinal() for d in mk.index(u).days])
        out[u] = ((o < EV.HOLD).sum(), (o >= EV.HOLD).sum())
    return out


def maxdd(daily):
    c = np.cumsum(daily)
    return float((np.maximum.accumulate(np.concatenate([[0], c]))[1:] - c).max()) if len(c) else 0.0


def describe(tr, u, nd):
    rows = {}
    for per, m, n_days in (("pre", tr.pre.values, nd[u][0]), ("hold", ~tr.pre.values, nd[u][1])):
        t = tr[m]
        daily = t.groupby("ord").net.sum().sort_index().values
        rd = t.net.sum() / n_days
        prem = float(np.nanmedian(t.prem)) if len(t) else np.nan
        rows[per] = dict(n=len(t), gross=t.gross.sum(), net=t.net.sum(), stress=t.stress.sum(), rand=t.m0.sum(),
                         rs_day=rd, maxdd=maxdd(daily), worst_day=float(daily.min()) if len(daily) else 0.0,
                         ties=int(t.tie.sum()) if "tie" in t else 0, prem_lot=prem,
                         lots_5k=(5000 / rd) if rd > 0 else np.inf,
                         lots_1lakh=int(CAP // prem) if prem > 0 else 0)
    return rows


def main():
    mk = market()
    nd = tdays(mk)
    pas = pd.read_csv(os.path.join(EV.OUT, "passers.csv"))
    top = pd.read_csv(os.path.join(EV.OUT, "top10.csv"))
    look = pd.concat([pas.assign(set="passer"), top.assign(set="top10 (info)")]).drop_duplicates("vid")
    H = pd.concat([pd.read_parquet(os.path.join(EV.OUT, "hold_sealed.parquet")),
                   pd.read_parquet(os.path.join(EV.OUT, "hold_fx_sealed.parquet"))], ignore_index=True)
    H["vid"] = H.und + "|" + H.sig + "|" + H.tf + "|" + H.par + "|" + H.exit
    fx = pd.read_parquet(os.path.join(EV.OUT, "fx_trades.parquet"))
    out = []
    for _, r in look.iterrows():
        u, sig, tf, par, k = r.vid.split("|")
        if k.startswith("FX"):
            t = fx[(fx.und == u) & (fx.sig == sig) & (fx.tf == tf) & (fx.par == par) & (fx.exit == k)].copy()
            t["tie"] = 0
        else:
            t = EV.trades_for(u, sig, tf if tf in ("D",) else (int(tf)), par, k, mk)
        d = describe(t, u, nd)
        h = H[H.vid == r.vid].iloc[0]
        assert abs(h.net - d["hold"]["net"]) < 1e-3 * max(1, abs(h.net)), (r.vid, h.net, d["hold"]["net"])
        row = dict(set=r.set, why=r.get("why", ""), vid=r.vid)
        for per in ("pre", "hold"):
            for kk, v in d[per].items():
                row[f"{per}_{kk}"] = v
        out.append(row)
    R = pd.DataFrame(out)
    R.to_csv(os.path.join(EV.OUT, "final.csv"), index=False)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 50)
    cols = ["set", "vid", "pre_n", "pre_gross", "pre_net", "pre_rs_day", "hold_n", "hold_gross", "hold_net",
            "hold_stress", "hold_rand", "hold_rs_day", "hold_maxdd", "hold_worst_day", "hold_ties", "hold_prem_lot",
            "hold_lots_5k", "hold_lots_1lakh"]
    print(R[cols].round(1).to_string())
    print("\npassers positive in holdout:", int((R[R.set == "passer"].hold_net > 0).sum()), "of",
          int((R.set == "passer").sum()))
    print("top10 positive in holdout:", int((R[R.set != "passer"].hold_net > 0).sum()), "of", int((R.set != "passer").sum()))


def baseline():
    """Random-entry bar per index (pre-holdout, every minute, both sides, real spread), per exit."""
    rows = []
    for u in EV.UNDS:
        tb = EV.load_table(u)
        pre = tb["days"] < EV.HOLD
        for k in EV.EXITS:
            r = tb[f"r_{k}"][pre]
            g = tb[f"g_{k}"][pre]
            rows.append(dict(und=u, exit=k, n=int((~np.isnan(r)).sum()), gross=float(np.nanmean(g)),
                             net=float(np.nanmean(r)), ties_pct=100 * float(tb[f"tie_{k}"][pre].sum() / (~np.isnan(r)).sum())))
        del tb
    B = pd.DataFrame(rows)
    B.to_csv(os.path.join(EV.OUT, "baseline.csv"), index=False)
    print(B.groupby("und").agg(gross_best=("gross", "max"), gross_worst=("gross", "min"), net_best=("net", "max"),
                               net_worst=("net", "min")).round(1).to_string())
    print(B.pivot(index="exit", columns="und", values="net").round(1).to_string())
    print(B.pivot(index="exit", columns="und", values="ties_pct").round(3).to_string())


if __name__ == "__main__":
    if sys.argv[1:] == ["baseline"]:
        baseline()
    else:
        main()
