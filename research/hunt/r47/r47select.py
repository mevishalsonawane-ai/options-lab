"""R47 selection (PREREG "Selection rule"): anchored walk-forward per family on design summaries, the family's
candidate, and the at-most-10 candidates frozen for the holdout.

    python3 research/hunt/r47/r47select.py               # -> results/design.json, results/candidates.json
    python3 research/hunt/r47/r47select.py --tag v2      # amendment 2 rerun -> results/design_v2.json, candidates_v2.json

Family = (signal family, underlying, instrument kind). Variant = (params, instrument, exit, filter).
"""
from __future__ import annotations

import json
import sys

import numpy as np
import pandas as pd

import r47cfg as K

NB = len(K.BLOCKS)
VKEY = ["params", "instr", "exit", "filt"]
FKEY = ["fam", "und", "kind"]


def tstat(n, s, q):
    """t of the mean from count, sum, sum of squares (0 if n < 2 or no spread)."""
    n, s, q = (np.asarray(x, dtype=np.float64) for x in (n, s, q))
    with np.errstate(all="ignore"):
        var = (q - s * s / np.maximum(n, 1)) / np.maximum(n - 1, 1)
        t = (s / np.maximum(n, 1)) / np.sqrt(var / np.maximum(n, 1))
    return np.where((n >= 2) & (var > 0), t, 0.0)


def load_all() -> pd.DataFrame:
    parts = [pd.read_parquet(K.OUT / f"search_{u}.parquet") for u in K.UNDS if (K.OUT / f"search_{u}.parquet").exists()]
    return pd.concat(parts, ignore_index=True)


def walk_forward(g: pd.DataFrame) -> dict:
    """Anchored walk-forward of one family's variants: test blocks 1..NB-1 (B2..B4)."""
    oos_n = oos_s = oos_q = 0.0
    green, picks = 0, []
    for b in range(1, NB):
        n = sum(g[f"n{k}"] for k in range(b))
        s = sum(g[f"s{k}"] for k in range(b))
        q = sum(g[f"q{k}"] for k in range(b))
        t = tstat(n, s, q)
        ok = (n >= K.WF_MIN_TRAIN) & (s > 0)
        if not ok.any():
            picks.append(None)
            continue
        i = int(np.argmax(np.where(ok, t, -np.inf)))
        r = g.iloc[i]
        oos_n += r[f"n{b}"]
        oos_s += r[f"s{b}"]
        oos_q += r[f"q{b}"]
        green += int(r[f"s{b}"] > 0)
        picks.append(dict(block=K.BLOCKS[b][0], params=r.params, instr=r.instr, exit=int(r.exit), filt=r.filt,
                          train_n=int(n.iloc[i]), train_npt=round(float(s.iloc[i] / n.iloc[i]), 1),
                          test_n=int(r[f"n{b}"]), test_net=round(float(r[f"s{b}"]), 0)))
    return dict(oos_n=int(oos_n), oos_net=float(oos_s), oos_npt=float(oos_s / oos_n) if oos_n else 0.0,
                oos_t=float(tstat(oos_n, oos_s, oos_q)), green=green, picks=picks)


def candidate(g: pd.DataFrame):
    nb_tr = sum((g[f"n{k}"] > 0).astype(int) for k in range(NB))
    nb_pos = sum((g[f"s{k}"] > 0).astype(int) for k in range(NB))
    ok = (g.n >= K.CAND_MIN_TRADES) & (g.net > 0) & (g.net - g.top5 > 0) & (nb_pos >= nb_tr - 1)
    if not ok.any():
        return None
    q = sum(g[f"q{k}"] for k in range(NB))
    t = tstat(g.n, g.net, q)
    i = int(np.argmax(np.where(ok, t, -np.inf)))
    r = g.iloc[i]
    return dict(params=json.loads(r.params), instr=r.instr, exit=int(r.exit), exit_spec=json.loads(r.exit_spec),
                filt=r.filt, n=int(r.n), net=round(float(r.net), 0), npt=round(float(r.net / r.n), 1),
                t=round(float(t[i]), 2), top5=round(float(r.top5), 0), fb=int(r.fb), outlay=round(float(r.outlay), 0),
                blocks={K.BLOCKS[k][0]: dict(n=int(r[f"n{k}"]), net=round(float(r[f"s{k}"]), 0)) for k in range(NB)})


