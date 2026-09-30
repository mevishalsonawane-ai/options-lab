"""Liquidity 15+5 on SENSEX, plus a small set of enhancements, with a Year A / Year B hold-out check.

    python research/liquidity_sensex_plus.py <sensex_index.parquet> <banknifty_prev_year_wide.parquet> \
        <banknifty_year_wide.parquet> <out.md> [scratch dir]

SENSEX has INDEX MINUTES ONLY (no option price history), so every rupee figure for SENSEX is an ESTIMATE from a simple
option model calibrated ONCE on BANKNIFTY real ATM option trades taken with the same rules:

    premium change (pts)  =  C + DELTA x index pts in our favour  -  THETA x minutes held      (OLS, BANKNIFTY)
    SENSEX theta, C       =  BANKNIFTY value x (SENSEX median daily range in pts / BANKNIFTY median daily range in pts)
    SENSEX typical ATM    =  BANKNIFTY median ATM entry premium x the same range ratio
    Rs per trade (1 lot)  =  (premium change - 2 x 0.5 slippage) x 20 - Rs 40
C (a positive per-trade term: the real option fills beat delta x points, mostly on quick trades) makes the model
unbiased on the BANKNIFTY trades. A stricter model without C (refitted delta/theta, C = 0) is reported as a
conservative bound ("strict").

The 15% premium stop cannot be simulated exactly without option prices: it is approximated by the modelled premium
(entry + DELTA x adverse index move - theta so far) touching 15% of the TYPICAL ATM premium below the price paid.
The BANKNIFTY files are used to check how far the model is from the real option P&L (same rules, real prices).

Strategy (copied from research/liquidity_break.py and extended here; the baseline file is not modified): a pool
broken by a close where an active swing zone of the same side overlaps ("both"), swing lookback 20, pool 2 / 5 / 10,
stop on a failed break, entries 09:20-14:30, exit at next liquidity / failed break / new liquidity / premium stop /
15:10. 15-minute and 5-minute books side by side (one position each). Buy ATM CE on an upside break, PE on a downside.
"""
from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, replace

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from liquidity_indices import load_any  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

LOT, STEP, SLIP, CHG, CUT = 20, 100, 0.5, 40.0, 355
BN_LOT = 30
SPLIT = pd.Timestamp("2025-02-15").date()


# ---------------------------------------------------------------- data
def sensex_expiry(day):
    """SENSEX weekly expiry weekday: Friday until 2024-12-31, Tuesday 2025-01-01 .. 2025-08-31, Thursday after."""
    if day < pd.Timestamp("2025-01-01").date():
        return 4
    if day < pd.Timestamp("2025-09-01").date():
        return 1
    return 3


def add_features(days, index="SENSEX"):
    """Per-day extras: TWAP of the typical price (no index volume), first-hour range, expiry-day flag."""
    tdays = [d["day"] for d in days]
    for i, d in enumerate(days):
        I = d["I"]
        tp = (I["high"] + I["low"] + I["close"]) / 3
        d["vwap"] = np.cumsum(tp) / np.arange(1, len(tp) + 1)
        d["fh"] = I["high"][:60].max() - I["low"][:60].min()
        d["rng"] = I["high"].max() - I["low"].min()
        if index == "SENSEX":
            wd = sensex_expiry(d["day"])
            # the expiry is the last trading day of the week on or before the expiry weekday
            wk = [x for x in tdays if x.isocalendar()[:2] == d["day"].isocalendar()[:2] and x.weekday() <= wd]
            d["is_exp"] = bool(wk) and d["day"] == max(wk)
        else:
            d["is_exp"] = d["exp"] == d["day"]
    fh = np.array([d["fh"] for d in days])
    for i, d in enumerate(days):
        prev = fh[max(0, i - 20):i]
        d["fh_ref"] = np.median(prev) if len(prev) >= 5 else np.nan
    return days


def bars(days, tf):
    rows = []
    for di, d in enumerate(days):
        I = d["I"]
        for s in range(0, 375, tf):
            e = min(s + tf, 375)
            rows.append((di, s, e, I["open"][s], I["high"][s:e].max(), I["low"][s:e].min(), I["close"][e - 1]))
    return pd.DataFrame(rows, columns=["di", "s", "e", "open", "high", "low", "close"])


class Book:
    """Bars + zones for one chart timeframe and indicator settings (built once, reused by every variant)."""

    def __init__(self, days, tf, swing=20, confirm=10):
        self.tf = tf
        self.b = b = bars(days, tf)
        self.zones = zones = swing_zones(b, swing, "full") + pool_zones(b, 2, 5, 10 if confirm is None else confirm)
        self.swings = [z for z in zones if z.kind == "swing"]
        self.breaks, self.known = {}, {}
        for z in zones:
            self.known.setdefault(z.known, []).append(z)
            if z.broken >= 0:
                self.breaks.setdefault(z.broken, []).append(z)
        self.ema = pd.Series(b.close.values).ewm(span=20, adjust=False).mean().values


