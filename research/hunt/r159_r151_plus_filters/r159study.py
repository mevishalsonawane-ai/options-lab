"""R159 study (PREREG.md): R155's filters (S/R lines, W50, Supertrend, EMA, OI, wicks) on top of R72 + R151's picker.

    python research/hunt/r159_r151_plus_filters/r159study.py design     # 20 Nov 2024 - Mar 2026 (P2 reported)
    python research/hunt/r159_r151_plus_filters/r159study.py final      # P3 once, base parity asserted, twins
    python research/hunt/r159_r151_plus_filters/r159study.py decide

The base is R151's own walk with the app's exported picker numbers (R152's `r152picker.scores` / R151's `kept_walk`); a
filter turns a failing candidate's score below the threshold, so it is removed before the walk exactly as R151's skips.
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
for _d in ("r155_scalp_filters", "r156_scalp_network"):
    p = str(HERE.parent / _d)
    if p not in sys.path:
        sys.path.insert(0, p)
import r155cfg as K5  # noqa: E402

K4 = K5.K4
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
ROOT = HERE.parents[2]
SINGLE = tuple(f for f in K5.FILTERS if f != "F20")                      # F1 .. F19
COMBOS = {"C1": ("F3", "F9"), "C2": ("F6", "F9"), "C3": ("F6", "F9", "F8", "F11")}
TESTS = SINGLE + tuple(COMBOS)                                           # 22
NAME = {**{f: K5.FNAME[f] for f in SINGLE}, "C1": "W50 AND Supertrend", "C2": "ALLSR (all lines) AND Supertrend",
        "C3": "ALLSR AND Supertrend AND EMA20 AND OI build-up"}
PUBLISHED = dict(base=(652, 219057), r72=(1202, 171366))                 # Apr - 13 Oct 2026, 1 lot
P2 = (K4.P2_START, K4.P2_END)
P3 = (K4.FINAL_START, K4.FINAL_END)
TWINS, SEED, GATE_Q, MIN_FINAL, N_PRIOR = 2000, 159, 0.10, 40, 2132


# ================================================================================================ pure
def passes(test: str, f: dict | None) -> bool:
    """The extra filter's verdict for one candidate (f = R155's {filter: bool}; None = unreadable = fails)."""
    if f is None:
        return False
    return all(f[x] for x in COMBOS[test]) if test in COMBOS else bool(f[test])


def combined_scores(sc: dict, keys, ok, thr: float) -> dict:
    """The picker's scores with every candidate that fails the extra filter pushed below the threshold (so R151's walk
    removes it). A candidate the picker could not score stays unscored (R151's walk skips it, as published)."""
    out = {}
    for k in keys:
        if not ok(k):
            out[k] = thr - 1.0
        elif k in sc:
            out[k] = sc[k]
    return out


def better(rule: dict, base: dict) -> bool:
    return bool(rule["trades"] and rule["win"] > base["win"] and rule["net"] > base["net"])


def gates(p2: dict, p3: dict, b2: dict, b3: dict, q: float) -> dict:
    fails = []
    if not (better(p2, b2) and better(p3, b3)):
        fails.append("not above R72 + R151 on win rate and net in P2 and P3")
    if not (p2["net"] > 0 and p3["net"] > 0):
        fails.append("not positive in P2 and P3")
    if not q <= GATE_Q:
        fails.append(f"random-skip BH q {q:.2f} > {GATE_Q}")
    if p3["trades"] < MIN_FINAL:
        fails.append(f"< {MIN_FINAL} final trades")
    return dict(passed=not fails, fails=fails)


def tstats(trades: list, lo: date, hi: date) -> dict:
    import r156study as S6
    t = [x for x in trades if lo <= x["day"] <= hi]
    nets = np.array([x["net"] for x in t], float)
    st = S6.stats(nets, np.array([x["day"].toordinal() for x in t]))
    if len(nets):
        st["ex_top5"] = round(float(nets.sum() - np.sort(nets)[::-1][:5].sum()))
    return st


# ================================================================================================ data
def setup(lo: date, hi: date) -> dict:
    import r155study as S5
    import r152picker as PK                      # R151's study (T) and R152's units / walk (R)
    M = S5.mods()
    us = PK.R.units("R72", lo, hi)
    sc, thr = PK.scores("R72", us)
    T = PK.T
    feats = {u: S5.feat_for(u, hi) for u in K5.UNDS}
    fx = {}
    for key, cands, _, _ in us:
        for c in cands:
            fx[T.cand_key(key[0], key[1], c["er"], c["side"])] = feats[key[0]].decide(key[1], c["er"] - 1, c["side"],
                                                                                    float(c["strike"]))[1]
    return dict(M=M, PK=PK, T=T, us=us, sc=sc, thr=thr, fx=fx, S5=S5)


def run_all(st: dict) -> dict:
    T, us, sc, thr, fx = st["T"], st["us"], st["sc"], st["thr"], st["fx"]
    keys = list(fx)
    out = {"base": T.kept_walk("R72", us, sc, thr), "r72": st["PK"].R.walk(us, [u[2] for u in us])}
    ones = {k: 1.0 for k in keys}
    for t in TESTS:
        ok = lambda k, t=t: passes(t, fx.get(k))  # noqa: E731
        out[t] = T.kept_walk("R72", us, combined_scores(sc, keys, ok, thr), thr)
        out[t + "/plain"] = T.kept_walk("R72", us, combined_scores(ones, keys, ok, 0.5), 0.5)
    return out


def cmd_design():
    st = setup(K4.ARM_START, K4.P2_END)
    tr = run_all(st)
    out = {k: dict(P2=tstats(v, *P2)) for k, v in tr.items()}
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "design.json").write_text(json.dumps(out, default=str))
    print("design done; base P2", out["base"]["P2"], flush=True)


