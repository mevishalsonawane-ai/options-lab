"""R190 study (PREREG.md section 7). From the repo root, after the builds (r190data lake, r190arms build/run,
r190night build, r190rules build):

    python3 -I research/hunt/r190_best_hunt/r190study.py bc       # eras B and C only -> results/eras_bc.json
    python3 -I research/hunt/r190_best_hunt/r190study.py a        # era A, ONCE, only after ERA_A_READ.flag is committed
    python3 -I research/hunt/r190_best_hunt/r190study.py report   # summary.md, report.md, decision.json

`select_era` is THE cut: `bc` never lets an era-A trade out of it.
"""
from __future__ import annotations

import json
import pickle
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r190cfg as K  # noqa: E402

import numpy as np  # noqa: E402

import r190lib as G  # noqa: E402

NIGHT, RULES = ("N1B", "N1N", "N2"), ("M1", "M2", "V1", "O1", "O2", "O3")
ARMS = ("L1", "D1", "D2", "D3")


# ================================================================================================ the cut
def select_era(trades: list, era: str, allowed: tuple) -> list:
    if era not in allowed:
        raise PermissionError(f"era {era} is not open in this phase (allowed {allowed})")
    return [t for t in trades if t["era"] == era]


def era_a_allowed(flag_tracked: bool, result_exists: bool) -> bool:
    """Era A opens once: only after the flag is committed, and never again once its result exists."""
    return flag_tracked and not result_exists


def flag_tracked() -> bool:
    r = subprocess.run(["git", "ls-files", "--error-unmatch", str(K.FLAG_A)], cwd=K.ROOT, capture_output=True)
    return r.returncode == 0


# ================================================================================================ trades
def night_sets() -> dict:
    import r190night as NT
    rows = {u: NT.night_rows(u) for u in K.NIGHT_UNDS}
    tr = {u: NT.trades_of(rows[u]) for u in K.NIGHT_UNDS}
    return dict(rows=rows, N1N=tr["NIFTY"], N1B=tr["NIFTY"] + tr["BANKNIFTY"], N2=NT.n2_trades(tr["NIFTY"], tr["BANKNIFTY"]))


def _t(rec: dict, side: int, p: dict) -> dict:
    return dict(und="NIFTY", day=rec["day"], era=rec["era"], side=side, net=p["net"], stress=p["stress"], fit=p["fit"],
                lots=p["lots"], prem=p["prem"], exit_how=p["exit_how"])


def rule_sets() -> dict:
    rows = pickle.loads((K.OUT / "rules_NIFTY.pkl").read_bytes())
    out = {k: [] for k in RULES}
    for r in rows:
        if r["era"] is None:
            continue
        for cid, col in (("M1", "m1"), ("M2", "m2")):
            s = r[col]
            p = r["mom"].get(s) if s else None
            if p is None:
                continue
            out[cid].append(_t(r, s, p))
            if G.overlay_ok(s, r.get("mom_px", np.nan), r["sma"], r["vix_high"]):
                out["O" + cid[1]].append(_t(r, s, p))
        for cid in ("V1", "O3"):
            for k, s, _ in r["walk"][cid]:
                p = r["noise"].get((k, s))
                if p is not None:
                    out[cid].append(dict(_t(r, s, p), mark=k))
    return dict(rows=rows, **out)


def arm_sets() -> dict:
    p = K.OUT / "arms_trades.pkl"
    if not p.exists():
        return {}
    d = pickle.loads(p.read_bytes())
    out = {"D2_note": d.get("D2_note")}
    for k in ARMS:
        if d.get(k) is None:
            out[k] = None
            continue
        out[k] = [dict(t, era=K.era_of(t["day"])) for t in d[k] if K.era_of(t["day"]) is not None]
    return out


