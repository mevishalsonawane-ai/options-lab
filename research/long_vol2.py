"""Non-directional buying, part 2: short holds, event days and expiry-day afternoons (BANKNIFTY, buying only).

    python research/long_vol2.py <prev_year_wide.parquet> <year_wide.parquet> <expiry_days.parquet> [out.md]

1. Short holds: the ATM straddle bought at every 15-minute mark from 09:20 to 14:40 and sold 15 / 30 / 60 minutes
   later (little time decay; needs a quick move either way). Also only after the first 15 minutes moved a lot.
2. Events (RBI policy days, Union Budgets, the 2024 election result): the ATM straddle bought at 15:15 one and two
   sessions before, sold on the event day at 10:30 (after the 10:00 RBI statement) or at 15:10. Only when the same
   contracts trade on both days (no expiry in between).
3. Expiry day (BANKNIFTY monthly expiries in the data, the expiring contracts): the ATM straddle and the 200 / 400
   point strangle bought at 13:00 / 14:00 / 14:30, sold at 15:20, or at 2x / 3x on the pair (the "hero-zero" trade).
Costs as always: 0.5 slippage a side, Rs 40 a leg, 1 lot of each leg (30).
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from long_vol import CUT, LEG, LOT, SLIP, atm, pair  # noqa: E402

EVENTS = {  # best-known dates; RBI statements are at 10:00
    "RBI": ["2024-04-05", "2024-06-07", "2024-08-08", "2024-10-09", "2024-12-06", "2025-02-07", "2025-04-09",
            "2025-06-06", "2025-08-06", "2025-10-01", "2025-12-05", "2026-02-06"],
    "Budget": ["2024-07-23", "2025-02-01", "2026-02-01"],
    "Election result": ["2024-06-04"],
}


def stat(x):
    x = pd.Series(x, dtype=float).dropna()
    if len(x) < 3:
        return f"{len(x)} | | | |"
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if x.std(ddof=1) > 0 else np.nan
    return f"{len(x)} | {100 * (x > 0).mean():.0f}% | Rs {x.mean():+,.0f} | Rs {x.sum():+,.0f} | {t:.2f}"


def short_holds(days):
    rows = []
    for d in days:
        I = d["I"]
        first15 = I["high"][:15].max() - I["low"][:15].min()
        for m0 in range(5, 330, 15):
            k = atm(d, I["open"][m0])
            if not k or not pair(d, k[0]):
                continue
            ce, pe = pair(d, k[0])
            e = ce["open"][m0] + pe["open"][m0] + 2 * SLIP
            for h in (15, 30, 60):
                m1 = min(m0 + h, CUT)
                x = ce["close"][m1] + pe["close"][m1] - 2 * SLIP
                rows.append(dict(day=d["day"], m0=m0, h=h, rs=(x - e) * LOT - 2 * LEG, first15=first15,
                                 move=abs(I["close"][m1] - I["open"][m0])))
    return pd.DataFrame(rows)


def events(days):
    idx = {str(d["day"]): i for i, d in enumerate(days)}
    out = []
    for kind, ds in EVENTS.items():
        for ev in ds:
            if ev not in idx:
                continue
            i = idx[ev]
            E = days[i]
            for back in (1, 2):
                if i - back < 0:
                    continue
                B = days[i - back]
                if B["exp"] != E["exp"]:
                    continue                                   # an expiry in between: not the same contracts
                k = atm(B, B["I"]["close"][360])
                if not k or not pair(B, k[0]) or not pair(E, k[0]):
                    continue
                ce, pe = pair(B, k[0])
                ce2, pe2 = pair(E, k[0])
                e = ce["open"][360] + pe["open"][360] + 2 * SLIP
                for lab, m in (("10:30", 75), ("15:10", CUT)):
                    x = ce2["close"][m] + pe2["close"][m] - 2 * SLIP
                    out.append(dict(kind=kind, day=ev, back=back, exit=lab, rs=(x - e) * LOT - 2 * LEG,
                                    cost=e, move=abs(E["I"]["close"][m] - B["I"]["close"][360])))
    return pd.DataFrame(out)


def expiry_days(path):
    x = pd.read_parquet(path)
    x = x[x.underlying.astype(str) == "BANKNIFTY"].copy()
    x["ts"] = pd.to_datetime(x.ts)
    x["m"] = x.ts.dt.hour * 60 + x.ts.dt.minute - (9 * 60 + 15)
    out = []
    for day, g in x.groupby(pd.to_datetime(x.day).dt.date):
        ix = g[g.right.astype(str) == "IX"].set_index("m").close
        if len(ix) < 300:
            continue
        opt = g[(g.right.astype(str) != "IX") & (pd.to_datetime(g.expiry).dt.date == day)]

        def px(k, r, m, col):
            s = opt[(opt.strike == k) & (opt.right.astype(str) == r)].set_index("m")
            s = s[col].reindex(range(375)).ffill()
            return s.get(m, np.nan)
        ks = np.array(sorted(opt.strike.unique()))
        for ent in (225, 285, 315):                         # 13:00, 14:00, 14:30
            spot = ix.reindex(range(375)).ffill().get(ent, np.nan)
            k0 = ks[np.argmin(np.abs(ks - spot))]
            for w in (0, 200, 400):
                kc = ks[np.argmin(np.abs(ks - (k0 + w)))]
                kp = ks[np.argmin(np.abs(ks - (k0 - w)))]
                ce_s = opt[(opt.strike == kc) & (opt.right.astype(str) == "CE")].set_index("m")
                pe_s = opt[(opt.strike == kp) & (opt.right.astype(str) == "PE")].set_index("m")
                if ce_s.empty or pe_s.empty:
                    continue
                cc = ce_s.close.reindex(range(375)).ffill()
                pc = pe_s.close.reindex(range(375)).ffill()
                co = ce_s.open.reindex(range(375)).fillna(cc)
                po = pe_s.open.reindex(range(375)).fillna(pc)
                e = co[ent] + po[ent] + 2 * SLIP
                if not np.isfinite(e) or e <= 2 * SLIP:
                    continue
                v = (cc + pc - 2 * SLIP).values
                end = 365                                      # 15:20
                x_end = v[end]
                res = {"hold": x_end}
                for mult in (2, 3):
                    hit = next((v[m] for m in range(ent, end + 1) if v[m] >= mult * e), None)
                    res[f"{mult}x"] = hit if hit is not None else x_end
                for lab, xv in res.items():
                    out.append(dict(day=day, entry=ent, width=w, exit=lab, rs=(xv - e) * LOT - 2 * LEG, cost=e))
    return pd.DataFrame(out)


def main():
    from sell_levels import load
    y0, y1 = load(sys.argv[1]), load(sys.argv[2])
    L = ["## Non-directional buying, part 2: short holds, events, expiry-day afternoons (research/long_vol2.py)", ""]
    sh = {n: short_holds(y) for n, y in (("A", y0), ("B", y1))}
    L += ["### 1. Short holds: ATM straddle bought at each time, sold 15 / 30 / 60 minutes later", "",
          "| entry | hold | A n | A win | A avg | A total | A t | B n | B win | B avg | B total | B t |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    best = []
    for (m0, h), _ in sh["B"].groupby(["m0", "h"]):
        a = sh["A"][(sh["A"].m0 == m0) & (sh["A"].h == h)].rs
        b = sh["B"][(sh["B"].m0 == m0) & (sh["B"].h == h)].rs
        t = f"{(m0 + 555) // 60:02d}:{(m0 + 555) % 60:02d}"
        L.append(f"| {t} | {h} | {stat(a)} | {stat(b)} |")
        best.append((min(a.mean(), b.mean()), t, h))
    L += ["", "Best by the worse year: " + ", ".join(f"{t} +{h}m (Rs {w:+,.0f})" for w, t, h in sorted(best, reverse=True)[:5])]
    L += ["", "Only on days whose first 15 minutes moved a lot (top third of each year):", "",
          "| entry | hold | A n | A win | A avg | A total | A t | B n | B win | B avg | B total | B t |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for m0 in (20, 35, 50):
        for h in (15, 30, 60):
            cells = []
            for n in ("A", "B"):
                s = sh[n]
                q = s.drop_duplicates("day").first15.quantile(2 / 3)
                cells.append(stat(s[(s.m0 == m0) & (s.h == h) & (s.first15 > q)].rs))
            L.append(f"| {(m0 + 555) // 60:02d}:{(m0 + 555) % 60:02d} | {h} | {cells[0]} | {cells[1]} |")

    ev = pd.concat([events(y0), events(y1)])
    L += ["", "### 2. Events: ATM straddle bought 15:15 one / two sessions before, sold on the event day", "",
          "| event | bought | sold | n | win | avg | total | t |", "|---|---|---|---|---|---|---|---|"]
    if not ev.empty:
        for (kind, back, ex), g in ev.groupby(["kind", "back", "exit"]):
            L.append(f"| {kind} | {back} day(s) before | {ex} | {stat(g.rs)} |")
        for (back, ex), g in ev.groupby(["back", "exit"]):
            L.append(f"| ALL events | {back} day(s) before | {ex} | {stat(g.rs)} |")
        L += ["", "Each event (bought 1 day before, sold 15:10): " + "; ".join(
            f"{r.day} {r.kind} Rs {r.rs:+,.0f} (moved {r.move:.0f} vs cost {r.cost:.0f})"
            for r in ev[(ev.back == 1) & (ev.exit == "15:10")].itertuples())]

    ex = expiry_days(sys.argv[3])
    L += ["", f"### 3. BANKNIFTY expiry-day afternoons ({ex.day.nunique() if not ex.empty else 0} monthly expiries, "
          "the expiring contracts)", "", "| entry | legs | exit | n | win | avg | total | t |", "|---|---|---|---|---|---|---|---|"]
    if not ex.empty:
        for (ent, w, x), g in ex.groupby(["entry", "width", "exit"]):
            legs = "ATM straddle" if w == 0 else f"strangle +/-{w}"
            L.append(f"| {(ent + 555) // 60:02d}:{(ent + 555) % 60:02d} | {legs} | {x} | {stat(g.rs)} |")
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 4:
        open(sys.argv[4], "w").write(text)


if __name__ == "__main__":
    main()
