"""Snapshot Dhan market depth (top 5) for MCX crude futures + near ATM options. Appends to data/quotes.parquet."""
import sys, os, json, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from dhan import Dhan, redact, OUT, IST
import pandas as pd
d = Dhan()
ids = [569900, 573422, 576264, 569901, 573423]
st, js, err = d.post("/marketfeed/quote", {"MCX_COMM": ids}, gap=1.5)
now = dt.datetime.now(IST).strftime("%Y-%m-%d %H:%M")
if err:
    print("quote", err); sys.exit(1)
rows = []
for sid, q in ((js.get("data") or {}).get("MCX_COMM") or {}).items():
    dep = q.get("depth") or {}
    b, a = (dep.get("buy") or [{}])[0], (dep.get("sell") or [{}])[0]
    rows.append(dict(snap=now, sid=int(sid), ltp=q.get("last_price"), bid=b.get("price"), ask=a.get("price"),
                     bidq=b.get("quantity"), askq=a.get("quantity"), vol=q.get("volume"), oi=q.get("oi"),
                     depth_bidq5=sum(x.get("quantity", 0) for x in dep.get("buy") or []),
                     depth_askq5=sum(x.get("quantity", 0) for x in dep.get("sell") or [])))
df = pd.DataFrame(rows); print(df.to_string())
fn = f"{OUT}/data/quotes.parquet"
if os.path.exists(fn): df = pd.concat([pd.read_parquet(fn), df], ignore_index=True)
df.to_parquet(fn, compression="zstd")
