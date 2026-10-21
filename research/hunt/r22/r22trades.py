#!/usr/bin/env python3
"""R22 trading test (PREREG.md part A4 framework, part B rules). Research only, 13 Oct 2026.

    python3 research/hunt/r22/r22trades.py design     # design period only -> results/design.json (frozen)
    python3 research/hunt/r22/r22trades.py holdout    # the ONE holdout read -> results/holdout.json (refuses twice)
    R22_SOURCE=lake python3 research/hunt/r22/r22trades.py design    # the same on the 6-year Dhan lake (results/lake/)

A rule's trade on one index and day: the first signal of each side (r22events' BREAK on 15-minute candles, or the
RETEST-HOLD on 5-minute candles) whose candle closes by 14:00 buys 1 lot of the call (up) / put (down) at the ORB arm's
strike (ATM of the 09:20 bar's close, held all day), nearest expiry AFTER the day, entry at the next minute's open (the
option must print within 3 minutes, premium above 40 / 20). Exits: the ORB family (R17 arms) with the part B changes.
Fills / charges: R17's `arms.py` (Fills 'liq', Costs 'app'). The baseline is today's ORB, R20's V0 (r20lib).
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

import numpy as np
from scipy import stats as SS

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r22cfg as CFG  # noqa: E402  (sets sys.path and the data switch before R17 is imported)
import arms as A  # noqa: E402
import r20lib as L20  # noqa: E402
import r22events as EV  # noqa: E402
import r22levels as LV  # noqa: E402
import r22study as S  # noqa: E402
from research.obuy.costs import Fills, floor_tick  # noqa: E402

ARM = {"BANKNIFTY": dict(step=100, stop=40.0, tgt=40.0, floor=40.0), "NIFTY": dict(step=50, stop=20.0, tgt=20.0, floor=20.0)}
WIN = {15: (3, 18), 5: (9, 56)}          # decision candles: labelled 10:00 .. 13:45 (15-min) / 10:00 .. 13:55 (5-min)
W_NARROW, W_WIDE = 0.407, 0.563          # part B: the design terciles of W = R / ATR14 (results/events_design.md)
TWINS = 1000
BH_Q = 0.05

# Part B rules (PREREG.md). Filled in from the part B commit (7dee09e), before any trading test.
RULES: dict = {
    "r22-brk15": dict(entry="BREAK", tf=15),
    "r22-retest": dict(entry="RETEST-HOLD", tf=5, idx_stop="edge"),
    "r22-brk15-x1": dict(entry="BREAK", tf=15, idx_tgt=1.0),
    "r22-brk15-mid": dict(entry="BREAK", tf=15, idx_stop="mid", prem_stop=False),
    "r22-brk15-nowide": dict(entry="BREAK", tf=15, wmax=W_WIDE),
    "r22-brk15-nonarrow": dict(entry="BREAK", tf=15, wmin=W_NARROW),
}
BASELINE = "orb-v0"


# ------------------------------------------------------------------------------------------------ signals
def passes(rule, w):
    if "wmax" in rule and not (w < rule["wmax"]):
        return False
    if "wmin" in rule and not (w >= rule["wmin"]):
        return False
    return True


def signals(rule, ix, lv, w):
    """[(side, decision row, edge)] for one index-day: the first signal of each side inside the rule's window."""
    if not passes(rule, w):
        return []
    tf = rule["tf"]
    k0, k1 = WIN[tf]
    bs = LV.bars(ix, tf)
    out = []
    for edge, side in (("OH", 1), ("OL", -1)):
        evs = EV.edge_events(bs, tf, lv[edge], side, lv["R"], k0, k1, retest=rule["entry"] == "RETEST-HOLD", touch=False)
        if rule["entry"] == "RETEST-HOLD":
            rt = [e for e in evs if e["kind"].startswith("RETEST")]
            e = rt[0] if rt and rt[0]["kind"] == "RETEST-HOLD" else None      # the FIRST retest must hold
        else:
            e = next((x for x in evs if x["kind"] == "BREAK"), None)
        if e is not None:
            out.append((side, EV.row_of(e["k"], tf), edge))
    return out


