"""h33 headline coverage table (counts only): headlines per source and year, market-hours share, classified counts,
events per class and year, share of event headlines timed from the article page.

    python3 -I research/hunt/h33/cover.py
"""
from __future__ import annotations

import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h33")
pd.set_option("display.width", 200)

hl = pd.read_parquet(os.path.join(OUT, "headlines.parquet"), columns=["src", "lastmod"])
hl["year"] = hl.lastmod.dt.year
m = hl.lastmod.dt.hour * 60 + hl.lastmod.dt.minute
hl["mkt"] = (m >= 555) & (m <= 930) & (hl.lastmod.dt.weekday < 5)
print("ALL HEADLINES by source x year (market-hours share)")
print(hl.groupby(["year", "src"]).agg(n=("mkt", "size"), mkt=("mkt", "mean")).unstack().round(2).to_string())
nh = pd.read_parquet(os.path.join(OUT, "news_hl.parquet"))
nh["year"] = nh.ts.dt.year
print("\nCLASSIFIED headlines by year")
print(nh.groupby("year")[["rbi", "govt", "bank_corp", "heavy_corp", "geo", "any"]].sum().to_string())
ev = pd.read_parquet(os.path.join(OUT, "news_events.parquet"))
ev["year"] = pd.to_datetime(ev.day).dt.year
print("\nEVENTS (09:20-14:30, novelty 120 min) by class x year")
print(ev.groupby(["cls", "year"]).size().unstack(fill_value=0).to_string())
print("\nburst events (>=3 in 20 min) by class:", ev[ev.n20 >= 3].groupby("cls").size().to_dict())
pg = pd.read_csv(os.path.join(OUT, "raw/page_times.tsv"), sep="\t", header=None, names=["n", "c", "p"])
ev["n"] = ev.src + "_" + ev.aid.astype(str)
print("event first headlines timed from the article page:", round(ev.n.isin(set(pg.n[pg.p.notna()])).mean(), 3))
print("\nexamples of burst events:")
print(ev[ev.n20 >= 3].sort_values("n20", ascending=False)[["cls", "day", "hmin", "n20", "slug"]].head(20).to_string())
