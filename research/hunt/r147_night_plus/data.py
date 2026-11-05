"""R147 data (PREREG 1-2): one record per night d -> next session for each index, from the Drive lake.

    python -m research.hunt.r147_night_plus.data build      # writes <OUT>/nights_<UND>.pkl (all periods)

Generalises R27's builder (research/hunt/r27/r27data.py, Night (R3) rebuilt exactly) to 5 indices, with R47's
foreign-row filter and R146's fill guard. Decision features read ONLY minutes up to 15:19 of d (and earlier sessions):
`decision_features` blanks every later row before reading. The next session's rows are kept only for the gap and the
09:16 sale. Nothing about the outcome is summarised here.
"""
from __future__ import annotations

import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

from . import cfg as K

C, H, L, O = K.C, K.H, K.L, K.O


# ================================================================================================ readers
def _read(path, cols=None) -> pd.DataFrame:
    df = pd.read_parquet(path, columns=cols)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["d"] = ts.dt.date
    df["m"] = (ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)).astype(np.int64)
    df = df[(df.m >= 0) & (df.m < K.N)]
    return df.drop(columns=["ts"])


def grid(g: pd.DataFrame, cols, n=K.N) -> np.ndarray:
    """(n, len(cols)) rows = minutes; NaN where no bar."""
    a = np.full((n, len(cols)), np.nan)
    g = g[g.m < n]
    a[g.m.values] = g[cols].values.astype(float)
    return a


def index_days(name: str) -> dict:
    parts = [_read(p, ["open", "high", "low", "close", "ts"]) for p in sorted(K.LAKE.glob(f"index_1m_{name}_*.parquet"))]
    df = pd.concat(parts, ignore_index=True).drop_duplicates(["d", "m"], keep="first")
    df = df[(df[["open", "high", "low", "close"]] > 0).all(axis=1)]
    return {d: grid(g, ["open", "high", "low", "close"]) for d, g in df.groupby("d", sort=True)}


def last_valid(a: np.ndarray, t: int, back: int = 10) -> float:
    seg = a[max(0, t - back): t + 1]
    ok = np.isfinite(seg)
    return float(seg[ok][-1]) if ok.any() else np.nan


def first_valid(a: np.ndarray, t: int, ahead: int) -> tuple[int, float]:
    seg = a[t: t + ahead]
    ok = np.nonzero(np.isfinite(seg))[0]
    return (t + int(ok[0]), float(seg[ok[0]])) if len(ok) else (-1, np.nan)


# ================================================================================================ breadth (R27)
def breadth_59() -> pd.Series:
    """Share of the lake's stocks whose 15:19 price (last bar 15:10-15:19) is above their previous session's last
    close; NaN under BREADTH_MIN priced. (R27's breadth_59.)"""
    out, prev_last = [], None
    for p in sorted((K.LAKE / "equity_1m").glob("equity_1m_*.parquet")):
        df = _read(p, ["symbol", "close", "ts"])
        df["symbol"] = df.symbol.astype(str)
        df = df.sort_values(["symbol", "d", "m"])
        last = df.groupby(["symbol", "d"]).close.last().unstack("symbol").sort_index()
        w = df[(df.m >= K.R1519 - 9) & (df.m <= K.R1519)]
        at = w.groupby(["symbol", "d"]).close.last().unstack("symbol").reindex(index=last.index, columns=last.columns)
        if prev_last is not None:
            prev_last = prev_last.reindex(columns=last.columns)
            last = pd.concat([prev_last.tail(1), last])
            at = pd.concat([prev_last.tail(1) * np.nan, at])
        pc = last.shift(1)
        up = (at > pc).astype(float).where(at.notna() & pc.notna())
        n = up.notna().sum(axis=1)
        s = (up.sum(axis=1) / n.replace(0, np.nan)).where(n >= K.BREADTH_MIN)
        if prev_last is not None:
            s = s.iloc[1:]
        out.append(s)
        prev_last = last
    s = pd.concat(out)
    return s[~s.index.duplicated(keep="last")].sort_index()


