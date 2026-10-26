"""R114 study (PREREG.md): R72 and R51 + Supertrend mornings only (entries by 09:55), closed at 10:00, in the app's
four-arm capped paper book (R108's model). First committed as "R113" (749734f); renumbered before any number.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                          # R101's clean stores (to 13 Oct)
    python research/hunt/r106_small_targets/r106study.py post-build                 # stores to 19 Oct, own folder
    python research/hunt/r114_donchian_close10/r114study.py design                  # design + walk-forward, parity asserted
    python research/hunt/r114_donchian_close10/r114study.py final                   # the ONE final read (refuses a second)
    R114_POST=1 python research/hunt/r114_donchian_close10/r114study.py post        # 14-19 Oct, read once after the final
    python research/hunt/r114_donchian_close10/r114study.py decide                  # BH + gates -> results/decision.json
    python research/hunt/r114_donchian_close10/r114study.py forward                 # sessions after 19 Oct (PREREG 6)

Arms: Liquidity 15+5, R72, R52 as R101 priced them; R51 with R73's Supertrend (10, 3) filter (R112's units_of); the
book is r108book.walk_period with the app's cap of 2 a way (CAP2) or no cap (NOCAP).
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timezone

import numpy as np

import r114cfg as K
import r114lib as L
import r108book as B

_M: dict = {}


def mods():
    if not _M:
        import r112study as S12               # R112's units_of / price / plain_exit (and through it R106's engine)
        S12.mods()
        import r108study as S108              # R108's arm_window (R101 parity asserted), candidates, per_arm
        _M.update(S12=S12, S108=S108, S=S12.mods()["S"], X=S12.mods()["X"], C=S12.mods()["C"],
                  r51data=S12.mods()["r51data"])
    return _M


# ================================================================================================ the arms
def arm_units(lo: date, hi: date, phase: str) -> dict:
    """{arm: (units, alone trades)}; 'R51' = R51 + Supertrend (the paper arm), 'R51plain' = plain R51 (parity only).
    R101's (and R73's) published figures are asserted inside arm_window / R112's parity for design and final."""
    M = mods()
    S108, S12 = M["S108"], M["S12"]
    out = {}
    for arm in ("LIQ", "R72", "R52"):
        out[arm] = S108.arm_window(arm, lo, hi, phase)
    out["R51plain"] = S108.arm_window("R51", lo, hi, phase)
    u = S12.units_of("R51", lo, hi)
    if phase in ("design", "final"):
        S12.parity("R51", phase, u)
    out["R51"] = (u, S12.walk(u, [x[2] for x in u]))
    return out


def rule_units(units: list, rule: str) -> list:
    """One Donchian arm's units under a rule: the morning cut and the forced close; re-priced by R112's engine only
    when a close is forced (today's rules re-use R101's own pricing)."""
    M = mods()
    cut, close = K.RULES[rule]
    out = []
    for key, cands, res, how in units:
        keep, cs = L.morning(cands, cut, close)
        if not keep:
            continue
        if close is None:
            pr = [res[j] for j in keep]
        else:
            D = M["r51data"].load(key[0])[key[1]]
            pr = M["S12"].price(key[0], D, cs, False)
        out.append((key, cs, pr, how))
    return out


def book_walks(units: dict, sessions: list, rule: str, arms=K.ARMS) -> dict:
    """{cap: walk} of the book with the Donchian arms under `rule` (Liquidity and R52 as today)."""
    M = mods()
    ub = {a: (rule_units(units[a], rule) if a in K.DONCHIAN else units[a]) for a in arms}
    for a in K.ARMS:
        ub.setdefault(a, [])
    dc = M["S108"].candidates(ub)
    return {cap: B.walk_period(sessions, dc, B.make_rule(cap)) for cap in K.CAPS}, ub


def real(walk: dict, sess: list) -> list:
    tr = B.trades_of({d: walk[d] for d in sess if d in walk})
    for t in tr:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    return tr


