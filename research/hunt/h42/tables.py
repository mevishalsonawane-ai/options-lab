"""h42 tables: from the c_<U>.npz caches (build.py) + daily candles + VIX + constituents, build the three answer tables
  day_<U>.parquet   one row per index-day (2020/21+ minute era): opening moves, gaps, checkpoints, breakouts, pins ...
  min_<U>.parquet   one row per index-day x 5-minute decision point (09:20 .. 14:55): past moves, streaks, levels,
                    VIX, option-premium / OI / volume features, cross-index and constituent features, forward moves
  dly_<U>.parquet   one row per day from daily candles 2006+ (long history; MIDCP 2022+)
Every feature uses data up to its decision minute only; every f*/rest*/holds* column is a FUTURE outcome.
python3 -I research/hunt/h42/tables.py
"""
from __future__ import annotations

import glob
import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import data as D  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h42")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
ROUND = {"NIFTY": 100, "BANKNIFTY": 500, "FINNIFTY": 200, "MIDCPNIFTY": 100, "SENSEX": 500}
GRID = np.arange(5, 341, 5)            # decision minutes (column of the 1-min bar whose close decides): 09:20..14:55
STOCKS = ["HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK", "RELIANCE", "INFY", "TCS", "BHARTIARTL", "LT",
          "ITC", "HINDUNILVR", "BAJFINANCE", "MARUTI", "SUNPHARMA", "HCLTECH", "NTPC", "ULTRACEMCO", "TITAN", "M&M"]
BANKS = ["HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK"]
EPOCH = date(1970, 1, 1)
warnings_off = np.seterr(all="ignore")
import warnings  # noqa: E402
warnings.filterwarnings("ignore")


def ld(u):
    z = np.load(os.path.join(OUT, f"c_{u}.npz"))
    return {k: z[k] for k in z.files}


def ff(a):
    return pd.DataFrame(a).T.ffill().T.values


def prev_mean(v, n=5):
    """mean of the previous n values (excluding today)."""
    s = pd.Series(v)
    return s.shift(1).rolling(n, min_periods=3).mean().values


def dte_arr(exp):
    out = np.full(len(exp), np.nan)
    j = -1
    for i in range(len(exp) - 1, -1, -1):
        if exp[i]:
            j = i
        out[i] = (j - i) if j >= 0 else np.nan
    return out


def daily_long(u):
    p = os.path.join(C.DATA, "candles", "daily", "IDX_I", f"{u}.parquet")
    d = pd.read_parquet(p)
    d["day"] = d.ts.dt.tz_localize(None).dt.normalize()
    d = d.drop_duplicates("day").set_index("day").sort_index()[["open", "high", "low", "close"]].astype(float)
    return d[d.high > d.low]          # FINNIFTY 2006-2011 daily rows are flat placeholders (O=H=L=C): drop them


def vix_daily():
    p = os.path.join(C.DATA, "candles", "daily", "IDX_I", "INDIA_VIX.parquet")
    d = pd.read_parquet(p)
    d["day"] = d.ts.dt.tz_localize(None).dt.normalize()
    return d.drop_duplicates("day").set_index("day").sort_index()["close"].astype(float)


def streak(sign):
    """consecutive same-sign count ending at each position (signed)."""
    out = np.zeros(len(sign))
    for i, x in enumerate(sign):
        if x == 0 or np.isnan(x):
            out[i] = 0
        elif i > 0 and np.sign(out[i - 1]) == x:
            out[i] = out[i - 1] + x
        else:
            out[i] = x
    return out


