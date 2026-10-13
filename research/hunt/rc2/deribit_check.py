"""RC2 depth and cross-checks with Deribit's public trade history (design span only).
  (a) Does Delta India's option mark price like Deribit? Near-ATM implied vol of Delta's mark vs Deribit trade IV,
      interpolated in total variance to Delta's expiry, at 300 random hours per coin.
  (b) Zero to hero on REAL traded prices: Deribit daily expiries (08:00 UTC), every 3rd day 2022-01..2026-04,
      cheap OTM options (0.02-0.10% of the index) bought 4/3/2/1 h before expiry: how often the traded price
      later reached 3x / 5x / 10x before 07:50 UTC.
python3 -m research.hunt.rc2.deribit_check  -> results/deribit_check.md"""
import glob
import os

import numpy as np
import pandas as pd

from research.brain import bs
from . import config as C
from . import data

MON = {m: i + 1 for i, m in enumerate("JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC".split())}
L = []


def say(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    L.append(s)


def parse(name):
    _, d, k, t = name.split("-")
    return pd.Timestamp(int("20" + d[-2:]), MON[d[-5:-2]], int(d[:-5]), 8, tz="UTC"), float(k), t


def iv_check():
    say("## (a) Delta India mark vs Deribit trade IV (near the money)")
    rows = []
    for f in sorted(glob.glob(os.path.join(C.RAW, "deribit_ivchk", "*.parquet"))):
        coin = os.path.basename(f)[:3]
        H = pd.to_datetime(os.path.basename(f)[4:14], format="%Y%m%d%H").tz_localize("UTC")
        if H >= C.HOLDOUT_START:
            continue
        tr = pd.read_parquet(f)
        if tr.empty:
            continue
        p = tr.instrument_name.map(parse)
        tr["E"] = [x[0] for x in p]; tr["K"] = [x[1] for x in p]
        tr["T"] = (tr.E - H).dt.total_seconds() / 31536000
        tr = tr[(tr["T"] > 1 / 365 / 24) & (tr.iv > 0)]
        tr["m"] = np.log(tr.K / tr.index_price) / (tr.iv / 100 * np.sqrt(tr["T"]))
        a = tr[tr.m.abs() <= 0.5].groupby("E").agg(iv=("iv", "median"), T=("T", "first"), n=("iv", "size"))
        a = a[a.n >= 3]
        if len(a) < 1:
            continue
        # Delta's nearest expiry with >= 2 h left
        Ed = H.floor("D") + pd.Timedelta(hours=12)
        if Ed - H < pd.Timedelta(hours=2):
            Ed += pd.Timedelta(days=1)
        Td = (Ed - H).total_seconds() / 31536000
        w = a.iv.to_numpy() ** 2 * a["T"].to_numpy()
        Ts = a["T"].to_numpy()
        if Td <= Ts.min():
            iv_d = a.iv.iloc[0]                         # flat in vol before the first expiry
        elif Td >= Ts.max():
            iv_d = np.sqrt(w[-1] / Ts[-1])
        else:
            iv_d = np.sqrt(np.interp(Td, Ts, w) / Td)
        P = data.panel(coin, Ed)
        if P is None or P.bar(H) is None:
            continue
        i = P.bar(H)
        s = IDX[coin].get(H - C.BAR, np.nan)
        ks = [k for k in P.strikes["C"] if k in P.strikes["P"]]
        if not ks or not np.isfinite(s):
            continue
        k = min(ks, key=lambda x: abs(x - s))
        ivs = bs.implied_vol([P.arr[("C", k)][i, 0], P.arr[("P", k)][i, 0]], [s, s], [k, k], [Td, Td], [True, False])
        if not np.isfinite(ivs).any():
            continue
        iv_delta = np.nanmean(ivs) * 100
        if np.isfinite(iv_delta):
            rows.append(dict(coin=coin, H=H, hours=Td * 8760, deribit=iv_d, delta=iv_delta, ratio=iv_delta / iv_d))
    d = pd.DataFrame(rows)
    for coin in C.COINS:
        g = d[d.coin == coin]
        if g.empty:
            continue
        say(f"- {coin}: {len(g)} hours; Delta mark IV / Deribit trade IV: median {g.ratio.median():.3f}, "
            f"25-75% {g.ratio.quantile(.25):.3f}-{g.ratio.quantile(.75):.3f}; median IV Delta {g.delta.median():.1f}, "
            f"Deribit {g.deribit.median():.1f}; |difference| median {np.median(np.abs(g.delta - g.deribit)):.1f} vol points")
    say("")
    return d


def hero():
    say("## (b) Zero to hero on Deribit's traded prices (daily 08:00 UTC expiries, design span, every 3rd day)")
    rows = []
    for f in sorted(glob.glob(os.path.join(C.RAW, "deribit_exp", "*.parquet"))):
        coin = os.path.basename(f)[:3]
        E = pd.to_datetime(os.path.basename(f)[4:12], format="%Y%m%d").tz_localize("UTC") + pd.Timedelta(hours=8)
        tr = pd.read_parquet(f)
        if tr.empty:
            continue
        tr["t"] = pd.to_datetime(tr.timestamp, unit="ms", utc=True)
        tr["usd"] = tr.price * tr.index_price
        last_exit = E - C.LAST_EXIT_BEFORE_EXPIRY
        for h in (4, 3, 2, 1):
            S0 = E - pd.Timedelta(hours=h)
            for name, g in tr.groupby("instrument_name"):
                _, K, typ = parse(name)
                before = g[(g.t <= S0) & (g.t > S0 - pd.Timedelta(minutes=30))]
                if before.empty:
                    continue
                px, idx = before.usd.iloc[-1], before.index_price.iloc[-1]
                otm = K > idx if typ == "C" else K < idx
                if not otm or not (0.0002 * idx <= px <= 0.0010 * idx):
                    continue
                after = g[(g.t > S0) & (g.t <= last_exit)]
                mx = after.usd.max() / px if len(after) else 1.0
                rows.append(dict(coin=coin, E=E, h=h, mult=mx, trades_after=len(after), year=E.year))
    d = pd.DataFrame(rows)
    if d.empty:
        say("no data"); return d
    for coin in C.COINS:
        g = d[d.coin == coin]
        t = g.groupby("h").agg(options=("mult", "size"), days=("E", "nunique"), p3=("mult", lambda x: (x >= 3).mean()),
                               p5=("mult", lambda x: (x >= 5).mean()), p10=("mult", lambda x: (x >= 10).mean()))
        dd = g.groupby(["h", "E"]).mult.max().groupby("h")
        t["days_any_3x"] = dd.apply(lambda x: (x >= 3).mean())
        t["days_any_5x"] = dd.apply(lambda x: (x >= 5).mean())
        t["days_any_10x"] = dd.apply(lambda x: (x >= 10).mean())
        say(f"### {coin} (traded prices; an option with no later trade counts as 1x)")
        say("| bought | options | days | reached 3x | 5x | 10x | days any 3x | any 5x | any 10x |")
        say("|---|---|---|---|---|---|---|---|---|")
        for h, r in t.sort_index(ascending=False).iterrows():
            say(f"| {h} h before 08:00 UTC | {r.options:.0f} | {r.days:.0f} | {r.p3:.1%} | {r.p5:.1%} | {r.p10:.1%} | "
                f"{r.days_any_3x:.0%} | {r.days_any_5x:.0%} | {r.days_any_10x:.0%} |")
        say("")
        y = g[g.h == 2].groupby("year").mult.apply(lambda x: f"{(x >= 3).mean():.1%} of {len(x)}")
        say(f"- {coin}, bought 2 h before, reached 3x by year: " + ", ".join(f"{k}: {v}" for k, v in y.items()))
        say("")
    return d


def trade_vs_mark():
    """Delta's own traded 5-minute closes vs its mark close in the same bar (every 7th design day)."""
    say("## (c) Delta India traded price vs its mark (same 5-minute bar, bars with volume, design days)")
    rows = []
    for f in sorted(glob.glob(os.path.join(C.RAW, "delta_opt", "*.parquet"))):
        coin = os.path.basename(f)[:3]
        d = pd.read_parquet(f)
        if d.empty or "trade" not in set(d.kind) or d.expiry.iloc[0] >= C.HOLDOUT_START:
            continue
        m = d[d.kind == "mark"][["typ", "K", "time", "close"]]
        t = d[(d.kind == "trade") & (d.volume > 0)][["typ", "K", "time", "close", "volume"]]
        j = t.merge(m, on=["typ", "K", "time"], suffixes=("_trade", "_mark"))
        j["coin"] = coin
        j["E"] = d.expiry.iloc[0]
        j["hrs"] = (d.expiry.iloc[0] - pd.to_datetime(j.time, unit="s", utc=True)).dt.total_seconds() / 3600
        rows.append(j)
    d = pd.concat(rows)
    d = d[d.close_mark > 0]
    d["dev"] = d.close_trade / d.close_mark - 1
    for coin in C.COINS:
        g = d[d.coin == coin]
        say(f"- {coin}: {len(g)} bars with trades on {g.E.nunique()} expiries; traded close / mark - 1: "
            f"median {g.dev.median():+.2%}, 25-75% {g.dev.quantile(.25):+.2%} to {g.dev.quantile(.75):+.2%}; "
            f"|dev| median {g.dev.abs().median():.2%}")
        h = g.groupby(pd.cut(g.hrs, [0, 3, 6, 12, 30]), observed=True).agg(n=("dev", "size"), vol=("volume", "sum"))
        say(f"  bars with trades by hours to expiry: " + ", ".join(f"{k}: {r.n} bars" for k, r in h.iterrows()))
    say("")


IDX = {}

if __name__ == "__main__":
    for c in C.COINS:
        IDX[c] = data.index5m(c).c
    say("# RC2 Deribit depth and cross-check (design span only)")
    say("")
    iv_check()
    hero()
    trade_vs_mark()
    os.makedirs(C.OUT, exist_ok=True)
    open(os.path.join(C.OUT, "deribit_check.md"), "w").write("\n".join(L) + "\n")
