"""R8: order-flow trap measurements on the R7 Dhan 5-level MCX sample (one evening, 34 min, 14 contracts).

Measures, per instrument (futures in detail, options summarised):
  1. feed health: duplicates, stale (volume steps back) packets, crossed books, update gaps, trade-to-receive latency
  2. flicker: size added at a visible price level that is gone again within 1/3/5/10 s with NO trade at that price
     (pure cancel), vs filled, vs still there; plus new best prices (inside the spread) that vanish untraded
  3. walls: levels with qty >= K x trailing median level size (K = 2,3,5); lifetime, how they end
     (pulled / traded / out of view), and single-order vs many-order walls
  4. pull timing: hazard of a wall pull in the last 5 s before a 1-min / 5-min boundary vs other seconds
  5. iceberg-like refills / absorption at the best price: traded volume at a price > displayed size there,
     and what the price did next (held = absorption, broke = exhaustion)
  6. total buy/sell qty (TBQ/TSQ): share of it visible in 5 levels, jumps without trades
  7. stop-hunt-like breakouts of the trailing 5-min range in the futures and how fast they reverse
  8. candle-close painting: does the move in the last 5 s of a minute reverse in the next 10 s more than other 5-s moves?
Outputs in scratchpad/hunt/r8 (csv + traps.log). One evening: anecdote-sized, read the counts.
"""
import json
from collections import defaultdict
from pathlib import Path
import numpy as np, pandas as pd

R7 = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r7")
OUT = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r8")
OUT.mkdir(parents=True, exist_ok=True)
LOG = open(OUT / "traps.log", "w")
def say(*a):
    s = " ".join(str(x) for x in a); print(s); LOG.write(s + "\n"); LOG.flush()

raw = pd.read_parquet(R7 / "ticks.parquet")
raw["ord"] = np.arange(len(raw))
BOOK = [f"{x}{k}" for k in range(5) for x in ("bq", "aq", "bo", "ao", "bp", "ap")]
KEYS = BOOK + ["vol", "ltp", "ltq", "ltt", "tbq", "tsq", "oi"]
IST = 19800

def clean(g):
    n0 = len(g)
    g = g.sort_values("ord")
    dup = g[KEYS].eq(g[KEYS].shift()).all(axis=1)
    g1 = g[~dup]
    stale = g1.vol < g1.vol.cummax()
    # a stale packet = volume below the running max (an older state re-sent)
    g2 = g1[~stale]
    g2 = g2[(g2.bp0 > 0) & (g2.ap0 > 0)]
    crossed = (g2.bp0 >= g2.ap0).mean()
    g2 = g2[g2.ap0 > g2.bp0].copy()
    return g2, dict(packets=n0, dup_share=dup.mean(), stale_share=stale.sum() / n0, crossed_share=crossed, distinct=len(g2))

def tickround(x):
    return np.round(x, 2)

# ---------------------------------------------------------------- per-snapshot book as dicts
def books(g):
    T = g.ts.values; V = g.vol.values; L = tickround(g.ltp.values)
    bids, asks = [], []
    for r in g[BOOK].itertuples(index=False):
        d = r._asdict()
        b = {tickround(d[f"bp{k}"]): (d[f"bq{k}"], d[f"bo{k}"]) for k in range(5) if d[f"bq{k}"] > 0 and d[f"bp{k}"] > 0}
        a = {tickround(d[f"ap{k}"]): (d[f"aq{k}"], d[f"ao{k}"]) for k in range(5) if d[f"aq{k}"] > 0 and d[f"ap{k}"] > 0}
        bids.append(b); asks.append(a)
    return T, V, L, bids, asks

def in_view(side, p, book):
    if not book: return False
    lo, hi = min(book), max(book)
    if side == "b":   # bid view = [lowest shown bid, +inf) : a price above best that vanished was consumed/pulled, not hidden
        return p >= lo
    return p <= hi

