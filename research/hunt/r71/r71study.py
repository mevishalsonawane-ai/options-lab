"""R71 study (PREREG.md): eight chart-pattern rules on the index, the 1-ITM option bought, gated as Donchian.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R51's clean stores (once)
    python research/hunt/r71/r71study.py design                # every grid point on design, walk-forward picks
    python research/hunt/r71/r71study.py final                 # the ONE final read (refuses a second), twins, BH
    python research/hunt/r71/r71study.py report                # results/*.json -> results/report.md

Signals: r71rules.py on R51's 5-minute index bars (fam_r44.bars5). Prices: R64's engine (R51's simulator plus the
breakeven step) on R51's Amendment A clean stores. Donchian 20 is priced by the same code (r64arms.donchian_trades) for
the same-days comparison. Trades go to $R71_OUT (never git); results/ holds small JSON and markdown only.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r71cfg as R  # noqa: E402
import r71rules as P  # noqa: E402

TESTS_ORDER = [f"{r}-{k}" for r in P.RULES for k in ("O", "RT")]


# ================================================================================================ statistics (pure)
def max_dd(daily) -> float:
    if not len(daily):
        return 0.0
    eq = np.concatenate([[0.0], np.cumsum(daily)])
    return float((np.maximum.accumulate(eq) - eq).max())


def daily_of(trades: list) -> dict:
    out = defaultdict(float)
    for t in trades:
        out[t["day"]] += t["net"]
    return dict(out)


def stats(trades: list, months: float | None = None) -> dict:
    """Trades, win rate, net, per trade, average win / loss, worst day, max drawdown (daily), % green days (of days
    traded), top-5 share, trades a month, exit reasons."""
    n = len(trades)
    if not n:
        return dict(trades=0, days=0, net=0.0, ex_top5=0.0)
    net = np.array([t["net"] for t in trades], float)
    dl = daily_of(trades)
    dv = np.array([dl[d] for d in sorted(dl)])
    w, lo = net[net > 0], net[net <= 0]
    tot = float(net.sum())
    top5 = float(np.sort(net)[::-1][:5].sum())
    why = defaultdict(int)
    for t in trades:
        why[t["why"]] += 1
    return dict(trades=n, days=len(dl), net=round(tot), per_trade=round(tot / n, 1), win=round(float((net > 0).mean()), 4),
                avg_win=round(float(w.mean())) if len(w) else None, avg_loss=round(float(lo.mean())) if len(lo) else None,
                worst_day=round(float(dv.min())), best_day=round(float(dv.max())), max_dd=round(max_dd(dv)),
                green_days=round(float((dv > 0).mean()), 4), ex_top5=round(tot - top5),
                per_month=round(n / months, 1) if months else None, why=dict(why))


def months_between(lo: date, hi: date) -> float:
    return ((hi - lo).days + 1) / 30.44


def split(trades: list, lo: date, hi: date) -> list:
    return [t for t in trades if lo <= t["day"] <= hi]


def bh(p) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    if not n:
        return p
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out


def wf_picks(grid_trades: dict, orig: str) -> list:
    """R51 section 4: for each fold, the grid point with the best net on every design day before the fold (>= WF_MIN
    trades there), else the original. grid_trades: {point: design trades}. Returns [point per fold]."""
    picks = []
    for a, _ in R.WF_FOLDS:
        best, best_net = None, -np.inf
        for gp, tr in grid_trades.items():
            before = [t for t in tr if t["day"] < a]
            if len(before) < R.WF_MIN:
                continue
            s = sum(t["net"] for t in before)
            if s > best_net:
                best, best_net = gp, s
        picks.append(best or orig)
    return picks


def wf_trades(grid_trades: dict, picks: list) -> list:
    out = []
    for (a, b), gp in zip(R.WF_FOLDS, picks):
        out += split(grid_trades[gp], a, b)
    return out


def final_pick(grid_trades: dict, orig: str) -> str:
    """The grid point with the best whole-design net (>= FINAL_MIN trades), else the original."""
    best, best_net = None, -np.inf
    for gp, tr in grid_trades.items():
        if len(tr) < R.FINAL_MIN:
            continue
        s = sum(t["net"] for t in tr)
        if s > best_net:
            best, best_net = gp, s
    return best or orig


def gates(final: dict, design_net: float, wf_net: float, p, q) -> list:
    fails = []
    if final.get("net", 0) <= 0:
        fails.append("final net <= 0")
    if p is None or p >= R.GATE_P:
        fails.append("twin p >= 0.05")
    if q is None or q > R.GATE_Q:
        fails.append("BH q > 0.10")
    if final.get("trades", 0) < R.GATE_MIN_TRADES:
        fails.append("fewer than 40 trades")
    if final.get("trades", 0) and final.get("ex_top5", 0) <= 0:
        fails.append("top 5 >= net")
    if design_net <= 0:
        fails.append("design net <= 0")
    if wf_net <= 0:
        fails.append("walk-forward net <= 0")
    return fails


def daily_corr(a: list, b: list, sessions: list) -> float | None:
    """Pearson correlation of two books' daily nets over every session (0 on a day with no trade)."""
    if not sessions:
        return None
    da, db = daily_of(a), daily_of(b)
    x = np.array([da.get(d, 0.0) for d in sessions])
    y = np.array([db.get(d, 0.0) for d in sessions])
    if x.std() == 0 or y.std() == 0:
        return None
    return round(float(np.corrcoef(x, y)[0, 1]), 3)