def exit_levels(rule, lv, edge, side):
    """(index stop level or None, index target level or None) for a trade on `edge`."""
    st = {"edge": lv[edge], "mid": lv["OM"]}.get(rule.get("idx_stop"))
    tg = None
    if rule.get("idx_tgt"):
        tg = lv[f"X{'+' if side > 0 else '-'}{rule['idx_tgt']:g}"]
    return st, tg


# ------------------------------------------------------------------------------------------------ exits
def sim(a, ix, c0, e, cfg, lot, side, prem_stop=True, idx_stop=None, idx_tgt=None):
    """The ORB family's exits (R17 sim_fixed_pts: resting SL-M at max(e - stop, ladder lock), the target on a minute's
    high sold at the next open, 15:10) with part A4's changes: no premium stop when prem_stop is False; an index stop (a
    5-min index close back through idx_stop: next minute's open); an index target (the 1-minute index high / low reaches
    idx_tgt: next minute's open) that replaces the premium target and the ladder. Returns (exit, exit row, why)."""
    stop_pts = cfg["stop"] if prem_stop else np.inf
    tgt = None if idx_tgt is not None else cfg["tgt"]
    trig = floor_tick(np.array([e - stop_pts]))[0] if np.isfinite(stop_pts) else -1.0
    be = e + float(A.COSTS.rt_per_unit(np.array([e]), np.array([float(lot)]))[0])
    peak = e
    for r in range(c0, A.SQ_ROW):
        if np.isfinite(a[r, 3]):
            o = a[r, 0] if np.isfinite(a[r, 0]) else a[r, 3]
            lock = -1.0
            if tgt is not None:
                for reach, keep in A.LADDER:
                    if peak >= e + reach * tgt - 1e-9:
                        lv = max(e + keep * tgt, be)
                        if lv < peak:
                            lock = max(lock, lv)
            rest = max(trig, lock)
            if rest > A.TICK and a[r, 2] <= rest:
                stop = trig >= lock
                return A.sell_fill(min(rest, o), a, r, lot, stop=stop), r, ("stop" if stop else "lock")
            if tgt is not None and r <= A.SQ_ROW - 2 and a[r, 1] >= e + tgt:
                rr, px = A.next_open(a, r + 1)
                return A.sell_fill(px, a, rr, lot), rr, "target"
            peak = max(peak, a[r, 1])
        if idx_tgt is not None and r <= A.SQ_ROW - 2 and side * ((ix[r, 1] if side > 0 else ix[r, 2]) - idx_tgt) >= 0:
            rr, px = A.next_open(a, r + 1)
            return A.sell_fill(px, a, rr, lot), rr, "idx_target"
        if idx_stop is not None and (r + 1) % 5 == 0 and r <= A.SQ_ROW - 2 and side * (ix[r, 3] - idx_stop) <= 0:
            rr, px = A.next_open(a, r + 1)
            return A.sell_fill(px, a, rr, lot), rr, "idx_stop"
    rr, px = A.next_open(a, A.SQ_ROW)
    return A.sell_fill(px, a, rr, lot), rr, "square_off"


def orb_strike(ix, und):
    st = ARM[und]["step"]
    return float(np.floor(ix[9, 3] / st + 0.5) * st)          # the 09:20 5-minute bar's close, half-up (R20 / OrbRules)


def trade(d, c, side, row, rule=None, stop_lv=None, tgt_lv=None):
    """One trade dict, or None when refused (no contract, no print within 3 minutes, premium at or below the floor)."""
    cfg = ARM[c.und]
    strike = orb_strike(c.ix, c.und)
    _, a = A.contract(d, strike, side, same_day_ok=False)
    if a is None or row + 1 >= A.SQ_ROW:
        return None
    c0, px = A.next_open(a, row + 1)
    if c0 > row + 3 or px <= cfg["floor"]:
        return None
    e = A.buy_fill(px, a, c0, d.lot)
    prem = True if rule is None else rule.get("prem_stop", True)
    x, xr, why = sim(a, c.ix, c0, e, cfg, d.lot, side, prem, stop_lv, tgt_lv)
    ch = A.charges(e, x, d.lot)
    hi = a[c0:A.SQ_ROW, 1]
    peak = float(np.nanmax(hi)) if np.isfinite(hi).any() else e
    return dict(und=c.und, day=c.day, side=side, row=row, strike=strike, entry=e, exit=x, exit_row=int(xr), why=why,
                net=(x - e) * d.lot - ch, charges=ch, peak=peak, lot=d.lot, tgt_pts=cfg["tgt"])


