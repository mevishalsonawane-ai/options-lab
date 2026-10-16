"""R73 study (PREREG.md): Donchian 20 with a trend filter (T1-T8), a wait of 1-minute candles (W2-W5) or both (C),
before the 1-ITM option is bought. Research only.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R51's clean stores (once)
    python research/hunt/r73/r73study.py design                # every variant on design, C's picks, walk-forward
    python research/hunt/r73/r73study.py final                 # the ONE final read (refuses a second), twins, BH
    python research/hunt/r73/r73study.py report                # results/*.json -> results/report.md

Signals: Donchian 20 on the INDEX's 5-minute bars (fam_r44.signal_list, the paper arm's own), filtered by r73rules on
the index only. Prices: R64's engine on R51's Amendment A clean stores, trade-taking exactly as
r64arms.donchian_trades (one open a side, at most 2 a side a day, entries 09:20-15:10, never on expiry day). Stores,
engine, twins, statistics, BH and gates are R51 / R64 / R71's tested code (r71study). Trades go to $R73_OUT (never
git); results/ holds small JSON and markdown only.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r73cfg as R  # noqa: E402
import r73rules as P  # noqa: E402

sys.path.insert(0, str(HERE.parent / "r71"))
import r71study as S  # noqa: E402  (stats, split, bh, max_dd, mods: R71's tested code)

VARIANTS = P.TRENDS + P.WAITS + ("C",)                       # 18 entry rules
TESTS = [f"{v}-{x}" for v in VARIANTS for x in R.EXIT_SETS]  # x 2 exit sets = 36 tests
stats, split, bh = S.stats, S.split, S.bh


# ================================================================================================ pure helpers
def spec_of(v: str, picks: dict | None = None):
    """(trend rule or None, wait rule or None) of a variant; C takes its (T, W) from `picks`."""
    if v == "base":
        return None, None
    if v in P.TRENDS:
        return v, None
    if v in P.WAITS:
        return None, v
    if v == "C":
        return picks["T"], picks["W"]
    raise ValueError(v)


def best_of(cands: dict) -> str:
    """PREREG 2C: the highest net; tie: the smaller max drawdown; then the earlier id. cands: {id: (net, max_dd)}."""
    order = list(cands)
    return min(order, key=lambda k: (-cands[k][0], cands[k][1], order.index(k)))


def pick_c(trades_p: dict, before: date | None = None) -> dict:
    """The best T and the best W on design trades under exits P (days < `before` when given)."""
    out = {}
    for fam, ids in (("T", P.TRENDS), ("W", P.WAITS)):
        c = {}
        for v in ids:
            tr = [t for t in trades_p[v] if before is None or t["day"] < before]
            dl = S.daily_of(tr)
            c[v] = (sum(t["net"] for t in tr), S.max_dd(np.array([dl[d] for d in sorted(dl)])))
        out[fam] = best_of(c)
    return out


def key(t) -> tuple:
    return t["und"], t["day"], t["side"], t["sig"]


def skip_compare(base: list, var: list) -> dict:
    """PREREG section 5: what the variant skipped and what it took that the baseline did not (matched on the signal:
    index, day, side and signal bar)."""
    kb = {key(t): t for t in base}
    kv = {key(t): t for t in var}
    skipped = [t for k, t in kb.items() if k not in kv]
    extra = [t for k, t in kv.items() if k not in kb]
    los = [t for t in skipped if t["net"] <= 0]
    big = [t for t in skipped if t.get("ret", -1) >= R.BIG - 1e-9]
    return dict(skipped=len(skipped), skipped_net=round(sum(t["net"] for t in skipped)),
                losers_avoided=len(los), losers_avoided_rs=round(-sum(t["net"] for t in los)),
                winners_missed=sum(1 for t in skipped if t["net"] > 0),
                winners_missed_rs=round(sum(t["net"] for t in skipped if t["net"] > 0)),
                big_missed=len(big), big_missed_rs=round(sum(t["net"] for t in big)),
                extra=len(extra), extra_net=round(sum(t["net"] for t in extra)))


def skip_twins(base_nets, n_var: int, n: int, rng) -> np.ndarray:
    """Random-skip twins: each drops k = (baseline trades - variant trades) baseline trades at random (k <= 0: none).
    Returns (n,) totals."""
    base_nets = np.asarray(base_nets, float)
    k = max(0, len(base_nets) - n_var)
    tot = np.full(n, base_nets.sum())
    if k == 0 or not len(base_nets):
        return tot
    for j in range(n):
        tot[j] -= base_nets[rng.choice(len(base_nets), size=k, replace=False)].sum()
    return tot


def p_of(tw, actual: float) -> float:
    """R51's convention: (1 + twins >= actual) / (N + 1)."""
    return round((1 + int((np.asarray(tw) >= actual).sum())) / (len(tw) + 1), 4)


