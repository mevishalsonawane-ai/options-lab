"""R79 study (PREREG.md): direction filters on Liquidity 15+5. Boss, 18 Oct: "should we use Supertrend or wait for 10
candles for getting accurate direction? Or like Breadth Donchian, check all the other indices at once?" Research only.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build BANKNIFTY FINNIFTY MIDCPNIFTY   # R51's clean stores
    python research/hunt/r79/r79study.py design
    python research/hunt/r79/r79study.py final                 # the ONE final read (refuses a second)
    python research/hunt/r79/r79study.py summary

Each variant's trades are made afresh through fam_liq's own loop (same signals, same exits, same one-position-per-book
walk), with the filter applied to each signal before the walk, so a skipped signal frees the book's slot. Every signal
and every filter reads index bars only; the 1-ITM option is bought. Trades go to $R79_OUT (never git); results/ holds
small JSON only.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
for _d in ("r64", "r70"):
    sys.path.insert(0, str(HERE.parent / _d))
sys.path.insert(0, str(HERE))
import r64cfg as R64  # noqa: E402
import r64study as P  # noqa: E402  (stats, periods, paired_boot, bh)
import r70study as R70  # noqa: E402  (LIQ_BOOKS, LIQ_IDX_STOP_MIDCP)
import r79rules as RU  # noqa: E402

OUT = Path(os.environ.get("R79_OUT", R64.CACHE_ROOT / "r79")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
TESTS = RU.VARIANTS
ALL = ("B0",) + TESTS
TWINS, SEED, BOOT = 500, 79, 2000
GATE_Q, GATE_SKIP = 0.10, 0.10
R76_B0 = {"design": (881, 257249), "final": (357, 11155)}       # R76 results/summary.json, B0
WIDTH = {"W10a": 1, "W10b": 5}
NEED = {"X1": 2, "X2": 3}


# ================================================================================================ pure
def sig_key(t) -> tuple:
    """A signal's identity: index, day, book and the signal's own entry row (before any wait)."""
    return t["und"], t["day"], t["book"], t["sig_er"]


def filter_signal(v: str, c: dict, idx: np.ndarray, ctx: dict) -> dict | None:
    """PREREG section 2: the candidate `c` (a fam_liq signal dict with fx, book; er = the signal's entry row) under
    variant `v`, or None if skipped. ctx: und, day, step, last (the entry-window end row), t5 {und: Trend 5-min} and
    t15 (the traded index's 15-minute Trend). Waits return a copy with the later entry row and the 1-ITM strike at the
    10th candle's close."""
    if v == "B0":
        return c
    und, d, er, sgn = ctx["und"], ctx["day"], int(c["er"]), int(c["sgn"])
    if v == "S1":
        return c if RU.trend_agrees(ctx["t5"][und], d, er, sgn) else None
    if v == "S2":
        return c if RU.trend_agrees(ctx["t15"], d, er, sgn) else None
    if v in NEED:
        return c if RU.breadth_count(ctx["t5"], und, d, er, sgn) >= NEED[v] else None
    if v in WIDTH:
        w = RU.wait_entry(idx, er, sgn, float(c["close"]), WIDTH[v])
        if w is None:
            return None
        ne, cl = w
        if ne > ctx["last"]:
            return None                                   # past the arm's entry window
        fx = int(c.get("fx", -1))
        if 0 <= fx <= ne:
            return None                                   # Liquidity's index exit came during the wait
        step = ctx["step"]
        return dict(c, er=ne, strike=float(ctx["atm"](cl, step) - sgn * step), waited_from=er)
    raise ValueError(v)


def skip_compare(base: list, var: list) -> dict:
    """What the variant skipped (B0 trades whose signal it did not take) and what it took that B0 did not (matched on
    the signal: index, day, book, signal row). Pure."""
    kb = {sig_key(t): t for t in base}
    kv = {sig_key(t): t for t in var}
    skipped = [t for k, t in kb.items() if k not in kv]
    extra = [t for k, t in kv.items() if k not in kb]
    los = [t for t in skipped if t["net"] <= 0]
    both = [k for k in kb if k in kv]
    return dict(skipped=len(skipped), skipped_net=round(sum(t["net"] for t in skipped)),
                losers_avoided=len(los), losers_avoided_rs=round(-sum(t["net"] for t in los)),
                winners_missed=sum(1 for t in skipped if t["net"] > 0),
                winners_missed_rs=round(sum(t["net"] for t in skipped if t["net"] > 0)),
                extra=len(extra), extra_net=round(sum(t["net"] for t in extra)),
                kept=len(both), kept_diff=round(sum(kv[k]["net"] - kb[k]["net"] for k in both)))


def skip_twins(base_nets, n_var: int, n: int, rng) -> np.ndarray:
    """hunt/r73/r73study.py `skip_twins`: each twin drops k = (B0 trades - variant trades) B0 trades at random (k <= 0:
    none). Returns (n,) totals. Pure given rng."""
    base_nets = np.asarray(base_nets, float)
    k = max(0, len(base_nets) - n_var)
    tot = np.full(n, base_nets.sum())
    if k == 0 or not len(base_nets):
        return tot
    for j in range(n):
        tot[j] -= base_nets[rng.choice(len(base_nets), size=k, replace=False)].sum()
    return tot


def p_of(tw, actual: float) -> float:
    """R51's convention: (1 + twins >= actual) / (N + 1)."""
    return round((1 + int((np.asarray(tw) >= actual).sum())) / (len(tw) + 1), 4)


def decide(b0: dict, v: dict) -> dict:
    """PREREG section 3: PASS only if net beats B0 in walk-forward AND final, BH q < 0.10 and random-skip p < 0.10."""
    fails = []
    if not v["wf"]["net"] > b0["wf"]["net"]:
        fails.append("walk-forward net not above B0")
    if not v["final"]["net"] > b0["final"]["net"]:
        fails.append("final net not above B0")
    if v.get("q") is None or not v["q"] < GATE_Q:
        fails.append(f"BH q {v.get('q')} not < {GATE_Q}")
    if v.get("skip_p") is None or not v["skip_p"] < GATE_SKIP:
        fails.append(f"random-skip p {v.get('skip_p')} not < {GATE_SKIP}")
    return dict(verdict="PASS" if not fails else "FAIL", fails=fails)


# ================================================================================================ helpers
def _save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name):
    p = OUT / f"{name}.pkl"
    return pickle.loads(p.read_bytes()) if p.exists() else []


def _mods():
    os.environ["R51_CLEAN"] = "1"
    sys.path.insert(0, str(HERE.parent / "r51"))
    import r51cfg as K
    import common as C
    import fam_liq
    import r51data

    assert K.CLEAN
    fam_liq.IDX_STOP.setdefault("MIDCPNIFTY", R70.LIQ_IDX_STOP_MIDCP)
    return K, C, fam_liq, r51data


def trends(r51data) -> dict:
    """5-minute Supertrend for every index (index minutes only) and 15-minute for the traded ones."""
    t5, t15 = {}, {}
    for u in RU.OTHERS:
        days = {d: D.idx for d, D in r51data.index_days(u).items()}
        t5[u] = RU.Trend.build(days, 5)
        if u in R70.LIQ_BOOKS:
            t15[u] = RU.Trend.build(days, 15)
    return dict(t5=t5, t15=t15)


def make_trades(v: str, lo: date, hi: date, M, T) -> list:
    """fam_liq.make(und, books).run(lo, hi) for every book of the arm, with variant v's filter on each signal."""
    K, C, fam_liq, r51data = M
    out = []
    for und, books in R70.LIQ_BOOKS.items():
        days = r51data.load(und)
        ctx = dict(und=und, step=K.STEP[und], last=fam_liq.LAST, atm=fam_liq.H4.atm, t5=T["t5"],
                   t15=T["t15"].get(und))
        for tf in books:
            sig = fam_liq.signals(und, tf, 1.0)
            for d in C.days_in(und, lo, hi):
                if d not in sig:
                    continue
                D = days[d]
                ctx["day"] = d
                cands = []
                for s in sig[d]:
                    c = dict(s, fx=fam_liq.forced_row(D, s), book=tf, sig_er=int(s["er"]))
                    c = filter_signal(v, c, D.idx, ctx)
                    if c is not None:
                        cands.append(c)
                cands.sort(key=lambda z: z["er"])
                out += C.take(und, D, cands, fam_liq.EX, scope="all")
    for t in out:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    out.sort(key=lambda t: (t["day"], t["und"], t["er"]))
    return out


def run_phase(phase: str) -> dict:
    M = _mods()
    K, C = M[0], M[1]
    if phase == "final":
        C.open_final()
        lo, hi = R64.FINAL_START, K.END
    else:
        lo, hi = R64.START, R64.DESIGN_END
    T = trends(M[3])
    tr = {v: make_trades(v, lo, hi, M, T) for v in ALL}
    n0, net0 = R76_B0[phase]
    assert len(tr["B0"]) == n0 and round(sum(t["net"] for t in tr["B0"])) == net0, "B0 must equal R76's B0"
    out = {"res": {}}
    rng = np.random.default_rng(SEED)
    b0nets = [t["net"] for t in tr["B0"]]
    for v in ALL:
        _save(f"{v}_{phase}", tr[v])
        r = out["res"][v] = P.periods(tr[v], "Liquidity", phase)
        if v != "B0":
            r["vs_b0"] = skip_compare(tr["B0"], tr[v])
            p, diff = P.paired_boot(tr["B0"], tr[v], BOOT, SEED)
            r["paired"] = dict(p=p, diff=diff)
            if phase == "final":
                tw = skip_twins(b0nets, len(tr[v]), TWINS, rng)
                r["skip_p"] = p_of(tw, sum(t["net"] for t in tr[v]))
                r["skip_k"] = max(0, len(b0nets) - len(tr[v]))
    key = "final" if phase == "final" else "design"
    print(phase, {v: (out["res"][v][key]["trades"], out["res"][v][key]["net"]) for v in ALL}, flush=True)
    return out


# ================================================================================================ steps
def run(phase: str):
    path = RESULTS / f"{phase}.json"
    if phase == "final":
        if not FINAL_FLAG.exists():
            raise SystemExit("R79: the final read is opened only by `r79study.py final`")
        if path.exists():
            raise SystemExit(f"R79: {path.name} exists - the final test is read once")
    res = run_phase(phase)
    RESULTS.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(res, indent=1, default=str))


