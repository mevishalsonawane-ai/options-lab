"""R19c runner (PREREG.md). Design first; the holdout once.

    PYTHONPATH=. python3 -m research.hunt.r19c.run design     # design period only: tables + design gates 1-2
    PYTHONPATH=. python3 -m research.hunt.r19c.run holdout    # the locked holdout, ONCE (writes a flag; refuses a second run)

Inputs: R17's day caches (research/hunt/r17/data.py) and R19b's Liquidity sessions (research/hunt/r19b/data.py), built
from the research-data release. No Dhan API. Results go to research/hunt/r19c/results.
"""
from __future__ import annotations

import json
import math
import os
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

from . import sim as S

import data as D17  # noqa: E402  (research/hunt/r17/data.py; sim put its folder on the path)
from research.brain import config as BC  # noqa: E402
from research.hunt.r19b import data as D19  # noqa: E402

CUT = date(2025, 4, 1)
RESULTS = Path(__file__).resolve().parent / "results"
OUT = Path(os.environ.get("R19C_OUT", BC.paths().root / "r19c")).expanduser()
FLAG = OUT / "holdout_read.flag"
TWINS = 1000
SEED = 1913
Q = 0.10
MIN_TRADES = 30
GIVE_UP = 0.30
TOL = 1.0

ORB = ["orb", "orb_fresh", "orb_sweep", "range_fade"]
ARMS = ["liq15", "liq5", "orb", "orb_fresh", "orb_sweep", "range_fade", "vix_div_banknifty", "vix_div_nifty", "hero", "solo"]
NAMES = {"liq15": "Liquidity BANKNIFTY 15m", "liq5": "Liquidity BANKNIFTY 5m", "orb": "ORB", "orb_fresh": "ORB Fresh",
         "orb_sweep": "ORB Sweep", "range_fade": "Range Fade", "vix_div_banknifty": "VIX divergence BANKNIFTY",
         "vix_div_nifty": "VIX divergence NIFTY", "hero": "Hero (NIFTY expiry)", "solo": "Solo (midday)"}
PORTFOLIO = {  # id: (arms off, loss cap, profit lock (reach, keep))
    "P0": (False, None, None),
    "P1": (True, None, None),
    "P2": (False, -2000.0, None),
    "P3": (False, -3000.0, None),
    "P4": (False, None, (2000.0, 1000.0)),
    "P5": (True, None, (2000.0, 1000.0)),
    "P6": (True, -2000.0, None),
    "P7": (True, -2000.0, (2000.0, 1000.0)),
}


# ---------------------------------------------------------------------------------------------------- data and runs
class Data:
    def __init__(self):
        self.bn = D17.load("BANKNIFTY")
        self.nf = D17.load("NIFTY")
        self.recs = D19.load()


def variants_of(arm):
    return [None] + S.VARIANTS + ([S.REPLACE] if arm in ORB else [])


def vname(f):
    return "CTRL" if f is None else f.name


def run_arm(D, arm, floor):
    if arm in S.LIQ_ARMS:
        return S.run_liq(D.recs, arm, floor)
    if arm in ORB:
        return S.run_orb(D.bn, arm, floor)
    if arm == "vix_div_banknifty":
        return S.run_vixdiv(D.bn, floor)
    if arm == "vix_div_nifty":
        return S.run_vixdiv(D.nf, floor)
    if arm == "hero":
        return S.run_hero(D.nf, floor)
    if arm == "solo":
        return S.run_solo({"BANKNIFTY": D.bn, "NIFTY": D.nf}, floor)
    raise ValueError(arm)


def fixed_entries(D, arm, ctrl, floor):
    """CTRL's entries, each held on its own, under `floor` (for the random twin)."""
    if arm in S.LIQ_ARMS:
        keys = {t["ctx"]["key"] for t in ctrl}
        got = {(t["day"], t["entry_min"]): t for t in S.run_liq(D.recs, arm, floor, only=keys)}
        return [got[(t["day"], t["entry_min"])] for t in ctrl]
    return [S.refloor(t, floor) for t in ctrl]


def in_period(trades, period):
    return [t for t in trades if (t["day"] < CUT) == (period == "design")]


# ---------------------------------------------------------------------------------------------------- statistics
def daily(trades):
    d: dict = {}
    for t in trades:
        d[t["day"]] = d.get(t["day"], 0.0) + t["net"]
    return d


