"""R93 study (PREREG.md). Two commands, run in this order:

    python -m research.hunt.r93_breadth_donchian_mcx.study design     # design + walk-forward only (any number of runs)
    python -m research.hunt.r93_breadth_donchian_mcx.study holdout    # the locked holdout, ONCE (writes HOLDOUT_READ)

Environment: IRAALGO_LAKE (the Drive lake copy), IRAALGO_CACHE (R53 market cache and R93 cache), R93_PROXY (drivers).
Trades and twin caches stay in the cache folder (never in git); results/ gets small JSON and markdown only.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r53 import common as C53
from research.hunt.r93_breadth_donchian_mcx import cfg as K
from research.hunt.r93_breadth_donchian_mcx import engine as E
from research.hunt.r93_breadth_donchian_mcx import rules as R

MIN = np.timedelta64(1, "m")


# ================================================================================================ data
def _market(sym: str):
    from research.hunt.r53.mcx import data as D
    return D.load_market(sym)


class Series:
    """One family's intraday minute series: ts (datetime64 naive IST), F, seg; m = R53 Market when it has an option book."""

    def __init__(self, fam: str, ts, F, seg, m=None):
        self.fam, self.ts, self.F, self.seg, self.m = fam, np.asarray(ts, "datetime64[ns]"), np.asarray(F, float), \
            np.asarray(seg, np.int64), m


def load_series(fam: str) -> Series:
    sym = K.FAMILIES[fam][1]
    if fam == "ALUMINIUM":
        c = pd.read_parquet(K.LAKE / "fut_1m_mcx_continuous" / "fut_1m_mcx_continuous.parquet")
        c = c[c.symbol.astype(str) == sym].sort_values("ts")
        ts = c.ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None).values
        ex = c.expiry.astype(str).values
        seg = np.r_[0, np.cumsum(ex[1:] != ex[:-1])]
        return Series(fam, ts, c.close.values, seg)
    m = _market(sym)
    return Series(fam, m.ts, m.F, m.seg, m)


def load_driver(fam: str) -> pd.DataFrame | None:
    sym = K.DRIVERS.get(fam)
    if sym is None:
        return None
    p = K.PROXY / f"{sym}.parquet"
    if not p.exists():
        raise FileNotFoundError(f"driver {sym} missing: run fetch_proxy.py into {K.PROXY}")
    d = pd.read_parquet(p).drop_duplicates("t").sort_values("t")
    ts = pd.to_datetime(d.t.values, unit="s") + pd.Timedelta(hours=5, minutes=30)      # UTC minute open -> IST
    return R.driver_bars(ts.values, d.c.values.astype(float))


def load_daily(fam: str) -> pd.DataFrame:
    sym = K.DAILY_SERIES[fam][0]
    d = pd.read_parquet(K.LAKE / "fut_daily_continuous" / "fut_daily_continuous.parquet")
    d = d[(d.symbol.astype(str) == sym) & (d.expiry_code == 0)]
    return R.daily_series(d[["date", "open", "high", "low", "close", "oi"]])


# ================================================================================================ intraday signals
def family_bars(S: Series) -> pd.DataFrame:
    b = R.with_channel(R.bars5(S.ts, S.F, S.seg))
    b["key"] = list(zip(b.day.values, b.start.values))
    b["end"] = b.day.values + (b.start.values + K.BAR_MIN).astype("timedelta64[m]")
    cl = np.array([R.close_min(d) for d in b.day.values])
    b["sq"] = b.day.values + (cl - K.SQ_BEFORE_CLOSE).astype("timedelta64[m]")
    b["last_fill"] = b.day.values + (cl - K.LAST_FILL_BEFORE_CLOSE).astype("timedelta64[m]")
    b["in_win"] = (b.start.values + K.BAR_MIN >= K.FILL_FROM) & (b.end.values <= b.last_fill.values)
    return b


def intraday_confirm(bars: dict, drivers: dict) -> dict:
    """{variant: {family: conf side array aligned with bars[fam]}}."""
    sides = {f: pd.Series(b.side.values, index=pd.MultiIndex.from_tuples(b.key, names=["day", "start"]))
             for f, b in bars.items()}
    br = R.breadth(sides)
    out = {"I-A": {f: b.side.values.astype(np.int8) for f, b in bars.items()},
           "I-B": {f: br[f].values.astype(np.int8) for f in bars},
           "I-C": {f: R.driver_confirm(b, drivers.get(f)) for f, b in bars.items()}}
    return out


