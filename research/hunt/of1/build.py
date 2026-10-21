#!/usr/bin/env python3
"""OF1 build: one pass over the index-only bhavcopies -> the tables the analysis reads (in <cache>/of1/work).

    python3 -I research/hunt/of1/build.py

Writes:
  pools.parquet       one row per session t+1 (column prev = t): pnl.pool_step sums (futures and options MTM pools)
  chain_near.parquet  NIFTY and BANKNIFTY, the NEAREST expiry each day: strike, kind, settle, previous settle, OI, OI change
  finals.parquet      every NIFTY / BANKNIFTY option on its expiry day: the final settle (intrinsic) and the
                      underlying's final settlement price
  fut_expiries.json   NIFTY / BANKNIFTY futures expiries (the monthly expiries; for the expiry-week regime)
  build_log.json      days used, days skipped and why, lost OI

Expiry dates: a contract whose listed expiry is not a session (a holiday moved it earlier) expires on the last session on
or before it ("effective expiry").
"""
from __future__ import annotations

import bisect
import json
import os
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import load as LD  # noqa: E402
import pnl as P  # noqa: E402

WORK = Path(os.environ.get("OF1_WORK", LD.CACHE / "of1" / "work")).expanduser()
UNDS = ("NIFTY", "BANKNIFTY")


def eff_map(days: list[date], expiries) -> dict:
    """Listed expiry -> the session it really expires on: itself when it is a session or lies after the last session we
    have (the future is unknown), otherwise the last session before it (a holiday moved it earlier)."""
    out = {}
    have = set(days)
    for e in set(expiries):
        if e in have or not days or e > days[-1]:
            out[e] = e
            continue
        i = bisect.bisect_right(days, e) - 1
        out[e] = days[i] if i >= 0 else e
    return out


def prep(df: pd.DataFrame, days: list[date], d: date, closes: dict[str, float] | None = None) -> pd.DataFrame:
    df = df.copy()
    df["expiry"] = pd.to_datetime(df.expiry).dt.date
    df["strike"] = df.strike.astype(float).round(2)
    m = eff_map(days, df.expiry.unique())
    df["eff_expiry"] = df.expiry.map(m)
    for c in ("open", "high", "low", "close", "settle"):
        df[c] = pd.to_numeric(df[c], errors="coerce")
    df = expiry_settle(df.drop_duplicates(P.KEY, keep="last"), d, closes)
    return option_close_mtm(df, d)


def option_close_mtm(df: pd.DataFrame, d: date) -> pd.DataFrame:
    """Options are marked to their CLOSE on ordinary days. NSE's option 'settle' is a theoretical price for contracts
    that did not trade (about 45% of rows with OI), and is 0.00 on some of them (e.g. far-month puts in 2012-2019),
    which would make fake jumps. The close is the last traded price: stale when untraded, never 0 in these files.
    Futures keep NSE's daily settlement price; expiring options keep their intrinsic value (expiry_settle). NSE's
    printed value stays in `nse_settle`."""
    df = df.copy()
    df["nse_settle"] = df.settle
    opt = (df.kind != "FUT") & (df.eff_expiry != d)
    df.loc[opt, "settle"] = df.loc[opt, "close"].where(df.loc[opt, "close"] > 0)
    return df


def parity_final(g: pd.DataFrame) -> float:
    """The underlying's final level from an expiring chain's own CLOSES: at expiry CE - PE = S - K, so S = K + CE - PE.
    Uses the 5 strikes where both sides traded (close > 0) and |CE - PE| is smallest (nearest the money, freshest
    prices); the median of K + CE - PE there. NaN when fewer than 3 such strikes."""
    c = g.pivot_table(index="strike", columns="kind", values="close", aggfunc="first")
    if "CE" not in c or "PE" not in c:
        return np.nan
    c = c[(c.CE > 0) & (c.PE > 0)].dropna()
    if len(c) < 3:
        return np.nan
    near = c.assign(gap=(c.CE - c.PE).abs()).nsmallest(5, "gap")
    return float(np.median(near.index.to_numpy(float) + near.CE.to_numpy(float) - near.PE.to_numpy(float)))


