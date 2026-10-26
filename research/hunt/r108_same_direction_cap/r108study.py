"""R108 study (PREREG.md): the same-direction cap as the app runs it, and 7 alternatives.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                       # R101's clean stores (to 13 Oct)
    R51_CLEAN=1 python research/hunt/r108_same_direction_cap/r108study.py design  # design + walk-forward (B1 parity asserted)
    R51_CLEAN=1 python research/hunt/r108_same_direction_cap/r108study.py final   # the ONE final read (refuses a second)
    python research/hunt/r108_same_direction_cap/r108study.py decide              # BH + gates -> results/decision.json

The arms come from R106's engine on R101's candidates; each arm alone must reproduce R101's published trades and net.
The book's walk, the rules, twins and gates are in r108book.py (pure, tested).
"""
from __future__ import annotations

import itertools
import json
import pickle
import sys
from collections import defaultdict
from datetime import datetime, timezone

import numpy as np

import r108cfg as K
import r108book as B

_M: dict = {}


def mods():
    if not _M:
        import r106study as S
        M = S.mods()
        import r101cfg
        _M.update(S=S, r101cfg=r101cfg, r51data=M["r51data"])
    return _M


# ------------------------------------------------------------------------------------------------ the arms
def arm_window(arm: str, lo, hi, phase: str):
    """R101's units of one arm and its B1 trades (each arm alone), checked against R101's published figure."""
    M = mods()
    S = M["S"]
    units = S.prepare(arm, lo, hi)
    tr = S.run_rule(arm, units, [u[2] for u in units], None)
    pub = M["r101cfg"].PUBLISHED[arm].get(phase)
    n0, net0 = len(tr), round(sum(t["net"] for t in tr))
    if pub is not None:
        assert (pub[0] is None or pub[0] == n0) and abs(pub[1] - net0) <= 1, (arm, phase, "B1 != R101", n0, net0, pub)
    print(arm, phase, "alone", n0, net0, "published", pub, flush=True)
    for t in tr:
        t["arm"] = arm
    return units, tr


def candidates(units_by_arm: dict) -> dict:
    """{day: (cands, priced)} for the book: every arm's candidates with the fields r108book needs."""
    out = defaultdict(lambda: ([], []))
    g = 0
    for arm in K.ARMS:
        for key, cands, res, how in units_by_arm[arm]:
            g += 1
            d = key[1]
            cs, ps = out[d]
            for j, (c, r) in enumerate(zip(cands, res)):
                risk = 0.0
                if r is not None:
                    risk = float(r["E"]) * float(r["qty"]) * K.STOP_SHARE[arm] + K.CHARGES_PER_LOT
                cs.append(dict(arm=arm, und=key[0], side=c["side"], er=int(c["er"]), g=g, j=j, how=how,
                               strike=float(c["strike"]) if c.get("strike") is not None else None,
                               book=c.get("book"), risk=risk))
                ps.append(r)
    return dict(out)


def load(phase: str):
    """The phase's arms: design -> START..DESIGN_END; final -> the design window too (the STOPS week / month and EDGE's
    history run on from it; nothing of the final window is read in the design phase)."""
    wins = [("design", K.START, K.DESIGN_END)]
    if phase == "final":
        wins.append(("final", K.FINAL_START, K.FINAL_END))
    units = {a: [] for a in K.ARMS}
    alone = {a: [] for a in K.ARMS}
    for name, lo, hi in wins:
        for arm in K.ARMS:
            u, tr = arm_window(arm, lo, hi, name)
            units[arm] += u
            alone[arm] += tr
    S = mods()["S"]
    hi = K.FINAL_END if phase == "final" else K.DESIGN_END
    sessions = S.sessions_in(K.START, hi)
    return units, alone, sessions


# ------------------------------------------------------------------------------------------------ walks
def walks(day_cands: dict, sessions: list, arms=K.ARMS, names=K.BASES[1:] + K.TESTS) -> dict:
    """Every rule's walk over all sessions (restricted to `arms`)."""
    dc = day_cands
    if tuple(arms) != tuple(K.ARMS):
        dc = {}
        for d, (cs, ps) in day_cands.items():
            keep = [i for i, c in enumerate(cs) if c["arm"] in arms]
            dc[d] = ([cs[i] for i in keep], [ps[i] for i in keep])
    out = {}
    nocap = B.walk_period(sessions, dc, B.make_rule("NOCAP"))
    out["NOCAP"] = nocap
    # EDGE's history: each arm's own record in the NOCAP book (real and would-be), by the day it closed
    rec = defaultdict(list)
    for d, recs in nocap.items():
        for x in recs:
            if x["out"] in (B.REAL, B.WB_HELD, B.WB_RULE):
                rec[d].append((x["c"]["arm"], float(x["r"]["net"])))
    eo = B.edge_orders(sessions, rec)
    for n in names:
        if n == "NOCAP":
            continue
        out[n] = B.walk_period(sessions, dc, B.make_rule(n), edge_order=lambda d: eo[d])
    return out, eo


