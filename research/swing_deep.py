"""Deep swing-trading study (hold 2-20 trading days) on real Dhan data, after Indian costs.

    python3 -I research/swing_deep.py <dhan-data dir> <cache dir> [out.md]

Instruments: NIFTY / BANKNIFTY index futures (index points x fixed lot), bought ATM monthly options and monthly
debit spreads (real minute prices, 09:20 and 15:25 snapshots), and F&O stocks in cash/delivery (ATR-sized portfolio).
Every decision is taken on a daily close; fills are at the next session's open (futures/stocks) or at the 09:20
option price (options). Stops in futures/stocks are resting SL orders (filled at the stop, or at the open if the
market gaps through it). Everything is split by calendar year and a walk-forward test picks parameters on past years.
"""
from __future__ import annotations

import glob
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")  # pandas deps when run with python -I
import warnings  # noqa: E402

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

DATA, CACHE = sys.argv[1], sys.argv[2]
OUT = sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "SWING_DEEP.md")
START, WARM = pd.Timestamp("2016-01-01"), pd.Timestamp("2014-01-01")
CAP = 500_000.0                       # Boss's capital for the "Rs per year on 5 lakh" numbers
LOT = {"NIFTY": 65, "BANKNIFTY": 30}  # held constant (current lots) so years are comparable
STEP = {"NIFTY": 50, "BANKNIFTY": 100}
OPT_MINSLIP = {"NIFTY": 0.5, "BANKNIFTY": 1.0}
CARRY = 0.055                         # futures cost-of-carry vs spot (r - dividend yield), paid by longs
FUT_SLIP = 0.0002                     # 0.02% a side (stop fills: double)
EQ_SLIP = 0.001                       # 0.10% a side for stocks (stop fills: double)
RNG = np.random.default_rng(7)
warnings.filterwarnings("ignore", category=RuntimeWarning)

# ----------------------------------------------------------------------------------------------- costs (Rs)

def fut_costs(n_in, n_out, direction, stop_exit=False):
    buy, sell = (n_in, n_out) if direction > 0 else (n_out, n_in)
    brok = 40.0
    exch = 0.0000173 * (n_in + n_out)
    sebi = 1e-6 * (n_in + n_out)
    stt = 0.0002 * sell
    stamp = 0.00002 * buy
    gst = 0.18 * (brok + exch + sebi)
    slip = FUT_SLIP * n_in + FUT_SLIP * n_out * (2 if stop_exit else 1)
    return brok + exch + sebi + stt + stamp + gst + slip


def opt_side_cost(value, side):
    """Statutory + brokerage for one leg, one side; value = premium x qty in Rs."""
    brok = 20.0
    exch = 0.0003503 * value
    sebi = 1e-6 * value
    stt = 0.001 * value if side == "sell" else 0.0
    stamp = 0.00003 * value if side == "buy" else 0.0
    return brok + exch + sebi + stt + stamp + 0.18 * (brok + exch + sebi)


def eq_side_cost(value, side):
    brok = 20.0
    exch = 0.0000297 * value
    sebi = 1e-6 * value
    stt = 0.001 * value
    stamp = 0.00015 * value if side == "buy" else 0.0
    dp = 15.93 if side == "sell" else 0.0
    return brok + exch + sebi + stt + stamp + dp + 0.18 * (brok + exch + sebi + dp)

# ----------------------------------------------------------------------------------------------- events

BUDGET = ["2016-02-29", "2017-02-01", "2018-02-01", "2019-02-01", "2019-07-05", "2020-02-01", "2021-02-01",
          "2022-02-01", "2023-02-01", "2024-02-01", "2024-07-23", "2025-02-01", "2026-02-01"]
RBI = ["2016-04-05", "2016-06-07", "2016-08-09", "2016-10-04", "2016-12-07", "2017-02-08", "2017-04-06", "2017-06-07",
       "2017-08-02", "2017-10-04", "2017-12-06", "2018-02-07", "2018-04-05", "2018-06-06", "2018-08-01", "2018-10-05",
       "2018-12-05", "2019-02-07", "2019-04-04", "2019-06-06", "2019-08-07", "2019-10-04", "2019-12-05", "2020-02-06",
       "2020-03-27", "2020-05-22", "2020-08-06", "2020-10-09", "2020-12-04", "2021-02-05", "2021-04-07", "2021-06-04",
       "2021-08-06", "2021-10-08", "2021-12-08", "2022-02-10", "2022-04-08", "2022-05-04", "2022-06-08", "2022-08-05",
       "2022-09-30", "2022-12-07", "2023-02-08", "2023-04-06", "2023-06-08", "2023-08-10", "2023-10-06", "2023-12-08",
       "2024-02-08", "2024-04-05", "2024-06-07", "2024-08-08", "2024-10-09", "2024-12-06", "2025-02-07", "2025-04-09",
       "2025-06-06", "2025-08-06", "2025-10-01", "2025-12-05", "2026-02-06", "2026-04-08", "2026-06-05", "2026-08-07",
       "2026-10-01"]
ELECTION = ["2019-05-23", "2024-06-04"]
EVENTS = {"budget": pd.to_datetime(BUDGET), "RBI policy": pd.to_datetime(RBI), "election result": pd.to_datetime(ELECTION)}

# ----------------------------------------------------------------------------------------------- data


def read_daily(path):
    d = pd.read_parquet(path)
    d["date"] = d.ts.dt.tz_localize(None).dt.normalize()
    d = d.drop_duplicates("date").set_index("date").sort_index()
    return d[["open", "high", "low", "close", "volume"]].astype("float64")


def wilder(x, n):
    return x.ewm(alpha=1 / n, adjust=False).mean()


def prep(d):
    """Indicators on a daily OHLC frame. All '..p' columns use history up to yesterday only."""
    d = d.copy()
    o, h, l, c = d.open, d.high, d.low, d.close
    pc = c.shift(1)
    tr = pd.concat([h - l, (h - pc).abs(), (l - pc).abs()], axis=1).max(axis=1)
    d["atr"] = wilder(tr, 14)
    d["sma200"] = c.rolling(200).mean()
    d["sma5"] = c.rolling(5).mean()
    d["ema20"] = c.ewm(span=20, adjust=False).mean()
    d["ema50"] = c.ewm(span=50, adjust=False).mean()
    dc = c.diff()
    for n in (2, 14):
        g, ls = wilder(dc.clip(lower=0), n), wilder((-dc).clip(lower=0), n)
        d[f"rsi{n}"] = 100 - 100 / (1 + g / ls.replace(0, 1e-12))
    m = c.ewm(span=12, adjust=False).mean() - c.ewm(span=26, adjust=False).mean()
    d["macd"], d["macds"] = m, m.ewm(span=9, adjust=False).mean()
    for n in (10, 20, 30, 55, 100, 252):
        d[f"hi{n}"] = h.shift(1).rolling(n).max()
        d[f"lo{n}"] = l.shift(1).rolling(n).min()
    rng = h - l
    d["nr7"] = rng <= rng.rolling(7).min()
    d["inside"] = (h < h.shift(1)) & (l > l.shift(1))
    d["ret1"] = c / pc - 1
    d["sd20"] = d.ret1.shift(1).rolling(20).std()
    d["ret126"] = c / c.shift(126) - 1
    d["vol20"] = d.volume.shift(1).rolling(20).mean()
    return d

# ----------------------------------------------------------------------------------------------- signals


def index_signals(d, seed=0):
    c = d.close
    s = {}
    s["Donchian 20-day breakout"] = np.where(c > d.hi20, 1, np.where(c < d.lo20, -1, 0))
    s["Donchian 55-day breakout"] = np.where(c > d.hi55, 1, np.where(c < d.lo55, -1, 0))
    s["Pullback: >200DMA & RSI(2)<10"] = np.where((c > d.sma200) & (d.rsi2 < 10), 1,
                                                  np.where((c < d.sma200) & (d.rsi2 > 90), -1, 0))
    dn3 = (c < c.shift(1)) & (c.shift(1) < c.shift(2)) & (c.shift(2) < c.shift(3))
    up3 = (c > c.shift(1)) & (c.shift(1) > c.shift(2)) & (c.shift(2) > c.shift(3))
    s["Pullback: >200DMA & 3 down closes"] = np.where((c > d.sma200) & dn3, 1, np.where((c < d.sma200) & up3, -1, 0))
    s["EMA 20/50 trend"] = np.where((c > d.ema20) & (d.ema20 > d.ema50), 1,
                                    np.where((c < d.ema20) & (d.ema20 < d.ema50), -1, 0))
    s["NR7 / inside-day breakout"] = np.where(d.nr7 | d.inside, 2, 0)   # 2 = stop entry both sides next day
    s["52-week high/low momentum"] = np.where(c >= d.hi252, 1, np.where(c <= d.lo252, -1, 0))
    s["Mean reversion: buy after big down day"] = np.where(d.ret1 < -2 * d.sd20, 1, 0)
    rng = np.random.default_rng(seed)
    u = rng.random(len(d))
    s["Coin flip (random side, ~15% of days)"] = np.where(u < 0.075, 1, np.where(u < 0.15, -1, 0))
    s["Always long (buy-and-hold proxy)"] = np.ones(len(d), dtype=int)
    return {k: np.asarray(v) for k, v in s.items()}

# ----------------------------------------------------------------------------------------------- exits

EXITS = {
    "fixed 5d": dict(kind="fixed", N=5),
    "fixed 10d": dict(kind="fixed", N=10),
    "fixed 20d": dict(kind="fixed", N=20),
    "2ATR stop + 3ATR chandelier (max 20d)": dict(kind="stop", m=2.0, trail=3.0, N=20),
    "2ATR stop + 10d time stop": dict(kind="stop", m=2.0, trail=None, N=10),
}

# ----------------------------------------------------------------------------------------------- index engine


def run_index(d, sig, ex, stop_mode="intraday", lo_only=False):
    """One position at a time. Returns list of trade dicts (prices in index points)."""
    o, h, l, c, atr = (d[k].values for k in ("open", "high", "low", "close", "atr"))
    n = len(d)
    trades, pos, pend_entry, pend_exit = [], None, None, False
    for i in range(n):
        # 1) entry at the open (or stop entry during the day)
        if pend_entry is not None and pos is None:
            dirn, lvl_b, lvl_s, a = pend_entry
            pend_entry = None
            px = None
            if dirn == 2:
                hb, hs = h[i] >= lvl_b, l[i] <= lvl_s
                if hb and hs:
                    # both levels traded the same day: order unknown from daily bars, so assume the worst - filled
                    # on the side nearer the open and stopped out at the other level the same day
                    near_b = abs(o[i] - lvl_b) <= abs(o[i] - lvl_s)
                    dirn = 1 if (near_b or lo_only) else -1
                    px = max(o[i], lvl_b) if dirn > 0 else min(o[i], lvl_s)
                    xp = lvl_s if dirn > 0 else lvl_b
                    if dirn > 0 or not lo_only:
                        pos = dict(dir=dirn, ei=i, ep=px, R=2.0 * a, stop=None, ext=px, stop_entry=True)
                        trades.append(close_trade(pos, i, xp, "whipsaw", d, stop_exit=True))
                        pos = None
                    px = None
                elif hb:
                    dirn, px = 1, max(o[i], lvl_b)
                elif hs and not lo_only:
                    dirn, px = -1, min(o[i], lvl_s)
            else:
                px = o[i]
            if px is not None and a > 0:
                stop = px - dirn * ex.get("m", 2.0) * a if ex["kind"] == "stop" else None
                pos = dict(dir=dirn, ei=i, ep=px, R=2.0 * a, stop=stop, ext=px, stop_entry=lvl_b is not None)
        # 2) scheduled exit at the open
        if pos is not None and pend_exit:
            trades.append(close_trade(pos, i, o[i], "time/close-stop", d))
            pos, pend_exit = None, False
        # 3) intraday stop
        if pos is not None and pos["stop"] is not None and stop_mode == "intraday":
            # (on a stop-entry day the low may have come before the fill; we assume the stop was hit - conservative)
            dr, st = pos["dir"], pos["stop"]
            if dr > 0 and l[i] <= st:
                trades.append(close_trade(pos, i, min(o[i], st), "stop", d, stop_exit=True))
                pos = None
            elif dr < 0 and h[i] >= st:
                trades.append(close_trade(pos, i, max(o[i], st), "stop", d, stop_exit=True))
                pos = None
        # 4) end of day bookkeeping
        if pos is not None:
            dr = pos["dir"]
            held = i - pos["ei"] + 1
            if pos["stop"] is not None and stop_mode == "close":
                if (dr > 0 and c[i] <= pos["stop"]) or (dr < 0 and c[i] >= pos["stop"]):
                    pend_exit = True
            if held >= ex["N"]:
                pend_exit = True
            if ex["kind"] == "stop" and ex["trail"]:
                if dr > 0:
                    pos["ext"] = max(pos["ext"], h[i])
                    pos["stop"] = max(pos["stop"], pos["ext"] - ex["trail"] * atr[i])
                else:
                    pos["ext"] = min(pos["ext"], l[i])
                    pos["stop"] = min(pos["stop"], pos["ext"] + ex["trail"] * atr[i])
            if i == n - 1:
                trades.append(close_trade(pos, i, c[i], "end of data", d))
                pos = None
        # 5) new signal on the close (only when flat and nothing pending)
        if pos is None and pend_entry is None and i < n - 1:
            sg = sig[i]
            if sg == 2:
                pend_entry = (2, h[i], l[i], atr[i])
            elif sg == 1 or (sg == -1 and not lo_only):
                pend_entry = (int(sg), None, None, atr[i])
    return trades


