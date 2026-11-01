"""R144d: rebuild the trades of the four MCX paper arms from their own research engines, with entry features.

Written 1 Nov 2026 for R144d (PREREG.md in this folder). Research only: nothing here trades or touches the app.

    python research/hunt/r144d_loss_nn_mcx/mcx_arms.py build [NGEVE SILM TREND USSIL]
    python -I research/hunt/r144d_loss_nn_mcx/mcx_arms.py parse-yahoo     # untrusted Yahoo JSON -> parquet

Arms and the engine code each one reuses (imported, or copied verbatim with the source named at the copy):
  NGEVE  NATURALGAS evening breakout, M3 rule EVE: research/hunt/m3/m3lib.py (Market, range_break_signals,
         sim_option, opt_charges) imported unchanged; run.py's run_book and analyze.py's costs copied verbatim.
  SILM   SILVERM morning OTM2-3 call, M4's frozen Q4 rule: research/hunt/m4/build.py and analyze.py, run through
         R42's path-only patch (research/hunt/r42/m4_rerun.py patched_build / patched_analyze, imported).
  USSIL  US-night silver, R2 rule C2 (SILVER, 09:05 -> close, SILVERMIC futures): research/hunt/r2/hourly.py (us_on,
         inr_on, px_at) and cand.py (the C2 / XEOD / FUT loop) copied verbatim; m3lib.Market imported.
  TREND  12-month trend (TSM252) on the MCX minis, M2: research/hunt/m2/prep.py (build) and engine.py (load, signal,
         run_futures, side_cost, stats) imported unchanged; the per-leg trade list follows run_futures' own
         unconstrained arithmetic (checked: the trades' nets sum to run_futures(constrained=False)).

Data: the Drive lake (~/.cache/iraalgo/lake): MCX rolling options (5 Aug 2025 - 19 Oct 2026, `spot` = the underlying
future), MCX continuous daily futures (2012 - 16 Oct 2026), MCX continuous 1-minute futures (near month only from 2026).
Yahoo 1-hour COMEX / NYMEX / USD-INR (as R2's fetch_yahoo.sh) in ~/.cache/iraalgo/ext/yahoo.

Features: one pure function per arm (features_ngeve, features_silm, features_ussil, features_trend) that is handed only
the history that ended strictly before the entry decision minute (see `truncate` and the look-ahead guard test).
Output: ~/.cache/iraalgo/r144d/trades_<ARM>.parquet; parity: results/parity_mcx.json.
"""
from __future__ import annotations

import datetime as dt
import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
HUNT = REPO / "research" / "hunt"
CACHE = Path.home() / ".cache" / "iraalgo"
LAKE = Path(os.environ.get("R144D_LAKE", CACHE / "lake"))
OUT = Path(os.environ.get("R144D_OUT", CACHE / "r144d"))
WORK = OUT / "work"
EXT = CACHE / "ext"
RES = HERE / "results"
IST = "Asia/Kolkata"
ARMS = ("NGEVE", "SILM", "TREND", "USSIL")
LAST_EXP = {"NATURALGAS": dt.date(2026, 10, 23), "SILVERM": dt.date(2026, 10, 27)}  # m4/build.py LAST_EXP (Kite 8 Oct)

# R2's US data calendar (research/hunt/r2/events.py CPI / NFP, fomc.py FOMC), plus the lake's us_events.csv
R2_CPI = """2024-06-12 2024-07-11 2024-08-14 2024-09-11 2024-10-10 2024-11-13 2024-12-11 2025-01-15 2025-02-12
2025-03-12 2025-04-10 2025-05-13 2025-06-11 2025-07-15 2025-08-12 2025-09-11 2025-10-24 2025-12-18 2026-01-13 2026-02-13
2026-03-11 2026-04-10 2026-05-12 2026-06-10 2026-07-14 2026-08-12 2026-09-11""".split()
R2_NFP = """2024-06-07 2024-07-05 2024-08-02 2024-09-06 2024-10-04 2024-11-01 2024-12-06 2025-01-10 2025-02-07
2025-03-07 2025-04-04 2025-05-02 2025-06-06 2025-07-03 2025-08-01 2025-09-05 2025-11-20 2025-12-16 2026-01-09 2026-02-11
2026-03-06 2026-04-03 2026-05-08 2026-06-05 2026-07-02 2026-08-07 2026-09-04 2026-10-02""".split()
R2_FOMC = "2025-09-17 2025-10-29 2025-12-10 2026-01-28 2026-03-18 2026-04-29 2026-06-17 2026-07-29 2026-09-16".split()
US_DATA_FIRST, US_DATA_LAST = dt.date(2024, 6, 1), dt.date(2026, 10, 19)  # outside: the calendar is not held -> NaN


def us_data_days():
    s = {dt.date.fromisoformat(x) for x in R2_CPI + R2_NFP + R2_FOMC}
    f = LAKE / "xauusd_1m" / "us_events.csv"
    if f.exists():
        s |= {dt.date.fromisoformat(x) for x in pd.read_csv(f).date.astype(str)}
    return s


def us_dst(day) -> bool:
    """US daylight time on this date (research/hunt/r2/lib.py us_dst, verbatim logic)."""
    d = pd.Timestamp(day)
    y = d.year
    mar = pd.Timestamp(y, 3, 1); s2 = mar + pd.Timedelta(days=(6 - mar.weekday()) % 7 + 7)
    nov = pd.Timestamp(y, 11, 1); s1 = nov + pd.Timedelta(days=(6 - nov.weekday()) % 7)
    return s2 <= d.normalize() < s1


def mcx_close_ts(day) -> pd.Timestamp:
    """MCX session end on `day` (IST, tz-aware): 23:30 in US summer time, 23:55 in winter (research/data/mcx.py)."""
    d = pd.Timestamp(day).normalize()
    m = 23 * 60 + (30 if us_dst(d) else 55)
    return (d + pd.Timedelta(minutes=m)).tz_localize(IST)


# ====================================================================== features (look-ahead safe)
FEATS = ["f_push", "f_rng_today", "f_trend20", "f_gap", "f_d_pdh", "f_d_pdl", "f_d_open", "f_min", "f_wd", "f_dte",
         "f_roll_days", "f_vol_rel", "f_oi_chg", "f_rng_proxy", "f_prev_rng", "f_ret5", "f_dst", "f_us_on", "f_fx_on",
         "f_us_data", "f_od", "f_oiq"]
OPT_FEATS = ["f_prem", "f_prem_pct", "f_opt_vol_rel", "f_opt_oi", "f_opt_spread", "f_opt_noTrade", "f_iv"]


def truncate(df: pd.DataFrame | None, t: pd.Timestamp, col: str = "ts", end_offset=None) -> pd.DataFrame | None:
    """Rows whose bar ENDED at or before t (1-minute bars stamped at their start: start < t; hourly bars: start + 1 h <= t)."""
    if df is None:
        return None
    if end_offset is None:
        return df[df[col] < t]
    return df[df[col] + end_offset <= t]


def _nan():
    return float("nan")


def _trading_days_between(cal: np.ndarray, a: dt.date, b: dt.date) -> float:
    """MCX trading days after a up to and including b (0 when b == a); NaN when b is unknown."""
    if b is None or a is None or pd.isna(b):
        return _nan()
    a64, b64 = np.datetime64(a), np.datetime64(b)
    return float(np.searchsorted(cal, b64, side="right") - np.searchsorted(cal, a64, side="right"))


def _same_clock_avg(df: pd.DataFrame, entry: pd.Timestamp, col: str, sessions: int = 20, win: int = 15) -> float:
    """Sum of `col` over [entry - win min, entry) today, over its average across the previous `sessions` sessions at the
    same clock minutes. NaN if today's window or the history is missing."""
    if df is None or not len(df) or col not in df:
        return _nan()
    x = df[["ts", col]].dropna()
    if not len(x):
        return _nan()
    day = entry.normalize()
    lo = entry - pd.Timedelta(minutes=win)
    now = x[(x.ts >= lo) & (x.ts < entry)]
    if not len(now):
        return _nan()
    tod = x.ts.dt.hour * 60 + x.ts.dt.minute
    t1 = entry.hour * 60 + entry.minute
    t0 = t1 - win
    past = x[(x.ts < day) & (tod >= t0) & (tod < t1)]
    if not len(past):
        return _nan()
    g = past.groupby(past.ts.dt.normalize())[col].sum()
    g = g.iloc[-sessions:]
    if len(g) < 5 or g.mean() <= 0:
        return _nan()
    return float(now[col].sum() / g.mean())


