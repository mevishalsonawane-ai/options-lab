"""R36: gamma exposure (GEX) regimes and expiry-afternoon option buying (PREREG.md).

    python3 research/hunt/r36/r36gex.py      # -> results/results.json

One decision per expiry and index at the 14:00 bar close (row R.close_row(14, 0)); every measure reads rows <= it.
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np
from scipy.stats import spearmanr

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT / "research" / "hunt" / "r34", HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))

import r34cfg  # noqa: E402,F401  (r25 / r26 on the path; R26's day files)
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r25rules as RU  # noqa: E402
import r26lib as L26  # noqa: E402
import r34feat as F  # noqa: E402
from r25data import N, minute  # noqa: E402
from research.brain import bs  # noqa: E402

RESULTS = HERE / "results"
UNDS = ("NIFTY", "SENSEX")
T0 = R.close_row(14, 0)
END = minute(15, 24)                  # realized variance over the bars 14:00 .. 15:24
SCALE_N = 20
MIN_CUT = 10                          # earlier scaled values needed before a regime cut exists
BAND = (15.0, 30.0)
LEG = 1000.0
DRAWS, SEED = 1000, 29
RULES = ("X1", "X2", "X3", "X4")


def atm_iv(day, t: int) -> float:
    s = R.spot(day, t)
    if math.isnan(s):
        return math.nan
    k = R.atm(day, s)
    T = max(F.mins_left(t), 1) / F.YEAR_MIN
    ivs = []
    for side in ("C", "P"):
        a = day.opts.get((k, side))
        if a is None:
            continue
        p, row = R.ltp(a, t)
        if row < t - 4 or not (p > 0):
            continue
        v = float(bs.implied_vol(np.array([p]), np.array([s]), np.array([k]), np.array([T]), np.array([side == "C"]))[0])
        if v == v:
            ivs.append(v)
    return float(np.mean(ivs)) if ivs else math.nan


def gex(day, t: int, sig: float) -> tuple[float, float]:
    """(GEX_short, GEX_net) at row t with every strike's gamma at sig (rows <= t only)."""
    s = R.spot(day, t)
    if math.isnan(s) or not (sig == sig and sig > 0):
        return math.nan, math.nan
    T = max(F.mins_left(t), 1) / F.YEAR_MIN
    ch = L26.chain(day)
    tot = {"C": 0.0, "P": 0.0}
    seen = False
    for side in ("C", "P"):
        ok = ch.present[side][:, t] & np.isfinite(ch.oi[side][:, t])
        if not ok.any():
            continue
        seen = True
        ks = ch.k[side][ok]
        g = bs.greeks(np.full(len(ks), s), ks, np.full(len(ks), T), np.full(len(ks), sig), side == "C")[1]
        tot[side] = float(np.nansum(ch.oi[side][ok, t] * g)) * s * s * 0.01
    if not seen or tot["C"] + tot["P"] <= 0:
        return math.nan, math.nan
    return tot["C"] + tot["P"], tot["C"] - tot["P"]


def move_ratio(day, t: int, sig: float) -> float:
    """Realized variance 14:00 .. 15:24 / the variance the ATM IV implies over the same minutes (hindsight)."""
    r = F.logret(day)[t + 1: END + 1]
    r = r[np.isfinite(r)]
    if len(r) < 30 or not (sig == sig and sig > 0):
        return math.nan
    return float((r ** 2).sum() / (sig * sig * len(r) / F.YEAR_MIN))


def pair(day, t: int) -> list[tuple[int, float, float]]:
    out = []
    for sgn in (1, -1):
        el = R.eligible(day, sgn, t, BAND)
        if el:
            k, p = min(el, key=lambda kp: (abs(kp[1] - 22.5), abs(kp[0] - R.spot(day, t))))
            out.append((sgn, k, p))
    return out


def leg_ivrv(day, t: int, sgn: int, k: float, p: float, ratio: float) -> float:
    vs = F.vol_state(day, t, ratio)
    s = R.spot(day, t)
    T = max(F.mins_left(t), 1) / F.YEAR_MIN
    iv = float(bs.implied_vol(np.array([p]), np.array([s]), np.array([k]), np.array([T]), np.array([sgn > 0]))[0])
    fc = vs["rv_fc"]
    return iv * 100 / fc if iv == iv and fc == fc and fc > 0 else math.nan


