"""R52 family 'misc': older rejected index-option rules from many studies, as signal functions for r52lab (PREREG.md,
FAMILY_BRIEF.md). Written 15 Oct 2026, before any R52 P&L of these rules.

Every rule's signal is read on the UNDERLYING index (CLAUDE.md); the option is only bought. Each function reuses the
original study's own code where it is a pure function of index bars (loaded by file path under a private module name,
so the many studies' same-named helper modules never collide), and ports the rest line by line with the source cited.

Keys and their original settings (one line each; the SPECS below carry the exact values):
  R20C-1   piv5n2, BANKNIFTY: floor-pivot R1/S1 break confirmed by 2 closing 5-min candles, by 14:00, ATM, -40/+40
           premium pts + ProfitLock ladder on the target, 15:10 (hunt/r20c/trades.py RULES r20brk-piv5n2, ARM)
  R20C-1N  the same rule's NIFTY half (-20/+20 pts): obuy exits are one setting a rule, so the per-index premium points
           of the ORB arm need their own spec (same catalogue rule R20C-1; report the two together)
  R20C-3   early15: R1/S1/PDH/PDL break, 2 closing 15-min candles, by 10:30, 1-ITM, Liquidity exits (-15%, 20 min
           not +5%, failed break on a 15-min close, 15:10), BANKNIFTY + NIFTY
  R20C-4   lowvix15: R1/S1 break, 2 closing 15-min candles, by 14:00, previous VIX close < 13, Liquidity exits
  H18-1    rolling 60-min high/low break (1-min close beyond by > 0.05%), 1-ITM, Liquidity exits + 30% target, index
           stop = level -/+ arm size, <= 3/day, 09:45-14:30, skip expiry, 5 indices (hunt/h18/rule.py, analyze.py)
  H43-2    BN Bollinger(20, 2) 1-min close outside -> CE / PE, ATM, -30%/+60%, 15-min time stop, 15:15, 2/day
  H43-3    BN afternoon PE: 5-min bar closing >= 14:00 with 15 <= ADX14 <= 25, ATM, -30/+60%, 30-min stop, 2/day
  H43-5    MIDCP ATM, BB(20, 2.5) 3-min close outside + option-OI build-up agrees, -30%/+60% to 15:15, 1/day
  H43-6    MIDCP 1-ITM, EMA 5/21 cross + ADX14 > 15 (5-min), 'wide day' filter, -30%/+60% to 15:15, 1/day
  H43-7    FINNIFTY 1-ITM, PE from 13:30, ADX 20-30 (5-min), VIX-high filter, -30%/+60% to 15:15, 4/day
  H46-2    MIDCP 15-min VWAP (TWAP) reject R1, ATM, exit on a 15-min CLOSE beyond EMA9, 15:10, no cap, skip expiry
  TREND_FILTER-1  BN 5-min, bar low above EMA9 and TWAP + 3 HH/HL -> ATM CE (mirror PE), sideways filter on,
           -40/+40 premium pts, <= 2/day, 09:30-14:25 bars, 15:10
  REVERSAL_SETUP-1  BN: first 5-min close 09:45-13:00 >= 0.3% below the open and the TWAP, low >= 15 min old, green
           candle -> the CE nearest the money whose lot fits Rs 20k (mirror PE), +10% target, no stop, 15:10
  CH-1     Chandelier Exit (ATR 7 x 2, close extremes) on BN 60-min: flip -> ATM CE / PE, 09:20-14:30 entries, SL 1 ATR /
           TP 1R on the index, out on the opposite signal, 15:10 (Boss's settings)
  TAS_OPTIONS-1  TAS tracker (ATR 19) on NIFTY 30-min: turn within 10 candles + score >= 50% -> ATM CE / PE, stop =
           tracker line, profit lock 1 / 3 ATR, out on a tracker turn, 15:10 (see the note on targets below)
  ST-1     Boss's Pine Supertrend(10, 3) + EMA50 on BN 5-min, ATM, reverse on the opposite signal, no stop / target,
           15:15 (Boss's exact settings)
  MP-1     Boss's Pine Matrix Premium v2.2 on BN 5-min: close crosses EMA20 -> ATM, index SL 2.7 x ATR14, TP 2.5R,
           no signals 11:00-13:15, 15:15 (Boss's exact settings)
  MV-1     Matrix v2.2 risk engine + structure-break entry, BN 5-min, SL pivot -/+ 2 ATR, TP1 1R half + breakeven, rest
           locked at 2R to 4R, CHoCH against = out, 15:10, 2 lots (each half is one 1-lot row: see the note)

Deviations forced by R52's harness (stated, not hidden):
  * Expiry days are SKIPPED for every rule. R20C, TREND_FILTER, REVERSAL_SETUP, CH, TAS, ST, MP and MV traded on expiry
    days in the NEXT expiry's contract; the lake has only the rolling nearest series (its expiring contract on that
    day), so the faithful contract does not exist there.
  * Index reference levels (CH stop/target, TAS R, MP and MV risk) use the index CLOSE of the signal minute, not the
    open of the fill minute the originals read (that open is one minute after the decision).
  * Structural exits that the originals took from the index path (opposite signal, failed break, EMA close-through,
    tracker turn, trend exit, index locks) are passed as `exit_at` (the engine sells at that minute's open). They are
    exits, so they may use bars after the entry; every ENTRY uses bars up to its sig_min only.
  * TAS_OPTIONS-1: the original's close row ("targets 1.5/2.5/3.5 R in thirds + lock 1/3", +54.1k, 209 tr) sells
    THIRDS of a lot, which one held contract with one exit cannot do. The same study's no-target row with the same
    lock (+51.5k, 209 tr; tas_options.py `run(..., tps=())`) is taken as the original instead.
  * MV-1 trades 2 lots and sells half at TP1: emitted as two 1-lot rows per signal (tag 'half A' = out at TP1 or the
    first full exit, 'half B' = the rest), so R52's trade count is TWO per signal for this rule.
  * The ORB floor (no entry under Rs 40 / Rs 20 premium) of R20C-1 is not applied (obuy has one min_premium per run).

LEFT (not testable faithfully here):
  * R20C-1's twin R20C-2 (piv5n1) is its grid point g01, not a separate key.
  * H18 gamma-filtered variants (Aneg / Blow) need the GEX panel of another session: not in the grid.
  * H43-5 'monthly ATM': the lake's MIDCP WEEK series is the monthly contract in this window (r52cfg), so 'near' = it.
  * H46-2 STK (stock-volume VWAP) and FUT variants: no constituent / futures minutes in the lake; TWAP only.
  * MP-1's 'TP1 partial, 2 lots' grid rows: the same half-lot issue as MV; left out of MP's grid.
  * TAS_INDICES-1 (index-points study, FIN 30m both ways with day-end exits) is covered only through TAS's FINNIFTY
    grid point (options, same tracker rule).

Run: OBUY_DATA=... OBUY_CACHE=... python3 research/hunt/r52/r52study.py design R20C-1 ...  (after r52rules registers it)
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
RES = HERE.parents[1]                       # research/
ROOT = RES.parent
for _p in (ROOT, RES, HERE):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

from obuy.engine import LADDER  # noqa: E402

OPEN = 555                                  # 09:15
W = 375
SQ10, SQ15 = 15 * 60 + 10, 15 * 60 + 15
EMPTY = ["und", "day", "sig_min", "side"]


# ------------------------------------------------------------------------------------------------ helpers
def _mod(name: str, rel: str):
    """Load an original study's module by file path under a private name (studies reuse names like config / sigs)."""
    key = f"_r52misc_{name}"
    if key in sys.modules:
        return sys.modules[key]
    path = RES / rel
    if str(path.parent) not in sys.path:
        sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location(key, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[key] = m
    spec.loader.exec_module(m)
    return m


def dense(ix) -> dict:
    """(D, 375) o/h/l/c of every day, the close forward-filled over the continuous series and o/h/l filled with it (the
    originals' minute grids: ml_long.grid / R17 Day.ix). Only earlier minutes ever fill a gap."""
    M = ix.mat()
    D = M["c"].shape[0]
    c = pd.Series(M["c"].reshape(-1)).ffill().values.reshape(D, W)
    out = {"c": c}
    for k in "ohl":
        a = np.array(M[k], dtype=float, copy=True)
        bad = np.isnan(a)
        a[bad] = c[bad]
        out[k] = a
    return out


def day_dicts(ix) -> list:
    """The script studies' day records: [dict(day, I=dict(open, high, low, close))] (sell_levels.load without options)."""
    X = dense(ix)
    return [dict(day=d, I=dict(open=X["o"][i], high=X["h"][i], low=X["l"][i], close=X["c"][i]), chain={})
            for i, d in enumerate(ix.days)]


def _bars(days, tf):
    """matrix_v22.bars (= liquidity_break.bars / st_ema_pine.bars): tf candles from 09:15 with di, s, e, o, h, l, c, atr."""
    if not days:
        return pd.DataFrame(columns=["di", "s", "e", "o", "h", "l", "c", "atr"])
    return _mod("matrix_v22", "matrix_v22.py").bars(days, tf)


def _frame(rows, cols=None) -> pd.DataFrame:
    if not rows:
        return pd.DataFrame(columns=cols or EMPTY)
    out = pd.DataFrame(rows)
    return out.sort_values(["und", "day", "sig_min", "side"], kind="stable").reset_index(drop=True)


def _cside(s):
    return "C" if s > 0 else "P"


# ================================================================================================ R20C (hunt/r20c)
def r20c_break(mk, types=("PIV",), tf=5, n=2, last_entry=(14, 0), vix_max=None, liq_exit=False,
               unds=("BANKNIFTY", "NIFTY")):
    """hunt/r20c/trades.py `signals` (+ `last_row`, `passes_filters`, `DayLv` for the daily level types) on obuy's index
    minutes: the first confirmed n-close break of R1 (up) / S1 (down) (and PDH / PDL with 'PDHL') per side and day, its
    confirming candle closing by last_entry. events.first_break and levels.piv / pdhl / candles are imported unchanged.
    liq_exit: the Liquidity family's failed-break exit (trades.sim_liq: a tf candle closing back beyond the level ->
    out at the next minute's open) as exit_at; its -15% / 20-min time stop are the engine's Exits."""
    EV = _mod("r20c_events", "hunt/r20c/events.py")
    LV = _mod("r20c_levels", "hunt/r20c/levels.py")
    h, m = last_entry
    last = h * 60 + m - OPEN - 1                       # trades.last_row
    rows = []
    for und in unds:
        ix = mk.index(und)
        if not len(ix.days):
            continue
        X = dense(ix)
        A = np.stack([X["o"], X["h"], X["l"], X["c"]], axis=2)          # (D, 375, 4) = R17 Day.ix per day
        for i in range(15, len(ix.days)):                                # study.contexts: from the 16th session
            d = ix.days[i]
            today, prev = A[i], A[i - 1]
            if np.isnan(today).any() or np.isnan(prev).any():
                continue
            if vix_max is not None:
                v = mk.vix.prev_close(d)
                if not (np.isfinite(v) and v < vix_max):                # study.vix_bucket '<13'
                    continue
            fixed = []
            for t in types:
                fixed += LV.piv(prev) if t == "PIV" else LV.pdhl(prev)
            lv = lambda b, fx=fixed: [x for x in fx if b >= x[3]]       # noqa: E731  (DayLv.__call__)
            cd = LV.candles(today, tf)
            for side in (1, -1):
                ev = EV.first_break(cd[:, 3], cd[:, 0], float(prev[-1, 3]), side, lv, n, tf)
                if ev is None:
                    continue
                row = (ev["e"] + 1) * tf - 1
                if row > last:
                    continue
                r = dict(und=und, day=d, sig_min=OPEN + row, side=side, book=f"r20c_{und}_{_cside(side)}",
                         exit_at=np.nan, tag=ev["key"])
                if liq_exit:
                    lvl = ev["level"]
                    for rr in range(row + 1, 355):                      # trades.sim_liq: failed break on a candle close
                        if (rr + 1) % tf == 0 and side * (today[rr, 3] - lvl) < 0:
                            r["exit_at"] = float(OPEN + rr + 1)
                            break
                rows.append(r)
    return _frame(rows)


# ================================================================================================ H18 (hunt/h18)
H18_ISTOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "NIFTY": 15.0, "SENSEX": 50.0, "MIDCPNIFTY": 8.0}  # rule.py ISTOP


