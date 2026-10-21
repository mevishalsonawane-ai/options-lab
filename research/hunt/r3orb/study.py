#!/usr/bin/env python3
"""R3 study steps (PREREG.md). Research only, 14 Oct 2026.

    python3 research/hunt/r3orb/study.py rebuild     # Step 1: the four arms (+ ORB uncapped) on BANKNIFTY + NIFTY, per year
    python3 research/hunt/r3orb/study.py anatomy     # Step 2: when they win / lose (design indices only)
    python3 research/hunt/r3orb/study.py partb       # Part B rule (A6): each arm's filter, from 2021-2024 design trades
    python3 research/hunt/r3orb/study.py design      # Step 4: 16 variants + baselines, per year / index, walk-forward
    python3 research/hunt/r3orb/study.py holdout     # Step 5: the ONE read of FINNIFTY + MIDCPNIFTY + SENSEX
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from datetime import datetime, timezone

import numpy as np

import r3cfg as K
import r3data as D
import r3engine as E

ARMS4 = E.ARMS
YEARS = K.YEARS


# ------------------------------------------------------------------------------------------------ helpers
def sessions(unds, part="design"):
    out = {}
    for u in unds:
        out[u] = D.load(u, part)
    return out


def keys(sess_by_und, year=None, unds=None):
    return [(u, s.day) for u, ss in sess_by_und.items() if (unds is None or u in unds) for s in ss
            if year is None or s.day.year == year]


def strip(t):
    return {k: (str(v) if k == "day" else v) for k, v in t.items() if k != "feats"}


def table_row(name, sc):
    return (f"| {name} | {sc['trades']:,} | {sc['trades_per_day']:.2f} | {sc['win_rate']:.0%} | {sc['avg_win']:+,.0f} / "
            f"{sc['avg_loss']:+,.0f} | {sc['net']:+,.0f} | {sc['per_day']:+,.0f} | {sc['green_days']:.0%} | "
            f"{sc['worst_day']:+,.0f} | {sc['max_dd']:+,.0f} | {sc['cut_short']} |")


HEAD = ("| rule | trades | trades/day | win rate | avg win / loss | net Rs | Rs/day | green days | worst day | max DD | "
        "big winners cut short |\n|---|---|---|---|---|---|---|---|---|---|---|")


def per_year(trades, sess_by_und, unds=None):
    out = {}
    for y in YEARS:
        ks = keys(sess_by_und, y, unds)
        if not ks:
            continue
        tt = [t for t in trades if t["year"] == y and (unds is None or t["und"] in unds)]
        out[y] = round(sum(t["net"] for t in tt)), len(tt)
    return out


# ------------------------------------------------------------------------------------------------ Step 1
def rebuild():
    sb = sessions(K.DESIGN_UNDS)
    res, md = {}, ["# R3 Step 1: the four arms rebuilt on the lake (design indices)\n",
                   "Rs after costs, 1 lot, every session (BANKNIFTY 2021-09-03 .. 2026-10-12, NIFTY 2021-08-02 .. 2026-10-12).\n"]
    for arm in ARMS4 + ("orb_uncapped",):
        v = E.baseline(arm)
        tr = []
        for u in K.DESIGN_UNDS:
            tr += E.run(sb[u], v)
        r = {"all": E.score(tr, keys(sb))}
        for u in K.DESIGN_UNDS:
            r[u] = E.score([t for t in tr if t["und"] == u], keys(sb, unds=[u]))
            r[u]["years"] = per_year(tr, sb, [u])
        res[arm] = r
        md.append(f"\n## {E.NAMES[arm]}\n\n{HEAD}")
        md.append(table_row("BANKNIFTY + NIFTY", r["all"]))
        for u in K.DESIGN_UNDS:
            md.append(table_row(u, r[u]))
        md.append("\n| index | " + " | ".join(str(y) for y in YEARS) + " |\n|---|" + "---|" * len(YEARS))
        for u in K.DESIGN_UNDS:
            yy = r[u]["years"]
            md.append(f"| {u} | " + " | ".join(f"{yy[y][0]:+,} ({yy[y][1]})" if y in yy else "-" for y in YEARS) + " |")
    (K.RESULTS / "rebuild.json").write_text(json.dumps(res, indent=1, default=str))
    (K.RESULTS / "rebuild.md").write_text("\n".join(md) + "\n")
    print("\n".join(md))


# ------------------------------------------------------------------------------------------------ Step 2
def day_type(s):
    """Descriptive only (needs the whole day): 'trend up' / 'trend down' when the close is in the outer quarter of the
    day's range on the side of the move, else 'range'."""
    ix = s.ix
    ok = np.isfinite(ix[:, 3])
    if not ok.any():
        return "n/a"
    h, l = np.nanmax(ix[:, 1]), np.nanmin(ix[:, 2])
    o, c = ix[ok, 0][0], ix[ok, 3][-1]
    if h - l <= 0:
        return "range"
    pos = (c - l) / (h - l)
    if pos >= 0.75 and c > o:
        return "trend up"
    if pos <= 0.25 and c < o:
        return "trend down"
    return "range"


