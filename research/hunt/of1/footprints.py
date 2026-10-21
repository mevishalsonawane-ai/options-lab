#!/usr/bin/env python3
"""OF1 footprints in the app's tick-zone recordings (PREREG §7). Descriptive only.

    python3 -I research/hunt/of1/footprints.py DAY_DIR [DAY_DIR ...] [--out results/footprints.csv]

DAY_DIR is one recorded day as research/data/decrypt_live.py extracts it (YYYY-MM-DD/ticks.jsonl.gz; the format is
android/ira-core/.../TickZone.kt: "I" rows = the watch-list [token, symbol, und, kind, strike, expiry], "T" rows = one
instrument's second: s, k, ltp, v (cumulative volume), tbq/tsq, b/a = five [price, qty, orders] levels, "G" = gaps).

Footprints, per instrument, point-in-time (each uses only seconds up to its own):
  large   a second's volume rise ABOVE the 99th percentile of the day's EARLIER seconds' rises (>= 200 of them); the
          side is the tick rule (price up = buy, down = sell, unchanged = the previous side)
  absorb  TickZone.absorbed on the minute: volume >= 2x the day's average minute so far (>= 5 minutes), >= 60% of it
          at one price, range <= max(Rs 0.10, 0.02% of the price); the side is the minute's delta sign (sellers
          absorbed by a bid = +1 when the delta was negative and the price held, i.e. the passive side)
  sweep   in one second the price crosses >= 3 of the previous second's 5 ask levels (buy sweep) or bid levels
          (sell sweep), with a volume rise
  iceberg the best bid (ask) price is unchanged for 60 s while the volume traded in that time is >= 2x the largest
          displayed quantity at that level, and the displayed quantity is restored (falls then rises back to >= 80% of
          its earlier size) >= 3 times; side +1 for a bid (a hidden buyer), -1 for an ask

What followed: the same index's FUTURE's last price 60, 300 and 900 s later minus at the footprint, in points, times
the side. Seconds past the end of the recording are NaN (never filled).
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

LARGE_Q, LARGE_MIN = 0.99, 200
ABSORB_SHARE, ABSORB_MULT, ABSORB_MIN_MIN, ABSORB_RANGE_MIN, ABSORB_RANGE_FRAC = 0.6, 2.0, 5, 0.10, 0.0002
SWEEP_LEVELS = 3
ICE_WINDOW, ICE_MULT, ICE_REFILLS, ICE_RESTORE = 60, 2.0, 3, 0.8
HORIZONS = (60, 300, 900)


def read_ticks(path: Path) -> tuple[pd.DataFrame, pd.DataFrame]:
    """ticks.jsonl.gz -> (instruments, seconds). A line that is not JSON is skipped (counted in attrs['bad'])."""
    inst, rows, bad = {}, [], 0
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        for ln in fh:
            try:
                r = json.loads(ln)
            except json.JSONDecodeError:
                bad += 1
                continue
            t = r.get("t")
            if t == "I":
                for tok, sym, und, kind, strike, expiry in r.get("list", []):
                    inst[int(tok)] = {"token": int(tok), "symbol": sym, "und": und, "kind": kind, "strike": strike, "expiry": expiry}
            elif t == "T":
                rows.append(r)
    I = pd.DataFrame(list(inst.values()))
    S = pd.DataFrame(rows)
    S.attrs["bad"] = bad
    return I, S


def _prep(s: pd.DataFrame) -> pd.DataFrame:
    s = s.sort_values("s").drop_duplicates("s", keep="last").reset_index(drop=True)
    s["dv"] = s["v"].astype(float).diff()
    s.loc[s.dv < 0, "dv"] = np.nan                      # a reset of the cumulative counter is not a trade
    s["gap"] = s["s"].diff() != 1                       # the rise spans several seconds: it is not one second's size
    side = np.zeros(len(s))
    prev = 0
    lp = s.ltp.to_numpy(float)
    for i in range(len(s)):
        if i > 0:
            prev = 1 if lp[i] > lp[i - 1] else (-1 if lp[i] < lp[i - 1] else prev)
        side[i] = prev
    s["side"] = side
    return s


def large_prints(s: pd.DataFrame) -> pd.DataFrame:
    """Seconds whose volume rise is above the 99th percentile of the EARLIER seconds' rises (point-in-time)."""
    s = _prep(s)
    out = []
    seen: list[float] = []
    for r in s.itertuples(index=False):
        dv = r.dv
        if not (dv > 0) or r.gap:
            continue
        if len(seen) >= LARGE_MIN:
            thr = float(np.quantile(np.asarray(seen), LARGE_Q, method="inverted_cdf"))
            if dv > thr and r.side != 0:                # strictly above, as TickZone.Flow
                out.append({"s": r.s, "kind": "large", "side": int(r.side), "size": dv, "thr": thr, "px": r.ltp})
        seen.append(dv)
    return pd.DataFrame(out)


