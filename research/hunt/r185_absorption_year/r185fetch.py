"""R185 gap fetcher: the Dhan v2 1-minute data the lake does not hold yet (20 Oct - 13 Nov 2026), in the lake's format.

The lake on Google Drive (`gdrive:IraAlgo-data-lake`, research/data/README.md) already holds the index 1-minute bars and
the rolling index options (ATM-10..ATM+10, WEEK, expiry code 1) to 19 Oct 2026. This fetches only the gap after it:

    python -I research/hunt/r185_absorption_year/r185fetch.py --und NIFTY --from 2026-10-20 --to 2026-11-13
    python -I research/hunt/r185_absorption_year/r185fetch.py --und NIFTY --build     # -> lake-format files + manifest

- Index 1-minute: /charts/intraday (IDX_I), one request for the whole gap (it allows 90 days).
- Options: /charts/rollingoption, ONE trading day per request (fromDate = D, toDate = D + 1; Dhan answers DH-905 to longer
  windows since about 29 Oct 2026; the answer also holds D + 1, which is dropped, so each request owns exactly one day).
  WEEK, expiryCode 1 (= the nearest expiry, checked against the phone's recordings), ATM-10..ATM+10, CALL and PUT.
- Every answer is cached as one small Parquet under ~/.cache/iraalgo/dhan/raw/ (an empty answer leaves a `.empty`
  marker), so a restart never fetches a chunk twice.
- Credentials: dhan_client reads them from the environment and sends them only as HTTP headers; nothing here prints,
  logs or stores them. Rate: dhan_client's pacer (a gap between calls), backoff on 429 / 5xx.
- Dhan has no second-level history: /charts/intraday with interval "1s" answers with empty arrays and
  /charts/rollingoption refuses every seconds interval (accepts 1, 5, 15, 60). Checked 16 Nov 2026.

Tests (test_r185.py) pass a fake `post`; they never call the network or need the token. Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

TZ = "Asia/Kolkata"   # the lake's timestamps (tz-aware IST)
CACHE = Path(os.path.expanduser("~/.cache/iraalgo/dhan"))
LAKE = Path(os.path.expanduser("~/.cache/iraalgo/lake"))
TAG = "gap1113"
RD = ["open", "high", "low", "close", "iv", "volume", "strike", "oi", "spot"]
# name: (security id, index segment, option segment) - the lake's ids (research/data/lake_fetch.py)
UND = {"NIFTY": (13, "IDX_I", "NSE_FNO"), "BANKNIFTY": (25, "IDX_I", "NSE_FNO"), "FINNIFTY": (27, "IDX_I", "NSE_FNO"),
       "MIDCPNIFTY": (442, "IDX_I", "NSE_FNO"), "SENSEX": (51, "IDX_I", "BSE_FNO")}
OFFSETS = range(-10, 11)
SESSION = (9 * 60 + 15, 15 * 60 + 29)


def default_post():
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "data"))
    import dhan_client  # noqa: E402  (reads the credentials from the environment itself)
    return dhan_client.post


def strike_name(off: int) -> str:
    return "ATM" if off == 0 else f"ATM{off:+d}"


def option_body(und: str, day: dt.date, off: int, side: str) -> dict:
    sid, _, seg = UND[und]
    return dict(exchangeSegment=seg, interval="1", securityId=sid, instrument="OPTIDX", expiryFlag="WEEK", expiryCode=1,
                strike=strike_name(off), drvOptionType="CALL" if side == "C" else "PUT", requiredData=RD,
                fromDate=day.isoformat(), toDate=(day + dt.timedelta(days=1)).isoformat())


def index_body(und: str, a: dt.date, b: dt.date) -> dict:
    sid, seg, _ = UND[und]
    return dict(securityId=str(sid), exchangeSegment=seg, instrument="INDEX", interval="1", oi=False,
                fromDate=f"{a} 09:00:00", toDate=f"{b + dt.timedelta(days=1)} 00:00:00")


def arrays_df(d: dict | None) -> pd.DataFrame:
    """Dhan's column arrays -> a frame with an IST `ts`; empty when there is nothing."""
    if not isinstance(d, dict) or not d.get("timestamp"):
        return pd.DataFrame()
    n = len(d["timestamp"])
    cols = {k: v for k, v in d.items() if isinstance(v, list) and len(v) == n}
    df = pd.DataFrame(cols)
    df["ts"] = pd.to_datetime(df.pop("timestamp").astype("int64"), unit="s", utc=True).dt.tz_convert(TZ)
    return df


