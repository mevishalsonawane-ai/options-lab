"""STRAD-INDEX build: per (index, day, decision minute) features, size targets, implied move, and the leg fill prices of
every pre-registered exit for the ATM straddle and the 1-strike-out strangle. See PREREG.md.

    flock <scratch>/obuy.lock python3 -I research/hunt/strad_index/build.py [NIFTY BANKNIFTY]
Output: <scratch>/hunt/strad_index/{feat_<U>.parquet, path_<U>.parquet}
"""
from __future__ import annotations

import importlib.util
import os
import sys
from datetime import date

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))   # research/
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy import data as D  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/strad_index")
EVENTS = os.path.join(C.SCRATCH, "hunt/h28/events.csv")
_spec = importlib.util.spec_from_file_location("h18build", os.path.join(os.path.dirname(HERE), "h18", "build.py"))
H18 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(H18)

GRID = np.arange(5, 336, 15)               # decision minute columns: 09:20 .. 14:50
HS = [15, 30, 60, 120, 0]                  # 0 = EOD (exit at 15:10)
HNAME = ["15", "30", "60", "120", "eod"]
XEOD = 355                                 # 15:10 column
EXITS = [(None, None), (0.20, None), (0.40, None), (None, 0.25), (None, 0.40), (0.20, 0.25), (0.40, 0.40)]
ENAME = ["T", "PT20", "PT40", "SL25", "SL40", "PT20SL25", "PT40SL40"]
LOT = {"NIFTY": 65, "BANKNIFTY": 30}


def ffill_lim(a, lim=5):
    """forward-fill NaNs of a 1-D array, at most `lim` steps; returns (filled, age)."""
    n = len(a)
    idx = np.where(~np.isnan(a), np.arange(n), -1)
    idx = np.maximum.accumulate(idx)
    age = np.arange(n) - idx
    out = np.where(idx >= 0, a[np.maximum(idx, 0)], np.nan)
    out_lim = np.where(age <= lim, out, np.nan)
    return out_lim, out, age


def rms(r):
    r = r[np.isfinite(r)]
    return float(np.sqrt(np.mean(r * r))) if len(r) else np.nan


def events():
    ev = pd.read_csv(EVENTS)
    ev["date"] = pd.to_datetime(ev["date"]).dt.date
    return {k: sorted(g.date) for k, g in ev.groupby("kind")}


def intraday_feats(o, h, l, c, s):
    """Features from ONE day's index bars truncated at the decision minute s (arrays already cut to s+1)."""
    assert len(c) == s + 1
    lr = np.diff(np.log(c))
    f = {}
    for w in (15, 30, 60):
        f[f"lrv{w}"] = np.log(rms(lr[-w:]) + 1e-7)
    f["lrv_day"] = np.log(rms(lr) + 1e-7)
    hh, ll = np.nanmax(h), np.nanmin(l)
    f["lrange_tod"] = np.log((hh - ll) / o[0] + 1e-5)
    f["ret_tod"] = np.log(c[-1] / o[0])
    return f