# ================================================================================================ options
def drop_foreign(o: pd.DataFrame, ix_close: pd.Series, step: float) -> pd.DataFrame:
    """R47: a row whose spot is > 3% from the index's close that minute (another index's row), or whose strike is
    > 12 steps from its spot, is dropped. ix_close: indexed by (d, m)."""
    o = o[(o.strike - o.spot).abs() <= 12 * step]
    ref = ix_close.reindex(pd.MultiIndex.from_arrays([o.d, o.m])).to_numpy()
    ok = np.isfinite(ref) & (np.abs(o.spot.to_numpy() / np.where(np.isfinite(ref), ref, 1.0) - 1) <= K.FOREIGN)
    return o[ok]


def opt_frames(und: str, series: str, keep, ix_close: pd.Series) -> pd.DataFrame:
    parts = []
    for p in sorted(K.LAKE.glob(f"opt_{und}_{series}_*.parquet")):
        o = _read(p, ["offset", "side", "oi", "strike", "spot", "open", "high", "low", "close", "ts"])
        o = o[keep(o)]
        o["side"] = o.side.astype(str).str[0].str.upper()
        for c in ("open", "high", "low", "close", "spot", "strike", "oi"):
            o[c] = o[c].astype(np.float64)
        parts.append(drop_foreign(o, ix_close, K.STEP[und]))
    df = pd.concat(parts, ignore_index=True)
    return df.drop_duplicates(["d", "m", "strike", "side"], keep="first")


def tv_1515(o: pd.DataFrame) -> pd.Series:
    x = o[(o.offset == 0) & (o.m == K.R1515)]
    p = x.pivot_table(index="d", columns="side", values="close", aggfunc="first")
    s = x.groupby("d").agg(spot=("spot", "first"), k=("strike", "first"))
    j = p.join(s)
    return ((j.C + j.P - (j.spot - j.k).abs()) / j.spot * 100).dropna().sort_index()


def expiry_days(tv: pd.Series) -> set:
    """r25 / r27: ATM time value < 0.6% of spot at 15:15 and the next session's > 2x it (the series rolls)."""
    tv = tv.sort_index()
    nxt = tv.shift(-1)
    ok = (tv < K.TV_MAX) & (nxt > K.TV_JUMP * tv.clip(lower=1e-6)) & nxt.notna()
    return {d for d, v in ok.items() if v}


def atm(x: float, step: float) -> float:
    return float(np.floor(x / step + 0.5) * step)


def buildup(day: pd.DataFrame, x: float, step: float) -> float:
    """NightRules.buildUp at 15:19 over ATM +-2 (ATM = rint(x / step)), each strike's sign(call close change since
    09:15) x |call OI change| less the put's, over the strikes' call + put OI now (r27data.buildup)."""
    a0 = np.rint(x / step) * step
    num, den = 0.0, 0.0
    for k in a0 + step * np.arange(-2, 3):
        legs = []
        for s in ("C", "P"):
            g = day[(day.strike == k) & (day.side == s)]
            f = g[g.m == 0]
            t = g[(g.m <= K.R1519) & (g.m >= K.R1519 - 9)].sort_values("m")
            legs.append((f.close.iloc[0] if len(f) else np.nan, t.close.iloc[-1] if len(t) else np.nan,
                         f.oi.iloc[0] if len(f) else np.nan, t.oi.iloc[-1] if len(t) else np.nan))
        (c0, cT, o0, oT), (p0, pT, q0, qT) = legs
        if np.all(np.isfinite([c0, cT, o0, oT, p0, pT, q0, qT])):
            num += np.sign(cT - c0) * abs(oT - o0) - np.sign(pT - p0) * abs(qT - q0)
        if np.isfinite(oT) and np.isfinite(qT):
            den += oT + qT
    return num / den if den > 0 else np.nan


