"""R100 study (PREREG.md): give-back protection for Liquidity 15+5. Seven fixed rules (G1-G7) that arm only after a
big open profit, each against Liquidity's own exits (B0), on the same trades as R76 / R81. Research only. 22 Oct 2026.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build BANKNIFTY FINNIFTY MIDCPNIFTY   # R51's clean stores
    python research/hunt/r100_liquidity_giveback/r100study.py design
    python research/hunt/r100_liquidity_giveback/r100study.py final        # the ONE final read (refuses a second)
    python research/hunt/r100_liquidity_giveback/r100study.py summary

Trades go to $R100_OUT (default ~/.cache/iraalgo/r100; never git); results/ holds small JSON only.
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
HUNT = HERE.parent
for _d in ("r51", "r64", "r65", "r70", "r76"):
    sys.path.insert(0, str(HUNT / _d))
sys.path.insert(0, str(HERE))
import r64cfg as R64  # noqa: E402
import r64study as P  # noqa: E402  (stats, periods, bh, paired_boot)
import r65study as Q  # noqa: E402  (exit_twin_totals, p_of, forced_rows)

OUT = Path(os.environ.get("R100_OUT", R64.CACHE_ROOT / "r100")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
NAN = float("nan")

# PREREG section 3: id -> (keep rows, index-breakeven units of the index stop). Keep row: (from_minute, start, keep, kind)
VARIANTS = {
    "G1": ([(0, 0.20, NAN, 0)], None),
    "G2": ([(0, 0.15, NAN, 0)], None),
    "G3": ([(0, 0.15, NAN, 0), (0, 0.30, 0.40, 0), (0, 0.50, 0.60, 0)], None),
    "G4": ([(0, 0.20, 0.50, 0)], None),
    "G5": ([], 2.0),
    "G6": ([(0, 0.20, 0.25, 0), (30, 0.20, 0.50, 0), (60, 0.20, 0.75, 0)], None),
    "G7": ([(0, 3000.0, 0.50, 1)], None),
}
NAMES = {"B0": "own exits (today)", "G1": "breakeven once +20%", "G2": "breakeven once +15%",
         "G3": "step: +15% BE, +30% keep 40%, +50% keep 60%", "G4": "keep 50% of the best gain once +20%",
         "G5": "index breakeven once 2 index stops in favour", "G6": "once +20% keep 25% / 50% after 30 min / 75% after 60",
         "G7": "once +Rs 3,000 a lot keep half the best profit", "R19C": "Boss's Rs 500 trail switch (reference)"}
TESTS = tuple(VARIANTS)
# Amendments A1 / A2 (PREREG.md, 22 Oct, Boss's questions after the results; written before any of their numbers)
AMEND = {
    "G8": ([(0, 0.10, 0.50, 0)], None),                         # keep half the best gain once +10%
    "G9": ([(0, 0.10, 0.50, 0), (0, 0.0, 0.15, 2)], None),       # G8 + the stop trailing 15% under the best price
    "G10": ([(0, 0.0, 0.15, 2)], None),                          # the 15% trailing stop alone
}
NAMES.update({"G8": "keep 50% of the best gain once +10% (Boss, A1)", "G9": "G8 + a 15% trailing stop (Boss, A2)",
              "G10": "a 15% trailing stop alone (A2)"})
AMEND_FLAG = RESULTS / "A1_FINAL_READ"
REFS = ("R19C",)
ALL = ("B0",) + TESTS + REFS
TWINS, SEED, BOOT = 500, 100, 2000
GATE_Q, MIN_CHANGED, TOP = 0.10, 30, 5
GIVEBACK = 0.20                     # a give-back trade: its option was >= +20% while held and it closed at a loss
PHASES = ("design", "wf", "final")
B0_EXPECT = {"design": (881, 257249), "final": (357, 11155)}       # R76 / R81's B0, to the rupee


# ================================================================================================ pure
def rows_of(v: str) -> np.ndarray:
    r = VARIANTS[v][0] if v in VARIANTS else AMEND[v][0] if v in AMEND else []
    return np.array(r, dtype=np.float64).reshape(-1, 4)


def changed(b0: list, v: list) -> list:
    assert len(b0) == len(v)
    return [i for i, (x, y) in enumerate(zip(b0, v)) if abs(y["net"] - x["net"]) > 1e-6]


def account(b0: list, v: list) -> dict:
    """Trade by trade against B0: changed, Rs saved / given up, best and worst example. Pure."""
    d = [(v[i]["net"] - b0[i]["net"], b0[i], v[i]) for i in changed(b0, v)]
    worse = [z for z in d if z[0] < 0]
    better = [z for z in d if z[0] > 0]

    def ex(z):
        if z is None:
            return None
        _, x, y = z
        return dict(und=x["und"], day=str(x["day"]), side=x["side"], strike=x["strike"], v_net=round(y["net"]),
                    b0_net=round(x["net"]), v_why=y["why"], b0_why=x["why"], b0_held_peak=round(x["held_peak"], 3))
    return dict(changed=len(d), n_worse=len(worse), given_up=round(-sum(z[0] for z in worse)), n_better=len(better),
                saved=round(sum(z[0] for z in better)), net_effect=round(sum(z[0] for z in d)),
                worst_example=ex(min(worse, key=lambda z: z[0]) if worse else None),
                best_example=ex(max(better, key=lambda z: z[0]) if better else None))


def giveback(b0: list, v: list, thr: float = GIVEBACK) -> dict:
    """The give-back trades (B0 held peak >= thr, B0 net < 0): count, B0's loss on them, the variant's net on them. Pure."""
    idx = [i for i, t in enumerate(b0) if t["held_peak"] >= thr - 1e-12 and t["net"] < 0]
    return dict(n=len(idx), b0=round(sum(b0[i]["net"] for i in idx)), variant=round(sum(v[i]["net"] for i in idx)),
                kept=round(sum(v[i]["net"] - b0[i]["net"] for i in idx)))


def top_removed(b0: list, v: list, k: int = TOP) -> float:
    """The variant's gain over B0 without the k trades where it gained most (PREREG gate 3). Pure."""
    d = sorted((y["net"] - x["net"] for x, y in zip(b0, v)), reverse=True)
    return float(sum(d[k:]))


