"""R94 idea 5 (PREREG 3.5): does the US overnight move tell us which way MCX goes after its 09:00 open, after costs?

Written 21 Oct 2026. A labelled PROXY and a RE-TEST of R2 (R2's holdout, Oct 2025 - Oct 2026, covers this window).
Signal source: the US benchmark (Dukascopy 1-minute BID candles: XAUUSD, XAGUSD, LIGHTCMDUSD = WTI, GASCMDUSD = Henry
Hub; spot / CFD prices, not COMEX or NYMEX prints) and the MCX near future itself (the median option-row `spot` of
the MONTH book, close-only; R53). Trade: 1 lot of the MCX mini future.

Per MCX session day d:
    prev  = the MCX close of the last minute of the previous session in the book
    u     = ln(US close of the 09:04 IST minute / US close of the minute of prev)           (US overnight move)
    g     = ln(MCX close of the 09:05 minute / prev)                                       (what MCX has priced)
    e     = u - g                                                                           (not yet priced; 1:1)
    G1 catch-up:  |e| >= 1.0 x sd(e) of the previous 40 sessions -> trade sign(e)
    G2 follow-US: |u| >= 0.5 x sd(u) of the previous 40 sessions -> trade sign(u)
    entry: the 09:06 minute close + half-spread; exit: the 14:00 minute close; hard stop 0.5 x ATR14 (daily continuous,
    rows before d) from the entry price, checked on minute closes 09:07-14:00, filled at that close - half-spread.

A US price "at" a minute = the close of the last US minute starting at or before it, at most 10 minutes earlier; a
day without one, or without MCX minutes at 09:05 and 09:06, is skipped and counted.

    python -m research.hunt.r94_mcx_advanced.usgap design      # design only (5 Aug 2025 - 31 Mar 2026); no later row
                                                                # is loaded
    python -m research.hunt.r94_mcx_advanced.usgap holdout     # the ONE holdout read (the parent runs it, after BH)
"""
from __future__ import annotations

import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r94_mcx_advanced import common as C

# signal book -> (US proxy, traded mini, its units, the daily continuous series for ATR14)
PAIRS = {"GOLDM": ("XAUUSD", "GOLDM", 10.0, "GOLDM"), "SILVERM": ("XAGUSD", "SILVERM", 5.0, "SILVERM"),
         "CRUDEOIL": ("LIGHTCMDUSD", "CRUDEOILM", 10.0, "CRUDEOIL"),
         "NATURALGAS": ("GASCMDUSD", "NATGASMINI", 250.0, "NATURALGAS")}
CELL_NAME = {"GOLDM": "GOLDM", "SILVERM": "SILVERM", "CRUDEOIL": "CRUDEOILM", "NATURALGAS": "NATGASMINI"}
RULES = ("G1", "G2")
K_SD = {"G1": 1.0, "G2": 0.5}
LOOKBACK = 40
US_STALE = 10                  # minutes
T_US, T_G, T_IN, T_OUT, T_OPEN = 9 * 60 + 4, 9 * 60 + 5, 9 * 60 + 6, 14 * 60, 9 * 60
STOP_ATR = 0.5
DESIGN = (date(2025, 8, 5), date(2026, 3, 31))
HOLDOUT = (date(2026, 4, 1), date(2026, 10, 19))
FOLDS = [(date(2025, 8, 5), date(2025, 10, 31)), (date(2025, 11, 1), date(2025, 12, 31)),
         (date(2026, 1, 1), date(2026, 2, 28)), (date(2026, 3, 1), date(2026, 3, 31))]


# ------------------------------------------------------------------------------------------------ loaders
def mcx_series(book: str, end: date | None = None) -> pd.Series:
    """The book's near-future minute close (median option-row spot), naive IST index, rows before `end` only."""
    from research.hunt.r53.mcx.data import lake_options
    raw = lake_options(book, C.lake_dir())
    if end is not None:
        raw = raw[raw.ts < pd.Timestamp(end)]
    return raw[raw.spot > 0].groupby("ts").spot.median().sort_index().astype(float)


