"""When and where gold's big rises and drops happen, plus a neural network that flags them 24 hours
ahead. Writes forex/results/patterns.json. Run from the repo root after fetch.py."""
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
    d, h = read(f"{DATA}/xauusd_1d.csv"), read(f"{DATA}/xauusd_1h.csv")
    per_h = len(h) / max((h.index[-1] - h.index[0]).days / 365.25, 0.1)
    extra = {}
    for name, f, is_yield in (("Dollar index 5-day move", "dxy", False), ("US 10Y yield 5-day change", "us10y", True)):
        c = read(f"{DATA}/context_{f}.csv")
        if c is not None:
            cc = daily_index(c)["close"]
            extra[name] = (cc.diff(5) if is_yield else np.log(cc).diff(5)).reindex(d.index.normalize()).ffill(limit=3)
            extra[name].index = d.index
    cfg = {"thr_h": 3.0, "thr_d": 2.0, "round_step": 50, "first_friday": True}
    res = full_patterns(d, h, per_h, cfg, extra)
    print(f"patterns {time.time() - t0:.0f}s: {res['hourly_counts']} {res['daily_counts']}")
    res["detector"] = run_detector(h, per_h, step=2000 if QUICK else 600, n_models=2 if QUICK else 3)
    print(f"detector {time.time() - t0:.0f}s up auc={res['detector']['up']['auc']} dn auc={res['detector']['dn']['auc']}")
    write_json(res, f"{RES}/patterns.json")


if __name__ == "__main__":
    main()
