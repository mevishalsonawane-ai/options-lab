#!/usr/bin/env python3
"""Crypto data lake: Binance public archive (data.binance.vision) → monthly Parquet → Google Drive. (13 Oct 2026)

  python -I research/data/crypto_lake.py run --tier 1                  # BTC/ETH: everything in tier 1 (last 3 years)
  python -I research/data/crypto_lake.py run --tier 2 --workers 6      # SOL/BNB/XRP klines + funding
  python -I research/data/crypto_lake.py run --tier 3                  # BTC/ETH futures aggTrades, last 12 months
  python -I research/data/crypto_lake.py run --series um/funding/BTCUSDT --dry-run
  python -I research/data/crypto_lake.py status                        # summary of the catalog

Why the archive: Binance's REST API is geo-blocked from the cloud boxes (HTTP 451); the bulk archive is not.
Every zip comes with a .CHECKSUM (sha256) and is verified before it is read.

Flow, one batch of months at a time so the disk never holds the whole lake:
  list the archive → plan (monthly zips, plus daily zips for months that have no monthly zip yet) →
  skip months whose Parquet is already on Drive with the same sources and md5 → download + verify sha256 →
  parse → fix the timestamp unit (some 2025 spot files are in microseconds) → sort, drop duplicate timestamps →
  QA (rows vs expected minutes, gaps listed but never filled, OHLC sanity, one daily close per year checked against
  the 1d klines) → Parquet (zstd) → `rclone copy --checksum` + `rclone check --one-way` → catalog → delete local.

Layout on Drive (REMOTE = gdrive:IraAlgo-data-crypto, a sibling of the Dhan lake's IraAlgo-data-lake):
  REMOTE/{market}/{dataset}/{symbol}/{year}/{symbol}-{dataset}-{YYYY-MM}.parquet   market = spot | futures_um
  REMOTE/catalog.json, REMOTE/catalog.csv, REMOTE/README.md
Times are UTC epoch milliseconds (int64). The catalog in git (research/data/crypto/catalog.json) is the resume
state: a month is rebuilt only when its sources change (e.g. October built from daily zips, later replaced by the
monthly zip) or Drive does not hold the same md5. Raw data never goes into git.
"""
from __future__ import annotations

import argparse, calendar, csv, datetime as dt, hashlib, io, json, os, re, shutil, subprocess, sys, tempfile
import threading, time, urllib.request, zipfile
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq

ARCHIVE = "https://data.binance.vision/"
LISTING = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision"
REMOTE = "gdrive:IraAlgo-data-crypto"
HERE = os.path.dirname(os.path.abspath(__file__))
CATALOG = os.path.join(HERE, "crypto", "catalog.json")
README = os.path.join(HERE, "crypto", "README.md")

MARKETS = {"spot": ("data/spot", "spot"), "um": ("data/futures/um", "futures_um")}  # archive root, Drive folder
KLINE_COLS = ["open_time", "open", "high", "low", "close", "volume", "close_time", "quote_volume", "count",
              "taker_buy_volume", "taker_buy_quote_volume", "ignore"]
KINDS = {
    "klines": dict(cols=KLINE_COLS, ts="open_time", times=["open_time", "close_time"], drop=["ignore"],
                   ints=["open_time", "close_time", "count"]),
    "funding": dict(cols=["calc_time", "funding_interval_hours", "last_funding_rate"], ts="calc_time",
                    times=["calc_time"], drop=[], ints=["calc_time", "funding_interval_hours"]),
    "metrics": dict(cols=["create_time", "symbol", "sum_open_interest", "sum_open_interest_value",
                          "count_toptrader_long_short_ratio", "sum_toptrader_long_short_ratio",
                          "count_long_short_ratio", "sum_taker_long_short_vol_ratio"], ts="create_time",
                    times=["create_time"], drop=["symbol"], ints=["create_time"]),
    "aggtrades": dict(cols=["agg_trade_id", "price", "quantity", "first_trade_id", "last_trade_id",
                            "transact_time", "is_buyer_maker"], ts="transact_time", times=["transact_time"],
                      drop=[], ints=["agg_trade_id", "first_trade_id", "last_trade_id", "transact_time"]),
}
# dataset → kind, archive sub-path, file stem, grid step in ms (None = no fixed grid), has monthly zips, 1d path
DATASETS = {
    "klines_1m": dict(kind="klines", path="klines/{s}/1m", stem="{s}-1m", step=60_000, monthly=True,
                      day_path="klines/{s}/1d", day_stem="{s}-1d"),
    "markprice_1m": dict(kind="klines", path="markPriceKlines/{s}/1m", stem="{s}-1m", step=60_000, monthly=True,
                         day_path="markPriceKlines/{s}/1d", day_stem="{s}-1d"),
    "indexprice_1m": dict(kind="klines", path="indexPriceKlines/{s}/1m", stem="{s}-1m", step=60_000, monthly=True,
                          day_path="indexPriceKlines/{s}/1d", day_stem="{s}-1d"),
    "funding": dict(kind="funding", path="fundingRate/{s}", stem="{s}-fundingRate", step=None, monthly=True),
    "metrics": dict(kind="metrics", path="metrics/{s}", stem="{s}-metrics", step=300_000, monthly=False),
    "aggtrades": dict(kind="aggtrades", path="aggTrades/{s}", stem="{s}-aggTrades", step=None, monthly=True),
}
T1 = [(m, d) for m, d in [("spot", "klines_1m"), ("um", "klines_1m"), ("um", "funding"), ("um", "markprice_1m"),
                          ("um", "indexprice_1m"), ("um", "metrics")]]
