"""R81 study (PREREG.md): Liquidity 15+5 exits and trade management (X1-X11 against the own exits B0), on the pooled arm
(BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5, as R70 / R76) and on each of five indices (NIFTY and SENSEX 15+5 too).
Research only. 18 Oct 2026.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build BANKNIFTY FINNIFTY MIDCPNIFTY NIFTY SENSEX
    python research/hunt/r81/r81study.py design
    python research/hunt/r81/r81study.py final                 # the ONE final read (refuses a second)
    python research/hunt/r81/r81study.py summary

B0's trades are made once (fam_liq, exactly as R76); every variant re-prices the same trades from the same entry row
(X6 / X7 add trades / lots). Trades go to $R81_OUT (never git); results/ holds small JSON only.
"""
from __future__ import annotations

import json
import os
import pickle
import subprocess
import sys
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
for _d in ("r64", "r65", "r70", "r76"):
    sys.path.insert(0, str(HERE.parent / _d))
sys.path.insert(0, str(HERE))
import r64cfg as R64  # noqa: E402
import r64study as P  # noqa: E402  (stats, periods, bh, paired_boot)
import r65study as Q  # noqa: E402  (exit_twin_totals, p_of, forced_rows)
import r70study as R70  # noqa: E402  (LIQ_BOOKS, LIQ_IDX_STOP_MIDCP, _fields, _price)
import r81rules as RU  # noqa: E402

OUT = Path(os.environ.get("R81_OUT", R64.CACHE_ROOT / "r81")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
POOLED = ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
BOOKS = dict(R70.LIQ_BOOKS, NIFTY=(15, 5), SENSEX=(15, 5))          # NIFTY / SENSEX: h4 comps BOOKS_EXT
IDX_STOP_EXTRA = {"MIDCPNIFTY": R70.LIQ_IDX_STOP_MIDCP, "NIFTY": 15.0, "SENSEX": 50.0}   # h4 comps IDX_STOP_EXT
UNDS = ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTY", "SENSEX")
TESTS = ("X1", "X2", "X3", "X4", "X5", "X6", "X7", "X8", "X9", "X10", "X11")
NAME = {"B0": "own exits (today)", "X1": "NOPS: no -15% premium stop", "X2": "SWEEPSTOP: index stop beyond the breaking bar",
        "X3": "ATRTRAIL: index chandelier 1.0 / 1.5 ATR", "X4": "NOPROG: index time stop, 10 min / 0.25 ATR",
        "X5": "SCALE: half at +15%, runner to breakeven", "X6": "REENTRY: once, after a stop, on a 5-min re-close",
        "X7": "PYRAMID: +1 lot at 50% to target", "X8": "EOD1430: square-off 14:30",
        "X9": "EOD1520: square-off 15:20", "X10": "OVERNIGHT: hold +20% runners, GTT at the fill",
        "X11": "BESTRUCT: breakeven after a 5-min close 0.5 ATR past the level"}
TWIN_KIND = {"X3": "exit", "X4": "exit", "X8": "exit", "X11": "exit", "X6": "reentry", "X7": "pyramid",
             "X1": "flip", "X2": "flip", "X5": "flip", "X9": "flip", "X10": "flip"}
B0_R76 = {"design": (257249, 881), "final": (11155, 357)}             # R76 / R70, pooled
TWINS, SEED, BOOT = 500, 81, 2000
GATE_P, GATE_Q = 0.10, 0.10


# ================================================================================================ pure
def changed(b0: list, v: list) -> list:
    assert len(b0) == len(v)
    return [i for i, (x, y) in enumerate(zip(b0, v)) if abs(y["net"] - x["net"]) > 1e-6]


def deltas(b0: list, v: list, added: list | None = None) -> list:
    """Per-trade gains over B0: the changed trades' differences, plus every added trade / lot's net. Pure."""
    d = [v[i]["net"] - b0[i]["net"] for i in changed(b0, v)]
    return d + [a["net"] for a in (added or [])]


def day_diff(b0: list, v: list) -> np.ndarray:
    a, b = defaultdict(float), defaultdict(float)
    for t in b0:
        b[t["day"]] += t["net"]
    for t in v:
        a[t["day"]] += t["net"]
    return np.array([a[x] - b[x] for x in sorted(set(a) | set(b))])


def decide(cell: dict) -> dict:
    """PREREG section 3. cell: net of B0 and the variant in design / wf / final, twin p, q, top-5 gains left."""
    b, v = cell["b0"], cell["v"]
    beats = v["wf"] > b["wf"] and v["final"] > b["final"] and v["design"] >= b["design"]
    top5 = cell["top5_design"] > 0 and cell["top5_final"] > 0
    twin = cell.get("twin_p") is not None and cell["twin_p"] < GATE_P
    q = cell.get("q") is not None and cell["q"] < GATE_Q
    fails = [s for s, ok in (("not ahead of B0 in design / walk-forward / final", beats), ("twin p >= 0.10", twin),
                             ("BH q >= 0.10", q), ("carried by its top 5 trades", top5)) if not ok]
    harmless = all(v[k] >= b[k] for k in ("design", "wf", "final"))
    return dict(passed=not fails, fails=fails, harmless=harmless)


# ================================================================================================ helpers
def _save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name):
    p = OUT / f"{name}.pkl"
    return pickle.loads(p.read_bytes()) if p.exists() else None


