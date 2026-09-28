"""Study the XAU/USD charts, train the neural networks walk-forward, and write
forex/results/results.json (read by forex/index.html). Run from the repo root after fetch.py.

  python forex/train.py            full run
  python forex/train.py --quick    fewer models / bigger steps, for a smoke test
"""
import os
import sys
import time

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.evaluate import run_direction_study, run_vol_study  # noqa: E402
from marketlab.features import base_features, calendar_features, targets  # noqa: E402
from marketlab.io import daily_index, read, write_json  # noqa: E402
from marketlab.study import _f, full_study  # noqa: E402

HERE = os.path.dirname(__file__)
DATA, RES = os.path.join(HERE, "data"), os.path.join(HERE, "results")
QUICK = "--quick" in sys.argv
NM = 2 if QUICK else 5
COST = 0.00015  # 1.5 bp a side: a typical XAU/USD spread plus slippage


def sessions(h):
    """Average hourly return / range in the Asia, London and New York sessions (UTC hours)."""
    r = np.log(h["close"]).diff()
    rng = (h["high"] - h["low"]) / h["close"]
    out = []
    for name, hours in (("Asia 23-07 UTC", list(range(23, 24)) + list(range(0, 7))),
                        ("London 07-12 UTC", list(range(7, 12))), ("London/NY overlap 12-16 UTC", list(range(12, 16))),
                        ("New York 16-21 UTC", list(range(16, 21)))):
        m = h.index.hour.isin(hours)
        out.append({"session": name, "mean_ret": _f(r[m].mean(), 6), "avg_range": _f(rng[m].mean(), 5),
                    "share_of_daily_move": _f(r[m].abs().sum() / r.abs().sum(), 3)})
    return out


def main():
    t0 = time.time()
    d, h = read(f"{DATA}/xauusd_1d.csv"), read(f"{DATA}/xauusd_1h.csv")
    ctx = {n: read(f"{DATA}/context_{f}.csv") for n, f in (("Dollar index", "dxy"), ("US 10Y yield", "us10y"),
                                                             ("Silver", "silver"), ("S&P 500", "spx"),
                                                             ("Bitcoin", "btc"))}
    src = open(f"{DATA}/SOURCE.txt").read() if os.path.exists(f"{DATA}/SOURCE.txt") else ""
    per_h = len(h) / max((h.index[-1] - h.index[0]).days / 365.25, 0.1)
    print(f"XAUUSD daily {len(d)}, hourly {len(h)} ({per_h:.0f} bars/yr)")

    corr_ctx = dict(ctx)
    if corr_ctx.get("US 10Y yield") is not None:
        corr_ctx["US10Y"] = corr_ctx.pop("US 10Y yield")  # yields: correlate with the change, not log return
    res = {"market": "XAU/USD", "source": src, "generated": pd.Timestamp.now(tz="UTC").isoformat(), "quick": QUICK}
    res["study"] = full_study(d, h, 252, corr_ctx)
    res["sessions"] = sessions(h)
    print(f"study done {time.time() - t0:.0f}s")

    f = base_features(d, 252).join(calendar_features(d.index))
    tg = targets(d, 252)
    extra = pd.DataFrame(index=d.index)
    di = d.index.normalize()
    for n, c in ctx.items():
        if c is None:
            continue
        cc = daily_index(c)["close"]
        ch = cc.diff() if n == "US 10Y yield" else np.log(cc).diff()
        v = ch.reindex(di).ffill(limit=3).values
        extra[f"{n}_chg"] = v
        extra[f"{n}_chg5"] = (cc.diff(5) if n == "US 10Y yield" else np.log(cc).diff(5)).reindex(di).ffill(limit=3).values
    step_d = 90 if QUICK else 30
    models = {}
    models["daily_price"] = run_direction_study(f, tg, 252, COST, step_d, "Daily direction, price only", NM)
    print(f"daily price model {time.time() - t0:.0f}s acc={models['daily_price']['metrics']['accuracy']:.3f}")
    fx = f.join(extra)
    models["daily_full"] = run_direction_study(fx, tg, 252, COST, step_d,
                                               "Daily direction, price + dollar, yields, silver, stocks", NM)
    print(f"daily full model {time.time() - t0:.0f}s acc={models['daily_full']['metrics']['accuracy']:.3f}")
    fh = base_features(h, per_h).join(calendar_features(h.index, hourly=True))
    th = targets(h, per_h)
    models["hourly_price"] = run_direction_study(fh, th, per_h, COST, 2000 if QUICK else 600,
                                                 "Hourly direction, price only", NM, batch=256)
    print(f"hourly model {time.time() - t0:.0f}s acc={models['hourly_price']['metrics']['accuracy']:.3f}")
    models["daily_vol"] = run_vol_study(fx, tg, 252, step_d, "Next-5-day volatility", n_models=NM)
    print(f"vol model {time.time() - t0:.0f}s r2={models['daily_vol']['metrics']['r2']:.3f}")
    res["models"] = models
    res["runtime_s"] = round(time.time() - t0)
    write_json(res, f"{RES}/results.json")
    print(f"wrote {RES}/results.json in {res['runtime_s']}s")


if __name__ == "__main__":
    main()