def inside_close(s):
    b5 = E.bars5(s.ix)
    hi, lo = E.opening_range(b5)
    c = s.ix[np.isfinite(s.ix[:, 3]), 3]
    return bool(len(c) and lo <= c[-1] <= hi)


def bucket_table(trades, key, order=None):
    g = defaultdict(list)
    for t in trades:
        g[key(t)].append(t["net"])
    rows = []
    for k in (order or sorted(g, key=str)):
        if k not in g:
            continue
        x = np.array(g[k])
        rows.append((k, len(x), float(x.mean()), float((x > 0).mean()), float(x.sum())))
    return rows


def terciles(vals):
    v = np.array([x for x in vals if np.isfinite(x)])
    return (float(np.percentile(v, 100 / 3)), float(np.percentile(v, 200 / 3))) if len(v) >= 30 else (np.nan, np.nan)


def tbin(x, cuts):
    if not np.isfinite(x):
        return "n/a"
    return "low" if x < cuts[0] else "mid" if x < cuts[1] else "high"


def anatomy():
    sb = sessions(K.DESIGN_UNDS)
    info = {(u, s.day): (day_type(s), inside_close(s)) for u in sb for s in sb[u]}
    md = ["# R3 Step 2: anatomy of the ORB family (design indices BANKNIFTY + NIFTY only)\n",
          "Mean net Rs per trade (n, win rate, total). Day type and 'closed inside the range' need the whole day: "
          "descriptive only, never a filter. Every other split is known at the signal.\n"]
    out = {}
    for arm in ARMS4 + ("orb_uncapped",):
        v = E.baseline(arm)
        tr = []
        for u in K.DESIGN_UNDS:
            tr += E.run(sb[u], v)
        for t in tr:
            t["dtype"], t["inside"] = info[(t["und"], t["day"])]
        md.append(f"\n## {E.NAMES[arm]} ({len(tr):,} trades)\n")
        sec = {}

        def add(title, rows):
            sec[title] = rows
            md.append(f"\n**{title}**\n\n| bucket | trades | mean Rs/trade | win rate | total Rs |\n|---|---|---|---|---|")
            for k, n, m, w, s in rows:
                md.append(f"| {k} | {n:,} | {m:+,.0f} | {w:.0%} | {s:+,.0f} |")

        add("day type (descriptive)", bucket_table(tr, lambda t: t["dtype"]))
        add("day closed inside the opening range (descriptive)", bucket_table(tr, lambda t: "inside" if t["inside"] else "outside"))
        add("trade direction vs day type (descriptive)", bucket_table(
            tr, lambda t: "with the trend" if (t["dtype"] == "trend up" and t["side"] > 0) or (t["dtype"] == "trend down" and t["side"] < 0)
            else "against the trend" if t["dtype"] != "range" else "range day"))
        for f in E.FEATURES:
            if f == "expiry":
                add("expiry day", bucket_table(tr, lambda t: "expiry" if t["expiry"] else "not expiry"))
                continue
            if f == "t_sig":
                add("signal time", bucket_table(tr, lambda t: f"{9 + (15 + int(t['feats']['t_sig'])) // 60:02d}:00 hour",
                                                ))
                continue
            cuts = terciles([t["feats"][f] for t in tr])
            add(f"{f} terciles (cuts {cuts[0]:.3g} / {cuts[1]:.3g})", bucket_table(tr, lambda t: tbin(t["feats"][f], cuts), ["low", "mid", "high", "n/a"]))
        add("index", bucket_table(tr, lambda t: t["und"]))
        add("side", bucket_table(tr, lambda t: "CE (up)" if t["side"] > 0 else "PE (down)"))
        add("entry number that day", bucket_table(tr, lambda t: min(t["n_day"], 4)))
        add("year", bucket_table(tr, lambda t: t["year"]))
        add("exit", bucket_table(tr, lambda t: t["why"]))
        out[arm] = {k: [[str(r[0])] + list(r[1:]) for r in rows] for k, rows in sec.items()}
    # the 13 Oct case: wide opening ranges and price staying inside until the last entry
    md.append("\n## Wide opening ranges and 'no break by 14:00' (Boss's 13 Oct BANKNIFTY case: a 630-point range)\n")
    md.append("| index | width / ATR14 | sessions | no ORB break by 13:55 | of those, broke 14:00-15:10 |\n|---|---|---|---|---|")
    wide = {}
    for u in K.DESIGN_UNDS:
        rows = defaultdict(lambda: [0, 0, 0])
        for s in sb[u]:
            b5 = E.bars5(s.ix)
            hi, lo = E.opening_range(b5)
            if not np.isfinite(hi) or not s.atr14:
                continue
            w = (hi - lo) / s.atr14
            bk = "<0.4" if w < 0.4 else "0.4-0.6" if w < 0.6 else "0.6-0.8" if w < 0.8 else ">=0.8"
            c = b5[10:56, 3]
            nob = not np.any((c > hi) | (c < lo))
            late = b5[56:71, 3]
            rows[bk][0] += 1
            if nob:
                rows[bk][1] += 1
                if np.any((late > hi) | (late < lo)):
                    rows[bk][2] += 1
        for bk in ("<0.4", "0.4-0.6", "0.6-0.8", ">=0.8"):
            n, a, b = rows[bk]
            md.append(f"| {u} | {bk} | {n} | {a} ({a / max(n, 1):.0%}) | {b} |")
        wide[u] = dict(rows)
    out["wide"] = wide
    (K.RESULTS / "anatomy.json").write_text(json.dumps(out, indent=1, default=str))
    (K.RESULTS / "anatomy.md").write_text("\n".join(md) + "\n")
    print("\n".join(md))