TIERS = {
    1: [(m, d, s) for s in ("BTCUSDT", "ETHUSDT") for m, d in T1],
    2: [(m, d, s) for s in ("SOLUSDT", "BNBUSDT", "XRPUSDT")
        for m, d in [("spot", "klines_1m"), ("um", "klines_1m"), ("um", "funding")]],
    3: [("um", "aggtrades", s) for s in ("BTCUSDT", "ETHUSDT")],
}
# tier 4 = the top N perpetuals of ALL USD-M symbols by last month's quote volume (Boss, 13 Oct: top 50 of all
# symbols), built at run time; the ranking is saved to crypto/top_symbols.csv
ALL_SETS = [("um", "klines_1m"), ("um", "funding"), ("spot", "klines_1m")]
TOP_FILE = os.path.join(HERE, "crypto", "top_symbols.csv")
US_THRESHOLD = 10 ** 14   # ms epochs are ~1.7e12 today; microsecond epochs are ~1.7e15
MAX_GAPS_LISTED = 20


class ChecksumError(Exception):
    pass


# ---------------------------------------------------------------- network (replaceable in tests)

def http_get(url, retries=5):
    for i in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404 or i == retries - 1:
                raise
        except Exception:
            if i == retries - 1:
                raise
        time.sleep(2 ** i)


def http_download(url, dest, retries=5):
    for i in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=300) as r, open(dest, "wb") as f:
                shutil.copyfileobj(r, f, 1 << 20)
            return dest
        except urllib.error.HTTPError as e:
            if e.code == 404 or i == retries - 1:
                raise
        except Exception:
            if i == retries - 1:
                raise
        time.sleep(2 ** i)


def list_keys(prefix, get=None):
    """Every .zip key under prefix in the archive's S3 bucket (follows pagination)."""
    get = get or http_get
    keys, marker = [], ""
    while True:
        x = get(f"{LISTING}?prefix={prefix}&delimiter=/&marker={marker}").decode()
        k = re.findall(r"<Key>([^<]+)</Key>", x)
        keys += k
        if "<IsTruncated>true</IsTruncated>" not in x or not k:
            break
        marker = k[-1]
    return [k for k in keys if k.endswith(".zip")]


def list_symbols(market, dataset, get=None, usdt_only=True):
    """Every symbol folder the archive has for this market/dataset (monthly files); USDT pairs only by default."""
    get = get or http_get
    prefix = f"{MARKETS[market][0]}/monthly/{DATASETS[dataset]['path'].split('/')[0]}/"
    out, marker = [], ""
    while True:
        x = get(f"{LISTING}?prefix={prefix}&delimiter=/&marker={marker}").decode()
        p = [q for q in re.findall(r"<Prefix>([^<]+)</Prefix>", x) if q != prefix]
        out += p
        if "<IsTruncated>true</IsTruncated>" not in x or not p:
            break
        nm = re.findall(r"<NextMarker>([^<]+)</NextMarker>", x)
        marker = nm[0] if nm else p[-1]
    return sorted({q.rstrip("/").split("/")[-1] for q in out if not usdt_only or q.rstrip("/").endswith("USDT")})


def rank_symbols(month, get=None, download=None, workers=16, work=None):
    """[(symbol, quote_volume)] for every USD-M perpetual (no expiry suffix) with a 1d klines file for month,
    biggest first. Volume is the sum of the month's daily quote volume."""
    syms = [s for s in list_symbols("um", "klines_1m", get, usdt_only=False) if "_" not in s]
    tmp = tempfile.mkdtemp(dir=work, prefix="rank-")

    def one(s):
        key = f"data/futures/um/monthly/klines/{s}/1d/{s}-1d-{month}.zip"
        try:
            d = tempfile.mkdtemp(dir=tmp)
            z = fetch_verified(key, d, get, download)
            return s, float(read_zip(z, "klines")["quote_volume"].sum())
        except Exception:  # noqa: BLE001 — delisted or not yet listed that month
            return s, None
    try:
        with ThreadPoolExecutor(workers) as ex:
            got = [x for x in ex.map(one, syms) if x[1] is not None]
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return sorted(got, key=lambda x: -x[1])


def tier4(top, today, get=None, download=None, work=None, path=TOP_FILE):
    """Series for the top N symbols (ranked on the last full month), minus those already in tiers 1-3."""
    y, m = (today.year, today.month - 1) if today.month > 1 else (today.year - 1, 12)
    month = f"{y:04d}-{m:02d}"
    ranked = rank_symbols(month, get, download, work=work)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(f"# USD-M perpetuals ranked by summed daily quote volume in {month} (Binance archive 1d klines)\n")
        f.write("rank,symbol,quote_volume\n")
        f.writelines(f"{i},{s},{v:.0f}\n" for i, (s, v) in enumerate(ranked, 1))
    have = {s for t in (1, 2, 3) for s in TIERS[t]}
    return [(mk, d, s) for s, _ in ranked[:top] for mk, d in ALL_SETS if (mk, d, s) not in have]


