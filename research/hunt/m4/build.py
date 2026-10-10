"""M4 build: per-commodity entry table (every 15-min grid x 14 near-month options) with horizons, realised vs implied,
delta-hedged P&L, unhedged exits (time exit E1 and SL30/TP50 exit E2), to-expiry payoff, plus a per-grid signal table.
No costs or P&L aggregation here.  Usage: python -I build.py SYM [SYM ...]
Outputs: scratchpad/hunt/m4/work/{ent_SYM.parquet, sig_SYM.parquet, min_SYM.parquet}"""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import datetime as dt
from pathlib import Path
import numpy as np
import pandas as pd
from scipy.stats import norm

SP = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt")
W = SP / "m4" / "work"
W.mkdir(parents=True, exist_ok=True)
STEP = {"CRUDEOIL": 50.0, "NATURALGAS": 5.0, "NATGASMINI": 5.0, "GOLD": 1000.0, "GOLDM": 500.0, "SILVER": 1000.0,
        "SILVERM": 1000.0, "COPPER": 5.0}
GRID, CUT = 15, 23 * 60 + 25
HS = {"h15": 15, "h60": 60, "h240": 240, "eod": 10 ** 6}
SL, TP = -0.30, 0.50
LAST_EXP = {"CRUDEOIL": dt.date(2026, 10, 15), "NATURALGAS": dt.date(2026, 10, 23), "NATGASMINI": dt.date(2026, 10, 23),
            "GOLD": dt.date(2026, 10, 30), "GOLDM": dt.date(2026, 10, 29), "SILVER": dt.date(2026, 10, 27),
            "SILVERM": dt.date(2026, 10, 27), "COPPER": dt.date(2026, 10, 23)}  # Kite instrument master 8 Oct
US_DST = [(dt.date(2025, 3, 9), dt.date(2025, 11, 2)), (dt.date(2026, 3, 8), dt.date(2026, 11, 1))]
_H28 = pd.read_csv(SP / "h28" / "events.csv")
CPI = {dt.date.fromisoformat(x) for x in _H28[_H28.kind == "uscpi"].date}
FOMC = {dt.date.fromisoformat(x) for x in _H28[_H28.kind == "fomc"].date}
EIA_EXC = {
    "2025-09-03": ("2025-09-04", "12:00"), "2025-10-15": ("2025-10-16", "12:00"), "2025-11-12": ("2025-11-13", "12:00"),
    "2025-12-24": ("2025-12-29", "17:00"), "2026-01-21": ("2026-01-22", "12:00"), "2026-02-18": ("2026-02-19", "12:00"),
    "2026-05-27": ("2026-05-28", "12:00"), "2026-09-09": ("2026-09-10", "12:00"), "2026-10-14": ("2026-10-15", "12:00")}


def first_sunday(y, m):
    d = dt.date(y, m, 1)
    return d + dt.timedelta(days=(6 - d.weekday()) % 7)


OPEC_MON = {first_sunday(y, m) + dt.timedelta(days=1) for y in (2025, 2026) for m in range(1, 13)}
OPEC_MON.add(dt.date(2025, 12, 1))


def et_to_ist(d, hm):
    from zoneinfo import ZoneInfo
    h, m = map(int, hm.split(":"))
    return pd.Timestamp(dt.datetime(d.year, d.month, d.day, h, m, tzinfo=ZoneInfo("America/New_York"))).tz_convert(
        "Asia/Kolkata")


def event_times(days):
    ev = {"eia": [], "ngs": [], "cpi": []}
    dset = set(days)
    for d in days:
        if d.weekday() == 2:
            k = d.isoformat()
            dd, hm = (dt.date.fromisoformat(EIA_EXC[k][0]), EIA_EXC[k][1]) if k in EIA_EXC else (d, "10:30")
            if dd in dset:
                ev["eia"].append(et_to_ist(dd, hm))
        if d.weekday() == 3:
            ev["ngs"].append(et_to_ist(d, "10:30"))
        if d in CPI:
            ev["cpi"].append(et_to_ist(d, "08:30"))
    return ev


# ------------------------------------------------------------------ black-76 (calendar tau, years)
def b76(F, K, T, sig, call):
    T = np.maximum(T, 1e-8)
    s = np.maximum(sig, 1e-4) * np.sqrt(T)
    d1 = (np.log(F / K) + 0.5 * s * s) / s
    c = F * norm.cdf(d1) - K * norm.cdf(d1 - s)
    return np.where(call, c, c - F + K)


