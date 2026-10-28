"""R129 (PREREG.md): the "Gamma-Driven Hero-to-Zero" expiry-day option buying strategy, as stated, on the Drive lake.

    python research/hunt/r129_gamma_expiry/r129.py      # -> results/results.json (+ the trade list in $R129_OUT)

Each index on each of its own expiry days: VIX > 12 at 13:15; the 13:45 OI wall with a 3x call/put imbalance; the 13:15-13:59
box broken through the wall by a 5-minute close 14:00-15:00 (beyond the TWAP); heavyweights beyond their VWAP; the ATM+1 CE /
ATM-1 PE at Rs 15-30; a dual Supertrend (7,3)+(10,2) trigger with the option's volume over its 10-minute mean; target 4x, out on
a Supertrend flip or the cap. The trigger is read on the option's premium chart (as written: NOT the default signal source,
labelled) or on the index (the default).
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r128_missing"))
sys.path.insert(0, str(HERE.parent / "r127_cpr"))
sys.path.insert(0, str(HERE.parent / "r73"))
import r128 as T8  # noqa: E402  (puts r123 / r124 on the path)
import r123cfg as K  # noqa: E402
import r123data as D3  # noqa: E402
import r127cpr as C7  # noqa: E402
from r73rules import supertrend_dir  # noqa: E402

N = K.N
O_, H_, L_, C_, V_ = range(5)
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R129_OUT", K.CACHE_ROOT / "r129")).expanduser()
EQUITY = Path(os.environ.get("IRAALGO_LAKE", K.CACHE_ROOT / "lake")).expanduser() / "equity_1m"
VIX_MIN = 12.0
ROW_VIX, ROW_WALL = K.row(13, 14), K.row(13, 44)           # the last bars closed by 13:15 and 13:45
BOX = (K.row(13, 15), K.row(13, 59))
WIN = (K.row(14, 0), K.row(14, 59))                         # 5-minute bars closing 14:05 .. 15:00; triggers to 15:00
CAPS = {"1525": K.row(15, 24), "1505": K.row(15, 4)}
CORRIDOR = (15.0, 30.0)
IMB = 3.0
TARGET_X = 4.0
NEAR_MAX = 8
HEAVY = {"BANKNIFTY": ("HDFCBANK", "ICICIBANK", "AXISBANK"), "FINNIFTY": ("HDFCBANK", "ICICIBANK", "AXISBANK"),
         "NIFTY": ("HDFCBANK", "RELIANCE", "ICICIBANK"), "SENSEX": ("HDFCBANK", "RELIANCE", "ICICIBANK"), "MIDCPNIFTY": ()}
VARIANTS = {"G1": dict(trig="option", strike="strict", cap="1525"), "G2": dict(trig="option", strike="strict", cap="1505"),
            "G3": dict(trig="index", strike="strict", cap="1525"), "G4": dict(trig="index", strike="strict", cap="1505"),
            "G5": dict(trig="option", strike="near", cap="1525"), "G6": dict(trig="index", strike="near", cap="1525")}
TWINS = 200
SEED = 129


# ------------------------------------------------------------------------------------------------ pure pieces
def dual_st(h, l, c):
    """(Supertrend(7,3), Supertrend(10,2)) directions, +1 / -1 / 0 (warm-up), on bars without gaps."""
    return supertrend_dir(h, l, c, 7, 3.0), supertrend_dir(h, l, c, 10, 2.0)


def traded_st(a: np.ndarray):
    """The option's dual Supertrend on its traded minutes, laid back on the minute grid (NaN rows: 0)."""
    idx = np.flatnonzero(np.isfinite(a[:, C_]) & np.isfinite(a[:, H_]) & np.isfinite(a[:, L_]))
    s7, s10 = np.zeros(N, np.int64), np.zeros(N, np.int64)
    if len(idx):
        x7, x10 = dual_st(a[idx, H_], a[idx, L_], a[idx, C_])
        s7[idx], s10[idx] = x7, x10
    return s7, s10, idx