def max_dd(days_net):
    eq = peak = worst = 0.0
    for k in sorted(days_net):
        eq += days_net[k]
        peak = max(peak, eq)
        worst = min(worst, eq - peak)
    return worst


def floor_exit(t):
    return t["why"] in ("floor", "trail")


def stats(trades):
    dn = daily(trades)
    v = list(dn.values())
    lots = [t["qty"] / t["lot"] for t in trades]
    return dict(
        trades=len(trades), days=len(dn), net=round(sum(t["net"] for t in trades), 2),
        win=(sum(t["net"] > 0 for t in trades) / len(trades)) if trades else None,
        green=(sum(x > 0 for x in v) / len(v)) if v else None, green_days=sum(x > 0 for x in v),
        worst_day=round(min(v), 2) if v else 0.0, best_day=round(max(v), 2) if v else 0.0,
        max_dd=round(max_dd(dn), 2),
        profit_to_loss=sum(1 for t, n in zip(trades, lots) if t["peak_lot"] >= 1000 - 1e-9 and t["net"] < 0),
        floor_exits=sum(floor_exit(t) for t in trades),
        big=sum(1 for t, n in zip(trades, lots) if t["net"] / max(n, 1) >= 2000))


def gates12(c, v):
    g1 = (c["green"] is not None and v["green"] is not None and v["green"] > c["green"] + 1e-12
          and v["worst_day"] >= c["worst_day"] - TOL and v["max_dd"] >= c["max_dd"] - TOL)
    g2 = v["net"] > 0 and (c["net"] <= 0 or v["net"] >= (1 - GIVE_UP) * c["net"] - TOL)
    return bool(g1), bool(g2)


def sign_test(ctrl_daily, var_daily):
    """One-sided exact sign test on the days whose colour changed: up = not green -> green, down = green -> not green."""
    days = set(ctrl_daily) | set(var_daily)
    up = sum(1 for d in days if ctrl_daily.get(d, 0.0) <= 0 < var_daily.get(d, 0.0))
    down = sum(1 for d in days if var_daily.get(d, 0.0) <= 0 < ctrl_daily.get(d, 0.0))
    n = up + down
    p = 1.0 if n == 0 else sum(math.comb(n, k) for k in range(up, n + 1)) / 2 ** n
    return up, down, p


def bh(ps):
    """Benjamini-Hochberg q-values, in the input order."""
    n = len(ps)
    order = sorted(range(n), key=lambda i: ps[i])
    q = [0.0] * n
    run = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        run = min(run, ps[i] * n / rank)
        q[i] = run
    return q


def twin_share(ctrl, var_fixed, floor, rng):
    """The share of TWINS random-early-exit twins the floor's fixed-entry net beats (PREREG gate 4)."""
    total_ctrl = sum(t["net"] for t in ctrl)
    total_var = sum(t["net"] for t in var_fixed)
    options, base = [], total_ctrl
    for c, v in zip(ctrl, var_fixed):
        if not floor_exit(v) or v["exit_min"] >= c["exit_min"]:
            continue
        act = S.points(floor.act, c["lot"])
        hit = np.nonzero(np.isfinite(c["ph"]) & (c["ph"] - c["entry"] >= act - 1e-9))[0]
        if not len(hit):
            continue
        m0, m1 = int(c["pm"][hit[0]]) + 1, int(c["exit_min"]) - 2
        if m1 < m0:
            continue
        nets = np.array([S.net_if_sold(c, S.market_sell_after(c, m)) for m in range(m0, m1 + 1)])
        options.append(nets)
        base -= c["net"]
    if not options:
        return None, 0
    tw = np.full(TWINS, base)
    for nets in options:
        tw += nets[rng.integers(0, len(nets), TWINS)]
    return float(np.mean(total_var > tw)), len(options)