def r107_steps(day_cands: dict, sessions: list) -> dict:
    """The attribution of R107's lead (Part A): R107's model, then each app choice added in turn, ending at CAP2."""
    cap = B.make_rule
    return {
        "R107 model": B.walk_period(sessions, day_cands, B.CountCap(2, keep_r72=False), arm_order=K.R107_ORDER,
                                    held_rule=False, would_be=False),
        "+ would-be accounting": B.walk_period(sessions, day_cands, B.CountCap(2, keep_r72=False),
                                               arm_order=K.R107_ORDER, held_rule=False, would_be=True),
        "+ one option, one arm": B.walk_period(sessions, day_cands, B.CountCap(2, keep_r72=False),
                                               arm_order=K.R107_ORDER, held_rule=True, would_be=True),
        "+ the app's order (Liquidity first)": B.walk_period(sessions, day_cands, B.CountCap(2, keep_r72=False)),
        "+ R72's kept place = CAP2": B.walk_period(sessions, day_cands, cap("CAP2")),
        "R107 model without a cap": B.walk_period(sessions, day_cands, B.NoCap(), arm_order=K.R107_ORDER,
                                                  held_rule=False, would_be=False),
    }


# ------------------------------------------------------------------------------------------------ evaluation
def per_arm(walk: dict, sess: set) -> dict:
    out = {}
    for arm in K.ARMS:
        rows = [x for d, recs in walk.items() if d in sess for x in recs if x["c"]["arm"] == arm]
        def grp(o):
            xs = [x for x in rows if x["out"] in o]
            return dict(n=len(xs), net=round(sum(x["r"]["net"] for x in xs)))
        out[arm] = dict(real=grp((B.REAL,)), refused=grp((B.WB_RULE, B.DROP)), held=grp((B.WB_HELD,)))
    return out


def period_view(walk: dict, sessions: list, period: str, nocap_walk: dict) -> dict:
    sess = [d for d in sessions if K.in_period(d, period)]
    ss = set(sess)
    real = [t for t in B.trades_of({d: walk[d] for d in sess if d in walk})]
    s = B.summary(real, sess, K.ACCOUNT_MONTH, K.ACCOUNT_DD)
    s["arms"] = per_arm(walk, ss)
    s["refused"] = sum(v["refused"]["n"] for v in s["arms"].values())
    # Liquidity's trades taken only because a refused entry left its book free (not in the NOCAP book)
    nk = {(t["und"], t["day"], t["er_sig"], t.get("book")) for t in
          B.trades_of({d: nocap_walk[d] for d in sess if d in nocap_walk}) if t["arm"] == "LIQ"}
    extra = [t for t in real if t["arm"] == "LIQ" and (t["und"], t["day"], t["er_sig"], t.get("book")) not in nk]
    s["liq_extra"] = dict(n=len(extra), net=round(sum(t["net"] for t in extra)))
    return s


def alone_view(alone: dict, sessions: list, period: str, arms=K.ARMS) -> dict:
    sess = [d for d in sessions if K.in_period(d, period)]
    tr = [t for a in arms for t in alone[a] if K.in_period(t["day"], period)]
    s = B.summary(tr, sess, K.ACCOUNT_MONTH, K.ACCOUNT_DD)
    s["per_arm"] = {a: B.summary([t for t in alone[a] if K.in_period(t["day"], period)], sess, K.ACCOUNT_MONTH, K.ACCOUNT_DD)
                    for a in arms}
    return s


def twins_for(walk: dict, nocap: dict, sessions: list, period: str, rng) -> dict:
    sess = [d for d in sessions if K.in_period(d, period)]
    base = {d: [x["r"]["net"] for x in nocap.get(d, []) if x["out"] == B.REAL] for d in sess}
    k = {d: max(0, len(base[d]) - sum(1 for x in walk.get(d, []) if x["out"] == B.REAL)) for d in sess}
    T = B.twin_matrix(base, k, sess, K.TWINS, rng)
    tr = B.matrix_r_dd(T)
    real = B.trades_of({d: walk[d] for d in sess if d in walk})
    v = B.summary(real, sess)["r_dd"]
    return dict(p=round(B.twin_p(v, tr), 6), median_r_dd=round(float(np.median(tr)), 4),
                median_net=round(float(np.median(T.sum(axis=1)))), refusals_matched=int(sum(k.values())))