def base_features(bars: pd.DataFrame, ctx: dict) -> dict:
    """Common features on the underlying future. `bars`: 1-minute bars (ts start, close; high, low, volume, oi may be
    NaN) that ENDED before ctx['entry_ts']; ctx['daily']: daily bars (date, open, high, low, close) of days before the
    entry day. Every input is checked to end before the entry; a violation raises."""
    entry = ctx["entry_ts"]
    side = float(ctx["side"])
    f = {k: _nan() for k in FEATS}
    if bars is not None and len(bars) and (bars.ts >= entry).any():
        raise ValueError("look-ahead: a bar at or after the entry minute")
    day = entry.tz_convert(IST).normalize().tz_localize(None)
    D = ctx["daily"]
    if D is not None and len(D) and (pd.to_datetime(D.date) >= day).any():
        raise ValueError("look-ahead: a daily bar on or after the entry day")
    f["f_min"] = float(entry.hour * 60 + entry.minute)
    f["f_wd"] = float(entry.weekday())
    f["f_dst"] = float(us_dst(day))
    ud = ctx.get("us_data_days")
    if ud is not None and US_DATA_FIRST <= day.date() <= US_DATA_LAST:
        f["f_us_data"] = float(day.date() in ud)
    cal = ctx.get("cal")
    if cal is not None:
        f["f_dte"] = _trading_days_between(cal, day.date(), ctx.get("expiry"))
        f["f_roll_days"] = _trading_days_between(cal, day.date(), ctx.get("roll"))
    H = L = C = np.array([])
    if D is not None and len(D):
        D = D.sort_values("date")
        H, L, C = D.high.values.astype(float), D.low.values.astype(float), D.close.values.astype(float)
    rng = H - L
    atr = float(np.mean(rng[-14:])) if len(rng) >= 14 else _nan()
    avg20 = float(np.mean(rng[-20:])) if len(rng) >= 20 else _nan()
    if not (atr > 0):
        atr = _nan()
    b = bars if bars is not None else pd.DataFrame(columns=["ts", "close"])
    b = b[np.isfinite(b.close.astype(float))] if len(b) else b
    # price just before entry: last minute close if it is from the last 4 days, else the last daily close
    price = _nan()
    if len(b) and (entry - b.ts.iloc[-1]) <= pd.Timedelta(days=4):
        price = float(b.close.iloc[-1])
    elif len(C):
        price = float(C[-1])
    sess0 = (day + pd.Timedelta(hours=9)).tz_localize(IST)
    today = b[b.ts >= sess0] if len(b) else b
    if len(b) >= 4:
        f["f_push"] = side * (float(b.close.iloc[-1]) - float(b.close.iloc[-4])) / atr
    if len(today):
        hi = np.nanmax(np.r_[today.close.values.astype(float), today["high"].values.astype(float) if "high" in today else []])
        lo = np.nanmin(np.r_[today.close.values.astype(float), today["low"].values.astype(float) if "low" in today else []])
        f["f_rng_today"] = (hi - lo) / avg20
        op = float(today["open"].iloc[0]) if "open" in today and np.isfinite(today["open"].iloc[0]) else float(today.close.iloc[0])
        f["f_d_open"] = side * (price - op) / atr
        if len(C):
            f["f_gap"] = side * (op - C[-1]) / atr
    if len(C) >= 20:
        f["f_trend20"] = side * (price - float(np.mean(C[-20:]))) / atr
    if len(H):
        f["f_d_pdh"] = (price - H[-1]) / atr
        f["f_d_pdl"] = (price - L[-1]) / atr
        f["f_prev_rng"] = rng[-1] / avg20
    if len(C) >= 6:
        f["f_ret5"] = side * (C[-1] - C[-6]) / atr
    # volume / OI of the future (only where the lake holds the traded near-month contract)
    f["f_vol_rel"] = _same_clock_avg(b, entry, "volume") if len(b) else _nan()
    if len(today) and "oi" in today:
        oi = today.oi.astype(float).dropna()
        oi = oi[oi > 0]
        if len(oi) >= 2:
            f["f_oi_chg"] = 100.0 * (oi.iloc[-1] / oi.iloc[0] - 1)
    w = b[b.ts >= entry - pd.Timedelta(minutes=15)] if len(b) else b
    if len(w) and {"high", "low"} <= set(w.columns):
        hl = (w.high.astype(float) - w.low.astype(float)).dropna()
        if len(hl) >= 5 and price > 0:
            f["f_rng_proxy"] = float(np.median(hl)) / price
    if len(w) >= 5 and "volume" in w:
        ww = w.dropna(subset=["volume"])
        pc = b.close.astype(float).shift(1).reindex(ww.index)
        up = (ww.close.astype(float) > pc).values; dn = (ww.close.astype(float) < pc).values
        tot = ww.volume.astype(float).sum()
        if len(ww) >= 5 and tot > 0:
            f["f_od"] = side * float(ww.volume.values[up].sum() - ww.volume.values[dn].sum()) / tot
        oo = ww.oi.astype(float) if "oi" in ww else pd.Series(dtype=float)
        oo = oo[oo > 0]
        if len(ww) >= 5 and len(oo) >= 2:
            dp = side * (float(ww.close.iloc[-1]) - float(ww.close.iloc[0]))
            doi = float(oo.iloc[-1]) - float(oo.iloc[0])
            f["f_oiq"] = float(1 if dp > 0 and doi > 0 else 2 if dp > 0 else 3 if doi > 0 else 4)
    # US overnight (hourly bars that ended before the entry) and USD/INR, from the previous MCX close
    pc_ts = ctx.get("prev_close_ts")
    for key, col in (("us", "f_us_on"), ("fx", "f_fx_on")):
        y = ctx.get(key)
        if y is None or pc_ts is None or not len(y):
            continue
        if (y.ts + pd.Timedelta(hours=1) > entry).any():
            raise ValueError("look-ahead: an hourly bar that ends after the entry")
        a = y[y.ts + pd.Timedelta(hours=1) <= pc_ts]
        if not len(a) or not len(y) or pc_ts - (a.ts.iloc[-1] + pd.Timedelta(hours=1)) > pd.Timedelta(hours=3):
            continue
        now = y.iloc[-1]
        if entry - (now.ts + pd.Timedelta(hours=1)) > pd.Timedelta(hours=3) or now.ts <= a.ts.iloc[-1]:
            continue
        r = float(np.log(now.close / a.close.iloc[-1]))
        f[col] = side * r if col == "f_us_on" else r
    return f


def option_features(opt: pd.DataFrame | None, optk: pd.DataFrame | None, entry: pd.Timestamp, under_px: float) -> dict:
    """Features of the bought option: `opt` = the contract's own minutes that ended before the entry (ts, high, low,
    close, volume, oi, iv); `optk` = the rolling series at the same ATM offset and side (for the 20-session volume)."""
    f = {k: _nan() for k in OPT_FEATS}
    for d in (opt, optk):
        if d is not None and len(d) and (d.ts >= entry).any():
            raise ValueError("look-ahead: an option bar at or after the entry minute")
    if opt is None or not len(opt):
        return f
    pr = opt[opt.volume > 0]
    last = pr.iloc[-1] if len(pr) else opt.iloc[-1]
    f["f_prem"] = float(last.close)
    if under_px > 0:
        f["f_prem_pct"] = float(last.close) / under_px
    f["f_opt_oi"] = float(opt.oi.iloc[-1]) if "oi" in opt else _nan()
    if "iv" in opt and np.isfinite(opt.iv.iloc[-1]) and opt.iv.iloc[-1] > 0:
        f["f_iv"] = float(opt.iv.iloc[-1])
    w = opt[opt.ts >= entry - pd.Timedelta(minutes=15)]
    if len(w):
        f["f_opt_noTrade"] = 1.0 - float((w.volume > 0).sum()) / 15.0
        wp = w[w.volume > 0]
        if len(wp) and float(last.close) > 0:
            f["f_opt_spread"] = float(np.median(wp.high - wp.low)) / float(last.close)
    else:
        f["f_opt_noTrade"] = 1.0
    f["f_opt_vol_rel"] = _same_clock_avg(optk, entry, "volume") if optk is not None else _nan()
    return f


def features_ngeve(bars: pd.DataFrame, ctx: dict) -> dict:
    """NGEVE: common + option + the breakout's own numbers (range width, how far past the edge the signal closed)."""
    f = base_features(bars, ctx)
    px = float(bars.close.iloc[-1]) if bars is not None and len(bars) else _nan()
    f.update(option_features(ctx.get("opt"), ctx.get("optk"), ctx["entry_ts"], px))
    D = ctx["daily"]
    atr = float(np.mean((D.high - D.low).values[-14:])) if D is not None and len(D) >= 14 else _nan()
    s = ctx["sig"]
    f["f_eve_width"] = (s["hi"] - s["lo"]) / atr
    f["f_brk_over"] = ctx["side"] * (s["sigF"] - s["level"]) / (s["hi"] - s["lo"])
    f["f_sig_min"] = float(s["sig_min"])
    f["f_entry_lag"] = float(s["entry_lag"])
    return f


def features_silm(bars: pd.DataFrame, ctx: dict) -> dict:
    """SILM: common + option + the strike's distance (steps, % of the future)."""
    f = base_features(bars, ctx)
    px = float(bars.close.iloc[-1]) if bars is not None and len(bars) else _nan()
    f.update(option_features(ctx.get("opt"), ctx.get("optk"), ctx["entry_ts"], px))
    f["f_k"] = float(ctx["k"])
    f["f_mny"] = (float(ctx["K"]) - px) / px if px > 0 else _nan()
    f["f_grid_n"] = float(ctx["grid_n"])
    return f