def h18_break(mk, levels="R60", unds=("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY")):
    """hunt/h18/analyze.py `sweep_events` (imported unchanged; its index cache filled from mk) -> rule.py `signals`:
    'break' events (1-min close beyond the level by > 0.05%), levels R60 (rolling 60-min high / low, 30-min cool-down) or
    ALL (+ PDH / PDL / 09:15-09:45 range), 09:45-14:30, index stop = level -/+ ISTOP."""
    A = _mod("h18_analyze", "hunt/h18/analyze.py")
    rows = []
    for und in unds:
        ix = mk.index(und)
        if len(ix.days) < 2:
            continue
        M = ix.mat()
        days = pd.DatetimeIndex([pd.Timestamp(d) for d in ix.days])
        A.IXC[und] = dict(ix=ix, M=M, days=days, real=np.array([bool(ix.d[d]["real"]) for d in ix.days]),
                          exp=np.array([bool(ix.d[d]["exp"]) for d in ix.days]), pos={d: i for i, d in enumerate(days)})
        E = A.sweep_events(und, "all")
        A.IXC.pop(und, None)
        if not len(E):
            continue
        E = E[E.kind == "break"]
        if levels == "R60":
            E = E[E.lvl.isin(["R60H", "R60L"])]
        for r in E.itertuples():
            rows.append(dict(und=und, day=r.day.date(), sig_min=int(OPEN + r.t), side=int(r.side), book=f"brk_{und}",
                             idx_stop=float(r.lev - r.side * H18_ISTOP[und]), tag=r.lvl))
    return _frame(rows)


# ================================================================================================ H43 (hunt/h43)
def h43_bakeoff(mk, strat="bb", tf=1):
    """hunt/h43/sigs.py `make` (imported unchanged): the bake-off's BANKNIFTY strategies on the continuous bar series."""
    S = _mod("h43_sigs", "hunt/h43/sigs.py")
    ix = mk.index("BANKNIFTY")
    if not len(ix.days):
        return pd.DataFrame(columns=EMPTY)
    out = S.make(ix, strat, tf)
    return _frame(out.to_dict("records"))


H43_FILTERS = ("none", "vix_low", "vix_high", "calm", "wide", "beyond_pd", "inside_pd", "htf", "oi", "am", "pm")


def _h43_oi(mk, und, days, c):
    """partD.features' OI build-up: change since 09:15 of total PE OI minus total CE OI over the nearest-expiry chain's
    ATM +- 5 strikes (ATM from the 09:15 close). Causal port: each strike's OI is forward-filled and measured from its
    own first print (the original also back-filled a strike's first print to 09:15, which reads later minutes)."""
    from obuy import config as OC
    D = len(days)
    oi = np.full((D, W), np.nan)
    opts = mk.options(und)
    step = OC.STEP[und]
    for i, d in enumerate(days):
        try:
            ch = opts.chain(d, "near")
        except Exception:      # noqa: BLE001  (a missing day's chain = no filter value, as partD)
            ch = None
        if ch is None or not np.isfinite(c[i, 0]):
            continue
        atm = np.floor(c[i, 0] / step + 0.5) * step
        ks = (ch.K >= atm - 5 * step) & (ch.K <= atm + 5 * step)
        if not ks.any():
            continue
        tot = np.zeros(W)
        for rt, sgn in (("P", 1), ("C", -1)):
            a = pd.DataFrame(ch.oi[rt][ks]).ffill(axis=1).values
            ok = ~np.isnan(a)
            first = np.where(ok.any(axis=1), a[np.arange(len(a)), np.argmax(ok, axis=1)], np.nan)
            tot = tot + sgn * np.nansum(np.where(ok, a - first[:, None], 0.0), axis=0)
        oi[i] = tot
    mk.release(und)
    return oi