# ================================================================================================ trade cache
def sim_bar(fam: str, S: Series, b: pd.DataFrame, r: int, side: int, slip: float = 1.0):
    """One trade from bar row r on side `side`. Futures stop: the bar's channel mid when the bar itself breaks that
    way (the rule's trade); for any other bar (a twin entry) the same distance a break has to its mid: half the
    channel width from the bar's close."""
    _g, _sig, _inst, kind, units, ck = K.FAMILIES[fam]
    end, sq = b.end.values[r], b.sq.values[r]
    if kind == "opt":
        li = int(np.searchsorted(S.m.ts, b.last_ts.values[r]))
        if li >= len(S.m.ts) or S.m.ts[li] != b.last_ts.values[r]:
            return None
        t = E.sim_option(S.m, end, li, side, units, ck, sq, slip)
    else:
        if int(b.side.values[r]) == side:
            mid = float(b.mid.values[r])
        else:
            mid = float(b.c.values[r]) - side * float(b.chi.values[r] - b.clo.values[r]) / 2
        t = E.sim_future(S.ts, S.F, end, side, mid, units, ck, sq, slip)
    if t is None or np.datetime64(t["entry_ts"]) > b.last_fill.values[r]:
        return None
    return t


def all_bar_trades(fam: str, S: Series, b: pd.DataFrame) -> dict:
    """{(bar row, side): trade (1x costs, with net2 = the net at 2x half-spreads) or None} for every in-window bar and
    both sides: the rule trades and the twins come from the same simulations."""
    out = {}
    for r in np.flatnonzero(b.in_win.values):
        for side in (1, -1):
            t = sim_bar(fam, S, b, int(r), side)
            if t is not None:
                t2 = sim_bar(fam, S, b, int(r), side, slip=2.0)
                t["net2"] = t2["net"] if t2 is not None else t["net"]
            out[(int(r), side)] = t
    return out


def take(cands: list, cache: dict) -> list:
    """R72 trade-taking per family and day: one open position a side, at most 2 fills a side a day. cands: (row, side)
    in time order."""
    out, busy, n_side = [], {}, defaultdict(int)
    for r, side in cands:
        t = cache.get((r, side))
        if t is None:
            continue
        d = t["entry_ts"].date()
        if t["entry_ts"] <= busy.get((d, side), pd.Timestamp.min):
            continue
        if n_side[(d, side)] >= K.MAX_PER_SIDE:
            continue
        busy[(d, side)] = t["exit_ts"]
        n_side[(d, side)] += 1
        out.append(dict(t, row=int(r)))
    return out


# ================================================================================================ daily
def daily_confirm(daily: dict) -> dict:
    sides = {f: pd.Series(d.side.values, index=pd.DatetimeIndex(d.date.values)) for f, d in daily.items()}
    br = R.breadth(sides)
    return {"D-A": {f: d.side.values.astype(np.int8) for f, d in daily.items()},
            "D-B": {f: br[f].values.astype(np.int8) for f in daily}}


def daily_trades(fam: str, d: pd.DataFrame, conf: np.ndarray, cache: dict) -> list:
    out, free_from = [], -1
    for i in np.flatnonzero(conf != 0):
        if i < free_from:
            continue
        t = cache.get((int(i), int(conf[i])))
        if t is None:
            continue
        out.append(dict(t, row=int(i)))
        free_from = t["b"]
    return out


def daily_cache(fam: str, d: pd.DataFrame) -> dict:
    sym, units = K.DAILY_SERIES[fam]
    ck = K.FAMILIES[fam][5]
    out = {}
    for i in range(len(d) - 1):
        for side in (1, -1):
            t = E.sim_daily(d, i, side, units, ck)
            if t is not None:
                t["net2"] = E.sim_daily(d, i, side, units, ck, slip=2.0)["net"]
            out[(i, side)] = t
    return out


