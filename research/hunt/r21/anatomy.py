"""R21 step 1: anatomy of zero-to-hero moves on expiry days. DESCRIPTIVE, DESIGN PERIOD ONLY (the holdout is never read).

    python3 research/hunt/r21/anatomy.py          # -> results/anatomy.json, results/anatomy.md

"Cheap" = an option of today's expiry whose traded close is Rs 1-10 at some bar starting 13:00-14:30. Its multiple is
the best later high (bars after that minute, up to the bar before 15:05, or to the close) over that close. Hindsight is
the point here (how big could it get); no rule is tested in this file except the k-scan at the end, which is the
step-1 input to the pre-registration (design days only).
"""
from __future__ import annotations

import json
import math
from datetime import date

import numpy as np

import config as K
import data as D
import rules as R
from data import C, H, OI, V, minute

W0, W1 = minute(13, 0), minute(14, 30)
SQ = R.EXIT_ROW            # 15:05 (the multiple counts highs of bars before it)
CLOSE = 375
MULTS = (5, 10, 20)


def design_days() -> list:
    ds = D.cached()
    out = []
    for u, days in ds.items():
        cut = D.holdout_cut(u, days)
        out += [d for d in days if d.day < cut]
    return out


def best_cheap(day, until: int) -> dict:
    """The cheap option with the best multiple (any strike, both sides). Hindsight."""
    best = dict(mult=0.0)
    for (k, s), a in day.opts.items():
        c = a[:, C]
        v = np.nan_to_num(a[:, V])
        hi = a[:, H]
        # running max of highs from the end: best later high for each start row
        later = np.full(len(c), np.nan)
        run = np.nan
        for r in range(until - 1, W0 - 1, -1):
            later[r] = run
            if not math.isnan(hi[r]):
                run = hi[r] if math.isnan(run) else max(run, hi[r])
        for r in range(W0, W1 + 1):
            if v[r] <= 0 or math.isnan(c[r]) or not (1.0 <= c[r] <= 10.0) or math.isnan(later[r]):
                continue
            m = later[r] / c[r]
            if m > best["mult"]:
                best = dict(mult=float(m), strike=k, side=s, row=r, px=float(c[r]))
    return best


def max_pain(day, t: int) -> float:
    ks = sorted({k for k, _ in day.opts})
    oi = {}
    for (k, s), a in day.opts.items():
        x = a[: t + 1, OI]
        ok = np.nonzero(~np.isnan(x) & (x > 0))[0]
        oi[(k, s)] = float(x[ok[-1]]) if len(ok) else 0.0
    best, bk = math.inf, math.nan
    for x in ks:
        pain = sum(oi.get((k, "C"), 0) * max(x - k, 0) + oi.get((k, "P"), 0) * max(k - x, 0) for k in ks)
        if pain < best:
            best, bk = pain, x
    return bk


def oi_walls(day, t: int, s: float) -> tuple[float, float]:
    """Largest call OI strike above spot and largest put OI strike below spot (last OI at or before t)."""
    best = {"C": (0, math.nan), "P": (0, math.nan)}
    for (k, sd), a in day.opts.items():
        if (sd == "C" and k <= s) or (sd == "P" and k >= s):
            continue
        x = a[: t + 1, OI]
        ok = np.nonzero(~np.isnan(x) & (x > 0))[0]
        o = float(x[ok[-1]]) if len(ok) else 0.0
        if o > best[sd][0]:
            best[sd] = (o, k)
    return best["C"][1], best["P"][1]


def first_open(day) -> float:
    ok = np.nonzero(~np.isnan(day.ix[:, 0]))[0]
    return float(day.ix[ok[0], 0]) if len(ok) else math.nan


def atm_leg_change(day, t: int, t0: int) -> tuple[float, float]:
    """% change of the ATM call and put (ATM fixed at t0) from t0 to t."""
    k = R.atm(day, R.spot(day, t0))
    out = []
    for s in ("C", "P"):
        a = day.opts.get((k, s))
        if a is None:
            out.append(math.nan)
            continue
        p0, _ = R.ltp(a, t0)
        p1, _ = R.ltp(a, t)
        out.append(p1 / p0 - 1 if p0 and not math.isnan(p0) and not math.isnan(p1) else math.nan)
    return out[0], out[1]


