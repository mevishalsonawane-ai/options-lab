"""R64 study (PREREG.md): BEFORE vs AFTER (Boss's 30-60-100 lock and -10% stop) on the Donchian, OOV and Solo 2.0
paper arms. Research only.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R51's clean stores (Donchian, OOV)
    python research/hunt/r57/r57data.py build                  # R57's stores (Solo 2.0)
    python research/hunt/r64/r64study.py design                # design + walk-forward, every arm and variant
    python research/hunt/r64/r64study.py final                 # the ONE final read (refuses a second), twins, BH
    python research/hunt/r64/r64study.py report                # results/*.json -> results/report.md

Each arm group runs in its own process (`run r51 <phase>` / `run solo2 <phase>`): r57cfg re-points the shared R51
engine at R57's stores, so the two must never share an interpreter. Trades go to $R64_OUT (never git); results/ holds
small JSON and markdown only.
"""
from __future__ import annotations

import json
import os
import pickle
import subprocess
import sys
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r64cfg as R  # noqa: E402

GROUPS = {"r51": ("Donchian", "OOV"), "solo2": ("Solo2",)}


# ================================================================================================ statistics (pure)
def max_dd(daily) -> float:
    if not len(daily):
        return 0.0
    eq = np.concatenate([[0.0], np.cumsum(daily)])
    return float((np.maximum.accumulate(eq) - eq).max())


def stats(trades: list) -> dict:
    """PREREG section 6 for a list of trade dicts (net, day, why, peak, ret, und)."""
    n = len(trades)
    if not n:
        return dict(trades=0, days=0, net=0.0)
    net = np.array([t["net"] for t in trades], float)
    daily = defaultdict(float)
    for t in trades:
        daily[t["day"]] += t["net"]
    dv = np.array([daily[d] for d in sorted(daily)])
    w, lo = net[net > 0], net[net <= 0]
    top5 = float(np.sort(net)[::-1][:5].sum())
    tot = float(net.sum())
    why = defaultdict(int)
    for t in trades:
        why[t["why"]] += 1
    big = [t for t in trades if np.isfinite(t.get("peak", np.nan)) and t["peak"] >= R.BIG - 1e-9]
    kept = [t for t in big if t["ret"] >= R.BIG - 1e-9]
    per = defaultdict(lambda: [0, 0.0])
    for t in trades:
        per[t["und"]][0] += 1
        per[t["und"]][1] += t["net"]
    return dict(trades=n, days=len(dv), win=round(float((net > 0).mean()), 3), net=round(tot), per_trade=round(tot / n),
                avg_win=round(float(w.mean())) if len(w) else None, avg_loss=round(float(lo.mean())) if len(lo) else None,
                biggest_loss=round(float(net.min())), worst_day=round(float(dv.min())), max_dd=round(max_dd(dv)),
                green_days=round(float((dv > 0).mean()), 3), top5_share=round(top5 / tot, 3) if tot > 0 else None,
                ex_top5=round(tot - top5), stops=why.get("stop", 0), locks=why.get("lock", 0),
                targets=why.get("target", 0), time=why.get("time", 0) + why.get("missing", 0),
                squareoff=why.get("squareoff", 0), rule_exit=why.get("rule_exit", 0),
                big_movers=len(big), big_kept=len(kept), big_cut=len(big) - len(kept),
                big_net=round(sum(t["net"] for t in big)),
                per_index={u: dict(trades=a, net=round(b)) for u, (a, b) in sorted(per.items())})


def split(trades: list, lo: date, hi: date) -> list:
    return [t for t in trades if lo <= t["day"] <= hi]


def periods(trades: list, arm: str, phase: str) -> dict:
    """design / walk-forward (sum and quarters) for the design phase; final for the final phase."""
    if phase == "final":
        return {"final": stats(trades)}
    wf0 = R.SOLO_WF_START if arm == "Solo2" else R.WF_START
    out = {"design": stats(trades), "wf": stats(split(trades, wf0, R.DESIGN_END))}
    out["wf_quarters"] = [dict(lo=str(a), hi=str(b), **{k: v for k, v in stats(split(trades, a, b)).items()
                                                       if k in ("trades", "net", "win", "worst_day")})
                          for a, b in R.WF_QUARTERS if b >= wf0]
    return out


