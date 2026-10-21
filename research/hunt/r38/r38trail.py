"""R38: Boss's 25% trailing exit (PREREG.md) on every earlier hero study's entries.

    python3 research/hunt/r38/r38trail.py      # -> results/results.json

TR25: the peak = the highest CONFIRMED price since the fill (a minute's high counts up to max(its close, the next open));
stop = 75% of the peak, from the entry; a minute close <= the stop sells everything at the next open; else the study's
own end of day (15:05; R34's K rules 15:25).
"""
from __future__ import annotations

import json
import math
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT / "research" / "hunt" / "r34", ROOT / "research" / "hunt" / "r35", ROOT / "research" / "hunt" / "r36",
          ROOT / "research" / "hunt" / "r37", HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))

import r34cfg  # noqa: E402,F401
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r25rules as RU  # noqa: E402
from r25data import C, minute  # noqa: E402

RESULTS = HERE / "results"
END = minute(15, 5)
END_K = minute(15, 25)
GIVE = 0.25
DAYS: dict = {}


def day(und: str, d) -> D.Day:
    if und not in DAYS:
        DAYS[und] = {x.day: x for x in D.load(und, "all")}
    return DAYS[und][d if isinstance(d, date) else date.fromisoformat(str(d))]


def simulate(dy, k: float, sgn: int, sig: int, ref: float, budget: float, end_row: int = END, skip_over: bool = False,
             mult: float = 1.0, arm: str = "confirmed"):
    """One TR25 trade; None when not filled (or skipped for size)."""
    side = "C" if sgn > 0 else "P"
    saved = R.EXIT_ROW
    R.EXIT_ROW = max(saved, end_row)               # the K rules enter as late as 15:21 (as R34 did)
    try:
        f = R.enter(dy, k, side, sig, ref, mult)
    finally:
        R.EXIT_ROW = saved
    if f is None or dy.lot <= 0:
        return None
    fr, e = f
    lots = int(math.floor(budget / (e * dy.lot) + 1e-9))
    if lots < 1:
        if skip_over:
            return None
        lots = 1
    tr = R.Trade(dy.und, dy.day, "TR25", sgn, k, sig, fr, e, lots, lots * dy.lot)
    a = dy.opts[(k, side)]
    peak = e
    conf_peak = e
    r = fr
    sold = False
    while r < end_row:
        o, hi, c, px = R.path_px(dy, k, side, r)
        tr.proxy |= px
        if r > fr and r <= end_row - 2 and not math.isnan(c) and c <= (1 - GIVE) * peak + R.EPS:
            x = R._next_open(dy, k, side, r + 1, False)
            if x is not None:
                rr, oo, pxx = x
                tr.exits.append((rr, tr.qty, R.sell_px(oo, R.vol5_lots(a, rr, dy.lot) if not pxx else 0.0, mult), "trail"))
                sold = True
                break
        if not math.isnan(hi):
            cf = R.confirmed(a, r)
            ok = not px and not math.isnan(cf)
            if ok:
                conf_peak = max(conf_peak, min(hi, cf))
            if arm == "confirmed":
                if ok:
                    peak = max(peak, min(hi, cf))
            else:
                peak = max(peak, hi)
        r += 1
    if not sold:
        x = R._next_open(dy, k, side, end_row, False)
        if x is None:
            p_, row = R.ltp(a, end_row - 1)
            rr, oo, pxx = row, (0.0 if math.isnan(p_) else p_), False
        else:
            rr, oo, pxx = x
        tr.exits.append((rr, tr.qty, R.sell_px(oo, R.vol5_lots(a, rr, dy.lot) if not pxx else 0.0, mult), "end"))
    for rr in range(r, end_row):
        _, hi, _, px = R.path_px(dy, k, side, rr)
        if not math.isnan(hi) and not px:
            cf = R.confirmed(a, rr)
            if not math.isnan(cf):
                conf_peak = max(conf_peak, min(hi, cf))
    tr.peak_mult = conf_peak / e
    R._settle(tr)
    tr.exit_px = tr.exits[-1][2]
    return tr


# ------------------------------------------------------------------------------------------------ the entries
def hm_row(hm: str) -> int:
    h, m = map(int, hm.split(":"))
    return R.close_row(h, m)


