"""NN-CRUDE feature builder.
MCX table (decision every 5 min, 09:30-23:00 IST) -> data/feat_mcx.parquet
Proxy table (WTI CL=F 1h x USDINR, decision at each hourly close inside the MCX session) -> data/feat_proxy.parquet
Everything at time t uses only bars that closed at or before t (Yahoo 1h bars are used only after bar start + 1h).
Feature spec (names, groups) is written to models/feature_spec.json."""
import sys, json
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from common import *

HOR = (15, 30, 60)
SEQ = 30
C_FEATS = ["c_ret60", "c_ret120", "c_ret240", "c_ret480", "c_rngpos", "c_tod_sin", "c_tod_cos",
           "c_dow0", "c_dow1", "c_dow2", "c_dow3", "c_dow4", "c_eia_day", "c_to_eia", "c_since_eia",
           "c_cpi_day", "c_since_cpi", "c_fomc_night", "x_inr1h", "x_dxy1h", "x_es1h", "x_bzcl1h", "c_vol_regime"]


def hourly_cross():
    """Last COMPLETED Yahoo 1h bar returns, indexed by availability time (bar start + 1h) in IST."""
    out = {}
    for nm, key in (("INR_X", "x_inr1h"), ("DX_Y_NYB", "x_dxy1h"), ("ES_F", "x_es1h"), ("BZ_F", "bz"), ("CL_F", "cl")):
        y = yahoo("1h", nm)
        s = np.log(y.close).diff()
        s.index = (s.index + pd.Timedelta(hours=1)).tz_localize("UTC").tz_convert("Asia/Kolkata").tz_localize(None)
        out[key] = s
    x = pd.DataFrame(out).sort_index()
    x["x_bzcl1h"] = x.bz - x.cl
    return x[["x_inr1h", "x_dxy1h", "x_es1h", "x_bzcl1h"]]


def event_feats(ts):
    """ts: DatetimeIndex (IST). Returns DataFrame of calendar features."""
    ev = pd.read_parquet(f"{D}/events.parquet")
    f = pd.DataFrame(index=ts)
    day = ts.normalize()
    for kind, pre in (("eia", "eia"), ("uscpi", "cpi")):
        e = ev[ev.kind == kind].ts_ist
        eday = pd.Series(e.values, index=e.dt.normalize().values)
        eday = eday[~eday.index.duplicated()]
        t_ev = pd.Series(day).map(eday).values
        t_ev = pd.to_datetime(t_ev)
        has = ~pd.isna(t_ev)
        mins = (t_ev - ts).total_seconds() / 60
        f[f"c_{pre}_day"] = has.astype(float)
        if pre == "eia":
            f["c_to_eia"] = np.where(has & (mins > 0), np.clip(mins, 0, 720) / 720, 0.0)
        f[f"c_since_{pre}"] = np.where(has & (mins <= 0), 1 - np.clip(-mins, 0, 240) / 240, 0.0)
    fomc = ev[ev.kind == "fomc"].ts_ist
    fn = set((fomc - pd.Timedelta(hours=3)).dt.normalize())  # FOMC 23:30/00:30 IST -> same/previous IST date
    f["c_fomc_night"] = pd.Series(day).isin(fn).values.astype(float)
    tod = (ts.hour * 60 + ts.minute) / 1440
    f["c_tod_sin"], f["c_tod_cos"] = np.sin(2 * np.pi * tod), np.cos(2 * np.pi * tod)
    for k in range(5):
        f[f"c_dow{k}"] = (ts.dayofweek == k).astype(float)
    return f


def asof(cross, ts):
    return cross.reindex(cross.index.union(ts)).sort_index().ffill().reindex(ts).fillna(0.0)


