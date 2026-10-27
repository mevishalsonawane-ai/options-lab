"""R116 study (PREREG.md): the arms' index signals on expiry days, buying the same-day option (world S), the next
expiry's option (N) or the lake's later monthly (M, reported only), against skipping (today, Rs 0).

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                       # R101's clean stores (to 13 Oct)
    python research/hunt/r116_expiry_next_option/r116data.py map                 # next-expiry contracts + data map
    python research/hunt/r116_expiry_next_option/r116study.py design             # design + walk-forward (all worlds)
    python research/hunt/r116_expiry_next_option/r116study.py final              # the ONE final read (refuses a second)
    python research/hunt/r116_expiry_next_option/r116study.py decide             # BH over every test + gates

Each world runs in its own process (`world <W> <phase>`), so no cached contract or signal of one world reaches another.
A world swaps only the expiry days' Day objects in R51's store cache: the expiry-day skip is lifted (expiry=False), the
15:05 expiry square-off kept (xday=True, patched into engine.sq_row), and for N / M the day's contracts replaced by the
next expiry's. Index minutes, signals, ATM label, strike rule and exits are untouched; every other day is the same object.
"""
from __future__ import annotations

import json
import pickle
import subprocess
import sys
from datetime import date, datetime, timezone

import numpy as np

import r116cfg as K
import r116lib as L

_M: dict = {}


def mods():
    if not _M:
        import r115study as S15                  # R115's units / walk (R101's arms, R106's walk, R112's R51 + ST)
        M = S15.mods()
        import r116data as DATA
        import fam_liq
        _M.update(S15=S15, A=M["A"], E=M["E"], C=M["C"], G=M["A"].mods()["G"], r51data=M["r51data"], DATA=DATA,
                  fam_liq=fam_liq)
    return _M


# ================================================================================================ worlds
def patched_sq(orig, sq_expiry: int):
    """engine.sq_row that gives an R116 expiry day (xday) the expiry square-off; every other day as before."""
    def sq_row(D, ex):
        if getattr(D, "xday", False):
            return min(ex.sq_row, sq_expiry) if ex.sq_row >= 0 else sq_expiry
        return orig(D, ex)
    return sq_row


def swap_days(days: dict, world: str, nxt: dict | None, day_cls) -> dict:
    """Replace, in place, the expiry days (START..FINAL_END) of one index's store with tradable copies: same index
    minutes and ATM label, the skip lifted (expiry=False), xday=True; world S keeps the day's own (expiring) contracts,
    N / M take nxt[day]['con'] and skip days without one. Returns {day: source label}."""
    sw = {}
    for d in sorted(days):
        D = days[d]
        if not D.expiry or not (K.START <= d <= K.FINAL_END):
            continue
        if world == "S":
            con, src = D.con, "same-day"
        else:
            x = (nxt or {}).get(d)
            if x is None:
                continue
            con, src = x["con"], f"{x['src']} {x['expiry']} ({x['dte']}d)"
        X = day_cls(d, D.idx, D.atm, con, False)
        X.xday = True
        days[d] = X
        sw[d] = src
    return sw


def install(world: str) -> dict:
    """Swap the expiry days of `world` into the store cache; returns {und: {day: source label}} of the swapped days."""
    M = mods()
    E, r51data, DATA = M["E"], M["r51data"], M["DATA"]
    if not getattr(E, "_r116_patched", False):
        E.sq_row = patched_sq(E.sq_row, E.K.SQ_ROW_EXPIRY)
        E._r116_patched = True
    return {und: swap_days(r51data.load(und), world, DATA.load_next(und, world) if world in ("N", "M") else None,
                           r51data.Day) for und in K.UNDS}


def restrict_days(swapped: dict):
    """Price only the swapped days (N / M): C.days_in yields just those (signals still read every day's history)."""
    C = mods()["C"]
    orig = C.days_in

    def days_in(und, lo, hi):
        keep = swapped.get(und, {})
        return [d for d in orig(und, lo, hi) if d in keep]
    C.days_in = days_in


# ================================================================================================ arms
def window(arm: str) -> tuple:
    """The arm's entry window (rows) for random-entry twins."""
    A = mods()["A"]
    if arm == "LIQ":
        fl = mods()["fam_liq"]
        return fl.FIRST, fl.LAST
    return A.K.OPEN_ROW, A.K.row(15, 10)