def features_ussil(bars: pd.DataFrame, ctx: dict) -> dict:
    """USSIL: common + R2's own signal pieces (us_on, inr_on read at 08:30 IST; s signed with the trade = |s|) and the
    MCX move from the previous close to the last minute before 09:05."""
    f = base_features(bars, ctx)
    s = ctx["sig"]
    side = ctx["side"]
    f["f_s"] = side * s["fair"]
    f["f_us_on_r2"] = side * s["us_on"]
    f["f_inr_on_r2"] = s["inr_on"]
    f["f_inr_missing"] = float(not np.isfinite(s["inr_on"]))
    px = float(bars.close.iloc[-1]) if bars is not None and len(bars) else _nan()
    f["f_mcx_pre"] = side * np.log(px / s["p_prev"]) if px > 0 and s["p_prev"] > 0 else _nan()
    f["f_vs_fair"] = f["f_mcx_pre"] - f["f_s"]
    return f


def features_trend(bars: pd.DataFrame, ctx: dict) -> dict:
    """TREND: common (on the leg's big contract) + the engine's own state at the decision close (12-month move in the
    roll-free series, 60-day vol, carry estimate, days since the last roll, entry kind)."""
    f = base_features(bars, ctx)
    s = ctx["sig"]
    f.update({"f_ret252": s["ret252"], "f_vol60": s["vol60"], "f_carry": s["carry"], "f_days_since_roll": s["dsr"],
              "f_reentry_roll": s["reentry"], "f_leg": s["leg"]})
    return f


# ====================================================================== data
def lake_daily(sym: str) -> pd.DataFrame:
    d = pd.read_parquet(LAKE / "fut_daily_continuous" / "fut_daily_continuous.parquet")
    d = d[d.symbol == sym].copy()
    d["date"] = pd.to_datetime(d.date)
    d = d[(d.close > 0) & (d.high >= d.low)].sort_values("date").drop_duplicates("date")
    return d[["date", "open", "high", "low", "close", "volume", "oi"]].reset_index(drop=True)


_CONT = None


def lake_cont_minutes(sym: str) -> pd.DataFrame:
    """Continuous 1-minute futures (lake), kept only on days it holds the near month (near_share >= 0.5)."""
    global _CONT
    if _CONT is None:
        _CONT = pd.read_parquet(LAKE / "fut_1m_mcx_continuous" / "fut_1m_mcx_continuous.parquet")
        _CONT["symbol"] = _CONT.symbol.astype(str)
    x = _CONT[_CONT.symbol == sym].copy()
    x["d"] = x.ts.dt.normalize()
    ok = x.groupby("d").near_share.transform("first") >= 0.5
    x = x[ok]
    return x[["ts", "open", "high", "low", "close", "volume", "oi"]].sort_values("ts").reset_index(drop=True)


def lake_options(sym: str) -> pd.DataFrame:
    fs = sorted((LAKE / "options_rolling_1m").glob(f"opt_{sym}_MONTH_202[56].parquet"))
    d = pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True)
    d["side"] = d.side.astype(str)
    d = d[d.offset.between(-3, 3)]
    return d.sort_values(["ts", "offset", "side"]).reset_index(drop=True)


def prep_m3raw(sym: str, root: Path, spot_rule: str = "m3") -> None:
    """M3's raw layout from the lake (research/hunt/m3/fetch_mcx.py: k, cp, OHLC, iv, oi, volume, strike as float32; spot
    from the ATM call, else the ATM put). spot_rule 'r42' = R42's lake rebuild (median spot of the minute)."""
    (root).mkdir(parents=True, exist_ok=True)
    d = lake_options(sym)
    o = pd.DataFrame({"ts": d.ts, "k": d.offset.astype(np.int8), "cp": np.where(d.side == "C", 0, 1).astype(np.int8)})
    for c in ("open", "high", "low", "close", "iv", "volume", "oi", "strike"):
        o[c] = d[c].astype("float32").values
    o.to_parquet(root / f"opt_{sym}.parquet", index=False)
    if spot_rule == "m3":
        a = d[d.offset == 0][["ts", "spot", "side"]].copy()
        a["src"] = np.where(a.side == "C", 0, 1)
        sp = a.sort_values(["ts", "src"]).drop_duplicates("ts")[["ts", "spot"]]
        sp["spot"] = sp.spot.astype("float32")
    else:
        sp = d.groupby("ts").spot.median().rename("spot").reset_index()
    sp.to_parquet(root / f"spot_{sym}.parquet", index=False)


def yahoo_h() -> pd.DataFrame:
    f = OUT / "yahoo_h.parquet"
    if not f.exists():
        raise FileNotFoundError("run: python -I mcx_arms.py parse-yahoo")
    return pd.read_parquet(f)


def parse_yahoo(src: Path = EXT / "yahoo", dst: Path = OUT / "yahoo_h.parquet") -> None:
    """r2/parse.py yjson, verbatim logic: Yahoo chart JSON (untrusted, run with python -I) -> naive-UTC hourly bars."""
    out = []
    for p in sorted(src.glob("h_*.json")):
        j = json.loads(p.read_text())["chart"]["result"][0]
        q = j["indicators"]["quote"][0]
        df = pd.DataFrame({k: q.get(k) for k in ("open", "high", "low", "close", "volume")})
        df["ts"] = pd.to_datetime(j["timestamp"], unit="s")
        df = df.dropna(subset=["close"]).sort_values("ts")
        df["sym"] = p.stem[2:]
        out.append(df)
    a = pd.concat(out, ignore_index=True)
    a = a[(a.ts.dt.minute == 0) & (a.ts.dt.second == 0)]  # drop Yahoo's live partial bar (e.g. 20:59:59)
    dst.parent.mkdir(parents=True, exist_ok=True)
    a.to_parquet(dst, index=False)
    print(a.groupby("sym").ts.agg(["min", "max", "count"]).to_string())


def us_bars(sym: str) -> pd.DataFrame | None:
    """Hourly bars of a Yahoo symbol as tz-aware IST start times (for the features)."""
    y = yahoo_h()
    y = y[y.sym == sym].drop_duplicates("ts", keep="last").sort_values("ts")
    if not len(y):
        return None
    y = y.assign(ts=y.ts.dt.tz_localize("UTC").dt.tz_convert(IST))
    return y[["ts", "open", "close"]].reset_index(drop=True)


class Ctx:
    """Shared, pre-sorted inputs for the per-trade feature contexts of one underlying."""

    def __init__(self, sym: str, spot: pd.DataFrame | None, us_sym: str | None, cal_days, expiry_of=None, roll_of=None):
        self.sym = sym
        self.daily = lake_daily(sym)
        cont = lake_cont_minutes(sym)
        if spot is not None:  # the engine's own price series, with the future's volume / OI / high / low where held
            b = spot.merge(cont[["ts", "high", "low", "volume", "oi"]], on="ts", how="left")
            b["open"] = np.nan
        else:
            b = cont
        self.bars = b.sort_values("ts").reset_index(drop=True)
        self.bts = self.bars.ts.values
        self.us = us_bars(us_sym) if us_sym else None
        self.fx = us_bars("INR_X")
        self.udd = us_data_days()
        cal = pd.DatetimeIndex(sorted(set(pd.to_datetime(list(cal_days)))))
        ext = pd.bdate_range(cal[-1] + pd.Timedelta(days=1), cal[-1] + pd.Timedelta(days=120))
        self.cal = np.array(cal.append(ext).values, dtype="datetime64[D]")
        self.cal_days = cal
        self.expiry_of = expiry_of or (lambda d: None)
        self.roll_of = roll_of or (lambda d: None)

    def _roll(self, d):
        """The next roll day: the builder's own, else the first calendar trading day after the expiry."""
        r = self.roll_of(d)
        e = self.expiry_of(d)
        if r is None and e is not None:
            i = np.searchsorted(self.cal, np.datetime64(e), side="right")
            r = pd.Timestamp(self.cal[i]).date() if i < len(self.cal) else None
        return r

    def prev_close_ts(self, day: pd.Timestamp):
        prev = self.cal_days[self.cal_days < day.normalize()]
        return mcx_close_ts(prev[-1]) if len(prev) else None

    def make(self, entry: pd.Timestamp, side: int, **extra) -> tuple[pd.DataFrame, dict]:
        lo = np.searchsorted(self.bts, np.datetime64((entry - pd.Timedelta(days=35)).tz_convert("UTC").tz_localize(None)))
        hi = np.searchsorted(self.bts, np.datetime64(entry.tz_convert("UTC").tz_localize(None)))
        bars = self.bars.iloc[lo:hi]
        day = entry.tz_convert(IST).normalize().tz_localize(None)
        ctx = dict(entry_ts=entry, side=side, daily=self.daily[self.daily.date < day].tail(40),
                   cal=self.cal, expiry=self.expiry_of(day.date()), roll=self._roll(day.date()),
                   prev_close_ts=self.prev_close_ts(day), us_data_days=self.udd)
        for key, y in (("us", self.us), ("fx", self.fx)):
            if y is not None:
                z = y[(y.ts >= entry - pd.Timedelta(days=6))]
                ctx[key] = truncate(z, entry, end_offset=pd.Timedelta(hours=1))
        ctx.update(extra)
        return truncate(bars, entry), ctx