def make_pools(bars: dict, caches: dict, daily: dict, dcache: dict) -> dict:
    """Twin pools: per (family, day, side) the nets of every in-window bar whose entry fills (intraday); per ("D",
    family, year, side) every session's trade (daily). Unfilled entries are left out (NOTES.md N1): the rule's trades
    are filled trades, and the thin gold / silver books fill about a quarter of the time."""
    pools = {}
    for f, b in bars.items():
        days = b.day.values
        for (r, side), t in caches[f].items():
            if t is not None:
                pools.setdefault((f, pd.Timestamp(days[r]).date(), side), []).append(t["net"])
    for f, d in daily.items():
        for (i, side), t in dcache[f].items():
            if t is not None:
                pools.setdefault(("D", f, pd.Timestamp(d.date.values[i + 1]).year, side), []).append(t["net"])
    return {k: np.asarray(v) for k, v in pools.items()}


# ================================================================================================ build everything
def build(force: bool = False) -> dict:
    p = K.CACHE / "build.pkl"
    if p.exists() and not force:
        with open(p, "rb") as f:
            return pickle.load(f)
    K.CACHE.mkdir(parents=True, exist_ok=True)
    series = {f: load_series(f) for f in K.FAMILIES}
    bars = {f: family_bars(S) for f, S in series.items()}
    drivers = {f: load_driver(f) for f in K.FAMILIES}
    conf = intraday_confirm(bars, drivers)
    caches = {f: all_bar_trades(f, series[f], bars[f]) for f in K.FAMILIES}
    trades = {}
    for v in K.INTRADAY:
        tv = []
        for f in K.FAMILIES:
            c = conf[v][f]
            b = bars[f]
            rows = [int(r) for r in np.flatnonzero((c != 0) & b.in_win.values)]
            for t in take([(r, int(c[r])) for r in rows], caches[f]):
                t.update(fam=f, group=K.FAMILIES[f][0], kind=K.FAMILIES[f][3],
                         ib=int(conf["I-B"][f][t["row"]] != 0), ic=int(conf["I-C"][f][t["row"]] != 0))
                tv.append(t)
        trades[v] = sorted(tv, key=lambda t: t["entry_ts"])
    daily = {f: load_daily(f) for f in K.DAILY_SERIES}
    dconf = daily_confirm(daily)
    dcache = {f: daily_cache(f, d) for f, d in daily.items()}
    for v in K.DAILY:
        tv = []
        for f, d in daily.items():
            for t in daily_trades(f, d, dconf[v][f], dcache[f]):
                t.update(fam=f, group=K.FAMILIES[f][0], kind="fut", ib=int(dconf["D-B"][f][t["row"]] != 0), ic=0)
                tv.append(t)
        trades[v] = sorted(tv, key=lambda t: t["entry_ts"])
    pools = make_pools(bars, caches, daily, dcache)
    info = {"bars": {f: dict(n=int(len(b)), first=str(b.day.min())[:10], last=str(b.day.max())[:10],
                             sessions=int(b.day.nunique()), signals=int((b.side != 0).sum()))
                     for f, b in bars.items()},
            "drivers": {f: (None if dv is None else dict(bars=int(len(dv)), first=str(dv.t.min()), last=str(dv.t.max())))
                        for f, dv in drivers.items()},
            "daily": {f: dict(rows=int(len(d)), rolls=int(d.roll.sum()), first=str(d.date.min())[:10],
                              last=str(d.date.max())[:10]) for f, d in daily.items()},
            "expiry_days": {f: [str(x)[:10] for x in S.m.exp_days] if S.m is not None else None
                            for f, S in series.items()}}
    out = dict(trades=trades, pools=pools, info=info)
    with open(p, "wb") as fh:
        pickle.dump(out, fh, protocol=pickle.HIGHEST_PROTOCOL)
    return out


# ================================================================================================ statistics
def part(trades: list, p) -> list:
    return [t for t in trades if K.in_period(t["entry_ts"].date(), p)]


def summary(trades: list) -> dict:
    s = C53.summary([dict(entry_ts=t["entry_ts"], net=t["net"]) for t in trades])
    if trades:
        df = pd.DataFrame(trades)
        mon = df.groupby(df.entry_ts.dt.strftime("%Y-%m")).net.sum()
        x = np.sort(df.net.values)
        fam = df.groupby("fam").net.sum()
        s.update(gross=float(df.gross.sum()), cost=float(df.cost.sum()), net_2x_slip=float(df.net2.sum()),
                 worst_month=float(mon.min()), worst_month_name=str(mon.idxmin()), months=int(len(mon)),
                 months_below_stop=int((mon < K.STOP_MONTH).sum()), dd_beyond_40k=bool(s["max_dd"] > K.STOP_DD),
                 net_wo_top5=float(x[:-5].sum()) if len(x) > 5 else float("nan"),
                 net_wo_best_family=float(df.net.sum() - fam.max()) if len(fam) > 1 else float("nan"),
                 best_family=str(fam.idxmax()), exits=df.why.value_counts().to_dict(),
                 stale_exits=int(df.stale.sum()), calls=int((df.side > 0).sum()), puts=int((df.side < 0).sum()),
                 net_calls=float(df[df.side > 0].net.sum()), net_puts=float(df[df.side < 0].net.sum()))
    return s


