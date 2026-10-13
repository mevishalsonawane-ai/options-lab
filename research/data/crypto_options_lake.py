#!/usr/bin/env python3
"""Crypto OPTIONS data lake: Deribit public history + Delta Exchange India → Parquet (zstd) → Google Drive. (13 Oct 2026)

Boss, 13 Oct: "Fetch options data too." Boss will BUY BTC/ETH options only, always with a stop-loss and a profit lock.
This tool only collects data; nothing here trades.

  python -I research/data/crypto_options_lake.py run                    # everything, in the priority order below
  python -I research/data/crypto_options_lake.py trades --currency BTC --since 2024-10   # one coin, a month range
  python -I research/data/crypto_options_lake.py instruments | dvol | index             # Deribit reference data
  python -I research/data/crypto_options_lake.py delta-products | delta-candles         # Delta Exchange India
  python -I research/data/crypto_options_lake.py snapshot               # the live Delta chain, once (run it nightly)
  python -I research/data/crypto_options_lake.py crosscheck --day 2026-10-12            # Delta vs Deribit IV, one day
  python -I research/data/crypto_options_lake.py status                 # what the catalog says is on Drive

Priority for `run` (Boss's list): Deribit BTC trades, newest 2 years first → ETH the same → Delta India products, fees
and candles → older Deribit history back to the start (BTC Nov 2016, ETH 2019). Every step skips files already on
Drive with the same md5, so a re-run continues where the last one stopped.

Flow per file: fetch (paced, backing off on HTTP 429 / Deribit 10028) → normalise → de-duplicate (trade_id) → sort by
time → QA → Parquet (zstd) → `rclone copyto` + `rclone check` → catalog row → delete the local file. Only a few files
are on the local disk at any time. A Deribit month in progress is checkpointed every few pages (parts + cursor.json in
the work folder), so a crash on the same box resumes from the last timestamp, not the start of the month.

Layout on Drive (REMOTE = gdrive:IraAlgo-data-crypto, the same folder as the Binance lake from claude/crypto-lake):
  options/deribit/trades/{BTC|ETH}/{YYYY}/{YYYY-MM}.parquet       every option trade (one month per file)
  options/deribit/instruments/{BTC|ETH}.parquet                   every option instrument, expired ones included
  options/deribit/dvol/{BTC|ETH}.parquet                          DVOL 1-minute candles
  options/deribit/index/{btc_usd|eth_usd}-delivery.parquet        daily 08:00 UTC settlement (delivery) index prices
  options/delta_india/products.parquet                            contract specs, live + expired
  options/delta_india/fees/fees-{YYYY-MM-DD}.json                 fee schedule as fetched that day
  options/delta_india/candles/{BTC|ETH}/{YYYY}/{YYYY-MM-DD}.parquet   1m candles of every option expiring that day
  options/delta_india/snapshots/{YYYY}/{YYYY-MM-DD}/{HHMMSS}.parquet  live chain snapshots (bid/ask, mark, IV, OI, Greeks)
  options/options_catalog.json, options/options_catalog.csv, options/README.md
All times are UTC epoch milliseconds (int64). Raw data never goes into git; the catalog does
(research/data/crypto/options_catalog.*) and is the resume state.
"""
from __future__ import annotations

import argparse, calendar, csv, datetime as dt, hashlib, http.client, json, math, os, re, shutil, subprocess, sys, tempfile
import threading, time, urllib.error, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq

DERIBIT = "https://history.deribit.com/api/v2/public"     # full trade + instrument history, no key
DERIBIT_LIVE = "https://www.deribit.com/api/v2/public"     # DVOL, delivery prices, live instruments
DELTA = "https://api.india.delta.exchange/v2"
REMOTE = "gdrive:IraAlgo-data-crypto"
ROOT = "options"
HERE = os.path.dirname(os.path.abspath(__file__))
CATALOG = os.path.join(HERE, "crypto", "options_catalog.json")
README = os.path.join(HERE, "crypto", "OPTIONS_README.md")
FEES_COPY = os.path.join(HERE, "crypto", "delta_india_fees.json")
CROSSCHECK = os.path.join(HERE, "crypto", "options_crosscheck.json")
COINS = ("BTC", "ETH")
FIRST_MONTH = {"BTC": "2016-11", "ETH": "2019-03"}          # first month with option trades (checked 13 Oct 2026)
DAY_MS = 86_400_000
MONTHS = {m: i for i, m in enumerate(["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV",
                                      "DEC"], 1)}


class ApiError(Exception):
    pass


# ---------------------------------------------------------------- network (replaceable in tests)

class Http:
    """GET → JSON, paced per host (min seconds between request starts), with back-off on 429 / Deribit 10028 / 5xx.
    Thread-safe: the pacing lock is shared by every thread using this host."""

    def __init__(self, min_interval=0.25, retries=8, timeout=90, opener=None, sleep=time.sleep):
        self.min_interval, self.retries, self.timeout = min_interval, retries, timeout
        self.opener = opener or (lambda url, t: urllib.request.urlopen(url, timeout=t).read())
        self.sleep = sleep
        self.lock = threading.Lock()
        self.next_at = 0.0
        self.calls = self.backoffs = 0

    def _pace(self):
        with self.lock:
            now = time.monotonic()
            wait = self.next_at - now
            self.next_at = max(now, self.next_at) + self.min_interval
        if wait > 0:
            self.sleep(wait)

    def __call__(self, url, params=None):
        q = url + ("?" + urllib.parse.urlencode(params) if params else "")
        for i in range(self.retries):
            self._pace()
            self.calls += 1
            try:
                body = json.loads(self.opener(q, self.timeout))
            except urllib.error.HTTPError as e:
                if e.code in (429, 500, 502, 503, 504) and i < self.retries - 1:
                    self.backoffs += 1
                    self.sleep(min(60, 2 ** i))
                    continue
                try:
                    detail = e.read()[:300].decode("utf-8", "replace")
                except Exception:  # noqa: BLE001
                    detail = ""
                raise ApiError(f"HTTP {e.code} {q[:160]} {detail}") from e
            except (urllib.error.URLError, http.client.HTTPException, OSError, json.JSONDecodeError) as e:
                if i < self.retries - 1:
                    self.sleep(min(60, 2 ** i))
                    continue
                raise ApiError(f"{type(e).__name__} {q[:160]}") from e
            err = body.get("error") if isinstance(body, dict) else None
            if err:
                code = err.get("code") if isinstance(err, dict) else None
                if code in (10028, 10040, 11098, "too_many_requests") and i < self.retries - 1:
                    self.backoffs += 1                     # Deribit "too_many_requests": wait and retry
                    self.sleep(min(60, 2 ** (i + 1)))
                    continue
                raise ApiError(f"API error {err} for {q[:160]}")
            return body
        raise ApiError(f"gave up after {self.retries} tries: {q[:160]}")


# ---------------------------------------------------------------- names

def parse_deribit_name(name):
    """'BTC-27DEC24-100000-C' → dict(underlying, expiry_ts (08:00 UTC), strike, option_type). Strikes may use 'd' for
    a decimal point (e.g. 'XRP_USDC-…-0d625-C'). Returns None for anything that is not an option name."""
    m = re.fullmatch(r"([A-Z0-9_]+)-(\d{1,2})([A-Z]{3})(\d{2})-([0-9]+(?:d[0-9]+)?(?:\.[0-9]+)?)-([CP])", name or "")
    if not m or m.group(3) not in MONTHS:
        return None
    try:
        exp = dt.datetime(2000 + int(m.group(4)), MONTHS[m.group(3)], int(m.group(2)), 8, tzinfo=dt.timezone.utc)
    except ValueError:
        return None
    return dict(underlying=m.group(1), expiry_ts=int(exp.timestamp() * 1000),
                strike=float(m.group(5).replace("d", ".")), option_type=m.group(6))


