"""S5 Trail as the app will run it: does the strategy survive the app's own implied volatility? 28 Oct 2026.

    python research/hunt/r128_missing/s5_app_check.py         # -> results/s5_app_check.json
    python research/hunt/r128_missing/s5_app_check.py --cal   # the app's IV shifted by IV_SHIFT -> results/s5_app_check_cal.json

The network was trained on the lake's IV (Dhan's rolling-option IV). The app has no Dhan feed, so it computes IV itself
(Black-76 on the index price, rate 0, calendar time to the 15:30 expiry; OptionMath.impliedVol). The two differ
(calls by about 1.5 vol points), and the inputs atm_iv and skew move S5's choices. This script recomputes those two
inputs the app's way, re-chooses S5 with the same networks (walk-forward fold models and the holdout model, each with
its own scalers refitted exactly as R123 did), and re-runs the trailing exit (R128 T2 x1) on the new picks.
Nothing else changes. It reads no new period: walk-forward 2023 - 15 Mar 2026 and the holdout Apr - Oct 2026.
"""
from __future__ import annotations

import json
import math
import sys
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import export_s5 as E  # noqa: E402
import r128 as T  # noqa: E402  (puts r123/r124 on the path)
import r123cfg as K  # noqa: E402
import r123data as D  # noqa: E402
import r123net as M  # noqa: E402
import r123rules as R  # noqa: E402

NJ = 16
SQRT2 = math.sqrt(2.0)


def ncdf(x):
    return 0.5 * (1 + math.erf(x / SQRT2))


def b76(F, Kk, t, s, call):
    v = s * math.sqrt(t)
    d1 = (math.log(F / Kk) + v * v / 2) / v
    d2 = d1 - v
    return F * ncdf(d1) - Kk * ncdf(d2) if call else Kk * ncdf(-d2) - F * ncdf(-d1)


def app_iv(price, F, Kk, t, call):
    """OptionMath.impliedVol (rate 0) in percent; NaN where it refuses (below intrinsic / above maximum)."""
    if not (np.isfinite(price) and np.isfinite(F) and price > 0 and F > 0 and t > 0):
        return np.nan
    intr = max(F - Kk, 0.0) if call else max(Kk - F, 0.0)
    if price < intr or price > (F if call else Kk):
        return np.nan
    lo, hi = 1e-6, 5.0
    if b76(F, Kk, t, lo, call) >= price:
        return lo * 100
    for _ in range(100):
        m = (lo + hi) / 2
        if b76(F, Kk, t, m, call) > price:
            hi = m
        else:
            lo = m
    return (lo + hi) / 2 * 100


def years_to_expiry(day, t_row, exp_day):
    now = datetime(day.year, day.month, day.day, 9, 15) + timedelta(minutes=t_row + 1)
    end = datetime(exp_day.year, exp_day.month, exp_day.day, 15, 30)
    return max((end - now).total_seconds(), 0.0) / (365 * 86400)


# Dhan's IV minus the app's, median by side and offset over 2024-25 design-period samples (no outcome read; ivmap in the
# write-up): calls sit ~1.5 points below the app's, puts ~1.3 above. Added to the app's IV when `cal` is on.
IV_SHIFT = {("C", -3): -2.02, ("C", 0): -1.53, ("C", 3): -1.21, ("P", -3): 0.97, ("P", 0): 1.31, ("P", 3): 1.79}