def cmd_final():
    if FINAL_FLAG.exists():
        raise SystemExit("R159 final already read once: " + FINAL_FLAG.read_text())
    RESULTS.mkdir(parents=True, exist_ok=True)
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    import r155study as S5
    S5.mods()["C"].open_final()                  # the repo's guard: the final is opened before anything reads it
    st = setup(*P3)
    tr = run_all(st)
    for k in ("base", "r72"):
        n0, net0 = len(tr[k]), round(sum(t["net"] for t in tr[k]))
        assert n0 == PUBLISHED[k][0] and abs(net0 - PUBLISHED[k][1]) <= 1, (k, n0, net0)
    print("parity: base and plain R72 reproduce the published figures", flush=True)
    rng = np.random.default_rng(SEED)
    base = np.array([t["net"] for t in tr["base"]], float)
    out = {k: dict(P3=tstats(v, *P3)) for k, v in tr.items()}
    for t in TESTS:
        p_, mean = st["S5"].subset_twin(base, len(tr[t]), float(sum(x["net"] for x in tr[t])), TWINS, rng)
        out[t]["twin"] = dict(p=round(p_, 4), mean=round(mean))
        out[t]["by_und"] = {u: tstats([x for x in tr[t] if x["und"] == u], *P3) for u in K5.UNDS}
    out["base"]["by_und"] = {u: tstats([x for x in tr["base"] if x["und"] == u], *P3) for u in K5.UNDS}
    (RESULTS / "final.json").write_text(json.dumps(out, default=str))
    print("final read", flush=True)


def cmd_decide() -> dict:
    import r156study as S6
    d = json.loads((RESULTS / "design.json").read_text())
    f = json.loads((RESULTS / "final.json").read_text())
    ps = [f[t]["twin"]["p"] for t in TESTS]
    q = S6.bh(ps, len(ps) + N_PRIOR)
    out = {}
    for t, qa, p in zip(TESTS, q, ps):
        g = gates(d[t]["P2"], f[t]["P3"], d["base"]["P2"], f["base"]["P3"], float(qa))
        g.update(twin_p=p, q=round(float(qa), 4), twin_mean=f[t]["twin"]["mean"], P2=d[t]["P2"], P3=f[t]["P3"],
                 plain_P2=d[t + "/plain"]["P2"], plain_P3=f[t + "/plain"]["P3"])
        out[t] = g
    out["_base"] = dict(P2=d["base"]["P2"], P3=f["base"]["P3"])
    out["_r72"] = dict(P2=d["r72"]["P2"], P3=f["r72"]["P3"])
    out["_m"] = dict(r159=len(ps), prior=N_PRIOR, total=len(ps) + N_PRIOR, passed=sum(1 for t in TESTS if out[t]["passed"]))
    (RESULTS / "decision.json").write_text(json.dumps(out, indent=0, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final"):
        {"design": cmd_design, "final": cmd_final}[cmd]()
    elif cmd == "decide":
        print(json.dumps(cmd_decide()["_m"], indent=1))
    else:
        raise SystemExit(__doc__)
