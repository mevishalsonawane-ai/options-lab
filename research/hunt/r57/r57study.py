"""R57 study: Solo 2.0 (PREREG.md). Research only.

    python research/hunt/r57/r57data.py build              # day stores + brain features from the lake (cache)
    python research/hunt/r57/r57study.py design            # candidates, labels, purged walk-forward on DESIGN only
    python research/hunt/r57/r57study.py final             # the ONE final-test read (refuses a second)

    python research/hunt/r57/r57study.py dryrun            # the final pipeline on design months only (crash check)

Writes results/design.json, results/final.json and results/models/*.json (small files only).
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import date

import numpy as np

import r57cfg as K
import r57data
import r57model as M
import r57setups as S

VARIANTS = {   # PREREG section 5 (10 gated variants)
    "A1": dict(fam="A", kind="gbc", c4=False), "A2": dict(fam="A", kind="logit", c4=False),
    "A3": dict(fam="A", kind="gbr", c4=False), "A4": dict(fam="A", kind="gbc", c4=True),
    "B1": dict(fam="B", kind="gbc", H=30, money=1), "B2": dict(fam="B", kind="gbc", H=60, money=1),
    "B3": dict(fam="B", kind="logit", H=30, money=1), "B4": dict(fam="B", kind="logit", H=60, money=1),
    "B5": dict(fam="B", kind="gbc", H=30, money=0), "B6": dict(fam="B", kind="gbc", H=60, money=0),
}
DELTA = {1: 0.6, 0: 0.5}
TWIN_WIN = {"A": (K.row(9, 20), K.row(14, 0)), "B": (K.row(9, 45), K.row(13, 30))}


# ------------------------------------------------------------------------------------------------ data
def calendar() -> list:
    s = set()
    for u in K.UNDS:
        s.update(d for d, D in r57data.load(u).items() if D.atm is not None and d >= K.START)
    return sorted(s)


def a_rows(cands: list) -> list:
    out = []
    for t in cands:
        D = r57data.load(t["und"])[t["day"]]
        f = M.row_a(t["und"], D, t["er"] - 1, t["side"], t["setup"])
        t = dict(t)
        t["f"] = f
        t["y_win"] = int(t["net"] > 0)
        t["y_ret"] = t["net"] / max(t["rE"] * t["qty"], 1e-9)
        t["exits"] = S.EXITS[t["setup"]]
        t["idx_spec"] = S.IDX_EXIT.get(t["setup"])
        out.append(t)
    return out


def b_rows(pts: list) -> list:
    for p in pts:
        D = r57data.load(p["und"])[p["day"]]
        p["f"] = M.row_b(p["und"], D, p["frow"])
    return pts


def load_or_build(name: str, lo: date, hi: date):
    path = K.OUT / f"{name}.pkl"
    if path.exists():
        with open(path, "rb") as f:
            return pickle.load(f)
    for u in K.UNDS:
        r57data.load(u)
    a = a_rows(S.candidates(lo, hi))
    b = b_rows(S.b_points(lo, hi))
    with open(path, "wb") as f:
        pickle.dump((a, b), f, protocol=pickle.HIGHEST_PROTOCOL)
    return a, b


# ------------------------------------------------------------------------------------------------ variants
def select_a(rows: list, sc: np.ndarray, thr: np.ndarray, kind: str) -> list:
    """Family A's take rule: each day, candidates in time order (same minute: highest score first); the first whose
    score passes (gbr: > 0; else >= its fold's threshold) is taken, then nothing more that day."""
    by = defaultdict(list)
    for i, r in enumerate(rows):
        if np.isfinite(sc[i]):
            by[r["day"]].append(i)
    trades = []
    for d in sorted(by):
        for i in sorted(by[d], key=lambda i: (rows[i]["er"], -sc[i])):
            if (sc[i] > 0) if kind == "gbr" else (sc[i] >= thr[i]):
                trades.append(rows[i])
                break
    return trades


def b_decision(p_up: float, mam: float, delta: float, qty: float, cost: float):
    """Family B at one point: expected index edge (2p - 1) x mean |move|, in option rupees edge x delta x lot; trade
    when that beats the expected round trip. Returns (side, rupees over cost) or None."""
    edge = (2 * p_up - 1) * mam
    if edge == 0 or not np.isfinite(edge):
        return None
    gain = abs(edge) * delta * qty
    return ("C" if edge > 0 else "P", gain - cost) if gain > cost else None


def run_a(v: dict, rows: list, months: list, cal: list):
    rows = [r for r in rows if v["c4"] or r["setup"] != "C4"]
    names = M.names_a(v["c4"])
    X = M.matrix([r["f"] for r in rows], names)
    y = np.array([r["y_ret"] if v["kind"] == "gbr" else r["y_win"] for r in rows])
    days = np.array([r["day"] for r in rows], dtype=object)
    sc, thr, _ = M.walk(days, X, y, v["kind"], months, cal)
    trades = select_a(rows, sc, thr, v["kind"])
    tested = np.isfinite(sc)
    yw = np.array([r["y_win"] for r in rows])
    return trades, dict(auc_wf=M.auc(yw[tested], sc[tested]), rows=len(rows), tested=int(tested.sum())), (rows, X, y)


def b_trade(p: dict, side: str, v: dict):
    r = p.get(f"{side}{v['H']}m{v['money']}")
    if r is None:
        return None
    t = dict(r)
    t.update(exits=S.B_EXITS[v["H"]], idx_spec=S.B_IDX, money=v["money"], setup=f"B{v['H']}")
    return t


def run_b(v: dict, pts: list, months: list, cal: list):
    H, money = v["H"], v["money"]
    pts = [p for p in pts if np.isfinite(p.get(f"mv{H}", np.nan))]
    names = M.names_b()
    X = M.matrix([p["f"] for p in pts], names)
    y = np.array([int(p[f"mv{H}"] > 0) for p in pts])
    days = np.array([p["day"] for p in pts], dtype=object)

    def mam(m, tr):
        acc = defaultdict(list)
        for i in np.flatnonzero(tr):
            acc[(pts[i]["und"], pts[i]["er"])].append(abs(pts[i][f"mv{H}"]))
        return {k: float(np.mean(x)) for k, x in acc.items()}

    sc, _, info = M.walk(days, X, y, v["kind"], months, cal, fold_fn=mam)
    by = defaultdict(list)
    for i, p in enumerate(pts):
        if np.isfinite(sc[i]):
            by[p["day"]].append(i)
    trades = []
    for d in sorted(by):
        done = False
        for er in S.B_TIMES:
            best = None
            for i in by[d]:
                p = pts[i]
                if p["er"] != er:
                    continue
                mm = info.get((d.year, d.month), {}).get((p["und"], er))
                if mm is None:
                    continue
                side = "C" if sc[i] > 0.5 else "P"
                prem = p[f"prem_{side}{money}"]
                if not np.isfinite(prem) or sc[i] == 0.5:
                    continue
                D = r57data.load(p["und"])[d]
                dec = b_decision(sc[i], mm, DELTA[money], float(K.lot(p["und"], d)),
                                 S.expected_cost(p["und"], D, prem, p[f"v5_{side}{money}"]))
                if dec is not None and (best is None or dec[1] > best[0]):
                    best = (dec[1], i, dec[0])
            if best is not None:
                t = b_trade(pts[best[1]], best[2], v)
                if t is not None:
                    trades.append(t)
                    done = True
            if done:
                break
    tested = np.isfinite(sc)
    s_t = sc[tested]
    pt = [pts[i] for i in np.flatnonzero(tested)]
    yc = [int(p[f"C{H}m{money}"]["net"] > 0) if p[f"C{H}m{money}"] else None for p in pt]
    yp = [int(p[f"P{H}m{money}"]["net"] > 0) if p[f"P{H}m{money}"] else None for p in pt]
    hs, hy = [], []
    for s_, a_, b_ in zip(s_t, yc, yp):
        if a_ is not None:
            hs.append(s_), hy.append(a_)
        if b_ is not None:
            hs.append(1 - s_), hy.append(b_)
    return trades, dict(auc_index_wf=M.auc(y[tested], s_t), auc_held_wf=M.auc(hy, hs), rows=len(pts),
                        tested=int(tested.sum())), (pts, X, y)


# ------------------------------------------------------------------------------------------------ statistics
def max_dd(daily) -> float:
    if not len(daily):
        return 0.0
    eq = np.concatenate([[0.0], np.cumsum(daily)])
    return float((np.maximum.accumulate(eq) - eq).max())


def stats(trades: list) -> dict:
    n = len(trades)
    if not n:
        return dict(trades=0, net=0.0, net_2x=0.0)
    net = np.array([t["net"] for t in trades])
    n2 = np.array([S.cost_2x(t) for t in trades])
    daily = defaultdict(float)
    monthly = defaultdict(float)
    for t in trades:
        daily[t["day"]] += t["net"]
        monthly[(t["day"].year, t["day"].month)] += t["net"]
    dv = np.array([daily[d] for d in sorted(daily)])
    top5 = float(np.sort(net)[::-1][:5].sum())
    tot = float(net.sum())
    per = defaultdict(lambda: [0, 0.0])
    for t in trades:
        per[t["und"]][0] += 1
        per[t["und"]][1] += t["net"]
    pset = defaultdict(lambda: [0, 0.0])
    for t in trades:
        pset[t.get("setup", "-")][0] += 1
        pset[t.get("setup", "-")][1] += t["net"]
    return dict(trades=n, win=round(float((net > 0).mean()), 3), net=round(tot), per_trade=round(tot / n),
                max_dd=round(max_dd(dv)), worst_day=round(float(dv.min())),
                months_green=f"{sum(v > 0 for v in monthly.values())}/{len(monthly)}",
                top5_share=round(top5 / tot, 3) if tot > 0 else None, ex_top5=round(tot - top5),
                net_2x=round(float(n2.sum())),
                per_index={u: dict(trades=a, net=round(b)) for u, (a, b) in sorted(per.items())},
                per_setup={u: dict(trades=a, net=round(b)) for u, (a, b) in sorted(pset.items())})


def twin_p(trades: list, fam: str, n: int = K.TWINS) -> tuple:
    if not trades:
        return None, None
    rng = np.random.default_rng(K.SEED)
    lo, hi = TWIN_WIN[fam]
    tot = S.twin_totals(trades, n, rng, lo, hi)
    act = sum(t["net"] for t in trades)
    return round((1 + int((tot >= act).sum())) / (n + 1), 4), round(float(tot.mean()))


def selection_p(trades: list, rows: list, n: int = K.TWINS) -> float | None:
    """A only: the same number of trades a day drawn at random from that day's candidates (not gated)."""
    if not trades:
        return None
    pool = defaultdict(list)
    for r in rows:
        pool[r["day"]].append(r["net"])
    rng = np.random.default_rng(K.SEED + 1)
    tot = np.zeros(n)
    for t in trades:
        x = np.array(pool[t["day"]])
        tot += x[rng.integers(0, len(x), size=n)]
    act = sum(t["net"] for t in trades)
    return round((1 + int((tot >= act).sum())) / (n + 1), 4)


def bh(p) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out


# ------------------------------------------------------------------------------------------------ references
def plain_solo(rows: list, lo: date, hi: date) -> list:
    """SoloMidday as the app runs it: S1 on NIFTY / BANKNIFTY / FINNIFTY, the strongest first (ties A-Z), 1 a day."""
    by = defaultdict(list)
    for r in rows:
        if r["setup"] == "S1" and r["und"] in K.SOLO_UNDS and lo <= r["day"] <= hi:
            by[r["day"]].append(r)
    return [sorted(v, key=lambda r: (-r["strength"], r["und"]))[0] for d, v in sorted(by.items())]


def in_months(rows: list, months: list) -> list:
    ms = set(months)
    return [r for r in rows if (r["day"].year, r["day"].month) in ms]


# ------------------------------------------------------------------------------------------------ steps
def design():
    for u in K.UNDS:
        r57data.load(u)
    cal = [d for d in calendar() if d <= K.DESIGN_END]
    a, b = load_or_build("design_data", K.START, K.DESIGN_END)
    out = dict(rows=dict(A=len(a), B=len(b)), variants={}, reference={})
    for vid, v in VARIANTS.items():
        if v["fam"] == "A":
            tr, info, (rows, X, y) = run_a(v, a, K.WF_MONTHS, cal)
            s_rand = M.random_split_auc(X, y, v["kind"])
            yw = np.array([r["y_win"] for r in rows])
            info["auc_random"] = M.auc(yw, s_rand)
        else:
            tr, info, (pts, X, y) = run_b(v, b, K.WF_MONTHS, cal)
            info["auc_index_random"] = M.auc(y, M.random_split_auc(X, y, v["kind"]))
        out["variants"][vid] = dict(**v, wf=stats(tr), **info)
        print(vid, json.dumps(out["variants"][vid], default=str)[:400], flush=True)
    wf_lo, wf_hi = M.month_start(*K.WF_MONTHS[0]), M.month_end(*K.WF_MONTHS[-1])
    out["reference"]["plain_solo_wf"] = stats(plain_solo(a, wf_lo, wf_hi))
    out["reference"]["plain_solo_design"] = stats(plain_solo(a, K.START, K.DESIGN_END))
    for s in ("S1", "S2", "S3", "S4", "C4"):
        out["reference"][f"{s}_all_wf"] = stats(in_months([r for r in a if r["setup"] == s], K.WF_MONTHS))
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps(out["reference"], default=str)[:1500])