def b76_delta(F, K, T, sig, call):
    T = np.maximum(T, 1e-8)
    s = np.maximum(sig, 1e-4) * np.sqrt(T)
    d1 = (np.log(F / K) + 0.5 * s * s) / s
    return np.where(call, norm.cdf(d1), norm.cdf(d1) - 1)


def implied_vol(P, F, K, T, call):
    P, F, K, T = map(np.asarray, (P, F, K, T))
    intr = np.where(call, np.maximum(F - K, 0), np.maximum(K - F, 0))
    lo, hi = np.full(P.shape, 1e-3), np.full(P.shape, 5.0)
    for _ in range(45):
        mid = (lo + hi) / 2
        p = b76(F, K, T, mid, call)
        hi = np.where(p > P, mid, hi)
        lo = np.where(p > P, lo, mid)
    iv = (lo + hi) / 2
    return np.where((P > intr + 1e-9) & (iv < 4.9) & (iv > 0.002), iv, np.nan)


# ------------------------------------------------------------------ load
def load(sym):
    if sym == "CRUDEOIL":
        C = SP / "strad_crude" / "cache"
        parts, sp = [], []
        for f in sorted(C.glob("c1_*.parquet")):
            _, off, side = f.stem.split("_")
            d = pd.read_parquet(f, columns=["ts", "strike", "close", "iv", "spot", "volume", "oi"])
            d["k"] = int(off.replace("ATM", "") or 0)
            d["cp"] = 0 if side == "CALL" else 1
            if d.k.iloc[0] == 0:
                sp.append(d[["ts", "spot"]])
            parts.append(d.drop(columns=["spot"]))
        L = pd.concat(parts, ignore_index=True)
        S = pd.concat(sp).groupby("ts").spot.median()
        nx = {}
        for side, cp in (("CALL", 0), ("PUT", 1)):
            d = pd.read_parquet(C / f"c2_ATM_{side}.parquet", columns=["ts", "strike", "close", "spot"])
            nx[cp] = d.set_index("ts")
        return L, S, nx
    R = SP / "m3" / "raw"
    L = pd.read_parquet(R / f"opt_{sym}.parquet", columns=["ts", "k", "cp", "strike", "close", "iv", "oi", "volume"])
    S = pd.read_parquet(R / f"spot_{sym}.parquet")
    S = (S.sort_values("src") if "src" in S else S).drop_duplicates("ts").set_index("ts").spot.sort_index()
    return L, S, None


def expiries_from_iv(M, L):
    """Infer each day's expiry: solve tau from the ATM call price at Dhan's IV, take the daily median date."""
    a = L[(L.k == 0) & (L.cp == 0)].set_index("ts")
    a = a[a.index.isin(M.index)]
    tod = a.index.hour * 60 + a.index.minute
    a = a[(tod >= 14 * 60) & (tod <= 16 * 60) & (a.iv > 0)]
    F = M.F.reindex(a.index).values
    K, P, sig = a.strike.values.astype(float), a.close.values.astype(float), a.iv.values / 100.0
    lo, hi = np.full(len(a), 1e-5), np.full(len(a), 0.5)
    for _ in range(50):
        mid = (lo + hi) / 2
        p = b76(F, K, mid, sig, True)
        hi = np.where(p > P, mid, hi)
        lo = np.where(p > P, lo, mid)
    tdays = (lo + hi) / 2 * 365
    est = pd.Series(a.index.tz_localize(None).normalize() + pd.to_timedelta(tdays, "D"), index=a.index)
    return est.groupby(a.index.tz_localize(None).normalize()).median().dt.normalize()


