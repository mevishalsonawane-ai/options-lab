"""R3: build minute series (IST) for the futures-level study -> scratchpad/hunt/r3/work/min_<NAME>.parquet
 GOLDM_C / GOLD_C : Dhan rollingoption 'spot' = near-month futures, continuous Aug 2025 - 9 Oct 2026, roll-adjusted
                    (back-adjusted by the open-vs-previous-close jump on the first session after each option expiry).
                    Close only (bar high/low from minute closes).
 GOLDM_NOV, GOLDPETAL_OCT : single live contracts with real OHLC (Apr 2026 ->).
 XAU : XAUUSD 1-min: histdata.com 2015 -> Sep 2023, then Dukascopy bid Oct 2023 -> Sep 2026 (proxy for MCX gold, no USDINR)."""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/r3")
from r3lib import *
W = R3 / "work"; W.mkdir(exist_ok=True)
RAW3 = SP / "m3" / "raw"


def save(name, ts, o, h, l, c):
    m = minute_frame(ts, o, h, l, c)
    m.to_parquet(W / f"min_{name}.parquet")
    print(name, len(m), m.day.min(), m.day.max(), m.day.nunique(), "days", flush=True)


def ref_gaps():
    """Overnight gap (first 09:00-session price / previous session last price - 1) of a reference series that has no
    roll: XAUUSD (to 25 Sep 2026), then the single live GOLDM NOV contract."""
    out = {}
    X = pd.read_csv(SP.parent / "xauusd_m1_bid.csv.gz")
    X["ts"] = pd.to_datetime(X.timestamp, unit="ms").dt.tz_localize("UTC").dt.tz_convert("Asia/Kolkata")
    f = pd.read_parquet(RAW3 / "fut_live.parquet"); f = f[f.contract == "GOLDM NOV"]
    for ts, c in ((X.ts, X.close.values), (pd.DatetimeIndex(f.ts), f.close.values)):
        m = minute_frame(ts, c, c, c, c)
        a = m.groupby("day").c.first(); b = m.groupby("day").c.last()
        g = (a / b.shift(1) - 1).dropna()
        for d, v in g.items():
            out.setdefault(d, v)
    return out


REF = None


def cont(sym, extra=None):
    global REF
    REF = REF or ref_gaps()
    sp = pd.read_parquet(RAW3 / f"spot_{sym}.parquet")[["ts", "spot"]]
    if extra is not None:
        sp = pd.concat([sp, extra[extra.ts > sp.ts.max()]])
    sp = sp.drop_duplicates("ts").sort_values("ts")
    sp = sp[sp.spot > 0].reset_index(drop=True)
    sp["day"] = sp.ts.dt.tz_localize(None).dt.normalize().dt.date
    sp = sp[[d.weekday() < 5 for d in sp.day]].reset_index(drop=True)
    days = sorted(sp.day.unique())
    roll = [min([x for x in days if x > e], default=None) for e in GOLDM_EXP] if sym == "GOLDM" else None
    p = sp.spot.values.astype(float).copy()
    first = sp.groupby("day").head(1).index.values
    adj = np.ones(len(p))
    for i in first[1:]:
        g = p[i] / p[i - 1] - 1
        rg = REF.get(sp.day[i], np.nan)
        jump = g - (0 if np.isnan(rg) else rg)
        if (roll and sp.day[i] in roll) or (not roll and abs(jump) > 0.004):
            adj[:i] *= (1 + g) / (1 + (0 if np.isnan(rg) else rg))
            print(sym, "roll adj", sp.day[i], "gap", round(g * 100, 2), "ref", round(rg * 100, 2), "%")
    p = p * adj
    return sp.ts.values, p


tail = pd.read_parquet(R3 / "raw" / "goldm_opt_tail.parquet")
tail = tail[(tail.k == 0) & (tail.cp == 0)][["ts", "spot"]]
ts, p = cont("GOLDM", tail)
save("GOLDM_C", pd.DatetimeIndex(ts).tz_localize("UTC").tz_convert("Asia/Kolkata") if pd.DatetimeIndex(ts).tz is None else ts, p, p, p, p)
mm = pd.read_parquet(W / "min_GOLDM_C.parquet")
REF = (mm.groupby("day").c.first() / mm.groupby("day").c.last().shift(1) - 1).dropna().to_dict()
ts, p = cont("GOLD")
save("GOLD_C", pd.DatetimeIndex(ts).tz_localize("UTC").tz_convert("Asia/Kolkata") if pd.DatetimeIndex(ts).tz is None else ts, p, p, p, p)
f = pd.read_parquet(RAW3 / "fut_live.parquet")
g = f[f.contract == "GOLDM NOV"].sort_values("ts")
save("GOLDM_NOV", g.ts.values if g.ts.dt.tz is None else pd.DatetimeIndex(g.ts), g.open.values, g.high.values, g.low.values, g.close.values)
pt = pd.read_parquet(R3 / "raw" / "petal_live.parquet")
g = pt[pt.contract == "GOLDPETAL OCT"].sort_values("ts")
save("GOLDPETAL_OCT", pd.DatetimeIndex(g.ts), g.open.values, g.high.values, g.low.values, g.close.values)
# XAU
parts = [pd.read_parquet(x) for x in sorted((R3 / "raw").glob("xauH_20*.parquet"))]  # histdata.com 2015-2023 (UTC)
x0 = pd.concat(parts) if parts else pd.DataFrame(columns=["ts", "open", "high", "low", "close"])
x1 = pd.read_csv(SP.parent / "xauusd_m1_bid.csv.gz")
x1["ts"] = pd.to_datetime(x1.timestamp, unit="ms")
x0["ts"] = pd.to_datetime(x0.ts)
X = pd.concat([x0[x0.ts < x1.ts.min()], x1[["ts", "open", "high", "low", "close"]]]).sort_values("ts").drop_duplicates("ts")
ts = pd.DatetimeIndex(X.ts).tz_localize("UTC").tz_convert("Asia/Kolkata")
save("XAU", ts, X.open.values, X.high.values, X.low.values, X.close.values)
