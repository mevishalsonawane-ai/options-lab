"""R105F (PREREG.md): open-interest checks O1-O4 on R72 (C) and R51 + Supertrend (B) Donchian breaks.

    python research/hunt/r105f_oi/r105f.py design
    python research/hunt/r105f_oi/r105f.py final         # the ONE final read
    R105_EXT=1 python research/hunt/r105f_oi/r105f.py oos  # after final, once

OI per strike and minute comes from the lake's options_rolling_1m WEEK files (ATM-10 .. ATM+10); "OI at m" is the last
print at or before m. Walks, twins, gate: R105B's helpers; candidates and prices: R105's. Pure checks first.
"""
from __future__ import annotations

import json
import os
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
FINAL_FLAG, OOS_FLAG = RESULTS / "FINAL_READ", RESULTS / "OOS_READ"
RULES = ("O1", "O2", "O3", "O4")
NAMES = {"O1": "PCR (ATM+-5): calls only if >= 1.0, puts only if <= 0.8",
         "O2": "WRITERS: OI added since 09:20 (ATM+-2) on the supporting side >= the other side",
         "O3": "OWNOI: skip if the bought option's OI rose > 5% in 15 minutes",
         "O4": "WALL: skip if the biggest OI strike on our side is within 0.3% ahead of the index"}
ARMS = ("C", "B")
NROWS, OPEN_MIN, ROW_0920 = 375, 9 * 60 + 15, 5


# ================================================================================================ pure
def oi_at(series, m: int) -> float:
    """Last finite value at or before row m (NaN if none)."""
    if m < 0:
        return np.nan
    s = np.asarray(series[:m + 1], float)
    f = np.flatnonzero(np.isfinite(s))
    return float(s[f[-1]]) if len(f) else np.nan


def strikes_around(atm: float, step: float, lo: int, hi: int) -> list:
    return [atm + k * step for k in range(lo, hi + 1)]


def o1_pcr(call: dict, put: dict, atm, step, m) -> int:
    """+1 calls only, -1 puts only, 0 both (or unknown)."""
    ks = strikes_around(atm, step, -5, 5)
    c = [oi_at(call[k], m) for k in ks if k in call]
    p = [oi_at(put[k], m) for k in ks if k in put]
    c, p = [x for x in c if np.isfinite(x)], [x for x in p if np.isfinite(x)]
    if not c or not p or sum(c) <= 0:
        return 0
    r = sum(p) / sum(c)
    return 1 if r >= 1.0 else (-1 if r <= 0.8 else 0)


def o2_writers(call: dict, put: dict, atm, step, m, side) -> bool:
    """Keep? A call needs put OI added since 09:20 >= call OI added (ATM+-2); a put the mirror. Unknown keeps."""
    if m < ROW_0920:
        return True
    dc = dp = 0.0
    n = 0
    for k in strikes_around(atm, step, -2, 2):
        if k in call and k in put:
            c0, c1, p0, p1 = oi_at(call[k], ROW_0920), oi_at(call[k], m), oi_at(put[k], ROW_0920), oi_at(put[k], m)
            if all(np.isfinite(x) for x in (c0, c1, p0, p1)):
                dc += c1 - c0
                dp += p1 - p0
                n += 1
    if n == 0:
        return True
    return dp >= dc if side == "C" else dc >= dp


def o3_ownoi(own, m, look: int = 15, rise: float = 0.05) -> bool:
    """Keep unless the bought contract's OI rose by more than `rise` over the last `look` minutes."""
    if own is None or m - look < 0:
        return True
    a, b = oi_at(own, m - look), oi_at(own, m)
    if not (np.isfinite(a) and np.isfinite(b)) or a <= 0:
        return True
    return b / a - 1.0 <= rise


def o4_wall(call: dict, put: dict, atm, step, m, side, spot, near: float = 0.003) -> bool:
    """Keep unless the largest-OI strike on our side (ATM..ATM+5 calls / ATM-5..ATM puts) is within `near` ahead."""
    if not np.isfinite(spot) or spot <= 0:
        return True
    book, ks = (call, strikes_around(atm, step, 0, 5)) if side == "C" else (put, strikes_around(atm, step, -5, 0))
    vals = [(oi_at(book[k], m), k) for k in ks if k in book]
    vals = [(v, k) for v, k in vals if np.isfinite(v)]
    if not vals:
        return True
    _, k = max(vals)
    d = (k - spot) / spot if side == "C" else (spot - k) / spot
    return not (0.0 <= d <= near)


