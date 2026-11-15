"""R180 study (PREREG.md): rebuild the manual's two books on real option prices, coordinate, twin-test, gate, report.

    python research/hunt/r180_dual_book_check/build_stores.py      # once: R51's clean stores, 1 Nov 2024 - 19 Oct 2026
    python research/hunt/r180_dual_book_check/r180study.py run     # everything -> results/results.json
    python research/hunt/r180_dual_book_check/r180study.py report  # results/results.json -> results/report.md

`run` writes results/FINAL_READ first and logs every later run in it (a re-run after a bug fix is written down there and
in the report, never hidden). Priced trades go to $R180_OUT (never in git).
"""
from __future__ import annotations

import json
import sys
from collections import Counter, defaultdict
from datetime import datetime

import numpy as np
import pandas as pd

import r180cfg as K
import r180engine as G
import r180rules as RU

MODES = ("close", "open")


# ================================================================================================ data
def load_all():
    K.use_stores()
    import fam_r44
    import r51data
    stores = {u: r51data.load(u) for u in K.UNDS}
    bars = {u: fam_r44.bars5(u) for u in K.UNDS}
    mins = {u: {d: D.idx for d, D in stores[u].items()} for u in K.UNDS}
    strad = {}
    for u in K.UNDS:
        strad[u] = {}
        for d, D in stores[u].items():
            if d < K.START or D.atm is None:
                continue
            strad[u][d] = RU.straddle_vs_0920(RU.straddle_series(D.atm, D.con))
    return stores, bars, mins, strad


def in_window(d) -> bool:
    return K.period_of(d) is not None


# ================================================================================================ metrics
def max_dd(nets) -> float:
    eq = np.concatenate([[0.0], np.cumsum(nets)])
    return float((eq - np.maximum.accumulate(eq)).min()) if len(nets) else 0.0


def summary(tr: list, key="net") -> dict:
    n = len(tr)
    if not n:
        return dict(trades=0, win=None, net=0, net2=0, avg_win=None, avg_loss=None, worst_month=None, max_dd=0)
    x = np.array([t[key] for t in tr])
    x2 = np.array([t.get("net2", np.nan) for t in tr])
    mon = defaultdict(float)
    for t in tr:
        mon[t["day"].strftime("%Y-%m")] += t[key]
    w = x[x > 0]
    lo = x[x <= 0]
    return dict(trades=n, win=round(100 * len(w) / n, 1), net=round(float(x.sum())), net2=round(float(np.nansum(x2))),
                avg_win=round(float(w.mean())) if len(w) else None, avg_loss=round(float(lo.mean())) if len(lo) else None,
                worst_month=round(min(mon.values())), worst_month_at=min(mon, key=mon.get), max_dd=round(max_dd(x)),
                avg=round(float(x.mean()), 1))


def split(tr: list) -> dict:
    out = dict(all=summary(tr))
    for p in ("design", "hold"):
        out[p] = summary([t for t in tr if K.period_of(t["day"]) == p])
    out["per_index"] = {u: dict(all=summary([t for t in tr if t["und"] == u]),
                                design=summary([t for t in tr if t["und"] == u and K.period_of(t["day"]) == "design"]),
                                hold=summary([t for t in tr if t["und"] == u and K.period_of(t["day"]) == "hold"]))
                        for u in K.UNDS if any(t["und"] == u for t in tr)}
    mon = defaultdict(list)
    for t in tr:
        mon[t["day"].strftime("%Y-%m")].append(t)
    out["monthly"] = {m: dict(n=len(v), win=round(100 * sum(t["net"] > 0 for t in v) / len(v), 1),
                              net=round(sum(t["net"] for t in v)), net2=round(sum(t["net2"] for t in v)))
                      for m, v in sorted(mon.items())}
    out["reasons"] = dict(Counter(t["why"] for t in tr))
    return out


def bh_q(ps: list, m_total: int = K.M_TOTAL) -> list:
    """Benjamini-Hochberg q with the earlier tests taken as p = 1 (R106 / R176's bh)."""
    p = np.asarray(ps, float)
    n = len(p)
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return [float(x) for x in np.minimum(q, 1.0)]


def reproduces(ours: dict, claim: tuple) -> dict:
    n, w, net = claim
    ok_n = abs(ours["trades"] - n) <= 0.25 * n
    ok_w = ours["win"] is not None and abs(ours["win"] - w) <= 5.0
    ok_net = np.sign(ours["net"]) == np.sign(net) and abs(ours["net"] - net) <= 0.5 * abs(net)
    return dict(trades=bool(ok_n), win=bool(ok_w), net=bool(ok_net), all=bool(ok_n and ok_w and ok_net))