# ---------------------------------------------------------------- settings
@dataclass(frozen=True)
class Cfg:
    tfs: tuple = (15, 5)
    swing: int = 20
    confirm: int = 10
    stop: bool = True            # failed-break stop
    prem: float | None = 0.15    # premium stop (fraction of the premium paid; modelled for SENSEX)
    win: tuple = (5, 315)        # entry minutes (09:20 .. 14:30)
    trend: str | None = None     # "vwap" (index on the right side of the day's TWAP) / "ema" (20-bar EMA slope)
    vol: float | None = None     # trade only when the first-hour range >= vol x trailing 20-day median (from 10:15)
    noexp: bool = False          # skip expiry days
    maxpd: int | None = None     # max entries per book per day
    lock: tuple | None = None    # (trigger, keep): once the premium is up trigger x P, exit if it falls back to keep x P


# ---------------------------------------------------------------- simulation
def simulate(days, bk, cfg, model, real=False):
    """One book. model = dict(delta, theta, P) for the modelled premium; real=True uses the chain's minute prices
    (BANKNIFTY) instead. Returns trades with index pts and the premium change per unit (before slippage)."""
    b = bk.b
    DI, S, E, C = b.di.values, b.s.values, b.e.values, b.close.values
    dl, th, P = model["delta"], model["theta"], model["P"]
    trades, pos = [], None
    nday = {}
    for i in range(len(b)):
        d = days[DI[i]]
        I = d["I"]
        if pos is not None:
            sg = pos["sign"]
            why, xm, xopt = None, None, None
            m0 = max(S[i], pos["m"]) if DI[i] == pos["di"] else S[i]
            for m in range(m0, E[i]):
                if m >= CUT:
                    why, xm = "15:10", m
                    break
                # premium path this minute: worst and best
                if real:
                    ch = d["chain"][pos["key"]]
                    worst, best = ch["low"][m] - pos["px"], ch["high"][m] - pos["px"]
                else:
                    adv = I["low"][m] if sg > 0 else I["high"][m]
                    fav = I["high"][m] if sg > 0 else I["low"][m]
                    worst = dl * sg * (adv - pos["ix"]) - th * (m - pos["m"])
                    best = dl * sg * (fav - pos["ix"]) - th * (m - pos["m"])
                # real: premium measured from the minute's open, the stop relative to the price paid (open + slip),
                # exactly as liquidity_break.simulate; modelled: relative to the typical premium P
                base, off = (pos["px"] + SLIP, SLIP) if real else (P, 0.0)
                floor = -cfg.prem * base + off if cfg.prem else -np.inf
                if cfg.lock and pos["peak"] >= cfg.lock[0] * base:
                    floor = max(floor, cfg.lock[1] * base + off)
                if worst <= floor:
                    why, xm = ("premium stop" if floor < 0 else "profit lock"), m
                    xopt = floor
                    break
                pos["peak"] = max(pos["peak"], best)
                if pos["target"] is not None and ((sg > 0 and I["high"][m] >= pos["target"]) or
                                                  (sg < 0 and I["low"][m] <= pos["target"])):
                    why, xm = "next liquidity", m
                    break
            if why is None and cfg.stop and sg * (C[i] - pos["level"]) < 0:
                why, xm = "failed break", E[i] - 1
            if why is None and any(z.side == sg and z.known == i for z in bk.known.get(i, [])):
                why, xm = "new liquidity", E[i] - 1
            if why is None and (i + 1 >= len(b) or DI[i + 1] != DI[i]):
                why, xm = "15:10", min(E[i] - 1, 374)
            if why:
                ix = I["close"][xm] if why != "next liquidity" else pos["target"]
                if real:
                    opt = d["chain"][pos["key"]]["close"][xm] - pos["px"]
                else:
                    opt = dl * sg * (ix - pos["ix"]) - th * (xm - pos["m"])
                if xopt is not None:
                    opt = min(opt, xopt)     # the resting stop's level (or worse if it gapped)
                strict = opt
                if not real:
                    strict = model["dl0"] * sg * (ix - pos["ix"]) - model["th0"] * (xm - pos["m"])
                    if xopt is not None:
                        strict = min(strict, xopt)
                    opt += model["c"]        # fill term, on the final P&L only (not on the stop path)
                trades.append(dict(day=pos["day"], tf=bk.tf, sign=sg, why=why, pts=sg * (ix - pos["ix"]),
                                   opt=opt, strict=strict, held=xm - pos["m"], px=pos["px"] if real else P))
                pos = None
        if pos is not None or i + 1 >= len(b) or DI[i + 1] != DI[i]:
            continue
        m = S[i + 1]
        if not (cfg.win[0] <= m <= cfg.win[1]):
            continue
        brk = bk.breaks.get(i, [])
        pl = [z for z in brk if z.kind == "pool"]
        cand = [p for p in pl if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and
                                     s.known <= i and (s.broken < 0 or s.broken >= i) for s in bk.swings)]
        if not cand:
            continue
        z = cand[0]
        sg = z.side
        ix = I["open"][m]
        # filters (all use information known at the entry minute)
        if cfg.noexp and d["is_exp"]:
            continue
        if cfg.maxpd and nday.get(DI[i], 0) >= cfg.maxpd:
            continue
        if cfg.trend == "vwap" and sg * (ix - d["vwap"][m - 1]) <= 0:
            continue
        if cfg.trend == "ema" and (i < 3 or sg * (bk.ema[i] - bk.ema[i - 3]) <= 0):
            continue
        if cfg.vol is not None and (m < 60 or not (d["fh"] >= cfg.vol * d["fh_ref"])):
            continue
        ahead = [q.edge for q in bk.zones if q.side == sg and q.known <= i and (q.broken < 0 or q.broken > i)
                 and sg * (q.edge - ix) > 0]
        target = (min(ahead) if sg > 0 else max(ahead)) if ahead else None
        key, px = None, np.nan
        if real:
            right = "CE" if sg > 0 else "PE"
            ks = np.array(sorted({k for k, r in d["chain"] if r == right}))
            if not len(ks):
                continue
            k = ks[np.argmin(np.abs(ks - ix))]
            key, px = (k, right), d["chain"][(k, right)]["open"][m]
        nday[DI[i]] = nday.get(DI[i], 0) + 1
        pos = dict(day=d["day"], di=DI[i], sign=sg, m=m, ix=ix, level=z.edge, target=target, key=key, px=px,
                   peak=0.0)
    return pd.DataFrame(trades)


