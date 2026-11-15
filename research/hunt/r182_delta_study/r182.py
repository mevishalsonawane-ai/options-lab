"""R182 runner (PREREG.md): what order-flow delta is, what it did on the phone's recordings, and whether a delta skip rule
helps the three arms' real paper trades (Liquidity 15+5, R51 Donchian 20, R72 Breadth Donchian).

    python -I research/hunt/r182_delta_study/r182.py --rec2 <1-second export dir> --rec1 <first export dir> \
        --diag <diagnostics dir> --cache <scratch cache dir> --stage {pre,run}

--stage pre: data quality, the definitions' cross-check and the trade counts only (no forward return, no trade outcome);
             written to results/pre.json. Allowed before the pre-registration (PREREG section 1).
--stage run: everything, once, after PREREG.md is committed; writes results/results.json and results/report.md.
The recordings and the diagnostics stay outside git. Plain-English notes, 15 Nov 2026.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path[:0] = [str(HERE), str(ROOT)]
import r182lib as LB  # noqa: E402
import r182load as LD  # noqa: E402

RESULTS = HERE / "results"
ARMS = ("Liquidity", "R51", "R72")
FEATS = ("D1", "D5", "D15", "CVDslope15", "Div15", "OBI", "Absorb5")
TOD = (("09:30-10:30", 15, 75), ("10:30-13:30", 75, 255), ("13:30-15:15", 255, 360))


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def load_all(a):
    days = LD.flow_days(a.rec2)
    flow = {d: LD.load_flow_day(a.rec2, d, a.cache) for d in days}
    mins = {d: LD.load_min_day(a.rec1, d, a.cache) for d in days}
    bars = {}
    for d in days:
        t0 = flow[d]["t0"]
        for u in LB.INDICES:
            b = LB.minute_bars(flow[d][u]["sec"], t0)
            b.absorb = LB.absorb_minutes(flow[d][u]["fp"], t0)
            bars[(d, u)] = b
    return days, flow, mins, bars


def quality(days, flow, bars) -> dict:
    out = {}
    for u in LB.INDICES:
        rows = []
        for d in days:
            s = flow[d][u]["sec"]
            b = bars[(d, u)]
            es = s["epoch_sec"]
            t0 = flow[d]["t0"]
            inside = (es >= t0) & (es < t0 + LB.N * 60)
            gaps = np.diff(es[inside]) if inside.sum() > 1 else np.array([0])
            rows.append(dict(day=d, seconds=int(inside.sum()), minutes=int((b.rows > 0).sum()),
                             thin=int(((b.rows > 0) & (b.rows < LB.THIN_ROWS)).sum()), missing=int((b.rows == 0).sum()),
                             stale_packets=int(np.nansum(s["stale"][inside])), gap5=int((np.nan_to_num(s["gap_s"][inside]) >= 5).sum()),
                             max_gap_s=int(gaps.max()) if len(gaps) else 0,
                             print_secs=int((np.nan_to_num(s["prints"][inside]) > 0).sum()),
                             vol=float(np.nansum(s["vol"][inside])), absorb=int((b.absorb != 0).sum()),
                             fp_minutes=len(flow[d][u]["fp"])))
        out[u] = rows
    return out


def crosscheck(days, mins, bars) -> dict:
    """The TickZone minute file against the 1-second file on the days both exist (PREREG section 2)."""
    out = {}
    for u in ("NIFTY", "BANKNIFTY"):
        per = []
        for d in days:
            m = mins.get(d)
            if m is None:
                continue
            b = bars[(d, u)]
            mm = m[u]
            ok = (mm["secs"] >= 50) & (b.rows >= LB.THIN_ROWS) & np.isfinite(mm["d"])
            if ok.sum() < 30:
                continue
            d1, d2, dq = mm["d"][ok], b.d[ok], b.dq[ok]
            per.append(dict(day=d, minutes=int(ok.sum()), vol_ratio_1s_over_1m=float(np.sum(b.v[ok]) / np.sum(mm["vol"][ok])),
                            delta_equal=float(np.mean(d1 == d2)), sign_agree=float(np.mean(np.sign(d1) == np.sign(d2))),
                            corr=LB.pearson(d1, d2), corr_quote_vs_1m=LB.pearson(d1, dq), corr_quote_vs_1s=LB.pearson(d2, dq)))
        out[u] = per
    return out


def trades_and_counts(a, days, flow, bars):
    orders = LD.load_orders(a.diag)
    trades, st = LB.rebuild_trades(orders)
    flow_days = set(days)
    for t in trades:
        t["flow_day"] = t["day"] in flow_days
        t["flow_und"] = t["und"] in LB.INDICES
        t["feat"] = None
        if t["flow_day"] and t["flow_und"]:
            fd = flow[t["day"]]
            s_entry = LB.epoch_of(t["day"], t["entry_at"])
            ab = bars[(t["day"], t["und"])].absorb if fd[t["und"]]["fp"] else None
            t["feat"] = LB.entry_features(fd[t["und"]]["sec"], ab, fd["t0"], s_entry, t["side"])
        t["lab"] = LB.labels(t["feat"])
    counts = {}
    for arm in ARMS:
        ts = [t for t in trades if t["arm"] == arm]
        counts[arm] = dict(trades=len(ts), per_day=dict(Counter(t["day"] for t in ts)),
                           per_und=dict(Counter(t["und"] for t in ts)), on_flow_days=sum(t["flow_day"] for t in ts),
                           with_futures_flow=sum(t["flow_day"] and t["flow_und"] for t in ts),
                           labelled=sum(t["lab"]["P"] is not None for t in ts),
                           primary_skips=sum(t["lab"]["P"] is True for t in ts),
                           v1_skips=sum(t["lab"]["V1"] is True for t in ts), v2_skips=sum(t["lab"]["V2"] is True for t in ts),
                           v3_skips=sum(t["lab"]["V3"] is True for t in ts),
                           same_entry_as_other_arm=0)
    # R51 and R72 often buy the same option in the same second: count the overlaps
    key = Counter((t["day"], t["entry_at"], t["symbol"]) for t in trades)
    for arm in ARMS:
        counts[arm]["same_entry_as_other_arm"] = sum(key[(t["day"], t["entry_at"], t["symbol"])] > 1 for t in trades if t["arm"] == arm)
    return trades, st, counts


# ------------------------------------------------------------------------------------------------ Part A
def part_a(days, bars, mins) -> dict:
    out = {}
    for u in LB.INDICES:
        F = {f: [] for f in FEATS + ("DQ1",)}
        R = {h: [] for h in LB.HORIZONS}
        R0 = []
        for d in days:
            b = bars[(d, u)]
            f = LB.features(b)
            sl = slice(LB.WARM, LB.LAST_SIG + 1)
            for k in F:
                F[k].append(f[k][sl])
            for h in LB.HORIZONS:
                R[h].append(LB.fwd_returns(b.c, h)[sl])
            R0.append(LB.same_minute_return(b.c)[sl])
        res = dict(days=len(days))
        # same minute: the minute's delta with the minute's own price change
        res["same_minute"] = LB.day_bootstrap(F["D1"], R0, draws=2000, seed=11)
        res["same_minute_pearson"] = LB.pearson(np.concatenate(F["D1"]), np.concatenate(R0))
        res["same_minute_quote_rule"] = LB.day_bootstrap(F["DQ1"], R0, draws=2000, seed=12)
        # the primary test: 5-minute delta at the close -> the next 15 minutes' futures return
        prim = LB.day_bootstrap(F["D5"], R[15], draws=10000, seed=13)
        prim["p_shift"] = LB.shift_null(F["D5"], R[15], draws=10000, seed=14)
        res["primary"] = prim
        grid = {}
        for k in FEATS:
            for h in LB.HORIZONS:
                g = LB.day_bootstrap(F[k], R[h], draws=1000, seed=100 + h)
                grid[f"{k}->{h}m"] = dict(r=g["r"], lo=g["lo"], hi=g["hi"], n=g["n"])
        res["grid"] = grid
        tod = {}
        for name, lo, hi in TOD:
            a0, b0 = lo - LB.WARM, hi - LB.WARM
            g = LB.day_bootstrap([x[a0:b0] for x in F["D5"]], [x[a0:b0] for x in R[15]], draws=1000, seed=21)
            tod[name] = dict(r=g["r"], lo=g["lo"], hi=g["hi"], n=g["n"])
        res["time_of_day"] = tod
        # the sign rule as Part B uses it: next-15-minute return after a positive vs a negative 5-minute delta
        d5, r15 = np.concatenate(F["D5"]), np.concatenate(R[15])
        ok = np.isfinite(d5) & np.isfinite(r15)
        res["sign_rule"] = dict(pos_n=int((ok & (d5 > 0)).sum()), pos_mean_bp=float(np.mean(r15[ok & (d5 > 0)]) * 1e4),
                                neg_n=int((ok & (d5 < 0)).sum()), neg_mean_bp=float(np.mean(r15[ok & (d5 < 0)]) * 1e4))
        # the TickZone minute file's delta on its days (NIFTY, BANKNIFTY): the same two statistics (cross-check)
        if u in ("NIFTY", "BANKNIFTY"):
            XS, YS, X0, Y0 = [], [], [], []
            for d in days:
                m = mins.get(d)
                if m is None:
                    continue
                mm = m[u]
                rows = np.where(np.isfinite(mm["d"]), mm["secs"], 0)
                bb = LB.Bars(mm["c"], np.nan_to_num(mm["d"]), np.zeros(LB.N), mm["vol"], rows, mm["obi"], np.zeros(LB.N))
                f = LB.features(bb)
                sl = slice(LB.WARM, LB.LAST_SIG + 1)
                XS.append(f["D5"][sl]); YS.append(LB.fwd_returns(mm["c"], 15)[sl])
                X0.append(f["D1"][sl]); Y0.append(LB.same_minute_return(mm["c"])[sl])
            if XS:
                res["minute_file"] = dict(days=len(XS), same_minute=LB.day_bootstrap(X0, Y0, draws=1000, seed=31),
                                          primary=LB.day_bootstrap(XS, YS, draws=1000, seed=32))
        out[u] = res
    return out


# ------------------------------------------------------------------------------------------------ Part B
def part_b(trades) -> dict:
    out = {}
    groups = list(ARMS) + ["all three (descriptive)"]
    for g in groups:
        ts = [t for t in trades if (g.startswith("all") or t["arm"] == g)]
        lab = [t for t in ts if t["lab"]["P"] is not None]
        res = dict(all=LB.summary([t["net"] for t in ts]), labelled=LB.summary([t["net"] for t in lab]),
                   unlabelled=LB.summary([t["net"] for t in ts if t["lab"]["P"] is None]),
                   points_all=float(np.sum([t["points"] for t in ts])) if ts else 0.0,
                   pct_avg_all=float(np.mean([t["pct"] for t in ts])) if ts else None)
        for rule in ("P", "V1", "V2", "V3"):
            el = [t for t in lab if t["lab"][rule] is not None]
            skip = [t for t in el if t["lab"][rule]]
            kept_all = [t for t in ts if not (t["lab"][rule] is True)]
            tw = LB.random_skip_p([t["net"] for t in el], [t["lab"][rule] for t in el], draws=10000,
                                  seed=17 + ARMS.index(g) if g in ARMS else 99)
            res[rule] = dict(kept=LB.summary([t["net"] for t in kept_all]), skipped=LB.summary([t["net"] for t in skip]),
                             skipped_points=float(np.sum([t["points"] for t in skip])) if skip else 0.0,
                             kept_points=float(np.sum([t["points"] for t in kept_all])) if kept_all else 0.0,
                             eligible=len(el), twins=tw)
        res["groups"] = {k: LB.summary([t["net"] for t in ts if t["lab"]["group"] == k])
                         for k in ("agrees", "disagrees", "zero", "no label")}
        out[g] = res
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--rec2", required=True)
    ap.add_argument("--rec1", required=True)
    ap.add_argument("--diag", required=True)
    ap.add_argument("--cache", default=None)
    ap.add_argument("--stage", choices=("pre", "run"), required=True)
    a = ap.parse_args(argv)
    days, flow, mins, bars = load_all(a)
    q = quality(days, flow, bars)
    xc = crosscheck(days, mins, bars)
    trades, st, counts = trades_and_counts(a, days, flow, bars)
    pre = dict(days=days, quality=q, crosscheck=xc, rebuild=st, counts=counts)
    RESULTS.mkdir(exist_ok=True)
    if a.stage == "pre":
        (RESULTS / "pre.json").write_text(json.dumps(pre, indent=1, default=float))
        print(json.dumps(dict(rebuild=st, counts={k: {kk: vv for kk, vv in v.items() if kk != "per_day"} for k, v in counts.items()}), indent=1))
        return
    A = part_a(days, bars, mins)
    B = part_b(trades)
    tests = [dict(id=f"r182-a-{u.lower()}", what=f"Part A primary, {u}: 5-minute delta -> next 15-minute futures return",
                  p=A[u]["primary"]["p"]) for u in LB.INDICES]
    tests += [dict(id=f"r182-b-{arm.lower()}", what=f"Part B primary, {arm}: skip when the 5-minute futures delta opposes",
                   p=B[arm]["P"]["twins"]["p"]) for arm in ARMS]
    ps = [t["p"] if t["p"] is not None else 1.0 for t in tests]
    q_all, q_in = LB.bh_with_prior(ps, LB.M_PRIOR + len(tests))
    for t, qa, qi in zip(tests, q_all, q_in):
        t["q"], t["q_within"] = float(qa), float(qi)
    tr = [{k: v for k, v in t.items() if k not in ("exits",)} | dict(exits=[list(x) for x in t["exits"]]) for t in trades]
    res = dict(study="R182", run_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
               prereg_sha256=sha(HERE / "PREREG.md"), m_total=LB.M_PRIOR + len(tests), **pre, part_a=A, part_b=B, tests=tests,
               trades_note="per-trade rows are kept out of git (scratch only): results.json holds the aggregates")
    (RESULTS / "results.json").write_text(json.dumps(res, indent=1, default=float))
    if a.cache:
        Path(a.cache, "r182_trades.json").write_text(json.dumps(tr, indent=1, default=float))
    print(json.dumps(tests, indent=1))


if __name__ == "__main__":
    main()
