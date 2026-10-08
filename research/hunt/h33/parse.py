"""h33: parse the raw news sitemaps (untrusted data; run with python -I) into one headline table.

    python3 -I research/hunt/h33/parse.py <raw_dir> <out.parquet>

Each sitemap row gives a URL and a <lastmod> time (IST offset). lastmod can be LATER than the first publication (an
edit), never earlier. Article ids are assigned when the article is created and grow with time, so the creation time
of article i is at most min(lastmod_j : id_j >= id_i). We use that suffix-minimum ('pub') as the timestamp. It removes
late edits; it can only make a time EARLIER, never later, than lastmod. Rows where it moved by > 30 min are flagged.
Headline text = the URL slug (words joined by '-'); section = the URL path.
"""
from __future__ import annotations

import glob
import gzip
import os
import re
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

LOC = re.compile(rb"<loc>([^<]+)</loc>\s*<lastmod>([^<]+)</lastmod>")
ET = re.compile(r"https://economictimes\.indiatimes\.com/(.*)/([^/]+)/articleshow/(\d+)\.cms")
MC = re.compile(r"https://www\.moneycontrol\.com/news/(.*)/([^/]+?)-(\d+)\.html")


def parse(raw):
    rows = []
    for f in sorted(glob.glob(os.path.join(raw, "*.xml.gz"))):
        src = os.path.basename(f)[:2]
        if src not in ("et", "mc"):
            continue
        data = gzip.open(f).read()
        n0 = len(rows)
        for loc, lm in LOC.findall(data):
            u = loc.decode("utf-8", "replace").strip()
            m = (ET if src == "et" else MC).match(u)
            if not m:
                continue
            rows.append((src, int(m.group(3)), m.group(1), m.group(2).replace("-", " ").lower(), lm.decode()[:25]))
        print(os.path.basename(f), len(rows) - n0, flush=True)
    df = pd.DataFrame(rows, columns=["src", "aid", "section", "slug", "lastmod"])
    df["lastmod"] = pd.to_datetime(df.lastmod, utc=True, errors="coerce").dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df = df.dropna(subset=["lastmod"]).drop_duplicates(["src", "aid"]).sort_values(["src", "aid"]).reset_index(drop=True)
    out = []
    for s, g in df.groupby("src"):
        lm = g.lastmod.values.astype("datetime64[s]").astype(np.int64)
        suf = np.minimum.accumulate(lm[::-1])[::-1]
        g = g.copy()
        g["pub"] = pd.to_datetime(suf, unit="s")
        g["edit_min"] = (lm - suf) / 60.0
        out.append(g)
    return pd.concat(out, ignore_index=True)


if __name__ == "__main__":
    df = parse(sys.argv[1])
    df.to_parquet(sys.argv[2])
    print(len(df), "rows")
    print(df.groupby("src").agg(n=("aid", "size"), first=("pub", "min"), last=("pub", "max"),
                                edited30=("edit_min", lambda x: float((x > 30).mean()))))