def parse_delta_symbol(sym, settle_hour_utc=12):
    """'C-BTC-85400-131026' → dict(option_type, underlying, strike, expiry_ts). Delta India options expire at
    17:30 IST = 12:00 UTC (the product's settlement_time is the authority when it is known)."""
    m = re.fullmatch(r"([CP])-([A-Z0-9]+)-([0-9]+(?:\.[0-9]+)?)-(\d{2})(\d{2})(\d{2})", sym or "")
    if not m:
        return None
    try:
        exp = dt.datetime(2000 + int(m.group(6)), int(m.group(5)), int(m.group(4)), settle_hour_utc,
                          tzinfo=dt.timezone.utc)
    except ValueError:
        return None
    return dict(option_type=m.group(1), underlying=m.group(2), strike=float(m.group(3)),
                expiry_ts=int(exp.timestamp() * 1000))


def iso_ms(text):
    """'2026-10-13T12:00:00Z' → epoch ms (None stays None)."""
    if not text:
        return None
    return int(dt.datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp() * 1000)


def utc(ms, fmt="%Y-%m-%dT%H:%MZ"):
    return dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc).strftime(fmt) if ms is not None else ""


def month_bounds(month):
    y, m = map(int, month.split("-"))
    start = dt.datetime(y, m, 1, tzinfo=dt.timezone.utc)
    end = start + dt.timedelta(days=calendar.monthrange(y, m)[1])
    return int(start.timestamp() * 1000), int(end.timestamp() * 1000)


def month_range(first, last):
    """Inclusive list of 'YYYY-MM' from first to last."""
    out, (y, m) = [], map(int, first.split("-"))
    while f"{y:04d}-{m:02d}" <= last:
        out.append(f"{y:04d}-{m:02d}")
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return out


# ---------------------------------------------------------------- Deribit trades

TRADE_SCHEMA = pa.schema([
    ("timestamp", pa.int64()), ("trade_id", pa.string()), ("trade_seq", pa.int64()),
    ("instrument_name", pa.string()), ("underlying", pa.string()), ("expiry_ts", pa.int64()),
    ("strike", pa.float64()), ("option_type", pa.string()),
    ("price", pa.float64()), ("mark_price", pa.float64()), ("iv", pa.float64()), ("index_price", pa.float64()),
    ("amount", pa.float64()), ("contracts", pa.float64()), ("direction", pa.string()),
    ("tick_direction", pa.int8()), ("liquidation", pa.string()), ("block_trade_id", pa.string()),
    ("block_trade_leg_count", pa.int32()), ("block_rfq_id", pa.int64()), ("combo_id", pa.string()),
    ("combo_trade_id", pa.string()), ("extra", pa.string()),
])
_API_FIELDS = {f.name for f in TRADE_SCHEMA} - {"underlying", "expiry_ts", "strike", "option_type", "extra"}


def trades_table(trades):
    """API trade dicts → Arrow table in TRADE_SCHEMA. Every API field is kept; fields the schema does not know yet go
    into `extra` as JSON so nothing is lost if Deribit adds one."""
    cols = {f.name: [] for f in TRADE_SCHEMA}
    for t in trades:
        p = parse_deribit_name(t.get("instrument_name")) or {}
        for k in _API_FIELDS:
            v = t.get(k)
            if k in ("trade_id", "block_trade_id", "combo_id", "combo_trade_id", "liquidation") and v is not None:
                v = str(v)
            cols[k].append(v)
        for k in ("underlying", "expiry_ts", "strike", "option_type"):
            cols[k].append(p.get(k))
        rest = {k: v for k, v in t.items() if k not in _API_FIELDS}
        cols["extra"].append(json.dumps(rest, sort_keys=True) if rest else None)
    return pa.table({f.name: pa.array(cols[f.name], f.type) for f in TRADE_SCHEMA}, schema=TRADE_SCHEMA)


def page_trades(get, currency, start, end, count=10_000, cursor=None, boundary_ids=()):
    """Yield (new_trades, cursor, boundary_ids, saturated) page by page for trades with start <= timestamp < end.

    Paging is by time: the next page starts AT the last timestamp seen (inclusive), and trades already seen at that
    millisecond are skipped by trade_id, so nothing between pages is lost or doubled. A page that is entirely one
    millisecond (never seen in practice with count=10000) cannot move on by time; the cursor then steps 1 ms and the
    page is counted as `saturated` in the QA so the gap is visible."""
    cursor = start if cursor is None else cursor
    seen = set(boundary_ids)
    while cursor < end:
        r = get(f"{DERIBIT}/get_last_trades_by_currency_and_time",
                dict(currency=currency, kind="option", start_timestamp=cursor, end_timestamp=end - 1, count=count,
                     sorting="asc", include_old="true"))["result"]
        trades = [t for t in r.get("trades", []) if start <= t["timestamp"] < end]
        if not trades:
            return
        new = [t for t in trades if str(t["trade_id"]) not in seen]
        last = trades[-1]["timestamp"]
        saturated = bool(r.get("has_more")) and last == cursor
        if saturated:
            nxt, ids = cursor + 1, set()
        else:
            nxt = last
            ids = {str(t["trade_id"]) for t in trades if t["timestamp"] == last}
            if last == cursor:
                ids |= seen
        yield new, nxt, sorted(ids), saturated
        if not r.get("has_more"):
            return
        cursor, seen = nxt, ids


def finish_trades(table):
    """Sort by time (stable, so the API order is kept within a millisecond) and drop repeated trade_ids."""
    n = table.num_rows
    table = table.append_column("_i", pa.array(range(n), pa.int64()))
    table = table.sort_by([("timestamp", "ascending"), ("_i", "ascending")]).drop_columns(["_i"])
    dup = pd.Series(table.column("trade_id").to_pylist()).duplicated(keep="first").to_numpy()
    if dup.any():
        table = table.filter(pa.array(~dup))
    return table, int(dup.sum())


