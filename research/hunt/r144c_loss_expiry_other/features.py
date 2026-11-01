"""R144c features at entry (PREREG.md section 3). Every feature of a trade reads only rows <= its decision row
(`dec = entry_row - 1`, the bar before the fill) of the trade's own day, and earlier days. `test_r144c.py` checks this
(the look-ahead guard): changing anything at or after the fill row, or on later days, never changes a feature.

    IRAALGO_LAKE=<lake> python -I research/hunt/r144c_loss_expiry_other/features.py     # -> $R144C_OUT/feats_<ARM>.pkl

Order-flow features are PROXIES (labelled): 1-minute volume signed by the bar's direction, and the OI-price quadrant.
The data has no bid / ask or depth: the "spread" is a proxy (the median 1-minute high-low / close).
"""
from __future__ import annotations

import math
import pickle
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lake as LK  # noqa: E402

ARMS = ("FT3", "HERO", "HEROTKT", "NIGHT", "VIXDIV", "FLOW")
W = 15                                         # the look-back window in minutes
QUAD = ("long build-up", "short covering", "short build-up", "long unwinding")
NAMES = {
    "mins_to_expiry": "minutes to the contract's expiry",
    "entry_min": "entry time (minutes after 09:15)",
    "morning_range": "today's range so far / ADR20",
    "trend": "move from today's open the trade's way / ADR20",
    "gap": "today's gap the trade's way / ADR20",
    "dist_straddles": "strike's distance from spot in ATM straddles",
    "opt_vol15": "option's volume, last 15 min (log)",
    "vol_vs_atm": "option's 15-min volume / the ATM's",
    "spread_proxy": "spread proxy: median 1-min (high-low)/close, 15 min",
    "print_share": "share of the last 15 minutes with a print",
    "premium": "premium paid (Rs)",
    "opt_oi": "option's OI (log)",
    "oi_chg": "option's OI change since its first print today",
    "pcr": "PCR (put OI / call OI, ATM+-10)",
    "iv": "option's IV",
    "iv_rel": "option's IV / ATM IV",
    "skew": "skew: put IV 2 steps out - call IV 2 steps out",
    "oi_wall": "biggest-OI strike on the trade's side, distance in straddles",
    "strad_vs_vix": "ATM straddle / the move VIX implies to expiry",
    "vix_prev": "India VIX previous close",
    "vix_chg": "India VIX change today to entry",
    "breadth": "breadth: share of lake stocks above yesterday's close",
    "prev_range": "yesterday's range / ADR20 (day regime)",
    "vol_delta": "PROXY order flow: signed volume / volume, 15 min",
    "quadrant": "PROXY order flow: OI-price quadrant, 15 min",
}
NUMERIC = [k for k in NAMES if k != "quadrant"]


# ------------------------------------------------------------------------------------------------ pure pieces
def adr20(dl: pd.DataFrame, d: date) -> float:
    p = dl[dl.index < d].tail(20)
    return float((p.high - p.low).mean()) if len(p) >= 10 else math.nan


def last_ok(x: np.ndarray, r: int, back: int = 5):
    """The last finite value of x at rows r-back+1 .. r (no row after r is ever read)."""
    lo = max(0, r - back + 1)
    seg = x[lo:r + 1]
    ok = np.nonzero(np.isfinite(seg))[0]
    return float(seg[ok[-1]]) if len(ok) else math.nan


def contract_feats(a: np.ndarray | None, dec: int) -> dict:
    """The traded option's own features from rows <= dec."""
    out = {k: math.nan for k in ("opt_vol15", "spread_proxy", "print_share", "opt_oi", "oi_chg", "iv", "vol_delta")}
    out["quadrant"] = None
    out["_vol15"] = math.nan
    if a is None or dec < 0:
        return out
    a = a[: dec + 1]
    lo = max(0, dec - W + 1)
    w = a[lo:]
    v = np.nan_to_num(w[:, LK.V])
    out["_vol15"] = float(v.sum())
    out["opt_vol15"] = math.log1p(float(v.sum()))
    rng = (w[:, LK.H] - w[:, LK.L]) / w[:, LK.C]
    rng = rng[np.isfinite(rng)]
    out["spread_proxy"] = float(np.median(rng)) if len(rng) else math.nan
    out["print_share"] = float((v > 0).mean())
    oi = last_ok(a[:, LK.OI], dec)
    out["opt_oi"] = math.log1p(oi) if oi == oi else math.nan
    first = np.nonzero(np.isfinite(a[:, LK.OI]) & (a[:, LK.OI] > 0))[0]
    if len(first) and oi == oi:
        out["oi_chg"] = oi / float(a[first[0], LK.OI]) - 1
    out["iv"] = last_ok(a[:, LK.IV], dec)
    sg = np.sign(np.nan_to_num(w[:, LK.C] - w[:, LK.O]))
    out["vol_delta"] = float((v * sg).sum() / v.sum()) if v.sum() > 0 else math.nan
    c0, c1 = last_ok(a[:, LK.C], lo, 5), last_ok(a[:, LK.C], dec)
    o0, o1 = last_ok(a[:, LK.OI], lo, 5), oi
    if all(x == x for x in (c0, c1, o0, o1)):
        up, oiup = c1 >= c0, o1 >= o0
        out["quadrant"] = QUAD[0] if up and oiup else QUAD[1] if up else QUAD[2] if oiup else QUAD[3]
    return out


