"""Open interest before buying (the owner's ask, 2026-10-01): would an OI check before an arm buys its option have
helped? Two BANKNIFTY years, the arms as they trade now, real minute option prices AND minute open interest.

    python research/oi_filter.py <year A wide parquet> <year B wide parquet> [out.md]

Trades:
  ORB, ORB Fresh, ORB Sweep, Range Fade   arms_long signals, ATM at 09:20, the profit-lock ladder (as live)
  Liquidity 15+5                          15-min + 5-min books, 15% stop, 30-pt index stop, 20-min time stop (as live)
Nothing about the trades changes; each OI check only decides whether a trade is TAKEN. OI is read at the minute
before the entry (what the app could know when it decides), on the option's own expiry, strikes within 3 of the
bought strike. "Since open" is from 09:15, "last 15 min" the 15 minutes before the decision.

Checks (each: take the trade only when it says yes):
  own OI falling 15m        the option we buy lost OI in the last 15 min (its writers covering: they stop leaning on it)
  own OI not piling 15m     the option we buy did not add more than 2% OI in the last 15 min (no writers piling in)
  writers lean our way      since open, OI added on the other side exceeds OI added on our side (for a call: more
                            puts written than calls near the money - support under the move)
  other side adding 15m     the other side's OI near the money rose in the last 15 min
  PCR moving our way        put/call OI ratio near the money rose since 09:20 for a call buy (fell for a put buy)
  short covering 15m        the option we buy rose in price while its OI fell in the last 15 min
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import arms_long as al  # noqa: E402
from profit_lock import fill_ladder  # noqa: E402
from range_fade_long import CHG, LOT  # noqa: E402
from liquidity_break import bars, simulate  # noqa: E402
from sell_levels import load as liq_load  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

LADDER = [(0.25, 0.0), (0.50, 0.25), (0.75, 0.50)]
NEAR = 3


def oi_days(path):
    """day -> (minute x (strike, right) OI frame, minute x (strike, right) close frame, sorted strikes)."""
    d = pd.read_parquet(path, columns=["ts", "right", "expiry", "strike", "close", "open_interest", "day"])
    d = d[d.right != "IX"]
    d["ts"] = pd.to_datetime(d.ts)
    d["m"] = d.ts.dt.hour * 60 + d.ts.dt.minute - 555
    out = {}
    for day, g in d.groupby(d.day.dt.date):
        exps = sorted(e for e in g.expiry.dt.date.unique() if e > day)
        if not exps:
            continue
        c = g[g.expiry.dt.date == exps[0]]
        oi = c.pivot_table(index="m", columns=["strike", "right"], values="open_interest", aggfunc="last").reindex(range(375)).ffill()
        px = c.pivot_table(index="m", columns=["strike", "right"], values="close", aggfunc="last").reindex(range(375)).ffill()
        out[day] = (oi, px, sorted(c.strike.unique()))
    return out


def features(od, day, m, k, right):
    """The checks for buying (k, right) with the decision at minute m (the entry is at m + 1)."""
    if day not in od or m < 16:
        return None
    oi, px, ks = od[day]
    other = "PE" if right == "CE" else "CE"
    i = int(np.argmin(np.abs(np.array(ks) - k)))
    near = ks[max(0, i - NEAR): i + NEAR + 1]

    def v(col, mm):
        return oi[col].iloc[mm] if col in oi.columns else np.nan

    def side(r, mm):
        return np.nansum([v((s, r), mm) for s in near])

    own, opp = (k, right), (k, other)
    o_now, o_15, o_open = v(own, m), v(own, m - 15), v(own, 0)
    p_now, p_15 = (px[own].iloc[m], px[own].iloc[m - 15]) if own in px.columns else (np.nan, np.nan)
    if not (o_now > 0 and o_15 > 0 and o_open > 0):
        return None
    ours_add = side(right, m) - side(right, 0)
    theirs_add = side(other, m) - side(other, 0)
    pcr = lambda mm: side("PE", mm) / max(side("CE", mm), 1)  # noqa: E731
    pcr_chg = pcr(m) - pcr(min(5, m))
    return {
        "own OI falling 15m": o_now < o_15,
        "own OI not piling 15m": o_now <= o_15 * 1.02,
        "writers lean our way": theirs_add > ours_add,
        "other side adding 15m": side(other, m) > side(other, m - 15),
        "PCR moving our way": pcr_chg > 0 if right == "CE" else pcr_chg < 0,
        "short covering 15m": (p_now > p_15) and (o_now < o_15),
    }


def orb_family(days, od):
    rows = []
    ind, st15 = al.indicators(days)
    for name in ("ORB", "ORB Fresh", "ORB Sweep", "Range Fade"):
        sig, stop, tgt, maxn = al.ARMS[name]
        for day, b, legs in days:
            orb_ = b.between_time("09:15", "10:00")
            ctx = dict(orh=orb_.high.max(), orl=orb_.low.min(), ind=ind, st15=st15)
            ref = b.between_time("09:20", "09:20").close
            spot = ref.iloc[0] if len(ref) else b.close.iloc[0]
            ks = od[day][2] if day in od else []
            if not len(ks):
                continue
            k = ks[int(np.argmin(np.abs(np.array(ks) - spot)))]
            n, busy = 0, None
            for j in range(1, len(b) - 1):
                if n >= maxn:
                    break
                if busy is not None and b.index[j] <= busy.floor("5min"):
                    continue
                s = sig(b, j, ctx)
                if not s:
                    continue
                t_entry = b.index[j + 1]
                f = fill_ladder(legs[s], t_entry, stop, tgt, LADDER)
                if f is None:
                    continue
                n += 1
                busy = f[1]
                a = legs[s][legs[s].index >= t_entry]
                m = a.index[0].hour * 60 + a.index[0].minute - 555
                rows.append(dict(arm=name, day=day, net=f[0] * LOT - CHG, f=features(od, day, m - 1, k, s)))
    return rows


def liquidity(path, od):
    days = liq_load(path)
    rows = []
    for tf in (15, 5):
        b = bars(days, tf)
        zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
        tr = simulate(days, b, zones, "both", True, prem_stop=0.15, ix_buffer=30, time_stop=(20, 0.05))
        for r in tr.itertuples():
            if r.key is None:
                continue
            k, right = r.key
            rows.append(dict(arm="Liquidity 15+5", day=r.day, net=r.rs, f=features(od, r.day, r.m0 - 1, k, right)))
    return rows


def cell(x):
    if len(x) == 0:
        return "0 | | "
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else float("nan")
    return f"{len(x)} | {100 * (x > 0).mean():.0f}% | Rs {x.sum():+,.0f} ({t:+.2f})"


def main():
    pa, pb = sys.argv[1], sys.argv[2]
    odA, odB = oi_days(pa), oi_days(pb)
    od = {**odA, **odB}
    print("OI days", len(odA), len(odB), flush=True)
    rows = []
    for path in (pa, pb):
        rows += orb_family(al.load("file:" + path), od)
        rows += liquidity(path, od)
    df = pd.DataFrame(rows)
    df = df[df.f.notna()].copy()
    A = set(odA)
    df["yr"] = np.where(df.day.isin(A), "A", "B")
    checks = list(df.f.iloc[0].keys())
    L = ["## An open-interest check before buying (research/oi_filter.py)", "",
         f"BANKNIFTY year A {min(odA)} .. {max(odA)}, year B {min(odB)} .. {max(odB)}. The arms as they trade now, real",
         "minute option prices and open interest, 1 lot of 30, after costs. Each check only decides whether a trade is taken.",
         "Cells: trades | win | net (t). \"Kept\" are the trades the check allows; \"skipped\" the ones it blocks.", ""]
    for arm in ["ORB", "ORB Fresh", "ORB Sweep", "Range Fade", "Liquidity 15+5"]:
        x = df[df.arm == arm]
        if x.empty:
            continue
        L += [f"### {arm}", "", "| check | year A kept | year A skipped | year B kept | year B skipped | change vs today (A / B) |",
              "|---|---|---|---|---|---|",
              f"| today (no check) | {cell(x[x.yr == 'A'].net)} | | {cell(x[x.yr == 'B'].net)} | | |"]
        for c in checks:
            ok = x.f.map(lambda f: bool(f[c]))
            ka, sa = x[(x.yr == "A") & ok].net, x[(x.yr == "A") & ~ok].net
            kb, sb = x[(x.yr == "B") & ok].net, x[(x.yr == "B") & ~ok].net
            L.append(f"| {c} | {cell(ka)} | {cell(sa)} | {cell(kb)} | {cell(sb)} | Rs {-sa.sum():+,.0f} / Rs {-sb.sum():+,.0f} |")
        L.append("")
        print("\n".join(L[-len(checks) - 4:]), flush=True)
    text = "\n".join(L)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
