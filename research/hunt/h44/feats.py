"""h44 stage 2: signal-time feature table for every Liquidity 15+5 trade (see PREREG.md). No P&L used except the
'prior outcome today' feature, which uses only trades of the same index that had CLOSED before the signal minute.

    OBUY_CACHE=<scratch>/hunt/h44/cache flock <scratch>/obuy.lock python3 -I research/hunt/h44/feats.py
-> <scratch>/hunt/h44/feats44.parquet (one row per trade in trades44.parquet, same order)
"""
from __future__ import annotations

import importlib.util
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
HUNT = os.path.dirname(HERE)
sys.path.insert(0, os.path.dirname(HUNT))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.data import market  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h44")
W = C.W
YEAR_MIN = 375.0 * 252
sys.path.insert(0, os.path.join(HUNT, "h26"))
import build as B26  # noqa: E402
import feats as FT  # noqa: E402
FT.OUT = os.path.join(C.SCRATCH, "hunt/h26/cache/h26")


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


H29 = _load("b29", os.path.join(HUNT, "h29", "build.py"))


def ffill1(a):
    return pd.Series(a).ffill().values


def daily_tables(ix):
    D = ix.daily().copy()
    pc = D.close.shift(1)
    tr = np.maximum(D.high - D.low, np.maximum((D.high - pc).abs(), (D.low - pc).abs()))
    D["atr"] = tr.rolling(14, min_periods=5).mean().shift(1)
    D["pdh"], D["pdl"], D["pdc"] = D.high.shift(1), D.low.shift(1), pc
    return D


def roll_pit(roll, u, day):
    g = roll[roll.und == u]
    if g.empty:
        return np.nan
    ms = g.month.values
    j = np.searchsorted(ms, pd.Timestamp(day).strftime("%Y-%m")) - 1
    return float(g.spread.values[j] / 2) if j >= 0 else np.nan


