"""R140 Amendment B (PREREG.md, commits a1da2f1 and bf02a0d): indicators, a vote, walk-forward models, high win-rate
exits and stop / lock settings, to try to raise the win rate of the 06:00-08:00 IST gold window and its GOLDM link.

Signal source: XAUUSD spot 15-minute candles completed before 06:00 IST (the underlying); GOLDM's own minute closes
(09:00-09:05) for M1 / M2. Every rule has a stop-loss and a profit lock.

    python -m research.hunt.r140_gold_0600.r140e design
    python -m research.hunt.r140_gold_0600.r140e holdout     # one read, only for a rule passing every design gate
"""
from __future__ import annotations

import json
import sys
from datetime import datetime

import numpy as np
import pandas as pd

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140c as C

E_RULES = ("E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "L1", "L2", "L3")
M_RULES = ("M1", "M2", "M3", "M4", "L4", "L5", "L6")
MIN_TRADES = {"E1": 100, "E2": 50, "E3": 50, "E4": 100, "E5": 50, "E6": 100, "E7": 50, "E8": 100,
              "L1": 50, "L2": 50, "L3": 50, "M1": 40, "M2": 40, "M3": 60, "M4": 40, "L4": 60, "L5": 60, "L6": 60}
# exits: XAUUSD in ATR15 units (stop, lock trigger, keep, target or None); GOLDM in fractions of the entry
X_BASE = (1.0, 1.0, 0.60, None)
X_HIGH = (1.0, 0.3, 0.60, 0.5)
X_EXIT = {"E1": X_BASE, "E2": X_BASE, "E3": X_BASE, "E4": X_BASE, "E5": X_BASE, "E6": X_BASE, "E7": X_HIGH,
          "E8": X_HIGH, "L1": (0.5, 0.5, 0.60, None), "L2": (1.5, 0.5, 0.50, None), "L3": (1.0, 0.5, 0.75, None)}
M_BASE = (0.0030, 0.0020, 0.60, None)
M_HIGH = (0.0030, 0.0010, 0.60, 0.0015)
M_EXIT = {"M1": M_BASE, "M2": M_BASE, "M3": M_HIGH, "M4": M_HIGH, "L4": (0.0020, 0.0010, 0.60, None),
          "L5": (0.0050, 0.0010, 0.50, None), "L6": (0.0030, 0.0010, 0.75, None)}
P_HI, P_LO = 0.58, 0.42


# ---------------------------------------------------------------------------------------------- indicators
def rsi(close: pd.Series, n: int = 14) -> pd.Series:
    d = close.diff()
    up = d.clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
    dn = (-d.clip(upper=0)).ewm(alpha=1 / n, adjust=False).mean()
    rs = up / dn.replace(0, np.nan)
    out = 100 - 100 / (1 + rs)
    return out.where(dn != 0, 100.0)


def supertrend_dir(b: pd.DataFrame, n: int = 10, mult: float = 3.0) -> pd.Series:
    """+1 up-trend / -1 down-trend (standard Supertrend on ATR n, RMA)."""
    h, l, c = b.high.values, b.low.values, b.close.values
    pc = np.concatenate([[c[0]], c[:-1]])
    tr = np.maximum(h - l, np.maximum(np.abs(h - pc), np.abs(l - pc)))
    atr = pd.Series(tr).ewm(alpha=1 / n, adjust=False).mean().values
    mid = (h + l) / 2
    ub, lb = mid + mult * atr, mid - mult * atr
    fu, fl = ub.copy(), lb.copy()
    d = np.ones(len(c), dtype=int)
    for i in range(1, len(c)):
        fu[i] = ub[i] if (ub[i] < fu[i - 1] or c[i - 1] > fu[i - 1]) else fu[i - 1]
        fl[i] = lb[i] if (lb[i] > fl[i - 1] or c[i - 1] < fl[i - 1]) else fl[i - 1]
        if d[i - 1] == 1:
            d[i] = -1 if c[i] < fl[i] else 1
        else:
            d[i] = 1 if c[i] > fu[i] else -1
    return pd.Series(d, index=b.index)


def indicators(b15: pd.DataFrame) -> pd.DataFrame:
    c = b15.close
    e20, e50 = c.ewm(span=20, adjust=False).mean(), c.ewm(span=50, adjust=False).mean()
    m = c.ewm(span=12, adjust=False).mean() - c.ewm(span=26, adjust=False).mean()
    hist = m - m.ewm(span=9, adjust=False).mean()
    mid, sd = c.rolling(20).mean(), c.rolling(20).std(ddof=0)
    pc = c.shift(1)
    tr = np.maximum(b15.high - b15.low, np.maximum((b15.high - pc).abs(), (b15.low - pc).abs()))
    return pd.DataFrame({"close": c, "ema_spread": e20 - e50, "rsi": rsi(c), "macd_hist": hist,
                         "bb_z": (c - mid) / sd, "st": supertrend_dir(b15), "atr14": tr.rolling(14).mean()})


def day_features(ind: pd.DataFrame, wt: pd.DataFrame, pr: pd.DataFrame) -> pd.DataFrame:
    """Indicator values of the last 15m candle completed by 06:00 IST (the candle that starts at 05:45 IST)."""
    rows = {}
    for d in wt.index:
        t = pd.Timestamp(d) + pd.Timedelta(minutes=R.W_START_UTC - 15)
        if t not in ind.index:
            continue
        rows[d] = ind.loc[t].to_dict()
    f = pd.DataFrame.from_dict(rows, orient="index")
    f = f.join(wt[["U", "P", "summer"]], how="left").join(pr[["C1", "C2", "C3", "C4", "C5"]], how="left")
    f["ema_n"] = f.ema_spread / f.atr14
    f["macd_n"] = f.macd_hist / f.atr14
    return f


def e_signals(f: pd.DataFrame) -> pd.DataFrame:
    s = pd.DataFrame(index=f.index)
    s["E1"] = np.sign(f.ema_spread)
    s["E2"] = np.where(f.rsi < 30, 1, np.where(f.rsi > 70, -1, 0))
    s["E3"] = np.where(f.bb_z < -2, 1, np.where(f.bb_z > 2, -1, 0))
    s["E4"] = f.st
    v = np.vstack([np.sign(f.ema_spread), np.sign(f.rsi - 50), np.sign(f.macd_hist), np.sign(f.C3)]).T
    s["E5"] = np.where((v == 1).all(axis=1), 1, np.where((v == -1).all(axis=1), -1, 0))
    return s.fillna(0)


E6_COLS = ["U", "P", "C1", "C2", "C3", "C4", "C5", "rsi_c", "ema_n", "macd_n", "bb_z", "st", "summer"]


def wf_logit(X: pd.DataFrame, y: pd.Series, train_mask_fn, predict_days) -> pd.Series:
    """P(up) for predict_days from a logistic model trained on the days train_mask_fn(day) allows."""
    from sklearn.linear_model import LogisticRegression
    from sklearn.preprocessing import StandardScaler
    out = {}
    groups = {}
    for d in predict_days:
        key = train_mask_fn(d)
        if key is None:
            continue
        groups.setdefault(key, []).append(d)
    for (cut,), days in groups.items():
        tr = X.index[(X.index < cut)]
        Xt, yt = X.loc[tr], y.loc[tr]
        ok = Xt.notna().all(axis=1) & yt.notna() & (yt != 0)
        Xt, yt = Xt[ok], yt[ok]
        if len(Xt) < 30 or yt.nunique() < 2:
            continue
        sc = StandardScaler().fit(Xt.values)
        m = LogisticRegression(C=1.0, max_iter=1000).fit(sc.transform(Xt.values), (yt > 0).astype(int).values)
        Xp = X.loc[[d for d in days if X.loc[d].notna().all()]]
        if len(Xp):
            for d, p in zip(Xp.index, m.predict_proba(sc.transform(Xp.values))[:, 1]):
                out[d] = float(p)
    return pd.Series(out, dtype=float)


def prob_to_side(p: pd.Series) -> pd.Series:
    return pd.Series(np.where(p >= P_HI, 1, np.where(p <= P_LO, -1, 0)), index=p.index)


# ---------------------------------------------------------------------------------------------- engines with targets
def sim_xau_t(X: R.Xau, i_in, i_out, side, stop, trig, keep, target):
    """R.sim_xau plus an optional profit target (a minute touching both the stop / lock and the target = the worse)."""
    e = X.o[i_in]
    peak, lock = 0.0, None
    s_lvl = e - side * stop
    for j in range(i_in, i_out):
        hi, lo, op = X.h[j], X.l[j], X.o[j]
        adv, fav = (lo, hi) if side > 0 else (hi, lo)
        lvl = lock if lock is not None else s_lvl
        if side * (adv - lvl) <= 0:
            return (min(op, lvl) if side > 0 else max(op, lvl)), ("lock" if lock is not None else "stop")
        if target is not None and side * (fav - (e + side * target)) >= 0:
            tl = e + side * target
            return (max(op, tl) if side > 0 else min(op, tl)), "target"
        peak = max(peak, side * (fav - e))
        if peak >= trig:
            new = e + side * keep * peak
            lock = new if lock is None else (max(lock, new) if side > 0 else min(lock, new))
            if side * (X.c[j] - lock) <= 0:
                return X.c[j], "lock"
    return X.o[i_out], "time"


def sim_close_t(F, i_in, i_out, side, stop_f, trig_f, keep, target_f):
    e = F[i_in]
    peak, lock = 0.0, None
    for j in range(i_in + 1, i_out + 1):
        p = F[j]
        g = side * (p - e)
        if g <= -stop_f * e:
            return p, "stop"
        if lock is not None and side * (p - lock) <= 0:
            return p, "lock"
        if target_f is not None and g >= target_f * e:
            return p, "target"
        peak = max(peak, g)
        if peak >= trig_f * e:
            new = e + side * keep * peak
            lock = new if lock is None else (max(lock, new) if side > 0 else min(lock, new))
    return F[i_out], "time"


def xau_trade_x(X, b15, d, side, cfg):
    t_in = pd.Timestamp(d) + pd.Timedelta(minutes=R.W_START_UTC)
    t_out = pd.Timestamp(d) + pd.Timedelta(minutes=R.W_END_UTC)
    i_in, i_out = X.ix_at_or_after(t_in), X.ix_at_or_after(t_out)
    if i_out >= len(X.ts) or i_out <= i_in or X.ts[i_in] - np.datetime64(t_in) > np.timedelta64(5, "m"):
        return None
    atr = R.atr15_before(b15, t_in)
    if not np.isfinite(atr) or atr <= 0:
        return None
    st, tg, kp, tgt = cfg
    px, why = sim_xau_t(X, i_in, i_out, side, st * atr, tg * atr, kp, None if tgt is None else tgt * atr)
    g = side * (px - X.o[i_in]) * R.XAU_LOT_OZ
    return dict(day=d, t_in=t_in, side=side, gross=g, net=g - R.XAU_COST, net2=g - 2 * R.XAU_COST, why=why)


def mcx_trade_x(mc, d, side, cfg, t_in=545, t_out=600):
    F = mc.F[d]
    if t_in not in F.index or t_out not in F.index:
        return None
    arr = F.values
    i0, i1 = F.index.get_loc(t_in), F.index.get_loc(t_out)
    px, why = sim_close_t(arr, i0, i1, side, *cfg)
    e = arr[i0]
    return dict(day=d, t_in=t_in, t_out=t_out, side=side, gross=side * (px - e) * R.MCX_UNITS,
                net=R.mcx_net(side, e, px), net2=R.mcx_net(side, e, px, 2.0), why=why)


# ---------------------------------------------------------------------------------------------- twins (own exits)
def twins(trades, pool_days, fn, rng):
    if not trades:
        return 1.0, 0.0
    L, S = [], []
    for d in pool_days:
        a, b = fn(d, 1), fn(d, -1)
        if a is not None and b is not None:
            L.append(a["net"])
            S.append(b["net"])
    L, S = np.array(L)[:, None], np.array(S)[:, None]
    share = float(np.mean([t["side"] > 0 for t in trades]))
    return R.twin_p(L, S, len(trades), share, float(sum(t["net"] for t in trades)), rng)


# ---------------------------------------------------------------------------------------------- runner
def build(X, b15, wt):
    pr = C.predictors(X, wt)
    ind = indicators(b15)
    f = day_features(ind, wt, pr)
    f["rsi_c"] = f.rsi - 50
    f["st"] = f.st.astype(float)
    f["summer"] = f.summer.astype(float)
    return pr, f


def fold_cut(d, period, folds, train_first):
    fk = next((i for i, x in enumerate(folds) if R.in_p(d, x)), None)
    if fk is None or (train_first and fk == 0):
        return None
    return (folds[fk][0],)


def mcx_frame(mc, X, wt, f, pr):
    rows = {}
    for i, d in enumerate(mc.days):
        if i == 0 or d not in wt.index or d not in f.index:
            continue
        F = mc.F[d]
        if not all(t in F.index for t in (545, 600)):
            continue
        pd_ = mc.days[i - 1]
        has0900 = 540 in F.index                     # 13 of 152 design sessions open at 09:01-09:02
        gap = F[540] / mc.last[pd_][1] - 1 if (has0900 and mc.seg[pd_] == mc.seg[d]) else np.nan
        first5 = F[545] / F[540] - 1 if has0900 else np.nan
        rows[d] = dict(W=wt.at[d, "W"], gap=gap, first5=first5, U=wt.at[d, "U"], P=wt.at[d, "P"],
                       C3=pr.at[d, "C3"], C5=pr.at[d, "C5"], rsi_c=f.at[d, "rsi_c"], ema_n=f.at[d, "ema_n"],
                       y=F[600] / F[545] - 1)
    return pd.DataFrame.from_dict(rows, orient="index")


def run(period_x, period_m, folds_x, folds_m, holdout=False):
    X, b15, wt = R._load()
    mc = R._load_mcx()
    pr, f = build(X, b15, wt)
    sig = e_signals(f)
    design_x = [d for d in f.index if R.in_p(d, R.XAU_DESIGN)]
    y = (wt.W).reindex(f.index)
    days_x = [d for d in f.index if R.in_p(d, period_x)]
    if holdout:
        cut_x = lambda d: (period_x[0],)
    else:
        cut_x = lambda d: fold_cut(d, period_x, folds_x, True)
    Xe = f[E6_COLS].loc[design_x + [d for d in days_x if d not in design_x]]
    p6 = wf_logit(Xe, y.loc[Xe.index], cut_x, days_x)
    sig["E6"] = prob_to_side(p6).reindex(sig.index).fillna(0)
    trades = {k: [] for k in E_RULES + M_RULES}
    src = {"E7": "E5", "E8": "E6", "L1": "E5", "L2": "E5", "L3": "E5"}
    for d in days_x:
        for rule in E_RULES:
            s = int(sig.at[d, src.get(rule, rule)])
            if rule in ("E6", "E8") and cut_x(d) is None:
                continue
            if s == 0:
                continue
            t = xau_trade_x(X, b15, d, s, X_EXIT[rule])
            if t:
                trades[rule].append(t)
    # MCX
    mf = mcx_frame(mc, X, wt, f, pr)
    days_m = [d for d in mf.index if R.in_p(d, period_m)]
    cut_m = (lambda d: (period_m[0],)) if holdout else (lambda d: fold_cut(d, period_m, folds_m, True))
    m_cols = ["W", "gap", "first5", "U", "P", "C3", "C5", "rsi_c", "ema_n"]
    mdesign = [d for d in mf.index if R.in_p(d, R.MCX_DESIGN)]
    Xm = mf[m_cols].loc[mdesign + [d for d in days_m if d not in mdesign]]
    p2 = wf_logit(Xm, mf.y.loc[Xm.index], cut_m, days_m)
    s2 = prob_to_side(p2)
    for d in days_m:
        w = int(np.sign(mf.at[d, "W"]))
        if w == 0:
            continue
        f5 = mf.at[d, "first5"]
        m1 = w if (np.isfinite(f5) and np.sign(f.at[d, "ema_spread"]) == w and np.sign(f5) == w) else 0
        plan = {"M1": m1, "M2": int(s2.get(d, 0)), "M3": w, "M4": m1, "L4": w, "L5": w, "L6": w}
        for rule, side in plan.items():
            if side == 0:
                continue
            t = mcx_trade_x(mc, d, side, M_EXIT[rule])
            if t:
                trades[rule].append(t)
    return trades, (X, b15, wt, mc, days_x, days_m)


def evaluate(trades, ctx, folds_x, folds_m, prior_p):
    X, b15, wt, mc, days_x, days_m = ctx
    rng = np.random.default_rng(R.SEED + 2)
    res, p = {}, {}
    for rule, ts in trades.items():
        is_m = rule in M_RULES
        s = R.summarise(rule, ts, folds_m if is_m else folds_x, is_m)
        if is_m:
            fn = lambda d, side, rule=rule: mcx_trade_x(mc, d, side, M_EXIT[rule])
            s["twin_p"], s["twin_median_total"] = twins(ts, days_m, fn, rng)
        else:
            fn = lambda d, side, rule=rule: xau_trade_x(X, b15, d, side, X_EXIT[rule])
            s["twin_p"], s["twin_median_total"] = twins(ts, days_x, fn, rng)
        s["targets"] = sum(t["why"] == "target" for t in ts)
        p[rule] = s["twin_p"]
        res[rule] = s
    q = R.bh({**prior_p, **p})
    for rule, s in res.items():
        s["q"] = q[rule]
        traded = sum(x is not None for x in s["folds"])
        g = R.gates(s, s["twin_p"], q[rule], "B1" if rule in M_RULES else ("A4" if traded == 5 else "A2"))
        g["trades"] = s["n"] >= MIN_TRADES[rule]
        g["all"] = all(v for k, v in g.items() if k != "all")
        s["gates"] = g
    return res


def main(mode):
    if mode == "design":
        prior = json.loads((R.RESULTS / "design.json").read_text())["variants"]
        pa = json.loads((R.RESULTS / "amendA_design.json").read_text())["variants"]
        prior_p = {**{k: v["twin_p"] for k, v in prior.items()}, **{k: v["twin_p"] for k, v in pa.items()}}
        trades, ctx = run(R.XAU_DESIGN, R.MCX_DESIGN, R.XAU_FOLDS, R.MCX_FOLDS)
        res = evaluate(trades, ctx, R.XAU_FOLDS, R.MCX_FOLDS, prior_p)
        (R.RESULTS / "amendB_design.json").write_text(json.dumps(R._clean(res), indent=1))
        md = R.table_md(res, is_rs=lambda r: r in M_RULES)
        (R.RESULTS / "amendB_design.md").write_text("# R140 Amendment B design results\n\n" + md + "\n")
        print(md)
    elif mode == "holdout":
        lock = R.RESULTS / "amendB_holdout.lock"
        if lock.exists():
            raise SystemExit("Amendment B holdout already read: " + lock.read_text())
        dz = json.loads((R.RESULTS / "amendB_design.json").read_text())
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment B rule passed the design gates; the holdout is not read")
            return
        trades, ctx = run(R.XAU_HOLD, R.MCX_HOLD, (R.XAU_HOLD,), (R.MCX_HOLD,), holdout=True)
        res = {k: R.summarise(k, trades[k], (R.MCX_HOLD,) if k in M_RULES else (R.XAU_HOLD,), k in M_RULES)
               for k in passing}
        (R.RESULTS / "amendB_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(json.dumps(R._clean(res), indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