def _env():
    os.environ["R51_CLEAN"] = "1"
    sys.path.insert(0, str(HERE.parent / "r51"))
    import r51cfg as K
    import common as C
    import engine as E
    import fam_liq
    import r51data
    import r64engine as G
    import r76engine as X
    assert K.CLEAN
    for u, s in IDX_STOP_EXTRA.items():
        fam_liq.IDX_STOP.setdefault(u, s)
    return K, C, E, fam_liq, r51data, G, X


def _plain(phase):
    """B0 trades for all five indices, exactly as R70 / R76 made them (fam_liq per book, keeping e0 / fx0)."""
    K, C, E, fam_liq, r51data, G, X = _env()
    if phase == "final":
        C.open_final()
        lo, hi = R64.FINAL_START, K.END
    else:
        lo, hi = R64.START, R64.DESIGN_END
    orig = E.run_day

    def rec(und, D, sides, strikes, entries, ex, forced=None):
        res = orig(und, D, sides, strikes, entries, ex, forced)
        fx = [-1] * len(entries) if forced is None else list(forced)
        for i, o in enumerate(res):
            if o is not None:
                o["e0"], o["fx0"] = int(entries[i]), int(fx[i])
        return res
    E.run_day = rec
    plain = []
    try:
        for und in UNDS:
            T = fam_liq.make(und, BOOKS[und]).run(lo, hi)
            plain += T.to_dict("records") if len(T) else []
    finally:
        E.run_day = orig
    for t in plain:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    plain.sort(key=lambda t: (t["day"], t["und"], t["er"]))
    return plain, fam_liq.EX


class Ctx:
    """Per-trade context: the day, the previous sessions (for the ATR), the entry index price, the level, the ATR."""

    def __init__(self, env, t):
        K, C, E, fam_liq, r51data, G, X = env
        days = r51data.load(t["und"])
        self.D = days[t["day"]]
        keys = getattr(r51data, "_R81_KEYS", {}).get(t["und"])
        if keys is None:
            keys = sorted(days)
            r51data.__dict__.setdefault("_R81_KEYS", {})[t["und"]] = keys
        i = keys.index(t["day"])
        self.prev = [days[k].idx for k in keys[max(i - 2, 0):i]]
        self.next = days[keys[i + 1]] if i + 1 < len(keys) else None
        self.ef = int(t["er"])
        self.sgn = int(t["sgn"])
        self.i0 = X.entry_index(self.D.idx, self.ef, t["close"])
        self.istop = fam_liq.IDX_STOP[t["und"]]
        self.level = float(t["idx_stop"]) + self.sgn * self.istop
        self.atr = RU.atr5(self.prev, self.D.idx, int(t["e0"]))
        st = E.stack(t["und"], self.D)
        ci = st.idx(t["side"], t["strike"])
        self.S = st.S[ci] if ci >= 0 else None