def h43_enhanced(mk, und="MIDCPNIFTY", fam="bb", tf=5, f=5, s=21, thr=0, n=20, k=2.0, start=14 * 60, band=None,
                 filt="none"):
    """hunt/h43/partD.py `Sig.make` (ema / bb / pe families on sigs.spot_series / sigs.bars, imported) + `features` /
    `fmask` (one filter), ported. Signals at or before 15:13."""
    S = _mod("h43_sigs", "hunt/h43/sigs.py")
    ix = mk.index(und)
    if not len(ix.days):
        return pd.DataFrame(columns=EMPTY)
    days, o, h, l, c = S.spot_series(ix)
    b = S.bars(days, o, h, l, c, tf)
    if fam == "ema":
        d = S.ema(b.c.values, f) - S.ema(b.c.values, s)
        ad = S.adx(b.h.values, b.l.values, b.c.values)
        dp = np.r_[np.nan, d[:-1]]
        ok = ad > thr if thr else np.ones(len(d), bool)
        up, dn = (d > 0) & (dp <= 0) & ok, (d < 0) & (dp >= 0) & ok
        sel = up | dn
        di, sm, sd = b.di.values[sel], b.sig_min.values[sel], np.where(up, 1, -1)[sel]
    elif fam == "bb":
        cl = pd.Series(b.c.values)
        mid, sdv = cl.rolling(n).mean().values, cl.rolling(n).std(ddof=0).values
        up, dn = b.c.values > mid + k * sdv, b.c.values < mid - k * sdv
        sel = up | dn
        di, sm, sd = b.di.values[sel], b.sig_min.values[sel], np.where(up, 1, -1)[sel]
    elif fam == "pe":
        ad = S.adx(b.h.values, b.l.values, b.c.values)
        ok = b.sig_min.values >= start
        if band:
            ok &= (ad >= band[0]) & (ad <= band[1])
        di, sm, sd = b.di.values[ok], b.sig_min.values[ok], -np.ones(int(ok.sum()), int)
    else:
        raise ValueError(fam)
    keep = sm <= 15 * 60 + 13
    di, sm, sd = di[keep].astype(np.int64), sm[keep].astype(np.int64), sd[keep].astype(np.int64)
    if filt != "none" and len(di):
        D = len(days)
        h2, l2, c2 = (a.reshape(D, W) for a in (h, l, c))
        col = sm - OPEN
        if filt in ("vix_low", "vix_high"):
            vx = mk.vix.daily.close
            vlow, vok = np.zeros(D, bool), np.zeros(D, bool)
            for i, dd in enumerate(days):
                j = vx.index.searchsorted(dd)
                if j >= 60:
                    w = vx.values[max(0, j - 250):j]
                    vlow[i], vok[i] = w[-1] < np.median(w), True
            m = vok[di] & (vlow[di] if filt == "vix_low" else ~vlow[di])
        elif filt in ("calm", "wide"):
            H, L = np.nanmax(h2, axis=1), np.nanmin(l2, axis=1)
            adr = pd.Series(H - L).shift(1).rolling(20, min_periods=5).mean().values
            sofar = np.fmax.accumulate(h2, axis=1) - np.fmin.accumulate(l2, axis=1)
            with np.errstate(all="ignore"):
                ratio = sofar / adr[:, None]
            m = ratio[di, col] < 0.5 if filt == "calm" else ratio[di, col] >= 0.5
        elif filt in ("beyond_pd", "inside_pd"):
            H, L = np.nanmax(h2, axis=1), np.nanmin(l2, axis=1)
            pdh, pdl = np.r_[np.nan, H[:-1]], np.r_[np.nan, L[:-1]]
            cc = c2[di, col]
            m = (np.where(sd > 0, cc > pdh[di], cc < pdl[di]) if filt == "beyond_pd"
                 else (cc <= pdh[di]) & (cc >= pdl[di]))
        elif filt == "htf":
            htf = S.ema(c, 300).reshape(D, W)
            m = sd * (c2[di, col] - htf[di, col]) > 0
        elif filt == "oi":
            oi = _h43_oi(mk, und, days, c2)
            m = sd * oi[di, col] > 0
        elif filt == "am":
            m = sm < 11 * 60 + 30
        elif filt == "pm":
            m = sm >= 11 * 60 + 30
        else:
            raise ValueError(filt)
        di, sm, sd = di[m], sm[m], sd[m]
    dd = np.array(days, dtype=object)
    rows = [dict(und=und, day=dd[a], sig_min=int(x), side=int(y), book=f"h43_{und}") for a, x, y in zip(di, sm, sd)]
    return _frame(rows)


# ================================================================================================ H46 (hunt/h46)
def h46_vwap_reject(mk, und="MIDCPNIFTY", tf=15, rej="R1", xm="CLOSE"):
    """hunt/h46/sim.py `gen` for one tf, TWAP, one rejection and one exit: candles / ema_cont / vwap imported, the
    trend + rejection + exit logic ported. Every candidate is emitted (book per index): r52's one-at-a-time positions()
    keeps a signal only once the previous exit has filled, which is gen's 'sig >= last exit' walk."""
    SIM = _mod("h46_sim", "hunt/h46/sim.py")
    ix = mk.index(und)
    if not len(ix.days):
        return pd.DataFrame(columns=EMPTY)
    M = ix.mat()
    o1, h1, l1, c1 = M["o"], M["h"], M["l"], M["c"]
    D = len(ix.days)
    SQC = 355
    Co, Ch, Cl, Cc, valid, end = SIM.candles(o1, h1, l1, c1, tf)
    G = Co.shape[1]
    ema = SIM.ema_cont(Cc, valid)
    vw = SIM.vwap(Ch, Cl, Cc, valid, valid.astype(float))
    with np.errstate(invalid="ignore"):
        okb = valid & np.isfinite(vw) & np.isfinite(ema)
        r1b = okb & (ema > vw) & (Cl <= vw * (1 + SIM.TOL)) & (Co > vw) & (Cc > vw)
        r1s = okb & (ema < vw) & (Ch >= vw * (1 - SIM.TOL)) & (Co < vw) & (Cc < vw)
        mb, ms = (r1b, r1s) if rej == "R1" else (r1b & (Cc > Co), r1s & (Cc < Co))
        below, above = valid & (Cc < ema), valid & (Cc > ema)

    def nxt(mask, size):
        idx = np.where(mask, np.arange(size)[None, :], size)
        return np.concatenate([np.minimum.accumulate(idx[:, ::-1], axis=1)[:, ::-1], np.full((D, 1), size)], axis=1)

    endx = np.concatenate([end, [W]])
    dd, gg = np.nonzero(mb | ms)
    side = np.where(mb[dd, gg], 1, -1)
    sig, ent = end[gg] - 1, end[gg]
    keep = ent < SQC
    dd, gg, side, sig, ent = dd[keep], gg[keep], side[keep], sig[keep], ent[keep]
    if xm == "CLOSE":
        nb, na = nxt(below, G), nxt(above, G)
        xx = np.where(side > 0, endx[nb[dd, gg + 1]], endx[na[dd, gg + 1]])
    else:                                                   # TOUCH: a 1-min bar beyond the last CLOSED candle's EMA9
        flat_ema = ema[valid]
        vidx = np.full(valid.shape, -1)
        vidx[valid] = np.arange(valid.sum())
        lastv = np.maximum.accumulate(np.where(valid, vidx, -1).reshape(-1)).reshape(D, G)
        gprev = np.arange(W) // tf - 1
        ep = np.full((D, W), np.nan)
        for di in range(D):
            li = np.where(gprev >= 0, lastv[di, np.maximum(gprev, 0)], lastv[di - 1, -1] if di > 0 else -1)
            ep[di] = np.where(li >= 0, flat_ema[np.maximum(li, 0)] if len(flat_ema) else np.nan, np.nan)
        with np.errstate(invalid="ignore"):
            ntb, nts = nxt(l1 < ep, W), nxt(h1 > ep, W)
        xx = np.where(side > 0, ntb[dd, ent], nts[dd, ent]) + 1
    xx = np.minimum(xx, SQC)
    rows = [dict(und=und, day=ix.days[a], sig_min=int(OPEN + s_), side=int(sd), book=f"h46_{und}",
                 exit_at=float(OPEN + x) if x < SQC else np.nan) for a, s_, sd, x in zip(dd, sig, side, xx)]
    return _frame(rows)