def straddle(day: pd.DataFrame, x: float, step: float) -> float:
    k = atm(x, step)
    v = []
    for s in ("C", "P"):
        g = day[(day.strike == k) & (day.side == s) & (day.m <= K.R1519) & (day.m >= K.R1519 - 5)].sort_values("m")
        v.append(g.close.iloc[-1] if len(g) else np.nan)
    return float(sum(v) / x) if np.all(np.isfinite(v)) else np.nan


def zscore(value: float, hist: list) -> float:
    h = [v for v in hist if np.isfinite(v)][-K.Z_WINDOW:]
    if len(h) < K.Z_MIN or not np.isfinite(value):
        return np.nan
    sd = float(np.std(h, ddof=1))
    return (value - float(np.mean(h))) / sd if sd > 0 else np.nan


def strong_close(loc: float, move: float, br: float) -> int:
    if not (np.isfinite(br) and np.isfinite(move)):
        return 0
    if loc >= K.LOC_UP and move >= 0 and br >= K.BREADTH_MID:
        return 1
    if loc <= K.LOC_DOWN and move <= 0 and br <= K.BREADTH_MID:
        return -1
    return 0


def r3_side(strong: int, buz: float) -> int:
    z = buz if np.isfinite(buz) else 0.0
    return strong if strong != 0 and strong * z >= K.BUZ_MIN else 0


# ================================================================================================ the decision
def decision_features(a_today: np.ndarray, prev_close: float) -> dict:
    """Index-only decision facts at 15:19 from the day's (375, 4) minutes. Rows after 15:19 are blanked first."""
    a = np.array(a_today, dtype=float, copy=True)
    a[K.R1519 + 1:] = np.nan
    x = last_valid(a[:, C], K.R1519)
    hi, lo = np.nanmax(a[:K.R1519 + 1, H]), np.nanmin(a[:K.R1519 + 1, L])
    o0 = first_valid(a[:, O], 0, 5)[1]
    return {"x": x, "loc": (x - lo) / (hi - lo) if hi > lo else 0.5, "move": x / prev_close - 1,
            "l30": x / last_valid(a[:, C], K.R1449) - 1, "l60": x / last_valid(a[:, C], K.R1419) - 1,
            "gap0": o0 / prev_close - 1, "rng": hi / lo - 1}


def guard_ok(a: np.ndarray, r: int) -> bool:
    """R146 NOTES A2: stray only if > 20% from its own close AND from the next bar's open (within 3 minutes).
    a: (rows, >=4) O H L C."""
    o, c = a[r, O], a[r, C]
    if not (np.isfinite(o) and o > 0):
        return False
    if np.isfinite(c) and c > 0 and abs(o / c - 1) <= K.FILL_GUARD:
        return True
    nxt = a[r + 1: r + 4, O]
    nxt = nxt[np.isfinite(nxt)]
    return bool(nxt.size and abs(o / nxt[0] - 1) <= K.FILL_GUARD)


def price_leg(g0: pd.DataFrame, g1: pd.DataFrame, k: float, side: int) -> tuple[float, float, str]:
    """(raw entry open 15:20-15:22, raw 09:16 open (fall forward to 09:45), why). NaN with a reason if not priced."""
    r = "C" if side > 0 else "P"
    e = grid(g0[(g0.strike == k) & (g0.side == r)], ["open", "high", "low", "close"])
    er = next((t for t in K.ENTRY_ROWS if np.isfinite(e[t, O])), None)
    if er is None:
        return np.nan, np.nan, "no_entry_bar"
    if not guard_ok(e, er):
        return np.nan, np.nan, "fill_guard_entry"
    x = grid(g1[(g1.strike == k) & (g1.side == r)], ["open", "high", "low", "close"], K.MORN + 4)
    xr, xp = first_valid(x[:, O], K.X16, K.EXIT_LAST - K.X16 + 1)
    if xr < 0:
        return np.nan, np.nan, "no_exit_bar"
    if not guard_ok(x, xr):
        return np.nan, np.nan, "fill_guard_exit"
    return float(e[er, O]), xp, "ok"