def chain_feats(ch: dict | None, und: str, spot: float, dec: int, side: str, strike: float, vix: float,
                mins_left: float) -> dict:
    out = {k: math.nan for k in ("pcr", "iv_rel", "skew", "oi_wall", "strad_vs_vix", "dist_straddles", "_atm_vol15")}
    if ch is None or not spot == spot or dec < 0:
        return out
    step = LK.STEP[und]
    k0 = float(round(spot / step) * step)
    px = {key: last_ok(a[:, LK.C], dec, 3) for key, a in ch.items() if key != "spot"}
    ivs = {key: last_ok(a[:, LK.IV], dec, 3) for key, a in ch.items() if key != "spot"}
    ois = {key: last_ok(a[:, LK.OI], dec, 5) for key, a in ch.items() if key != "spot"}
    c, p = px.get((k0, "C"), math.nan), px.get((k0, "P"), math.nan)
    em = c + p if c == c and p == p and c > 0 and p > 0 else math.nan
    if em == em and strike == strike:
        out["dist_straddles"] = abs(strike - spot) / em
    co = sum(v for (k, s), v in ois.items() if s == "C" and v == v)
    po = sum(v for (k, s), v in ois.items() if s == "P" and v == v)
    out["pcr"] = po / co if co > 0 else math.nan
    a_iv = [ivs.get((k0, s), math.nan) for s in ("C", "P")]
    a_iv = [x for x in a_iv if x == x and x > 0]
    atm_iv = float(np.mean(a_iv)) if a_iv else math.nan
    own = ivs.get((strike, side), math.nan)
    out["iv_rel"] = own / atm_iv if own == own and atm_iv == atm_iv and atm_iv > 0 else math.nan
    pi, ci = ivs.get((k0 - 2 * step, "P"), math.nan), ivs.get((k0 + 2 * step, "C"), math.nan)
    out["skew"] = pi - ci if pi == pi and ci == ci else math.nan
    if em == em:
        cand = [(v, k) for (k, s), v in ois.items() if s == side and v == v and ((k > spot) if side == "C" else (k < spot))]
        if cand:
            out["oi_wall"] = abs(max(cand)[1] - spot) / em
        if vix == vix and vix > 0 and mins_left > 0:
            out["strad_vs_vix"] = em / (spot * vix / 100 * math.sqrt(mins_left / (365 * 1440)))
    a = ch.get((k0, side))
    if a is not None:
        out["_atm_vol15"] = float(np.nan_to_num(a[max(0, dec - W + 1): dec + 1, LK.V]).sum())
    return out


def index_feats(ix: np.ndarray, dec: int, sign: int, dl: pd.DataFrame, d: date) -> dict:
    out = {k: math.nan for k in ("morning_range", "trend", "gap", "prev_range")}
    a = ix[: dec + 1]
    adr = adr20(dl, d)
    prev = dl[dl.index < d]
    if not adr == adr or adr <= 0 or not len(prev):
        return out
    o0 = a[np.isfinite(a[:, 0]), 0]
    c1 = last_ok(a[:, 3], dec)
    if len(o0):
        out["morning_range"] = (np.nanmax(a[:, 1]) - np.nanmin(a[:, 2])) / adr
        out["trend"] = sign * (c1 - o0[0]) / adr if c1 == c1 else math.nan
        out["gap"] = sign * (o0[0] - float(prev.close.iloc[-1])) / adr
    out["prev_range"] = float(prev.high.iloc[-1] - prev.low.iloc[-1]) / adr
    return out


