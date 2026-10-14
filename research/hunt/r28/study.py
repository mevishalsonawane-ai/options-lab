#!/usr/bin/env python3
"""R28 Step 3 (PREREG.md): the 10 pre-registered GEX filters on the arms' lake rebuilds.

    python3 research/hunt/r28/study.py design     # design + walk-forward + twins + BH -> results/design.json, design.md
    python3 research/hunt/r28/study.py holdout    # the ONE holdout read -> results/holdout.json, holdout.md (refuses twice)

Inputs (r28gex.py, r28arms.py): <OUT>/gex_<UND>.parquet and <OUT>/trades_<arm>.parquet. Every filter is a SKIP rule on
its arm's unfiltered trades. A trade whose GEX value is missing is kept (the filter cannot act) and counted.
"""
from __future__ import annotations

import hashlib
import json
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable

import numpy as np
import pandas as pd

import r28cfg as K

FLAG = K.OUT / "holdout_read.flag"
HOLDOUT_JSON = K.RESULTS / "holdout.json"
GEX_COLS = ["near_net_cr", "near_norm", "near_s2_cr", "near_spot_above_flip"]


@dataclass(frozen=True)
class Filter:
    id: str
    arm: str
    family: str                       # 'A' or 'H'
    col: str
    keep: Callable                    # value -> keep? (value finite)
    text: str


FILTERS = [
    Filter("G1", "orb", "A", "near_net_cr", lambda v: v < 0, "ORB: keep only S1 at 10:00 < 0"),
    Filter("G2", "orb", "A", "near_norm", lambda v: not v > 1, "ORB: skip gex_norm at 10:00 > +1"),
    Filter("G3", "orb", "A", "near_s2_cr", lambda v: v < 0, "ORB: keep only S2 (customer net buying) at 10:00 < 0"),
    Filter("G4", "orb", "A", "near_spot_above_flip", lambda v: v == 0, "ORB: keep only spot below the flip at 10:00"),
    Filter("G5", "fade", "A", "near_net_cr", lambda v: v > 0, "Range Fade: keep only S1 at 10:00 > 0"),
    Filter("G6", "fade", "A", "near_norm", lambda v: not v < -1, "Range Fade: skip gex_norm at 10:00 < -1"),
    Filter("G7", "liq", "A", "near_norm", lambda v: not v > 1, "Liquidity 15+5 BN: skip gex_norm > +1 at the signal"),
    Filter("G8", "h0", "H", "near_net_cr", lambda v: v < 0, "Hero H0: keep only S1 < 0 at 13:45 (or the signal)"),
    Filter("G9", "l3", "H", "near_net_cr", lambda v: v < 0, "Lottery L3 (14:30): keep only S1 at 13:45 < 0"),
    Filter("G10", "night", "A", "near_net_cr", lambda v: v < 0, "Night R3: keep only S1 at 15:15 < 0"),
]
assert len(FILTERS) == 10


# ------------------------------------------------------------------------------------------------ data
_GEX: dict = {}


def gex_at(und: str) -> pd.DataFrame:
    if und not in _GEX:
        g = pd.read_parquet(K.OUT / f"gex_{und}.parquet", columns=["day", "minute"] + GEX_COLS)
        _GEX[und] = g.set_index(["day", "minute"])
    return _GEX[und]


def trades(arm: str) -> pd.DataFrame:
    t = pd.read_parquet(K.OUT / f"trades_{arm}.parquet")
    t["day"] = pd.to_datetime(t.day).dt.date
    parts = []
    for u, g in t.groupby("und"):
        x = gex_at(u).reindex(pd.MultiIndex.from_arrays([g.day.values, g.row.values]))
        parts.append(g.assign(**{c: x[c].values for c in GEX_COLS}))
    return pd.concat(parts).sort_values(["day", "und"]).reset_index(drop=True)


def sessions(arm: str) -> dict:
    """{und: [days]}: the arm's sessions that also have the lake's options (a GEX table day)."""
    s = json.load(open(K.OUT / f"sessions_{arm}.json"))
    out = {}
    for u, ds in s.items():
        have = set(gex_at(u).index.get_level_values(0).unique())
        out[u] = sorted(d for d in (pd.Timestamp(x).date() for x in ds) if d in have)
    return out