def run(days, books, cfg, model, real=False, lot=LOT):
    parts = [simulate(days, books[(tf, cfg.swing, cfg.confirm)], cfg, model, real) for tf in cfg.tfs]
    parts = [p for p in parts if not p.empty]
    if not parts:
        return pd.DataFrame(columns=["day", "tf", "sign", "why", "pts", "opt", "strict", "held", "px", "rs", "rs_strict"])
    tr = pd.concat(parts, ignore_index=True)
    tr["rs"] = (tr.opt - 2 * SLIP) * lot - CHG
    tr["rs_strict"] = (tr.strict - 2 * SLIP) * lot - CHG
    return tr


def stats(tr, ndays):
    if tr.empty:
        return dict(n=0, perday=0, win=np.nan, pts=np.nan, tpts=np.nan, rs=np.nan, t=np.nan, net=0, green="0/0", dd=0,
                    strict=0)
    x = tr.rs
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else np.nan
    tp = tr.pts.mean() / (tr.pts.std(ddof=1) / np.sqrt(len(tr))) if len(tr) > 2 else np.nan
    mo = tr.assign(mo=[str(d)[:7] for d in tr.day]).groupby("mo").rs.sum()
    daily = tr.groupby("day").rs.sum().sort_index().cumsum()
    dd = (daily - np.maximum.accumulate(np.maximum(daily, 0))).min()
    return dict(n=len(tr), perday=len(tr) / ndays, win=100 * (x > 0).mean(), pts=tr.pts.mean(), tpts=tp,
                rs=x.mean(), t=t, net=x.sum(), green=f"{(mo > 0).sum()}/{len(mo)}", dd=dd, strict=tr.rs_strict.sum())


def fmt(label, s):
    if not s["n"]:
        return f"| {label} | 0 | | | | | | | | |"
    return (f"| {label} | {s['n']} ({s['perday']:.1f}/day) | {s['win']:.0f}% | {s['pts']:+.1f} (t {s['tpts']:.2f}) | "
            f"{s['rs']:+,.0f} | {s['t']:.2f} | {s['net']:+,.0f} | {s['green']} | {s['dd']:,.0f} | {s['strict']:+,.0f} |")


HDR = ["| variant | trades | win (est.) | index pts/trade (t) | est. Rs/trade | t (est. Rs) | est. net Rs / lot / yr | green months (est.) | est. max DD | strict model net (C = 0) |",
       "|---|---|---|---|---|---|---|---|---|---|"]


