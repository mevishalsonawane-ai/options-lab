"""R131 (PREREG.md): the 15-minute structure trend on its own (stop-and-reverse on wick breaks), with R123's network as a
filter. Signal on the index (wicks only); the option is what is bought.

    python research/hunt/r131_trend15/r131.py design    # -> results/design.json
    python research/hunt/r131_trend15/r131.py holdout   # read once -> results/holdout.json
    python research/hunt/r131_trend15/r131.py nifty     # NIFTY index 2017-2020, points only -> results/nifty_2017_2020.json
"""
from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r130_sweep_mss_fvg"))
import r130 as R  # noqa: E402
import r130enh as E  # noqa: E402

K, D3 = R.K, R.D3
N, TICK = R.N, R.TICK
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R131_OUT", K.K3.CACHE_ROOT / "r131")).expanduser()
PREDS = Path(os.environ.get("R123_PREDS", K.K3.CACHE_ROOT / "r123")).expanduser()
SWING_K = 2
FIRST_ENTRY, LAST_ENTRY = K.row(9, 30), K.row(14, 30)
TWINS = 200
SEED = 131
VERSIONS = {
    "T1": dict(itm=0, h60=False, target=False, nn=None, label="the 15-minute trend as is (ATM)"),
    "T2": dict(itm=0, h60=True, target=False, nn=None, label="+ the 60-minute structure agrees"),
    "T3": dict(itm=1, h60=False, target=False, nn=None, label="1-in-the-money option"),
    "T4": dict(itm=0, h60=False, target=True, nn=None, label="+ a 1R target"),
    "T5": dict(itm=0, h60=False, target=False, nn="dir", label="+ R123 direction agrees (P(up in 60 min))"),
    "T6": dict(itm=0, h60=False, target=False, nn="ev", label="+ R123 expected value >= Rs 0 for the side"),
}
GATES = dict(design_n=300, folds=3, q=0.10, holdout_n=50, win=0.65, dd=-40000.0, month=-13000.0)


# ------------------------------------------------------------------------------------------------ breaks
@dataclass
class Break:
    d: int          # session index
    r: int          # the minute whose wick broke the level
    side: int       # +1 up, -1 down
    opp: float      # the opposite swing level known at that minute (for the 1R target)


def breaks(g, tf: int = 15, k: int = SWING_K) -> list[Break]:
    """Every wick break of the latest known tf-minute swing high / low, minute by minute (PREREG section 1). A swing is
    known from the minute after the bar k bars later ends; each level breaks once; a minute that breaks both is
    skipped (both levels are spent)."""
    B = R.make_bars(g.o, g.h, g.l, g.c, tf)
    known = []                                           # (day, row known from, kind, level)
    for i in R.swing_highs(B.h, k):
        j = i + k
        if j < len(B.day):
            known.append((int(B.day[j]), int(B.end[j]) + 1, 1, float(B.h[i])))
    for i in R.swing_highs(-B.l, k):
        j = i + k
        if j < len(B.day):
            known.append((int(B.day[j]), int(B.end[j]) + 1, -1, float(B.l[i])))
    known.sort(key=lambda x: (x[0], x[1]))
    out, kp = [], 0
    sh = sl = None
    sh_ok = sl_ok = False
    D = len(g.days)
    for d in range(D):
        hh, ll = g.h[d], g.l[d]
        for r in range(N):
            while kp < len(known) and (known[kp][0], known[kp][1]) <= (d, r):
                _, _, kind, lv = known[kp]
                if kind > 0:
                    sh, sh_ok = lv, True
                else:
                    sl, sl_ok = lv, True
                kp += 1
            if not (np.isfinite(hh[r]) and np.isfinite(ll[r])):
                continue
            up = sh_ok and hh[r] >= sh + TICK
            dn = sl_ok and ll[r] <= sl - TICK
            if up:
                sh_ok = False
            if dn:
                sl_ok = False
            if up and dn:
                continue
            if up:
                out.append(Break(d, r, 1, sl if sl is not None else np.nan))
            elif dn:
                out.append(Break(d, r, -1, sh if sh is not None else np.nan))
    return out


def state_grid(g, tf: int) -> np.ndarray:
    """days x N: the direction of the latest tf-minute structure break, known from the minute after it."""
    st = np.zeros((len(g.days), N), dtype=np.int8)
    cur, last = 0, (0, 0)
    for b in breaks(g, tf):
        _fill(st, last, (b.d, b.r + 1), cur)
        cur, last = b.side, (b.d, b.r + 1)
    _fill(st, last, (len(g.days) - 1, N), cur)
    return st