def regime_flags(gs: list, gn: list) -> list[dict]:
    """Per expiry (in date order): the scaled GEX (the day's value / the median of the previous 20 expiries' values; the
    net, which changes sign, / the median SIZE of the previous 20 nets) and the regime flags against the expanding 2/3
    and 1/3 quantiles of all EARLIER scaled values (at least MIN_CUT of them). Entry i reads entries < i only."""
    out, hs, hn, cs, cn = [], [], [], [], []
    for x, y in zip(gs, gn):
        f = {"ok": False, "x1": False, "x2": False}
        ps = [v for v in hs[-SCALE_N:] if v == v]
        pn = [v for v in hn[-SCALE_N:] if v == v]
        if x == x and len(ps) >= SCALE_N:
            ss, sn = x / float(np.median(ps)), y / float(np.median(np.abs(pn)))
            f.update(s_short=ss, s_net=sn)
            if len(cs) >= MIN_CUT:
                f.update(ok=True, x1=ss >= float(np.quantile(cs, 2 / 3)), x2=sn <= float(np.quantile(cn, 1 / 3)))
            cs.append(ss)
            cn.append(sn)
        hs.append(x)
        hn.append(y)
        out.append(f)
    return out


def build() -> list[dict]:
    rows = []
    for u in UNDS:
        ds = sorted(D.load(u, "all"), key=lambda d: d.day)
        rat = F.all_ratios(ds)
        j = (T0 - F.DECISIONS[0]) // 5
        meas = []
        for d in ds:
            sig = atm_iv(d, T0)
            meas.append((d, sig, *gex(d, T0, sig)))
        flags = regime_flags([m[2] for m in meas], [m[3] for m in meas])
        for (d, sig, gs, gn), ratio, fl in zip(meas, rat, flags):
            row = {"und": u, "day": d.day, "sig": sig, "gex_short": gs, "gex_net": gn, **fl}
            if row["ok"]:
                row["move"] = move_ratio(d, T0, sig)
                legs = []
                for sgn, k, p in pair(d, T0):
                    x = leg_ivrv(d, T0, sgn, k, p, float(ratio[j]))
                    leg = {"sgn": sgn, "k": k, "p": p, "ivrv": x}
                    for ex in ("E3", "E1"):
                        for mult in (1.0, 2.0):
                            tr = L26.simulate(d, k, sgn, T0, p, ex, LEG, mult)
                            leg[f"{ex}_{mult:g}"] = (tr.net, tr.risked, tr.peak_mult) if tr else None
                    legs.append(leg)
                row["legs"] = legs
            rows.append(row)
    return rows


def day_net(row, ex="E3", mult=1.0, ivrv=None) -> tuple[float, float, int]:
    net = risk = 0.0
    n = 0
    for leg in row.get("legs", []):
        if ivrv is not None and not (leg["ivrv"] == leg["ivrv"] and leg["ivrv"] <= ivrv + 1e-9):
            continue
        x = leg[f"{ex}_{mult:g}"]
        if x is None:
            continue
        net += x[0]
        risk += x[1]
        n += 1
    return net, risk, n


def select(rows, rid):
    if rid == "X1":
        return [r for r in rows if r["ok"] and r["x1"]]
    if rid == "X2":
        return [r for r in rows if r["ok"] and r["x2"]]
    if rid == "X3":
        return [r for r in rows if r["ok"] and r["x1"] and r["x2"]]
    if rid == "X4":
        return [r for r in rows if r["ok"] and r["x1"]]
    return [r for r in rows if r["ok"]]


