"""Validation suite: parity with earlier backtests, exit-engine unit tests, cost checks, cross-checks with older
studies, and calibration of the statistics on synthetic data. Writes <CACHE>/runs/validate/validation.json and prints
a summary (research/OBUY_VALIDATION.md is written from it).

    python3 -I research/obuy/run.py validate
"""
from __future__ import annotations

import json
import os
import time
from dataclasses import replace
from datetime import date
from decimal import ROUND_HALF_EVEN, Decimal

import numpy as np
import pandas as pd

from . import config as C
from . import overfit as OF
from . import stats as ST
from .costs import Costs, Fills, adverse_bps
from .engine import LADDER, Execution, Exits, Pack, Store, StrikeRule, positions, prepare_many, run_exits
from .lab import signals_cached

OUT = os.path.join(C.CACHE, "runs", "validate")
RES = {}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ------------------------------------------------------------------------------------------------ 1. unit tests
def synthetic_pack(paths, index=None, e_open=None, side=1, c0=10, lot=50, extra=None):
    """paths: list of dicts with O, H, L, Cl arrays (len W) for one contract each."""
    st = Store()
    meta = []
    for i, p in enumerate(paths):
        rows = [np.asarray(p[k], dtype=float) for k in ("O", "H", "L", "Cl")] + [np.full(C.W, 1e6)]
        cr = st.contract(("X", i), rows)
        I = index[i] if index else dict(h=np.full(C.W, 100.0), l=np.full(C.W, 100.0))
        ir = st.index(("X", i), I)
        e = float(Fills().buy(np.array([rows[0][c0]]))[0])
        m = dict(cand=i, parent=-1, und="NIFTY", day=date(2025, 1, 2), dn=20090, book="t", gate=c0 + C.OPEN_M, sig_min=c0 + C.OPEN_M - 1,
                 side=side, right="C", strike=100, strike2=100, lot=lot, qty=lot, c0=c0, e=e, e1=e, e2=np.nan, ref=100.0,
                 idx_stop=np.nan, idx_target=np.nan, exit_at=np.nan, tag="", bse=False, crow=cr, crow2=cr, irow=ir)
        if extra:
            m.update(extra[i])
        meta.append(m)
    st.done()
    return Pack(pd.DataFrame(meta), st, 1)


def flat(v):
    return np.full(C.W, float(v))