def minutes(s: pd.DataFrame) -> pd.DataFrame:
    s = _prep(s)
    s["m"] = (s["s"] // 60) * 60
    g = s.groupby("m")
    m = pd.DataFrame({"o": g.ltp.first(), "h": g.ltp.max(), "l": g.ltp.min(), "c": g.ltp.last(), "vol": g.dv.sum(min_count=1)})
    m["delta"] = (s.dv * s.side).groupby(s.m).sum()
    top = s.groupby(["m", "ltp"]).dv.sum().groupby(level=0).max()
    m["top"] = top
    return m.reset_index()


def absorption(s: pd.DataFrame) -> pd.DataFrame:
    """TickZone.absorbed on each minute, with the day's average minute SO FAR (earlier minutes only)."""
    m = minutes(s)
    out = []
    vols: list[float] = []
    for r in m.itertuples(index=False):
        v = 0.0 if np.isnan(r.vol) else float(r.vol)
        if len(vols) >= ABSORB_MIN_MIN and v > 0:
            avg = float(np.mean(vols))
            ok = (avg > 0 and v >= ABSORB_MULT * avg and r.top / v >= ABSORB_SHARE
                  and r.h - r.l <= max(ABSORB_RANGE_MIN, ABSORB_RANGE_FRAC * r.c) + 1e-9)
            if ok and r.delta != 0:
                # aggressive sellers met a passive buyer (delta < 0, price held) -> the absorber is a buyer: side +1
                out.append({"s": int(r.m) + 59, "kind": "absorb", "side": int(-np.sign(r.delta)), "size": v, "px": r.c})
        vols.append(v)
    return pd.DataFrame(out)


def _levels(x) -> list[tuple[float, float]]:
    if not isinstance(x, list):
        return []
    return [(float(p), float(q)) for p, q, *_ in x if p and p > 0]


def sweeps(s: pd.DataFrame) -> pd.DataFrame:
    """Seconds whose price went through >= 3 of the previous second's ask (bid) levels, with volume."""
    s = _prep(s)
    out = []
    prev = None
    for r in s.itertuples(index=False):
        if prev is not None and not r.gap and r.dv and r.dv > 0:
            asks = _levels(getattr(prev, "a", None))
            bids = _levels(getattr(prev, "b", None))
            up = sum(1 for p, _ in asks if r.ltp >= p)
            dn = sum(1 for p, _ in bids if r.ltp <= p)
            if up >= SWEEP_LEVELS:
                out.append({"s": r.s, "kind": "sweep", "side": 1, "size": r.dv, "levels": up, "px": r.ltp})
            elif dn >= SWEEP_LEVELS:
                out.append({"s": r.s, "kind": "sweep", "side": -1, "size": r.dv, "levels": dn, "px": r.ltp})
        prev = r
    return pd.DataFrame(out)


def icebergs(s: pd.DataFrame) -> pd.DataFrame:
    """Best level held at one price for ICE_WINDOW seconds while trading >= ICE_MULT x its largest displayed size, with
    >= ICE_REFILLS refills. One footprint per (side, price) run, stamped at the second the rule is first met."""
    s = _prep(s)
    out = []
    for side, col in ((1, "b"), (-1, "a")):
        run_px, start, qtys, vol, refills, low, fired = None, None, [], 0.0, 0, None, False
        for r in s.itertuples(index=False):
            lv = _levels(getattr(r, col, None))
            if not lv or r.gap:
                run_px = None
                continue
            px, q = lv[0]
            if px != run_px:
                run_px, start, qtys, vol, refills, low, fired = px, r.s, [q], 0.0, 0, None, False
                continue
            if r.dv and r.dv > 0 and r.ltp == px:
                vol += r.dv
            peak = max(qtys) if qtys else q
            if low is None and q < ICE_RESTORE * peak:
                low = q
            elif low is not None and q >= ICE_RESTORE * peak:
                refills += 1
                low = None
            qtys.append(q)
            if (not fired and r.s - start >= ICE_WINDOW and vol >= ICE_MULT * max(qtys) and refills >= ICE_REFILLS):
                out.append({"s": r.s, "kind": "iceberg", "side": side, "size": vol, "px": px, "refills": refills})
                fired = True
    return pd.DataFrame(out)


def outcomes(events: pd.DataFrame, fut: pd.DataFrame, horizons=HORIZONS) -> pd.DataFrame:
    """Signed future move (points) `h` seconds after each event: last future price at or before s + h, minus at s.
    NaN when the recording ends before s + h or no future price is known at s."""
    if events.empty:
        return events
    f = fut.sort_values("s").drop_duplicates("s", keep="last")
    sec, px = f["s"].to_numpy(), f["ltp"].to_numpy(float)
    ev = events.copy()

    def at(t):
        i = np.searchsorted(sec, t, side="right") - 1
        return np.where(i >= 0, px[np.clip(i, 0, None)], np.nan)

    p0 = at(ev.s.to_numpy())
    for h in horizons:
        t = ev.s.to_numpy() + h
        v = at(t) - p0
        v[t > sec[-1]] = np.nan
        ev[f"fwd{h // 60}m"] = v * ev.side.to_numpy()
    return ev


def detect_day(day_dir: Path) -> pd.DataFrame:
    I, S = read_ticks(day_dir / "ticks.jsonl.gz")
    if S.empty or I.empty:
        return pd.DataFrame()
    S["k"] = S["k"].astype(int)
    out = []
    for und in sorted(I.und.dropna().unique()):
        toks = I[(I.und == und)]
        ft = toks[toks.kind == "FUT"].token.tolist()
        if not ft:
            continue
        fut = S[S.k == ft[0]]
        for tok in toks.token:
            s = S[S.k == tok]
            if len(s) < 300:
                continue
            ev = pd.concat([large_prints(s), absorption(s), sweeps(s), icebergs(s)], ignore_index=True)
            if ev.empty:
                continue
            ev = outcomes(ev, fut)
            meta = I[I.token == tok].iloc[0]
            ev["day"], ev["und"], ev["inst"], ev["inst_kind"] = day_dir.name, und, meta.symbol, meta.kind
            out.append(ev)
    return pd.concat(out, ignore_index=True) if out else pd.DataFrame()


def summary(ev: pd.DataFrame) -> pd.DataFrame:
    if ev.empty:
        return ev
    cols = [c for c in ev.columns if c.startswith("fwd")]
    g = ev.groupby(["und", "inst_kind", "kind"])
    s = g[cols].agg(["count", "mean", "median"])
    s.columns = [f"{a}_{b}" for a, b in s.columns]
    return s.reset_index()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("days", nargs="+")
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent / "results" / "footprints.csv"))
    a = ap.parse_args()
    ev = pd.concat([detect_day(Path(d)) for d in a.days], ignore_index=True)
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    ev.to_csv(a.out, index=False)
    print(summary(ev).to_string(index=False) if not ev.empty else "no footprints")
    print(f"{len(ev)} footprints on {len(a.days)} day(s): DESCRIPTIVE ONLY, no trading claim")
    return 0


if __name__ == "__main__":
    sys.exit(main())
