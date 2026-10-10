"""h24 part 1: real top-of-book half-spreads (Dhan option-chain snapshot, 2026-10-06 ~11:02-11:05 IST) vs the models.

    python3 -I research/hunt/h24/snap.py

Writes <scratch>/hunt/h24/: snap_rows.csv (every near-ATM contract), snap_summary.csv (1-ITM / ATM per index),
roll_fresh.csv (Roll on raw minute prints for the same contracts: 2026-10-06 and Sep 2026),
roll_vol.csv (pooled Roll half-spread by 15-min index-move quintile, holdout monthly contracts),
roll_signal.csv (pooled Roll in the window around each h23 entry vs the same contracts' whole day).
Half-spread hs = (ask - bid) / 2 / mid. Roll hs = sqrt(-cov(r_t, r_t-1)) of 1-min log returns of consecutive prints.
"""
from __future__ import annotations

import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
DATA = os.path.join(SCR, "dhan/repo/dhan-data")
OUT = os.path.join(SCR, "hunt/h24")
STEP = {"NIFTY": 50, "BANKNIFTY": 100, "FINNIFTY": 50, "MIDCPNIFTY": 25}
UNDS = ("BANKNIFTY", "MIDCPNIFTY", "FINNIFTY", "NIFTY")
DAY = pd.Timestamp("2026-10-06").date()
MONTHLY = "2026-10-27"
TICK = 0.05
HOLD = pd.Timestamp("2025-10-01")
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)


def h17_hs(p, ticks=1):
    """h10 'liq' fill (h17's model) as a half-spread: 5 bps + 1 tick (>= 20 lots traded in 5 min), 2 ticks if thinner."""
    return 0.0005 + ticks * TICK / p


# ------------------------------------------------------------------------------------------------ snapshot
def snapshot():
    rows = []
    for u in UNDS:
        d = pd.read_parquet(os.path.join(DATA, "optionchain/2026-10-06", u + ".parquet"))
        exps = sorted(d.expiry.unique())
        want = {MONTHLY: "monthly (nearest)", "2026-11-23": "next monthly"}
        if u == "NIFTY":
            want["2026-10-13"] = "next weekly"
        for e, lab in want.items():
            if e not in exps:
                continue
            x = d[(d.expiry == e) & (d.top_bid_price > 0) & (d.top_ask_price > 0)].copy()
            spot = float(x.underlying_ltp.iloc[0])
            atm = round(spot / STEP[u]) * STEP[u]
            k = (atm - x.strike) / STEP[u]
            x["money"] = np.where(x.side == "CE", k, -k).astype(int)          # +1 = 1-ITM, 0 = ATM, -1 = 1-OTM
            x = x[x.money.abs() <= 3]
            x["mid"] = (x.top_bid_price + x.top_ask_price) / 2
            x["hs_pct"] = 100 * (x.top_ask_price - x.top_bid_price) / 2 / x.mid
            x["ask_vs_ltp_pct"] = 100 * (x.top_ask_price / x.last_price - 1)
            x["h17_hs_pct"] = 100 * h17_hs(x.mid)
            x["ticks"] = np.round((x.top_ask_price - x.top_bid_price) / TICK)
            x["und"], x["series"], x["spot"] = u, lab, spot
            x["time_ist"] = (x.snapshot_utc + pd.Timedelta(hours=5, minutes=30)).dt.strftime("%H:%M:%S")
            rows.append(x[["und", "series", "expiry", "time_ist", "spot", "strike", "side", "money", "top_bid_price",
                           "top_ask_price", "top_bid_quantity", "top_ask_quantity", "last_price", "volume", "oi", "mid",
                           "ticks", "hs_pct", "ask_vs_ltp_pct", "h17_hs_pct"]])
    R = pd.concat(rows, ignore_index=True)
    R.to_csv(os.path.join(OUT, "snap_rows.csv"), index=False)
    m = R[R.series == "monthly (nearest)"]
    S = []
    for u, g in m.groupby("und", sort=False):
        for nm, sel in (("1-ITM", g.money == 1), ("ATM", g.money == 0), ("1-ITM+ATM", g.money.isin([0, 1])),
                        ("ITM2..OTM2", g.money.abs() <= 2)):
            q = g[sel]
            S.append(dict(und=u, money=nm, n=len(q), prem_lo=q.mid.min(), prem_hi=q.mid.max(), hs_min=q.hs_pct.min(),
                          hs_med=q.hs_pct.median(), hs_mean=q.hs_pct.mean(), hs_max=q.hs_pct.max(),
                          ticks_med=q.ticks.median(), top_qty_lots_med=np.nan, h17_hs_med=q.h17_hs_pct.median(),
                          volume_med=q.volume.median()))
    S = pd.DataFrame(S)
    S.to_csv(os.path.join(OUT, "snap_summary.csv"), index=False)
    print("SNAPSHOT 2026-10-06, nearest monthly (2026-10-27), half-spread % of mid\n", S.round(3).to_string())
    print("\nAll rows, nearest monthly, money -2..+2\n",
          m[m.money.abs() <= 2].sort_values(["und", "side", "money"]).round(3).to_string())
    o = R[R.series != "monthly (nearest)"]
    print("\nOther expiries (info), 1-ITM+ATM median hs %:\n",
          o[o.money.isin([0, 1])].groupby(["und", "series"]).hs_pct.agg(["count", "median", "min", "max"]).round(3))
    return R


