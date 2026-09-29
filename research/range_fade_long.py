"""Range Fade on a long BANKNIFTY history (runs in GitHub Actions; the dev sandbox cannot reach the data).

Options: the Kaggle archive (samardubey/niftybanknifty-options-data, 1-minute OHLC per contract, read one session
at a time over HTTP Range - see options_lab/backfill/archive.py). Index: Upstox v3 1-minute candles (unauthenticated),
falling back to the near-month future when Upstox refuses.

Rules exactly as the app's RangeFadeRules: opening range 09:15-10:00 on 5-minute bars; decide on bars 10:30-13:55;
high within the top 10% of the range and close back below the range high -> buy the PE (mirror -> CE); entry at the
next bar on the option's first minute, +0.5 slippage; -40 / +40 premium points; 15:10 exit; at most 2 a day; strike
ATM from the 09:20 bar, nearest listed; the nearest expiry strictly after the day. Costs: 0.5 a side + Rs 40 a trip,
one lot of 30 throughout (so months compare in points, whatever the lot size then was).
"""
from __future__ import annotations

import os
import sys
import time
from datetime import date, timedelta

import numpy as np
import pandas as pd

LOT, SLIP, CHG = 30, 0.5, 40.0
TARGETS = (40, 30, 60)


def five_min(ix: pd.DataFrame) -> pd.DataFrame:
    b = ix.resample("5min", label="left", closed="left").agg(
        {"open": "first", "high": "max", "low": "min", "close": "last"}).dropna()
    return b.between_time("09:15", "15:25")


def fade_signal(r, orh, orl):
    w = orh - orl
    if w <= 0:
        return None
    if r.high >= orh - 0.1 * w and r.close < orh:
        return "PE"
    if r.low <= orl + 0.1 * w and r.close > orl:
        return "CE"
    return None


def day_trades(day: date, ix: pd.DataFrame, opts: pd.DataFrame, target: float, stop: float = 40, maxn: int = 2):
    """ix: 1-minute index (tz-naive IST index); opts: rows expiry, strike, right, ts, open, high, low, close."""
    b = five_min(ix)
    orb = b.between_time("09:15", "10:00")
    if len(orb) < 8 or b.index.max().strftime("%H:%M") < "14:00":
        return None
    orh, orl = orb.high.max(), orb.low.min()
    ref = b.between_time("09:20", "09:20").close
    spot = ref.iloc[0] if len(ref) else b.close.iloc[0]
    exps = sorted(e for e in opts.expiry.unique() if e > day)
    if not exps:
        return None
    ch = opts[opts.expiry == exps[0]]
    legs = {}
    for right in ("CE", "PE"):
        s = ch[ch.right == right]
        if s.empty:
            return None
        k = s.strike.unique()[np.argmin(np.abs(s.strike.unique() - spot))]
        legs[right] = s[s.strike == k].sort_values("ts").set_index("ts")
    out, n, busy = [], 0, None
    for j in range(len(b) - 1):
        t = b.index[j]
        hm = t.strftime("%H:%M")
        if not ("10:30" <= hm < "14:00") or n >= maxn:
            continue
        if busy is not None and t <= busy.floor("5min"):
            continue
        sig = fade_signal(b.iloc[j], orh, orl)
        if not sig:
            continue
        a = legs[sig][legs[sig].index >= b.index[j + 1]]
        if a.empty or a.index[0].strftime("%H:%M") >= "15:10":
            continue
        e = a.open.iloc[0] + SLIP
        pnl, ex, why = None, None, None
        for ts, r in a.iterrows():
            if r.low <= e - stop:
                pnl, ex, why = -stop - SLIP, ts, "stop"; break
            if r.high >= e + target:
                pnl, ex, why = target - SLIP, ts, "target"; break
            if ts.strftime("%H:%M") >= "15:10":
                pnl, ex, why = r.close - SLIP - e, ts, "15:10"; break
        if pnl is None:
            pnl, ex, why = a.close.iloc[-1] - SLIP - e, a.index[-1], "last"
        out.append(dict(day=day, signal=hm, right=sig, entry=round(e, 2), why=why, pts=round(pnl, 2),
                        net=round(pnl * LOT - CHG, 2)))
        n += 1
        busy = ex
    inside = orl <= b.close.iloc[-1] <= orh
    return out, dict(day=day, width=round(orh - orl, 1), closed_inside=inside,
                     move=round(b.close.iloc[-1] - b.open.iloc[0], 1))


