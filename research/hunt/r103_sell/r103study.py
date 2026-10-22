"""R103 study (PREREG.md): 24 defined-risk selling tests on NIFTY and BANKNIFTY, design first, the holdout once.

    R103_LAKE=<lake> R103_OUT=<cache> python research/hunt/r103_sell/r103study.py design    # results/design.json
    ... python research/hunt/r103_sell/r103study.py holdout   # ONE read for design passers (results/HOLDOUT_READ)

Trades go to <R103_OUT>/trades_<part>.pkl (never in git); results/ holds small JSON only.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r103data as RD  # noqa: E402
import r103engine as E  # noqa: E402

RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"
DESIGN_END = date(2026, 7, 14)
HOLDOUT_START, HOLDOUT_END = date(2026, 7, 15), date(2026, 10, 19)
NEW_REGIME = date(2024, 11, 20)
WF_YEARS = (2022, 2023, 2024, 2025)
SEED = 103
BOOT = 10_000
TWIN_PER_DAY, TWIN_DRAWS = 20, 200
TWIN_LAST_DECIDE = 224          # entries up to 13:00 (decide 12:59)
BH_Q, BOOT_P, MIN_TRADES, MONTH_STOP = 0.10, 0.05, 40, -13000.0
TESTS = [(u, t, s, x) for u in RD.UNDS for t in ("E", "N") for s in E.STRUCTS for x in E.EXITS]


def test_id(u, t, s, x) -> str:
    return f"{u}-{t}-{s}-{x}"


def period_of(d: date) -> str:
    if d <= DESIGN_END:
        return "design"
    if HOLDOUT_START <= d <= HOLDOUT_END:
        return "holdout"
    return "after"


def expiry_info(und: str, days: dict) -> tuple[set, dict]:
    """(expiry days, {day: nearest expiry date}) from the bhavcopy calendar (R102) where it reaches; after its last day,
    R51's straddle flag and the 2025-26 rule (NIFTY: next Tuesday; BANKNIFTY: the month's last Tuesday)."""
    out, near, last = set(), {}, None
    try:
        sys.path.insert(0, str(HERE.parent / "r102_deep"))
        os.environ.setdefault("R102_LAKE", str(RD.LAKE))
        import r102data as B
        B.LAKE = RD.LAKE
        e = B.expiries(und)
        out = set(e[e.is_expiry].day)
        near = {d: x.date() for d, x in zip(e.day, e.expiry)}
        last = max(e.day)
    except Exception as ex:  # pragma: no cover - only without the bhavcopy files
        print(f"bhavcopy calendar unavailable ({ex}); using R51's flag", file=sys.stderr)
    for d, D in days.items():
        if last is None or d > last:
            if D.expiry:
                out.add(d)
            near[d] = next_expiry(und, d)
    return out, near


def next_expiry(und: str, d: date) -> date:
    """The 2025-26 calendar rule, for days past the bhavcopy (holidays ignored)."""
    if und == "NIFTY":
        return d + timedelta(days=(1 - d.weekday()) % 7)
    y, m = d.year, d.month
    for _ in range(2):
        last = date(y + (m == 12), m % 12 + 1, 1) - timedelta(days=1)
        lt = last - timedelta(days=(last.weekday() - 1) % 7)
        if lt >= d:
            return lt
        y, m = y + (m == 12), m % 12 + 1
    return lt


def _one_test(args):
    part, mult, tid = args
    return tid, _trades_one(part, mult, tid)


def _trades_one(part: str, mult: float, tid: str) -> list:
    u, t, s, x = tid.split("-")
    days = RD.load(u)
    exp, near = expiry_info(u, days)
    out = []
    for d in sorted(days):
        if d < RD.START or period_of(d) != part or days[d].atm is None:
            continue
        if ("E" if d in exp else "N") != t:
            continue
        tr = E.run(days[d], u, d, s, x, t == "E", mult, expiry_date=near.get(d))
        if tr is not None:
            out.append(tr)
    return out


def trades_parallel(part: str, mult: float = 1.0, only: set | None = None, procs: int = 4) -> dict:
    """As trades_for, one process per test (same trades; the order of days inside a test is kept)."""
    if part == "holdout" and not HOLDOUT_FLAG.exists():
        raise PermissionError("the holdout is locked: only `r103study.py holdout` may read it (PREREG.md)")
    import multiprocessing as mp
    ids = [test_id(*t) for t in TESTS if only is None or test_id(*t) in only]
    with mp.get_context("fork").Pool(procs) as pool:
        return dict(pool.map(_one_test, [(part, mult, i) for i in ids]))


def trades_for(part: str, mult: float = 1.0, only: set | None = None) -> dict:
    """{test id: [trade, ...]} for every test (or the ids in `only`), one trade per eligible day."""
    if part == "holdout" and not HOLDOUT_FLAG.exists():
        raise PermissionError("the holdout is locked: only `r103study.py holdout` may read it (PREREG.md)")
    out = defaultdict(list)
    for u in RD.UNDS:
        days = RD.load(u)
        exp, near = expiry_info(u, days)
        for d in sorted(days):
            if d < RD.START or period_of(d) != part or days[d].atm is None:
                continue
            D = days[d]
            t = "E" if d in exp else "N"
            for (uu, tt, s, x) in TESTS:
                if uu != u or tt != t:
                    continue
                tid = test_id(uu, tt, s, x)
                if only is not None and tid not in only:
                    continue
                tr = E.run(D, u, d, s, x, t == "E", mult, expiry_date=near.get(d))
                if tr is not None:
                    out[tid].append(tr)
    return out


def twin_means(part: str, tid: str, real: list, rng) -> np.ndarray:
    """Timing twins (reported, not gated): per traded day TWIN_PER_DAY random decide minutes 5..224, same structure and
    exits; TWIN_DRAWS totals, each one random twin per day."""
    u, t, s, x = tid.split("-")
    days = RD.load(u)
    _, near = expiry_info(u, days)
    per_day = []
    for tr in real:
        D = days[tr["day"]]
        nets = []
        for r in rng.integers(E.DECIDE_ROW, TWIN_LAST_DECIDE + 1, size=TWIN_PER_DAY):
            z = E.run(D, u, tr["day"], s, x, t == "E", 1.0, decide=int(r), expiry_date=near.get(tr["day"]))
            if z is not None:
                nets.append(z["net"])
        per_day.append(nets or [0.0])
    tot = np.zeros(TWIN_DRAWS)
    for nets in per_day:
        tot += np.asarray(nets)[rng.integers(0, len(nets), size=TWIN_DRAWS)]
    return tot


def max_dd(daily: list) -> float:
    eq = np.cumsum(daily) if daily else np.zeros(1)
    return float(np.max(np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:] - eq)) if daily else 0.0


def boot_p(nets: np.ndarray, rng) -> float:
    """One-sided day-bootstrap p of mean <= 0 (trades are one a day per test)."""
    if len(nets) < 2:
        return 1.0
    means = nets[rng.integers(0, len(nets), size=(BOOT, len(nets)))].mean(axis=1)
    return float((means <= 0).mean())


def bh(p) -> list:
    p = np.asarray(p, float)
    n = len(p)
    if not n:
        return []
    o = np.argsort(p)
    r = p[o] * n / (np.arange(n) + 1)
    q = np.minimum.accumulate(r[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.clip(q, 0, 1)
    return out.tolist()


def summary(tr: list, rng=None) -> dict:
    if not tr:
        return dict(n=0, net=0.0)
    nets = np.array([t["net"] for t in tr])
    w, l = nets[nets > 0], nets[nets <= 0]
    months = defaultdict(float)
    years = defaultdict(float)
    for t in tr:
        months[t["day"].strftime("%Y-%m")] += t["net"]
        years[t["day"].year] += t["net"]
    order = sorted(tr, key=lambda t: t["day"])
    daily = [t["net"] for t in order]
    best5 = float(np.sort(nets)[-5:].sum()) if len(nets) >= 5 else float(nets.sum())
    out = dict(n=int(len(nets)), net=round(float(nets.sum())), mean=round(float(nets.mean()), 1),
               win=round(float((nets > 0).mean()), 3), avg_win=round(float(w.mean()), 1) if len(w) else 0.0,
               avg_loss=round(float(l.mean()), 1) if len(l) else 0.0,
               pf=round(float(w.sum() / -l.sum()), 3) if len(l) and l.sum() < 0 else None,
               worst_day=round(float(nets.min())), best_day=round(float(nets.max())), max_dd=round(max_dd(daily)),
               worst_month=round(min(months.values())), months_below_stop=int(sum(v < MONTH_STOP for v in months.values())),
               ex_best5=round(float(nets.sum() - best5)), by_year={int(y): round(v) for y, v in sorted(years.items())},
               post_regime_net=round(float(sum(t["net"] for t in tr if t["day"] >= NEW_REGIME))),
               post_regime_n=int(sum(t["day"] >= NEW_REGIME for t in tr)),
               mean_credit=round(float(np.mean([t["credit"] for t in tr]))),
               mean_max_loss=round(float(np.mean([t["max_loss"] for t in tr]))),
               exits={k: int(sum(t["why"] == k for t in tr)) for k in ("stop", "lock", "target", "time")},
               first=str(order[0]["day"]), last=str(order[-1]["day"]))
    if rng is not None:
        out["boot_p"] = boot_p(nets, rng)
    return out


def gates(s: dict, s2x: dict, q: float) -> dict:
    yrs = [s["by_year"].get(y, 0) for y in WF_YEARS]
    g = {"1_mean_gt_0_boot": s["net"] > 0 and s.get("boot_p", 1) < BOOT_P, "2_bh": q <= BH_Q,
         "3_years_and_regime": sum(v > 0 for v in yrs) >= 3 and s["post_regime_net"] > 0,
         "4_trades": s["n"] >= MIN_TRADES, "5_ex_best5": s["ex_best5"] > 0,
         "6_account_stop": s["worst_month"] >= MONTH_STOP, "7_slip_2x": s2x["net"] > 0}
    g["pass"] = all(g.values())
    return g


def _json(obj, path: Path):
    path.write_text(json.dumps(obj, indent=1, default=str) + "\n")


def _save(name: str, obj):
    RD.OUT.mkdir(parents=True, exist_ok=True)
    with open(RD.OUT / f"{name}.pkl", "wb") as f:
        pickle.dump(obj, f, protocol=pickle.HIGHEST_PROTOCOL)


def bhav_check(und: str, trades: list) -> dict:
    """Independent check (Amendment 1): the time exits re-priced at the F&O bhavcopy close of every leg (15:30, the same
    contract), with the same entry, slippage model at 1 tick and dated charges. Stops, locks and targets keep their
    minute fills. Returns totals over the trades that have a bhavcopy price for all four legs."""
    try:
        sys.path.insert(0, str(HERE.parent / "r102_deep"))
        import r102data as B
        B.LAKE = RD.LAKE
        b = B.bhav(und)
    except Exception as ex:  # pragma: no cover
        print(f"bhavcopy check unavailable: {ex}", file=sys.stderr)
        return dict(n=0)
    b = b[b.oi_qty > 0]
    near = b.groupby("date").expiry.min()
    key = b.set_index(["date", "expiry", "kind", "strike"]).close
    key = key[~key.index.duplicated()]
    import pandas as pd
    n, net_min, net_bh, win_min, win_bh = 0, 0.0, 0.0, 0, 0
    for t in trades:
        d = pd.Timestamp(t["day"])
        if d not in near.index:
            continue
        net = t["net"]
        if t["why"] == "time":
            px = [key.get((d, near[d], "CE" if s_ == "C" else "PE", float(k)), np.nan) for s_, k, _ in t["legs"]]
            if not np.all(np.isfinite(px)):
                continue
            q = t["lot"]
            exit_ch = sum(E.charges(x, q, sg < 0, t["day"]) for (_, _, sg), x in zip(t["legs"], t["exitpx"]))
            gross, ch = 0.0, t["charges"] - exit_ch
            for (_, _, sg), f, p in zip(t["legs"], t["entry"], px):
                x = E.slip(float(p), sg < 0, E.THIN_LOTS)
                gross += sg * (x - f) * q
                ch += E.charges(x, q, sg < 0, t["day"])
            net = gross - ch
        n += 1
        net_min += t["net"]
        net_bh += net
        win_min += t["net"] > 0
        win_bh += net > 0
    return dict(n=n, net_minute=round(net_min), net_bhav=round(net_bh), win_minute=round(win_min / n, 3) if n else None,
                win_bhav=round(win_bh / n, 3) if n else None)


def design() -> dict:
    rng = np.random.default_rng(SEED)
    tr = trades_parallel("design")
    tr2 = trades_parallel("design", mult=2.0)
    _save("trades_design", dict(tr))
    rows = []
    for tid in [test_id(*t) for t in TESTS]:
        s = summary(tr.get(tid, []), rng)
        s2 = summary(tr2.get(tid, []))
        rows.append(dict(id=tid, design=s, slip2x_net=s2.get("net", 0)))
    qs = bh([r["design"].get("boot_p", 1.0) for r in rows])
    for r, q in zip(rows, qs):
        r["q_bh"] = round(q, 4)
        r["gates"] = gates(r["design"], dict(net=r["slip2x_net"]), q)
        r["high_win_label"] = bool(r["design"].get("win", 0) >= 0.60 and (r["design"].get("pf") or 0) >= 1.1)
        r["bhav_check"] = bhav_check(r["id"].split("-")[0], tr.get(r["id"], []))
    for r in rows:                                    # timing twins, reported only (amendment 2: design passers only)
        if r["design"]["n"] and r["gates"]["pass"]:
            tw = twin_means("design", r["id"], tr[r["id"]], rng)
            r["timing_twin"] = dict(mean_total=round(float(tw.mean())), p_twin_ge_real=round(float((tw >= r["design"]["net"]).mean()), 3))
    res = dict(part="design", design_end=str(DESIGN_END), n_tests=len(rows), passed=[r["id"] for r in rows if r["gates"]["pass"]],
               tests=rows)
    RESULTS.mkdir(parents=True, exist_ok=True)
    _json(res, RESULTS / "design.json")
    print(f"design: {len(rows)} tests; passed: {res['passed']}")
    return res


def holdout() -> dict:
    if HOLDOUT_FLAG.exists():
        print("the holdout was already read once (results/HOLDOUT_READ); PREREG.md allows no second read", file=sys.stderr)
        raise SystemExit(2)
    dz = json.loads((RESULTS / "design.json").read_text())
    passed = set(dz["passed"])
    HOLDOUT_FLAG.write_text(f"read on {date.today().isoformat()} by r103study.py holdout; design passers: {sorted(passed)}\n")
    if not passed:
        res = dict(part="holdout", read=False, note="no test passed design; nothing read", tests=[])
        _json(res, RESULTS / "holdout.json")
        print("holdout: no design passer, nothing read")
        return res
    tr = trades_parallel("holdout", only=passed)
    _save("trades_holdout", dict(tr))
    rows = []
    for tid in sorted(passed):
        s = summary(tr.get(tid, []))
        dsg = next(r["design"] for r in dz["tests"] if r["id"] == tid)
        ok = s.get("n", 0) > 0 and s["net"] > 0 and s["worst_month"] >= MONTH_STOP and abs(s["win"] - dsg["win"]) <= 0.15
        rows.append(dict(id=tid, holdout=s, confirmed=bool(ok)))
    res = dict(part="holdout", read=True, confirmed=[r["id"] for r in rows if r["confirmed"]], tests=rows)
    _json(res, RESULTS / "holdout.json")
    print(f"holdout: confirmed {res['confirmed']}")
    return res


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "holdout":
        holdout()
    else:
        raise SystemExit(__doc__)
