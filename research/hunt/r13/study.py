"""R13: Majority Rule (MR) on NIFTY weekly. Research only.

MR(L) = 100 * (# of last L bars that are 'up') / L.
  mode 'cc': up = close > previous close   (our working definition)
  mode 'co': up = close > same bar's open  (candle colour; TradingView alt)
Oversold touch  : MR <= 28.57 (14-len: <=4 up weeks) and previous week above.
Overbought touch: MR >= 71.43 (>=10 up weeks) and previous week below.
Entry at NEXT week's open; forward return to close of week t+h.
Run: venv/bin/python -I study.py   (needs daily.parquet from data.py)
"""
import math, sys
import numpy as np, pandas as pd

S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
OUT = f"{S}/hunt/r13"
HZ = [1, 2, 4, 8, 13, 26]
REPS = 10_000
rng = np.random.default_rng(13)
LOT = 65            # NIFTY lot from 2025-12-31 (docs/lot-history.md); 75 during 2025
OUTLINES = []


def p(*a):
    s = " ".join(str(x) for x in a)
    OUTLINES.append(s)
    print(s)


# ---------------------------------------------------------------- data
D = pd.read_parquet(f"{OUT}/daily.parquet")
D = D.rename(columns={"NIFTY_open": "open", "NIFTY_high": "high", "NIFTY_low": "low",
                      "NIFTY_close": "close", "INDIA_VIX_close": "vix"})


def weekly(d):
    g = d.resample("W-FRI")
    w = pd.DataFrame({"open": g["open"].first(), "high": g["high"].max(), "low": g["low"].min(),
                      "close": g["close"].last(), "vix": g["vix"].last(),
                      "last_day": g["close"].apply(lambda s: s.index[-1] if len(s) else pd.NaT),
                      "ndays": g["close"].count()})
    w = w[w["ndays"] > 0].copy()
    w.index = pd.DatetimeIndex(w["last_day"])
    return w.drop(columns="last_day")


W = weekly(D)


def mr(bars, L, mode):
    up = (bars["close"] > bars["close"].shift(1)) if mode == "cc" else (bars["close"] > bars["open"])
    up = up.astype(float)
    if mode == "cc":
        up.iloc[0] = np.nan
    return 100.0 * up.rolling(L).sum() / L


def fwd(bars, h):
    """forward return from next bar open to close of bar t+h, MAE (min low) and MFE (max high) over the hold."""
    o = bars["open"].shift(-1).values
    c = bars["close"].shift(-h).values
    lo = bars["low"].values; hi = bars["high"].values
    n = len(bars)
    mae = np.full(n, np.nan); mfe = np.full(n, np.nan)
    for i in range(n - h):
        mae[i] = lo[i + 1:i + h + 1].min() / o[i] - 1
        mfe[i] = hi[i + 1:i + h + 1].max() / o[i] - 1
    return pd.DataFrame({"ret": c / o - 1, "mae": mae, "mfe": mfe}, index=bars.index)


FW = {h: fwd(W, h) for h in HZ}


def boot(base, n, obs_mean, obs_hit):
    """bootstrap: n random entry weeks from base (with replacement), REPS times.
    returns p(mean>=obs), p(mean<=obs), p(hit>=obs), baseline mean, hit."""
    b = base.values
    idx = rng.integers(0, len(b), size=(REPS, n))
    s = b[idx]
    m = s.mean(1); hr = (s > 0).mean(1)
    return ((m >= obs_mean).mean(), (m <= obs_mean).mean(), (hr >= obs_hit).mean(), b.mean(), (b > 0).mean())


def touches(m, thr, side):
    if side == "low":
        cur = m <= thr + 1e-9; prev = m.shift(1) > thr + 1e-9
    else:
        cur = m >= thr - 1e-9; prev = m.shift(1) < thr - 1e-9
    return (cur & prev & m.shift(1).notna()), cur


