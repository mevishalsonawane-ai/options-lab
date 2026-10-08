"""x3: the lot used per day (jx/ix_<U>.pkl, read from OI moves) vs the exchange schedule in obuy/config.py; changes
and disagreements. Also trading-day coverage per year and expiry-day counts."""
import sys, pickle, os
sys.path.append("/root/.local/lib/python3.11/site-packages")
sys.path.insert(0, "/home/user/options-lab/research")
import pandas as pd
from obuy import config as C
from obuy.data import lot_schedule
for u in ("NIFTY", "BANKNIFTY", "FINNIFTY"):
    d = pickle.load(open(os.path.join(C.JX, f"ix_{u}.pkl"), "rb"))
    df = pd.DataFrame([(k, v["lot"], v["exp"], len(v["m"])) for k, v in sorted(d.items())], columns=["day", "lot", "exp", "nmin"])
    df["sched"] = [lot_schedule(u, x) for x in df.day]
    ch = df[df.lot != df.lot.shift()]
    print(f"== {u}: {len(df)} days {df.day.iloc[0]}..{df.day.iloc[-1]}; lot changes:")
    print("  ", [(str(r.day), r.lot) for r in ch.itertuples()])
    bad = df[df.lot != df.sched]
    print(f"   days where data lot != schedule: {len(bad)}", bad.groupby(["lot", "sched"]).day.agg(["min", "max", "count"]).to_string().replace("\n", "\n   "))
    df["y"] = [x.year for x in df.day]
    print("   per year: days / expiry days / days with <370 index minutes:",
          df.groupby("y").agg(n=("day", "size"), exp=("exp", "sum"), short=("nmin", lambda s: int((s < 370).sum()))).T.to_dict())
