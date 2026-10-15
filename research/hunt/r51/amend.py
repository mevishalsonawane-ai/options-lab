"""R51 Amendment A (PREREG.md, 15 Oct 2026): re-price the passing rule and the near-misses on lake data with foreign rows
and stray prints removed (research/hunt/lakeclean.py). Same rules, same spans, same gates; the final test is re-priced,
not re-selected.

    python research/hunt/r51/amend.py targets                     # the re-priced variants (from results/final.json)
    R51_CLEAN=0 python research/hunt/r51/r51data.py build         # original stores (if not already built)
    R51_CLEAN=0 python research/hunt/r51/amend.py raw             # the passing rule's ORIGINAL trades (reproduction)
    R51_CLEAN=1 python research/hunt/r51/r51data.py build         # cleaned stores + what was dropped, per index
    R51_CLEAN=1 python research/hunt/r51/amend.py run             # the ONE re-priced read -> results/amend_clean.json
    python research/hunt/r51/amend.py report                      # results/amend_clean.json -> results/amend_clean.md
"""
from __future__ import annotations

import json
import subprocess
import sys
from datetime import datetime, timezone

import numpy as np
import pandas as pd

import common as C
import engine as E
import r51cfg as K
import r51data
import study

lakeclean = r51data.lakeclean
PASSING = "R44-donchian20-RT"
NEAR_Q = 0.20
NON_INDEX = {"m4"}                      # MCX: no index to check a row's spot against (keeps its original numbers)
OUT_JSON = K.RESULTS / "amend_clean.json"
OUT_MD = K.RESULTS / "amend_clean.md"
PREREG = K.HERE / "PREREG.md"
MARK = "## Amendment A (15 Oct 2026, after the one final read)"


def raw_dir():
    return (K.OUT.parent if K.CLEAN else K.OUT) / "amend"


def targets(final: dict, design: dict) -> list[str]:
    """The passing variant(s) plus every gated, failed index-option variant with final q <= NEAR_Q."""
    out = []
    for n, f in final.items():
        if design[n]["kind"] == "ref" or f.get("q") is None or design[n].get("module") in NON_INDEX:
            continue
        if f.get("pass") or f["q"] <= NEAR_Q:
            out.append(n)
    return sorted(out, key=lambda n: (not final[n].get("pass"), final[n]["q"], n))


def gated(final: dict, design: dict) -> list[str]:
    """The variants the original BH ran over (study.final: not 'ref', p present), in final.json order."""
    return [n for n in final if design[n]["kind"] != "ref" and final[n].get("p") is not None]


def requalify(final: dict, design: dict, new_p: dict) -> dict:
    """BH over the original gated set with re-priced p where given, original p elsewhere. {name: q}."""
    names = gated(final, design)
    p = [new_p.get(n, final[n]["p"]) for n in names]
    return {n: round(float(q), 4) for n, q in zip(names, C.bh(p))}


def check_key(check_git: bool = True):
    """The re-priced read happens once, only after the original final read, with the amendment committed unchanged."""
    if not K.FINAL_FLAG.exists():
        raise RuntimeError("refused: Amendment A re-prices a final test that was read; R51's was not")
    if OUT_JSON.exists():
        raise RuntimeError(f"refused: the Amendment A read was already made ({OUT_JSON.name} exists)")
    if MARK not in PREREG.read_text():
        raise RuntimeError("refused: Amendment A is not in PREREG.md")
    if check_git:
        rel = PREREG.relative_to(K.ROOT)
        st = subprocess.run(["git", "status", "--porcelain", str(rel)], cwd=K.ROOT, capture_output=True, text=True)
        if st.stdout.strip():
            raise RuntimeError("refused: PREREG.md must be committed unchanged before the Amendment A read")


def _variants(names):
    by = {v.name: v for v in study.family_variants(study.FAMILIES)}
    return [by[n] for n in names]


def _cfg(v, name):
    for c in v.configs:
        if c.name == name:
            return c
    raise KeyError(f"{v.name}: config {name} not in the grid")


def by_und(T: pd.DataFrame) -> dict:
    return {u: round(float(x), 0) for u, x in T.groupby("und").net.sum().items()} if len(T) else {}


