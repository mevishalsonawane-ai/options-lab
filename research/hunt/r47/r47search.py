"""R47 exploration on DESIGN data only: every family x params x instrument x exit x filter (r47cfg), priced trades
summarised per walk-forward block. No BH here (relaxed item 5); the selection rule and the holdout come later.

    python3 research/hunt/r47/r47search.py [UND ...]      # -> <R47_OUT>/search_<UND>.parquet (never in git)
"""
from __future__ import annotations

import json
import sys
import time
from concurrent.futures import ProcessPoolExecutor

import numpy as np
import pandas as pd

import r47cfg as K
import r47data as D
import r47ml as ML
import r47run as R
import r47sig as S

NB = len(K.BLOCKS)


def summarise(tr: pd.DataFrame, f: dict, base: dict) -> list[dict]:
    """One row per filter: per block trades, net sum, sum of squares; top-5 net; fallback count."""
    out = []
    if len(tr) == 0:
        return out
    blk = R.block_of(tr.day.to_numpy())
    net = tr.net.to_numpy()
    di = tr.di.to_numpy()
    fb = tr.fb.to_numpy()
    for name in K.FILTERS:
        msk = S.filter_mask(name, f, di) & (blk >= 0)
        if msk.sum() == 0:
            continue
        b, x = blk[msk], net[msk]
        row = dict(base, filt=name, n=int(msk.sum()), net=float(x.sum()), top5=float(np.sort(x)[-5:].sum()),
                   fb=int(fb[msk].sum()), outlay=float(tr.outlay.to_numpy()[msk].mean()))
        for k in range(NB):
            xb = x[b == k]
            row[f"n{k}"], row[f"s{k}"], row[f"q{k}"] = int(len(xb)), float(xb.sum()), float((xb ** 2).sum())
        out.append(row)
    return out


def family_events(u, fam, p, cache):
    if fam == "M1_gbm":
        if "m1" not in cache:
            cache["m1"] = ML.wf_probs(u)
        di, m, pr = cache["m1"]
        return ML.events_from_probs(di, m, pr, p["th"])
    return S.SIGNALS[fam](u, **p)


def run_und(und: str) -> str:
    t0 = time.time()
    u = D.load(und, "design")
    D.assert_design_days([D.date.fromordinal(int(u.days[-1]))])
    f = S.day_features(u)
    lots = R.lots_of(u)
    rows, cache = [], {}
    for fam, spec in K.FAMILIES.items():
        for p in spec["params"]:
            evs = {}
            for instr in R.instruments_for(fam, und):
                ser = R.series_of(instr)
                if ser not in evs:
                    evs[ser] = (S.e2_expiry_vol(u, p["at"], ser) if fam == "E2_expiry_vol" else family_events(u, fam, p, cache))
                ev = evs[ser]
                if len(ev) == 0:
                    continue
                legs = R.legs_for(u, ev, instr)
                for xi, ex in enumerate(R.exits_for(fam, p)):
                    tr = R.price(u, ev, instr, ex, legs=legs, lots=lots)
                    base = dict(fam=fam, label=spec["label"], und=und, kind=R.kind_of(instr), params=json.dumps(p),
                                instr=instr, exit=xi, exit_spec=json.dumps(ex), events=int(len(ev)))
                    rows += summarise(tr, f, base)
        print(f"{und} {fam} done, {len(rows)} rows, {time.time() - t0:.0f}s", flush=True)
    df = pd.DataFrame(rows)
    K.OUT.mkdir(parents=True, exist_ok=True)
    path = K.OUT / f"search_{und}.parquet"
    df.to_parquet(path)
    return f"{und}: {len(df)} rows -> {path} ({time.time() - t0:.0f}s)"


def main(argv):
    unds = argv or list(K.UNDS)
    with ProcessPoolExecutor(max_workers=min(4, len(unds))) as ex:
        for msg in ex.map(run_und, unds):
            print(msg, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
