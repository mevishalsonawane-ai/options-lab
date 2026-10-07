"""h19: the app's arms replayed with the engine's exact rules, the app's paper fills (+-5 bps, stops -10 bps) and
SandboxCosts, 1 lot each, with a minute-by-minute mark-to-market path per trade.

ORB family (BANKNIFTY): a line-by-line port of android/.../orb/ArmsBacktest.kt + OrbRules / SweepRules / RangeFadeRules
+ ProfitLock (plain ladder):
  5-min bars folded from index minutes (start 09:15..15:25); opening range = bars 09:15..10:00 (needs the 10:00 bar);
  strike = ATM (half-up, 100) of the first bar at/after 09:20's close; contract = nearest listed expiry ON or AFTER the
  day (same-day on expiry days), nearest recorded strike;
  ORB / ORB Fresh: bars labelled after 10:00 and before 14:00; close beyond the range; Fresh needs the previous bar not
  beyond on the same side; unlimited entries; Sweep: after 10:00 and before 14:30, high > ORH & close < ORH -> PE,
  low < ORL & close > ORL -> CE, max 2/day; Range Fade: 10:30 .. before 14:00, edge 10% of the range, max 2/day;
  cooldown: a bar may decide only if it starts after the bar holding the last exit;
  entry: the option's first recorded minute at/after the signal bar's end (refused if >= 15:10 or premium-40 <= 0);
  exits per minute (entry minute included): stop (low <= entry-40; trigger on the 0.05 tick), profit-lock (ladder from
  the peak BEFORE this minute), target (+40, Sweep +80), square-off 15:10.
Fills: buy = open x (1+5bps); stop = min(trigger, open) x (1-10bps); lock = min(lock, open) x (1-5bps);
target = max(target, open) x (1-5bps); square-off = 15:10 open x (1-5bps). Charges: SandboxCosts ('app').

Liquidity 15+5 (BANKNIFTY 15m+5m, FINNIFTY 30m+5m): h4's port (comps.liq_ext_signals, signal-identical to the validated
port) through obuy's engine with the app execution (validated to the paisa), then paths rebuilt from the chain.

    flock <scratch>/obuy.lock python3 -I research/hunt/h19/sim.py
Output: <scratch>/hunt/h19/trades.parquet (one row per trade) and paths.pkl ({trade id: (cols, closes)}).
"""
from __future__ import annotations

import dataclasses
import glob
import math
import os
import pickle
import sys
import time
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))

import obuy  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, Fills, adverse_bps  # noqa: E402
from obuy.data import market, lot_from_oi  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h19")
EPS = 1e-9
SQ = 15 * 60 + 10
COST = Costs("app")
LADDER = ((0.25, 0.0), (0.50, 0.25), (0.75, 0.50))
LADDER_ON = True


def supplement(mk, und):
    """Add days present in Dhan's IDX_I minutes but missing from the jx index cache (e.g. 6 Oct 2026, partial)."""
    ix = mk.index(und)
    f = os.path.join(C.DATA, "candles", "minute", "IDX_I", und, "2026.parquet")
    x = pd.read_parquet(f, columns=["ts", "open", "high", "low", "close"])
    x["ts"] = x.ts.dt.tz_localize(None)
    x["day"] = x.ts.dt.date
    x["m"] = x.ts.dt.hour * 60 + x.ts.dt.minute
    x = x[(x.m >= C.OPEN_M) & (x.m <= C.LAST_M)].drop_duplicates(["day", "m"]).sort_values(["day", "m"])
    opts = mk.options(und)
    added = []
    for d, g in x.groupby("day"):
        if d in ix.d or d <= ix.days[-1]:
            continue
        ch = opts.chain(d, "near")
        if ch is None:
            continue
        lot = lot_from_oi(ch) or ix.d[ix.days[-1]]["lot"]
        o, h, l, c = (np.round(g[k].values.astype(float), 2) for k in ("open", "high", "low", "close"))
        ix.d[d] = dict(lot=int(lot), exp=False, m=g.m.values.astype(int), o=o, h=h, l=l, c=c, real=True, partial=len(g) < 370)
        added.append((d, len(g)))
    ix.days = sorted(ix.d)
    ix.pos = {d: i for i, d in enumerate(ix.days)}
    ix._mat = None
    ix._daily = None
    return added


# ------------------------------------------------------------------ ORB family
def five(x):
    """ArmsBacktest.fiveMinute: [(start_min, o, h, l, c)]."""
    m, o, h, l, c = x["m"], x["o"], x["h"], x["l"], x["c"]
    out = []
    i, n = 0, len(m)
    while i < n:
        s = m[i] - m[i] % 5
        hi, lo, op, cl = -np.inf, np.inf, o[i], c[i]
        while i < n and m[i] - m[i] % 5 == s:
            hi = max(hi, h[i]); lo = min(lo, l[i]); cl = c[i]; i += 1
        if 555 <= s <= 925:
            out.append((s, op, hi, lo, cl))
    return out


