"""R98e new features (PREREG_E.md section 1), point-in-time, for every R98 decision row.

    python3 -I research/hunt/r98/features_e.py      # -> $R98_OUT/feat_e.parquet (keys + 18 new features)

F1 gamma exposure / OI walls, F2 demand on the strike, F3 breadth (constituent stocks), F4 cross-index, F5 events.
"""
from __future__ import annotations

import math
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402

HERE = Path(__file__).resolve().parent
MASTER = Path("/home/user/lake/raw/master")
EQ = R.LAKE / "eq"
NEW = ["gex_rel", "gex_side", "wall_em", "wall_oi30", "pin_em", "resid15", "iv_rel", "vshare15", "br_up", "br_vwap",
       "br_chg30", "x_r15", "x_r30", "x_rel30", "us_event", "dom_event", "gex_lvl", "gex_chg15"]
T11 = R.minute(11, 0)
CLOSE_MIN = 375                      # 15:30 = minute 375 after 09:15
YEAR_MIN = 375 * 252


def norm_pdf(x):
    return np.exp(-0.5 * x * x) / math.sqrt(2 * math.pi)


def norm_cdf(x):
    return 0.5 * (1 + np.vectorize(math.erf)(x / math.sqrt(2)))


def bs_greeks(S, K, iv, t, call: bool):
    """Delta and gamma, r = 0. iv in %, t = the grid minute."""
    T = max(CLOSE_MIN - t, 1) / YEAR_MIN
    sig = iv / 100.0
    if not (sig > 0) or not (S > 0):
        return np.nan, np.nan
    d1 = (math.log(S / K) + 0.5 * sig * sig * T) / (sig * math.sqrt(T))
    nd = 0.5 * (1 + math.erf(d1 / math.sqrt(2)))
    delta = nd if call else nd - 1
    gamma = math.exp(-0.5 * d1 * d1) / math.sqrt(2 * math.pi) / (S * sig * math.sqrt(T))
    return delta, gamma


def constituents():
    n50 = set(pd.read_csv(MASTER / "ind_nifty50list.csv").Symbol)
    bank = set(pd.read_csv(MASTER / "ind_niftybanklist.csv").Symbol)
    return n50, bank