def rule_day(rule, d, c, lv=None):
    lv = lv or c.lv
    out = []
    for side, row, edge in signals(rule, c.ix, lv, c.w):
        st, tg = exit_levels(rule, lv, edge, side)
        t = trade(d, c, side, row, rule, st, tg)
        if t:
            t["edge"] = edge
            out.append(t)
    return out


def baseline_day(d, c):
    """Today's ORB = R20's V0: 5-min range 09:15 .. 10:00, a 5-min close beyond it on bars 10:05 .. 13:55, 1 entry a side
    a day, a new decision only from the bar after an exit (r20lib.take), the ORB strike, ORB exits."""
    _, bh, bl, bc = L20.bars5(c.ix)
    p = L20.plan_for(L20.VARIANTS[0], bh, bl, c.atr)
    if p is None:
        return []

    def tr(k, s):
        t = trade(d, c, s, 5 * k + 4)
        return (t, t["exit_row"]) if t else None
    return L20.take(L20.signals(p, bh, bl, bc), p, tr)


# ------------------------------------------------------------------------------------------------ data and scores
_PREP: dict = {}


def prep(und):
    """[(R17 Day, Ctx)] for every session from the 16th (cached in memory)."""
    if und not in _PREP:
        days = S.load_days(und)
        _PREP[und] = [(days[c.i], c) for c in S.contexts(und, days)]
    return _PREP[und]


def cut(und):
    return CFG.holdout_start(und, [c.day for _, c in prep(und)])


def items(period):
    return [(d, c) for u in CFG.TRADE_UNDS for d, c in prep(u) if (c.day < cut(u)) == (period == "design")]


def sessions(period):
    return sorted({c.day for _, c in items(period)})


def run(rid, period, lv_for=None):
    out = []
    for d, c in items(period):
        if rid == BASELINE:
            out += baseline_day(d, c)
        else:
            out += rule_day(RULES[rid], d, c, lv_for(c) if lv_for else None)
    return out


def daily(trades, ss):
    out = {s: 0.0 for s in ss}
    for t in trades:
        if t["day"] in out:
            out[t["day"]] += t["net"]
    return out


def max_dd(x):
    c = np.cumsum(x)
    return float(np.min(c - np.maximum.accumulate(np.concatenate([[0.0], c]))[1:])) if len(c) else 0.0


def big_cut(trades):
    """Trades whose option high reached entry + 2 x target but that were sold below entry + target."""
    big = [t for t in trades if t["peak"] >= t["entry"] + 2 * t["tgt_pts"]]
    cutr = [t for t in big if t["exit"] < t["entry"] + t["tgt_pts"]]
    return dict(big=len(big), cut=len(cutr), left=float(sum((t["peak"] - t["exit"]) * t["lot"] for t in cutr)))


def score(trades, ss):
    dn = daily(trades, ss)
    x = np.array([dn[k] for k in sorted(dn)])
    tr = [t for t in trades if t["day"] in dn]
    tdays = sorted({t["day"] for t in tr})
    tx = np.array([dn[k] for k in tdays])
    t, p = (SS.ttest_1samp(x, 0.0, alternative="greater") if len(x) > 2 and x.std() > 0 else (np.nan, 1.0))
    return dict(trades=len(tr), sessions=len(x), trade_days=len(tdays), net=float(x.sum()),
                per_session=float(x.mean()) if len(x) else 0.0, trades_per_session=len(tr) / len(x) if len(x) else 0.0,
                per_trade=float(np.mean([q["net"] for q in tr])) if tr else 0.0,
                win_trades=float(np.mean([q["net"] > 0 for q in tr])) if tr else np.nan,
                green_trade_days=float((tx > 0).mean()) if len(tx) else np.nan, worst_day=float(x.min()) if len(x) else 0.0,
                best_day=float(x.max()) if len(x) else 0.0, max_dd=max_dd(x), t=float(t), p=float(p), **big_cut(tr))