def index_st(h, l, c):
    """The index's dual Supertrend on its minutes (a missing minute carries the last bar: no gap in the bands)."""
    ok = np.isfinite(c) & np.isfinite(h) & np.isfinite(l)
    s7, s10 = np.zeros(N, np.int64), np.zeros(N, np.int64)
    idx = np.flatnonzero(ok)
    if len(idx):
        x7, x10 = dual_st(h[idx], l[idx], c[idx])
        s7[idx], s10[idx] = x7, x10
    return s7, s10


def wall_of(oi: dict):
    """{strike: (call OI, put OI)} -> (wall strike, direction): +1 call OI >= 3x put OI, -1 put OI >= 3x call OI, 0 neither."""
    if not oi:
        return None, 0
    k = max(oi, key=lambda s: (np.nan_to_num(oi[s][0]) + np.nan_to_num(oi[s][1]), -s))
    c, p = oi[k]
    c, p = np.nan_to_num(c), np.nan_to_num(p)
    if c > 0 and c >= IMB * p:
        return k, 1
    if p > 0 and p >= IMB * c:
        return k, -1
    return k, 0


def setup_bar(h, l, c, box_hi, box_lo, wall, d: int):
    """The first 5-minute bar closing 14:05..15:00 whose close is beyond the box and the wall in direction d and beyond the
    TWAP (mean minute close since 09:15); the 13:59 close must still be on the near side of the wall. -> its last row or None."""
    c59 = D3._last(c[:WIN[0]])
    if not np.isfinite(c59) or (d > 0 and c59 > wall) or (d < 0 and c59 < wall):
        return None
    for e in range(WIN[0] + 4, WIN[1] + 1, 5):
        ce = D3._last(c[e - 4:e + 1])
        if not np.isfinite(ce):
            continue
        twap = np.nanmean(c[:e + 1])
        if d > 0 and ce > max(box_hi, wall) and ce > twap:
            return e
        if d < 0 and ce < min(box_lo, wall) and ce < twap:
            return e
    return None


def vol_ok(a: np.ndarray, idx: np.ndarray, m: int) -> bool:
    """The option traded at minute m with volume over the mean of its previous 10 traded minutes."""
    k = np.searchsorted(idx, m)
    if k >= len(idx) or idx[k] != m or k < 10:
        return False
    return a[m, V_] > a[idx[k - 10:k], V_].mean()


def trigger(a, idx, s7o, s10o, s7i, s10i, d: int, frm: int, to: int, mode: str):
    """The first minute in frm..to meeting the trigger (both Supertrends the trade's way + the volume) -> row or None."""
    for m in range(frm, to + 1):
        if mode == "option":
            ok = s7o[m] == 1 and s10o[m] == 1
        else:
            ok = s7i[m] == d and s10i[m] == d
        if ok and vol_ok(a, idx, m):
            return m
    return None


def walk(a, e: int, cap: int, flip):
    """From the entry row e (bought at its open): the target (a high at 4x the raw entry; first), else the first minute close
    where flip(r) says out -> sell at the next traded open, else the cap's close. -> (raw exit, row, why) or None."""
    if not np.isfinite(a[e, O_]):
        return None
    tgt = TARGET_X * a[e, O_]
    for r in range(e, cap + 1):
        if np.isfinite(a[r, H_]) and a[r, H_] >= tgt:
            return tgt, r, "target"
        if np.isfinite(a[r, C_]) and flip(r):
            nxt = [x for x in range(r + 1, cap + 1) if np.isfinite(a[x, O_])]
            if nxt:
                return float(a[nxt[0], O_]), nxt[0], "flip"
            return float(a[r, C_]), r, "flip"
    fin = np.flatnonzero(np.isfinite(a[e:cap + 1, C_]))
    if not len(fin):
        return None
    return float(a[e + fin[-1], C_]), e + fin[-1], "cap"


def trade_net(a, e, raw_exit, xr, lot, d, und):
    b = T8.buy_fill(a, e, lot)
    if b is None:
        return None
    s = T8.sell_at(a, e, xr, lot, price=raw_exit)
    return T8.leg_net(b[0], b[1], s[0], s[1], lot, d, und)


