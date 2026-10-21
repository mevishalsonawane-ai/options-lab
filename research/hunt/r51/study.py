"""R51 study driver (PREREG.md).

    python research/hunt/r51/study.py design [--family r44 ...]   # design + walk-forward; merges into results/design.json
    python research/hunt/r51/study.py final                        # the ONE final-test read (all variants), then BH + gates

`final` refuses to run twice (results/FINAL_READ) and refuses while any variant lacks its design result.
"""
from __future__ import annotations

import argparse
import importlib
import json
import pickle
import sys
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd

import common as C
import r51cfg as K

FAMILIES = ["liq", "night", "m4", "r44", "r45", "f1", "r29", "r41", "b2"]


def family_variants(fams):
    out = []
    for f in fams:
        mod = importlib.import_module(f"fam_{f}")
        for v in mod.variants():
            v.extra["module"] = f
            out.append(v)
    return out


def _cache(name):
    return K.OUT / "trades" / f"{name.replace('|', '_').replace('/', '_')}.pkl"


def trades_of(cfg: C.Config, lo: date, hi: date, tag: str) -> pd.DataFrame:
    p = _cache(f"{tag}_{cfg.name}")
    if p.exists():
        return pd.read_pickle(p)
    T = cfg.run(lo, hi)
    p.parent.mkdir(parents=True, exist_ok=True)
    T.to_pickle(p)
    return T


def unds_of(v: C.Variant):
    return v.extra.get("unds", K.UNDS)


def sessions(v, lo, hi):
    if "sessions" in v.extra:
        return v.extra["sessions"](lo, hi)
    return C.n_sessions(unds_of(v), lo, hi)


def window(v):
    lo = v.extra.get("design_start", K.START)
    return lo, K.DESIGN_END


def fold_nets(T: pd.DataFrame):
    out = []
    for a, b in K.WF_FOLDS:
        k = (T.day >= a) & (T.day <= b) if len(T) else []
        out.append(round(float(T.net[k].sum()), 0) if len(T) else 0.0)
    return out


def walk_forward(v: C.Variant, Ts: list):
    """Out-of-fold net of the re-tune: in each fold, the grid point with the best net before it (>= 20 trades)."""
    lo, _ = window(v)
    picks, oof, n_oof = [], 0.0, 0
    for a, b in K.WF_FOLDS:
        best, best_net = 0, None
        for i, T in enumerate(Ts):
            tr = T[(T.day >= lo) & (T.day < a)] if len(T) else T
            if len(tr) < 20:
                continue
            if best_net is None or tr.net.sum() > best_net:
                best, best_net = i, float(tr.net.sum())
        T = Ts[best]
        f = T[(T.day >= a) & (T.day <= b)] if len(T) else T
        picks.append(v.configs[best].name)
        oof += float(f.net.sum()) if len(f) else 0.0
        n_oof += len(f)
    full = [(float(T.net.sum()) if len(T) >= 30 else -np.inf) for T in Ts]
    final_i = int(np.argmax(full)) if np.isfinite(max(full)) else 0
    return dict(wf_picks=picks, wf_net=round(oof, 0), wf_trades=n_oof, final_pick=v.configs[final_i].name,
                final_pick_i=final_i, grid_design_net={c.name: round(float(T.net.sum()), 0) if len(T) else 0.0
                                                      for c, T in zip(v.configs, Ts)})


def design(fams):
    path = K.RESULTS / "design.json"
    res = json.loads(path.read_text()) if path.exists() else {}
    for v in family_variants(fams):
        lo, hi = window(v)
        print(f"design {v.name} ...", flush=True)
        Ts = [trades_of(c, lo, hi, "design") for c in v.configs]
        ses = sessions(v, lo, hi)
        r = dict(family=v.family, source=v.source, kind=v.kind, desc=v.desc, old=v.old, note=v.note,
                 module=v.extra["module"], design_start=str(lo), sessions=ses)
        if v.kind == "retune":
            wf = walk_forward(v, Ts)
            r.update(wf)
            r["design"] = C.stats(Ts[wf["final_pick_i"]], ses)
        else:
            T = Ts[0]
            r["design"] = C.stats(T, ses)
            r["folds"] = fold_nets(T)
            r["wf_net"] = r["design"]["net"]
            p, tw = C.twin_p(T, v.configs[0]) if len(T) else (None, None)
            r["design"]["p"], r["design"]["twin_mean"] = p, tw
        res[v.name] = r
        print(json.dumps({v.name: {k: r[k] for k in ("design", "wf_net") if k in r}}, default=str)[:600], flush=True)
        K.RESULTS.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(res, indent=1, default=str))
    return res


def gates(f: dict, wf_net) -> tuple[bool, str]:
    if f["trades"] < K.GATE_MIN_TRADES:
        return False, "too few trades to judge"
    fails = []
    if f["net"] <= 0:
        fails.append("final net <= 0")
    if f.get("p") is None or f["p"] >= K.GATE_P:
        fails.append("p >= 0.05")
    if f.get("q") is None or f["q"] > K.GATE_Q:
        fails.append("q > 0.10")
    if f["ex_top5"] <= 0:
        fails.append("top-5 trades >= net")
    if wf_net is None or wf_net <= 0:
        fails.append("design walk-forward <= 0")
    return (not fails), ("PASS" if not fails else "; ".join(fails))


def final():
    if K.FINAL_FLAG.exists():
        raise SystemExit(f"the final test was already read ({K.FINAL_FLAG.read_text().strip()}); R51 reads it once")
    design_res = json.loads((K.RESULTS / "design.json").read_text())
    vs = family_variants(FAMILIES)
    missing = [v.name for v in vs if v.name not in design_res]
    if missing:
        raise SystemExit(f"design results missing for {missing}")
    K.FINAL_FLAG.write_text(f"final test read {datetime.now(timezone.utc).isoformat()} by study.py final\n")
    C.open_final()
    out = {}
    for v in vs:
        d = design_res[v.name]
        cfg = v.configs[d.get("final_pick_i", 0)] if v.kind == "retune" else v.configs[0]
        lo, hi = K.FINAL_START, v.extra.get("final_end", K.END)
        print(f"final {v.name} ({cfg.name}) ...", flush=True)
        T = trades_of(cfg, lo, hi, "final")
        ses = sessions(v, lo, hi)
        f = C.stats(T, ses)
        f["p"], f["twin_mean"] = C.twin_p(T, cfg) if len(T) else (None, None)
        f["config"] = cfg.name
        out[v.name] = f
        (K.RESULTS / "final_partial.json").write_text(json.dumps(out, indent=1, default=str))
    names = [n for n in out if design_res[n]["kind"] != "ref" and out[n].get("p") is not None]
    q = C.bh([out[n]["p"] for n in names])
    for n, qq in zip(names, q):
        out[n]["q"] = round(float(qq), 4)
    for n, f in out.items():
        if design_res[n]["kind"] == "ref":
            f["verdict"] = "reference (not gated)"
            continue
        ok, why = gates(f, design_res[n].get("wf_net"))
        f["pass"], f["verdict"] = ok, why
    (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    (K.RESULTS / "final_partial.json").unlink(missing_ok=True)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["design", "final"])
    ap.add_argument("--family", nargs="*", default=None)
    a = ap.parse_args(argv)
    if a.cmd == "design":
        design(a.family or FAMILIES)
    else:
        final()


if __name__ == "__main__":
    main()