def stats(sel, rid, ex="E3", mult=1.0) -> dict:
    iv = 1.0 if rid == "X4" else None
    ds = [(r, *day_net(r, ex, mult, iv)) for r in sel]
    ds = [x for x in ds if x[3] > 0]
    ds.sort(key=lambda x: (x[0]["day"], x[0]["und"]))
    nets = np.array([x[1] for x in ds])
    risk = sum(x[2] for x in ds)
    if not len(nets):
        return {"trades": 0, "net": 0.0}
    eq = np.cumsum(nets)
    dd = float(np.min(eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]))
    streak = cur = 0
    for v in nets:
        cur = cur + 1 if v <= 0 else 0
        streak = max(streak, cur)
    peaks = [leg[f"{ex}_1"][2] for x in ds for leg in x[0]["legs"]
             if leg[f"{ex}_1"] and (iv is None or (leg["ivrv"] == leg["ivrv"] and leg["ivrv"] <= iv))]
    out = {"trades": int(len(nets)), "legs": int(sum(x[3] for x in ds)), "net": round(float(nets.sum()), 0),
           "per_1000": round(float(nets.sum()) / risk * 1000, 1) if risk else 0.0, "hit_pct": round(float((nets > 0).mean()) * 100, 1),
           "avg_win": round(float(nets[nets > 0].mean()), 0) if (nets > 0).any() else 0.0,
           "avg_loss": round(float(nets[nets <= 0].mean()), 0) if (nets <= 0).any() else 0.0,
           "worst_day": round(float(nets.min()), 0), "best_day": round(float(nets.max()), 0), "max_dd": round(dd, 0),
           "losing_streak": streak, "wo_best": round(float(nets.sum() - nets.max()), 0),
           "x4_leg_pct": round(100 * float(np.mean([p >= 4 for p in peaks])), 1) if peaks else 0.0,
           "x10_leg_pct": round(100 * float(np.mean([p >= 10 for p in peaks])), 1) if peaks else 0.0,
           "best": [(str(x[0]["day"]), x[0]["und"], round(x[1])) for x in sorted(ds, key=lambda x: -x[1])[:3]]}
    for blk, f in (("2021-22", lambda d: d.year <= 2022), ("2023-26", lambda d: d.year >= 2023)):
        b = [x[1] for x in ds if f(x[0]["day"])]
        out[f"block_{blk}"] = (len(b), round(float(sum(b)), 0))
    out["years"] = {str(y): (sum(1 for x in ds if x[0]["day"].year == y), round(float(sum(x[1] for x in ds if x[0]["day"].year == y)), 0))
                    for y in range(2021, 2027)}
    out["index"] = {u: (sum(1 for x in ds if x[0]["und"] == u), round(float(sum(x[1] for x in ds if x[0]["und"] == u)), 0)) for u in UNDS}
    return out


def control_p(rows, sel, rid, net: float) -> tuple[float, float]:
    """Random same-size draws per index from the eligible pool; p = share with net >= the rule's."""
    rng = np.random.default_rng(SEED)
    pool = {u: [r for r in select(rows, "B0") if r["und"] == u] for u in UNDS}
    cnt = {u: sum(1 for r in sel if r["und"] == u) for u in UNDS}
    iv = 1.0 if rid == "X4" else None
    vals = {u: np.array([day_net(r, "E3", 1.0, iv)[0] for r in pool[u]]) for u in UNDS}
    out = np.zeros(DRAWS)
    for i in range(DRAWS):
        out[i] = sum(rng.choice(vals[u], size=cnt[u], replace=False).sum() for u in UNDS if cnt[u])
    return float((out >= net - 1e-9).mean()), float(out.mean())


def describe(rows) -> dict:
    ok = [r for r in rows if r["ok"] and r.get("move") == r.get("move") and r.get("move") is not None]
    out = {}
    for key in ("s_short", "s_net"):
        x = np.array([r[key] for r in ok])
        mv = np.array([r["move"] for r in ok])
        traded = np.array([day_net(r)[2] > 0 for r in ok])
        pn = np.array([day_net(r)[0] / day_net(r)[1] * 1000 if day_net(r)[1] > 0 else np.nan for r in ok])
        rho, p = spearmanr(x, mv)
        cuts = np.quantile(x, [1 / 3, 2 / 3])
        bins = np.digitize(x, cuts)
        out[key] = {"spearman_move": round(float(rho), 3), "p": round(float(p), 4), "n": int(len(x)),
                    "terciles": {f"T{b + 1}": {"n": int((bins == b).sum()), "move_mean": round(float(mv[bins == b].mean()), 3),
                                               "move_median": round(float(np.median(mv[bins == b])), 3),
                                               "traded_days": int((traded & (bins == b)).sum()),
                                               "pair_per1000_E3": round(float(np.nanmean(pn[bins == b])), 1)} for b in range(3)}}
    mv = np.array([r["move"] for r in ok])
    out["all"] = {"n": int(len(mv)), "move_mean": round(float(mv.mean()), 3), "move_median": round(float(np.median(mv)), 3),
                  "share_move_gt1": round(float((mv > 1).mean()) * 100, 1)}
    return out


