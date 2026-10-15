"""R59b runner (PREREG.md).

    R59_LAKE=<lake> python3 -I research/hunt/r59b/r59brun.py literal   # steps 1-2 -> results/literal.json
    R59_LAKE=<lake> python3 -I research/hunt/r59b/r59brun.py design    # design.json (R59's frozen spread.json)
    R59_LAKE=<lake> python3 -I research/hunt/r59b/r59brun.py final     # the one final read -> final.json + final_read.json
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

import r59bcfg as K  # noqa: E402
import r59blit as BL  # noqa: E402
import r59bsim as BS  # noqa: E402
import r59cfg as K59  # noqa: E402
import r59data as D  # noqa: E402
import r59lit as LT  # noqa: E402
import r59sim as S  # noqa: E402

SIDE_NAME = {0: "CE", 1: "PE"}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ------------------------------------------------------------------------------------------------ phase lock
class _Key:
    """Proof that the R59b final-test lock was checked (only final_key() makes one)."""


_KEY = None


def final_key():
    global _KEY
    if K.FINAL_LOCK.exists():
        raise RuntimeError(f"refused: the R59b final test was already read ({K.FINAL_LOCK.name} exists)")
    if not (K.RESULTS / "design.json").exists():
        raise RuntimeError("refused: no frozen design results (results/design.json)")
    _KEY = _Key()
    return _KEY


def phase_end(phase: str, key=None) -> date:
    if phase == "design":
        return K.DESIGN_END
    if phase == "final":
        if key is None or key is not _KEY:
            raise RuntimeError("refused: the final phase needs final_key()")
        return K.FINAL_END
    raise ValueError(phase)


def period(d: date) -> str:
    if d < K.START:
        return "pre"
    return "design" if d <= K.DESIGN_END else "final"


def load_books(phase, key=None):
    end = phase_end(phase, key)
    bhav = D.read_bhav()
    eq_all = D.read_equity(list(K.STOCKS), K.START, min(end, date(2030, 1, 1)))
    books = []
    for sym in K.STOCKS:
        opt = D.read_options(sym, K.START, min(end, date(2030, 1, 1)))
        eq = eq_all[eq_all.sym == sym]
        if phase == "design":
            D.assert_design_days(np.unique(opt.d.to_numpy()))
            D.assert_design_days(np.unique(eq.d.to_numpy()))
        last = max(int(opt.d.max()), int(eq.d.max()))
        books.append(D.build_book(sym, opt, eq, bhav, K.START, date.fromordinal(last)))
        log("book", sym, books[-1].info)
    return books


# ------------------------------------------------------------------------------------------------ steps 1-2
def literal():
    bhav = D.read_bhav()
    out, allT, dates = {}, [], set()
    for sym in K.STOCKS:
        opt = D.read_options(sym, K.LIT_START, K.LIT_END)
        exps = D.expiries_of(bhav, sym)
        for side, nm in (("C", "CE"), ("P", "PE")):
            b = LT.bars15(opt, side, exps)
            dates |= set(int(x) for x in b.d)
            T = BL.their_trades(b)
            T["held"] = LT.held_value(opt, b, T, side)
            T["sym"], T["side"] = sym, nm
            out[f"{sym}_{nm}"] = BL.summary(T)
            allT.append(T)
            log(sym, nm, out[f"{sym}_{nm}"]["trades"], out[f"{sym}_{nm}"].get("per_trade"))
    A = pd.concat(allT, ignore_index=True)
    res = dict(window=[K.LIT_START.isoformat(), K.LIT_END.isoformat()],
               label="a check of their claim only (their own window)", pooled=BL.summary(A),
               pooled_CE=BL.summary(A[A.side == "CE"]), pooled_PE=BL.summary(A[A.side == "PE"]),
               claim_checks=BL.claim_checks(A, len(dates)), cells=out)
    D.save_json(K.RESULTS / "literal.json", res)
    p = res["pooled"]
    log("pooled", p["trades"], p["win"], p["per_trade"], p["median"], p["total"], res["claim_checks"]["max_drawdown"])


# ------------------------------------------------------------------------------------------------ step 3
def cells_for(books, spread, per):
    """All 126 cells' trades and twin totals on sessions of period `per`."""
    res, alltr = {}, {}
    pools = {v: {} for v in K.VARIANTS}
    sessions, dates = {}, set()
    for bk in books:
        ok = np.array([period(date.fromordinal(int(d))) == per for d in bk.days])
        el = ok & ~bk.skip
        sessions[bk.sym] = int(el.sum())
        dates |= set(int(x) for x in bk.days[el])
        H = BS.held_bars(bk)
        SB = BS.stock_bars(bk)
        for sd in (0, 1):
            nm = SIDE_NAME[sd]
            rh = spread[f"{bk.sym}_{nm}"]["half"]
            sk = {}
            for di in np.flatnonzero(el):
                for b in range(1, K59.NBARS - 1):
                    s = S.skeleton(bk, int(di), b, sd)
                    if s is not None:
                        sk[(int(di), b)] = s
            for v in K.VARIANTS:
                pool = {}
                for (di, b), s in sk.items():
                    pool.setdefault(di, []).append(BS.price(bk, s, v, rh, 1.0)["net"])
                pools[v][(bk.sym, nm)] = {di: np.array(x) for di, x in pool.items()}
            sigA = BS.signals_A(bk, sd, H, ok)
            sigB = BS.signals_B(bk, sd, SB, ok)
            for fam, sigs in (("A", sigA), ("B", sigB)):
                for v in K.VARIANTS:
                    alltr[(fam, v, bk.sym, nm)] = BS.trades_for(bk, sigs, v, rh)
            log(per, bk.sym, nm, "bars", len(sk), "sigA", len(sigA), "sigB", len(sigB))
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
                    res[f"{fam}-{v}-{sym}-{nm}"] = BS.metrics(tr, tw, sessions[sym])
            rng = np.random.default_rng([K.SEED, i])
            i += 1
            tw = S.twin_totals(pooled, pools[v], rng) if pooled else None
            res[f"{fam}-{v}-ALL"] = BS.metrics(pooled, tw, len(dates))
    return res, alltr


