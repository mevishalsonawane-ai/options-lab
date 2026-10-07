"""h20 follow-up (07 Oct, after Boss approved fixes F1-F6): the replayed effect of the FIXED profit lock on each arm.

before = sim mode "app"   : what the app did until 07 Oct (lock / target sold at market after a sampled close crossed them)
after  = sim mode "fixed" : what the app does now (the -40 SL-M moved up to the lock earned by the minute HIGHS, filled at
                            its trigger or the gap open; the +target an app check on the minute high, sold next minute)
Same signals, entries, lots, app costs (net) and position rule as analyse.py; the app's own rules (B0_app_now), no
alternative. Information only: nothing is chosen from it (the locked holdout was already used once by analyse.py).

    OBUY_CACHE=<scratch>/hunt/h20/cache flock <scratch>/obuy.lock python3 -I research/hunt/h20/sim.py prep
    OBUY_CACHE=<scratch>/hunt/h20/cache flock <scratch>/obuy.lock python3 -I research/hunt/h20/fixed.py
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sim  # noqa: E402
import analyse as A  # noqa: E402

import pandas as pd  # noqa: E402


def main():
    P = sim.load()
    rows = []
    for part in ("pre", "ho"):
        days = A.days_of(P, part)
        for arm in sim.ARMS:
            base = A.specs_for(arm)[0]
            for mode in ("app", "fixed", "tick"):
                tr = A.run(P, arm, base, "app", mode, part=part)
                g = A.run(P, arm, base, "gross", mode, part=part)
                s = A.summ(tr, days)
                w = tr.why.value_counts(normalize=True).mul(100).round(1).to_dict() if len(tr) else {}
                rows.append(dict(part=part, arm=arm, mode=mode, sessions=len(days), **s, gross_headline=round(float(g.gross.sum())),
                                 lock_pct=w.get("lock", 0.0), stop_pct=w.get("stop", 0.0), target_pct=w.get("target", 0.0)))
                print(part, arm, mode, s.get("net"), s.get("rs_day"), flush=True)
    df = pd.DataFrame(rows)
    df.to_csv(os.path.join(HERE, "fixed.csv"), index=False)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 40)
    print(df[["part", "arm", "mode", "sessions", "n", "win", "gross_headline", "net", "per_trade", "rs_day", "maxdd",
              "lock_pct", "stop_pct", "target_pct"]].to_string())
    ho = df[df.part == "ho"].pivot(index="arm", columns="mode", values="rs_day")
    print(json.dumps(ho.round(1).to_dict(), indent=1))


if __name__ == "__main__":
    main()
