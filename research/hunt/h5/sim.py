"""h5 step 2: features at each decision minute + pyramiding simulations for every (und, day, T, dir, X, Y, TS).
Output: feats.pkl (one row per und/day/T) and sims.pkl (one row per und/day/T/dir/X/Y/TS with per-lot gross/net)."""
import os, sys, pickle
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pandas as pd
from obuy import config as C
from obuy.costs import Costs, adverse_bps

OUT = os.path.join(C.SCRATCH, "hunt", "h5")
DEC = [600, 630, 660, 690]
XS, YS, TSS = (0.15, 0.25, 0.35), (0.25, 0.4, 0.6), (0, 60)
SQ = 360                                   # 15:15 column
SPREAD = {"NIFTY": 0.5, "BANKNIFTY": 1.0}  # half-spread Rs per unit per side (realistic ATM/1-ITM)
COST = Costs("app")
MAXL = 4


def load(u):
    with open(os.path.join(OUT, f"day_{u}.pkl"), "rb") as f:
        return pickle.load(f)


def features(u, P, br):
    days, rec, daily = P["days"], P["rec"], P["daily"]
    dl = daily[daily.real].copy()
    tr = np.maximum(dl.high - dl.low, np.maximum(abs(dl.high - dl.close.shift()), abs(dl.low - dl.close.shift())))
    dl["atr"] = tr.rolling(14).mean().shift(1)
    dl["pc"] = dl.close.shift(1)
    rg = dl.high - dl.low
    dl["nr4"] = (rg == rg.rolling(4).min()).shift(1).fillna(False)
    dl["nr7"] = (rg == rg.rolling(7).min()).shift(1).fillna(False)
    vixd = P["vixd"].close
    rows = []
    hist = {T: [] for T in DEC}
    for d in days:
        r = rec[d]
        if d not in dl.index or np.isnan(dl.at[d, "atr"]):
            continue
        A, pc = dl.at[d, "atr"], dl.at[d, "pc"]
        o0 = r["io"][0]
        vi = vixd.index.searchsorted(d) - 1
        vpc = float(vixd.iloc[vi]) if vi >= 0 else np.nan
        # outcome labels (not features)
        cE = r["ic"][SQ - 1]
        dh, dlo = r["ih"][:SQ].max(), r["il"][:SQ].min()
        for T in DEC:
            t = T - C.OPEN_M
            c = r["ic"][t]
            hi, lo = r["ih"][:t + 1].max(), r["il"][:t + 1].min()
            dr = 1 if c >= o0 else -1
            rs = hi - lo
            prev = hist[T][-20:]
            rngr = rs / np.mean(prev) if len(prev) >= 10 else np.nan
            hist[T].append(rs)
            gap = o0 - pc
            gap_unf = bool(np.sign(gap) == dr and abs(gap) > 0.1 * A and ((dr > 0 and lo > pc) or (dr < 0 and hi < pc)))
            loc = (c - lo) / rs if rs > 0 else 0.5
            if dr < 0:
                loc = 1 - loc
            vchg = (r["vix"][t] / vpc - 1) if (r["vix"] is not None and vpc > 0) else np.nan
            f = r["feats"][T]
            b = br.get((d, T))
            bro = np.nan if b is None else (b[0] if dr > 0 else 1 - b[0])
            rows.append(dict(und=u, day=d, T=T, dir=dr, atr=A, exp=r["exp"], series=r["series"],
                             drive=abs(c - o0) / A, drive_pc=(c - pc) * dr / A, rngr=rngr, gap_unf=gap_unf, loc=loc,
                             vchg=vchg, st_ratio=f["st_ratio"], oi_al=f["oi_imb"] * dr, breadth=bro,
                             nr4=bool(dl.at[d, "nr4"]), nr7=bool(dl.at[d, "nr7"]),
                             cont=(cE - c) * dr / A, mfe=((r["ih"][t:SQ].max() - c) if dr > 0 else (c - r["il"][t:SQ].min())) / A,
                             day_rng=(dh - dlo) / A, trend=bool(abs(cE - o0) / max(dh - dlo, 1e-9) >= 0.6 and (dh - dlo) >= 1.0 * A)))
    return pd.DataFrame(rows)