def build(und):
    os.makedirs(OUT, exist_ok=True)
    ix = D.Index(und)
    op = D.Options(und)
    vx = D.Vix()
    vmin = vx.minutes()
    ev = events()
    M = ix.mat()
    days = ix.days
    exp_days = [d for d in days if ix.d[d]["exp"]]
    exp_arr = np.array(exp_days)
    pos = {d: i for i, d in enumerate(days)}
    # daily stats (from full days - used only for LATER days)
    dstat = {}
    for i, d in enumerate(days):
        c = pd.Series(M["c"][i]).ffill().values
        if np.isnan(c).all():
            continue
        lr = np.diff(np.log(c[~np.isnan(c)]))
        dstat[d] = dict(rv=rms(lr), rng=(np.nanmax(M["h"][i]) - np.nanmin(M["l"][i])) / c[~np.isnan(c)][-1],
                        close=c[~np.isnan(c)][-1], real=ix.d[d]["real"])

    def next_trading(dt):
        k = np.searchsorted(np.array(days), dt, side="right")
        return days[k] if k < len(days) else None
    flags = {k: set() for k in ("rbi", "budget", "election", "post_cpi", "post_fomc")}
    for k in ("rbi", "budget", "election"):
        flags[k] = set(ev.get(k, []))
    for k, src in (("post_cpi", "uscpi"), ("post_fomc", "fomc")):
        flags[k] = {next_trading(x) for x in ev.get(src, [])}

    frows, prows = [], []
    lot = LOT[und]
    step = C.STEP[und]
    lim = int(os.environ.get("STRAD_MAXDAYS", "0"))
    for i, d in enumerate(days):
        if lim and i > 6 + lim:
            break
        x = ix.d[d]
        if not x["real"] or i < 6:
            continue
        prev = days[i - 1]
        if prev not in dstat:
            continue
        k = np.searchsorted(exp_arr, d)
        if k >= len(exp_arr):
            continue
        ed = exp_arr[k]
        if (ed - d).days > 40:
            continue
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        o, h, l, c = (pd.Series(M[f][i]).ffill().values for f in ("o", "h", "l", "c"))
        if np.isnan(c[:6]).any() or np.isnan(c[XEOD]):
            o, h, l, c = (pd.Series(v).bfill().values for v in (o, h, l, c))   # leading gap only
            if np.isnan(c).any():
                continue
        ntd_between = pos[ed] - pos[d] - 1 if ed > d else -1        # full trading days strictly between
        # history features (previous days only)
        pv = [dstat[days[j]]["rv"] for j in range(i - 5, i) if days[j] in dstat]
        lrv_y = np.log(dstat[prev]["rv"] + 1e-7)
        lrv_5d = np.log(np.mean(pv) + 1e-7)
        lrng_y = np.log(dstat[prev]["rng"] + 1e-5)
        pclose = dstat[prev]["close"]
        pprev = dstat.get(days[i - 2], {}).get("close", np.nan)
        ret_y = pclose / pprev - 1 if np.isfinite(pprev) else 0.0
        gap = np.log(o[0] / pclose)
        vprev = vx.prev_close(d)
        va = vmin.get(d)
        # h18 gamma at its 5-min columns
        spot_cols = c[H18.COLS]
        T = ((ed - d).days + (930 - (C.OPEN_M + H18.COLS)) / 1440.0) / 365.0
        T = np.maximum(T, 5 / 1440 / 365)
        try:
            g = H18.day_feats(ch, spot_cols, T, vprev)
        except Exception:
            g = None
        # option arrays
        Kpos = {K: j for j, K in enumerate(ch.K)}
        ivs = {r: np.apply_along_axis(lambda a: ffill_lim(a, 10**6)[1], 1, ch.iv[r]) for r in ("C", "P")}
        leg_cache = {}

        def leg(K, r):
            key = (K, r)
            if key not in leg_cache:
                j = Kpos.get(K)
                if j is None:
                    leg_cache[key] = None
                else:
                    cl, clu, age = ffill_lim(ch.c[r][j], 5)
                    op_ = ch.o[r][j]
                    # fill price at column X: the open at X, else the last close (any age) before X
                    prevc = np.concatenate([[np.nan], clu[:-1]])
                    fill = np.where(np.isfinite(op_), op_, prevc)
                    prevc5 = np.concatenate([[np.nan], cl[:-1]])
                    fill5 = np.where(np.isfinite(op_), op_, prevc5)      # entry: at most 5 minutes stale
                    leg_cache[key] = (cl, clu, fill, fill5, age)
            return leg_cache[key]

        wd = d.weekday()
        lr = np.diff(np.log(c))
        for s in GRID:
            f = intraday_feats(o[:s + 1], h[:s + 1], l[:s + 1], c[:s + 1], int(s))
            spot = c[s]
            N_rem = (930 - (C.OPEN_M + s)) + (375 * (ntd_between + 1) if ed > d else 0)
            vs = np.nan
            if va is not None:
                vv = va[:s + 1]
                vv = vv[np.isfinite(vv)]
                vs = vv[-1] if len(vv) else np.nan
            row = dict(und=und, day=d, s=int(s), spot=spot, lrv_y=lrv_y, lrv_5d=lrv_5d, lrng_y=lrng_y,
                       gap=abs(gap), sgap=gap, ret_y=ret_y, down1=float(ret_y <= -0.01), wd=wd,
                       dte=(ed - d).days, expday=float(ed == d), monthly=float(ch.series == "MONTH"),
                       bn=float(und == "BANKNIFTY"), vix_l=np.log(vprev),
                       vix_chg=np.log(vs / vprev) if np.isfinite(vs) else np.nan, N_rem=N_rem,
                       rbi=float(d in flags["rbi"]), rbi_pre=float(d in flags["rbi"] and s < 60),
                       budget=float(d in flags["budget"]), election=float(d in flags["election"]),
                       post_cpi=float(d in flags["post_cpi"]), post_fomc=float(d in flags["post_fomc"]), **f)
            if g is not None:
                gi = int(s) // 5
                row["gtot"] = g["gtot"][gi]
                row["A_share"] = g["gexA"][gi] / g["gtot"][gi] if g["gtot"][gi] > 0 else np.nan
            else:
                row["gtot"] = row["A_share"] = np.nan
            # targets
            for hn, hh in zip(HNAME, HS):
                xe = XEOD if hh == 0 else s + hh
                row[f"rv_{hn}"] = np.sqrt(np.sum(lr[s:xe] ** 2)) if xe <= XEOD else np.nan
                row[f"mv_{hn}"] = abs(np.log(c[xe] / c[s])) if xe <= XEOD else np.nan
            # straddle at s (mid closes)
            K0 = int(round(spot / step) * step)
            ce, pe = leg(K0, "C"), leg(K0, "P")
            row["K0"] = K0
            row["strad"] = (ce[0][s] + pe[0][s]) if ce is not None and pe is not None else np.nan
            jk = Kpos.get(K0)
            row["iv_atm"] = np.nanmean([ivs["C"][jk][s], ivs["P"][jk][s]]) if jk is not None else np.nan
            frows.append(row)
            # trade paths
            E = s + 1
            for st, (kc, kp) in enumerate(((K0, K0), (K0 + step, K0 - step))):
                a, b = leg(kc, "C"), leg(kp, "P")
                if a is None or b is None:
                    continue
                e_c, e_p = a[3][E], b[3][E]
                if not (np.isfinite(e_c) and np.isfinite(e_p)) or e_c <= 0 or e_p <= 0:
                    continue
                ent = e_c + e_p
                comb = a[1] + b[1]                      # combined close (any-age ffill)
                pr = dict(und=und, day=d, s=int(s), st=st, e_c=e_c, e_p=e_p,
                          stale=int(max(a[4][E:XEOD + 1].max(), b[4][E:XEOD + 1].max())))
                for hn, hh in zip(HNAME, HS):
                    xt = XEOD if hh == 0 else E + hh
                    if xt > XEOD:
                        continue
                    seg = comb[E:xt]                    # closes of E .. xt-1
                    for en, (pt, sl) in zip(ENAME, EXITS):
                        X = xt
                        if pt is not None or sl is not None:
                            hit = np.zeros(len(seg), bool)
                            if pt is not None:
                                hit |= seg >= ent * (1 + pt)
                            if sl is not None:
                                hit |= seg <= ent * (1 - sl)
                            if hit.any():
                                X = E + int(np.argmax(hit)) + 1
                        pr[f"x_{hn}_{en}"] = X
                        pr[f"c_{hn}_{en}"] = a[2][X]
                        pr[f"p_{hn}_{en}"] = b[2][X]
                prows.append(pr)
        if i % 100 == 0:
            print(und, d, len(frows), len(prows), flush=True)
    F = pd.DataFrame(frows)
    P = pd.DataFrame(prows)
    for col in P.columns:
        if P[col].dtype == np.float64:
            P[col] = P[col].astype(np.float32)
    sfx = "_test" if lim else ""
    F.to_parquet(os.path.join(OUT, f"feat_{und}{sfx}.parquet"))
    P.to_parquet(os.path.join(OUT, f"path_{und}{sfx}.parquet"))
    print(und, "done", len(F), len(P), F.day.nunique(), flush=True)