def report(trades: pd.DataFrame, days: pd.DataFrame, title: str) -> str:
    lines = [f"### {title}", ""]
    if trades.empty:
        return "\n".join(lines + ["no trades", ""])
    alld = days.day.tolist()
    daily = trades.groupby("day").net.sum().reindex(alld, fill_value=0.0)
    eq = daily.cumsum()
    t = trades.net.mean() / (trades.net.std(ddof=1) / np.sqrt(len(trades))) if len(trades) > 2 else float("nan")
    lines += [f"{len(alld)} sessions {alld[0]} .. {alld[-1]}; {len(trades)} trades; win {100 * (trades.net > 0).mean():.0f}%; "
              f"net Rs {trades.net.sum():,.0f}; per trade Rs {trades.net.mean():,.0f}; t = {t:.2f}; "
              f"worst day Rs {daily.min():,.0f}; max drawdown Rs {(eq - eq.cummax()).min():,.0f}; "
              f"green days {(daily > 0).sum()}/{(daily != 0).sum()}", ""]
    lines += ["| month | sessions | trades | win % | net Rs | avg entry premium |", "|---|---|---|---|---|---|"]
    tm = trades.assign(m=pd.to_datetime(trades.day).dt.strftime("%Y-%m"))
    dm = days.assign(m=pd.to_datetime(days.day).dt.strftime("%Y-%m"))
    for m, g in dm.groupby("m"):
        x = tm[tm.m == m]
        lines.append(f"| {m} | {len(g)} | {len(x)} | {100 * (x.net > 0).mean() if len(x) else 0:.0f} | "
                     f"{x.net.sum():,.0f} | {x.entry.mean() if len(x) else 0:,.0f} |")
    j = trades.merge(days, on="day")
    for flag, name in ((True, "range days (closed inside the opening range)"), (False, "trend days (closed outside)")):
        x = j[j.closed_inside == flag]
        if len(x):
            lines.append(f"\n{name}: {len(x)} trades, win {100 * (x.net > 0).mean():.0f}%, net Rs {x.net.sum():,.0f}")
    return "\n".join(lines + [""])


# ---- data -------------------------------------------------------------------------------------------------------

INDEX_KEYS = {"BANKNIFTY": "NSE_INDEX%7CNifty%20Bank", "NIFTY": "NSE_INDEX%7CNifty%2050"}


def upstox_index(frm: date, to: date, underlying: str = "BANKNIFTY") -> pd.DataFrame | None:
    import requests
    key = INDEX_KEYS[underlying]
    parts, cur = [], frm
    while cur <= to:
        end = min(cur + timedelta(days=27), to)
        url = f"https://api.upstox.com/v3/historical-candle/{key}/minutes/1/{end:%Y-%m-%d}/{cur:%Y-%m-%d}"
        for attempt in range(5):
            r = requests.get(url, headers={"Accept": "application/json", "User-Agent": "Mozilla/5.0"}, timeout=60)
            if r.status_code == 429:
                time.sleep(15 * (attempt + 1)); continue
            break
        if r.status_code != 200:
            print(f"upstox {r.status_code} for {cur}..{end}: falling back to futures", flush=True)
            return None
        c = r.json().get("data", {}).get("candles", [])
        if c:
            df = pd.DataFrame(c).iloc[:, :5]
            df.columns = ["ts", "open", "high", "low", "close"]
            parts.append(df)
        cur = end + timedelta(days=1)
    if not parts:
        return None
    ix = pd.concat(parts)
    ix["ts"] = pd.to_datetime(ix.ts).dt.tz_localize(None)
    return ix.drop_duplicates("ts").sort_values("ts").set_index("ts")


