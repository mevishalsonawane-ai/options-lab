"""R156 study (PREREG.md): train the scalp network walk-forward, trade its forecasts, gate them, and describe what it
learned.

    python research/hunt/r156_scalp_network/r156data.py build                    # examples (to 13 Oct)
    R156_POST=1 python research/hunt/r156_scalp_network/r156data.py build        # examples 14-19 Oct
    python research/hunt/r156_scalp_network/r156study.py train FOLD SEED         # one model (inputs only for the fold)
    python research/hunt/r156_scalp_network/r156study.py forecast FOLD           # ensemble forecasts + TOP thresholds
    python research/hunt/r156_scalp_network/r156study.py trees FOLD              # comparison model (table only)
    python research/hunt/r156_scalp_network/r156study.py design                  # O1 + P2: rules, AUC, calibration
    python research/hunt/r156_scalp_network/r156study.py final                   # P3 once (+ P4), twins
    python research/hunt/r156_scalp_network/r156study.py importance              # shuffle test on P2 (fold 2025)
    python research/hunt/r156_scalp_network/r156study.py decide                  # BH + gates -> results/decision.json

Pure parts (walk, scores, twins, calibration, stats, BH, gates) take plain arrays; test_r156.py covers them.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timezone

import numpy as np
from numba import njit

import r156cfg as K

K4 = K.K4


# ================================================================================================ pure
@njit(cache=True)
def walk(sess, s, score, rule, net, xr, thr, strict, max_per_day):
    """Walk every session (rows sess[i]..sess[i+1], pairs of call / put rows per moment in time order): at a free moment
    take the side with the higher score when it clears the threshold (score > thr if strict else >= thr) and its label
    exists; one open position (next entry row s + 1 must be after the exit row), at most max_per_day. Returns the taken
    rows and their rule columns."""
    rows = np.empty(len(s), np.int64)
    cols = np.empty(len(s), np.int64)
    n = 0
    for i in range(len(sess) - 1):
        busy, cnt = -1, 0
        a = sess[i]
        while a + 1 < sess[i + 1]:
            if cnt >= max_per_day:
                break
            if s[a] + 1 > busy:
                r = a if score[a] >= score[a + 1] else a + 1
                v = score[r]
                ok = (v > thr[r]) if strict else (v >= thr[r])
                if ok and v == v:
                    j = rule[r]
                    x = net[r, j]
                    if x == x:
                        rows[n], cols[n] = r, j
                        n += 1
                        busy = xr[r, j]
                        cnt += 1
            a += 2
    return rows[:n], cols[:n]


def scores_for(test: str, p: np.ndarray, ev: np.ndarray, thr_top: np.ndarray) -> tuple:
    """(score (n,), rule column (n,), threshold (n,), strict) of a trading rule (PREREG section 4)."""
    n = len(p)
    if test.startswith("EV-"):
        j = K.RULE_IDS.index(test[3:])
        return ev[:, j].astype(np.float64), np.full(n, j, np.int64), np.zeros(n), True
    if test.startswith("TOP-"):
        j = K.RULE_IDS.index(test[4:])
        return p[:, j].astype(np.float64), np.full(n, j, np.int64), np.full(n, float(thr_top[j])), False
    cols = np.array({"BEST-A": K.A_IDX, "BEST-B": K.B_IDX, "BEST": tuple(range(K.NR))}[test])
    sub = ev[:, cols]
    k = np.nanargmax(np.where(np.isfinite(sub), sub, -np.inf), axis=1)
    return sub[np.arange(n), k].astype(np.float64), cols[k].astype(np.int64), np.zeros(n), True


def sessions_of(und: np.ndarray, day: np.ndarray) -> np.ndarray:
    """Start rows of each (index, day) block plus the end; rows must be grouped by index then day."""
    key = und.astype(np.int64) * 10_000_000 + day.astype(np.int64)
    cut = np.flatnonzero(np.diff(key) != 0) + 1
    return np.concatenate([[0], cut, [len(key)]]).astype(np.int64)


def stats(nets: np.ndarray, days: np.ndarray) -> dict:
    n = int(len(nets))
    if not n:
        return dict(trades=0, win=None, be_win=None, net=0, per_trade=None, worst_day=None, max_dd=0, days=0)
    u, inv = np.unique(days, return_inverse=True)
    seq = np.bincount(inv, weights=nets)
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    w, lo = nets[nets > 0], nets[nets <= 0]
    aw, al = (float(w.mean()) if len(w) else 0.0), (float(-lo.mean()) if len(lo) else 0.0)
    return dict(trades=n, win=round(float((nets > 0).mean()), 4), be_win=(round(al / (aw + al), 4) if aw + al > 0 else None),
                net=round(float(nets.sum())), per_trade=round(float(nets.mean())), worst_day=round(float(seq.min())),
                max_dd=round(float((np.maximum.accumulate(eq) - eq).max())), days=int(len(u)))


def twin_totals(sess: np.ndarray, net: np.ndarray, rows: np.ndarray, cols: np.ndarray, draws: int, rng) -> np.ndarray:
    """Random-entry twins (PREREG gate 2): for each session and exit rule with k of the rule's trades, k labelled rows of
    that session (random moments and sides, without replacement) under the same exit rule."""
    tot = np.zeros(draws)
    if not len(rows):
        return tot
    si = np.searchsorted(sess, rows, side="right") - 1
    order = np.lexsort((cols, si))
    si, cs = si[order], cols[order]
    a = 0
    while a < len(si):
        b = a
        while b < len(si) and si[b] == si[a] and cs[b] == cs[a]:
            b += 1
        k = b - a
        pool = net[sess[si[a]]:sess[si[a] + 1], cs[a]]
        pool = pool[np.isfinite(pool)].astype(np.float64)
        if k >= len(pool):
            tot += pool.sum()
        else:
            R = rng.random((draws, len(pool)))
            cutv = np.partition(R, k - 1, axis=1)[:, k - 1:k]
            tot += (R <= cutv) @ pool
        a = b
    return tot


def twin_p(total: float, tots: np.ndarray) -> float:
    return float((1 + int((tots >= total).sum())) / (len(tots) + 1))


def calibration(p: np.ndarray, net: np.ndarray, bins: int = 10) -> list:
    """Forecast tenths over the labelled (example, rule) cells: forecast win chance, real win rate, break-even win rate
    and net a trade in each tenth (lowest forecasts first)."""
    m = np.isfinite(net) & np.isfinite(p)
    pv, nv = p[m], net[m]
    if not len(pv):
        return []
    edges = np.quantile(pv, np.linspace(0, 1, bins + 1))
    which = np.clip(np.searchsorted(edges, pv, side="right") - 1, 0, bins - 1)
    out = []
    for b in range(bins):
        x = nv[which == b]
        if not len(x):
            continue
        w, lo = x[x > 0], x[x <= 0]
        aw, al = (w.mean() if len(w) else 0.0), (-lo.mean() if len(lo) else 0.0)
        out.append(dict(bin=b + 1, n=int(len(x)), forecast=round(float(pv[which == b].mean()), 4),
                        win=round(float((x > 0).mean()), 4), be_win=(round(float(al / (aw + al)), 4) if aw + al > 0 else None),
                        per_trade=round(float(x.mean()), 1)))
    return out


def auc(p: np.ndarray, net: np.ndarray) -> float | None:
    from sklearn.metrics import roc_auc_score
    m = np.isfinite(net) & np.isfinite(p)
    y = net[m] > 0
    if m.sum() < 50 or y.all() or (~y).all():
        return None
    return round(float(roc_auc_score(y, p[m])), 4)


def bh(p, m_total: int | None = None) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    m = max(m_total or n, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


def gates(o1: dict, p2: dict, p3: dict, q: float) -> dict:
    fails = []
    if not (o1["net"] > 0 and p2["net"] > 0 and p3["net"] > 0):
        fails.append("not positive in O1, P2 and P3")
    if not q <= K.GATE_Q:
        fails.append(f"random-entry BH q {q:.2f} > {K.GATE_Q}")
    if p3["trades"] < K.MIN_FINAL:
        fails.append(f"< {K.MIN_FINAL} final trades")
    return dict(passed=not fails, fails=fails)


# ================================================================================================ data
_EX: dict = {}


def examples(post: bool = False) -> dict:
    """Every index's examples joined (grouped by index, then day, then moment; call row then put row)."""
    if post not in _EX:
        import r156data as DT
        parts = []
        for ui, u in enumerate(K.UNDS):
            e = DT.load(u, post)
            e["und"] = np.full(len(e["day"]), ui, np.int8)
            parts.append(e)
        _EX[post] = {k: np.concatenate([x[k] for x in parts]) for k in parts[0]}
    return _EX[post]


