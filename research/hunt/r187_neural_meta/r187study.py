"""R187 runner (PREREG.md).

    python research/hunt/r187_neural_meta/r187study.py build      # design rows (to 31 Mar 2026; no final data)
    python research/hunt/r187_neural_meta/r187study.py train      # folds F1-F5: settings, theta / delta, refits
    python research/hunt/r187_neural_meta/r187study.py wf         # the uses on the walk-forward window -> results/wf.json
    python research/hunt/r187_neural_meta/r187study.py final      # the ONE final read (FINAL_READ first) -> results/year.json
    X="IRAALGO_LAKE=~/.cache/iraalgo/r186ext/lake IRAALGO_CACHE=~/.cache/iraalgo/r186ext/cache R51_CLEAN=1 R176_POST=1"
    env $X python research/hunt/r187_neural_meta/r187study.py new  # 21 Oct - 13 Nov 2026, descriptive -> results/new_month.json
    python research/hunt/r187_neural_meta/r187study.py decide     # gates + BH -> results/decision.json

Every model is trained on trades before 1 Apr 2026 (design data), so `train` never reads the final. Rows, features and
models live in the R187 cache (never in git). Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

if (sys.argv[1:] or [""])[0] == "train":            # one BLAS thread per fold process (folds run in parallel)
    for _v in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
        os.environ.setdefault(_v, "1")
HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import numpy as np  # noqa: E402

import r187cfg as K  # noqa: E402
import r187feats as F  # noqa: E402
import r187net as N  # noqa: E402

_LAKE: list = []


def lake() -> F.Lake:
    if not _LAKE:
        _LAKE.append(F.Lake.load())
    return _LAKE[0]


def _save(name: str, obj):
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name: str):
    return pickle.loads((K.OUT / f"{name}.pkl").read_bytes())


# ================================================================================================ pure: stats, gates, twins
def describe(trades: list, lo: date, hi: date) -> dict:
    """Trades, win rate, net, average win / loss, max drawdown (on the day-by-day equity), worst month, month nets."""
    tr = [t for t in trades if lo <= t["day"] <= hi]
    nets = np.array([t["net"] for t in tr], float)
    by_day: dict = {}
    for t in tr:
        by_day[t["day"]] = by_day.get(t["day"], 0.0) + t["net"]
    eq = np.cumsum([by_day[d] for d in sorted(by_day)]) if by_day else np.zeros(0)
    dd = float(-(np.concatenate([[0.0], eq]) - np.maximum.accumulate(np.concatenate([[0.0], eq]))).min()) if len(eq) else 0.0
    months = {m: 0.0 for m in K.months_between(lo, hi)}
    for t in tr:
        months[K.month_of(t["day"])] = months.get(K.month_of(t["day"]), 0.0) + t["net"]
    wm = min(months, key=lambda m: months[m]) if months else None
    w, lo_ = nets[nets > 0], nets[nets <= 0]
    return dict(trades=int(len(nets)), win=round(float((nets > 0).mean()), 4) if len(nets) else None,
                net=round(float(nets.sum())), avg_win=round(float(w.mean())) if len(w) else 0,
                avg_loss=round(float(lo_.mean())) if len(lo_) else 0, max_dd=round(dd),
                worst_month=dict(month=wm, net=round(months[wm]) if wm else 0),
                months={m: round(v) for m, v in months.items()},
                account_stop_ok=bool(dd <= K.MAX_DD and (months[wm] if wm else 0) >= K.WORST_MONTH))


def gates(per: dict, base: dict, q: float) -> dict:
    """PREREG section 5. per / base: {period: describe()} for early, final, six, year."""
    fails = []
    for p in ("year", "final"):
        u, b = per[p], base[p]
        if not u["net"] >= b["net"]:
            fails.append(f"net below the base ({p})")
        if not (u["win"] or 0) > (b["win"] or 0):
            fails.append(f"win rate not above the base ({p})")
        if not (u["max_dd"] < b["max_dd"] or u["worst_month"]["net"] > b["worst_month"]["net"]):
            fails.append(f"neither drawdown nor worst month better ({p})")
    if not all(per[p]["net"] > 0 for p in K.PERIODS):
        fails.append("not positive in every period")
    if not q <= K.GATE_Q:
        fails.append(f"twin BH q {q:.2f} > 0.10")
    if per["final"]["trades"] < K.MIN_FINAL:
        fails.append("< 40 final trades")
    return dict(passed=not fails, fails=fails, q=round(float(q), 4))


def bh_q(ps: list, m_total: int = K.M_TOTAL) -> list:
    """Benjamini-Hochberg q with the earlier tests taken as p = 1."""
    p = np.asarray(ps, float)
    n = len(p)
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return [float(x) for x in np.minimum(q, 1.0)]


def twin_p(actual: float, twins: np.ndarray) -> float:
    return float((1 + np.sum(twins >= actual - 1e-9)) / (len(twins) + 1))


def skip_twins(nets: np.ndarray, n_keep: int, n: int, rng) -> np.ndarray:
    """Totals of n random keeps of n_keep trades out of `nets`."""
    nets = np.asarray(nets, float)
    if n_keep >= len(nets):
        return np.full(n, nets.sum())
    return np.array([nets[rng.choice(len(nets), n_keep, replace=False)].sum() for _ in range(n)])


def exit_twin_nets(trades: list, alts: list, base: str, n: int, rng) -> np.ndarray:
    """(n, len(trades)) twin nets: a switched trade (exit_id != base) gets a uniformly random other exit of its menu
    (alts[i]: {exit: net}); every other trade keeps its net."""
    out = np.tile(np.array([t["net"] for t in trades], float), (n, 1))
    for i, (t, al) in enumerate(zip(trades, alts)):
        if t.get("exit_id", base) == base:
            continue
        opts = [v for e, v in al.items() if e != base and np.isfinite(v)]
        if opts:
            out[:, i] = np.asarray(opts)[rng.integers(0, len(opts), n)]
    return out


# ================================================================================================ rows
def r_rows(A, keys=None) -> dict:
    """{cand key: row} for the arm's candidates (all, or `keys`): meta, features, R151 block, nets, walked / v5 flags."""
    lk = lake()
    st = A.study_feats()
    base = A.walk()
    walked = {t["key"]: t for t in base}
    out = {}
    for key in (keys or A.cands):
        m = A.meta(key)
        m["feats"] = F.common(lk, m["und"], m["day"], m["r"], m["side"], m["dte"], m["is_exp"])
        m["study"] = st.get(key, {})
        m["nets"] = A.nets(key)
        m["walked"] = key in walked
        m["v5"] = bool(key in walked and A.kept(walked[key]))
        m["net"] = m["nets"]["R:V5"]
        out[key] = m
    return out