def build_mcx():
    b = pd.read_parquet(f"{D}/mcx_min.parquet")
    dte = pd.read_parquet(f"{D}/mcx_dte.parquet").dte
    b["dte"] = dte
    rows = []
    for day, g in b.groupby("day"):
        g = g[~g.index.duplicated()]
        idx = pd.date_range(g.index[0], g.index[-1], freq="1min")
        g = g.reindex(idx).ffill()
        g["day"] = day
        rows.append(g)
    b = pd.concat(rows)
    lp = np.log(b.spot)
    gday = b.day
    r1 = lp.groupby(gday).diff()
    b["r1"] = r1
    # slow vol: std of 5-min returns over the previous 5 sessions (per minute units), from completed days only
    r5 = lp.groupby(gday).diff(5)
    dvol = (r5 ** 2).groupby(gday).mean().pipe(np.sqrt) / np.sqrt(5)       # per-minute vol of each day
    slow = dvol.rolling(5, min_periods=2).mean().shift(1)                   # previous 5 days only
    b["vol_slow"] = gday.map(slow).bfill()
    b["vol60"] = r1.groupby(gday).transform(lambda s: s.rolling(60, min_periods=20).std())
    b["vol15"] = r1.groupby(gday).transform(lambda s: s.rolling(15, min_periods=8).std())
    vs = b.vol_slow.clip(lower=1e-5)
    F = pd.DataFrame(index=b.index)
    v60 = b.vol60.fillna(vs).clip(lower=1e-5)
    for k in range(SEQ):
        F[f"s_{k:02d}"] = (r1.groupby(gday).shift(k) / v60).clip(-6, 6)
    for k in (5, 15, 30, 60, 120, 240, 480):
        rk = lp.groupby(gday).diff(k)
        F[f"m_ret{k}" if k < 60 else f"c_ret{k}"] = (rk / (vs * np.sqrt(k))).clip(-6, 6)
    op = lp.groupby(gday).transform("first")
    F["m_ret_open"] = ((lp - op) / (vs * np.sqrt(np.maximum((b.index - b.index.normalize()).total_seconds() / 60 - 540, 1)))).clip(-6, 6)
    dl = b.groupby("day").spot.agg(["first", "last"])
    gap = np.log(dl["first"] / dl["last"].shift())
    gap[b.groupby("day").roll_day.first()] = 0.0
    F["m_gap"] = (gday.map(gap).fillna(0) / (vs * np.sqrt(60))).clip(-6, 6)
    hi = b.spot.groupby(gday).cummax(); lo = b.spot.groupby(gday).cummin()
    F["c_rngpos"] = ((b.spot - lo) / (hi - lo).replace(0, np.nan)).fillna(0.5) - 0.5
    F["m_dist_hi"] = (np.log(hi / b.spot) / (vs * np.sqrt(60))).clip(0, 8)
    F["m_dist_lo"] = (np.log(b.spot / lo) / (vs * np.sqrt(60))).clip(0, 8)
    F["m_vol15_60"] = np.log((b.vol15 / v60).clip(0.1, 10)).fillna(0)
    F["m_vol60_slow"] = np.log((v60 / vs).clip(0.1, 10)).fillna(0)
    F["c_vol_regime"] = np.log((vs / vs.rolling(60 * 870, min_periods=870 * 5).median()).clip(0.2, 5)).fillna(0)
    F["m_mins_open"] = ((b.index - b.index.normalize()).total_seconds() / 60 - 540) / 900
    # options
    ivz = b.iv.ffill()
    F["o_iv_lvl"] = np.log(ivz / ivz.rolling(870 * 10, min_periods=870).median()).clip(-2, 2).fillna(0)
    F["o_iv_ch15"] = (ivz.groupby(gday).diff(15) / ivz).clip(-0.5, 0.5).fillna(0)
    F["o_iv_ch60"] = (ivz.groupby(gday).diff(60) / ivz).clip(-0.5, 0.5).fillna(0)
    F["o_strad"] = np.log(((b.ce + b.pe) / b.spot).clip(1e-4, 1)).fillna(-4)
    F["o_dte"] = (b.dte.clip(0, 40) / 40).fillna(0.5)
    vc, vp = b.optvol_c.fillna(0), b.optvol_p.fillna(0)
    v15 = (vc + vp).groupby(gday).transform(lambda s: s.rolling(15, min_periods=1).sum())
    F["o_vol15"] = np.log1p(v15) - np.log1p(v15.rolling(870 * 5, min_periods=870).mean())
    F["o_vol15"] = F.o_vol15.fillna(0).clip(-4, 4)
    c15 = vc.groupby(gday).transform(lambda s: s.rolling(15, min_periods=1).sum())
    p15 = vp.groupby(gday).transform(lambda s: s.rolling(15, min_periods=1).sum())
    F["o_cp_imb15"] = ((c15 - p15) / (c15 + p15).replace(0, np.nan)).fillna(0)
    oid = (b.oi_c - b.oi_p).groupby(gday).diff(60)
    F["o_oi_imb60"] = (oid / (b.oi_c + b.oi_p).replace(0, np.nan)).clip(-0.5, 0.5).fillna(0)
    # events + cross
    F = F.join(event_feats(F.index))
    F = F.join(asof(hourly_cross(), F.index))
    # labels
    for h in HOR:
        fwd = lp.groupby(gday).shift(-h) - lp
        F[f"y_ret{h}"] = fwd
    F["spot"] = b.spot; F["day"] = gday; F["ce"] = b.ce; F["pe"] = b.pe; F["atm_strike"] = b.atm_strike; F["dte"] = b.dte
    # decision grid
    tm = F.index.hour * 60 + F.index.minute
    keep = (F.index.minute % 5 == 0) & (tm >= 9 * 60 + 30) & (tm <= 23 * 60)
    F = F[keep & F.c_ret60.notna()]
    F = F.astype({c: "float32" for c in F.columns if c not in ("day",)})
    F.to_parquet(f"{D}/feat_mcx.parquet", compression="zstd")
    print("feat_mcx", F.shape, F.index.min(), F.index.max())
    return F