def option_slices(L: pd.DataFrame, cp_side: str, strike: float, offset: int, entry: pd.Timestamp, seg_start) -> tuple:
    """The bought contract's minutes in its expiry segment before the entry, and the same-offset rolling series."""
    w = L[(L.ts >= entry - pd.Timedelta(days=35)) & (L.ts < entry)]
    c = w[(w.side == cp_side) & (np.abs(w.strike - strike) < 1e-6) & (w.ts >= seg_start)]
    c = c.sort_values(["ts", "volume"], ascending=[True, False]).drop_duplicates("ts")
    k = w[(w.side == cp_side) & (w.offset == offset)].drop_duplicates("ts")
    return truncate(c, entry), truncate(k, entry)


def finish(rows: list, arm: str) -> pd.DataFrame:
    T = pd.DataFrame(rows)
    T.insert(0, "arm", arm)
    T.insert(1, "tid", np.arange(len(T), dtype=np.int64))
    fcols = sorted(c for c in T.columns if c.startswith("f_"))
    for c in fcols:
        T[c] = T[c].astype(float)
    base = ["arm", "tid", "day", "entry_ts", "exit_ts", "side", "instrument", "qty_lot", "entry_px", "exit_px", "net",
            "gross", "exit_why", "mfe", "mins_held"]
    T["mins_held"] = ((T.exit_ts - T.entry_ts).dt.total_seconds() / 60.0).astype(float)
    T["side"] = T.side.astype(int)
    T["tid"] = T.tid.astype(int)
    return T[base + fcols]


def write(T: pd.DataFrame, arm: str) -> Path:
    OUT.mkdir(parents=True, exist_ok=True)
    p = OUT / f"trades_{arm}.parquet"
    T.to_parquet(p, index=False)
    print(arm, "rows", len(T), "days", T.day.min() if len(T) else None, "->", T.day.max() if len(T) else None, flush=True)
    return p


def summ(net: np.ndarray) -> dict:
    net = np.asarray(net, float)
    if not len(net):
        return dict(trades=0)
    eq = np.cumsum(net)
    dd = float(np.max(np.maximum.accumulate(np.r_[0, eq])[1:] - eq))
    return dict(trades=int(len(net)), net_total=float(net.sum()), net_per_trade=float(net.mean()),
                win_rate=float((net > 0).mean()), max_dd=dd)


# ====================================================================== NGEVE (M3 EVE)
def m3lib():
    sys.path.insert(0, str(HUNT / "m3"))
    import m3lib as M  # noqa: E402  (creates M3's /tmp work dir on import; harmless)
    M.RAW = WORK / "m3raw"
    return M


def m3_run_book(m, M, first_last, rule, sigs, inst):
    """research/hunt/m3/run.py run_book, verbatim (m, M, first_last passed in instead of module globals)."""
    rows, busy_until = [], -1
    for s in sorted(sigs, key=lambda s: s["e"]):
        if s["e"] <= busy_until:
            continue
        d = m.day[s["e"]]
        if inst == "OPT" and m.is_exp[s["e"]]:
            continue
        cut = m.day_cut[d]
        if s.get("cut_tod"):
            a_, b_ = first_last[d]
            ix = np.arange(a_, b_ + 1); ix = ix[m.tod[ix] <= s["cut_tod"]]
            cut = min(cut, int(ix[-1]))
        if s["e"] >= cut:
            continue
        r = M.sim_option(m, s, cut) if inst == "OPT" else M.sim_fut(m, s, cut)
        if r is None:
            continue
        busy_until = s["e"] + r["hold"]
        rows.append(dict(s, **r, rule=rule, inst=inst, day=pd.Timestamp(d), tod=int(m.tod[s["e"]])))
    return rows


# research/hunt/m3/analyze.py costs(): the option half-spread it used for these trades (0.58% before / 0.29% after 17:00;
# read back from results/trades_{design,holdout}.csv.gz: cost - opt_charges = 0.0029 x mean turnover on every EVE trade).
M3_SP = {"opt": {"NATURALGAS": {"am": 0.0058, "pm": 0.0029}, "_default": {"am": 0.0058, "pm": 0.0029}}, "fut": {}}


def m3_costs(df, M, SP=M3_SP, SPREAD_X=1.0):
    """research/hunt/m3/analyze.py costs(), verbatim (SP / SPREAD_X passed in)."""
    mult = df.sym.map(M.MULT).values.astype(float)
    opt = (df.inst == "OPT").values
    buy = df.fill.values * mult; sell = df.exit.values * mult
    g_opt = sell - buy
    g_fut = df.side.values * (df.exit.values - df.fill.values) * mult
    gross = np.where(opt, g_opt, g_fut)
    am = df.sym.map(lambda s: (SP["opt"].get(s) or SP["opt"]["_default"])["am"]).values
    pm = df.sym.map(lambda s: (SP["opt"].get(s) or SP["opt"]["_default"])["pm"]).values
    rs = SPREAD_X * np.where(df.tod.values < 17 * 60, am, pm)
    c_opt = M.opt_charges(buy, sell) + rs / 2 * (buy + sell)
    fb, fs = np.where(df.side.values > 0, buy, sell), np.where(df.side.values > 0, sell, buy)
    fv = df.sym.map(lambda s: SP["fut"].get(s, np.nan)).values.astype(float)
    tick = df.sym.map(M.TICK).values.astype(float)
    fsp = SPREAD_X * np.where(np.isfinite(fv), fv, np.maximum(tick, 0.0001 * df.fill.values)) * mult
    c_fut = M.fut_charges(fb, fs) + fsp
    df = df.copy()
    df["gross"] = gross
    df["cost"] = np.where(opt, c_opt, c_fut)
    df["net"] = gross - df["cost"]
    df["prem_rs"] = np.where(opt, buy, np.nan)
    return df


def first_last_of(m):
    di = pd.Series(np.arange(len(m.ts))).groupby(m.day)
    return {d: (ix.values[0], ix.values[-1]) for d, ix in di}


def expiry_lookup(exp_days: np.ndarray, last_exp: dt.date):
    E = sorted(pd.Timestamp(x).date() for x in exp_days)

    def f(d):
        for e in E:
            if e >= d:
                return e
        return last_exp if last_exp >= d else None
    return f


def build_ngeve(parity: dict) -> pd.DataFrame:
    M = m3lib()
    prep_m3raw("NATURALGAS", WORK / "m3raw", "m3")
    m = M.Market("NATURALGAS")
    days = [pd.Timestamp(d) for d in m.days]
    win = {d.to_datetime64(): (17 * 60, 19 * 60, 22 * 60) for d in days}
    sigs = [dict(x, cut_tod=23 * 60 + 15) for x in M.range_break_signals(m, win, "EVE", 120)]
    fl = first_last_of(m)
    T = pd.DataFrame(m3_run_book(m, M, fl, "EVE", sigs, "OPT"))
    T["sym"] = "NATURALGAS"; T["und"] = "NATURALGAS"
    T = m3_costs(T, M)
    T["aff"] = (T.inst == "FUT") | (T.prem_rs <= 1e5)
    n_unaff = int((~T.aff).sum())
    T = T[T.aff].reset_index(drop=True)
    # ---- parity vs M3's published trade lists (design 6 Aug 2025 - 7 Jul 2026; holdout 8 Jul - 8 Oct 2026)
    par = {"engine": "research/hunt/m3 (m3lib imported; run_book, costs copied verbatim)", "dropped_premium_over_1L": n_unaff}
    for part, f, lo, hi in (("design", "trades_design.csv.gz", None, M.DESIGN_END), ("holdout", "trades_holdout.csv.gz", M.HOLD_START, None)):
        P = pd.read_csv(HUNT / "m3" / "results" / f)
        P = P[(P.und == "NATURALGAS") & (P.rule == "EVE") & (P.inst == "OPT")].copy()
        P["day"] = pd.to_datetime(P.day)
        lo_ = P.day.min() if lo is None else lo
        hi_ = P.day.max() if hi is None else P.day.max()
        R = T[(T.day >= lo_) & (T.day <= hi_)]
        mg = P.merge(R, on="day", suffixes=("_pub", "_new"), how="outer", indicator=True)
        both = mg[mg._merge == "both"]
        same = ((both.side_pub == both.side_new) & (np.abs(both.strike_pub - both.strike_new) < 1e-6)
                & (np.abs(both.fill_pub - both.fill_new) < 1e-3) & (np.abs(both.exit_pub - both.exit_new) < 1e-3)
                & (both.why_pub == both.why_new))
        par[part] = dict(window=[str(lo_.date()), str(hi_.date())], published=summ(P.sort_values("e").net.values),
                         rebuilt=summ(R.net.values), days_published_only=int((mg._merge == "left_only").sum()),
                         days_rebuilt_only=int((mg._merge == "right_only").sum()), days_both=int(len(both)),
                         identical_trades=int(same.sum()),
                         net_diff_on_identical=float((both.net_pub - both.net_new)[same].abs().max()) if same.any() else None,
                         differing_examples=both[~same][["day", "side_pub", "side_new", "strike_pub", "strike_new", "fill_pub",
                                                         "fill_new", "exit_pub", "exit_new", "why_pub", "why_new"]]
                         .head(8).astype(str).to_dict("records"),
                         published_only_days=[str(x.date()) for x in mg[mg._merge == "left_only"].day.head(10)],
                         rebuilt_only_days=[str(x.date()) for x in mg[mg._merge == "right_only"].day.head(10)])
    par["recost_note"] = ("net uses M3's own spread for these trades (0.29% after 17:00); M3's 9 Oct re-cost with the "
                          "measured 0.43% gave +Rs 70/trade on the holdout instead of +Rs 93")
    parity["NGEVE"] = par
    # ---- trades + features
    ts = pd.DatetimeIndex(m.ts).tz_localize(IST)
    spot = pd.DataFrame({"ts": ts, "close": m.F})
    expf = expiry_lookup(m.exp_days, LAST_EXP["NATURALGAS"])
    seg_first = {}
    for s_ in np.unique(m.seg):
        seg_first[s_] = ts[np.where(m.seg == s_)[0][0]]
    C = Ctx("NATURALGAS", spot, "NG_F", m.days, expiry_of=expf,
            roll_of=lambda d: (lambda e: None if e is None else next((x.date() for x in pd.DatetimeIndex(m.days) if x.date() > e), None))(expf(d)))
    L = lake_options("NATURALGAS")
    rows = []
    for r in T.itertuples():
        e = int(r.e)
        o, h, lo, c, v = m.leg(int(r.cp), r.strike, e, min(e + 2, len(m.ts) - 1))
        pk = [k for k in range(len(c)) if np.isfinite(c[k]) and v[k] > 0]
        k_in = pk[0]
        e2 = e + k_in
        xi = e2 + int(r.hold)
        entry_ts, exit_ts = ts[e2], ts[min(xi, len(ts) - 1)]
        o2, h2, l2, c2, v2 = m.leg(int(r.cp), r.strike, e2, xi)
        hh = h2[np.isfinite(h2) & (v2 > 0)]
        mfe = float(hh.max() / r.fill - 1) if len(hh) else _nan()
        side = int(r.side)
        sig = dict(hi=max(r.level, r.level - side * 2 * r.unit), lo=min(r.level, r.level - side * 2 * r.unit),
                   sigF=r.sigF, level=r.level, sig_min=int(m.tod[e - 1]), entry_lag=k_in)
        dec = ts[e]  # the decision minute: the minute after the signal close (fill at the first print in [e, e + 2])
        cps = "C" if r.cp == 0 else "P"
        opt, optk = option_slices(L, cps, r.strike, -1 if r.cp == 0 else 1, dec, seg_first[m.seg[e]])
        bars, ctx = C.make(dec, side, sig=sig, opt=opt, optk=optk)
        f = features_ngeve(bars, ctx)
        rows.append(dict(day=r.day.date(), entry_ts=entry_ts, exit_ts=exit_ts, side=side,
                         instrument=f"NATURALGAS {r.strike:g} {'CE' if r.cp == 0 else 'PE'} (near month, exp {expf(r.day.date())})",
                         qty_lot=float(M.MULT["NATURALGAS"]), entry_px=float(r.fill), exit_px=float(r.exit), net=float(r.net),
                         gross=float(r.gross), exit_why=str(r.why) + ("_stale" if r.stale_exit else ""), mfe=mfe, **f))
    return finish(rows, "NGEVE")


