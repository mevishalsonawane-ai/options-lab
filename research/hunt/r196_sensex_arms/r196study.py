"""R196 study (PREREG.md): SENSEX in R72, R51 and Liquidity 15+5 with today's exits (V5), 1 Nov 2024 - 13 Nov 2026.

    python3 -I research/hunt/r196_sensex_arms/r196study.py repro    # R193's pooled totals on R196's data -> results/repro.json
    python3 -I research/hunt/r196_sensex_arms/r196study.py w1       # W1 (design + walk-forward) -> results/w1.json
    python3 -I research/hunt/r196_sensex_arms/r196study.py w2       # W2 read ONCE + total + twins -> results/w2.json
    python3 -I research/hunt/r196_sensex_arms/r196study.py decide   # the gate -> results/decision.json

Reuses R193's study functions (units, price, walk, the picker) unchanged, pointed at R196's lake and stores. The only
addition is Liquidity's SENSEX book (15 + 5 minutes, index stop 50 points), set in this process's copy of R101's book list
and fam_liq's index stops, never in their files. Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r196cfg as K  # noqa: E402  (sets R193_OUT / R193_LAKE first)

import numpy as np  # noqa: E402

import r193study as R3  # noqa: E402  (R186 -> R179 -> R177 -> R176 -> R152 -> R112 -> R106 -> R101 -> R51)
import r196lib as L  # noqa: E402

R3.K.STORE = K.STORE                       # R196's store window (PREREG 2)
S6, R7 = R3.S6, R3.R7
TRADES = K.OUT / "r196_trades.pkl"


def boot() -> dict:
    return R3.boot()


def set_liq_sensex(on: bool):
    """Liquidity's SENSEX book in this process only (PREREG 5)."""
    import r101arms
    r101arms.mods()                        # imports fam_liq (lazily) and sets MIDCPNIFTY's index stop
    import fam_liq
    import r101cfg
    assert r101arms.K is r101cfg
    if on:
        r101cfg.LIQ_BOOKS[K.SX_IDX] = K.LIQ_SX_BOOKS
        fam_liq.IDX_STOP[K.SX_IDX] = K.LIQ_SX_IDX_STOP
    else:
        r101cfg.LIQ_BOOKS.pop(K.SX_IDX, None)


def _expiry_days(und: str) -> set:
    days = R7.mods()["r51data"].load(und)
    return {S6._d(d) for d, D in days.items() if D.expiry}


def arm_trades(arm: str, with_sx: bool = True) -> dict:
    """{'A': own exits (no picker), 'V5': the live rule (picker kept, as the app), 'V5_all': V5 before the picker,
    'removed': picker-removed V5 trades} over the whole span, all indices."""
    lo, hi = K.SPAN
    if arm == "LIQ":
        set_liq_sensex(with_sx)
    us0, usA = R3.units(arm, lo, hi)
    be, lock, pick = {"R72": K.TEST_RULE["R72-SX"], "R51": K.TEST_RULE["R51-SX"], "LIQ": K.TEST_RULE["LIQ-SX"]}[arm]
    walk = lambda pr: [t for t in S6.walk(arm, usA, pr) if lo <= t["day"] <= hi]  # noqa: E731
    a = walk(R3.price(arm, usA, False, "", 1))
    v = walk(R3.price(arm, usA, be, lock, 1))
    if pick:
        P = S6.picker()
        sc, thr = P.scores(arm, us0)
        assert abs(thr - K.PICK_THR[arm]) < 1e-12
        kp = [P.kept(t, sc, thr) for t in v]
    else:
        kp = [True] * len(v)
    unds = sorted({k[0] for k, _, _, _ in usA})
    if arm == "LIQ":
        assert (K.SX_IDX in unds) == with_sx, unds
    return dict(A=a, V5=[t for t, k in zip(v, kp) if k], V5_all=v, removed=[t for t, k in zip(v, kp) if not k],
                unds=unds)