def by_index(trades: list) -> dict:
    out = defaultdict(lambda: [0, 0.0])
    for t in trades:
        out[t["und"]][0] += 1
        out[t["und"]][1] += t["net"]
    return {u: dict(trades=n, net=round(x)) for u, (n, x) in sorted(out.items())}


def wf_select(T: dict, quarters, start) -> dict:
    """PREREG section 5: each walk-forward quarter takes the choice (B0 or a test) with the best net on all design days
    before it; the stitched total against B0. T: {id: trades} for the design phase. Pure."""
    picks, tot, b0 = [], 0.0, 0.0
    for lo, hi in quarters:
        past = {v: sum(t["net"] for t in T[v] if start <= t["day"] < lo) for v in ("B0",) + TESTS}
        best = max(past, key=lambda v: (past[v], v == "B0"))
        q = sum(t["net"] for t in T[best] if lo <= t["day"] <= hi)
        qb = sum(t["net"] for t in T["B0"] if lo <= t["day"] <= hi)
        picks.append(dict(lo=str(lo), hi=str(hi), pick=best, net=round(q), b0=round(qb)))
        tot += q
        b0 += qb
    return dict(picks=picks, net=round(tot), b0=round(b0), diff=round(tot - b0))


def decide(r: dict, b: dict) -> dict:
    """PREREG section 4. r / b: the variant's and B0's summary rows."""
    g1 = all(r[ph]["net"] >= b[ph]["net"] for ph in PHASES)
    g2 = (r.get("q_final") is not None and r["q_final"] <= GATE_Q and r.get("q_design") is not None
          and r["q_design"] <= GATE_Q)
    g3 = r["top_removed_design"] >= 0
    g4 = r["changed"]["design"] >= MIN_CHANGED
    return dict(passed=bool(g1 and g2 and g3 and g4), harmless=bool(g1),
                gates=dict(never_less=g1, twins_bh=bool(g2), not_top5=g3, sample=g4))


