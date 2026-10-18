"""R77 study (PREREG.md): the "absorption bubble" PROXY on 1-minute INDEX bars, the 1-ITM option bought. 18 Oct 2026.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R51's clean stores (once)
    python research/hunt/r77/r77study.py design                # 8 tests on design + walk-forward quarters, events
    python research/hunt/r77/r77study.py final                 # the ONE final read (refuses a second), twins, BH
    python research/hunt/r77/r77study.py proxy                 # option-volume proxy vs real futures volume (no P&L)

Signal source: the UNDERLYING (index bars; the index's option-market volume as the volume condition). Prices: R64's
engine on R51's Amendment A clean stores, exactly as R71. Trades go to $R77_OUT (never git); results/ holds small JSON.
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
for _d in (HERE, HERE.parent / "r71"):
    if str(_d) not in sys.path:
        sys.path.insert(0, str(_d))
import r71cfg as R71C  # noqa: E402  (periods, cache root)
import r71study as S  # noqa: E402  (stats, bh, split, months_between, mods: R51 / R64 modules on the clean stores)
import r77rules as P  # noqa: E402

OUT = Path(os.environ.get("R77_OUT", R71C.CACHE_ROOT / "r77")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
START, DESIGN_END, FINAL_START, WF_FOLDS = R71C.START, R71C.DESIGN_END, R71C.FINAL_START, R71C.WF_FOLDS
MONEY, MAX_PER_SIDE = 1, 2
TWINS, SEED, BOOT, PLACEBO_DAYS, SWING_WIN = 500, 77, 2000, 200, 15
GATE_Q, MIN_DESIGN = 0.10, 100
HORIZONS = (5, 15, 30)
EXIT_SPEC = {"X1": dict(stop=0.25, target=-1.0, tmax=30, index=True),
             "X2": dict(stop=0.15, target=0.30, tmax=30, index=False)}


# ================================================================================================ pure
def gates(design: dict, wf: dict, final: dict, q) -> list:
    """PREREG section 4: net > 0 in design, walk-forward and final; >= 100 design trades; final twin q <= 0.10."""
    fails = []
    if design.get("net", 0) <= 0:
        fails.append("design net <= 0")
    if wf.get("net", 0) <= 0:
        fails.append("walk-forward net <= 0")
    if final.get("net", 0) <= 0:
        fails.append("final net <= 0")
    if design.get("trades", 0) < MIN_DESIGN:
        fails.append("fewer than 100 design trades")
    if q is None or q > GATE_Q:
        fails.append("twin q > 0.10")
    return fails


def twin_p(real: float, tw: np.ndarray) -> float:
    return round((1 + int((np.asarray(tw) >= real).sum())) / (len(tw) + 1), 4)


def draw_other(rng, n_pool: int, own: int, size: int) -> np.ndarray:
    """`size` uniform draws from range(n_pool) excluding `own` (own < 0: nothing excluded). Pure given rng."""
    if own < 0:
        return rng.integers(0, n_pool, size=size)
    x = rng.integers(0, n_pool - 1, size=size)
    return x + (x >= own)


def block_boot(diffs_by_day: dict, n: int, rng) -> tuple:
    """Day-block bootstrap of the mean of per-event values: resample days with replacement. Returns (lo, hi) 95%."""
    days = list(diffs_by_day)
    if len(days) < 2:
        return None, None
    arrs = [np.asarray(diffs_by_day[d], float) for d in days]
    sums = np.array([a.sum() for a in arrs])
    cnts = np.array([len(a) for a in arrs])
    k = rng.integers(0, len(days), size=(n, len(days)))
    m = sums[k].sum(axis=1) / cnts[k].sum(axis=1)
    return round(float(np.percentile(m, 2.5)), 2), round(float(np.percentile(m, 97.5)), 2)


# ================================================================================================ data side
_PREP: dict = {}


def prep(und: str, hi: date) -> dict:
    """One index: its sessions with option data (time order, up to `hi`), the volume (lots) and range percentiles."""
    key = (und, hi)
    if key not in _PREP:
        M = S.mods()
        K, r51data = M["K"], M["r51data"]
        days = r51data.load(und)
        ds = [d for d in sorted(days) if d <= hi and days[d].atm is not None]
        vol = np.stack([P.day_volume(days[d].con, K.lot(und, d)) for d in ds])
        rng_ = np.stack([days[d].idx[1] - days[d].idx[2] for d in ds])
        _PREP[key] = dict(days=ds, pos={d: t for t, d in enumerate(ds)}, vp=P.pct_prev(vol), rp=P.pct_prev(rng_))
    return _PREP[key]


def period_days(und: str, lo: date, hi: date) -> list:
    """Non-expiry tradable sessions of an index in [lo, hi] (after R51's final guard)."""
    M = S.mods()
    days = M["r51data"].load(und)
    return [d for d in M["C"].days_in(und, lo, hi) if not days[d].expiry]


def events(und: str, det: str, lo: date, hi: date) -> dict:
    """{day: [event dict]} of one detector on one index (non-expiry sessions in [lo, hi])."""
    vmin, rmax = P.DETECTORS[det]
    pr = prep(und, hi)
    days = S.mods()["r51data"].load(und)
    out = {}
    for d in period_days(und, lo, hi):
        t = pr["pos"][d]
        a = days[d].idx
        ev = P.absorption_events(a[0], a[1], a[2], a[3], pr["vp"][t], pr["rp"][t], vmin, rmax)
        if ev:
            out[d] = ev
    return out


def exits_of(xk: str):
    M = S.mods()
    s = EXIT_SPEC[xk]
    return M["E"].Exits(stop=s["stop"], target=s["target"], tmax=s["tmax"], sq_row=M["K"].row(15, 15))


def trades(test: str, lo: date, hi: date) -> list:
    det, xk = test.split("-")
    M = S.mods()
    K, E, G, r51data = M["K"], M["E"], M["G"], M["r51data"]
    ex = exits_of(xk)
    rows = []
    for und in K.UNDS:
        days = r51data.load(und)
        for d, evs in events(und, det, lo, hi).items():
            D = days[d]
            cands = []
            for e in evs:
                k = E.strike_for(D, und, e["side"], e["er"] - 1, MONEY)
                if k is None:
                    continue
                c = dict(er=e["er"], side=e["side"], strike=k, sgn=e["sgn"], stop_idx=e["stop"], tgt_idx=e["target"],
                         ref=e["ref"], abs_row=e["i"])
                if EXIT_SPEC[xk]["index"]:
                    c["fx"] = P.forced_row(D.idx[1], D.idx[2], e["er"], e["sgn"], e["stop"], e["target"])
                cands.append(c)
            rows += G.take(und, D, cands, ex, max_per_side=MAX_PER_SIDE)
    return rows


def twins(tr: list, xk: str, lo: date, hi: date, n: int, rng) -> np.ndarray:
    """PREREG section 4: each trade -> n twins on the same index, side and entry row, on random OTHER non-expiry
    sessions of the same period; same strike rule and exits (X1: index stop / target the same points from the twin's
    close at row er - 1). Batched by twin day."""
    M = S.mods()
    E, G, r51data = M["E"], M["G"], M["r51data"]
    ex = exits_of(xk)
    tot = np.zeros(n)
    by_und = defaultdict(list)
    for t in tr:
        by_und[t["und"]].append(t)
    for und, ts in by_und.items():
        pool = period_days(und, lo, hi)
        pos = {d: i for i, d in enumerate(pool)}
        days = r51data.load(und)
        jobs = defaultdict(list)                      # pool index -> [(twin number, trade)]
        for t in ts:
            for j, pi in enumerate(draw_other(rng, len(pool), pos.get(t["day"], -1), n)):
                jobs[int(pi)].append((j, t))
        for pi, lst in jobs.items():
            D = days[pool[pi]]
            cl = D.idx[3]
            sides, strikes, rows, fx, who = [], [], [], [], []
            for j, t in lst:
                er = t["er"]
                sides.append(t["side"])
                strikes.append(E.strike_for(D, und, t["side"], er - 1, MONEY))
                rows.append(er)
                who.append(j)
                f = -1
                if EXIT_SPEC[xk]["index"]:
                    p0 = cl[er - 1]
                    if np.isfinite(p0):
                        s = t["sgn"]
                        stop = p0 - (t["ref"] - t["stop_idx"])
                        tgt = p0 + (t["tgt_idx"] - t["ref"]) if np.isfinite(t["tgt_idx"]) else float("nan")
                        f = P.forced_row(D.idx[1], D.idx[2], er, s, stop, tgt)
                fx.append(f)
            nets = G.twin_nets(und, D, sides, strikes, np.asarray(rows, np.int64), ex, fx)
            np.add.at(tot, np.asarray(who), nets)
    return tot


def _moves(closes: np.ndarray, rows: np.ndarray, k: int, sgn) -> np.ndarray:
    """Vectorised P.signed_move_bps: closes (n, 375), rows (n,), sgn scalar or (n,). NaN where missing. Pure."""
    rows = np.asarray(rows, int)
    ok = rows + k < closes.shape[1]
    out = np.full(len(rows), np.nan)
    if ok.any():
        ix = np.flatnonzero(ok)
        a = closes[ix, rows[ix]]
        b = closes[ix, rows[ix] + k]
        s = np.broadcast_to(np.asarray(sgn, float), rows.shape)[ix]
        with np.errstate(invalid="ignore", divide="ignore"):
            out[ix] = np.where(a > 0, s * (b - a) / a * 1e4, np.nan)
    return out


def event_study(lo: date, hi: date, rng) -> dict:
    """PREREG section 5, information only: signed index moves after every confirmed event, against P-time (the same
    row on 200 random other sessions) and P-swing (swing reversals without the volume / range condition, rows +-15)."""
    M = S.mods()
    K, r51data = M["K"], M["r51data"]
    out = {}
    base = {}
    for und in K.UNDS:
        days = r51data.load(und)
        pool = period_days(und, lo, hi)
        closes = np.stack([days[d].idx[3] for d in pool])
        sw = [(t, i, s) for t, d in enumerate(pool) for i, s, _, _ in P.swing_events(*days[d].idx)
              if P.FIRST_ER <= i + 2 <= P.LAST_ER]
        sw = np.array(sw, int).reshape(-1, 3)
        smv = {k: _moves(closes[sw[:, 0]], sw[:, 1] + 1, k, sw[:, 2]) for k in HORIZONS}
        base[und] = (pool, {d: t for t, d in enumerate(pool)}, closes, sw, smv)
    for det in P.DETECTORS:
        acc = {k: dict(ev=[], dt=defaultdict(list), ds=defaultdict(list), pt=[], ps=[]) for k in HORIZONS}
        for und in K.UNDS:
            pool, pos, closes, sw, smv = base[und]
            for d, evs in events(und, det, lo, hi).items():
                t = pos[d]
                for e in evs:
                    row, sgn = e["i"] + 1, e["sgn"]
                    others = draw_other(rng, len(pool), t, PLACEBO_DAYS)
                    mask = (sw[:, 2] == sgn) & (np.abs(sw[:, 1] - e["i"]) <= SWING_WIN) & \
                        ~((sw[:, 0] == t) & (sw[:, 1] == e["i"]))
                    for k in HORIZONS:
                        m = P.signed_move_bps(closes[t], row, k, sgn)
                        if not np.isfinite(m):
                            continue
                        ptv = _moves(closes[others], np.full(len(others), row), k, sgn)
                        pt = float(np.nanmean(ptv)) if np.isfinite(ptv).any() else np.nan
                        psv = smv[k][mask]
                        ps = float(np.nanmean(psv)) if np.isfinite(psv).any() else np.nan
                        A = acc[k]
                        A["ev"].append(m)
                        A["pt"].append(pt)
                        A["ps"].append(ps)
                        if np.isfinite(pt):
                            A["dt"][(und, d)].append(m - pt)
                        if np.isfinite(ps):
                            A["ds"][(und, d)].append(m - ps)
        res = {}
        for k, A in acc.items():
            ev = np.array(A["ev"])
            if not len(ev):
                res[k] = dict(n=0)
                continue
            pt, ps = np.array(A["pt"]), np.array(A["ps"])
            res[k] = dict(n=int(len(ev)), days=len(A["dt"]), mean_bps=round(float(ev.mean()), 2),
                          median_bps=round(float(np.median(ev)), 2), share_up=round(float((ev > 0).mean()), 4),
                          p_time_bps=round(float(np.nanmean(pt)), 2), p_swing_bps=round(float(np.nanmean(ps)), 2),
                          n_swing_matched=int(np.isfinite(ps).sum()),
                          diff_time_bps=round(float(np.nanmean(ev - pt)), 2), ci_time=block_boot(A["dt"], BOOT, rng),
                          diff_swing_bps=round(float(np.nanmean(ev - ps)), 2), ci_swing=block_boot(A["ds"], BOOT, rng))
        out[det] = res
    # the plain swing reversal itself (no volume / range condition), against P-time: is there any reversal edge at all?
    out["swing_only"] = {}
    for k in HORIZONS:
        allm = np.concatenate([base[u][4][k] for u in K.UNDS])
        allm = allm[np.isfinite(allm)]
        out["swing_only"][k] = dict(n=int(len(allm)), mean_bps=round(float(allm.mean()), 2) if len(allm) else None,
                                    share_up=round(float((allm > 0).mean()), 4) if len(allm) else None)
    return out


def per_index(tr: list) -> dict:
    o = defaultdict(list)
    for t in tr:
        o[t["und"]].append(t)
    return {u: dict(trades=len(v), net=round(sum(t["net"] for t in v)), win=round(float(np.mean([t["net"] > 0 for t in v])), 4))
            for u, v in sorted(o.items())}


def examples(tr: list) -> list:
    """The best, the median and the worst trade (by net)."""
    if not tr:
        return []
    srt = sorted(tr, key=lambda t: t["net"])
    pick = [srt[-1], srt[len(srt) // 2], srt[0]]
    K = S.mods()["K"]

    def hm(r):
        m = K.OPEN_MIN + r
        return f"{m // 60:02d}:{m % 60:02d}"
    return [dict(und=t["und"], day=str(t["day"]), side=t["side"], strike=t["strike"], absorption_bar=hm(t["abs_row"]),
                 entry=hm(t["er"]), exit=hm(t["xr"]), fill=round(t["E"], 2), out=round(t["X"], 2), why=t["why"],
                 index_stop=round(t["stop_idx"], 2),
                 index_target=None if not np.isfinite(t["tgt_idx"]) else round(t["tgt_idx"], 2),
                 net=round(t["net"])) for t in pick]


def _strip(tr: list) -> list:
    keep = ("und", "day", "side", "strike", "er", "xr", "E", "X", "rE", "rX", "qty", "why", "net", "abs_row", "stop_idx",
            "tgt_idx", "ref", "sgn")
    return [{k: t[k] for k in keep if k in t} for t in tr]


def _save(name: str, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / f"{name}.pkl", "wb") as f:
        pickle.dump(obj, f, protocol=pickle.HIGHEST_PROTOCOL)


def _json(obj, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1, default=str) + "\n")


# ================================================================================================ phases
def design() -> dict:
    lo, hi = START, DESIGN_END
    months = S.months_between(lo, hi)
    out = dict(tests={}, written=datetime.now(timezone.utc).isoformat(timespec="seconds"))
    for test in P.TESTS:
        tr = trades(test, lo, hi)
        _save(f"{test}_design", _strip(tr))
        wf = S.split(tr, WF_FOLDS[0][0], WF_FOLDS[-1][1])
        tw = twins(tr, test.split("-")[1], lo, hi, TWINS, np.random.default_rng(SEED)) if tr else np.zeros(TWINS)
        st = S.stats(tr, months)
        out["tests"][test] = dict(design=st, wf=S.stats(wf), folds=[round(sum(t["net"] for t in S.split(tr, a, b)))
                                                                     for a, b in WF_FOLDS],
                                  fold_trades=[len(S.split(tr, a, b)) for a, b in WF_FOLDS],
                                  per_index=per_index(tr), examples=examples(tr),
                                  design_twin_p=twin_p(st["net"], tw) if tr else None,
                                  design_twin_mean=round(float(tw.mean())))
        print(test, st["trades"], st["net"], "wf", out["tests"][test]["wf"]["net"], flush=True)
    out["events"] = event_study(lo, hi, np.random.default_rng(SEED))
    _json(out, RESULTS / "design.json")
    return out


def final() -> dict:
    dj = RESULTS / "design.json"
    if not dj.exists():
        raise SystemExit("run `design` first")
    if FINAL_FLAG.exists():
        raise SystemExit("R77's final period was already read once (results/FINAL_READ); a second read is refused")
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    des = json.loads(dj.read_text())
    M = S.mods()
    M["C"].open_final()
    lo, hi = FINAL_START, M["K"].END
    months = S.months_between(lo, hi)
    out = dict(tests={}, end=str(hi), read=FINAL_FLAG.read_text().strip())
    ps = []
    for test in P.TESTS:
        tr = trades(test, lo, hi)
        _save(f"{test}_final", _strip(tr))
        st = S.stats(tr, months)
        tw = twins(tr, test.split("-")[1], lo, hi, TWINS, np.random.default_rng(SEED)) if tr else np.zeros(TWINS)
        p = twin_p(st["net"], tw) if tr else 1.0
        ps.append(p)
        out["tests"][test] = dict(final=st, p=p, twin_mean=round(float(tw.mean())), per_index=per_index(tr),
                                  examples=examples(tr))
        print(test, st["trades"], st["net"], p, flush=True)
    for test, q in zip(P.TESTS, S.bh(ps)):
        t = out["tests"][test]
        d = des["tests"][test]
        t["q"] = round(float(q), 4)
        t["fails"] = gates(d["design"], d["wf"], t["final"], t["q"])
        t["verdict"] = "PASS" if not t["fails"] else "FAIL"
    out["events"] = event_study(lo, hi, np.random.default_rng(SEED))
    _json(out, RESULTS / "final.json")
    return out


def proxy() -> dict:
    """PREREG section 5 proxy check (no prices or P&L): option-volume proxy vs the real index-future volume."""
    import pandas as pd
    M = S.mods()
    K, r51data = M["K"], M["r51data"]
    M["C"].open_final()
    lake = Path(os.environ.get("IRAALGO_LAKE", R71C.CACHE_ROOT / "lake")).expanduser()
    f = pd.read_parquet(lake / "fut_1m_live_index.parquet", columns=["symbol", "expiry", "volume", "ts"])
    ts = f.ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    f["day"], f["r"] = ts.dt.date, (ts.dt.hour * 60 + ts.dt.minute - K.OPEN_MIN)
    f = f[(f.r >= 0) & (f.r < K.NROWS)]
    out = {}
    for und in K.UNDS:
        g = f[f.symbol == und]
        if g.empty:
            continue
        pr = prep(und, K.END)
        days = r51data.load(und)
        xs, ys, rhos, hits, flags = [], [], [], 0, 0
        for d, h in g.groupby("day"):
            if d not in pr["pos"]:
                continue
            exp = h.groupby("expiry").size().idxmax()           # the contract the lake holds most minutes of that day
            h = h[h.expiry == exp]
            fv = np.full(K.NROWS, np.nan)
            fv[h.r.values] = h.volume.values
            pv = P.day_volume(days[d].con, K.lot(und, d))
            ok = np.isfinite(fv) & np.isfinite(pv)
            if ok.sum() < 100:
                continue
            xs.append(fv[ok])
            ys.append(pv[ok])
            rhos.append(pd.Series(fv[ok]).rank().corr(pd.Series(pv[ok]).rank()))
            vp = pr["vp"][pr["pos"][d]]
            top = fv >= np.nanpercentile(fv, 90)
            fl = (vp >= 0.95) & ok
            flags += int(fl.sum())
            hits += int((fl & top).sum())
        if not xs:
            continue
        x, y = np.concatenate(xs), np.concatenate(ys)
        out[und] = dict(days=len(xs), minutes=int(len(x)),
                        spearman_pooled=round(float(pd.Series(x).rank().corr(pd.Series(y).rank())), 3),
                        spearman_day_median=round(float(np.median(rhos)), 3), proxy_flags=flags,
                        flags_in_futures_top10=round(hits / flags, 3) if flags else None)
        print(und, out[und], flush=True)
    _json(out, RESULTS / "proxy.json")
    return out


def posthoc() -> dict:
    """NOT pre-registered (found after the final read): trades filled on a stale zero-volume print. Lists every saved
    trade whose fill minute (and the 2 after) printed no volume and whose option moved more than -60% / +200%, and
    the final nets without them. Reads only the saved trades and the stores; prices nothing again."""
    M = S.mods()
    r51data = M["r51data"]
    out = {}
    for test in P.TESTS:
        for ph in ("design", "final"):
            with open(OUT / f"{test}_{ph}.pkl", "rb") as f:
                tr = pickle.load(f)
            bad = []
            for t in tr:
                c = r51data.load(t["und"])[t["day"]].con[(t["side"], t["strike"])]
                v = c[4, t["er"]:t["er"] + 3]
                r = t["X"] / t["E"] - 1
                if np.nansum(v) == 0 and (r < -0.6 or r > 2.0):
                    bad.append(t)
            keep = [t for t in tr if t not in bad]
            out[f"{test}|{ph}"] = dict(stale=[dict(und=t["und"], day=str(t["day"]), side=t["side"], strike=t["strike"],
                                                   fill=round(t["E"], 2), out=round(t["X"], 2), net=round(t["net"]))
                                              for t in bad],
                                       without=S.stats(keep))
    _json(out, RESULTS / "posthoc.json")
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "proxy":
        proxy()
    elif cmd == "posthoc":
        posthoc()
    else:
        raise SystemExit(__doc__)