def blocks(trades, ss, k=4):
    cuts = np.array_split(np.arange(len(ss)), k)
    return [score(trades, [ss[i] for i in c]) | {"from": str(ss[c[0]]), "to": str(ss[c[-1]])} for c in cuts]


def report(trades, period):
    ss = sessions(period)
    out = dict(all=score(trades, ss))
    for u in CFG.TRADE_UNDS:
        su = sorted({c.day for _, c in prep(u) if (c.day < cut(u)) == (period == "design")})
        out[u] = score([t for t in trades if t["und"] == u], su)
    out["blocks"] = blocks(trades, ss)
    out["exits"] = {w: int(sum(1 for t in trades if t["why"] == w)) for w in sorted({t["why"] for t in trades})}
    out["sides"] = {str(s): score([t for t in trades if t["side"] == s], ss)["net"] for s in (1, -1)}
    return out


def bh(pvals: dict, q=BH_Q):
    """Benjamini-Hochberg: {name: (p, q-value, rejected)}."""
    names = sorted(pvals, key=lambda k: pvals[k])
    m = len(names)
    ps = np.array([pvals[k] for k in names])
    qv = np.minimum.accumulate((ps * m / np.arange(1, m + 1))[::-1])[::-1]
    kmax = max([i + 1 for i in range(m) if ps[i] <= (i + 1) / m * q], default=0)
    return {k: (float(ps[i]), float(min(qv[i], 1.0)), i < kmax) for i, k in enumerate(names)}


# ------------------------------------------------------------------------------------------------ twins
def twin_level(rid, actual, n=TWINS, seed=CFG.SEED):
    """Per holdout index-day: the rule's trades with the real levels and with the levels mirrored around P10; each twin
    picks the mirror on a random half of the days."""
    rule = RULES[rid]
    rng = np.random.default_rng([seed, 1])
    real, mir = [], []
    for d, c in items("holdout"):
        real.append(sum(t["net"] for t in rule_day(rule, d, c)))
        mir.append(sum(t["net"] for t in rule_day(rule, d, c, LV.mirror(c.lv))))
    real, mir = np.array(real), np.array(mir)
    pick = rng.random((n, len(real))) < 0.5
    nets = np.where(pick, mir[None, :], real[None, :]).sum(axis=1)
    return float((1 + (nets >= actual - 1e-9).sum()) / (n + 1)), nets, float(mir.sum())


def twin_time(rid, trades, n=TWINS, seed=CFG.SEED):
    """Each holdout trade re-entered at a random decision candle of the rule's window that day (same side, contract,
    exits; index stop / target = the rule's real levels that day)."""
    rule = RULES[rid]
    rng = np.random.default_rng([seed, 2])
    byday = {(c.und, c.day): (d, c) for d, c in items("holdout")}
    tf = rule["tf"]
    k0, k1 = WIN[tf]
    tables = []
    for t in trades:
        d, c = byday[(t["und"], t["day"])]
        st, tg = exit_levels(rule, c.lv, t["edge"], t["side"])
        nets = []
        for k in range(k0, k1 + 1):
            x = trade(d, c, t["side"], EV.row_of(k, tf), rule, st, tg)
            nets.append(x["net"] if x else 0.0)
        tables.append(np.array(nets))
    actual = sum(t["net"] for t in trades)
    tot = np.zeros(n)
    for tb in tables:
        tot += tb[rng.integers(0, len(tb), n)]
    return float((1 + (tot >= actual - 1e-9).sum()) / (n + 1)), tot


class Fills2x(Fills):
    """G6: the liq half-spread ticks doubled."""

    def _ticks(self, vol5_lots, p):
        return 2.0 * super()._ticks(vol5_lots, p)


# ------------------------------------------------------------------------------------------------ runs
def strip(tr):
    return [dict(und=t["und"], day=str(t["day"]), side=t["side"], row=t["row"], net=round(t["net"], 2), why=t["why"],
                 entry=t["entry"], exit=t["exit"], peak=t["peak"]) for t in tr]


