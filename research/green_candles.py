"""Green candles on the year file: how often, what happens during them, and what comes before them.

    python research/green_candles.py <year.parquet>

A condition "comes before green" only counts if its green rate beats the base rate in BOTH halves of the year
(chronological split), by more than two standard errors overall. Rates are over the NEXT candle; nothing uses
the candle being explained.
"""
from __future__ import annotations

import sys

import numpy as np
import pandas as pd


def load(path):
    df = pd.read_parquet(path)
    df["ts"] = pd.to_datetime(df.ts)
    if df.ts.dt.tz is not None:
        df["ts"] = df.ts.dt.tz_localize(None)
    ix = df[df.right == "IX"].sort_values("ts").set_index("ts")[["open", "high", "low", "close"]]
    opts = df[df.right != "IX"]
    oi = opts.pivot_table(index="ts", columns="right", values="open_interest", aggfunc="sum").sort_index()
    vol = opts.pivot_table(index="ts", columns="right", values="volume", aggfunc="sum").sort_index()
    # ATM straddle per minute: CE + PE at the strike nearest the index each minute (the day's chain only)
    px = opts.merge(ix.close.rename("spot"), left_on="ts", right_index=True)
    px["dist"] = (px.strike - px.spot).abs()
    atm = px[px.dist == px.groupby(["ts", "right"]).dist.transform("min")]
    strad = atm.groupby("ts").close.sum()
    return ix, oi, vol, strad


def candles(ix, rule):
    b = ix.groupby(ix.index.date).apply(lambda d: d.resample(rule, label="left", closed="left").agg(
        {"open": "first", "high": "max", "low": "min", "close": "last"}).dropna()).droplevel(0)
    b = b.between_time("09:15", "15:25")
    b["green"] = b.close > b.open
    b["body"] = b.close - b.open
    return b


def rate(mask, y):
    y = y[mask]
    n = len(y)
    if n < 30:
        return None
    p = y.mean()
    return p, n, np.sqrt(p * (1 - p) / n)


