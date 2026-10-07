"""h6 signal sets (all known at the signal minute's close; no look-ahead).

DIV   divergence PAIR: at T minutes after the open, among the 6 index pairs (NIFTY, BANKNIFTY, FINNIFTY, SENSEX) take the
      one whose open->T log-return spread has the largest |z| (spread / sd of the same spread over the previous 60
      days); trade when |z| > k. mode 'mom': buy ATM call on the outperformer + ATM put on the underperformer;
      'rev': the opposite. Legs sized to equal notional (lots ~ max notional / own notional), so delta-balanced.
CATCH catch-up: same pair choice, but the leader must also have moved |own z| > 1 and in the spread's direction; buy
      the LAGGARD's ATM option in the leader's direction (single leg).
RPAIR random pairs for the DIV control: same day, same two indices, same lots, uniform random minute 09:30-13:30,
      coin-flip orientation (which index gets the call).
"""
import sys, os, itertools
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as cm
import numpy as np, pandas as pd
from obuy import config as C

PAIRS = list(itertools.combinations(cm.UNDS, 2))


def _pair_table(T):
    A = cm.aligned()
    Cl, Op = A["C"], A["O"]
    days = A["days"]
    out = {}
    for a, b in PAIRS:
        ra = np.log(Cl[a][:, T - 1] / Op[a][:, 0]); rb = np.log(Cl[b][:, T - 1] / Op[b][:, 0])
        sp = ra - rb
        s = pd.Series(sp)
        sd = s.shift(1).rolling(60, min_periods=30).std().values      # NaN days skipped by rolling? no: use dropna
        s2 = s.dropna()
        sd2 = s2.shift(1).rolling(60, min_periods=30).std().reindex(s.index).values
        z = sp / sd2
        sra = pd.Series(ra).dropna(); sdA = sra.shift(1).rolling(60, min_periods=30).std().reindex(s.index).values
        srb = pd.Series(rb).dropna(); sdB = srb.shift(1).rolling(60, min_periods=30).std().reindex(s.index).values
        ok = np.isfinite(z) & ~A["exp"][a] & ~A["exp"][b]
        out[(a, b)] = dict(z=np.where(ok, z, np.nan), ra=ra, rb=rb, za=ra / sdA, zb=rb / sdB,
                           ca=Cl[a][:, T - 1], cb=Cl[b][:, T - 1])
    return A, days, out


def _lots(A, j, u, spot_by_u):
    lots = {x: A["lot"][x][j] for x in spot_by_u}
    notl = {x: lots[x] * spot_by_u[x] for x in spot_by_u}
    mx = max(notl.values())
    return {x: int(lots[x] * max(1, round(mx / notl[x]))) for x in spot_by_u}


def pick_pairs(T, k):
    A, days, tab = _pair_table(T)
    rows = []
    for j, d in enumerate(days):
        best = None
        for (a, b), t in tab.items():
            z = t["z"][j]
            if np.isfinite(z) and abs(z) > k and (best is None or abs(z) > abs(best[2])):
                best = (a, b, z, t, j)
        if best:
            a, b, z, t, j = best
            lead, lag = (a, b) if z > 0 else (b, a)
            r_lead = t["ra"][j] if z > 0 else t["rb"][j]
            z_lead = t["za"][j] if z > 0 else t["zb"][j]
            z_lag = t["zb"][j] if z > 0 else t["za"][j]
            lots = _lots(A, j, None, {a: t["ca"][j], b: t["cb"][j]})
            rows.append(dict(day=d, lead=lead, lag=lag, z=abs(z), r_lead=r_lead, z_lead=z_lead, z_lag=z_lag, lots=lots))
    return pd.DataFrame(rows)


def div(T=30, k=1.5, mode="mom"):
    P = pick_pairs(T, k)
    sm = C.OPEN_M + T - 1
    out = []
    for r in P.itertuples():
        tag = f"{r.day}|{r.lead}>{r.lag}"
        s_lead = 1 if mode == "mom" else -1
        for u, sd in ((r.lead, s_lead), (r.lag, -s_lead)):
            out.append(dict(und=u, day=r.day, sig_min=sm, side=sd, book=f"div_{u}", lot=r.lots[u], tag=tag))
    return pd.DataFrame(out)


def rpair(T=30, k=1.5, K=5, seed=11):
    P = pick_pairs(T, k)
    rng = np.random.default_rng(seed + T * 10 + int(k * 10))
    out = []
    for r in P.itertuples():
        for i in range(K):
            sm = int(rng.integers(9 * 60 + 30, 13 * 60 + 30 + 1))
            o = int(rng.choice((1, -1)))
            tag = f"{r.day}|{r.lead}>{r.lag}|r{i}"
            for u, sd in ((r.lead, o), (r.lag, -o)):
                out.append(dict(und=u, day=r.day, sig_min=sm, side=sd, book=f"rp{i}_{u}", lot=r.lots[u], tag=tag))
    return pd.DataFrame(out)


def catch(T=30, k=1.5):
    P = pick_pairs(T, k)
    # the outperformer up (r_lead > 0): it leads UP, needs own z > 1; else the underperformer leads DOWN, own z < -1
    P = P[((P.r_lead > 0) & (P.z_lead > 1)) | ((P.r_lead <= 0) & (P.z_lag < -1))]
    out = []
    sm = C.OPEN_M + T - 1
    for r in P.itertuples():
        if r.r_lead > 0:
            lead, lag, side = r.lead, r.lag, +1          # leader up more; laggard should catch up upward
        else:
            lead, lag, side = r.lag, r.lead, -1          # the 'lag' fell more: it leads downward; buy put on the other
        out.append(dict(und=lag, day=r.day, sig_min=sm, side=side, book=f"catch_{lag}", lot=r.lots[lag],
                        tag=f"{r.day}|{lead}>{lag}"))
    return pd.DataFrame(out)