def main():
    T = pd.read_parquet(os.path.join(OUT, "trades44.parquet"))
    T["day"] = pd.to_datetime(T.day)
    S = pd.read_parquet(os.path.join(OUT, "signals44.parquet"))
    S["day"] = pd.to_datetime(S.day)
    roll = pd.read_csv(os.path.join(C.SCRATCH, "hunt/h23/roll.csv"))
    har = pd.read_parquet(os.path.join(C.SCRATCH, "hunt/h29/cache/h29/har.parquet"))
    har["day"] = pd.to_datetime(har.day)
    harm = {(r.und, r.day): r.har for r in har.itertuples()}
    f40p = os.path.join(C.SCRATCH, "hunt/h40/feat.parquet")
    f40 = pd.read_parquet(f40p) if os.path.exists(f40p) else None
    if f40 is not None:
        f40["day"] = pd.to_datetime(f40.day)
        f40 = f40.set_index(["und", "day"])[["S01", "S02", "S03"]]
    mk = market()
    vix = mk.vix
    vmin = vix.minutes()
    rows = [None] * len(T)
    t0 = time.time()
    for u in T.und.unique():
        ix = mk.index(u)
        M = ix.mat()
        Dd = daily_tables(ix)
        op = mk.options(u)
        step = C.STEP[u]
        days_ix = ix.days
        exp_days = np.array([d for d in days_ix if ix.d[d]["exp"]])
        odays, OD = FT.opt(u)
        opos = {pd.Timestamp(d): i for i, d in enumerate(odays)}
        try:
            cdays, CD = FT.cons(u) if u in ("BANKNIFTY", "NIFTY") else ([], {})
        except Exception as e:  # noqa: BLE001
            print("cons fail", u, e, flush=True)
            cdays, CD = [], {}
        cpos = {pd.Timestamp(d): i for i, d in enumerate(cdays)}
        Tu = T[T.und == u]
        Su = S[S.und == u]
        for d, g in Tu.groupby("day"):
            dd = d.date()
            i = ix.pos.get(dd)
            if i is None:
                continue
            c = ffill1(M["c"][i])
            h, lo, o = M["h"][i], M["l"][i], M["o"][i]
            drow = Dd.loc[dd]
            atr = float(drow.atr) if np.isfinite(drow.atr) else np.nan
            op0 = float(np.nanmax([o[0], c[0]]) if np.isfinite(o[0]) else c[0])
            ch = op.chain(dd, "near")
            k = np.searchsorted(exp_days, dd)
            ed = exp_days[k] if k < len(exp_days) else None
            dte = (ed - dd).days if ed is not None else np.nan
            nda = (ix.pos[ed] - i) if (ed is not None and ed in ix.pos) else np.nan
            ivd = None
            if ch is not None and len(ch.K) >= 5 and np.isfinite(nda):
                mins = C.OPEN_M + np.arange(W)
                tmin = (C.LAST_M + 1 - mins) + 375.0 * nda
                Tt = np.maximum(tmin, 1.0) / YEAR_MIN
                with np.errstate(all="ignore"):
                    K, Cc, Pc, F, K0, strad, i0 = H29.chain_core(ch, c, step)
                    iv = H29.straddle_iv(strad, F, K0, Tt)
                    dist = np.maximum(np.where(np.isfinite(strad), 0.5 * strad, np.nan), step)
                    Fs = np.where(np.isfinite(F), F, c)
                    kc = H29.nearest_idx(K, Fs + dist)
                    kp = H29.nearest_idx(K, Fs - dist)
                    kc = np.where(K[kc] <= Fs, np.minimum(kc + 1, len(K) - 1), kc)
                    kp = np.where(K[kp] >= Fs, np.maximum(kp - 1, 0), kp)
                    cols = np.arange(W)
                    ivc = H29.implied(Cc[kc, cols], F, K[kc], Tt, 1)
                    ivp = H29.implied(Pc[kp, cols], F, K[kp], Tt, -1)
                    rr = np.where(K[kc] > Fs, ivc, np.nan) - np.where(K[kp] < Fs, ivp, np.nan)
                    oC, oP = B26.ffill(ch.oi["C"]), B26.ffill(ch.oi["P"])
                    atmK = np.round(c / step) * step
                    inb = np.abs(K[:, None] - atmK[None, :]) <= 5 * step + 1e-9
                    pcr = np.nansum(np.where(inb, oP, 0), axis=0) / np.nansum(np.where(inb, oC, 0), axis=0)
                ivd = dict(iv=iv, rr=rr, strad=strad, pcr=pcr, Cc=Cc, Pc=Pc, K=K)
            # realised vol since open (h29 rvt)
            r = np.diff(np.log(c))
            r = np.r_[np.nan, r]
            r[:2] = np.nan
            cs = np.nancumsum(r ** 2)
            n = np.cumsum(~np.isnan(r))
            rvt = np.sqrt(cs / np.maximum(n, 1) * 375 * 252)
            rvt[n < 30] = np.nan
            vprev = vix.prev_close(dd)
            vm = vmin.get(dd)
            oi_i = opos.get(d)
            ci = cpos.get(d)
            sday = Su[Su.day == d]
            closed = g[["exit_min", "net1_0.02"]].values
            for ridx, t in g.iterrows():
                col = int(t.sig_min - C.OPEN_M)
                sd = int(t.side)
                x = dict(row=ridx)
                cl = c[col]
                hi_s, lo_s = np.nanmax(h[:col + 1]), np.nanmin(lo[:col + 1])
                x["tod"] = t.sig_min
                x["dow"] = d.dayofweek
                x["book15"] = 1.0 if "15" in str(t.book) else 0.0
                x["dte"] = dte
                x["vix_prev"] = vprev
                x["vix_chg"] = (np.nanmax([np.nan, *[vm[col]]]) / vprev - 1) if vm is not None and np.isfinite(vm[col]) else np.nan
                x["atr_pct"] = atr / drow.pdc if np.isfinite(atr) else np.nan
                x["range_atr"] = (hi_s - lo_s) / atr
                fh = min(col, 60)
                x["fhr_atr"] = (np.nanmax(h[:fh + 1]) - np.nanmin(lo[:fh + 1])) / atr
                x["gap_dir"] = (op0 / drow.pdc - 1) * drow.pdc / atr * sd
                x["pdh_dir"] = (cl - drow.pdh) / atr * sd
                x["pdl_dir"] = (cl - drow.pdl) / atr * sd
                pos_r = (cl - lo_s) / (hi_s - lo_s) if hi_s > lo_s else 0.5
                x["posr_dir"] = pos_r if sd > 0 else 1 - pos_r
                x["r15_dir"] = (cl - c[max(col - 15, 0)]) / atr * sd
                x["r60_dir"] = (cl - c[max(col - 60, 0)]) / atr * sd
                x["ropen_dir"] = (cl - op0) / atr * sd
                if oi_i is not None:
                    x["bu15_dir"] = OD["BU15_z"][oi_i, col] * sd
                    x["buopen_dir"] = OD["BUopen_z"][oi_i, col] * sd
                    x["doipc5_dir"] = OD["dOIpc5_z"][oi_i, col] * sd
                if ivd is not None:
                    x["pcr_chg_dir"] = (ivd["pcr"][col] - ivd["pcr"][min(5, col)]) * sd
                    x["iv"] = ivd["iv"][col]
                    hv = harm.get((u, d), np.nan)
                    x["iv_har"] = ivd["iv"][col] / hv if np.isfinite(hv) else np.nan
                    x["iv_rv"] = ivd["iv"][col] / rvt[col] if np.isfinite(rvt[col]) and rvt[col] > 0 else np.nan
                    x["rr_dir"] = ivd["rr"][col] * sd
                    x["drr15_dir"] = (ivd["rr"][col] - ivd["rr"][max(col - 15, 0)]) * sd
                    x["strad_pct"] = ivd["strad"][col] / cl
                    kk = np.searchsorted(ivd["K"], t.strike)
                    if kk < len(ivd["K"]) and ivd["K"][kk] == t.strike:
                        A = ivd["Cc"] if sd > 0 else ivd["Pc"]
                        p = A[kk, col]
                        x["prem_pct"] = p / cl if np.isfinite(p) else np.nan
                        x["tick_rel"] = 0.05 / p if np.isfinite(p) and p > 0 else np.nan
                x["logv5"] = np.log1p(t.v5)
                x["roll_hs"] = roll_pit(roll, u, d)
                x["room_atr"] = abs(t.idx_target - t.ref_spot) / atr if np.isfinite(t.idx_target) else np.nan
                x["stop_atr"] = abs(t.ref_spot - t.idx_stop) / atr if np.isfinite(t.idx_stop) else np.nan
                lv = [drow.pdh, drow.pdl, drow.pdc]
                if col >= 15:
                    lv += [np.nanmax(h[:15]), np.nanmin(lo[:15])]
                lv = np.array([v for v in lv if np.isfinite(v)])
                beyond = lv[(lv - cl) * sd > 0]
                x["brain_room_atr"] = min(np.min(np.abs(beyond - cl)) / atr, 3.0) if len(beyond) else 3.0
                x["n_sig_before"] = int((sday.sig_min < t.sig_min).sum())
                done = closed[closed[:, 0] < t.sig_min]
                x["n_closed_before"] = len(done)
                x["prior_out"] = float(np.sign(done[:, 1].sum())) if len(done) else 0.0
                if ci is not None:
                    x["vwapsh_dir"] = (CD["VWAPSH"][ci, col] - 0.5) * sd
                    x["vwb_dir"] = CD["VWB"][ci, col] * sd
                if f40 is not None and (u, d) in f40.index:
                    fr = f40.loc[(u, d)]
                    for kf in ("S01", "S02", "S03"):
                        x[f"{kf.lower()}_dir"] = fr[kf] * sd
                rows[ridx] = x
            op.chains.clear() if hasattr(op, "chains") else None
        mk.release(u)
        print(u, "done", f"{time.time() - t0:.0f}s", flush=True)
    X = pd.DataFrame([r for r in rows if r is not None]).set_index("row").reindex(range(len(T)))
    X.to_parquet(os.path.join(OUT, "feats44.parquet"))
    print(X.describe().T.round(3).to_string(), flush=True)
    print("coverage by year\n", X.notna().groupby(T.day.dt.year.values).mean().round(2).T.to_string(), flush=True)


if __name__ == "__main__":
    main()