def us_series(sym: str, end: date | None = None) -> pd.Series:
    """Dukascopy BID minute closes, index = minute start in naive IST, rows before `end` only."""
    d = pd.read_parquet(C.cache_dir() / "duka" / f"{sym}.parquet")
    ix = pd.to_datetime(d.t.values, unit="s") + pd.Timedelta(hours=5, minutes=30)
    s = pd.Series(d.close.values.astype(float), index=ix).sort_index()
    s = s[~s.index.duplicated(keep="last")]
    if end is not None:
        s = s[s.index < pd.Timestamp(end)]
    return s


def daily_atr(sym: str, end: date | None = None) -> pd.Series:
    """ATR14 known BEFORE each day: index = the day it is used on (the next row's date), value = mean true range of
    the 14 rows ending the row before. Roll rows (open interest > 3x the previous row's) use high - low."""
    d = pd.read_parquet(C.lake_dir() / "fut_daily_continuous" / "fut_daily_continuous.parquet")
    d = d[(d.symbol == sym) & (d.close > 0)].copy()
    d["date"] = pd.to_datetime(d.date).dt.normalize()
    d = d.drop_duplicates("date", keep="last").sort_values("date").reset_index(drop=True)
    if end is not None:
        d = d[d.date < pd.Timestamp(end)].reset_index(drop=True)
    return atr_before(d)


def atr_before(d: pd.DataFrame) -> pd.Series:
    """d: date, high, low, close, oi (sorted). Returns ATR14 indexed by each row's date, using rows strictly before."""
    pc = d.close.shift(1)
    tr = np.maximum(d.high - d.low, np.maximum((d.high - pc).abs(), (d.low - pc).abs()))
    roll = (d.oi / d.oi.shift(1)) > 3
    tr = tr.where(~roll & pc.notna(), d.high - d.low)
    atr = tr.rolling(14, min_periods=14).mean().shift(1)
    return pd.Series(atr.values, index=pd.DatetimeIndex(d.date))


# ------------------------------------------------------------------------------------------------ the day table
def price_at(s: pd.Series, t: pd.Timestamp, stale: int = US_STALE) -> float:
    """Close of the last minute starting at or before t, at most `stale` minutes earlier; NaN otherwise."""
    i = s.index.searchsorted(t, side="right") - 1
    if i < 0 or t - s.index[i] > pd.Timedelta(minutes=stale):
        return float("nan")
    return float(s.iloc[i])


def day_table(F: pd.Series, U: pd.Series) -> pd.DataFrame:
    """One row per MCX session day with prev close, the 09:00 / 09:05 / 09:06 / 14:00 MCX closes, u, u_open (US move
    to 08:59, descriptive), g, gap09 (descriptive), e, and the skip reason (None when usable)."""
    days = pd.DatetimeIndex(F.index).normalize()
    tod = F.index.hour * 60 + F.index.minute
    rows = []
    ud = sorted(set(days))
    first_ix = {}
    last_ts = {}
    for d, g in pd.Series(np.arange(len(F)), index=days).groupby(level=0):
        first_ix[d] = g.values
        last_ts[d] = F.index[g.values[-1]]
    for k, d in enumerate(ud):
        r = {"day": d, "skip": None}
        ix = first_ix[d]
        m = dict(zip(tod[ix], F.values[ix]))
        if k == 0:
            r["skip"] = "no_prev"
            rows.append(r)
            continue
        pt = last_ts[ud[k - 1]]
        prev = float(F.loc[pt])
        r.update(prev=prev, prev_ts=pt, f0900=m.get(T_OPEN, np.nan), f0905=m.get(T_G, np.nan),
                 f0906=m.get(T_IN, np.nan), f1400=m.get(T_OUT, np.nan))
        us_prev = price_at(U, pt)
        us_now = price_at(U, d + pd.Timedelta(minutes=T_US))
        us_open = price_at(U, d + pd.Timedelta(minutes=T_OPEN - 1))
        r["u"] = np.log(us_now / us_prev) if us_prev > 0 and us_now > 0 else np.nan
        r["u_open"] = np.log(us_open / us_prev) if us_prev > 0 and us_open > 0 else np.nan
        r["g"] = np.log(r["f0905"] / prev) if np.isfinite(r["f0905"]) else np.nan
        r["gap09"] = np.log(r["f0900"] / prev) if np.isfinite(r["f0900"]) else np.nan
        r["e"] = r["u"] - r["g"]
        if not np.isfinite(r["f0905"]) or not np.isfinite(r["f0906"]):
            r["skip"] = "no_mcx_0905_0906"
        elif not np.isfinite(r["u"]):
            r["skip"] = "no_us_minute"
        rows.append(r)
    return pd.DataFrame(rows).set_index("day")


