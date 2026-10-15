#!/usr/bin/env python3
"""R52 Amendment 2 (PREREG.md, 15 Oct 2026): re-price the passing setting H37-10:g17 (and H37-10:orig, information) on
lake data with foreign rows and stray prints removed (research/hunt/lakeclean.py). Same rule, spans and gates; the
final test is re-priced, not re-selected.

    python3 research/hunt/r52/lake2obuy.py <raw dir>                   # original layout (if not already made)
    OBUY_DATA=<raw dir> OBUY_CACHE=<c1> python3 research/hunt/r52/r52amend.py raw     # ORIGINAL trades (reproduction)
    python3 research/hunt/r52/lake2obuy.py <fo dir> --foreign-only     # foreign rows only: the expiry-day labels
    OBUY_DATA=<fo dir> OBUY_CACHE=<c3> python3 research/hunt/r52/r52amend.py labels <clean dir>/expiry_labels.json
    python3 research/hunt/r52/lake2obuy.py <clean dir> --clean          # cleaned layout + what was dropped
    OBUY_DATA=<clean dir> OBUY_CACHE=<c2> python3 research/hunt/r52/r52amend.py run   # the ONE re-priced read
    python3 research/hunt/r52/r52amend.py report                        # results/amend_clean.json -> amend_clean.md
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass

import numpy as np
import pandas as pd

import r52cfg as K
import r52lab as L
import r52rules as RR
from obuy import config as OC
from obuy.costs import Fills

sys.path.insert(0, str(K.HERE.parent))
import lakeclean  # noqa: E402

KEY = "H37-10"
PASSING = "g17"
VIDS = (PASSING, "orig")
OUT_JSON = K.RESULTS / "amend_clean.json"
OUT_MD = K.RESULTS / "amend_clean.md"
PREREG = K.HERE / "PREREG.md"
MARK = "## Amendment 2 (15 Oct 2026, after the one final read)"
RAW = K.OUT / "amend"


@dataclass(frozen=True)
class Fills2x(Fills):
    """Twice R17's 'liq' fills: twice the bps and twice the half-spread ticks (limit targets still at their price)."""
    mode: str = "liq"
    bps_mkt: int = 10
    bps_stop: int = 20

    def _ticks(self, vol5_lots, p):
        return 2.0 * super()._ticks(vol5_lots, p)


def cleaned() -> bool:
    return os.path.exists(os.path.join(OC.DATA, "CLEANED"))


LABELS = "expiry_labels.json"


def labels(path: str):
    """Write {und|day: expiry flag} from the foreign-only layout (stray prints kept): the labels the cleaned run uses."""
    if not os.path.exists(os.path.join(OC.DATA, "FOREIGN_ONLY")):
        raise SystemExit("run `labels` on the foreign-only layout (lake2obuy.py <dir> --foreign-only)")
    from obuy.data import market
    out = {f"{u}|{d}": bool(market().index(u).d[d]["exp"]) for u in K.UNDS for d in market().index(u).days}
    with open(path, "w") as f:
        json.dump(out, f)
    print(len(out), "labels,", sum(out.values()), "expiry days")


def pin_labels(lab: dict, mk=None) -> int:
    """Set obuy's expiry flags from `lab` before any signal or trade is computed. Returns how many flags changed."""
    from obuy.data import market
    mk = mk or market()
    n = 0
    for u in K.UNDS:
        ix = mk.index(u)
        for d in ix.days:
            v = lab.get(f"{u}|{d}")
            if v is not None and bool(ix.d[d]["exp"]) != v:
                ix.d[d]["exp"] = v
                n += 1
        ix._daily = None
    return n


def check_key(check_git: bool = True):
    if not K.FINAL_FLAG.exists():
        raise RuntimeError("refused: Amendment 2 re-prices a final test that was read; R52's was not")
    if OUT_JSON.exists():
        raise RuntimeError(f"refused: the Amendment 2 read was already made ({OUT_JSON.name} exists)")
    if MARK not in PREREG.read_text():
        raise RuntimeError("refused: Amendment 2 is not in PREREG.md")
    if check_git:
        rel = PREREG.relative_to(K.ROOT)
        st = subprocess.run(["git", "status", "--porcelain", str(rel)], cwd=K.ROOT, capture_output=True, text=True)
        if st.stdout.strip():
            raise RuntimeError("refused: PREREG.md must be committed unchanged before the Amendment 2 read")


