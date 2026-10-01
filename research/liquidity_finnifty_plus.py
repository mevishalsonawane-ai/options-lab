"""Liquidity 15+5 on FINNIFTY, "plus": a small, reasoned set of enhancements, chosen on one year and tested on the other.

    python research/liquidity_finnifty_plus.py [scratch_dir] [out.md]

scratch_dir holds finnifty_index.parquet (index minutes 2024-02-12 .. 2026-02-24) and fin/finnifty_data (the owner's
option upload, 4 expiries). Nothing in the baseline files is changed: the simulate logic of liquidity_break.py is copied
here and extended.

Baseline ("Liquidity 15+5"): 15-min and 5-min books side by side (one position each), source "both" (a pool broken by a
close where an active swing zone of the same side overlaps), swing lookback 20, pools 2 contacts / gap 5 / confirm 10,
exit at the next liquidity / a new liquidity level on the trade's side / a close back through the broken level (failed
break) / 15% premium stop / 15:10; entries 09:20-14:30. BUY the ATM CE on an upside break, ATM PE on a downside one.
1 lot of 65, 0.5 slippage a side, Rs 40 a round trip.

Stage 1 (index, 2 years): variants in index points per trade; Year A = before 2025-02-15, Year B = from 2025-02-15
(each year simulated on its own chart, like liquidity_indices.py). Selection score = ESTIMATED Rs per lot:
a + b * pts per trade, with a, b fitted (OLS) on the baseline's real-option trades on days with the real index.
Stage 2 (real options): the chosen settings plus option-only knobs (premium stop %, 1 strike ITM, profit lock) on the
upload days that have the real index (Oct 2024, Feb-Mar 2025, Oct 2025); March 2026 (synthetic index) shown apart.
"""
from __future__ import annotations

import datetime as dt
import os
import pickle
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from liquidity_indices import load_any  # noqa: E402
from finnifty_upload import load_days  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

LOT, SLIP, CHG, CUT, STEP = 65, 0.5, 40.0, 355, 50
SPLIT = dt.date(2025, 2, 15)
BASE = dict(books=(15, 5), L=20, conf=10, win=(5, 315), prem=0.15, lock=None, be=None, trend=None, vol=None,
            maxday=None, noexp=False, itm=0)


# ---------------------------------------------------------------- expiry calendar (approximate, FINNIFTY)
def expiry_days(trading_days):
    """FINNIFTY expiry days: weekly Tuesdays to 19 Nov 2024; then monthly - last Tuesday (Nov-Dec 2024), last Thursday
    (Jan-Aug 2025, NSE's move of monthly expiries to Thursday), last Tuesday (from Sep 2025). A holiday moves it to the
    previous trading day. Checked against the upload's folders (22 Oct 2024, 27 Mar 2025, 28 Oct 2025, 30 Mar 2026)."""
    td = sorted(trading_days)
    tset = np.array(td, dtype="datetime64[D]")
    targets = []
    d = dt.date(2024, 1, 2)
    while d <= dt.date(2026, 4, 30):
        if d <= dt.date(2024, 11, 19):
            if d.weekday() == 1:
                targets.append(d)
        else:
            wd = 3 if dt.date(2025, 1, 1) <= d <= dt.date(2025, 8, 31) else 1
            nxt = d + dt.timedelta(days=7)
            if d.weekday() == wd and nxt.month != d.month:
                targets.append(d)
        d += dt.timedelta(days=1)
    out = set()
    for t in targets:
        j = np.searchsorted(tset, np.datetime64(t), side="right") - 1
        if j >= 0 and (np.datetime64(t) - tset[j]).astype(int) <= 4:
            out.add(td[j])
    return out


# ---------------------------------------------------------------- bars + per-bar features
def bars(days, tf):
    rows = []
    for di, d in enumerate(days):
        I = d["I"]
        for s in range(0, 375, tf):
            e = min(s + tf, 375)
            rows.append((di, s, e, I["open"][s], I["high"][s:e].max(), I["low"][s:e].min(), I["close"][e - 1]))
    return pd.DataFrame(rows, columns=["di", "s", "e", "open", "high", "low", "close"])