def _fill(st, a, b, v):
    (d0, r0), (d1, r1) = a, b
    if d0 == d1:
        st[d0, r0:r1] = v
        return
    st[d0, r0:] = v
    st[d0 + 1:d1] = v
    st[d1, :r1] = v


# ------------------------------------------------------------------------------------------------ R123 network filter
class NN:
    """R123 N1's out-of-sample predictions by (index, day, decision row): pdir60 and the best EV per side."""

    def __init__(self, und: str, part: str):
        fn = "wf_rows_preds_N1.parquet" if part == "design" else "holdout_rows_preds_N1.parquet"
        cols = ["und", "day", "row", "pdir60"] + [f"ev_{j}" for j in range(16)]
        P = pd.read_parquet(PREDS / fn, columns=cols, filters=[("und", "==", und)])
        P["day"] = pd.to_datetime(P.day).dt.date
        ev = P[[f"ev_{j}" for j in range(16)]].values
        P["ev_c"] = np.nanmax(ev[:, 0::2], axis=1)            # j = 2 x cell + side; side 0 = call
        P["ev_p"] = np.nanmax(ev[:, 1::2], axis=1)
        self.by = {(d, int(r)): (float(a), float(b), float(c))
                   for d, r, a, b, c in zip(P.day, P.row, P.pdir60, P.ev_c, P.ev_p)}
        self.rows = sorted({int(r) for r in P.row})

    def at(self, day, entry: int):
        """The latest decision row t with t + 1 <= entry (a decision at row t acts at t + 1)."""
        cands = [t for t in self.rows if t + 1 <= entry]
        if not cands:
            return None
        return self.by.get((day, cands[-1]))

    def ok(self, kind: str, day, entry: int, side: int) -> bool:
        x = self.at(day, entry)
        if x is None or not all(np.isfinite(x)):
            return False
        pdir, evc, evp = x
        if kind == "dir":
            return pdir >= 0.5 if side > 0 else pdir <= 0.5
        return (evc if side > 0 else evp) >= 0.0


# ------------------------------------------------------------------------------------------------ trading the breaks
def trade_breaks(g, brks: list[Break], cfg: dict, blocked: set, keep, h60=None, nn=None, last_row=K.K3.EXIT_LAST):
    """Stop-and-reverse on the breaks (PREREG section 1): returns trades (index side) with entry / exit rows."""
    out = []
    pos = None

    def close(p, xr, why, xpx):
        ref = p["ref"]
        out.append(dict(p, exit=xr, why=why, pts=float((xpx - ref) * p["side"]),
                        R=float((xpx - ref) * p["side"] / p["risk"]) if p["risk"] > 0 else np.nan))

    def settle(until_d, until_r):
        """Close the open position by its target or 15:20 if that comes before (until_d, until_r)."""
        nonlocal pos
        if pos is None:
            return
        d = pos["d"]
        if pos.get("tgt_exit") is not None and (d, pos["tgt_exit"]) < (until_d, until_r):
            close(pos, pos["tgt_exit"], "target", pos["tgt"])
            pos = None
            return
        if (d, last_row) < (until_d, until_r):
            fin = [r for r in range(pos["entry"], last_row + 1) if np.isfinite(g.c[d][r])]
            if fin:
                close(pos, fin[-1], "time", float(g.c[d][fin[-1]]))
            pos = None

    def can_enter(d, e, side):
        if d in blocked or not (FIRST_ENTRY <= e <= LAST_ENTRY) or not np.isfinite(g.o[d][e]):
            return False
        if h60 is not None and h60[d, e] != side:
            return False
        if nn is not None and not nn[0].ok(nn[1], g.days[d], e, side):
            return False
        return True

    def open_(b, e):
        nonlocal pos
        d = b.d
        ref = float(g.o[d][e])
        risk = abs(ref - b.opp) if np.isfinite(b.opp) else np.nan
        pos = dict(d=d, day=g.days[d], side=b.side, entry=e, ref=ref, risk=risk)
        if cfg["target"] and np.isfinite(risk) and risk > 0:
            tgt = ref + b.side * risk
            hh, ll = (g.h[d], g.l[d])
            seg = hh[e:last_row + 1] >= tgt if b.side > 0 else ll[e:last_row + 1] <= tgt
            hit = np.flatnonzero(np.nan_to_num(seg, nan=0).astype(bool))
            pos["tgt"], pos["tgt_exit"] = tgt, (e + int(hit[0]) if len(hit) else None)

    for b in brks:
        e = b.r + 1
        settle(b.d, b.r)
        if pos is not None and b.side == -pos["side"]:
            if e <= last_row and np.isfinite(g.c[b.d][min(e, N - 1)]):
                close(pos, e, "flip", float(g.c[b.d][e]))
            else:
                close(pos, b.r, "flip", float(g.c[b.d][b.r]))
            pos = None
        if pos is None and e < N and can_enter(b.d, e, b.side):
            open_(b, e)
    settle(len(g.days), 0)
    return [t for t in out if keep(t["day"])]


