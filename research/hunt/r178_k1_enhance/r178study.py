"""R178 (PREREG.md): K1 skip variants, selected on 20 Nov 2024 - 19 Oct 2025, the chosen one per arm tested on the last
year (20 Oct 2025 - 19 Oct 2026).

    R176_POST=1 python research/hunt/r178_k1_enhance/r178study.py run      # selection, then the one test read
    python research/hunt/r178_k1_enhance/r178study.py decide                # gates -> results/decision.json

Reuses R177 (levels, failed_break, the A walk) and R176 (engine, stats, gates). Skips do not change prices, so each
variant is today's priced candidates with a skip mask. Pure parts: skip(), choose(), mask_units() (test_r178.py).
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timezone
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
SEL_START, SEL_END = date(2024, 11, 20), date(2025, 10, 19)
H1_END = date(2025, 4, 30)
VARIANTS = ("K1", "K2", "K3", "K4", "K5", "K6")
SPEC = {"K1": (("va", 30),), "K2": (("va", 15),), "K3": (("va", 60),), "K4": (("va", None),), "K5": (("day", 30),),
        "K6": (("va", 30), ("day", 30))}
LABEL = {"K1": "failed value-area break, last 30 min", "K2": "failed value-area break, last 15 min",
         "K3": "failed value-area break, last 60 min", "K4": "failed value-area break, any time earlier today",
         "K5": "failed break of yesterday's high / low, last 30 min", "K6": "K1 or K5"}
N_SEL = len(VARIANTS) * len(K6.ARMS)                                 # 18 selection comparisons
SEED = 178


# ================================================================================================ pure
def skip(close: np.ndarray, s: int, side: str, lv: dict | None, spec: tuple) -> bool:
    """True when any (kind, window) in spec finds a failed break before the signal row s. kind 'va' = VAH / VAL,
    'day' = PDH / PDL; window None = every row earlier today."""
    if lv is None:
        return False
    call = side == "C"
    for kind, w in spec:
        level = (lv["vah"] if call else lv["val"]) if kind == "va" else (lv["pdh"] if call else lv["pdl"])
        if L.failed_break(close, s, side, level, s if w is None else w):
            return True
    return False


def choose(rows: dict) -> str | None:
    """rows: {variant: (gain H1, gain H2, gain whole window)} vs A. The eligible variant (gain > 0 in both halves) with
    the largest whole-window gain; ties: the earlier in VARIANTS; None if none is eligible."""
    best, best_gain = None, None
    for v in VARIANTS:
        if v not in rows:
            continue
        g1, g2, gt = rows[v]
        if g1 > 0 and g2 > 0 and (best_gain is None or gt > best_gain):
            best, best_gain = v, gt
    return best


def mask_units(us: list, pr: list, keep: list) -> tuple:
    """(units, priced) with the candidates whose keep flag is False removed (keep: one list of bools per unit)."""
    nu, np_ = [], []
    for (key, cands, res, how), row, ks in zip(us, pr, keep):
        nu.append((key, [c for c, k in zip(cands, ks) if k], [r for r, k in zip(res, ks) if k], how))
        np_.append([t for t, k in zip(row, ks) if k])
    return nu, np_


# ================================================================================================ data
def flags(us: list) -> dict:
    """{variant: keep flags per unit} for every candidate."""
    M = R7.mods()
    out = {v: [] for v in VARIANTS}
    for key, cands, _, _ in us:
        und, d = key[0], key[1]
        close = np.asarray(M["r51data"].load(und)[d].idx[3], float)
        lv = R7.prev_levels(und, d)
        for v in VARIANTS:
            out[v].append([not skip(close, int(c["er"]) - 1, c["side"], lv, SPEC[v]) for c in cands])
    return out


def _net(tr, lo, hi):
    return sum(t["net"] for t in tr if lo <= t["day"] <= hi)


def select() -> dict:
    out = {}
    for arm in K6.ARMS:
        us0 = S6.units(arm, SEL_START, SEL_END)
        usA, _ = R7.apply_rule(arm, us0, None)
        pr = S6.priced(arm, usA, False, 1)
        a = [t for t in S6.walk(arm, usA, pr) if SEL_START <= t["day"] <= SEL_END]
        fl = flags(usA)
        aH1, aH2, aT = _net(a, SEL_START, H1_END), _net(a, date(2025, 5, 1), SEL_END), _net(a, SEL_START, SEL_END)
        rows, info = {}, {"A": dict(trades=len(a), net=round(aT), H1=round(aH1), H2=round(aH2))}
        for v in VARIANTS:
            u2, p2 = mask_units(usA, pr, fl[v])
            tr = [t for t in S6.walk(arm, u2, p2) if SEL_START <= t["day"] <= SEL_END]
            h1, h2, tot = _net(tr, SEL_START, H1_END), _net(tr, date(2025, 5, 1), SEL_END), _net(tr, SEL_START, SEL_END)
            rows[v] = (h1 - aH1, h2 - aH2, tot - aT)
            info[v] = dict(trades=len(tr), net=round(tot), H1=round(h1), H2=round(h2), gain=round(tot - aT),
                           win=round(sum(t["net"] > 0 for t in tr) / len(tr), 4) if tr else None,
                           skipped=sum(1 for ks in fl[v] for k in ks if not k))
        info["chosen"] = choose(rows)
        out[arm] = info
        print(arm, "selection", {k: (v["net"], v.get("gain")) if isinstance(v, dict) else v for k, v in info.items()},
              flush=True)
    return out


def test(chosen: dict) -> dict:
    M = R7.mods()
    M["C"].open_final()
    lo, hi = K6.Y_START, K6.Y_END
    out = {"parity": {}, "twins": {}, "chosen": chosen}
    trades = {}
    rng = np.random.default_rng(SEED)
    for arm in K6.ARMS:
        us0 = S6.units(arm, lo, hi)
        usA, _ = R7.apply_rule(arm, us0, None)
        fl = flags(usA)
        P = sc = thr = None
        if arm in K6.PICKER_ARMS:
            P = S6.picker()
            sc, thr = P.scores(arm, us0)
        v = chosen.get(arm)
        for lots in K6.LOTS:
            pr = S6.priced(arm, usA, False, lots)
            a = [t for t in S6.walk(arm, usA, pr) if lo <= t["day"] <= hi]
            if lots == 1:
                af = S6.in_span(a, "final")
                pub = K6.PARITY[(arm, "final")]
                r = dict(trades=len(af), net=round(sum(t["net"] for t in af)), published=list(pub))
                r["reproduced"] = r["trades"] == pub[0] and abs(r["net"] - pub[1]) <= 1
                out["parity"][arm] = r
                assert r["reproduced"], ("parity failed", arm, r)
            trades.setdefault(("A", lots), {})[arm] = a
            trades.setdefault(("B", lots), {})[arm] = a if P is None else [t for t in a if P.kept(t, sc, thr)]
            for vv in VARIANTS:                          # every variant reported (descriptive); only the chosen is tested
                u2, p2 = mask_units(usA, pr, fl[vv])
                tr = [t for t in S6.walk(arm, u2, p2) if lo <= t["day"] <= hi]
                trades.setdefault((vv, lots), {})[arm] = tr
                trades.setdefault(("B+" + vv, lots), {})[arm] = tr if P is None else [t for t in tr if P.kept(t, sc, thr)]
                if lots == 1 and vv == v:
                    k = max(0, len(a) - len(tr))
                    tw = L.selection_twin([t["net"] for t in a], k, K6.TWINS, rng)
                    out["twins"][arm] = dict(variant=v, p=round(M["S"].twin_p(sum(t["net"] for t in tr), tw), 4),
                                             mean=round(float(tw.mean())), dropped=k)
                    print(arm, v, "twin", out["twins"][arm], flush=True)
            print(arm, lots, {x: round(sum(t["net"] for t in trades[(x, lots)][arm])) for x in ("A",) + VARIANTS}, flush=True)
    for per in K6.YEAR_PERIODS:
        plo, phi = K6.span_of(per)
        n_sess = len(S6.sessions(plo, phi))
        months = K6.months_between(plo, phi)
        res = {"sessions": n_sess}
        for (vv, lots), by_arm in trades.items():
            sub = {a_: [t for t in by_arm[a_] if plo <= t["day"] <= phi] for a_ in K6.ARMS}
            res[f"{vv}@{lots}"] = {a_: S6.describe(sub[a_], n_sess, months) for a_ in K6.ARMS}
            res[f"{vv}@{lots}"]["ALL"] = S6.describe(S6.together(sub), n_sess, months)
        out[per] = res
    return out


def run():
    assert K6.POST, "R178 reads the stores built to 19 Oct (R176_POST=1)"
    RESULTS.mkdir(parents=True, exist_ok=True)
    if FINAL_FLAG.exists():
        raise SystemExit("R178 already read once: " + FINAL_FLAG.read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    R7.mods()["C"].open_final()                          # the selection window overlaps nothing final, but the walk asks
    sel = select()
    (RESULTS / "selection.json").write_text(json.dumps(sel, indent=1))
    chosen = {a: sel[a]["chosen"] for a in K6.ARMS if sel[a]["chosen"]}
    t = test(chosen)
    (RESULTS / "test.json").write_text(json.dumps(t, indent=1, default=str))


def m_total(n_tests: int) -> int:
    return R7.M_TOTAL_C + N_SEL + n_tests


def decide() -> dict:
    t = json.loads((RESULTS / "test.json").read_text())
    arms = [a for a in K6.ARMS if a in t["chosen"]]
    ps = [t["twins"][a]["p"] for a in arms]
    m = m_total(len(arms))
    q_all, q_own = (S6.bh_q(ps, m), S6.bh_q(ps, len(ps))) if ps else ([], [])
    out = {}
    for a, qa, qo in zip(arms, q_all, q_own):
        v = t["chosen"][a]
        per = {p: dict(S=t[p][f"{v}@1"][a], A=t[p]["A@1"][a]) for p in K6.YEAR_PERIODS}
        g = S6.gates(per, qa, K6.YEAR_PERIODS, K6.YEAR_BETTER)
        g.update(variant=v, q_own=round(qo, 4), twin_p=t["twins"][a]["p"],
                 passed_except_account_stop=not [f for f in g["fails"] if "account stop" not in f])
        out[a] = g
    out["_m"] = dict(tests=len(arms), selection=N_SEL, prior=R7.M_TOTAL_C, total=m)
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
