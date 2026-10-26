"""R105B study (PREREG.md): ten book direction biases (D1-D10) on R72, R51 + Supertrend, Liquidity 15+5 and R52.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                       # R51's clean stores (once)
    python research/hunt/r105b_direction/r105bstudy.py design                   # baselines, 40 tests, twins (design)
    python research/hunt/r105b_direction/r105bstudy.py final                    # the ONE final read, BH, the gate
    R105_EXT=1 python research/hunt/r105_donchian_giveback/r105study.py build_ext   # the store copy to 19 Oct (once)
    R105B_EXT=1 python research/hunt/r105b_direction/r105bstudy.py oos          # after final: 14-19 Oct, once

The arms' candidates are R101's (r101arms.UNITS: priced once on R64's engine with each arm's own exits); a bias only
removes signals before the arm's own walk (R101's method). R51T = R101's R51 with R73's T7 Supertrend agreement (R75's
B); signals and the T7 check are on the index's 5-minute bars, the biases on the index's daily / weekly / 60-minute /
15-minute bars (r105bbias).
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from bisect import bisect_left
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
EXT = os.environ.get("R105B_EXT", "0") == "1"


def _setup():
    for d in ("r101_arms_enhance", "r75"):
        sys.path.insert(0, str(HERE.parent / d))
    import r101cfg  # noqa: F401  (sets R51_CLEAN and the R51 / R64 / R71 / R72 paths)
    import r51cfg as K51
    if EXT:
        import r105blib as L0
        K51.END = L0.OOS_END
        K51.OUT = K51.OUT.parent / "clean_ext19"
    return K51


K51 = _setup()
import r105blib as L  # noqa: E402
import r105bbias as B  # noqa: E402
import r101arms as A  # noqa: E402


# ================================================================================================ bias inputs
_DAILY: dict = {}


def daily(und: str):
    """(dates, o, h, l, c) of the index's daily bars from the lake's index_daily (oldest first)."""
    if und not in _DAILY:
        import pandas as pd
        df = pd.read_parquet(Path(os.environ.get("IRAALGO_LAKE", K51.LAKE)) / "index_daily.parquet")
        df = df[df.symbol == und].copy()
        df["date"] = pd.to_datetime(df.date).dt.date
        df = df.sort_values("date").drop_duplicates("date")
        _DAILY[und] = (list(df.date), df.open.to_numpy(float), df.high.to_numpy(float), df.low.to_numpy(float),
                       df.close.to_numpy(float))
    return _DAILY[und]


_DB: dict = {}


def day_bias(und: str, d) -> dict:
    """{rule: bias} of the day-level rules, from sessions strictly before d (and d's own open for D1 / D10)."""
    k = (und, d)
    if k not in _DB:
        dates, o, h, l, c = daily(und)
        i = bisect_left(dates, d)
        prev = dict(o=o[:i], h=h[:i], l=l[:i], c=c[:i])
        o_today = o[i] if i < len(dates) and dates[i] == d else np.nan
        wk = B.completed_weeks(dates[:i], h[:i], l[:i], c[:i], d)
        _DB[k] = dict(D1=B.d1_gaphl(prev, o_today), D2=B.d2_wk3(wk), D3=B.d3_wk89(wk), D4=B.d4_ddow(prev),
                      D6=B.d6_pday(prev), D7=B.d7_sma200(prev), D8=B.d8_adx30(prev), D10=B.d10_gap25(prev, o_today))
    return _DB[k]


_H60: dict = {}


def h60(und: str):
    """{day: (history (high, low) of the last 120 60-minute bars before the day, today's bars)} from the store."""
    if und not in _H60:
        days = A.mods()["r51data"].load(und)
        hist, out = [], {}
        for d in sorted(days):
            tb = B.h60_bars(days[d].idx)
            out[d] = (list(hist[-120:]), tb)
            hist += [(x[1], x[2]) for x in tb]
        _H60[und] = out
    return _H60[und]


def bias_of(rule: str):
    def f(c):
        und, d = c["und"], c["_day"]
        if rule in B.DAY_RULES:
            return day_bias(und, d)[rule]
        if rule == "D5":
            hb, tb = h60(und)[d]
            return B.d5_h60(hb, tb, c["er"])
        if rule == "D9":
            return B.d9_f15(A.mods()["r51data"].load(und)[d].idx, c["er"])
        raise ValueError(rule)
    return f


# ================================================================================================ arms
_T7: dict = {}


def t7_ok(c) -> bool:
    import r75study as S75
    pos, F = S75.feats(c["und"])
    i = pos.get((c["_day"], c["er"] // 5 - 1))
    return i is not None and bool(S75.P73.trend_ok("T7", F, i, 1 if c["side"] == "C" else -1))


def units(arm: str, lo, hi) -> list:
    src = "R51" if arm == "R51T" else arm
    u = A.UNITS[src](lo, hi)
    for key, cands, res, how in u:
        for c in cands:
            c["und"] = key[0]
            c["_day"] = key[1]
    return u


def base_keep(arm):
    return t7_ok if arm == "R51T" else (lambda c: True)


def check(arm, phase, tr):
    n, net = len(tr), round(sum(t["net"] for t in tr))
    exp = L.EXPECT[(arm, phase)]
    if not ((exp[0] is None or exp[0] == n) and abs(exp[1] - net) <= 1):
        raise SystemExit(f"{arm} {phase} baseline {n} / {net} does not reproduce {exp}")


def block(arm, lo, hi, phase, twins=True):
    u = units(arm, lo, hi)
    bk = L.keeps_for(u, base_keep(arm))
    b0 = L.run_keep(u, bk)
    if phase in ("design", "final"):
        check(arm, phase, b0)
    out = dict(base=L.stats(b0), rules={})
    if phase == "design":
        out["base_q"] = L.quarters(b0)
        out["base_wf_net"] = round(sum(t["net"] for t in L.split(b0, L.WF_START, L.DESIGN_END)))
    for rule in B.RULES:
        vk = L.keeps_for(u, base_keep(arm), bias_of(rule))
        vt = L.run_keep(u, vk)
        dropped = [(g, j) for g in range(len(u)) for j in range(len(u[g][1])) if bk[g][j] and not vk[g][j]]
        # the dropped signals' own baseline result (priced once; did the bias drop losers?)
        dres = [u[g][2][j] for g, j in dropped if u[g][2][j] is not None]
        dnet = [r["net"] for r in dres]
        r = dict(stats=L.stats(vt), dropped=len(dropped), dropped_lost=sum(1 for x in dnet if x < 0),
                 dropped_net=round(sum(dnet)))
        if phase == "design":
            r["q"] = L.quarters(vt)
            r["wf_net"] = round(sum(t["net"] for t in L.split(vt, L.WF_START, L.DESIGN_END)))
        if twins:
            rng = np.random.default_rng(L.SEED + 17 * L.ARMS.index(arm) + B.RULES.index(rule))
            tw = L.twin_totals(u, bk, vk, L.TWINS, rng)
            r["p"] = L.p_of(tw, sum(t["net"] for t in vt))
            r["twin_mean"] = round(float(tw.mean()))
        out["rules"][rule] = r
        print(phase, arm, rule, r["stats"]["trades"], r["stats"]["net"], "base", out["base"]["net"], "p", r.get("p"),
              flush=True)
    return out


def _json(obj, path):
    L.RESULTS.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1, default=str) + "\n")


def design(arms=L.ARMS):
    for arm in arms:
        _json(block(arm, L.START, L.DESIGN_END, "design"), L.RESULTS / f"design_{arm}.json")


def final():
    missing = [a for a in L.ARMS if not (L.RESULTS / f"design_{a}.json").exists()]
    if missing:
        raise SystemExit(f"design results missing for {missing}")
    if L.FINAL_FLAG.exists():
        raise SystemExit("R105B's final period was already read once (results/FINAL_READ)")
    L.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    import common as C
    C.open_final()
    out = dict(read=L.FINAL_FLAG.read_text().strip(), arms={}, tests={})
    for arm in L.ARMS:
        out["arms"][arm] = block(arm, L.FINAL_START, L.FINAL_END, "final")
    prior = L.prior_family()
    des = {a: json.loads((L.RESULTS / f"design_{a}.json").read_text()) for a in L.ARMS}
    new_p = {f"{a}:{r}": max(des[a]["rules"][r]["p"], out["arms"][a]["rules"][r]["p"]) for a in L.ARMS for r in B.RULES}
    q = L.family_q(new_p, prior)
    q_own = dict(zip(new_p, [round(float(x), 4) for x in L.bh(list(new_p.values()))]))
    out["family"] = dict(new=len(new_p), prior=len(prior), prior_p_below_005=sum(1 for _, p in prior if p < 0.05))
    for a in L.ARMS:
        D, F = des[a], out["arms"][a]
        for rule in B.RULES:
            t = f"{a}:{rule}"
            dr, fr = D["rules"][rule], F["rules"][rule]
            r = dict(design=dr["stats"], base_design=D["base"], final=fr["stats"], base_final=F["base"],
                     wf_net=dr["wf_net"], base_wf_net=D["base_wf_net"], q=dr["q"], base_q=D["base_q"],
                     p_design=dr["p"], p_final=fr["p"], q_bh=q[t], q_own40=q_own[t])
            r["gate"] = L.gate(r)
            out["tests"][t] = r
            print(t, "PASS" if r["gate"]["passed"] else "fail", r["gate"]["failed"], flush=True)
    _json(out, L.RESULTS / "final.json")


def oos():
    if not EXT:
        raise SystemExit("run with R105B_EXT=1 (after r105study.py build_ext)")
    if not L.FINAL_FLAG.exists():
        raise SystemExit("the OOS window is read only after the final read")
    if L.OOS_FLAG.exists():
        raise SystemExit("R105B's OOS window was already read once (results/OOS_READ)")
    missing = [u for u in K51.UNDS if not (K51.OUT / f"day_{u}.pkl").exists()]
    if missing:
        raise SystemExit(f"extended stores missing for {missing} (nothing read)")
    L.OOS_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    import common as C
    C.open_final()
    out = dict(arms={})
    for arm in L.ARMS:
        out["arms"][arm] = block(arm, L.OOS_START, L.OOS_END, "oos", twins=False)
    _json(out, L.RESULTS / "oos.json")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design(tuple(sys.argv[2:]) or L.ARMS)
    elif cmd == "final":
        final()
    elif cmd == "oos":
        oos()
    else:
        print(__doc__)