# ---------------------------------------------------------------- calibration on BANKNIFTY real options
def calibrate(bn_files, cache):
    if cache and os.path.exists(cache):
        return json.load(open(cache))
    from sell_levels import load
    days = []
    for f in bn_files:
        days += load(f)
    days.sort(key=lambda d: d["day"])
    add_features(days, "BN")
    out = dict(years={})
    allt = []
    for yname, ys in (("A", [d for d in days if d["day"] < SPLIT]), ("B", [d for d in days if d["day"] >= SPLIT])):
        books = {(tf, 20, 10): Book(ys, tf) for tf in (15, 5)}
        dummy = dict(delta=0.5, theta=0.0, P=1.0, c=0.0, dl0=0.5, th0=0.0)
        base = Cfg(prem=None)
        tr = run(ys, books, base, dummy, real=True, lot=BN_LOT)
        tr = tr[np.isfinite(tr.opt)]
        dte = {d["day"]: (d["exp"] - d["day"]).days for d in ys}
        tr["dte"] = tr.day.map(dte)
        tr["year"] = yname
        allt.append(tr)
        out["years"][yname] = dict(days=len(ys), range=float(np.median([d["rng"] for d in ys])))
        # keep the days/books for the model-vs-real check below
        out.setdefault("_keep", {})[yname] = (ys, books)
    tr = pd.concat(allt, ignore_index=True)
    wk = tr[tr.dte <= 7]                  # weekly-contract regime (SENSEX has weekly options throughout)
    X = np.c_[wk.pts.values, -wk.held.values, np.ones(len(wk))]
    coef, *_ = np.linalg.lstsq(X, wk.opt.values, rcond=None)
    pred = X @ coef
    r2 = 1 - ((wk.opt - pred) ** 2).sum() / ((wk.opt - wk.opt.mean()) ** 2).sum()
    c0, *_ = np.linalg.lstsq(X[:, :2], wk.opt.values, rcond=None)
    Xa = np.c_[tr.pts.values, -tr.held.values, np.ones(len(tr))]
    ca, *_ = np.linalg.lstsq(Xa, tr.opt.values, rcond=None)
    q = pd.cut(wk.held, [-1, 5, 30, 400])
    out.update(delta=float(coef[0]), theta=float(coef[1]), c=float(coef[2]), r2=float(r2), n=int(len(wk)),
               delta0=float(c0[0]), theta0=float(c0[1]), n_all=int(len(tr)),
               resid_by_hold={str(k): float(v) for k, v in (wk.opt - X[:, :2] @ coef[:2]).groupby(q).mean().items()},
               delta_all=float(ca[0]), theta_all=float(ca[1]), c_all=float(ca[2]), P_bn=float(wk.px.median()),
               P_bn_all=float(tr.px.median()), bn_range=float(np.median([d["rng"] for ys, _ in out["_keep"].values()
                                                                          for d in ys])))
    # model vs real on BANKNIFTY, per year: baseline without a premium stop, and with the 15% stop (real vs modelled)
    chk = {}
    for yname, (ys, books) in out.pop("_keep").items():
        mdl = dict(delta=out["delta"], theta=out["theta"], P=out["P_bn"], c=out["c"], dl0=out["delta0"],
                   th0=out["theta0"])
        r0 = run(ys, books, Cfg(prem=None), mdl, real=True, lot=BN_LOT)
        m0 = run(ys, books, Cfg(prem=None), mdl, real=False, lot=BN_LOT)
        r1 = run(ys, books, Cfg(), mdl, real=True, lot=BN_LOT)
        m1 = run(ys, books, Cfg(), mdl, real=False, lot=BN_LOT)
        chk[yname] = dict(real_nostop=float(r0.rs.sum()), model_nostop=float(m0.rs.sum()),
                          strict_nostop=float(m0.rs_strict.sum()), strict_15=float(m1.rs_strict.sum()),
                          real_15=float(r1.rs.sum()), model_15=float(m1.rs.sum()), n=int(len(r0)),
                          stops_real=float((r1.why == "premium stop").mean()),
                          stops_model=float((m1.why == "premium stop").mean()),
                          pts=float(r0.pts.mean()))
    out["check"] = chk
    if cache:
        json.dump(out, open(cache, "w"), indent=1, default=float)
    return out