def close_trade(pos, i, px, why, d, stop_exit=False):
    o, c = d.open.values, d.close.values
    ei, dr = pos["ei"], pos["dir"]
    gap = dr * float(np.sum(o[ei + 1:i + 1] - c[ei:i])) if i > ei else 0.0
    return dict(dir=dr, ei=ei, xi=i, ep=pos["ep"], xp=px, R=pos["R"], why=why, gap=gap, stop_exit=stop_exit,
                ed=d.index[ei], xd=d.index[i])


def monthly_expiries(dates):
    """Approximate monthly expiry days (last Thu; last Tue from Sep 2025), moved back to a trading day."""
    s = pd.Series(dates)
    out = []
    for (y, m), g in s.groupby([s.dt.year, s.dt.month]):
        wd = 1 if pd.Timestamp(y, m, 1) >= pd.Timestamp("2025-09-01") else 3
        last = pd.Timestamp(y, m, 1) + pd.offsets.MonthEnd(0)
        while last.weekday() != wd:
            last -= pd.Timedelta(days=1)
        ok = g[g <= last]
        if len(ok):
            out.append(ok.iloc[-1])
    return pd.DatetimeIndex(out)


def fut_pnl(trades, d, sym):
    """Adds Rs P&L (1 lot, after costs, carry and roll) to each trade and returns a DataFrame."""
    lot = LOT[sym]
    exps = monthly_expiries(d.index)
    rows = []
    for t in trades:
        n_in, n_out = t["ep"] * lot, t["xp"] * lot
        cost = fut_costs(n_in, n_out, t["dir"], t["stop_exit"])
        rolls = int(((exps >= t["ed"]) & (exps < t["xd"])).sum())
        cost += rolls * fut_costs(n_out, n_out, t["dir"])
        days = (t["xd"] - t["ed"]).days
        carry = CARRY * n_in * days / 365 * t["dir"]
        gross = t["dir"] * (t["xp"] - t["ep"]) * lot
        net = gross - cost - carry
        rows.append({**t, "gross": gross, "cost": cost + carry, "net": net, "Rn": net / (t["R"] * lot),
                     "gap_rs": t["gap"] * lot, "year": t["ed"].year})
    return pd.DataFrame(rows)


def daily_mtm(tr, d, lot):
    """Daily mark-to-market P&L series (Rs) for a futures trade list; costs booked on the exit day."""
    c, o = d.close.values, d.open.values
    pnl = np.zeros(len(d))
    gap = np.zeros(len(d))
    for t in tr.itertuples():
        dr, ei, xi = t.dir, t.ei, t.xi
        if xi == ei:
            pnl[ei] += dr * (t.xp - t.ep) * lot
        else:
            pnl[ei] += dr * (c[ei] - t.ep) * lot
            if xi - ei > 1:
                pnl[ei + 1:xi] += dr * (c[ei + 1:xi] - c[ei:xi - 1]) * lot
            pnl[xi] += dr * (t.xp - c[xi - 1]) * lot
            gap[ei + 1:xi + 1] += dr * (o[ei + 1:xi + 1] - c[ei:xi]) * lot
        pnl[xi] -= t.cost
    return pd.Series(pnl, index=d.index), pd.Series(gap, index=d.index)


def maxdd(series):
    eq = series.cumsum()
    return float((eq - eq.cummax()).min()) if len(eq) else 0.0


def streak(x):
    best = cur = 0
    for v in x:
        cur = cur + 1 if v <= 0 else 0
        best = max(best, cur)
    return best


def pf(x):
    w, lo = x[x > 0].sum(), -x[x < 0].sum()
    return w / lo if lo > 0 else np.inf


def years_of(idx):
    return sorted(set(pd.DatetimeIndex(idx).year))

# ----------------------------------------------------------------------------------------------- formatting


def rs(x):
    if x is None or (isinstance(x, float) and not np.isfinite(x)):
        return ""
    return f"{x:,.0f}"


def table(header, rows):
    out = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    out += ["| " + " | ".join(str(v) for v in r) + " |" for r in rows]
    return "\n".join(out)

# ----------------------------------------------------------------------------------------------- options cache


def build_option_cache(sym, kind="MONTH"):
    path = os.path.join(CACHE, f"opt_{sym}_{kind}.parquet")
    if os.path.exists(path):
        return pd.read_parquet(path)
    parts = []
    for typ in ("CALL", "PUT"):
        for f in sorted(glob.glob(os.path.join(DATA, "options", sym, kind, typ, "*.parquet"))):
            x = pd.read_parquet(f, columns=["ts", "strike", "close", "iv", "spot", "offset", "oi"])
            t = x.ts.dt.tz_localize(None)
            mins = t.dt.hour * 60 + t.dt.minute
            x["date"] = t.dt.normalize()
            am = x[(mins >= 9 * 60 + 20) & (mins <= 9 * 60 + 30)].copy()
            am["mm"] = mins[am.index]
            am = am.sort_values("mm").groupby(["date", "strike"], as_index=False).first()
            am["slot"] = "am"
            pm = x[(mins >= 15 * 60 + 10) & (mins <= 15 * 60 + 25)].copy()
            pm["mm"] = mins[pm.index]
            pm = pm.sort_values("mm").groupby(["date", "strike"], as_index=False).last()
            pm["slot"] = "pm"
            y = pd.concat([am, pm])
            y["typ"] = "C" if typ == "CALL" else "P"
            parts.append(y[["date", "slot", "typ", "strike", "close", "iv", "spot", "offset", "oi"]])
    df = pd.concat(parts).drop_duplicates(["date", "slot", "typ", "strike"])
    df.to_parquet(path)
    return df


def detect_expiries(snap, thr=0.006):
    """Expiry day = the ATM call's last price of the day is tiny and the next session's ATM call is >2.5x dearer."""
    a = snap[(snap.typ == "C") & (snap.offset == 0)].copy()
    a["r"] = a.close / a.spot
    a["k"] = a.slot.map({"am": 0, "pm": 1})
    a = a.sort_values(["date", "k"])
    last = a.groupby("date").r.last()
    first = a.groupby("date").r.first()
    nxt = first.shift(-1)
    exp = last[(last < thr) & (nxt > 2.5 * last)].index
    return pd.DatetimeIndex(exp)

# ----------------------------------------------------------------------------------------------- option pricing

from math import erf, exp, log, sqrt  # noqa: E402


def _ncdf(x):
    return 0.5 * (1 + erf(x / sqrt(2)))


def bs(S, K, T, vol, typ, r=0.065):
    if T <= 0 or vol <= 0:
        return max(0.0, S - K) if typ == "C" else max(0.0, K - S)
    d1 = (log(S / K) + (r + vol * vol / 2) * T) / (vol * sqrt(T))
    d2 = d1 - vol * sqrt(T)
    if typ == "C":
        return S * _ncdf(d1) - K * exp(-r * T) * _ncdf(d2)
    return K * exp(-r * T) * _ncdf(-d2) - S * _ncdf(-d1)


class Book:
    """Real 09:20 / 15:25 option prices for one index and one expiry series; Black-Scholes with the nearest
    strike's IV when the wanted strike/contract is not in the data (counted as 'model-priced')."""

    def __init__(self, sym, kind, thr, last_exp):
        df = build_option_cache(sym, kind)
        self.sym, self.step = sym, STEP[sym]
        self.exp = detect_expiries(df, thr)
        self.exp = self.exp.append(pd.DatetimeIndex([pd.Timestamp(last_exp)])).unique().sort_values()
        df = df[df.close > 0]
        self.px = {(r.date, r.slot, r.typ, float(r.strike)): (float(r.close), float(r.iv)) for r in df.itertuples()}
        self.spot = df.groupby(["date", "slot"]).spot.median().to_dict()
        g = {}
        for (dt, sl, ty), x in df[df.iv > 0].groupby(["date", "slot", "typ"]):
            g[(dt, sl, ty)] = (x.strike.values.astype(float), x.iv.values.astype(float), x.oi.values.astype(float))
        self.chain = g
        self.dates = sorted(set(df.date))
        self.model = 0
        self.real = 0
        self.iv_proxy = None

    def exp_for(self, dt, ahead=0):
        i = self.exp.searchsorted(dt)
        i = min(i + ahead, len(self.exp) - 1)
        return self.exp[i]

    def S(self, dt, slot):
        return self.spot.get((dt, slot), self.spot.get((dt, "pm" if slot == "am" else "am")))

    def iv_near(self, dt, slot, typ, K):
        for ty in (typ, "P" if typ == "C" else "C"):
            ch = self.chain.get((dt, slot, ty))
            if ch is not None and len(ch[0]):
                j = int(np.argmin(np.abs(ch[0] - K)))
                return ch[1][j] / 100.0
        return None

    def T(self, dt, slot, expiry):
        now = dt + pd.Timedelta(hours=9, minutes=20) if slot == "am" else dt + pd.Timedelta(hours=15, minutes=25)
        return max(0.0, ((expiry + pd.Timedelta(hours=15, minutes=30)) - now).total_seconds() / (365 * 86400))

    def price(self, dt, slot, typ, K, expiry, force_model=False):
        if expiry == self.exp_for(dt) and not force_model:
            hit = self.px.get((dt, slot, typ, float(K)))
            if hit is not None:
                self.real += 1
                return hit[0]
        S = self.S(dt, slot)
        vol = None
        if force_model and self.iv_proxy is not None:
            # a contract that is not the nearest one: use the monthly chain's IV (a longer-dated IV) rather than
            # the expiring weekly's, whose IV is meaningless in its last sessions
            vol = self.iv_proxy.iv_near(dt, slot, typ, K)
        if vol is None:
            vol = self.iv_near(dt, slot, typ, K)
        if S is None or vol is None:
            return None
        self.model += 1
        return bs(S, K, self.T(dt, slot, expiry), vol, typ)


def opt_slip(p, sym):
    return max(OPT_MINSLIP[sym], 0.0025 * p)


def _before(d1, s1, d2, s2):
    return (d1, s1 == "pm") < (d2, s2 == "pm")


def option_trade(book, dates, dr, e_date, x_date, legs, roll=True, x_slot="am", last_ok=None, all_model=False):
    """Open the legs at 09:20 on e_date, close at x_slot on x_date. legs = [(qty_sign, strikes_otm, weeks_ahead)].
    A contract that expires first is closed at 15:25 on its expiry day and, if roll, re-opened (new ATM, next
    contract) at the next session's 09:20. last_ok(expiry) -> (date, slot) forces that leg's exit earlier (the
    'never hold weekly OTM into expiry' rule). Returns dict(net, debit, theta, segs) or None if prices are missing."""
    sym, lot, typ = book.sym, LOT[book.sym], ("C" if dr > 0 else "P")
    di = {d: i for i, d in enumerate(dates)}
    cur, net, theta, debit, segs = e_date, 0.0, 0.0, None, 0
    aheads = [a for _, _, a in legs]
    while True:
        S0 = book.S(cur, "am")
        if S0 is None:
            return None
        atm = round(S0 / book.step) * book.step
        exs = [book.exp_for(cur, a) for a in aheads]
        end, end_slot, planned = x_date, x_slot, True
        for ex in exs:
            if _before(ex, "pm", end, end_slot):
                end, end_slot, planned = ex, "pm", False
            if last_ok is not None:
                lo = last_ok(ex)
                if lo is not None and _before(lo[0], lo[1], end, end_slot):
                    end, end_slot, planned = lo[0], lo[1], False
        if _before(end, end_slot, cur, "am"):
            return None
        seg_net, seg_debit = 0.0, 0.0
        for (q, k, _), ex in zip(legs, exs):
            K = atm + dr * k * book.step
            fm = all_model or ex != book.exp_for(cur)   # not the nearest contract at entry: model-price both ends
            p0 = book.price(cur, "am", typ, K, ex, fm)
            p1 = book.price(end, end_slot, typ, K, ex, fm)
            if p0 is None or p1 is None:
                return None
            if q > 0:
                b, sl = p0 + opt_slip(p0, sym), max(0.0, p1 - opt_slip(p1, sym))
                seg_net += (sl - b) * lot - opt_side_cost(b * lot, "buy") - opt_side_cost(sl * lot, "sell")
                seg_debit += b * lot
            else:
                sl, b = max(0.0, p0 - opt_slip(p0, sym)), p1 + opt_slip(p1, sym)
                seg_net += (sl - b) * lot - opt_side_cost(sl * lot, "sell") - opt_side_cost(b * lot, "buy")
                seg_debit -= sl * lot
            vol = book.iv_near(cur, "am", typ, K)
            if vol:
                th = bs(S0, K, book.T(end, end_slot, ex), vol, typ) - bs(S0, K, book.T(cur, "am", ex), vol, typ)
                theta += q * th * lot
        net += seg_net
        segs += 1
        if debit is None:
            debit = seg_debit
        if planned or not roll:
            break
        i = di.get(end)
        if i is None or i + 1 >= len(dates):
            break
        cur = dates[i + 1]
        if not _before(cur, "am", x_date, x_slot):
            break
        old = max(exs)
        aheads = [0 if book.exp_for(cur) > old else 1 for _ in legs]
    return dict(net=net, debit=debit, theta=theta, segs=segs)