def evaluate(a: list, b: list, months: list, cal: list, lo: date, hi: date) -> tuple:
    """Every variant over `months` (monthly refits, training on all rows before each month minus the embargo), twins,
    BH and the gates; the references on [lo, hi]. Used by `final` (the one read) and `dryrun` (design months only)."""
    out = dict(variants={}, reference={})
    trades = {}
    for vid, v in VARIANTS.items():
        if v["fam"] == "A":
            tr, info, (rows, X, y) = run_a(v, a, months, cal)
            info["p_selection"] = selection_p(tr, [r for r in rows if lo <= r["day"] <= hi])
            info = {("auc_test" if k == "auc_wf" else k): x for k, x in info.items()}
        else:
            tr, info, _ = run_b(v, b, months, cal)
            info = {k.replace("_wf", "_test"): x for k, x in info.items()}
        p, twin_mean = twin_p(tr, v["fam"])
        trades[vid] = tr
        out["variants"][vid] = dict(**v, test=stats(tr), p=p, twin_mean=twin_mean, **info)
        print(vid, json.dumps(out["variants"][vid], default=str)[:300], flush=True)
    ids = list(VARIANTS)
    ps = [out["variants"][i]["p"] if out["variants"][i]["p"] is not None else 1.0 for i in ids]
    qs = bh(ps)
    design_res = json.loads((K.RESULTS / "design.json").read_text())
    for i, q in zip(ids, qs):
        r = out["variants"][i]
        r["q"] = round(float(q), 4)
        f = r["test"]
        wf = design_res["variants"][i]["wf"]["net"]
        fails = []
        if f["net"] <= 0:
            fails.append("final net <= 0")
        if r["p"] is None or r["p"] >= K.GATE_P:
            fails.append("p >= 0.05")
        if r["q"] > K.GATE_Q:
            fails.append("q > 0.10")
        if f["trades"] < K.GATE_MIN_TRADES:
            fails.append("fewer than 40 trades")
        if f["trades"] and f.get("ex_top5", 0) <= 0:
            fails.append("top-5 >= net")
        if wf <= 0:
            fails.append("design walk-forward <= 0")
        r["verdict"] = "PASS" if not fails else "FAIL: " + "; ".join(fails)
    ps_ = plain_solo(a, lo, hi)
    p_ps, m_ps = twin_p(ps_, "A")
    out["reference"]["plain_solo"] = dict(stats(ps_), p=p_ps, twin_mean=m_ps)
    for s in ("S1", "S2", "S3", "S4", "C4"):
        out["reference"][f"{s}_all"] = stats(in_months([r for r in a if r["setup"] == s], months))
    return out, trades


