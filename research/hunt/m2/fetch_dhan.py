"""M2: Dhan continuous near-month DAILY futures (expiryCode 0) for MCX commodities, 2012-today, with OI.
Output: scratchpad/hunt/m2/data/dhan_daily.parquet (long format). Token never printed (redact)."""
import sys, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
import pandas as pd
from dhan import Dhan, candles, redact
OUT = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/m2/data"
IDS = {"CRUDEOIL": "569900", "CRUDEOILM": "569901", "NATURALGAS": "570750", "NATGASMINI": "570751",
       "GOLD": "495213", "GOLDM": "571445", "GOLDPETAL": "571306", "GOLDGUINEA": "571305", "GOLDTEN": "571307",
       "SILVER": "495214", "SILVERM": "483080", "SILVERMIC": "562058", "SILVER100": "574825",
       "COPPER": "574829", "ZINC": "574834", "ZINCMINI": "574835", "ALUMINIUM": "574828", "ALUMINI": "574827",
       "LEAD": "574830", "LEADMINI": "574831", "NICKEL": "574832"}
d = Dhan()
rows = []
for sym, sid in IDS.items():
    parts = []
    for y0 in range(2010, 2027, 4):
        f, t = dt.date(y0, 1, 1), min(dt.date(y0 + 3, 12, 31), dt.date.today()) + dt.timedelta(days=1)
        st, js, err = d.post("/charts/historical", dict(securityId=sid, exchangeSegment="MCX_COMM", instrument="FUTCOM",
                             expiryCode=0, oi=True, fromDate=f.isoformat(), toDate=t.isoformat()), gap=0.4)
        df = candles(js)
        if err: print(sym, f, redact(err), flush=True)
        if len(df): parts.append(df)
    if parts:
        df = pd.concat(parts); df = df[~df.index.duplicated()].sort_index()
        df["sym"] = sym
        rows.append(df.reset_index())
        print(sym, len(df), df.index[0].date(), df.index[-1].date(), flush=True)
    else:
        print(sym, "NO DATA", flush=True)
pd.concat(rows).to_parquet(f"{OUT}/dhan_daily.parquet", compression="zstd")
