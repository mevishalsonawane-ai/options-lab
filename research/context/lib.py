"""Shared pieces: the event sessions, the BANKNIFTY ORB / ORB Fresh replay with real costs, BH and the splits."""
from __future__ import annotations

import bisect
import json
from datetime import date

import numpy as np
import pandas as pd

from research.obuy import config as OC
from research.obuy.costs import Costs, Fills

from . import config as C

FILLS = Fills(mode="liq")
COSTS = Costs(mode="app")


# ---- events --------------------------------------------------------------------------------------------------------
KIND_GROUP = {"rbi": "india_policy", "budget": "india_policy", "fomc": "us_macro", "us_cpi": "us_macro", "results": "results"}
RESULTS_FOR = {"BANKNIFTY": ("HDFC Bank", "ICICI Bank"), "NIFTY": ("HDFC Bank", "ICICI Bank", "Reliance")}


def events() -> pd.DataFrame:
    e = pd.read_csv(C.HERE / "events.csv", parse_dates=["date"])
    e["date"] = e.date.dt.date
    return e


def event_sessions(und: str, days: list[date]) -> pd.DataFrame:
    """(session, kind, group, name, verified) - the first session that trades after each event (see PREREG)."""
    rows = []
    for r in events().itertuples():
        if r.kind == "results" and not any(r.name.startswith(n) for n in RESULTS_FOR[und]):
            continue
        i = bisect.bisect_left(days, r.date) if r.ist_time != "after_close" else bisect.bisect_right(days, r.date)
        if i >= len(days) or (days[i] - r.date).days > 5:
            continue
        rows.append(dict(session=days[i], kind=r.kind, group=KIND_GROUP[r.kind], name=r.name, verified=r.verified == "yes"))
    return pd.DataFrame(rows)


def split(d: pd.DataFrame, col: str = "day") -> tuple[pd.Series, date]:
    last = max(d[col])
    h = C.holdout_from(last)
    return d[col] >= h, h


# ---- statistics ------------------------------------------------------------------------------------------------------
def bh(ps: dict[str, float], q: float = C.BH_Q) -> dict[str, float]:
    """Benjamini-Hochberg q-values for a dict of p-values (NaN p stays NaN and is not counted)."""
    items = [(k, p) for k, p in ps.items() if p is not None and np.isfinite(p)]
    m = len(items)
    out = {k: float("nan") for k in ps}
    if not m:
        return out
    items.sort(key=lambda x: x[1])
    qs = [p * m / (i + 1) for i, (_, p) in enumerate(items)]
    for i in range(m - 2, -1, -1):
        qs[i] = min(qs[i], qs[i + 1])
    for (k, _), qv in zip(items, qs):
        out[k] = min(1.0, qv)
    return out


def save(name: str, obj) -> None:
    C.RESULTS.mkdir(parents=True, exist_ok=True)
    (C.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str) + "\n")


# ---- the ORB arms (BANKNIFTY), as the app runs them -------------------------------------------------------------------
def lot(und: str, d: date) -> int:
    ls = OC.LOT_SCHEDULE[und]
    v = ls[0][1]
    for since, n in ls:
        if d.isoformat() >= since:
            v = n
    return v


def orb_day(s: dict, fresh: bool) -> list[dict]:
    """One session of ORB (fresh=False) or ORB Fresh: entries on 5-minute closes 10:05-13:55 beyond the 09:15-10:00 range,
    the ATM (09:20) call or put, premium >= 40, -40 stop with the lock ladder, +40 target, 15:10 square-off, one position at
    a time; re-entry from the bar after the exit's bar. Option prices are minute closes (the release has no ticks): an
    entry fills at the previous minute's close, a stop at min(stop, close). Costs: FILLS + COSTS."""
    if s.get("expiry") is None or "C_c" not in s:
        return []
    c, h, l = s["c"], s["h"], s["l"]
    nb = 75
    bh_ = np.array([np.nanmax(h[5 * k:5 * k + 5]) if np.isfinite(h[5 * k:5 * k + 5]).any() else np.nan for k in range(nb)])
    bl_ = np.array([np.nanmin(l[5 * k:5 * k + 5]) if np.isfinite(l[5 * k:5 * k + 5]).any() else np.nan for k in range(nb)])
    bc = np.array([c[5 * k + 4] for k in range(nb)])
    if np.isnan(bh_[:9]).sum() > 2:
        return []
    orh, orl = np.nanmax(bh_[:9]), np.nanmin(bl_[:9])
    q = lot("BANKNIFTY", s["day"])
    trades = []
    k = 10
    while k <= 56:
        if not np.isfinite(bc[k]):
            k += 1
            continue
        side = 1 if bc[k] > orh else -1 if bc[k] < orl else 0
        if side and fresh and np.isfinite(bc[k - 1]) and ((side > 0 and bc[k - 1] > orh) or (side < 0 and bc[k - 1] < orl)):
            side = 0
        if not side:
            k += 1
            continue
        px = s["C_c" if side > 0 else "P_c"]
        vol = s["C_v" if side > 0 else "P_v"]
        e = 5 * k + 5
        ref = px[e - 1]
        if not (np.isfinite(ref) and ref >= 40.0):
            k += 1
            continue
        v5 = vol[max(0, e - 5):e].sum() / q
        ent = float(FILLS.buy(np.array([ref]), np.array([v5]))[0])
        stop, best, x, why = ent - 40.0, ent, None, None
        for m in range(e, 356):
            p = px[m]
            if not np.isfinite(p):
                continue
            if p <= stop:
                x, why = (min(stop, p), True), "stop"
                break
            best = max(best, p)
            lock = ent + 20 if best >= ent + 30 else ent + 10 if best >= ent + 20 else ent if best >= ent + 10 else ent - 40
            stop = max(stop, lock)
            if p >= ent + 40:
                nxt = px[m + 1] if m + 1 < W_ and np.isfinite(px[m + 1]) else p
                x, why = (nxt, False), "target"
                break
        else:
            m = 355
        if x is None:
            p = px[355] if np.isfinite(px[355]) else px[np.isfinite(px)][-1]
            x, why = (p, False), "15:10"
        vx = vol[max(0, m - 4):m + 1].sum() / q
        out = float(FILLS.sell(np.array([x[0]]), np.array([vx]), stop=x[1])[0])
        ch = float(COSTS.charge(True, np.array([ent]), np.array([q]))[0] + COSTS.charge(False, np.array([out]), np.array([q]))[0])
        trades.append(dict(day=s["day"], arm="ORB Fresh" if fresh else "ORB", side=side, entry=ent, exit=out, why=why,
                           gross=(out - ent) * q, charges=ch, net=(out - ent) * q - ch))
        k = m // 5 + 1           # the next decision bar starts after the exit's 5-minute bar
    return trades


W_ = 375


def arm_days(sessions: list[dict]) -> pd.DataFrame:
    """Net P&L after costs per day and arm (0 on days with no trade)."""
    rows = []
    for s in sessions:
        for fresh in (False, True):
            t = orb_day(s, fresh)
            rows.append(dict(day=s["day"], arm="ORB Fresh" if fresh else "ORB", trades=len(t),
                             net=sum(x["net"] for x in t), charges=sum(x["charges"] for x in t)))
    return pd.DataFrame(rows)
