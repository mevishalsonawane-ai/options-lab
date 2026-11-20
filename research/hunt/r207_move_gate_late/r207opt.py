"""R207 / R208 option runner (PREREG.md): the expected-move gate as a skip on today's rules (V5), and the late-day break
as a standalone rule with real costs. R202's build (option data to 5 Oct 2026), R186's engine, unchanged.

    python3 -I research/hunt/r207_move_gate_late/r207opt.py design    # 21 Nov 2024 - 31 Mar 2026 -> results/design.json
    python3 -I research/hunt/r207_move_gate_late/r207opt.py final     # the holdout (a second look) -> results/final.json
    python3 -I research/hunt/r207_move_gate_late/r207opt.py decide    # the gates -> results/decision.json (needs r207idx fresh)

Pure parts in r207lib.py. Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import dataclasses
import json
import pickle
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r207cfg as K  # noqa: E402

sys.path.insert(0, str(K.R202))
import numpy as np  # noqa: E402

import r202cfg as K2  # noqa: E402
import r202lib as G  # noqa: E402
import r207lib as L  # noqa: E402

WINDOWS = K2.WINDOWS


def stack():
    import r202study as S2                 # sets R202's environment and imports the R186 stack
    return S2


# ================================================================================================ R207 features
def add_features(S2, trades: list) -> None:
    """ratio / x / rt on every trade, from the index's own minutes before the trade's signal minute and its previous 20
    sessions (the store's days; the store starts on 2 Sep 2024)."""
    load = S2.R7.mods()["r51data"].load
    order, cache = {}, {}
    for t in trades:
        u = t["und"]
        if u not in order:
            order[u] = sorted(load(u))
        k = (u, t["day"], int(t["er_sig"]))
        if k not in cache:
            days = load(u)
            i = order[u].index(t["day"])
            prev = [days[d].idx for d in order[u][max(0, i - K.PREV_N):i]]
            cache[k] = L.features(days[t["day"]].idx, prev, int(t["er_sig"]))
        t.update(cache[k])


def gate_keep(rule: str, base: list) -> np.ndarray:
    if rule == "G-RT85":
        return L.rt_keep([t["rt"] for t in base])
    field, q = K.GATES[rule]
    return L.wf_keep([t["day"] for t in base], [t[field] for t in base], q)


def rng_for(seed: int, *ids):
    return np.random.default_rng([seed, *ids])


# ================================================================================================ R208 LATE rule
def late_units(usA: list) -> list:
    out = []
    for key, cands, res, how in usA:
        idx = [j for j, c in enumerate(cands) if L.is_late(c["er"])]
        if idx:
            out.append((key, [cands[j] for j in idx], [res[j] for j in idx], how))
    return out


def late_trades(S2, arm: str, us: list, sq: int, lo, hi) -> list:
    """The standalone LATE rule: today's exits with the square-off at row `sq`, no picker, its own caps (the arm's walk on
    the late breaks only)."""
    import r186engine as Z
    M = S2.R7.mods()
    ex0 = S2.S6.exits(arm)
    assert ex0.sq_row == K.SQ["15"], "the arms square off at 15:15 today"
    ex = dataclasses.replace(ex0, sq_row=sq)
    pr = [Z.price(key[0], M["r51data"].load(key[0])[key[1]], cands, ex, 1, -1.0, K2.LOCK[arm]) for key, cands, _, _ in us]
    return [t for t in S2.S6.walk(arm, us, pr) if lo <= t["day"] <= hi]


def daily(trades: list) -> list:
    d = defaultdict(float)
    for t in trades:
        d[t["day"]] += t["net"]
    return [d[k] for k in sorted(d)]


# ================================================================================================ the run
def evaluate(phase: str) -> dict:
    final = phase == "final"
    S2 = stack()
    S2.boot(final)
    lo, hi = K2.SPAN if final else K2.DESIGN
    wins = ("design", "holdout", "total", "last_year") if final else ("design",)
    twin_wins = ("design", "holdout") if final else ("design",)
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), phase=phase, span=f"{lo} .. {hi}", m_total=K.M_TOTAL,
                         note="the holdout is a second look (PREREG section 0)"), "gate": {}, "late": {}}
    keep_all = {}
    for ai, arm in enumerate(K.ARMS):
        us0, usA = S2.units(arm, lo, hi)
        if arm in K2.PICK_THR:
            P = S2.S6.picker()
            sc, thr = P.scores(arm, us0)
            keep_fn = (lambda P, sc, thr: lambda t: P.kept(t, sc, thr))(P, sc, thr)
        else:
            keep_fn = lambda t: True  # noqa: E731
        base = S2.walk(arm, usA, S2.price(arm, usA), lo, hi, keep_fn)
        add_features(S2, base)
        keep_all[arm + ":B"] = base
        res = {"B": dict(windows={w: G.stats(G.in_window(base, *WINDOWS[w]), *WINDOWS[w]) for w in wins},
                         folds=[G.stats(G.in_window(base, *f), *f) for f in K2.FOLDS],
                         known={f: int(sum(1 for t in base if t[f] == t[f])) for f in ("ratio", "x", "rt")})}
        print(arm, "B", {w: (res["B"]["windows"][w]["trades"], res["B"]["windows"][w]["net"]) for w in wins}, res["B"]["known"], flush=True)
        for gi, (a, rule) in enumerate(K.GATE_TESTS):
            if a != arm:
                continue
            keep = gate_keep(rule, base)
            tr = [t for t, k in zip(base, keep) if k]
            r: dict = dict(changed={}, effect={}, twin={})
            for wi, w in enumerate(wins):
                a0, b0 = WINDOWS[w]
                bw = [t for t in base if a0 <= t["day"] <= b0]
                kw = [k for t, k in zip(base, keep) if a0 <= t["day"] <= b0]
                rem = [t for t, k in zip(bw, kw) if not k]
                r["changed"][w] = len(rem)
                r["effect"][w] = dict(removed_net=round(sum(t["net"] for t in rem)), removed_losers=sum(1 for t in rem if t["net"] <= 0))
                if w in twin_wins:
                    tot = G.skip_twins(np.array([t["net"] for t in bw], float), len(rem), K.TWINS, rng_for(K.SEED_GATE, gi, wi))
                    act = float(sum(t["net"] for t, k in zip(bw, kw) if k))
                    r["twin"][w] = dict(p=round(G.twin_p(act, tot), 5), p_normal=G.normal_p(act, tot), mean=round(float(tot.mean())),
                                        sd=round(float(tot.std(ddof=1))), actual=round(act))
            r["windows"] = {w: G.stats(G.in_window(tr, *WINDOWS[w]), *WINDOWS[w]) for w in wins}
            r["folds"] = [G.stats(G.in_window(tr, *f), *f) for f in K2.FOLDS]
            res[rule] = r
            print(arm, rule, {w: (r["windows"][w]["trades"], r["windows"][w]["net"], r["changed"][w]) for w in wins},
                  {w: r["twin"][w]["p"] for w in twin_wins}, flush=True)
        out["gate"][arm] = res
        if arm in K.LATE_ARMS:
            us_late = late_units(usA)
            lr = {"today": {w: G.stats([t for t in G.in_window(base, *WINDOWS[w]) if L.is_late(t["er_sig"])], *WINDOWS[w]) for w in wins}}
            for ti, (tag, sq) in enumerate(K.SQ.items()):
                tr = late_trades(S2, arm, us_late, sq, lo, hi)
                keep_all[f"{arm}:LATE{tag}"] = tr
                c = dict(windows={w: G.stats(G.in_window(tr, *WINDOWS[w]), *WINDOWS[w]) for w in wins},
                         folds=[G.stats(G.in_window(tr, *f), *f) for f in K2.FOLDS], boot={})
                for wi, w in enumerate(twin_wins):
                    c["boot"][w] = L.boot_mean_pos(daily(G.in_window(tr, *WINDOWS[w])), K.BOOT, rng_for(K.SEED_LATE, ai, ti, wi))
                lr[tag] = c
                print(arm, "LATE", tag, {w: (c["windows"][w]["trades"], c["windows"][w]["win"], c["windows"][w]["net"]) for w in wins},
                      {w: round(c["boot"][w]["p"], 4) for w in twin_wins}, flush=True)
            out["late"][arm] = lr
    K2.OUT.mkdir(parents=True, exist_ok=True)
    (K2.OUT / f"r207_trades_{phase}.pkl").write_bytes(pickle.dumps(keep_all, protocol=pickle.HIGHEST_PROTOCOL))
    return out


