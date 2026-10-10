"""h33: headline table -> intraday news events (pre-registered keyword classes; see PREREG.md). No prices, no P&L.

    python3 -I research/hunt/h33/news.py      reads <scratch>/hunt/h33/headlines.parquet
                                              writes news_hl.parquet (classified market-hours headlines)
                                                     news_events.parquet (one row per class event)
"""
from __future__ import annotations

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h33")


def rx(*words):
    return re.compile(r"\b(" + "|".join(words) + r")\b")


# reaction / market-wrap / opinion headlines: they describe a move that already happened, or are not news
WRAP = rx("sensex", "nifty", "bank nifty", "market", "markets", "stock market", "stocks", "shares", "share price",
          "dalal street", "d street", "rupee", "live", "live updates", "buzzing", "gainers", "losers", "buy", "sell",
          "target price", "trade setup", "technical", "chart", "outlook", "view", "views", "opinion", "explained",
          "explainer", "what", "how", "why", "should", "will", "may", "could", "expected", "likely", "preview", "ahead",
          "analysts", "brokerages", "mutual fund", "mutual funds", "ipo", "f o", "options", "futures", "smallcap",
          "midcap", "podcast", "video", "quiz", "photos", "watch")
ACT = ("hikes|hike|raises|cuts|cut|slashes|keeps|holds|bans|ban|bars|curbs|imposes|imposed|penalty|penalises|fines|"
       "fined|cancels|cancelled|scraps|approves|approved|clears|announces|announced|orders|ordered|notifies|suspends|"
       "suspended|restricts|restrictions|probe|raid|raids|arrest|arrested|resigns|quits|steps down|sacked|removed|"
       "merger|merge|acquire|acquires|acquisition|stake|deal|downgrade|downgrades|upgrade|default|fraud|halts|halt")
CLASSES = {
    # class: (index traded, subject regex, action regex)
    "rbi": ("BANKNIFTY", rx("rbi", "reserve bank", "mpc", "repo", "crr", "governor das", "shaktikanta das",
                            "sanjay malhotra"), re.compile(r"\b(" + ACT + r"|rate|rates|liquidity|measures|"
                                                           r"policy|unchanged)\b")),
    "govt": ("NIFTY", rx("govt", "government", "centre", "cabinet", "finance ministry", "fm", "sitharaman", "pm modi",
                         "gst council", "cbdt", "sebi", "windfall", "export duty", "import duty", "customs duty"),
             re.compile(r"\b(" + ACT + r"|tax|levy|duty|stimulus|package|relief|ordinance|policy)\b")),
    "bank_corp": ("BANKNIFTY", rx("hdfc bank", "icici bank", "sbi", "state bank", "kotak", "kotak mahindra bank",
                                  "axis bank", "indusind", "indusind bank", "yes bank", "bank of baroda", "pnb"),
                  re.compile(r"\b(" + ACT + r"|results|q1|q2|q3|q4|net profit|profit|loss|npa|ceo|md)\b")),
    "heavy_corp": ("NIFTY", rx("reliance", "ril", "infosys", "infy", "tcs", "airtel", "bharti airtel", "itc", "larsen",
                               "l t", "adani", "hindenburg", "hul", "hindustan unilever", "bajaj finance", "maruti",
                               "mahindra", "m m", "wipro", "hcl tech", "sun pharma", "tata motors", "tata steel"),
                   re.compile(r"\b(" + ACT + r"|results|q1|q2|q3|q4|net profit|profit|loss|ceo|guidance|outage|fire)\b")),
    "geo": ("NIFTY", rx("war", "attack", "attacks", "missile", "missiles", "airstrike", "airstrikes", "air strike",
                        "terror", "terrorist", "blast", "ceasefire", "invasion", "invades", "tariff", "tariffs",
                        "sanctions", "lockdown", "clash", "clashes", "escalation", "earthquake", "coup", "drone strike",
                        "operation sindoor"), None),
}
T0, T1 = 9 * 60 + 20, 14 * 60 + 30
NOVELTY = 120          # minutes without a same-class headline before an event
BURST = 20             # minutes after the first headline over which intensity is counted


