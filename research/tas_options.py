"""TAS (research/gold_tas.py, tracker ATR 19) on NIFTY, BANKNIFTY, FINNIFTY and SENSEX, trading options - the owner's
ask, 2026-10-02: signals on the index, buy the ATM option (CE when the tracker turns up, PE when it turns down), sell
it on the index only; every chart; with and without a profit lock; rupees.

    python research/tas_options.py <out.md> NIFTY:75:nifty_prev.parquet,nifty_year.parquet \
        BANKNIFTY:35:banknifty_prev_year_wide.parquet,banknifty_year.parquet FINNIFTY:65:finnifty_index.parquet \
        SENSEX:20:sensex_index.parquet

  chart    5, 15, 30, 60 and 240-minute candles from 09:15 (the 240 candle: 09:15-13:15 and 13:15-15:30), indicators
           continuous across days like a TradingView chart
  entry    the TAS buy rule on a completed candle (tracker turned within 10 candles, score >= +50%; for a PE the
           mirror); BUY the ATM option of the nearest expiry at the next candle's open (+0.5 slippage); entries up to
           14:30; one trade per turn
  exits    all on the INDEX, checked each minute: the stop (the tracker line at the signal), the targets 1.5 / 2.5 / 3.5 R
           (a third each; the stop then at the entry's index level) or none, the profit lock (once the index has moved
           START x ATR(14) of the chart our way, out on a move of GIVEBACK ATRs back from its best), the tracker turning
           (out at the next candle's open), 15:10 square-off (intraday, as IraAlgo trades)
  rupees   the option's real minute prices for NIFTY and BANKNIFTY (sold at the minute's close - 0.5; Rs 40 a lot round
           trip); FINNIFTY and SENSEX have no option history, so their rupees are ESTIMATED from how NIFTY / BANKNIFTY
           options moved for the same kind of trades (option points = a x index points - b x index level x minutes held)
  lots     NIFTY 75, BANKNIFTY 35, FINNIFTY 65, SENSEX 20; per lot (the thirds as fractions of a lot)
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import gold_tas as gt  # noqa: E402
import liquidity_break as lb  # noqa: E402
from ml_long import grid  # noqa: E402

SLIP, CHG, CUT, LAST_M = 0.5, 40.0, 355, 315
LOCKS = (None, (1.0, 1.5), (1.0, 2.0), (1.0, 3.0), (1.0, 4.0), (2.0, 3.0))
TFS = (5, 15, 30, 60, 240)


def load(path):
    df = pd.read_parquet(path, columns=["right"])
    if (df.right.astype(str) != "IX").any():
        from sell_levels import load as ld
        return ld(path)
    df = pd.read_parquet(path)
    df = df[df.right.astype(str) == "IX"]
    df["ts"] = pd.to_datetime(df.ts)
    days = []
    for day, g in df.groupby(df.ts.dt.date):
        g = g.sort_values("ts").set_index("ts")
        if len(g) >= 300:
            days.append(dict(day=day, exp=None, I=grid(g, ["open", "high", "low", "close"]), chain={}))
    return days


def run(days, b, trk, d, pct, atr, side, tps, lock):
    """Trades of one side: dicts with the day, index points, option points (NaN without options), minutes held, level."""
    DI, S, E = b.di.values, b.s.values, b.e.values
    n, out, i, flip = len(b), [], 1, None
    right = "CE" if side > 0 else "PE"
    while i < n - 1:
        if d[i] == side and d[i - 1] != side:
            flip = i
        if d[i] != side:
            flip = None
        ok = flip is not None and i - flip <= gt.LATE and np.isfinite(pct[i]) and side * pct[i] >= gt.MIN_SCORE
        if not ok or DI[i + 1] != DI[i] or S[i + 1] > LAST_M:
            i += 1; continue
        day = days[DI[i]]; I = day["I"]; m0 = S[i + 1]; ix0 = I["open"][m0]
        stop = trk[i]; r = side * (ix0 - stop); a = atr[i]
        if r <= 0:
            i += 1; continue
        opt = None
        if day["chain"]:
            ks = np.array(sorted({k for k, rr in day["chain"] if rr == right}))
            if not len(ks):
                i += 1; continue
            opt = day["chain"][(ks[np.argmin(np.abs(ks - ix0))], right)]
            px = opt["open"][m0] + SLIP
            if not np.isfinite(px):
                i += 1; continue
        left, ixp, opp = 1.0, 0.0, 0.0
        hit = [False] * len(tps)
        peak = -np.inf if side > 0 else np.inf
        be = False
        j, xm = i + 1, None

        def close_part(q, m, ixv, at_open=False):
            nonlocal left, ixp, opp
            ixp += q * side * (ixv - ix0)
            if opt is not None:
                v = (opt["open"][m] if at_open else opt["close"][m]) - SLIP
                opp += q * (v - px)
            left -= q

        while j < n and DI[j] == DI[i] and left > 1e-9:
            for m in range(max(S[j], m0), E[j]):
                if m >= CUT:
                    close_part(left, m, I["close"][m]); xm = m; break
                lo, hi = I["low"][m], I["high"][m]
                if (side > 0 and lo <= stop) or (side < 0 and hi >= stop):
                    close_part(left, m, stop if (side > 0 and I["open"][m] > stop) or (side < 0 and I["open"][m] < stop) else I["open"][m])
                    xm = m; break
                moved = False
                for k, (rm, q) in enumerate(tps):
                    tgt = ix0 + side * rm * r
                    if not hit[k] and ((side > 0 and hi >= tgt) or (side < 0 and lo <= tgt)):
                        close_part(left if k == len(tps) - 1 else min(q, left), m, tgt); hit[k] = True; moved = True
                if left <= 1e-9:
                    xm = m; break
                if moved and not be:
                    stop, be = ix0, True
                peak = max(peak, hi) if side > 0 else min(peak, lo)
                if lock is not None and np.isfinite(a) and side * (peak - ix0) >= lock[0] * a:
                    ls = peak - side * lock[1] * a
                    stop = max(stop, ls) if side > 0 else min(stop, ls)
            if xm is not None or left <= 1e-9:
                break
            if d[j] != side:                                   # the tracker turned: out at the next candle's open
                m = E[j]
                if j + 1 < n and DI[j + 1] == DI[j] and m < CUT:
                    close_part(left, m, I["open"][m], at_open=True)
                else:
                    close_part(left, min(E[j] - 1, CUT), I["close"][min(E[j] - 1, CUT)])
                xm = m; break
            j += 1
        if left > 1e-9:                                        # the day ran out
            m = min(E[j - 1] - 1, CUT); close_part(left, m, I["close"][m]); xm = m
        out.append(dict(day=day["day"], side=side, pts=ixp, opt=opp if opt is not None else np.nan,
                        held=(xm or m0) - m0, level=ix0))
        flip = None
        i = j + 1
        while i < n and d[i] == side and d[i - 1] == side:
            i += 1
    return out


def main():
    out = sys.argv[1]
    specs = [s.split(":") for s in sys.argv[2:]]
    results, fit = {}, []
    for name, lot, files in specs:
        lot = int(lot)
        parts = [load(f) for f in files.split(",")]
        split = parts[1][0]["day"] if len(parts) > 1 else pd.Timestamp("2025-02-15").date()
        days = sorted(sum(parts, []), key=lambda x: x["day"])
        del parts
        has_opt = any(dd["chain"] for dd in days)
        for tf in TFS:
            b = lb.bars(days, tf)
            trk, d, pct = gt.indicators(b, 19)
            h, l, c = b.high, b.low, b.close
            tr = pd.concat([h - l, (h - c.shift()).abs(), (l - c.shift()).abs()], axis=1).max(axis=1)
            atr = gt.rma(tr, 14).values
            for tname, tps in (("1.5/2.5/3.5 R", gt.TPS), ("none", ())):
                for lock in LOCKS:
                    t = pd.DataFrame(run(days, b, trk, d, pct, atr, 1, tps, lock) + run(days, b, trk, d, pct, atr, -1, tps, lock))
                    results[(name, tf, tname, lock)] = (t, lot, split, has_opt)
                    if has_opt and len(t):
                        fit.append(t[["pts", "held", "level", "opt"]])
            print(name, tf, "done", flush=True)
        del days
    f = pd.concat(fit).dropna()
    A = np.c_[f.pts.values, -(f.held * f.level).values]
    coef, *_ = np.linalg.lstsq(A, f.opt.values, rcond=None)
    print("fit: option pts = %.3f x index pts - %.3g x level x minutes" % tuple(coef), flush=True)
    L = ["## TAS on index options (research/tas_options.py)", "",
         "Signals and exits on the index, the ATM option bought (CE on an up-turn, PE on a down-turn), intraday (15:10), "
         "per lot after Rs 40 a round trip and 0.5 slippage a side. NIFTY / BANKNIFTY: real option prices. FINNIFTY / "
         f"SENSEX: estimated (option points = {coef[0]:.3f} x index points - {coef[1]:.3g} x index level x minutes held, "
         "fitted on the NIFTY / BANKNIFTY trades).", "",
         "| index | chart | targets | lock (start / giveback ATR) | trades | win | Rs 1st year | Rs 2nd year | Rs total | Rs CE | Rs PE | t | deepest drawdown Rs | Rs a month |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for (name, tf, tname, lock), (t, lot, split, has_opt) in results.items():
        if t.empty:
            continue
        o = t.opt if has_opt else coef[0] * t.pts - coef[1] * t.level * t.held - 2 * SLIP
        rs = (o.fillna(0) * lot - CHG).values
        ts = pd.to_datetime(t.day)
        order = np.argsort(ts.values, kind="stable"); rs_o = rs[order]
        eq = np.cumsum(rs_o); dd = (eq - np.maximum.accumulate(eq)).min()
        y1 = rs[(t.day < split).values].sum(); y2 = rs[(t.day >= split).values].sum()
        months = max(1, (ts.max() - ts.min()).days / 30.4)
        tt = rs.mean() / (rs.std(ddof=1) / len(rs) ** 0.5) if len(rs) > 1 else 0
        lk = "none" if lock is None else f"{lock[0]:g} / {lock[1]:g}"
        lab = f"{tf}m" if tf < 60 else f"{tf // 60}h"
        L.append(f"| {name}{'' if has_opt else ' (est.)'} | {lab} | {tname} | {lk} | {len(rs)} | {100 * (rs > 0).mean():.0f}% | "
                 f"{y1:+,.0f} | {y2:+,.0f} | {rs.sum():+,.0f} | {rs[(t.side > 0).values].sum():+,.0f} | {rs[(t.side < 0).values].sum():+,.0f} | "
                 f"{tt:.2f} | {dd:,.0f} | {rs.sum() / months:+,.0f} |")
    open(out, "w").write("\n".join(L))
    print("\n".join(L))


if __name__ == "__main__":
    main()
