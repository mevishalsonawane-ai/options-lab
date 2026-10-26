"""R105 study (PREREG.md): Donchian giveback. Ten exits aimed at the trades that go +Rs 1,000-2,000 and end in a loss,
on C (R72 Breadth Donchian) and B (R51 Donchian 20 + Supertrend), gated over design, walk-forward and final.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R51's clean stores (once)
    python research/hunt/r105_donchian_giveback/r105study.py design   # baselines check, cohort, rules, twins (design)
    python research/hunt/r105_donchian_giveback/r105study.py final    # the ONE final read: rules, twins, 2x slip, BH,
                                                                      # gate, cohort, paper bootstrap
    python research/hunt/r105_donchian_giveback/r105study.py oos      # after final: 14-19 Oct (own store copy), once
    python research/hunt/r105_donchian_giveback/r105study.py report   # results/*.json -> results/report.md

Signals and trade-taking are R75's (`r75study.candidates`, verbatim): Donchian 20 on the index's 5-minute bars (B with
R73's Supertrend agreement, C with R72's 4-of-5 breadth), 1-ITM, entries 09:20-15:10, one open a side per index, at
most 2 a side a day, never on the index's own expiry day, 1 lot. Prices: r105engine (R64's simulator + the new
exits) on R51's Amendment A clean stores.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r105lib as L  # noqa: E402

EXT = os.environ.get("R105_EXT", "0") == "1"      # the OOS process: stores to 19 Oct in their own folder


def _setup():
    """R75's study modules, pointed at the extended store copy when EXT (before anything loads a store)."""
    sys.path.insert(0, str(HERE.parent / "r75"))
    sys.path.insert(0, str(HERE.parent / "r51"))
    os.environ["R51_CLEAN"] = "1"
    import r51cfg as K
    if EXT:
        K.END = L.OOS_END
        K.OUT = K.OUT.parent / "clean_ext19"
    import r75study as S
    return S, K


S, K = _setup()
import r105engine as X  # noqa: E402


def mods():
    return S.S.mods()


# ================================================================================================ candidates
_LV: dict = {}


def breakout_levels(und: str):
    """{(day, bar): (prior-20 high, prior-20 low)} of the index's 5-minute series (the channel the signal broke)."""
    if und not in _LV:
        d, b, o, h, l, c = mods()["fam_r44"].bars5(und)
        out = {}
        for i in range(20, len(d)):
            out[(d[i], int(b[i]))] = (float(h[i - 20:i].max()), float(l[i - 20:i].min()))
        _LV[und] = out
    return _LV[und]