# ====================================================================== SILM (M4 Q4 via R42's patch)
def _m4_trades_for_stable(A):
    """research/hunt/m4/analyze.py trades_for, verbatim except sort_values(..., kind="stable")."""
    def trades_for(D, hn, ex):
        D = D.sort_values("ts", kind="stable")
        taken, free_at = [], None
        for i, (ts, tod) in enumerate(zip(D.ts.values, D.tod.values)):
            if free_at is not None and ts < free_at:
                continue
            taken.append(i)
            dur = A.HMIN[hn] if hn != "eod" else (23 * 60 + 25 - tod)
            free_at = ts + np.timedelta64(int(dur), "m")
        return D.iloc[taken]
    return trades_for


def build_silm(parity: dict) -> pd.DataFrame:
    sys.path.insert(0, str(HUNT / "r42"))
    import m4_rerun as R42
    root = WORK / "m4sp"
    for sub in ("m3/raw", "strad_crude/cache", "h28", "m4/work", "results"):
        (root / sub).mkdir(parents=True, exist_ok=True)
    pd.DataFrame(columns=["date", "kind"]).to_csv(root / "h28" / "events.csv", index=False)  # events unused by Q4 (R42)
    prep_m3raw("SILVERM", root / "m3" / "raw", "r42")
    (root / "build_r42.py").write_text(R42.patched_build())
    (root / "analyze_r42.py").write_text(R42.patched_analyze())
    for f in ("spread_estimates.json", "q1_cells_CRUDEOIL_NATURALGAS_GOLDM_SILVERM.csv"):
        (root / "results" / f).write_bytes((HUNT / "m4" / "results" / f).read_bytes())
    env = dict(os.environ, R42_SP=str(root), R42_ONLY="1", R42_RES=str(root / "results"))
    if not (root / "m4" / "work" / "ent_SILVERM.parquet").exists() or os.environ.get("R144D_REBUILD"):
        subprocess.run([sys.executable, "-I", str(root / "build_r42.py"), "SILVERM"], env=env, check=True)
    os.environ["R42_SP"], os.environ["R42_RES"] = str(root), str(root / "results")
    spec = importlib.util.spec_from_file_location("m4_analyze_r144d", root / "analyze_r42.py")
    A = importlib.util.module_from_spec(spec); spec.loader.exec_module(A)
    E = A.load("SILVERM")
    # M4's trades_for sorts with pandas' default (unstable) quicksort, so when OTM2 and OTM3 both have a price at one grid
    # time, which one is bought depends on the row layout (it changed between M4's run, R42's and this one). Ties are
    # resolved here as the app does (McxMorningRules: OTM2, else OTM3): rows ordered by (ts, k), then a stable sort.
    E = E.sort_values(["ts", "k"], kind="stable").reset_index(drop=True)
    A.trades_for = _m4_trades_for_stable(A)
    cell = ("SILVERM", "OTM2-3 call", "11-20", "morning")
    T, _ = A.run_rule(E, cell, "h240", "E1")
    T = T.sort_values("ts").reset_index(drop=True)
    # ---- parity
    par = {"engine": "research/hunt/m4 build.py + analyze.py (R42's path-only patch, R42_ONLY=1: the frozen cell's candidates only)"}
    pub = pd.read_csv(HUNT / "m4" / "results" / "q4_holdout_trades.csv")
    pub["ts"] = pd.to_datetime(pub.ts)
    d = pd.read_csv(HUNT / "m4" / "results" / "q4_design_CRUDEOIL_NATURALGAS_GOLDM_SILVERM.csv")
    d = d[d.rule == "passing cell: SILVERM OTM2-3 call dte11-20 morning h240 E1"].iloc[0]
    Td = T[T.day < A.HOLD0]
    par["design"] = dict(window=["2025-08-05", "2026-07-07"],
                         published=dict(trades=int(d.trades), net_per_trade=float(d.net_tr), win_rate=float(d.hit),
                                        net_total=float(d.net_tot), max_dd=float(d.maxdd)),
                         rebuilt=summ(Td.net.values),
                         r42_lake_rebuild=dict(trades=95, net_per_trade=871.32, win_rate=0.568))
    Th = T[(T.day >= A.HOLD0) & (T.day <= pd.Timestamp("2026-10-07"))]
    pts = pd.DatetimeIndex(pub.ts).tz_convert(IST) if pd.DatetimeIndex(pub.ts).tz is not None else pd.DatetimeIndex(pub.ts).tz_localize(IST)
    nts = pd.DatetimeIndex(Th.ts).tz_convert(IST) if pd.DatetimeIndex(Th.ts).tz is not None else pd.DatetimeIndex(Th.ts).tz_localize(IST)
    pub = pub.assign(ts=pts); Th2 = Th.assign(ts=nts)
    mg = pub.merge(Th2[["ts", "K", "P0", "h240_P", "net"]], on="ts", how="outer", suffixes=("_pub", "_new"), indicator=True)
    both = mg[mg._merge == "both"]
    same = (np.abs(both.K_pub - both.K_new) < 1e-6) & (np.abs(both.P0_pub - both.P0_new) < 1e-3) & (np.abs(both.h240_P_pub - both.h240_P_new) < 1e-2)
    sameK = np.abs(both.K_pub - both.K_new) < 1e-6
    par["holdout_to_7oct"] = dict(window=["2026-07-08", "2026-10-07"], published=summ(pub.net.values), rebuilt=summ(Th.net.values),
                                  r42_lake_rebuild=dict(trades=51, net_per_trade=465.66, win_rate=0.529),
                                  entries_both=int(len(both)), identical_trades=int(same.sum()),
                                  same_strike=int(sameK.sum()),
                                  same_strike_other_exit_price=int((sameK & ~same).sum()),
                                  why_different=("strike: M4's unstable sort picked OTM3 where OTM2 also had a price (here OTM2, "
                                                 "the app's rule; 3 trades); exit price (3 trades): where one source has no print in the "
                                                 "exit minute M4 prices it by B76 at the day's filled IV, so the lake's fuller "
                                                 "minutes give a real print or a different fallback"),
                                  published_only=[str(x) for x in mg[mg._merge == "left_only"].ts.head(10)],
                                  rebuilt_only=[str(x) for x in mg[mg._merge == "right_only"].ts.head(10)])
    parity["SILM"] = par
    # ---- trades + features
    Mn = pd.read_parquet(root / "m4" / "work" / "min_SILVERM.parquet")
    mts = pd.DatetimeIndex(Mn.index)
    mts = mts.tz_convert(IST) if mts.tz is not None else mts.tz_localize(IST)
    mday = Mn.day.values
    spot = pd.DataFrame({"ts": mts, "close": Mn.F.values.astype(float)})
    exp_by_day = {pd.Timestamp(k).date(): pd.Timestamp(v).date() for k, v in Mn.groupby("day").expiry.first().items()}
    days_sorted = sorted(exp_by_day)
    roll_by_day = {}
    for i, dd in enumerate(days_sorted):
        nxt = [x for x in days_sorted[i + 1:] if exp_by_day[x] != exp_by_day[dd]]
        roll_by_day[dd] = nxt[0] if nxt else None
    C = Ctx("SILVERM", spot, "SI_F", days_sorted, expiry_of=exp_by_day.get, roll_of=roll_by_day.get)
    L = lake_options("SILVERM")
    rows = []
    gridn = T.groupby("day").cumcount().values
    for i, r in enumerate(T.itertuples()):
        ent = pd.Timestamp(r.ts); ent = ent.tz_convert(IST) if ent.tzinfo else ent.tz_localize(IST)
        dd = ent.normalize().tz_localize(None)
        dix = np.where(mday == np.datetime64(dd))[0]
        pos = int(np.searchsorted(mts[dix].values, np.datetime64(ent.tz_convert("UTC").tz_localize(None))))
        cut_i = int(np.searchsorted(Mn.tod.values[dix], 23 * 60 + 25, side="right")) - 1
        j = min(pos + 240, cut_i)
        exit_ts = mts[dix[j]]
        seg_start = mts[dix[0]]
        c = L[(L.side == "C") & (np.abs(L.strike - r.K) < 1e-6) & (L.ts > ent) & (L.ts <= exit_ts) & (L.volume > 0)]
        mfe = float(c.high.max() / r.P0 - 1) if len(c) else _nan()
        opt, optk = option_slices(L, "C", r.K, int(r.k), ent, seg_start)
        bars, ctx = C.make(ent, 1, opt=opt, optk=optk, k=int(r.k), K=float(r.K), grid_n=int(gridn[i]))
        f = features_silm(bars, ctx)
        why = "four_hours" + ("_b76_fallback_price" if bool(r.h240_fb) else "")
        rows.append(dict(day=dd.date(), entry_ts=ent, exit_ts=exit_ts, side=1,
                         instrument=f"SILVERM {r.K:g} CE (near month, exp {exp_by_day.get(dd.date())}, ATM+{int(r.k)})",
                         qty_lot=float(r.mult), entry_px=float(r.P0), exit_px=float(r.h240_P), net=float(r.net),
                         gross=float(r.gross), exit_why=why, mfe=mfe, **f))
    return finish(rows, "SILM")


