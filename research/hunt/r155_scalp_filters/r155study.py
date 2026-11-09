"""R155 study (PREREG.md): 20 filters (S/R lines, W5 / W20 / W50, trend, OI, candle wicks, a vote and a picker) on R154's
10-15 point scalps.

    python research/hunt/r155_scalp_filters/r155oi.py build                 # OI store (Aug 2021 - 19 Oct 2026)
    python research/hunt/r155_scalp_filters/r155study.py design [ENTRY ...] # P1 + P2; F0 parity with R154 asserted
    python research/hunt/r155_scalp_filters/r155study.py final  [ENTRY ...] # P3, once (a second look after R154)
    R155_POST=1 python research/hunt/r155_scalp_filters/r155study.py post   # 14-19 Oct, once, after the final
    python research/hunt/r155_scalp_filters/r155study.py decide             # pooled gates, twins, BH -> results/decision.json

Pure parts (keep sets, pooling, random-subset twins, picker folds and threshold, gates) take plain arrays; test_r155.py
covers them. Trades are priced once per (signal, exit rule) and reused by every filter.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timezone

import numpy as np

import r155cfg as K

K4 = K.K4


# ================================================================================================ pure
def be_rate(nets: np.ndarray) -> float:
    """Break-even win rate of a set of trades: average loss / (average win + average loss); NaN with no wins or losses."""
    nets = np.asarray(nets, float)
    w, lo = nets[nets > 0], nets[nets <= 0]
    if not len(w) or not len(lo):
        return float("nan")
    aw, al = w.mean(), -lo.mean()
    return float(al / (aw + al))


def fold_of(day: date, folds=K.PICK_FOLDS) -> int:
    """Index of the fold a day is scored in (-1 before the first fold)."""
    j = -1
    for i, f in enumerate(folds):
        if day >= f:
            j = i
    return j


def subset_twin(base: np.ndarray, n: int, total: float, draws: int, rng, batch: int = 50) -> tuple:
    """Random-skip twin: `draws` uniform random subsets of size n (without replacement) of the unfiltered trades' nets;
    (p, mean total). A subset is the n smallest of fresh uniform keys, drawn in batches."""
    base = np.asarray(base, float)
    if n <= 0 or not len(base):
        return 1.0, 0.0
    if n >= len(base):
        tots = np.full(draws, base.sum())
    else:
        tots = []
        left = draws
        while left > 0:
            m = min(batch, left)
            R = rng.random((m, len(base)))
            thr = np.partition(R, n - 1, axis=1)[:, n - 1:n]
            tots.append((R <= thr) @ base)
            left -= m
        tots = np.concatenate(tots)
    return float((1 + (tots >= total).sum()) / (draws + 1)), float(tots.mean())


def gates(p1: dict, p2: dict, p3: dict, f0_p2: dict, f0_p3: dict, q: float, n_rules: int,
          b0_p2: float | None = None, b0_p3: float | None = None) -> dict:
    """PREREG section 3 for one pooled test (dicts with trades, net)."""
    fails = []
    if not (p1["net"] > 0 and p2["net"] > 0 and p3["net"] > 0):
        fails.append("not positive in P1, P2 and P3")

    def pt(s):
        return s["net"] / s["trades"] if s["trades"] else -np.inf
    if not (pt(p2) > pt(f0_p2) and pt(p3) > pt(f0_p3)):
        fails.append("not better per trade than the unfiltered scalp in P2 and P3")
    if not q <= K.GATE_Q:
        fails.append(f"random-skip BH q {q:.2f} > {K.GATE_Q}")
    if p3["trades"] / max(n_rules, 1) < K.MIN_FINAL_PER_RULE:
        fails.append(f"< {K.MIN_FINAL_PER_RULE} final trades per exit rule")
    if b0_p2 is not None and not (p2["net"] > b0_p2 and p3["net"] > b0_p3):
        fails.append("not above the arm's own exits in P2 and P3")
    return dict(passed=not fails, fails=fails)


# ================================================================================================ data
_M: dict = {}


def mods():
    if not _M:
        import r154study as S4
        M = S4.mods()
        import r155feat as FE
        import r155oi as OI
        _M.update(M, S4=S4, FE=FE, OI=OI)
    return _M


def feat_for(und: str, hi: date):
    M = mods()
    days = M["DATA"].sessions(und, K4.EARLY_WARM, hi, warm=True)
    return M["FE"].Feat(und, days, M["OI"].load(und))


def signals(entry: str, und: str, F, lo: date, hi: date) -> dict:
    """{day: [(er, side, strike)]} of a plain entry (R154's signals and strike rule)."""
    M = mods()
    sig = M["SG"].plain_signals(entry, F.days, und)
    out = {}
    for d, D in M["DATA"].sessions(und, lo, hi):
        rows = []
        for er, side in sig.get(d, []):
            k = M["E"].strike_for(D, und, side, er - 1, K4.ITM)
            if k is not None:
                rows.append((er, side, float(k)))
        if rows:
            out[d] = rows
    return out


class Pricer:
    """Prices a (day, er, side, strike) under a rule once; every filter reuses it."""

    def __init__(self, und: str, dmap: dict):
        self.und, self.dmap, self.cache = und, dmap, {}

    def __call__(self, d, er, side, strike, rule_id):
        key = (d, er, side, strike, rule_id)
        if key not in self.cache:
            t = mods()["Y"].price(self.und, self.dmap[d], er, side, K.RULES[rule_id], 1, strike=strike)
            self.cache[key] = None if t is None else dict(und=t["und"], day=t["day"], side=side, strike=strike,
                                                          er=t["er"], xr=t["xr"], net=t["net"], why=t["why"],
                                                          charges=t["charges"], er_sig=er)
        return self.cache[key]


def plain_walk(sig: dict, keep, pricer: Pricer, rule_id: str) -> list:
    """R154's plain walk on the signals `keep(d, er, side)` lets through."""
    S4 = mods()["S4"]
    out = []
    for d, rows in sig.items():
        rows_k = [(er, side, k) for er, side, k in rows if keep(d, er, side)]
        if not rows_k:
            continue
        strike = {(er, side): k for er, side, k in rows_k}
        out += S4.walk_plain([(er, side) for er, side, _ in rows_k],
                             lambda er, side: pricer(d, er, side, strike[(er, side)], rule_id))
    return out


def arm_walk(arm: str, us: list, keep, pricers: dict, rule_id: str) -> list:
    """The arm's own taking walk (R106's run_rule) on the candidates `keep` lets through."""
    M = mods()
    us_f, pr_f = [], []
    for key, cands, res, how in us:
        cs = [c for c in cands if keep(key[1], c["er"], c["side"], key[0])]
        us_f.append((key, cs, res, how))
        pr_f.append([pricers[key[0]](key[1], c["er"], c["side"], float(c["strike"]), rule_id) for c in cs])
    return M["S"].run_rule(arm, us_f, pr_f, None)


# ================================================================================================ picker (F20)
def pick_fit(X: np.ndarray, y: np.ndarray):
    from sklearn.impute import SimpleImputer
    from sklearn.linear_model import LogisticRegression
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    m = make_pipeline(SimpleImputer(strategy="median", keep_empty_features=True), StandardScaler(),
                      LogisticRegression(C=K.PICK_C, max_iter=500))
    m.fit(X, y)
    return m


def pick_keep(train: dict, score_rows: list, folds=K.PICK_FOLDS) -> set:
    """F20's keep set. train: arrays day (ordinal), X, net of the UNFILTERED trades (every period so far); score_rows:
    [(key, day, x vector)] signals to score. A signal in fold j is scored by a model trained on the trades before
    folds[j]; with no fold or fewer than K.PICK_MIN_TRAIN training trades it is kept. Kept when p >= the training
    trades' break-even win rate."""
    keep = set()
    by_fold: dict = {}
    for key, d, xv in score_rows:
        by_fold.setdefault(fold_of(d, folds), []).append((key, xv))
    for j, rows in by_fold.items():
        if j < 0:
            keep.update(k for k, _ in rows)
            continue
        m = train["day"] < folds[j].toordinal()
        if m.sum() < K.PICK_MIN_TRAIN or len(np.unique(train["net"][m] > 0)) < 2:
            keep.update(k for k, _ in rows)
            continue
        model = pick_fit(train["X"][m], (train["net"][m] > 0).astype(int))
        thr = be_rate(train["net"][m])
        p = model.predict_proba(np.vstack([xv for _, xv in rows]))[:, 1]
        keep.update(k for (k, _), pv in zip(rows, p) if pv >= thr)
    return keep


# ================================================================================================ one entry
def run_entry(entry: str, phase: str) -> dict:
    """Every filter x index x rule of one entry over the phase window: {(filter, und, rule): compact arrays}, plus the
    F0 trades' feature rows (for the picker's later folds)."""
    M = mods()
    S4, FE = M["S4"], M["FE"]
    lo, hi = S4.phase_range(phase, entry)
    unds = K4.ENTRY_UNDS[entry]
    feats, pricers, sigs, units = {}, {}, {}, None
    fx: dict = {}                                   # (und, day, er, side) -> (numbers, filters)
    for u in unds:
        F = feat_for(u, hi)
        dmap = dict(M["DATA"].sessions(u, lo, hi))
        pricers[u] = Pricer(u, dmap)
        if entry in K4.ARMS:
            continue
        sigs[u] = signals(entry, u, F, lo, hi)
        for d, rows in sigs[u].items():
            for er, side, k in rows:
                fx[(u, d, er, side)] = F.decide(d, er - 1, side, k)
        feats[u] = F
        print(entry, phase, u, "features", sum(len(v) for v in sigs[u].values()), flush=True)
    if entry in K4.ARMS:
        units = S4.arm_units(entry, lo, hi)
        Fs = {u: feat_for(u, hi) for u in unds}
        for key, cands, _, _ in units:
            for c in cands:
                fx[(key[0], key[1], c["er"], c["side"])] = Fs[key[0]].decide(key[1], c["er"] - 1, c["side"], float(c["strike"]))
        print(entry, phase, "arm features", len(fx), flush=True)

    def keeper(f):
        if f == "F0":
            return lambda d, er, side, u=None: True
        return lambda d, er, side, u=None: fx[(u, d, er, side)][1][f]

    out = {}
    f0_rows: dict = {}
    for r in K.RULE_IDS:
        for f in ("F0",) + tuple(x for x in K.FILTERS if x != "F20"):
            kp = keeper(f)
            if entry in K4.ARMS:
                tr = arm_walk(entry, units, lambda d, er, side, u: kp(d, er, side, u), pricers, r)
                for u in unds:
                    out[(f, u, r)] = M["S4"].compact([t for t in tr if t["und"] == u])
            else:
                for u in unds:
                    tr = plain_walk(sigs[u], lambda d, er, side, u=u, kp=kp: kp(d, er, side, u), pricers[u], r)
                    out[(f, u, r)] = M["S4"].compact(tr)
                    if f == "F0":
                        f0_rows.setdefault(r, []).extend(
                            (t["day"].toordinal(), FE.vector(fx[(u, t["day"], t["er_sig"], t["side"])][0]), t["net"])
                            for t in tr)
            if entry in K4.ARMS and f == "F0":
                f0_rows[r] = [(t["day"].toordinal(), FE.vector(fx[(t["und"], t["day"], t["er_sig"], t["side"])][0]), t["net"])
                              for t in tr]
        print(entry, phase, r, "filters walked", flush=True)
    return dict(out=out, f0_rows=f0_rows, fx=fx, sigs=sigs, units=units, pricers=pricers)


def run_pick(entry: str, phase: str, state: dict, prior_rows: dict) -> dict:
    """F20 per exit rule: train on the unfiltered trades of earlier days (prior phases' + this phase's), walk."""
    M = mods()
    FE = M["FE"]
    unds = K4.ENTRY_UNDS[entry]
    out = {}
    fx = state["fx"]
    score_rows = [(key, key[1], FE.vector(v[0])) for key, v in fx.items()]
    for r in K.RULE_IDS:
        rows = prior_rows.get(r, []) + state["f0_rows"].get(r, [])
        train = dict(day=np.array([a for a, _, _ in rows], np.int64),
                     X=np.vstack([b for _, b, _ in rows]) if rows else np.zeros((0, len(FE.NUM))),
                     net=np.array([c for _, _, c in rows], float))
        keep = pick_keep(train, score_rows)
        if entry in K4.ARMS:
            tr = arm_walk(entry, state["units"], lambda d, er, side, u: (u, d, er, side) in keep, state["pricers"], r)
            for u in unds:
                out[("F20", u, r)] = M["S4"].compact([t for t in tr if t["und"] == u])
        else:
            for u in unds:
                tr = plain_walk(state["sigs"][u], lambda d, er, side, u=u: (u, d, er, side) in keep, state["pricers"][u], r)
                out[("F20", u, r)] = M["S4"].compact(tr)
    return out


# ================================================================================================ phases
def _save(name: str, obj):
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name: str):
    return pickle.loads((K.OUT / f"{name}.pkl").read_bytes())