def _run1(env, t, ex, fx, e0=None, side=None, strike=None):
    K, C, E, fam_liq, r51data, G, X = env
    D = r51data.load(t["und"])[t["day"]]
    r = G.run_day(t["und"], D, [side or t["side"]], [t["strike"] if strike is None else strike],
                  [t["e0"] if e0 is None else e0], ex, [fx])[0]
    if r is not None and e0 is None:
        r.update({k: t[k] for k in ("e0", "fx0", "sgn", "close", "target", "book", "idx_stop", "bar_exit") if k in t})
    return r


def _min_fx(a: int, b: int) -> int:
    xs = [x for x in (a, b) if x >= 0]
    return min(xs) if xs else -1


def _overnight(env, t, b, cx):
    """X10 for one B0 trade b (its priced dict): the held-overnight version, or None to keep B0."""
    K, C, E, fam_liq, r51data, G, X = env
    if b["why"] != "squareoff" or cx.S is None or cx.next is None:
        return None
    sq = E.sq_row(cx.D, t["ex"])
    c = cx.S[3, sq - 1]
    if not (np.isfinite(c) and c >= (1 + RU.OVN_GAIN) * b["E"]):
        return None
    C2 = cx.next.contract(t["side"], t["strike"])
    if C2 is None:
        return None
    px, row, how = RU.overnight_px(C2, b["E"])
    if row < 0:
        return None
    qty = b["qty"]
    vol = float(E._vol5(C2[4].astype(np.float64), max(row - 1, 0), qty))
    if how == "gtt":
        rx = max(E._r2(px * E.SLIP[2]) - 2.0 * E.SLIP[3] * E._ticks(vol) * K.TICK, 0.0)
    else:
        rx = max(E._r2(px * E.SLIP[1]) - E.SLIP[3] * E._ticks(vol) * K.TICK, 0.0)
    ch = float(E.charges(t["und"], np.array([b["rE"]]), np.array([rx]), qty, cx.D.d)[0])
    return dict(b, X=float(px), rX=float(rx), why="overnight_" + how, net=float((rx - b["rE"]) * qty - ch),
                gross=float((px - b["E"]) * qty), ret=float(px / b["E"] - 1.0), xr=-1)


def _bestruct(env, t, ex, cx, b):
    """X11: R76's engine with this rule's per-minute flags and a +1% lock."""
    K, C, E, fam_liq, r51data, G, X = env
    if cx.S is None:
        return b
    trg = RU.struct_flags(cx.D.idx, cx.S, cx.ef, cx.sgn, cx.level, cx.atr, b["E"])
    if not trg.any():
        return b
    sq = E.sq_row(cx.D, ex)
    qty = b["qty"]
    a, bb, rE, rX, er, xr, rs = X.simulate(cx.S, int(t["e0"]), int(sq), int(t["fx0"]), ex.stop, ex.target, ex.lock_trig,
                                           ex.lock_keep, ex.lock_trail, ex.steps_arr(), ex.tmax, ex.need, ex.close_stop,
                                           K.TICK, qty, 0.0, trg, RU.STRUCT_KEEP, *E.SLIP)
    ch = float(E.charges(t["und"], np.array([rE]), np.array([rX]), qty, cx.D.d)[0])
    return dict(b, E=float(a), X=float(bb), rE=float(rE), rX=float(rX), er=int(er), xr=int(xr), why=E.REASONS[int(rs)],
                net=float((rX - rE) * qty - ch), gross=float((bb - a) * qty), ret=float(bb / a - 1.0))


