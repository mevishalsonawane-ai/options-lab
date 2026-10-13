#!/usr/bin/env python3
"""R20c: what happens after a support / resistance break is confirmed by N candle closes (PREREG.md). Research only.

    python3 research/hunt/r20c/study.py events             # part A2 event study, DESIGN sessions -> results/events_design.json
    python3 research/hunt/r20c/study.py tables             # the design tables as markdown (results/events_design.md)
    (trading test commands: trades.py, after part B is committed)

Data: R17's day cache (research/hunt/r17/data.py) for BANKNIFTY / NIFTY; FINNIFTY index minutes straight from the release
file (no options). Outputs that hold minute data or per-event rows stay in the cache (<cache>/r20c), never in git.
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

HERE = os.path.dirname(os.path.abspath(__file__))
R17 = os.path.abspath(os.path.join(HERE, "..", "r17"))
for p in (R17, HERE):
    if p not in sys.path:
        sys.path.insert(0, p)

import config as K17  # noqa: E402  (R17: paths, SOURCE, holdout cuts; sets sys.path to the repo)
import events as EV  # noqa: E402
import levels as LV  # noqa: E402

RESULTS = os.path.join(HERE, "results")
OUT = os.path.join(str(K17.CACHE.root), "r20c")
FLAG = os.path.join(OUT, "HOLDOUT_READ")
HOLDOUT = {"BANKNIFTY": date(2025, 8, 25), "NIFTY": date(2025, 10, 13), "FINNIFTY": date(2025, 10, 13)}
UNDS = ("BANKNIFTY", "NIFTY", "FINNIFTY")
TFS = (5, 15)
NS = (1, 2, 3)
TYPES = ("PDHL", "PIV", "LIQ", "OI", "OR")
TWIN_TYPES = ("PDHL", "PIV", "OI")           # part A2: the random-level rows (daily levels mirrored around the open)
SEED = 2026
TOD = [(74, "09:15-10:30"), (164, "10:30-12:00"), (254, "12:00-13:30"), (999, "13:30-15:15")]
VIXB = [(13.0, "<13"), (16.0, "13-16"), (1e9, ">=16")]


@dataclass
class Ctx:
    """One session as the event study sees it (everything known before 09:15 except ix)."""
    und: str
    day: date
    i: int
    ix: np.ndarray
    prev_close: float
    atr: float
    vix: float
    expiry: bool | None
    levels: dict                  # type -> [(price, side, key, known_bar)] for the daily types (PDHL, PIV, OI)
    hist: dict                    # tf -> stacked history candles for LIQ


# ------------------------------------------------------------------------------------------------ data
def finnifty_days():
    """FINNIFTY index minutes as R17-like days (ix forward-filled on the 375 grid; sessions with < 300 minutes dropped)."""
    import data as D17
    p = os.path.join(OUT, "days_finnifty.pkl")
    if os.path.exists(p):
        with open(p, "rb") as f:
            return pickle.load(f)
    df = pd.read_parquet(os.path.join(str(K17.CACHE.raw_release), "finnifty_index.parquet"))
    df = df[df.right.astype(str).str.upper().isin(["IX", "INDEX", "NONE", ""]) | df.right.isna()] if "right" in df else df
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
    os.makedirs(OUT, exist_ok=True)
    with open(p, "wb") as f:
        pickle.dump(days, f, protocol=4)
    return days


def load_days(und):
    import data as D17
    return finnifty_days() if und == "FINNIFTY" else D17.load(und)


def contexts(und, days, vix_by_day=None):
    """Ctx per session from the 16th on (ATR14 needs 15 earlier sessions)."""
    import oi as OI
    from research.brain import calendar as CAL
    out = []
    for i in range(15, len(days)):
        d, prev = days[i], days[i - 1]
        lv = {"PDHL": LV.pdhl(prev.ix), "PIV": LV.piv(prev.ix), "OI": []}
        if und != "FINNIFTY":
            lv["OI"] = LV.oi_levels(OI.walls(OI.load(und, prev.day), d.day))
            exp = CAL.next_expiry(und, d.day)[0] == d.day
            vix = d.vix_prev
        else:
            exp = None
            vix = (vix_by_day or {}).get(d.day, np.nan)
        out.append(Ctx(und, d.day, i, d.ix, float(prev.ix[-1, 3]), LV.atr14(days, i), float(vix), exp, lv,
                       {tf: LV.liq_history(days, i, tf) for tf in TFS}))
    return out


# ------------------------------------------------------------------------------------------------ events
def level_fn(ctx, typ, tf, liq=None, twin_signs=None):
    """levels_at(b) for one level type on one day (twin_signs: mirror daily levels around the 09:15 open)."""
    if typ == "LIQ":
        return lambda b: liq.active(b)
    if typ == "OR":
        lv = LV.or_edges(ctx.ix, tf)
    else:
        lv = ctx.levels[typ]
    if twin_signs is not None:
        o = float(ctx.ix[0, 0])
        lv = [(LV.mirror(p, o, twin_signs[k]), s, k, kb) for p, s, k, kb in lv]
    return lambda b: [x for x in lv if b >= x[3]]


def day_events(ctx, base, rng=None):
    """Every (type, tf, N, side) first confirmed break of one session, with its measurements."""
    rows = []
    for tf in TFS:
        cd = LV.candles(ctx.ix, tf)
        C, O = cd[:, 3], cd[:, 0]
        liq = LV.LiqMap(ctx.hist[tf], ctx.ix, tf)
        variants = [(t, None) for t in TYPES]
        if rng is not None:
            for t in TWIN_TYPES:
                variants.append((t + "_rnd", {x[2]: int(rng.choice([-1, 1])) for x in ctx.levels[t]}))
        for typ, signs in variants:
            fn = level_fn(ctx, typ.replace("_rnd", ""), tf, liq, signs)
            for n in NS:
                for side in (1, -1):
                    ev = EV.first_break(C, O, ctx.prev_close, side, fn, n, tf)
                    if ev is None:
                        continue
                    row = (ev["e"] + 1) * tf - 1
                    pe = float(C[ev["e"]])
                    r = dict(und=ctx.und, day=ctx.day, tf=tf, n=n, type=typ, side=side, key=str(ev["key"]), b=ev["b"],
                             e=ev["e"], row=row, level=ev["level"], pe=pe, gap=ev["gap"], atr=ctx.atr, vix=ctx.vix,
                             expiry=ctx.expiry, wait=side * (pe - float(C[ev["b"]])), dist_open=side * (ev["level"] - ctx.ix[0, 0]))
                    r["wait_a"] = r["wait"] / ctx.atr
                    r.update(EV.measure(ctx.ix, row, pe, side, ev["level"], ctx.atr))
                    for h in ("15", "30", "60", "c"):
                        r[f"base{h}_a"] = side * base[h][row]
                    rows.append(r)
    return rows


def baseline(ctxs):
    """Mean forward move (in ATR) from each minute over the given sessions: {h: array(375)} (h = '15', '30', '60', 'c')."""
    M = np.array([c.ix[:, 3] for c in ctxs])
    A = np.array([c.atr for c in ctxs])[:, None]
    out = {}
    for h, k in (("15", 15), ("30", 30), ("60", 60)):
        v = np.full(375, np.nan)
        v[:375 - k] = np.mean((M[:, k:] - M[:, :-k]) / A, axis=0)
        out[h] = v
    out["c"] = np.mean((M[:, -1:] - M) / A, axis=0)
    return out


def run_events(period="design"):
    if period != "design" and not os.path.exists(FLAG):
        sys.exit("holdout events only after the one holdout read (trades.py holdout)")
    rows = []
    vix_by_day = {}
    for und in UNDS:
        days = load_days(und)
        if und != "FINNIFTY":
            vix_by_day.update({d.day: d.vix_prev for d in days})
        cs = contexts(und, days, vix_by_day)
        cs = [c for c in cs if (c.day < HOLDOUT[und]) == (period == "design")]
        base = baseline(cs)
        rng = np.random.default_rng([SEED, UNDS.index(und)])
        for c in cs:
            rows += day_events(c, base, rng)
        print(und, period, len(cs), "sessions", flush=True)
    ev = pd.DataFrame(rows)
    os.makedirs(OUT, exist_ok=True)
    ev.to_pickle(os.path.join(OUT, f"events_{period}.pkl"))
    return ev


# ------------------------------------------------------------------------------------------------ tables
COLS = ["mv15_a", "mv30_a", "mv60_a", "mvc_a", "fake30", "mfe60_a", "mae60_a", "mfec_a", "maec_a", "wait_a",
        "mv30", "mvc", "mfe60", "mae60", "wait"]


def stats(g: pd.DataFrame) -> dict:
    n = len(g)
    out = {"n": n, "days": int(g.day.nunique()), "flag": "n<30" if n < 30 else ""}
    for c in COLS:
        v = g[c].astype(float)
        out[c] = float(v.mean()) if v.notna().any() else np.nan
        out[c + "_n"] = int(v.notna().sum())
    for h in ("30", "c"):
        x = (g[f"mv{h}_a"] - g[f"base{h}_a"]).dropna()
        out[f"ex{h}_a"] = float(x.mean()) if len(x) else np.nan
        out[f"t_ex{h}"] = float(x.mean() / (x.std(ddof=1) / np.sqrt(len(x)))) if len(x) > 2 and x.std() > 0 else np.nan
    x = g.mvc_a.dropna()
    out["t_mvc"] = float(x.mean() / (x.std(ddof=1) / np.sqrt(len(x)))) if len(x) > 2 and x.std() > 0 else np.nan
    out["med_mvc_a"] = float(x.median()) if len(x) else np.nan
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


def summarize(ev: pd.DataFrame) -> dict:
    ev = ev.copy()
    ev["tod"] = ev.row.map(tod_bucket)
    ev["vixb"] = ev.vix.map(vix_bucket)
    ev["exp"] = ev.expiry.map(lambda x: "n/a" if x is None or (isinstance(x, float) and np.isnan(x)) else ("expiry" if x else "other"))
    ev["dir"] = ev.side.map({1: "up", -1: "down"})
    real = ev[~ev.type.str.endswith("_rnd")]
    T = {}

    def tab(df, by):
        return [dict(zip(by, k if isinstance(k, tuple) else (k,)), **stats(g)) for k, g in df.groupby(list(by), sort=True)]

    T["type_tf_n"] = tab(ev, ["type", "tf", "n"])
    T["type_tf_n_und"] = tab(ev, ["und", "type", "tf", "n"])
    n2 = real[real.n == 2]
    for name, col in (("dir", "dir"), ("tod", "tod"), ("vix", "vixb"), ("expiry", "exp"), ("und", "und"), ("gap", "gap")):
        T[f"n2_{name}"] = tab(n2, ["type", "tf", col])
    T["n2_pooled_by"] = {name: tab(n2, [col]) for name, col in (("dir", "dir"), ("tod", "tod"), ("vix", "vixb"),
                                                                ("expiry", "exp"), ("und", "und"), ("gap", "gap"))}
    T["n_pooled"] = tab(real, ["tf", "n"])
    return T


def md_table(rows, keys, cols=("n", "mv15_a", "mv30_a", "mv60_a", "mvc_a", "ex30_a", "exc_a", "t_exc", "fake30",
                              "mfe60_a", "mae60_a", "wait")):
    def f(c, v):
        if isinstance(v, str):
            return v
        if c in ("n",):
            return f"{v}"
        if c == "fake30" or c == "win_c":
            return f"{100 * v:.0f}%" if np.isfinite(v) else "-"
        if c in ("wait", "mv30", "mvc", "mfe60", "mae60"):
            return f"{v:+.1f}" if np.isfinite(v) else "-"
        return f"{v:+.2f}" if np.isfinite(v) else "-"
    head = "| " + " | ".join(keys + list(cols)) + " | |"
    sep = "|" + "---|" * (len(keys) + len(cols) + 1)
    lines = [head, sep]
    for r in rows:
        lines.append("| " + " | ".join(str(r[k]) for k in keys) + " | " + " | ".join(f(c, r[c]) for c in cols) + f" | {r['flag']} |")
    return "\n".join(lines)


def jsonable(o):
    if isinstance(o, dict):
        return {str(k): jsonable(v) for k, v in o.items()}
    if isinstance(o, list):
        return [jsonable(v) for v in o]
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.floating, float)):
        return None if not np.isfinite(o) else round(float(o), 5)
    if isinstance(o, (np.bool_,)):
        return bool(o)
    if isinstance(o, (date,)):
        return str(o)
    return o


def write_tables(period="design"):
    ev = pd.read_pickle(os.path.join(OUT, f"events_{period}.pkl"))
    T = summarize(ev)
    os.makedirs(RESULTS, exist_ok=True)
    json.dump(jsonable(T), open(os.path.join(RESULTS, f"events_{period}.json"), "w"), indent=1)
    md = [f"# R20c event study, {period} sessions (generated by study.py; ATR = ATR14 units, points where named)\n",
          "Columns: mv15/30/60/c = move after the event over 15/30/60 min / to 15:29 (ATR); ex30/exc = the same minus the "
          "no-level baseline (same clock minute, all sessions); t_exc = t of exc; fake30 = share with a 1-min close back "
          "through the level within 30 min; mfe60/mae60 = best / worst excursion in 60 min (ATR); wait = points paid by "
          "entering at the N-th close instead of the 1st (N=1: 0).\n",
          "## Level type x timeframe x N (all indices, both sides; _rnd = random-level twin)\n",
          md_table(T["type_tf_n"], ["type", "tf", "n"]),
          "\n## By index\n", md_table(T["type_tf_n_und"], ["und", "type", "tf", "n"])]
    for name in ("dir", "tod", "vix", "expiry", "und", "gap"):
        col = {"dir": "dir", "tod": "tod", "vix": "vixb", "expiry": "exp", "und": "und", "gap": "gap"}[name]
        md += [f"\n## N = 2, by {name} (all real level types pooled)\n", md_table(T["n2_pooled_by"][name], [col]),
               f"\n## N = 2, level type x timeframe x {name}\n", md_table(T[f"n2_{name}"], ["type", "tf", col])]
    open(os.path.join(RESULTS, f"events_{period}.md"), "w").write("\n".join(md) + "\n")
    return T


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "events"
    if cmd == "events":
        run_events("design")
        write_tables("design")
    elif cmd == "tables":
        write_tables(sys.argv[2] if len(sys.argv) > 2 else "design")
    else:
        sys.exit(__doc__)