# ----------------------------------------------------------------------------------------------- stats helpers


def trade_stats(net, R=None):
    net = np.asarray(net, float)
    if len(net) == 0:
        return dict(n=0, win="", avgR="", pf="", tot=0.0, streak=0)
    out = dict(n=len(net), win=f"{100 * np.mean(net > 0):.0f}%", tot=float(net.sum()), streak=streak(net))
    p = pf(net)
    out["pf"] = f"{p:.2f}" if np.isfinite(p) else "inf"
    out["avgR"] = f"{np.mean(np.asarray(R, float)):+.2f}" if R is not None else ""
    return out


def year_cells(series_by_year, years, fmt=rs):
    return [fmt(series_by_year.get(y, 0.0)) for y in years]

# ======================================================================================== SECTION: index futures

KEY = {}


def load_index(sym):
    d = prep(read_daily(os.path.join(DATA, "candles", "daily", "IDX_I", f"{sym}.parquet")))
    return d[d.index >= WARM]


def study_period(d):
    return d[d.index >= START - pd.Timedelta(days=400)]


def run_fut(d, sym, sig, ex, mode="intraday", lo_only=False):
    """Run on the full frame but keep only trades entered from START."""
    tr = run_index(d, sig, ex, mode, lo_only)
    tr = [t for t in tr if t["ed"] >= START]
    return fut_pnl(tr, d, sym) if tr else pd.DataFrame()


def fut_summary(tr, d, sym, years):
    if tr.empty:
        return None
    pnl, gap = daily_mtm(tr, d, LOT[sym])
    pnl, gap = pnl[pnl.index >= START], gap[gap.index >= START]
    by_year = pnl.groupby(pnl.index.year).sum()
    month = pnl.groupby([pnl.index.year, pnl.index.month]).sum()
    st = trade_stats(tr.net.values, tr.Rn.values)
    gross = tr.gross.sum()
    return dict(st=st, by_year=by_year.to_dict(), dd=maxdd(pnl), worst_month=float(month.min()),
                pos_years=int((by_year > 0).sum()), n_years=len(by_year), gap_share=tr.gap_rs.sum(),
                gross=gross, intraday=gross - tr.gap_rs.sum(), dd_gap=maxdd(gap), dd_intra=maxdd(pnl - gap), pnl=pnl)


def index_section(idx):
    md = ["## 1. NIFTY and BANKNIFTY index futures (1 lot, 2016 - Oct 2026)",
          "",
          f"1 lot = NIFTY {LOT['NIFTY']} / BANKNIFTY {LOT['BANKNIFTY']} units throughout (today's lot sizes, so years are "
          "comparable). Prices are the spot index; a futures holder also pays cost-of-carry (5.5% a year on the notional "
          "for longs, received by shorts) and a roll each time a monthly expiry falls inside the trade - both are "
          "charged. Costs: Rs 20/order, STT 0.02% on the sell side, exchange 0.00173%, SEBI, stamp 0.002% on the buy "
          "side, GST 18%, slippage 0.02% a side (0.04% on stop fills). Signal on the daily close, entry next open, "
          "stops are resting SL orders (filled at the open when the market gaps through). Long and short signals "
          "both traded unless stated. Margin for 1 lot is roughly Rs 1.6-2 lakh, so Rs 5 lakh carries 1 lot.",
          ""]
    years = list(range(2016, 2027))
    allres = {}
    for sym, d in idx.items():
        sigs = index_signals(d)
        md += [f"### {sym}: every rule x every exit (totals over 2016-2026, Rs per lot after all costs)", ""]
        rows = []
        for rn, sg in sigs.items():
            for en, ex in EXITS.items():
                tr = run_fut(d, sym, sg, ex)
                s = fut_summary(tr, d, sym, years)
                if s is None:
                    continue
                allres[(sym, rn, en)] = (tr, s)
                st = s["st"]
                gs = f"{100 * s['gap_share'] / s['gross']:.0f}%" if s["gross"] > 0 else "n/a (gross<0)"
                rows.append([rn, en, st["n"], st["win"], st["avgR"], st["pf"], rs(st["tot"]),
                             rs(st["tot"] / 10.75), f"{s['pos_years']}/{s['n_years']}", rs(s["dd"]),
                             rs(s["worst_month"]), st["streak"], rs(s["gap_share"])])
        md.append(table(["rule", "exit", "trades", "win", "avg R", "PF", "total Rs", "Rs/yr", "years +", "max DD",
                         "worst month", "longest losing run", "of which overnight gaps Rs"], rows))
        md.append("")
        # coin flip distribution over seeds (fixed 10d and chandelier)
        for en in ("fixed 10d", "2ATR stop + 3ATR chandelier (max 20d)"):
            tots, yrs = [], []
            for seed in range(1, 31):
                sg = index_signals(d, seed)["Coin flip (random side, ~15% of days)"]
                tr = run_fut(d, sym, sg, EXITS[en])
                tots.append(tr.net.sum())
                yrs.append(tr.groupby("year").net.sum())
            yy = pd.concat(yrs, axis=1).fillna(0)
            KEY[(sym, "coin", en)] = (np.mean(tots), np.percentile(tots, 5), np.percentile(tots, 95))
            md.append(f"Coin flip over 30 random seeds, {en}: average total Rs {rs(np.mean(tots))}, 90% of seeds between "
                      f"Rs {rs(np.percentile(tots, 5))} and Rs {rs(np.percentile(tots, 95))}; average positive years "
                      f"{(yy > 0).sum().mean():.1f} of {len(yy)}. A real rule has to beat this band, not zero.")
            md.append("")
    return md, allres


def index_years_table(allres, sym, years, exits=("fixed 10d", "2ATR stop + 3ATR chandelier (max 20d)")):
    rows = []
    for (s, rn, en), (tr, st) in allres.items():
        if s != sym or en not in exits:
            continue
        rows.append([rn, en] + year_cells(st["by_year"], years) + [rs(st["st"]["tot"])])
    return table(["rule", "exit"] + [str(y) for y in years] + ["total"], rows)


def long_short_table(allres, sym, en="2ATR stop + 3ATR chandelier (max 20d)"):
    rows = []
    for (s, rn, e), (tr, st) in allres.items():
        if s != sym or e != en or tr.empty:
            continue
        L, S = tr[tr.dir > 0], tr[tr.dir < 0]
        rows.append([rn, len(L), rs(L.net.sum()), f"{100 * (L.net > 0).mean():.0f}%" if len(L) else "",
                     len(S), rs(S.net.sum()), f"{100 * (S.net > 0).mean():.0f}%" if len(S) else ""])
    return table(["rule", "long trades", "long Rs", "long win", "short trades", "short Rs", "short win"], rows)


def gap_event_tables(allres, sym, en="2ATR stop + 3ATR chandelier (max 20d)", en_ev="fixed 10d"):
    rows, ev_rows = [], []
    for (s, rn, e), (tr, st) in allres.items():
        if s != sym or e not in (en, en_ev) or tr.empty or rn.startswith("Coin"):
            continue
        if e == en_ev:
            flag = np.zeros(len(tr), bool)
            for name, dts in EVENTS.items():
                flag |= np.array([((dts >= a) & (dts <= b)).any() for a, b in zip(tr.ed, tr.xd)])
            E, N = tr[flag], tr[~flag]
            ev_rows.append([rn, len(E), rs(E.net.mean()) if len(E) else "",
                            f"{100 * (E.net > 0).mean():.0f}%" if len(E) else "",
                            len(N), rs(N.net.mean()) if len(N) else "", f"{100 * (N.net > 0).mean():.0f}%" if len(N) else ""])
            continue
        rows.append([rn, rs(st["gross"]), rs(st["gap_share"]), rs(st["intraday"]), rs(st["dd"]), rs(st["dd_gap"]),
                     rs(st["dd_intra"])])
    t1 = table(["rule", "gross points P&L Rs", "from overnight gaps", "from trading hours", "max DD (all)",
                "max DD of gap part alone", "max DD of trading-hours part alone"], rows)
    t2 = table(["rule", "trades spanning budget/RBI/election", "avg net Rs", "win", "other trades", "avg net Rs",
                "win"], ev_rows)
    return t1, t2

# ======================================================================================== SECTION: monthly options

OPT_START = {"NIFTY": pd.Timestamp("2020-08-03"), "BANKNIFTY": pd.Timestamp("2021-09-01")}
STRUCTS = {"bought ATM": [(1, 0, 0)],
           "debit spread: buy ATM, sell 2 strikes OTM": [(1, 0, 0), (-1, 2, 0)],
           "debit spread: buy ATM, sell 4 strikes OTM": [(1, 0, 0), (-1, 4, 0)]}
OPT_RULES = ["Donchian 20-day breakout", "Donchian 55-day breakout", "Pullback: >200DMA & RSI(2)<10",
             "Pullback: >200DMA & 3 down closes", "EMA 20/50 trend", "52-week high/low momentum",
             "Mean reversion: buy after big down day", "Coin flip (random side, ~15% of days)",
             "Always long (buy-and-hold proxy)"]
OPT_EXITS = ["fixed 5d", "fixed 10d", "2ATR stop + 3ATR chandelier (max 20d)"]
BOOKS = {}


def book(sym, kind):
    if (sym, kind) not in BOOKS:
        last = "2026-10-27" if kind == "MONTH" else "2026-10-13"
        BOOKS[(sym, kind)] = Book(sym, kind, 0.006 if kind == "MONTH" else 0.004, last)
        if kind == "WEEK":
            BOOKS[(sym, kind)].iv_proxy = book(sym, "MONTH")
    return BOOKS[(sym, kind)]


def close_mode_trades(d, sig, ex, start):
    tr = run_index(d, sig, ex, "close")
    return [t for t in tr if t["ed"] >= start]


def options_section(idx):
    md = ["## 2. Bought monthly options and monthly debit spreads (real option prices)", "",
          "Same daily signals; the option is bought at 09:20 the next morning (real minute price of the ATM call for a "
          "long signal, ATM put for a short signal; nearest monthly expiry) and sold at 09:20 on the exit morning. "
          "Stops are judged on the daily close of the index (exit next 09:20) because an option cannot carry the "
          "index stop order. If the monthly contract expires inside the hold it is sold at 15:25 on expiry day and "
          "re-bought (new ATM, next month) next morning - costs paid twice. 1 lot, slippage max(0.5 pt NIFTY / 1 pt "
          "BANKNIFTY, 0.25% of premium) a side per leg, Rs 20/order, STT 0.1% of premium on sells, exchange 0.035%, "
          "stamp, GST. avg R = net / premium paid (net debit). Data: NIFTY Aug-2020 on, BANKNIFTY Sep-2021 on. "
          "When the strike leaves the +/-10-strike window in the data (big moves), it is priced by Black-Scholes with "
          "the nearest strike's IV - the share of such legs is shown.", ""]
    results = {}
    for sym, d in idx.items():
        years = list(range(OPT_START[sym].year, 2027))
        B = book(sym, "MONTH")
        dates = list(d.index)
        sigs = index_signals(d)
        rows, yrows = [], []
        for rn in OPT_RULES:
            for en in OPT_EXITS:
                trs = close_mode_trades(d, sigs[rn], EXITS[en], OPT_START[sym])
                fut = fut_pnl(trs, d, sym) if trs else pd.DataFrame()
                for sn, legs in STRUCTS.items():
                    B.model = B.real = 0
                    out = []
                    for t in trs:
                        slot = "pm" if t["why"] == "end of data" else "am"
                        r = option_trade(B, dates, t["dir"], t["ed"], t["xd"], legs, roll=True, x_slot=slot)
                        if r is not None:
                            out.append(dict(year=t["ed"].year, net=r["net"], debit=r["debit"], theta=r["theta"],
                                            ed=t["ed"], dir=t["dir"]))
                    o = pd.DataFrame(out)
                    if o.empty:
                        continue
                    results[(sym, rn, en, sn)] = o
                    st = trade_stats(o.net.values, (o.net / o.debit.abs().clip(lower=1)).values)
                    by = o.groupby("year").net.sum()
                    eq = o.sort_values("ed").net.cumsum()
                    mshare = B.model / max(1, B.model + B.real)
                    rows.append([rn, en, sn, st["n"], st["win"], st["avgR"], st["pf"], rs(st["tot"]),
                                 f"{int((by > 0).sum())}/{len(by)}", rs(float((eq - eq.cummax()).min())),
                                 st["streak"], rs(o.theta.sum()), rs(fut.net.sum()) if sn == "bought ATM" and len(fut) else "",
                                 f"{100 * mshare:.0f}%"])
                    if sn == "bought ATM" or en == "fixed 10d":
                        yrows.append([rn, en, sn] + year_cells(by.to_dict(), years) + [rs(st["tot"])])
        cfg = {(rn, en, sn): o.groupby("year").net.sum().to_dict() for (sy, rn, en, sn), o in results.items()
               if sy == sym and not rn.startswith(("Coin", "Always"))}
        first = OPT_START[sym].year + 1 if OPT_START[sym].month < 9 else OPT_START[sym].year + 2
        wrows, oos = walk_forward(cfg, first_test=first)
        KEY[("wf_opt", sym)] = (sum(oos.values()), sum(1 for v in oos.values() if v > 0), len(oos))
        md += [f"### {sym} monthly options: walk-forward (config with the best profit over all earlier years, traded "
               f"blind the next year)", "", table(["test year", "config chosen", "training profit Rs", "out-of-sample Rs"], wrows),
               "", f"Out-of-sample total: Rs {rs(sum(oos.values()))} per lot "
               f"({sum(1 for v in oos.values() if v > 0)}/{len(oos)} years positive).", ""]
        md += [f"### {sym} monthly options: totals (Rs per lot, after costs)", "",
               table(["rule", "exit", "structure", "trades", "win", "avg R", "PF", "total Rs", "years +",
                      "max DD (closed trades)", "longest losing run", "theta paid (BS est.)",
                      "same trades in futures Rs", "legs model-priced"], rows), "",
               f"### {sym} monthly options: by year (Rs per lot)", "",
               table(["rule", "exit", "structure"] + [str(y) for y in years] + ["total"], yrows), ""]
    return md, results