# ---------------------------------------------------------------- 2. flicker of added size
HZ = (1, 3, 5, 10)
def flicker(T, V, L, bids, asks):
    """Every increase of qty at a visible price = an 'add chunk' of size d. Follow the price until the added size is
    gone (qty back to <= base) or 10 s pass or the price leaves view. Gone with traded volume at that price
    < half the chunk = CANCEL (fleeting); else FILLED."""
    rows = []
    for side, BK in (("b", bids), ("a", asks)):
        open_ = {}   # price -> list of [t0, base, size, traded_at_p, best_at_birth]
        for i in range(1, len(T)):
            prev, cur = BK[i - 1], BK[i]
            dv = max(V[i] - V[i - 1], 0)
            best = (max(cur) if side == "b" else min(cur)) if cur else None
            # traded volume attribution: trades print at LTP
            for p, lst in list(open_.items()):
                if dv > 0 and L[i] == p:
                    for c in lst: c[3] += dv
                q = cur.get(p, (0, 0))[0]
                if not in_view(side, p, cur) and p not in cur:
                    for c in lst: rows.append((side, c[0], T[i] - c[0], c[2], "outofview", c[4]))
                    del open_[p]; continue
                keep = []
                for c in lst:
                    age = T[i] - c[0]
                    if q <= c[1]:
                        rows.append((side, c[0], age, c[2], "filled" if c[3] >= 0.5 * c[2] else "cancel", c[4]))
                    elif age > 10:
                        rows.append((side, c[0], age, c[2], "persist", c[4]))
                    else:
                        keep.append(c)
                if keep: open_[p] = keep
                else: del open_[p]
            for p, (q, o) in cur.items():
                q0 = prev.get(p, (0, 0))[0]
                if q > q0 and in_view(side, p, prev) or (q > q0 and p not in prev and prev and
                                                          ((side == "b" and p > max(prev)) or (side == "a" and p < min(prev)))):
                    open_.setdefault(p, []).append([T[i], q0, q - q0, 0, p == best])
    return pd.DataFrame(rows, columns=["side", "t0", "life", "size", "end", "atbest"])

# ---------------------------------------------------------------- 3/4. walls
def walls(T, V, L, bids, asks, K):
    allq = np.array([np.median([v[0] for v in list(b.values()) + list(a.values())]) for b, a in zip(bids, asks)])
    ref = pd.Series(allq, index=pd.to_datetime(T, unit="s")).rolling("300s").median().values
    eps = []
    for side, BK in (("b", bids), ("a", asks)):
        live = {}  # p -> [t0, peak, orders_at_peak, traded, ref]
        for i in range(1, len(T)):
            cur, prev = BK[i], BK[i - 1]
            dv = max(V[i] - V[i - 1], 0)
            for p, w in list(live.items()):
                q = cur.get(p, (0, 0))[0]
                if dv > 0 and L[i] == p: w[3] += dv
                if p not in cur and not in_view(side, p, cur):
                    eps.append((side, w[0], T[i], T[i] - w[0], w[1], w[2], w[4], "outofview", w[1] - q)); del live[p]; continue
                if q < 0.5 * w[1]:
                    drop = w[1] - q
                    end = "traded" if w[3] >= 0.5 * drop else "pulled"
                    eps.append((side, w[0], T[i], T[i] - w[0], w[1], w[2], w[4], end, drop)); del live[p]; continue
                if q > w[1]: w[1] = q; w[2] = cur[p][1]
            for p, (q, o) in cur.items():
                if p not in live and q >= K * ref[i] and q >= 3:
                    live[p] = [T[i], q, o, 0, ref[i]]
        for p, w in live.items():
            eps.append((side, w[0], T[-1], T[-1] - w[0], w[1], w[2], w[4], "open", 0))
    return pd.DataFrame(eps, columns=["side", "t0", "t1", "life", "peak", "orders", "ref", "end", "drop"])

def exposure_by_phase(wdf, period, last):
    """wall-seconds spent in the last `last` s before a `period` boundary vs elsewhere, and pulls in each."""
    inw = outw = 0.0
    for t0, t1 in zip(wdf.t0, wdf.t1):
        grid = np.arange(np.floor(t0 * 10) / 10, t1, 0.1)
        ph = np.mod(grid, period)
        inw += np.sum(ph >= period - last) * 0.1; outw += np.sum(ph < period - last) * 0.1
    pulls = wdf[wdf.end == "pulled"]
    php = np.mod(pulls.t1.values, period)
    pin = int(np.sum(php >= period - last)); pout = int(np.sum(php < period - last))
    return pin, inw, pout, outw