def overlap(trades: list, ref: list, rows: int = R.OVERLAP_ROWS) -> float | None:
    """Share of `trades` with a `ref` trade on the same index, day and side whose entry is within `rows` minutes."""
    if not trades:
        return None
    idx = defaultdict(list)
    for t in ref:
        idx[(t["und"], t["day"], t["side"])].append(t["er"])
    hit = sum(1 for t in trades if any(abs(t["er"] - e) <= rows for e in idx.get((t["und"], t["day"], t["side"]), ())))
    return round(hit / len(trades), 4)


def combined(a: list, b: list) -> dict:
    dl = defaultdict(float)
    for t in a + b:
        dl[t["day"]] += t["net"]
    dv = np.array([dl[d] for d in sorted(dl)])
    return dict(net=round(float(dv.sum())) if len(dv) else 0, max_dd=round(max_dd(dv)),
                worst_day=round(float(dv.min())) if len(dv) else 0)


def index_exit_row(close, start: int, level: float, side: int, sq: int) -> int:
    """XI: the first minute m >= start whose index close is beyond `level` against the trade (calls: below) -> sold at
    m + 1's open; -1 if none before the square-off. NaN minutes trigger nothing."""
    for m in range(start, min(sq, len(close))):
        x = close[m]
        if x == x and ((side > 0 and x < level) or (side < 0 and x > level)):
            return m + 1 if m + 1 < sq else -1
    return -1


# ================================================================================================ data side
_M = {}


def mods():
    """R51's modules on the clean stores, and R64's engine and arms (imported once, in this process)."""
    if not _M:
        os.environ["R51_CLEAN"] = "1"
        for p in (HERE.parent / "r51", HERE.parent / "r64"):
            if str(p) not in sys.path:
                sys.path.insert(0, str(p))
        import r51cfg as K
        import common as C
        import engine as E
        import fam_r44
        import r51data
        import r64arms as A
        import r64engine as G
        assert K.CLEAN, "R71 prices on R51's Amendment A clean stores"
        _M.update(K=K, C=C, E=E, fam_r44=fam_r44, r51data=r51data, A=A, G=G)
    return _M


def exits_of(key: str):
    M = mods()
    s = R.EXITS[key]
    return M["E"].Exits(stop=s["stop"], target=s["target"] if s["target"] is not None else -1.0, steps=tuple(s["steps"]),
                        tmax=s["tmax"], sq_row=M["K"].row(*s["sq"]))


_SIG: dict = {}


def rule_signals(und: str, rule: str, param) -> dict:
    """{day: [(entry row, side 'C'/'P', inval)]} for one index (signal on bar i's close -> entry row 5 (b_i + 1))."""
    key = (und, rule, param)
    if key not in _SIG:
        d, b, o, h, l, c = mods()["fam_r44"].bars5(und)
        out = defaultdict(list)
        for i, s, inval in P.signals(rule, param, d, b, o, h, l, c):
            out[d[i]].append((5 * (int(b[i]) + 1), "C" if s > 0 else "P", float(inval)))
        _SIG[key] = {k: sorted(v) for k, v in out.items()}
    return _SIG[key]


def rule_trades(rule: str, param, ex_key: str, lo: date, hi: date) -> list:
    M = mods()
    K, C, E, G, r51data = M["K"], M["C"], M["E"], M["G"], M["r51data"]
    ex = exits_of(ex_key)
    last = K.row(*R.LAST_ROW_HM)
    rows = []
    for und in K.UNDS:
        sig = rule_signals(und, rule, param)
        days = r51data.load(und)
        for d in C.days_in(und, lo, hi):
            D = days[d]
            if D.expiry or d not in sig:
                continue
            sq = E.sq_row(D, ex)
            cands = []
            for er, side, inval in sig[d]:
                if er < R.FIRST_ROW or er > last:
                    continue
                k = E.strike_for(D, und, side, er - 1, R.MONEY)
                if k is None:
                    continue
                c = dict(er=er, side=side, strike=k, inval=inval)
                if R.EXITS[ex_key]["index_stop"]:
                    c["fx"] = index_exit_row(D.idx[3], er, inval, 1 if side == "C" else -1, sq)
                cands.append(c)
            rows += G.take(und, D, cands, ex, max_per_side=R.MAX_PER_SIDE)
    return rows


