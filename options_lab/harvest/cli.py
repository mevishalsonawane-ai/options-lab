"""Nightly chain harvester.

    PYTHONPATH=. python -m options_lab.harvest.cli --underlying NIFTY --expiries 1

Collects 1-minute OHLCV+OI for every live option contract, plus the index and
India VIX, into <root>/bars/<UNDERLYING>/<date>.parquet.

Why this runs every day: no free source serves EXPIRED contracts. Upstox drops
a contract from its master the moment it settles, and recycles the token. A day
not collected is a day gone permanently.
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pandas as pd
import requests

from options_lab.harvest import collect, instruments, manifest, store, upstox

FLUSH_EVERY = 60         # contracts accumulated before rewriting partitions

MASTER_URL = "https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz"
UA = {"User-Agent": "Mozilla/5.0", "Accept": "application/json"}
DEFAULT_ROOT = Path(__file__).resolve().parents[1] / "data"

INDEX_KEYS = {
    "NIFTY": "NSE_INDEX|Nifty 50",
    "BANKNIFTY": "NSE_INDEX|Nifty Bank",
    "INDIAVIX": "NSE_INDEX|India VIX",
}


def http_get_json(url: str, *, tries: int = 6) -> dict:
    last: Exception | None = None
    for attempt in range(tries):
        try:
            r = requests.get(url, headers=UA, timeout=45)
            if r.status_code == 401:
                raise upstox.UpstoxError(
                    "Upstox now requires authentication for historical-candle. "
                    "The unauthenticated route has been withdrawn - stop and re-plan."
                )
            if r.status_code == 429:
                # Cloudflare rate limit, and it is not a brief one - once
                # tripped it stayed on for minutes, so the backoff has to be
                # geometric and start well above a transport blip. Measured
                # 2026-09-10: a burst of ~200 requests tripped it and a 75s
                # total backoff was still not enough.
                time.sleep(10.0 * (2 ** attempt))
                last = RuntimeError("rate limited (429)")
                continue
            r.raise_for_status()
            return r.json()
        except upstox.UpstoxError:
            raise
        except Exception as exc:  # noqa: BLE001 - retry any transport failure
            last = exc
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"GET failed after {tries} tries: {url}") from last


def fetch_master(root: Path) -> list[dict]:
    """Download the master and archive it dated - without it, expired symbols
    can never be mapped back to a token."""
    raw = requests.get(MASTER_URL, headers={"User-Agent": UA["User-Agent"]}, timeout=300)
    raw.raise_for_status()
    body = gzip.decompress(raw.content)

    archive = root / "instrument_master" / f"{date.today():%Y-%m-%d}.json.gz"
    archive.parent.mkdir(parents=True, exist_ok=True)
    archive.write_bytes(raw.content)

    return json.loads(body)


def harvest_index(root: Path, name: str, key: str, start: date, end: date,
                  fetched_at: datetime) -> int:
    contract = instruments.Contract(
        underlying=name, expiry=date(2099, 1, 1), strike=0.0, right="IX",
        lot_size=1, instrument_key=key, trading_symbol=name,
    )
    frame = collect.harvest_contract(contract, start, end, fetch=http_get_json)
    written = 0
    for day, part in collect.split_by_day(frame):
        store.write_day(root, name, day, part, source="upstox", fetched_at=fetched_at)
        written += len(part)
    return written


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--underlying", nargs="+", default=["NIFTY", "BANKNIFTY"])
    p.add_argument("--expiries", type=int, default=3,
                   help="nearest N expiries per underlying (nearest dies soonest). "
                        "Default 3: collecting only the front expiry leaves every "
                        "past partition holding a single far-dated chain.")
    p.add_argument("--days", type=int, default=30, help="backfill window in days")
    p.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    p.add_argument("--workers", type=int, default=4,
                   help="Upstox rate-limits hard at 429; 8 tripped it")
    p.add_argument("--indices", action="store_true", help="also collect index + VIX")
    args = p.parse_args(argv)

    today = date.today()
    start, end = today - timedelta(days=args.days), today
    fetched_at = datetime.now(timezone.utc)
    args.root.mkdir(parents=True, exist_ok=True)

    print(f"[{fetched_at:%H:%M:%S}] fetching instrument master ...", flush=True)
    master = fetch_master(args.root)
    print(f"  {len(master):,} instruments; archived under {args.root/'instrument_master'}")

    if args.indices:
        for name, key in INDEX_KEYS.items():
            n = harvest_index(args.root, name, key, start, end, fetched_at)
            print(f"  index {name}: {n:,} bars", flush=True)

    total = 0
    for underlying in args.underlying:
        live = collect.contracts_to_refresh(
            instruments.parse_options(master, underlying), today=today)
        expiries = sorted({c.expiry for c in live})[: args.expiries]
        chain = [c for c in live if c.expiry in expiries]
        print(f"\n{underlying}: {len(chain)} contracts over expiries "
              f"{[str(e) for e in expiries]}", flush=True)

        failures: list[str] = []

        def one(contract):
            try:
                # Only TODAY's expiring series is at risk of vanishing;
                # everything else is still listed tomorrow and reads fine from
                # the dated endpoint. Restricting it here keeps the extra
                # request count small enough to stay under the 429 limit.
                return contract, collect.harvest_contract(
                    contract, start, end, fetch=http_get_json,
                    today=today, include_current=(contract.expiry == today))
            except upstox.UpstoxError as exc:
                print(f"  ! {contract.trading_symbol}: {exc}", flush=True)
                failures.append(contract.trading_symbol)
                return contract, None
            except RuntimeError as exc:
                # A transport failure on one contract used to abort the whole
                # run AFTER partitions were written but BEFORE the manifest
                # was, leaving the store and the manifest out of sync. It is
                # recorded and counted instead - and a non-zero count stops
                # the day claiming same_day.
                print(f"  ! {contract.trading_symbol}: {exc}", flush=True)
                failures.append(contract.trading_symbol)
                return contract, None

        # Accumulate across contracts and rewrite each day-partition once per
        # batch. write_day is a read-modify-write of the whole partition, so
        # calling it per contract is quadratic - it measurably stalled a live
        # run at 375/502 contracts.
        done = 0
        pending: dict[date, list] = {}

        def flush() -> int:
            written = 0
            for day, frames in pending.items():
                part = pd.concat(frames, ignore_index=True)
                store.write_day(args.root, underlying, day, part,
                                source="upstox", fetched_at=fetched_at)
                written += len(part)
            pending.clear()
            return written

        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            for contract, frame in pool.map(one, chain):
                done += 1
                if frame is not None and not frame.empty:
                    for day, part in collect.split_by_day(frame):
                        pending.setdefault(day, []).append(part)
                if done % FLUSH_EVERY == 0:
                    total += flush()
                    print(f"  {done}/{len(chain)} contracts, {total:,} bars",
                          flush=True)
        total += flush()
        if failures:
            print(f"  {len(failures)} contract(s) failed to fetch; today "
                  f"cannot claim same_day: {failures[:4]}", flush=True)

        # Record what each partition actually contains. A session reached by
        # backfill holds only contracts still listed today; its real front
        # chain expired and is unrecoverable. Chain-aggregate features are
        # valid on same_day sessions only.
        for day in store.harvested_days(args.root, underlying):
            part = store.read_day(args.root, underlying, day)
            opts = part[part["right"] != "IX"]
            n_contracts = int(opts["contract_id"].nunique())
            manifest.record(
                args.root, underlying, day,
                n_expiries=int(opts["expiry"].nunique()),
                n_contracts=n_contracts,
                scope=manifest.scope_for(day=day, today=today,
                                         failures=len(failures),
                                         n_contracts=n_contracts),
                collected_on=today,
            )

        days = store.harvested_days(args.root, underlying)
        complete = manifest.complete_chain_sessions(args.root, underlying)
        if days:
            print(f"  {underlying} done: {len(days)} day-partitions "
                  f"({days[0]} .. {days[-1]}); {len(complete)} same-day "
                  f"complete-chain, {len(days) - len(complete)} backfilled/partial",
                  flush=True)
        else:
            print(f"  {underlying}: nothing", flush=True)

    print(f"\nTotal bars written: {total:,}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