# ---------------------------------------------------------------- 5. absorption / refills at best
def absorption(T, V, L, bids, asks, mid):
    """Episode = best price unchanged on a side. Track traded volume printed at that price, max displayed qty, and
    refills (qty at p rises again after a trade at p). Flag when traded >= 1.5 x max displayed and >= 2 trades."""
    out = []
    for side, BK in (("b", bids), ("a", asks)):
        cur_p = None
        for i in range(1, len(T)):
            bk = BK[i]
            if not bk: continue
            best = max(bk) if side == "b" else min(bk)
            q = bk[best][0]; dv = max(V[i] - V[i - 1], 0)
            if best != cur_p:
                if cur_p is not None and ep["traded"] > 0:
                    broke = (best < cur_p) if side == "b" else (best > cur_p)
                    ep.update(t1=T[i], broke=broke); out.append(ep)
                cur_p = best
                ep = dict(side=side, p=best, t0=T[i], maxq=q, traded=0, ntr=0, refills=0, lastq=q, flag_t=np.nan, mid_flag=np.nan)
                continue
            if dv > 0 and L[i] == best:
                ep["traded"] += dv; ep["ntr"] += 1; ep["after_trade"] = True
            elif q > ep["lastq"] and ep.get("after_trade"):
                ep["refills"] += 1; ep["after_trade"] = False
            ep["maxq"] = max(ep["maxq"], q); ep["lastq"] = q
            if np.isnan(ep["flag_t"]) and ep["traded"] >= 1.5 * ep["maxq"] and ep["ntr"] >= 2:
                ep["flag_t"] = T[i]; ep["mid_flag"] = mid[i]
    return pd.DataFrame(out)

def fwd_move(ts, mid, t, h):
    j = np.searchsorted(ts, t + h)
    return np.nan if j >= len(ts) else mid[j]

# ================================================================ run
meta = json.load(open(R7 / "instruments.json"))
health, flick_rows, wall_rows, abs_rows = [], [], [], []
fut_series = {}
for sid, g0 in raw.groupby("sid"):
    sym, kind = meta[sid]["sym"], meta[sid]["kind"]
    name = f"{sym}-{kind}{'' if kind == 'FUT' else int(meta[sid]['strike'])}"
    g, h = clean(g0)
    T, V, L, bids, asks = books(g)
    mid = ((g.bp0 + g.ap0) / 2).values
    # feed health
    gaps = np.diff(T)
    tr = g[g.vol.diff() > 0]
    lat = (tr.ts - (tr.ltt - IST)).values
    h.update(name=name, kind=kind, gap_med=np.median(gaps), gap_p99=np.percentile(gaps, 99), gap_max=gaps.max(),
             gaps_over_5s=int((gaps > 5).sum()), trade_lat_p05=np.percentile(lat, 5), trade_lat_med=np.median(lat),
             vis_share_tbq=float(np.median(g[[f"bq{k}" for k in range(5)]].sum(axis=1) / g.tbq)),
             vis_share_tsq=float(np.median(g[[f"aq{k}" for k in range(5)]].sum(axis=1) / g.tsq)))
    # TBQ jumps without trades
    dtb = g.tbq.diff().abs(); dts = g.tsq.diff().abs(); dvol = g.vol.diff().clip(lower=0)
    vis = g[[f"bq{k}" for k in range(5)] + [f"aq{k}" for k in range(5)]].sum(axis=1)
    ch = (dtb > 0) | (dts > 0)
    no_tr = ch & (dvol == 0)
    vis_d = (g[[f"bq{k}" for k in range(5)]].sum(axis=1).diff().abs() + g[[f"aq{k}" for k in range(5)]].sum(axis=1).diff().abs())
    h.update(tq_changes_no_trade=float(no_tr.sum() / max(ch.sum(), 1)),
             tq_jump_vs_visdepth_p99=float(np.nanpercentile(((dtb + dts) / vis)[ch], 99)),
             tq_change_unexplained_by_visible=float(np.nanmedian(((dtb + dts) - vis_d).clip(lower=0)[ch] / (dtb + dts)[ch])))
    health.append(h)
    f = flicker(T, V, L, bids, asks); f["name"] = name; f["kind"] = kind; flick_rows.append(f)
    for K in (2, 3, 5):
        w = walls(T, V, L, bids, asks, K); w["name"] = name; w["kind"] = kind; w["K"] = K
        if K == 3:
            # did a bid wall pull come before a move up/down? (classic spoof: wall on one side, price goes the OTHER way)
            w["mv10"] = [ (fwd_move(T, mid, t1, 10) - mid[min(np.searchsorted(T, t1), len(T) - 1)]) / mid[0] * 1e4
                          for t1 in w.t1 ]
        wall_rows.append(w)
    a = absorption(T, V, L, bids, asks, mid); a["name"] = name; a["kind"] = kind
    if len(a):
        for hh in (30, 60):
            a[f"mv{hh}"] = [ (fwd_move(T, mid, t, hh) - m) / m * 1e4 if not np.isnan(t) else np.nan
                            for t, m in zip(a.flag_t, a.mid_flag)]
    abs_rows.append(a)
    if kind == "FUT":
        fut_series[name] = pd.Series(mid, index=pd.to_datetime(T, unit="s"))