def candidates(arm: str, und: str, D, sig: list) -> list:
    out = S.candidates(arm, und, D, sig, (), S.R.MONEY)
    lv = breakout_levels(und)
    for c in out:
        hh, ll = lv.get((D.d, c["er"] // 5 - 1), (np.nan, np.nan))
        c["lvl"] = hh if c["side"] == "C" else ll
    return out


# ================================================================================================ pricing
def price(und: str, D, cands: list, spec: dict, forced=None, slip=None):
    """Price candidates of one day under one engine spec: list of dicts (None = no fill)."""
    M = mods()
    E, G = M["E"], M["G"]
    slip = slip or E.SLIP
    st = E.stack(und, D)
    ci = np.asarray([st.idx(c["side"], c["strike"]) for c in cands], np.int64)
    e = np.asarray([c["er"] for c in cands], np.int64)
    n = len(cands)
    fx = np.full(n, -1, np.int64) if forced is None else np.asarray(forced, np.int64)
    qty = float(K.lot(und, D.d))
    sq = K.row(*L.P_SQ)
    if D.expiry:
        sq = min(sq, K.SQ_ROW_EXPIRY)
    old = E.SLIP
    E.SLIP = slip
    try:
        be = G.be_levels(und, D, st.S, ci, e, sq, qty)
    finally:
        E.SLIP = old
    lvl = np.asarray([c.get("lvl", np.nan) for c in cands], float)
    sgn = np.asarray([1 if c["side"] == "C" else -1 for c in cands], np.int64)
    steps = np.array(spec["steps"], dtype=np.float64).reshape(-1, 2) if spec["steps"] else np.zeros((0, 2))
    res, er, xr, rs = X.simulate_many(st.S, ci, e, sq, fx, L.P_STOP, spec["target"], steps, L.P_TMAX, -1.0, -1.0,
                                      K.TICK, qty, be, spec["be_rs"], spec["tt_off"], spec["tt_pk"], spec["gb_trig"],
                                      spec["gb_keep"], spec["idx_mode"], spec["idx_arm"], lvl, sgn,
                                      np.ascontiguousarray(D.idx), *slip)
    rE, rX = np.nan_to_num(res[:, 2]), np.nan_to_num(res[:, 3])
    ch = E.charges(und, rE, rX, qty, D.d) if n else np.zeros(0)
    out = []
    for i, c in enumerate(cands):
        if rs[i] == 0:
            out.append(None)
            continue
        E_ = float(res[i, 0])
        out.append(dict(und=und, day=D.d, side=c["side"], strike=float(c["strike"]), er=int(c["er"]), ef=int(er[i]),
                        xr=int(xr[i]), E=E_, X=float(res[i, 1]), rE=float(rE[i]), rX=float(rX[i]), qty=qty,
                        why=X_REASONS.get(int(rs[i]), str(int(rs[i]))),
                        net=float((rX[i] - rE[i]) * qty - ch[i]), pk=float(res[i, 4] / E_ - 1.0),
                        pk_rs=float((res[i, 4] - E_) * qty), lvl=float(c.get("lvl", np.nan))))
    return out


X_REASONS = {1: "stop", 2: "lock", 3: "target", 4: "time", 5: "squareoff", 6: "rule_exit", 7: "missing",
             8: "close_stop", 9: "index_exit"}


def price_rule(und, D, cands, rule, forced=None, slip=None):
    """Per candidate (net, busy-until row, trade dict) under a rule; X9 = mean of its two legs (per lot)."""
    spec = L.rule_spec(rule)
    if spec.get("part"):
        a = price(und, D, cands, spec["leg1"], forced, slip)
        b = price(und, D, cands, spec["leg2"], forced, slip)
        out = []
        for x, y in zip(a, b):
            if x is None or y is None:
                out.append(None)
                continue
            t = dict(y, net=0.5 * (x["net"] + y["net"]), xr=max(x["xr"], y["xr"]), leg1=x["net"], leg2=y["net"],
                     why=f"{x['why']}+{y['why']}")
            out.append(t)
        return out
    return price(und, D, cands, spec, forced, slip)


def walk(cands: list, res: list) -> list:
    """R64's take: one open a side, at most MAX_PER_SIDE fills a side a day (time order)."""
    out, busy, n_side = [], {}, {}
    for c, r in zip(cands, res):
        if r is None:
            continue
        if c["er"] <= busy.get(c["side"], -1):
            continue
        if n_side.get(c["side"], 0) >= S.R.MAX_PER_SIDE:
            continue
        busy[c["side"]] = r["xr"]
        n_side[c["side"]] = n_side.get(c["side"], 0) + 1
        out.append(r)
    return out


def days_of(lo, hi):
    M = mods()
    out = []
    for und in K.UNDS:
        days = M["r51data"].load(und)
        for d in M["C"].days_in(und, lo, hi):
            out.append((und, days[d]))
    return out


_CANDS: dict = {}


def day_cands(arm, lo, hi):
    k = (arm, lo, hi)
    if k not in _CANDS:
        out = []
        for und, D in days_of(lo, hi):
            sig = S.signals(arm, und)
            if D.expiry or D.d not in sig:
                continue
            cs = candidates(arm, und, D, sig[D.d])
            if cs:
                out.append((und, D, cs))
        _CANDS[k] = out
    return _CANDS[k]


def run(arm, rule, lo, hi, slip=None):
    """(re-walked trades under the rule, the baseline's trades re-priced under the rule without re-walking)."""
    walked, fixed = [], []
    for und, D, cs in day_cands(arm, lo, hi):
        base = walk(cs, price_rule(und, D, cs, None, slip=slip))
        rr = price_rule(und, D, cs, rule, slip=slip) if rule else None
        if rule is None:
            walked += base
            fixed += base
            continue
        walked += walk(cs, rr)
        idx = {(c["er"], c["side"]): i for i, c in enumerate(cs)}
        for t in base:
            fixed.append(rr[idx[(t["er"], t["side"])]])
    return walked, fixed


# ================================================================================================ twins
def forced_nets(arm):
    def fn(t, rows):
        D = mods()["r51data"].load(t["und"])[t["day"]]
        c = dict(er=t["er"], side=t["side"], strike=t["strike"], lvl=t["lvl"])
        cs = [c] * len(rows)
        res = price(t["und"], D, cs, L.rule_spec(None), forced=rows)
        return [r["net"] if r is not None else 0.0 for r in res]
    return fn


def twin_p(arm, rule, base, fixed):
    changed = sum(1 for a, b in zip(base, fixed) if abs(a["net"] - b["net"]) > 0.5 or a["xr"] != b["xr"])
    rng = np.random.default_rng(L.SEED + L.TESTS.index(f"{arm}:{rule}"))
    w = 0.5 if L.RULES[rule].get("part") else 1.0
    tw = L.twin_totals(base, changed, L.TWINS, rng, forced_nets(arm), leg_weight=w)
    act = sum(t["net"] for t in fixed)
    return L.p_of(tw, act), changed, round(float(np.mean(tw)))


# ================================================================================================ phases
def _check(arm, phase, trades):
    n, net = len(trades), round(sum(t["net"] for t in trades))
    exp = L.EXPECT[(arm, phase)]
    if (n, net) != exp:
        raise SystemExit(f"baseline {arm} {phase} does not reproduce: {(n, net)} vs {exp}")


def _dump(name, obj):
    L.OUT.mkdir(parents=True, exist_ok=True)
    with open(L.OUT / f"{name}.pkl", "wb") as f:
        pickle.dump(obj, f, protocol=pickle.HIGHEST_PROTOCOL)


def _json(obj, path):
    L.RESULTS.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1, default=str) + "\n")


