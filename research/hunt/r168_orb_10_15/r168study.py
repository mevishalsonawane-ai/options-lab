"""R168 study (PREREG.md): the app's ORB, ORB Fresh and ORB Sweep with their own exits (B0) and with R154's 24 scalp exits.

    python research/hunt/r51/r51data.py build                    (R51_CLEAN=1; R51's clean stores, to 13 Oct)
    python research/hunt/r154_scalp_10_15/r154data.py build-early (R154's early store, Aug 2021 - 19 Nov 2024)
    python research/hunt/r168_orb_10_15/r168study.py design      # P1 + P2 for every index (+ the pre-window parity)
    python research/hunt/r168_orb_10_15/r168study.py final       # the ONE final read (+ the holdout parity; refuses a 2nd)
    python research/hunt/r168_orb_10_15/r168study.py decide      # twins' BH + gates -> results/decision.json

Pure parts (compact arrays, stats) are tested in test_r168.py; BH, twins and the walk-forward pick are R154's.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import Counter
from datetime import date, datetime, timezone
from multiprocessing import Pool

import numpy as np

import r168cfg as K
import r154study as S154          # pure: bh, twin_totals, twin_p, wf_pick

WHY_CODE = {"target": 0, "stop": 1, "time": 2, "squareoff": 3, "missing": 4, "lock": 5}
WHY_NAME = {v: k for k, v in WHY_CODE.items()}


# ================================================================================================ pure
def compact(trades: list) -> dict:
    return dict(day=np.array([t["day"].toordinal() for t in trades], np.int64),
                net=np.array([t["net"] for t in trades], float),
                charges=np.array([t["charges"] for t in trades], float),
                why=np.array([WHY_CODE.get(t["why"], 4) for t in trades], np.int8))


def in_range(a: dict, lo: date, hi: date) -> dict:
    m = (a["day"] >= lo.toordinal()) & (a["day"] <= hi.toordinal())
    return {k: v[m] for k, v in a.items()}


def concat(parts: list) -> dict:
    if not parts:
        return dict(day=np.zeros(0, np.int64), net=np.zeros(0), charges=np.zeros(0), why=np.zeros(0, np.int8))
    return {k: np.concatenate([p[k] for p in parts]) for k in parts[0]}


def stats(a: dict, sessions: int | None = None) -> dict:
    """Trades, win rate, break-even win rate (avg loss / (avg win + avg loss)), net, per trade, per session, worst day,
    max drawdown (daily), net without the 5 best trades, average charges, exit mix."""
    n = int(len(a["net"]))
    base = dict(trades=n, sessions=sessions)
    if not n:
        return dict(base, win=None, be_win=None, net=0, per_trade=None, per_session=0 if sessions else None,
                    worst_day=None, max_dd=0, ex_top5=0, avg_win=None, avg_loss=None, avg_charges=None, exits={})
    nets = a["net"]
    days, inv = np.unique(a["day"], return_inverse=True)
    seq = np.bincount(inv, weights=nets)
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    mdd = float((np.maximum.accumulate(eq) - eq).max())
    wins, losses = nets[nets > 0], nets[nets <= 0]
    aw = float(wins.mean()) if len(wins) else 0.0
    al = float(-losses.mean()) if len(losses) else 0.0
    net = float(nets.sum())
    return dict(base, win=round(float((nets > 0).mean()), 4), be_win=(round(al / (aw + al), 4) if aw + al > 0 else None),
                net=round(net), per_trade=round(net / n), per_session=(round(net / sessions) if sessions else None),
                days=int(len(days)), worst_day=round(float(seq.min())), max_dd=round(mdd),
                ex_top5=round(net - float(np.sort(nets)[::-1][:5].sum())), avg_win=round(aw), avg_loss=round(al),
                avg_charges=round(float(a["charges"].mean())), exits=dict(Counter(WHY_NAME[int(w)] for w in a["why"])))


def gates(p1: dict, p2: dict, p3: dict, q: float, b0_p2: dict, b0_p3: dict) -> dict:
    """PREREG section 7 (1 lot)."""
    fails = []
    if not (p1["net"] > 0 and p2["net"] > 0 and p3["net"] > 0):
        fails.append("not positive in P1, P2 and P3")
    if not q <= K.GATE_Q:
        fails.append(f"exit-twin BH q {q:.2f} > {K.GATE_Q}")
    if p3["trades"] < K.MIN_FINAL:
        fails.append(f"< {K.MIN_FINAL} final trades")
    if not (p2["net"] > b0_p2["net"] and p3["net"] > b0_p3["net"]):
        fails.append("not above the arm's own exits in P2 and P3")
    return dict(passed=not fails, fails=fails)


def parity_check(arm: str, part: str, n: int, net: float) -> dict:
    pt, pn = K.PUBLISHED[(arm, part)]
    ok_n = abs(n - pt) <= K.PAR_TRADES_TOL * pt
    ok_net = abs(net - pn) <= K.PAR_NET_TOL * abs(pn)
    return dict(trades=n, net=round(net), published=[pt, pn], trades_ok=bool(ok_n), net_ok=bool(ok_net))


# ================================================================================================ data
def mods():
    import r168engine as X
    return dict(X.mods(), X=X)


def sessions(und: str, lo: date, hi: date) -> list:
    M = mods()
    first = max(lo, K.FIRST_OPTION_DAY.get(und, K.EARLY_START))
    return M["DATA"].sessions(und, first, hi)


def day_trades(arm: str, und: str, D, s: dict, how: str, lots: int, mode: str = "dated") -> list:
    """One day's trades of `arm` on `und` under exit `how` ('B0' or a rule id)."""
    import r168signals as SG
    X = mods()["X"]
    if how == "B0":
        fn = lambda er, side: X.price_own(und, D, er, side, s["strike"], arm, lots, mode)  # noqa: E731
    else:
        fn = lambda er, side: X.price_scalp(und, D, er, side, s["strike"], how, lots)  # noqa: E731
    out = SG.walk(arm, s, fn)
    for t in out:
        t["arm"] = arm
    return out