def dly_table(u, vx):
    d = daily_long(u)
    t = pd.DataFrame(index=d.index)
    pc = d.close.shift(1)
    t["gap"] = (d.open / pc - 1) * 100
    t["oc"] = (d.close / d.open - 1) * 100
    t["cc"] = (d.close / pc - 1) * 100
    t["rng"] = (d.high - d.low) / d.open * 100
    t["pret"] = t.cc.shift(1)
    t["prng"] = t.rng.shift(1)
    t["prng_rel"] = t.prng / t.rng.shift(2).rolling(20, min_periods=10).mean()
    t["rng_rel"] = t.rng / t.rng.shift(1).rolling(20, min_periods=10).mean()
    t["nr7"] = (t.prng <= t.rng.shift(1).rolling(7).min()).astype(int)
    t["inside"] = ((d.high.shift(1) < d.high.shift(2)) & (d.low.shift(1) > d.low.shift(2))).astype(int)
    st = streak(np.sign(t.cc.fillna(0).values))
    t["pstreak"] = pd.Series(st, index=t.index).shift(1)
    t["dow"] = t.index.dayofweek
    t["month"] = t.index.month
    t["year"] = t.index.year
    g = t.groupby([t.index.year, t.index.month])
    t["tdm"] = g.cumcount() + 1
    t["tdme"] = g.cumcount(ascending=False) + 1
    hi52 = d.high.shift(1).rolling(250, min_periods=200).max()
    t["near52h"] = ((pc / hi52 - 1) * 100 > -1).astype(int)
    v = vx.reindex(t.index).ffill()
    t["vixp"] = v.shift(1)
    t["vixchp"] = (v.shift(1) / v.shift(2) - 1) * 100
    t["pret5"] = (pc / d.close.shift(6) - 1) * 100
    t["up"] = (t.oc > 0).astype(int)
    t["ccup"] = (t.cc > 0).astype(int)
    t["dn"] = np.array([(x - EPOCH).days for x in t.index.date])
    t["und"] = u
    t = t.reset_index(drop=True).rename(columns={"dn": "day"})
    return t.iloc[30:].reset_index(drop=True)


def load_stocks():
    out = {}
    for sname in STOCKS:
        fs = sorted(glob.glob(os.path.join(C.DATA, "candles", "minute", "NSE_EQ", sname, "*.parquet")))
        if not fs:
            continue
        x = pd.concat([pd.read_parquet(f, columns=["ts", "open", "close"]) for f in fs])
        x["ts"] = x.ts.dt.tz_localize(None)
        x["day"] = np.array([(dd - EPOCH).days for dd in x.ts.dt.date])
        x["m"] = x.ts.dt.hour * 60 + x.ts.dt.minute - C.OPEN_M
        x = x[(x.m >= 0) & (x.m < C.W)].drop_duplicates(["day", "m"])
        days = np.sort(x.day.unique())
        di = np.searchsorted(days, x.day.values)
        cm = np.full((len(days), C.W), np.nan, np.float32)
        cm[di, x.m.values] = x.close.values
        om = np.full((len(days), C.W), np.nan, np.float32)
        om[di, x.m.values] = x.open.values
        out[sname] = (days, ff(cm), om)
    return out


