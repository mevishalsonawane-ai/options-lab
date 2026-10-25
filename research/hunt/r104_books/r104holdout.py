"""R104 holdout: the ONE read of every market's locked holdout, for every test at once (PREREG.md sections 6-7).

    python research/hunt/r104_books/r104holdout.py read      # refuses a second read (results/HOLDOUT_READ)
    python research/hunt/r104_books/r104holdout.py report    # results/holdout.json + gates -> results/final_gates.json

Entry tests trade the variant with the best full-design net (chosen before this read); comparison tests read the
host on the same holdout; ARM / MM tests build the arms' final-period units (R101's final, 1 Apr - 13 Oct 2026).
"""
from __future__ import annotations

import importlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r104cfg as K  # noqa: E402
import r104gate as G  # noqa: E402
import r104rules as RR  # noqa: E402
import r104stats as ST  # noqa: E402

MODULES = ("f3_patterns", "f10_indicators", "f6_porwal", "f7_aziz", "f1f2_wyckoff_volume", "f4_carli", "f5_swing",
           "f12_quant", "f8_vol", "f_new")


def read():
    if K.HOLDOUT_FLAG.exists():
        raise SystemExit("R104 holdout already read once: " + K.HOLDOUT_FLAG.read_text())
    K.HOLDOUT_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    import r104run as R
    for m in MODULES:
        importlib.import_module(m)
    design = G.load_design()
    out = {}
    for key, r in design.items():
        if "|" not in key:
            continue
        rid, market = key.split("|")
        if market in ("ARM", "MM") or (rid, market) not in R.IMPL:
            continue
        v = r.get("holdout_variant")
        imp = R.IMPL[(rid, market)]
        one = dict(imp)
        one["variants"] = {v: imp["variants"][v]}
        R.IMPL[(rid, market)] = one
        t = R.run_rule(rid, market, "holdout")[v]
        R.IMPL[(rid, market)] = imp
        (K.OUT / "trades").mkdir(parents=True, exist_ok=True)
        t.to_parquet(K.OUT / "trades" / f"{rid}__{market}__{v}__holdout.parquet")
        out[key] = dict(variant=v, **ST.summary(t))
        print(key, out[key]["n"], round(out[key]["net"]), flush=True)
        (K.RESULTS / "holdout_entries.json").write_text(json.dumps(out, indent=1, default=str))
    # the arms
    import f11_arms as A
    import f_new as N
    for arm in A.ARMS:
        A.units(arm, "final")
    arm_out = {}
    for rid in A.OVERLAYS:
        arm_out[f"{rid}|ARM"] = dict(variants={"base": A.delta_stats(A.run_overlay(rid, "final"))})
    for rid, (fns, arms) in list(A.FILTERS.items()) + list(N.NEW_FILTERS.items()):
        A.FILTERS[rid] = (fns, arms)
        allv = A.run_filter(rid, "final")
        arm_out[f"{rid}|ARM"] = dict(variants={vn: A.delta_stats(per) for vn, per in allv.items()})
    arm_out["book-heitkoetter-15|ARM"] = dict(variants={"base": A.delta_stats(A.run_sequential("book-heitkoetter-15", "final"))})
    arm_out["book-porwal-12|ARM"] = dict(variants={
        "stops": A.delta_stats(A.run_sequential("book-porwal-12", "final")),
        "losses": A.delta_stats(A.run_sequential("book-porwal-12", "final", any_loss=True))})
    mm = A.run_mm("final")
    for rid, r in mm.items():
        arm_out[f"{rid}|MM"] = r
    trades = A.mm_trades("final")
    base = A.equity_stats(trades, [1] * len(trades))
    lots = N.mm_n4(trades)
    arm_out["new-N4|MM"] = dict(base=base, variants={"base": dict(A.equity_stats(trades, lots), adds_risk=False)})
    (K.RESULTS / "holdout_arms.json").write_text(json.dumps(arm_out, indent=1, default=str))
    return out, arm_out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "read":
        read()
    else:
        raise SystemExit(__doc__)