# ------------------------------------------------------------------------------------------------ the option leg
def price(a, r0, xr, why, lot, day, und):
    stop = why == "flip"
    return R.option_trade(a, r0, xr, "stop" if stop else why, lot, day, und)


def run_index(args) -> dict:
    und, part = args
    lo, hi = K.DESIGN if part == "design" else K.HOLDOUT
    g = D3.load_grid(und, hi)
    W, Mo = R.Series(und, hi, "WEEK"), R.Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und) + (100 if part != "design" else 0))
    brks = breaks(g)
    h60 = state_grid(g, 60)
    nnet = NN(und, part)
    sig = {}
    for v, cfg in VERSIONS.items():
        sig[v] = trade_breaks(g, brks, cfg, blocked, keep, h60=h60 if cfg["h60"] else None,
                              nn=(nnet, cfg["nn"]) if cfg["nn"] else None)
    by_day = {}
    for v, T in sig.items():
        for t in T:
            by_day.setdefault(t["d"], []).append((v, t))
    rows = []
    for d in sorted(by_day):
        day = g.days[d]
        src = Mo if day in wexp else W
        opts = src.day(day, g.c[d])
        if opts is None:
            continue
        for v, t in by_day[d]:
            sd, r0, xr, itm = t["side"], t["entry"], t["exit"], VERSIONS[v]["itm"]
            ref_c = D3._last(g.c[d][:r0])
            if not np.isfinite(ref_c):
                continue
            cp = lambda s: "C" if s > 0 else "P"  # noqa: E731
            tr = price(opts.contracts.get((cp(sd), E.strike(ref_c, step, sd, itm))), r0, xr, t["why"], lot, day, und)
            if tr is None:
                continue
            hold = xr - r0
            tw_r, tw_s = [], []
            for kind, lst in (("random", tw_r), ("same", tw_s)):
                for _ in range(TWINS):
                    r1 = int(rng.integers(FIRST_ENTRY, LAST_ENTRY + 1))
                    s1 = (1 if rng.random() < 0.5 else -1) if kind == "random" else sd
                    c1 = D3._last(g.c[d][:r1])
                    x1 = min(r1 + hold, K.K3.EXIT_LAST)
                    if not np.isfinite(c1):
                        lst.append(np.nan)
                        continue
                    t1 = R.option_trade(opts.contracts.get((cp(s1), E.strike(c1, step, s1, itm))), r1, x1, "time",
                                        lot, day, und)
                    lst.append(np.nan if t1 is None else t1[0])
            rows.append(dict(t, variant=v, und=und, series=src.series, net=tr[0], netx2=tr[1], twins=tw_r,
                             twins_same=tw_s))
    print(f"R131 {part} {und}: " + ", ".join(f"{v} {sum(r['variant'] == v for r in rows)}" for v in VERSIONS),
          flush=True)
    return dict(rows=rows, sessions=sorted({d for d in g.days if keep(d)}))


# ------------------------------------------------------------------------------------------------ NIFTY 2017-2020
def load_grid_from(name: str, first: date, last: date):
    """R123's grid loader without its 2021 floor (for the untouched 2017-2020 NIFTY minutes)."""
    fs = sorted((K.K3.LAKE / "index_1m").glob(f"index_1m_{name}_*.parquet"))
    fs = [p for p in fs if first.year <= int(p.stem[-4:]) <= last.year]
    df = pd.concat([D3._ts_frame(p, ["open", "high", "low", "close", "ts"]) for p in fs], ignore_index=True)
    df = df[(df.d <= last) & (df.d >= first)].drop_duplicates(["d", "m"], keep="first")
    df = df[np.isfinite(df.close) & (df.close > 0)]
    days = sorted(df.d.unique())
    di = {d: i for i, d in enumerate(days)}
    a = {k: np.full((len(days), N), np.nan) for k in ("open", "high", "low", "close")}
    r, m = df.d.map(di).values, df.m.values
    for k in a:
        a[k][r, m] = df[k].values
    return D3.Grid(days, a["open"], a["high"], a["low"], a["close"])