# ======================================================================================== SECTION: weekly vs monthly, theta


def theta_section(idx):
    md = ["## 7. Golden rule A - which expiry should a swing option buyer use? (weekly vs next-week vs monthly)", "",
          "Signals: Donchian 20-day breakout (the 'typical' rule, the one the earlier SWING.md study liked) and "
          "RSI(2) pullback (the best close-entry rule on BANKNIFTY futures). Entries at 09:20 after the signal, "
          "exit at 09:20 after 1, 3 or 5 sessions. Signal days that are themselves a weekly expiry day are skipped "
          "for every variant so all variants trade the same days. Expiry dates are taken from the data itself "
          "(the day the series rolls), so the Thursday -> Wednesday (BANKNIFTY 2023-24) -> Tuesday (NIFTY from "
          "Sep-2025) changes are followed automatically. 'OTM' = 2 strikes out of the money (100 pts NIFTY, 200 pts "
          "BANKNIFTY). Weekly held to plan: if expiry comes first the option is sold at 15:25 on expiry day (no roll). "
          "Golden rule = sell the weekly by 15:25 of the session BEFORE expiry (never hold it into expiry day); "
          "'+ roll' = then buy the next weekly OTM at 09:20 next session and continue to the planned exit. "
          "Next-week options are not in the data until they become the nearest weekly, so their ENTRY price is "
          "Black-Scholes with the current weekly's IV (exit is a real price once it is the nearest weekly) - "
          "treat those rows as approximate. BANKNIFTY weeklies stop in Nov-2024 (NSE discontinued them).", ""]
    rules = ["Donchian 20-day breakout", "Pullback: >200DMA & RSI(2)<10"]
    for sym, d in idx.items():
        W, M = book(sym, "WEEK"), book(sym, "MONTH")
        wexp = set(W.exp[:-1])
        wend = max(W.dates)
        dates = list(d.index)
        di = {x: i for i, x in enumerate(dates)}

        def golden(E):
            i = di.get(E)
            return (dates[i - 1], "pm") if i else None
        sigs = index_signals(d)
        rows, dte_rows = [], []
        dte_acc = {}
        for rn in rules:
            for H in (1, 3, 5):
                trs = close_mode_trades(d, sigs[rn], dict(kind="fixed", N=H), OPT_START[sym])
                trs = [t for t in trs if t["ed"] not in wexp and t["xd"] <= wend and t["why"] != "end of data"]
                variants = {
                    "weekly ATM, held to plan": (W, [(1, 0, 0)], False, None),
                    "weekly OTM, held to plan": (W, [(1, 2, 0)], False, None),
                    "weekly OTM, golden rule (out before expiry day)": (W, [(1, 2, 0)], False, golden),
                    "weekly OTM, golden rule + roll to next week": (W, [(1, 2, 0)], True, golden),
                    "next-week ATM (approx. entry price)": (W, [(1, 0, 1)], False, None),
                    "monthly ATM": (M, [(1, 0, 0)], True, None),
                    "monthly OTM": (M, [(1, 2, 0)], True, None),
                    "monthly ATM priced by the same model (bias check)": (M, [(1, 0, 0)], True, "model"),
                }
                for vn, (bk, legs, roll, lo) in variants.items():
                    out = []
                    am = lo == "model"
                    lo = None if am else lo
                    for t in trs:
                        r = option_trade(bk, dates, t["dir"], t["ed"], t["xd"], legs, roll=roll, last_ok=lo, all_model=am)
                        if r is None:
                            continue
                        dte = di.get(bk.exp_for(t["ed"]), 0) - di.get(t["ed"], 0)
                        out.append(dict(net=r["net"], debit=r["debit"], theta=r["theta"], year=t["ed"].year, dte=dte))
                        if (vn.startswith("weekly") and "held" in vn) or vn == "monthly ATM":
                            b = dte if dte < 5 else ("5-9" if dte < 10 else ("10-14" if dte < 15 else "15+"))
                            dte_acc.setdefault((vn, str(b)), []).append((r["net"], r["theta"], r["debit"]))
                    o = pd.DataFrame(out)
                    if o.empty:
                        continue
                    st = trade_stats(o.net.values)
                    by = o.groupby("year").net.sum()
                    rows.append([rn, H, vn, st["n"], st["win"], rs(st["tot"]), rs(o.net.mean()), rs(o.debit.mean()),
                                 rs(o.theta.mean()), f"{int((by > 0).sum())}/{len(by)}"])
        md += [f"### {sym}: same signals, different contracts (Rs per lot after costs)", "",
               table(["rule", "hold (sessions)", "contract", "trades", "win", "total Rs", "avg Rs/trade",
                      "avg premium paid", "avg theta paid/trade (BS est.)", "years +"], rows), ""]
        order = {str(i): i for i in range(5)} | {"5-9": 5, "10-14": 6, "15+": 7}
        for (vn, b), v in sorted(dte_acc.items(), key=lambda kv: (kv[0][0], order[kv[0][1]])):
            a = np.array(v)
            dte_rows.append([vn, b, len(a), rs(a[:, 0].mean()), rs(a[:, 1].mean()),
                             f"{100 * a[:, 1].sum() / a[:, 2].sum():.1f}%", f"{100 * np.mean(a[:, 0] > 0):.0f}%"])
        md += [f"### {sym}: what theta eats, by sessions to the contract's own expiry at entry (both rules, all holds pooled)", "",
               table(["contract", "sessions to its expiry at entry", "trades", "avg net Rs", "avg theta Rs",
                      "theta as % of premium", "win"], dte_rows), ""]
    return md

# ======================================================================================== SECTION: OI confirmation


def oi_features(B):
    """Per date (15:25 snapshot): PCR change and OI change of puts at/below spot vs calls at/above spot, against the
    previous session of the SAME contract. Known at 15:25 on the signal day, i.e. before the next-morning entry."""
    snap = {}
    for (dt, sl, ty), (k, iv, oi) in B.chain.items():
        if sl == "pm":
            snap[(dt, ty)] = dict(zip(k, oi))
    out = {}
    prev = None
    for dt in B.dates:
        if (dt, "C") not in snap or (dt, "P") not in snap:
            continue
        if prev is not None and B.exp_for(prev) == B.exp_for(dt):
            S = B.S(dt, "pm")
            c0, p0, c1, p1 = snap[(prev, "C")], snap[(prev, "P")], snap[(dt, "C")], snap[(dt, "P")]
            ks = sorted(set(c0) & set(p0) & set(c1) & set(p1))
            if len(ks) >= 8:
                pcr0 = sum(p0[k] for k in ks) / max(1, sum(c0[k] for k in ks))
                pcr1 = sum(p1[k] for k in ks) / max(1, sum(c1[k] for k in ks))
                dput = sum(p1[k] - p0[k] for k in ks if k <= S)
                dcall = sum(c1[k] - c0[k] for k in ks if k >= S)
                out[dt] = dict(dpcr=pcr1 - pcr0, dput=dput, dcall=dcall)
        prev = dt
    return out


def oi_section(idx, allres, opt_results):
    md = ["## 8. Golden rule B - does open-interest confirmation improve the swing signals?", "",
          "OI comes from the monthly option chain in the data (+/-10 strikes around ATM), read at 15:25 on the signal "
          "day and compared with 15:25 the previous session of the same contract - so it is known before the "
          "next-morning entry (no look-ahead). Two filters: (1) PCR rising for a long / falling for a short; "
          "(2) 'writers' build-up': for a long, put OI added at/below spot exceeds call OI added at/above spot "
          "(put writers defending, call writers leaving); mirror for a short. Days right after a monthly roll have no "
          "same-contract comparison and count as 'unconfirmed'. Futures OI cannot be tested: the data holds only the "
          "three live futures contracts (no expired contracts), so there is no futures OI history for 2016-2025.", ""]
    for sym, d in idx.items():
        B = book(sym, "MONTH")
        F = oi_features(B)
        first = min(F) if F else OPT_START[sym]
        rows = []
        for rn in ["Donchian 20-day breakout", "Pullback: >200DMA & RSI(2)<10", "EMA 20/50 trend",
                   "NR7 / inside-day breakout", "Mean reversion: buy after big down day",
                   "Coin flip (random side, ~15% of days)"]:
            for kind, en in (("futures", "fixed 10d"), ("futures", "2ATR stop + 3ATR chandelier (max 20d)"),
                             ("ATM option", "fixed 10d")):
                if kind == "futures":
                    tr = allres[(sym, rn, en)][0]
                    if tr.empty:
                        continue
                    tr = tr[tr.ed >= first].copy()
                    sig_day = [d.index[i - 1] for i in tr.ei]
                else:
                    o = opt_results.get((sym, rn, en, "bought ATM"))
                    if o is None:
                        continue
                    tr = o[o.ed >= first].copy()
                    pos = {x: i for i, x in enumerate(d.index)}
                    sig_day = [d.index[pos[e] - 1] for e in tr.ed]
                for fn, f in (("PCR rising (long) / falling (short)", lambda z, dr: z["dpcr"] * dr > 0),
                              ("writers' build-up", lambda z, dr: (z["dput"] - z["dcall"]) * dr > 0)):
                    ok = np.array([(sd in F) and bool(f(F[sd], dr)) for sd, dr in zip(sig_day, tr.dir)])
                    A, K, R = tr, tr[ok], tr[~ok]
                    ys = sorted(set(A.year))
                    rows.append([rn, f"{kind}, {en}", fn, len(A), f"{100 * (A.net > 0).mean():.0f}%", rs(A.net.sum()),
                                 len(K), f"{100 * (K.net > 0).mean():.0f}%" if len(K) else "", rs(K.net.sum()),
                                 rs(R.net.sum()),
                                 f"{sum(1 for y in ys if (K.year == y).any() and (R.year == y).any() and K[K.year == y].net.mean() > R[R.year == y].net.mean())}/{len(ys)}"])
        md += [f"### {sym} (from {first.date()})", "",
               table(["rule", "instrument / exit", "OI filter", "all trades", "win", "net Rs", "kept", "win (kept)",
                      "net Rs (kept)", "net Rs (dropped)", "years the kept set beat the dropped set (per trade)"], rows),
               ""]
    return md

# ======================================================================================== SECTION: F&O stocks (cash)

SECTORS = ["NIFTY_AUTO", "NIFTY_PHARMA", "NIFTYIT", "NIFTY_METAL", "NIFTY_FMCG", "NIFTY_REALTY", "NIFTY_ENERGY",
           "NIFTY_PSU_BANK", "NIFTY_PVT_BANK", "NIFTY_MEDIA", "FINNIFTY", "NIFTYINFRA", "NIFTY_CONSUMPTION"]
ABSORB = ["NIFTY_MIDCAP_150"]      # a stock whose best match is the mid-cap index itself gets no sector