def unit_tests():
    exe = Execution()
    T = []

    def check(name, cond, detail=""):
        T.append(dict(test=name, ok=bool(cond), detail=detail))

    # a) premium stop on the bar's low, filled at the trigger (-10 bps); gap through -> at the open
    p = dict(O=flat(100), H=flat(101), L=flat(99), Cl=flat(100))
    p["L"][20] = 80
    q = dict(O=flat(100), H=flat(101), L=flat(99), Cl=flat(100))
    q["O"][20], q["L"][20] = 70, 69
    pk = synthetic_pack([p, q])
    tr = pk.run(Exits(stop_pct=0.15), exe)
    e = 100.05
    trig = np.floor(e * 0.85 / 0.05 + 1e-9) * 0.05
    check("stop at trigger", tr.why[0] == "stop" and abs(tr.exit[0] - float(adverse_bps(np.array([round(trig, 2)]), 10, False)[0])) < 1e-9
          and tr.exit_min[0] == 20 + C.OPEN_M, f"exit {tr.exit[0]} trig {trig:.2f}")
    check("stop gap fills at open", tr.why[1] == "stop" and abs(tr.exit[1] - float(adverse_bps(np.array([70.0]), 10, False)[0])) < 1e-9,
          f"exit {tr.exit[1]}")
    # b) target: resting, at the target or a better open
    p = dict(O=flat(100), H=flat(101), L=flat(99), Cl=flat(100))
    p["H"][30] = 140
    tr = synthetic_pack([p]).run(Exits(stop_pct=0.15, tgt_pct=0.3), exe)
    check("target at e x 1.3", tr.why[0] == "target" and abs(tr.exit[0] - float(Fills().sell(np.array([e * 1.3]))[0])) < 1e-9, f"{tr.exit[0]}")
    # c) stop wins a bar that hits both
    p["L"][30] = 50
    tr = synthetic_pack([p]).run(Exits(stop_pct=0.15, tgt_pct=0.3), exe)
    check("stop before target in one bar", tr.why[0] == "stop")
    # d) ladder: peak +R/2 locks +R/4; then a fall to the lock exits there (MARKET -5 bps)
    p = dict(O=flat(100), H=flat(100.5), L=flat(99.5), Cl=flat(100))
    p["H"][15] = 131                      # +31 on R = 60 -> rung 0.5 -> lock e + 15
    p["O"][16], p["H"][16], p["L"][16] = 125, 126, 110
    tr = synthetic_pack([p]).run(Exits(stop_pts=30, tgt_pts=60, ladder=LADDER, ladder_ref_pts=60), exe)
    check("ladder lock +R/4", tr.why[0] == "lock" and abs(tr.exit[0] - float(Fills().sell(np.array([e + 15]))[0])) < 1e-6
          and tr.exit_min[0] == 16 + C.OPEN_M, f"{tr.why[0]} {tr.exit[0]}")
    # e) the lock uses the peak BEFORE the bar: a bar that makes the high and falls back does not lock itself
    p = dict(O=flat(100), H=flat(100.5), L=flat(99.5), Cl=flat(100))
    p["H"][15], p["L"][15] = 131, 99
    tr = synthetic_pack([p]).run(Exits(stop_pts=30, ladder=LADDER, ladder_ref_pts=60), exe)
    check("no same-bar lock", tr.why[0] != "lock" or tr.exit_min[0] > 15 + C.OPEN_M, f"{tr.why[0]} at {tr.exit_min[0]}")
    # f) time stop: out at the open 20 minutes after the entry unless +5%
    p = dict(O=flat(100), H=flat(100.5), L=flat(99.5), Cl=flat(100))
    p["O"][30] = 98
    tr = synthetic_pack([p]).run(Exits(time_stop=20, time_gain=0.05), exe)
    check("time stop at entry + 20", tr.why[0] == "time_stop" and tr.exit_min[0] == 30 + C.OPEN_M
          and abs(tr.exit[0] - float(Fills().sell(np.array([98.0]))[0])) < 1e-9, f"{tr.why[0]} {tr.exit_min[0]}")
    # g) index stop: decided on the minute's index low, filled at the next open
    I = dict(h=flat(100), l=flat(100))
    I["l"][40] = 89
    p = dict(O=flat(100), H=flat(100.5), L=flat(99.5), Cl=flat(100))
    p["O"][41] = 90
    pk = synthetic_pack([p], index=[I], extra=[dict(idx_stop=90.0)])
    tr = pk.run(Exits(), exe)
    check("index stop next open", tr.why[0] == "index_stop" and tr.exit_min[0] == 41 + C.OPEN_M
          and abs(tr.exit[0] - float(Fills().sell(np.array([90.0]))[0])) < 1e-9, f"{tr.why[0]} {tr.exit_min[0]}")
    # h) square-off at the 15:10 open; missing bars skipped to the next print
    p = dict(O=flat(100), H=flat(100.5), L=flat(99.5), Cl=flat(100))
    sq = 15 * 60 + 10 - C.OPEN_M
    for k in ("O", "H", "L", "Cl"):
        p[k][sq:sq + 3] = np.nan
    tr = synthetic_pack([p]).run(Exits(sq_off=15 * 60 + 10), exe)
    check("square-off at next print", tr.why[0] == "square_off" and tr.exit_min[0] == 15 * 60 + 13, f"{tr.exit_min[0]}")
    # i) one position at a time per book: a signal inside an open trade is skipped
    t = pd.DataFrame(dict(book=["b"] * 3, day=[date(2025, 1, 2)] * 3, gate=[600, 610, 640], cand=[0, 1, 2], exit_min=[630, 650, 700],
                          net=[1.0, 2.0, 3.0], entry_min=[600, 610, 640]))
    k = positions(t)
    check("one position per book", list(k.cand) == [0, 2])
    # j) charges = the app's SandboxCosts (Decimal) on 1000 random orders; adverse() = Decimal half-even
    rng = np.random.default_rng(0)
    pr = np.round(rng.uniform(0.05, 1500, 1000), 2)
    qt = rng.choice([15, 25, 30, 50, 65, 75, 650], 1000)
    co = Costs()
    vec = co.charge(False, pr, qt)
    ex = np.array([co.charge_exact(False, a, b) for a, b in zip(pr, qt)])
    check("charges vectorised == Decimal", np.abs(vec - ex).max() < 0.005, f"max diff {np.abs(vec - ex).max():.4f}")
    dec = np.array([float((Decimal(a) * (1 + Decimal(5) / Decimal(10000))).quantize(Decimal('0.01'), rounding=ROUND_HALF_EVEN)) for a in pr])
    check("adverse +5 bps == Decimal", np.abs(adverse_bps(pr, 5, True) - dec).max() < 1e-9)
    tie = np.array([10.0, 30.0, 50.0])     # exact half-paisa ties (10.005 -> 10.00, 30.015 -> 30.02 half-even)
    check("adverse half-even ties", list(adverse_bps(tie, 5, True)) == [10.0, 30.02, 50.02], str(adverse_bps(tie, 5, True)))
    RES["unit_tests"] = T
    log(f"unit tests: {sum(x['ok'] for x in T)}/{len(T)} pass")
    for x in T:
        if not x["ok"]:
            log("  FAIL", x)