def twin_totals(arm: str, trades: list, n: int, rng) -> np.ndarray:
    """PREREG gate 3: each trade's entry minute drawn uniformly in the arm's window (same day, side, count), 1-ITM from
    the minute before, the arm's premium exits on the world's contracts; unfilled = Rs 0. (n,) totals."""
    M = mods()
    A, E, G, r51data, fl = M["A"], M["E"], M["G"], M["r51data"], M["fam_liq"]
    lo, hi = window(arm)
    ex = A._oov_l25() if arm == "R52" else (fl.EX if arm == "LIQ" else A._xl())
    tot = np.zeros(n)
    for t in trades:
        und, d, side = t["und"], t["day"], t["side"]
        D = r51data.load(und)[d]
        rows = rng.integers(lo, hi + 1, size=n)
        ks = [E.strike_for(D, und, side, int(r) - 1, 1) for r in rows]
        ok = [k is not None for k in ks]
        rr = [int(r) for r, o in zip(rows, ok) if o]
        kk = [k for k in ks if k is not None]
        res = (E.run_day(und, D, [side] * len(rr), kk, rr, ex) if arm == "LIQ"
               else G.run_day(und, D, [side] * len(rr), kk, rr, ex))
        nets = np.zeros(n)
        nets[np.flatnonzero(ok)] = [r["net"] if r is not None else 0.0 for r in res]
        tot += nets
    return tot


# ================================================================================================ one world, one phase
def periods(phase: str) -> tuple:
    return ("design", "wf") if phase == "design" else ("final",)


def bounds(phase: str) -> tuple:
    return (K.START, K.DESIGN_END) if phase == "design" else (K.FINAL_START, K.FINAL_END)


def slim(t: dict) -> dict:
    keep = ("und", "day", "side", "strike", "er", "xr", "er_sig", "E", "X", "rE", "rX", "qty", "why", "net", "gross",
            "book")
    return {k: t.get(k) for k in keep}


def run_world(world: str, phase: str):
    M = mods()
    if phase == "final":
        M["C"].open_final()
    swapped = install(world)
    if world != "S":
        restrict_days(swapped)
    lo, hi = bounds(phase)
    xset = {(u, d) for u, s in swapped.items() for d in s}
    out = dict(world=world, phase=phase, swapped={u: {str(d): s for d, s in v.items()} for u, v in swapped.items()},
               parity={}, trades={})
    for arm in K.ARMS:
        us = M["S15"].units(arm, lo, hi)
        tr = M["S15"].walk(arm, us)
        if world == "S":                       # every non-expiry day must reproduce R115's published "today"
            rest = [t for t in tr if (t["und"], t["day"]) not in xset]
            pub = M["S15"].K.PUBLISHED[(arm, phase)]
            got = (len(rest), round(sum(t["net"] for t in rest)))
            print(world, phase, arm, "non-expiry days", got, "published", pub, flush=True)
            assert got[0] == pub[0] and abs(got[1] - pub[1]) <= 1, (arm, phase, "parity", got, pub)
            out["parity"][arm] = dict(trades=got[0], net=got[1], published=list(pub))
        xt = [slim(t) for t in tr if (t["und"], t["day"]) in xset]
        print(world, phase, arm, "expiry-day trades", len(xt), flush=True)
        out["trades"][arm] = xt
    rng = np.random.default_rng(K.SEED + {"S": 0, "N": 1, "M": 2}[world] + (10 if phase == "final" else 0))
    out["twins"] = {}
    for arm in K.ARMS:
        tr = out["trades"][arm]
        tw = twin_totals(arm, tr, K.TWINS, rng) if tr else np.zeros(0)
        out["twins"][arm] = tw.tolist()
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"world_{world}_{phase}.pkl").write_bytes(pickle.dumps(out))


# ================================================================================================ summaries
def describe(trades: list, per: str) -> dict:
    tr = [t for t in trades if K.in_period(t["day"], per)]
    s = L.stats(tr)
    s["open_0915_0930"] = L.stats(L.open_slice(tr))
    s["by_index"] = {u: L.stats([t for t in tr if t["und"] == u]) for u in K.UNDS if any(t["und"] == u for t in tr)}
    if tr:
        prem = [float(t["E"]) * float(t["qty"]) for t in tr]
        risk = [L.risk_per_lot(float(t["E"]), int(t["qty"])) for t in tr]
        s["premium_per_lot_median"] = round(float(np.median(prem)))
        s["risk_per_lot_median"] = round(float(np.median(risk)))
        s["account_for_2pct_median"] = round(float(np.median([r / K.RISK_PCT for r in risk])))
        s["premium_by_index"] = {u: round(float(np.median([float(t["E"]) * float(t["qty"]) for t in tr if t["und"] == u])))
                                 for u in s["by_index"]}
    return s


