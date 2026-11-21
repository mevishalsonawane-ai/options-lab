"""R202 study (PREREG.md): 21 price-action patterns on the index's 5-minute bars, each bought as a 1-ITM option, with the
plain exit (X = R71's XB) and the arms' V5 (V = XL + breakeven at +10% + R186's L500 step lock).

    python3 -I research/hunt/r202_price_action25/r202study.py design     # signals, design pricing, twins -> results/design.json
    python3 -I research/hunt/r202_price_action25/r202study.py holdout    # read ONCE -> results/holdout.json (guarded)
    python3 -I research/hunt/r202_price_action25/r202study.py decide     # the gate -> results/decision.json

Reuses R51's engine (run_day, twin_totals, strike_for, charges, R17 fills), R186's price / lock_levels and R193's stats,
twin p, normal p and BH, unchanged. Signal source of every rule: the index. Plain-English notes, 21 Nov 2026.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import datetime, timezone
from multiprocessing import get_context
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r202cfg as K  # noqa: E402  (environment first)

sys.path.insert(0, str(K.HUNT / "r193_arms_nov24"))
import numpy as np  # noqa: E402

import engine as E  # noqa: E402  (hunt/r51/engine.py)
import r51cfg as K51  # noqa: E402
import r51data  # noqa: E402
import r186engine as Z  # noqa: E402
import r193lib as G  # noqa: E402
import r202pat as P  # noqa: E402

LADDER_L25 = ((0.30, np.nan), (0.60, 0.30), (1.00, 0.60), (0.25, 0.05))
XB = E.Exits(stop=0.15, target=0.30, tmax=30, sq_row=K51.row(15, 15))
XL = E.Exits(stop=0.15, target=-1.0, steps=LADDER_L25, tmax=30, sq_row=K51.row(15, 15))
TESTS = [(p, x) for p in P.PATTERNS for x in K.EXITS]
assert len(TESTS) == K.N_TESTS


def boot():
    k = r51data.K
    k.WARM_START, k.START, k.END = K.STORE


def tid(p, x) -> str:
    return f"{p}-{x}"


# ================================================================================================ signals
def signals(und: str, days: dict, lo, hi) -> dict:
    """{day: {pattern: [(er, side)]}} for option days in [lo, hi], not the index's expiry day."""
    ks = sorted(days)
    out = {}
    for i, d in enumerate(ks):
        if not (lo <= d <= hi):
            continue
        D = days[d]
        if D.atm is None or D.expiry:
            continue
        prev = ks[max(0, i - K.LEVEL_SESS):i]
        out[d] = P.day_signals([P.bars5(days[x].idx) for x in prev + [d]])
    return out


# ================================================================================================ pricing and the book
def price_day(und: str, D, sigs: list, x: str) -> list:
    """Price each signal alone: [trade dict or None] in signal order."""
    cands = []
    for er, side in sigs:
        k = E.strike_for(D, und, side, er - 1, K.MONEY)
        cands.append(None if k is None else dict(side=side, strike=k, er=er))
    live = [c for c in cands if c is not None]
    if not live:
        return [None] * len(cands)
    if x == "X":
        res = E.run_day(und, D, [c["side"] for c in live], [c["strike"] for c in live], [c["er"] for c in live], XB)
    else:
        res = Z.price(und, D, live, XL, 1, lock="L500")
    it = iter(res)
    return [next(it) if c is not None else None for c in cands]


def trades_of(und: str, days: dict, sig: dict, lo, hi) -> dict:
    """{test id: [trade]} for one index: every pattern x exit, the one-position book applied."""
    out = defaultdict(list)
    for d in sorted(sig):
        if not (lo <= d <= hi):
            continue
        D = days[d]
        for p in P.PATTERNS:
            s = sig[d].get(p, [])
            if not s:
                continue
            for x in K.EXITS:
                pr = price_day(und, D, s, x)
                take = P.book(s, [t["xr"] if t else None for t in pr], K.MAX_DAY)
                for i in take:
                    t = pr[i]
                    out[tid(p, x)].append(dict(und=und, day=d, side=t["side"], er=int(s[i][0]), xr=int(t["xr"]),
                                               net=float(t["net"]), why=t["why"]))
    return out


# ================================================================================================ twins
_ROWNET: dict = {}