def run_und(args) -> dict:
    """Every arm x exit x lots on one index over lo..hi: {arm: {how@lots: compact}}, the 1-lot trades kept for the twins
    (final), the day count, and the parity runs (BANKNIFTY)."""
    und, lo, hi, phase = args
    import r168signals as SG
    days = sessions(und, lo, hi)
    setups = [(d, D, SG.day_setup(D.idx, und)) for d, D in days]
    res, kept = {}, {}
    hows = ("B0",) + K.RULE_IDS
    for arm in K.ARMS:
        res[arm], kept[arm] = {}, {}
        for how in hows:
            for n in K.LOTS:
                tr = []
                for d, D, s in setups:
                    if s is not None:
                        tr += day_trades(arm, und, D, s, how, n)
                res[arm][f"{how}@{n}"] = compact(tr)
                if n == 1 and phase == "final" and how != "B0":
                    kept[arm][how] = [{k: t[k] for k in ("und", "day", "side", "strike", "er", "xr", "tmax", "sq", "qty", "net")}
                                      for t in tr]
        print(phase, und, arm, "priced", flush=True)
    par = {}
    if und == K.APP_UND:
        win = {"design": ("pre", K.PAR_PRE), "final": ("ho", K.PAR_HO)}[phase]
        part, (plo, phi) = win
        pdays = sessions(und, plo, phi)
        for arm in K.ARMS:
            for mode in ("parity", "dated"):
                tr = []
                for d, D in pdays:
                    s = SG.day_setup(D.idx, und)
                    if s is not None:
                        tr += day_trades(arm, und, D, s, "B0", 1, mode)
                net = float(sum(t["net"] for t in tr))
                par[f"{arm}:{part}:{mode}"] = dict(parity_check(arm, part, len(tr), net), sessions=len(pdays),
                                                   win=round(float(np.mean([t["net"] > 0 for t in tr])), 4) if tr else None)
        print(phase, "parity", json.dumps(par), flush=True)
    return dict(und=und, res=res, kept=kept, sessions=len(days), parity=par)


