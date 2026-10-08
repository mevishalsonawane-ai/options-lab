"""h43 part D summary of the enhanced (in-sample best) variant per family: pre-holdout and the locked holdout.

    python3 -I research/hunt/h43/partD_report.py
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.data import market  # noqa: E402
from partD import HOLD, OUT, decode  # noqa: E402

W0, W1 = date(2026, 8, 31), date(2026, 10, 7)


def main():
    fin = json.load(open(os.path.join(OUT, "final.json")))
    t = pd.read_csv(os.path.join(OUT, "detail_trades.csv.gz"), parse_dates=["day"])
    t["day"] = t.day.dt.date
    mk = market()
    rows = []
    for f, d in fin["families"].items():
        v = d["best"]["vid"]
        dv = decode(v)
        ix = mk.index(dv["und"])
        tdays = [x for x in ix.days if not ix.d[x]["exp"]]
        x = t[t.vid == v]
        for per, lo, hi in (("pre", date(2020, 1, 1), date(2025, 9, 30)), ("hold", HOLD, date(2026, 12, 31))):
            days = [z for z in tdays if lo <= z <= hi]
            tr = x[(x.day >= lo) & (x.day <= hi)]
            s = pd.Series(0.0, index=pd.Index(days)).add(tr.groupby("day").net.sum(), fill_value=0)
            eq = s.cumsum().values
            dd = float((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min())
            mo = s.groupby([z.strftime("%Y-%m") for z in s.index]).sum()
            row = dict(family=f, period=per, und=dv["und"], strike=dv["strike"], params=str(dv["params"]),
                       filter=dv["filter"], exit=dv["exit"], maxday=dv["maxday"], first_day=str(days[0]),
                       days=len(days), trades=len(tr), gross_day=tr.gross.sum() / len(days),
                       net_day=tr.net.sum() / len(days), net=tr.net.sum(), win=(tr.net > 0).mean(), maxdd=dd,
                       worst_month=mo.min(), green_months=(mo > 0).mean(), months=len(mo), prem_med=tr.prem.median())
            if per == "hold":
                ex = tr[[not (W0 <= z <= W1) for z in tr.day]]
                n_ex = sum(1 for z in days if not (W0 <= z <= W1))
                row["net_day_exwin"] = ex.net.sum() / n_ex
            else:
                for y, g in tr.groupby([z.year for z in tr.day]):
                    row[f"y{y}"] = g.net.sum()
            row["lots5k"] = 5000 / row["net_day"] if row["net_day"] > 0 else np.nan
            row["lots1L"] = 100000 // row["prem_med"] if row["prem_med"] > 0 else np.nan
            rows.append(row)
    r = pd.DataFrame(rows)
    pd.set_option("display.width", 300)
    pd.set_option("display.max_columns", 40)
    print(r.round(1).to_string())
    r.to_csv(os.path.join(OUT, "partD_enhanced.csv"), index=False)


if __name__ == "__main__":
    main()
