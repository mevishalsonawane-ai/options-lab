"""R112 study (PREREG.md): the two Donchian arms with entries until 10:00 and +Rs 1,500 / -Rs 1,000 an order.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                         # R101's clean stores (to 13 Oct)
    python research/hunt/r112_donchian_morning/r112study.py design                 # design + walk-forward, parity asserted
    python research/hunt/r112_donchian_morning/r112study.py final                  # the ONE final read (refuses a second)
    python research/hunt/r106_small_targets/r106study.py post-build                # stores to 19 Oct, own folder (R106's)
    R112_POST=1 python research/hunt/r112_donchian_morning/r112study.py post       # 14-19 Oct, read once after the final
    python research/hunt/r112_donchian_morning/r112study.py decide                 # BH + gates -> results/decision.json

The arms are R101's candidates (R72) and R101's R51 candidates with R73's Supertrend (10, 3) filter; orders are priced
by R106's engine (per-order rupee target / stop); the book with and without the same-direction cap is R108's model.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import date, datetime, timezone

import numpy as np

import r112cfg as K
import r112lib as L

_M: dict = {}


def mods():
    if not _M:
        import r106study as S
        M = S.mods()
        import r101arms as A
        import r108book as B
        import r73rules as P73
        _M.update(S=S, A=A, B=B, P73=P73, X=M["X"], W=M["W"], E=M["E"], C=M["C"], r51data=M["r51data"],
                  fam_r44=A.mods()["fam_r44"], R51K=A.mods()["R51"])
    return _M


# ================================================================================================ the arms' candidates
def exits():
    return mods()["A"]._xl()


def r51_st_signals() -> dict:
    """{und: {day: [(er, side)]}}: R101's R51 Donchian-20 signals kept only when Supertrend (10, 3) on the same
    continuous 5-minute index bars points the same way at the signal bar (R73 T7, PREREG section 1)."""
    M = mods()
    raw = M["A"].r51_signals()
    out = {}
    for u in M["A"].K.UNDS:
        d, b, o, h, l, c = M["fam_r44"].bars5(u)
        pos = {(d[i], int(b[i])): i for i in range(len(d))}
        st = M["P73"].supertrend_dir(np.asarray(h, float), np.asarray(l, float), np.asarray(c, float), 10, 3.0)
        out[u] = L.supertrend_keep(raw.get(u, {}), pos, st)
    return out


def units_of(arm: str, lo: date, hi: date) -> list:
    """[(key, cands, res, 'side2')]: R72 = R101's units; R51 = R101's R51 units on the Supertrend-kept signals;
    'R51plain' = R101's R51 units (parity only)."""
    M = mods()
    A = M["A"]
    if arm == "R72":
        return A.r72_units(lo, hi)
    if arm == "R51plain":
        return A.r51_units(lo, hi)
    if arm == "R51":
        return A._side2_units(r51_st_signals(), lo, hi, exits())
    raise ValueError(arm)


# ================================================================================================ pricing
def price(und: str, D, cands: list, ts: bool) -> list:
    """Price one day's candidates of an index with today's exits (XL) plus, when ts, the +Rs 1,500 target and the
    -Rs 1,000 stop (replacing the -15% premium stop), each solved from the order's own real fill (R106's functions).
    A candidate's 'fx' (the 10:00 close) is a forced exit at that row's open."""
    M = mods()
    E, X = M["E"], M["X"]
    import r51cfg as RK
    import r64engine as G
    ex = exits()
    st = E.stack(und, D)
    qty = float(RK.lot(und, D.d))
    sq = E.sq_row(D, ex)
    stp = ex.steps_arr()
    ch = lambda a, b: float(E.charges(und, np.array([a]), np.array([b]), qty, D.d)[0])  # noqa: E731
    out = []
    for c in cands:
        ci = st.idx(c["side"], c["strike"])
        if ci < 0:
            out.append(None)
            continue
        C = st.S[ci]
        raw, real, ef, tks = X.entry_info(C, c["er"], sq, qty)
        if ef < 0:
            out.append(None)
            continue
        be = real + ch(real, real) / qty if G.needs_be(ex) else 0.0
        tgt_abs, stop_abs, stop_f = -1.0, -1.0, ex.stop
        if ts:
            tgt_abs = X.target_price(real, qty, K.TARGET, ch)
            stop_abs = X.stop_price(real, qty, K.STOP, ch, tks, E.SLIP[2], E.SLIP[3])
            stop_f = -1.0
        fx = int(c.get("fx", -1))
        a, b, rE, rX, r0, r1, r = X.simulate(C, int(c["er"]), int(sq), fx, stop_f, ex.target, ex.lock_trig, ex.lock_keep,
                                             ex.lock_trail, stp, ex.tmax, ex.need, ex.close_stop, RK.TICK, qty, be,
                                             stop_abs, tgt_abs, *E.SLIP)
        if r == E.R_NOFILL:
            out.append(None)
            continue
        net = (rX - rE) * qty - ch(rE, rX)
        out.append(dict(und=und, day=D.d, side=c["side"], strike=float(c["strike"]), er=int(r0), xr=int(r1), E=float(a),
                        X=float(b), rE=float(rE), rX=float(rX), qty=qty, why=E.REASONS[int(r)], net=float(net),
                        tgt_px=float(tgt_abs), stop_px=float(stop_abs)))
    return out