def gates(sp: dict, p: float, q: float) -> dict:
    a = sp["all"]
    worst = min((m["net"] for m in sp["monthly"].values()), default=0)
    g = dict(design_pos=sp["design"]["net"] > 0, hold_pos=sp["hold"]["net"] > 0, slip2_pos=a["net2"] > 0,
             acct_dd=a["max_dd"] > K.ACCT_DD, acct_month=worst > K.ACCT_MONTH, twin_p=p < K.GATE_P,
             bh_q=q <= K.GATE_Q, hold_n=sp["hold"]["trades"] >= K.MIN_HOLD)
    g = {k: bool(v) for k, v in g.items()}
    g["passed"] = all(g.values())
    g["fails"] = [k for k, v in g.items() if k != "passed" and not v]
    return g


# ================================================================================================ run
def _flag():
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    now = datetime.now().isoformat(timespec="seconds")
    if K.FINAL_FLAG.exists():
        with open(K.FINAL_FLAG, "a") as f:
            f.write(f"re-run {now} (see report: Runs)\n")
    else:
        K.FINAL_FLAG.write_text(f"R180 first run on data {now}\n")


def run():
    _flag()
    stores, bars, mins, strad = load_all()
    c1, n1 = RU.book1_candidates(bars, mins, strad)
    c2, n2 = RU.book2_candidates(bars, mins, strad)
    c1 = [c for c in c1 if in_window(c["day"])]
    c2 = [c for c in c2 if in_window(c["day"])]
    print("candidates", len(c1), len(c2), n1, n2, flush=True)
    priced = {}
    for mode in MODES:
        for cs in (c1, c2):
            for ci, c in enumerate(cs):
                D = stores[c["und"]][c["day"]]
                t = G.price(c, D, mode, G.SLIP1)
                t2 = G.price(c, D, mode, G.SLIP2)
                t["net2"] = t2["net"] if t2["ok"] else t["net"]
                # tie order: Book 1 before Book 2 at one minute; Book 1 highest straddle first, Book 2 lowest first
                t["order"] = (0 if c["book"] == "B1" else 1, -c["strad"] if c["book"] == "B1" else c["strad"])
                t["strad"] = c["strad"]
                if c["book"] == "B2":
                    t["ref"], t["stop_pts"] = c["ref"], c["stop_pts"]
                priced[(mode, c["book"], ci)] = t
    res = dict(counts=dict(B1=n1, B2=n2), runs={}, cands={})
    accounts = {}
    for mode in MODES:
        P1 = [priced[(mode, "B1", i)] for i in range(len(c1))]
        P2 = [priced[(mode, "B2", i)] for i in range(len(c2))]
        res["cands"][mode] = dict(B1=dict(n=len(P1), filled=sum(t["why"] not in ("nofill", "cap") for t in P1),
                                          cap=sum(t["why"] == "cap" for t in P1)),
                                  B2=dict(n=len(P2), filled=sum(t["why"] not in ("nofill", "cap") for t in P2),
                                          cap=sum(t["why"] == "cap" for t in P2)))
        sfx = "" if mode == "close" else "_open"
        accounts["B1" + sfx] = RU.one_position(P1)
        accounts["B2" + sfx] = RU.one_position(P2)
        accounts["COMB" + sfx] = RU.one_position(P1 + P2, pause=K.MONTH_PAUSE)
        if mode == "close":
            accounts["COMB_NOSTOP"] = RU.one_position(P1 + P2)
            # every candidate, no one-position rule (context: the books' raw signal quality)
            accounts["B1_all"] = [t for t in P1 if t["ok"]]
            accounts["B2_all"] = [t for t in P2 if t["ok"]]
    # random-entry twins, per taken trade (the account's twin = the sum over the trades it took)
    rng = np.random.default_rng(K.SEED)
    twin = {}
    for name in ("B1", "B2", "COMB", "COMB_NOSTOP", "B1_open", "B2_open", "COMB_open"):
        mode = "open" if name.endswith("_open") else "close"
        tot = np.zeros(K.TWINS)
        for t in accounts[name]:
            key = (mode, t["book"], t["und"], t["day"], t["sig"], t["side"], t.get("ref"))
            if key not in twin:
                twin[key] = twin_nets(t, stores[t["und"]][t["day"]], mode, rng)
            tot += twin[key]
        real = sum(t["net"] for t in accounts[name])
        res["runs"][name] = dict(split=split(accounts[name]), twin_mean=round(float(tot.mean())),
                                 twin_p=float((np.sum(tot >= real) + 1) / (K.TWINS + 1)))
    for name in ("B1_all", "B2_all"):
        res["runs"][name] = dict(split=split(accounts[name]))
    names = list(K.TESTS) + list(K.SENS)
    qs = bh_q([res["runs"][n]["twin_p"] for n in names])
    for n, q in zip(names, qs):
        r = res["runs"][n]
        r["bh_q"] = q
        r["gates"] = gates(r["split"], r["twin_p"], q)
        base = n.replace("_open", "")
        if base in K.CLAIMS:
            r["reproduces"] = reproduces(r["split"]["all"], K.CLAIMS[base]["all"])
    res["m_total"] = K.M_TOTAL
    res["overlap"] = overlap(accounts)
    K.OUT.mkdir(parents=True, exist_ok=True)
    rows = []
    for name, tr in accounts.items():
        for t in tr:
            rows.append({**{k: v for k, v in t.items() if k not in ("order",)}, "account": name})
    pd.DataFrame(rows).astype({"day": "datetime64[ns]"}).to_parquet(K.OUT / "trades.parquet", index=False)
    (K.RESULTS / "results.json").write_text(json.dumps(res, indent=1, default=str))
    print(json.dumps({n: (res["runs"][n]["split"]["all"], res["runs"][n].get("gates", {}).get("fails")) for n in res["runs"]},
                     indent=1, default=str))