# ------------------------------------------------------------------------------------------------ data
def load_equity(symbols) -> dict:
    """{(symbol, day): (close[N], vwap[N])} from the lake's equity minutes (typical-price VWAP since 09:15)."""
    out = {}
    for p in sorted(EQUITY.glob("equity_1m_*.parquet")):
        df = pd.read_parquet(p, columns=["symbol", "high", "low", "close", "volume", "ts"])
        df = df[df.symbol.isin(symbols)]
        ts = pd.to_datetime(df.ts)
        if ts.dt.tz is not None:
            ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        df = df.assign(d=ts.dt.date, m=((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15))
        df = df[(df.m >= 0) & (df.m < N)]
        for (s, d), g in df.groupby(["symbol", "d"]):
            c = np.full(N, np.nan)
            pv = np.zeros(N)
            vv = np.zeros(N)
            c[g.m.values] = g.close.values
            tp = (g.high.values + g.low.values + g.close.values) / 3
            pv[g.m.values] = tp * g.volume.values
            vv[g.m.values] = g.volume.values
            with np.errstate(all="ignore"):
                vwap = np.cumsum(pv) / np.cumsum(vv)
            out[(s, d)] = (c, vwap)
    return out


def day_frame(LO, d, closes):
    """The day's cleaned option rows (lakeclean 1-3, stale): m, strike, side, ohlc, volume, oi. None when absent."""
    if LO.day(d, closes) is None:
        return None
    g = LO.by_day.get(d)
    if g is None or g.empty:
        return None
    w = g.rename(columns={"d": "day", "m": "r"})
    kept, _, _ = D3.lakeclean.clean(w, {d: closes}, stale=True)
    return kept.rename(columns={"day": "d", "r": "m"}) if len(kept) else None


def oi_at(g: pd.DataFrame, row: int, spot: float, step: float) -> dict:
    """{strike: (call OI, put OI)}: the last known OI at or before row, strikes within +-10 of spot's ATM."""
    atm = D3.atm(spot, step)
    x = g[(g.m <= row) & ((g.strike - atm).abs() <= 10 * step + 1e-9) & np.isfinite(g.oi)]
    if x.empty:
        return {}
    last = x.sort_values("m").groupby(["strike", "side"]).oi.last()
    out = {}
    for (k, s), v in last.items():
        c, p = out.get(float(k), (np.nan, np.nan))
        out[float(k)] = (v, p) if s == "C" else (c, v)
    return out


# ------------------------------------------------------------------------------------------------ per index
def run_index(und: str, equity: dict, log=print):
    g = D3.load_grid(und, K.HOLDOUT[1])
    vix = D3.load_grid("INDIAVIX", K.HOLDOUT[1])
    vpos = vix.pos()
    LO = D3.LakeOptions(und, K.HOLDOUT[1])
    exp = sorted(LO.expiry_days())
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und))
    pos = g.pos()
    trades, days = [], []
    for d in exp:
        i = pos.get(d)
        if i is None:
            continue
        rec = dict(und=und, day=d, stop=None)
        days.append(rec)
        h, l, c = g.h[i], g.l[i], g.c[i]
        vi = vpos.get(d)
        vx = D3._last(vix.c[vi][:ROW_VIX + 1]) if vi is not None else np.nan
        if not (np.isfinite(vx) and vx > VIX_MIN):
            rec["stop"] = "vix"
            continue
        gf = day_frame(LO, d, c)
        if gf is None:
            rec["stop"] = "no options"
            continue
        spot45 = D3._last(c[:ROW_WALL + 1])
        wall, dr = wall_of(oi_at(gf, ROW_WALL, spot45, step))
        rec.update(wall=wall, dir=dr)
        if wall is None or dr == 0:
            rec["stop"] = "no 3x wall"
            continue
        bh, bl = np.nanmax(h[BOX[0]:BOX[1] + 1]), np.nanmin(l[BOX[0]:BOX[1] + 1])
        e5 = setup_bar(h, l, c, bh, bl, wall, dr)
        # the claim: index points the wall's way from 14:00 to 15:20, setup days vs the other expiry afternoons
        c0, c1 = D3._last(c[:WIN[0]]), D3._last(c[:K.row(15, 19) + 1])
        rec["move"] = float(dr * (c1 - c0)) if np.isfinite(c0) and np.isfinite(c1) else np.nan
        if e5 is None:
            rec["stop"] = "no breakout"
            continue
        rec["setup"] = True
        heavy = HEAVY[und]
        if heavy:
            ok = True
            for s in heavy:
                q = equity.get((s, d))
                if q is None or not (np.isfinite(q[0][e5]) and np.isfinite(q[1][e5])):
                    ok = False
                    break
                if (dr > 0 and not q[0][e5] > q[1][e5]) or (dr < 0 and not q[0][e5] < q[1][e5]):
                    ok = False
                    break
            if not ok:
                rec["stop"] = "heavyweights"
                continue
        side = "C" if dr > 0 else "P"
        atm = D3.atm(D3._last(c[:e5 + 1]), step)
        contracts = {}
        for (s_, k), grp in gf.groupby(["side", "strike"]):
            if s_ != side:
                continue
            a = np.full((N, 5), np.nan)
            mm = grp.m.values
            for j, col in enumerate(("open", "high", "low", "close", "volume")):
                a[mm, j] = grp[col].values.astype(float)
            a[~(a[:, V_] > 0)] = np.nan
            contracts[float(k)] = a

        def pick(mode):
            ks = [atm + dr * step] if mode == "strict" else [atm + dr * step * n for n in range(1, NEAR_MAX + 1)]
            for k in ks:
                a = contracts.get(float(k))
                if a is None:
                    continue
                px = D3._last(a[:e5 + 1, C_])
                if np.isfinite(px) and CORRIDOR[0] <= px <= CORRIDOR[1]:
                    return float(k), a
            return None, None

        s7i, s10i = index_st(h, l, c)
        rec["stop"] = "corridor"
        for v, cfg in VARIANTS.items():
            k, a = pick(cfg["strike"])
            if a is None:
                continue
            rec["stop"] = None
            s7o, s10o, idx = traded_st(a)
            cap = CAPS[cfg["cap"]]
            if cfg["trig"] == "option":
                def flip(r, s7o=s7o, s10o=s10o):
                    return s7o[r] == -1 or s10o[r] == -1
            else:
                def flip(r):
                    return s7i[r] == -dr or s10i[r] == -dr
            m = trigger(a, idx, s7o, s10o, s7i, s10i, dr, e5, WIN[1], cfg["trig"])
            if m is None:
                continue
            nxt = [x for x in idx if m < x <= cap]
            if not nxt:
                continue
            e = int(nxt[0])
            w = walk(a, e, cap, flip)
            if w is None:
                continue
            raw_x, xr, why = w
            tn = trade_net(a, e, raw_x, xr, lot, d, und)
            if tn is None:
                continue
            # random-entry twins: a random traded minute 14:00..15:00 of the same contract, the same exits
            tw = []
            pool = [x for x in idx if WIN[0] <= x <= min(WIN[1] + 1, cap)]
            for _ in range(TWINS):
                if not pool:
                    tw.append(np.nan)
                    continue
                e2 = int(rng.choice(pool))
                w2 = walk(a, e2, cap, flip)
                t2 = trade_net(a, e2, w2[0], w2[1], lot, d, und) if w2 is not None else None
                tw.append(np.nan if t2 is None else t2[0])
            pts = dr * (D3._last(c[:xr + 1]) - D3._last(c[:e + 1]))
            trades.append(dict(variant=v, und=und, day=d, side=dr, strike=k, entry_row=e, exit_row=xr, why=why,
                               entry=float(a[e, O_]), exit=float(raw_x), net=tn[0], netx2=tn[1], pts=float(pts),
                               mult=float(raw_x / a[e, O_]), monthly=bool(d.month != (d + pd.Timedelta(days=7)).month),
                               twins=tw))
    log(f"{und}: {len(days)} expiry days, {len(trades)} trades")
    return trades, days