def sha256_file(path, algo="sha256"):
    h = hashlib.new(algo)
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def fetch_verified(key, dest_dir, get=None, download=None):
    """Download key + key.CHECKSUM, verify sha256 (one retry), return the local path. Raises ChecksumError."""
    get, download = get or http_get, download or http_download
    want = get(ARCHIVE + key + ".CHECKSUM").decode().split()[0].lower()
    dest = os.path.join(dest_dir, os.path.basename(key))
    for _ in range(2):
        download(ARCHIVE + key, dest)
        got = sha256_file(dest)
        if got == want:
            return dest
    os.remove(dest)
    raise ChecksumError(f"{key}: sha256 {got} != CHECKSUM {want}")


# ---------------------------------------------------------------- planning

def month_of(key):
    m = re.search(r"-(\d{4})-(\d{2})(?:-(\d{2}))?\.zip$", key)
    return (f"{m.group(1)}-{m.group(2)}", m.group(3)) if m else (None, None)


def series_prefix(market, dataset, symbol, freq):
    spec = DATASETS[dataset]
    return f"{MARKETS[market][0]}/{freq}/{spec['path'].format(s=symbol)}/"


def plan_series(market, dataset, symbol, today, lister=None, since=None):
    """{month: [keys]} from the archive: monthly zips where they exist, daily zips for the other months.
    Only keys named {stem}-YYYY-MM.zip / {stem}-YYYY-MM-DD.zip count (the archive has a few stray part-*.zip)."""
    lister = lister or list_keys
    spec = DATASETS[dataset]
    stem = spec["stem"].format(s=symbol)
    pat_m = re.compile(re.escape(stem) + r"-\d{4}-\d{2}\.zip$")
    pat_d = re.compile(re.escape(stem) + r"-\d{4}-\d{2}-\d{2}\.zip$")
    plan = {}
    if spec["monthly"]:
        for k in lister(series_prefix(market, dataset, symbol, "monthly")):
            if pat_m.search(os.path.basename(k)):
                plan[month_of(k)[0]] = [k]
        last = max(plan) if plan else None
        # daily zips only for the months after the last monthly zip (normally the current month)
        first = max(last or today.strftime("%Y-%m"), since or "")       # never list daily zips before --since
        months, y, m = [], *map(int, first.split("-"))
        cur = today.strftime("%Y-%m")
        while True:
            ym = f"{y:04d}-{m:02d}"
            if ym > cur:
                break
            if ym not in plan:
                months.append(ym)
            y, m = (y + 1, 1) if m == 12 else (y, m + 1)
        if dataset == "funding":
            months = []    # the archive has no daily funding files
        daily = [k for ym in months for k in lister(series_prefix(market, dataset, symbol, "daily") + f"{stem}-{ym}")]
    else:
        daily = lister(series_prefix(market, dataset, symbol, "daily"))
    for k in sorted(daily):
        if pat_d.search(os.path.basename(k)):
            plan.setdefault(month_of(k)[0], []).append(k)
    return dict(sorted(plan.items()))


def drive_path(market, dataset, symbol, month):
    return f"{MARKETS[market][1]}/{dataset}/{symbol}/{month[:4]}/{symbol}-{dataset}-{month}.parquet"


def month_bounds(month):
    y, m = map(int, month.split("-"))
    start = dt.datetime(y, m, 1, tzinfo=dt.timezone.utc)
    end = start + dt.timedelta(days=calendar.monthrange(y, m)[1])
    return int(start.timestamp() * 1000), int(end.timestamp() * 1000)


def window_for(month, keys, first_ts, is_first, step):
    """[start, end) the month should cover: the whole month for a monthly zip, up to the end of the last daily zip
    otherwise; the series' first month starts at its first row (the listing date)."""
    start, end = month_bounds(month)
    days = [month_of(k)[1] for k in keys if month_of(k)[1]]
    if days and len(keys) == len(days):
        end = min(end, start + int(max(days)) * 86_400_000)
    if is_first and first_ts is not None:
        start = max(start, first_ts - (first_ts % step) if step else first_ts)
    return start, end


def complete_month(month, keys, today):
    """True when the month is over and its data is final (monthly zip, or every day present for daily-only sets)."""
    if month >= today.strftime("%Y-%m"):
        return False
    days = {month_of(k)[1] for k in keys if month_of(k)[1]}
    if not days:
        return True
    y, m = map(int, month.split("-"))
    return len(days) == calendar.monthrange(y, m)[1]


# ---------------------------------------------------------------- parsing + QA