def features(days, b):
    """Per bar, known at the bar's close: EMA(20) slope of the chart closes, the day's open, the day's TWAP so far
    (typical price; the index has no volume, so VWAP is not available), the day's range so far, and the median full-day
    range of the previous 20 days."""
    c = b.close.values
    ema = pd.Series(c).ewm(span=20, adjust=False).mean().values
    slope = np.r_[0.0, np.diff(ema)]
    dayrng = np.array([np.nanmax(d["I"]["high"]) - np.nanmin(d["I"]["low"]) for d in days])
    med = np.array([np.median(dayrng[max(0, k - 20):k]) if k >= 5 else np.nan for k in range(len(days))])
    DI, E = b.di.values, b.e.values
    tw, rg, op = np.empty(len(b)), np.empty(len(b)), np.empty(len(b))
    cache = {}
    for di, d in enumerate(days):
        I = d["I"]
        tp = (I["high"] + I["low"] + I["close"]) / 3
        cache[di] = (np.cumsum(tp) / np.arange(1, 376), np.maximum.accumulate(I["high"]), np.minimum.accumulate(I["low"]),
                     I["open"][0])
    for i in range(len(b)):
        t, hh, ll, o = cache[DI[i]]
        tw[i], rg[i], op[i] = t[E[i] - 1], hh[E[i] - 1] - ll[E[i] - 1], o
    return dict(slope=slope, twap=tw, rng=rg, open=op, med=med[DI])


# ---------------------------------------------------------------- the simulation (liquidity_break.simulate, extended)
def simulate(days, b, zones, f, cfg, expset=frozenset()):
    DI, S, E, C = b.di.values, b.s.values, b.e.values, b.close.values
    breaks, known = {}, {}
    for z in zones:
        known.setdefault(z.known, []).append(z)
        if z.broken >= 0:
            breaks.setdefault(z.broken, []).append(z)
    swings = [z for z in zones if z.kind == "swing"]
    prem, lock, be = cfg["prem"], cfg["lock"], cfg["be"]
    w0, w1 = cfg["win"]
    trades, pos, nday, lastdi = [], None, 0, -1
    for i in range(len(b)):
        d = days[DI[i]]
        I = d["I"]
        if DI[i] != lastdi:
            lastdi, nday = DI[i], 0
        if pos is not None:
            sg = pos["sign"]
            why, xm = None, None
            ch = d["chain"][pos["key"]] if pos["key"] is not None else None
            for m in range(max(S[i], pos["m"]), E[i]):
                if m >= CUT:
                    why, xm = "15:10", m
                    break
                if prem and ch is not None and ch["low"][m] <= pos["px"] * (1 - prem):
                    why, xm = "premium stop", m
                    break
                if lock and ch is not None:
                    if pos["locked"] and ch["low"][m] <= pos["px"] * (1 + lock[1]):
                        why, xm = "profit lock", m
                        break
                    if ch["high"][m] >= pos["px"] * (1 + lock[0]):
                        pos["locked"] = True
                if pos["target"] is not None and ((sg > 0 and I["high"][m] >= pos["target"]) or
                                                  (sg < 0 and I["low"][m] <= pos["target"])):
                    why, xm = "next liquidity", m
                    break
                if be:
                    if pos["mfe"] >= be and sg * (I["close"][m] - pos["ix"]) <= 0:
                        why, xm = "breakeven", m
                        break
                    pos["mfe"] = max(pos["mfe"], sg * ((I["high"][m] if sg > 0 else I["low"][m]) - pos["ix"]))
            if why is None and sg * (C[i] - pos["level"]) < 0:
                why, xm = "failed break", E[i] - 1
            if why is None and any(z.side == sg for z in known.get(i, [])):
                why, xm = "new liquidity", E[i] - 1
            if why is None and (i + 1 >= len(b) or DI[i + 1] != DI[i]):
                why, xm = "15:10", min(E[i] - 1, 374)
            if why:
                ix = I["close"][xm] if why != "next liquidity" else pos["target"]
                opt = ch["close"][xm] if ch is not None else np.nan
                if why == "premium stop":
                    opt = min(opt, pos["px"] * (1 - prem))
                if why == "profit lock":
                    opt = min(opt, pos["px"] * (1 + lock[1]))
                trades.append(dict(day=pos["day"], sign=sg, why=why, pts=sg * (ix - pos["ix"]),
                                   rs=(opt - SLIP - pos["px"]) * LOT - CHG, held=xm - pos["m"], src=d.get("src", "index")))
                pos = None
        if pos is not None or i + 1 >= len(b) or DI[i + 1] != DI[i]:
            continue
        m = S[i + 1]
        if not (w0 <= m <= w1):
            continue
        brk = breaks.get(i, [])
        if not brk:
            continue
        cand = [p for p in brk if p.kind == "pool" and any(
            s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i and (s.broken < 0 or s.broken >= i)
            for s in swings)]
        if not cand:
            continue
        z = cand[0]
        sg = z.side
        # ---- filters (all known at the signal bar's close)
        if cfg["maxday"] and nday >= cfg["maxday"]:
            continue
        if cfg["noexp"] and d["day"] in expset:
            continue
        tr = cfg["trend"]
        if tr == "ema" and sg * f["slope"][i] <= 0:
            continue
        if tr == "open" and sg * (C[i] - f["open"][i]) <= 0:
            continue
        if tr == "twap" and sg * (C[i] - f["twap"][i]) <= 0:
            continue
        if cfg["vol"] and np.isfinite(f["med"][i]) and f["rng"][i] < cfg["vol"] * f["med"][i]:
            continue
        ix = I["open"][m]
        ahead = [q.edge for q in zones if q.side == sg and q.known <= i and (q.broken < 0 or q.broken > i)
                 and sg * (q.edge - ix) > 0]
        target = (min(ahead) if sg > 0 else max(ahead)) if ahead else None
        right = "CE" if sg > 0 else "PE"
        key, px = None, np.nan
        if d["chain"]:
            ks = np.array(sorted({k for k, r in d["chain"] if r == right}))
            if not len(ks):
                continue
            k = ks[np.argmin(np.abs(ks - ix))] - sg * STEP * cfg["itm"]
            if (k, right) not in d["chain"]:
                continue
            key, px = (k, right), d["chain"][(k, right)]["open"][m] + SLIP
        pos = dict(day=d["day"], di=DI[i], sign=sg, m=m, ix=ix, level=z.edge, target=target, key=key, px=px,
                   locked=False, mfe=0.0)
        nday += 1
    return pd.DataFrame(trades, columns=["day", "sign", "why", "pts", "rs", "held", "src"])


