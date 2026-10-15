"""R60 amendment 1 (15 Oct 2026, after the one final read): the sessions-to-expiry bug, fixed and measured.

    python research/hunt/r60/r60amend.py

The bug (r60mcx._days, fixed): the MCX expiry days never matched a session key, so `dte` was NaN everywhere and the
expiry-eve flag never set. Expiry days themselves were skipped correctly (R53's Market.is_exp). What it touched:
  - X1-X14 (SN / SE rules) never read `dte`, and their exits (14:30 / 20:30) are before the 23:00 expiry-eve cap, so
    they must reproduce trade for trade: checked here on design and final.
  - M1 / M2: the `dte` feature was constant (median-imputed); M1 had no MO candidates (its 11-20 sessions filter); EV
    trades on an expiry eve could run to 23:15 instead of 23:00.
This script refuses to run before results/FINAL_READ exists. It writes results/amend1.json with the corrected M1 / M2
("M1c" / "M2c"), read once, labelled a POST-LOCK CORRECTION: they cannot count as a clean pass whatever they show, and
the BH family and the gated verdicts stay the as-run ones.
"""
from __future__ import annotations

import json
import pickle

import numpy as np

import r60cfg as C
import r60mcx as X
import r60study as T

FLAG = C.RESULTS / "amend1.json"


def key(t):
    return (t["und"], t["setup"], str(t["day"]), int(t["i_in"]), int(t["i_out"]), round(t["net"], 6))


def main():
    if not C.FINAL_FLAG.exists():
        raise SystemExit("amendment 1 runs only after the final read")
    if FLAG.exists():
        raise SystemExit("amendment 1 was already run (results/amend1.json). One read only.")
    C.open_final()
    for s in C.MCX_SYMS:                                     # refresh the per-day fields with the fixed code
        m = X.market(s)
        X._days(m)
        with open(C.OUT / "mcx" / f"market_{s}.pkl", "wb") as f:
            pickle.dump(m, f, protocol=pickle.HIGHEST_PROTOCOL)
    old = {}
    for part in ("design", "final"):
        p = C.OUT / "mcx" / f"cands_{part}.pkl"
        asrun = C.OUT / "mcx" / f"cands_{part}_asrun.pkl"
        if not asrun.exists():
            p.rename(asrun)
        with open(asrun, "rb") as f:
            old[part] = pickle.load(f)
    new = {part: T.load_mcx(part) for part in ("design", "final")}
    check = {}
    for part in ("design", "final"):
        for s in X.SETUPS:
            a = sorted(key(t) for t in old[part] if t["setup"] == s)
            b = sorted(key(t) for t in new[part] if t["setup"] == s)
            check[f"{part}:{s}"] = dict(as_run=len(a), fixed=len(b), identical=a == b)
    for s in ("SN", "SE"):
        for part in ("design", "final"):
            assert check[f"{part}:{s}"]["identical"], f"{part}:{s} changed: the X rules would not reproduce"
    out = dict(note="POST-LOCK CORRECTION, read once after the final; cannot count as a clean pass; BH family and gated "
                    "verdicts stay as run", candidates=check, variants={})
    mrows = new["design"] + new["final"]
    cal_d = T.mcx_calendar(C.MCX_START, C.DESIGN_END)
    cal = T.mcx_calendar(C.MCX_START, C.END)
    for vid, setups in (("M1c", X.SETUPS), ("M2c", ("SN", "SE"))):
        dtr, dinfo = T.mcx_meta(new["design"], C.MCX_WF_MONTHS, cal_d, setups)
        ftr, finfo = T.mcx_meta(mrows, C.FINAL_MONTHS, cal, setups)
        tot = X.twin_totals(ftr, C.TWINS, C.SEED) if ftr else np.zeros(C.TWINS)
        p = T.p_value(sum(t["net"] for t in ftr), tot) if ftr else None
        ses = T.mcx_sessions("ALL", C.FINAL_START, C.END)
        out["variants"][vid] = dict(design=T.stats(dtr), design_auc=dinfo["auc"], test=T.stats(ftr, ses), p=p,
                                    twin_mean=round(float(tot.mean())), auc=finfo["auc"],
                                    verdict_if_it_had_been_pre_lock=T.gate(T.stats(ftr), p, None, T.stats(dtr)["net"]))
        print(vid, json.dumps(out["variants"][vid]["test"], default=str)[:300], p, flush=True)
    FLAG.write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    main()