def part_of(fam: str):
    return K.part_a if fam == "A" else K.part_h


def wf_of(fam: str):
    return K.wf_a if fam == "A" else K.wf_h


# ------------------------------------------------------------------------------------------------ metrics
def kept_mask(f: Filter, t: pd.DataFrame) -> np.ndarray:
    v = t[f.col].values.astype(float)
    k = np.array([True if not np.isfinite(x) else bool(f.keep(x)) for x in v])
    return k


def daily(t: pd.DataFrame, days: list, col="net") -> np.ndarray:
    s = t.groupby("day")[col].sum()
    return np.array([s.get(d, 0.0) for d in days])


def max_dd(x) -> float:
    c = np.cumsum(x)
    return float((c - np.maximum.accumulate(np.r_[0.0, c])[1:]).min()) if len(c) else 0.0


def metrics(t: pd.DataFrame, days: list, big_cut: float | None = None, skipped: pd.DataFrame | None = None) -> dict:
    d = daily(t, days)
    td = sorted(set(t.day))
    tdv = daily(t, td)
    w, lo = t.net[t.net > 0], t.net[t.net <= 0]
    out = dict(trades=int(len(t)), sessions=len(days), trade_days=len(td), net=float(t.net.sum()),
               per_day=float(d.mean()) if len(d) else 0.0, win_pct=float((t.net > 0).mean() * 100) if len(t) else None,
               avg_win=float(w.mean()) if len(w) else None, avg_loss=float(lo.mean()) if len(lo) else None,
               green_days_pct=float((tdv > 0).mean() * 100) if len(td) else None,
               worst_day=float(d.min()) if len(d) else 0.0, max_dd=max_dd(d))
    if big_cut is not None and skipped is not None:
        out["big_winners_cut"] = int((skipped.net >= big_cut).sum())
        out["big_winners_cut_rs"] = float(skipped.net[skipped.net >= big_cut].sum())
    return out


def twin_p(t: pd.DataFrame, keep: np.ndarray, days: list, n=K.TWINS, seed=K.SEED, tag="") -> tuple[float, float]:
    """Random-filter twins: skip the same number of (index, day) units at random among the units with trades.
    Returns (p, the twins' mean change in P&L/day). The filter's own change is its real skipped trades' P&L. When the
    filter skips only some trades of a unit (Liquidity reads GEX per trade), the twins skip the same number of single
    trades instead (correction of 14 Oct 2026, review: the unit version compared a different number with the twins)."""
    real = -float(t.net.values[~keep].sum()) / len(days)
    unit = list(zip(t.und, t.day))
    net_u = pd.Series(t.net.values).groupby(pd.Index(unit)).sum()
    skip_u = pd.Series(~keep).groupby(pd.Index(unit)).any()
    all_u = pd.Series(~keep).groupby(pd.Index(unit)).all()
    partial = bool((skip_u & ~all_u).any())
    v = t.net.values if partial else net_u.values
    k = int((~keep).sum()) if partial else int(skip_u.sum())
    if k == 0 or k == len(v):
        return np.nan, real
    rng = np.random.default_rng([K.SEED, int(hashlib.md5(tag.encode()).hexdigest()[:8], 16)])
    tw = np.array([-v[rng.choice(len(v), k, replace=False)].sum() / len(days) for _ in range(n)])
    return float((1 + (tw >= real - 1e-9).sum()) / (n + 1)), float(tw.mean())


def cmd_correct():
    """Post-hoc correction (14 Oct 2026, after the review): the corrected twin p of every filter in design and in the
    already-read holdout, and the BH q again. It reads no new period and changes no gate input except the twin p; the
    original design.json / holdout.json stay as written. -> results/correction.json"""
    des = json.load(open(K.RESULTS / "design.json"))
    out, ps = {}, {}
    for f in FILTERS:
        t = trades(f.arm)
        r = {}
        for part in ("design", "holdout"):
            dd = period_days(f.arm, f.family, part)
            x = subset(t, dd)
            keep = kept_mask(f, x)
            p, m = twin_p(x, keep, dd, tag=f.id + str(len(dd)))
            r[part] = dict(twin_p=p, twin_mean_delta=m, delta_day=-float(x.net.values[~keep].sum()) / len(dd),
                           old_twin_p=des["filters"][f.id]["design"]["twin_p"] if part == "design" else None)
        out[f.id] = r
        ps[f.id] = r["design"]["twin_p"]
    q_own = bh(ps)
    jf = joined_family()
    q_join = bh(dict(jf["p"], **{f"R28 {k}": v for k, v in ps.items()}))
    for k in out:
        out[k]["q_r28"], out[k]["q_joined"] = q_own.get(k), q_join.get(f"R28 {k}")
        print(k, out[k])
    json.dump(_js(out), open(K.RESULTS / "correction.json", "w"), indent=1)