def orb_row_feats(rows: list) -> list:
    lk = lake()
    for r in rows:
        r["feats"] = F.common(lk, r["und"], r["day"], r["r"], r["side"], r["dte"], r["is_exp"])
        r["walked"] = True
        r["v5"] = True
    return rows


def build():
    """Design rows: R72 / R51 / Liquidity candidates 20 Nov 2024 - 31 Mar 2026; ORB B0 trades Aug 2021 - 31 Mar 2026."""
    import r187orb as OB
    import r187rarms as RA
    out = {}
    for arm in K.R_ARMS:
        A = RA.Arm(arm, K.R_START, K.DESIGN_END)
        out[arm] = r_rows(A)
        print(arm, "candidates", len(out[arm]), "walked", sum(r["walked"] for r in out[arm].values()), flush=True)
    for arm in K.ORB_ARMS:
        rs = orb_row_feats(OB.rows(arm, K.ORB_START, K.DESIGN_END))
        out[arm] = {r["key"]: r for r in rs}
        print(arm, "trades", len(rs), flush=True)
    _save("design_rows", out)


# ================================================================================================ training
def training_rows(rows: dict, hi_excl: date, lo: date | None = None) -> list:
    """Walked rows (every arm; ORB Fresh's rows are ORB's duplicates and are left out) with lo <= day < hi_excl."""
    out = []
    for arm, rs in rows.items():
        if arm == "orb_fresh":
            continue
        out += [r for r in rs.values() if r["walked"] and r["day"] < hi_excl and (lo is None or r["day"] >= lo)]
    return out


