"""R12 specialists: one small rule per data family. Each returns, per trade and minute (decided on bar k's close, using
data up to k only), an EXIT vote (bool [n, W]), or a LOCK proposal (level from bar k+1 on; NEG none), or an EXTEND vote.
Strength is 1 when a rule fires (binary); committees weight and count them.

Families (task list) and the rule tested for each:
  PA  price action / MFE   no progress: T minutes in, the best high is still under entry x (1 + m)   (MFE time stop)
  PB  price action / MAE   a close <= entry x (1 - a): a tighter, close-based stop (lowers risk only)
  TT  time / theta         clock past tau (expiry day: 60 min earlier) and the premium still under entry x (1 + g)
  VC  volatility trail     chandelier on the premium: peak high - k x option ATR(10), armed after `arm` ATRs of profit
  BE  breakeven move       lock at breakeven + charges once the peak is b x entry up
  SR  parabolic SAR        Wilder's SAR on the option's 1-minute bars from the entry (step s, max 0.2) as the lock
  GB  giveback trail       lock entry + keep x (peak - entry) once the peak is arm x entry up (the current manager's 50%)
  VW  VWAP                 the index closes on the wrong side of the session VWAP (option-volume proxy) by z SD, p bars
  VO  volume climax        option volume >= m x its 30-min mean on a new-high bar that closes in its lower 40% -> exit / lock at its low
  OI  open interest        put-minus-call writing ATM+-2 over 15 min against the trade (z <= -z0)
  VX  VIX / IV             India VIX moves >= x% in 5 min the way that hurts the trade (up for calls, down for puts)
  MP  market profile       the index was outside the developing value area the trade's way and is back inside for p bars
  MO  momentum fade        RSI(14) was beyond 70/30 the trade's way and is back through 50 / ROC(10) against by r
  DP  delta PROXY (thin)   signed option volume (tick rule, r11 'flow') over 10 min against the trade, z <= -z0
Extension agreement votes (EXTEND): VWAP >= 0.5 SD the trade's way, outside value the trade's way, OI agreeing, VIX calm
(|5-min move| < 3%), ROC agreeing, delta proxy agreeing; M0 = the current manager's extension minus its live-only flow
condition (VWAP & value & OI & VIX).
"""
from __future__ import annotations

import itertools

import numpy as np

import lib12 as L

W = L.W
NEG = L.NEG
COLS = np.arange(W)[None, :]


def _after(arm):
    return COLS >= arm.c0[:, None]


def _peak_incl(arm):
    """best option high from the entry bar to k (inclusive)."""
    h = np.where(_after(arm) & ~np.isnan(arm.H), arm.H, NEG)
    return np.maximum(np.maximum.accumulate(h, axis=1), arm.e[:, None])


def _persist(m, p):
    if p <= 1:
        return m
    c = np.zeros(m.shape[0])
    out = np.zeros_like(m)
    for k in range(m.shape[1]):
        c = np.where(m[:, k], c + 1, 0)
        out[:, k] = c >= p
    return out


def _since(m, arm):
    """True from the first k >= c0 where m holds (sticky)."""
    return np.maximum.accumulate(m & _after(arm), axis=1)


def _sd(F, arm):
    return arm.side[:, None].astype(float)


# ---------------------------------------------------------------- exit specialists
def PA(arm, F, T, m):
    held = COLS - arm.c0[:, None] + 1
    best = _peak_incl(arm)
    return _after(arm) & (held >= T) & (best < (arm.e * (1 + m))[:, None])


def PB(arm, F, a):
    return _after(arm) & (arm.Cl <= (arm.e * (1 - a))[:, None])


def TT(arm, F, tau, g):
    t = np.where(F["exp"], tau - 60, tau) - L.C.OPEN_M
    return _after(arm) & (COLS >= t[:, None]) & (arm.Cl < (arm.e * (1 + g))[:, None])


def VW(arm, F, z, p):
    s = _sd(F, arm)
    m = s * (F["c"] - F["vw"]) < -z * F["vsd"]
    return _after(arm) & _persist(np.nan_to_num(m).astype(bool), p)


