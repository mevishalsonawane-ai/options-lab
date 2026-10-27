"""Liquidity paper check (27 Oct 2026), data side: the index and option minutes of October 2026 from Upstox's public
candle API (no login, no secret), turned into the same day stores the research engine reads (r51data.Day).

    python research/reports/liqpaper/lpdata.py fetch          # -> $LP_CACHE (default ~/.cache/iraalgo/liqpaper)

Why Upstox: the research lake (Dhan) ends 19 Oct and the Dhan token has expired. Upstox serves 1-minute candles for
indices and for option contracts that are still listed without a login. Every October trade of Liquidity's three
indices (BANKNIFTY, FINNIFTY, MIDCPNIFTY: monthly expiries only) is on the 27 Oct 2026 contracts, which are still
listed, so 1-26 Oct can be replayed in full. Known limits: Upstox's minute bars are the exchange's, but they are a
different vendor from the lake (the stray-print cleaning of R51 Amendment A is not applied); a contract minute with no
trade is missing, as in the lake.

Nothing here is committed: the candles stay in the cache folder (CLAUDE.md: no raw data in git).
"""
from __future__ import annotations

import gzip
import json
import os
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
R51 = HERE.parents[1] / "hunt" / "r51"
for _p in (R51, HERE.parents[1], HERE.parents[2]):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

import r51cfg as K  # noqa: E402
import r51data  # noqa: E402

CACHE = Path(os.environ.get("LP_CACHE", Path.home() / ".cache" / "iraalgo" / "liqpaper")).expanduser()
BASE = "https://api.upstox.com/v3/historical-candle"
MASTER = "https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz"
INDEX_KEY = {"BANKNIFTY": "NSE_INDEX|Nifty Bank", "FINNIFTY": "NSE_INDEX|Nifty Fin Service",
             "MIDCPNIFTY": "NSE_INDEX|NIFTY MID SELECT"}
UNDS = tuple(INDEX_KEY)
EXPIRY = date(2026, 10, 27)              # the October monthly expiry of all three (last Tuesday)
WARM = date(2026, 9, 14)                 # index warm-up: the levels read 10 calendar days of past minutes
FIRST, LAST = date(2026, 10, 1), date(2026, 10, 26)
STRIKE_PAD = 4                           # strikes fetched: the day's index range +- this many steps


def _get(url: str, tries: int = 4):
    import requests
    for k in range(tries):
        r = requests.get(url, headers={"Accept": "application/json", "User-Agent": "Mozilla/5.0"}, timeout=60)
        if r.status_code == 200:
            return r.json()
        if r.status_code == 429 or r.status_code >= 500:
            time.sleep(2 ** (k + 1))
            continue
        r.raise_for_status()
    raise RuntimeError(f"{url}: gave up after {tries} tries")


def candles(key: str, lo: date, hi: date) -> list:
    """[(ts 'YYYY-MM-DDTHH:MM', o, h, l, c, v)] for one instrument, oldest first (Upstox returns newest first and at
    most a month a call for 1-minute candles, so the range is walked in 25-day pieces)."""
    out, a = [], lo
    while a <= hi:
        b = min(a + timedelta(days=25), hi)
        js = _get(f"{BASE}/{key.replace('|', '%7C').replace(' ', '%20')}/minutes/1/{b}/{a}")
        for c in js.get("data", {}).get("candles", []):
            out.append((c[0][:16], float(c[1]), float(c[2]), float(c[3]), float(c[4]), float(c[5] or 0)))
        a = b + timedelta(days=1)
        time.sleep(0.25)
    return sorted(set(out))


def to_rows(cs: list, traded_only: bool = False) -> dict:
    """{day: (5, 375) O H L C V on the 09:15..15:29 grid}; minutes outside the session are dropped. traded_only (option
    contracts): Upstox fills a minute with no trade with a flat candle at the last price and zero volume; the lake
    leaves such a minute empty, so it is dropped here too (an index candle always has zero volume and is kept)."""
    out: dict = {}
    for ts, o, h, lo, c, v in cs:
        t = datetime.strptime(ts, "%Y-%m-%dT%H:%M")
        r = t.hour * 60 + t.minute - K.OPEN_MIN
        if not 0 <= r < K.NROWS or c <= 0 or (traded_only and not v > 0):
            continue
        a = out.setdefault(t.date(), np.full((5, K.NROWS), np.nan))
        a[:, r] = (o, h, lo, c, v)
    return out


def contracts(master: list, und: str) -> dict:
    """{(side, strike): instrument key} of the 27 Oct 2026 contracts of one index."""
    exp_ms = None
    out = {}
    for r in master:
        if (r.get("segment") != "NSE_FO" or r.get("underlying_symbol") != und
                or r.get("instrument_type") not in ("CE", "PE")):
            continue
        if datetime.fromtimestamp(r["expiry"] / 1000).date() != EXPIRY:
            continue
        exp_ms = r["expiry"]
        out[(r["instrument_type"][0], float(r["strike_price"]))] = r["instrument_key"]
    assert exp_ms is not None, f"no {EXPIRY} contracts for {und}"
    return out


def fetch():
    CACHE.mkdir(parents=True, exist_ok=True)
    mp = CACHE / "NSE.json.gz"
    if not mp.exists():
        import requests
        mp.write_bytes(requests.get(MASTER, timeout=120).content)
    master = json.loads(gzip.decompress(mp.read_bytes()))
    for und in UNDS:
        f = CACHE / f"index_{und}.json"
        if not f.exists():
            f.write_text(json.dumps(candles(INDEX_KEY[und], WARM, LAST)))
        idx = to_rows(json.loads(f.read_text()))
        days = [d for d in idx if FIRST <= d <= LAST]
        lo = min(np.nanmin(idx[d][2]) for d in days)
        hi = max(np.nanmax(idx[d][1]) for d in days)
        step = K.STEP[und]
        want = np.arange(np.floor(lo / step) * step - STRIKE_PAD * step,
                         np.ceil(hi / step) * step + (STRIKE_PAD + 1) * step, step)
        cons = contracts(master, und)
        got = {}
        cf = CACHE / f"opt_{und}.json"
        if cf.exists():
            got = {tuple(json.loads(k)): v for k, v in json.loads(cf.read_text()).items()}
        for side in ("C", "P"):
            for k in want:
                key = (side, float(k))
                if key in got or key not in cons:
                    continue
                got[key] = candles(cons[key], FIRST, LAST)
                print(und, side, k, len(got[key]), flush=True)
            cf.write_text(json.dumps({json.dumps(list(k)): v for k, v in got.items()}))


def build(und: str) -> dict:
    """{day: r51data.Day} for one index from the cache: idx from WARM, contracts from 1 Oct. The ATM label is the index
    close rounded to the step (the research's fallback); expiry is True only on 27 Oct, the only expiry in the
    window."""
    idx = to_rows(json.loads((CACHE / f"index_{und}.json").read_text()))
    raw = json.loads((CACHE / f"opt_{und}.json").read_text())
    opt = {tuple(json.loads(k)): to_rows(v, traded_only=True) for k, v in raw.items()}
    step = K.STEP[und]
    out = {}
    for d in sorted(idx):
        D = r51data.Day(d, idx[d][:4].copy())
        if FIRST <= d <= LAST:
            cl = D.idx[3]
            D.atm = np.where(np.isfinite(cl), np.floor(cl / step + 0.5) * step, np.nan)
            for key, by_day in opt.items():
                if d in by_day:
                    D.con[(key[0], float(key[1]))] = by_day[d].astype(np.float32)
            D.expiry = d == EXPIRY
        out[d] = D
    return out


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "fetch":
        fetch()
