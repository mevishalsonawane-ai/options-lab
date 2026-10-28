"""R120 arms (PREREG section 1 + Amendment A1): each arm's historical trades from R113's adapters' own code paths, plus
what the app's TimeMachineHost.Entry call passes for that arm (stop and target in underlying points, exit time, bars).

    python research/hunt/r120_time_machine_gate/r120arms.py build <ARM>     # one arm per process (module clashes)

Writes $R120_OUT/trades/<ARM>.pkl and <ARM>.json (outside git). The trades' nets are the adapters' own, unchanged; R120
reads the entry premium (and Liquidity's pool level and target) from the same objects. 28 Oct 2026.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from datetime import date, datetime, time, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
ADP = HUNT / "r113_time_of_day" / "adapters"
sys.path.insert(0, str(HERE))
import r120cfg as K  # noqa: E402
import tm  # noqa: E402

# ---------------------------------------------------------------- the app's asks (Amendment A1), pure


T1515, T1510, T1505 = time(15, 15), time(15, 10), time(15, 5)


def liq_index_stop(und: str) -> float:
    """LiquidityRules.indexStopPoints."""
    return {"FINNIFTY": 15.0, "MIDCPNIFTY": 8.0}.get(und, 30.0)


def ask_fields(arm: str, prem: float | None, **kw) -> dict:
    """stop_pts / target_pts (underlying points, after TimeMachineHost.points), exit_at, bar_minutes for one entry."""
    def prem_pts(stop_frac, target_frac=None):
        s = None if prem is None or stop_frac is None else stop_frac * prem
        t = None if prem is None or target_frac is None else target_frac * prem
        return tm.points(s, t, premium=True)

    if arm in ("R51", "R72"):
        s, t = prem_pts(0.15)                 # DonchianArm.EXITS under the 30-60-100 lock: no target
        return dict(stop_pts=s, target_pts=t, exit_at=T1515, bar_minutes=5)
    if arm == "R52":
        s, t = prem_pts(0.30)                 # OovArm.EXITS: no target
        return dict(stop_pts=s, target_pts=t, exit_at=T1510, bar_minutes=5)
    if arm == "LIQ":
        und, target, level = kw["und"], kw.get("target"), kw.get("level")
        tp = None if target is None or level is None or not _finite(target) else abs(target - level)
        s, t = tm.points(liq_index_stop(und), tp, premium=False)
        return dict(stop_pts=s, target_pts=t, exit_at=T1510, bar_minutes=5)
    if arm == "SOLO":
        setup = kw["setup"]
        frac, at = {"S2": (0.15, T1515), "S3": (0.30, T1510), "S4": (0.25, T1510)}.get(setup, (None, time(14, 30)))
        s, t = prem_pts(frac)                 # S1: no stop on the passed-arms host -> unknown
        return dict(stop_pts=s, target_pts=t, exit_at=at, bar_minutes=5)
    if arm == "HERO":
        s, t = prem_pts(0.25)                 # HeroTicketRules.TRAIL; trail-only default: no target
        return dict(stop_pts=s, target_pts=t, exit_at=T1505, bar_minutes=5)
    if arm in ("ORB_ORB", "ORB_FRESH", "ORB_FADE", "ORB_SWEEP"):
        s, t = tm.points(40.0, 80.0 if arm == "ORB_SWEEP" else 40.0, premium=True)
        return dict(stop_pts=s, target_pts=t, exit_at=T1510, bar_minutes=5)
    if arm == "VIXDIV":
        s, t = prem_pts(0.15) if kw["und"] == "NIFTY" else (None, None)   # BANKNIFTY passes no stop
        return dict(stop_pts=s, target_pts=t, exit_at=T1510, bar_minutes=5)
    if arm.startswith("PINE_"):
        tick = 0.05
        stop = 30.0 if prem is not None and prem - 30.0 > tick else None   # PineProtection.STOP
        s, t = tm.points(stop, None, premium=True)
        return dict(stop_pts=s, target_pts=t, exit_at=T1515, bar_minutes=5)
    raise KeyError(arm)


def _finite(x) -> bool:
    try:
        return x == x and abs(float(x)) != float("inf")
    except (TypeError, ValueError):
        return False


def trade(arm, und, day, minute_from_0915, side, net, prem, **kw) -> dict:
    d = day if isinstance(day, date) and not isinstance(day, datetime) else day.date()
    t = dict(arm=arm, und=und, day=d, entry_ts=datetime.combine(d, time(9, 15)) + timedelta(minutes=int(minute_from_0915)),
             side=side, net=float(net), prem=(None if prem is None else float(prem)), period=K.period_of(d),
             setup=kw.get("setup"))
    t.update(ask_fields(arm, t["prem"], und=und, **kw))
    return t


# ---------------------------------------------------------------- builders (one per adapter)


def build_core(arm: str) -> tuple[list, dict]:
    sys.path.insert(0, str(ADP))
    import core as C
    import r113cfg as K113
    out, par = [], {}
    for per, (lo, hi) in (("design", (K113.START, K113.DESIGN_END)), ("final", (K113.FINAL_START, K113.FINAL_END))):
        if per == "final":
            C.open_final()
        tr = C.walk(arm, C.units(arm, lo, hi))
        istop = None
        if arm == "LIQ":
            import fam_liq
            istop = fam_liq.IDX_STOP
        for x in tr:
            kw = {}
            if arm == "LIQ":
                sgn = 1 if x["side"] == "C" else -1
                kw = dict(target=(float(x["target"]) if _finite(x.get("target")) else None),
                          level=float(x["idx_stop"]) + sgn * istop[x["und"]])
            out.append(trade(arm, x["und"], x["day"], x["er"], 1 if x["side"] == "C" else -1, x["net"], x["E"], **kw))
        par[per] = dict(trades=len(tr), net=round(sum(x["net"] for x in tr)), published=C.PUBLISHED.get((arm, per)))
    return out, par


def build_solo() -> tuple[list, dict]:
    sys.path.insert(0, str(ADP))
    import solo as SO
    out, par = [], {}
    for per in ("design", "final"):
        tr = SO.picks(per)
        par[per] = dict(trades=len(tr), net=round(sum(t["net"] for t in tr)), published=SO.PUBLISHED[per])
        for x in tr:
            out.append(trade("SOLO", x["und"], x["day"], x["er"], 1 if x["side"] == "C" else -1, x["net"], x["E"],
                             setup=x["setup"]))
    return out, par


def build_hero() -> tuple[list, dict]:
    sys.path.insert(0, str(ADP))
    import hero as H
    H.LB.load_late()
    out, par = [], {}
    for part in ("design", "final"):
        days = []
        for u in H.K.UNDS:
            days += H.DD.load(u, part)
        days.sort(key=lambda d: (d.day, d.und))
        tr = H.L.run_cell(days, *H.CELL)
        par[part] = dict(trades=len(tr), net=round(sum(t.net for t in tr)), published=H.PUBLISHED[part])
        for t in tr:
            out.append(trade("HERO", t.und, t.day, t.fill_row, 1 if t.side > 0 else -1, t.net, t.entry))
    return out, par


def build_orb() -> dict:
    sys.path.insert(0, str(ADP))
    import orb as O
    ss = O.D.load(O.UND, "design")
    res = {}
    for arm, aid in O.ARM_ID.items():
        tr = O.E.run(ss, O.E.baseline(arm))
        lst = [trade(aid, t["und"], t["day"], t["entry_row"], 1 if t["side"] > 0 else -1, t["net"], t["entry"])
               for t in tr]
        res[aid] = (lst, dict(all=dict(trades=len(tr), net=round(sum(t["net"] for t in tr)), published=O.PUBLISHED[arm])))
    return res


def build_vixdiv() -> tuple[list, dict]:
    sys.path.insert(0, str(ADP))
    import vixdiv as V
    import numpy as np
    out, par = [], {}
    for u in ("NIFTY", "BANKNIFTY"):
        for period in ("design", "holdout"):
            P, mask, req, el = V.n13_requests(u, period)
            req = req[req["var"] == V.PICK[u]].reset_index(drop=True)
            tr = V.Dz.engine_dedup(P, req)
            tr = tr[np.isfinite(tr.net)]
            par[f"{u}_{period}"] = dict(trades=len(tr), net=round(float(tr.net.sum())))
            for r in tr.itertuples():
                d = P["days"][int(r.di)]
                out.append(trade("VIXDIV", u, d, r.ent, 1 if int(r.side) == 0 else -1, r.net, r.E))
            V.L.D.market().release()
    return out, par


def build_pine() -> dict:
    sys.path.insert(0, str(ADP))
    import pine_h22 as PH
    mk = PH.PR.market()
    PH.PR.H19.supplement(mk, "BANKNIFTY")
    ix = mk.index("BANKNIFTY")
    S = []
    for d in ix.days:
        a, n = PH.PR.dense(ix.d[d])
        if a is None or n < 250:
            continue
        S.append((d, a, ix.lot(d), bool(ix.d[d].get("exp", False))))
    days = {d for d, *_ in S if PH.START <= d <= PH.END}
    res = {}
    for name, tf, fn in (("st_ema_5m", 5, PH.PR.st_ema_targets), ("st_ema_15m", 15, PH.PR.st_ema_targets),
                         ("matrix_5m", 5, PH.PR.matrix_targets)):
        b = PH.PR.bars_of(S, tf)
        tl = PH.PR.replay(mk, S, b, fn(b), days)
        aid = PH.ARM[name]
        lst = [trade(aid, "BANKNIFTY", r.day, r.em, 1 if r.side > 0 else -1, r.net, r.entry) for r in tl.itertuples()]
        res[aid] = (lst, dict(all=dict(trades=len(lst), net=round(float(tl.net.sum())), published=PH.PUBLISHED[name])))
    return res


def save(arm: str, trades: list, par: dict) -> None:
    K.TRADES.mkdir(parents=True, exist_ok=True)
    with open(K.TRADES / f"{arm}.pkl", "wb") as f:
        pickle.dump(trades, f)
    by = {}
    for t in trades:
        p = t["period"] or "other"
        by.setdefault(p, [0, 0.0])
        by[p][0] += 1
        by[p][1] += t["net"]
    meta = dict(arm=arm, n=len(trades), by_period={p: dict(trades=a, net=round(b)) for p, (a, b) in by.items()},
                parity=par, no_stop=sum(1 for t in trades if t["stop_pts"] is None))
    (K.TRADES / f"{arm}.json").write_text(json.dumps(meta, indent=1, default=str))
    print(json.dumps(meta, default=str), flush=True)


def load(arm: str) -> list:
    with open(K.TRADES / f"{arm}.pkl", "rb") as f:
        return pickle.load(f)


def main(arm: str) -> None:
    if arm in ("R72", "R51", "R52", "LIQ"):
        save(arm, *build_core(arm))
    elif arm == "SOLO":
        save(arm, *build_solo())
    elif arm == "HERO":
        save(arm, *build_hero())
    elif arm == "VIXDIV":
        save(arm, *build_vixdiv())
    elif arm == "ORB":
        for aid, (lst, par) in build_orb().items():
            save(aid, lst, par)
    elif arm == "PINE":
        for aid, (lst, par) in build_pine().items():
            save(aid, lst, par)
    else:
        raise SystemExit(f"unknown arm {arm}")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "build":
        main(sys.argv[2])
    else:
        print(__doc__)