def diag():
    """Post-hoc diagnostic (written after the run, NOT a test, no p-value, not gated): Book 1 without the per-index
    "initial breakout" state machine (the manual's undefined idx_stop_ext / free_idx_m), manual close fills, one
    position. Shows how much of the trade-count gap that one reading explains. Its random-entry twin p is reported and
    counted in BH (m = 4,052 for it), but it is not gated. -> results/diag.json"""
    stores, bars, mins, strad = load_all()
    c1, n1 = RU.book1_candidates(bars, mins, strad, initial_lock=False)
    c1 = [c for c in c1 if in_window(c["day"])]
    P1 = []
    for c in c1:
        D = stores[c["und"]][c["day"]]
        t = G.price(c, D, "close", G.SLIP1)
        t2 = G.price(c, D, "close", G.SLIP2)
        t["net2"] = t2["net"] if t2["ok"] else t["net"]
        t["order"] = (0, -c["strad"])
        P1.append(t)
    taken = RU.one_position(P1)
    rng = np.random.default_rng(K.SEED + 1)
    tot = np.zeros(K.TWINS)
    for t in taken:
        tot += twin_nets(t, stores[t["und"]][t["day"]], "close", rng)
    real = sum(t["net"] for t in taken)
    p = float((np.sum(tot >= real) + 1) / (K.TWINS + 1))
    out = dict(counts=n1, B1_nolock=split(taken), B1_nolock_all=split([t for t in P1 if t["ok"]]),
               twin_p=p, twin_mean=round(float(tot.mean())), m_total_with_diag=K.M_TOTAL + 1,
               bh_q=bh_q([p], K.M_TOTAL + 1)[0])
    (K.RESULTS / "diag.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps(dict(counts=n1, one=out["B1_nolock"]["all"], every=out["B1_nolock_all"]["all"],
                          d=out["B1_nolock"]["design"], h=out["B1_nolock"]["hold"], p=p, tm=out["twin_mean"]),
                     indent=1, default=str))


def twin_nets(t: dict, D, mode: str, rng) -> np.ndarray:
    """500 random-entry twins of one trade: same day, index, side; entry minute uniform in the book's window; same
    strike rule and exits; Book 2's stop the same points from the twin's own entry close (PREREG section 4)."""
    lo, hi = (K.B1_FIRST, K.B1_LAST) if t["book"] == "B1" else (K.B2_FIRST, K.B2_LAST)
    ems = rng.integers(lo, hi + 1, size=K.TWINS)
    cnd = dict(book=t["book"], und=t["und"], side=t["side"], sig=t["sig"], ref=t.get("ref"), stop_pts=t.get("stop_pts"))
    out = np.zeros(K.TWINS)
    for i, em in enumerate(ems):
        ref = None
        if t["book"] == "B2":
            ref = float(D.idx[3, em])
            if not np.isfinite(ref):
                continue
        r = G.price(cnd, D, mode, G.SLIP1, em=int(em), ref=ref)
        out[i] = r["net"] if r["ok"] else 0.0
    return out


def overlap(acc: dict) -> dict:
    """How the combined account's trades split between the books, and Book 2 trades lost to Book 1's open positions."""
    c = acc["COMB"]
    return dict(B1=sum(t["book"] == "B1" for t in c), B2=sum(t["book"] == "B2" for t in c))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "run":
        run()
    elif cmd == "diag":
        diag()
    elif cmd == "report":
        import r180report
        r180report.write()
    else:
        raise SystemExit(__doc__)
