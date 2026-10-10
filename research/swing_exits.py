"""Swing strategies from SWING_DEEP.md re-run with the APP'S OWN exit rules (stop, target, profit-lock ladder).

    python3 -I research/swing_exits.py <dhan-data dir> <cache dir> [out.md]

Reuses swing_deep.py's signals, cost model, option books, portfolio engine, walk-forward and coin-flip baselines.
Only the exits change. The app's rules (read from android/, quoted in the report):
  ProfitLock.LADDER = (25% of target reached -> stop to breakeven after charges, 50% -> lock 25%, 75% -> lock 50%)
  JarvisTrades: STOP_POINTS 30, TARGET_POINTS 60, MIN_PREMIUM 35 (not bought at or below), COST_POINTS 1
  ProfitLock.Trail (Pine default): +5% -> breakeven after charges; +8% keep 50% of best gain, +20% 65%, +40% 75%
Options are simulated minute by minute on real minute bars over the whole multi-day hold (stop/lock checked before
target inside a minute, as JarvisTrades.OptionSim does; a minute that opens through the stop fills at its open).
Futures and stocks use daily bars (stop before target in the same bar); futures are re-checked on minute bars
for 2021-10 onwards.
"""
from __future__ import annotations

import glob
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)                                          # swing_deep (our own code) when run with -I
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import swing_deep as SD  # noqa: E402

DATA, CACHE = sys.argv[1], sys.argv[2]
OUT = sys.argv[3] if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else os.path.join(HERE, "SWING_EXITS.md")
T0 = time.time()

# ------------------------------------------------------------------------------------------- the app's rules
LADDER = [(0.25, 0.0), (0.50, 0.25), (0.75, 0.50)]      # engine/orb/ProfitLock.kt LADDER
LIT_STOP, LIT_TGT, MIN_PREM, TICK = 30.0, 60.0, 35.0, 0.05  # ira-core JarvisTrades.kt
PINE_BE, PINE_STEPS = 5.0, [(8.0, 50.0), (20.0, 65.0), (40.0, 75.0)]  # ProfitLock.Trail defaults
SC_STOP, SC_TGT = 0.30, 0.60                             # scaled adaptation: -30% / +60% of premium
EPS = 1e-9
FAMS = ["original (time exit)", "app literal: -30 / +60 pts + ladder", "scaled app: -30% / +60% + ladder",
        "app Pine % trail only"]
FSHORT = {FAMS[0]: "orig", FAMS[1]: "app", FAMS[2]: "scaled", FAMS[3]: "pine"}


def ladder_lock(E, T, pk, be):
    out = np.full(np.shape(pk), np.nan)
    for f, k in LADDER:
        lv = max(E + k * T, be)
        ok = (pk >= E + f * T - EPS) & (lv < pk)
        out = np.where(ok, np.fmax(out, lv), out)
    return out


def pine_lock(E, pk, be):
    out = np.full(np.shape(pk), np.nan)
    if E <= 0:
        return out
    g = (pk - E) / E * 100
    out = np.where((g >= PINE_BE - EPS) & (be < pk), be, out)
    for s, k in PINE_STEPS:
        out = np.where(g >= s - EPS, np.fmax(out, E + k / 100 * (pk - E)), out)
    return out


def fam_params(fam, E, be):
    """(stop, target, lock function of the peak-before array) or None when the app would not buy."""
    if fam == "orig":
        return -np.inf, np.inf, None
    if fam == "app":
        if E <= MIN_PREM:
            return None
        st = np.floor((E - LIT_STOP) / TICK + 1e-9) * TICK
        return (st if st > 0 else -np.inf), E + LIT_TGT, (lambda pk: ladder_lock(E, LIT_TGT, pk, be))
    if fam == "scaled":
        T = SC_TGT * E
        return E * (1 - SC_STOP), E + T, (lambda pk: ladder_lock(E, T, pk, be))
    if fam == "pine":
        return -np.inf, np.inf, (lambda pk: pine_lock(E, pk, be))
    if fam == "R":
        raise ValueError
    raise KeyError(fam)


def first_event(E, o, h, l, stop, tgt, lockf):
    """Minute-by-minute (any bars): returns (k, fill, why) of the first exit or None. Lock earned by the peak BEFORE
    the bar (ProfitLock.exits); stop/lock checked before target; a bar opening through the floor fills at its open."""
    n = len(o)
    if n == 0:
        return None
    pk = np.maximum.accumulate(np.concatenate(([E], h[:-1])))
    lock = lockf(pk) if lockf is not None else np.full(n, np.nan)
    floor = np.fmax(lock, stop)
    hs = np.flatnonzero(l <= floor + EPS)
    ht = np.flatnonzero(h >= tgt - EPS) if np.isfinite(tgt) else np.array([], int)
    ks = hs[0] if len(hs) else n
    kt = ht[0] if len(ht) else n
    if ks < n and ks <= kt:
        why = "stop" if (np.isnan(lock[ks]) or lock[ks] <= stop) else "lock"
        return ks, min(floor[ks], o[ks]), why
    if kt < n:
        return kt, tgt, "target"
    return None

# ------------------------------------------------------------------------------------------- option minute store


class MinStore:
    """Real option minute bars (nearest weekly or nearest monthly series, +/-10 strikes), keyed by contract."""

    def __init__(self, sym, kind, B):
        path = os.path.join(CACHE, f"min_{sym}_{kind}.parquet")
        if os.path.exists(path):
            df = pd.read_parquet(path)
        else:
            parts = []
            for typ in ("CALL", "PUT"):
                for f in sorted(glob.glob(os.path.join(DATA, "options", sym, kind, typ, "*.parquet"))):
                    x = pd.read_parquet(f, columns=["ts", "strike", "open", "high", "low", "close"])
                    t = x.ts.dt.tz_localize(None).values.astype("datetime64[m]").astype(np.int64)
                    mod = t % 1440
                    keep = (mod >= 555) & (mod <= 929) & (x.close.values > 0)
                    parts.append(pd.DataFrame({"t": t[keep], "typ": np.int8(0 if typ == "CALL" else 1),
                                               "strike": x.strike.values[keep].astype(np.float32),
                                               "o": x.open.values[keep], "h": x.high.values[keep],
                                               "l": x.low.values[keep], "c": x.close.values[keep]}))
            df = pd.concat(parts, ignore_index=True).drop_duplicates(["typ", "strike", "t"])
            df.to_parquet(path)
        expd = (B.exp.values.astype("datetime64[D]").astype(np.int64))
        day = df.t.values // 1440
        j = np.minimum(np.searchsorted(expd, day), len(expd) - 1)
        df = df.assign(exp=expd[j].astype(np.int32))
        df = df.sort_values(["exp", "typ", "strike", "t"], kind="stable").reset_index(drop=True)
        self.t = df.t.values
        self.o, self.h, self.l, self.c = (df[k].values.astype(float) for k in "ohlc")
        g = df.groupby(["exp", "typ", "strike"], sort=False).indices
        self.idx = {(int(e), int(ty), float(k)): (v[0], v[-1] + 1) for (e, ty, k), v in g.items()}

    def bars(self, exp_day, typ, K, t_from, t_to):
        """Bars with t_from <= t <= t_to."""
        s = self.idx.get((exp_day, typ, float(K)))
        if s is None:
            return None
        a, b = s
        t = self.t[a:b]
        i0, i1 = np.searchsorted(t, t_from, "left"), np.searchsorted(t, t_to, "right")
        return t[i0:i1], self.o[a + i0:a + i1], self.h[a + i0:a + i1], self.l[a + i0:a + i1], self.c[a + i0:a + i1]


def tmin(dt, hh, mm):
    return int(np.datetime64(dt, "m").astype(np.int64)) + hh * 60 + mm


STORES = {}


def store(sym, kind):
    if (sym, kind) not in STORES:
        STORES[(sym, kind)] = MinStore(sym, kind, SD.book(sym, kind))
    return STORES[(sym, kind)]


STATS = {"model": 0, "real": 0, "skip_expday": 0, "skip_minprem": 0, "noprice": 0}


def leg_price_at(st, B, dt, slot, typ, K, expiry):
    """Real minute close at 09:20 (first bar 09:20-09:30) or 15:25 (last bar 15:10-15:25); else Book (model)."""
    e = int(np.datetime64(expiry, "D").astype(np.int64))
    ty = 0 if typ == "C" else 1
    if slot == "am":
        r = st.bars(e, ty, K, tmin(dt, 9, 20), tmin(dt, 9, 30))
        if r is not None and len(r[0]):
            STATS["real"] += 1
            return r[4][0], r[0][0]
        tt = tmin(dt, 9, 20)
    else:
        r = st.bars(e, ty, K, tmin(dt, 15, 10), tmin(dt, 15, 25))
        if r is not None and len(r[0]):
            STATS["real"] += 1
            return r[4][-1], r[0][-1]
        tt = tmin(dt, 15, 25)
    p = B.price(dt, slot, typ, K, expiry, force_model=True)
    if p is None:
        return None, tt
    STATS["model"] += 1
    return p, tt


