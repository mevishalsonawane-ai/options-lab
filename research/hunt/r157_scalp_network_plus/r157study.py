"""R157 study (PREREG.md): R156's scalp network with the cross-index sequence, 28 exits x 2 lot sizes, and the TOP99 rule.

    python research/hunt/r157_scalp_network_plus/r157data.py build                 # extra examples (and R157_POST=1)
    python research/hunt/r157_scalp_network_plus/r157study.py train FOLD SEED
    python research/hunt/r157_scalp_network_plus/r157study.py forecast FOLD
    python research/hunt/r157_scalp_network_plus/r157study.py design               # O1 + P2
    python research/hunt/r157_scalp_network_plus/r157study.py final                # P3 once (+ P4), twins
    python research/hunt/r157_scalp_network_plus/r157study.py importance
    python research/hunt/r157_scalp_network_plus/r157study.py decide

The walk, twins, statistics, calibration, AUC, BH and gates are R156's functions (tested there); the pure parts added
here (scores, top share, the network's shape) are covered by test_r157.py.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import datetime, timezone

import numpy as np

import r157cfg as K
import r156study as S6
import r156net as N

K6 = K.K6


# ================================================================================================ pure
def scores_for(test: str, p: np.ndarray, ev: np.ndarray, thr: np.ndarray) -> tuple:
    """(score (n,), label column (n,), threshold (n,), strict) of a rule 'EV-r@L', 'TOP99-r@L' or 'BEST@L'."""
    name, lots = test.rsplit("@", 1)
    lots = int(lots)
    n = len(p)
    if name == "BEST":
        cols = np.arange(K.NR) + K.LOTS.index(lots) * K.NR
        sub = np.where(np.isfinite(ev[:, cols]), ev[:, cols], -np.inf)
        k = sub.argmax(1)
        return sub[np.arange(n), k].astype(np.float64), cols[k].astype(np.int64), np.zeros(n), True
    kind, rule = name.split("-", 1)
    c = K.col(rule, lots)
    if kind == "EV":
        return ev[:, c].astype(np.float64), np.full(n, c, np.int64), np.zeros(n), True
    return p[:, c].astype(np.float64), np.full(n, c, np.int64), thr[:, c].astype(np.float64), False


def top_share(p: np.ndarray, net: np.ndarray, share: float = 0.01) -> dict | None:
    """The top `share` of forecasts over the labelled cells: forecast, real win rate, win needed, net a trade."""
    m = np.isfinite(net) & np.isfinite(p)
    pv, nv = p[m], net[m]
    if len(pv) < 100:
        return None
    x = nv[pv >= np.quantile(pv, 1 - share)]
    w, lo = x[x > 0], x[x <= 0]
    aw, al = (w.mean() if len(w) else 0.0), (-lo.mean() if len(lo) else 0.0)
    return dict(n=int(len(x)), win=round(float((x > 0).mean()), 4),
                be_win=(round(float(al / (aw + al)), 4) if aw + al > 0 else None), per_trade=round(float(x.mean()), 1))


def make_net():
    import torch
    from torch import nn

    class ScalpNetPlus(nn.Module):
        def __init__(self):
            super().__init__()
            self.conv = nn.Sequential(nn.Conv1d(K.SEQ_CH, 32, 5, padding=2), nn.ReLU(),
                                      nn.Conv1d(32, 32, 5, padding=2), nn.ReLU(),
                                      nn.Conv1d(32, 32, 3, padding=1), nn.ReLU())
            self.tab = nn.Sequential(nn.Linear(K6.N_TAB, 64), nn.ReLU())
            self.head = nn.Sequential(nn.Linear(128, 128), nn.ReLU(), nn.Dropout(K6.DROPOUT), nn.Linear(128, 2 * K.NCOL))

        def forward(self, seq, tab):
            h = self.conv(seq.transpose(1, 2))
            out = self.head(torch.cat([h.mean(2), h.amax(2), self.tab(tab)], 1))
            return out[:, :K.NCOL], out[:, K.NCOL:]

    return ScalpNetPlus()


# ================================================================================================ data
_EX: dict = {}


def examples(post: bool = False) -> dict:
    """R156's examples with the sequence widened to 13 channels and the 56 label columns."""
    if post not in _EX:
        import r156data as DT6
        import r157data as DT
        parts = []
        for ui, u in enumerate(K.UNDS):
            e, x = DT6.load(u, post), DT.load(u, post)
            parts.append(dict(day=e["day"], s=e["s"], side=e["side"], tab=e["tab"], und=np.full(len(e["day"]), ui, np.int8),
                              seq=np.concatenate([e["seq"], x["xseq"]], axis=2), net=x["net"], xr=x["xr"]))
        _EX[post] = {k: np.concatenate([q[k] for q in parts]) for k in parts[0]}
    return _EX[post]


def _path(kind: str, name: str, seed=None):
    return K.OUT / (f"{kind}_{name}" + (f"_{seed}" if seed is not None else "") + ".pkl")