def build_proxy():
    cl, inr = yahoo("1h", "CL_F"), yahoo("1h", "INR_X")
    # close of bar starting at ts is known at ts+1h
    cl.index = (cl.index + pd.Timedelta(hours=1)).tz_localize("UTC").tz_convert("Asia/Kolkata").tz_localize(None)
    inr.index = (inr.index + pd.Timedelta(hours=1)).tz_localize("UTC").tz_convert("Asia/Kolkata").tz_localize(None)
    cl = cl[~cl.index.duplicated()]
    inr_c = inr.close[~inr.index.duplicated()]
    p = cl.close * inr_c.reindex(inr_c.index.union(cl.index)).sort_index().ffill().reindex(cl.index)
    p = p.dropna()
    p = p[p > 0]
    lp = np.log(p)
    # IST "MCX day" = calendar date of the timestamp (session 09:00-23:30)
    day = pd.Series(p.index.normalize(), index=p.index)
    r = lp.diff()
    # drop hourly returns across big time gaps (weekends/maintenance) from vol estimates
    gaps = pd.Series(p.index, index=p.index).diff() > pd.Timedelta(hours=2)
    r[gaps] = np.nan
    vs = (r.rolling(24 * 5, min_periods=24).std() / np.sqrt(60)).shift(1)  # per-minute equivalent, past only
    F = pd.DataFrame(index=p.index)
    for k, n in ((60, 1), (120, 2), (240, 4), (480, 8)):
        F[f"c_ret{k}"] = ((lp - lp.shift(n)) / (vs * np.sqrt(k))).clip(-6, 6)
    sess = (p.index.hour >= 9)
    hi = p.where(sess).groupby(day).cummax(); lo = p.where(sess).groupby(day).cummin()
    F["c_rngpos"] = ((p - lo) / (hi - lo).replace(0, np.nan)).fillna(0.5) - 0.5
    F["c_vol_regime"] = np.log((vs / vs.rolling(24 * 60, min_periods=24 * 5).median()).clip(0.2, 5)).fillna(0)
    F = F.join(event_feats(F.index)).join(asof(hourly_cross(), F.index))
    fwd = lp.shift(-1) - lp
    ok = (pd.Series(p.index, index=p.index).shift(-1) - pd.Series(p.index, index=p.index)) == pd.Timedelta(hours=1)
    F["y_ret60"] = fwd.where(ok)
    tm = F.index.hour * 60 + F.index.minute
    F = F[(tm >= 9 * 60 + 30) & (tm <= 23 * 60) & (F.index.dayofweek < 5)].dropna(subset=["c_ret480"])
    F["day"] = F.index.normalize()
    F = F.astype({c: "float32" for c in F.columns if c != "day"})
    F.to_parquet(f"{D}/feat_proxy.parquet", compression="zstd")
    print("feat_proxy", F.shape, F.index.min(), F.index.max())
    return F


if __name__ == "__main__":
    F = build_mcx()
    P = build_proxy()
    groups = {"seq": [c for c in F.columns if c.startswith("s_")],
              "mcx": [c for c in F.columns if c.startswith("m_")],
              "opt": [c for c in F.columns if c.startswith("o_")],
              "common": C_FEATS}
    spec = {"groups": groups, "horizons": HOR, "seq_len": SEQ, "decision_grid": "every 5 min 09:30-23:00 IST",
            "holdout_start": str(HOLDOUT_START.date()), "labels": "y_ret{h} = log(spot[t+h]/spot[t]) same session",
            "notes": "returns scaled by slow vol (prev 5 sessions); s_k = 1-min return k minutes ago / 60-min vol"}
    json.dump(spec, open(f"{MODELS}/feature_spec.json", "w"), indent=1)
    print({k: len(v) for k, v in groups.items()})