def csv_dtypes(kind):
    spec = KINDS[kind]
    dtypes = {c: "float64" for c in spec["cols"]}
    for c in spec["ints"]:
        dtypes[c] = "int64"
    if kind == "metrics":
        dtypes.update(create_time="str", symbol="str")
    if kind == "aggtrades":
        dtypes["is_buyer_maker"] = "str"
    if kind == "klines":
        dtypes["ignore"] = "str"
    return dtypes


AGG_SCHEMA = pa.schema([("agg_trade_id", pa.int64()), ("price", pa.float64()), ("quantity", pa.float64()),
                        ("first_trade_id", pa.int64()), ("last_trade_id", pa.int64()),
                        ("transact_time", pa.int64()), ("is_buyer_maker", pa.bool_())])


def stream_aggtrades(keys, tmp, out, window, get=None, download=None, chunk=2_000_000):
    """aggTrades months are 0.4–0.9 GB zipped: read each zip in chunks and append to one Parquet file, checking
    order on the way (agg_trade_id must rise strictly, which also rules out duplicates). Nothing is re-sorted."""
    start, end = window
    qa = dict(raw_rows=0, ts_unit_fixed=0, outside_dropped=0, rows=0, id_not_increasing=0, time_decreasing=0,
              first_ts=None, last_ts=None, window=[start, end])
    prev_id = prev_t = None
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with pq.ParquetWriter(out, AGG_SCHEMA, compression="zstd") as w:
        for k in keys:
            z = fetch_verified(k, tmp, get, download)
            try:
                with zipfile.ZipFile(z) as zf:
                    name = [n for n in zf.namelist() if n.lower().endswith(".csv")][0]
                    with zf.open(name) as f:
                        head = f.peek(64)[:1]
                        if not head:
                            continue
                        for df in pd.read_csv(f, header=0 if not head.isdigit() else None, names=KINDS["aggtrades"]["cols"],
                                              dtype=csv_dtypes("aggtrades"), chunksize=chunk):
                            df["is_buyer_maker"] = df["is_buyer_maker"].str.lower().eq("true")
                            qa["raw_rows"] += len(df)
                            qa["ts_unit_fixed"] += fix_time_units(df, ["transact_time"])
                            inside = (df["transact_time"] >= start) & (df["transact_time"] < end)
                            qa["outside_dropped"] += int((~inside).sum())
                            df = df[inside]
                            if df.empty:
                                continue
                            ids, t = df["agg_trade_id"].to_numpy(), df["transact_time"].to_numpy()
                            qa["id_not_increasing"] += int((np.diff(ids) <= 0).sum()) + int(
                                prev_id is not None and ids[0] <= prev_id)
                            qa["time_decreasing"] += int((np.diff(t) < 0).sum()) + int(prev_t is not None and t[0] < prev_t)
                            prev_id, prev_t = int(ids[-1]), int(t[-1])
                            qa["first_ts"] = int(t[0]) if qa["first_ts"] is None else qa["first_ts"]
                            qa["last_ts"] = int(t[-1])
                            qa["rows"] += len(df)
                            w.write_table(pa.Table.from_pandas(df, schema=AGG_SCHEMA, preserve_index=False))
            finally:
                os.remove(z)
    qa["dup_exact"] = qa["dup_conflict"] = 0
    qa["ok"] = qa["id_not_increasing"] == 0 and qa["time_decreasing"] == 0
    return qa


def read_zip(path, kind):
    """DataFrame of the CSV inside a Binance zip, with or without a header row, typed per KINDS."""
    spec = KINDS[kind]
    with zipfile.ZipFile(path) as z:
        name = [n for n in z.namelist() if n.lower().endswith(".csv")][0]
        with z.open(name) as f:
            raw = f.read()
    body = raw.strip()
    header = not body[:1].isdigit()
    if not body or (header and b"\n" not in body):
        return pd.DataFrame({c: pd.Series(dtype="int64" if c in spec["ints"] else "float64")
                             for c in spec["cols"] if c not in spec["drop"]})
    df = pd.read_csv(io.BytesIO(raw), header=0 if header else None, names=spec["cols"], dtype=csv_dtypes(kind))
    if kind == "metrics":
        df["create_time"] = pd.to_datetime(df["create_time"], utc=True).dt.as_unit("ms").astype("int64")
    if kind == "aggtrades":
        df["is_buyer_maker"] = df["is_buyer_maker"].str.lower().eq("true")
    return df.drop(columns=spec["drop"])


def fix_time_units(df, cols):
    """Microsecond epochs (Binance spot files from 2025) → milliseconds. Returns the number of rows fixed."""
    n = 0
    for c in cols:
        us = df[c] > US_THRESHOLD
        n = max(n, int(us.sum()))
        if us.any():
            df.loc[us, c] = df.loc[us, c] // 1000
    return n


def gap_ranges(missing, step):
    """Consecutive missing grid points → [(first, last), ...]."""
    if len(missing) == 0:
        return []
    breaks = np.where(np.diff(missing) != step)[0]
    starts = np.r_[missing[0], missing[breaks + 1]]
    ends = np.r_[missing[breaks], missing[-1]]
    return list(zip(starts.tolist(), ends.tolist()))


def iso(ms):
    return dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc).strftime("%Y-%m-%dT%H:%MZ")