def paired_boot(before: list, after: list, n: int = R.BOOT, seed: int = R.SEED):
    """One-sided bootstrap over days of the AFTER - BEFORE net (same signal days): share of resampled mean
    differences <= 0, and the observed total difference. None when there are no days."""
    b, a = defaultdict(float), defaultdict(float)
    for t in before:
        b[t["day"]] += t["net"]
    for t in after:
        a[t["day"]] += t["net"]
    days = sorted(set(b) | set(a))
    if not days:
        return None, 0.0
    d = np.array([a[x] - b[x] for x in days])
    rng = np.random.default_rng(seed)
    m = d[rng.integers(0, len(d), size=(n, len(d)))].mean(axis=1)
    return round(float((m <= 0).mean()), 4), round(float(d.sum()))


def bh(p) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    if not n:
        return p
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out


def gates(final: dict, design: dict, wf: dict, p, q) -> list:
    fails = []
    if final.get("net", 0) <= 0:
        fails.append("final net <= 0")
    if p is None or p >= R.GATE_P:
        fails.append("p >= 0.05")
    if q is None or q > R.GATE_Q:
        fails.append("q > 0.10")
    if final.get("trades", 0) < R.GATE_MIN_TRADES:
        fails.append("fewer than 40 trades")
    if final.get("trades", 0) and final.get("ex_top5", 0) <= 0:
        fails.append("top-5 >= net")
    if design.get("net", 0) <= 0:
        fails.append("design net <= 0")
    if wf.get("net", 0) <= 0:
        fails.append("walk-forward net <= 0")
    return fails


def decide(arm_res: dict) -> dict:
    """PREREG section 5: the recommended variant (or BEFORE) and the risk trade-offs."""
    b = arm_res["B0"]
    rec, tradeoffs = None, []
    for v in R.VARIANTS[1:]:
        r = arm_res[v]
        better = all(r[k]["net"] >= b[k]["net"] for k in ("design", "wf", "final"))
        if r["verdict"] == "PASS" and better:
            if rec is None or r["final"]["net"] > arm_res[rec]["final"]["net"]:
                rec = v
        elif (r["final"].get("max_dd", 1e18) < b["final"].get("max_dd", 0)
              or r["final"].get("worst_day", -1e18) > b["final"].get("worst_day", 0)):
            tradeoffs.append(v)
    return dict(recommended=rec or "B0", tradeoffs=tradeoffs)


# ================================================================================================ group runners
def _exits(E, K, spec: dict):
    return E.Exits(stop=spec["stop"] if spec["stop"] is not None else -1.0,
                   target=spec["target"] if spec["target"] is not None else -1.0,
                   steps=tuple(spec["steps"]), tmax=spec["tmax"] if spec["tmax"] else -1, sq_row=K.row(*spec["sq"]))


def _save(name: str, obj):
    R.OUT.mkdir(parents=True, exist_ok=True)
    with open(R.OUT / f"{name}.pkl", "wb") as f:
        pickle.dump(obj, f, protocol=pickle.HIGHEST_PROTOCOL)


def _load(name: str):
    with open(R.OUT / f"{name}.pkl", "rb") as f:
        return pickle.load(f)


def _strip(trades: list) -> list:
    keep = ("und", "day", "side", "strike", "er", "xr", "E", "X", "rE", "rX", "qty", "why", "net", "peak", "ret",
            "setup", "money")
    return [{k: t[k] for k in keep if k in t} for t in trades]