class Opt:
    def __init__(self, r):
        self.r, self.cache = r, {}

    def px(self, k, right):
        key = (k, right)
        if key not in self.cache:
            r = self.r
            i = np.searchsorted(r["K"], k)
            if i >= len(r["K"]) or r["K"][i] != k:
                self.cache[key] = None
            else:
                o = (r["Co"] if right == "C" else r["Po"])[i].astype(np.float64)
                c = (r["Cc"] if right == "C" else r["Pc"])[i].astype(np.float64)
                cf = pd.Series(c).ffill().values
                fill = np.where(np.isnan(o), np.r_[np.nan, cf[:-1]], o)    # open, else last close
                self.cache[key] = np.round(fill, 2)
        return self.cache[key]


def sim_day(u, d, r, A, qty, step, out):
    ic = r["ic"]
    op = Opt(r)
    dn = (d - pd.Timestamp("1970-01-01").date()).days
    spr = SPREAD[u]
    for T in DEC:
        t = T - C.OPEN_M
        for dr in (1, -1):
            fav = (ic[t:SQ] - ic[t]) * dr                  # decisions at cols t..SQ-1
            peak = np.maximum.accumulate(fav)
            for Y in YS:
                hit = np.nonzero((fav[1:] <= peak[1:] - Y * A))[0]
                k_stop = hit[0] + 1 if len(hit) else None
                for TS in TSS:
                    for X in XS:
                        kx = k_stop
                        if TS and t + TS < SQ - 1 and peak[TS] < X * A:
                            kx = TS if kx is None else min(kx, TS)
                        xcol = SQ if kx is None else t + kx + 1
                        entries = [t]
                        for n in range(1, MAXL):
                            a = np.nonzero(fav[1:] >= n * X * A)[0]
                            if len(a) and (kx is None or a[0] + 1 < kx):
                                entries.append(t + a[0] + 1)
                            else:
                                break
                        g = np.full(MAXL, np.nan); nt = np.full(MAXL, np.nan); prem = np.nan
                        for j, ec in enumerate(entries):
                            if ec + 1 >= xcol:
                                break
                            atm = int(round(ic[ec] / step)) * step
                            k, right = (atm - step, "C") if dr > 0 else (atm + step, "P")
                            p = op.px(k, right)
                            if p is None:
                                break
                            bi, so = p[ec + 1], p[xcol]
                            if np.isnan(bi) or np.isnan(so) or bi <= 0:
                                break
                            g[j] = (so - bi) * qty
                            b = float(adverse_bps(bi, 5, True)) + spr
                            s = max(float(adverse_bps(so, 5, False)) - spr, 0.0)
                            ch = COST.charge(True, np.array([b]), np.array([qty]))[0] + COST.charge(False, np.array([s]), np.array([qty]))[0]
                            nt[j] = (s - b) * qty - ch
                            if j == 0:
                                prem = bi
                        out.append((u, d, T, dr, X, Y, TS, xcol, *g, *nt, prem))


def main():
    br = {}
    bp = os.path.join(OUT, "breadth.pkl")
    if os.path.exists(bp):
        b = pd.read_pickle(bp)
        br = {(r.day, r.T): (r.up_open, r.up_pc) for r in b.itertuples()}
    F, S = [], []
    for u in ("NIFTY", "BANKNIFTY"):
        P = load(u)
        f = features(u, P, br)
        F.append(f)
        qty = P["lot_today"]
        atr = f.drop_duplicates("day").set_index("day").atr
        out = []
        for d in atr.index:
            sim_day(u, d, P["rec"][d], atr[d], qty, C.STEP[u], out)
        cols = ["und", "day", "T", "dir", "X", "Y", "TS", "xcol"] + [f"g{i}" for i in range(MAXL)] + [f"n{i}" for i in range(MAXL)] + ["prem"]
        S.append(pd.DataFrame(out, columns=cols))
        print(u, len(f), len(out), "qty", qty, flush=True)
        del P
    pd.concat(F).to_pickle(os.path.join(OUT, "feats.pkl"))
    pd.concat(S).to_pickle(os.path.join(OUT, "sims.pkl"))


if __name__ == "__main__":
    main()