def cheap_volume_surge(day, t: int) -> float:
    """Volume of options priced Rs 1-10 at t in the 15 bars to t, over the mean 15-bar volume 11:00 .. t-15."""
    a0 = minute(11, 0)
    rec, base = 0.0, 0.0
    for (k, s), a in day.opts.items():
        p, _ = R.ltp(a, t)
        if math.isnan(p) or not (1 <= p <= 10):
            continue
        v = np.nan_to_num(a[:, V])
        rec += v[t - 14: t + 1].sum()
        base += v[a0: t - 14].sum() / max((t - 14 - a0) / 15, 1)
    return rec / base if base > 0 else math.nan


def features(day, t: int) -> dict:
    s = R.spot(day, t)
    op = first_open(day)
    noon = R.spot(day, minute(11, 59))
    cw, pw = oi_walls(day, t, s)
    mp = max_pain(day, t)
    ce, pe = atm_leg_change(day, t, minute(11, 59))
    vix = day.vix[t] if day.vix is not None else math.nan
    return dict(
        ret_open=s / op - 1, ret_noon=s / noon - 1, gap=op / day.prev_close - 1 if day.prev_close else math.nan,
        maxpain_dist=(s - mp) / s, call_wall_dist=(cw - s) / s, put_wall_dist=(s - pw) / s,
        vix=float(vix), vix_chg=float(vix / day.vix_prev - 1) if day.vix_prev and not math.isnan(vix) else math.nan,
        atm_ce_chg=ce, atm_pe_chg=pe, one_sided=(ce - pe) if not (math.isnan(ce) or math.isnan(pe)) else math.nan,
        cheap_vol_surge=cheap_volume_surge(day, t),
        range_so_far=(np.nanmax(day.ix[: t + 1, 1]) - np.nanmin(day.ix[: t + 1, 2])) / s,
    )


def spike_shape(day, b: dict) -> dict:
    a = day.opts[(b["strike"], b["side"])]
    e = b["px"]
    hi = a[:, H]
    seg = hi[b["row"] + 1: SQ]
    pk = int(np.nanargmax(seg)) + b["row"] + 1
    peak = float(hi[pk])
    c = a[:, C]
    above = [r for r in range(b["row"] + 1, CLOSE) if not math.isnan(c[r]) and c[r] >= 0.5 * peak]
    p1505 = R.ltp(a, SQ - 1)[0]
    pclose = R.ltp(a, CLOSE - 1)[0]
    first5 = next((r for r in range(b["row"] + 1, SQ) if not math.isnan(hi[r]) and hi[r] >= 5 * e), None)
    return dict(start=D_hm(b["row"]), peak_at=D_hm(pk), peak_mult=peak / e, mins_to_peak=pk - b["row"],
                mins_above_half_peak=len(above), first_5x_at=D_hm(first5) if first5 else None,
                giveback_1505=(peak - p1505) / (peak - e) if peak > e else math.nan,
                mult_1505=p1505 / e, mult_close=pclose / e)


def D_hm(r: int) -> str:
    m = 9 * 60 + 15 + r
    return f"{m // 60:02d}:{m % 60:02d}"


def k_scan(days, ks=(1.5, 2.0, 2.5, 3.0, 4.0)) -> list[dict]:
    """Design only: the one-sided trigger (variant a, window 13:30-14:45, band 1-5 and 1-15, app exits) for each k."""
    out = []
    for band in ((1.0, 5.0), (1.0, 15.0)):
        for k in ks:
            p = R.Params(name=f"k{k}", trigger="onesided", k=k, band=band)
            nets, n, win = [], 0, 0
            for d in days:
                t = R.run_day(d, p)
                if t:
                    n += 1
                    win += t.net > 0
                    nets.append(t.net)
            out.append(dict(k=k, band=list(band), trades=n, wins=win, net=round(sum(nets), 0),
                            worst=round(min(nets), 0) if nets else 0))
    return out