def design() -> dict:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    out = evaluate("design")
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def final() -> dict:
    if not (K.RESULTS / "design.json").exists():
        raise SystemExit("design first (PREREG 5)")
    if K.HOLDOUT_FLAG.exists() and (K.RESULTS / "final.json").exists():
        raise SystemExit("R207 / R208 holdout already read: " + K.HOLDOUT_FLAG.read_text())
    if not K.HOLDOUT_FLAG.exists():
        K.HOLDOUT_FLAG.write_text(datetime.now(timezone.utc).isoformat() + " (a second look: PREREG section 0)\n")
    out = evaluate("final")
    d = json.loads((K.RESULTS / "design.json").read_text())
    mm = [f"{a}:{r}" for a in K.ARMS for r in out["gate"][a] if out["gate"][a][r]["windows"]["design"] != d["gate"][a][r]["windows"]["design"]]
    mm += [f"{a}:LATE{t}" for a in K.LATE_ARMS for t in K.SQ if out["late"][a][t]["windows"]["design"] != d["late"][a][t]["windows"]["design"]]
    out["_meta"]["design_mismatch"] = mm
    (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def decide() -> dict:
    """One BH family of 15 (PREREG 4), then each part's own gate."""
    r = json.loads((K.RESULTS / "final.json").read_text())
    ix = json.loads((K.RESULTS / "index_fresh.json").read_text())
    ids, ps = [], []
    for a, rule in K.GATE_TESTS:
        v = r["gate"][a][rule]
        ids.append(("gate", a, rule))
        ps.append(G.p_for_bh(v["changed"]["holdout"], v["twin"]["holdout"]["p_normal"]))
    for h in K.INDEX_TESTS:
        ids.append(("index", h, None))
        ps.append(ix["tests"][h]["late"]["p_normal"] if ix["tests"][h]["late"]["n"] >= K.MIN_SIGNALS else 1.0)
    for a, t in K.LATE_TESTS:
        ids.append(("late", a, t))
        ps.append(r["late"][a][t]["boot"]["holdout"]["p_normal"])
    qs = L.bh_total(ps, K.M_TOTAL)
    qf = L.bh_total(ps, len(ps))
    out = {"m_total": K.M_TOTAL, "gate": {}, "index": {}, "late": {}}
    for (kind, a, b), p, q, q15 in zip(ids, ps, qs, qf):
        if kind == "gate":
            v, base = r["gate"][a][b], r["gate"][a]["B"]
            g = G.gate(v["windows"], base["windows"], v["folds"], base["folds"], v["changed"], v["twin"]["holdout"]["p"], q)
            g.update(p_normal=p, q_family=q15, design_twin_p=v["twin"]["design"]["p"])
            out["gate"][f"{a}:{b}"] = g
        elif kind == "index":
            g = L.gate_index(ix["tests"][a]["late"], q)
            g.update(p_normal=p, q_family=q15)
            out["index"][a] = g
        else:
            c = r["late"][a][b]
            g = L.gate_late(c["windows"], c["folds"], c["boot"]["holdout"]["p"], q)
            g.update(p_normal=p, q_family=q15)
            out["late"][f"{a}:{b}"] = g
    out["clears"] = ([k for k, g in out["gate"].items() if g["passes"]] + [k for k, g in out["index"].items() if g["holds"]]
                     + [f"LATE {k}" for k, g in out["late"].items() if g["passes"]])
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    {"design": design, "final": final, "decide": decide}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