def rule_units(units: list, rule: str) -> list:
    """The units with the rule's entry cut and 10:00 close applied (empty units dropped)."""
    sp = K.RULES[rule]
    out = []
    for key, cands, res, how in units:
        keep = L.cut_keep(cands, sp["cut"])
        if not keep:
            continue
        cs = L.with_close([cands[j] for j in keep], sp["close"])
        out.append((key, cs, [res[j] for j in keep], how))
    return out


def priced_units(units: list, rule: str) -> list:
    """Each unit's priced candidates under the rule (B0 / CUT10 re-use R101's own pricing, checked equal in design)."""
    M = mods()
    sp = K.RULES[rule]
    out = []
    for key, cands, res, how in units:
        if not sp["ts"] and sp["close"] is None:
            out.append(res)
        else:
            D = M["r51data"].load(key[0])[key[1]]
            out.append(price(key[0], D, cands, sp["ts"]))
    return out


def walk(units: list, priced: list) -> list:
    """The arm's own book (R101's side2 rule) day by day: one open a side per index, at most 2 fresh fills a side a day."""
    W = mods()["W"]
    trades = []
    for d, gs in sorted(W.by_day(units).items()):
        trades += W.walk_day([(units[g][0], units[g][1], units[g][3]) for g in gs], [priced[g] for g in gs])
    for t in trades:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    trades.sort(key=lambda t: (t["day"], t["und"], t["er_sig"]))
    return trades


# ================================================================================================ the book (R108)
def book(arm_units: dict, arm_priced: dict, sessions: list, rule_name: str) -> list:
    """R108's model of the app's paper book with only the two Donchian arms: CAP2 (the app's same-direction cap) or
    NOCAP. Returns the REAL trades (dicts with day, arm, net, ...)."""
    B = mods()["B"]
    dc = defaultdict(lambda: ([], []))
    g = 0
    for arm in K.ARMS:
        for (key, cands, res, how), pr in zip(arm_units[arm], arm_priced[arm]):
            g += 1
            cs, ps = dc[key[1]]
            for j, (c, r) in enumerate(zip(cands, pr)):
                cs.append(dict(arm=arm, und=key[0], side=c["side"], er=int(c["er"]), g=g, j=j, how=how,
                               strike=float(c["strike"]), book=None, risk=0.0))
                ps.append(r)
    w = B.walk_period(sessions, dict(dc), B.make_rule(rule_name))
    tr = B.trades_of(w)
    for t in tr:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    return tr


# ================================================================================================ twins
def plain_exit(t: dict):
    """The trade priced again from its own signal entry row with today's exits (no target / stop / 10:00 close)."""
    M = mods()
    D = M["r51data"].load(t["und"])[t["day"]]
    return price(t["und"], D, [dict(side=t["side"], strike=t["strike"], er=t["er_sig"])], False)[0]


def exit_twin(trades: list, n: int, rng) -> tuple:
    """PREREG gate 3 exit twin: (twin totals, changed count)."""
    M = mods()
    ex = exits()
    fixed, rows = 0.0, []
    for t in trades:
        p = plain_exit(t)
        if p is None or t["xr"] >= p["xr"] or p["xr"] - t["er"] < 2:
            fixed += t["net"]
            continue
        D = M["r51data"].load(t["und"])[t["day"]]
        r = np.arange(t["er"] + 1, p["xr"], dtype=np.int64)
        rows.append(M["X"].forced_exit_nets(t["und"], D, t, ex, r, -1))
    return L.exit_twin_totals(fixed, rows, n, rng), len(rows)


