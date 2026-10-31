"""R142 (PREREG.md): R141's leads tested on the paper arms. Bad-break filters (R72, R51, Liquidity) and a "no progress"
exit (R72, R51, R52), gated on losing less (R140's gates). Research only: no arm or app code changes.

    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r142_bad_break_noprogress/r142.py run
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r140_structure_arms"))
import r140a as A  # noqa: E402

M, K, L, S = A.M, A.K, A.L, A.S
N = 375
RESULTS = HERE / "results"
SEED = 142
TWINS = M.TWINS
PERIODS = ("design", "wf", "final")

TESTS = {}
for _arm, _a in (("R72", "72"), ("R51", "51"), ("LIQ", "L")):
    for _f in ("BB1", "BB2", "BB3", "BBX"):
        TESTS[f"{_f}-{_a}"] = dict(arm=_arm, kind="filter", rule=_f)
TESTS["T11-51"] = dict(arm="R51", kind="filter", rule="T11")
for _arm, _a in (("R72", "72"), ("R51", "51"), ("R52", "52")):
    for _n, _x in ((5, 0.05), (10, 0.05), (15, 0.10)):
        TESTS[f"NP{_n}-{_a}"] = dict(arm=_arm, kind="exit", n=_n, x=_x)
LABEL = dict(BB1="skip after an earlier fake poke today", BB2="skip a weak push into the entry (lowest 2 fifths)",
             BB3="skip a quiet signal bar (lowest fifth of range / ATR)", BBX="skip if any of BB1-BB3",
             T11="skip entries 10:30-12:00")
T11_ROWS = (75, 165)                       # 10:30 .. 11:59


# ================================================================================================ the rules
def bad(rule: str, f: dict, cut: dict, er: int) -> bool:
    """Is the candidate a bad break under `rule`? f: its R140A features; cut: the arm's design cuts. Unknown: not bad."""
    def fin(x):
        return x is not None and isinstance(x, (int, float, np.floating, np.integer)) and np.isfinite(x)
    if rule == "BB1":
        return fin(f.get("A11")) and f["A11"] >= 1
    if rule == "BB2":
        return fin(f.get("A1")) and f["A1"] < cut["A1_40"]
    if rule == "BB3":
        return fin(f.get("A7")) and f["A7"] < cut["A7_20"]
    if rule == "BBX":
        return any(bad(r, f, cut, er) for r in ("BB1", "BB2", "BB3"))
    if rule == "T11":
        return T11_ROWS[0] <= er < T11_ROWS[1]
    raise ValueError(rule)


def no_progress_row(con, er: int, n: int, x: float) -> int:
    """The forced sell row: fill + n, if the option's minute highs over [er, er + n - 1] never reach open[er] x (1 + x).
    con: (5, 375) O H L C V of the contract. -1 when it made progress, has no entry price, or the row is past 15:29."""
    if con is None or er + n >= N:
        return -1
    o0 = con[0][er]
    if not (np.isfinite(o0) and o0 > 0):
        return -1
    h = con[1][er:er + n]
    h = h[np.isfinite(h)]
    if len(h) and h.max() >= o0 * (1 + x):
        return -1
    return er + n


def exits_for(arm: str):
    a = S.mods()["A"]
    return a._oov_l25() if arm == "R52" else a._xl()


def exit_priced(arm: str, us: list, n: int, x: float, force_all: bool = False) -> list:
    """Every candidate re-priced with the no-progress row as a forced exit (R106's engine with the arm's own exits);
    a candidate with no forced row keeps R101's own price (force_all re-prices it anyway: the parity check)."""
    Mm = S.mods()
    ex = exits_for(arm)
    out = []
    for key, cands, res, how in us:
        D = Mm["r51data"].load(key[0])[key[1]]
        fxs = [no_progress_row(D.contract(c["side"], c["strike"]), int(c["er"]), n, x) for c in cands]
        if not force_all and all(f < 0 for f in fxs):
            out.append(list(res))
            continue
        pr = Mm["X"].price(key[0], D, [dict(c, fx=(f if not force_all else -1)) for c, f in zip(cands, fxs)], ex)
        out.append(pr if force_all else [p if f >= 0 else r for p, r, f in zip(pr, res, fxs)])
    return out


def engine_parity(arm: str, us: list, k: int = 200):
    """R106's engine without a forced exit prices the first k units as R101 did (R52's exits included)."""
    pr = exit_priced(arm, us[:k], 5, 0.05, force_all=True)
    for (key, cands, res, _), p in zip(us[:k], pr):
        for a, b in zip(p, res):
            assert (a is None) == (b is None) and (a is None or abs(a["net"] - b["net"]) < 1e-6), (arm, key)


def exit_twins(arm: str, rule_tr: list, base_res: dict, lo: date, hi: date, n: int, rng) -> tuple:
    """R140's exit twin with the arm's own exits: each trade sold earlier is sold at a random minute in between."""
    Mm = S.mods()
    ex = exits_for(arm)
    fixed, rows = [], []
    for t in rule_tr:
        if not lo <= t["day"] <= hi:
            continue
        p = base_res.get((t["und"], t["day"], int(t["er_sig"]), t["side"]))
        if p is None or int(t["xr"]) >= int(p["xr"]) or int(p["xr"]) - int(t["er"]) < 2:
            fixed.append((t["day"], float(t["net"])))
            continue
        D = Mm["r51data"].load(t["und"])[t["day"]]
        r = np.arange(int(t["er"]) + 1, int(p["xr"]), dtype=np.int64)
        rows.append((t["day"], np.asarray(Mm["X"].forced_exit_nets(t["und"], D, t, ex, r, -1), float)))
    out = np.zeros(n)
    for i in range(n):
        out[i] = M.loss_sum(list(fixed) + [(d, v[rng.integers(0, len(v))]) for d, v in rows if len(v)])
    return out, len(rows)