def parity(entry: str, phase: str, out: dict):
    """F0 must reproduce R154's 1-lot trades and net for every index, rule and period (PREREG section 1)."""
    if phase == "post":
        return
    res = json.loads((K4.RESULTS / ("design.json" if phase == "design" else "final.json")).read_text())[entry]
    pers = ("P1", "P2") if phase == "design" else ("P3",)
    S4 = mods()["S4"]
    for u in K4.ENTRY_UNDS[entry]:
        for r in K.RULE_IDS:
            a = out[("F0", u, r)]
            for p in pers:
                lo, hi = {"P1": ((K4.ARM_START if entry in K4.ARMS else K4.EARLY_START), K4.P1_END),
                          "P2": (K4.P2_START, K4.P2_END), "P3": (K4.FINAL_START, K4.FINAL_END)}[p]
                s = S4.stats(S4.in_range(a, lo, hi))
                want = res[u][f"{r}@1"][p]
                assert s["trades"] == want["trades"] and abs(s["net"] - want["net"]) <= 1, (entry, u, r, p, s["trades"],
                                                                                         s["net"], want)
    print(entry, phase, "F0 parity with R154: reproduced", flush=True)


def evaluate(entry: str, phase: str):
    st = run_entry(entry, phase)
    parity(entry, phase, st["out"])
    prior = {}
    if phase in ("final", "post"):
        prior = _load(f"f0rows_{entry}_design")
        if phase == "post":
            fin = _load(f"f0rows_{entry}_final")
            prior = {r: prior.get(r, []) + fin.get(r, []) for r in K.RULE_IDS}
    st["out"].update(run_pick(entry, phase, st, prior))
    _save(f"trades_{entry}_{phase}", st["out"])
    _save(f"f0rows_{entry}_{phase}", st["f0_rows"])
    print(entry, phase, "saved", flush=True)