def main(sym):
    L, S, nx = load(sym)
    L = L[L.close > 0].copy()
    L["ts"] = pd.to_datetime(L.ts)
    M = S[S > 0].rename("F").to_frame()
    M.index = pd.to_datetime(M.index)
    M["day"] = M.index.tz_localize(None).normalize()
    M["tod"] = M.index.hour * 60 + M.index.minute
    M = M[(M.tod >= 9 * 60) & (M.tod <= 23 * 60 + 55)]
    # ---- expiries: switch day = first day whose ATM strike set / straddle restarts; use Dhan-IV implied tau
    est = expiries_from_iv(M, L)
    days = sorted(M.day.unique())
    # rough expiry dates from the Dhan-IV tau (noisy), refined by the ATM straddle jump at the contract switch
    cand = sorted(set(est.dt.date))
    rough = []
    for e in cand:
        if not rough or (e - rough[-1]).days > 10:
            rough.append(e)
    dlist = [pd.Timestamp(x).date() for x in days]
    a0 = L[L.k == 0].pivot_table(index="ts", columns="cp", values="close")
    st = (a0[0] + a0[1]).dropna()
    st = st[st.index.isin(M.index)]
    sday = st.index.tz_localize(None).normalize()
    first, last = st.groupby(sday).first(), st.groupby(sday).last()
    J = (first / last.shift(1)).dropna()
    J.index = [x.date() for x in J.index]
    # contract switch = day whose first ATM straddle jumps vs the previous close; greedy by jump size, >= 18 days apart
    cj = J[J > 1.25].sort_values(ascending=False)
    sw = []
    for d_, j_ in cj.items():
        if all(abs((d_ - x).days) >= 18 for x in sw):
            sw.append(d_)
    # intraday switch (series rolls mid-session): straddle max/min within the day > 2.5 and no switch within 18 days
    rng_ = (st.groupby(sday).max() / st.groupby(sday).min())
    rng_.index = [x.date() for x in rng_.index]
    bad_days = set()
    for d_, r_ in rng_[rng_ > 2.5].sort_values(ascending=False).items():
        if all(abs((d_ - x).days) >= 18 for x in sw):
            sw.append(d_)
            bad_days.add(d_)
    exps2 = [max(x for x in dlist if x < d_) for d_ in sorted(sw) if d_ > dlist[0]]
    print(sym, "intraday-switch days dropped:", sorted(map(str, bad_days)), flush=True)
    exps2 = [e for e in exps2 if e < LAST_EXP[sym] - dt.timedelta(days=10)]
    exps2 = sorted(set(exps2 + [LAST_EXP[sym]]))
    print(sym, "expiries:", [str(x) for x in exps2], "maxJ-missing rough:", len(rough) - len(exps2), flush=True)
    if __import__("os").environ.get("ONLYEXP"):
        return
    E = np.array(exps2, dtype="datetime64[D]")
    dd = np.array(dlist, dtype="datetime64[D]")
    ix = np.searchsorted(E, dd, side="left")
    day_exp = {pd.Timestamp(d): pd.Timestamp(E[min(i, len(E) - 1)]) for d, i in zip(dd, ix)}
    M["expiry"] = M.day.map(day_exp)
    sess = M.groupby("day").size()
    M["sess_len"] = M.day.map(sess)
    typ = int(np.median(sess.values))
    bd = {d: np.busday_count(d.date() + dt.timedelta(days=1), (day_exp[d] + pd.Timedelta(days=1)).date()) for d in
          sess.index}
    M["dte"] = M.day.map(bd)
    close_t = M.groupby("day").tod.max() + 1
    M["min_left"] = M.day.map(close_t) - M.tod
    M["N_rem"] = M.min_left + M.dte * typ
    tnow = M.index.tz_localize(None)
    M["tau"] = (((M.expiry + pd.Timedelta(hours=23, minutes=30)).values - tnow.values) / np.timedelta64(1, "s")) / (
            365.0 * 86400)
    M["r"] = np.log(M.F).groupby(M.day).diff()
    M[["F", "day", "tod", "expiry", "dte", "N_rem", "tau", "r"]].to_parquet(W / f"min_{sym}.parquet")
    exp_last_F = M.groupby("day").F.last()

    ev = event_times(dlist)
    a1 = L[(L.k == 1) & (L.cp == 0)].set_index("ts").strike
    a0 = L[(L.k == 0) & (L.cp == 0)].set_index("ts").strike
    dd_ = (a1 - a0.reindex(a1.index)).dropna()
    step = float(dd_[dd_ > 0].round(4).mode().iloc[0]) if len(dd_) else STEP[sym]
    print(sym, "strike step", step, flush=True)
    step_g = step
    L["day"] = L.ts.dt.tz_localize(None).dt.normalize()
    Lg = dict(tuple(L.groupby("day")))
    rows, sigs = [], []
    nxd = None
    if nx is not None:
        nxd = {cp: v for cp, v in nx.items()}
    for di, d in enumerate(sess.index):
        md = M[M.day == d]
        if len(md) < 200 or d not in Lg or d.date() in bad_days:
            continue
        Ld = Lg[d]
        idx = md.index
        n = len(md)
        F, tau, Nr, tod, r = md.F.values, md.tau.values, md.N_rem.values, md.tod.values, np.nan_to_num(md.r.values)
        dte = int(md.dte.values[0])
        strikes = np.sort(Ld.strike.unique())
        x1 = Ld[(Ld.k == 1) & (Ld.cp == 0)].set_index("ts").strike
        x0 = Ld[(Ld.k == 0) & (Ld.cp == 0)].set_index("ts").strike
        dx = (x1 - x0.reindex(x1.index)).dropna()
        step = float(dx[dx > 0].round(4).mode().iloc[0]) if (dx > 0).any() else step_g
        kpos = {k: i for i, k in enumerate(strikes)}
        PX = np.full((2, len(strikes), n), np.nan)
        OI = np.full((2, len(strikes), n), np.nan)
        ti = idx.get_indexer(Ld.ts)
        ok = ti >= 0
        cpv, kv = Ld.cp.values[ok].astype(int), np.array([kpos[x] for x in Ld.strike.values[ok]])
        PX[cpv, kv, ti[ok]] = np.where(Ld.volume.values[ok] > 0, Ld.close.values[ok], np.nan)  # no trade = no price
        OI[cpv, kv, ti[ok]] = Ld.oi.values[ok]
        OIf = pd.DataFrame(OI.reshape(-1, n).T).ffill().values.T.reshape(OI.shape)
        # own IV on observed prices, ffilled; ffill price up to 5 min
        PXf = pd.DataFrame(PX.reshape(-1, n).T).ffill(limit=5).values.T.reshape(PX.shape)
        Kmat = np.broadcast_to(strikes[None, :, None], PX.shape)
        callm = np.broadcast_to(np.array([True, False])[:, None, None], PX.shape)
        IV = implied_vol(np.nan_to_num(PXf, nan=-1.0), np.broadcast_to(F, PX.shape), Kmat, np.broadcast_to(tau, PX.shape),
                         callm)
        IVf = pd.DataFrame(IV.reshape(-1, n).T).ffill().bfill().values.T.reshape(PX.shape)
        # fill missing prices with B76 at last IV (flag)
        miss = np.isnan(PXf)
        FB = b76(np.broadcast_to(F, PX.shape), Kmat, np.broadcast_to(tau, PX.shape), np.nan_to_num(IVf, nan=0.3), callm)
        PXa = np.where(miss, FB, PXf)
        DL = b76_delta(np.broadcast_to(F, PX.shape), Kmat, np.broadcast_to(tau, PX.shape), np.nan_to_num(IVf, nan=0.3),
                       callm)
        cut_i = int(np.searchsorted(tod, CUT, side="right")) - 1
        cumr2 = np.concatenate([[0], np.cumsum(r ** 2)])
        dday = d.date()
        flags = dict(cpi_day=dday in CPI, fomc_day=dday in FOMC,
                     fomc_next=any(dday == f + dt.timedelta(days=1 if f.weekday() < 4 else 3) for f in FOMC),
                     opec_mon=dday in OPEC_MON, eia_day=any(e.date() == dday for e in ev["eia"]),
                     ngs_day=any(e.date() == dday for e in ev["ngs"]))
        evd = {k: [e for e in v if e.date() == dday] for k, v in ev.items()}
        # ATM-relative series map at each minute: from L (k offsets) for signals
        for i in range(n):
            t = tod[i]
            if t < 9 * 60 + 15 or t % GRID or t > CUT - 15 or i >= cut_i:
                continue
            K0 = round(F[i] / step) * step
            # --- signals (near month)
            sg = dict(ts=idx[i], day=d, tod=t, F=F[i], dte=dte)
            for h in (15, 60):
                j = i + h
                sg[f"fwd{h}"] = np.log(F[j] / F[i]) if j <= cut_i else np.nan
            sg["back60"] = np.log(F[i] / F[max(0, i - 60)])
            sg["back15"] = np.log(F[i] / F[max(0, i - 15)])

            def ivat(cp, k, ii):
                p = kpos.get(K0 + k * step)
                return IV[cp, p, ii] if p is not None and ii >= 0 else np.nan

            def pxat(cp, k, ii):
                p = kpos.get(K0 + k * step)
                return PX[cp, p, ii] if p is not None and ii >= 0 else np.nan

            for lag, nm in ((0, ""), (15, "_l15")):
                ii = i - lag
                sg["rr" + nm] = ivat(0, 2, ii) - ivat(1, -2, ii)
                sg["atmiv" + nm] = np.nanmean([ivat(0, 0, ii), ivat(1, 0, ii)])
            for lag, nm in ((0, ""), (5, "_l5")):
                ii = i - lag
                c, p = pxat(0, 0, ii), pxat(1, 0, ii)
                sg["basis" + nm] = (K0 + c - p) / F[ii] - 1 if ii >= 0 else np.nan
            ps = [kpos[K0 + kk * step] for kk in range(-3, 4) if K0 + kk * step in kpos]
            i60 = max(0, i - 60)
            okk = np.isfinite(OIf[:, ps, i]) & np.isfinite(OIf[:, ps, i60])  # same fixed strikes at both times
            sg["oic"], sg["oip"] = np.sum(np.where(okk[0], OIf[0, ps, i], 0)), np.sum(np.where(okk[1], OIf[1, ps, i], 0))
            sg["oic_l60"] = np.sum(np.where(okk[0], OIf[0, ps, i60], 0))
            sg["oip_l60"] = np.sum(np.where(okk[1], OIf[1, ps, i60], 0))
            if nxd is not None:
                v = []
                for cp in (0, 1):
                    x = nxd[cp]
                    if idx[i] in x.index:
                        row = x.loc[idx[i]]
                        tn = tau[i] + 30 / 365  # next monthly ~ one month later (approx, documented)
                        v.append(float(implied_vol(np.array([row.close]), np.array([row.spot]), np.array([row.strike]),
                                                   np.array([tn]), np.array([cp == 0]))[0]))
                sg["nextiv"] = np.nanmean(v) if v else np.nan
            sigs.append(sg)
            # --- entries: 14 options
            ivmed = np.nanmedian(IV[:, [kpos[K0 + kk * step] for kk in range(-3, 4) if K0 + kk * step in kpos], i])
            for k in range(-3, 4):
                p = kpos.get(K0 + k * step)
                if p is None:
                    continue
                for cp in (0, 1):
                    P0 = PX[cp, p, i]
                    if not np.isfinite(P0) or P0 <= 0:
                        continue
                    iv0 = IV[cp, p, i]
                    if not np.isfinite(iv0) or not (0.5 * ivmed <= iv0 <= 2.0 * ivmed):
                        continue  # stale or broken print (amendment 4)
                    row = dict(ts=idx[i], day=d, tod=t, dte=dte, k=k, cp=cp, K=K0 + k * step, F0=F[i], P0=P0, iv0=iv0,
                               tau0=tau[i], N_rem=Nr[i], dhiv=float(np.nanmean([IV[0, kpos[K0], i], IV[1, kpos[K0], i]]))
                               if K0 in kpos else np.nan, **flags)
                    path = PXa[cp, p]
                    for hn, h in HS.items():
                        j = min(i + h, cut_i)
                        nmin = j - i
                        row[f"{hn}_P"] = path[j]
                        row[f"{hn}_fb"] = bool(miss[cp, p, j])
                        row[f"{hn}_rv"] = cumr2[j + 1] - cumr2[i + 1]
                        row[f"{hn}_iv"] = (iv0 ** 2) * tau[i] / max(Nr[i], 1) * nmin
                        # delta hedge every 15 min
                        hp = np.arange(i, j, 15)
                        hp2 = np.append(hp[1:], j)
                        row[f"{hn}_dh"] = (path[j] - P0) - np.nansum(DL[cp, p, hp] * (F[hp2] - F[hp]))
                        # E2 exit: SL/TP on 1-min closes
                        seg = path[i + 1:j + 1] / P0 - 1
                        hit = np.where((seg <= SL) | (seg >= TP))[0]
                        row[f"{hn}_P2"] = path[i + 1 + hit[0]] if len(hit) else path[j]
                        tend = idx[j]
                        for en in ("eia", "ngs", "cpi"):
                            row[f"{hn}_{en}"] = any(idx[i] < e <= tend for e in evd[en])
                    row["mn_fwd60"] = np.log(F[min(i + 60, cut_i)] / F[i])
                    rows.append(row)
        if di % 40 == 0:
            print(sym, d.date(), len(rows), flush=True)
    Ent = pd.DataFrame(rows)
    # to-expiry payoff (intrinsic at the expiry day's last futures price)
    lastF = Ent.day.map(lambda x: exp_last_F.get(day_exp.get(x), np.nan))
    Ent["expF"] = lastF.values
    Ent["exp_pay"] = np.where(Ent.cp == 0, np.maximum(Ent.expF - Ent.K, 0), np.maximum(Ent.K - Ent.expF, 0))
    for c in Ent.columns:
        if Ent[c].dtype == "float64":
            Ent[c] = Ent[c].astype("float32")
    Ent.to_parquet(W / f"ent_{sym}.parquet", compression="zstd")
    pd.DataFrame(sigs).to_parquet(W / f"sig_{sym}.parquet", compression="zstd")
    print(sym, "entries", len(Ent), "fallback share h60", Ent.h60_fb.mean(), flush=True)


if __name__ == "__main__":
    for s in sys.argv[1:]:
        main(s)
