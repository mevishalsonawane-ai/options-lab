"""R115 study (PREREG.md): 12 price-action filters / exits on the four paper arms, each against today's rules.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                       # R101's clean stores (to 13 Oct)
    python research/hunt/r115_price_action_arms/r115study.py design              # design + walk-forward, parity asserted
    python research/hunt/r115_price_action_arms/r115study.py final               # the ONE final read (refuses a second)
    python research/hunt/r115_price_action_arms/r115study.py decide              # twins' BH + gates -> results/decision.json

Arms: R101's units (R72, R52, LIQ) and R51 with R73's Supertrend (10, 3) filter (R112's r51_st_signals), walked by
R106's run_rule with today's rules. Filters drop candidates before the walk; the exit re-prices with a forced exit row on
R106's engine. Every feature is read from the index's own bars (fam_r44.bars5 / the store's index minutes).
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timezone

import numpy as np

import r115cfg as K
import r115lib as L

_M: dict = {}


def mods():
    if not _M:
        import r112cfg  # noqa: F401  (puts r73, r108, r106 and through them r101 / r51 on the path)
        import r106study as S
        M = S.mods()
        import r112study as S12
        _M.update(S=S, S12=S12, A=M["A"], X=M["X"], E=M["E"], C=M["C"], r51data=M["r51data"],
                  fam_r44=M["A"].mods()["fam_r44"])
    return _M


# ================================================================================================ the arms
def units(arm: str, lo: date, hi: date) -> list:
    M = mods()
    A = M["A"]
    if arm == "R51":
        return A._side2_units(M["S12"].r51_st_signals(), lo, hi, A._xl())
    return A.UNITS[arm](lo, hi)


def walk(arm: str, us: list, priced: list | None = None) -> list:
    """Today's taking walk (R101 / R106 B0) of the units with their prices (default: R101's own)."""
    S = mods()["S"]
    return S.run_rule(arm, us, [u[2] for u in us] if priced is None else priced, None)


def filtered(us: list, keep_fn) -> list:
    out = []
    for g, (key, cands, res, how) in enumerate(us):
        idx = [j for j, c in enumerate(cands) if keep_fn(g, j, key, c)]
        if idx:
            out.append((key, [cands[j] for j in idx], [res[j] for j in idx], how))
    return out


# ================================================================================================ features (index bars)
_BARS: dict = {}


def bars(und: str) -> dict:
    """The index's continuous 5-minute bars with the lookups the features need (cached)."""
    if und not in _BARS:
        d, b, o, h, l, c = mods()["fam_r44"].bars5(und)
        h, l = np.asarray(h, float), np.asarray(l, float)
        ds = L.day_starts(d)
        nxt = np.empty(len(d), np.int64)
        end = len(d)
        for i in range(len(d) - 1, -1, -1):
            nxt[i] = end
            if ds[i] == i:
                end = i
        days, dhi, dlo = L.daily_hl(d, h, l)
        _BARS[und] = dict(d=d, b=np.asarray(b), o=np.asarray(o, float), h=h, l=l, c=np.asarray(c, float), ds=ds,
                          nxt=nxt, pos={(d[i], int(b[i])): i for i in range(len(d))}, days=days, dhi=dhi, dlo=dlo,
                          dk=L.prev_day_index(days))
    return _BARS[und]


