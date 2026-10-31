"""R140 Amendment A (PREREG_A.md): deep analysis of the breakout arms' trades, and fake-breakout filters chosen by a
fixed rule, gated on losing less.

    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r140_structure_arms/r140a.py design   # analysis + choice
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r140_structure_arms/r140a.py final    # read once
    python research/hunt/r140_structure_arms/r140a.py decide

Signal source: the index (5- and 15-minute bars from the store's index minutes); VIX and breadth only as R101's
context features. A "fake breakout" is a losing trade.
"""
from __future__ import annotations

import json
import sys
import warnings
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r140 as M  # noqa: E402

K, L, S, R = M.K, M.L, M.S, M.R
N = M.N
RESULTS = M.RESULTS
FINAL_FLAG = RESULTS / "FINAL_READ_A"
ARMS = ("R72", "R51", "LIQ")
H1 = (date(2024, 11, 20), date(2025, 3, 31))
H2 = (date(2025, 4, 1), date(2026, 3, 31))
MIN_HALF = 30
KEEP_PER_ARM = 2
SEED = 1401
QUINT = ("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A10", "C1", "C2", "C3")
CATS = ("A9", "A11", "C4", "C5", "C6")
FEATS = QUINT + CATS
NAMES = dict(A1="push into the entry (3 bars, ATR)", A2="day's range so far / ADR", A3="stretch from today's open / ADR",
             A4="beyond yesterday's extreme / ADR", A5="entry minute", A6="last 5-min bar's close location",
             A7="last 5-min bar's range / ATR", A8="20-bar 5-min box / ATR", A9="15-min Dow trend (with/none/against)",
             A10="yesterday's range / ADR", A11="fake pokes earlier today", C1="gap (trade's way)",
             C2="realised volatility", C3="VIX 30-min change", C4="breadth", C5="VIX regime", C6="weekday")


# ================================================================================================ features
class Days:
    """Per-session high / low / open of one index and the 20-session average daily range (ADR) before each day."""

    def __init__(self, ix: M.Index):
        mh, ml, mo = ix.side.mh, ix.side.ml, ix.side.mo
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            self.dh, self.dl = np.nanmax(mh, axis=1), np.nanmin(ml, axis=1)
        rng = self.dh - self.dl
        self.adr = np.full(len(rng), np.nan)
        for d in range(20, len(rng)):
            w = rng[d - 20:d]
            w = w[np.isfinite(w)]
            if len(w) >= 10:
                self.adr[d] = float(w.mean())
        ok = np.isfinite(mo)
        first = np.argmax(ok, axis=1)
        self.open = np.where(ok.any(axis=1), mo[np.arange(len(rng)), first], np.nan)


_DAYS: dict = {}


def days_of(und: str, ix: M.Index) -> Days:
    if und not in _DAYS:
        _DAYS[und] = Days(ix)
    return _DAYS[und]