# ================================================================================================ twins
def exit_twin(x10: list, n: int, rng) -> tuple:
    """PREREG gate 3 exit twin on the X10 book: (twin totals, Donchian trades the 10:00 close sold earlier)."""
    M = mods()
    S12 = M["S12"]
    ex = S12.exits()
    fixed, rows = 0.0, []
    for t in x10:
        if t["arm"] not in K.DONCHIAN:
            fixed += t["net"]
            continue
        p = S12.plain_exit(t)
        if p is None or t["xr"] >= p["xr"] or p["xr"] - t["er"] < 2:
            fixed += t["net"]
            continue
        D = M["r51data"].load(t["und"])[t["day"]]
        r = np.arange(t["er"] + 1, p["xr"], dtype=np.int64)
        rows.append(M["X"].forced_exit_nets(t["und"], D, t, ex, r, -1))
    return L.exit_twin_totals(fixed, rows, n, rng), len(rows)


# ================================================================================================ evaluation
def periods_of(phase: str) -> tuple:
    return {"design": ("design", "wf"), "final": ("final",), "post": ("post",)}[phase]


def parity(units: dict, sessions: list, phase: str) -> dict:
    """PREREG section 1: with plain R51 the four-arm CAP2 / NOCAP books reproduce R108's published figures."""
    u = dict(units)
    u["R51"] = units["R51plain"]
    W, _ = book_walks(u, sessions, "BOOK0")
    out = {}
    for per in periods_of(phase):
        sess = [d for d in sessions if K.in_period(d, per)]
        for cap in K.CAPS:
            pub = K.R108_PUBLISHED.get((cap, per))
            if pub is None:
                continue
            tr = real(W[cap], sess)
            n0, net0 = len(tr), round(sum(t["net"] for t in tr))
            ok = (pub[0] is None or pub[0] == n0) and abs(pub[1] - net0) <= 1
            out[f"{cap}:{per}"] = dict(trades=n0, net=net0, published=list(pub), reproduced=ok)
            print("R108 parity", cap, per, n0, net0, pub, flush=True)
            assert ok, (cap, per, "four-arm book does not reproduce R108", n0, net0, pub)
    return out


def view(walk: dict, sessions: list, per: str, base_walk: dict | None) -> dict:
    sess = [d for d in sessions if K.in_period(d, per)]
    tr = real(walk, sess)
    s = L.summary(tr, sess)
    s["arms"] = mods()["S108"].per_arm(walk, set(sess))
    if base_walk is not None:
        b = real(base_walk, sess)
        s["liq_r52_gained"] = L.extra(tr, b)
        s["liq_r52_lost"] = L.extra(b, tr)
        s["changed_vs_book0"] = B.changed_vs(tr, b)
    return s


def evaluate(phase: str, lo: date, hi: date) -> dict:
    M = mods()
    sessions = M["S"].sessions_in(lo, hi)
    units = {a: v[0] for a, v in arm_units(lo, hi, phase).items()}
    out = {"_meta": dict(phase=phase, sessions=len(sessions), first=str(sessions[0]), last=str(sessions[-1]),
                         twins=K.TWINS, seed=K.SEED, m=K.M_TOTAL), "parity": {}, "books": {}, "alone": {}}
    if phase in ("design", "final"):
        out["parity"] = parity(units, sessions, phase)
        if phase == "design":
            for a in K.DONCHIAN:
                M["S12"].engine_parity(units[a])
    walks, trades = {}, {}
    for rule in K.RULES:
        W, ub = book_walks(units, sessions, rule)
        walks[rule] = W
        for cap in K.CAPS:
            trades[f"{cap}|{rule}"] = B.trades_of(W[cap], outs=(B.REAL, B.WB_RULE, B.WB_HELD, B.DROP))
        # each Donchian arm alone under the rule (its own research record, no book rules) and the two-arm CAP2 book
        # (R112's view, a check that the rule reproduces R112's published CUT10X)
        al = {a: M["S12"].walk(ub[a], [x[2] for x in ub[a]]) for a in K.DONCHIAN}
        W2, _ = book_walks(units, sessions, rule, arms=K.DONCHIAN)
        for per in periods_of(phase):
            sess = [d for d in sessions if K.in_period(d, per)]
            ss = set(sess)
            for cap in K.CAPS:
                base = walks["BOOK0"][cap] if rule != "BOOK0" else None
                out["books"].setdefault(cap, {}).setdefault(rule, {})[per] = view(W[cap], sessions, per, base)
            for a in K.DONCHIAN:
                out["alone"].setdefault(a, {}).setdefault(rule, {})[per] = L.summary(
                    [t for t in al[a] if t["day"] in ss], sess)
            out["alone"].setdefault("R72+R51 CAP2 (R112's view)", {}).setdefault(rule, {})[per] = L.summary(
                real(W2["CAP2"], sess), sess)
            b = out["books"]["CAP2"][rule][per]
            print(phase, per, rule, "CAP2", b["trades"], b["net"], "| NOCAP", out["books"]["NOCAP"][rule][per]["net"],
                  flush=True)
    for a in ("LIQ", "R52"):                     # today's rules in every book
        tr = M["S"].run_rule(a, units[a], [u[2] for u in units[a]], None)
        for per in periods_of(phase):
            sess = [d for d in sessions if K.in_period(d, per)]
            ss = set(sess)
            out["alone"].setdefault(a, {}).setdefault("BOOK0", {})[per] = L.summary(
                [t for t in tr if t["day"] in ss], sess)
    if phase == "final":
        out["twins"] = twins(walks, sessions)
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"trades_{phase}.pkl").write_bytes(pickle.dumps(trades, protocol=pickle.HIGHEST_PROTOCOL))
    return M["S108"]._clean(out)


