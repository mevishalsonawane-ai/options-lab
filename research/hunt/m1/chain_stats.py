"""Option liquidity per MCX underlying from the Dhan chain snapshot (8 Oct 2026 session close)."""
import sys, glob, pandas as pd, numpy as np
D = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/m1/data"
f = sorted(glob.glob(f"{D}/chain_*.parquet"))[-1]; c = pd.read_parquet(f)
# units per lot for option premium (quote unit -> lot)
MULT = dict(CRUDEOIL=100, CRUDEOILM=10, NATURALGAS=1250, NATGASMINI=250, GOLD=100, GOLDM=10, SILVER=30, SILVERM=5,
            COPPER=2500, ZINC=5000, MCXBULLDEX=30)
print("snapshot", f)
for (s, e), g in c.groupby(["sym", "expiry"], sort=True):
    ks = np.sort(g.strike.unique()); step = np.median(np.diff(ks))
    pv = g[g.vol > 0].pivot_table(index="strike", columns="type", values="ltp")
    pv = pv.dropna() if {"CE","PE"} <= set(pv.columns) else pv
    if len(pv) == 0 or not {"CE","PE"} <= set(pv.columns):
        print(f"{s:11s} {e} no two-sided trades; vol_all={int(g.vol.sum())} OI={int(g.oi.sum())}"); continue
    atm = (pv.CE - pv.PE).abs().idxmin(); u = atm + pv.CE[atm] - pv.PE[atm]
    a = g[g.strike == atm]
    near = g[(g.strike - atm).abs() <= 3 * step]
    sp = ((near.ask - near.bid) / ((near.ask + near.bid) / 2)).where((near.bid > 0) & (near.ask > 0))
    m = MULT[s]
    prem = (a.ltp.mean()) * m
    print(f"{s:11s} {e} und={u:>10} step={step:g} ATMprem/lot~Rs{prem:,.0f} (CE {a[a.type=='CE'].ltp.values} PE {a[a.type=='PE'].ltp.values}) "
          f"vol_all={int(g.vol.sum()):,} vol_atm±3={int(near.vol.sum()):,} OI_all={int(g.oi.sum()):,} "
          f"med_spread_atm±3={np.nanmedian(sp)*100:.2f}% ATM_IV={a.iv.mean():.1f}")
