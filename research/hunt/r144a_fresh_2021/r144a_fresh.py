"""R144a fresh test (PREREG.md): six frozen skip / exit rules on 1 Nov 2021 - 30 Nov 2022, a period no study has used.

    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r144a_fresh_2021/r144a_fresh.py fbuild    # fresh store
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r144a_fresh_2021/r144a_fresh.py fparity   # R143 store, Dec 2022
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r144a_fresh_2021/r144a_fresh.py frun      # the test (once)

The fresh store lives in its own folder ($R51_OUT/r144a_fresh), 2 Aug 2021 - 31 Dec 2022. Dec 2022 overlaps R143's old
store and is used only for the engine check. Research only: nothing in the app or any arm changes. 1 Nov 2026.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
CMD = (sys.argv[1:] or [""])[0] if __name__ == "__main__" else ""
_BASE = Path(os.environ.get("R51_OUT", Path.home() / ".cache" / "iraalgo" / "r51")).expanduser()
if CMD in ("fbuild", "frun"):
    os.environ["R51_OUT"] = str(_BASE / "r144a_fresh")
elif CMD == "fparity":
    os.environ["R51_OUT"] = str(_BASE / "r143_old")

import numpy as np  # noqa: E402

RESULTS = HERE / "results"
SEED = 14421
TWINS = 2000
FRESH = (date(2021, 11, 1), date(2022, 11, 30))
HALVES = {"Nov21-May22": (date(2021, 11, 1), date(2022, 5, 31)), "Jun-Nov22": (date(2022, 6, 1), date(2022, 11, 30))}
CHECK = (date(2022, 12, 1), date(2022, 12, 30))     # the last day of a store cannot get its expiry flag (R143)
STORE = dict(fresh=(date(2021, 8, 2), date(2021, 11, 1), date(2022, 12, 31)),
             r143=(date(2022, 9, 1), date(2022, 11, 1), date(2024, 12, 31)))
A10_CUT = 1.3206
V4_CUT = 1.3162
CUTS = {"R51": dict(A1_40=1.6274061753648996, A7_20=0.9946660973025536),
        "R72": dict(A1_40=1.8093962496420002, A7_20=1.0672037114280541)}
TESTS = {"FB-R72-A10": dict(arm="R72", kind="filter", rule="A10", proven=True),
         "NP5-52": dict(arm="R52", kind="exit", n=5, x=0.05, proven=True),
         "BBX-72": dict(arm="R72", kind="filter", rule="BBX", proven=False),
         "BBX-51": dict(arm="R51", kind="filter", rule="BBX", proven=False),
         "R144A-72-V4": dict(arm="R72", kind="filter", rule="V4", proven=False),
         "R144A-51-F4": dict(arm="R51", kind="filter", rule="F4", proven=False)}
GATE_P, GATE_Q, MIN_TRADES, MIN_CHANGED = 0.05, 0.10, 40, 30


# ================================================================================================ pure
def fin(x) -> bool:
    return x is not None and isinstance(x, (int, float, np.floating, np.integer)) and np.isfinite(x)


def drops(rule: str, f: dict, arm: str) -> bool:
    """Is the candidate dropped by the frozen rule? f: R140A features plus V4 / F4. Unknown never drops."""
    if rule == "A10":
        return fin(f.get("A10")) and f["A10"] >= A10_CUT
    if rule == "V4":
        return fin(f.get("V4")) and f["V4"] >= V4_CUT
    if rule == "F4":
        return f.get("F4") == "covering against"
    if rule == "BBX":
        c = CUTS[arm]
        return ((fin(f.get("A11")) and f["A11"] >= 1) or (fin(f.get("A1")) and f["A1"] < c["A1_40"])
                or (fin(f.get("A7")) and f["A7"] < c["A7_20"]))
    raise ValueError(rule)


def gate(rule: dict, base: dict, p: float, q: float, changed: int) -> list:
    f = []
    if not rule["loss_sum"] > base["loss_sum"]:
        f.append("losing days not smaller")
    if rule["max_dd"] > base["max_dd"]:
        f.append("drawdown worse")
    if rule["worst_day"] < base["worst_day"]:
        f.append("worst day worse")
    if rule["worst_month"] < base["worst_month"]:
        f.append("worst month worse")
    if base["net"] > 0 and not rule["net"] > 0:
        f.append("no longer makes money")
    if not p <= GATE_P:
        f.append(f"twin p {p:.3g} > {GATE_P}")
    if q > GATE_Q:
        f.append(f"BH q {q:.3g} > {GATE_Q}")
    if rule["trades"] < MIN_TRADES:
        f.append(f"< {MIN_TRADES} trades")
    if changed < MIN_CHANGED:
        f.append(f"< {MIN_CHANGED} trades differ")
    return f


def trade_set(tr: list) -> list:
    return sorted([t["und"], str(t["day"]), int(t["er_sig"]), t["side"], round(float(t["net"]), 2)] for t in tr)


# ================================================================================================ engine
def engine(store: str):
    sys.path.insert(0, str(HERE.parent / "r142_bad_break_noprogress"))
    sys.path.insert(0, str(HERE.parent / "r144a_loss_breakout"))
    import r142 as B
    k = B.S.mods()["r51data"].K
    k.WARM_START, k.START, k.END = STORE[store]
    return B


def build():
    B = engine("fresh")
    import r51data  # noqa: F401  (pickle the Day class under its module name)
    for u in B.S.mods()["r51data"].K.UNDS:
        try:
            print(B.S.mods()["r51data"].build(u), flush=True)
        except ValueError as e:              # SENSEX: no option days before May 2023 (the store is still written)
            print(u, "store written, no option days:", e, flush=True)


def parity():
    B = engine("r143")
    out = {}
    for arm in ("R72", "R51", "R52"):
        tr = B.S.walk(arm, B.S.units(arm, *CHECK))
        out[arm] = trade_set(tr)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "parity_r143.json").write_text(json.dumps(out))
    print({a: len(v) for a, v in out.items()})


def run():
    flag = RESULTS / "FRESH_READ"
    if flag.exists():
        raise SystemExit("fresh period already read once: " + flag.read_text())
    B = engine("fresh")
    import r144a_feat as F
    A, M, L, S = B.A, B.M, B.L, B.S
    ref = json.loads((RESULTS / "parity_r143.json").read_text())
    flag.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    dctx = F.daily_context()
    rng = np.random.default_rng(SEED)
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), fresh=[str(x) for x in FRESH]), "parity": {},
           "today": {}, "tests": {}}
    cache = {}
    for t, v in TESTS.items():
        arm = v["arm"]
        if arm not in cache:
            us = S.units(arm, FRESH[0], CHECK[1])
            base_all = S.walk(arm, us)
            mine = trade_set([x for x in base_all if CHECK[0] <= x["day"] <= CHECK[1]])
            same = mine == ref[arm]
            out["parity"][arm] = dict(r143=len(ref[arm]), fresh=len(mine), identical=same)
            print("parity", arm, out["parity"][arm], flush=True)
            assert same, (arm, "the fresh store does not reproduce R143's trades on 1 - 30 Dec 2022")
            us = [u for u in us if FRESH[0] <= u[0][1] <= FRESH[1]]
            base = S.walk(arm, us)
            feats = A.all_features(us) if arm != "R52" else {}
            for key, cands, _, _ in us:
                ctx = dctx.get(key[0], {}).get(key[1], {})
                for c in cands:
                    k = A.cand_key(key[0], key[1], c)
                    nf = F.features(None, None, np.full((4, F.N), np.nan), int(c["er"]), c["side"], c["strike"], ctx)
                    feats.setdefault(k, {}).update(V4=nf["V4"], F4=nf["F4"])
            base_res = {(key[0], key[1], int(c["er"]), c["side"]): r
                        for key, cs, rr, _ in us for c, r in zip(cs, rr) if r is not None}
            cache[arm] = (us, base, feats, base_res)
            out["today"][arm] = dict(fresh=M.loss_stats(base), **{h: M.loss_stats([x for x in base if a <= x["day"] <= b])
                                                                  for h, (a, b) in HALVES.items()})
            out["today"][arm]["by_index"] = {u: M.loss_stats([x for x in base if x["und"] == u])
                                             for u in sorted({x["und"] for x in base})}
        us, base, feats, base_res = cache[arm]
        if v["kind"] == "filter":
            keep = (lambda g, j, key, c, rule=v["rule"], feats=feats, arm=arm:
                    not drops(rule, feats.get(A.cand_key(key[0], key[1], c), {}), arm))
            tr = S.walk(arm, S.filtered(us, keep))
            dropped = sum(1 for g, u in enumerate(us) for j, c in enumerate(u[1]) if not keep(g, j, u[0], c))
            tw = B.filter_twins(arm, us, keep, *FRESH, TWINS, rng)
            extra = dict(dropped_candidates=dropped, candidates=sum(len(u[1]) for u in us))
        else:
            tr = S.walk(arm, us, B.exit_priced(arm, us, v["n"], v["x"]))
            tw, nch = B.exit_twins(arm, tr, base_res, *FRESH, TWINS, rng)
            extra = dict(sold_earlier=nch)
        r = dict(fresh=M.loss_stats(tr), **{h: M.loss_stats([x for x in tr if a <= x["day"] <= b])
                                            for h, (a, b) in HALVES.items()}, **extra)
        r["changed"] = L.changed(tr, base)
        r["twin"] = L.twin_p(M.loss_sum((x["day"], x["net"]) for x in tr), tw)
        out["tests"][t] = r
        print(t, (r["fresh"]["trades"], r["fresh"]["net"], r["fresh"]["loss_sum"], r["fresh"]["max_dd"]), "today",
              (out["today"][arm]["fresh"]["trades"], out["today"][arm]["fresh"]["net"],
               out["today"][arm]["fresh"]["loss_sum"], out["today"][arm]["fresh"]["max_dd"]),
              "p", round(r["twin"]["p"], 4), flush=True)
    ps = [out["tests"][t]["twin"]["p"] for t in TESTS]
    for t, q in zip(TESTS, L.bh(ps)):
        r = out["tests"][t]
        fails = gate(r["fresh"], out["today"][TESTS[t]["arm"]]["fresh"], r["twin"]["p"], float(q), int(r["changed"]))
        r.update(q=round(float(q), 4), passed=not fails, fails=fails)
        print(t, "PASS" if not fails else fails, flush=True)
    (RESULTS / "fresh.json").write_text(json.dumps(out, indent=1, default=str))
    pv = dict(_meta=dict(group="R144a fresh year", note="raw twin p (2,000 twins) on 1 Nov 2021 - 30 Nov 2022; when no "
                         "twin reached the rule, p is R115's normal-tail p (Monte-Carlo floor 1/2001)"),
              tests={t: dict(arm=TESTS[t]["arm"], p=out["tests"][t]["twin"]["p"], q_within_group=out["tests"][t]["q"])
                     for t in TESTS})
    (RESULTS / "pvalues.json").write_text(json.dumps(pv, indent=1))


if __name__ == "__main__":
    {"fbuild": build, "fparity": parity, "frun": run}.get(CMD, lambda: (_ for _ in ()).throw(SystemExit(__doc__)))()