def lock_level(e, tgt, peak):
    lv = [e + lk * tgt for reach, lk in LADDER if peak >= e + reach * tgt - EPS]
    lv = [v for v in lv if v < peak]
    return max(lv) if lv else None


def stop_trigger(e):
    lvl = e - 40.0
    if lvl <= EPS:
        return None
    return round(round(lvl / 0.05) * 0.05, 2)


def bps(p, b, buy):
    return float(adverse_bps(np.array([p]), b, buy)[0])


def fill(leg, frm, tgt):
    """leg = (o, h, l, c) dense W arrays. Returns (entry_col, exit_col, entry, exit, why) or None."""
    o, h, l, c = leg
    rec = np.nonzero(~np.isnan(o))[0]
    rec = rec[rec >= frm - C.OPEN_M]
    if not len(rec):
        return None
    i0 = rec[0]
    if i0 + C.OPEN_M >= SQ:
        return None
    e = bps(o[i0], 5, True)
    st = stop_trigger(e)
    if st is None:
        return None
    peak = e
    for i in rec:
        m = i + C.OPEN_M
        if m >= SQ:
            return i0, i, e, bps(o[i], 5, False), "session_end"
        if l[i] <= st + EPS:
            return i0, i, e, bps(min(st, o[i]), 10, False), "stop"
        lk = lock_level(e, tgt, peak) if LADDER_ON else None
        if lk is not None and l[i] <= lk + EPS:
            return i0, i, e, bps(min(lk, o[i]), 5, False), "profit_lock"
        if h[i] >= e + tgt - EPS:
            return i0, i, e, bps(max(e + tgt, o[i]), 5, False), "target"
        peak = max(peak, h[i])
    i = rec[-1]
    return i0, i, e, bps(c[i], 5, False), "last_bar"


ARMS = ("orb", "orb_fresh", "orb_sweep", "range_fade")


def signal(arm, bars, k, rng, last_exit_bar, n_today):
    s = bars[k][0]
    if arm in ("orb", "orb_fresh"):
        if not (600 < s < 840):
            return 0
    elif arm == "orb_sweep":
        if not (600 < s < 870):
            return 0
        if n_today >= 2:
            return 0
    else:
        if not (630 <= s < 840):
            return 0
        if n_today >= 2:
            return 0
    if last_exit_bar is not None and not s > last_exit_bar:
        return 0
    orh, orl = rng
    _, o, h, l, c = bars[k]
    if arm in ("orb", "orb_fresh"):
        d = 1 if c > orh else -1 if c < orl else 0
        if d and arm == "orb_fresh" and k > 0:
            pc = bars[k - 1][4]
            if (1 if pc > orh else -1 if pc < orl else 0) == d:
                return 0
        return d
    if arm == "orb_sweep":
        if h > orh + EPS and c < orh - EPS:
            return -1
        if l < orl - EPS and c > orl + EPS:
            return 1
        return 0
    w = orh - orl
    if w <= EPS:
        return 0
    if h >= orh - 0.1 * w - EPS and c < orh - EPS:
        return -1
    if l <= orl + 0.1 * w + EPS and c > orl + EPS:
        return 1
    return 0


def orb_day(mk, d):
    ix = mk.index("BANKNIFTY")
    x = ix.d[d]
    if not x.get("real", True):
        return []
    bars = five(x)
    win = [b for b in bars if 555 <= b[0] <= 600]
    if not win or win[-1][0] != 600:
        return []
    rng = (max(b[2] for b in win), min(b[3] for b in win))
    sb = next((b for b in bars if b[0] >= 560), None)
    if sb is None:
        return []
    strike = int(math.floor(sb[4] / 100 + 0.5) * 100)
    ch = mk.options("BANKNIFTY").chain(d, "near")
    if ch is None:
        return []
    legs = {}
    for r in ("C", "P"):
        has = ~np.all(np.isnan(ch.o[r]), axis=1)
        ks = ch.K[has]
        if not len(ks):
            return []
        k = int(ks[np.argmin(np.abs(ks - strike))])
        i = ch.kpos(k)
        legs[r] = (k, (ch.o[r][i], ch.h[r][i], ch.l[r][i], ch.c[r][i]))
    lot = ix.lot(d)
    out = []
    for arm in ARMS:
        tgt = 80.0 if arm == "orb_sweep" else 40.0
        last_exit_bar, n = None, 0
        for k in range(len(bars)):
            dr = signal(arm, bars, k, rng, last_exit_bar, n)
            if not dr:
                continue
            r = "C" if dr > 0 else "P"
            kk, leg = legs[r]
            f = fill(leg, bars[k][0] + 5, tgt)
            if f is None:
                continue
            i0, i1, e, xp, why = f
            bc = COST.charge_exact(True, e, lot)
            sc = COST.charge_exact(False, xp, lot)
            gross = (xp - e) * lot
            out.append(dict(und="BANKNIFTY", day=d, arm=arm, book=arm, sig_bar=bars[k][0], entry_min=i0 + C.OPEN_M,
                            exit_min=i1 + C.OPEN_M, side=dr, strike=kk, lot=lot, entry=e, exit=xp, why=why,
                            gross=gross, buy_chg=bc, charges=bc + sc, net=gross - bc - sc,
                            exp=bool(x.get("exp", False)), series=ch.series,
                            path=(leg[3][i0:i1 + 1].copy(), leg[0][i0:i1 + 2].copy() if i1 + 1 < C.W else leg[0][i0:i1 + 1].copy())))
            last_exit_bar = (i1 + C.OPEN_M) - (i1 + C.OPEN_M) % 5
            n += 1
    return out


