"""R75 study (PREREG.md): Donchian, less loss. Levers L1-L7 (20 grid points) on B (Donchian 20 + Supertrend) and C
(Breadth Donchian), gated against each baseline. Research only.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R51's clean stores (once)
    python research/hunt/r75/r75study.py design                # baselines check, every lever on design + quarters
    python research/hunt/r75/r75study.py final                 # the ONE final read (refuses a second), twins, BH
    python research/hunt/r75/r75study.py report                # results/*.json -> results/report.md

Signals: Donchian 20 on the INDEX's 5-minute bars (fam_r44.signal_list, the paper arm's own); B adds R73's Supertrend
(10, 3) agreement at the signal bar (r73rules, verbatim); C is R72's C1 (r72study.rule_signals). Prices: R64's engine on
R51's Amendment A clean stores, trade-taking exactly as R73 (one open a side, at most 2 a side a day, entries
09:20-15:10, 1-ITM, never on the index's own expiry day). Trades go to $R75_OUT (never git).
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
import r75cfg as R  # noqa: E402
import r75levers as L  # noqa: E402
import r73rules as P73  # noqa: E402

sys.path.insert(0, str(HERE.parent / "r71"))
import r71study as S  # noqa: E402  (stats, split, mods: R71's tested code)

stats, split = S.stats, S.split


# ================================================================================================ signals
_F: dict = {}
_SIG: dict = {}


def feats(und: str):
    """(flat index of (day, b), R73 features) of an index's 5-minute series (cached)."""
    if und not in _F:
        d, b, o, h, l, c = S.mods()["fam_r44"].bars5(und)
        pos = {(d[i], int(b[i])): i for i in range(len(d))}
        _F[und] = (pos, P73.features(d, b, o, h, l, c))
    return _F[und]