def clean_and_check(df, kind, step, window):
    """Sort, drop duplicate timestamps and rows outside the window; return (df, qa). Gaps are reported, not filled."""
    spec = KINDS[kind]
    ts = spec["ts"]
    qa = dict(raw_rows=len(df))
    qa["ts_unit_fixed"] = fix_time_units(df, spec["times"])
    qa["resorted"] = bool(not df[ts].is_monotonic_increasing)
    if qa["resorted"]:
        df = df.sort_values(ts, kind="stable")
    n = len(df)
    df = df.drop_duplicates()
    qa["dup_exact"] = n - len(df)
    n = len(df)
    df = df.drop_duplicates(subset=[ts], keep="first")
    qa["dup_conflict"] = n - len(df)
    start, end = window
    inside = (df[ts] >= start) & (df[ts] < end)
    qa["outside_dropped"] = int((~inside).sum())
    df = df[inside].reset_index(drop=True)
    qa["rows"] = len(df)
    qa["first_ts"] = int(df[ts].iloc[0]) if len(df) else None
    qa["last_ts"] = int(df[ts].iloc[-1]) if len(df) else None
    qa["window"] = [start, end]
    if step:
        t = df[ts].to_numpy()
        qa["off_grid"] = int((t % step != 0).sum())
        grid = np.arange(start, end, step, dtype=np.int64)
        missing = np.setdiff1d(grid, t, assume_unique=True)
        gaps = gap_ranges(missing, step)
        qa["expected"] = len(grid)
        qa["missing"] = len(missing)
        qa["gap_count"] = len(gaps)
        qa["gaps"] = [f"{iso(a)}/{iso(b)} ({(b - a) // step + 1})" for a, b in gaps[:MAX_GAPS_LISTED]]
    if kind == "klines":
        o, h, l, c = (df[k] for k in ("open", "high", "low", "close"))
        bad = (h < np.maximum(o, c)) | (l > np.minimum(o, c)) | (l <= 0) | (h < l)
        qa["ohlc_bad"] = int(bad.sum())
    if kind == "funding":
        qa["rate_abs_gt_1pct"] = int((df["last_funding_rate"].abs() > 0.01).sum())
    qa["ok"] = qa["dup_conflict"] == 0 and qa.get("ohlc_bad", 0) == 0 and qa.get("off_grid", 0) == 0
    return df, qa