def from_archive(sess: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    s = sess.copy()
    s["ts"] = pd.to_datetime(s["date"])
    if s.ts.dt.tz is not None:
        s["ts"] = s.ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    s["expiry"] = pd.to_datetime(s["expiry"]).dt.date
    opts = s[s.instrument_type.isin(["CE", "PE"])].rename(columns={"instrument_type": "right", "oi": "open_interest"})
    fut = s[s.instrument_type == "FUT"]
    if not fut.empty:
        fut = fut[fut.expiry == fut.expiry.min()].sort_values("ts").set_index("ts")[["open", "high", "low", "close"]]
    return opts[["expiry", "strike", "right", "ts", "open", "high", "low", "close", "volume", "open_interest"]], fut


def local_days():
    """The repo's 22 recorded sessions (for checking this script against the app's numbers)."""
    import glob
    for f in sorted(glob.glob("options_lab/data/bars/BANKNIFTY/*.parquet")):
        d = pd.read_parquet(f)
        d["ts"] = pd.to_datetime(d.ts).dt.tz_localize(None)
        d["expiry"] = pd.to_datetime(d.expiry).dt.date
        day = date.fromisoformat(f[-18:-8])
        ix = d[d.right == "IX"].sort_values("ts").set_index("ts")[["open", "high", "low", "close"]]
        yield day, ix, d[d.right != "IX"]


def file_days(path: str):
    """Days from a research/dump_year.py parquet."""
    df = pd.read_parquet(path)
    df["expiry"] = df["expiry"].dt.date
    for day, g in df.groupby(df["day"].dt.date):
        ix = g[g.right == "IX"].sort_values("ts").set_index("ts")[["open", "high", "low", "close"]]
        yield day, ix, g[g.right != "IX"]


def days_from(src: str):
    if src == "local":
        return local_days()
    if src.startswith("file:"):
        return file_days(src[5:])
    return archive_days(int(src))


def archive_days(n_sessions: int, underlying: str = "BANKNIFTY", only=None, skip: int = 0):
    """[only]: an optional predicate on the day's option rows - sessions it rejects are skipped after reading."""
    sys.path.insert(0, ".")
    from options_lab.backfill import archive
    zf = archive.open_archive()
    days = archive.available_days(zf.namelist(), underlying)
    days = days[: len(days) - skip] if skip else days
    days = days[-n_sessions:]
    print(f"archive: {underlying} sessions {days[0]} .. {days[-1]} (using {len(days)})", flush=True)
    ix_all = upstox_index(days[0], days[-1], underlying)
    for i, day in enumerate(days):
        try:
            sess = archive.read_session(zf, underlying, day)
        except Exception as e:  # noqa: BLE001
            print(f"{day}: read failed {e}", flush=True); continue
        opts, fut = from_archive(sess)
        if only is not None and not only(day, opts):
            continue
        ix = None
        if ix_all is not None:
            ix = ix_all[ix_all.index.date == day]
        if ix is None or len(ix) < 300:
            ix = fut
        if i % 20 == 0:
            print(f"{i}/{len(days)} {day} index rows {len(ix)} ({'upstox' if ix is not fut else 'future'})", flush=True)
        yield day, ix, opts


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "local"
    gen = days_from(src)
    res = {t: [] for t in TARGETS}
    info = []
    for day, ix, opts in gen:
        if ix is None or len(ix) == 0 or opts.empty:
            continue
        for t in TARGETS:
            r = day_trades(day, ix, opts, t)
            if r is None:
                break
            res[t] += r[0]
            if t == TARGETS[0]:
                info.append(r[1])
    days = pd.DataFrame(info)
    out = ["## Range Fade on the long history", ""]
    for t in TARGETS:
        tr = pd.DataFrame(res[t])
        out.append(report(tr, days, f"-40 / +{t}" + ("  (the app's rule)" if t == 40 else "")))
        if t == 40 and len(days) > 60:
            last = days.day.iloc[-126:]
            out.append(report(tr[tr.day.isin(set(last))], days[days.day.isin(set(last))], "-40 / +40, the last 6 months"))
            for q, part in enumerate(np.array_split(days.day.values, 4)):
                out.append(report(tr[tr.day.isin(set(part))], days[days.day.isin(set(part))], f"-40 / +40, quarter {q + 1}"))
    text = "\n".join(out)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as fh:
            fh.write(text + "\n")
    pd.DataFrame(res[40]).to_csv("range_fade_trades.csv", index=False)


if __name__ == "__main__":
    main()