def evaluate(f: Filter, t: pd.DataFrame, days: list, twins: bool = True) -> dict:
    keep = kept_mask(f, t)
    kt, st = t[keep], t[~keep]
    big = float(t.net.quantile(0.95)) if len(t) else None
    base, kept = metrics(t, days), metrics(kt, days, big, st)
    d_day = kept["per_day"] - base["per_day"]
    out = dict(base=base, kept=kept, kept_n=int(keep.sum()), skipped_n=int((~keep).sum()),
               missing_gex=int((~np.isfinite(t[f.col].values.astype(float))).sum()), delta_day=d_day)
    x2 = t.net_x2.values.astype(float)
    if len(t) and np.isfinite(x2).all():
        out["delta_day_x2"] = float((x2[keep].sum() - x2.sum()) / len(days))
    else:
        out["delta_day_x2"] = None
    if twins:
        out["twin_p"], out["twin_mean_delta"] = twin_p(t, keep, days, tag=f.id + str(len(days)))
    return out


# ------------------------------------------------------------------------------------------------ BH
def bh(p: dict) -> dict:
    keys = [k for k, v in p.items() if v is not None and np.isfinite(v)]
    ps = np.array([p[k] for k in keys])
    m = len(ps)
    o = np.argsort(ps)
    q = np.empty(m)
    q[o] = ps[o] * m / (np.arange(m) + 1)
    q[o] = np.minimum.accumulate(q[o][::-1])[::-1]
    return {k: float(min(v, 1.0)) for k, v in zip(keys, q)}


R9_KNOWN = {"R9 C-filter ORB Fresh B": 0.058, "R9 C-filter ORB D": 0.061, "R9 C-filter Liq BN B": 0.12,
            "R9 C-filter Liq BN A": 0.88, "R9 C-filter Liq BN D": 0.83, "R9 C-filter Liq MIDCP D": 0.30}


def joined_family() -> dict:
    """Earlier tests R28 joins (PREREG.md gate 2). R9's 40 gamma predictive + 28 gamma filters: the 6 printed design
    p-values, the rest entered as 1.0 (never lowers any q). Arm families' holdout p-values from their results files."""
    fam = dict(R9_KNOWN)
    for i in range(28 - len(R9_KNOWN)):
        fam[f"R9 C-filter other {i}"] = 1.0
    for i in range(40):
        fam[f"R9 C-predict {i}"] = 1.0
    src = {"r22": K.ROOT / "research/hunt/r22/results/holdout.json",
           "r25": K.ROOT / "research/hunt/r25/results/holdout.json",
           "r27": K.ROOT / "research/hunt/r27/results/holdout.json"}
    got = {}
    for k, p in src.items():
        if not p.exists():
            got[k] = 0
            continue
        j = json.load(open(p))
        if k == "r22":
            items = {f"R22fam {a}": v["p"] for a, v in j["_bh"]["table"].items()}
        elif k == "r25":
            items = {f"R25 {a}": v["p"] for a, v in j["bh_family"].items()}
        else:
            items = {f"R27 {a}": v["p"] for a, v in j["pooled"].items() if isinstance(v, dict) and "p" in v}
        fam.update(items)
        got[k] = len(items)
    return dict(p=fam, sources=got)


# ------------------------------------------------------------------------------------------------ runs
def period_days(arm: str, fam: str, part: str, wf: str | None = None) -> list:
    pf, wff = part_of(fam), wf_of(fam)
    out = set()
    for u, ds in sessions(arm).items():
        out |= {d for d in ds if pf(d) == part and (wf is None or wff(d) == wf)}
    return sorted(out)


