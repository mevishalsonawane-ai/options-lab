"""R144 (PREREG.md): Liquidity's health on the unseen Dec 2022 - Nov 2024, and the wide-day skip / 5-minute no-progress
exit on R51, Liquidity and R52. Research only.

    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r144_other_arms/r144.py recent   # main store
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r144_other_arms/r144.py unseen   # R143's older store
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
CMD = (sys.argv[1:] or [""])[0]
if CMD == "unseen":                       # R143's older store, before anything imports r51cfg
    base = Path(os.environ.get("R51_OUT", Path.home() / ".cache" / "iraalgo" / "r51")).expanduser()
    os.environ["R51_OUT"] = str(base / "r143_old")

import numpy as np  # noqa: E402

sys.path.insert(0, str(HERE.parent / "r143_unseen_two_years"))
import r143 as U  # noqa: E402

B, A, M, K, L, S = U.B, U.A, U.M, U.K, U.L, U.S
RESULTS = HERE / "results"
SEED = 144
TWINS = 2000
CUTS = {"R51": 1.2907, "LIQ": 1.1961}          # R140A design top-fifth cuts; R52's is computed in `recent`
TESTS = {"W-51": dict(arm="R51", kind="wide"), "W-L": dict(arm="LIQ", kind="wide"),
         "W-52": dict(arm="R52", kind="wide"), "NP5-L": dict(arm="LIQ", kind="np", n=5, x=0.05)}
RECENT = {"design": (K.START, K.DESIGN_END), "wf": (date(2025, 4, 1), K.DESIGN_END), "final": (K.FINAL_START, K.FINAL_END)}
UNSEEN = {"test": U.TEST, **U.YEARS}


def wide(f: dict, cut: float) -> bool:
    x = f.get("A10")
    return x is not None and np.isfinite(x) and x >= cut


# ------------------------------------------------------------------------------------------------ Liquidity's own exit
def liq_np_fxs(D, cands: list, n: int, x: float) -> list:
    """Each candidate's forced row: the earlier of Liquidity's own (fx) and the no-progress row (-1 = none)."""
    out = []
    for c in cands:
        r = B.no_progress_row(D.contract(c["side"], c["strike"]), int(c["er"]), n, x)
        own = int(c.get("fx", -1))
        out.append(min(own, r) if (own >= 0 and r >= 0) else max(own, r))
    return out


def liq_price(und: str, D, cands: list, fxs: list):
    Ma = S.mods()["A"].mods()                      # R101's modules: Liquidity's own engine and exits
    return Ma["E"].run_day(und, D, [c["side"] for c in cands], [c["strike"] for c in cands],
                           [c["er"] for c in cands], Ma["fam_liq"].EX, fxs)


def liq_np_priced(us: list, n: int, x: float) -> list:
    Mm = S.mods()
    out = []
    for key, cands, res, how in us:
        D = Mm["r51data"].load(key[0])[key[1]]
        fxs = liq_np_fxs(D, cands, n, x)
        if all(f == int(c.get("fx", -1)) for f, c in zip(fxs, cands)):
            out.append(list(res))
            continue
        out.append(list(liq_price(key[0], D, cands, fxs)))
    return out


def liq_parity(us: list, k: int = 150):
    """Liquidity's own engine re-pricing with its own fx reproduces R101's prices (the first k units)."""
    Mm = S.mods()
    for key, cands, res, _ in us[:k]:
        D = Mm["r51data"].load(key[0])[key[1]]
        for a, b in zip(liq_price(key[0], D, cands, [int(c.get("fx", -1)) for c in cands]), res):
            assert (a is None) == (b is None) and (a is None or abs(a["net"] - b["net"]) < 1e-6), key


def liq_exit_twins(us: list, rule_tr: list, base_res: dict, lo: date, hi: date, n: int, rng) -> tuple:
    """Each trade the rule sold earlier is sold instead at a uniformly random row between its fill and today's exit
    (Liquidity's own engine, its own fx still applying when earlier)."""
    Mm = S.mods()
    cand = {(key[0], key[1], int(c["er"]), c["side"]): c for key, cs, _, _ in us for c in cs}
    fixed, rows = [], []
    for t in rule_tr:
        if not lo <= t["day"] <= hi:
            continue
        k = (t["und"], t["day"], int(t["er_sig"]), t["side"])
        p = base_res.get(k)
        if p is None or int(t["xr"]) >= int(p["xr"]) or int(p["xr"]) - int(t["er"]) < 2:
            fixed.append((t["day"], float(t["net"])))
            continue
        c = cand[k]
        D = Mm["r51data"].load(t["und"])[t["day"]]
        own = int(c.get("fx", -1))
        nets = []
        for r in range(int(t["er"]) + 1, int(p["xr"])):
            f = min(r, own) if own >= 0 else r
            res = liq_price(t["und"], D, [c], [f])[0]
            if res is not None:
                nets.append(float(res["net"]))
        rows.append((t["day"], np.array(nets)))
    out = np.zeros(n)
    for i in range(n):
        out[i] = M.loss_sum(list(fixed) + [(d, v[rng.integers(0, len(v))]) for d, v in rows if len(v)])
    return out, len(rows)


# ------------------------------------------------------------------------------------------------ one run
def rule_trades(t: str, v: dict, us: list, feats: dict, cut: float | None):
    arm = v["arm"]
    if v["kind"] == "wide":
        keep = (lambda g, j, key, c: not wide(feats.get(A.cand_key(key[0], key[1], c), {}), cut))
        return S.walk(arm, S.filtered(us, keep)), keep
    return S.walk(arm, us, liq_np_priced(us, v["n"], v["x"])), None


def run(mode: str):
    Mm = S.mods()
    if mode == "unseen":
        U.patch_old()
        lo, hi, periods = U.TEST[0], U.TEST[1], UNSEEN
        cuts = dict(CUTS, R52=json.loads((RESULTS / "recent.json").read_text())["cuts"]["R52"])
    else:
        Mm["C"].open_final()
        lo, hi, periods = K.START, K.FINAL_END, RECENT
        cuts = dict(CUTS)
    rng = np.random.default_rng(SEED + (1 if mode == "unseen" else 0))
    out = {"_meta": dict(mode=mode, written=datetime.now(timezone.utc).isoformat()), "today": {}, "tests": {}}
    cache = {}
    for arm in ("LIQ", "R51", "R52"):
        us = S.units(arm, lo, hi)
        base = S.walk(arm, us)
        feats = A.all_features(us)
        if arm == "LIQ":
            liq_parity(us)
        if arm == "R52" and mode == "recent":
            des = [x for x in base if K.START <= x["day"] <= K.DESIGN_END]
            a10 = np.array([feats.get(A.trade_key(x), {}).get("A10", np.nan) for x in des], float)
            cuts["R52"] = round(float(np.nanquantile(a10, 0.8)), 4)
            print("R52 cut", cuts["R52"], flush=True)
        base_res = {(key[0], key[1], int(c["er"]), c["side"]): r
                    for key, cs, rr, _ in us for c, r in zip(cs, rr) if r is not None}
        cache[arm] = (us, base, feats, base_res)
        out["today"][arm] = {p: M.loss_stats([x for x in base if a <= x["day"] <= b]) for p, (a, b) in periods.items()}
    out["cuts"] = cuts
    for t, v in TESTS.items():
        us, base, feats, base_res = cache[v["arm"]]
        tr, keep = rule_trades(t, v, us, feats, cuts.get(v["arm"]))
        r = {p: M.loss_stats([x for x in tr if a <= x["day"] <= b]) for p, (a, b) in periods.items()}
        key_p = "test" if mode == "unseen" else "final"
        a, b = periods[key_p]
        r["changed"] = L.changed([x for x in tr if a <= x["day"] <= b], [x for x in base if a <= x["day"] <= b])
        if mode == "unseen":
            if keep is not None:
                tw = B.filter_twins(v["arm"], us, keep, a, b, TWINS, rng)
            else:
                tw, _ = liq_exit_twins(us, tr, base_res, a, b, TWINS, rng)
            r["twin"] = L.twin_p(M.loss_sum((x["day"], x["net"]) for x in tr if a <= x["day"] <= b), tw)
        out["tests"][t] = r
        print(t, {p: (r[p]["trades"], r[p]["net"], r[p]["loss_sum"], r[p]["max_dd"], r[p]["win"]) for p in periods},
              "today", {p: (out["today"][v["arm"]][p]["trades"], out["today"][v["arm"]][p]["net"],
                            out["today"][v["arm"]][p]["loss_sum"]) for p in periods},
              "p", round(r.get("twin", {}).get("p", np.nan), 4), flush=True)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{mode}.json").write_text(json.dumps(out, indent=1, default=str))


def gate(rec: dict, uns: dict) -> dict:
    """PREREG: every period's loss measures; net > 0 in the recent periods, no lower than today's in the unseen years;
    twin p and BH on the unseen years; trade counts."""
    tests = list(TESTS)
    ps = [uns["tests"][t]["twin"]["p"] for t in tests]
    out = {}
    for t, q, p in zip(tests, L.bh(ps), ps):
        arm = TESTS[t]["arm"]
        fails = []
        for src, pers in ((rec, ("design", "wf", "final")), (uns, ("test",))):
            for per in pers:
                rr, bb = src["tests"][t][per], src["today"][arm][per]
                if not rr["loss_sum"] > bb["loss_sum"]:
                    fails.append(f"{per}: losing days not smaller")
                if rr["max_dd"] > bb["max_dd"]:
                    fails.append(f"{per}: drawdown worse")
                if rr["worst_day"] < bb["worst_day"]:
                    fails.append(f"{per}: worst day worse")
                if rr["worst_month"] < bb["worst_month"]:
                    fails.append(f"{per}: worst month worse")
                if per == "test":
                    if rr["net"] < bb["net"]:
                        fails.append("unseen: net lower than today's")
                elif not rr["net"] > 0:
                    fails.append(f"{per}: no longer makes money")
        if not p < 0.05:
            fails.append(f"twin p {p:.3g}")
        if q > 0.10:
            fails.append(f"q {q:.3g}")
        if uns["tests"][t]["test"]["trades"] < 40 or uns["tests"][t]["changed"] < 30:
            fails.append("too few trades")
        out[t] = dict(passed=not fails, fails=fails, p=p, q=round(float(q), 4))
    return out


if __name__ == "__main__":
    if CMD in ("recent", "unseen"):
        run(CMD)
    elif CMD == "decide":
        rec = json.loads((RESULTS / "recent.json").read_text())
        uns = json.loads((RESULTS / "unseen.json").read_text())
        d = gate(rec, uns)
        (RESULTS / "decision.json").write_text(json.dumps(d, indent=1))
        print(json.dumps(d, indent=1))
    else:
        raise SystemExit(__doc__)