def cost_table():
    co_app, co_d = Costs("app"), Costs("dated")
    rows = []
    for d in ("2022-06-01", "2023-06-01", "2024-12-01", "2026-06-01"):
        dn = (date.fromisoformat(d) - date(1970, 1, 1)).days
        for e, x in ((100.0, 110.0), (400.0, 380.0)):
            q = 75
            a = float(co_app.charge(True, e, q) + co_app.charge(False, x, q))
            b = float(co_d.charge(True, e, q, dn) + co_d.charge(False, x, q, dn))
            rows.append(dict(date=d, buy=e, sell=x, qty=q, app=round(a, 2), dated=round(b, 2)))
    RES["cost_table"] = rows


# ------------------------------------------------------------------------------------------------ 2. parity
def parity():
    from .strategies import liquidity as LQ, solo as SO
    t0 = time.time()
    ls = signals_cached(LQ.STRATEGIES[0], LQ.STRATEGIES[0].sig_grid[0])
    ss = signals_cached(SO.STRATEGIES[0], SO.STRATEGIES[0].sig_grid[0])
    exl = replace(LQ.STRATEGIES[0].exe, exact=True)
    (pl, _), (ps, _) = prepare_many([(ls, StrikeRule(1), exl, 0, None, False), (ss, StrikeRule(0), SO.JARVIS_EXE, 0, None, False)])
    t1 = time.time()
    tr = positions(pl.run(LQ.ARM_EXITS, exl))
    t2 = time.time()
    ref = pd.read_csv(os.path.join(C.LIQX, "liqx_trades.csv"))
    ref = ref[ref.v == "A"]
    key = lambda d: d.book + "|" + d.day.astype(str) + "|" + d.entry_min.astype(str)  # noqa: E731
    mm = ref.assign(k=key(ref)).merge(tr.assign(k=key(tr)), on="k", how="outer", suffixes=("_r", "_o"), indicator=True)
    both = mm[mm._merge == "both"]
    RES["liquidity"] = dict(trades=len(tr), net=round(float(tr.net.sum()), 2), ref_trades=len(ref), ref_net=round(float(ref.net.sum()), 2),
                            matched=int(len(both)), same_net=int((np.abs(both.net_r - both.net_o) < 0.005).sum()),
                            only_ref=int((mm._merge == "left_only").sum()), only_obuy=int((mm._merge == "right_only").sum()),
                            prepare_s=round(t1 - t0, 1), exits_s=round(t2 - t1, 3),
                            per_year={int(k): round(float(v), 0) for k, v in tr.groupby("year").net.sum().items()},
                            why={k: round(float(v), 3) for k, v in tr.why.value_counts(normalize=True).items()})
    log("liquidity", RES["liquidity"])
    jx = pd.read_csv(os.path.join(C.JX, "jx_trades.csv"), low_memory=False)
    out = {}
    from .strategies.solo import C0
    cands = {"C0": C0, "X1": Exits(stop_pts=40, tgt_pts=80, ladder=LADDER, ladder_ref_pts=80, sq_off=15 * 60 + 15),
             "P1": Exits(stop_pct=0.15, tgt_pct=0.30, sq_off=15 * 60 + 15),
             "P4": Exits(stop_pct=0.20, tgt_pct=0.40, ladder=LADDER, ladder_ref_pct=0.40, sq_off=15 * 60 + 15),
             "T1": Exits(stop_pts=30, tgt_pts=60, ladder=LADDER, ladder_ref_pts=60, pine_trail=True, sq_off=15 * 60 + 15)}
    for nm, ex in cands.items():
        t = ps.run(ex, SO.JARVIS_EXE)
        r = jx[(jx.set == "SOLO") & (jx.x == nm)]
        m = r.assign(k=r.und + r.day.astype(str)).merge(t.assign(k=t.und + t.day.astype(str)), on="k", suffixes=("_r", "_o"))
        out[nm] = dict(trades=len(t), net=round(float(t.net.sum()), 2), ref_trades=len(r), ref_net=round(float(r.net.sum()), 2),
                       same_net=int((np.abs(m.net_r - m.net_o) < 0.005).sum()))
    RES["solo"] = out
    log("solo", out)