def classify(df):
    s = df.slug.values
    sec = df.section.values
    wrap = np.array([bool(WRAP.search(x)) for x in s])
    # sections that are market commentary / personal finance / lifestyle are not 'news breaking'
    badsec = np.array([bool(re.search(r"(markets/stocks/recos|markets/expert-view|wealth|personal-finance|magazines|"
                                      r"panache|opinion|technical|mutual-fund|videos|photos|podcast|cryptocurrency|"
                                      r"astrology|entertainment|lifestyle|sports|cricket|world-news/us)", x)) for x in sec])
    out = {}
    for k, (u, subj, act) in CLASSES.items():
        m = np.array([bool(subj.search(x)) and (act is None or bool(act.search(x))) for x in s])
        out[k] = m & ~wrap & ~badsec
    return pd.DataFrame(out, index=df.index)


def main():
    hl = pd.read_parquet(os.path.join(OUT, "headlines.parquet"))
    hl = hl[hl.pub >= "2020-07-01"].copy()
    cl = classify(hl)
    hl = pd.concat([hl, cl], axis=1)
    hl["any"] = cl.any(axis=1)
    hl = hl[hl["any"]].copy()
    # Timestamp = the article page's publication time where fetched (pages.csv), else sitemap lastmod. lastmod is
    # never earlier than publication (calibration: calib.py), so it cannot create look-ahead; it can be late after edits.
    ts = hl.lastmod.copy()
    pf = os.path.join(OUT, "raw/page_times.tsv")
    if os.path.exists(pf):
        from calib import page_table
        pg = page_table().dropna(subset=["page"])
        key = pd.Series(pg.page.values, index=list(zip(pg.src, pg.aid)))
        k = list(zip(hl.src, hl.aid))
        got = np.array([x in key.index for x in k])
        ts[got] = np.minimum(key.loc[[x for x, g in zip(k, got) if g]].values, ts[got].values)
        print("page times used:", int(got.sum()))
    hl["ts"] = ts
    hl["day"] = hl.ts.dt.date
    hl["min"] = hl.ts.dt.hour * 60 + hl.ts.dt.minute
    hl["t"] = hl.ts.astype("datetime64[s]").astype(np.int64) // 60
    hl.to_parquet(os.path.join(OUT, "news_hl.parquet"))
    ev = []
    for k, u in [(k, v[0]) for k, v in CLASSES.items()] + [("any", "NIFTY")]:
        g = hl[hl[k]].sort_values("t")
        t = g.t.values
        for i in range(len(t)):
            if i > 0 and t[i] - t[i - 1] < NOVELTY:
                continue
            m = int(g["min"].values[i])
            if not (T0 <= m <= T1) or g.ts.values[i].astype("datetime64[D]").item().weekday() >= 5:
                continue
            j = np.searchsorted(t, t[i] + BURST, side="right")
            ev.append(dict(cls=k, und=u, day=g.day.values[i], hmin=m, n20=int(j - i),
                           nsrc=int(g.src.values[i:j].tolist().count("et") > 0) + int(g.src.values[i:j].tolist().count("mc") > 0),
                           slug=g.slug.values[i], src=g.src.values[i], aid=int(g.aid.values[i]),
                           section=g.section.values[i], edit_min=float(g.edit_min.values[i])))
    ev = pd.DataFrame(ev)
    ev.to_parquet(os.path.join(OUT, "news_events.parquet"))
    print("classified market-relevant headlines:", len(hl))
    print(hl[list(CLASSES)].sum())
    print(ev.groupby("cls").agg(n=("day", "size"), n20ge3=("n20", lambda x: int((x >= 3).sum())),
                                both_src=("nsrc", lambda x: int((x == 2).sum()))))


if __name__ == "__main__":
    main()