# ================================================================================================ TREND_FILTER
def trend_hh(mk, n=3, chop=True, exit_rule="fixed", und="BANKNIFTY"):
    """research/trend_filter.py `prepare` + `signal` (+ `fill_trend`'s exit as exit_at), ported to obuy minutes:
    5-min bars from 09:15, EMA9 continuous, the day's TWAP of the bars' typical price, the sideways count (2+ TWAP side
    changes in the last 6 bars); signal on a completed bar starting 09:30-14:25; ATM from the 09:20 bar's close
    (arms_long.legs_for: one strike a day)."""
    ix = mk.index(und)
    days = day_dicts(ix)
    if not days:
        return pd.DataFrame(columns=EMPTY)
    b = _bars(days, 5)
    b["ema9"] = b.c.ewm(span=9, adjust=False).mean().values
    rows = []
    for di, g in b.groupby("di", sort=True):
        g = g.reset_index(drop=True)
        tp = (g.h + g.l + g.c) / 3
        vwap = tp.expanding().mean()
        sd = np.sign(g.c - vwap)
        cross6 = (sd != sd.shift()).astype(int).rolling(6, min_periods=2).sum()
        hi, lo, cl, em, s = g.h.values, g.l.values, g.c.values, g.ema9.values, g.s.values
        ref = days[di]["I"]["close"][9]                         # the 09:20 5-min bar's close
        for j in range(len(g) - 1):
            if not (15 <= s[j] <= 310) or j < n:
                continue
            if chop and cross6.iloc[j] >= 2:
                continue
            wh, wl = np.diff(hi[j - n:j + 1]), np.diff(lo[j - n:j + 1])
            hh = (wh > 0).all() and (wl > 0).all()
            ll = (wh < 0).all() and (wl < 0).all()
            if lo[j] > em[j] and lo[j] > vwap.iloc[j] and hh:
                side = 1
            elif hi[j] < em[j] and hi[j] < vwap.iloc[j] and ll:
                side = -1
            else:
                continue
            xa = np.nan
            if exit_rule == "trend":
                for k in range(j + 1, len(g)):
                    broken = cl[k] < em[k] if side > 0 else cl[k] > em[k]
                    if s[k] >= 350 or broken:                   # the 15:05 bar or a close through the EMA9
                        xa = float(OPEN + s[k] + 5)
                        break
            rows.append(dict(und=und, day=days[di]["day"], sig_min=int(OPEN + s[j] + 4), side=side, ref_spot=float(ref),
                             book=f"tf_{und}", exit_at=xa))
    return _frame(rows)


# ================================================================================================ REVERSAL_SETUP
def reversal(mk, drop=0.003, capital=(("BANKNIFTY", 20000.0), ("NIFTY", 15000.0)), unds=("BANKNIFTY",)):
    """research/reversal_setup.py `setup` (ported on obuy minutes) + `trade`'s strike: the option of the setup's side
    nearest the money whose one lot (the day's lot) costs <= capital and > Rs 3 a unit. Causal: priced at its last
    print at or before the signal minute (the original read the fill minute's open). No fitting strike -> no trade."""
    cap = dict(capital)
    rows = []
    for und in unds:
        ix = mk.index(und)
        days = day_dicts(ix)
        opts = None
        for x in days:
            I = x["I"]
            o0 = I["open"][0]
            if not np.isfinite(o0):
                continue
            vw = np.cumsum((I["high"] + I["low"] + I["close"]) / 3) / np.arange(1, W + 1)
            hit = None
            for t in range(30, 226, 5):                          # 09:45 .. 13:00
                c = I["close"][t - 1]
                lo_at, hi_at = int(np.argmin(I["low"][:t])), int(np.argmax(I["high"][:t]))
                green = I["close"][t - 1] > I["open"][t - 5]
                if c <= o0 * (1 - drop) and c <= vw[t - 1] * 0.997 and lo_at <= t - 15 and green:
                    hit = (t, 1)
                    break
                if c >= o0 * (1 + drop) and c >= vw[t - 1] * 1.003 and hi_at <= t - 15 and not green:
                    hit = (t, -1)
                    break
            if hit is None:
                continue
            t, sign = hit
            opts = opts or mk.options(und)
            ch = opts.chain(x["day"], "near")
            if ch is None:
                continue
            lot = ix.lot(x["day"])
            spot = I["close"][t - 1]
            px = ch.c[_cside(sign)][:, :t]
            best = None
            for kk, row in zip(ch.K, px):
                ok = np.nonzero(~np.isnan(row))[0]
                if not len(ok):
                    continue
                p = row[ok[-1]]
                if p > 3 and p * lot <= cap[und] and (best is None or abs(kk - spot) < abs(best - spot)):
                    best = kk
            if best is None:
                continue
            rows.append(dict(und=und, day=x["day"], sig_min=OPEN + t - 1, side=sign, strike=float(best),
                             book=f"rev_{und}"))
        if opts is not None:
            mk.release(und)
    return _frame(rows)


# ================================================================================================ CH-1 chandelier
def chandelier(mk, tf=60, mode=1, und="BANKNIFTY"):
    """research/chandelier_exit.py `chandelier` (imported unchanged) on the continuous tf chart + `simulate`'s trading
    rules: a flip on a closed bar (not the day's last) buys at the next bar's open if that is 09:20-14:30; index stop
    1 x ATR and target mode x ATR from the signal close (mode 'flip': no stop / target); the next signal of the day closes
    it at the following bar's open (exit_at). One position at a time: signals alternate, so a book per side suffices."""
    CE = _mod("chandelier_exit", "chandelier_exit.py")
    ix = mk.index(und)
    days = day_dicts(ix)
    if not days:
        return pd.DataFrame(columns=EMPTY)
    b = _bars(days, tf).rename(columns=dict(o="open", h="high", l="low", c="close"))
    sig, atr = CE.chandelier(b)
    DI, S, E, C = b.di.values, b.s.values, b.e.values, b.close.values
    n = len(b)
    last = np.r_[DI[1:] != DI[:-1], True]
    ev = [i for i in range(n) if sig[i] != 0 and not last[i]]
    rows = []
    for a, i in enumerate(ev):
        m = S[i + 1]
        if not (5 <= m <= 315):
            continue
        side = int(sig[i])
        nx = ev[a + 1] if a + 1 < len(ev) and DI[ev[a + 1]] == DI[i] else None
        r = dict(und=und, day=days[DI[i]]["day"], sig_min=int(OPEN + m - 1), side=side,
                 book=f"ch_{und}_{_cside(side)}", exit_at=float(OPEN + E[nx]) if nx is not None else np.nan,
                 idx_stop=np.nan, idx_target=np.nan)
        if mode != "flip" and np.isfinite(atr[i]):
            r["idx_stop"] = float(C[i] - side * atr[i])
            r["idx_target"] = float(C[i] + side * atr[i] * mode)
        rows.append(r)
    return _frame(rows)