def row_nets(und: str, D, side: str, x: str) -> np.ndarray:
    """The net of a trade entered at each row TWIN_FIRST .. TWIN_LAST of this day and side (strike from the minute
    before, the exit x; no fill or no strike = 0). Each candidate is priced alone, so caching these once and summing
    the twins' draws gives exactly what pricing every twin separately would (engine.twin_totals / R186's price)."""
    key = (und, D.d, side, x)
    if key not in _ROWNET:
        rows = list(range(K.TWIN_FIRST, K.TWIN_LAST + 1))
        net = np.zeros(len(rows))
        cands, pos = [], []
        for j, r in enumerate(rows):
            k = E.strike_for(D, und, side, r - 1, K.MONEY)
            if k is not None:
                cands.append(dict(side=side, strike=k, er=r))
                pos.append(j)
        if cands:
            if x == "X":
                res = E.run_day(und, D, [c["side"] for c in cands], [c["strike"] for c in cands],
                                [c["er"] for c in cands], XB)
            else:
                res = Z.price(und, D, cands, XL, 1, lock="L500")
            for j, t in zip(pos, res):
                net[j] = t["net"] if t is not None else 0.0
        _ROWNET[key] = net
    return _ROWNET[key]


def twin_totals(und: str, days: dict, plan: list, x: str, n: int, rng) -> np.ndarray:
    """Random-entry twins: the same (day, side, count), entry rows uniform in TWIN_FIRST .. TWIN_LAST."""
    tot = np.zeros(n)
    for d, side, cnt, first, last in plan:
        rows = rng.integers(first, last + 1, size=(n, cnt))
        tot += row_nets(und, days[d], side, x)[rows - K.TWIN_FIRST].sum(axis=1)
    return tot


def twins_of(und: str, days: dict, trades: dict) -> dict:
    out = {}
    for ti, (p, x) in enumerate(TESTS):
        tr = trades.get(tid(p, x), [])
        cnt = defaultdict(int)
        for t in tr:
            cnt[(t["day"], t["side"])] += 1
        plan = [(d, s, c, K.TWIN_FIRST, K.TWIN_LAST) for (d, s), c in sorted(cnt.items())]
        rng = np.random.default_rng([K.SEED, K.UNDS.index(und), ti])
        out[tid(p, x)] = twin_totals(und, days, plan, x, K.TWINS, rng) if plan else np.zeros(K.TWINS)
    return out


# ================================================================================================ workers
def _design_worker(und: str) -> str:
    boot()
    days = r51data.load(und)
    sig = signals(und, days, K.DESIGN[0], K.HOLDOUT[1])
    (K.OUT / f"sig_{und}.pkl").write_bytes(pickle.dumps(sig, protocol=pickle.HIGHEST_PROTOCOL))
    tr = trades_of(und, days, sig, *K.DESIGN)
    tw = twins_of(und, days, tr)
    (K.OUT / f"design_{und}.pkl").write_bytes(pickle.dumps(dict(trades=dict(tr), twins=tw),
                                                            protocol=pickle.HIGHEST_PROTOCOL))
    return und


def _holdout_worker(und: str) -> str:
    boot()
    days = r51data.load(und)
    sig = pickle.loads((K.OUT / f"sig_{und}.pkl").read_bytes())
    tr = trades_of(und, days, sig, *K.HOLDOUT)
    (K.OUT / f"holdout_{und}.pkl").write_bytes(pickle.dumps(dict(trades=dict(tr)), protocol=pickle.HIGHEST_PROTOCOL))
    return und


def run_all(fn, procs: int = 3):
    K.OUT.mkdir(parents=True, exist_ok=True)
    with get_context("spawn").Pool(procs, maxtasksperchild=1) as pool:
        for u in pool.imap_unordered(fn, K.UNDS):
            print("done", u, datetime.now().strftime("%H:%M:%S"), flush=True)


def gather(kind: str) -> tuple:
    trades, twins = defaultdict(list), defaultdict(lambda: np.zeros(K.TWINS))
    for u in K.UNDS:
        z = pickle.loads((K.OUT / f"{kind}_{u}.pkl").read_bytes())
        for k, v in z["trades"].items():
            trades[k] += v
        for k, v in z.get("twins", {}).items():
            twins[k] = twins[k] + v
    for k in trades:
        trades[k].sort(key=lambda t: (t["day"], t["er"], t["und"]))
    return trades, twins


# ================================================================================================ statistics
def stats(tr: list, lo, hi) -> dict:
    s = G.stats([t for t in tr if lo <= t["day"] <= hi], lo, hi)
    s.pop("months", None)
    s["by_und"] = {u: round(sum(t["net"] for t in tr if t["und"] == u and lo <= t["day"] <= hi)) for u in K.UNDS}
    return s


def design_block(tr: list, tw: np.ndarray) -> dict:
    d = stats(tr, *K.DESIGN)
    folds = {f: stats(tr, *w) for f, w in K.FOLDS.items()}
    for f in folds:
        folds[f].pop("by_und", None)
    actual = d["net"]
    return dict(design=d, folds=folds, folds_pos=sum(folds[f]["net"] > 0 for f in folds),
                twin_p=G.twin_p(actual, tw), normal_p=G.normal_p(actual, tw),
                twin_mean=round(float(np.mean(tw))), twin_sd=round(float(np.std(tw, ddof=1))))