def leg_extra(g0: pd.DataFrame, g1: pd.DataFrame, k: float, side: int) -> tuple:
    """R148: the next session's 09:15 bar (O H L C) and the entry day's lowest low after the entry bar (15:21-15:29).
    The 09:15 OPEN is replaced by the bar's close when it fails the guard (R146 NOTES A3). NaN where missing."""
    r = "C" if side > 0 else "P"
    e = grid(g0[(g0.strike == k) & (g0.side == r)], ["open", "high", "low", "close"])
    er = next((t for t in K.ENTRY_ROWS if np.isfinite(e[t, O])), None)
    low_after = float(np.nanmin(e[er + 1: K.R1529 + 1, L])) if er is not None and np.isfinite(e[er + 1: K.R1529 + 1, L]).any() else np.nan
    x = grid(g1[(g1.strike == k) & (g1.side == r)], ["open", "high", "low", "close"], K.MORN + 4)
    o, h, lo, c = (float(v) for v in x[0])
    if np.isfinite(o) and not guard_ok(x, 0):
        o = c
    return o, h, lo, c, low_after


# ================================================================================================ build
def daily_close(df: pd.DataFrame | None, d: date) -> float:
    if df is None or d not in df.index:
        return 0.0
    i = df.index.get_loc(d)
    return float(np.log(df.close.iloc[i] / df.close.iloc[i - 1])) if i > 0 else 0.0


def yahoo(name: str) -> pd.DataFrame | None:
    p = K.YAHOO / f"{name}.csv"
    if not p.exists():
        return None
    df = pd.read_csv(p, parse_dates=["date"])
    df["date"] = df.date.dt.date
    return df.set_index("date")


def events() -> tuple[set, set]:
    ev = pd.read_csv(K.EVENTS)
    ev["session"] = pd.to_datetime(ev["session"]).dt.date
    return (set(ev.loc[ev.category.isin(["FOMC", "USCPI"]), "session"]),
            set(ev.loc[ev.category.isin(["RBI", "BUDGET", "ELECTION"]), "session"]))