def _reentry(env, t, b, cx, b0_same_book: list, row=None):
    """X6: the re-entry trade after B0 trade b (or at a given entry row for a twin), or None."""
    K, C, E, fam_liq, r51data, G, X = env
    idx = cx.D.idx
    if row is None:
        if not _stopped(env, t, b, cx):
            return None
        row = RU.reentry_row(idx, b["xr"], cx.sgn, cx.level, float(t["target"]), cx.istop, fam_liq.LAST)
        if row < 0:
            return None
    c = idx[3, row - 1]
    if not np.isfinite(c):
        return None
    strike = float(fam_liq.H4.atm(c, K.STEP[t["und"]]) - cx.sgn * K.STEP[t["und"]])
    fb = RU.failed_break_row(idx, row, cx.sgn, cx.level)
    fx = RU.forced(idx, row, cx.sgn, float(t["idx_stop"]), float(t["target"]), fb, K.NROWS)
    r = _run1(env, t, fam_liq.EX, fx, e0=row, strike=strike)
    if r is None:
        return None
    if any(row <= o["e0"] <= r["xr"] for o in b0_same_book if o["e0"] > b["e0"]):
        return None                      # would overlap the book's next B0 trade
    r.update(book=t["book"], reentry=True, e0=row, fx0=fx)
    return r


def _stopped(env, t, b, cx) -> bool:
    """B0 trade b left on its premium stop or its index stop."""
    if b["why"] == "stop":
        return True
    if b["why"] != "rule_exit":
        return False
    h = RU.index_stop_hit(cx.D.idx, int(t["e0"]), cx.sgn, float(t["idx_stop"]))
    return h >= 0 and h + 1 == b["xr"]


def _pyramid(env, t, b, cx, row=None):
    """X7: the added lot (bought at the trigger row + 1, or at `row` for a twin), sold with the base trade."""
    K, C, E, fam_liq, r51data, G, X = env
    if cx.S is None or b["xr"] < 0:
        return None
    if row is None:
        m = RU.pyramid_row(cx.D.idx, cx.S, cx.ef, b["xr"], cx.sgn, cx.i0, float(t["target"]), b["E"])
        if m < 0:
            return None
        row = m + 1
    if row >= b["xr"]:
        return None
    # the add is sold with the base: at the base's exit row when the base sold at that row's open (index exits,
    # square-off); after a stop / time exit inside a minute, at the NEXT minute's open (no look-ahead into the minute)
    fx = int(b["xr"]) if b["why"] in ("rule_exit", "squareoff") else int(b["xr"]) + 1
    ex_add = E.Exits(stop=fam_liq.EX.stop)
    r = _run1(env, t, ex_add, fx if fx < K.NROWS else -1, e0=row)
    if r is None:
        return None
    r.update(book=t["book"], add=True)
    return r