# ================================================================================================ TAS_OPTIONS-1
def tas(mk, tf=30, lock=(1.0, 3.0), confirm=0, unds=("NIFTY",)):
    """research/tas_options.py `run` with tps=() (no targets): gold_tas.indicators (ATR 19, imported unchanged) on the
    continuous tf chart; buy on the tracker turn (within confirm .. confirm + 10 candles, score >= +50%) at the next
    candle's open if <= 14:30; all exits on the index, as exit_at: the stop (the tracker line, tightened by the ATR(14)
    profit lock from the next minute) -> out at the next minute, the tracker turning -> the next candle's open, else 15:10.
    One trade per turn; CE and PE run independently (a book per side)."""
    GT = _mod("gold_tas", "gold_tas.py")
    LAST_M, CUT = 315, 355
    rows = []
    for und in unds:
        ix = mk.index(und)
        days = day_dicts(ix)
        if not days:
            continue
        b = _bars(days, tf).rename(columns=dict(o="open", h="high", l="low", c="close"))
        if len(b) < 3:
            continue
        trk, d, pct = GT.indicators(b, 19)
        h, l, c = b.high, b.low, b.close
        tr = pd.concat([h - l, (h - c.shift()).abs(), (l - c.shift()).abs()], axis=1).max(axis=1)
        atr = GT.rma(tr, 14).values
        DI, S, E = b.di.values, b.s.values, b.e.values
        n = len(b)
        for side in (1, -1):
            i, flip = 1, None
            while i < n - 1:
                if d[i] == side and d[i - 1] != side:
                    flip = i
                if d[i] != side:
                    flip = None
                ok = (flip is not None and confirm <= i - flip <= confirm + GT.LATE and np.isfinite(pct[i])
                      and side * pct[i] >= GT.MIN_SCORE)
                if not ok or DI[i + 1] != DI[i] or S[i + 1] > LAST_M:
                    i += 1
                    continue
                I = days[DI[i]]["I"]
                m0 = S[i + 1]
                ix0 = I["close"][m0 - 1]
                stop, a = trk[i], atr[i]
                if side * (ix0 - stop) <= 0:
                    i += 1
                    continue
                peak = -np.inf if side > 0 else np.inf
                j, xm, done = i + 1, np.nan, False
                while j < n and DI[j] == DI[i] and not done:
                    for m in range(max(S[j], m0), E[j]):
                        if m >= CUT:
                            done = True
                            break
                        lo, hi = I["low"][m], I["high"][m]
                        if (side > 0 and lo <= stop) or (side < 0 and hi >= stop):
                            xm, done = float(OPEN + m + 1), True
                            break
                        peak = max(peak, hi) if side > 0 else min(peak, lo)
                        if lock is not None and np.isfinite(a) and side * (peak - ix0) >= lock[0] * a:
                            ls = peak - side * lock[1] * a
                            stop = max(stop, ls) if side > 0 else min(stop, ls)
                    if done:
                        break
                    if d[j] != side:                             # the tracker turned: out at the next candle's open
                        if j + 1 < n and DI[j + 1] == DI[j] and E[j] < CUT:
                            xm = float(OPEN + E[j])
                        done = True
                        break
                    j += 1
                rows.append(dict(und=und, day=days[DI[i]]["day"], sig_min=int(OPEN + m0 - 1), side=side,
                                 book=f"tas_{und}_{_cside(side)}", exit_at=xm if xm < OPEN + CUT else np.nan))
                flip = None
                i = j + 1
                while i < n and d[i] == side and d[i - 1] == side:
                    i += 1
    return _frame(rows)


# ================================================================================================ ST-1 (Boss's Pine)
def st_ema(mk, tf=5, und="BANKNIFTY"):
    """research/st_ema_pine.py `supertrend` (imported unchanged) + `simulate`'s position walk: long when the direction
    flips bullish and close > EMA50 (short mirror), only bars starting 09:15-15:15, an opposite signal reverses at the
    next bar's open, the bar holding 15:15 closes it (never later than R52's 15:15 square-off), no stop or target."""
    ST = _mod("st_ema_pine", "st_ema_pine.py")
    ix = mk.index(und)
    days = day_dicts(ix)
    if not days:
        return pd.DataFrame(columns=EMPTY)
    b = _bars(days, tf)
    ema = b.c.ewm(span=50, adjust=False).mean().values
    dirn = ST.supertrend(b)
    chg = pd.Series(dirn).diff().values
    buy, sell = (chg < 0) & (b.c.values > ema), (chg > 0) & (b.c.values < ema)
    DI, S = b.di.values, b.s.values
    sq = SQ15 - OPEN
    rows, pos = [], None

    def close(m):
        if pos is not None and OPEN + m < SQ15:
            rows[pos]["exit_at"] = float(OPEN + m)

    for i in range(len(b) - 1):
        if pos is not None and rows[pos]["_di"] != DI[i]:
            pos = None
        in_sess = S[i] <= sq
        is_end = S[i] <= sq < S[i] + tf
        sig = 1 if (buy[i] and in_sess) else (-1 if (sell[i] and in_sess) else 0)
        if DI[i + 1] != DI[i]:
            pos = None                                           # day end: the 15:15 square-off
            continue
        if sig != 0 and (pos is None or rows[pos]["side"] != sig):
            if pos is not None:
                close(S[i + 1])
                pos = None
            if not is_end:
                rows.append(dict(und=und, day=days[DI[i]]["day"], sig_min=int(OPEN + S[i + 1] - 1), side=sig,
                                 book=f"st_{und}_{_cside(sig)}", exit_at=np.nan, _di=DI[i]))
                pos = len(rows) - 1
        if is_end and pos is not None:
            close(S[i + 1])
            pos = None
    for r in rows:
        r.pop("_di")
    return _frame(rows)


# ================================================================================================ MP-1 (Boss's Pine)
def matrix_premium(mk, dead=True, atr_mult=2.7, tp2r=2.5, und="BANKNIFTY"):
    """research/matrix_pine.py `simulate` signals: 5-min close crosses EMA20 (up -> CE, down -> PE) on bars starting
    09:20-15:15, not 11:00-13:14 (dead zone); index stop close -/+ atr_mult x ATR14 (matrix_v22.bars' RMA ATR), target
    tp2r x risk; 15:15. Opposite signals while a trade is open are ignored: r52's one-at-a-time positions()."""
    ix = mk.index(und)
    days = day_dicts(ix)
    if not days:
        return pd.DataFrame(columns=EMPTY)
    b = _bars(days, 5)
    ema = b.c.ewm(span=20, adjust=False).mean()
    up = ((b.c > ema) & (b.c.shift() <= ema.shift())).values
    dn = ((b.c < ema) & (b.c.shift() >= ema.shift())).values
    t = b.s.values + OPEN
    ok = (t >= 9 * 60 + 20) & (t <= SQ15)
    if dead:
        ok &= ~((t >= 11 * 60) & (t < 13 * 60 + 15))
    rows = []
    for i in np.nonzero((up | dn) & ok)[0]:
        e = int(b.e.iat[i])
        if e >= W or e > SQ15 - OPEN:
            continue
        sig = 1 if up[i] else -1
        risk = atr_mult * float(b.atr.iat[i])
        cl = float(b.c.iat[i])
        rows.append(dict(und=und, day=days[int(b.di.iat[i])]["day"], sig_min=OPEN + e - 1, side=sig,
                         idx_stop=cl - sig * risk, idx_target=cl + sig * tp2r * risk, book=f"mp_{und}"))
    return _frame(rows)


