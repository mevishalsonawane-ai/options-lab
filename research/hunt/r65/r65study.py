"""R65 study (PREREG.md): Boss's step trail (+10% -> stop +5%, +15% -> +10%, ...) and a -5% stop moved up, on the
Donchian, OOV and Solo 2.0 paper arms, against B0 (research rules) and A4 (the app's R64 ladder). Research only.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R51's clean stores (Donchian, OOV)
    python research/hunt/r57/r57data.py build                  # R57's stores (Solo 2.0)
    python research/hunt/r65/r65study.py design                # design + walk-forward, every arm and variant
    python research/hunt/r65/r65study.py final                 # the ONE final read (refuses a second), twins, BH
    python research/hunt/r65/r65study.py report                # results/*.json -> results/report.md

Signals, engine and arm code are R64's (hunt/r64: r64arms, r64engine); only the exits change. Each arm group runs in its
own process (r57cfg re-points the shared R51 engine at R57's stores). Trades go to $R65_OUT (never git).
"""
from __future__ import annotations

import json
import os
import pickle
import subprocess
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r64"))
import r65cfg as R  # noqa: E402
import r64study as P  # noqa: E402  (R64's pure statistics: stats, periods, split, bh, gates, paired_boot)

GROUPS = {"r51": ("Donchian", "OOV"), "solo2": ("Solo2",)}
RUN = R.VARIANTS + R.REFS
KEY = ("und", "day", "side", "er")


# ================================================================================================ pure helpers
def key(t: dict) -> tuple:
    return tuple(t[k] for k in KEY)


def trail_account(trades: list, plain: list) -> dict:
    """PREREG section 4: the trades the trail (lock) closed against the same trade under the plain counterpart.
    trades / plain: dicts with und, day, side, er, why, net, ret, and 'post' (the option's best gain after the exit,
    before 15:15) on lock exits."""
    pm = {key(t): t for t in plain}
    locked = [t for t in trades if t["why"] == "lock"]
    pairs = [(t, pm[key(t)]) for t in locked if key(t) in pm]
    d = np.array([t["net"] - p["net"] for t, p in pairs], float)
    give = [(t, p) for t, p in pairs if p["net"] > t["net"]]
    save = [(t, p) for t, p in pairs if p["net"] < t["net"]]

    def ex(tp):
        if tp is None:
            return None
        t, p = tp
        return dict(und=t["und"], day=str(t["day"]), side=t["side"], trail_net=round(t["net"]),
                    plain_net=round(p["net"]), trail_ret=round(t["ret"], 3), plain_ret=round(p["ret"], 3),
                    plain_why=p["why"])

    return dict(locked=len(locked), matched=len(pairs), unmatched=len(locked) - len(pairs),
                given_up=round(float(-d[d < 0].sum())) if len(d) else 0,
                saved=round(float(d[d > 0].sum())) if len(d) else 0,
                net_effect=round(float(d.sum())) if len(d) else 0, n_worse=len(give), n_better=len(save),
                cut_plain30=sum(1 for _, p in pairs if p["ret"] >= R.BIG - 1e-9),
                went_on30=sum(1 for t in locked if np.isfinite(t.get("post", np.nan)) and t["post"] >= R.BIG - 1e-9),
                worst_example=ex(max(give, key=lambda tp: tp[1]["net"] - tp[0]["net"]) if give else None),
                best_example=ex(max(save, key=lambda tp: tp[0]["net"] - tp[1]["net"]) if save else None))


def exit_twin_totals(plain: list, k: int, n: int, rng, net_forced) -> np.ndarray:
    """PREREG section 6 random-EXIT twins: n sets; each picks min(k, eligible) plain trades held >= 2 rows (xr - er >=
    2) and exits each at the open of a uniform row in (er, xr]; net_forced(trade, rows) -> nets of that trade forced
    out at each row (the plain exits still act before). Returns (n,) totals."""
    base = float(sum(t["net"] for t in plain))
    tot = np.full(n, base)
    elig = [i for i, t in enumerate(plain) if t["xr"] - t["er"] >= 2]
    m = min(k, len(elig))
    if m == 0:
        return tot
    req = defaultdict(lambda: ([], []))
    for s in range(n):
        for j in rng.choice(len(elig), size=m, replace=False):
            t = plain[elig[j]]
            req[elig[j]][0].append(s)
            req[elig[j]][1].append(int(rng.integers(t["er"] + 1, t["xr"] + 1)))
    for i in sorted(req):
        sets, rows = req[i]
        nets = np.asarray(net_forced(plain[i], np.asarray(rows, np.int64)), float)
        np.add.at(tot, np.asarray(sets), nets - plain[i]["net"])
    return tot