def confound(rows) -> dict:
    """Is the GEX-move link an implied-vol effect? Rank correlations and the partial rank correlation given ATM IV."""
    from scipy.stats import rankdata
    ok = [r for r in rows if r["ok"] and r.get("move") == r.get("move") and r.get("move") is not None]
    s = np.array([r["s_short"] for r in ok])
    sig = np.array([r["sig"] for r in ok])
    mv = np.array([r["move"] for r in ok])
    rs, rg, rm = rankdata(s), rankdata(sig), rankdata(mv)

    def res(y, x):
        return y - np.polyval(np.polyfit(x, y, 1), x)
    out = {"n": len(ok), "rho_gex_iv": round(float(spearmanr(s, sig)[0]), 3), "rho_iv_move": round(float(spearmanr(sig, mv)[0]), 3),
           "partial_rho_gex_move_given_iv": round(float(np.corrcoef(res(rs, rg), res(rm, rg))[0, 1]), 3), "by_index": {}}
    for u in UNDS:
        m = [i for i, r in enumerate(ok) if r["und"] == u]
        out["by_index"][u] = round(float(spearmanr(s[m], mv[m])[0]), 3)
    return out


def main():
    rows = build()
    r35 = json.loads((ROOT / "research/hunt/r35/results/results.json").read_text())
    r34 = json.loads((ROOT / "research/hunt/r34/results/final.json").read_text())
    r26 = json.loads((ROOT / "research/hunt/r26/results/results.json").read_text())["bh_family"]
    fam = {k: v["p"] for k, v in r26.items()} | {f"R34-{k}": v["twins"]["p"] for k, v in r34["rules"].items()} | \
          {f"R35-{k}": v["twins"]["p"] for k, v in r35["rules"].items()}
    res = {"B0": {"m": stats(select(rows, "B0"), "B0"), "m_E1": stats(select(rows, "B0"), "B0", "E1")}}
    for rid in RULES:
        sel = select(rows, rid)
        m = stats(sel, rid)
        p, cmean = control_p(rows, sel, rid, m["net"]) if m["trades"] else (1.0, 0.0)
        res[rid] = {"m": m, "m_E1": stats(sel, rid, "E1"), "x2_spread": stats(sel, rid, "E3", 2.0)["net"],
                    "control": {"p": round(p, 4), "mean": round(cmean, 0)}}
        fam[f"R36-{rid}"] = p
        print(rid, m["trades"], m["net"], "p", round(p, 3), flush=True)
    qs = dict(zip(fam, RU.bh(list(fam.values()))))
    for rid in RULES:
        v = res[rid]
        m = v["m"]
        v["q"] = round(qs[f"R36-{rid}"], 4)
        g = {"1 >= 30 trades": m["trades"] >= 30, "2 net > 0": m["net"] > 0,
             "3 blocks > 0": all(m[f"block_{b}"][1] > 0 for b in ("2021-22", "2023-26") if m[f"block_{b}"][0] > 0),
             "4 BH q < 0.05": v["q"] < 0.05, "5 x2 spread > 0": v["x2_spread"] > 0,
             "6 w/o best day > 0": m["wo_best"] > 0, "7 max DD > -40k": m["max_dd"] > -40000}
        v["gates"], v["passes"] = g, all(g.values())
    out = {"bh_size": len(fam), "bh_min_q": round(min(qs.values()), 4), "describe": describe(rows), "confound": confound(rows),
           "eligible": {u: sum(1 for r in rows if r["ok"] and r["und"] == u) for u in UNDS},
           "no_gex": {u: sum(1 for r in rows if r["und"] == u and not r["gex_short"] == r["gex_short"]) for u in UNDS},
           "rules": res}
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps(out["describe"], indent=1))
    print("BH", len(fam), "min q", out["bh_min_q"], "passes", [r for r in RULES if res[r]["passes"]])


if __name__ == "__main__":
    main()