def run(phase: str, entries=K.ENTRIES):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists() and not (K.RESULTS / "final_open").exists():
            raise SystemExit("R155 final already read once: " + K.FINAL_FLAG.read_text())
        if not K.FINAL_FLAG.exists():
            K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (K.RESULTS / "final_open").write_text("entries in progress\n")
    elif phase == "post":
        assert K.POST
        if K.POST_FLAG.exists() and not (K.RESULTS / "post_open").exists():
            raise SystemExit("R155 post window already read once")
        if not K.POST_FLAG.exists():
            K.POST_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (K.RESULTS / "post_open").write_text("entries in progress\n")
    if phase in ("final", "post"):
        mods()["C"].open_final()
    for e in entries:
        if phase != "design" and (K.OUT / f"trades_{e}_{phase}.pkl").exists():
            print(e, phase, "already read; skipped", flush=True)
            continue
        evaluate(e, phase)


def close(phase: str):
    flag = K.RESULTS / f"{phase}_open"
    if flag.exists() and all((K.OUT / f"trades_{e}_{phase}.pkl").exists() for e in K.ENTRIES):
        flag.unlink()
        print(phase, "closed")


# ================================================================================================ decide
PER = {"P1": None, "P2": (K4.P2_START, K4.P2_END), "P3": (K4.FINAL_START, K4.FINAL_END),
       "P4": (K4.POST_START, K4.POST_END)}


