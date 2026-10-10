"""h30 signals on the premium's own chart (see PREREG.md). Works on dense (N, 375) 1-minute arrays.

defs(): the 54 pre-registered signal definitions. entries(P, meta, d) -> (N,) entry column (first signal; -1 = none).
"""
import numpy as np

W = 375
E_LO, E_HI = 5, 315          # entry minute 09:20 .. 14:30 (columns from 09:15)


def defs():
    out = []
    for n, tfs in ((5, (1, 5)), (15, (1, 3, 5, 15)), (30, (1, 3, 5, 15))):
        for tf in tfs:
            out.append(("ORB%d" % n, tf))
    for fam in ("PDH", "VWAPRECL", "VWAPHOLD", "EMAX", "EMAPB", "HHHL", "VOLSPK", "STRADBRK", "ROUND", "SWINGHI",
                "OPPCOLL"):
        for tf in (1, 3, 5, 15):
            out.append((fam, tf))
    return out


def ffill(a):
    """Forward-fill NaNs along axis 1 (leading NaNs stay)."""
    n, w = a.shape
    idx = np.where(~np.isnan(a), np.arange(w)[None, :], 0)
    np.maximum.accumulate(idx, axis=1, out=idx)
    out = a[np.arange(n)[:, None], idx]
    return out


def bfill_first(a):
    ok = ~np.isnan(a)
    j = np.where(ok.any(axis=1), ok.argmax(axis=1), 0)
    return a[np.arange(len(a)), j]


class Day:
    """Per-row 1-minute derived arrays shared by all definitions."""

    def __init__(self, P):
        O, H, L, C, V = (P[:, i, :].astype(np.float64) for i in range(5))
        self.O, self.H, self.L, self.C = O, H, L, C
        self.V = np.nan_to_num(V)
        self.Cf = ffill(C)
        self.open0 = bfill_first(O)
        self.Cf = np.where(np.isnan(self.Cf), self.open0[:, None], self.Cf)
        tp = np.where(np.isnan(C), 0.0, (H + L + C) / 3.0)
        cv = np.cumsum(tp * self.V, axis=1)
        cvol = np.cumsum(self.V, axis=1)
        with np.errstate(invalid="ignore", divide="ignore"):
            vw = np.where(cvol > 0, cv / cvol, np.nan)
        self.vwap = np.where(np.isnan(vw), self.Cf, vw)
        self._tf = {}

    def candles(self, tf):
        if tf not in self._tf:
            n = len(self.C)
            nb = W // tf
            r = lambda a: a.reshape(n, nb, tf)  # noqa: E731
            with np.errstate(all="ignore"):
                import warnings
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore")
                    h = np.nanmax(r(self.H), axis=2)
                    lo = np.nanmin(r(self.L), axis=2)
            o = bfill_first_blocks(r(self.O))
            c = self.Cf[:, tf - 1::tf]
            v = r(self.V).sum(axis=2)
            # empty candles: flat at the last close
            o = np.where(np.isnan(o), c, o)
            h = np.where(np.isnan(h), c, h)
            lo = np.where(np.isnan(lo), c, lo)
            cprev = np.concatenate([self.open0[:, None], c[:, :-1]], axis=1)
            vw = self.vwap[:, tf - 1::tf]
            ccol = np.arange(nb) * tf + tf - 1              # closing minute column of each candle
            self._tf[tf] = dict(o=o, h=h, l=lo, c=c, v=v, cp=cprev, vw=vw, ccol=ccol, nb=nb)
        return self._tf[tf]


def bfill_first_blocks(a):
    ok = ~np.isnan(a)
    j = np.where(ok.any(axis=2), ok.argmax(axis=2), 0)
    n, nb, _ = a.shape
    return a[np.arange(n)[:, None], np.arange(nb)[None, :], j]


def ema(c, span):
    a = 2.0 / (span + 1)
    out = np.empty_like(c)
    out[:, 0] = c[:, 0]
    for t in range(1, c.shape[1]):
        out[:, t] = a * c[:, t] + (1 - a) * out[:, t - 1]
    return out


def swings(h, lo):
    """Latest and previous confirmed swing highs/lows per candle (2-candle fractals, confirmed 2 candles later)."""
    n, nb = h.shape
    sh1 = np.full((n, nb), np.nan); sh0 = np.full((n, nb), np.nan)   # noqa: E702
    sl1 = np.full((n, nb), np.nan); sl0 = np.full((n, nb), np.nan)   # noqa: E702
    a1 = np.full(n, np.nan); a0 = np.full(n, np.nan); b1 = np.full(n, np.nan); b0 = np.full(n, np.nan)  # noqa: E702
    for t in range(nb):
        j = t - 2
        if j >= 2:
            ish = (h[:, j] > h[:, j - 1]) & (h[:, j] > h[:, j - 2]) & (h[:, j] >= h[:, j + 1]) & (h[:, j] >= h[:, j + 2])
            isl = (lo[:, j] < lo[:, j - 1]) & (lo[:, j] < lo[:, j - 2]) & (lo[:, j] <= lo[:, j + 1]) & (lo[:, j] <= lo[:, j + 2])
            a0 = np.where(ish, a1, a0); a1 = np.where(ish, h[:, j], a1)    # noqa: E702
            b0 = np.where(isl, b1, b0); b1 = np.where(isl, lo[:, j], b1)   # noqa: E702
        sh1[:, t], sh0[:, t], sl1[:, t], sl0[:, t] = a1, a0, b1, b0
    return sh1, sh0, sl1, sl0


