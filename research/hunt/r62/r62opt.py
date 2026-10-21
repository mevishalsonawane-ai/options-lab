"""R62 STK-opt (PREREG section 6, Amendment 0 item 5; context only, never gated): the STK candidates traded as the
stock's ATM nearest-monthly OPTION on R58's engine, unchanged (held contract, per-stock fitted spread, band exit, the
T-3 expiry cut-off, dated lots). Signal on the stock; the option is only what is bought.

    R58_OUT=<cache>/r58 python research/hunt/r58/r58data.py bhav ; ... build SYM [SYM ...]   # R58's books first
    python research/hunt/r62/r62opt.py design|final       # results/opt_<part>.json (final only after r62study final)
"""
from __future__ import annotations

import json
import sys
from dataclasses import replace

import numpy as np

import r62cfg as K
import r62study as T

if str(K.R58) not in sys.path:
    sys.path.insert(0, str(K.R58))
import r58cfg as K58  # noqa: E402
import r58data as D58  # noqa: E402
import r58engine as E58  # noqa: E402


def run(variant: str, part: str) -> list:
    tgt = K.parse_variant(variant)["target"]
    out = []
    for sym in K.STOCKS:
        bk = D58.load(sym)
        last = K.DESIGN_END if part == "design" else K58.LAST_DAY
        mode = E58.Mode(end=max(i for i, d in enumerate(bk.days) if d <= last))
        cands, _ = T.cands_for(sym, "vol", part)
        g = T.grid(sym)
        busy, used = -1, set()
        for c in T.select(cands, variant):
            day = g["days"][c.di]
            if day not in bk._dix or c.leg in used:
                continue
            di = bk.di_of(day)
            if di * K58.NMIN + c.s + 1 <= busy:
                continue
            ref = g["M"][c.di, c.s + 1, 0]
            if not np.isfinite(ref):
                continue
            ut = ref + c.side * 2 * abs(ref - c.stop) if tgt == "2r" else c.st_tgt
            sp = E58.Spec(sym=sym, di=di, s=c.s, side=c.side, ustop=c.stop, utgt=ut, hold=1)
            t = E58.simulate(bk, sp, mode, want_cash=False)
            if t is None:
                continue
            t2 = E58.simulate(bk, sp, replace(mode, cost=2.0), want_cash=False)
            used.add(c.leg)
            busy = di * K58.NMIN + t["exit_f"]
            t.update(inst=sym, r_mult=np.nan, net2=t2["net"] if t2 else np.nan, s=c.s)
            out.append({k: v for k, v in t.items() if k != "spec"})
    return out


def main(part: str):
    if part == "final":
        if not K.FINAL_FLAG.exists():
            raise PermissionError("STK-opt final runs only after `r62study.py final` has opened the final read")
        K.open_final()
    res = {}
    for v in K.VARIANTS:
        tr = run(v, part)
        s = T.stats(tr, "net") if tr else dict(trades=0, net=0)
        s["net_2x"] = round(float(np.nansum([t["net2"] for t in tr]))) if tr else 0
        s["stale_model_exits"] = sum(t["src"] != "print" for t in tr)
        res[v] = s
        print(part, v, s.get("trades"), s.get("net"), flush=True)
    (K.RESULTS / f"opt_{part}.json").write_text(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    main(sys.argv[1])