def export_models(vids: list, a: list, b: list, cal: list, tag: str = "", out_dir=None) -> dict:
    """The deployment model of each variant (fitted on every labelled row), as JSON + golden vectors
    (results/models/<vid>.json, <vid>_golden.json). Returns {vid: max |sklearn - r57eval| on the golden rows}."""
    import r57eval
    d = K.RESULTS / "models" if out_dir is None else out_dir
    d.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(K.SEED)
    gaps = {}
    for vid in vids:
        v = VARIANTS[vid]
        if v["fam"] == "A":
            rows = [r for r in a if v["c4"] or r["setup"] != "C4"]
            names = M.names_a(v["c4"])
            X = M.matrix([r["f"] for r in rows], names)
            y = np.array([r["y_ret"] if v["kind"] == "gbr" else r["y_win"] for r in rows])
            m = M.fit(v["kind"], X, y)
            thr = 0.0 if v["kind"] == "gbr" else float(np.percentile(M.predict(v["kind"], m, X), 70))
            meta = dict(take="first candidate of the day (time order; same minute: highest score) passing the rule; "
                             "then nothing more that day", setups=SOURCE_A(v["c4"]))
            js = M.export(v["kind"], m, names, thr, ">" if v["kind"] == "gbr" else ">=", meta)
        else:
            H = v["H"]
            pts = [p for p in b if np.isfinite(p.get(f"mv{H}", np.nan))]
            names = M.names_b()
            X = M.matrix([p["f"] for p in pts], names)
            y = np.array([int(p[f"mv{H}"] > 0) for p in pts])
            m = M.fit(v["kind"], X, y)
            acc = defaultdict(list)
            for p in pts:
                acc[f"{p['und']}@{p['er']}"].append(abs(p[f"mv{H}"]))
            meta = dict(H=H, money=v["money"], delta=DELTA[v["money"]],
                        mean_abs_move={k: round(float(np.mean(x)), 4) for k, x in acc.items()},
                        edge="(2p-1) x mean_abs_move[index@row] x delta x lot > expected round trip; up -> call",
                        exits="index stop 0.3 ATR14 on a 1-min close; option stop -25%; +10% locked once +30%; "
                              f"{H} minutes; 15:10", times="09:45, 10:30, 12:00, 13:30; one trade a day")
            js = M.export(v["kind"], m, names, 0.5, ">=", meta)
        js["meta"]["trained_on"] = f"{min(cal)} - {max(cal)}{tag}"
        (d / f"{vid}.json").write_text(json.dumps(js, separators=(",", ":")))
        idx = rng.choice(len(X), size=min(25, len(X)), replace=False)
        sk = M.predict(v["kind"], m, X[idx])
        gold = []
        for i, s_ in zip(idx, sk):
            gold.append(dict(inputs=[None if not np.isfinite(x) else float(x) for x in X[i]], score=float(s_)))
        (d / f"{vid}_golden.json").write_text(json.dumps(dict(model=f"{vid}.json", rows=gold), indent=0))
        gaps[vid] = max(abs(r57eval.score(js, g["inputs"]) - g["score"]) for g in gold)
    return gaps