def breakdown(trades: list) -> dict:
    out = {"by_family": {}, "by_group": {}}
    for f in sorted({t["fam"] for t in trades}):
        out["by_family"][f] = summary([t for t in trades if t["fam"] == f])
    for g in ("metals", "energy"):
        for kind in ("opt", "fut"):
            tt = [t for t in trades if t["group"] == g and t["kind"] == kind]
            if tt:
                out["by_group"][f"{g}_{'options' if kind == 'opt' else 'futures'}"] = summary(tt)
    return out


def confirmed_split(trades: list, flag: str) -> dict:
    """Breadth question: the plain variant's trades split by whether the filtered variant confirms the same bar."""
    out = {}
    for name, sel in (("confirmed", 1), ("unconfirmed", 0)):
        tt = [t for t in trades if t[flag] == sel]
        s = summary(tt)
        out[name] = dict(trades=s["trades"], win_rate=s["win_rate"], net=s["net"],
                         net_per_trade=(s["net"] / s["trades"]) if s["trades"] else None)
        out[name]["gross_per_trade"] = float(np.mean([t["gross"] for t in tt])) if tt else None
        for g in ("metals", "energy"):
            gg = [t for t in tt if t["group"] == g]
            out[name][g] = dict(trades=len(gg), net=float(sum(t["net"] for t in gg)),
                                net_per_trade=(float(np.mean([t["net"] for t in gg])) if gg else None))
        # per family (descriptive, added before the holdout read: the families' mix differs between the two sets)
        out[name]["by_family"] = {
            f: dict(trades=len(ff), gross_per_trade=float(np.mean([t["gross"] for t in ff])),
                    net_per_trade=float(np.mean([t["net"] for t in ff])), win_rate=float(np.mean([t["net"] > 0 for t in ff])))
            for f in sorted({t["fam"] for t in tt}) for ff in [[t for t in tt if t["fam"] == f]]}
    return out


def twin_totals(trades: list, pools: dict, daily: bool, n: int = K.TWINS, seed: int = K.SEED) -> np.ndarray:
    """Random-entry twins (PREREG s7): every trade replaced by a random in-window bar of the same family, day and side
    (daily: a random session of the same calendar year), same instrument and exits; n twin totals."""
    rng = np.random.default_rng(seed)
    tot = np.zeros(n)
    for t in trades:
        key = ("D", t["fam"], t["entry_ts"].year, t["side"]) if daily else (t["fam"], t["entry_ts"].date(), t["side"])
        pool = pools.get(key)
        if pool is None or not len(pool):
            continue
        tot += pool[rng.integers(len(pool), size=n)]
    return tot


def twin_p(net: float, tw: np.ndarray) -> float:
    return float((1 + np.sum(tw >= net)) / (len(tw) + 1))


def walk_forward(trades: dict, variants, design, folds) -> dict:
    per = {v: [float(sum(t["net"] for t in part(trades[v], f))) for f in folds] for v in variants}
    picks, oos = [], 0.0
    for a, b in folds:
        before = {v: sum(t["net"] for t in part(trades[v], (design[0], date.fromordinal(a.toordinal() - 1))))
                  for v in variants}
        best = max(before.values())
        pick = sorted(v for v, s in before.items() if s == best)[0]
        picks.append(pick)
        oos += sum(t["net"] for t in part(trades[pick], (a, b)))
    return {"fold_net": per, "fold_sum": {v: float(sum(x)) for v, x in per.items()}, "selection_picks": picks,
            "selection_oos": float(oos), "folds": [[str(a), str(b)] for a, b in folds]}


