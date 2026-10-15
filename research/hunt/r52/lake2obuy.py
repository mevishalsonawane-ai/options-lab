"""Lay the Drive lake's files out the way research/obuy/data.py reads them, so R52 can run the old studies' own obuy
signal functions and engine on the Nov 2024+ data without changing obuy.

    python3 research/hunt/r52/lake2obuy.py <OBUY_DATA dir>      # reads $IRAALGO_LAKE (flat folder of lake Parquet)

Writes (all derived, outside git):
    options/<U>/<WEEK|MONTH>/<CALL|PUT>/<YYYY>.parquet   ts (UTC, ms), offset, strike, spot, open..close, volume, oi, iv
    candles/minute/IDX_I/<U|INDIA_VIX>/<YYYY>.parquet     ts (IST), open, high, low, close
    candles/daily/IDX_I/INDIA_VIX.parquet                 ts (IST midnight), open, high, low, close
Years 2024-2026 only: 2024 sessions are warm-up for indicators; no trade before 20 Nov 2024 is ever kept (r52run.py).

Amendment 2 (15 Oct 2026): `lake2obuy.py <dir> --clean` drops foreign rows and stray prints from every option file
(research/hunt/lakeclean.py; rows outside 09:15-15:29 pass through untouched) and writes, next to the layout,
clean_log_<U>_<SERIES>.parquet (every dropped or capped row), clean_counts.json and the CLEANED marker r52amend.py checks.
`--foreign-only` applies step 1 alone (marker FOREIGN_ONLY): the layout the expiry-day labels are read from, so a dropped
stray print can never change which days a rule skips (the same rule as R51 Amendment A's ATM and expiry labels).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

import r52cfg as K

sys.path.insert(0, str(K.HERE.parent))
import lakeclean  # noqa: E402

YEARS = (2024, 2025, 2026)
COLS = ["ts", "offset", "strike", "spot", "open", "high", "low", "close", "volume", "oi", "iv"]


def _day_r(ts: pd.Series):
    t = ts.dt.tz_convert("Asia/Kolkata") if ts.dt.tz is not None else ts
    return t.dt.date.to_numpy(), (t.dt.hour * 60 + t.dt.minute - (9 * 60 + 15)).to_numpy(np.int64)


def index_closes(u: str, years=YEARS) -> dict:
    """{day: (375,) index minute closes, NaN where no minute} from the lake's index_1m files."""
    parts = [pd.read_parquet(p, columns=["ts", "close"]) for y in years
             if (p := K.LAKE / f"index_1m_{u}_{y}.parquet").exists()]
    out = {}
    if not parts:
        return out
    x = pd.concat(parts, ignore_index=True)
    d, r = _day_r(pd.to_datetime(x.ts))
    ok = (r >= 0) & (r < lakeclean.NROWS) & (x.close.to_numpy() > 0)
    f = pd.DataFrame(dict(day=d[ok], r=r[ok], c=x.close.to_numpy(np.float64)[ok])).drop_duplicates(["day", "r"])
    for day, g in f.groupby("day", sort=True):
        a = np.full(lakeclean.NROWS, np.nan)
        a[g.r.to_numpy()] = g.c.to_numpy()
        out[day] = a
    return out


def clean_frame(df: pd.DataFrame, closes: dict, stray: bool = True):
    """(cleaned lake frame, log of removed / capped rows, rows checked). Rows outside the minute grid are kept as is.
    stray=False: foreign rows only (step 1)."""
    d, r = _day_r(df.ts)
    ing = (r >= 0) & (r < lakeclean.NROWS)
    w = df[ing].copy()
    w["day"], w["r"] = d[ing], r[ing]
    w["side"] = w.side.astype(str).str[0].str.upper()
    kept, after1, log = lakeclean.clean(w, closes)
    if not stray:
        kept, log = after1, log[log.why == "foreign"]
    keep = df[ing].loc[kept.index].copy()
    keep["high"] = kept.high.to_numpy()
    return pd.concat([df[~ing], keep]).sort_index(), log, int(ing.sum())


def convert(out: Path, unds=K.UNDS, years=YEARS, clean: bool = False, stray: bool = True) -> None:
    counts = {}
    for u in unds:
        closes = index_closes(u, years) if clean else None
        for ser in ("WEEK", "MONTH"):
            logs, total = [], 0
            for y in years:
                p = K.LAKE / f"opt_{u}_{ser}_{y}.parquet"
                if not p.exists():
                    continue
                df = pd.read_parquet(p)
                if clean:
                    df, log, n = clean_frame(df, closes, stray)
                    logs.append(log)
                    total += n
                df["ts"] = df.ts.dt.tz_convert("UTC").astype("datetime64[ms, UTC]")
                side = df.side.astype(str).str[0].str.upper()
                for s, name in (("C", "CALL"), ("P", "PUT")):
                    d = out / "options" / u / ser / name
                    d.mkdir(parents=True, exist_ok=True)
                    df.loc[side == s, COLS].reset_index(drop=True).to_parquet(d / f"{y}.parquet", index=False)
            if clean and logs:
                log = pd.concat(logs, ignore_index=True)
                log.to_parquet(out / f"clean_log_{u}_{ser}.parquet", index=False)
                counts[f"{u}_{ser}"] = lakeclean.counts(log, total)
    for u in tuple(unds) + ("INDIAVIX",):
        name = "INDIA_VIX" if u == "INDIAVIX" else u
        d = out / "candles" / "minute" / "IDX_I" / name
        d.mkdir(parents=True, exist_ok=True)
        for y in years:
            p = K.LAKE / f"index_1m_{u}_{y}.parquet"
            if p.exists():
                pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"]).to_parquet(d / f"{y}.parquet", index=False)
    dd = pd.read_parquet(K.LAKE / "index_daily.parquet")
    v = dd[dd.symbol == "INDIAVIX"].copy()
    v["ts"] = pd.to_datetime(v.date).dt.tz_localize("Asia/Kolkata")
    d = out / "candles" / "daily" / "IDX_I"
    d.mkdir(parents=True, exist_ok=True)
    v[["ts", "open", "high", "low", "close"]].to_parquet(d / "INDIA_VIX.parquet", index=False)
    if clean:
        (out / "clean_counts.json").write_text(json.dumps(counts, indent=1))
        if stray:
            (out / "CLEANED").write_text("foreign rows and stray prints removed (R52 Amendment 2)\n")
        else:
            (out / "FOREIGN_ONLY").write_text("foreign rows removed, stray prints kept (expiry labels only)\n")


if __name__ == "__main__":
    fo = "--foreign-only" in sys.argv[2:]
    convert(Path(sys.argv[1]).expanduser(), clean=fo or "--clean" in sys.argv[2:], stray=not fo)