# ================================================================================================ phases
def _save(name: str, obj):
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name: str):
    return pickle.loads((K.OUT / f"{name}.pkl").read_bytes())


def parity(arm: str, phase: str, units: list) -> dict:
    """PREREG section 1: B0 must reproduce the published figure; the engine with no levels must price as R101."""
    tr = walk(units, [u[2] for u in units])
    n0, net0 = len(tr), round(sum(t["net"] for t in tr))
    pub = K.PUBLISHED[arm].get(phase)
    out = dict(trades=n0, net=net0, published=list(pub) if pub else None)
    if pub is not None:
        out["reproduced"] = pub[0] == n0 and abs(pub[1] - net0) <= 1
        assert out["reproduced"], (arm, phase, "B0 does not reproduce", n0, net0, pub)
    print(arm, phase, "B0 parity", out, flush=True)
    return out


def engine_parity(units: list):
    """R112's pricing with no levels equals R101's priced results (checked in design)."""
    M = mods()
    for key, cands, res, how in units:
        D = M["r51data"].load(key[0])[key[1]]
        for a, b in zip(price(key[0], D, cands, False), res):
            assert (a is None) == (b is None) and (a is None or abs(a["net"] - b["net"]) < 1e-6), (key, "engine parity")


def periods_of(phase: str, sessions: list) -> dict:
    if phase == "design":
        return {"design": sessions, "wf": [d for d in sessions if L.in_wf(d)]}
    return {phase: sessions}


def evaluate(phase: str, lo: date, hi: date) -> dict:
    M = mods()
    S = M["S"]
    sessions = S.sessions_in(lo, hi)
    pers = periods_of(phase, sessions)
    sets = {p: set(ss) for p, ss in pers.items()}
    out = {"phase": phase, "sessions": len(sessions), "parity": {}, "arms": {}, "both": {}, "wf_q": {}}
    units = {a: units_of(a, lo, hi) for a in K.ARMS}
    if phase in ("design", "final"):
        for a in K.ARMS:
            out["parity"][a] = parity(a, phase, units[a])
        plain = units_of("R51plain", lo, hi)
        out["parity"]["R51plain"] = parity("R51plain", phase, plain)
        if phase == "design":
            for a in K.ARMS:
                engine_parity(units[a])
    trades = {}
    ru, rp = {}, {}
    for rule in K.RULES:
        for a in K.ARMS:
            ru[(a, rule)] = rule_units(units[a], rule)
            rp[(a, rule)] = priced_units(ru[(a, rule)], rule)
            trades[(a, rule)] = walk(ru[(a, rule)], rp[(a, rule)])
            print(phase, a, rule, len(trades[(a, rule)]), round(sum(t["net"] for t in trades[(a, rule)])), flush=True)
        trades[("ALONE", rule)] = sorted(trades[("R72", rule)] + trades[("R51", rule)], key=lambda t: (t["day"], t["er"]))
        for cap in ("NOCAP", "CAP2"):
            trades[(cap, rule)] = book({a: ru[(a, rule)] for a in K.ARMS}, {a: rp[(a, rule)] for a in K.ARMS}, sessions,
                                       cap)
            print(phase, cap, rule, len(trades[(cap, rule)]), round(sum(t["net"] for t in trades[(cap, rule)])), flush=True)
    for (who, rule), tr in trades.items():
        dest = out["arms"].setdefault(who, {}) if who in K.ARMS else out["both"].setdefault(who, {})
        dest[rule] = {p: L.stats([t for t in tr if t["day"] in sets[p]], ss) for p, ss in pers.items()}
        if phase == "design":
            out["wf_q"].setdefault(who, {})[rule] = [round(sum(t["net"] for t in tr if a_ <= t["day"] <= b_))
                                                     for a_, b_ in K.WF_Q]
    if phase == "final":
        out["twins"] = twins(trades, sessions)
    if phase == "design":
        out["levels"] = levels(trades)
    _save(f"trades_{phase}", {f"{a}|{r}": v for (a, r), v in trades.items()})
    return out