# ================================================================================================ helpers
def _save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name):
    p = OUT / f"{name}.pkl"
    return pickle.loads(p.read_bytes()) if p.exists() else []


def _price_all(plain, ex_b0, v, load, X, E, IDX_STOP):
    out = []
    if v == "R19C":
        ex = E.Exits(**dict(_fields(ex_b0), lock_trig=1000.0, lock_keep=0.0, lock_trail=500.0))
        rows, units = X.NOKEEP, None
    else:
        ex, rows = ex_b0, (rows_of(v) if v != "B0" else X.NOKEEP)
        units = VARIANTS[v][1] if v in VARIANTS else None
    for t in plain:
        u = None if units is None else units * IDX_STOP[t["und"]]
        r = X.price_trade(t["und"], load(t["und"])[t["day"]], t, ex, rows, u)
        if r is not None:
            r.update({k: t[k] for k in ("e0", "fx0", "sgn", "close", "book") if k in t})
            out.append(r)
    return out


def _fields(ex) -> dict:
    return dict(stop=ex.stop, target=ex.target, lock_trig=ex.lock_trig, lock_keep=ex.lock_keep,
                lock_trail=ex.lock_trail, steps=tuple(ex.steps), tmax=ex.tmax, need=ex.need,
                close_stop=ex.close_stop, sq_row=ex.sq_row)


def run_phase(phase: str) -> dict:
    import r76study as S76
    plain, ex_b0 = S76._plain(phase)
    import engine as E
    import fam_liq
    import r51data
    import r64engine as G
    import r70study as R70
    import r100engine as X

    load = r51data.load
    ref = R70._price(G, load, plain, ex_b0)                    # R64's own B0
    tr = {v: _price_all(plain, ex_b0, v, load, X, E, fam_liq.IDX_STOP) for v in ALL}
    assert len(tr["B0"]) == len(ref) and all(abs(a["net"] - b["net"]) < 1e-6 for a, b in zip(tr["B0"], ref)), \
        "R100's engine does not reproduce R64's B0 trade by trade"
    n_exp, net_exp = B0_EXPECT[phase]
    assert len(ref) == n_exp and round(sum(t["net"] for t in ref)) == net_exp, \
        f"B0 {len(ref)} trades Rs {sum(t['net'] for t in ref):.0f} is not R76's {n_exp} / {net_exp}"
    for v in ALL:
        assert len(tr[v]) == len(tr["B0"]) and all(a["er"] == b["er"] for a, b in zip(tr[v], tr["B0"]))
    out = {"plain_trades": len(plain), "res": {}}
    rng = np.random.default_rng(SEED)
    for v in ALL:
        _save(f"{v}_{phase}", tr[v])
        res = P.periods(tr[v], "Liquidity", phase)
        res["by_index"] = by_index(tr[v])
        if v != "B0":
            k = len(changed(tr["B0"], tr[v]))
            res["changed"] = k
            if v in TESTS:
                def nf(t, rows_):
                    D = load(t["und"])[t["day"]]
                    m = len(rows_)
                    return G.twin_nets(t["und"], D, [t["side"]] * m, [t["strike"]] * m, [t["er"]] * m, ex_b0,
                                       Q.forced_rows(rows_, t["fx0"]))
                tw = Q.exit_twin_totals(tr["B0"], k, TWINS, rng, nf)
                res["exit_p"] = Q.p_of(tw, sum(t["net"] for t in tr[v]))
                res["twin_mean"] = round(float(tw.mean()))
        out["res"][v] = res
    key = "final" if phase == "final" else "design"
    print(phase, {v: round(out["res"][v][key]["net"]) for v in ALL}, flush=True)
    return out


# ================================================================================================ steps
def run(phase: str):
    path = RESULTS / f"{phase}.json"
    if phase == "final":
        if not FINAL_FLAG.exists():
            raise SystemExit("R100: the final read is opened only by `r100study.py final`")
        if path.exists():
            raise SystemExit(f"R100: {path.name} exists - the final period is read once")
    res = run_phase(phase)
    RESULTS.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(res, indent=1, default=str))


def _sub(phase):
    subprocess.run([sys.executable, str(Path(__file__).resolve()), "run", phase], check=True)


