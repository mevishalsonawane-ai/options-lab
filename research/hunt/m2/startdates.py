"""M2: start-date luck. Fresh Rs 1 lakh every 1 Jan and 1 Jul 2013-2024 (dev only), 2-year runs: ruin share, median outcome."""
import numpy as np, pandas as pd
import engine as E, opt as O
from prep import SPEC
data = E.load(E.DEV_END)
RATIO = {"CRUDE": 1.065, "NATGAS": 1.05, "GOLD": 0.961, "SILVER": 1.005}
rows = []
for key in ["PORT|ENS|LS", "PORT|ENS|LO", "PORT|TSM252|LS", "PORT4|ENS|LS", "PORT4|ENS|LO", "ZINC|TSM21|LO", "PORT_OPT|ENS|OPT"]:
    u, sig, mode = key.split("|")
    legs = list(SPEC) if u == "PORT" else (["CRUDE", "NATGAS", "GOLD", "SILVER"] if u in ("PORT4", "PORT_OPT") else [u])
    sg = {n: E.signal(data[n], sig) for n in legs}
    fin = []; dd = []
    for y in range(2013, 2023):
        for m in (1, 7):
            s0 = pd.Timestamp(y, m, 1); e0 = s0 + pd.DateOffset(years=2) - pd.Timedelta(days=1)
            df = O.run_options(data, sg, RATIO, s0, e0) if mode == "OPT" else E.run_futures(data, sg, mode, start=s0, end=e0)
            eq = 1e5 + df.pnl.cumsum(); fin.append(eq.iloc[-1]); dd.append((eq / eq.cummax() - 1).min())
    fin = np.array(fin)
    rows.append(dict(key=key, starts=len(fin), ruined_pct=100 * (fin < 26000).mean(), lost_pct=100 * (fin < 1e5).mean(),
                     median_final=np.median(fin), p10_final=np.percentile(fin, 10), p90_final=np.percentile(fin, 90), median_maxdd_pct=100 * np.median(dd)))
R = pd.DataFrame(rows); R.to_csv("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/m2/startdates.csv", index=False)
print(R.round(0).to_string())
