"""R143 (PREREG.md): the three frozen candidates on 1 Dec 2022 - 19 Nov 2024, never used by R139-R142.

    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r143_unseen_two_years/r143.py build        # old store
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r143_unseen_two_years/r143.py parity-main  # main store
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r143_unseen_two_years/r143.py run          # the test

The old store lives in its own folder ($R51_OUT/r143_old), Sep 2022 - 31 Dec 2024. 20 Nov - 31 Dec 2024 overlaps the
main store and is used only for the engine check: both stores must give the same trades there.
Research only: nothing in the app or any arm changes.
"""
from __future__ import annotations

import json
import os
import sys
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
CMD = (sys.argv[1:] or [""])[0]
OLD = CMD in ("build", "run")
if OLD:                                   # before anything imports r51cfg: the old store's own folder
    base = Path(os.environ.get("R51_OUT", Path.home() / ".cache" / "iraalgo" / "r51")).expanduser()
    os.environ["R51_OUT"] = str(base / "r143_old")

import numpy as np  # noqa: E402

sys.path.insert(0, str(HERE.parent / "r142_bad_break_noprogress"))
import r142 as B  # noqa: E402

A, M, K, L, S = B.A, B.M, B.K, B.L, B.S
RESULTS = HERE / "results"
SEED = 143
TWINS = 2000
TEST = (date(2022, 12, 1), date(2024, 11, 19))
YEARS = {"Y1": (date(2022, 12, 1), date(2023, 11, 30)), "Y2": (date(2023, 12, 1), date(2024, 11, 19))}
CHECK = (date(2024, 11, 20), date(2024, 12, 31))
# The engine check skips the old store's last day: its expiry flag needs the next session, which the old store does not
# have (31 Dec 2024 was a FINNIFTY expiry; found 31 Oct, the only difference between the stores).
CHECK_CMP = (date(2024, 11, 20), date(2024, 12, 30))
OLD_WARM, OLD_START, OLD_END = date(2022, 9, 1), date(2022, 11, 1), date(2024, 12, 31)
A10_CUT = 1.3206
CUTS = {"R51": dict(A1_40=1.6274061753648996, A7_20=0.9946660973025536),
        "R72": dict(A1_40=1.8093962496420002, A7_20=1.0672037114280541)}
TESTS = {"FB-R72-A10": dict(arm="R72", kind="filter", rule="A10"),
         "NP5-52": dict(arm="R52", kind="exit", n=5, x=0.05),
         "BBX-51": dict(arm="R51", kind="filter", rule="BBX")}
REPORTED = {"BBX-72": dict(arm="R72", kind="filter", rule="BBX"),
            "BB1-51": dict(arm="R51", kind="filter", rule="BB1"),
            "NP10-52": dict(arm="R52", kind="exit", n=10, x=0.05)}


def r51cfg():
    return S.mods()["r51data"].K


def patch_old():
    k = r51cfg()
    k.WARM_START, k.START, k.END = OLD_WARM, OLD_START, OLD_END


def drops(rule: str, f: dict, arm: str, er: int) -> bool:
    """Is the candidate dropped by the frozen rule? Unknown never drops."""
    if rule == "A10":
        x = f.get("A10")
        return x is not None and np.isfinite(x) and x >= A10_CUT
    return B.bad(rule, f, CUTS[arm], er)


def in_(t, lo, hi):
    return lo <= t["day"] <= hi


def stats(tr: list, lo: date, hi: date) -> dict:
    return M.loss_stats([t for t in tr if in_(t, lo, hi)])


def gate(rule: dict, base: dict, p: float, q: float, changed: int) -> dict:
    fails = []
    if not rule["loss_sum"] > base["loss_sum"]:
        fails.append("losing days not smaller")
    if rule["max_dd"] > base["max_dd"]:
        fails.append("drawdown worse")
    if rule["worst_day"] < base["worst_day"]:
        fails.append("worst day worse")
    if rule["worst_month"] < base["worst_month"]:
        fails.append("worst month worse")
    if not rule["net"] > 0:
        fails.append("no longer makes money")
    if not p < 0.05:
        fails.append(f"twin p {p:.3g}")
    if q > 0.10:
        fails.append(f"q {q:.3g}")
    if rule["trades"] < 40:
        fails.append("< 40 trades")
    if changed < 30:
        fails.append("< 30 trades differ")
    return dict(passed=not fails, fails=fails)