# ================================================================================================ twins
def twins_for(cid: str, era: str, trades: list, ns: dict, rs: dict, rng) -> np.ndarray:
    if cid in NIGHT:
        import r190night as NT
        tot = np.zeros(K.TWINS)
        for u in K.NIGHT_UNDS:
            n = sum(1 for t in trades if t["und"] == u)
            if n:
                c, p = NT.twin_pool(ns["rows"][u], era)
                tot += G.twin_draw(c, p, n, rng)
        return tot
    rows = [r for r in rs["rows"] if r["era"] == era]
    if cid in ("M1", "M2", "O1", "O2"):
        ok = [r for r in rows if r["mom"].get(1) is not None and r["mom"].get(-1) is not None]
        c = np.array([r["mom"][1]["net"] for r in ok])
        p = np.array([r["mom"][-1]["net"] for r in ok])
    else:
        ok = [(r, k) for r in rows for (k, s) in r["noise"] if s == 1
              and r["noise"].get((k, 1)) is not None and r["noise"].get((k, -1)) is not None]
        c = np.array([r["noise"][(k, 1)]["net"] for r, k in ok])
        p = np.array([r["noise"][(k, -1)]["net"] for r, k in ok])
    return G.twin_draw(c, p, len(trades), rng)


# ================================================================================================ one era
def by_year(trades: list) -> dict:
    out = {}
    for t in trades:
        out[t["day"].year] = out.get(t["day"].year, 0.0) + t["net"]
    return {str(k): round(v) for k, v in sorted(out.items())}


def era_block(cid: str, era: str, trades: list, ns, rs, with_twins: bool) -> dict:
    s = G.stats(trades)
    blk = dict(one_lot=s, by_year=by_year(trades))
    if trades and "stress" in trades[0]:
        blk["stress_2x"] = G.stats(trades, "stress")
        blk["max_fit"] = G.stats(trades, "fit")
        blk["max_fit"]["avg_lots"] = float(np.mean([t["lots"] for t in trades if t.get("fit") is not None])) \
            if any(t.get("fit") is not None for t in trades) else 0.0
        blk["last_print_exits"] = sum(1 for t in trades if t.get("exit_how") == "last_print")
    if trades and "und" in trades[0]:
        blk["by_index"] = {u: G.stats([t for t in trades if t["und"] == u]) for u in sorted({t["und"] for t in trades})}
    if with_twins and trades:
        seed = K.SEED * 1000 + (K.CANDIDATES.index(cid) * 10) + "ABC".index(era)
        tw = twins_for(cid, era, trades, ns, rs, np.random.default_rng(seed))
        pe, pn = G.twin_p(s["net"], tw)
        blk["twin"] = dict(p=pe, p_norm=pn, mean=float(tw.mean()), sd=float(tw.std(ddof=1)), n=len(tw))
    return blk


def run_eras(eras: tuple) -> dict:
    ns, rs, ar = night_sets(), rule_sets(), arm_sets()
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), eras=list(eras))}
    for cid in K.CANDIDATES:
        src = ns.get(cid) if cid in NIGHT else (rs.get(cid) if cid in RULES else ar.get(cid))
        out[cid] = {}
        for e in eras:
            if cid in ARMS and e == "A":
                out[cid][e] = None          # untestable: no data
                continue
            if src is None:
                out[cid][e] = dict(not_run=ar.get("D2_note") if cid == "D2" else "no trades file")
                continue
            tr = select_era(src, e, eras)
            out[cid][e] = era_block(cid, e, tr, ns, rs, with_twins=cid not in ARMS)
            s = out[cid][e]["one_lot"]
            print(cid, e, s["trades"], round(s["win"], 1), round(s["net"]), round(s["max_dd"]),
                  out[cid][e].get("twin", {}).get("p"), flush=True)
    if "D2_note" in ar:
        out["_meta"]["D2_note"] = ar["D2_note"]
    return out


def phase_bc():
    out = run_eras(("B", "C"))
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "eras_bc.json").write_text(json.dumps(out, indent=1, default=str))


def phase_a():
    if not era_a_allowed(flag_tracked(), K.ERA_A_JSON.exists()):
        raise SystemExit("era A is locked: commit results/ERA_A_READ.flag first; it is read once (era_a.json exists?)")
    out = run_eras(("A",))
    K.ERA_A_JSON.write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    cmd = (sys.argv[1:] or [""])[0]
    if cmd == "report":
        import r190report
        r190report.main()
    else:
        {"bc": phase_bc, "a": phase_a}.get(cmd, lambda: sys.exit(__doc__))()