def _clean(x):
    if isinstance(x, dict):
        return {str(k): _clean(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [_clean(v) for v in x]
    if isinstance(x, float):
        return None if not np.isfinite(x) else round(x, 4)
    if isinstance(x, (np.integer,)):
        return int(x)
    if isinstance(x, (np.floating,)):
        return None if not np.isfinite(x) else round(float(x), 4)
    return x


def evaluate(phase: str) -> dict:
    units, alone, sessions = load(phase)
    dc = candidates(units)
    W, eo = walks(dc, sessions)
    periods = ("design", "wf") if phase == "design" else ("final",)
    rng = np.random.default_rng(K.SEED + (0 if phase == "design" else 1))
    out = {"_meta": dict(phase=phase, sessions=len(sessions), twins=K.TWINS, seed=K.SEED, n_prior=K.N_PRIOR)}
    steps = r107_steps(dc, sessions)
    for per in periods:
        res = {"ALONE": alone_view(alone, sessions, per)}
        for n, w in W.items():
            res[n] = period_view(w, sessions, per, W["NOCAP"])
            if n != "NOCAP":
                res[n]["twin"] = twins_for(w, W["NOCAP"], sessions, per, rng)
                res[n]["changed_vs_cap2"] = B.changed_vs(
                    B.trades_of({d: w[d] for d in w if K.in_period(d, per)}),
                    B.trades_of({d: W["CAP2"][d] for d in W["CAP2"] if K.in_period(d, per)}))
            print(per, n, res[n]["net"], res[n]["trades"], res[n].get("twin", {}).get("p"), flush=True)
        res["_r107_steps"] = {k: period_view(w, sessions, per, W["NOCAP"]) for k, w in steps.items()}
        # the account rule by arm set (Part 7): all 15 subsets, NOCAP and CAP2 books
        subs = {}
        for r in range(1, len(K.ARMS) + 1):
            for arms in itertools.combinations(K.ARMS, r):
                ws, _ = walks(dc, sessions, arms=arms, names=("NOCAP", "CAP2"))
                subs["+".join(arms)] = {n: {k: v for k, v in period_view(ws[n], sessions, per, ws["NOCAP"]).items()
                                            if k in ("net", "trades", "max_dd", "months_below", "worst_month",
                                                     "worst_day", "dd_breaches", "days_below")} for n in ("NOCAP", "CAP2")}
        res["_subsets"] = subs
        res["_edge_order_last"] = list(eo[[d for d in sessions if K.in_period(d, per)][-1]])
        out[per] = res
    K.OUT.mkdir(parents=True, exist_ok=True)
    save = {n: B.trades_of(w, outs=(B.REAL, B.WB_RULE, B.WB_HELD, B.DROP)) for n, w in W.items()}
    (K.OUT / f"{phase}.pkl").write_bytes(pickle.dumps(save, protocol=pickle.HIGHEST_PROTOCOL))
    return _clean(out)


def run(phase: str):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists():
            raise SystemExit("R108 final already read once: " + K.FINAL_FLAG.read_text())
        K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        mods()["S"].mods()["C"].open_final()
    out = evaluate(phase)
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    ps = [f["final"][t]["twin"]["p"] for t in K.TESTS]
    q_all = B.bh(ps, len(ps) + K.N_PRIOR)
    q_own = B.bh(ps)
    out = {}
    for t, qa, qo in zip(K.TESTS, q_all, q_own):
        per = {p: ((d if p != "final" else f)[p][t], (d if p != "final" else f)[p]["CAP2"]) for p in K.PERIODS}
        g = B.gates(per, qa, f["final"][t]["changed_vs_cap2"], K.PERIODS, K.GATE_Q, K.MIN_CHANGED)
        g["q_r108"] = round(float(qo), 4)
        g["twin_p"] = f["final"][t]["twin"]["p"]
        out[t] = g
    out["_m"] = dict(r108=len(ps), prior=K.N_PRIOR, total=len(ps) + K.N_PRIOR)
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd in ("design", "final"):
        run(cmd)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