def trade_set(tr: list) -> list:
    return sorted([t["und"], str(t["day"]), int(t["er_sig"]), t["side"], round(float(t["net"]), 2)] for t in tr)


def arm_trades(arm: str, lo: date, hi: date):
    us = S.units(arm, lo, hi)
    return us, S.walk(arm, us)


# ================================================================================================ phases
def build():
    patch_old()
    import r51data as R  # noqa: F401  (pickle the Day class under its module name)
    for u in r51cfg().UNDS:
        print(S.mods()["r51data"].build(u), flush=True)


def parity_main():
    S.mods()["C"].open_final()
    out = {arm: trade_set(arm_trades(arm, *CHECK)[1]) for arm in K.ARMS}
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "parity_main.json").write_text(json.dumps(out))
    print({a: len(v) for a, v in out.items()})


def run():
    patch_old()
    main = json.loads((RESULTS / "parity_main.json").read_text())
    rng = np.random.default_rng(SEED)
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), test=[str(x) for x in TEST]), "parity": {},
           "today": {}, "tests": {}, "reported": {}}
    cache = {}
    for t, v in {**TESTS, **REPORTED}.items():
        arm = v["arm"]
        if arm not in cache:
            us, base = arm_trades(arm, TEST[0], CHECK[1])
            mine = trade_set([x for x in base if in_(x, *CHECK_CMP)])
            ref = [x for x in main[arm] if x[1] <= str(CHECK_CMP[1])]
            same = mine == ref
            out["parity"][arm] = dict(main=len(ref), old=len(mine), identical=same)
            print("parity", arm, out["parity"][arm], flush=True)
            assert same, (arm, "the old store does not reproduce the main store's trades on 20 Nov - 30 Dec 2024")
            feats = A.all_features(us) if arm != "R52" else {}
            base_res = {(key[0], key[1], int(c["er"]), c["side"]): r
                        for key, cs, rr, _ in us for c, r in zip(cs, rr) if r is not None}
            cache[arm] = (us, base, feats, base_res)
            out["today"][arm] = dict(test=stats(base, *TEST), **{y: stats(base, *p) for y, p in YEARS.items()})
        us, base, feats, base_res = cache[arm]
        if v["kind"] == "filter":
            keep = (lambda g, j, key, c, rule=v["rule"], feats=feats, arm=arm:
                    not drops(rule, feats.get(A.cand_key(key[0], key[1], c), {}), arm, int(c["er"])))
            tr = S.walk(arm, S.filtered(us, keep))
            tw = B.filter_twins(arm, us, keep, *TEST, TWINS, rng) if t in TESTS else None
        else:
            tr = S.walk(arm, us, B.exit_priced(arm, us, v["n"], v["x"]))
            tw = B.exit_twins(arm, tr, base_res, *TEST, TWINS, rng)[0] if t in TESTS else None
        r = dict(test=stats(tr, *TEST), **{y: stats(tr, *p) for y, p in YEARS.items()})
        r["changed"] = L.changed([x for x in tr if in_(x, *TEST)], [x for x in base if in_(x, *TEST)])
        if tw is not None:
            r["twin"] = L.twin_p(M.loss_sum((x["day"], x["net"]) for x in tr if in_(x, *TEST)), tw)
        (out["tests"] if t in TESTS else out["reported"])[t] = r
        print(t, {k: (r[k]["trades"], r[k]["net"], r[k]["loss_sum"], r[k]["max_dd"], r[k]["win"]) for k in
                  ("test", "Y1", "Y2")}, "today", {k: (out["today"][arm][k]["trades"], out["today"][arm][k]["net"],
                                                      out["today"][arm][k]["loss_sum"], out["today"][arm][k]["max_dd"])
                                                   for k in ("test",)}, flush=True)
    ps = [out["tests"][t]["twin"]["p"] for t in TESTS]
    for t, q, p in zip(TESTS, L.bh(ps), ps):
        r = out["tests"][t]
        g = gate(r["test"], out["today"][TESTS[t]["arm"]]["test"], float(p), float(q), int(r["changed"]))
        r.update(q=round(float(q), 4), **g)
        print(t, g, "p", round(p, 4), flush=True)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "r143.json").write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    {"build": build, "parity-main": parity_main, "run": run}.get(CMD, lambda: (_ for _ in ()).throw(
        SystemExit(__doc__)))()
