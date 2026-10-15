"""R59 runner (PREREG.md).

    R59_LAKE=<lake> python3 -I research/hunt/r59/r59run.py literal   # steps 1-2 -> results/literal.json
    R59_LAKE=<lake> python3 -I research/hunt/r59/r59run.py design    # spread.json (frozen) + design.json
    R59_LAKE=<lake> python3 -I research/hunt/r59/r59run.py final     # the one final read -> final.json + final_read.json
"""
from __future__ import annotations

import sys
import time
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import json  # noqa: E402

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import r59cfg as K  # noqa: E402
import r59data as D  # noqa: E402
import r59lit as LT  # noqa: E402
import r59sim as S  # noqa: E402

SIDE_NAME = {0: "CE", 1: "PE"}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ------------------------------------------------------------------------------------------------ steps 1-2
def literal():
    bhav = D.read_bhav()
    out, allT = {}, []
    for sym in K.STOCKS:
        opt = D.read_options(sym, K.LIT_START, K.LIT_END)
        exps = D.expiries_of(bhav, sym)
        for side, nm in (("C", "CE"), ("P", "PE")):
            b = LT.bars15(opt, side, exps)
            T = LT.their_trades(b)
            T["held"] = LT.held_value(opt, b, T, side)
            T["sym"], T["side"] = sym, nm
            out[f"{sym}_{nm}"] = LT.summary(T)
            allT.append(T)
            log(sym, nm, out[f"{sym}_{nm}"]["trades"], out[f"{sym}_{nm}"].get("per_trade"))
    A = pd.concat(allT, ignore_index=True)
    res = dict(window=[K.LIT_START.isoformat(), K.LIT_END.isoformat()], pooled=LT.summary(A),
               pooled_CE=LT.summary(A[A.side == "CE"]), pooled_PE=LT.summary(A[A.side == "PE"]), cells=out)
    yr = A.assign(y=pd.to_datetime(A.ts).dt.year).groupby("y").pnl.agg(["size", "mean", "sum"]).round(0)
    res["by_year"] = {int(k): dict(trades=int(v["size"]), per_trade=float(v["mean"]), total=float(v["sum"]))
                      for k, v in yr.iterrows()}
    D.save_json(K.RESULTS / "literal.json", res)
    log("pooled", res["pooled"]["trades"], res["pooled"]["win"], res["pooled"]["per_trade"], res["pooled"]["total"])


# ------------------------------------------------------------------------------------------------ step 3
def cells_for(books, spread, period):
    """All 84 cells' trades and twin totals on sessions of `period`."""
    res, alltr = {}, {}
    pools = {v: {} for v in K.VARIANTS}
    for bk in books:
        ok = np.array([K.period(date.fromordinal(int(d))) == period for d in bk.days])
        for sd in (0, 1):
            nm = SIDE_NAME[sd]
            rh = spread[f"{bk.sym}_{nm}"]["half"]
            sk = {}
            for di in np.flatnonzero(ok & ~bk.skip):
                for b in range(1, K.NBARS - 1):
                    s = S.skeleton(bk, int(di), b, sd)
                    if s is not None:
                        sk[(int(di), b)] = s
            for v in K.VARIANTS:
                pool = {}
                for (di, b), s in sk.items():
                    pool.setdefault(di, []).append(S.price(bk, s, v, rh, 1.0)["net"])
                pools[v][(bk.sym, nm)] = {di: np.array(x) for di, x in pool.items()}
            sigA = S.signals_A(bk, sd, ok)
            sigB = S.signals_B(bk, sd, ok)
            for fam, sigs in (("A", sigA), ("B", sigB)):
                for v in K.VARIANTS:
                    alltr[(fam, v, bk.sym, nm)] = S.trades_for(bk, sigs, v, rh)
            log(period, bk.sym, nm, "bars", len(sk), "sigA", len(sigA), "sigB", len(sigB))
    i = 0
    for fam in K.FAMILIES:
        for v in K.VARIANTS:
            pooled = []
            for sym in K.STOCKS:
                for nm in ("CE", "PE"):
                    tr = alltr.get((fam, v, sym, nm), [])
                    pooled += tr
                    rng = np.random.default_rng([K.SEED, i])
                    i += 1
                    tw = S.twin_totals(tr, pools[v], rng) if tr else None
                    res[f"{fam}-{v}-{sym}-{nm}"] = S.metrics(tr, tw)
            rng = np.random.default_rng([K.SEED, i])
            i += 1
            tw = S.twin_totals(pooled, pools[v], rng) if pooled else None
            res[f"{fam}-{v}-ALL"] = S.metrics(pooled, tw)
    return res, alltr