def twins(trades: dict, sessions: list) -> dict:
    """PREREG gate 3 on the final period: entry twin and / or exit twin per arm and test; p = the larger."""
    rng = np.random.default_rng(K.SEED)
    out = {}
    for a in K.ARMS:
        for rule in K.TESTS:
            tr = trades[(a, rule)]
            act = sum(t["net"] for t in tr)
            r = dict(net=round(act))
            ps = []
            if rule in K.ENTRY_BASE:
                tw = L.entry_twin_totals(tr, trades[(a, K.ENTRY_BASE[rule])], sessions, K.TWINS, rng)
                r["entry_p"] = round(L.twin_p(act, tw), 6)
                r["entry_mean"] = round(float(tw.mean()))
                ps.append(r["entry_p"])
            if rule in K.EXIT_TWIN:
                tw, ch = exit_twin(tr, K.TWINS, rng)
                r["exit_p"] = round(L.twin_p(act, tw), 6)
                r["exit_mean"] = round(float(tw.mean()))
                r["exit_changed"] = ch
                ps.append(r["exit_p"])
            r["p"] = max(ps)
            out[f"{a}:{rule}"] = r
            print("twin", a, rule, r, flush=True)
    return out


def levels(trades: dict) -> dict:
    """PREREG section 4: per index, the typical (median) real entry premium of B0's design trades (both arms), the lot
    in force on 15 Oct 2026, and the premium move the +Rs 1,500 target and the -Rs 1,000 stop sit at."""
    M = mods()
    E, X = M["E"], M["X"]
    import r51cfg as RK
    out = {}
    tr = trades[("R72", "B0")] + trades[("R51", "B0")]
    d15 = date(2026, 10, 15)
    for u in M["A"].K.UNDS:
        px = [t["rE"] for t in tr if t["und"] == u]
        if not px:
            continue
        prem = float(np.median(px))
        qty = float(RK.lot(u, d15))
        ch = lambda a, b, u=u, qty=qty: float(E.charges(u, np.array([a]), np.array([b]), qty, d15)[0])  # noqa: E731
        tgt = X.target_price(prem, qty, K.TARGET, ch)
        stp = X.stop_price(prem, qty, K.STOP, ch, 1.0, E.SLIP[2], E.SLIP[3])
        out[u] = dict(lot=int(qty), premium=round(prem, 1), lot_value=round(prem * qty), charges=round(ch(prem, prem)),
                      target_move_pct=round(100 * (tgt / prem - 1), 1),
                      stop_move_pct=(round(100 * (stp / prem - 1), 1) if stp > 0 else None),
                      loss_at_15pct=round(prem * qty * 0.15 + ch(prem, prem * 0.85)))
    return out


def run(phase: str):
    M = mods()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists():
            raise SystemExit("R112 final already read once: " + K.FINAL_FLAG.read_text())
        assert (K.RESULTS / "design.json").exists(), "the design read comes first"
        K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        M["C"].open_final()
        lo, hi = K.FINAL_START, K.FINAL_END
    elif phase == "post":
        assert K.POST, "the post window reads the stores built to 19 Oct (R112_POST=1)"
        if not (K.RESULTS / "final.json").exists():
            raise SystemExit("the post window is read only after the final")
        if K.POST_FLAG.exists():
            raise SystemExit("R112 post window already read once: " + K.POST_FLAG.read_text())
        K.POST_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        M["C"].open_final()
        lo, hi = K.POST_START, K.POST_END
    else:
        lo, hi = K.START, K.DESIGN_END
    out = evaluate(phase, lo, hi)
    out["written"] = datetime.now(timezone.utc).isoformat()
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    tests = [(a, r) for a in K.ARMS for r in K.TESTS]
    ps = [f["twins"][f"{a}:{r}"]["p"] for a, r in tests]
    q_all = L.bh(ps, len(tests) + K.N_PRIOR)
    q_own = L.bh(ps)
    out = {}
    for (a, r), qa, qo in zip(tests, q_all, q_own):
        per = {"design": (d["arms"][a][r]["design"], d["arms"][a]["B0"]["design"]),
               "wf": (d["arms"][a][r]["wf"], d["arms"][a]["B0"]["wf"]),
               "final": (f["arms"][a][r]["final"], f["arms"][a]["B0"]["final"])}
        g = L.gates(per, qa, f["arms"][a][r]["final"]["trades"])
        g.update(q_r112=round(float(qo), 4), p=f["twins"][f"{a}:{r}"]["p"])
        out[f"{a}:{r}"] = g
    out["_m"] = dict(r112=len(tests), prior=K.N_PRIOR, total=len(tests) + K.N_PRIOR)
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final", "post"):
        run(cmd)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