def SOURCE_A(with_c4: bool) -> dict:
    return {k: v for k, v in S.SOURCE.items() if with_c4 or k != "C4"}


def dryrun():
    """The final pipeline on the last 3 design months only (no final-test session is touched): catches crashes before
    the one read. Writes nothing to results/."""
    for u in K.UNDS:
        r57data.load(u)
    cal = [d for d in calendar() if d <= K.DESIGN_END]
    a, b = load_or_build("design_data", K.START, K.DESIGN_END)
    months = K.WF_MONTHS[-3:]
    out, _ = evaluate(a, b, months, cal, M.month_start(*months[0]), K.DESIGN_END)
    print(json.dumps(out["reference"]["plain_solo"], default=str)[:300])
    print({k: v["verdict"] for k, v in out["variants"].items()})


def final():
    if K.FINAL_FLAG.exists():
        raise SystemExit("R57: the final test was already read (results/FINAL_READ). One read only.")
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    K.FINAL_FLAG.write_text(f"final test read on {date.today()}\n")
    S.open_final()
    for u in K.UNDS:
        r57data.load(u)
    cal = calendar()
    a0, b0 = load_or_build("design_data", K.START, K.DESIGN_END)
    a1, b1 = load_or_build("final_data", K.FINAL_START, K.END)
    out, trades = evaluate(a0 + a1, b0 + b1, K.FINAL_MONTHS, cal, K.FINAL_START, K.END)
    out["rows"] = dict(A_final=len(a1), B_final=len(b1))
    (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    with open(K.OUT / "final_trades.pkl", "wb") as f:
        pickle.dump(trades, f)
    passed = [k for k, v in out["variants"].items() if v["verdict"] == "PASS"]
    gaps = export_models(sorted(set(["A1", "B2"] + passed)), a0 + a1, b0 + b1, cal)
    print("passed:", passed, "parity gaps:", gaps)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "dryrun":
        dryrun()
    elif cmd == "final":
        final()
    else:
        print(__doc__)