H = pd.DataFrame(health).set_index("name"); H.to_csv(OUT / "feed_health.csv")
say("== 1. FEED HEALTH (per instrument)")
say(H[["packets", "dup_share", "stale_share", "crossed_share", "distinct", "gap_med", "gap_p99", "gap_max", "gaps_over_5s",
       "trade_lat_p05", "trade_lat_med", "vis_share_tbq", "vis_share_tsq", "tq_changes_no_trade",
       "tq_jump_vs_visdepth_p99", "tq_change_unexplained_by_visible"]].round(3).to_string())

F = pd.concat(flick_rows); F.to_csv(OUT / "flicker_chunks.csv", index=False)
say("\n== 2. FLICKER: size added at a visible level - how it ends (share of chunks; life = time to end)")
def fl_table(F):
    rows = []
    for (nm), x in F.groupby(["name"]):
        r = dict(name=nm, n=len(x))
        for hz in HZ:
            r[f"cancel<{hz}s"] = ((x.end == "cancel") & (x.life <= hz)).mean()
        r["filled"] = (x.end == "filled").mean(); r["persist>10s"] = (x.end == "persist").mean(); r["outofview"] = (x.end == "outofview").mean()
        r["cancel_share_of_resolved"] = (x.end == "cancel").sum() / max(((x.end == "cancel") | (x.end == "filled")).sum(), 1)
        xb = x[x.atbest]
        r["best_cancel<3s"] = ((xb.end == "cancel") & (xb.life <= 3)).mean() if len(xb) else np.nan
        big = x[x["size"] >= x["size"].quantile(0.9)]
        r["big10%_cancel<3s"] = ((big.end == "cancel") & (big.life <= 3)).mean()
        r["big10%_size"] = big["size"].min()
        rows.append(r)
    return pd.DataFrame(rows).set_index("name")
FT = fl_table(F); FT.to_csv(OUT / "flicker_summary.csv"); say(FT.round(3).to_string())
# pooled option vs fut
for k, x in F.groupby(F.kind == "FUT"):
    say(f"  pooled {'FUT' if k else 'OPT'}: chunks {len(x)}, cancelled within 1/3/5 s: " +
        ", ".join(f"{((x.end=='cancel')&(x.life<=hz)).mean():.0%}" for hz in (1, 3, 5)) +
        f"; survive >=3 s (persist or end later than 3 s): {(x.life>3).mean():.0%}")

W = pd.concat(wall_rows); W.to_csv(OUT / "walls.csv", index=False)
say("\n== 3. WALLS (qty >= K x trailing-5-min median level size). life in s; ends")
rows = []
for (K, nm), x in W.groupby(["K", "name"]):
    done = x[x.end != "open"]
    rows.append(dict(K=K, name=nm, n=len(x), peak_max=x.peak.max(), peak_over_ref_max=(x.peak / x.ref).max(),
                     life_med=done.life.median(), life_p90=done.life.quantile(0.9),
                     pulled=(done.end == "pulled").mean(), traded=(done.end == "traded").mean(),
                     outofview=(done.end == "outofview").mean(),
                     pulled_lt3s=((done.end == "pulled") & (done.life < 3)).mean(),
                     pulled_lt5s=((done.end == "pulled") & (done.life < 5)).mean(),
                     single_order=(x.orders == 1).mean()))