def keep_day(df: pd.DataFrame, day: dt.date) -> pd.DataFrame:
    """Only the requested day's session minutes (09:15 - 15:29), as the lake's cleaning rule."""
    if not len(df):
        return df
    hm = df.ts.dt.hour * 60 + df.ts.dt.minute
    ok = (df.ts.dt.date == day) & (hm >= SESSION[0]) & (hm <= SESSION[1])
    return df[ok].reset_index(drop=True)


def chunk_path(und: str, day: dt.date, off: int, side: str, root: Path = CACHE) -> Path:
    return root / "raw" / und / "opt" / f"{off:+03d}{side}" / f"{day}.parquet"


def fetch_option_day(post, und: str, day: dt.date, off: int, side: str, root: Path = CACHE) -> str:
    """One request -> one cached chunk. Returns 'cached', 'ok', 'empty' or an error string (nothing written)."""
    p = chunk_path(und, day, off, side, root)
    if p.exists() or p.with_suffix(".empty").exists():
        return "cached"
    st, js, err = post("/charts/rollingoption", option_body(und, day, off, side))
    if st != 200 or not isinstance(js, dict):
        return f"error {st} {err}"[:200]
    data = js.get("data") or {}
    df = keep_day(arrays_df(data.get("ce" if side == "C" else "pe")), day)
    p.parent.mkdir(parents=True, exist_ok=True)
    if not len(df):
        p.with_suffix(".empty").write_text("")
        return "empty"
    tmp = p.with_suffix(".tmp")
    df.to_parquet(tmp, index=False)
    os.replace(tmp, p)
    return "ok"


def fetch_index(post, und: str, a: dt.date, b: dt.date, root: Path = CACHE) -> pd.DataFrame:
    p = root / "raw" / und / f"index_{a}_{b}.parquet"
    if p.exists():
        return pd.read_parquet(p)
    st, js, err = post("/charts/intraday", index_body(und, a, b))
    if st != 200:
        raise RuntimeError(f"index fetch failed: {st} {err}"[:200])
    df = arrays_df(js)
    if len(df):
        hm = df.ts.dt.hour * 60 + df.ts.dt.minute
        df = df[(df.ts.dt.date >= a) & (df.ts.dt.date <= b) & (hm >= SESSION[0]) & (hm <= SESSION[1])]
        df = df.reset_index(drop=True)
    p.parent.mkdir(parents=True, exist_ok=True)
    df.to_parquet(p, index=False)
    return df


def trading_days(idx: pd.DataFrame) -> list:
    """Days with index minutes (special sessions such as Muhurat included here; the study drops short days)."""
    return sorted(set(idx.ts.dt.date)) if len(idx) else []


def run(post, und: str, a: dt.date, b: dt.date, threads: int = 3, root: Path = CACHE, log=print) -> dict:
    idx = fetch_index(post, und, a, b, root)
    days = trading_days(idx)
    jobs = [(d, o, s) for d in days for o in sorted(OFFSETS, key=abs) for s in "CP"]
    counts, lock = {}, threading.Lock()

    def one(j):
        r = fetch_option_day(post, und, *j, root=root)
        key = r if r in ("cached", "ok", "empty") else "error"
        with lock:
            counts[key] = counts.get(key, 0) + 1
            if key == "error":
                counts.setdefault("errors", []).append(f"{j[0]} {j[1]:+d}{j[2]}: {r}")
            n = sum(v for k, v in counts.items() if k != "errors")
            if n % 200 == 0:
                log(und, n, "/", len(jobs), {k: v for k, v in counts.items() if k != "errors"})
        return r

    with ThreadPoolExecutor(max_workers=threads) as ex:
        list(ex.map(one, jobs))
    return dict(und=und, days=[str(d) for d in days], jobs=len(jobs), **counts)


def lake_ts(ts: pd.Series) -> pd.Series:
    """Any tz-aware IST spelling or unit -> the lake's datetime64[ns, Asia/Kolkata]."""
    return pd.to_datetime(ts, utc=True).dt.tz_convert(TZ).astype(f"datetime64[ns, {TZ}]")