def p_of(tw: np.ndarray, actual: float) -> float:
    return round((1 + int((tw >= actual).sum())) / (len(tw) + 1), 4)


def decide(arm_res: dict) -> dict:
    """PREREG section 6: a trail variant is recommended only if it passes every gate, its exit-twin q <= 0.10 and its
    net is >= both B0's and A4's in design, walk-forward and final. Trade-offs: lower final max DD or a better worst
    day than A4 (what the app runs) at a lower final net."""
    rec = None
    tradeoffs = []
    for v in R.TRAILS:
        r = arm_res[v]
        better = all(r[ph]["net"] >= arm_res[b][ph]["net"] for b in ("B0", "A4") for ph in ("design", "wf", "final"))
        ok = r["verdict"] == "PASS" and r.get("exit_q") is not None and r["exit_q"] <= P.R.GATE_Q
        if ok and better:
            if rec is None or r["final"]["net"] > arm_res[rec]["final"]["net"]:
                rec = v
        a4 = arm_res["A4"]["final"]
        if r["final"].get("net", 0) < a4.get("net", 0) and (
                r["final"].get("max_dd", 1e18) < a4.get("max_dd", 0) or
                r["final"].get("worst_day", -1e18) > a4.get("worst_day", 0)):
            tradeoffs.append(v)
    return dict(recommended=rec, keep="A4 (app today)" if rec is None else rec, tradeoffs=tradeoffs)


# ================================================================================================ runners
def _exits(E, K, spec: dict):
    return P._exits(E, K, spec)


def _save(name: str, obj):
    R.OUT.mkdir(parents=True, exist_ok=True)
    with open(R.OUT / f"{name}.pkl", "wb") as f:
        pickle.dump(obj, f, protocol=pickle.HIGHEST_PROTOCOL)


def _load(name: str):
    try:
        with open(R.OUT / f"{name}.pkl", "rb") as f:
            return pickle.load(f)
    except FileNotFoundError:
        return []


def _strip(trades: list) -> list:
    keep = ("und", "day", "side", "strike", "er", "xr", "E", "X", "rE", "rX", "qty", "why", "net", "peak", "ret",
            "setup", "money", "fx0", "post")
    return [{k: t[k] for k in keep if k in t} for t in trades]


def add_post(E, K, load, trades: list):
    """'post' on lock exits: the option's best high after the exit row through 15:15, over the raw fill, - 1."""
    last = K.row(15, 15)
    for t in trades:
        if t["why"] != "lock":
            continue
        D = load(t["und"])[t["day"]]
        st = E.stack(t["und"], D)
        C = st.S[st.idx(t["side"], t["strike"])]
        h = C[1, t["xr"] + 1:last + 1]
        h = h[np.isfinite(h)]
        t["post"] = float(h.max() / t["E"] - 1.0) if len(h) else float("nan")


def _record(arm, v, phase, tr, res, out):
    _save(f"{arm}_{v}_{phase}", _strip(tr))
    out[arm][v] = res
    print(arm, v, phase, json.dumps({k: res[k].get("net") if isinstance(res[k], dict) else res[k]
                                     for k in res if k != "wf_quarters"}), flush=True)


def _exit_twins(arm, phase, trades_by_v, out, net_forced_of):
    if phase != "final":
        return
    for v in R.TRAILS:
        plain = trades_by_v[R.PLAIN[v]]
        k = sum(1 for t in trades_by_v[v] if t["why"] == "lock")
        rng = np.random.default_rng(R.SEED)
        tw = exit_twin_totals(plain, k, R.TWINS, rng, net_forced_of(R.PLAIN[v]))
        out[arm][v]["exit_p"] = p_of(tw, sum(t["net"] for t in trades_by_v[v]))
        out[arm][v]["exit_twin_mean"] = round(float(tw.mean()))
        out[arm][v]["exit_k"] = k


