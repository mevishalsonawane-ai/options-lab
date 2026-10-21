"""R37: the gamma-flip hero trigger (PREREG.md). Reuses R35's TWAP / volume / heavyweight checks, R36's ATM IV, and R25 /
R26 fills and charges.

    python3 research/hunt/r37/r37flip.py      # -> results/results.json
"""
from __future__ import annotations

import json
import math
import sys
from dataclasses import dataclass, replace
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT / "research" / "hunt" / "r34", ROOT / "research" / "hunt" / "r35", ROOT / "research" / "hunt" / "r36", HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))

import r35lib as S  # noqa: E402  (puts r25 / r26 / r34 on the path)
import r36gex as G  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r25rules as RU  # noqa: E402
import r26lib as L26  # noqa: E402
import r34feat as F  # noqa: E402
from r25data import C, minute  # noqa: E402
from research.brain import bs  # noqa: E402

RESULTS = HERE / "results"
UNDS = ("NIFTY", "SENSEX")
T_GEX = R.close_row(13, 45)
FIRST, LAST = R.close_row(14, 0), R.close_row(14, 45)
EXIT_ROW = minute(15, 5)
BUDGET = 2000.0
TWINS, SEED = 1000, 30


@dataclass(frozen=True)
class Rule:
    id: str
    note: str
    neg_side: bool = False
    exits: str = "T2"
    heavy: bool = True


G1 = Rule("G1", "spot crosses the 13:45 gamma flip + TWAP side + option volume 2x EMA10 + 3 heavyweights, 2x-breakeven / 50% trail")
RULES = [G1, replace(G1, id="G2", neg_side=True, note="G1 and the destination side has negative net GEX"),
         replace(G1, id="G3", exits="E1", note="G1 held to 15:05"),
         replace(G1, id="G4", heavy=False, note="G1 without the heavyweight check")]


# ------------------------------------------------------------------------------------------------ the GEX profile
def profile(day, t: int):
    """(strikes, call OI - put OI per strike, ATM IV, T) at row t; None when no OI or no IV."""
    sig = G.atm_iv(day, t)
    if not (sig == sig and sig > 0):
        return None
    ch = L26.chain(day)
    net: dict = {}
    for side, sgn in (("C", 1.0), ("P", -1.0)):
        ok = ch.present[side][:, t] & np.isfinite(ch.oi[side][:, t])
        for k, oi in zip(ch.k[side][ok], ch.oi[side][ok, t]):
            net[float(k)] = net.get(float(k), 0.0) + sgn * float(oi)
    if not net:
        return None
    ks = np.array(sorted(net))
    return ks, np.array([net[k] for k in ks]), sig, max(F.mins_left(t), 1) / F.YEAR_MIN


def net_gex(prof, s: float) -> float:
    ks, w, sig, T = prof
    g = bs.greeks(np.full(len(ks), s), ks, np.full(len(ks), T), np.full(len(ks), sig), True)[1]
    return float(np.nansum(w * g)) * s * s * 0.01


def flip(day, t: int = T_GEX):
    """The zero crossing of Net GEX nearest spot on spot x [0.97, 1.03] (step 0.05%); None without one."""
    s0 = R.spot(day, t)
    prof = profile(day, t)
    if prof is None or math.isnan(s0):
        return None, prof
    grid = s0 * (1 + np.arange(-60, 61) * 0.0005)              # spot x 0.97 .. 1.03 in 0.05% steps
    vals = np.array([net_gex(prof, x) for x in grid])
    sgn = np.sign(vals)
    cross = np.nonzero(sgn[:-1] * sgn[1:] < 0)[0]
    if not len(cross):
        return None, prof
    pts = [grid[i] - vals[i] * (grid[i + 1] - grid[i]) / (vals[i + 1] - vals[i]) for i in cross]
    return float(min(pts, key=lambda x: abs(x - s0))), prof


# ------------------------------------------------------------------------------------------------ the trigger
def fires(day, rule: Rule, ctx, fl: float, prof, t: int, sgn: int):
    c, cp = day.ix[t, 3], day.ix[t - 1, 3]
    if math.isnan(c) or math.isnan(cp):
        return None
    if not ((c - fl) * sgn > 0 and (cp - fl) * sgn <= 0):
        return None
    if math.isnan(ctx.tw[t]) or (c - ctx.tw[t]) * sgn <= 0:
        return None
    ko = S.nearest_otm(day, t, sgn)
    if ko is None:
        return None
    k, a = ko
    if not S.vol_ok(a, t):
        return None
    if rule.heavy and not S.heavy_ok(ctx, t, sgn):
        return None
    if rule.neg_side and not net_gex(prof, c) < 0:
        return None
    p = float(a[t, C])
    return (k, p) if p > 0 else None