def cmd_train(name: str, seed: int):
    import torch
    torch.set_num_threads(1)
    ex = examples()
    first, last = S6.fold(name)
    tr, _ = N.fold_masks(ex["day"], first, last)
    std = N.Standardiser().fit(ex["tab"][tr])
    model, hist = N.train(ex["seq"][tr], std.apply(ex["tab"][tr]), ex["net"][tr], ex["day"][tr], seed,
                          log=lambda h: print(name, seed, h, flush=True), factory=make_net)
    _path("model", name, seed).write_bytes(pickle.dumps(dict(state={k: v.numpy() for k, v in model.state_dict().items()},
                                                             std=std, hist=hist)))
    print(name, seed, "trained", flush=True)


def load_models(name: str) -> list:
    import torch
    out = []
    for seed in K.SEEDS:
        d = pickle.loads(_path("model", name, seed).read_bytes())
        m = make_net()
        m.load_state_dict({k: torch.from_numpy(v) for k, v in d["state"].items()})
        m.eval()
        out.append((m, d["std"], d["hist"]))
    return out


def cmd_forecast(name: str):
    import torch
    torch.set_num_threads(4)
    ex = examples()
    first, last = S6.fold(name)
    tr, sc = N.fold_masks(ex["day"], first, last)
    models = load_models(name)
    p_tr, _ = S6.ensemble(models, ex["seq"][tr], ex["tab"][tr])
    thr = np.quantile(p_tr, K.TOP_Q, axis=0)
    del p_tr
    p, ev = S6.ensemble(models, ex["seq"][sc], ex["tab"][sc])
    out = dict(rows=np.flatnonzero(sc), p=p.astype(np.float32), ev=ev.astype(np.float32), thr=thr, hist=[h for _, _, h in models])
    if name == "final":
        exp = examples(post=True)
        pp, pe = S6.ensemble(models, exp["seq"], exp["tab"])
        out.update(post_p=pp.astype(np.float32), post_ev=pe.astype(np.float32))
    _path("fc", name).write_bytes(pickle.dumps(out))
    print(name, "forecast", int(sc.sum()), flush=True)


def forecasts(names) -> dict:
    ex = examples()
    n = len(ex["day"])
    out = {k: np.full((n, K.NCOL), np.nan, np.float32) for k in ("p", "ev", "thr")}
    for name in names:
        f = pickle.loads(_path("fc", name).read_bytes())
        out["p"][f["rows"]], out["ev"][f["rows"]], out["thr"][f["rows"]] = f["p"], f["ev"], f["thr"]
    return out


def run_rules(ex: dict, fc: dict, mask: np.ndarray, twins: bool, rng=None) -> dict:
    idx = np.flatnonzero(mask)
    sub = {k: ex[k][idx] for k in ("und", "day", "s", "net", "xr", "side")}
    p, ev, thr = fc["p"][idx], fc["ev"][idx], fc["thr"][idx]
    sess = S6.sessions_of(sub["und"], sub["day"])
    xr = sub["xr"].astype(np.int64)
    s64 = sub["s"].astype(np.int64)
    out = {}
    for test in K.TESTS:
        sc, colv, th, strict = scores_for(test, p, ev, thr)
        rows, cols = S6.walk(sess, s64, sc, colv, sub["net"], xr, th, strict, K6.MAX_PER_DAY)
        nets = sub["net"][rows, cols].astype(np.float64)
        r = dict(all=S6.stats(nets, sub["day"][rows]))
        r["by_und"] = {u: S6.stats(nets[sub["und"][rows] == i], sub["day"][rows][sub["und"][rows] == i])
                       for i, u in enumerate(K.UNDS)}
        r["calls"] = round(float(sub["side"][rows].mean()), 3) if len(rows) else None
        if test.startswith("BEST"):
            r["by_rule"] = {K.RULE_IDS[j % K.NR]: int((cols == j).sum()) for j in np.unique(cols)}
        if twins:
            tt = S6.twin_totals(sess, sub["net"], rows, cols, K.TWINS, rng)
            r["twin"] = dict(p=round(S6.twin_p(float(nets.sum()), tt), 4), mean=round(float(tt.mean())))
        out[test] = r
    return out


def describe(ex: dict, fc: dict, mask: np.ndarray) -> dict:
    """AUC per label column; per group x lots: the unconditional scalp, the forecast's tenths and its top 1%."""
    idx = np.flatnonzero(mask)
    net, p = ex["net"][idx], fc["p"][idx]
    out = dict(auc={f"{r}@{n}": S6.auc(p[:, K.col(r, n)], net[:, K.col(r, n)]) for n in K.LOTS for r in K.RULE_IDS}, groups={})
    for n in K.LOTS:
        for g, js in K.GROUPS.items():
            cols = [K.LOTS.index(n) * K.NR + j for j in js]
            x = net[:, cols]
            x = x[np.isfinite(x)]
            w, lo = x[x > 0], x[x <= 0]
            out["groups"][f"{g}@{n}"] = dict(
                base=dict(n=int(len(x)), win=round(float((x > 0).mean()), 4),
                          be_win=round(float(-lo.mean() / (w.mean() - lo.mean())), 4), per_trade=round(float(x.mean()), 1)),
                tenths=S6.calibration(p[:, cols], net[:, cols]), top1=top_share(p[:, cols], net[:, cols]))
    return out