def windows(phase: str, und: str) -> dict:
    if phase == "design":
        w = {"P1": (K.EARLY_START, K.P1_END), "P2": (K.P2_START, K.P2_END),
             "P1_early": (K.EARLY_START, date(2024, 11, 19)), "P1_recent": (K.ARM_START, K.P1_END)}
        for i, (lo, hi) in enumerate(K.P2_Q):
            w[f"P2_q{i + 1}"] = (lo, hi)
    else:
        w = {"P3": (K.FINAL_START, K.FINAL_END)}
    return {k: (lo, hi, len(sessions(und, lo, hi))) for k, (lo, hi) in w.items()}


def _save(name: str, obj):
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name: str):
    return pickle.loads((K.OUT / f"{name}.pkl").read_bytes())


def run(phase: str):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists():
            raise SystemExit("R168 final already read once: " + K.FINAL_FLAG.read_text())
        if not (K.RESULTS / "design.json").exists():
            raise SystemExit("the final is read only after the design run")
        K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        lo, hi = K.FINAL_START, K.FINAL_END
    else:
        lo, hi = K.EARLY_START, K.P2_END
    with Pool(4) as pool:
        outs = pool.map(run_und, [(u, lo, hi, phase) for u in K.UNDS])
    allres, parity = {}, {}
    rng = np.random.default_rng(K.SEED)
    for o in outs:
        u = o["und"]
        parity.update(o["parity"])
        win = windows(phase, u)
        _save(f"{phase}_{u}", o["res"])
        for arm, byhow in o["res"].items():
            ou = allres.setdefault(arm, {}).setdefault(u, {})
            for key, arr in byhow.items():
                ou[key] = {w: stats(in_range(arr, a, b), s) for w, (a, b, s) in win.items()}
            if phase == "final":
                Y = mods()["Y"]
                dm = dict(sessions(u, lo, hi))
                for r in K.RULE_IDS:
                    tr = o["kept"][arm][r]
                    choices = [Y.twin_nets(t, dm[t["day"]]) for t in tr]
                    act = float(sum(t["net"] for t in tr))
                    tot = S154.twin_totals(choices, K.TWINS, rng)
                    ou[f"{r}@1"]["twin"] = dict(p=round(S154.twin_p(act, tot), 4), mean=round(float(tot.mean())), n=len(tr))
    allres["_parity"] = parity
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(allres, indent=0, default=str))
    print(phase, "written", flush=True)


def wf_all() -> dict:
    """Walk-forward pick per arm x index (descriptive), from the saved design and final trades (1 lot)."""
    out = {}
    for u in K.UNDS:
        d, f = _load(f"design_{u}"), _load(f"final_{u}")
        for arm in K.ARMS:
            by = {r: concat([d[arm][f"{r}@1"], f[arm][f"{r}@1"]]) for r in K.RULE_IDS}
            out[f"{arm}:{u}"] = dict(P2=S154.wf_pick(by, list(K.P2_Q), K.EARLY_START),
                                     P3=S154.wf_pick(by, [(K.FINAL_START, K.FINAL_END)], K.EARLY_START))
    return out


def decide() -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    tests = list(K.TESTS)
    ps = [f[a][u][f"{r}@1"]["twin"]["p"] for a, u, r in tests]
    q_all = S154.bh(ps, K.M_TOTAL)
    q_own = S154.bh(ps)
    out = {}
    for (a, u, r), qa, qo, p in zip(tests, q_all, q_own, ps):
        s1, s2, s3 = d[a][u][f"{r}@1"]["P1"], d[a][u][f"{r}@1"]["P2"], f[a][u][f"{r}@1"]["P3"]
        g = gates(s1, s2, s3, float(qa), d[a][u]["B0@1"]["P2"], f[a][u]["B0@1"]["P3"])
        g.update(twin_p=p, q=round(float(qa), 4), q_r168=round(float(qo), 4))
        out[f"{a}:{u}:{r}"] = g
    out["_m"] = dict(r168=len(tests), prior=K.N_PRIOR, total=K.M_TOTAL,
                     passed=sum(1 for k, v in out.items() if not k.startswith("_") and v["passed"]))
    out["_wf"] = wf_all()
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=0))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final"):
        run(cmd)
    elif cmd == "decide":
        print(json.dumps(decide()["_m"], indent=1))
    else:
        raise SystemExit(__doc__)
