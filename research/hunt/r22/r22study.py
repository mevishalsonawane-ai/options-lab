#!/usr/bin/env python3
"""R22 event study: the morning's three 15-minute candles as support and resistance (PREREG.md part A3). Research only.

    python3 research/hunt/r22/r22study.py events             # DESIGN sessions -> results/events_design.md|json
    python3 research/hunt/r22/r22study.py events holdout     # the same on the holdout, only after the one holdout read
    R22_SOURCE=lake python3 research/hunt/r22/r22study.py events    # the same on the 6-year Dhan lake (results/lake/)

Data: R17's day cache (research/hunt/r17/data.py) for BANKNIFTY / NIFTY; FINNIFTY index minutes from the release file
(no options). Per-event rows stay in the cache (<cache>/r22), never in git. 13 Oct 2026.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r22cfg as CFG  # noqa: E402  (sets sys.path for R17 / R20 and the data switch)
import r20lib as L20  # noqa: E402  (atr14, daily_hlc)
import r22events as EV  # noqa: E402
import r22levels as LV  # noqa: E402

TOD = [(104, "10:00-11:00"), (194, "11:00-12:30"), (999, "12:30-15:00")]   # by decision row (row 104 = 10:59)
VIXB = [(13.0, "<13"), (16.0, "13-16"), (1e9, ">=16")]
GAP = 0.15                       # gap bucket edge, in ATR14


@dataclass
class Ctx:
    """One session as R22 sees it: index minutes, ATR14, VIX previous close, expiry flag, gap, the morning levels."""
    und: str
    day: date
    i: int
    ix: np.ndarray
    prev_close: float
    atr: float
    vix: float
    expiry: bool | None
    lv: dict

    @property
    def w(self):
        return LV.width_atr(self.lv, self.atr)

    @property
    def gap(self):
        return (float(self.ix[0, 0]) - self.prev_close) / self.atr


# ------------------------------------------------------------------------------------------------ data
def finnifty_days():
    """FINNIFTY index minutes as R17-like days (forward-filled on the 375 grid; sessions with < 300 minutes dropped)."""
    import data as D17
    if CFG.SOURCE == "lake":
        try:
            return D17.load("FINNIFTY")
        except Exception as e:  # noqa: BLE001  (the lake may not carry it; the event study then skips FINNIFTY)
            print("FINNIFTY not available on the lake:", e)
            return []
    p = os.path.join(CFG.OUT, "days_finnifty.pkl")
    if os.path.exists(p):
        with open(p, "rb") as f:
            return pickle.load(f)
    src = os.path.join(str(CFG.K17.CACHE.raw_release), "finnifty_index.parquet")
    if not os.path.exists(src):
        print("FINNIFTY file missing:", src)
        return []
    df = pd.read_parquet(src)
    days = []
    for d, g in df.groupby(pd.to_datetime(df.day).dt.date):
        ix = D17._grid(g, d, ["open", "high", "low", "close"])
        if np.isfinite(ix[:, 3]).sum() < 300:
            continue
        c = pd.Series(ix[:, 3]).ffill().bfill().values
        for j in range(3):
            ix[:, j] = np.where(np.isfinite(ix[:, j]), ix[:, j], c)
        ix[:, 3] = c
        days.append(D17.Day(d, "FINNIFTY", ix))
    os.makedirs(CFG.OUT, exist_ok=True)
    with open(p, "wb") as f:
        pickle.dump(days, f, protocol=4)
    return days


def load_days(und):
    import data as D17
    return finnifty_days() if und == "FINNIFTY" else D17.load(und)


def contexts(und, days, vix_by_day=None):
    """Ctx per session from the 16th on (ATR14 needs 15 earlier sessions)."""
    from research.brain import calendar as CAL
    hlc = [L20.daily_hlc(d.ix) for d in days]
    out = []
    for i in range(15, len(days)):
        d = days[i]
        atr = L20.atr14(hlc, i)
        if not np.isfinite(atr) or atr <= 0:
            continue
        if und == "FINNIFTY":
            exp, vix = None, (vix_by_day or {}).get(d.day, np.nan)
        else:
            exp, vix = CAL.next_expiry(und, d.day)[0] == d.day, d.vix_prev
        out.append(Ctx(und, d.day, i, d.ix, float(days[i - 1].ix[-1, 3]), atr, float(vix), exp, LV.morning_levels(d.ix)))
    return out


def split(und, ctxs, period):
    cut = CFG.holdout_start(und, [c.day for c in ctxs])
    return [c for c in ctxs if (c.day < cut) == (period == "design")]


# ------------------------------------------------------------------------------------------------ events
def baseline(ctxs):
    """Mean up-move (ATR) from each minute over the sessions: {h: array(375)} for h = '15', '30', '60', 'c'."""
    M = np.array([c.ix[:, 3] for c in ctxs])
    A = np.array([c.atr for c in ctxs])[:, None]
    out = {}
    for h, k in (("15", 15), ("30", 30), ("60", 60)):
        v = np.full(375, np.nan)
        v[:375 - k] = np.mean((M[:, k:] - M[:, :-k]) / A, axis=0)
        out[h] = v
    out["c"] = np.mean((M[:, -1:] - M) / A, axis=0)
    return out


def ctx_events(c: Ctx, base: dict) -> list[dict]:
    rows = []
    for twin, lv in (("real", c.lv), ("rnd", LV.mirror(c.lv))):
        for e in EV.day_events(c.ix, lv):
            pe = float(c.ix[e["row"], 3])
            e.update(und=c.und, day=c.day, twin=twin, atr=c.atr, w=c.w, gap=c.gap, vix=c.vix, expiry=c.expiry, pe=pe)
            if e["kind"] != "touch":
                e.update(EV.measure(c.ix, e["row"], pe, e["d"], c.atr))
                for h in ("15", "30", "60", "c"):
                    e[f"base{h}_a"] = e["d"] * base[h][e["row"]]
            rows.append(e)
    return rows


def run_events(period="design"):
    if period != "design" and not os.path.exists(CFG.FLAG):
        sys.exit("holdout events only after the one holdout read (r22trades.py holdout)")
    rows, nsess = [], {}
    vix_by_day = {}
    for und in CFG.EVENT_UNDS:
        days = load_days(und)
        if not days:
            continue
        if und != "FINNIFTY":
            vix_by_day.update({d.day: d.vix_prev for d in days})
        cs = split(und, contexts(und, days, vix_by_day), period)
        if not cs:
            continue
        base = baseline(cs)
        for c in cs:
            rows += ctx_events(c, base)
        nsess[und] = dict(sessions=len(cs), first=str(cs[0].day), last=str(cs[-1].day),
                          median_atr=float(np.median([c.atr for c in cs])), median_w=float(np.median([c.w for c in cs])))
        print(und, period, len(cs), "sessions", flush=True)
    ev = pd.DataFrame(rows)
    os.makedirs(CFG.OUT, exist_ok=True)
    ev.to_pickle(os.path.join(CFG.OUT, f"events_{period}.pkl"))
    with open(os.path.join(CFG.OUT, f"sessions_{period}.json"), "w") as f:
        json.dump(nsess, f)
    return ev, nsess


# ------------------------------------------------------------------------------------------------ tables
MCOLS = ["mv15_a", "mv30_a", "mv60_a", "mvc_a", "mfe60_a", "mae60_a", "mv30", "mvc"]


def tstat(x):
    x = pd.Series(x).dropna()
    return float(x.mean() / (x.std(ddof=1) / np.sqrt(len(x)))) if len(x) > 2 and x.std() > 0 else np.nan


def stats(g: pd.DataFrame) -> dict:
    n = len(g)
    out = {"events": n, "days": int(g.day.nunique()), "flag": "n<30" if n < 30 else ""}
    for c in MCOLS:
        v = g[c].astype(float)
        out[c] = float(v.mean()) if v.notna().any() else np.nan
    for h in ("30", "c"):
        x = (g[f"mv{h}_a"] - g[f"base{h}_a"]).dropna()
        out[f"ex{h}_a"] = float(x.mean()) if len(x) else np.nan
        out[f"t_ex{h}"] = tstat(x)
    x = g.mvc_a.dropna()
    out["win_c"] = float((x > 0).mean()) if len(x) else np.nan
    return out


def tod_bucket(row):
    for lim, name in TOD:
        if row <= lim:
            return name


def vix_bucket(v):
    if not np.isfinite(v):
        return "n/a"
    for lim, name in VIXB:
        if v < lim:
            return name


def gap_bucket(g):
    return "down" if g <= -GAP else "up" if g >= GAP else "flat"


def tag(ev: pd.DataFrame, wcuts) -> pd.DataFrame:
    ev = ev.copy()
    ev["tod"] = ev.row.map(tod_bucket)
    ev["vixb"] = ev.vix.map(vix_bucket)
    ev["gapb"] = ev.gap.map(gap_bucket)
    ev["wb"] = ev.w.map(lambda x: "narrow" if x < wcuts[0] else "normal" if x < wcuts[1] else "wide")
    ev["exp"] = ev.expiry.map(lambda x: "n/a" if x is None or (isinstance(x, float) and np.isnan(x)) else ("expiry" if x else "other"))
    return ev


def width_cuts(period):
    """Design terciles of W = R / ATR14 pooled over the indices (one value per session), frozen with the design table."""
    p = os.path.join(CFG.RESULTS, "events_design.json")
    if period != "design" and os.path.exists(p):
        return json.load(open(p))["width_cuts"]
    ev = pd.read_pickle(os.path.join(CFG.OUT, "events_design.pkl"))
    w = ev[ev.twin == "real"].drop_duplicates(["und", "day"]).w
    return [float(w.quantile(1 / 3)), float(w.quantile(2 / 3))]


def rates(ev: pd.DataFrame, by: list[str]) -> list[dict]:
    """Shares per edge event: reject per touch, hold / fail per break, retest per hold, extension reach per break."""
    out = []
    keys = by + ["twin", "tf"]
    oe = ev[ev.edge.isin(["OH", "OL"])]
    for k, g in oe.groupby(keys, sort=True):
        r = dict(zip(keys, k if isinstance(k, tuple) else (k,)))
        cnt = g.kind.value_counts()
        touch, brk, hold = int(cnt.get("touch", 0)), int(cnt.get("BREAK", 0)), int(cnt.get("HOLD", 0))
        r.update(touches=touch, breaks=brk, holds=hold, fails=int(cnt.get("FAIL", 0)), rejects=int(cnt.get("REJECT", 0)),
                 rt_hold=int(cnt.get("RETEST-HOLD", 0)), rt_fail=int(cnt.get("RETEST-FAIL", 0)))
        r["reject_per_touch"] = r["rejects"] / touch if touch else np.nan
        r["hold_per_break"] = hold / brk if brk else np.nan
        r["retest_per_hold"] = (r["rt_hold"] + r["rt_fail"]) / hold if hold else np.nan
        r["rthold_per_retest"] = r["rt_hold"] / (r["rt_hold"] + r["rt_fail"]) if (r["rt_hold"] + r["rt_fail"]) else np.nan
        b = g[g.kind == "BREAK"]
        for c in ("reach_0.5", "reach_1", "clean_0.5", "clean_1"):
            r[c] = float(b[c].mean()) if c in b and b[c].notna().any() else np.nan
        bh = b.merge(g[g.kind == "HOLD"][["und", "day", "edge", "twin"]], on=["und", "day", "edge", "twin"])
        r["reach_1_if_hold"] = float(bh["reach_1"].mean()) if len(bh) else np.nan
        r["reach_1_if_hold_n"] = int(len(bh))
        out.append(r)
    mid = ev[ev.edge.isin(["OM", "INNER"])]
    for k, g in mid.groupby(by + ["twin", "edge"], sort=True):
        r = dict(zip(by + ["twin", "edge"], k if isinstance(k, tuple) else (k,)))
        t, rj = int((g.kind == "touch").sum()), int(g.kind.str.endswith("REJECT").sum())
        r.update(touches=t, rejects=rj, reject_per_touch=rj / t if t else np.nan)
        out.append(r)
    return out


def summarize(ev: pd.DataFrame, wcuts) -> dict:
    ev = tag(ev, wcuts)
    mv = ev[ev.kind != "touch"]
    T = {"width_cuts": wcuts}

    def tab(df, by):
        return [dict(zip(by, k if isinstance(k, tuple) else (k,)), **stats(g)) for k, g in df.groupby(list(by), sort=True)]

    T["kind"] = tab(mv, ["kind", "tf", "twin"])
    T["kind_edge"] = tab(mv, ["kind", "tf", "edge", "twin"])
    T["rates"] = rates(ev, [])
    T["rates_und"] = rates(ev, ["und"])
    real = mv[mv.twin == "real"]
    T["by"] = {}
    T["rates_by"] = {}
    for name, col in (("index", "und"), ("width", "wb"), ("gap", "gapb"), ("vix", "vixb"), ("expiry", "exp"), ("tod", "tod")):
        T["by"][name] = tab(real, ["kind", "tf", col])
        T["rates_by"][name] = rates(ev[ev.twin == "real"], [col]) if name != "tod" else []
    return T


def fmt(c, v):
    if isinstance(v, str):
        return v
    if v is None or (isinstance(v, float) and not np.isfinite(v)):
        return "-"
    if c in ("events", "days", "touches", "breaks", "holds", "fails", "rejects", "rt_hold", "rt_fail", "reach_1_if_hold_n", "tf"):
        return f"{int(v):,}"
    if c in ("win_c",) or c.endswith("_per_touch") or c.endswith("_per_break") or c.endswith("_per_hold") or \
            c.endswith("_per_retest") or c.startswith("reach_") or c.startswith("clean_"):
        return f"{100 * v:.0f}%"
    if c in ("mv30", "mvc"):
        return f"{v:+.1f}"
    if c.startswith("t_"):
        return f"{v:+.1f}"
    return f"{v:+.3f}"


def md(rows, keys, cols):
    lines = ["| " + " | ".join(keys + list(cols)) + " |", "|" + "---|" * (len(keys) + len(cols))]
    for r in rows:
        lines.append("| " + " | ".join(fmt(k, r.get(k, "")) for k in keys) + " | " +
                     " | ".join(fmt(c, r.get(c, np.nan)) for c in cols) + " |")
    return "\n".join(lines)


MV = ("events", "mv15_a", "mv30_a", "mv60_a", "mvc_a", "ex30_a", "exc_a", "t_exc", "mfe60_a", "mae60_a", "win_c", "mv30", "mvc", "flag")
RT = ("touches", "rejects", "reject_per_touch", "breaks", "holds", "hold_per_break", "rt_hold", "rt_fail", "retest_per_hold",
      "rthold_per_retest", "reach_0.5", "reach_1", "clean_0.5", "clean_1", "reach_1_if_hold", "reach_1_if_hold_n")


def jsonable(o):
    if isinstance(o, dict):
        return {str(k): jsonable(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [jsonable(v) for v in o]
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.floating, float)):
        return None if not np.isfinite(o) else round(float(o), 5)
    if isinstance(o, (np.bool_,)):
        return bool(o)
    if isinstance(o, date):
        return str(o)
    return o


def write_tables(period="design"):
    ev = pd.read_pickle(os.path.join(CFG.OUT, f"events_{period}.pkl"))
    sess = json.load(open(os.path.join(CFG.OUT, f"sessions_{period}.json")))
    wc = width_cuts(period)
    T = summarize(ev, wc)
    T["sessions"] = sess
    os.makedirs(CFG.RESULTS, exist_ok=True)
    json.dump(jsonable(T), open(os.path.join(CFG.RESULTS, f"events_{period}.json"), "w"), indent=1)
    out = [f"# R22 event study, {period} sessions (generated by r22study.py, source {CFG.SOURCE})\n",
           "Sessions: " + "; ".join(f"{u} {v['sessions']} ({v['first']} - {v['last']}, median ATR14 {v['median_atr']:.0f}, "
                                    f"median W {v['median_w']:.2f})" for u, v in sess.items()) + ".\n",
           f"Width terciles (W = range / ATR14, design, pooled): narrow < {wc[0]:.3f} <= normal < {wc[1]:.3f} <= wide.\n",
           "Columns: mv15/30/60/c = move after the decision over 15/30/60 min / to 15:29 in the event's direction (ATR14); "
           "ex30/exc = minus the same-minute baseline; t_exc = t of exc (events treated as independent: descriptive); "
           "mfe60/mae60 = best / worst in 60 min (ATR); win_c = share further on at 15:29; mv30 / mvc in index points. "
           "Directions: REJECT / FAIL / RETEST-FAIL = back into the range (or away from the mid / inner level); BREAK / HOLD "
           "/ RETEST-HOLD = the break's way. twin rnd = levels mirrored around the 10:00 price.\n",
           "## Shares at the OR edges (both edges pooled; rnd = mirror twin)\n", md(T["rates"], ["twin", "tf", "edge"], RT),
           "\n## Shares by index\n", md(T["rates_und"], ["und", "twin", "tf", "edge"], RT),
           "\n## Moves after each event kind (all indices, both edges)\n", md(T["kind"], ["kind", "tf", "twin"], MV),
           "\n## Moves by edge\n", md(T["kind_edge"], ["kind", "tf", "edge", "twin"], MV)]
    for name, col in (("index", "und"), ("width", "wb"), ("gap", "gapb"), ("vix", "vixb"), ("expiry", "exp"), ("tod", "tod")):
        out += [f"\n## Moves by {name} (real levels)\n", md(T["by"][name], ["kind", "tf", col], MV)]
        if T["rates_by"][name]:
            out += [f"\n## Shares by {name} (real levels)\n", md(T["rates_by"][name], [col, "twin", "tf", "edge"], RT)]
    open(os.path.join(CFG.RESULTS, f"events_{period}.md"), "w").write("\n".join(out) + "\n")
    return T


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "events"
    per = sys.argv[2] if len(sys.argv) > 2 else "design"
    if cmd == "events":
        run_events(per)
        write_tables(per)
    elif cmd == "tables":
        write_tables(per)
    else:
        sys.exit(__doc__)