# ---------------------------------------------------------------- variants
BASE = Cfg()
VARIANTS = {
    # chart timeframes (books)
    "books 15 only": dict(tfs=(15,)),
    "books 5 only": dict(tfs=(5,)),
    "books 30+15": dict(tfs=(30, 15)),
    "books 10+5": dict(tfs=(10, 5)),
    "books 15+3": dict(tfs=(15, 3)),
    # indicator settings
    "swing lookback 10": dict(swing=10),
    "swing lookback 15": dict(swing=15),
    "swing lookback 30": dict(swing=30),
    "pool confirm 5": dict(confirm=5),
    "pool confirm 15": dict(confirm=15),
    # exits
    "no failed-break stop": dict(stop=False),
    "premium stop none (modelled)": dict(prem=None),
    "premium stop 10% (modelled)": dict(prem=0.10),
    "premium stop 20% (modelled)": dict(prem=0.20),
    "premium stop 25% (modelled)": dict(prem=0.25),
    "profit lock: +20% -> exit at cost": dict(lock=(0.20, 0.0)),
    "profit lock: +30% -> keep +10%": dict(lock=(0.30, 0.10)),
    # entry window
    "entries 09:30-14:30": dict(win=(15, 315)),
    "entries 09:20-13:30": dict(win=(5, 255)),
    "entries 10:15-14:30": dict(win=(60, 315)),
    # filters
    "trend: with the day's TWAP side": dict(trend="vwap"),
    "trend: 20-bar EMA slope": dict(trend="ema"),
    "vol: 1st-hour range >= 0.8x 20d median (from 10:15)": dict(vol=0.8),
    "vol: 1st-hour range >= 1.0x 20d median (from 10:15)": dict(vol=1.0),
    "skip expiry day": dict(noexp=True),
    "max 2 entries / book / day": dict(maxpd=2),
}
GROUP = {k: k.split(":")[0].split(" ")[0] for k in VARIANTS}
GROUP.update({k: "prem" for k in VARIANTS if k.startswith("premium")})
GROUP.update({k: "lock" for k in VARIANTS if k.startswith("profit")})
GROUP.update({k: "swing" for k in VARIANTS if k.startswith("swing")})
GROUP.update({k: "pool" for k in VARIANTS if k.startswith("pool")})