def run_nifty():
    if (RESULTS / "nifty_2017_2020.json").exists():
        raise R.HoldoutError("refused: NIFTY 2017-2020 was already read (results/nifty_2017_2020.json)")
    g = load_grid_from("NIFTY", date(2017, 1, 1), date(2020, 12, 31))
    brks = breaks(g)
    h60 = state_grid(g, 60)
    D = json.loads((RESULTS / "design.json").read_text())
    be = D["breakeven_pts_nifty"]
    out = dict(period=["2017-01-01", "2020-12-31"], sessions=len(g.days), breakeven_pts=be, versions={})
    for v in ("T1", "T2", "T4"):
        cfg = VERSIONS[v]
        T = pd.DataFrame(trade_breaks(g, brks, cfg, set(), lambda d: True, h60=h60 if cfg["h60"] else None))
        yr = T.groupby(np.array([d.year for d in T.day])).pts.agg(["count", "mean", "sum"])
        out["versions"][v] = dict(label=cfg["label"], n=int(len(T)), avg_pts=float(T.pts.mean()),
                                  pts_win=float((T.pts > 0).mean()), net_pts=float(T.pts.mean() - be),
                                  above_breakeven=bool(T.pts.mean() > be),
                                  by_year={int(y): dict(n=int(r["count"]), avg=float(r["mean"]), total=float(r["sum"]))
                                           for y, r in yr.iterrows()},
                                  exits={k: int(x) for k, x in T.why.value_counts().items()})
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "nifty_2017_2020.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps(out["versions"], indent=1)[:2000])


# ------------------------------------------------------------------------------------------------ statistics and run
def stats(T: pd.DataFrame, by_fold: bool) -> dict:
    s = R.stats(T, by_fold)
    if not s.get("n"):
        return s
    T = T.sort_values(["day", "und", "entry"]).reset_index(drop=True)
    s["p_random_side"] = s.pop("p_twins")
    s["p_normal_random_side"], _ = E.normal_p(T)
    s["p_same_side"], _, _ = R.twin_p(T.assign(twins=T.twins_same))
    s["index_win"] = float((T.pts > 0).mean())
    return s


def run(part: str):
    from multiprocessing import Pool
    if part == "nifty":
        return run_nifty()
    if part == "holdout":
        R.holdout_lock("start", OUT, RESULTS)
    with Pool(4) as pool:
        res = pool.map(run_index, [(u, part) for u in K.UNDS])
    T = pd.DataFrame([r for x in res for r in x["rows"]])
    out = dict(part=part, period=[str(x) for x in (K.DESIGN if part == "design" else K.HOLDOUT)],
               sessions=len({d for x in res for d in x["sessions"]}), variants={})
    for v in VERSIONS:
        Tv = T[T.variant == v]
        s = stats(Tv, part == "design")
        s["label"] = VERSIONS[v]["label"]
        if v in ("T5", "T6") or part != "design":
            pass
        out["variants"][v] = s
    if part == "design":
        # T1 on the network's days (2023 on), for a like-for-like comparison with T5 / T6
        out["T1_from_2023"] = R.money(T[(T.variant == "T1") & (T.day >= date(2023, 1, 1))])
        qs = R.R3.bh([out["variants"][v].get("p_random_side", 1.0) for v in VERSIONS])
        for v, q in zip(VERSIONS, qs):
            s = out["variants"][v]
            s["q"] = q
            folds = [s.get("by_fold", {}).get(f, {}).get("total", 0.0) > 0 for f in K.FOLDS]
            s["design_gates"] = dict(n=s.get("n", 0) >= GATES["design_n"],
                                     net_pos=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                                     folds=sum(folds) >= GATES["folds"], q=q <= GATES["q"])
            s["passes_design"] = all(s["design_gates"].values())
        X = T[(T.variant == "T1") & (T.und == "NIFTY")]
        out["breakeven_pts_nifty"] = float(np.median(X.pts - 2 * X.net / K.LOT["NIFTY"]))
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}.json").write_text(json.dumps(out, indent=1, default=str))
    OUT.mkdir(parents=True, exist_ok=True)
    if len(T):
        T.drop(columns=["twins", "twins_same"]).to_parquet(OUT / f"trades_{part}.parquet")
    if part == "holdout":
        R.holdout_lock("done", OUT, RESULTS)
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 3),
               s.get("p_random_side")) for v, s in out["variants"].items()})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
