"""R54 late-minute liquidity (PREREG.md), measured on DESIGN expiry days only and before any P&L.

    python3 research/hunt/r54/r54liq.py        # -> results/liquidity.json (+ the table printed)

The lake has no bid / ask. Two proxies, per 5-minute bucket from 12:30 to 15:30, over every OTM contract (call above
spot, put below it, at that minute) last priced Rs 0.05-30 that traded in the previous 10 minutes:
  - the median 1-minute bar range (high - low) / close, over minutes that traded;
  - the share of minutes with no trade (no bar or zero volume).
Late multiplier for each bucket from 15:00: max(1, its median range / the median range over 14:00-14:59), rounded up to
the next 0.25. It multiplies the tick count of any fill in that bucket (r54lib.late_mult).
"""
from __future__ import annotations

import json
import math

import numpy as np

import r54cfg as K
import r54data as DD
import r54lib as LB
from r25data import C, H, L, V

LO_PX, HI_PX = 0.05, 30.0
LOOKBACK = 10


def day_obs(day, a0: int, a1: int) -> tuple[dict, dict]:
    """{bucket: [ranges]} and {bucket: [traded 0/1]} for one day, rows a0..a1 (bar start rows)."""
    rng: dict = {}
    trd: dict = {}
    ix = day.ix[:, 3]
    for (k, s), a in day.opts.items():
        cl = a[:, C]
        vol = np.nan_to_num(a[:, V])
        for r in range(a0, a1 + 1):
            sp = ix[r - 1] if r > 0 else math.nan
            if math.isnan(sp) or (s == "C" and k <= sp) or (s == "P" and k >= sp):
                continue
            lo = max(r - LOOKBACK, 0)
            past = np.nonzero(~np.isnan(cl[lo:r]) & (vol[lo:r] > 0))[0]
            if not len(past):
                continue
            last = cl[lo + past[-1]]
            if not (LO_PX <= last <= HI_PX):
                continue
            b = r - r % 5
            traded = (not math.isnan(cl[r])) and vol[r] > 0
            trd.setdefault(b, []).append(1 if traded else 0)
            if traded and cl[r] > 0 and not math.isnan(a[r, H]) and not math.isnan(a[r, L]):
                rng.setdefault(b, []).append((a[r, H] - a[r, L]) / cl[r])
    return rng, trd


def measure(days: list) -> dict:
    a0, a1 = LB.bar_start(K.LIQ_FROM), LB.bar_start(K.LIQ_TO)
    rng: dict = {}
    trd: dict = {}
    for d in days:
        r1, t1 = day_obs(d, a0, a1)
        for b, v in r1.items():
            rng.setdefault(b, []).extend(v)
        for b, v in t1.items():
            trd.setdefault(b, []).extend(v)
    b0, b1 = LB.bar_start(K.LATE_BASE[0]), LB.bar_start(K.LATE_BASE[1])
    base = [x for b, v in rng.items() if b0 <= b <= b1 for x in v]
    base_med = float(np.median(base)) if base else math.nan
    rows, late = [], {}
    for b in sorted(set(rng) | set(trd)):
        med = float(np.median(rng[b])) if rng.get(b) else math.nan
        nt = 1 - float(np.mean(trd[b])) if trd.get(b) else math.nan
        mult = 1.0
        if b >= LB.bar_start(K.LATE_FROM) and not math.isnan(med) and base_med > 0:
            mult = LB.round_up_quarter(max(1.0, med / base_med))
            late[b] = mult
        hh, mm = divmod(b + 9 * 60 + 15, 60)
        rows.append(dict(bucket=f"{hh:02d}:{mm:02d}", row=b, obs=len(rng.get(b, [])), median_range_pct=round(med * 100, 2),
                         no_trade_pct=round(nt * 100, 1), mult=mult))
    return dict(days=len(days), base="14:00-14:59", base_median_range_pct=round(base_med * 100, 2), buckets=rows,
                late_mult={str(k): v for k, v in late.items()})


def main():
    days = []
    for u in K.UNDS:
        days += DD.load(u, "design")
    out = measure(days)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "liquidity.json").write_text(json.dumps(out, indent=1) + "\n")
    print("design days", out["days"], "base median range %", out["base_median_range_pct"])
    for r in out["buckets"]:
        print(r)


if __name__ == "__main__":
    main()
