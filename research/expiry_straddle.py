"""Expiry-day afternoon straddles (buying only), BANKNIFTY monthly expiries and, as an independent check of the
same mechanism, NIFTY weekly expiries.

    python research/expiry_straddle.py <expiry_days.parquet> [out.md]

On an expiry day the expiring ATM call + put are bought at an afternoon minute (open + 0.5 each) and sold at a later
minute's close - 0.5, or earlier if the pair reaches a multiple of its cost. Rs 40 a leg, 1 lot each leg (BANKNIFTY
30, NIFTY 75). Also split by what was known at entry: the day's range so far and the straddle's price vs that range.
"""
import sys

import numpy as np
import pandas as pd

SLIP, LEG = 0.5, 40.0
LOTS = {"BANKNIFTY": 30, "NIFTY": 75}


def grids(path):
    x = pd.read_parquet(path)
    x["ts"] = pd.to_datetime(x.ts)
    x["m"] = x.ts.dt.hour * 60 + x.ts.dt.minute - (9 * 60 + 15)
    x["u"] = x.underlying.astype(str)
    x["r"] = x.right.astype(str)
    x["dday"] = pd.to_datetime(x.day).dt.date
    return x


def trades(x, u):
    out = []
    for day, g in x[x.u == u].groupby("dday"):
        ix = g[g.r == "IX"].set_index("m")
        if len(ix) < 300:
            continue
        ixc = ix.close.reindex(range(375)).ffill()
        ixh = ix.high.reindex(range(375)).ffill()
        ixl = ix.low.reindex(range(375)).ffill()
        opt = g[(g.r != "IX") & (pd.to_datetime(g.expiry).dt.date == day)]
        if opt.empty:
            continue
        ks = np.array(sorted(opt.strike.unique()))
        series = {}
        for (k, r), s in opt.groupby(["strike", "r"]):
            s = s.set_index("m")
            c = s.close.reindex(range(375)).ffill()
            series[(k, r)] = (s.open.reindex(range(375)).fillna(c).values, c.values)
        for ent in (225, 255, 285, 300, 315, 330):              # 13:00 13:30 14:00 14:15 14:30 14:45
            spot = ixc[ent - 1]
            k0 = ks[np.argmin(np.abs(ks - spot))]
            if (k0, "CE") not in series or (k0, "PE") not in series:
                continue
            (co, cc), (po, pc) = series[(k0, "CE")], series[(k0, "PE")]
            e = co[ent] + po[ent] + 2 * SLIP
            if not np.isfinite(e):
                continue
            v = cc + pc - 2 * SLIP
            rng_so_far = ixh[:ent].max() - ixl[:ent].min()
            for end in (360, 365, 370):                         # 15:15 15:20 15:25
                for mult in (None, 2.0, 3.0):
                    xv = v[end]
                    if mult:
                        hit = next((v[m] for m in range(ent, end + 1) if v[m] >= mult * e), None)
                        xv = hit if hit is not None else v[end]
                    out.append(dict(u=u, day=day, entry=ent, end=end, mult=mult or 0, cost=e,
                                    rs=(xv - e) * LOTS[u] - 2 * LEG, cost_vs_range=e / rng_so_far,
                                    move=abs(ixc[end] - spot)))
    return pd.DataFrame(out)


def stat(s):
    s = pd.Series(s).dropna()
    if len(s) < 3:
        return f"{len(s)} | | | | |"
    t = s.mean() / (s.std(ddof=1) / np.sqrt(len(s)))
    return f"{len(s)} | {100 * (s > 0).mean():.0f}% | Rs {s.mean():+,.0f} | Rs {s.sum():+,.0f} | {t:.2f} | Rs {s.median():+,.0f}"


def hm(m):
    return f"{(m + 555) // 60:02d}:{(m + 555) % 60:02d}"


def main():
    x = grids(sys.argv[1])
    L = ["## Expiry-day afternoon straddles, buying only (research/expiry_straddle.py)", ""]
    for u in ("BANKNIFTY", "NIFTY"):
        t = trades(x, u)
        L += [f"### {u}: {t.day.nunique()} expiry days {t.day.min()} .. {t.day.max()} (lot {LOTS[u]})", "",
              "| bought | sold | take profit | days | win | avg | total | t | median |", "|---|---|---|---|---|---|---|---|---|"]
        for (ent, end, mult), g in t.groupby(["entry", "end", "mult"]):
            L.append(f"| {hm(ent)} | {hm(end)} | {'none' if mult == 0 else f'{mult:.0f}x'} | {stat(g.rs)} |")
        base = t[(t.entry == 285) & (t.end == 365) & (t.mult == 0)]
        if len(base) >= 6:
            q = base.cost_vs_range.median()
            L += ["", f"14:00 -> 15:20, split by the straddle's price vs the day's range so far (median {q:.2f}):", "",
                  "| straddle vs range | days | win | avg | total | t | median |", "|---|---|---|---|---|---|---|",
                  f"| cheap (below median) | {stat(base[base.cost_vs_range <= q].rs)} |",
                  f"| dear (above median) | {stat(base[base.cost_vs_range > q].rs)} |"]
            half = sorted(base.day)[len(base) // 2]
            L += ["", f"First half / second half of the days: {stat(base[base.day < half].rs)} / "
                  f"{stat(base[base.day >= half].rs)}", "",
                  "Each day (14:00 -> 15:20): " + "; ".join(f"{r.day} Rs {r.rs:+,.0f} (cost {r.cost:.0f}, moved {r.move:.0f})"
                                                            for r in base.itertuples())]
        L.append("")
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