WT = pd.DataFrame(rows); WT.to_csv(OUT / "walls_summary.csv", index=False); say(WT.round(3).to_string(index=False))
for K, x in W[W.end != "open"].groupby("K"):
    say(f"  pooled K={K}: walls {len(x)}  fut {int((x.kind=='FUT').sum())}; median life {x.life.median():.1f}s; "
        f"pulled {(x.end=='pulled').mean():.0%} traded {(x.end=='traded').mean():.0%} out-of-view {(x.end=='outofview').mean():.0%}; "
        f"pulled within 3 s {((x.end=='pulled')&(x.life<3)).mean():.0%}, within 5 s {((x.end=='pulled')&(x.life<5)).mean():.0%}")
    s1 = x[x.orders == 1]; sm = x[x.orders >= 3]
    say(f"     single-order walls n={len(s1)} pulled {(s1.end=='pulled').mean():.0%} | >=3-order walls n={len(sm)} pulled {(sm.end=='pulled').mean():.0%}")
    surv = {s: (x.life >= s).mean() for s in (1, 3, 5, 10, 30, 60)}
    say("     share of walls alive at " + ", ".join(f"{s}s {v:.0%}" for s, v in surv.items()))

say("\n== 3b. after a K=3 wall is PULLED, 10-s mid move (bp) in the wall's direction (bid wall: +up)")
x = W[(W.K == 3) & (W.end == "pulled")].copy()
x["mv_dir"] = np.where(x.side == "b", 1, -1) * x.mv10
for k, y in x.groupby(x.kind == "FUT"):
    say(f"  {'FUT' if k else 'OPT'} pulls n={len(y)}: mean {y.mv_dir.mean():+.2f} bp, median {y.mv_dir.median():+.2f}, "
        f"share moving AGAINST the wall side {(y.mv_dir<0).mean():.0%}, with it {(y.mv_dir>0).mean():.0%}")

say("\n== 4. PULL TIMING: wall-pull hazard in the last 5 s (and 2 s) before 1-min / 5-min boundaries vs other seconds")
pt = []
for K in (2, 3):
    x = W[(W.K == K)]
    for period, last in ((60, 5), (60, 2), (300, 5), (300, 10)):
        pin, inw, pout, outw = exposure_by_phase(x, period, last)
        rin, rout = pin / inw, pout / outw
        # Poisson exact-ish test: under equal hazard, pin ~ Binomial(pin+pout, inw/(inw+outw))
        from scipy.stats import binomtest
        pv = binomtest(pin, pin + pout, inw / (inw + outw)).pvalue if pin + pout else np.nan
        pt.append(dict(K=K, period=period, last=last, pulls_in=pin, wall_s_in=round(inw), pulls_out=pout, wall_s_out=round(outw),
                       hazard_in=rin, hazard_out=rout, ratio=rin / rout if rout else np.nan, p=pv))
PT = pd.DataFrame(pt); PT.to_csv(OUT / "pull_timing.csv", index=False); say(PT.round(4).to_string(index=False))
# end-of-session (MCX closes 23:30 IST in Oct) : last 5 min vs before
say("  end of session (last 5 min before 23:30 IST, recording ends 23:29):")
cut = pd.Timestamp("2026-10-09 23:25").tz_localize(None).value / 1e9 - IST
for K in (2, 3):
    x = W[W.K == K]
    late = x.t1 >= cut
    pin = int(((x.end == "pulled") & late).sum()); pout = int(((x.end == "pulled") & ~late).sum())
    # exposure split
    inw = sum(max(0, t1 - max(t0, cut)) for t0, t1 in zip(x.t0, x.t1)); outw = sum(max(0, min(t1, cut) - t0) for t0, t1 in zip(x.t0, x.t1))
    say(f"   K={K}: pulls/wall-hour last 5 min {pin/inw*3600:.1f} (n={pin}) vs earlier {pout/outw*3600:.1f} (n={pout})")

A = pd.concat(abs_rows); A.to_csv(OUT / "absorption.csv", index=False)
say("\n== 5. ABSORPTION / ICEBERG-LIKE at the best price (episode = best price unchanged on that side)")
for k, x in A.groupby(A.kind == "FUT"):
    fl = x[~x.flag_t.isna()]
    say(f"  {'FUT' if k else 'OPT'}: episodes with trades {len(x)}; refills>=1 {(x.refills>=1).mean():.0%}, >=3 {(x.refills>=3).mean():.0%}; "
        f"flagged (traded >= 1.5 x max shown, >=2 prints) {len(fl)} = {len(fl)/len(x):.0%}")
    if len(fl):
        held = ~fl.broke.astype(bool)
        sgn = np.where(fl.side == "b", 1, -1)
        say(f"     flagged: price level HELD (absorption) {held.mean():.0%} / BROKE (exhaustion) {(~held).mean():.0%}; "
            f"mid move after flag in the defended direction: 30 s mean {np.nanmean(sgn*fl.mv30):+.2f} bp "
            f"(median {np.nanmedian(sgn*fl.mv30):+.2f}, >0 share {np.nanmean(sgn*fl.mv30>0):.0%}), "
            f"60 s mean {np.nanmean(sgn*fl.mv60):+.2f} bp (n={np.isfinite(fl.mv60).sum()})")
        say(f"     median traded/max-shown on flagged {np.median(fl.traded/fl.maxq):.1f}x, median refills {fl.refills.median():.0f}, "
            f"median episode life {np.median(fl.t1-fl.t0):.1f}s")
    nf = x[x.flag_t.isna() & (x.traded > 0)]
    say(f"     unflagged episodes broke {nf.broke.astype(bool).mean():.0%}")