def predict(models: dict, rows: list) -> None:
    """Adds r['p'] = {exit: P(win)} and r['ev'] = {exit: expected net} for each row's menu (in place)."""
    if not rows:
        return
    by_menu: dict = {}
    for r in rows:
        by_menu.setdefault(K.menu(r["arm"]), []).append(r)
    for mn, rs in by_menu.items():
        B = np.vstack([N.base_vector(r) for r in rs])
        X = N.with_exit(B, mn)
        pc, pr = models["clf"].predict(X), models["reg"].predict(X)
        n = len(rs)
        for j, e in enumerate(mn):
            for i, r in enumerate(rs):
                r.setdefault("p", {})[e] = float(pc[j * n + i])
                r.setdefault("ev", {})[e] = float(pr[j * n + i])


def choices(val: list, arm: str) -> dict:
    """theta1 / delta / theta3 / theta4 for one arm from its validation rows (with p / ev from the inner models)."""
    b = K.base_exit(arm)
    vs = [r for r in val if r["arm"] == arm and r["v5"]]
    out = dict(n_val=len(vs))
    out["theta1"] = N.choose_theta([r["p"][b] for r in vs], [r["nets"][b] for r in vs], K.MIN_KEEP_U1)
    out["delta"] = N.choose_delta([r["ev"] for r in vs], [r["nets"] for r in vs], b)
    ch = [N.exit_choice(r["ev"], b, out["delta"]) for r in vs]
    nc = [r["nets"][e] if np.isfinite(r["nets"][e]) else r["nets"][b] for r, e in zip(vs, ch)]
    out["theta3"] = N.choose_theta([r["p"][e] for r, e in zip(vs, ch)], nc, K.MIN_KEEP_U1)
    if arm in K.PICKER_ARMS:
        va = [r for r in val if r["arm"] == arm and r["walked"]]
        out["theta4"] = N.choose_theta([r["p"][b] for r in va], [r["nets"][b] for r in va], K.MIN_KEEP_U4)
    return out


def fit_fold(spec: tuple) -> tuple:
    """One fold: the settings on the inner validation quarter, the theta / delta choices from the inner models' scores
    on that quarter, and the 3-seed refit on the whole training window (trades before the fold only)."""
    name, lo, hi, vlo = spec
    rows = _load("design_rows")
    tr = training_rows(rows, lo)
    inner, val = [r for r in tr if r["day"] < vlo], [r for r in tr if r["day"] >= vlo]
    assert max(r["day"] for r in tr) < lo and all(r["day"] < lo for r in val) and all(r["day"] < vlo for r in inner)
    Xi, ywi, yni, ii = N.stacked(inner)
    Xv, ywv, ynv, _ = N.stacked(val)
    Xt, ywt, ynt, it = N.stacked(tr)
    oi, ot = N.order_of(inner, ii), N.order_of(tr, it)
    info = dict(n_train_trades=len(tr), n_inner=len(inner), n_val=len(val), n_rows=int(len(Xt)))
    inner_m, full = {}, {}
    for kind in ("clf", "reg"):
        h, a, m_in, losses = N.choose_setting(kind, Xi, ywi, yni, Xv, ywv, ynv, oi)
        inner_m[kind] = m_in
        info[f"{kind}_setting"] = dict(hidden=list(h), alpha=a, losses=losses)
        full[kind] = N.Model(kind, h, a).fit(Xt, ywt, ynt, ot)
        print(name, kind, info[f"{kind}_setting"], flush=True)
    valc = [dict(r) for r in val]
    predict(inner_m, valc)
    # ORB Fresh takes ORB's choices (its validation trades are ORB's)
    info["choices"] = {a: choices(valc, "orb" if a == "orb_fresh" else a) for a in K.ARMS}
    print(name, info["n_train_trades"], info["choices"], flush=True)
    return name, dict(models=full, info=info, lo=lo, hi=hi, vlo=vlo)


def train():
    from multiprocessing import Pool
    with Pool(min(4, len(K.FOLDS))) as p:
        got = p.map(fit_fold, K.FOLDS)
    folds = dict(got)
    _save("folds", folds)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "folds.json").write_text(json.dumps({k: v["info"] for k, v in folds.items()}, indent=1, default=str))


# ================================================================================================ the uses
def fold_for(folds: dict, d: date):
    n = K.fold_of(d)
    return folds.get(n) if n else None