def features(ix: M.Index, dy: Days, day: date, er: int, side: str, c: dict) -> dict:
    """PREREG_A section 1, read at the decision (bars closed before minute er; earlier sessions). NaN = unknown."""
    out = {f: np.nan for f in FEATS}
    s = 1.0 if side == "C" else -1.0
    d = ix.pos.get(day)
    j = M.last_bar(ix, 5, day, er)
    if d is None or j < 3:
        return out
    ch = ix.tf[5][0]
    atr = ch.atr[j]
    adr = dy.adr[d]
    last = float(ch.c[j])
    if np.isfinite(atr) and atr > 0:
        out["A1"] = s * (last - ch.c[j - 3]) / atr
        out["A7"] = (ch.h[j] - ch.l[j]) / atr
        if j >= 21:
            out["A8"] = (ch.h[j - 20:j].max() - ch.l[j - 20:j].min()) / atr
    rng = ch.h[j] - ch.l[j]
    if rng > 0:
        out["A6"] = (ch.c[j] - ch.l[j]) / rng if s > 0 else (ch.h[j] - ch.c[j]) / rng
    if int(ch.day[j]) == d and np.isfinite(adr) and adr > 0:
        first = int(ch.first_bar[d])
        out["A2"] = (ch.h[first:j + 1].max() - ch.l[first:j + 1].min()) / adr
        if np.isfinite(dy.open[d]):
            out["A3"] = s * (last - dy.open[d]) / adr
        if d >= 1 and np.isfinite(dy.dh[d - 1]):
            out["A4"] = (last - dy.dh[d - 1]) / adr if s > 0 else (dy.dl[d - 1] - last) / adr
            out["A10"] = (dy.dh[d - 1] - dy.dl[d - 1]) / adr
        pokes, ext = 0, None
        for k in range(first, j + 1):
            hk, lk = (ch.h[k], ch.l[k]) if s > 0 else (-ch.l[k], -ch.h[k])
            ck = ch.c[k] * s
            if ext is not None and hk > ext and ck < (hk + lk) / 2:
                pokes += 1
            ext = hk if ext is None else max(ext, hk)
        out["A11"] = float(min(pokes, 3))
    j15 = M.last_bar(ix, 15, day, er)
    if j15 >= 0:
        out["A9"] = float(int(ix.tf[15][3][j15]) * s)
    out["A5"] = float(er)
    g = c.get("f_gap")
    out["C1"] = s * float(g) if g is not None and np.isfinite(g) else np.nan
    out["C2"] = float(c["f_rv"]) if c.get("f_rv") is not None else np.nan
    out["C3"] = float(c["f_vixchg"]) if c.get("f_vixchg") is not None else np.nan
    out["C4"] = float(c["f_breadth"]) if c.get("f_breadth") is not None else np.nan
    out["C5"] = c.get("f_vixreg")
    out["C6"] = c.get("f_dow")
    return out


def cand_key(und, day, c) -> tuple:
    return (und, day, int(c["er"]), c["side"], c.get("book"))


def trade_key(t) -> tuple:
    return (t["und"], t["day"], int(t["er_sig"]), t["side"], t.get("book"))


def all_features(us: list) -> dict:
    out = {}
    for key, cands, _, _ in us:
        ix = M.index(key[0])
        dy = days_of(key[0], ix)
        for c in cands:
            out[cand_key(key[0], key[1], c)] = features(ix, dy, key[1], int(c["er"]), c["side"], c)
    return out


# ================================================================================================ slices
def edges(values) -> np.ndarray:
    v = np.asarray([x for x in values if x is not None and np.isfinite(x)], float)
    return np.quantile(v, [0.2, 0.4, 0.6, 0.8]) if len(v) >= 5 else np.array([])


def slice_of(f: str, x, ed) -> object:
    """Quintile 0-4 (QUINT, by the fixed edges) or the category itself (CATS); None = unknown."""
    if f in CATS:
        if x is None or (isinstance(x, float) and not np.isfinite(x)):
            return None
        return x if not isinstance(x, (float, np.floating)) else float(x)
    if x is None or not np.isfinite(x) or not len(ed):
        return None
    return int(np.searchsorted(ed, x, side="right"))


def slice_table(trades: list, feats: dict, f: str, ed) -> dict:
    """{slice: {all / H1 / H2: stats}} of the trades (today's rules)."""
    groups = defaultdict(list)
    for t in trades:
        sl = slice_of(f, feats.get(trade_key(t), {}).get(f, np.nan), ed)
        if sl is not None:
            groups[sl].append(t)
    out = {}
    for sl, tr in groups.items():
        row = {}
        for nm, (a, b) in (("all", (K.START, K.DESIGN_END)), ("H1", H1), ("H2", H2)):
            x = [t for t in tr if a <= t["day"] <= b]
            nets = np.array([float(t["net"]) for t in x]) if x else np.zeros(0)
            row[nm] = dict(n=len(x), win=round(float((nets > 0).mean()), 3) if len(x) else None,
                           avg=round(float(nets.mean())) if len(x) else None,
                           loss_trades=round(float(nets[nets < 0].sum())) if len(x) else 0,
                           stopped=round(float(np.mean([str(t.get("why", "")).startswith("stop") for t in x])), 3)
                           if x else None)
        out[sl] = row
    return out