def audit(und="NIFTY", n=30, seed=0):
    """Perturbation audit: intraday features at s must not change when bars after s are scrambled."""
    ix = D.Index(und)
    M = ix.mat()
    rng = np.random.default_rng(seed)
    bad = 0
    for i in rng.choice(len(ix.days), n, replace=False):
        o, h, l, c = (pd.Series(M[f][i]).ffill().bfill().values for f in ("o", "h", "l", "c"))
        for s in GRID:
            f0 = intraday_feats(o[:s + 1], h[:s + 1], l[:s + 1], c[:s + 1], int(s))
            o2, h2, l2, c2 = (v.copy() for v in (o, h, l, c))
            for v in (o2, h2, l2, c2):
                v[s + 1:] = v[s + 1:] * rng.uniform(0.5, 1.5, len(v) - s - 1)
            f1 = intraday_feats(o2[:s + 1], h2[:s + 1], l2[:s + 1], c2[:s + 1], int(s))
            bad += sum(not np.isclose(f0[k], f1[k], equal_nan=True) for k in f0)
    print("audit: changed features =", bad)


if __name__ == "__main__":
    args = sys.argv[1:] or ["NIFTY", "BANKNIFTY"]
    if args[0] == "audit":
        audit()
    else:
        for u in args:
            build(u)