def _one(und):
    eq = load_equity(sorted({s for v in HEAVY.values() for s in v}))
    return run_index(und, eq)


def sizing() -> dict:
    """The stated 2% rule: lots = capital x 0.02 / (premium x lot), by capital and premium (rounded down)."""
    out = {}
    for cap in (200_000, 500_000, 1_000_000):
        out[str(cap)] = {u: {str(p): int(cap * 0.02 // (p * K.LOT[u])) for p in (15, 20, 25, 30)} for u in K.UNDS}
    return out


def run():
    from multiprocessing import Pool
    with Pool(len(K.UNDS)) as pool:
        res = pool.map(_one, list(K.UNDS))
    T = pd.DataFrame([t for r in res for t in r[0]])
    Dd = pd.DataFrame([d for r in res for d in r[1]])
    OUT.mkdir(parents=True, exist_ok=True)
    T.drop(columns=["twins"]).to_parquet(OUT / "trades.parquet")
    out = dict(expiry_days=int(len(Dd)), stops={str(k): int(v) for k, v in Dd.stop.fillna("traded or reached a trigger").value_counts().items()},
               sizing=sizing(), variants={})
    setup = Dd[Dd.get("setup", pd.Series(False, index=Dd.index)).fillna(False).astype(bool)]
    other = Dd[(Dd.stop.isin(["no breakout"])) & Dd.move.notna()]
    out["claim_move"] = dict(setup_days=int(len(setup)), setup_move=float(setup.move.mean()) if len(setup) else None,
                             other_wall_days=int(len(other)), other_move=float(other.move.mean()) if len(other) else None)
    ps = []
    for v in VARIANTS:
        X = T[T.variant == v] if len(T) else T
        st = C7.stats(X.reset_index(drop=True), max(1, int(len(Dd)))) if len(X) else dict(n=0)
        if len(X):
            st["hit_4x"] = float((X.why == "target").mean())
            st["apr_oct_2026"] = dict(n=int((X.day >= K.HOLDOUT[0]).sum()),
                                     avg=float(X.net[X.day >= K.HOLDOUT[0]].mean()) if (X.day >= K.HOLDOUT[0]).any() else None)
            st["weekly_vs_monthly"] = {k: dict(n=int(len(x)), avg=float(x.net.mean())) for k, x in
                                       (("weekly", X[~X.monthly]), ("monthly", X[X.monthly])) if len(x)}
            st["ex_midcp"] = dict(n=int((X.und != "MIDCPNIFTY").sum()), avg=float(X.net[X.und != "MIDCPNIFTY"].mean())
                                  if (X.und != "MIDCPNIFTY").any() else None)
        st["signal"] = "option premium chart (NOT the default)" if VARIANTS[v]["trig"] == "option" else "index (default)"
        st["cfg"] = VARIANTS[v]
        out["variants"][v] = st
        ps.append(st.get("p_twins", 1.0))
    q = C7.R3.bh(ps) if hasattr(C7, "R3") else None
    for v, qq in zip(VARIANTS, q if q is not None else [None] * len(VARIANTS)):
        st = out["variants"][v]
        st["q"] = qq
        if st.get("n"):
            yrs = st["by_year"]
            st["gates"] = dict(n=st["n"] >= 300, net_pos=st["avg_net"] > 0 and st["avg_net_x2"] > 0,
                               years=sum(1 for y in range(2022, 2027) if yrs.get(y, {}).get("total", -1) > 0) >= 3,
                               q=(qq is not None and qq <= 0.10), account=st["max_dd"] > -40_000 and st["worst_month"] > -13_000)
            st["passes"] = all(st["gates"].values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps({v: {k: out["variants"][v].get(k) for k in ("n", "win", "avg_net", "avg_net_x2", "total", "hit_4x", "q", "passes")}
                      for v in VARIANTS}, indent=1, default=str))
    print("stops", out["stops"], "claim", out["claim_move"])


if __name__ == "__main__":
    run()