def filter_twins(arm: str, us: list, keep, lo: date, hi: date, n: int, rng) -> np.ndarray:
    flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
    k = sum(1 for g, u in enumerate(us) for j, c in enumerate(u[1]) if not keep(g, j, u[0], c))
    out = np.zeros(n)
    for i in range(n):
        gone = {flat[q] for q in L.random_drop(len(flat), k, rng)}
        tr = S.walk(arm, S.filtered(us, lambda g, j, key, c: (g, j) not in gone))
        out[i] = M.loss_sum((t["day"], t["net"]) for t in tr if lo <= t["day"] <= hi)
    return out


# ================================================================================================ the run
def stats(tr: list) -> dict:
    s = M.loss_stats(tr)
    s["win_rate"] = s.get("win")
    return s


def run() -> dict:
    Mm = S.mods()
    Mm["C"].open_final()
    rng = np.random.default_rng(SEED)
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), note="final not fresh (R140/R141 read it)"),
           "today": {}, "tests": {}}
    lo_f, hi_f = K.FINAL_START, K.FINAL_END
    cache = {}
    for t, v in TESTS.items():
        arm = v["arm"]
        if arm not in cache:
            us = S.units(arm, K.START, K.FINAL_END)
            base = S.walk(arm, us)
            for ph, (a, b) in (("design", (K.START, K.DESIGN_END)), ("final", (lo_f, hi_f))):
                pub = K.PUBLISHED[(arm, ph)]
                tr = [x for x in base if a <= x["day"] <= b]
                assert pub[0] == len(tr) and abs(pub[1] - sum(x["net"] for x in tr)) <= 1, (arm, ph, "parity")
            feats = A.all_features(us) if arm != "R52" else {}
            des = [x for x in base if K.START <= x["day"] <= K.DESIGN_END]
            cut = {}
            if feats:
                a1 = [feats.get(A.trade_key(x), {}).get("A1", np.nan) for x in des]
                a7 = [feats.get(A.trade_key(x), {}).get("A7", np.nan) for x in des]
                cut = dict(A1_40=float(np.nanquantile(np.array(a1, float), 0.4)),
                           A7_20=float(np.nanquantile(np.array(a7, float), 0.2)))
            if arm in ("R52",):
                engine_parity(arm, us)
            base_res = {(key[0], key[1], int(c["er"]), c["side"]): r
                        for key, cs, rr, _ in us for c, r in zip(cs, rr) if r is not None}
            cache[arm] = (us, base, feats, cut, base_res)
            out["today"][arm] = {p: stats(S.by_period(base, p)) for p in PERIODS}
            out["today"][arm]["cuts"] = cut
        us, base, feats, cut, base_res = cache[arm]
        if v["kind"] == "filter":
            keep = (lambda g, j, key, c, rule=v["rule"], feats=feats, cut=cut:
                    not bad(rule, feats.get(A.cand_key(key[0], key[1], c), {}), cut, int(c["er"])))
            tr = S.walk(arm, S.filtered(us, keep))
            tw = filter_twins(arm, us, keep, lo_f, hi_f, TWINS, rng)
            extra = dict(dropped=sum(1 for g, u in enumerate(us) for j, c in enumerate(u[1]) if not keep(g, j, u[0], c)))
        else:
            tr = S.walk(arm, us, exit_priced(arm, us, v["n"], v["x"]))
            tw, nch = exit_twins(arm, tr, base_res, lo_f, hi_f, TWINS, rng)
            extra = dict(sold_earlier_final=nch)
        r = {p: stats(S.by_period(tr, p)) for p in PERIODS}
        r["changed_final"] = L.changed(S.by_period(tr, "final"), S.by_period(base, "final"))
        act = M.loss_sum((x["day"], x["net"]) for x in S.by_period(tr, "final"))
        r["twin"] = L.twin_p(act, tw)
        r.update(extra)
        out["tests"][t] = r
        print(t, {p: (r[p]["trades"], r[p]["net"], r[p]["loss_sum"], r[p]["win"]) for p in PERIODS}, "p",
              round(r["twin"]["p"], 4), flush=True)
    tests = list(TESTS)
    ps = [out["tests"][t]["twin"]["p"] for t in tests]
    qs = L.bh(ps)
    for t, q, p in zip(tests, qs, ps):
        arm = TESTS[t]["arm"]
        g = M.gates({p_: out["tests"][t][p_] for p_ in PERIODS}, {p_: out["today"][arm][p_] for p_ in PERIODS},
                    float(q), float(p), int(out["tests"][t]["changed_final"]))
        out["tests"][t].update(q=round(float(q), 4), passed=g["passed"], fails=g["fails"])
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "r142.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    if (sys.argv[1:] or [""])[0] == "run":
        run()
    else:
        raise SystemExit(__doc__)