def cmd_design():
    ex = examples()
    fc = forecasts(K.DESIGN_FOLDS)
    out = {}
    for per in ("O1", "P2"):
        m = S6.period_mask(ex, per)
        out[per] = dict(rules=run_rules(ex, fc, m, False), forecast=describe(ex, fc, m))
        print(per, "done", flush=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(out, default=str))


def cmd_final():
    if K.FINAL_FLAG.exists():
        raise SystemExit("R157 final already read once: " + K.FINAL_FLAG.read_text())
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    ex = examples()
    fc = forecasts(("final",))
    rng = np.random.default_rng(K.SEED)
    m = S6.period_mask(ex, "P3")
    out = {"P3": dict(rules=run_rules(ex, fc, m, True, rng), forecast=describe(ex, fc, m))}
    exp = examples(post=True)
    f = pickle.loads(_path("fc", "final").read_bytes())
    fcp = dict(p=f["post_p"], ev=f["post_ev"], thr=np.tile(f["thr"], (len(exp["day"]), 1)).astype(np.float32))
    out["P4"] = dict(rules=run_rules(exp, fcp, np.ones(len(exp["day"]), bool), False))
    (K.RESULTS / "final.json").write_text(json.dumps(out, default=str))
    print("final read", flush=True)


def cmd_importance():
    """Shuffle test on fold 2025's P2 rows (Apr - Dec 2025): own sequence, cross-index channels, table groups."""
    import torch
    torch.set_num_threads(4)
    ex = examples()
    _, last = S6.fold("2025")
    idx = np.flatnonzero((ex["day"] >= K.K4.P2_START.toordinal()) & (ex["day"] <= last.toordinal()))
    rng = np.random.default_rng(K.SEED)
    idx = np.sort(rng.choice(idx, min(60_000, len(idx)), replace=False))
    seq, tab, net = ex["seq"][idx], ex["tab"][idx], ex["net"][idx]
    win, _, mask = N.targets(net)
    models = load_models("2025")

    def loss(sq, tb):
        p, _ = S6.ensemble(models, sq, tb)
        p = np.clip(p, 1e-6, 1 - 1e-6)
        return float(((-(win * np.log(p) + (1 - win) * np.log(1 - p))) * mask).sum() / mask.sum())
    base = loss(seq, tab)
    perm = rng.permutation(len(idx))
    out = {"base_loss": round(base, 5), "rows": int(len(idx)), "groups": {}}
    for g, chans in (("the traded index's last hour of 1-minute bars", slice(0, 5)), ("the other four indices' last hour", slice(5, 13))):
        sq = seq.copy()
        sq[:, :, chans] = seq[perm][:, :, chans]
        out["groups"][g] = round(loss(sq, tab) - base, 5)
    for g, cols in K6.TAB_GROUPS.items():
        tb = tab.copy()
        tb[:, list(cols)] = tab[perm][:, list(cols)]
        out["groups"][g] = round(loss(seq, tb) - base, 5)
    (K.RESULTS / "importance.json").write_text(json.dumps(out, indent=1))
    print(out, flush=True)


def cmd_decide() -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    ps = [f["P3"]["rules"][t]["twin"]["p"] for t in K.TESTS]
    q = S6.bh(ps, len(ps) + K.N_PRIOR)
    out = {}
    for t, qa, p in zip(K.TESTS, q, ps):
        o1, p2, p3 = d["O1"]["rules"][t]["all"], d["P2"]["rules"][t]["all"], f["P3"]["rules"][t]["all"]
        g = S6.gates(o1, p2, p3, float(qa))
        g.update(twin_p=p, q=round(float(qa), 4), O1=o1, P2=p2, P3=p3, P4=f["P4"]["rules"][t]["all"],
                 twin_mean=f["P3"]["rules"][t]["twin"]["mean"])
        out[t] = g
    out["_m"] = dict(r157=len(ps), prior=K.N_PRIOR, total=len(ps) + K.N_PRIOR, passed=sum(1 for t in K.TESTS if out[t]["passed"]))
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=0, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "train":
        cmd_train(sys.argv[2], int(sys.argv[3]))
    elif cmd == "forecast":
        cmd_forecast(sys.argv[2])
    elif cmd in ("design", "final", "importance"):
        {"design": cmd_design, "final": cmd_final, "importance": cmd_importance}[cmd]()
    elif cmd == "decide":
        print(json.dumps(cmd_decide()["_m"], indent=1))
    else:
        raise SystemExit(__doc__)
