"""R153 study (PREREG.md): combinations that make the Donchian arms lose less, 1-3 lots, against today's rules.

    (stores as R152: R51_CLEAN=1 python research/hunt/r51/r51data.py build; r106study.py post-build for the post window)
    python research/hunt/r153_donchian_loseless/r153study.py design
    python research/hunt/r153_donchian_loseless/r153study.py final      # the ONE final read, with the twins
    R152_POST=1 python research/hunt/r153_donchian_loseless/r153study.py post
    python research/hunt/r153_donchian_loseless/r153study.py decide
    python research/hunt/r153_donchian_loseless/r153study.py picker     # second look on R151's app picks (after final)

Units, walk and B0 parity are R152's (r152study); pricing is r153engine; statistics, caps, twins and gates r153lib.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r152_donchian_2k"))
sys.path.insert(0, str(HERE))
import r152cfg as K2  # noqa: E402
import r152study as R  # noqa: E402
import r153engine as Z  # noqa: E402
import r153lib as L  # noqa: E402

RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
POST_FLAG = RESULTS / "POST_READ"
OUT = K2.OUT.parent / "r153"
ARMS = K2.ARMS
LOTS = K2.LOTS
COMBOS = {
    "C1": dict(stop=2000.0, lock=True, cap=False, am=False),
    "C2": dict(stop=None, lock=False, cap=True, am=False),
    "C3": dict(stop=2000.0, lock=True, cap=True, am=False),
    "C4": dict(stop=2000.0, lock=True, cap=False, am=True),
}
LABEL = {"B0": "today's rules", "C1": "-Rs 2,000 stop + lock +Rs 1,000 once +Rs 2,000",
         "C2": "stop new entries for the day at -Rs 4,000 closed", "C3": "C1 + the day cap",
         "C4": "entries until 10:00 + C1"}
TESTS = tuple((a, c, n) for a in ARMS for c in COMBOS for n in LOTS)     # 24
M_TOTAL = len(TESTS) + 866                                              # R152's m (its 18 + 848 earlier)
TWINS, SEED = 2000, 153


# ================================================================================================ trades
def priced(us: list, stop, lock: bool, lots: int) -> list:
    M = R.mods()
    ex = R.exits()
    return [Z.price(key[0], M["r51data"].load(key[0])[key[1]], cands, ex, stop, lock, lots) for key, cands, _, _ in us]


def am_filter(us: list, pr: list) -> tuple:
    """AM10 applied to units and their prices together (dropped candidates free the side, as a filter does)."""
    us2, pr2 = [], []
    for (key, cands, res, how), row in zip(us, pr):
        idx = [j for j, c in enumerate(cands) if L.am_keep(c)]
        if idx:
            us2.append((key, [cands[j] for j in idx], [res[j] for j in idx], how))
            pr2.append([row[j] for j in idx])
    return us2, pr2


def combo_trades(us: list, spec: dict, lots: int, cache: dict) -> tuple:
    """(trades, pre-skip trades with the same exits, (units, prices) walked) for one combination."""
    k = (spec["stop"], spec["lock"], lots)
    if k not in cache:
        cache[k] = priced(us, spec["stop"], spec["lock"], lots)
    pr = cache[k]
    u, p = am_filter(us, pr) if spec["am"] else (us, pr)
    pre = R.walk(u, p)
    tr = L.day_cap(pre) if spec["cap"] else pre
    return tr, pre, (u, p)


def by_period(tr: list, lo: date, hi: date) -> list:
    return [t for t in tr if lo <= t["day"] <= hi]


# ================================================================================================ twins (final)
def exit_twins(rule_tr: list, plain: dict, lots: int, n: int, rng) -> tuple:
    M = R.mods()
    ex = R.exits()
    fixed, rows = [], []
    for t in rule_tr:
        p = plain.get(L.tkey(t))
        if not R.changed(t, p):
            fixed.append((t["day"], float(t["net"])))
            continue
        D = M["r51data"].load(t["und"])[t["day"]]
        r = np.arange(int(t["er"]) + 1, int(p["xr"]), dtype=np.int64)
        rows.append((t["day"], Z.Y.forced_exit_nets(t["und"], D, t, ex, r, lots)))
    return L.exit_twin_loss_sums(fixed, rows, n, rng), len(rows)


def am_skip_twins(us: list, pr: list, k: int, n: int, rng) -> np.ndarray:
    """AM10's twin: drop k candidates at random (k = what the 10:00 cut drops), walk again with the same prices."""
    flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
    out = np.zeros(n)
    for x in range(n):
        gone = {flat[i] for i in rng.choice(len(flat), size=k, replace=False)}
        u2, p2 = [], []
        for g, ((key, cands, res, how), row) in enumerate(zip(us, pr)):
            idx = [j for j in range(len(cands)) if (g, j) not in gone]
            if idx:
                u2.append((key, [cands[j] for j in idx], [res[j] for j in idx], how))
                p2.append([row[j] for j in idx])
        out[x] = L.loss_sum_of((t["day"], t["net"]) for t in R.walk(u2, p2))
    return out