class Panel:
    def __init__(self, dates):
        files = sorted(glob.glob(os.path.join(DATA, "candles", "daily", "NSE_EQ", "*.parquet")))
        cols = {k: {} for k in ("open", "high", "low", "close", "volume")}
        for f in files:
            x = read_daily(f)
            x = x[x.index >= WARM - pd.Timedelta(days=420)]
            for k in cols:
                cols[k][os.path.basename(f)[:-8]] = x[k]
        self.names = sorted(cols["close"])
        fr = {k: pd.DataFrame(v).reindex(columns=self.names) for k, v in cols.items()}
        allidx = fr["close"].index.union(dates)
        fr = {k: v.reindex(allidx) for k, v in fr.items()}
        O, H, L, C, V = fr["open"], fr["high"], fr["low"], fr["close"], fr["volume"]
        pc = C.shift(1)
        tr = np.maximum(H - L, np.maximum((H - pc).abs(), (L - pc).abs()))
        ind = {}
        ind["atr"] = tr.ewm(alpha=1 / 14, adjust=False).mean()
        ind["sma200"] = C.rolling(200).mean()
        ind["sma5"] = C.rolling(5).mean()
        ind["ema20"] = C.ewm(span=20, adjust=False).mean()
        ind["ema50"] = C.ewm(span=50, adjust=False).mean()
        dc = C.diff()
        for n in (2, 14):
            g = dc.clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
            ls = (-dc).clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
            ind[f"rsi{n}"] = 100 - 100 / (1 + g / ls.replace(0, 1e-12))
        m = C.ewm(span=12, adjust=False).mean() - C.ewm(span=26, adjust=False).mean()
        ind["macd"], ind["macds"] = m, m.ewm(span=9, adjust=False).mean()
        for n in (20, 55, 252):
            ind[f"hi{n}"] = H.shift(1).rolling(n).max()
        rng = H - L
        ind["nr7"] = rng <= rng.rolling(7).min()
        ind["inside"] = (H < H.shift(1)) & (L > L.shift(1))
        w = (H.rolling(20).max() - L.rolling(20).min()) / C
        ind["wrank"] = w.rolling(250, min_periods=120).rank(pct=True)
        ind["vol20"] = V.shift(1).rolling(20).mean()
        ind["ret1"] = C / pc - 1
        ind["sd20"] = ind["ret1"].shift(1).rolling(20).std()
        ind["ret126"] = C / C.shift(126) - 1
        ind["ret_12_1"] = C.shift(21) / C.shift(252) - 1
        tv = (C * V).rolling(20).median()
        nbars = C.notna().cumsum()
        ind["elig"] = (C >= 50) & (tv >= 5e7) & (nbars >= 260)
        tvr = (C * V).rolling(60).mean().where(ind["elig"])
        rk = tvr.rank(axis=1, ascending=False)
        ind["large"] = ind["elig"] & (rk <= 50)
        ind["mid"] = ind["elig"] & (rk > 50)
        keep = allidx[allidx >= WARM]
        keep = keep[keep.isin(dates)]
        self.dates = keep
        self.O, self.H, self.L, self.C, self.V = (z.loc[keep] for z in (O, H, L, C, V))
        self.ind = {k: v.loc[keep] for k, v in ind.items()}
        self.A = {k: v.values.astype(float) for k, v in (("O", self.O), ("H", self.H), ("L", self.L), ("C", self.C),
                                                          ("atr", self.ind["atr"]), ("sma5", self.ind["sma5"]))}

    def sector_map(self):
        idxr = {}
        nifty = read_daily(os.path.join(DATA, "candles", "daily", "IDX_I", "NIFTY.parquet")).close
        win = (nifty.index >= "2017-01-01") & (nifty.index < "2020-01-01")
        nr = nifty.pct_change()
        for s in SECTORS + ABSORB:
            x = read_daily(os.path.join(DATA, "candles", "daily", "IDX_I", f"{s}.parquet")).close
            idxr[s] = (x.pct_change() - nr).reindex(nifty.index[win])
        R = pd.DataFrame(idxr)
        out = {}
        sr = (self.C.pct_change().sub(nr.reindex(self.C.index), axis=0))
        for nm in self.names:
            y = sr[nm].reindex(R.index)
            if y.notna().sum() < 200:
                y = sr[nm].dropna().iloc[:500]
                Rw = pd.DataFrame({s: (read_daily(os.path.join(DATA, "candles", "daily", "IDX_I", f"{s}.parquet")).close.pct_change()
                                       - nr).reindex(y.index) for s in SECTORS + ABSORB})
            else:
                Rw = R
            cc = Rw.corrwith(y)
            best = cc.idxmax() if cc.notna().any() else None
            out[nm] = None if best in ABSORB else best
        return out

    def sector_strong(self, smap):
        nifty = read_daily(os.path.join(DATA, "candles", "daily", "IDX_I", "NIFTY.parquet")).close.reindex(self.dates)
        strong = {}
        for s in SECTORS:
            x = read_daily(os.path.join(DATA, "candles", "daily", "IDX_I", f"{s}.parquet")).close.reindex(self.dates).ffill()
            strong[s] = (x > x.ewm(span=50, adjust=False).mean()) & (x / x.shift(20) > nifty / nifty.shift(20))
        M = pd.DataFrame(False, index=self.dates, columns=self.names)
        for nm, s in smap.items():
            if s is not None:
                M[nm] = strong[s].values
        return M


def stock_signals(P, smap=None):
    I, C, O, H, L, V = P.ind, P.C, P.O, P.H, P.L, P.V
    el = I["elig"]
    s = {}
    up200 = C > I["sma200"]
    s["Breakout 20-day high (>200DMA)"] = el & (C > I["hi20"]) & up200
    s["Breakout 55-day high (>200DMA)"] = el & (C > I["hi55"]) & up200
    s["52-week-high momentum"] = el & (C >= I["hi252"])
    s["Pullback: >200DMA & RSI(2)<10"] = el & up200 & (I["rsi2"] < 10)
    dn3 = (C < C.shift(1)) & (C.shift(1) < C.shift(2)) & (C.shift(2) < C.shift(3))
    s["Pullback: >200DMA & 3 down closes"] = el & up200 & dn3
    e20, e50 = I["ema20"], I["ema50"]
    s["EMA 20/50 bullish cross"] = el & (e20 > e50) & (e20.shift(1) <= e50.shift(1)) & (C > e20)
    s["NR7/inside day, buy-stop above its high (>200DMA)"] = el & (I["nr7"] | I["inside"]) & up200
    s["Mean reversion: big down day (>200DMA)"] = el & up200 & (I["ret1"] < -2.5 * I["sd20"])
    # --- the three 'popular' strategies Boss asked about
    body = (C - O).abs()
    rng = (H - L).replace(0, np.nan)
    hammer = ((np.minimum(O, C) - L) >= 2 * body) & ((H - np.maximum(O, C)) <= body.clip(lower=0.1 * rng)) & \
             ((C - L) / rng >= 0.6)
    engulf = (C > O) & (C.shift(1) < O.shift(1)) & (C >= O.shift(1)) & (O <= C.shift(1))
    uptrend = (e20 > e50) & (e50 > e50.shift(5)) & (e20 > e20.shift(5))
    touch = (L <= e20 * 1.005) | (L <= e50 * 1.005)
    s["P1 trend pullback to 20/50 EMA + hammer/engulfing"] = el & uptrend & touch & (C > e50) & (hammer | engulf)
    s["P1 same, without the candle confirmation"] = el & uptrend & touch & (C > e50)
    tight = (I["wrank"].shift(1) <= 0.2) | I["nr7"].shift(1).fillna(False).astype(bool)
    boA = el & tight & (C > I["hi20"]) & (V > 1.5 * I["vol20"])
    s["P2A tight range + volume breakout, buy next open"] = boA
    lvl = I["hi20"].where(boA)
    rt = pd.DataFrame(False, index=C.index, columns=C.columns)
    for k in range(1, 6):
        lk = lvl.shift(k)
        rt |= lk.notna() & (L <= lk * 1.005) & (C >= lk * 0.99)
    s["P2B same breakout, buy after a retest of the level (within 5 days)"] = el & rt
    s["P2 breakout without the volume/tight-range filters"] = el & (C > I["hi20"])
    if smap is not None:
        s["P2A + sector index also strong"] = boA & P.sector_strong(smap)
    r14 = I["rsi14"]
    zone = (r14.shift(1).rolling(3).min() >= 40) & (r14.shift(1).rolling(3).min() <= 50)
    mx = (I["macd"] > I["macds"]) & ((I["macd"].shift(1) <= I["macds"].shift(1)) | (I["macd"].shift(2) <= I["macds"].shift(2)))
    s["P3 RSI(14) bounce from 40-50 + MACD cross, >50EMA"] = el & (C > e50) & zone & (r14 > r14.shift(1)) & mx
    s["P3 RSI(14) oversold: crosses back above 30"] = el & (r14.shift(1) < 30) & (r14 >= 30)
    return {k: v.fillna(False).values.astype(bool) for k, v in s.items()}


STK_EXITS = {
    "fixed 5d": dict(kind="fixed", N=5),
    "fixed 10d": dict(kind="fixed", N=10),
    "fixed 20d": dict(kind="fixed", N=20),
    "2ATR stop + 3ATR chandelier (max 10d)": dict(kind="stop", m=2.0, trail=3.0, N=10),
    "2ATR stop + 3ATR chandelier (max 20d)": dict(kind="stop", m=2.0, trail=3.0, N=20),
    "2ATR stop + 10d time stop": dict(kind="stop", m=2.0, trail=None, N=10),
    "exit on close > 5DMA (max 10d), 3ATR disaster stop": dict(kind="ma5", m=3.0, trail=None, N=10),
}


def stock_sim(P, sig, ex, score=None, maxN=10, risk=0.01, maxw=0.2, stop_entry=False, start=START, mask=None):
    """Long-only cash portfolio. Signal on close -> entry next open (or buy-stop above the signal day's high).
    Size = risk x equity / (2 x ATR), capped at maxw of equity and by cash. Returns (equity Series, trades df)."""
    A = P.A
    O, H, L, C, atr, sma5 = A["O"], A["H"], A["L"], A["C"], A["atr"], A["sma5"]
    T, S = C.shape
    if mask is not None:
        sig = sig & mask
    sc = score if score is not None else np.nan_to_num(P.ind["ret126"].values, nan=-9)
    i0 = int(np.searchsorted(P.dates.values, np.datetime64(start)))
    cash, pos, pend_in, pend_out = CAP, {}, [], set()
    eq = np.full(T, np.nan)
    eq[:i0] = CAP
    trades = []
    last_close = np.where(np.isnan(C), np.nan, C)
    lastpx = pd.DataFrame(C).ffill().values
    for i in range(i0, T):
        eqp = eq[i - 1]
        # exits scheduled at the open
        for s in list(pend_out):
            if s in pos and np.isfinite(O[i, s]):
                p = pos.pop(s)
                px = O[i, s] * (1 - EQ_SLIP)
                cash += _close_stock(p, s, i, px, trades, P, False)
                pend_out.discard(s)
        # entries
        if pend_in:
            pend_in.sort(key=lambda z: -z[1])
            for s, _, lvl, a in pend_in:
                if len(pos) >= maxN or s in pos or not np.isfinite(O[i, s]) or not (a > 0):
                    continue
                if stop_entry:
                    if not (H[i, s] >= lvl):
                        continue
                    px = max(O[i, s], lvl) * (1 + EQ_SLIP)
                else:
                    px = O[i, s] * (1 + EQ_SLIP)
                sh = int(min(risk * eqp / (2.0 * a), maxw * eqp / px, (cash - 100) / (px * 1.0012)))
                if sh < 1 or sh * px < 10_000:          # no tiny leftover-cash positions
                    continue
                cost = eq_side_cost(sh * px, "buy")
                cash -= sh * px + cost
                stop = px - ex.get("m", 2.0) * a if ex["kind"] in ("stop", "ma5") else None
                pos[s] = dict(sh=sh, ep=px, ei=i, stop=stop, ext=px, R=sh * 2.0 * a, cost_in=cost, gap=0.0)
            pend_in = []
        # intraday stops (checked on the entry day too - conservative)
        for s in list(pos):
            p = pos[s]
            if p["stop"] is not None and np.isfinite(L[i, s]) and L[i, s] <= p["stop"]:
                px = min(O[i, s], p["stop"]) * (1 - 2 * EQ_SLIP)
                pos.pop(s)
                pend_out.discard(s)
                cash += _close_stock(p, s, i, px, trades, P, True)
        # close: mark to market and bookkeeping
        mv = 0.0
        for s, p in pos.items():
            if i > p["ei"] and np.isfinite(O[i, s]) and np.isfinite(C[i - 1, s]):
                p["gap"] += (O[i, s] - C[i - 1, s]) * p["sh"]
            mv += p["sh"] * lastpx[i, s]
            held = i - p["ei"] + 1
            if held >= ex["N"]:
                pend_out.add(s)
            if ex["kind"] == "ma5" and C[i, s] > sma5[i, s]:
                pend_out.add(s)
            if ex["kind"] == "stop" and ex["trail"] and np.isfinite(H[i, s]):
                p["ext"] = max(p["ext"], H[i, s])
                p["stop"] = max(p["stop"], p["ext"] - ex["trail"] * atr[i, s])
        eq[i] = cash + mv
        if i < T - 1:
            free = maxN - len(pos) + len(pend_out)
            if free > 0:
                cand = np.flatnonzero(sig[i])
                cand = [s for s in cand if s not in pos]
                if cand:
                    cand.sort(key=lambda s: -sc[i, s])
                    pend_in = [(s, sc[i, s], H[i, s], atr[i, s]) for s in cand[:free + 5]]
    for s, p in list(pos.items()):
        cash += _close_stock(p, s, T - 1, lastpx[T - 1, s], trades, P, False)
    return pd.Series(eq[i0:], index=P.dates[i0:]), pd.DataFrame(trades)


def _close_stock(p, s, i, px, trades, P, stop_exit):
    val = p["sh"] * px
    cost = eq_side_cost(val, "sell")
    net = val - p["sh"] * p["ep"] - cost - p["cost_in"]
    trades.append(dict(s=s, ei=p["ei"], xi=i, ed=P.dates[p["ei"]], xd=P.dates[i], net=net, R=net / p["R"],
                       gap=p["gap"], gross=val - p["sh"] * p["ep"], year=P.dates[p["ei"]].year, stop=stop_exit))
    return val - cost