# ------------------------------------------------------------------------------------------------ 3. cross-checks
def cross_checks():
    from .strategies import bigbar as BB, hero as HE, straddle as SD
    res = {}
    flat_exe = Execution(fills=Fills("flat", pts=0.5), costs=Costs("flat", per_order=20.0), expiry="allow")
    # straddle 09:20 -> 15:10, BANKNIFTY, LONG_VOL.md years A / B, lot 30, 0.5 slippage, Rs 40 a leg
    jobs = []
    spans = {"A": ("2024-02-13", "2025-02-14"), "B": ("2025-02-17", "2026-02-23")}
    from .data import market
    mk = market()
    for k, (a, b) in spans.items():
        s = SD.signals(mk, at=920, lot=30, unds=("BANKNIFTY",), since=a, until=b)
        jobs.append((s, StrikeRule(0), flat_exe, 0, None, False))
    for k, (a, b) in spans.items():
        s = SD.signals(mk, at=920, lot=30, unds=("BANKNIFTY",), since=a, until=b)
        jobs.append((s, StrikeRule(0), replace(flat_exe, expiry="skip"), 0, None, False))
    for k, (a, b) in [("bigbar_BN", ("2025-02-17", "2026-02-23"))]:
        s = BB.signals(mk, top=0.2, depth=0.4, k=2.0, lot=30, unds=("BANKNIFTY",), since=a, until=b)
        jobs.append((s, StrikeRule(0), replace(flat_exe, expiry="allow"), 0, None, False))
    hs = signals_cached(HE.STRATEGIES[0], HE.STRATEGIES[0].sig_grid[0])
    jobs.append((hs, StrikeRule(0), HE.HERO_EXE, 0, None, False))
    packs = prepare_many(jobs)
    ex = Exits(sq_off=15 * 60 + 10, intrabar=False)
    for (pk, _), k in zip(packs[:2], spans):
        t = pk.run(ex, flat_exe)
        res[f"straddle920_BANKNIFTY_{k}"] = dict(days=len(t), net=round(float(t.net.sum()), 0), win=round(float((t.net > 0).mean()), 3),
                                                  ref={"A": "249 days, Rs -105,413, win 26%", "B": "249 days, Rs -114,707, win 33%"}[k])
    for (pk, _), k in zip(packs[2:4], spans):
        t = pk.run(ex, flat_exe)
        res[f"straddle920_BANKNIFTY_{k}_no_expiry_days"] = dict(days=len(t), net=round(float(t.net.sum()), 0),
                                                                avg=round(float(t.net.mean()), 0), win=round(float((t.net > 0).mean()), 3))
    packs = packs[:2] + packs[4:]
    t = positions(packs[2][0].run(Exits(sq_off=15 * 60 + 10), flat_exe), one_at_a_time=True, max_per_day=2)
    res["bigbar_BANKNIFTY_2025"] = dict(trades=len(t), net=round(float(t.net.sum()), 0), win=round(float((t.net > 0).mean()), 3),
                                        ref="285 trades, Rs +7,468, option win 42% (COMBINED.md, exit at the option close of the index-exit minute)")
    t = positions(packs[3][0].run(Exits(sq_off=15 * 60 + 5), HE.HERO_EXE), one_at_a_time=True, max_per_day=1)
    res["hero_NIFTY"] = dict(trades=len(t), wins=int((t.net > 0).sum()), net=round(float(t.net.sum()), 0),
                             best_day=str(t.loc[t.net.idxmax(), "day"]) if len(t) else None, best=round(float(t.net.max()), 0) if len(t) else None,
                             per_year={int(k): round(float(v), 0) for k, v in t.groupby("year").net.sum().items()},
                             ref="61 trades, 6 wins, Rs +230,521 (realistic fills), best 2024-09-12 +205,601")
    RES["cross"] = res
    log("cross", res)


