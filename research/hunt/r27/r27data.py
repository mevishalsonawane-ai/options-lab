"""R27 data: one record per NIGHT (entry session d -> next session nd) of NIFTY and BANKNIFTY, built from the lake.

    python3 research/hunt/r27/r27data.py build        # writes <OUT>/nights_<UND>.pkl (every night, all periods)

Each record holds what R3 (NightRules / research/hunt/x2/PREREG.md) reads at 15:19 on d (close location, the day's move,
breadth, the day-end option OI build-up and its z-score), the 1-ITM call and put R3 would buy for both sides (so a
coin-flip side is exact), their 15:20 entry prices, and the next session's first 136 minutes (09:15 .. 11:30) of the
index, India VIX and both options. Nothing about the outcome is computed here.

Grid: row t = the 1-minute bar that STARTS at 09:15 + t (375 rows); row t is known once that minute is over (t + 1).
Nothing is filled: a minute without a bar is NaN.

Data limits (PREREG.md, "Data"):
  - Breadth: the lake has 59 stocks (NIFTY 50 + NIFTY BANK), not R3's 214 F&O stocks. R27 reads the share of those 59
    above their previous close at 15:19 (minute data, no look-ahead) with at least 40 priced.
  - The OI build-up's z-score history starts with the lake (Aug 2021): its first 20 sessions have no z (no trade).
  - The rolling option series hold ATM-10 .. ATM+10 of each minute; a strike outside that range has no rows.
"""
from __future__ import annotations

import pickle
import sys
from dataclasses import dataclass, field
from datetime import date

import numpy as np
import pandas as pd

import r27cfg as K

N = 375
MORN = 136                      # next-session rows kept: 09:15 .. 11:30
O, H, L, C = range(4)
R1519, R1520, R1515, R1529 = 364, 365, 360, 374
TV_MAX, TV_JUMP = 0.6, 2.0      # expiry day: ATM time value at 15:15 below 0.6% of spot, next session's above 2x (r25)
BREADTH_MIN = 40
Z_WINDOW, Z_MIN = 60, 20
LOC_UP, LOC_DOWN, BREADTH_MID, BUZ_MIN = 0.6, 0.4, 0.5, 0.5


def minute(hh: int, mm: int) -> int:
    """Grid row of the bar that STARTS at hh:mm."""
    return hh * 60 + mm - (9 * 60 + 15)


@dataclass
class Night:
    und: str
    d: date
    nd: date
    qty: int
    x: float                         # index close of d's 15:19 bar
    prev_close: float                # index close of the session before d (its last bar)
    d_hi: float                      # d's high / low to 15:19 (close location)
    d_lo: float
    d_close: float                   # d's last bar close (15:29): the reference for the next open's gap
    d_high_all: float                # d's full-day high / low (for "where the next open sits vs yesterday")
    d_low_all: float
    loc: float
    move: float
    breadth: float
    bu: float                        # h26 BUopen at 15:19 (nearest series, ATM +-2)
    buz: float                       # its z against the previous 60 sessions (NaN under 20)
    r17: int
    r3: int                          # +1 call, -1 put, 0 no trade (also 0 when the contract rules forbid it)
    tradable: bool                   # contract rules: d not an expiry day; the contract expires after nd
    expiry_ok_reason: str
    strike: dict = field(default_factory=dict)     # side -> strike (1-ITM from x)
    entry: dict = field(default_factory=dict)      # side -> raw 15:20 open (first bar 15:20 .. 15:23), NaN if none
    last_d: dict = field(default_factory=dict)     # side -> (close, oi) of d's last bar <= 15:29
    ix: np.ndarray | None = None                   # (MORN, 4) next-session index O H L C
    vix: np.ndarray | None = None                  # (MORN,) next-session VIX close
    vix_prev: float = np.nan                       # VIX close of d (last bar)
    opt: dict = field(default_factory=dict)        # side -> (MORN, 5) next-session option O H L C OI
    x_nifty: float = np.nan                        # NIFTY's 15:19 close on d (BANKNIFTY's point scaling)