def run_r51(phase: str) -> dict:
    """Donchian and OOV on R51's clean stores (R64's arm code)."""
    os.environ["R51_CLEAN"] = "1"
    sys.path.insert(0, str(HERE.parent / "r51"))
    import r51cfg as K
    import common as C
    import engine as E
    import fam_r44
    import r51data
    import r64arms as A
    import r64engine as G

    assert K.CLEAN, "R65 prices Donchian / OOV on R51's Amendment A clean stores"
    if phase == "final":
        C.open_final()
        lo, hi = R.R64.FINAL_START, K.END
    else:
        lo, hi = R.R64.START, R.R64.DESIGN_END
    out = {}
    for arm in GROUPS["r51"]:
        out[arm], by_v = {}, {}
        for v in RUN:
            ex = _exits(E, K, R.exit_spec(R.OWN[arm], v))
            tr = A.donchian_trades(C, E, K, fam_r44, r51data, G, ex, lo, hi) if arm == "Donchian" else \
                A.oov_trades(C, E, K, r51data, G, ex, lo, hi)
            add_post(E, K, r51data.load, tr)
            by_v[v] = tr
            res = P.periods(tr, arm, phase)
            if phase == "final" and v in R.VARIANTS:
                rng = np.random.default_rng(R.SEED)
                tw = A.donchian_twins(E, K, r51data, G, tr, ex, R.TWINS, rng) if arm == "Donchian" else \
                    A.r57_twins(E, K, r51data.load, G, tr, lambda t: (ex, None), R.TWINS, rng)
                res["p"] = p_of(tw, sum(t["net"] for t in tr))
                res["twin_mean"] = round(float(tw.mean()))
            _record(arm, v, phase, tr, res, out)

        def nf_of(pv, arm=arm):
            ex = _exits(E, K, R.exit_spec(R.OWN[arm], pv))

            def nf(t, rows):
                D = r51data.load(t["und"])[t["day"]]
                m = len(rows)
                return G.twin_nets(t["und"], D, [t["side"]] * m, [t["strike"]] * m, [t["er"]] * m, ex, rows)
            return nf
        _exit_twins(arm, phase, by_v, out, nf_of)
    return out


def solo2_reprice(E, S, G, load, picks: list, exits_of) -> list:
    """r64arms.solo2_reprice, keeping each trade's index-exit row `fx0` (for the exit twins)."""
    out = []
    for p in picks:
        D = load(p["und"])[p["day"]]
        ex = exits_of(p["setup"])
        fx = S.forced_row(D, p["er"], S.sgn(p["side"]), S.IDX_EXIT.get(p["setup"]), E.sq_row(D, ex))
        r = G.run_day(p["und"], D, [p["side"]], [p["strike"]], [p["er"]], ex, [fx])[0]
        if r is None:
            continue
        r["setup"], r["money"], r["fx0"] = p["setup"], p["money"], int(fx)
        out.append(r)
    return out


def forced_rows(rows: np.ndarray, fx0: int) -> np.ndarray:
    """A twin's forced exit: its random row, or the trade's own index exit if that comes first."""
    return np.minimum(rows, fx0) if fx0 >= 0 else rows


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

    out, by_v = {"Solo2": {}}, {}
    for v in RUN:
        tr = solo2_reprice(E, S, G, r57data.load, picks, lambda s: exits_of(s, v))
        add_post(E, K, r57data.load, tr)
        by_v[v] = tr
        res = P.periods(tr, "Solo2", phase)
        res["r57_reference"] = ref
        if phase == "final" and v in R.VARIANTS:
            rng = np.random.default_rng(R.SEED)
            tw = A.r57_twins(E, K, r57data.load, G, tr,
                             lambda t: (exits_of(t["setup"], v), S.IDX_EXIT.get(t["setup"])), R.TWINS, rng,
                             forced_fn=S.forced_row)
            res["p"] = p_of(tw, sum(t["net"] for t in tr))
            res["twin_mean"] = round(float(tw.mean()))
        _record("Solo2", v, phase, tr, res, out)

    def nf_of(pv):
        def nf(t, rows):
            D = r57data.load(t["und"])[t["day"]]
            m = len(rows)
            return G.twin_nets(t["und"], D, [t["side"]] * m, [t["strike"]] * m, [t["er"]] * m,
                               exits_of(t["setup"], pv), forced_rows(rows, t.get("fx0", -1)))
        return nf
    _exit_twins("Solo2", phase, by_v, out, nf_of)
    return out


