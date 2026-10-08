"""h33 headline timing calibration: compare sitemap lastmod and the id suffix-minimum ('pub') with the publication time
printed on the article page (ET: publishedDate / 'Mon d, yyyy, hh:mm:ss AM IST'; Moneycontrol: datePublished).

    python3 -I research/hunt/h33/calib.py list <n_per_src>   -> <scratch>/hunt/h33/calib_urls.tsv (random sample of
                                                               classified headlines with a market-hours timestamp)
    bash research/hunt/h33/fetch_pages.sh calib_urls.tsv raw/page_times.tsv   (keeps only the time strings)
    python3 -I research/hunt/h33/calib.py an                 -> calib.csv + summary
"""
from __future__ import annotations

import os
import re
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h33")


def url(r):
    if r.src == "et":
        return f"https://economictimes.indiatimes.com/{r.section}/{r.slug.replace(' ', '-')}/articleshow/{r.aid}.cms"
    return f"https://www.moneycontrol.com/news/{r.section}/{r.slug.replace(' ', '-')}-{r.aid}.html"


def page_time(src, txt):
    if src == "et":
        m = re.search(r"publishedDate'?\"?\s*:\s*['\"](\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)", txt)
    else:
        m = re.search(r"datePublished\"\s*:\s*\"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)", txt)
    return pd.Timestamp(m.group(1)) if m else pd.NaT


def page_table():
    """raw/page_times.tsv (name, http code, page publication time) written by fetch_pages.sh -> src, aid, page."""
    p = pd.read_csv(os.path.join(OUT, "raw/page_times.tsv"), sep="\t", header=None, names=["n", "code", "page"])
    p["src"], p["aid"] = p.n.str[:2], p.n.str[3:].astype(int)
    p["page"] = pd.to_datetime(p.page, errors="coerce")
    return p[["src", "aid", "page"]].drop_duplicates(["src", "aid"])


def main():
    if sys.argv[1] == "list":
        n = int(sys.argv[2])
        hl = pd.read_parquet(os.path.join(OUT, "news_hl.parquet"))
        hl = hl[(hl["min"] >= 9 * 60 + 20) & (hl["min"] <= 14 * 60 + 30)]
        s = pd.concat([g.sample(min(n, len(g)), random_state=3) for _, g in hl.groupby("src")])
        with open(os.path.join(OUT, "calib_urls.tsv"), "w") as f:
            for r in s.itertuples():
                f.write(f"{r.src}_{r.aid}\t{url(r)}\n")
        print(len(s))
        return
    hl = pd.read_parquet(os.path.join(OUT, "headlines.parquet"))
    d = page_table().merge(hl, on=["src", "aid"])
    cal = pd.read_csv(os.path.join(OUT, "calib_urls.tsv"), sep="\t", header=None, names=["n", "u"])
    d = d[(d.src + "_" + d.aid.astype(str)).isin(cal.n)]
    d["lag_lastmod"] = (d.lastmod - d.page).dt.total_seconds() / 60
    d["lag_pub"] = (d.pub - d.page).dt.total_seconds() / 60
    d.to_csv(os.path.join(OUT, "calib.csv"), index=False)
    for s, g in d.groupby("src"):
        g = g.dropna(subset=["page"])
        print(s, "n", len(g))
        for c in ("lag_lastmod", "lag_pub"):
            x = g[c]
            print(f"  {c}: |lag|<=2min {np.mean(x.abs() <= 2):.2f}  <=10min {np.mean(x.abs() <= 10):.2f}  "
                  f"early(<-2) {np.mean(x < -2):.2f}  late(>10) {np.mean(x > 10):.2f}  median {x.median():.1f}")


if __name__ == "__main__":
    main()