def final():
    if FINAL_FLAG.exists():
        raise SystemExit("R79: the final test was already read (results/FINAL_READ). One read only.")
    if not (RESULTS / "design.json").exists():
        raise SystemExit("R79: run and commit the design phase first")
    RESULTS.mkdir(parents=True, exist_ok=True)
    FINAL_FLAG.write_text(f"final test read on {datetime.now(timezone.utc).isoformat()}\n")
    run("final")
    summarise()


def summarise() -> dict:
    d, f = (json.loads((RESULTS / f"{ph}.json").read_text()) for ph in ("design", "final"))
    out = {"res": {}}
    for v in ALL:
        a, b = d["res"][v], f["res"][v]
        A = out["res"][v] = dict(design=a["design"], wf=a["wf"], wf_quarters=a["wf_quarters"], final=b["final"])
        if v != "B0":
            A.update(vs_b0=dict(design=a["vs_b0"], final=b["vs_b0"]),
                     paired=dict(design=a["paired"], final=b["paired"]), p=b["paired"]["p"], skip_p=b["skip_p"],
                     skip_k=b["skip_k"])
    qs = P.bh([out["res"][v]["p"] for v in TESTS])
    for v, q in zip(TESTS, qs):
        out["res"][v]["q"] = round(float(q), 4)
        out["res"][v]["decision"] = decide(out["res"]["B0"], out["res"][v])
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "summary.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        run("design")
    elif cmd == "final":
        final()
    elif cmd == "summary":
        summarise()
    else:
        print(__doc__)