def phase_block(arm, lo, hi, phase, twins=True):
    base, _ = run(arm, None, lo, hi)
    if phase in ("design", "final"):
        _check(arm, phase, base)
    _dump(f"{arm}_base_{phase}", base)
    co = L.cohort(base)
    out = dict(base=L.stats(base), base_q=L.quarters(base) if phase == "design" else None, cohort=co, rules={})
    if phase == "design":
        out["base_wf_net"] = round(sum(t["net"] for t in L.split(base, L.WF_START, L.WF_END)))
    lost_keys = co["rs1000_lost"]["keys"]
    pct_keys = co["pct8_25_lost"]["keys"]
    wins_keys = [L.key(t) for t in base if t["net"] > 0]
    for rule in L.RULES:
        walked, fixed = run(arm, rule, lo, hi)
        _dump(f"{arm}_{rule}_{phase}", walked)
        deltas = [b["net"] - a["net"] for a, b in zip(base, fixed)]
        r = dict(stats=L.stats(walked), fixed_net=round(sum(t["net"] for t in fixed)),
                 cohort_rs1000_lost=L.same_trades(fixed, lost_keys),
                 cohort_pct8_25_lost=L.same_trades(fixed, pct_keys),
                 base_winners_after=L.same_trades(fixed, wins_keys),
                 base_winners=L.same_trades(base, wins_keys),
                 ex_top5=round(L.ex_top5_gain(deltas)))
        if phase == "design":
            r["q"] = L.quarters(walked)
            r["wf_net"] = round(sum(t["net"] for t in L.split(walked, L.WF_START, L.WF_END)))
        if twins:
            r["p"], r["changed"], r["twin_mean"] = twin_p(arm, rule, base, fixed)
        else:
            r["changed"] = sum(1 for a, b in zip(base, fixed) if abs(a["net"] - b["net"]) > 0.5)
        out["rules"][rule] = r
        print(phase, arm, rule, r["stats"]["trades"], r["stats"]["net"], "base", out["base"]["net"], "p", r.get("p"),
              flush=True)
    return out


def design():
    lo, hi = L.START, L.DESIGN_END
    out = dict(phase="design", lo=str(lo), hi=str(hi), arms={})
    for arm in L.ARMS:
        out["arms"][arm] = phase_block(arm, lo, hi, "design")
    _json(out, L.RESULTS / "design.json")


def paper_bootstrap(arm, final_base, wf_base):
    rng = np.random.default_rng(L.SEED)
    out = {}
    refs = [("C", L.PAPER["C"])] if arm == "C" else [("B", L.PAPER["B"]), ("B14", L.PAPER["B14"]),
                                                    ("B14plain", L.PAPER["B14plain"])]
    for nm, ref in refs:
        for per, tr in (("final", final_base), ("wf", wf_base)):
            tr = sorted(tr, key=lambda t: (t["day"], t["er"], t["und"]))
            nets = [t["net"] for t in tr]
            dn = L.daily(tr)
            out[f"{nm}_{per}"] = dict(paper=ref, iid=L.pct_le(L.boot_iid(nets, ref["n"], L.BOOT, rng), ref["net"]),
                                      runs=L.pct_le(L.runs(nets, ref["n"]), ref["net"]),
                                      sessions6=L.pct_le(L.runs([dn[d] for d in sorted(dn)], L.PAPER_SESSIONS),
                                                         ref["net"]))
    return out


