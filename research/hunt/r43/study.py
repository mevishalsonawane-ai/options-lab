"""R43 study (PREREG.md).

    python3 research/hunt/r43/study.py design     # design days only -> results/design.json
    python3 research/hunt/r43/study.py holdout    # the locked holdout, ONCE -> results/holdout.json, results/final.json

The outcome table (every side x strike rule x bar x exit of every expiry day) is cached in <OUT>/table_<part>.pkl.
"""
from __future__ import annotations

import json
import multiprocessing as mp
import pickle
import sys
from datetime import datetime, timezone

import numpy as np

import r43cfg as K
import r43data as D
import r43lib as L
import r43sig as S

_DAYS: list = []


def _work(i: int) -> list[dict]:
    return L.day_table(_DAYS[i])


def build(part: str):
    """(table, sigs, days by und, n index-days)."""
    global _DAYS
    table, sigs, alldays = {}, {}, {}
    for u in K.UNDS:
        days = D.load_days(u, part)
        alldays[u] = {d.day: d for d in days}
        bars = D.load_bars(u)
        sg = S.signals(bars)
        want = {d.day for d in days}
        for d, idx in bars.groupby("d").groups.items():
            if d not in want:
                continue
            js = bars.j.values[bars.index.get_indexer(idx)]
            fs = {}
            for f in sg.columns:
                v = sg[f].values[bars.index.get_indexer(idx)]
                fs[f] = {int(j): int(s) for j, s in zip(js, v) if s != 0}
            sigs[(u, d)] = fs
        for d in want - {k[1] for k in sigs if k[0] == u}:
            sigs[(u, d)] = {}
        _DAYS = days
        with mp.get_context("fork").Pool(4) as pool:
            for rows in pool.imap_unordered(_work, range(len(days)), chunksize=4):
                for r in rows:
                    table[(r["und"], r["d"], r["side"], r["strike_rule"], r["j"], r["exit"])] = r
        print(u, part, len(days), "days, table", len(table), flush=True)
    n = sum(len(v) for v in alldays.values())
    return table, sigs, alldays, n


def stress(trades: list[dict], alldays: dict, rule) -> dict:
    """The same trades at 2x the half-spread, and with out-of-range prices at 0."""
    out = {"spread2x": 0.0, "oor0": 0.0}
    for t in trades:
        day = alldays[t["und"]][t["d"]]
        a = L.simulate(day, t["strike"], t["side"], L.sig_row(t["j"]), t["ref"], rule[3], mult=2.0)
        b = L.simulate(day, t["strike"], t["side"], L.sig_row(t["j"]), t["ref"], rule[3], oor_zero=True)
        out["spread2x"] += a.net if a else 0.0
        out["oor0"] += b.net if b else 0.0
    return {k: round(v, 0) for k, v in out.items()}


def evaluate(part: str) -> dict:
    table, sigs, alldays, n_days = build(part)
    pickle.dump(table, open(K.OUT / f"table_{part}.pkl", "wb"))
    pools = {}
    res = {}
    for rule in K.rules():
        rid = K.rule_id(rule)
        tr = L.rule_trades(table, sigs, rule)
        key = (rule[1], rule[3], rule[2])
        if key not in pools:
            pools[key] = L.twin_pool(table, *key)
        tw = L.twins(tr, pools[key])
        m = L.metrics(tr, n_days)
        m.update(stress(tr, alldays, rule))
        res[rid] = dict(rule=list(rule), m=m, by_year=L.by_year(tr), by_index=L.by_index(tr),
                        twin_mean=round(float(tw.mean()), 0), twin_sd=round(float(tw.std()), 0),
                        p=round(L.p_value(m["net"], tw), 4),
                        top=[dict(d=str(t["d"]), und=t["und"], side="CE" if t["side"] > 0 else "PE", strike=t["strike"],
                                  j=t["j"], entry=t["entry"], peak=round(t["peak"], 1), xmult=round(t["xmult"], 2),
                                  net=t["net"]) for t in sorted(tr, key=lambda x: -x["net"])[:5]])
        print(rid, m["trades"], m["net"], res[rid]["p"], flush=True)
    qs = L.bh([v["p"] for v in res.values()])
    for v, q in zip(res.values(), qs):
        v["q"] = round(q, 4)
    days = {u: len(v) for u, v in alldays.items()}
    return dict(part=part, index_days=days, n_index_days=n_days, bh_size=len(res), rules=res,
                when=datetime.now(timezone.utc).isoformat(timespec="seconds"))


def gates(dv: dict, hv: dict) -> dict:
    dm, hm = dv["m"], hv["m"]
    g = {"design_net>0": dm["net"] > 0, "holdout_net>0": hm["net"] > 0, "p<0.05": hv["p"] < K.GATE_P,
         "q<=0.10": hv["q"] <= K.GATE_Q, "trades>=40": hm["trades"] >= K.GATE_TRADES,
         "top5<100%": hm["net"] > 0 and hm["net_wo_top5"] > 0}
    return dict(g, passes=all(g.values()))


def main():
    K.OUT.mkdir(parents=True, exist_ok=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        r = evaluate("design")
        (K.RESULTS / "design.json").write_text(json.dumps(r, indent=1, default=str))
    elif cmd == "holdout":
        flag = K.OUT / "HOLDOUT_READ"
        if flag.exists() or (K.RESULTS / "holdout.json").exists():
            sys.exit("the R43 holdout was already read once (" + (flag.read_text() if flag.exists() else "holdout.json") + ")")
        if not (K.RESULTS / "design.json").exists():
            sys.exit("run design first")
        flag.write_text(datetime.now(timezone.utc).isoformat())
        h = evaluate("holdout")
        (K.RESULTS / "holdout.json").write_text(json.dumps(h, indent=1, default=str))
        d = json.loads((K.RESULTS / "design.json").read_text())
        fin = {rid: dict(rule=h["rules"][rid]["rule"], design=d["rules"][rid], holdout=h["rules"][rid],
                         gates=gates(d["rules"][rid], h["rules"][rid])) for rid in h["rules"]}
        out = dict(n_rules=len(fin), passed=[k for k, v in fin.items() if v["gates"]["passes"]],
                   index_days={"design": d["index_days"], "holdout": h["index_days"]}, rules=fin)
        (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
        print("passed:", out["passed"])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