def signals(tab: pd.DataFrame, rule: str) -> pd.Series:
    """Side (+1 / -1 / 0) per day. sd over the previous LOOKBACK usable sessions (today excluded); 0 until warm."""
    x = tab["e" if rule == "G1" else "u"].where(tab.skip.isna())
    ok = x.dropna()
    sd = ok.rolling(LOOKBACK, min_periods=LOOKBACK).std().shift(1).reindex(tab.index)
    side = np.where(x.abs() >= K_SD[rule] * sd, np.sign(x), 0.0)
    return pd.Series(np.nan_to_num(side), index=tab.index).astype(int)


# ------------------------------------------------------------------------------------------------ one trade
def simulate(day_F: pd.Series, side: int, atr: float, mini: str, units: float) -> dict | None:
    """day_F: the session's minute closes (naive IST index). Entry at the 09:06 close, exit at the 14:00 close (or the
    last minute before it), hard stop 0.5 x atr from the entry price on minute closes after entry."""
    tod = day_F.index.hour * 60 + day_F.index.minute
    p = day_F.values
    a = np.nonzero(tod == T_IN)[0]
    if not len(a) or not np.isfinite(atr) or atr <= 0 or side == 0:
        return None
    a = int(a[0])
    after = np.nonzero((tod > T_IN) & (tod <= T_OUT))[0]
    entry_px = float(p[a])
    stop = entry_px - side * STOP_ATR * atr
    why, b = "time", (int(after[-1]) if len(after) else a)
    for j in after:
        if side * (p[j] - stop) <= 0:
            why, b = "stop", int(j)
            break
    exit_px = float(p[b])
    hs_in = C.fut_half_spread(mini, entry_px)
    hs_out = C.fut_half_spread(mini, exit_px)
    fill_in = entry_px + side * hs_in
    fill_out = exit_px - side * hs_out
    buy_rs, sell_rs = (fill_in, fill_out) if side > 0 else (fill_out, fill_in)
    charges = C.fut_leg_charges(buy_rs * units, sell_rs * units)
    gross = side * (exit_px - entry_px) * units
    net = side * (fill_out - fill_in) * units - charges
    risk = (STOP_ATR * atr + hs_in + C.fut_half_spread(mini, stop)) * units + C.fut_leg_charges(
        entry_px * units, stop * units)
    return {"entry_ts": day_F.index[a], "exit_ts": day_F.index[b], "side": int(side), "gross": float(gross),
            "net": float(net), "why": why, "risk_rs": float(risk), "margin_rs": C.FUT_MARGIN[mini],
            "entry_px": entry_px, "exit_px": exit_px, "atr": float(atr)}