def VO(arm, F, m):
    pk = _peak_incl(arm)
    prev = np.concatenate([np.full((arm.n, 1), NEG), pk[:, :-1]], axis=1)
    rng = arm.H - arm.L
    pos = np.where(rng > 0, (arm.Cl - arm.L) / np.where(rng > 0, rng, 1), 1.0)
    return _after(arm) & (np.nan_to_num(F["ovz"]) >= m) & (arm.H >= prev) & (pos <= 0.4)


def OI(arm, F, z0):
    return _after(arm) & (np.nan_to_num(_sd(F, arm) * F["oiz"]) <= -z0)


def VX(arm, F, x):
    return _after(arm) & (np.nan_to_num(_sd(F, arm) * -F["vix5"]) <= -x)


def MP(arm, F, which, p):
    hi, lo = (F["vah"], F["val"]) if which == "dev" else (F["pvah"], F["pval"])
    s = arm.side[:, None]
    out = np.where(s > 0, F["c"] > hi, F["c"] < lo)
    back = np.where(s > 0, F["c"] < hi, F["c"] > lo)
    ent = arm.c0 - 1
    was = _since(np.nan_to_num(out).astype(bool), arm) | np.nan_to_num(out[np.arange(arm.n), np.maximum(ent, 0)]).astype(bool)[:, None]
    return _after(arm) & was & _persist(np.nan_to_num(back).astype(bool), p)


def MO(arm, F, mode, r):
    s = _sd(F, arm)
    if mode == "rsi":
        x = s * (F["rsi"] - 50)
        was = _since(np.nan_to_num(x >= 20).astype(bool), arm)
        return _after(arm) & was & np.nan_to_num(x < 0).astype(bool)
    return _after(arm) & (np.nan_to_num(s * F["roc10"]) <= -r)


def DP(arm, F, z0):
    return _after(arm) & (np.nan_to_num(_sd(F, arm) * F["flz"]) <= -z0)


# ---------------------------------------------------------------- lock (trail) specialists: level for bar k+1
def VC(arm, F, k, a):
    pk = _peak_incl(arm)
    atr = F["oatr"]
    lv = pk - k * atr
    armed = (pk - arm.e[:, None]) >= a * atr
    lv = np.where(_after(arm) & armed & np.isfinite(lv), lv, NEG)
    return np.floor(lv / L.TICK) * L.TICK


def BE(arm, F, b):
    pk = _peak_incl(arm)
    lv = np.where(_after(arm) & (pk >= (arm.e * (1 + b))[:, None]), arm.be[:, None], NEG)
    return np.ceil(lv / L.TICK - 1e-9) * L.TICK


def GB(arm, F, keep, a):
    pk = _peak_incl(arm)
    e = arm.e[:, None]
    lv = np.where(_after(arm) & (pk >= e * (1 + a)), np.maximum(e + keep * (pk - e), arm.be[:, None]), NEG)
    return np.ceil(lv / L.TICK - 1e-9) * L.TICK


def SR(arm, F, step, amax=0.2):
    n = arm.n
    out = np.full((n, W), NEG)
    sar = np.full(n, np.nan)
    ep = np.full(n, np.nan)
    af = np.full(n, step)
    l1 = np.full(n, np.nan)
    l2 = np.full(n, np.nan)
    H, Lw = arm.H, arm.L
    for k in range(W):
        st = arm.c0 == k
        if st.any():
            sar[st] = Lw[st, k]
            ep[st] = H[st, k]
            af[st] = step
        on = (arm.c0 < k) & np.isfinite(sar) & ~np.isnan(H[:, k])
        if on.any():
            s2 = sar[on] + af[on] * (ep[on] - sar[on])
            s2 = np.fmin(s2, np.fmin(l1[on], l2[on]))
            sar[on] = s2
            nh = H[on, k] > ep[on]
            ep[on] = np.where(nh, H[on, k], ep[on])
            af[on] = np.where(nh, np.minimum(af[on] + step, amax), af[on])
        live = (arm.c0 <= k) & np.isfinite(sar)
        out[live, k] = sar[live]
        ok = ~np.isnan(Lw[:, k])
        l2 = np.where(ok, l1, l2)
        l1 = np.where(ok, Lw[:, k], l1)
    return np.floor(out / L.TICK) * L.TICK


