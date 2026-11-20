"""R202 / R203 study (PREREG.md): extra trend-turn exits (R202) and other loss-cutting rules (R203) on top of what R72,
R51 and Liquidity 15+5 run today (V5), 1 lot.

    python3 -I research/hunt/r202_trend_exit/r202study.py design    # 21 Nov 2024 - 31 Mar 2026 -> results/design.json
    python3 -I research/hunt/r202_trend_exit/r202study.py final     # the holdout, read once -> results/final.json
    python3 -I research/hunt/r202_trend_exit/r202study.py decide    # the gate -> results/decision.json

Reuses R186's engine (r186engine.price, forced_nets), R176's units / walk and R152's picker module, unchanged. A
trend-turn exit is passed to the engine as the candidate's forced index-exit row `fx` (the earlier of the arm's own and
the rule's), the way R177 did. Pure parts in r202lib.py. Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import dataclasses
import json
import pickle
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r202cfg as K  # noqa: E402

K.set_env()
sys.path.insert(0, str(K.HUNT / "r186_fixed_profit"))
import numpy as np  # noqa: E402

import r186study as Z6  # noqa: E402  (R179 -> R177 -> R176 -> R152 -> R112 -> R106 -> R101 -> R51)
import r202lib as G  # noqa: E402

R7, S6 = Z6.R7, Z6.S6
_CTX: dict = {}


def boot(final: bool) -> dict:
    """The stack's modules with R51's window set to this build's stores. R51's own guard keeps every day after
    31 Mar 2026 closed unless `final` opens it."""
    M = R7.mods()
    k = M["r51data"].K
    k.WARM_START, k.START, k.END = K.STORE
    if final:
        M["C"].open_final()
    return M


# ================================================================================================ index context
def ctx(und: str) -> dict:
    """{day: dict(b5, tw5, ema5, st, st_prev[, c15, ema15])} for one index: 5-minute bars, the day's average price at
    each bar's close, EMA20 and Supertrend carried across sessions (causal: a bar reads only bars up to itself)."""
    if und in _CTX:
        return _CTX[und]
    days = R7.mods()["r51data"].load(und)
    ks = sorted(days)
    b5 = {d: G.bars(days[d].idx, 5) for d in ks}
    nb = K.NROWS // 5
    allc = np.concatenate([b5[d][3] for d in ks])
    allh = np.concatenate([b5[d][1] for d in ks])
    alll = np.concatenate([b5[d][2] for d in ks])
    e5 = G.ema(allc, K.EMA_N)
    st = G.supertrend_dir(allh, alll, allc)
    out = {}
    if und == K.MARKET:
        b15 = {d: G.bars(days[d].idx, 15) for d in ks}
        e15 = G.ema(np.concatenate([b15[d][3] for d in ks]), K.EMA_N)
        n15 = K.NROWS // 15
    for i, d in enumerate(ks):
        s = slice(i * nb, (i + 1) * nb)
        out[d] = dict(b5=b5[d], tw5=G.twap(days[d].idx)[4::5], ema5=e5[s], st=st[s], st_prev=int(st[i * nb - 1]) if i else 0)
        if und == K.MARKET:
            out[d].update(c15=b15[d][3], ema15=e15[i * n15:(i + 1) * n15])
    _CTX[und] = out
    return out


def signal_map(us: list) -> dict:
    """{(index, day): [(entry minute, side)]} of an arm's raw signals (every candidate, before the picker and the walk)."""
    out: dict = {}
    for key, cands, _, _ in us:
        out.setdefault((key[0], S6._d(key[1])), []).extend((int(c["er"]), c["side"]) for c in cands)
    return out