def donchian_trades(ex_key: str, lo: date, hi: date) -> list:
    M = mods()
    return M["A"].donchian_trades(M["C"], M["E"], M["K"], M["fam_r44"], M["r51data"], M["G"], exits_of(ex_key), lo, hi)


def twins(trades: list, ex_key: str, n: int, rng) -> np.ndarray:
    """PREREG section 4: each trade replaced by n twins on the same day, index and side, entry minute uniform in
    09:20-15:10, 1-ITM, the same exits; XI: the twin's index stop the same number of points from its entry."""
    M = mods()
    K, E, G, r51data = M["K"], M["E"], M["G"], M["r51data"]
    ex = exits_of(ex_key)
    last = K.row(*R.LAST_ROW_HM)
    tot = np.zeros(n)
    for t in trades:
        D = r51data.load(t["und"])[t["day"]]
        side, sg = t["side"], (1 if t["side"] == "C" else -1)
        rows = rng.integers(R.FIRST_ROW, last + 1, size=n)
        kmap = {r: E.strike_for(D, t["und"], side, r - 1, R.MONEY) for r in np.unique(rows).tolist()}
        fx = None
        if R.EXITS[ex_key]["index_stop"]:
            cl = D.idx[3]
            ref = cl[t["er"] - 1] if np.isfinite(cl[t["er"] - 1]) else np.nan
            dist = abs(ref - t["inval"]) if np.isfinite(ref) else np.nan
            sq = E.sq_row(D, ex)
            fx = []
            for r in rows.tolist():
                p0 = cl[r - 1]
                fx.append(index_exit_row(cl, r, p0 - sg * dist, sg, sq) if np.isfinite(p0) and np.isfinite(dist) else -1)
        tot += G.twin_nets(t["und"], D, [side] * n, [kmap[int(r)] for r in rows], rows, ex, fx)
    return tot


def sessions(lo: date, hi: date) -> list:
    M = mods()
    s = set()
    for u in M["K"].UNDS:
        s.update(M["C"].days_in(u, lo, hi))
    return sorted(s)


def _strip(trades: list) -> list:
    keep = ("und", "day", "side", "strike", "er", "xr", "E", "X", "rE", "rX", "qty", "why", "net", "inval", "peak", "ret")
    return [{k: t[k] for k in keep if k in t} for t in trades]


def _save(name: str, obj):
    R.OUT.mkdir(parents=True, exist_ok=True)
    with open(R.OUT / f"{name}.pkl", "wb") as f:
        pickle.dump(obj, f, protocol=pickle.HIGHEST_PROTOCOL)


def _load(name: str):
    with open(R.OUT / f"{name}.pkl", "rb") as f:
        return pickle.load(f)


def gp_name(param, ex_key: str) -> str:
    return f"{param}|{ex_key}"


def gp_parse(rule: str, name: str):
    p, ex = name.split("|")
    orig = P.RULES[rule][2]
    return type(orig)(p), ex


def _json(obj, path: Path):
    path.write_text(json.dumps(obj, indent=1, default=str) + "\n")