# ------------------------------------------------------------------------------------------------ Part B rule (A6)
def choose_filter(trades: list) -> dict:
    """PREREG A6: on the arm's 2021-2024 trades (both design indices), terciles of each candidate feature; the chosen
    feature has the largest (best - worst tercile mean net per trade) among those whose worst tercile is the same on
    BANKNIFTY and NIFTY. Returns the feature, its worst bucket and the skip interval [lo, hi)."""
    tr = [t for t in trades if t["year"] <= 2024]
    cands = []
    for f in E.FEATURES:
        if f == "expiry":
            groups = {"low": [t for t in tr if t["feats"][f] < 0.5], "high": [t for t in tr if t["feats"][f] >= 0.5]}
            bounds = {"low": (-np.inf, 0.5), "high": (0.5, np.inf)}
            cuts = (0.5, 0.5)
        else:
            cuts = terciles([t["feats"][f] for t in tr])
            if not np.isfinite(cuts[0]):
                continue
            groups = {b: [t for t in tr if tbin(t["feats"][f], cuts) == b] for b in ("low", "mid", "high")}
            bounds = {"low": (-np.inf, cuts[0]), "mid": (cuts[0], cuts[1]), "high": (cuts[1], np.inf)}
        means = {b: np.mean([t["net"] for t in g]) for b, g in groups.items() if g}
        worst = min(means, key=means.get)
        best = max(means, key=means.get)
        per_und = {}
        for u in K.DESIGN_UNDS:
            mu = {b: np.mean([t["net"] for t in g if t["und"] == u]) for b, g in groups.items() if any(t["und"] == u for t in g)}
            per_und[u] = min(mu, key=mu.get) if mu else None
        consistent = all(per_und[u] == worst for u in K.DESIGN_UNDS)
        cands.append(dict(feature=f, cuts=cuts, means={b: float(m) for b, m in means.items()}, worst=worst,
                          spread=float(means[best] - means[worst]), per_und_worst=per_und, consistent=consistent,
                          skip=[float(x) for x in bounds[worst]], n={b: len(g) for b, g in groups.items()}))
    ok = [c for c in cands if c["consistent"]]
    pick = max(ok, key=lambda c: c["spread"]) if ok else None
    return dict(pick=pick, candidates=cands)