# ------------------------------------------------------------------------------------------------ cells
def run_book(book: str, window: tuple, end: date | None, seed: int = C.SEED):
    """All trades of both rules for one book inside `window`, using data before `end` only. Also returns, per trade,
    the other side's net (for the random-side twins), the day table and skip counts."""
    us, mini, units, dsym = PAIRS[book]
    F = mcx_series(book, end)
    U = us_series(us, end)
    atr = daily_atr(dsym, end)
    tab = day_table(F, U)
    days_ix = pd.DatetimeIndex(F.index).normalize()
    out = {}
    for rule in RULES:
        sig = signals(tab, rule)
        trades, other = [], []
        for d, s in sig.items():
            if not (window[0] <= d.date() <= window[1]) or s == 0:
                continue
            dF = F[days_ix == d]
            a = float(atr.get(d, np.nan))
            t = simulate(dF, int(s), a, mini, units)
            o = simulate(dF, -int(s), a, mini, units)
            if t is None or o is None:
                continue
            trades.append(t)
            other.append(o["net"])
        out[rule] = (trades, other)
    w = tab[(tab.index >= pd.Timestamp(window[0])) & (tab.index <= pd.Timestamp(window[1]))]
    skips = {str(k): int(v) for k, v in w.skip.value_counts().items()}
    skips["sessions"] = int(len(w))
    return out, tab, skips


def twins(trades, other, n=C.N_TWINS, seed=C.SEED) -> np.ndarray:
    """Random side on the same days and times: each trade keeps its own net or takes the other side's."""
    a = np.array([t["net"] for t in trades])
    b = np.array(other)
    if not len(a):
        return np.zeros(0)
    rng = np.random.default_rng(seed)
    flip = rng.integers(0, 2, size=(n, len(a))).astype(bool)
    return np.where(flip, b[None, :], a[None, :]).sum(axis=1)


def descriptive(tab: pd.DataFrame, window) -> dict:
    w = tab[(tab.index >= pd.Timestamp(window[0])) & (tab.index <= pd.Timestamp(window[1]))]
    a = w[["u_open", "gap09"]].dropna()
    ok = w[w.skip.isna()].copy()
    ok["rest"] = np.log(ok.f1400 / ok.f0905)
    b = ok[["e", "rest"]].dropna()
    return {"corr_u_gap0900": float(a.u_open.corr(a.gap09)) if len(a) > 2 else None, "n_u_gap0900": int(len(a)),
            "corr_e_0905_1400": float(b.e.corr(b.rest)) if len(b) > 2 else None, "n_e_rest": int(len(b))}


def cells(part: str) -> dict:
    """{cell_id: {trades, p, folds, extra}} for part 'design' (data before 1 Apr 2026 only) or 'holdout'."""
    window, end = (DESIGN, HOLDOUT[0]) if part == "design" else (HOLDOUT, None)
    if part == "holdout":
        C.open_holdout("idea5", [f"g-{r}-{CELL_NAME[b]}" for b in PAIRS for r in RULES])
    res = {}
    for book in PAIRS:
        out, tab, skips = run_book(book, window, end)
        desc = descriptive(tab, window)
        for rule in RULES:
            trades, other = out[rule]
            tw = twins(trades, other)
            p = C.twin_p(sum(t["net"] for t in trades), tw) if trades else 1.0
            res[f"g-{rule}-{CELL_NAME[book]}"] = {
                "trades": trades, "p": p,
                "folds": C.fold_nets(trades, FOLDS) if part == "design" else None,
                "extra": {"skips": skips, **desc,
                          "stops": int(sum(t["why"] == "stop" for t in trades)),
                          "longs": int(sum(t["side"] > 0 for t in trades))}}
    return res


def main(part: str):
    res = cells(part)
    with open(C.cache_dir() / f"idea5_{part}_trades.pkl", "wb") as f:
        pickle.dump({k: v["trades"] for k, v in res.items()}, f)
    out = {}
    for k, v in res.items():
        s = C.summary(v["trades"])
        out[k] = {"summary": s, "p": v["p"], "folds": v["folds"], "extra": v["extra"],
                  "signal_source": "US proxy (Dukascopy) + the MCX future itself; trade = MCX mini future"}
    C.save(f"{part}_idea5.json", out)
    for k, v in out.items():
        s = v["summary"]
        print(f"{k:18s} n {s['trades']:4d} win {s['win_rate'] if s['win_rate'] is None else round(s['win_rate'], 3)}"
              f" net {s['net']:10.0f} p {v['p']:.3f} folds {v['folds']}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
