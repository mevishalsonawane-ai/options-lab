"""R84 study (PREREG.md): Breadth Donchian (R75's C), how many correlated positions per signal. Research only.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R51's clean stores (once)
    python research/hunt/r84/r84study.py design                # K0 check, 7 variants on design + quarters, twins, BH
    python research/hunt/r84/r84study.py final                 # ONE final read, only K0 + the variants that passed
    python research/hunt/r84/r84study.py day19                 # 19 Oct rebuilt (index side from Yahoo)

Signals and prices are R75's C exactly (r75study.signals / candidates, R64's engine, exits P). The only change: the
per-index `take` is replaced by r84rules.walk_day across the five indices, which at each signal keeps the subset a
variant chooses. Trades are never written to git; results/ holds small JSON only.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r75"))
import r84rules as P  # noqa: E402
import r75study as R75  # noqa: E402
import r75cfg as R75C  # noqa: E402
import r75levers as L  # noqa: E402

S = R75.S
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
EXPECT = {"design": 216929, "final": 171366}       # R75's published C (PREREG section 1)
TWINS, SEED = 500, 84


# ================================================================================================ candidates
_NIFTY: dict = {}


def nifty_shock() -> tuple:
    """({(day, b): NIFTY's 10-minute move at bar b}, {day: usual move}) from NIFTY's own 5-minute bars."""
    if not _NIFTY:
        from r71rules import day_starts
        d, b, o, h, l, c = S.mods()["fam_r44"].bars5("NIFTY")
        mv = P.ten_min_moves(np.asarray(o, float), np.asarray(c, float), day_starts(d))
        _NIFTY["mv"] = {(d[i], int(b[i])): float(mv[i]) for i in range(len(d))}
        _NIFTY["usual"] = P.usual_by_day(np.asarray(d), mv)
    return _NIFTY["mv"], _NIFTY["usual"]


_POS: dict = {}


def panel_pos(und: str):
    sys.path.insert(0, str(HERE.parent / "r72"))
    import r72study as X
    Pn = X.panel()
    if und not in _POS:
        f = Pn.f[und]
        _POS[und] = {(f["d"][i], int(f["b"][i])): i for i in range(len(f["d"]))}
    return Pn.f[und], _POS[und]


def day_cands(lo: date, hi: date) -> dict:
    """{day: [candidate dicts]} of C across the five indices, each priced by R64's engine (res None = no fill), with
    its break size, NIFTY's shock ratio at the signal and the premium risk."""
    M = S.mods()
    C, G, K, r51data = M["C"], M["G"], M["K"], M["r51data"]
    ex = R75.exits()
    mv, usual = nifty_shock()
    out: dict = {}
    for und in K.UNDS:
        sig = R75.signals("C", und)
        days = r51data.load(und)
        f, pos = panel_pos(und)
        for d in C.days_in(und, lo, hi):
            D = days[d]
            if D.expiry or d not in sig:
                continue
            cs = R75.candidates("C", und, D, sig[d], (), R75C.MONEY)
            if not cs:
                continue
            res = G.run_day(und, D, [c["side"] for c in cs], [c["strike"] for c in cs], [c["er"] for c in cs], ex,
                            [-1] * len(cs))
            qty = float(K.lot(und, d))
            for c, r in zip(cs, res):
                b = c["er"] // 5 - 1
                j = pos.get((d, b))
                size = 0.0 if j is None else P.break_size(c["side"], f["c"][j], f["chi"][j], f["clo"][j], f["atr"][j])
                con = D.contract(c["side"], c["strike"])
                prem = float(con[3][c["er"] - 1]) if con is not None else float("nan")
                risk = prem * qty * P.RB_STOP if np.isfinite(prem) and prem > 0 else None
                m = mv.get((d, b), float("nan"))
                if r is not None:
                    r = dict(r)
                    r.update(und=und, day=d, side=c["side"], strike=c["strike"], er=c["er"], sig=c["sig"])
                out.setdefault(d, []).append(dict(und=und, day=d, er=c["er"], side=c["side"], sig=c["sig"], res=r,
                                                  size=size, risk=risk, shock=P.shock_ratio(m, usual.get(d))))
    return out


def book(dc: dict, chooser, open_cap: int | None = None) -> tuple:
    """(trades, signals {(day, er, side): eligible count}) of one walk over every day."""
    tr, sigs = [], {}
    for d in sorted(dc):
        taken, s = P.walk_day(dc[d], chooser, open_cap=open_cap)
        tr += [c["res"] for c in taken]
        sigs.update({(d,) + k: n for k, n in s.items()})
    return tr, sigs


def variant_chooser(v: str):
    return lambda key, el, shock: P.choose(v, el, shock)


def shocked(dc: dict, sigs: dict) -> set:
    """The signals of a walk (sigs: {(day, er, side): n}) whose NIFTY shock ratio > k (what an X rule skipped)."""
    sh = {(c["day"], c["er"], c["side"]): c["shock"] for d in dc for c in dc[d]}
    return {k for k in sigs if sh.get(k) is not None and sh[k] > P.SHOCK_K}


# ================================================================================================ summaries
def summary(tr: list, lo: date, hi: date) -> dict:
    st = S.stats(tr, S.months_between(lo, hi))
    st.update(L.breaches(tr))
    sn = P.signal_nets(tr)
    st["worst_signal"] = round(min(sn.values())) if sn else 0
    st["signals"] = len(sn)
    st["signals_loss_5k"] = sum(1 for x in sn.values() if x <= -5000)
    return st


def quarters(k0: list, tr: list) -> list:
    out = []
    for a, z in R75C.WF_FOLDS:
        b, v = summary(S.split(k0, a, z), a, z), summary(S.split(tr, a, z), a, z)
        out.append(dict(k0=b, v=v, beat=bool(v.get("net", 0) >= b.get("net", 0)
                                              and v.get("worst_day", 0) >= b.get("worst_day", 0))))
    return out


def twins(v: str, dc: dict, x_n: int, k0_sigs: list, n: int = TWINS, seed: int = SEED) -> np.ndarray:
    rng = np.random.default_rng(seed)
    out = np.empty(n)
    for i in range(n):
        skip = None
        if v in ("X-skip", "X-K2"):
            ix = rng.choice(len(k0_sigs), size=min(x_n, len(k0_sigs)), replace=False)
            skip = {k0_sigs[j] for j in ix}
        tr, _ = book(dc, P.twin_chooser(v, rng, skip), P.OPEN_CAP.get(v))
        out[i] = sum(t["net"] for t in tr)
    return out


def _json(obj, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1, default=str) + "\n")


# ================================================================================================ phases
def design() -> dict:
    lo, hi = R75C.START, R75C.DESIGN_END
    ref = R75.priced("C", lo, hi)                        # R75's own book, the per-index take
    dc = day_cands(lo, hi)
    k0, k0_sigs = book(dc, variant_chooser("K0"))
    for name, tr in (("R75 priced", ref), ("R84 walker", k0)):
        net = round(sum(t["net"] for t in tr))
        if net != EXPECT["design"]:
            raise SystemExit(f"K0 design via {name}: {net} != R75's {EXPECT['design']}: stop")
    sk = shocked(dc, k0_sigs)
    sig_list = sorted(k0_sigs)
    q0 = quarters(k0, k0)
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), k0=dict(
        design=summary(k0, lo, hi), quarters=[q["k0"] for q in q0], wf_net=sum(q["k0"].get("net", 0) for q in q0),
        signals=len(k0_sigs), positions_per_signal=_dist(k0_sigs), shocked_signals=len(sk)), variants={})
    print("K0", out["k0"]["design"]["trades"], out["k0"]["design"]["net"], "signals", len(k0_sigs), "shocked", len(sk),
          flush=True)
    ps = []
    for v in P.ALL:
        tr, vs = book(dc, variant_chooser(v), P.OPEN_CAP.get(v))
        qs = quarters(k0, tr)
        x_n = len(shocked(dc, vs)) if v in ("X-skip", "X-K2") else 0
        tw = twins(v, dc, x_n, sig_list)
        net = sum(t["net"] for t in tr)
        p = L.p_of(tw, net)
        r = dict(design=summary(tr, lo, hi), quarters=[q["v"] for q in qs], quarters_beat=sum(q["beat"] for q in qs),
                 wf_net=sum(q["v"].get("net", 0) for q in qs), p=p, twin_mean=round(float(tw.mean())),
                 twin_p90=round(float(np.quantile(tw, 0.9))), vs_k0=R75.compare(k0, tr), shocked_skipped=x_n)
        out["variants"][v] = r
        ps.append(p)
        print(v, r["design"]["trades"], r["design"]["net"], r["design"]["max_dd"], r["design"]["worst_day"],
              "p", p, flush=True)
    q7 = L.bh(ps[:len(P.VARIANTS)])                  # BH over the 7 pre-registered tests (before amendment A1)
    for v, q, q_7 in zip(P.ALL, L.bh(ps), list(q7) + [None] * len(P.A1)):
        r = out["variants"][v]
        r["q"] = round(float(q), 4)
        r["q7"] = None if q_7 is None else round(float(q_7), 4)
        r["post_hoc"] = v in P.A1
        r["fails"] = P.gate(r, out["k0"], r["p"], r["q"])
        r["verdict"] = "PASS" if not r["fails"] else "FAIL"
    _json(out, RESULTS / "design.json")
    return out


