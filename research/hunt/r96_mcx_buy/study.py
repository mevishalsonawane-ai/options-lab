"""R96 study (PREREG_ROUND1.md). Commands:

    python -m research.hunt.r96_mcx_buy.study design [round]            # every rule of that round on the design period
    python -m research.hunt.r96_mcx_buy.study holdout <rule> [round]    # the LOCKED holdout, once per passing rule

Environment: IRAALGO_LAKE (the Drive lake copy), IRAALGO_CACHE (R53 markets, R96 books). Results: small JSON / CSV /
markdown in results/. The holdout refuses to run for a rule that did not pass every design gate, and refuses a second
read (results/HOLDOUT_READ_<rule>.json).
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone

import numpy as np
import pandas as pd

from research.hunt.r96_mcx_buy import cfg as K
from research.hunt.r96_mcx_buy import engine as E
from research.hunt.r96_mcx_buy import rules as R


# ================================================================================================ simulate
def simulate(book, sp: dict, last_day):
    if sp is None:
        return None
    if sp["kind"] == "straddle":
        if sp.get("i_end_fixed") is not None:
            return E.sim_straddle(book, sp["i_sig"], None, last_day, sp["i_from"], sp["i_end_fixed"],
                                  stop_frac=K.EV_STOP, ladder=K.EV_LADDER, trail_from=K.EV_TRAIL_FROM,
                                  trail_gap=K.EV_TRAIL_GAP, fill_wait=sp.get("fill_wait", K.FILL_WAIT))
        return E.sim_straddle(book, sp["i_sig"], sp["n_sessions"], last_day, sp["i_from"],
                              edge_exit=sp.get("edge", False))
    return E.sim_single(book, sp["i_sig"], sp["side"], sp["ustop"], sp["n_sessions"], last_day, sp["i_from"],
                        offset=sp.get("itm"), edge_exit=sp.get("edge", False), max_risk=sp.get("max_risk"))


def run_rule(rid: str, books: dict, period) -> list:
    """All trades of rule rid over `period`, one position per book at a time (a signal while one is open is skipped)."""
    rule = R.RULES[rid]
    out = []
    for name in getattr(rule, "books", K.BOOK_ORDER):
        b = books[name]
        busy_until = None
        for sp in sorted(rule.candidates(b, period), key=lambda s: s["i_from"]):
            if busy_until is not None and b.m.ts[sp["i_from"]] <= busy_until:
                continue
            t = simulate(b, sp, period[1])
            if t is None:
                continue
            t["rule"], t["spec"] = rid, sp
            busy_until = np.datetime64(t["exit_ts"])
            out.append(t)
    return out


# ================================================================================================ twins
class TwinCache:
    def __init__(self):
        self.c = {}

    def get(self, rid, book, sp, day, last_day):
        rule = R.RULES[rid]
        key = (rid, book.name, day, sp.get("side"), sp.get("tod"), sp.get("rel"),
               None if sp.get("katr") is None else round(float(sp["katr"]), 2))
        if key not in self.c:
            ts = rule.twin(book, sp, day)
            self.c[key] = None if ts is None else simulate(book, ts, last_day)
        return self.c[key]


def twins(rid: str, trades: list, books: dict, period, n: int = K.TWINS, seed: int = K.SEED):
    """Twin-set totals (net, 1x costs). Each trade -> the same trade on a random session of the same book and calendar
    month in the period (not its own day); up to 20 redraws, then 0."""
    rng = np.random.default_rng(seed)
    cache = TwinCache()
    tot = np.zeros(n)
    pools = {}
    for t in trades:
        b = books[t["book"]]
        d0 = np.datetime64(t["day"], "ns")
        key = (t["book"], pd.Timestamp(d0).year, pd.Timestamp(d0).month)
        if key not in pools:
            days = [d for d in b.m.days if pd.Timestamp(d).year == key[1] and pd.Timestamp(d).month == key[2]
                    and R.in_p(d, period)]
            pools[key] = days
        pool = [d for d in pools[key] if d != d0]
        if not pool:
            continue
        for s in range(n):
            v = 0.0
            for _ in range(20):
                d = pool[rng.integers(len(pool))]
                x = cache.get(rid, b, t["spec"], d, period[1])
                if x is not None:
                    v = x["net"]
                    break
            tot[s] += v
    return tot


# ================================================================================================ statistics
def frame(trades: list) -> pd.DataFrame:
    if not trades:
        return pd.DataFrame(columns=["book", "entry_ts", "exit_ts", "net", "net2", "gross", "cost"])
    keep = ["rule", "book", "day", "entry_ts", "exit_ts", "side", "kind", "why", "model", "stale", "gross", "cost",
            "net", "net2", "prem_rs", "risk_rs"]
    df = pd.DataFrame([{k: t.get(k) for k in keep} for t in trades])
    df["strike"] = [",".join(f"{x:g}" for x in t["strike"]) for t in trades]
    df["fill"] = [",".join(f"{x:g}" for x in t["fill"]) for t in trades]
    df["exit"] = [",".join(f"{x:g}" for x in t["exit"]) for t in trades]
    return df.sort_values("exit_ts").reset_index(drop=True)


def summarize(df: pd.DataFrame) -> dict:
    n = len(df)
    if n == 0:
        return dict(trades=0, net=0.0, net2=0.0)
    net = df.net.values
    cum = np.cumsum(net)
    dd = float(np.min(cum - np.maximum.accumulate(np.r_[0.0, cum])[1:])) if n else 0.0
    month = df.groupby(pd.DatetimeIndex(df.exit_ts).to_period("M")).net.sum()
    srt = np.sort(net)[::-1]
    wins, losses = net[net > 0], net[net <= 0]
    risk = df.risk_rs.astype(float).values
    return dict(
        trades=n, win=float((net > 0).mean()), avg_win=float(wins.mean()) if len(wins) else 0.0,
        avg_loss=float(losses.mean()) if len(losses) else 0.0, gross=float(df.gross.sum()), cost=float(df.cost.sum()),
        net=float(net.sum()), net2=float(df.net2.sum()), per_trade=float(net.mean()),
        worst_month=float(month.min()), months_below_stop=int((month < K.STOP_MONTH).sum()), months=int(len(month)),
        max_dd=dd, wo_top3=float(srt[K.GATE_WO_TOP:].sum()),
        model_share=float(df.model.mean()), stale_share=float(df.stale.mean()),
        real_exit_trades=int((~df.model.astype(bool)).sum()), real_exit_net=float(df.net[~df.model.astype(bool)].sum()),
        why={k: int(v) for k, v in df.why.value_counts().items()},
        risk_median=float(np.median(risk)), cap2_median=float(np.median(risk) / 0.02),
        cap1_median=float(np.median(risk) / 0.01), cap2_max=float(risk.max() / 0.02),
        by_book={b: dict(trades=int(len(g)), net=float(g.net.sum()), gross=float(g.gross.sum()),
                         win=float((g.net > 0).mean())) for b, g in df.groupby("book")},
        by_month={str(k): float(v) for k, v in month.items()})


def folds(df: pd.DataFrame) -> list:
    out = []
    for k, (a, b) in enumerate(K.FOLDS):
        days = pd.DatetimeIndex(df.day).date if len(df) else []
        g = df[[(a <= x <= b) for x in days]] if len(df) else df
        out.append(dict(fold=k + 1, start=str(a), end=str(b), trades=int(len(g)),
                        net=float(g.net.sum()) if len(g) else 0.0))
    return out


def bh(ps: list) -> list:
    """Benjamini-Hochberg q-values (NaN p -> q 1)."""
    p = np.array([1.0 if (x is None or not np.isfinite(x)) else x for x in ps], float)
    m = len(p)
    if m == 0:
        return []
    o = np.argsort(p)
    q = np.empty(m)
    run = 1.0
    for r in range(m - 1, -1, -1):
        i = o[r]
        run = min(run, p[i] * m / (r + 1))
        q[i] = run
    return [float(x) for x in q]


def gates(s: dict, fl: list, p: float, q: float, hist: dict | None, round_no: int = 1) -> dict:
    g = dict(
        trades=s["trades"] >= K.GATE_MIN_TRADES,
        net=s.get("net", 0) > 0,
        net2=s.get("net2", 0) > 0,
        twin_p=np.isfinite(p) and p < K.GATE_P,
        bh_q=np.isfinite(q) and q <= K.GATE_Q,
        folds=sum(f["net"] > 0 for f in fl) >= K.GATE_FOLDS_POS,
        worst_month=s.get("worst_month", -np.inf) > K.STOP_MONTH if s["trades"] else False,
        max_dd=s.get("max_dd", -np.inf) > K.STOP_DD if s["trades"] else False,
        wo_top3=s.get("wo_top3", 0) > 0,
    )
    if round_no >= 2:              # PREREG_ROUND2 s2 gate 8
        g["real_exits"] = bool(s["trades"] and s["model_share"] <= K.GATE_MODEL_SHARE and s["real_exit_net"] > 0)
    if hist is not None:
        g["history"] = bool(np.isfinite(hist.get("mean", np.nan)) and hist["mean"] > 0)
    return g


def is_lead(s, fl, p) -> bool:
    return bool((s.get("net", 0) > 0 and sum(f["net"] > 0 for f in fl) >= 2) or (np.isfinite(p) and p < 0.10))


# ================================================================================================ ledger
LEDGER_JSON = K.RESULTS / "ledger.json"


def load_ledger() -> list:
    if LEDGER_JSON.exists():
        return json.loads(LEDGER_JSON.read_text())
    return []


def save_ledger(rows: list):
    q = bh([r.get("p") for r in rows])
    for r, x in zip(rows, q):
        r["q"] = x
    LEDGER_JSON.write_text(json.dumps(rows, indent=1, default=str))
    return rows


# ================================================================================================ commands
def load_books():
    from research.hunt.r96_mcx_buy import data as D
    return {n: D.load_book(n) for n in K.ALL_BOOKS}


def design(round_no: int = 1, only=None):
    from research.hunt.r96_mcx_buy import history as H
    books = load_books()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    res = {}
    all_df = []
    for rid in (only or (R.ORDER if round_no == 1 else R.ORDER2)):
        trades = run_rule(rid, books, K.DESIGN)
        df = frame(trades)
        all_df.append(df)
        s = summarize(df)
        fl = folds(df)
        tw = twins(rid, trades, books, K.DESIGN) if trades else np.zeros(K.TWINS)
        p = float((np.sum(tw >= s.get("net", 0)) + 1) / (K.TWINS + 1)) if trades else float("nan")
        hist = None
        if rid in R.HISTORY_RULES:
            hist = {}
            for name in getattr(R.RULES[rid], "books", K.BOOK_ORDER):
                hist[name] = H.run(rid, books[name].daily)
            rr = [h for h in hist.values() if h["n"]]
            n = sum(h["n"] for h in rr)
            hist["pooled"] = dict(n=n, mean=float(sum(h["mean"] * h["n"] for h in rr) / n) if n else float("nan"))
        res[rid] = dict(summary=s, folds=fl, twin_mean=float(tw.mean()), twin_p=p, history=hist)
        print(rid, s.get("trades"), round(s.get("net", 0)), "p", round(p, 3), flush=True)
    rows = [r for r in load_ledger() if not (r["round"] == round_no and r["id"] in res)]
    for rid, r in res.items():
        rows.append(dict(round=round_no, id=rid, trades=r["summary"]["trades"], net=r["summary"].get("net", 0.0),
                         p=r["twin_p"]))
    rows = save_ledger(rows)
    qmap = {(r["round"], r["id"]): r["q"] for r in rows}
    for rid, r in res.items():
        q = qmap[(round_no, rid)]
        h = r["history"]["pooled"] if r["history"] else None
        r["bh_q"] = q
        r["gates"] = gates(r["summary"], r["folds"], r["twin_p"], q, h, round_no)
        r["pass"] = bool(all(r["gates"].values()))
        r["lead"] = is_lead(r["summary"], r["folds"], r["twin_p"])
    out = dict(round=round_no, written=datetime.now(timezone.utc).isoformat(timespec="seconds"),
               design=[str(x) for x in K.DESIGN], ideas_in_ledger=len(rows), rules=res)
    (K.RESULTS / f"design_round{round_no}.json").write_text(json.dumps(out, indent=1, default=str))
    pd.concat(all_df, ignore_index=True).to_csv(K.RESULTS / f"trades_design_round{round_no}.csv", index=False)
    return out


def holdout(rid: str, round_no: int = 1):
    lock = K.RESULTS / f"HOLDOUT_READ_{rid}.json"
    if lock.exists():
        raise SystemExit(f"holdout already read for {rid}: {lock}")
    dj = json.loads((K.RESULTS / f"design_round{round_no}.json").read_text())
    r = dj["rules"].get(rid)
    if r is None or not r.get("pass"):
        raise SystemExit(f"{rid} did not pass every design gate; the holdout stays locked")
    lock.write_text(json.dumps(dict(rule=rid, round=round_no, read_at=datetime.now(timezone.utc).isoformat()), indent=1))
    books = load_books()
    trades = run_rule(rid, books, K.HOLDOUT)
    df = frame(trades)
    s = summarize(df)
    tw = twins(rid, trades, books, K.HOLDOUT) if trades else np.zeros(K.TWINS)
    p = float((np.sum(tw >= s.get("net", 0)) + 1) / (K.TWINS + 1)) if trades else float("nan")
    ok = dict(net=s.get("net", 0) > 0, trades=s["trades"] >= K.HOLD_MIN_TRADES, twin_p=p <= K.HOLD_P,
              worst_month=s.get("worst_month", -np.inf) > K.STOP_MONTH, max_dd=s.get("max_dd", -np.inf) > K.STOP_DD)
    out = dict(rule=rid, holdout=[str(x) for x in K.HOLDOUT], summary=s, twin_p=p, twin_mean=float(tw.mean()),
               gates=ok, pass_=bool(all(ok.values())))
    (K.RESULTS / f"holdout_{rid}.json").write_text(json.dumps(out, indent=1, default=str))
    df.to_csv(K.RESULTS / f"trades_holdout_{rid}.csv", index=False)
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd == "design":                      # design [round] [rule ...]
        args = sys.argv[2:]
        rnd = int(args.pop(0)) if args and args[0].isdigit() else 1
        design(rnd, only=args or None)
    elif cmd == "holdout":                   # holdout <rule> [round]
        print(json.dumps(holdout(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 1), indent=1, default=str))
    else:
        raise SystemExit(__doc__)
