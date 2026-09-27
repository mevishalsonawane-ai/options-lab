"""Study the BTC charts and options market, train the neural networks walk-forward, and write
crypto/results/results.json (read by crypto/index.html). Run from the repo root after fetch.py.

  python crypto/train.py            full run
  python crypto/train.py --quick    fewer models / bigger steps, for a smoke test
"""
import os
import sys
import time

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.evaluate import run_direction_study, run_vol_study  # noqa: E402
from marketlab.features import base_features, calendar_features, realized_vol, targets  # noqa: E402
from marketlab.io import daily_index, read, write_json  # noqa: E402
from marketlab.study import _f, full_study  # noqa: E402

HERE = os.path.dirname(__file__)
DATA, RES = os.path.join(HERE, "data"), os.path.join(HERE, "results")
QUICK = "--quick" in sys.argv
NM = 2 if QUICK else 5
COST = 0.0005  # 5 bp a side (exchange taker fee + slippage)


def max_pain(g):
    ks = np.sort(g.strike.unique())
    c = g[g.type == "C"].groupby("strike").open_interest.sum().reindex(ks, fill_value=0).values
    p = g[g.type == "P"].groupby("strike").open_interest.sum().reindex(ks, fill_value=0).values
    pain = [np.sum(c * np.maximum(s - ks, 0)) + np.sum(p * np.maximum(ks - s, 0)) for s in ks]
    return float(ks[int(np.argmin(pain))])


def chain_study(ch):
    if ch is None or not len(ch):
        return None
    ch = ch.copy()
    ch["expiry"] = pd.to_datetime(ch["expiry"], utc=True)
    snap = pd.to_datetime(ch["snapshot_time"].iloc[0], utc=True)
    spot = float(ch["underlying_price"].median())
    ch["dte"] = (ch["expiry"] - snap).dt.total_seconds() / 86400
    ch["mny"] = ch["strike"] / ch["underlying_price"]
    exps = []
    for e, g in ch.groupby("expiry"):
        atm = g.iloc[(g.mny - 1).abs().argsort()[:4]]
        exps.append({"expiry": str(e.date()), "dte": _f(g.dte.iloc[0], 2),
                     "call_oi": _f(g[g.type == "C"].open_interest.sum(), 1),
                     "put_oi": _f(g[g.type == "P"].open_interest.sum(), 1),
                     "atm_iv": _f(atm.mark_iv.mean(), 2), "max_pain": max_pain(g),
                     "volume_24h": _f(g.volume_24h.sum(), 1)})
    tot = ch.groupby(["strike", "type"]).open_interest.sum().unstack(fill_value=0)
    near = tot[(tot.index > spot * 0.6) & (tot.index < spot * 1.6)]
    oi_by_strike = {"strike": [float(k) for k in near.index],
                    "call": [_f(v, 1) for v in near.get("C", pd.Series(0, index=near.index))],
                    "put": [_f(v, 1) for v in near.get("P", pd.Series(0, index=near.index))]}
    # smile of the expiry closest to 30 days
    e30 = min(exps, key=lambda x: abs(x["dte"] - 30))["expiry"]
    g = ch[ch.expiry.dt.date.astype(str) == e30]
    otm = g[((g.type == "P") & (g.mny <= 1)) | ((g.type == "C") & (g.mny > 1))].sort_values("strike")
    otm = otm[(otm.mny > 0.6) & (otm.mny < 1.6)]
    smile = {"expiry": e30, "strike": [float(k) for k in otm.strike], "iv": [_f(v, 2) for v in otm.mark_iv]}
    calls, puts = ch[ch.type == "C"].open_interest.sum(), ch[ch.type == "P"].open_interest.sum()
    top = tot.sum(axis=1).sort_values(ascending=False).head(8)
    return {"snapshot_time": str(snap), "spot": _f(spot, 2), "expiries": exps, "oi_by_strike": oi_by_strike,
            "smile": smile, "put_call_oi": _f(puts / calls, 3) if calls else None,
            "total_oi_btc": _f(calls + puts, 1),
            "top_strikes": [{"strike": float(k), "oi": _f(v, 1)} for k, v in top.items()]}