# ================================================================================================ phases
def design() -> dict:
    lo, hi = R.START, R.DESIGN_END
    months = months_between(lo, hi)
    out = dict(rules={}, donchian={}, written=datetime.now(timezone.utc).isoformat(timespec="seconds"))
    ses = sessions(lo, hi)
    don = {}
    for ex_key in ("XB", "XL"):
        tr = donchian_trades(ex_key, lo, hi)
        _save(f"donchian_{ex_key}_design", _strip(tr))
        don[ex_key] = tr
        out["donchian"][ex_key] = dict(design=stats(tr, months), wf=stats(split(tr, R.WF_FOLDS[0][0], hi)))
        print("Donchian", ex_key, out["donchian"][ex_key]["design"]["net"], flush=True)
    for rule, (_, _, orig, grid) in P.RULES.items():
        o_name = gp_name(orig, R.ORIG_EXIT)
        gt = {}
        for param in grid:
            for ex_key in R.EXIT_ORDER:
                tr = rule_trades(rule, param, ex_key, lo, hi)
                gt[gp_name(param, ex_key)] = tr
                _save(f"{rule}_{gp_name(param, ex_key).replace('|', '_')}_design", _strip(tr))
        picks = wf_picks(gt, o_name)
        wf = wf_trades(gt, picks)
        fp = final_pick(gt, o_name)
        res = dict(name=P.NAMES[rule], original=o_name,
                   grid={g: dict(design=stats(tr, months),
                                 folds=[round(sum(t["net"] for t in split(tr, a, b))) for a, b in R.WF_FOLDS])
                         for g, tr in gt.items()},
                   wf_picks=picks, wf=stats(wf), final_pick=fp,
                   corr_donchian=daily_corr(gt[o_name], don["XB"], ses), overlap_donchian=overlap(gt[o_name], don["XB"]))
        out["rules"][rule] = res
        print(rule, "orig", res["grid"][o_name]["design"]["net"], "WF", res["wf"]["net"], "pick", fp, flush=True)
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    _json(out, R.RESULTS / "design.json")
    return out


def final() -> dict:
    """The ONE final read: the 8 originals and the 8 re-tunes' frozen picks, twins, BH over the 16, gates, the double-
    slippage check for every pass, and the same-days comparison with Donchian."""
    dj = R.RESULTS / "design.json"
    if not dj.exists():
        raise SystemExit("run `design` first")
    if R.FINAL_FLAG.exists():
        raise SystemExit("R71's final period was already read once (results/FINAL_READ); a second read is refused")
    R.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    des = json.loads(dj.read_text())
    M = mods()
    M["C"].open_final()
    lo, hi = R.FINAL_START, M["K"].END
    months = months_between(lo, hi)
    ses = sessions(lo, hi)
    out = dict(tests={}, donchian={}, end=str(hi), sessions=len(ses), read=R.FINAL_FLAG.read_text().strip())
    don = {}
    for ex_key in ("XB", "XL"):
        tr = donchian_trades(ex_key, lo, hi)
        _save(f"donchian_{ex_key}_final", _strip(tr))
        don[ex_key] = tr
        out["donchian"][ex_key] = stats(tr, months)
    tests = []
    for rule in P.RULES:
        r = des["rules"][rule]
        for kind, gp in (("O", r["original"]), ("RT", r["final_pick"])):
            tests.append((rule, kind, gp))
    rng_seed = R.SEED
    ps = []
    trades_of = {}
    for rule, kind, gp in tests:
        param, ex_key = gp_parse(rule, gp)
        tr = rule_trades(rule, param, ex_key, lo, hi)
        trades_of[(rule, kind)] = tr
        _save(f"{rule}_{kind}_final", _strip(tr))
        st = stats(tr, months)
        p, tm = None, None
        if tr:
            tw = twins(tr, ex_key, R.TWINS, np.random.default_rng(rng_seed))
            p = round((1 + int((tw >= st["net"]).sum())) / (R.TWINS + 1), 4)
            tm = round(float(tw.mean()))
        ps.append(p if p is not None else 1.0)
        r = des["rules"][rule]
        dnet = r["grid"][gp]["design"]["net"]
        wnet = dnet if kind == "O" else r["wf"]["net"]
        out["tests"][f"{rule}-{kind}"] = dict(rule=rule, kind=kind, setting=gp, final=st, p=p, twin_mean=tm,
                                               design_net=dnet, wf_net=wnet,
                                               corr_donchian=daily_corr(tr, don["XB"], ses),
                                               overlap_donchian=overlap(tr, don["XB"]),
                                               with_donchian=combined(don["XB"], tr))
        print(rule, kind, gp, st["trades"], st["net"], p, flush=True)
    qs = bh(ps)
    for (rule, kind, gp), q in zip(tests, qs):
        t = out["tests"][f"{rule}-{kind}"]
        t["q"] = round(float(q), 4)
        t["fails"] = gates(t["final"], t["design_net"], t["wf_net"], t["p"], t["q"])
        t["verdict"] = "PASS" if not t["fails"] else "FAIL"
        if t["verdict"] == "PASS":                      # Amendment A's check: twice the spread and slippage
            E = M["E"]
            old = E.SLIP
            E.SLIP = E.SLIP2
            try:
                param, ex_key = gp_parse(rule, gp)
                t["slip2"] = stats(rule_trades(rule, param, ex_key, lo, hi), months)
            finally:
                E.SLIP = old
    out["donchian_alone"] = combined(don["XB"], [])
    _json(out, R.RESULTS / "final.json")
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "report":
        import r71report
        r71report.write()
    else:
        raise SystemExit(__doc__)