say("\n== 7. STOP-HUNT-LIKE BREAKOUTS of the trailing 5-min high/low (futures mid, 1-s grid)")
sh = []
for nm, s in fut_series.items():
    s = s.groupby(s.index.floor("1s")).last().asfreq("1s").ffill()
    hi = s.rolling("300s").max().shift(1); lo = s.rolling("300s").min().shift(1)
    tick = 1.0 if "CRUDE" in nm else 0.1
    last_ev = None
    for t in s.index[300:]:
        v = s[t]
        for d, lvl in ((1, hi[t]), (-1, lo[t])):
            if (d == 1 and v >= lvl + tick) or (d == -1 and v <= lvl - tick):
                if last_ev is not None and (t - last_ev).total_seconds() < 60: continue
                last_ev = t
                fut = s[t:t + pd.Timedelta(seconds=180)]
                back = fut[(fut < lvl) if d == 1 else (fut > lvl)]
                rev = (back.index[0] - t).total_seconds() if len(back) else np.nan
                ext = ((fut.max() - lvl) if d == 1 else (lvl - fut.min())) / lvl * 1e4
                cont = (fut.iloc[-1] - v) * d / v * 1e4 if len(fut) > 170 else np.nan
                sh.append(dict(name=nm, t=t, dir=d, rev_s=rev, ext_bp=ext, cont180_bp=cont))
SH = pd.DataFrame(sh); SH.to_csv(OUT / "breakouts.csv", index=False)
for nm, x in SH.groupby("name"):
    say(f"  {nm}: breakouts {len(x)}; back inside the old range within 10 s {np.mean(x.rev_s<=10):.0%}, 30 s {np.mean(x.rev_s<=30):.0%}, "
        f"60 s {np.mean(x.rev_s<=60):.0%}, 120 s {np.mean(x.rev_s<=120):.0%}, 180 s {np.mean(x.rev_s<=180):.0%}; "
        f"median max extension {x.ext_bp.median():.1f} bp; mean move 180 s later in breakout direction {np.nanmean(x.cont180_bp):+.1f} bp")

say("\n== 8. CANDLE-CLOSE PAINTING: corr(move in a 5-s window, move in the next 10 s), last 5 s of minute vs all other 5-s windows")
for nm, s in fut_series.items():
    s = s.groupby(s.index.floor("1s")).last().asfreq("1s").ffill()
    x = np.log(s.values)
    secs = np.array([t.second for t in s.index])
    r5 = np.full(len(x), np.nan); r10 = np.full(len(x), np.nan)
    r5[5:] = x[5:] - x[:-5]
    r10[:-10] = x[10:] - x[:-10]
    end_min = secs == 0      # window [55,60) ends at second 0 of the next minute
    other = (secs % 5 == 0) & ~end_min
    def c(m):
        a, b = r5[m], r10[m]; ok = np.isfinite(a) & np.isfinite(b) & (a != 0)
        return np.corrcoef(a[ok], b[ok])[0, 1] if ok.sum() > 5 else np.nan, int(ok.sum())
    ce, ne = c(end_min); co, no = c(other)
    vol_end = np.nanstd(r5[end_min]) * 1e4; vol_oth = np.nanstd(r5[other]) * 1e4
    say(f"  {nm}: last-5s-of-minute -> next 10 s corr {ce:+.2f} (n={ne}) vs other 5-s windows {co:+.2f} (n={no}); "
        f"5-s move sd last-5s {vol_end:.2f} bp vs other {vol_oth:.2f} bp")
LOG.close()