def signal(day, rule: Rule, ctx, fl, prof):
    if fl is None:
        return None
    for t in range(FIRST, LAST + 1):
        for sgn in (1, -1):
            f = fires(day, rule, ctx, fl, prof, t, sgn)
            if f is not None:
                return t, sgn, f[0], f[1]
    return None


# ------------------------------------------------------------------------------------------------ the trade
def simulate(day, k: float, sgn: int, t: int, ref: float, exits: str, mult: float = 1.0, arm: str = "high"):
    """(trade or None, skipped-for-size). T2: breakeven lock at 2x, then 50% of each doubling level; else 15:05."""
    side = "C" if sgn > 0 else "P"
    f = R.enter(day, k, side, t, ref, mult)
    if f is None:
        return None, False
    fr, e = f
    lots = int(math.floor(BUDGET / (e * day.lot) + 1e-9)) if day.lot > 0 else 0
    if lots < 1:
        return None, True
    if exits == "E1":
        return L26.simulate(day, k, sgn, t, ref, "E1", BUDGET, mult), False
    tr = R.Trade(day.und, day.day, "T2", sgn, k, t, fr, e, lots, lots * day.lot)
    a = day.opts[(k, side)]
    best = e
    lock = -math.inf
    peak = e
    r = fr
    sold = False
    while r < EXIT_ROW:
        o, hi, c, px = R.path_px(day, k, side, r)
        tr.proxy |= px
        if r > fr and r <= EXIT_ROW - 2 and not math.isnan(c) and c <= lock + R.EPS:
            x = R._next_open(day, k, side, r + 1, False)
            if x is not None:
                rr, oo, pxx = x
                tr.exits.append((rr, tr.qty, R.sell_px(oo, R.vol5_lots(a, rr, day.lot) if not pxx else 0.0, mult), "lock"))
                sold = True
                break
        if not math.isnan(hi):
            conf = R.confirmed(a, r)
            if not px and not math.isnan(conf):
                peak = max(peak, min(hi, conf))
            if arm == "confirmed":                    # the exit audit's sensitivity (14 Oct): locks raised by confirmed prices
                if not px and not math.isnan(conf):
                    best = max(best, min(hi, conf))
            else:
                best = max(best, hi)
        if best >= 2 * e - R.EPS:                     # takes effect from the next minute's close
            lock = max(lock, e)
        if best >= 4 * e - R.EPS:
            lvl = 2 ** math.floor(math.log2(best / e) + 1e-9)
            lock = max(lock, 0.5 * lvl * e)
        r += 1
    if not sold:
        x = R._next_open(day, k, side, EXIT_ROW, False)
        if x is None:
            p_, row = R.ltp(a, EXIT_ROW - 1)
            rr, oo, pxx = row, (0.0 if math.isnan(p_) else p_), False
        else:
            rr, oo, pxx = x
        tr.exits.append((rr, tr.qty, R.sell_px(oo, R.vol5_lots(a, rr, day.lot) if not pxx else 0.0, mult), "1505"))
    for rr in range(r, EXIT_ROW):
        _, hi, _, px = R.path_px(day, k, side, rr)
        if not math.isnan(hi) and not px:
            conf = R.confirmed(a, rr)
            if not math.isnan(conf):
                peak = max(peak, min(hi, conf))
    tr.peak_mult = peak / e
    R._settle(tr)
    return tr, False


def twins(base: list, rule: Rule, n: int = TWINS, seed: int = SEED) -> np.ndarray:
    rng = np.random.default_rng(seed)
    cache: dict = {}
    out = np.zeros(n)
    for i in range(n):
        tot = 0.0
        for day, sgn in base:
            t = int(rng.integers(FIRST, LAST + 1))
            key = (day.und, day.day, t, sgn)
            if key not in cache:
                net = 0.0
                ko = S.nearest_otm(day, t, sgn)
                if ko is not None:
                    k, a = ko
                    pr, row = R.ltp(a, t)
                    if row >= t - 4 and pr == pr and pr > 0:
                        tr, _ = simulate(day, k, sgn, t, pr, rule.exits)
                        net = tr.net if tr else 0.0
                cache[key] = net
            tot += cache[key]
        out[i] = tot
    return out


class _R:
    unds = UNDS