def r_uses(A, crow: dict, folds: dict) -> dict:
    """{use: trades} for an R arm over A's span; candidates outside the folds keep the base (no skip, V5 exit)."""
    arm = A.arm
    for f in folds.values():
        predict(f["models"], [r for r in crow.values() if f["lo"] <= r["day"] <= f["hi"]])

    def ch(d):
        f = fold_for(folds, d)
        return f["info"]["choices"][arm] if f else None

    def keep(t, th_key, exit_key="R:V5"):
        c = ch(t["day"])
        r = crow.get(t["key"])
        th = c[th_key] if c else None
        return th is None or r is None or "p" not in r or r["p"][exit_key] >= th

    base = A.walk()
    v5 = A.v5(base)
    out = {"BASE": v5, "U1": [t for t in v5 if keep(t, "theta1")]}
    choice = {}
    for k, r in crow.items():
        c = ch(r["day"])
        if c and "ev" in r:
            e = N.exit_choice(r["ev"], "R:V5", c["delta"])
            if e != "R:V5":
                choice[k] = e
    w2 = A.walk(choice)
    out["U2"] = A.v5(w2)
    out["U3"] = [t for t in out["U2"] if keep(t, "theta3", t["exit_id"])]
    if arm in K.PICKER_ARMS:
        out["U4"] = [t for t in base if keep(t, "theta4")]
        out["NOPICK"] = base
    return out


def orb_uses(arm: str, lo: date, hi: date, folds: dict) -> tuple:
    """({use: trades}, alts {trade id: {exit: net}}) for an ORB arm on sessions lo..hi."""
    import r169lib as X
    import r187orb as OB
    lk = lake()
    ss = [s for s in OB.sessions() if lo <= s.day <= hi]
    ctx = OB.contexts()
    b0 = "O:B0"

    def row_of(s, side, row):
        ex = ctx[s.day]["expiry_date"]
        return dict(arm=arm, und="BANKNIFTY", day=s.day, side="C" if side > 0 else "P", r=int(row),
                    dte=float((ex - s.day).days) if ex is not None else np.nan, is_exp=bool(s.expiry))

    def scored(s, side, row, f):
        r = row_of(s, side, row)
        r["feats"] = F.common(lk, r["und"], r["day"], r["r"], r["side"], r["dte"], r["is_exp"])
        r["study"] = {f"o_{k}": float(f.get(k, np.nan)) for k in X.PICK_FEATS}
        fo = fold_for(folds, s.day)
        if fo:
            predict(fo["models"], [r])
        return r, fo

    cache: dict = {}

    def chooser(s, side, row, f, k):
        r, fo = scored(s, side, row, f)
        cache[(s.day, side, row)] = r
        if not fo:
            return "B0"
        return N.exit_choice(r["ev"], b0, fo["info"]["choices"][arm]["delta"]).split(":")[1]

    def keep(t, th_key, e):
        fo = fold_for(folds, t["day"])
        r = cache.get((t["day"], t["side"], t["sig_row"]))
        if r is None:
            s = next(x for x in ss if x.day == t["day"])
            r, _ = scored(s, t["side"], t["sig_row"], t["feats"])
            cache[(t["day"], t["side"], t["sig_row"])] = r
        th = fo["info"]["choices"][arm][th_key] if fo else None
        return th is None or r["p"][e] >= th

    base = OB.walk(arm, ss)
    for t in base:
        t["exit_id"] = b0
    out = {"BASE": base, "U1": [t for t in base if keep(t, "theta1", b0)]}
    u2 = OB.walk(arm, ss, chooser)
    out["U2"] = u2
    out["U3"] = [t for t in u2 if keep(t, "theta3", t["exit_id"])]
    # alternative exits' nets for the U2 / U3 twins (switched trades only)
    by_day = {s.day: s for s in ss}
    P = OB.K9.K3.PTS[OB.K9.UND]
    alts = {}
    for t in u2:
        if t["exit_id"] == b0:
            continue
        s = by_day[t["day"]]
        al = {}
        for e in K.ORB_EXITS:
            u = X.simulate(s, OB.cfg_for(arm, e), t["side"], t["sig_row"], t["k"], P, t["hi"], t["lo"])
            al[f"O:{e}"] = float(u["net"]) if u is not None else np.nan
        alts[(t["day"], t["side"], t["sig_row"])] = al
    return out, alts


def _strip(t: dict) -> dict:
    return dict(day=t["day"], net=float(t["net"]), exit_id=t.get("exit_id"), side=t.get("side"),
                key=t.get("key") or (t.get("und"), t["day"], t.get("sig_row"), t.get("side")))


