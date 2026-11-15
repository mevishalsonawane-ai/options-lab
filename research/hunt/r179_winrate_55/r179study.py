"""R179 (PREREG.md): win rate above 55% with profit kept - R151's picker (P) and R162's breakeven lock (BE) on the Rs 1,500
rule (SC) and on today's rules (A), R72 and R51 (Liquidity descriptive), the last year (20 Oct 2025 - 19 Oct 2026).

    R176_POST=1 python research/hunt/r179_winrate_55/r179study.py run      # read once
    python research/hunt/r179_winrate_55/r179study.py decide                # gates -> results/decision.json

Pure parts: gates_r179() (test_r179.py).
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
for p in (str(HERE.parent / "r177_vp_trap"), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r177study as R7  # noqa: E402

S6, K6, L = R7.S6, R7.K6, R7.L
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
ARMS = ("LIQ", "R72", "R51")
TEST_ARMS = ("R72", "R51")
SC = dict(per_lot=1500.0, gap_per_lot=1500.0, step_per_lot=1000.0, inc_per_lot=500.0, trig_per_lot=1000.0)
BE_X = 0.10
# variant -> (stop rule 'A' / 'SC', breakeven lock, picker)
VARIANTS = {"A": ("A", False, False), "P": ("A", False, True), "BE": ("A", True, False), "V4": ("A", True, True),
            "SC": ("SC", False, False), "V1": ("SC", False, True), "V2": ("SC", True, False), "V3": ("SC", True, True)}
BASE = {"V1": "SC", "V2": "SC", "V3": "SC", "V4": "A"}
TWIN_REF = {"V1": "SC", "V2": "SC", "V3": "V2", "V4": "BE"}           # the variant the twin compares against
TESTS = tuple((a, v) for v in ("V1", "V2", "V3", "V4") for a in TEST_ARMS)   # 8
M_TOTAL = 4030 + len(TESTS)                                          # 4,038
WIN_GATE = 0.55
SEED = 179
R162_BE_FINAL = {"R72": (1203, 164609), "R51": (1673, 127044)}


# ================================================================================================ pure
def gates_r179(per: dict, base: dict, q: float) -> dict:
    """per / base: {period: stats} for early, final, six, year (stats: trades, win, net, max_dd, worst_month {net})."""
    fails = []
    if not all((per[p]["win"] or 0) >= WIN_GATE for p in ("year", "final")):
        fails.append("win rate below 55% (year or final)")
    if not all(per[p]["net"] >= base[p]["net"] for p in ("year", "final")):
        fails.append("net below its base (year or final)")
    if not all(per[p]["net"] > 0 for p in ("early", "final", "six", "year")):
        fails.append("not positive in every period")
    if not q <= 0.10:
        fails.append(f"twin BH q {q:.2f} > 0.10")
    if per["final"]["trades"] < 40:
        fails.append("< 40 final trades")
    risk = [p for p in ("early", "final", "six", "year")
            if per[p]["max_dd"] > K6.MAX_DD or per[p]["worst_month"]["net"] < K6.WORST_MONTH]
    if risk:
        fails.append("outside the account stop rule in " + ", ".join(risk))
    return dict(passed=not fails, fails=fails, q=round(float(q), 4),
                passed_except_account_stop=not [f for f in fails if "account stop" not in f])


# ================================================================================================ data
def priced(arm: str, us: list, rule: str, be: bool, lots: int) -> list:
    M = R7.mods()
    ex = S6.exits(arm)
    kw = dict(SC) if rule == "SC" else {}
    out = []
    for key, cands, _, _ in us:
        D = M["r51data"].load(key[0])[key[1]]
        out.append(M["Z"].price(key[0], D, cands, ex, rule == "SC", lots, be_x=BE_X if be else -1.0, **kw))
    return out


def run() -> dict:
    assert K6.POST, "R179 reads the stores built to 19 Oct (R176_POST=1)"
    RESULTS.mkdir(parents=True, exist_ok=True)
    if FINAL_FLAG.exists():
        raise SystemExit("R179 already read once: " + FINAL_FLAG.read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    M = R7.mods()
    M["C"].open_final()
    lo, hi = K6.Y_START, K6.Y_END
    out = {"parity": {}, "twins": {}, "be_check": {}}
    trades = {}
    rng = np.random.default_rng(SEED)
    for arm in ARMS:
        us0 = S6.units(arm, lo, hi)
        usA, _ = R7.apply_rule(arm, us0, None)
        P = sc = thr = None
        if arm in K6.PICKER_ARMS:
            P = S6.picker()
            sc, thr = P.scores(arm, us0)
        for lots in K6.LOTS:
            walked = {}
            plain_sc = None
            for rule, be in (("A", False), ("A", True), ("SC", False), ("SC", True)):
                pr = priced(arm, usA, rule, be, lots)
                walked[(rule, be)] = [t for t in S6.walk(arm, usA, pr) if lo <= t["day"] <= hi]
                if rule == "SC" and not be and lots == 1:
                    plain_sc = {(t["und"], S6._d(t["day"]), c["er"], c["side"]): t
                                for (key, cands, _, _), row in zip(usA, pr) for c, t in zip(cands, row) if t is not None}
            for v, (rule, be, pk) in VARIANTS.items():
                if pk and P is None:
                    continue                                   # Liquidity has no picker
                tr = walked[(rule, be)]
                trades.setdefault((v, lots), {})[arm] = tr if not pk else [t for t in tr if P.kept(t, sc, thr)]
            if lots == 1:
                a = trades[("A", 1)][arm]
                af = S6.in_span(a, "final")
                pub = K6.PARITY[(arm, "final")]
                r = dict(trades=len(af), net=round(sum(t["net"] for t in af)), published=list(pub))
                r["reproduced"] = r["trades"] == pub[0] and abs(r["net"] - pub[1]) <= 1
                out["parity"][arm] = r
                assert r["reproduced"], ("parity failed", arm, r)
                if arm in R162_BE_FINAL:
                    bf = S6.in_span(trades[("BE", 1)][arm], "final")
                    out["be_check"][arm] = dict(trades=len(bf), net=round(sum(t["net"] for t in bf)),
                                                r162_13oct=list(R162_BE_FINAL[arm]))
                    print(arm, "BE check", out["be_check"][arm], flush=True)
                if arm in TEST_ARMS:
                    for v in ("V1", "V2", "V3", "V4"):
                        tr, ref = trades[(v, 1)][arm], trades[(TWIN_REF[v], 1)][arm]
                        act = sum(t["net"] for t in tr)
                        if v == "V2":
                            tw, chg = R7.exit_twins(arm, tr, plain_sc, K6.TWINS, rng)
                        else:
                            chg = max(0, len(ref) - len(tr))
                            tw = L.selection_twin([t["net"] for t in ref], chg, K6.TWINS, rng)
                        out["twins"][f"{arm}:{v}"] = dict(p=round(M["S"].twin_p(act, tw), 4), mean=round(float(tw.mean())),
                                                          changed=chg, vs=TWIN_REF[v])
                        print(arm, v, "twin", out["twins"][f"{arm}:{v}"], flush=True)
            print(arm, lots, {v: (len(trades[(v, lots)][arm]), round(sum(t["net"] for t in trades[(v, lots)][arm])),
                                  round(100 * sum(t["net"] > 0 for t in trades[(v, lots)][arm]) /
                                        max(1, len(trades[(v, lots)][arm])), 1))
                              for v in VARIANTS if arm in trades.get((v, lots), {})}, flush=True)
    for per in K6.YEAR_PERIODS:
        plo, phi = K6.span_of(per)
        n_sess = len(S6.sessions(plo, phi))
        months = K6.months_between(plo, phi)
        res = {"sessions": n_sess}
        for (v, lots), by_arm in trades.items():
            res[f"{v}@{lots}"] = {a_: S6.describe([t for t in tr if plo <= t["day"] <= phi], n_sess, months)
                                  for a_, tr in by_arm.items()}
        out[per] = res
    (RESULTS / "year.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def decide() -> dict:
    r = json.loads((RESULTS / "year.json").read_text())
    ps = [r["twins"][f"{a}:{v}"]["p"] for a, v in TESTS]
    q_all, q_own = S6.bh_q(ps, M_TOTAL), S6.bh_q(ps, len(ps))
    out = {}
    for (a, v), qa, qo in zip(TESTS, q_all, q_own):
        per = {p: r[p][f"{v}@1"][a] for p in K6.YEAR_PERIODS}
        base = {p: r[p][f"{BASE[v]}@1"][a] for p in K6.YEAR_PERIODS}
        g = gates_r179(per, base, qa)
        g.update(q_own=round(qo, 4), twin_p=r["twins"][f"{a}:{v}"]["p"], win_year=per["year"]["win"],
                 win_final=per["final"]["win"], net_year=per["year"]["net"], base_net_year=base["year"]["net"])
        out[f"{a}:{v}"] = g
    out["_m"] = dict(tests=len(TESTS), prior=4030, total=M_TOTAL)
    (RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "run":
        run()
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
