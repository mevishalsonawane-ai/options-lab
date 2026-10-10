"""h43 part D: enhancement search over the 5 bake-off families, nested walk-forward (see PREREG.md, Part D).

    flock <scratch>/obuy.lock python3 -I research/hunt/h43/partD.py build UND      # one index, all 3 strikes
    python3 -I research/hunt/h43/partD.py final                                     # nested WF + SPA (pre only)
    flock <scratch>/obuy.lock python3 -I research/hunt/h43/partD.py detail          # chosen variants: trades incl.
                                                                                     # the locked holdout (run once)

build writes <scratch>/hunt/h43/D/res_<U>.npz (per variant: ids, per-year pre-holdout net / trades / gross),
spa_<U>.npz (chunked White RC / SPA_c accumulators on the common NIFTY calendar), and hold_<U>.npz SEALED holdout
sums (read only by `detail` for the variants `final` chose).
Outcome tables are kept in memory only (disk is tight).
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import LADDER, Execution, Exits, StrikeRule, prepare_many  # noqa: E402
from obuy.overfit import _stationary_counts  # noqa: E402
import sigs  # noqa: E402

TEST = os.environ.get("H43_TEST")
OUT = os.path.join(C.SCRATCH, "hunt/h43/D" + ("_test" if TEST else ""))
os.makedirs(OUT, exist_ok=True)
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
HSP = {"BANKNIFTY": 0.0016, "NIFTY": 0.0016, "MIDCPNIFTY": 0.0021, "FINNIFTY": 0.0042, "SENSEX": 0.0016}
MONEY = [(0, "near"), (1, "near"), (-1, "near"), (0, "month")]   # ATM, 1-ITM, 1-OTM nearest; ATM monthly
NK = len(MONEY)
KNAME = ["ATM", "ITM1", "OTM1", "ATM_month"]
SQ = 15 * 60 + 15
SM0, SM1 = 555, 15 * 60 + 13
M = SM1 - SM0 + 1
HOLD = date(2025, 10, 1)
YEARS = [2020, 2021, 2022, 2023, 2024, 2025]
MDS = [1, 2, 4]
B_SPA = 500
EXE = Execution(expiry="skip", lot_mode="today")


def exits():
    t = lambda n: Exits(stop_pct=0.30, tgt_pct=0.60, time_stop=n, time_gain=None, sq_off=SQ)  # noqa: E731
    return {"S30T15": t(15), "S30T30": t(30), "S30T40": t(40), "S30T60": t(60),
            "S30EOD": Exits(stop_pct=0.30, tgt_pct=0.60, sq_off=SQ),
            "P15_10": Exits(stop_pts=10, tgt_pts=15, sq_off=SQ), "P20_15": Exits(stop_pts=15, tgt_pts=20, sq_off=SQ),
            "P30_20": Exits(stop_pts=20, tgt_pts=30, sq_off=SQ),
            "LAD": Exits(stop_pct=0.15, ladder=LADDER, ladder_ref_pct=0.15, tgt_pct=0.30, sq_off=SQ),
            "TRAIL": Exits(stop_pct=0.30, trail_pct=0.15, trail_arm=0.10, sq_off=SQ),
            "CHAMP": Exits(stop_pct=0.25, tgt_pct=0.50, ladder=((1, 1), (2, 2), (3, 3), (4, 4)), ladder_ref_pct=0.25,
                           time_stop=60, time_gain=None, sq_off=SQ)}


EXN = list(exits())
FILTERS = ["none", "vix_low", "vix_high", "calm", "wide", "beyond_pd", "inside_pd", "htf", "oi", "am", "pm"]


def sigsets():
    out = []
    for f, s in ((5, 13), (5, 21), (8, 21), (9, 21), (9, 34), (13, 34)):
        for thr in (0, 15, 20, 25, 30):
            for tf in (3, 5, 10, 15):
                out.append(("ema", dict(f=f, s=s, thr=thr, tf=tf)))
    for n in (14, 20, 30):
        for k in (1.5, 2.0, 2.5):
            for tf in (1, 3, 5, 10, 15):
                out.append(("bb", dict(n=n, k=k, tf=tf)))
    for R in (15, 30, 45, 60):
        for tf in (1, 3, 5, 10, 15):
            out.append(("orb", dict(R=R, tf=tf)))
    for st in (13 * 60, 13 * 60 + 30, 14 * 60, 14 * 60 + 30):
        for band in ((10, 20), (15, 25), (20, 30), None):
            for tf in (3, 5, 10, 15):
                out.append(("pe", dict(start=st, band=band, tf=tf)))
    return out


SIGS = sigsets()          # 249


# ------------------------------------------------------------------------------------------------ signals
class Sig:
    def __init__(self, ix):
        self.days, o, h, l, c = sigs.spot_series(ix)
        self.o, self.h, self.l, self.c = o, h, l, c
        self.D = len(self.days)
        self._b, self._ind = {}, {}

    def bars(self, tf):
        if tf not in self._b:
            self._b[tf] = sigs.bars(self.days, self.o, self.h, self.l, self.c, tf)
        return self._b[tf]

    def ind(self, key, fn):
        if key not in self._ind:
            self._ind[key] = fn()
        return self._ind[key]

    def make(self, fam, p):
        b = self.bars(p["tf"])
        tf = p["tf"]
        if fam == "ema":
            e1 = self.ind(("ema", tf, p["f"]), lambda: sigs.ema(b.c.values, p["f"]))
            e2 = self.ind(("ema", tf, p["s"]), lambda: sigs.ema(b.c.values, p["s"]))
            ad = self.ind(("adx", tf), lambda: sigs.adx(b.h.values, b.l.values, b.c.values))
            d = e1 - e2
            dp = np.r_[np.nan, d[:-1]]
            ok = ad > p["thr"] if p["thr"] else np.ones(len(d), bool)
            up, dn = (d > 0) & (dp <= 0) & ok, (d < 0) & (dp >= 0) & ok
            sel = up | dn
            di, sm, sd = b.di.values[sel], b.sig_min.values[sel], np.where(up, 1, -1)[sel]
        elif fam == "bb":
            cl = pd.Series(b.c.values)
            m = self.ind(("bbm", tf, p["n"]), lambda: cl.rolling(p["n"]).mean().values)
            s = self.ind(("bbs", tf, p["n"]), lambda: cl.rolling(p["n"]).std(ddof=0).values)
            up, dn = b.c.values > m + p["k"] * s, b.c.values < m - p["k"] * s
            sel = up | dn
            di, sm, sd = b.di.values[sel], b.sig_min.values[sel], np.where(up, 1, -1)[sel]
        elif fam == "orb":
            R = p["R"]
            hh = self.h.reshape(self.D, 375)[:, :R].max(axis=1)
            ll = self.l.reshape(self.D, 375)[:, :R].min(axis=1)
            x = b[b.start >= 555 + R]
            H, L = hh[x.di.values], ll[x.di.values]
            up, dn = x.c.values > H, x.c.values < L
            x = x[up | dn].assign(side=np.where(up, 1, -1)[up | dn]).groupby("di", sort=False).head(1)
            di, sm, sd = x.di.values, x.sig_min.values, x.side.values
        else:
            ad = self.ind(("adx", tf), lambda: sigs.adx(b.h.values, b.l.values, b.c.values))
            ok = b.sig_min.values >= p["start"]
            if p["band"]:
                ok &= (ad >= p["band"][0]) & (ad <= p["band"][1])
            di, sm, sd = b.di.values[ok], b.sig_min.values[ok], -np.ones(ok.sum(), int)
        keep = sm <= SM1
        return di[keep].astype(np.int64), sm[keep].astype(np.int64), sd[keep].astype(np.int64)


def features(mk, u, sg):
    """Per (day, minute) filter features, information up to that minute only."""
    D = sg.D
    h, l, c = (a.reshape(D, 375) for a in (sg.h, sg.l, sg.c))
    H, L = np.nanmax(h, axis=1), np.nanmin(l, axis=1)
    rng = pd.Series(H - L)
    adr = rng.shift(1).rolling(20, min_periods=5).mean().values
    sofar = np.fmax.accumulate(h, axis=1) - np.fmin.accumulate(l, axis=1)
    ratio = sofar / adr[:, None]
    pdh, pdl = np.r_[np.nan, H[:-1]], np.r_[np.nan, L[:-1]]
    htf = sigs.ema(sg.c, 300).reshape(D, 375)
    vx = mk.vix.daily.close
    vlow = np.zeros(D, bool)
    vok = np.zeros(D, bool)
    vi = vx.index
    for i, d in enumerate(sg.days):
        j = vi.searchsorted(d)
        if j >= 60:
            w = vx.values[max(0, j - 250):j]
            vlow[i] = w[-1] < np.median(w)
            vok[i] = True
    oi = np.full((D, 375), np.nan)
    opts = mk.options(u)
    step = C.STEP[u]
    for i, d in enumerate(sg.days):
        try:
            ch = opts.chain(d, "near")
        except Exception:
            ch = None
        if ch is None:
            continue
        atm = np.floor(c[i, 0] / step + 0.5) * step
        ks = (ch.K >= atm - 5 * step) & (ch.K <= atm + 5 * step)
        if not ks.any():
            continue
        tot = 0
        for rt, sgn in (("P", 1), ("C", -1)):
            a = pd.DataFrame(ch.oi[rt][ks]).ffill(axis=1).bfill(axis=1).values
            tot = tot + sgn * np.nansum(a, axis=0)
        oi[i] = tot - tot[0]
    mk.release(u)
    return dict(ratio=ratio, pdh=pdh, pdl=pdl, c=c, htf=htf, vlow=vlow, vok=vok, oi=oi)


def fmask(fi, F, di, sm, sd):
    col = sm - 555
    name = FILTERS[fi]
    if name == "none":
        return np.ones(len(di), bool)
    if name == "vix_low":
        return F["vok"][di] & F["vlow"][di]
    if name == "vix_high":
        return F["vok"][di] & ~F["vlow"][di]
    if name == "calm":
        return F["ratio"][di, col] < 0.5
    if name == "wide":
        return F["ratio"][di, col] >= 0.5
    cc = F["c"][di, col]
    if name == "beyond_pd":
        return np.where(sd > 0, cc > F["pdh"][di], cc < F["pdl"][di])
    if name == "inside_pd":
        return (cc <= F["pdh"][di]) & (cc >= F["pdl"][di])
    if name == "htf":
        return sd * (cc - F["htf"][di, col]) > 0
    if name == "oi":
        return sd * F["oi"][di, col] > 0
    if name == "am":
        return sm < 11 * 60 + 30
    return sm >= 11 * 60 + 30


# ------------------------------------------------------------------------------------------------ outcome table
def table(u, mo, tdays):
    money, series = mo
    D = len(tdays)
    di = {d: i for i, d in enumerate(tdays)}
    mins = np.arange(SM0, SM1 + 1)
    sig = pd.DataFrame({"und": u, "day": np.repeat(np.array(tdays, dtype=object), M * 2),
                        "sig_min": np.tile(np.repeat(mins, 2), D), "side": np.tile(np.array([-1, 1]), D * M)})
    sig["book"] = "all"
    (pk, _), = prepare_many([(sig, StrikeRule(money=money, series=series), EXE, 0, None, False)])
    del sig
    meta = pk.meta
    flat = ((meta.day.map(di).values.astype(np.int64) * M + meta.sig_min.values.astype(np.int64) - SM0) * 2
            + (meta.side.values > 0).astype(np.int64))
    cpos = pd.Series(np.arange(len(meta)), index=meta.cand.values)
    hs = HSP[u]
    T = {}
    for k, ex in exits().items():
        tr = pk.run(ex, EXE, chunk=6000)
        f = flat[cpos.loc[tr.cand.values].values]
        r = np.full(D * M * 2, np.nan, np.float32)
        r[f] = tr.net.values - hs * (tr.entry.values + tr.exit.values) * tr.qty.values
        g = np.full(D * M * 2, np.nan, np.float32)
        g[f] = tr.gross.values
        x = np.full(D * M * 2, -1, np.int16)
        x[f] = tr.exit_min.values
        T[k] = (r.reshape(D, M, 2), g.reshape(D, M, 2), x.reshape(D, M, 2))
        del tr
    prem = np.full(D * M * 2, np.nan, np.float32)
    prem[flat] = meta.e.values * meta.qty.values
    T["prem"] = prem.reshape(D, M, 2)
    mk = market()
    mk.release(u)
    return T


def rounds(td, mi, sd, x, maxr=4):
    """Positions walk, vectorised by rounds. td/mi sorted by (td, mi). Returns (idx, round)."""
    n = len(td)
    if n == 0:
        return np.zeros(0, np.int64), np.zeros(0, np.int64)
    key = td * 1000 + mi + SM0
    first = np.r_[0, np.nonzero(np.diff(td))[0] + 1]
    idx, rnd = [first], [np.zeros(len(first), np.int64)]
    cur = first
    for r in range(1, maxr):
        xm = x[td[cur], mi[cur], (sd[cur] > 0).astype(np.int64)].astype(np.int64)   # exit minute
        nxt = np.searchsorted(key, td[cur] * 1000 + xm, side="left")
        ok = nxt < n
        nxt = nxt[ok]
        ok2 = td[nxt] == td[cur][ok]
        cur = nxt[ok2]
        if not len(cur):
            break
        idx.append(cur)
        rnd.append(np.full(len(cur), r, np.int64))
    return np.concatenate(idx), np.concatenate(rnd)


class SpaAcc:
    def __init__(self, T):
        rng = np.random.default_rng(4343)
        self.counts = _stationary_counts(T, B_SPA, 5.0, rng).astype(np.float32)
        self.T = T
        self.rc_obs, self.rc_b = -np.inf, np.full(B_SPA, -np.inf)
        self.spa_obs, self.spa_b = 0.0, np.zeros(B_SPA)
        self.n = 0
        self.cols = []

    def add(self, col):
        self.cols.append(col)
        if len(self.cols) >= 4000:
            self.flush()

    def flush(self):
        if not self.cols:
            return
        X = np.stack(self.cols, axis=1).astype(np.float32)
        self.cols = []
        T = self.T
        mu = X.mean(axis=0)
        mus = self.counts @ X / T
        omega = np.sqrt(T) * mus.std(axis=0, ddof=1)
        omega = np.where(omega > 0, omega, np.inf)
        self.rc_obs = max(self.rc_obs, float(np.sqrt(T) * mu.max()))
        self.rc_b = np.maximum(self.rc_b, np.sqrt(T) * (mus - mu).max(axis=1))
        tt = np.sqrt(T) * mu / omega
        self.spa_obs = max(self.spa_obs, float(tt.max()))
        thr = -np.sqrt(2 * np.log(np.log(T))) * omega / np.sqrt(T)
        g = np.where(mu >= thr, mu, 0.0)
        self.spa_b = np.maximum(self.spa_b, (np.sqrt(T) * (mus - g) / omega).max(axis=1))
        self.n += X.shape[1]


def build(u, want=None):
    """want: None (full grid) or {vid: ...} -> return trades of those variants (detail mode)."""
    t0 = time.time()
    mk = market()
    ix = mk.index(u)
    sg = Sig(ix)
    F = features(mk, u, sg)
    print(u, "features", f"{time.time() - t0:.0f}s", flush=True)
    ui = UNDS.index(u)
    nifty = market().index("NIFTY").days
    cal = [d for d in nifty if d < HOLD]
    cali = {d: i for i, d in enumerate(cal)}
    tdays = [d for d in ix.days if not ix.d[d]["exp"]]
    if TEST:
        tdays = tdays[-330:-250]
    tmap = {d: i for i, d in enumerate(tdays)}
    d2t = np.array([tmap.get(d, -1) for d in sg.days])
    t_year = np.array([YEARS.index(d.year) if d < HOLD else -1 for d in tdays])
    t_hold = np.array([d >= HOLD for d in tdays])
    t_cal = np.array([cali.get(d, -1) for d in tdays])
    # signals + filters once per index
    cands = []
    for s, (fam, p) in enumerate(SIGS):
        di, sm, sd = sg.make(fam, p)
        td = d2t[di]
        ok = td >= 0
        di, sm, sd, td = di[ok], sm[ok], sd[ok], td[ok]
        o = np.lexsort((sm, td))
        di, sm, sd, td = di[o], sm[o], sd[o], td[o]
        cands.append([(td[m], sm[m] - SM0, sd[m]) for m in (fmask(fi, F, di, sm, sd) for fi in range(len(FILTERS)))])
    print(u, "signals", sum(len(c[0][0]) for c in cands), f"{time.time() - t0:.0f}s", flush=True)
    acc = SpaAcc(len(cal))
    rows, hold, trades = [], [], []
    for ki, money in enumerate(MONEY):
        if want is not None and not any((v // (len(SIGS) * len(FILTERS) * len(EXN) * 3)) == ui * NK + ki for v in want):
            continue
        T = table(u, money, tdays)
        print(u, money, "table", f"{time.time() - t0:.0f}s", flush=True)
        for s in range(len(SIGS)):
            for fi in range(len(FILTERS)):
                td, mi, sd = cands[s][fi]
                for ei, en in enumerate(EXN):
                    r, g, x = T[en]
                    sdi = (sd > 0).astype(np.int64)
                    v = ~np.isnan(r[td, mi, sdi])
                    a, b_, c_ = td[v], mi[v], sd[v]
                    idx, rn = rounds(a, b_, c_, x)
                    tdd, mii, ssi = a[idx], b_[idx], (c_[idx] > 0).astype(np.int64)
                    rv = r[tdd, mii, ssi].astype(np.float64)
                    gv = g[tdd, mii, ssi].astype(np.float64)
                    yr = t_year[tdd]
                    pre = yr >= 0
                    for mdi, md in enumerate(MDS):
                        vid = ((((ui * NK + ki) * len(SIGS) + s) * len(FILTERS) + fi) * len(EXN) + ei) * 3 + mdi
                        k = rn < md
                        if want is not None:
                            if vid in want:
                                trades.append(pd.DataFrame(dict(
                                    vid=vid, day=[tdays[i] for i in tdd[k]], sig_min=mii[k] + SM0, side=c_[idx][k],
                                    net=rv[k], gross=gv[k], prem=T["prem"][tdd[k], mii[k], ssi[k]])))
                            continue
                        kp = k & pre
                        ny = np.bincount(yr[kp], weights=rv[kp], minlength=6)
                        cy = np.bincount(yr[kp], minlength=6)
                        gp = gv[kp].sum()
                        kh = k & ~pre
                        rows.append((vid, *ny, *cy, gp))
                        hold.append((vid, rv[kh].sum(), kh.sum(), gv[kh].sum()))
                        cc = t_cal[tdd[kp]]
                        ok = cc >= 0
                        acc.add(np.bincount(cc[ok], weights=rv[kp][ok], minlength=len(cal)))
        del T
        print(u, money, "evaluated", len(rows), f"{time.time() - t0:.0f}s", flush=True)
    if want is not None:
        return pd.concat(trades) if trades else pd.DataFrame()
    acc.flush()
    R = np.array(rows, dtype=np.float64)
    np.savez_compressed(os.path.join(OUT, f"res_{u}.npz"), vid=R[:, 0].astype(np.int64),
                        net=R[:, 1:7].astype(np.float32), n=R[:, 7:13].astype(np.int32), gross=R[:, 13].astype(np.float32))
    Hh = np.array(hold, dtype=np.float64)
    np.savez_compressed(os.path.join(OUT, f"hold_{u}.npz"), vid=Hh[:, 0].astype(np.int64), net=Hh[:, 1], n=Hh[:, 2],
                        gross=Hh[:, 3])
    np.savez_compressed(os.path.join(OUT, f"spa_{u}.npz"), rc_obs=acc.rc_obs, rc_b=acc.rc_b, spa_obs=acc.spa_obs,
                        spa_b=acc.spa_b, n=acc.n, T=acc.T)
    print(u, "done", len(rows), "variants", f"{time.time() - t0:.0f}s", flush=True)


def decode(vid):
    mdi = vid % 3; vid //= 3
    ei = vid % len(EXN); vid //= len(EXN)
    fi = vid % len(FILTERS); vid //= len(FILTERS)
    s = vid % len(SIGS); vid //= len(SIGS)
    ki = vid % NK; ui = vid // NK
    fam, p = SIGS[s]
    return dict(und=UNDS[ui], strike=KNAME[ki], fam=fam, params=p, filter=FILTERS[fi],
                exit=EXN[ei], maxday=MDS[mdi])


def family_of(vid):
    return SIGS[(vid // (3 * len(EXN) * len(FILTERS))) % len(SIGS)][0]


def final():
    parts = [np.load(os.path.join(OUT, f"res_{u}.npz")) for u in UNDS if os.path.exists(os.path.join(OUT, f"res_{u}.npz"))]
    vid = np.concatenate([p["vid"] for p in parts])
    net = np.concatenate([p["net"] for p in parts]).astype(np.float64)
    n = np.concatenate([p["n"] for p in parts])
    gross = np.concatenate([p["gross"] for p in parts])
    fam = np.array([SIGS[(v // (3 * len(EXN) * len(FILTERS))) % len(SIGS)][0] for v in vid])
    nifty = market().index("NIFTY").days
    ndays = np.array([sum(1 for d in nifty if d.year == y and d < HOLD) for y in YEARS])
    out = {"n_variants": int(len(vid)), "families": {}}
    # SPA over everything
    sp = [np.load(os.path.join(OUT, f"spa_{u}.npz")) for u in UNDS if os.path.exists(os.path.join(OUT, f"spa_{u}.npz"))]
    rc_obs = max(float(s["rc_obs"]) for s in sp)
    rc_b = np.max(np.stack([s["rc_b"] for s in sp]), axis=0)
    spa_obs = max(float(s["spa_obs"]) for s in sp)
    spa_b = np.max(np.stack([s["spa_b"] for s in sp]), axis=0)
    out["spa"] = dict(n=int(sum(int(s["n"]) for s in sp)), rc_p=float((rc_b >= rc_obs).mean()),
                      spa_p=float((np.maximum(spa_b, 0) >= max(spa_obs, 0)).mean()))
    rows = []
    for f in ("ema", "bb", "orb", "pe"):
        m = fam == f
        fv, fn, fnet = vid[m], n[m], net[m]
        wf = {}
        for ti, Y in enumerate(YEARS):
            if Y < 2022:
                continue
            trn = fnet[:, :ti].sum(axis=1)
            tcnt = fn[:, :ti].sum(axis=1)
            elig = tcnt >= 30
            if not elig.any():
                continue
            j = np.argmax(np.where(elig, trn, -np.inf))
            wf[Y] = dict(vid=int(fv[j]), train_net=float(trn[j]), test_net=float(fnet[j, ti]), test_n=int(fn[j, ti]),
                         test_rs_day=float(fnet[j, ti] / ndays[ti]), **decode(int(fv[j])))
        tot = fnet.sum(axis=1)
        elig = fn.sum(axis=1) >= 30
        j = np.argmax(np.where(elig, tot, -np.inf))
        best = dict(vid=int(fv[j]), pre_net=float(tot[j]), pre_n=int(fn[j].sum()), per_year=fnet[j].round(0).tolist(),
                    pre_rs_day=float(tot[j] / ndays.sum()), **decode(int(fv[j])))
        # in-sample performance of the per-year winners in their test years vs in-sample best in those years
        is_years = {Y: float(fnet[j, YEARS.index(Y)]) for Y in wf}
        out["families"][f] = dict(n_variants=int(m.sum()), share_pos=float((tot > 0).mean()),
                                  median_rs_day=float(np.median(tot) / ndays.sum()), wf=wf, best=best, best_in_wf_years=is_years,
                                  wf_total=float(sum(w["test_net"] for w in wf.values())),
                                  wf_rs_day=float(sum(w["test_net"] for w in wf.values()) / sum(ndays[YEARS.index(Y)] for Y in wf)))
        print(f, json.dumps(out["families"][f], default=str)[:3000], flush=True)
    # the report's 5th family (ORB30) is part of 'orb'; also give the ORB-30 subfamily its own WF
    with open(os.path.join(OUT, "final.json"), "w") as fh:
        json.dump(out, fh, indent=1, default=str)
    print("SPA", out["spa"])


def detail():
    fin = json.load(open(os.path.join(OUT, "final.json")))
    want = {}
    for f, d in fin["families"].items():
        want[d["best"]["vid"]] = f"{f}:best"
    allt = []
    for u in UNDS:
        ws = {v: t for v, t in want.items() if decode(v)["und"] == u}
        if not ws:
            continue
        tr = build(u, want=set(ws))
        if len(tr):
            tr["tag"] = tr.vid.map(ws)
            allt.append(tr)
    t = pd.concat(allt)
    t.to_csv(os.path.join(OUT, "detail_trades.csv.gz"), index=False)
    print("detail trades", len(t))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "build":
        for u in sys.argv[2:]:
            build(u)
    elif cmd == "final":
        final()
    else:
        detail()