def trades_qa(table, window):
    """Per-month checks: counts, duplicate ids, order, IV in (0, 500], prices against their no-arbitrage bound, and
    days with no trades.

    Price bound (prices are in BTC/ETH): a call is worth less than the coin, so its price must be below 1 coin; a put
    is worth at most its strike in USD, so price × index must not exceed the strike (2% slack for the index moving
    within the trade's millisecond and for tick rounding). A deep in-the-money put CAN cost more than 1 coin (e.g. a
    200,000 put with BTC at 83,000 trades at ~1.35 BTC), so "price < 1 coin" is reported (`price_ge_coin`) but only
    the bound above fails the month. Deribit marks IV it cannot solve as 0 and caps it at 999; those are counted."""
    start, end = window
    df = table.select(["timestamp", "trade_id", "instrument_name", "underlying", "strike", "option_type", "price",
                       "iv", "index_price", "block_trade_id", "liquidation"]).to_pandas()
    ts = df["timestamp"]
    qa = dict(rows=len(df), first_ts=int(ts.iloc[0]) if len(df) else None, last_ts=int(ts.iloc[-1]) if len(df) else None,
              dup_ids=int(df["trade_id"].duplicated().sum()), not_monotonic=int((ts.diff() < 0).sum()),
              outside_window=int(((ts < start) | (ts >= end)).sum()), instruments=int(df["instrument_name"].nunique()),
              unparsed_names=int(df["underlying"].isna().sum()), iv_null=int(df["iv"].isna().sum()),
              iv_out_of_range=int(((df["iv"] <= 0) | (df["iv"] > 500)).sum()), iv_zero=int((df["iv"] == 0).sum()),
              iv_over_500=int((df["iv"] > 500).sum()), price_nonpositive=int((df["price"] <= 0).sum()),
              block_trades=int(df["block_trade_id"].notna().sum()), liquidations=int(df["liquidation"].notna().sum()))
    coin = df["underlying"].isin(COINS)
    qa["price_ge_coin"] = int((coin & (df["price"] >= 1.0)).sum())
    call_bad = coin & (df["option_type"] == "C") & (df["price"] >= 1.0)
    put_bad = coin & (df["option_type"] == "P") & (df["price"] * df["index_price"] > df["strike"] * 1.02)
    qa["price_above_bound"] = int((call_bad | put_bad).sum())
    days = pd.Series(ts // DAY_MS).value_counts()
    all_days = range(start // DAY_MS, -(-min(end, (qa["last_ts"] or start) + 1) // DAY_MS))
    empty = [d for d in all_days if d not in days.index]
    qa["days"] = len(all_days)
    qa["days_without_trades"] = len(empty)
    qa["empty_days"] = [utc(d * DAY_MS, "%Y-%m-%d") for d in empty[:20]]
    qa["trades_per_day_min"] = int(days.min()) if len(days) else 0
    qa["trades_per_day_max"] = int(days.max()) if len(days) else 0
    qa["iv_ok"] = qa["iv_out_of_range"] == 0
    qa["ok"] = (qa["dup_ids"] == 0 and qa["not_monotonic"] == 0 and qa["outside_window"] == 0
                and qa["price_above_bound"] == 0 and qa["price_nonpositive"] == 0 and qa["unparsed_names"] == 0)
    return qa


def write_parquet(table, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    pq.write_table(table, tmp, compression="zstd")
    os.replace(tmp, path)


def build_trades_month(get, currency, month, work, now_ms, flush_pages=10, count=10_000):
    """Fetch one month of option trades for a coin into work/<rel>; resumable from work/<rel>.parts/cursor.json.
    Returns (local path, rel, qa)."""
    rel = f"{ROOT}/deribit/trades/{currency}/{month[:4]}/{month}.parquet"
    out = os.path.join(work, rel)
    parts = out + ".parts"
    os.makedirs(parts, exist_ok=True)
    state_file = os.path.join(parts, "cursor.json")
    start, end = month_bounds(month)
    end = min(end, now_ms)
    state = json.load(open(state_file)) if os.path.exists(state_file) else dict(cursor=None, boundary=[], parts=0,
                                                                                  pages=0, saturated=0, end=end)
    if state.get("end", end) != end:          # the month grew since the checkpoint (it is the current month): fine,
        state["end"] = end                    # the cursor still marks where to continue
    buf = []

    def flush(cursor, boundary):
        if buf:
            write_parquet(trades_table(buf), os.path.join(parts, f"part-{state['parts']:05d}.parquet"))
            state["parts"] += 1
            buf.clear()
        state.update(cursor=cursor, boundary=boundary)
        with open(state_file + ".tmp", "w") as f:
            json.dump(state, f)
        os.replace(state_file + ".tmp", state_file)

    cursor, boundary, n = state["cursor"], state["boundary"], 0
    for new, cursor, boundary, sat in page_trades(get, currency, start, end, count, state["cursor"], state["boundary"]):
        buf.extend(new)
        state["pages"] += 1
        state["saturated"] += int(sat)
        n += 1
        if n % flush_pages == 0:
            flush(cursor, boundary)
    flush(cursor, boundary)
    files = sorted(f for f in os.listdir(parts) if f.endswith(".parquet"))
    tables = [pq.read_table(os.path.join(parts, f)) for f in files]
    table = pa.concat_tables(tables) if tables else TRADE_SCHEMA.empty_table()
    raw = table.num_rows
    table, dropped = finish_trades(table)
    qa = trades_qa(table, (start, end))
    qa.update(raw_rows=raw, dup_ids_dropped=dropped, pages=state["pages"], saturated_pages=state["saturated"],
              window=[start, end])
    write_parquet(table, out)
    shutil.rmtree(parts, ignore_errors=True)
    return out, rel, qa


# ---------------------------------------------------------------- Deribit reference data

INSTR_FIELDS = ["instrument_name", "instrument_id", "kind", "option_type", "strike", "expiration_timestamp",
                "creation_timestamp", "settlement_period", "settlement_currency", "base_currency", "quote_currency",
                "counter_currency", "contract_size", "tick_size", "min_trade_amount", "maker_commission",
                "taker_commission", "block_trade_commission", "price_index", "is_active"]


def fetch_instruments(get, currency):
    """Every option instrument for a coin: expired ones from the history server plus the live list."""
    rows = {}
    for base, expired in ((DERIBIT, "true"), (DERIBIT_LIVE, "false")):
        for x in get(f"{base}/get_instruments", dict(currency=currency, kind="option", expired=expired))["result"]:
            rows[x["instrument_name"]] = {k: x.get(k) for k in INSTR_FIELDS} | dict(
                tick_size_steps=json.dumps(x.get("tick_size_steps")) if x.get("tick_size_steps") else None)
    df = pd.DataFrame(sorted(rows.values(), key=lambda r: (r["expiration_timestamp"] or 0, r["instrument_name"])))
    for c in ("strike", "contract_size", "tick_size", "min_trade_amount", "maker_commission", "taker_commission",
              "block_trade_commission"):
        df[c] = pd.to_numeric(df[c], errors="coerce").astype("float64")
    for c in ("expiration_timestamp", "creation_timestamp", "instrument_id"):
        df[c] = pd.to_numeric(df[c], errors="coerce").astype("Int64")
    return df


def fetch_dvol(get, currency, start_ms, end_ms, resolution=60):
    """DVOL candles [ts, open, high, low, close] with start_ms <= ts < end_ms. The API returns the newest 1000 points
    of the window plus a `continuation` (an older end); pages walk backwards until the window is covered.
    resolution=60 → 1-minute (the finest besides 1 s; 1 s would be ~180M rows per coin)."""
    out, cur = [], end_ms - 1
    while cur >= start_ms:
        r = get(f"{DERIBIT_LIVE}/get_volatility_index_data",
                dict(currency=currency, start_timestamp=start_ms, end_timestamp=cur, resolution=resolution))["result"]
        data = r.get("data") or []
        out.extend(d for d in data if start_ms <= d[0] < end_ms)
        cont = r.get("continuation")
        if not data or cont is None or cont >= cur:
            break
        cur = cont
    df = pd.DataFrame(out, columns=["timestamp", "open", "high", "low", "close"])
    df["timestamp"] = df["timestamp"].astype("int64")
    return df.drop_duplicates("timestamp").sort_values("timestamp").reset_index(drop=True)


def fetch_delivery(get, index_name, page=1000):
    """Daily delivery (settlement) prices of a Deribit index, all of history. Timestamp = 08:00 UTC of the date."""
    rows, offset = [], 0
    while True:
        r = get(f"{DERIBIT_LIVE}/get_delivery_prices", dict(index_name=index_name, offset=offset, count=page))["result"]
        data = r.get("data") or []
        rows += data
        offset += len(data)
        if not data or offset >= r.get("records_total", 0):
            break
    df = pd.DataFrame(rows, columns=["date", "delivery_price"])
    df["timestamp"] = [int(dt.datetime.fromisoformat(d).replace(hour=8, tzinfo=dt.timezone.utc).timestamp() * 1000)
                       for d in df["date"]]
    df["delivery_price"] = df["delivery_price"].astype("float64")
    return df.drop_duplicates("date").sort_values("timestamp")[["timestamp", "date", "delivery_price"]].reset_index(
        drop=True)


# ---------------------------------------------------------------- Delta Exchange India

PRODUCT_FIELDS = ["id", "symbol", "contract_type", "state", "strike_price", "settlement_time", "launch_time",
                  "contract_value", "contract_unit_currency", "tick_size", "settlement_price", "notional_type",
                  "maker_commission_rate", "taker_commission_rate", "initial_margin", "maintenance_margin",
                  "position_size_limit", "is_quanto", "description"]


def page_products(get, states, coins=COINS, page_size=500):
    """Every BTC/ETH call and put option product in the given states (cursor paging via meta.after)."""
    out = []
    for state in states:
        after = None
        while True:
            p = dict(contract_types="call_options,put_options", states=state, underlying_asset_symbols=",".join(coins),
                     page_size=page_size)
            if after:
                p["after"] = after
            r = get(f"{DELTA}/products", p)
            out += [slim_product(x) for x in r.get("result") or []]
            after = (r.get("meta") or {}).get("after")
            if not after or not r.get("result"):
                break
    return out


_ASSET_KEYS = ("underlying_asset", "quoting_asset", "settling_asset", "spot_index")


def slim_product(p):
    """A product without the bulky nested asset records (deposit networks, withdrawal limits, UI config): those
    repeat on every one of ~150k products and took >4 GB of memory (13 Oct). Each asset keeps its symbol and id."""
    q = {k: v for k, v in p.items() if k not in _ASSET_KEYS and k != "ui_config"}
    for k in _ASSET_KEYS:
        if isinstance(p.get(k), dict):
            q[k] = {kk: p[k].get(kk) for kk in ("symbol", "id") if kk in p[k]}
    return q


def products_frame(raw, inr_per_usd=None, fetched_ms=None):
    """Flatten Delta products into one row each: specs, fee rates, expiry (ms), and the rupee notional of one
    contract at the strike (contract_value × strike × INR per USD at fetch time; a guide, not a settlement value)."""
    rows = {}
    for p in raw:
        r = {k: p.get(k) for k in PRODUCT_FIELDS}
        r["underlying"] = (p.get("underlying_asset") or {}).get("symbol")
        r["quoting_asset"] = (p.get("quoting_asset") or {}).get("symbol")
        r["settling_asset"] = (p.get("settling_asset") or {}).get("symbol")
        r["premium_commission_cap"] = (p.get("product_specs") or {}).get("premium_commission_rate")
        r["expiry_ts"] = iso_ms(p.get("settlement_time"))
        r["launch_ts"] = iso_ms(p.get("launch_time"))
        r["inr_per_usd_at_fetch"] = inr_per_usd
        r["fetched_ts"] = fetched_ms
        r["raw"] = json.dumps(p, sort_keys=True)
        rows[p["id"]] = r
    df = pd.DataFrame(list(rows.values()))
    if df.empty:
        return df
    for c in ("strike_price", "contract_value", "tick_size", "settlement_price", "maker_commission_rate",
              "taker_commission_rate", "premium_commission_cap", "inr_per_usd_at_fetch"):
        df[c] = pd.to_numeric(df[c], errors="coerce").astype("float64")
    df["notional_inr_at_strike"] = df["contract_value"] * df["strike_price"] * df["inr_per_usd_at_fetch"]
    for c in ("expiry_ts", "launch_ts", "fetched_ts", "id"):
        df[c] = pd.to_numeric(df[c], errors="coerce").astype("Int64")
    return df.sort_values(["expiry_ts", "underlying", "strike_price", "contract_type", "id"]).reset_index(drop=True)


def merge_products(old, new):
    """Union by product id; the newest fetch wins (an expired product keeps its last known row even when the API stops
    listing it)."""
    if old is None or old.empty:
        return new
    keep = old[~old["id"].isin(set(new["id"]))]
    return pd.concat([keep, new], ignore_index=True).sort_values(
        ["expiry_ts", "underlying", "strike_price", "contract_type", "id"]).reset_index(drop=True)


def inr_per_usd(get):
    try:
        v = get(f"{DELTA}/settings")["result"].get("fiat_to_usd")     # {'asset_to_fiat_value': 85, 'precision': 2}
        return float(v.get("asset_to_fiat_value") if isinstance(v, dict) else v)
    except Exception:  # noqa: BLE001 — optional context, never fatal
        return None


def fee_schedule(products, today, inr):
    """The option fee schedule as Delta's API states it per product (maker/taker rate on the notional, capped at a
    share of the premium). Each distinct schedule appears once with the expiries it covered, so rate changes over
    time are visible; `current` is the schedule of the live products. GST (18% in India) is charged on top of fees
    and is not in the API."""
    sched = {}
    for p in products:
        u = (p.get("underlying_asset") or {}).get("symbol")
        key = (u, p.get("maker_commission_rate"), p.get("taker_commission_rate"),
               (p.get("product_specs") or {}).get("premium_commission_rate"), p.get("contract_value"))
        x = sched.setdefault(key, dict(products=0, live=0, first_expiry=None, last_expiry=None))
        x["products"] += 1
        x["live"] += p.get("state") == "live"
        e = (p.get("settlement_time") or "")[:10] or None
        if e:
            x["first_expiry"] = min(x["first_expiry"] or e, e)
            x["last_expiry"] = max(x["last_expiry"] or e, e)
    rows = [dict(underlying=k[0], maker_rate=k[1], taker_rate=k[2], premium_commission_cap=k[3], contract_value=k[4],
                 **v) for k, v in sorted(sched.items(), key=lambda kv: (str(kv[0][0]), kv[1]["first_expiry"] or ""))]
    return dict(fetched=today, source=f"{DELTA}/products (maker_commission_rate, taker_commission_rate, "
                                      "product_specs.premium_commission_rate)",
                note="fee = rate × notional (contract_value × underlying price × contracts), capped at "
                     "premium_commission_cap × premium; GST 18% on top (not in the API). Rates as fractions.",
                inr_per_usd=inr, current=[r for r in rows if r["live"]], schedules=rows)


CANDLE_SCHEMA = pa.schema([("timestamp", pa.int64()), ("symbol", pa.string()), ("product_id", pa.int64()),
                           ("underlying", pa.string()), ("option_type", pa.string()), ("strike", pa.float64()),
                           ("expiry_ts", pa.int64()), ("open", pa.float64()), ("high", pa.float64()),
                           ("low", pa.float64()), ("close", pa.float64()), ("volume", pa.float64())])


def fetch_candles(get, symbol, start_ms, end_ms, resolution="1m", limit=4000, step_ms=60_000):
    """1m candles start_ms <= t < end_ms. The API answers newest first, at most `limit` per call, times in SECONDS;
    pages walk backwards. Returns rows sorted ascending, de-duplicated by time."""
    rows, hi = {}, end_ms
    while hi > start_ms:
        lo = max(start_ms, hi - limit * step_ms)
        r = get(f"{DELTA}/history/candles", dict(resolution=resolution, symbol=symbol, start=lo // 1000,
                                                 end=(hi - 1) // 1000))
        data = r.get("result") or []
        for c in data:
            t = int(c["time"]) * 1000
            if start_ms <= t < end_ms:
                rows[t] = c
        hi = lo
    return [dict(timestamp=t, **{k: rows[t].get(k) for k in ("open", "high", "low", "close", "volume")})
            for t in sorted(rows)]


def build_candles_day(get, products, work, rel, max_days=120):
    """1m candles for every product in `products` (one underlying, one expiry day) → one Parquet. Candles after the
    settlement time are dropped (the API keeps printing a flat post-expiry price). Returns (path, qa)."""
    cols = {f.name: [] for f in CANDLE_SCHEMA}
    qa = dict(products=len(products), products_with_candles=0, dropped_after_expiry=0, rows=0)
    for p in products:
        exp = int(p["expiry_ts"])
        launch = int(p["launch_ts"]) if pd.notna(p.get("launch_ts")) else exp - max_days * DAY_MS
        start = max(launch - launch % 60_000, exp - max_days * DAY_MS)
        got = fetch_candles(get, p["symbol"], start, exp + 2 * 3_600_000)
        keep = [c for c in got if c["timestamp"] < exp]
        qa["dropped_after_expiry"] += len(got) - len(keep)
        if keep:
            qa["products_with_candles"] += 1
        s = parse_delta_symbol(p["symbol"]) or {}
        for c in keep:
            for k in ("timestamp", "open", "high", "low", "close", "volume"):
                cols[k].append(None if c[k] is None else (int(c[k]) if k == "timestamp" else float(c[k])))
            cols["symbol"].append(p["symbol"])
            cols["product_id"].append(int(p["id"]))
            cols["underlying"].append(p.get("underlying") or s.get("underlying"))
            cols["option_type"].append(s.get("option_type"))
            cols["strike"].append(float(p["strike_price"]) if pd.notna(p.get("strike_price")) else s.get("strike"))
            cols["expiry_ts"].append(exp)
    t = pa.table({f.name: pa.array(cols[f.name], f.type) for f in CANDLE_SCHEMA}, schema=CANDLE_SCHEMA)
    t = t.sort_by([("timestamp", "ascending"), ("symbol", "ascending")])
    qa["rows"] = t.num_rows
    ts = t.column("timestamp").to_pylist()
    qa["first_ts"], qa["last_ts"] = (ts[0], ts[-1]) if ts else (None, None)
    closes = [x for x in t.column("close").to_pylist() if x is not None]
    qa["price_nonpositive"] = sum(1 for x in closes if x <= 0)
    qa["ok"] = qa["price_nonpositive"] == 0
    out = os.path.join(work, rel)
    write_parquet(t, out)
    return out, qa


TICKER_GREEKS = ("delta", "gamma", "theta", "vega", "rho", "spot")
TICKER_QUOTES = ("best_bid", "best_ask", "bid_size", "ask_size", "bid_iv", "ask_iv", "mark_iv", "impact_mid_price")
TICKER_FIELDS = ("symbol", "product_id", "contract_type", "underlying_asset_symbol", "strike_price", "mark_price",
                 "mark_vol", "spot_price", "close", "open", "high", "low", "volume", "turnover_usd", "oi",
                 "oi_contracts", "oi_value_usd", "contract_value", "product_trading_status")


def snapshot_frame(tickers, taken_ms):
    """Live Delta option tickers → one row each (quotes and Greeks flattened, numbers as floats)."""
    rows = []
    for t in tickers:
        r = dict(snapshot_ts=taken_ms, ticker_ts=int(t["timestamp"]) // 1000 if t.get("timestamp") else None)
        r |= {k: t.get(k) for k in TICKER_FIELDS}
        r |= {f"greek_{k}": (t.get("greeks") or {}).get(k) for k in TICKER_GREEKS}
        r |= {k: (t.get("quotes") or {}).get(k) for k in TICKER_QUOTES}
        s = parse_delta_symbol(t.get("symbol")) or {}
        r["expiry_ts"], r["option_type"] = s.get("expiry_ts"), s.get("option_type")
        rows.append(r)
    df = pd.DataFrame(rows)
    text = {"symbol", "contract_type", "underlying_asset_symbol", "product_trading_status", "option_type"}
    for c in df.columns:
        if c not in text and c not in ("snapshot_ts", "ticker_ts", "expiry_ts", "product_id"):
            df[c] = pd.to_numeric(df[c], errors="coerce").astype("float64")
    for c in ("snapshot_ts", "ticker_ts", "expiry_ts", "product_id"):
        if c in df:
            df[c] = pd.to_numeric(df[c], errors="coerce").astype("Int64")
    return df


# ---------------------------------------------------------------- Black-Scholes (for the cross-check)

def _ncdf(x):
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def bs_price(spot, strike, t_years, vol, kind):
    """Black-Scholes premium in quote currency, zero rates (crypto options are priced off the forward ≈ spot)."""
    if t_years <= 0 or vol <= 0:
        return max(0.0, spot - strike) if kind == "C" else max(0.0, strike - spot)
    d1 = (math.log(spot / strike) + 0.5 * vol * vol * t_years) / (vol * math.sqrt(t_years))
    d2 = d1 - vol * math.sqrt(t_years)
    if kind == "C":
        return spot * _ncdf(d1) - strike * _ncdf(d2)
    return strike * _ncdf(-d2) - spot * _ncdf(-d1)


def implied_vol(price, spot, strike, t_years, kind, lo=1e-4, hi=10.0):
    """Implied vol by bisection; None when the price is outside the no-arbitrage range."""
    if price is None or t_years <= 0 or spot <= 0:
        return None
    if not bs_price(spot, strike, t_years, lo, kind) <= price <= bs_price(spot, strike, t_years, hi, kind):
        return None
    for _ in range(100):
        mid = 0.5 * (lo + hi)
        if bs_price(spot, strike, t_years, mid, kind) < price:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


# ---------------------------------------------------------------- catalog

CSV_FIELDS = ["path", "source", "dataset", "key", "period", "complete", "rows", "first_utc", "last_utc", "first_ts",
              "last_ts", "ok", "iv_ok", "dup_ids", "dup_ids_dropped", "iv_out_of_range", "iv_zero", "iv_over_500",
              "price_ge_coin", "price_above_bound",
              "days_without_trades", "saturated_pages", "products", "products_with_candles", "bytes", "sha256", "md5",
              "built"]


def sha256_file(path, algo="sha256"):
    h = hashlib.new(algo)
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def entry(rel, local, source, dataset, key, period, complete, qa, today):
    return dict(path=rel, source=source, dataset=dataset, key=key, period=period, complete=bool(complete),
                sha256=sha256_file(local), md5=sha256_file(local, "md5"), bytes=os.path.getsize(local),
                built=today, **qa)


def load_catalog(path=CATALOG, remote=None, run=subprocess.run):
    """Catalog from disk; when missing locally, the copy on Drive (so a fresh box can resume)."""
    if not os.path.exists(path) and remote:
        rclone(["copyto", f"{remote}/{ROOT}/{os.path.basename(path)}", path], run)
    if os.path.exists(path):
        c = json.load(open(path))
        return {e["path"]: e for e in c.get("files", [])}, {f["path"]: f for f in c.get("failures", [])}
    return {}, {}


def merge_catalog(files, failures, path, mine=()):
    """Fold in what other processes (e.g. a Delta run beside a Deribit run) saved meanwhile. For every path this
    process did not build or fail itself (`mine`), the copy on disk wins, so a long run never writes back the stale
    rows it loaded at start. Changes `files` and `failures` in place."""
    if not os.path.exists(path):
        return
    disk_files, disk_fail = load_catalog(path)
    for k, v in disk_files.items():
        if k not in mine:
            files[k] = v
    for k in [k for k in failures if k not in mine]:
        failures.pop(k)
    for k, v in disk_fail.items():
        if k not in mine:
            failures[k] = v


def save_catalog(files, failures, path=CATALOG, remote=REMOTE, mine=None):
    """Write the catalog (JSON + CSV) atomically. With `mine` (the paths this process wrote, a set, even empty) the
    file on disk is merged in first, under a lock file, so several processes can share one catalog."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if mine is not None:
        import fcntl
        with open(path + ".lock", "w") as lk:
            fcntl.flock(lk, fcntl.LOCK_EX)
            merge_catalog(files, failures, path, mine)
            _write_catalog(files, failures, path, remote)
        return
    _write_catalog(files, failures, path, remote)


def _write_catalog(files, failures, path, remote):
    rows = [files[k] for k in sorted(files)]
    doc = dict(remote=f"{remote}/{ROOT}", times="UTC epoch milliseconds (int64)",
               sources=dict(deribit=DERIBIT, deribit_live=DERIBIT_LIVE, delta_india=DELTA),
               totals=dict(files=len(rows), rows=sum(e.get("rows") or 0 for e in rows),
                           bytes=sum(e["bytes"] for e in rows)),
               files=rows, failures=[failures[k] for k in sorted(failures)])
    with open(path + ".tmp", "w") as f:
        json.dump(doc, f, indent=1, sort_keys=True)
        f.write("\n")
    os.replace(path + ".tmp", path)
    with open(path[:-5] + ".csv", "w", newline="") as f:
        w = csv.DictWriter(f, CSV_FIELDS, extrasaction="ignore")
        w.writeheader()
        for e in rows:
            w.writerow({**{k: e.get(k) for k in CSV_FIELDS}, "first_utc": utc(e.get("first_ts")),
                        "last_utc": utc(e.get("last_ts"))})


# ---------------------------------------------------------------- Drive (rclone), as in crypto_lake.py

def rclone(args, run=subprocess.run, capture=False):
    cmd = ["rclone", *args]
    print("$", " ".join(cmd), flush=True)
    return run(cmd, capture_output=True, text=True) if capture else run(cmd)


def drive_listing(remote, folder, run=subprocess.run):
    """{path relative to remote: (size, md5)} under remote/folder; {} when the folder does not exist."""
    r = rclone(["lsjson", "-R", "--files-only", "--hash", "--hash-type", "md5", f"{remote}/{folder}"], run, True)
    if r.returncode != 0:
        return {}
    return {f"{folder}/{f['Path']}": (f["Size"], (f.get("Hashes") or {}).get("md5")) for f in json.loads(r.stdout)}


def upload(local, remote, rel, run=subprocess.run):
    """Copy one file to remote/rel and verify it with `rclone check` (size + md5)."""
    if rclone(["copyto", local, f"{remote}/{rel}", "--checksum", "--retries", "5"], run).returncode:
        return False
    d = os.path.dirname(local)
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as t:
        t.write(os.path.basename(local) + "\n")
    try:
        return rclone(["check", d, f"{remote}/{os.path.dirname(rel)}", "--files-from", t.name, "--one-way"],
                      run).returncode == 0
    finally:
        os.remove(t.name)


def fetch_remote(remote, rel, local, run=subprocess.run):
    """Download remote/rel to local; False when it is not on Drive."""
    os.makedirs(os.path.dirname(local), exist_ok=True)
    return rclone(["copyto", f"{remote}/{rel}", local], run, True).returncode == 0 and os.path.exists(local)


# ---------------------------------------------------------------- driver

class Lake:
    """Run state shared by the worker threads: HTTP clients, the catalog (behind one lock), Drive."""

    def __init__(self, a, run=subprocess.run, deribit=None, delta=None, now_ms=None, today=None):
        self.a, self.run = a, run
        self.deribit = deribit or Http(min_interval=0.3)        # ≈3 req/s, far under Deribit's public limits
        self.delta = delta or Http(min_interval=getattr(a, "delta_interval", 0.2))   # ≈5 req/s by default
        self.now_ms = now_ms or int(time.time() * 1000)
        self.today = today or utc(self.now_ms, "%Y-%m-%d")
        self.files, self.failures = load_catalog(a.catalog, None if a.dry_run else a.remote, run)
        self.lock = threading.Lock()
        self.upload_lock = threading.Lock()
        self.mine = set()                    # catalog paths this process built, failed or re-checked
        self.saved = self.published = 0.0
        self._listing = {}

    def listing(self, folder):
        if folder not in self._listing:
            self._listing[folder] = {} if self.a.dry_run else drive_listing(self.a.remote, folder, self.run)
        return self._listing[folder]

    def have(self, rel, folder):
        """True when the catalog has rel as complete and Drive holds the same size + md5."""
        e = self.files.get(rel)
        return bool(e and e.get("complete") and self.listing(folder).get(rel) == (e["bytes"], e["md5"]))

    def publish(self, local, rel, e):
        """Upload + check, record the catalog row, delete the local file. Returns True on success."""
        with self.upload_lock:     # one upload at a time: Drive makes duplicate folders when two create one at once
            ok = upload(local, self.a.remote, rel, self.run)
        with self.lock:
            self.mine.add(rel)
            if ok:
                self.files[rel] = e
                self.failures.pop(rel, None)
            else:
                self.failures[rel] = dict(path=rel, error="upload or rclone check failed", when=self.today)
        if os.path.exists(local):
            os.remove(local)
        self.flush()
        return ok

    def fail(self, rel, e):
        with self.lock:
            self.mine.add(rel)
            self.failures[rel] = dict(path=rel, error=f"{type(e).__name__}: {str(e)[:300]}", when=self.today)
        print(f"FAILED {rel}: {e}", flush=True)

    def flush(self, force=False):
        with self.lock:
            now = time.time()
            if force or now - self.saved >= 30:
                save_catalog(self.files, self.failures, self.a.catalog, self.a.remote, self.mine)
                self.saved = now
            if not self.a.dry_run and (force or now - self.published >= 600):
                for f in (self.a.catalog, self.a.catalog[:-5] + ".csv"):
                    rclone(["copyto", f, f"{self.a.remote}/{ROOT}/{os.path.basename(f)}", "--checksum"], self.run)
                if os.path.exists(README):
                    rclone(["copyto", README, f"{self.a.remote}/{ROOT}/README.md", "--checksum"], self.run)
                self.published = now

    # -- Deribit
    def trades(self, currency, months):
        """Build the given months (in the order given), `--workers` at a time; skip months already on Drive."""
        folder = f"{ROOT}/deribit/trades/{currency}"
        cur = utc(self.now_ms, "%Y-%m")
        todo = [m for m in months if not self.have(f"{folder}/{m[:4]}/{m}.parquet", folder)]
        print(f"deribit/trades/{currency}: {len(months)} months, {len(todo)} to build", flush=True)
        if self.a.dry_run:
            return todo

        def job(m):
            rel = f"{folder}/{m[:4]}/{m}.parquet"
            t0 = time.time()
            try:
                out, rel, qa = build_trades_month(self.deribit, currency, m, self.a.work, self.now_ms)
                e = entry(rel, out, "deribit", "trades", currency, m, m < cur, qa, self.today)
                if self.publish(out, rel, e):
                    print(f"  {rel}: {qa['rows']:,} trades, {e['bytes'] / 1e6:.1f} MB, ok={qa['ok']} "
                          f"iv_ok={qa['iv_ok']} ({time.time() - t0:.0f}s)", flush=True)
            except Exception as ex:  # noqa: BLE001 — recorded; a re-run retries the month
                self.fail(rel, ex)

        with ThreadPoolExecutor(self.a.workers) as ex:
            list(ex.map(job, todo))
        return todo

    def requa(self, coins):
        """Re-run the trade checks on months already on Drive (download, check md5, recompute QA, update the
        catalog row). The data itself is not changed."""
        for rel, e in sorted(self.files.items()):
            if e.get("dataset") != "trades" or e.get("key") not in coins:
                continue
            local = os.path.join(self.a.work, "requa", rel)
            try:
                if not fetch_remote(self.a.remote, rel, local, self.run) or sha256_file(local, "md5") != e["md5"]:
                    raise RuntimeError("missing on Drive or md5 differs from the catalog")
                qa = trades_qa(pq.read_table(local), tuple(e["window"]))
                with self.lock:
                    self.files[rel] = {**e, **qa}
                    self.failures.pop(rel, None)
                    self.mine.add(rel)
                print(f"  requa {rel}: ok={qa['ok']} iv_out={qa['iv_out_of_range']} "
                      f"above_bound={qa['price_above_bound']}", flush=True)
            except Exception as ex:  # noqa: BLE001
                self.fail(rel, ex)
            finally:
                if os.path.exists(local):
                    os.remove(local)
        self.flush(force=True)

    def single(self, rel, df_or_table, dataset, key, source="deribit"):
        local = os.path.join(self.a.work, rel)
        t = df_or_table if isinstance(df_or_table, pa.Table) else pa.Table.from_pandas(df_or_table, preserve_index=False)
        write_parquet(t, local)
        ts = None
        for c in ("timestamp", "expiration_timestamp", "expiry_ts", "snapshot_ts"):
            if c in t.column_names:
                ts = [x for x in t.column(c).to_pylist() if x is not None]
                break
        qa = dict(rows=t.num_rows, first_ts=min(ts) if ts else None, last_ts=max(ts) if ts else None, ok=True)
        return self.publish(local, rel, entry(rel, local, source, dataset, key, self.today, True, qa, self.today))

    def instruments(self):
        for c in COINS:
            rel = f"{ROOT}/deribit/instruments/{c}.parquet"
            try:
                df = fetch_instruments(self.deribit, c)
                print(f"deribit/instruments/{c}: {len(df):,} instruments", flush=True)
                if not self.a.dry_run:
                    self.single(rel, df, "instruments", c)
            except Exception as ex:  # noqa: BLE001
                self.fail(rel, ex)

    def dvol(self):
        """DVOL per coin, resumed from the last timestamp of the copy already on Drive. Deribit keeps 1-minute DVOL
        for only ~6 months (checked 13 Oct 2026), so the 1m file grows from there on every run; the hourly file goes
        back to DVOL's start (Mar 2021)."""
        for c in COINS:
            for res, name in ((60, f"{c}.parquet"), (3600, f"{c}_1h.parquet")):
                rel = f"{ROOT}/deribit/dvol/{name}"
                local = os.path.join(self.a.work, rel)
                try:
                    old = None
                    if self.files.get(rel) and not self.a.dry_run and fetch_remote(self.a.remote, rel, local, self.run):
                        old = pd.read_parquet(local)
                    # restart AT the last stored candle: it may have been the unfinished current minute/hour
                    start = int(old["timestamp"].max()) if old is not None and len(old) else 1_609_459_200_000
                    new = fetch_dvol(self.deribit, c, start, self.now_ms, res)
                    df = pd.concat([old, new]) if old is not None else new
                    df = df.drop_duplicates("timestamp", keep="last").sort_values("timestamp").reset_index(drop=True)
                    print(f"deribit/dvol/{name}: +{len(new):,} rows, {len(df):,} total from "
                          f"{utc(df['timestamp'].min())}", flush=True)
                    if not self.a.dry_run:
                        self.single(rel, df, "dvol_1m" if res == 60 else "dvol_1h", c)
                except Exception as ex:  # noqa: BLE001
                    self.fail(rel, ex)

    def index(self):
        for name in ("btc_usd", "eth_usd"):
            rel = f"{ROOT}/deribit/index/{name}-delivery.parquet"
            try:
                df = fetch_delivery(self.deribit, name)
                print(f"deribit/index/{name}: {len(df):,} daily delivery prices from {df['date'].min()}", flush=True)
                if not self.a.dry_run:
                    self.single(rel, df, "index_delivery", name)
            except Exception as ex:  # noqa: BLE001
                self.fail(rel, ex)

    # -- Delta India
    def delta_products(self):
        rel = f"{ROOT}/delta_india/products.parquet"
        try:
            raw = page_products(self.delta, ["live", "expired"])
            inr = inr_per_usd(self.delta)
            df = products_frame(raw, inr, self.now_ms)
            local = os.path.join(self.a.work, rel)
            old = None
            if not self.a.dry_run and self.files.get(rel) and fetch_remote(self.a.remote, rel, local, self.run):
                gone = pq.read_table(local, filters=[("id", "not in", [int(x) for x in df["id"]])])
                old = gone.to_pandas() if gone.num_rows else None      # only delisted rows, not all 150k raw rows
                os.remove(local)
            df = merge_products(old, df)
            print(f"delta_india/products: {len(df):,} BTC/ETH options ({(df['state'] == 'live').sum()} live), expiries "
                  f"{utc(df['expiry_ts'].min(), '%Y-%m-%d')}..{utc(df['expiry_ts'].max(), '%Y-%m-%d')}", flush=True)
            fees = fee_schedule(raw, self.today, inr)
            os.makedirs(os.path.dirname(FEES_COPY), exist_ok=True)
            with open(FEES_COPY, "w") as f:
                json.dump(fees, f, indent=1)
                f.write("\n")
            if not self.a.dry_run:
                self.single(rel, df, "products", "BTC,ETH", "delta_india")
                frel = f"{ROOT}/delta_india/fees/fees-{self.today}.json"
                flocal = os.path.join(self.a.work, frel)
                os.makedirs(os.path.dirname(flocal), exist_ok=True)
                shutil.copyfile(FEES_COPY, flocal)
                with self.upload_lock:
                    ok = upload(flocal, self.a.remote, frel, self.run)
                if not ok:
                    self.fail(frel, RuntimeError("upload failed"))
                os.remove(flocal)
            return df
        except Exception as ex:  # noqa: BLE001
            self.fail(rel, ex)
            return None

    CANDLE_PRODUCT_COLS = ["id", "symbol", "underlying", "strike_price", "expiry_ts", "launch_ts"]

    def delta_candles(self, products=None):
        """One file per underlying per expiry day, newest expiries first; only expiries already settled."""
        if products is None:
            rel = f"{ROOT}/delta_india/products.parquet"
            local = os.path.join(self.a.work, rel)
            if not fetch_remote(self.a.remote, rel, local, self.run):
                products = self.delta_products()
            else:
                products = pd.read_parquet(local, columns=self.CANDLE_PRODUCT_COLS)
                os.remove(local)
        if products is None or products.empty:
            return []
        products = products[self.CANDLE_PRODUCT_COLS]
        done = products[products["expiry_ts"] < self.now_ms]
        groups = []
        for (und, exp_day), g in done.groupby([done["underlying"], done["expiry_ts"] // DAY_MS]):
            day = utc(int(exp_day) * DAY_MS, "%Y-%m-%d")
            groups.append((day, und, g.to_dict("records")))
        groups.sort(key=lambda x: (x[0], x[1]), reverse=True)
        if self.a.since:
            groups = [g for g in groups if g[0][:7] >= self.a.since]
        todo = []
        for day, und, ps in groups:
            folder = f"{ROOT}/delta_india/candles/{und}"
            rel = f"{folder}/{day[:4]}/{day}.parquet"
            if not self.have(rel, folder):
                todo.append((day, und, ps, rel))
        if self.a.limit:
            todo = todo[:self.a.limit]
        print(f"delta_india/candles: {len(groups)} expiry days, {len(todo)} to build", flush=True)
        if self.a.dry_run:
            return todo

        def job(x):
            day, und, ps, rel = x
            try:
                out, qa = build_candles_day(self.delta, ps, self.a.work, rel)
                e = entry(rel, out, "delta_india", "candles_1m", und, day, True, qa, self.today)
                if self.publish(out, rel, e):
                    print(f"  {rel}: {qa['rows']:,} candles, {qa['products_with_candles']}/{qa['products']} "
                          f"products with candles", flush=True)
            except Exception as ex:  # noqa: BLE001
                self.fail(rel, ex)

        with ThreadPoolExecutor(self.a.workers) as ex:
            list(ex.map(job, todo))
        return todo

    def snapshot(self):
        taken = int(time.time() * 1000)
        tick = []
        for kind in ("call_options", "put_options"):
            tick += self.delta(f"{DELTA}/tickers", dict(contract_types=kind,
                                                        underlying_asset_symbols=",".join(COINS)))["result"]
        df = snapshot_frame(tick, taken)
        rel = f"{ROOT}/delta_india/snapshots/{utc(taken, '%Y')}/{utc(taken, '%Y-%m-%d')}/{utc(taken, '%H%M%S')}.parquet"
        print(f"delta_india/snapshot: {len(df)} option tickers", flush=True)
        out = os.path.join(self.a.work, rel)
        write_parquet(pa.Table.from_pandas(df, preserve_index=False), out)
        if self.a.dry_run or self.a.no_upload:
            print(f"  saved locally: {out}")
            return out
        # Snapshots stay out of the catalog on purpose: the nightly job may run on a box whose catalog copy is old,
        # and it must never overwrite the catalog on Drive. The path itself says what each file is.
        ok = upload(out, self.a.remote, rel, self.run)
        os.remove(out)
        if not ok:
            raise ApiError(f"snapshot upload or rclone check failed: {rel}")
        return rel


def crosscheck(lake, day, und="BTC", window_min=30):
    """One day: every Delta India option that traded (1m candle with volume) vs Deribit trades on the same strike and
    expiry date within ±window_min minutes. Delta's IV comes from Black-Scholes on the candle close with Deribit's
    index price at that minute as spot and Delta's own expiry (12:00 UTC; Deribit's is 08:00 UTC)."""
    d0 = int(dt.datetime.fromisoformat(day).replace(tzinfo=dt.timezone.utc).timestamp() * 1000)
    rel = f"{ROOT}/delta_india/products.parquet"
    local = os.path.join(lake.a.work, "crosscheck", rel)
    if fetch_remote(lake.a.remote, rel, local, lake.run):
        prods = pd.read_parquet(local)
        os.remove(local)
    else:
        prods = products_frame(page_products(lake.delta, ["expired", "live"], coins=(und,)))
    prods = prods[prods["underlying"] == und]
    prods = prods[(prods["expiry_ts"] > d0) & (prods["launch_ts"].fillna(0) < d0 + DAY_MS)]
    # Deribit trades of the day (the API, so the check works before the month is in the lake)
    trades = []
    for new, *_ in page_trades(lake.deribit, und, d0, d0 + DAY_MS):
        trades += new
    dt_ = trades_table(trades).to_pandas()
    dt_["exp_day"] = dt_["expiry_ts"] // DAY_MS
    pairs, rows = [], []
    for p in prods.to_dict("records"):
        s = parse_delta_symbol(p["symbol"])
        exp_day = int(p["expiry_ts"]) // DAY_MS
        der = dt_[(dt_["exp_day"] == exp_day) & (dt_["strike"] == float(p["strike_price"])) &
                  (dt_["option_type"] == s["option_type"])]
        if der.empty:
            continue
        pairs.append((p, s, der))
    for p, s, der in pairs[:60]:                       # cap the candle calls; plenty for a sanity check
        cs = [c for c in fetch_candles(lake.delta, p["symbol"], d0, d0 + DAY_MS) if (c["volume"] or 0) > 0]
        for c in cs:
            near = der[(der["timestamp"] - c["timestamp"]).abs() <= window_min * 60_000]
            if near.empty:
                continue
            j = (near["timestamp"] - c["timestamp"]).abs().idxmin()
            spot = float(near.loc[j, "index_price"])
            t = (int(p["expiry_ts"]) - c["timestamp"]) / (365 * DAY_MS)
            iv = implied_vol(float(c["close"]), spot, float(p["strike_price"]), t, s["option_type"])
            if iv is None:
                continue
            rows.append(dict(symbol=p["symbol"], minute=utc(c["timestamp"]), delta_close_usd=c["close"],
                             delta_iv=round(iv * 100, 2), deribit_iv=float(near.loc[j, "iv"]),
                             deribit_price_usd=round(float(near.loc[j, "price"]) * spot, 2), spot=spot,
                             gap_min=round(abs(int(near.loc[j, "timestamp"]) - c["timestamp"]) / 60_000, 1)))
    df = pd.DataFrame(rows)
    out = dict(day=day, underlying=und, delta_products_alive=len(prods), matched_strike_expiry=len(pairs),
               comparisons=len(df), method=crosscheck.__doc__.split("\n")[0].strip())
    if len(df):
        diff = df["delta_iv"] - df["deribit_iv"]
        out |= dict(median_iv_diff_pts=round(float(diff.median()), 2), mean_abs_iv_diff_pts=round(float(diff.abs().mean()), 2),
                    share_within_5pts=round(float((diff.abs() <= 5).mean()), 3),
                    share_within_10pts=round(float((diff.abs() <= 10).mean()), 3),
                    sample=df.head(15).to_dict("records"))
    return out


def status(files, failures):
    g = {}
    for e in files.values():
        k = (e["source"], e["dataset"], e["key"])
        x = g.setdefault(k, dict(n=0, rows=0, bytes=0, periods=[], bad=0, ivbad=0, first=None, last=None))
        x["n"] += 1
        x["rows"] += e.get("rows") or 0
        x["bytes"] += e["bytes"]
        x["periods"].append(e["period"])
        x["bad"] += not e.get("ok", True)
        x["ivbad"] += e.get("iv_out_of_range") or 0
        for f, fn in (("first", min), ("last", max)):
            v = e.get(f"{f}_ts")
            if v is not None:
                x[f] = v if x[f] is None else fn(x[f], v)
    lines = [f"{'source/dataset/key':36} {'files':>6} {'from':>10} {'to':>10} {'rows':>13} {'MB':>9} {'bad':>4} iv-out"]
    for k in sorted(g):
        x = g[k]
        lines.append(f"{'/'.join(k):36} {x['n']:>6} {utc(x['first'], '%Y-%m-%d'):>10} {utc(x['last'], '%Y-%m-%d'):>10} "
                     f"{x['rows']:>13,} {x['bytes'] / 1e6:>9.1f} {x['bad']:>4} {x['ivbad']}")
    tot = sum(e["bytes"] for e in files.values())
    lines.append(f"total: {len(files)} files, {sum(e.get('rows') or 0 for e in files.values()):,} rows, "
                 f"{tot / 1e9:.2f} GB; failures: {len(failures)}")
    lines += [f"  FAILED {f['path']}: {f['error']}" for f in failures.values()]
    return "\n".join(lines)


def priority_plan(now_ms):
    """Boss's order: BTC newest 2 years (newest first), ETH the same, then (after Delta) the older months."""
    cur = utc(now_ms, "%Y-%m")
    y, m = map(int, cur.split("-"))
    two = f"{y - 2:04d}-{m:02d}"
    recent = {c: month_range(max(two, FIRST_MONTH[c]), cur)[::-1] for c in COINS}
    older = {c: [x for x in month_range(FIRST_MONTH[c], cur)[::-1] if x < two] for c in COINS}
    return recent, older


def main(argv=None, run=subprocess.run, deribit=None, delta=None, now_ms=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("cmd", choices=["run", "trades", "instruments", "dvol", "index", "delta-products", "delta-candles",
                                    "snapshot", "crosscheck", "requa", "status"])
    ap.add_argument("--currency", choices=COINS, action="append", default=[])
    ap.add_argument("--since", help="first month YYYY-MM (trades / delta-candles)")
    ap.add_argument("--until", help="last month YYYY-MM (trades)")
    ap.add_argument("--day", help="crosscheck: the UTC day YYYY-MM-DD")
    ap.add_argument("--limit", type=int, default=0, help="delta-candles: at most this many expiry days")
    ap.add_argument("--remote", default=REMOTE)
    ap.add_argument("--work", default=os.path.join(tempfile.gettempdir(), "crypto-options-lake"))
    ap.add_argument("--catalog", default=CATALOG)
    ap.add_argument("--workers", type=int, default=3, help="months / expiry days fetched at the same time")
    ap.add_argument("--delta-interval", type=float, default=0.2,
                    help="seconds between Delta requests (0.2 ≈ 5/s; Delta allows ~10k weight per 5 min per IP)")
    ap.add_argument("--dry-run", action="store_true", help="plan only: no uploads")
    ap.add_argument("--no-upload", action="store_true", help="snapshot: save locally only")
    a = ap.parse_args(argv)
    if a.cmd == "status":
        print(status(*load_catalog(a.catalog)))
        return 0
    os.makedirs(a.work, exist_ok=True)
    if a.cmd == "snapshot":
        a.dry_run = a.dry_run or a.no_upload
        lake = Lake(argparse.Namespace(**{**vars(a), "dry_run": True}), run, deribit, delta, now_ms)  # no catalog I/O
        lake.a = a
        lake.snapshot()
        return 0
    lake = Lake(a, run, deribit, delta, now_ms)
    coins = a.currency or list(COINS)
    if a.cmd == "trades":
        for c in coins:
            months = month_range(max(a.since or FIRST_MONTH[c], FIRST_MONTH[c]), a.until or utc(lake.now_ms, "%Y-%m"))
            lake.trades(c, months[::-1])
    elif a.cmd == "requa":
        lake.requa(coins)
    elif a.cmd == "instruments":
        lake.instruments()
    elif a.cmd == "dvol":
        lake.dvol()
    elif a.cmd == "index":
        lake.index()
    elif a.cmd == "delta-products":
        lake.delta_products()
    elif a.cmd == "delta-candles":
        lake.delta_candles()
    elif a.cmd == "crosscheck":
        day = a.day or utc(lake.now_ms - DAY_MS, "%Y-%m-%d")
        res = crosscheck(lake, day, coins[0])
        with open(CROSSCHECK, "w") as f:
            json.dump(res, f, indent=1)
            f.write("\n")
        print(json.dumps({k: v for k, v in res.items() if k != "sample"}, indent=1))
        return 0
    elif a.cmd == "run":
        recent, older = priority_plan(lake.now_ms)
        for c in coins:
            lake.trades(c, recent[c])
        lake.instruments()
        lake.dvol()
        lake.index()
        products = lake.delta_products()
        lake.delta_candles(products)
        for c in coins:
            lake.trades(c, older[c])
    if not a.dry_run:
        lake.flush(force=True)
    print(status(lake.files, lake.failures))
    return 1 if lake.failures else 0


if __name__ == "__main__":
    sys.exit(main())