def gates(final: dict, design_net: float, wf_net: float, p, q) -> list:
    return S.gates(final, design_net, wf_net, p, q)


def decide(tests: dict) -> dict:
    """PREREG section 4. tests: {test: dict(verdict, skip_q, design_net, wf_net, final, base)}. Returns the recommended
    test per exit set (None = the baseline stays) and the loss-reduction trade-offs."""
    rec, trade = {}, []
    for x in R.EXIT_SETS:
        best = None
        for v in VARIANTS:
            t = tests[f"{v}-{x}"]
            b = t["base"]
            beats = (t["design_net"] >= b["design_net"] and t["wf_net"] >= b["wf_net"]
                     and t["final"]["net"] >= b["final"]["net"])
            if t["verdict"] == "PASS" and (t["skip_q"] is not None and t["skip_q"] <= R.GATE_Q) and beats:
                if best is None or t["design_net"] > tests[best]["design_net"]:
                    best = f"{v}-{x}"
            f, bf = t["final"], b["final"]
            if (f.get("trades") and f["net"] < bf["net"] and f["max_dd"] < bf["max_dd"]
                    and f["worst_day"] > bf["worst_day"]):
                trade.append(f"{v}-{x}")
        rec[x] = best
    return dict(recommended=rec, tradeoffs=trade)


# ================================================================================================ data side
_F: dict = {}


def feats(und: str):
    """(flat index of (day, b), features) of an index's 5-minute series (cached)."""
    if und not in _F:
        d, b, o, h, l, c = S.mods()["fam_r44"].bars5(und)
        pos = {(d[i], int(b[i])): i for i in range(len(d))}
        _F[und] = (pos, P.features(d, b, o, h, l, c))
    return _F[und]


def don_signals(und: str) -> dict:
    """{day: [(b, side)]}: the paper arm's Donchian 20 signals (fam_r44.signal_list)."""
    return S.mods()["fam_r44"].signal_list(und, "donchian20")


_C1: dict = {}