def raw():
    """The passing rule's original trades (design and final) on the original stores. Must reproduce final.json."""
    if K.CLEAN:
        raise SystemExit("run `raw` with R51_CLEAN=0 (the original stores)")
    if not K.FINAL_FLAG.exists():
        raise SystemExit("refused: the final test has not been read")
    fin = json.loads((K.RESULTS / "final.json").read_text())
    C.open_final()
    v = _variants([PASSING])[0]
    cfg = _cfg(v, fin[PASSING]["config"])
    raw_dir().mkdir(parents=True, exist_ok=True)
    lo, hi = study.window(v)
    for tag, a, b in (("design", lo, hi), ("final", K.FINAL_START, v.extra.get("final_end", K.END))):
        T = cfg.run(a, b)
        T.to_pickle(raw_dir() / f"raw_{tag}_{PASSING}.pkl")
        print(tag, len(T), round(float(T.net.sum()), 0), flush=True)
    f = C.stats(pd.read_pickle(raw_dir() / f"raw_final_{PASSING}.pkl"), study.sessions(v, K.FINAL_START, K.END))
    ok = f["trades"] == fin[PASSING]["trades"] and abs(f["net"] - fin[PASSING]["net"]) < 1
    print("reproduces final.json" if ok else f"DOES NOT reproduce final.json: {f['trades']} {f['net']}", flush=True)


def drop_counts() -> dict:
    out = {}
    for u in K.UNDS:
        p = K.OUT / f"clean_counts_{u}.json"
        if p.exists():
            out[u] = json.loads(p.read_text())
    return out


def touched_trades(T: pd.DataFrame) -> pd.DataFrame:
    """Original trades flagged by what the cleaning removed on their contract between entry and exit."""
    parts = []
    for u, g in T.groupby("und"):
        log = pd.read_parquet(K.OUT / f"clean_log_{u}.parquet")
        keys = {(d, s, float(k)) for d, D in r51data.load(u).items() for (s, k) in D.con}
        parts.append(lakeclean.touched(g, log, keys))
    return pd.concat(parts, ignore_index=True) if parts else T


def touch_summary(Tt: pd.DataFrame) -> dict:
    if not len(Tt):
        return dict(trades=0)
    out = dict(trades=int(len(Tt)), net=round(float(Tt.net.sum()), 0))
    for w in ("foreign", "foreign_contract", "stray_open", "high_capped", "any"):
        k = Tt[w].to_numpy(bool)
        out[w] = dict(trades=int(k.sum()), net=round(float(Tt.net[k].sum()), 0))
    return out


def run():
    if not K.CLEAN:
        raise SystemExit("run `run` with R51_CLEAN=1 (the cleaned stores)")
    check_key()
    fin0 = json.loads((K.RESULTS / "final.json").read_text())
    des0 = json.loads((K.RESULTS / "design.json").read_text())
    names = targets(fin0, des0)
    C.open_final()
    out = dict(read=datetime.now(timezone.utc).isoformat(), targets=names, drops=drop_counts(), variants={})
    for v in _variants(names):
        d0, f0 = des0[v.name], fin0[v.name]
        lo, hi = study.window(v)
        print(f"design {v.name} ...", flush=True)
        Ts = [study.trades_of(c, lo, hi, "design") for c in v.configs]
        ses = study.sessions(v, lo, hi)
        r = dict(kind=v.kind, config=f0["config"])
        cfg = _cfg(v, f0["config"])
        if v.kind == "retune":
            wf = study.walk_forward(v, Ts)
            r["wf_net"], r["wf_picks"], r["clean_pick"] = wf["wf_net"], wf["wf_picks"], wf["final_pick"]
            r["design"] = C.stats(Ts[v.configs.index(cfg)], ses)
        else:
            r["design"] = C.stats(Ts[0], ses)
            r["wf_net"] = r["design"]["net"]
        print(f"final {v.name} ({cfg.name}) ...", flush=True)
        T = study.trades_of(cfg, K.FINAL_START, v.extra.get("final_end", K.END), "final")
        f = C.stats(T, study.sessions(v, K.FINAL_START, K.END))
        f["p"], f["twin_mean"] = C.twin_p(T, cfg) if len(T) else (None, None)
        f["by_und"] = by_und(T)
        r["final"] = f
        r["orig"] = dict(final_net=f0["net"], final_trades=f0["trades"], p=f0["p"], q=f0["q"], verdict=f0["verdict"],
                         wf_net=d0.get("wf_net"))
        out["variants"][v.name] = r
    q = requalify(fin0, des0, {n: r["final"]["p"] for n, r in out["variants"].items() if r["final"]["p"] is not None})
    for n, r in out["variants"].items():
        r["final"]["q"] = q.get(n)
        ok, why = study.gates(r["final"], r["wf_net"])
        r["pass"], r["verdict"] = ok, why
    # the passing rule: what touched its original trades, and twice the spread / slippage (information only)
    for n in [x for x in names if fin0[x].get("pass")]:
        v = _variants([n])[0]
        cfg = _cfg(v, fin0[n]["config"])
        info = {}
        for tag in ("design", "final"):
            p = raw_dir() / f"raw_{tag}_{n}.pkl"
            if p.exists():
                info[f"touched_{tag}"] = touch_summary(touched_trades(pd.read_pickle(p)))
        E.SLIP = E.SLIP2
        try:
            lo, hi = study.window(v)
            Td = cfg.run(lo, hi)
            Tf = cfg.run(K.FINAL_START, v.extra.get("final_end", K.END))
            f2 = C.stats(Tf, study.sessions(v, K.FINAL_START, K.END))
            f2["p"], f2["twin_mean"] = C.twin_p(Tf, cfg)
            info["slip2"] = dict(design=C.stats(Td, study.sessions(v, lo, hi)), final=f2, by_und=by_und(Tf))
        finally:
            E.SLIP = E.SLIP1
        out["variants"][n]["passing_info"] = info
    OUT_JSON.write_text(json.dumps(out, indent=1, default=str))
    return out