def cross_up(c, cp, lvl):
    return (c > lvl) & (cp <= lvl)


def entries(D: Day, meta, fam, tf):
    """First eligible signal -> entry column (next minute after the candle's close); -1 if none."""
    k = D.candles(tf)
    c, cp, o, h, lo, v, vw, nb = k["c"], k["cp"], k["o"], k["h"], k["l"], k["v"], k["vw"], k["nb"]
    n = len(c)
    t_idx = np.arange(nb)[None, :]
    green = c > o
    if fam.startswith("ORB"):
        N = int(fam[3:])
        orh = np.nanmax(np.where(np.isnan(D.H[:, :N]), -np.inf, D.H[:, :N]), axis=1)[:, None]
        S = cross_up(c, cp, orh) & (t_idx * tf >= N)
    elif fam == "PDH":
        S = cross_up(c, cp, meta.pdh.values[:, None])
    elif fam == "VWAPRECL":
        vwp = np.concatenate([vw[:, :1], vw[:, :-1]], axis=1)
        S = (cp < vwp) & (c > vw) & (t_idx >= 1)
    elif fam == "VWAPHOLD":
        a = c > vw
        S = a & np.roll(a, 1, axis=1) & np.roll(a, 2, axis=1) & green & (t_idx >= 2)
    elif fam in ("EMAX", "EMAPB"):
        e9, e20 = ema(c, 9), ema(c, 20)
        up = e9 > e20
        if fam == "EMAX":
            S = up & ~np.roll(up, 1, axis=1) & (t_idx >= 8)
        else:
            S = up & np.roll(up, 1, axis=1) & np.roll(up, 2, axis=1) & (lo <= e9) & (c > e9) & green & (t_idx >= 10)
    elif fam in ("HHHL", "SWINGHI"):
        sh1, sh0, sl1, sl0 = swings(h, lo)
        S = cross_up(c, cp, sh1)
        if fam == "HHHL":
            S &= (sh1 > sh0) & (sl1 > sl0)
    elif fam == "VOLSPK":
        cs = np.concatenate([np.zeros((n, 1)), np.cumsum(v, axis=1)], axis=1)
        lo_i = np.maximum(np.arange(nb) - 10, 0)
        cnt = np.arange(nb) - lo_i
        with np.errstate(invalid="ignore", divide="ignore"):
            mean = (cs[:, np.arange(nb)] - cs[:, lo_i]) / np.maximum(cnt, 1)[None, :]
        S = (v >= 3 * mean) & (mean > 0) & green & (cnt[None, :] >= 5)
    elif fam == "STRADBRK":
        pair = meta.pair.values
        okp = pair >= 0
        L = max(30 // tf, 2)
        cpair = c[np.where(okp, pair, 0)]
        lagc = np.concatenate([np.full((n, L), np.nan), c[:, :-L]], axis=1)
        lagp = np.concatenate([np.full((n, L), np.nan), cpair[:, :-L]], axis=1)
        both = (c < lagc) & (cpair < lagp) & okp[:, None]
        prevboth = np.concatenate([np.zeros((n, 1), bool), both[:, :-1]], axis=1)
        hh = np.full((n, nb), np.nan)
        for t in range(L, nb):
            hh[:, t] = h[:, t - L:t].max(axis=1)
        S = prevboth & (c > hh)
    elif fam == "ROUND":
        p0 = D.open0
        s = np.select([p0 < 50, p0 < 150, p0 < 400, p0 < 1000], [5, 10, 25, 50], 100).astype(float)[:, None]
        m = np.floor(c / s) * s
        S = (m > cp) & (m > p0[:, None]) & (c >= m)
    elif fam == "OPPCOLL":
        pair = meta.pair.values
        okp = pair >= 0
        priorhi = np.maximum.accumulate(np.concatenate([np.full((n, 1), -np.inf), h[:, :-1]], axis=1), axis=1)
        Cp = D.Cf[np.where(okp, pair, 0)]
        ccol = k["ccol"]
        now = Cp[:, ccol]
        then = Cp[:, np.maximum(ccol - 15, 0)]
        S = (c > priorhi) & (now <= 0.9 * then) & (ccol[None, :] >= 15) & okp[:, None] & (t_idx >= 1)
    else:
        raise ValueError(fam)
    ent = (np.arange(nb) + 1) * tf
    S = S & (ent >= E_LO)[None, :] & (ent <= E_HI)[None, :]
    S = np.nan_to_num(S).astype(bool)
    has = S.any(axis=1)
    first = S.argmax(axis=1)
    return np.where(has, ent[first], -1)
