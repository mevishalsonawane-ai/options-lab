"""R181 runner (PREREG.md): the guide's order-flow setups on the phone's 1-second recordings, run once.

    python -I research/hunt/r181_orderflow_cases/r181.py --rec2 <1-second export dir> --rec1 <first export dir> \
        --cache <scratch cache dir>

Writes results/results.json (appending to its run log) and results/report.md (r181report.py). The recordings stay
outside git. Plain-English notes, 15 Nov 2026.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from dataclasses import asdict, replace
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path[:0] = [str(HERE), str(ROOT)]
import r181lib as LB  # noqa: E402
import r181load as LD  # noqa: E402

RESULTS = HERE / "results"
SEED = 20261115
N_TWINS = 200
M_PRIOR = 4051
CASES = ("C1", "C2", "C3", "C4", "C5", "A+")
VARIANT_CASES = ("C1", "C2", "A+")
# Expiry days of the recorded (nearest) options, PREREG section 0: from the token changes; 9 Nov inferred (10 Nov absent).
EXPIRY = {"NIFTY": {"2026-10-19", "2026-10-27", "2026-11-03", "2026-11-09"}, "BANKNIFTY": {"2026-10-27"}}
APLUS_LAST = "2026-10-28"
EXIT_NORMAL = 6 * 3600            # 15:15 from 09:15
EXIT_EXPIRY = 5 * 3600 + 50 * 60  # 15:05


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def days_of(dir2: str) -> list:
    return sorted(m.group(1) for f in os.listdir(dir2) if (m := re.match(r"orderflow-(\d{4}-\d{2}-\d{2})\.csv\.gz$", f)))


def levels_for(prev: dict, cur: dict, und: str, mode: str = "prereg"):
    """Prior day's futures volume profile (footprint), shifted by the change in basis. None when < 300 minutes.
    mode 'prereg': today's first basis - the prior day's last (PREREG section 3). mode 'amend2' (post hoc, Amendment 2):
    no shift on the same contract; on a roll, median basis of today's bars 0-9 - the prior day's median over 45-344."""
    P, C = prev[und], cur[und]
    if len(P["fp"]) < 300:
        return None
    pr = np.concatenate([v[0] for v in P["fp"].values()])
    vo = np.concatenate([v[1] + v[2] for v in P["fp"].values()])
    pb = P["bars"]
    lv = LB.volume_profile(pr, vo, LB.STEP[und], np.nanmax(pb.h), np.nanmin(pb.l))
    if lv is None:
        return None
    pbasis = pb.c - P["spot"]
    cbasis = C["bars"].c - C["spot"]
    a, z = np.flatnonzero(np.isfinite(pbasis)), np.flatnonzero(np.isfinite(cbasis))
    if not len(a) or not len(z):
        return None
    if mode == "prereg":
        delta = float(cbasis[z[0]] - pbasis[a[-1]])
    elif cur.get(und + "_fut_token") is not None and cur.get(und + "_fut_token") == prev.get(und + "_fut_token"):
        delta = 0.0
    else:
        delta = float(np.nanmedian(cbasis[:LB.FIRST_SIG]) - np.nanmedian(pbasis[45:345]))
    out = LB.shift_levels(lv, delta)
    out["shift"] = round(delta, 2)
    return out


def episodes(B, lv, und, fp, absorbf):
    eps = {}
    eps["C1"] = LB.case1(B, lv, und, absorbf, "L")
    eps["C2"] = LB.case1(B, lv, und, absorbf, "S")
    for c, fn in (("C3", lambda s: LB.case3(B, lv, und, fp, s)), ("C4", lambda s: LB.case4(B, lv, und, s)),
                  ("C5", lambda s: LB.case5(B, lv, und, s))):
        both = sorted(fn("L") + fn("S"), key=lambda e: e.r)
        eps[c] = both[:LB.MAX_EPISODES]
    return eps


def aplus(eps, lv, B, spot, day1, und):
    out = []
    for e in eps["C1"] + eps["C2"]:
        ch = LD.chain_at(day1, und, e.r)
        basis = B.c[e.r] - spot[e.r]
        lvl = lv["val"] if e.side == "L" else lv["vah"]
        if LB.oi_wall_ok(ch, e.side, lvl, basis, LB.STEP[und]):
            t2 = lv["vah"] if e.side == "L" else lv["val"]
            out.append(replace(e, case="A+", extra=dict(e.extra, t2=float(t2), chain=ch is not None)))
    return out


class Trader:
    """The option leg for one index-day (PREREG section 5)."""

    def __init__(self, D: dict, und: str, day: str):
        self.U, self.und, self.day = D[und], und, day
        self.d = date.fromisoformat(day)
        self.t0 = D["t0"]
        self.end = self.t0 + (EXIT_EXPIRY if day in EXPIRY[und] else EXIT_NORMAL)
        self.lot, self.step = LB.LOT[und], LB.STEP[und]

    def trade(self, r, side, stop, target, t2=None):
        spot = self.U["spot"][r]
        if not np.isfinite(spot):
            return None, "no_spot"
        a = LB.atm(spot, self.step)
        entry = self.t0 + 60 * (r + 1)
        right = "CE" if side == "L" else "PE"
        live = [k for (rt, k), s in self.U["opts"].items() if rt == right and s.recorded_near(entry)]
        k = LB.pick_strike(live, a, self.step, side)
        if k is None:
            return None, "no_strike"
        e_level = float(self.U["bars"].c[r])
        res = LB.simulate(self.U["fut"], self.U["opts"][(right, k)], entry, self.end, side, stop, target, self.lot,
                          self.d, t2=t2, e_level=e_level)
        if res is None:
            return None, "no_price"
        res.update(strike=k, atm=a, off_atm=bool(k != a))
        return res, "ok"


def run(dir2, dir1, cache, out_dir=RESULTS, mode="prereg"):
    rng = np.random.default_rng(SEED)
    twins_on = mode == "prereg"
    days = days_of(dir2)
    loaded = {d: LD.load_day2(dir2, d, cache) for d in days}
    trades, drops, quality, alt = [], {}, {}, []
    level_log = {}
    twins_by_trade = {}
    for k, day in enumerate(days):
        D = loaded[day]
        quality[day] = {u: D[u]["q"] for u in LD.INDICES}
        if k == 0:
            continue
        prev = loaded[days[k - 1]]
        day1 = LD.load_day1(dir1, day, cache) if day <= APLUS_LAST else None
        for und in LD.INDICES:
            lv = levels_for(prev, D, und, mode)
            level_log[f"{day} {und}"] = lv
            if lv is None:
                continue
            U = D[und]
            B = U["bars"]
            absorbf = LB.absorb_formula(B, U["fp"])
            eps = episodes(B, lv, und, U["fp"], absorbf)
            eps["A+"] = aplus(eps, lv, B, U["spot"], day1, und) if day1 else []
            # Source cross-check: the same bars with the first export's minute deltas.
            alt_of = {}
            if day1:
                d1 = day1[und]
                B2 = LB.Bars(B.o, B.h, B.l, B.c, B.v, np.where(d1["seen"], d1["d"], 0.0), np.where(d1["seen"], d1["secs"], 0))
                e2 = episodes(B2, lv, und, U["fp"], absorbf)
                e2["A+"] = aplus(e2, lv, B2, U["spot"], day1, und)
                for c, es in e2.items():
                    for e in es:
                        alt_of[(c, e.side, e.r)] = e.of
            T = Trader(D, und, day)
            for c in CASES:
                for e in eps[c]:
                    key = (c, e.side, e.r)
                    row = dict(case=c, side=e.side, und=und, day=day, b=int(e.b), r=int(e.r),
                               sig=f"{(9 * 60 + 15 + e.r) // 60:02d}:{(9 * 60 + 15 + e.r) % 60:02d}",
                               e=float(B.c[e.r]), stop=round(e.stop, 2), target=round(e.target, 2), of=e.of, ofv=e.ofv,
                               rr_ok=e.rr_ok, of_alt=alt_of.get(key), t2=e.extra.get("t2"))
                    if not e.rr_ok:
                        drops[f"{c}:rr"] = drops.get(f"{c}:rr", 0) + 1
                        continue
                    res, why = T.trade(e.r, e.side, e.stop, e.target, e.extra.get("t2"))
                    if res is None:
                        drops[f"{c}:{why}"] = drops.get(f"{c}:{why}", 0) + 1
                        continue
                    row.update(res)
                    row["id"] = len(trades)
                    trades.append(row)
                    if e.of and twins_on:
                        twins_by_trade[row["id"]] = random_twins(T, B, U, e, rng)
    if not twins_on:
        return dict(levels=level_log, cases={c: dict(
            price_only=LB.summary([t["net"] for t in trades if t["case"] == c], [t["net2"] for t in trades if t["case"] == c]),
            of=LB.summary([t["net"] for t in trades if t["case"] == c and t["of"]],
                          [t["net2"] for t in trades if t["case"] == c and t["of"]])) for c in CASES})
    out = analyse(trades, twins_by_trade, rng)
    out.update(drops=drops, quality=quality, levels=level_log, days=days, trades=trades,
               twins_by_trade={str(k): v for k, v in twins_by_trade.items()})
    return out


def random_twins(T, B, U, e, rng):
    """200 random entries on the same index, day and side, same stop / target (and T2) distances; nets (None = no
    price)."""
    ok = [r for r in range(LB.FIRST_SIG, LB.LAST_SIG + 1) if np.isfinite(B.c[r]) and np.isfinite(U["spot"][r])]
    E = float(B.c[e.r])
    ds, dt = abs(E - e.stop), abs(e.target - E)
    d2 = None if e.extra.get("t2") is None else abs(e.extra["t2"] - E)
    out = []
    for _ in range(N_TWINS):
        r = int(ok[rng.integers(len(ok))])
        E2 = float(B.c[r])
        sg = 1 if e.side == "L" else -1
        res, _ = T.trade(r, e.side, E2 - sg * ds, E2 + sg * dt, None if d2 is None else E2 + sg * d2)
        out.append(None if res is None else [res["net"], res["net2"]])
    return out


def analyse(trades, twins, rng):
    by = {}
    tests = []
    for c in CASES:
        tc = [t for t in trades if t["case"] == c]
        of = [t for t in tc if t["of"]]
        nof = [t for t in tc if not t["of"]]
        S = dict(price_only=LB.summary([t["net"] for t in tc], [t["net2"] for t in tc]),
                 of=LB.summary([t["net"] for t in of], [t["net2"] for t in of]),
                 of_fail=LB.summary([t["net"] for t in nof], [t["net2"] for t in nof]))
        ba = [t["net_ba"] for t in of if t.get("net_ba") is not None]
        S["of_bid_ask"] = LB.summary(ba)
        S["of_by_index"] = {u: LB.summary([t["net"] for t in of if t["und"] == u]) for u in LD.INDICES}
        S["of_by_side"] = {s: LB.summary([t["net"] for t in of if t["side"] == s]) for s in ("L", "S")}
        S["price_by_side"] = {s: LB.summary([t["net"] for t in tc if t["side"] == s]) for s in ("L", "S")}
        S["off_atm"] = sum(t["off_atm"] for t in tc)
        p1 = LB.selection_p([t["net"] for t in tc], [t["net"] for t in of], rng)
        # twin means per draw
        draws = []
        for j in range(N_TWINS):
            v = [twins[t["id"]][j][0] for t in of if twins[t["id"]][j] is not None]
            if v:
                draws.append(float(np.mean(v)))
        draws2 = []
        for j in range(N_TWINS):
            v = [twins[t["id"]][j][1] for t in of if twins[t["id"]][j] is not None]
            if v:
                draws2.append(float(np.mean(v)))
        p2 = LB.twin_p([t["net"] for t in of], draws)
        S["random_twin"] = dict(mean_of_means=round(float(np.mean(draws)), 1) if draws else None,
                                mean_of_means2=round(float(np.mean(draws2)), 1) if draws2 else None,
                                p10=round(float(np.percentile(draws, 10)), 1) if draws else None,
                                p90=round(float(np.percentile(draws, 90)), 1) if draws else None,
                                win=round(float(np.mean([x[0] > 0 for t in of for x in twins[t["id"]] if x])), 3)
                                if of else None)
        tests.append(dict(id=f"{c}:T1", p=p1))
        tests.append(dict(id=f"{c}:T2", p=p2))
        if c in VARIANT_CASES:
            ofv = [t for t in tc if t["ofv"]]
            S["variant"] = LB.summary([t["net"] for t in ofv], [t["net2"] for t in ofv])
            tests.append(dict(id=f"{c}:T1v", p=LB.selection_p([t["net"] for t in tc], [t["net"] for t in ofv], rng)))
        alt = [t for t in tc if t.get("of_alt") is not None]
        if alt:
            S["cross_check"] = dict(n=len(alt), agree=round(float(np.mean([t["of"] == t["of_alt"] for t in alt])), 3),
                                    of_this=LB.summary([t["net"] for t in alt if t["of"]]),
                                    of_other=LB.summary([t["net"] for t in alt if t["of_alt"]]))
        n8 = S["of"]["n"] >= 8
        fwd = (n8 and S["of"]["mean2"] is not None and S["of"]["mean2"] > 0
               and S["of"]["mean2"] > (S["price_only"]["mean2"] or -1e18) and p1 <= 0.20 and p2 <= 0.20)
        S["verdict"] = "worth a forward test" if fwd else ("too few trades" if not n8 else "no evidence")
        S["p_T1"], S["p_T2"] = round(p1, 4), round(p2, 4)
        by[c] = S
    p = [t["p"] for t in tests]
    q_all, q_in = LB.bh_with_prior(p, M_PRIOR + len(tests))
    for t, a, b in zip(tests, q_all, q_in):
        t["p"], t["q_all"], t["q_within"] = round(t["p"], 4), round(float(a), 4), round(float(b), 4)
    per_day = {}
    for t in trades:
        k = t["day"]
        per_day.setdefault(k, dict(of_n=0, of_net=0.0, price_n=0, price_net=0.0))
        per_day[k]["price_n"] += 1
        per_day[k]["price_net"] += t["net"]
        if t["of"]:
            per_day[k]["of_n"] += 1
            per_day[k]["of_net"] += t["net"]
    return dict(cases=by, tests=tests, m_total=M_PRIOR + len(tests), per_day=per_day)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--rec2", required=True)
    ap.add_argument("--rec1", required=True)
    ap.add_argument("--cache", default=None)
    ap.add_argument("--out", default=str(RESULTS))
    a = ap.parse_args(argv)
    out_dir = Path(a.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    res = run(a.rec2, a.rec1, a.cache, out_dir)
    res["sensitivity_amend2"] = run(a.rec2, a.rec1, a.cache, out_dir, mode="amend2")
    pj = out_dir / "results.json"
    runs = json.loads(pj.read_text()).get("runs", []) if pj.exists() else []
    runs.append(dict(at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), prereg_sha256=sha(HERE / "PREREG.md"),
                     code_sha256={f: sha(HERE / f) for f in ("r181.py", "r181lib.py", "r181load.py")},
                     trades=len(res["trades"])))
    res["runs"] = runs
    pj.write_text(json.dumps(res, indent=1, default=lambda o: o.item() if hasattr(o, "item") else str(o)))
    import r181report
    (out_dir / "report.md").write_text(r181report.render(res))
    print(json.dumps({c: dict(price=v["price_only"], of=v["of"], p1=v["p_T1"], p2=v["p_T2"]) for c, v in res["cases"].items()},
                     indent=0, default=str))


if __name__ == "__main__":
    main()