def design_gate(b: dict) -> list:
    d = b["design"]
    f = []
    if d["trades"] < K.MIN_DESIGN:
        f.append(f"design {d['trades']} trades < {K.MIN_DESIGN}")
    if not d["net"] > 0:
        f.append(f"design net {d['net']:,} not > 0")
    if not d["net_wo_top5"] > 0:
        f.append(f"design net without top 5 {d['net_wo_top5']:,} not > 0")
    if b["folds_pos"] < K.MIN_FOLDS_POS:
        f.append(f"{b['folds_pos']} of 5 folds positive (< {K.MIN_FOLDS_POS})")
    if not b["twin_p"] < K.GATE_P:
        f.append(f"twin p {b['twin_p']:.3f} >= {K.GATE_P}")
    if not b["q"] <= K.GATE_Q:
        f.append(f"BH q {b['q']:.3g} > {K.GATE_Q}")
    if d["worst_month"]["net"] < K.MONTH_CAP:
        f.append(f"design worst month {d['worst_month']['net']:,} < {K.MONTH_CAP:,.0f}")
    if d["max_dd"] > K.ACC_DD:
        f.append(f"design max drawdown {d['max_dd']:,} > {K.ACC_DD:,.0f}")
    return f


def holdout_gate(h: dict) -> list:
    f = []
    if h["trades"] < K.MIN_HOLDOUT:
        f.append(f"holdout {h['trades']} trades < {K.MIN_HOLDOUT}")
    if not h["net"] > 0:
        f.append(f"holdout net {h['net']:,} not > 0")
    if h["worst_month"]["net"] < K.MONTH_CAP:
        f.append(f"holdout worst month {h['worst_month']['net']:,} < {K.MONTH_CAP:,.0f}")
    if h["max_dd"] > K.ACC_DD:
        f.append(f"holdout max drawdown {h['max_dd']:,} > {K.ACC_DD:,.0f}")
    return f


def _meta() -> dict:
    return dict(written=datetime.now(timezone.utc).isoformat(), lots=1, money=K.MONEY,
                data="Drive lake copy of 21 Nov 2026 (~/.cache/iraalgo/r202/lake); R51 clean stores 1 Dec 2021 - 13 Nov 2026",
                m_total=K.M_TOTAL, twins=K.TWINS)


# ================================================================================================ commands
def cmd_design():
    run_all(_design_worker)
    trades, twins = gather("design")
    out = {"_meta": _meta()}
    for p, x in TESTS:
        k = tid(p, x)
        out[k] = design_block(trades.get(k, []), twins[k])
        out[k].update(name=P.NAMES[p], exit=x)
    ks = [tid(p, x) for p, x in TESTS]
    q_all = G.bh_total([out[k]["normal_p"] for k in ks], K.M_TOTAL)
    q_own = G.bh_total([out[k]["normal_p"] for k in ks], len(ks))
    for k, qa, qo in zip(ks, q_all, q_own):
        out[k]["q"], out[k]["q_r202"] = qa, qo
        out[k]["design_fails"] = design_gate(out[k])
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    print("design written", flush=True)


def cmd_holdout():
    if K.HOLDOUT_FLAG.exists():
        sys.exit("the holdout was already read (results/HOLDOUT_READ); it is read once")
    if not (K.RESULTS / "design.json").exists():
        sys.exit("design.json first")
    K.HOLDOUT_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    run_all(_holdout_worker)
    trades, _ = gather("holdout")
    out = {"_meta": _meta()}
    for p, x in TESTS:
        k = tid(p, x)
        out[k] = stats(trades.get(k, []), *K.HOLDOUT)
    (K.RESULTS / "holdout.json").write_text(json.dumps(out, indent=1, default=str))
    print("holdout written", flush=True)


def cmd_decide():
    d = json.loads((K.RESULTS / "design.json").read_text())
    h = json.loads((K.RESULTS / "holdout.json").read_text())
    out = {"_meta": _meta()}
    for p, x in TESTS:
        k = tid(p, x)
        fails = d[k]["design_fails"] + holdout_gate(h[k])
        out[k] = dict(name=P.NAMES[p], exit=x, passes=not fails, fails=fails)
    out["_passed"] = [k for k in out if not k.startswith("_") and out[k]["passes"]]
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    print("passed:", out["_passed"], flush=True)


if __name__ == "__main__":
    {"design": cmd_design, "holdout": cmd_holdout, "decide": cmd_decide}.get(
        (sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