def app_chain_iv(rows: pd.DataFrame, und: str, last_day, cal: bool = False) -> pd.DataFrame:
    """atm_iv and skew recomputed from the lake's option closes the app's way, at each morning row ([IV_SHIFT] added: cal)."""
    g = D.load_grid(und, last_day)
    LO = D.LakeOptions(und, last_day)
    exps = sorted(LO.expiry_days())
    step = K.STEP[und]
    pos = g.pos()
    out = {}
    for d, sub in rows.groupby("day"):
        i = pos.get(d)
        nxt = [e for e in exps if e >= d]
        opts = LO.day(d, g.c[i]) if i is not None else None
        if opts is None or not nxt:
            continue
        ch = opts.chain["close"]
        for t in sub.row.values:
            spot = D._last(g.c[i][:t + 1])
            cl = D.ffill_rows(ch[:t + 1].transpose(1, 2, 0))[..., -1]
            atmk = D.atm(spot, step)
            ty = years_to_expiry(d, int(t), nxt[0])

            def iv(off, side):
                v = app_iv(cl[off + 10, side], spot, atmk + off * step, ty, side == 0)
                return v + IV_SHIFT[("C" if side == 0 else "P", off)] if cal else v
            c0, p0 = iv(0, 0), iv(0, 1)
            atm_iv = np.nanmean([c0, p0]) if np.isfinite([c0, p0]).any() else np.nan
            out[(d, int(t))] = (atm_iv, iv(-3, 1) - iv(3, 0))
    r = rows.copy()
    v = [out.get((d, int(t)), (np.nan, np.nan)) for d, t in zip(r.day, r.row)]
    r["atm_iv"] = [x[0] for x in v]
    r["skew"] = [x[1] for x in v]
    return r