def summary_stats(df: pd.DataFrame) -> dict:
    """What the exploration looked like: per family label and signal family, how many variants, how many positive."""
    a = df[df.filt == "all"]
    out = {}
    for fam, g in a.groupby("fam"):
        pos = g[(g.n >= 100)]
        best = pos.loc[(pos.net / pos.n).idxmax()] if len(pos) else None
        out[fam] = dict(label=K.FAMILIES[fam]["label"], variants=int(len(g)), with_100=int(len(pos)),
                        positive_100=int((pos.net > 0).sum()),
                        median_npt_100=round(float((pos.net / pos.n).median()), 1) if len(pos) else None,
                        pooled_npt=round(float(g.net.sum() / max(g.n.sum(), 1)), 1),
                        best_100=None if best is None else dict(und=best.und, params=best.params, instr=best.instr,
                                                                 exit=json.loads(best.exit_spec), n=int(best.n),
                                                                 npt=round(float(best.net / best.n), 1),
                                                                 green=int(sum(best[f"s{k}"] > 0 for k in range(NB))),
                                                                 blocks_with_trades=int(sum(best[f"n{k}"] > 0 for k in range(NB)))))
    return out


def main(argv):
    tag = argv[argv.index("--tag") + 1] if "--tag" in argv else ""
    df = load_all()
    fams = []
    for key, g in df.groupby(FKEY, sort=True):
        g = g.reset_index(drop=True)
        wf = walk_forward(g)
        cand = candidate(g)
        qual = wf["oos_n"] >= K.WF_MIN_OOS and wf["oos_net"] > 0 and wf["green"] >= K.WF_GREEN_BLOCKS
        fams.append(dict(fam=key[0], und=key[1], kind=key[2], label=K.FAMILIES[key[0]]["label"],
                         variants=int(len(g)), qualifies=bool(qual), wf=wf, candidate=cand))
    ranked = sorted([f for f in fams if f["qualifies"] and f["candidate"] and f["fam"] != "E1_event_vol"],
                    key=lambda f: -f["wf"]["oos_t"])
    sent, seen = [], set()
    for f in ranked:
        if (f["fam"], f["und"]) in seen:
            continue
        seen.add((f["fam"], f["und"]))
        sent.append(f)
        if len(sent) == K.MAX_CANDIDATES:
            break
    cands = [dict(id=f"C{i + 1}", fam=f["fam"], label=f["label"], signal_source=K.SIGNAL_SOURCE[f["label"]],
                  und=f["und"], kind=f["kind"], wf=dict((k, v) for k, v in f["wf"].items() if k != "picks"),
                  **f["candidate"]) for i, f in enumerate(sent)]
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    design = dict(variants_tried=int(len(df)), rule_combinations=int(df[df.filt == "all"].shape[0]),
                  families=len(fams), qualifying=int(sum(f["qualifies"] for f in fams)),
                  qualifying_with_candidate=len(ranked), by_signal_family=summary_stats(df),
                  families_detail=sorted(fams, key=lambda f: -f["wf"]["oos_t"]))
    (K.RESULTS / f"design{'_' + tag if tag else ''}.json").write_text(json.dumps(design, indent=1, default=str))
    out = K.RESULTS / f"candidates_{tag}.json" if tag else K.CANDIDATES
    out.write_text(json.dumps(dict(prereg="research/hunt/r47/PREREG.md", design_end=str(K.DESIGN_END),
                                            holdout_start=str(K.HOLDOUT_START), candidates=cands), indent=1))
    print(f"{len(df)} variant rows, {len(fams)} families, {design['qualifying']} qualify on walk-forward, "
          f"{len(ranked)} with a candidate; {len(cands)} sent")
    for c in cands:
        print(c["id"], c["fam"], c["und"], c["kind"], c["instr"], c["exit_spec"], c["filt"], c["params"],
              "design", c["n"], c["npt"], "WF", c["wf"]["oos_n"], round(c["wf"]["oos_npt"], 1), round(c["wf"]["oos_t"], 2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