def c1_signals(und: str) -> dict:
    """{day: [(b, side)]}: R72's C1 Breadth Donchian (4 of 5 break on the same bar), from R72's own code."""
    if not _C1:
        sys.path.insert(0, str(HERE.parent / "r72"))
        import r72study as X
        for u in S.mods()["K"].UNDS:
            sig = X.rule_signals(u, "C1", 4)
            _C1[u] = {d: [(er // 5 - 1, side) for er, side, _ in v] for d, v in sig.items()}
    return _C1[und]


def exits(x: str):
    M = S.mods()
    s = R.EXIT_SETS.get(x) or R.CHECK_SETS[x]
    return M["E"].Exits(stop=s["stop"] if s["stop"] is not None else -1.0,
                        target=s["target"] if s["target"] is not None else -1.0,
                        steps=tuple(s["steps"]), tmax=s["tmax"] if s["tmax"] else -1, sq_row=M["K"].row(*s["sq"]))


def candidates(und: str, D, sigs: list, trend: str | None, wait: str | None) -> list:
    """The day's candidate entries of one index: Donchian's signals that pass the trend filter (at the signal bar's
    close) and the wait (on the next N one-minute candles), in entry order."""
    M = S.mods()
    K, E = M["K"], M["E"]
    last = K.row(*R.LAST_ROW_HM)
    pos, F = feats(und)
    out = []
    for b, side in sigs:
        s = 1 if side == "C" else -1
        er0 = 5 * (b + 1)
        i = pos.get((D.d, int(b)))
        if trend is not None or wait is not None:
            if i is None:
                continue
            if trend is not None:
                tp = P.tp_avg(D.idx, 5 * b + 4) if trend == "T8" else float("nan")
                if not P.trend_ok(trend, F, i, s, tp):
                    continue
        er = er0
        if wait is not None:
            level = F["hi"][i] if s > 0 else F["lo"][i]
            er = P.wait_entry(D.idx, b, s, level, F["c"][i], wait)
            if er is None:
                continue
        if er < R.FIRST_ROW or er > last:
            continue
        k = E.strike_for(D, und, side, er - 1, R.MONEY)
        if k is not None:
            out.append(dict(er=er, side=side, strike=k, sig=er0))
    out.sort(key=lambda c: c["er"])
    return out


def trades(v: str, x: str, lo: date, hi: date, picks: dict | None = None, source: str = "don") -> list:
    """Every trade of variant v (or 'base') under exit set x in [lo, hi]."""
    M = S.mods()
    C, G, r51data = M["C"], M["G"], M["r51data"]
    trend, wait = spec_of(v, picks)
    ex = exits(x)
    rows = []
    for und in M["K"].UNDS:
        sig = don_signals(und) if source == "don" else c1_signals(und)
        days = r51data.load(und)
        for d in C.days_in(und, lo, hi):
            D = days[d]
            if D.expiry or d not in sig:
                continue
            rows += G.take(und, D, candidates(und, D, sig[d], trend, wait), ex, max_per_side=R.MAX_PER_SIDE)
    return rows


def wait_cost(base: list, var: list) -> dict | None:
    """PREREG section 5, waits: on signals taken by both, the index points moved in the break's direction between the
    two fills, and the option fill paid (real fills, Rs per lot and %)."""
    M = S.mods()
    kb = {key(t): t for t in base}
    pts, rs, pc = [], [], []
    for t in var:
        b = kb.get(key(t))
        if b is None:
            continue
        D = M["r51data"].load(t["und"])[t["day"]]
        o = D.idx[0]
        a, z = o[b["er"]], o[t["er"]]
        s = 1 if t["side"] == "C" else -1
        if np.isfinite(a) and np.isfinite(z):
            pts.append(s * (z - a))
        rs.append((t["rE"] - b["rE"]) * t["qty"])
        pc.append(t["rE"] / b["rE"] - 1.0)
    if not rs:
        return None
    return dict(n=len(rs), points_mean=round(float(np.mean(pts)), 1) if pts else None,
                points_median=round(float(np.median(pts)), 1) if pts else None,
                rs_lot_mean=round(float(np.mean(rs))), rs_lot_median=round(float(np.median(rs))),
                pct_mean=round(float(np.mean(pc)), 4), pct_median=round(float(np.median(pc)), 4),
                same_strike=round(float(np.mean([kb[key(t)]["strike"] == t["strike"] for t in var if key(t) in kb])), 4))


def _strip(tr: list) -> list:
    keep = ("und", "day", "side", "strike", "er", "xr", "E", "X", "rE", "rX", "qty", "why", "net", "peak", "ret", "sig")
    return [{k: t[k] for k in keep if k in t} for t in tr]


def _save(name: str, obj):
    R.OUT.mkdir(parents=True, exist_ok=True)
    with open(R.OUT / f"{name}.pkl", "wb") as f:
        pickle.dump(obj, f, protocol=pickle.HIGHEST_PROTOCOL)


def _load(name: str):
    with open(R.OUT / f"{name}.pkl", "rb") as f:
        return pickle.load(f)


def _json(obj, path: Path):
    path.write_text(json.dumps(obj, indent=1, default=str) + "\n")


def wf_net_of(tr: list) -> float:
    return round(sum(t["net"] for t in split(tr, R.WF_FOLDS[0][0], R.WF_FOLDS[-1][1])))


def months(lo: date, hi: date) -> float:
    return S.months_between(lo, hi)


# ================================================================================================ phases
def design() -> dict:
    lo, hi = R.START, R.DESIGN_END
    mo = months(lo, hi)
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), base={}, variants={}, check={})
    tr_all = {}
    for x in ("XB",) + tuple(R.EXIT_SETS):
        b = trades("base", x, lo, hi)
        _save(f"base_{x}_design", _strip(b))
        tr_all[("base", x)] = b
        st = stats(b, mo)
        out["check"][x] = dict(net=st["net"], expect=R.EXPECT.get((x, "design")), trades=st["trades"])
        if x in R.EXIT_SETS:
            out["base"][x] = dict(design=st, wf=stats(split(b, R.WF_FOLDS[0][0], hi)), wf_net=wf_net_of(b))
        print("base", x, st["trades"], st["net"], flush=True)
    for x in R.EXIT_SETS:
        for v in VARIANTS[:-1]:
            t = trades(v, x, lo, hi)
            tr_all[(v, x)] = t
            _save(f"{v}_{x}_design", _strip(t))
            print(v, x, len(t), round(sum(z["net"] for z in t)), flush=True)
    trades_p = {v: tr_all[(v, "P")] for v in VARIANTS[:-1]}
    picks = pick_c(trades_p)
    wf_picks = [pick_c(trades_p, a) for a, _ in R.WF_FOLDS]
    out["c_pick"], out["c_wf_picks"] = picks, wf_picks
    for x in R.EXIT_SETS:
        c = trades("C", x, lo, hi, picks)
        _save(f"C_{x}_design", _strip(c))
        tr_all[("C", x)] = c
        wf = []
        for (a, z), pk in zip(R.WF_FOLDS, wf_picks):
            wf += split(c if pk == picks else trades("C", x, a, z, pk), a, z)
        out["variants"][f"C-{x}"] = dict(design=stats(c, mo), wf=stats(wf), wf_net=round(sum(t["net"] for t in wf)),
                                         vs_base=skip_compare(tr_all[("base", x)], c))
        print("C", x, picks, len(c), round(sum(z["net"] for z in c)), "WF", out["variants"][f"C-{x}"]["wf_net"],
              flush=True)
    for x in R.EXIT_SETS:
        b = tr_all[("base", x)]
        for v in VARIANTS[:-1]:
            t = tr_all[(v, x)]
            sk = skip_twins([z["net"] for z in b], len(t), R.TWINS, np.random.default_rng(R.SEED))
            out["variants"][f"{v}-{x}"] = dict(design=stats(t, mo), wf=stats(split(t, R.WF_FOLDS[0][0], hi)),
                                               wf_net=wf_net_of(t), vs_base=skip_compare(b, t),
                                               skip_p_design=p_of(sk, sum(z["net"] for z in t)),
                                               wait_cost=wait_cost(b, t) if v in P.WAITS else None)
        c = tr_all[("C", x)]
        sk = skip_twins([z["net"] for z in b], len(c), R.TWINS, np.random.default_rng(R.SEED))
        out["variants"][f"C-{x}"]["skip_p_design"] = p_of(sk, sum(z["net"] for z in c))
        out["variants"][f"C-{x}"]["wait_cost"] = wait_cost(b, c)
    # labelled, non-gated extra: the best design variant (under P) on top of R72's C1 Breadth Donchian
    best = max(VARIANTS, key=lambda v: out["variants"][f"{v}-P"]["design"]["net"])
    c1 = trades("base", "P", lo, hi, source="c1")
    c1v = trades(best, "P", lo, hi, picks, source="c1")
    _save("C1_P_design", _strip(c1))
    _save("C1x_P_design", _strip(c1v))
    out["extra_c1"] = dict(variant=best, c1=stats(c1, mo), c1_expect=R.EXPECT[("C1", "design")],
                           c1_plus=stats(c1v, mo), vs_c1=skip_compare(c1, c1v),
                           c1_wf_net=wf_net_of(c1), c1_plus_wf_net=wf_net_of(c1v))
    print("extra C1", best, out["extra_c1"]["c1"]["net"], out["extra_c1"]["c1_plus"]["net"], flush=True)
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    _json(out, R.RESULTS / "design.json")
    return out


def final() -> dict:
    """The ONE final read: 36 tests, random-entry twins, random-skip twins, BH over the 36 (both), gates, decision,
    the same-days comparison with Donchian, and the labelled C1 extra."""
    dj = R.RESULTS / "design.json"
    if not dj.exists():
        raise SystemExit("run `design` first")
    if R.FINAL_FLAG.exists():
        raise SystemExit("R73's final period was already read once (results/FINAL_READ); a second read is refused")
    R.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    des = json.loads(dj.read_text())
    M = S.mods()
    M["C"].open_final()
    lo, hi = R.FINAL_START, M["K"].END
    mo = months(lo, hi)
    picks = des["c_pick"]
    out = dict(read=R.FINAL_FLAG.read_text().strip(), end=str(hi), sessions=len(S.sessions(lo, hi)), base={},
               tests={}, check={})
    base = {}
    for x in ("XB",) + tuple(R.EXIT_SETS):
        b = trades("base", x, lo, hi)
        _save(f"base_{x}_final", _strip(b))
        base[x] = b
        st = stats(b, mo)
        out["check"][x] = dict(net=st["net"], expect=R.EXPECT.get((x, "final")), trades=st["trades"])
        if x in R.EXIT_SETS:
            out["base"][x] = st
        print("base final", x, st["trades"], st["net"], flush=True)
    ps, sps = [], []
    for tname in TESTS:
        v, x = tname.split("-")
        t = trades(v, x, lo, hi, picks)
        _save(f"{v}_{x}_final", _strip(t))
        st = stats(t, mo)
        net = sum(z["net"] for z in t)
        p = tm = None
        if t:
            tw = M["A"].donchian_twins(M["E"], M["K"], M["r51data"], M["G"], t, exits(x), R.TWINS,
                                       np.random.default_rng(R.SEED))
            p, tm = p_of(tw, net), round(float(tw.mean()))
        sk = skip_twins([z["net"] for z in base[x]], len(t), R.TWINS, np.random.default_rng(R.SEED))
        sp = p_of(sk, net)
        dv = des["variants"][tname]
        bd = des["base"][x]
        out["tests"][tname] = dict(
            variant=v, exits=x, final=st, p=p, twin_mean=tm, skip_p=sp, skip_twin_mean=round(float(sk.mean())),
            skip_p_design=dv["skip_p_design"], design=dv["design"], design_net=dv["design"]["net"], wf_net=dv["wf_net"],
            vs_base=skip_compare(base[x], t), wait_cost=wait_cost(base[x], t) if (v in P.WAITS or v == "C") else None,
            base=dict(design_net=bd["design"]["net"], wf_net=bd["wf_net"], final=out["base"][x]))
        ps.append(p if p is not None else 1.0)
        sps.append(sp)
        print(tname, st["trades"], st["net"], p, sp, flush=True)
    for (tname, q, sq) in zip(TESTS, bh(ps), bh(sps)):
        r = out["tests"][tname]
        r["q"], r["skip_q"] = round(float(q), 4), round(float(sq), 4)
        r["fails"] = gates(r["final"], r["design_net"], r["wf_net"], r["p"], r["q"])
        r["verdict"] = "PASS" if not r["fails"] else "FAIL"
        if r["verdict"] == "PASS":                      # Amendment A's check: twice the spread and slippage (info)
            E = M["E"]
            old = E.SLIP
            E.SLIP = E.SLIP2
            try:
                r["slip2"] = stats(trades(r["variant"], r["exits"], lo, hi, picks), mo)
            finally:
                E.SLIP = old
    out["decision"] = decide(out["tests"])
    # labelled, non-gated extra (the variant was fixed on design): on top of R72's C1 Breadth Donchian
    best = des["extra_c1"]["variant"]
    c1 = trades("base", "P", lo, hi, source="c1")
    c1v = trades(best, "P", lo, hi, picks, source="c1")
    _save("C1_P_final", _strip(c1))
    _save("C1x_P_final", _strip(c1v))
    sk = skip_twins([z["net"] for z in c1], len(c1v), R.TWINS, np.random.default_rng(R.SEED))
    out["extra_c1"] = dict(variant=best, c1=stats(c1, mo), c1_expect=R.EXPECT[("C1", "final")], c1_plus=stats(c1v, mo),
                           vs_c1=skip_compare(c1, c1v), skip_p=p_of(sk, sum(z["net"] for z in c1v)))
    _json(out, R.RESULTS / "final.json")
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "report":
        import r73report
        r73report.write()
    else:
        raise SystemExit(__doc__)