# ------------------------------------------------------------------------------------------------ breadth
def breadth_table(days: set) -> dict:
    """{day: (375,) share of the lake's stocks whose last close (forward-filled within the day) is above yesterday's
    close}; days the equity file does not cover are absent."""
    p = LK.OUT / "breadth.pkl"
    have = pickle.loads(p.read_bytes()) if p.exists() else {}
    need = {d for d in days if d not in have}
    for y in sorted({d.year for d in need}):
        f = LK.LAKE / "equity_1m" / f"equity_1m_{y}.parquet"
        fp = LK.LAKE / "equity_1m" / f"equity_1m_{y - 1}.parquet"
        if not f.exists():
            continue
        df = LK._frame(f, ["symbol", "close", "ts"])
        df["symbol"] = df.symbol.astype(str)
        last = df.sort_values("m").groupby(["symbol", "d"]).close.last().unstack(0).sort_index()
        if fp.exists():
            pv = LK._frame(fp, ["symbol", "close", "ts"])
            pv["symbol"] = pv.symbol.astype(str)
            lp = pv.sort_values("m").groupby(["symbol", "d"]).close.last().unstack(0).sort_index().tail(1)
            last = pd.concat([lp, last])
        prevc = last.shift(1)
        for d, g in df[df.d.isin({x for x in need if x.year == y})].groupby("d"):
            if d not in prevc.index:
                continue
            pc = prevc.loc[d]
            piv = g.pivot_table(index="m", columns="symbol", values="close", aggfunc="last").reindex(range(LK.N)).ffill()
            pcs = pc.reindex(piv.columns)
            ok = piv.notna() & pcs.notna().values[None, :]
            n = ok.sum(1).values
            up = ((piv.values > pcs.values[None, :]) & ok.values).sum(1)
            have[d] = np.where(n >= 20, up / np.maximum(n, 1), np.nan)
    p.write_bytes(pickle.dumps(have))
    return have


# ------------------------------------------------------------------------------------------------ the run
def series_of(t: dict) -> str:
    return "MONTH" if t["arm"] == "NIGHT" and t["und"] == "BANKNIFTY" and t["day"] < date(2024, 11, 20) else "WEEK"


def expiry_for(cal: list, d: date, monthly: bool) -> date | None:
    nxt = [x for x in cal if x >= d]
    if not nxt:
        return None
    if not monthly:
        return nxt[0]
    m = [x for x in nxt if (x.year, x.month) == (nxt[0].year, nxt[0].month)]
    return m[-1]


def strike_1itm(spot: float, und: str, side: str) -> float:
    step = LK.STEP[und]
    k = round(spot / step) * step              # Python's round: half to even, as R1
    return float(k - step if side == "C" else k + step)


def compute(arm: str, trades: list) -> list:
    vix = LK.index_minutes("INDIAVIX")
    vdl = LK.daily("INDIAVIX")
    br = breadth_table({t["day"] for t in trades})
    out = []
    by_und = {}
    for t in trades:
        by_und.setdefault((t["und"], series_of(t)), []).append(t)
    for (und, ser), ts in by_und.items():
        ix, dl = LK.index_minutes(und), LK.daily(und)
        cal = LK.expiry_days(und)
        ch = LK.chains(und, ser, {t["day"] for t in ts})
        chw = LK.chains(und, "WEEK", {t["day"] for t in ts}) if ser != "WEEK" else ch
        for t in ts:
            d, dec = t["day"], int(t["entry_row"]) - 1
            sign = 1 if t["side"] == "C" else -1
            a_ix = ix.get(d)
            f = {"_key": (t["arm"], t["und"], t["day"], t["side"], t["entry_row"])}
            spot = last_ok(a_ix[:, 3], dec) if a_ix is not None else math.nan
            strike = t["strike"] if t["strike"] == t["strike"] else (
                strike_1itm(spot, und, t["side"]) if spot == spot else math.nan)
            ex = expiry_for(cal, d, ser == "MONTH")
            mins_left = ((ex - d).days * 1440 + (LK.row(15, 30) - (dec + 1))) if ex else math.nan
            f["mins_to_expiry"] = float(mins_left)
            f["entry_min"] = float(dec + 1)
            f.update(index_feats(a_ix, dec, sign, dl, d) if a_ix is not None else {})
            c = ch.get(d) or {}
            a = c.get((strike, t["side"])) if c else None
            f.update(contract_feats(a, dec))
            v_now = last_ok(vix[d][:, 3], dec) if d in vix else math.nan
            vp = vdl[vdl.index < d]
            f["vix_prev"] = float(vp.close.iloc[-1]) if len(vp) else math.nan
            f["vix_chg"] = v_now / f["vix_prev"] - 1 if v_now == v_now and f["vix_prev"] == f["vix_prev"] else math.nan
            cf = chain_feats(chw.get(d), und, spot, dec, t["side"], strike, v_now, mins_left)
            f.update(cf)
            f["vol_vs_atm"] = f["_vol15"] / cf["_atm_vol15"] if cf["_atm_vol15"] and cf["_atm_vol15"] > 0 else math.nan
            f["premium"] = float(t["entry_px"])
            b = br.get(d)
            f["breadth"] = float(last_ok(b, dec)) if b is not None else math.nan
            out.append(f)
    return out


def main():
    for arm in ARMS:
        p = LK.OUT / f"trades_{arm}.pkl"
        trades = pickle.loads(p.read_bytes())
        fs = compute(arm, trades)
        (LK.OUT / f"feats_{arm}.pkl").write_bytes(pickle.dumps(fs))
        cov = {k: round(float(np.mean([x.get(k) is not None and x.get(k) == x.get(k) for x in fs])), 2) for k in NAMES}
        print(arm, len(fs), cov, flush=True)


if __name__ == "__main__":
    main()