# ------------------------------------------------------------------------------------------------ readers
def _read(path, cols=None) -> pd.DataFrame:
    df = pd.read_parquet(path, columns=cols)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["d"] = ts.dt.date
    df["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
    df = df[(df.m >= 0) & (df.m < N)]
    return df.drop(columns=["ts"])


def _grid(g: pd.DataFrame, cols, n=N) -> np.ndarray:
    a = np.full((n, len(cols)), np.nan)
    g = g[g.m < n]
    a[g.m.values] = g[cols].values.astype(float)
    return a


def index_days(name: str) -> dict:
    parts = [_read(p) for p in sorted(K.LAKE.glob(f"index_1m_{name}_*.parquet"))]
    df = pd.concat(parts, ignore_index=True).drop_duplicates(["d", "m"], keep="first")
    return {d: _grid(g, ["open", "high", "low", "close"]) for d, g in df.groupby("d", sort=True)}


def last_valid(a: np.ndarray, t: int, back: int = 10) -> float:
    seg = a[max(0, t - back): t + 1]
    ok = np.isfinite(seg)
    return float(seg[ok][-1]) if ok.any() else np.nan


def first_valid(a: np.ndarray, t: int, ahead: int) -> tuple[int, float]:
    seg = a[t: t + ahead]
    ok = np.nonzero(np.isfinite(seg))[0]
    return (t + int(ok[0]), float(seg[ok[0]])) if len(ok) else (-1, np.nan)


# ------------------------------------------------------------------------------------------------ breadth
def breadth_59() -> pd.Series:
    """Share of the lake's stocks whose 15:19 price (last bar 15:10 .. 15:19) is above their previous session's last
    close, per session; NaN under BREADTH_MIN priced."""
    out = []
    prev_last = None
    for p in sorted((K.LAKE / "equity_1m").glob("equity_1m_*.parquet")):
        df = _read(p, ["symbol", "close", "ts"])
        df["symbol"] = df.symbol.astype(str)
        df = df.sort_values(["symbol", "d", "m"])
        last = df.groupby(["symbol", "d"]).close.last().unstack("symbol").sort_index()
        w = df[(df.m >= R1519 - 9) & (df.m <= R1519)]
        at = w.groupby(["symbol", "d"]).close.last().unstack("symbol").reindex(index=last.index, columns=last.columns)
        if prev_last is not None:
            last = pd.concat([prev_last.tail(1), last])
            at = pd.concat([prev_last.tail(1) * np.nan, at])
        pc = last.shift(1)
        up = (at > pc).astype(float).where(at.notna() & pc.notna())
        n = up.notna().sum(axis=1)
        s = (up.sum(axis=1) / n.replace(0, np.nan)).where(n >= BREADTH_MIN)
        if prev_last is not None:
            s = s.iloc[1:]
        out.append(s)
        prev_last = last
    s = pd.concat(out)
    return s[~s.index.duplicated(keep="last")].sort_index()


# ------------------------------------------------------------------------------------------------ options
def _opt_frames(und: str, series: str, keep) -> pd.DataFrame:
    parts = []
    for p in sorted(K.LAKE.glob(f"opt_{und}_{series}_*.parquet")):
        o = _read(p, ["offset", "side", "oi", "strike", "spot", "open", "high", "low", "close", "ts"])
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[(o.strike - o.spot).abs() <= 12 * K.STEP[und]]          # mislabelled rows (r25data)
        o = o[keep(o)]
        for c in ("open", "high", "low", "close", "spot", "strike"):
            o[c] = o[c].astype(np.float64)
        o["oi"] = o.oi.astype(np.float64)
        parts.append(o)
    df = pd.concat(parts, ignore_index=True)
    return df.drop_duplicates(["d", "m", "strike", "side"], keep="first")


def tv_1515(o: pd.DataFrame) -> pd.Series:
    x = o[(o.offset == 0) & (o.m == R1515)]
    p = x.pivot_table(index="d", columns="side", values="close", aggfunc="first")
    s = x.groupby("d").agg(spot=("spot", "first"), k=("strike", "first"))
    j = p.join(s)
    return ((j.C + j.P - (j.spot - j.k).abs()) / j.spot * 100).dropna().sort_index()


def expiry_days(tv: pd.Series) -> set:
    """r25data.expiry_days: tv < TV_MAX and the next session's tv > TV_JUMP x its own (the series rolls)."""
    tv = tv.sort_index()
    nxt = tv.shift(-1)
    ok = (tv < TV_MAX) & (nxt > TV_JUMP * tv.clip(lower=1e-6)) & nxt.notna()
    return {d for d, v in ok.items() if v}


def buildup(day: pd.DataFrame, x: float, step: float) -> float:
    """h26 BUopen at 15:19 (NightRules.buildUp): over ATM +-2 of the 15:19 close x (ATM = rint(x / step) x step), each
    strike's sign(call close change since 09:15) x |call OI change| less the put's, over the strikes' call + put OI
    now. 'now' = the last bar at or before 15:19 (from 15:10); '09:15' = the 09:15 bar itself."""
    atm = np.rint(x / step) * step
    num, den = 0.0, 0.0
    for k in atm + step * np.arange(-2, 3):
        legs = []
        for s in ("C", "P"):
            g = day[(day.strike == k) & (day.side == s)]
            f = g[g.m == 0]
            t = g[(g.m <= R1519) & (g.m >= R1519 - 9)].sort_values("m")
            legs.append((f.close.iloc[0] if len(f) else np.nan, t.close.iloc[-1] if len(t) else np.nan,
                         f.oi.iloc[0] if len(f) else np.nan, t.oi.iloc[-1] if len(t) else np.nan))
        (c0, cT, o0, oT), (p0, pT, q0, qT) = legs
        if np.all(np.isfinite([c0, cT, o0, oT, p0, pT, q0, qT])):
            num += np.sign(cT - c0) * abs(oT - o0) - np.sign(pT - p0) * abs(qT - q0)
        if np.isfinite(oT) and np.isfinite(qT):
            den += oT + qT
    return num / den if den > 0 else np.nan


def zscore(value: float, hist: list) -> float:
    """NightRules.z: value against the last Z_WINDOW finite values of hist (sample sd); NaN under Z_MIN or no spread."""
    h = [v for v in hist if np.isfinite(v)][-Z_WINDOW:]
    if len(h) < Z_MIN or not np.isfinite(value):
        return np.nan
    sd = float(np.std(h, ddof=1))
    return (value - float(np.mean(h))) / sd if sd > 0 else np.nan


def strong_close(loc: float, move: float, br: float) -> int:
    if not np.isfinite(br):
        return 0
    if loc >= LOC_UP and move >= 0 and br >= BREADTH_MID:
        return 1
    if loc <= LOC_DOWN and move <= 0 and br <= BREADTH_MID:
        return -1
    return 0


def r3_side(strong: int, buz: float) -> int:
    z = buz if np.isfinite(buz) else 0.0
    return strong if strong != 0 and strong * z >= BUZ_MIN else 0


def atm(x: float, step: float) -> float:
    """OrbRules.atmStrike: floor(x / step + 0.5) x step."""
    return float(np.floor(x / step + 0.5) * step)


def build(und: str, ixs=None, vix=None, br=None, nifty=None) -> list[Night]:
    step = K.STEP[und]
    ixs = ixs if ixs is not None else index_days(und)
    vix = vix if vix is not None else index_days("INDIAVIX")
    br = br if br is not None else breadth_59()
    nifty = nifty if nifty is not None else (ixs if und == "NIFTY" else index_days("NIFTY"))
    sess = [d for d in sorted(ixs) if np.isfinite(ixs[d][R1519, C]) or np.isfinite(last_valid(ixs[d][:, C], R1519))]
    near = _opt_frames(und, K.NEAR_SERIES[und], lambda o: (o.m == 0) | ((o.m >= R1519 - 9) & (o.m <= R1519)) | (o.m == R1515))
    near_exp = expiry_days(tv_1515(near))
    trade = _opt_frames(und, K.TRADE_SERIES[und], lambda o: (o.m < MORN) | (o.m >= R1519 - 9))
    trade_exp = expiry_days(tv_1515(trade))
    first_opt = min(trade.d.min(), near.d.min())
    nd_by = dict(tuple(near.groupby("d")))
    td_by = dict(tuple(trade.groupby("d")))
    hist: list = []
    out = []
    for i in range(1, len(sess) - 1):
        d, nd, pd_ = sess[i], sess[i + 1], sess[i - 1]
        if d < first_opt:
            continue
        a = ixs[d]
        x = last_valid(a[:, C], R1519)
        pc = last_valid(ixs[pd_][:, C], N - 1, 30)
        hi, lo = np.nanmax(a[:R1519 + 1, H]), np.nanmin(a[:R1519 + 1, L])
        loc = (x - lo) / (hi - lo) if hi > lo else 0.5
        move = x / pc - 1 if np.isfinite(pc) else np.nan
        b = float(br.get(d, np.nan))
        bu = buildup(nd_by[d], x, step) if d in nd_by else np.nan
        z = zscore(bu, hist)
        if np.isfinite(bu):
            hist.append(bu)
        s17 = strong_close(loc, move, b) if np.isfinite(move) else 0
        s3 = r3_side(s17, z)
        # contract rules (NightRules.expiry): no entry on an expiry day of the index (any series); the traded series'
        # nearest contract must expire after the next session (never held into its expiry day)
        if d in near_exp or d in trade_exp:
            ok, why = False, "entry_on_expiry_day"
        elif nd in trade_exp:
            ok, why = False, "contract_expires_next_session"
        else:
            ok, why = True, ""
        n = Night(und, d, nd, K.lot(und, d), x, pc, hi, lo, last_valid(a[:, C], N - 1, 30),
                  float(np.nanmax(a[:, H])), float(np.nanmin(a[:, L])), loc, move, b, bu, z, s17, s3 if ok else 0,
                  ok, why)
        nx = last_valid(nifty[d][:, C], R1519) if d in nifty else np.nan
        n.x_nifty = nx
        if ok and d in td_by and nd in td_by:
            g0, g1 = td_by[d], td_by[nd]
            for side in (1, -1):
                k = atm(x, step) - side * step
                r = "C" if side > 0 else "P"
                e0 = g0[(g0.strike == k) & (g0.side == r)]
                ea = _grid(e0, ["open", "high", "low", "close", "oi"])
                _, e = first_valid(ea[:, O], R1520, 4)
                n.strike[side] = k
                n.entry[side] = e
                n.last_d[side] = (last_valid(ea[:, C], R1529, 30), last_valid(ea[:, 4], R1529, 30))
                e1 = g1[(g1.strike == k) & (g1.side == r)]
                n.opt[side] = _grid(e1, ["open", "high", "low", "close", "oi"], MORN)
            n.ix = ixs[nd][:MORN].copy()
            n.vix = vix[nd][:MORN, C].copy() if nd in vix else np.full(MORN, np.nan)
            n.vix_prev = last_valid(vix[d][:, C], N - 1, 30) if d in vix else np.nan
        out.append(n)
    return out


def path(und: str):
    return K.OUT / f"nights_{und}.pkl"


def load(und: str, parts=("desc",)) -> list[Night]:
    """parts: any of 'desc', 'wf', 'hold'. Only study.py holdout may ask for 'hold'."""
    ns = pickle.loads(path(und).read_bytes())
    return [n for n in ns if K.part(n.d) in parts]


def main(argv):
    if argv[:1] != ["build"]:
        sys.exit(__doc__)
    K.OUT.mkdir(parents=True, exist_ok=True)
    vix = index_days("INDIAVIX")
    br = breadth_59()
    nifty = index_days("NIFTY")
    print("breadth sessions", int(br.notna().sum()), br.index.min(), br.index.max(), flush=True)
    for u in K.UNDS:
        ixs = nifty if u == "NIFTY" else index_days(u)
        ns = build(u, ixs, vix, br, nifty)
        path(u).write_bytes(pickle.dumps(ns))
        print(u, "nights", len(ns), "R3 signals", sum(1 for n in ns if n.r3 != 0), ns[0].d, ns[-1].d, flush=True)


if __name__ == "__main__":
    import r27data                     # pickle the records as r27data.Night, not __main__.Night
    r27data.main(sys.argv[1:])