# ------------------------------------------------------------------------------------------------ raw minute data
def load_month(u, years=(2025, 2026)):
    fr = []
    for side, s in (("CALL", 1), ("PUT", -1)):
        for y in years:
            p = os.path.join(DATA, "options", u, "MONTH", side, f"{y}.parquet")
            if os.path.exists(p):
                x = pd.read_parquet(p, columns=["strike", "spot", "open", "high", "low", "close", "volume", "ts", "offset"])
                x["side"] = s
                fr.append(x)
    x = pd.concat(fr, ignore_index=True)
    x["day"] = x.ts.dt.tz_localize(None).dt.normalize()
    x["mn"] = x.ts.dt.hour * 60 + x.ts.dt.minute
    x["money"] = np.where(x.side == 1, -x.offset, x.offset)    # CALL offset -1 = 1-ITM; PUT offset +1 = 1-ITM
    return x


def pairs(c):
    """cross-products of consecutive 1-min log returns of consecutive prints."""
    r = np.diff(np.log(c))
    return r[1:] * r[:-1], r[1:] ** 2


def roll_hs(s, n):
    return float(np.sqrt(max(0.0, -s / n))) if n > 0 else np.nan


def roll_on(x, keys):
    """pooled Roll half-spread over contract-day series (prints only)."""
    S = N = 0.0
    for _, g in x.sort_values("ts").groupby(keys):
        c = g.close.values[(g.volume.values > 0) & (g.close.values > 0)]
        if len(c) < 4:
            continue
        cp, _ = pairs(c)
        S += cp.sum()
        N += len(cp)
    return roll_hs(S, N), int(N)


def fresh_roll(snap, X):
    """Roll on the same contracts the snapshot priced (1-ITM / ATM monthly), on 2026-10-06 and over Sep 2026."""
    rows = []
    h23 = pd.read_csv(os.path.join(SCR, "hunt/h23/roll.csv"))
    for u in UNDS:
        if u not in X:
            continue
        x = X[u]
        m = snap[(snap.und == u) & (snap.series == "monthly (nearest)") & snap.money.isin([0, 1])]
        sel = set(zip(m.strike, np.where(m.side == "CE", 1, -1)))
        today = x[x.day == pd.Timestamp(DAY)]
        t = today[[(k, s) in sel for k, s in zip(today.strike, today.side)]]
        hs_day, n_day = roll_on(t, ["strike", "side"])
        win = t[(t.mn >= 10 * 60 + 30) & (t.mn <= 11 * 60 + 40)]
        hs_win, n_win = roll_on(win, ["strike", "side"])
        sep = x[(x.day >= "2026-09-01") & (x.day < "2026-10-01")]
        # Sep: strikes that were 1-ITM/ATM at some minute of the day (whole day of that strike), as h23 did for trades
        k = sep[sep.money.isin([0, 1])][["day", "strike", "side"]].drop_duplicates()
        s2 = sep.merge(k, on=["day", "strike", "side"])
        hs_sep, n_sep = roll_on(s2, ["day", "strike", "side"])
        hold = x[(x.day >= HOLD)]
        k = hold[hold.money.isin([0, 1])][["day", "strike", "side"]].drop_duplicates()
        s3 = hold.merge(k, on=["day", "strike", "side"])
        hs_hold, n_hold = roll_on(s3, ["day", "strike", "side"])
        r = h23[h23.und == u].set_index("month").spread / 2
        snap_hs = m.hs_pct.median() / 100
        rows.append(dict(und=u, snap_hs=snap_hs, roll_day_whole=hs_day, pairs_day=n_day, roll_day_1030_1140=hs_win,
                         pairs_win=n_win, roll_sep26_all_itm1atm=hs_sep, pairs_sep=n_sep,
                         roll_holdout_all_itm1atm=hs_hold, pairs_hold=n_hold,
                         h23_roll_sep26=r.get("2026-09", np.nan), h23_roll_oct26=r.get("2026-10", np.nan),
                         h23_roll_hold_median=float(r[r.index >= "2025-10"].median()) if len(r) else np.nan,
                         h23_roll_hold_mean=float(r[r.index >= "2025-10"].mean()) if len(r) else np.nan,
                         h23_roll_pre_mean=float(r[r.index < "2025-10"].mean()) if len(r) else np.nan,
                         h17_hs=float(np.median(h17_hs(m.mid.values)))))
    F = pd.DataFrame(rows)
    F.to_csv(os.path.join(OUT, "roll_fresh.csv"), index=False)
    print("\nREAL vs MODELS (fractions of premium; x100 = %)\n", (F.set_index("und") * 1).T.round(5).to_string())
    return F


