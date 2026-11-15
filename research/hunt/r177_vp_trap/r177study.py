"""R177 study (PREREG.md): volume-profile trap filters (X1 VA trap exit, X2 day-range trap exit, K1 skip after a failed
break) on Liquidity 15+5, R72 and R51 (+ Supertrend), the last year (20 Oct 2025 - 19 Oct 2026).

    R176_POST=1 python research/hunt/r177_vp_trap/r177study.py year      # read once; parity, rules, twins
    python research/hunt/r177_vp_trap/r177study.py decide                 # gates -> results/decision.json
    python research/hunt/r177_vp_trap/r177report.py                       # results/report.md

Reuses R176's engine (rule off = R101's pricing), walk, statistics and gates. Pure parts are in r177lib.py.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

os.environ.setdefault("R176_POST", "1")
HERE = Path(__file__).resolve().parent
for p in (str(HERE.parent / "r176_step_trail_500"), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r176cfg as K6  # noqa: E402
import r176study as S6  # noqa: E402
import r177lib as L  # noqa: E402

RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
RULES = ("X1", "X2", "K1")
LABEL = {"A": "today's rules", "X1": "VA trap exit (Cases 1 / 2)", "X2": "day-range trap exit (Case 5)",
         "K1": "skip after a failed value-area break"}
TESTS = tuple((a, r) for r in RULES for a in K6.ARMS)                # 9, at 1 lot
M_TOTAL = K6.M_TOTAL_B + len(TESTS)                                  # 3,997
SEED = 177
_PREV: dict = {}


def mods():
    return S6.mods()


def prev_levels(und: str, d) -> dict | None:
    """The previous trading session's TPO levels for `und` (None: no previous session or too few minutes)."""
    if und not in _PREV:
        _PREV[und] = {}
    if d not in _PREV[und]:
        M = mods()
        days = M["r51data"].load(und)
        ks = sorted(days)
        i = ks.index(d)
        lv = None
        if i > 0:
            import r51cfg
            lv = L.tpo_levels(days[ks[i - 1]].idx, float(r51cfg.STEP[und]))
        _PREV[und][d] = lv
    return _PREV[und][d]


def apply_rule(arm: str, us: list, rule: str | None) -> tuple:
    """(units with the rule applied, how many candidates it changed). rule None = A (only adds 'fx_arm')."""
    M = mods()
    E = M["E"]
    ex = S6.exits(arm)
    out, changed = [], 0
    for key, cands, res, how in us:
        und, d = key[0], key[1]
        D = M["r51data"].load(und)[d]
        close = np.asarray(D.idx[3], float)
        lv = prev_levels(und, d) if rule else None
        st = E.stack(und, D) if rule in ("X1", "X2") else None
        sq = E.sq_row(D, ex)
        nc, nr = [], []
        for c, r in zip(cands, res):
            c2 = dict(c)
            fx0 = c.get("fx", -1)
            c2["fx_arm"] = -1 if fx0 is None else int(fx0)
            if lv is not None:
                call = c["side"] == "C"
                if rule == "K1":
                    if L.failed_break(close, int(c["er"]) - 1, c["side"], lv["vah"] if call else lv["val"]):
                        changed += 1
                        continue
                else:
                    level = (lv["vah"] if call else lv["val"]) if rule == "X1" else (lv["pdh"] if call else lv["pdl"])
                    ci = st.idx(c["side"], c["strike"])
                    if ci >= 0:
                        ef = L.first_fill_row(st.S[ci][0], int(c["er"]), int(sq))
                        if ef >= 0:
                            t = L.trap_row(close, int(c["er"]), ef, c["side"], level)
                            if t >= 0:
                                c2["fx"] = L.earlier(c2["fx_arm"], t)
                                c2["trap"] = 1
                                changed += 1
            nc.append(c2)
            nr.append(r)
        out.append((key, nc, nr, how))
    return out, changed


def exit_twins(arm: str, trades: list, plain: dict, n: int, rng) -> tuple:
    """X1 / X2 twins (R176's design, 1 lot), with the arm's own index exit (fx_arm) active in the twin."""
    M = mods()
    ex = S6.exits(arm)
    tot = np.zeros(n)
    ch = 0
    for t in trades:
        p = plain.get((t["und"], S6._d(t["day"]), t["er_sig"], t["side"]))
        if p is None or not (t["xr"] < p["xr"] and p["xr"] - t["er"] >= 2):
            tot += t["net"]
            continue
        ch += 1
        rows = M["S"].twin_rows(t["er"], p["xr"], n, rng)
        D = M["r51data"].load(t["und"])[t["day"]]
        tot += M["Y"].forced_exit_nets(t["und"], D, t, ex, rows, 1, t.get("fx_arm", -1))
    return tot, ch