# ====================================================================== USSIL (R2 C2)
R2_HOLD_START, R2_HOLD_END = pd.Timestamp("2025-10-01"), pd.Timestamp("2026-10-06")  # research/hunt/r2/lib.py
R2_MINI_SILVER_UNITS = 1  # lib.py MINI["SILVER"] = ("SILVERMIC", 1)
R2_FUT_SPREAD = 0.0003


def r2_fut_charges(buy_rs, sell_rs):
    """research/hunt/r2/lib.py fut_charges, verbatim."""
    brok = np.minimum(20, 0.0003 * buy_rs) + np.minimum(20, 0.0003 * sell_rs)
    ctt = 0.0001 * sell_rs; txn = 0.000021 * (buy_rs + sell_rs); sebi = 1e-6 * (buy_rs + sell_rs)
    stamp = 0.00002 * buy_rs; gst = 0.18 * (brok + txn + sebi)
    return brok + ctt + txn + sebi + stamp + gst


def r2_fut_pnl(side, p_in, p_out, u=R2_MINI_SILVER_UNITS):
    """research/hunt/r2/lib.py fut_pnl, verbatim (com fixed to SILVER: u = 1)."""
    gross = side * (p_out - p_in) * u
    notional_in, notional_out = p_in * u, p_out * u
    cost = r2_fut_charges(notional_in, notional_out) + R2_FUT_SPREAD * 0.5 * (notional_in + notional_out)
    return gross, gross - cost


def r2_px_at(y, t, col="open"):
    """research/hunt/r2/hourly.py px_at, verbatim."""
    i = y.index.searchsorted(t)
    if i < len(y) and y.index[i] == t:
        return y[col].iloc[i]
    if i > 0 and t - y.index[i - 1] <= pd.Timedelta(hours=2):
        return y["close"].iloc[i - 1]
    return np.nan


def r2_hourly(daily_dates: pd.DatetimeIndex, LO, HI) -> pd.DataFrame:
    """research/hunt/r2/hourly.py, the SILVER us_on / inr_on part, verbatim logic (prev = the previous MCX daily date)."""
    yh = yahoo_h()

    def us_hourly(sym):
        y = yh[yh.sym == sym].set_index("ts").sort_index()
        return y[~y.index.duplicated(keep="last")]
    y, inr = us_hourly("SI_F"), us_hourly("INR_X")
    idx = daily_dates[(daily_dates >= max(LO, y.index.min().normalize() + pd.Timedelta(days=2))) & (daily_dates <= HI)]
    rec, prev = [], None
    for D in idx:
        if prev is None:
            prev = D; continue
        t_c_prev = prev + pd.Timedelta(hours=18)
        t_pre = D + pd.Timedelta(hours=3)
        a, b = r2_px_at(y, t_c_prev), r2_px_at(y, t_pre)
        i0, i1 = r2_px_at(inr, t_c_prev), r2_px_at(inr, t_pre)
        rec.append(dict(date=D, us_on=np.log(b / a), inr_on=np.log(i1 / i0) if i0 > 0 else np.nan))
        prev = D
    return pd.DataFrame(rec).set_index("date")


def r2_c2_trades(m, hr: pd.DataFrame, LO, HI) -> list:
    """research/hunt/r2/cand.py, the C2_follow_us / XEOD / FUT branch for SILVER, verbatim logic."""
    out = []
    days = [pd.Timestamp(d) for d in m.days if LO <= pd.Timestamp(d) < HI]
    for D in days:
        d64 = D.to_datetime64()
        ix = np.where(m.day == d64)[0]
        if len(ix) < 200: continue
        prev_ix = np.where(m.day < d64)[0]
        if not len(prev_ix): continue
        pc = prev_ix[-1]
        e = ix[np.searchsorted(m.tod[ix], 9 * 60 + 5)] if (m.tod[ix] >= 9 * 60 + 5).any() else None
        if e is None or m.tod[e] > 9 * 60 + 15 or m.seg[e] != m.seg[pc]: continue
        p_in, p_prev = m.F[e], m.F[pc]
        fair = np.nan
        us_on = inr_on = np.nan
        if D in hr.index and np.isfinite(hr.loc[D, "us_on"]):
            us_on, inr_on = hr.loc[D, "us_on"], hr.loc[D, "inr_on"]
            fair = hr.loc[D, "us_on"] + (hr.loc[D, "inr_on"] if np.isfinite(hr.loc[D, "inr_on"]) else 0.0)
        xe = m.day_cut[d64]
        sd = np.sign(fair) if np.isfinite(fair) else 0.0
        if sd == 0: continue
        sd = int(sd)
        g, n = r2_fut_pnl(sd, p_in, m.F[xe])
        out.append(dict(date=D, e=int(e), xe=int(xe), pc=int(pc), side=sd, p_in=float(p_in), p_out=float(m.F[xe]),
                        p_prev=float(p_prev), gross=float(g), net=float(n), fair=float(fair), us_on=float(us_on),
                        inr_on=float(inr_on)))
    return out


