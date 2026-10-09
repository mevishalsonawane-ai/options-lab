"""R2 robustness (holdout, after opening): re-price the C1-C3 futures trades with REAL futures 1-min OHLC (Dhan live
contracts, m3/raw/fut_live.parquet, mid-2026 only): entry = OPEN of the 09:06 bar (one minute later than the frozen rule),
exit = close at the exit minute. Same days, same sides. Also: how stale is the 'spot' field (zero-change minutes)."""
import numpy as np, pandas as pd
from lib import *
assert (R2 / "holdout.lock").exists()
T = pd.read_parquet(OUT / "cand_trades_holdout.parquet")
T = T[(T.inst == "FUT") & T.rule.str.startswith(("C1", "C2", "C3"))]
f = pd.read_parquet(H / "m3/raw/fut_live.parquet"); f["ts"] = f.ts.dt.tz_localize(None)
CON = {"CRUDE": ["CRUDEOIL OCT", "CRUDEOILM OCT", "CRUDEOILM NOV"], "NATGAS": ["NATURALGAS OCT", "NATGASMINI OCT", "NATGASMINI NOV"],
       "GOLD": ["GOLDM NOV", "GOLDM DEC"], "SILVER": ["SILVERM NOV"]}
import sys; sys.path.insert(0, "/home/user/options-lab/research/hunt/m3"); import m3lib as M
out = []
for com in COMS:
    m = M.Market(MCX_SYM[com]); sp = pd.Series(m.F, index=pd.DatetimeIndex(m.ts))
    tt = T[T.com == com]
    for D in sorted(set(tt.date)):
        t905 = D + pd.Timedelta(hours=9, minutes=5)
        s0 = sp.asof(t905)
        best = None
        for c in CON[com]:
            g = f[(f.contract == c) & (f.ts.dt.normalize() == D)].set_index("ts")
            if len(g) < 100: continue
            g = g[g.volume > 0]
            if not len(g): continue
            p = g.close.asof(t905)
            if np.isfinite(p) and abs(np.log(p / s0)) < 0.004 and (best is None or g.volume.sum() > best[1].volume.sum()):
                best = (c, g)
        if best is None: continue
        c, g = best
        nb = g[g.index >= D + pd.Timedelta(hours=9, minutes=6)]
        if not len(nb): continue
        p_in = nb.open.iloc[0]
        for _, r in tt[tt.date == D].iterrows():
            xt = D + (pd.Timedelta(hours=14) if r.exit == "X14" else pd.Timedelta(hours=23, minutes=20))
            p_out = g.close.asof(xt)
            gr, nt = fut_pnl(com, r.side, p_in, p_out)
            out.append(dict(com=com, rule=r.rule, exit=r.exit, date=D, contract=c, side=r.side, net_spot=r.net, net_live=nt,
                            slip_bp=1e4 * r.side * np.log(p_in / s0)))
O = pd.DataFrame(out)
O.to_csv(OUT / "robust_live.csv", index=False)
pd.set_option("display.width", 250)
print(O.groupby(["com", "rule", "exit"]).agg(n=("net_live", "size"), net_spot=("net_spot", "mean"), net_live=("net_live", "mean"),
      slip_bp=("slip_bp", "mean"), first=("date", "min")).round(1).to_string())
