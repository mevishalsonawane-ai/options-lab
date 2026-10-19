#!/usr/bin/env python3
"""Bad-tick checks on newly fetched lake data (19 Oct 2026).

For every option file given: the lake spike metric (spikes.py: one-minute spikes that revert, per contract-minute) and
the lakeclean checks (research/hunt/lakeclean.py: foreign rows, stray opens, capped highs, stale repeated prints), on
the rows from --since on (the newly fetched part; all rows for a new book). Foreign rows are judged against the index
close (index_1m files) for NSE/BSE books and, for MCX books, against the median `spot` of the book's own rows at that
minute (the underlying future; there is no separate MCX index). For MCX futures (fut_1m_live_mcx.parquet): the same
spike rule on contract-minute closes, a tighter futures rule (a close more than 2% from both neighbours that are within
0.5% of each other) and the share of padded minutes (flat bar, zero volume, same close as the minute before).

  python -I research/data/newdata_checks.py LAKE_DIR OUT.json [--since YYYY-MM-DD] FILE [FILE ...]
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import spikes  # noqa: E402
from research.hunt import lakeclean as LC  # noqa: E402

IST = "Asia/Kolkata"
FUT_JUMP, FUT_NEAR = 0.02, 0.005


def _rows(df: pd.DataFrame, mcx: bool) -> pd.DataFrame:
    t = df.ts.dt.tz_convert(IST)
    o = 9 * 60 if mcx else 9 * 60 + 15
    return df.assign(day=t.dt.date.to_numpy(), r=(t.dt.hour * 60 + t.dt.minute - o).to_numpy())


def index_closes(lake: str, und: str, days) -> dict:
    sym = {"SENSEX": "SENSEX", "BANKEX": "BANKEX"}.get(und, und)
    ys = sorted({d.year for d in days})
    fs = [f"{lake}/index_1m_{sym}_{y}.parquet" for y in ys if os.path.exists(f"{lake}/index_1m_{sym}_{y}.parquet")]
    if not fs:
        return {}
    ix = _rows(pd.concat([pd.read_parquet(f, columns=["ts", "close"]) for f in fs]), False)
    ix = ix[ix.day.isin(set(days)) & (ix.r >= 0) & (ix.r < LC.NROWS)]
    out = {}
    for d, g in ix.groupby("day"):
        a = np.full(LC.NROWS, np.nan)
        a[g.r.to_numpy()] = g.close.to_numpy()
        out[d] = a
    return out


def spot_closes(df: pd.DataFrame, width: int) -> dict:
    """Per day, the median of the rows' spot at each minute (MCX books: the underlying future)."""
    m = df[(df.r >= 0) & (df.r < width)].groupby(["day", "r"]).spot.median()
    out = {}
    for d, g in m.groupby(level=0):
        a = np.full(width, np.nan)
        a[g.index.get_level_values(1).to_numpy()] = g.to_numpy()
        out[d] = a
    return out


def option_file(lake: str, fn: str, since=None) -> dict:
    b = spikes.book(fn)
    und, mcx = b[0], spikes.exchange(b[0]) == "MCX"
    df = pd.read_parquet(fn)
    if since is not None:
        df = df[df.ts >= pd.Timestamp(since, tz=IST)]
    out = {"file": os.path.basename(fn), "since": since, "rows": int(len(df))}
    if not len(df):
        return out
    out["spikes"] = spikes.spike_metric(df, und)
    df = _rows(df.reset_index(drop=True), mcx)
    old = LC.NROWS
    try:
        if mcx:
            LC.NROWS = 900                          # 09:00-23:59; lakeclean's grid is the NSE day by default
            closes = spot_closes(df, LC.NROWS)
        else:
            closes = index_closes(lake, und, set(df.day))
        _, _, log = LC.clean(df, closes, stale=True)
    finally:
        LC.NROWS = old
    c = LC.counts(log, len(df))
    c.setdefault("stale_run", 0)
    bad = c["stray_open"] + c["stale_run"]
    c["bad_tick_share"] = round(bad / len(df), 8)
    c["foreign_share"] = round(c["foreign"] / len(df), 8)
    c["foreign_ref"] = "median spot of the book" if mcx else ("index_1m close" if closes else "none (no index file)")
    out["lakeclean"] = c
    return out


def futures_file(fn: str, since=None) -> dict:
    df = pd.read_parquet(fn)
    if since is not None:
        df = df[df.ts >= pd.Timestamp(since, tz=IST)]
    out = {"file": os.path.basename(fn), "since": since, "rows": int(len(df))}
    if not len(df):
        return out
    x = df.assign(strike=df.expiry.astype(str), side="F")
    out["spikes_50pct"] = spikes.spike_metric(x, "GOLD")["spikes"]
    per = {}
    for s, g in x.groupby("symbol", observed=True):
        g = g.sort_values(["expiry", "ts"])
        g = g.assign(_d=g.ts.dt.tz_convert(IST).dt.date)
        k = g.groupby(["expiry", "_d"], observed=True).close
        p, n, c = k.shift(1), k.shift(-1), g.close
        hi, lo = np.maximum(p, n), np.minimum(p, n)
        sp = ((c > hi * (1 + FUT_JUMP)) | (c < lo * (1 - FUT_JUMP))) & (hi <= lo * (1 + FUT_NEAR))
        flat = (g.open == g.high) & (g.high == g.low) & (g.low == g.close) & (g.volume == 0) & (c == p)
        per[str(s)] = dict(minutes=int(len(g)), spikes_2pct=int(sp.sum()), padded=int(flat.sum()),
                           padded_share=round(float(flat.mean()), 6))
    out["per_symbol"] = per
    tot = sum(v["minutes"] for v in per.values())
    out["spikes_2pct"] = sum(v["spikes_2pct"] for v in per.values())
    out["spike_share_2pct"] = round(out["spikes_2pct"] / tot, 8) if tot else None
    out["padded_share"] = round(sum(v["padded"] for v in per.values()) / tot, 6) if tot else None
    return out


def main(argv):
    lake, outp, args = argv[0], argv[1], argv[2:]
    since = None
    if args and args[0] == "--since":
        since, args = args[1], args[2:]
    res = []
    for fn in args:
        fn = fn if os.path.isabs(fn) or os.path.exists(fn) else os.path.join(lake, fn)
        r = futures_file(fn, since) if os.path.basename(fn).startswith("fut_1m") else option_file(lake, fn, since)
        print(json.dumps(r, default=str)[:400], flush=True)
        res.append(r)
    json.dump(res, open(outp, "w"), indent=1, default=str)


if __name__ == "__main__":
    main(sys.argv[1:])