def period_range(p: str, entry: str) -> tuple:
    if p == "P1":
        return (K4.ARM_START if entry in K4.ARMS else K4.EARLY_START), K4.P1_END
    return PER[p]


def pooled(trades: dict, f: str, entry: str, reading: str, p: str) -> dict:
    S4 = mods()["S4"]
    parts = [trades[(f, u, r)] for u in K4.ENTRY_UNDS[entry] for r in K.rules_of(reading)]
    return S4.in_range(S4.concat(parts), *period_range(p, entry))


def decide() -> dict:
    S4 = mods()["S4"]
    rng = np.random.default_rng(K.SEED)
    r154d = json.loads((K4.RESULTS / "design.json").read_text())
    r154f = json.loads((K4.RESULTS / "final.json").read_text())
    rows, ps = [], []
    for e in K.ENTRIES:
        tr = {**_load(f"trades_{e}_design"), **{k: S4.concat([_load(f"trades_{e}_design")[k], v])
                                                 for k, v in _load(f"trades_{e}_final").items()}}
        for rd in ("A", "B"):
            base = {p: pooled(tr, "F0", e, rd, p) for p in ("P1", "P2", "P3")}
            bs = {p: S4.stats(v) for p, v in base.items()}
            b0 = None
            if e in K4.ARMS:
                b0 = (sum(r154d[e][u]["B0@1"]["P2"]["net"] for u in K4.ENTRY_UNDS[e]),
                      sum(r154f[e][u]["B0@1"]["P3"]["net"] for u in K4.ENTRY_UNDS[e]))
            for f in K.FILTERS:
                arr = {p: pooled(tr, f, e, rd, p) for p in ("P1", "P2", "P3")}
                s = {p: S4.stats(v) for p, v in arr.items()}
                p_, mean = subset_twin(base["P3"]["net"], s["P3"]["trades"], float(arr["P3"]["net"].sum()), K.TWINS, rng)
                rows.append(dict(filter=f, entry=e, reading=rd, s=s, f0=bs, twin_p=round(p_, 4), twin_mean=round(mean),
                                 b0=b0))
                ps.append(p_)
        print(e, "decided", flush=True)
    q = S4.bh(ps, len(ps) + K.N_PRIOR)
    q_own = S4.bh(ps)
    out = {}
    nr = 12
    for row, qa, qo in zip(rows, q, q_own):
        s, bs = row["s"], row["f0"]
        b0p2 = b0p3 = None
        if row["b0"] is not None:
            b0p2 = row["b0"][0] * nr          # the arm's own exits: one walk; the pool is 12 exit variants of it
            b0p3 = row["b0"][1] * nr
        g = gates(s["P1"], s["P2"], s["P3"], bs["P2"], bs["P3"], float(qa), nr, b0p2, b0p3)
        g.update(twin_p=row["twin_p"], twin_mean=row["twin_mean"], q=round(float(qa), 4), q_r155=round(float(qo), 4),
                 stats={p: {k: s[p][k] for k in ("trades", "win", "be_win", "net", "per_trade", "worst_day", "max_dd")}
                        for p in s},
                 f0={p: {k: bs[p][k] for k in ("trades", "win", "be_win", "net", "per_trade")} for p in bs})
        out[f"{row['filter']}:{row['entry']}:{row['reading']}"] = g
    out["_m"] = dict(r155=len(ps), prior=K.N_PRIOR, total=len(ps) + K.N_PRIOR,
                     passed=sum(1 for k, v in out.items() if not k.startswith("_") and v["passed"]))
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=0, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final", "post"):
        run(cmd, tuple(sys.argv[2:]) or K.ENTRIES)
    elif cmd == "close":
        close(sys.argv[2])
    elif cmd == "decide":
        print(json.dumps(decide()["_m"], indent=1))
    else:
        raise SystemExit(__doc__)
