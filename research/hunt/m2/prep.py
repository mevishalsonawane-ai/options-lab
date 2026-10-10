"""M2 prep: per-commodity daily frames with roll flags + carry estimate. Output scratchpad/hunt/m2/data/panel.parquet"""
import pandas as pd, numpy as np
D = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/m2/data"
# tradable name -> (Dhan series, price divisor, lot point value Rs per price unit, lots, tick Rs/lot, spread Rs/lot round trip, margin floor)
SPEC = {
    "CRUDE":  dict(src="CRUDEOIL",  div=1,  pv=10,   lots=1, rt_spread=20,  floor=0.08, contract="CRUDEOILM"),
    "NATGAS": dict(src="NATURALGAS", div=1, pv=250,  lots=1, rt_spread=50,  floor=0.10, contract="NATGASMINI"),
    "GOLD":   dict(src="GOLD",      div=10, pv=1,    lots=5, rt_spread=3,   floor=0.06, contract="GOLDPETAL"),
    "SILVER": dict(src="SILVER",    div=1,  pv=1,    lots=1, rt_spread=15,  floor=0.06, contract="SILVERMIC"),
    "ZINC":   dict(src="ZINC",      div=1,  pv=1000, lots=1, rt_spread=100, floor=0.07, contract="ZINCMINI"),
    "LEAD":   dict(src="LEAD",      div=1,  pv=1000, lots=1, rt_spread=100, floor=0.07, contract="LEADMINI"),
    "ALUM":   dict(src="ALUMINIUM", div=1,  pv=1000, lots=1, rt_spread=100, floor=0.07, contract="ALUMINI"),
}

def roll_days(oi):
    r = (oi / oi.shift(1)).fillna(1.0)
    cand = r.where(r > 1.5)
    out = pd.Series(False, index=oi.index)
    vals = cand.values; n = len(vals); i = 0
    idx = np.where(~np.isnan(vals))[0]
    taken = []
    for j in idx:
        if taken and j - taken[-1] < 15:
            if vals[j] > vals[taken[-1]]: taken[-1] = j
        else:
            taken.append(j)
    out.iloc[taken] = True
    return out

def build():
    d = pd.read_parquet(f"{D}/dhan_daily.parquet")
    d["date"] = d.ts.dt.normalize()
    frames = []
    for name, s in SPEC.items():
        g = d[d.sym == s["src"]].set_index("date").sort_index()[["open", "high", "low", "close", "volume", "open_interest"]].copy()
        g = g[~g.index.duplicated()]
        if name == "CRUDE" and pd.Timestamp("2020-04-20") in g.index:
            g.loc["2020-04-20", ["low", "close"]] = -2884.0
        for c in ("open", "high", "low", "close"): g[c] = g[c] / s["div"]
        g["roll"] = roll_days(g.open_interest)
        if name in ("ZINC", "LEAD", "ALUM"):   # base metals expire on the last business day of the month
            m = pd.Series(g.index.month, index=g.index)
            g["roll"] = (m != m.shift(1)).values
        if name == "CRUDE" and pd.Timestamp("2020-04-21") in g.index:
            g.loc["2020-04-21", "roll"] = True      # April 2020 contract expired 20 Apr at -2,884
        gp = (g.open - g.close.shift(1)) / g.close.shift(1).abs()
        z = gp / gp.rolling(60, min_periods=20).std()
        g["glitch"] = (z.abs() > 8) & (~g.roll)
        g.loc[g.glitch, "roll"] = True               # treat as contract switch / data error: overnight gap not earned
        # roll jump estimate: overnight gap on roll day minus median overnight gap of non-roll days (60d)
        gap = g.open - g.close.shift(1)
        gap_pct = gap / g.close.shift(1).abs()
        typ = gap_pct.where(~g.roll).rolling(60, min_periods=20).median()
        g["jump_pct"] = (gap_pct - typ).where(g.roll)
        # annualised carry: -(mean of last 6 jumps) * rolls per year
        j = g.jump_pct.dropna()
        days_between = pd.Series(j.index, index=j.index).diff().dt.days
        ann = (-j * 365.0 / days_between).rolling(6, min_periods=3).mean()
        g["carry"] = ann.reindex(g.index).ffill()
        g["name"] = name
        frames.append(g.reset_index())
        print(name, len(g), "rolls", int(g.roll.sum()), "rolls/yr", round(g.roll.sum() / (len(g) / 250), 1),
              "mean ann carry %", round(100 * g.carry.mean(), 2), "glitch days", [d.date().isoformat() for d in g.index[g.glitch]])
    p = pd.concat(frames)
    p.to_parquet(f"{D}/panel.parquet", compression="zstd")

if __name__ == "__main__":
    build()
