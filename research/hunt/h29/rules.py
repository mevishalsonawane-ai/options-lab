"""h29 tests 1 and 2a: standalone directional option BUYING from option-pricing signals, through the obuy Lab
(app fills + dated charges + real half-spread from h24, same-exit random baseline, BH, SPA, walk-forward).

    OBUY_CACHE=<scratch>/hunt/h29/cache flock <scratch>/obuy.lock python3 -I research/hunt/h29/rules.py pre [mult]
    OBUY_CACHE=<scratch>/hunt/h29/cache flock <scratch>/obuy.lock python3 -I research/hunt/h29/rules.py hold <vid>
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import engine as EN  # noqa: E402
from obuy.engine import LADDER, Execution, Exits, StrikeRule  # noqa: E402
from obuy.lab import Lab  # noqa: E402
from obuy.strategies.base import Strategy  # noqa: E402

HOLD = pd.Timestamp("2025-10-01")
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY")
OUT = os.path.join(C.CACHE, "h29")
HS = {"BANKNIFTY": 0.0016, "NIFTY": 0.0016, "SENSEX": 0.0016, "MIDCPNIFTY": 0.0021, "FINNIFTY": 0.0042}
SPREAD_MULT = float(os.environ.get("H29_SPREAD", "1.0"))
SQ = 15 * 60 + 10

EXITS = [Exits(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=SQ),                         # E1 Liquidity arm
         Exits(stop_pct=0.15, tgt_pct=0.30, time_stop=20, time_gain=0.05, sq_off=SQ),           # E2 15/30
         Exits(stop_pct=0.15, ladder=LADDER, ladder_ref_pct=0.15, sq_off=SQ),                   # E3 ladder
         Exits(stop_pct=0.30, time_stop=30, time_gain=None, sq_off=SQ)]                         # E4 time exit

# ---------------------------------------------------------------------------------------- real spread on top of app fills
_orig_run = EN.Pack.run


def _run_spread(self, ex, exe, chunk=8000):
    tr = _orig_run(self, ex, exe, chunk)
    if len(tr) and SPREAD_MULT > 0:
        hs = np.maximum(tr.und.map(HS).fillna(0.0016).values * SPREAD_MULT - 0.0005, 0.0)
        extra = hs * (tr.entry.values + tr.exit.values) * tr.qty.values
        tr = tr.copy()
        tr["net"] = tr.net.values - extra
        tr["charges"] = tr.charges.values + extra
    return tr


EN.Pack.run = _run_spread

_F = {}


def feat(u):
    if u not in _F:
        f = os.path.join(OUT, f"feat_{u}.parquet")
        _F[u] = pd.read_parquet(f, columns=["day", "m", "S", "dbz", "drz", "cheap1", "S30", "open", "exp", "real"])
    return _F[u]


def _period(F, period):
    return F[F.day < HOLD] if period == "pre" else F[F.day >= HOLD]


def sig_t1(mk, kind="basis", k=2.0, period="pre"):
    out = []
    for u in UNDS:
        F = _period(feat(u), period)
        F = F[(~F.exp) & F.real]
        z1 = F.dbz.values.reshape(-1, C.W)
        z2 = F.drz.values.reshape(-1, C.W)
        days = F.day.values[::C.W]
        for sgn in (1, -1):
            if kind == "basis":
                cond = sgn * z1 >= k
            elif kind == "skew":
                cond = sgn * z2 >= k
            else:
                cond = (sgn * z1 >= k - 1) & (sgn * z2 >= k - 1)
            cross = cond.copy()
            cross[:, 1:] &= ~cond[:, :-1]
            cross[:, :15] = False                               # from 09:30
            cross[:, 14 * 60 + 30 - C.OPEN_M + 1:] = False      # to 14:30
            r, c = np.nonzero(cross)
            for ri, ci in zip(r, c):
                out.append((u, pd.Timestamp(days[ri]).date(), int(C.OPEN_M + ci), sgn))
    S = pd.DataFrame(out, columns=["und", "day", "sig_min", "side"]).sort_values(["und", "day", "sig_min"])
    S = S.groupby(["und", "day"]).head(10).reset_index(drop=True)
    S["book"] = "h29_" + S.und
    S["tag"] = kind
    return S


CHECKS = (599, 689, 779)          # bars closing at 10:00, 11:30, 13:00


def rank_table(u):
    """cheap1 at each check time per day, and its percentile within the prior 60 trading days (strict)."""
    F = feat(u)
    rows = []
    for m in CHECKS:
        g = F[F.m == m][["day", "cheap1", "S", "S30", "open", "exp", "real"]].sort_values("day").reset_index(drop=True)
        v = g.cheap1.values
        rk = np.full(len(v), np.nan)
        for i in range(len(v)):
            past = v[max(0, i - 60):i]
            past = past[np.isfinite(past)]
            if len(past) >= 40 and np.isfinite(v[i]):
                rk[i] = (past < v[i]).mean()
        g["rank1"] = rk
        g["m"] = m
        rows.append(g)
    return pd.concat(rows, ignore_index=True)


def sig_t2(mk, q=0.2, dirn="mom30", period="pre"):
    out = []
    for u in UNDS:
        R = rank_table(u)
        R = R[(R.day < HOLD) if period == "pre" else (R.day >= HOLD)]
        R = R[(~R.exp) & R.real & (R.rank1 <= q)]
        mv = (R.S - R.S30) if dirn == "mom30" else (R.S - R.open)
        side = np.sign(mv.values)
        for r, s in zip(R.itertuples(), side):
            if s != 0 and np.isfinite(s):
                out.append((u, r.day.date(), int(r.m), int(s)))
    S = pd.DataFrame(out, columns=["und", "day", "sig_min", "side"])
    S["book"] = "h29c_" + S.und
    return S


def strategies(period, g1=None, g2=None, x1=None, x2=None):
    g1 = g1 if g1 is not None else [dict(kind=kd, k=k, period=period) for kd in ("basis", "skew", "both") for k in (2.0, 3.0)]
    g2 = g2 if g2 is not None else [dict(q=q, dirn=dr, period=period) for q in (0.1, 0.2) for dr in ("mom30", "day")]
    out = []
    if g1:
        out.append(Strategy(name=f"h29t1_{period}", family="pricing_dir", signal_fn=sig_t1, sig_grid=g1,
                            rules=[StrikeRule(money=1)], exits=x1 or EXITS, exe=Execution(expiry="skip"),
                            pos=dict(one_at_a_time=True, max_per_day=3), window=(9 * 60 + 30, 14 * 60 + 30)))
    if g2:
        out.append(Strategy(name=f"h29t2_{period}", family="cheap_dir", signal_fn=sig_t2, sig_grid=g2,
                            rules=[StrikeRule(money=1)], exits=x2 or EXITS, exe=Execution(expiry="skip"),
                            pos=dict(one_at_a_time=True, max_per_day=3), window=(9 * 60 + 30, 14 * 60 + 30)))
    return out


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "pre":
        lab = Lab(strategies("pre"), name=f"h29_pre_s{SPREAD_MULT:g}", pool_k=5, B=2000, pool_all=True).run()
        print(lab.report())
    elif mode == "hold":
        vid = sys.argv[2]                                   # e.g. h29t1_pre|s0|r0|x1
        st, s, _, x = vid.split("|")
        si, xi = int(s[1:]), int(x[1:])
        if st.startswith("h29t1"):
            g = [dict(kind=kd, k=k, period="hold") for kd in ("basis", "skew", "both") for k in (2.0, 3.0)][si]
            sts = strategies("hold", g1=[g], g2=[], x1=[EXITS[xi]])
        else:
            g = [dict(q=q, dirn=dr, period="hold") for q in (0.1, 0.2) for dr in ("mom30", "day")][si]
            sts = strategies("hold", g1=[], g2=[g], x2=[EXITS[xi]])
        lab = Lab(sts, name=f"h29_hold_s{SPREAD_MULT:g}", pool_k=5, B=2000, pool_all=True).run()
        print(lab.report())