def lift_table(b, conds, title, fwd_pts):
    y = b.green_next
    base = y.mean()
    half = b.index[len(b) // 2]
    lines = [f"### {title}", "", f"base rate: {100 * base:.1f}% green of {len(y)} candles", "",
             "| condition | candles | next green | 1st half | 2nd half | next candle avg pts | verdict |",
             "|---|---|---|---|---|---|---|"]
    for name, m in conds.items():
        m = pd.Series(m.values if hasattr(m, "values") else m, index=b.index).fillna(False).astype(bool)
        r = rate(m, y)
        if r is None:
            continue
        p, n, se = r
        h1 = y[m & (b.index < half)].mean()
        h2 = y[m & (b.index >= half)].mean()
        z = (p - base) / se
        steady = (h1 - base) * (h2 - base) > 0 and abs(z) > 2
        verdict = ("MORE green" if p > base else "LESS green") if steady else "noise"
        lines.append(f"| {name} | {n} | {100 * p:.1f}% | {100 * h1:.1f}% | {100 * h2:.1f}% | "
                     f"{fwd_pts[m].mean():+.1f} | {verdict} |")
    return "\n".join(lines) + "\n"


def param_stats(params, y, pts, rule):
    """Every parameter cut into five equal groups (quintiles): next-candle green rate and points in each group."""
    base = y.mean()
    half = params.index[len(params) // 2]
    lines = [f"### Every parameter vs the NEXT {rule} candle (quintiles, lowest to highest)", "",
             f"Base rate {100 * base:.1f}% green. Each cell: next green % / next avg points. "
             "'steady' = the top-minus-bottom gap has the same sign in both halves of the year and exceeds 2 standard errors.", "",
             "| parameter | Q1 (low) | Q2 | Q3 | Q4 | Q5 (high) | Q5-Q1 green | steady? |", "|---|---|---|---|---|---|---|---|"]
    for name in params.columns:
        x = params[name]
        ok = x.notna()
        try:
            q = pd.qcut(x[ok].rank(method="first"), 5, labels=False)
        except ValueError:
            continue
        cells, gaps = [], []
        for k in range(5):
            m = q == k
            cells.append(f"{100 * y[ok][m].mean():.1f}% / {pts[ok][m].mean():+.1f}")
        top, bot = q == 4, q == 0
        gap = y[ok][top].mean() - y[ok][bot].mean()
        se = np.sqrt(y[ok][top].var() / top.sum() + y[ok][bot].var() / bot.sum())
        first = x.index < half
        g1 = y[ok & first][q[first[ok]] == 4].mean() - y[ok & first][q[first[ok]] == 0].mean()
        g2 = y[ok & ~first][q[~first[ok]] == 4].mean() - y[ok & ~first][q[~first[ok]] == 0].mean()
        steady = "yes" if (g1 * g2 > 0 and abs(gap) > 2 * se) else "no"
        lines.append(f"| {name} | " + " | ".join(cells) + f" | {100 * gap:+.1f} pts% | {steady} |")
    return "\n".join(lines) + "\n"


def during(b, oi, vol, strad, rule):
    """What the option book does inside green vs red candles."""
    s = pd.DataFrame(index=b.index)
    end = b.index + pd.Timedelta(rule) - pd.Timedelta("1min")
    def at(series, t):
        return series.reindex(t, method="ffill").values
    s["ce_oi"] = at(oi.CE, end) / at(oi.CE, b.index) - 1
    s["pe_oi"] = at(oi.PE, end) / at(oi.PE, b.index) - 1
    s["strad"] = at(strad, end) / at(strad, b.index) - 1
    v = vol.resample(rule, label="left", closed="left").sum().reindex(b.index)
    s["vol_ratio"] = np.log((v.CE + 1) / (v.PE + 1))
    s["green"] = b.green.values
    s["body"] = b.body.values
    g = s.replace([np.inf, -np.inf], np.nan).groupby("green").median()
    big = s[s.body.abs() > s.body.abs().quantile(0.8)].replace([np.inf, -np.inf], np.nan).groupby("green").median()
    fmt = lambda d, k: f"{100 * d.loc[k, 'ce_oi']:+.2f}% | {100 * d.loc[k, 'pe_oi']:+.2f}% | {100 * d.loc[k, 'strad']:+.2f}% | {d.loc[k, 'vol_ratio']:+.2f}"
    return "\n".join([
        f"### During {rule} candles (median over candles)", "",
        "| candles | call OI change | put OI change | ATM straddle change | log(call vol / put vol) |", "|---|---|---|---|---|",
        f"| green | {fmt(g, True)} |", f"| red | {fmt(g, False)} |",
        f"| big green (top 20% body) | {fmt(big, True)} |", f"| big red (top 20% body) | {fmt(big, False)} |", ""])


def intraday(ix, oi, strad, rule):
    b = candles(ix, rule)
    day = pd.Series(b.index.date, index=b.index)
    b["green_next"] = b.groupby(day).green.shift(-1)
    b["pts_next"] = b.groupby(day).body.shift(-1)
    b = b.dropna(subset=["green_next"])
    b["green_next"] = b.green_next.astype(float)
    c = ix.close
    dopen = ix.groupby(ix.index.date).open.transform("first")
    hi = ix.groupby(ix.index.date).high.cummax()
    lo = ix.groupby(ix.index.date).low.cummin()
    end = b.index + pd.Timedelta(rule) - pd.Timedelta("1min")
    last = c.reindex(end, method="ffill").values
    twap = ((ix.high + ix.low + ix.close) / 3).groupby(ix.index.date).expanding().mean().droplevel(0)
    pos = ((c - lo) / (hi - lo).replace(0, np.nan)).reindex(end, method="ffill").values
    tw = (c / twap - 1).reindex(end, method="ffill").values * 1e4
    d = c.diff()
    up = d.clip(lower=0).ewm(alpha=1 / 14, adjust=False).mean()
    dn = (-d.clip(upper=0)).ewm(alpha=1 / 14, adjust=False).mean()
    rsi = (100 - 100 / (1 + up / dn)).reindex(end, method="ffill").values
    oi_ce = oi.CE.reindex(end, method="ffill").values / oi.CE.reindex(end - pd.Timedelta("30min"), method="ffill").values - 1
    oi_pe = oi.PE.reindex(end, method="ffill").values / oi.PE.reindex(end - pd.Timedelta("30min"), method="ffill").values - 1
    pcr = (oi.PE / oi.CE).reindex(end, method="ffill").values
    st = strad.reindex(end, method="ffill").values / strad.reindex(end - pd.Timedelta("30min"), method="ffill").values - 1
    run = b.groupby(day).green.transform(lambda g: g.groupby((g != g.shift()).cumsum()).cumcount() + 1)
    body = b.body.abs()
    t = b.index.strftime("%H:%M")
    fromopen = (last / dopen.reindex(b.index, method="ffill").values - 1) * 1e4
    conds = {
        "this candle green": b.green,
        "this candle red": ~b.green,
        "3+ greens in a row": b.green & (run >= 3),
        "3+ reds in a row": ~b.green & (run >= 3),
        "big green (top 20% body)": b.green & (body > body.quantile(0.8)),
        "big red (top 20% body)": ~b.green & (body > body.quantile(0.8)),
        "long lower wick (hammer)": (np.minimum(b.open, b.close) - b.low) > 2 * body,
        "long upper wick": (b.high - np.maximum(b.open, b.close)) > 2 * body,
        "first hour (09:15-10:15)": t < "10:15",
        "midday (11:30-13:30)": (t >= "11:30") & (t < "13:30"),
        "last hour (14:25+)": t >= "14:25",
        "near day low (bottom 10% of range)": pd.Series(pos < 0.1, index=b.index),
        "near day high (top 10% of range)": pd.Series(pos > 0.9, index=b.index),
        "well below TWAP (< -30 bps)": pd.Series(tw < -30, index=b.index),
        "well above TWAP (> +30 bps)": pd.Series(tw > 30, index=b.index),
        "RSI < 30": pd.Series(rsi < 30, index=b.index),
        "RSI > 70": pd.Series(rsi > 70, index=b.index),
        "down > 0.5% from open": pd.Series(fromopen < -50, index=b.index),
        "up > 0.5% from open": pd.Series(fromopen > 50, index=b.index),
        "put OI up > 5% in 30 min (put writing)": pd.Series(oi_pe > 0.05, index=b.index),
        "call OI up > 5% in 30 min (call writing)": pd.Series(oi_ce > 0.05, index=b.index),
        "put writing AND call unwinding": pd.Series((oi_pe > 0.03) & (oi_ce < 0), index=b.index),
        "call writing AND put unwinding": pd.Series((oi_ce > 0.03) & (oi_pe < 0), index=b.index),
        "PCR > 1.3": pd.Series(pcr > 1.3, index=b.index),
        "PCR < 0.7": pd.Series(pcr < 0.7, index=b.index),
        "straddle falling > 3% in 30 min": pd.Series(st < -0.03, index=b.index),
        "straddle rising > 3% in 30 min": pd.Series(st > 0.03, index=b.index),
    }
    params = pd.DataFrame({
        "time of day (minutes from 09:15)": (b.index.hour * 60 + b.index.minute - 555),
        "this candle body (pts)": b.body, "this candle range (pts)": b.high - b.low,
        "upper wick (pts)": b.high - np.maximum(b.open, b.close), "lower wick (pts)": np.minimum(b.open, b.close) - b.low,
        "same-colour run length (signed)": np.where(b.green, run, -run),
        "move from day open (bps)": fromopen, "position in day range (0-1)": pos, "distance from TWAP (bps)": tw,
        "RSI(14) on 1-min": rsi, "call OI change 30 min (%)": oi_ce * 100, "put OI change 30 min (%)": oi_pe * 100,
        "PCR (put OI / call OI)": pcr, "ATM straddle change 30 min (%)": st * 100,
    }, index=b.index).replace([np.inf, -np.inf], np.nan)
    return b, lift_table(b, conds, f"What comes before a green {rule} candle", b.pts_next) + "\n" + \
        param_stats(params, b.green_next, b.pts_next, rule)


def daily(ix, oi, strad):
    d = ix.groupby(ix.index.date).agg(open=("open", "first"), high=("high", "max"), low=("low", "min"), close=("close", "last"))
    d.index = pd.to_datetime(d.index)
    d["green"] = d.close > d.open
    d["pts"] = d.close - d.open
    first30 = ix.between_time("09:15", "09:44").groupby(ix.between_time("09:15", "09:44").index.date)
    f = first30.agg(o=("open", "first"), c=("close", "last"))
    f.index = pd.to_datetime(f.index)
    orng = ix.between_time("09:15", "10:04").groupby(ix.between_time("09:15", "10:04").index.date).agg(h=("high", "max"), l=("low", "min"))
    orng.index = pd.to_datetime(orng.index)
    c1030 = ix.between_time("10:30", "10:30").close
    c1030.index = pd.to_datetime(c1030.index.date)
    d["green_next"] = d.green.astype(float)          # daily: explain today's colour from what is known by the time given
    gap = d.open / d.close.shift() - 1
    prev_green = d.green.shift()
    oiday = oi.groupby(oi.index.date).last()
    oiday.index = pd.to_datetime(oiday.index)
    oi_prev = oiday.shift()
    oi_open = oi.between_time("09:15", "09:20").groupby(oi.between_time("09:15", "09:20").index.date).last()
    oi_open.index = pd.to_datetime(oi_open.index)
    conds = {
        "gap up > 0.3% (known 09:15)": gap > 0.003,
        "gap down > 0.3% (known 09:15)": gap < -0.003,
        "yesterday green (known 09:15)": prev_green == True,  # noqa: E712
        "yesterday red (known 09:15)": prev_green == False,  # noqa: E712
        "Monday": pd.Series(d.index.weekday == 0, index=d.index),
        "Friday": pd.Series(d.index.weekday == 4, index=d.index),
        "first 30 min green (known 09:45)": (f.c > f.o).reindex(d.index),
        "first 30 min red (known 09:45)": (f.c < f.o).reindex(d.index),
        "above opening range at 10:30 (known 10:30)": (c1030.reindex(d.index) > orng.h.reindex(d.index)),
        "below opening range at 10:30 (known 10:30)": (c1030.reindex(d.index) < orng.l.reindex(d.index)),
        "yesterday's PCR > 1 (known 09:15)": (oi_prev.PE / oi_prev.CE) > 1,
        "yesterday's PCR < 0.8 (known 09:15)": (oi_prev.PE / oi_prev.CE) < 0.8,
    }
    d = d.dropna(subset=["green_next"])
    return lift_table(d, conds, "What goes with a green DAY (known by the time in brackets)", d.pts), d


def lookback(ix, oi, strad, rule, ns=(20, 25)):
    """The 20 and 25 candles BEFORE each candle (running across days), compared for green vs red candles."""
    b = candles(ix, rule)
    b["pts"] = b.body
    out = [f"### The {ns[0]}-{ns[-1]} candles before each {rule} candle: before green vs before red", ""]
    start = b.index
    oi_ce = oi.CE.reindex(start, method="ffill")
    oi_pe = oi.PE.reindex(start, method="ffill")
    sd = strad.reindex(start, method="ffill")
    prof = []
    for n in ns:
        prev = b.shift(1)
        f = pd.DataFrame(index=b.index)
        f[f"greens in last {n}"] = prev.green.astype(float).rolling(n).sum()
        f[f"net move last {n} (pts)"] = prev.close - b.open.shift(n)
        hi = prev.high.rolling(n).max()
        lo = prev.low.rolling(n).min()
        f[f"range of last {n} (pts)"] = hi - lo
        f[f"where price sits in last-{n} range (0-1)"] = (prev.close - lo) / (hi - lo)
        f[f"trend slope last {n} (pts/candle)"] = prev.close.rolling(n).apply(
            lambda a: np.polyfit(np.arange(len(a)), a, 1)[0], raw=True)
        f[f"avg candle size last {n} (pts)"] = prev.body.abs().rolling(n).mean()
        f[f"biggest red in last {n} (pts)"] = prev.body.rolling(n).min()
        f[f"biggest green in last {n} (pts)"] = prev.body.rolling(n).max()
        f[f"call OI change over last {n} (%)"] = (oi_ce / oi_ce.shift(n) - 1) * 100
        f[f"put OI change over last {n} (%)"] = (oi_pe / oi_pe.shift(n) - 1) * 100
        f[f"ATM straddle change over last {n} (%)"] = (sd / sd.shift(n) - 1) * 100
        f = f.replace([np.inf, -np.inf], np.nan)
        g, r = b.green, ~b.green
        half = b.index[len(b) // 2]
        out += [f"**Last {n} candles**", "",
                "| measure (before the candle) | before GREEN (avg) | before RED (avg) | difference | 1st half diff | 2nd half diff | steady? |",
                "|---|---|---|---|---|---|---|"]
        for c in f.columns:
            x = f[c]
            a, bb = x[g].mean(), x[r].mean()
            se = np.sqrt(x[g].var() / x[g].count() + x[r].var() / x[r].count())
            h = b.index < half
            d1 = x[g & h].mean() - x[r & h].mean()
            d2 = x[g & ~h].mean() - x[r & ~h].mean()
            steady = "yes" if (d1 * d2 > 0 and abs(a - bb) > 2 * se) else "no"
            out.append(f"| {c} | {a:,.2f} | {bb:,.2f} | {a - bb:+,.2f} | {d1:+,.2f} | {d2:+,.2f} | {steady} |")
        out.append("")
    # candle-by-candle profile: average body of each of the 25 candles before a green vs a red candle
    n = ns[-1]
    rows = []
    for k in range(n, 0, -1):
        body = b.body.shift(k)
        rows.append((k, body[b.green].mean(), body[~b.green].mean(),
                     b.green.shift(k)[b.green].astype(float).mean(), b.green.shift(k)[~b.green].astype(float).mean()))
    out += [f"**Candle by candle: the {n} candles before (k = how many candles back)**", "",
            "| k back | avg body before GREEN | avg body before RED | % green before GREEN | % green before RED |",
            "|---|---|---|---|---|"]
    out += [f"| {k} | {x:+.2f} | {y:+.2f} | {100 * p:.1f}% | {100 * q:.1f}% |" for k, x, y, p, q in rows]
    return "\n".join(out) + "\n"


def main():
    ix, oi, vol, strad = load(sys.argv[1])
    out = [f"## Green candles, BANKNIFTY {ix.index.min().date()} .. {ix.index.max().date()}", ""]
    t, d = daily(ix, oi, strad)
    out += [f"Days: {len(d)}, green {100 * d.green.mean():.0f}%, average green day {d.pts[d.green].mean():+.0f} pts, "
            f"average red day {d.pts[~d.green].mean():+.0f} pts", "", t]
    for rule in ("15min", "5min"):
        b, tab = intraday(ix, oi, strad, rule)
        out += [during(b, oi, vol, strad, rule), tab, lookback(ix, oi, strad, rule)]
    print("\n".join(out))


if __name__ == "__main__":
    main()
