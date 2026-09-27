"""BTC: one neural network over every timeframe (1m ... 24h). Writes crypto/results/mtf.json and
crypto/results/alarms_mtf_hourly.csv. Run after fetch_mtf.py and strategy.py."""
import os
import sys

import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.io import read, write_json  # noqa: E402
from marketlab.extra_inputs import btc as extra_inputs  # noqa: E402
from marketlab.mtf_study import run  # noqa: E402

HERE = os.path.dirname(__file__)


def main():
    m1 = pd.read_csv(os.path.join(HERE, "data_mtf", "btc_1m.csv.gz"), index_col=0)
    m1.index = pd.to_datetime(m1.index, utc=True, format="ISO8601")
    al = read(os.path.join(HERE, "results", "alarms_hourly.csv"))
    h = read(os.path.join(HERE, "data", "btc_1h.csv"))
    m1 = m1[m1.index < h.index[-1] + pd.Timedelta(hours=1)]  # same end as the rest of the study
    win_start = h.index[-1].normalize() - pd.Timedelta(days=91)
    dev_start = al.index[0].normalize() + pd.Timedelta(days=1)
    print(f"1m bars: {len(m1)}; development year from {dev_start.date()}, window from {win_start.date()}")
    extra = extra_inputs(os.path.join(HERE, ".."))
    print("extra inputs:", {k: v.shape for k, v in extra.items()})
    res, alarms = run(m1, dev_start, win_start, extra=extra, cost=0.0006)
    res.update({"market": "BTC/USDT", "dev": [str(dev_start), str(win_start)], "window": [str(win_start), str(h.index[-1])]})
    write_json(res, os.path.join(HERE, "results", "mtf.json"))
    alarms.index = alarms.index - pd.Timedelta(hours=1)  # key by bar OPEN time, like alarms_hourly.csv
    alarms.to_csv(os.path.join(HERE, "results", "alarms_mtf_hourly.csv"))


if __name__ == "__main__":
    main()