def build(und: str, root: Path = CACHE, out: Path = LAKE) -> dict:
    """Raw chunks -> opt_<UND>_WEEK_2026_gap1113.parquet and index_1m_<UND>_2026_gap1113.parquet (the lake's columns),
    plus an aggregate manifest (days, rows, gaps) for the repo."""
    parts, empty = [], 0
    for sd in sorted((root / "raw" / und / "opt").glob("*")):
        off, side = int(sd.name[:-1]), sd.name[-1]
        for f in sorted(sd.glob("*.parquet")):
            df = pd.read_parquet(f)
            df.insert(0, "offset", np.int8(off))
            df.insert(1, "side", side)
            parts.append(df)
        empty += len(list(sd.glob("*.empty")))
    opt = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()
    if len(opt):
        opt["side"] = opt["side"].astype("category")
        for c in ("open", "high", "low", "close", "strike", "spot"):
            opt[c] = opt[c].astype("float64")
        opt["iv"] = opt["iv"].astype("float32")
        for c in ("volume", "oi"):
            opt[c] = opt[c].fillna(0).astype("int64")
        opt["ts"] = lake_ts(opt.ts)
        n0 = len(opt)
        opt = opt.drop_duplicates(["offset", "side", "ts"], keep="last").sort_values(["offset", "side", "ts"])
        opt = opt[["offset", "side", "open", "high", "low", "close", "iv", "volume", "strike", "oi", "spot", "ts"]]
        dups = n0 - len(opt)
    else:
        dups = 0
    idxf = sorted((root / "raw" / und).glob("index_*.parquet"))
    idx = pd.concat([pd.read_parquet(f) for f in idxf], ignore_index=True) if idxf else pd.DataFrame()
    if len(idx):
        idx["ts"] = lake_ts(idx.ts)
        idx = idx.drop_duplicates("ts").sort_values("ts")[["open", "high", "low", "close", "volume", "ts"]]
    out.mkdir(parents=True, exist_ok=True)
    fo, fi = out / f"opt_{und}_WEEK_2026_{TAG}.parquet", out / f"index_1m_{und}_2026_{TAG}.parquet"
    if len(opt):
        opt.reset_index(drop=True).to_parquet(fo, index=False, compression="zstd")
    if len(idx):
        idx.reset_index(drop=True).to_parquet(fi, index=False, compression="zstd")
    return manifest(und, opt, idx, empty, dups, [fo, fi])


def manifest(und: str, opt: pd.DataFrame, idx: pd.DataFrame, empty: int, dups: int, files: list) -> dict:
    """Aggregates only (no prices): per day the index minutes and the option minutes per series, and the gaps."""
    import hashlib
    days = sorted(set(idx.ts.dt.date)) if len(idx) else []
    per = []
    for d in days:
        im = int((idx.ts.dt.date == d).sum())
        o = opt[opt.ts.dt.date == d] if len(opt) else opt
        g = o.groupby(["offset", "side"], observed=True).size() if len(o) else pd.Series(dtype=int)
        near = o[o.offset.between(-2, 2)] if len(o) else o
        gn = near.groupby(["offset", "side"], observed=True).size() if len(near) else pd.Series(dtype=int)
        per.append(dict(day=str(d), index_minutes=im, option_rows=int(len(o)), series=int(len(g)),
                        series_min_minutes=int(g.min()) if len(g) else 0,
                        near_atm2_series=int(len(gn)), near_atm2_min_minutes=int(gn.min()) if len(gn) else 0,
                        near_atm2_missing_minutes=int(10 * im - len(near)) if len(near) else 10 * im))
    return dict(und=und, source="Dhan /v2/charts/rollingoption (WEEK, expiryCode 1, ATM-10..+10, 1-minute, one day a "
                "request) and /v2/charts/intraday (index, 1-minute)",
                days=len(days), first=str(days[0]) if days else None, last=str(days[-1]) if days else None,
                option_rows=int(len(opt)), index_rows=int(len(idx)), empty_series_days=int(empty),
                duplicate_rows_dropped=int(dups), short_days=[p["day"] for p in per if p["index_minutes"] < 300],
                per_day=per,
                files={f.name: dict(bytes=f.stat().st_size, sha256=hashlib.sha256(f.read_bytes()).hexdigest())
                       for f in files if f.exists()},
                drive=f"gdrive:IraAlgo-data-lake/dhan/{und}/ (and the lake folders options_rolling_1m/, index_1m/)")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--und", required=True, choices=sorted(UND))
    ap.add_argument("--from", dest="a", default="2026-10-20")
    ap.add_argument("--to", dest="b", default="2026-11-13")
    ap.add_argument("--threads", type=int, default=3)
    ap.add_argument("--build", action="store_true")
    ap.add_argument("--manifest", default=None, help="write the aggregate manifest JSON here (in the repo)")
    a = ap.parse_args(argv)
    if a.build:
        m = build(a.und)
        if a.manifest:
            Path(a.manifest).write_text(json.dumps(m, indent=1))
        print(json.dumps({k: v for k, v in m.items() if k != "per_day"}, indent=1))
        return
    r = run(default_post(), a.und, dt.date.fromisoformat(a.a), dt.date.fromisoformat(a.b), a.threads)
    print(json.dumps({k: (v if k != "errors" else v[:20]) for k, v in r.items()}, indent=1, default=str))


if __name__ == "__main__":
    main()