def fold(name: str) -> tuple:
    for n, a, b in K.FOLDS:
        if n == name:
            return a, b
    raise ValueError(name)


def _path(kind: str, name: str, seed=None):
    return K.OUT / (f"{kind}_{name}" + (f"_{seed}" if seed is not None else "") + ".pkl")


def cmd_train(name: str, seed: int):
    import torch
    import r156net as N
    torch.set_num_threads(int(__import__("os").environ.get("R156_THREADS", "1")))
    ex = examples()
    first, last = fold(name)
    tr, _ = N.fold_masks(ex["day"], first, last)
    std = N.Standardiser().fit(ex["tab"][tr])
    model, hist = N.train(ex["seq"][tr], std.apply(ex["tab"][tr]), ex["net"][tr], ex["day"][tr], seed,
                          log=lambda h: print(name, seed, h, flush=True))
    K.OUT.mkdir(parents=True, exist_ok=True)
    _path("model", name, seed).write_bytes(pickle.dumps(dict(state={k: v.numpy() for k, v in model.state_dict().items()},
                                                             std=std, hist=hist, train_rows=int(tr.sum()))))
    print(name, seed, "trained", hist, flush=True)


def load_models(name: str) -> list:
    import torch
    import r156net as N
    out = []
    for seed in K.SEEDS:
        d = pickle.loads(_path("model", name, seed).read_bytes())
        m = N.make_net()
        m.load_state_dict({k: torch.from_numpy(v) for k, v in d["state"].items()})
        m.eval()
        out.append((m, d["std"], d["hist"]))
    return out


