"""h47 shared code: bars, lines, sessions, features, the C state machine, costs, option model, random baseline."""
from __future__ import annotations

import ctypes
import json
import math
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy.special import ndtr  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h47"
DATA = os.path.join(SCR, "data")
HERE = os.path.dirname(os.path.abspath(__file__))
COINS = ("BTC", "ETH")
SYM = {"BTC": "BTCUSDT", "ETH": "ETHUSDT"}
TFS = (1, 2, 3, 5)
ANCHORS = ("U", "I", "E")
FX = 96.78
TAX = 0.312
FEE_T = 0.0005 * 1.18
FEE_M = 0.0002 * 1.18
CV = {"BTC": 0.001, "ETH": 0.01}
KSTEP = {"BTC": 200.0, "ETH": 20.0}
NOTIONAL = 1e5
IS_END = int(pd.Timestamp("2026-04-01").value // 60_000_000_000)   # minute index of holdout start
DAY0 = int(pd.Timestamp("2020-01-01").value // 60_000_000_000)


# ---------------- C core ----------------
class Params(ctypes.Structure):
    _fields_ = [("setup", ctypes.c_int), ("slope", ctypes.c_int), ("volf", ctypes.c_int), ("hold_bars", ctypes.c_int),
                ("hours", ctypes.c_int), ("skip", ctypes.c_int), ("window", ctypes.c_int), ("k", ctypes.c_double),
                ("f30", ctypes.c_double), ("e15", ctypes.c_double), ("d80", ctypes.c_double), ("f60", ctypes.c_double)]


_so = os.path.join(SCR, "core.so")
if not os.path.exists(_so) or os.path.getmtime(_so) < os.path.getmtime(os.path.join(HERE, "core.c")):
    os.system(f"gcc -O2 -shared -fPIC -o {_so} {os.path.join(HERE, 'core.c')}")
_lib = ctypes.CDLL(_so)
_D = np.ctypeslib.ndpointer(np.float64, flags="C")
_I = np.ctypeslib.ndpointer(np.int32, flags="C")
_lib.run.restype = ctypes.c_int
_lib.run.argtypes = [ctypes.c_int] + [_D] * 7 + [_I] * 4 + [_D] * 6 + [Params, ctypes.c_int] + [_I] * 4 + [_D] * 2 + [_I]


# ---------------- data ----------------
def load_1m(coin):
    k = pd.read_parquet(os.path.join(DATA, f"k_{SYM[coin]}.parquet"))
    return k


def daily_atr(k):
    day = k.t.values // 1440
    g = pd.DataFrame({"d": day, "h": k.h.values, "l": k.l.values, "c": k.c.values}).groupby("d").agg(
        h=("h", "max"), l=("l", "min"), c=("c", "last"))
    pc = g.c.shift(1)
    tr = np.maximum(g.h, pc) - np.minimum(g.l, pc)
    tr = tr.fillna(g.h - g.l)
    atr = tr.rolling(14, min_periods=5).mean().shift(1)     # through the previous day
    return pd.Series(atr.values, index=g.index)


def bars(k, N):
    g = (k.t.values // N).astype(np.int64)
    df = pd.DataFrame({"g": g, "o": k.o.values.astype(np.float64), "h": k.h.values.astype(np.float64),
                       "l": k.l.values.astype(np.float64), "c": k.c.values.astype(np.float64),
                       "v": k.v.values.astype(np.float64)})
    b = df.groupby("g", sort=True).agg(o=("o", "first"), h=("h", "max"), l=("l", "min"), c=("c", "last"), v=("v", "sum"))
    b["t"] = b.index.values * N                       # bar start minute (UTC epoch minutes)
    return b.reset_index(drop=True)


def ema9(c):
    a = 2 / 10
    out = np.empty_like(c)
    # exact recursive EMA via pandas (adjust=False)
    out[:] = pd.Series(c).ewm(alpha=a, adjust=False).mean().values
    return out


def sessions(t_start, N, anchor):
    """Per bar: session id (or -1), minutes from anchor to bar close, last-bar-of-session flag."""
    tc = t_start + N                                   # bar close minute
    if anchor in ("U", "I"):
        off = 0 if anchor == "U" else 1110             # 18:30 UTC
        sid = (t_start - off) // 1440
        msa = (tc - off) - sid * 1440
        sess = sid.astype(np.int64)
    else:
        ts = pd.to_datetime(t_start * 60, unit="s", utc=True).tz_convert("America/New_York")
        mod = (ts.hour * 60 + ts.minute).values
        wd = ts.weekday.values
        ins = (mod >= 570) & (mod < 960) & (wd < 5)
        dayn = (ts.normalize().tz_localize(None).values.astype("datetime64[D]").astype(np.int64))
        sess = np.where(ins, dayn, -1)
        msa = np.where(ins, mod + N - 570, -1)
    sess = sess.astype(np.int32)
    send = np.zeros(len(sess), np.int32)
    nxt = np.r_[sess[1:], -9]
    send[(sess >= 0) & (nxt != sess)] = 1
    return sess, msa.astype(np.int32), send


def et_ok(t_start, N):
    ts = pd.to_datetime((t_start + N) * 60, unit="s", utc=True).tz_convert("America/New_York")
    mod = (ts.hour * 60 + ts.minute).values
    return ((mod >= 600) & (mod <= 810)).astype(np.int32)


def build(coin, N, anchor, k=None, atr_d=None):
    """All arrays for one bar series and anchor."""
    if k is None:
        k = load_1m(coin)
    if atr_d is None:
        atr_d = daily_atr(k)
    b = bars(k, N)
    t = b.t.values.astype(np.int64)
    o, h, l, c, v = (b[x].values for x in "ohlcv")
    e = ema9(c)
    sess, msa, send = sessions(t, N, anchor)
    hlc3 = (h + l + c) / 3
    vv = np.where(sess >= 0, v, 0.0)
    pv = hlc3 * vv
    # cumulative within session
    key = pd.Series(sess)
    cpv = pd.Series(pv).groupby(key).cumsum().values
    cv = pd.Series(vv).groupby(key).cumsum().values
    with np.errstate(invalid="ignore", divide="ignore"):
        vw = np.where(cv > 0, cpv / np.maximum(cv, 1e-12), np.nan)
    # sessions whose first bars have zero volume: fall back to hlc3
    vw = np.where((sess >= 0) & ~np.isfinite(vw), hlc3, vw)
    vw = np.where(sess >= 0, vw, np.nan)
    first = np.r_[True, sess[1:] != sess[:-1]]
    sopen = pd.Series(np.where(first, o, np.nan)).ffill().values
    # vwap change over 30/60 min (clamped to the session start)
    idx = np.arange(len(t))
    sstart = pd.Series(np.where(first, idx, np.nan)).ffill().values.astype(np.int64)
    def lagged(x, m):
        j = np.maximum(idx - int(math.ceil(m / N)), sstart)
        return x - x[j]
    dv30 = lagged(vw, 30)
    dv60 = lagged(vw, 60)
    w = int(math.ceil(15 / N))
    er = pd.Series(e).rolling(w, min_periods=1)
    erng = (er.max() - er.min()).values
    vavg = pd.Series(v).shift(1).rolling(20, min_periods=20).mean().values
    atr = atr_d.reindex(t // 1440).values
    atr = np.where(np.isfinite(atr), atr, np.inf)      # no ATR yet: k x inf blocks C, thresholds inert
    vw0 = np.where(np.isfinite(vw), vw, 0.0)
    return dict(t=t, o=o, h=h, l=l, c=c, v=v, ema=e, vw=vw0, vwnan=vw, sess=sess, msa=msa, send=send,
                etok=et_ok(t, N), atr=atr, sopen=np.nan_to_num(sopen), dv30=np.nan_to_num(dv30),
                dv60=np.nan_to_num(dv60), erng=erng, vavg=np.nan_to_num(vavg, nan=np.inf), N=N)


def thresholds(A):
    """Percentile thresholds from in-sample bars (features only, no P&L)."""
    m = (A["sess"] >= 0) & (A["t"] < IS_END) & np.isfinite(A["atr"]) & (A["msa"] >= 30)
    m60 = m & (A["msa"] >= 60)
    atr = A["atr"]
    return dict(f30=float(np.percentile(np.abs(A["dv30"][m]) / atr[m], 33)),
                e15=float(np.percentile(A["erng"][m] / atr[m], 20)),
                d80=float(np.percentile(np.abs(A["ema"][m] - A["vw"][m]) / atr[m], 80)),
                f60=float(np.percentile(np.abs(A["dv60"][m60]) / atr[m60], 33)))


def run(A, P, cap=2_000_000):
    n = len(A["t"])
    outs = [np.empty(cap, np.int32) for _ in range(4)] + [np.empty(cap, np.float64) for _ in range(2)] + [np.empty(cap, np.int32)]
    nt = _lib.run(n, A["o"], A["h"], A["l"], A["c"], A["v"], A["ema"], A["vw"], A["sess"], A["msa"], A["send"], A["etok"],
                  A["atr"], A["sopen"], A["dv30"], A["dv60"], A["erng"], A["vavg"], P, cap, *outs)
    if nt < 0:
        raise RuntimeError("cap")
    sig, ef, xf, side, ep, xp, typ = (x[:nt].copy() for x in outs)
    t = A["t"]
    N = A["N"]
    te = t[ef]
    tx = np.where(typ == 1, t[xf] + N // 2, t[xf])     # VWAP touch: mid-bar
    # maker fill: limit at the signal close, filled if the entry bar trades strictly through it
    lim = A["c"][sig]
    mk = np.where(side > 0, A["l"][ef] < lim, A["h"][ef] > lim)
    return pd.DataFrame(dict(te=te, tx=tx, side=side.astype(np.int8), ep=ep, xp=xp, typ=typ.astype(np.int8),
                             lim=lim, mk=mk))


# ---------------- costs ----------------
class Market:
    """Funding, half-spreads, DVOL, Delta IV ratios, 1m opens (for the random baseline)."""

    def __init__(self, coin, k=None):
        self.coin = coin
        fu = pd.read_parquet(os.path.join(DATA, "funding.parquet"))
        fu = fu[fu.sym == SYM[coin]].sort_values("ts")
        self.fts = (fu.ts.values // 60000).astype(np.int64)       # minutes
        self.fcs = np.r_[0.0, np.cumsum(fu.rate.astype(float).values)]
        dv = pd.read_parquet(os.path.join(DATA, "dvol.parquet"))
        dv = dv[dv.cur == coin].sort_values("ts")
        self.dts = (dv.ts.values // 60000).astype(np.int64) + 60    # value known at the hour's end
        self.dvc = dv.c.astype(float).values / 100
        cal = json.load(open(os.path.join(SCR, "delta_cal.json")))
        self.hs = cal["hs"][coin]
        self.ra, self.rb = cal["ratio"][coin]
        if k is None:
            k = load_1m(coin)
        self.t0 = int(k.t.values[0])
        op = np.full(int(k.t.values[-1]) - self.t0 + 1, np.nan)
        op[k.t.values - self.t0] = k.o.values
        self.op = pd.Series(op).ffill().values

    def funding(self, te, tx, side):
        """Return units: funding paid (negative = cost) by a position of `side` from te to tx (minutes)."""
        s = self.fcs[np.searchsorted(self.fts, tx, side="right")] - self.fcs[np.searchsorted(self.fts, te, side="right")]
        return -side * s

    def dvol(self, tm):
        j = np.searchsorted(self.dts, tm, side="right") - 1
        out = np.where(j >= 0, self.dvc[np.maximum(j, 0)], np.nan)
        return out

    def fut(self, tr, maker=False):
        side = tr.side.values.astype(float)
        ep = np.where(maker, tr.lim.values, tr.ep.values) if maker else tr.ep.values
        xp = tr.xp.values
        g = side * (xp / ep - 1)
        fee = (FEE_M if maker else FEE_T + self.hs) + (FEE_T + self.hs) * xp / ep
        fund = self.funding(tr.te.values, tr.tx.values, side)
        return g, g - fee + fund

    def opt(self, te, tx, side, S0, S1):
        """Gross (mid) and net (ask in, bid out, Delta fee) option P&L in units of the underlying notional at entry."""
        call = side > 0
        texp = (np.floor((te / 60 - 12) / 24) * 24 + 12) * 60          # 12:00 UTC expiries (minutes)
        texp = np.where(texp - te < 360, texp + 1440, texp)
        texp = np.where(texp - te < 360, texp + 1440, texp)
        T0 = (texp - te) / (365 * 1440)
        T1 = np.maximum(texp - tx, 0) / (365 * 1440)
        K = np.round(S0 / KSTEP[self.coin]) * KSTEP[self.coin]
        v0, v1 = self.dvol(te), self.dvol(tx)
        pa = bs(S0, K, T0, v0 * self.ra, call)
        pb = bs(S1, K, T1, v1 * self.rb, call)
        pm0 = bs(S0, K, T0, v0 * (self.ra + self.rb) / 2, call)
        pm1 = bs(S1, K, T1, v1 * (self.ra + self.rb) / 2, call)
        fee = (np.minimum(1e-4 * S0, 0.035 * pa) + np.minimum(1e-4 * S1, 0.035 * pb)) * 1.18
        return (pm1 - pm0) / S0, (pb - pa - fee) / S0, pa / S0


def bs(S, K, T, v, call):
    v = np.maximum(v, 1e-4)
    T = np.maximum(T, 1e-7)
    d1 = (np.log(S / K) + 0.5 * v * v * T) / (v * np.sqrt(T))
    d2 = d1 - v * np.sqrt(T)
    intr = np.where(call, np.maximum(S - K, 0), np.maximum(K - S, 0))
    p = np.where(call, S * ndtr(d1) - K * ndtr(d2), K * ndtr(-d2) - S * ndtr(-d1))
    return np.where(T <= 1e-7, intr, p)


# ---------------- random baseline ----------------
def random_draws(M, te, tx, side, B, rng, weekday_match=True):
    """For each trade, B random other days of the same year (same weekday/weekend class), same UTC minute and hold.
    Returns entry and exit minute arrays (n, B)."""
    n = len(te)
    day = te // 1440
    mod = te - day * 1440
    hold = tx - te
    yr = pd.to_datetime(day, unit="D").year.values
    out_e = np.empty((n, B), np.int64)
    dlo = M.t0 // 1440 + 1
    dhi = (M.t0 + len(M.op)) // 1440 - 2
    alld = np.arange(dlo, dhi + 1)
    yrs = pd.to_datetime(alld, unit="D").year.values
    wk = pd.to_datetime(alld, unit="D").weekday.values >= 5
    trade_wk = pd.to_datetime(day, unit="D").weekday.values >= 5
    for y in np.unique(yr):
        for w in (False, True):
            m = (yr == y) & (trade_wk == w)
            if not m.any():
                continue
            pool = alld[(yrs == y) & ((wk == w) if weekday_match else True)]
            if len(pool) < 2:
                pool = alld[yrs == y]
            pick = pool[rng.integers(0, len(pool), size=(m.sum(), B))]
            same = pick == day[m][:, None]
            pick = np.where(same, pool[(np.searchsorted(pool, pick) + 1) % len(pool)], pick)
            out_e[m] = pick * 1440 + mod[m][:, None]
    out_x = out_e + hold[:, None]
    lim = M.t0 + len(M.op) - 1
    out_x = np.minimum(out_x, lim)
    return out_e, out_x


def price_at(M, tm):
    return M.op[np.clip(tm - M.t0, 0, len(M.op) - 1)]