class Model:
    """One fitted N1 (3 seeds) with R123's own scalers, refitted from its training rows."""

    def __init__(self, tag, train, S):
        import torch
        blob = torch.load(K.OUT / f"net_N1_{tag}.pt", weights_only=False)
        self.st = [{k: v.double().numpy() for k, v in s.items()} for s in blob["states"]]
        days = np.array(sorted(train.day.unique()))
        cut = days[int(len(days) * (1 - K.VAL_SHARE))]
        tr = train[train.day < cut]
        self.cols = list(D.TAB_COLS)
        self.tab = M.Tab().fit(tr[self.cols].values.astype(np.float64))
        self.store = M.SeqStore(S).fit(M.SeqStore(S).flat(tr.und.values, tr.gpos.values))
        self.mn = R.neither_means(train)

    def ev(self, F):
        X = self.tab.transform(F[self.cols].values.astype(np.float64)).astype(np.float64)
        fp = self.store.flat(F.und.values, F.gpos.values)
        out = np.zeros((len(F), NJ))
        P = np.zeros((len(F), NJ, 3))
        for i in range(len(F)):
            W = self.store.window(fp[i:i + 1])[0].astype(np.float64)
            p = np.mean([E.forward(s, W, X[i])[0] for s in self.st], 0)
            P[i] = p
            u = F.und.iat[i]
            out[i] = [p[j, 0] * K.CELLS[j // 2][0] - p[j, 1] * K.CELLS[j // 2][1] + p[j, 2] * self.mn.get((u, j), 0.0)
                      for j in range(NJ)]
        return out, P


def picks(F, ev):
    cls = F[[f"cls_{j}" for j in range(NJ)]].values
    ev = np.where(cls >= 0, ev, -np.inf)
    j = ev.argmax(1)
    sc = ev[np.arange(len(F)), j]
    ok = sc >= K.RULES["S5"]["thr"]
    C = F.loc[ok, ["und", "day", "row"]].copy()
    C["j"], C["score"] = j[ok], sc[ok]
    return C


def trail_trades(C: pd.DataFrame, last_day) -> list:
    """R128's x1 exit on each pick, one open trade per index, at most 5 a day (R123's portfolio, real exits)."""
    out = []
    for und, Cu in C.groupby("und"):
        g = D.load_grid(und, last_day)
        LO = D.LakeOptions(und, last_day)
        exp = LO.expiry_days()
        pos, lot, step = g.pos(), K.LOT[und], K.STEP[und]
        for d, Cd in Cu.groupby("day"):
            i = pos.get(d)
            opts = LO.day(d, g.c[i]) if i is not None else None
            if opts is None:
                continue
            last = K.EXIT_LAST_EXPIRY if d in exp else K.EXIT_LAST
            busy = -1
            for tr in Cd.sort_values(["row", "score"], ascending=[True, False]).itertuples(index=False):
                if tr.row <= busy:
                    continue
                x, y, _ = K.CELLS[int(tr.j) // 2]
                side = "C" if int(tr.j) % 2 == 0 else "P"
                c = D._last(g.c[i][:tr.row + 1])
                a = opts.contracts.get((side, D.atm(c, step)))
                r_ = T.convex_exit(a, tr.row, last, lot, d, und, x, y, "x1") if a is not None else None
                if r_ is None:
                    continue
                busy = r_["exit"]
                out.append(dict(und=und, day=d, row=int(tr.row), side=side, y=y, net=r_["net"], netx2=r_["netx2"],
                                why=r_["why"]))
    # R123's day cap (5 a day across indices, time order)
    T_ = pd.DataFrame(out)
    if T_.empty:
        return out
    T_ = T_.sort_values(["day", "row"]).groupby("day").head(5)
    return T_.to_dict("records")


def summary(T_: pd.DataFrame) -> dict:
    if T_.empty:
        return dict(n=0)
    m = T_.assign(mo=pd.to_datetime(T_.day).dt.to_period("M")).groupby("mo").net.sum()
    eq = T_.sort_values(["day", "row"]).net.cumsum()
    return dict(n=len(T_), win=float((T_.net > 0).mean()), avg=float(T_.net.mean()), avg_x2=float(T_.netx2.mean()),
                total=float(T_.net.sum()), worst_month=float(m.min()), max_dd=float((eq - eq.cummax()).min()),
                puts=float((T_.side == "P").mean()), stop_1000=int((T_.y == 1000).sum()),
                by_year={str(k): [int(len(v)), round(float(v.net.sum()))] for k, v in
                         T_.groupby(pd.to_datetime(T_.day).dt.year)})


def main(cal: bool = False):
    Fd, Sd = D.load("design")
    Fh = pd.read_parquet(K.OUT / "rows_holdout.parquet")
    Sh = dict(np.load(K.OUT / "seq_holdout.npz"))
    morning = list(K.MORNING_ROWS)
    res = {}
    for part in ("wf", "holdout"):
        orig, app, agree, n_rows = [], [], [], 0
        if part == "wf":
            jobs = []
            for f, (a, b) in K.FOLDS.items():
                train = Fd[Fd.day < a - timedelta(days=K.FOLD_EMBARGO_DAYS)]
                test = Fd[(Fd.day >= a) & (Fd.day <= b) & Fd.row.isin(morning)]
                jobs.append((f"wf_{f}", train, test, Sd, K.DESIGN[1]))
        else:
            jobs = [("holdout", Fd, Fh[Fh.row.isin(morning)], Sh, K.HOLDOUT[1])]
        for tag, train, test, S, last_day in jobs:
            m = Model(tag, train, S)
            test = test.reset_index(drop=True)
            ev0, _ = m.ev(test)
            test_app = pd.concat([app_chain_iv(test[test.und == u], u, last_day, cal) for u in K.UNDS]).loc[test.index]
            ev1, _ = m.ev(test_app)
            c0, c1 = picks(test, ev0), picks(test_app, ev1)
            k0 = set(zip(c0.und, c0.day, c0.row, c0.j))
            k1 = set(zip(c1.und, c1.day, c1.row, c1.j))
            agree.append((len(k0), len(k1), len(k0 & k1)))
            n_rows += len(test)
            orig += trail_trades(c0, last_day)
            app += trail_trades(c1, last_day)
            print(tag, "rows", len(test), "picks lake-IV", len(k0), "app-IV", len(k1), "both", len(k0 & k1), flush=True)
        res[part] = dict(rows=n_rows, picks_lake_iv=sum(a for a, _, _ in agree), picks_app_iv=sum(b for _, b, _ in agree),
                         picks_both=sum(c for _, _, c in agree), lake_iv=summary(pd.DataFrame(orig)),
                         app_iv=summary(pd.DataFrame(app)))
        print(part, json.dumps(res[part], default=str), flush=True)
    out = HERE / "results" / ("s5_app_check_cal.json" if cal else "s5_app_check.json")
    out.write_text(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    main(cal="--cal" in sys.argv)
