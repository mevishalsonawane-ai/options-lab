"""R89 same-day comparison with the arms already running (PREREG section 8; descriptive, no gate).

- R72 Breadth Donchian (C1, breadth 4 of 5, exits XL): R72 / R71's own code (r72study.use_r72 + r71study.rule_trades) on
  R89's stores (R51's loader, clean + stale step, to 19 Oct).
- Liquidity BANKNIFTY 15+5: R51 fam_liq.make('BANKNIFTY', (15, 5)) on the same stores.
- ORB signal (R20 V0): the 09:15-09:59 range, the first 5-minute close beyond it from 10:00, last decision bar 13:55, one
  per side a day (signals only: the ORB family is paused on paper).
The holdout window is compared only after R89's own holdout read (results/FINAL_READ exists).
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from datetime import date

import numpy as np
import pandas as pd

from . import cfg as K
from . import store  # noqa: F401  (R51 on sys.path with R89's window and stores)

import common as C51  # noqa: E402
import r51cfg as K51  # noqa: E402
import r51data  # noqa: E402

sys.path.insert(0, str(K.HERE.parent / "r72"))
sys.path.insert(0, str(K.HERE.parent / "r71"))


def bd_trades(lo: date, hi: date) -> pd.DataFrame:
    import r72study as S72
    S72.use_r72()
    rows = S72.S.rule_trades("C1", 4, "XL", lo, hi)
    return pd.DataFrame([dict(und=t["und"], date=t["day"], side=1 if t["side"] == "C" else -1, net=t["net"])
                         for t in rows])


def liq_trades(lo: date, hi: date) -> pd.DataFrame:
    import fam_liq
    T = fam_liq.make("BANKNIFTY", (15, 5)).run(lo, hi)
    if not len(T):
        return pd.DataFrame(columns=["und", "date", "side", "net"])
    return pd.DataFrame(dict(und=T.und.values, date=T.day.values, side=np.where(T.side == "C", 1, -1), net=T.net.values))


def orb_signals(lo: date, hi: date) -> pd.DataFrame:
    rows = []
    for und in K.INDICES:
        days = r51data.load(und)
        for d in sorted(days):
            if not (lo <= d <= hi):
                continue
            a = days[d].idx
            if not np.isfinite(a[1, :45]).any():
                continue
            hi_r, lo_r = np.nanmax(a[1, :45]), np.nanmin(a[2, :45])
            seen = set()
            for b in range(45, K51.row(13, 55) + 1, 5):
                c = a[3, b:b + 5]
                c = c[np.isfinite(c)]
                if not len(c):
                    continue
                x = c[-1]
                for s, ok in ((1, x > hi_r), (-1, x < lo_r)):
                    if ok and s not in seen:
                        seen.add(s)
                        rows.append(dict(und=und, date=d, side=s))
    return pd.DataFrame(rows)


def _r89(tag: str, v: str) -> pd.DataFrame:
    parts = []
    for und in K.INDICES:
        p = K.OUT / "trades" / tag / f"{und}_{v}.parquet"
        if p.exists():
            x = pd.read_parquet(p)
            x = x[x.filled]
            parts.append(pd.DataFrame(dict(und=und, date=pd.to_datetime(x.date).dt.date, side=x.side, net=x.net)))
    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame(columns=["und", "date", "side", "net"])


def compare(R: pd.DataFrame, A: pd.DataFrame, sessions: dict, has_net: bool) -> dict:
    if not len(R):
        return {}
    a_any = set(zip(A.und, A.date)) if len(A) else set()
    a_side = set(zip(A.und, A.date, A.side)) if len(A) else set()
    a_opp = set(zip(A.und, A.date, -A.side)) if len(A) else set()
    keys = list(zip(R.und, R.date))
    same_day = np.array([k in a_any for k in keys])
    same_side = np.array([k in a_side for k in zip(R.und, R.date, R.side)])
    opp = np.array([k in a_opp for k in zip(R.und, R.date, R.side)])
    out = dict(r89_trades=int(len(R)), share_same_day=round(float(same_day.mean()), 4),
               share_same_side=round(float(same_side.mean()), 4), share_opposite=round(float(opp.mean()), 4),
               r89_net_arm_days=round(float(R.net[same_day].sum()), 0), n_arm_days=int(same_day.sum()),
               r89_net_other_days=round(float(R.net[~same_day].sum()), 0), n_other=int((~same_day).sum()))
    if has_net and len(A):
        cors = []
        for und in sorted(set(R.und) & set(A.und)):
            ss = sessions.get(und, [])
            if not ss:
                continue
            ra = R[R.und == und].groupby("date").net.sum()
            aa = A[A.und == und].groupby("date").net.sum()
            x = np.array([ra.get(d, 0.0) for d in ss])
            y = np.array([aa.get(d, 0.0) for d in ss])
            if x.std() > 0 and y.std() > 0:
                cors.append((und, round(float(np.corrcoef(x, y)[0, 1]), 3)))
        out["daily_corr"] = dict(cors)
    return out


def main():
    wins = [("design", *K.IDX_DESIGN)]
    if K.FINAL_FLAG.exists():
        C51.open_final()
        wins.append(("final", *K.HOLDOUT))
    out = {}
    for tag, lo, hi in wins:
        sessions = {u: [d for d in sorted(r51data.load(u)) if lo <= d <= hi and r51data.load(u)[d].atm is not None]
                    for u in K.INDICES}
        arms = dict(breadth_donchian=bd_trades(lo, hi), liquidity_bn=liq_trades(lo, hi), orb_signal=orb_signals(lo, hi))
        res = dict(arms={k: dict(trades=int(len(v)), net=round(float(v.net.sum()), 0) if "net" in v else None,
                                 per_und={u: int(n) for u, n in v.und.value_counts().items()} if len(v) else {})
                         for k, v in arms.items()})
        res["variants"] = {}
        for v in K.VARIANTS:
            R = _r89(tag, v.name)
            res["variants"][v.name] = {k: compare(R if k != "liquidity_bn" else R[R.und == "BANKNIFTY"], A, sessions,
                                                  k != "orb_signal") for k, A in arms.items()}
        out[tag] = res
        print(tag, json.dumps(res["arms"]), flush=True)
    (K.RESULTS / "arms.json").write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    main()
