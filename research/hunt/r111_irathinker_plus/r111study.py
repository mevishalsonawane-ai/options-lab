"""R111 study (26 Oct 2026): the 10 pre-registered enhancements of IraThinker's strategies (PREREG.md).

    python -I research/hunt/r111_irathinker_plus/r111study.py <release_dir> <bars_dir> <signals_dir> <out_dir>

Same data, signals, pricing, split and gates as R110 (r110study.py is reused); only the trade built on each signal changes.
Signal source of every rule: the underlying index. Release files are untrusted data: read only, run with python -I.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, timedelta

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "r110_irathinker"))
import r110study as R  # noqa: E402

CONSENSUS_MEDIAN = {"NIFTY": 94.1, "BANKNIFTY": 93.9}   # design-period medians (PREREG.md, from the signal file only)
WING = 400                                              # wings 400 beyond the shorts: ATM +/- 600
STOP_X = 2.0                                            # buy-back cost at 2x the credit = a loss of one credit


# ------------------------------------------------------------------------------------------------ mom60 variants
def run_mom(mk: R.Market, mom: pd.DataFrame, fill: R.Fill, *, itm: bool = False, stop: bool = True,
            monthly: bool = True) -> pd.DataFrame:
    out = []
    step = R.STEP[mk.und]
    for _, r in mom.iterrows():
        d, e = R.minute_of(int(r["entry_ts"]))
        up = int(r["call"]) == 1
        _, xm = R.minute_of(int(r["exit_ts"]) - 60)
        k = float(r["strike"]) + ((-step if up else step) if itm else 0)
        tr = R.long_option(mk, d, "CE" if up else "PE", k, e, prem_stop_mult=0.70 if stop else None, exit_min=xm,
                           fill=fill, need_expiry=(lambda dd, ex: R.is_monthly(mk, ex, dd)) if monthly else None)
        if tr is not None:
            out.append(tr)
    return pd.DataFrame(out)


# ------------------------------------------------------------------------------------------------ strangle variants
def _last_close(a: np.ndarray, upto: int) -> float:
    c = a[3, :upto + 1]
    c = c[np.isfinite(c)]
    return float(c[-1]) if len(c) else float("nan")


def strangle_plus(mk: R.Market, row: dict, fill: R.Fill, *, stop: bool = False, wings: bool = False,
                  skip_expiry_week: bool = False):
    """R110's S4 (sell CE / PE at IraThinker's strikes at 09:31 Monday, out at the exit day's last close or settle at
    expiry intrinsic) with: stop - out at the next minute's open once the buy-back cost reaches STOP_X x the net credit
    (minute closes, last known close of each leg); wings - also buy CE +WING and PE -WING (an iron condor);
    skip_expiry_week - no entry when the real expiry falls inside Mon-Thu. None when no real monthly price exists."""
    d = date.fromisoformat(row["monday"])
    if d not in mk.opt:
        return None
    exp = mk.expiry[d]
    if not R.is_monthly(mk, exp, d):
        return None
    thu = d + timedelta(days=3)
    settle = exp <= thu
    if skip_expiry_week and settle:
        return "skipped"
    lots = R.lot(mk.und, d)
    ce, pe = float(row["ce_strike"]), float(row["pe_strike"])
    legs = [("CE", ce, -1), ("PE", pe, -1)]              # -1 sold, +1 bought
    if wings:
        legs += [("CE", ce + WING, +1), ("PE", pe - WING, +1)]
    entry = []
    for right, k, sgn in legs:
        a = mk.opt[d].get((exp, k, right))
        if a is None:
            return None
        ef = R.first_print(a, 16, R.W)
        if ef < 0:
            return None
        v = R.vol5_lots(a, ef, lots)
        entry.append(fill.sell(a[0, ef], v) if sgn < 0 else fill.buy(a[0, ef], v))
    credit = sum(p if s < 0 else -p for (_, _, s), p in zip(legs, entry))      # net credit per unit
    if credit <= 0:
        return None
    ch = sum(R.charge(s > 0, p, lots, d) for (_, _, s), p in zip(legs, entry))
    exit_day = exp if settle else max((x for x in mk.days if d <= x <= thu), default=None)
    if exit_day is None or exit_day not in mk.ix:
        return None                                       # as R110: no index close for the exit day, no price
    hold = [x for x in mk.days if d <= x <= exit_day]
    why, out_px, out_day = None, None, None
    if stop:
        last = [float("nan")] * len(legs)
        for x in hold:
            arrs = [mk.opt.get(x, {}).get((exp, k, r)) for r, k, _ in legs]
            start = 17 if x == d else 0
            for j in range(start, R.W):
                for i, a in enumerate(arrs):
                    if a is not None and np.isfinite(a[3, j]):
                        last[i] = float(a[3, j])
                if any(np.isnan(last)):
                    continue
                cost = sum(p if s < 0 else -p for (_, _, s), p in zip(legs, last))
                if cost >= STOP_X * credit:
                    # out at the next minute's open (the next day's first minute after the last one)
                    nx = (x, j + 1) if j + 1 < R.W else (next((y for y in mk.days if y > x), None), 0)
                    if nx[0] is None or nx[0] > exit_day:
                        break
                    px = []
                    for (r, k, s), lp in zip(legs, last):
                        a = mk.opt.get(nx[0], {}).get((exp, k, r))
                        f = R.first_print(a, nx[1], R.W) if a is not None else -1
                        p = float(a[0, f]) if f >= 0 else lp
                        v = R.vol5_lots(a, max(f, 0), lots) if a is not None else 0.0
                        px.append(fill.buy(p, v, stop=True) if s < 0 else fill.sell(p, v, stop=True))
                    why, out_px, out_day = "stop", px, nx[0]
                    break
            if why:
                break
    if why is None:
        out_day = exit_day
        spot = mk.ix[exit_day][3][np.isfinite(mk.ix[exit_day][3])]
        if not len(spot):
            return None
        out_px = []
        for r, k, s in legs:
            if settle:
                p = max(0.0, spot[-1] - k) if r == "CE" else max(0.0, k - spot[-1])
            else:
                a = mk.opt.get(exit_day, {}).get((exp, k, r))
                if a is None:
                    return None
                lc = _last_close(a, R.W - 1)
                if np.isnan(lc):
                    return None
                v = R.vol5_lots(a, R.W - 1, lots)
                p = fill.buy(lc, v) if s < 0 else fill.sell(lc, v)
            out_px.append(p)
        why = "settle" if settle else "thu_close"
    gross = sum((pin - pout) * lots if s < 0 else (pout - pin) * lots
                for (_, _, s), pin, pout in zip(legs, entry, out_px))
    if why != "settle":
        ch += sum(R.charge(s < 0, p, lots, out_day) for (_, _, s), p in zip(legs, out_px))
    return {"day": d, "expiry": exp, "exit_day": out_day, "why": why, "credit": round(credit, 2), "qty": lots,
            "gross": round(gross, 2), "charges": round(ch, 2), "net": round(gross - ch, 2)}


def run_strangle(mk: R.Market, st: pd.DataFrame, fill: R.Fill, **kw) -> tuple[pd.DataFrame, int]:
    rows, skipped = [], 0
    for r in st.to_dict("records"):
        x = strangle_plus(mk, r, fill, **kw)
        if x == "skipped":
            skipped += 1
        elif x:
            rows.append(x)
    return pd.DataFrame(rows), skipped


# ------------------------------------------------------------------------------------------------ the 10 variants
VARIANTS = [
    ("E1", "BANKNIFTY", "strangle", {"stop": True}, "ex-ante", False),
    ("E2", "BANKNIFTY", "strangle", {"wings": True}, "ex-ante", False),
    ("E3", "BANKNIFTY", "strangle", {"skip_expiry_week": True}, "post-hoc", False),
    ("E4", "NIFTY", "mom60", {"itm": True}, "ex-ante", True),
    ("E5", "BANKNIFTY", "mom60", {"itm": True}, "ex-ante", True),
    ("E6", "NIFTY", "mom60", {"stop": False}, "ex-ante", True),
    ("E7", "BANKNIFTY", "mom60", {"stop": False}, "ex-ante", True),
    ("E8", "NIFTY", "mom60", {"monthly": False}, "ex-ante", True),
    ("E9", "NIFTY", "predict", {}, "ex-ante", True),
    ("E10", "BANKNIFTY", "predict", {}, "ex-ante", True),
]


def trades_for(mk, sig, kind, kw, fill):
    und = mk.und
    if kind == "strangle":
        st = pd.read_csv(os.path.join(sig, "strangle_BANKNIFTY.csv"))
        return run_strangle(mk, st, fill, **kw)[0]
    if kind == "mom60":
        return run_mom(mk, pd.read_csv(os.path.join(sig, f"mom60_{und}.csv")), fill, **kw)
    pred = pd.read_csv(os.path.join(sig, f"predict_{und}.csv"))
    pred = pred[(pred["dir60"] != 0) & (pred["consensus60"] >= CONSENSUS_MEDIAN[und])]
    t = R.run_directional(mk, pred, "S5", fill, True)
    return t


def main(rel: str, bars: str, sig: str, out: str) -> None:
    os.makedirs(out, exist_ok=True)
    rng = np.random.default_rng(108)
    mks = {u: R.Market(u, bars, rel) for u in ("NIFTY", "BANKNIFTY")}
    results = []
    for vid, und, kind, kw, tag, can_pass in VARIANTS:
        mk = mks[und]
        t1 = trades_for(mk, sig, kind, kw, R.Fill(1.0))
        t2 = trades_for(mk, sig, kind, kw, R.Fill(2.0))
        t1.to_csv(os.path.join(out, f"trades_{vid}.csv"), index=False)
        b = R.block(und, vid, True, int(len(t1)), t1, t2, rng)
        b.update({"strategy": kind, "change": kw, "tag": tag, "can_pass": can_pass})
        if kind == "strangle" and not t1.empty:
            des = t1[t1["day"] <= R.DESIGN_END]
            b["design"]["net_pct_of_premium"] = round(float((des["net"] / (des["credit"] * des["qty"])).mean() * 100), 1)
            b["design"]["worst_week"] = round(float(des["net"].min()), 0)
            if "why" in des:
                b["design"]["exits"] = des["why"].value_counts().to_dict()
        results.append(b)
        print(vid, und, kind, b["design"].get("n"), b["design"].get("mean"), flush=True)
    qs = R.bh([r["design"].get("p", 1.0) for r in results])
    for r, q in zip(results, qs):
        r["design"]["q"] = round(q, 4)
    # also over all 23 = R110's 13 real-price tests + these 10
    r110 = os.path.join(HERE, "..", "r110_irathinker", "results", "results.json")
    old = [x["design"]["p"] for x in json.load(open(r110)) if x["real"] and x["design"].get("n", 0) > 0]
    q23 = R.bh(old + [r["design"].get("p", 1.0) for r in results])[len(old):]
    for r, q in zip(results, q23):
        r["design"]["q_with_r110"] = round(q, 4)
    for r in results:
        g, ok = R.gates(r)
        r["gates"] = g
        r["pass_design"] = bool(ok and r["can_pass"])
        if r["pass_design"]:
            h = r["holdout"]
            r["holdout_read"] = True
            r["pass_holdout"] = bool(h.get("n", 0) >= 10 and h.get("mean", -1) > 0)
        else:
            r["holdout_read"] = False
            r["holdout"] = {"n": r["holdout"].get("n", 0), "note": "not read (failed design)"}
    with open(os.path.join(out, "results.json"), "w") as f:
        json.dump(results, f, indent=1, default=str)
    print("done")


if __name__ == "__main__":
    main(*sys.argv[1:5])
