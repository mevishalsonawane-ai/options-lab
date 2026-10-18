"""Stale repeated prints: the studies' headline numbers, committed vs re-run on the Amendment A stores (clean, must
reproduce) vs re-run with lakeclean step 3 (stale), plus a trade-by-trade diff of clean vs stale. 18 Oct 2026.

    python research/hunt/lakestale/compare.py      # reads $LAKESTALE_OUT/{clean,stale}; writes results/compare.json

Pure reading of JSON and the trade caches the re-runs left in $LAKESTALE_OUT; nothing is re-priced here.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
ROOT = Path(os.environ.get("LAKESTALE_OUT", "/dev/shm/out/lakestale"))
R51_OUT = Path(os.environ.get("R51_OUT", "/dev/shm/out/r51"))
R77_RES = Path(os.environ.get("R77_DIR", "/home/user/r77wt/research/hunt/r77")) / "results"
OUT_JSON = HERE / "results" / "compare.json"
FIELDS = ("d_trades", "d_net", "wf_net", "f_trades", "f_net", "p", "q", "verdict")


def _j(p: Path):
    return json.loads(p.read_text()) if p.exists() else None


def _row(d_trades, d_net, wf_net, f_trades, f_net, p, q, verdict, **extra) -> dict:
    return dict(d_trades=d_trades, d_net=d_net, wf_net=wf_net, f_trades=f_trades, f_net=f_net, p=p, q=q,
                verdict=verdict, **extra)


# ------------------------------------------------------------------------------------------- per-study headline rows
def rows_r51(res: dict | None) -> dict:
    if not res:
        return {}
    return {n: _row(r["design"]["trades"], r["design"]["net"], r["wf_net"], r["final"]["trades"], r["final"]["net"],
                    r["final"]["p"], r["final"]["q"], r["verdict"], config=r["config"])
            for n, r in res["variants"].items()}


def rows_r71(des: dict | None, fin: dict | None) -> dict:
    """R72 (R71's study code): 12 tests, plus Donchian's own final as a reference row."""
    if not fin:
        return {}
    out = {}
    for n, t in fin["tests"].items():
        r = (des or {}).get("rules", {}).get(t["rule"], {})
        g = r.get("grid", {}).get(t["setting"], {}).get("design", {})
        out[n] = _row(g.get("trades"), t["design_net"], t["wf_net"], t["final"]["trades"], t["final"]["net"], t["p"],
                      t["q"], t["verdict"], setting=t["setting"])
    for x, s in fin.get("donchian", {}).items():
        out[f"Donchian-{x} (reference)"] = _row(None, None, None, s["trades"], s["net"], None, None, None)
    return out


def rows_r73(fin: dict | None) -> dict:
    if not fin:
        return {}
    out = {n: _row(t["design"]["trades"], t["design_net"], t["wf_net"], t["final"]["trades"], t["final"]["net"], t["p"],
                   t["q"], t["verdict"], skip_q=t.get("skip_q")) for n, t in fin["tests"].items()}
    for x, s in fin.get("base", {}).items():
        out[f"base-{x} (reference)"] = _row(None, None, None, s["trades"], s["net"], None, None, None)
    out["_decision"] = fin.get("decision")
    e = fin.get("extra_c1")
    if e:
        out["C1 extra (reference)"] = _row(None, None, None, e["c1"]["trades"], e["c1"]["net"], None, None, None)
    return out


def rows_r76(s: dict | None) -> dict:
    if not s:
        return {}
    out = {}
    for v, r in s["res"].items():
        dec = r.get("decision") or {}
        verdict = None if v == "B0" else ("recommended" if dec.get("recommended") else
                                          "harmless" if dec.get("harmless") else "not recommended")
        out[v] = _row(r["design"]["trades"], r["design"]["net"], r["wf"]["net"], r["final"]["trades"], r["final"]["net"],
                      r.get("exit_p"), r.get("exit_q"), verdict)
    return out


def rows_r77(des: dict | None, fin: dict | None) -> dict:
    if not fin:
        return {}
    return {n: _row(des["tests"][n]["design"]["trades"], des["tests"][n]["design"]["net"], des["tests"][n]["wf"]["net"],
                    t["final"]["trades"], t["final"]["net"], t["p"], t["q"], t["verdict"])
            for n, t in fin["tests"].items()}


def headline(study: str, where: str) -> dict:
    """where: 'committed' or a mode ('clean' / 'stale')."""
    if where == "committed":
        res = {"r51": HUNT / "r51" / "results", "r72": HUNT / "r72" / "results", "r73": HUNT / "r73" / "results",
               "r76": HUNT / "r76" / "results", "r77": R77_RES}[study]
        r51 = res / "amend_clean.json"
    else:
        res = ROOT / where / "results" / study
        r51 = res / "amend.json"
    if study == "r51":
        return rows_r51(_j(r51))
    if study == "r72":
        return rows_r71(_j(res / "design.json"), _j(res / "final.json"))
    if study == "r73":
        return rows_r73(_j(res / "final.json"))
    if study == "r76":
        return rows_r76(_j(res / "summary.json"))
    return rows_r77(_j(res / "design.json"), _j(res / "final.json"))


def same(a: dict | None, b: dict | None) -> bool:
    if a is None or b is None:
        return a is b
    for f in FIELDS:
        x, y = a.get(f), b.get(f)
        if isinstance(x, (int, float)) and isinstance(y, (int, float)):
            tol = 1e-6 if f in ("p", "q") else 0.5          # rupees are rounded; p and q are not
            if abs(x - y) > tol:
                return False
        elif x != y:
            return False
    return True


def delta(a: dict, b: dict) -> dict:
    out = {}
    for f in FIELDS:
        x, y = a.get(f), b.get(f)
        if isinstance(x, (int, float)) and isinstance(y, (int, float)) and abs(x - y) > 1e-9:
            out[f] = round(y - x, 4)
        elif x != y:
            out[f] = [x, y]
    return out


# ------------------------------------------------------------------------------------------- trade-by-trade diff
def _trades(p: Path):
    if not p.exists():
        return None
    obj = pickle.loads(p.read_bytes())
    if hasattr(obj, "to_dict"):
        obj = obj.to_dict("records")
    return obj


def _key(t) -> tuple:
    d = t["day"]
    d = d.date() if hasattr(d, "date") and callable(d.date) else d
    return (t["und"], str(d), t["side"], float(t["strike"]), int(t["er"]))


def trade_diff(a: list | None, b: list | None) -> dict | None:
    """clean trades a vs stale trades b, matched on (index, day, side, strike, entry minute)."""
    if a is None or b is None:
        return None
    ka, kb = {}, {}
    for t in a:
        ka.setdefault(_key(t), []).append(t)
    for t in b:
        kb.setdefault(_key(t), []).append(t)
    changed, d_net, ex = 0, 0.0, []
    for k in set(ka) & set(kb):
        for x, y in zip(ka[k], kb[k]):
            dn = float(y["net"]) - float(x["net"])
            if abs(dn) > 0.5:
                changed += 1
                d_net += dn
                ex.append(dict(und=k[0], day=k[1], side=k[2], strike=k[3], er=k[4], clean_net=round(float(x["net"])),
                               stale_net=round(float(y["net"])), clean_E=round(float(x.get("E", np.nan)), 2),
                               stale_E=round(float(y.get("E", np.nan)), 2)))
    only_a = [k for k in ka if k not in kb]
    only_b = [k for k in kb if k not in ka]
    ex.sort(key=lambda z: -abs(z["stale_net"] - z["clean_net"]))
    return dict(clean_trades=len(a), stale_trades=len(b), changed=changed, net_change=round(d_net),
                only_clean=len(only_a), only_clean_net=round(sum(float(t["net"]) for k in only_a for t in ka[k])),
                only_stale=len(only_b), only_stale_net=round(sum(float(t["net"]) for k in only_b for t in kb[k])),
                largest=ex[:5])


def trade_files(study: str, mode: str) -> dict:
    """{label: trade cache path} for the main tests of a study in one mode."""
    t = ROOT / mode / "trades" / study
    out = {}
    if study == "r51":
        base = R51_OUT / ("clean_stale" if mode == "stale" else "clean") / "trades"
        res = _j(ROOT / mode / "results" / "r51" / "amend.json") or {}
        for n, r in res.get("variants", {}).items():
            for tag in ("design", "final"):
                out[f"{n}|{tag}"] = base / f"{tag}_{r['config'].replace('|', '_').replace('/', '_')}.pkl"
    elif study == "r72":
        fin = _j(ROOT / mode / "results" / "r72" / "final.json") or {}
        for n, x in fin.get("tests", {}).items():
            out[f"{n}|final"] = t / f"{x['rule']}_{x['kind']}_final.pkl"
            out[f"{n}|design"] = t / f"{x['rule']}_{x['setting'].replace('|', '_')}_design.pkl"
    elif study == "r73":
        fin = _j(ROOT / mode / "results" / "r73" / "final.json") or {}
        for n in fin.get("tests", {}):
            v, x = n.split("-")
            for ph in ("design", "final"):
                out[f"{n}|{ph}"] = t / f"{v}_{x}_{ph}.pkl"
    elif study == "r76":
        for v in ("B0", "T25", "T30", "T35", "T50", "L35"):
            for ph in ("design", "final"):
                out[f"{v}|{ph}"] = t / f"{v}_{ph}.pkl"
    else:
        fin = _j(ROOT / mode / "results" / "r77" / "final.json") or {}
        for n in fin.get("tests", {}):
            for ph in ("design", "final"):
                out[f"{n}|{ph}"] = t / f"{n}_{ph}.pkl"
    return out


def main(studies):
    out = {}
    for s in studies:
        com, cln, stl = headline(s, "committed"), headline(s, "clean"), headline(s, "stale")
        names = [n for n in com if not n.startswith("_")]
        rows = {n: dict(committed=com.get(n), clean=cln.get(n), stale=stl.get(n)) for n in names}
        repro = {n: same(r["committed"], r["clean"]) for n, r in rows.items()}
        changes = {n: delta(r["clean"], r["stale"]) for n, r in rows.items()
                   if r["clean"] and r["stale"] and not same(r["clean"], r["stale"])}
        flips = {n: [rows[n]["clean"]["verdict"], rows[n]["stale"]["verdict"]] for n in rows
                 if rows[n]["clean"] and rows[n]["stale"] and rows[n]["clean"]["verdict"] != rows[n]["stale"]["verdict"]}
        fa, fb = trade_files(s, "clean"), trade_files(s, "stale")
        diffs = {k: trade_diff(_trades(fa[k]), _trades(fb[k])) for k in fa if k in fb}
        out[s] = dict(rows=rows, reproduces=all(repro.values()) if repro else None,
                      not_reproduced=[n for n, ok in repro.items() if not ok], changed=changes, verdict_flips=flips,
                      decision={w: h.get("_decision") for w, h in (("committed", com), ("clean", cln), ("stale", stl))}
                      if s == "r73" else None,
                      trades=dict(sorted(diffs.items())))
        print(s, "reproduces" if out[s]["reproduces"] else f"NOT reproduced: {out[s]['not_reproduced']}",
              "| changed:", list(changes), "| flips:", flips, flush=True)
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    old = _j(OUT_JSON) or {}
    old.update(out)
    OUT_JSON.write_text(json.dumps(old, indent=1, default=str) + "\n")
    return out


if __name__ == "__main__":
    main(sys.argv[1:] or ["r51", "r72", "r73", "r76", "r77"])