def eq_stats(eq, tr):
    yr = eq.groupby(eq.index.year).last()
    prev = yr.shift(1).fillna(CAP)
    yret = (yr / prev - 1)
    m = eq.groupby([eq.index.year, eq.index.month]).last()
    mret = m / m.shift(1).fillna(CAP) - 1
    dd = float((eq / eq.cummax() - 1).min())
    st = trade_stats(tr.net.values, tr.R.values) if len(tr) else trade_stats([])
    yrs = len(eq) / 248
    cagr = (eq.iloc[-1] / CAP) ** (1 / yrs) - 1
    gs = tr.gap.sum() if len(tr) else 0.0
    return dict(st=st, yret=yret.to_dict(), dd=dd, worst_m=float(mret.min()), cagr=cagr,
                pos_years=int((yret > 0).sum()), n_years=len(yret), gap=gs, gross=tr.gross.sum() if len(tr) else 0.0)


def pct(x):
    return f"{100 * x:+.1f}%"


def random_like(sig, base_mask, rng):
    """Same number of picks per day as the rule, drawn at random from the same eligible group."""
    out = np.zeros_like(sig)
    cnt = sig.sum(axis=1)
    for i in np.flatnonzero(cnt):
        pool = np.flatnonzero(base_mask[i])
        if len(pool):
            out[i, rng.choice(pool, size=min(cnt[i], len(pool)), replace=False)] = True
    return out


def signal_edge(P, sig, N=10, cost=0.004, mask=None):
    """Per-signal forward return (next open -> open N sessions later, minus ~0.4% round-trip cost) against the
    average of ALL eligible stocks over the same days (the 'random stock, same day' baseline). By year."""
    O = P.A["O"]
    T = O.shape[0]
    fwd = np.full_like(O, np.nan)
    fwd[:T - N - 1] = O[N + 1:] / O[1:T - N] - 1
    el = P.ind["elig"].values if mask is None else mask
    base = np.where(el, fwd, np.nan)
    bmean = np.nanmean(base, axis=1)
    rows = {}
    ii, ss = np.nonzero(sig)
    for i, s in zip(ii, ss):
        if P.dates[i] < START or not np.isfinite(fwd[i, s]):
            continue
        y = P.dates[i].year
        rows.setdefault(y, []).append((fwd[i, s] - cost, bmean[i] - cost))
    out = {}
    for y, v in rows.items():
        a = np.array(v)
        out[y] = (len(a), a[:, 0].mean(), a[:, 0].mean() - a[:, 1].mean(), np.mean(a[:, 0] > 0))
    return out


def stock_section(P, smap):
    S = stock_signals(P, smap)
    el = P.ind["elig"].values
    large, mid = P.ind["large"].values, P.ind["mid"].values
    years = list(range(2016, 2027))
    rng = np.random.default_rng(11)
    cfg_years = {}
    md = ["## 3. F&O stocks in cash/delivery - a swing portfolio (2016 - Oct 2026)", "",
          f"Universe: the {len(P.names)} stocks that are in the F&O segment TODAY (survivorship bias - see section 6), "
          "only on days a stock had price >= Rs 50, 20-day median traded value >= Rs 5 crore and 260+ days of history. "
          "Long only (delivery cannot be shorted overnight). Start Rs 5 lakh, compounding; each new position risks 1% of "
          "equity against a 2xATR stop distance, capped at 20% of equity and by available cash; max 10 positions; when "
          "more stocks signal than there are slots, the strongest 6-month momentum is taken first. Signal on the close, "
          "buy next open; stops are SL orders (gap-through fills at the open). Costs: Rs 20/order, STT 0.1% both "
          "sides, exchange, SEBI, stamp 0.015% on buys, DP Rs 15.93 per sale, GST, slippage 0.1% a side (0.2% on stop "
          "fills). Yearly numbers are % return of the account; x 5 lakh gives rupees.", ""]
    classic = [k for k in S if k[:2] not in ("P1", "P2", "P3")]
    rows, yrows, brow = [], [], []
    for rn in classic:
        exits = ["fixed 10d", "fixed 20d", "2ATR stop + 3ATR chandelier (max 20d)", "2ATR stop + 10d time stop"]
        if rn.startswith("Pullback") or rn.startswith("Mean"):
            exits.append("exit on close > 5DMA (max 10d), 3ATR disaster stop")
        for en in exits:
            eqs, tr = stock_sim(P, S[rn], STK_EXITS[en], stop_entry=rn.startswith("NR7"))
            st = eq_stats(eqs, tr)
            cfg_years[(rn, "all F&O", en)] = st["yret"]
            rows.append(_stock_row(rn, en, st))
            if en in ("fixed 10d", "2ATR stop + 3ATR chandelier (max 20d)"):
                yrows.append([rn, en] + [pct(st["yret"].get(y, 0)) for y in years] + [pct(st["cagr"])])
        # random same-day baseline (3 seeds), fixed 10d
        rc, ry = [], []
        for k in range(3):
            rs_ = random_like(S[rn], el, rng)
            e2, t2 = stock_sim(P, rs_, STK_EXITS["fixed 10d"], score=rng.random(el.shape), stop_entry=rn.startswith("NR7"))
            s2 = eq_stats(e2, t2)
            rc.append(s2["cagr"])
            ry.append(s2["pos_years"])
        KEY[("stock_rand", rn)] = np.mean(rc)
        brow.append([rn, pct(np.mean(rc)), f"{np.mean(ry):.1f}/11"])
    hdr = ["rule", "exit", "trades", "win", "avg R", "PF", "CAGR", "~Rs/yr on 5L (CAGR x 5L)", "years +", "max DD",
           "worst month", "longest losing run", "P&L from overnight gaps Rs", "P&L in trading hours Rs"]
    md += ["### Classic rules on the F&O stock universe", "", table(hdr, rows), "",
           "### Same rules, % return by year", "",
           table(["rule", "exit"] + [str(y) for y in years] + ["CAGR"], yrows), "",
           "### Baseline: buy RANDOM eligible stocks on the same days, same count, fixed 10-day hold (3 seeds)", "",
           table(["rule whose days/counts are copied", "random-stock CAGR", "random years +"], brow), ""]
    # signal-level edge vs same-day universe
    erows = []
    for rn in classic:
        ed = signal_edge(P, S[rn], 10)
        erows.append([rn] + [f"{100 * ed[y][2]:+.2f}" if y in ed else "" for y in years] +
                     [f"{sum(1 for y in ed if ed[y][2] > 0)}/{len(ed)}"])
    md += ["### Per-signal edge: 10-day net return of the signalled stock MINUS the average eligible stock over the "
           "same 10 days (percentage points, by year of signal)", "",
           "This ignores portfolio limits and sizing; it answers 'does the signal pick better stocks than a dart?'", "",
           table(["rule"] + [str(y) for y in years] + ["years > 0"], erows), ""]
    return md, S, cfg_years


def _stock_row(rn, en, st):
    s = st["st"]
    return [rn, en, s["n"], s["win"], s["avgR"], s["pf"], pct(st["cagr"]), rs(st["cagr"] * CAP),
            f"{st['pos_years']}/{st['n_years']}", pct(st["dd"]), pct(st["worst_m"]), s["streak"], rs(st["gap"]),
            rs(st["gross"] - st["gap"])]


def popular_section(P, S, cfg_years):
    years = list(range(2016, 2027))
    rng = np.random.default_rng(23)
    groups = {"top-50 large caps": P.ind["large"].values, "other F&O (mid caps)": P.ind["mid"].values}
    exits = ["fixed 5d", "fixed 10d", "2ATR stop + 3ATR chandelier (max 10d)", "2ATR stop + 10d time stop"]
    blocks = {
        "P1": ("### 4a. Popular strategy 1 - trend pullback to the 20/50 EMA with a hammer / bullish engulfing candle",
               ["P1 trend pullback to 20/50 EMA + hammer/engulfing", "P1 same, without the candle confirmation"]),
        "P2": ("### 4b. Popular strategy 2 - tight range, high-volume breakout (A: buy the breakout, B: buy the retest)",
               ["P2A tight range + volume breakout, buy next open",
                "P2B same breakout, buy after a retest of the level (within 5 days)",
                "P2A + sector index also strong", "P2 breakout without the volume/tight-range filters"]),
        "P3": ("### 4c. Popular strategy 3 - RSI(14) + 50 EMA + MACD", [
            "P3 RSI(14) bounce from 40-50 + MACD cross, >50EMA", "P3 RSI(14) oversold: crosses back above 30"]),
    }
    md = ["## 4. The three popular Indian swing set-ups Boss asked about", "",
          "Rules exactly as in the coordinator's brief (details in the script). Same portfolio engine, costs and sizing "
          "as section 3, run separately on the 50 most-traded F&O stocks each day ('large caps': RELIANCE, ICICIBANK, "
          "LT, HDFCBANK, INFY ... ) and on the rest of the F&O list ('mid caps'). Holds 3-10 days: fixed 5 / 10 days vs "
          "a 2xATR stop with a 3xATR chandelier trail (max 10 days) vs 2xATR stop + 10-day time stop. Baseline = the "
          "same number of RANDOM stocks from the same group bought on the same days, fixed 10 days (3 seeds).",
          "Sector flavour: each stock is mapped to the sector index (Auto, Pharma, IT, Metal, FMCG, Realty, Energy, "
          "PSU bank, Private bank, Media, Fin services, Infra, Consumption) whose daily moves (net of NIFTY) it tracked "
          "best in 2017-19; 'sector strong' = sector index above its 50 EMA and beating NIFTY over 20 days. Stocks "
          "that track the mid-cap index better than any sector get no sector (filter never passes for them).", ""]
    for key, (title, rules) in blocks.items():
        rows, yrows, erows = [], [], []
        for rn in rules:
            for gn, gm in groups.items():
                for en in exits:
                    eqs, tr = stock_sim(P, S[rn], STK_EXITS[en], mask=gm)
                    st = eq_stats(eqs, tr)
                    cfg_years[(rn, gn, en)] = st["yret"]
                    rows.append(_stock_row(f"{rn} [{gn}]", en, st))
                    if en == "fixed 10d":
                        yrows.append([rn, gn] + [pct(st["yret"].get(y, 0)) for y in years] + [pct(st["cagr"])])
                rc = []
                sg = S[rn] & gm
                for k in range(3):
                    e2, t2 = stock_sim(P, random_like(sg, gm, rng), STK_EXITS["fixed 10d"], score=rng.random(gm.shape))
                    rc.append(eq_stats(e2, t2)["cagr"])
                KEY[("pop_rand", rn, gn)] = np.mean(rc)
                rows.append([f"{rn} [{gn}]", "RANDOM same-day stocks, fixed 10d", "", "", "", "", pct(np.mean(rc)),
                             rs(np.mean(rc) * CAP), "", "", "", "", "", ""])
                ed = signal_edge(P, sg, 10, mask=gm)
                ed5 = signal_edge(P, sg, 5, mask=gm)
                n_all = sum(v[0] for v in ed.values())
                erows.append([rn, gn, n_all,
                              f"{100 * np.average([v[1] for v in ed5.values()], weights=[v[0] for v in ed5.values()]):+.2f}%" if ed5 else "",
                              f"{100 * np.average([v[1] for v in ed.values()], weights=[v[0] for v in ed.values()]):+.2f}%" if ed else "",
                              f"{100 * np.average([v[2] for v in ed.values()], weights=[v[0] for v in ed.values()]):+.2f}" if ed else "",
                              f"{sum(1 for y in ed if ed[y][2] > 0)}/{len(ed)}"])
        hdr = ["set-up [group]", "exit", "trades", "win", "avg R", "PF", "CAGR", "~Rs/yr on 5L (CAGR x 5L)", "years +",
               "max DD", "worst month", "longest losing run", "P&L from overnight gaps Rs", "P&L in trading hours Rs"]
        md += [title, "", table(hdr, rows), "", "Fixed 10-day hold, % return by year:", "",
               table(["set-up", "group"] + [str(y) for y in years] + ["CAGR"], yrows), "",
               "Per-signal edge (no portfolio limits): every signal, next open -> 5 / 10 sessions later, minus 0.4% costs, "
               "and the excess over the average stock of the same group on the same days:", "",
               table(["set-up", "group", "signals", "avg 5-day net", "avg 10-day net", "10-day excess vs group (pp)",
                      "years excess > 0"], erows), ""]
    # the three named large caps
    rows = []
    for nm in ("RELIANCE", "ICICIBANK", "LT"):
        if nm not in P.names:
            continue
        j = P.names.index(nm)
        for rn in ("P1 trend pullback to 20/50 EMA + hammer/engulfing", "P2A tight range + volume breakout, buy next open",
                   "P3 RSI(14) bounce from 40-50 + MACD cross, >50EMA", "P3 RSI(14) oversold: crosses back above 30"):
            Oj = P.A["O"][:, j]
            fwd = np.full(len(Oj), np.nan)
            fwd[:-11] = Oj[11:] / Oj[1:-10] - 1 - 0.004
            ok = (P.dates >= START) & np.isfinite(fwd)
            sigj = S[rn][:, j] & ok
            n = int(sigj.sum())
            if n == 0:
                rows.append([nm, rn, 0, "", "", ""])
                continue
            base = np.nanmean(fwd[ok & P.ind["elig"].values[:, j]])
            rows.append([nm, rn, n, f"{100 * fwd[sigj].mean():+.2f}%", f"{100 * np.mean(fwd[sigj] > 0):.0f}%",
                         f"{100 * (fwd[sigj].mean() - base):+.2f}"])
    md += ["### The three stocks Boss named (each signal, 10-day hold, after 0.4% costs)", "",
           table(["stock", "set-up", "signals 2016-26", "avg 10-day net", "win", "vs holding that stock any 10 days (pp)"],
                 rows), "", "Too few signals per single stock to judge anything - this is why the study uses the whole "
           "group.", ""]
    return md


