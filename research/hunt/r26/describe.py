"""R26 descriptive check of the claim itself (PREREG.md, "Descriptive check"). No gate; all 770 expiries.

    python3 research/hunt/r26/describe.py      # -> results/describe.json

1. Walls: how often the 13:00 wall is the outermost stored strike (the true wall may lie beyond ATM+-10).
2. Breaks: per expiry-side, did spot break the wall in 13:45-14:30, hold 16 closes, with the OI fall, the volume spike,
   price + summed OI, and the full trigger (P without the price filter).
3. Blasts and OI: the ATM+1 CE / ATM-1 PE at the 13:45 close; BLAST = a confirmed high before 15:05 >= 4x its 13:45
   close. The wall's OI change from 13:45 to the first 4x minute (blasts) or to 15:00 (the rest), and the strike's own
   OI change. Links to R25 (jackpots come with OI rising).
"""
from __future__ import annotations

import collections
import json
import math

import numpy as np

import r26cfg as K26
import r25lib as R
import r26lib as L
from r25data import N, minute
from r26study import all_days

END = minute(15, 4)               # bars starting <= 15:04 ("before 15:05")
AT_1500 = R.close_row(15, 0)


def blast(day, sgn: int) -> dict | None:
    """The ATM+-1 option at the 13:45 close and whether it reached 4x (confirmed) before 15:05."""
    t0 = L.FIRST
    pk = L.pick(day, replace_filt(), sgn, t0)
    if pk is None:
        return None
    k, p0 = pk
    side = "C" if sgn > 0 else "P"
    a = day.opts[(k, side)]
    first4, best = None, p0
    for r in range(t0 + 1, END + 1):
        hi = a[r, 1]
        if math.isnan(hi):
            continue
        conf = R.confirmed(a, r)
        if math.isnan(conf):
            continue
        v = min(hi, conf)
        best = max(best, v)
        if first4 is None and v >= 4 * p0 - R.EPS:
            first4 = r
    return {"strike": k, "p0": p0, "mult": best / p0, "first4": first4}


def replace_filt():
    from dataclasses import replace
    return replace(L.P, filt=False)


def pct(a: float, b: float) -> float:
    return (b / a - 1) * 100 if a and not math.isnan(a) and not math.isnan(b) and a > 0 else math.nan


def oi_class(ch_pct: float, theta: float) -> str:
    if math.isnan(ch_pct):
        return "no OI"
    if ch_pct <= -theta * 100:
        return "falling >= theta"
    if ch_pct < 0:
        return "falling < theta"
    return "rising or flat"


def main():
    days = all_days()
    rule = replace_filt()
    walls = collections.Counter()
    funnel = collections.Counter()
    funnel_idx = collections.defaultdict(collections.Counter)
    bl = collections.defaultdict(collections.Counter)
    pre = collections.defaultdict(collections.Counter)
    own = collections.defaultdict(collections.Counter)
    blast_days = []
    for d in days:
        ch = L.chain(d)
        th = L.THETA[d.und]
        for sgn in (1, -1):
            side = "C" if sgn > 0 else "P"
            w, edge = ch.wall(side, L.ANCHOR)
            walls["n"] += 1
            walls["outermost" if edge else "inside"] += 1 if not math.isnan(w) else 0
            walls["no wall"] += 1 if math.isnan(w) else 0
            seen = set()
            for t in range(L.FIRST, L.LAST + 1):
                c = L.conditions(d, rule, sgn, t, ch)
                s = ch.close
                if not math.isnan(c["wall"]) and not math.isnan(s[t]) and (s[t] - c["wall"]) * sgn > 0:
                    seen.add("1 spot beyond the wall (a close)")
                if c["break"]:
                    seen.add("2 broke and held 16 closes")
                    if c["oi_fall"]:
                        seen.add("3 + wall OI down >= theta")
                        if c["vol"]:
                            seen.add("4 + volume spike")
                            if c["price"] and c["sum_oi"]:
                                seen.add("5 + price and summed OI (full trigger)")
            for k in seen:
                funnel[k] += 1
                funnel_idx[d.und][k] += 1
            funnel["0 expiry-sides"] += 1
            funnel_idx[d.und]["0 expiry-sides"] += 1
            b = blast(d, sgn)
            if b is None:
                continue
            is_b = b["first4"] is not None
            end = b["first4"] if is_b else AT_1500
            grp = "blast" if is_b else "no blast"
            wp = ch.oi_path(side, w) if not math.isnan(w) else np.full(N, np.nan)
            bl[grp][oi_class(pct(wp[L.FIRST], wp[end]), th)] += 1
            # point-in-time at 13:45 (what a trader could know before the move): the wall's OI vs its peak since 12:00
            pk = np.nanmax(wp[L.NOON: L.FIRST + 1]) if np.isfinite(wp[L.NOON: L.FIRST + 1]).any() else math.nan
            st = oi_class(pct(pk, wp[L.FIRST]), th)
            pre[st]["n"] += 1
            pre[st]["blast"] += int(is_b)
            bl[grp]["n"] += 1
            op = ch.oi_path(side, b["strike"])
            own[grp][oi_class(pct(op[L.FIRST], op[end]), th)] += 1
            own[grp]["n"] += 1
            if is_b:
                blast_days.append(dict(und=d.und, day=str(d.day), side="CE" if sgn > 0 else "PE", strike=b["strike"],
                                       p0=b["p0"], mult=round(b["mult"], 1), wall=w,
                                       wall_oi_pct=round(pct(wp[L.FIRST], wp[end]), 1),
                                       own_oi_pct=round(pct(op[L.FIRST], op[end]), 1), trigger="5 + price and summed OI (full trigger)" in seen,
                                       broke="2 broke and held 16 closes" in seen))
    out = {"walls": dict(walls), "funnel": dict(sorted(funnel.items())),
           "funnel_by_index": {u: dict(sorted(v.items())) for u, v in funnel_idx.items()},
           "blast_wall_oi": {k: dict(v) for k, v in bl.items()}, "blast_own_oi": {k: dict(v) for k, v in own.items()},
           "wall_oi_at_1345_vs_blast": {k: dict(v, rate_pct=round(100 * v["blast"] / v["n"], 1)) for k, v in pre.items()},
           "blasts": blast_days}
    (K26.RESULTS / "describe.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps({k: v for k, v in out.items() if k != "blasts"}, indent=1))
    print("blasts", len(blast_days), "with the full trigger", sum(b["trigger"] for b in blast_days),
          "after a held break", sum(b["broke"] for b in blast_days))


if __name__ == "__main__":
    main()