def entries() -> dict:
    """{rule: {"pos": [(und, day, k, sgn, sig, ref, budget, end_row, skip_over)], "published": net}}"""
    out = {}
    r26 = json.loads((ROOT / "research/hunt/r26/results/results.json").read_text())["rules"]
    for rid in ("P", "A1", "A2", "A3", "A7", "B1"):
        pos = []
        for t in r26[rid]["trades_list"]:
            dy = day(t["und"], t["day"])
            sgn = 1 if t["side"] == "CE" else -1
            row = hm_row(t["signal"])
            ref = R.ltp(dy.opts[(t["strike"], "C" if sgn > 0 else "P")], row)[0]
            pos.append((t["und"], t["day"], t["strike"], sgn, row, ref, 2000.0, END, False))
        out[f"R26-{rid}"] = {"pos": pos, "published": r26[rid]["m"]["net"]}
    r34 = json.loads((ROOT / "research/hunt/r34/results/final.json").read_text())["rules"]
    for rid in ("M1", "M2", "F1", "G1", "Q1", "Q2"):
        pos = [("NIFTY", t["day"], t["strike"], 1 if t["side"] == "C" else -1, t["t"], t["price"], 2000.0, END, False)
               for t in r34[rid]["trades_list"]]
        out[f"R34-{rid}"] = {"pos": pos, "published": r34[rid]["m"]["net"]}
    import r34late as KL
    kres = json.loads((ROOT / "research/hunt/r34/results/k_results.json").read_text())
    for rid, w in (("K1", 1), ("K2", 2), ("K3", 3)):
        pos = []
        for t in kres[rid]["trades_list"]:
            dy = day("NIFTY", t["day"])
            s = KL.signal(dy, w)
            pos.append(("NIFTY", t["day"], s[2], s[1], s[0], s[3], 2000.0, END_K, False))
        out[f"R34-{rid}"] = {"pos": pos, "published": kres[rid]["m"]["net"]}
    r35 = json.loads((ROOT / "research/hunt/r35/results/results.json").read_text())["rules"]
    for rid in ("S1", "S3", "S4", "S5"):
        pos = []
        for t in r35[rid]["trades_list"]:
            dy = day(t["und"], t["day"])
            sgn = 1 if t["side"] == "CE" else -1
            pos.append((t["und"], t["day"], t["strike"], sgn, t["sig"], float(dy.opts[(t["strike"], "C" if sgn > 0 else "P")][t["sig"], C]),
                        2000.0, END, True))
        out[f"R35-{rid}"] = {"pos": pos, "published": r35[rid]["m"]["net"]}
    import r36gex as G
    rows = G.build()
    r36 = json.loads((ROOT / "research/hunt/r36/results/results.json").read_text())["rules"]
    for rid in ("X1", "X2", "X3", "X4"):
        pos = []
        for r in G.select(rows, rid):
            for leg in r.get("legs", []):
                if rid == "X4" and not (leg["ivrv"] == leg["ivrv"] and leg["ivrv"] <= 1.0 + 1e-9):
                    continue
                if leg["E3_1"] is None:
                    continue
                pos.append((r["und"], str(r["day"]), leg["k"], leg["sgn"], G.T0, leg["p"], 1000.0, END, False))
        out[f"R36-{rid}"] = {"pos": pos, "published": r36[rid]["m"]["net"]}
    r37 = json.loads((ROOT / "research/hunt/r37/results/results.json").read_text())["rules"]
    for rid in ("G1", "G2", "G4"):
        pos = []
        for t in r37[rid]["trades_list"]:
            dy = day(t["und"], t["day"])
            sgn = 1 if t["side"] == "CE" else -1
            pos.append((t["und"], t["day"], t["strike"], sgn, t["sig"], float(dy.opts[(t["strike"], "C" if sgn > 0 else "P")][t["sig"], C]),
                        2000.0, END, True))
        out[f"R37-{rid}"] = {"pos": pos, "published": r37[rid]["m"]["net"]}
    return out


# ------------------------------------------------------------------------------------------------ evaluation
def evaluate(pos: list, mult: float = 1.0, arm: str = "confirmed"):
    trs = []
    for und, d, k, sgn, sig, ref, budget, end_row, skip in pos:
        t = simulate(day(und, d), k, sgn, sig, ref, budget, end_row, skip, mult, arm)
        if t is not None:
            trs.append(t)
    return trs