def trend_row(rule: str, und: str, day, c: dict, sigs: dict) -> int:
    """The rule's exit minute for one candidate (PREREG 3); -1 = never fires."""
    er, side = int(c["er"]), c["side"]
    d = S6._d(day)
    if rule in ("X1", "X2", "X3"):
        arms = {"X1": ("R51",), "X2": ("R72",), "X3": K.ARMS}[rule]
        return G.earlier(-1, min([r for r in (G.opposite_row(sigs[a].get((und, d), []), er, side) for a in arms) if r >= 0],
                                 default=-1))
    x = ctx(und).get(day)
    if x is None:
        return -1
    if rule == "X4":
        return G.x4_row(R7.mods()["r51data"].load(und)[day].idx, x["b5"], er, side)
    if rule == "X5":
        return G.two_wrong_row(x["b5"][3], x["ema5"], er, side)
    if rule == "X6":
        return G.two_wrong_row(x["b5"][3], x["tw5"], er, side)
    if rule == "X7":
        return G.flip_row(x["st"], x["st_prev"], er, side)
    if rule == "X8":
        ws = []
        for u in K.UNDS:
            y = ctx(u).get(day)
            if y is not None:
                ws.append(G.wrong(y["b5"][3], y["tw5"], side))
        return G.breadth_row(ws, er) if ws else -1
    if rule == "X9":
        y = ctx(K.MARKET).get(day)
        return G.market_row(y["c15"], y["ema15"], er, side) if y is not None else -1
    raise ValueError(rule)


# ================================================================================================ pieces
def key_c(key, c) -> tuple:
    return (key[0], S6._d(key[1]), int(c["er"]), c["side"], c.get("book"))


def key_t(t) -> tuple:
    return (t["und"], S6._d(t["day"]), int(t["er_sig"]), t["side"], t.get("book"))


def units(arm: str, lo, hi) -> tuple:
    """(raw units, units with 'fx_arm' (the arm's own index exit) and 'f_trend' on every candidate)."""
    us0 = S6.units(arm, lo, hi)
    usA, _ = R7.apply_rule(arm, us0, None)
    days = R7.mods()["r51data"].load
    for key, cands, _, _ in usA:
        idx = days(key[0])[key[1]].idx
        for c in cands:
            c["f_trend"] = G.trend_strength(idx, int(c["er"]), c["side"])
    return us0, usA


def with_exit(usA: list, rule: str, sigs: dict) -> list:
    """The units with the rule's exit added: fx = the earlier of the arm's own index exit and the rule's minute."""
    out = []
    for key, cands, res, how in usA:
        nc = []
        for c in cands:
            c2 = dict(c)
            c2["fx"] = G.earlier(c["fx_arm"], trend_row(rule, key[0], key[1], c, sigs))
            nc.append(c2)
        out.append((key, nc, res, how))
    return out


def price(arm: str, us: list, stop: float | None = None) -> list:
    """Today's rule (breakeven at +10% + the arm's lock) on the units' candidates; stop = S10's tighter entry stop."""
    import r186engine as Z
    M = R7.mods()
    ex = S6.exits(arm)
    if stop is not None:
        assert ex.stop > stop > 0
        ex = dataclasses.replace(ex, stop=stop)
    return [Z.price(key[0], M["r51data"].load(key[0])[key[1]], cands, ex, 1, -1.0, K.LOCK[arm]) for key, cands, _, _ in us]


def walk(arm: str, us: list, pr: list, lo, hi, keep_fn) -> list:
    tr = [t for t in S6.walk(arm, us, pr) if lo <= t["day"] <= hi]
    if arm == "LIQ":
        tr = [t for t in tr if t["und"] in K.LIQ_UNDS]
    return [t for t in tr if keep_fn(t)]


def lookup(us: list, pr: list) -> dict:
    return {key_c(key, c): t for (key, cands, _, _), row in zip(us, pr) for c, t in zip(cands, row) if t is not None}


def is_changed(t: dict, p) -> bool:
    return p is not None and t["xr"] < p["xr"] and p["xr"] - t["er"] >= 2


def exit_twins(arm: str, trades: list, plainB: dict, rng) -> tuple:
    """PREREG 6: (twin totals, changed, saved on B's losers, given up on B's winners). A changed trade is sold at a
    random minute strictly between its fill and B's exit, B's exits (breakeven, lock, the arm's index exit) active."""
    import r186engine as Z
    M = R7.mods()
    ex = S6.exits(arm)
    tot = np.zeros(K.TWINS)
    ch, saved, given = 0, 0.0, 0.0
    for t in trades:
        p = plainB.get(key_t(t))
        if not is_changed(t, p):
            tot += t["net"]
            continue
        ch += 1
        if p["net"] <= 0:
            saved += t["net"] - p["net"]
        else:
            given += t["net"] - p["net"]
        rows = M["S"].twin_rows(t["er"], p["xr"], K.TWINS, rng)
        D = M["r51data"].load(t["und"])[t["day"]]
        tot += Z.forced_nets(t["und"], D, t, ex, rows, 1, int(t.get("fx_arm", -1)), be_x=K.BE_X, lock=K.LOCK[arm])
    return tot, ch, round(saved), round(given)