def build_ussil(parity: dict) -> pd.DataFrame:
    M = m3lib()
    prep_m3raw("SILVERM", WORK / "m3raw", "m3")
    m = M.Market("SILVERM")
    dd = pd.DatetimeIndex(lake_daily("SILVERM").date)
    par = {"engine": "research/hunt/r2 hourly.py + cand.py (C2, XEOD, FUT; copied verbatim), m3lib.Market imported",
           "daily_day_list": "lake fut_daily_continuous SILVERM (Dhan expiryCode 0), as R2's m1 Dhan daily"}
    for part, LO, HI, hlo, hhi in (("design", pd.Timestamp("2025-08-01"), R2_HOLD_START, pd.Timestamp("2023-10-02"), R2_HOLD_START - pd.Timedelta(days=1)),
                                   ("holdout", R2_HOLD_START, R2_HOLD_END + pd.Timedelta(days=1), R2_HOLD_START, R2_HOLD_END)):
        hr = r2_hourly(dd, hlo, hhi)
        t = r2_c2_trades(m, hr, LO, HI)
        nets = np.array([x["net"] for x in t])
        par[part] = dict(window=[str(LO.date()), str((HI - pd.Timedelta(days=1)).date())], rebuilt=summ(nets))
    par["design"]["published"] = dict(trades=31, net_per_trade=-246.0, note="HUNT_R2_US_MCX.md: '09:05 minute check -Rs 246/tr (31 d)'")
    par["holdout"]["published"] = dict(trades=166, net_per_trade=1189.0, win_rate=0.56, max_dd=29600.0, per_day=1115.0)
    # the arm's trades: one continuous run (no design/holdout seam), 5 Aug 2025 -> 19 Oct 2026
    # the lake's MCX daily ends 16 Oct (Dhan had not published later dailies): later days come from the minute data
    md = pd.DatetimeIndex(m.days)
    dd_full = dd.append(md[md > dd.max()])
    par["day_list_extension"] = [str(x.date()) for x in md[md > dd.max()]]
    hr = r2_hourly(dd_full, pd.Timestamp("2025-07-01"), pd.Timestamp("2026-10-31"))
    t = r2_c2_trades(m, hr, pd.Timestamp("2025-08-01"), pd.Timestamp("2026-10-31"))
    par["full_run_note"] = ("trades file = one run over the whole span; the published runs skip the first day of each "
                            "window (hourly.py starts each window with prev=None)")
    parity["USSIL"] = par
    ts = pd.DatetimeIndex(m.ts).tz_localize(IST)
    spot = pd.DataFrame({"ts": ts, "close": m.F})
    expf = expiry_lookup(m.exp_days, LAST_EXP["SILVERM"])
    days = pd.DatetimeIndex(m.days)
    rollf = lambda d: (lambda e: None if e is None else next((x.date() for x in days if x.date() > e), None))(expf(d))
    C = Ctx("SILVERM", spot, "SI_F", m.days, expiry_of=expf, roll_of=rollf)
    rows = []
    for x in t:
        e, xe, sd = x["e"], x["xe"], x["side"]
        path = m.F[e + 1:xe + 1]
        mfe = float(np.max(sd * (path - x["p_in"])) / x["p_in"]) if len(path) else _nan()
        bars, ctx = C.make(ts[e], sd, sig=x)
        f = features_ussil(bars, ctx)
        rows.append(dict(day=x["date"].date(), entry_ts=ts[e], exit_ts=ts[xe], side=sd,
                         instrument="SILVERMIC near month (priced on the SILVERM future, R2: 1 kg x SILVERM quote)",
                         qty_lot=float(R2_MINI_SILVER_UNITS), entry_px=x["p_in"], exit_px=x["p_out"], net=x["net"],
                         gross=x["gross"], exit_why="close_10_min", mfe=mfe, **f))
    return finish(rows, "USSIL")


# ====================================================================== TREND (M2 TSM252)
M2_LEG_SYMS = {"CRUDE": ("CRUDEOIL", "CL_F"), "NATGAS": ("NATURALGAS", "NG_F"), "GOLD": ("GOLD", "GC_F"),
               "SILVER": ("SILVER", "SI_F"), "ZINC": ("ZINC", None), "LEAD": ("LEAD", None), "ALUM": ("ALUMINIUM", None)}


def m2_modules():
    sys.path.insert(0, str(HUNT / "m2"))
    import prep as P
    import engine as E
    d = WORK / "m2"
    d.mkdir(parents=True, exist_ok=True)
    P.D = str(d); E.D = str(d)
    return P, E


def m2_inputs(P, E):
    """M2's dhan_daily.parquet from the lake's Dhan continuous daily (expiryCode 0). LEAD is not in the lake."""
    d = pd.read_parquet(LAKE / "fut_daily_continuous" / "fut_daily_continuous.parquet")
    out = []
    missing = []
    for name, s in list(P.SPEC.items()):
        x = d[d.symbol == s["src"]]
        if not len(x):
            missing.append(name)
            continue
        out.append(pd.DataFrame({"ts": pd.to_datetime(x.date), "open": x.open.values, "high": x.high.values, "low": x.low.values,
                                 "close": x.close.values, "volume": x.volume.values, "open_interest": x.oi.values, "sym": s["src"]}))
    for n in missing:
        P.SPEC.pop(n)  # the same dict object as engine.SPEC
    pd.concat(out).to_parquet(f"{P.D}/dhan_daily.parquet", compression="zstd")
    P.build()
    return missing


def m2_leg_trades(E, g: pd.DataFrame, n: str, s: pd.Series, spec: dict):
    """Per-leg trades with run_futures' arithmetic, unconstrained (constrained=False), 1 contract of `spec` lots.
    A trade ends on a signal change (exit at that day's open) or on a contract roll (exit at the previous close: the
    engine earns no roll gap and charges a round trip at that close)."""
    idx = g.index
    o, c = g.open.values, g.close.values
    cl = g.close.ffill().values
    roll = g.roll.fillna(False).values.astype(bool)
    glitch = g.glitch.fillna(False).values.astype(bool) if "glitch" in g else np.zeros(len(g), bool)
    has = g.open.notna().values
    sv = s.reindex(idx).ffill().fillna(0.0).values
    L = spec["lots"]; pv = spec["pv"] * L
    trades, cur, pos = [], None, 0.0
    daily_pnl = np.zeros(len(idx))

    def open_tr(t, side, px, cost, kind):
        return dict(t0=t, side=side, entry=px, gross=0.0, cost=cost, kind=kind)

    def close_tr(tr, t_exit, px, cost, why, at_open):
        tr.update(t1=t_exit, exit=px, why=why, at_open=at_open)
        tr["cost"] += cost
        trades.append(tr)

    for t in range(len(idx)):
        if not has[t]:
            continue
        prev = pos
        want = sv[t - 1] if t > 0 else 0.0
        cprev = cl[t - 1] if t > 0 else o[t]
        day_pnl, day_cost = 0.0, 0.0
        if not roll[t]:
            g_ = prev * (o[t] - cprev) * pv
            day_pnl += g_
            if cur is not None:
                cur["gross"] += g_
        elif prev != 0:
            c1 = E.side_cost(cprev, L, spec, True) + E.side_cost(cprev, L, spec, False)
            day_cost += c1
            half = c1 / 2
            close_tr(cur, t - 1, cprev, half, "data_glitch" if glitch[t] else "trend_roll", False)
            cur = open_tr(t, prev, o[t], half, 2) if want == prev else None
            if cur is None:  # rolled and closed the same day: the engine charges both; the roll's buy side stays with it
                trades[-1]["cost"] += half
        if want != prev:
            d = want - prev
            sc = [E.side_cost(o[t], L, spec, d > 0) for _ in range(int(abs(d)))]
            day_cost += sum(sc)
            if prev != 0 and cur is not None:
                close_tr(cur, t, o[t], sc[0], "trend_flip" if want != 0 else "trend_flat", True)
                sc = sc[1:]
                cur = None
            if want != 0:
                cur = open_tr(t, want, o[t], sum(sc), 1)
            elif sc:
                trades[-1]["cost"] += sum(sc)
        g_ = want * (c[t] - o[t]) * pv
        day_pnl += g_
        if cur is not None and want != 0:
            cur["gross"] += g_
        pos = want
        daily_pnl[t] = day_pnl - day_cost
    if cur is not None:
        last = int(np.where(has)[0][-1])
        cur.update(t1=last, exit=c[last], why="open_at_data_end_mtm", at_open=False)
        trades.append(cur)
    return trades, daily_pnl


