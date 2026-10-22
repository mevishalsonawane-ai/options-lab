"""R98 decision rows (PREREG sections 4-6): every 5 minutes 11:00-14:55, every cheap OTM option (Rs 1-10, traded in the
last 3 minutes) is one row, with point-in-time features, labels and the net rupees of every target x stop exit.

    python3 -I research/hunt/r98/rows.py      # writes $R98_OUT/rows.parquet

Entry: the next minute's open + 1 tick. Outcomes by minute closes to 15:20. Exits: a target fills at target x entry once
a close confirms it; a stop exits at the close that breaks it; otherwise out at the last close by 15:20.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402

DEC = list(range(R.minute(11, 0), R.minute(14, 55) + 1, 5))
T11 = R.minute(11, 0)
TARGETS = [1.1, 1.2, 1.3, 1.5, 2.0, 3.0, 5.0, 10.0, 20.0, np.inf]
STOPS = [0.2, 0.4, 0.5, 0.6, 0.8, 0.0]          # 0.0 = no stop
EXITS = [(tg, st) for tg in TARGETS for st in STOPS]
EXIT_COLS = [f"x_{tg:g}_{st:g}" for tg, st in EXITS]


def first_hit(mask: np.ndarray) -> int:
    return int(np.argmax(mask)) if mask.any() else -1


def exits_for(und: str, e: float, path: np.ndarray) -> np.ndarray:
    """Net Rs of every (target, stop) for entry e and the minute closes `path` (entry minute .. 15:20, NaN = no trade)."""
    ok = ~np.isnan(path)
    pc = path[ok]
    if pc.size == 0:
        last = 0.0
    else:
        last = pc[-1]
    th = {tg: first_hit(pc >= tg * e) for tg in TARGETS if np.isfinite(tg)}
    sh = {st: first_hit(pc <= st * e) for st in STOPS if st > 0}
    out = np.empty(len(EXITS), dtype=np.float32)
    for j, (tg, st) in enumerate(EXITS):
        it = th.get(tg, -1) if np.isfinite(tg) else -1
        isl = sh.get(st, -1) if st > 0 else -1
        if it >= 0 and (isl < 0 or it <= isl):
            px = tg * e
        elif isl >= 0:
            px = pc[isl]
        else:
            px = last
        out[j] = L.net_pnl(und, e, px)
    return out


def day_rows(day: R.Day, monthly: bool) -> list[dict]:
    sp = L.spot(day)
    em, _ = L.straddle(day)
    hi, lo = day.ix[:, 1], day.ix[:, 2]
    rh = np.fmax.accumulate(np.where(np.isnan(hi), -np.inf, hi))
    rl = np.fmin.accumulate(np.where(np.isnan(lo), np.inf, lo))
    orh, orl = np.nanmax(hi[:15]), np.nanmin(lo[:15])                    # opening range 09:15-09:29
    o0 = day.ix[0, 0] if not np.isnan(day.ix[0, 0]) else sp[0]
    vix = L.ffill(day.vix, 10) if day.vix is not None else np.full(R.N, np.nan)
    step = R.STEP[day.und]
    em_low = np.full(R.N, np.nan)
    run = np.nan
    for t in range(T11, R.N):
        if not np.isnan(em[t]):
            run = em[t] if np.isnan(run) else min(run, em[t])
        em_low[t] = run
    wd = day.day.weekday()
    rows = []
    for (k, s), a in day.opts.items():
        c = a[:, R.C]
        cf = L.ffill(c, 3)
        oi = L.ffill(a[:, R.OI], 30)
        iv = L.ffill(a[:, R.IV], 5)
        vol = np.nan_to_num(a[:, R.V])
        sign = 1.0 if s == "C" else -1.0
        for t in DEC:
            p = c[t]
            if np.isnan(p) or p < L.LO or p > L.HI or np.isnan(sp[t]) or not L.otm(k, s, sp[t]) or not L.traded(a, t):
                continue
            te = t + 1
            if np.isnan(a[te, R.O]):
                continue
            e = a[te, R.O] + L.TICK
            path = c[te:L.T_END + 1]
            if np.all(np.isnan(path)):
                continue
            best = np.nanmax(path)
            rng = rh[t] - rl[t]
            r = dict(
                und=day.und, day=day.day, t=t, strike=k, side=s, entry=e, best_mult=best / e,
                end_px=path[~np.isnan(path)][-1],
                y2=best >= 2 * e, y5=best >= 5 * e, y10=best >= 10 * e,
                # ---- features (data up to minute t)
                f_call=float(s == "C"), f_left=float(L.T_END - t), f_px=p, f_logpx=np.log(p),
                f_strikes=abs(k - sp[t]) / step, f_dist_pct=abs(k - sp[t]) / sp[t] * 100,
                f_dist_em=abs(k - sp[t]) / em[t] if em[t] > 0 else np.nan,
                f_dist_rng=abs(k - sp[t]) / rng if rng > 0 else np.nan,
                f_em_pct=em[t] / sp[t] * 100 if em[t] > 0 else np.nan,
                f_vix=vix[t], f_vix_chg=vix[t] - day.vix_prev, f_vix_chg30=vix[t] - vix[t - 30],
                f_r5=sign * (sp[t] / sp[t - 5] - 1) * 100, f_r15=sign * (sp[t] / sp[t - 15] - 1) * 100,
                f_r30=sign * (sp[t] / sp[t - 30] - 1) * 100, f_r60=sign * (sp[t] / sp[t - 60] - 1) * 100,
                f_rday=sign * (sp[t] / o0 - 1) * 100,
                f_gap=sign * (o0 / day.prev_close - 1) * 100 if day.prev_close == day.prev_close else np.nan,
                f_pos=((sp[t] - rl[t]) / rng if sign > 0 else (rh[t] - sp[t]) / rng) if rng > 0 else np.nan,
                f_brk15=float((rh[t] > rh[t - 15]) if sign > 0 else (rl[t] < rl[t - 15])),
                f_orb=float((sp[t] > orh) if sign > 0 else (sp[t] < orl)),
                f_stx=em[t] / em_low[t] - 1 if em_low[t] and em_low[t] == em_low[t] else np.nan,
                f_st15=em[t] / em[t - 15] - 1 if em[t - 15] and em[t - 15] == em[t - 15] else np.nan,
                f_or15=cf[t] / cf[t - 15] - 1 if cf[t - 15] and cf[t - 15] == cf[t - 15] else np.nan,
                f_volb=vol[t - 4:t + 1].sum() / (vol[T11:t + 1].mean() * 5 + 1e-9) if t > T11 else np.nan,
                f_oi11=oi[t] / oi[T11] - 1 if oi[T11] and oi[T11] == oi[T11] else np.nan,
                f_oi15=oi[t] / oi[t - 15] - 1 if oi[t - 15] and oi[t - 15] == oi[t - 15] else np.nan,
                f_iv=iv[t], f_iv15=iv[t] - iv[t - 15],
                f_rng_prev=rng / day.prev_range if day.prev_range and day.prev_range == day.prev_range else np.nan,
                f_wd=float(wd), f_monthly=float(monthly),
                **{f"f_und_{u}": float(day.und == u) for u in R.UNDS},
            )
            x = exits_for(day.und, e, path)
            for col, v in zip(EXIT_COLS, x):
                r[col] = v
            rows.append(r)
    return rows


def main():
    out = []
    for u in R.UNDS:
        ds = R.load(u)
        n0 = len(out)
        for d in ds:
            out += day_rows(d, L.is_monthly(d, ds))
        print(u, len(ds), "days", len(out) - n0, "rows", flush=True)
    df = pd.DataFrame(out)
    df.to_parquet(R.OUT / "rows.parquet")
    print(len(df), "rows")


if __name__ == "__main__":
    main()
