"""R106 walks (PREREG section 2), pure: one day of an arm, all its units together in time order.

A unit is (key, cands, how): key (und, day, book|None); cands in time order (dicts with er = the signal's entry row,
side, ...); how = the arm's taking rule, as R101:
  'book'   one position a book (Liquidity: a unit is one index-day-book)
  'side2'  one open position a side, at most 2 FRESH fills a side a day (Donchian / Breadth Donchian)
  'one'    the first filled fresh candidate of the day only (OOV)
With re=False and goal=False the result is exactly R101's walk unit by unit (test_r106.py checks it).

Re-entry (RE5): after a taken trade exits at its target, `make_re(unit, cand, trade)` may return a new candidate (the
same signal bought again at the next minute) or None (out of hours / signal dead); it is priced by `price_re` and
queued; fills per index-day are capped at `max_fills`. A re-entry never counts as a fresh fill.
Day goal: no new entry once `day_max` trades were taken that day or the trades already closed (exit row < entry row)
net >= `goal`.
"""
from __future__ import annotations

import heapq


def walk_day(units: list, priced: list, re: bool = False, make_re=None, price_re=None, max_fills: int = 5,
             goal: float | None = None, day_max: int | None = None, max_per_side: int = 2) -> list:
    """units: [(key, cands, how)]; priced[g][j]: the trade dict of cands[j] (None = no fill). Returns taken trades (dicts
    with the candidate's keys added, 'er_sig' = the signal's entry row, 're' = re-entry depth)."""
    heap = []
    for g, (key, cands, how) in enumerate(units):
        last = -1
        for j, c in enumerate(cands):
            assert c["er"] >= last, "candidates must be in time order"
            last = c["er"]
            heap.append((c["er"], g, j, 0, c, priced[g][j]))
    heapq.heapify(heap)
    seq = len(heap) + 1
    busy, n_side, fills, one_done = {}, {}, {}, set()
    taken, closed = [], []
    while heap:
        er, g, j, depth, c, r = heapq.heappop(heap)
        if r is None:
            continue
        key, _, how = units[g]
        und = key[0]
        if how == "book":
            bk = (g, c.get("book"))
        elif how == "side2":
            bk = (g, c["side"])
        else:
            bk = (g,)
        if er <= busy.get(bk, -1):
            continue
        if depth == 0:
            if how == "side2" and n_side.get(bk, 0) >= max_per_side:
                continue
            if how == "one" and g in one_done:
                continue
        if re and fills.get(und, 0) >= max_fills:
            continue
        if day_max is not None and len(taken) >= day_max:
            continue
        if goal is not None and sum(n for x, n in closed if x < er) >= goal:
            continue
        busy[bk] = r["xr"]
        if depth == 0:
            n_side[bk] = n_side.get(bk, 0) + 1
            one_done.add(g)
        fills[und] = fills.get(und, 0) + 1
        t = dict(r)
        t.update({k: v for k, v in c.items() if k not in t and k != "fx"})
        t["er_sig"] = c["er"]
        t["re"] = depth
        t["_g"] = g
        taken.append(t)
        closed.append((r["xr"], r["net"]))
        if re and r.get("why") == "target" and make_re is not None:
            nc = make_re(units[g], c, r)
            if nc is not None:
                nr = price_re(units[g], nc)
                heapq.heappush(heap, (nc["er"], g, seq, depth + 1, nc, nr))
                seq += 1
    taken.sort(key=lambda t: (t["_g"], t["er_sig"], t["re"]))
    for t in taken:
        t.pop("_g", None)
    return taken


def by_day(units: list) -> dict:
    """{day: [unit indices]} for units keyed (und, day, ...)."""
    out = {}
    for g, u in enumerate(units):
        out.setdefault(u[0][1], []).append(g)
    return out
