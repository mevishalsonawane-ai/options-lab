"""Build R26's per-expiry-day pickles with R25's loader (r25data.build), FINNIFTY included.

    python3 research/hunt/r26/build.py [UND ...]     # -> <R26_OUT>/days_<UND>.pkl
"""
from __future__ import annotations

import pickle
import sys

import r26cfg as K26
import r25cfg as K
import r25data as D

if __name__ == "__main__":
    K26.OUT.mkdir(parents=True, exist_ok=True)
    for u in (sys.argv[1:] or K26.UNDS):
        ds = D.build(u)
        D.path(u).write_bytes(pickle.dumps(ds))
        print(u, len(ds), ds[0].day if ds else "-", ds[-1].day if ds else "-",
              "R25-holdout block", sum(K.is_holdout(d.day) for d in ds), flush=True)