def stats(trs: list) -> dict:
    by = defaultdict(float)
    risk = 0.0
    for t in trs:
        by[(t.day, t.und)] += t.net
        risk += t.risked
    keys = sorted(by)
    nets = np.array([by[k] for k in keys])
    if not len(nets):
        return {"positions": 0, "days": 0, "net": 0.0}
    eq = np.cumsum(nets)
    dd = float(np.min(eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]))
    streak = cur = 0
    for v in nets:
        cur = cur + 1 if v <= 0 else 0
        streak = max(streak, cur)
    give = [1 - t.exit_px / (t.peak_mult * t.entry) for t in trs if t.peak_mult > 1.0 + 1e-9]
    out = {"positions": len(trs), "days": int(len(nets)), "net": round(float(nets.sum()), 0),
           "per_1000": round(float(nets.sum()) / risk * 1000, 1) if risk else 0.0,
           "hit_pct": round(float((nets > 0).mean()) * 100, 1), "worst_day": round(float(nets.min()), 0),
           "best_day": round(float(nets.max()), 0), "max_dd": round(dd, 0), "losing_streak": streak,
           "wo_best": round(float(nets.sum() - nets.max()), 0),
           "x2_pct": round(100 * float(np.mean([t.peak_mult >= 2 for t in trs])), 1),
           "x4_pct": round(100 * float(np.mean([t.peak_mult >= 4 for t in trs])), 1),
           "x10_pct": round(100 * float(np.mean([t.peak_mult >= 10 for t in trs])), 1),
           "trail_exits_pct": round(100 * float(np.mean([t.exits[-1][3] == "trail" for t in trs])), 1),
           "avg_giveback_pct": round(100 * float(np.mean(give)), 1) if give else None,
           "p": RU.boot_p(nets)}
    for blk, f in (("2021-22", lambda d: d.year <= 2022), ("2023-26", lambda d: d.year >= 2023)):
        b = [by[k] for k in keys if f(k[0])]
        out[f"block_{blk}"] = (len(b), round(float(sum(b)), 0))
    return out


def earlier_family() -> dict:
    r26 = json.loads((ROOT / "research/hunt/r26/results/results.json").read_text())["bh_family"]
    r34 = json.loads((ROOT / "research/hunt/r34/results/final.json").read_text())["rules"]
    r35 = json.loads((ROOT / "research/hunt/r35/results/results.json").read_text())["rules"]
    r36 = json.loads((ROOT / "research/hunt/r36/results/results.json").read_text())["rules"]
    r37 = json.loads((ROOT / "research/hunt/r37/results/results.json").read_text())["rules"]
    return {k: v["p"] for k, v in r26.items()} | {f"R34-{k}": v["twins"]["p"] for k, v in r34.items()} | \
        {f"R35-{k}": v["twins"]["p"] for k, v in r35.items()} | \
        {f"R36-{k}": v["control"]["p"] for k, v in r36.items() if k != "B0"} | \
        {f"R37-{k}": v["twins"]["p"] for k, v in r37.items()}


def main():
    ent = entries()
    fam = earlier_family()
    res = {}
    for rid, e in ent.items():
        trs = evaluate(e["pos"])
        m = stats(trs)
        m["published_exit_net"] = round(e["published"], 0)
        m["x2_spread"] = stats(evaluate(e["pos"], mult=2.0))["net"]
        m["raw_high_net"] = stats(evaluate(e["pos"], arm="high"))["net"]
        res[rid] = m
        fam[f"R38-{rid}"] = m.get("p", 1.0)
        print(rid, m["days"], m["net"], "published", m["published_exit_net"], "p", round(m.get("p", 1.0), 3), flush=True)
    qs = dict(zip(fam, RU.bh(list(fam.values()))))
    for rid, m in res.items():
        m["q"] = round(qs[f"R38-{rid}"], 4)
        g = {"1 >= 30 trades": m["days"] >= 30, "2 net > 0": m["net"] > 0,
             "3 blocks > 0": all(m[f"block_{b}"][1] > 0 for b in ("2021-22", "2023-26") if m.get(f"block_{b}", (0, 0))[0] > 0),
             "4 BH q < 0.05": m["q"] < 0.05, "5 x2 spread > 0": m["x2_spread"] > 0,
             "6 w/o best day > 0": m.get("wo_best", -1) > 0, "7 max DD > -40k": m.get("max_dd", -1e9) > -40000}
        m["gates"], m["passes"] = g, all(g.values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    out = {"bh_size": len(fam), "bh_min_q": round(min(qs.values()), 4), "rules": res}
    (RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    print("BH", len(fam), "min q", out["bh_min_q"], "passes", [k for k, v in res.items() if v["passes"]])


if __name__ == "__main__":
    main()