def opt_trade_min(sym, kind, dates, di, dr, ei, N, fam, legs, golden=False):
    """One option position entered 09:20 on dates[ei]; time exit 09:20 on dates[ei+N] (or the last date, 15:25), or
    15:25 on the contract's expiry day (or the session before it with golden=True) - no roll. App exits are checked
    on every real minute of the whole hold. legs = [(qty_sign, strikes_otm)]. Returns a dict or None (not traded)."""
    B, st = SD.book(sym, kind), store(sym, kind)
    lot, typ = SD.LOT[sym], ("C" if dr > 0 else "P")
    e_date = dates[ei]
    expiry = B.exp_for(e_date)
    if expiry <= e_date:                       # the app never buys today's expiry (OrbRules: next expiry after day)
        STATS["skip_expday"] += 1
        return None
    S0 = B.S(e_date, "am")
    if S0 is None:
        STATS["noprice"] += 1
        return None
    atm = round(S0 / B.step) * B.step
    xi, xslot = (ei + N, "am") if ei + N < len(dates) else (len(dates) - 1, "pm")
    x_date = dates[xi]
    why_t = "time"
    if golden:
        k = di.get(expiry)
        if k is not None and k - 1 >= ei and SD._before(dates[k - 1], "pm", x_date, xslot):
            x_date, xslot, xi, why_t = dates[k - 1], "pm", k - 1, "golden"
    if SD._before(expiry, "pm", x_date, xslot):
        x_date, xslot, xi, why_t = expiry, "pm", di.get(expiry, xi), "expiry"
    if xi <= ei and not (xi == ei and xslot == "pm"):
        STATS["noprice"] += 1
        return None
    e_day = int(np.datetime64(expiry, "D").astype(np.int64))
    Ks = [atm + dr * k * B.step for _, k in legs]
    p0s, p1s, t_in, t_out = [], [], None, None
    for (q, k), K in zip(legs, Ks):
        p0, ta = leg_price_at(st, B, e_date, "am", typ, K, expiry)
        p1, tb = leg_price_at(st, B, x_date, xslot, typ, K, expiry)
        if p0 is None or p1 is None:
            STATS["noprice"] += 1
            return None
        p0s.append(p0)
        p1s.append(p1)
        t_in = ta if t_in is None else max(t_in, ta)
        t_out = tb if t_out is None else min(t_out, tb)
    E = sum(q * p for (q, _), p in zip(legs, p0s))           # position value per unit (debit)
    # charges per unit for the breakeven rung (round trip + slippage), as ProfitLock.roundTripPerUnit
    cost_u = 0.0
    for (q, _), p in zip(legs, p0s):
        cost_u += (SD.opt_side_cost(p * lot, "buy") + SD.opt_side_cost(p * lot, "sell")) / lot + 2 * SD.opt_slip(p, sym)
    prm = fam_params(fam, E, E + cost_u)
    if prm is None:
        STATS["skip_minprem"] += 1
        return None
    stop, tgt, lockf = prm
    exit_px = list(p1s)
    why, x_t = why_t, t_out
    if fam != "orig":
        ty = 0 if typ == "C" else 1
        if len(legs) == 1:
            r = st.bars(e_day, ty, Ks[0], t_in + 1, t_out)
            if r is not None and len(r[0]):
                ev = first_event(E, r[1], r[2], r[3], stop, tgt, lockf)
                if ev is not None:
                    k, fill, why = ev
                    exit_px, x_t = [fill], r[0][k]
        else:                                   # spreads: value path from both legs' minute closes on shared minutes
            rs = [st.bars(e_day, ty, K, t_in + 1, t_out) for K in Ks]
            if all(r is not None and len(r[0]) for r in rs):
                tt = rs[0][0]
                for r in rs[1:]:
                    tt = np.intersect1d(tt, r[0])
                if len(tt):
                    cl = [r[4][np.searchsorted(r[0], tt)] for r in rs]
                    v = sum(q * c for (q, _), c in zip(legs, cl))
                    ev = first_event(E, v, v, v, stop, tgt, lockf)
                    if ev is not None:
                        k, _, why = ev
                        exit_px, x_t = [c[k] for c in cl], tt[k]
    stop_fill = why in ("stop", "lock")
    net, debit = 0.0, 0.0
    for (q, _), p0, p1 in zip(legs, p0s, exit_px):
        s0, s1 = SD.opt_slip(p0, sym), SD.opt_slip(p1, sym) * (2 if stop_fill else 1)
        if q > 0:
            b, sl = p0 + s0, max(0.0, p1 - s1)
            net += (sl - b) * lot - SD.opt_side_cost(b * lot, "buy") - SD.opt_side_cost(sl * lot, "sell")
            debit += b * lot
        else:
            sl, b = max(0.0, p0 - s0), p1 + s1
            net += (sl - b) * lot - SD.opt_side_cost(sl * lot, "sell") - SD.opt_side_cost(b * lot, "buy")
            debit -= sl * lot
    x_day = np.datetime64(int(x_t // 1440), "D")
    xi = di.get(pd.Timestamp(x_day), xi)
    return dict(net=net, debit=debit, R=net / max(1.0, abs(debit)), why=why, ed=e_date, xd=dates[xi], xi=xi,
                year=e_date.year, dir=dr, prem=E)


def run_opt(d, sig, sym, kind, N, fam, legs, start, golden=False):
    """One position at a time: signal on the close, buy 09:20 next session; after the exit the next signal is
    the exit day's close."""
    dates = list(d.index)
    di = {x: i for i, x in enumerate(dates)}
    i = max(0, int(np.searchsorted(d.index.values, np.datetime64(start))) - 1)
    out = []
    n = len(dates)
    while i < n - 1:
        sg = sig[i]
        if sg in (1, -1) and dates[i + 1] >= start:
            r = opt_trade_min(sym, kind, dates, di, int(sg), i + 1, N, fam, legs, golden)
            if r is not None:
                out.append(r)
                i = max(r["xi"], i + 1)
                continue
        i += 1
    return pd.DataFrame(out)

# ------------------------------------------------------------------------------------------- stats helpers


def span_years(start, end=pd.Timestamp("2026-10-06")):
    return (end - start).days / 365.25


def opt_stats(o, span, years):
    if o is None or o.empty:
        return dict(n=0, win="", avgR="", pf="", tot=0.0, yr=0.0, dd=0.0, pos="0/0", by={}, posn=0)
    st = SD.trade_stats(o.net.values, o.R.values)
    by = o.groupby("year").net.sum().to_dict()
    eq = o.sort_values("xd").net.cumsum()
    posn = sum(1 for y in years if by.get(y, 0) > 0)
    return dict(n=st["n"], win=st["win"], avgR=st["avgR"], pf=st["pf"], tot=st["tot"], yr=st["tot"] / span,
                dd=float((eq - eq.cummax()).min()), pos=f"{posn}/{len(years)}", by=by, posn=posn)


def wf(cfg, first, fmt=SD.rs):
    cfg = {(k if isinstance(k, tuple) else (k,)): v for k, v in cfg.items()}
    rows, oos = SD.walk_forward(cfg, first_test=first, fmt=fmt)
    return rows, oos


def wf_txt(oos, money=True):
    if money:
        return f"{SD.rs(sum(oos.values()))} ({sum(1 for v in oos.values() if v > 0)}/{len(oos)})"
    tot = np.prod([1 + v for v in oos.values()]) - 1
    return f"{SD.pct(tot)} ({sum(1 for v in oos.values() if v > 0)}/{len(oos)})"


SUMMARY = []          # rows for the top table


def coin_band(vals):
    v = np.asarray(vals, float)
    return np.mean(v), np.percentile(v, 5), np.percentile(v, 95)


def pctile(x, vals):
    v = np.asarray(vals, float)
    return 100.0 * np.mean(v < x)

# ======================================================================================== OPTIONS


OPT_STRUCT = {"bought monthly ATM": ("MONTH", [(1, 0)]),
              "bought weekly ATM": ("WEEK", [(1, 0)]),
              "monthly debit spread ATM / 2 OTM": ("MONTH", [(1, 0), (-1, 2)]),
              "monthly debit spread ATM / 4 OTM": ("MONTH", [(1, 0), (-1, 4)])}
HOLDS = {"MONTH": (5, 10, 20), "WEEK": (3, 5)}
HEAD_HOLD = {"MONTH": 10, "WEEK": 5}
COIN = "Coin flip (random side, ~15% of days)"


def options_part(idx):
    md = ["## 2. Index options with the app's exits (minute by minute, real prices)", "",
          "Signals as SWING_DEEP section 2. Buy at 09:20 the session after the signal (ATM call for a long signal, "
          "ATM put for a short; spreads also sell 2 or 4 strikes further out), one lot. Contract = the nearest expiry "
          "AFTER the entry day (the app never buys today's expiry; entries that fall on an expiry day are skipped for "
          "every exit so all exits trade the same signals). No roll: if the contract expires inside the hold it is "
          "sold at 15:25 on expiry day. Time stop (max hold) = 09:20 after 5 / 10 / 20 sessions (weekly: 3 / 5). "
          "App exits are checked on every real minute bar of the hold, overnight included: a minute that opens "
          "through the stop/lock fills at its open (gap-through = worse price); stop and lock fills pay double "
          "slippage. Spreads are checked on the spread's value at each minute's close of both legs. 'avg R' = net / "
          "premium paid. 'original' here = the same time exit with no stop (this engine; slightly different from "
          "SWING_DEEP because of no-roll and the skipped expiry-day entries - SWING_DEEP's own figure is shown for "
          "reference in the summary).", ""]
    res = {}
    coin_res = {}
    for sym, d in idx.items():
        start = SD.OPT_START[sym]
        sigs = SD.index_signals(d)
        for sn, (kind, legs) in OPT_STRUCT.items():
            Bk = SD.book(sym, kind)
            last = max(Bk.dates)
            span = span_years(start, min(pd.Timestamp("2026-10-06"), last))
            years = [y for y in range(start.year, last.year + 1)]
            fams = ["orig", "app", "scaled", "pine"] if len(legs) == 1 else ["orig", "app", "scaled"]
            rows = []
            for rn in SD.OPT_RULES:
                for N in HOLDS[kind]:
                    for f in fams:
                        o = run_opt(d, sigs[rn], sym, kind, N, f, legs, start)
                        o = o[o.ed <= last] if len(o) else o
                        s = opt_stats(o, span, years)
                        res[(sym, sn, rn, N, f)] = (o, s)
                        whys = o.why.value_counts(normalize=True) if len(o) else pd.Series(dtype=float)
                        rows.append([rn, N, f, s["n"], s["win"], s["avgR"], s["pf"], SD.rs(s["yr"]), SD.rs(s["dd"]),
                                     s["pos"], f"{100 * whys.get('stop', 0):.0f}/{100 * whys.get('lock', 0):.0f}/"
                                     f"{100 * whys.get('target', 0):.0f}/{100 * (whys.get('time', 0) + whys.get('expiry', 0)):.0f}"])
            # coin flip with the same exits, head hold
            N0 = HEAD_HOLD[kind]
            nseed = 20 if len(legs) == 1 else 10
            for f in fams:
                vals, posy = [], []
                for seed in range(1, nseed + 1):
                    sg = SD.index_signals(d, seed)[COIN]
                    o = run_opt(d, sg, sym, kind, N0, f, legs, start)
                    o = o[o.ed <= last] if len(o) else o
                    s = opt_stats(o, span, years)
                    vals.append(s["yr"])
                    posy.append(s["posn"])
                coin_res[(sym, sn, f)] = (vals, np.mean(posy), len(years))
            first = start.year + 1 if start.month < 9 else start.year + 2
            # walk-forward per exit family (rules x holds), coin/always excluded
            wrows = []
            for f in fams:
                cfg = {(rn, N): res[(sym, sn, rn, N, f)][1]["by"] for rn in SD.OPT_RULES for N in HOLDS[kind]
                       if not rn.startswith(("Coin", "Always"))}
                r_, oos = wf(cfg, first)
                res[(sym, sn, "WF", f)] = oos
                wrows.append([f, wf_txt(oos), "; ".join(f"{r[0]}: {r[1]}" for r in r_)])
            cb = [[f, N0, SD.rs(coin_band(coin_res[(sym, sn, f)][0])[0]),
                   f"{SD.rs(coin_band(coin_res[(sym, sn, f)][0])[1])} .. {SD.rs(coin_band(coin_res[(sym, sn, f)][0])[2])}",
                   f"{coin_res[(sym, sn, f)][1]:.1f}/{coin_res[(sym, sn, f)][2]}"] for f in fams]
            md += [f"### {sym} - {sn} ({start.date()} .. {min(last, pd.Timestamp('2026-10-06')).date()}, Rs per lot)", "",
                   SD.table(["rule", "max hold", "exit", "trades", "win", "avg R", "PF", "Rs/yr", "max DD (closed)",
                             "years +", "exit mix % stop/lock/target/time"], rows), "",
                   f"Coin-flip entries with the SAME exits ({nseed} seeds):", "",
                   SD.table(["exit", "max hold", "coin mean Rs/yr", "coin 90% band Rs/yr", "coin avg years +"], cb), "",
                   "Walk-forward (each test year trades the rule x hold with the best profit over all earlier years; "
                   "stay out if none was positive):", "",
                   SD.table(["exit family", "out-of-sample Rs total (years +)", "picks"], wrows), ""]
            print(f"  options {sym} {sn} done {time.time() - T0:.0f}s", flush=True)
    return md, res, coin_res


def expiry_choice_part(idx):
    """SWING_DEEP section 7 (golden rule A) with the app's exits."""
    md = ["## 5. Option-buyer rule A (which expiry) with the app's exits", "",
          "SWING_DEEP section 7's two signals and 1 / 3 / 5-session holds, each contract with each exit. Golden rule = "
          "sell the weekly by 15:25 the session before its expiry. Next-week and roll variants are left out (no "
          "minute prices for a contract before it becomes the nearest one). Rs per lot per year.", ""]
    rules = ["Donchian 20-day breakout", "Pullback: >200DMA & RSI(2)<10"]
    variants = {"weekly ATM": ("WEEK", [(1, 0)], False), "weekly OTM (2 strikes)": ("WEEK", [(1, 2)], False),
                "weekly OTM, golden rule": ("WEEK", [(1, 2)], True), "monthly ATM": ("MONTH", [(1, 0)], False),
                "monthly OTM (2 strikes)": ("MONTH", [(1, 2)], False)}
    out = {}
    for sym, d in idx.items():
        start = SD.OPT_START[sym]
        wend = max(SD.book(sym, "WEEK").dates)
        span = span_years(start, wend)
        sigs = SD.index_signals(d)
        rows = []
        for rn in rules:
            for H in (1, 3, 5):
                for vn, (kind, legs, gold) in variants.items():
                    cells = []
                    for f in ("orig", "app", "scaled", "pine"):
                        o = run_opt(d, sigs[rn], sym, kind, H, f, legs, start, golden=gold)
                        o = o[o.ed <= wend] if len(o) else o
                        s = opt_stats(o, span, list(range(start.year, wend.year + 1)))
                        out[(sym, rn, H, vn, f)] = s
                        cells.append(f"{SD.rs(s['yr'])} ({s['pos']})")
                    rows.append([rn, H, vn] + cells)
        md += [f"### {sym} (to {wend.date()}): Rs/yr per lot (years +)", "",
               SD.table(["rule", "hold", "contract", "original", "app literal", "scaled app", "Pine trail"], rows), ""]
    print(f"  expiry choice done {time.time() - T0:.0f}s", flush=True)
    return md, out

# ======================================================================================== FUTURES (R ladder)


def run_index_R(d, sig, N, m=2.0, lo_only=False, lot=65):
    """SD.run_index with the app's structure in R: stop 1R = m x ATR, target 2R, ProfitLock ladder on the 2R target,
    time stop after N sessions (exit next open). Daily bars: gap through the floor fills at the open; stop/lock before
    target in a bar; a rung earned by today's high exits today only if the close is back at/below it (certain)."""
    o, h, l, c, atr = (d[k].values for k in ("open", "high", "low", "close", "atr"))
    n = len(d)
    trades, pos, pend_entry, pend_exit = [], None, None, False

    def lockR(p, peak):
        T = p["T"]
        lv = np.nan
        for f, k in LADDER:
            v = max(k * T, p["be"])
            if peak >= f * T - EPS and v < peak:
                lv = np.fmax(lv, v)
        return lv

    def floor_of(p):
        lk = lockR(p, p["peak"])
        return max(-p["sd"], lk) if np.isfinite(lk) else -p["sd"]

    def close(p, i, fav, why, stop_exit):
        p2 = dict(p)
        trades.append(SD.close_trade(p2, i, p["ep"] + p["dir"] * fav, why, d, stop_exit=stop_exit))

    for i in range(n):
        if pend_entry is not None and pos is None:
            dirn, lvl_b, lvl_s, a = pend_entry
            pend_entry = None
            px = None
            if dirn == 2:
                hb, hs = h[i] >= lvl_b, l[i] <= lvl_s
                if hb and hs:
                    near_b = abs(o[i] - lvl_b) <= abs(o[i] - lvl_s)
                    dirn = 1 if (near_b or lo_only) else -1
                    px = max(o[i], lvl_b) if dirn > 0 else min(o[i], lvl_s)
                    xp = lvl_s if dirn > 0 else lvl_b
                    if dirn > 0 or not lo_only:
                        pp = dict(dir=dirn, ei=i, ep=px, R=m * a)
                        trades.append(SD.close_trade(pp, i, xp, "whipsaw", d, stop_exit=True))
                    px = None
                elif hb:
                    dirn, px = 1, max(o[i], lvl_b)
                elif hs and not lo_only:
                    dirn, px = -1, min(o[i], lvl_s)
            else:
                px = o[i]
            if px is not None and a > 0:
                be = SD.fut_costs(px * lot, px * lot, dirn) / lot
                pos = dict(dir=dirn, ei=i, ep=px, R=m * a, sd=m * a, T=2 * m * a, be=be, peak=0.0, new=True)
        if pos is not None and pend_exit:
            trades.append(SD.close_trade(pos, i, o[i], "time", d))
            pos, pend_exit = None, False
        if pos is not None:
            dr, ep = pos["dir"], pos["ep"]
            fh = dr * ((h[i] if dr > 0 else l[i]) - ep)
            fl = dr * ((l[i] if dr > 0 else h[i]) - ep)
            fc = dr * (c[i] - ep)
            fo = dr * (o[i] - ep)
            fl0 = floor_of(pos)
            done = False
            if not pos["new"] and fo <= fl0:
                close(pos, i, fo, "stop/lock (gap)", True)
                done = True
            elif fl <= fl0:
                close(pos, i, fl0, "stop" if fl0 <= -pos["sd"] + EPS else "lock", True)
                done = True
            elif fh >= pos["T"]:
                close(pos, i, pos["T"], "target", False)
                done = True
            else:
                pos["peak"] = max(pos["peak"], fh)
                lk = lockR(pos, pos["peak"])
                if np.isfinite(lk) and fc <= lk:
                    close(pos, i, lk, "lock", True)
                    done = True
            if done:
                pos, pend_exit = None, False
            else:
                pos["new"] = False
                if i - pos["ei"] + 1 >= N:
                    pend_exit = True
                if i == n - 1:
                    trades.append(SD.close_trade(pos, i, c[i], "end of data", d))
                    pos = None
        if pos is None and pend_entry is None and i < n - 1 and not pend_exit:
            sg = sig[i]
            if sg == 2:
                pend_entry = (2, h[i], l[i], atr[i])
            elif sg == 1 or (sg == -1 and not lo_only):
                pend_entry = (int(sg), None, None, atr[i])
    return trades


def fut_run(d, sym, sig, exn, lo_only=False):
    if exn.startswith("app R"):
        N = int(exn.split("max ")[1].split("d")[0])
        tr = run_index_R(d, sig, N, lo_only=lo_only, lot=SD.LOT[sym])
    else:
        tr = SD.run_index(d, sig, SD.EXITS[exn], "intraday", lo_only)
    tr = [t for t in tr if t["ed"] >= SD.START]
    return SD.fut_pnl(tr, d, sym) if tr else pd.DataFrame()


FUT_ORIG = list(SD.EXITS)
FUT_APP = ["app R: stop 1R(2ATR) / target 2R + ladder, max 5d", "app R: stop 1R(2ATR) / target 2R + ladder, max 10d",
           "app R: stop 1R(2ATR) / target 2R + ladder, max 20d"]


def futures_part(idx):
    md = ["## 1. NIFTY / BANKNIFTY futures with the app's exit structure in R (1 lot, 2016 - Oct 2026)", "",
          "Same signals, costs, carry and rolls as SWING_DEEP section 1. App structure translated to R: 1R = the "
          "SWING_DEEP stop distance (2 x ATR14 at the signal), stop -1R (resting), target +2R (the app's 1 : 2, "
          "30 / 60), and ProfitLock's ladder on the 2R target: at +0.5R the stop goes to breakeven after charges, at "
          "+1R it locks +0.5R, at +1.5R it locks +1R. Max hold 5 / 10 / 20 sessions as the backstop. Daily bars: "
          "gap-through fills at the open, stop/lock checked before target in the same bar (pessimistic). There is no "
          "'literal' premium-point version for futures (30 / 60 premium points mean nothing on the index).", ""]
    years = list(range(2016, 2027))
    res, coin = {}, {}
    for sym, d in idx.items():
        sigs = SD.index_signals(d)
        rows = []
        for rn, sg in sigs.items():
            for en in FUT_ORIG + FUT_APP:
                tr = fut_run(d, sym, sg, en)
                s = SD.fut_summary(tr, d, sym, years)
                if s is None:
                    continue
                res[(sym, rn, en)] = (tr, s)
                st = s["st"]
                whys = tr.why.value_counts(normalize=True)
                mix = ""
                if en in FUT_APP:
                    mix = (f"{100 * whys.filter(like='stop').sum():.0f}/{100 * whys.get('lock', 0):.0f}/"
                           f"{100 * whys.get('target', 0):.0f}/{100 * whys.get('time', 0):.0f}")
                rows.append([rn, en, st["n"], st["win"], st["avgR"], st["pf"], SD.rs(st["tot"] / 10.75),
                             f"{s['pos_years']}/{s['n_years']}", SD.rs(s["dd"]), mix])
        md += [f"### {sym}: every rule x exit (Rs per lot per year after all costs)", "",
               SD.table(["rule", "exit", "trades", "win", "avg R", "PF", "Rs/yr", "years +", "max DD (daily MTM)",
                         "app exit mix % stop/lock/target/time"], rows), ""]
        # coin flips with the same exits
        crow = []
        for en in ("fixed 10d", "2ATR stop + 3ATR chandelier (max 20d)") + tuple(FUT_APP):
            tots, posy = [], []
            for seed in range(1, 31):
                tr = fut_run(d, sym, SD.index_signals(d, seed)[COIN], en)
                s = SD.fut_summary(tr, d, sym, years)
                tots.append(s["st"]["tot"] / 10.75)
                posy.append(s["pos_years"])
            coin[(sym, en)] = (tots, np.mean(posy))
            mu, lo_, hi_ = coin_band(tots)
            crow.append([en, SD.rs(mu), f"{SD.rs(lo_)} .. {SD.rs(hi_)}", f"{np.mean(posy):.1f}/11"])
        md += [f"Coin-flip entries (30 seeds) with the SAME exits, {sym}:", "",
               SD.table(["exit", "coin mean Rs/yr", "90% band Rs/yr", "coin avg years +"], crow), ""]
        # walk-forward per family (rules x exits x long/short) - as SWING_DEEP 6a
        wrows = []
        for fam, exs in (("original exits", FUT_ORIG), ("app R ladder", FUT_APP), ("both", FUT_ORIG + FUT_APP)):
            cfg = {}
            for rn, sg in sigs.items():
                if rn.startswith(("Coin", "Always")):
                    continue
                for en in exs:
                    for lo in (False, True):
                        if lo and (sg >= 0).all():
                            continue
                        if lo:
                            tr = fut_run(d, sym, sg, en, lo_only=True)
                            s = SD.fut_summary(tr, d, sym, None)
                        else:
                            s = res[(sym, rn, en)][1]
                        if s is not None:
                            cfg[(rn, en, "long only" if lo else "long+short")] = s["by_year"]
            r_, oos = wf(cfg, 2019)
            res[(sym, "WF", fam)] = oos
            wrows.append([fam, len(cfg), wf_txt(oos)])
        md += [f"Walk-forward 2019-2026, {sym} (same method as SWING_DEEP 6a):", "",
               SD.table(["candidate exits", "configs", "out-of-sample Rs total (years +)"], wrows), ""]
        print(f"  futures {sym} done {time.time() - T0:.0f}s", flush=True)
    return md, res, coin


def futures_minute_check(idx, res):
    """Re-simulate the daily-bar app-R trades (entries from Oct-2021) on index minute bars, same entry/stop/target."""
    md = ["### 1c. Check: daily-bar vs minute-bar fills for the app R exits (index minute data, Oct-2021 on)", "",
          "Same trades (same entry price, R, target), exit re-simulated on 1-minute index bars over the whole hold "
          "(lock earned by the peak before each minute, as the app). NR7 stop-entries are left out (entry minute "
          "unknown). Gross points x lot, before costs.", ""]
    rows = []
    for sym, d in idx.items():
        fs = sorted(glob.glob(os.path.join(DATA, "candles", "minute", "IDX_I", sym, "*.parquet")))
        x = pd.concat([pd.read_parquet(f) for f in fs])
        t = x.ts.dt.tz_localize(None).values.astype("datetime64[m]").astype(np.int64)
        mod = t % 1440
        k = (mod >= 555) & (mod <= 929)
        t = t[k]
        o, h, l, c = (x[z].values[k].astype(float) for z in ("open", "high", "low", "close"))
        srt = np.argsort(t, kind="stable")
        t, o, h, l, c = t[srt], o[srt], h[srt], l[srt], c[srt]
        t0 = pd.Timestamp(np.datetime64(int(t[0]), "m")).normalize() + pd.Timedelta(days=1)
        lot = SD.LOT[sym]
        for rn in ("Donchian 20-day breakout", "Pullback: >200DMA & RSI(2)<10", "EMA 20/50 trend",
                   "Mean reversion: buy after big down day", COIN):
            tr, _ = res[(sym, rn, FUT_APP[1])]
            tr = tr[(tr.ed >= t0) & (tr.why != "end of data")]
            gd, gm, same = 0.0, 0.0, 0
            dates = d.index
            for z in tr.itertuples():
                k_end = z.ei + 10
                if k_end >= len(dates):
                    continue
                a = tmin(z.ed, 9, 15)
                b = tmin(dates[k_end], 9, 15)          # the planned time exit: open of the 10th session
                i0, i1 = np.searchsorted(t, a), np.searchsorted(t, b, "left")
                if i1 - i0 < 2 or i1 >= len(t) or t[i1] // 1440 != b // 1440:
                    continue
                dr, ep, R = z.dir, z.ep, z.R
                sl = slice(i0, i1)
                if dr > 0:
                    oo, hh, ll, ox = o[sl], h[sl], l[sl], o[i1]
                    E = ep
                else:
                    oo, hh, ll, ox = -o[sl], -l[sl], -h[sl], -o[i1]
                    E = -ep
                be = E + SD.fut_costs(ep * lot, ep * lot, dr) / lot
                T = 2 * R
                ev = first_event(E, oo, hh, ll, E - R, E + T, lambda pk: ladder_lock(E, T, pk, be))
                xp_m = ev[1] if ev is not None else ox
                gm += (xp_m - E) * lot
                gd += dr * (z.xp - ep) * lot
                same += 1
            rows.append([sym, rn, same, SD.rs(gd), SD.rs(gm), SD.rs(gm - gd)])
    md += [SD.table(["index", "rule (app R, max 10d)", "trades", "daily-bar gross Rs", "minute-bar gross Rs",
                     "minute minus daily"], rows), ""]
    print(f"  futures minute check done {time.time() - T0:.0f}s", flush=True)
    return md

# ======================================================================================== STOCKS (R ladder portfolio)


def stock_sim_R(P, sig, N, score=None, maxN=10, risk=0.01, maxw=0.2, stop_entry=False, start=SD.START, mask=None):
    """SD.stock_sim with the app's structure in R: stop 1R = 2 x ATR (resting), target 2R, ProfitLock ladder on the
    2R target, time stop after N sessions. Daily bars: gap through the floor fills at the open, stop/lock before target
    in the same bar, a rung earned by today's high exits today only if the close is back at/below it."""
    A = P.A
    O, H, L, C, atr = A["O"], A["H"], A["L"], A["C"], A["atr"]
    T_, S = C.shape
    if mask is not None:
        sig = sig & mask
    sc = score if score is not None else np.nan_to_num(P.ind["ret126"].values, nan=-9)
    i0 = int(np.searchsorted(P.dates.values, np.datetime64(start)))
    cash, pos, pend_in, pend_out = SD.CAP, {}, [], set()
    eq = np.full(T_, np.nan)
    eq[:i0] = SD.CAP
    trades = []
    lastpx = pd.DataFrame(C).ffill().values

    def lockR(p, peak):
        lv = np.nan
        for f, k in LADDER:
            v = max(k * p["T"], p["be"])
            if peak >= f * p["T"] - EPS and v < peak:
                lv = np.fmax(lv, v)
        return lv

    def out(s, i, px, stopx, why):
        p = pos.pop(s)
        pend_out.discard(s)
        v = SD._close_stock(p, s, i, px, trades, P, stopx)
        trades[-1]["why"] = why
        return v

    for i in range(i0, T_):
        eqp = eq[i - 1]
        for s in list(pend_out):
            if s in pos and np.isfinite(O[i, s]):
                cash += out(s, i, O[i, s] * (1 - SD.EQ_SLIP), False, "time")
        if pend_in:
            pend_in.sort(key=lambda z: -z[1])
            for s, _, lvl, a in pend_in:
                if len(pos) >= maxN or s in pos or not np.isfinite(O[i, s]) or not (a > 0):
                    continue
                if stop_entry:
                    if not (H[i, s] >= lvl):
                        continue
                    px = max(O[i, s], lvl) * (1 + SD.EQ_SLIP)
                else:
                    px = O[i, s] * (1 + SD.EQ_SLIP)
                sh = int(min(risk * eqp / (2.0 * a), maxw * eqp / px, (cash - 100) / (px * 1.0012)))
                if sh < 1 or sh * px < 10_000:
                    continue
                cost = SD.eq_side_cost(sh * px, "buy")
                cash -= sh * px + cost
                be = (cost + SD.eq_side_cost(sh * px, "sell")) / sh + 2 * SD.EQ_SLIP * px
                pos[s] = dict(sh=sh, ep=px, ei=i, R=sh * 2.0 * a, sd=2.0 * a, T=4.0 * a, be=be, peak=0.0,
                              cost_in=cost, gap=0.0, new=True)
            pend_in = []
        for s in list(pos):
            p = pos[s]
            if not np.isfinite(L[i, s]):
                continue
            ep = p["ep"]
            lk = lockR(p, p["peak"])
            fl0 = max(-p["sd"], lk) if np.isfinite(lk) else -p["sd"]
            if not p["new"] and O[i, s] - ep <= fl0:
                cash += out(s, i, O[i, s] * (1 - 2 * SD.EQ_SLIP), True, "stop/lock gap")
            elif L[i, s] - ep <= fl0:
                cash += out(s, i, (ep + fl0) * (1 - 2 * SD.EQ_SLIP), True,
                            "stop" if fl0 <= -p["sd"] + EPS else "lock")
            elif H[i, s] - ep >= p["T"]:
                cash += out(s, i, (ep + p["T"]) * (1 - SD.EQ_SLIP), False, "target")
            else:
                p["peak"] = max(p["peak"], H[i, s] - ep)
                lk = lockR(p, p["peak"])
                if np.isfinite(lk) and C[i, s] - ep <= lk:
                    cash += out(s, i, (ep + lk) * (1 - 2 * SD.EQ_SLIP), True, "lock")
        mv = 0.0
        for s, p in pos.items():
            if i > p["ei"] and np.isfinite(O[i, s]) and np.isfinite(C[i - 1, s]):
                p["gap"] += (O[i, s] - C[i - 1, s]) * p["sh"]
            p["new"] = False
            mv += p["sh"] * lastpx[i, s]
            if i - p["ei"] + 1 >= N:
                pend_out.add(s)
        eq[i] = cash + mv
        if i < T_ - 1:
            free = maxN - len(pos) + len(pend_out)
            if free > 0:
                cand = [s for s in np.flatnonzero(sig[i]) if s not in pos]
                if cand:
                    cand.sort(key=lambda s: -sc[i, s])
                    pend_in = [(s, sc[i, s], H[i, s], atr[i, s]) for s in cand[:free + 5]]
    for s, p in list(pos.items()):
        cash += SD._close_stock(p, s, T_ - 1, lastpx[T_ - 1, s], trades, P, False)
    return pd.Series(eq[i0:], index=P.dates[i0:]), pd.DataFrame(trades)


STK_APP = {f"app R: stop 1R(2ATR) / target 2R + ladder, max {N}d": N for N in (5, 10, 20)}


def momentum_signal(P):
    """SWING_DEEP section 5's 12-1 momentum as entries: every 20 sessions, the top 10 by 12-1 month return above the
    200DMA (score = 12-1 return)."""
    r121 = P.ind["ret_12_1"].values
    elv = P.ind["elig"].values & (P.C.values > P.ind["sma200"].values)
    sc = np.where(elv, np.nan_to_num(r121, nan=-9), -9)
    sig = np.zeros_like(elv)
    i0 = int(np.searchsorted(P.dates.values, np.datetime64(SD.START)))
    for i in range(i0 - 1, len(P.dates), 20):
        top = [s for s in np.argsort(-sc[i])[:10] if sc[i, s] > -9]
        sig[i, top] = True
    return sig, np.nan_to_num(r121, nan=-9)


def stocks_part(P, smap):
    S = SD.stock_signals(P, smap)
    msig, mscore = momentum_signal(P)
    S["12-1 momentum, top 10 every 20 sessions (>200DMA)"] = msig
    el = P.ind["elig"].values
    groups_all = {"all F&O": None}
    groups_pop = {"top-50 large caps": P.ind["large"].values, "other F&O (mid caps)": P.ind["mid"].values}
    rng = np.random.default_rng(31)
    years = list(range(2016, 2027))
    md = ["## 3-4. F&O stock portfolios and Boss's three set-ups with the app's exit structure in R", "",
          "Same universe, sizing (1% risk against a 2 x ATR stop, max 10 positions, 20% cap), costs and signals as "
          "SWING_DEEP sections 3-4. App structure in R: stop -1R (= 2 x ATR, resting), target +2R, ladder on the 2R "
          "target (+0.5R -> breakeven after charges, +1R -> lock +0.5R, +1.5R -> lock +1R), max hold 5 / 10 / 20 "
          "sessions. Daily bars only (stock minute data exists only from 2024): gap through the stop/lock fills at the "
          "open; when one daily bar touches both the stop/lock and the target, the stop is assumed to come first. "
          "'Original' = SWING_DEEP's exits re-run here. Random = the same number of random stocks from the same "
          "group bought on the same days, same exit (3 seeds). Rs/yr = CAGR x Rs 5 lakh; DD in % of the account.", ""]
    res = {}
    rows = []
    jobs = []
    classic = [k for k in S if k[:2] not in ("P1", "P2", "P3")]
    for rn in classic:
        exits = ["fixed 10d", "fixed 20d", "2ATR stop + 3ATR chandelier (max 20d)", "2ATR stop + 10d time stop"]
        if rn.startswith("Pullback") or rn.startswith("Mean"):
            exits.append("exit on close > 5DMA (max 10d), 3ATR disaster stop")
        jobs.append((rn, "all F&O", None, exits))
    for rn in [k for k in S if k[:2] in ("P1", "P2", "P3")]:
        for gn, gm in groups_pop.items():
            jobs.append((rn, gn, gm, ["fixed 5d", "fixed 10d", "2ATR stop + 3ATR chandelier (max 10d)",
                                      "2ATR stop + 10d time stop"]))
    for rn, gn, gm, exits in jobs:
        se = rn.startswith("NR7")
        score = mscore if rn.startswith("12-1") else None
        for en in exits:
            eqs, tr = SD.stock_sim(P, S[rn], SD.STK_EXITS[en], score=score, stop_entry=se, mask=gm)
            st = SD.eq_stats(eqs, tr)
            res[(rn, gn, en)] = st
            rows.append(_srow(rn, gn, en, st, tr))
        for en, N in STK_APP.items():
            eqs, tr = stock_sim_R(P, S[rn], N, score=score, stop_entry=se, mask=gm)
            st = SD.eq_stats(eqs, tr)
            res[(rn, gn, en)] = st
            rows.append(_srow(rn, gn, en, st, tr))
        # random stocks, same days / counts, same exits (fixed 10d and app R max 10d)
        base = el if gm is None else gm
        sg = S[rn] if gm is None else (S[rn] & gm)
        for en in ("fixed 10d", "app R: stop 1R(2ATR) / target 2R + ladder, max 10d"):
            cg, py = [], []
            for k in range(3):
                rsig = SD.random_like(sg, base, rng)
                rsc = rng.random(base.shape)
                if en == "fixed 10d":
                    e2, t2 = SD.stock_sim(P, rsig, SD.STK_EXITS[en], score=rsc, stop_entry=se)
                else:
                    e2, t2 = stock_sim_R(P, rsig, 10, score=rsc, stop_entry=se)
                s2 = SD.eq_stats(e2, t2)
                cg.append(s2["cagr"])
                py.append(s2["pos_years"])
            res[(rn, gn, "RANDOM " + en)] = (np.mean(cg), np.mean(py))
            rows.append([rn, gn, "RANDOM stocks, " + en, "", "", "", "", SD.pct(np.mean(cg)), SD.rs(np.mean(cg) * SD.CAP),
                         f"{np.mean(py):.1f}/11", "", ""])
        print(f"  stocks {rn} [{gn}] done {time.time() - T0:.0f}s", flush=True)
    md += [SD.table(["rule", "group", "exit", "trades", "win", "avg R", "PF", "CAGR", "Rs/yr on 5L", "years +", "max DD",
                     "app exit mix % stop/lock/target/time"], rows), ""]
    # walk-forward (SWING_DEEP 6c method) per exit family
    wrows = []
    for fam in ("original", "app R", "both"):
        cfg = {k: v["yret"] for k, v in res.items() if isinstance(v, dict) and len(k) == 3 and k[0] != "WF" and
               (fam == "both" or (fam == "app R") == k[2].startswith("app R"))}
        r_, oos = wf(cfg, 2019, fmt=SD.pct)
        res[("WF", fam)] = oos
        wrows.append([fam, len(cfg), wf_txt(oos, money=False), "; ".join(f"{r[0]}: {r[1]}" for r in r_)])
    md += ["Walk-forward on the stock configurations (each test year trades the config with the best summed return over "
           "all earlier years):", "", SD.table(["candidate exits", "configs", "out-of-sample compounded 2019-Oct 2026 "
                                                "(years +)", "picks"], wrows), ""]
    return md, res, jobs


def _srow(rn, gn, en, st, tr):
    s = st["st"]
    mix = ""
    if en.startswith("app R") and len(tr) and "why" in tr:
        w = tr.why.fillna("end").value_counts(normalize=True)
        mix = (f"{100 * (w.get('stop', 0) + w.get('stop/lock gap', 0)):.0f}/{100 * w.get('lock', 0):.0f}/"
               f"{100 * w.get('target', 0):.0f}/{100 * w.get('time', 0):.0f}")
    return [rn, gn, en, s["n"], s["win"], s["avgR"], s["pf"], SD.pct(st["cagr"]), SD.rs(st["cagr"] * SD.CAP),
            f"{st['pos_years']}/{st['n_years']}", SD.pct(st["dd"]), mix]

# ======================================================================================== OI confirmation


def oi_part(idx, fres, ores):
    md = ["## 6. Option-buyer rule B (OI confirmation) with the app's exits", "",
          "SWING_DEEP section 8's two OI filters applied to the trades taken with each exit (futures: app R max 10d vs "
          "fixed 10d; bought monthly ATM: original / app literal / scaled, max 10d). Kept = OI confirmed. Last column: "
          "years in which the kept trades beat the dropped ones per trade.", ""]
    for sym, d in idx.items():
        B = SD.book(sym, "MONTH")
        F = SD.oi_features(B)
        first = min(F) if F else SD.OPT_START[sym]
        pos = {x: i for i, x in enumerate(d.index)}
        rows = []
        for rn in ["Donchian 20-day breakout", "Pullback: >200DMA & RSI(2)<10", "EMA 20/50 trend",
                   "NR7 / inside-day breakout", "Mean reversion: buy after big down day", COIN]:
            cands = [("futures, fixed 10d", fres.get((sym, rn, "fixed 10d"), (None,))[0]),
                     ("futures, app R max 10d", fres.get((sym, rn, FUT_APP[1]), (None,))[0])]
            for f in ("orig", "app", "scaled"):
                o = ores.get((sym, "bought monthly ATM", rn, 10, f))
                cands.append((f"monthly ATM, {f}, max 10d", None if o is None else o[0]))
            for nm, tr in cands:
                if tr is None or len(tr) == 0:
                    continue
                tr = tr[tr.ed >= first].copy()
                sig_day = [d.index[pos[e] - 1] for e in tr.ed]
                for fn, fl in (("PCR rising/falling", lambda z, dr: z["dpcr"] * dr > 0),
                               ("writers' build-up", lambda z, dr: (z["dput"] - z["dcall"]) * dr > 0)):
                    ok = np.array([(sd in F) and bool(fl(F[sd], dr)) for sd, dr in zip(sig_day, tr.dir)])
                    K, R = tr[ok], tr[~ok]
                    ys = sorted(set(tr.year))
                    beat = sum(1 for y in ys if (K.year == y).any() and (R.year == y).any()
                               and K[K.year == y].net.mean() > R[R.year == y].net.mean())
                    rows.append([rn, nm, fn, len(tr), SD.rs(tr.net.sum()), len(K), SD.rs(K.net.sum()),
                                 SD.rs(R.net.sum()), f"{beat}/{len(ys)}"])
        md += [f"### {sym} (from {first.date()})", "",
               SD.table(["rule", "instrument / exit", "OI filter", "all trades", "net Rs", "kept", "net Rs kept",
                         "net Rs dropped", "years kept > dropped"], rows), ""]
    return md

# ======================================================================================== report


def sd_original(d, sym, rn, legs, N):
    """SWING_DEEP's own figure for the same rule (fixed N, monthly, with roll) - Rs per year per lot."""
    start = SD.OPT_START[sym]
    trs = SD.close_mode_trades(d, SD.index_signals(d)[rn], dict(kind="fixed", N=N), start)
    B = SD.book(sym, "MONTH")
    dates = list(d.index)
    tot = 0.0
    for t in trs:
        slot = "pm" if t["why"] == "end of data" else "am"
        r = SD.option_trade(B, dates, t["dir"], t["ed"], t["xd"], [(q, k, 0) for q, k in legs], roll=True, x_slot=slot)
        if r is not None:
            tot += r["net"]
    return tot / span_years(start)


def verdict_of(app_yr, coin_hi, wf_tot, pos, nyrs):
    if app_yr > 0 and app_yr > coin_hi and wf_tot > 0 and pos >= 0.6 * nyrs:
        return "candidate"
    if app_yr > 0 and wf_tot > 0:
        return "no (inside coin band)" if app_yr <= coin_hi else "weak"
    return "no"


def main():
    import pickle
    idx = {s: SD.load_index(s) for s in ("NIFTY", "BANKNIFTY")}
    stage = os.path.join(CACHE, "exits_stage.pkl")
    if "--resume" in sys.argv and os.path.exists(stage):
        with open(stage, "rb") as f:
            fmd, fres, fcoin, omd, ores, ocoin, emd, oimd, smd, sres, sjobs, st_ = pickle.load(f)
        STATS.update(st_)
        sjobs = [(rn, gn, None, ex) for rn, gn, _, ex in sjobs]
    else:
        print("futures...", flush=True)
        fmd, fres, fcoin = futures_part(idx)
        fmd += futures_minute_check(idx, fres)
        print("options...", flush=True)
        omd, ores, ocoin = options_part(idx)
        emd, eres = expiry_choice_part(idx)
        oimd = oi_part(idx, fres, ores)
        print("stocks...", flush=True)
        P = SD.Panel(idx["NIFTY"].index)
        smap = P.sector_map()
        smd, sres, sjobs = stocks_part(P, smap)
        with open(stage, "wb") as f:
            pickle.dump((fmd, fres, fcoin, omd, ores, ocoin, emd, oimd, smd, sres,
                         [(rn, gn, None, ex) for rn, gn, _, ex in sjobs], dict(STATS)), f)
    # ---------------- summary table: every strategy x {original, app, scaled, coin with the same exits}
    srows = []
    for sym, d in idx.items():
        for rn in SD.index_signals(d):
            o = fres[(sym, rn, "fixed 10d")][1]
            a = fres[(sym, rn, FUT_APP[1])][1]
            cg = fcoin[(sym, FUT_APP[1])][0]
            cfg_o = {en: fres[(sym, rn, en)][1]["by_year"] for en in FUT_ORIG}
            cfg_a = {en: fres[(sym, rn, en)][1]["by_year"] for en in FUT_APP}
            _, oo = wf(cfg_o, 2019)
            _, oa = wf(cfg_a, 2019)
            ay = a["st"]["tot"] / 10.75
            srows.append([f"{sym} futures", rn,
                          f"{SD.rs(o['st']['tot'] / 10.75)} / {SD.rs(o['dd'])} / {o['pos_years']}/11",
                          f"{SD.rs(ay)} / {SD.rs(a['dd'])} / {a['pos_years']}/11", "n/a",
                          f"{SD.rs(np.mean(cg))} (90%: {SD.rs(np.percentile(cg, 5))} .. {SD.rs(np.percentile(cg, 95))})",
                          f"{wf_txt(oo)} ; {wf_txt(oa)}",
                          "baseline" if rn.startswith(("Coin", "Always")) else
                          verdict_of(ay, np.percentile(cg, 95), sum(oa.values()), a["pos_years"], 11)])
        for sn, (kind, legs) in OPT_STRUCT.items():
            N0 = HEAD_HOLD[kind]
            start = SD.OPT_START[sym]
            first = start.year + 1 if start.month < 9 else start.year + 2
            for rn in SD.OPT_RULES:
                cells = []
                wfs = []
                for f in ("orig", "app", "scaled"):
                    s = ores[(sym, sn, rn, N0, f)][1]
                    cells.append(f"{SD.rs(s['yr'])} / {SD.rs(s['dd'])} / {s['pos']}")
                    cfg = {N: ores[(sym, sn, rn, N, f)][1]["by"] for N in HOLDS[kind]}
                    _, oos = wf(cfg, first)
                    wfs.append(oos)
                cs = ocoin[(sym, sn, "app")][0]
                cs2 = ocoin[(sym, sn, "scaled")][0]
                sa, ss = ores[(sym, sn, rn, N0, "app")][1], ores[(sym, sn, rn, N0, "scaled")][1]
                best = max((sa["yr"], np.percentile(cs, 95), sum(wfs[1].values()), sa["posn"]),
                           (ss["yr"], np.percentile(cs2, 95), sum(wfs[2].values()), ss["posn"]))
                nyrs = int(sa["pos"].split("/")[1] or 1) if sa["pos"] else 1
                if kind == "MONTH":
                    cells[0] += f" (SWING_DEEP, with roll: {SD.rs(sd_original(d, sym, rn, legs, N0))}/yr)"
                srows.append([f"{sym} {sn} (max {N0}d)", rn, cells[0], cells[1], cells[2],
                              f"app {SD.rs(np.mean(cs))} (90%: {SD.rs(np.percentile(cs, 5))} .. {SD.rs(np.percentile(cs, 95))}); "
                              f"scaled {SD.rs(np.mean(cs2))} (.. {SD.rs(np.percentile(cs2, 95))})",
                              " ; ".join(wf_txt(w) for w in wfs),
                              "baseline" if rn.startswith(("Coin", "Always")) else verdict_of(*best, nyrs)])
    for rn, gn, gm, exits in sjobs:
        o = sres[(rn, gn, "fixed 10d")]
        a = sres[(rn, gn, "app R: stop 1R(2ATR) / target 2R + ladder, max 10d")]
        rc = sres[(rn, gn, "RANDOM app R: stop 1R(2ATR) / target 2R + ladder, max 10d")]
        rco = sres[(rn, gn, "RANDOM fixed 10d")]
        cfg_o = {en: sres[(rn, gn, en)]["yret"] for en in exits}
        cfg_a = {en: sres[(rn, gn, en)]["yret"] for en in STK_APP}
        _, oo = wf(cfg_o, 2019, fmt=SD.pct)
        _, oa = wf(cfg_a, 2019, fmt=SD.pct)
        ok = a["cagr"] > rc[0] + 0.02 and np.prod([1 + v for v in oa.values()]) > 1 and a["pos_years"] >= 7
        srows.append([f"stocks [{gn}]", rn,
                      f"{SD.rs(o['cagr'] * SD.CAP)} / {SD.pct(o['dd'])} / {o['pos_years']}/11",
                      f"{SD.rs(a['cagr'] * SD.CAP)} / {SD.pct(a['dd'])} / {a['pos_years']}/11", "n/a",
                      f"random stocks: app {SD.rs(rc[0] * SD.CAP)}; fixed 10d {SD.rs(rco[0] * SD.CAP)}",
                      f"{wf_txt(oo, False)} ; {wf_txt(oa, False)}",
                      ("beats random, not buy&hold" if ok and a["cagr"] < SD.KEY.get("ew_cagr", 0.179) else
                       "candidate" if ok else "no")])
    summary = ["## Summary table: every SWING_DEEP strategy x exit", "",
               "Each cell = **Rs per year on Rs 5 lakh / max drawdown / years positive**. Futures and options: 1 lot "
               "(futures years 2016-Oct 2026; options NIFTY Aug-2020 on, BANKNIFTY Sep-2021 on, BANKNIFTY weeklies to "
               "Nov-2024). Representative hold: futures/stocks/monthly options max 10 sessions, weekly max 5. 'original' = "
               "the same time exit with no stop (stocks/futures: fixed 10 days). 'app rules' = options: the literal "
               "Jarvis -30 / +60 premium points + ProfitLock ladder; futures/stocks: the same 1 : 2 + ladder structure "
               "in R (1R = 2 ATR). 'scaled' = options only: -30% / +60% of premium + the same ladder. Coin = random "
               "entries (futures/options: coin-flip signals; stocks: random stocks on the same days) with the SAME exit. "
               "Walk-forward = choose the hold (and exit for 'original') on earlier years only, trade the next year "
               "blind; shown as out-of-sample total (years +): original ; app ; scaled (stocks: compounded %). "
               "Verdict 'candidate' needs: app/scaled Rs/yr > 0, above the coin band's 95th percentile, walk-forward "
               "> 0 and >= 60% of years positive.", "",
               SD.table(["instrument", "strategy", "original exit", "app rules", "scaled app rules",
                         "coin / random entries with same exits (Rs/yr)", "walk-forward", "verdict"], srows), ""]
    rules_md = RULES_MD
    stats = (f"Option legs priced from real minute bars: {STATS['real']:,}; Black-Scholes fallback (strike outside "
             f"the data's +/-10 window at the entry/time-exit snapshot): {STATS['model']:,} "
             f"({100 * STATS['model'] / max(1, STATS['model'] + STATS['real']):.1f}%). Signals skipped because the entry "
             f"day was the contract's own expiry day: {STATS['skip_expday']:,}; skipped by MIN_PREMIUM 35 (literal "
             f"rule): {STATS['skip_minprem']:,}; no price: {STATS['noprice']:,} (counts summed over all runs).")
    head = ["# Swing strategies with the app's own exits (stop, target, profit-lock ladder)", "",
            f"*Generated by `research/swing_exits.py` on {pd.Timestamp.today().date()} from the same data and code as "
            "SWING_DEEP.md (`swing_deep.py` is imported; only the exits change). A full run takes about 6 minutes; "
            "`--resume` re-renders from the cached stage results.*", "", VERDICT, "", rules_md, ""]
    caveats = ["## 7. Caveats", "",
               "- Option minute bars: the data has only the nearest weekly and nearest monthly series, +/-10 strikes "
               "around ATM. When the index moves more than 10 strikes, the held strike drops out of the window and "
               "those minutes are not seen (stops/targets can only trigger on minutes that exist). " + stats,
               "- Target fills at the target price even when a bar opens above it (as JarvisTrades.OptionSim); stop "
               "fills at the stop or the worse open. Option minute highs/lows include odd prints; the app's own "
               "simulator uses them too.",
               "- Futures/stocks use daily bars with a pessimistic order (stop before target inside a bar). Section "
               "1c measures the effect against minute bars for 2021-10 onwards.",
               "- No-roll: a monthly bought with fewer sessions to expiry than the hold is sold on expiry day; "
               "SWING_DEEP rolled it. The 'original' column in this report uses the same no-roll rule so the "
               "comparison with the app exits is like for like.",
               "- 'Scaled' and 'R' versions are adaptations, not the app's literal numbers - labelled as such.",
               "- Variants tried: exit families original / app literal / scaled / Pine trail (options), original / "
               "app R (futures, stocks), each with 2-3 max holds; nothing was tuned on the full sample - the app's "
               "numbers were used as-is, and holds are chosen only inside the walk-forward.",
               "- Two strategies named in the brief, '5-day momentum/reversal' and 'gap-down recovery', do not exist "
               "in swing_deep.py / SWING_DEEP.md, so they are not here. 12-1 momentum is run as entries every 20 "
               "sessions (top 10), so its 'original' (fixed 10d) differs from SWING_DEEP's rebalanced portfolio.",
               "- Stock universe has survivorship bias (today's F&O list); see SWING_DEEP 6e.", ""]
    md = head + summary + fmd + omd + smd + emd + oimd + caveats
    with open(OUT, "w") as f:
        f.write("\n".join(md) + "\n")
    with open(os.path.join(CACHE, "exits_res.pkl"), "wb") as f:
        pickle.dump(dict(srows=srows, stats=STATS,
                         fwf={k: v for k, v in fres.items() if k[1] == "WF"},
                         owf={k: v for k, v in ores.items() if k[2] == "WF"},
                         swf={k: v for k, v in sres.items() if k[0] == "WF"}), f)
    print("wrote", OUT, f"{time.time() - T0:.0f}s")


VERDICT = """## Verdict for Boss (plain language)

**Short answer: no.** Adding the app's stop loss, target and profit-lock ladder does not make any of the swing
strategies worth trading. What it does is **make the losses smaller and the bad days much less bad**, mainly on options.
The proof that it creates no edge is simple: when the same exits are put on **random coin-flip entries**, they do as
well as, or better than, the real strategies.

What it would have done on **Rs 5 lakh** (1 lot of futures or options, or a stock portfolio of 5 lakh):

| what you trade | before (SWING_DEEP's time exit) | with the app's stop + target + profit lock | coin-flip entries with the SAME exits | worth trading? |
|---|---|---|---|---|
| NIFTY / BANKNIFTY futures, 1 lot, 2016-2026 (app structure in R: stop 2 ATR, target 2x that, ladder) | -Rs 75k to +Rs 42k a year; worst drawdown -Rs 2.4 to -9.9 lakh | best rows +Rs 10k to +18k a year (NIFTY NR7 / inside-day +Rs 14k, 8 of 11 years; BANKNIFTY big-down-day bounce +Rs 18k, 7 of 11); worst drawdown still -Rs 2.6 to -10 lakh | random entries: -Rs 44k to +Rs 25k a year (NIFTY), -Rs 75k to +Rs 65k (BANKNIFTY); one coin flip made +Rs 15k / +Rs 30k | **No** - every app-exit row sits inside the coin-flip range; picking the best app-exit rule on past years and trading it the next year lost Rs 2.3 lakh (NIFTY) and Rs 3.8 lakh (BANKNIFTY) over 2019-2026 |
| Bought monthly ATM options, the app's literal rule (-30 / +60 premium points + ladder) | -Rs 85k to +Rs 29k a year; worst drawdown up to -Rs 4.8 lakh | -Rs 0.2k to -Rs 33k a year, almost all slightly negative; worst drawdown only -Rs 13k to -Rs 1.9 lakh; positive in 0-4 of 6-7 years | -Rs 7.5k a year on average (90% of coin flips between -Rs 12k and -Rs 2k) | **No** - the 30-point stop is tiny for a swing option (3-13% of a 250-1,000 premium), so it fires on noise; every rule ends up near the coin-flip result: a small, steady loss |
| Bought monthly ATM, scaled rule (-30% / +60% of premium + ladder) | same as above | -Rs 70k to +Rs 11.5k a year; only NIFTY RSI(2) pullback (+Rs 7.6k, 4 of 7 years) and BANKNIFTY big-down-day bounce (+Rs 11.5k, 2 of 6) above zero | -Rs 10k to -Rs 20k a year on average, top 5% up to +Rs 14.5k | **No** - the NIFTY row is inside the coin band; the BANKNIFTY row was positive in only 2 of 6 years, and the walk-forward never picked it (it stayed out) |
| Bought weekly ATM options | -Rs 113k to +Rs 13k a year | -Rs 21k to +Rs 2k a year | -Rs 4k to -Rs 9k a year on average | **At most a paper trade:** 'buy the ATM call the day after a big down day' made **+Rs 2k a year** on NIFTY with the literal rule (5 of 7 years, worst drawdown -Rs 8.5k) and +Rs 6.7k on BANKNIFTY with the scaled rule (3 of 4 years). That is 0.4-1.3% a year on 5 lakh, from one of hundreds of rows tried |
| Debit spreads (monthly, sell 2 or 4 strikes out) | +Rs 0.3k to -Rs 19k a year | worse: -Rs 3k to -Rs 72k a year | -Rs 12k to -Rs 18k a year | **No** |
| F&O stock portfolios and Boss's three set-ups (app structure in R) | -Rs 34k to +Rs 1.06 lakh a year (-7% to +21%) | **worse in every one of the 24 portfolios**: -Rs 97k to +Rs 12.7k a year; drawdowns -23% to -90% | random stocks with the same exits: -Rs 7k to -Rs 97k a year | **No** - on stocks the exits lose money by themselves. They cut winners at breakeven and pay about 0.6% in costs on every extra round trip. Walk-forward: +100% over 2019-Oct 2026, against +453% with the original exits and +327% from just holding the stocks |

How often does it work? With the app's exits, options were profitable in **0 to 4 of 6-7 years** (most rows 0-2).
Futures were profitable in 2-8 of 11 years, the same as coin flips (4-6). Stocks were profitable in 0-6 of 11 years.

What the exits ARE good for: **damage control.** On a bought monthly option, the literal 30 / 60 rule with the
ladder cut the worst drawdown from -Rs 0.8 to -4.8 lakh down to -Rs 0.1 to -1.9 lakh. If Boss trades swing options
anyway, the app's rule makes losing slower and smaller. It does not turn losing into winning. On stocks, do not add
them: the plain 10-20 day hold was better.

How this was tested (no tuning): the app's numbers were used exactly as written in the code (below). Two adaptations
were added and labelled: a % version for options, and an R version for futures and stocks. One extra app rule, the
Pine % trail, was also tried. Each was run with 2-3 maximum holds, which gives about 1,400 rule x exit x hold x
instrument configurations. Holds and rules were only ever chosen inside the walk-forward. Options were simulated
minute by minute on real option prices over the whole multi-day hold (overnight gaps fill at the worse open).
Futures and stocks used daily bars with the stop assumed to come first; a minute-bar check for 2021-26 moved
results both ways by up to about Rs 1.7 lakh per rule over 5 years.
"""

RULES_MD = """## The app's exit rules used (quoted from the code)

| source | rule | value used |
|---|---|---|
| `android/engine/.../orb/ProfitLock.kt` `LADDER` | profit-lock ladder: (share of target reached -> share of target locked) | 25% -> 0% (breakeven after charges), 50% -> 25%, 75% -> 50%; a rung counts from the next price after the peak that earned it; never below breakeven + round-trip charges |
| `android/ira-core/.../JarvisTrades.kt` | `STOP_POINTS` / `TARGET_POINTS` | resting stop 30 premium points below the price paid (tick 0.05), target +60 points (1 : 2), ladder measured on the 60 (so +15 -> breakeven, +30 -> lock +15, +45 -> lock +30) |
| same | `MIN_PREMIUM` | an option at 35 or less is not bought |
| same, `OptionSim` | fills | stop/lock checked before target in each minute; a minute that opens below the stop fills at its open; target fills at the target |
| `android/app/.../ira/IraNewsTrades.kt` (doc) | news trades | ATM option, the next expiry after today, the Jarvis 30 / 60 rules and the ladder on the 60 (same numbers as above) |
| `engine/.../orb/OrbRules.kt` | ORB arms | stop 40 / target 40 points + the same ladder (not used: the swing study takes the Jarvis 30 / 60 numbers) |
| `engine/.../orb/LiquidityRules.kt` (Liquidity 15+5) | stop / target / trail | resting stop 15% below the premium paid; index stop 30 points back through the level; time stop +5% after 20 min; target = the next liquidity level; no profit-lock ladder ("Liquidity 15+5 has no fixed target ... so it is not laddered") - intraday-only and level-based, so it does not translate to a swing hold; not tested |
| `ProfitLock.Trail` (Pine scripts, default) | percentage trail on the premium | +5% -> breakeven after charges; from +8% keep 50% of the best gain, +20% keep 65%, +40% keep 75% (tested on options as 'Pine % trail', no stop, no target) |
| chandelier | - | no chandelier exit exists in the app code (only in research/CHANDELIER_EXIT.md); SWING_DEEP's 2ATR + 3ATR chandelier stays in the 'original' column |

**Adaptations (not literal app rules, labelled 'scaled' / 'R'):** options - stop -30% of premium, target +60%, the same
ladder (+15% -> breakeven, +30% -> lock +15%, +45% -> lock +30%). Futures and stocks - stop -1R where 1R = 2 x ATR14
(SWING_DEEP's stop distance), target +2R, ladder at +0.5R / +1R / +1.5R -> lock breakeven / +0.5R / +1R. Every
version keeps a max-hold time stop (5 / 10 / 20 sessions; weekly options 3 / 5) as a backstop.
"""

if __name__ == "__main__":
    main()
