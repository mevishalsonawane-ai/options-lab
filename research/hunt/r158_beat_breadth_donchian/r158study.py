"""R158 study (PREREG.md): a network trained on Breadth Donchian's exit (XL) at every moment; its own entries and its
picks among R72's entries, judged against R72.

    python research/hunt/r158_beat_breadth_donchian/r158data.py build            # XL labels (and R158_POST=1)
    python research/hunt/r158_beat_breadth_donchian/r158study.py train FOLD SEED
    python research/hunt/r158_beat_breadth_donchian/r158study.py forecast FOLD
    python research/hunt/r158_beat_breadth_donchian/r158study.py design          # O1 + P2 (R72 parity asserted)
    python research/hunt/r158_beat_breadth_donchian/r158study.py final           # P3 once (+ P4), twins
    python research/hunt/r158_beat_breadth_donchian/r158study.py importance
    python research/hunt/r158_beat_breadth_donchian/r158study.py decide

Statistics, twins, calibration, AUC and BH are R156's functions; the pure parts added here (the side-wise walk, the
picker's keep function, the beat-R72 gate) are covered by test_r158.py.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timezone

import numpy as np
from numba import njit

import r158cfg as K
import r156study as S6
import r156net as N

K4, K6 = K.K4, K.K6


# ================================================================================================ pure
@njit(cache=True)
def walk_sides(sess, s, score, thr, net, xr, max_per_side):
    """R72's taking rule on forecasts: rows are (call, put) pairs per moment in time order inside each session
    (sess[i]..sess[i+1]). A side is taken at a moment when its score is > its threshold, its label exists, that side has
    no open position (entry row s + 1 after the side's last exit row) and has had fewer than max_per_side fills."""
    out = np.empty(len(s), np.int64)
    n = 0
    for i in range(len(sess) - 1):
        busy = np.array([-1, -1])
        cnt = np.array([0, 0])
        a = sess[i]
        while a + 1 < sess[i + 1]:
            for k in range(2):
                r = a + k
                v = score[r]
                if v == v and v > thr[r] and cnt[k] < max_per_side and s[r] + 1 > busy[k]:
                    x = net[r]
                    if x == x:
                        out[n] = r
                        n += 1
                        busy[k] = xr[r]
                        cnt[k] += 1
            a += 2
    return out[:n]


def beats(rule: dict, b0: dict) -> bool:
    """A higher win rate AND a higher net than R72 in one period."""
    return bool(rule["trades"] and rule["win"] is not None and rule["win"] > b0["win"] and rule["net"] > b0["net"])


def gates(per: dict, b0: dict, q: float, standalone: bool) -> dict:
    """PREREG section 3. per / b0: {period: stats}."""
    fails = []
    need = ("O1", "P2", "P3") if standalone else ("P2", "P3")
    if not all(per[p]["net"] > 0 for p in need):
        fails.append("not positive in " + ", ".join(need))
    if not (beats(per["P2"], b0["P2"]) and beats(per["P3"], b0["P3"])):
        fails.append("does not beat Breadth Donchian on win rate and net in P2 and P3")
    if not q <= K.GATE_Q:
        fails.append(f"twin BH q {q:.2f} > {K.GATE_Q}")
    if per["P3"]["trades"] < K.MIN_FINAL:
        fails.append(f"< {K.MIN_FINAL} final trades")
    return dict(passed=not fails, fails=fails)


def full_stats(nets: np.ndarray, days: np.ndarray) -> dict:
    st = S6.stats(nets, days)
    if len(nets):
        st["ex_top5"] = round(float(nets.sum() - np.sort(nets)[::-1][:5].sum()))
        st["largest_loss"] = round(float(nets.min()))
    return st


def make_net():
    import torch
    from torch import nn

    class XLNet(nn.Module):
        def __init__(self):
            super().__init__()
            self.conv = nn.Sequential(nn.Conv1d(K.SEQ_CH, 32, 5, padding=2), nn.ReLU(),
                                      nn.Conv1d(32, 32, 5, padding=2), nn.ReLU(),
                                      nn.Conv1d(32, 32, 3, padding=1), nn.ReLU())
            self.tab = nn.Sequential(nn.Linear(K6.N_TAB, 64), nn.ReLU())
            self.head = nn.Sequential(nn.Linear(128, 128), nn.ReLU(), nn.Dropout(K6.DROPOUT), nn.Linear(128, 2))

        def forward(self, seq, tab):
            h = self.conv(seq.transpose(1, 2))
            out = self.head(torch.cat([h.mean(2), h.amax(2), self.tab(tab)], 1))
            return out[:, :1], out[:, 1:]

    return XLNet()


# ================================================================================================ data
_EX: dict = {}


def examples(post: bool = False) -> dict:
    if post not in _EX:
        import r156data as DT6
        import r157data as DT7
        import r158data as DT
        parts = []
        for ui, u in enumerate(K.UNDS):
            e, x, y = DT6.load(u, post), DT7.load(u, post), DT.load(u, post)
            parts.append(dict(day=e["day"], s=e["s"], side=e["side"], tab=e["tab"], und=np.full(len(e["day"]), ui, np.int8),
                              seq=np.concatenate([e["seq"], x["xseq"]], axis=2), net=y["net"], xr=y["xr"]))
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
    tr &= np.isfinite(ex["net"])
    std = N.Standardiser().fit(ex["tab"][tr])
    model, hist = N.train(ex["seq"][tr], std.apply(ex["tab"][tr]), ex["net"][tr][:, None], ex["day"][tr], seed,
                          log=lambda h: print(name, seed, h, flush=True), factory=make_net)
    _path("model", name, seed).write_bytes(pickle.dumps(dict(state={k: v.numpy() for k, v in model.state_dict().items()},
                                                             std=std, hist=hist)))


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
    _, e_tr = S6.ensemble(models, ex["seq"][tr], ex["tab"][tr])
    thr = {k: float(np.quantile(e_tr[:, 0], q)) for k, q in K.TOP.items()}
    thr["PICK50"] = float(np.median(e_tr[:, 0]))
    p, ev = S6.ensemble(models, ex["seq"][sc], ex["tab"][sc])
    out = dict(rows=np.flatnonzero(sc), p=p[:, 0].astype(np.float32), ev=ev[:, 0].astype(np.float32), thr=thr,
               hist=[h for _, _, h in models])
    if name == "final":
        exp = examples(post=True)
        pp, pe = S6.ensemble(models, exp["seq"], exp["tab"])
        out.update(post_p=pp[:, 0].astype(np.float32), post_ev=pe[:, 0].astype(np.float32))
    _path("fc", name).write_bytes(pickle.dumps(out))
    print(name, "forecast", int(sc.sum()), thr, flush=True)


def forecasts(names) -> dict:
    """Per-row forecasts and thresholds aligned with the main examples (NaN where no fold scored the row)."""
    n = len(examples()["day"])
    out = {k: np.full(n, np.nan, np.float32) for k in ("p", "ev", "NET-TOP5", "NET-TOP1", "PICK50")}
    for name in names:
        f = pickle.loads(_path("fc", name).read_bytes())
        out["p"][f["rows"]], out["ev"][f["rows"]] = f["p"], f["ev"]
        for k, v in f["thr"].items():
            out[k][f["rows"]] = v
    return out


# ================================================================================================ R72
_R72: dict = {}


def r72_units(lo: date, hi: date) -> list:
    import r155study as S5
    M = S5.mods()
    key = (lo, hi)
    if key not in _R72:
        _R72[key] = M["S52"].units("R72", lo, hi)
    return _R72[key]


def r72_walk(us: list, keep=None) -> list:
    """R72's own walk (R106's run_rule) on the candidates `keep(und, day, er, side)` lets through (None = all)."""
    import r155study as S5
    M = S5.mods()
    uf, pr = [], []
    for key, cands, res, how in us:
        ix = [i for i, c in enumerate(cands) if keep is None or keep(key[0], key[1], c["er"], c["side"])]
        uf.append((key, [cands[i] for i in ix], [res[i] for i in ix], how))
        pr.append([res[i] for i in ix])
    return M["S"].run_rule("R72", uf, pr, None)


def tstats(trades: list, lo: date, hi: date) -> dict:
    t = [x for x in trades if lo <= x["day"] <= hi]
    return full_stats(np.array([x["net"] for x in t], float), np.array([x["day"].toordinal() for x in t]))


def lookup(ex: dict, fc: dict) -> dict:
    """{(und index, day ordinal, s, side 1/0): row} for the rows with a forecast."""
    rows = np.flatnonzero(np.isfinite(fc["ev"]))
    return {(int(ex["und"][r]), int(ex["day"][r]), int(ex["s"][r]), int(ex["side"][r])): int(r) for r in rows}


def pick_fn(ex: dict, fc: dict, test: str):
    lk = lookup(ex, fc)

    def keep(und, day, er, side):
        r = lk.get((K.UNDS.index(und), day.toordinal(), er - 1, 1 if side == "C" else 0))
        if r is None:
            return True
        if test == "PICK":
            return bool(fc["ev"][r] > 0)
        return bool(fc["ev"][r] >= fc["PICK50"][r])
    return keep


# ================================================================================================ evaluation
def standalone(ex: dict, fc: dict, mask: np.ndarray, test: str) -> tuple:
    idx = np.flatnonzero(mask)
    sub = {k: ex[k][idx] for k in ("und", "day", "s", "net", "xr", "side")}
    sess = S6.sessions_of(sub["und"], sub["day"])
    thr = np.zeros(len(idx)) if test == "NET-EV" else fc[test][idx].astype(np.float64)
    rows = walk_sides(sess, sub["s"].astype(np.int64), fc["ev"][idx].astype(np.float64), thr, sub["net"],
                      sub["xr"].astype(np.int64), K.MAX_PER_SIDE)
    return sub, sess, rows


def describe_rows(sub: dict, rows: np.ndarray) -> dict:
    nets = sub["net"][rows].astype(np.float64)
    r = dict(all=full_stats(nets, sub["day"][rows]))
    r["by_und"] = {u: S6.stats(nets[sub["und"][rows] == i], sub["day"][rows][sub["und"][rows] == i]) for i, u in enumerate(K.UNDS)}
    r["calls"] = round(float(sub["side"][rows].mean()), 3) if len(rows) else None
    hr = (sub["s"][rows].astype(int) + 16) // 60 + 9
    r["by_hour"] = {int(h): [int((hr == h).sum()), round(float(nets[hr == h].sum()))] for h in np.unique(hr)}
    return r


def forecast_quality(ex: dict, fc: dict, mask: np.ndarray) -> dict:
    idx = np.flatnonzero(mask)
    net, p, ev = ex["net"][idx], fc["p"][idx], fc["ev"][idx]
    m = np.isfinite(net)
    x = net[m]
    w, lo = x[x > 0], x[x <= 0]
    return dict(auc=S6.auc(p, net), base=dict(n=int(len(x)), win=round(float((x > 0).mean()), 4),
                                              be_win=round(float(-lo.mean() / (w.mean() - lo.mean())), 4),
                                              per_trade=round(float(x.mean()), 1)),
                tenths_by_net=S6.calibration(ev, net), tenths_by_win=S6.calibration(p, net))


def overlap(sub: dict, rows: np.ndarray, r72: list) -> int:
    """How many of a standalone rule's trades are also R72 entries (same index, day, entry minute and side)."""
    keys = {(K.UNDS.index(t["und"]), t["day"].toordinal(), t["er_sig"] - 1, 1 if t["side"] == "C" else 0) for t in r72}
    return int(sum((int(sub["und"][r]), int(sub["day"][r]), int(sub["s"][r]), int(sub["side"][r])) in keys for r in rows))


def cmd_design():
    ex = examples()
    fc = forecasts(K.DESIGN_FOLDS)
    us = r72_units(K4.ARM_START, K4.P2_END)
    b0 = r72_walk(us)
    n0, net0 = len(b0), round(sum(t["net"] for t in b0))
    assert n0 == K.PUBLISHED["design"][0] and abs(net0 - K.PUBLISHED["design"][1]) <= 1, (n0, net0)
    print("R72 design parity reproduced", n0, net0, flush=True)
    out = {"parity": [n0, net0], "B0": {p: tstats(b0, *K.PERIODS[p]) for p in ("P2",)}}
    out["B0"]["P1arm"] = tstats(b0, K4.ARM_START, K4.P1_END)
    for per in ("O1", "P2"):
        m = S6.period_mask(ex, per)
        out[per] = dict(forecast=forecast_quality(ex, fc, m), rules={})
        for t in K.STANDALONE:
            sub, sess, rows = standalone(ex, fc, m, t)
            out[per]["rules"][t] = dict(describe_rows(sub, rows), also_r72=overlap(sub, rows, b0))
        if per == "P2":
            for t in K.PICKS:
                tr = r72_walk(us, pick_fn(ex, fc, t))
                out[per]["rules"][t] = dict(all=tstats(tr, *K.PERIODS["P2"]))
        print(per, "done", flush=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(out, default=str))


def cmd_final():
    import r155study as S5
    if K.FINAL_FLAG.exists():
        raise SystemExit("R158 final already read once: " + K.FINAL_FLAG.read_text())
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    M = S5.mods()
    M["C"].open_final()
    ex = examples()
    fc = forecasts(("final",))
    rng = np.random.default_rng(K.SEED)
    lo, hi = K.PERIODS["P3"]
    us = r72_units(lo, hi)
    b0 = r72_walk(us)
    n0, net0 = len(b0), round(sum(t["net"] for t in b0))
    assert n0 == K.PUBLISHED["final"][0] and abs(net0 - K.PUBLISHED["final"][1]) <= 1, (n0, net0)
    print("R72 final parity reproduced", n0, net0, flush=True)
    m = S6.period_mask(ex, "P3")
    out = {"parity": [n0, net0], "B0": {"P3": tstats(b0, lo, hi)}, "P3": dict(forecast=forecast_quality(ex, fc, m), rules={})}
    out["B0"]["P3"]["by_und"] = {u: tstats([t for t in b0 if t["und"] == u], lo, hi) for u in K.UNDS}
    for t in K.STANDALONE:
        sub, sess, rows = standalone(ex, fc, m, t)
        r = dict(describe_rows(sub, rows), also_r72=overlap(sub, rows, b0))
        tt = S6.twin_totals(sess, sub["net"][:, None], rows, np.zeros(len(rows), np.int64), K.TWINS, rng)
        r["twin"] = dict(p=round(S6.twin_p(float(sub["net"][rows].sum()), tt), 4), mean=round(float(tt.mean())))
        out["P3"]["rules"][t] = r
    base = np.array([t["net"] for t in b0], float)
    for t in K.PICKS:
        tr = r72_walk(us, pick_fn(ex, fc, t))
        r = dict(all=tstats(tr, lo, hi), by_und={u: tstats([x for x in tr if x["und"] == u], lo, hi) for u in K.UNDS})
        p_, mean = S5.subset_twin(base, len(tr), float(sum(x["net"] for x in tr)), K.TWINS, rng)
        r["twin"] = dict(p=round(p_, 4), mean=round(mean))
        out["P3"]["rules"][t] = r
    # post window (14-19 Oct): standalone rules only (descriptive)
    exp = examples(post=True)
    f = pickle.loads(_path("fc", "final").read_bytes())
    fcp = dict(p=f["post_p"], ev=f["post_ev"], **{k: np.full(len(exp["day"]), v, np.float32) for k, v in f["thr"].items()})
    out["P4"] = {}
    for t in K.STANDALONE:
        sub, sess, rows = standalone(exp, fcp, np.ones(len(exp["day"]), bool), t)
        out["P4"][t] = describe_rows(sub, rows)["all"]
    (K.RESULTS / "final.json").write_text(json.dumps(out, default=str))
    print("final read", flush=True)


def cmd_importance():
    import torch
    torch.set_num_threads(4)
    ex = examples()
    _, last = S6.fold("2025")
    idx = np.flatnonzero((ex["day"] >= K4.P2_START.toordinal()) & (ex["day"] <= last.toordinal()) & np.isfinite(ex["net"]))
    rng = np.random.default_rng(K.SEED)
    idx = np.sort(rng.choice(idx, min(60_000, len(idx)), replace=False))
    seq, tab, net = ex["seq"][idx], ex["tab"][idx], ex["net"][idx]
    val = np.clip(net / 1000.0, -K6.CLIP, K6.CLIP)
    models = load_models("2025")

    def loss(sq, tb):
        _, ev = S6.ensemble(models, sq, tb)
        return float(np.mean((ev[:, 0] / 1000.0 - val) ** 2))
    base = loss(seq, tab)
    perm = rng.permutation(len(idx))
    out = {"base_loss": round(base, 5), "rows": int(len(idx)), "groups": {}}
    for g, ch in (("the traded index's last hour of 1-minute bars", slice(0, 5)), ("the other four indices' last hour", slice(5, 13))):
        sq = seq.copy()
        sq[:, :, ch] = seq[perm][:, :, ch]
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
    b0 = {"P2": d["B0"]["P2"], "P3": f["B0"]["P3"]}
    ps = [f["P3"]["rules"][t]["twin"]["p"] for t in K.TESTS]
    q = S6.bh(ps, len(ps) + K.N_PRIOR)
    out = {}
    for t, qa, p in zip(K.TESTS, q, ps):
        per = {"P2": d["P2"]["rules"][t]["all"], "P3": f["P3"]["rules"][t]["all"]}
        if t in K.STANDALONE:
            per["O1"] = d["O1"]["rules"][t]["all"]
        g = gates(per, b0, float(qa), t in K.STANDALONE)
        g.update(twin_p=p, q=round(float(qa), 4), twin_mean=f["P3"]["rules"][t]["twin"]["mean"], **per)
        out[t] = g
    out["_b0"] = b0
    out["_m"] = dict(r158=len(ps), prior=K.N_PRIOR, total=len(ps) + K.N_PRIOR, passed=sum(1 for t in K.TESTS if out[t]["passed"]))
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