# ================================================================================================ phases
def evaluate(arm: str, phase: str, lo: date, hi: date) -> dict:
    us = R.units(arm, lo, hi)
    out = {"parity": R.parity(arm, phase, us)}
    pers = {"design": [("design", lo, hi), ("wf", K2.WF_START, hi)], "final": [("final", lo, hi)],
            "post": [("post", lo, hi)]}[phase]
    rng = np.random.default_rng(SEED)
    keep = {}
    for lots in LOTS:
        cache = {}
        pr0 = priced(us, None, False, lots)
        b0 = R.walk(us, pr0)
        plain = {(key[0], key[1], int(c["er"]), c["side"]): t
                 for (key, cands, _, _), row in zip(us, pr0) for c, t in zip(cands, row) if t is not None}
        out[f"B0@{lots}"] = {p: L.loss_stats(by_period(b0, a, b)) for p, a, b in pers}
        keep[("B0", lots)] = b0
        for cid, spec in COMBOS.items():
            tr, pre, (u, p) = combo_trades(us, spec, lots, cache)
            keep[(cid, lots)] = tr
            r = {p_: L.loss_stats(by_period(tr, a, b)) for p_, a, b in pers}
            r["changed"] = L.changed(tr, b0)
            r["removed_by_skip"] = len(pre) - len(tr) if spec["cap"] else sum(len(x[1]) for x in us) - sum(
                len(x[1]) for x in u)
            if phase == "final":
                act = L.loss_sum_of((t["day"], t["net"]) for t in tr)
                ps = {}
                if spec["stop"] is not None or spec["lock"]:
                    tw, nch = exit_twins(tr, plain, lots, TWINS, rng)
                    ps["exit"] = dict(L.twin_p(act, tw), sold_earlier=nch)
                if spec["cap"]:
                    ps["skip"] = L.twin_p(act, L.day_skip_twin_loss_sums(pre, tr, TWINS, rng))
                if spec["am"]:
                    base_pr = cache[(spec["stop"], spec["lock"], lots)]
                    k = sum(len(x[1]) for x in us) - sum(len(x[1]) for x in u)
                    ps["skip"] = L.twin_p(act, am_skip_twins(us, base_pr, k, TWINS, rng))
                r["twin"] = ps
                r["p"] = max(v["p"] for v in ps.values())
            out[f"{cid}@{lots}"] = r
            print(arm, phase, cid, lots, {p_: (r[p_]["trades"], r[p_]["net"], r[p_]["loss_sum"], r[p_]["max_dd"])
                                          for p_, _, _ in pers}, "B0", {p_: (out[f"B0@{lots}"][p_]["net"],
                                          out[f"B0@{lots}"][p_]["loss_sum"]) for p_, _, _ in pers},
                  r.get("p"), flush=True)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{arm}_{phase}.pkl").write_bytes(pickle.dumps(keep, protocol=pickle.HIGHEST_PROTOCOL))
    return out


