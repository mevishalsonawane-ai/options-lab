"""Hourly feature table per asset (no P&L): realised-move targets, HAR inputs, DVOL, funding, event/IST flags,
and the IV surface lookup from Deribit hourly trade summaries.
Usage: python3 -I build.py SCRATCH   (SCRATCH = scratchpad/hunt/strad_crypto)"""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")  # pandas deps under python -I
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crypto")
import glob
import numpy as np, pandas as pd
from events import events

SCR = sys.argv[1] if len(sys.argv) > 1 else "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/strad_crypto"
EVCSV = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/src/crypto/data_more/events.csv"
HS = (1, 4, 24)
YR = 365 * 24.0


def spot5(cur):
    p = pd.read_parquet(f"{SCR}/data/{cur}_perp5m.parquet")
    p = p.set_index("t").sort_index()
    # 5-min bar t covers [t, t+5m); its close is known at t+5m. Re-index on close time.
    p.index = p.index + pd.Timedelta(minutes=5)
    full = pd.date_range(p.index[0], p.index[-1], freq="5min")
    c = p["c"].reindex(full).ffill()
    return c


def iv_table(cur):
    fs = sorted(glob.glob(f"{SCR}/trades/{cur}_*.parquet"))
    d = pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True)
    d = d.dropna(subset=["ex"]) if "ex" in d else d
    d = d[d.n_atm > 0].copy()
    d["iv_med"] = d.iv_med.clip(10, 400)
    d["Th"] = (d.ex - d.H).dt.total_seconds() / 3600
    return d[["H", "ex", "Th", "n_atm", "iv_med", "hs_med", "n_hs"]]


def build(cur):
    c = spot5(cur)
    lr = np.log(c).diff().fillna(0.0)
    sq = lr ** 2
    cs = sq.cumsum()
    hours = pd.date_range(c.index[0].ceil("h") + pd.Timedelta(days=8), c.index[-1].floor("h"), freq="h")
    pos = c.index.get_indexer(hours)
    csv = cs.values
    f = pd.DataFrame(index=hours)
    f["S"] = c.values[pos]
    n = len(csv)
    for lag, nm in [(12, "rv1p"), (48, "rv4p"), (288, "rv24p"), (2016, "rv168p")]:
        f[nm] = np.sqrt(np.maximum(csv[pos] - csv[pos - lag], 0))
    for h in HS:
        k = 12 * h
        ok = pos + k < n
        v = np.full(len(pos), np.nan)
        v[ok] = np.sqrt(np.maximum(csv[pos[ok] + k] - csv[pos[ok]], 0))
        f[f"rv{h}f"] = v
        m = np.full(len(pos), np.nan)
        m[ok] = np.abs(np.log(c.values[pos[ok] + k] / c.values[pos[ok]]))
        f[f"mv{h}f"] = m
    # DVOL: hourly candle opened at t closes at t+1h -> known at t+1h
    dv = pd.read_parquet(f"{SCR}/data/{cur}_dvol1h.parquet").set_index("t")["c"]
    dv.index = dv.index + pd.Timedelta(hours=1)
    f["dvol"] = dv.reindex(hours, method="ffill").values
    fu = pd.read_parquet(f"{SCR}/data/{cur}_funding1h.parquet").set_index("t")["interest_8h"]
    f["fund8"] = fu.reindex(hours, method="ffill").values
    af = f["fund8"].abs()
    f["fund_p95"] = af.rolling(90 * 24, min_periods=30 * 24).quantile(0.95).shift(1)
    f["fundz"] = (af - af.rolling(90 * 24, min_periods=30 * 24).mean().shift(1)) / af.rolling(90 * 24, min_periods=30 * 24).std().shift(1)
    f["hod"] = f.index.hour
    f["dow"] = f.index.dayofweek
    # events in (t, t+h]
    ev = events(EVCSV)
    et = ev.t.values.astype("datetime64[ns]")
    hv = f.index.values.astype("datetime64[ns]")
    for h in HS:
        a = np.searchsorted(et, hv, side="right")
        b = np.searchsorted(et, hv + np.timedelta64(h, "h"), side="right")
        f[f"ev{h}"] = (b > a).astype(int)
        # IST 19:00-21:00 = 13:30-15:30 UTC overlap with (t, t+h]
        day = f.index.floor("D")
        s1 = day + pd.Timedelta(hours=13, minutes=30)
        s2 = day + pd.Timedelta(hours=15, minutes=30)
        ov = np.zeros(len(f), bool)
        for dd in (0, 1):
            a1 = s1 + pd.Timedelta(days=dd); a2 = s2 + pd.Timedelta(days=dd)
            ov |= (a1 < f.index + pd.Timedelta(hours=h)) & (a2 > f.index)
        f[f"ist{h}"] = ov.astype(int)
    # next Delta expiry (12:00 UTC) with >= h+2 hours left
    for h in HS:
        e = f.index.floor("D") + pd.Timedelta(hours=12)
        e = e.where(e - f.index >= pd.Timedelta(hours=h + 2), e + pd.Timedelta(days=1))
        e = e.where(e - f.index >= pd.Timedelta(hours=h + 2), e + pd.Timedelta(days=1))
        f[f"exp{h}"] = e
    f.index.name = "H"
    return f


def check_events(cur="BTC"):
    c = spot5(cur)
    ar = np.log(c).diff().abs()
    med = ar.rolling(12 * 24 * 7).median()
    ev = events(EVCSV)
    out = []
    for _, r in ev.iterrows():
        t = r.t + pd.Timedelta(minutes=5)
        if t in ar.index:
            out.append((r.kind, r.date, ar[t] / med[t]))
    o = pd.DataFrame(out, columns=["kind", "date", "x"])
    return o


if __name__ == "__main__":
    for cur in sys.argv[2:] or ["BTC", "ETH"]:
        f = build(cur)
        f.to_parquet(f"{SCR}/data/{cur}_feat.parquet")
        print(cur, f.shape, f.index[0], f.index[-1], flush=True)
    o = check_events()
    o.to_csv(f"{SCR}/logs/event_check.csv", index=False)
    print(o.groupby("kind").x.describe())
    print("weak releases (5-min move < 2x weekly median):")
    print(o[o.x < 2].to_string())