def requalify(final: dict, new_p: dict) -> dict:
    """BH over the original final reads (results/final.json order) with re-priced p where given. {id: q}."""
    ids = list(final)
    q = L.bh([new_p.get(i, final[i]["p"]) for i in ids])
    return {i: float(x) for i, x in zip(ids, q)}


def variants():
    return [r for r in RR.variants(KEY) if r.vid in VIDS]


def raw():
    """The original-data trades of g17 and orig (design and final); must reproduce results/final.json."""
    if cleaned():
        raise SystemExit("run `raw` on the ORIGINAL layout (OBUY_DATA without the CLEANED marker)")
    if not K.FINAL_FLAG.exists():
        raise SystemExit("refused: the final test has not been read")
    L.env_check()
    fin = json.loads((K.RESULTS / "final.json").read_text())
    RAW.mkdir(parents=True, exist_ok=True)
    for part in ("design", "final"):
        res = L.run(variants(), part, twins=False)
        for r in variants():
            T = res[r.id]["trades"]
            T.to_pickle(RAW / f"raw_{part}_{r.vid}.pkl")
            print(part, r.id, len(T), round(float(T.net.sum()) if len(T) else 0.0), flush=True)
            if part == "final":
                f = fin[r.id]
                ok = len(T) == f["trades"] and abs(float(T.net.sum()) - f["net"]) < 1
                print("  reproduces final.json" if ok else f"  DOES NOT reproduce final.json ({f['trades']}, "
                      f"{f['net']:.0f})", flush=True)


def to_rows(T: pd.DataFrame) -> pd.DataFrame:
    """obuy trades -> the columns lakeclean.touched reads (bought right, minute rows)."""
    x = pd.DataFrame(dict(day=pd.to_datetime(pd.Series(T.day.values)).dt.date.values,
                          side=np.where(T.side.values > 0, "C", "P"), strike=T.strike.astype(float).values,
                          er=T.entry_min.values.astype(int) - (9 * 60 + 15),
                          xr=T.exit_min.values.astype(int) - (9 * 60 + 15),
                          und=T.und.values, net=T.net.values.astype(float)))
    return x


def touched(T: pd.DataFrame) -> dict:
    """Original trades whose contract had a removed or capped row between entry and exit (WEEK = the 'near' series)."""
    if not len(T):
        return dict(trades=0)
    parts = []
    for u, g in to_rows(T).groupby("und"):
        p = os.path.join(OC.DATA, f"clean_log_{u}_WEEK.parquet")
        log = pd.read_parquet(p) if os.path.exists(p) else pd.DataFrame(columns=["day", "r", "side", "strike", "why"])
        parts.append(lakeclean.touched(g, log))
    Tt = pd.concat(parts, ignore_index=True)
    out = dict(trades=int(len(Tt)), net=round(float(Tt.net.sum()), 0))
    for w in ("foreign", "stray_open", "high_capped", "any"):
        k = Tt[w].to_numpy(bool)
        out[w] = dict(trades=int(k.sum()), net=round(float(Tt.net[k].sum()), 0),
                      by_und={u: int(n) for u, n in Tt[k].und.value_counts().items()})
    return out