# ---------------------------------------------------------------- running a config on a dataset
class Data:
    def __init__(self, days, expset):
        self.days, self.expset, self.cache = days, expset, {}

    def chart(self, tf, L, conf):
        key = (tf, L, conf)
        if key not in self.cache:
            b = bars(self.days, tf)
            self.cache[key] = (b, features(self.days, b), swing_zones(b, L, "full") + pool_zones(b, 2, 5, conf))
        return self.cache[key]

    def run(self, cfg):
        parts = []
        for tf in cfg["books"]:
            b, f, z = self.chart(tf, cfg["L"], cfg["conf"])
            t = simulate(self.days, b, z, f, cfg, self.expset)
            parts.append(t.assign(book=tf))
        return pd.concat(parts, ignore_index=True).sort_values("day", kind="stable").reset_index(drop=True)


def stats(tr, ndays, cal=None, col=None):
    """col None: index view (pts, and estimated Rs = a + b*pts when cal given); col 'rs': real option P&L."""
    if tr.empty:
        return dict(n=0, perday=0, win=np.nan, pts=np.nan, tpts=np.nan, rs=0, pt=np.nan, t=np.nan, green="0/0", dd=0)
    x = tr.pts.values
    tp = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else np.nan
    r = tr[col].values if col else (cal[0] + cal[1] * x if cal else x * LOT)
    t = r.mean() / (r.std(ddof=1) / np.sqrt(len(r))) if len(r) > 2 else np.nan
    mo = pd.Series(r).groupby([str(d)[:7] for d in tr.day]).sum()
    eq = np.cumsum(r)
    dd = float((np.maximum.accumulate(np.r_[0, eq]) - np.r_[0, eq]).max())
    return dict(n=len(tr), perday=len(tr) / ndays, win=100 * ((r if col else x) > 0).mean(), pts=x.mean(), tpts=tp,
                rs=r.sum(), pt=r.mean(), t=t, green=f"{(mo > 0).sum()}/{len(mo)}", dd=dd)


def fmt(label, s, rs_label="est. Rs"):
    if not s["n"]:
        return f"| {label} | 0 | | | | | | | | |"
    return (f"| {label} | {s['n']} ({s['perday']:.1f}/day) | {s['win']:.0f}% | {s['pts']:+.1f} | {s['tpts']:.2f} | "
            f"{s['pt']:+,.0f} | {s['t']:.2f} | {s['rs']:+,.0f} | {s['green']} | {s['dd']:,.0f} |")


HDR = ["| setting | trades | win | index pts/trade | t (pts) | {r} per trade | t ({r}) | net {r} (1 lot) | green months | max DD |",
       "|---|---|---|---|---|---|---|---|---|---|"]


def hdr(r):
    return [HDR[0].format(r=r), HDR[1]]


def desc(cfg):
    out = []
    for k, v in cfg.items():
        if v != BASE[k]:
            out.append(f"{k}={v}")
    return ", ".join(out) or "baseline"