def rng_for(arm: str, rule: str, w: str):
    return np.random.default_rng([K.SEED, K.ARMS.index(arm), K.RULES.index(rule), list(K.WINDOWS).index(w)])


def twin_row(actual: float, tw: np.ndarray) -> dict:
    return dict(p=round(G.twin_p(actual, tw), 5), p_normal=G.normal_p(actual, tw), mean=round(float(tw.mean())),
                sd=round(float(tw.std(ddof=1))), actual=round(actual))


def skip_keep(rule: str, base: list) -> tuple:
    """(keep mask over the baseline trades, the walk-forward plan or None)."""
    if rule in K.WF_SKIPS:
        plan = G.wf_plan(base, G.BUCKET[rule])
        return G.wf_keep(base, plan, G.BUCKET[rule]), {str(q): (None if b is None else str(b)) for q, b in plan.items()}
    if rule in K.DAY_CAP:
        return G.day_cap_keep(base, K.DAY_CAP[rule]), None
    if rule == "L2":
        return G.two_losers_keep(base), None
    raise ValueError(rule)


# ================================================================================================ the run
def evaluate(phase: str) -> dict:
    final = phase == "final"
    boot(final)
    lo, hi = K.SPAN if final else K.DESIGN
    wins = ("design", "holdout", "total", "last_year") if final else ("design",)
    twin_wins = ("design", "holdout") if final else ("design",)
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), phase=phase, span=f"{lo} .. {hi}", twins=K.TWINS,
                         seed=K.SEED, m_total=K.M_TOTAL)}
    prep = {arm: units(arm, lo, hi) for arm in K.ARMS}
    sigs = {arm: signal_map(prep[arm][1]) for arm in K.ARMS}
    keep_all = {}
    for arm in K.ARMS:
        us0, usA = prep[arm]
        if arm in K.PICK_THR:
            P = S6.picker()
            sc, thr = P.scores(arm, us0)
            assert abs(thr - K.PICK_THR[arm]) < 1e-12
            keep_fn = (lambda P, sc, thr: lambda t: P.kept(t, sc, thr))(P, sc, thr)
        else:
            keep_fn = lambda t: True  # noqa: E731
        prB = price(arm, usA)
        plainB = lookup(usA, prB)
        base = walk(arm, usA, prB, lo, hi, keep_fn)
        res = {"B": dict(windows={w: G.stats(G.in_window(base, *K.WINDOWS[w]), *K.WINDOWS[w]) for w in wins},
                         folds=[G.stats(G.in_window(base, *f), *f) for f in K.FOLDS])}
        keep_all[arm + ":B"] = base
        print(arm, "B", {w: (res["B"]["windows"][w]["trades"], res["B"]["windows"][w]["net"]) for w in wins}, flush=True)
        for rule in K.RULES:
            r: dict = {}
            if rule in K.EXIT_RULES:
                us = usA if rule == "S10" else with_exit(usA, rule, sigs)
                tr = walk(arm, us, price(arm, us, K.S10_STOP if rule == "S10" else None), lo, hi, keep_fn)
                r["changed"], r["effect"], r["twin"] = {}, {}, {}
                for w in wins:
                    tw_ = G.in_window(tr, *K.WINDOWS[w])
                    if w in twin_wins:
                        tot, ch, saved, given = exit_twins(arm, tw_, plainB, rng_for(arm, rule, w))
                        r["twin"][w] = twin_row(float(sum(t["net"] for t in tw_)), tot)
                    else:
                        ch = sum(1 for t in tw_ if is_changed(t, plainB.get(key_t(t))))
                        saved = round(sum(t["net"] - plainB[key_t(t)]["net"] for t in tw_
                                          if is_changed(t, plainB.get(key_t(t))) and plainB[key_t(t)]["net"] <= 0))
                        given = round(sum(t["net"] - plainB[key_t(t)]["net"] for t in tw_
                                          if is_changed(t, plainB.get(key_t(t))) and plainB[key_t(t)]["net"] > 0))
                    r["changed"][w] = ch
                    r["effect"][w] = dict(saved_on_losers=saved, given_up_on_winners=given)
            else:
                keep, plan = skip_keep(rule, base)
                tr = [t for t, k in zip(base, keep) if k]
                r["changed"], r["effect"], r["twin"] = {}, {}, {}
                if plan is not None:
                    r["plan"] = plan
                for w in wins:
                    bw = G.in_window(base, *K.WINDOWS[w])
                    kw = [k for t, k in zip(base, keep) if K.WINDOWS[w][0] <= t["day"] <= K.WINDOWS[w][1]]
                    rem = [t for t, k in zip(bw, kw) if not k]
                    r["changed"][w] = len(rem)
                    r["effect"][w] = dict(removed_net=round(sum(t["net"] for t in rem)),
                                          removed_losers=sum(1 for t in rem if t["net"] <= 0))
                    if w in twin_wins:
                        tot = G.skip_twins(np.array([t["net"] for t in bw], float), len(rem), K.TWINS, rng_for(arm, rule, w))
                        r["twin"][w] = twin_row(float(sum(t["net"] for t, k in zip(bw, kw) if k)), tot)
            r["windows"] = {w: G.stats(G.in_window(tr, *K.WINDOWS[w]), *K.WINDOWS[w]) for w in wins}
            r["folds"] = [G.stats(G.in_window(tr, *f), *f) for f in K.FOLDS]
            res[rule] = r
            keep_all[f"{arm}:{rule}"] = tr
            print(arm, rule, {w: (r["windows"][w]["trades"], r["windows"][w]["net"], r["changed"][w]) for w in wins},
                  {w: r["twin"][w]["p"] for w in twin_wins}, flush=True)
        out[arm] = res
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"r202_trades_{phase}.pkl").write_bytes(pickle.dumps(keep_all, protocol=pickle.HIGHEST_PROTOCOL))
    return out