def tables(uses: dict, periods=K.PERIODS) -> dict:
    return {u: {p: describe(tr, *K.span_of(p)) for p in periods} for u, tr in uses.items()}


def wf():
    """The uses on the walk-forward window (Apr 2025 - Mar 2026; folds F1-F4), design data only."""
    import r187rarms as RA
    folds = {k: v for k, v in _load("folds").items() if k in K.DESIGN_FOLDS}
    rows = _load("design_rows")
    res = {}
    lo, hi = K.span_of("wf")
    for arm in K.R_ARMS:
        A = RA.Arm(arm, K.R_START, K.DESIGN_END)
        u = r_uses(A, {k: dict(r) for k, r in rows[arm].items()}, folds)
        res[arm] = {k: describe(v, lo, hi) for k, v in u.items()}
        print(arm, {k: (v["trades"], v["win"], v["net"]) for k, v in res[arm].items()}, flush=True)
    for arm in K.ORB_ARMS:
        u, _ = orb_uses(arm, lo, hi, folds)
        res[arm] = {k: describe(v, lo, hi) for k, v in u.items()}
        print(arm, {k: (v["trades"], v["win"], v["net"]) for k, v in res[arm].items()}, flush=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "wf.json").write_text(json.dumps(res, indent=1, default=str))


# ================================================================================================ the final read
def final():
    import r187rarms as RA
    assert RA.K6.POST, "R187 reads the stores built to 19 Oct (R176_POST=1)"
    if K.FINAL_FLAG.exists():
        raise SystemExit("R187 already read once: " + K.FINAL_FLAG.read_text())
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    RA.R7.mods()["C"].open_final()
    folds = _load("folds")
    rng = np.random.default_rng(K.SEED)
    out = {"parity": {}, "twins": {}, "tables": {}}
    keep_tr = {}
    ylo, yhi = K.span_of("year")
    for arm in K.R_ARMS:
        A = RA.Arm(arm, K.Y_START, K.Y_END)
        v5 = A.v5(A.walk())
        got = (len(v5), round(sum(t["net"] for t in v5)))
        pub = K.V5_PARITY_YEAR[arm]
        out["parity"][arm] = dict(got=list(got), published=list(pub), ok=got[0] == pub[0] and abs(got[1] - pub[1]) <= 1)
        print(arm, "parity", out["parity"][arm], flush=True)
        assert out["parity"][arm]["ok"], ("V5 parity failed", arm)
        crow = r_rows(A)
        u = r_uses(A, crow, folds)
        out["tables"][arm] = tables(u)
        keep_tr[arm] = {k: [_strip(t) for t in v] for k, v in u.items()}
        out["twins"].update(r_twins(A, u, rng))
        print(arm, {k: (v["year"]["trades"], v["year"]["win"], v["year"]["net"]) for k, v in out["tables"][arm].items()},
              flush=True)
    for arm in K.ORB_ARMS:
        u, alts = orb_uses(arm, ylo, yhi, folds)
        out["tables"][arm] = tables(u)
        keep_tr[arm] = {k: [_strip(t) for t in v] for k, v in u.items()}
        out["twins"].update(orb_twins(arm, u, alts, rng))
        print(arm, {k: (v["year"]["trades"], v["year"]["win"], v["year"]["net"]) for k, v in out["tables"][arm].items()},
              flush=True)
    (K.RESULTS / "year.json").write_text(json.dumps(out, indent=1, default=str))
    _save("final_trades", keep_tr)
    return out


def _year(tr: list) -> list:
    lo, hi = K.span_of("year")
    return [t for t in tr if lo <= t["day"] <= hi]