# ------------------------------------------------------------------ Liquidity 15+5 (BN, FIN) via h4 port + obuy engine
def liquidity(mk, room=1.0, money=1):
    import comps
    from obuy.engine import positions, prepare_many
    from obuy.strategies import liquidity as LQ
    from obuy.engine import Execution, StrikeRule
    sig = comps.liq_ext_signals(mk, unds=("BANKNIFTY", "FINNIFTY"), room=room)
    sig["book"] = sig.book.str.replace("_BANKNIFTY", "_BN").str.replace("_FINNIFTY", "_FIN")
    if money != 1:
        sig = sig.drop(columns=["strike"])          # let StrikeRule pick (the port pins 1-ITM)
    exe = Execution(expiry="skip", fills=Fills(), costs=Costs())
    [(pk, _)] = prepare_many([(sig, StrikeRule(money=money), exe, 0, (comps.WIN_FROM - 1, comps.WIN_TO - 1), False)])
    tr = pk.run(LQ.ARM_EXITS, exe)
    tr = positions(tr, one_at_a_time=True)
    return sig, tr


def liq_paths(mk, tr):
    rows = []
    for t in tr.itertuples():
        d = pd.Timestamp(t.day).date()
        ch = mk.options(t.und).chain(d, "near")
        r = "C" if t.side > 0 else "P"
        i = ch.kpos(int(t.strike))
        i0, i1 = int(t.entry_min) - C.OPEN_M, int(t.exit_min) - C.OPEN_M
        c = ch.c[r][i][i0:i1 + 1].copy()
        o = ch.o[r][i][i0:min(i1 + 2, C.W)].copy()
        bc = COST.charge_exact(True, float(t.entry), int(t.qty))
        rows.append(dict(und=t.und, day=d, arm="liq_" + ("bn" if t.und == "BANKNIFTY" else "fin"), book=t.book,
                         sig_bar=int(t.sig_min), entry_min=int(t.entry_min), exit_min=int(t.exit_min), side=int(t.side),
                         strike=int(t.strike), lot=int(t.qty), entry=float(t.entry), exit=float(t.exit), why=t.why,
                         gross=float(t.gross), buy_chg=bc, charges=float(t.charges), net=float(t.net), exp=False,
                         series=ch.series, path=(c, o)))
    return rows


def main():
    os.makedirs(OUT, exist_ok=True)
    t0 = time.time()
    mk = market()
    for u in ("BANKNIFTY", "FINNIFTY"):
        print("supplement", u, supplement(mk, u), flush=True)
    rows = []
    ix = mk.index("BANKNIFTY")
    for d in ix.days:
        if mk.options("BANKNIFTY").resolve(d, "near") is None:
            continue
        rows += orb_day(mk, d)
    print("orb rows", len(rows), time.time() - t0, flush=True)
    sig, tr = liquidity(mk)
    sig.to_pickle(os.path.join(OUT, "liq_signals.pkl"))
    print("liq trades", len(tr), tr.net.sum(), time.time() - t0, flush=True)
    rows += liq_paths(mk, tr)
    paths = {}
    for i, r in enumerate(rows):
        paths[i] = r.pop("path")
        r["tid"] = i
    df = pd.DataFrame(rows)
    df["day"] = pd.to_datetime(df["day"])
    df.to_parquet(os.path.join(OUT, "trades.parquet"))
    with open(os.path.join(OUT, "paths.pkl"), "wb") as f:
        pickle.dump(paths, f)
    print(df.groupby("arm").net.agg(["count", "sum"]), time.time() - t0)


if __name__ == "__main__":
    main()
