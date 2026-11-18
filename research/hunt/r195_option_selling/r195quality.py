"""R195 Amendment 1 (written AFTER the holdout read; descriptive only, it changes no verdict): how much of each test's
net comes from entries sold at a stale print.

A trade is flagged when (a) any SOLD leg traded 0 contracts in the 5 minutes up to its entry fill minute (the fill price
is then a repeated old print, not a market), or (b) put-call parity fails at the decision minute at the ATM strike K0:
|(C - P) - (index - K0 x exp(-rT))| > 25% of (C + P), with C, P the decision-minute closes (an off-market print). Found on S1-FINNIFTY's best holdout days (27 Mar 2026: the ATM call printed
635.95 with no volume while the ATM put was 296.6, one session before expiry).

    python3 -I research/hunt/r195_option_selling/r195quality.py      # -> results/quality.json
"""
from __future__ import annotations

import json
import pickle
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r195cfg as K  # noqa: E402
import r195rules as RU  # noqa: E402
import r195study as S  # noqa: E402


def parity_bad(t: dict, store: dict, exp: dict) -> bool:
    import math
    import r195engine as E
    D, und, r = store[t["day"]], RU.und_of(t["tag"]), t["decide"]
    k0 = RU.atm(D, und, r)
    if k0 is None:
        return False
    c, p, s = RU.close_at(D, "C", k0, r), RU.close_at(D, "P", k0, r), D.idx[3, r]
    if not (np.isfinite(c) and np.isfinite(p) and np.isfinite(s)):
        return False
    tt = float(E.years_left(t["day"], exp[t["day"]], r))
    return abs((c - p) - (s - k0 * math.exp(-E.RATE * tt))) > 0.25 * (c + p)


def flagged(t: dict, store: dict, exp: dict | None = None) -> bool:
    if exp is not None and parity_bad(t, store, exp):
        return True
    D = store[t["day"]]
    er = t["decide"] + 1
    for side, k, sign, _ in t["legs"]:
        if sign > 0:
            continue
        con = D.con.get((side, float(k)))
        v = con[4, max(0, er - 5): er + 1] if con is not None else np.array([])
        if not np.nansum(v) > 0:
            return True
    return False


def main():
    out = {}
    for cid in RU.TESTS:
        store, _, exp = S.setup(RU.und_of(cid))
        row = {}
        for part in ("wf", "ho"):
            with open(S.CACHE / f"{part}_{cid}.pkl", "rb") as f:
                tr = pickle.load(f)
            fl = [t for t in tr if flagged(t, store, exp)]
            ok = [t for t in tr if not flagged(t, store, exp)]
            row[part] = dict(n=len(tr), flagged=len(fl), net_flagged=round(sum(t["net"] for t in fl)),
                             n_clean=len(ok), net_clean=round(sum(t["net"] for t in ok)),
                             win_clean=round(float(np.mean([t["net"] > 0 for t in ok])), 3) if ok else None)
        out[cid] = row
        print(cid, row, flush=True)
    (K.RESULTS / "quality.json").write_text(json.dumps(out, indent=1) + "\n")


if __name__ == "__main__":
    main()