def keep(rule, side, chain, m, own, spot) -> bool:
    call, put, atm, step = chain
    if rule == "O1":
        b = o1_pcr(call, put, atm, step, m)
        return b == 0 or (b > 0 and side == "C") or (b < 0 and side == "P")
    if rule == "O2":
        return o2_writers(call, put, atm, step, m, side)
    if rule == "O3":
        return o3_ownoi(own, m)
    if rule == "O4":
        return o4_wall(call, put, atm, step, m, side, spot)
    raise ValueError(rule)


# ================================================================================================ data
def _mods():
    sys.path.insert(0, str(HERE.parent / "r105_donchian_giveback"))
    sys.path.insert(0, str(HERE.parent / "r105b_direction"))
    import r105study as T
    import r105lib as L5
    import r105blib as LB
    return T, L5, LB


def chain_days(und: str, days: set) -> dict:
    """{day: (call {strike: (375,) OI}, put {...}, atm_by_row (375,))} for the wanted days."""
    import pandas as pd
    lake = Path(os.environ.get("IRAALGO_LAKE", Path.home() / ".cache" / "iraalgo" / "lake"))
    parts = []
    for y in sorted({d.year for d in days}):
        p = lake / f"opt_{und}_WEEK_{y}.parquet"
        if p.exists():
            parts.append(pd.read_parquet(p, columns=["offset", "side", "oi", "strike", "ts"]))
    x = pd.concat(parts, ignore_index=True)
    ts = pd.to_datetime(x.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    x["day"] = ts.dt.date
    x["r"] = (ts.dt.hour * 60 + ts.dt.minute - OPEN_MIN).astype(int)
    x = x[(x.r >= 0) & (x.r < NROWS) & x.day.isin(days)]
    out = {}
    for d, g in x.groupby("day"):
        books = {}
        for s in ("C", "P"):
            gs = g[g.side == s]
            book = defaultdict(lambda: np.full(NROWS, np.nan))
            for k, gk in gs.groupby("strike"):
                a = np.full(NROWS, np.nan)
                a[gk.r.to_numpy()] = gk.oi.to_numpy(float)
                book[float(k)] = a
            books[s] = dict(book)
        atm = np.full(NROWS, np.nan)
        z = g[(g.offset == 0) & (g.side == "C")]
        atm[z.r.to_numpy()] = z.strike.to_numpy(float)
        out[d] = (books["C"], books["P"], atm)
    return out


def units(arm, lo, hi):
    T, L5, LB = _mods()
    K = T.K
    spec = L5.rule_spec(None)
    raw = [(und, D, cs, T.price(und, D, cs, spec)) for und, D, cs in T.day_cands(arm, lo, hi)]
    need = defaultdict(set)
    for und, D, cs, _ in raw:
        need[und].add(D.d)
    for und in need:
        ch = chain_days(und, need[und])
        step = float(K.STEP[und])
        for u, D, cs, _ in raw:
            if u != und:
                continue
            c = ch.get(D.d)
            for cd in cs:
                m = cd["er"] - 1
                if c is None:
                    cd["oi"] = {r: True for r in RULES}
                    continue
                call, put, atm_rows = c
                spot = float(D.idx[3, m]) if np.isfinite(D.idx[3, m]) else np.nan
                atm = oi_at(atm_rows, m)
                if not np.isfinite(atm):
                    atm = round(spot / step) * step if np.isfinite(spot) else np.nan
                if not np.isfinite(atm):
                    cd["oi"] = {r: True for r in RULES}
                    continue
                own = (call if cd["side"] == "C" else put).get(float(cd["strike"]))
                cd["oi"] = {r: keep(r, cd["side"], (call, put, atm, step), m, own, spot) for r in RULES}
    return [((und, D.d), cs, res, "side2") for und, D, cs, res in raw]


def block(arm, lo, hi, phase, twins=True):
    T, L5, LB = _mods()
    u = units(arm, lo, hi)
    base = [[True] * len(cs) for _, cs, _, _ in u]
    b0 = LB.run_keep(u, base)
    if phase in ("design", "final"):
        exp = L5.EXPECT[(arm, phase)]
        if (len(b0), round(sum(t["net"] for t in b0))) != exp:
            raise SystemExit(f"{arm} {phase} baseline does not reproduce {exp}")
    out = dict(base=LB.stats(b0), rules={})
    if phase == "design":
        out["base_q"] = LB.quarters(b0)
        out["base_wf_net"] = round(sum(t["net"] for t in LB.split(b0, LB.WF_START, LB.DESIGN_END)))
    for rule in RULES:
        kp = [[c["oi"][rule] for c in cs] for _, cs, _, _ in u]
        vt = LB.run_keep(u, kp)
        r = dict(stats=LB.stats(vt), dropped=sum(1 for k in kp for x in k if not x))
        if phase == "design":
            r["q"] = LB.quarters(vt)
            r["wf_net"] = round(sum(t["net"] for t in LB.split(vt, LB.WF_START, LB.DESIGN_END)))
        if twins:
            rng = np.random.default_rng(1056 + 7 * ARMS.index(arm) + RULES.index(rule))
            r["p"] = LB.p_of(LB.twin_totals(u, base, kp, LB.TWINS, rng), sum(t["net"] for t in vt))
        out["rules"][rule] = r
        print(phase, arm, rule, r["stats"]["trades"], r["stats"]["win"], r["stats"]["net"], "base", out["base"]["net"],
              "dropped", r["dropped"], "p", r.get("p"), flush=True)
    return out


def _json(o, p):
    RESULTS.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(o, indent=1, default=str) + "\n")


