"""R44 runner (PREREG.md "Periods").

    python3 research/hunt/r44/run.py design      # Step 1: IS 2021-23 and OOS-design (1 Jan 2024 - 13 Apr 2026)
    python3 research/hunt/r44/run.py holdout     # Step 2: the ONE holdout read (27 Apr - 12 Oct 2026) + Boss's windows

Writes results/design.json, results/holdout.json (and results/HOLDOUT_READ). Trades stay in R44_OUT (never in git).
"""
from __future__ import annotations

import json
import sys
import time
from datetime import date

import numpy as np
import pandas as pd

import r44cfg as K
import engine as G

SIDES = {0: "CE", 1: "PE"}


def cells():
    for und in K.UNDS:
        for strat in K.STRATS:
            for side in (0, 1):
                for sess in K.sessions(und):
                    yield und, strat, side, sess


def key(und, strat, side, sess):
    return f"{strat}|{und}|{SIDES[side]}|{sess}"


def sessions_in(D, mask_fn, keep_expiry: bool) -> int:
    """Trading sessions of the period with option data (expiry days out unless kept)."""
    d = D.days[mask_fn(D.days)]
    if not keep_expiry:
        d = np.array([x for x in d if int(x) not in D.expiry])
    return int(len(d))


def period_mask(period: str, champ_days: set | None = None):
    if period == "SepOct2026":
        return lambda d: np.isin(np.asarray(d), np.array(sorted(champ_days), dtype=np.int64))
    return lambda d: G.in_period(d, period)


def evaluate(periods, twin_periods, champ_days=None, log=print):
    out = {}
    trades_all = {}
    t0 = time.time()
    for und, strat, side, sess in cells():
        D = G.data(und)
        k = key(und, strat, side, sess)
        rec = {}
        for keep in (False, True):
            tr = G.all_trades(und, strat, side, sess, keep_expiry=keep)
            if not keep:
                trades_all[k] = tr
            lab = "incl_expiry" if keep else "main"
            for per in periods:
                mf = period_mask(per, champ_days)
                sub = tr[mf(tr.d.values)] if len(tr) else tr
                st = G.stats(sub, sessions_in(D, mf, keep))
                if not keep and per in twin_periods:
                    p, tot = G.twins(D, sub, strat, side, sess)
                    st["p"] = p
                    st["twin_mean"] = round(float(tot.mean()), 2) if len(tot) else None
                rec[f"{lab}:{per}"] = st
        out[k] = rec
        m = rec.get(f"main:{periods[0]}", {})
        log(f"{k:52s} {m.get('trades', 0):6d} {m.get('net', 0):12.0f}  ({time.time() - t0:.0f}s)")
    return out, trades_all


def add_bh(out: dict, period: str):
    ks = [k for k in out if out[k][f"main:{period}"].get("p") is not None]
    ps = np.array([out[k][f"main:{period}"]["p"] for k in ks])
    qs = G.bh(ps)
    for k, q in zip(ks, qs):
        out[k][f"main:{period}"]["q"] = round(float(q), 4)
    return len(ks)


def gates(out: dict, k: str) -> dict:
    """PREREG gates; holdout part only when the holdout record exists."""
    r = out[k]
    od = r["main:OOS_design"]
    is_ = r.get("main:IS", {})
    und = k.split("|")[1]
    g = {
        "trades>=100": od["trades"] >= 100,
        "IS>0": True if K.is_mcx(und) else is_.get("net", 0) > 0,
        "OOS_design>0": od["net"] > 0,
        "boss_OOS_design>0": od.get("net_boss", 0) > 0,
        "q<0.05": (od.get("q") or 1.0) < 0.05,
        "maxDD<40k": od["max_dd"] < 40000,
        "ex_best_day>0": od.get("ex_best_day", 0) > 0,
    }
    if "main:holdout" in r:
        g["holdout>0"] = r["main:holdout"]["net"] > 0
    g["pass"] = all(g.values())
    return g


def save_trades(trades_all: dict, name: str):
    K.OUT.mkdir(parents=True, exist_ok=True)
    fr = []
    for k, tr in trades_all.items():
        if len(tr):
            fr.append(tr.assign(cell=k))
    if fr:
        pd.concat(fr, ignore_index=True).to_parquet(K.OUT / f"trades_{name}.parquet")


def design():
    G.ALLOW_HOLDOUT = False
    out, trades_all = evaluate(list(K.DESIGN_PERIODS), set(K.DESIGN_PERIODS))
    n = add_bh(out, "OOS_design")
    add_bh(out, "IS")          # descriptive only
    for k in out:
        out[k]["gates"] = gates(out, k)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    meta = dict(step="design", bh_family=n, twins=K.TWINS, seed=K.SEED, last_design_day=str(K.DESIGN_END))
    (K.RESULTS / "design.json").write_text(json.dumps(dict(meta=meta, cells=out), indent=1, default=str))
    save_trades(trades_all, "design")
    print(f"design done: {n} cells in the BH family; passing gates: "
          f"{[k for k in out if out[k]['gates']['pass']]}")


def champion_days() -> set:
    D = G.data("NIFTY")
    days = np.array([int(x) for x in np.unique(D.bd) if x <= K.LAST_DAY.toordinal()])
    return set(int(x) for x in days[-K.CHAMP_SESSIONS:])


def holdout():
    if K.HOLDOUT_FLAG.exists():
        raise SystemExit("the R44 holdout has been read already (results/HOLDOUT_READ); a second read is refused")
    dz = json.loads((K.RESULTS / "design.json").read_text())
    K.HOLDOUT_FLAG.write_text(f"read {time.strftime('%Y-%m-%d %H:%M:%S')} UTC by run.py holdout\n")
    G.ALLOW_HOLDOUT = True
    G.data.cache_clear()
    G._SIG.clear()
    champ = champion_days()
    periods = ["holdout", "IS", "OOS_design", "OOS_boss", "JanAug2026", "SepOct2026", "ALL"]
    out, trades_all = evaluate(periods, {"holdout"}, champ_days=champ)
    # the design numbers must not move when later bars are visible (no look-ahead)
    moved = [k for k in out if abs(out[k]["main:OOS_design"]["net"] - dz["cells"][k]["main:OOS_design"]["net"]) > 0.01
             or out[k]["main:IS"]["trades"] != dz["cells"][k]["main:IS"]["trades"]]
    for k in out:
        for per in ("IS", "OOS_design"):
            for f in ("p", "q", "twin_mean"):
                if f in dz["cells"][k][f"main:{per}"]:
                    out[k][f"main:{per}"][f] = dz["cells"][k][f"main:{per}"][f]
    add_bh(out, "holdout")
    for k in out:
        out[k]["gates"] = gates(out, k)
    cd = sorted(champ)
    meta = dict(step="holdout", holdout=[str(K.HOLDOUT_START), str(K.LAST_DAY)],
                sepoct_window=[str(date.fromordinal(cd[0])), str(date.fromordinal(cd[-1]))], sepoct_sessions=len(cd),
                design_numbers_moved=moved)
    (K.RESULTS / "holdout.json").write_text(json.dumps(dict(meta=meta, cells=out), indent=1, default=str))
    save_trades(trades_all, "holdout")
    print(f"holdout done; design numbers moved: {moved}; passing: {[k for k in out if out[k]['gates']['pass']]}")


if __name__ == "__main__":
    {"design": design, "holdout": holdout}[sys.argv[1]]()