def build_trend(parity: dict) -> pd.DataFrame:
    P, E = m2_modules()
    missing = m2_inputs(P, E)
    par = {"engine": "research/hunt/m2 prep.build + engine (load, signal TSM252, run_futures, stats) imported unchanged",
           "legs_missing": missing,
           "legs_missing_why": "LEAD (LEADMINI leg): not in the lake's daily futures; the Dhan token was expired on 1 Nov so it "
                               "could not be refetched. The rebuild runs on the other 6 legs."}
    # parity: dev 2013-2024 (data cut at DEV_END before signals), constrained (Rs 1 lakh), and holdout 2025 ->
    data_dev = E.load(E.DEV_END)
    sig_dev = {n: E.signal(data_dev[n], "TSM252") for n in data_dev}
    dev = E.run_futures(data_dev, sig_dev, "LS", start=pd.Timestamp("2013-01-01"), end=E.DEV_END)
    st = E.stats(dev)
    yr = dev.pnl.groupby(dev.index.year).sum()
    data = E.load()
    sig = {n: E.signal(data[n], "TSM252") for n in data}
    end = max(g.index[-1] for g in data.values())
    ho = E.run_futures(data, sig, "LS", start=E.HOLD_START, end=end)
    sth = E.stats(ho)
    keep = ["cagr", "rs_month", "maxdd", "sharpe", "trades_yr", "final", "worst_month", "green"]
    par["dev_2013_2024_constrained_PORT_TSM252_LS"] = dict(
        published=dict(cagr=16.9, rs_month=3847, maxdd=-181000, sharpe=0.54, trades_yr=11, final=650000, worst_month=-92000, green=55,
                       by_year={"2013": -12e3, "2014": 66e3, "2015": 38e3, "2016": -60e3, "2017": 44e3, "2018": -88e3,
                                "2019": -20e3, "2020": 152e3, "2021": 142e3, "2022": 67e3, "2023": 94e3, "2024": 132e3}),
        rebuilt_6_legs={k: float(st[k]) for k in keep}, rebuilt_by_year={str(k): float(v) for k, v in yr.items()})
    par["holdout_2025_constrained_PORT_TSM252_LS"] = dict(
        window=["2025-01-01", str(end.date())],
        published=dict(cagr=92, rs_month=9800, maxdd=-200000, sharpe=0.76, trades_yr=12, final=315000, worst_month=-87000, green=77,
                       note="MCX_TREND.md walk-forward pick 'PORT 12-month TSM L/S', to 7 Oct 2026"),
        rebuilt_6_legs={k: float(sth[k]) for k in keep})
    # the arm's trades: 1 lot of each mini, every signal taken (no Rs 1 lakh margin rule), GOLDPETAL at 1 lot (the app)
    rows, chk = [], {}
    for n in data:
        g = data[n]
        spec = dict(P.SPEC[n]); spec["lots"] = 1
        s = E.signal(g, "TSM252")
        tr, dp = m2_leg_trades(E, g, n, s, spec)
        # check vs the engine: the trades' nets sum to run_futures(constrained=False) for this leg at these lots
        orig = E.SPEC[n]
        E.SPEC[n] = spec  # E.SPEC is P.SPEC (one dict)
        ref = E.run_futures({n: g}, {n: s}, "LS", constrained=False)
        E.SPEC[n] = orig
        tot = sum(x["gross"] - x["cost"] for x in tr)
        chk[n] = dict(trades=len(tr), sum_trade_net=float(tot), run_futures_unconstrained=float(ref.pnl.sum()),
                      open_trade_mtm_included=any(x["why"] == "open_at_data_end_mtm" for x in tr))
        big, us = M2_LEG_SYMS[n]
        C = Ctx(big, None, us, g.index)
        if P.SPEC[n]["div"] != 1:  # M2 quotes GOLD per gram (Dhan's per-10 g price / 10): put the minutes on its scale
            C.bars[["open", "high", "low", "close"]] = C.bars[["open", "high", "low", "close"]] / P.SPEC[n]["div"]
        rolls = g.index[g.roll.fillna(False).values]
        adj, vol60, carry = g.adj.values, g.vol60.values, g.carry.values
        for x in tr:
            t0, t1 = x["t0"], x["t1"]
            d0 = g.index[t0]
            entry_ts = (d0 + pd.Timedelta(hours=9)).tz_localize(IST)
            exit_ts = (g.index[t1] + pd.Timedelta(hours=9)).tz_localize(IST) if x["at_open"] else mcx_close_ts(g.index[t1])
            last_held = t1 - 1 if x["at_open"] else t1
            hi = g.high.values[t0:last_held + 1]; lo = g.low.values[t0:last_held + 1]
            fav = (np.nanmax(hi) - x["entry"]) if x["side"] > 0 else (x["entry"] - np.nanmin(lo))
            mfe = float(fav / abs(x["entry"])) if len(hi) else _nan()
            tm1 = t0 - 1
            prior_rolls = rolls[rolls < d0]
            nxt = rolls[rolls > d0]
            sigd = dict(ret252=float(x["side"] * (adj[tm1] - adj[tm1 - 252]) / abs(g.close.values[tm1])) if tm1 >= 252 else _nan(),
                        vol60=float(vol60[tm1]) if tm1 >= 0 else _nan(), carry=float(x["side"] * carry[tm1]) if tm1 >= 0 else _nan(),
                        dsr=float((d0 - prior_rolls[-1]).days) if len(prior_rolls) else _nan(), reentry=float(x["kind"] == 2),
                        leg=float(list(M2_LEG_SYMS).index(n)))
            C.daily = g.reset_index().rename(columns={"index": "date", "open_interest": "oi"})[["date", "open", "high", "low", "close", "volume", "oi"]]
            C.roll_of = (lambda nx: (lambda d: nx[0].date() if len(nx) else None))(nxt)
            bars, ctx = C.make(entry_ts, int(x["side"]), sig=sigd)
            f = features_trend(bars, ctx)
            rows.append(dict(day=d0.date(), entry_ts=entry_ts, exit_ts=exit_ts, side=int(x["side"]),
                             instrument=f"{P.SPEC[n]['contract']} (near month; signal on {big} continuous)",
                             qty_lot=float(spec["pv"] * spec["lots"]), entry_px=float(x["entry"]), exit_px=float(x["exit"]),
                             net=float(x["gross"] - x["cost"]), gross=float(x["gross"]), exit_why=x["why"], mfe=mfe, **f))
    par["trade_list_check"] = chk
    par["roll_flag_note"] = ("M2's prep.roll_days keeps the largest OI jump within 15 days, so a roll flag can move a few days "
                             "with later data; the trades follow the engine's flags as they are on the full data")
    n_open = int(sum(r["exit_why"] == "open_at_data_end_mtm" for r in rows))
    par["open_trades_note"] = f"{n_open} trades still open at the data's last daily close are marked to it (exit_why open_at_data_end_mtm)"
    par["trade_list_rules"] = ("1 lot of each mini (GOLDPETAL 1 lot as the app, the research held 5), every month-end signal "
                               "taken, no Rs 1 lakh margin / ruin rule (the app's arm has none); M2's own costs (Zerodha + "
                               "half spread + 0.02% slippage a side) and roll round trips")
    parity["TREND"] = par
    T = finish(rows, "TREND")
    return T.sort_values(["entry_ts", "instrument"]).reset_index(drop=True).assign(tid=lambda x: np.arange(len(x)))


# ====================================================================== CLI
BUILDERS = {"NGEVE": build_ngeve, "SILM": build_silm, "USSIL": build_ussil, "TREND": build_trend}

COVERAGE = {
    "asked": "1 Dec 2022 - 19 Oct 2026",
    "mcx_option_minutes": "lake options_rolling_1m MCX books start 5 Aug 2025 17:45 IST and end 19 Oct 2026 23:29: NGEVE, SILM "
                          "and USSIL (whose engines read the future from these files' `spot`) cannot be rebuilt before "
                          "5 Aug 2025; 1 Dec 2022 - 4 Aug 2025 has no trades for them (not invented)",
    "mcx_daily_futures": "lake fut_daily_continuous MCX: 2 Jan 2012 - 16 Oct 2026 (TREND rebuilt over all of it; its first "
                         "trade is 1 Nov 2012, the first month-end with 252 days of history); no LEAD",
    "mcx_futures_minutes_with_volume_oi": "lake fut_1m_mcx_continuous holds the near month only from NATURALGAS 26 Aug 2026, "
                                          "SILVERM 27 Feb 2026 (patchy: near_share >= 0.5 on 47 of 255 days), SILVER 30 Jun, "
                                          "CRUDEOIL 18 Sep 2026; f_vol_rel, f_oi_chg, f_od, f_oiq, f_rng_proxy are NaN elsewhere",
    "us_hourly": "Yahoo 1-hour SI=F, NG=F, CL=F, GC=F from 10 Jun 2024, INR=X from 15 Jan 2024, to 30 Oct 2026 (fetched 1 Nov "
                 "2026 as R2's fetch_yahoo.sh); f_us_on / f_fx_on NaN before. Yahoo's front-month series jump at its own "
                 "contract rolls (NG=F most), which shows in f_us_on on those days",
    "us_data_calendar": "R2 events.py CPI / NFP, fomc.py FOMC, lake xauusd_1m/us_events.csv: Jun 2024 - Oct 2026; f_us_data NaN outside",
    "features_note": ("signed with the trade: f_push, f_trend20, f_gap, f_d_open, f_ret5, f_us_on, f_od, f_oiq (direction); "
                      "unsigned: f_d_pdh, f_d_pdl, f_fx_on (USD/INR log move, as asked), ranges, volumes. ATR = mean daily "
                      "high-low of the last 14 sessions (lake daily). f_dte / f_roll_days count MCX trading days on the data's "
                      "day list (weekdays after its end) to the option series' expiry / the first day of the next series "
                      "(public calendar dates, inferred by each engine from the data); TREND: f_dte NaN (no expiry in M2's "
                      "data), f_roll_days to M2's next detected roll. Price features use each engine's own price series "
                      "(the options' `spot` future for NGEVE / SILM / USSIL, M2's Dhan daily for TREND)."),
}


def main(argv):
    if not argv:
        print(__doc__); return
    if argv[0] == "parse-yahoo":
        parse_yahoo(); return
    if argv[0] != "build":
        raise SystemExit("usage: build [ARM ...] | parse-yahoo")
    arms = argv[1:] or list(ARMS)
    RES.mkdir(exist_ok=True)
    pf = RES / "parity_mcx.json"
    parity = json.loads(pf.read_text()) if pf.exists() else {}
    parity["_coverage"] = COVERAGE
    for a in arms:
        T = BUILDERS[a](parity)
        p = write(T, a)
        parity[a]["output"] = dict(file=str(p).replace(str(Path.home()), "~"), rows=int(len(T)),
                                   first_day=str(T.day.min()) if len(T) else None, last_day=str(T.day.max()) if len(T) else None,
                                   all_trades=summ(T.net.values))
        pf.write_text(json.dumps(parity, indent=1, default=str))
    print(json.dumps({a: parity[a].get("output") for a in arms}, indent=1, default=str))


if __name__ == "__main__":
    main(sys.argv[1:])