def subset(t: pd.DataFrame, days: list) -> pd.DataFrame:
    s = set(days)
    return t[t.day.isin(s)]


def prereg_sha() -> str:
    return hashlib.sha256((K.HERE / "PREREG.md").read_bytes()).hexdigest()


def cmd_design():
    res, ps = {}, {}
    for f in FILTERS:
        t = trades(f.arm)
        dd = period_days(f.arm, f.family, "design")
        r = dict(text=f.text, arm=f.arm, family=f.family, design=evaluate(f, subset(t, dd), dd))
        wf = {}
        for y in (K.A_WF if f.family == "A" else K.H_WF):
            yd = period_days(f.arm, f.family, "design", str(y))
            if yd:
                wf[str(y)] = evaluate(f, subset(t, yd), yd, twins=False)
        r["wf"] = wf
        res[f.id] = r
        ps[f.id] = r["design"]["twin_p"]
        print(f.id, f.arm, "design days", len(dd), "trades", r["design"]["base"]["trades"], "kept", r["design"]["kept_n"],
              "delta/day", round(r["design"]["delta_day"], 1), "p", r["design"]["twin_p"], flush=True)
    q_own = bh(ps)
    jf = joined_family()
    q_join = bh(dict(jf["p"], **{f"R28 {k}": v for k, v in ps.items()}))
    for f in FILTERS:
        r = res[f.id]
        r["q_r28"] = q_own.get(f.id)
        r["q_joined"] = q_join.get(f"R28 {f.id}")
        d = r["design"]
        years_better = sum(v["delta_day"] > 0 for v in r["wf"].values())
        need_y = 3 if f.family == "A" else 2
        need_n = 100 if f.family == "A" else 30
        r["gates"] = dict(g1=bool(d["delta_day"] > 0 and (d["twin_p"] or 1) < 0.05),
                          g2=bool(r["q_joined"] is not None and r["q_joined"] < K.BH_Q),
                          g3=bool(years_better >= need_y), years_better=f"{years_better}/{len(r['wf'])}",
                          g4=bool(d["kept"]["trades"] >= need_n),
                          g6=bool(d["delta_day_x2"] is None or d["delta_day_x2"] > 0))
        r["design_pass"] = all(r["gates"][g] for g in ("g1", "g2", "g3", "g4", "g6"))
    out = dict(prereg_sha256=prereg_sha(), written_utc=datetime.now(timezone.utc).isoformat(timespec="seconds"),
               joined_family=dict(m=len(jf["p"]) + len(ps), sources=jf["sources"]), filters=res)
    K.RESULTS.mkdir(exist_ok=True)
    json.dump(_js(out), open(K.RESULTS / "design.json", "w"), indent=1)
    (K.RESULTS / "design.md").write_text(md_design(out))
    print("design passes:", [k for k, v in res.items() if v["design_pass"]])


def cmd_holdout():
    if FLAG.exists() or HOLDOUT_JSON.exists():
        sys.exit("refused: the R28 holdout has already been read (flag or results/holdout.json exists)")
    des = json.load(open(K.RESULTS / "design.json"))
    if des["prereg_sha256"] != prereg_sha():
        sys.exit("refused: PREREG.md changed since the design run")
    K.OUT.mkdir(parents=True, exist_ok=True)
    FLAG.write_text(datetime.now(timezone.utc).isoformat())
    res = {}
    for f in FILTERS:
        t = trades(f.arm)
        hd = period_days(f.arm, f.family, "holdout")
        h = evaluate(f, subset(t, hd), hd)
        d = des["filters"][f.id]
        ok = d["design_pass"] and h["delta_day"] > 0 and (h["twin_p"] or 1) < 0.10
        res[f.id] = dict(text=f.text, holdout=h, design_pass=d["design_pass"],
                         g5=bool(h["delta_day"] > 0 and (h["twin_p"] or 1) < 0.10), passed=bool(ok),
                         paper_eligible=bool(ok and h["kept"]["per_day"] > 0),
                         period=[str(hd[0]), str(hd[-1])] if hd else None)
        print(f.id, "holdout days", len(hd), "trades", h["base"]["trades"], "kept", h["kept_n"],
              "delta/day", round(h["delta_day"], 1), "p", h["twin_p"], flush=True)
    out = dict(read_at_utc=FLAG.read_text(), prereg_sha256=prereg_sha(), filters=res,
               passed=[k for k, v in res.items() if v["passed"]])
    json.dump(_js(out), open(HOLDOUT_JSON, "w"), indent=1)
    (K.RESULTS / "holdout.md").write_text(md_holdout(out, des))


