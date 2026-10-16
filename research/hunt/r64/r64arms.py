"""R64 arms: the Donchian and OOV trade lists, Solo 2.0's re-priced picks, and the random-entry twins.

Signals are the arms' own, read from the INDEX (PREREG section 1); only the exits `ex` change between variants. The
modules of the study whose stores are priced (r51cfg K, common C, engine E, r51data / r57data loaders) are passed in, so
this file imports no study config itself.
"""
from __future__ import annotations

import importlib.util
import sys

import numpy as np

# ------------------------------------------------------------------------------------------------ Donchian (R51 / R44)
D_FIRST = 5                                    # 09:20 (fam_r44.FIRST)


def donchian_trades(C, E, K, fam_r44, r51data, G, ex, lo, hi) -> list:
    """fam_r44.make('donchian20', 'B', money=1) with exits `ex`: 5-min index Donchian-20 signals, 1-ITM, entries
    09:20-15:10, one open a side, at most 2 a side a day, never on the index's expiry day."""
    last = K.row(15, 10)
    rows = []
    for und in K.UNDS:
        sig = fam_r44.signal_list(und, "donchian20")
        days = r51data.load(und)
        for d in C.days_in(und, lo, hi):
            D = days[d]
            if D.expiry or d not in sig:
                continue
            cands = []
            for b, side in sig[d]:
                er = 5 * (b + 1)
                if er < D_FIRST or er > last:
                    continue
                k = E.strike_for(D, und, side, er - 1, 1)
                if k is not None:
                    cands.append(dict(er=er, side=side, strike=k))
            rows += G.take(und, D, cands, ex, max_per_side=2)
    return rows


def donchian_twins(E, K, r51data, G, trades: list, ex, n: int, rng) -> np.ndarray:
    """R51's twins (common.twin_p / engine.twin_totals): same days, sides and counts a day, entry rows uniform in
    09:20-15:10, the same strike rule (1-ITM) and exits. Returns (n,) total nets."""
    last = K.row(15, 10)
    plan = {}
    for t in trades:
        plan[(t["und"], t["day"], t["side"])] = plan.get((t["und"], t["day"], t["side"]), 0) + 1
    tot = np.zeros(n)
    for (und, d, side), cnt in sorted(plan.items()):
        D = r51data.load(und)[d]
        rows = rng.integers(D_FIRST, last + 1, size=(n, cnt)).ravel()
        kmap = {r: E.strike_for(D, und, side, r - 1, 1) for r in np.unique(rows).tolist()}
        nets = G.twin_nets(und, D, [side] * len(rows), [kmap[int(r)] for r in rows], rows, ex)
        tot += nets.reshape(n, cnt).sum(axis=1)
    return tot


# ------------------------------------------------------------------------------------------------ OOV (R52 H37-10 / R57 S3)
_H37 = None


def h37(root):
    global _H37
    if _H37 is None:
        spec = importlib.util.spec_from_file_location("r64_h37_sigs", root / "research" / "hunt" / "h37" / "sigs.py")
        _H37 = importlib.util.module_from_spec(spec)
        sys.modules["r64_h37_sigs"] = _H37
        spec.loader.exec_module(_H37)
    return _H37


def prev_value_area(days: dict, root) -> dict:
    """{day: (POC, VAH, VAL) of the PREVIOUS session} (h37 sigs.profile_levels: 30-min TPO, 70%), as r57setups."""
    ds = sorted(days)
    M = np.stack([days[d].idx for d in ds])
    lev = h37(root).profile_levels(M[:, 1, :], M[:, 2, :], M[:, 3, :])
    return {d: (lev[i - 1] if i else np.full(3, np.nan)) for i, d in enumerate(ds)}


def oov_side(idx: np.ndarray, lev) -> str | None:
    """r57setups.s3_candidates' signal on a day's index minutes (4, 375) and the previous session's (POC, VAH, VAL):
    opens above VAH and the first 5-minute bar closes above VAH -> 'C'; both below VAL -> 'P'; else None."""
    if lev is None or not np.isfinite(lev).all():
        return None
    _, vah, val = lev
    ok = np.flatnonzero(np.isfinite(idx[0, :5]))
    okc = np.flatnonzero(np.isfinite(idx[3, :5]))
    if not len(ok) or not len(okc):
        return None
    op, c5 = idx[0, ok[0]], idx[3, okc[-1]]
    return "C" if (op > vah and c5 > vah) else "P" if (op < val and c5 < val) else None


def oov_trades(C, E, K, r51data, G, ex, lo, hi) -> list:
    """OovArm / R57 S3: entry 09:20 (row 5), 1-ITM from the 09:19 ATM, one a day per index, never on its expiry day;
    the five indices."""
    rows = []
    for und in K.UNDS:
        days = r51data.load(und)
        lev = prev_value_area(days, K.ROOT)
        for d in C.days_in(und, lo, hi):
            D = days[d]
            if D.expiry:
                continue
            side = oov_side(D.idx, lev.get(d))
            if side is None:
                continue
            k = E.strike_for(D, und, side, 4, 1)
            if k is None:
                continue
            r = G.run_day(und, D, [side], [k], [5], ex)[0]
            if r is not None:
                r["setup"], r["money"] = "S3", 1
                rows.append(r)
    return rows


# ------------------------------------------------------------------------------------------------ Solo 2.0 (R57 A2)
def solo2_reprice(E, K, load, S, G, picks: list, exits_of) -> list:
    """Each A2 pick (und, day, setup, side, strike, entry row) priced again with exits_of(setup); S1's index exits
    (r57setups.IDX_EXIT) are recomputed up to the variant's own square-off."""
    out = []
    for p in picks:
        D = load(p["und"])[p["day"]]
        ex = exits_of(p["setup"])
        fx = S.forced_row(D, p["er"], S.sgn(p["side"]), S.IDX_EXIT.get(p["setup"]), E.sq_row(D, ex))
        r = G.run_day(p["und"], D, [p["side"]], [p["strike"]], [p["er"]], ex, [fx])[0]
        if r is None:
            continue
        r["setup"], r["money"] = p["setup"], p["money"]
        out.append(r)
    return out


# ------------------------------------------------------------------------------------------------ R57-style twins
TW_FIRST, TW_LAST = 5, 285                     # 09:20 .. 14:00 (r57study.TWIN_WIN['A'])


def r57_twins(E, K, load, G, trades: list, spec_of, n: int, rng, forced_fn=None) -> np.ndarray:
    """r57setups.twin_totals on R64's engine: each trade replaced by one on the same day and index, entry row uniform in
    09:20-14:00, side by coin flip, the trade's strike rule (money) and exits; spec_of(trade) -> (Exits, index-exit spec
    or None), the index exits recomputed from the twin's entry by forced_fn(D, row, side, spec, sq). (n,) totals."""
    tot = np.zeros(n)
    for t in trades:
        und, d = t["und"], t["day"]
        D = load(und)[d]
        ex, spec = spec_of(t)
        sq = E.sq_row(D, ex)
        rows = rng.integers(TW_FIRST, TW_LAST + 1, size=n)
        sides = np.where(rng.integers(0, 2, size=n) == 1, "C", "P")
        strikes = [E.strike_for(D, und, s, int(r) - 1, t["money"]) for s, r in zip(sides, rows)]
        fx = None
        if forced_fn is not None and spec is not None:
            fx = [forced_fn(D, int(r), 1 if s == "C" else -1, spec, sq) for s, r in zip(sides, rows)]
        tot += G.twin_nets(und, D, list(sides), strikes, rows, ex, fx)
    return tot