def ensemble(models: list, seq: np.ndarray, tab: np.ndarray) -> tuple:
    import r156net as N
    ps, es = [], []
    for m, std, _ in models:
        p, e = N.predict(m, seq, std.apply(tab))
        ps.append(p); es.append(e)
    return np.mean(ps, 0), np.mean(es, 0)


def cmd_forecast(name: str):
    """Ensemble forecasts for the fold's own rows (inputs only) and the TOP thresholds from its training rows."""
    import torch
    import r156net as N
    torch.set_num_threads(4)
    ex = examples()
    first, last = fold(name)
    tr, sc = N.fold_masks(ex["day"], first, last)
    models = load_models(name)
    p_tr, _ = ensemble(models, ex["seq"][tr], ex["tab"][tr])
    thr = np.quantile(p_tr, K.TOP_Q, axis=0)
    p, ev = ensemble(models, ex["seq"][sc], ex["tab"][sc])
    out = dict(rows=np.flatnonzero(sc), p=p.astype(np.float32), ev=ev.astype(np.float32), thr=thr,
               hist=[h for _, _, h in models])
    if name == "final":
        exp = examples(post=True)
        pp, pe = ensemble(models, exp["seq"], exp["tab"])
        out.update(post_p=pp.astype(np.float32), post_ev=pe.astype(np.float32))
    _path("fc", name).write_bytes(pickle.dumps(out))
    print(name, "forecast", int(sc.sum()), "rows; thresholds", np.round(thr[:3], 3), flush=True)


def cmd_trees(name: str):
    """Comparison model (PREREG section 3): gradient trees on the table only, one per exit rule's win label."""
    from sklearn.ensemble import HistGradientBoostingClassifier
    import r156net as N
    ex = examples()
    first, last = fold(name)
    tr, sc = N.fold_masks(ex["day"], first, last)
    p = np.full((int(sc.sum()), K.NR), np.nan, np.float32)
    for j in range(K.NR):
        m = tr & np.isfinite(ex["net"][:, j])
        clf = HistGradientBoostingClassifier(max_depth=3, learning_rate=0.05, max_iter=200, min_samples_leaf=200,
                                             random_state=K.SEED)
        clf.fit(ex["tab"][m], ex["net"][m, j] > 0)
        p[:, j] = clf.predict_proba(ex["tab"][sc])[:, 1]
        print(name, "trees", K.RULE_IDS[j], flush=True)
    _path("trees", name).write_bytes(pickle.dumps(dict(rows=np.flatnonzero(sc), p=p)))