def run(phase: str):
    M = R.mods()
    RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if FINAL_FLAG.exists() and not (RESULTS / "final_open").exists():
            raise SystemExit("R153 final already read once: " + FINAL_FLAG.read_text())
        if not FINAL_FLAG.exists():
            FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (RESULTS / "final_open").write_text("arms in progress\n")
        M["C"].open_final()
        lo, hi = K2.FINAL_START, K2.FINAL_END
    elif phase == "post":
        assert K2.POST, "the post window reads the stores built to 19 Oct (R152_POST=1)"
        assert (RESULTS / "final.json").exists(), "the post window is read only after the final"
        if POST_FLAG.exists() and not (RESULTS / "post_open").exists():
            raise SystemExit("R153 post window already read once: " + POST_FLAG.read_text())
        if not POST_FLAG.exists():
            POST_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (RESULTS / "post_open").write_text("arms in progress\n")
        M["C"].open_final()
        lo, hi = K2.POST_START, K2.POST_END
    else:
        lo, hi = K2.START, K2.DESIGN_END
    for arm in ARMS:
        done = RESULTS / f"{phase}_{arm}.json"
        if phase != "design" and done.exists():
            continue
        done.write_text(json.dumps(evaluate(arm, phase, lo, hi), indent=1, default=str))
    if all((RESULTS / f"{phase}_{a}.json").exists() for a in ARMS):
        (RESULTS / f"{phase}_open").unlink(missing_ok=True)
        (RESULTS / f"{phase}.json").write_text(json.dumps(
            {a: json.loads((RESULTS / f"{phase}_{a}.json").read_text()) for a in ARMS}, indent=1))


def decide() -> dict:
    d = {a: json.loads((RESULTS / f"design_{a}.json").read_text()) for a in ARMS}
    f = {a: json.loads((RESULTS / f"final_{a}.json").read_text()) for a in ARMS}
    ps = [f[a][f"{c}@{n}"]["p"] for a, c, n in TESTS]
    q_all = R.mods()["S"].bh(ps, M_TOTAL)
    q_own = R.mods()["S"].bh(ps)
    out = {}
    for (a, c, n), qa, qo in zip(TESTS, q_all, q_own):
        k, b = f"{c}@{n}", f"B0@{n}"
        rule = dict(design=d[a][k]["design"], wf=d[a][k]["wf"], final=f[a][k]["final"])
        base = dict(design=d[a][b]["design"], wf=d[a][b]["wf"], final=f[a][b]["final"])
        g = L.gates(rule, base, f[a][k]["p"], float(qa), f[a][k]["changed"])
        g.update(p=round(f[a][k]["p"], 4), q_all=round(float(qa), 4), q_r153=round(float(qo), 4))
        out[f"{a}:{k}"] = g
    out["_m"] = dict(r153=len(TESTS), prior=866, total=M_TOTAL)
    (RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


def picker() -> dict:
    """Second look (descriptive): the combinations on the trades R151's app pickers take, Apr - Oct 2026."""
    assert FINAL_FLAG.exists(), "the second look follows R153's one final read"
    import r152picker as P
    M = R.mods()
    M["C"].open_final()
    lo, hi = K2.FINAL_START, K2.FINAL_END
    out = {"_meta": dict(period=f"{lo} .. {hi}", note="second look; a skipped break still holds its side")}
    for arm in ARMS:
        us = R.units(arm, lo, hi)
        sc, thr = P.scores(arm, us)
        a = {}
        for lots in LOTS:
            cache = {}
            a[f"B0@{lots}"] = L.loss_stats([t for t in R.walk(us, priced(us, None, False, lots)) if P.kept(t, sc, thr)])
            for cid, spec in COMBOS.items():
                k = (spec["stop"], spec["lock"], lots)
                if k not in cache:
                    cache[k] = priced(us, spec["stop"], spec["lock"], lots)
                u, p = am_filter(us, cache[k]) if spec["am"] else (us, cache[k])
                tr = [t for t in R.walk(u, p) if P.kept(t, sc, thr)]       # skipped breaks hold their side
                a[f"{cid}@{lots}"] = L.loss_stats(L.day_cap(tr) if spec["cap"] else tr)
                print(arm, "picker", cid, lots, a[f"{cid}@{lots}"]["net"], a[f"{cid}@{lots}"]["loss_sum"], flush=True)
        out[arm] = a
    (RESULTS / "picker.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final", "post"):
        run(cmd)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    elif cmd == "picker":
        picker()
    else:
        raise SystemExit(__doc__)