def _dist(sigs: dict) -> dict:
    c: dict = {}
    for n in sigs.values():
        c[n] = c.get(n, 0) + 1
    return {str(k): c[k] for k in sorted(c)}


def final() -> dict:
    des = json.loads((RESULTS / "design.json").read_text())
    passed = [v for v in P.ALL if des["variants"][v]["verdict"] == "PASS"]
    if not passed:
        raise SystemExit("no variant passed design + walk-forward: the final is not read (PREREG section 7)")
    if FINAL_FLAG.exists():
        raise SystemExit("R84's final was already read once (results/FINAL_READ); a second read is refused")
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    M = S.mods()
    M["C"].open_final()
    lo, hi = R75C.FINAL_START, M["K"].END
    dc = day_cands(lo, hi)
    k0, _ = book(dc, variant_chooser("K0"))
    if round(sum(t["net"] for t in k0)) != EXPECT["final"]:
        raise SystemExit("K0 final does not reproduce R75's C: stop")
    out = dict(read=FINAL_FLAG.read_text().strip(), k0=summary(k0, lo, hi), variants={})
    for v in passed:
        tr, _ = book(dc, variant_chooser(v), P.OPEN_CAP.get(v))
        out["variants"][v] = dict(final=summary(tr, lo, hi), vs_k0=R75.compare(k0, tr))
    _json(out, RESULTS / "final.json")
    return out


if __name__ == "__main__":
    os.environ.setdefault("R51_CLEAN", "1")
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "day19":
        import r84day19
        r84day19.run()
    else:
        raise SystemExit(__doc__)