def final():
    if L.FINAL_FLAG.exists():
        raise SystemExit("R105's final period was already read once (results/FINAL_READ); a second read is refused")
    des = json.loads((L.RESULTS / "design.json").read_text())
    L.RESULTS.mkdir(parents=True, exist_ok=True)
    L.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    mods()["C"].open_final()
    lo, hi = L.FINAL_START, L.FINAL_END
    out = dict(phase="final", lo=str(lo), hi=str(hi), read=L.FINAL_FLAG.read_text().strip(), arms={}, tests={})
    E = mods()["E"]
    for arm in L.ARMS:
        blk = phase_block(arm, lo, hi, "final")
        b2, _ = run(arm, None, lo, hi, slip=E.SLIP2)
        blk["base_slip2_net"] = round(sum(t["net"] for t in b2))
        for rule in L.RULES:
            w2, _ = run(arm, rule, lo, hi, slip=E.SLIP2)
            blk["rules"][rule]["slip2_net"] = round(sum(t["net"] for t in w2))
        wf_base, _ = run(arm, None, L.WF_START, L.WF_END)
        blk["paper_bootstrap"] = paper_bootstrap(arm, _load(f"{arm}_base_final"), wf_base)
        blk["cohort_wf"] = L.cohort(wf_base)
        out["arms"][arm] = blk
    prior = L.prior_family()
    new_p = {}
    for arm in L.ARMS:
        for rule in L.RULES:
            new_p[f"{arm}:{rule}"] = max(des["arms"][arm]["rules"][rule]["p"], out["arms"][arm]["rules"][rule]["p"])
    q = L.family_q(new_p, prior)
    q_own = dict(zip(new_p, [round(float(x), 4) for x in L.bh(list(new_p.values()))]))
    out["family"] = dict(new=len(new_p), prior=len(prior), prior_p_below_005=sum(1 for _, p in prior if p < 0.05))
    for arm in L.ARMS:
        D_, F_ = des["arms"][arm], out["arms"][arm]
        for rule in L.RULES:
            t = f"{arm}:{rule}"
            dr, fr = D_["rules"][rule], F_["rules"][rule]
            r = dict(design=dr["stats"], base_design=D_["base"], final=fr["stats"], base_final=F_["base"],
                     wf_net=dr["wf_net"], base_wf_net=D_["base_wf_net"], q=dr["q"], base_q=D_["base_q"],
                     p_design=dr["p"], p_final=fr["p"], q_bh=q[t], q_own20=q_own[t], changed_design=dr["changed"],
                     changed_final=fr["changed"], ex_top5_design=dr["ex_top5"], slip2_final_net=fr["slip2_net"],
                     base_slip2_final_net=F_["base_slip2_net"])
            r["gate"] = L.gate(r)
            out["tests"][t] = r
            print(t, "PASS" if r["gate"]["passed"] else "fail", r["gate"]["failed"], flush=True)
    _json(out, L.RESULTS / "final.json")


def _load(name):
    with open(L.OUT / f"{name}.pkl", "rb") as f:
        return pickle.load(f)


def oos():
    """14-19 Oct, own extended store copy (R105_EXT=1). Read once, after the final; never gated."""
    if not EXT:
        raise SystemExit("run with R105_EXT=1 (and build the extended store first: r105study.py build_ext)")
    if not L.FINAL_FLAG.exists():
        raise SystemExit("the OOS window is read only after the final read")
    if L.OOS_FLAG.exists():
        raise SystemExit("R105's OOS window was already read once (results/OOS_READ)")
    L.OOS_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    mods()["C"].open_final()
    out = dict(phase="oos", lo=str(L.OOS_START), hi=str(L.OOS_END), arms={})
    for arm in L.ARMS:
        blk = phase_block(arm, L.OOS_START, L.OOS_END, "oos", twins=False)
        blk["trades"] = [{k: (str(v) if k == "day" else v) for k, v in t.items()}
                         for t in _load(f"{arm}_base_oos")]
        out["arms"][arm] = blk
    _json(out, L.RESULTS / "oos.json")


def build_ext():
    if not EXT:
        raise SystemExit("run with R105_EXT=1")
    import r51data
    for u in K.UNDS:
        print(r51data.build(u), flush=True)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    {"design": design, "final": final, "oos": oos, "build_ext": build_ext}.get(cmd, lambda: print(__doc__))()
