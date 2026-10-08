"""Daily range / move per lot for every MCX future, from Dhan continuous near-month daily.
Roll days (series switches contract) are detected as OI jumping >1.8x; on those days the return is open->close."""
import pandas as pd, numpy as np, glob, os
D = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/m1/data"
# quote-unit -> units per lot (Rs P&L per 1 Re move in the quote = MULT)
MULT = dict(CRUDEOIL=100, CRUDEOILM=10, NATURALGAS=1250, NATGASMINI=250, GOLD=100, GOLDM=10, GOLDTEN=1, GOLDGUINEA=1,
            GOLDPETAL=1, SILVER=30, SILVERM=5, SILVERMIC=1, SILVER100=10, COPPER=2500, ZINC=5000, ZINCMINI=1000,
            ALUMINIUM=5000, ALUMINI=1000, LEAD=5000, LEADMINI=1000, NICKEL=250, MCXBULLDEX=30, MCXMETLDEX=40,
            MENTHAOIL=360, COTTON=25, COTTONOIL=500, KAPAS=200, CARDAMOM=100, STEELREBAR=5, ELECDMBL=50)
def load(s):
    df = pd.read_parquet(f"{D}/daily_{s}.parquet").sort_index()
    df = df[(df.close > 0) & (df.high >= df.low)]
    roll = (df.open_interest / df.open_interest.shift()) > 1.8
    r = np.log(df.close / df.close.shift())
    r[roll] = np.log(df.close / df.open)[roll]
    df["ret"] = r; df["roll"] = roll
    df["rng"] = (df.high - df.low) / df.close
    return df
def main():
    rows = []
    for f in sorted(glob.glob(f"{D}/daily_*.parquet")):
      s = os.path.basename(f)[6:-8]
      if s not in MULT: continue
      df = load(s)
      y = df[df.index >= "2025-10-08"]; y5 = df[df.index >= "2021-10-08"]
      if len(y) < 20: continue
      px = df.close.iloc[-1]; m = MULT[s]
      rows.append(dict(sym=s, last=px, days_1y=len(y), med_vol_lots_60d=int(df.volume.tail(60).median()),
          med_oi_60d=int(df.open_interest.tail(60).median()),
          rng_pct_1y=100 * y.rng.median(), absmove_pct_1y=100 * y.ret.abs().mean(), ann_vol_1y=100 * y.ret.std() * np.sqrt(250),
          ann_vol_5y=100 * y5.ret.std() * np.sqrt(250),
          rng_rs_lot_1y=y.rng.median() * px * m, p90_rng_rs_lot=y.rng.quantile(.9) * px * m,
          gap_abs_pct_1y=100 * (np.log(y.open / y.close.shift())[~y.roll]).abs().mean()))
    t = pd.DataFrame(rows).set_index("sym")
    pd.set_option("display.width", 250); pd.set_option("display.max_columns", 30)
    print(t.round(2).to_string())
    t.to_csv(f"{D}/../daily_stats.csv")

if __name__ == '__main__':
    main()