# ---------------------------------------------------------------------------------------------------- portfolio day rules
def day_rule(trades, cap=None, lock=None, force=None):
    """One day's trades (every arm) under the day rules: returns (kept trades with their nets, fired kind or None).
    cap: block new entries once realised + open <= cap. lock (reach, keep): once the day reached `reach`, at `keep` sell
    everything open (next open) and block. force (minute, 'block' | 'sell'): a twin's action at that minute instead."""
    if not trades:
        return [], None
    ts = sorted(trades, key=lambda t: (t["entry_min"], t["arm"]))
    m_first = min(t["entry_min"] for t in ts)
    m_last = max(t["exit_min"] for t in ts)
    reached = False
    start = min(m_first, force[0]) if force is not None else m_first
    for m in range(start, m_last + 1):
        act = None
        if force is not None and m == force[0]:
            act = force[1]
        elif force is None:
            live = [t for t in ts if t["entry_min"] <= m]
            pnl = sum(t["net"] if t["exit_min"] <= m else S.mark(t, m) for t in live)
            if lock is not None:
                reached = reached or pnl >= lock[0] - 1e-9
                if reached and pnl <= lock[1] + 1e-9:
                    act = "sell"
            if act is None and cap is not None and pnl <= cap + 1e-9:
                act = "block"
        if act is None:
            continue
        out = []
        for t in ts:
            if t["entry_min"] > m:
                continue
            if act == "sell" and t["exit_min"] > m:
                out.append(dict(t, net=S.net_if_sold(t, S.market_sell_after(t, m)), why="day_lock", exit_min=m + 1))
            else:
                out.append(t)
        return out, act
    return ts, None


def portfolio(ctrl_by_arm, pid, force_days=None):
    off, cap, lock = PORTFOLIO[pid]
    arms = [a for a in ARMS if not (off and a in ORB)]
    by_day: dict = {}
    for a in arms:
        for t in ctrl_by_arm[a]:
            by_day.setdefault(t["day"], []).append(t)
    out, fired = [], []
    for d in sorted(by_day):
        if force_days is not None:
            kept, act = day_rule(by_day[d], force=force_days.get(d)) if d in force_days else (by_day[d], None)
        else:
            kept, act = day_rule(by_day[d], cap, lock) if (cap is not None or lock is not None) else (by_day[d], None)
        out += kept
        if act:
            fired.append((d, act))
    return out, fired


def portfolio_twin(ctrl_by_arm, pid, period, fired, rng, n=TWINS):
    """Gate 4 for a day rule: the same number of days (random among the period's traded days) get the same actions at a
    random minute 09:30-15:00. Returns the share of twins the rule's net beats."""
    base_pid = "P1" if PORTFOLIO[pid][0] else "P0"
    base, _ = portfolio(ctrl_by_arm, base_pid)
    base = in_period(base, period)
    days = sorted({t["day"] for t in base})
    by_day: dict = {}
    for t in base:
        by_day.setdefault(t["day"], []).append(t)
    plain = {d: sum(t["net"] for t in by_day[d]) for d in days}
    total_plain = sum(plain.values())
    rule_net = sum(t["net"] for t in in_period(portfolio(ctrl_by_arm, pid)[0], period))
    acts = [a for _, a in fired]
    if not acts:
        return None
    cache: dict = {}

    def alt(d, m, a):
        k = (d, m, a)
        if k not in cache:
            kept, _ = day_rule(by_day[d], force=(m, a))
            cache[k] = sum(t["net"] for t in kept)
        return cache[k]

    wins = 0
    for _ in range(n):
        pick = rng.choice(len(days), size=len(acts), replace=False)
        tot = total_plain
        for i, a in zip(pick, acts):
            d = days[i]
            m = int(rng.integers(570, 901))
            tot += alt(d, m, a) - plain[d]
        wins += rule_net > tot
    return wins / n


# ---------------------------------------------------------------------------------------------------- reporting helpers
def rs(x):
    return ("-" if x < 0 else "+") + f"Rs {abs(x):,.0f}"


def pct(x):
    return "-" if x is None else f"{100 * x:.0f}%"


def row(arm, name, s, extra=""):
    return (f"| {arm} | {name} | {s['trades']} | {s['days']} | {pct(s['win'])} | {rs(s['net'])} | {pct(s['green'])} | "
            f"{rs(s['worst_day'])} | {rs(s['max_dd'])} | {s['profit_to_loss']} | {s['floor_exits']} |{extra}")


HEAD = ("| arm | variant | trades | days | win | net | green days | worst day | max DD | profit->loss | floor exits |")