def run_r51(phase: str) -> dict:
    """Donchian and OOV on R51's clean stores."""
    os.environ["R51_CLEAN"] = "1"
    sys.path.insert(0, str(HERE.parent / "r51"))
    import r51cfg as K
    import common as C
    import engine as E
    import fam_r44
    import r51data
    import r64arms as A
    import r64engine as G

    assert K.CLEAN, "R64 prices Donchian / OOV on R51's Amendment A clean stores"
    if phase == "final":
        C.open_final()
        lo, hi = R.FINAL_START, K.END
    else:
        lo, hi = R.START, R.DESIGN_END
    out = {}
    for arm in GROUPS["r51"]:
        out[arm] = {}
        for v in R.VARIANTS:
            ex = _exits(E, K, R.exit_spec(R.OWN[arm], v))
            tr = A.donchian_trades(C, E, K, fam_r44, r51data, G, ex, lo, hi) if arm == "Donchian" else \
                A.oov_trades(C, E, K, r51data, G, ex, lo, hi)
            _save(f"{arm}_{v}_{phase}", _strip(tr))
            res = periods(tr, arm, phase)
            if phase == "final":
                rng = np.random.default_rng(R.SEED)
                tw = A.donchian_twins(E, K, r51data, G, tr, ex, R.TWINS, rng) if arm == "Donchian" else \
                    A.r57_twins(E, K, r51data.load, G, tr, lambda t: (ex, None), R.TWINS, rng)
                act = sum(t["net"] for t in tr)
                res["p"] = round((1 + int((tw >= act).sum())) / (R.TWINS + 1), 4)
                res["twin_mean"] = round(float(tw.mean()))
            out[arm][v] = res
            print(arm, v, phase, json.dumps({k: res[k].get("net") if isinstance(res[k], dict) else res[k]
                                             for k in res if k != "wf_quarters"}), flush=True)
    return out


def run_solo2(phase: str) -> dict:
    """Solo 2.0: R57's A2 picks (walk-forward / final monthly refits) re-priced under each variant."""
    sys.path.insert(0, str(HERE.parent / "r57"))
    import r57cfg as K57
    import r57data
    import r57setups as S
    import r57study as ST
    import engine as E
    import r64arms as A
    import r64engine as G

    K = K57._K51
    for u in K57.UNDS:
        r57data.load(u)
    a0, _ = ST.load_or_build("design_data", K57.START, K57.DESIGN_END)
    if phase == "final":
        S.open_final()
        a1, _ = ST.load_or_build("final_data", K57.FINAL_START, K57.END)
        picks, _, _ = ST.run_a(ST.VARIANTS["A2"], a0 + a1, K57.FINAL_MONTHS, ST.calendar())
    else:
        cal = [d for d in ST.calendar() if d <= K57.DESIGN_END]
        picks, _, _ = ST.run_a(ST.VARIANTS["A2"], a0, K57.WF_MONTHS, cal)
    picks = sorted(picks, key=lambda t: (t["day"], t["er"]))
    ref = dict(trades=len(picks), net=round(sum(t["net"] for t in picks)))

    def exits_of(setup, v):
        return _exits(E, K, R.exit_spec(R.OWN[setup], v))

    out = {"Solo2": {}}
    for v in R.VARIANTS:
        tr = A.solo2_reprice(E, K, r57data.load, S, G, picks, lambda s: exits_of(s, v))
        _save(f"Solo2_{v}_{phase}", _strip(tr))
        res = periods(tr, "Solo2", phase)
        res["r57_reference"] = ref
        if phase == "final":
            rng = np.random.default_rng(R.SEED)
            tw = A.r57_twins(E, K, r57data.load, G, tr,
                             lambda t: (exits_of(t["setup"], v), S.IDX_EXIT.get(t["setup"])), R.TWINS, rng,
                             forced_fn=S.forced_row)
            act = sum(t["net"] for t in tr)
            res["p"] = round((1 + int((tw >= act).sum())) / (R.TWINS + 1), 4)
            res["twin_mean"] = round(float(tw.mean()))
        out["Solo2"][v] = res
        print("Solo2", v, phase, ref, json.dumps({k: res[k].get("net") if isinstance(res[k], dict) else res[k]
                                                   for k in res if k != "wf_quarters"}), flush=True)
    return out


# ================================================================================================ steps
def run(group: str, phase: str):
    path = R.RESULTS / f"{phase}_{group}.json"
    if phase == "final":
        if not R.FINAL_FLAG.exists():
            raise SystemExit("R64: the final read is opened only by `r64study.py final`")
        if path.exists():
            raise SystemExit(f"R64: {path.name} exists - the final test is read once")
    res = run_r51(phase) if group == "r51" else run_solo2(phase)
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(res, indent=1, default=str))