def design():
    res = {}
    base = None
    for rid in [BASELINE] + list(RULES):
        tr = run(rid, "design")
        r = report(tr, "design")
        if rid == BASELINE:
            base = r["all"]["net"]
        else:
            r["G1"] = r["all"]["net"] > 0
            r["G2"] = sum(b["net"] > 0 for b in r["blocks"]) >= 3
            r["G7_design"] = r["all"]["net"] > base
        res[rid] = r
        print(rid, {k: round(v, 1) if isinstance(v, float) else v for k, v in r["all"].items()},
              [round(b["net"]) for b in r["blocks"]], flush=True)
    os.makedirs(CFG.RESULTS, exist_ok=True)
    json.dump(S.jsonable(res), open(os.path.join(CFG.RESULTS, "design.json"), "w"), indent=1)
    return res


def prior_pvalues():
    """R20 / R20b's 28 (R20c prior_pvalues.json) + R20c's 12 (R20c results/holdout.json) holdout p-values."""
    r20c = os.path.join(CFG.HERE, "..", "r20c")
    out = dict(json.load(open(os.path.join(r20c, "prior_pvalues.json")))["pvalues"])
    tab = json.load(open(os.path.join(r20c, "results", "holdout.json")))["_bh"]["table"]
    out.update({k: v["p"] for k, v in tab.items() if k.startswith("R20c ")})
    return out


def holdout(n_twins=TWINS):
    if os.path.exists(CFG.FLAG):
        sys.exit("the R22 holdout has already been read once (flag exists)")
    if not os.path.exists(os.path.join(CFG.RESULTS, "design.json")):
        sys.exit("freeze results/design.json first")
    os.makedirs(CFG.OUT, exist_ok=True)
    open(CFG.FLAG, "w").write(str(date.today()))
    des = json.load(open(os.path.join(CFG.RESULTS, "design.json")))
    res, pv = {}, {}
    btr = run(BASELINE, "holdout")
    res[BASELINE] = report(btr, "holdout")
    res[BASELINE]["trades_list"] = strip(btr)
    base = res[BASELINE]["all"]["net"]
    for rid in RULES:
        tr = run(rid, "holdout")
        r = report(tr, "holdout")
        net = r["all"]["net"]
        r["p_t"] = r["all"]["p"]
        r["p_level"], lv, r["mirror_all_net"] = twin_level(rid, net, n_twins)
        r["p_time"], tm = twin_time(rid, tr, n_twins)
        r["twin_level_mean"], r["twin_time_mean"] = float(lv.mean()), float(tm.mean())
        A.FILLS = Fills2x(mode="liq")
        r["net_2x_ticks"] = sum(t["net"] for t in run(rid, "holdout"))
        A.FILLS = Fills(mode="liq")
        r["G1"], r["G2"] = des[rid]["G1"], des[rid]["G2"]
        r["G6"] = r["net_2x_ticks"] > 0
        r["G7"] = bool(des[rid]["G7_design"] and net > base)
        r["trades_list"] = strip(tr)
        pv[f"R22 {rid} G3"], pv[f"R22 {rid} G4"], pv[f"R22 {rid} G5"] = r["p_t"], r["p_level"], r["p_time"]
        res[rid] = r
        print(rid, round(net), r["all"]["trades"], "p", round(r["p_t"], 3), round(r["p_level"], 3), round(r["p_time"], 3), flush=True)
    fam = dict(prior_pvalues(), **pv)
    B = bh(fam)
    for rid in RULES:
        ok = [B[f"R22 {rid} G{g}"][2] for g in (3, 4, 5)]
        res[rid]["q"] = {f"G{g}": B[f"R22 {rid} G{g}"][1] for g in (3, 4, 5)}
        res[rid]["PASS"] = bool(res[rid]["G1"] and res[rid]["G2"] and all(ok) and res[rid]["G6"] and res[rid]["G7"])
    res["_bh"] = dict(m=len(fam), q=BH_Q, rejected=[k for k, v in B.items() if v[2]],
                      table={k: dict(p=v[0], q=v[1]) for k, v in B.items()})
    json.dump(S.jsonable(res), open(os.path.join(CFG.RESULTS, "holdout.json"), "w"), indent=1)
    return res


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "holdout":
        holdout()
    else:
        sys.exit(__doc__)