def run() -> dict:
    assert K6.POST, "R177 reads the stores built to 19 Oct (R176_POST=1)"
    RESULTS.mkdir(parents=True, exist_ok=True)
    if FINAL_FLAG.exists():
        raise SystemExit("R177 already read once: " + FINAL_FLAG.read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    M = mods()
    M["C"].open_final()
    lo, hi = K6.Y_START, K6.Y_END
    out = {"_meta": dict(span=f"{lo} .. {hi}", stores="19 Oct"), "parity": {}, "changed": {}, "twins": {}}
    trades = {}                                         # trades[(variant, lots)][arm], variant in A, rules, B, B+rule
    rng = np.random.default_rng(SEED)
    for arm in K6.ARMS:
        us0 = S6.units(arm, lo, hi)
        usA, _ = apply_rule(arm, us0, None)
        P = sc = thr = None
        if arm in K6.PICKER_ARMS:
            P = S6.picker()
            sc, thr = P.scores(arm, us0)
        for lots in K6.LOTS:
            prA = S6.priced(arm, usA, False, lots)
            if lots == 1:
                same = all((x is None) == (y is None) and (x is None or abs(x["net"] - y["net"]) < 1e-6)
                           for a_, b_ in zip(prA, [u[2] for u in us0]) for x, y in zip(a_, b_))
                assert same, ("engine parity", arm)
            a = [t for t in S6.walk(arm, usA, prA) if lo <= t["day"] <= hi]
            if lots == 1:
                af = S6.in_span(a, "final")
                pub = K6.PARITY[(arm, "final")]
                r = dict(trades=len(af), net=round(sum(t["net"] for t in af)), published=list(pub))
                r["reproduced"] = r["trades"] == pub[0] and abs(r["net"] - pub[1]) <= 1
                out["parity"][arm] = r
                print(arm, "parity", r, flush=True)
                assert r["reproduced"], ("parity failed", arm, r)
            trades.setdefault(("A", lots), {})[arm] = a
            trades.setdefault(("B", lots), {})[arm] = a if P is None else [t for t in a if P.kept(t, sc, thr)]
            plain = {(t["und"], S6._d(t["day"]), c["er"], c["side"]): t
                     for (key, cands, _, _), row in zip(usA, prA) for c, t in zip(cands, row) if t is not None}
            for rule in RULES:
                usR, ch = apply_rule(arm, us0, rule)
                tr = [t for t in S6.walk(arm, usR, S6.priced(arm, usR, False, lots)) if lo <= t["day"] <= hi]
                trades.setdefault((rule, lots), {})[arm] = tr
                trades.setdefault(("B+" + rule, lots), {})[arm] = tr if P is None else [t for t in tr if P.kept(t, sc, thr)]
                if lots == 1:
                    out["changed"][f"{arm}:{rule}"] = dict(candidates_changed=ch, trades_trapped=sum(1 for t in tr if t.get("trap")))
                    if rule == "K1":
                        k = max(0, len(a) - len(tr))
                        tw = L.selection_twin([t["net"] for t in a], k, K6.TWINS, rng)
                        chg = k
                    else:
                        tw, chg = exit_twins(arm, tr, plain, K6.TWINS, rng)
                    act = sum(t["net"] for t in tr)
                    out["twins"][f"{arm}:{rule}"] = dict(p=round(M["S"].twin_p(act, tw), 4), mean=round(float(tw.mean())),
                                                         changed=chg)
                    print(arm, rule, "twin", out["twins"][f"{arm}:{rule}"], flush=True)
                print(arm, rule, lots, len(tr), round(sum(t["net"] for t in tr)), "A", len(a),
                      round(sum(t["net"] for t in a)), flush=True)
    for per in K6.YEAR_PERIODS:
        plo, phi = K6.span_of(per)
        n_sess = len(S6.sessions(plo, phi))
        months = K6.months_between(plo, phi)
        res = {"sessions": n_sess}
        for (v, lots), by_arm in trades.items():
            sub = {a_: [t for t in by_arm[a_] if plo <= t["day"] <= phi] for a_ in K6.ARMS}
            res[f"{v}@{lots}"] = {a_: S6.describe(sub[a_], n_sess, months) for a_ in K6.ARMS}
            res[f"{v}@{lots}"]["ALL"] = S6.describe(S6.together(sub), n_sess, months)
        out[per] = res
    (RESULTS / "year.json").write_text(json.dumps(out, indent=1, default=str))
    return out


# ================================================================================================ Amendment A
FINAL_FLAG_A = RESULTS / "FINAL_READ_A"
M_TOTAL_A = M_TOTAL + len(K6.ARMS)                                  # 4,000


GAP = {"v": K6.STEP}                                                # Amendment B sets Rs 1,000
FINAL_FLAG_B = RESULTS / "FINAL_READ_B"
M_TOTAL_B = M_TOTAL_A + 2 * len(K6.ARMS)                            # 4,006


def _priced_stop(arm: str, us: list, lots: int) -> list:
    """Price with the Rs 1,000 a lot entry stop + the step trail (gap GAP['v'] a lot: Rs 500, or Rs 1,000 in B)."""
    old, oldg = S6.ENTRY["v"], S6.GAP["v"]
    S6.ENTRY["v"], S6.GAP["v"] = K6.ENTRY_B, GAP["v"]
    try:
        return S6.priced(arm, us, True, lots)
    finally:
        S6.ENTRY["v"], S6.GAP["v"] = old, oldg


def run_a(b: bool = False) -> dict:
    """Amendment A (b=False): A, S1k (the stop rule alone) and K1S (K1 + the stop rule), 1-3 lots, with / without the
    picker. Amendment B (b=True): the same with a Rs 1,000 gap under each step (SG / K1SG stored under the same keys),
    plus R176's exit twins for SG."""
    assert K6.POST, "R177 reads the stores built to 19 Oct (R176_POST=1)"
    flag = FINAL_FLAG_B if b else FINAL_FLAG_A
    if flag.exists():
        raise SystemExit("R177 amendment already read once: " + flag.read_text())
    flag.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    GAP["v"] = 1000.0 if b else K6.STEP
    M = mods()
    M["C"].open_final()
    lo, hi = K6.Y_START, K6.Y_END
    out = {"_meta": dict(span=f"{lo} .. {hi}", stores="19 Oct", rule="K1 + Rs 1,000 a lot entry stop + Rs 500 step trail"),
           "parity": {}, "twins": {}, "check_s1k": {}, "twins_sg": {}, "gap_a_lot": GAP["v"]}
    r176b = json.loads((HERE.parent / "r176_step_trail_500" / "results" / "yearb.json").read_text())
    trades = {}
    rng = np.random.default_rng(SEED)
    for arm in K6.ARMS:
        us0 = S6.units(arm, lo, hi)
        usA, _ = apply_rule(arm, us0, None)
        usK, kch = apply_rule(arm, us0, "K1")
        P = sc = thr = None
        if arm in K6.PICKER_ARMS:
            P = S6.picker()
            sc, thr = P.scores(arm, us0)
        for lots in K6.LOTS:
            a = [t for t in S6.walk(arm, usA, S6.priced(arm, usA, False, lots)) if lo <= t["day"] <= hi]
            s1 = [t for t in S6.walk(arm, usA, _priced_stop(arm, usA, lots)) if lo <= t["day"] <= hi]
            ks = [t for t in S6.walk(arm, usK, _priced_stop(arm, usK, lots)) if lo <= t["day"] <= hi]
            if lots == 1:
                af = S6.in_span(a, "final")
                pub = K6.PARITY[(arm, "final")]
                r = dict(trades=len(af), net=round(sum(t["net"] for t in af)), published=list(pub))
                r["reproduced"] = r["trades"] == pub[0] and abs(r["net"] - pub[1]) <= 1
                out["parity"][arm] = r
                assert r["reproduced"], ("parity failed", arm, r)
            if not b:
                ref = r176b["year"][f"S@{lots}"][arm]
                out["check_s1k"][f"{arm}@{lots}"] = dict(trades=len(s1), net=round(sum(t["net"] for t in s1)),
                                                         r176b=[ref["trades"], ref["net"]])
                print(arm, lots, "S1k check", out["check_s1k"][f"{arm}@{lots}"], flush=True)
            elif lots == 1:
                prA = S6.priced(arm, usA, False, 1)
                plain = {(t["und"], S6._d(t["day"]), c["er"], c["side"]): t
                         for (key, cands, _, _), row in zip(usA, prA) for c, t in zip(cands, row) if t is not None}
                tw, chg = exit_twins(arm, s1, plain, K6.TWINS, rng)
                out["twins_sg"][arm] = dict(p=round(M["S"].twin_p(sum(t["net"] for t in s1), tw), 4),
                                            mean=round(float(tw.mean())), changed=chg)
                print(arm, "SG twin", out["twins_sg"][arm], flush=True)
            for v, tr in (("A", a), ("S1k", s1), ("K1S", ks)):
                trades.setdefault((v, lots), {})[arm] = tr
                trades.setdefault(("B" if v == "A" else "B+" + v, lots), {})[arm] = (
                    tr if P is None else [t for t in tr if P.kept(t, sc, thr)])
            if lots == 1:
                k = max(0, len(s1) - len(ks))
                tw = L.selection_twin([t["net"] for t in s1], k, K6.TWINS, rng)
                act = sum(t["net"] for t in ks)
                out["twins"][arm] = dict(p=round(M["S"].twin_p(act, tw), 4), mean=round(float(tw.mean())), dropped=k,
                                         candidates_skipped=kch)
                print(arm, "K1S twin", out["twins"][arm], flush=True)
            print(arm, lots, {v: (len(trades[(v, lots)][arm]), round(sum(t["net"] for t in trades[(v, lots)][arm])))
                              for v in ("A", "S1k", "K1S")}, flush=True)
    for per in K6.YEAR_PERIODS:
        plo, phi = K6.span_of(per)
        n_sess = len(S6.sessions(plo, phi))
        months = K6.months_between(plo, phi)
        res = {"sessions": n_sess}
        for (v, lots), by_arm in trades.items():
            sub = {a_: [t for t in by_arm[a_] if plo <= t["day"] <= phi] for a_ in K6.ARMS}
            res[f"{v}@{lots}"] = {a_: S6.describe(sub[a_], n_sess, months) for a_ in K6.ARMS}
            res[f"{v}@{lots}"]["ALL"] = S6.describe(S6.together(sub), n_sess, months)
        out[per] = res
    (RESULTS / ("year_b.json" if b else "year_a.json")).write_text(json.dumps(out, indent=1, default=str))
    return out


def decide_a(b: bool = False) -> dict:
    """Amendment A: K1S (3 tests, m 4,000). Amendment B: SG and K1SG (6 tests, m 4,006); keys 'S1k' / 'K1S' in
    year_b.json hold SG / K1SG."""
    r = json.loads((RESULTS / ("year_b.json" if b else "year_a.json")).read_text())
    tests = [(a, "K1S", r["twins"][a]["p"]) for a in K6.ARMS]
    if b:
        tests = [(a, "S1k", r["twins_sg"][a]["p"]) for a in K6.ARMS] + tests
    ps = [t[2] for t in tests]
    q_all, q_own = S6.bh_q(ps, M_TOTAL_B if b else M_TOTAL_A), S6.bh_q(ps, len(ps))
    out = {}
    for (a, v, p_), qa, qo in zip(tests, q_all, q_own):
        per = {p: dict(S=r[p][f"{v}@1"][a], A=r[p]["A@1"][a]) for p in K6.YEAR_PERIODS}
        g = S6.gates(per, qa, K6.YEAR_PERIODS, K6.YEAR_BETTER)
        g.update(q_own=round(qo, 4), twin_p=p_,
                 passed_except_account_stop=not [f for f in g["fails"] if "account stop" not in f])
        name = ({"S1k": "SG", "K1S": "K1SG"}[v] if b else v)
        out[a if not b else f"{a}:{name}"] = g
    out["_m"] = dict(tests=len(tests), prior=M_TOTAL_A if b else M_TOTAL, total=M_TOTAL_B if b else M_TOTAL_A)
    (RESULTS / ("decision_b.json" if b else "decision_a.json")).write_text(json.dumps(out, indent=1))
    return out


def decide() -> dict:
    r = json.loads((RESULTS / "year.json").read_text())
    ps = [r["twins"][f"{a}:{v}"]["p"] for a, v in TESTS]
    q_all, q_own = S6.bh_q(ps, M_TOTAL), S6.bh_q(ps, len(TESTS))
    out = {}
    for (a, v), qa, qo in zip(TESTS, q_all, q_own):
        per = {p: dict(S=r[p][f"{v}@1"][a], A=r[p]["A@1"][a]) for p in K6.YEAR_PERIODS}
        g = S6.gates(per, qa, K6.YEAR_PERIODS, K6.YEAR_BETTER)
        g.update(q_r177=round(qo, 4), twin_p=r["twins"][f"{a}:{v}"]["p"],
                 passed_except_account_stop=not [f for f in g["fails"] if "account stop" not in f])
        out[f"{a}:{v}"] = g
    out["_m"] = dict(r177=len(TESTS), prior=K6.M_TOTAL_B, total=M_TOTAL)
    (RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "year":
        run()
    elif cmd == "year-a":
        run_a()
    elif cmd == "decide-a":
        print(json.dumps(decide_a(), indent=1))
    elif cmd == "year-b":
        run_a(True)
    elif cmd == "decide-b":
        print(json.dumps(decide_a(True), indent=1))
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