# ================================================================================================ pricing
def price_all(phase: str):
    env = _env()
    K, C, E, fam_liq, r51data, G, X = env
    plain, ex_b0 = _plain(phase)
    load = r51data.load
    b0 = R70._price(G, load, plain, ex_b0)
    assert len(b0) == len(plain) and all(abs(a["net"] - p["net"]) < 1e-6 for a, p in zip(b0, plain))
    pooled = [t for t in b0 if t["und"] in POOLED]
    want = B0_R76[phase]
    got = (round(sum(t["net"] for t in pooled)), len(pooled))
    print(phase, "B0 pooled", got, "R76", want, flush=True)
    assert got == want, f"B0 does not reproduce R76: {got} vs {want}"
    for t, b in zip(plain, b0):
        b.update({k: t[k] for k in ("sgn", "close", "target", "book", "idx_stop", "bar_exit") if k in t})
        t["ex"] = ex_b0
    F = R70._fields(ex_b0)
    ex = {"X1": E.Exits(**dict(F, stop=-1.0)), "X5a": E.Exits(**dict(F, target=RU.SCALE_R)),
          "X5b": E.Exits(**dict(F, steps=tuple(F.get("steps", ())) + ((RU.SCALE_R, 0.0),))),
          "X8": E.Exits(**dict(F, sq_row=K.row(14, 30))), "X9": E.Exits(**dict(F, sq_row=K.row(15, 20)))}
    V = {v: [] for v in TESTS}
    added = {"X6": [], "X7": []}
    by_book = defaultdict(list)
    for b in b0:
        by_book[(b["und"], b["day"], b["book"])].append(b)
    info = defaultdict(int)
    for t, b in zip(plain, b0):
        cx = Ctx(env, t)
        info["atr_nan"] += int(not np.isfinite(cx.atr))
        # X1, X8, X9: plain re-pricing with other exits
        for v in ("X1", "X8", "X9"):
            V[v].append(_run1(env, t, ex[v], int(t["fx0"])))
        # X2: the index stop beyond the breaking bar
        st2 = RU.sweep_stop(cx.D.idx, int(t["e0"]), int(t["book"]), cx.sgn, float(t["idx_stop"]))
        fx_chk = RU.forced(cx.D.idx, int(t["e0"]), cx.sgn, float(t["idx_stop"]), float(t["target"]), int(t["bar_exit"]),
                           K.NROWS)
        assert fx_chk == int(t["fx0"]), (fx_chk, t["fx0"])
        fx2 = RU.forced(cx.D.idx, int(t["e0"]), cx.sgn, st2, float(t["target"]), int(t["bar_exit"]), K.NROWS)
        V["X2"].append(b if fx2 == int(t["fx0"]) else _run1(env, t, ex_b0, fx2))
        # X3, X4: index exits -> forced rows
        m3 = RU.chandelier_row(cx.D.idx, cx.ef, cx.sgn, cx.i0, cx.atr)
        V["X3"].append(b if m3 < 0 else _run1(env, t, ex_b0, _min_fx(int(t["fx0"]), m3 + 1)))
        m4 = RU.noprog_row(cx.D.idx, cx.ef, cx.sgn, cx.i0, cx.atr)
        V["X4"].append(b if m4 < 0 else _run1(env, t, ex_b0, _min_fx(int(t["fx0"]), m4 + 1)))
        # X5: two lots, per lot
        a5, b5 = _run1(env, t, ex["X5a"], int(t["fx0"])), _run1(env, t, ex["X5b"], int(t["fx0"]))
        V["X5"].append(dict(b5, net=(a5["net"] + b5["net"]) / 2, gross=(a5["gross"] + b5["gross"]) / 2,
                            why=f"{a5['why']}+{b5['why']}", ret=(a5["ret"] + b5["ret"]) / 2))
        # X6: B0 plus a re-entry
        V["X6"].append(b)
        r6 = _reentry(env, t, b, cx, by_book[(b["und"], b["day"], b["book"])])
        if r6 is not None:
            added["X6"].append(r6)
        info["stopped"] += int(_stopped(env, t, b, cx))
        # X7: B0 plus an added lot
        r7 = _pyramid(env, t, b, cx)
        if r7 is not None:
            added["X7"].append(r7)
            V["X7"].append(dict(b, net=b["net"] + r7["net"], added=r7["net"]))
        else:
            V["X7"].append(b)
        # X10, X11
        o = _overnight(env, t, b, cx)
        V["X10"].append(b if o is None else o)
        V["X11"].append(_bestruct(env, t, ex_b0, cx, b))
    for v in TESTS:
        assert len(V[v]) == len(b0) and all(x is not None for x in V[v]), v
    V["X6"] = V["X6"] + added["X6"]
    return plain, b0, V, added, dict(info)