def benchmarks_section(P):
    years = list(range(2016, 2027))
    C = P.C
    el = P.ind["elig"]
    rows = []
    out = {}
    for y in years:
        d = C.index[C.index.year == y]
        if len(d) < 2:
            continue
        d0 = C.index[C.index < d[0]][-1]
        e = el.loc[d0]
        r = (C.loc[d[-1]] / C.loc[d0] - 1)[e]
        out[y] = r.mean()
    idxr = {}
    for nm in ("NIFTY", "NIFTY_200", "NIFTY_MIDCAP_150"):
        x = read_daily(os.path.join(DATA, "candles", "daily", "IDX_I", f"{nm}.parquet")).close
        yy = x.groupby(x.index.year).last()
        idxr[nm] = (yy / yy.shift(1) - 1).to_dict()
    for y in years:
        rows.append([y, pct(out.get(y, np.nan)), pct(idxr["NIFTY"].get(y, np.nan)), pct(idxr["NIFTY_200"].get(y, np.nan)),
                     pct(idxr["NIFTY_MIDCAP_150"].get(y, np.nan)),
                     f"{100 * (out.get(y, 0) - (idxr['NIFTY_200'][y] + idxr['NIFTY_MIDCAP_150'][y]) / 2):+.1f} pp"])
    KEY["ew"] = out
    KEY["idx_y"] = idxr
    ew_cagr = np.prod([1 + v for v in out.values()]) ** (1 / 10.75) - 1
    n_cagr = np.prod([1 + idxr["NIFTY"][y] for y in years]) ** (1 / 10.75) - 1
    KEY["ew_cagr"], KEY["nifty_cagr"] = ew_cagr, n_cagr
    # cross-sectional momentum, rebalanced every 20 sessions, top 10 by 12-1 month return, equal weight
    r121 = P.ind["ret_12_1"].values
    Cv, Ov = P.A["C"], P.A["O"]
    i0 = int(np.searchsorted(P.dates.values, np.datetime64(START)))
    elv = el.values & (C.values > P.ind["sma200"].values)
    hold, cash, eq = {}, CAP, []
    lastpx = pd.DataFrame(Cv).ffill().values
    for i in range(i0, len(P.dates)):
        if (i - i0) % 20 == 1 and i > i0:
            sc = np.where(elv[i - 1], np.nan_to_num(r121[i - 1], nan=-9), -9)
            top = [s for s in np.argsort(-sc)[:10] if sc[s] > -9]
            for s in list(hold):
                if s not in top and np.isfinite(Ov[i, s]):
                    v = hold.pop(s) * Ov[i, s] * (1 - EQ_SLIP)
                    cash += v - eq_side_cost(v, "sell")
            tot = cash + sum(sh * lastpx[i - 1, s] for s, sh in hold.items())
            for s in top:
                if s in hold or not np.isfinite(Ov[i, s]):
                    continue
                px = Ov[i, s] * (1 + EQ_SLIP)
                sh = int(min(tot / 10, cash - 100) / (px * 1.0012))
                if sh * px < 10_000:
                    continue
                cash -= sh * px + eq_side_cost(sh * px, "buy")
                hold[s] = sh
        eq.append(cash + sum(sh * lastpx[i, s] for s, sh in hold.items()))
    eqs = pd.Series(eq, index=P.dates[i0:])
    st = eq_stats(eqs, pd.DataFrame(columns=["net", "R", "gap", "gross"]))
    KEY["xsmom"] = st
    m30 = read_daily(os.path.join(DATA, "candles", "daily", "IDX_I", "NIFTY200MOMENTM30.parquet")).close
    m30_26 = m30[m30.index.year == 2026].iloc[-1] / m30[m30.index.year == 2025].iloc[-1] - 1
    KEY["m30_26"] = m30_26
    md = ["## 5. Benchmarks for the stock results", "",
          "Equal-weight buy-and-hold of the eligible universe (rebalanced each 1 Jan) against the official indices "
          "(price indices, no dividends - same as our stock prices). The last column is the survivorship gap: how much "
          "better TODAY's F&O list did than the indices that held the stocks of that time (approx. average of NIFTY 200 "
          "and Midcap 150).", "",
          table(["year", "our universe EW buy&hold", "NIFTY 50", "NIFTY 200", "NIFTY Midcap 150", "universe minus avg(N200, MC150)"],
                rows), "",
          f"Compounded Jan-2016 to Oct-2026: equal-weight buy-and-hold of the universe {pct(ew_cagr)} a year (no trading "
          f"costs, one rebalance a year); NIFTY 50 {pct(n_cagr)} a year. **These are the numbers every long-only stock "
          f"swing system in sections 3-4 has to beat** - a swing rule that makes 10-15% a year on this universe is "
          f"doing worse than simply holding the same stocks.", "",
          f"Cross-sectional momentum (top 10 by 12-1 month return, above 200DMA, rebalanced every 20 sessions, equal "
          f"weight, same costs): CAGR {pct(st['cagr'])}, max drawdown {pct(st['dd'])}, worst month {pct(st['worst_m'])}, "
          f"positive years {st['pos_years']}/{st['n_years']}; by year: " +
          ", ".join(f"{y} {pct(v)}" for y, v in st["yret"].items()) + ".",
          "",
          f"**Do not believe that momentum number.** It is the rule most inflated by survivorship: it buys the "
          f"biggest past winners, and today's F&O list is, by construction, made of companies that kept winning. The "
          f"only survivorship-free check in the data is the official NIFTY 200 Momentum 30 index, which only has "
          f"history from Aug-2025: in 2026 (to Oct) it returned {pct(m30_26)} while our version made "
          f"{pct(st['yret'].get(2026, np.nan))}. Published long-run figures for Indian momentum indices are in the "
          f"high-teens % a year, not 30%+.", ""]
    return md

# ======================================================================================== robustness


def walk_forward(cfg_years, first_test=2019, last=2026, scale=1.0, fmt=rs):
    """cfg_years: {config: {year: result}}. Each test year uses the config with the best SUM over all earlier
    years (2016..Y-1); if even the best is <= 0, stay out that year."""
    rows, oos = [], {}
    for Y in range(first_test, last + 1):
        train = {k: sum(v.get(y, 0.0) for y in range(2016, Y)) for k, v in cfg_years.items()}
        best = max(train, key=train.get)
        if train[best] <= 0:
            oos[Y] = 0.0
            rows.append([Y, "(nothing positive in training - stay out)", "", fmt(0.0)])
            continue
        r = cfg_years[best].get(Y, 0.0)
        oos[Y] = r
        rows.append([Y, " / ".join(str(x) for x in best), fmt(train[best] * scale), fmt(r * scale)])
    return rows, oos


def index_wf_and_sensitivity(idx):
    md = ["## 6. Robustness: walk-forward, parameter sensitivity, survivorship", "",
          "### 6a. Walk-forward on index futures", "",
          "Candidate set: every rule (except coin flip / always-long) x every exit x {both sides, long only}. For each "
          "test year, the configuration with the best total profit over ALL earlier years (2016 onward) is traded "
          "blind for that year. If a rule really works, picking 'what worked so far' should keep working.", ""]
    for sym, d in idx.items():
        sigs = index_signals(d)
        cfg = {}
        for rn, sg in sigs.items():
            if rn.startswith("Coin") or rn.startswith("Always"):
                continue
            for en, ex in EXITS.items():
                for lo in (False, True):
                    if lo and (sg >= 0).all():
                        continue
                    tr = run_fut(d, sym, sg, ex, lo_only=lo)
                    s = fut_summary(tr, d, sym, None)
                    if s is not None:
                        cfg[(rn, en, "long only" if lo else "long+short")] = s["by_year"]
        rows, oos = walk_forward(cfg)
        tot = sum(oos.values())
        KEY[("wf", sym)] = (tot, sum(1 for v in oos.values() if v > 0), len(oos))
        bh = run_fut(d, sym, sigs["Always long (buy-and-hold proxy)"], EXITS["fixed 20d"])
        bhy = fut_summary(bh, d, sym, None)["by_year"]
        md += [f"**{sym}** - out-of-sample total 2019-2026: Rs {rs(tot)} per lot "
               f"({sum(1 for v in oos.values() if v > 0)}/{len(oos)} years positive). Always-long (20-day re-entry) in "
               f"the same years: Rs {rs(sum(bhy.get(y, 0) for y in oos))}.", "",
               table(["test year", "config chosen on earlier years", "its training profit Rs", "out-of-sample Rs"], rows), ""]
    # sensitivity
    md += ["### 6b. Parameter sensitivity (index futures, totals 2016-2026, Rs per lot; years positive in brackets)", ""]
    for sym, d in idx.items():
        c = d.close
        rows = []
        for n in (10, 20, 30, 55, 100):
            sg = np.asarray(np.where(c > d[f"hi{n}"], 1, np.where(c < d[f"lo{n}"], -1, 0)))
            cells = []
            for en in ("fixed 5d", "fixed 10d", "fixed 20d", "2ATR stop + 3ATR chandelier (max 20d)"):
                s = fut_summary(run_fut(d, sym, sg, EXITS[en]), d, sym, None)
                cells.append(f"{rs(s['st']['tot'])} ({s['pos_years']}/{s['n_years']})")
            rows.append([f"Donchian {n}-day"] + cells)
        md += [f"{sym} - breakout lookback x exit:", "",
               table(["lookback", "fixed 5d", "fixed 10d", "fixed 20d", "2ATR + 3ATR chandelier"], rows), ""]
        rows = []
        sigs = index_signals(d)
        for rn in ("Donchian 20-day breakout", "NR7 / inside-day breakout"):
            for m in (1.5, 2.0, 3.0):
                cells = []
                for tr_ in (2.0, 3.0, 4.0):
                    s = fut_summary(run_fut(d, sym, sigs[rn], dict(kind="stop", m=m, trail=tr_, N=20)), d, sym, None)
                    cells.append(f"{rs(s['st']['tot'])} ({s['pos_years']}/{s['n_years']})")
                rows.append([rn, f"{m} ATR"] + cells)
        md += [f"{sym} - initial stop x chandelier trail (max 20 days):", "",
               table(["rule", "initial stop", "trail 2 ATR", "trail 3 ATR", "trail 4 ATR"], rows), ""]
    return md


def stock_wf_and_sensitivity(P, S, cfg_years):
    rows, oos = walk_forward(cfg_years, fmt=pct)
    tot = np.prod([1 + v for v in oos.values()]) - 1
    KEY["wf_stock"] = (oos, tot)
    md = ["### 6c. Walk-forward on the stock portfolio", "",
          f"Candidates: every stock rule / set-up x group x exit in sections 3-4 ({len(cfg_years)} configurations). "
          "Each test year trades the one with the best summed yearly return over all earlier years.", "",
          table(["test year", "config chosen on earlier years", "its summed training return", "out-of-sample return"], rows),
          "", f"Out-of-sample compounded 2019-Oct 2026: {pct(tot)} on the account "
          f"(Rs {rs(tot * CAP)} on Rs 5 lakh); equal-weight buy-and-hold of the same universe in those years: "
          f"{pct(np.prod([1 + KEY['ew'].get(y, 0) for y in oos]) - 1)}; NIFTY 50: "
          f"{pct(np.prod([1 + KEY['idx_y']['NIFTY'].get(y, 0) for y in oos]) - 1)}.", ""]
    # sensitivity on the RSI(2) pullback (the best classic stock rule)
    I, C = P.ind, P.C
    el = I["elig"]
    rows = []
    for th in (5, 10, 20, 30):
        sg = (el & (C > I["sma200"]) & (I["rsi2"] < th)).fillna(False).values
        cells = []
        for en in ("fixed 5d", "fixed 10d", "exit on close > 5DMA (max 10d), 3ATR disaster stop"):
            st = eq_stats(*stock_sim(P, sg, STK_EXITS[en]))
            cells.append(f"{pct(st['cagr'])} ({st['pos_years']}/{st['n_years']}, DD {pct(st['dd'])})")
        rows.append([f"RSI(2) < {th}"] + cells)
    md += ["### 6d. Sensitivity - stock pullback rule (>200DMA & RSI(2) below threshold): CAGR (years +, max DD)", "",
           table(["threshold", "fixed 5d", "fixed 10d", "close > 5DMA exit"], rows), ""]
    rows = []
    sg = S["Pullback: >200DMA & RSI(2)<10"]
    for maxN in (5, 10, 20):
        cells = []
        for risk in (0.005, 0.01, 0.02):
            st = eq_stats(*stock_sim(P, sg, STK_EXITS["fixed 10d"], maxN=maxN, risk=risk))
            cells.append(f"{pct(st['cagr'])} (DD {pct(st['dd'])})")
        rows.append([maxN] + cells)
    md += ["Position count x risk per trade (same rule, fixed 10d):", "",
           table(["max positions", "0.5% risk", "1% risk", "2% risk"], rows), ""]
    return md

# ======================================================================================== report