def twins(walks: dict, sessions: list) -> dict:
    """PREREG gate 3 on the final period, CAP2 books: refusal twin, then exit twin (seed 113); p = the larger."""
    rng = np.random.default_rng(K.SEED)
    sess = [d for d in sessions if K.in_period(d, "final")]
    b0 = real(walks["BOOK0"]["CAP2"], sess)
    x = real(walks[K.TEST]["CAP2"], sess)
    act = sum(t["net"] for t in x)
    rt, removed = L.refusal_twin_totals(b0, x, sess, K.TWINS, rng)
    et, changed = exit_twin(x, K.TWINS, rng)
    r = dict(net=round(act), refusal_p=round(L.twin_p(act, rt), 6), refusal_mean=round(float(rt.mean())),
             refusal_removed=removed, exit_p=round(L.twin_p(act, et), 6), exit_mean=round(float(et.mean())),
             exit_changed=changed, changed_vs_book0=B.changed_vs(x, b0))
    r["p"] = max(r["refusal_p"], r["exit_p"])
    print("twins", r, flush=True)
    return r


def run(phase: str):
    M = mods()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists():
            raise SystemExit("R114 final already read once: " + K.FINAL_FLAG.read_text())
        assert (K.RESULTS / "design.json").exists(), "the design read comes first"
        K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        M["C"].open_final()
        lo, hi = K.FINAL_START, K.FINAL_END
    elif phase == "post":
        assert K.POST, "the post window reads the stores built to 19 Oct (R114_POST=1)"
        if not (K.RESULTS / "final.json").exists():
            raise SystemExit("the post window is read only after the final")
        if K.POST_FLAG.exists():
            raise SystemExit("R114 post window already read once: " + K.POST_FLAG.read_text())
        K.POST_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        M["C"].open_final()
        lo, hi = K.POST_START, K.POST_END
    else:
        lo, hi = K.START, K.DESIGN_END
    out = evaluate(phase, lo, hi)
    out["_meta"]["written"] = datetime.now(timezone.utc).isoformat()
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    bk = {"design": d["books"]["CAP2"], "wf": d["books"]["CAP2"], "final": f["books"]["CAP2"]}
    per = {p: (bk[p][K.TEST][p], bk[p]["BOOK0"][p]) for p in ("design", "wf", "final")}
    g = L.decide(per, f["twins"]["p"], f["twins"]["changed_vs_book0"])
    g["twins"] = f["twins"]
    g["_m"] = dict(r114=1, prior=K.N_PRIOR, total=K.M_TOTAL, prior_detail=K.PRIOR_TESTS)
    (K.RESULTS / "decision.json").write_text(json.dumps(g, indent=1))
    return g


def forward():
    """PREREG section 6: BOOK0 and X10 on every session after 19 Oct, once, when the lake holds them."""
    sys.path.insert(0, str(K.HERE.parent / "r51"))
    import r51cfg
    import pandas as pd
    p = r51cfg.LAKE / f"index_1m_NIFTY_{date.today().year}.parquet"
    last = pd.read_parquet(p, columns=["ts"]).ts.max().date() if p.exists() else None
    if last is None or last <= K.POST_END:
        raise SystemExit(f"the lake ends {last}: no session after {K.POST_END} to read yet (PREREG section 6)")
    raise SystemExit("sessions after 19 Oct are in the lake: build their stores in their own folder, then add the read")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final", "post"):
        run(cmd)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    elif cmd == "forward":
        forward()
    else:
        raise SystemExit(__doc__)
