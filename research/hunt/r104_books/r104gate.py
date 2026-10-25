"""R104 gates and Benjamini-Hochberg across EVERY R104 test (PREREG.md section 7).

    python research/hunt/r104_books/r104gate.py design      # results/gates_design.json + results/gates_design.md

Entry tests (IDX, IDXF, DLY, MCXI, MCXD, CRY, STK, VOL): the walk-forward series from results/design_*.json.
Comparison tests (the book's exit or filter against a host on the same entries: lswing-05, smith-03, smith-07,
musiela-02): the gates read the DAILY DIFFERENCE to the host (registered as '<rule>~host') as well as the book version's
own net. ARM overlays / filters: the difference to B0 (f11_arms). MM: the risk-lowering bar.
"""
from __future__ import annotations

import glob
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r104cfg as K  # noqa: E402
import r104rules as RR  # noqa: E402
import r104stats as ST  # noqa: E402

HOSTS = {"book-lswing-05": "book-lswing-05~host", "book-smith-03": "book-smith-03~host",
         "book-smith-07": "book-smith-07~host", "book-fmmusiela-02": "book-fmmusiela-02~host"}


def load_design():
    out = {}
    for f in sorted(glob.glob(str(K.RESULTS / "design_*.json"))):
        out.update(json.loads(Path(f).read_text()))
    return out


def trades(rid, market, variant, part="design"):
    p = K.OUT / "trades" / f"{rid}__{market}__{variant}__{part}.parquet"
    return pd.read_parquet(p) if p.exists() else None


def wf_trades(res, rid, market):
    """The walk-forward-chosen series (fold k >= first from the variant picked on earlier folds)."""
    picks, ff = res["wf"]["picks"], res["wf"]["first_fold"]
    parts = []
    for k in range(ff, len(picks)):
        v = picks[k] if picks[k] is not None else list(res["variants"])[0]
        t = trades(rid, market, v)
        if t is not None and len(t):
            parts.append(t[t.fold == k])
    return pd.concat(parts) if parts else pd.DataFrame(columns=["day", "net", "fold"])


def compare_stats(rid, market, res, host_res):
    """Book series vs host on the same days: daily delta, fold deltas, p."""
    nf = len(K.PERIODS[market]["folds"])
    book = wf_trades(res, rid, market)
    hv = list(host_res["variants"])[0]
    host = trades(HOSTS[rid], market, hv)
    if host is None:
        return None
    ff = res["wf"]["first_fold"]
    host = host[host.fold >= ff]
    sb = book.groupby("day").net.sum() if len(book) else pd.Series(dtype=float)
    sh = host.groupby("day").net.sum() if len(host) else pd.Series(dtype=float)
    days = sorted(set(sb.index) | set(sh.index))
    delta = np.array([sb.get(d, 0.0) - sh.get(d, 0.0) for d in days])
    fd = [float(book[book.fold == k].net.sum() - host[host.fold == k].net.sum()) for k in range(ff, nf)]
    return dict(delta=float(delta.sum()), fold_delta=fd, p=ST.boot_p(delta), host_net=float(sh.sum()),
                host_n=int(len(host)))