def all_trades() -> dict:
    if TRADES.exists():
        return pickle.loads(TRADES.read_bytes())
    boot()
    K.OUT.mkdir(parents=True, exist_ok=True)
    out = {}
    for name, arm, sx in (("R72", "R72", True), ("R51", "R51", True), ("LIQ_noSX", "LIQ", False), ("LIQ", "LIQ", True)):
        part = K.OUT / f"r196_part_{name}.pkl"     # each arm kept as soon as it is done (outside git)
        if part.exists():
            out[name] = pickle.loads(part.read_bytes())
        else:
            out[name] = arm_trades(arm, with_sx=sx)
            part.write_bytes(pickle.dumps(out[name], protocol=pickle.HIGHEST_PROTOCOL))
    set_liq_sensex(True)
    out["expiry_days"] = sorted(_expiry_days(K.SX_IDX))
    TRADES.write_bytes(pickle.dumps(out, protocol=pickle.HIGHEST_PROTOCOL))
    return out


def _tot(tr: list) -> dict:
    return dict(trades=len(tr), net=round(float(sum(t["net"] for t in tr))))


# ================================================================================================ reproduction (PREREG 3)
def repro() -> dict:
    T = all_trades()
    rows = {"A:R72": T["R72"]["A"], "R72-V5": T["R72"]["V5"], "A:R51": T["R51"]["A"], "R51-V5": T["R51"]["V5"],
            "A:LIQ": T["LIQ_noSX"]["A"], "LIQ-V5": T["LIQ_noSX"]["V5"]}
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), stores=f"R196 stores {K.STORE}",
                         what="R193's pooled totals (all indices; Liquidity without the SENSEX book), 1 lot")}
    for k, tr in rows.items():
        s = _tot(tr)
        pub = K.R193_PUB[k]
        r = dict(s, published=list(pub), gap_trades=s["trades"] - pub[0], gap_net=s["net"] - pub[1])
        r["reproduced"] = r["gap_trades"] == 0 and abs(r["gap_net"]) <= 1
        out[k] = r
        print(k, r, flush=True)
    # PREREG 5: the SENSEX book must leave the other three Liquidity books' trades unchanged
    key = lambda t: (t["und"], t["day"], t.get("book"), t["er_sig"], t["side"], round(t["net"], 2))  # noqa: E731
    for tag in ("A", "V5"):
        a = sorted(key(t) for t in L.without(T["LIQ"][tag], K.SX_IDX))
        b = sorted(key(t) for t in T["LIQ_noSX"][tag])
        out[f"LIQ others unchanged by SENSEX ({tag})"] = dict(same=a == b, with_sx=len(a), without_sx=len(b))
        print(tag, "others unchanged:", a == b, len(a), len(b), flush=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "repro.json").write_text(json.dumps(out, indent=1, default=str))
    return out


# ================================================================================================ windows
def _slice_report(T: dict, test: str, windows: tuple) -> dict:
    arm = K.TEST_ARM[test]
    sx = L.only(T[arm]["V5"], K.SX_IDX)
    a_sx = L.only(T[arm]["A"], K.SX_IDX)
    rem = L.only(T[arm]["removed"], K.SX_IDX)
    exp = set(T["expiry_days"])
    r = dict(label=K.LABEL[test], windows={}, own_exits_A={}, picker_removed={}, expiry_days_skipped={}, quarters={},
             exits={}, per_index={}, arm_with_without={})
    for w in windows:
        lo, hi = K.WINDOWS[w]
        r["windows"][w] = L.window_stats(sx, w)
        r["own_exits_A"][w] = L.window_stats(a_sx, w)
        r["picker_removed"][w] = len(L.in_dates(rem, lo, hi))
        r["expiry_days_skipped"][w] = sum(1 for d in exp if lo <= d <= hi)
        r["exits"][w] = L.exits_by_reason(L.in_dates(sx, lo, hi))
        if w in K.QUARTERS:
            r["quarters"][w] = L.quarters(sx, w)
        allv = L.in_dates(T[arm]["V5"], lo, hi)
        r["per_index"][w] = {u: _tot(L.only(allv, u)) for u in sorted({t["und"] for t in allv})}
        r["arm_with_without"][w] = dict(with_sensex=_tot(allv), without_sensex=_tot(L.without(allv, K.SX_IDX)))
    return r