def forecasts(names) -> dict:
    """Forecast arrays aligned with the main examples (NaN where no fold scored the row) and per-row TOP thresholds."""
    ex = examples()
    n = len(ex["day"])
    p = np.full((n, K.NR), np.nan, np.float32)
    ev = np.full((n, K.NR), np.nan, np.float32)
    thr = np.full((n, K.NR), np.nan, np.float32)
    tp = np.full((n, K.NR), np.nan, np.float32)
    for name in names:
        f = pickle.loads(_path("fc", name).read_bytes())
        p[f["rows"]], ev[f["rows"]], thr[f["rows"]] = f["p"], f["ev"], f["thr"]
        if _path("trees", name).exists():
            t = pickle.loads(_path("trees", name).read_bytes())
            tp[t["rows"]] = t["p"]
    return dict(p=p, ev=ev, thr=thr, trees=tp)


def run_rules(ex: dict, fc: dict, mask: np.ndarray, twins: bool, rng=None) -> dict:
    """Every trading rule on the rows in `mask` (whole sessions): stats pooled and per index, plus twins when asked."""
    idx = np.flatnonzero(mask)
    sub = {k: ex[k][idx] for k in ("und", "day", "s", "net", "xr", "side")}
    p, ev, thr = fc["p"][idx], fc["ev"][idx], fc["thr"][idx]
    sess = sessions_of(sub["und"], sub["day"])
    out = {}
    for test in K.TESTS:
        sc, rule, _, strict = scores_for(test, p, ev, np.zeros(K.NR))
        th = np.zeros(len(sc))
        if test.startswith("TOP-"):
            th = thr[:, K.RULE_IDS.index(test[4:])].astype(np.float64)
        rows, cols = walk(sess, sub["s"].astype(np.int64), sc, rule, sub["net"], sub["xr"].astype(np.int64), th, strict,
                          K.MAX_PER_DAY)
        nets = sub["net"][rows, cols].astype(np.float64)
        r = dict(all=stats(nets, sub["day"][rows]))
        r["by_und"] = {u: stats(nets[sub["und"][rows] == i], sub["day"][rows][sub["und"][rows] == i])
                       for i, u in enumerate(K.UNDS)}
        r["calls"] = round(float(sub["side"][rows].mean()), 3) if len(rows) else None
        hr = (sub["s"][rows].astype(int) + 1 + 15) // 60 + 9
        r["by_hour"] = {int(h): [int((hr == h).sum()), round(float(nets[hr == h].sum()))] for h in np.unique(hr)}
        r["by_rule"] = {K.RULE_IDS[j]: int((cols == j).sum()) for j in np.unique(cols)} if test.startswith("BEST") else None
        if twins:
            tt = twin_totals(sess, sub["net"], rows, cols, K.TWINS, rng)
            r["twin"] = dict(p=round(twin_p(float(nets.sum()), tt), 4), mean=round(float(tt.mean())))
        out[test] = r
    return out


def describe(ex: dict, fc: dict, mask: np.ndarray) -> dict:
    """Forecast quality on the rows in `mask`: AUC per exit rule (network, trees), calibration tenths per reading, and the
    unconditional scalp (every labelled moment) for reference."""
    idx = np.flatnonzero(mask)
    net, p, tp = ex["net"][idx], fc["p"][idx], fc["trees"][idx]
    out = dict(auc={r: dict(net=auc(p[:, j], net[:, j]), trees=auc(tp[:, j], net[:, j])) for j, r in enumerate(K.RULE_IDS)},
               calibration={rd: calibration(p[:, cols], net[:, cols]) for rd, cols in (("A", list(K.A_IDX)), ("B", list(K.B_IDX)))},
               base={})
    for rd, cols in (("A", list(K.A_IDX)), ("B", list(K.B_IDX))):
        x = net[:, cols]
        x = x[np.isfinite(x)]
        w, lo = x[x > 0], x[x <= 0]
        out["base"][rd] = dict(n=int(len(x)), win=round(float((x > 0).mean()), 4),
                               be_win=round(float(-lo.mean() / (w.mean() - lo.mean())), 4), per_trade=round(float(x.mean()), 1))
    return out


def period_mask(ex: dict, per: str) -> np.ndarray:
    a, b = K.PERIODS[per]
    return (ex["day"] >= a.toordinal()) & (ex["day"] <= b.toordinal())


def cmd_design():
    ex = examples()
    fc = forecasts(K.DESIGN_FOLDS)
    out = {}
    for per in ("O1", "P2"):
        m = period_mask(ex, per)
        out[per] = dict(rules=run_rules(ex, fc, m, False), forecast=describe(ex, fc, m))
        print(per, "done", flush=True)
    out["examples"] = dict(total=int(len(ex["day"])), by_und={u: int((ex["und"] == i).sum()) for i, u in enumerate(K.UNDS)},
                           labelled=round(float(np.isfinite(ex["net"]).mean()), 4))
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(out, default=str))


