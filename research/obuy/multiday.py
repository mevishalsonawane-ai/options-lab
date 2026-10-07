"""Multi-day holds (overnight / BTST / positional) for the intraday engine.

A signal set is multi-day when it has an `exit_day` column (datetime.date) and an `exit_min` column (clock minute on
exit_day at whose OPEN the position is closed; the time exit). The engine's exits run unchanged on a COMPOSITE path of
at most 375 columns that spans entry day .. exit day:

  * entry day: 1-minute columns from `lo` (the entry window's start) to `fine_hi` (window end + a few minutes), then
    b-minute buckets to 15:29;
  * every later day: b-minute buckets from 09:15 (a bucket never spans two days, so an overnight gap shows up as the
    next bucket's OPEN, where a stop gapped through fills, exactly as on a 1-minute bar);
  * exit day: buckets up to exit_min, then 1-minute columns from exit_min (the time exit fills at that minute's open).

b = 1 (true 1-minute paths) whenever everything fits in 374 columns (BTST does), else the smallest b that fits.
A bucket's o / h / l / c / volume = first open / max high / min low / last close / sum, so premium stops, targets,
locks and index stops still see every minute's high and low (a stop that triggers mid-bucket fills at the trigger;
only the "gap through" open is approximated by the bucket's first open). Decisions on a bucket's close fill at the
next bucket's open (up to b minutes later than on 1-minute data).

Conventions: `sig_min`, `exit_at`, entry_min / exit_min in the trades are COMPOSITE minutes (555 + column), not clock
times; the trade's `day` (P&L date) is the ENTRY day. Exits must use sq_off = MD_SQ (the path's end). The contract is
the one picked on the entry day (strike rule on the entry day's spot); it is followed by strike on each later day's
chain of the same series and must not expire before exit_day (checked against the expiry days in the data: weekly =
the next expiry day, monthly = the last expiry day of the month). Days on which the strike is outside the data's
ATM+-10 window have no bars (NaN), as in the intraday engine.

Random-entry pool: same entry day, a uniform random minute in the strategy's window, a coin-flip side, the same strike
rule, the same exit day / time and the same index stop / target distances.
"""
from __future__ import annotations

import math
from bisect import bisect_left

import numpy as np

from . import config as C
from .data import dnum

MD_SQ = C.LAST_M + 1          # 15:30: the composite path's end (use as Exits.sq_off for multi-day strategies)


def expiry_tables(ix):
    """(weekly-or-near expiries sorted, monthly expiries sorted) from the index's expiry flags."""
    ex = sorted(d for d in ix.days if ix.d[d]["exp"])
    last = {}
    for d in ex:
        last[(d.year, d.month)] = d
    return ex, sorted(last.values())


def next_on_or_after(lst, d):
    i = bisect_left(lst, d)
    return lst[i] if i < len(lst) else None


def contract_expiry(ix, d, series, _cache={}):
    key = id(ix)
    if key not in _cache:
        _cache[key] = expiry_tables(ix)
    wk, mo = _cache[key]
    return next_on_or_after(mo if series == "month" else wk, d)


def _grid(ndays, lo, fine_hi, exit_min):
    """Column segments: list over day offsets of (fine_end, b-bucket starts, tail 1-minute starts). Returns
    (cols, b) where cols = list of (t, s, e) minute ranges [s, e) per column (t = day offset)."""
    for b in range(1, 400):
        cols = []
        for t in range(ndays):
            s0 = lo if t == 0 else C.OPEN_M
            end = exit_min if t == ndays - 1 else C.LAST_M + 1
            m = s0
            if t == 0:
                fe = min(fine_hi + 1, end)
                while m < fe:
                    cols.append((t, m, m + 1))
                    m += 1
            while m < end:
                e = min(m + b, end)
                cols.append((t, m, e))
                m = e
            if t == ndays - 1:
                for mm in range(exit_min, min(exit_min + 5, C.LAST_M + 1)):
                    cols.append((t, mm, mm + 1))
        if len(cols) <= C.W - 1:
            return cols, b
    raise ValueError("hold too long for a composite path")