def event_table(sig, start, label, side, fw=FW, hz=HZ, unit="w"):
    p(f"\n#### {label}  (from {start})")
    p(f"| hold | n | mean | median | hit% | base mean | base hit% | excess | p(mean>base) | p(mean<base) | p(hit>=) | avg MAE | worst MAE |")
    p("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    res = {}
    for h in hz:
        f = fw[h]
        f = f[f.index >= start].dropna(subset=["ret"])
        s = f.loc[f.index.intersection(sig[sig].index)]
        if len(s) == 0:
            continue
        r = s["ret"]
        pu, pd_, ph, bm, bh = boot(f["ret"], len(r), r.mean(), (r > 0).mean())
        adv = s["mae"] if side == "low" else s["mfe"]
        worst = adv.min() if side == "low" else adv.max()
        p(f"| {h}{unit} | {len(r)} | {r.mean()*100:+.2f}% | {r.median()*100:+.2f}% | {(r>0).mean()*100:.0f} | "
          f"{bm*100:+.2f}% | {bh*100:.0f} | {(r.mean()-bm)*100:+.2f} | {pu:.3f} | {pd_:.3f} | {ph:.3f} | "
          f"{adv.mean()*100:+.1f}% | {worst*100:+.1f}% |")
        res[h] = (len(r), r.mean(), bm, pu, pd_)
    return res


def signal_list(sig, side, m):
    p(f"\n| signal week (Fri) | MR | NIFTY close | entry (next open) | " + " | ".join(f"{h}w" for h in HZ) + " | MAE 13w |")
    p("|---|---|---|---|" + "---|" * len(HZ) + "---|")
    for t in sig[sig].index:
        row = [f"{FW[h].loc[t,'ret']*100:+.1f}%" if not np.isnan(FW[h].loc[t, 'ret']) else "open" for h in HZ]
        ent = W["open"].shift(-1).loc[t]
        mae = FW[13].loc[t, "mae" if side == "low" else "mfe"]
        p(f"| {t.date()} | {m.loc[t]:.1f} | {W.loc[t,'close']:.0f} | {ent:.0f} | " + " | ".join(row) +
          f" | {'' if np.isnan(mae) else f'{mae*100:+.1f}%'} |")


PERIODS = [pd.Timestamp("2006-01-01"), pd.Timestamp("2015-01-01"), pd.Timestamp("2020-01-01")]

# ---------------------------------------------------------------- 1. event study
p("# R13 output\n")
p(f"weekly bars: {len(W)} weeks {W.index[0].date()} .. {W.index[-1].date()}")
ties = (W['close'] == W['close'].shift(1)).sum()
p(f"weekly equal closes (cc ties): {ties}; equal close/open: {(W['close']==W['open']).sum()}")
SUMMARY = {}
for mode in ["cc", "co"]:
    m = mr(W, 14, mode)
    p(f"\n## 1. Event study, mode={mode}  (current MR14 = {m.iloc[-1]:.2f} at {W.index[-1].date()})")
    for side, thr in [("low", 100 * 4 / 14), ("high", 100 * 10 / 14)]:
        new, allw = touches(m, thr, side)
        p(f"\n### {'Oversold MR<=28.57' if side=='low' else 'Overbought MR>=71.43'} — new touches: {int(new.sum())}, all weeks in zone: {int(allw.sum())}")
        for st in PERIODS:
            SUMMARY[(mode, side, "new", st)] = event_table(new, st, f"{mode} {side} NEW touches", side)
        for st in PERIODS:
            event_table(allw, st, f"{mode} {side} ALL weeks in zone", side)
        if mode == "cc":
            signal_list(new, side, m)
        else:
            p("signal weeks: " + ", ".join(str(t.date()) for t in new[new].index))

# ---------------------------------------------------------------- 2. trading versions
def charges_fut(buy_px, sell_px, lot, stt):
    tb, ts = buy_px * lot, sell_px * lot
    brok = 40.0
    exch = 0.0000173 * (tb + ts)
    gst = 0.18 * (brok + exch)
    stamp = 0.00002 * tb
    return brok + exch + gst + stamp + stt * ts


def stt_fut(t):
    return 0.0002 if t >= pd.Timestamp("2024-10-01") else (0.0001 if t >= pd.Timestamp("2013-06-01") else 0.00017)


CARRY = 0.055       # futures premium drag (r - q) per year on notional, paid by a long
SLIP_PTS = 1.0      # points per side, futures


def run_trades(m, N, use_mr_exit, start, thr=100 * 4 / 14):
    """non-overlapping trades. Signal at week t close (new touch) -> buy at open t+1.
    Exit at close of week t+N, or if MR crosses back above 50 at a week close -> exit next week's open."""
    new, _ = touches(m, thr, "low")
    idx = W.index
    trades = []
    i = 0
    o, c = W["open"].values, W["close"].values
    sigpos = [k for k, t in enumerate(idx) if new.iloc[k] and t >= start]
    busy_until = -1
    for k in sigpos:
        if k <= busy_until or k + 1 >= len(idx):
            continue
        e = k + 1
        ex, why, px = None, "time", None
        for j in range(e, min(k + N, len(idx) - 1) + 1):
            if use_mr_exit and m.iloc[j] > 50 and j < k + N:
                if j + 1 < len(idx):
                    ex, px, why = j + 1, o[j + 1], "MR>50"
                break
        if ex is None:
            if k + N >= len(idx):
                continue  # incomplete trade at end of data
            ex, px = k + N, c[k + N]
        trades.append(dict(sig=idx[k], entry=idx[e], exit=idx[ex], ep=o[e], xp=px, why=why,
                           weeks=ex - k if why == "time" else ex - e))
        busy_until = ex if why == "time" else ex - 1
    return pd.DataFrame(trades)


def fut_pnl(tr):
    out = []
    for _, t in tr.iterrows():
        pts = t.xp - t.ep - 2 * SLIP_PTS
        days = (t.exit - t.entry).days + 4
        carry = CARRY * days / 365 * t.ep
        rolls = max(0, int(days // 28))
        cost = charges_fut(t.ep, t.xp, LOT, stt_fut(t.exit)) * (1 + rolls) + rolls * 2 * SLIP_PTS * LOT
        out.append((pts - carry) * LOT - cost)
    tr = tr.copy(); tr["rs"] = out
    tr["pct"] = tr.xp / tr.ep - 1
    return tr


def etf_curve(tr, start, cap=100_000.0):
    eq = cap; peak = cap; mdd = 0; rows = []
    for _, t in tr.iterrows():
        buy = eq * (1 - 0.0005 - 0.00015 - 0.0000325) - 20         # slippage+stamp+exch, Rs20 brokerage
        units = buy / t.ep
        sell = units * t.xp
        eq = sell * (1 - 0.0005 - 0.00001 - 0.0000325) - 20
        peak = max(peak, eq); mdd = min(mdd, eq / peak - 1)
        rows.append(eq)
    end = W.index[-1]
    yrs = (end - max(start, W.index[14])).days / 365.25
    cagr = (eq / cap) ** (1 / yrs) - 1
    w0 = W[W.index >= max(start, W.index[14])]
    bh = w0["close"].iloc[-1] / w0["open"].iloc[0]
    bh_eq = W[W.index >= max(start, W.index[14])]["close"]
    bh_dd = (bh_eq / bh_eq.cummax() - 1).min()
    return eq, cagr, mdd, bh ** (1 / yrs) - 1, bh_dd, yrs


p("\n## 2. Trading versions (oversold new touch, buy next week open)")
p(f"futures: 1 lot = {LOT} (current lot, applied to all history), slippage {SLIP_PTS} pt/side, "
  f"carry drag {CARRY*100:.1f}%/yr on notional, monthly roll = extra round-trip charges + slippage, STT on sell side by era.")
p("ETF: Rs 1,00,000 compounding, all-in when in trade, idle cash earns 0; 0.05%/side spread, Rs 20/order, stamp/exch/STT.")
TRADES = {}
for mode in ["cc", "co"]:
    m = mr(W, 14, mode)
    for st in PERIODS:
        p(f"\n### mode={mode}, from {st.date()}")
        p("| rule | trades | win% | avg % | total Rs (1 lot fut) | avg Rs | worst Rs | fut maxDD Rs | B&H 1 lot Rs | ETF end Rs | ETF CAGR | ETF maxDD | B&H CAGR | B&H maxDD | time in mkt |")
        p("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for N in [4, 8, 13]:
            for mrx in [False, True]:
                tr = run_trades(m, N, mrx, st)
                if len(tr) == 0:
                    continue
                tr = fut_pnl(tr)
                cum = tr.rs.cumsum(); dd = (cum - cum.cummax().clip(lower=0)).min()
                w0 = W[W.index >= max(st, W.index[14])]
                days_all = (w0.index[-1] - w0.index[0]).days
                bh_rs = (w0["close"].iloc[-1] - w0["open"].iloc[0] - CARRY * days_all / 365 * w0["close"].mean()) * LOT
                eq, cagr, mdd, bhc, bhdd, yrs = etf_curve(tr, st)
                inmkt = sum((t.exit - t.entry).days + 4 for _, t in tr.iterrows()) / (days_all + 1)
                name = f"hold {N}w" + (" or MR>50" if mrx else "")
                p(f"| {name} | {len(tr)} | {(tr.rs>0).mean()*100:.0f} | {tr.pct.mean()*100:+.2f}% | {tr.rs.sum():+,.0f} | {tr.rs.mean():+,.0f} | "
                  f"{tr.rs.min():+,.0f} | {dd:+,.0f} | {bh_rs:+,.0f} | {eq:,.0f} | {cagr*100:+.1f}% | {mdd*100:.0f}% | {bhc*100:+.1f}% | {bhdd*100:.0f}% | {inmkt*100:.0f}% |")
                TRADES[(mode, st, N, mrx)] = tr
p("\nB&H 1 lot Rs = one lot held long the whole period (same carry drag, no roll charges). B&H CAGR = index price only (no dividends) from the same start.")

# per-trade list, cc mode, 8w or MR>50, full history
tr = TRADES[("cc", PERIODS[0], 8, True)]
p("\n### Trade list (cc, hold 8w or MR>50, full history)")
p("| signal | entry | exit | why | entry px | exit px | % | Rs 1 lot |")
p("|---|---|---|---|---|---|---|---|")
for _, t in tr.iterrows():
    p(f"| {t.sig.date()} | {t.entry.date()} | {t.exit.date()} | {t.why} | {t.ep:.0f} | {t.xp:.0f} | {t.pct*100:+.1f}% | {t.rs:+,.0f} |")


# ---------------------------------------------------------------- 2b. option approximation
def bs_call(S0, K, T, r, q, sig):
    if T <= 0:
        return max(S0 - K, 0.0)
    d1 = (math.log(S0 / K) + (r - q + 0.5 * sig * sig) * T) / (sig * math.sqrt(T))
    d2 = d1 - sig * math.sqrt(T)
    N = lambda x: 0.5 * (1 + math.erf(x / math.sqrt(2)))
    return S0 * math.exp(-q * T) * N(d1) - K * math.exp(-r * T) * N(d2)


RFR, DIVY = 0.065, 0.013
DV = D[["open", "close", "vix"]].copy()
DV["vix"] = DV["vix"].ffill()


def option_trade(t_entry_week, t_exit_week, exit_at_open, tenor_days):
    """Buy ATM call at the first daily open of entry week; IV = India VIX at previous daily close.
    Exit at exit-week close (or open) with IV = VIX that day (prev close if at open). Strike rounded to 50."""
    wk = DV[(DV.index > t_entry_week - pd.Timedelta(days=7)) & (DV.index <= t_entry_week)]
    d0 = wk.index[0]
    S0 = wk["open"].iloc[0]
    v0 = DV["vix"].shift(1).loc[d0]
    if np.isnan(v0):
        return None
    K = round(S0 / 50) * 50
    exp_date = d0 + pd.Timedelta(days=tenor_days)
    if exit_at_open:
        wk2 = DV[(DV.index > t_exit_week - pd.Timedelta(days=7)) & (DV.index <= t_exit_week)]
        d1 = wk2.index[0]; S1 = wk2["open"].iloc[0]; v1 = DV["vix"].shift(1).loc[d1]
    else:
        d1 = t_exit_week; S1 = DV.loc[d1, "close"]; v1 = DV.loc[d1, "vix"]
    d1 = min(d1, exp_date)
    T0 = tenor_days / 365; T1 = max((exp_date - d1).days, 0) / 365
    c0 = bs_call(S0, K, T0, RFR, DIVY, v0 / 100)
    c1 = bs_call(S1, K, T1, RFR, DIVY, v1 / 100)
    buy = c0 * 1.01; sell = c1 * 0.99           # 1% spread each side
    cost = 40 * 1.18 + 0.001 * sell * LOT + 0.00003 * buy * LOT + 0.0005 * (buy + sell) * LOT
    return dict(prem=buy, val=sell, ret=sell / buy - 1, rs=(sell - buy) * LOT - cost, vix0=v0, vix1=v1, S0=S0, S1=S1)


p("\n## 2b. Option-buying APPROXIMATION (Black-Scholes, IV = India VIX, no skew; NOT real option prices)")
p("ATM call bought at entry-week open, strike = spot rounded to 50, r=6.5%, q=1.3%, 1% spread/side, STT 0.1% on sell, Rs 47 brokerage+GST.")
OPTRES = {}
for mode in ["cc", "co"]:
    m = mr(W, 14, mode)
    for st in PERIODS:
        p(f"\n### mode={mode}, from {st.date()}")
        p("| option / exit rule | trades | win% | avg ret on premium | median | total Rs (1 lot=65) | avg Rs | worst Rs | avg premium Rs | sum-of-premium-weighted ret |")
        p("|---|---|---|---|---|---|---|---|---|---|")
        for tenor, N, mrx in [(35, 4, False), (35, 4, True), (63, 4, False), (63, 4, True), (63, 8, False), (63, 8, True)]:
            tr = run_trades(m, N, mrx, st)
            rows = []
            for _, t in tr.iterrows():
                o = option_trade(t.entry, t.exit, t.why != "time", tenor)
                if o:
                    o["sig"] = t.sig; rows.append(o)
            if not rows:
                continue
            o = pd.DataFrame(rows)
            OPTRES[(mode, st, tenor, N, mrx)] = o
            name = f"{tenor}d call, hold {N}w" + (" or MR>50" if mrx else "")
            p(f"| {name} | {len(o)} | {(o.rs>0).mean()*100:.0f} | {o.ret.mean()*100:+.0f}% | {o.ret.median()*100:+.0f}% | {o.rs.sum():+,.0f} | "
              f"{o.rs.mean():+,.0f} | {o.rs.min():+,.0f} | {(o.prem*LOT).mean():,.0f} | {o.rs.sum()/(o.prem*LOT).sum()*100:+.0f}% |")
o = OPTRES[("cc", PERIODS[0], 63, 4, True)]
p("\n### Option trade list (cc, 63-day ATM call, hold 4w or MR>50)")
p("| signal | spot in | VIX in | premium pts | spot out | VIX out | value out | ret | Rs 1 lot |")
p("|---|---|---|---|---|---|---|---|---|")
for _, x in o.iterrows():
    p(f"| {x.sig.date()} | {x.S0:.0f} | {x.vix0:.1f} | {x.prem:.0f} | {x.S1:.0f} | {x.vix1:.1f} | {x.val:.0f} | {x.ret*100:+.0f}% | {x.rs:+,.0f} |")

# ---------------------------------------------------------------- 3. robustness
p("\n## 3. Robustness (NOT for picking a best value)")
p("Cells: new-touch count n / mean 13-week excess return vs all-weeks baseline (pct points) / bootstrap p(mean>base). Oversold: MR <= k/L.")
for mode in ["cc", "co"]:
    p(f"\n### weekly, mode={mode}, oversold, full history")
    p("| L | k (MR<=) | n | 4w excess | 13w excess | p13 | 26w excess |")
    p("|---|---|---|---|---|---|---|")
    for L in [10, 12, 14, 16, 20]:
        m = mr(W, L, mode)
        for k in range(0, L // 2 + 1):
            thr = 100 * k / L
            new, _ = touches(m, thr, "low")
            cells = []
            ok = True
            for h in [4, 13, 26]:
                f = FW[h].dropna(subset=["ret"])
                s = f.loc[f.index.intersection(new[new].index), "ret"]
                if len(s) < 3:
                    ok = False; break
                pu, _, _, bm, _ = boot(f["ret"], len(s), s.mean(), (s > 0).mean())
                cells.append((len(s), s.mean() - bm, pu))
            if not ok:
                continue
            mark = " **(Boss)**" if (L == 14 and k == 4) else ""
            p(f"| {L} | {k} ({thr:.1f}){mark} | {cells[1][0]} | {cells[0][1]*100:+.2f} | {cells[1][1]*100:+.2f} | {cells[1][2]:.3f} | {cells[2][1]*100:+.2f} |")
    p(f"\n### weekly, mode={mode}, overbought, full history (excess <0 = reversal)")
    p("| L | k (MR>=) | n | 4w excess | 13w excess | p13(mean<base) | 26w excess |")
    p("|---|---|---|---|---|---|---|")
    for L in [10, 12, 14, 16, 20]:
        m = mr(W, L, mode)
        for k in range(math.ceil(L / 2), L + 1):
            thr = 100 * k / L
            new, _ = touches(m, thr, "high")
            cells = []; ok = True
            for h in [4, 13, 26]:
                f = FW[h].dropna(subset=["ret"])
                s = f.loc[f.index.intersection(new[new].index), "ret"]
                if len(s) < 3:
                    ok = False; break
                _, pd_, _, bm, _ = boot(f["ret"], len(s), s.mean(), (s > 0).mean())
                cells.append((len(s), s.mean() - bm, pd_))
            if not ok:
                continue
            mark = " **(Boss)**" if (L == 14 and k == 10) else ""
            p(f"| {L} | {k} ({thr:.1f}){mark} | {cells[1][0]} | {cells[0][1]*100:+.2f} | {cells[1][1]*100:+.2f} | {cells[1][2]:.3f} | {cells[2][1]*100:+.2f} |")

# 40-week MA filter
p("\n### 40-week MA trend filter (MR14 cc, oversold new touches)")
ma40 = W["close"].rolling(40).mean()
m = mr(W, 14, "cc")
new, _ = touches(m, 100 * 4 / 14, "low")
above = W["close"] > ma40
for lab, filt in [("no filter", new), ("close ABOVE 40w MA (dip in uptrend)", new & above), ("close BELOW 40w MA", new & ~above & ma40.notna())]:
    event_table(filt, PERIODS[0], lab, "low")

# daily chart
p("\n### Daily chart: MR14 on DAILY closes (<=4 of last 14 days up), forward in trading days")
DD = D[["open", "high", "low", "close"]].copy()
DHZ = [5, 10, 20, 40, 65, 130]
DFW = {h: fwd(DD, h) for h in DHZ}
for mode in ["cc", "co"]:
    md = mr(DD, 14, mode)
    st0 = pd.Timestamp("2011-01-01") if mode == "co" else PERIODS[0]
    newd, _ = touches(md, 100 * 4 / 14, "low")
    for st in [st0] + PERIODS[1:]:
        event_table(newd, st, f"daily {mode} oversold new touches", "low", fw=DFW, hz=DHZ, unit="d")

# ---------------------------------------------------------------- 4. intraday filter
p("\n## 4. Intraday direction filter (daily close > open), 2011+ (pre-2011 index opens ~= prev close)")
m = mr(W, 14, "cc")
new, allw = touches(m, 100 * 4 / 14, "low")
DI = D[D.index >= "2011-01-01"].copy()
wk_of = DI.index.to_series().apply(lambda d: W.index[W.index.searchsorted(d)] if W.index.searchsorted(d) < len(W) else pd.NaT)
pos = {t: i for i, t in enumerate(W.index)}
wk_i = wk_of.map(pos)
sig_i = [pos[t] for t in new[new].index]
zone_i = set(pos[t] for t in allw[allw].index)
in1 = wk_i.apply(lambda i: any(i - s == 1 for s in sig_i))
in4 = wk_i.apply(lambda i: any(1 <= i - s <= 4 for s in sig_i))
inz = wk_i.apply(lambda i: (i - 1) in zone_i)
from math import comb


def binom_p(k, n, p0):
    # two-sided normal approx
    z = (k - n * p0) / math.sqrt(n * p0 * (1 - p0))
    return math.erfc(abs(z) / math.sqrt(2))


p("| index | window | days | up-day % (close>open) | baseline % | avg open->close | baseline avg | p (two-sided) |")
p("|---|---|---|---|---|---|---|---|")
for sym in ["NIFTY", "BANKNIFTY"]:
    o = DI["open"] if sym == "NIFTY" else DI["BANKNIFTY_open"]
    c = DI["close"] if sym == "NIFTY" else DI["BANKNIFTY_close"]
    oc = (c / o - 1)
    up = oc > 0
    b = up.mean()
    for lab, msk in [("week right after new touch", in1), ("4 weeks after new touch", in4), ("any week after a week with MR<=28.57", inz)]:
        n = int(msk.sum()); k = int(up[msk].sum())
        p(f"| {sym} | {lab} | {n} | {k/n*100:.1f} | {b*100:.1f} | {oc[msk].mean()*100:+.3f}% | {oc.mean()*100:+.3f}% | {binom_p(k, n, b):.3f} |")

open(f"{OUT}/study_out.md", "w").write("\n".join(OUTLINES))