def gates(h: dict, p: float, q: float, design_net: float, wf: float) -> dict:
    g = {"holdout_net_pos": h["net"] > 0, "trades40": h["trades"] >= K.GATE_MIN_TRADES,
         "twin_p": p < K.GATE_P, "bh_q": q <= K.GATE_Q, "design_pos": design_net > 0, "wf_pos": wf > 0,
         "wo_top5_pos": bool(np.nan_to_num(h.get("net_wo_top5", np.nan), nan=-1) > 0),
         "wo_best_family_pos": bool(np.nan_to_num(h.get("net_wo_best_family", np.nan), nan=-1) > 0),
         "net_2x_slip_pos": h.get("net_2x_slip", -1) > 0}
    g["PASS"] = all(g.values())
    return g


def _save(name: str, obj) -> None:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str, sort_keys=True))


def run_design() -> dict:
    B = build()
    T, P = B["trades"], B["pools"]
    out = {"info": B["info"], "variants": {}}
    for v in K.VARIANTS:
        daily = v in K.DAILY
        dp = K.D_DESIGN if daily else K.I_DESIGN
        tt = part(T[v], dp)
        tw = twin_totals(tt, P, daily)
        s = summary(tt)
        out["variants"][v] = dict(label=K.LABEL[v], design=s, design_breakdown=breakdown(tt),
                                  design_twin_mean=float(tw.mean()), design_twin_p=twin_p(s["net"], tw))
    out["walk_forward_intraday"] = walk_forward(T, K.INTRADAY, K.I_DESIGN, K.I_FOLDS)
    out["walk_forward_daily"] = walk_forward(T, K.DAILY, K.D_DESIGN, K.D_FOLDS)
    out["breadth_split_design"] = {
        "I-A by I-B": confirmed_split(part(T["I-A"], K.I_DESIGN), "ib"),
        "I-A by I-C": confirmed_split(part(T["I-A"], K.I_DESIGN), "ic"),
        "D-A by D-B": confirmed_split(part(T["D-A"], K.D_DESIGN), "ib")}
    _save("design.json", out)
    return out


def run_holdout() -> dict:
    if K.HOLDOUT_FLAG.exists():
        raise RuntimeError(f"the locked holdout was already read ({K.HOLDOUT_FLAG.read_text().strip()}); not again")
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    K.HOLDOUT_FLAG.write_text(pd.Timestamp.now(tz="UTC").isoformat() + "\n")
    B = build()
    T, P = B["trades"], B["pools"]
    D = json.loads((K.RESULTS / "design.json").read_text())
    out = {"variants": {}}
    ps = {}
    for v in K.VARIANTS:
        daily = v in K.DAILY
        tt = part(T[v], K.D_HOLDOUT if daily else K.I_HOLDOUT)
        tw = twin_totals(tt, P, daily)
        s = summary(tt)
        ps[v] = twin_p(s["net"], tw)
        out["variants"][v] = dict(label=K.LABEL[v], holdout=s, holdout_breakdown=breakdown(tt),
                                  twin_mean=float(tw.mean()), twin_p=ps[v])
    qs = C53.bh(ps)
    for v in K.VARIANTS:
        wfk = "walk_forward_daily" if v in K.DAILY else "walk_forward_intraday"
        o = out["variants"][v]
        o["bh_q"] = qs[v]
        o["gates"] = gates(o["holdout"], ps[v], qs[v], D["variants"][v]["design"]["net"], D[wfk]["fold_sum"][v])
    out["breadth_split_holdout"] = {
        "I-A by I-B": confirmed_split(part(T["I-A"], K.I_HOLDOUT), "ib"),
        "I-A by I-C": confirmed_split(part(T["I-A"], K.I_HOLDOUT), "ic"),
        "D-A by D-B": confirmed_split(part(T["D-A"], K.D_HOLDOUT), "ib")}
    _save("holdout.json", out)
    tr = pd.DataFrame([dict(variant=v, **{k: t[k] for k in ("fam", "entry_ts", "exit_ts", "side", "fill", "exit",
                                                              "why", "gross", "cost", "net", "ib", "ic")})
                       for v in K.VARIANTS for t in part(T[v], K.D_HOLDOUT if v in K.DAILY else K.I_HOLDOUT)])
    tr.to_csv(K.CACHE / "holdout_trades.csv", index=False)       # cache only, never in git
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd == "build":
        build(force=True)
    elif cmd == "design":
        run_design()
    elif cmd == "holdout":
        run_holdout()
    else:
        raise SystemExit(__doc__)