def build(und: str, vix: dict, br: pd.Series, hsi, spx, us_ev: set, in_ev: set) -> list[dict]:
    step = K.STEP[und]
    ixs = index_days(und)
    ixc = pd.Series({(d, m): a[m, C] for d, a in ixs.items() for m in range(K.N) if np.isfinite(a[m, C])})
    sess = [d for d in sorted(ixs) if np.isfinite(last_valid(ixs[d][:, C], K.R1519))]
    near = opt_frames(und, K.NEAR_SERIES[und], lambda o: (o.m == 0) | ((o.m >= K.R1519 - 9) & (o.m <= K.R1519))
                      | (o.m == K.R1515), ixc)
    near_exp = expiry_days(tv_1515(near))
    if K.TRADE_SERIES[und] == K.NEAR_SERIES[und]:
        trade_src = opt_frames(und, K.TRADE_SERIES[und], lambda o: (o.m < K.MORN + 4) | (o.m >= K.R1519 - 9), ixc)
        trade_exp = near_exp
    else:
        trade_src = opt_frames(und, K.TRADE_SERIES[und], lambda o: (o.m < K.MORN + 4) | (o.m >= K.R1519 - 9)
                               | (o.m == K.R1515), ixc)
        trade_exp = expiry_days(tv_1515(trade_src))
    first_opt = near.d.min()
    nd_by, td_by = dict(tuple(near.groupby("d"))), dict(tuple(trade_src.groupby("d")))
    hist, strad_hist, out = [], [], []
    for i in range(1, len(sess) - 1):
        d, nd, pd_ = sess[i], sess[i + 1], sess[i - 1]
        if d < first_opt:
            continue
        pc = last_valid(ixs[pd_][:, C], K.N - 1, 30)
        f = decision_features(ixs[d], pc)
        x = f["x"]
        bu = buildup(nd_by[d], x, step) if d in nd_by else np.nan
        z = zscore(bu, hist)
        if np.isfinite(bu):
            hist.append(bu)
        sd = straddle(nd_by[d], x, step) if d in nd_by else np.nan
        sh = [v for v in strad_hist if np.isfinite(v)][-K.F8_WINDOW:]
        strad_ratio = sd / float(np.median(sh)) if len(sh) >= K.Z_MIN and np.isfinite(sd) else np.nan
        strad_hist.append(sd)
        b = float(br.get(d, np.nan))
        s17 = strong_close(f["loc"], f["move"], b)
        s3 = r3_side(s17, z)
        if d in near_exp or d in trade_exp:
            ok, why = False, "entry_on_expiry_day"
        elif nd in trade_exp:
            ok, why = False, "contract_expires_next_session"
        else:
            ok, why = True, ""
        vt = vix.get(d)
        vprev = last_valid(vix[pd_][:, C], K.N - 1, 30) if pd_ in vix else np.nan
        v1519 = last_valid(vt[:, C], K.R1519) if vt is not None else np.nan
        o_next = first_valid(ixs[nd][:, O], 0, 5)[1]
        rec = {"und": und, "d": d, "nd": nd, "part": K.part(d), "qty": K.lot(und, d), **f, "prev_close": pc,
               "breadth": b, "bu": bu, "buz": z, "straddle": sd, "strad_ratio": strad_ratio,
               "vix": v1519, "dvix": np.log(v1519 / vprev) if np.isfinite(vprev) and vprev > 0 else np.nan,
               "hsi": daily_close(hsi, d), "ov_spx": daily_close(spx, d),
               "wkend": (nd - d).days > 1, "us_ev": nd in us_ev, "in_ev": nd in in_ev,
               "exp_today": d in near_exp, "s17": s17, "r3": s3, "tradable": ok, "why": why,
               "g": float(np.log(o_next / x)) if np.isfinite(o_next) and np.isfinite(x) else np.nan,
               "g29": float(np.log(o_next / last_valid(ixs[d][:, C], K.N - 1, 30))) if np.isfinite(o_next) else np.nan,
               "legs": {}}
        if ok and d in td_by and nd in td_by:
            for side in (1, -1):
                for itm in (0, 1, 2):                       # ATM, 1-ITM (Night), 2-ITM
                    k = atm(x, step) - side * itm * step
                    rec["legs"][(side, itm)] = (k, *price_leg(td_by[d], td_by[nd], k, side))
                    rec.setdefault("extra", {})[(side, itm)] = leg_extra(td_by[d], td_by[nd], k, side)
        out.append(rec)
    return out


def main(argv) -> None:
    if argv[:1] != ["build"]:
        sys.exit(__doc__)
    K.OUT.mkdir(parents=True, exist_ok=True)
    vix = index_days("INDIAVIX")
    br = breadth_59()
    print("breadth sessions", int(br.notna().sum()), br.index.min(), br.index.max(), flush=True)
    hsi, spx = yahoo("HSI"), yahoo("GSPC")
    us_ev, in_ev = events()
    unds = argv[1:] or list(K.UNDS)
    for u in unds:
        ns = build(u, vix, br, hsi, spx, us_ev, in_ev)
        (K.OUT / f"nights_{u}.pkl").write_bytes(pickle.dumps(ns))
        print(u, "nights", len(ns), ns[0]["d"], ns[-1]["d"], "R3 signals", sum(1 for n in ns if n["r3"] != 0),
              "tradable", sum(1 for n in ns if n["tradable"]), flush=True)


if __name__ == "__main__":
    main(sys.argv[1:])