def build_all():
    mk = D.market()
    vmin = mk.vix.minutes()
    vx = vix_daily()
    stocks = load_stocks()
    print("stocks", list(stocks), flush=True)
    mins, days_t = {}, {}
    for u in UNDS:
        z = ld(u)
        keep = (z["has"] == 1) & (z["real"] == 1)
        days = z["days"]
        o, h, l, c = z["io"], z["ih"], z["il"], z["ic"]
        cf = ff(c)
        hf, lf = ff(h), ff(l)
        n = len(days)
        exp = z["exp"].astype(bool)
        dte = dte_arr(exp)
        dlong = daily_long(u)
        dl_day = np.array([(x - EPOCH).days for x in dlong.index.date])
        pmap = dict(zip(dl_day, dlong.close.values))
        hmap = dict(zip(dl_day, dlong.high.values))
        lmap = dict(zip(dl_day, dlong.low.values))
        dl_pos = {dd: i for i, dd in enumerate(dl_day)}

        def prevd(dd, mp):
            i = dl_pos.get(dd)
            if i is None or i == 0:
                return np.nan
            return mp[dl_day[i - 1]]
        o0 = np.array([o[i][np.isfinite(o[i])][0] if np.isfinite(o[i]).any() else np.nan for i in range(n)])
        pc = np.array([prevd(dd, pmap) for dd in days])
        ph = np.array([prevd(dd, hmap) for dd in days])
        pl = np.array([prevd(dd, lmap) for dd in days])
        # ---------------------------------------------------------------- DAY table
        T = pd.DataFrame({"day": days, "exp": exp.astype(int), "dte": dte})
        dts = pd.to_datetime(days, unit="D")
        T["dow"] = dts.dayofweek
        T["year"] = dts.year
        T["month"] = dts.month
        T["gap"] = (o0 / pc - 1) * 100
        cl = cf[:, 374]
        c354 = cf[:, 354]
        T["oc"] = (cl / o0 - 1) * 100
        T["up"] = (cl > o0).astype(int)
        hcum = np.fmax.accumulate(np.nan_to_num(h, nan=-np.inf), axis=1)
        lcum = np.fmin.accumulate(np.nan_to_num(l, nan=np.inf), axis=1)
        dh, dlw = hcum[:, 374], lcum[:, 374]
        T["rng"] = (dh - dlw) / o0 * 100
        T["rng_rel"] = T.rng / prev_mean(T.rng.values, 20)
        T["trend"] = (np.abs(cl - o0) >= 0.7 * (dh - dlw)).astype(int)
        T["himin"] = np.nanargmax(np.nan_to_num(h, nan=-np.inf), axis=1)
        T["lomin"] = np.nanargmin(np.nan_to_num(l, nan=np.inf), axis=1)
        T["hi30"] = (T.himin < 30).astype(int)
        T["lo30"] = (T.lomin < 30).astype(int)
        T["hilast60"] = (T.himin >= 315).astype(int)
        T["lolast60"] = (T.lomin >= 315).astype(int)
        # previous-day features from the long daily table
        dl = dly_table(u, vx).set_index("day")
        for k in ("pret", "prng", "prng_rel", "nr7", "inside", "pstreak", "vixp", "vixchp", "pret5", "tdm", "tdme"):
            T[k] = dl[k].reindex(days).values
        for k in (5, 15, 30, 60):
            T[f"r{k}"] = (cf[:, k - 1] / o0 - 1) * 100
            T[f"rg{k}"] = (hcum[:, k - 1] - lcum[:, k - 1]) / o0 * 100
            T[f"rest{k}"] = (c354 / cf[:, k - 1] - 1) * 100
        T["rg60_rel"] = T.rg60 / prev_mean(T.rg60.values, 20)
        T["rest0"] = (c354 / o0 - 1) * 100
        # checkpoints
        for m in (60, 120, 195, 285):
            cm_ = cf[:, m]
            hs, ls = hcum[:, m], lcum[:, m]
            T[f"id{m}"] = (cm_ / o0 - 1) * 100
            T[f"pos{m}"] = (cm_ - ls) / np.where(hs > ls, hs - ls, np.nan)
            T[f"rest_{m}"] = (c354 / cm_ - 1) * 100
            T[f"hiearly{m}"] = (np.nanargmax(np.nan_to_num(h[:, :m + 1], nan=-np.inf), axis=1) < 15).astype(int)
            T[f"loearly{m}"] = (np.nanargmin(np.nan_to_num(l[:, :m + 1], nan=np.inf), axis=1) < 15).astype(int)
            T[f"holdhi{m}"] = (np.nanmax(np.nan_to_num(h[:, m + 1:375], nan=-np.inf), axis=1) <= hs).astype(int)
            T[f"holdlo{m}"] = (np.nanmin(np.nan_to_num(l[:, m + 1:375], nan=np.inf), axis=1) >= ls).astype(int)
        # gap fill
        T["gapfill"] = np.where(T.gap > 0, (dlw <= pc).astype(float), np.where(T.gap < 0, (dh >= pc).astype(float), np.nan))
        T["gapfill_dir"] = np.where(T.gap > 0, -1.0, 1.0)      # direction of the fill move
        # VIX intraday at 10:15
        vp = np.array([mk.vix.prev_close(D.ddate(int(dd))) for dd in days])
        v1015 = np.array([pd.Series(vmin[D.ddate(int(dd))]).ffill().values[60] if D.ddate(int(dd)) in vmin else np.nan for dd in days])
        T["vix1015"] = (v1015 / vp - 1) * 100
        # straddle decay
        st = ff(z["atmC"]) + ff(z["atmP"])
        T["strad920"] = st[:, 5] / cf[:, 5] * 1e4
        T["straddecay"] = (st[:, 354] / st[:, 5] - 1) * 100
        T["straddecay_am"] = (st[:, 120] / st[:, 5] - 1) * 100
        # pin (max OI strike)
        mK = ff(z["maxK"])
        for m in (165, 255, 315):
            k_ = mK[:, m]
            T[f"pinside{m}"] = np.sign(k_ - cf[:, m])
            T[f"pin{m}"] = (np.abs(c354 - k_) < np.abs(cf[:, m] - k_)).astype(int)
            T[f"pinmv{m}"] = np.sign(k_ - cf[:, m]) * (c354 / cf[:, m] - 1) * 100     # move toward the pin, %
            T[f"pindist{m}"] = np.abs(cf[:, m] - k_) / cf[:, m] * 100
        # first-hour breakout and prev-day high/low breaks (first close beyond, from 10:15 / from 09:20)
        fhh, fhl = hcum[:, 59], lcum[:, 59]
        for nm, lvl, sgn, start in (("bu", fhh, 1, 60), ("bd", fhl, -1, 60), ("pu", ph, 1, 5), ("pd", pl, -1, 5)):
            x = sgn * (cf[:, start:345] - lvl[:, None]) > 0
            first = np.where(x.any(1), x.argmax(1) + start, -1)
            if nm in ("pu", "pd"):          # require the open to be on the near side of the level
                first = np.where(sgn * (o0 - lvl) < 0, first, -1)
            T[f"{nm}min"] = first
            fi = np.maximum(first, 0)
            cfi = cf[np.arange(n), fi]
            T[f"{nm}_close"] = np.where(first >= 0, (sgn * (c354 - lvl) > 0).astype(float), np.nan)
            T[f"{nm}_f30"] = np.where(first >= 0, sgn * (cf[np.arange(n), np.minimum(fi + 30, 354)] / cfi - 1) * 100, np.nan)
            T[f"{nm}_rest"] = np.where(first >= 0, sgn * (c354 / cfi - 1) * 100, np.nan)
        # trend at checkpoint
        T["und"] = u
        days_t[u] = T[keep].reset_index(drop=True)
        # ---------------------------------------------------------------- MINUTE table
        s = GRID
        rows = []
        lr = np.log(cf)
        sd = {}
        for k in (5, 15, 30, 60):
            r = (lr[:, k::k] - lr[:, :-k:k][:, : lr[:, k::k].shape[1]])
            sd[k] = np.sqrt(prev_mean(np.nanvar(r[:, :374 // k], axis=1), 5)) * 1e4
        atmC, atmP, itmC, itmP = (ff(z[k]) for k in ("atmC", "atmP", "itmC", "itmP"))
        oiC, oiP = z["oiC"], z["oiP"]
        vC = np.concatenate([np.zeros((n, 1)), np.cumsum(np.nan_to_num(z["vC"]), 1)], 1)
        vP = np.concatenate([np.zeros((n, 1)), np.cumsum(np.nan_to_num(z["vP"]), 1)], 1)
        hu20, hd15 = z["hu20"], z["hd15"]
        R = ROUND[u]
        for i in range(n):
            if not keep[i]:
                continue
            d = days[i]
            cs = cf[i, s]
            f = {"day": np.full(len(s), d), "s": s}
            s15 = np.maximum(s - 15, 0)          # (bug fix: s - 15 < 0 wrapped to the END of the day = look-ahead)
            for k in (5, 15, 30, 60):
                f[f"p{k}"] = (cs / cf[i, np.maximum(s - k, 0)] - 1) * 1e4
                f[f"z{k}"] = f[f"p{k}"] / sd[k][i]
            for hz in (5, 15, 30, 60):
                f[f"f{hz}"] = (cf[i, np.minimum(s + hz, 354)] / cs - 1) * 1e4
            f["fsq"] = (cf[i, 354] / cs - 1) * 1e4
            hfw = np.array([np.nanmax(h[i, a + 1:min(a + 61, 355)]) for a in s])
            lfw = np.array([np.nanmin(l[i, a + 1:min(a + 61, 355)]) for a in s])
            f["frng60"] = (hfw - lfw) / cs * 1e4
            f["iday"] = (cs / o0[i] - 1) * 1e4
            hs, ls = hcum[i, s], lcum[i, s]
            f["pos"] = (cs - ls) / np.where(hs > ls, hs - ls, np.nan)
            f["dhi"] = (hs / cs - 1) * 1e4
            f["dlo"] = (cs / ls - 1) * 1e4
            prevh = hcum[i, s - 5]
            prevl = lcum[i, s - 5]
            f["nh5"] = (hs > prevh).astype(int)
            f["nl5"] = (ls < prevl).astype(int)
            c5 = cf[i, s - 5]
            f["xpdh"] = ((cs > ph[i]) & (c5 <= ph[i])).astype(int)
            f["xpdl"] = ((cs < pl[i]) & (c5 >= pl[i])).astype(int)
            f["xopu"] = ((cs > o0[i]) & (c5 <= o0[i])).astype(int)
            f["xopd"] = ((cs < o0[i]) & (c5 >= o0[i])).astype(int)
            f["xrndu"] = (np.floor(cs / R) > np.floor(c5 / R)).astype(int)
            f["xrndd"] = (np.floor(cs / R) < np.floor(c5 / R)).astype(int)
            dist_up = (np.ceil(cs / R) * R - cs) / cs * 1e4
            dist_dn = (cs - np.floor(cs / R) * R) / cs * 1e4
            f["nrndb"] = ((dist_up < 10) & (f["p15"] > 0)).astype(int)        # within 0.1% below a round level, rising
            f["nrnda"] = ((dist_dn < 10) & (f["p15"] < 0)).astype(int)        # within 0.1% above a round level, falling
            # 5-min / 15-min candle streaks
            for blk, mx, nm in ((5, 6, "st5"), (15, 4, "st15")):
                sg = np.zeros((len(s), mx))
                for j in range(mx):
                    a = s - blk * j
                    b = a - blk
                    sg[:, j] = np.where(b >= 0, np.sign(cf[i, np.maximum(a, 0)] - cf[i, np.maximum(b, 0)]), 0)
                first = sg[:, 0]
                same = (sg == first[:, None]) & (first[:, None] != 0)
                cnt = np.where(same.all(1), mx, np.argmin(same, axis=1))
                f[nm] = first * cnt
            # VIX
            dd = D.ddate(int(d))
            if dd in vmin:
                vf = pd.Series(vmin[dd]).ffill().values
                f["vix15"] = (vf[s] / vf[s15] - 1) * 100
                f["vixday"] = (vf[s] / vp[i] - 1) * 100
            else:
                f["vix15"] = np.full(len(s), np.nan)
                f["vixday"] = np.full(len(s), np.nan)
            # option premium features
            ac, ap = atmC[i, s], atmP[i, s]
            f["cA15"] = (ac / atmC[i, s15] - 1) * 100
            f["pA15"] = (ap / atmP[i, s15] - 1) * 100
            f["cApt15"] = ac - atmC[i, s15]
            f["pApt15"] = ap - atmP[i, s15]
            stc = ac + ap
            st15 = atmC[i, s15] + atmP[i, s15]
            f["strad"] = stc / cs * 1e4
            f["strad15"] = (stc / st15 - 1) * 100
            f["fstrad60"] = ((atmC[i, np.minimum(s + 60, 354)] + atmP[i, np.minimum(s + 60, 354)]) / stc - 1) * 100
            f["pcrat15"] = (np.log(ap / ac) - np.log(atmP[i, s15] / atmC[i, s15])) * 100
            f["ricC15"] = itmC[i, s] - np.nanmin(np.stack([itmC[i, np.maximum(s - j, 0)] for j in range(16)]), axis=0)
            f["ricP15"] = itmP[i, s] - np.nanmin(np.stack([itmP[i, np.maximum(s - j, 0)] for j in range(16)]), axis=0)
            tot = oiC[i, s] + oiP[i, s]
            f["pcr"] = oiP[i, s] / np.where(oiC[i, s] > 0, oiC[i, s], np.nan)
            nb = (oiP[i, s] - oiC[i, s]) / np.where(tot > 0, tot, np.nan)
            t15 = oiC[i, s15] + oiP[i, s15]
            nb15 = (oiP[i, s15] - oiC[i, s15]) / np.where(t15 > 0, t15, np.nan)
            f["dpcr15"] = (nb - nb15) * 100
            vc5 = vC[i, s + 1] - vC[i, s - 4]
            vp5 = vP[i, s + 1] - vP[i, s - 4]
            f["vshare"] = vc5 / np.where(vc5 + vp5 > 0, vc5 + vp5, np.nan)
            vt30 = (vC[i, s - 4] - vC[i, np.maximum(s - 34, 0)] + vP[i, s - 4] - vP[i, np.maximum(s - 34, 0)]) / 6
            f["vsurge"] = (vc5 + vp5) / np.where(vt30 > 0, vt30, np.nan)
            f["jC"] = ((hu20[i, s, 0] < 15) & (hu20[i, s, 0] < hd15[i, s, 0])).astype(int)
            f["dC"] = ((hd15[i, s, 0] < 15) & (hd15[i, s, 0] <= hu20[i, s, 0])).astype(int)
            f["jP"] = ((hu20[i, s, 1] < 15) & (hu20[i, s, 1] < hd15[i, s, 1])).astype(int)
            f["dP"] = ((hd15[i, s, 1] < 15) & (hd15[i, s, 1] <= hu20[i, s, 1])).astype(int)
            f["okC"] = np.isfinite(z["E"][i, s, 0]).astype(int)
            f["okP"] = np.isfinite(z["E"][i, s, 1]).astype(int)
            for k_ in ("vix15", "cA15", "pA15", "cApt15", "pApt15", "strad15", "pcrat15", "ricC15", "ricP15", "dpcr15"):
                f[k_] = np.where(s < 15, np.nan, f[k_])      # need 15 minutes of history
            rows.append(pd.DataFrame(f))
        Mn = pd.concat(rows, ignore_index=True)
        Mn = Mn.merge(days_t[u][["day", "dow", "dte", "exp", "year", "vixp", "gap"]], on="day", how="left")
        Mn["hb"] = np.select([Mn.s < 45, Mn.s < 105, Mn.s < 165, Mn.s < 225, Mn.s < 285], [0, 1, 2, 3, 4], 5)
        # causal z of option / OI features: / std over the previous 5 days' grid values
        for k in ("pcrat15", "dpcr15", "strad15", "cA15", "pA15", "vix15"):
            sdv = Mn.groupby("day")[k].std()
            sdp = np.sqrt(pd.Series(sdv.values ** 2).shift(1).rolling(5, min_periods=3).mean().values)
            Mn["z_" + k] = Mn[k].values / pd.Series(sdp, index=sdv.index).reindex(Mn.day).values
        Mn["und"] = u
        mins[u] = Mn
        print(u, "day rows", len(days_t[u]), "min rows", len(Mn), flush=True)
    # ---------------------------------------------------------------- cross-index columns
    for u in UNDS:
        for v in UNDS:
            if v == u:
                continue
            o_ = mins[v][["day", "s", "p5", "z5", "p15", "z15", "p30"]].rename(
                columns={"p5": f"x5_{v}", "z5": f"xz5_{v}", "p15": f"x15_{v}", "z15": f"xz15_{v}", "p30": f"x30_{v}"})
            mins[u] = mins[u].merge(o_, on=["day", "s"], how="left")
            od = days_t[v][["day", "r15", "r30"]].rename(columns={"r15": f"xr15_{v}", "r30": f"xr30_{v}"})
            days_t[u] = days_t[u].merge(od, on="day", how="left")
    # ---------------------------------------------------------------- constituents (2024-10+)
    for u in UNDS:
        Mn = mins[u]
        for sname, (sdays, cm, om) in stocks.items():
            pos = {d: i for i, d in enumerate(sdays)}
            ii = np.array([pos.get(d, -1) for d in Mn.day.values])
            okk = ii >= 0
            sv = Mn.s.values
            p5 = np.full(len(Mn), np.nan)
            p15 = np.full(len(Mn), np.nan)
            iday = np.full(len(Mn), np.nan)
            p5[okk] = (cm[ii[okk], sv[okk]] / cm[ii[okk], sv[okk] - 5] - 1) * 1e4
            p15[okk] = (cm[ii[okk], sv[okk]] / cm[ii[okk], np.maximum(sv[okk] - 15, 0)] - 1) * 1e4
            p15[sv < 15] = np.nan
            o0s = np.array([om[j][np.isfinite(om[j])][0] if j >= 0 and np.isfinite(om[j]).any() else np.nan for j in ii])
            iday[okk] = (cm[ii[okk], sv[okk]] / o0s[okk] - 1) * 1e4
            Mn[f"k5_{sname}"] = p5
            Mn[f"k15_{sname}"] = p15
            Mn[f"kd_{sname}"] = iday
        kd = [c for c in Mn.columns if c.startswith("kd_")]
        if kd:
            Mn["breadth"] = (Mn[kd] > 0).sum(1) / Mn[kd].notna().sum(1).replace(0, np.nan)
            b5 = [f"k5_{b}" for b in BANKS if f"k5_{b}" in Mn]
            Mn["bank5"] = Mn[b5].mean(1)
            sd5 = Mn.groupby("day")["bank5"].std()
            sdp = np.sqrt(pd.Series(sd5.values ** 2).shift(1).rolling(5, min_periods=3).mean().values)
            Mn["z_bank5"] = Mn.bank5.values / pd.Series(sdp, index=sd5.index).reindex(Mn.day).values
            for sname in stocks:
                c5 = f"k5_{sname}"
                sd5 = Mn.groupby("day")[c5].std()
                sdp = np.sqrt(pd.Series(sd5.values ** 2).shift(1).rolling(5, min_periods=3).mean().values)
                Mn[f"kz5_{sname}"] = Mn[c5].values / pd.Series(sdp, index=sd5.index).reindex(Mn.day).values
            # breadth at 10:15 onto the day table
            b = Mn[Mn.s == 60][["day", "breadth"]].rename(columns={"breadth": "breadth1015"})
            days_t[u] = days_t[u].merge(b, on="day", how="left")
        mins[u] = Mn
    for u in UNDS:
        f32 = {c: np.float32 for c in mins[u].columns if mins[u][c].dtype == np.float64}
        mins[u].astype(f32).to_parquet(os.path.join(OUT, f"min_{u}.parquet"), compression="zstd", index=False)
        days_t[u].to_parquet(os.path.join(OUT, f"day_{u}.parquet"), compression="zstd", index=False)
        dly_table(u, vx).to_parquet(os.path.join(OUT, f"dly_{u}.parquet"), compression="zstd", index=False)
    print("tables done", flush=True)


if __name__ == "__main__":
    build_all()