def expiry_settle(df: pd.DataFrame, d: date, closes: dict[str, float] | None = None) -> pd.DataFrame:
    """Expiring options' final value on their expiry day, from what NSE printed in the settle column:
    - from about 2020 on, the UNDERLYING's final settlement price (one value for every strike);
    - before that, 0 for every strike (checked on 2014 and 2019 files).
    The option's final value is its intrinsic value at the underlying's final level S. S = the printed value when it is a
    price (> 0); otherwise NSE's official closing value of the index that day (`closes`: an index option's final
    settlement price IS the index close; on all 623 NIFTY / BANKNIFTY expiries of the later era NSE's printed value
    equals it exactly), and only for an index with no close here, put-call parity on the chain's own closes
    (parity_final). `und_final` keeps S and `final_src` says which ('nse_settle' / 'index_close' / 'parity'). A file
    whose expiring settles differ across strikes (premiums already) is left alone."""
    df = df.copy()
    df["und_final"] = np.nan
    df["final_src"] = None
    ex = (df.kind != "FUT") & (df.eff_expiry == d)
    for u, g in df[ex].groupby("und"):
        vals = g.settle.dropna().unique()
        if len(vals) != 1:
            continue
        S, src = float(vals[0]), "nse_settle"
        if not S > 0 and closes and closes.get(u, 0) > 0:
            S, src = float(closes[u]), "index_close"
        elif not S > 0:
            S, src = parity_final(g), "parity"
        idx = g.index
        if np.isnan(S):                                  # cannot value it: leave NaN (the contract drops out as lost)
            df.loc[idx, "settle"] = np.nan
            continue
        k = df.loc[idx, "strike"].to_numpy(float)
        call = (df.loc[idx, "kind"] == "CE").to_numpy()
        df.loc[idx, "settle"] = np.where(call, np.maximum(S - k, 0.0), np.maximum(k - S, 0.0))
        df.loc[idx, "und_final"] = S
        df.loc[idx, "final_src"] = src
    return df


def main() -> int:
    WORK.mkdir(parents=True, exist_ok=True)
    days = LD.bhav_days()
    ix = LD.index_closes()
    log = {"days": len(days), "skipped": [], "lost_oi_days": 0}
    pools, chains, finals = [], [], []
    futexp: set[tuple[str, date]] = set()
    prev, d_prev = None, None
    for i, d in enumerate(days):
        try:
            ts = pd.Timestamp(d)
            closes = {u: float(ix.loc[ts, f"{u}_close"]) for u in UNDS if ts in ix.index and pd.notna(ix.loc[ts, f"{u}_close"])}
            cur = prep(LD.bhav(d), days, d, closes)
        except Exception as e:                       # a damaged file: skip that day, say so
            log["skipped"].append(f"{d}: {type(e).__name__}: {e}")
            prev, d_prev = None, None
            continue
        if prev is not None:
            # pool_step drops contracts that expired on d_prev; use the effective expiry for that
            s = P.pool_step(prev.assign(expiry=prev.eff_expiry), cur.assign(expiry=cur.eff_expiry), d_prev)
            s["date"], s["prev"] = d, d_prev
            if s["lost_oi"] > 0:
                log["lost_oi_days"] += 1
            pools.append(s)
        for u in UNDS:
            o = cur[(cur.und == u) & cur.kind.isin(["CE", "PE"])]
            if o.empty:
                continue
            live = o[o.eff_expiry >= d]
            if not live.empty:
                near = live.eff_expiry.min()
                c = live[live.eff_expiry == near][["und", "expiry", "eff_expiry", "strike", "kind", "settle", "close",
                                                  "oi_qty", "chg_oi_qty", "contracts"]].copy()
                if prev is not None:
                    pp = prev[(prev.und == u)][P.KEY + ["settle"]].rename(columns={"settle": "prev_settle"})
                    c = c.merge(pp, on=["und", "kind", "expiry", "strike"], how="left")
                else:
                    c["prev_settle"] = np.nan
                c["date"] = d
                fut = cur[(cur.und == u) & (cur.kind == "FUT") & (cur.eff_expiry >= d)].sort_values("eff_expiry")
                c["fut_settle"] = float(fut.settle.iloc[0]) if len(fut) else np.nan
                chains.append(c)
            ex = o[o.eff_expiry == d]
            if not ex.empty:
                finals.append(ex[["und", "expiry", "eff_expiry", "strike", "kind", "settle", "und_final", "final_src"]]
                              .rename(columns={"settle": "final"})
                              .assign(date=d))
        f = cur[(cur.kind == "FUT") & cur.und.isin(UNDS)]
        futexp |= set(zip(f.und, f.eff_expiry))
        prev, d_prev = cur, d
        if (i + 1) % 250 == 0:
            print(f"{i + 1}/{len(days)} {d}", flush=True)
    pd.DataFrame(pools).to_parquet(WORK / "pools.parquet", index=False)
    pd.concat(chains, ignore_index=True).to_parquet(WORK / "chain_near.parquet", index=False)
    pd.concat(finals, ignore_index=True).to_parquet(WORK / "finals.parquet", index=False)
    (WORK / "fut_expiries.json").write_text(json.dumps(sorted([u, str(e)] for u, e in futexp)))
    log["first"], log["last"] = str(days[0]), str(days[-1])
    (WORK / "build_log.json").write_text(json.dumps(log, indent=1))
    print(json.dumps({k: v for k, v in log.items() if k != "skipped"}), "skipped", len(log["skipped"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