def signal_bar(und: str, day: date, er: int):
    """Flat index of a Donchian candidate's signal bar (b = er / 5 - 1), or None."""
    return bars(und)["pos"].get((day, int(er) // 5 - 1))


def keep(rule: str, arm: str, key: tuple, c: dict) -> bool:
    """PREREG section 3: does `rule` keep candidate c of `arm` (unit key (und, day, book))? Reads only bars up to the
    candidate's signal bar; unknown never confirms."""
    und, day = key[0], key[1]
    side = c["side"]
    if rule == "quality_ex0920" and int(c["er"]) == 5:
        return True
    if arm in ("R72", "R51"):
        B = bars(und)
        i = signal_bar(und, day, c["er"])
        if i is None:
            return False
        if rule in ("quality", "quality_ex0920"):
            return L.bar_quality(B["o"][i], B["h"][i], B["l"][i], B["c"][i], side)
        if rule == "no_outside":
            o = L.is_outside(B["h"], B["l"], i)
            return o is not None and not o
        k = B["dk"][day]
        if rule == "fresh":
            if k < 1:
                return False
            return L.fresh(side, B["h"], B["l"], int(B["ds"][i]), i, B["dhi"][k - 1], B["dlo"][k - 1])
        if rule == "compression":
            return L.compression(k, B["dhi"], B["dlo"])
        raise ValueError(rule)
    D = mods()["r51data"].load(und)[day]
    if arm == "R52" and rule == "quality":
        return L.bar_quality(*L.minute_bar(D.idx, 0, 5), side)
    if arm == "LIQ" and rule == "quality":
        tf = int(key[2])
        er = int(c["er"])
        return L.bar_quality(*L.minute_bar(D.idx, er - tf, er), side)
    raise ValueError((arm, rule))


def failed_break_fx(und: str, day: date, c: dict) -> int:
    B = bars(und)
    i = signal_bar(und, day, c["er"])
    if i is None:
        return -1
    lev = L.channel_level(B["h"], B["l"], i, c["side"])
    return L.failed_break_row(c["side"], i, lev, B["b"], B["c"], int(B["nxt"][i]))


def exit_priced(arm: str, us: list) -> list:
    """Every candidate re-priced with the failed-break row as a forced exit (R106's engine, today's exits otherwise);
    a candidate with no failed break keeps R101's own price (engine parity asserted in design)."""
    M = mods()
    ex = M["A"]._xl()
    out = []
    for key, cands, res, how in us:
        fxs = [failed_break_fx(key[0], key[1], c) for c in cands]
        if all(f < 0 for f in fxs):
            out.append(list(res))
            continue
        D = M["r51data"].load(key[0])[key[1]]
        cs = [dict(c, fx=f) for c, f in zip(cands, fxs)]
        pr = M["X"].price(key[0], D, cs, ex)
        out.append([p if f >= 0 else r for p, r, f in zip(pr, res, fxs)])
    return out


def engine_parity(us: list, n: int = 300):
    """R106's engine without a forced exit prices as R101 (the first n units)."""
    M = mods()
    ex = M["A"]._xl()
    for key, cands, res, how in us[:n]:
        D = M["r51data"].load(key[0])[key[1]]
        for a, b in zip(M["X"].price(key[0], D, cands, ex), res):
            assert (a is None) == (b is None) and (a is None or abs(a["net"] - b["net"]) < 1e-6), (key, "engine parity")


# ================================================================================================ one test
def rule_trades(test: str, arm: str, us: list) -> tuple:
    """(trades, removed candidates) of a test on the units."""
    kind, rule = (K.TESTS.get(test) or K.REPORTED[test])[1:]
    if kind == "exit":
        return walk(arm, us, exit_priced(arm, us)), 0
    fu = filtered(us, lambda g, j, key, c: keep(rule, arm, key, c))
    removed = sum(len(u[1]) for u in us) - sum(len(u[1]) for u in fu)
    return walk(arm, fu), removed


def filter_twins(arm: str, rule: str, us: list, n: int, rng) -> np.ndarray:
    """Drop as many candidates at random as the rule dropped, walk again (R101); n totals."""
    flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
    k = sum(1 for g, u in enumerate(us) for c in u[1] if not keep(rule, arm, u[0], c))
    out = np.zeros(n)
    for x in range(n):
        gone = {flat[i] for i in L.random_drop(len(flat), k, rng)}
        out[x] = sum(t["net"] for t in walk(arm, filtered(us, lambda g, j, key, c: (g, j) not in gone)))
    return out


def exit_twins(arm: str, rule_tr: list, base_res: dict, n: int, rng) -> tuple:
    """Each trade the failed-break exit sold before today's exit is sold at a random minute in between instead."""
    M = mods()
    ex = M["A"]._xl()
    fixed, rows = 0.0, []
    for t in rule_tr:
        p = base_res.get((t["und"], t["day"], int(t["er_sig"]), t["side"]))
        if p is None or int(t["xr"]) >= int(p["xr"]) or int(p["xr"]) - int(t["er"]) < 2:
            fixed += float(t["net"])
            continue
        D = M["r51data"].load(t["und"])[t["day"]]
        r = np.arange(int(t["er"]) + 1, int(p["xr"]), dtype=np.int64)
        rows.append(M["X"].forced_exit_nets(t["und"], D, t, ex, r, -1))
    return L.exit_twin_totals(fixed, rows, n, rng), len(rows)


# ================================================================================================ evaluation
def periods(phase: str) -> tuple:
    return ("design", "wf") if phase == "design" else ("final",)


def by_period(trades: list, per: str) -> list:
    return [t for t in trades if K.in_period(t["day"], per)]


def describe(trades: list, per: str) -> dict:
    tr = by_period(trades, per)
    s = L.stats(tr)
    s["open_0915_0930"] = L.stats(L.open_slice(tr))
    return s


def parity(arm: str, phase: str, base: list):
    tr = by_period(base, phase)
    n0, net0 = len(tr), round(sum(t["net"] for t in tr))
    pub = K.PUBLISHED[(arm, phase)]
    print(arm, phase, "today's rules", n0, net0, "published", pub, flush=True)
    assert pub[0] == n0 and abs(pub[1] - net0) <= 1, (arm, phase, "today's rules do not reproduce", n0, net0, pub)
    return dict(trades=n0, net=net0, published=list(pub), reproduced=True)


def evaluate(phase: str, lo: date, hi: date, twins: bool) -> dict:
    out = {"_meta": dict(phase=phase, lo=str(lo), hi=str(hi), twins=K.TWINS if twins else 0, seed=K.SEED),
           "parity": {}, "arms": {}}
    rng = np.random.default_rng(K.SEED)
    for arm in K.ARMS:
        us = units(arm, lo, hi)
        base = walk(arm, us)
        out["parity"][arm] = parity(arm, phase, base)
        if phase == "design" and arm in ("R72", "R51"):
            engine_parity(us)
        res = {"today": {p: describe(base, p) for p in periods(phase)}, "n_candidates": sum(len(u[1]) for u in us)}
        base_res = {(key[0], key[1], int(c["er"]), c["side"]): r                 # by the signal's entry row
                    for key, cs, rr, _ in us for c, r in zip(cs, rr) if r is not None}
        tests = [t for t, v in list(K.TESTS.items()) + list(K.REPORTED.items()) if v[0] == arm]
        for test in tests:
            kind, rule = (K.TESTS.get(test) or K.REPORTED[test])[1:]
            tr, removed = rule_trades(test, arm, us)
            r = {p: describe(tr, p) for p in periods(phase)}
            r["removed_candidates"] = removed
            for p in periods(phase):
                r[p]["changed_vs_today"] = L.changed(by_period(tr, p), by_period(base, p))
            if kind == "filter":                    # what the dropped signals' trades did under today's rules
                drop = {(u[0][0], u[0][1], int(c["er"]), c["side"], c.get("book")) for u in us for c in u[1]
                        if not keep(rule, arm, u[0], c)}
                dt = [t for t in base if (t["und"], t["day"], int(t["er_sig"]), t["side"], t.get("book")) in drop]
                r["dropped_trades"] = {p: L.stats(by_period(dt, p)) for p in periods(phase)}
            if twins and test in K.TESTS:
                act = float(sum(t["net"] for t in tr))
                if kind == "filter":
                    tw = filter_twins(arm, rule, us, K.TWINS, rng)
                    r["twin"] = L.twin_p(act, tw)
                else:
                    tw, nch = exit_twins(arm, tr, base_res, K.TWINS, rng)
                    r["twin"] = L.twin_p(act, tw)
                    r["twin"]["sold_earlier"] = nch
                print("  twin", test, r["twin"], flush=True)
            res[test] = r
            print(arm, phase, test, {p: (r[p]["trades"], r[p]["net"]) for p in periods(phase)}, "today",
                  {p: (res["today"][p]["trades"], res["today"][p]["net"]) for p in periods(phase)}, flush=True)
        out["arms"][arm] = res
    return out


def run(phase: str):
    M = mods()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists():
            raise SystemExit("R115 final already read once: " + K.FINAL_FLAG.read_text())
        assert (K.RESULTS / "design.json").exists(), "the design read comes first"
        K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        M["C"].open_final()
        out = evaluate("final", K.FINAL_START, K.FINAL_END, twins=True)
    else:
        out = evaluate("design", K.START, K.DESIGN_END, twins=False)
    out["_meta"]["written"] = datetime.now(timezone.utc).isoformat()
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())["arms"]
    f = json.loads((K.RESULTS / "final.json").read_text())["arms"]
    tests = list(K.TESTS)
    ps = [f[K.TESTS[t][0]][t]["twin"]["p"] for t in tests]
    q_all = L.bh(ps, K.M_ALL)
    q_r64 = L.bh(ps, K.M_R64)
    q_own = L.bh(ps)
    out = {"_m": dict(r115=len(tests), prior_all=K.N_PRIOR_ALL, m_all=K.M_ALL, prior_r64=K.N_PRIOR_R64, m_r64=K.M_R64)}
    for t, qa, qr, qo in zip(tests, q_all, q_r64, q_own):
        arm = K.TESTS[t][0]
        rule = {"design": d[arm][t]["design"], "wf": d[arm][t]["wf"], "final": f[arm][t]["final"]}
        base = {"design": d[arm]["today"]["design"], "wf": d[arm]["today"]["wf"], "final": f[arm]["today"]["final"]}
        g = L.gates(rule, base, float(qa), int(f[arm][t]["final"]["changed_vs_today"]))
        g.update(p=f[arm][t]["twin"]["p"], q_all=float(qa), q_r64=round(float(qr), 4), q_own=round(float(qo), 4))
        out[t] = g
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final"):
        run(cmd)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