def load_books(phase, key=None):
    end = min(D.phase_end(phase, key), K.LIT_END)
    bhav = D.read_bhav()
    eq = D.read_equity(list(K.STOCKS), K.START, end)
    books = []
    for sym in K.STOCKS:
        books.append(D.load_book(sym, phase, key, bhav=bhav, eq_all=eq))
        log("book", sym, books[-1].info)
    return books


def design():
    if K.FINAL_LOCK.exists():
        raise SystemExit("refused: the final test was already read; design is frozen")
    books = load_books("design")
    spread = {}
    for bk in books:
        for sd in (0, 1):
            rs, n = S.roll_spread(bk, sd)
            spread[f"{bk.sym}_{SIDE_NAME[sd]}"] = dict(roll=round(rs, 5), half=round(rs / 2, 5), contract_days=n)
    D.save_json(K.SPREAD_JSON, spread)
    log("spread", spread)
    res, alltr = cells_for(books, spread, "design")
    D.save_json(K.RESULTS / "design.json", dict(cells=res, books={b.sym: b.info for b in books}))
    for k, v in res.items():
        if k.endswith("ALL"):
            log(k, v)


def gate(fin, des):
    why = []
    if fin.get("trades", 0) < K.GATE_TRADES:
        why.append("trades<40")
    if not fin.get("net", 0) > 0:
        why.append("final net<=0")
    if fin.get("p") is None or fin["p"] >= K.GATE_P:
        why.append("p>=0.05")
    if fin.get("q") is None or fin["q"] > K.GATE_Q:
        why.append("q>0.10")
    if not fin.get("net_wo_top5", 0) > 0:
        why.append("top5>=100%")
    if not des.get("net", 0) > 0:
        why.append("design net<=0")
    return why


def final():
    key = D.final_key()
    spread = json.loads(K.SPREAD_JSON.read_text())
    des = json.loads((K.RESULTS / "design.json").read_text())["cells"]
    D.save_json(K.FINAL_LOCK, dict(read_at=time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime())))
    books = load_books("final", key)
    res, alltr = cells_for(books, spread, "final")
    names = list(res)
    q = S.bh([res[n].get("p") for n in names])
    for n, qq in zip(names, q):
        res[n]["q"] = qq
        res[n]["fails"] = gate(res[n], des.get(n, {}))
        res[n]["pass"] = not res[n]["fails"]
    trades = []
    for (fam, v, sym, nm), tr in alltr.items():
        for t in tr:
            trades.append(dict(fam=fam, var=v, **{k: t[k] for k in ("sym", "side", "date", "net", "net2", "capital", "reason")}))
    T = pd.DataFrame(trades)
    by_month = {}
    if len(T):
        T["mo"] = T.date.str[:7]
        for (fam, v), g in T.groupby(["fam", "var"]):
            by_month[f"{fam}-{v}"] = g.groupby("mo").net.agg(["size", "sum"]).round(0).astype(int).to_dict("index")
    D.save_json(K.RESULTS / "final.json", dict(cells=res, by_month=by_month, books={b.sym: b.info for b in books},
                                               last_session=max(date.fromordinal(int(b.days[-1])) for b in books)))
    log("passing cells:", [n for n in names if res[n]["pass"]])
    for n in names:
        if n.endswith("ALL"):
            log(n, res[n])


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    {"literal": literal, "design": design, "final": final}[cmd]()