# ---------------------------------------------------------------- main
def main():
    scr = sys.argv[1] if len(sys.argv) > 1 else "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(scr, "finplus_out.md")
    ipath = os.path.join(scr, "finnifty_index.parquet")
    pk = os.path.join(scr, "finplus_upload_days.pkl")
    if os.path.exists(pk):
        up_days, rate = pickle.load(open(pk, "rb"))
    else:
        up_days, _, rate = load_days(ipath, os.path.join(scr, "fin", "finnifty_data"))
        for d in up_days:
            if len(d["chain"]) < 40:
                d["chain"] = {}
        pickle.dump((up_days, rate), open(pk, "wb"))
    ix_days = load_any(ipath)
    exps = expiry_days([d["day"] for d in ix_days] + [d["day"] for d in up_days])
    A = Data([d for d in ix_days if d["day"] < SPLIT], exps)
    B = Data([d for d in ix_days if d["day"] >= SPLIT], exps)
    U = Data(up_days, exps)
    nA, nB = len(A.days), len(B.days)
    real_days = [d["day"] for d in up_days if d["chain"] and d["src"] == "index"]
    syn_days = [d["day"] for d in up_days if d["chain"] and d["src"] != "index"]
    L = []
    say = lambda *s: (L.extend(s), print("\n".join(s), flush=True))  # noqa: E731

    def real(cfg):
        t = U.run(cfg)
        t = t[np.isfinite(t.rs)]
        return t[t.day.isin(real_days)], t[t.day.isin(syn_days)]

    # ---- baseline + calibration
    base_r, base_s = real(BASE)
    cal = np.polyfit(base_r.pts.values, base_r.rs.values, 1)[::-1]          # (a, b): rs = a + b*pts
    cal = (float(cal[0]), float(cal[1]))
    say(f"Calibration on the baseline's {len(base_r)} real-option trades (real-index days): Rs per lot = "
        f"{cal[0]:+.0f} + {cal[1]:.1f} x index pts (b/lot = {cal[1] / LOT:.2f}, an effective delta).",
        f"Expiry days (approx. calendar) in the index data: {sum(d['day'] in exps for d in ix_days)}.")
    variants = []
    add = lambda **kw: variants.append({**BASE, **kw})  # noqa: E731
    add()
    for bk in [(5,), (15,), (3, 15), (5, 30), (10, 15)]:
        add(books=bk)
    for v in (10, 15, 30):
        add(L=v)
    for v in (5, 15):
        add(conf=v)
    for v in [(5, 255), (20, 315), (45, 315)]:
        add(win=v)
    for v in ("ema", "open", "twap"):
        add(trend=v)
    for v in (0.3, 0.5):
        add(vol=v)
    for v in (1, 2):
        add(maxday=v)
    add(noexp=True)
    for v in (20, 40):
        add(be=v)
    n_single = len(variants)
    res = {}
    for k, cfg in enumerate(variants):
        res[k] = (stats(A.run(cfg), nA, cal), stats(B.run(cfg), nB, cal))
        print(k, desc(cfg), f"A {res[k][0]['pts']:+.1f} {res[k][0]['rs']:+,.0f}  B {res[k][1]['pts']:+.1f} {res[k][1]['rs']:+,.0f}",
              flush=True)
    base_ab = res[0]
    say("", f"### Stage 1: index, {len(variants)} single changes (Year A {nA} days, Year B {nB} days)", "",
        "Estimated Rs = calibration above applied per trade (an ESTIMATE, not option prices).", "",
        "| setting | A trades | A pts/trade (t) | A est. Rs | B trades | B pts/trade (t) | B est. Rs |", "|---|---|---|---|---|---|---|")
    for k, cfg in enumerate(variants):
        a, bb = res[k]
        say(f"| {desc(cfg)} | {a['n']} | {a['pts']:+.1f} ({a['tpts']:.2f}) | {a['rs']:+,.0f} | {bb['n']} | "
            f"{bb['pts']:+.1f} ({bb['tpts']:.2f}) | {bb['rs']:+,.0f} |")

    # ---- greedy combos, built on the training year only
    chosen = {}
    for tr_i, name in ((0, "A"), (1, "B")):
        better = sorted([k for k in res if k and res[k][tr_i]["rs"] > base_ab[tr_i]["rs"]],
                        key=lambda k: -res[k][tr_i]["rs"])
        cands = {k: (variants[k], res[k]) for k in res}
        # combine the top single changes that touch different knobs
        picks, knobs = [], set()
        for k in better:
            kn = [x for x in variants[k] if variants[k][x] != BASE[x]][0]
            if kn not in knobs:
                picks.append(k)
                knobs.add(kn)
            if len(picks) == 3:
                break
        for n in (2, 3):
            if len(picks) >= n:
                cfg = dict(BASE)
                for k in picks[:n]:
                    cfg.update({x: v for x, v in variants[k].items() if v != BASE[x]})
                kk = len(variants)
                variants.append(cfg)
                res[kk] = (stats(A.run(cfg), nA, cal), stats(B.run(cfg), nB, cal))
                cands[kk] = (cfg, res[kk])
        best = max((k for k in cands if k), key=lambda k: cands[k][1][tr_i]["rs"])
        chosen[name] = best
    say("", f"Combos built on each training year: " + "; ".join(
        f"[{desc(variants[k])}] A {res[k][0]['rs']:+,.0f} / B {res[k][1]['rs']:+,.0f}" for k in res if k >= n_single),
        f"Total index variants run: {len(variants)} (incl. baseline).")

    say("", "### Out of sample", "")
    for name, other, tr_i, te_i, nte in (("A", "B", 0, 1, nB), ("B", "A", 1, 0, nA)):
        k = chosen[name]
        say(f"**Chosen on Year {name}: {desc(variants[k])}** -> held-out Year {other}:", "", *hdr("est. Rs"),
            fmt(f"baseline, Year {name}", base_ab[tr_i]), fmt(f"chosen, Year {name} (in-sample)", res[k][tr_i]),
            fmt(f"baseline, Year {other}", base_ab[te_i]), fmt(f"**chosen, Year {other} (held out)**", res[k][te_i]), "")
        ok = res[k][0]["rs"] > 0 and res[k][1]["rs"] > 0 and res[k][te_i]["rs"] > base_ab[te_i]["rs"]
        say(f"Passes (positive both years, beats baseline held out): {'YES' if ok else 'NO'}", "")

    # ---- cost sensitivity: a harsher intercept (slippage + charges + decay) than the fitted one
    harsh = (-150.0, cal[1])
    say("Cost sensitivity (est. Rs with the intercept forced to Rs -150 a trade, same slope):", "",
        "| setting | Year A est. Rs | Year B est. Rs |", "|---|---|---|")
    for label, cfg in [("baseline", BASE)] + [(f"chosen on {n}: {desc(variants[chosen[n]])}", variants[chosen[n]])
                                              for n in ("A", "B")] + [("books=(5, 30) alone", {**BASE, "books": (5, 30)})]:
        say(f"| {label} | {stats(A.run(cfg), nA, harsh)['rs']:+,.0f} | {stats(B.run(cfg), nB, harsh)['rs']:+,.0f} |")
    say("")

    # ---- stage 2: real options
    say("### Stage 2: real option prices (upload days with the real index)", "",
        f"{len(real_days)} real-index chain days ({real_days[0]} .. {real_days[-1]}); March 2026 ({len(syn_days)} days, "
        "synthetic index) shown apart.", "", *hdr("Rs"))
    opt_vars = [("baseline (15% stop)", BASE)]
    for name in ("A", "B"):
        opt_vars.append((f"chosen on Year {name}: {desc(variants[chosen[name]])}", variants[chosen[name]]))
    opt_vars.append(("books=(5, 30) alone (index single change, not the protocol pick)", {**BASE, "books": (5, 30)}))
    for p in (None, 0.10, 0.20, 0.25):
        opt_vars.append((f"premium stop {p}", {**BASE, "prem": p}))
    opt_vars.append(("1 strike ITM", {**BASE, "itm": 1}))
    opt_vars.append(("profit lock: after +30% lock +10%", {**BASE, "lock": (0.30, 0.10)}))
    opt_vars.append(("profit lock: after +50% lock breakeven", {**BASE, "lock": (0.50, 0.0)}))
    syn_rows = []
    for label, cfg in opt_vars:
        r, s = real(cfg)
        sa, sb = r[r.day < SPLIT], r[r.day >= SPLIT]
        say(fmt(label, stats(r, len(real_days), col="rs")) +
            f" A {sa.rs.sum():+,.0f} ({len(sa)}) / B {sb.rs.sum():+,.0f} ({len(sb)})")
        syn_rows.append(fmt(label, stats(s, len(syn_days), col="rs")))
    say("", "March 2026 only (synthetic index; unreliable):", "", *hdr("Rs"), *syn_rows)
    say("", f"Option-only variants: {len(opt_vars) - 4}. Exits (baseline, real days): " + ", ".join(
        f"{k} {100 * v:.0f}%" for k, v in base_r.why.value_counts(normalize=True).items()))
    open(out, "w").write("\n".join(L))


if __name__ == "__main__":
    main()
