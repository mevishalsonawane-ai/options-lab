"""NN-CRUDE trade translation: per decision point and horizon, the net P&L of
  (a) 1 lot CRUDEOILM futures (10 bbl) long/short, entry at spot[t], exit at spot[t+h];
  (b) buying 1 lot CRUDEOIL ATM option (100 bbl): CE for long, PE for short, same strike held to t+h.
Costs: Zerodha charges (common.zerodha_costs) + spread. Futures: measured top-of-book spread 2 Rs/bbl for
CRUDEOILM (half each side). Options: measured ATM spread 0.13% of premium (chain snapshots), floor 1 tick (Rs 0.10),
'stress' = 3x. Output: data/pnl_grid.parquet"""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from common import *

FUT_SPREAD = 2.0          # Rs/bbl full spread, CRUDEOILM (quote snapshots 8 Oct 2026)
OPT_SPREAD_PCT = 0.0013   # full spread / premium, CRUDEOIL near-month ATM (chain snapshots 8 Oct 2026)


def build():
    F = pd.read_parquet(f"{D}/feat_mcx.parquet")
    r = roll_parts("CRUDEOIL", 1)
    px = r.set_index(["type", "strike", "ts"]).close.sort_index()
    px = px[~px.index.duplicated()]
    vol = r.set_index(["type", "strike", "ts"]).volume.sort_index()
    vol = vol[~vol.index.duplicated()]
    out = []
    for h in (15, 30, 60):
        G = pd.DataFrame(index=F.index)
        G["h"] = h
        s0 = F.spot.values.astype(float)
        s1 = s0 * np.exp(F[f"y_ret{h}"].values.astype(float))
        G["fut_gross_long"] = (s1 - s0) * LOT["CRUDEOILM"]
        chg = np.array([zerodha_costs("fut", a, b, LOT["CRUDEOILM"]) if np.isfinite(b) else np.nan for a, b in zip(s0, s1)])
        G["fut_cost"] = chg + FUT_SPREAD * LOT["CRUDEOILM"]
        K = F.atm_strike.values.astype(float)
        t1 = F.index + pd.Timedelta(minutes=h)
        for typ, side in (("C", "long"), ("P", "short")):
            e = F.ce.values if typ == "C" else F.pe.values
            keys = list(zip([typ] * len(F), K, t1))
            x = px.reindex(pd.MultiIndex.from_tuples(keys, names=["type", "strike", "ts"])).values.astype(float).copy()
            # strike drifted beyond ATM+-3 by t+h: price = nearest available strike's close + intrinsic difference
            miss = np.isnan(x) & np.isfinite(s1)
            if miss.any():
                rt = r[(r.type == typ)].set_index("ts")
                for i in np.where(miss)[0]:
                    try:
                        rows = rt.loc[[t1[i]]]
                    except KeyError:
                        continue
                    j = (rows.strike - K[i]).abs().values.argmin()
                    kn, pn, S = rows.strike.values[j], rows.close.values[j], s1[i]
                    intr = (lambda k: max(S - k, 0.0)) if typ == "C" else (lambda k: max(k - S, 0.0))
                    x[i] = max(pn + intr(K[i]) - intr(kn), 0.05)
            G[f"opt_fallback_{side}"] = miss.astype(float)
            ev = vol.reindex(pd.MultiIndex.from_tuples(list(zip([typ] * len(F), K, F.index)), names=["type", "strike", "ts"])).values
            G[f"opt_entry_{side}"] = e
            G[f"opt_exit_{side}"] = x
            G[f"opt_entry_vol_{side}"] = ev
            G[f"opt_gross_{side}"] = (x - e) * LOT["CRUDEOIL"]
            ch = np.array([zerodha_costs("opt", a, b, LOT["CRUDEOIL"]) if np.isfinite(a) and np.isfinite(b) else np.nan for a, b in zip(e, x)])
            spr = np.maximum(OPT_SPREAD_PCT * (e + x) / 2, 0.10) * LOT["CRUDEOIL"]
            G[f"opt_cost_{side}"] = ch + spr
            G[f"opt_cost_stress_{side}"] = ch + 3 * spr
        out.append(G)
    P = pd.concat(out)
    P = P.astype({c: "float32" for c in P.columns})
    P.to_parquet(f"{D}/pnl_grid.parquet", compression="zstd")
    for h in (15, 30, 60):
        g = P[P.h == h]
        print(f"h={h}: option exit found {g.opt_exit_long.notna().mean():.3f}/{g.opt_exit_short.notna().mean():.3f}; "
              f"entry minute had volume>0 {np.mean(g.opt_entry_vol_long > 0):.3f}; "
              f"mean fut cost Rs {g.fut_cost.mean():.1f}; mean opt cost Rs {g.opt_cost_long.mean():.1f}; "
              f"mean |fut gross| Rs {g.fut_gross_long.abs().mean():.0f}; mean |opt gross| Rs {g.opt_gross_long.abs().mean():.0f}")
        print(f"   always-long fut net/trade Rs {(g.fut_gross_long - g.fut_cost).mean():.1f}; always-buy-CE net Rs {(g.opt_gross_long - g.opt_cost_long).mean():.1f}; always-buy-PE net Rs {(g.opt_gross_short - g.opt_cost_short).mean():.1f}")


if __name__ == "__main__":
    build()
