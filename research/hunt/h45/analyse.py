"""h45 analysis. python3 -I research/hunt/h45/analyse.py pre    (pre-holdout only -> pre.csv)
               python3 -I research/hunt/h45/analyse.py hold   (the locked holdout, run ONCE -> hold.csv)"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run as R  # noqa: E402
import core as K  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import stats as sst  # noqa: E402
from obuy.data import market  # noqa: E402

HOLDP = sys.argv[1] == "hold"


def load():
    D = pd.concat([pd.read_parquet(os.path.join(R.OUT, f"tr_{s}.parquet")) for s in ("liq", "h18", "champ", "rand")],
                  ignore_index=True)
    D = D[D.hold == HOLDP].reset_index(drop=True)
    D["bk"] = K.bucket(D.entry_min.values)
    return D


def groups(D):
    """(label, frame) per reported entry group."""
    out = []
    L = D[D.set == "liq"]
    out += [("Liquidity BN", L[L.und == "BANKNIFTY"]), ("Liquidity FIN", L[L.und == "FINNIFTY"]),
            ("Liquidity MIDCP", L[L.und == "MIDCPNIFTY"]), ("Liquidity BN+FIN+MIDCP", L)]
    H = D[D.set == "h18"]
    out += [("h18 r60 all 5", H)] + [(f"h18 r60 {u}", H[H.und == u]) for u in K.UNDS5]
    out += [("h43 CHAMPION BN", D[D.set == "champ"])]
    Rn = D[D.set == "rand"]
    out += [("random all 5", Rn)] + [(f"random {u}", Rn[Rn.und == u]) for u in K.UNDS5]
    return out


def main():
    D = load()
    mk = market()
    cal = {u: [d for d in mk.index(u).days if (d >= K.HOLD) == HOLDP] for u in K.UNDS5}
    Rn = D[D.set == "rand"]
    rmean = Rn.groupby(["und", "rule", "xi", "bk"]).NET.mean()
    rows = []
    for lab, G in groups(D):
        if not len(G):
            continue
        unds = sorted(set(G.und))
        days = sorted(set().union(*[set(cal[u]) for u in unds]))
        for (ri, xi), g in G.groupby(["rule", "xi"]):
            t, s = R.BRK[xi // 4]
            n = len(g)
            dl = g.groupby("day").NET.sum().reindex(days, fill_value=0.0)
            c = dl.cumsum().values
            dd = float((c - np.maximum.accumulate(np.concatenate([[0], c]))[1:]).min())
            sd = dl.std(ddof=1)
            tst = dl.mean() / sd * np.sqrt(len(dl)) if sd > 0 else 0.0
            notional = float((g.e_raw * g.qty).mean())
            cost = float((g.gross_mid - g.NET).mean())
            cpct = cost / notional
            net_t = float(g.NET.mean())
            tie_t = float(((g.tie | g.gapup) * 1.0).mean())
            half = float((g.NET + np.where(g.tie, 0.5, 0.0) * g.tgain + np.where(g.gapup, 1.0, 0.0) * g.tgain).mean())
            rm = np.nan
            if not lab.startswith("random"):
                key = pd.MultiIndex.from_arrays([g.und, np.full(n, ri), np.full(n, xi), g.bk])
                rv = rmean.reindex(key).values
                rm = float(np.nanmean(rv))
            rows.append(dict(group=lab, strike=K.RLAB[ri], exit=R.MLAB[xi], xi=xi, n=n, days=len(days),
                             tr_day=n / len(days), prem=float(g.e_raw.mean()), qty=float(g.qty.mean()),
                             hit_tgt=float((g.why == "target").mean()), hit_stop=float((g.why == "stop").mean()),
                             timeout=float(g.why.isin(["time_stop", "square_off"]).mean()),
                             tie=tie_t, cost_t=cost, cost_pct=cpct, be_hit=(s + cpct) / (t + s),
                             gross_t=float(g.gross_mid.mean()), net_t=net_t, net_t_tie50=half, stress_t=float(g.STRESS.mean()),
                             rand_t=rm, net_day=float(dl.mean()), gross_day=float(g.gross_mid.sum()) / len(days),
                             max_dd=dd, t=tst, p=float(1 - sst.norm.cdf(tst)),
                             trades_5k=(5000 / net_t if net_t > 0 else np.nan),
                             win_days=float((dl[dl != 0] > 0).mean())))
    T = pd.DataFrame(rows)
    real = ~T.group.str.startswith("random")
    from obuy.overfit import bh
    T["bh_q"] = np.nan
    T.loc[real, "bh_q"] = bh(T.loc[real, "p"].values)
    T.to_csv(os.path.join(R.OUT, "hold.csv" if HOLDP else "pre.csv"), index=False)
    pd.set_option("display.width", 250)
    cols = ["group", "strike", "exit", "n", "tr_day", "prem", "hit_tgt", "be_hit", "tie", "gross_t", "net_t", "net_t_tie50",
            "rand_t", "net_day", "max_dd", "t", "bh_q"]
    print(T[T.exit.str.startswith("+5/-5/1510")][cols].round(3).to_string())
    print(T[real].sort_values("net_t", ascending=False).head(15)[cols].round(3).to_string())
    print("positive net variants (real):", int((T[real].net_t > 0).sum()), "of", int(real.sum()),
          "| BH q<=0.10:", int((T.bh_q <= 0.10).sum()))


if __name__ == "__main__":
    main()