def design():
    _sub("design")


def final():
    if FINAL_FLAG.exists():
        raise SystemExit("R100: the final period was already read (results/FINAL_READ). One read only.")
    if not (RESULTS / "design.json").exists():
        raise SystemExit("R100: run and commit the design phase first")
    RESULTS.mkdir(parents=True, exist_ok=True)
    FINAL_FLAG.write_text(f"final period read on {datetime.now(timezone.utc).isoformat()}\n")
    _sub("final")
    summarise()


def summarise() -> dict:
    d, f = (json.loads((RESULTS / f"{ph}.json").read_text()) for ph in ("design", "final"))
    T = {v: {ph: _load(f"{v}_{ph}") for ph in ("design", "final")} for v in ALL}
    out = {"names": NAMES, "res": {}}
    for v in ALL:
        a, b = d["res"][v], f["res"][v]
        A = out["res"][v] = dict(design=a["design"], wf=a["wf"], wf_quarters=a["wf_quarters"], final=b["final"],
                                 by_index=dict(design=a["by_index"], final=b["by_index"]))
        A["giveback"] = {ph: giveback(T["B0"][ph], T[v][ph]) for ph in ("design", "final")}
        if v != "B0":
            A["changed"] = dict(design=a["changed"], final=b["changed"])
            A["account"] = {ph: account(T["B0"][ph], T[v][ph]) for ph in ("design", "final")}
            A["paired"] = {ph: dict(zip(("p", "diff"), P.paired_boot(T["B0"][ph], T[v][ph], BOOT, SEED)))
                           for ph in ("design", "final")}
            A["top_removed_design"] = round(top_removed(T["B0"]["design"], T[v]["design"]))
            A["top_removed_final"] = round(top_removed(T["B0"]["final"], T[v]["final"]))
            if v in TESTS:
                A["p_design"], A["p_final"] = a["exit_p"], b["exit_p"]
                A["twin_mean"] = dict(design=a["twin_mean"], final=b["twin_mean"])
    for ph in ("design", "final"):
        qs = P.bh([out["res"][v][f"p_{ph}"] for v in TESTS])
        for v, q in zip(TESTS, qs):
            out["res"][v][f"q_{ph}"] = round(float(q), 4)
    for v in TESTS + REFS:
        if v in REFS:
            out["res"][v]["q_design"] = out["res"][v]["q_final"] = None
        out["res"][v]["decision"] = decide(out["res"][v], out["res"]["B0"])
    out["wf_select"] = wf_select({v: T[v]["design"] for v in ("B0",) + TESTS}, R64.WF_QUARTERS, R64.START)
    out["passed"] = [v for v in TESTS if out["res"][v]["decision"]["passed"]]
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "summary.json").write_text(json.dumps(out, indent=1, default=str))
    return out


# ================================================================================================ amendments A1 / A2
def amend_phase(phase: str) -> dict:
    """G8-G10 (PREREG amendments A1 / A2) priced from the saved B0 trades of [phase] (the same trades, entry and index-exit
    rows), with their own random early-exit twins."""
    os.environ["R51_CLEAN"] = "1"
    sys.path.insert(0, str(HUNT / "r51"))
    import r51cfg as K
    import fam_liq
    import r51data
    import r64engine as G
    import r100engine as X

    assert K.CLEAN
    b0 = _load(f"B0_{phase}")
    n_exp, net_exp = B0_EXPECT[phase]
    assert len(b0) == n_exp and round(sum(t["net"] for t in b0)) == net_exp, "the saved B0 trades are not R100's"
    load, ex_b0 = r51data.load, fam_liq.EX
    rng = np.random.default_rng(SEED + 1)
    out = {}
    for v in AMEND:
        tr = []
        for t in b0:
            r = X.price_trade(t["und"], load(t["und"])[t["day"]], t, ex_b0, rows_of(v), None)
            assert r is not None and r["er"] == t["er"]
            r.update({k: t[k] for k in ("e0", "fx0", "sgn", "close", "book") if k in t})
            tr.append(r)
        _save(f"{v}_{phase}", tr)
        res = P.periods(tr, "Liquidity", phase)
        res["by_index"] = by_index(tr)
        k = len(changed(b0, tr))
        res["changed"] = k

        def nf(t, rows_):
            D = load(t["und"])[t["day"]]
            m = len(rows_)
            return G.twin_nets(t["und"], D, [t["side"]] * m, [t["strike"]] * m, [t["er"]] * m, ex_b0,
                               Q.forced_rows(rows_, t["fx0"]))
        tw = Q.exit_twin_totals(b0, k, TWINS, rng, nf)
        res["exit_p"] = Q.p_of(tw, sum(t["net"] for t in tr))
        res["twin_mean"] = round(float(tw.mean()))
        out[v] = res
    key = "final" if phase == "final" else "design"
    print("amend", phase, {v: round(out[v][key]["net"]) for v in AMEND}, flush=True)
    return out