def cell_nets(b0, V, added, unds, phase):
    """Per variant: the trades of these indices, their period stats and their gains over B0."""
    sel = [i for i, t in enumerate(b0) if t["und"] in unds]
    B = [b0[i] for i in sel]
    out = {"B0": P.periods(B, "Liquidity", phase)}
    per = {}
    for v in TESTS:
        if v == "X6":
            base = [V[v][i] for i in sel]
            add = [a for a in added["X6"] if a["und"] in unds]
            T = sorted(base + add, key=lambda x: (x["day"], x["und"], x["er"]))
            dl = deltas(B, base, add)
        else:
            T = [V[v][i] for i in sel]
            add = [a for a in added["X7"] if a["und"] in unds] if v == "X7" else []
            dl = deltas(B, T)
        r = P.periods(T, "Liquidity", phase)
        r["changed"] = len(changed(B, [V[v][i] for i in sel]))
        r["added"] = len(add)
        r["gain"] = round(sum(dl))
        r["top5_left"] = round(RU.top5_ok(dl))
        r["paired"] = dict(zip(("p", "diff"), P.paired_boot(B, T, BOOT, SEED)))
        out[v] = r
        per[v] = T
    return out, B, per


# ================================================================================================ twins (final)
def twin_p(env, v, B, T, added, ex_b0, rng) -> float | None:
    K, C, E, fam_liq, r51data, G, X = env
    load = r51data.load
    kind = TWIN_KIND[v]
    actual = sum(t["net"] for t in T)
    if kind == "flip":
        return RU.flip_p(day_diff(B, T), TWINS, rng)
    if kind == "exit":
        k = len(changed(B, T))
        if k == 0:
            return 1.0

        def nf(t, rows):
            D = load(t["und"])[t["day"]]
            m = len(rows)
            return G.twin_nets(t["und"], D, [t["side"]] * m, [t["strike"]] * m, [t["er"]] * m, ex_b0,
                               Q.forced_rows(rows, t["fx0"]))
        return Q.p_of(Q.exit_twin_totals(B, k, TWINS, rng, nf), actual)
    base = float(sum(t["net"] for t in B))
    k = len(added)
    if k == 0:
        return 1.0
    tot = np.full(TWINS, base)
    by_book = defaultdict(list)
    for b in B:
        by_book[(b["und"], b["day"], b["book"])].append(b)
    if kind == "reentry":
        elig = [b for b in B if _stopped(env, b, b, Ctx(env, b)) and b["xr"] < fam_liq.LAST - 1]
    else:
        elig = [b for b in B if b["xr"] - b["er"] >= 3]
    m = min(k, len(elig))
    cache = {}
    for s in range(TWINS):
        for j in rng.choice(len(elig), size=m, replace=False):
            b = elig[j]
            if kind == "reentry":
                row = int(rng.integers(b["xr"] + 1, fam_liq.LAST + 1))
            else:
                row = int(rng.integers(b["er"] + 1, b["xr"]))
            key = (id(b), row)
            if key not in cache:
                cx = Ctx(env, b)
                r = (_reentry(env, b, b, cx, by_book[(b["und"], b["day"], b["book"])], row=row) if kind == "reentry"
                     else _pyramid(env, b, b, cx, row=row))
                cache[key] = 0.0 if r is None else r["net"]
            tot[s] += cache[key]
    return Q.p_of(tot, actual)


# ================================================================================================ steps
def run_phase(phase: str) -> dict:
    env = _env()
    _, b0, V, added, info = price_all(phase)
    _save(f"b0_{phase}", b0)
    _save(f"V_{phase}", (V, added))
    out = {"info": info, "cells": {}}
    groups = {"pooled": POOLED, **{u: (u,) for u in UNDS}}
    rng = np.random.default_rng(SEED)
    for g, unds in groups.items():
        res, B, per = cell_nets(b0, V, added, unds, phase)
        if phase == "final":
            for v in TESTS:
                add = [a for a in added.get(v, []) if a["und"] in unds] if v in ("X6", "X7") else []
                res[v]["twin_p"] = twin_p(env, v, B, per[v], add, env[3].EX, rng)
            if g != "pooled":
                res["B0"]["entry_twin_p"] = _entry_twin(env, g, B)
        out["cells"][g] = res
        key = "final" if phase == "final" else "design"
        print(phase, g, {v: res[v][key]["net"] for v in ("B0",) + TESTS}, flush=True)
    return out