def run():
    if not cleaned():
        raise SystemExit("run `run` on the CLEANED layout (lake2obuy.py <dir> --clean)")
    check_key()
    L.env_check()
    lp = os.path.join(OC.DATA, LABELS)
    if not os.path.exists(lp):
        raise SystemExit(f"missing {LABELS} in the cleaned layout (r52amend.py labels)")
    pinned = pin_labels(json.loads(open(lp).read()))
    fin0 = json.loads((K.RESULTS / "final.json").read_text())
    des0 = json.loads((K.RESULTS / "design.json").read_text())
    cnt = os.path.join(OC.DATA, "clean_counts.json")
    out = dict(read=time.strftime("%Y-%m-%d %H:%M:%S"), drops=json.loads(open(cnt).read()) if os.path.exists(cnt) else {},
               settings={}, expiry_flags_pinned=pinned)
    vs = variants()
    dres = L.run(vs, "design")
    fres = L.run(vs, "final")
    for r in vs:
        ds = L.score(dres[r.id])
        wf = L.walk_forward({r.vid: dres[r.id]["trades"]})["net"]
        fs = L.score(fres[r.id])
        o = fin0[f"{KEY}:{r.vid}"]
        out["settings"][r.vid] = dict(design=ds, wf_design=wf, final=fs,
                                      orig=dict(trades=o["trades"], net=o["net"], p=o["p"], q=o["q"],
                                                by_und=o["by_und"], wf_design=o["wf_design"],
                                                verdict=o["gates"]["verdict"],
                                                design_net=des0[KEY]["scores"][r.vid]["net"]))
    q = requalify(fin0, {f"{KEY}:{v}": s["final"]["p"] for v, s in out["settings"].items()})
    for v, s in out["settings"].items():
        s["final"]["q"] = q[f"{KEY}:{v}"]
        s["gates"] = L.gates(s["final"], s["wf_design"], s["final"]["q"])
    # the passing setting: what touched its original trades, and twice the spread / slippage (information only)
    info = {}
    for part in ("design", "final"):
        p = RAW / f"raw_{part}_{PASSING}.pkl"
        if p.exists():
            info[f"touched_{part}"] = touched(pd.read_pickle(p))
    g17 = [r for r in vs if r.vid == PASSING]
    keep = L.FILLS
    L.FILLS = Fills2x()
    try:
        d2 = L.run(g17, "design")[g17[0].id]
        f2 = L.run(g17, "final")[g17[0].id]
        info["slip2"] = dict(design=L.score(d2), final=L.score(f2))
    finally:
        L.FILLS = keep
    out["settings"][PASSING]["passing_info"] = info
    OUT_JSON.write_text(json.dumps(out, indent=1, default=str))
    return out


def _rs(x):
    return "-" if x is None else f"{x:+,.0f}"


def report(res: dict | None = None) -> str:
    res = res or json.loads(OUT_JSON.read_text())
    L_ = ["| Index / series | option rows (2024 - 13 Oct 2026) | foreign dropped | stray opens dropped | highs capped |",
          "|---|---:|---:|---:|---:|"]
    for k, c in res["drops"].items():
        L_.append(f"| {k} | {c['rows']:,} | {c['foreign']:,} ({c['foreign'] / max(c['rows'], 1):.2%}) | "
                  f"{c['stray_open']:,} | {c['high_capped']:,} |")
    L_ += ["", "| Setting | ORIGINAL final: trades, net, p, q | CLEANED final: trades, net, p, q, p_flip | cleaned "
           "design walk-forward | verdict now |", "|---|---|---|---:|---|"]
    for v, s in res["settings"].items():
        o, f = s["orig"], s["final"]
        L_.append(f"| {KEY}:{v} | {o['trades']}, {_rs(o['net'])}, {o['p']:.4f}, {o['q']:.4f} | {f['trades']}, "
                  f"{_rs(f['net'])}, {f['p']:.4f}, {f['q']:.4f}, {f['p_flip']:.4f} | {_rs(s['wf_design'])} | "
                  f"{s['gates']['verdict']} |")
    for v, s in res["settings"].items():
        L_.append(f"\n**{KEY}:{v}** final by index, original -> cleaned: " + ", ".join(
            f"{u} {_rs(s['orig']['by_und'].get(u))} -> {_rs(s['final']['by_und'].get(u))}"
            for u in sorted(set(s["orig"]["by_und"]) | set(s["final"]["by_und"]))))
        info = s.get("passing_info")
        if not info:
            continue
        for part in ("design", "final"):
            t = info.get(f"touched_{part}")
            if t and t.get("trades"):
                L_.append(f"- Original {part} trades that touched a removed or capped row: {t['any']['trades']} of "
                          f"{t['trades']} (their net {_rs(t['any']['net'])} of {_rs(t['net'])}); foreign rows "
                          f"{t['foreign']['trades']}, stray opens {t['stray_open']['trades']}, capped highs "
                          f"{t['high_capped']['trades']}; by index {t['any']['by_und']}.")
        s2 = info.get("slip2")
        if s2:
            L_.append(f"- Twice the spread and slippage (cleaned data): final {s2['final']['trades']} trades "
                      f"{_rs(s2['final']['net'])} (p {s2['final']['p']:.4f}), design {s2['design']['trades']} trades "
                      f"{_rs(s2['design']['net'])}.")
    md = "\n".join(L_) + "\n"
    OUT_MD.write_text(md)
    return md


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "raw":
        raw()
    elif cmd == "labels":
        labels(sys.argv[2])
    elif cmd == "run":
        run()
        print(report())
    elif cmd == "report":
        print(report())
    else:
        print(__doc__)