VERDICT = """## Verdict for Boss (plain language)

**Short answer: none of the swing rules passed the strict test.** The test was: profit after all costs in most
years AND still profitable when the rule is picked only from earlier years (walk-forward). Nothing on NIFTY or
BANKNIFTY futures passed. Nothing on bought options or debit spreads passed. On stocks, one idea - buying dips in
stocks that are in an uptrend - does pick better stocks than chance. But after costs it did no better than simply
buying and holding the same stocks.

What each approach would have done on **Rs 5 lakh** (Jan-2016 to Oct-2026 unless stated):

| approach | typical result | worst drawdown | does it pass? |
|---|---|---|---|
| NIFTY / BANKNIFTY futures, 1 lot, best-looking rule (NR7 / inside-day breakout) | +Rs 18k to +55k a year in hindsight, 6-8 of 11 years positive | -Rs 3.4 to -4.1 lakh (70-80% of capital) | **No** - walk-forward lost Rs 45-58k a year, and the results sit inside the coin-flip range |
| Same, other classic rules (20/55-day breakout, RSI(2) pullback, EMA 20/50, 52-week high, big-down-day bounce) | from -Rs 75k to +Rs 25k a year; most negative | -Rs 3 to -10 lakh per lot | **No** |
| Bought monthly ATM options on the same signals | mostly losses; the best (EMA 20/50 trend, 5-day hold) made about +Rs 50k a year on NIFTY (6 of 7 years) and +Rs 28k a year on BANKNIFTY (5 of 6) | -Rs 1.3 lakh (closed trades) | **Not proven** - walk-forward: NIFTY +Rs 21k in total over 6 years, BANKNIFTY -Rs 1.5 lakh. This is the only thing worth paper-trading |
| Debit spreads (buy ATM, sell 2 or 4 strikes OTM, monthly) | lose a small, steady amount: about Rs 5-25k a year | small | **No** - lower theta, but the spread also caps the gains and costs eat what is left |
| F&O stocks in cash, pullback-in-uptrend portfolio (1% risk, max 10 positions); the NR7/inside-day buy-stop above the 200DMA is similar | +10.8% to +20% a year = about +Rs 54k to +1 lakh a year on 5 lakh, 7-8 of 11 years positive | -33% to -44% | **Partly** - beats random stocks bought on the same days (by about 9-13 points a year), but NOT the +17.9% a year from just holding the same stocks |
| F&O stocks: 20/55-day breakouts, 52-week high, EMA cross, big-down-day bounce, the 3 popular set-ups | from -19% to +13% a year depending on exit | -26% to -90% | **No** |
| Just hold the stocks (equal-weight F&O universe) / NIFTY 50 | +17.9% / +10.2% a year | the 2020 crash | (baseline - flattered by survivorship, see 6e) |

What the data says clearly:

1. **Index swing trading in futures has no edge after costs.** Nearly every rule's 10-year total is inside the range a
   coin flip produces. Trading costs are not the main drain. Carry is: a long futures position held for 20 days
   costs about Rs 2-3k a lot in carry alone. Choosing rules on past years and trading them the next year
   (walk-forward) lost money on both indices.
2. **Almost all index swing profit is made overnight.** Gross of costs, every rule made money between the close and
   the next open (gaps) and lost money during market hours. So gap risk is not the danger it looks like. It is the
   reason holding overnight works at all. The deep drawdowns come from losses during market hours.
3. **Bought options turn a weak edge into a loss.** Theta took about 8% of the premium on a monthly option bought
   15+ sessions before expiry. It took 50-90% on a weekly bought 1-4 sessions before expiry. On 1-day holds the
   choice of contract hardly matters.
4. **Golden rule A ('never hold a weekly OTM into expiry') is right, but it only cuts the loss.** Selling the
   weekly the session before expiry usually cut the loss on 3-5 day holds, by 5-60% (once it was slightly worse),
   but never turned it into a profit. Rolling into next week's option made things worse. For holds of 3 days or
   more, the monthly lost less than the weekly in 7 of 8 comparisons, so buy the monthly in the first place.
5. **Golden rule B (OI confirmation) is not proven.** On BANKNIFTY 20-day breakouts, keeping only trades where put
   writing beat call writing helped in 5 of 6 years. Elsewhere it made no difference or hurt. Pullback and bounce
   signals almost never get OI confirmation, because puts are being unwound on a dip. A random coin-flip signal
   'improved' with OI in 2-4 of 6-7 years, so one good row out of many is what luck alone looks like.
6. **Stocks: short-term dips in uptrending stocks bounce.** This matches the research on short-term reversal. The
   effect was positive in 9 of 11 years (about +0.3% per 10-day trade over a random stock), but it was negative in
   2025 and 2026. Delivery costs eat most of it: STT is 0.1% on both sides plus slippage, about 0.6% a round trip,
   or roughly 15% a year at this turnover. Breakout systems on stocks picked better stocks than chance in only 5 of
   11 years, and their stop/trailing versions lost money. Walk-forward on all stock set-ups made +453% over
   2019-Oct 2026, against +327% for holding the same stocks and +108% for NIFTY. That is a small lead, and both
   numbers are inflated by survivorship.
7. **The three popular set-ups** (section 4): the trend pullback with a hammer or engulfing candle, the volume
   breakout with or without a retest or a sector filter, and RSI + MACD all failed the test. The candle
   confirmation did not help consistently: it helped a little in large caps and hurt in mid caps. Volume breakouts
   lost money in both large and mid caps. Buying the retest lost less than buying the breakout, and the sector
   filter did not make either one profitable. RSI + MACD came out about flat. Oversold RSI(14) < 30 bounces lost
   money in large caps.

**What I would actually do with Rs 5 lakh:** don't trade index futures or weekly options on these daily rules. If
Boss wants to swing trade, the least-bad tested approach is a small stock portfolio of uptrend pullbacks (e.g. above
the 200DMA with 3 down closes or RSI(2) < 10, buy next open, hold 10-20 days, max 10 positions). But expect it to
roughly match buy-and-hold with far more work and a 35-45% drawdown. On index options, paper-trade 'EMA 20/50 trend
-> monthly ATM option, 5-day hold' for 6-12 months before risking money. It is the only option row that held up
reasonably in both indices, and it has not passed walk-forward.
"""

LITERATURE = """## 9. What published research says (from memory - general knowledge, not checked against sources for this report)

*Labelled as background from the author's own knowledge; no web search was used; citations may be imprecise.*

- **Time-series momentum / trend following** (Moskowitz, Ooi & Pedersen 2012; Hurst, Ooi & Pedersen 'A Century of
  Evidence on Trend-Following'): across futures markets, the past 1-12 MONTH return predicts the next month. The
  edge is strongest at 3-12 month lookbacks, comes from a minority of big trends, and needs many uncorrelated markets
  to be smooth. A single index on 20-55 DAY breakouts is a much weaker, noisier slice of that effect.
- **Short-term reversal** (Jegadeesh 1990; Lehmann 1990): stocks that fell most in the past week/month tend to bounce
  the next week/month. Much of it is a liquidity premium and it shrinks sharply after trading costs, especially in
  small stocks. Connors-style RSI(2) pullbacks are the practitioner version (popular books, not peer-reviewed).
- **Cross-sectional momentum** (Jegadeesh & Titman 1993) and the **52-week-high effect** (George & Hwang 2004): stocks
  near their 52-week high keep outperforming over 3-12 months. In India, academic work (e.g. Sehgal and co-authors;
  the IIM Ahmedabad Fama-French-momentum factor data by Agarwalla, Jacob & Varma) finds a sizeable momentum premium,
  with deep crashes in reversals such as 2009 and 2020.
- **Technical trading rules** (Brock, Lakonishok & LeBaron 1992 found moving-average/breakout rules worked on the Dow
  1897-1986; Sullivan, Timmermann & White 1999 showed much of it is data-snooping and it faded after the sample;
  Park & Irwin 2007 survey: early profits, weaker in recent data, and fragile once costs and snooping are handled).
  Studies on Indian indices report mixed results: some profitability in the 1990s-2000s that mostly disappears after
  costs in later data.
- **Overnight vs intraday returns**: in many markets (US, and studies on NSE stocks) most of the equity premium is
  earned overnight (close -> next open) while the trading-hours return is near zero or negative - we see the same here.
- **Retail F&O outcomes**: SEBI's studies (FY22 and FY24/FY25) found roughly 9 in 10 individual F&O traders lost
  money, with average losses well above their costs - consistent with option buying being a negative-sum game
  after theta and costs.
"""


def main():
    import pickle
    idx = {s: load_index(s) for s in ("NIFTY", "BANKNIFTY")}
    years = list(range(2016, 2027))
    sec1, allres = index_section(idx)
    sec1b = ["## 1b. Overnight gaps and event weeks (index futures)", "",
             "Gap = next open minus previous close while holding. 'Trading hours' = the rest. Event table uses the "
             "fixed 10-day exit so trades are the same length (with trailing exits, long winners naturally span more "
             "event dates and the comparison is biased). Events: Union Budgets, RBI policy days, 2019/2024 election "
             "results (dates from memory - RBI dates may be off by a day in places).", ""]
    for sym in idx:
        sec1.append(f"### {sym}: by year (Rs per lot after costs)")
        sec1.append("")
        sec1.append(index_years_table(allres, sym, years))
        sec1.append("")
        sec1.append(f"### {sym}: long side vs short side (2ATR stop + 3ATR chandelier)")
        sec1.append("")
        sec1.append(long_short_table(allres, sym))
        sec1.append("")
        t1, t2 = gap_event_tables(allres, sym)
        sec1b += [f"### {sym}: where the points came from (2ATR + 3ATR chandelier exit, Rs per lot before costs)", "", t1,
                  "", f"### {sym}: trades whose 10-day hold spanned an event", "", t2, ""]
    sec2, opt_res = options_section(idx)
    sec7 = theta_section(idx)
    sec8 = oi_section(idx, allres, opt_res)
    P = Panel(idx["NIFTY"].index)
    smap = P.sector_map()
    sec3, S, cfg_years = stock_section(P, smap)
    sec4 = popular_section(P, S, cfg_years)
    sec5 = benchmarks_section(P)
    sec6 = index_wf_and_sensitivity(idx)
    sec6 += stock_wf_and_sensitivity(P, S, cfg_years)
    sec6 += ["### 6e. Survivorship", "",
             f"The stock list is today's {len(P.names)} F&O names. Companies that collapsed or were dropped from F&O "
             "between 2016 and 2026 (e.g. the DHFL / Yes Bank / Jet Airways type of blow-ups, and dozens of quiet "
             "removals) are missing, and names that were small in 2016 but grew into F&O are included with "
             "hindsight. Section 5's last column measures the effect directly: the equal-weight universe beat the "
             "indices of the time by a large margin most years. Long-only stock results above are therefore "
             "flattered by several percentage points a year; the per-signal 'excess vs same-day random stock' "
             "numbers are much less affected, because the dart-board baseline has the same bias.", ""]
    caveats = ["## 10. Honest caveats", "",
               "- Index 'futures' are the spot index plus a modelled 5.5%/yr carry and roll costs; real futures basis "
               "varies (sometimes cheaper, sometimes dearer). Lot sizes are held at today's values for every year.",
               "- One fill price per decision (next open / 09:20) and fixed slippage; real fills on stop orders in fast "
               "markets can be worse. Statutory rates are today's (STT on options and futures was lower before Oct-2024, "
               "so earlier years were slightly cheaper in reality).",
               "- Options: only the nearest weekly and nearest monthly series exist in the data, each with +/-10 strikes. "
               "Strikes that drift outside that window and every 'next-week' contract are Black-Scholes priced from a "
               "nearby IV; the 'model bias check' row shows how much that flatters results.",
               "- Event dates (budget/RBI/elections) are typed from memory, not from an official calendar.",
               "- Stock data are split/bonus adjusted prices without dividends (~1-1.5%/yr missing for buy-and-hold "
               "and a little for swing holds).",
               "- 2026 is a part-year (to 5 Oct 2026). Some 2016 trades use the 2015 warm-up for indicators only.",
               "- Many rules x exits x instruments were tried (several hundred combinations). With that many tries a "
               "few will look good by luck; that is why the coin-flip bands, random-stock baselines and the "
               "walk-forward test carry more weight than any single good-looking row.",
               "- Run time is about 2-3 minutes; nothing was subsampled.", ""]
    head = ["# Swing trading (2-20 day holds) - deep study on real NSE data, after costs", "",
            f"*Generated by `research/swing_deep.py` on {pd.Timestamp.today().date()}. Data: Dhan daily candles 2014-Oct "
            "2026 (indices and today's F&O stocks), NIFTY/BANKNIFTY weekly + monthly option minute data (NIFTY from "
            "Aug-2020, BANKNIFTY from Aug-2021). Builds on SWING.md (2 years, BANKNIFTY bought options).*", "",
            VERDICT, ""]
    md = head + sec1 + sec1b + sec2 + sec3 + sec4 + sec5 + sec6 + sec7 + sec8 + [LITERATURE] + caveats
    with open(OUT, "w") as f:
        f.write("\n".join(md) + "\n")
    with open(os.path.join(CACHE, "key.pkl"), "wb") as f:
        pickle.dump({str(k): v for k, v in KEY.items()}, f)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