# ---------------------------------------------------------------- extension agreement votes
def EXT(arm, F):
    s = arm.side[:, None]
    sd = s.astype(float)
    return {
        "xVW": np.nan_to_num(sd * (F["c"] - F["vw"]) >= 0.5 * F["vsd"]).astype(bool),
        "xMP": np.nan_to_num(np.where(s > 0, F["c"] > F["vah"], F["c"] < F["val"])).astype(bool),
        "xOI": np.nan_to_num(sd * F["oiz"] >= 0.5).astype(bool),
        "xVX": np.nan_to_num(np.abs(F["vix5"]) < 0.03).astype(bool),
        "xMO": np.nan_to_num(sd * F["roc10"] > 0).astype(bool),
        "xDP": np.nan_to_num(sd * F["flz"] >= 0.5).astype(bool),
    }


# ---------------------------------------------------------------- the round-1 grids (declared before any result)
EXIT_GRID = {
    "PA": [dict(T=T, m=m) for T in (10, 15, 20, 30, 45) for m in (0.03, 0.06, 0.10)],
    "PB": [dict(a=a) for a in (0.06, 0.08, 0.10, 0.12)],
    "TT": [dict(tau=t, g=g) for t in (13 * 60, 13 * 60 + 30, 14 * 60, 14 * 60 + 30) for g in (0.0, 0.05)],
    "VW": [dict(z=z, p=p) for z in (0.0, 0.5, 1.0) for p in (1, 3, 5)],
    "VO": [dict(m=m) for m in (3.0, 5.0, 8.0)],
    "OI": [dict(z0=z) for z in (1.0, 1.5, 2.0, 2.5)],
    "VX": [dict(x=x) for x in (0.01, 0.02, 0.03)],
    "MP": [dict(which=w, p=p) for w in ("dev", "prior") for p in (1, 3, 5)],
    "MO": [dict(mode="rsi", r=0.0)] + [dict(mode="roc", r=r) for r in (0.001, 0.0015, 0.0025)],
    "DP": [dict(z0=z) for z in (1.5, 2.0, 2.5)],
}
LOCK_GRID = {
    "VC": [dict(k=k, a=a) for k in (2.0, 3.0, 4.0, 6.0) for a in (1.0, 2.0)],
    "BE": [dict(b=b) for b in (0.05, 0.10, 0.15, 0.20)],
    "SR": [dict(step=s) for s in (0.01, 0.02, 0.04)],
    "GB": [dict(keep=k, a=a) for k in (0.5, 0.65) for a in (0.10, 0.20)],
}
FAMILY = {"PA": "price action / MFE", "PB": "price action / MAE", "TT": "time / theta", "VC": "volatility trail",
          "BE": "breakeven move", "SR": "parabolic SAR", "GB": "giveback trail", "VW": "VWAP", "VO": "volume climax",
          "OI": "open interest", "VX": "VIX / IV", "MP": "market profile", "MO": "momentum fade", "DP": "delta PROXY (thin)"}
FN = {"PA": PA, "PB": PB, "TT": TT, "VW": VW, "VO": VO, "OI": OI, "VX": VX, "MP": MP, "MO": MO, "DP": DP,
      "VC": VC, "BE": BE, "SR": SR, "GB": GB}
ORDER = {"PA": ("T", "m"), "PB": ("a",), "TT": ("tau", "g"), "VW": ("z", "p"), "VO": ("m",), "OI": ("z0",), "VX": ("x",),
         "MP": ("which", "p"), "MO": ("mode", "r"), "DP": ("z0",), "VC": ("k", "a"), "BE": ("b",), "SR": ("step",),
         "GB": ("keep", "a")}


def key(fam, p):
    return fam + "(" + ",".join(f"{k}={p[k]}" for k in ORDER[fam]) + ")"


def neighbours(fam, p, grid):
    """grid points of the same specialist one step away in exactly one parameter."""
    out = []
    names = ORDER[fam]
    vals = {k: sorted({g[k] for g in grid}, key=lambda x: (str(type(x)), x)) for k in names}
    for g in grid:
        diff = [k for k in names if g[k] != p[k]]
        if len(diff) != 1:
            continue
        k = diff[0]
        v = vals[k]
        if isinstance(p[k], str) or abs(v.index(g[k]) - v.index(p[k])) == 1:
            out.append(g)
    return out