# ------------------------------------------------------------------------------------------------ output
def _js(o):
    if isinstance(o, dict):
        return {str(k): _js(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [_js(v) for v in o]
    if isinstance(o, (np.floating, float)):
        return None if not np.isfinite(o) else round(float(o), 4)
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.bool_,)):
        return bool(o)
    return o


def _r(x, nd=0):
    return "-" if x is None else f"{x:,.{nd}f}"


ROW_HDR = ("| filter | period | trades base / kept | win % base / kept | avg win / loss kept | Rs/day base / kept | "
           "change Rs/day | % green days base / kept | worst day kept | max DD base / kept | big winners cut | "
           "missing GEX | sessions | twin p |")


def _row(fid, period, e):
    b, k = e["base"], e["kept"]
    return (f"| {fid} | {period} | {b['trades']} / {k['trades']} | {_r(b['win_pct'], 1)} / {_r(k['win_pct'], 1)} | "
            f"{_r(k['avg_win'])} / {_r(k['avg_loss'])} | {_r(b['per_day'], 1)} / {_r(k['per_day'], 1)} | "
            f"{_r(e['delta_day'], 1)} | {_r(b['green_days_pct'], 1)} / {_r(k['green_days_pct'], 1)} | "
            f"{_r(k['worst_day'])} | {_r(b['max_dd'])} / {_r(k['max_dd'])} | {k.get('big_winners_cut', '-')} | "
            f"{e['missing_gex']} | {b['sessions']} | {_r(e.get('twin_p'), 3)} |")


def md_design(out) -> str:
    L = ["# R28 Step 3: design and walk-forward (generated by study.py)", "",
         f"PREREG sha256 `{out['prereg_sha256'][:16]}`. Joined BH family m = {out['joined_family']['m']} "
         f"(sources {out['joined_family']['sources']}).", "",
         "| filter | rule | design change Rs/day | twin p | q R28 | q joined | years better | kept trades | x2 cost change | design pass |",
         "|---|---|---|---|---|---|---|---|---|---|"]
    for k, r in out["filters"].items():
        d, g = r["design"], r["gates"]
        L.append(f"| {k} | {r['text']} | {_r(d['delta_day'], 1)} | {_r(d['twin_p'], 3)} | {_r(r['q_r28'], 3)} | "
                 f"{_r(r['q_joined'], 3)} | {g['years_better']} | {d['kept']['trades']} | {_r(d['delta_day_x2'], 1)} | "
                 f"{'yes' if r['design_pass'] else 'no'} |")
    L += ["", "## Every filter, design and each walk-forward year", "", ROW_HDR, "|" + "---|" * 14]
    for k, r in out["filters"].items():
        L.append(_row(k, "design", r["design"]))
        for y, e in r["wf"].items():
            L.append(_row(k, y, dict(e, twin_p=None)))
    return "\n".join(L) + "\n"


def md_holdout(out, des) -> str:
    L = ["# R28 holdout (read once; generated by study.py)", "", f"Read at {out['read_at_utc']} UTC. Passed: "
         f"{out['passed'] or 'none'}.", "", ROW_HDR, "|" + "---|" * 14]
    for k, r in out["filters"].items():
        L.append(_row(k, f"holdout {r['period'][0] if r['period'] else ''}..", r["holdout"]))
    L += ["", "| filter | design pass | holdout gate (change > 0 and twin p < 0.10) | passed | paper eligible |",
          "|---|---|---|---|---|"]
    for k, r in out["filters"].items():
        L.append(f"| {k} | {r['design_pass']} | {r['g5']} | {r['passed']} | {r['paper_eligible']} |")
    return "\n".join(L) + "\n"


if __name__ == "__main__":
    {"design": cmd_design, "holdout": cmd_holdout, "correct": cmd_correct}[sys.argv[1]]()