def diagnose(day) -> dict:
    """Why the plain Hero fired or not: the straddle's best expansion in the window, minutes with |mom| >= 0.25%, and
    minutes where both held (the rule needs both in the same minute)."""
    p = R.Params()
    st, low = R.straddle_path(day, p.last)
    exp = [st[i] / low[i] - 1 if not (math.isnan(st[i]) or math.isnan(low[i]) or low[i] <= 0) else math.nan
           for i in range(len(st))]
    best, mom_n, both = -1.0, 0, 0
    for t in range(p.first, p.last + 1):
        e = exp[t - R.LOW_FROM]
        m = R.momentum(day, t, p.mom_k)
        if not math.isnan(e):
            best = max(best, e)
        mo = not math.isnan(m) and abs(m) >= p.mom - 1e-9
        mom_n += mo
        both += mo and not math.isnan(e) and e >= p.st_exp - 1e-9
    idx = R.spot(day, p.last) / R.spot(day, p.first) - 1
    return dict(st_best=best, mom_minutes=mom_n, both_minutes=both, idx_1330_1445=idx)


def main():
    days = design_days()
    rows = []
    for d in days:
        b05 = best_cheap(d, SQ)
        bcl = best_cheap(d, CLOSE)
        f13 = features(d, minute(13, 0))
        dg = diagnose(d)
        r = dict(diag={k: round(v, 4) if isinstance(v, float) else v for k, v in dg.items()}, und=d.und, day=str(d.day), best_1505=round(b05.get("mult", 0), 2), best_close=round(bcl.get("mult", 0), 2),
                 f1300={k: (None if (v is None or (isinstance(v, float) and math.isnan(v))) else round(v, 5)) for k, v in f13.items()})
        if b05.get("mult", 0) >= 5:
            r["best"] = dict(strike=b05["strike"], side=b05["side"], px=b05["px"])
            r["shape"] = {k: (round(v, 3) if isinstance(v, float) else v) for k, v in spike_shape(d, b05).items()}
            fs = features(d, b05["row"])
            r["f_start"] = {k: (None if math.isnan(v) else round(v, 5)) for k, v in fs.items()}
            r["side_vs_trend"] = int(np.sign(f13["ret_noon"]) == (1 if b05["side"] == "C" else -1))
        rows.append(r)
    ks = k_scan(days)
    json.dump(dict(days=rows, k_scan=ks), open(K.RESULTS / "anatomy.json", "w"), indent=1, default=str)
    write_md(rows, ks)


def _med(xs):
    xs = [x for x in xs if x is not None and not (isinstance(x, float) and math.isnan(x))]
    return (float(np.median(xs)), len(xs)) if xs else (math.nan, 0)


