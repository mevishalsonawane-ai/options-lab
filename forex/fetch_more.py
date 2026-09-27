"""More gold inputs into forex/data_more/: daily gold miners (GDX), copper, crude oil, USD/JPY, EUR/USD, USD/CNY,
13-week T-bill, 5- and 30-year Treasury yields, and GLD / IAU / SLV (price and volume, a proxy for ETF flows),
plus the FOMC and jobs-report day list (shared with crypto/fetch_more.py).

Only public endpoints, no keys. Run from the repo root: python forex/fetch_more.py
"""
import os
import sys

import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "crypto"))
from fetch_more import DAYS, fomc_days, jobs_days  # noqa: E402
from marketlab.yahoo import chart  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "data_more")


def main():
    os.makedirs(OUT, exist_ok=True)
    for sym, name in (("GDX", "gdx"), ("HG=F", "copper"), ("CL=F", "oil"), ("JPY=X", "usdjpy"), ("EURUSD=X", "eurusd"),
                      ("CNY=X", "usdcny"), ("^IRX", "t_bill_13w"), ("^FVX", "treasury_5y"), ("^TYX", "treasury_30y"),
                      ("GLD", "gld"), ("IAU", "iau"), ("SLV", "slv")):
        df = chart(sym, DAYS)
        if df is None or len(df) == 0:
            print(f"  {name}: no data")
            continue
        df.to_csv(os.path.join(OUT, f"yahoo_{name}.csv"))
        print(f"  yahoo_{name}.csv: {len(df)} rows")
    fomc = fomc_days()
    ev = pd.DataFrame([(d, "fomc") for d in fomc] + [(d, "jobs") for d in jobs_days()], columns=["date", "event"])
    ev.to_csv(os.path.join(OUT, "events.csv"), index=False)
    print(f"  events: {len(fomc)} FOMC days")
    print("done")


if __name__ == "__main__":
    main()
