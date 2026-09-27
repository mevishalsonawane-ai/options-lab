"""When and where BTC's big rises and drops happen, plus a neural network that flags them 24 hours
ahead. Writes crypto/results/patterns.json. Run from the repo root after fetch.py."""
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.io import daily_index, read, write_json  # noqa: E402
from marketlab.patterns import full_patterns, run_detector  # noqa: E402

HERE = os.path.dirname(__file__)
DATA, RES = os.path.join(HERE, "data"), os.path.join(HERE, "results")
QUICK = "--quick" in sys.argv


def main():
    t0 = time.time()
    d, h = read(f"{DATA}/btc_1d.csv"), read(f"{DATA}/btc_1h.csv")
    dv, od = daily_index(read(f"{DATA}/dvol_1d.csv")), daily_index(read(f"{DATA}/options_daily.csv"))
    extra = {}
    if dv is not None:
        extra["DVOL (implied vol)"] = dv["close"].reindex(d.index)
        extra["DVOL 5-day change"] = dv["close"].diff(5).reindex(d.index)
    if od is not None:
        o = od.reindex(d.index)
        extra["Put skew 90/110"] = o["skew_90_110"].rolling(5, min_periods=2).mean()
        extra["Put/call volume"] = o["pc_ratio"].rolling(5, min_periods=2).mean()
    cfg = {"thr_h": 3.0, "thr_d": 2.0, "round_step": 5000, "expiry_friday": True}
    res = full_patterns(d, h, 8760, cfg, extra)
    print(f"patterns {time.time() - t0:.0f}s: {res['hourly_counts']} {res['daily_counts']}")
    det_extra = {k: v for k, v in extra.items() if k.startswith("DVOL")}
    res["detector"] = run_detector(h, 8760, step=2000 if QUICK else 720, n_models=2 if QUICK else 3,
                                   extra_daily={"dvol": det_extra["DVOL (implied vol)"]} if det_extra else None)
    print(f"detector {time.time() - t0:.0f}s up auc={res['detector']['up']['auc']} dn auc={res['detector']['dn']['auc']}")
    write_json(res, f"{RES}/patterns.json")


if __name__ == "__main__":
    main()