def r_twins(A, u: dict, rng) -> dict:
    arm, out = A.arm, {}
    v5, base = _year(u["BASE"]), _year(u.get("NOPICK", []))
    u1, u2, u3 = _year(u["U1"]), _year(u["U2"]), _year(u["U3"])
    tw = skip_twins([t["net"] for t in v5], len(u1), K.TWINS, rng)
    out[f"{arm}:U1"] = dict(p=twin_p(sum(t["net"] for t in u1), tw), mean=round(float(tw.mean())), skipped=len(v5) - len(u1))
    alts = [A.nets(t["key"]) for t in u2]
    m2 = exit_twin_nets(u2, alts, "R:V5", K.TWINS, rng)
    out[f"{arm}:U2"] = dict(p=twin_p(sum(t["net"] for t in u2), m2.sum(1)), mean=round(float(m2.sum(1).mean())),
                            switched=sum(t["exit_id"] != "R:V5" for t in u2))
    t3 = np.array([m2[i][rng.choice(len(u2), len(u3), replace=False)].sum() for i in range(K.TWINS)])
    out[f"{arm}:U3"] = dict(p=twin_p(sum(t["net"] for t in u3), t3), mean=round(float(t3.mean())))
    if arm in K.PICKER_ARMS:
        u4 = _year(u["U4"])
        tw4 = skip_twins([t["net"] for t in base], len(u4), K.TWINS, rng)
        out[f"{arm}:U4"] = dict(p=twin_p(sum(t["net"] for t in u4), tw4), mean=round(float(tw4.mean())), kept=len(u4))
    return out


def orb_twins(arm: str, u: dict, alts: dict, rng) -> dict:
    out = {}
    b0, u1, u2, u3 = _year(u["BASE"]), _year(u["U1"]), _year(u["U2"]), _year(u["U3"])
    tw = skip_twins([t["net"] for t in b0], len(u1), K.TWINS, rng)
    out[f"{arm}:U1"] = dict(p=twin_p(sum(t["net"] for t in u1), tw), mean=round(float(tw.mean())), skipped=len(b0) - len(u1))
    al = [alts.get((t["day"], t["side"], t["sig_row"]), {}) for t in u2]
    m2 = exit_twin_nets(u2, al, "O:B0", K.TWINS, rng)
    out[f"{arm}:U2"] = dict(p=twin_p(sum(t["net"] for t in u2), m2.sum(1)), mean=round(float(m2.sum(1).mean())),
                            switched=sum(t["exit_id"] != "O:B0" for t in u2))
    t3 = np.array([m2[i][rng.choice(len(u2), len(u3), replace=False)].sum() for i in range(K.TWINS)])
    out[f"{arm}:U3"] = dict(p=twin_p(sum(t["net"] for t in u3), t3), mean=round(float(t3.mean())))
    return out


def new_month():
    """21 Oct - 13 Nov 2026 with the frozen F5 models (descriptive), R arms on R186's extended stores."""
    import r187rarms as RA
    RA.extend_to(K.NEW_END)
    RA.R7.mods()["C"].open_final()
    folds = _load("folds")
    lo, hi = K.span_of("new")
    res = {"window": f"{lo} .. {hi}"}
    for arm in K.R_ARMS:
        A = RA.Arm(arm, date(2026, 9, 1), K.NEW_END)
        u = r_uses(A, r_rows(A), folds)
        res[arm] = {k: describe(v, lo, hi) for k, v in u.items()}
        print(arm, {k: (v["trades"], v["win"], v["net"]) for k, v in res[arm].items()}, flush=True)
    for arm in K.ORB_ARMS:
        u, _ = orb_uses(arm, lo, hi, folds)
        res[arm] = {k: describe(v, lo, hi) for k, v in u.items()}
        print(arm, {k: (v["trades"], v["win"], v["net"]) for k, v in res[arm].items()}, flush=True)
    (K.RESULTS / "new_month.json").write_text(json.dumps(res, indent=1, default=str))


def decide() -> dict:
    r = json.loads((K.RESULTS / "year.json").read_text())
    ps = [r["twins"][f"{a}:{u}"]["p"] for a, u in K.TESTS]
    qs, qo = bh_q(ps), bh_q(ps, len(ps))
    out = {}
    for (a, u), q, q2 in zip(K.TESTS, qs, qo):
        t = r["tables"][a]
        g = gates(t[u], t["BASE"], q)
        g.update(twin_p=r["twins"][f"{a}:{u}"]["p"], q_own=round(q2, 4))
        out[f"{a}:{u}"] = g
    out["_m"] = dict(tests=len(K.TESTS), prior=K.M_PRIOR, total=K.M_TOTAL)
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    fn = {"build": build, "train": train, "wf": wf, "final": final, "new": new_month, "decide": decide}.get(cmd)
    if fn is None:
        raise SystemExit(__doc__)
    r = fn()
    if cmd == "decide":
        print(json.dumps(r, indent=1))