def options_study(d, dv, od):
    out, ser = {}, {}
    r = np.log(d["close"]).diff()
    rv30 = realized_vol(r, 30, 365) * 100
    fwd_rv30 = rv30.shift(-30)  # what actually happened over the next 30 days
    if dv is not None and len(dv):
        iv = dv["close"].reindex(d.index)
        vrp = iv - rv30
        both = pd.concat([iv, fwd_rv30], axis=1).dropna()
        out["dvol"] = {"now": _f(iv.dropna().iloc[-1], 2), "mean": _f(iv.mean(), 2), "min": _f(iv.min(), 2),
                       "max": _f(iv.max(), 2), "vrp_mean": _f(vrp.mean(), 2),
                       "vrp_positive_share": _f((vrp.dropna() > 0).mean()),
                       "iv_over_future_rv_share": _f((both.iloc[:, 0] > both.iloc[:, 1]).mean()),
                       "iv_minus_future_rv_mean": _f((both.iloc[:, 0] - both.iloc[:, 1]).mean(), 2),
                       "corr_iv_future_rv": _f(both.iloc[:, 0].corr(both.iloc[:, 1]), 3)}
        ser["dvol"] = [_f(v, 2) for v in iv]
        ser["rv30"] = [_f(v, 2) for v in rv30]
    if od is not None and len(od):
        o = od.reindex(d.index)
        fwd7 = np.log(d["close"].shift(-7) / d["close"])
        for col in ("skew_90_110", "pc_ratio"):
            x = o[col].rolling(5, min_periods=3).mean()
            j = pd.concat([x, fwd7], axis=1).dropna()
            if len(j) > 100:
                q = pd.qcut(j.iloc[:, 0], 5, labels=False, duplicates="drop")
                out[col] = {"now": _f(x.dropna().iloc[-1], 3), "mean": _f(x.mean(), 3),
                            "corr_next7d": _f(j.iloc[:, 0].corr(j.iloc[:, 1]), 3),
                            "next7d_by_quintile": [_f(v) for v in j.iloc[:, 1].groupby(q).mean()]}
            ser[col] = [_f(v, 3) for v in x]
        for col in ("atm_iv", "atm_iv_short", "atm_iv_long"):
            ser[col] = [_f(v, 2) for v in o[col].rolling(3, min_periods=1).mean()]
        out["sample_trades"] = int(od["trades"].sum())
        out["days_with_options"] = int(od["atm_iv"].notna().sum())
    return out, ser


def main():
    t0 = time.time()
    d, h = read(f"{DATA}/btc_1d.csv"), read(f"{DATA}/btc_1h.csv")
    dv, od = daily_index(read(f"{DATA}/dvol_1d.csv")), daily_index(read(f"{DATA}/options_daily.csv"))
    ctx = {n: read(f"{DATA}/context_{f}.csv") for n, f in (("S&P 500", "spx"), ("Dollar index", "dxy"),
                                                             ("Gold", "gold"))}
    ch = pd.read_csv(f"{DATA}/chain_snapshot.csv") if os.path.exists(f"{DATA}/chain_snapshot.csv") else None
    print(f"BTC daily {len(d)}, hourly {len(h)}")

    res = {"market": "BTC/USDT", "generated": pd.Timestamp.now(tz="UTC").isoformat(), "quick": QUICK}
    res["study"] = full_study(d, h, 365, ctx)
    res["options"], res["options_series"] = options_study(d, dv, od)
    res["chain"] = chain_study(ch)
    print(f"study done {time.time() - t0:.0f}s")

    # features
    f = base_features(d, 365).join(calendar_features(d.index))
    tg = targets(d, 365)
    extra = pd.DataFrame(index=d.index)
    if dv is not None:
        iv = dv["close"].reindex(d.index)
        extra["dvol"] = iv
        extra["dvol_chg1"] = iv.diff()
        extra["dvol_chg5"] = iv.diff(5)
        extra["vrp"] = iv - f["rv20"] * 100
    if od is not None:
        o = od.reindex(d.index)
        extra["skew"] = o["skew_90_110"].rolling(5, min_periods=2).mean()
        extra["pc_ratio"] = np.log(o["pc_ratio"]).rolling(5, min_periods=2).mean()
        extra["term_slope"] = (o["atm_iv_long"] - o["atm_iv_short"]).rolling(5, min_periods=2).mean()
    for n, c in ctx.items():
        if c is not None:
            cr = np.log(daily_index(c)["close"]).diff().reindex(d.index)
            extra[f"{n}_ret"] = cr.ffill(limit=3)
    models = {}
    step_d = 90 if QUICK else 30
    models["daily_price"] = run_direction_study(f, tg, 365, COST, step_d, "Daily direction, price only", NM)
    print(f"daily price model {time.time() - t0:.0f}s acc={models['daily_price']['metrics']['accuracy']:.3f}")
    fx = f.join(extra)
    models["daily_full"] = run_direction_study(fx, tg, 365, COST, step_d,
                                               "Daily direction, price + options + markets", NM)
    print(f"daily full model {time.time() - t0:.0f}s acc={models['daily_full']['metrics']['accuracy']:.3f}")
    fh = base_features(h, 8760).join(calendar_features(h.index, hourly=True))
    th = targets(h, 8760)
    models["hourly_price"] = run_direction_study(fh, th, 8760, COST, 2000 if QUICK else 720,
                                                 "Hourly direction, price only", NM, batch=256)
    print(f"hourly model {time.time() - t0:.0f}s acc={models['hourly_price']['metrics']['accuracy']:.3f}")
    models["daily_vol"] = run_vol_study(fx, tg, 365, step_d, "Next-5-day volatility",
                                        implied=extra["dvol"] if "dvol" in extra else None, n_models=NM)
    print(f"vol model {time.time() - t0:.0f}s r2={models['daily_vol']['metrics']['r2']:.3f}")
    res["models"] = models
    res["runtime_s"] = round(time.time() - t0)
    write_json(res, f"{RES}/results.json")
    print(f"wrote {RES}/results.json in {res['runtime_s']}s")


if __name__ == "__main__":
    main()