# ================================================================================================ MV-1 (Matrix v2.2)
def matrix_v22(mk, tf=5, buf=2.0, tp1=1.0, mode="lock", choch_only=False, und="BANKNIFTY"):
    """research/matrix_v22.py `simulate`, ported on the index alone: 3-bar fractal pivots (continuous chart), a close
    through the last unbroken swing high / low = BOS / CHoCH entry at the next bar's open (09:30-14:30, flat only),
    SL = last swing low / high -/+ buf x ATR14, TP1 tp1 R (half out, stop to entry), rest 'fixed' 2R / 'lock' (stop to
    TP1 at 2R, out at 4R) / 'ribbon' (2 ATR trail on bar closes after TP1), CHoCH against -> out at the next bar's open,
    15:10. Index exits in a minute -> the option is sold at the next minute's open (exit_at). Two 1-lot rows per entry
    (half A, half B); the walk itself keeps one position at a time."""
    ix = mk.index(und)
    days = day_dicts(ix)
    if not days:
        return pd.DataFrame(columns=EMPTY)
    b = _bars(days, tf)
    H, L, C, A = b.h.values, b.l.values, b.c.values, b.atr.values
    DI, S, E = b.di.values, b.s.values, b.e.values
    CUT, FIRST, LAST, PIV = SQ10 - OPEN, 15, 5 * 60 + 15, 3
    sh = sl = lsh = lsl = None
    trend = 0
    rows, pos = [], None

    def exit_part(frac, col):
        """col: the column at whose open the option is sold (None = the 15:10 square-off)."""
        xa = float(OPEN + col) if col is not None and col < CUT else np.nan
        if pos["left"] >= 1.0 - 1e-9 and frac >= 1.0 - 1e-9:
            pos["xA"] = pos["xB"] = xa
        elif pos["left"] >= 1.0 - 1e-9:
            pos["xA"] = xa
        else:
            pos["xB"] = xa
        pos["left"] -= frac

    def finish():
        for half, x in (("A", pos["xA"]), ("B", pos["xB"])):
            rows.append(dict(und=und, day=pos["day"], sig_min=pos["sm"], side=pos["sign"], book=f"mv_{und}_{half}",
                             exit_at=x, tag=f"half {half} {pos['kind']}"))

    for i in range(len(b)):
        di = DI[i]
        I = days[di]["I"]
        if pos is not None:
            sg = pos["sign"]
            for m in range(max(S[i], pos["m"]), E[i]):
                if pos["left"] <= 1e-9:
                    break
                adverse = I["low"][m] if sg > 0 else I["high"][m]
                fav = I["high"][m] if sg > 0 else I["low"][m]
                if m >= CUT:
                    exit_part(pos["left"], None)
                    break
                if sg * (adverse - pos["stop"]) <= 0:
                    exit_part(pos["left"], m + 1)
                    break
                lvl = lambda R: pos["ix"] + sg * R * pos["risk"]  # noqa: E731
                if not pos["tp1hit"] and sg * (fav - lvl(tp1)) >= 0:
                    exit_part(0.5, m + 1)
                    pos["tp1hit"], pos["stop"] = True, pos["ix"]
                if pos["tp1hit"] and mode == "fixed" and sg * (fav - lvl(2.0)) >= 0:
                    exit_part(pos["left"], m + 1)
                    break
                if pos["tp1hit"] and mode == "lock":
                    if sg * (fav - lvl(2.0)) >= 0:
                        pos["stop"] = lvl(tp1) if sg * (lvl(tp1) - pos["stop"]) > 0 else pos["stop"]
                    if sg * (fav - lvl(4.0)) >= 0:
                        exit_part(pos["left"], m + 1)
                        break
            if pos["left"] <= 1e-9:
                finish()
                pos = None
            elif mode == "ribbon" and pos["tp1hit"]:
                trail = C[i] - pos["sign"] * 2.0 * A[i]
                if pos["sign"] * (trail - pos["stop"]) > 0:
                    pos["stop"] = trail
        j = i - PIV
        if j >= PIV:
            if H[j] == H[j - PIV:i + 1].max():
                sh = lsh = H[j]
            if L[j] == L[j - PIV:i + 1].min():
                sl = lsl = L[j]
        sig, kind = 0, ""
        if sh is not None and C[i] > sh:
            sig, kind = 1, "CHoCH" if trend < 0 else "BOS"
            sh = None
        elif sl is not None and C[i] < sl:
            sig, kind = -1, "CHoCH" if trend > 0 else "BOS"
            sl = None
        if sig:
            trend = sig
        nxt = i + 1 < len(b) and DI[i + 1] == di
        if pos is not None and sig == -pos["sign"]:
            exit_part(pos["left"], S[i + 1] if nxt else None)
            finish()
            pos = None
        if pos is not None and not nxt:
            exit_part(pos["left"], None)
            finish()
            pos = None
        if pos is None and sig and nxt and FIRST <= S[i + 1] <= LAST and not (choch_only and kind != "CHoCH"):
            m = S[i + 1]
            ixp = C[i]
            if lsl is None or lsh is None:
                continue
            stop = (lsl - buf * A[i]) if sig > 0 else (lsh + buf * A[i])
            if sig * (ixp - stop) <= 0:
                continue
            pos = dict(day=days[di]["day"], sm=int(OPEN + m - 1), sign=sig, kind=kind, m=m, ix=ixp,
                       risk=abs(ixp - stop), stop=stop, left=1.0, tp1hit=False, xA=np.nan, xB=np.nan)
    if pos is not None:
        exit_part(pos["left"], None)
        finish()
    return _frame(rows)


# ================================================================================================ SPECS
WIN_DAY = (9 * 60 + 20, 14 * 60 + 30)
ORB = lambda pts: dict(stop_pts=pts, tgt_pts=pts, ladder=LADDER, ladder_ref_pts=pts, sq_off=SQ10)  # noqa: E731
LIQ = dict(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=SQ10)
H43X = {  # hunt/h43/partA.EXITS / partD.exits()
    "T15": dict(stop_pct=0.30, tgt_pct=0.60, time_stop=15, time_gain=None, sq_off=SQ15),
    "T30": dict(stop_pct=0.30, tgt_pct=0.60, time_stop=30, time_gain=None, sq_off=SQ15),
    "T45": dict(stop_pct=0.30, tgt_pct=0.60, time_stop=45, time_gain=None, sq_off=SQ15),
    "T60": dict(stop_pct=0.30, tgt_pct=0.60, time_stop=60, time_gain=None, sq_off=SQ15),
    "EOD": dict(stop_pct=0.30, tgt_pct=0.60, sq_off=SQ15),
    "LAD": dict(stop_pct=0.15, ladder=LADDER, ladder_ref_pct=0.15, tgt_pct=0.30, sq_off=SQ15),
    "TRAIL": dict(stop_pct=0.30, trail_pct=0.15, trail_arm=0.10, sq_off=SQ15),
    "CHAMP": dict(stop_pct=0.25, tgt_pct=0.50, ladder=((1, 1), (2, 2), (3, 3), (4, 4)), ladder_ref_pct=0.25,
                  time_stop=60, time_gain=None, sq_off=SQ15),
}
H43WIN = (OPEN, 15 * 60 + 13)
ONE = dict(one_at_a_time=True)