# ------------------------------------------------------------------------------------------------ 4. statistics calibration
def stats_calibration():
    rng = np.random.default_rng(11)
    T, N = 1200, 300
    noise = rng.normal(0, 1000, (T, N))                       # 300 variants, no edge
    sp0 = OF.spa(noise, B=500)
    pb0 = OF.pbo(noise, S=16, max_combos=2000)
    edge = noise.copy()
    edge[:, 0] += 150                                          # one real edge: +0.15 sd a day (Sharpe ~2.4 a year)
    sp1 = OF.spa(edge, B=500)
    srs = noise.mean(0) / noise.std(0, ddof=1)
    best = int(np.argmax(srs))
    d0 = OF.dsr(noise[:, best], srs, N)
    srs1 = edge.mean(0) / edge.std(0, ddof=1)
    d1 = OF.dsr(edge[:, 0], srs1, N)
    # BH / Holm on uniform p's and on p's with 10 true signals
    pu = rng.uniform(size=1000)
    ps = np.concatenate([rng.uniform(0, 1e-4, 10), rng.uniform(size=990)])
    # random-baseline p calibration: real trades drawn from the same distribution as the pool -> p ~ U(0,1)
    pvals = []
    for s in range(200):
        r2 = np.random.default_rng(100 + s)
        pool = pd.DataFrame(dict(parent=np.repeat(np.arange(100), 10), net=r2.normal(-50, 1000, 1000)))
        real = pd.DataFrame(dict(cand=np.arange(100), net=r2.normal(-50, 1000, 100)))
        pvals.append(OF.random_baseline(real, pool, B=500, seed=s)["p"])
    pvals = np.array(pvals)
    RES["stats"] = dict(
        spa_noise=dict(rc_p=sp0["rc_p"], spa_p=sp0["spa_p"]), spa_edge=dict(rc_p=sp1["rc_p"], spa_p=sp1["spa_p"], best=sp1["best"]),
        pbo_noise=pb0["pbo"], dsr_noise_best=d0["dsr"], dsr_true_edge=d1["dsr"],
        bh_uniform_rejections=int((OF.bh(pu) < 0.05).sum()), bh_signal_rejections=int((OF.bh(ps) < 0.05).sum()),
        holm_signal_rejections=int((OF.holm(ps) < 0.05).sum()),
        rand_p_null_share_below_05=float((pvals < 0.05).mean()), rand_p_null_mean=float(pvals.mean()))
    log("stats", RES["stats"])


def main():
    os.makedirs(OUT, exist_ok=True)
    t0 = time.time()
    unit_tests()
    cost_table()
    stats_calibration()
    parity()
    cross_checks()
    RES["total_s"] = round(time.time() - t0, 1)
    with open(os.path.join(OUT, "validation.json"), "w") as f:
        json.dump(RES, f, indent=1, default=str)
    log("done", RES["total_s"], "s ->", os.path.join(OUT, "validation.json"))