def design():
    T, L5, LB = _mods()
    _json({a: block(a, L5.START, L5.DESIGN_END, "design") for a in ARMS}, RESULTS / "design.json")


def final():
    T, L5, LB = _mods()
    if FINAL_FLAG.exists():
        raise SystemExit("R105F's final period was already read once")
    des = json.loads((RESULTS / "design.json").read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    T.mods()["C"].open_final()
    fin = {a: block(a, L5.FINAL_START, L5.FINAL_END, "final") for a in ARMS}
    sys.path.insert(0, str(HERE.parent / "r105e_optliq"))
    import r105e
    r105e.PRIOR_EXTRA = r105e.PRIOR_EXTRA + ("r105e-",)
    pf = r105e.prior()
    new_p = {f"{a}:{r}": max(des[a]["rules"][r]["p"], fin[a]["rules"][r]["p"]) for a in ARMS for r in RULES}
    q = LB.family_q(new_p, pf)
    tests = {}
    for a in ARMS:
        for r in RULES:
            d, f = des[a]["rules"][r], fin[a]["rules"][r]
            t = dict(design=d["stats"], base_design=des[a]["base"], final=f["stats"], base_final=fin[a]["base"],
                     wf_net=d["wf_net"], base_wf_net=des[a]["base_wf_net"], q=d["q"], base_q=des[a]["base_q"],
                     p_design=d["p"], p_final=f["p"], q_bh=q[f"{a}:{r}"])
            t["gate"] = LB.gate(t)
            tests[f"{a}:{r}"] = t
            print(a, r, "PASS" if t["gate"]["passed"] else "fail", t["gate"]["failed"], flush=True)
    _json(dict(read=FINAL_FLAG.read_text().strip(), family=dict(new=8, prior=len(pf)), arms=fin, tests=tests),
          RESULTS / "final.json")


def oos():
    if os.environ.get("R105_EXT", "0") != "1":
        raise SystemExit("run with R105_EXT=1")
    if not FINAL_FLAG.exists() or OOS_FLAG.exists():
        raise SystemExit("OOS is read once, after the final")
    T, L5, LB = _mods()
    OOS_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    T.mods()["C"].open_final()
    _json({a: block(a, L5.OOS_START, L5.OOS_END, "oos", twins=False) for a in ARMS}, RESULTS / "oos.json")


if __name__ == "__main__":
    {"design": design, "final": final, "oos": oos}.get(sys.argv[1] if len(sys.argv) > 1 else "", lambda: print(__doc__))()