def amend(phase: str):
    path = RESULTS / f"amend_{phase}.json"
    if phase == "final":
        if AMEND_FLAG.exists():
            raise SystemExit("R100 A1: the final period was already read for G8-G10 (results/A1_FINAL_READ). One read only.")
        if not (RESULTS / "amend_design.json").exists():
            raise SystemExit("R100 A1: run and commit the amendment's design phase first")
        AMEND_FLAG.write_text(f"A1 / A2 final period read on {datetime.now(timezone.utc).isoformat()}\n")
    path.write_text(json.dumps(amend_phase(phase), indent=1, default=str))
    if phase == "final":
        amend_summary()


def amend_summary() -> dict:
    """G8-G10 summarised as G1-G7 (summary.json), BH over all 10 tests in each period (G1-G7's verdicts not re-opened)."""
    base = json.loads((RESULTS / "summary.json").read_text())
    d, f = (json.loads((RESULTS / f"amend_{ph}.json").read_text()) for ph in ("design", "final"))
    T = {v: {ph: _load(f"{v}_{ph}") for ph in ("design", "final")} for v in ("B0",) + tuple(AMEND)}
    out = {"names": {v: NAMES[v] for v in AMEND}, "res": {}}
    for v in AMEND:
        a, b = d[v], f[v]
        A = out["res"][v] = dict(design=a["design"], wf=a["wf"], wf_quarters=a["wf_quarters"], final=b["final"],
                                 by_index=dict(design=a["by_index"], final=b["by_index"]),
                                 changed=dict(design=a["changed"], final=b["changed"]),
                                 p_design=a["exit_p"], p_final=b["exit_p"],
                                 twin_mean=dict(design=a["twin_mean"], final=b["twin_mean"]))
        A["giveback"] = {ph: giveback(T["B0"][ph], T[v][ph]) for ph in ("design", "final")}
        A["account"] = {ph: account(T["B0"][ph], T[v][ph]) for ph in ("design", "final")}
        A["paired"] = {ph: dict(zip(("p", "diff"), P.paired_boot(T["B0"][ph], T[v][ph], BOOT, SEED)))
                       for ph in ("design", "final")}
        A["top_removed_design"] = round(top_removed(T["B0"]["design"], T[v]["design"]))
        A["top_removed_final"] = round(top_removed(T["B0"]["final"], T[v]["final"]))
    ids = TESTS + tuple(AMEND)
    for ph in ("design", "final"):
        ps = [base["res"][v][f"p_{ph}"] if v in TESTS else out["res"][v][f"p_{ph}"] for v in ids]
        for v, q in zip(ids, P.bh(ps)):
            if v in AMEND:
                out["res"][v][f"q_{ph}"] = round(float(q), 4)
    for v in AMEND:
        out["res"][v]["decision"] = decide(out["res"][v], base["res"]["B0"])
    out["passed"] = [v for v in AMEND if out["res"][v]["decision"]["passed"]]
    (RESULTS / "amend_A1.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "run" and len(sys.argv) == 3 and sys.argv[2] in ("design", "final"):
        run(sys.argv[2])
    elif cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "summary":
        summarise()
    elif cmd == "amend" and len(sys.argv) == 3 and sys.argv[2] in ("design", "final"):
        amend(sys.argv[2])
    else:
        print(__doc__)