def _entry_twin(env, und, B):
    """Per-index B0: R51's random-entry twin p (common.twin_p with fam_liq's TwinSpec), final period."""
    K, C, E, fam_liq, r51data, G, X = env
    import pandas as pd
    T = pd.DataFrame([dict(und=b["und"], day=b["day"], side=b["side"], net=b["net"]) for b in B])
    p, _ = C.twin_p(T, fam_liq.make(und, BOOKS[und]), TWINS, SEED)
    return p


def run(phase: str):
    path = RESULTS / f"{phase}.json"
    if phase == "final":
        if not FINAL_FLAG.exists():
            raise SystemExit("R81: the final read is opened only by `r81study.py final`")
        if path.exists():
            raise SystemExit(f"R81: {path.name} exists - the final test is read once")
    res = run_phase(phase)
    RESULTS.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(res, indent=1, default=str))


def _sub(phase):
    subprocess.run([sys.executable, str(Path(__file__).resolve()), "run", phase], check=True)


def design():
    _sub("design")


def final():
    if FINAL_FLAG.exists():
        raise SystemExit("R81: the final test was already read (results/FINAL_READ). One read only.")
    RESULTS.mkdir(parents=True, exist_ok=True)
    FINAL_FLAG.write_text(f"final test read on {datetime.now(timezone.utc).isoformat()}\n")
    _sub("final")
    summarise()


def summarise() -> dict:
    d, f = (json.loads((RESULTS / f"{ph}.json").read_text()) for ph in ("design", "final"))
    out = {"cells": {}}
    ps, keys = [], []
    for g in d["cells"]:
        dc, fc = d["cells"][g], f["cells"][g]
        G_ = out["cells"][g] = {"B0": dict(design=dc["B0"]["design"], wf=dc["B0"]["wf"], wf_quarters=dc["B0"]["wf_quarters"],
                                           final=fc["B0"]["final"], entry_twin_p=fc["B0"].get("entry_twin_p"))}
        for v in TESTS:
            a, b = dc[v], fc[v]
            G_[v] = dict(design=a["design"], wf=a["wf"], final=b["final"], changed=dict(design=a["changed"], final=b["changed"]),
                         added=dict(design=a["added"], final=b["added"]), gain=dict(design=a["gain"], final=b["gain"]),
                         top5_left=dict(design=a["top5_left"], final=b["top5_left"]),
                         paired=dict(design=a["paired"], final=b["paired"]), twin_p=b["twin_p"],
                         twin_kind=TWIN_KIND[v])
            ps.append(b["twin_p"] if b["twin_p"] is not None else 1.0)
            keys.append((g, v))
    for (g, v), q in zip(keys, P.bh(ps)):
        c = out["cells"][g]
        cell = dict(b0={k: c["B0"][k]["net"] for k in ("design", "wf", "final")},
                    v={k: c[v][k]["net"] for k in ("design", "wf", "final")}, twin_p=c[v]["twin_p"], q=float(q),
                    top5_design=c[v]["top5_left"]["design"], top5_final=c[v]["top5_left"]["final"])
        c[v]["q"] = round(float(q), 4)
        c[v]["decision"] = decide(cell)
    for g in out["cells"]:
        if g == "pooled":
            continue
        b = out["cells"][g]["B0"]
        b["run_it"] = bool(b["design"]["net"] > 0 and b["wf"]["net"] > 0 and b["final"]["net"] > 0
                           and b["design"]["ex_top5"] > 0 and b["final"]["ex_top5"] > 0)
    out["info"] = dict(design=d["info"], final=f["info"])
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "summary.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "run" and len(sys.argv) == 3 and sys.argv[2] in ("design", "final"):
        run(sys.argv[2])
    elif cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "summary":
        summarise()
    else:
        print(__doc__)