def bad_slice(f: str, tab: dict, arm_avg: dict):
    """PREREG_A section 2: the extreme quintile (or any category) with the lowest design average; it qualifies only if
    in BOTH halves it has >= 30 trades and an average below 0 and below the arm's average. None if it does not."""
    opts = [sl for sl in tab if (f in CATS or sl in (0, 4)) and tab[sl]["all"]["n"]]
    if not opts:
        return None
    sl = min(opts, key=lambda x: tab[x]["all"]["avg"])
    for h in ("H1", "H2"):
        r = tab[sl][h]
        if r["n"] < MIN_HALF or r["avg"] is None or not (r["avg"] < 0 and r["avg"] < arm_avg[h]):
            return None
    return sl


def drop_fn(feats: dict, rules: list):
    """keep_fn for S.filtered: drop a candidate in ANY of the (feature, slice, edges) rules."""
    def keep(g, j, key, c):
        x = feats.get(cand_key(key[0], key[1], c), {})
        return not any(slice_of(f, x.get(f, np.nan), ed) == sl for f, sl, ed in rules)
    return keep


def period_trades(tr, a, b):
    return [t for t in tr if a <= t["day"] <= b]


# ================================================================================================ the run
def design() -> dict:
    out = {"_meta": dict(phase="design", written=datetime.now(timezone.utc).isoformat()), "arms": {}}
    chosen = {}
    for arm in ARMS:
        us = S.units(arm, K.START, K.DESIGN_END)
        base = S.walk(arm, us)
        feats = all_features(us)
        avg = {nm: float(np.mean([t["net"] for t in period_trades(base, a, b)]))
               for nm, (a, b) in (("all", (K.START, K.DESIGN_END)), ("H1", H1), ("H2", H2))}
        losers = [t for t in base if t["net"] < 0]
        arm_out = dict(trades=len(base), losers=len(losers), loser_share=round(len(losers) / len(base), 3),
                       sum_losing_trades=round(sum(t["net"] for t in losers)), avg=avg, features={}, candidates=[])
        cands = []
        for f in FEATS:
            vals = [feats.get(trade_key(t), {}).get(f, np.nan) for t in base]
            ed = edges(vals) if f in QUINT else np.array([])
            tab = slice_table(base, feats, f, ed)
            bs = bad_slice(f, tab, avg)
            arm_out["features"][f] = dict(name=NAMES[f], edges=[round(float(e), 4) for e in ed],
                                          slices={str(k): v for k, v in sorted(tab.items(), key=lambda kv: str(kv[0]))},
                                          bad_slice=None if bs is None else str(bs))
            if bs is not None:
                tr = S.walk(arm, S.filtered(us, drop_fn(feats, [(f, bs, ed)])))
                cut = M.loss_stats(tr)["loss_sum"] - M.loss_stats(base)["loss_sum"]
                cands.append((f, bs, ed, cut))
                arm_out["candidates"].append(dict(feature=f, slice=str(bs), loss_cut=round(cut)))
        cands.sort(key=lambda x: -x[3])
        keep = [x for x in cands[:KEEP_PER_ARM] if x[3] > 0]
        chosen[arm] = [(f, bs, ed) for f, bs, ed, _ in keep]
        out["arms"][arm] = arm_out
        print(arm, "trades", len(base), "losers", len(losers), "candidates", [(c[0], c[1], round(c[3])) for c in cands],
              flush=True)
    tests = {}
    for arm, rules in chosen.items():
        for f, bs, ed in rules:
            tests[f"FB-{arm}-{f}"] = dict(arm=arm, rules=[(f, bs, ed)])
        if len(rules) == 2:
            tests[f"FB-{arm}-both"] = dict(arm=arm, rules=list(rules))
    out["tests"] = {t: dict(arm=v["arm"], rules=[[f, str(bs), [float(e) for e in ed]] for f, bs, ed in v["rules"]])
                    for t, v in tests.items()}
    for t, v in tests.items():
        arm = v["arm"]
        us = S.units(arm, K.START, K.DESIGN_END)
        base = S.walk(arm, us)
        feats = all_features(us)
        tr = S.walk(arm, S.filtered(us, drop_fn(feats, v["rules"])))
        out["tests"][t]["today"] = {p: M.loss_stats(S.by_period(base, p)) for p in ("design", "wf")}
        out["tests"][t]["rule"] = {p: M.loss_stats(S.by_period(tr, p)) for p in ("design", "wf")}
        print(t, {p: (out["tests"][t]["rule"][p]["trades"], out["tests"][t]["rule"][p]["net"],
                      out["tests"][t]["rule"][p]["loss_sum"]) for p in ("design", "wf")}, flush=True)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "design_a.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def parse_rules(rs: list) -> list:
    out = []
    for f, sl, ed in rs:
        if f in CATS:
            try:
                sl = float(sl)
            except ValueError:
                pass
        else:
            sl = int(sl)
        out.append((f, sl, np.array(ed, float)))
    return out