def signals(base: str, und: str) -> dict:
    """{day: [(b, side)]}: A / B = the paper arm's Donchian 20 signals (B's Supertrend check is in candidates);
    C = R72's C1 Breadth Donchian (4 of 5), from R72's own code, exactly as R73 read it."""
    key = ("C" if base == "C" else "D", und)
    if key not in _SIG:
        if base == "C":
            sys.path.insert(0, str(HERE.parent / "r72"))
            import r72study as X
            sig = X.rule_signals(und, "C1", 4)
            _SIG[key] = {d: [(er // 5 - 1, side) for er, side, _ in v] for d, v in sig.items()}
        else:
            _SIG[key] = S.mods()["fam_r44"].signal_list(und, "donchian20")
    return _SIG[key]


def exits():
    M = S.mods()
    s = R.EXITS_P
    return M["E"].Exits(stop=s["stop"] if s["stop"] is not None else -1.0,
                        target=s["target"] if s["target"] is not None else -1.0,
                        steps=tuple(s["steps"]), tmax=s["tmax"] if s["tmax"] else -1, sq_row=M["K"].row(*s["sq"]))


# ================================================================================================ lever context
_CTX: dict = {}


def ctx() -> dict:
    """Daily bars, ATR, expiry calendar, VIX low days, gap / narrow / high-vol days (cached; data to each decision
    moment only, see r75levers)."""
    if _CTX:
        return _CTX
    M = S.mods()
    K = M["K"]
    bars, atr, prev, gap, pdn, hv = {}, {}, {}, {}, {}, {}
    exp_any = set()
    for u in K.UNDS:
        days = M["r51data"].load(u)
        bars[u] = L.daily_bars(days)
        atr[u] = L.atr_series(bars[u])
        prev[u] = L.prev_of(sorted(bars[u]))
        gap[u] = L.gap_days(bars[u], atr[u])
        pdn[u] = L.pd_narrow_days(bars[u], atr[u])
        hv[u] = L.high_vol_days(bars[u])
        exp_any |= {d for d, D in days.items() if D.expiry}
    _CTX.update(bars=bars, atr=atr, prev=prev, gap=gap, pdn=pdn, hv=hv, exp_any=exp_any, vix_low=L.vix_low_days(vix()))
    return _CTX


def vix() -> dict:
    """{day: India VIX's last 1-minute close} from the lake (index_1m_INDIAVIX_<year>)."""
    import pandas as pd
    parts = []
    for y in (2024, 2025, 2026):
        p = R.LAKE / f"index_1m_INDIAVIX_{y}.parquet"
        if p.exists():
            parts.append(pd.read_parquet(p, columns=["ts", "close"]))
    if not parts:
        raise SystemExit("India VIX minutes missing from the lake")
    x = pd.concat(parts, ignore_index=True)
    ts = pd.to_datetime(x.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    x["day"], x["t"] = ts.dt.date, ts
    x = x[(x.close > 0) & (ts.dt.hour * 60 + ts.dt.minute <= 15 * 60 + 30)].sort_values("t")
    return {d: float(g.close.iloc[-1]) for d, g in x.groupby("day")}


def entry_filter(levers: tuple, und: str, D):
    """A function (er, side) -> keep? for the candidate-level levers among `levers` (L3, L4, L6-EXP, L6-GAP)."""
    c = ctx() if any(lv[:2] in ("L4", "L6") for lv in levers) else None
    tests = []
    for lv in levers:
        if lv.startswith("L3"):
            tests.append(lambda er, side, lv=lv: L.l3_ok(er, lv))
        elif lv == "L4-VIX":
            skip = D.d in c["vix_low"]
            tests.append(lambda er, side, s=skip: not s)
        elif lv == "L4-OR":
            p = c["prev"][und].get(D.d)
            narrow = L.or_narrow(D.idx, c["atr"][und].get(p, np.nan)) if p is not None else False
            tests.append(lambda er, side, n=narrow: L.l4_or_ok(er, n))
        elif lv == "L4-PD":
            skip = D.d in c["pdn"][und]
            tests.append(lambda er, side, s=skip: not s)
        elif lv == "L6-EXP":
            skip = D.d in c["exp_any"]
            tests.append(lambda er, side, s=skip: not s)
        elif lv == "L6-GAP":
            skip = D.d in c["gap"][und]
            tests.append(lambda er, side, s=skip: not s)
    return lambda er, side: all(t(er, side) for t in tests)


def candidates(base: str, und: str, D, sigs: list, levers: tuple = (), money: int = R.MONEY) -> list:
    """R73's candidates (trend T7 for B, no wait), with the candidate-level levers and the strike rule."""
    M = S.mods()
    K, E = M["K"], M["E"]
    last = K.row(*R.LAST_ROW_HM)
    pos, F = feats(und)
    keep = entry_filter(levers, und, D)
    out = []
    for b, side in sigs:
        s = 1 if side == "C" else -1
        er = 5 * (b + 1)
        if base == "B":
            i = pos.get((D.d, int(b)))
            if i is None or not P73.trend_ok("T7", F, i, s):
                continue
        if er < R.FIRST_ROW or er > last:
            continue
        if not keep(er, side):
            continue
        k = E.strike_for(D, und, side, er - 1, money)
        if k is not None:
            out.append(dict(er=er, side=side, strike=k, sig=er))
    out.sort(key=lambda z: z["er"])
    return out


def add_half(tr: list) -> list:
    """Each trade's net at half size (same fills, charges at half the quantity)."""
    E = S.mods()["E"]
    for t in tr:
        ch = float(E.charges(t["und"], t["rE"], t["rX"], t["qty"] * R.HALF, t["day"])[0])
        t["net_half"] = R.HALF * (t["rX"] - t["rE"]) * t["qty"] - ch
    return tr


def priced(base: str, lo: date, hi: date, levers: tuple = ()) -> list:
    """Trades of a baseline with the candidate-level levers and the strike lever in `levers` (others: see apply)."""
    M = S.mods()
    C, G, r51data = M["C"], M["G"], M["r51data"]
    money = R.MONEY
    for lv in levers:
        money = R.MONEY_SPEC.get(lv, money)
    cand_lv = tuple(lv for lv in levers if lv[:2] == "L3" or lv in ("L4-VIX", "L4-OR", "L4-PD", "L6-EXP", "L6-GAP"))
    ex = exits()
    rows = []
    for und in M["K"].UNDS:
        sig = signals(base, und)
        days = r51data.load(und)
        for d in C.days_in(und, lo, hi):
            D = days[d]
            if D.expiry or d not in sig:
                continue
            rows += G.take(und, D, candidates(base, und, D, sig[d], cand_lv, money), ex, max_per_side=R.MAX_PER_SIDE)
    return add_half(rows)


def apply(tr: list, levers: tuple, train: list | None) -> list:
    """The post-trade levers on a priced book, in order: L2 (drop indices from `train`), L1 (daily cap), sizing (L6-GAPH,
    L7-VOL, L7-DD*). Every trade gets w (1.0 or 0.5)."""
    c = ctx() if any(lv in ("L6-GAPH", "L7-VOL") for lv in levers) else None
    out = [dict(t, w=1.0) for t in tr]
    for lv in levers:
        if lv.startswith("L2"):
            drop = L.l2_drop(train or [], lv[3:], S.mods()["K"].UNDS)
            out = [t for t in out if t["und"] not in drop]
    for lv in levers:
        if lv in R.L1_SPEC:
            out = L.l1_apply(out, *R.L1_SPEC[lv])
    halves = []
    for lv in levers:
        if lv == "L6-GAPH":
            halves.append(lambda t: t["day"] in c["gap"][t["und"]])
        elif lv == "L7-VOL":
            halves.append(lambda t: t["day"] in c["hv"][t["und"]])
    if halves:
        out = L.sized(out, lambda t: any(f(t) for f in halves))
    for lv in levers:
        if lv in R.DD_SPEC:
            if any(t["w"] != 1.0 for t in out):
                raise ValueError("L7-DD combined with another sizing lever is not pre-registered")
            w = L.dd_weights(out, R.DD_SPEC[lv])
            out = L.sized(out, lambda t: w[t["day"]] != 1.0)
    return out


def lever_book(base: str, levers: tuple, lo: date, hi: date, base_tr: list, train: list | None) -> list:
    """The book of a baseline with `levers`: priced again only when a candidate or strike lever is in it."""
    need = any(lv[:2] in ("L3", "L5") or lv in ("L4-VIX", "L4-OR", "L4-PD", "L6-EXP", "L6-GAP") for lv in levers)
    tr = priced(base, lo, hi, levers) if need else base_tr
    return apply(tr, levers, train)


# ================================================================================================ comparisons
def key(t) -> tuple:
    return t["und"], t["day"], t["side"], t["sig"]


def compare(base: list, lv: list) -> dict:
    """Losers avoided vs winners missed: baseline trades not taken (matched on index, day, side, signal bar) and
    baseline trades halved; plus trades the lever took that the baseline did not."""
    kb = {key(t): t for t in base}
    kv = {key(t): t for t in lv}
    skipped = [t for k, t in kb.items() if k not in kv]
    halved = [kb[k] for k, t in kv.items() if k in kb and t.get("w", 1.0) != 1.0]
    extra = [t for k, t in kv.items() if k not in kb]
    los = [t for t in skipped if t["net"] <= 0]
    win = [t for t in skipped if t["net"] > 0]
    return dict(skipped=len(skipped), skipped_net=round(sum(t["net"] for t in skipped)),
                losers_avoided=len(los), losers_avoided_rs=round(-sum(t["net"] for t in los)),
                winners_missed=len(win), winners_missed_rs=round(sum(t["net"] for t in win)),
                big_missed=sum(1 for t in skipped if t.get("ret", -1) >= R.BIG - 1e-9),
                halved=len(halved), halved_losers=sum(1 for t in halved if t["net"] <= 0),
                halved_saved_rs=round(sum(t["net"] - t["net_half"] for t in halved if t["net"] <= 0) * -1),
                halved_given_rs=round(sum(t["net"] - t["net_half"] for t in halved if t["net"] > 0)),
                extra=len(extra), extra_net=round(sum(t["net"] for t in extra)))


def affected(lever: str, base: list, lv: list, cmp: dict) -> int:
    if lever.startswith("L5"):
        return len(lv)
    return cmp["skipped"] + cmp["halved"] + cmp["extra"]


def summary(tr: list, lo: date, hi: date) -> dict:
    st = stats(tr, S.months_between(lo, hi))
    st.update(L.breaches(tr))
    return st


def quarters(base_d: list, lv_d_of) -> list:
    """Per walk-forward quarter: (base summary, lever summary, better). lv_d_of(i, a, z) -> the lever's trades in
    quarter i."""
    out = []
    for i, (a, z) in enumerate(R.WF_FOLDS):
        b = summary(split(base_d, a, z), a, z)
        v = summary(lv_d_of(i, a, z), a, z)
        out.append(dict(base=b, lever=v, better=L.better(v, b)))
    return out


# ================================================================================================ io
def _strip(tr: list) -> list:
    keep = ("und", "day", "side", "strike", "er", "xr", "rE", "rX", "qty", "why", "net", "net_half", "w", "ret", "sig")
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


def check(base: str, period: str, tr: list):
    net = round(sum(t["net"] for t in tr))
    if net != R.EXPECT[(base, period)]:
        raise SystemExit(f"baseline {base} {period} net {net} != published {R.EXPECT[(base, period)]}: stop")


def lever_design(base: str, lever: str, base_d: list) -> dict:
    lo, hi = R.START, R.DESIGN_END
    lv = lever_book(base, (lever,), lo, hi, base_d, base_d)            # L2 in design: in-sample (flagged)
    if lever.startswith("L2"):
        def q_of(i, a, z):
            return split(apply(base_d, (lever,), [t for t in base_d if t["day"] < a]), a, z)
    else:
        def q_of(i, a, z):
            return split(lv, a, z)
    qs = quarters(base_d, q_of)
    b, v = summary(base_d, lo, hi), summary(lv, lo, hi)
    cmp = compare(base_d, lv)
    _save(f"{base}_{lever}_design", _strip(lv))
    return dict(design=v, design_better=L.better(v, b), quarters=qs, wf_better=sum(q["better"] for q in qs),
                vs_base=cmp, affected=affected(lever, base_d, lv, cmp))


# ================================================================================================ phases
def design() -> dict:
    lo, hi = R.START, R.DESIGN_END
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), base={}, tests={})
    books = {}
    for base in ("A",) + R.BASES:
        b = priced(base, lo, hi)
        check(base, "design", b)
        books[base] = b
        _save(f"{base}_base_design", _strip(b))
        out["base"][base] = dict(design=summary(b, lo, hi),
                                 quarters=[summary(split(b, a, z), a, z) for a, z in R.WF_FOLDS])
        print("base", base, len(b), round(sum(t["net"] for t in b)), flush=True)
    for base in R.BASES:
        for lever in R.LEVERS:
            r = lever_design(base, lever, books[base])
            out["tests"][f"{base}:{lever}"] = r
            print(base, lever, r["design"]["trades"], r["design"]["net"], r["design"]["max_dd"], r["design_better"],
                  r["wf_better"], flush=True)
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    _json(out, R.RESULTS / "design.json")
    return out


def twin_p(lever: str, base_f: list, lv_f: list, rng) -> tuple:
    """PREREG section 5 part 2: random-skip / random-half twins, or the paired sign-flip for L5."""
    net = sum(t["net"] for t in lv_f)
    if lever.startswith("L5"):
        db, dv = L.daily(base_f), L.daily(lv_f)
        diff = [dv.get(d, 0.0) - db.get(d, 0.0) for d in sorted(set(db) | set(dv))]
        tw = L.signflip(diff, R.TWINS, rng)
        return L.p_of(tw, sum(diff)), round(float(tw.mean()))
    n_half = sum(1 for t in lv_f if t.get("w", 1.0) != 1.0)
    if n_half:
        tw = L.half_twins([t["net"] for t in base_f], [t["net_half"] for t in base_f], n_half, R.TWINS, rng)
    else:
        tw = L.skip_twins([t["net"] for t in base_f], len(base_f) - len(lv_f), R.TWINS, rng)
    return L.p_of(tw, net), round(float(tw.mean()))


def final() -> dict:
    """The ONE final read: 40 tests, twins, BH over the 40, the gate, 2x slippage, and the combinations."""
    dj = R.RESULTS / "design.json"
    if not dj.exists():
        raise SystemExit("run `design` first")
    if R.FINAL_FLAG.exists():
        raise SystemExit("R75's final period was already read once (results/FINAL_READ); a second read is refused")
    R.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    des = json.loads(dj.read_text())
    M = S.mods()
    M["C"].open_final()
    lo, hi = R.FINAL_START, M["K"].END
    out = dict(read=R.FINAL_FLAG.read_text().strip(), end=str(hi), sessions=len(S.sessions(lo, hi)), base={},
               tests={})
    bd, bf, bf2 = {}, {}, {}
    E = M["E"]
    for base in ("A",) + R.BASES:
        bd[base] = _load(f"{base}_base_design")
        f = priced(base, lo, hi)
        check(base, "final", f)
        bf[base] = f
        _save(f"{base}_base_final", _strip(f))
        old = E.SLIP
        E.SLIP = E.SLIP2
        try:
            bf2[base] = priced(base, lo, hi)
        finally:
            E.SLIP = old
        out["base"][base] = dict(final=summary(f, lo, hi), slip2=summary(bf2[base], lo, hi))
        print("base final", base, len(f), round(sum(t["net"] for t in f)), flush=True)
    ps = []
    for tname in R.TESTS:
        base, lever = tname.split(":")
        lv = lever_book(base, (lever,), lo, hi, bf[base], bd[base])
        _save(f"{base}_{lever}_final", _strip(lv))
        st, bs = summary(lv, lo, hi), out["base"][base]["final"]
        cmp = compare(bf[base], lv)
        p, tm = twin_p(lever, bf[base], lv, np.random.default_rng(R.SEED))
        old = E.SLIP
        E.SLIP = E.SLIP2
        try:
            lv2 = lever_book(base, (lever,), lo, hi, bf2[base], bd[base])
        finally:
            E.SLIP = old
        s2 = summary(lv2, lo, hi)
        dv = des["tests"][tname]
        r = dict(base=base, lever=lever, final=st, final_better=L.better(st, bs), p=p, twin_mean=tm, vs_base=cmp,
                 affected=affected(lever, bf[base], lv, cmp), design=dv["design"], design_better=dv["design_better"],
                 quarters=dv["quarters"], wf_better=dv["wf_better"], design_vs_base=dv["vs_base"],
                 design_affected=dv["affected"], slip2=s2, slip2_net=s2.get("net", 0),
                 slip2_better=L.better(s2, out["base"][base]["slip2"]))
        out["tests"][tname] = r
        ps.append(p)
        print(tname, st.get("trades"), st.get("net"), st.get("max_dd"), r["final_better"], p, flush=True)
    for tname, q in zip(R.TESTS, L.bh(ps)):
        r = out["tests"][tname]
        r["q"] = round(float(q), 4)
        r["fails"] = L.gate(r)
        r["verdict"] = "PASS" if not r["fails"] else "FAIL"
    out["combos"] = combos(out, des, bd, bf)
    _json(out, R.RESULTS / "final.json")
    return out


def combos(out: dict, des: dict, bd: dict, bf: dict) -> dict:
    """PREREG section 6: per baseline, the best 2 passing levers by DESIGN net (ties: smaller design max DD), combined;
    reported on design, quarters and final; recommended only if better than the baseline and each part (design and
    final). Sizing levers are never stacked (two sizing rules were not pre-registered together)."""
    M = S.mods()
    res = {}
    for base in R.BASES:
        ok = [t for t in R.TESTS if t.startswith(base + ":") and out["tests"][t]["verdict"] == "PASS"]
        ok.sort(key=lambda t: (-out["tests"][t]["design"]["net"], out["tests"][t]["design"]["max_dd"]))
        sizing = {"L6-GAPH", "L7-VOL", "L7-DD20", "L7-DD40"}
        pick = []
        for t in ok:
            lv = t.split(":")[1]
            if len(pick) < 2 and not (lv in sizing and any(p in sizing for p in pick)) \
                    and not (lv[:2] == "L5" and any(p[:2] == "L5" for p in pick)):
                pick.append(lv)
        r = dict(passing=[t.split(":")[1] for t in ok], pick=pick)
        if len(pick) == 2:
            levers = tuple(pick)
            d_lo, d_hi, f_lo, f_hi = R.START, R.DESIGN_END, R.FINAL_START, M["K"].END
            dv = lever_book(base, levers, d_lo, d_hi, bd[base], bd[base])
            fv = lever_book(base, levers, f_lo, f_hi, bf[base], bd[base])
            ds, fs = summary(dv, d_lo, d_hi), summary(fv, f_lo, f_hi)
            if any(x.startswith("L2") for x in pick):
                def q_of(i, a, z):
                    tr = [t for t in bd[base] if t["day"] < a]
                    return split(lever_book(base, levers, d_lo, d_hi, bd[base], tr), a, z)
            else:
                def q_of(i, a, z):
                    return split(dv, a, z)
            qs = quarters(bd[base], q_of)
            r.update(quarters=qs, wf_better=sum(q["better"] for q in qs))
            parts = [out["tests"][f"{base}:{x}"] for x in pick]
            rec = (L.better(ds, des["base"][base]["design"]) and L.better(fs, out["base"][base]["final"])
                   and all(L.better(ds, p["design"]) and L.better(fs, p["final"]) for p in parts))
            r.update(design=ds, final=fs, vs_base=compare(bf[base], fv), recommended=rec)
        res[base] = r
    return res


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "report":
        import r75report
        r75report.write()
    else:
        raise SystemExit(__doc__)