def spread():
    return json.loads(K.SPREAD_JSON.read_text())


def design():
    if K.FINAL_LOCK.exists():
        raise SystemExit("refused: the final test was already read; design is frozen")
    books = load_books("design")
    res, alltr = cells_for(books, spread(), "design")
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


def tuning_verdict(des, fin):
    """PREREG: worth trying only if pooled gross per trade > 0 with t >= 2 in design AND final, and the final gross per
    trade covers the median 1x cost per trade."""
    out = {}
    for fam in K.FAMILIES:
        for v in K.VARIANTS:
            n = f"{fam}-{v}-ALL"
            d, f = des.get(n, {}), fin.get(n, {})
            ok = (d.get("trades", 0) > 1 and f.get("trades", 0) > 1 and d.get("gross_per_trade", 0) > 0
                  and f.get("gross_per_trade", 0) > 0 and (d.get("gross_t") or 0) >= K.TUNE_T
                  and (f.get("gross_t") or 0) >= K.TUNE_T and f.get("gross_per_trade", 0) >= f.get("cost_median", 1e18))
            out[n] = dict(design_gross=d.get("gross_per_trade"), design_t=d.get("gross_t"),
                          final_gross=f.get("gross_per_trade"), final_t=f.get("gross_t"),
                          final_cost_median=f.get("cost_median"), worth_trying=bool(ok))
    return out


def final():
    key = final_key()
    des = json.loads((K.RESULTS / "design.json").read_text())["cells"]
    D.save_json(K.FINAL_LOCK, dict(read_at=time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime())))
    books = load_books("final", key)
    res, alltr = cells_for(books, spread(), "final")
    names = list(res)
    q = S.bh([1.0 if res[n].get("p") is None else res[n]["p"] for n in names])   # PREREG: no trade counts as p = 1
    for n, qq in zip(names, q):
        res[n]["q"] = qq if res[n].get("p") is not None else None
        res[n]["fails"] = gate(res[n], des.get(n, {}))
        res[n]["pass"] = not res[n]["fails"]
    trades = []
    for (fam, v, sym, nm), tr in alltr.items():
        for t in tr:
            trades.append(dict(fam=fam, var=v, **{k: t[k] for k in ("sym", "side", "date", "net", "gross0", "reason")}))
    T = pd.DataFrame(trades)
    by_month = {}
    if len(T):
        T["mo"] = T.date.str[:7]
        for (fam, v), g in T.groupby(["fam", "var"]):
            by_month[f"{fam}-{v}"] = g.groupby("mo").net.agg(["size", "sum"]).round(0).astype(int).to_dict("index")
    D.save_json(K.RESULTS / "final.json", dict(
        cells=res, by_month=by_month, tuning=tuning_verdict(des, res), books={b.sym: b.info for b in books},
        last_session=max(date.fromordinal(int(b.days[-1])) for b in books)))
    log("passing cells:", [n for n in names if res[n]["pass"]])
    for n in names:
        if n.endswith("ALL"):
            log(n, res[n])


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    {"literal": literal, "design": design, "final": final}[cmd]()