def _sub(group: str, phase: str):
    subprocess.run([sys.executable, str(Path(__file__).resolve()), "run", group, phase], check=True)


def design():
    for g in GROUPS:
        _sub(g, "design")


def final():
    if R.FINAL_FLAG.exists():
        raise SystemExit("R64: the final test was already read (results/FINAL_READ). One read only.")
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    R.FINAL_FLAG.write_text(f"final test read on {datetime.now(timezone.utc).isoformat()}\n")
    for g in GROUPS:
        _sub(g, "final")
    summarise()


def _load_trades(arm: str, v: str, phase: str) -> list:
    try:
        return _load(f"{arm}_{v}_{phase}")
    except FileNotFoundError:
        return []


def summarise() -> dict:
    """Merge the group results, add the three-together rows, BH over the 15 tests, gates, the decision and the
    paired bootstrap. Writes results/summary.json."""
    des, fin = {}, {}
    for g in GROUPS:
        des.update(json.loads((R.RESULTS / f"design_{g}.json").read_text()))
        fin.update(json.loads((R.RESULTS / f"final_{g}.json").read_text()))
    out = {"arms": {}, "together": {}}
    keys = []
    for arm in R.ARMS:
        out["arms"][arm] = {}
        for v in R.VARIANTS:
            d, f = des[arm][v], fin[arm][v]
            out["arms"][arm][v] = dict(design=d["design"], wf=d["wf"], wf_quarters=d["wf_quarters"],
                                       final=f["final"], p=f.get("p"), twin_mean=f.get("twin_mean"))
            keys.append((arm, v))
    qs = bh([out["arms"][a][v]["p"] if out["arms"][a][v]["p"] is not None else 1.0 for a, v in keys])
    for (a, v), q in zip(keys, qs):
        r = out["arms"][a][v]
        r["q"] = round(float(q), 4)
        fails = gates(r["final"], r["design"], r["wf"], r["p"], r["q"])
        r["verdict"] = "PASS" if not fails else "FAIL: " + "; ".join(fails)
    for arm in R.ARMS:
        for v in R.VARIANTS[1:]:
            boots = {}
            for ph in ("design", "final"):
                boots[ph] = paired_boot(_load_trades(arm, "B0", ph), _load_trades(arm, v, ph))
            out["arms"][arm][v]["paired_vs_before"] = {ph: dict(p=b[0], diff=b[1]) for ph, b in boots.items()}
        out["arms"][arm]["decision"] = decide(out["arms"][arm])
    for v in R.VARIANTS:
        dt = sum((_load_trades(a, v, "design") for a in R.ARMS), [])
        ft = sum((_load_trades(a, v, "final") for a in R.ARMS), [])
        out["together"][v] = dict(design=stats(dt), wf=stats(split(dt, R.WF_START, R.DESIGN_END)), final=stats(ft))
        if v != "B0":
            b0d = sum((_load_trades(a, "B0", "design") for a in R.ARMS), [])
            b0f = sum((_load_trades(a, "B0", "final") for a in R.ARMS), [])
            out["together"][v]["paired_vs_before"] = {
                ph: dict(zip(("p", "diff"), paired_boot(b, x))) for ph, b, x in (("design", b0d, dt), ("final", b0f, ft))}
    boss = {}
    for v in R.VARIANTS:
        ft = [t for a in R.ARMS for t in _load_trades(a, v, "final") if t["day"] == R.BOSS_DAY]
        boss[v] = stats(ft) if ft else None
    out["boss_day"] = dict(day=str(R.BOSS_DAY), in_lake=any(boss.values()), variants=boss)
    (R.RESULTS / "summary.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "run" and len(sys.argv) == 4 and sys.argv[2] in GROUPS and sys.argv[3] in ("design", "final"):
        run(sys.argv[2], sys.argv[3])
    elif cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "summary":
        summarise()
    elif cmd == "report":
        import r64report
        r64report.main()
    else:
        print(__doc__)
