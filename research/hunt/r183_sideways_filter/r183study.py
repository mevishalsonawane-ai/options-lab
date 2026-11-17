"""R183 (PREREG.md): skip R51 / R72 entries when the index is sideways. Baseline B = R179's V4 (today's rules + R151
picker + breakeven at +10%). 16 filter variants selected on 20 Nov 2024 - 19 Oct 2025; the chosen one per arm tested ONCE
on the last year (20 Oct 2025 - 19 Oct 2026).

    R176_POST=1 python research/hunt/r183_sideways_filter/r183study.py run      # selection, then the one test read
    python research/hunt/r183_sideways_filter/r183study.py decide                # gates -> results/decision.json

Reuses R179 (B's pricing with the breakeven lock), R177 / R176 (units, walk, picker, stats, gates). Skips are applied
after the walk, as the app's picker: a skipped entry still holds its side and counts in the day's caps. Pure parts are
in r183lib.py (test_r183.py). Plain-English notes, 15 Nov 2026.
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
for p in (str(HERE.parent / "r179_winrate_55"), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r179study as R9  # noqa: E402
import r183lib as LB  # noqa: E402

R7, S6, K6, L = R9.R7, R9.S6, R9.K6, R9.L
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
SEL_START, SEL_END = date(2024, 11, 20), date(2025, 10, 19)
H1_END, H2_START = date(2025, 4, 30), date(2025, 5, 1)
TWINS = 2000
SEED = 183
# R179's published V4 at 1 lot (results/year.json): (trades, net)
V4_PUB = {("R72", "final"): (604, 167233), ("R72", "year"): (1069, 324629),
          ("R51", "final"): (655, 144802), ("R51", "year"): (1367, 249903)}
TEST_LOTS = (1, 3)
_FEAT: dict = {}
_DAYS: dict = {}


# ================================================================================================ data
def _days(und: str):
    if und not in _DAYS:
        D = R7.mods()["r51data"].load(und)
        _DAYS[und] = (D, sorted(D))
    return _DAYS[und]


def feats_at(und: str, d, er: int) -> dict:
    """Every filter's value for a trade on `und`, day d, signal-entry row er (signal bar er - 1)."""
    d = S6._d(d)
    key = (und, d, int(er))
    if key not in _FEAT:
        D, ks = _days(und)
        i = ks.index(d)
        prev = [D[k].idx for k in ks[max(0, i - LB.N_ADR):i]]
        _FEAT[key] = LB.features(prev, D[d].idx, int(er) - 1)
    return _FEAT[key]


def baseline(arm: str, lo: date, hi: date, lots: int, P=None, sc=None, thr=None, us=None) -> tuple:
    """B = V4 trades in lo .. hi at `lots` (R179's construction exactly), plus the units / picker for reuse."""
    if us is None:
        us0 = S6.units(arm, lo, hi)
        usA, _ = R7.apply_rule(arm, us0, None)
        P = S6.picker()
        sc, thr = P.scores(arm, us0)
        us = usA
    tr = [t for t in S6.walk(arm, us, R9.priced(arm, us, "A", True, lots)) if lo <= t["day"] <= hi]
    return [t for t in tr if P.kept(t, sc, thr)], (P, sc, thr, us)


def filtered(tr: list, v: str) -> list:
    return [t for t in tr if not LB.skip(v, feats_at(t["und"], t["day"], t["er_sig"]))]


def _net(tr, lo, hi):
    return sum(t["net"] for t in tr if lo <= t["day"] <= hi)


def _desc(tr, lo, hi):
    sub = [t for t in tr if lo <= t["day"] <= hi]
    return S6.describe(sub, len(S6.sessions(lo, hi)), K6.months_between(lo, hi))


def _short(x: dict) -> dict:
    return {k: x[k] for k in ("trades", "win", "net", "max_dd", "worst_month", "avg_win", "avg_loss")}


def select() -> dict:
    out = {}
    for arm in LB.ARMS:
        b, _ = baseline(arm, SEL_START, SEL_END, 1)
        bH1, bH2, bT = _net(b, SEL_START, H1_END), _net(b, H2_START, SEL_END), _net(b, SEL_START, SEL_END)
        info = {"B": dict(_short(_desc(b, SEL_START, SEL_END)), H1=round(bH1), H2=round(bH2))}
        rows = {}
        for v in LB.VARIANTS:
            tr = filtered(b, v)
            h1, h2, tot = _net(tr, SEL_START, H1_END), _net(tr, H2_START, SEL_END), _net(tr, SEL_START, SEL_END)
            rows[v] = (h1 - bH1, h2 - bH2, tot - bT)
            info[v] = dict(_short(_desc(tr, SEL_START, SEL_END)), H1=round(h1), H2=round(h2), gain=round(tot - bT),
                           gain_H1=round(h1 - bH1), gain_H2=round(h2 - bH2), skipped=len(b) - len(tr),
                           eligible=bool(h1 > bH1 and h2 > bH2))
        info["chosen"] = LB.choose(rows)
        out[arm] = info
        print(arm, "selection", {k: (v["net"], v.get("gain"), v.get("eligible")) if isinstance(v, dict) else v
                                 for k, v in info.items()}, flush=True)
    return out