SPECS = [
    # ------------------------------------------------------------------ R20C
    dict(key="R20C-1", title="Pivot R1/S1 break, 2 closing 5-min candles (BANKNIFTY half)", source="index",
         why="original = R20c's pre-registered r20brk-piv5n2 (hunt/r20c/trades.py RULES; ARM BANKNIFTY 40/40 + LADDER, "
             "15:10). NIFTY half is R20C-1N (per-index premium points). Grid = the study's other 5-min rule piv5n1 "
             "(= R20C-2).",
         orig=dict(fn=r20c_break, params=dict(types=("PIV",), tf=5, n=2, last_entry=(14, 0), unds=("BANKNIFTY",)),
                   money=0, series="near", unds=("BANKNIFTY",), exits=ORB(40.0), expiry="skip", pos=ONE,
                   window=(9 * 60 + 24, 13 * 60 + 59)),
         grid=[dict(params=dict(n=1))]),
    dict(key="R20C-1N", title="Pivot R1/S1 break, 2 closing 5-min candles (NIFTY half of R20C-1)", source="index",
         why="R20C-1's NIFTY half: r20brk-piv5n2 with ARM NIFTY 20/20 + LADDER (trades.py ARM). Grid piv5n1.",
         orig=dict(fn=r20c_break, params=dict(types=("PIV",), tf=5, n=2, last_entry=(14, 0), unds=("NIFTY",)),
                   money=0, series="near", unds=("NIFTY",), exits=ORB(20.0), expiry="skip", pos=ONE,
                   window=(9 * 60 + 24, 13 * 60 + 59)),
         grid=[dict(params=dict(n=1))]),
    dict(key="R20C-3", title="Early 15-min R1/S1/PDH/PDL break (2 closes, by 10:30)", source="index",
         why="original = r20brk-early15 (trades.py RULES): PIV + PDHL, 15-min, N 2, by 10:30, 1-ITM, Liquidity exits "
             "(sim_liq). The study's 4-rule grid is the separate keys R20C-1/3/4: no own grid.",
         orig=dict(fn=r20c_break, params=dict(types=("PIV", "PDHL"), tf=15, n=2, last_entry=(10, 30), liq_exit=True),
                   money=1, series="near", unds=("BANKNIFTY", "NIFTY"), exits=LIQ, expiry="skip", pos=ONE,
                   window=(9 * 60 + 44, 10 * 60 + 29)),
         grid=[]),
    dict(key="R20C-4", title="Low-VIX 15-min pivot break (prev VIX < 13)", source="index + VIX",
         why="original = r20brk-lowvix15 (trades.py RULES): PIV, 15-min, N 2, by 14:00, previous VIX close < 13, 1-ITM, "
             "Liquidity exits. No own grid (see R20C-3).",
         orig=dict(fn=r20c_break, params=dict(types=("PIV",), tf=15, n=2, last_entry=(14, 0), vix_max=13.0,
                                              liq_exit=True),
                   money=1, series="near", unds=("BANKNIFTY", "NIFTY"), exits=LIQ, expiry="skip", pos=ONE,
                   window=(9 * 60 + 44, 13 * 60 + 59)),
         grid=[]),
    # ------------------------------------------------------------------ H18
    dict(key="H18-1", title="Rolling 60-min high/low break (h18)", source="index",
         why="original = h18's best pre-holdout variant R60 / gamma none / arm + 30% target (hunt/h18/rule.py EXITS[1], "
             "1-ITM, skip expiry, <= 3/day, 09:45-14:30). Grid = levels ALL x exits arm / arm+30% (gamma variants need "
             "another session's GEX panel).",
         orig=dict(fn=h18_break, params=dict(levels="R60"), money=1, series="near",
                   unds=("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY"),
                   exits=dict(stop_pct=0.15, tgt_pct=0.30, time_stop=20, time_gain=0.05, sq_off=SQ10), expiry="skip",
                   pos=dict(one_at_a_time=True, max_per_day=3), window=(9 * 60 + 45, 14 * 60 + 30)),
         grid=[dict(params=dict(levels="ALL")), dict(exits=LIQ), dict(params=dict(levels="ALL"), exits=LIQ)]),
    # ------------------------------------------------------------------ H43
    dict(key="H43-2", title="Bake-off Bollinger(20,2) state entries, BN", source="index",
         why="original = the report's headline bb 1-min, -30/+60%, 15-min time stop, 2/day (hunt/h43/sigs.py STRATS, "
             "partA.EXITS T15). Grid = bb at 5 / 15 min with T15 and the parameter document's T30 / T45.",
         orig=dict(fn=h43_bakeoff, params=dict(strat="bb", tf=1), money=0, series="near", unds=("BANKNIFTY",),
                   exits=H43X["T15"], expiry="skip", pos=dict(one_at_a_time=True, max_per_day=2), window=H43WIN),
         grid=[dict(params=dict(tf=5)), dict(params=dict(tf=15)), dict(params=dict(tf=5), exits=H43X["T30"]),
               dict(params=dict(tf=15), exits=H43X["T45"])]),
    dict(key="H43-3", title="Bake-off afternoon PE (ADX 15-25 after 14:00), BN", source="index",
         why="original = the report's pe 5-min, -30/+60%, 30-min time stop, 2/day (sigs.py STRATS, partA T30). Grid = "
             "pe at 1 / 15 min (T30) and the document's 15-min T45.",
         orig=dict(fn=h43_bakeoff, params=dict(strat="pe", tf=5), money=0, series="near", unds=("BANKNIFTY",),
                   exits=H43X["T30"], expiry="skip", pos=dict(one_at_a_time=True, max_per_day=2),
                   window=(14 * 60, 15 * 60 + 13)),
         grid=[dict(params=dict(tf=1)), dict(params=dict(tf=15)), dict(params=dict(tf=15), exits=H43X["T45"])]),
    dict(key="H43-5", title="Enhanced Bollinger, MIDCP (OI build-up filter)", source="index + option-chain context (OI)",
         why="original = part D's 'enhanced' bb pick (HUNT_H43.md table): MIDCP ATM (monthly = the lake's near series), "
             "BB(20, 2.5) 3-min, filter oi, S30EOD, 1/day. Grid = one-step neighbours in partD's own grid.",
         orig=dict(fn=h43_enhanced, params=dict(und="MIDCPNIFTY", fam="bb", n=20, k=2.5, tf=3, filt="oi"), money=0,
                   series="near", unds=("MIDCPNIFTY",), exits=H43X["EOD"], expiry="skip",
                   pos=dict(one_at_a_time=True, max_per_day=1), window=H43WIN),
         grid=[dict(params=dict(k=2.0)), dict(params=dict(k=1.5)), dict(params=dict(n=14)), dict(params=dict(n=30)),
               dict(params=dict(tf=1)), dict(params=dict(tf=5)), dict(params=dict(filt="none")),
               dict(exits=H43X["T15"]), dict(exits=H43X["T30"]), dict(exits=H43X["T60"]), dict(exits=H43X["LAD"]),
               dict(money=1), dict(money=-1), dict(pos=dict(one_at_a_time=True, max_per_day=2)),
               dict(pos=dict(one_at_a_time=True, max_per_day=4))]),
    dict(key="H43-6", title="Enhanced EMA5/21 + ADX>15, MIDCP ('wide day')", source="index",
         why="original = part D's 'enhanced' ema pick: MIDCP 1-ITM, EMA 5/21 + ADX > 15, 5-min, filter wide, S30EOD, "
             "1/day. Grid = one-step neighbours in partD's grid.",
         orig=dict(fn=h43_enhanced, params=dict(und="MIDCPNIFTY", fam="ema", f=5, s=21, thr=15, tf=5, filt="wide"),
                   money=1, series="near", unds=("MIDCPNIFTY",), exits=H43X["EOD"], expiry="skip",
                   pos=dict(one_at_a_time=True, max_per_day=1), window=H43WIN),
         grid=[dict(params=dict(thr=0)), dict(params=dict(thr=20)), dict(params=dict(thr=25)),
               dict(params=dict(f=5, s=13)), dict(params=dict(f=8, s=21)), dict(params=dict(f=9, s=21)),
               dict(params=dict(tf=3)), dict(params=dict(tf=10)), dict(params=dict(filt="none")),
               dict(exits=H43X["T30"]), dict(exits=H43X["T60"]), dict(exits=H43X["CHAMP"]), dict(exits=H43X["TRAIL"]),
               dict(money=0), dict(pos=dict(one_at_a_time=True, max_per_day=2)),
               dict(pos=dict(one_at_a_time=True, max_per_day=4))]),
    dict(key="H43-7", title="Enhanced afternoon PE, FINNIFTY (VIX high)", source="index + VIX",
         why="original = part D's 'enhanced' pe pick: FINNIFTY 1-ITM, from 13:30, ADX 20-30, 5-min, filter vix_high, "
             "S30EOD, 4/day. Grid = one-step neighbours in partD's grid.",
         orig=dict(fn=h43_enhanced, params=dict(und="FINNIFTY", fam="pe", start=13 * 60 + 30, band=(20, 30), tf=5,
                                                filt="vix_high"),
                   money=1, series="near", unds=("FINNIFTY",), exits=H43X["EOD"], expiry="skip",
                   pos=dict(one_at_a_time=True, max_per_day=4), window=(13 * 60 + 30, 15 * 60 + 13)),
         grid=[dict(params=dict(start=13 * 60), window=(13 * 60, 15 * 60 + 13)),
               dict(params=dict(start=14 * 60), window=(14 * 60, 15 * 60 + 13)),
               dict(params=dict(band=(15, 25))), dict(params=dict(band=(10, 20))), dict(params=dict(band=None)),
               dict(params=dict(tf=3)), dict(params=dict(tf=10)), dict(params=dict(filt="none")),
               dict(exits=H43X["T30"]), dict(exits=H43X["T60"]), dict(exits=H43X["TRAIL"]), dict(money=0),
               dict(pos=dict(one_at_a_time=True, max_per_day=1)), dict(pos=dict(one_at_a_time=True, max_per_day=2))]),
    # ------------------------------------------------------------------ H46
    dict(key="H46-2", title="VWAP reject, MIDCP 15-min (TWAP, close exit)", source="index",
         why="original = the h46 lead: MIDCP 15-min, TWAP, R1, CLOSE exit, ATM, unlimited a day, skip expiry (the "
             "primary's settings, hunt/h46/PREREG.md). Grid = h46's own axes: TOUCH, R2, 1-ITM, max 3 / 5, expiry "
             "allowed, 5-min.",
         orig=dict(fn=h46_vwap_reject, params=dict(und="MIDCPNIFTY", tf=15, rej="R1", xm="CLOSE"), money=0,
                   series="near", unds=("MIDCPNIFTY",), exits=dict(sq_off=SQ10), expiry="skip", pos=ONE,
                   window=(9 * 60 + 29, 15 * 60 + 8)),
         grid=[dict(params=dict(xm="TOUCH")), dict(params=dict(rej="R2")), dict(money=1),
               dict(pos=dict(one_at_a_time=True, max_per_day=3)), dict(pos=dict(one_at_a_time=True, max_per_day=5)),
               dict(expiry="allow"), dict(params=dict(tf=5))]),
    # ------------------------------------------------------------------ TREND_FILTER
    dict(key="TREND_FILTER-1", title="BN 5-min EMA9/TWAP + higher highs/lows trend", source="index",
         why="original = the study's best row: 3 HH/HL, sideways filter on, -40/+40 premium pts, <= 2/day, ATM "
             "(research/trend_filter.py trades). Grid = its own n 2/3 x filter on/off x exit fixed/trend (-60 stop).",
         orig=dict(fn=trend_hh, params=dict(n=3, chop=True, exit_rule="fixed"), money=0, series="near",
                   unds=("BANKNIFTY",), exits=dict(stop_pts=40.0, tgt_pts=40.0, sq_off=SQ10), expiry="skip",
                   pos=dict(one_at_a_time=True, max_per_day=2), window=(9 * 60 + 34, 14 * 60 + 29)),
         grid=[dict(params=dict(n=2)), dict(params=dict(chop=False)), dict(params=dict(n=2, chop=False)),
               dict(params=dict(exit_rule="trend"), exits=dict(stop_pts=60.0, sq_off=SQ10)),
               dict(params=dict(n=2, exit_rule="trend"), exits=dict(stop_pts=60.0, sq_off=SQ10)),
               dict(params=dict(chop=False, exit_rule="trend"), exits=dict(stop_pts=60.0, sq_off=SQ10)),
               dict(params=dict(n=2, chop=False, exit_rule="trend"), exits=dict(stop_pts=60.0, sq_off=SQ10))]),
    # ------------------------------------------------------------------ REVERSAL_SETUP
    dict(key="REVERSAL_SETUP-1", title="Boss's 29 Sep reversal after a stalled move", source="index",
         why="original = the close row: BANKNIFTY next-expiry, capital Rs 20k, move 0.3%, +10% target, no stop, 15:10 "
             "(REVERSAL_SETUP.md). Grid = the study's other exits and the 0.5% move / Rs 5k capital.",
         orig=dict(fn=reversal, params=dict(drop=0.003), money=0, series="near", unds=("BANKNIFTY",),
                   exits=dict(tgt_pct=0.10, sq_off=SQ10), expiry="skip", pos=ONE, window=(9 * 60 + 44, 12 * 60 + 59)),
         grid=[dict(exits=dict(tgt_pct=0.10, stop_pct=0.10 / 3, sq_off=SQ10)),
               dict(exits=dict(tgt_pct=0.10, stop_pct=0.33, sq_off=SQ10)),
               dict(exits=dict(tgt_pct=0.20, stop_pct=0.20 / 3, sq_off=SQ10)), dict(exits=dict(sq_off=SQ10)),
               dict(params=dict(drop=0.005)),
               dict(params=dict(capital=(("BANKNIFTY", 5000.0), ("NIFTY", 5000.0))))]),
    # ------------------------------------------------------------------ CH-1
    dict(key="CH-1", title="Chandelier Exit flips (Boss's ATR 7 x 2), BN", source="index",
         why="original = the close row: 60-min, SL 1 ATR / TP 1R on the index, opposite signal, 09:20-14:30, 15:10, "
             "ATR 7 x 2 (Boss's settings; research/chandelier_exit.py). Grid = its 4 charts x 4 exits.",
         orig=dict(fn=chandelier, params=dict(tf=60, mode=1), money=0, series="near", unds=("BANKNIFTY",),
                   exits=dict(sq_off=SQ10), expiry="skip", pos=ONE, window=(9 * 60 + 19, 14 * 60 + 29)),
         grid=[dict(params=dict(tf=tf, mode=md)) for tf in (60, 30, 15, 5) for md in ("flip", 1, 2, 3)
               if (tf, md) != (60, 1)]),
    # ------------------------------------------------------------------ TAS_OPTIONS-1
    dict(key="TAS_OPTIONS-1", title="TAS tracker on the index, buy ATM", source="index",
         why="original = NIFTY 30-min, lock 1 / 3 ATR, tracker stop, out on a turn, 15:10 (tas_options.py run); the "
             "close row's 1.5/2.5/3.5 R thirds cannot be one held contract, so the study's no-target row with the same "
             "lock is used (+51.5k vs +54.1k). Grid = charts, locks, wait 3 / 5 (TAS_CONFIRM), other indices.",
         orig=dict(fn=tas, params=dict(tf=30, lock=(1.0, 3.0), confirm=0, unds=("NIFTY",)), money=0, series="near",
                   unds=("NIFTY",), exits=dict(sq_off=SQ10), expiry="skip", pos=ONE, window=WIN_DAY),
         grid=[dict(params=dict(tf=15)), dict(params=dict(tf=60)), dict(params=dict(tf=5)), dict(params=dict(tf=240)),
               dict(params=dict(lock=None)), dict(params=dict(lock=(1.0, 1.5))), dict(params=dict(lock=(1.0, 2.0))),
               dict(params=dict(lock=(1.0, 4.0))), dict(params=dict(lock=(2.0, 3.0))),
               dict(params=dict(confirm=3)), dict(params=dict(confirm=5)), dict(params=dict(tf=15, confirm=5)),
               dict(params=dict(unds=("BANKNIFTY",)), unds=("BANKNIFTY",)),
               dict(params=dict(unds=("FINNIFTY",)), unds=("FINNIFTY",)),
               dict(params=dict(unds=("SENSEX",)), unds=("SENSEX",))]),
    # ------------------------------------------------------------------ ST-1
    dict(key="ST-1", title="Boss's Pine Supertrend(10,3) + EMA50, BN", source="index",
         why="original = Boss's script as written: 5-min, ST(10, 3) + EMA 50, reverse on the opposite signal, no stop / "
             "target, ATM, 15:15 (research/st_ema_pine.py). Grid = its 3 / 15-min charts and 2-ITM strike.",
         orig=dict(fn=st_ema, params=dict(tf=5), money=0, series="near", unds=("BANKNIFTY",), exits=dict(sq_off=SQ15),
                   expiry="skip", pos=ONE, window=(OPEN, SQ15 - 1)),
         grid=[dict(params=dict(tf=3)), dict(params=dict(tf=15)), dict(money=2), dict(params=dict(tf=3), money=2),
               dict(params=dict(tf=15), money=2)]),
    # ------------------------------------------------------------------ MP-1
    dict(key="MP-1", title="Boss's Matrix Premium v2.2 Pine, BN", source="index",
         why="original = Boss's script as written: 5-min EMA 20 cross, index SL 2.7 x ATR14, TP 2.5R, dead zone "
             "11:00-13:15, ATM, 1 lot, 15:15 (research/matrix_pine.py). Grid = 2-ITM, no dead zone (the 2-lot TP1 "
             "partial rows are left).",
         orig=dict(fn=matrix_premium, params=dict(dead=True), money=0, series="near", unds=("BANKNIFTY",),
                   exits=dict(sq_off=SQ15), expiry="skip", pos=ONE, window=(9 * 60 + 24, SQ15 - 1)),
         grid=[dict(money=2), dict(params=dict(dead=False))]),
    # ------------------------------------------------------------------ MV-1
    dict(key="MV-1", title="Matrix v2.2 risk engine, structure-break entries, BN", source="index",
         why="original = the study's default: 5-min, SL pivot 2 x ATR, TP1 1R half + breakeven, lock, 2 lots "
             "(research/matrix_v22.py). Two 1-lot rows per entry (half A / B). Grid = its 15-min chart, CHoCH only, "
             "fixed / ribbon, buffer 1.5 / 2.5, TP1 1.5R, the tight 0.5 ATR row.",
         orig=dict(fn=matrix_v22, params=dict(tf=5, buf=2.0, tp1=1.0, mode="lock"), money=0, series="near",
                   unds=("BANKNIFTY",), exits=dict(sq_off=SQ10), expiry="skip", pos=dict(one_at_a_time=False),
                   window=(9 * 60 + 29, 14 * 60 + 29)),
         grid=[dict(params=dict(tf=15)), dict(params=dict(tf=15, choch_only=True)), dict(params=dict(choch_only=True)),
               dict(params=dict(mode="fixed")), dict(params=dict(mode="ribbon")),
               dict(params=dict(tf=15, mode="fixed")), dict(params=dict(tf=15, mode="ribbon")),
               dict(params=dict(buf=1.5)), dict(params=dict(buf=2.5)), dict(params=dict(tf=15, buf=1.5)),
               dict(params=dict(tf=15, buf=2.5)), dict(params=dict(tp1=1.5)), dict(params=dict(tf=15, tp1=1.5)),
               dict(params=dict(buf=0.5)), dict(params=dict(tf=15, buf=0.5))]),
]