def load_eq_year(y):
    f = EQ / f"equity_1m_{y}.parquet"
    if not f.exists():
        return {}
    e = pd.read_parquet(f)
    ts = pd.to_datetime(e.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    e["d"] = ts.dt.date
    e["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
    e = e[(e.m >= 0) & (e.m < R.N)]
    return dict(tuple(e.groupby("d")))


def breadth(eqd: pd.DataFrame | None, syms: set) -> tuple[np.ndarray, np.ndarray]:
    """Per minute: share of the stocks above their day's open, and above their VWAP (ffilled per stock)."""
    up, vw = np.full(R.N, np.nan), np.full(R.N, np.nan)
    if eqd is None or eqd.empty:
        return up, vw
    x = eqd[eqd.symbol.isin(syms)]
    if x.symbol.nunique() < 5:
        return up, vw
    ups, vws = [], []
    for _, g in x.groupby("symbol"):
        c = np.full(R.N, np.nan)
        v = np.zeros(R.N)
        c[g.m.values] = g.close.values
        v[g.m.values] = g.volume.values
        o0 = g.sort_values("m").open.iloc[0]
        cf = L.ffill(c, 10)
        pv = np.cumsum(np.nan_to_num(c) * v)
        vv = np.cumsum(v)
        vwap = np.where(vv > 0, pv / np.maximum(vv, 1e-9), np.nan)
        ups.append(np.where(np.isnan(cf), np.nan, (cf > o0).astype(float)))
        vws.append(np.where(np.isnan(cf) | np.isnan(vwap), np.nan, (cf > vwap).astype(float)))
    with np.errstate(all="ignore"):
        up = np.nanmean(np.stack(ups), axis=0)
        vw = np.nanmean(np.stack(vws), axis=0)
    return up, vw


def index_returns(ixd: dict, und: str, d) -> dict:
    """{other index: close array} for date d."""
    out = {}
    for u, days in ixd.items():
        if u != und and d in days:
            out[u] = L.ffill(days[d], 5)
    return out


def load_index_closes():
    out = {}
    for u in R.UNDS:
        parts = [R._read(p) for p in sorted(R.LAKE.glob(f"index_1m_{u}_*.parquet"))]
        ix = pd.concat(parts, ignore_index=True)
        days = {}
        for d, g in ix.groupby("d"):
            a = np.full(R.N, np.nan)
            a[g.m.values] = g.close.values
            days[d] = a
        out[u] = days
    return out


def run_und(und):
    n50, bank = constituents()
    syms = bank if und in ("BANKNIFTY", "FINNIFTY") else n50
    ev = pd.read_csv(HERE / "events.csv")
    us = set(ev[ev.category.isin(["FOMC", "USCPI"])].session)
    dom = set(ev[ev.category.isin(["RBI", "BUDGET", "ELECTION"])].session)
    ixd = load_index_closes()
    rows = pd.read_parquet(R.OUT / "rows.parquet", columns=["und", "day", "t", "strike", "side"], filters=[("und", "==", und)])
    byday = dict(tuple(rows.groupby("day")))
    eq_cache = {}
    out = []
    for day in R.load(und):
        if day.day not in byday:
            continue
        y = day.day.year
        if y not in eq_cache:
            eq_cache = {y: load_eq_year(y)}
        bu, bv = breadth(eq_cache[y].get(day.day), syms)
        others = index_returns(ixd, und, day.day)
        out += day_features(day, byday[day.day][["t", "strike", "side"]].itertuples(index=False), bu, bv, others,
                            str(day.day) in us, str(day.day) in dom)
    print(und, len(out), flush=True)
    return pd.DataFrame(out)


def day_features(day, rows, bu, bv, others, is_us, is_dom) -> list[dict]:
    """Features for the (t, strike, side) rows of one expiry day; every input array is read only up to row t."""
    und = day.und
    out = []
    if True:
        sp = L.spot(day)
        em, katm = L.straddle(day)
        # chain arrays (ffilled): OI 30 min, IV 5 min, close 3 min, volume raw
        ks = sorted({k for k, _ in day.opts})
        oi = {key: L.ffill(a[:, R.OI], 30) for key, a in day.opts.items()}
        iv = {key: L.ffill(a[:, R.IV], 5) for key, a in day.opts.items()}
        cl = {key: L.ffill(a[:, R.C], 3) for key, a in day.opts.items()}
        vol = {key: np.nan_to_num(a[:, R.V]) for key, a in day.opts.items()}
        cache = {}

        def chain(t):
            if t in cache:
                return cache[t]
            S = sp[t]
            g_tot = g_up = g_dn = 0.0
            per = {}
            for key in day.opts:
                k, s = key
                o, v = oi[key][t], iv[key][t]
                if np.isnan(o) or np.isnan(v) or v <= 0 or np.isnan(S):
                    continue
                _, gm = bs_greeks(S, k, v, t, s == "C")
                if np.isnan(gm):
                    continue
                gx = o * gm * S * S * 0.01
                g_tot += gx
                if k > S:
                    g_up += gx
                elif k < S:
                    g_dn += gx
                per[key] = o
            cache[t] = (g_tot, g_up, g_dn, per)
            return cache[t]

        for r in rows:
            t, k, s = int(r[0]), float(r[1]), r[2]
            sign = 1.0 if s == "C" else -1.0
            S, E = sp[t], em[t]
            g_tot, g_up, g_dn, per = chain(t)
            g11 = chain(T11)[0]
            g15 = chain(t - 15)[0]
            side_oi = {kk: o for (kk, ss), o in per.items() if ss == s and ((kk > S) if s == "C" else (kk < S))}
            tot_oi = {}
            for (kk, ss), o in per.items():
                tot_oi[kk] = tot_oi.get(kk, 0.0) + o
            wall = max(side_oi, key=side_oi.get) if side_oi else None
            pin = max(tot_oi, key=tot_oi.get) if tot_oi else None
            f = dict(und=und, day=day.day, t=t, strike=k, side=s)
            f["gex_lvl"] = g_tot
            f["gex_rel"] = g_tot / g11 if g11 > 0 else np.nan
            f["gex_chg15"] = g_tot / g15 - 1 if g15 > 0 else np.nan
            f["gex_side"] = ((g_up if s == "C" else g_dn) / g_tot) if g_tot > 0 else np.nan
            f["wall_em"] = abs(wall - S) / E if (wall is not None and E > 0) else np.nan
            if wall is not None:
                w0 = oi[(wall, s)][t - 30]
                f["wall_oi30"] = oi[(wall, s)][t] / w0 - 1 if w0 and w0 == w0 else np.nan
            else:
                f["wall_oi30"] = np.nan
            f["pin_em"] = abs(pin - S) / E if (pin is not None and E > 0) else np.nan
            key = (k, s)
            p0, p1 = cl[key][t - 15], cl[key][t]
            d0, _ = bs_greeks(sp[t - 15], k, iv[key][t - 15], t - 15, s == "C") if not np.isnan(sp[t - 15]) else (np.nan, np.nan)
            f["resid15"] = ((p1 - p0) - d0 * (sp[t] - sp[t - 15])) / p0 if (p0 and p0 == p0 and d0 == d0) else np.nan
            ka = katm[t]
            ivs = [iv[(ka, x)][t] for x in "CP" if (ka, x) in iv]
            ivs = [v for v in ivs if v == v and v > 0]
            f["iv_rel"] = iv[key][t] / np.mean(ivs) if ivs and iv[key][t] == iv[key][t] else np.nan
            sv = sum(vol[kk][t - 14:t + 1].sum() for kk in day.opts if kk[1] == s)
            f["vshare15"] = vol[key][t - 14:t + 1].sum() / sv if sv > 0 else np.nan
            f["br_up"] = bu[t] if sign > 0 else 1 - bu[t]
            f["br_vwap"] = bv[t] if sign > 0 else 1 - bv[t]
            f["br_chg30"] = sign * (bu[t] - bu[t - 30])
            rr15 = [sign * (a[t] / a[t - 15] - 1) * 100 for a in others.values() if a[t] == a[t] and a[t - 15] == a[t - 15]]
            rr30 = [sign * (a[t] / a[t - 30] - 1) * 100 for a in others.values() if a[t] == a[t] and a[t - 30] == a[t - 30]]
            f["x_r15"] = float(np.mean(rr15)) if rr15 else np.nan
            f["x_r30"] = float(np.mean(rr30)) if rr30 else np.nan
            own30 = sign * (sp[t] / sp[t - 30] - 1) * 100
            f["x_rel30"] = own30 - f["x_r30"] if rr30 else np.nan
            f["us_event"] = float(is_us)
            f["dom_event"] = float(is_dom)
            out.append(f)
    return out


def main():
    with Pool(4) as p:
        parts = p.map(run_und, R.UNDS)
    df = pd.concat(parts, ignore_index=True)
    df.to_parquet(R.OUT / "feat_e.parquet")
    print(len(df), "rows;", df[NEW].notna().mean().round(3).to_dict())


if __name__ == "__main__":
    main()