def design() -> dict:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    out = evaluate("design")
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def final() -> dict:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if not (K.RESULTS / "design.json").exists():
        raise SystemExit("design first (PREREG 7)")
    if K.HOLDOUT_FLAG.exists() and (K.RESULTS / "final.json").exists():
        raise SystemExit("R202 / R203 holdout already read once: " + K.HOLDOUT_FLAG.read_text())
    if not K.HOLDOUT_FLAG.exists():
        K.HOLDOUT_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    out = evaluate("final")
    d = json.loads((K.RESULTS / "design.json").read_text())
    out["_meta"]["design_mismatch"] = [f"{a}:{r}" for a in K.ARMS for r in ("B",) + K.RULES
                                       if out[a][r]["windows"]["design"] != d[a][r]["windows"]["design"]]
    (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def decide() -> dict:
    r = json.loads((K.RESULTS / "final.json").read_text())
    ps = [G.p_for_bh(r[a][rule]["changed"]["holdout"], r[a][rule]["twin"]["holdout"]["p_normal"]) for a, rule in K.TESTS]
    qs = G.bh_total(ps, K.M_TOTAL)
    qf = G.bh_total(ps, len(K.TESTS))
    out = {"m_total": K.M_TOTAL, "tests": {}}
    for (a, rule), q, q60 in zip(K.TESTS, qs, qf):
        v, b = r[a][rule], r[a]["B"]
        g = G.gate(v["windows"], b["windows"], v["folds"], b["folds"], v["changed"], v["twin"]["holdout"]["p"], q)
        g.update(arm=a, rule=rule, study=K.STUDY[rule],
                 p_normal=G.p_for_bh(v["changed"]["holdout"], v["twin"]["holdout"]["p_normal"]), q_family=q60,
                 design_twin_p=v["twin"]["design"]["p"])
        out["tests"][f"{a}:{rule}"] = g
    out["passes"] = [k for k, g in out["tests"].items() if g["passes"]]
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    {"design": design, "final": final, "decide": decide}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