def main():
    sx_path, bnA, bnB, out = sys.argv[1:5]
    scratch = sys.argv[5] if len(sys.argv) > 5 else None
    cal = calibrate([bnA, bnB], os.path.join(scratch, "sensex_plus_cal.json") if scratch else None)
    print(json.dumps({k: v for k, v in cal.items() if k != "years"}, indent=1, default=float), flush=True)

    days = load_any(sx_path)
    days.sort(key=lambda d: d["day"])
    add_features(days, "SENSEX")
    sx_range = float(np.median([d["rng"] for d in days]))
    ratio = sx_range / cal["bn_range"]
    model = dict(delta=cal["delta"], theta=cal["theta"] * ratio, P=cal["P_bn"] * ratio, c=cal["c"] * ratio,
                 dl0=cal["delta0"], th0=cal["theta0"] * ratio)
    print("SENSEX model", model, "range ratio", ratio, flush=True)

    years = {"A": [d for d in days if d["day"] < SPLIT], "B": [d for d in days if d["day"] >= SPLIT]}
    need = {(tf, 20, 10) for tf in (3, 5, 10, 15, 30)}
    need |= {(tf, s, 10) for tf in (15, 5) for s in (10, 15, 30)} | {(tf, 20, c) for tf in (15, 5) for c in (5, 15)}
    books = {}
    for y, ys in years.items():
        for key in sorted(need):
            books[(y,) + key] = Book(ys, *key)
        print("books", y, flush=True)

    def res(y, cfg):
        bk = {k[1:]: v for k, v in books.items() if k[0] == y}
        for key in {(tf, cfg.swing, cfg.confirm) for tf in cfg.tfs} - set(bk):
            books[(y,) + key] = bk[key] = Book(years[y], *key)
        return run(years[y], bk, cfg, model)

    R = {}
    ntried = 0

    def ev(name, cfg):
        nonlocal ntried
        ntried += 1
        R[name] = {y: res(y, cfg) for y in years}
        R[name]["cfg"] = cfg
        s = {y: stats(R[name][y], len(years[y])) for y in years}
        R[name]["s"] = s
        print(f"{name:55s} A {s['A']['n']:4d} {s['A']['pts']:+6.1f} {s['A']['net']:+9,.0f}  "
              f"B {s['B']['n']:4d} {s['B']['pts']:+6.1f} {s['B']['net']:+9,.0f}", flush=True)

    ev("BASELINE (arm rule, modelled 15% stop)", BASE)
    for name, kw in VARIANTS.items():
        ev(name, replace(BASE, **kw))

    # greedy combination chosen on one year only, then tested on the other
    def combine(fit):
        base_net = R["BASELINE (arm rule, modelled 15% stop)"]["s"][fit]["net"]
        good = sorted([(R[k]["s"][fit]["net"], k) for k in VARIANTS if R[k]["s"][fit]["net"] > base_net
                       and R[k]["s"][fit]["n"] >= 100], reverse=True)
        cfg, cur, used, picked = BASE, base_net, set(), []
        for _, k in good[:4]:
            if GROUP[k] in used:
                continue
            trial = replace(cfg, **VARIANTS[k])
            tr = res(fit, trial)
            if len(tr) >= 100 and tr.rs.sum() > cur:
                cfg, cur = trial, tr.rs.sum()
                used.add(GROUP[k])
                picked.append(k)
        return cfg, picked

    combos = {}
    for fit in ("A", "B"):
        cfg, picked = combine(fit)
        name = f"COMBO chosen on Year {fit}: " + (" + ".join(picked) if picked else "(nothing improved)")
        if picked:
            ev(name, cfg)
        combos[fit] = (name, picked)

    # selection: best single variant or combo by estimated net Rs on the fitting year (>= 100 trades)
    names = [k for k in R]
    sel = {}
    for fit, hold in (("A", "B"), ("B", "A")):
        ok = [k for k in names if R[k]["s"][fit]["n"] >= 100]
        best = max(ok, key=lambda k: R[k]["s"][fit]["net"])
        sel[fit] = best
    bl = R["BASELINE (arm rule, modelled 15% stop)"]["s"]

    def recommended(k):
        s = R[k]["s"]
        return s["A"]["net"] > 0 and s["B"]["net"] > 0

    # ------------------------------------------------ report
    L = ["# Liquidity 15+5 on SENSEX: enhancements with a Year A / Year B hold-out (research/liquidity_sensex_plus.py)", "",
         "**Every rupee figure for SENSEX below is an ESTIMATE.** There is no SENSEX option price history: the "
         "simulation records SENSEX index points only and the option P&L comes from a model calibrated on BANKNIFTY "
         "real ATM option trades. 1 lot = 20, ATM strike (100 apart), 0.5 slippage a side, Rs 40 a round trip.", "",
         f"Year A = {years['A'][0]['day']} .. {years['A'][-1]['day']} ({len(years['A'])} days); "
         f"Year B = {years['B'][0]['day']} .. {years['B'][-1]['day']} ({len(years['B'])} days).", "",
         "## 1. Option model (calibrated once, on BANKNIFTY)", "",
         "Same rules (both tools, lookback 20, confirm 10, failed-break stop, 15+5 books, no premium stop) run on the "
         "BANKNIFTY files with real ATM option minute prices (lot 30, strike step 100). Least squares "
         f"on the {cal['n']} trades taken in a weekly-contract regime (<= 7 days to expiry; SENSEX has weekly options "
         f"throughout) out of {cal['n_all']}:", "",
         f"- premium change (pts) = **{cal['c']:+.2f}** + **{cal['delta']:.3f}** x index pts in our favour - "
         f"**{cal['theta']:.3f}** x minutes held (R^2 {cal['r2']:.2f}); on all {cal['n_all']} trades (incl. monthly "
         f"contracts): {cal['c_all']:+.2f} / {cal['delta_all']:.3f} / {cal['theta_all']:.3f}.",
         f"- The constant C: real fills beat delta x points by, on average, " + ", ".join(
             f"{v:+.1f} pts for holds {k}" for k, v in cal["resid_by_hold"].items()) + " minutes - mostly on the quick "
         "trades (option minute prices lag the index at a break). It may not carry over to SENSEX options (thinner, "
         f"wider spreads), so a **strict** model with C = 0 is also shown (refitted: delta {cal['delta0']:.3f}, theta "
         f"{cal['theta0']:.3f} pts/min on BANKNIFTY).",
         f"- BANKNIFTY median ATM premium paid (weekly regime) {cal['P_bn']:.0f}; median daily range {cal['bn_range']:.0f} pts.",
         f"- SENSEX median daily range {sx_range:.0f} pts -> scale factor {ratio:.2f}: SENSEX theta **{model['theta']:.3f} "
         f"pts/min**, C **{model['c']:+.2f}** pts (strict: theta {model['th0']:.3f}), typical ATM premium **{model['P']:.0f}** pts (so the 15% stop = {0.15 * model['P']:.0f} premium "
         f"pts, about {0.15 * model['P'] / model['delta']:.0f} index pts against us before theta).",
         f"- Break-even per trade: (Rs 40 / 20 + 2 x 0.5 - C) / delta = "
         f"**{(CHG / LOT + 2 * SLIP - model['c']) / model['delta']:.1f} index pts** in our favour plus theta / delta for "
         f"the time held (strict model: {(CHG / LOT + 2 * SLIP) / model['dl0']:.1f} pts plus theta).",
         "- Premium stop: approximated, NOT simulated: the modelled premium (delta x adverse index move on the minute's "
         "low/high - theta so far, C not included) touching X% of the typical premium below the price paid. Profit "
         "locks use the same model.",
         "", "Model vs real on BANKNIFTY (same trades, the model's own calibration data, so an optimistic check; year B "
         "is mostly monthly contracts after Nov 2024, where the weekly theta overstates the decay):", "",
         "| BANKNIFTY year | trades | index pts/trade | real Rs, no prem. stop | model Rs (strict), no prem. stop | real Rs, 15% stop | modelled Rs (strict), modelled 15% stop | stop hits real / modelled |",
         "|---|---|---|---|---|---|---|---|"]
    for y, c in cal["check"].items():
        L.append(f"| {y} | {c['n']} | {c['pts']:+.1f} | {c['real_nostop']:+,.0f} | {c['model_nostop']:+,.0f} ({c['strict_nostop']:+,.0f}) | "
                 f"{c['real_15']:+,.0f} | {c['model_15']:+,.0f} ({c['strict_15']:+,.0f}) | {100 * c['stops_real']:.0f}% / {100 * c['stops_model']:.0f}% |")
    base0 = Cfg(prem=None)
    b0 = {y: stats(res(y, base0), len(years[y])) for y in years}
    L += ["", "## 2. Baseline reproduced", "",
          f"Index points, arm rule without a premium stop (as in LIQUIDITY_INDICES.md): Year A {b0['A']['pts']:+.1f} pts a trade "
          f"({b0['A']['n']} trades), Year B {b0['B']['pts']:+.1f} ({b0['B']['n']} trades). "
          f"Estimated option P&L without the stop: Year A Rs {b0['A']['net']:+,.0f}, Year B Rs {b0['B']['net']:+,.0f} (ESTIMATE).", ""]
    for y in years:
        L.append(f"### Year {y}")
        L += [""] + HDR
        L.append(fmt("baseline, no premium stop", b0[y]))
        for tf in (15, 5):
            L.append(fmt(f"baseline (15% modelled stop), {tf}-min book only",
                         stats(R["BASELINE (arm rule, modelled 15% stop)"][y].query("tf == @tf"), len(years[y]))))
        L.append(fmt("**baseline: 15+5, 15% modelled stop**", bl[y]))
        tr = R["BASELINE (arm rule, modelled 15% stop)"][y]
        L += ["", "Exits: " + ", ".join(f"{k} {100 * v:.0f}%" for k, v in tr.why.value_counts(normalize=True).items())
              + f"; median hold {tr.held.median():.0f} min; mean hold {tr.held.mean():.0f} min "
              f"(theta ~ {model['theta'] * tr.held.mean():.1f} premium pts a trade).", ""]
    L += ["## 3. Variants (one change at a time from the baseline, then a greedy combination)", "",
          f"Variants evaluated: **{ntried}** (1 baseline + {len(VARIANTS)} single changes + "
          f"{sum(1 for f in combos if combos[f][1])} combinations). Each is run on both years; the choice is made on "
          "one year only.", ""]
    for y in years:
        L += [f"### Year {y} (all rupee figures ESTIMATES)", ""] + HDR
        for k in names:
            lab = f"**{k}**" if k.startswith("BASELINE") or k.startswith("COMBO") else k
            L.append(fmt(lab, R[k]["s"][y]))
        L.append("")
    L += ["## 4. Hold-out check", "",
          "Selection rule: highest estimated net Rs on the fitting year (at least 100 trades). A setting is "
          "*recommended* only if it is positive after (estimated) costs in BOTH years and beats the baseline in the "
          "held-out year.", "",
          "| chosen on | setting | fit year net (est.) | held-out year net (est.) | baseline held-out (est.) | t held-out | both years > 0 | beats baseline held-out |",
          "|---|---|---|---|---|---|---|---|"]
    verdicts = []
    for fit, hold in (("A", "B"), ("B", "A")):
        for k in [sel[fit]] + ([combos[fit][0]] if combos[fit][1] and combos[fit][0] != sel[fit] else []):
            s = R[k]["s"]
            both = recommended(k)
            beats = s[hold]["net"] > bl[hold]["net"]
            verdicts.append((k, both and beats))
            L.append(f"| Year {fit} | {k} | {s[fit]['net']:+,.0f} | {s[hold]['net']:+,.0f} | {bl[hold]['net']:+,.0f} | "
                     f"{s[hold]['t']:.2f} | {'yes' if both else 'no'} | {'yes' if beats else 'no'} |")
    pos_both = [k for k in names if recommended(k)]
    L += ["", "Settings positive (estimated) in both years: " + (", ".join(pos_both) if pos_both else "**none**") + ".", ""]
    rec = [k for k, v in verdicts if v]
    # sensitivity: the chosen settings without the modelled premium stop (the modelled stop is optimistic on BANKNIFTY)
    L += ["### Sensitivity of the chosen settings (not used for selection)", "",
          "The modelled 15% stop flatters the BANKNIFTY check (section 1), so each chosen setting is also shown with no "
          "premium stop, and the strict model (C = 0) is in the last column.", ""] + HDR
    for fit in ("A", "B"):
        for k in dict.fromkeys([sel[fit], combos[fit][0]] if combos[fit][1] else [sel[fit]]):
            c0 = replace(R[k]["cfg"], prem=None)
            for y in years:
                L.append(fmt(f"{k}, NO premium stop, Year {y}", stats(res(y, c0), len(years[y]))))
    for y in years:
        L.append(fmt(f"baseline, NO premium stop, Year {y}", b0[y]))
    L += ["", "## 5. Verdict", ""]
    if rec:
        L += ["Passes the hold-out rule on the central estimate: " + ", ".join(rec) + ".", ""]
        for k in rec:
            s = R[k]["s"]
            L += [f"- {k}: Year A {s['A']['n']} trades ({s['A']['perday']:.1f}/day), est. Rs {s['A']['rs']:+,.0f} a trade, "
                  f"t {s['A']['t']:.2f}, est. net Rs {s['A']['net']:+,.0f}; Year B {s['B']['n']} trades "
                  f"({s['B']['perday']:.1f}/day), est. Rs {s['B']['rs']:+,.0f}, t {s['B']['t']:.2f}, est. net Rs "
                  f"{s['B']['net']:+,.0f}. Strict model (C = 0): Rs {s['A']['strict']:+,.0f} / {s['B']['strict']:+,.0f}."]
    else:
        L += ["**Nothing is recommended.** No setting chosen on one year is positive after estimated costs in both "
              "years and better than the baseline in the held-out year.", ""]
    c = cal["check"]
    L += ["", "## 6. Reading it (caveats)", "",
          "- **All SENSEX rupee figures are estimates.** The simulation uses SENSEX index minutes; option P&L = the "
          "BANKNIFTY-calibrated model above. The central model includes the constant C, which is what makes it match "
          f"BANKNIFTY's real P&L (Year A real Rs {c['A']['real_nostop']:+,.0f} vs model {c['A']['model_nostop']:+,.0f}); "
          "without C (strict column) almost every SENSEX setting is negative. Whether SENSEX option fills behave like "
          "BANKNIFTY's is the single biggest unknown.",
          f"- The premium stop is approximated from the index. On BANKNIFTY the modelled 15% stop looks better than the "
          f"real one (Year A modelled Rs {c['A']['model_15']:+,.0f} vs real {c['A']['real_15']:+,.0f}), so gains from "
          "tighter modelled stops (e.g. the 10% variant) are not trustworthy; the chosen settings are therefore also "
          "shown with no premium stop.",
          "- The selection is unstable: the setting chosen on Year A (slower levels: swing 30, pool confirm 15) collapsed "
          "in Year B, while the setting chosen on Year B held up in Year A. One direction out of two passing, with "
          "t-stats below 2 on the estimate, is weak evidence.",
          "- The ingredients that help in both years are the ones that cut trading in the quiet part of the day: no "
          "entries before 10:15, and trading only when the first hour moved at least ~0.8x its usual range; the "
          "3-minute book replacing the 5-minute book adds trades with similar edge. The 15-minute book on its own and "
          "the 30-minute book lose on the estimate.",
          "- SENSEX expiry days are computed from the calendar (Friday to 2024-12-31, Tuesday 2025-01-01 .. 2025-08-31, "
          "Thursday after; previous trading day on a holiday), not from exchange data. SENSEX has no volume in this "
          "file, so the 'VWAP' filter is a TWAP of the typical price.",
          "- Strike choice (1 ITM) could not be tested: there are no SENSEX option prices and the model only has an "
          "ATM delta.",
          "", "Intermediate trade lists: scratchpad sensex_plus_trades.parquet; calibration: sensex_plus_cal.json."]
    text = "\n".join(L)
    open(out, "w").write(text)
    if scratch:
        pd.concat([R[k][y].assign(variant=k, year=y) for k in names for y in years]).to_parquet(
            os.path.join(scratch, "sensex_plus_trades.parquet"))
    print(text)


if __name__ == "__main__":
    main()