def _agg(rows, cols, days_rows):
    """rows: per day offset t, a tuple of (o, h, l, c, v) W-arrays (or None = no data). -> 5 composite W-arrays."""
    n = len(cols)
    out = [np.full(C.W, np.nan) for _ in range(5)]
    # group columns by day
    i = 0
    while i < n:
        t = cols[i][0]
        j = i
        while j < n and cols[j][0] == t:
            j += 1
        r = rows[t]
        if r is not None:
            o, h, l, c, v = r
            s = np.array([cols[k][1] - C.OPEN_M for k in range(i, j)])
            e = np.array([cols[k][2] - C.OPEN_M for k in range(i, j)])
            lo_, hi_ = s[0], e[-1]
            idx = np.arange(lo_, hi_)
            rel = s - lo_
            okc = ~np.isnan(c[lo_:hi_])
            fi = np.minimum.reduceat(np.where(okc, idx, 10**6), rel)
            la = np.maximum.reduceat(np.where(okc, idx, -1), rel)
            has = fi < 10**6
            fic = np.where(has, fi, 0)
            lac = np.where(has, la, 0)
            out[0][i:j] = np.where(has, o[fic], np.nan)
            with np.errstate(all="ignore"):
                out[1][i:j] = np.where(has, np.fmax.reduceat(h[lo_:hi_], rel), np.nan)
                out[2][i:j] = np.where(has, np.fmin.reduceat(l[lo_:hi_], rel), np.nan)
            out[3][i:j] = np.where(has, c[lac], np.nan)
            if v is not None:
                out[4][i:j] = np.where(has, np.add.reduceat(np.nan_to_num(v[lo_:hi_]), rel), np.nan)
        i = j
    return out


