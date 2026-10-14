"""L3 crypto cube: one aligned 1-minute UTC grid per symbol, 1440 rows a day, 24x7.

    python -m research.brain.crypto.cube build --symbols BTCUSDT ETHUSDT [--from 2023-10-01 --to 2026-03-31]
    python -m research.brain.crypto.cube info  --symbols BTCUSDT ETHUSDT

Per UTC day: <cache>/crypto/cube/<SYM>/<YYYY>/<day>.parquet and <day>.json (cube version, source fingerprint, flag
counts, sha256). Built a month at a time with the previous day loaded in front (for the stale-run and carried-forward
values); a month is rebuilt only when CUBE_VERSION or its source files changed. Nothing is filled: see clean.py.

Columns (row t = the bar that starts at t and closes at t + 1 minute; known once that minute is over):
  ts, day, minute                         naive UTC bar start; UTC date; 0 .. 1439
  open high low close volume quote_volume trades taker_buy_volume flag          spot (Binance spot klines)
  perp_open ... perp_taker_buy_volume perp_flag                                  USD-M perpetual klines
  mark_close mark_flag, index_close index_flag                                   mark price and index price (1m)
  premium_close premium_flag             premium index (perp vs index, a fraction; Binance's funding input)
  basis, basis_bps                       perp close - spot close (USD, bps of spot) when both bars are ok/stale
  mark_premium_bps                       (mark - index) / index, bps
  funding_rate, funding_age_min          the last SETTLED funding rate, from the settlement's minute on; age in minutes
  funding_interval_h                     the interval the archive gives with that rate (8 for these symbols)
  min_to_next_funding                    minutes from the bar's end to the next 00/08/16 UTC settlement (clock only)
  oi oi_value top_ls_accounts top_ls_positions ls_accounts taker_ls_vol metrics_age_min
                                         5-minute OI and long/short metrics, from the END of their 5-minute period
  dvol, dvol_age_min                     Deribit DVOL (hourly close, from the row holding the hour's end; BTC, ETH)
  opt_atm_iv opt_skew opt_pc_24h opt_flow_24h opt_n_1h opt_age_min
                                         Deribit option trades by UTC hour (adapters.option_hours), from the row
                                         holding the hour's end, carried up to 2 hours (BTC, ETH; v3, 14 Oct 2026)
  vol_precision                          "estimate" (1-minute bars) where spot volume exists, else "none"
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from .. import quality
from . import clean, config
from .adapters import OPT_COLS, ArchiveAdapter, DvolAdapter, OptionsAdapter

PERP = ["perp_" + c for c in clean.BAR]
METRICS = list(clean.METRIC_MAP.values())
GOOD = ("ok", "stale")


def metrics_avail(ts) -> pd.DatetimeIndex:
    return pd.DatetimeIndex(ts) + pd.Timedelta(minutes=config.METRICS_PERIOD_MIN)


def build_frame(raw: dict, dvol: pd.DataFrame | None, g: pd.DatetimeIndex) -> tuple[pd.DataFrame, dict]:
    """The cube for grid g from raw frames (ArchiveAdapter.load) and DVOL. Pure: same inputs, same output."""
    m = pd.DataFrame(index=g)
    m.index.name = "ts"
    m["day"] = g.normalize()
    m["minute"] = (g.hour * 60 + g.minute).astype(np.int16)
    info = {}
    S, sflag, info["spot"] = clean.bars_on_grid(raw.get("spot"), g)
    for c in clean.BAR:
        m[c] = S[c].values
    m["flag"] = sflag
    P, pflag, info["perp"] = clean.bars_on_grid(raw.get("perp"), g)
    for c in clean.BAR:
        m["perp_" + c] = P[c].values
    m["perp_flag"] = pflag
    for k in ("mark", "index"):
        B, f, info[k] = clean.bars_on_grid(raw.get(k), g, cols=["open", "high", "low", "close"])
        m[k + "_close"], m[k + "_flag"] = B.close.values, f
    B, f, info["premium"] = clean.bars_on_grid(raw.get("premium"), g, cols=["open", "high", "low", "close"],
                                               prices=False)
    m["premium_close"], m["premium_flag"] = B.close.values, f

    both = np.isin(sflag, GOOD) & np.isin(pflag, GOOD)
    with np.errstate(invalid="ignore", divide="ignore"):
        m["basis"] = np.where(both, m.perp_close - m.close, np.nan)
        m["basis_bps"] = np.where(both, (m.perp_close / m.close - 1) * 1e4, np.nan)
        mi = np.isin(m.mark_flag, GOOD) & np.isin(m.index_flag, GOOD)
        m["mark_premium_bps"] = np.where(mi, (m.mark_close / m.index_close - 1) * 1e4, np.nan)

    fr = raw.get("funding")
    if fr is not None and len(fr):
        for col, out in (("rate", "funding_rate"), ("interval_h", "funding_interval_h")):
            v, age = clean.ffill_age(clean.place_asof(fr.ts, fr[col].values, g), config.MAX_AGE["funding"])
            m[out] = v
        m["funding_age_min"] = age
    else:
        m["funding_rate"] = m["funding_interval_h"] = m["funding_age_min"] = np.nan
    m["min_to_next_funding"] = clean.minutes_to_next(g)

    me = raw.get("metrics")
    if me is not None and len(me):
        # A row stamped T describes the 5 minutes [T, T+5): its OI value matches the mark price at T+5 and its taker ratio
        # the flow over [T, T+5) (checked on the real archive, 13 Oct 2026 review). So it is known at T+5 at the
        # earliest: it goes on row T+5 (known at T+6). The archive gives no publication time, so any extra delay is unknown.
        avail = metrics_avail(me.ts)
        for c in METRICS:
            v, age = clean.ffill_age(clean.place_asof(avail, me[c].values, g), config.MAX_AGE["metrics_5m"])
            m[c] = v
        m["metrics_age_min"] = age
    else:
        for c in METRICS + ["metrics_age_min"]:
            m[c] = np.nan
    if dvol is not None and len(dvol):
        m["dvol"], m["dvol_age_min"] = clean.ffill_age(clean.place_asof(dvol.avail, dvol.dvol.values, g),
                                                       config.MAX_AGE["dvol"])
    else:
        m["dvol"] = m["dvol_age_min"] = np.nan
    op = raw.get("options")
    if op is not None and len(op):
        for c in OPT_COLS:
            m[c], age = clean.ffill_age(clean.place_asof(op.ts, op[c].values, g), config.MAX_AGE["options"])
            if c == "opt_n_1h":
                m["opt_age_min"] = age
    else:
        for c in OPT_COLS + ["opt_age_min"]:
            m[c] = np.nan
    m["vol_precision"] = np.where(np.isfinite(m.volume.to_numpy(float)), "estimate", "none")
    return m.reset_index(), info


# --- storage ------------------------------------------------------------------------------------------------------------
def day_paths(root: Path, sym: str, d: date) -> tuple[Path, Path]:
    base = config.paths(root).cube(sym) / str(d.year)
    return base / f"{d}.parquet", base / f"{d}.json"


def manifest(root: Path, sym: str, d: date) -> dict | None:
    pj = day_paths(root, sym, d)[1]
    return json.loads(pj.read_text()) if pj.exists() else None


def built_days(root: Path, sym: str) -> list[date]:
    base = config.paths(root).cube(sym)
    return sorted(date.fromisoformat(p.stem) for p in base.glob("*/*.json")) if base.exists() else []


def load(root: Path, sym: str, frm: date | None = None, to: date | None = None) -> pd.DataFrame:
    days = [d for d in built_days(root, sym) if (frm is None or d >= frm) and (to is None or d <= to)]
    if not days:
        return pd.DataFrame()
    return pd.concat([pd.read_parquet(day_paths(root, sym, d)[0]) for d in days], ignore_index=True)


def _write_day(root: Path, sym: str, d: date, m: pd.DataFrame, fp: str, info: dict) -> None:
    pp, pj = day_paths(root, sym, d)
    pp.parent.mkdir(parents=True, exist_ok=True)
    m.to_parquet(pp, compression="zstd", index=False)
    sha = hashlib.sha256(pp.read_bytes()).hexdigest()
    flags = {k: quality.counts(m[k]) for k in ("flag", "perp_flag", "mark_flag", "index_flag", "premium_flag")}
    pj.write_text(json.dumps(dict(cube_version=config.CUBE_VERSION, fingerprint=hashlib.sha256(fp.encode()).hexdigest()[:16],
                                  rows=len(m), flags=flags, sha256=sha, raw=info,
                                  coverage={c: int(np.isfinite(m[c].to_numpy(float)).sum()) for c in
                                            ("close", "perp_close", "funding_rate", "oi", "dvol", "opt_atm_iv")}), indent=1))


def build(root: Path, sym: str, frm: date, to: date, adapter: ArchiveAdapter | None = None,
          dvol: DvolAdapter | None = None, force: bool = False, verbose: bool = True,
          options: OptionsAdapter | None = None) -> dict:
    root = Path(root)
    adapter = adapter or ArchiveAdapter(config.paths(root).raw)
    dvol = dvol or DvolAdapter(options_root=config.paths(root).options)
    options = options or OptionsAdapter(config.paths(root).options)
    t0, n, skipped = time.time(), 0, 0
    for mo in adapter.months(sym):
        p = pd.Period(mo, "M")
        lo, hi = max(p.start_time.date(), frm), min(p.end_time.date(), to)
        if lo > hi:
            continue
        days = [d.date() for d in pd.date_range(lo, hi, freq="D")]
        fp = (adapter.fingerprint(sym, [str(p - 1), mo]) + f"|dvol:{dvol.fingerprint(sym)}"
              + f"|opt:{options.fingerprint(sym, [str(p - 1), mo])}")
        fph = hashlib.sha256(fp.encode()).hexdigest()[:16]
        if not force and all((man := manifest(root, sym, d)) and man.get("cube_version") == config.CUBE_VERSION
                             and man.get("fingerprint") == fph for d in days):
            skipped += len(days)
            continue
        start = pd.Timestamp(days[0])
        end = pd.Timestamp(days[-1]) + pd.Timedelta(minutes=config.MINUTES_PER_DAY - 1)
        warm = start - pd.Timedelta(days=1)
        raw = adapter.load(sym, warm, end)
        opt = options.load(sym, warm, end)
        if opt is not None:
            raw["options"] = opt
        g = clean.grid(warm, end)
        M, info = build_frame(raw, dvol.load(sym, warm, end), g)
        M = M[M.ts >= start]
        for d, part in M.groupby(M.day.dt.date):
            if not np.isfinite(part.close.to_numpy(float)).any() and not np.isfinite(part.perp_close.to_numpy(float)).any():
                continue            # no spot and no perp at all: the source does not cover this day
            _write_day(root, sym, d, part.reset_index(drop=True), fp, info)
            n += 1
        if verbose:
            print(f"  {sym} {mo}: {n} days written, {time.time() - t0:.0f}s", flush=True)
    if verbose:
        print(f"{sym}: {n} cube days built, {skipped} up to date")
    return dict(built=n, skipped=skipped, seconds=round(time.time() - t0, 1))


def info(root: Path, sym: str) -> dict:
    days = built_days(root, sym)
    if not days:
        return dict(symbol=sym, days=0)
    tot: dict = {}
    mb = 0.0
    cov: dict = {}
    for d in days:
        man = manifest(root, sym, d)
        mb += day_paths(root, sym, d)[0].stat().st_size / 1e6
        for k, v in man["flags"]["flag"].items():
            tot[k] = tot.get(k, 0) + v
        for k, v in man["coverage"].items():
            cov[k] = cov.get(k, 0) + v
    rows = len(days) * config.MINUTES_PER_DAY
    return dict(symbol=sym, days=len(days), first=str(days[0]), last=str(days[-1]), mb=round(mb, 1), spot_flags=tot,
                coverage_pct={k: round(100 * v / rows, 1) for k, v in cov.items()})


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.crypto.cube")
    ap.add_argument("cmd", choices=["build", "info"])
    ap.add_argument("--symbols", nargs="+", default=["BTCUSDT", "ETHUSDT"])
    ap.add_argument("--from", dest="frm", default="2017-01-01")
    ap.add_argument("--to", default="2099-12-31")
    ap.add_argument("--cache", default=None)
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args(argv)
    root = config.paths(a.cache).root
    for s in a.symbols:
        if a.cmd == "build":
            build(root, s, date.fromisoformat(a.frm), date.fromisoformat(a.to), force=a.force)
        else:
            print(json.dumps(info(root, s)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