def monthly_days() -> set:
    """(index, day) of the monthly-expiry days in the data map."""
    m = json.loads((K.RESULTS / "datamap.json").read_text())
    return {(r["und"], date.fromisoformat(r["day"])) for r in m["days"]
            if r["und"] not in K.WEEKLY or r.get("lake_month") == "same"}


def same_days(trades: list, days: set) -> list:
    return [t for t in trades if (t["und"], t["day"]) in days]


def summarise(phase: str) -> dict:
    W = {w: pickle.loads((K.OUT / f"world_{w}_{phase}.pkl").read_bytes()) for w in K.WORLDS}
    out = {"_meta": dict(phase=phase, twins=K.TWINS, seed=K.SEED, written=datetime.now(timezone.utc).isoformat()),
           "parity": W["S"]["parity"],
           "arms": {}}
    out["days"] = {w: {p: {u: sum(1 for d in v if K.in_period(date.fromisoformat(d), p))
                           for u, v in W[w]["swapped"].items()} for p in periods(phase)} for w in K.WORLDS}
    n_days = {(u, date.fromisoformat(d)) for u, v in W["N"]["swapped"].items() for d in v}
    m_days = {(u, date.fromisoformat(d)) for u, v in W["M"]["swapped"].items() for d in v}
    for arm in K.ARMS:
        r = {}
        for w in K.WORLDS:
            tr = W[w]["trades"][arm]
            r[w] = {p: describe(tr, p) for p in periods(phase)}
            act = float(sum(t["net"] for t in tr))
            r[w]["twin"] = L.twin_p(act, W[w]["twins"][arm]) if tr else L.twin_p(0.0, [])
        # the fair comparisons: same-day option on exactly the days N (or M) could trade
        r["S_on_N_days"] = {p: describe(same_days(W["S"]["trades"][arm], n_days), p) for p in periods(phase)}
        r["S_on_M_days"] = {p: describe(same_days(W["S"]["trades"][arm], m_days), p) for p in periods(phase)}
        # reported: world N on monthly-expiry days (every expiry of BANKNIFTY / FINNIFTY / MIDCPNIFTY; NIFTY / SENSEX
        # days whose monthly expires too) against weekly-only expiry days
        mon = monthly_days()
        nt = W["N"]["trades"][arm]
        r["N_monthly_days"] = {p: describe([t for t in nt if (t["und"], t["day"]) in mon], p) for p in periods(phase)}
        r["N_weekly_days"] = {p: describe([t for t in nt if (t["und"], t["day"]) not in mon], p) for p in periods(phase)}
        out["arms"][arm] = r
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def run(phase: str):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists():
            raise SystemExit("R116 final already read once: " + K.FINAL_FLAG.read_text())
        assert (K.RESULTS / "design.json").exists(), "the design read comes first"
        K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    for w in K.WORLDS:
        subprocess.run([sys.executable, __file__, "world", w, phase], check=True)
    summarise(phase)


def decide() -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())["arms"]
    f = json.loads((K.RESULTS / "final.json").read_text())["arms"]
    tests = list(K.TESTS)
    ps = [f[a][w]["twin"]["p"] for a, w in (K.TESTS[t] for t in tests)]
    q_all = L.bh(ps, K.M_ALL)
    q_own = L.bh(ps)
    out = {"_m": dict(r116=len(tests), prior_all=K.N_PRIOR_ALL, m_all=K.M_ALL)}
    for t, qa, qo in zip(tests, q_all, q_own):
        arm, w = K.TESTS[t]
        per = {"design": d[arm][w]["design"], "wf": d[arm][w]["wf"], "final": f[arm][w]["final"]}
        g = L.gates(per, float(qa))
        g.update(p=f[arm][w]["twin"]["p"], q_all=float(qa), q_own=round(float(qo), 4))
        out[t] = g
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "world":
        run_world(sys.argv[2], sys.argv[3])
    elif cmd in ("design", "final"):
        run(cmd)
    elif cmd == "summarise":
        summarise(sys.argv[2])
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