def daily_check(df, market, dataset, symbol, month, keys, work, get=None, download=None):
    """Compare the 1m close of one day (the 15th, or the last day present) with the close in the 1d klines file."""
    spec = DATASETS[dataset]
    if "day_path" not in spec or df.empty:
        return None
    last_mins = df[df["open_time"] % 86_400_000 == 86_340_000]          # the 23:59 candle of each day
    if last_mins.empty:
        return dict(date=None, ok=False, error="no 23:59 candle in the month")
    days = (last_mins["open_time"] // 86_400_000).tolist()
    want = (month_bounds(month)[0] // 86_400_000) + 14                    # the 15th, else the last full day
    day = dt.date(1970, 1, 1) + dt.timedelta(days=want if want in days else days[-1])
    root, sub = MARKETS[market][0], spec["day_path"].format(s=symbol)
    stem = spec["day_stem"].format(s=symbol)
    monthly = len(keys) == 1 and month_of(keys[0])[1] is None
    key = (f"{root}/monthly/{sub}/{stem}-{month}.zip" if monthly else
           f"{root}/daily/{sub}/{stem}-{day.isoformat()}.zip")
    d0 = int(dt.datetime(day.year, day.month, day.day, tzinfo=dt.timezone.utc).timestamp() * 1000)
    last_min = df[df["open_time"] == d0 + 86_400_000 - 60_000]
    out = dict(date=day.isoformat(), close_1m=float(last_min["close"].iloc[0]) if len(last_min) else None)
    try:
        z = fetch_verified(key, work, get, download)
        d = read_zip(z, "klines")
        os.remove(z)
        fix_time_units(d, ["open_time", "close_time"])
        row = d[d["open_time"] == d0]
        out["close_1d"] = float(row["close"].iloc[0]) if len(row) else None
    except Exception as e:  # noqa: BLE001 — a missing 1d file is reported, not fatal
        out["close_1d"], out["error"] = None, str(e)[:200]
    out["ok"] = (out["close_1m"] is not None and out["close_1d"] is not None
                 and abs(out["close_1m"] - out["close_1d"]) <= 1e-9 * max(1.0, abs(out["close_1d"])))
    return out


def write_parquet(df, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pq.write_table(pa.Table.from_pandas(df, preserve_index=False), path, compression="zstd")


def build_month(market, dataset, symbol, month, keys, work, today, is_first=False, check_daily=False,
                get=None, download=None):
    """Download, verify, parse, clean, QA and write one month. Returns its catalog entry."""
    spec = DATASETS[dataset]
    kind, step = spec["kind"], spec["step"]
    tmp = tempfile.mkdtemp(dir=work, prefix="zip-")
    rel = drive_path(market, dataset, symbol, month)
    out = os.path.join(work, rel)
    try:
        if kind == "aggtrades":
            qa = stream_aggtrades(keys, tmp, out, window_for(month, keys, None, False, None), get, download)
            return entry(market, dataset, symbol, month, keys, today, out, rel, qa)

        def one(k):
            z = fetch_verified(k, tmp, get, download)
            try:
                return read_zip(z, kind)
            finally:
                os.remove(z)

        with ThreadPoolExecutor(4) as ex:                    # daily-built months have ~30 small zips
            frames = list(ex.map(one, keys))
        df = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame(columns=KINDS[kind]["cols"])
        ts = KINDS[kind]["ts"]
        fixed = fix_time_units(df, KINDS[kind]["times"])
        first = int(df[ts].min()) if len(df) else None
        df, qa = clean_and_check(df, kind, step, window_for(month, keys, first, is_first, step))
        qa["ts_unit_fixed"] += fixed
        write_parquet(df, out)
        if check_daily:
            qa["daily_check"] = daily_check(df, market, dataset, symbol, month, keys, tmp, get, download)
    except BaseException:
        if os.path.exists(out):                              # never leave a half-written month behind
            os.remove(out)
        raise
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return entry(market, dataset, symbol, month, keys, today, out, rel, qa)


def entry(market, dataset, symbol, month, keys, today, out, rel, qa):
    return dict(path=rel, market=MARKETS[market][1], dataset=dataset, symbol=symbol, month=month,
                complete=complete_month(month, keys, today), sources=[os.path.basename(k) for k in keys],
                sha256=sha256_file(out), md5=sha256_file(out, "md5"), bytes=os.path.getsize(out),
                built=today.isoformat(), **qa)


# ---------------------------------------------------------------- catalog

CSV_FIELDS = ["path", "market", "dataset", "symbol", "month", "complete", "rows", "expected", "missing",
              "gap_count", "first_ts", "last_ts", "first_utc", "last_utc", "dup_exact", "dup_conflict",
              "outside_dropped", "ohlc_bad", "off_grid", "ts_unit_fixed", "daily_check_ok", "n_sources", "bytes",
              "sha256", "md5", "built"]


def load_catalog(path=CATALOG, remote=None, run=subprocess.run):
    """Catalog from disk; when it is missing locally, the copy on Drive (so a fresh box can resume)."""
    if not os.path.exists(path) and remote:
        rclone(["copyto", f"{remote}/{os.path.basename(path)}", path], run)
    if os.path.exists(path):
        c = json.load(open(path))
        return {e["path"]: e for e in c.get("files", [])}, {f["path"]: f for f in c.get("failures", [])}
    return {}, {}


def save_catalog(files, failures, path=CATALOG, remote=REMOTE):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    rows = [files[k] for k in sorted(files)]
    doc = dict(remote=remote, layout="{market}/{dataset}/{symbol}/{year}/{symbol}-{dataset}-{YYYY-MM}.parquet",
               times="UTC epoch milliseconds (int64)", source=ARCHIVE,
               totals=dict(files=len(rows), rows=sum(e["rows"] for e in rows), bytes=sum(e["bytes"] for e in rows)),
               files=rows, failures=[failures[k] for k in sorted(failures)])
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(doc, f, indent=1, sort_keys=True)
        f.write("\n")
    os.replace(tmp, path)
    with open(path[:-5] + "_series.csv", "w", newline="") as f:
        f.write(series_csv(rows))
    with open(path[:-5] + ".csv", "w", newline="") as f:
        w = csv.DictWriter(f, CSV_FIELDS)
        w.writeheader()
        for e in rows:
            dc = e.get("daily_check")
            w.writerow({**{k: e.get(k) for k in CSV_FIELDS}, "n_sources": len(e["sources"]),
                        "first_utc": iso(e["first_ts"]) if e.get("first_ts") is not None else "",
                        "last_utc": iso(e["last_ts"]) if e.get("last_ts") is not None else "",
                        "daily_check_ok": "" if not dc else dc["ok"]})


def series_summary(rows):
    """One row per series: months, first/last month, rows, bytes, missing slots, bad rows, daily checks."""
    g = {}
    for e in rows:
        k = (e["market"], e["dataset"], e["symbol"])
        x = g.setdefault(k, dict(months=[], rows=0, bytes=0, missing=0, bad=0, dc_ok=0, dc=0, incomplete=0))
        x["months"].append(e["month"])
        x["rows"] += e["rows"]
        x["bytes"] += e["bytes"]
        x["missing"] += e.get("missing") or 0
        x["bad"] += (e.get("ohlc_bad") or 0) + (e.get("dup_conflict") or 0) + (e.get("off_grid") or 0)
        x["incomplete"] += not e["complete"]
        if e.get("daily_check"):
            x["dc"] += 1
            x["dc_ok"] += bool(e["daily_check"]["ok"])
    return g


def series_csv(rows):
    lines = ["market,dataset,symbol,files,first_month,last_month,rows,bytes,missing,bad,incomplete,daily_ok,daily_checked"]
    for k, x in sorted(series_summary(rows).items()):
        lines.append(",".join(map(str, [*k, len(x["months"]), min(x["months"]), max(x["months"]), x["rows"],
                                        x["bytes"], x["missing"], x["bad"], x["incomplete"], x["dc_ok"], x["dc"]])))
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- Drive (rclone)

def rclone(args, run=subprocess.run, capture=False):
    cmd = ["rclone", *args]
    print("$", " ".join(cmd), flush=True)
    return run(cmd, capture_output=True, text=True) if capture else run(cmd)


def drive_listing(remote, folder, run=subprocess.run):
    """{relative path under remote: (size, md5)} for files under remote/folder; {} when the folder does not exist."""
    r = rclone(["lsjson", "-R", "--files-only", "--hash", "--hash-type", "md5", f"{remote}/{folder}"], run, True)
    if r.returncode != 0:
        return {}
    return {f"{folder}/{f['Path']}": (f["Size"], (f.get("Hashes") or {}).get("md5")) for f in json.loads(r.stdout)}


def upload_batch(work, remote, rels, run=subprocess.run):
    """Copy the files (paths relative to work) to remote with --checksum, then `rclone check --one-way` them."""
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as t:
        t.write("".join(r + "\n" for r in rels))
    try:
        sel = ["--files-from", t.name]
        if rclone(["copy", work, remote, *sel, "--checksum", "--transfers", "8", "--retries", "5"], run).returncode:
            return False
        return rclone(["check", work, remote, *sel, "--one-way"], run).returncode == 0
    finally:
        os.remove(t.name)


def publish_meta(remote, run=subprocess.run, catalog=CATALOG):
    ok = True
    for f in (catalog, catalog[:-5] + ".csv", catalog[:-5] + "_series.csv", README):
        if os.path.exists(f):
            ok &= rclone(["copyto", f, f"{remote}/{os.path.basename(f)}", "--checksum"], run).returncode == 0
    return ok


# ---------------------------------------------------------------- driver

def todo_months(plan, market, dataset, symbol, files, on_drive):
    """Months to (re)build: not in the catalog, sources changed, or Drive lacks the same md5/size."""
    out = []
    for month, keys in plan.items():
        rel = drive_path(market, dataset, symbol, month)
        e = files.get(rel)
        if e and e["sources"] == [os.path.basename(k) for k in keys] and on_drive.get(rel) == (e["bytes"], e["md5"]):
            continue
        out.append(month)
    return out


class Book:
    """Shared run state for the series threads: one lock around the catalog, throttled saves and Drive copies."""

    def __init__(self, a, files, failures, run=subprocess.run, save_every=60, publish_every=600):
        self.a, self.files, self.failures, self.run = a, files, failures, run
        self.lock = threading.Lock()
        self.saved = self.published = 0.0
        self.save_every, self.publish_every = save_every, publish_every

    def flush(self, force=False):
        with self.lock:
            now = time.time()
            if force or now - self.saved >= self.save_every:
                save_catalog(self.files, self.failures, self.a.catalog, self.a.remote)
                self.saved = now
            if force or now - self.published >= self.publish_every:
                publish_meta(self.a.remote, self.run, self.a.catalog)
                self.published = now


def run_series(market, dataset, symbol, a, files, failures, today, run=subprocess.run, lister=None,
               get=None, download=None, book=None):
    book = book or Book(a, files, failures, run, 0, 0)
    plan = plan_series(market, dataset, symbol, today, lister, a.since)
    first_month = min(plan) if plan else None                            # the listing month, before --since
    if a.since:
        plan = {m: k for m, k in plan.items() if m >= a.since}
    if not plan:
        print(f"{market}/{dataset}/{symbol}: nothing in the archive", flush=True)
        return
    folder = f"{MARKETS[market][1]}/{dataset}/{symbol}"
    on_drive = {} if a.dry_run else drive_listing(a.remote, folder, run)
    with book.lock:
        todo = todo_months(plan, market, dataset, symbol, files, on_drive)
    if a.limit:
        todo = todo[:a.limit]
    years = {}
    for m in plan:
        years.setdefault(m[:4], m)          # the first month of each year carries the daily-close spot check
    print(f"{market}/{dataset}/{symbol}: {len(plan)} months {min(plan)}..{max(plan)}, "
          f"{len(todo)} to build", flush=True)
    if a.dry_run or not todo:
        return
    batch = 1 if dataset == "aggtrades" else a.batch
    for i in range(0, len(todo), batch):
        months = todo[i:i + batch]
        built = []

        def job(m):
            return build_month(market, dataset, symbol, m, plan[m], a.work, today, is_first=(m == first_month),
                               check_daily=(years[m[:4]] == m), get=get, download=download)

        with ThreadPoolExecutor(a.workers) as ex:
            futs = {m: ex.submit(job, m) for m in months}
            for m, f in futs.items():
                rel = drive_path(market, dataset, symbol, m)
                try:
                    built.append(f.result())
                except Exception as e:  # noqa: BLE001 — record and move on; a re-run retries it
                    with book.lock:
                        failures[rel] = dict(path=rel, error=f"{type(e).__name__}: {str(e)[:300]}",
                                             when=today.isoformat())
                    print(f"FAILED {rel}: {e}", flush=True)
        rels = [e["path"] for e in built]
        ok = bool(rels) and upload_batch(a.work, a.remote, rels, run)
        with book.lock:
            if ok:
                for e in built:
                    files[e["path"]] = e
                    failures.pop(e["path"], None)
            else:
                for r in rels:
                    failures[r] = dict(path=r, error="upload or rclone check failed", when=today.isoformat())
        for r in rels:
            p = os.path.join(a.work, r)
            if os.path.exists(p):
                os.remove(p)
        book.flush()


def status(files, failures):
    groups = {}
    for e in files.values():
        g = groups.setdefault((e["market"], e["dataset"], e["symbol"]), dict(n=0, rows=0, bytes=0, months=[],
                                                                             missing=0, bad=0, dc=[]))
        g["n"] += 1
        g["rows"] += e["rows"]
        g["bytes"] += e["bytes"]
        g["months"].append(e["month"])
        g["missing"] += e.get("missing", 0) or 0
        g["bad"] += (e.get("ohlc_bad", 0) or 0) + (e.get("dup_conflict", 0) or 0)
        if e.get("daily_check"):
            g["dc"].append(e["daily_check"]["ok"])
    lines = [f"{'series':42} {'months':>6} {'from':>8} {'to':>8} {'rows':>12} {'MB':>8} {'missing':>9} {'bad':>5} daily-close"]
    for k in sorted(groups):
        g = groups[k]
        lines.append(f"{'/'.join(k):42} {g['n']:>6} {min(g['months']):>8} {max(g['months']):>8} {g['rows']:>12,} "
                     f"{g['bytes'] / 1e6:>8.1f} {g['missing']:>9,} {g['bad']:>5} {sum(g['dc'])}/{len(g['dc'])} ok")
    tot = sum(e["bytes"] for e in files.values())
    lines.append(f"total: {len(files)} files, {sum(e['rows'] for e in files.values()):,} rows, {tot / 1e9:.2f} GB; "
                 f"failures: {len(failures)}")
    lines += [f"  FAILED {f['path']}: {f['error']}" for f in failures.values()]
    return "\n".join(lines)


def main(argv=None, run=subprocess.run, lister=None, get=None, download=None, today=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("cmd", choices=["run", "status"])
    ap.add_argument("--tier", type=int, action="append", default=[], help="1, 2, 3, or 4 = the --top symbols")
    ap.add_argument("--top", type=int, default=50, help="tier 4: how many symbols, by last month's futures volume")
    ap.add_argument("--series", action="append", default=[], help="market/dataset/SYMBOL, e.g. um/funding/BTCUSDT")
    ap.add_argument("--since", help="only months >= YYYY-MM (default: the last 3 years, Boss 13 Oct; "
                                    "tier 3 aggTrades: the last 12 months)")
    ap.add_argument("--limit", type=int, default=0, help="at most this many months per series (for trials)")
    ap.add_argument("--remote", default=REMOTE)
    ap.add_argument("--work", default=os.path.join(tempfile.gettempdir(), "crypto-lake"))
    ap.add_argument("--catalog", default=CATALOG)
    ap.add_argument("--workers", type=int, default=6, help="month threads per series")
    ap.add_argument("--parallel", type=int, default=4, help="series built at the same time (threads)")
    ap.add_argument("--batch", type=int, default=24, help="months built locally before each upload+check")
    ap.add_argument("--dry-run", action="store_true", help="plan only: no downloads, no uploads")
    a = ap.parse_args(argv)
    files, failures = load_catalog(a.catalog, None if a.cmd == "status" or a.dry_run else a.remote, run)
    if a.cmd == "status":
        print(status(files, failures))
        return 0
    today = today or dt.datetime.now(dt.timezone.utc).date()
    a.since = a.since or f"{today.year - 3:04d}-{today.month:02d}"     # Boss, 13 Oct: "only last 3 yrs"
    os.makedirs(a.work, exist_ok=True)
    series = [tuple(s.split("/")) for s in a.series]
    for t in a.tier:
        series += tier4(a.top, today, get, download, a.work) if t == 4 else TIERS[t]
    book = Book(a, files, failures, run)

    def one(sr):
        market, dataset, symbol = sr
        b = argparse.Namespace(**vars(a))
        if dataset == "aggtrades":
            b.since = max(b.since, f"{today.year - 1:04d}-{today.month:02d}")   # tier 3: the recent 12 months
        try:
            run_series(market, dataset, symbol, b, files, failures, today, run, lister, get, download, book)
            with book.lock:
                failures.pop("/".join(sr), None)
        except Exception as e:  # noqa: BLE001 — e.g. a listing error; the next run retries the series
            with book.lock:
                failures["/".join(sr)] = dict(path="/".join(sr), error=f"{type(e).__name__}: {str(e)[:300]}",
                                              when=today.isoformat())
            print(f"FAILED series {'/'.join(sr)}: {e}", flush=True)

    with ThreadPoolExecutor(max(1, a.parallel)) as ex:
        list(ex.map(one, series))
    if not a.dry_run:
        book.flush(force=True)
    print(status(files, failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
