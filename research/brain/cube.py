"""L3 Market Cube: one aligned 1-minute picture of each session.

    python -m research.brain.cube fetch                       # download the research-data release files (cache)
    python -m research.brain.cube build --from 2024-02-13 --to 2026-02-23 [--und BANKNIFTY] [--source auto]
    python -m research.brain.cube info [--und BANKNIFTY]
    python -m research.brain.cube expiries                    # refresh calendar_data/expiries.csv from the release

Per session two Parquet tables and a manifest under <cache>/cube/<UND>/<YYYY>/:
  <day>.minutes.parquet   375 rows: index OHLCV + flag, futures + basis, VIX, ATM strike, expiry/DTE, events
  <day>.chain.parquet     375 x 21 offsets x 2 sides: strike, bar, LTP (+age), OI, OI change, IV, Greeks, flag
  <day>.json              cube version, source fingerprint, provenance, row/flag counts (drives incremental builds)
Nothing is filled: a missing bar is NaN with flag "gap". See README.md for every rule.
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

from . import adapters, bs, calendar, config, quality
from .adapters.base import DayData

OFFS = np.array(list(config.CHAIN_OFFSETS))


# --- one day ----------------------------------------------------------------------------------------------------------
def _on_grid(df: pd.DataFrame, g: pd.DatetimeIndex, cols: list[str]) -> tuple[pd.DataFrame, dict]:
    """Rows of df placed on the grid. Out-of-grid rows dropped; exact duplicates collapsed; conflicting duplicates
    keep the first and mark the minute suspect. Returns (frame indexed by g, counts)."""
    info = dict(rows=len(df), off_grid=0, dup_same=0, dup_conflict=0)
    if df is None or df.empty:
        return pd.DataFrame(index=g, columns=cols, dtype=float), info
    d = df.copy()
    d["ts"] = pd.to_datetime(d.ts).dt.floor("min")
    inside = d.ts.isin(g)
    info["off_grid"] = int((~inside).sum())
    d = d[inside]
    full = d.drop_duplicates()
    info["dup_same"] = len(d) - len(full)
    conflict = full.ts[full.ts.duplicated()].unique()
    info["dup_conflict"] = len(conflict)
    full = full.drop_duplicates("ts", keep="first").set_index("ts")
    out = full.reindex(g)[cols].astype(float)
    out.attrs["conflict"] = set(conflict)
    return out, info


def _pick_expiry(opts: pd.DataFrame, day: date):
    """The nearest expiry on or after the day among the contracts present (the expiring one on expiry days when the
    source has it). Any row counts, so the choice at 09:15 is the same as at 15:29 (no look-ahead)."""
    if opts is None or opts.empty:
        return None
    e = [x for x in set(opts.expiry) if x is not None and x == x and x >= day]
    return min(e) if e else None


def build_day(dd: DayData, sessions: set[date] | None = None) -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    day, und = dd.day, dd.und
    g = calendar.grid(day)
    n = len(g)
    step = config.STRIKE_STEP[und]

    # index
    I, ii = _on_grid(dd.index, g, ["open", "high", "low", "close", "volume"])
    iflag = quality.series_flags(I.open, I.high, I.low, I.close)
    if I.attrs.get("conflict"):
        iflag[np.isin(g, list(I.attrs["conflict"]))] = "suspect"
    m = pd.DataFrame(index=g)
    m.index.name = "ts"
    m["day"] = pd.Timestamp(day)
    m["minute"] = np.arange(n, dtype=np.int16)
    for c in ["open", "high", "low", "close", "volume"]:
        m[c] = I[c].values
    m["flag"] = iflag

    # futures and basis
    F, fi = _on_grid(dd.fut, g, ["close", "volume", "oi"])
    fflag = quality.series_flags(F.close, F.close, F.close, F.close)
    m["fut_close"], m["fut_volume"], m["fut_oi"], m["fut_flag"] = F.close.values, F.volume.values, F.oi.values, fflag
    both = (iflag == "ok") & (fflag == "ok")
    m["basis"] = np.where(both, F.close.values - I.close.values, np.nan)
    m["basis_pct"] = m.basis / m.close * 100

    # VIX (a 20% one-minute jump is suspect; VIX moves far more than the index)
    V, vi = _on_grid(dd.vix, g, ["close"])
    m["vix"] = V.close.values
    m["vix_flag"] = quality.series_flags(V.close, V.close, V.close, V.close, jump=0.20, stale_run=10)

    # calendar
    exp = _pick_expiry(dd.options, day)
    exp_src = "data" if exp else ""
    if exp is None:
        exp, exp_src = calendar.next_expiry(und, day, sessions)
    cal_exp = calendar.expiries(und, day, day, sessions)
    m["expiry"] = pd.Timestamp(exp) if exp else pd.NaT
    m["dte_days"] = calendar.dte_days(day, exp)
    m["is_expiry_day"] = bool(len(cal_exp)) or (exp == day)
    ev = calendar.events_on(und, day)
    m["events"] = ",".join(ev)

    ok_close = np.where(iflag == "ok", I.close.values, np.nan)
    atm = np.round(ok_close / step) * step
    m["atm_strike"] = atm

    chain, ci = _chain(dd, g, exp, atm, ok_close, step)
    cov = chain.groupby("ts").flag.apply(lambda s: (s == "ok").mean()).reindex(g).values if len(chain) else np.zeros(n)
    m["chain_ok_share"] = cov
    m = m.reset_index()

    man = dict(cube_version=config.CUBE_VERSION, und=und, day=str(day), sources=dd.sources,
               expiry=str(exp) if exp else None, expiry_source=exp_src, is_expiry_day=bool(m.is_expiry_day.iloc[0]),
               events=ev, index=dict(ii, flags=quality.counts(iflag)), fut=dict(fi, flags=quality.counts(fflag)),
               vix=dict(vi, flags=quality.counts(m.vix_flag)), chain=ci)
    return m, chain, man


def _chain(dd: DayData, g, exp, atm, spot, step) -> tuple[pd.DataFrame, dict]:
    n = len(g)
    o = dd.options
    info = dict(contracts=0, rows=0, flags=quality.counts([]))
    if o is None or o.empty or exp is None:
        return pd.DataFrame(), info
    o = o[o.expiry == exp].copy()
    o["ts"] = pd.to_datetime(o.ts).dt.floor("min")
    o = o[o.ts.isin(g)].drop_duplicates(["ts", "strike", "side"], keep="first")
    keys = sorted(set(zip(o.strike, o.side)))
    info["contracts"] = len(keys)
    col = {k: j for j, k in enumerate(keys)}
    nc = len(keys)
    pos = g.get_indexer(o.ts)
    cj = np.array([col[(k, s)] for k, s in zip(o.strike, o.side)])
    M = {}
    for f in ["open", "high", "low", "close", "volume", "oi", "iv_vendor"]:
        a = np.full((n, nc), np.nan)
        a[pos, cj] = o[f].astype(float).values
        M[f] = a
    # a real trade this minute: a bar that is not a volume-0 flat copy (the archive pads untraded minutes that way)
    padded = (M["volume"] == 0) & (M["open"] == M["close"]) & (M["high"] == M["low"]) & (M["high"] == M["close"])
    traded = np.isfinite(M["close"]) & ~padded
    last = np.where(traded, np.arange(n)[:, None], -1)
    last = np.maximum.accumulate(last, axis=0)
    ltp = np.where(last >= 0, M["close"][np.clip(last, 0, None), np.arange(nc)[None, :]], np.nan)
    age = np.where(last >= 0, np.arange(n)[:, None] - last, np.nan)
    oi = pd.DataFrame(M["oi"]).ffill().values                       # last reported OI (snapshots every few minutes)
    oi_prev = np.vstack([np.full((1, nc), np.nan), oi[:-1]])
    # the day's first reported OI per contract, known only from that minute on
    fo = np.full((n, nc), np.nan)
    for j in range(nc):
        idx = np.flatnonzero(np.isfinite(M["oi"][:, j]))
        if len(idx):
            fo[idx[0]:, j] = M["oi"][idx[0], j]

    # lookup (t, offset, side) -> contract column
    want = atm[:, None] + OFFS[None, :] * step                       # n x 21
    rows = []
    for side in ("C", "P"):
        cols_side = {k: col[(k, s)] for k, s in keys if s == side}
        J = np.vectorize(lambda k: cols_side.get(k, -1), otypes=[int])(np.nan_to_num(want, nan=-1.0))
        J[~np.isfinite(want)] = -1
        tt, kk = np.meshgrid(np.arange(n), np.arange(len(OFFS)), indexing="ij")
        has = J >= 0
        jj = np.where(has, J, 0)

        def pick(a):
            return np.where(has, a[tt, jj], np.nan)

        rows.append(dict(t=tt.ravel(), off=OFFS[kk].ravel(), side=np.full(tt.size, side), strike=want.ravel(),
                         has=has.ravel(), open=pick(M["open"]).ravel(), high=pick(M["high"]).ravel(),
                         low=pick(M["low"]).ravel(), close=pick(M["close"]).ravel(), volume=pick(M["volume"]).ravel(),
                         traded=np.where(has, traded[tt, jj], False).ravel(), ltp=pick(ltp).ravel(),
                         ltp_age=pick(age).ravel(), oi=pick(oi).ravel(),
                         oi_chg_1m=(pick(oi) - pick(oi_prev)).ravel(), oi_chg_day=(pick(oi) - pick(fo)).ravel(),
                         iv_vendor=pick(M["iv_vendor"]).ravel()))
    R = {k: np.concatenate([r[k] for r in rows]) for k in rows[0]}
    ch = pd.DataFrame(R)
    t_idx = ch.pop("t").values
    ch.insert(0, "ts", g.values[t_idx])
    ch.rename(columns={"off": "offset"}, inplace=True)
    ch["expiry"] = pd.Timestamp(exp)
    S = spot[t_idx]
    T = ((calendar.expiry_ts(exp) - (pd.DatetimeIndex(ch.ts) + pd.Timedelta(minutes=1))).total_seconds().values
         / (365 * 86400))
    T = np.where(T > 0, T, np.nan)
    is_call = ch.side.values == "C"
    K = ch.strike.values
    intrinsic = np.where(is_call, np.maximum(S - K, 0), np.maximum(K - S, 0))
    has, trd = ch.pop("has").values, ch.pop("traded").values
    flag = np.full(len(ch), "gap", dtype=object)
    flag[has & np.isfinite(ch.ltp.values)] = "stale"
    flag[trd] = "ok"
    c = ch.close.values
    with np.errstate(invalid="ignore"):
        bad = trd & ((c <= 0) | (ch.high.values < ch.low.values) | (c < intrinsic - np.maximum(0.002 * S, 5.0)))
    flag[bad] = "suspect"
    iv = np.full(len(ch), np.nan)
    sel = (flag == "ok") & (c >= config.IV_MIN_PRICE)
    iv[sel] = bs.implied_vol(c[sel], S[sel], K[sel], T[sel], is_call[sel], config.RISK_FREE, config.DIV_YIELD,
                             config.IV_MIN, config.IV_MAX, min_vega=config.IV_MIN_VEGA)
    d, gm, th, vg = bs.greeks(S, K, T, iv, is_call, config.RISK_FREE, config.DIV_YIELD)
    ch["iv"] = iv
    ch["delta"], ch["gamma"], ch["theta"], ch["vega"] = d, gm, th, vg
    ch["flag"] = flag
    ch["offset"] = ch.offset.astype(np.int8)
    info.update(rows=len(ch), flags=quality.counts(flag), iv_solved=int(np.isfinite(iv).sum()))
    return ch, info


# --- storage ----------------------------------------------------------------------------------------------------------
def day_paths(root: Path, und: str, day: date) -> tuple[Path, Path, Path]:
    d = root / "cube" / und / str(day.year)
    s = str(day)
    return d / f"{s}.minutes.parquet", d / f"{s}.chain.parquet", d / f"{s}.json"


def write_day(root: Path, m: pd.DataFrame, ch: pd.DataFrame, man: dict) -> int:
    pm, pc, pj = day_paths(root, man["und"], date.fromisoformat(man["day"]))
    pm.parent.mkdir(parents=True, exist_ok=True)
    m.to_parquet(pm, compression="zstd", index=False)
    if len(ch):
        ch.to_parquet(pc, compression="zstd", index=False)
    elif pc.exists():
        pc.unlink()
    size = pm.stat().st_size + (pc.stat().st_size if pc.exists() else 0)
    h = hashlib.sha256(pm.read_bytes())
    if pc.exists():
        h.update(pc.read_bytes())
    man["bytes"] = size
    man["sha256"] = h.hexdigest()
    pj.write_text(json.dumps(man, indent=1, default=str))
    return size


def manifest(root: Path, und: str, day: date) -> dict | None:
    pj = day_paths(root, und, day)[2]
    return json.loads(pj.read_text()) if pj.exists() else None


def built_days(root: Path, und: str) -> list[date]:
    base = root / "cube" / und
    if not base.exists():
        return []
    return sorted(date.fromisoformat(p.name[:10]) for p in base.glob("*/*.json"))


def load(root: Path, und: str, frm: date | None = None, to: date | None = None, chain: bool = True):
    """Read built days back: (minutes, chain) concatenated, sorted by time."""
    days = [d for d in built_days(root, und) if (frm is None or d >= frm) and (to is None or d <= to)]
    ms, cs = [], []
    for d in days:
        pm, pc, _ = day_paths(root, und, d)
        ms.append(pd.read_parquet(pm))
        if chain and pc.exists():
            cs.append(pd.read_parquet(pc))
    M = pd.concat(ms, ignore_index=True) if ms else pd.DataFrame()
    C = pd.concat(cs, ignore_index=True) if cs else pd.DataFrame()
    return M, C


# --- build ------------------------------------------------------------------------------------------------------------
def _ranges(days: list[date], all_days: list[date]) -> list[tuple[date, date]]:
    """Group days into runs of consecutive sessions (so each run is one filtered read)."""
    pos = {d: i for i, d in enumerate(all_days)}
    out, cur = [], []
    for d in days:
        if cur and pos[d] != pos[cur[-1]] + 1:
            out.append((cur[0], cur[-1]))
            cur = []
        cur.append(d)
    if cur:
        out.append((cur[0], cur[-1]))
    return out


def build(root: Path, und: str, frm: date, to: date, source: str = "auto", force: bool = False,
          adapter_list: list | None = None, verbose: bool = True) -> dict:
    ads = adapter_list if adapter_list is not None else adapters.pick(config.paths(root), und, source)
    if not ads:
        print(f"no source available for {und}; nothing built")
        return dict(built=0, skipped=0)
    owner: dict[date, object] = {}
    for a in ads:                                   # first adapter that has a day owns it
        for d in a.sessions(und):
            if frm <= d <= to and d not in owner:
                owner[d] = a
    sessions = set(owner)
    todo: dict[object, list[date]] = {}
    skipped = 0
    for d in sorted(owner):
        a = owner[d]
        man = manifest(root, und, d)
        if (not force and man and man.get("cube_version") == config.CUBE_VERSION
                and man.get("fingerprint") == a.fingerprint(und)):
            skipped += 1
            continue
        todo.setdefault(a, []).append(d)
    built, sizes, t0 = 0, [], time.time()
    for a, days in todo.items():
        fp = a.fingerprint(und)
        allp = sorted(d for d, x in owner.items() if x is a)
        for lo, hi in _ranges(days, allp):
            want = set(days)
            for dd in a.load(und, lo, hi):
                if dd.day not in want:
                    continue
                m, ch, man = build_day(dd, sessions)
                man["fingerprint"] = fp
                sizes.append(write_day(root, m, ch, man))
                built += 1
                if verbose and built % 25 == 0:
                    print(f"  {built} days built ({dd.day}), {time.time() - t0:.0f}s", flush=True)
    res = dict(built=built, skipped=skipped, seconds=round(time.time() - t0, 1),
               mean_day_mb=round(float(np.mean(sizes)) / 1e6, 3) if sizes else None)
    if verbose:
        print(f"{und}: built {built}, up to date {skipped}, {res['seconds']}s, mean {res['mean_day_mb']} MB/day")
    return res


def refresh_expiries(root: Path) -> Path:
    from .adapters.release import ReleaseAdapter
    ra = ReleaseAdapter(config.paths(root).raw_release)
    frames = {u: ra.nearest_expiries(u) for u in ("BANKNIFTY", "NIFTY") if ra.available(u) or u == "NIFTY"}
    frames = {u: f for u, f in frames.items() if len(f)}
    return calendar.write_observed_expiries(frames)


def info(root: Path, und: str):
    days = built_days(root, und)
    if not days:
        print(f"no cube days for {und} in {root}")
        return
    tot = 0
    fl = {f: 0 for f in config.FLAGS}
    cf = {f: 0 for f in config.FLAGS}
    for d in days:
        man = manifest(root, und, d)
        tot += man.get("bytes", 0)
        for k, v in man["index"]["flags"].items():
            fl[k] += v
        for k, v in man["chain"].get("flags", {}).items():
            cf[k] += v
    print(f"{und}: {len(days)} days {days[0]} .. {days[-1]}, {tot / 1e6:.1f} MB, {tot / len(days) / 1e6:.2f} MB/day")
    print(f"  index flags {fl}")
    print(f"  chain flags {cf}")


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.cube")
    ap.add_argument("cmd", choices=["build", "fetch", "info", "expiries"])
    ap.add_argument("--from", dest="frm", default="2000-01-01")
    ap.add_argument("--to", default="2099-12-31")
    ap.add_argument("--und", default="BANKNIFTY")
    ap.add_argument("--source", default="auto", choices=["auto", "release", "lake"])
    ap.add_argument("--cache", default=None, help="cache root (default $IRAALGO_CACHE or ~/.cache/iraalgo)")
    ap.add_argument("--force", action="store_true", help="rebuild days even if up to date")
    a = ap.parse_args(argv)
    p = config.paths(a.cache)
    if a.cmd == "fetch":
        from .adapters.release import fetch, files_for
        fetch(files_for(a.und), p.raw_release)
    elif a.cmd == "build":
        build(p.root, a.und, date.fromisoformat(a.frm), date.fromisoformat(a.to), a.source, a.force)
    elif a.cmd == "info":
        info(p.root, a.und)
    elif a.cmd == "expiries":
        print("wrote", refresh_expiries(p.root))


if __name__ == "__main__":
    sys.exit(main())
