"""h47 LOCKED HOLDOUT (2026-04-01 .. end of data). Runs ONCE: the primary (setup A, 3-min, 00:00 UTC, taker
futures, both coins) and the survivors listed in an.json. Refuses a second run."""
from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import IS_END, SCR, load_1m, np, pd  # noqa: E402
import detail as D  # noqa: E402
from analyze import PRIMARY  # noqa: E402

FLAG = os.path.join(SCR, "HOLDOUT_DONE")


def parse(vid):
    coin, setup, an, N, rest = vid.split("|")
    import re
    m = re.match(r"s(\d)v(\d)h(\d+)t(\d)k(\d)w(\d)c([\d.]+)", rest)
    s, v, h, t, k, w, c = m.groups()
    return coin, dict(setup=setup, anchor=an, N=int(N), slope=int(s), vol=int(v), hold=int(h), hours=int(t), skip=int(k),
                      win=int(w), k=float(c))


def main():
    if os.path.exists(FLAG):
        sys.exit("holdout already run: " + open(FLAG).read())
    an = json.load(open(os.path.join(SCR, "an.json")))
    th = json.load(open(os.path.join(SCR, "thresholds.json")))
    end = int(load_1m("BTC").t.max()) // 1440 * 1440 + 1440           # end of the last data day
    open(FLAG, "w").write(pd.Timestamp.now().isoformat())
    runs = [("BTC", PRIMARY, "FT", "primary"), ("ETH", PRIMARY, "FT", "primary")]
    for vid, inst in an.get("survivor_rows", []):
        coin, v = parse(vid)
        runs.append((coin, v, inst, "survivor"))
    res, dsum = {}, None
    for coin, v, inst, tag in runs:
        s, daily, t = D.detail(coin, v, th[f"{coin}|{v['N']}"], inst, IS_END, end)
        s.update(D.day_profile(daily))
        mo = t.groupby(pd.to_datetime(t.day, unit="D").dt.to_period("M")).agg(n=("net", "size"), gross=("g", "sum"), net=("net", "sum"))
        s["months"] = {str(k): v for k, v in mo.to_dict("index").items()}
        res[f"{tag}|{coin}|{inst}|{v}"] = s
        if tag == "primary":
            dsum = daily if dsum is None else dsum + daily
            t.to_parquet(os.path.join(SCR, f"prim_hold_{coin}.parquet"))
        print(tag, coin, inst, json.dumps(s, default=float, indent=1), flush=True)
    res["BOTH|primary|days"] = D.day_profile(dsum)
    res["period"] = [str(pd.to_datetime(IS_END * 60, unit="s")), str(pd.to_datetime(end * 60, unit="s"))]
    json.dump(res, open(os.path.join(SCR, "holdout.json"), "w"), indent=1, default=float)
    print(json.dumps(res["BOTH|primary|days"], default=float))


if __name__ == "__main__":
    main()