def write_md(rows, ks):
    n = len(rows)
    nn = sum(r["und"] == "NIFTY" for r in rows)
    L = [f"# R21 step 1: anatomy (design days only: {n} expiry days = {nn} NIFTY weeklies + {n - nn} BANKNIFTY monthlies)", ""]
    L.append("| reached (any Rs 1-10 option, 13:00-14:30 start) | before 15:05 | before the close |")
    L.append("|---|---|---|")
    for m in MULTS:
        L.append(f"| {m}x | {sum(r['best_1505'] >= m for r in rows)} / {n} | {sum(r['best_close'] >= m for r in rows)} / {n} |")
    big = [r for r in rows if r["best_1505"] >= 5]
    oth = [r for r in rows if r["best_1505"] < 5]
    L += ["", f"Big days (5x+ before 15:05): {len(big)}; other days: {len(oth)}.", ""]
    L.append("| big day | best | start | first 5x | peak at | peak x | min to peak | min >= half peak | x at 15:05 | given back by 15:05 | side vs trend since 12:00 |")
    L.append("|---|---|---|---|---|---|---|---|---|---|---|")
    for r in big:
        s = r["shape"]
        L.append(f"| {r['und']} {r['day']} | {r['best']['strike']:.0f}{r['best']['side']}E @ {r['best']['px']} | {s['start']} | "
                 f"{s['first_5x_at']} | {s['peak_at']} | {s['peak_mult']:.1f} | {s['mins_to_peak']} | {s['mins_above_half_peak']} | "
                 f"{s['mult_1505']:.1f} | {s['giveback_1505']:.0%} | {'with' if r['side_vs_trend'] else 'against'} |")
    L += ["", "Why the plain Hero fires or not (window 13:30-14:45): best straddle expansion over its low since 12:00, minutes "
          "with |15-minute index move| >= 0.25%, and minutes with both at once:", "",
          "| group | days | median best straddle exp | days best exp >= 15% | days with a 0.25% minute | days with both in one minute |",
          "|---|---|---|---|---|---|"]
    for name, g in (("big", big), ("other", oth), ("all", rows)):
        L.append(f"| {name} | {len(g)} | {_med([r['diag']['st_best'] for r in g])[0]:.1%} | "
                 f"{sum(r['diag']['st_best'] >= 0.15 for r in g)} | {sum(r['diag']['mom_minutes'] > 0 for r in g)} | "
                 f"{sum(r['diag']['both_minutes'] > 0 for r in g)} |")
    tr = [r for r in rows if abs(r["diag"]["idx_1330_1445"]) >= 0.004]
    L += ["", f"One-way afternoons (index moved >= 0.4% from 13:30 to 14:45; the 13 Oct shape): {len(tr)} of {n} design days.", "",
          "| day | index 13:30-14:45 | best straddle exp | 0.25% minutes | both | best cheap x by 15:05 |", "|---|---|---|---|---|---|"]
    for r in tr:
        dg = r["diag"]
        L.append(f"| {r['und']} {r['day']} | {dg['idx_1330_1445']:+.2%} | {dg['st_best']:.1%} | {dg['mom_minutes']} | {dg['both_minutes']} | {r['best_1505']:.1f} |")
    L += ["", "Medians at 13:00 (big days vs other days; n in brackets):", "", "| feature at 13:00 | big | other |", "|---|---|---|"]
    for f in rows[0]["f1300"]:
        a, na = _med([abs(r["f1300"][f]) if f in ("ret_open", "ret_noon", "gap", "maxpain_dist", "one_sided") and r["f1300"][f] is not None
                      else r["f1300"][f] for r in big])
        b, nb = _med([abs(r["f1300"][f]) if f in ("ret_open", "ret_noon", "gap", "maxpain_dist", "one_sided") and r["f1300"][f] is not None
                      else r["f1300"][f] for r in oth])
        L.append(f"| {f}{' (abs)' if f in ('ret_open', 'ret_noon', 'gap', 'maxpain_dist', 'one_sided') else ''} | {a:.4f} ({na}) | {b:.4f} ({nb}) |")
    if big:
        L += ["", "Medians at the move's start (big days only):", "", "| feature | median (n) |", "|---|---|"]
        for f in big[0]["f_start"]:
            v, k = _med([abs(r["f_start"][f]) if f in ("ret_open", "ret_noon", "gap", "maxpain_dist", "one_sided") and r["f_start"][f] is not None
                         else r["f_start"][f] for r in big])
            L.append(f"| {f} | {v:.4f} ({k}) |")
    L += ["", "k-scan of the one-sided trigger (design only; app exits; Rs 5k ticket; liq fills, app charges):", "",
          "| band | k | trades | wins | net Rs | worst Rs |", "|---|---|---|---|---|---|"]
    for x in ks:
        L.append(f"| {x['band'][0]:.0f}-{x['band'][1]:.0f} | {x['k']} | {x['trades']} | {x['wins']} | {x['net']:+,.0f} | {x['worst']:+,.0f} |")
    (K.RESULTS / "anatomy.md").write_text("\n".join(L) + "\n")
    print("\n".join(L))


if __name__ == "__main__":
    main()