def test(chosen: dict) -> dict:
    lo, hi = K6.Y_START, K6.Y_END
    out = {"parity": {}, "twins": {}, "chosen": chosen}
    trades = {}
    rng = np.random.default_rng(SEED)
    for arm in LB.ARMS:
        ctx = None
        for lots in TEST_LOTS:
            if ctx is None:
                b, ctx = baseline(arm, lo, hi, lots)
            else:
                b, _ = baseline(arm, lo, hi, lots, *ctx)
            if lots == 1:                                         # parity before anything else is read
                for per in ("final", "year"):
                    sub = S6.in_span(b, per)
                    pub = V4_PUB[(arm, per)]
                    r = dict(trades=len(sub), net=round(sum(t["net"] for t in sub)), published=list(pub))
                    r["reproduced"] = r["trades"] == pub[0] and abs(r["net"] - pub[1]) <= 1
                    out["parity"][f"{arm}:{per}"] = r
                    print(arm, per, "parity", r, flush=True)
                    assert r["reproduced"], ("parity failed", arm, per, r)
            trades.setdefault(("B", lots), {})[arm] = b
            v = chosen.get(arm)
            if v:
                tr = filtered(b, v)
                trades.setdefault(("S", lots), {})[arm] = tr
                if lots == 1:
                    k = len(b) - len(tr)
                    tw = L.selection_twin([t["net"] for t in b], k, TWINS, rng)
                    act = sum(t["net"] for t in tr)
                    out["twins"][arm] = dict(variant=v, p_emp=round(R7.mods()["S"].twin_p(act, tw), 4),
                                             p_norm=float(LB.normal_p(act, tw)), mean=round(float(tw.mean())),
                                             sd=round(float(tw.std(ddof=1))), skipped=k, net=round(act))
                    print(arm, v, "twin", out["twins"][arm], flush=True)
            if lots == 1:                                         # every variant, descriptive, after the chosen read
                for vv in LB.VARIANTS:
                    trades.setdefault((vv, 1), {})[arm] = filtered(b, vv)
    for per in K6.YEAR_PERIODS:
        plo, phi = K6.span_of(per)
        n_sess = len(S6.sessions(plo, phi))
        months = K6.months_between(plo, phi)
        res = {"sessions": n_sess}
        for (vv, lots), by_arm in trades.items():
            res[f"{vv}@{lots}"] = {a_: S6.describe([t for t in tr if plo <= t["day"] <= phi], n_sess, months)
                                   for a_, tr in by_arm.items()}
        out[per] = res
    out["skipped_year"] = {f"{vv}@{lots}": {a_: len(trades[("B", lots)][a_]) - len(tr) for a_, tr in by_arm.items()}
                           for (vv, lots), by_arm in trades.items() if vv != "B"}
    return out


def run():
    assert K6.POST, "R183 reads the stores built to 19 Oct (R176_POST=1)"
    RESULTS.mkdir(parents=True, exist_ok=True)
    if FINAL_FLAG.exists():
        raise SystemExit("R183 already read once: " + FINAL_FLAG.read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    R7.mods()["C"].open_final()
    sel = select()
    (RESULTS / "selection.json").write_text(json.dumps(sel, indent=1, default=str))
    chosen = {a: sel[a]["chosen"] for a in LB.ARMS if sel[a]["chosen"]}
    t = test(chosen)
    (RESULTS / "test.json").write_text(json.dumps(t, indent=1, default=str))


def decide() -> dict:
    t = json.loads((RESULTS / "test.json").read_text())
    arms = [a for a in LB.ARMS if a in t["chosen"]]
    ps = [t["twins"][a]["p_norm"] for a in arms]
    m = LB.m_total(len(arms))
    q_all, q_own = (S6.bh_q(ps, m), S6.bh_q(ps, len(ps))) if ps else ([], [])
    out = {}
    for a, qa, qo in zip(arms, q_all, q_own):
        per = {p: dict(S=t[p]["S@1"][a], A=t[p]["B@1"][a]) for p in K6.YEAR_PERIODS}
        g = S6.gates(per, qa, K6.YEAR_PERIODS, K6.YEAR_BETTER)
        g["fails"] = [f.replace("exit-twin", "random-skip twin").replace("today's rules", "the baseline (V4)")
                      for f in g["fails"]]
        if not t["twins"][a]["p_emp"] < 0.05:
            g["fails"].append(f"empirical twin p {t['twins'][a]['p_emp']} >= 0.05")
        g["passed"] = not g["fails"]
        g.update(variant=t["chosen"][a], q_own=round(qo, 4), p_emp=t["twins"][a]["p_emp"],
                 p_norm=round(t["twins"][a]["p_norm"], 6),
                 passed_except_account_stop=not [f for f in g["fails"] if "account stop" not in f])
        out[a] = g
    out["_m"] = dict(tests=len(arms), selection=LB.N_SEL, prior=LB.M_PRIOR, total=m)
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
