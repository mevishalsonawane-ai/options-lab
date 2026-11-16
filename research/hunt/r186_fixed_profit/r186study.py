"""R186 (PREREG.md): a hard fixed profit target (T1000 / T1250 / T1500: Rs 1,000 / 1,250 / 1,500 a lot net) and a Rs 500
step lock (L500, L-BE1000) on top of the rule in the app today (R179's V4 on R72 / R51; Liquidity 15+5 with the breakeven),
the last year (20 Oct 2025 - 19 Oct 2026); plus how often a trade was +Rs 1,000 / 1,500 / 2,000 and then lost (7a).

    R176_POST=1 python research/hunt/r186_fixed_profit/r186study.py run       # read once -> results/year.json
    python research/hunt/r186_fixed_profit/r186study.py decide                 # gates -> results/decision.json
    # 7a on 20 Oct - 13 Nov 2026: a copy of the lake with the 2026 files joined to their gap1113 / fill1113 parts, stores
    # built outside git (never the real 19 Oct stores; _extend() refuses the default cache)
    X="IRAALGO_LAKE=~/.cache/iraalgo/r186ext/lake IRAALGO_CACHE=~/.cache/iraalgo/r186ext/cache R51_CLEAN=1 R176_POST=1"
    env $X python research/hunt/r186_fixed_profit/r186study.py gap-build      # stores to 13 Nov
    env $X python research/hunt/r186_fixed_profit/r186study.py gap            # -> results/gap_month.json

Reuses R179's units, picker and walk (R176 / R106) and R177's apply_rule. Pure parts: gates_r186, giveback,
target_effect (test_r186.py). Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

os.environ.setdefault("R176_POST", "1")
HERE = Path(__file__).resolve().parent
for p in (str(HERE.parent / "r179_winrate_55"), str(HERE.parent / "r177_vp_trap"), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r179study as R9  # noqa: E402

R7, S6, K6 = R9.R7, R9.S6, R9.K6
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
ARMS = ("LIQ", "R72", "R51")
RULES = {"T1000": dict(tgt=1000.0), "T1250": dict(tgt=1250.0), "T1500": dict(tgt=1500.0),
         "L500": dict(lock="L500"), "LBE1000": dict(lock="LBE")}
LABEL = {"BASE": "the rule in the app today (own stop + breakeven at +10%; picker on R72 / R51)",
         "A": "R179's A: each arm's own exits, no picker, no breakeven",
         "T1000": "base + Rs 1,000 a lot net target", "T1250": "base + Rs 1,250 a lot net target",
         "T1500": "base + Rs 1,500 a lot net target",
         "L500": "base + Rs 500 step lock (+1,000 -> lock +500, +1,500 -> +1,000, ...)",
         "LBE1000": "base + breakeven once +Rs 1,000 a lot"}
TESTS = tuple((a, r) for r in RULES for a in ARMS)                  # 15
M_PRIOR = 4852                                                      # the count after R185
M_TOTAL = M_PRIOR + len(TESTS)                                      # 4,867
SEED = 186
GIVE = (1000.0, 1500.0, 2000.0)
LAST3 = ("2026-08", "2026-09", "2026-10")
# R179's published base (results/year.json): (period, arm) -> (trades, net), 1 lot
PARITY = {("year", "R72"): (1069, 324629), ("year", "R51"): (1367, 249903), ("year", "LIQ"): (688, 100222),
          ("final", "R72"): (604, 167233), ("final", "R51"): (655, 144802), ("final", "LIQ"): (357, 6432)}


# ================================================================================================ pure
def gates_r186(per: dict, base: dict, q: float) -> dict:
    """PREREG section 5. per / base: {period: stats} for early, final, six, year (stats: trades, net, max_dd,
    worst_month {net})."""
    fails = []
    if not all(per[p]["net"] >= base[p]["net"] for p in ("year", "final")):
        fails.append("net below the base (year or final)")
    if not all(per[p]["net"] > 0 for p in ("early", "final", "six", "year")):
        fails.append("not positive in every period")
    if not q <= K6.GATE_Q:
        fails.append(f"twin BH q {q:.2f} > 0.10")
    if per["final"]["trades"] < K6.MIN_FINAL:
        fails.append("< 40 final trades")
    risk = [p for p in ("early", "final", "six", "year")
            if per[p]["max_dd"] > K6.MAX_DD or per[p]["worst_month"]["net"] < K6.WORST_MONTH]
    if risk:
        fails.append("outside the account stop rule in " + ", ".join(risk))
    return dict(passed=not fails, fails=fails, q=round(float(q), 4),
                passed_except_account_stop=not [f for f in fails if "account stop" not in f])


def giveback(trades: list, lots: int = 1) -> dict:
    """PREREG 7a: trades whose best open P&L net reached each level (a lot) and then ended with a loss."""
    losers = [t for t in trades if t["net"] < 0]
    out = dict(trades=len(trades), losers=len(losers))
    for lv in GIVE:
        g = [t for t in losers if t["best"] >= lv * lots - 1e-9]
        out[f"ge{int(lv)}"] = dict(lost_after=len(g), given_back=round(sum(t["best"] - t["net"] for t in g)),
                                   final_loss=round(sum(t["net"] for t in g)),
                                   reached=sum(1 for t in trades if t["best"] >= lv * lots - 1e-9))
    out["share_losers_were_1000"] = round(out["ge1000"]["lost_after"] / len(losers), 4) if losers else None
    return out


def key_of(t: dict) -> tuple:
    return (t["und"], S6._d(t["day"]), t["er_sig"], t["side"])


def target_effect(trades: list, plain: dict, reasons: tuple) -> dict:
    """The trades the rule closed (why in `reasons`), how many would have made MORE under the base (same signal, base
    pricing) and the profit given up on those; the profit saved on the others."""
    closed = [t for t in trades if t.get("why") in reasons]
    more = given = saved = 0.0
    n_more = 0
    for t in closed:
        p = plain.get(key_of(t))
        if p is None:
            continue
        d = p["net"] - t["net"]
        if d > 1e-6:
            n_more += 1
            given += d
        elif d < -1e-6:
            saved += -d
    return dict(closed=len(closed), base_made_more=n_more, profit_given_up=round(given), profit_saved=round(saved),
                net_effect=round(saved - given))


# ================================================================================================ data
def priced(arm: str, us: list, lots: int, tgt: float = -1.0, lock: str = "") -> list:
    M = R7.mods()
    import r186engine as Z
    ex = S6.exits(arm)
    return [Z.price(key[0], M["r51data"].load(key[0])[key[1]], cands, ex, lots, tgt, lock) for key, cands, _, _ in us]


def exit_twins(arm: str, trades: list, plain: dict, n: int, rng) -> tuple:
    """PREREG 5.3 at 1 lot: (twin totals (n,), changed count)."""
    import r186engine as Z
    M = R7.mods()
    ex = S6.exits(arm)
    tot = np.zeros(n)
    ch = 0
    for t in trades:
        p = plain.get(key_of(t))
        if p is None or not (t["xr"] < p["xr"] and p["xr"] - t["er"] >= 2):
            tot += t["net"]
            continue
        ch += 1
        rows = M["S"].twin_rows(t["er"], p["xr"], n, rng)
        D = M["r51data"].load(t["und"])[t["day"]]
        tot += Z.forced_nets(t["und"], D, t, ex, rows, 1, int(t.get("fx_arm", -1)))
    return tot, ch


def by_month(trades: list, months: tuple) -> dict:
    return {m: giveback([t for t in trades if K6.month_of(t["day"]) == m]) for m in months}


def run() -> dict:
    assert K6.POST, "R186 reads the stores built to 19 Oct (R176_POST=1)"
    RESULTS.mkdir(parents=True, exist_ok=True)
    if FINAL_FLAG.exists():
        raise SystemExit("R186 already read once: " + FINAL_FLAG.read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    M = R7.mods()
    M["C"].open_final()
    lo, hi = K6.Y_START, K6.Y_END
    out = {"parity": {}, "twins": {}, "effect": {}, "giveback": {}}
    trades = {}
    rng = np.random.default_rng(SEED)
    for arm in ARMS:
        us0 = S6.units(arm, lo, hi)
        usA, _ = R7.apply_rule(arm, us0, None)
        P = sc = thr = None
        if arm in K6.PICKER_ARMS:
            P = S6.picker()
            sc, thr = P.scores(arm, us0)
        keep = (lambda tr: tr) if P is None else (lambda tr: [t for t in tr if P.kept(t, sc, thr)])

        def walked(pr):
            return [t for t in S6.walk(arm, usA, pr) if lo <= t["day"] <= hi]

        for lots in K6.LOTS:
            if lots == 2:
                continue                                  # 1 lot gated, 3 lots descriptive (PREREG section 5)
            a = walked(R9.priced(arm, usA, "A", False, lots))
            pr_b = priced(arm, usA, lots)
            base = keep(walked(pr_b))
            trades.setdefault(("A", lots), {})[arm] = a
            trades.setdefault(("BASE", lots), {})[arm] = base
            plain = {(t["und"], S6._d(t["day"]), c["er"], c["side"]): t
                     for (key, cands, _, _), row in zip(usA, pr_b) for c, t in zip(cands, row) if t is not None}
            if lots == 1:
                for per in ("year", "final"):
                    sub = S6.in_span(base, per)
                    pub = PARITY[(per, arm)]
                    r = dict(trades=len(sub), net=round(sum(t["net"] for t in sub)), published=list(pub))
                    r["reproduced"] = r["trades"] == pub[0] and abs(r["net"] - pub[1]) <= 1
                    out["parity"][f"{arm}:{per}"] = r
                    print(arm, per, "parity", r, flush=True)
                    assert r["reproduced"], ("parity failed", arm, per, r)
                af = S6.in_span(a, "final")
                pubA = K6.PARITY[(arm, "final")]
                out["parity"][f"{arm}:A:final"] = dict(trades=len(af), net=round(sum(t["net"] for t in af)),
                                                       published=list(pubA))
                assert len(af) == pubA[0] and abs(sum(t["net"] for t in af) - pubA[1]) <= 1, ("A parity", arm)
                gb = {per: giveback(S6.in_span(base, per)) for per in K6.YEAR_PERIODS}
                gb["months"] = by_month(base, LAST3)
                out["giveback"][arm] = gb
                print(arm, "giveback year", gb["year"], flush=True)
            for rule, kw in RULES.items():
                tr = keep(walked(priced(arm, usA, lots, kw.get("tgt", -1.0), kw.get("lock", ""))))
                trades.setdefault((rule, lots), {})[arm] = tr
                why = ("target", "target_gap") if "tgt" in kw else ("step_lock",)
                out["effect"][f"{arm}:{rule}@{lots}"] = {per: target_effect(S6.in_span(tr, per), plain, why)
                                                         for per in K6.YEAR_PERIODS}
                if lots == 1:
                    tw, chg = exit_twins(arm, tr, plain, K6.TWINS, rng)
                    act = sum(t["net"] for t in tr)
                    out["twins"][f"{arm}:{rule}"] = dict(p=round(M["S"].twin_p(act, tw), 4), mean=round(float(tw.mean())),
                                                         changed=chg, gaps=sum(1 for t in tr if t["why"] == "target_gap"))
                    print(arm, rule, "twin", out["twins"][f"{arm}:{rule}"], flush=True)
            print(arm, lots, {v: (len(trades[(v, lots)][arm]), round(sum(t["net"] for t in trades[(v, lots)][arm])))
                              for v in ("A", "BASE", *RULES)}, flush=True)
    for per in K6.YEAR_PERIODS:
        plo, phi = K6.span_of(per)
        n_sess = len(S6.sessions(plo, phi))
        months = K6.months_between(plo, phi)
        res = {"sessions": n_sess}
        for (v, lots), by_arm in trades.items():
            res[f"{v}@{lots}"] = {a_: S6.describe([t for t in tr if plo <= t["day"] <= phi], n_sess, months)
                                  for a_, tr in by_arm.items()}
        out[per] = res
    (RESULTS / "year.json").write_text(json.dumps(out, indent=1, default=str))
    return out


GAP_LO, GAP_HI = date(2026, 10, 20), date(2026, 11, 13)
CHECK_LO = date(2026, 9, 1)                                         # overlap with the stores to 19 Oct (a parity check)


def _extend():
    """Point the store window at 13 Nov 2026, only on the extended copy (never the real 19 Oct stores)."""
    cache = Path(os.environ.get("IRAALGO_CACHE", "")).expanduser()
    lake = Path(os.environ.get("IRAALGO_LAKE", "")).expanduser()
    assert cache.name and cache != Path.home() / ".cache" / "iraalgo" and lake.name and "r186ext" in str(lake), \
        "set IRAALGO_LAKE / IRAALGO_CACHE to the extended copy (outside git)"
    import r106cfg
    r106cfg.POST_END = GAP_HI


def gap_build():
    _extend()
    os.environ["R51_CLEAN"] = "1"
    import r101cfg  # noqa: F401
    import r106cfg
    import r51cfg
    r51cfg.END = r106cfg.POST_END
    r51cfg.OUT = r51cfg.OUT.parent / "clean_post"
    import r51data
    for u in r51cfg.UNDS:
        print(r51data.build(u), flush=True)


def gap_month() -> dict:
    """PREREG 7a extended to 20 Oct - 13 Nov 2026 (the month Boss watched), descriptive only: the base at 1 lot on stores
    built from the lake plus its 20 Oct - 13 Nov gap files (IRAALGO_LAKE / IRAALGO_CACHE point at that extended copy,
    outside git). The base's 1 Sep - 19 Oct trades on these stores are compared with the same trades on the 19 Oct stores
    (results/year.json), so a difference in how the stores were built would show."""
    assert K6.POST, "R176_POST=1"
    _extend()
    M = R7.mods()
    M["C"].open_final()
    out = {"window": f"{GAP_LO} .. {GAP_HI}", "check": {}, "giveback": {}, "base": {}}
    ref = json.loads((RESULTS / "year.json").read_text())
    for arm in ARMS:
        us0 = S6.units(arm, CHECK_LO, GAP_HI)
        usA, _ = R7.apply_rule(arm, us0, None)
        P = sc = thr = None
        if arm in K6.PICKER_ARMS:
            P = S6.picker()
            sc, thr = P.scores(arm, us0)
        tr = S6.walk(arm, usA, priced(arm, usA, 1))
        if P is not None:
            tr = [t for t in tr if P.kept(t, sc, thr)]
        old = [t for t in tr if CHECK_LO <= t["day"] <= K6.Y_END]
        mo = ("2026-09", "2026-10")
        mine = S6.monthly(old, mo)
        pub = {m: ref["year"]["BASE@1"][arm]["months"][m] for m in mo}
        out["check"][arm] = dict(this=mine, year_json=pub, same=all(abs(mine[m] - pub[m]) <= 1 for m in mo))
        new = [t for t in tr if GAP_LO <= t["day"] <= GAP_HI]
        out["giveback"][arm] = giveback(new)
        out["giveback"][arm]["days_with_1000_then_loss"] = len({t["day"] for t in new if t["net"] < 0 and t["best"] >= 1000.0})
        out["base"][arm] = dict(trades=len(new), net=round(sum(t["net"] for t in new)),
                                win=round(sum(t["net"] > 0 for t in new) / len(new), 4) if new else None,
                                days=len({t["day"] for t in new}),
                                muhurat_8nov=sum(1 for t in new if t["day"] == date(2026, 11, 8)))
        print(arm, out["check"][arm], out["base"][arm], out["giveback"][arm], flush=True)
    (RESULTS / "gap_month.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def decide() -> dict:
    r = json.loads((RESULTS / "year.json").read_text())
    ps = [r["twins"][f"{a}:{v}"]["p"] for a, v in TESTS]
    q_all, q_own = S6.bh_q(ps, M_TOTAL), S6.bh_q(ps, len(ps))
    out = {}
    for (a, v), qa, qo in zip(TESTS, q_all, q_own):
        per = {p: r[p][f"{v}@1"][a] for p in K6.YEAR_PERIODS}
        base = {p: r[p]["BASE@1"][a] for p in K6.YEAR_PERIODS}
        g = gates_r186(per, base, qa)
        g.update(q_own=round(qo, 4), twin_p=r["twins"][f"{a}:{v}"]["p"], net_year=per["year"]["net"],
                 base_net_year=base["year"]["net"], net_final=per["final"]["net"], base_net_final=base["final"]["net"])
        out[f"{a}:{v}"] = g
    out["_m"] = dict(tests=len(TESTS), prior=M_PRIOR, total=M_TOTAL)
    (RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "run":
        run()
    elif cmd == "gap-build":
        gap_build()
    elif cmd == "gap":
        gap_month()
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
