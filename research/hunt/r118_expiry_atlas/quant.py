"""R118 quant section (PREREG.md + Boss, 27 Oct: "use all the math you can, also try quant mathematics"): descriptive only.

    python3 research/hunt/r118_expiry_atlas/quant.py      # -> results/quant.md (reads atlas.py's cache and the lake)

  L1  Variance risk premium: the straddle's implied variance to the close vs the realised variance of 1-minute index
      returns over the same minutes. ATM straddle ~ sqrt(2/pi) x S x sigma x sqrt(T), so implied variance to the close =
      (straddle / (0.7979 S))^2.
  L2  The close's move in straddles vs what a normal (Gaussian) move priced by that straddle would give.
  L3  The index's 1-minute returns on expiry vs normal days: fat tails (kurtosis), jumps (bipower variation, Barndorff-
      Nielsen & Shephard), momentum or mean reversion (lag-1 autocorrelation, Lo-MacKinlay variance ratios), and the
      intraday volatility profile.
  L4  Risk-neutral probability vs what happened: each OTM option's own Black-76 IV (from its price), N(d2) = the
      market's probability it ends ITM, against the share that did; and the average end value against the price.
  L5  Theoretical delta N(d1) against the empirical 5-minute delta of section H.
Every number has its n; H1 = 2021-23, H2 = 2024-26.
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.special import ndtr

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import atlas as A  # noqa: E402
import report as RP  # noqa: E402

RES = HERE / "results"
SQ2PI = math.sqrt(2 / math.pi)
YEAR_MIN = 365 * 24 * 60
VR_Q = (5, 15, 30)
M = RP.m_


# ------------------------------------------------------------------------------------------------ maths helpers
def log_returns(c: np.ndarray) -> np.ndarray:
    """1-minute log returns of a close series; a minute with a missing close on either side gives NaN (no fill)."""
    c = np.asarray(c, float)
    with np.errstate(all="ignore"):
        return np.diff(np.log(c))


def rv_bv(r: np.ndarray) -> tuple[float, float]:
    """Realised variance sum(r^2) and bipower variation (pi/2) sum |r_i||r_i-1| over the valid returns."""
    r = r[~np.isnan(r)]
    if len(r) < 3:
        return np.nan, np.nan
    return float((r ** 2).sum()), float(np.pi / 2 * (np.abs(r[1:]) * np.abs(r[:-1])).sum() * len(r) / (len(r) - 1))


def kurt(r: np.ndarray) -> float:
    """Kurtosis m4 / m2^2 (3 for a normal distribution)."""
    r = r[~np.isnan(r)]
    if len(r) < 20:
        return np.nan
    r = r - r.mean()
    m2 = (r ** 2).mean()
    return float((r ** 4).mean() / m2 ** 2) if m2 > 0 else np.nan


def ac1(r: np.ndarray) -> float:
    ok = ~np.isnan(r[1:]) & ~np.isnan(r[:-1])
    if ok.sum() < 20:
        return np.nan
    a, b = r[1:][ok], r[:-1][ok]
    if a.std() == 0 or b.std() == 0:
        return np.nan
    return float(np.corrcoef(a, b)[0, 1])


def variance_ratio(r: np.ndarray, q: int) -> float:
    """Lo-MacKinlay: var of non-overlapping q-minute sums / (q x var of 1-minute returns). 1 = random walk; > 1
    momentum, < 1 mean reversion. Missing returns count as 0 inside a block (a quiet minute)."""
    x = np.nan_to_num(r)
    n = len(x) // q * q
    if n < 4 * q:
        return np.nan
    v1 = x[:n].var()
    vq = x[:n].reshape(-1, q).sum(1).var()
    return float(vq / (q * v1)) if v1 > 0 else np.nan


def b76_price(f, k, t, sig, call):
    sig = np.maximum(sig, 1e-9)
    sd = sig * np.sqrt(t)
    d1 = (np.log(f / k) + 0.5 * sd * sd) / sd
    d2 = d1 - sd
    return np.where(call, f * ndtr(d1) - k * ndtr(d2), k * ndtr(-d2) - f * ndtr(-d1))


def b76_iv(px, f, k, t, call, iters: int = 70):
    """Vectorised Black-76 implied vol by bisection (forward = spot, r = 0); NaN when the price is not above intrinsic
    or above the no-arbitrage bound."""
    px, f, k, t = (np.asarray(x, float) for x in (px, f, k, t))
    call = np.asarray(call, bool)
    intr = np.where(call, np.maximum(f - k, 0), np.maximum(k - f, 0))
    upper = np.where(call, f, k)
    ok = (px > intr + 1e-9) & (px < upper) & (t > 0)
    lo, hi = np.full(px.shape, 1e-4), np.full(px.shape, 30.0)
    for _ in range(iters):
        mid = 0.5 * (lo + hi)
        above = b76_price(f, k, t, mid, call) > px
        hi = np.where(above, mid, hi)
        lo = np.where(above, lo, mid)
    return np.where(ok, 0.5 * (lo + hi), np.nan)


def b76_d(f, k, t, sig):
    sd = sig * np.sqrt(t)
    d1 = (np.log(f / k) + 0.5 * sd * sd) / sd
    return d1, d1 - sd


# ------------------------------------------------------------------------------------------------ L1, L2
L_T = [M(9, 30), M(11, 0), M(12, 0), M(13, 0), M(14, 0), M(14, 30), M(15, 0)]


def vrp_rows(mins) -> pd.DataFrame:
    rows = []
    for x in mins:
        sp = x["spot"]
        r = log_returns(sp)
        ok = ~np.isnan(sp)
        if not ok.any():
            continue
        end = sp[ok][-1]
        for t in L_T:
            s, e = sp[t], x["em_pct"][t]
            if not (s == s and e == e and e > 0):
                continue
            iv2 = (e / 100 / SQ2PI) ** 2                     # implied variance of log(S_end / S_t)
            rv = float(np.nansum(r[t:] ** 2))
            rows.append(dict(und=x["und"], day=x["day"], t=t, iv2=iv2, rv=rv, z=math.log(end / s) / math.sqrt(iv2)))
    return pd.DataFrame(rows)


def sec_l1(v: pd.DataFrame):
    out = ["## L1. Variance risk premium: the variance the straddle priced against the variance that came", "",
           "implied = (straddle / (0.798 x spot))^2, the variance of the index's log move to the close that the ATM "
           "straddle prices; realised = the sum of squared 1-minute log returns over the same minutes. Ratio > 1: "
           "options priced more movement than came.", ""]
    rows = []
    for t in L_T:
        g = v[v.t == t]
        h = {hh: g[g.day.map(RP.half) == hh] for hh in ("H1", "H2")}
        rows.append({"from": RP.hm(t), "days": len(g), "implied/realised (median)": (g.iv2 / g.rv).median(),
                     "implied vol / realised vol (median)": np.sqrt(g.iv2 / g.rv).median(),
                     "days implied > realised %": (g.iv2 > g.rv).mean() * 100,
                     "ratio of the means (sum iv2 / sum rv)": g.iv2.sum() / g.rv.sum(),
                     "H1 median ratio": (h["H1"].iv2 / h["H1"].rv).median(), "H2 median ratio": (h["H2"].iv2 / h["H2"].rv).median(),
                     "stable?": RP.stable((h["H1"].iv2 / h["H1"].rv).median(), (h["H2"].iv2 / h["H2"].rv).median(), 1.0)})
    out += [RP.md(pd.DataFrame(rows), 2), ""]
    rows = []
    g = v[v.t == M(12, 0)]
    for u in A.R.UNDS:
        x = g[g.und == u]
        rows.append({"index": u, "days": len(x), "implied/realised from 12:00 (median)": (x.iv2 / x.rv).median(),
                     "days implied > realised %": (x.iv2 > x.rv).mean() * 100})
    out += ["By index, from 12:00:", "", RP.md(pd.DataFrame(rows), 2), "",
            "Caution: on expiry days the index mean-reverts minute to minute (L3: VR(30) about 0.76), so the sum of "
            "1-minute squared returns overstates the variance that matters for a move to the close, most for SENSEX "
            "and MIDCPNIFTY. L2's close-move test has no such bias; read the by-index premium there.", ""]
    return out


def sec_l2(v: pd.DataFrame):
    out = ["## L2. The close's move against a normal (bell-curve) move of the size the straddle priced", "",
           "z = log(close / spot at t) / implied sd. If the straddle priced a normal move correctly, |z| would follow "
           "a standard normal: P(|move| > 1 straddle) = P(|z| > 0.798) = 42.5%, P(> 2 straddles) = 11.0%, P(> 3) = "
           "1.7%.", ""]
    from scipy.stats import norm
    rows = []
    for t in (M(9, 30), M(12, 0), M(14, 0), M(15, 0)):
        z = v[v.t == t].z.dropna().values
        r = {"from": RP.hm(t), "days": len(z), "sd of z": z.std(), "kurtosis of z (normal 3)": kurt(z)}
        for k in (0.5, 1, 2, 3):
            thr = k * SQ2PI
            r[f"> {k:g} straddle: seen %"] = (np.abs(z) > thr).mean() * 100
            r[f"> {k:g}: normal %"] = 2 * (1 - norm.cdf(thr)) * 100
        rows.append(r)
    hr = []
    for t in (M(9, 30), M(12, 0), M(14, 0)):
        for hh in ("H1", "H2"):
            z = v[(v.t == t) & (v.day.map(RP.half) == hh)].z.dropna().values
            hr.append({"from": RP.hm(t), "half": hh, "days": len(z), "sd of z": z.std(), "kurtosis": kurt(z),
                       "> 1 straddle %": (np.abs(z) > SQ2PI).mean() * 100,
                       "> 3 straddles %": (np.abs(z) > 3 * SQ2PI).mean() * 100})
    out_half = ["### By half (normal: > 1 straddle 42.5%, > 3 1.7%, kurtosis 3)", "", RP.md(pd.DataFrame(hr), 2), ""]
    g = v[v.t == M(12, 0)]
    br = []
    for u in A.R.UNDS:
        z = g[g.und == u].z.dropna()
        h = {hh: g[(g.und == u) & (g.day.map(RP.half) == hh)].z.dropna() for hh in ("H1", "H2")}
        br.append({"index": u, "days": len(z), "sd of z (1 = fair)": z.std(), "H1 sd": h["H1"].std(),
                   "H2 sd": h["H2"].std(), "> 1 straddle %": (z.abs() > SQ2PI).mean() * 100,
                   "> 3 straddles %": (z.abs() > 3 * SQ2PI).mean() * 100})
    rows_idx = br
    out += [RP.md(pd.DataFrame(rows), 2), ""] + out_half + ["### By index, from 12:00 (normal: > 1 straddle 42.5%, > 3 1.7%)", "",
            RP.md(pd.DataFrame(rows_idx), 2), "",
            "sd of z below 1: the 12:00 straddle priced more than the typical move to the close (the premium is rich); "
            "above 1: cheap. H1 / H2 under 30 days are small samples.", "",
            "Reading: fewer small-to-medium moves than a bell curve, and about as many (or more) big ones: the classic "
            "fat-tailed shape. The straddle is dear for the typical day and roughly fair or cheap for the rare big one.",
            ""]
    return out


# ------------------------------------------------------------------------------------------------ L3 index returns
def index_stats(und: str, expiries: set) -> pd.DataFrame:
    ix = A.R._index_frames(und)
    rows = []
    slots = list(range(0, A.N, 15))
    for d, g in ix.groupby("d"):
        a = A.R._grid(g, ["close"])[:, 0]
        if np.isnan(a).sum() > 60:
            continue
        r = log_returns(a)
        rv, bv = rv_bv(r)
        rr = np.nan_to_num(r) ** 2
        sl = [rr[s:s + 15].sum() for s in slots]
        tot = rr.sum()
        rows.append(dict(und=und, day=d, expiry=d in expiries, rv=rv, bv=bv,
                         jump=max(0.0, 1 - bv / rv) if rv and rv > 0 else np.nan, kurtosis=kurt(r), ac1=ac1(r),
                         **{f"vr{q}": variance_ratio(r, q) for q in VR_Q},
                         **{f"s{i}": (x / tot if tot > 0 else np.nan) for i, x in enumerate(sl)},
                         top1=float(np.sort(rr)[-4:].sum() / tot) if tot > 0 else np.nan))
    return pd.DataFrame(rows)


def sec_l3(st: pd.DataFrame):
    out = ["## L3. The index's 1-minute returns: expiry days against normal days (same years)", "",
           "kurtosis 3 = normal; jump share = 1 - bipower / realised variance (the part of the day's variance that came "
           "in jumps); top-1% share = the share of the day's variance in its 4 biggest minutes (about 1% of 374); "
           "VR(q) = variance ratio, 1 = random walk, > 1 trending, < 1 mean-reverting.", ""]
    rows = []
    for u in (*A.R.UNDS, "ALL"):
        x = st if u == "ALL" else st[st.und == u]
        e = x[x.expiry]
        if u != "ALL":
            x = x[(x.day >= e.day.min()) & (x.day <= e.day.max())]
        for lab, g in (("expiry", x[x.expiry]), ("normal", x[~x.expiry])):
            rows.append({"index": u, "days": lab, "n": len(g), "daily vol % (sqrt RV)": np.sqrt(g.rv).median() * 100,
                         "kurtosis": g["kurtosis"].median(), "jump share": g.jump.median(), "top-1% share": g.top1.median(),
                         "lag-1 autocorr": g.ac1.mean(), **{f"VR({q})": g[f"vr{q}"].median() for q in VR_Q}})
    out += [RP.md(pd.DataFrame(rows), 3), ""]
    rows = []
    for h in ("H1", "H2"):
        for lab, flag in (("expiry", True), ("normal", False)):
            g = st[(st.half == h) & (st.expiry == flag)]
            rows.append({"half": h, "days": lab, "n": len(g), "kurtosis": g["kurtosis"].median(), "jump share": g.jump.median(),
                         "lag-1 autocorr": g.ac1.mean(), "VR(15)": g.vr15.median(), "VR(30)": g.vr30.median()})
    out += ["By half (all indices):", "", RP.md(pd.DataFrame(rows), 3), ""]
    # intraday volatility profile
    sl = [c for c in st.columns if c.startswith("s") and c[1:].isdigit()]
    rows = []
    for c in sl:
        i = int(c[1:])
        rows.append({"15 minutes from": RP.hm(i * 15), "expiry: share of day's variance %": st[st.expiry][c].median() * 100,
                     "normal %": st[~st.expiry][c].median() * 100})
    out += ["### When the day's index variance comes (median share per 15-minute slot)", "", RP.md(pd.DataFrame(rows), 2), ""]
    return out


# ------------------------------------------------------------------------------------------------ L4, L5
def with_iv(mk: pd.DataFrame) -> pd.DataFrame:
    g = mk[mk.otm & (mk.px >= 0.5)].copy()
    t = (A.N - g.t.values) / YEAR_MIN
    g["T"] = t
    g["iv_own"] = b76_iv(g.px.values, g.spot.values, g.strike.values, t, (g.side == "C").values)
    d1, d2 = b76_d(g.spot.values, g.strike.values, t, g.iv_own.values)
    call = (g.side == "C").values
    g["p_itm"] = np.where(call, ndtr(d2), ndtr(-d2))
    g["delta_b76"] = np.where(call, ndtr(d1), ndtr(-d1))          # absolute delta
    g["itm_end"] = np.where(call, g.y_s_end > g.strike, g.y_s_end < g.strike)
    return g[g.iv_own.notna()]


def sec_l4(g: pd.DataFrame, n_boot: int = 1000):
    out = ["## L4. The market's probability against what happened (OTM options priced Rs 0.50+, every mark)", "",
           "p_itm = N(d2) from the option's own Black-76 IV (forward = spot, r = 0, calendar time to 15:30): the "
           "risk-neutral chance it ends in the money. 'ended ITM' uses the index's last minute (a proxy for the "
           "exchange's settlement price). 'payout / price' = mean end value / mean price: 1.00 means buyers got back, "
           "on average, exactly what they paid (before any costs); 95% range from 1,000 day-bootstraps.", ""]
    rng = np.random.default_rng(RP.SEED)
    g = g.copy()
    g["pb"] = pd.cut(g.p_itm, [0, 0.01, 0.02, 0.05, 0.1, 0.2, 0.3, 0.4, 0.5], right=True)
    g["dkey"] = g.und + g.day.astype(str)
    rows = []
    for b, x in g.groupby("pb", observed=True):
        days = x.dkey.unique()
        e_sum = x.groupby("dkey").y_end.sum().reindex(days).values
        p_sum = x.groupby("dkey").px.sum().reindex(days).values
        bs = []
        for _ in range(n_boot):
            ii = rng.integers(0, len(days), len(days))
            bs.append(e_sum[ii].sum() / p_sum[ii].sum())
        h = {hh: x[x.half == hh] for hh in ("H1", "H2")}
        rows.append({"market's p_itm": str(b), "marks": len(x), "days": len(days), "mean p_itm %": x.p_itm.mean() * 100,
                     "ended ITM %": x.itm_end.mean() * 100,
                     "ITM H1 / H2 %": f"{h['H1'].itm_end.mean() * 100:.1f} / {h['H2'].itm_end.mean() * 100:.1f}",
                     "payout / price": x.y_end.sum() / x.px.sum(),
                     "95% range": f"{np.percentile(bs, 2.5):.2f} .. {np.percentile(bs, 97.5):.2f}",
                     "payout/price H1 / H2": f"{h['H1'].y_end.sum() / h['H1'].px.sum():.2f} / "
                                             f"{h['H2'].y_end.sum() / h['H2'].px.sum():.2f}"})
    out += [RP.md(pd.DataFrame(rows), 2), ""]
    rows = []
    for (s, side), x in g.groupby([g.t.map(RP.slot), "side"]):
        rows.append({"time": s, "side": side, "marks": len(x), "mean p_itm %": x.p_itm.mean() * 100,
                     "ended ITM %": x.itm_end.mean() * 100, "payout / price": x.y_end.sum() / x.px.sum()})
    out += ["### By time and side (all OTM Rs 0.50+)", "", RP.md(pd.DataFrame(rows), 2), "",
            "These are marks, not independent trades: options on the same day move together. The day-bootstrap range "
            "in the first table is the honest width.", ""]
    return out


def sec_l5(g: pd.DataFrame):
    out = ["## L5. Theoretical delta (Black-76, own IV) against the delta measured over the next 5 minutes", ""]
    y = g[g.y_px5.notna() & g.y_s5.notna() & (g.px >= 1)].copy()
    ds = (y.y_s5 - y.spot) * np.where(y.side == "C", 1, -1)
    y = y[(ds.abs() / y.spot) > 0.0003]
    ds = (y.y_s5 - y.spot) * np.where(y.side == "C", 1, -1)
    y["delta_emp"] = (y.y_px5 - y.px) / ds
    rows = []
    for (s, b), x in y.groupby([y.t.map(RP.slot), "b_em"]):
        rows.append({"time": s, "distance (straddles)": A.EM_LABELS[b], "n": len(x), "B76 delta": x.delta_b76.median(),
                     "measured delta": x.delta_emp.median(), "measured / B76": x.delta_emp.median() / x.delta_b76.median()})
    out += [RP.md(pd.DataFrame(rows), 3), "",
            "Measured below theory: the option reacts less than its delta over 5 minutes (time decay in those 5 minutes, "
            "the bid-ask bounce and the IV moving against the index all eat into it).", ""]
    return out


def main():
    mk, dy, ix, mins = RP.load()
    v = vrp_rows(mins)
    parts = ["# R118 quant section (generated by quant.py)", "",
             "Descriptive only; signal source: none. The index's 1-minute closes (unfilled) and the options' own prints.", ""]
    parts += sec_l1(v) + sec_l2(v)
    st = pd.concat([index_stats(u, set(dy[dy.und == u].day)) for u in A.R.UNDS], ignore_index=True)
    st["half"] = st.day.map(RP.half)
    parts += sec_l3(st)
    g = with_iv(mk)
    parts += sec_l4(g) + sec_l5(g)
    RES.mkdir(exist_ok=True)
    (RES / "quant.md").write_text("\n".join(parts) + "\n")
    print("\n".join(parts))


if __name__ == "__main__":
    main()