# ------------------------------------------------------------------------------------------------ Roll vs volatility
def roll_vs_vol(X, T):
    rows, sig = [], []
    for u, x in X.items():
        h = x[(x.day >= HOLD)]
        k = h[h.money.isin([0, 1])][["day", "strike", "side"]].drop_duplicates()
        h = h.merge(k, on=["day", "strike", "side"]).sort_values(["day", "strike", "side", "ts"])
        h = h[(h.volume > 0) & (h.close > 0)]
        h["w"] = (h.mn - 555) // 15
        # per contract-day: returns of consecutive prints; attribute each pair to the window of its last print
        g = h.groupby(["day", "strike", "side"], sort=False)
        lc = np.log(h.close.values)
        same = (g.cumcount().values >= 1)
        r = np.where(same, np.r_[np.nan, np.diff(lc)], np.nan)
        h["r"] = r
        h["rl"] = h.groupby(["day", "strike", "side"], sort=False).r.shift(1).values
        h = h[np.isfinite(h.r) & np.isfinite(h.rl)]
        h["cp"] = h.r * h.rl
        # index move per (day, window): spot range / spot
        sp = x[x.day >= HOLD].assign(w=lambda z: (z.mn - 555) // 15).groupby(["day", "w"]).spot.agg(["max", "min", "mean"])
        sp["mv"] = (sp["max"] - sp["min"]) / sp["mean"]
        h = h.join(sp.mv, on=["day", "w"])
        h["q"] = pd.qcut(h.mv.rank(method="first"), 5, labels=False)
        for q, gg in h.groupby("q"):
            rows.append(dict(und=u, move_quintile=int(q) + 1, idx_move_pct_med=100 * gg.mv.median(), pairs=len(gg),
                             roll_hs_pct=100 * roll_hs(gg.cp.sum(), len(gg)), rv_pct=100 * np.sqrt((gg.r ** 2).mean())))
        # signal windows: h23 trades of this index in the holdout; the 1-ITM strike at the entry minute
        t = T[(T.und == u) & (T.day >= HOLD)]
        if t.empty:
            continue
        itm = x[(x.money == 1)][["day", "mn", "side", "strike"]]
        t = t.merge(itm, left_on=["day", "entry_min", "side"], right_on=["day", "mn", "side"], how="inner")
        hh = h.set_index(["day", "strike", "side"]).sort_index()
        S1 = N1 = S2 = N2 = 0.0
        for _, tr in t.iterrows():
            key = (tr.day, tr.strike, tr.side)
            if key not in hh.index:
                continue
            z = hh.loc[key]
            a = z[(z.mn >= tr.entry_min - 5) & (z.mn <= tr.entry_min + 15)]
            S1 += a.cp.sum()
            N1 += len(a)
            S2 += z.cp.sum()
            N2 += len(z)
        sig.append(dict(und=u, trades=len(t), roll_hs_pct_entry_window=100 * roll_hs(S1, N1), pairs_window=int(N1),
                        roll_hs_pct_same_contract_whole_day=100 * roll_hs(S2, N2), pairs_day=int(N2)))
    V = pd.DataFrame(rows)
    V.to_csv(os.path.join(OUT, "roll_vol.csv"), index=False)
    G = pd.DataFrame(sig)
    G.to_csv(os.path.join(OUT, "roll_signal.csv"), index=False)
    print("\nROLL by 15-min index-move quintile (holdout, 1-ITM/ATM monthly strikes, whole day)\n", V.round(4).to_string())
    print("\nROLL around h23 entries (entry-5..entry+15, the 1-ITM strike) vs the same contract-days\n",
          G.round(4).to_string())


def context(X):
    """how calm was 2026-10-06 (day range of the index vs the holdout's days)."""
    for u, x in X.items():
        d = x[x.day >= HOLD].groupby("day").spot.agg(["max", "min", "mean"])
        rg = (d["max"] - d["min"]) / d["mean"]
        if pd.Timestamp(DAY) in rg.index:
            v = rg.loc[pd.Timestamp(DAY)]
            print(f"{u}: 2026-10-06 range (to {x[x.day == pd.Timestamp(DAY)].ts.max().strftime('%H:%M')}) "
                  f"{100 * v:.2f}% vs holdout median {100 * rg.median():.2f}% (percentile {100 * (rg < v).mean():.0f})")


def main():
    os.makedirs(OUT, exist_ok=True)
    snap = snapshot()
    X = {u: load_month(u) for u in ("BANKNIFTY", "MIDCPNIFTY", "FINNIFTY")}
    fresh_roll(snap, X)
    context(X)
    T = pd.read_parquet(os.path.join(SCR, "hunt/h23/trades.parquet"), columns=["und", "day", "side", "entry_min"])
    T["day"] = pd.to_datetime(T.day)
    roll_vs_vol(X, T)


if __name__ == "__main__":
    main()