def final() -> dict:
    if FINAL_FLAG.exists():
        raise SystemExit("R140 Amendment A final already read once: " + FINAL_FLAG.read_text())
    dj = json.loads((RESULTS / "design_a.json").read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    S.mods()["C"].open_final()
    rng = np.random.default_rng(SEED)
    out = {"_meta": dict(phase="final", written=datetime.now(timezone.utc).isoformat()), "tests": {}}
    lo, hi = K.FINAL_START, K.FINAL_END
    cache = {}
    for t, v in dj["tests"].items():
        arm = v["arm"]
        if arm not in cache:
            us = S.units(arm, lo, hi)
            cache[arm] = (us, S.walk(arm, us), all_features(us))
        us, base, feats = cache[arm]
        rules = parse_rules(v["rules"])
        kf = drop_fn(feats, rules)
        tr = S.walk(arm, S.filtered(us, kf))
        r = dict(today=M.loss_stats(S.by_period(base, "final")), rule=M.loss_stats(S.by_period(tr, "final")))
        r["changed"] = L.changed(S.by_period(tr, "final"), S.by_period(base, "final"))
        flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
        k = sum(1 for g, u in enumerate(us) for j, c in enumerate(u[1]) if not kf(g, j, u[0], c))
        tw = np.zeros(M.TWINS)
        for x in range(M.TWINS):
            gone = {flat[i] for i in L.random_drop(len(flat), k, rng)}
            trx = S.walk(arm, S.filtered(us, lambda g, j, key, c: (g, j) not in gone))
            tw[x] = M.loss_sum((y["day"], y["net"]) for y in trx if lo <= y["day"] <= hi)
        r["twin"] = L.twin_p(float(r["rule"]["loss_sum"]), tw)
        r["dropped_candidates"] = k
        out["tests"][t] = r
        print(t, (r["rule"]["trades"], r["rule"]["net"], r["rule"]["loss_sum"], r["rule"]["max_dd"]), "today",
              (r["today"]["trades"], r["today"]["net"], r["today"]["loss_sum"], r["today"]["max_dd"]), "p",
              round(r["twin"]["p"], 4), flush=True)
    (RESULTS / "final_a.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def decide() -> dict:
    dj = json.loads((RESULTS / "design_a.json").read_text())["tests"]
    fj = json.loads((RESULTS / "final_a.json").read_text())["tests"]
    tests = list(dj)
    ps = [fj[t]["twin"]["p"] for t in tests]
    qs = L.bh(ps) if ps else []
    out = {}
    for t, q, p in zip(tests, qs, ps):
        rule = {"design": dj[t]["rule"]["design"], "wf": dj[t]["rule"]["wf"], "final": fj[t]["rule"]}
        base = {"design": dj[t]["today"]["design"], "wf": dj[t]["today"]["wf"], "final": fj[t]["today"]}
        g = M.gates(rule, base, float(q), float(p), int(fj[t]["changed"]))
        g.update(p=p, q=round(float(q), 4))
        out[t] = g
    (RESULTS / "decision_a.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