def w1() -> dict:
    if not (K.RESULTS / "repro.json").exists():
        raise SystemExit("reproduction first (PREREG 3)")
    T = all_trades()
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), window=K.WINDOW_NAME["W1"])}
    for test in K.TESTS:
        out[test] = _slice_report(T, test, ("W1",))
        s = out[test]["windows"]["W1"]
        print(test, s["trades"], s["win"], s["net"], s["max_dd"], s["worst_month"],
              [q["net"] for q in out[test]["quarters"]["W1"]], flush=True)
    (K.RESULTS / "w1.json").write_text(json.dumps(out, indent=1, default=str))
    return out


# ================================================================================================ twins
def _twins(test: str, trades: list, exp: set, rng) -> np.ndarray:
    import r186engine as Z
    arm = K.TEST_ARM[test]
    _, lock, _ = K.TEST_RULE[test]
    M = R7.mods()
    E, days = M["E"], M["r51data"].load(K.SX_IDX)
    by_date = {S6._d(k): v for k, v in days.items()}
    ex = S6.exits(arm)
    first, last = K.TWIN_WINDOW[arm]

    def net_at(d, side, r):
        D = by_date[d]
        k = E.strike_for(D, K.SX_IDX, side, r - 1, K.TWIN_MONEY)
        if k is None:
            return None
        t = Z.price(K.SX_IDX, D, [dict(side=side, strike=k, er=r, fx=-1)], ex, 1, -1.0, lock)[0]
        return None if t is None else float(t["net"])

    return L.twin_totals(L.twin_plan(trades, exp), net_at, first, last, K.TWINS, rng)


def w2() -> dict:
    if not (K.RESULTS / "w1.json").exists():
        raise SystemExit("W1 first (PREREG 8)")
    if K.W2_FLAG.exists():
        raise SystemExit("R196 W2 already read once: " + K.W2_FLAG.read_text())
    K.W2_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    T = all_trades()
    boot()
    set_liq_sensex(True)
    exp = set(T["expiry_days"])
    rng = np.random.default_rng(K.SEED)
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), twins=K.TWINS, seed=K.SEED, m_total=K.M_TOTAL)}
    for test in K.TESTS:
        r = _slice_report(T, test, ("W1", "W2", "total"))
        sx = L.only(T[K.TEST_ARM[test]]["V5"], K.SX_IDX)
        tw = _twins(test, sx, exp, rng)
        act = float(sum(t["net"] for t in sx))
        r["twin"] = dict(p=round(L.G3.twin_p(act, tw), 5), p_normal=L.G3.normal_p(act, tw), mean=round(float(tw.mean())),
                         sd=round(float(tw.std(ddof=1))), actual=round(act), plan=len(L.twin_plan(sx)))
        out[test] = r
        print(test, {w: (r["windows"][w]["trades"], r["windows"][w]["net"]) for w in r["windows"]}, r["twin"], flush=True)
    (K.RESULTS / "w2.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def decide() -> dict:
    r = json.loads((K.RESULTS / "w2.json").read_text())
    qs = L.bh([r[t]["twin"]["p_normal"] for t in K.TESTS])
    out = {"m_total": K.M_TOTAL, "tests": {}}
    for t, q in zip(K.TESTS, qs):
        wf = L.wf_positive(r[t]["quarters"]["W1"])
        g = L.gate(r[t]["windows"], wf, r[t]["twin"]["p"], q)
        g.update(p_normal=r[t]["twin"]["p_normal"], wf_positive=wf,
                 account_stop={w: r[t]["windows"][w]["account_stop_ok"] for w in K.WINDOWS})
        out["tests"][t] = g
    out["passed"] = [t for t in K.TESTS if out["tests"][t]["passed"]]
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    print(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    {"repro": repro, "w1": w1, "w2": w2, "decide": decide}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