def _rs(x):
    return "-" if x is None else f"{x:+,.0f}"


def report(res: dict | None = None) -> str:
    res = res or json.loads(OUT_JSON.read_text())
    L = ["| Index | option rows (20 Nov 2024 - 13 Oct 2026, WEEK) | foreign dropped | stray opens dropped | highs capped |",
         "|---|---:|---:|---:|---:|"]
    for u, c in res["drops"].items():
        L.append(f"| {u} | {c['rows']:,} | {c['foreign']:,} ({c['foreign'] / max(c['rows'], 1):.2%}) | "
                 f"{c['stray_open']:,} | {c['high_capped']:,} |")
    L += ["", "| Variant | config | ORIGINAL final: trades, net, p, q | CLEANED final: trades, net, p, q | cleaned design "
          "walk-forward | verdict now |", "|---|---|---|---|---:|---|"]
    for n, r in res["variants"].items():
        o, f = r["orig"], r["final"]
        L.append(f"| {n} | {r['config']} | {o['final_trades']}, {_rs(o['final_net'])}, {o['p']}, {o['q']} | "
                 f"{f['trades']}, {_rs(f['net'])}, {f['p']}, {f['q']} | {_rs(r['wf_net'])} | {r['verdict']} |")
    for n, r in res["variants"].items():
        info = r.get("passing_info")
        if not info:
            continue
        L += ["", f"**{n}** by index (cleaned final): " + ", ".join(f"{u} {_rs(x)}" for u, x in r["final"]["by_und"].items())]
        for tag in ("design", "final"):
            t = info.get(f"touched_{tag}")
            if t and t.get("trades"):
                L.append(f"- Original {tag} trades that touched a removed or capped row: {t['any']['trades']} of "
                         f"{t['trades']} (their net {_rs(t['any']['net'])} of {_rs(t['net'])}); foreign rows "
                         f"{t['foreign']['trades']}, contract only in foreign rows {t['foreign_contract']['trades']}, "
                         f"stray opens {t['stray_open']['trades']}, capped highs {t['high_capped']['trades']}.")
        s2 = info.get("slip2")
        if s2:
            L.append(f"- Twice the spread and slippage (cleaned data): final {s2['final']['trades']} trades "
                     f"{_rs(s2['final']['net'])} (p {s2['final']['p']}), design {s2['design']['trades']} trades "
                     f"{_rs(s2['design']['net'])}.")
    md = "\n".join(L) + "\n"
    OUT_MD.write_text(md)
    return md


def main(argv):
    cmd = argv[0] if argv else ""
    if cmd == "targets":
        fin = json.loads((K.RESULTS / "final.json").read_text())
        des = json.loads((K.RESULTS / "design.json").read_text())
        for n in targets(fin, des):
            print(n, fin[n]["config"], fin[n]["trades"], fin[n]["net"], fin[n]["p"], fin[n]["q"])
    elif cmd == "raw":
        raw()
    elif cmd == "run":
        run()
        print(report())
    elif cmd == "report":
        print(report())
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