def main():
    days = []
    for u in UNDS:
        days += sorted(D.load(u, "all"), key=lambda d: d.day)
    days.sort(key=lambda d: (d.day, d.und))
    ctxs, flips = {}, {}
    funnel = {u: {"expiries": 0, "oi_and_iv": 0, "flip_in_range": 0, "spot_crossed_in_window": 0} for u in UNDS}
    for d in days:
        key = (d.und, d.day)
        ctxs[key] = S.Ctx(d, None)
        fl, prof = flip(d)
        flips[key] = (fl, prof)
        f = funnel[d.und]
        f["expiries"] += 1
        f["oi_and_iv"] += prof is not None
        f["flip_in_range"] += fl is not None
        if fl is not None:
            c = d.ix[FIRST - 1: LAST + 1, 3]
            c = c[np.isfinite(c)]
            f["spot_crossed_in_window"] += bool(len(c) > 1 and np.any(np.sign(c[1:] - fl) != np.sign(c[:-1] - fl)))
    r36 = json.loads((ROOT / "research/hunt/r36/results/results.json").read_text())
    r35 = json.loads((ROOT / "research/hunt/r35/results/results.json").read_text())
    r34 = json.loads((ROOT / "research/hunt/r34/results/final.json").read_text())
    r26 = json.loads((ROOT / "research/hunt/r26/results/results.json").read_text())["bh_family"]
    fam = {k: v["p"] for k, v in r26.items()} | {f"R34-{k}": v["twins"]["p"] for k, v in r34["rules"].items()} | \
          {f"R35-{k}": v["twins"]["p"] for k, v in r35["rules"].items()} | \
          {f"R36-{k}": v["control"]["p"] for k, v in r36["rules"].items() if k != "B0"}
    res = {}
    for rule in RULES:
        trs, base, skips = [], [], 0
        for d in days:
            fl, prof = flips[(d.und, d.day)]
            s = signal(d, rule, ctxs[(d.und, d.day)], fl, prof)
            if s is None:
                continue
            t, sgn, k, p = s
            tr, sk = simulate(d, k, sgn, t, p, rule.exits)
            skips += sk
            if tr is not None:
                tr.sig = t
                trs.append(tr)
                base.append((d, sgn))
        m = RU.metrics(trs, days, _R())
        n = len(trs)
        for x in (2, 4, 10):
            m[f"x{x}_pct"] = round(100 * sum(t.peak_mult >= x for t in trs) / n, 1) if n else 0.0
        tw = twins(base, rule)
        x2 = 0.0
        for d, t_ in zip([b[0] for b in base], trs):
            tr2, _ = simulate(d, t_.strike, t_.side, t_.sig, float(d.opts[(t_.strike, "C" if t_.side > 0 else "P")][t_.sig, C]), rule.exits, 2.0)
            x2 += tr2.net if tr2 else 0.0
        by = lambda f: RU.metrics([t for t in trs if f(t.und, t.day)], [d for d in days if f(d.und, d.day)], _R())  # noqa: E731
        p_ = float((tw >= m["net"] - 1e-9).mean()) if n else 1.0
        res[rule.id] = {"note": rule.note, "m": m, "skips_1lot_over_2000": skips,
                        "index": {u: by(lambda uu, dd, u=u: uu == u) for u in UNDS},
                        "blocks": {"2021-22": by(lambda uu, dd: dd.year <= 2022), "2023-26": by(lambda uu, dd: dd.year >= 2023)},
                        "years": {str(y): by(lambda uu, dd, y=y: dd.year == y) for y in range(2021, 2027)},
                        "direction": {"CE": sum(t.side > 0 for t in trs), "PE": sum(t.side < 0 for t in trs)},
                        "x2_spread": round(x2, 0), "twins": {"p": round(p_, 4), "mean": round(float(tw.mean()), 0)},
                        "trades_list": [dict(und=t.und, day=str(t.day), side="CE" if t.side > 0 else "PE", strike=t.strike, sig=t.sig,
                                             entry=t.entry, lots=t.lots, peak=round(t.peak_mult, 2), net=round(t.net, 0),
                                             exit=t.exits[-1][3] if t.exits else "") for t in trs]}
        fam[f"R37-{rule.id}"] = p_
        print(rule.id, n, round(m["net"]), "skips", skips, "p", round(p_, 3), flush=True)
    qs = dict(zip(fam, RU.bh(list(fam.values()))))
    for rid, v in res.items():
        v["q"] = round(qs[f"R37-{rid}"], 4)
        m = v["m"]
        g = {"1 >= 30 trades": m["trades"] >= 30, "2 net > 0": m["net"] > 0,
             "3 blocks > 0": all(v["blocks"][b]["net"] > 0 for b in v["blocks"] if v["blocks"][b]["trades"] > 0),
             "4 BH q < 0.05": v["q"] < 0.05, "5 x2 spread > 0": v["x2_spread"] > 0,
             "6 w/o best day > 0": m["net_without_best_day"] > 0, "7 max DD > -40k": m["max_dd"] > -40000}
        v["gates"], v["passes"] = g, all(g.values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    out = {"bh_size": len(fam), "bh_min_q": round(min(qs.values()), 4), "funnel": funnel, "rules": res}
    (RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps(funnel))
    print("BH", len(fam), "min q", out["bh_min_q"], "passes", [k for k, v in res.items() if v["passes"]])


if __name__ == "__main__":
    main()