def gate_table():
    R = load_design()
    rows = []
    for rid, market in RR.tests():
        key = f"{rid}|{market}"
        r = R.get(key)
        if r is None:
            rows.append(dict(test=key, rule=rid, market=market, status="missing"))
            continue
        if market in ("ARM",):
            vs = r["variants"]
            best = max(vs, key=lambda v: vs[v]["delta"])
            v = vs[best]
            p = v["p"]
            g = dict(design_delta_pos=v["delta"] > 0, every_quarter_pos=all(x > 0 for x in v["q"]),
                     twin=(v.get("twin_p") is None or v["twin_p"] < 0.05))
            rows.append(dict(test=key, rule=rid, market=market, kind=r["kind"], variant=best, p=p, n=v["var_n"],
                             win=v["var_win"], net=v["var_net"], b0_net=v["b0_net"], b0_win=v["b0_win"],
                             delta=v["delta"], folds=v["q"], twin_p=v.get("twin_p"), gates=g))
            continue
        if market == "MM":
            vs = r["variants"]
            base = r["base"]
            best, bh = None, None
            for vn, v in vs.items():
                dd_cut = 1 - (v["maxdd"] / base["maxdd"]) if base["maxdd"] < 0 else 0.0
                net_cut = 1 - (v["net"] / base["net"]) if base["net"] > 0 else 0.0
                helps = (not v["adds_risk"]) and dd_cut >= 0.20 and net_cut <= 0.10 and v["worst_month"] >= base["worst_month"]
                score = (helps, dd_cut - max(net_cut, 0))
                if bh is None or score > bh:
                    best, bh = vn, score
            v = vs[best]
            dd_cut = 1 - (v["maxdd"] / base["maxdd"]) if base["maxdd"] < 0 else 0.0
            net_cut = 1 - (v["net"] / base["net"]) if base["net"] > 0 else 0.0
            g = dict(dd_cut_20=dd_cut >= 0.20, net_cut_10=net_cut <= 0.10, worst_month=v["worst_month"] >= base["worst_month"],
                     not_adds_risk=not v["adds_risk"])
            rows.append(dict(test=key, rule=rid, market=market, kind="mm", variant=best,
                             p=v["twin_p"] if v["twin_p"] is not None else 1.0, net=v["net"], b0_net=base["net"],
                             maxdd=v["maxdd"], b0_maxdd=base["maxdd"], dd_cut=dd_cut, net_cut=net_cut,
                             adds_risk=v["adds_risk"], gates=g))
            continue
        nf = len(K.PERIODS[market]["folds"])
        w = r["wf"]
        wf = wf_trades(r, rid, market)
        g = ST.entry_gates(market, wf, w["first_fold"], nf, r["p"], None)
        row = dict(test=key, rule=rid, market=market, kind="entry", variant=r["holdout_variant"], p=r["p"], n=w["n"],
                   win=w["win"], net=w["net"], folds=w["folds"][w["first_fold"]:], gates=g)
        if rid in HOSTS:
            hk = f"{HOSTS[rid]}|{market}"
            cs = compare_stats(rid, market, r, R[hk]) if hk in R else None
            if cs:
                row.update(cmp=cs)
                row["p"] = cs["p"]
                g["beats_host"] = cs["delta"] > 0 and all(x > 0 for x in cs["fold_delta"])
        rows.append(row)
    ps = np.array([x.get("p", 1.0) if x.get("p") is not None else 1.0 for x in rows])
    q = ST.bh(ps)
    for x, qq in zip(rows, q):
        x["q"] = float(qq)
        if "gates" in x:
            x["gates"]["bh_q"] = qq < K.GATE_Q
            x["design_pass"] = all(x["gates"].values())
    return rows


def md(rows):
    out = ["| test | variant | n | win | net (Rs) | folds | p | q | design pass |", "|---|---|---|---|---|---|---|---|---|"]
    for x in sorted(rows, key=lambda r: r.get("q", 1)):
        if x.get("status") == "missing":
            out.append(f"| {x['test']} | missing | | | | | | | |")
            continue
        win = "" if x.get("win") is None else f"{x['win']:.0%}"
        folds = ", ".join(f"{v:,.0f}" for v in x.get("folds", [])) if x.get("folds") is not None else ""
        net = x.get("net", 0.0)
        extra = ""
        if x["market"] == "ARM":
            extra = f" (B0 {x['b0_net']:,.0f}, delta {x['delta']:,.0f})"
        if x["market"] == "MM":
            extra = f" (B0 {x['b0_net']:,.0f}; DD {x['maxdd']:,.0f} vs {x['b0_maxdd']:,.0f})"
        out.append(f"| {x['test']} | {x.get('variant', '')} | {x.get('n', '')} | {win} | {net:,.0f}{extra} | {folds} | "
                   f"{x['p']:.3f} | {x['q']:.3f} | {'yes' if x.get('design_pass') else 'no'} |")
    return "\n".join(out)


if __name__ == "__main__":
    rows = gate_table()
    (K.RESULTS / "gates_design.json").write_text(json.dumps(rows, indent=1, default=str))
    (K.RESULTS / "gates_design.md").write_text(md(rows) + "\n")
    n_pass = sum(1 for x in rows if x.get("design_pass"))
    print(len(rows), "tests;", n_pass, "pass every design gate;",
          sum(1 for x in rows if x.get("status") == "missing"), "missing")
    for x in rows:
        if x.get("design_pass"):
            print("PASS", x["test"], x.get("variant"), x.get("net"), x.get("q"))