def partb():
    sb = sessions(K.DESIGN_UNDS)
    out = {}
    for arm in ARMS4:
        v = E.baseline(arm)
        tr = []
        for u in K.DESIGN_UNDS:
            tr += E.run(sb[u], v)
        out[arm] = choose_filter(tr)
        p = out[arm]["pick"]
        print(arm, p and (p["feature"], p["worst"], p["skip"], round(p["spread"]), p["n"]))
    out["written"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    (K.RESULTS / "partb.json").write_text(json.dumps(out, indent=1, default=str))


# ------------------------------------------------------------------------------------------------ the 16 variants
def variants() -> list[E.Variant]:
    pb = json.loads((K.RESULTS / "partb.json").read_text())
    out = []
    for arm in ARMS4:
        b = E.baseline(arm)
        p = pb[arm]["pick"]
        filt = (p["feature"], float(p["skip"][0]), float(p["skip"][1])) if p else ()
        ex = dict(tgt_mult=0.0, lock_rs=(1000.0, 500.0), trail_rs=(1000.0, 500.0), time_stop=(45, 0.25))
        st = dict(itm=1, side_cap=1)
        out += [
            E.with_(b, id=f"r3-{arm}-F", filt=filt),
            E.with_(b, id=f"r3-{arm}-E", **ex),
            E.with_(b, id=f"r3-{arm}-S", **st),
            E.with_(b, id=f"r3-{arm}-C", filt=filt, **ex, **st),
        ]
    assert len(out) == 16
    return out


_G = {}


def _twin_job(args):
    """Worker (forked; reads the parent's sessions and trades): `n` twins of one variant, its own seed."""
    i, n = args
    v = _G["vs"][i]
    rng = np.random.default_rng(K.SEED + i)
    return [E.twin_net(_G["by_key"], _G["trades"][v.id], v, rng) for _ in range(n)]


def evaluate(vs, sb, twins=0, twin_ids=None):
    """Every rule on the sessions; with `twins`, PREREG A9's random-entry twins for the rules in `twin_ids` (default all),
    seed K.SEED + the rule's position in `vs`, run in 4 forked workers."""
    import multiprocessing as mp
    res, trades_by = {}, {}
    allk = keys(sb)
    by_key = {(u, s.day): s for u, ss in sb.items() for s in ss}
    for v in vs:
        tr = []
        for u in sb:
            tr += E.run(sb[u], v)
        trades_by[v.id] = tr
        r = {"all": E.score(tr, allk), "t_p": E.t_p(tr, allk)}
        for u in sb:
            r[u] = E.score([t for t in tr if t["und"] == u], keys(sb, unds=[u]))
        r["years"] = per_year(tr, sb)
        r["years_by_und"] = {u: per_year(tr, sb, [u]) for u in sb}
        res[v.id] = r
        print(v.id, r["all"]["trades"], round(r["all"]["net"]), r["years"], flush=True)
    if twins:
        _G.update(vs=vs, by_key=by_key, trades=trades_by)
        idx = [i for i, v in enumerate(vs) if twin_ids is None or v.id in twin_ids]
        with mp.get_context("fork").Pool(4) as pool:
            outs = pool.map(_twin_job, [(i, twins) for i in idx])
        for i, tw in zip(idx, outs):
            r, tw = res[vs[i].id], np.array(tw)
            r["twin_mean"] = float(tw.mean())
            r["twin_p"] = float((1 + (tw >= r["all"]["net"]).sum()) / (twins + 1))
            print(vs[i].id, "twin mean", round(r["twin_mean"]), "p", round(r["twin_p"], 4), flush=True)
    return res, trades_by


def design():
    sb = sessions(K.DESIGN_UNDS)
    vs = [E.baseline(a) for a in ARMS4] + variants()
    res, trades_by = evaluate(vs, sb, twins=int(sys.argv[2]) if len(sys.argv) > 2 else 0)
    # gates D1-D4
    for v in vs:
        r = res[v.id]
        yrs = r["years"]
        late = sum(yrs[y][0] for y in (2025, 2026) if y in yrs)
        r["gates"] = dict(D1=r["all"]["net"] > 0, D2=sum(yrs[y][0] > 0 for y in yrs) >= 4,
                          D3=r["BANKNIFTY"]["net"] > 0, D4=late > 0)
        r["design_pass"] = all(r["gates"].values())
    # nested walk-forward of "pick the best so far"
    wf = {}
    ids = [v.id for v in vs]
    for y in (2023, 2024, 2025, 2026):
        best = max(ids, key=lambda i: sum(res[i]["years"][yy][0] for yy in res[i]["years"] if yy < y))
        wf[y] = dict(pick=best, net=res[best]["years"][y][0],
                     baselines={a: res[f"base-{a}"]["years"][y][0] for a in ARMS4})
    out = dict(results=res, walk_forward=wf, variants=[v.__dict__ for v in vs])
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    with open(K.OUT / "design_trades.json", "w") as f:
        json.dump({k: [strip(t) for t in v] for k, v in trades_by.items()}, f, default=str)
    print(json.dumps(wf, indent=1))


# ------------------------------------------------------------------------------------------------ Step 5: the ONE holdout read
def holdout():
    if K.HOLDOUT_FLAG.exists():
        raise SystemExit("R3's holdout has been read already (results/HOLDOUT_READ). A new test needs a new PREREG.")
    if not (K.RESULTS / "partb.json").exists():
        raise SystemExit("Part B is missing")
    K.HOLDOUT_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    sb = {u: D.load(u, "holdout") for u in K.HOLDOUT_UNDS}
    vs = [E.baseline(a) for a in ARMS4] + variants()
    res, trades_by = evaluate(vs, sb, twins=K.TWINS, twin_ids={v.id for v in vs[4:]})
    prior = json.loads((K.RESULTS / "prior_p.json").read_text())
    pri = [p for g in prior["groups"].values() for p in g.values()]
    own = []
    for v in vs[4:]:
        own += [res[v.id]["t_p"], res[v.id]["twin_p"]]
    q = E.bh(own + pri)
    for i, v in enumerate(vs[4:]):
        r = res[v.id]
        r["q_t"], r["q_twin"] = q[2 * i], q[2 * i + 1]
        base = res[f"base-{v.arm}"]["all"]["net"]
        r["gates"] = dict(H1=r["all"]["net"] > 0, H2=r["all"]["net"] > base, H3=r["twin_p"] < 0.05,
                          H4=max(r["q_t"], r["q_twin"]) < K.BH_Q,
                          H5=sum(r[u]["net"] > 0 for u in K.HOLDOUT_UNDS) >= 2)
        r["holdout_pass"] = all(r["gates"].values())
    out = dict(read=K.HOLDOUT_FLAG.read_text().strip(), m=len(own) + len(pri), results=res)
    (K.RESULTS / "holdout.json").write_text(json.dumps(out, indent=1, default=str))
    with open(K.OUT / "holdout_trades.json", "w") as f:
        json.dump({k: [strip(t) for t in v] for k, v in trades_by.items()}, f, default=str)


if __name__ == "__main__":
    {"rebuild": rebuild, "anatomy": anatomy, "partb": partb, "design": design, "holdout": holdout}[sys.argv[1]]()