def build_multi(jobs, sigs, kcap, multi, store, rng, metas):
    from .data import market
    from .engine import _strike_for, _vol5
    import pandas as pd
    mk = market()
    parts = [sigs[j].assign(jobid=j) for j in multi if not sigs[j].empty]
    if not parts:
        return
    allsig = pd.concat(parts, ignore_index=True)
    wins = {}
    for j in multi:
        s = sigs[j]
        if not s.empty:
            wins[j] = jobs[j][4] if jobs[j][4] else (int(s.sig_min.min()), int(s.sig_min.max()))
    for und, g in allsig.groupby("und", sort=False):
        ix = mk.index(und)
        opts = mk.options(und)
        M = ix.mat()
        step = C.STEP[und]
        bse = und in C.BSE
        for r in g.sort_values(["day", "jobid"], kind="stable").itertuples(index=False):
            if True:
                j = int(r.jobid)
                rule, exe, window, pool_same_side = jobs[j][1], jobs[j][2], jobs[j][4], jobs[j][5]
                pool_k = kcap[j]
                wlo, whi = wins[j]
                d, xd, xm = r.day, r.exit_day, int(r.exit_min)
                if d not in ix.pos or xd not in ix.pos or xd < d:
                    continue
                fd = ix.d[d]
                if (exe.expiry == "skip" and fd["exp"]) or (exe.expiry == "only" and not fd["exp"]) or (exe.real_index and not fd["real"]):
                    continue
                cexp = contract_expiry(ix, d, rule.series)
                if cexp is None or xd > cexp:
                    continue
                span = ix.days[ix.pos[d]: ix.pos[xd] + 1]
                lo = min(wlo, int(r.sig_min))
                fine_hi = max(whi, int(r.sig_min)) + exe.max_delay + 3
                if len(span) == 1:
                    fine_hi = min(fine_hi, xm - 1)
                try:
                    cols, b = _grid(len(span), lo, fine_hi, xm)
                except ValueError:
                    continue
                gkey = (d, xd, xm, lo, fine_hi)
                colpos = {(c[0], c[1]): k for k, c in enumerate(cols) if c[2] - c[1] == 1}
                xcol = colpos.get((len(span) - 1, xm))
                if xcol is None:
                    continue
                chains = [opts.chain(dd, rule.series) for dd in span]
                if chains[0] is None:
                    continue
                ser0 = chains[0].series
                Ic = M["c"][ix.pos[d]]
                irow_key = (und, gkey, "md")
                if irow_key not in store.ikey:
                    ih = [(None, M["h"][ix.pos[dd]], M["l"][ix.pos[dd]], M["c"][ix.pos[dd]], None) for dd in span]
                    agg = _agg([(x[1], x[1], x[2], x[3], None) for x in ih], cols, None)
                    store.index(irow_key, dict(h=agg[1], l=agg[2]))
                irow = store.ikey[irow_key]
                lot_day = ix.lot(d, exe.lot_mode)

                def path(right, k):
                    key = (und, gkey, ser0, k, right, "md")
                    if key in store.ckey:
                        return store.ckey[key]
                    rows = []
                    for ch in chains:
                        if ch is None or ch.series != ser0:
                            rows.append(None)
                            continue
                        i = ch.kpos(k)
                        rows.append(None if i < 0 else (ch.o[right][i], ch.h[right][i], ch.l[right][i], ch.c[right][i], ch.v[right][i]))
                    if rows[0] is None:
                        return None
                    return store.contract(key, _agg(rows, cols, None))

                def cut(kind, sm, side, ref, strike, istop, itgt, cand, parent, book, tag, lot_o):
                    col = colpos.get((0, sm))
                    if col is None or col >= C.W - 2:
                        return
                    if not np.isfinite(ref):
                        x = Ic[: sm - C.OPEN_M + 1]
                        ok = np.nonzero(~np.isnan(x))[0]
                        if not len(ok):
                            return
                        ref = x[ok[-1]]
                    if side == 0:
                        kc = int(strike) if np.isfinite(strike) else int(_strike_for(rule, 1, ref, step))
                        kp = int(strike) if np.isfinite(strike) else int(_strike_for(rule, -1, ref, step))
                        legs = [("C", kc), ("P", kp)]
                    else:
                        right = "C" if side > 0 else "P"
                        k = int(strike) if np.isfinite(strike) else int(_strike_for(rule, side, ref, step))
                        legs = [(right, k)]
                    crows = []
                    for right, k in legs:
                        cr = path(right, k)
                        if cr is None:
                            return
                        crows.append(cr)
                    Oa = store.c["O"] if store.c is not None else None
                    cf = col + 1
                    ok = np.ones(C.W, bool)
                    for cr in crows:
                        ok &= ~np.isnan(Oa[cr])
                    nz = np.nonzero(ok[cf:cf + exe.max_delay + 1])[0]
                    if not len(nz):
                        return
                    c0 = cf + int(nz[0])
                    if c0 >= xcol:
                        return
                    lot = int(lot_o) if np.isfinite(lot_o) else lot_day
                    es = []
                    for cr in crows:
                        V = store.c["V"][cr]
                        if exe.min_vol_lots and np.nansum(V[:c0]) < exe.min_vol_lots * lot:
                            return
                        v5 = _vol5(V, c0) / lot
                        es.append(float(exe.fills.buy(np.array([round(float(Oa[cr][c0]), 2)]), np.array([v5]))[0]))
                    e = sum(es)
                    if e <= exe.min_premium:
                        return
                    qty = exe.lots * lot
                    if exe.budget:
                        n = int(np.floor(exe.budget / (e * lot) + 1e-9))
                        if n <= 0:
                            return
                        qty = n * lot
                    csm = C.OPEN_M + col
                    metas[j][0 if kind == "real" else 1].append(dict(
                        cand=cand, parent=parent, und=und, day=d, dn=dnum(d), book=book, gate=csm + 1, sig_min=csm,
                        side=side, right="".join(x for x, _ in legs), strike=legs[0][1], strike2=legs[-1][1], lot=lot,
                        qty=qty, c0=c0, e=e, e1=es[0], e2=es[-1] if len(es) > 1 else np.nan, ref=ref, idx_stop=istop,
                        idx_target=itgt, exit_at=C.OPEN_M + xcol, tag=tag, bse=bse, crow=crows[0], crow2=crows[-1],
                        irow=irow))

                sm0 = int(r.sig_min)
                lot_o = float(r.lot) if np.isfinite(r.lot) else np.nan
                cut("real", sm0, int(r.side), float(r.ref_spot), float(r.strike), float(r.idx_stop), float(r.idx_target),
                    int(r.cand), -1, r.book, r.tag, lot_o)
                x = Ic[: sm0 - C.OPEN_M + 1]
                okx = np.nonzero(~np.isnan(x))[0]
                ref0 = float(r.ref_spot) if np.isfinite(r.ref_spot) else (x[okx[-1]] if len(okx) else np.nan)
                for _ in range(pool_k):
                    sm = int(rng.integers(wlo, whi + 1))
                    sd = int(r.side) if (pool_same_side or r.side == 0) else int(rng.choice((1, -1)))
                    xx = Ic[: sm - C.OPEN_M + 1]
                    ok1 = np.nonzero(~np.isnan(xx))[0]
                    if not len(ok1):
                        continue
                    ref1 = xx[ok1[-1]]
                    ist = itg = np.nan
                    if np.isfinite(r.idx_stop) and r.side != 0:
                        ist = ref1 - sd * (r.side * (ref0 - r.idx_stop))
                    if np.isfinite(r.idx_target) and r.side != 0:
                        itg = ref1 + sd * (r.side * (r.idx_target - ref0))
                    cut("pool", sm, sd, ref1, np.nan, ist, itg, int(r.cand), int(r.cand), r.book, "", lot_o)
        mk.release(und)