# ================================================================================================ steps
def run(group: str, phase: str):
    path = R.RESULTS / f"{phase}_{group}.json"
    if phase == "final":
        if not R.FINAL_FLAG.exists():
            raise SystemExit("R65: the final read is opened only by `r65study.py final`")
        if path.exists():
            raise SystemExit(f"R65: {path.name} exists - the final test is read once")
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
        raise SystemExit("R65: the final test was already read (results/FINAL_READ). One read only.")
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    R.FINAL_FLAG.write_text(f"final test read on {datetime.now(timezone.utc).isoformat()}\n")
    for g in GROUPS:
        _sub(g, "final")
    summarise()


def _accounts(arm_trades: dict, phase: str) -> dict:
    return {v: trail_account(arm_trades[v][phase], arm_trades[R.PLAIN.get(v, "N0")][phase])
            for v in R.TRAILS + ("A4",)}


def summarise() -> dict:
    """Merge the group results; BH over the 30 entry-twin tests and the 24 exit-twin tests; gates; trail accounting;
    paired bootstraps against B0 and A4; the three together; the decision. Writes results/summary.json."""
    des, fin = {}, {}
    for g in GROUPS:
        des.update(json.loads((R.RESULTS / f"design_{g}.json").read_text()))
        fin.update(json.loads((R.RESULTS / f"final_{g}.json").read_text()))
    T = {a: {v: {ph: _load(f"{a}_{v}_{ph}") for ph in ("design", "final")} for v in RUN} for a in R.ARMS}
    out = {"arms": {}, "together": {}}
    keys = []
    for arm in R.ARMS:
        out["arms"][arm] = {}
        for v in RUN:
            d, f = des[arm][v], fin[arm][v]
            out["arms"][arm][v] = dict(design=d["design"], wf=d["wf"], wf_quarters=d["wf_quarters"],
                                       final=f["final"], p=f.get("p"), twin_mean=f.get("twin_mean"),
                                       exit_p=f.get("exit_p"), exit_k=f.get("exit_k"),
                                       exit_twin_mean=f.get("exit_twin_mean"))
            if v in R.VARIANTS:
                keys.append((arm, v))
    qs = P.bh([out["arms"][a][v]["p"] if out["arms"][a][v]["p"] is not None else 1.0 for a, v in keys])
    for (a, v), q in zip(keys, qs):
        r = out["arms"][a][v]
        r["q"] = round(float(q), 4)
        fails = P.gates(r["final"], r["design"], r["wf"], r["p"], r["q"])
        r["verdict"] = "PASS" if not fails else "FAIL: " + "; ".join(fails)
    tk = [(a, v) for a in R.ARMS for v in R.TRAILS]
    eq = P.bh([out["arms"][a][v]["exit_p"] if out["arms"][a][v]["exit_p"] is not None else 1.0 for a, v in tk])
    for (a, v), q in zip(tk, eq):
        out["arms"][a][v]["exit_q"] = round(float(q), 4)
    for arm in R.ARMS:
        A_ = out["arms"][arm]
        acc = {ph: _accounts(T[arm], ph) for ph in ("design", "final")}
        for v in R.TRAILS + ("A4",):
            A_[v]["trail"] = {ph: acc[ph][v] for ph in acc}
            A_[v]["paired"] = {f"vs_{b}": {ph: dict(zip(("p", "diff"), P.paired_boot(T[arm][b][ph], T[arm][v][ph],
                                                                                    R.BOOT, R.SEED)))
                                           for ph in ("design", "final")} for b in ("B0", "A4") if b != v}
        A_["decision"] = decide(A_)
    for v in RUN:
        dt = sum((T[a][v]["design"] for a in R.ARMS), [])
        ft = sum((T[a][v]["final"] for a in R.ARMS), [])
        row = dict(design=P.stats(dt), wf=P.stats(P.split(dt, R.R64.WF_START, R.R64.DESIGN_END)), final=P.stats(ft))
        if v in R.TRAILS + ("A4",):
            row["paired"] = {f"vs_{b}": {ph: dict(zip(("p", "diff"), P.paired_boot(
                sum((T[a][b][ph] for a in R.ARMS), []), x, R.BOOT, R.SEED))) for ph, x in (("design", dt), ("final", ft))}
                for b in ("B0", "A4") if b != v}
            row["trail"] = {ph: trail_account(sum((T[a][v][ph] for a in R.ARMS), []),
                                              sum((T[a][R.PLAIN.get(v, "N0")][ph] for a in R.ARMS), []))
                            for ph in ("design", "final")}
        out["together"][v] = row
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
        import r65report
        r65report.main()
    else:
        print(__doc__)
