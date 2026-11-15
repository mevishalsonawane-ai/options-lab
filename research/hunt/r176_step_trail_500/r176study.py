"""R176 study (PREREG.md): Boss's Rs 500 step trail per lot vs today's rules on Liquidity 15+5, R72 and R51 (+ Supertrend),
1-3 lots, with and without R151's pickers, in design / walk-forward / final and the last 6 months.

    R176_POST=0 python research/hunt/r176_step_trail_500/r176study.py design   # 13 Oct stores; design + walk-forward
    R51_CLEAN=1 python research/hunt/r106_small_targets/r106study.py post-build   # stores to 19 Oct (clean_post), once
    R176_POST=1 python research/hunt/r176_step_trail_500/r176study.py final    # read ONCE; twins
    R176_POST=1 python research/hunt/r176_step_trail_500/r176study.py six      # 20 Apr - 19 Oct, month by month
    python research/hunt/r176_step_trail_500/r176study.py decide               # BH + gates -> results/decision.json
    python research/hunt/r176_step_trail_500/r176report.py                     # results/report.md

Variants: A today's rules; S + the step trail; B / B+S the same on the trades R151's pickers take (R72 / R51; Liquidity's
B = A). Only S vs A is gated (9 tests). Pure parts take plain lists; test_r176.py covers them.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import Counter
from datetime import date, datetime, timezone

import numpy as np

import r176cfg as K

_M: dict = {}


def mods():
    if not _M:
        import r152study as R
        M = R.mods()
        import r152engine as Y
        import r176engine as Z
        _M.update(M, R=R, Y=Y, Z=Z)
    return _M


# ================================================================================================ pure
def exit_mix(trades: list) -> dict:
    """Rs 500 entry stop / step trail / other (other's -15% stop shown as own_stop)."""
    c = Counter(t.get("why", "?") for t in trades)
    return dict(stop500=c.get("stop500", 0), step=c.get("step", 0),
                other=sum(v for k, v in c.items() if k not in ("stop500", "step")), own_stop=c.get("stop", 0),
                reasons=dict(sorted(c.items())))


def monthly(trades: list, months: tuple) -> dict:
    """Net per calendar month over `months` (a month with no trade is Rs 0)."""
    out = {m: 0.0 for m in months}
    for t in trades:
        k = K.month_of(t["day"])
        out[k] = out.get(k, 0.0) + t["net"]
    return {m: round(v) for m, v in out.items()}


def worst_month(trades: list, months: tuple) -> tuple:
    m = monthly(trades, months)
    k = min(m, key=lambda x: m[x])
    return k, m[k]


def together(by_arm: dict) -> list:
    """The arms' trades summed (each arm trades on its own; their trades simply add up)."""
    out = []
    for a in K.ARMS:
        out += [dict(t, arm=a) for t in by_arm.get(a, [])]
    out.sort(key=lambda t: (t["day"], t["arm"]))
    return out


def in_span(trades: list, period: str) -> list:
    lo, hi = K.span_of(period)
    return [t for t in trades if lo <= t["day"] <= hi]


def gates(per: dict, q_all: float) -> dict:
    """PREREG section 7. per: {period: {'S': stats, 'A': stats}} for design, wf, final, six (stats: net, max_dd,
    worst_month {net}, trades)."""
    fails = []
    if not all(per[p]["S"]["net"] > 0 for p in K.PERIODS):
        fails.append("not positive in every period")
    if not all(per[p]["S"]["net"] > per[p]["A"]["net"] for p in ("design", "wf", "final")):
        fails.append("net not above today's rules (design, walk-forward, final)")
    if not q_all <= K.GATE_Q:
        fails.append(f"exit-twin BH q {q_all:.2f} > 0.10")
    if per["final"]["S"]["trades"] < K.MIN_FINAL:
        fails.append("< 40 final trades")
    risk = [p for p in K.PERIODS if per[p]["S"]["max_dd"] > K.MAX_DD or per[p]["S"]["worst_month"]["net"] < K.WORST_MONTH]
    if risk:
        fails.append("outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in " + ", ".join(risk))
    return dict(passed=not fails, fails=fails, q_all=round(float(q_all), 4))


def bh_q(ps: list, m_total: int = K.M_TOTAL) -> list:
    """Benjamini-Hochberg q with the earlier tests taken as p = 1 (R106's bh)."""
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


# ================================================================================================ data
def _d(x) -> date:
    return x if type(x) is date else x.date()


def units(arm: str, lo: date, hi: date) -> list:
    M = mods()
    if arm == "LIQ":
        return M["S"].prepare("LIQ", lo, hi)
    return M["S12"].units_of(arm, lo, hi)


def exits(arm: str):
    return mods()["S"].arm_exits(arm)


def priced(arm: str, us: list, on: bool, lots: int) -> list:
    M = mods()
    ex = exits(arm)
    return [M["Z"].price(key[0], M["r51data"].load(key[0])[key[1]], cands, ex, on, lots) for key, cands, _, _ in us]


def walk(arm: str, us: list, pr: list) -> list:
    tr = mods()["S"].run_rule(arm, us, pr, None)     # today's taking walk; no re-entries
    for t in tr:
        t["day"] = _d(t["day"])
    return tr


def sessions(lo: date, hi: date) -> list:
    return mods()["S"].sessions_in(lo, hi)


def picker():
    import r152picker as P                             # R151's app model numbers and the app's keep rule
    return P


def describe(trades: list, n_sess: int, months: tuple) -> dict:
    s = mods()["S"].stats(trades, n_sess)
    wm = worst_month(trades, months)
    return dict(trades=s["trades"], win=s["win"], net=s["net"], avg_win=s["avg_win"], avg_loss=s["avg_loss"],
                worst_day=s["worst_day"], max_dd=s["max_dd"], largest_loss=s["largest_loss"],
                worst_month=dict(month=wm[0], net=wm[1]), months=monthly(trades, months), mix=exit_mix(trades),
                rupee_stop_share=(round(sum(t.get("stop_kind") == "rupee" for t in trades) / len(trades), 4)
                                  if trades and any("stop_kind" in t for t in trades) else None))


def _save(name: str, obj):
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


# ================================================================================================ twins
def exit_twins(arm: str, trades: list, plain: dict, lots: int, n: int, rng) -> tuple:
    """PREREG section 6: (twin totals (n,), changed count). plain: {(und, day, er_sig, side): A-priced trade at `lots`}."""
    M = mods()
    ex = exits(arm)
    tot = np.zeros(n)
    ch = 0
    for t in trades:
        p = plain.get((t["und"], _d(t["day"]), t["er_sig"], t["side"]))
        if p is None or not (t["xr"] < p["xr"] and p["xr"] - t["er"] >= 2):
            tot += t["net"]
            continue
        ch += 1
        rows = M["S"].twin_rows(t["er"], p["xr"], n, rng)
        D = M["r51data"].load(t["und"])[t["day"]]
        tot += M["Y"].forced_exit_nets(t["und"], D, t, ex, rows, lots, t.get("fx_idx", -1))
    return tot, ch


# ================================================================================================ phases
def evaluate(phase: str) -> dict:
    """Price and walk every arm x lots x variant over the phase's span; describe each period inside it."""
    assert K.POST == K.STORES_OF[phase], f"{phase} needs R176_POST={int(K.STORES_OF[phase])}"
    M = mods()
    lo, hi = (K.START, K.DESIGN_END) if phase == "design" else K.span_of(phase)
    periods = ("design", "wf") if phase == "design" else (phase,)
    out = {"_meta": dict(span=f"{lo} .. {hi}", stores="19 Oct" if K.POST else "13 Oct"), "parity": {}}
    trades = {}                                         # trades[(variant, lots)][arm]
    rng = np.random.default_rng(K.SEED)
    twins = {}
    for arm in K.ARMS:
        us = units(arm, lo, hi)
        sc = thr = P = None
        if arm in K.PICKER_ARMS:
            P = picker()
            sc, thr = P.scores(arm, us)
        for lots in K.LOTS:
            pr0 = priced(arm, us, False, lots)
            if lots == 1:                               # rule off = R101's own pricing, candidate by candidate
                same = all((x is None) == (y is None) and (x is None or abs(x["net"] - y["net"]) < 1e-6)
                           for a_, b_ in zip(pr0, [u[2] for u in us]) for x, y in zip(a_, b_))
                assert same, ("engine parity", arm, phase)
            a = walk(arm, us, pr0)
            s = walk(arm, us, priced(arm, us, True, lots))
            if lots == 1 and phase in ("design", "final"):
                pub = K.PARITY[(arm, phase)]
                n0, net0 = len(a), round(sum(t["net"] for t in a))
                r = dict(trades=n0, net=net0, published=list(pub), reproduced=(n0 == pub[0] and abs(net0 - pub[1]) <= 1))
                if P is not None and phase == "final":
                    par = P.T.kept_walk(arm, us, sc, thr)
                    pp = K.PICKER_PARITY_FINAL[arm]
                    r["picker"] = dict(trades=len(par), net=round(sum(t["net"] for t in par)), published=list(pp))
                    r["picker"]["reproduced"] = r["picker"]["trades"] == pp[0] and abs(r["picker"]["net"] - pp[1]) <= 1
                out["parity"][arm] = r
                print(arm, phase, "parity", r, flush=True)
                assert r["reproduced"] and r.get("picker", {}).get("reproduced", True), ("parity failed", arm, phase, r)
            for v, tr in (("A", a), ("S", s)):
                tr = [t for t in tr if lo <= t["day"] <= hi]
                trades.setdefault((v, lots), {})[arm] = tr
                b = "B" if v == "A" else "B+S"
                trades.setdefault((b, lots), {})[arm] = tr if P is None else [t for t in tr if P.kept(t, sc, thr)]
            if phase == "final":
                plain = {(t["und"], _d(t["day"]), c["er"], c["side"]): t
                         for (key, cands, _, _), row in zip(us, pr0) for c, t in zip(cands, row) if t is not None}
                tw, ch = exit_twins(arm, trades[("S", lots)][arm], plain, lots, K.TWINS, rng)
                act = sum(t["net"] for t in trades[("S", lots)][arm])
                twins[f"{arm}@{lots}"] = dict(p=round(M["S"].twin_p(act, tw), 4), mean=round(float(tw.mean())), changed=ch)
                print(arm, lots, "twin", twins[f"{arm}@{lots}"], flush=True)
            print(arm, phase, lots, {v: (len(trades[(v, lots)][arm]), round(sum(t["net"] for t in trades[(v, lots)][arm])))
                                     for v in K.VARIANTS}, flush=True)
    for per in periods:
        plo, phi = K.span_of(per)
        n_sess = len(sessions(plo, phi))
        months = K.months_between(plo, phi)
        res = {"sessions": n_sess}
        for (v, lots), by_arm in trades.items():
            sub = {a_: [t for t in by_arm[a_] if plo <= t["day"] <= phi] for a_ in K.ARMS}
            res[f"{v}@{lots}"] = {a_: describe(sub[a_], n_sess, months) for a_ in K.ARMS}
            res[f"{v}@{lots}"]["ALL"] = describe(together(sub), n_sess, months)
        if per == "wf":
            res["wf_q"] = {f"{v}@{lots}": {a_: [round(sum(t["net"] for t in by_arm[a_] if q0 <= t["day"] <= q1))
                                               for q0, q1 in K.WF_Q] for a_ in K.ARMS}
                           for (v, lots), by_arm in trades.items()}
        out[per] = res
    if twins:
        out["twins"] = twins
    _save(phase, trades)
    return out


def run(phase: str) -> dict:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    M = mods()
    if phase in ("final", "six"):
        if phase == "final":
            if K.FINAL_FLAG.exists():
                raise SystemExit("R176 final already read once: " + K.FINAL_FLAG.read_text())
            K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        elif not (K.RESULTS / "final.json").exists():
            raise SystemExit("the 6-month window is read after the final")
        M["C"].open_final()
    out = evaluate(phase)
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def decide() -> dict:
    r = {ph: json.loads((K.RESULTS / f"{ph}.json").read_text()) for ph in ("design", "final", "six")}
    per_src = {"design": r["design"]["design"], "wf": r["design"]["wf"], "final": r["final"]["final"],
               "six": r["six"]["six"]}
    ps = [r["final"]["twins"][f"{a}@{n}"]["p"] for a, n in K.TESTS]
    q_all = bh_q(ps)
    q_own = bh_q(ps, len(K.TESTS))
    out = {}
    for (a, n), qa, qo in zip(K.TESTS, q_all, q_own):
        per = {p: dict(S=per_src[p][f"S@{n}"][a], A=per_src[p][f"A@{n}"][a]) for p in K.PERIODS}
        g = gates(per, qa)
        g.update(q_r176=round(qo, 4), twin_p=r["final"]["twins"][f"{a}@{n}"]["p"])
        out[f"{a}@{n}"] = g
    out["_m"] = dict(r176=len(K.TESTS), prior=K.N_PRIOR, total=K.M_TOTAL)
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final", "six"):
        run(cmd)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
