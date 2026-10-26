"""R110 study (PREREG.md).

    python research/hunt/r110_strangle/r110study.py design   # history + design (+ walk-forward) -> results/design.json
    python research/hunt/r110_strangle/r110study.py final    # ONE read of 1 Apr - 13 Oct (+ 14-19 Oct) -> results/final.json
    python research/hunt/r110_strangle/r110study.py decide   # gates + BH -> results/decision.json
    python research/hunt/r110_strangle/r110study.py report   # results/report.md
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r110engine as X  # noqa: E402  (puts R109 on the path)
import r109cfg as K  # noqa: E402
import r109data as DA  # noqa: E402
import r109engine as E  # noqa: E402

RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
SEED = 110
UND = "BANKNIFTY"
PHASES = {"design": ("history", "design"), "final": ("final", "post")}
TESTS = {   # PREREG section 2
    "W1": dict(shorts=200.0, wings=700.0),
    "W2": dict(shorts=200.0, wings=1000.0),
    "X1": dict(shorts=500.0),
    "X1W": dict(shorts=500.0, wings=1000.0),
    "S1": dict(stop="S1"),
    "S2": dict(stop="S2"),
    "F1": dict(filter="F1"),
    "F2": dict(filter="F2"),
}
N_EARLIER = 14_253           # R1-R108 as R109 counted (14,245) + R109's 8
GATE_P, GATE_Q = 0.05, 0.10
MIN_DESIGN, MIN_FINAL = 40, 15


def mondays(days: dict, exps: list, periods: tuple):
    """(monday, expiry, exit-day period) for every week whose exit day falls in `periods` (calendar only)."""
    sess = sorted(days)
    first = min(d for d in sess if d >= K.START and days[d].atm is not None)
    mon = first + timedelta(days=(7 - first.weekday()) % 7)
    while mon <= K.END:
        exp = E.nearest_on_or_after(exps, mon)
        thu = mon + timedelta(days=3)
        tgt = exp if (exp is not None and exp <= thu) else thu
        inside = [x for x in sess if mon <= x <= tgt]
        if inside and K.period_of(inside[-1]) in periods:
            yield mon, exp, K.period_of(inside[-1])
        mon += timedelta(days=7)


def f1_ratios(days, exps) -> dict:
    """{monday: entry IV / RV20} for EVERY Monday of the lake (the F1 median reads only earlier Mondays)."""
    out = {}
    for mon, exp, _ in mondays(days, exps, ("history", "design", "final", "post")):
        if mon not in days:
            continue
        iv = X.entry_iv(days, mon, exp)
        rv = DA.rv20_before(UND, mon)
        out[mon] = iv / rv if (np.isfinite(iv) and np.isfinite(rv) and rv > 0) else float("nan")
    return out


def f1_flags(ratios: dict) -> dict:
    ms = sorted(ratios)
    vals = [ratios[m] for m in ms]
    return {m: X.f1_keep(vals, i) for i, m in enumerate(ms)}


def f2_flags(mons) -> dict:
    s = DA.daily_closes(UND)
    return {m: X.aftershock(s[s.index < m].values[-25:]) for m in mons}


def run_phase(phase: str) -> dict:
    periods = PHASES[phase]
    days = DA.load(UND, "MONTH")
    exps = DA.expiry_dates(UND, "MONTH")
    weeks = list(mondays(days, exps, periods))
    base, skipped = {}, []
    for mon, exp, per in weeks:
        w = X.week(days, mon, exp, K.lot(UND, mon)) if mon in days else None
        if w is None:
            skipped.append(str(mon))
            continue
        w["period"] = per
        base[mon] = w
    parity(base, phase)
    ratios = f1_ratios(days, exps)
    f1 = f1_flags(ratios)
    f2 = f2_flags(list(base))
    rows = {"B0": list(base.values())}
    skips = {"B0": skipped}
    for name, t in TESTS.items():
        out, sk = [], []
        for mon, exp, per in weeks:
            if mon not in base:
                continue
            if t.get("filter") == "F1":
                if not f1.get(mon, False):
                    sk.append(str(mon))
                    continue
                w = base[mon]
            elif t.get("filter") == "F2":
                if f2.get(mon, -1) != 0:
                    sk.append(str(mon))
                    continue
                w = base[mon]
            else:
                w = X.week(days, mon, exp, K.lot(UND, mon), shorts=t.get("shorts", 200.0), wings=t.get("wings"),
                           stop=t.get("stop"))
                if w is None:
                    sk.append(str(mon))
                    continue
                w["period"] = per
            out.append(w)
        rows[name] = out
        skips[name] = sk
        print(name, {p: (len([r for r in out if r["period"] == p]), round(sum(r["net"] for r in out if r["period"] == p)))
                     for p in periods}, flush=True)
    res = summarise(rows, skips, base, periods, phase)
    res["_meta"] = dict(phase=phase, run=datetime.now(timezone.utc).isoformat(timespec="seconds"),
                        f1_ratio_median_all=float(np.nanmedian([v for v in ratios.values()])),
                        f1_kept=int(sum(f1.get(m, False) for m in base)), f2_flagged=int(sum(f2[m] == 1 for m in base)))
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / f"r110_weeks_{phase}.pkl", "wb") as f:
        pickle.dump(rows, f)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{phase}.json").write_text(json.dumps(jsonable(res), indent=1))
    return res


def parity(base: dict, phase: str):
    """B0 must reproduce R109's strangle week for week (R109's trade table, same phase)."""
    p = K.OUT / f"trades_{phase}.pkl"
    if not p.exists():
        raise SystemExit(f"R109's trade table {p} is missing: run R109's {phase} first")
    r109 = {r["day"]: r for r in pickle.load(open(p, "rb"))["strangle-bn"]}
    assert set(r109) == set(base), (sorted(set(r109) ^ set(base)))[:5]
    for d, r in r109.items():
        assert abs(r["net"] - base[d]["net"]) < 1e-6, (d, r["net"], base[d]["net"])
    print(f"parity with R109 ({phase}): {len(base)} weeks, net identical", flush=True)


def summarise(rows, skips, base, periods, phase) -> dict:
    rng = np.random.default_rng(SEED + (0 if phase == "design" else 1))
    out = {}
    sets = list(periods) + (["wf"] if "design" in periods else [])
    for name, rr in rows.items():
        o = {"skipped": len(skips[name]), "periods": {}}
        for p in sets:
            if p == "wf":
                x = [r for r in rr if r["period"] == "design" and r["day"] >= K.WF_START]
                b = [r for r in base.values() if r["period"] == "design" and r["day"] >= K.WF_START]
            else:
                x = [r for r in rr if r["period"] == p]
                b = [r for r in base.values() if r["period"] == p]
            s = E.period_stats([dict(r) for r in x])
            if x:
                s["worst5"] = sorted([(str(r["day"]), round(r["net"])) for r in x], key=lambda t: t[1])[:5]
                s["weeks_le_13k"] = int(sum(r["net"] <= -13_000 for r in x))
                s["credit_mean"] = float(np.mean([r["credit"] for r in x]))
                s["margin_mean"] = float(np.mean([r["margin"] for r in x]))
                s["net_per_margin"] = s["net"] / s["margin_mean"] if s["margin_mean"] else None
                s["stale_legs"] = int(sum(r["stale"] for r in x))
                s["stops"] = int(sum(r.get("stopped", False) for r in x))
                s["exp_weeks"] = int(sum(r["exp_week"] for r in x))
                same = {r["day"] for r in x}
                s["b0_same_weeks_net"] = float(sum(r["net"] for r in b if r["day"] in same))
            s["b0_net"] = float(sum(r["net"] for r in b))
            s["b0_n"] = len(b)
            if p in ("design", "wf", "final") and len(x) >= 2:
                s["boot_p"] = X.boot_p([r["net"] for r in x], rng)
                if name in ("F1", "F2"):
                    s["skip_twin_p"] = X.skip_twin_p([r["net"] for r in b], len(b) - len(x), s["net"], rng)
            o["periods"][p] = s
        out[name] = o
    return out


def jsonable(x):
    if isinstance(x, dict):
        return {str(k): jsonable(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [jsonable(v) for v in x]
    if isinstance(x, (float, np.floating)):
        return None if not np.isfinite(x) else round(float(x), 6)
    if isinstance(x, np.integer):
        return int(x)
    if isinstance(x, date):
        return str(x)
    return x


def decide() -> dict:
    D = json.loads((RESULTS / "design.json").read_text())
    F = json.loads((RESULTS / "final.json").read_text())
    names = list(TESTS)
    ps, rows = [], {}
    for n in names:
        d, w, f = D[n]["periods"].get("design", {}), D[n]["periods"].get("wf", {}), F[n]["periods"].get("final", {})
        fails = []
        for lab, s in (("design", d), ("walk-forward", w), ("final", f)):
            if not (s.get("n") and s["net"] > 0):
                fails.append(f"{lab}: net {s.get('net', 0):,.0f} not above 0")
            if s.get("worst_month") is not None and s["worst_month"] < K.ACCOUNT_MONTH:
                fails.append(f"{lab}: worst month Rs {s['worst_month']:,.0f} below -Rs 13,000")
            if s.get("max_dd", 0) >= K.ACCOUNT_DD:
                fails.append(f"{lab}: drawdown Rs {s['max_dd']:,.0f} >= Rs 40,000")
        pp = [d.get("boot_p", 1.0)]
        if n in ("F1", "F2"):
            pp.append(d.get("skip_twin_p", 1.0))
        for lab, v in zip(("design bootstrap p", "design random-skip twin p"), pp):
            if not v < GATE_P:
                fails.append(f"{lab} {v:.3g} >= 0.05")
        if d.get("n", 0) < MIN_DESIGN:
            fails.append(f"design weeks {d.get('n', 0)} < {MIN_DESIGN}")
        if f.get("n", 0) < MIN_FINAL:
            fails.append(f"final weeks {f.get('n', 0)} < {MIN_FINAL}")
        ps.append(max(pp))
        rows[n] = dict(p=max(pp), fails=fails)
    m = len(ps) + N_EARLIER
    q, q_own = E.bh(ps, m), E.bh(ps)
    for i, n in enumerate(names):
        r = rows[n]
        r.update(q=float(q[i]), q_r110=float(q_own[i]))
        if q[i] > GATE_Q:
            r["fails"].append(f"BH q {q[i]:.3g} > 0.10 (m = {m:,})")
        r["verdict"] = "PASS" if not r["fails"] else "FAIL"
    out = dict(tests=rows, m=m)
    (RESULTS / "decision.json").write_text(json.dumps(jsonable(out), indent=1))
    return out


# ------------------------------------------------------------------------------------------------ report
LABEL = {"B0": "B0 R109 strangle (+-200)", "W1": "W1 wings +-700", "W2": "W2 wings +-1000", "X1": "X1 shorts +-500",
         "X1W": "X1W shorts +-500, wings +-1000", "S1": "S1 premium stop 1x credit", "S2": "S2 index breakeven stop",
         "F1": "F1 IV/RV >= its 52-week median", "F2": "F2 skip after a 2-sigma day"}
PER = (("history", "history"), ("design", "design"), ("wf", "walk-forward"), ("final", "final"), ("post", "post-13 Oct"))


def rs(x):
    return "-" if x is None else (("-Rs " if x < 0 else "Rs ") + f"{abs(x):,.0f}")


def report():
    D = json.loads((RESULTS / "design.json").read_text())
    F = json.loads((RESULTS / "final.json").read_text())
    C = json.loads((RESULTS / "decision.json").read_text())
    md = ["# R110 results (generated by r110study.py report)", "", f"BH over m = {C['m']:,} tests.", "",
          "| test | verdict | gating p | BH q (all) | q (R110 alone) | failed gates |", "|---|---|---|---|---|---|"]
    for n, r in C["tests"].items():
        md.append(f"| {LABEL[n]} | **{r['verdict']}** | {r['p']:.3g} | {r['q']:.3g} | {r['q_r110']:.3g} | "
                  f"{'; '.join(r['fails'])} |")
    md += ["", "## Every test and period (1 lot, after charges and slippage)", "",
           "| test | period | weeks | win | net | B0 on the same weeks | B0 (all its weeks) | worst week | worst month "
           "| max DD | months <= -13k | weeks <= -13k | stops | mean credit (pts) | margin (est.) | net / margin "
           "| boot p | skip-twin p | 5 worst weeks |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for n in ["B0"] + list(TESTS):
        for p, lab in PER:
            src = D if p in ("history", "design", "wf") else F
            s = src[n]["periods"].get(p)
            if not s or not s.get("n"):
                continue
            w5 = "; ".join(f"{d} {rs(v)}" for d, v in s.get("worst5", []))
            bp = s.get("boot_p")
            tp = s.get("skip_twin_p")
            md.append(f"| {LABEL[n]} | {lab} | {s['n']} | {s['win'] * 100:.0f}% | {rs(s['net'])} | "
                      f"{rs(s.get('b0_same_weeks_net'))} | {rs(s['b0_net'])} | {rs(s['worst_week'])} | "
                      f"{rs(s['worst_month'])} | {rs(s['max_dd'])} | {s['months_le_13k']} | {s['weeks_le_13k']} | "
                      f"{s['stops']} | {s['credit_mean']:.0f} | {rs(s['margin_mean'])} | "
                      f"{(s['net_per_margin'] or 0) * 100:.0f}% | {'-' if bp is None else f'{bp:.3g}'} | "
                      f"{'-' if tp is None else f'{tp:.3g}'} | {w5} |")
    (RESULTS / "report.md").write_text("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        run_phase("design")
    elif cmd == "final":
        if FINAL_FLAG.exists():
            raise SystemExit("the final window was already read (results/FINAL_READ); a second read is refused")
        if not (RESULTS / "design.json").exists():
            raise SystemExit("run design first")
        FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
        run_phase("final")
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    elif cmd == "report":
        report()
    else:
        raise SystemExit(__doc__)