def cmd_final():
    if K.FINAL_FLAG.exists():
        raise SystemExit("R156 final already read once: " + K.FINAL_FLAG.read_text())
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    ex = examples()
    fc = forecasts(("final",))
    rng = np.random.default_rng(K.SEED)
    m = period_mask(ex, "P3")
    out = {"P3": dict(rules=run_rules(ex, fc, m, True, rng), forecast=describe(ex, fc, m))}
    exp = examples(post=True)
    f = pickle.loads(_path("fc", "final").read_bytes())
    fcp = dict(p=f["post_p"], ev=f["post_ev"], thr=np.tile(f["thr"], (len(exp["day"]), 1)).astype(np.float32),
               trees=np.full_like(f["post_p"], np.nan))
    out["P4"] = dict(rules=run_rules(exp, fcp, np.ones(len(exp["day"]), bool), False))
    (K.RESULTS / "final.json").write_text(json.dumps(out, default=str))
    print("final read", flush=True)


def cmd_importance():
    """Shuffle test (PREREG section 6): the win-loss increase on fold 2025's P2 rows (Apr - Dec 2025) when one input
    group is shuffled across examples."""
    import torch
    import torch.nn.functional as F
    import r156net as N
    torch.set_num_threads(4)
    ex = examples()
    first, last = fold("2025")
    m = (ex["day"] >= K4.P2_START.toordinal()) & (ex["day"] <= last.toordinal())
    idx = np.flatnonzero(m)
    rng = np.random.default_rng(K.SEED)
    if len(idx) > 60_000:
        idx = np.sort(rng.choice(idx, 60_000, replace=False))
    seq, tab, net = ex["seq"][idx], ex["tab"][idx], ex["net"][idx]
    win, _, mask = N.targets(net)
    models = load_models("2025")

    def loss(sq, tb):
        p, _ = ensemble(models, sq, tb)
        p = np.clip(p, 1e-6, 1 - 1e-6)
        b = -(win * np.log(p) + (1 - win) * np.log(1 - p))
        return float((b * mask).sum() / mask.sum())
    base = loss(seq, tab)
    out = {"base_loss": round(base, 5), "rows": int(len(idx)), "groups": {}}
    perm = rng.permutation(len(idx))
    out["groups"]["the last hour of 1-minute bars"] = round(loss(seq[perm], tab) - base, 5)
    for g, cols in K.TAB_GROUPS.items():
        tb = tab.copy()
        tb[:, list(cols)] = tab[perm][:, list(cols)]
        out["groups"][g] = round(loss(seq, tb) - base, 5)
        print(g, out["groups"][g], flush=True)
    (K.RESULTS / "importance.json").write_text(json.dumps(out, indent=1))


def cmd_decide() -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    ps = [f["P3"]["rules"][t]["twin"]["p"] for t in K.TESTS]
    q = bh(ps, len(ps) + K.N_PRIOR)
    q_own = bh(ps)
    out = {}
    for t, qa, qo, p in zip(K.TESTS, q, q_own, ps):
        o1, p2, p3 = d["O1"]["rules"][t]["all"], d["P2"]["rules"][t]["all"], f["P3"]["rules"][t]["all"]
        g = gates(o1, p2, p3, float(qa))
        g.update(twin_p=p, q=round(float(qa), 4), q_r156=round(float(qo), 4), O1=o1, P2=p2, P3=p3,
                 P4=f["P4"]["rules"][t]["all"], twin_mean=f["P3"]["rules"][t]["twin"]["mean"])
        out[t] = g
    out["_m"] = dict(r156=len(ps), prior=K.N_PRIOR, total=len(ps) + K.N_PRIOR,
                     passed=sum(1 for t in K.TESTS if out[t]["passed"]))
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=0, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "train":
        cmd_train(sys.argv[2], int(sys.argv[3]))
    elif cmd == "forecast":
        cmd_forecast(sys.argv[2])
    elif cmd == "trees":
        cmd_trees(sys.argv[2])
    elif cmd == "design":
        cmd_design()
    elif cmd == "final":
        cmd_final()
    elif cmd == "importance":
        cmd_importance()
    elif cmd == "decide":
        print(json.dumps(cmd_decide()["_m"], indent=1))
    else:
        raise SystemExit(__doc__)