def jsonable(x):
    if isinstance(x, dict):
        return {str(k): jsonable(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [jsonable(v) for v in x]
    if isinstance(x, (np.floating, np.integer)):
        return x.item()
    if isinstance(x, date):
        return x.isoformat()
    return x


def run_all(D):
    res = {}
    for arm in ARMS:
        for f in variants_of(arm):
            res[(arm, vname(f))] = run_arm(D, arm, f)
    return res


# ---------------------------------------------------------------------------------------------------- design
def design():
    D = Data()
    res = run_all(D)
    out = {"arms": {}, "portfolio": {}}
    lines = ["# R19c design results (before 1 Apr 2025)", "",
             "Written before the holdout was read. Gate 1 (daily) and gate 2 (net) on the design period; a variant must pass",
             "them here AND in the holdout.", "", HEAD + " gate 1 | gate 2 |", "|" + "---|" * 13]
    for arm in ARMS:
        c = stats(in_period(res[(arm, "CTRL")], "design"))
        out["arms"][arm] = {"CTRL": c}
        lines.append(row(NAMES[arm], "CTRL", c, " - | - |"))
        for f in variants_of(arm)[1:]:
            v = stats(in_period(res[(arm, f.name)], "design"))
            g1, g2 = gates12(c, v)
            v.update(g1=g1, g2=g2)
            out["arms"][arm][f.name] = v
            lines.append(row(NAMES[arm], f.name, v, f" {'pass' if g1 else 'fail'} | {'pass' if g2 else 'fail'} |"))
    ctrl_by_arm = {a: res[(a, "CTRL")] for a in ARMS}
    lines += ["", "## Portfolio (1 lot each arm, plain rules; Night not run)", "", HEAD + " fired | gate 1 | gate 2 |",
              "|" + "---|" * 14]
    p0 = None
    for pid in PORTFOLIO:
        tr, fired = portfolio(ctrl_by_arm, pid)
        s = stats(in_period(tr, "design"))
        nf = sum(1 for d, _ in fired if d < CUT)
        if pid == "P0":
            p0 = s
            ex = f" {nf} | - | - |"
        else:
            g1, g2 = gates12(p0, s)
            s.update(g1=g1, g2=g2)
            ex = f" {nf} | {'pass' if g1 else 'fail'} | {'pass' if g2 else 'fail'} |"
        s["fired"] = nf
        out["portfolio"][pid] = s
        lines.append(row(pid, "", s, ex))
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "design.md").write_text("\n".join(lines) + "\n")
    (RESULTS / "design.json").write_text(json.dumps(jsonable(out), indent=1))
    print("\n".join(lines))


# ---------------------------------------------------------------------------------------------------- holdout (once)
def holdout():
    OUT.mkdir(parents=True, exist_ok=True)
    if FLAG.exists():
        sys.exit(f"The holdout was already read ({FLAG.read_text().strip()}). PREREG.md: once only.")
    FLAG.write_text(datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC"))
    des = json.loads((RESULTS / "design.json").read_text())
    D = Data()
    res = run_all(D)
    rng = np.random.default_rng(SEED)
    hyps = []           # (kind, arm or pid, variant, p)
    out = {"arms": {}, "portfolio": {}, "years": {}}
    for arm in ARMS:
        ctrl_all = res[(arm, "CTRL")]
        ctrl = in_period(ctrl_all, "holdout")
        c = stats(ctrl)
        out["arms"][arm] = {"CTRL": c}
        out["years"][arm] = {"design": des["arms"][arm]["CTRL"]["net"], "holdout": c["net"]}
        for f in variants_of(arm)[1:]:
            var = in_period(res[(arm, f.name)], "holdout")
            v = stats(var)
            g1, g2 = gates12(c, v)
            up, down, p = sign_test(daily(ctrl), daily(var))
            fixed = fixed_entries(D, arm, ctrl, f)
            share, ncut = twin_share(ctrl, fixed, f, rng)
            d = des["arms"][arm][f.name]
            v.update(g1=g1, g2=g2, up=up, down=down, p=p, twin=share, twin_cut=ncut, design_g1=d["g1"], design_g2=d["g2"],
                     fixed_net=round(sum(t["net"] for t in fixed), 2))
            out["arms"][arm][f.name] = v
            hyps.append(("arm", arm, f.name, p))
    ctrl_by_arm = {a: res[(a, "CTRL")] for a in ARMS}
    p0 = None
    for pid in PORTFOLIO:
        tr, fired = portfolio(ctrl_by_arm, pid)
        hold = in_period(tr, "holdout")
        s = stats(hold)
        hf = [(d, a) for d, a in fired if d >= CUT]
        s["fired"] = len(hf)
        if pid == "P0":
            p0, p0_daily = s, daily(hold)
        else:
            g1, g2 = gates12(p0, s)
            up, down, p = sign_test(p0_daily, daily(hold))
            tw = portfolio_twin(ctrl_by_arm, pid, "holdout", hf, rng) if PORTFOLIO[pid][1] or PORTFOLIO[pid][2] else None
            d = des["portfolio"][pid]
            s.update(g1=g1, g2=g2, up=up, down=down, p=p, twin=tw, design_g1=d["g1"], design_g2=d["g2"])
            hyps.append(("portfolio", pid, "", p))
        out["portfolio"][pid] = s
    qs = bh([h[3] for h in hyps])
    for (kind, k, vn, _), q in zip(hyps, qs):
        tgt = out["arms"][k][vn] if kind == "arm" else out["portfolio"][k]
        tgt["q"] = q
        twin_ok = tgt["twin"] is None and kind == "portfolio" and not (PORTFOLIO[k][1] or PORTFOLIO[k][2])
        twin_ok = twin_ok or (tgt["twin"] is not None and tgt["twin"] >= 0.95)
        enough = tgt["trades"] >= MIN_TRADES
        ok = tgt["design_g1"] and tgt["design_g2"] and tgt["g1"] and tgt["g2"] and q <= Q and twin_ok
        tgt["verdict"] = "PASS" if ok and enough else ("inconclusive" if not enough else "FAIL")
    out["n_hypotheses"] = len(hyps)
    out["read_at"] = FLAG.read_text().strip()
    (RESULTS / "holdout.json").write_text(json.dumps(jsonable(out), indent=1))
    write_holdout_md(out)


def write_holdout_md(out):
    L = [f"# R19c holdout results (from 1 Apr 2025; read once, {out['read_at']})", "",
         f"p: one-sided exact sign test on days that changed colour; q: Benjamini-Hochberg over {out['n_hypotheses']};",
         "twin: share of 1,000 random-early-exit twins beaten (needs 95%). d1/d2: design gates 1/2; h1/h2: holdout gates 1/2.",
         "", HEAD + " up/down | p | q | twin | d1 d2 h1 h2 | verdict |", "|" + "---|" * 17]
    for arm, vs in out["arms"].items():
        for vn, s in vs.items():
            if vn == "CTRL":
                L.append(row(NAMES[arm], "CTRL", s, " | | | | | |"))
                continue
            flags = " ".join("y" if s[k] else "n" for k in ("design_g1", "design_g2", "g1", "g2"))
            L.append(row(NAMES[arm], vn, s, f" {s['up']}/{s['down']} | {s['p']:.3f} | {s['q']:.2f} | {pct(s['twin'])} | "
                                           f"{flags} | {s['verdict']} |"))
    L += ["", "## Portfolio (1 lot each arm, plain rules)", "", HEAD + " fired | up/down | p | q | twin | d1 d2 h1 h2 | verdict |",
          "|" + "---|" * 18]
    for pid, s in out["portfolio"].items():
        if pid == "P0":
            L.append(row(pid, "", s, f" {s['fired']} | | | | | | |"))
            continue
        flags = " ".join("y" if s[k] else "n" for k in ("design_g1", "design_g2", "g1", "g2"))
        L.append(row(pid, "", s, f" {s['fired']} | {s['up']}/{s['down']} | {s['p']:.3f} | {s['q']:.2f} | {pct(s['twin'])} | "
                                 f"{flags} | {s['verdict']} |"))
    L += ["", "## Each arm's net in the two years (plain rules)", "", "| arm | design year | holdout year | both negative |",
          "|---|---|---|---|"]
    for arm, y in out["years"].items():
        L.append(f"| {NAMES[arm]} | {rs(y['design'])} | {rs(y['holdout'])} | {'yes' if y['design'] < 0 and y['holdout'] < 0 else 'no'} |")
    (RESULTS / "holdout.md").write_text("\n".join(L) + "\n")
    print("\n".join(L))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    {"design": design, "holdout": holdout}[cmd]()
